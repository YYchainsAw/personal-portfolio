package xyz.yychainsaw.portfolio.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import xyz.yychainsaw.portfolio.media.domain.MediaStatus;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRecord;
import xyz.yychainsaw.portfolio.media.storage.StorageException;
import xyz.yychainsaw.portfolio.media.storage.StorageLocation;
import xyz.yychainsaw.portfolio.media.storage.StorageRouter;
import xyz.yychainsaw.portfolio.media.storage.StorageService;

class ArchivedMediaCleanupJobHandlerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID ASSET_ID =
            UUID.fromString("11111111-2222-4333-8444-555555555555");
    private static final Instant NOW = Instant.parse("2026-07-17T03:00:00Z");
    private static final Instant CUTOFF = NOW.minus(Duration.ofDays(30));
    private static final long VERSION = 7;
    private static final String SHA = "a".repeat(64);
    private static final String ORIGINAL =
            MediaObjectKeys.originalKey(ASSET_ID, SHA, "application/pdf");
    private static final String OTHER = "variants/" + ASSET_ID + "/w640/x.jpg";

    private final MediaCleanupCoordinator coordinator = mock(MediaCleanupCoordinator.class);
    private final MediaLifecycleBarrier barrier = mock(MediaLifecycleBarrier.class);
    private final StorageRouter router = mock(StorageRouter.class);
    private final StorageService storage = mock(StorageService.class);
    private final AutoCloseable lease = mock(AutoCloseable.class);
    private ArchivedMediaCleanupJobHandler handler;

    @BeforeEach
    void setUp() {
        when(barrier.acquireExclusiveDeletionLease()).thenReturn(lease);
        when(router.require(StorageProvider.LOCAL)).thenReturn(storage);
        when(storage.provider()).thenReturn(StorageProvider.LOCAL);
        when(storage.location()).thenReturn(
                new StorageLocation(StorageProvider.LOCAL, null, null));
        handler = new ArchivedMediaCleanupJobHandler(
                coordinator,
                barrier,
                router,
                Clock.fixed(NOW, ZoneOffset.UTC),
                resolverWithChecker());
    }

    @Test
    void deduplicatesDocumentAndOriginalKeysAndReleasesBarrierAfterSuccess()
            throws Exception {
        MediaDeletionPlan plan = plan(List.of(ORIGINAL, ORIGINAL, OTHER));
        when(coordinator.prepareDeletion(
                new MediaDeletionRequest(ASSET_ID, VERSION, CUTOFF), CUTOFF))
                .thenReturn(Optional.of(plan));
        when(coordinator.finishDeletion(plan)).thenReturn(true);

        handler.handle(deletePayload());

        InOrder order = inOrder(barrier, storage, coordinator, lease);
        order.verify(barrier).acquireExclusiveDeletionLease();
        order.verify(coordinator).prepareDeletion(
                new MediaDeletionRequest(ASSET_ID, VERSION, CUTOFF), CUTOFF);
        order.verify(storage).delete(ORIGINAL);
        order.verify(storage).delete(OTHER);
        order.verify(coordinator).finishDeletion(plan);
        order.verify(lease).close();
        verify(storage, org.mockito.Mockito.times(1)).delete(ORIGINAL);
        verify(storage, org.mockito.Mockito.times(1)).delete(OTHER);
    }

    @Test
    void partialFailureLeavesDatabasePendingAndRetryTreatsMissingObjectsAsSuccess()
            throws Exception {
        MediaDeletionPlan plan = plan(List.of(ORIGINAL, OTHER));
        when(coordinator.prepareDeletion(
                new MediaDeletionRequest(ASSET_ID, VERSION, CUTOFF), CUTOFF))
                .thenReturn(Optional.of(plan));
        org.mockito.Mockito.doThrow(new StorageException("PROVIDER_FAILED"))
                .doNothing()
                .when(storage)
                .delete(OTHER);
        when(coordinator.finishDeletion(plan)).thenReturn(true);

        assertThatThrownBy(() -> handler.handle(deletePayload()))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("MEDIA_DELETE_FAILED")
                .hasNoCause();
        verify(coordinator, never()).finishDeletion(plan);
        verify(coordinator).auditDeletionFailure(plan, "PROVIDER_DELETE_FAILED");
        verify(lease).close();

        assertThatCode(() -> handler.handle(deletePayload()))
                .doesNotThrowAnyException();
        verify(storage, org.mockito.Mockito.times(2)).delete(ORIGINAL);
        verify(storage, org.mockito.Mockito.times(2)).delete(OTHER);
        verify(coordinator).finishDeletion(plan);
        verify(lease, org.mockito.Mockito.times(2)).close();
    }

    @Test
    void locationMismatchFailsBeforeAnyObjectDeleteAndStillReleasesBarrier()
            throws Exception {
        MediaDeletionPlan plan = plan(List.of(ORIGINAL));
        when(coordinator.prepareDeletion(
                new MediaDeletionRequest(ASSET_ID, VERSION, CUTOFF), CUTOFF))
                .thenReturn(Optional.of(plan));
        when(storage.location()).thenReturn(new StorageLocation(
                StorageProvider.TENCENT_COS, "wrong-bucket", "wrong-region"));

        assertThatThrownBy(() -> handler.handle(deletePayload()))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("MEDIA_DELETE_FAILED")
                .hasNoCause();

        verify(storage, never()).delete(org.mockito.ArgumentMatchers.anyString());
        verify(coordinator).auditDeletionFailure(plan, "STORAGE_LOCATION_MISMATCH");
        verify(lease).close();
    }

    @Test
    void noOpAfterFinalReloadStillReleasesBarrier() throws Exception {
        when(coordinator.prepareDeletion(
                new MediaDeletionRequest(ASSET_ID, VERSION, CUTOFF), CUTOFF))
                .thenReturn(Optional.empty());

        handler.handle(deletePayload());

        verify(router, never()).require(org.mockito.ArgumentMatchers.any());
        verify(lease).close();
    }

    @Test
    void deadLetterUsesStrictPayloadAndRecordsVersionFencedPendingState()
            throws Exception {
        handler.onDeadLetter(deletePayload(), "JOB_HANDLER_FAILED");

        verify(coordinator).recordDeadLetter(
                new MediaDeletionRequest(ASSET_ID, VERSION, CUTOFF));
        verify(barrier, never()).acquireExclusiveDeletionLease();
    }

    @Test
    void deadLetterIgnoresPoisonPayloadSoTheJobCanRemainDead() {
        JsonNode poison = JSON.valueToTree(Map.of(
                "assetId", "not-a-uuid",
                "version", VERSION,
                "cutoffEpochSecond", CUTOFF.getEpochSecond()));

        assertThatCode(() -> handler.onDeadLetter(poison, "JOB_ATTEMPTS_EXHAUSTED"))
                .doesNotThrowAnyException();

        verify(coordinator, never()).recordDeadLetter(
                org.mockito.ArgumentMatchers.any());
        verify(barrier, never()).acquireExclusiveDeletionLease();
    }

    @Test
    void rejectsMalformedPayloadsBeforeTakingTheBarrier() {
        List<JsonNode> invalid = List.of(
                JSON.nullNode(),
                JSON.createArrayNode(),
                JSON.createObjectNode(),
                JSON.valueToTree(Map.of(
                        "assetId", ASSET_ID.toString(),
                        "version", VERSION)),
                JSON.valueToTree(Map.of(
                        "assetId", "1-1-1-1-1",
                        "version", VERSION,
                        "cutoffEpochSecond", CUTOFF.getEpochSecond())),
                JSON.valueToTree(Map.of(
                        "assetId", ASSET_ID.toString(),
                        "version", -1,
                        "cutoffEpochSecond", CUTOFF.getEpochSecond())),
                JSON.valueToTree(Map.of(
                        "assetId", ASSET_ID.toString(),
                        "version", BigInteger.ONE.shiftLeft(80),
                        "cutoffEpochSecond", CUTOFF.getEpochSecond())),
                JSON.valueToTree(Map.of(
                        "assetId", ASSET_ID.toString(),
                        "version", VERSION,
                        "cutoffEpochSecond", CUTOFF.plusSeconds(1).getEpochSecond())),
                JSON.valueToTree(Map.of(
                        "assetId", ASSET_ID.toString(),
                        "version", VERSION,
                        "cutoffEpochSecond", CUTOFF.getEpochSecond(),
                        "extra", true)));

        for (JsonNode payload : invalid) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> handler.handle(payload))
                    .withMessage("MEDIA_DELETE_PAYLOAD_INVALID")
                    .withNoCause();
        }
        verify(barrier, never()).acquireExclusiveDeletionLease();
    }

    @Test
    void exposesExactDeleteJobType() {
        assertThat(handler.jobType()).isEqualTo("DELETE_MEDIA_ASSET");
    }

    private static MediaDeletionPlan plan(List<String> keys) {
        return new MediaDeletionPlan(
                new MediaAssetRecord(
                        ASSET_ID,
                        StorageProvider.LOCAL,
                        null,
                        null,
                        ORIGINAL,
                        "resume.pdf",
                        "application/pdf",
                        1024,
                        null,
                        null,
                        SHA,
                        MediaStatus.PENDING_DELETE,
                        CUTOFF,
                        VERSION,
                        Instant.parse("2026-01-01T00:00:00Z"),
                        NOW),
                CUTOFF,
                keys);
    }

    private static JsonNode deletePayload() {
        return JSON.valueToTree(Map.of(
                "assetId", ASSET_ID.toString(),
                "version", VERSION,
                "cutoffEpochSecond", CUTOFF.getEpochSecond()));
    }

    private static MediaReferenceResolver resolverWithChecker() {
        return new MediaReferenceResolver(List.of(assetId -> List.of()));
    }
}
