package xyz.yychainsaw.portfolio.media.application;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.yychainsaw.portfolio.media.domain.MediaStatus;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRecord;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRepository;
import xyz.yychainsaw.portfolio.media.persistence.MediaVariantRecord;
import xyz.yychainsaw.portfolio.media.persistence.MediaVariantRepository;
import xyz.yychainsaw.portfolio.media.storage.StorageLocation;
import xyz.yychainsaw.portfolio.media.storage.StorageRouter;
import xyz.yychainsaw.portfolio.media.storage.StorageService;
import xyz.yychainsaw.portfolio.media.storage.StoredObject;

@Service
public final class MediaFinalizationService {
    private final MediaAssetRepository assets;
    private final MediaVariantRepository variants;
    private final StorageRouter storageRouter;
    private final StorageObjectVerifier verifier;
    private final ImageVariantGenerator generator;
    private final TransactionTemplate transactions;

    public MediaFinalizationService(
            MediaAssetRepository assets,
            MediaVariantRepository variants,
            StorageRouter storageRouter,
            StorageObjectVerifier verifier,
            ImageVariantGenerator generator,
            TransactionTemplate transactions) {
        this.assets = Objects.requireNonNull(assets, "media asset repository is required");
        this.variants = Objects.requireNonNull(
                variants, "media variant repository is required");
        this.storageRouter = Objects.requireNonNull(storageRouter, "storage router is required");
        this.verifier = Objects.requireNonNull(verifier, "storage verifier is required");
        this.generator = Objects.requireNonNull(generator, "image generator is required");
        this.transactions = Objects.requireNonNull(
                transactions, "media transaction template is required");
    }

    public void finalizeAsset(UUID assetId) throws InterruptedException {
        if (assetId == null
                || TransactionSynchronizationManager.isActualTransactionActive()) {
            throw failure();
        }
        try {
            MediaAssetRecord asset = assets.findById(assetId).orElseThrow(
                    MediaFinalizationService::failure);
            if (asset.status() != MediaStatus.PROCESSING) {
                return;
            }

            StorageService storage = requireStorage(asset);
            String stagingKey = MediaObjectKeys.stagingKey(
                    asset.id(), asset.sha256(), asset.mimeType());
            String originalKey = MediaObjectKeys.originalKey(
                    asset.id(), asset.sha256(), asset.mimeType());
            if (!originalKey.equals(asset.objectKey())) {
                throw failure();
            }

            VerifiedMediaObject original = promoteOriginal(
                    storage, asset, stagingKey, originalKey);
            List<MediaVariantRecord.Insert> expected = new ArrayList<>();
            if ("application/pdf".equals(asset.mimeType())) {
                expected.add(documentVariant(asset));
                closeWithRetry(original);
            } else {
                try {
                    generator.generateEach(original, asset, generated ->
                            expected.add(publishVariant(storage, asset, generated)));
                } catch (InterruptedException interrupted) {
                    closeTwiceBestEffort(original);
                    throw interrupted;
                } catch (RuntimeException generationFailure) {
                    closeTwiceBestEffort(original);
                    throw generationFailure;
                }
                closeWithRetry(original);
            }
            complete(asset, List.copyOf(expected));
        } catch (InterruptedException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw failure();
        }
    }

    private StorageService requireStorage(MediaAssetRecord asset) {
        StorageService storage = storageRouter.require(asset.provider());
        StorageLocation expected = new StorageLocation(
                asset.provider(), asset.bucket(), asset.region());
        if (storage == null
                || storage.provider() != asset.provider()
                || storage.location() == null
                || storage.location().provider() != storage.provider()
                || !expected.equals(storage.location())) {
            throw failure();
        }
        return storage;
    }

    private VerifiedMediaObject promoteOriginal(
            StorageService storage,
            MediaAssetRecord asset,
            String stagingKey,
            String originalKey) {
        boolean originalExistsHint = existsHint(storage, originalKey);
        if (originalExistsHint) {
            VerifiedMediaObject original = verify(storage, originalKey, asset);
            deleteStagingBestEffort(storage, stagingKey);
            return original;
        }
        var readableOriginal = verifier.verifyIfOpenable(
                storage,
                originalKey,
                asset.mimeType(),
                asset.byteSize(),
                asset.sha256());
        if (readableOriginal.isPresent()) {
            VerifiedMediaObject original = readableOriginal.orElseThrow();
            deleteStagingBestEffort(storage, stagingKey);
            return original;
        }

        // A negative hint plus a failed open still leaves staging open as the proof.
        try (VerifiedMediaObject ignored = verify(storage, stagingKey, asset)) {
            // The complete staging read proves the immutable copy source first.
        }
        try {
            storage.copy(stagingKey, originalKey);
        } catch (RuntimeException unknownOutcome) {
            // A full readback below is the only authority after every copy outcome.
        }
        VerifiedMediaObject original = verify(storage, originalKey, asset);
        deleteStagingBestEffort(storage, stagingKey);
        return original;
    }

    private static boolean existsHint(StorageService storage, String key) {
        try {
            return storage.exists(key);
        } catch (RuntimeException unavailableHint) {
            return false;
        }
    }

    private VerifiedMediaObject verify(
            StorageService storage, String key, MediaAssetRecord asset) {
        return verifier.verify(
                storage, key, asset.mimeType(), asset.byteSize(), asset.sha256());
    }

    private MediaVariantRecord.Insert publishVariant(
            StorageService storage,
            MediaAssetRecord asset,
            GeneratedVariant generated) {
        String key = MediaObjectKeys.variantKey(
                asset.id(),
                generated.variantName(),
                generated.sha256(),
                generated.mimeType());
        boolean returnedNormally = false;
        boolean responseMatches = false;
        try {
            StoredObject stored = storage.put(
                    key,
                    generated.openInput(),
                    generated.byteSize(),
                    generated.mimeType());
            returnedNormally = true;
            responseMatches = exactStoredResponse(
                    storage, stored, key, generated.byteSize(), generated.mimeType());
        } catch (IOException | RuntimeException unknownOutcome) {
            if (unknownOutcome instanceof IOException) {
                throw failure();
            }
        }

        try (VerifiedMediaObject ignored = verifier.verify(
                storage,
                key,
                generated.mimeType(),
                generated.byteSize(),
                generated.sha256())) {
            // Mandatory full readback covers apparent success and every unknown outcome.
        }
        if (returnedNormally && !responseMatches) {
            throw failure();
        }
        return new MediaVariantRecord.Insert(
                UUID.randomUUID(),
                asset.id(),
                generated.variantName(),
                generated.format(),
                key,
                generated.mimeType(),
                generated.byteSize(),
                generated.width(),
                generated.height(),
                generated.sha256());
    }

    private static boolean exactStoredResponse(
            StorageService storage,
            StoredObject stored,
            String key,
            long length,
            String mimeType) {
        StorageLocation location = storage.location();
        return stored != null
                && location != null
                && stored.provider() == location.provider()
                && Objects.equals(stored.bucket(), location.bucket())
                && Objects.equals(stored.region(), location.region())
                && key.equals(stored.objectKey())
                && stored.contentLength() == length
                && mimeType.equals(stored.contentType());
    }

    private static MediaVariantRecord.Insert documentVariant(MediaAssetRecord asset) {
        return new MediaVariantRecord.Insert(
                UUID.randomUUID(),
                asset.id(),
                "document",
                "PDF",
                asset.objectKey(),
                asset.mimeType(),
                asset.byteSize(),
                null,
                null,
                asset.sha256());
    }

    private void complete(
            MediaAssetRecord asset, List<MediaVariantRecord.Insert> expected) {
        if (expected.isEmpty()) {
            throw failure();
        }
        AtomicInteger completion = new AtomicInteger(
                TransactionSynchronization.STATUS_UNKNOWN);
        transactions.executeWithoutResult(status -> {
            if (!TransactionSynchronizationManager.isActualTransactionActive()
                    || !TransactionSynchronizationManager.isSynchronizationActive()) {
                throw failure();
            }
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            completion.set(status);
                        }
                    });

            if (!variants.lockAssetGraph(asset.id())) {
                throw failure();
            }

            for (MediaVariantRecord.Insert variant : expected) {
                if (!variants.insertReadyIfAbsent(variant)) {
                    MediaVariantRecord existing = variants.findByAssetAndName(
                                    variant.assetId(), variant.variantName())
                            .orElseThrow(MediaFinalizationService::failure);
                    if (!existing.matches(variant)) {
                        throw failure();
                    }
                }
            }
            requireExactSet(asset.id(), expected);
            if (assets.markReadyIfProcessing(asset.id()) == 1) {
                return;
            }
            MediaAssetRecord current = assets.findById(asset.id()).orElseThrow(
                    MediaFinalizationService::failure);
            requireExactSet(asset.id(), expected);
            if (current.status() != MediaStatus.READY) {
                throw failure();
            }
        });
        if (completion.get() != TransactionSynchronization.STATUS_COMMITTED) {
            throw failure();
        }
    }

    private void requireExactSet(
            UUID assetId, List<MediaVariantRecord.Insert> expected) {
        Map<String, MediaVariantRecord.Insert> byName = new HashMap<>();
        for (MediaVariantRecord.Insert variant : expected) {
            if (byName.putIfAbsent(variant.variantName(), variant) != null) {
                throw failure();
            }
        }
        List<MediaVariantRecord> stored = variants.findByAssetId(assetId);
        if (stored.size() != byName.size()) {
            throw failure();
        }
        for (MediaVariantRecord variant : stored) {
            MediaVariantRecord.Insert match = byName.get(variant.variantName());
            if (match == null || !variant.matches(match)) {
                throw failure();
            }
        }
    }

    private static void deleteStagingBestEffort(
            StorageService storage, String stagingKey) {
        if (storage.provider() == StorageProvider.LOCAL) {
            // The Local reservation journal owns canonical staging deletion and release.
            return;
        }
        try {
            storage.delete(stagingKey);
        } catch (RuntimeException ignored) {
            // The durable stale-staging cleanup owns this already-verified residue.
        }
    }

    private static void closeWithRetry(VerifiedMediaObject original) {
        try {
            original.close();
        } catch (RuntimeException firstFailure) {
            closeTwiceBestEffort(original);
            throw failure();
        }
    }

    private static void closeTwiceBestEffort(VerifiedMediaObject original) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                original.close();
                return;
            } catch (RuntimeException ignored) {
                // Preserve interruption or the fixed primary generation failure.
            }
        }
    }

    private static IllegalStateException failure() {
        return new IllegalStateException("MEDIA_FINALIZATION_FAILED");
    }
}
