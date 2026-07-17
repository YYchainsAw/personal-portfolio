package xyz.yychainsaw.portfolio.publishing.application;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.yychainsaw.portfolio.audit.AuditService;
import xyz.yychainsaw.portfolio.auth.CurrentAdminProvider;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.content.api.ProjectWorkspaceDto;
import xyz.yychainsaw.portfolio.content.api.SiteWorkspaceDto;
import xyz.yychainsaw.portfolio.content.persistence.SiteWorkspaceRepository;
import xyz.yychainsaw.portfolio.content.persistence.support.ContentPersistenceFixtures;
import xyz.yychainsaw.portfolio.publishing.api.PreviewTokenRequest;
import xyz.yychainsaw.portfolio.publishing.api.PublicationResult;
import xyz.yychainsaw.portfolio.publishing.api.PublishProjectCommand;
import xyz.yychainsaw.portfolio.publishing.application.PreviewTokenService.PreviewClaims;
import xyz.yychainsaw.portfolio.publishing.application.PublishingMediaLockCoordinator.LockedMediaPlan;
import xyz.yychainsaw.portfolio.publishing.persistence.PublishingRepository;
import xyz.yychainsaw.portfolio.publishing.persistence.PublishingRepository.PublicationRow;
import xyz.yychainsaw.portfolio.publishing.persistence.PublishingRepository.RevisionRow;
import xyz.yychainsaw.portfolio.publishing.snapshot.AggregateType;
import xyz.yychainsaw.portfolio.publishing.snapshot.SiteSnapshotMapper;
import xyz.yychainsaw.portfolio.publishing.snapshot.SnapshotCodec;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.LocaleV1;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.ProjectSnapshotV1;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.SiteSnapshotV1;
import xyz.yychainsaw.portfolio.publishing.support.PublishingTestFixture;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
@ActiveProfiles("test")
@Isolated
@Import({
    PublishingTestFixture.class,
    RestorePreviewIntegrationTest.FixedAdminConfiguration.class
})
class RestorePreviewIntegrationTest extends PostgresIntegrationTestBase {
    @Autowired PublicationService publication;
    @Autowired RestoreService restore;
    @Autowired PreviewTokenService tokens;
    @Autowired PreviewService preview;
    @Autowired PublishingRepository publishing;
    @Autowired SiteWorkspaceRepository sites;
    @Autowired SiteSnapshotMapper siteSnapshots;
    @Autowired SnapshotCodec snapshotCodec;
    @Autowired PublishingTestFixture fixture;
    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate transactions;

    @MockitoSpyBean AuditService audit;
    @MockitoSpyBean PublishingMediaLockCoordinator mediaLocks;

    @BeforeEach
    void ensureRealAdminRow() {
        fixture.ensureAdmin();
    }

    @Test
    void restoringOldProjectRevisionChangesOnlyTheDraftAndAddsOneAudit() {
        transactions.executeWithoutResult(status -> {
            PublishedProjectState state = publishProjectTwiceThenCreateDraft();
            PublishingFootprint before = publishingFootprint(state.projectId());
            long auditBefore = auditCount(state.projectId());
            long allAuditBefore = allAuditCount();

            assertThat(before.projectPointer()).get().satisfies(pointer -> {
                assertThat(pointer.currentRevisionId())
                        .isEqualTo(state.secondPublication().revisionId());
                assertThat(pointer.currentSlug()).isEqualTo(state.secondDraft().slug());
            });
            assertThat(before.catalogPointer().currentRevisionId())
                    .isEqualTo(state.secondPublication().catalogRevisionId());
            assertThat(before.redirectCount()).isEqualTo(1L);

            restore.restore(state.firstPublication().revisionId(), state.draft().version());

            ProjectWorkspaceDto restored = fixture.project(state.projectId());
            assertThat(restored.version()).isEqualTo(state.draft().version() + 1L);
            assertThat(restored.publicationDirty()).isTrue();
            assertThat(restored.slug()).isEqualTo(state.firstDraft().slug());
            assertThat(restored.translations().get(LocaleCode.EN).title())
                    .isEqualTo(state.firstDraft().translations()
                            .get(LocaleCode.EN).title())
                    .isNotEqualTo(state.draft().translations()
                            .get(LocaleCode.EN).title());

            assertThat(publishingFootprint(state.projectId())).isEqualTo(before);
            assertThat(auditCount(state.projectId())).isEqualTo(auditBefore + 1L);
            assertThat(allAuditCount()).isEqualTo(allAuditBefore + 1L);
            assertThat(restoreAudits(state.projectId())).containsExactly(new RestoreAuditRow(
                    "REVISION_RESTORED_TO_DRAFT",
                    "PROJECT",
                    state.projectId().toString(),
                    "SUCCESS",
                    state.firstPublication().revisionId().toString()));

            status.setRollbackOnly();
        });
    }

    @Test
    void realSitePreviewAndRestoreKeepThePublishedSiteHistoryImmutable() {
        transactions.executeWithoutResult(status -> {
            long rootVersion = jdbc.sql("""
                            select version from portfolio.site_profile
                            where id=:siteId
                            """)
                    .param("siteId", SiteWorkspaceDto.SITE_ID)
                    .query(Long.class)
                    .single();
            SiteWorkspaceDto seed =
                    ContentPersistenceFixtures.siteWithoutMedia(rootVersion);
            sites.replace(seed, rootVersion);
            SiteWorkspaceDto historical = sites.require();

            var encoded = snapshotCodec.encode(siteSnapshots.toSnapshot(historical));
            long revisionVersion = jdbc.sql("""
                            select coalesce(max(version), 0) + 1
                            from portfolio.content_revision
                            where aggregate_type='SITE' and aggregate_id=:siteId
                            """)
                    .param("siteId", SiteWorkspaceDto.SITE_ID)
                    .query(Long.class)
                    .single();
            UUID revisionId = UUID.randomUUID();
            publishing.insertRevision(new RevisionRow(
                    revisionId,
                    AggregateType.SITE,
                    SiteWorkspaceDto.SITE_ID,
                    revisionVersion,
                    encoded.schemaVersion(),
                    encoded.json(),
                    encoded.sha256(),
                    PublishingTestFixture.ADMIN_ID,
                    Instant.parse("2026-07-17T14:00:00Z")));
            PublicationRow pointer = publishing.lock(
                    AggregateType.SITE, SiteWorkspaceDto.SITE_ID);
            assertThat(publishing.casPublish(
                            AggregateType.SITE,
                            SiteWorkspaceDto.SITE_ID,
                            pointer.version(),
                            revisionId,
                            null,
                            Instant.parse("2026-07-17T14:00:00Z")))
                    .isTrue();

            DatabaseWriteTotals previewTotals =
                    databaseWriteTotals(SiteWorkspaceDto.SITE_ID);
            var issued = tokens.issue(new PreviewTokenRequest(
                    AggregateType.SITE,
                    SiteWorkspaceDto.SITE_ID,
                    historical.version()), PublishingTestFixture.ADMIN_ID);
            Object previewResult = preview.preview(tokens.verify(
                    issued.token(), PublishingTestFixture.ADMIN_ID));
            assertThat(previewResult).isInstanceOf(SiteSnapshotV1.class);
            SiteSnapshotV1 previewSnapshot = (SiteSnapshotV1) previewResult;
            assertThat(previewSnapshot.schemaVersion()).isEqualTo(1);
            assertThat(previewSnapshot.siteId()).isEqualTo(SiteWorkspaceDto.SITE_ID);
            assertThat(previewSnapshot.content().identity())
                    .containsOnlyKeys(LocaleV1.ZH_CN, LocaleV1.EN);
            assertThat(databaseWriteTotals(SiteWorkspaceDto.SITE_ID))
                    .isEqualTo(previewTotals);

            sites.replace(
                    ContentPersistenceFixtures.withMonogram(historical, "NEW"),
                    historical.version());
            SiteWorkspaceDto current = sites.require();
            PublicationRow pointerBeforeRestore = publishing
                    .find(AggregateType.SITE, SiteWorkspaceDto.SITE_ID)
                    .orElseThrow();
            List<RevisionRow> historyBeforeRestore = List.copyOf(publishing.history(
                    AggregateType.SITE, SiteWorkspaceDto.SITE_ID));
            long revisionsBeforeRestore = revisionCount();
            long referencesBeforeRestore = referenceCount();
            long publicationsBeforeRestore = publicationCount();
            long auditsBeforeRestore = siteAuditCount();

            restore.restore(revisionId, current.version());

            SiteWorkspaceDto restored = sites.require();
            assertThat(restored.version()).isEqualTo(current.version() + 1L);
            assertThat(restored.monogram()).isEqualTo(historical.monogram());
            assertThat(publishing.find(AggregateType.SITE, SiteWorkspaceDto.SITE_ID))
                    .contains(pointerBeforeRestore);
            assertThat(publishing.history(AggregateType.SITE, SiteWorkspaceDto.SITE_ID))
                    .containsExactlyElementsOf(historyBeforeRestore);
            assertThat(revisionCount()).isEqualTo(revisionsBeforeRestore);
            assertThat(referenceCount()).isEqualTo(referencesBeforeRestore);
            assertThat(publicationCount()).isEqualTo(publicationsBeforeRestore);
            assertThat(siteAuditCount()).isEqualTo(auditsBeforeRestore + 1L);

            status.setRollbackOnly();
        });
    }

    @Test
    void auditFailureRollsBackRestoredDraftAndAuditTogether() {
        PublishedProjectState state = publishProjectTwiceThenCreateDraft();
        WriteState before = writeState(state.projectId());

        doAnswer(invocation -> {
                    invocation.callRealMethod();
                    throw new IllegalStateException("forced restore audit failure");
                })
                .when(audit)
                .record(argThat(command -> command != null
                        && "REVISION_RESTORED_TO_DRAFT".equals(command.action())
                        && state.projectId().toString().equals(command.targetId())));

        assertThatThrownBy(() -> restore.restore(
                        state.firstPublication().revisionId(), state.draft().version()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("forced restore audit failure");

        assertThat(writeState(state.projectId())).isEqualTo(before);
    }

    @Test
    void realTokenPreviewsUnpublishedBilingualProjectWithoutHistoryWrites() {
        ProjectWorkspaceDto project = fixture.persistReadyProject();
        assertThat(publishing.find(AggregateType.PROJECT, project.id())).isEmpty();
        ProjectWorkspaceDto workspaceBefore = fixture.project(project.id());
        DatabaseWriteTotals totalsBefore = databaseWriteTotals(project.id());
        assertThat(AopUtils.isAopProxy(preview)).isTrue();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();

        var issued = tokens.issue(new PreviewTokenRequest(
                AggregateType.PROJECT,
                project.id(),
                project.version()), PublishingTestFixture.ADMIN_ID);
        PreviewClaims claims = tokens.verify(
                issued.token(), PublishingTestFixture.ADMIN_ID);
        Object result = preview.preview(claims);

        assertThat(result).isInstanceOf(ProjectSnapshotV1.class);
        ProjectSnapshotV1 snapshot = (ProjectSnapshotV1) result;
        assertThat(snapshot.schemaVersion()).isEqualTo(1);
        assertThat(snapshot.projectId()).isEqualTo(project.id());
        assertThat(snapshot.translations())
                .containsOnlyKeys(LocaleV1.ZH_CN, LocaleV1.EN);
        assertThat(snapshot.translations().get(LocaleV1.ZH_CN).title())
                .isNotBlank();
        assertThat(snapshot.translations().get(LocaleV1.EN).title())
                .isEqualTo("Complete project");
        assertThat(snapshot.media()).singleElement().satisfies(media ->
                assertThat(media.copy())
                        .containsOnlyKeys(LocaleV1.ZH_CN, LocaleV1.EN));
        assertThat(fixture.project(project.id())).isEqualTo(workspaceBefore);
        assertThat(databaseWriteTotals(project.id())).isEqualTo(totalsBefore);
        assertThat(publishing.find(AggregateType.PROJECT, project.id())).isEmpty();

        ProjectWorkspaceDto edited = fixture.editProjectEnglishTitle(
                project.id(), "Preview became stale");
        DatabaseWriteTotals totalsAfterEdit = databaseWriteTotals(project.id());
        assertThat(edited.version()).isEqualTo(project.version() + 1L);

        assertThatThrownBy(() -> preview.preview(claims))
                .isInstanceOfSatisfying(DomainException.class, error -> {
                    assertThat(error.code()).isEqualTo("CONTENT_VERSION_CONFLICT");
                    assertThat(error.status().value()).isEqualTo(409);
                });
        assertThat(fixture.project(project.id())).isEqualTo(edited);
        assertThat(databaseWriteTotals(project.id())).isEqualTo(totalsAfterEdit);
        assertThat(publishing.find(AggregateType.PROJECT, project.id())).isEmpty();
    }

    @Test
    void catalogRestoreIsRejectedWithoutAnyWrite() {
        transactions.executeWithoutResult(status -> {
            ProjectWorkspaceDto project = fixture.persistReadyProject();
            PublicationResult result = publish(project, 0L);
            WriteState before = writeState(project.id());

            assertThatThrownBy(() -> restore.restore(
                            result.catalogRevisionId(),
                            fixture.project(project.id()).version()))
                    .isInstanceOfSatisfying(DomainException.class, error -> {
                        assertThat(error.code()).isEqualTo("CATALOG_RESTORE_NOT_ALLOWED");
                        assertThat(error.status().value()).isEqualTo(422);
                    });

            assertThat(writeState(project.id())).isEqualTo(before);
            status.setRollbackOnly();
        });
    }

    @Test
    void unknownSnapshotSchemaIsRejectedWithoutAnyWrite() {
        transactions.executeWithoutResult(status -> {
            ProjectWorkspaceDto project = fixture.persistReadyProject();
            UUID revisionId = insertRevision(
                    project.id(), 1, 999, "{}", 'c');
            WriteState before = writeState(project.id());

            assertThatThrownBy(() -> restore.restore(revisionId, project.version()))
                    .isInstanceOfSatisfying(DomainException.class, error -> {
                        assertThat(error.code()).isEqualTo("SNAPSHOT_SCHEMA_UNSUPPORTED");
                        assertThat(error.status().value()).isEqualTo(422);
                    });

            assertThat(writeState(project.id())).isEqualTo(before);
            status.setRollbackOnly();
        });
    }

    @Test
    void mismatchedRevisionAndSnapshotOwnersAreRejectedWithoutAnyWrite() {
        transactions.executeWithoutResult(status -> {
            ProjectWorkspaceDto target = fixture.persistReadyProject();
            ProjectWorkspaceDto source = fixture.persistReadyProject();
            PublicationResult sourcePublication = publish(source, 0L);
            RevisionRow sourceRevision = publishing.requireRevision(
                    sourcePublication.revisionId());
            UUID corruptRevisionId = insertRevision(
                    target.id(),
                    1,
                    sourceRevision.schemaVersion(),
                    sourceRevision.json(),
                    'd');
            WriteState before = writeState(target.id());

            assertThatThrownBy(() -> restore.restore(
                            corruptRevisionId, target.version()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("PROJECT revision ownership is inconsistent");

            assertThat(writeState(target.id())).isEqualTo(before);
            status.setRollbackOnly();
        });
    }

    @Test
    void concurrentWorkspaceEditAfterMediaLockMakesRestoreConflictAndRollBack()
            throws Exception {
        PublishedProjectState state = publishProjectTwiceThenCreateDraft();
        PublishingFootprint footprintBefore = publishingFootprint(state.projectId());
        long auditBefore = allAuditCount();
        CountDownLatch mediaLocked = new CountDownLatch(1);
        CountDownLatch continueRestore = new CountDownLatch(1);

        doAnswer(invocation -> {
                    LockedMediaPlan plan = (LockedMediaPlan) invocation.callRealMethod();
                    mediaLocked.countDown();
                    if (!continueRestore.await(10, SECONDS)) {
                        throw new AssertionError("restore was not released after media locking");
                    }
                    return plan;
                })
                .when(mediaLocks)
                .lockRestoreMedia(anySet(), anyList());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Void> restoreFuture = executor.submit(() -> {
            restore.restore(
                    state.firstPublication().revisionId(), state.draft().version());
            return null;
        });
        try {
            assertThat(mediaLocked.await(10, SECONDS)).isTrue();
            ProjectWorkspaceDto raced = fixture.editProjectEnglishTitle(
                    state.projectId(), "Concurrent editor won");
            continueRestore.countDown();

            assertThatThrownBy(() -> restoreFuture.get(10, SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOfSatisfying(DomainException.class, error -> {
                        assertThat(error.code()).isEqualTo("CONTENT_VERSION_CONFLICT");
                        assertThat(error.status().value()).isEqualTo(409);
                    });

            assertThat(fixture.project(state.projectId())).isEqualTo(raced);
            assertThat(publishingFootprint(state.projectId()))
                    .isEqualTo(footprintBefore);
            assertThat(allAuditCount()).isEqualTo(auditBefore);
        } finally {
            continueRestore.countDown();
            restoreFuture.cancel(true);
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, SECONDS)).isTrue();
        }
    }

    private PublishedProjectState publishProjectTwiceThenCreateDraft() {
        ProjectWorkspaceDto firstDraft = fixture.persistReadyProject();
        PublicationResult firstPublication = publish(firstDraft, 0L);

        ProjectWorkspaceDto secondTitleDraft = fixture.editProjectEnglishTitle(
                firstDraft.id(), "Second published title");
        ProjectWorkspaceDto secondDraft = fixture.editProjectSlug(
                firstDraft.id(), firstDraft.slug() + "-published-2");
        assertThat(secondDraft.version())
                .isEqualTo(secondTitleDraft.version() + 1L);
        PublicationRow projectPointer = publishing
                .find(AggregateType.PROJECT, firstDraft.id())
                .orElseThrow();
        PublicationResult secondPublication = publish(
                secondDraft, projectPointer.version());

        ProjectWorkspaceDto thirdTitleDraft = fixture.editProjectEnglishTitle(
                firstDraft.id(), "Unpublished third title");
        ProjectWorkspaceDto draft = fixture.editProjectSlug(
                firstDraft.id(), secondDraft.slug() + "-draft");
        assertThat(draft.version())
                .isEqualTo(thirdTitleDraft.version() + 1L);
        assertThat(draft.publicationDirty()).isTrue();
        return new PublishedProjectState(
                firstDraft.id(),
                firstDraft,
                firstPublication,
                secondDraft,
                secondPublication,
                draft);
    }

    private PublicationResult publish(
            ProjectWorkspaceDto project, long expectedProjectPublicationVersion) {
        PublicationRow catalog = publishing
                .find(
                        AggregateType.PROJECT_CATALOG,
                        PublicationService.PROJECT_CATALOG_ID)
                .orElseThrow();
        return publication.publishProject(new PublishProjectCommand(
                project.id(),
                project.version(),
                expectedProjectPublicationVersion,
                catalog.version()));
    }

    private UUID insertRevision(
            UUID aggregateId,
            long version,
            int schemaVersion,
            String json,
            char checksumCharacter) {
        UUID revisionId = UUID.randomUUID();
        int inserted = jdbc.sql("""
                        insert into portfolio.content_revision(
                            id, aggregate_type, aggregate_id, version,
                            snapshot_schema_version, snapshot, checksum, published_by)
                        values (
                            :id, 'PROJECT', :aggregateId, :version,
                            :schemaVersion, cast(:snapshot as jsonb),
                            repeat(:checksumCharacter, 64), :publishedBy)
                        """)
                .param("id", revisionId)
                .param("aggregateId", aggregateId)
                .param("version", version)
                .param("schemaVersion", schemaVersion)
                .param("snapshot", json)
                .param("checksumCharacter", Character.toString(checksumCharacter))
                .param("publishedBy", PublishingTestFixture.ADMIN_ID)
                .update();
        assertThat(inserted).isEqualTo(1);
        return revisionId;
    }

    private PublishingFootprint publishingFootprint(UUID projectId) {
        return new PublishingFootprint(
                publishing.find(AggregateType.PROJECT, projectId),
                publishing.find(
                                AggregateType.PROJECT_CATALOG,
                                PublicationService.PROJECT_CATALOG_ID)
                        .orElseThrow(),
                List.copyOf(publishing.history(AggregateType.PROJECT, projectId)),
                List.copyOf(publishing.history(
                        AggregateType.PROJECT_CATALOG,
                        PublicationService.PROJECT_CATALOG_ID)),
                revisionCount(),
                referenceCount(),
                publicationCount(),
                redirectCount(projectId));
    }

    private WriteState writeState(UUID projectId) {
        return new WriteState(
                fixture.project(projectId),
                publishingFootprint(projectId),
                projectCount(),
                auditCount(projectId),
                allAuditCount());
    }

    private DatabaseWriteTotals databaseWriteTotals(UUID projectId) {
        return new DatabaseWriteTotals(
                projectCount(),
                publicationCount(),
                revisionCount(),
                referenceCount(),
                redirectCount(projectId),
                auditCount(projectId),
                allAuditCount());
    }

    private List<RestoreAuditRow> restoreAudits(UUID projectId) {
        return jdbc.sql("""
                        select action, target_type, target_id, outcome,
                               metadata ->> 'revisionId' revision_id
                        from portfolio.audit_log
                        where actor_admin_id=:actorId
                          and target_type='PROJECT'
                          and target_id=:targetId
                          and action='REVISION_RESTORED_TO_DRAFT'
                        order by created_at, id
                        """)
                .param("actorId", PublishingTestFixture.ADMIN_ID)
                .param("targetId", projectId.toString())
                .query((row, number) -> new RestoreAuditRow(
                        row.getString("action"),
                        row.getString("target_type"),
                        row.getString("target_id"),
                        row.getString("outcome"),
                        row.getString("revision_id")))
                .list();
    }

    private long projectCount() {
        return jdbc.sql("select count(*) from portfolio.project")
                .query(Long.class)
                .single();
    }

    private long publicationCount() {
        return jdbc.sql("select count(*) from portfolio.publication")
                .query(Long.class)
                .single();
    }

    private long revisionCount() {
        return jdbc.sql("select count(*) from portfolio.content_revision")
                .query(Long.class)
                .single();
    }

    private long referenceCount() {
        return jdbc.sql("select count(*) from portfolio.revision_media_reference")
                .query(Long.class)
                .single();
    }

    private long redirectCount(UUID projectId) {
        return jdbc.sql("""
                        select count(*)
                        from portfolio.slug_redirect
                        where project_id=:projectId
                        """)
                .param("projectId", projectId)
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

    private long siteAuditCount() {
        return jdbc.sql("""
                        select count(*)
                        from portfolio.audit_log
                        where target_type='SITE' and target_id=:targetId
                        """)
                .param("targetId", SiteWorkspaceDto.SITE_ID.toString())
                .query(Long.class)
                .single();
    }

    private long allAuditCount() {
        return jdbc.sql("select count(*) from portfolio.audit_log")
                .query(Long.class)
                .single();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedAdminConfiguration {
        @Bean
        @Primary
        CurrentAdminProvider restorePreviewIntegrationCurrentAdmin() {
            return () -> PublishingTestFixture.ADMIN_ID;
        }
    }

    private record PublishedProjectState(
            UUID projectId,
            ProjectWorkspaceDto firstDraft,
            PublicationResult firstPublication,
            ProjectWorkspaceDto secondDraft,
            PublicationResult secondPublication,
            ProjectWorkspaceDto draft) { }

    private record PublishingFootprint(
            Optional<PublicationRow> projectPointer,
            PublicationRow catalogPointer,
            List<RevisionRow> projectHistory,
            List<RevisionRow> catalogHistory,
            long revisionCount,
            long referenceCount,
            long publicationCount,
            long redirectCount) { }

    private record WriteState(
            ProjectWorkspaceDto workspace,
            PublishingFootprint publishing,
            long projectCount,
            long projectAuditCount,
            long allAuditCount) { }

    private record DatabaseWriteTotals(
            long projectCount,
            long publicationCount,
            long revisionCount,
            long referenceCount,
            long redirectCount,
            long projectAuditCount,
            long allAuditCount) { }

    private record RestoreAuditRow(
            String action,
            String targetType,
            String targetId,
            String outcome,
            String revisionId) { }
}
