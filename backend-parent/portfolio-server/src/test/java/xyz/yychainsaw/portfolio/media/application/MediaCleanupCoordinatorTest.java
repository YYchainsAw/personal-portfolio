package xyz.yychainsaw.portfolio.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import xyz.yychainsaw.portfolio.audit.AuditCommand;
import xyz.yychainsaw.portfolio.audit.AuditOutcome;
import xyz.yychainsaw.portfolio.audit.AuditService;
import xyz.yychainsaw.portfolio.media.domain.MediaStatus;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRecord;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRepository;
import xyz.yychainsaw.portfolio.media.persistence.MediaTranslationRepository;
import xyz.yychainsaw.portfolio.media.persistence.MediaVariantRecord;
import xyz.yychainsaw.portfolio.media.persistence.MediaVariantRepository;
import xyz.yychainsaw.portfolio.system.job.ScheduledJobInserter;

class MediaCleanupCoordinatorTest {
    private static final UUID ASSET_ID =
            UUID.fromString("11111111-2222-4333-8444-555555555555");
    private static final Instant NOW = Instant.parse("2026-07-17T03:00:00Z");
    private static final Instant CUTOFF = NOW.minus(Duration.ofDays(30));
    private static final long VERSION = 7;
    private static final String SHA = "a".repeat(64);

    private final MediaAssetRepository assets = mock(MediaAssetRepository.class);
    private final MediaVariantRepository variants = mock(MediaVariantRepository.class);
    private final MediaTranslationRepository translations =
            mock(MediaTranslationRepository.class);
    private final ScheduledJobInserter jobs = mock(ScheduledJobInserter.class);
    private final AuditService audit = mock(AuditService.class);
    private MediaReferenceChecker checker;
    private MediaCleanupCoordinator coordinator;

    @BeforeEach
    void setUp() {
        checker = mock(MediaReferenceChecker.class);
        coordinator = new MediaCleanupCoordinator(
                assets,
                variants,
                translations,
                jobs,
                audit,
                new MediaReferenceResolver(List.of(checker)));
    }

    @Test
    void scanLocksRechecksTransitionsWithoutVersionAdvanceAndEnqueuesAtomically() {
        MediaAssetRecord archived = asset(MediaStatus.ARCHIVED, CUTOFF, VERSION);
        when(assets.findByIdForUpdate(ASSET_ID)).thenReturn(Optional.of(archived));
        when(checker.findReferences(ASSET_ID)).thenReturn(List.of());
        when(assets.markPendingDelete(ASSET_ID, VERSION, CUTOFF)).thenReturn(1);

        assertThat(coordinator.stageForDeletion(ASSET_ID, CUTOFF)).isTrue();

        verify(assets).markPendingDelete(ASSET_ID, VERSION, CUTOFF);
        verify(jobs).insertAfter(
                "DELETE_MEDIA_ASSET",
                "media-delete:" + ASSET_ID + ":" + VERSION,
                Map.of(
                        "assetId", ASSET_ID.toString(),
                        "version", VERSION,
                        "cutoffEpochSecond", CUTOFF.getEpochSecond()),
                Duration.ZERO);
    }

    @Test
    void scanLeavesAReferencedArchiveUntouchedAndEnqueuesNothing() {
        when(assets.findByIdForUpdate(ASSET_ID))
                .thenReturn(Optional.of(asset(MediaStatus.ARCHIVED, CUTOFF, VERSION)));
        when(checker.findReferences(ASSET_ID)).thenReturn(List.of(
                new MediaReference("HISTORICAL_REVISION", UUID.randomUUID())));

        assertThat(coordinator.stageForDeletion(ASSET_ID, CUTOFF)).isFalse();

        verify(assets, never()).markPendingDelete(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any());
        verify(jobs, never()).insertAfter(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void finalCheckKeepsPendingAssetQuarantinedWhenAReferenceAppears() {
        when(assets.findByIdForUpdate(ASSET_ID)).thenReturn(Optional.of(
                asset(MediaStatus.PENDING_DELETE, CUTOFF, VERSION)));
        when(checker.findReferences(ASSET_ID)).thenReturn(List.of(
                new MediaReference("WORKSPACE", UUID.randomUUID())));
        assertThat(coordinator.prepareDeletion(
                        new MediaDeletionRequest(ASSET_ID, VERSION, CUTOFF), CUTOFF))
                .isEmpty();

        verify(variants, never()).findByAssetId(ASSET_ID);
        verifySanitizedAudit("REFERENCE_BLOCKED", AuditOutcome.FAILURE, 0);
    }

    @Test
    void exactThirtyDaysIsEligibleWhileTwentyNineDaysIsNot() {
        MediaAssetRecord exact = asset(MediaStatus.PENDING_DELETE, CUTOFF, VERSION);
        MediaAssetRecord young = asset(
                MediaStatus.PENDING_DELETE, NOW.minus(Duration.ofDays(29)), VERSION);
        when(assets.findByIdForUpdate(ASSET_ID))
                .thenReturn(Optional.of(exact), Optional.of(young));
        when(checker.findReferences(ASSET_ID)).thenReturn(List.of());
        when(variants.findByAssetId(ASSET_ID)).thenReturn(List.of());

        assertThat(coordinator.prepareDeletion(
                        new MediaDeletionRequest(ASSET_ID, VERSION, CUTOFF), CUTOFF))
                .isPresent();
        assertThat(coordinator.prepareDeletion(
                        new MediaDeletionRequest(ASSET_ID, VERSION, NOW), CUTOFF))
                .isEmpty();
    }

    @Test
    void successfulFinishDeletesChildrenThenVersionFencedParentAndAuditsNoKeys() {
        MediaDeletionPlan plan = new MediaDeletionPlan(
                asset(MediaStatus.PENDING_DELETE, CUTOFF, VERSION),
                CUTOFF,
                List.of("original", "variant"));
        when(assets.findByIdForUpdate(ASSET_ID)).thenReturn(Optional.of(plan.asset()));
        when(assets.deletePending(ASSET_ID, VERSION)).thenReturn(1);

        assertThat(coordinator.finishDeletion(plan)).isTrue();

        InOrder order = inOrder(assets, translations, variants, audit);
        order.verify(assets).findByIdForUpdate(ASSET_ID);
        order.verify(translations).deleteByAssetId(ASSET_ID);
        order.verify(variants).deleteByAssetId(ASSET_ID);
        order.verify(assets).deletePending(ASSET_ID, VERSION);
        order.verify(audit).record(org.mockito.ArgumentMatchers.any(AuditCommand.class));
        verifySanitizedAudit("DELETED", AuditOutcome.SUCCESS, 2);
    }

    @Test
    void terminalDeadLetterKeepsTheSamePendingVersionQuarantined() {
        when(assets.findByIdForUpdate(ASSET_ID)).thenReturn(Optional.of(
                asset(MediaStatus.PENDING_DELETE, CUTOFF, VERSION)));

        coordinator.recordDeadLetter(
                new MediaDeletionRequest(ASSET_ID, VERSION, CUTOFF));

        verifySanitizedAudit("DEAD_LETTER_PENDING", AuditOutcome.FAILURE, 0);
    }

    private void verifySanitizedAudit(
            String result, AuditOutcome outcome, int objectCount) {
        ArgumentCaptor<AuditCommand> command = ArgumentCaptor.forClass(AuditCommand.class);
        verify(audit).record(command.capture());
        assertThat(command.getValue().actorAdminId()).isNull();
        assertThat(command.getValue().targetId()).isEqualTo(ASSET_ID.toString());
        assertThat(command.getValue().outcome()).isEqualTo(outcome);
        assertThat(command.getValue().metadata()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "assetId", ASSET_ID.toString(),
                "provider", StorageProvider.LOCAL.name(),
                "objectCount", Integer.toString(objectCount),
                "result", result));
        assertThat(command.getValue().metadata().toString())
                .doesNotContain("original")
                .doesNotContain("variant")
                .doesNotContain("objectKey");
    }

    private static MediaAssetRecord asset(
            MediaStatus status, Instant archivedAt, long version) {
        return new MediaAssetRecord(
                ASSET_ID,
                StorageProvider.LOCAL,
                null,
                null,
                MediaObjectKeys.originalKey(ASSET_ID, SHA, "image/jpeg"),
                "portfolio.jpg",
                "image/jpeg",
                1024,
                32,
                32,
                SHA,
                status,
                archivedAt,
                version,
                Instant.parse("2026-01-01T00:00:00Z"),
                NOW);
    }
}
