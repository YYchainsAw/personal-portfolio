package xyz.yychainsaw.portfolio.media.persistence;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.yychainsaw.portfolio.media.application.MediaObjectKeys;
import xyz.yychainsaw.portfolio.media.domain.MediaStatus;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
@Isolated
class MediaManagementPersistenceIntegrationTest extends PostgresIntegrationTestBase {
    private static final String SHA256 = "a".repeat(64);
    private static final String MIME_TYPE = "image/jpeg";

    @Autowired MediaAssetRepository assets;
    @Autowired MediaTranslationRepository translations;
    @Autowired TransactionTemplate transactions;

    @BeforeEach
    @AfterEach
    void clearMediaFixtures() {
        JdbcClient owner = migratorJdbc();
        owner.sql("delete from portfolio.media_translation").update();
        owner.sql("delete from portfolio.media_variant").update();
        owner.sql("delete from portfolio.media_asset").update();
    }

    @Test
    void translationsReplaceReadInLocaleOrderAndDeleteExactlyOnce() {
        UUID assetId = UUID.randomUUID();
        insertAsset(assetId, "translations.jpg");
        MediaTranslationRecord chinese = translation(
                assetId,
                "zh-CN",
                "中文替代文本",
                "中文说明",
                "易嘉轩",
                "https://example.com/zh/source");
        MediaTranslationRecord english = translation(
                assetId,
                "en",
                "English alternative text",
                "English caption",
                "Yijiaxuan Yi",
                null);

        translations.replaceAll(assetId, List.of(chinese, english));

        assertThat(translations.findByAssetId(assetId))
                .containsExactly(english, chinese);

        MediaTranslationRecord replacement = translation(
                assetId,
                "zh-CN",
                "更新后的替代文本",
                null,
                null,
                "https://portfolio.example/assets/1");
        translations.replaceAll(assetId, List.of(replacement));

        assertThat(translations.findByAssetId(assetId))
                .containsExactly(replacement);
        assertThat(translations.deleteByAssetId(assetId)).isOne();
        assertThat(translations.deleteByAssetId(assetId)).isZero();
        assertThat(translations.findByAssetId(assetId)).isEmpty();
    }

    @Test
    void pageCountsFiltersAndOrdersByCreatedAtThenIdDescending() {
        UUID oldestReady = uuid("00000000-0000-4000-8000-000000000001");
        UUID tiedReadyLow = uuid("00000000-0000-4000-8000-000000000010");
        UUID tiedReadyHigh = uuid("00000000-0000-4000-8000-000000000020");
        UUID failed = uuid("00000000-0000-4000-8000-000000000030");
        UUID archived = uuid("00000000-0000-4000-8000-000000000040");

        insertAsset(oldestReady, "oldest-ready.jpg");
        insertAsset(tiedReadyLow, "tied-ready-low.jpg");
        insertAsset(tiedReadyHigh, "tied-ready-high.jpg");
        insertAsset(failed, "failed.jpg");
        insertAsset(archived, "archived.jpg");
        setFixtureState(
                oldestReady, MediaStatus.READY, "2026-01-01T00:00:00Z");
        setFixtureState(
                tiedReadyLow, MediaStatus.READY, "2026-01-02T00:00:00Z");
        setFixtureState(
                tiedReadyHigh, MediaStatus.READY, "2026-01-02T00:00:00Z");
        setFixtureState(failed, MediaStatus.FAILED, "2026-01-03T00:00:00Z");
        setFixtureState(archived, MediaStatus.ARCHIVED, "2026-01-04T00:00:00Z");

        MediaAssetPage firstPage = assets.findPage(0, 2, Optional.empty());
        MediaAssetPage secondPage = assets.findPage(1, 2, Optional.empty());
        MediaAssetPage readyPage =
                assets.findPage(0, 10, Optional.of(MediaStatus.READY));
        MediaAssetPage beyondLastPage = assets.findPage(99, 10, Optional.empty());

        assertThat(firstPage.totalItems()).isEqualTo(5);
        assertThat(firstPage.items())
                .extracting(MediaAssetRecord::id)
                .containsExactly(archived, failed);
        assertThat(secondPage.totalItems()).isEqualTo(5);
        assertThat(secondPage.items())
                .extracting(MediaAssetRecord::id)
                .containsExactly(tiedReadyHigh, tiedReadyLow);
        assertThat(readyPage.totalItems()).isEqualTo(3);
        assertThat(readyPage.items())
                .extracting(MediaAssetRecord::id)
                .containsExactly(tiedReadyHigh, tiedReadyLow, oldestReady);
        assertThat(beyondLastPage.totalItems()).isEqualTo(5);
        assertThat(beyondLastPage.items()).isEmpty();
    }

    @Test
    void versionAndArchiveCompareAndSetRejectStaleOrIneligibleRows() {
        UUID ready = UUID.randomUUID();
        insertAsset(ready, "ready.jpg");
        assertThat(assets.markReadyIfProcessing(ready)).isOne();

        MediaAssetRecord versioned = assets.incrementVersion(ready, 1).orElseThrow();

        assertThat(versioned.version()).isEqualTo(2);
        assertThat(versioned.status()).isEqualTo(MediaStatus.READY);
        assertThat(assets.incrementVersion(ready, 1)).isEmpty();
        assertThat(assets.archive(ready, 1)).isEmpty();

        MediaAssetRecord archived = assets.archive(ready, 2).orElseThrow();

        assertThat(archived.status()).isEqualTo(MediaStatus.ARCHIVED);
        assertThat(archived.archivedAt()).isNotNull();
        assertThat(archived.version()).isEqualTo(3);
        assertThat(assets.archive(ready, 3)).isEmpty();

        UUID processing = UUID.randomUUID();
        insertAsset(processing, "processing.jpg");
        assertThat(assets.archive(processing, 0)).isEmpty();

        UUID pendingDelete = UUID.randomUUID();
        insertAsset(pendingDelete, "pending-delete.jpg");
        setFixtureState(
                pendingDelete,
                MediaStatus.PENDING_DELETE,
                "2026-01-05T00:00:00Z");
        assertThat(assets.incrementVersion(pendingDelete, 0)).isEmpty();
        assertThat(assets.findById(pendingDelete).orElseThrow().version()).isZero();
    }

    @Test
    void outerRollbackRestoresTheLockedAssetAndItsReplacedTranslations() {
        UUID assetId = UUID.randomUUID();
        insertAsset(assetId, "rollback.jpg");
        assertThat(assets.markReadyIfProcessing(assetId)).isOne();
        List<MediaTranslationRecord> original = List.of(
                translation(assetId, "en", "Original", null, null, null),
                translation(assetId, "zh-CN", "原始文本", null, null, null));
        translations.replaceAll(assetId, original);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
                    MediaAssetRecord locked =
                            assets.findByIdForUpdate(assetId).orElseThrow();
                    assertThat(locked.version()).isOne();
                    translations.replaceAll(assetId, List.of(translation(
                            assetId,
                            "en",
                            "Replacement",
                            "Should roll back",
                            null,
                            null)));
                    assertThat(assets.incrementVersion(assetId, locked.version()))
                            .get()
                            .extracting(MediaAssetRecord::version)
                            .isEqualTo(2L);
                    assertThat(assets.archive(assetId, 2)).isPresent();
                    throw new IllegalStateException("force management rollback");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("force management rollback");

        MediaAssetRecord restored = assets.findById(assetId).orElseThrow();
        assertThat(restored.status()).isEqualTo(MediaStatus.READY);
        assertThat(restored.archivedAt()).isNull();
        assertThat(restored.version()).isOne();
        assertThat(translations.findByAssetId(assetId))
                .containsExactlyElementsOf(original);
    }

    @Test
    void shareLocksAreCompatibleButShareAndUpdateLocksWaitForEachOther()
            throws Exception {
        UUID assetId = UUID.randomUUID();
        insertAsset(assetId, "locks.jpg");
        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch releaseShare = new CountDownLatch(1);
        CountDownLatch releaseUpdate = new CountDownLatch(1);
        try {
            CountDownLatch shareAcquired = new CountDownLatch(1);
            Future<?> shareHolder = holdRowLock(
                    executor, assetId, false, shareAcquired, releaseShare);
            assertThat(shareAcquired.await(10, SECONDS)).isTrue();

            Future<Optional<MediaAssetRecord>> compatibleShare = executor.submit(
                    () -> transactions.execute(status -> assets.findByIdForShare(assetId)));
            assertThat(compatibleShare.get(5, SECONDS)).isPresent();

            CountDownLatch updateAttempted = new CountDownLatch(1);
            Future<Optional<MediaAssetRecord>> waitingUpdate = executor.submit(() -> {
                updateAttempted.countDown();
                return transactions.execute(
                        status -> assets.findByIdForUpdate(assetId));
            });
            assertThat(updateAttempted.await(10, SECONDS)).isTrue();
            assertThatThrownBy(() -> waitingUpdate.get(300, MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            releaseShare.countDown();
            shareHolder.get(10, SECONDS);
            assertThat(waitingUpdate.get(10, SECONDS)).isPresent();

            CountDownLatch updateAcquired = new CountDownLatch(1);
            Future<?> updateHolder = holdRowLock(
                    executor, assetId, true, updateAcquired, releaseUpdate);
            assertThat(updateAcquired.await(10, SECONDS)).isTrue();

            CountDownLatch shareAttempted = new CountDownLatch(1);
            Future<Optional<MediaAssetRecord>> waitingShare = executor.submit(() -> {
                shareAttempted.countDown();
                return transactions.execute(
                        status -> assets.findByIdForShare(assetId));
            });
            assertThat(shareAttempted.await(10, SECONDS)).isTrue();
            assertThatThrownBy(() -> waitingShare.get(300, MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            releaseUpdate.countDown();
            updateHolder.get(10, SECONDS);
            assertThat(waitingShare.get(10, SECONDS)).isPresent();
        } finally {
            releaseShare.countDown();
            releaseUpdate.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, SECONDS)).isTrue();
        }
    }

    private Future<?> holdRowLock(
            ExecutorService executor,
            UUID assetId,
            boolean forUpdate,
            CountDownLatch acquired,
            CountDownLatch release) {
        return executor.submit(() -> transactions.executeWithoutResult(status -> {
            Optional<MediaAssetRecord> record = forUpdate
                    ? assets.findByIdForUpdate(assetId)
                    : assets.findByIdForShare(assetId);
            assertThat(record).isPresent();
            acquired.countDown();
            await(release);
        }));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, SECONDS)) {
                throw new IllegalStateException("test row lock gate timed out");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test row lock gate was interrupted", failure);
        }
    }

    private MediaAssetRecord insertAsset(UUID assetId, String filename) {
        return assets.insertProcessing(new MediaAssetRecord.Insert(
                assetId,
                StorageProvider.LOCAL,
                null,
                null,
                MediaObjectKeys.originalKey(assetId, SHA256, MIME_TYPE),
                filename,
                MIME_TYPE,
                1_024,
                32,
                32,
                SHA256));
    }

    private static MediaTranslationRecord translation(
            UUID assetId,
            String locale,
            String altText,
            String caption,
            String credit,
            String sourceUrl) {
        return new MediaTranslationRecord(
                assetId, locale, altText, caption, credit, sourceUrl);
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }

    private static void setFixtureState(
            UUID assetId, MediaStatus status, String createdAt) {
        OffsetDateTime timestamp = OffsetDateTime.parse(createdAt);
        OffsetDateTime archivedAt = status == MediaStatus.ARCHIVED
                        || status == MediaStatus.PENDING_DELETE
                ? timestamp
                : null;
        migratorJdbc().sql("""
                        update portfolio.media_asset
                        set status=:status,
                            archived_at=:archivedAt,
                            created_at=:createdAt
                        where id=:id
                        """)
                .param("status", status.name(), Types.VARCHAR)
                .param("archivedAt", archivedAt, Types.TIMESTAMP_WITH_TIMEZONE)
                .param("createdAt", timestamp, Types.TIMESTAMP_WITH_TIMEZONE)
                .param("id", assetId, Types.OTHER)
                .update();
    }
}
