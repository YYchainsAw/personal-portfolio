package xyz.yychainsaw.portfolio.publishing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;

import java.sql.Connection;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.yychainsaw.portfolio.audit.AuditService;
import xyz.yychainsaw.portfolio.auth.CurrentAdminProvider;
import xyz.yychainsaw.portfolio.content.api.ProjectWorkspaceDto;
import xyz.yychainsaw.portfolio.publishing.api.PublicationResult;
import xyz.yychainsaw.portfolio.publishing.api.PublishProjectCommand;
import xyz.yychainsaw.portfolio.publishing.persistence.PublishingRepository;
import xyz.yychainsaw.portfolio.publishing.persistence.PublishingRepository.PublicationRow;
import xyz.yychainsaw.portfolio.publishing.snapshot.AggregateType;
import xyz.yychainsaw.portfolio.publishing.snapshot.SnapshotCodec;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.ProjectCatalogSnapshotV1;
import xyz.yychainsaw.portfolio.publishing.support.PublishingTestFixture;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
@ActiveProfiles("test")
@Isolated
@Import({
    PublishingTestFixture.class,
    PublicationServiceIntegrationTest.FixedAdminConfiguration.class
})
class PublicationServiceIntegrationTest extends PostgresIntegrationTestBase {
    @Autowired PublicationService service;
    @Autowired PublishingRepository publishing;
    @Autowired PublishingTestFixture fixture;
    @Autowired SnapshotCodec codec;
    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate transactions;

    @MockitoSpyBean AuditService audit;

    @BeforeEach
    void ensureRealAdminRow() {
        fixture.ensureAdmin();
    }

    @Test
    void publishingProjectAtomicallyMovesBothPointersAndPersistsExhaustiveHistory() {
        transactions.executeWithoutResult(status -> {
            ProjectWorkspaceDto project = fixture.persistReadyProject();
            UUID coverAssetId = fixture.coverAssetId(project.id());
            PublicationRow catalogBefore = publishing
                    .find(AggregateType.PROJECT_CATALOG, PublicationService.PROJECT_CATALOG_ID)
                    .orElseThrow();

            PublicationResult result = service.publishProject(new PublishProjectCommand(
                    project.id(), project.version(), 0L, catalogBefore.version()));

            PublicationRow projectPointer = publishing
                    .find(AggregateType.PROJECT, project.id())
                    .orElseThrow();
            PublicationRow catalogPointer = publishing
                    .find(AggregateType.PROJECT_CATALOG, PublicationService.PROJECT_CATALOG_ID)
                    .orElseThrow();
            assertThat(projectPointer.status()).isEqualTo("PUBLISHED");
            assertThat(projectPointer.currentRevisionId()).isEqualTo(result.revisionId());
            assertThat(projectPointer.currentSlug()).isEqualTo(project.slug());
            assertThat(projectPointer.version()).isEqualTo(1L);
            assertThat(catalogPointer.status()).isEqualTo("PUBLISHED");
            assertThat(catalogPointer.currentRevisionId())
                    .isEqualTo(result.catalogRevisionId());
            assertThat(catalogPointer.version()).isEqualTo(catalogBefore.version() + 1L);
            assertThat(catalogPointer.publishedAt()).isEqualTo(projectPointer.publishedAt());

            assertThat(result.aggregateVersion()).isEqualTo(1L);
            assertThat(result.catalogVersion()).isEqualTo(catalogBefore.version() + 1L);
            assertThat(jdbc.sql("""
                            select publication_dirty
                            from portfolio.project
                            where id=:projectId
                            """)
                    .param("projectId", project.id())
                    .query(Boolean.class)
                    .single()).isFalse();

            var projectRevision = publishing.requireRevision(result.revisionId());
            var catalogRevision = publishing.requireRevision(result.catalogRevisionId());
            assertThat(projectRevision)
                    .extracting(
                            PublishingRepository.RevisionRow::type,
                            PublishingRepository.RevisionRow::aggregateId,
                            PublishingRepository.RevisionRow::version,
                            PublishingRepository.RevisionRow::publishedBy,
                            PublishingRepository.RevisionRow::checksum)
                    .containsExactly(
                            AggregateType.PROJECT,
                            project.id(),
                            1L,
                            PublishingTestFixture.ADMIN_ID,
                            result.checksum());
            assertThat(catalogRevision.type()).isEqualTo(AggregateType.PROJECT_CATALOG);
            assertThat(catalogRevision.aggregateId())
                    .isEqualTo(PublicationService.PROJECT_CATALOG_ID);
            assertThat(catalogRevision.version()).isEqualTo(catalogBefore.version() + 1L);
            assertThat(catalogRevision.publishedBy())
                    .isEqualTo(PublishingTestFixture.ADMIN_ID);
            assertThat(catalogRevision.publishedAt()).isEqualTo(projectRevision.publishedAt());

            assertThat(references(result.revisionId()))
                    .extracting(ReferenceRow::assetId, ReferenceRow::variantName, ReferenceRow::usage)
                    .containsExactly(
                            tuple(coverAssetId, "w1280", "COVER"),
                            tuple(coverAssetId, "w640", "COVER"));
            assertThat(references(result.catalogRevisionId()))
                    .containsExactlyElementsOf(expectedCatalogReferences(catalogRevision.json()));

            assertThat(jdbc.sql("""
                            select action, target_type, target_id, outcome,
                                   metadata ->> 'revisionId' revision_id
                            from portfolio.audit_log
                            where actor_admin_id=:actorId and target_id=:targetId
                            """)
                    .param("actorId", PublishingTestFixture.ADMIN_ID)
                    .param("targetId", project.id().toString())
                    .query((row, number) -> new AuditRow(
                            row.getString("action"),
                            row.getString("target_type"),
                            row.getString("target_id"),
                            row.getString("outcome"),
                            row.getString("revision_id")))
                    .list()).containsExactly(new AuditRow(
                            "PROJECT_PUBLISHED",
                            "PROJECT",
                            project.id().toString(),
                            "SUCCESS",
                            result.revisionId().toString()));

            status.setRollbackOnly();
        });
    }

    @Test
    void auditFailureRollsBackPointersRevisionsDirtyClearRedirectAndAudit() {
        ProjectWorkspaceDto project = fixture.persistReadyProject();
        UUID coverAssetId = fixture.coverAssetId(project.id());
        try {
            String retainedOldSlug = fixture.seedArchivedProjectPointerWithOldSlug(project.id());
            PublicationRow projectBefore = publishing
                    .find(AggregateType.PROJECT, project.id())
                    .orElseThrow();
            PublicationRow catalogBefore = publishing
                    .find(AggregateType.PROJECT_CATALOG, PublicationService.PROJECT_CATALOG_ID)
                    .orElseThrow();
            long projectRevisionsBefore = revisionCount(AggregateType.PROJECT, project.id());
            long catalogRevisionsBefore = revisionCount(
                    AggregateType.PROJECT_CATALOG, PublicationService.PROJECT_CATALOG_ID);
            long referencesBefore = referenceCount();
            long auditBefore = auditCount(project.id());

            doAnswer(invocation -> {
                        invocation.callRealMethod();
                        throw new IllegalStateException("forced audit failure");
                    })
                    .when(audit)
                    .record(argThat(command -> command != null
                            && "PROJECT_PUBLISHED".equals(command.action())
                            && project.id().toString().equals(command.targetId())));

            assertThatThrownBy(() -> service.publishProject(new PublishProjectCommand(
                            project.id(),
                            project.version(),
                            projectBefore.version(),
                            catalogBefore.version())))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("forced audit failure");

            assertThat(publishing.find(AggregateType.PROJECT, project.id()).orElseThrow())
                    .isEqualTo(projectBefore);
            assertThat(publishing.find(
                            AggregateType.PROJECT_CATALOG,
                            PublicationService.PROJECT_CATALOG_ID)
                    .orElseThrow()).isEqualTo(catalogBefore);
            assertThat(revisionCount(AggregateType.PROJECT, project.id()))
                    .isEqualTo(projectRevisionsBefore);
            assertThat(revisionCount(
                            AggregateType.PROJECT_CATALOG,
                            PublicationService.PROJECT_CATALOG_ID))
                    .isEqualTo(catalogRevisionsBefore);
            assertThat(referenceCount()).isEqualTo(referencesBefore);
            assertThat(jdbc.sql("""
                            select publication_dirty
                            from portfolio.project
                            where id=:projectId
                            """)
                    .param("projectId", project.id())
                    .query(Boolean.class)
                    .single()).isTrue();
            assertThat(publishing.redirectTarget(retainedOldSlug)).isEmpty();
            assertThat(auditCount(project.id())).isEqualTo(auditBefore);
        } finally {
            cleanupUnpublishedFixture(project.id(), coverAssetId);
        }
    }

    @Test
    void migratorTriggerAndRuntimePrivilegeAreIndependentImmutabilityLayers()
            throws Exception {
        UUID revisionId = UUID.randomUUID();
        try (Connection connection = migratorDataSource().getConnection()) {
            connection.setAutoCommit(false);
            JdbcTemplate migrator = new JdbcTemplate(
                    new SingleConnectionDataSource(connection, true));
            migrator.update("""
                    insert into portfolio.content_revision(
                        id, aggregate_type, aggregate_id, version,
                        snapshot_schema_version, snapshot, checksum, published_by)
                    values (?, 'PROJECT', ?, 1, 1, '{}'::jsonb, repeat('a', 64), ?)
                    """, revisionId, UUID.randomUUID(), PublishingTestFixture.ADMIN_ID);

            assertThatThrownBy(() -> migrator.update(
                            "update portfolio.content_revision "
                                    + "set checksum=repeat('b', 64) where id=?",
                            revisionId))
                    .hasMessageContaining("published revisions are immutable");
            connection.rollback();
        }

        assertThat(jdbc.sql("""
                        select pg_catalog.has_table_privilege(
                            current_user, 'portfolio.content_revision', 'UPDATE')
                        """)
                .query(Boolean.class)
                .single()).isFalse();
        assertThatThrownBy(() -> jdbc.sql("""
                        update portfolio.content_revision
                        set checksum=repeat('b', 64)
                        where id=:revisionId
                        """)
                .param("revisionId", revisionId)
                .update())
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    private List<ReferenceRow> references(UUID revisionId) {
        return jdbc.sql("""
                        select asset_id, variant_name, usage
                        from portfolio.revision_media_reference
                        where revision_id=:revisionId
                        order by asset_id, variant_name, usage
                        """)
                .param("revisionId", revisionId)
                .query((row, number) -> new ReferenceRow(
                        row.getObject("asset_id", UUID.class),
                        row.getString("variant_name"),
                        row.getString("usage")))
                .list();
    }

    private List<ReferenceRow> expectedCatalogReferences(String catalogJson) {
        ProjectCatalogSnapshotV1 snapshot =
                codec.decode(catalogJson, ProjectCatalogSnapshotV1.class);
        TreeSet<ReferenceRow> expected = new TreeSet<>(Comparator
                .comparing((ReferenceRow reference) -> reference.assetId().toString())
                .thenComparing(ReferenceRow::variantName)
                .thenComparing(ReferenceRow::usage));
        snapshot.projects().forEach(card -> card.cover().variants().forEach(variant ->
                expected.add(new ReferenceRow(
                        card.cover().assetId(), variant.name(), "CATALOG_COVER"))));
        return List.copyOf(expected);
    }

    private void cleanupUnpublishedFixture(UUID projectId, UUID coverAssetId) {
        JdbcClient owner = migratorJdbc();
        long revisions = owner.sql("""
                        select count(*)
                        from portfolio.content_revision
                        where aggregate_type='PROJECT' and aggregate_id=:projectId
                        """)
                .param("projectId", projectId)
                .query(Long.class)
                .single();
        assertThat(revisions)
                .as("rollback fixture must not own immutable project history")
                .isZero();
        owner.sql("""
                        delete from portfolio.publication
                        where aggregate_type='PROJECT' and aggregate_id=:projectId
                        """)
                .param("projectId", projectId)
                .update();
        owner.sql("delete from portfolio.project where id=:projectId")
                .param("projectId", projectId)
                .update();
        owner.sql("delete from portfolio.media_translation where asset_id=:assetId")
                .param("assetId", coverAssetId)
                .update();
        owner.sql("delete from portfolio.media_variant where asset_id=:assetId")
                .param("assetId", coverAssetId)
                .update();
        owner.sql("delete from portfolio.media_asset where id=:assetId")
                .param("assetId", coverAssetId)
                .update();
        assertThat(owner.sql("""
                        select count(*)
                        from portfolio.publication
                        where aggregate_type='PROJECT' and aggregate_id=:projectId
                        """)
                .param("projectId", projectId)
                .query(Long.class)
                .single()).isZero();
        assertThat(owner.sql("select count(*) from portfolio.project where id=:projectId")
                .param("projectId", projectId)
                .query(Long.class)
                .single()).isZero();
        assertThat(owner.sql("select count(*) from portfolio.media_asset where id=:assetId")
                .param("assetId", coverAssetId)
                .query(Long.class)
                .single()).isZero();
    }

    private long revisionCount(AggregateType type, UUID aggregateId) {
        return jdbc.sql("""
                        select count(*)
                        from portfolio.content_revision
                        where aggregate_type=:type and aggregate_id=:aggregateId
                        """)
                .param("type", type.name())
                .param("aggregateId", aggregateId)
                .query(Long.class)
                .single();
    }

    private long referenceCount() {
        return jdbc.sql("select count(*) from portfolio.revision_media_reference")
                .query(Long.class)
                .single();
    }

    private long auditCount(UUID projectId) {
        return jdbc.sql("""
                        select count(*)
                        from portfolio.audit_log
                        where target_type='PROJECT' and target_id=:targetId
                        """)
                .param("targetId", projectId.toString())
                .query(Long.class)
                .single();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedAdminConfiguration {
        @Bean
        @Primary
        CurrentAdminProvider publishingIntegrationCurrentAdmin() {
            return () -> PublishingTestFixture.ADMIN_ID;
        }
    }

    private record ReferenceRow(UUID assetId, String variantName, String usage) { }

    private record AuditRow(
            String action,
            String targetType,
            String targetId,
            String outcome,
            String revisionId) { }
}
