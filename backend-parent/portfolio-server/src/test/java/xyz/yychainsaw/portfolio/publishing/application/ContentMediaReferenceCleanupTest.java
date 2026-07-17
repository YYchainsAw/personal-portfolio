package xyz.yychainsaw.portfolio.publishing.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import xyz.yychainsaw.portfolio.content.api.SiteWorkspaceDto;
import xyz.yychainsaw.portfolio.media.application.ArchivedMediaCleanupJobHandler;
import xyz.yychainsaw.portfolio.media.application.MediaCleanupCoordinator;
import xyz.yychainsaw.portfolio.media.application.MediaLifecycleBarrier;
import xyz.yychainsaw.portfolio.media.application.MediaObjectKeys;
import xyz.yychainsaw.portfolio.media.application.MediaReference;
import xyz.yychainsaw.portfolio.media.application.MediaReferenceResolver;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;
import xyz.yychainsaw.portfolio.media.storage.StorageRouter;
import xyz.yychainsaw.portfolio.media.storage.StorageService;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Isolated
class ContentMediaReferenceCleanupTest extends PostgresIntegrationTestBase {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID SITE_ID = SiteWorkspaceDto.SITE_ID;
    private static final String VARIANT_NAME = "w640";
    private static final long CLEANUP_VERSION = 7L;

    @Autowired ContentMediaReferenceChecker references;
    @Autowired MediaCleanupCoordinator cleanup;
    @Autowired MediaLifecycleBarrier lifecycleBarrier;
    @Autowired MediaReferenceResolver referenceResolver;
    @Autowired StorageRouter storageRouter;
    @Autowired Clock clock;
    @Autowired JdbcClient jdbc;

    private final List<String> cleanupObjectKeys = new ArrayList<>();
    private final List<UUID> committedCleanupAssetIds = new ArrayList<>();

    @AfterEach
    void removeCommittedFixturesAndStoredObjects() {
        for (UUID assetId : committedCleanupAssetIds) {
            jdbc.sql("delete from portfolio.media_translation where asset_id=:assetId")
                    .param("assetId", assetId)
                    .update();
            jdbc.sql("delete from portfolio.media_variant where asset_id=:assetId")
                    .param("assetId", assetId)
                    .update();
            jdbc.sql("delete from portfolio.media_asset where id=:assetId")
                    .param("assetId", assetId)
                    .update();
        }
        committedCleanupAssetIds.clear();
        StorageService local = localStorage();
        for (String objectKey : cleanupObjectKeys) {
            local.delete(objectKey);
        }
        cleanupObjectKeys.clear();
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(WorkspaceMediaLocation.class)
    void everyNormalizedWorkspaceLocationReturnsOneRedactedReference(
            WorkspaceMediaLocation location) {
        if (location == WorkspaceMediaLocation.HERO
                || location == WorkspaceMediaLocation.RESUME) {
            prepopulateFixedSiteMediaRows();
        }
        ReferencedAsset state = insertArchivedAssetReferencedFrom(location);

        assertThat(references.findReferences(state.assetId()))
                .containsExactly(state.expectedReference());
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(CleanupReferenceKind.class)
    void workspaceAndEveryRetainedRevisionKindBlockPhysicalDeletion(
            CleanupReferenceKind kind) throws Exception {
        RetainedReferenceState state = insertRetainedReference(kind);
        CleanupAsset asset = state.asset();
        assertThat(references.findReferences(asset.assetId()))
                .containsExactly(state.expectedReference());
        assertThat(asset.objectKeys()).allSatisfy(
                objectKey -> assertThat(localStorage().exists(objectKey)).isTrue());

        runPhysicalCleanup(asset);

        assertThat(mediaAssetState(asset.assetId()))
                .isEqualTo(new MediaAssetState("ARCHIVED", asset.version()));
        assertThat(mediaVariantCount(asset.assetId())).isOne();
        assertThat(mediaTranslationCount(asset.assetId())).isEqualTo(2L);
        assertThat(references.findReferences(asset.assetId()))
                .containsExactly(state.expectedReference());
        assertThat(asset.objectKeys()).allSatisfy(
                objectKey -> assertThat(localStorage().exists(objectKey)).isTrue());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void fullyUnreferencedArchivedAssetIsDeletedFromDatabaseAndLocalStorage()
            throws Exception {
        CleanupAsset asset = insertCommittedStoredArchivedAsset();
        assertThat(references.findReferences(asset.assetId())).isEmpty();
        assertThat(asset.objectKeys()).allSatisfy(
                objectKey -> assertThat(localStorage().exists(objectKey)).isTrue());

        runPhysicalCleanup(asset);

        assertThat(mediaAssetCount(asset.assetId())).isZero();
        assertThat(mediaVariantCount(asset.assetId())).isZero();
        assertThat(mediaTranslationCount(asset.assetId())).isZero();
        assertThat(successfulDeletionAuditCount(asset.assetId())).isOne();
        assertThat(asset.objectKeys()).allSatisfy(
                objectKey -> assertThat(localStorage().exists(objectKey)).isFalse());
    }

    private ReferencedAsset insertArchivedAssetReferencedFrom(WorkspaceMediaLocation location) {
        UUID assetId = insertArchivedAsset();
        return switch (location) {
            case HERO -> {
                insertHeroReference(assetId);
                yield siteReference(assetId);
            }
            case RESUME -> {
                insertResumeReference(assetId);
                yield siteReference(assetId);
            }
            case PROJECT_MEDIA -> {
                UUID projectId = insertProject();
                insertProjectMediaReference(projectId, assetId);
                yield projectReference(assetId, projectId);
            }
            case BLOCK_IMAGE_OR_GALLERY -> {
                UUID projectId = insertProject();
                insertBlockMediaReference(projectId, assetId);
                yield projectReference(assetId, projectId);
            }
            case VIDEO_COVER -> {
                UUID projectId = insertProject();
                insertVideoCoverReference(projectId, assetId);
                yield projectReference(assetId, projectId);
            }
            case BLOCK_DOWNLOAD -> {
                UUID projectId = insertProject();
                insertDownloadReference(projectId, assetId);
                yield projectReference(assetId, projectId);
            }
        };
    }

    private void prepopulateFixedSiteMediaRows() {
        UUID assetId = insertArchivedAsset();
        UUID heroId = jdbc.sql("""
                        select id from portfolio.hero_section where site_id=:siteId
                        """)
                .param("siteId", SITE_ID)
                .query(UUID.class)
                .optional()
                .orElseGet(() -> {
                    UUID id = UUID.randomUUID();
                    jdbc.sql("""
                                    insert into portfolio.hero_section(id, site_id)
                                    values (:id, :siteId)
                                    """)
                            .param("id", id)
                            .param("siteId", SITE_ID)
                            .update();
                    return id;
                });
        jdbc.sql("delete from portfolio.hero_media where hero_id=:heroId")
                .param("heroId", heroId)
                .update();
        jdbc.sql("""
                        insert into portfolio.hero_media(
                            hero_id, media_asset_id, object_position, credit, source_url)
                        values (:heroId, :assetId, '50% 50%', '',
                                'https://example.test/preexisting-hero')
                        """)
                .param("heroId", heroId)
                .param("assetId", assetId)
                .update();
        jdbc.sql("""
                        delete from portfolio.resume_document
                        where site_id=:siteId and locale='en' and is_current
                        """)
                .param("siteId", SITE_ID)
                .update();
        jdbc.sql("""
                        insert into portfolio.resume_document(
                            id, site_id, locale, media_asset_id, version_label,
                            is_current, document_date)
                        values (:id, :siteId, 'en', :assetId, :versionLabel,
                                true, date '2026-07-17')
                        """)
                .param("id", UUID.randomUUID())
                .param("siteId", SITE_ID)
                .param("assetId", assetId)
                .param("versionLabel", "preexisting-" + compact(assetId))
                .update();
    }

    private RetainedReferenceState insertRetainedReference(CleanupReferenceKind kind) {
        CleanupAsset asset = insertStoredArchivedAsset();
        return switch (kind) {
            case WORKSPACE -> {
                UUID projectId = insertProject();
                insertProjectMediaReference(projectId, asset.assetId());
                yield new RetainedReferenceState(
                        asset,
                        new MediaReference("PROJECT_WORKSPACE", projectId));
            }
            case CURRENT_REVISION -> {
                UUID aggregateId = UUID.randomUUID();
                UUID revisionId = insertRevision(
                        aggregateId, 1L, asset, true);
                insertPublication(aggregateId, "PUBLISHED", revisionId);
                yield new RetainedReferenceState(
                        asset,
                        new MediaReference("CONTENT_REVISION", revisionId));
            }
            case OLD_RETAINED_REVISION -> {
                UUID aggregateId = UUID.randomUUID();
                UUID retainedRevisionId = insertRevision(
                        aggregateId, 1L, asset, true);
                UUID currentRevisionId = insertRevision(
                        aggregateId, 2L, asset, false);
                insertPublication(aggregateId, "PUBLISHED", currentRevisionId);
                assertThat(currentPublicationRevision(aggregateId))
                        .isEqualTo(currentRevisionId)
                        .isNotEqualTo(retainedRevisionId);
                yield new RetainedReferenceState(
                        asset,
                        new MediaReference("CONTENT_REVISION", retainedRevisionId));
            }
            case ARCHIVED_REVISION -> {
                UUID aggregateId = UUID.randomUUID();
                UUID revisionId = insertRevision(
                        aggregateId, 1L, asset, true);
                insertPublication(aggregateId, "ARCHIVED", revisionId);
                assertThat(publicationStatus(aggregateId)).isEqualTo("ARCHIVED");
                yield new RetainedReferenceState(
                        asset,
                        new MediaReference("CONTENT_REVISION", revisionId));
            }
        };
    }

    private CleanupAsset insertStoredArchivedAsset() {
        return insertStoredArchivedAsset(false);
    }

    private CleanupAsset insertCommittedStoredArchivedAsset() {
        return insertStoredArchivedAsset(true);
    }

    private CleanupAsset insertStoredArchivedAsset(boolean committedFixture) {
        UUID assetId = UUID.randomUUID();
        if (committedFixture) {
            committedCleanupAssetIds.add(assetId);
        }
        String sha256 = assetId.toString().replace("-", "").repeat(2);
        String originalKey = MediaObjectKeys.originalKey(assetId, sha256, "image/png");
        String variantKey = MediaObjectKeys.variantKey(
                assetId, VARIANT_NAME, sha256, "image/png");
        putObject(originalKey, "o");
        putObject(variantKey, "v");
        Instant cutoff = clock.instant()
                .minus(Duration.ofDays(31))
                .truncatedTo(ChronoUnit.SECONDS);
        Instant archivedAt = cutoff.minus(Duration.ofDays(1));
        jdbc.sql("""
                        insert into portfolio.media_asset(
                            id, provider, object_key, original_filename, mime_type,
                            byte_size, width, height, sha256, status, archived_at, version)
                        values (:id, 'LOCAL', :objectKey, 'cleanup.png', 'image/png',
                                1, 1, 1, :sha256, 'ARCHIVED', :archivedAt, :version)
                        """)
                .param("id", assetId)
                .param("objectKey", originalKey)
                .param("sha256", sha256)
                .param("archivedAt", Timestamp.from(archivedAt))
                .param("version", CLEANUP_VERSION)
                .update();
        jdbc.sql("""
                        insert into portfolio.media_variant(
                            id, asset_id, variant_name, format, object_key, mime_type,
                            byte_size, width, height, sha256, status)
                        values (:id, :assetId, :variantName, 'PNG', :objectKey,
                                'image/png', 1, 640, 360, :sha256, 'READY')
                        """)
                .param("id", UUID.randomUUID())
                .param("assetId", assetId)
                .param("variantName", VARIANT_NAME)
                .param("objectKey", variantKey)
                .param("sha256", sha256)
                .update();
        for (String locale : List.of("zh-CN", "en")) {
            jdbc.sql("""
                            insert into portfolio.media_translation(
                                asset_id, locale, alt_text, caption, credit, source_url)
                            values (:assetId, :locale, 'alt', 'caption', 'credit',
                                    'https://example.test/cleanup-source')
                            """)
                    .param("assetId", assetId)
                    .param("locale", locale)
                    .update();
        }
        return new CleanupAsset(
                assetId,
                CLEANUP_VERSION,
                cutoff,
                List.of(originalKey, variantKey));
    }

    private void putObject(String objectKey, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        localStorage().put(
                objectKey,
                new ByteArrayInputStream(bytes),
                bytes.length,
                "image/png");
        cleanupObjectKeys.add(objectKey);
    }

    private UUID insertRevision(
            UUID aggregateId,
            long version,
            CleanupAsset asset,
            boolean referenced) {
        UUID revisionId = UUID.randomUUID();
        jdbc.sql("""
                        insert into portfolio.content_revision(
                            id, aggregate_type, aggregate_id, version,
                            snapshot_schema_version, snapshot, checksum, published_by)
                        values (:id, 'PROJECT', :aggregateId, :version,
                                1, '{}'::jsonb, :checksum, :adminId)
                        """)
                .param("id", revisionId)
                .param("aggregateId", aggregateId)
                .param("version", version)
                .param("checksum", "e".repeat(64))
                .param("adminId", ensureAdmin())
                .update();
        if (referenced) {
            jdbc.sql("""
                            insert into portfolio.revision_media_reference(
                                revision_id, asset_id, variant_name, usage)
                            values (:revisionId, :assetId, :variantName, 'DETAIL')
                            """)
                    .param("revisionId", revisionId)
                    .param("assetId", asset.assetId())
                    .param("variantName", VARIANT_NAME)
                    .update();
        }
        return revisionId;
    }

    private void insertPublication(
            UUID aggregateId,
            String status,
            UUID currentRevisionId) {
        jdbc.sql("""
                        insert into portfolio.publication(
                            aggregate_type, aggregate_id, status, current_revision_id,
                            current_slug, version, published_at)
                        values ('PROJECT', :aggregateId, :status, :revisionId,
                                null, 2, clock_timestamp())
                        """)
                .param("aggregateId", aggregateId)
                .param("status", status)
                .param("revisionId", currentRevisionId)
                .update();
    }

    private UUID ensureAdmin() {
        return jdbc.sql("select id from portfolio.admin_user order by created_at limit 1")
                .query(UUID.class)
                .optional()
                .orElseGet(() -> {
                    UUID adminId = UUID.randomUUID();
                    jdbc.sql("""
                                    insert into portfolio.admin_user(
                                        id, username, password_hash, status,
                                        totp_key_version, totp_nonce, totp_ciphertext)
                                    values (:id, :username, '{noop}test', 'ACTIVE', 1,
                                            decode(repeat('00', 12), 'hex'),
                                            decode('00', 'hex'))
                                    """)
                            .param("id", adminId)
                            .param("username", "cleanup-" + compact(adminId))
                            .update();
                    return adminId;
                });
    }

    private void runPhysicalCleanup(CleanupAsset asset) throws Exception {
        cleanupHandler().handle(JSON.valueToTree(Map.of(
                "assetId", asset.assetId().toString(),
                "version", asset.version(),
                "cutoffEpochSecond", asset.cutoff().getEpochSecond())));
    }

    private ArchivedMediaCleanupJobHandler cleanupHandler() {
        return new ArchivedMediaCleanupJobHandler(
                cleanup,
                lifecycleBarrier,
                storageRouter,
                clock,
                referenceResolver);
    }

    private StorageService localStorage() {
        return storageRouter.require(StorageProvider.LOCAL);
    }

    private UUID currentPublicationRevision(UUID aggregateId) {
        return jdbc.sql("""
                        select current_revision_id
                        from portfolio.publication
                        where aggregate_type='PROJECT' and aggregate_id=:aggregateId
                        """)
                .param("aggregateId", aggregateId)
                .query(UUID.class)
                .single();
    }

    private String publicationStatus(UUID aggregateId) {
        return jdbc.sql("""
                        select status
                        from portfolio.publication
                        where aggregate_type='PROJECT' and aggregate_id=:aggregateId
                        """)
                .param("aggregateId", aggregateId)
                .query(String.class)
                .single();
    }

    private MediaAssetState mediaAssetState(UUID assetId) {
        return jdbc.sql("""
                        select status, version
                        from portfolio.media_asset
                        where id=:assetId
                        """)
                .param("assetId", assetId)
                .query((rs, rowNum) -> new MediaAssetState(
                        rs.getString("status"), rs.getLong("version")))
                .single();
    }

    private long mediaAssetCount(UUID assetId) {
        return rowCount("media_asset", assetId);
    }

    private long mediaVariantCount(UUID assetId) {
        return rowCount("media_variant", assetId);
    }

    private long mediaTranslationCount(UUID assetId) {
        return rowCount("media_translation", assetId);
    }

    private long successfulDeletionAuditCount(UUID assetId) {
        return jdbc.sql("""
                        select count(*)
                        from portfolio.audit_log
                        where action='MEDIA_PHYSICAL_DELETE'
                          and target_type='MEDIA_ASSET'
                          and target_id=:targetId
                          and outcome='SUCCESS'
                        """)
                .param("targetId", assetId.toString())
                .query(Long.class)
                .single();
    }

    private long rowCount(String table, UUID assetId) {
        String sql = switch (table) {
            case "media_asset" -> "select count(*) from portfolio.media_asset where id=:assetId";
            case "media_variant" ->
                    "select count(*) from portfolio.media_variant where asset_id=:assetId";
            case "media_translation" ->
                    "select count(*) from portfolio.media_translation where asset_id=:assetId";
            default -> throw new IllegalArgumentException("unsupported table");
        };
        return jdbc.sql(sql)
                .param("assetId", assetId)
                .query(Long.class)
                .single();
    }

    private UUID insertArchivedAsset() {
        UUID assetId = UUID.randomUUID();
        jdbc.sql("""
                        insert into portfolio.media_asset(
                            id, provider, object_key, original_filename, mime_type,
                            byte_size, width, height, sha256, status, archived_at)
                        values (:id, 'LOCAL', :objectKey, 'referenced.png', 'image/png',
                                1, 1, 1, :sha256, 'ARCHIVED',
                                timestamptz '2026-06-01 00:00:00+00')
                        """)
                .param("id", assetId)
                .param("objectKey", "reference-cleanup/" + assetId + ".png")
                .param("sha256", assetId.toString().replace("-", "").repeat(2))
                .update();
        return assetId;
    }

    private void insertHeroReference(UUID assetId) {
        UUID heroId = jdbc.sql("""
                        select id from portfolio.hero_section where site_id=:siteId
                        """)
                .param("siteId", SITE_ID)
                .query(UUID.class)
                .optional()
                .orElseGet(() -> {
                    UUID id = UUID.randomUUID();
                    jdbc.sql("""
                                    insert into portfolio.hero_section(id, site_id)
                                    values (:id, :siteId)
                                    """)
                            .param("id", id)
                            .param("siteId", SITE_ID)
                            .update();
                    return id;
                });
        jdbc.sql("delete from portfolio.hero_media where hero_id=:heroId")
                .param("heroId", heroId)
                .update();
        jdbc.sql("""
                        insert into portfolio.hero_media(
                            hero_id, media_asset_id, object_position, credit, source_url)
                        values (:heroId, :assetId, '50% 50%', '',
                                'https://example.test/reference-hero')
                        """)
                .param("heroId", heroId)
                .param("assetId", assetId)
                .update();
    }

    private void insertResumeReference(UUID assetId) {
        UUID resumeId = UUID.randomUUID();
        jdbc.sql("""
                        insert into portfolio.resume_document(
                            id, site_id, locale, media_asset_id, version_label,
                            is_current, document_date)
                        values (:id, :siteId, 'en', :assetId, :versionLabel,
                                false, date '2026-07-17')
                        """)
                .param("id", resumeId)
                .param("siteId", SITE_ID)
                .param("assetId", assetId)
                .param("versionLabel", "reference-" + compact(resumeId))
                .update();
    }

    private UUID insertProject() {
        UUID projectId = UUID.randomUUID();
        jdbc.sql("""
                        insert into portfolio.project(
                            id, external_key, slug, number_label, sort_order)
                        values (:id, :externalKey, :slug, '01', :sortOrder)
                        """)
                .param("id", projectId)
                .param("externalKey", "reference-cleanup-" + projectId)
                .param("slug", "reference-" + compact(projectId))
                .param("sortOrder", nextProjectSortOrder())
                .update();
        return projectId;
    }

    private void insertProjectMediaReference(UUID projectId, UUID assetId) {
        jdbc.sql("""
                        insert into portfolio.project_media(
                            project_id, media_asset_id, usage, sort_order, layout,
                            object_position, credit, source_url)
                        values (:projectId, :assetId, 'COVER', 0, 'wide',
                                '50% 50%', '', 'https://example.test/reference-project')
                        """)
                .param("projectId", projectId)
                .param("assetId", assetId)
                .update();
    }

    private void insertBlockMediaReference(UUID projectId, UUID assetId) {
        UUID blockId = insertBlock(projectId, "GALLERY");
        jdbc.sql("""
                        insert into portfolio.content_block_media(
                            block_id, media_asset_id, role, sort_order)
                        values (:blockId, :assetId, 'GALLERY', 0)
                        """)
                .param("blockId", blockId)
                .param("assetId", assetId)
                .update();
    }

    private void insertVideoCoverReference(UUID projectId, UUID assetId) {
        UUID blockId = insertBlock(projectId, "VIDEO");
        jdbc.sql("""
                        insert into portfolio.content_block_video(
                            block_id, provider, url, cover_asset_id)
                        values (:blockId, 'YOUTUBE',
                                'https://example.test/reference-video', :assetId)
                        """)
                .param("blockId", blockId)
                .param("assetId", assetId)
                .update();
    }

    private void insertDownloadReference(UUID projectId, UUID assetId) {
        UUID blockId = insertBlock(projectId, "DOWNLOAD");
        jdbc.sql("""
                        insert into portfolio.content_block_action(
                            block_id, action_type, target_type, media_asset_id,
                            open_new_tab)
                        values (:blockId, 'DOWNLOAD', 'MEDIA', :assetId, true)
                        """)
                .param("blockId", blockId)
                .param("assetId", assetId)
                .update();
    }

    private UUID insertBlock(UUID projectId, String blockType) {
        UUID blockId = UUID.randomUUID();
        jdbc.sql("""
                        insert into portfolio.project_content_block(
                            id, project_id, block_type, sort_order)
                        values (:id, :projectId, :blockType, 0)
                        """)
                .param("id", blockId)
                .param("projectId", projectId)
                .param("blockType", blockType)
                .update();
        return blockId;
    }

    private int nextProjectSortOrder() {
        return jdbc.sql("select coalesce(max(sort_order), -1) + 1 from portfolio.project")
                .query(Integer.class)
                .single();
    }

    private static ReferencedAsset siteReference(UUID assetId) {
        return new ReferencedAsset(
                assetId, new MediaReference("SITE_WORKSPACE", SITE_ID));
    }

    private static ReferencedAsset projectReference(UUID assetId, UUID projectId) {
        return new ReferencedAsset(
                assetId, new MediaReference("PROJECT_WORKSPACE", projectId));
    }

    private static String compact(UUID id) {
        return id.toString().replace("-", "").substring(0, 12);
    }

    enum WorkspaceMediaLocation {
        HERO,
        RESUME,
        PROJECT_MEDIA,
        BLOCK_IMAGE_OR_GALLERY,
        VIDEO_COVER,
        BLOCK_DOWNLOAD
    }

    enum CleanupReferenceKind {
        WORKSPACE,
        CURRENT_REVISION,
        OLD_RETAINED_REVISION,
        ARCHIVED_REVISION
    }

    private record ReferencedAsset(UUID assetId, MediaReference expectedReference) {}

    private record CleanupAsset(
            UUID assetId,
            long version,
            Instant cutoff,
            List<String> objectKeys) {}

    private record RetainedReferenceState(
            CleanupAsset asset,
            MediaReference expectedReference) {}

    private record MediaAssetState(String status, long version) {}
}
