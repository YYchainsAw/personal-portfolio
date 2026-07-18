package xyz.yychainsaw.portfolio.publishing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionOperations;
import xyz.yychainsaw.portfolio.audit.AuditCommand;
import xyz.yychainsaw.portfolio.audit.AuditOutcome;
import xyz.yychainsaw.portfolio.audit.AuditService;
import xyz.yychainsaw.portfolio.auth.CurrentAdminProvider;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.content.api.ProjectWorkspaceDto;
import xyz.yychainsaw.portfolio.content.api.SiteWorkspaceDto;
import xyz.yychainsaw.portfolio.content.application.WorkspaceValidator;
import xyz.yychainsaw.portfolio.content.persistence.ProjectWorkspaceRepository;
import xyz.yychainsaw.portfolio.content.persistence.SiteWorkspaceRepository;
import xyz.yychainsaw.portfolio.content.support.WorkspaceFixtures;
import xyz.yychainsaw.portfolio.media.application.MediaQueryAccessGuard;
import xyz.yychainsaw.portfolio.publishing.application.PublishingMediaLockCoordinator.LockedMediaPlan;
import xyz.yychainsaw.portfolio.publishing.persistence.PublishingRepository;
import xyz.yychainsaw.portfolio.publishing.persistence.PublishingRepository.RevisionRow;
import xyz.yychainsaw.portfolio.publishing.snapshot.AggregateType;
import xyz.yychainsaw.portfolio.publishing.snapshot.SnapshotMapperRegistry;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.ProjectSnapshotV1;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.PublishedMediaV1;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.SiteSnapshotV1;

class RestoreServiceTest {
    private static final UUID ACTOR =
            UUID.fromString("90000000-0000-4000-8000-000000000001");
    private static final UUID REVISION_ID =
            UUID.fromString("91000000-0000-4000-8000-000000000001");
    private static final UUID PROJECT_ID =
            UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_PROJECT_ID =
            UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final UUID ASSET_LOW =
            UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID ASSET_HIGH =
            UUID.fromString("f0000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-07-17T08:00:00Z");
    private static final String JSON = "{\"schemaVersion\":1}";

    private final CurrentAdminProvider currentAdmin = mock(CurrentAdminProvider.class);
    private final SiteWorkspaceRepository sites = mock(SiteWorkspaceRepository.class);
    private final ProjectWorkspaceRepository projects = mock(ProjectWorkspaceRepository.class);
    private final PublishingRepository publishing = mock(PublishingRepository.class);
    private final SnapshotMapperRegistry snapshots = mock(SnapshotMapperRegistry.class);
    private final WorkspaceValidator validator = mock(WorkspaceValidator.class);
    private final PublishingMediaLockCoordinator mediaLocks =
            mock(PublishingMediaLockCoordinator.class);
    private final AuditService audit = mock(AuditService.class);
    private final TransactionOperations transactions = mock(TransactionOperations.class);
    private RestoreService service;

    @BeforeEach
    void setUp() {
        when(currentAdmin.requireAdminId()).thenReturn(ACTOR);
        doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactions).executeWithoutResult(any());
        service = new RestoreService(
                currentAdmin,
                sites,
                projects,
                publishing,
                snapshots,
                validator,
                mediaLocks,
                audit,
                transactions,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void restoresProjectInsideOneTransactionAfterMediaLockWithoutPublicationMutation() {
        ProjectWorkspaceDto observed = project(5, ASSET_LOW, ASSET_HIGH);
        ProjectWorkspaceDto locked = project(5, ASSET_LOW, ASSET_HIGH);
        ProjectWorkspaceDto candidate = project(5, ASSET_LOW, ASSET_HIGH);
        ProjectWorkspaceDto restored = project(5, ASSET_LOW, ASSET_HIGH);
        ProjectSnapshotV1 snapshot = projectSnapshot(
                PROJECT_ID,
                publishedMedia(ASSET_LOW, "alpha", "zeta"),
                publishedMedia(ASSET_HIGH, "webp"));
        RevisionRow revision = revision(AggregateType.PROJECT, PROJECT_ID, 1);
        LockedMediaPlan plan = plan(
                Set.of(ASSET_LOW, ASSET_HIGH),
                Set.of(
                        variant(ASSET_LOW, "alpha"),
                        variant(ASSET_LOW, "zeta"),
                        variant(ASSET_HIGH, "webp")));

        when(publishing.requireRevision(REVISION_ID)).thenReturn(revision);
        when(snapshots.readProject(1, JSON)).thenReturn(snapshot);
        when(projects.require(PROJECT_ID)).thenReturn(observed);
        when(snapshots.restoreProject(1, JSON, 5)).thenReturn(candidate, restored);
        when(mediaLocks.lockRestoreMedia(
                        Set.of(ASSET_LOW, ASSET_HIGH), snapshot.media()))
                .thenReturn(plan);
        when(projects.requireForUpdate(PROJECT_ID)).thenReturn(locked);

        service.restore(REVISION_ID, 5);

        verify(projects).replace(restored, 5, NOW);
        assertThat(restored.id()).isEqualTo(observed.id());
        assertThat(restored.externalKey()).isEqualTo(observed.externalKey());
        assertThat(restored.publicationDirty()).isTrue();
        assertThat(restored.version()).isEqualTo(5);
        verify(snapshots, times(2)).restoreProject(1, JSON, 5);
        verify(validator, times(2)).validateProject(candidate);
        assertSuccessfulAudit(AggregateType.PROJECT, PROJECT_ID);

        InOrder order = inOrder(
                currentAdmin,
                transactions,
                publishing,
                snapshots,
                projects,
                validator,
                mediaLocks,
                audit);
        order.verify(currentAdmin).requireAdminId();
        order.verify(transactions).executeWithoutResult(any());
        order.verify(publishing).requireRevision(REVISION_ID);
        order.verify(snapshots).readProject(1, JSON);
        order.verify(projects).require(PROJECT_ID);
        order.verify(snapshots).restoreProject(1, JSON, 5);
        order.verify(validator).validateProject(candidate);
        order.verify(mediaLocks).lockRestoreMedia(
                Set.of(ASSET_LOW, ASSET_HIGH), snapshot.media());
        order.verify(projects).requireForUpdate(PROJECT_ID);
        order.verify(snapshots).restoreProject(1, JSON, 5);
        order.verify(validator).validateProject(restored);
        order.verify(projects).replace(restored, 5, NOW);
        order.verify(audit).record(any());
        assertOnlyRevisionReadFromPublishing();
    }

    @Test
    void restoresSiteAfterMediaLockAndUsesLockedWorkspaceForFinalDerivation() {
        SiteWorkspaceDto observed = site(5, ASSET_HIGH, ASSET_LOW);
        SiteWorkspaceDto locked = site(5, ASSET_HIGH, ASSET_LOW);
        SiteWorkspaceDto candidate = site(5, ASSET_HIGH, ASSET_LOW);
        SiteWorkspaceDto restored = site(5, ASSET_HIGH, ASSET_LOW);
        SiteSnapshotV1 snapshot = new SiteSnapshotV1(
                1,
                SiteWorkspaceDto.SITE_ID,
                null,
                List.of(
                        publishedMedia(ASSET_LOW, "document"),
                        publishedMedia(ASSET_HIGH, "hero")));
        LockedMediaPlan plan = plan(
                Set.of(ASSET_LOW, ASSET_HIGH),
                Set.of(
                        variant(ASSET_LOW, "document"),
                        variant(ASSET_HIGH, "hero")));

        when(publishing.requireRevision(REVISION_ID))
                .thenReturn(revision(AggregateType.SITE, SiteWorkspaceDto.SITE_ID, 1));
        when(snapshots.readSite(1, JSON)).thenReturn(snapshot);
        when(sites.require()).thenReturn(observed);
        when(snapshots.restoreSite(1, JSON, observed)).thenReturn(candidate);
        when(mediaLocks.lockRestoreMedia(
                        Set.of(ASSET_LOW, ASSET_HIGH), snapshot.media()))
                .thenReturn(plan);
        when(sites.requireForUpdate()).thenReturn(locked);
        when(snapshots.restoreSite(1, JSON, locked)).thenReturn(restored);

        service.restore(REVISION_ID, 5);

        verify(sites).replace(restored, 5, NOW);
        verify(validator).validateSite(candidate);
        verify(validator).validateSite(restored);
        assertSuccessfulAudit(AggregateType.SITE, SiteWorkspaceDto.SITE_ID);
        InOrder order = inOrder(mediaLocks, sites, snapshots, validator, audit);
        order.verify(mediaLocks).lockRestoreMedia(
                Set.of(ASSET_LOW, ASSET_HIGH), snapshot.media());
        order.verify(sites).requireForUpdate();
        order.verify(snapshots).restoreSite(1, JSON, locked);
        order.verify(validator).validateSite(restored);
        order.verify(sites).replace(restored, 5, NOW);
        order.verify(audit).record(any());
        assertOnlyRevisionReadFromPublishing();
    }

    @Test
    void mapsMissingRevisionToStableNotFoundBeforeAnyWorkspaceRead() {
        when(publishing.requireRevision(REVISION_ID))
                .thenThrow(new NoSuchElementException("database detail"));

        assertDomainFailure(
                () -> service.restore(REVISION_ID, 5),
                "REVISION_NOT_FOUND",
                HttpStatus.NOT_FOUND);

        verifyNoInteractions(sites, projects, snapshots, validator, mediaLocks, audit);
        assertOnlyRevisionReadFromPublishing();
    }

    @Test
    void rejectsCatalogRestoreBeforeDecodeOrWorkspaceAccess() {
        when(publishing.requireRevision(REVISION_ID)).thenReturn(revision(
                AggregateType.PROJECT_CATALOG,
                PublicationService.PROJECT_CATALOG_ID,
                1));

        assertDomainFailure(
                () -> service.restore(REVISION_ID, 5),
                "CATALOG_RESTORE_NOT_ALLOWED",
                HttpStatus.UNPROCESSABLE_ENTITY);

        verifyNoInteractions(sites, projects, snapshots, validator, mediaLocks, audit);
        assertOnlyRevisionReadFromPublishing();
    }

    @Test
    void propagatesUnknownSnapshotSchemaBeforeWorkspaceAccess() {
        DomainException unsupported = new DomainException(
                "SNAPSHOT_SCHEMA_UNSUPPORTED",
                HttpStatus.UNPROCESSABLE_ENTITY,
                Map.of("snapshotSchemaVersion", "99"));
        when(publishing.requireRevision(REVISION_ID))
                .thenReturn(revision(AggregateType.PROJECT, PROJECT_ID, 99));
        when(snapshots.readProject(99, JSON)).thenThrow(unsupported);

        assertThatThrownBy(() -> service.restore(REVISION_ID, 5)).isSameAs(unsupported);

        verifyNoInteractions(sites, projects, validator, mediaLocks, audit);
        assertOnlyRevisionReadFromPublishing();
    }

    @Test
    void rejectsProjectRevisionAndSnapshotOwnershipMismatchBeforeWorkspaceAccess() {
        ProjectSnapshotV1 foreign = projectSnapshot(OTHER_PROJECT_ID);
        when(publishing.requireRevision(REVISION_ID))
                .thenReturn(revision(AggregateType.PROJECT, PROJECT_ID, 1));
        when(snapshots.readProject(1, JSON)).thenReturn(foreign);

        assertThatThrownBy(() -> service.restore(REVISION_ID, 5))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(sites, projects, validator, mediaLocks, audit);
        assertOnlyRevisionReadFromPublishing();
    }

    @Test
    void rejectsSiteRevisionAggregateThatIsNotTheFixedSiteBeforeWorkspaceAccess() {
        SiteSnapshotV1 snapshot = new SiteSnapshotV1(
                1, SiteWorkspaceDto.SITE_ID, null, List.of());
        when(publishing.requireRevision(REVISION_ID))
                .thenReturn(revision(AggregateType.SITE, OTHER_PROJECT_ID, 1));
        when(snapshots.readSite(1, JSON)).thenReturn(snapshot);

        assertThatThrownBy(() -> service.restore(REVISION_ID, 5))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(sites, projects, validator, mediaLocks, audit);
        assertOnlyRevisionReadFromPublishing();
    }

    @Test
    void rejectsSiteSnapshotThatIsNotTheFixedSiteBeforeWorkspaceAccess() {
        SiteSnapshotV1 foreign = new SiteSnapshotV1(
                1, OTHER_PROJECT_ID, null, List.of());
        when(publishing.requireRevision(REVISION_ID))
                .thenReturn(revision(AggregateType.SITE, SiteWorkspaceDto.SITE_ID, 1));
        when(snapshots.readSite(1, JSON)).thenReturn(foreign);

        assertThatThrownBy(() -> service.restore(REVISION_ID, 5))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(sites, projects, validator, mediaLocks, audit);
        assertOnlyRevisionReadFromPublishing();
    }

    @Test
    void rejectsOptimisticallyStaleWorkspaceBeforeRestoreOrMediaLock() {
        ProjectSnapshotV1 snapshot = projectSnapshot(PROJECT_ID);
        when(publishing.requireRevision(REVISION_ID))
                .thenReturn(revision(AggregateType.PROJECT, PROJECT_ID, 1));
        when(snapshots.readProject(1, JSON)).thenReturn(snapshot);
        when(projects.require(PROJECT_ID)).thenReturn(project(6));

        assertDomainFailure(
                () -> service.restore(REVISION_ID, 5),
                "CONTENT_VERSION_CONFLICT",
                HttpStatus.CONFLICT);

        verify(snapshots, never()).restoreProject(anyInt(), any(), anyLong());
        verify(projects, never()).requireForUpdate(any());
        verify(projects, never()).replace(any(), anyLong(), any());
        verifyNoInteractions(validator, mediaLocks, audit, sites);
        assertOnlyRevisionReadFromPublishing();
    }

    @Test
    void rejectsLockedWorkspaceVersionChangeAfterMediaLocksWithoutWriting() {
        ProjectWorkspaceDto observed = project(5, ASSET_LOW);
        ProjectWorkspaceDto candidate = project(5, ASSET_LOW);
        ProjectSnapshotV1 snapshot = projectSnapshot(
                PROJECT_ID, publishedMedia(ASSET_LOW, "webp"));
        when(publishing.requireRevision(REVISION_ID))
                .thenReturn(revision(AggregateType.PROJECT, PROJECT_ID, 1));
        when(snapshots.readProject(1, JSON)).thenReturn(snapshot);
        when(projects.require(PROJECT_ID)).thenReturn(observed);
        when(snapshots.restoreProject(1, JSON, 5)).thenReturn(candidate);
        when(mediaLocks.lockRestoreMedia(Set.of(ASSET_LOW), snapshot.media()))
                .thenReturn(plan(Set.of(ASSET_LOW), Set.of(variant(ASSET_LOW, "webp"))));
        when(projects.requireForUpdate(PROJECT_ID)).thenReturn(project(6, ASSET_LOW));

        assertDomainFailure(
                () -> service.restore(REVISION_ID, 5),
                "CONTENT_VERSION_CONFLICT",
                HttpStatus.CONFLICT);

        InOrder order = inOrder(mediaLocks, projects);
        order.verify(mediaLocks).lockRestoreMedia(Set.of(ASSET_LOW), snapshot.media());
        order.verify(projects).requireForUpdate(PROJECT_ID);
        verify(projects, never()).replace(any(), anyLong(), any());
        verifyNoInteractions(audit, sites);
        assertOnlyRevisionReadFromPublishing();
    }

    @Test
    void rejectsLockedProjectOwnershipMismatchWithoutReplacingForeignWorkspace() {
        ProjectWorkspaceDto observed = project(5);
        ProjectWorkspaceDto candidate = project(5);
        ProjectWorkspaceDto foreign = projectWithIdentity(OTHER_PROJECT_ID, "foreign", 5);
        ProjectSnapshotV1 snapshot = projectSnapshot(PROJECT_ID);
        when(publishing.requireRevision(REVISION_ID))
                .thenReturn(revision(AggregateType.PROJECT, PROJECT_ID, 1));
        when(snapshots.readProject(1, JSON)).thenReturn(snapshot);
        when(projects.require(PROJECT_ID)).thenReturn(observed);
        when(snapshots.restoreProject(1, JSON, 5)).thenReturn(candidate);
        when(mediaLocks.lockRestoreMedia(Set.of(), snapshot.media()))
                .thenReturn(plan(Set.of(), Set.of()));
        when(projects.requireForUpdate(PROJECT_ID)).thenReturn(foreign);

        assertThatThrownBy(() -> service.restore(REVISION_ID, 5))
                .isInstanceOf(IllegalStateException.class);

        verify(projects, never()).replace(any(), anyLong(), any());
        verifyNoInteractions(audit, sites);
        assertOnlyRevisionReadFromPublishing();
    }

    @Test
    void rejectsFinalReferenceDriftWithoutTakingAdditionalMediaLocksOrWriting() {
        ProjectWorkspaceDto observed = project(5, ASSET_LOW);
        ProjectWorkspaceDto locked = project(5, ASSET_LOW);
        ProjectWorkspaceDto candidate = project(5, ASSET_LOW);
        ProjectWorkspaceDto drifted = project(5, ASSET_HIGH);
        ProjectSnapshotV1 snapshot = projectSnapshot(
                PROJECT_ID, publishedMedia(ASSET_LOW, "webp"));
        when(publishing.requireRevision(REVISION_ID))
                .thenReturn(revision(AggregateType.PROJECT, PROJECT_ID, 1));
        when(snapshots.readProject(1, JSON)).thenReturn(snapshot);
        when(projects.require(PROJECT_ID)).thenReturn(observed);
        when(snapshots.restoreProject(1, JSON, 5)).thenReturn(candidate, drifted);
        when(mediaLocks.lockRestoreMedia(Set.of(ASSET_LOW), snapshot.media()))
                .thenReturn(plan(Set.of(ASSET_LOW), Set.of(variant(ASSET_LOW, "webp"))));
        when(projects.requireForUpdate(PROJECT_ID)).thenReturn(locked);

        assertDomainFailure(
                () -> service.restore(REVISION_ID, 5),
                "CONTENT_VERSION_CONFLICT",
                HttpStatus.CONFLICT);

        verify(mediaLocks, times(1))
                .lockRestoreMedia(Set.of(ASSET_LOW), snapshot.media());
        verify(projects, never()).replace(any(), anyLong(), any());
        verifyNoInteractions(audit, sites);
        assertOnlyRevisionReadFromPublishing();
    }

    @Test
    void propagatesNormalizedMediaFailureBeforeContentLockAndWriting() {
        ProjectWorkspaceDto observed = project(5, ASSET_LOW);
        ProjectWorkspaceDto candidate = project(5, ASSET_LOW);
        ProjectSnapshotV1 snapshot = projectSnapshot(
                PROJECT_ID, publishedMedia(ASSET_LOW, "webp"));
        DomainException failure = new DomainException(
                "MEDIA_NOT_READY",
                HttpStatus.UNPROCESSABLE_ENTITY,
                Map.of("media[" + ASSET_LOW + "]", "READY media required"));
        when(publishing.requireRevision(REVISION_ID))
                .thenReturn(revision(AggregateType.PROJECT, PROJECT_ID, 1));
        when(snapshots.readProject(1, JSON)).thenReturn(snapshot);
        when(projects.require(PROJECT_ID)).thenReturn(observed);
        when(snapshots.restoreProject(1, JSON, 5)).thenReturn(candidate);
        when(mediaLocks.lockRestoreMedia(Set.of(ASSET_LOW), snapshot.media()))
                .thenThrow(failure);

        assertThatThrownBy(() -> service.restore(REVISION_ID, 5)).isSameAs(failure);

        verify(projects, never()).requireForUpdate(any());
        verify(projects, never()).replace(any(), anyLong(), any());
        verifyNoInteractions(audit, sites);
        assertOnlyRevisionReadFromPublishing();
    }

    private void assertSuccessfulAudit(AggregateType type, UUID aggregateId) {
        ArgumentCaptor<AuditCommand> captured = ArgumentCaptor.forClass(AuditCommand.class);
        verify(audit).record(captured.capture());
        assertThat(captured.getValue())
                .extracting(
                        AuditCommand::actorAdminId,
                        AuditCommand::action,
                        AuditCommand::targetType,
                        AuditCommand::targetId,
                        AuditCommand::outcome,
                        AuditCommand::metadata)
                .containsExactly(
                        ACTOR,
                        "REVISION_RESTORED_TO_DRAFT",
                        type.name(),
                        aggregateId.toString(),
                        AuditOutcome.SUCCESS,
                        Map.of("revisionId", REVISION_ID.toString()));
    }

    private void assertOnlyRevisionReadFromPublishing() {
        verify(publishing).requireRevision(REVISION_ID);
        verifyNoMoreInteractions(publishing);
    }

    private static void assertDomainFailure(
            Runnable action, String code, HttpStatus status) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(DomainException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(code);
                    assertThat(failure.status()).isEqualTo(status);
                });
    }

    private static RevisionRow revision(
            AggregateType type, UUID aggregateId, int schemaVersion) {
        return new RevisionRow(
                REVISION_ID,
                type,
                aggregateId,
                3,
                schemaVersion,
                JSON,
                "a".repeat(64),
                ACTOR,
                NOW.minusSeconds(3_600));
    }

    private static ProjectSnapshotV1 projectSnapshot(
            UUID projectId, PublishedMediaV1... media) {
        return new ProjectSnapshotV1(
                1,
                projectId,
                "project-test",
                "historical-project",
                "01",
                0,
                false,
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(media));
    }

    private static PublishedMediaV1 publishedMedia(
            UUID assetId, String... variantNames) {
        return new PublishedMediaV1(
                assetId,
                "image/webp",
                1_024,
                "b".repeat(64),
                Map.of(),
                List.of(variantNames).stream()
                        .map(name -> new PublishedMediaV1.Variant(
                                name, 1280, 720, 512, "c".repeat(64)))
                        .toList());
    }

    private static ProjectWorkspaceDto project(long version, UUID... assetIds) {
        return projectWithIdentity(PROJECT_ID, "project-test", version, assetIds);
    }

    private static ProjectWorkspaceDto projectWithIdentity(
            UUID projectId, String externalKey, long version, UUID... assetIds) {
        ProjectWorkspaceDto source = WorkspaceFixtures.projectBuilder()
                .version(version)
                .publicationDirty(true)
                .build();
        List<ProjectWorkspaceDto.ProjectMedia> media = List.of(assetIds).stream()
                .map(assetId -> new ProjectWorkspaceDto.ProjectMedia(
                        assetId,
                        assetId.equals(assetIds[0]) ? "COVER" : "DETAIL",
                        0,
                        "wide",
                        "50% 50%",
                        "credit",
                        URI.create("https://example.test/media/" + assetId)))
                .toList();
        return new ProjectWorkspaceDto(
                projectId,
                externalKey,
                source.slug(),
                source.number(),
                source.sortOrder(),
                source.featured(),
                source.visible(),
                true,
                version,
                source.translations(),
                source.tags(),
                source.skills(),
                media,
                List.of());
    }

    private static SiteWorkspaceDto site(
            long version, UUID heroAsset, UUID resumeAsset) {
        SiteWorkspaceDto workspace = mock(SiteWorkspaceDto.class);
        SiteWorkspaceDto.Hero hero = new SiteWorkspaceDto.Hero(
                UUID.fromString("90000000-0000-4000-8000-000000000010"),
                3,
                heroAsset,
                "50% 50%",
                "credit",
                URI.create("https://example.test/hero"),
                Map.of());
        SiteWorkspaceDto.ResumeDocument resume = new SiteWorkspaceDto.ResumeDocument(
                UUID.fromString("90000000-0000-4000-8000-000000000011"),
                LocaleCode.EN,
                resumeAsset,
                "v1",
                true,
                LocalDate.of(2026, 7, 17));
        when(workspace.siteId()).thenReturn(SiteWorkspaceDto.SITE_ID);
        when(workspace.version()).thenReturn(version);
        when(workspace.hero()).thenReturn(hero);
        when(workspace.resumes()).thenReturn(List.of(resume));
        return workspace;
    }

    private static LockedMediaPlan plan(
            Set<UUID> assetIds, Set<MediaQueryAccessGuard.VariantKey> variants) {
        return new LockedMediaPlan(assetIds, variants);
    }

    private static MediaQueryAccessGuard.VariantKey variant(
            UUID assetId, String name) {
        return new MediaQueryAccessGuard.VariantKey(assetId, name);
    }
}
