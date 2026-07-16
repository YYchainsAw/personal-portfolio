package xyz.yychainsaw.portfolio.media.staging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import xyz.yychainsaw.portfolio.media.storage.LocalStorageService;
import xyz.yychainsaw.portfolio.system.job.ScheduledJobInsert;
import xyz.yychainsaw.portfolio.system.job.ScheduledJobInserter;

class LocalStagingReservationCommitTest {
    private static final LocalStagingPolicy POLICY =
            new LocalStagingPolicy(3, 64, 6, 16);
    private static final LocalStagingPolicyProperties PROPERTIES =
            new LocalStagingPolicyProperties(3, 64, 16);
    private static final String VOLUME_ID = "a".repeat(64);
    private static final OffsetDateTime NOW = OffsetDateTime.of(
            2026, 7, 17, 1, 0, 0, 0, ZoneOffset.UTC);

    @Test
    void synchronizationWithoutAnActualTransactionCannotMutateOrAuthorize() {
        LocalStagingReservationRepository repository =
                mock(LocalStagingReservationRepository.class);
        ScheduledJobInserter jobs = mock(ScheduledJobInserter.class);
        LocalStorageService storage = mock(LocalStorageService.class);
        LocalStagingReservationService service = new LocalStagingReservationService(
                repository,
                jobs,
                storage,
                PROPERTIES,
                new CompletionTransactionManager(false, null));

        assertThatThrownBy(() -> service.reserve(
                        UUID.randomUUID(), "a".repeat(64), "image/jpeg"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("LOCAL_STAGING_RESERVATION_COMMIT_UNKNOWN")
                .hasNoCause();
        verifyNoInteractions(repository, jobs);
    }

    @Test
    void missingOrUnknownCompletionCallbackNeverReturnsAReceipt() {
        for (Integer completion : new Integer[] {
                null, TransactionSynchronization.STATUS_UNKNOWN
        }) {
            LocalStagingReservationRepository repository =
                    mock(LocalStagingReservationRepository.class);
            ScheduledJobInserter jobs = mock(ScheduledJobInserter.class);
            when(repository.findPolicy()).thenReturn(Optional.of(POLICY));
            when(repository.countActiveReservations()).thenReturn(0L);
            LocalStorageService storage = matchingStorage(repository);
            when(jobs.insertAfter(any(), any(), any(), any())).thenReturn(
                    new ScheduledJobInsert(UUID.randomUUID(), NOW, NOW.plusHours(24)));
            LocalStagingReservationService service = new LocalStagingReservationService(
                    repository,
                    jobs,
                    storage,
                    PROPERTIES,
                    new CompletionTransactionManager(true, completion));

            assertThatThrownBy(() -> service.reserve(
                            UUID.randomUUID(), "a".repeat(64), "image/png"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("LOCAL_STAGING_RESERVATION_COMMIT_UNKNOWN")
                    .hasNoCause();
            verify(repository).insert(any(LocalStagingReservation.class));
        }
    }

    @Test
    void reserveAndExactReleaseUseTheSameCapacityAdmissionLock() {
        LocalStagingReservationRepository repository =
                mock(LocalStagingReservationRepository.class);
        ScheduledJobInserter jobs = mock(ScheduledJobInserter.class);
        UUID jobId = UUID.randomUUID();
        when(repository.findPolicy()).thenReturn(Optional.of(POLICY));
        when(repository.countActiveReservations()).thenReturn(0L);
        when(repository.deleteExact(any(LocalStagingReservation.class))).thenReturn(true);
        LocalStorageService storage = matchingStorage(repository);
        when(jobs.insertAfter(any(), any(), any(), any())).thenReturn(
                new ScheduledJobInsert(jobId, NOW, NOW.plusHours(24)));
        LocalStagingReservationService service = new LocalStagingReservationService(
                repository,
                jobs,
                storage,
                PROPERTIES,
                new CompletionTransactionManager(
                        true, TransactionSynchronization.STATUS_COMMITTED));

        LocalStagingReservationReceipt receipt = service.reserve(
                UUID.randomUUID(), "a".repeat(64), "image/jpeg");
        service.releaseExact(receipt.reservation());

        verify(repository, times(2)).acquireCapacityLock();
    }

    @Test
    void stalledChainRejectsAdmissionUnderTheCapacityLockBeforeCountOrJobInsert() {
        LocalStagingReservationRepository repository =
                mock(LocalStagingReservationRepository.class);
        ScheduledJobInserter jobs = mock(ScheduledJobInserter.class);
        when(repository.findPolicy()).thenReturn(Optional.of(POLICY));
        when(repository.hasStalledReservation()).thenReturn(true);
        LocalStorageService storage = matchingStorage(repository);
        LocalStagingReservationService service = new LocalStagingReservationService(
                repository,
                jobs,
                storage,
                PROPERTIES,
                new CompletionTransactionManager(
                        true, TransactionSynchronization.STATUS_COMMITTED));

        assertThatThrownBy(() -> service.reserve(
                        UUID.randomUUID(), "a".repeat(64), "image/jpeg"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("LOCAL_STAGING_CHAIN_STALLED")
                .hasNoCause();

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(repository);
        order.verify(repository).acquireCapacityLock();
        order.verify(repository).findPolicy();
        order.verify(repository).volumeMatches(VOLUME_ID);
        order.verify(repository).hasStalledReservation();
        verify(repository, org.mockito.Mockito.never()).countActiveReservations();
        verifyNoInteractions(jobs);
    }

    @Test
    void initializationEnsuresPolicyThenClaimsAndVerifiesTheCurrentVolume() {
        LocalStagingReservationRepository repository =
                mock(LocalStagingReservationRepository.class);
        ScheduledJobInserter jobs = mock(ScheduledJobInserter.class);
        LocalStorageService storage = matchingStorage(repository);
        when(repository.findPolicy())
                .thenReturn(Optional.empty(), Optional.of(POLICY));
        when(repository.claimVolumeId(VOLUME_ID)).thenReturn(true);
        LocalStagingReservationService service = new LocalStagingReservationService(
                repository,
                jobs,
                storage,
                PROPERTIES,
                new CompletionTransactionManager(
                        true, TransactionSynchronization.STATUS_COMMITTED));

        service.initializePolicy(POLICY);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(repository, storage);
        order.verify(repository).acquirePolicyLock();
        order.verify(repository).findPolicy();
        order.verify(repository).insertPolicyIfAbsent(POLICY);
        order.verify(repository).findPolicy();
        order.verify(storage).volumeId();
        order.verify(repository).claimVolumeId(VOLUME_ID);
        order.verify(repository).volumeMatches(VOLUME_ID);
        verifyNoInteractions(jobs);
    }

    @Test
    void initializationRejectsAnAlreadyClaimedDifferentVolumeCauseFree() {
        LocalStagingReservationRepository repository =
                mock(LocalStagingReservationRepository.class);
        ScheduledJobInserter jobs = mock(ScheduledJobInserter.class);
        LocalStorageService storage = matchingStorage(repository);
        when(repository.findPolicy()).thenReturn(Optional.of(POLICY));
        when(repository.claimVolumeId(VOLUME_ID)).thenReturn(false);
        LocalStagingReservationService service = new LocalStagingReservationService(
                repository,
                jobs,
                storage,
                PROPERTIES,
                new CompletionTransactionManager(
                        true, TransactionSynchronization.STATUS_COMMITTED));

        assertThatThrownBy(() -> service.initializePolicy(POLICY))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("LOCAL_STAGING_VOLUME_MISMATCH")
                .hasNoCause();
        verify(repository, never()).volumeMatches(VOLUME_ID);
        verifyNoInteractions(jobs);
    }

    @Test
    void everyReservationOperationRechecksTheExactCurrentVolume() {
        LocalStagingReservationRepository repository =
                mock(LocalStagingReservationRepository.class);
        ScheduledJobInserter jobs = mock(ScheduledJobInserter.class);
        LocalStorageService storage = mock(LocalStorageService.class);
        when(storage.volumeId()).thenReturn(VOLUME_ID);
        when(repository.findPolicy()).thenReturn(Optional.of(POLICY));
        when(repository.volumeMatches(VOLUME_ID)).thenReturn(false);
        LocalStagingReservationService service = new LocalStagingReservationService(
                repository,
                jobs,
                storage,
                PROPERTIES,
                new CompletionTransactionManager(
                        true, TransactionSynchronization.STATUS_COMMITTED));
        LocalStagingReservation expected = new LocalStagingReservation(
                UUID.randomUUID(),
                "b".repeat(64),
                "image/jpeg",
                0L,
                UUID.randomUUID(),
                NOW,
                NOW.plusHours(24));

        List<Runnable> operations = List.of(
                () -> service.reserve(
                        expected.assetId(), expected.sha256(), expected.mimeType()),
                () -> service.releaseExact(expected),
                () -> service.authenticateCurrent(
                        expected.assetId(),
                        expected.sha256(),
                        expected.mimeType(),
                        expected.generation(),
                        expected.cleanupJobId()),
                () -> service.lockCurrentForOuterTransaction(expected));
        for (Runnable operation : operations) {
            assertThatThrownBy(operation::run)
                    .isExactlyInstanceOf(IllegalStateException.class)
                    .hasMessage("LOCAL_STAGING_VOLUME_MISMATCH")
                    .hasNoCause();
        }

        verify(repository, times(4)).volumeMatches(VOLUME_ID);
        verify(repository, never()).insert(any(LocalStagingReservation.class));
        verify(repository, never()).deleteExact(any(LocalStagingReservation.class));
        verify(repository, never()).findByAssetId(any(UUID.class));
        verify(repository, never()).findByAssetIdForUpdate(any(UUID.class));
        verifyNoInteractions(jobs);
    }

    private static LocalStorageService matchingStorage(
            LocalStagingReservationRepository repository) {
        LocalStorageService storage = mock(LocalStorageService.class);
        when(storage.volumeId()).thenReturn(VOLUME_ID);
        when(repository.volumeMatches(VOLUME_ID)).thenReturn(true);
        return storage;
    }

    private static final class CompletionTransactionManager
            implements PlatformTransactionManager {
        private final boolean actualTransaction;
        private final Integer completion;

        private CompletionTransactionManager(
                boolean actualTransaction, Integer completion) {
            this.actualTransaction = actualTransaction;
            this.completion = completion;
        }

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            TransactionSynchronizationManager.initSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(actualTransaction);
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            try {
                if (completion != null) {
                    for (TransactionSynchronization synchronization
                            : TransactionSynchronizationManager.getSynchronizations()) {
                        synchronization.afterCompletion(completion);
                    }
                }
            } finally {
                clearSynchronization();
            }
        }

        @Override
        public void rollback(TransactionStatus status) {
            try {
                for (TransactionSynchronization synchronization
                        : TransactionSynchronizationManager.getSynchronizations()) {
                    synchronization.afterCompletion(
                            TransactionSynchronization.STATUS_ROLLED_BACK);
                }
            } finally {
                clearSynchronization();
            }
        }

        private static void clearSynchronization() {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.clearSynchronization();
            }
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }
}
