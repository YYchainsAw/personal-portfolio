package xyz.yychainsaw.portfolio.publishing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import xyz.yychainsaw.portfolio.audit.AuditCommand;
import xyz.yychainsaw.portfolio.audit.AuditOutcome;
import xyz.yychainsaw.portfolio.audit.AuditService;
import xyz.yychainsaw.portfolio.auth.CurrentAdminProvider;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.content.api.ContentBlockDto;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.content.api.ProjectWorkspaceDto;
import xyz.yychainsaw.portfolio.content.api.SiteWorkspaceDto;
import xyz.yychainsaw.portfolio.content.persistence.ProjectWorkspaceRepository;
import xyz.yychainsaw.portfolio.content.persistence.SiteWorkspaceRepository;
import xyz.yychainsaw.portfolio.content.support.WorkspaceFixtures;
import xyz.yychainsaw.portfolio.media.application.MediaAssetDescriptor;
import xyz.yychainsaw.portfolio.media.application.MediaCopyDescriptor;
import xyz.yychainsaw.portfolio.media.application.MediaQueryAccessGuard;
import xyz.yychainsaw.portfolio.media.application.MediaQueryService;
import xyz.yychainsaw.portfolio.media.application.MediaVariantDescriptor;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;
import xyz.yychainsaw.portfolio.publishing.api.ArchiveProjectCommand;
import xyz.yychainsaw.portfolio.publishing.api.PublicationResult;
import xyz.yychainsaw.portfolio.publishing.api.PublishProjectCommand;
import xyz.yychainsaw.portfolio.publishing.api.PublishSiteCommand;
import xyz.yychainsaw.portfolio.publishing.api.ReorderCatalogCommand;
import xyz.yychainsaw.portfolio.publishing.persistence.PublishingRepository;
import xyz.yychainsaw.portfolio.publishing.persistence.PublishingRepository.MediaReferenceRow;
import xyz.yychainsaw.portfolio.publishing.persistence.PublishingRepository.PublicationRow;
import xyz.yychainsaw.portfolio.publishing.persistence.PublishingRepository.RevisionRow;
import xyz.yychainsaw.portfolio.publishing.snapshot.AggregateType;
import xyz.yychainsaw.portfolio.publishing.snapshot.EncodedSnapshot;
import xyz.yychainsaw.portfolio.publishing.snapshot.ProjectCatalogSnapshotMapper;
import xyz.yychainsaw.portfolio.publishing.snapshot.ProjectSnapshotMapper;
import xyz.yychainsaw.portfolio.publishing.snapshot.SiteSnapshotMapper;
import xyz.yychainsaw.portfolio.publishing.snapshot.SnapshotCodec;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.ProjectCatalogSnapshotV1;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.ProjectSnapshotV1;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.PublishedMediaV1;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.SiteSnapshotV1;

/**
 * Service-level publication contract tests. Snapshot field validation belongs to
 * {@link PublicationValidator}; these tests freeze transaction orchestration and
 * immutable-write semantics without booting Spring or PostgreSQL.
 */
class PublicationServiceTest {
    private static final UUID CATALOG_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID ACTOR =
            UUID.fromString("90000000-0000-4000-8000-000000000001");
    private static final UUID PROJECT_ID =
            UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_PROJECT_ID =
            UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final UUID SITE_REVISION =
            UUID.fromString("91000000-0000-4000-8000-000000000001");
    private static final UUID PROJECT_REVISION =
            UUID.fromString("91000000-0000-4000-8000-000000000002");
    private static final UUID CATALOG_REVISION =
            UUID.fromString("91000000-0000-4000-8000-000000000003");
    private static final UUID RETAINED_PROJECT_REVISION =
            UUID.fromString("91000000-0000-4000-8000-000000000004");
    private static final UUID OTHER_OLD_REVISION =
            UUID.fromString("91000000-0000-4000-8000-000000000005");
    private static final UUID OTHER_NEW_REVISION =
            UUID.fromString("91000000-0000-4000-8000-000000000006");
    private static final UUID OLD_SITE_REVISION =
            UUID.fromString("91000000-0000-4000-8000-000000000007");
    private static final UUID OLD_CATALOG_REVISION =
            UUID.fromString("91000000-0000-4000-8000-000000000008");
    private static final UUID ASSET_LOW =
            UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID ASSET_HIGH =
            UUID.fromString("f0000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-07-17T08:00:00Z");
    private static final Instant BEFORE = Instant.parse("2026-07-16T08:00:00Z");

    private final CurrentAdminProvider currentAdmin = mock(CurrentAdminProvider.class);
    private final SiteWorkspaceRepository sites = mock(SiteWorkspaceRepository.class);
    private final ProjectWorkspaceRepository projects = mock(ProjectWorkspaceRepository.class);
    private final PublishingRepository publishing = mock(PublishingRepository.class);
    private final PublicationValidator validator = mock(PublicationValidator.class);
    private final SiteSnapshotMapper siteSnapshots = mock(SiteSnapshotMapper.class);
    private final ProjectSnapshotMapper projectSnapshots = mock(ProjectSnapshotMapper.class);
    private final ProjectCatalogSnapshotMapper catalogSnapshots =
            mock(ProjectCatalogSnapshotMapper.class);
    private final MediaQueryService media = mock(MediaQueryService.class);
    private final MediaQueryAccessGuard mediaAccess = new MediaQueryAccessGuard();
    private final AuditService audit = mock(AuditService.class);
    private final TransactionOperations transactions = mock(TransactionOperations.class);
    private final SnapshotCodec codec = new SnapshotCodec(new ObjectMapper());
    private final CountingClock clock = new CountingClock(NOW);
    private final RevisionIdSequence revisionIds = new RevisionIdSequence();
    private PublicationService service;

    @BeforeEach
    void setUp() {
        when(currentAdmin.requireAdminId()).thenReturn(ACTOR);
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        when(media.requireReadyAsset(any())).thenAnswer(invocation ->
                readyAsset(invocation.getArgument(0)));
        when(media.requireReadyVariant(any(), any())).thenAnswer(invocation ->
                readyVariant(invocation.getArgument(0), invocation.getArgument(1)));

        service = newService(media);
    }

    private PublicationService newService(MediaQueryService mediaQueryService) {
        return new PublicationService(
                currentAdmin,
                sites,
                projects,
                publishing,
                validator,
                siteSnapshots,
                projectSnapshots,
                catalogSnapshots,
                codec,
                mediaQueryService,
                mediaAccess,
                audit,
                transactions,
                clock,
                revisionIds);
    }

    @Test
    void publishSiteLocksMediaBeforeSiteStateAndWritesOneRevisionWithPrimaryResult() {
        SiteWorkspaceDto workspace = siteWithPublishedMedia(WorkspaceFixtures.site(5));
        SiteSnapshotV1 snapshot = new SiteSnapshotV1(
                1,
                SiteWorkspaceDto.SITE_ID,
                null,
                List.of(
                        publishedMedia(ASSET_LOW, "original", "zeta"),
                        publishedMedia(ASSET_HIGH, "webp")));
        List<MediaReferenceRow> references = List.of(
                reference(ASSET_LOW, "original", "RESUME"),
                reference(ASSET_LOW, "zeta", "RESUME"),
                reference(ASSET_HIGH, "webp", "HERO"));
        PublicationRow pointer = publication(
                AggregateType.SITE,
                SiteWorkspaceDto.SITE_ID,
                "PUBLISHED",
                OLD_SITE_REVISION,
                null,
                2);
        EncodedSnapshot encoded = codec.encode(snapshot);
        revisionIds.reset(SITE_REVISION);

        when(media.requireReadyAsset(ASSET_LOW))
                .thenReturn(readyAsset(ASSET_LOW, "zeta", "original"));
        when(media.requireReadyAsset(ASSET_HIGH))
                .thenReturn(readyAsset(ASSET_HIGH, "webp"));
        when(sites.require()).thenReturn(workspace);
        when(siteSnapshots.toSnapshot(workspace)).thenReturn(snapshot);
        when(validator.validateSite(workspace, snapshot)).thenReturn(references);
        when(publishing.lock(AggregateType.SITE, SiteWorkspaceDto.SITE_ID))
                .thenReturn(pointer);
        when(sites.requireForUpdate()).thenReturn(workspace);
        when(publishing.casPublish(
                        AggregateType.SITE,
                        SiteWorkspaceDto.SITE_ID,
                        2,
                        SITE_REVISION,
                        null,
                        NOW))
                .thenReturn(true);

        PublicationResult result = service.publishSite(new PublishSiteCommand(5, 2));

        assertThat(result).isEqualTo(new PublicationResult(
                SITE_REVISION, 3, null, null, encoded.sha256()));
        verify(publishing).insertRevision(new RevisionRow(
                SITE_REVISION,
                AggregateType.SITE,
                SiteWorkspaceDto.SITE_ID,
                3,
                encoded.schemaVersion(),
                encoded.json(),
                encoded.sha256(),
                ACTOR,
                NOW));
        verify(publishing).insertMediaReferences(SITE_REVISION, references);

        InOrder mediaBeforePointer = inOrder(
                validator, media, siteSnapshots, publishing);
        mediaBeforePointer.verify(validator).validateSiteWorkspace(workspace);
        mediaBeforePointer.verify(media).requireReadyAsset(ASSET_LOW);
        mediaBeforePointer.verify(media).requireReadyAsset(ASSET_HIGH);
        mediaBeforePointer.verify(media).requireReadyVariant(ASSET_LOW, "original");
        mediaBeforePointer.verify(media).requireReadyVariant(ASSET_LOW, "zeta");
        mediaBeforePointer.verify(media).requireReadyVariant(ASSET_HIGH, "webp");
        mediaBeforePointer.verify(siteSnapshots).toSnapshot(workspace);
        mediaBeforePointer.verify(validator).validateSite(workspace, snapshot);
        mediaBeforePointer.verify(publishing)
                .lock(AggregateType.SITE, SiteWorkspaceDto.SITE_ID);
        InOrder mediaBeforeWorkspace = inOrder(media, sites);
        mediaBeforeWorkspace.verify(media).requireReadyAsset(ASSET_LOW);
        mediaBeforeWorkspace.verify(media).requireReadyAsset(ASSET_HIGH);
        mediaBeforeWorkspace.verify(media).requireReadyVariant(ASSET_LOW, "original");
        mediaBeforeWorkspace.verify(media).requireReadyVariant(ASSET_LOW, "zeta");
        mediaBeforeWorkspace.verify(media).requireReadyVariant(ASSET_HIGH, "webp");
        mediaBeforeWorkspace.verify(sites).requireForUpdate();

        assertAudit(
                "SITE_PUBLISHED",
                "SITE",
                SiteWorkspaceDto.SITE_ID,
                Map.of("revisionId", SITE_REVISION.toString()));
        assertThat(clock.calls()).isOne();
    }

    @Test
    void publishProjectWritesProjectAndCatalogAtomicallyRedirectsAndClearsDirty() {
        ProjectWorkspaceDto workspace = projectWithPublishedMedia(WorkspaceFixtures.projectBuilder()
                .slug("new-project-slug")
                .version(5)
                .build());
        ProjectSnapshotV1 projectSnapshot = withPublishedMedia(
                projectSnapshot(PROJECT_ID, "new-project-slug", 0),
                publishedMedia(ASSET_LOW, "card"),
                publishedMedia(ASSET_HIGH, "desktop"));
        ProjectCatalogSnapshotV1 catalogSnapshot = catalogSnapshot(
                List.of(PROJECT_ID),
                Map.of(PROJECT_ID, publishedMedia(ASSET_LOW, "card")));
        List<MediaReferenceRow> projectReferences = List.of(
                reference(ASSET_LOW, "card", "COVER"),
                reference(ASSET_HIGH, "desktop", "DETAIL"));
        List<MediaReferenceRow> catalogReferences = List.of(
                reference(ASSET_LOW, "card", "CATALOG_COVER"));
        PublicationRow projectPointer = publication(
                AggregateType.PROJECT,
                PROJECT_ID,
                "PUBLISHED",
                RETAINED_PROJECT_REVISION,
                "old-project-slug",
                3);
        PublicationRow catalogPointer = publication(
                AggregateType.PROJECT_CATALOG,
                CATALOG_ID,
                "PUBLISHED",
                OLD_CATALOG_REVISION,
                null,
                7);
        EncodedSnapshot encodedProject = codec.encode(projectSnapshot);
        EncodedSnapshot encodedCatalog = codec.encode(catalogSnapshot);
        revisionIds.reset(PROJECT_REVISION, CATALOG_REVISION);

        when(media.requireReadyAsset(ASSET_LOW))
                .thenReturn(readyAsset(ASSET_LOW, "card"));
        when(media.requireReadyAsset(ASSET_HIGH))
                .thenReturn(readyAsset(ASSET_HIGH, "desktop"));
        when(projects.require(PROJECT_ID)).thenReturn(workspace);
        when(projectSnapshots.toSnapshot(workspace)).thenReturn(projectSnapshot);
        when(publishing.findPublishedProjects())
                .thenReturn(List.of(projectPointer), List.of(projectPointer));
        when(catalogSnapshots.fromCurrentProjects(List.of(projectSnapshot)))
                .thenReturn(catalogSnapshot);
        when(validator.validateProject(workspace, projectSnapshot))
                .thenReturn(projectReferences);
        when(validator.validateCatalog(catalogSnapshot)).thenReturn(catalogReferences);
        when(publishing.lock(AggregateType.PROJECT_CATALOG, CATALOG_ID))
                .thenReturn(catalogPointer);
        when(projects.requireForUpdate(PROJECT_ID)).thenReturn(workspace);
        when(publishing.lock(AggregateType.PROJECT, PROJECT_ID)).thenReturn(projectPointer);
        when(publishing.currentSlugOrRedirectExists("new-project-slug", PROJECT_ID))
                .thenReturn(false);
        when(publishing.casPublish(
                        AggregateType.PROJECT,
                        PROJECT_ID,
                        3,
                        PROJECT_REVISION,
                        "new-project-slug",
                        NOW))
                .thenReturn(true);
        when(publishing.casPublish(
                        AggregateType.PROJECT_CATALOG,
                        CATALOG_ID,
                        7,
                        CATALOG_REVISION,
                        null,
                        NOW))
                .thenReturn(true);

        PublicationResult result = service.publishProject(
                new PublishProjectCommand(PROJECT_ID, 5, 3, 7));

        assertThat(result).isEqualTo(new PublicationResult(
                PROJECT_REVISION, 4, CATALOG_REVISION, 8L, encodedProject.sha256()));
        verify(publishing).insertRevision(new RevisionRow(
                PROJECT_REVISION,
                AggregateType.PROJECT,
                PROJECT_ID,
                4,
                encodedProject.schemaVersion(),
                encodedProject.json(),
                encodedProject.sha256(),
                ACTOR,
                NOW));
        verify(publishing).insertRevision(new RevisionRow(
                CATALOG_REVISION,
                AggregateType.PROJECT_CATALOG,
                CATALOG_ID,
                8,
                encodedCatalog.schemaVersion(),
                encodedCatalog.json(),
                encodedCatalog.sha256(),
                ACTOR,
                NOW));
        verify(publishing).insertMediaReferences(PROJECT_REVISION, projectReferences);
        verify(publishing).insertMediaReferences(CATALOG_REVISION, catalogReferences);
        verify(publishing).insertRedirect(
                "old-project-slug", "new-project-slug", PROJECT_ID, NOW);
        verify(projects).markPublished(PROJECT_ID, 5, NOW);

        InOrder locks = inOrder(
                validator,
                media,
                projectSnapshots,
                catalogSnapshots,
                publishing,
                projects);
        locks.verify(validator).validateProjectWorkspace(workspace);
        locks.verify(media).requireReadyAsset(ASSET_LOW);
        locks.verify(media).requireReadyAsset(ASSET_HIGH);
        locks.verify(media).requireReadyVariant(ASSET_LOW, "card");
        locks.verify(media).requireReadyVariant(ASSET_HIGH, "desktop");
        locks.verify(projectSnapshots).toSnapshot(workspace);
        locks.verify(validator).validateProject(workspace, projectSnapshot);
        locks.verify(catalogSnapshots).fromCurrentProjects(List.of(projectSnapshot));
        locks.verify(validator).validateCatalogStructure(catalogSnapshot);
        locks.verify(validator).validateCatalog(catalogSnapshot);
        locks.verify(publishing).lock(AggregateType.PROJECT_CATALOG, CATALOG_ID);
        locks.verify(projects).requireForUpdate(PROJECT_ID);
        locks.verify(publishing).ensureProjectPublication(PROJECT_ID);
        locks.verify(publishing).lock(AggregateType.PROJECT, PROJECT_ID);

        assertAudit(
                "PROJECT_PUBLISHED",
                "PROJECT",
                PROJECT_ID,
                Map.of("revisionId", PROJECT_REVISION.toString()));
        assertThat(clock.calls()).isOne();
    }

    @Test
    void archiveWritesOnlyCatalogAndReturnsRetainedProjectRevisionAsPrimaryResult() {
        ProjectSnapshotV1 retainedSnapshot = projectSnapshot(PROJECT_ID, "project", 0);
        ProjectSnapshotV1 remainingSnapshot = projectSnapshotWithCover(
                OTHER_PROJECT_ID, "remaining", 1, ASSET_LOW, "card");
        EncodedSnapshot encodedRetained = codec.encode(retainedSnapshot);
        RevisionRow retainedRevision = new RevisionRow(
                RETAINED_PROJECT_REVISION,
                AggregateType.PROJECT,
                PROJECT_ID,
                4,
                encodedRetained.schemaVersion(),
                encodedRetained.json(),
                encodedRetained.sha256(),
                ACTOR,
                BEFORE);
        PublicationRow projectPointer = publication(
                AggregateType.PROJECT,
                PROJECT_ID,
                "PUBLISHED",
                RETAINED_PROJECT_REVISION,
                "project",
                4);
        PublicationRow catalogPointer = publication(
                AggregateType.PROJECT_CATALOG,
                CATALOG_ID,
                "PUBLISHED",
                OLD_CATALOG_REVISION,
                null,
                8);
        PublicationRow remainingPointer = publication(
                AggregateType.PROJECT,
                OTHER_PROJECT_ID,
                "PUBLISHED",
                OTHER_OLD_REVISION,
                "remaining",
                2);
        RevisionRow remainingRevision = revision(
                OTHER_OLD_REVISION,
                OTHER_PROJECT_ID,
                2,
                remainingSnapshot,
                BEFORE);
        ProjectCatalogSnapshotV1 catalogSnapshot = catalogSnapshot(
                List.of(OTHER_PROJECT_ID),
                Map.of(OTHER_PROJECT_ID, publishedMedia(ASSET_LOW, "card")));
        List<MediaReferenceRow> catalogReferences = List.of(
                reference(ASSET_LOW, "card", "CATALOG_COVER"));
        EncodedSnapshot encodedCatalog = codec.encode(catalogSnapshot);
        revisionIds.reset(CATALOG_REVISION);

        when(media.requireReadyAsset(ASSET_LOW))
                .thenReturn(readyAsset(ASSET_LOW));
        when(publishing.findPublishedProjects())
                .thenReturn(
                        List.of(projectPointer, remainingPointer),
                        List.of(projectPointer, remainingPointer));
        when(publishing.requireRevision(RETAINED_PROJECT_REVISION))
                .thenReturn(retainedRevision);
        when(publishing.requireRevision(OTHER_OLD_REVISION))
                .thenReturn(remainingRevision);
        when(catalogSnapshots.fromCurrentProjects(List.of(remainingSnapshot)))
                .thenReturn(catalogSnapshot);
        when(validator.validateCatalog(catalogSnapshot)).thenReturn(catalogReferences);
        when(publishing.lock(AggregateType.PROJECT_CATALOG, CATALOG_ID))
                .thenReturn(catalogPointer);
        when(publishing.lock(AggregateType.PROJECT, PROJECT_ID)).thenReturn(projectPointer);
        when(publishing.casPublish(
                        AggregateType.PROJECT_CATALOG,
                        CATALOG_ID,
                        8,
                        CATALOG_REVISION,
                        null,
                        NOW))
                .thenReturn(true);
        when(publishing.casArchive(AggregateType.PROJECT, PROJECT_ID, 4, NOW))
                .thenReturn(true);

        PublicationResult result = service.archiveProject(
                new ArchiveProjectCommand(PROJECT_ID, 4, 8));

        assertThat(result).isEqualTo(new PublicationResult(
                RETAINED_PROJECT_REVISION,
                5,
                CATALOG_REVISION,
                9L,
                encodedRetained.sha256()));
        verify(publishing).insertRevision(new RevisionRow(
                CATALOG_REVISION,
                AggregateType.PROJECT_CATALOG,
                CATALOG_ID,
                9,
                encodedCatalog.schemaVersion(),
                encodedCatalog.json(),
                encodedCatalog.sha256(),
                ACTOR,
                NOW));
        verify(publishing, times(1)).insertRevision(any(RevisionRow.class));
        verify(publishing).insertMediaReferences(CATALOG_REVISION, catalogReferences);
        verify(projects, never()).markPublished(any(), anyLong(), any(Instant.class));
        verify(publishing, never()).insertRedirect(any(), any(), any(), any(Instant.class));

        InOrder locks = inOrder(validator, media, publishing);
        locks.verify(validator).validateCatalogStructure(catalogSnapshot);
        locks.verify(media).requireReadyAsset(ASSET_LOW);
        locks.verify(media).requireReadyVariant(ASSET_LOW, "card");
        locks.verify(validator).validateCatalog(catalogSnapshot);
        locks.verify(publishing).lock(AggregateType.PROJECT_CATALOG, CATALOG_ID);
        locks.verify(publishing).lock(AggregateType.PROJECT, PROJECT_ID);

        assertAudit(
                "PROJECT_ARCHIVED",
                "PROJECT_CATALOG",
                CATALOG_ID,
                Map.of(
                        "projectId", PROJECT_ID.toString(),
                        "revisionId", CATALOG_REVISION.toString()));
        assertThat(clock.calls()).isOne();
    }

    @Test
    void reorderUsesExplicitOrderWritesOnlyCatalogAndLeavesSecondaryCatalogFieldsNull() {
        ProjectSnapshotV1 first = projectSnapshot(PROJECT_ID, "first", 0);
        ProjectSnapshotV1 second = projectSnapshotWithCover(
                OTHER_PROJECT_ID, "second", 1, ASSET_HIGH, "card");
        RevisionRow firstRevision = revision(
                RETAINED_PROJECT_REVISION, PROJECT_ID, 4, first, BEFORE);
        RevisionRow secondRevision = revision(
                OTHER_OLD_REVISION, OTHER_PROJECT_ID, 2, second, BEFORE);
        PublicationRow firstPointer = publication(
                AggregateType.PROJECT,
                PROJECT_ID,
                "PUBLISHED",
                RETAINED_PROJECT_REVISION,
                "first",
                4);
        PublicationRow secondPointer = publication(
                AggregateType.PROJECT,
                OTHER_PROJECT_ID,
                "PUBLISHED",
                OTHER_OLD_REVISION,
                "second",
                2);
        PublicationRow catalogPointer = publication(
                AggregateType.PROJECT_CATALOG,
                CATALOG_ID,
                "PUBLISHED",
                OLD_CATALOG_REVISION,
                null,
                9);
        ProjectCatalogSnapshotV1 reordered = catalogSnapshot(
                List.of(OTHER_PROJECT_ID, PROJECT_ID),
                Map.of(OTHER_PROJECT_ID, publishedMedia(ASSET_HIGH, "card")));
        List<MediaReferenceRow> references = List.of(
                reference(ASSET_HIGH, "card", "CATALOG_COVER"));
        EncodedSnapshot encoded = codec.encode(reordered);
        List<UUID> requestedOrder = List.of(OTHER_PROJECT_ID, PROJECT_ID);
        revisionIds.reset(CATALOG_REVISION);

        when(media.requireReadyAsset(ASSET_HIGH))
                .thenReturn(readyAsset(ASSET_HIGH));
        when(publishing.findPublishedProjects()).thenReturn(
                List.of(firstPointer, secondPointer),
                List.of(firstPointer, secondPointer));
        when(publishing.requireRevision(RETAINED_PROJECT_REVISION)).thenReturn(firstRevision);
        when(publishing.requireRevision(OTHER_OLD_REVISION)).thenReturn(secondRevision);
        when(catalogSnapshots.fromCurrentProjects(List.of(second, first)))
                .thenReturn(reordered);
        when(validator.validateCatalog(reordered)).thenReturn(references);
        when(publishing.lock(AggregateType.PROJECT_CATALOG, CATALOG_ID))
                .thenReturn(catalogPointer);
        when(publishing.casPublish(
                        AggregateType.PROJECT_CATALOG,
                        CATALOG_ID,
                        9,
                        CATALOG_REVISION,
                        null,
                        NOW))
                .thenReturn(true);

        PublicationResult result = service.reorderCatalog(
                new ReorderCatalogCommand(9, requestedOrder));

        assertThat(result).isEqualTo(new PublicationResult(
                CATALOG_REVISION, 10, null, null, encoded.sha256()));
        verify(catalogSnapshots).fromCurrentProjects(List.of(second, first));
        verify(projects).updateCatalogOrder(requestedOrder, NOW);
        verify(publishing).insertRevision(new RevisionRow(
                CATALOG_REVISION,
                AggregateType.PROJECT_CATALOG,
                CATALOG_ID,
                10,
                encoded.schemaVersion(),
                encoded.json(),
                encoded.sha256(),
                ACTOR,
                NOW));
        verify(publishing, times(1)).insertRevision(any(RevisionRow.class));
        verify(publishing).insertMediaReferences(CATALOG_REVISION, references);
        verify(publishing, never()).casPublish(
                eq(AggregateType.PROJECT), any(), anyLong(), any(), any(), any());
        verify(publishing, never()).casArchive(any(), any(), anyLong(), any());

        InOrder locks = inOrder(validator, media, publishing, projects);
        locks.verify(validator).validateCatalogStructure(reordered);
        locks.verify(media).requireReadyAsset(ASSET_HIGH);
        locks.verify(media).requireReadyVariant(ASSET_HIGH, "card");
        locks.verify(validator).validateCatalog(reordered);
        locks.verify(publishing).lock(AggregateType.PROJECT_CATALOG, CATALOG_ID);
        locks.verify(projects).updateCatalogOrder(requestedOrder, NOW);

        assertAudit(
                "PROJECT_CATALOG_REORDERED",
                "PROJECT_CATALOG",
                CATALOG_ID,
                Map.of("revisionId", CATALOG_REVISION.toString()));
        assertThat(clock.calls()).isOne();
    }

    @Test
    void staleCatalogVersionReturnsCatalogConflictBeforeAnyRevisionWrite() {
        ProjectWorkspaceDto workspace = WorkspaceFixtures.projectBuilder().version(5).build();
        ProjectSnapshotV1 snapshot = projectSnapshot(PROJECT_ID, workspace.slug(), 0);
        ProjectCatalogSnapshotV1 catalog = catalogSnapshot(List.of(PROJECT_ID));
        PublicationRow projectPointer = publication(
                AggregateType.PROJECT,
                PROJECT_ID,
                "PUBLISHED",
                RETAINED_PROJECT_REVISION,
                workspace.slug(),
                3);

        when(projects.require(PROJECT_ID)).thenReturn(workspace);
        when(projectSnapshots.toSnapshot(workspace)).thenReturn(snapshot);
        when(publishing.findPublishedProjects()).thenReturn(List.of(projectPointer));
        when(catalogSnapshots.fromCurrentProjects(List.of(snapshot))).thenReturn(catalog);
        when(validator.validateProject(workspace, snapshot)).thenReturn(List.of());
        when(validator.validateCatalog(catalog)).thenReturn(List.of());
        when(publishing.lock(AggregateType.PROJECT_CATALOG, CATALOG_ID))
                .thenReturn(publication(
                        AggregateType.PROJECT_CATALOG,
                        CATALOG_ID,
                        "PUBLISHED",
                        OLD_CATALOG_REVISION,
                        null,
                        8));

        assertConflict(
                () -> service.publishProject(new PublishProjectCommand(PROJECT_ID, 5, 3, 7)),
                "CATALOG_VERSION_CONFLICT");

        verify(projects, never()).requireForUpdate(any());
        assertNoImmutableWrites();
    }

    @Test
    void staleProjectPublicationVersionReturnsPublicationConflictBeforeAnyRevisionWrite() {
        ProjectWorkspaceDto workspace = WorkspaceFixtures.projectBuilder().version(5).build();
        ProjectSnapshotV1 snapshot = projectSnapshot(PROJECT_ID, workspace.slug(), 0);
        ProjectCatalogSnapshotV1 catalog = catalogSnapshot(List.of(PROJECT_ID));
        PublicationRow observedPointer = publication(
                AggregateType.PROJECT,
                PROJECT_ID,
                "PUBLISHED",
                RETAINED_PROJECT_REVISION,
                workspace.slug(),
                3);

        when(projects.require(PROJECT_ID)).thenReturn(workspace);
        when(projectSnapshots.toSnapshot(workspace)).thenReturn(snapshot);
        when(publishing.findPublishedProjects())
                .thenReturn(List.of(observedPointer), List.of(observedPointer));
        when(catalogSnapshots.fromCurrentProjects(List.of(snapshot))).thenReturn(catalog);
        when(validator.validateProject(workspace, snapshot)).thenReturn(List.of());
        when(validator.validateCatalog(catalog)).thenReturn(List.of());
        when(publishing.lock(AggregateType.PROJECT_CATALOG, CATALOG_ID))
                .thenReturn(publication(
                        AggregateType.PROJECT_CATALOG,
                        CATALOG_ID,
                        "PUBLISHED",
                        OLD_CATALOG_REVISION,
                        null,
                        7));
        when(projects.requireForUpdate(PROJECT_ID)).thenReturn(workspace);
        when(publishing.lock(AggregateType.PROJECT, PROJECT_ID))
                .thenReturn(publication(
                        AggregateType.PROJECT,
                        PROJECT_ID,
                        "PUBLISHED",
                        RETAINED_PROJECT_REVISION,
                        workspace.slug(),
                        4));

        assertConflict(
                () -> service.publishProject(new PublishProjectCommand(PROJECT_ID, 5, 3, 7)),
                "PUBLICATION_VERSION_CONFLICT");

        assertNoImmutableWrites();
    }

    @Test
    void workspaceDriftAfterMediaLocksReturnsContentConflictWithZeroRevisionInsert() {
        ProjectWorkspaceDto observed = projectWithPublishedMedia(
                WorkspaceFixtures.projectBuilder().version(5).build());
        ProjectWorkspaceDto changed = projectWithPublishedMedia(
                WorkspaceFixtures.projectBuilder().version(6).build());
        ProjectSnapshotV1 snapshot = withPublishedMedia(
                projectSnapshot(PROJECT_ID, observed.slug(), 0),
                publishedMedia(ASSET_LOW, "card"));
        ProjectCatalogSnapshotV1 catalog = catalogSnapshot(List.of(PROJECT_ID));
        List<MediaReferenceRow> references = List.of(
                reference(ASSET_LOW, "card", "COVER"));
        PublicationRow projectPointer = publication(
                AggregateType.PROJECT,
                PROJECT_ID,
                "PUBLISHED",
                RETAINED_PROJECT_REVISION,
                observed.slug(),
                3);

        when(media.requireReadyAsset(ASSET_LOW))
                .thenReturn(readyAsset(ASSET_LOW, "card"));
        when(media.requireReadyAsset(ASSET_HIGH))
                .thenReturn(readyAsset(ASSET_HIGH, "desktop"));
        when(projects.require(PROJECT_ID)).thenReturn(observed);
        when(projectSnapshots.toSnapshot(observed)).thenReturn(snapshot);
        when(publishing.findPublishedProjects())
                .thenReturn(List.of(projectPointer), List.of(projectPointer));
        when(catalogSnapshots.fromCurrentProjects(List.of(snapshot))).thenReturn(catalog);
        when(validator.validateProject(observed, snapshot)).thenReturn(references);
        when(validator.validateCatalog(catalog)).thenReturn(List.of());
        when(publishing.lock(AggregateType.PROJECT_CATALOG, CATALOG_ID))
                .thenReturn(publication(
                        AggregateType.PROJECT_CATALOG,
                        CATALOG_ID,
                        "PUBLISHED",
                        OLD_CATALOG_REVISION,
                        null,
                        7));
        when(projects.requireForUpdate(PROJECT_ID)).thenReturn(changed);

        assertConflict(
                () -> service.publishProject(new PublishProjectCommand(PROJECT_ID, 5, 3, 7)),
                "CONTENT_VERSION_CONFLICT");

        InOrder locks = inOrder(media, publishing, projects);
        locks.verify(media).requireReadyAsset(ASSET_LOW);
        locks.verify(media).requireReadyAsset(ASSET_HIGH);
        locks.verify(media).requireReadyVariant(ASSET_LOW, "card");
        locks.verify(media).requireReadyVariant(ASSET_HIGH, "desktop");
        locks.verify(publishing).lock(AggregateType.PROJECT_CATALOG, CATALOG_ID);
        locks.verify(projects).requireForUpdate(PROJECT_ID);
        verify(publishing, never()).ensureProjectPublication(PROJECT_ID);
        assertNoImmutableWrites();
    }

    @ParameterizedTest
    @ValueSource(strings = {"MEDIA_NOT_FOUND", "MEDIA_NOT_READY"})
    void initialMediaFailureIsNormalizedBeforeSnapshotMappingOrContentLocks(
            String sourceCode) {
        SiteWorkspaceDto workspace = siteWithPublishedMedia(WorkspaceFixtures.site(5));
        HttpStatus sourceStatus = "MEDIA_NOT_FOUND".equals(sourceCode)
                ? HttpStatus.NOT_FOUND
                : HttpStatus.UNPROCESSABLE_ENTITY;

        when(sites.require()).thenReturn(workspace);
        when(media.requireReadyAsset(ASSET_LOW)).thenThrow(new DomainException(
                sourceCode,
                sourceStatus,
                Map.of("assetId", "source adapter detail must not escape")));

        assertThatThrownBy(() -> service.publishSite(new PublishSiteCommand(5, 2)))
                .isInstanceOf(DomainException.class)
                .satisfies(error -> {
                    DomainException domain = (DomainException) error;
                    assertThat(domain.code()).isEqualTo("MEDIA_NOT_READY");
                    assertThat(domain.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(domain.fieldErrors())
                            .containsKey("media[" + ASSET_LOW + "]");
                });

        InOrder preflight = inOrder(validator, media);
        preflight.verify(validator).validateSiteWorkspace(workspace);
        preflight.verify(media).requireReadyAsset(ASSET_LOW);
        verify(siteSnapshots, never()).toSnapshot(any());
        verify(validator, never()).validateSite(any(), any());
        verify(publishing, never()).lock(any(), any());
        assertNoImmutableWrites();
    }

    @Test
    void mapperOrValidatorCannotIntroduceMediaOutsideThePrelockedPlan() {
        SiteWorkspaceDto workspace = siteWithoutMedia(WorkspaceFixtures.site(5));
        SiteSnapshotV1 snapshot = new SiteSnapshotV1(
                1, SiteWorkspaceDto.SITE_ID, null, List.of());
        MediaReferenceRow undiscovered = reference(ASSET_LOW, "original", "HERO");

        when(sites.require()).thenReturn(workspace);
        when(siteSnapshots.toSnapshot(workspace)).thenReturn(snapshot);
        when(validator.validateSite(workspace, snapshot)).thenReturn(List.of(undiscovered));

        assertThatThrownBy(() -> service.publishSite(new PublishSiteCommand(5, 2)))
                .isInstanceOf(DomainException.class)
                .satisfies(error -> {
                    DomainException domain = (DomainException) error;
                    assertThat(domain.code()).isEqualTo("MEDIA_NOT_READY");
                    assertThat(domain.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(domain.fieldErrors())
                            .containsKey("media[" + ASSET_LOW + "]");
                });

        verify(validator).validateSiteWorkspace(workspace);
        verify(media, never()).requireReadyAsset(ASSET_LOW);
        verify(media, never()).requireReadyVariant(ASSET_LOW, "original");
        verify(publishing, never()).lock(AggregateType.SITE, SiteWorkspaceDto.SITE_ID);
        assertNoImmutableWrites();
    }

    @Test
    void activePublicationScopeRejectsMapperQueriesOutsideThePrelockedAssetPlan() {
        SiteWorkspaceDto workspace = WorkspaceFixtures.site(5);
        MediaQueryService guardedMedia = new GuardedMediaQueryService(mediaAccess);
        service = newService(guardedMedia);

        when(sites.require()).thenReturn(workspace);
        when(siteSnapshots.toSnapshot(workspace)).thenAnswer(invocation -> {
            guardedMedia.requireReadyAsset(ASSET_HIGH);
            return new SiteSnapshotV1(
                    1, SiteWorkspaceDto.SITE_ID, null, List.of());
        });

        assertThatThrownBy(() -> service.publishSite(new PublishSiteCommand(5, 2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("media query is outside the active publication plan");

        verify(validator).validateSiteWorkspace(workspace);
        verify(publishing, never()).lock(AggregateType.SITE, SiteWorkspaceDto.SITE_ID);
        assertNoImmutableWrites();
    }

    @Test
    void everySnapshotVariantRequiresAnExhaustiveFinalReferenceKey() {
        SiteWorkspaceDto workspace = WorkspaceFixtures.site(5);
        SiteSnapshotV1 snapshot = new SiteSnapshotV1(
                1,
                SiteWorkspaceDto.SITE_ID,
                null,
                List.of(publishedMedia(ASSET_LOW, "original")));

        when(media.requireReadyAsset(ASSET_LOW))
                .thenReturn(readyAsset(ASSET_LOW, "original"));
        when(sites.require()).thenReturn(workspace);
        when(siteSnapshots.toSnapshot(workspace)).thenReturn(snapshot);
        when(validator.validateSite(workspace, snapshot)).thenReturn(List.of());

        assertThatThrownBy(() -> service.publishSite(new PublishSiteCommand(5, 2)))
                .isInstanceOf(DomainException.class)
                .satisfies(error -> {
                    DomainException domain = (DomainException) error;
                    assertThat(domain.code()).isEqualTo("MEDIA_NOT_READY");
                    assertThat(domain.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(domain.fieldErrors())
                            .containsKey("media[" + ASSET_LOW + "]");
                });

        verify(publishing, never()).lock(AggregateType.SITE, SiteWorkspaceDto.SITE_ID);
        assertNoImmutableWrites();
    }

    @Test
    void archivedProjectCannotRepublishIntoAnExistingRedirectWithTheSameSlug() {
        ProjectWorkspaceDto workspace = WorkspaceFixtures.projectBuilder()
                .slug("retained-slug")
                .version(5)
                .build();
        ProjectSnapshotV1 projectSnapshot =
                projectSnapshot(PROJECT_ID, "retained-slug", 0);
        ProjectCatalogSnapshotV1 catalogSnapshot = catalogSnapshot(List.of(PROJECT_ID));
        PublicationRow catalogPointer = publication(
                AggregateType.PROJECT_CATALOG,
                CATALOG_ID,
                "PUBLISHED",
                OLD_CATALOG_REVISION,
                null,
                7);
        PublicationRow archivedPointer = publication(
                AggregateType.PROJECT,
                PROJECT_ID,
                "ARCHIVED",
                RETAINED_PROJECT_REVISION,
                "retained-slug",
                3);

        when(projects.require(PROJECT_ID)).thenReturn(workspace);
        when(projectSnapshots.toSnapshot(workspace)).thenReturn(projectSnapshot);
        when(validator.validateProject(workspace, projectSnapshot)).thenReturn(List.of());
        when(publishing.findPublishedProjects()).thenReturn(List.of(), List.of());
        when(catalogSnapshots.fromCurrentProjects(List.of(projectSnapshot)))
                .thenReturn(catalogSnapshot);
        when(validator.validateCatalog(catalogSnapshot)).thenReturn(List.of());
        when(publishing.lock(AggregateType.PROJECT_CATALOG, CATALOG_ID))
                .thenReturn(catalogPointer);
        when(projects.requireForUpdate(PROJECT_ID)).thenReturn(workspace);
        when(publishing.lock(AggregateType.PROJECT, PROJECT_ID))
                .thenReturn(archivedPointer);
        when(publishing.currentSlugOrRedirectExists("retained-slug", PROJECT_ID))
                .thenReturn(true);

        assertConflict(
                () -> service.publishProject(new PublishProjectCommand(
                        PROJECT_ID, 5, 3, 7)),
                "PROJECT_SLUG_CONFLICT");

        verify(publishing)
                .currentSlugOrRedirectExists("retained-slug", PROJECT_ID);
        assertNoImmutableWrites();
    }

    @Test
    void authoritativeCatalogInputDriftReturnsCatalogConflictWithZeroRevisionInsert() {
        ProjectWorkspaceDto workspace = WorkspaceFixtures.projectBuilder().version(5).build();
        ProjectSnapshotV1 target = projectSnapshot(PROJECT_ID, workspace.slug(), 0);
        ProjectSnapshotV1 other = projectSnapshot(OTHER_PROJECT_ID, "other", 1);
        PublicationRow targetPointer = publication(
                AggregateType.PROJECT,
                PROJECT_ID,
                "PUBLISHED",
                RETAINED_PROJECT_REVISION,
                workspace.slug(),
                3);
        PublicationRow otherBefore = publication(
                AggregateType.PROJECT,
                OTHER_PROJECT_ID,
                "PUBLISHED",
                OTHER_OLD_REVISION,
                "other",
                2);
        PublicationRow otherAfter = publication(
                AggregateType.PROJECT,
                OTHER_PROJECT_ID,
                "PUBLISHED",
                OTHER_NEW_REVISION,
                "other",
                3);
        ProjectCatalogSnapshotV1 optimisticCatalog =
                catalogSnapshot(List.of(PROJECT_ID, OTHER_PROJECT_ID));

        when(projects.require(PROJECT_ID)).thenReturn(workspace);
        when(projectSnapshots.toSnapshot(workspace)).thenReturn(target);
        when(publishing.findPublishedProjects()).thenReturn(
                List.of(targetPointer, otherBefore),
                List.of(targetPointer, otherAfter));
        when(publishing.requireRevision(OTHER_OLD_REVISION))
                .thenReturn(revision(OTHER_OLD_REVISION, OTHER_PROJECT_ID, 2, other, BEFORE));
        when(catalogSnapshots.fromCurrentProjects(List.of(target, other)))
                .thenReturn(optimisticCatalog);
        when(validator.validateProject(workspace, target)).thenReturn(List.of());
        when(validator.validateCatalog(optimisticCatalog)).thenReturn(List.of());
        when(publishing.lock(AggregateType.PROJECT_CATALOG, CATALOG_ID))
                .thenReturn(publication(
                        AggregateType.PROJECT_CATALOG,
                        CATALOG_ID,
                        "PUBLISHED",
                        OLD_CATALOG_REVISION,
                        null,
                        7));
        when(projects.requireForUpdate(PROJECT_ID)).thenReturn(workspace);
        when(publishing.lock(AggregateType.PROJECT, PROJECT_ID)).thenReturn(targetPointer);

        assertConflict(
                () -> service.publishProject(new PublishProjectCommand(PROJECT_ID, 5, 3, 7)),
                "CATALOG_VERSION_CONFLICT");

        assertNoImmutableWrites();
    }

    @Test
    void failedSiteCasMapsToPublicationConflictAndSuppressesSuccessAudit() {
        SiteWorkspaceDto workspace = WorkspaceFixtures.site(5);
        SiteSnapshotV1 snapshot = new SiteSnapshotV1(
                1, SiteWorkspaceDto.SITE_ID, null, List.of());
        revisionIds.reset(SITE_REVISION);

        when(sites.require()).thenReturn(workspace);
        when(siteSnapshots.toSnapshot(workspace)).thenReturn(snapshot);
        when(validator.validateSite(workspace, snapshot)).thenReturn(List.of());
        when(publishing.lock(AggregateType.SITE, SiteWorkspaceDto.SITE_ID))
                .thenReturn(publication(
                        AggregateType.SITE,
                        SiteWorkspaceDto.SITE_ID,
                        "PUBLISHED",
                        OLD_SITE_REVISION,
                        null,
                        2));
        when(sites.requireForUpdate()).thenReturn(workspace);
        when(publishing.casPublish(
                        AggregateType.SITE,
                        SiteWorkspaceDto.SITE_ID,
                        2,
                        SITE_REVISION,
                        null,
                        NOW))
                .thenReturn(false);

        assertConflict(
                () -> service.publishSite(new PublishSiteCommand(5, 2)),
                "PUBLICATION_VERSION_CONFLICT");

        verify(audit, never()).record(any());
    }

    @Test
    void failedCatalogCasMapsToCatalogConflictAndSuppressesSuccessAudit() {
        ProjectSnapshotV1 first = projectSnapshot(PROJECT_ID, "first", 0);
        PublicationRow firstPointer = publication(
                AggregateType.PROJECT,
                PROJECT_ID,
                "PUBLISHED",
                RETAINED_PROJECT_REVISION,
                "first",
                4);
        ProjectCatalogSnapshotV1 reordered = catalogSnapshot(List.of(PROJECT_ID));
        revisionIds.reset(CATALOG_REVISION);

        when(publishing.findPublishedProjects())
                .thenReturn(List.of(firstPointer), List.of(firstPointer));
        when(publishing.requireRevision(RETAINED_PROJECT_REVISION))
                .thenReturn(revision(
                        RETAINED_PROJECT_REVISION, PROJECT_ID, 4, first, BEFORE));
        when(catalogSnapshots.fromCurrentProjects(List.of(first))).thenReturn(reordered);
        when(validator.validateCatalog(reordered)).thenReturn(List.of());
        when(publishing.lock(AggregateType.PROJECT_CATALOG, CATALOG_ID))
                .thenReturn(publication(
                        AggregateType.PROJECT_CATALOG,
                        CATALOG_ID,
                        "PUBLISHED",
                        OLD_CATALOG_REVISION,
                        null,
                        9));
        when(publishing.casPublish(
                        AggregateType.PROJECT_CATALOG,
                        CATALOG_ID,
                        9,
                        CATALOG_REVISION,
                        null,
                        NOW))
                .thenReturn(false);

        assertConflict(
                () -> service.reorderCatalog(
                        new ReorderCatalogCommand(9, List.of(PROJECT_ID))),
                "CATALOG_VERSION_CONFLICT");

        verify(audit, never()).record(any());
    }

    private void assertAudit(
            String action,
            String targetType,
            UUID targetId,
            Map<String, String> metadata) {
        ArgumentCaptor<AuditCommand> command = ArgumentCaptor.forClass(AuditCommand.class);
        verify(audit).record(command.capture());
        assertThat(command.getValue().actorAdminId()).isEqualTo(ACTOR);
        assertThat(command.getValue().action()).isEqualTo(action);
        assertThat(command.getValue().targetType()).isEqualTo(targetType);
        assertThat(command.getValue().targetId()).isEqualTo(targetId.toString());
        assertThat(command.getValue().outcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(command.getValue().metadata()).isEqualTo(metadata);
    }

    private void assertNoImmutableWrites() {
        verify(publishing, never()).insertRevision(any());
        verify(publishing, never()).insertMediaReferences(any(), any());
        verify(publishing, never()).casPublish(any(), any(), anyLong(), any(), any(), any());
        verify(publishing, never()).casArchive(any(), any(), anyLong(), any());
        verify(audit, never()).record(any());
    }

    private static void assertConflict(Runnable operation, String code) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(DomainException.class)
                .satisfies(error -> {
                    DomainException domain = (DomainException) error;
                    assertThat(domain.code()).isEqualTo(code);
                    assertThat(domain.status()).isEqualTo(HttpStatus.CONFLICT);
                });
    }

    private RevisionRow revision(
            UUID revisionId,
            UUID projectId,
            long version,
            ProjectSnapshotV1 snapshot,
            Instant publishedAt) {
        EncodedSnapshot encoded = codec.encode(snapshot);
        return new RevisionRow(
                revisionId,
                AggregateType.PROJECT,
                projectId,
                version,
                encoded.schemaVersion(),
                encoded.json(),
                encoded.sha256(),
                ACTOR,
                publishedAt);
    }

    private static PublicationRow publication(
            AggregateType type,
            UUID aggregateId,
            String status,
            UUID revisionId,
            String slug,
            long version) {
        return new PublicationRow(
                type, aggregateId, status, revisionId, slug, version, BEFORE);
    }

    private static MediaReferenceRow reference(
            UUID assetId, String variantName, String usage) {
        return new MediaReferenceRow(assetId, variantName, usage);
    }

    private static ProjectSnapshotV1 projectSnapshot(
            UUID projectId, String slug, int sortOrder) {
        return new ProjectSnapshotV1(
                1,
                projectId,
                "external-" + projectId,
                slug,
                Integer.toString(sortOrder + 1),
                sortOrder,
                false,
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    private static ProjectSnapshotV1 withPublishedMedia(
            ProjectSnapshotV1 source, PublishedMediaV1... media) {
        return new ProjectSnapshotV1(
                source.schemaVersion(),
                source.projectId(),
                source.externalKey(),
                source.slug(),
                source.number(),
                source.sortOrder(),
                source.featured(),
                source.translations(),
                source.tags(),
                source.skills(),
                source.projectMedia(),
                source.blocks(),
                List.of(media));
    }

    private static ProjectSnapshotV1 projectSnapshotWithCover(
            UUID projectId,
            String slug,
            int sortOrder,
            UUID assetId,
            String... variantNames) {
        ProjectSnapshotV1 base = projectSnapshot(projectId, slug, sortOrder);
        return new ProjectSnapshotV1(
                base.schemaVersion(),
                base.projectId(),
                base.externalKey(),
                base.slug(),
                base.number(),
                base.sortOrder(),
                base.featured(),
                base.translations(),
                base.tags(),
                base.skills(),
                List.of(new ProjectSnapshotV1.ProjectMediaV1(
                        assetId,
                        "COVER",
                        0,
                        "wide",
                        "50% 50%",
                        null,
                        null)),
                base.blocks(),
                List.of(publishedMedia(assetId, variantNames)));
    }

    private static ProjectCatalogSnapshotV1 catalogSnapshot(List<UUID> projectIds) {
        return catalogSnapshot(projectIds, Map.of());
    }

    private static ProjectCatalogSnapshotV1 catalogSnapshot(
            List<UUID> projectIds,
            Map<UUID, PublishedMediaV1> covers) {
        List<ProjectCatalogSnapshotV1.Card> cards = projectIds.stream()
                .map(projectId -> new ProjectCatalogSnapshotV1.Card(
                        projectId,
                        "project-" + projectId,
                        "01",
                        projectIds.indexOf(projectId),
                        false,
                        Map.of(),
                        covers.get(projectId)))
                .toList();
        return new ProjectCatalogSnapshotV1(1, cards);
    }

    private static MediaAssetDescriptor readyAsset(UUID assetId) {
        return readyAsset(assetId, new String[0]);
    }

    private static MediaAssetDescriptor readyAsset(
            UUID assetId, String... variantNames) {
        return new MediaAssetDescriptor(
                assetId,
                "READY",
                "image/webp",
                1_024,
                "a".repeat(64),
                Map.of(
                        "zh-CN",
                        new MediaCopyDescriptor(
                                "alt zh", "caption zh", "credit zh", "https://example.test/zh"),
                        "en",
                        new MediaCopyDescriptor(
                                "alt en", "caption en", "credit en", "https://example.test/en")),
                List.of(variantNames).stream()
                        .map(variantName -> readyVariant(assetId, variantName))
                        .toList());
    }

    private static PublishedMediaV1 publishedMedia(
            UUID assetId, String... variantNames) {
        return new PublishedMediaV1(
                assetId,
                "image/webp",
                1_024,
                "c".repeat(64),
                Map.of(),
                List.of(variantNames).stream()
                        .map(variantName -> new PublishedMediaV1.Variant(
                                variantName,
                                1280,
                                720,
                                512,
                                "d".repeat(64)))
                        .toList());
    }

    private static SiteWorkspaceDto siteWithPublishedMedia(SiteWorkspaceDto source) {
        SiteWorkspaceDto.Hero hero = new SiteWorkspaceDto.Hero(
                source.hero().id(),
                source.hero().version(),
                ASSET_HIGH,
                "50% 50%",
                "credit",
                URI.create("https://example.test/hero"),
                source.hero().copy());
        SiteWorkspaceDto.ResumeDocument resume = new SiteWorkspaceDto.ResumeDocument(
                UUID.fromString("90000000-0000-4000-8000-000000000020"),
                LocaleCode.EN,
                ASSET_LOW,
                "v1",
                true,
                LocalDate.of(2026, 7, 17));
        return new SiteWorkspaceDto(
                source.siteId(),
                source.version(),
                source.monogram(),
                source.email(),
                source.identity(),
                source.seo(),
                source.accessibility(),
                source.navigation(),
                hero,
                source.about(),
                source.facts(),
                source.profileSkills(),
                source.work(),
                source.roadmap(),
                source.contact(),
                source.privacy(),
                source.socialLinks(),
                List.of(resume));
    }

    private static SiteWorkspaceDto siteWithoutMedia(SiteWorkspaceDto source) {
        SiteWorkspaceDto.Hero hero = new SiteWorkspaceDto.Hero(
                source.hero().id(),
                source.hero().version(),
                null,
                null,
                null,
                null,
                source.hero().copy());
        return new SiteWorkspaceDto(
                source.siteId(),
                source.version(),
                source.monogram(),
                source.email(),
                source.identity(),
                source.seo(),
                source.accessibility(),
                source.navigation(),
                hero,
                source.about(),
                source.facts(),
                source.profileSkills(),
                source.work(),
                source.roadmap(),
                source.contact(),
                source.privacy(),
                source.socialLinks(),
                List.of());
    }

    private static ProjectWorkspaceDto projectWithPublishedMedia(
            ProjectWorkspaceDto source) {
        List<ProjectWorkspaceDto.ProjectMedia> projectMedia = List.of(
                new ProjectWorkspaceDto.ProjectMedia(
                        ASSET_LOW,
                        "COVER",
                        0,
                        "wide",
                        "50% 50%",
                        "credit",
                        URI.create("https://example.test/project-cover")));
        ContentBlockDto image = new ContentBlockDto(
                UUID.fromString("90000000-0000-4000-8000-000000000030"),
                0,
                true,
                ContentBlockDto.Width.STANDARD,
                ContentBlockDto.Alignment.LEFT,
                ContentBlockDto.Emphasis.NONE,
                1,
                new ContentBlockDto.ImagePayload(ASSET_HIGH));
        return new ProjectWorkspaceDto(
                source.id(),
                source.externalKey(),
                source.slug(),
                source.number(),
                source.sortOrder(),
                source.featured(),
                source.visible(),
                source.publicationDirty(),
                source.version(),
                source.translations(),
                source.tags(),
                source.skills(),
                projectMedia,
                List.of(image));
    }

    private static MediaVariantDescriptor readyVariant(UUID assetId, String variantName) {
        return new MediaVariantDescriptor(
                assetId,
                variantName,
                "READY",
                StorageProvider.LOCAL,
                "portfolio-test",
                "local",
                "media/" + assetId + "/" + variantName,
                "image/webp",
                512,
                "b".repeat(64),
                1280,
                720);
    }

    private static final class GuardedMediaQueryService implements MediaQueryService {
        private final MediaQueryAccessGuard guard;

        private GuardedMediaQueryService(MediaQueryAccessGuard guard) {
            this.guard = guard;
        }

        @Override
        public MediaAssetDescriptor requireReadyAsset(UUID assetId) {
            guard.checkAsset(assetId);
            return readyAsset(assetId);
        }

        @Override
        public MediaVariantDescriptor requireReadyVariant(
                UUID assetId, String variantName) {
            guard.checkVariant(assetId, variantName);
            return readyVariant(assetId, variantName);
        }
    }

    private static final class RevisionIdSequence implements java.util.function.Supplier<UUID> {
        private final Deque<UUID> ids = new ArrayDeque<>();

        void reset(UUID... values) {
            ids.clear();
            ids.addAll(List.of(values));
        }

        @Override
        public UUID get() {
            UUID next = ids.pollFirst();
            if (next == null) {
                throw new AssertionError("publication requested an unexpected revision id");
            }
            return next;
        }
    }

    private static final class CountingClock extends Clock {
        private final Instant fixed;
        private int calls;

        private CountingClock(Instant fixed) {
            this.fixed = fixed;
        }

        int calls() {
            return calls;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            calls++;
            if (calls > 1) {
                throw new AssertionError("one publication transaction must capture one timestamp");
            }
            return fixed;
        }
    }
}
