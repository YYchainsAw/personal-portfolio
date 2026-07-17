package xyz.yychainsaw.portfolio.content.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.time.ZoneOffset;
import java.sql.Types;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.yychainsaw.portfolio.audit.AuditService;
import xyz.yychainsaw.portfolio.content.api.SiteWorkspaceDto;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.media.application.MediaCleanupCoordinator;
import xyz.yychainsaw.portfolio.media.application.MediaManagementService;
import xyz.yychainsaw.portfolio.media.application.MediaObjectKeys;
import xyz.yychainsaw.portfolio.media.application.MediaQueryService;
import xyz.yychainsaw.portfolio.media.application.MediaReference;
import xyz.yychainsaw.portfolio.media.application.MediaReferenceResolver;
import xyz.yychainsaw.portfolio.media.domain.MediaStatus;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRepository;
import xyz.yychainsaw.portfolio.media.persistence.MediaTranslationRepository;
import xyz.yychainsaw.portfolio.media.persistence.MediaVariantRepository;
import xyz.yychainsaw.portfolio.publishing.application.ContentMediaReferenceChecker;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
class ContentMediaReferenceConcurrencyTest extends PostgresIntegrationTestBase {
    private static final UUID ASSET_ID =
            UUID.fromString("70000000-0000-4000-8000-000000000001");
    private static final UUID PROJECT_ID =
            UUID.fromString("70000000-0000-4000-8000-000000000002");
    private static final UUID HERO_ID =
            UUID.fromString("70000000-0000-4000-8000-000000000003");
    private static final UUID BLOCK_ID =
            UUID.fromString("70000000-0000-4000-8000-000000000004");
    private static final UUID RESUME_ID =
            UUID.fromString("70000000-0000-4000-8000-000000000005");
    private static final UUID UNREFERENCED_ASSET_ID =
            UUID.fromString("70000000-0000-4000-8000-000000000006");
    private static final UUID ACTOR_ID =
            UUID.fromString("70000000-0000-4000-8000-000000000007");

    @Autowired ContentMediaReferenceChecker checker;
    @Autowired MediaQueryService mediaQueries;
    @Autowired MediaAssetRepository mediaAssets;
    @Autowired MediaVariantRepository mediaVariants;
    @Autowired MediaTranslationRepository mediaTranslations;
    @Autowired MediaReferenceResolver referenceResolver;
    @Autowired MediaCleanupCoordinator cleanup;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcClient jdbc;

    private final JdbcClient owner = migratorJdbc();

    @AfterEach
    void clean() {
        owner.sql("delete from portfolio.content_block_action where block_id=:id")
                .param("id", BLOCK_ID).update();
        owner.sql("delete from portfolio.content_block_video where block_id=:id")
                .param("id", BLOCK_ID).update();
        owner.sql("delete from portfolio.content_block_media where block_id=:id")
                .param("id", BLOCK_ID).update();
        owner.sql("delete from portfolio.project_content_block where id=:id")
                .param("id", BLOCK_ID).update();
        owner.sql("delete from portfolio.project_media where project_id=:id")
                .param("id", PROJECT_ID).update();
        owner.sql("delete from portfolio.project where id=:id")
                .param("id", PROJECT_ID).update();
        owner.sql("delete from portfolio.hero_media where hero_id=:id")
                .param("id", HERO_ID).update();
        owner.sql("delete from portfolio.hero_section where id=:id")
                .param("id", HERO_ID).update();
        owner.sql("delete from portfolio.resume_document where id=:id")
                .param("id", RESUME_ID).update();
        owner.sql("delete from portfolio.background_job where idempotency_key=:key")
                .param("key", "media-delete:" + UNREFERENCED_ASSET_ID + ":0")
                .update();
        owner.sql("delete from portfolio.media_asset where id=:id")
                .param("id", ASSET_ID).update();
        owner.sql("delete from portfolio.media_asset where id=:id")
                .param("id", UNREFERENCED_ASSET_ID).update();
    }

    @ParameterizedTest
    @EnumSource(ReferencePath.class)
    void resolvesEveryV7ReferencePath(ReferencePath path) {
        insertReadyAsset();
        path.insert(owner);

        List<MediaReference> references = checker.findReferences(ASSET_ID);

        assertThat(references).containsExactly(path.expected());
    }

    @Test
    void returnsAnEmptyListForAnUnreferencedAsset() {
        insertReadyAsset();

        assertThat(checker.findReferences(ASSET_ID)).isEmpty();
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(ReferencePath.class)
    void writerWinsArchiveWaitsThenFailsAndLeavesAssetReady(ReferencePath path)
            throws Exception {
        insertReadyAsset();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        MediaManagementService management = mediaManagement();
        CountDownLatch referenceWritten = new CountDownLatch(1);
        CountDownLatch allowWriterCommit = new CountDownLatch(1);
        CountDownLatch archiveAttempted = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> writer = executor.submit(() -> transaction.execute(status -> {
                mediaQueries.requireReadyAsset(ASSET_ID);
                path.insert(jdbc);
                referenceWritten.countDown();
                await(allowWriterCommit, "writer commit gate");
                return null;
            }));
            assertThat(referenceWritten.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> archive = executor.submit(() -> {
                archiveAttempted.countDown();
                management.archive(ASSET_ID);
                return null;
            });
            assertThat(archiveAttempted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> archive.get(250, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            allowWriterCommit.countDown();
            writer.get(5, TimeUnit.SECONDS);
            ExecutionException archiveFailure = assertThrows(
                    ExecutionException.class,
                    () -> archive.get(5, TimeUnit.SECONDS));
            assertThat(archiveFailure.getCause())
                    .isInstanceOf(DomainException.class);
            assertThat(((DomainException) archiveFailure.getCause()).code())
                    .isEqualTo("MEDIA_STILL_REFERENCED");
        } finally {
            allowWriterCommit.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(mediaAssets.findById(ASSET_ID).orElseThrow().status())
                .isEqualTo(MediaStatus.READY);
        assertThat(checker.findReferences(ASSET_ID)).containsExactly(path.expected());
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(ReferencePath.class)
    void lifecycleWinsContentWaitsThenRejectsArchivedAssetWithoutReference(
            ReferencePath path)
            throws Exception {
        insertReadyAsset();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch lifecycleLocked = new CountDownLatch(1);
        CountDownLatch allowLifecycleCommit = new CountDownLatch(1);
        CountDownLatch writerAttempted = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> lifecycle = executor.submit(() -> transaction.execute(status -> {
                var locked = mediaAssets.findByIdForUpdate(ASSET_ID).orElseThrow();
                lifecycleLocked.countDown();
                await(allowLifecycleCommit, "lifecycle commit gate");
                assertThat(mediaAssets.archive(ASSET_ID, locked.version())).isPresent();
                return null;
            }));
            assertThat(lifecycleLocked.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> writer = executor.submit(() -> transaction.execute(status -> {
                writerAttempted.countDown();
                mediaQueries.requireReadyAsset(ASSET_ID);
                path.insert(jdbc);
                return null;
            }));
            assertThat(writerAttempted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> writer.get(250, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            allowLifecycleCommit.countDown();
            lifecycle.get(5, TimeUnit.SECONDS);
            ExecutionException writerFailure = assertThrows(
                    ExecutionException.class,
                    () -> writer.get(5, TimeUnit.SECONDS));
            assertThat(writerFailure.getCause()).isInstanceOf(DomainException.class);
            assertThat(((DomainException) writerFailure.getCause()).code())
                    .isEqualTo("MEDIA_NOT_READY");
        } finally {
            allowLifecycleCommit.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(checker.findReferences(ASSET_ID)).isEmpty();
        assertThat(mediaAssets.findById(ASSET_ID).orElseThrow().status())
                .isEqualTo(MediaStatus.ARCHIVED);
        assertThat(owner.sql("""
                        select count(*) from portfolio.audit_log
                        where action='CONTENT_WORKSPACE_UPDATED'
                          and target_id=:target
                        """)
                .param("target", path.expected().referenceId().toString())
                .query(Long.class)
                .single()).isZero();
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(ReferencePath.class)
    void lowerLevelArchivedReferenceStillBlocksCleanupWhileControlStages(
            ReferencePath path) {
        // Application writes cannot create this state: the reference is inserted
        // directly only to prove the cleanup checker's defense-in-depth behavior.
        insertAsset(ASSET_ID, "ARCHIVED", Instant.parse("2026-06-01T00:00:00Z"));
        insertAsset(
                UNREFERENCED_ASSET_ID,
                "ARCHIVED",
                Instant.parse("2026-06-01T00:00:00Z"));
        path.insert(owner);
        Instant cutoff = Instant.parse("2026-07-17T00:00:00Z");

        assertThat(checker.findReferences(ASSET_ID)).containsExactly(path.expected());
        assertThat(cleanup.stageForDeletion(ASSET_ID, cutoff)).isFalse();
        assertThat(cleanup.stageForDeletion(UNREFERENCED_ASSET_ID, cutoff)).isTrue();

        assertThat(mediaAssets.findById(ASSET_ID).orElseThrow().status())
                .isEqualTo(MediaStatus.ARCHIVED);
        assertThat(mediaAssets.findById(UNREFERENCED_ASSET_ID).orElseThrow().status())
                .isEqualTo(MediaStatus.PENDING_DELETE);
    }

    private void insertReadyAsset() {
        insertAsset(ASSET_ID, "READY", null);
    }

    private void insertAsset(UUID id, String status, Instant archivedAt) {
        String sha = id.equals(ASSET_ID) ? "7".repeat(64) : "8".repeat(64);
        owner.sql("""
                        insert into portfolio.media_asset(
                            id, provider, object_key, original_filename, mime_type,
                            byte_size, width, height, sha256, status, archived_at
                        ) values (
                            :id, 'LOCAL', :key, 'reference.png', 'image/png',
                            1, 1, 1, :sha, :status, :archivedAt
                        )
                        """)
                .param("id", id)
                .param("key", MediaObjectKeys.originalKey(id, sha, "image/png"))
                .param("sha", sha)
                .param("status", status)
                .param(
                        "archivedAt",
                        archivedAt == null ? null : archivedAt.atOffset(ZoneOffset.UTC),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    private MediaManagementService mediaManagement() {
        return new MediaManagementService(
                () -> ACTOR_ID,
                mediaAssets,
                mediaVariants,
                mediaTranslations,
                referenceResolver,
                List.of(),
                mock(AuditService.class),
                transactionManager);
    }

    private static void await(CountDownLatch latch, String label) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError(label + " timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(label + " interrupted", interrupted);
        }
    }

    private enum ReferencePath {
        HERO {
            @Override
            void insert(JdbcClient jdbc) {
                jdbc.sql("insert into portfolio.hero_section(id, site_id) values (:id, :site)")
                        .param("id", HERO_ID).param("site", SiteWorkspaceDto.SITE_ID).update();
                jdbc.sql("""
                                insert into portfolio.hero_media(
                                    hero_id, media_asset_id, object_position, credit, source_url
                                ) values (:hero, :asset, '50% 50%', '', 'https://example.test/hero')
                                """)
                        .param("hero", HERO_ID).param("asset", ASSET_ID).update();
            }

            @Override
            MediaReference expected() {
                return new MediaReference("SITE_WORKSPACE", SiteWorkspaceDto.SITE_ID);
            }
        },
        RESUME {
            @Override
            void insert(JdbcClient jdbc) {
                jdbc.sql("""
                                insert into portfolio.resume_document(
                                    id, site_id, locale, media_asset_id, version_label,
                                    is_current, document_date
                                ) values (:id, :site, 'en', :asset, 'v1', true, date '2026-07-17')
                                """)
                        .param("id", RESUME_ID).param("site", SiteWorkspaceDto.SITE_ID)
                        .param("asset", ASSET_ID).update();
            }

            @Override
            MediaReference expected() {
                return new MediaReference("SITE_WORKSPACE", SiteWorkspaceDto.SITE_ID);
            }
        },
        PROJECT_MEDIA {
            @Override
            void insert(JdbcClient jdbc) {
                insertProject(jdbc);
                jdbc.sql("""
                                insert into portfolio.project_media(
                                    project_id, media_asset_id, usage, sort_order, layout,
                                    object_position, credit, source_url
                                ) values (:project, :asset, 'COVER', 0, 'wide',
                                          '50% 50%', '', 'https://example.test/project')
                                """)
                        .param("project", PROJECT_ID).param("asset", ASSET_ID).update();
            }
        },
        BLOCK_MEDIA {
            @Override
            void insert(JdbcClient jdbc) {
                insertBlock(jdbc, "IMAGE");
                jdbc.sql("""
                                insert into portfolio.content_block_media(
                                    block_id, media_asset_id, role, sort_order
                                ) values (:block, :asset, 'PRIMARY', 0)
                                """)
                        .param("block", BLOCK_ID).param("asset", ASSET_ID).update();
            }
        },
        VIDEO_COVER {
            @Override
            void insert(JdbcClient jdbc) {
                insertBlock(jdbc, "VIDEO");
                jdbc.sql("""
                                insert into portfolio.content_block_video(
                                    block_id, provider, url, cover_asset_id
                                ) values (:block, 'YOUTUBE', 'https://example.test/video', :asset)
                                """)
                        .param("block", BLOCK_ID).param("asset", ASSET_ID).update();
            }
        },
        ACTION_MEDIA {
            @Override
            void insert(JdbcClient jdbc) {
                insertBlock(jdbc, "DOWNLOAD");
                jdbc.sql("""
                                insert into portfolio.content_block_action(
                                    block_id, action_type, target_type, media_asset_id,
                                    open_new_tab
                                ) values (:block, 'DOWNLOAD', 'MEDIA', :asset, true)
                                """)
                        .param("block", BLOCK_ID).param("asset", ASSET_ID).update();
            }
        };

        abstract void insert(JdbcClient jdbc);

        MediaReference expected() {
            return new MediaReference("PROJECT_WORKSPACE", PROJECT_ID);
        }

        static void insertProject(JdbcClient jdbc) {
            jdbc.sql("""
                            insert into portfolio.project(
                                id, external_key, slug, number_label, sort_order
                            ) values (:id, :key, :slug, '01', 700)
                            """)
                    .param("id", PROJECT_ID)
                    .param("key", "reference-" + PROJECT_ID)
                    .param("slug", "reference-" + PROJECT_ID.toString().substring(0, 8))
                    .update();
        }

        static void insertBlock(JdbcClient jdbc, String type) {
            insertProject(jdbc);
            jdbc.sql("""
                            insert into portfolio.project_content_block(
                                id, project_id, block_type, sort_order
                            ) values (:id, :project, :type, 0)
                            """)
                    .param("id", BLOCK_ID).param("project", PROJECT_ID)
                    .param("type", type).update();
        }
    }
}
