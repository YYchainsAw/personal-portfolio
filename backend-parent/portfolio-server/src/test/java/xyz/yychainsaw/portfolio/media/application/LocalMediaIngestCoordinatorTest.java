package xyz.yychainsaw.portfolio.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;
import xyz.yychainsaw.portfolio.media.staging.LocalStagingKnownRollbackCleanup;
import xyz.yychainsaw.portfolio.media.staging.LocalStagingReservation;
import xyz.yychainsaw.portfolio.media.staging.LocalStagingReservationReceipt;
import xyz.yychainsaw.portfolio.media.staging.LocalStagingReservationService;
import xyz.yychainsaw.portfolio.media.storage.LocalPublicationAuthorization;
import xyz.yychainsaw.portfolio.media.storage.LocalPublicationFence;
import xyz.yychainsaw.portfolio.media.storage.LocalStagingPublication;
import xyz.yychainsaw.portfolio.media.storage.LocalStorageService;
import xyz.yychainsaw.portfolio.media.storage.StorageException;
import xyz.yychainsaw.portfolio.media.storage.StorageLocation;
import xyz.yychainsaw.portfolio.media.storage.StorageService;
import xyz.yychainsaw.portfolio.media.storage.StoredObject;

class LocalMediaIngestCoordinatorTest {
    private static final UUID ASSET_ID =
            UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID CLEANUP_JOB_ID =
            UUID.fromString("77777777-8888-4999-aaaa-bbbbbbbbbbbb");
    private static final String SHA256 = "a".repeat(64);
    private static final String MIME_TYPE = "application/pdf";
    private static final String STAGING_KEY =
            "staging/" + ASSET_ID + "/" + SHA256 + ".pdf";
    private static final StorageLocation LOCAL_LOCATION =
            new StorageLocation(StorageProvider.LOCAL, null, null);

    @Test
    void reservesThenFencesAndPublishesOnlyThroughTheReservedLocalBoundary() {
        Context context = context();
        StoredObject expected = new StoredObject(
                StorageProvider.LOCAL,
                null,
                null,
                STAGING_KEY,
                3,
                MIME_TYPE,
                "etag");
        when(context.localStorage().putReservedStaging(
                        any(), any(), any(), anyLong()))
                .thenReturn(expected);

        LocalMediaIngestSession session = context.coordinator().open(
                context.localStorage(),
                LOCAL_LOCATION,
                ASSET_ID,
                STAGING_KEY,
                SHA256,
                MIME_TYPE);
        session.prepareOuterTransaction();
        StoredObject actual = session.publish(new ByteArrayInputStream(new byte[] {1, 2, 3}), 3);
        session.cleanupKnownRollback();
        session.close();

        ArgumentCaptor<LocalStagingPublication> publication =
                ArgumentCaptor.forClass(LocalStagingPublication.class);
        InOrder order = inOrder(
                context.reservations(),
                context.fence(),
                context.localStorage(),
                context.cleanup(),
                context.authorization());
        order.verify(context.reservations()).reserve(ASSET_ID, SHA256, MIME_TYPE);
        order.verify(context.fence()).acquire(publication.capture());
        order.verify(context.reservations())
                .lockCurrentForOuterTransaction(context.reservation());
        order.verify(context.authorization()).reauthenticate();
        order.verify(context.localStorage()).putReservedStaging(
                eq(context.authorization()),
                eq(publication.getValue()),
                any(),
                eq(3L));
        order.verify(context.cleanup()).cleanupKnownRollback(
                context.authorization(), context.reservation());
        order.verify(context.authorization()).close();
        verify(context.localStorage(), never()).put(
                anyString(), any(), anyLong(), anyString());
        assertThat(actual).isSameAs(expected);
        assertThat(publication.getValue().assetId()).isEqualTo(ASSET_ID);
        assertThat(publication.getValue().objectKey()).isEqualTo(STAGING_KEY);
        assertThat(publication.getValue().sha256()).isEqualTo(SHA256);
        assertThat(publication.getValue().mimeType()).isEqualTo(MIME_TYPE);
        assertThat(publication.getValue().location()).isEqualTo(LOCAL_LOCATION);
        assertThat(publication.getValue().generation()).isZero();
        assertThat(publication.getValue().cleanupJobId()).isEqualTo(CLEANUP_JOB_ID);
    }

    @Test
    void rejectsAProviderLocalWriterThatIsNotTheInjectedLocalAdapterBeforeReservation() {
        Context context = context();
        StorageService forged = mock(StorageService.class);
        when(forged.provider()).thenReturn(StorageProvider.LOCAL);
        when(forged.location()).thenReturn(LOCAL_LOCATION);

        assertThatThrownBy(() -> context.coordinator().open(
                        forged,
                        LOCAL_LOCATION,
                        ASSET_ID,
                        STAGING_KEY,
                        SHA256,
                        MIME_TYPE))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasNoCause();

        verifyNoInteractions(context.reservations(), context.fence(), context.cleanup());
        verify(forged, never()).put(anyString(), any(), anyLong(), anyString());
        verify(context.localStorage(), never()).putReservedStaging(
                any(), any(), any(), anyLong());
    }

    @Test
    void rejectsAnInjectedLocalAdapterWhoseLocationDoesNotMatchBeforeReservation() {
        Context context = context();
        when(context.localStorage().location()).thenReturn(new StorageLocation(
                StorageProvider.TENCENT_COS,
                "portfolio-1234567890",
                "ap-guangzhou"));

        assertThatThrownBy(() -> context.coordinator().open(
                        context.localStorage(),
                        LOCAL_LOCATION,
                        ASSET_ID,
                        STAGING_KEY,
                        SHA256,
                        MIME_TYPE))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasNoCause();

        verifyNoInteractions(context.reservations(), context.fence(), context.cleanup());
        verify(context.localStorage(), never()).put(
                anyString(), any(), anyLong(), anyString());
        verify(context.localStorage(), never()).putReservedStaging(
                any(), any(), any(), anyLong());
    }

    @Test
    void fenceFailureAfterCommittedReservationRetainsTheSlotAndMakesZeroStorageCalls() {
        Context context = context();
        when(context.fence().acquire(any()))
                .thenThrow(new StorageException("private fence failure"));

        assertThatThrownBy(() -> context.coordinator().open(
                        context.localStorage(),
                        LOCAL_LOCATION,
                        ASSET_ID,
                        STAGING_KEY,
                        SHA256,
                        MIME_TYPE))
                .isInstanceOf(StorageException.class);

        verify(context.reservations()).reserve(ASSET_ID, SHA256, MIME_TYPE);
        verify(context.localStorage(), never()).put(
                anyString(), any(), anyLong(), anyString());
        verify(context.localStorage(), never()).putReservedStaging(
                any(), any(), any(), anyLong());
        verifyNoInteractions(context.cleanup(), context.authorization());
    }

    @Test
    void knownRollbackAndFenceCloseAreEachOneShot() {
        Context context = context();
        LocalMediaIngestSession session = context.coordinator().open(
                context.localStorage(),
                LOCAL_LOCATION,
                ASSET_ID,
                STAGING_KEY,
                SHA256,
                MIME_TYPE);

        session.prepareOuterTransaction();
        session.cleanupKnownRollback();
        session.cleanupKnownRollback();
        session.close();
        session.close();

        verify(context.cleanup()).cleanupKnownRollback(
                context.authorization(), context.reservation());
        verify(context.authorization()).close();
    }

    @Test
    void cleanupFailureIsFailClosedAndFenceRemainsClosable() {
        Context context = context();
        when(context.cleanup().cleanupKnownRollback(any(), any()))
                .thenThrow(new IllegalStateException("private cleanup failure"));
        LocalMediaIngestSession session = context.coordinator().open(
                context.localStorage(),
                LOCAL_LOCATION,
                ASSET_ID,
                STAGING_KEY,
                SHA256,
                MIME_TYPE);

        session.prepareOuterTransaction();
        session.cleanupKnownRollback();
        session.close();

        verify(context.cleanup()).cleanupKnownRollback(
                context.authorization(), context.reservation());
        verify(context.authorization()).close();
    }

    private static Context context() {
        LocalStorageService localStorage = mock(LocalStorageService.class);
        LocalStagingReservationService reservations =
                mock(LocalStagingReservationService.class);
        LocalPublicationFence fence = mock(LocalPublicationFence.class);
        LocalStagingKnownRollbackCleanup cleanup =
                mock(LocalStagingKnownRollbackCleanup.class);
        LocalPublicationAuthorization authorization =
                mock(LocalPublicationAuthorization.class);
        LocalStagingReservation reservation = reservation();
        LocalStagingReservationReceipt receipt =
                mock(LocalStagingReservationReceipt.class);
        when(localStorage.provider()).thenReturn(StorageProvider.LOCAL);
        when(localStorage.location()).thenReturn(LOCAL_LOCATION);
        when(receipt.reservation()).thenReturn(reservation);
        when(reservations.reserve(ASSET_ID, SHA256, MIME_TYPE)).thenReturn(receipt);
        when(reservations.lockCurrentForOuterTransaction(reservation))
                .thenReturn(reservation);
        when(fence.acquire(any())).thenReturn(authorization);
        LocalMediaIngestCoordinator coordinator = new LocalMediaIngestCoordinator(
                localStorage, reservations, fence, cleanup);
        return new Context(
                coordinator,
                localStorage,
                reservations,
                fence,
                cleanup,
                authorization,
                reservation);
    }

    private static LocalStagingReservation reservation() {
        OffsetDateTime reservedAt = OffsetDateTime.of(
                2026, 7, 17, 0, 0, 0, 0, ZoneOffset.UTC);
        return new LocalStagingReservation(
                ASSET_ID,
                SHA256,
                MIME_TYPE,
                0,
                CLEANUP_JOB_ID,
                reservedAt,
                reservedAt.plusHours(24));
    }

    private record Context(
            LocalMediaIngestCoordinator coordinator,
            LocalStorageService localStorage,
            LocalStagingReservationService reservations,
            LocalPublicationFence fence,
            LocalStagingKnownRollbackCleanup cleanup,
            LocalPublicationAuthorization authorization,
            LocalStagingReservation reservation) {}
}
