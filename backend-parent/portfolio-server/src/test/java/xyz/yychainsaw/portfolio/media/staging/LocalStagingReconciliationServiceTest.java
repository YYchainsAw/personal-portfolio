package xyz.yychainsaw.portfolio.media.staging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import xyz.yychainsaw.portfolio.media.storage.LocalStagingAuditExpectation;
import xyz.yychainsaw.portfolio.media.storage.LocalStorageService;
import xyz.yychainsaw.portfolio.media.storage.StorageException;

class LocalStagingReconciliationServiceTest {
    private static final UUID FIRST =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID SECOND =
            UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);

    @Test
    void loadsTheWholeBoundedJournalBeforeStartingFilesystemAudit() {
        LocalStagingReservationRepository repository =
                mock(LocalStagingReservationRepository.class);
        LocalStorageService storage = mock(LocalStorageService.class);
        LocalStagingReservation first = reservation(FIRST, SHA_A, "image/jpeg");
        LocalStagingReservation second = reservation(SECOND, SHA_B, "application/pdf");
        when(repository.findAllBounded(4)).thenReturn(List.of(first, second));
        LocalStagingReconciliationService service = service(repository, storage);

        service.auditDaily();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<UUID, LocalStagingAuditExpectation>> expected =
                ArgumentCaptor.forClass(Map.class);
        InOrder order = inOrder(repository, storage);
        order.verify(repository).acquireCapacityLock();
        order.verify(repository).findAllBounded(4);
        order.verify(repository).hasStalledReservation();
        order.verify(storage).auditReservedStaging(expected.capture());
        org.assertj.core.api.Assertions.assertThat(expected.getValue())
                .containsOnlyKeys(FIRST, SECOND);
        org.assertj.core.api.Assertions.assertThat(expected.getValue().get(FIRST))
                .isEqualTo(new LocalStagingAuditExpectation(
                        FIRST, SHA_A, "image/jpeg"));
    }

    @Test
    void capacityPlusOneRowsFailClosedBeforeAnyFilesystemAccess() {
        LocalStagingReservationRepository repository =
                mock(LocalStagingReservationRepository.class);
        LocalStorageService storage = mock(LocalStorageService.class);
        when(repository.findAllBounded(4)).thenReturn(List.of(
                reservation(FIRST, SHA_A, "image/jpeg"),
                reservation(SECOND, SHA_B, "image/png"),
                reservation(UUID.fromString(
                        "33333333-3333-4333-8333-333333333333"), SHA_A, "image/png"),
                reservation(UUID.fromString(
                        "44444444-4444-4444-8444-444444444444"), SHA_B, "image/jpeg")));

        assertMigrationRequired(() -> service(repository, storage).auditDaily());

        verifyNoInteractions(storage);
    }

    @Test
    void duplicateNullOrUnexpectedRepositoryRowsFailClosedBeforeFilesystem() {
        LocalStagingReservation first = reservation(FIRST, SHA_A, "image/jpeg");
        for (List<LocalStagingReservation> corrupt : List.of(
                java.util.Arrays.asList(first, null),
                List.of(first, first))) {
            LocalStagingReservationRepository repository =
                    mock(LocalStagingReservationRepository.class);
            LocalStorageService storage = mock(LocalStorageService.class);
            when(repository.findAllBounded(4)).thenReturn(corrupt);

            assertMigrationRequired(() -> service(repository, storage).auditDaily());
            verifyNoInteractions(storage);
        }
    }

    @Test
    void databaseOrFilesystemFailureUsesOneCauseFreeMigrationSurface() {
        LocalStagingReservationRepository repository =
                mock(LocalStagingReservationRepository.class);
        LocalStorageService storage = mock(LocalStorageService.class);
        when(repository.findAllBounded(4))
                .thenThrow(new IllegalStateException("jdbc private path"));

        assertMigrationRequired(() -> service(repository, storage).auditDaily());
        verifyNoInteractions(storage);

        org.mockito.Mockito.doReturn(List.of())
                .when(repository)
                .findAllBounded(4);
        org.mockito.Mockito.doThrow(new StorageException(
                        "LOCAL_STAGING_MIGRATION_REQUIRED",
                        new IllegalStateException("private root")))
                .when(storage)
                .auditReservedStaging(Map.of());
        assertMigrationRequired(() -> service(repository, storage).auditDaily());
    }

    @Test
    void neverCallsTheLegacyBlindDeletionPrimitive() {
        LocalStagingReservationRepository repository =
                mock(LocalStagingReservationRepository.class);
        LocalStorageService storage = mock(LocalStorageService.class);
        when(repository.findAllBounded(4)).thenReturn(List.of());

        service(repository, storage).auditDaily();

        verify(storage, never()).cleanupStaging(
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void dailyAuditFailsOnAStalledChainButStartupAuditDoesNotFailSolelyForAge() {
        LocalStagingReservationRepository repository =
                mock(LocalStagingReservationRepository.class);
        LocalStorageService storage = mock(LocalStorageService.class);
        LocalStagingReservation current = reservation(FIRST, SHA_A, "image/jpeg");
        when(repository.findAllBounded(4)).thenReturn(List.of(current));
        when(repository.hasStalledReservation()).thenReturn(true);
        LocalStagingReconciliationService service = service(repository, storage);

        assertMigrationRequired(service::auditDaily);
        verifyNoInteractions(storage);

        org.mockito.Mockito.clearInvocations(storage, repository);
        service.auditStartup();
        verify(repository).findAllBounded(4);
        verify(repository, never()).hasStalledReservation();
        verify(storage).auditReservedStaging(org.mockito.ArgumentMatchers.anyMap());
    }

    private static LocalStagingReconciliationService service(
            LocalStagingReservationRepository repository,
            LocalStorageService storage) {
        return new LocalStagingReconciliationService(
                repository,
                storage,
                new LocalStagingPolicyProperties(3, 64, 16));
    }

    private static LocalStagingReservation reservation(
            UUID assetId, String sha256, String mimeType) {
        OffsetDateTime reservedAt = OffsetDateTime.parse("2026-07-15T00:00:00Z");
        return new LocalStagingReservation(
                assetId,
                sha256,
                mimeType,
                0,
                UUID.randomUUID(),
                reservedAt,
                reservedAt.plusHours(24));
    }

    private static void assertMigrationRequired(ThrowingOperation operation) {
        assertThatThrownBy(operation::run)
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("LOCAL_STAGING_MIGRATION_REQUIRED")
                .hasNoCause();
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run();
    }
}
