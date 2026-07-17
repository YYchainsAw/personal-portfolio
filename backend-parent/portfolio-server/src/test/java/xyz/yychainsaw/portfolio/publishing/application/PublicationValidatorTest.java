package xyz.yychainsaw.portfolio.publishing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.content.api.ContentBlockDto;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.content.api.ProjectWorkspaceDto;
import xyz.yychainsaw.portfolio.content.api.SiteWorkspaceDto;
import xyz.yychainsaw.portfolio.content.application.WorkspaceValidator;
import xyz.yychainsaw.portfolio.content.persistence.support.ContentPersistenceFixtures;
import xyz.yychainsaw.portfolio.content.support.WorkspaceFixtures;
import xyz.yychainsaw.portfolio.media.application.MediaAssetDescriptor;
import xyz.yychainsaw.portfolio.media.application.MediaCopyDescriptor;
import xyz.yychainsaw.portfolio.media.application.MediaQueryService;
import xyz.yychainsaw.portfolio.media.application.MediaVariantDescriptor;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;
import xyz.yychainsaw.portfolio.publishing.persistence.PublishingRepository;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.LocaleV1;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.ProjectCatalogSnapshotMapperV1;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.ProjectCatalogSnapshotV1;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.ProjectSnapshotMapperV1;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.ProjectSnapshotV1;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.PublishedMediaV1;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.SiteSnapshotMapperV1;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.SiteSnapshotV1;

/**
 * Publication-only rules intentionally live above the draft-valid workspace rules. These tests
 * use the real editable DTOs and V1 snapshot contracts so a reflective or request-metadata-based
 * implementation cannot accidentally satisfy the contract.
 */
class PublicationValidatorTest {
    private static final UUID SITE_HERO_ASSET =
            uuid("71000000-0000-4000-8000-000000000001");
    private static final UUID SITE_ZH_RESUME_ASSET =
            uuid("71000000-0000-4000-8000-000000000002");
    private static final UUID SITE_EN_RESUME_ASSET =
            uuid("71000000-0000-4000-8000-000000000003");
    private static final UUID REFERENCE_FIRST_ASSET =
            uuid("10000000-0000-4000-8000-000000000901");
    private static final UUID REFERENCE_LAST_ASSET =
            uuid("f0000000-0000-4000-8000-000000000902");

    private static final String READY = "READY";
    @Test
    void delegatesProjectWorkspaceValidationBeforeReadingTheSnapshotOrMedia() {
        RecordingMediaQueryService media = new RecordingMediaQueryService();
        ProjectWorkspaceDto invalidDraft = WorkspaceFixtures.projectBuilder()
                .slug("Invalid Slug")
                .build();

        DomainException failure = failure(() -> validator(media)
                .validateProject(invalidDraft, null));

        assertThat(failure.code()).isEqualTo("CONTENT_BLOCK_INVALID");
        assertThat(failure.fieldErrors()).containsKey("slug");
        assertThat(media.assetRequests).isEmpty();
        assertThat(media.variantRequests).isEmpty();
    }

    @Test
    void delegatesSiteWorkspaceValidationBeforeReadingTheSnapshotOrMedia() {
        RecordingMediaQueryService media = new RecordingMediaQueryService();
        SiteWorkspaceDto valid = publishableSite();
        SiteWorkspaceDto invalidDraft = copySite(
                valid,
                " ",
                valid.email(),
                valid.identity(),
                valid.seo(),
                valid.accessibility(),
                valid.navigation(),
                valid.hero(),
                valid.about(),
                valid.facts(),
                valid.profileSkills(),
                valid.work(),
                valid.roadmap(),
                valid.contact(),
                valid.privacy(),
                valid.resumes());

        DomainException failure = failure(() -> validator(media)
                .validateSite(invalidDraft, null));

        assertThat(failure.code()).isEqualTo("SITE_WORKSPACE_INVALID");
        assertThat(failure.fieldErrors()).containsKey("monogram");
        assertThat(media.assetRequests).isEmpty();
        assertThat(media.variantRequests).isEmpty();
    }

    @Test
    void siteWorkspacePreflightPreservesWorkspaceValidationPriorityWithoutMediaLookup() {
        RecordingMediaQueryService media = new RecordingMediaQueryService();
        SiteWorkspaceDto placeholderSite = siteWithAllFixedPlaceholders();
        SiteWorkspaceDto invalidDraft = copySite(
                placeholderSite,
                " ",
                placeholderSite.email(),
                placeholderSite.identity(),
                placeholderSite.seo(),
                placeholderSite.accessibility(),
                placeholderSite.navigation(),
                placeholderSite.hero(),
                placeholderSite.about(),
                placeholderSite.facts(),
                placeholderSite.profileSkills(),
                placeholderSite.work(),
                placeholderSite.roadmap(),
                placeholderSite.contact(),
                placeholderSite.privacy(),
                placeholderSite.resumes());

        DomainException failure = failure(() -> validator(media)
                .validateSiteWorkspace(invalidDraft));

        assertThat(failure.code()).isEqualTo("SITE_WORKSPACE_INVALID");
        assertThat(failure.fieldErrors()).containsKey("monogram");
        assertThat(media.assetRequests).isEmpty();
        assertThat(media.variantRequests).isEmpty();
    }

    @Test
    void projectWorkspacePreflightRejectsInvalidMediaFieldsWithoutMediaLookup() {
        RecordingMediaQueryService media = new RecordingMediaQueryService();
        ProjectWorkspaceDto base = ContentPersistenceFixtures.projectWithAllPayloads();
        ProjectWorkspaceDto.ProjectMedia invalidCover = new ProjectWorkspaceDto.ProjectMedia(
                ContentPersistenceFixtures.PROJECT_ASSET_IDS.get(0),
                "COVER",
                0,
                " ",
                "",
                " ",
                URI.create("http://example.test/not-publishable"));
        ProjectWorkspaceDto project = copyProject(
                base,
                base.number(),
                base.translations(),
                List.of(invalidCover),
                base.blocks());

        DomainException failure = failure(() -> validator(media)
                .validateProjectWorkspace(project));

        assertThat(failure.code()).isEqualTo("PROJECT_NOT_PUBLISHABLE");
        assertThat(failure.fieldErrors()).containsOnlyKeys(
                "media[0].layout",
                "media[0].objectPosition",
                "media[0].credit",
                "media[0].sourceUrl");
        assertThat(media.assetRequests).isEmpty();
        assertThat(media.variantRequests).isEmpty();
    }

    @Test
    void rejectsOnlyTheSevenExactTaskFivePlaceholderPathValuePairs() {
        RecordingMediaQueryService media = readySiteMedia();
        SiteWorkspaceDto site = siteWithAllFixedPlaceholders();
        SiteSnapshotV1 snapshot = new SiteSnapshotMapperV1(media).toSnapshot(site);

        DomainException failure = failure(() -> validator(media)
                .validateSite(site, snapshot));

        assertThat(failure.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(failure.code()).isEqualTo("PLACEHOLDER_CONTENT_PRESENT");
        assertThat(failure.fieldErrors()).containsOnlyKeys(
                "identity.email",
                "portfolioContent.zh-CN.hero.visualLabel",
                "portfolioContent.en.hero.visualLabel",
                "portfolioContent.zh-CN.work.imageNotice",
                "portfolioContent.en.work.imageNotice",
                "portfolioContent.zh-CN.contact.emailLabel",
                "portfolioContent.en.contact.emailLabel");
    }

    @Test
    void siteTypedWalkRejectsEveryBlankTranslatedLeafIncludingNestedItems() {
        RecordingMediaQueryService media = readySiteMedia();
        SiteWorkspaceDto site = siteWithEveryTranslatedLeafBlank();
        SiteSnapshotV1 snapshot = new SiteSnapshotMapperV1(media).toSnapshot(site);

        DomainException failure = failure(() -> validator(media)
                .validateSite(site, snapshot));

        assertThat(failure.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(failure.code()).isEqualTo("SITE_NOT_PUBLISHABLE");
        assertThat(failure.fieldErrors())
                .containsOnlyKeys(expectedSiteTranslatedLeafPaths().toArray(String[]::new));
    }

    @Test
    void projectTypedWalkRejectsEveryBlankTranslatedLeafIncludingHiddenBlockCopies() {
        RecordingMediaQueryService media = readyProjectMedia();
        ProjectWorkspaceDto project = projectWithEveryTranslatedLeafBlank();
        ProjectSnapshotV1 snapshot = new ProjectSnapshotMapperV1(media).toSnapshot(project);

        DomainException failure = failure(() -> validator(media)
                .validateProject(project, snapshot));

        assertThat(failure.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(failure.code()).isEqualTo("PROJECT_NOT_PUBLISHABLE");
        assertThat(failure.fieldErrors())
                .containsOnlyKeys(expectedProjectTranslatedLeafPaths().toArray(String[]::new));
    }

    @Test
    void siteRequiresOneCurrentResumeForEachSupportedLocale() {
        RecordingMediaQueryService media = readySiteMedia();
        SiteWorkspaceDto valid = publishableSite();
        SiteWorkspaceDto.ResumeDocument english = valid.resumes().stream()
                .filter(resume -> resume.locale() == LocaleCode.EN)
                .findFirst()
                .orElseThrow();
        SiteWorkspaceDto site = copySiteWithResumes(valid, List.of(english));
        SiteSnapshotV1 snapshot = new SiteSnapshotMapperV1(media).toSnapshot(site);

        DomainException failure = failure(() -> validator(media)
                .validateSite(site, snapshot));

        assertThat(failure.code()).isEqualTo("SITE_NOT_PUBLISHABLE");
        assertThat(failure.fieldErrors()).containsOnlyKeys("resumes.zh-CN.current");
    }

    @Test
    void projectRequiresCardNumberSeoCoverAndPublishableCoverFields() {
        RecordingMediaQueryService media = readyProjectMedia();
        ProjectWorkspaceDto base = ContentPersistenceFixtures.projectWithAllPayloads();
        Map<LocaleCode, ProjectWorkspaceDto.ProjectCopy> translations = new LinkedHashMap<>();
        base.translations().forEach((locale, copy) -> translations.put(
                locale,
                new ProjectWorkspaceDto.ProjectCopy(
                        copy.status(), copy.eyebrow(), copy.title(), copy.summary(), " ", "")));
        ProjectWorkspaceDto.ProjectMedia cover = new ProjectWorkspaceDto.ProjectMedia(
                ContentPersistenceFixtures.PROJECT_ASSET_IDS.get(0),
                "COVER",
                0,
                " ",
                "",
                " ",
                URI.create("http://example.test/not-publishable"));
        ProjectWorkspaceDto project = copyProject(
                base, " ", translations, List.of(cover), base.blocks());
        ProjectSnapshotV1 snapshot = new ProjectSnapshotMapperV1(media).toSnapshot(project);

        DomainException failure = failure(() -> validator(media)
                .validateProject(project, snapshot));

        assertThat(failure.code()).isEqualTo("PROJECT_NOT_PUBLISHABLE");
        assertThat(failure.fieldErrors()).containsOnlyKeys(
                "number",
                "translations.zh-CN.seoTitle",
                "translations.zh-CN.seoDescription",
                "translations.en.seoTitle",
                "translations.en.seoDescription",
                "media[0].layout",
                "media[0].objectPosition",
                "media[0].credit",
                "media[0].sourceUrl");

        ProjectWorkspaceDto missingCover = copyProject(
                base, base.number(), base.translations(), List.of(), base.blocks());
        ProjectSnapshotV1 noCoverSnapshot =
                new ProjectSnapshotMapperV1(media).toSnapshot(missingCover);
        DomainException missingCoverFailure = failure(() -> validator(media)
                .validateProject(missingCover, noCoverSnapshot));
        assertThat(missingCoverFailure.code()).isEqualTo("PROJECT_NOT_PUBLISHABLE");
        assertThat(missingCoverFailure.fieldErrors()).containsOnlyKeys("media.cover");
    }

    @Test
    void projectRejectsBlankCodeLanguageAndMissingMetricNumberAsSubtypeErrors() {
        RecordingMediaQueryService media = new RecordingMediaQueryService()
                .put(imageDescriptor(REFERENCE_FIRST_ASSET));
        ProjectWorkspaceDto base = WorkspaceFixtures.projectBuilder()
                .media(List.of(cover(REFERENCE_FIRST_ASSET)))
                .blocks(List.of(
                        block(0, new ContentBlockDto.CodePayload(
                                " ", "", true, blockCopy())),
                        block(1, new ContentBlockDto.MetricsPayload(List.of(
                                new ContentBlockDto.Metric(
                                        uuid("72000000-0000-4000-8000-000000000001"),
                                        0,
                                        null,
                                        metricCopy()))))))
                .build();
        ProjectSnapshotV1 snapshot = new ProjectSnapshotMapperV1(media).toSnapshot(base);

        DomainException failure = failure(() -> validator(media)
                .validateProject(base, snapshot));

        assertThat(failure.code()).isEqualTo("PROJECT_NOT_PUBLISHABLE");
        assertThat(failure.fieldErrors()).containsOnlyKeys(
                "blocks[0].code",
                "blocks[0].language",
                "blocks[1].metrics[0].numericValue");
    }

    @Test
    void rejectsNonReadyAssetsWithMediaNotReady() {
        RecordingMediaQueryService ready = readyProjectMedia();
        ProjectWorkspaceDto project = ContentPersistenceFixtures.projectWithAllPayloads();
        ProjectSnapshotV1 snapshot = new ProjectSnapshotMapperV1(ready).toSnapshot(project);
        UUID coverId = ContentPersistenceFixtures.PROJECT_ASSET_IDS.get(0);
        RecordingMediaQueryService changed = readyProjectMedia();
        changed.put(withAssetStatus(changed.descriptors.get(coverId), "PROCESSING"));

        DomainException failure = failure(() -> validator(changed)
                .validateProject(project, snapshot));

        assertThat(failure.code()).isEqualTo("MEDIA_NOT_READY");
        assertThat(failure.fieldErrors()).containsOnlyKeys(
                mediaPath(coverId) + ".status");
    }

    @Test
    void rejectsNonReadySelectedVariantsWithMediaNotReady() {
        RecordingMediaQueryService ready = readyProjectMedia();
        ProjectWorkspaceDto project = ContentPersistenceFixtures.projectWithAllPayloads();
        ProjectSnapshotV1 snapshot = new ProjectSnapshotMapperV1(ready).toSnapshot(project);
        UUID imageId = ContentPersistenceFixtures.PROJECT_ASSET_IDS.get(1);
        RecordingMediaQueryService changed = readyProjectMedia();
        changed.put(withVariantStatus(changed.descriptors.get(imageId), "w640", "PROCESSING"));

        DomainException failure = failure(() -> validator(changed)
                .validateProject(project, snapshot));

        assertThat(failure.code()).isEqualTo("MEDIA_NOT_READY");
        assertThat(failure.fieldErrors()).containsOnlyKeys(
                mediaPath(imageId) + ".variants.w640.status");
    }

    @Test
    void requiresBilingualAltAndHttpsForEveryNonblankMediaSourceUrl() {
        UUID coverId = ContentPersistenceFixtures.PROJECT_ASSET_IDS.get(0);
        RecordingMediaQueryService media = readyProjectMedia();
        MediaAssetDescriptor cover = media.descriptors.get(coverId);
        Map<String, MediaCopyDescriptor> copy = new LinkedHashMap<>(cover.copyByLocale());
        copy.put("en", new MediaCopyDescriptor(
                " ", "English caption", "English credit", "https://example.test/en/source"));
        copy.put("zh-CN", new MediaCopyDescriptor(
                "封面替代文本", "中文说明", "中文署名", "http://example.test/unsafe"));
        media.put(copyDescriptor(cover, copy));
        ProjectWorkspaceDto project = ContentPersistenceFixtures.projectWithAllPayloads();
        ProjectSnapshotV1 snapshot = new ProjectSnapshotMapperV1(media).toSnapshot(project);

        DomainException failure = failure(() -> validator(media)
                .validateProject(project, snapshot));

        assertThat(failure.code()).isEqualTo("MEDIA_TRANSLATION_INCOMPLETE");
        assertThat(failure.fieldErrors()).containsOnlyKeys(
                mediaPath(coverId) + ".copy.en.alt",
                mediaPath(coverId) + ".copy.zh-CN.sourceUrl");
    }

    @Test
    void missingWholeMediaLocaleReportsEveryMediaCopyLeaf() {
        UUID coverId = ContentPersistenceFixtures.PROJECT_ASSET_IDS.get(0);
        RecordingMediaQueryService media = readyProjectMedia();
        MediaAssetDescriptor cover = media.descriptors.get(coverId);
        Map<String, MediaCopyDescriptor> copy = new LinkedHashMap<>(cover.copyByLocale());
        copy.remove("en");
        media.put(copyDescriptor(cover, copy));
        ProjectWorkspaceDto project = ContentPersistenceFixtures.projectWithAllPayloads();
        ProjectSnapshotV1 snapshot = new ProjectSnapshotMapperV1(media).toSnapshot(project);

        DomainException failure = failure(() -> validator(media)
                .validateProject(project, snapshot));

        assertThat(failure.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(failure.code()).isEqualTo("MEDIA_TRANSLATION_INCOMPLETE");
        assertThat(failure.fieldErrors()).containsOnlyKeys(
                mediaPath(coverId) + ".copy.en.alt",
                mediaPath(coverId) + ".copy.en.caption",
                mediaPath(coverId) + ".copy.en.credit",
                mediaPath(coverId) + ".copy.en.sourceUrl");
    }

    @Test
    void comparesEveryMediaCopyFieldAgainstTheLockedDescriptor() {
        RecordingMediaQueryService media = readyProjectMedia();
        ProjectWorkspaceDto project = ContentPersistenceFixtures.projectWithAllPayloads();
        ProjectSnapshotV1 snapshot = new ProjectSnapshotMapperV1(media).toSnapshot(project);
        UUID assetId = ContentPersistenceFixtures.PROJECT_ASSET_IDS.get(1);
        ProjectSnapshotV1 mismatched = replaceProjectMedia(snapshot, assetId, published -> {
            Map<LocaleV1, PublishedMediaV1.MediaCopy> copy =
                    new LinkedHashMap<>(published.copy());
            copy.put(LocaleV1.EN, new PublishedMediaV1.MediaCopy(
                    "wrong alt", "wrong caption", "wrong credit", "https://wrong.example.test"));
            return new PublishedMediaV1(
                    published.assetId(),
                    published.contentType(),
                    published.contentLength(),
                    published.sha256(),
                    copy,
                    published.variants());
        });

        DomainException failure = failure(() -> validator(media)
                .validateProject(project, mismatched));

        assertThat(failure.code()).isEqualTo("MEDIA_TRANSLATION_INCOMPLETE");
        assertThat(failure.fieldErrors()).containsOnlyKeys(
                mediaPath(assetId) + ".copy.en.alt",
                mediaPath(assetId) + ".copy.en.caption",
                mediaPath(assetId) + ".copy.en.credit",
                mediaPath(assetId) + ".copy.en.sourceUrl");
    }

    @Test
    void comparesAssetSummaryVariantSetAndEveryVariantField() {
        RecordingMediaQueryService media = readyProjectMedia();
        ProjectWorkspaceDto project = ContentPersistenceFixtures.projectWithAllPayloads();
        ProjectSnapshotV1 snapshot = new ProjectSnapshotMapperV1(media).toSnapshot(project);
        UUID assetId = ContentPersistenceFixtures.PROJECT_ASSET_IDS.get(1);
        ProjectSnapshotV1 mismatched = replaceProjectMedia(snapshot, assetId, published -> {
            PublishedMediaV1.Variant alpha = published.variants().stream()
                    .filter(variant -> variant.name().equals("w640"))
                    .findFirst()
                    .orElseThrow();
            PublishedMediaV1.Variant changedAlpha = new PublishedMediaV1.Variant(
                    alpha.name(),
                    alpha.width() + 1,
                    alpha.height() + 1,
                    alpha.bytes() + 1,
                    "f".repeat(64));
            return new PublishedMediaV1(
                    published.assetId(),
                    "image/jpeg",
                    published.contentLength() + 1,
                    "e".repeat(64),
                    published.copy(),
                    List.of(changedAlpha));
        });

        DomainException failure = failure(() -> validator(media)
                .validateProject(project, mismatched));

        assertThat(failure.code()).isEqualTo("MEDIA_NOT_READY");
        assertThat(failure.fieldErrors()).containsOnlyKeys(
                mediaPath(assetId) + ".contentType",
                mediaPath(assetId) + ".contentLength",
                mediaPath(assetId) + ".sha256",
                mediaPath(assetId) + ".variants",
                mediaPath(assetId) + ".variants.w640.width",
                mediaPath(assetId) + ".variants.w640.height",
                mediaPath(assetId) + ".variants.w640.bytes",
                mediaPath(assetId) + ".variants.w640.sha256");
    }

    @Test
    void mediaBackedDownloadVerifiesItsDescriptorAndSelectedVariant() {
        RecordingMediaQueryService media = readyProjectMedia();
        ProjectWorkspaceDto project = ContentPersistenceFixtures.projectWithAllPayloads();
        ProjectSnapshotV1 snapshot = new ProjectSnapshotMapperV1(media).toSnapshot(project);
        UUID downloadId = ContentPersistenceFixtures.PROJECT_ASSET_IDS.get(5);

        List<PublishingRepository.MediaReferenceRow> references =
                validator(media).validateProject(project, snapshot);

        assertThat(references).contains(new PublishingRepository.MediaReferenceRow(
                downloadId, "document", "BLOCK_DOWNLOAD"));

        ProjectSnapshotV1 mismatched = replaceProjectMedia(snapshot, downloadId, published -> {
            PublishedMediaV1.Variant document = published.variants().get(0);
            return new PublishedMediaV1(
                    published.assetId(),
                    "application/octet-stream",
                    published.contentLength() + 1,
                    "f".repeat(64),
                    published.copy(),
                    List.of(new PublishedMediaV1.Variant(
                            document.name(),
                            document.width() + 1,
                            document.height() + 1,
                            document.bytes() + 1,
                            "e".repeat(64))));
        });
        DomainException failure = failure(() -> validator(media)
                .validateProject(project, mismatched));
        assertThat(failure.code()).isEqualTo("MEDIA_NOT_READY");
        assertThat(failure.fieldErrors()).containsOnlyKeys(
                mediaPath(downloadId) + ".contentType",
                mediaPath(downloadId) + ".contentLength",
                mediaPath(downloadId) + ".sha256",
                mediaPath(downloadId) + ".variants.document.width",
                mediaPath(downloadId) + ".variants.document.height",
                mediaPath(downloadId) + ".variants.document.bytes",
                mediaPath(downloadId) + ".variants.document.sha256");
    }

    @Test
    void externalHttpsDownloadDoesNotResolveOrRetainMedia() {
        UUID coverId = ContentPersistenceFixtures.PROJECT_ASSET_IDS.get(0);
        RecordingMediaQueryService media = new RecordingMediaQueryService()
                .put(imageDescriptor(coverId));
        ProjectWorkspaceDto base = ContentPersistenceFixtures.projectWithAllPayloads();
        ContentBlockDto externalDownload = block(
                0,
                new ContentBlockDto.DownloadPayload(
                        null,
                        URI.create("https://downloads.example.test/game.zip"),
                        actionCopy()));
        ProjectWorkspaceDto project = copyProject(
                base, base.number(), base.translations(), base.media(), List.of(externalDownload));
        ProjectSnapshotV1 snapshot = new ProjectSnapshotMapperV1(media).toSnapshot(project);
        media.clearRequests();

        List<PublishingRepository.MediaReferenceRow> references =
                validator(media).validateProject(project, snapshot);

        assertThat(references)
                .noneMatch(reference -> reference.usage().equals("BLOCK_DOWNLOAD"));
        assertThat(media.assetRequests).containsOnly(coverId);
        assertThat(snapshot.media()).extracting(PublishedMediaV1::assetId)
                .containsExactly(coverId);
    }

    @Test
    void mediaReferencesDeduplicateRepeatedUsesAndSortByAssetVariantThenUsage() {
        RecordingMediaQueryService media = new RecordingMediaQueryService()
                .put(imageDescriptor(REFERENCE_LAST_ASSET))
                .put(imageDescriptor(REFERENCE_FIRST_ASSET));
        List<ProjectWorkspaceDto.ProjectMedia> projectMedia = List.of(
                projectMedia(REFERENCE_LAST_ASSET, "COVER", 0),
                projectMedia(REFERENCE_LAST_ASSET, "CARD", 0),
                projectMedia(REFERENCE_FIRST_ASSET, "DETAIL", 0));
        List<ContentBlockDto> blocks = List.of(
                block(0, new ContentBlockDto.ImagePayload(REFERENCE_LAST_ASSET)),
                block(1, new ContentBlockDto.ImagePayload(REFERENCE_LAST_ASSET)),
                block(2, new ContentBlockDto.GalleryPayload(
                        List.of(REFERENCE_LAST_ASSET, REFERENCE_LAST_ASSET))),
                block(3, new ContentBlockDto.VideoPayload(
                        "YOUTUBE",
                        URI.create("https://example.test/video"),
                        REFERENCE_LAST_ASSET,
                        blockCopy())),
                block(4, new ContentBlockDto.DownloadPayload(
                        REFERENCE_LAST_ASSET, null, actionCopy())),
                block(5, new ContentBlockDto.ImagePayload(REFERENCE_FIRST_ASSET)));
        ProjectWorkspaceDto project = WorkspaceFixtures.projectBuilder()
                .media(projectMedia)
                .blocks(blocks)
                .build();
        ProjectSnapshotV1 snapshot = new ProjectSnapshotMapperV1(media).toSnapshot(project);

        List<PublishingRepository.MediaReferenceRow> references =
                validator(media).validateProject(project, snapshot);

        List<PublishingRepository.MediaReferenceRow> expected = new ArrayList<>();
        addExpectedReferences(
                expected,
                REFERENCE_FIRST_ASSET,
                List.of("DETAIL", "BLOCK_IMAGE"));
        addExpectedReferences(
                expected,
                REFERENCE_LAST_ASSET,
                List.of(
                        "COVER",
                        "CARD",
                        "BLOCK_IMAGE",
                        "BLOCK_GALLERY",
                        "BLOCK_VIDEO_COVER",
                        "BLOCK_DOWNLOAD"));
        expected.sort(referenceOrder());

        assertThat(references).containsExactlyElementsOf(expected);
    }

    @Test
    void catalogCoverValidationReturnsDeduplicatedReferencesAndChecksReadySummary() {
        RecordingMediaQueryService media = readyProjectMedia();
        ProjectWorkspaceDto project = ContentPersistenceFixtures.projectWithAllPayloads();
        ProjectSnapshotV1 projectSnapshot =
                new ProjectSnapshotMapperV1(media).toSnapshot(project);
        ProjectCatalogSnapshotV1 catalog =
                new ProjectCatalogSnapshotMapperV1().fromCurrentProjects(
                        List.of(projectSnapshot, projectSnapshot));
        UUID coverId = ContentPersistenceFixtures.PROJECT_ASSET_IDS.get(0);

        List<PublishingRepository.MediaReferenceRow> references =
                validator(media).validateCatalog(catalog);

        assertThat(references).containsExactly(
                new PublishingRepository.MediaReferenceRow(
                        coverId, "w1280", "CATALOG_COVER"),
                new PublishingRepository.MediaReferenceRow(
                        coverId, "w640", "CATALOG_COVER"));

        ProjectCatalogSnapshotV1.Card card = catalog.projects().get(0);
        PublishedMediaV1 cover = card.cover();
        PublishedMediaV1 mismatchedCover = new PublishedMediaV1(
                cover.assetId(),
                cover.contentType(),
                cover.contentLength() + 1,
                cover.sha256(),
                cover.copy(),
                cover.variants());
        ProjectCatalogSnapshotV1 invalidCatalog = new ProjectCatalogSnapshotV1(
                1,
                List.of(new ProjectCatalogSnapshotV1.Card(
                        card.projectId(),
                        card.slug(),
                        card.number(),
                        card.sortOrder(),
                        card.featured(),
                        card.copy(),
                        mismatchedCover)));
        DomainException failure = failure(() -> validator(media)
                .validateCatalog(invalidCatalog));
        assertThat(failure.code()).isEqualTo("MEDIA_NOT_READY");
        assertThat(failure.fieldErrors())
                .containsKey(mediaPath(coverId) + ".contentLength");
    }

    @Test
    void catalogTypedWalkRejectsMissingLocaleAndBlankCardFields() {
        RecordingMediaQueryService media = readyProjectMedia();
        ProjectWorkspaceDto project = ContentPersistenceFixtures.projectWithAllPayloads();
        ProjectSnapshotV1 projectSnapshot =
                new ProjectSnapshotMapperV1(media).toSnapshot(project);
        ProjectCatalogSnapshotV1 catalog =
                new ProjectCatalogSnapshotMapperV1().fromCurrentProjects(
                        List.of(projectSnapshot));
        ProjectCatalogSnapshotV1.Card card = catalog.projects().get(0);
        ProjectCatalogSnapshotV1.CardCopy blankChineseCopy =
                new ProjectCatalogSnapshotV1.CardCopy(
                        " ", "\t", "", " ", List.of(" "));
        ProjectCatalogSnapshotV1 invalidCatalog = new ProjectCatalogSnapshotV1(
                1,
                List.of(new ProjectCatalogSnapshotV1.Card(
                        null,
                        " ",
                        "\t",
                        card.sortOrder(),
                        card.featured(),
                        Map.of(LocaleV1.ZH_CN, blankChineseCopy),
                        card.cover())));

        DomainException failure = failure(() -> validator(media)
                .validateCatalog(invalidCatalog));

        assertThat(failure.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(failure.code()).isEqualTo("PROJECT_NOT_PUBLISHABLE");
        assertThat(failure.fieldErrors()).containsOnlyKeys(
                "projects[0].projectId",
                "projects[0].slug",
                "projects[0].number",
                "projects[0].copy.zh-CN.status",
                "projects[0].copy.zh-CN.eyebrow",
                "projects[0].copy.zh-CN.title",
                "projects[0].copy.zh-CN.summary",
                "projects[0].copy.zh-CN.tags[0]",
                "projects[0].copy.en");
    }

    @Test
    void catalogStructurePreflightChecksOnlyCardFieldsWithoutMediaLookup() {
        RecordingMediaQueryService media = new RecordingMediaQueryService();
        ProjectCatalogSnapshotV1.CardCopy blankChineseCopy =
                new ProjectCatalogSnapshotV1.CardCopy(
                        " ", "\t", "", " ", List.of(" "));
        ProjectCatalogSnapshotV1 invalidCatalog = new ProjectCatalogSnapshotV1(
                1,
                List.of(new ProjectCatalogSnapshotV1.Card(
                        null,
                        " ",
                        "\t",
                        0,
                        false,
                        Map.of(LocaleV1.ZH_CN, blankChineseCopy),
                        null)));

        DomainException failure = failure(() -> validator(media)
                .validateCatalogStructure(invalidCatalog));

        assertThat(failure.code()).isEqualTo("PROJECT_NOT_PUBLISHABLE");
        assertThat(failure.fieldErrors()).containsOnlyKeys(
                "projects[0].projectId",
                "projects[0].slug",
                "projects[0].number",
                "projects[0].copy.zh-CN.status",
                "projects[0].copy.zh-CN.eyebrow",
                "projects[0].copy.zh-CN.title",
                "projects[0].copy.zh-CN.summary",
                "projects[0].copy.zh-CN.tags[0]",
                "projects[0].copy.en");
        assertThat(media.assetRequests).isEmpty();
        assertThat(media.variantRequests).isEmpty();
    }

    private static PublicationValidator validator(MediaQueryService media) {
        return new PublicationValidator(new WorkspaceValidator(), media);
    }

    private static SiteWorkspaceDto publishableSite() {
        SiteWorkspaceDto base = WorkspaceFixtures.site(3L);
        SiteWorkspaceDto.Hero sourceHero = base.hero();
        SiteWorkspaceDto.Hero hero = new SiteWorkspaceDto.Hero(
                sourceHero.id(),
                sourceHero.version(),
                SITE_HERO_ASSET,
                "50% 50%",
                "Portfolio portrait",
                URI.create("https://example.test/portrait"),
                sourceHero.copy());
        List<SiteWorkspaceDto.ResumeDocument> resumes = List.of(
                new SiteWorkspaceDto.ResumeDocument(
                        uuid("71000000-0000-4000-8000-000000000011"),
                        LocaleCode.ZH_CN,
                        SITE_ZH_RESUME_ASSET,
                        "2026.1",
                        true,
                        LocalDate.of(2026, 7, 17)),
                new SiteWorkspaceDto.ResumeDocument(
                        uuid("71000000-0000-4000-8000-000000000012"),
                        LocaleCode.EN,
                        SITE_EN_RESUME_ASSET,
                        "2026.1",
                        true,
                        LocalDate.of(2026, 7, 17)));
        return copySite(
                base,
                base.monogram(),
                "jiaxuan@example.test",
                base.identity(),
                base.seo(),
                base.accessibility(),
                base.navigation(),
                hero,
                base.about(),
                base.facts(),
                base.profileSkills(),
                base.work(),
                base.roadmap(),
                base.contact(),
                base.privacy(),
                resumes);
    }

    private static SiteWorkspaceDto siteWithAllFixedPlaceholders() {
        SiteWorkspaceDto base = publishableSite();
        Map<LocaleCode, SiteWorkspaceDto.HeroCopy> heroCopy = new LinkedHashMap<>();
        base.hero().copy().forEach((locale, copy) -> heroCopy.put(
                locale,
                new SiteWorkspaceDto.HeroCopy(
                        copy.eyebrow(),
                        copy.displayName(),
                        copy.secondaryName(),
                        copy.role(),
                        copy.headline(),
                        copy.introduction(),
                        copy.availability(),
                        copy.primaryCta(),
                        copy.secondaryCta(),
                        locale == LocaleCode.ZH_CN
                                ? "视觉概念图 / 之后替换为本人 UE 截图"
                                : "Visual concept image / replace with my own UE capture",
                        copy.stageLabel())));
        Map<LocaleCode, SiteWorkspaceDto.WorkCopy> work = new LinkedHashMap<>();
        base.work().forEach((locale, copy) -> work.put(
                locale,
                new SiteWorkspaceDto.WorkCopy(
                        copy.label(),
                        copy.title(),
                        copy.introduction(),
                        locale == LocaleCode.ZH_CN
                                ? "概念占位图，之后替换为本人 UE 截图"
                                : "Concept placeholder - to be replaced with my own UE capture",
                        copy.openSlotLabel(),
                        copy.openSlotTitle(),
                        copy.openSlotText(),
                        copy.openSlotMeta())));
        Map<LocaleCode, SiteWorkspaceDto.ContactCopy> contact = new LinkedHashMap<>();
        base.contact().forEach((locale, copy) -> contact.put(
                locale,
                new SiteWorkspaceDto.ContactCopy(
                        copy.label(),
                        copy.title(),
                        copy.introduction(),
                        locale == LocaleCode.ZH_CN ? "联系邮箱（待替换）" : "Email placeholder",
                        copy.workCta(),
                        copy.roadmapCta(),
                        copy.footerNote())));
        SiteWorkspaceDto.Hero hero = new SiteWorkspaceDto.Hero(
                base.hero().id(),
                base.hero().version(),
                base.hero().mediaAssetId(),
                base.hero().objectPosition(),
                base.hero().credit(),
                base.hero().sourceUrl(),
                heroCopy);
        return copySite(
                base,
                base.monogram(),
                "your-email@example.com",
                base.identity(),
                base.seo(),
                base.accessibility(),
                base.navigation(),
                hero,
                base.about(),
                base.facts(),
                base.profileSkills(),
                work,
                base.roadmap(),
                contact,
                base.privacy(),
                base.resumes());
    }

    private static SiteWorkspaceDto siteWithEveryTranslatedLeafBlank() {
        SiteWorkspaceDto base = publishableSite();
        Map<LocaleCode, SiteWorkspaceDto.IdentityCopy> identity = localized(
                new SiteWorkspaceDto.IdentityCopy("", " "),
                new SiteWorkspaceDto.IdentityCopy(" ", ""));
        Map<LocaleCode, SiteWorkspaceDto.SeoCopy> seo = localized(
                new SiteWorkspaceDto.SeoCopy("", " "),
                new SiteWorkspaceDto.SeoCopy(" ", ""));
        Map<LocaleCode, SiteWorkspaceDto.AccessibilityCopy> accessibility = localized(
                blankAccessibility(), blankAccessibility());
        List<SiteWorkspaceDto.NavigationItem> navigation = base.navigation().stream()
                .map(item -> new SiteWorkspaceDto.NavigationItem(
                        item.id(), item.target(), item.sortOrder(), item.visible(), blankStrings()))
                .toList();
        SiteWorkspaceDto.Hero hero = new SiteWorkspaceDto.Hero(
                base.hero().id(),
                base.hero().version(),
                base.hero().mediaAssetId(),
                base.hero().objectPosition(),
                base.hero().credit(),
                base.hero().sourceUrl(),
                localized(blankHeroCopy(), blankHeroCopy()));
        Map<LocaleCode, SiteWorkspaceDto.AboutCopy> about =
                localized(blankAboutCopy(), blankAboutCopy());
        List<SiteWorkspaceDto.ProfileFact> facts = base.facts().stream()
                .map(fact -> new SiteWorkspaceDto.ProfileFact(
                        fact.id(),
                        fact.externalKey(),
                        fact.sortOrder(),
                        localized(
                                new SiteWorkspaceDto.LabelValueCopy("", " "),
                                new SiteWorkspaceDto.LabelValueCopy(" ", ""))))
                .toList();
        List<SiteWorkspaceDto.ProfileSkill> skills = base.profileSkills().stream()
                .map(skill -> new SiteWorkspaceDto.ProfileSkill(
                        skill.id(),
                        skill.externalKey(),
                        skill.sortOrder(),
                        localized(
                                new SiteWorkspaceDto.SkillStatusCopy("", " "),
                                new SiteWorkspaceDto.SkillStatusCopy(" ", ""))))
                .toList();
        Map<LocaleCode, SiteWorkspaceDto.WorkCopy> work =
                localized(blankWorkCopy(), blankWorkCopy());
        List<SiteWorkspaceDto.RoadmapStage> stages = base.roadmap().stages().stream()
                .map(stage -> new SiteWorkspaceDto.RoadmapStage(
                        stage.id(),
                        stage.externalKey(),
                        stage.number(),
                        stage.sortOrder(),
                        stage.visible(),
                        localized(blankStageCopy(), blankStageCopy()),
                        stage.outcomes().stream()
                                .map(outcome -> new SiteWorkspaceDto.RoadmapOutcome(
                                        outcome.id(), outcome.sortOrder(), blankStrings()))
                                .toList()))
                .toList();
        SiteWorkspaceDto.Roadmap roadmap = new SiteWorkspaceDto.Roadmap(
                localized(blankRoadmapHeader(), blankRoadmapHeader()), stages);
        Map<LocaleCode, SiteWorkspaceDto.ContactCopy> contact =
                localized(blankContactCopy(), blankContactCopy());
        Map<LocaleCode, SiteWorkspaceDto.PrivacyCopy> privacy = localized(
                new SiteWorkspaceDto.PrivacyCopy("", " "),
                new SiteWorkspaceDto.PrivacyCopy(" ", ""));
        return copySite(
                base,
                base.monogram(),
                base.email(),
                identity,
                seo,
                accessibility,
                navigation,
                hero,
                about,
                facts,
                skills,
                work,
                roadmap,
                contact,
                privacy,
                base.resumes());
    }

    private static ProjectWorkspaceDto projectWithEveryTranslatedLeafBlank() {
        ProjectWorkspaceDto base = ContentPersistenceFixtures.projectWithAllPayloads();
        Map<LocaleCode, ProjectWorkspaceDto.ProjectCopy> translations = localized(
                blankProjectCopy(), blankProjectCopy());
        List<ProjectWorkspaceDto.TaxonomyRef> tags = base.tags().stream()
                .map(tag -> new ProjectWorkspaceDto.TaxonomyRef(
                        tag.id(), tag.normalizedKey(), tag.sortOrder(), blankStrings()))
                .toList();
        List<ProjectWorkspaceDto.TaxonomyRef> skills = base.skills().stream()
                .map(skill -> new ProjectWorkspaceDto.TaxonomyRef(
                        skill.id(), skill.normalizedKey(), skill.sortOrder(), blankStrings()))
                .toList();
        List<ContentBlockDto> blocks = base.blocks().stream()
                .map(block -> copyBlock(block, blankTranslatedPayload(block.payload())))
                .toList();
        return new ProjectWorkspaceDto(
                base.id(),
                base.externalKey(),
                base.slug(),
                base.number(),
                base.sortOrder(),
                base.featured(),
                base.visible(),
                base.publicationDirty(),
                base.version(),
                translations,
                tags,
                skills,
                base.media(),
                blocks);
    }

    private static ContentBlockDto.Payload blankTranslatedPayload(
            ContentBlockDto.Payload payload) {
        if (payload instanceof ContentBlockDto.MarkdownPayload) {
            return new ContentBlockDto.MarkdownPayload(blankStrings());
        }
        if (payload instanceof ContentBlockDto.ImagePayload image) {
            return image;
        }
        if (payload instanceof ContentBlockDto.GalleryPayload gallery) {
            return gallery;
        }
        if (payload instanceof ContentBlockDto.VideoPayload video) {
            return new ContentBlockDto.VideoPayload(
                    video.provider(), video.url(), video.coverAssetId(), blankBlockCopy());
        }
        if (payload instanceof ContentBlockDto.CodePayload code) {
            return new ContentBlockDto.CodePayload(
                    code.code(), code.language(), code.showLineNumbers(), blankBlockCopy());
        }
        if (payload instanceof ContentBlockDto.QuotePayload) {
            return new ContentBlockDto.QuotePayload(blankQuoteCopy());
        }
        if (payload instanceof ContentBlockDto.MetricsPayload metrics) {
            return new ContentBlockDto.MetricsPayload(metrics.metrics().stream()
                    .map(metric -> new ContentBlockDto.Metric(
                            metric.id(), metric.sortOrder(), metric.numericValue(), blankMetricCopy()))
                    .toList());
        }
        if (payload instanceof ContentBlockDto.DownloadPayload download) {
            return new ContentBlockDto.DownloadPayload(
                    download.mediaAssetId(), download.externalUrl(), blankActionCopy());
        }
        if (payload instanceof ContentBlockDto.LinkPayload link) {
            return new ContentBlockDto.LinkPayload(
                    link.url(), link.openNewTab(), blankActionCopy());
        }
        throw new AssertionError("uncovered payload: " + payload);
    }

    private static Set<String> expectedSiteTranslatedLeafPaths() {
        Set<String> paths = new LinkedHashSet<>();
        addLocaleFields(paths, "identity", "displayName", "secondaryName");
        addLocaleFields(paths, "seo", "title", "description");
        addLocaleFields(
                paths,
                "accessibility",
                "skip",
                "primaryNav",
                "mobileNav",
                "openMenu",
                "closeMenu",
                "language",
                "backToTop",
                "projectTags");
        SiteWorkspaceDto base = publishableSite();
        for (int index = 0; index < base.navigation().size(); index++) {
            addLocaleFields(paths, "navigation[" + index + "].labels", "value");
        }
        addLocaleFields(
                paths,
                "hero.copy",
                "eyebrow",
                "displayName",
                "secondaryName",
                "role",
                "headline",
                "introduction",
                "availability",
                "primaryCta",
                "secondaryCta",
                "visualLabel",
                "stageLabel");
        addLocaleFields(
                paths,
                "about",
                "label",
                "title",
                "statement",
                "focusLabel",
                "focusTitle",
                "focusIntro");
        for (int index = 0; index < base.facts().size(); index++) {
            String prefix = "facts[" + index + "].copy";
            addLocaleFields(paths, prefix, "label");
            addLocaleNamedField(paths, prefix, "value");
        }
        for (int index = 0; index < base.profileSkills().size(); index++) {
            addLocaleFields(
                    paths, "profileSkills[" + index + "].copy", "name", "status");
        }
        addLocaleFields(
                paths,
                "work",
                "label",
                "title",
                "introduction",
                "imageNotice",
                "openSlotLabel",
                "openSlotTitle",
                "openSlotText",
                "openSlotMeta");
        addLocaleFields(paths, "roadmap.header", "label", "title", "introduction");
        for (int stage = 0; stage < base.roadmap().stages().size(); stage++) {
            addLocaleFields(
                    paths,
                    "roadmap.stages[" + stage + "].copy",
                    "period",
                    "title",
                    "summary");
            for (int outcome = 0;
                    outcome < base.roadmap().stages().get(stage).outcomes().size();
                    outcome++) {
                addLocaleFields(
                        paths,
                        "roadmap.stages[" + stage + "].outcomes[" + outcome + "].text",
                        "value");
            }
        }
        addLocaleFields(
                paths,
                "contact",
                "label",
                "title",
                "introduction",
                "emailLabel",
                "workCta",
                "roadmapCta",
                "footerNote");
        addLocaleFields(paths, "privacy", "title", "bodyMarkdown");
        return paths;
    }

    private static Set<String> expectedProjectTranslatedLeafPaths() {
        Set<String> paths = new LinkedHashSet<>();
        addLocaleFields(
                paths,
                "translations",
                "status",
                "eyebrow",
                "title",
                "summary",
                "seoTitle",
                "seoDescription");
        addLocaleFields(paths, "tags[0].names", "value");
        addLocaleFields(paths, "skills[0].names", "value");
        addLocaleFields(paths, "blocks[0].markdown", "value");
        addLocaleFields(paths, "blocks[3].copy", "title", "description");
        addLocaleFields(paths, "blocks[4].copy", "title", "description");
        addLocaleFields(paths, "blocks[5].copy", "quote", "source");
        addLocaleFields(paths, "blocks[6].metrics[0].copy", "label", "suffix");
        addLocaleNamedField(paths, "blocks[6].metrics[0].copy", "value");
        addLocaleFields(paths, "blocks[7].copy", "label", "description");
        addLocaleFields(paths, "blocks[8].copy", "label", "description");
        return paths;
    }

    private static void addLocaleFields(
            Set<String> paths, String prefix, String... fields) {
        for (String locale : List.of("zh-CN", "en")) {
            for (String field : fields) {
                String suffix = "value".equals(field) ? "" : "." + field;
                paths.add(prefix + "." + locale + suffix);
            }
        }
    }

    private static void addLocaleNamedField(Set<String> paths, String prefix, String field) {
        for (String locale : List.of("zh-CN", "en")) {
            paths.add(prefix + "." + locale + "." + field);
        }
    }

    private static RecordingMediaQueryService readySiteMedia() {
        return new RecordingMediaQueryService()
                .put(imageDescriptor(SITE_HERO_ASSET))
                .put(documentDescriptor(SITE_ZH_RESUME_ASSET))
                .put(documentDescriptor(SITE_EN_RESUME_ASSET));
    }

    private static RecordingMediaQueryService readyProjectMedia() {
        RecordingMediaQueryService media = new RecordingMediaQueryService();
        for (int index = 0;
                index < ContentPersistenceFixtures.PROJECT_ASSET_IDS.size();
                index++) {
            UUID assetId = ContentPersistenceFixtures.PROJECT_ASSET_IDS.get(index);
            media.put(index == 5 ? documentDescriptor(assetId) : imageDescriptor(assetId));
        }
        return media;
    }

    private static MediaAssetDescriptor imageDescriptor(UUID assetId) {
        return descriptor(
                assetId,
                "image/png",
                4_096L,
                "a",
                List.of(
                        variant(assetId, "w1280", READY, 1280, 720, 2_048L, "b"),
                        variant(assetId, "w640", READY, 640, 360, 1_024L, "c")));
    }

    private static MediaAssetDescriptor documentDescriptor(UUID assetId) {
        return descriptor(
                assetId,
                "application/pdf",
                8_192L,
                "d",
                List.of(variant(assetId, "document", READY, 0, 0, 8_192L, "d")));
    }

    private static MediaAssetDescriptor descriptor(
            UUID assetId,
            String mimeType,
            long bytes,
            String checksum,
            List<MediaVariantDescriptor> variants) {
        return new MediaAssetDescriptor(
                assetId,
                READY,
                mimeType,
                bytes,
                checksum.repeat(64),
                Map.of(
                        "zh-CN", mediaCopy(assetId, "zh-CN"),
                        "en", mediaCopy(assetId, "en")),
                variants);
    }

    private static MediaCopyDescriptor mediaCopy(UUID assetId, String locale) {
        return new MediaCopyDescriptor(
                "alt-" + locale + "-" + assetId,
                "caption-" + locale + "-" + assetId,
                "credit-" + locale + "-" + assetId,
                "https://example.test/" + locale + "/" + assetId);
    }

    private static MediaVariantDescriptor variant(
            UUID assetId,
            String name,
            String status,
            int width,
            int height,
            long bytes,
            String checksum) {
        return new MediaVariantDescriptor(
                assetId,
                name,
                status,
                StorageProvider.LOCAL,
                "portfolio-test",
                "local",
                "media/" + assetId + "/" + name,
                name.equals("document") ? "application/pdf" : "image/png",
                bytes,
                checksum.repeat(64),
                width,
                height);
    }

    private static MediaAssetDescriptor withAssetStatus(
            MediaAssetDescriptor source, String status) {
        return new MediaAssetDescriptor(
                source.assetId(),
                status,
                source.mimeType(),
                source.byteSize(),
                source.sha256(),
                source.copyByLocale(),
                source.variants());
    }

    private static MediaAssetDescriptor withVariantStatus(
            MediaAssetDescriptor source, String name, String status) {
        List<MediaVariantDescriptor> variants = source.variants().stream()
                .map(variant -> variant.variantName().equals(name)
                        ? new MediaVariantDescriptor(
                                variant.assetId(),
                                variant.variantName(),
                                status,
                                variant.provider(),
                                variant.bucket(),
                                variant.region(),
                                variant.objectKey(),
                                variant.mimeType(),
                                variant.byteSize(),
                                variant.sha256(),
                                variant.width(),
                                variant.height())
                        : variant)
                .toList();
        return new MediaAssetDescriptor(
                source.assetId(),
                source.status(),
                source.mimeType(),
                source.byteSize(),
                source.sha256(),
                source.copyByLocale(),
                variants);
    }

    private static MediaAssetDescriptor copyDescriptor(
            MediaAssetDescriptor source, Map<String, MediaCopyDescriptor> copy) {
        return new MediaAssetDescriptor(
                source.assetId(),
                source.status(),
                source.mimeType(),
                source.byteSize(),
                source.sha256(),
                copy,
                source.variants());
    }

    private static ProjectSnapshotV1 replaceProjectMedia(
            ProjectSnapshotV1 source,
            UUID assetId,
            UnaryOperator<PublishedMediaV1> replacement) {
        List<PublishedMediaV1> media = source.media().stream()
                .map(value -> value.assetId().equals(assetId) ? replacement.apply(value) : value)
                .toList();
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
                media);
    }

    private static ProjectWorkspaceDto.ProjectMedia cover(UUID assetId) {
        return projectMedia(assetId, "COVER", 0);
    }

    private static ProjectWorkspaceDto.ProjectMedia projectMedia(
            UUID assetId, String usage, int sortOrder) {
        return new ProjectWorkspaceDto.ProjectMedia(
                assetId,
                usage,
                sortOrder,
                "wide",
                "50% 50%",
                "Project media credit",
                URI.create("https://example.test/project/" + assetId + "/" + usage));
    }

    private static ContentBlockDto block(
            int sortOrder, ContentBlockDto.Payload payload) {
        return new ContentBlockDto(
                uuid(String.format(
                        "73000000-0000-4000-8000-%012d", sortOrder + 1)),
                sortOrder,
                true,
                ContentBlockDto.Width.STANDARD,
                ContentBlockDto.Alignment.LEFT,
                ContentBlockDto.Emphasis.NONE,
                1,
                payload);
    }

    private static ContentBlockDto copyBlock(
            ContentBlockDto source, ContentBlockDto.Payload payload) {
        return new ContentBlockDto(
                source.id(),
                source.sortOrder(),
                source.visible(),
                source.width(),
                source.alignment(),
                source.emphasis(),
                source.columns(),
                payload);
    }

    private static ProjectWorkspaceDto copyProject(
            ProjectWorkspaceDto source,
            String number,
            Map<LocaleCode, ProjectWorkspaceDto.ProjectCopy> translations,
            List<ProjectWorkspaceDto.ProjectMedia> media,
            List<ContentBlockDto> blocks) {
        return new ProjectWorkspaceDto(
                source.id(),
                source.externalKey(),
                source.slug(),
                number,
                source.sortOrder(),
                source.featured(),
                source.visible(),
                source.publicationDirty(),
                source.version(),
                translations,
                source.tags(),
                source.skills(),
                media,
                blocks);
    }

    private static SiteWorkspaceDto copySiteWithResumes(
            SiteWorkspaceDto source, List<SiteWorkspaceDto.ResumeDocument> resumes) {
        return copySite(
                source,
                source.monogram(),
                source.email(),
                source.identity(),
                source.seo(),
                source.accessibility(),
                source.navigation(),
                source.hero(),
                source.about(),
                source.facts(),
                source.profileSkills(),
                source.work(),
                source.roadmap(),
                source.contact(),
                source.privacy(),
                resumes);
    }

    private static SiteWorkspaceDto copySite(
            SiteWorkspaceDto source,
            String monogram,
            String email,
            Map<LocaleCode, SiteWorkspaceDto.IdentityCopy> identity,
            Map<LocaleCode, SiteWorkspaceDto.SeoCopy> seo,
            Map<LocaleCode, SiteWorkspaceDto.AccessibilityCopy> accessibility,
            List<SiteWorkspaceDto.NavigationItem> navigation,
            SiteWorkspaceDto.Hero hero,
            Map<LocaleCode, SiteWorkspaceDto.AboutCopy> about,
            List<SiteWorkspaceDto.ProfileFact> facts,
            List<SiteWorkspaceDto.ProfileSkill> profileSkills,
            Map<LocaleCode, SiteWorkspaceDto.WorkCopy> work,
            SiteWorkspaceDto.Roadmap roadmap,
            Map<LocaleCode, SiteWorkspaceDto.ContactCopy> contact,
            Map<LocaleCode, SiteWorkspaceDto.PrivacyCopy> privacy,
            List<SiteWorkspaceDto.ResumeDocument> resumes) {
        return new SiteWorkspaceDto(
                source.siteId(),
                source.version(),
                monogram,
                email,
                identity,
                seo,
                accessibility,
                navigation,
                hero,
                about,
                facts,
                profileSkills,
                work,
                roadmap,
                contact,
                privacy,
                source.socialLinks(),
                resumes);
    }

    private static SiteWorkspaceDto.AccessibilityCopy blankAccessibility() {
        return new SiteWorkspaceDto.AccessibilityCopy(
                "", " ", "", " ", "", " ", "", " ");
    }

    private static SiteWorkspaceDto.HeroCopy blankHeroCopy() {
        return new SiteWorkspaceDto.HeroCopy(
                "", " ", "", " ", "", " ", "", " ", "", " ", "");
    }

    private static SiteWorkspaceDto.AboutCopy blankAboutCopy() {
        return new SiteWorkspaceDto.AboutCopy("", " ", "", " ", "", " ");
    }

    private static SiteWorkspaceDto.WorkCopy blankWorkCopy() {
        return new SiteWorkspaceDto.WorkCopy("", " ", "", " ", "", " ", "", " ");
    }

    private static SiteWorkspaceDto.RoadmapHeaderCopy blankRoadmapHeader() {
        return new SiteWorkspaceDto.RoadmapHeaderCopy("", " ", "");
    }

    private static SiteWorkspaceDto.RoadmapStageCopy blankStageCopy() {
        return new SiteWorkspaceDto.RoadmapStageCopy("", " ", "");
    }

    private static SiteWorkspaceDto.ContactCopy blankContactCopy() {
        return new SiteWorkspaceDto.ContactCopy("", " ", "", " ", "", " ", "");
    }

    private static ProjectWorkspaceDto.ProjectCopy blankProjectCopy() {
        return new ProjectWorkspaceDto.ProjectCopy("", " ", "", " ", "", " ");
    }

    private static Map<LocaleCode, ContentBlockDto.BlockCopy> blockCopy() {
        return localized(
                new ContentBlockDto.BlockCopy("标题", "说明"),
                new ContentBlockDto.BlockCopy("Title", "Description"));
    }

    private static Map<LocaleCode, ContentBlockDto.MetricCopy> metricCopy() {
        return localized(
                new ContentBlockDto.MetricCopy("指标", "1", "次"),
                new ContentBlockDto.MetricCopy("Metric", "1", "times"));
    }

    private static Map<LocaleCode, ContentBlockDto.ActionCopy> actionCopy() {
        return localized(
                new ContentBlockDto.ActionCopy("下载", "获取文件"),
                new ContentBlockDto.ActionCopy("Download", "Get file"));
    }

    private static Map<LocaleCode, ContentBlockDto.BlockCopy> blankBlockCopy() {
        return localized(
                new ContentBlockDto.BlockCopy("", " "),
                new ContentBlockDto.BlockCopy(" ", ""));
    }

    private static Map<LocaleCode, ContentBlockDto.QuoteCopy> blankQuoteCopy() {
        return localized(
                new ContentBlockDto.QuoteCopy("", " "),
                new ContentBlockDto.QuoteCopy(" ", ""));
    }

    private static Map<LocaleCode, ContentBlockDto.MetricCopy> blankMetricCopy() {
        return localized(
                new ContentBlockDto.MetricCopy("", " ", ""),
                new ContentBlockDto.MetricCopy(" ", "", " "));
    }

    private static Map<LocaleCode, ContentBlockDto.ActionCopy> blankActionCopy() {
        return localized(
                new ContentBlockDto.ActionCopy("", " "),
                new ContentBlockDto.ActionCopy(" ", ""));
    }

    private static Map<LocaleCode, String> blankStrings() {
        return localized("", " ");
    }

    private static <T> Map<LocaleCode, T> localized(T chinese, T english) {
        return Map.of(LocaleCode.ZH_CN, chinese, LocaleCode.EN, english);
    }

    private static void addExpectedReferences(
            List<PublishingRepository.MediaReferenceRow> references,
            UUID assetId,
            List<String> usages) {
        for (String variant : List.of("w1280", "w640")) {
            for (String usage : usages) {
                references.add(new PublishingRepository.MediaReferenceRow(
                        assetId, variant, usage));
            }
        }
    }

    private static Comparator<PublishingRepository.MediaReferenceRow> referenceOrder() {
        return Comparator
                .comparing((PublishingRepository.MediaReferenceRow row) ->
                        row.assetId().toString())
                .thenComparing(PublishingRepository.MediaReferenceRow::variantName)
                .thenComparing(PublishingRepository.MediaReferenceRow::usage);
    }

    private static String mediaPath(UUID assetId) {
        return "media[" + assetId + "]";
    }

    private static DomainException failure(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        DomainException failure = catchThrowableOfType(DomainException.class, call);
        assertThat(failure).isNotNull();
        assertThat(failure.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        return failure;
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }

    private static final class RecordingMediaQueryService implements MediaQueryService {
        private final Map<UUID, MediaAssetDescriptor> descriptors = new HashMap<>();
        private final List<UUID> assetRequests = new ArrayList<>();
        private final List<String> variantRequests = new ArrayList<>();

        RecordingMediaQueryService put(MediaAssetDescriptor descriptor) {
            descriptors.put(descriptor.assetId(), descriptor);
            return this;
        }

        void clearRequests() {
            assetRequests.clear();
            variantRequests.clear();
        }

        @Override
        public MediaAssetDescriptor requireReadyAsset(UUID assetId) {
            assetRequests.add(assetId);
            MediaAssetDescriptor descriptor = descriptors.get(assetId);
            if (descriptor == null) {
                throw new AssertionError("unexpected media asset request: " + assetId);
            }
            return descriptor;
        }

        @Override
        public MediaVariantDescriptor requireReadyVariant(
                UUID assetId, String variantName) {
            variantRequests.add(assetId + ":" + variantName);
            MediaAssetDescriptor descriptor = descriptors.get(assetId);
            if (descriptor == null) {
                throw new AssertionError("unexpected media variant asset: " + assetId);
            }
            return descriptor.variants().stream()
                    .filter(variant -> variant.variantName().equals(variantName))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "unexpected media variant request: " + assetId + ":" + variantName));
        }
    }
}
