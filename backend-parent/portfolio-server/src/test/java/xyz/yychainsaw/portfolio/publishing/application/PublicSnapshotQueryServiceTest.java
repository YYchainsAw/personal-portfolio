package xyz.yychainsaw.portfolio.publishing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.content.api.SiteWorkspaceDto;
import xyz.yychainsaw.portfolio.publicapi.PublicProjectCardDto;
import xyz.yychainsaw.portfolio.publicapi.PublicProjectDto;
import xyz.yychainsaw.portfolio.publicapi.PublicSiteDto;
import xyz.yychainsaw.portfolio.publishing.persistence.PublishingRepository;
import xyz.yychainsaw.portfolio.publishing.persistence.PublishingRepository.PublicationRow;
import xyz.yychainsaw.portfolio.publishing.persistence.PublishingRepository.RevisionRow;
import xyz.yychainsaw.portfolio.publishing.snapshot.AggregateType;
import xyz.yychainsaw.portfolio.publishing.snapshot.SnapshotMapperRegistry;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.ProjectCatalogSnapshotV1;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.ProjectSnapshotV1;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.SiteSnapshotV1;

class PublicSnapshotQueryServiceTest {
    private static final long POINTER_VERSION = 41L;
    private static final long REVISION_VERSION = 7L;
    private static final UUID CATALOG_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID PROJECT_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000010");
    private static final UUID REVISION_ID =
            UUID.fromString("20000000-0000-4000-8000-000000000010");
    private static final String CHECKSUM = "a".repeat(64);

    private PublishingRepository publishing;
    private SnapshotMapperRegistry snapshots;
    private PublicProjectionMapper projections;
    private PublicSnapshotQueryService service;

    @BeforeEach
    void setUp() {
        publishing = mock(PublishingRepository.class);
        snapshots = mock(SnapshotMapperRegistry.class);
        projections = mock(PublicProjectionMapper.class);
        service = new PublicSnapshotQueryService(publishing, snapshots, projections);
    }

    @AfterEach
    void publicQueriesNeverUseRepositoryMutationMethods() {
        verify(publishing, never()).ensureProjectPublication(org.mockito.ArgumentMatchers.any());
        verify(publishing, never()).lock(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(publishing, never()).insertRevision(org.mockito.ArgumentMatchers.any());
        verify(publishing, never()).insertMediaReferences(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList());
        verify(publishing, never()).casPublish(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.any());
        verify(publishing, never()).casArchive(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any());
        verify(publishing, never()).insertRedirect(
                org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.any());
        verify(publishing, never()).insertRedirect(
                org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void siteReadsOnlyTheCurrentPublishedRevisionThroughTheSchemaRegistry() {
        SiteSnapshotV1 snapshot = mock(SiteSnapshotV1.class);
        PublicSiteDto projected = mock(PublicSiteDto.class);
        when(publishing.find(AggregateType.SITE, SiteWorkspaceDto.SITE_ID))
                .thenReturn(Optional.of(pointer(
                        AggregateType.SITE, SiteWorkspaceDto.SITE_ID, "PUBLISHED", null)));
        when(publishing.requireRevision(REVISION_ID)).thenReturn(revision(
                AggregateType.SITE, SiteWorkspaceDto.SITE_ID));
        when(snapshots.readSite(1, "{\"site\":true}")).thenReturn(snapshot);
        when(snapshot.siteId()).thenReturn(SiteWorkspaceDto.SITE_ID);
        when(projections.site(snapshot, LocaleCode.EN)).thenReturn(projected);

        var envelope = service.site(LocaleCode.EN);

        assertThat(envelope.revisionVersion()).isEqualTo(REVISION_VERSION);
        assertThat(envelope.checksum()).isEqualTo(CHECKSUM);
        assertThat(envelope.data()).isSameAs(projected);
        verify(snapshots).readSite(1, "{\"site\":true}");
    }

    @Test
    void catalogReadsOnlyTheFixedCurrentPublishedCatalogRevision() {
        ProjectCatalogSnapshotV1 snapshot = mock(ProjectCatalogSnapshotV1.class);
        List<PublicProjectCardDto> projected = List.of(mock(PublicProjectCardDto.class));
        when(publishing.find(AggregateType.PROJECT_CATALOG, CATALOG_ID))
                .thenReturn(Optional.of(pointer(
                        AggregateType.PROJECT_CATALOG, CATALOG_ID, "PUBLISHED", null)));
        when(publishing.requireRevision(REVISION_ID)).thenReturn(revision(
                AggregateType.PROJECT_CATALOG, CATALOG_ID));
        when(snapshots.readCatalog(1, "{\"site\":true}")).thenReturn(snapshot);
        when(projections.catalog(snapshot, LocaleCode.ZH_CN)).thenReturn(projected);

        var envelope = service.catalog(LocaleCode.ZH_CN);

        assertThat(envelope.data()).containsExactlyElementsOf(projected);
        assertThat(envelope.revisionVersion()).isEqualTo(REVISION_VERSION);
    }

    @Test
    void projectResolvesThePublishedSlugAndNeverTouchesWorkspaceState() {
        ProjectSnapshotV1 snapshot = mock(ProjectSnapshotV1.class);
        PublicProjectDto projected = mock(PublicProjectDto.class);
        when(publishing.findPublishedProjectBySlug("gameplay-prototype"))
                .thenReturn(Optional.of(pointer(
                        AggregateType.PROJECT,
                        PROJECT_ID,
                        "PUBLISHED",
                        "gameplay-prototype")));
        when(publishing.requireRevision(REVISION_ID)).thenReturn(revision(
                AggregateType.PROJECT, PROJECT_ID));
        when(snapshots.readProject(1, "{\"site\":true}")).thenReturn(snapshot);
        when(snapshot.projectId()).thenReturn(PROJECT_ID);
        when(projections.project(snapshot, LocaleCode.EN)).thenReturn(projected);

        var envelope = service.project("gameplay-prototype", LocaleCode.EN);

        assertThat(envelope.data()).isSameAs(projected);
        assertThat(envelope.revisionVersion()).isEqualTo(REVISION_VERSION);
        verify(publishing).findPublishedProjectBySlug("gameplay-prototype");
        verify(snapshots).readProject(1, "{\"site\":true}");
    }

    @Test
    void publishedPointerWithoutCurrentRevisionIsNotPublic() {
        when(publishing.find(AggregateType.SITE, SiteWorkspaceDto.SITE_ID))
                .thenReturn(Optional.of(pointer(
                        AggregateType.SITE,
                        SiteWorkspaceDto.SITE_ID,
                        "PUBLISHED",
                        null,
                        null)));

        assertThatThrownBy(() -> service.site(LocaleCode.EN))
                .isInstanceOfSatisfying(DomainException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("SITE_NOT_FOUND");
                    assertThat(failure.status().value()).isEqualTo(404);
                });
        verify(publishing, never()).requireRevision(org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(snapshots, projections);
    }

    @Test
    void nonPublishedProjectPointerIsNotPublic() {
        when(publishing.findPublishedProjectBySlug("draft-only"))
                .thenReturn(Optional.of(pointer(
                        AggregateType.PROJECT,
                        PROJECT_ID,
                        "DRAFT",
                        "draft-only")));

        assertThatThrownBy(() -> service.project("draft-only", LocaleCode.EN))
                .isInstanceOfSatisfying(DomainException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("PROJECT_NOT_FOUND");
                    assertThat(failure.status().value()).isEqualTo(404);
                });
        verify(publishing, never()).requireRevision(org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(snapshots, projections);
    }

    @Test
    void missingCatalogIsNotPublic() {
        when(publishing.find(AggregateType.PROJECT_CATALOG, CATALOG_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.catalog(LocaleCode.ZH_CN))
                .isInstanceOfSatisfying(DomainException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("PROJECT_CATALOG_NOT_FOUND");
                    assertThat(failure.status().value()).isEqualTo(404);
                });
        verify(publishing, never()).requireRevision(org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(snapshots, projections);
    }

    @Test
    void archivedOrMissingPointersReturnStableNotFoundWithoutReadingARevision() {
        when(publishing.find(AggregateType.SITE, SiteWorkspaceDto.SITE_ID))
                .thenReturn(Optional.of(pointer(
                        AggregateType.SITE, SiteWorkspaceDto.SITE_ID, "ARCHIVED", null)));

        assertThatThrownBy(() -> service.site(LocaleCode.EN))
                .isInstanceOfSatisfying(DomainException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("SITE_NOT_FOUND");
                    assertThat(failure.status().value()).isEqualTo(404);
                });
        assertThatThrownBy(() -> service.project("missing", LocaleCode.EN))
                .isInstanceOfSatisfying(DomainException.class, failure ->
                        assertThat(failure.code()).isEqualTo("PROJECT_NOT_FOUND"));
        verify(publishing, never()).requireRevision(REVISION_ID);
        verifyNoInteractions(snapshots, projections);
    }

    @Test
    void inconsistentPointerRevisionOrSnapshotIdentityFailsClosed() {
        when(publishing.find(AggregateType.SITE, SiteWorkspaceDto.SITE_ID))
                .thenReturn(Optional.of(pointer(
                        AggregateType.SITE, SiteWorkspaceDto.SITE_ID, "PUBLISHED", null)));
        when(publishing.requireRevision(REVISION_ID)).thenReturn(revision(
                AggregateType.PROJECT, PROJECT_ID));

        assertThatThrownBy(() -> service.site(LocaleCode.EN))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(snapshots, projections);

        SiteSnapshotV1 wrongSite = mock(SiteSnapshotV1.class);
        when(publishing.requireRevision(REVISION_ID)).thenReturn(revision(
                AggregateType.SITE, SiteWorkspaceDto.SITE_ID));
        when(snapshots.readSite(1, "{\"site\":true}")).thenReturn(wrongSite);
        when(wrongSite.siteId()).thenReturn(PROJECT_ID);

        assertThatThrownBy(() -> service.site(LocaleCode.EN))
                .isInstanceOf(IllegalStateException.class);
        verify(projections, never()).site(wrongSite, LocaleCode.EN);
    }

    @Test
    void unknownSnapshotSchemaFailurePropagatesWithoutProjection() {
        DomainException unsupported = new DomainException(
                "SNAPSHOT_SCHEMA_UNSUPPORTED", HttpStatus.UNPROCESSABLE_ENTITY, Map.of());
        when(publishing.find(AggregateType.SITE, SiteWorkspaceDto.SITE_ID))
                .thenReturn(Optional.of(pointer(
                        AggregateType.SITE, SiteWorkspaceDto.SITE_ID, "PUBLISHED", null)));
        when(publishing.requireRevision(REVISION_ID)).thenReturn(new RevisionRow(
                REVISION_ID,
                AggregateType.SITE,
                SiteWorkspaceDto.SITE_ID,
                REVISION_VERSION,
                99,
                "{\"site\":true}",
                CHECKSUM,
                UUID.fromString("30000000-0000-4000-8000-000000000010"),
                Instant.parse("2026-07-14T00:00:00Z")));
        when(snapshots.readSite(99, "{\"site\":true}"))
                .thenThrow(unsupported);

        assertThatThrownBy(() -> service.site(LocaleCode.EN))
                .isSameAs(unsupported)
                .isInstanceOfSatisfying(DomainException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("SNAPSHOT_SCHEMA_UNSUPPORTED");
                    assertThat(failure.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                });
        verifyNoInteractions(projections);
    }

    private static PublicationRow pointer(
            AggregateType type, UUID aggregateId, String status, String slug) {
        return pointer(type, aggregateId, status, slug, REVISION_ID);
    }

    private static PublicationRow pointer(
            AggregateType type,
            UUID aggregateId,
            String status,
            String slug,
            UUID currentRevisionId) {
        return new PublicationRow(
                type,
                aggregateId,
                status,
                currentRevisionId,
                slug,
                POINTER_VERSION,
                Instant.parse("2026-07-14T00:00:00Z"));
    }

    private static RevisionRow revision(AggregateType type, UUID aggregateId) {
        return new RevisionRow(
                REVISION_ID,
                type,
                aggregateId,
                REVISION_VERSION,
                1,
                "{\"site\":true}",
                CHECKSUM,
                UUID.fromString("30000000-0000-4000-8000-000000000010"),
                Instant.parse("2026-07-14T00:00:00Z"));
    }
}
