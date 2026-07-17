package xyz.yychainsaw.portfolio.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import xyz.yychainsaw.portfolio.audit.AuditCommand;
import xyz.yychainsaw.portfolio.audit.AuditService;
import xyz.yychainsaw.portfolio.auth.CurrentAdminProvider;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.content.api.ContentBlockDto;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.content.api.ProjectWorkspaceDto;
import xyz.yychainsaw.portfolio.content.api.SiteWorkspaceDto;
import xyz.yychainsaw.portfolio.content.api.TaxonomyWorkspaceDto;
import xyz.yychainsaw.portfolio.content.persistence.ProjectWorkspaceRepository;
import xyz.yychainsaw.portfolio.content.persistence.SiteWorkspaceRepository;
import xyz.yychainsaw.portfolio.content.persistence.TaxonomyRepository;
import xyz.yychainsaw.portfolio.content.support.WorkspaceFixtures;
import xyz.yychainsaw.portfolio.media.application.MediaQueryService;

class ContentWorkspaceServiceTest {
    private static final UUID ACTOR =
            UUID.fromString("90000000-0000-4000-8000-000000000001");
    private static final UUID GENERATED_PROJECT =
            UUID.fromString("90000000-0000-4000-8000-000000000002");
    private static final UUID ASSET_HIGH =
            UUID.fromString("f0000000-0000-4000-8000-000000000001");
    private static final UUID ASSET_LOW =
            UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID ASSET_20 =
            UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID ASSET_30 =
            UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID ASSET_40 =
            UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final UUID ASSET_50 =
            UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final UUID ASSET_60 =
            UUID.fromString("60000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-07-17T05:30:00Z");

    private final CurrentAdminProvider currentAdmin = mock(CurrentAdminProvider.class);
    private final SiteWorkspaceRepository sites = mock(SiteWorkspaceRepository.class);
    private final ProjectWorkspaceRepository projects = mock(ProjectWorkspaceRepository.class);
    private final TaxonomyRepository taxonomy = mock(TaxonomyRepository.class);
    private final WorkspaceValidator validator = mock(WorkspaceValidator.class);
    private final MediaQueryService media = mock(MediaQueryService.class);
    private final AuditService audit = mock(AuditService.class);
    private final TransactionOperations transactions = mock(TransactionOperations.class);
    private ContentWorkspaceService service;

    @BeforeEach
    void setUp() {
        when(currentAdmin.requireAdminId()).thenReturn(ACTOR);
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        service = new ContentWorkspaceService(
                currentAdmin,
                sites,
                projects,
                taxonomy,
                validator,
                media,
                audit,
                transactions,
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> GENERATED_PROJECT);
    }

    @Test
    void updateSiteLocksDistinctMediaInUuidOrderThenWritesAndAudits() {
        SiteWorkspaceDto request = siteWithMedia(WorkspaceFixtures.site(99));
        SiteWorkspaceDto saved = siteWithMedia(WorkspaceFixtures.site(4));
        when(sites.require()).thenReturn(saved);

        SiteWorkspaceDto result = service.updateSite(request, 3);

        assertThat(result).isSameAs(saved);
        InOrder ordered = inOrder(currentAdmin, transactions, media, sites, audit);
        ordered.verify(currentAdmin).requireAdminId();
        ordered.verify(transactions).execute(any());
        ordered.verify(media).requireReadyAsset(ASSET_LOW);
        ordered.verify(media).requireReadyAsset(ASSET_HIGH);
        ordered.verify(sites).replace(request, 3, NOW);
        ordered.verify(audit).record(any(AuditCommand.class));
        ordered.verify(sites).require();
        ArgumentCaptor<AuditCommand> command = ArgumentCaptor.forClass(AuditCommand.class);
        verify(audit).record(command.capture());
        assertThat(command.getValue().actorAdminId()).isEqualTo(ACTOR);
        assertThat(command.getValue().action()).isEqualTo("CONTENT_WORKSPACE_UPDATED");
        assertThat(command.getValue().targetType()).isEqualTo("SITE_WORKSPACE");
    }

    @Test
    void updateProjectRechecksIdentityAndVersionAfterMediaLocks() {
        ProjectWorkspaceDto request = projectWithMedia(WorkspaceFixtures.project(), ASSET_HIGH, ASSET_LOW);
        ProjectWorkspaceDto current = projectWithMedia(WorkspaceFixtures.projectBuilder().version(7).build(), ASSET_HIGH, ASSET_LOW);
        ProjectWorkspaceDto saved = projectWithMedia(WorkspaceFixtures.projectBuilder().version(8).build(), ASSET_HIGH, ASSET_LOW);
        when(projects.require(request.id())).thenReturn(current, saved);
        when(projects.requireForUpdate(request.id())).thenReturn(current);

        ProjectWorkspaceDto result = service.updateProject(request.id(), request, 7);

        assertThat(result).isSameAs(saved);
        InOrder ordered = inOrder(media, projects, audit);
        ordered.verify(media).requireReadyAsset(ASSET_LOW);
        ordered.verify(media).requireReadyAsset(ASSET_HIGH);
        ordered.verify(projects).requireForUpdate(request.id());
        ordered.verify(projects).replace(request, 7, NOW);
        ordered.verify(audit).record(any(AuditCommand.class));
    }

    @Test
    void rejectsProjectPathOrExternalKeyChangesBeforeWriting() {
        ProjectWorkspaceDto request = WorkspaceFixtures.project();

        assertThatThrownBy(() -> service.updateProject(UUID.randomUUID(), request, 0))
                .isInstanceOf(DomainException.class)
                .extracting(error -> ((DomainException) error).code())
                .isEqualTo("PROJECT_ID_MISMATCH");

        ProjectWorkspaceDto existing = withExternalKey(request, "server-owned-key");
        when(projects.require(request.id())).thenReturn(existing);
        assertThatThrownBy(() -> service.updateProject(request.id(), request, 0))
                .isInstanceOf(DomainException.class)
                .extracting(error -> ((DomainException) error).code())
                .isEqualTo("PROJECT_IDENTITY_IMMUTABLE");
        verify(projects, never()).replace(any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void creationReplacesAllServerOwnedRootFields() {
        ProjectWorkspaceDto request = WorkspaceFixtures.projectBuilder()
                .version(44)
                .publicationDirty(false)
                .build();
        ProjectWorkspaceDto saved = copyRoot(request, GENERATED_PROJECT, 0, true);
        when(projects.require(GENERATED_PROJECT)).thenReturn(saved);

        ProjectWorkspaceDto result = service.createProject(request);

        ArgumentCaptor<ProjectWorkspaceDto> inserted =
                ArgumentCaptor.forClass(ProjectWorkspaceDto.class);
        verify(projects).insert(inserted.capture(), org.mockito.ArgumentMatchers.eq(NOW));
        assertThat(inserted.getValue().id()).isEqualTo(GENERATED_PROJECT);
        assertThat(inserted.getValue().version()).isZero();
        assertThat(inserted.getValue().publicationDirty()).isTrue();
        assertThat(result).isSameAs(saved);
    }

    @Test
    void taxonomyMutationReturnsFreshVersionAndAuditsInsideTransaction() {
        UUID tagId = UUID.fromString("90000000-0000-4000-8000-000000000010");
        Map<LocaleCode, String> names = Map.of(
                LocaleCode.ZH_CN, "玩法",
                LocaleCode.EN, "Gameplay");
        TaxonomyWorkspaceDto saved =
                new TaxonomyWorkspaceDto(tagId, "gameplay", 2, names);
        when(taxonomy.findTags()).thenReturn(List.of(saved));

        TaxonomyWorkspaceDto result = service.updateTag(tagId, names, 1);

        assertThat(result).isSameAs(saved);
        InOrder ordered = inOrder(taxonomy, audit);
        ordered.verify(taxonomy).updateTag(tagId, names, 1, NOW);
        ordered.verify(audit).record(any(AuditCommand.class));
        ordered.verify(taxonomy).findTags();
    }

    @Test
    void missingReferencedMediaIsAFieldErrorBeforeActorOrTransaction() {
        SiteWorkspaceDto baseSite = WorkspaceFixtures.site(0);
        SiteWorkspaceDto invalidSite = new SiteWorkspaceDto(
                baseSite.siteId(), baseSite.version(), baseSite.monogram(), baseSite.email(),
                baseSite.identity(), baseSite.seo(), baseSite.accessibility(),
                baseSite.navigation(), baseSite.hero(), baseSite.about(), baseSite.facts(),
                baseSite.profileSkills(), baseSite.work(), baseSite.roadmap(),
                baseSite.contact(), baseSite.privacy(), baseSite.socialLinks(),
                List.of(new SiteWorkspaceDto.ResumeDocument(
                        UUID.fromString("90000000-0000-4000-8000-000000000040"),
                        LocaleCode.EN,
                        null,
                        "v1",
                        true,
                        java.time.LocalDate.of(2026, 7, 17))));
        assertThatThrownBy(() -> service.updateSite(invalidSite, 0))
                .isInstanceOf(DomainException.class)
                .satisfies(error -> assertThat(((DomainException) error).fieldErrors())
                        .containsKey("resumes[0].mediaAssetId"));

        ProjectWorkspaceDto baseProject = WorkspaceFixtures.project();
        ProjectWorkspaceDto invalidProject = new ProjectWorkspaceDto(
                baseProject.id(), baseProject.externalKey(), baseProject.slug(),
                baseProject.number(), baseProject.sortOrder(), baseProject.featured(),
                baseProject.visible(), baseProject.publicationDirty(), baseProject.version(),
                baseProject.translations(), baseProject.tags(), baseProject.skills(),
                List.of(new ProjectWorkspaceDto.ProjectMedia(
                        null,
                        "COVER",
                        0,
                        "wide",
                        "50% 50%",
                        "",
                        URI.create("https://example.test/project"))),
                baseProject.blocks());
        assertThatThrownBy(() -> service.updateProject(
                        invalidProject.id(), invalidProject, 0))
                .isInstanceOf(DomainException.class)
                .satisfies(error -> assertThat(((DomainException) error).fieldErrors())
                        .containsKey("media[0].assetId"));

        verify(currentAdmin, never()).requireAdminId();
        verify(transactions, never()).execute(any());
    }

    @Test
    void malformedBlockMediaReferencesArePreciseFieldErrorsBeforeActorOrTransaction() {
        ProjectWorkspaceDto base = WorkspaceFixtures.project();
        ProjectWorkspaceDto missingImage = withBlocks(base, List.of(block(
                new ContentBlockDto.ImagePayload(null))));
        assertThatThrownBy(() -> service.createProject(missingImage))
                .isInstanceOf(DomainException.class)
                .satisfies(error -> assertThat(((DomainException) error).fieldErrors())
                        .containsKey("blocks[0].mediaAssetId"));

        ContentBlockDto.GalleryPayload malformedGallery =
                mock(ContentBlockDto.GalleryPayload.class);
        when(malformedGallery.mediaAssetIds())
                .thenReturn(java.util.Arrays.asList(ASSET_LOW, null));
        ProjectWorkspaceDto missingGalleryItem = withBlocks(base, List.of(block(
                malformedGallery)));
        assertThatThrownBy(() -> service.createProject(missingGalleryItem))
                .isInstanceOf(DomainException.class)
                .satisfies(error -> assertThat(((DomainException) error).fieldErrors())
                        .containsKey("blocks[0].mediaAssetIds[1]"));

        verify(currentAdmin, never()).requireAdminId();
        verify(transactions, never()).execute(any());
    }

    @Test
    void externalDownloadIsValidAndDoesNotAcquireAMediaLock() {
        ProjectWorkspaceDto request = withBlocks(
                WorkspaceFixtures.project(),
                List.of(block(new ContentBlockDto.DownloadPayload(
                        null,
                        URI.create("https://example.test/download"),
                        actionCopy()))));
        ProjectWorkspaceDto saved = copyRoot(request, GENERATED_PROJECT, 0, true);
        when(projects.require(GENERATED_PROJECT)).thenReturn(saved);

        assertThat(service.createProject(request)).isSameAs(saved);

        verify(media, never()).requireReadyAsset(any());
        verify(projects).insert(any(ProjectWorkspaceDto.class),
                org.mockito.ArgumentMatchers.eq(NOW));
    }

    @Test
    void defensivelyRejectsNonConformingMediaListElementsWithPreciseFields() {
        SiteWorkspaceDto malformedSite = mock(SiteWorkspaceDto.class);
        when(malformedSite.siteId()).thenReturn(SiteWorkspaceDto.SITE_ID);
        when(malformedSite.resumes()).thenReturn(
                java.util.Arrays.asList((SiteWorkspaceDto.ResumeDocument) null));
        assertThatThrownBy(() -> service.updateSite(malformedSite, 0))
                .isInstanceOf(DomainException.class)
                .satisfies(error -> assertThat(((DomainException) error).fieldErrors())
                        .containsKey("resumes[0].mediaAssetId"));

        ProjectWorkspaceDto malformedProject = mock(ProjectWorkspaceDto.class);
        UUID projectId = UUID.fromString("90000000-0000-4000-8000-000000000041");
        when(malformedProject.id()).thenReturn(projectId);
        when(malformedProject.externalKey()).thenReturn("malformed-project");
        when(malformedProject.media()).thenReturn(
                java.util.Arrays.asList((ProjectWorkspaceDto.ProjectMedia) null));
        when(malformedProject.blocks()).thenReturn(List.of());
        assertThatThrownBy(() -> service.updateProject(projectId, malformedProject, 0))
                .isInstanceOf(DomainException.class)
                .satisfies(error -> assertThat(((DomainException) error).fieldErrors())
                        .containsKey("media[0].assetId"));

        verify(currentAdmin, never()).requireAdminId();
        verify(transactions, never()).execute(any());
    }

    @Test
    void updateProjectLocksEveryMediaReferencePathInSortedOrder() {
        ProjectWorkspaceDto request = projectWithEveryMediaPath(WorkspaceFixtures.project());
        ProjectWorkspaceDto current = projectWithEveryMediaPath(
                WorkspaceFixtures.projectBuilder().version(7).build());
        ProjectWorkspaceDto saved = projectWithEveryMediaPath(
                WorkspaceFixtures.projectBuilder().version(8).build());
        when(projects.require(request.id())).thenReturn(current, saved);
        when(projects.requireForUpdate(request.id())).thenReturn(current);

        assertThat(service.updateProject(request.id(), request, 7)).isSameAs(saved);

        InOrder ordered = inOrder(media, projects);
        ordered.verify(media).requireReadyAsset(ASSET_LOW);
        ordered.verify(media).requireReadyAsset(ASSET_20);
        ordered.verify(media).requireReadyAsset(ASSET_30);
        ordered.verify(media).requireReadyAsset(ASSET_40);
        ordered.verify(media).requireReadyAsset(ASSET_50);
        ordered.verify(media).requireReadyAsset(ASSET_60);
        ordered.verify(media).requireReadyAsset(ASSET_HIGH);
        ordered.verify(projects).requireForUpdate(request.id());
    }

    private static SiteWorkspaceDto siteWithMedia(SiteWorkspaceDto source) {
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
                java.time.LocalDate.of(2026, 7, 17));
        return new SiteWorkspaceDto(
                source.siteId(), source.version(), source.monogram(), source.email(),
                source.identity(), source.seo(), source.accessibility(), source.navigation(),
                hero, source.about(), source.facts(), source.profileSkills(), source.work(),
                source.roadmap(), source.contact(), source.privacy(), source.socialLinks(),
                List.of(resume));
    }

    private static ProjectWorkspaceDto projectWithMedia(
            ProjectWorkspaceDto source, UUID first, UUID second) {
        List<ContentBlockDto> blocks = List.of(
                new ContentBlockDto(
                        UUID.fromString("90000000-0000-4000-8000-000000000030"),
                        0, true, ContentBlockDto.Width.STANDARD,
                        ContentBlockDto.Alignment.LEFT, ContentBlockDto.Emphasis.NONE, 1,
                        new ContentBlockDto.GalleryPayload(List.of(first, second, first))));
        return new ProjectWorkspaceDto(
                source.id(), source.externalKey(), source.slug(), source.number(),
                source.sortOrder(), source.featured(), source.visible(),
                source.publicationDirty(), source.version(), source.translations(),
                source.tags(), source.skills(), source.media(), blocks);
    }

    private static ProjectWorkspaceDto projectWithEveryMediaPath(
            ProjectWorkspaceDto source) {
        List<ProjectWorkspaceDto.ProjectMedia> media = List.of(
                new ProjectWorkspaceDto.ProjectMedia(
                        ASSET_20,
                        "COVER",
                        0,
                        "wide",
                        "50% 50%",
                        "credit",
                        URI.create("https://example.test/project-media")));
        List<ContentBlockDto> blocks = List.of(
                block(new ContentBlockDto.ImagePayload(ASSET_60)),
                block(new ContentBlockDto.GalleryPayload(
                        List.of(ASSET_LOW, ASSET_50, ASSET_HIGH))),
                block(new ContentBlockDto.VideoPayload(
                        "YOUTUBE",
                        URI.create("https://example.test/video"),
                        ASSET_40,
                        blockCopy())),
                block(new ContentBlockDto.DownloadPayload(
                        ASSET_30,
                        null,
                        actionCopy())));
        return new ProjectWorkspaceDto(
                source.id(), source.externalKey(), source.slug(), source.number(),
                source.sortOrder(), source.featured(), source.visible(),
                source.publicationDirty(), source.version(), source.translations(),
                source.tags(), source.skills(), media, blocks);
    }

    private static ProjectWorkspaceDto withBlocks(
            ProjectWorkspaceDto source, List<ContentBlockDto> blocks) {
        return new ProjectWorkspaceDto(
                source.id(), source.externalKey(), source.slug(), source.number(),
                source.sortOrder(), source.featured(), source.visible(),
                source.publicationDirty(), source.version(), source.translations(),
                source.tags(), source.skills(), source.media(), blocks);
    }

    private static ContentBlockDto block(ContentBlockDto.Payload payload) {
        return new ContentBlockDto(
                UUID.randomUUID(),
                0,
                true,
                ContentBlockDto.Width.STANDARD,
                ContentBlockDto.Alignment.LEFT,
                ContentBlockDto.Emphasis.NONE,
                1,
                payload);
    }

    private static Map<LocaleCode, ContentBlockDto.BlockCopy> blockCopy() {
        ContentBlockDto.BlockCopy copy = new ContentBlockDto.BlockCopy("Title", "Description");
        return Map.of(LocaleCode.ZH_CN, copy, LocaleCode.EN, copy);
    }

    private static Map<LocaleCode, ContentBlockDto.ActionCopy> actionCopy() {
        ContentBlockDto.ActionCopy copy =
                new ContentBlockDto.ActionCopy("Download", "Description");
        return Map.of(LocaleCode.ZH_CN, copy, LocaleCode.EN, copy);
    }

    private static ProjectWorkspaceDto withExternalKey(
            ProjectWorkspaceDto source, String externalKey) {
        return new ProjectWorkspaceDto(
                source.id(), externalKey, source.slug(), source.number(), source.sortOrder(),
                source.featured(), source.visible(), source.publicationDirty(), source.version(),
                source.translations(), source.tags(), source.skills(), source.media(), source.blocks());
    }

    private static ProjectWorkspaceDto copyRoot(
            ProjectWorkspaceDto source,
            UUID id,
            long version,
            boolean publicationDirty) {
        return new ProjectWorkspaceDto(
                id, source.externalKey(), source.slug(), source.number(), source.sortOrder(),
                source.featured(), source.visible(), publicationDirty, version,
                source.translations(), source.tags(), source.skills(), source.media(), source.blocks());
    }
}
