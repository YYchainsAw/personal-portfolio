package xyz.yychainsaw.portfolio.publishing.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import xyz.yychainsaw.portfolio.content.api.SiteWorkspaceDto;
import xyz.yychainsaw.portfolio.media.application.MediaChangeType;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Isolated
class ContentMediaChangeListenerTest extends PostgresIntegrationTestBase {
    private static final UUID SITE_ID = SiteWorkspaceDto.SITE_ID;

    @Autowired ContentMediaChangeListener listener;
    @Autowired JdbcClient jdbc;

    @Test
    void sharedAssetBumpsSiteAndEachProjectOnceWithoutMovingPublicationPointers() {
        prepopulateFixedSiteMediaRows();
        SharedMediaState shared = insertSharedMediaState();
        AffectedState before = affectedState(shared.projectIds());

        listener.onMediaChanged(shared.assetId(), MediaChangeType.TRANSLATION_UPDATED);

        AffectedState after = affectedState(shared.projectIds());
        assertThat(after.site().version()).isEqualTo(before.site().version() + 1);
        assertThat(after.projects()).allSatisfy((projectId, project) -> {
            assertThat(project.version())
                    .isEqualTo(before.projects().get(projectId).version() + 1);
            assertThat(project.publicationDirty()).isTrue();
        });
        assertThat(after.projects().values())
                .extracting(ProjectWorkspaceState::updatedAt)
                .containsOnly(after.site().updatedAt());
        assertThat(after.publications()).isEqualTo(before.publications());
    }

    @Test
    void unreferencedMetadataChangeLeavesEveryWorkspaceAndPublicationUnchanged() {
        UUID unreferencedAssetId = insertReadyAsset();
        DatabaseState before = databaseState();

        listener.onMediaChanged(unreferencedAssetId, MediaChangeType.METADATA_UPDATED);

        assertThat(databaseState()).isEqualTo(before);
    }

    private SharedMediaState insertSharedMediaState() {
        UUID assetId = insertReadyAsset();
        insertSiteReferences(assetId);
        UUID firstProjectId = insertProjectWithEveryReference(assetId, 3L);
        UUID secondProjectId = insertProjectWithEveryReference(assetId, 8L);
        UUID adminId = ensureAdmin();
        publish("SITE", SITE_ID, null, adminId);
        publish("PROJECT", firstProjectId, slug(firstProjectId), adminId);
        publish("PROJECT", secondProjectId, slug(secondProjectId), adminId);
        return new SharedMediaState(assetId, List.of(firstProjectId, secondProjectId));
    }

    private void prepopulateFixedSiteMediaRows() {
        UUID assetId = insertReadyAsset();
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
                                'https://example.test/preexisting-listener-hero')
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

    private UUID insertReadyAsset() {
        UUID assetId = UUID.randomUUID();
        jdbc.sql("""
                        insert into portfolio.media_asset(
                            id, provider, object_key, original_filename, mime_type,
                            byte_size, width, height, sha256, status)
                        values (:id, 'LOCAL', :objectKey, 'listener.png', 'image/png',
                                1, 1, 1, :sha256, 'READY')
                        """)
                .param("id", assetId)
                .param("objectKey", "listener/" + assetId + ".png")
                .param("sha256", assetId.toString().replace("-", "").repeat(2))
                .update();
        return assetId;
    }

    private void insertSiteReferences(UUID assetId) {
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
                                'https://example.test/listener-hero')
                        """)
                .param("heroId", heroId)
                .param("assetId", assetId)
                .update();
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
                .param("versionLabel", "listener-" + compact(resumeId))
                .update();
    }

    private UUID insertProjectWithEveryReference(UUID assetId, long version) {
        UUID projectId = UUID.randomUUID();
        jdbc.sql("""
                        insert into portfolio.project(
                            id, external_key, slug, number_label, sort_order,
                            publication_dirty, version)
                        values (:id, :externalKey, :slug, '01', :sortOrder, false, :version)
                        """)
                .param("id", projectId)
                .param("externalKey", "listener-" + projectId)
                .param("slug", slug(projectId))
                .param("sortOrder", nextProjectSortOrder())
                .param("version", version)
                .update();
        jdbc.sql("""
                        insert into portfolio.project_media(
                            project_id, media_asset_id, usage, sort_order, layout,
                            object_position, credit, source_url)
                        values (:projectId, :assetId, 'COVER', 0, 'wide',
                                '50% 50%', '', 'https://example.test/listener-project')
                        """)
                .param("projectId", projectId)
                .param("assetId", assetId)
                .update();

        UUID imageBlockId = insertBlock(projectId, "IMAGE", 0);
        jdbc.sql("""
                        insert into portfolio.content_block_media(
                            block_id, media_asset_id, role, sort_order)
                        values (:blockId, :assetId, 'PRIMARY', 0)
                        """)
                .param("blockId", imageBlockId)
                .param("assetId", assetId)
                .update();

        UUID videoBlockId = insertBlock(projectId, "VIDEO", 1);
        jdbc.sql("""
                        insert into portfolio.content_block_video(
                            block_id, provider, url, cover_asset_id)
                        values (:blockId, 'YOUTUBE',
                                'https://example.test/listener-video', :assetId)
                        """)
                .param("blockId", videoBlockId)
                .param("assetId", assetId)
                .update();

        UUID downloadBlockId = insertBlock(projectId, "DOWNLOAD", 2);
        jdbc.sql("""
                        insert into portfolio.content_block_action(
                            block_id, action_type, target_type, media_asset_id,
                            open_new_tab)
                        values (:blockId, 'DOWNLOAD', 'MEDIA', :assetId, true)
                        """)
                .param("blockId", downloadBlockId)
                .param("assetId", assetId)
                .update();
        return projectId;
    }

    private UUID insertBlock(UUID projectId, String type, int sortOrder) {
        UUID blockId = UUID.randomUUID();
        jdbc.sql("""
                        insert into portfolio.project_content_block(
                            id, project_id, block_type, sort_order)
                        values (:id, :projectId, :type, :sortOrder)
                        """)
                .param("id", blockId)
                .param("projectId", projectId)
                .param("type", type)
                .param("sortOrder", sortOrder)
                .update();
        return blockId;
    }

    private int nextProjectSortOrder() {
        return jdbc.sql("select coalesce(max(sort_order), -1) + 1 from portfolio.project")
                .query(Integer.class)
                .single();
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
                            .param("username", "listener-" + compact(adminId))
                            .update();
                    return adminId;
                });
    }

    private void publish(String aggregateType, UUID aggregateId, String slug, UUID adminId) {
        long revisionVersion = jdbc.sql("""
                        select coalesce(max(version), 0) + 1
                        from portfolio.content_revision
                        where aggregate_type=:aggregateType and aggregate_id=:aggregateId
                        """)
                .param("aggregateType", aggregateType)
                .param("aggregateId", aggregateId)
                .query(Long.class)
                .single();
        UUID revisionId = UUID.randomUUID();
        jdbc.sql("""
                        insert into portfolio.content_revision(
                            id, aggregate_type, aggregate_id, version,
                            snapshot_schema_version, snapshot, checksum, published_by)
                        values (:id, :aggregateType, :aggregateId, :version,
                                1, '{}'::jsonb, :checksum, :adminId)
                        """)
                .param("id", revisionId)
                .param("aggregateType", aggregateType)
                .param("aggregateId", aggregateId)
                .param("version", revisionVersion)
                .param("checksum", "d".repeat(64))
                .param("adminId", adminId)
                .update();
        if ("SITE".equals(aggregateType)) {
            jdbc.sql("""
                            update portfolio.publication
                            set status='PUBLISHED', current_revision_id=:revisionId,
                                current_slug=null, version=version+1,
                                published_at=clock_timestamp()
                            where aggregate_type='SITE' and aggregate_id=:aggregateId
                            """)
                    .param("revisionId", revisionId)
                    .param("aggregateId", aggregateId)
                    .update();
            return;
        }
        jdbc.sql("""
                        insert into portfolio.publication(
                            aggregate_type, aggregate_id, status, current_revision_id,
                            current_slug, version, published_at)
                        values (:aggregateType, :aggregateId, 'PUBLISHED', :revisionId,
                                :slug, 4, clock_timestamp())
                        """)
                .param("aggregateType", aggregateType)
                .param("aggregateId", aggregateId)
                .param("revisionId", revisionId)
                .param("slug", slug)
                .update();
    }

    private AffectedState affectedState(List<UUID> projectIds) {
        SiteWorkspaceState site = jdbc.sql("""
                        select version, updated_at
                        from portfolio.site_profile
                        where id=:siteId
                        """)
                .param("siteId", SITE_ID)
                .query((rs, rowNum) -> new SiteWorkspaceState(
                        rs.getLong("version"),
                        instant(rs, "updated_at")))
                .single();
        Map<UUID, ProjectWorkspaceState> projects = new LinkedHashMap<>();
        for (UUID projectId : projectIds) {
            projects.put(projectId, projectState(projectId));
        }
        Map<PublicationKey, PublicationState> publications = new LinkedHashMap<>();
        publications.put(new PublicationKey("SITE", SITE_ID), publicationState("SITE", SITE_ID));
        for (UUID projectId : projectIds) {
            publications.put(
                    new PublicationKey("PROJECT", projectId),
                    publicationState("PROJECT", projectId));
        }
        return new AffectedState(site, Map.copyOf(projects), Map.copyOf(publications));
    }

    private ProjectWorkspaceState projectState(UUID projectId) {
        return jdbc.sql("""
                        select version, publication_dirty, updated_at
                        from portfolio.project
                        where id=:projectId
                        """)
                .param("projectId", projectId)
                .query((rs, rowNum) -> new ProjectWorkspaceState(
                        rs.getLong("version"),
                        rs.getBoolean("publication_dirty"),
                        instant(rs, "updated_at")))
                .single();
    }

    private PublicationState publicationState(String aggregateType, UUID aggregateId) {
        return jdbc.sql("""
                        select status, current_revision_id, current_slug, version, published_at
                        from portfolio.publication
                        where aggregate_type=:aggregateType and aggregate_id=:aggregateId
                        """)
                .param("aggregateType", aggregateType)
                .param("aggregateId", aggregateId)
                .query((rs, rowNum) -> new PublicationState(
                        rs.getString("status"),
                        rs.getObject("current_revision_id", UUID.class),
                        rs.getString("current_slug"),
                        rs.getLong("version"),
                        instant(rs, "published_at")))
                .single();
    }

    private DatabaseState databaseState() {
        List<SiteDatabaseRow> sites = jdbc.sql("""
                        select id, version, updated_at
                        from portfolio.site_profile
                        order by id
                        """)
                .query((rs, rowNum) -> new SiteDatabaseRow(
                        rs.getObject("id", UUID.class),
                        rs.getLong("version"),
                        instant(rs, "updated_at")))
                .list();
        List<ProjectDatabaseRow> projects = jdbc.sql("""
                        select id, version, publication_dirty, updated_at
                        from portfolio.project
                        order by id
                        """)
                .query((rs, rowNum) -> new ProjectDatabaseRow(
                        rs.getObject("id", UUID.class),
                        rs.getLong("version"),
                        rs.getBoolean("publication_dirty"),
                        instant(rs, "updated_at")))
                .list();
        List<PublicationDatabaseRow> publications = jdbc.sql("""
                        select aggregate_type, aggregate_id, status, current_revision_id,
                               current_slug, version, published_at
                        from portfolio.publication
                        order by aggregate_type, aggregate_id
                        """)
                .query((rs, rowNum) -> new PublicationDatabaseRow(
                        rs.getString("aggregate_type"),
                        rs.getObject("aggregate_id", UUID.class),
                        rs.getString("status"),
                        rs.getObject("current_revision_id", UUID.class),
                        rs.getString("current_slug"),
                        rs.getLong("version"),
                        instant(rs, "published_at")))
                .list();
        return new DatabaseState(List.copyOf(sites), List.copyOf(projects), List.copyOf(publications));
    }

    private static String slug(UUID projectId) {
        return "listener-" + compact(projectId);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String compact(UUID id) {
        return id.toString().replace("-", "").substring(0, 12);
    }

    private record SharedMediaState(UUID assetId, List<UUID> projectIds) {}

    private record SiteWorkspaceState(long version, Instant updatedAt) {}

    private record ProjectWorkspaceState(
            long version, boolean publicationDirty, Instant updatedAt) {}

    private record PublicationKey(String aggregateType, UUID aggregateId) {}

    private record PublicationState(
            String status,
            UUID currentRevisionId,
            String currentSlug,
            long version,
            Instant publishedAt) {}

    private record AffectedState(
            SiteWorkspaceState site,
            Map<UUID, ProjectWorkspaceState> projects,
            Map<PublicationKey, PublicationState> publications) {}

    private record SiteDatabaseRow(UUID id, long version, Instant updatedAt) {}

    private record ProjectDatabaseRow(
            UUID id, long version, boolean publicationDirty, Instant updatedAt) {}

    private record PublicationDatabaseRow(
            String aggregateType,
            UUID aggregateId,
            String status,
            UUID currentRevisionId,
            String currentSlug,
            long version,
            Instant publishedAt) {}

    private record DatabaseState(
            List<SiteDatabaseRow> sites,
            List<ProjectDatabaseRow> projects,
            List<PublicationDatabaseRow> publications) {}
}
