package xyz.yychainsaw.portfolio.publishing.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import xyz.yychainsaw.portfolio.publishing.snapshot.AggregateType;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
@Transactional
@Isolated
class PublicMediaReferenceRepositoryTest extends PostgresIntegrationTestBase {
    private static final UUID SITE_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000001");
    private static final UUID CATALOG_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000002");
    private static final String CURRENT_VARIANT = "w640";
    private static final String OTHER_VARIANT = "w1280";

    @Autowired PublicMediaReferenceRepository references;
    @Autowired JdbcClient jdbc;

    @Test
    void currentSiteProjectAndCatalogRevisionReferencesArePublic() {
        UUID adminId = insertAdmin();

        MediaFixture siteMedia = insertReadyMedia(true);
        UUID siteRevision = insertRevision(
                adminId,
                AggregateType.SITE,
                SITE_ID,
                nextRevisionVersion(AggregateType.SITE, SITE_ID));
        insertReference(siteRevision, siteMedia.assetId(), CURRENT_VARIANT, "HERO");
        pointExistingPublication(AggregateType.SITE, SITE_ID, siteRevision, "PUBLISHED");

        MediaFixture projectMedia = insertReadyMedia(true);
        UUID projectId = UUID.randomUUID();
        UUID projectRevision = insertRevision(
                adminId, AggregateType.PROJECT, projectId, 1L);
        insertReference(
                projectRevision, projectMedia.assetId(), CURRENT_VARIANT, "DETAIL");
        insertPublication(
                AggregateType.PROJECT, projectId, projectRevision, "PUBLISHED");

        MediaFixture catalogMedia = insertReadyMedia(true);
        UUID catalogRevision = insertRevision(
                adminId,
                AggregateType.PROJECT_CATALOG,
                CATALOG_ID,
                nextRevisionVersion(AggregateType.PROJECT_CATALOG, CATALOG_ID));
        insertReference(
                catalogRevision, catalogMedia.assetId(), CURRENT_VARIANT, "COVER");
        pointExistingPublication(
                AggregateType.PROJECT_CATALOG,
                CATALOG_ID,
                catalogRevision,
                "PUBLISHED");

        assertThat(references.isCurrentlyPublished(
                        siteMedia.assetId(), CURRENT_VARIANT))
                .as("current SITE revision reference")
                .isTrue();
        assertThat(references.isCurrentlyPublished(
                        projectMedia.assetId(), CURRENT_VARIANT))
                .as("current PROJECT revision reference")
                .isTrue();
        assertThat(references.isCurrentlyPublished(
                        catalogMedia.assetId(), CURRENT_VARIANT))
                .as("current PROJECT_CATALOG revision reference")
                .isTrue();
    }

    @Test
    void historicalReferenceIsPrivateAfterThePublishedPointerMoves() {
        UUID adminId = insertAdmin();
        MediaFixture media = insertReadyMedia(false);
        UUID aggregateId = UUID.randomUUID();
        UUID historicalRevision = insertRevision(
                adminId, AggregateType.PROJECT, aggregateId, 1L);
        UUID currentRevision = insertRevision(
                adminId, AggregateType.PROJECT, aggregateId, 2L);
        insertReference(
                historicalRevision, media.assetId(), CURRENT_VARIANT, "DETAIL");
        insertPublication(
                AggregateType.PROJECT, aggregateId, currentRevision, "PUBLISHED");

        assertThat(references.isCurrentlyPublished(
                        media.assetId(), CURRENT_VARIANT))
                .isFalse();
    }

    @Test
    void workspaceOnlyDraftReferenceIsPrivate() {
        MediaFixture media = insertReadyMedia(false);
        UUID projectId = insertDraftProject();
        jdbc.sql("""
                        insert into portfolio.project_media(
                            project_id, media_asset_id, usage, sort_order, layout,
                            object_position, credit, source_url)
                        values (:projectId, :assetId, 'COVER', 0, 'wide',
                                '50% 50%', 'test credit',
                                'https://example.test/draft-source')
                        """)
                .param("projectId", projectId)
                .param("assetId", media.assetId())
                .update();

        assertThat(references.isCurrentlyPublished(
                        media.assetId(), CURRENT_VARIANT))
                .isFalse();
    }

    @Test
    void archivedCurrentRevisionReferenceIsPrivate() {
        UUID adminId = insertAdmin();
        MediaFixture media = insertReadyMedia(false);
        UUID aggregateId = UUID.randomUUID();
        UUID revisionId = insertRevision(
                adminId, AggregateType.PROJECT, aggregateId, 1L);
        insertReference(revisionId, media.assetId(), CURRENT_VARIANT, "DETAIL");
        insertPublication(
                AggregateType.PROJECT, aggregateId, revisionId, "ARCHIVED");

        assertThat(references.isCurrentlyPublished(
                        media.assetId(), CURRENT_VARIANT))
                .isFalse();
    }

    @Test
    void authorizationIsExactToAssetAndVariant() {
        UUID adminId = insertAdmin();
        MediaFixture media = insertReadyMedia(true);
        UUID aggregateId = UUID.randomUUID();
        UUID revisionId = insertRevision(
                adminId, AggregateType.PROJECT, aggregateId, 1L);
        insertReference(revisionId, media.assetId(), CURRENT_VARIANT, "DETAIL");
        insertPublication(
                AggregateType.PROJECT, aggregateId, revisionId, "PUBLISHED");

        assertThat(references.isCurrentlyPublished(
                        media.assetId(), CURRENT_VARIANT))
                .isTrue();
        assertThat(references.isCurrentlyPublished(
                        media.assetId(), OTHER_VARIANT))
                .as("an existing but unreferenced variant")
                .isFalse();
        assertThat(references.isCurrentlyPublished(
                        UUID.randomUUID(), CURRENT_VARIANT))
                .as("an unknown asset")
                .isFalse();
    }

    private UUID insertAdmin() {
        var existing = jdbc.sql("select id from portfolio.admin_user limit 1")
                .query(UUID.class)
                .optional();
        if (existing.isPresent()) {
            return existing.orElseThrow();
        }
        UUID adminId = UUID.randomUUID();
        jdbc.sql("""
                        insert into portfolio.admin_user(
                            id, username, password_hash, status, totp_key_version,
                            totp_nonce, totp_ciphertext)
                        values (:id, :username, '{noop}test', 'ACTIVE', 1,
                                decode(repeat('00', 12), 'hex'), decode('00', 'hex'))
                        """)
                .param("id", adminId)
                .param("username", "public-media-" + compact(adminId))
                .update();
        return adminId;
    }

    private MediaFixture insertReadyMedia(boolean includeOtherVariant) {
        UUID assetId = UUID.randomUUID();
        String assetSha256 = digest(assetId);
        jdbc.sql("""
                        insert into portfolio.media_asset(
                            id, provider, object_key, original_filename, mime_type,
                            byte_size, width, height, sha256, status)
                        values (:id, 'LOCAL', :objectKey, 'public-test.png', 'image/png',
                                4, 1280, 720, :sha256, 'READY')
                        """)
                .param("id", assetId)
                .param("objectKey", "public-reference/" + assetId + "/original.png")
                .param("sha256", assetSha256)
                .update();
        insertVariant(assetId, CURRENT_VARIANT, 640, 360, "a".repeat(64));
        if (includeOtherVariant) {
            insertVariant(assetId, OTHER_VARIANT, 1280, 720, "b".repeat(64));
        }
        return new MediaFixture(assetId);
    }

    private void insertVariant(
            UUID assetId,
            String variantName,
            int width,
            int height,
            String sha256) {
        jdbc.sql("""
                        insert into portfolio.media_variant(
                            id, asset_id, variant_name, format, object_key, mime_type,
                            byte_size, width, height, sha256, status)
                        values (:id, :assetId, :variantName, 'PNG', :objectKey,
                                'image/png', 4, :width, :height, :sha256, 'READY')
                        """)
                .param("id", UUID.randomUUID())
                .param("assetId", assetId)
                .param("variantName", variantName)
                .param("objectKey", "public-reference/" + assetId + '/'
                        + variantName + ".png")
                .param("width", width)
                .param("height", height)
                .param("sha256", sha256)
                .update();
    }

    private UUID insertRevision(
            UUID adminId,
            AggregateType type,
            UUID aggregateId,
            long version) {
        UUID revisionId = UUID.randomUUID();
        jdbc.sql("""
                        insert into portfolio.content_revision(
                            id, aggregate_type, aggregate_id, version,
                            snapshot_schema_version, snapshot, checksum, published_by)
                        values (:id, :type, :aggregateId, :version,
                                1, '{}'::jsonb, :checksum, :adminId)
                        """)
                .param("id", revisionId)
                .param("type", type.name())
                .param("aggregateId", aggregateId)
                .param("version", version)
                .param("checksum", digest(revisionId))
                .param("adminId", adminId)
                .update();
        return revisionId;
    }

    private void insertReference(
            UUID revisionId,
            UUID assetId,
            String variantName,
            String usage) {
        jdbc.sql("""
                        insert into portfolio.revision_media_reference(
                            revision_id, asset_id, variant_name, usage)
                        values (:revisionId, :assetId, :variantName, :usage)
                        """)
                .param("revisionId", revisionId)
                .param("assetId", assetId)
                .param("variantName", variantName)
                .param("usage", usage)
                .update();
    }

    private void pointExistingPublication(
            AggregateType type,
            UUID aggregateId,
            UUID revisionId,
            String status) {
        int updated = jdbc.sql("""
                        update portfolio.publication
                        set status=:status,
                            current_revision_id=:revisionId,
                            current_slug=null,
                            version=version+1,
                            published_at=clock_timestamp()
                        where aggregate_type=:type and aggregate_id=:aggregateId
                        """)
                .param("status", status)
                .param("revisionId", revisionId)
                .param("type", type.name())
                .param("aggregateId", aggregateId)
                .update();
        assertThat(updated).isOne();
    }

    private void insertPublication(
            AggregateType type,
            UUID aggregateId,
            UUID revisionId,
            String status) {
        jdbc.sql("""
                        insert into portfolio.publication(
                            aggregate_type, aggregate_id, status, current_revision_id,
                            current_slug, version, published_at)
                        values (:type, :aggregateId, :status, :revisionId,
                                null, 1, clock_timestamp())
                        """)
                .param("type", type.name())
                .param("aggregateId", aggregateId)
                .param("status", status)
                .param("revisionId", revisionId)
                .update();
    }

    private UUID insertDraftProject() {
        UUID projectId = UUID.randomUUID();
        int sortOrder = jdbc.sql(
                        "select coalesce(max(sort_order), -1) + 1 from portfolio.project")
                .query(Integer.class)
                .single();
        jdbc.sql("""
                        insert into portfolio.project(
                            id, external_key, slug, number_label, sort_order,
                            featured, visible, publication_dirty, version)
                        values (:id, :externalKey, :slug, '01', :sortOrder,
                                false, true, true, 0)
                        """)
                .param("id", projectId)
                .param("externalKey", "public-media-draft-" + projectId)
                .param("slug", "public-media-" + compact(projectId))
                .param("sortOrder", sortOrder)
                .update();
        return projectId;
    }

    private long nextRevisionVersion(AggregateType type, UUID aggregateId) {
        return jdbc.sql("""
                        select coalesce(max(version), 0) + 1
                        from portfolio.content_revision
                        where aggregate_type=:type and aggregate_id=:aggregateId
                        """)
                .param("type", type.name())
                .param("aggregateId", aggregateId)
                .query(Long.class)
                .single();
    }

    private static String digest(UUID value) {
        return value.toString().replace("-", "").repeat(2);
    }

    private static String compact(UUID value) {
        return value.toString().replace("-", "").substring(0, 12);
    }

    private record MediaFixture(UUID assetId) { }
}
