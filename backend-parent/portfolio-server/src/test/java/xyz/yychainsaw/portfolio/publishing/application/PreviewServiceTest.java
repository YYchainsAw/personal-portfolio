package xyz.yychainsaw.portfolio.publishing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.content.api.ContentBlockDto;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.content.api.ProjectWorkspaceDto;
import xyz.yychainsaw.portfolio.content.api.SiteWorkspaceDto;
import xyz.yychainsaw.portfolio.content.application.WorkspaceValidator;
import xyz.yychainsaw.portfolio.content.persistence.ProjectWorkspaceRepository;
import xyz.yychainsaw.portfolio.content.persistence.SiteWorkspaceRepository;
import xyz.yychainsaw.portfolio.content.support.WorkspaceFixtures;
import xyz.yychainsaw.portfolio.media.application.MediaQueryAccessGuard;
import xyz.yychainsaw.portfolio.publishing.application.PreviewTokenService.PreviewClaims;
import xyz.yychainsaw.portfolio.publishing.snapshot.AggregateType;
import xyz.yychainsaw.portfolio.publishing.snapshot.ProjectSnapshotMapper;
import xyz.yychainsaw.portfolio.publishing.snapshot.SiteSnapshotMapper;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.ProjectSnapshotV1;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.PublishedBlockV1;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.SiteSnapshotV1;

class PreviewServiceTest {
    private static final UUID ADMIN_ID =
            UUID.fromString("93000000-0000-4000-8000-000000000001");
    private static final UUID PROJECT_ID =
            UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID NONCE =
            UUID.fromString("93000000-0000-4000-8000-000000000003");
    private static final UUID ALLOWED_ASSET =
            UUID.fromString("93000000-0000-4000-8000-000000000004");
    private static final UUID OUTSIDE_ASSET =
            UUID.fromString("93000000-0000-4000-8000-000000000005");
    private static final long VERSION = 7L;
    private static final Instant EXPIRES_AT =
            Instant.parse("2026-07-14T00:10:00Z");

    private final SiteWorkspaceRepository sites = mock(SiteWorkspaceRepository.class);
    private final ProjectWorkspaceRepository projects =
            mock(ProjectWorkspaceRepository.class);
    private final WorkspaceValidator validator = spy(new WorkspaceValidator());
    private final SiteSnapshotMapper siteSnapshots = mock(SiteSnapshotMapper.class);
    private final ProjectSnapshotMapper projectSnapshots =
            mock(ProjectSnapshotMapper.class);
    private final PublicProjectionMapper projections = mock(PublicProjectionMapper.class);
    private final MediaQueryAccessGuard mediaAccess = new MediaQueryAccessGuard();

    private PreviewService service;

    @BeforeEach
    void setUp() {
        service = new PreviewService(
                sites,
                projects,
                validator,
                siteSnapshots,
                projectSnapshots,
                projections,
                mediaAccess);
    }

    @Test
    void sitePreviewUsesCurrentWorkspaceAndAllowsPublicationPlaceholders() {
        SiteWorkspaceDto workspace = WorkspaceFixtures.site(VERSION);
        SiteSnapshotV1 snapshot =
                new SiteSnapshotV1(1, SiteWorkspaceDto.SITE_ID, null, List.of());
        when(sites.require()).thenReturn(workspace);
        when(siteSnapshots.toSnapshot(workspace)).thenReturn(snapshot);

        Object preview = service.preview(claims(
                AggregateType.SITE, SiteWorkspaceDto.SITE_ID, VERSION));

        assertThat(preview).isSameAs(snapshot);
        verify(validator).validateSite(workspace);
        verify(siteSnapshots).toSnapshot(workspace);
        verifyNoInteractions(projects, projectSnapshots);
    }

    @Test
    void projectPreviewUsesCurrentWorkspaceAndAllowsMissingPublicationCover() {
        ProjectWorkspaceDto workspace = WorkspaceFixtures.projectBuilder()
                .version(VERSION)
                .media(List.of())
                .build();
        ProjectSnapshotV1 snapshot = projectSnapshot(workspace);
        when(projects.require(PROJECT_ID)).thenReturn(workspace);
        when(projectSnapshots.toSnapshot(workspace)).thenReturn(snapshot);

        Object preview = service.preview(claims(
                AggregateType.PROJECT, PROJECT_ID, VERSION));

        assertThat(preview).isSameAs(snapshot);
        verify(validator).validateProject(workspace);
        verify(projectSnapshots).toSnapshot(workspace);
        verify(projections).validateProjectSafetyTargets(snapshot);
        verifyNoInteractions(sites, siteSnapshots);
    }

    @Test
    void projectPreviewRejectsForgedYoutubeHostButAllowsEmptyBlockCopy() {
        URI forgedUrl = URI.create("https://youtube.com.evil.example/watch?v=abc");
        ContentBlockDto workspaceVideo = new ContentBlockDto(
                UUID.fromString("93000000-0000-4000-8000-000000000020"),
                0,
                true,
                ContentBlockDto.Width.STANDARD,
                ContentBlockDto.Alignment.LEFT,
                ContentBlockDto.Emphasis.NONE,
                1,
                new ContentBlockDto.VideoPayload(
                        "YOUTUBE",
                        forgedUrl,
                        null,
                        Map.of(
                                LocaleCode.ZH_CN, new ContentBlockDto.BlockCopy("", ""),
                                LocaleCode.EN, new ContentBlockDto.BlockCopy("", ""))));
        ProjectWorkspaceDto workspace = WorkspaceFixtures.projectBuilder()
                .version(VERSION)
                .blocks(List.of(workspaceVideo))
                .build();
        ProjectSnapshotV1 base = projectSnapshot(workspace);
        PublishedBlockV1 snapshotVideo = new PublishedBlockV1(
                workspaceVideo.id(),
                0,
                true,
                PublishedBlockV1.WidthV1.STANDARD,
                PublishedBlockV1.AlignmentV1.LEFT,
                PublishedBlockV1.EmphasisV1.NONE,
                1,
                new PublishedBlockV1.VideoPayloadV1(
                        "YOUTUBE", forgedUrl, null, Map.of()));
        ProjectSnapshotV1 snapshot = new ProjectSnapshotV1(
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
                base.projectMedia(),
                List.of(snapshotVideo),
                base.media());
        PreviewService strict = new PreviewService(
                sites,
                projects,
                validator,
                siteSnapshots,
                projectSnapshots,
                new PublicProjectionMapper(new SafeMarkdownRenderer()),
                mediaAccess);
        when(projects.require(PROJECT_ID)).thenReturn(workspace);
        when(projectSnapshots.toSnapshot(workspace)).thenReturn(snapshot);

        assertThatThrownBy(() -> strict.preview(claims(
                        AggregateType.PROJECT, PROJECT_ID, VERSION)))
                .isInstanceOfSatisfying(DomainException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("PROJECT_NOT_PUBLISHABLE");
                    assertThat(failure.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                });
    }

    @Test
    void mapperMediaReadsAreConfinedToAssetsReferencedByTheWorkspace() {
        ProjectWorkspaceDto workspace = WorkspaceFixtures.projectBuilder()
                .version(VERSION)
                .media(List.of(new ProjectWorkspaceDto.ProjectMedia(
                        ALLOWED_ASSET,
                        "COVER",
                        0,
                        "WIDE",
                        null,
                        null,
                        null)))
                .build();
        ProjectSnapshotV1 snapshot = projectSnapshot(workspace);
        when(projects.require(PROJECT_ID)).thenReturn(workspace);
        when(projectSnapshots.toSnapshot(workspace)).thenAnswer(ignored -> {
            mediaAccess.checkAsset(ALLOWED_ASSET);
            assertThatThrownBy(() -> mediaAccess.checkAsset(OUTSIDE_ASSET))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("media query is outside the active publication plan");
            return snapshot;
        });

        assertThat(service.preview(claims(
                AggregateType.PROJECT, PROJECT_ID, VERSION))).isSameAs(snapshot);

        assertThatCode(() -> mediaAccess.checkAsset(OUTSIDE_ASSET))
                .doesNotThrowAnyException();
    }

    @Test
    void staleSiteAndProjectClaimsFailBeforeValidationOrSnapshotMapping() {
        SiteWorkspaceDto site = WorkspaceFixtures.site(VERSION + 1);
        ProjectWorkspaceDto project = WorkspaceFixtures.projectBuilder()
                .version(VERSION + 1)
                .build();
        when(sites.require()).thenReturn(site);
        when(projects.require(PROJECT_ID)).thenReturn(project);

        assertContentConflict(() -> service.preview(claims(
                AggregateType.SITE, SiteWorkspaceDto.SITE_ID, VERSION)));
        assertContentConflict(() -> service.preview(claims(
                AggregateType.PROJECT, PROJECT_ID, VERSION)));

        verify(validator, never()).validateSite(site);
        verify(validator, never()).validateProject(project);
        verifyNoInteractions(siteSnapshots, projectSnapshots);
    }

    @Test
    void siteClaimsForAnotherAggregateAreRejectedBeforeWorkspaceAccess() {
        PreviewClaims mismatched = claims(AggregateType.SITE, PROJECT_ID, VERSION);

        assertThatThrownBy(() -> service.preview(mismatched))
                .isInstanceOfSatisfying(DomainException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("PREVIEW_TOKEN_INVALID");
                    assertThat(failure.status()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(failure.fieldErrors()).isEmpty();
                });

        verifyNoInteractions(
                sites, projects, validator, siteSnapshots, projectSnapshots, projections);
    }

    @Test
    void catalogPreviewIsRejectedWithoutReadingAnyWorkspace() {
        PreviewClaims catalog = claims(
                AggregateType.PROJECT_CATALOG,
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                VERSION);

        assertThatThrownBy(() -> service.preview(catalog))
                .isInstanceOfSatisfying(DomainException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("CATALOG_PREVIEW_NOT_ALLOWED");
                    assertThat(failure.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                });

        verifyNoInteractions(
                sites, projects, validator, siteSnapshots, projectSnapshots, projections);
    }

    @Test
    void previewRunsInAWritableSpringTransactionForPostgresShareLocks()
            throws NoSuchMethodException {
        Transactional annotation = PreviewService.class
                .getMethod("preview", PreviewClaims.class)
                .getAnnotation(Transactional.class);
        if (annotation == null) {
            annotation = PreviewService.class.getAnnotation(Transactional.class);
        }

        assertThat(annotation).isNotNull();
        assertThat(annotation.readOnly()).isFalse();
        assertThat(annotation.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
    }

    private static PreviewClaims claims(
            AggregateType type, UUID aggregateId, long workspaceVersion) {
        return new PreviewClaims(
                ADMIN_ID,
                type,
                aggregateId,
                workspaceVersion,
                EXPIRES_AT,
                NONCE);
    }

    private static ProjectSnapshotV1 projectSnapshot(ProjectWorkspaceDto workspace) {
        return new ProjectSnapshotV1(
                1,
                workspace.id(),
                workspace.externalKey(),
                workspace.slug(),
                workspace.number(),
                workspace.sortOrder(),
                workspace.featured(),
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    private static void assertContentConflict(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(DomainException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("CONTENT_VERSION_CONFLICT");
                    assertThat(failure.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(failure.fieldErrors())
                            .containsEntry("version", "workspace was changed by another request");
                });
    }
}
