package xyz.yychainsaw.portfolio.media.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.yychainsaw.portfolio.audit.AuditService;
import xyz.yychainsaw.portfolio.media.application.MediaCleanupCoordinator;
import xyz.yychainsaw.portfolio.media.application.MediaDeletionPlan;
import xyz.yychainsaw.portfolio.media.application.MediaDeletionRequest;
import xyz.yychainsaw.portfolio.media.application.MediaObjectKeys;
import xyz.yychainsaw.portfolio.media.application.MediaReference;
import xyz.yychainsaw.portfolio.media.application.MediaReferenceResolver;
import xyz.yychainsaw.portfolio.media.domain.MediaStatus;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;
import xyz.yychainsaw.portfolio.system.job.ScheduledJobInserter;

@SpringBootTest
@Isolated
class MediaCleanupPersistenceIntegrationTest extends PostgresIntegrationTestBase {
    private static final Instant NOW = Instant.parse("2026-07-17T03:00:00Z");
    private static final Instant CUTOFF = NOW.minus(Duration.ofDays(30));
    private static final String SHA = "a".repeat(64);

    @Autowired MediaAssetRepository assets;
    @Autowired MediaVariantRepository variants;
    @Autowired MediaTranslationRepository translations;
    @Autowired ScheduledJobInserter jobs;
    @Autowired AuditService audit;
    @Autowired TransactionTemplate transactions;

    private final List<UUID> fixtures = new ArrayList<>();

    @AfterEach
    void removeMutableFixtures() {
        JdbcClient owner = migratorJdbc();
        for (UUID assetId : fixtures) {
            owner.sql("delete from portfolio.background_job where idempotency_key like :key")
                    .param("key", "media-delete:" + assetId + ":%")
                    .update();
            owner.sql("delete from portfolio.media_translation where asset_id=:id")
                    .param("id", assetId)
                    .update();
            owner.sql("delete from portfolio.media_variant where asset_id=:id")
                    .param("id", assetId)
                    .update();
            owner.sql("delete from portfolio.media_asset where id=:id")
                    .param("id", assetId)
                    .update();
        }
    }

    @Test
    void cutoffIncludesExactlyThirtyDaysAndExcludesTwentyNineDays() {
        UUID exact = insertImage("exact-thirty.jpg");
        UUID young = insertImage("twenty-nine.jpg");
        archiveFixture(exact, CUTOFF, 1);
        archiveFixture(young, NOW.minus(Duration.ofDays(29)), 1);

        assertThat(assets.findArchivedIdsAtOrBefore(CUTOFF, null, 10))
                .contains(exact)
                .doesNotContain(young);
        assertThat(assets.markPendingDelete(exact, 1, CUTOFF)).isOne();

        MediaAssetRecord pending = assets.findById(exact).orElseThrow();
        assertThat(pending.status()).isEqualTo(MediaStatus.PENDING_DELETE);
        assertThat(pending.version()).isOne();
        assertThat(assets.markPendingDelete(young, 1, CUTOFF)).isZero();
    }

    @Test
    void archivedCandidateQueryUsesABoundedUuidKeysetCursor() {
        List<UUID> archived = new ArrayList<>(List.of(
                insertImage("keyset-a.jpg"),
                insertImage("keyset-b.jpg"),
                insertImage("keyset-c.jpg")));
        archived.forEach(assetId -> archiveFixture(assetId, CUTOFF, 1));
        archived.sort(Comparator.comparing(UUID::toString));

        List<UUID> firstPage = assets.findArchivedIdsAtOrBefore(CUTOFF, null, 2);
        List<UUID> secondPage = assets.findArchivedIdsAtOrBefore(
                CUTOFF, firstPage.get(firstPage.size() - 1), 2);

        assertThat(firstPage).containsExactlyElementsOf(archived.subList(0, 2));
        assertThat(secondPage).containsExactly(archived.get(2));
    }

    @Test
    void finalReferenceKeepsPendingAssetQuarantinedInTheSameTransaction() {
        UUID assetId = insertImage("referenced.jpg");
        archiveFixture(assetId, CUTOFF, 4);
        assertThat(assets.markPendingDelete(assetId, 4, CUTOFF)).isOne();
        MediaCleanupCoordinator referenced = coordinatorWithReference(assetId);

        java.util.Optional<MediaDeletionPlan> prepared = transactions.execute(
                status -> referenced.prepareDeletion(
                        new MediaDeletionRequest(assetId, 4, CUTOFF), CUTOFF));
        assertThat(prepared).isEmpty();

        MediaAssetRecord quarantined = assets.findById(assetId).orElseThrow();
        assertThat(quarantined.status()).isEqualTo(MediaStatus.PENDING_DELETE);
        assertThat(quarantined.version()).isEqualTo(4);
        assertThat(quarantined.archivedAt()).isEqualTo(CUTOFF);
    }

    @Test
    void scanTransitionAndDeleteJobAreIdempotentWithVersionUnchanged() {
        UUID assetId = insertImage("idempotent-scan.jpg");
        archiveFixture(assetId, CUTOFF, 9);
        MediaCleanupCoordinator coordinator = coordinatorWithoutReferences();

        Boolean firstScan = transactions.execute(
                status -> coordinator.stageForDeletion(assetId, CUTOFF));
        Boolean repeatedScan = transactions.execute(
                status -> coordinator.stageForDeletion(assetId, CUTOFF));
        assertThat(firstScan).isTrue();
        assertThat(repeatedScan).isFalse();

        MediaAssetRecord pending = assets.findById(assetId).orElseThrow();
        assertThat(pending.status()).isEqualTo(MediaStatus.PENDING_DELETE);
        assertThat(pending.version()).isEqualTo(9);
        assertThat(migratorJdbc().sql("""
                        select count(*)
                        from portfolio.background_job
                        where idempotency_key=:key
                          and job_type='DELETE_MEDIA_ASSET'
                          and payload = jsonb_build_object(
                              'assetId', :assetId,
                              'version', cast(:version as bigint),
                              'cutoffEpochSecond', cast(:cutoff as bigint))
                        """)
                .param("key", "media-delete:" + assetId + ":9")
                .param("assetId", assetId.toString())
                .param("version", 9)
                .param("cutoff", CUTOFF.getEpochSecond())
                .query(Integer.class)
                .single()).isOne();
    }

    @Test
    void cleanupUpdateLockWaitsForReferenceWriterShareLockThenRechecks()
            throws Exception {
        UUID assetId = insertImage("reference-race.jpg");
        archiveFixture(assetId, CUTOFF, 3);
        AtomicBoolean referenced = new AtomicBoolean();
        MediaCleanupCoordinator coordinator = new MediaCleanupCoordinator(
                assets,
                variants,
                translations,
                jobs,
                audit,
                new MediaReferenceResolver(List.of(id -> referenced.get()
                        ? List.of(new MediaReference("WORKSPACE", UUID.randomUUID()))
                        : List.of())));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch shareAcquired = new CountDownLatch(1);
        CountDownLatch releaseShare = new CountDownLatch(1);
        try {
            Future<?> referenceWriter = executor.submit(() ->
                    transactions.executeWithoutResult(status -> {
                        assertThat(assets.findByIdForShare(assetId)).isPresent();
                        referenced.set(true);
                        shareAcquired.countDown();
                        await(releaseShare);
                    }));
            assertThat(shareAcquired.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Boolean> cleanup = executor.submit(() -> transactions.execute(
                    status -> coordinator.stageForDeletion(assetId, CUTOFF)));
            assertThatThrownBy(() -> cleanup.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            releaseShare.countDown();
            referenceWriter.get(10, TimeUnit.SECONDS);
            assertThat(cleanup.get(10, TimeUnit.SECONDS)).isFalse();
            assertThat(assets.findById(assetId).orElseThrow().status())
                    .isEqualTo(MediaStatus.ARCHIVED);
        } finally {
            releaseShare.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void pdfPlanDeduplicatesSharedOriginalAndDocumentKeyAndFinalDeleteIsAtomic() {
        UUID assetId = insertPdfWithDocumentVariant();
        translations.replaceAll(assetId, List.of(new MediaTranslationRecord(
                assetId,
                "en",
                "Resume",
                "Portfolio resume",
                "Yijiaxuan Yi",
                "https://example.com/resume")));
        archiveFixture(assetId, CUTOFF, 2);
        assertThat(assets.markPendingDelete(assetId, 2, CUTOFF)).isOne();
        MediaCleanupCoordinator coordinator = coordinatorWithoutReferences();

        MediaDeletionPlan plan = transactions.execute(status -> coordinator.prepareDeletion(
                        new MediaDeletionRequest(assetId, 2, CUTOFF), CUTOFF))
                .orElseThrow();

        assertThat(plan.objectKeys())
                .containsExactly(plan.asset().objectKey());

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
                    assertThat(coordinator.finishDeletion(plan)).isTrue();
                    throw new IllegalStateException("force cleanup rollback");
                }))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("force cleanup rollback");
        assertThat(assets.findById(assetId)).isPresent();
        assertThat(variants.findByAssetId(assetId)).hasSize(1);
        assertThat(translations.findByAssetId(assetId)).hasSize(1);

        Boolean deleted = transactions.execute(
                status -> coordinator.finishDeletion(plan));
        assertThat(deleted).isTrue();
        assertThat(assets.findById(assetId)).isEmpty();
        assertThat(variants.findByAssetId(assetId)).isEmpty();
        assertThat(translations.findByAssetId(assetId)).isEmpty();
    }

    @Test
    void readyShaLookupIsFilteredAndStablyOrdered() {
        UUID later = insertImage("later.jpg");
        UUID earlierHigh = insertImage("earlier-high.jpg");
        UUID earlierLow = insertImage("earlier-low.jpg");
        UUID failed = insertImage("failed.jpg");
        setCreatedAt(later, "2026-02-02T00:00:00Z");
        setCreatedAt(earlierHigh, "2026-02-01T00:00:00Z");
        setCreatedAt(earlierLow, "2026-02-01T00:00:00Z");
        setFailed(failed);

        List<UUID> expected = new ArrayList<>(List.of(earlierHigh, earlierLow));
        expected.sort(Comparator.comparing(UUID::toString));
        expected.add(later);

        assertThat(assets.findReadyBySha256(SHA))
                .extracting(MediaAssetRecord::id)
                .containsSubsequence(expected.toArray(UUID[]::new))
                .doesNotContain(failed);
    }

    private MediaCleanupCoordinator coordinatorWithoutReferences() {
        return new MediaCleanupCoordinator(
                assets,
                variants,
                translations,
                jobs,
                audit,
                new MediaReferenceResolver(List.of(assetId -> List.of())));
    }

    private MediaCleanupCoordinator coordinatorWithReference(UUID referencedAssetId) {
        UUID referenceId = UUID.randomUUID();
        return new MediaCleanupCoordinator(
                assets,
                variants,
                translations,
                jobs,
                audit,
                new MediaReferenceResolver(List.of(assetId -> assetId.equals(referencedAssetId)
                        ? List.of(new MediaReference("HISTORICAL_REVISION", referenceId))
                        : List.of())));
    }

    private UUID insertImage(String filename) {
        UUID assetId = UUID.randomUUID();
        fixtures.add(assetId);
        assets.insertProcessing(new MediaAssetRecord.Insert(
                assetId,
                StorageProvider.LOCAL,
                null,
                null,
                MediaObjectKeys.originalKey(assetId, SHA, "image/jpeg"),
                filename,
                "image/jpeg",
                1024,
                32,
                32,
                SHA));
        assertThat(assets.markReadyIfProcessing(assetId)).isOne();
        return assetId;
    }

    private UUID insertPdfWithDocumentVariant() {
        UUID assetId = UUID.randomUUID();
        fixtures.add(assetId);
        String originalKey = MediaObjectKeys.originalKey(
                assetId, SHA, "application/pdf");
        assets.insertProcessing(new MediaAssetRecord.Insert(
                assetId,
                StorageProvider.LOCAL,
                null,
                null,
                originalKey,
                "resume.pdf",
                "application/pdf",
                2048,
                null,
                null,
                SHA));
        variants.insertReadyIfAbsent(new MediaVariantRecord.Insert(
                UUID.randomUUID(),
                assetId,
                "document",
                "PDF",
                originalKey,
                "application/pdf",
                2048,
                null,
                null,
                SHA));
        assertThat(assets.markReadyIfProcessing(assetId)).isOne();
        return assetId;
    }

    private static void archiveFixture(UUID assetId, Instant archivedAt, long version) {
        migratorJdbc().sql("""
                        update portfolio.media_asset
                        set status='ARCHIVED', archived_at=:archivedAt, version=:version
                        where id=:id
                        """)
                .param(
                        "archivedAt",
                        OffsetDateTime.parse(archivedAt.toString()),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .param("version", version, Types.BIGINT)
                .param("id", assetId, Types.OTHER)
                .update();
    }

    private static void setCreatedAt(UUID assetId, String createdAt) {
        migratorJdbc().sql("""
                        update portfolio.media_asset
                        set created_at=:createdAt
                        where id=:id
                        """)
                .param(
                        "createdAt",
                        OffsetDateTime.parse(createdAt),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .param("id", assetId, Types.OTHER)
                .update();
    }

    private static void setFailed(UUID assetId) {
        migratorJdbc().sql("""
                        update portfolio.media_asset
                        set status='FAILED', archived_at=null
                        where id=:id
                        """)
                .param("id", assetId, Types.OTHER)
                .update();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("cleanup race test timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("cleanup race test interrupted", interrupted);
        }
    }
}
