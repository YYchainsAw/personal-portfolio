package xyz.yychainsaw.portfolio.media.staging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.yychainsaw.portfolio.media.application.MediaObjectKeys;
import xyz.yychainsaw.portfolio.media.domain.MediaStatus;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRecord;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRepository;
import xyz.yychainsaw.portfolio.system.job.JobExecutionContext;

class CleanLocalStagingObjectJobHandlerTest {
    private static final UUID ASSET_ID =
            UUID.fromString("109c8768-c09d-4fc2-bf23-f1471e105b9a");
    private static final UUID JOB_ID =
            UUID.fromString("b51f34c7-6891-4748-9661-734f10904d8b");
    private static final UUID OTHER_JOB_ID =
            UUID.fromString("43cf3d59-704f-4ee9-b5e9-62484bad936e");
    private static final String SHA256 = "b".repeat(64);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LocalStagingReservationRepository reservations =
            mock(LocalStagingReservationRepository.class);
    private final MediaAssetRepository assets = mock(MediaAssetRepository.class);
    private final LocalStagingSuccessorService successors =
            mock(LocalStagingSuccessorService.class);
    private final LocalStagingObjectCleanupPort cleanup =
            mock(LocalStagingObjectCleanupPort.class);

    private CleanLocalStagingObjectJobHandler handler;

    @BeforeEach
    void setUp() {
        handler = new CleanLocalStagingObjectJobHandler(
                reservations, assets, successors, cleanup);
    }

    @Test
    void registersTheDedicatedPerObjectJobType() {
        assertThat(handler.jobType()).isEqualTo("CLEAN_LOCAL_STAGING_OBJECT");
    }

    @Test
    void missingReservationIsAnIdempotentSuccessWithNoSideEffects() throws Exception {
        when(reservations.findByAssetId(ASSET_ID)).thenReturn(Optional.empty());

        handler.handle(context(JOB_ID), payload(0));

        verifyNoInteractions(assets, successors, cleanup);
    }

    @Test
    void staleGenerationOrUnauthenticatedJobIdDoesNothing() throws Exception {
        LocalStagingReservation current = reservation(1, JOB_ID);
        when(reservations.findByAssetId(ASSET_ID)).thenReturn(Optional.of(current));

        handler.handle(context(JOB_ID), payload(0));
        handler.handle(context(OTHER_JOB_ID), payload(1));

        verifyNoInteractions(assets, successors, cleanup);
    }

    @Test
    void currentImmutableIdentityMismatchFixedFailsBeforeAssetOrStorage() {
        LocalStagingReservation corrupt = new LocalStagingReservation(
                ASSET_ID,
                "c".repeat(64),
                "image/jpeg",
                0,
                JOB_ID,
                OffsetDateTime.now(ZoneOffset.UTC).minusDays(2),
                OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));
        when(reservations.findByAssetId(ASSET_ID)).thenReturn(Optional.of(corrupt));

        assertThatIllegalStateException()
                .isThrownBy(() -> handler.handle(context(JOB_ID), payload(0)))
                .withMessage("LOCAL_STAGING_CLEANUP_IDENTITY_MISMATCH")
                .withNoCause();
        verifyNoInteractions(assets, successors, cleanup);
    }

    @Test
    void matchingProcessingAssetAtomicallySchedulesASuccessorAndDoesNotTouchStorage()
            throws Exception {
        LocalStagingReservation current = reservation(0, JOB_ID);
        when(reservations.findByAssetId(ASSET_ID)).thenReturn(Optional.of(current));
        when(assets.findById(ASSET_ID)).thenReturn(Optional.of(asset(MediaStatus.PROCESSING)));

        handler.handle(context(JOB_ID), payload(0));

        verify(successors).scheduleFromHandler(current);
        verifyNoInteractions(cleanup);
    }

    @Test
    void exactTerminalAssetDelegatesToExactCleanupAndCleanedDoesNotEnqueue()
            throws Exception {
        LocalStagingReservation current = reservation(0, JOB_ID);
        MediaAssetRecord ready = asset(MediaStatus.READY);
        when(reservations.findByAssetId(ASSET_ID)).thenReturn(Optional.of(current));
        when(assets.findById(ASSET_ID)).thenReturn(Optional.of(ready));
        when(cleanup.cleanup(current, Optional.of(ready)))
                .thenReturn(LocalStagingObjectCleanupResult.CLEANED);

        handler.handle(context(JOB_ID), payload(0));

        verify(cleanup).cleanup(current, Optional.of(ready));
        verifyNoInteractions(successors);
    }

    @Test
    void deferredTerminalCleanupSchedulesOneSuccessor() throws Exception {
        LocalStagingReservation current = reservation(0, JOB_ID);
        MediaAssetRecord failed = asset(MediaStatus.FAILED);
        when(reservations.findByAssetId(ASSET_ID)).thenReturn(Optional.of(current));
        when(assets.findById(ASSET_ID)).thenReturn(Optional.of(failed));
        when(cleanup.cleanup(current, Optional.of(failed)))
                .thenReturn(LocalStagingObjectCleanupResult.DEFERRED);

        handler.handle(context(JOB_ID), payload(0));

        verify(successors).scheduleFromHandler(current);
    }

    @Test
    void missingAssetDelegatesFenceAndAgeDecisionToExactCleanup() throws Exception {
        LocalStagingReservation current = reservation(0, JOB_ID);
        when(reservations.findByAssetId(ASSET_ID)).thenReturn(Optional.of(current));
        when(assets.findById(ASSET_ID)).thenReturn(Optional.empty());
        when(cleanup.cleanup(current, Optional.empty()))
                .thenReturn(LocalStagingObjectCleanupResult.DEFERRED);

        handler.handle(context(JOB_ID), payload(0));

        verify(cleanup).cleanup(current, Optional.empty());
        verify(successors).scheduleFromHandler(current);
    }

    @Test
    void terminalAssetWithWrongLocalIdentityFixedFailsBeforeCleanup() throws Exception {
        LocalStagingReservation current = reservation(0, JOB_ID);
        MediaAssetRecord wrongKey = new MediaAssetRecord(
                ASSET_ID,
                StorageProvider.TENCENT_COS,
                "portfolio-bucket",
                "ap-guangzhou",
                MediaObjectKeys.originalKey(ASSET_ID, SHA256, "image/jpeg"),
                "asset.jpg",
                "image/jpeg",
                123,
                10,
                10,
                SHA256,
                MediaStatus.READY,
                null,
                0,
                Instant.parse("2026-07-01T00:00:00Z"),
                Instant.parse("2026-07-01T00:00:00Z"));
        when(reservations.findByAssetId(ASSET_ID)).thenReturn(Optional.of(current));
        when(assets.findById(ASSET_ID)).thenReturn(Optional.of(wrongKey));

        assertThatIllegalStateException()
                .isThrownBy(() -> handler.handle(context(JOB_ID), payload(0)))
                .withMessage("LOCAL_STAGING_CLEANUP_ASSET_MISMATCH")
                .withNoCause();
        verify(cleanup, never()).cleanup(current, Optional.of(wrongKey));
        verifyNoInteractions(successors);
    }

    @Test
    void malformedPayloadFixedFailsNormallyButIsPoisonNoOpAtDeadLetter() throws Exception {
        JsonNode malformed = objectMapper.readTree("{\"assetId\":\"not-a-uuid\"}");

        assertThatIllegalStateException()
                .isThrownBy(() -> handler.handle(context(JOB_ID), malformed))
                .withMessage("LOCAL_STAGING_CLEANUP_PAYLOAD_INVALID")
                .withNoCause();
        assertDoesNotThrow(() -> handler.onDeadLetter(
                context(JOB_ID), malformed, "JOB_HANDLER_FAILED"));
        verifyNoInteractions(reservations, assets, successors, cleanup);
    }

    @Test
    void currentDeadLetterSchedulesInTheAmbientTransactionButStaleAndPoisonDoNot()
            throws Exception {
        LocalStagingReservation current = reservation(0, JOB_ID);
        when(reservations.findByAssetId(ASSET_ID)).thenReturn(Optional.of(current));

        handler.onDeadLetter(context(JOB_ID), payload(0), "JOB_HANDLER_FAILED");
        handler.onDeadLetter(context(OTHER_JOB_ID), payload(0), "JOB_HANDLER_FAILED");
        handler.onDeadLetter(
                context(JOB_ID), payload(0, "c".repeat(64)), "JOB_HANDLER_FAILED");

        verify(successors).scheduleFromDeadLetter(current);
        verifyNoInteractions(assets, cleanup);
    }

    private LocalStagingReservation reservation(long generation, UUID jobId) {
        OffsetDateTime reservedAt = OffsetDateTime.parse("2026-07-01T00:00:00Z");
        return new LocalStagingReservation(
                ASSET_ID,
                SHA256,
                "image/jpeg",
                generation,
                jobId,
                reservedAt,
                reservedAt.plusHours(24));
    }

    private MediaAssetRecord asset(MediaStatus status) {
        Instant archivedAt = status == MediaStatus.ARCHIVED
                        || status == MediaStatus.PENDING_DELETE
                ? Instant.parse("2026-07-02T00:00:00Z")
                : null;
        return new MediaAssetRecord(
                ASSET_ID,
                StorageProvider.LOCAL,
                null,
                null,
                MediaObjectKeys.originalKey(ASSET_ID, SHA256, "image/jpeg"),
                "asset.jpg",
                "image/jpeg",
                123,
                10,
                10,
                SHA256,
                status,
                archivedAt,
                0,
                Instant.parse("2026-07-01T00:00:00Z"),
                Instant.parse("2026-07-01T00:00:00Z"));
    }

    private JobExecutionContext context(UUID jobId) {
        return new JobExecutionContext(jobId, "cleanup-worker", 1);
    }

    private JsonNode payload(long generation) {
        return payload(generation, SHA256);
    }

    private JsonNode payload(long generation, String sha256) {
        return objectMapper.valueToTree(new LocalStagingCleanupPayload(
                        ASSET_ID, generation, "image/jpeg", sha256)
                .toJobPayload());
    }
}
