package xyz.yychainsaw.portfolio.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.yychainsaw.portfolio.media.domain.MediaStatus;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRecord;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRepository;
import xyz.yychainsaw.portfolio.media.persistence.MediaVariantRecord;
import xyz.yychainsaw.portfolio.media.persistence.MediaVariantRepository;
import xyz.yychainsaw.portfolio.media.storage.ByteRange;
import xyz.yychainsaw.portfolio.media.storage.StorageException;
import xyz.yychainsaw.portfolio.media.storage.StorageLocation;
import xyz.yychainsaw.portfolio.media.storage.StorageRead;
import xyz.yychainsaw.portfolio.media.storage.StorageRouter;
import xyz.yychainsaw.portfolio.media.storage.StorageService;
import xyz.yychainsaw.portfolio.media.storage.StoredObject;

class MediaFinalizationServiceTest {
    private static final UUID ASSET_ID =
            UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final Instant NOW = Instant.parse("2026-07-16T00:00:00Z");
    private static final byte[] PDF =
            "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n%%EOF\n"
                    .getBytes(StandardCharsets.US_ASCII);

    @TempDir Path temporaryDirectory;

    @Test
    void localStagingOnlyPdfIsVerifiedCopiedAndRetainedForJournalCleanup()
            throws Exception {
        MediaAssetRecord asset = pdfAsset(MediaStatus.PROCESSING);
        FakeStorage storage = new FakeStorage();
        storage.store(stagingKey(asset), PDF, asset.mimeType());
        TestContext context = context(asset, storage, TransactionSynchronization.STATUS_COMMITTED);

        context.service.finalizeAsset(ASSET_ID);

        assertThat(storage.copyCalls)
                .containsExactly(stagingKey(asset) + " -> " + asset.objectKey());
        assertThat(storage.openKeys)
                .contains(stagingKey(asset), asset.objectKey());
        assertThat(storage.deleteKeys).isEmpty();
        assertThat(storage.objects).containsKeys(stagingKey(asset), asset.objectKey());
        assertThat(context.assets.state.status()).isEqualTo(MediaStatus.READY);
        assertThat(context.assets.state.version()).isOne();
        assertThat(context.variants.findByAssetId(ASSET_ID))
                .singleElement()
                .satisfies(variant -> {
                    assertThat(variant.variantName()).isEqualTo("document");
                    assertThat(variant.format()).isEqualTo("PDF");
                    assertThat(variant.objectKey()).isEqualTo(asset.objectKey());
                    assertThat(variant.sha256()).isEqualTo(asset.sha256());
                    assertThat(variant.width()).isNull();
                    assertThat(variant.height()).isNull();
                });
        assertThat(storage.anyOperationObservedTransaction).isFalse();
    }

    @Test
    void remoteStagingIsDeletedBestEffortAfterTheOriginalFullyVerifies()
            throws Exception {
        MediaAssetRecord asset = cosAsset(MediaStatus.PROCESSING);
        FakeStorage storage = new FakeStorage();
        storage.location = new StorageLocation(
                StorageProvider.TENCENT_COS, asset.bucket(), asset.region());
        storage.store(stagingKey(asset), PDF, asset.mimeType());
        TestContext context = context(asset, storage, TransactionSynchronization.STATUS_COMMITTED);

        context.service.finalizeAsset(ASSET_ID);

        assertThat(storage.deleteKeys).containsExactly(stagingKey(asset));
        assertThat(storage.objects)
                .containsKey(asset.objectKey())
                .doesNotContainKey(stagingKey(asset));
        assertThat(context.assets.state.status()).isEqualTo(MediaStatus.READY);
    }

    @Test
    void copyUnknownOutcomeConvergesOnlyWhenTheOriginalFullyVerifies() throws Exception {
        MediaAssetRecord asset = pdfAsset(MediaStatus.PROCESSING);
        FakeStorage resolved = new FakeStorage();
        resolved.store(stagingKey(asset), PDF, asset.mimeType());
        resolved.copyMode = CopyMode.STORE_THEN_THROW;
        TestContext converged = context(
                asset, resolved, TransactionSynchronization.STATUS_COMMITTED);

        converged.service.finalizeAsset(ASSET_ID);
        assertThat(converged.assets.state.status()).isEqualTo(MediaStatus.READY);

        FakeStorage unresolved = new FakeStorage();
        unresolved.store(stagingKey(asset), PDF, asset.mimeType());
        unresolved.copyMode = CopyMode.THROW_WITHOUT_STORE;
        TestContext failed = context(
                asset, unresolved, TransactionSynchronization.STATUS_COMMITTED);

        assertFixedFailure(() -> failed.service.finalizeAsset(ASSET_ID));
        assertThat(failed.assets.state.status()).isEqualTo(MediaStatus.PROCESSING);
        assertThat(unresolved.deleteKeys).isEmpty();
        assertThat(unresolved.objects).containsKey(stagingKey(asset));
    }

    @Test
    void falseExistsHintsNeverHideAnExactReadableOriginalOrStaging()
            throws Exception {
        MediaAssetRecord asset = pdfAsset(MediaStatus.PROCESSING);
        FakeStorage originalReadable = new FakeStorage();
        originalReadable.forceExistsFalse = true;
        originalReadable.store(asset.objectKey(), PDF, asset.mimeType());
        TestContext originalContext = context(
                asset, originalReadable, TransactionSynchronization.STATUS_COMMITTED);

        originalContext.service.finalizeAsset(ASSET_ID);

        assertThat(originalReadable.openKeys).contains(asset.objectKey());
        assertThat(originalReadable.copyCalls).isEmpty();
        assertThat(originalContext.assets.state.status()).isEqualTo(MediaStatus.READY);

        FakeStorage stagingReadable = new FakeStorage();
        stagingReadable.forceExistsFalse = true;
        stagingReadable.store(stagingKey(asset), PDF, asset.mimeType());
        TestContext stagingContext = context(
                asset, stagingReadable, TransactionSynchronization.STATUS_COMMITTED);

        stagingContext.service.finalizeAsset(ASSET_ID);

        assertThat(stagingReadable.openKeys)
                .contains(stagingKey(asset), asset.objectKey());
        assertThat(stagingReadable.copyCalls)
                .containsExactly(stagingKey(asset) + " -> " + asset.objectKey());
        assertThat(stagingContext.assets.state.status()).isEqualTo(MediaStatus.READY);
    }

    @Test
    void lyingFalseHintCanNeverOverwriteAMismatchedOriginal() throws Exception {
        MediaAssetRecord asset = pdfAsset(MediaStatus.PROCESSING);
        FakeStorage storage = new FakeStorage();
        storage.forceExistsFalse = true;
        byte[] mismatched = "wrong".getBytes(StandardCharsets.US_ASCII);
        storage.store(asset.objectKey(), mismatched, asset.mimeType());
        storage.store(stagingKey(asset), PDF, asset.mimeType());
        TestContext context = context(
                asset, storage, TransactionSynchronization.STATUS_COMMITTED);

        assertFixedFailure(() -> context.service.finalizeAsset(ASSET_ID));

        assertThat(storage.objects.get(asset.objectKey()).bytes)
                .containsExactly(mismatched);
        assertThat(storage.copyCalls).isEmpty();
        assertThat(storage.deleteKeys).isEmpty();
        assertThat(context.assets.state.status()).isEqualTo(MediaStatus.PROCESSING);
    }

    @Test
    void existingOriginalMismatchFailsClosedWithoutCopyOrDelete() throws Exception {
        MediaAssetRecord asset = pdfAsset(MediaStatus.PROCESSING);
        FakeStorage storage = new FakeStorage();
        storage.store(asset.objectKey(), "wrong".getBytes(StandardCharsets.US_ASCII), asset.mimeType());
        storage.store(stagingKey(asset), PDF, asset.mimeType());
        TestContext context = context(asset, storage, TransactionSynchronization.STATUS_COMMITTED);

        assertFixedFailure(() -> context.service.finalizeAsset(ASSET_ID));

        assertThat(storage.copyCalls).isEmpty();
        assertThat(storage.deleteKeys).isEmpty();
        assertThat(context.assets.state.status()).isEqualTo(MediaStatus.PROCESSING);
        assertThat(context.variants.findByAssetId(ASSET_ID)).isEmpty();
    }

    @Test
    void imagePutSuccessIsStillOpenedAndHashedBeforeReady() throws Exception {
        byte[] png = png(8, 4);
        MediaAssetRecord asset = imageAsset(png, 8, 4, MediaStatus.PROCESSING);
        FakeStorage storage = new FakeStorage();
        storage.store(stagingKey(asset), png, asset.mimeType());
        TestContext context = context(asset, storage, TransactionSynchronization.STATUS_COMMITTED);

        context.service.finalizeAsset(ASSET_ID);

        assertThat(storage.putKeys).singleElement().asString().startsWith(
                "variants/" + ASSET_ID + "/w8/");
        String variantKey = storage.putKeys.get(0);
        assertThat(storage.openKeys).contains(variantKey);
        assertThat(storage.putInputCloseCalls).isOne();
        assertThat(context.variants.findByAssetId(ASSET_ID))
                .extracting(MediaVariantRecord::variantName)
                .containsExactly("w8");
        assertThat(context.assets.state.status()).isEqualTo(MediaStatus.READY);
        assertThat(mediaTemporaryFiles()).isEmpty();
    }

    @Test
    void apparentPutSuccessWithCorruptStoredBytesNeverCreatesRowsOrReady()
            throws Exception {
        byte[] png = png(8, 4);
        MediaAssetRecord asset = imageAsset(png, 8, 4, MediaStatus.PROCESSING);
        FakeStorage storage = new FakeStorage();
        storage.store(stagingKey(asset), png, asset.mimeType());
        storage.putMode = PutMode.RETURN_SUCCESS_BUT_CORRUPT;
        TestContext context = context(asset, storage, TransactionSynchronization.STATUS_COMMITTED);

        assertFixedFailure(() -> context.service.finalizeAsset(ASSET_ID));

        assertThat(storage.putKeys).hasSize(1);
        assertThat(storage.openKeys).contains(storage.putKeys.get(0));
        assertThat(context.variants.findByAssetId(ASSET_ID)).isEmpty();
        assertThat(context.assets.state.status()).isEqualTo(MediaStatus.PROCESSING);
        assertThat(mediaTemporaryFiles()).isEmpty();
    }

    @Test
    void unknownVariantPutOutcomeConvergesOnlyAfterExactFullReadback() throws Exception {
        byte[] png = png(8, 4);
        MediaAssetRecord asset = imageAsset(png, 8, 4, MediaStatus.PROCESSING);
        FakeStorage storage = new FakeStorage();
        storage.store(stagingKey(asset), png, asset.mimeType());
        storage.putMode = PutMode.STORE_THEN_THROW;
        TestContext context = context(asset, storage, TransactionSynchronization.STATUS_COMMITTED);

        context.service.finalizeAsset(ASSET_ID);

        assertThat(storage.openKeys).contains(storage.putKeys.get(0));
        assertThat(context.assets.state.status()).isEqualTo(MediaStatus.READY);
    }

    @Test
    void exactVariantSetIsRequiredBeforeCasAndExtraRowsRollBackCompletion()
            throws Exception {
        MediaAssetRecord asset = pdfAsset(MediaStatus.PROCESSING);
        FakeStorage storage = new FakeStorage();
        storage.store(asset.objectKey(), PDF, asset.mimeType());
        TestContext context = context(asset, storage, TransactionSynchronization.STATUS_COMMITTED);
        context.variants.rows.put("w1", imageVariant(asset.id(), "w1", "c".repeat(64), 1, 1));

        assertFixedFailure(() -> context.service.finalizeAsset(ASSET_ID));

        assertThat(context.assets.markReadyCalls).isZero();
        assertThat(context.assets.state.status()).isEqualTo(MediaStatus.PROCESSING);
    }

    @Test
    void casZeroAcceptsOnlyReadyWithTheExactImmutableSet() throws Exception {
        MediaAssetRecord asset = pdfAsset(MediaStatus.PROCESSING);
        FakeStorage acceptedStorage = new FakeStorage();
        acceptedStorage.store(asset.objectKey(), PDF, asset.mimeType());
        TestContext accepted = context(
                asset, acceptedStorage, TransactionSynchronization.STATUS_COMMITTED);
        accepted.assets.casRace = MediaStatus.READY;

        accepted.service.finalizeAsset(ASSET_ID);
        assertThat(accepted.assets.state.status()).isEqualTo(MediaStatus.READY);

        FakeStorage rejectedStorage = new FakeStorage();
        rejectedStorage.store(asset.objectKey(), PDF, asset.mimeType());
        TestContext rejected = context(
                asset, rejectedStorage, TransactionSynchronization.STATUS_COMMITTED);
        rejected.assets.casRace = MediaStatus.FAILED;

        assertFixedFailure(() -> rejected.service.finalizeAsset(ASSET_ID));
    }

    @Test
    void normalTransactionReturnStillFailsUnlessAfterCompletionIsExactlyCommitted()
            throws Exception {
        for (int completion : List.of(
                TransactionSynchronization.STATUS_ROLLED_BACK,
                TransactionSynchronization.STATUS_UNKNOWN)) {
            MediaAssetRecord asset = pdfAsset(MediaStatus.PROCESSING);
            FakeStorage storage = new FakeStorage();
            storage.store(asset.objectKey(), PDF, asset.mimeType());
            TestContext context = context(asset, storage, completion);

            assertFixedFailure(() -> context.service.finalizeAsset(ASSET_ID));
            assertThat(storage.objects).containsKey(asset.objectKey());
        }
    }

    @Test
    void completionRequiresAnActualTransactionBeforeGraphLockOrMutation()
            throws Exception {
        MediaAssetRecord asset = pdfAsset(MediaStatus.PROCESSING);
        FakeStorage storage = new FakeStorage();
        storage.store(asset.objectKey(), PDF, asset.mimeType());
        FakeAssetRepository assets = new FakeAssetRepository(asset);
        FakeVariantRepository variants = new FakeVariantRepository();
        StorageRouter router = mock(StorageRouter.class);
        when(router.require(asset.provider())).thenReturn(storage);
        MediaFinalizationService service = service(
                assets,
                variants,
                router,
                new CompletionStatusTemplate(
                        TransactionSynchronization.STATUS_COMMITTED, false, true));

        assertFixedFailure(() -> service.finalizeAsset(ASSET_ID));

        assertThat(variants.graphLockCalls).isZero();
        assertThat(variants.rows).isEmpty();
        assertThat(assets.markReadyCalls).isZero();
    }

    @Test
    void missingAfterCompletionAcknowledgementAlwaysRetries() throws Exception {
        MediaAssetRecord asset = pdfAsset(MediaStatus.PROCESSING);
        FakeStorage storage = new FakeStorage();
        storage.store(asset.objectKey(), PDF, asset.mimeType());
        FakeAssetRepository assets = new FakeAssetRepository(asset);
        FakeVariantRepository variants = new FakeVariantRepository();
        StorageRouter router = mock(StorageRouter.class);
        when(router.require(asset.provider())).thenReturn(storage);
        MediaFinalizationService service = service(
                assets,
                variants,
                router,
                new CompletionStatusTemplate(
                        TransactionSynchronization.STATUS_COMMITTED, true, false));

        assertFixedFailure(() -> service.finalizeAsset(ASSET_ID));

        assertThat(storage.objects).containsKey(asset.objectKey());
    }

    @Test
    void terminalStatesAreNoOpAndMissingOrWrongLocationFailsWithoutStorageIo()
            throws Exception {
        for (MediaStatus terminal : List.of(
                MediaStatus.READY,
                MediaStatus.FAILED,
                MediaStatus.ARCHIVED,
                MediaStatus.PENDING_DELETE)) {
            MediaAssetRecord asset = terminal == MediaStatus.ARCHIVED
                    || terminal == MediaStatus.PENDING_DELETE
                    ? archivedPdfAsset(terminal)
                    : pdfAsset(terminal);
            FakeStorage storage = new FakeStorage();
            TestContext context = context(
                    asset, storage, TransactionSynchronization.STATUS_COMMITTED);
            context.service.finalizeAsset(ASSET_ID);
            assertThat(storage.operationCount()).isZero();
        }

        StorageRouter missingRouter = mock(StorageRouter.class);
        FakeAssetRepository missingAssets = new FakeAssetRepository(null);
        MediaFinalizationService missing = service(
                missingAssets,
                new FakeVariantRepository(),
                missingRouter,
                new CompletionStatusTemplate(TransactionSynchronization.STATUS_COMMITTED));
        assertFixedFailure(() -> missing.finalizeAsset(ASSET_ID));
        verifyNoInteractions(missingRouter);

        MediaAssetRecord asset = pdfAsset(MediaStatus.PROCESSING);
        FakeStorage wrong = new FakeStorage();
        wrong.location = new StorageLocation(
                StorageProvider.TENCENT_COS, "other-bucket", "ap-other");
        StorageRouter wrongRouter = mock(StorageRouter.class);
        when(wrongRouter.require(StorageProvider.LOCAL)).thenReturn(wrong);
        MediaFinalizationService mismatch = service(
                new FakeAssetRepository(asset),
                new FakeVariantRepository(),
                wrongRouter,
                new CompletionStatusTemplate(TransactionSynchronization.STATUS_COMMITTED));
        assertFixedFailure(() -> mismatch.finalizeAsset(ASSET_ID));
        assertThat(wrong.operationCount()).isZero();
    }

    @Test
    void ambientTransactionIsRejectedBeforeRepositoryOrStorageAccess() {
        FakeAssetRepository assets = new FakeAssetRepository(pdfAsset(MediaStatus.PROCESSING));
        StorageRouter router = mock(StorageRouter.class);
        MediaFinalizationService service = service(
                assets,
                new FakeVariantRepository(),
                router,
                new CompletionStatusTemplate(TransactionSynchronization.STATUS_COMMITTED));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertFixedFailure(() -> service.finalizeAsset(ASSET_ID));
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        assertThat(assets.findCalls).isZero();
        verifyNoInteractions(router);
    }

    private TestContext context(MediaAssetRecord asset, FakeStorage storage, int completion) {
        FakeAssetRepository assets = new FakeAssetRepository(asset);
        FakeVariantRepository variants = new FakeVariantRepository();
        StorageRouter router = mock(StorageRouter.class);
        when(router.require(asset.provider())).thenReturn(storage);
        MediaFinalizationService service = service(
                assets, variants, router, new CompletionStatusTemplate(completion));
        return new TestContext(service, assets, variants);
    }

    private MediaFinalizationService service(
            FakeAssetRepository assets,
            FakeVariantRepository variants,
            StorageRouter router,
            TransactionTemplate transactions) {
        return new MediaFinalizationService(
                assets,
                variants,
                router,
                new StorageObjectVerifier(temporaryDirectory),
                new ImageVariantGenerator(temporaryDirectory),
                transactions);
    }

    private static void assertFixedFailure(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("MEDIA_FINALIZATION_FAILED")
                .hasNoCause()
                .satisfies(failure -> assertThat(failure.getSuppressed()).isEmpty());
    }

    private static MediaAssetRecord pdfAsset(MediaStatus status) {
        return asset(PDF, "application/pdf", null, null, status, null);
    }

    private static MediaAssetRecord cosAsset(MediaStatus status) {
        MediaAssetRecord local = pdfAsset(status);
        return new MediaAssetRecord(
                local.id(),
                StorageProvider.TENCENT_COS,
                "portfolio-media-1250000000",
                "ap-guangzhou",
                local.objectKey(),
                local.originalFilename(),
                local.mimeType(),
                local.byteSize(),
                local.width(),
                local.height(),
                local.sha256(),
                local.status(),
                local.archivedAt(),
                local.version(),
                local.createdAt(),
                local.updatedAt());
    }

    private static MediaAssetRecord archivedPdfAsset(MediaStatus status) {
        return asset(PDF, "application/pdf", null, null, status, NOW);
    }

    private static MediaAssetRecord imageAsset(
            byte[] bytes, int width, int height, MediaStatus status) {
        return asset(bytes, "image/png", width, height, status, null);
    }

    private static MediaAssetRecord asset(
            byte[] bytes,
            String mimeType,
            Integer width,
            Integer height,
            MediaStatus status,
            Instant archivedAt) {
        String sha = sha256(bytes);
        return new MediaAssetRecord(
                ASSET_ID,
                StorageProvider.LOCAL,
                null,
                null,
                MediaObjectKeys.originalKey(ASSET_ID, sha, mimeType),
                "source",
                mimeType,
                bytes.length,
                width,
                height,
                sha,
                status,
                archivedAt,
                0,
                NOW,
                NOW);
    }

    private static MediaAssetRecord withStatus(MediaAssetRecord source, MediaStatus status) {
        return new MediaAssetRecord(
                source.id(), source.provider(), source.bucket(), source.region(),
                source.objectKey(), source.originalFilename(), source.mimeType(),
                source.byteSize(), source.width(), source.height(), source.sha256(),
                status,
                status == MediaStatus.ARCHIVED || status == MediaStatus.PENDING_DELETE
                        ? NOW : null,
                source.version() + 1,
                source.createdAt(), NOW);
    }

    private static String stagingKey(MediaAssetRecord asset) {
        return MediaObjectKeys.stagingKey(
                asset.id(), asset.sha256(), asset.mimeType());
    }

    private static MediaVariantRecord imageVariant(
            UUID assetId, String name, String sha, int width, int height) {
        return stored(new MediaVariantRecord.Insert(
                UUID.randomUUID(),
                assetId,
                name,
                "JPEG",
                MediaObjectKeys.variantKey(assetId, name, sha, "image/jpeg"),
                "image/jpeg",
                1,
                width,
                height,
                sha));
    }

    private static MediaVariantRecord stored(MediaVariantRecord.Insert insert) {
        return new MediaVariantRecord(
                insert.id(), insert.assetId(), insert.variantName(), insert.format(),
                insert.objectKey(), insert.mimeType(), insert.byteSize(), insert.width(),
                insert.height(), insert.sha256(), "READY", NOW);
    }

    private static byte[] png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(10, 20, 30, 77));
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, "png", output)).isTrue();
        return output.toByteArray();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private List<Path> mediaTemporaryFiles() throws IOException {
        try (var paths = Files.list(temporaryDirectory)) {
            return paths.toList();
        }
    }

    private record TestContext(
            MediaFinalizationService service,
            FakeAssetRepository assets,
            FakeVariantRepository variants) {}

    private static final class FakeAssetRepository extends MediaAssetRepository {
        private MediaAssetRecord state;
        private MediaStatus casRace;
        private int findCalls;
        private int markReadyCalls;

        private FakeAssetRepository(MediaAssetRecord state) {
            super(mock(JdbcClient.class));
            this.state = state;
        }

        @Override
        public Optional<MediaAssetRecord> findById(UUID id) {
            findCalls++;
            assertThat(id).isEqualTo(ASSET_ID);
            return Optional.ofNullable(state);
        }

        @Override
        public int markReadyIfProcessing(UUID id) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            markReadyCalls++;
            if (casRace != null) {
                state = withStatus(state, casRace);
                return 0;
            }
            if (state.status() != MediaStatus.PROCESSING) {
                return 0;
            }
            state = withStatus(state, MediaStatus.READY);
            return 1;
        }

        @Override
        public int markFailedIfProcessing(UUID id) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            if (state != null && state.status() == MediaStatus.PROCESSING) {
                state = withStatus(state, MediaStatus.FAILED);
                return 1;
            }
            return 0;
        }
    }

    private static final class FakeVariantRepository extends MediaVariantRepository {
        private final Map<String, MediaVariantRecord> rows = new HashMap<>();
        private int graphLockCalls;

        private FakeVariantRepository() {
            super(mock(JdbcClient.class));
        }

        @Override
        public boolean lockAssetGraph(UUID assetId) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            graphLockCalls++;
            return assetId.equals(ASSET_ID);
        }

        @Override
        public boolean insertReadyIfAbsent(MediaVariantRecord.Insert insert) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            if (rows.containsKey(insert.variantName())) {
                return false;
            }
            rows.put(insert.variantName(), stored(insert));
            return true;
        }

        @Override
        public Optional<MediaVariantRecord> findByAssetAndName(
                UUID assetId, String variantName) {
            return Optional.ofNullable(rows.get(variantName));
        }

        @Override
        public List<MediaVariantRecord> findByAssetId(UUID assetId) {
            return rows.values().stream()
                    .sorted(Comparator.comparing(MediaVariantRecord::variantName))
                    .toList();
        }
    }

    private static final class CompletionStatusTemplate extends TransactionTemplate {
        private final int completionStatus;
        private final boolean actualTransaction;
        private final boolean invokeAfterCompletion;

        private CompletionStatusTemplate(int completionStatus) {
            this(completionStatus, true, true);
        }

        private CompletionStatusTemplate(
                int completionStatus,
                boolean actualTransaction,
                boolean invokeAfterCompletion) {
            this.completionStatus = completionStatus;
            this.actualTransaction = actualTransaction;
            this.invokeAfterCompletion = invokeAfterCompletion;
        }

        @Override
        public <T> T execute(TransactionCallback<T> action) throws TransactionException {
            TransactionSynchronizationManager.initSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(actualTransaction);
            try {
                T result = action.doInTransaction(new SimpleTransactionStatus());
                List<TransactionSynchronization> synchronizations =
                        TransactionSynchronizationManager.getSynchronizations();
                synchronizations.forEach(TransactionSynchronization::beforeCompletion);
                if (invokeAfterCompletion) {
                    synchronizations.forEach(sync -> sync.afterCompletion(completionStatus));
                }
                return result;
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
                TransactionSynchronizationManager.setActualTransactionActive(false);
            }
        }
    }

    private enum CopyMode {
        SUCCESS,
        STORE_THEN_THROW,
        THROW_WITHOUT_STORE
    }

    private enum PutMode {
        SUCCESS,
        STORE_THEN_THROW,
        RETURN_SUCCESS_BUT_CORRUPT
    }

    private static final class FakeStorage implements StorageService {
        private final Map<String, StoredBytes> objects = new HashMap<>();
        private final List<String> openKeys = new ArrayList<>();
        private final List<String> copyCalls = new ArrayList<>();
        private final List<String> deleteKeys = new ArrayList<>();
        private final List<String> putKeys = new ArrayList<>();
        private StorageLocation location =
                new StorageLocation(StorageProvider.LOCAL, null, null);
        private CopyMode copyMode = CopyMode.SUCCESS;
        private PutMode putMode = PutMode.SUCCESS;
        private int putInputCloseCalls;
        private boolean anyOperationObservedTransaction;
        private boolean forceExistsFalse;

        private void store(String key, byte[] bytes, String mime) {
            objects.put(key, new StoredBytes(bytes.clone(), mime));
        }

        @Override
        public StorageProvider provider() {
            return location.provider();
        }

        @Override
        public StorageLocation location() {
            return location;
        }

        @Override
        public StoredObject put(
                String objectKey, InputStream input, long contentLength, String contentType) {
            observeTransaction();
            putKeys.add(objectKey);
            byte[] bytes;
            try (input) {
                bytes = input.readAllBytes();
                putInputCloseCalls++;
            } catch (IOException exception) {
                throw new StorageException("TEST_PUT_FAILED");
            }
            assertThat(bytes).hasSize((int) contentLength);
            byte[] stored = putMode == PutMode.RETURN_SUCCESS_BUT_CORRUPT
                    ? new byte[] {1}
                    : bytes;
            store(objectKey, stored, contentType);
            if (putMode == PutMode.STORE_THEN_THROW) {
                throw new StorageException("TEST_UNKNOWN_PUT");
            }
            return new StoredObject(
                    location.provider(), location.bucket(), location.region(), objectKey,
                    contentLength, contentType, "test-etag");
        }

        @Override
        public StorageRead open(String objectKey, Optional<ByteRange> range) {
            observeTransaction();
            openKeys.add(objectKey);
            StoredBytes stored = objects.get(objectKey);
            if (stored == null) {
                throw new StorageException("TEST_NOT_FOUND");
            }
            return new StorageRead(
                    new ByteArrayInputStream(stored.bytes),
                    stored.bytes.length,
                    Optional.empty(),
                    stored.bytes.length,
                    stored.mime,
                    "test-etag");
        }

        @Override
        public boolean exists(String objectKey) {
            observeTransaction();
            return !forceExistsFalse && objects.containsKey(objectKey);
        }

        @Override
        public void copy(String sourceKey, String targetKey) {
            observeTransaction();
            copyCalls.add(sourceKey + " -> " + targetKey);
            if (objects.containsKey(targetKey)) {
                throw new StorageException("TEST_ALREADY_EXISTS");
            }
            if (copyMode != CopyMode.THROW_WITHOUT_STORE) {
                StoredBytes source = objects.get(sourceKey);
                if (source == null) {
                    throw new StorageException("TEST_NOT_FOUND");
                }
                store(targetKey, source.bytes, source.mime);
            }
            if (copyMode != CopyMode.SUCCESS) {
                throw new StorageException("TEST_UNKNOWN_COPY");
            }
        }

        @Override
        public void delete(String objectKey) {
            observeTransaction();
            deleteKeys.add(objectKey);
            objects.remove(objectKey);
        }

        @Override
        public URI signedGet(String objectKey, Duration ttl) {
            throw new UnsupportedOperationException();
        }

        private int operationCount() {
            return openKeys.size() + copyCalls.size() + deleteKeys.size() + putKeys.size();
        }

        private void observeTransaction() {
            anyOperationObservedTransaction |=
                    TransactionSynchronizationManager.isActualTransactionActive();
        }

        private record StoredBytes(byte[] bytes, String mime) {}
    }
}
