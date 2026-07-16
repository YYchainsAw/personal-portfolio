package xyz.yychainsaw.portfolio.media.staging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
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

class TransactionalLocalStagingObjectCleanupTest {
    private static final UUID ASSET_ID =
            UUID.fromString("11111111-2222-4333-8444-555555555555");
    private static final UUID JOB_ID =
            UUID.fromString("77777777-8888-4999-aaaa-bbbbbbbbbbbb");
    private static final String SHA256 = "a".repeat(64);
    private static final String VOLUME_ID = "c".repeat(64);
    private static final OffsetDateTime RESERVED_AT =
            OffsetDateTime.parse("2026-07-15T00:00:00Z");
    private static final OffsetDateTime DATABASE_NOW =
            OffsetDateTime.parse("2026-07-17T00:00:00Z");

    @Test
    void oldMissingAssetCleansAndDeletesTheExactReservationInOneOwnedTransaction()
            throws Exception {
        Context context = context(TransactionMode.NORMAL, reservation(1));
        when(context.assets().findById(ASSET_ID)).thenReturn(Optional.empty());
        when(context.storage().cleanupReservedStaging(
                        context.authorization(),
                        ASSET_ID,
                        SHA256,
                        "image/jpeg",
                        DATABASE_NOW.minusHours(24).toInstant()))
                .thenReturn(ReservedStagingCleanupResult.CLEANED);
        when(context.reservations().deleteExact(context.reservation())).thenReturn(true);

        LocalStagingObjectCleanupResult result = context.cleaner()
                .cleanup(context.reservation(), Optional.empty());

        assertThat(result).isEqualTo(LocalStagingObjectCleanupResult.CLEANED);
        InOrder order = inOrder(
                context.fence(),
                context.reservations(),
                context.assets(),
                context.authorization(),
                context.storage());
        order.verify(context.fence()).acquire(any(LocalStagingPublication.class));
        order.verify(context.reservations()).acquireCapacityLock();
        order.verify(context.reservations()).findByAssetIdForUpdate(ASSET_ID);
        order.verify(context.authorization()).reauthenticateVolume(VOLUME_ID);
        order.verify(context.assets()).findById(ASSET_ID);
        order.verify(context.reservations()).databaseNow();
        order.verify(context.storage()).cleanupReservedStaging(
                context.authorization(),
                ASSET_ID,
                SHA256,
                "image/jpeg",
                DATABASE_NOW.minusHours(24).toInstant());
        order.verify(context.authorization()).reauthenticateVolume(VOLUME_ID);
        order.verify(context.reservations()).deleteExact(context.reservation());
        verify(context.authorization()).close();
        assertThat(context.transactions().events())
                .containsExactly("tx.begin", "tx.commit");
    }

    @Test
    void currentGenerationOneIsUsedForFenceAuthentication() throws Exception {
        Context context = context(TransactionMode.NORMAL, reservation(1));
        when(context.assets().findById(ASSET_ID)).thenReturn(Optional.empty());
        when(context.storage().cleanupReservedStaging(any(), any(), any(), any(), any()))
                .thenReturn(ReservedStagingCleanupResult.CLEANED);
        when(context.reservations().deleteExact(context.reservation())).thenReturn(true);
        ArgumentCaptor<LocalStagingPublication> publication =
                ArgumentCaptor.forClass(LocalStagingPublication.class);

        context.cleaner().cleanup(context.reservation(), Optional.empty());

        verify(context.fence()).acquire(publication.capture());
        assertThat(publication.getValue().generation()).isOne();
        assertThat(publication.getValue().cleanupJobId()).isEqualTo(JOB_ID);
        assertThat(publication.getValue().objectKey()).isEqualTo(
                "staging/" + ASSET_ID + '/' + SHA256 + ".jpg");
    }

    @Test
    void busyFenceDefersBeforeStartingADatabaseTransaction() throws Exception {
        Context context = context(TransactionMode.NORMAL, reservation(0));
        when(context.fence().acquire(any()))
                .thenThrow(new StorageException("LOCAL_PUBLICATION_FENCE_TIMEOUT"));

        assertThat(context.cleaner().cleanup(context.reservation(), Optional.empty()))
                .isEqualTo(LocalStagingObjectCleanupResult.DEFERRED);

        verifyNoInteractions(context.assets(), context.storage(), context.authorization());
        verify(context.reservations(), never()).acquireCapacityLock();
        assertThat(context.transactions().events()).isEmpty();
    }

    @Test
    void youngReservationDefersUnderTheRowLockWithoutTouchingStorageOrDeleting()
            throws Exception {
        LocalStagingReservation young = reservationAt(
                0, DATABASE_NOW.minusHours(1), DATABASE_NOW.plusHours(23));
        Context context = context(TransactionMode.NORMAL, young);
        when(context.assets().findById(ASSET_ID)).thenReturn(Optional.empty());

        assertThat(context.cleaner().cleanup(young, Optional.empty()))
                .isEqualTo(LocalStagingObjectCleanupResult.DEFERRED);

        verify(context.storage(), never()).cleanupReservedStaging(
                any(), any(), any(), any(), any());
        verify(context.reservations(), never()).deleteExact(any());
        assertThat(context.transactions().events())
                .containsExactly("tx.begin", "tx.commit");
    }

    @Test
    void lockedProcessingAssetOverridesTheHandlerSnapshotAndDefersBeforeFilesystem()
            throws Exception {
        Context context = context(TransactionMode.NORMAL, reservation(0));
        when(context.assets().findById(ASSET_ID))
                .thenReturn(Optional.of(asset(MediaStatus.PROCESSING, StorageProvider.LOCAL)));

        assertThat(context.cleaner().cleanup(
                        context.reservation(),
                        Optional.of(asset(MediaStatus.FAILED, StorageProvider.LOCAL))))
                .isEqualTo(LocalStagingObjectCleanupResult.DEFERRED);

        verify(context.storage(), never()).cleanupReservedStaging(
                any(), any(), any(), any(), any());
        verify(context.reservations(), never()).deleteExact(any());
    }

    @Test
    void lockedTerminalAssetMismatchRollsBackBeforeFilesystem() {
        Context context = context(TransactionMode.NORMAL, reservation(0));
        when(context.assets().findById(ASSET_ID))
                .thenReturn(Optional.of(asset(MediaStatus.READY, StorageProvider.TENCENT_COS)));

        assertFixedFailure(() -> context.cleaner().cleanup(
                context.reservation(), Optional.empty()));

        verify(context.storage(), never()).cleanupReservedStaging(
                any(), any(), any(), any(), any());
        verify(context.reservations(), never()).deleteExact(any());
        assertThat(context.transactions().events())
                .containsExactly("tx.begin", "tx.rollback");
    }

    @Test
    void filesystemDeferredCommitsTheObservationButRetainsTheReservation()
            throws Exception {
        Context context = context(TransactionMode.NORMAL, reservation(0));
        when(context.assets().findById(ASSET_ID)).thenReturn(Optional.empty());
        when(context.storage().cleanupReservedStaging(any(), any(), any(), any(), any()))
                .thenReturn(ReservedStagingCleanupResult.DEFERRED);

        assertThat(context.cleaner().cleanup(context.reservation(), Optional.empty()))
                .isEqualTo(LocalStagingObjectCleanupResult.DEFERRED);

        verify(context.reservations(), never()).deleteExact(any());
        assertThat(context.transactions().events())
                .containsExactly("tx.begin", "tx.commit");
    }

    @Test
    void fsyncOrExactDeleteFailureRollsBackAndRetainsTheReservation() {
        Context context = context(TransactionMode.NORMAL, reservation(0));
        when(context.assets().findById(ASSET_ID)).thenReturn(Optional.empty());
        when(context.storage().cleanupReservedStaging(any(), any(), any(), any(), any()))
                .thenThrow(new StorageException("LOCAL_STAGING_EXACT_CLEANUP_FAILED"));

        assertFixedFailure(() -> context.cleaner().cleanup(
                context.reservation(), Optional.empty()));

        verify(context.reservations(), never()).deleteExact(any());
        assertThat(context.transactions().events())
                .containsExactly("tx.begin", "tx.rollback");
    }

    @Test
    void falseReservationDeleteRollsBackAfterFilesystemAndNeverClaimsCleaned() {
        Context context = context(TransactionMode.NORMAL, reservation(0));
        when(context.assets().findById(ASSET_ID)).thenReturn(Optional.empty());
        when(context.storage().cleanupReservedStaging(any(), any(), any(), any(), any()))
                .thenReturn(ReservedStagingCleanupResult.CLEANED);
        when(context.reservations().deleteExact(context.reservation())).thenReturn(false);

        assertFixedFailure(() -> context.cleaner().cleanup(
                context.reservation(), Optional.empty()));

        assertThat(context.transactions().events())
                .containsExactly("tx.begin", "tx.rollback");
    }

    @Test
    void commitFailureAfterFilesystemAndRowDeleteIsUnknownAndNeverClaimsCleaned() {
        Context context = context(TransactionMode.COMMIT_FAIL, reservation(0));
        when(context.assets().findById(ASSET_ID)).thenReturn(Optional.empty());
        when(context.storage().cleanupReservedStaging(any(), any(), any(), any(), any()))
                .thenReturn(ReservedStagingCleanupResult.CLEANED);
        when(context.reservations().deleteExact(context.reservation())).thenReturn(true);

        assertFixedFailure(() -> context.cleaner().cleanup(
                context.reservation(), Optional.empty()));

        verify(context.reservations()).deleteExact(context.reservation());
        assertThat(context.transactions().events())
                .containsExactly("tx.begin", "tx.commit");
    }

    @Test
    void knownRollbackUsesDatabaseNowAndTheAlreadyHeldFenceWithoutReacquiring()
            throws Exception {
        LocalStagingReservation young = reservationAt(
                0, DATABASE_NOW.minusMinutes(1), DATABASE_NOW.plusHours(24));
        Context context = context(TransactionMode.NORMAL, young);
        when(context.assets().findById(ASSET_ID)).thenReturn(Optional.empty());
        when(context.storage().cleanupReservedStaging(
                        context.authorization(),
                        ASSET_ID,
                        SHA256,
                        "image/jpeg",
                        DATABASE_NOW.toInstant()))
                .thenReturn(ReservedStagingCleanupResult.CLEANED);
        when(context.reservations().deleteExact(young)).thenReturn(true);

        assertThat(context.cleaner().cleanupKnownRollback(
                        context.authorization(), young))
                .isTrue();

        verifyNoInteractions(context.fence());
        verify(context.authorization(), never()).close();
        verify(context.storage()).cleanupReservedStaging(
                context.authorization(),
                ASSET_ID, SHA256, "image/jpeg", DATABASE_NOW.toInstant());
    }

    @Test
    void knownRollbackDeferredRetainsTheReservationAndReturnsFalse() {
        Context context = context(TransactionMode.NORMAL, reservation(0));
        when(context.assets().findById(ASSET_ID)).thenReturn(Optional.empty());
        when(context.storage().cleanupReservedStaging(any(), any(), any(), any(), any()))
                .thenReturn(ReservedStagingCleanupResult.DEFERRED);

        assertThat(context.cleaner().cleanupKnownRollback(
                        context.authorization(), context.reservation()))
                .isFalse();

        verify(context.reservations(), never()).deleteExact(any());
        verifyNoInteractions(context.fence());
    }

    @Test
    void staleLockedReservationRollsBackBeforeAssetAndFilesystem() {
        Context context = context(TransactionMode.NORMAL, reservation(0));
        when(context.reservations().findByAssetIdForUpdate(ASSET_ID))
                .thenReturn(Optional.of(reservation(1)));

        assertFixedFailure(() -> context.cleaner().cleanup(
                context.reservation(), Optional.empty()));

        verifyNoInteractions(context.assets(), context.storage());
        verify(context.reservations(), never()).deleteExact(any());
    }

    private static Context context(
            TransactionMode mode, LocalStagingReservation reservation) {
        LocalStorageService storage = mock(LocalStorageService.class);
        LocalPublicationFence fence = mock(LocalPublicationFence.class);
        LocalPublicationAuthorization authorization =
                mock(LocalPublicationAuthorization.class);
        LocalStagingReservationRepository reservations =
                mock(LocalStagingReservationRepository.class);
        MediaAssetRepository assets = mock(MediaAssetRepository.class);
        RecordingTransactionManager transactions =
                new RecordingTransactionManager(mode);
        when(storage.volumeId()).thenReturn(VOLUME_ID);
        when(fence.acquire(any())).thenReturn(authorization);
        when(reservations.findByAssetIdForUpdate(ASSET_ID))
                .thenReturn(Optional.of(reservation));
        when(reservations.databaseNow()).thenReturn(DATABASE_NOW);
        TransactionalLocalStagingObjectCleanup cleaner =
                new TransactionalLocalStagingObjectCleanup(
                        storage, fence, reservations, assets, transactions);
        return new Context(
                cleaner,
                storage,
                fence,
                authorization,
                reservations,
                assets,
                transactions,
                reservation);
    }

    private static LocalStagingReservation reservation(long generation) {
        return reservationAt(
                generation, RESERVED_AT, RESERVED_AT.plusHours(24));
    }

    private static LocalStagingReservation reservationAt(
            long generation, OffsetDateTime reservedAt, OffsetDateTime cleanupAfter) {
        return new LocalStagingReservation(
                ASSET_ID,
                SHA256,
                "image/jpeg",
                generation,
                JOB_ID,
                reservedAt,
                cleanupAfter);
    }

    private static MediaAssetRecord asset(
            MediaStatus status, StorageProvider provider) {
        String bucket = provider == StorageProvider.LOCAL ? null : "portfolio-1234567890";
        String region = provider == StorageProvider.LOCAL ? null : "ap-guangzhou";
        return new MediaAssetRecord(
                ASSET_ID,
                provider,
                bucket,
                region,
                MediaObjectKeys.originalKey(ASSET_ID, SHA256, "image/jpeg"),
                "asset.jpg",
                "image/jpeg",
                123,
                10,
                10,
                SHA256,
                status,
                null,
                0,
                Instant.parse("2026-07-15T00:00:00Z"),
                Instant.parse("2026-07-15T00:00:00Z"));
    }

    private static void assertFixedFailure(ThrowingOperation operation) {
        assertThatThrownBy(operation::run)
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("LOCAL_STAGING_EXACT_CLEANUP_FAILED")
                .hasNoCause();
    }

    private record Context(
            TransactionalLocalStagingObjectCleanup cleaner,
            LocalStorageService storage,
            LocalPublicationFence fence,
            LocalPublicationAuthorization authorization,
            LocalStagingReservationRepository reservations,
            MediaAssetRepository assets,
            RecordingTransactionManager transactions,
            LocalStagingReservation reservation) {}

    @FunctionalInterface
    private interface ThrowingOperation {
        void run() throws Exception;
    }

    private enum TransactionMode {
        NORMAL,
        BEGIN_FAIL,
        COMMIT_FAIL
    }

    private static final class RecordingTransactionManager
            extends AbstractPlatformTransactionManager {
        private final TransactionMode mode;
        private final List<String> events = new ArrayList<>();

        private RecordingTransactionManager(TransactionMode mode) {
            this.mode = mode;
            setRollbackOnCommitFailure(false);
        }

        List<String> events() {
            return List.copyOf(events);
        }

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            events.add("tx.begin");
            if (mode == TransactionMode.BEGIN_FAIL) {
                throw new CannotCreateTransactionException("private begin failure");
            }
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            events.add("tx.commit");
            if (mode == TransactionMode.COMMIT_FAIL) {
                throw new TransactionSystemException("private commit failure");
            }
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            events.add("tx.rollback");
        }
    }
}
