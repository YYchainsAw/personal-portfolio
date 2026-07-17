package xyz.yychainsaw.portfolio.media.staging;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.yychainsaw.portfolio.media.application.MediaObjectKeys;
import xyz.yychainsaw.portfolio.media.domain.MediaStatus;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRecord;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRepository;
import xyz.yychainsaw.portfolio.media.storage.LocalPublicationAuthorization;
import xyz.yychainsaw.portfolio.media.storage.LocalPublicationFence;
import xyz.yychainsaw.portfolio.media.storage.LocalStagingPublication;
import xyz.yychainsaw.portfolio.media.storage.LocalStorageService;
import xyz.yychainsaw.portfolio.media.storage.ReservedStagingCleanupResult;
import xyz.yychainsaw.portfolio.media.storage.StorageException;
import xyz.yychainsaw.portfolio.media.storage.StorageLocation;

@Component
@ConditionalOnWebApplication(type = Type.SERVLET)
public final class TransactionalLocalStagingObjectCleanup
        implements LocalStagingObjectCleanupPort, LocalStagingKnownRollbackCleanup {
    private static final String FAILED = "LOCAL_STAGING_EXACT_CLEANUP_FAILED";
    private static final String FENCE_TIMEOUT = "LOCAL_PUBLICATION_FENCE_TIMEOUT";
    private static final int TRANSACTION_TIMEOUT_SECONDS = 30;
    private static final StorageLocation LOCAL_LOCATION =
            new StorageLocation(StorageProvider.LOCAL, null, null);

    private final LocalStorageService storage;
    private final LocalPublicationFence fence;
    private final LocalStagingReservationRepository reservations;
    private final MediaAssetRepository assets;
    private final TransactionTemplate requiresNew;

    public TransactionalLocalStagingObjectCleanup(
            LocalStorageService storage,
            LocalPublicationFence fence,
            LocalStagingReservationRepository reservations,
            MediaAssetRepository assets,
            PlatformTransactionManager transactionManager) {
        this.storage = Objects.requireNonNull(storage, "local storage is required");
        this.fence = Objects.requireNonNull(fence, "local publication fence is required");
        this.reservations = Objects.requireNonNull(
                reservations, "local staging reservations are required");
        this.assets = Objects.requireNonNull(assets, "media assets are required");
        this.requiresNew = new TransactionTemplate(Objects.requireNonNull(
                transactionManager, "transaction manager is required"));
        this.requiresNew.setName("local-staging-exact-cleanup");
        this.requiresNew.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.requiresNew.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.requiresNew.setTimeout(TRANSACTION_TIMEOUT_SECONDS);
    }

    @Override
    public LocalStagingObjectCleanupResult cleanup(
            LocalStagingReservation reservation, Optional<MediaAssetRecord> asset) {
        LocalStagingReservation expected = Objects.requireNonNull(
                reservation, "local staging reservation is required");
        Optional<MediaAssetRecord> preliminary = Objects.requireNonNull(
                asset, "media asset observation is required");
        preliminary.ifPresent(record -> requireExactAssetIdentity(expected, record));

        LocalPublicationAuthorization authorization;
        try {
            authorization = Objects.requireNonNull(
                    fence.acquire(publication(expected)),
                    "local publication authorization is required");
        } catch (StorageException busy) {
            if (FENCE_TIMEOUT.equals(busy.code())) {
                return LocalStagingObjectCleanupResult.DEFERRED;
            }
            throw fixed();
        } catch (RuntimeException failure) {
            throw fixed();
        }

        try (authorization) {
            return reconcile(authorization, expected, CleanupAge.OLDER_THAN_24_HOURS);
        } catch (RuntimeException failure) {
            throw fixed();
        }
    }

    @Override
    public boolean cleanupKnownRollback(
            LocalPublicationAuthorization authorization,
            LocalStagingReservation reservation) {
        LocalPublicationAuthorization held = Objects.requireNonNull(
                authorization, "local publication authorization is required");
        LocalStagingReservation expected = Objects.requireNonNull(
                reservation, "local staging reservation is required");
        try {
            return reconcile(held, expected, CleanupAge.DATABASE_NOW)
                    == LocalStagingObjectCleanupResult.CLEANED;
        } catch (RuntimeException failure) {
            throw fixed();
        }
    }

    private LocalStagingObjectCleanupResult reconcile(
            LocalPublicationAuthorization authorization,
            LocalStagingReservation expected,
            CleanupAge cleanupAge) {
        AtomicInteger completion = new AtomicInteger(
                TransactionSynchronization.STATUS_UNKNOWN);
        LocalStagingObjectCleanupResult result;
        try {
            result = requiresNew.execute(status -> {
                registerCompletion(completion);
                reservations.acquireCapacityLock();
                LocalStagingReservation current = reservations
                        .findByAssetIdForUpdate(expected.assetId())
                        .orElseThrow(TransactionalLocalStagingObjectCleanup::fixed);
                if (!current.equals(expected)) {
                    throw fixed();
                }
                authorization.reauthenticateVolume(storage.volumeId());

                Optional<MediaAssetRecord> lockedAsset = assets.findById(expected.assetId());
                if (lockedAsset.isPresent()
                        && lockedAsset.get().status() == MediaStatus.PROCESSING) {
                    return LocalStagingObjectCleanupResult.DEFERRED;
                }
                lockedAsset.ifPresent(record -> requireExactAssetIdentity(expected, record));

                OffsetDateTime databaseNow = requireDatabaseNow(reservations.databaseNow());
                if (cleanupAge == CleanupAge.OLDER_THAN_24_HOURS
                        && databaseNow.isBefore(expected.cleanupAfter())) {
                    return LocalStagingObjectCleanupResult.DEFERRED;
                }
                Instant cutoff = cleanupAge.cutoff(databaseNow);
                ReservedStagingCleanupResult storageResult =
                        storage.cleanupReservedStaging(
                                authorization,
                                expected.assetId(),
                                expected.sha256(),
                                expected.mimeType(),
                                cutoff);
                if (storageResult == ReservedStagingCleanupResult.DEFERRED) {
                    return LocalStagingObjectCleanupResult.DEFERRED;
                }
                if (storageResult != ReservedStagingCleanupResult.CLEANED) {
                    throw fixed();
                }

                authorization.reauthenticateVolume(storage.volumeId());
                if (!reservations.deleteExact(expected)) {
                    throw fixed();
                }
                return LocalStagingObjectCleanupResult.CLEANED;
            });
        } catch (RuntimeException failure) {
            throw fixed();
        }
        if (completion.get() != TransactionSynchronization.STATUS_COMMITTED
                || result == null) {
            throw fixed();
        }
        return result;
    }

    private static LocalStagingPublication publication(
            LocalStagingReservation reservation) {
        String objectKey = "staging/"
                + reservation.assetId()
                + '/'
                + reservation.sha256()
                + '.'
                + extension(reservation.mimeType());
        return new LocalStagingPublication(
                reservation.assetId(),
                objectKey,
                reservation.sha256(),
                reservation.mimeType(),
                LOCAL_LOCATION,
                reservation.generation(),
                reservation.cleanupJobId());
    }

    private static void requireExactAssetIdentity(
            LocalStagingReservation reservation, MediaAssetRecord asset) {
        String expectedOriginal = MediaObjectKeys.originalKey(
                reservation.assetId(), reservation.sha256(), reservation.mimeType());
        if (!asset.id().equals(reservation.assetId())
                || asset.provider() != StorageProvider.LOCAL
                || asset.bucket() != null
                || asset.region() != null
                || !asset.objectKey().equals(expectedOriginal)
                || !asset.sha256().equals(reservation.sha256())
                || !asset.mimeType().equals(reservation.mimeType())) {
            throw fixed();
        }
    }

    private static OffsetDateTime requireDatabaseNow(OffsetDateTime value) {
        if (value == null || !ZoneOffset.UTC.equals(value.getOffset())) {
            throw fixed();
        }
        return value;
    }

    private static String extension(String mimeType) {
        return switch (mimeType) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "application/pdf" -> "pdf";
            default -> throw fixed();
        };
    }

    private static void registerCompletion(AtomicInteger completion) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw fixed();
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        completion.set(status);
                    }
                });
    }

    private static IllegalStateException fixed() {
        return new IllegalStateException(FAILED);
    }

    private enum CleanupAge {
        OLDER_THAN_24_HOURS {
            @Override
            Instant cutoff(OffsetDateTime databaseNow) {
                try {
                    return databaseNow.minusHours(24).toInstant();
                } catch (DateTimeException | ArithmeticException invalid) {
                    throw fixed();
                }
            }
        },
        DATABASE_NOW {
            @Override
            Instant cutoff(OffsetDateTime databaseNow) {
                return databaseNow.toInstant();
            }
        };

        abstract Instant cutoff(OffsetDateTime databaseNow);
    }
}
