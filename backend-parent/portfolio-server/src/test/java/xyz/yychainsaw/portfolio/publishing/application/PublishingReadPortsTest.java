package xyz.yychainsaw.portfolio.publishing.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
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
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.publishing.persistence.PublishingRepository;
import xyz.yychainsaw.portfolio.publishing.persistence.PublishingRepository.PublicationRow;
import xyz.yychainsaw.portfolio.publishing.persistence.PublishingRepository.RevisionRow;
import xyz.yychainsaw.portfolio.publishing.snapshot.AggregateType;
import xyz.yychainsaw.portfolio.publishing.snapshot.EncodedSnapshot;
import xyz.yychainsaw.portfolio.publishing.snapshot.SnapshotCodec;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.LocaleV1;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.ProjectCatalogSnapshotV1;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.ProjectCatalogSnapshotV1.Card;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.ProjectCatalogSnapshotV1.CardCopy;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.ProjectSnapshotV1;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.ProjectSnapshotV1.ProjectCopyV1;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Isolated
class PublishingReadPortsTest extends PostgresIntegrationTestBase {
    private static final UUID CATALOG_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Instant PUBLISHED_AT =
            Instant.parse("2026-07-17T12:00:00Z");

    @Autowired CurrentPublicationQuery currentPublications;
    @Autowired ProjectLabelQuery projectLabels;
    @Autowired PublishingRepository publications;
    @Autowired SnapshotCodec snapshots;
    @Autowired JdbcClient jdbc;

    @Test
    void currentProjectRequiresPublishedPointerMatchingRevisionAndCurrentCatalogMembership() {
        UUID adminId = ensureAdmin();
        ProjectSeed current = insertProject("当前项目", "Current project");
        ProjectSeed wrongRevision = insertProject("错误版本", "Wrong revision");
        ProjectSeed neverPublished = insertProject("从未发布", "Never published");
        ProjectSeed archived = insertProject("已归档", "Archived");
        ProjectSeed historicalCatalogOnly =
                insertProject("历史目录", "Historical catalog");

        RevisionRow currentRevision = publishProject(current, adminId, 1);
        publishProject(archived, adminId, 1);
        archiveProject(archived);
        publishProject(historicalCatalogOnly, adminId, 1);
        publications.ensureProjectPublication(neverPublished.id());

        publications.ensureProjectPublication(wrongRevision.id());
        PublicationRow wrongPointer =
                publications.lock(AggregateType.PROJECT, wrongRevision.id());
        assertThat(publications.casPublish(
                AggregateType.PROJECT,
                wrongRevision.id(),
                wrongPointer.version(),
                currentRevision.id(),
                wrongRevision.slug(),
                PUBLISHED_AT)).isTrue();

        publishCatalog(adminId, List.of(historicalCatalogOnly));
        publishCatalog(adminId, List.of(
                current, wrongRevision, neverPublished, archived));

        assertThat(currentPublications.isCurrentPublishedProject(current.id())).isTrue();
        assertThat(currentPublications.isCurrentPublishedProject(wrongRevision.id())).isFalse();
        assertThat(currentPublications.isCurrentPublishedProject(neverPublished.id())).isFalse();
        assertThat(currentPublications.isCurrentPublishedProject(archived.id())).isFalse();
        assertThat(currentPublications.isCurrentPublishedProject(
                historicalCatalogOnly.id())).isFalse();

        publishProject(current, adminId, 2);
        assertThat(currentPublications.isCurrentPublishedProject(current.id())).isTrue();

        PublicationRow catalog =
                publications.lock(AggregateType.PROJECT_CATALOG, CATALOG_ID);
        assertThat(publications.casArchive(
                AggregateType.PROJECT_CATALOG,
                CATALOG_ID,
                catalog.version(),
                PUBLISHED_AT.plusSeconds(60))).isTrue();
        assertThat(currentPublications.isCurrentPublishedProject(current.id())).isFalse();
    }

    @Test
    void labelsReadCurrentBilingualWorkspaceForPublishedAndArchivedProjectsOnly() {
        UUID adminId = ensureAdmin();
        ProjectSeed published = insertProject("发布前标题", "Title before publish");
        ProjectSeed archived = insertProject("归档前标题", "Title before archive");
        ProjectSeed neverPublished = insertProject("草稿标题", "Draft title");

        publishProject(published, adminId, 1);
        publishProject(archived, adminId, 1);
        archiveProject(archived);
        publications.ensureProjectPublication(neverPublished.id());

        updateTitles(published, "当前发布标题", "Current published title");
        updateTitles(archived, "当前归档标题", "Current archived title");

        assertThat(projectLabels.findProjectTitle(published.id(), LocaleCode.ZH_CN))
                .contains("当前发布标题");
        assertThat(projectLabels.findProjectTitle(published.id(), LocaleCode.EN))
                .contains("Current published title");
        assertThat(projectLabels.findProjectTitle(archived.id(), LocaleCode.ZH_CN))
                .contains("当前归档标题");
        assertThat(projectLabels.findProjectTitle(archived.id(), LocaleCode.EN))
                .contains("Current archived title");
        assertThat(projectLabels.findProjectTitle(neverPublished.id(), LocaleCode.ZH_CN))
                .isEmpty();
        assertThat(projectLabels.findProjectTitle(neverPublished.id(), LocaleCode.EN))
                .isEmpty();
        assertThat(projectLabels.findProjectTitle(UUID.randomUUID(), LocaleCode.ZH_CN))
                .isEmpty();
        assertThat(projectLabels.findProjectTitle(UUID.randomUUID(), LocaleCode.EN))
                .isEmpty();
    }

    private RevisionRow publishProject(ProjectSeed project, UUID adminId, long revisionVersion) {
        publications.ensureProjectPublication(project.id());
        PublicationRow pointer = publications.lock(AggregateType.PROJECT, project.id());
        RevisionRow revision = revision(
                adminId,
                AggregateType.PROJECT,
                project.id(),
                revisionVersion,
                projectSnapshot(project));
        publications.insertRevision(revision);
        assertThat(publications.casPublish(
                AggregateType.PROJECT,
                project.id(),
                pointer.version(),
                revision.id(),
                project.slug(),
                PUBLISHED_AT.plusSeconds(revisionVersion))).isTrue();
        return revision;
    }

    private void archiveProject(ProjectSeed project) {
        PublicationRow pointer = publications.lock(AggregateType.PROJECT, project.id());
        assertThat(publications.casArchive(
                AggregateType.PROJECT,
                project.id(),
                pointer.version(),
                PUBLISHED_AT.plusSeconds(30))).isTrue();
    }

    private void publishCatalog(UUID adminId, List<ProjectSeed> projects) {
        PublicationRow pointer =
                publications.lock(AggregateType.PROJECT_CATALOG, CATALOG_ID);
        long nextVersion = pointer.version() + 1;
        RevisionRow revision = revision(
                adminId,
                AggregateType.PROJECT_CATALOG,
                CATALOG_ID,
                nextVersion,
                catalogSnapshot(projects));
        publications.insertRevision(revision);
        assertThat(publications.casPublish(
                AggregateType.PROJECT_CATALOG,
                CATALOG_ID,
                pointer.version(),
                revision.id(),
                null,
                PUBLISHED_AT.plusSeconds(nextVersion))).isTrue();
    }

    private RevisionRow revision(
            UUID adminId,
            AggregateType type,
            UUID aggregateId,
            long version,
            Object snapshot) {
        EncodedSnapshot encoded = snapshots.encode(snapshot);
        return new RevisionRow(
                UUID.randomUUID(),
                type,
                aggregateId,
                version,
                encoded.schemaVersion(),
                encoded.json(),
                encoded.sha256(),
                adminId,
                PUBLISHED_AT.plusSeconds(version));
    }

    private ProjectSnapshotV1 projectSnapshot(ProjectSeed project) {
        return new ProjectSnapshotV1(
                1,
                project.id(),
                project.externalKey(),
                project.slug(),
                "01",
                project.sortOrder(),
                false,
                Map.of(
                        LocaleV1.ZH_CN,
                        projectCopy(project.zhTitle()),
                        LocaleV1.EN,
                        projectCopy(project.enTitle())),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    private ProjectCatalogSnapshotV1 catalogSnapshot(List<ProjectSeed> projects) {
        List<Card> cards = projects.stream()
                .map(project -> new Card(
                        project.id(),
                        project.slug(),
                        "01",
                        projects.indexOf(project),
                        false,
                        Map.of(
                                LocaleV1.ZH_CN,
                                cardCopy(project.zhTitle()),
                                LocaleV1.EN,
                                cardCopy(project.enTitle())),
                        null))
                .toList();
        return new ProjectCatalogSnapshotV1(1, cards);
    }

    private ProjectSeed insertProject(String zhTitle, String enTitle) {
        UUID id = UUID.randomUUID();
        String compact = compact(id);
        String externalKey = "read-port-" + compact;
        String slug = "read-port-" + compact;
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
                .param("id", id)
                .param("externalKey", externalKey)
                .param("slug", slug)
                .param("sortOrder", sortOrder)
                .update();
        insertTranslation(id, LocaleCode.ZH_CN, zhTitle);
        insertTranslation(id, LocaleCode.EN, enTitle);
        return new ProjectSeed(
                id, externalKey, slug, sortOrder, zhTitle, enTitle);
    }

    private void insertTranslation(UUID projectId, LocaleCode locale, String title) {
        jdbc.sql("""
                        insert into portfolio.project_translation(
                          project_id, locale, status_label, eyebrow, title, summary,
                          seo_title, seo_description)
                        values (:projectId, :locale, 'ready', 'project', :title,
                                'summary', 'seo', 'description')
                        """)
                .param("projectId", projectId)
                .param("locale", locale.value())
                .param("title", title)
                .update();
    }

    private void updateTitles(ProjectSeed project, String zhTitle, String enTitle) {
        updateTitle(project.id(), LocaleCode.ZH_CN, zhTitle);
        updateTitle(project.id(), LocaleCode.EN, enTitle);
    }

    private void updateTitle(UUID projectId, LocaleCode locale, String title) {
        assertThat(jdbc.sql("""
                        update portfolio.project_translation
                        set title=:title
                        where project_id=:projectId and locale=:locale
                        """)
                .param("title", title)
                .param("projectId", projectId)
                .param("locale", locale.value())
                .update()).isOne();
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
                            .param("username", "read-port-" + compact(id))
                            .update();
                    return id;
                });
    }

    private static ProjectCopyV1 projectCopy(String title) {
        return new ProjectCopyV1(
                "ready", "project", title, "summary", "seo", "description");
    }

    private static CardCopy cardCopy(String title) {
        return new CardCopy("ready", "project", title, "summary", List.of());
    }

    private static String compact(UUID id) {
        return id.toString().replace("-", "").substring(0, 20);
    }

    private record ProjectSeed(
            UUID id,
            String externalKey,
            String slug,
            int sortOrder,
            String zhTitle,
            String enTitle) { }
}
