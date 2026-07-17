package xyz.yychainsaw.portfolio.media.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.yychainsaw.portfolio.media.application.MediaObjectKeys;
import xyz.yychainsaw.portfolio.media.domain.MediaStatus;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
class MediaVariantRepositoryTest extends PostgresIntegrationTestBase {
    private static final String SOURCE_SHA = "a".repeat(64);
    private static final String VARIANT_SHA = "b".repeat(64);

    @Autowired MediaAssetRepository assets;
    @Autowired MediaVariantRepository variants;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcClient jdbc;

    @Test
    void readyVariantInsertIsCreateOnlyAndRoundTripsImmutableFields() {
        UUID assetId = UUID.randomUUID();
        MediaVariantRecord.Insert expected = variant(assetId, "w640", VARIANT_SHA, 640, 360);
        try {
            assets.insertProcessing(asset(assetId));

            assertThat(variants.insertReadyIfAbsent(expected)).isTrue();
            assertThat(variants.insertReadyIfAbsent(expected)).isFalse();

            MediaVariantRecord stored = variants.findByAssetAndName(assetId, "w640")
                    .orElseThrow();
            assertThat(stored.matches(expected)).isTrue();
            assertThat(stored.status()).isEqualTo("READY");
            assertThat(stored.createdAt()).isNotNull();
            assertThat(variants.findByAssetId(assetId)).containsExactly(stored);
        } finally {
            cleanup(assetId);
        }
    }

    @Test
    void immutableConflictIsNeverUpdatedOrSilentlyReplaced() {
        UUID assetId = UUID.randomUUID();
        MediaVariantRecord.Insert first = variant(assetId, "w640", VARIANT_SHA, 640, 360);
        MediaVariantRecord.Insert conflict = variant(
                assetId, "w640", "c".repeat(64), 640, 361);
        try {
            assets.insertProcessing(asset(assetId));
            assertThat(variants.insertReadyIfAbsent(first)).isTrue();

            assertThat(variants.insertReadyIfAbsent(conflict)).isFalse();
            MediaVariantRecord stored = variants.findByAssetAndName(assetId, "w640")
                    .orElseThrow();
            assertThat(stored.matches(first)).isTrue();
            assertThat(stored.matches(conflict)).isFalse();
        } finally {
            cleanup(assetId);
        }
    }

    @Test
    void allVariantsAndProcessingToReadyCasCommitOrRollBackTogether() {
        UUID committedId = UUID.randomUUID();
        UUID rolledBackId = UUID.randomUUID();
        try {
            assets.insertProcessing(asset(committedId));
            transactions.executeWithoutResult(status -> {
                assertThat(variants.insertReadyIfAbsent(
                                variant(committedId, "w640", VARIANT_SHA, 640, 360)))
                        .isTrue();
                assertThat(assets.markReadyIfProcessing(committedId)).isOne();
            });
            assertThat(assets.findById(committedId).orElseThrow().status())
                    .isEqualTo(MediaStatus.READY);
            assertThat(assets.findById(committedId).orElseThrow().version()).isOne();
            assertThat(variants.findByAssetId(committedId)).hasSize(1);

            assets.insertProcessing(asset(rolledBackId));
            transactions.executeWithoutResult(status -> {
                assertThat(variants.insertReadyIfAbsent(
                                variant(rolledBackId, "w640", VARIANT_SHA, 640, 360)))
                        .isTrue();
                assertThat(assets.markReadyIfProcessing(rolledBackId)).isOne();
                status.setRollbackOnly();
            });
            assertThat(assets.findById(rolledBackId).orElseThrow().status())
                    .isEqualTo(MediaStatus.PROCESSING);
            assertThat(assets.findById(rolledBackId).orElseThrow().version()).isZero();
            assertThat(variants.findByAssetId(rolledBackId)).isEmpty();
        } finally {
            cleanup(committedId);
            cleanup(rolledBackId);
        }
    }

    @Test
    void readyAndFailedTransitionsAreFencedByProcessingState() {
        UUID readyId = UUID.randomUUID();
        UUID failedId = UUID.randomUUID();
        try {
            assets.insertProcessing(asset(readyId));
            assertThat(assets.markReadyIfProcessing(readyId)).isOne();
            assertThat(assets.markReadyIfProcessing(readyId)).isZero();
            assertThat(assets.markFailedIfProcessing(readyId)).isZero();

            assets.insertProcessing(asset(failedId));
            assertThat(assets.markFailedIfProcessing(failedId)).isOne();
            assertThat(assets.markFailedIfProcessing(failedId)).isZero();
            assertThat(assets.markReadyIfProcessing(failedId)).isZero();

            assertThat(assets.findById(readyId).orElseThrow().status())
                    .isEqualTo(MediaStatus.READY);
            assertThat(assets.findById(failedId).orElseThrow().status())
                    .isEqualTo(MediaStatus.FAILED);
        } finally {
            cleanup(readyId);
            cleanup(failedId);
        }
    }

    @Test
    void readyParentRejectsLateVariantEvenWhenItWaitedBehindCompletionLock()
            throws Exception {
        UUID assetId = UUID.randomUUID();
        CountDownLatch completionLocked = new CountDownLatch(1);
        CountDownLatch allowCompletionCommit = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            assets.insertProcessing(asset(assetId));
            Future<?> completion = executor.submit(() -> transactions.executeWithoutResult(status -> {
                assertThat(variants.lockAssetGraph(assetId)).isTrue();
                assertThat(variants.insertReadyIfAbsent(
                                variant(assetId, "w640", VARIANT_SHA, 640, 360)))
                        .isTrue();
                assertThat(assets.markReadyIfProcessing(assetId)).isOne();
                completionLocked.countDown();
                try {
                    assertThat(allowCompletionCommit.await(10, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("test interrupted");
                }
            }));

            assertThat(completionLocked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Boolean> lateInsert = executor.submit(() -> transactions.execute(status ->
                    variants.insertReadyIfAbsent(
                            variant(assetId, "w1280", "c".repeat(64), 1280, 720))));

            Thread.sleep(200);
            assertThat(lateInsert.isDone()).isFalse();
            allowCompletionCommit.countDown();
            completion.get(10, TimeUnit.SECONDS);

            assertThat(lateInsert.get(10, TimeUnit.SECONDS)).isFalse();
            assertThat(assets.findById(assetId).orElseThrow().status())
                    .isEqualTo(MediaStatus.READY);
            assertThat(variants.findByAssetId(assetId))
                    .extracting(MediaVariantRecord::variantName)
                    .containsExactly("w640");
        } finally {
            allowCompletionCommit.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            cleanup(assetId);
        }
    }

    private static MediaAssetRecord.Insert asset(UUID id) {
        return new MediaAssetRecord.Insert(
                id,
                StorageProvider.LOCAL,
                null,
                null,
                MediaObjectKeys.originalKey(id, SOURCE_SHA, "image/jpeg"),
                "source.jpg",
                "image/jpeg",
                100,
                1280,
                720,
                SOURCE_SHA);
    }

    private static MediaVariantRecord.Insert variant(
            UUID assetId,
            String name,
            String sha256,
            int width,
            int height) {
        return new MediaVariantRecord.Insert(
                UUID.randomUUID(),
                assetId,
                name,
                "JPEG",
                "variants/" + assetId + '/' + name + '/' + sha256 + ".jpg",
                "image/jpeg",
                50,
                width,
                height,
                sha256);
    }

    private void cleanup(UUID assetId) {
        JdbcClient owner = migratorJdbc();
        owner.sql("delete from portfolio.media_variant where asset_id=:id")
                .param("id", assetId)
                .update();
        owner.sql("delete from portfolio.media_asset where id=:id")
                .param("id", assetId)
                .update();
    }
}
