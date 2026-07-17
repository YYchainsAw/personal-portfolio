package xyz.yychainsaw.portfolio.publishing.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.content.persistence.SiteWorkspaceRepository;
import xyz.yychainsaw.portfolio.media.application.MediaChangeType;
import xyz.yychainsaw.portfolio.media.application.MediaReference;
import xyz.yychainsaw.portfolio.publishing.persistence.PublishingRepository;
import xyz.yychainsaw.portfolio.publishing.persistence.PublishingRepository.MediaReferenceRow;
import xyz.yychainsaw.portfolio.publishing.persistence.PublishingRepository.RevisionRow;
import xyz.yychainsaw.portfolio.publishing.snapshot.AggregateType;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PublishingFoundationIntegrationTest extends PostgresIntegrationTestBase {
    private static final UUID CATALOG_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Autowired PublishingRepository publications;
    @Autowired CurrentPublicationQuery currentPublication;
    @Autowired ProjectLabelQuery labels;
    @Autowired ContentMediaReferenceChecker references;
    @Autowired ContentMediaChangeListener mediaChanges;
    @Autowired SiteWorkspaceRepository sites;
    @Autowired JdbcClient jdbc;

    @Test
    void repositoryRoundTripsRevisionPointerHistoryAndTimestampedRedirect() {
        UUID adminId = ensureAdmin();
        UUID projectId = insertProject("Repository title");
        publications.ensureProjectPublication(projectId);
        var initial = publications.lock(AggregateType.PROJECT, projectId);
        assertThat(initial.status()).isEqualTo("ARCHIVED");
        assertThat(initial.version()).isZero();

        Instant publishedAt = Instant.parse("2026-07-17T08:00:00Z");
        RevisionRow revision = revision(
                adminId, AggregateType.PROJECT, projectId, 1L, "{}");
        publications.insertRevision(revision);
        assertThat(publications.casPublish(
                AggregateType.PROJECT,
                projectId,
                initial.version(),
                revision.id(),
                slug(projectId),
                publishedAt)).isTrue();

        assertThat(publications.findPublishedProjectBySlug(slug(projectId)))
                .get()
                .extracting(PublishingRepository.PublicationRow::currentRevisionId)
                .isEqualTo(revision.id());
        assertThat(publications.requireRevision(revision.id()).json()).isEqualTo("{}");
        assertThat(publications.history(AggregateType.PROJECT, projectId))
                .extracting(RevisionRow::id)
                .containsExactly(revision.id());

        Instant redirectedAt = publishedAt.plusSeconds(60);
        publications.insertRedirect(slug(projectId), "new-" + slug(projectId), projectId, redirectedAt);
        assertThat(publications.redirectTarget(slug(projectId)))
                .contains("new-" + slug(projectId));
        assertThat(jdbc.sql("""
                        select created_at from portfolio.slug_redirect where old_slug=:slug
                        """)
                .param("slug", slug(projectId))
                .query(Instant.class)
                .single()).isEqualTo(redirectedAt);

        assertThat(publications.casArchive(
                AggregateType.PROJECT, projectId, 1L, publishedAt.plusSeconds(120))).isTrue();
        var archived = publications.lock(AggregateType.PROJECT, projectId);
        assertThat(archived.status()).isEqualTo("ARCHIVED");
        assertThat(archived.currentRevisionId()).isEqualTo(revision.id());
    }

    @Test
    void readPortsRequireCurrentProjectAndCatalogPointersButKeepArchivedLabels() {
        UUID adminId = ensureAdmin();
        UUID projectId = insertProject("Current title");
        publications.ensureProjectPublication(projectId);
        var projectPointer = publications.lock(AggregateType.PROJECT, projectId);
        var catalogPointer = publications.lock(AggregateType.PROJECT_CATALOG, CATALOG_ID);

        RevisionRow projectRevision = revision(
                adminId,
                AggregateType.PROJECT,
                projectId,
                projectPointer.version() + 1,
                "{}");
        RevisionRow catalogRevision = revision(
                adminId,
                AggregateType.PROJECT_CATALOG,
                CATALOG_ID,
                catalogPointer.version() + 1,
                "{\"projects\":[{\"projectId\":\"" + projectId + "\"}]}");
        publications.insertRevision(projectRevision);
        publications.insertRevision(catalogRevision);
        Instant timestamp = Instant.parse("2026-07-17T09:00:00Z");
        assertThat(publications.casPublish(
                AggregateType.PROJECT,
                projectId,
                projectPointer.version(),
                projectRevision.id(),
                slug(projectId),
                timestamp)).isTrue();
        assertThat(publications.casPublish(
                AggregateType.PROJECT_CATALOG,
                CATALOG_ID,
                catalogPointer.version(),
                catalogRevision.id(),
                null,
                timestamp)).isTrue();

        assertThat(currentPublication.isCurrentPublishedProject(projectId)).isTrue();
        assertThat(labels.findProjectTitle(projectId, LocaleCode.EN)).contains("Current title");

        assertThat(publications.casArchive(
                AggregateType.PROJECT,
                projectId,
                projectPointer.version() + 1,
                timestamp.plusSeconds(60))).isTrue();
        assertThat(currentPublication.isCurrentPublishedProject(projectId)).isFalse();
        assertThat(labels.findProjectTitle(projectId, LocaleCode.EN)).contains("Current title");
        assertThat(labels.findProjectTitle(UUID.randomUUID(), LocaleCode.EN)).isEmpty();
    }

    @Test
    void retainedRevisionReferencesAreReportedAndMediaChangesBumpEachWorkspaceOnce() {
        UUID adminId = ensureAdmin();
        UUID projectId = insertProject("Media title");
        UUID assetId = insertReadyMedia();
        jdbc.sql("""
                        insert into portfolio.project_media(
                            project_id, media_asset_id, usage, sort_order, layout,
                            object_position, credit, source_url)
                        values (:projectId, :assetId, 'COVER', 0, 'wide',
                                '50% 50%', 'credit', 'https://example.com/source')
                        """)
                .param("projectId", projectId)
                .param("assetId", assetId)
                .update();
        jdbc.sql("""
                        insert into portfolio.resume_document(
                            id, site_id, locale, media_asset_id, version_label,
                            is_current, document_date)
                        values (:id, '00000000-0000-0000-0000-000000000001', 'en',
                                :assetId, 'v1', true, date '2026-07-17')
                        """)
                .param("id", UUID.randomUUID())
                .param("assetId", assetId)
                .update();
        RevisionRow retained = revision(
                adminId, AggregateType.PROJECT, UUID.randomUUID(), 1L, "{}");
        publications.insertRevision(retained);
        publications.insertMediaReferences(
                retained.id(), List.of(new MediaReferenceRow(assetId, "original", "DETAIL")));

        assertThat(references.findReferences(assetId)).containsExactly(
                new MediaReference("CONTENT_REVISION", retained.id()),
                new MediaReference("PROJECT_WORKSPACE", projectId),
                new MediaReference(
                        "SITE_WORKSPACE",
                        UUID.fromString("00000000-0000-0000-0000-000000000001")));

        long siteBefore = version("site_profile", UUID.fromString(
                "00000000-0000-0000-0000-000000000001"));
        long projectBefore = version("project", projectId);
        UUID pointerBefore = publications.find(AggregateType.PROJECT, projectId)
                .map(PublishingRepository.PublicationRow::currentRevisionId)
                .orElse(null);

        mediaChanges.onMediaChanged(assetId, MediaChangeType.TRANSLATION_UPDATED);

        assertThat(version("site_profile", UUID.fromString(
                "00000000-0000-0000-0000-000000000001"))).isEqualTo(siteBefore + 1);
        assertThat(version("project", projectId)).isEqualTo(projectBefore + 1);
        assertThat(jdbc.sql("select publication_dirty from portfolio.project where id=:id")
                .param("id", projectId)
                .query(Boolean.class)
                .single()).isTrue();
        assertThat(publications.find(AggregateType.PROJECT, projectId)
                .map(PublishingRepository.PublicationRow::currentRevisionId)
                .orElse(null)).isEqualTo(pointerBefore);

        assertThat(sites.requireForUpdate().siteId()).isEqualTo(UUID.fromString(
                "00000000-0000-0000-0000-000000000001"));
    }

    private UUID ensureAdmin() {
        return jdbc.sql("select id from portfolio.admin_user order by created_at limit 1")
                .query(UUID.class)
                .optional()
                .orElseGet(() -> {
                    UUID id = UUID.randomUUID();
                    jdbc.sql("""
                                    insert into portfolio.admin_user(
                                      id, username, password_hash, status, totp_key_version,
                                      totp_nonce, totp_ciphertext)
                                    values (:id, :username, '{noop}test', 'ACTIVE', 1,
                                            decode(repeat('00', 12), 'hex'), decode('00', 'hex'))
                                    """)
                            .param("id", id)
                            .param("username", "publish-" + compact(id))
                            .update();
                    return id;
                });
    }

    private UUID insertProject(String title) {
        UUID id = UUID.randomUUID();
        int sortOrder = jdbc.sql("select coalesce(max(sort_order), -1) + 1 from portfolio.project")
                .query(Integer.class)
                .single();
        jdbc.sql("""
                        insert into portfolio.project(
                          id, external_key, slug, number_label, sort_order,
                          featured, visible, publication_dirty, version)
                        values (:id, :externalKey, :slug, '01', :sortOrder,
                                false, true, true, 0)
                        """)
                .param("id", id)
                .param("externalKey", "project-" + id)
                .param("slug", slug(id))
                .param("sortOrder", sortOrder)
                .update();
        for (LocaleCode locale : LocaleCode.values()) {
            jdbc.sql("""
                            insert into portfolio.project_translation(
                              project_id, locale, status_label, eyebrow, title, summary,
                              seo_title, seo_description)
                            values (:projectId, :locale, 'ready', 'project', :title,
                                    'summary', 'seo', 'description')
                            """)
                    .param("projectId", id)
                    .param("locale", locale.value())
                    .param("title", title)
                    .update();
        }
        return id;
    }

    private UUID insertReadyMedia() {
        UUID assetId = UUID.randomUUID();
        jdbc.sql("""
                        insert into portfolio.media_asset(
                          id, provider, object_key, original_filename, mime_type,
                          byte_size, sha256, status)
                        values (:id, 'LOCAL', :objectKey, 'image.png', 'image/png',
                                1, repeat('a', 64), 'READY')
                        """)
                .param("id", assetId)
                .param("objectKey", "test/" + assetId + ".png")
                .update();
        jdbc.sql("""
                        insert into portfolio.media_variant(
                          id, asset_id, variant_name, format, object_key, mime_type,
                          byte_size, width, height, sha256, status)
                        values (:id, :assetId, 'original', 'PNG', :objectKey, 'image/png',
                                1, 1, 1, repeat('b', 64), 'READY')
                        """)
                .param("id", UUID.randomUUID())
                .param("assetId", assetId)
                .param("objectKey", "test/" + assetId + "-original.png")
                .update();
        return assetId;
    }

    private RevisionRow revision(
            UUID adminId,
            AggregateType type,
            UUID aggregateId,
            long version,
            String json) {
        return new RevisionRow(
                UUID.randomUUID(),
                type,
                aggregateId,
                version,
                1,
                json,
                "c".repeat(64),
                adminId,
                Instant.parse("2026-07-17T07:00:00Z"));
    }

    private long version(String table, UUID id) {
        if ("site_profile".equals(table)) {
            return jdbc.sql("select version from portfolio.site_profile where id=:id")
                    .param("id", id)
                    .query(Long.class)
                    .single();
        }
        return jdbc.sql("select version from portfolio.project where id=:id")
                .param("id", id)
                .query(Long.class)
                .single();
    }

    private static String slug(UUID id) {
        return "project-" + compact(id);
    }

    private static String compact(UUID id) {
        return id.toString().replace("-", "").substring(0, 12);
    }
}
