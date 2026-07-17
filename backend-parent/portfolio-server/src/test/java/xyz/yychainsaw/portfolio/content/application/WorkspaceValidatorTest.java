package xyz.yychainsaw.portfolio.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.content.api.ContentBlockDto;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.content.api.ProjectWorkspaceDto;
import xyz.yychainsaw.portfolio.content.api.SiteWorkspaceDto;
import xyz.yychainsaw.portfolio.content.support.WorkspaceFixtures;

class WorkspaceValidatorTest {
    private static final String LOCALES_REQUIRED =
            "exactly zh-CN and en are required";
    private static final String HTTPS_REQUIRED = "HTTPS URL required";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final WorkspaceValidator validator = new WorkspaceValidator();

    @Test
    void rejectsProjectWithoutBothTranslations() {
        ProjectWorkspaceDto project = WorkspaceFixtures.projectBuilder()
                .translations(Map.of(
                        LocaleCode.EN,
                        new ProjectWorkspaceDto.ProjectCopy(
                                "In progress",
                                "Gameplay",
                                "Prototype",
                                "Summary",
                                "SEO",
                                "Description")))
                .build();

        DomainException failure = projectFailure(project);

        assertThat(failure.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(failure.code()).isEqualTo("PROJECT_TRANSLATION_INCOMPLETE");
        assertThat(failure.fieldErrors())
                .containsEntry("translations", LOCALES_REQUIRED);
    }

    @Test
    void rejectsUnsafeLinkProtocol() {
        ContentBlockDto block = block(new ContentBlockDto.LinkPayload(
                URI.create("javascript:alert(1)"), true, actionCopy()));
        ProjectWorkspaceDto project = WorkspaceFixtures.projectBuilder()
                .blocks(List.of(block))
                .build();

        DomainException failure = projectFailure(project);

        assertThat(failure.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(failure.code()).isEqualTo("CONTENT_BLOCK_INVALID");
        assertThat(failure.fieldErrors())
                .containsEntry("blocks[0].url", HTTPS_REQUIRED);
    }

    @Test
    void validFixturesPass() {
        assertThatCode(() -> validator.validateSite(WorkspaceFixtures.site(0L)))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validateProject(
                        WorkspaceFixtures.projectWithoutMedia()))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validateProject(
                        WorkspaceFixtures.projectWithImageGalleryAndVideoCover()))
                .doesNotThrowAnyException();
    }

    @Test
    void fixtureNavigationTargetsMatchThePersistedVocabulary() {
        assertThat(WorkspaceFixtures.site(0L).navigation())
                .extracting(SiteWorkspaceDto.NavigationItem::target)
                .containsExactly("work", "contact");
    }

    @Test
    void fixtureRepresentsAbsentHeroMediaAsAnEmptyPersistedTuple() {
        SiteWorkspaceDto.Hero hero = WorkspaceFixtures.site(0L).hero();

        assertThat(hero.mediaAssetId()).isNull();
        assertThat(hero.objectPosition()).isNull();
        assertThat(hero.credit()).isNull();
        assertThat(hero.sourceUrl()).isNull();
    }

    @Test
    void validatorIsAProductionComponent() {
        assertThat(WorkspaceValidator.class).hasAnnotation(Component.class);
    }

    @Test
    void localeCodeUsesExactWireValues() throws Exception {
        assertThat(LocaleCode.ZH_CN.value()).isEqualTo("zh-CN");
        assertThat(LocaleCode.EN.value()).isEqualTo("en");
        assertThat(LocaleCode.from("zh-CN")).isEqualTo(LocaleCode.ZH_CN);
        assertThat(LocaleCode.from("en")).isEqualTo(LocaleCode.EN);
        assertThat(JSON.writeValueAsString(LocaleCode.ZH_CN)).isEqualTo("\"zh-CN\"");
        assertThat(JSON.writeValueAsString(Map.of(LocaleCode.ZH_CN, "中文")))
                .isEqualTo("{\"zh-CN\":\"中文\"}");
        assertThat(JSON.readValue("\"en\"", LocaleCode.class))
                .isEqualTo(LocaleCode.EN);

        assertThatThrownBy(() -> LocaleCode.from("zh-cn"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unsupported locale: zh-cn");
        assertThatThrownBy(() -> LocaleCode.from("EN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unsupported locale: EN");
        assertThatThrownBy(() -> LocaleCode.from(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unsupported locale: null");
    }

    @Test
    void payloadContractIsSealedAndUsesStableJacksonDiscriminators() throws Exception {
        assertThat(ContentBlockDto.Payload.class.isSealed()).isTrue();
        assertThat(Arrays.asList(ContentBlockDto.Payload.class.getPermittedSubclasses()))
                .containsExactlyInAnyOrder(
                        ContentBlockDto.MarkdownPayload.class,
                        ContentBlockDto.ImagePayload.class,
                        ContentBlockDto.GalleryPayload.class,
                        ContentBlockDto.VideoPayload.class,
                        ContentBlockDto.CodePayload.class,
                        ContentBlockDto.QuotePayload.class,
                        ContentBlockDto.MetricsPayload.class,
                        ContentBlockDto.DownloadPayload.class,
                        ContentBlockDto.LinkPayload.class);
        JsonSubTypes discriminator = ContentBlockDto.Payload.class
                .getAnnotation(JsonSubTypes.class);
        assertThat(Arrays.stream(discriminator.value())
                        .collect(java.util.stream.Collectors.toMap(
                                JsonSubTypes.Type::name,
                                JsonSubTypes.Type::value)))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "MARKDOWN", ContentBlockDto.MarkdownPayload.class,
                        "IMAGE", ContentBlockDto.ImagePayload.class,
                        "GALLERY", ContentBlockDto.GalleryPayload.class,
                        "VIDEO", ContentBlockDto.VideoPayload.class,
                        "CODE", ContentBlockDto.CodePayload.class,
                        "QUOTE", ContentBlockDto.QuotePayload.class,
                        "METRICS", ContentBlockDto.MetricsPayload.class,
                        "DOWNLOAD", ContentBlockDto.DownloadPayload.class,
                        "LINK", ContentBlockDto.LinkPayload.class));

        ContentBlockDto source = block(new ContentBlockDto.LinkPayload(
                URI.create("https://example.test/project"), true, actionCopy()));
        JsonNode serialized = JSON.readTree(JSON.writeValueAsBytes(source));
        assertThat(serialized.path("payload").path("type").asText())
                .isEqualTo("LINK");
        assertThat(JSON.readValue(JSON.writeValueAsBytes(source), ContentBlockDto.class))
                .isEqualTo(source);
    }

    @Test
    void rootRecordComponentsKeepTheirBindingOrder() {
        assertComponentNames(
                SiteWorkspaceDto.class,
                "siteId", "version", "monogram", "email", "identity", "seo",
                "accessibility", "navigation", "hero", "about", "facts",
                "profileSkills", "work", "roadmap", "contact", "privacy",
                "socialLinks", "resumes");
        assertComponentNames(
                ProjectWorkspaceDto.class,
                "id", "externalKey", "slug", "number", "sortOrder", "featured",
                "visible", "publicationDirty", "version", "translations", "tags",
                "skills", "media", "blocks");
        assertComponentNames(
                ContentBlockDto.class,
                "id", "sortOrder", "visible", "width", "alignment", "emphasis",
                "columns", "payload");
    }

    @Test
    void recordsDefensivelyCopyEveryCollectionComponent() {
        SiteWorkspaceDto site = WorkspaceFixtures.site(7L);
        ProjectWorkspaceDto project = WorkspaceFixtures.projectWithImageGalleryAndVideoCover();

        assertUnmodifiable(site.identity()::clear);
        assertUnmodifiable(site.seo()::clear);
        assertUnmodifiable(site.accessibility()::clear);
        assertUnmodifiable(site.navigation()::clear);
        assertUnmodifiable(site.about()::clear);
        assertUnmodifiable(site.facts()::clear);
        assertUnmodifiable(site.profileSkills()::clear);
        assertUnmodifiable(site.work()::clear);
        assertUnmodifiable(site.contact()::clear);
        assertUnmodifiable(site.privacy()::clear);
        assertUnmodifiable(site.socialLinks()::clear);
        assertUnmodifiable(site.resumes()::clear);
        assertUnmodifiable(site.navigation().get(0).labels()::clear);
        assertUnmodifiable(site.hero().copy()::clear);
        assertUnmodifiable(site.facts().get(0).copy()::clear);
        assertUnmodifiable(site.profileSkills().get(0).copy()::clear);
        assertUnmodifiable(site.roadmap().header()::clear);
        assertUnmodifiable(site.roadmap().stages()::clear);
        assertUnmodifiable(site.roadmap().stages().get(0).copy()::clear);
        assertUnmodifiable(site.roadmap().stages().get(0).outcomes()::clear);
        assertUnmodifiable(site.roadmap().stages().get(0).outcomes().get(0).text()::clear);

        assertUnmodifiable(project.translations()::clear);
        assertUnmodifiable(project.tags()::clear);
        assertUnmodifiable(project.skills()::clear);
        assertUnmodifiable(project.media()::clear);
        assertUnmodifiable(project.blocks()::clear);
        assertUnmodifiable(project.tags().get(0).names()::clear);

        ContentBlockDto.MarkdownPayload markdown =
                new ContentBlockDto.MarkdownPayload(localized("正文", "Body"));
        ContentBlockDto.GalleryPayload gallery =
                new ContentBlockDto.GalleryPayload(List.of(uuid(81), uuid(82)));
        ContentBlockDto.VideoPayload video = new ContentBlockDto.VideoPayload(
                "YOUTUBE", URI.create("https://example.test/video"), uuid(83), blockCopy());
        ContentBlockDto.CodePayload code = new ContentBlockDto.CodePayload(
                "print('ok')", "python", true, blockCopy());
        ContentBlockDto.QuotePayload quote =
                new ContentBlockDto.QuotePayload(quoteCopy());
        ContentBlockDto.Metric metric = new ContentBlockDto.Metric(
                uuid(84), 0, BigDecimal.ONE, metricCopy());
        ContentBlockDto.MetricsPayload metrics =
                new ContentBlockDto.MetricsPayload(List.of(metric));
        ContentBlockDto.DownloadPayload download = new ContentBlockDto.DownloadPayload(
                uuid(85), null, actionCopy());
        ContentBlockDto.LinkPayload link = new ContentBlockDto.LinkPayload(
                URI.create("https://example.test"), false, actionCopy());

        assertUnmodifiable(markdown.markdown()::clear);
        assertUnmodifiable(gallery.mediaAssetIds()::clear);
        assertUnmodifiable(video.copy()::clear);
        assertUnmodifiable(code.copy()::clear);
        assertUnmodifiable(quote.copy()::clear);
        assertUnmodifiable(metric.copy()::clear);
        assertUnmodifiable(metrics.metrics()::clear);
        assertUnmodifiable(download.copy()::clear);
        assertUnmodifiable(link.copy()::clear);
    }

    @Test
    void collectionCopiesAreDetachedAndNullCollectionsFailFast() {
        Map<LocaleCode, SiteWorkspaceDto.IdentityCopy> identity =
                new LinkedHashMap<>(WorkspaceFixtures.site(0L).identity());
        List<ContentBlockDto> blocks = new ArrayList<>();
        blocks.add(block(new ContentBlockDto.LinkPayload(
                URI.create("https://example.test"), false, actionCopy())));
        SiteWorkspaceDto copiedSite = copySite(
                WorkspaceFixtures.site(0L), identity, WorkspaceFixtures.site(0L).hero(),
                WorkspaceFixtures.site(0L).roadmap());
        ProjectWorkspaceDto copiedProject = copyProject(
                WorkspaceFixtures.project(), "project-test",
                WorkspaceFixtures.project().translations(), blocks);

        identity.clear();
        blocks.clear();

        assertThat(copiedSite.identity()).hasSize(2);
        assertThat(copiedProject.blocks()).hasSize(1);
        assertThatThrownBy(() -> new ContentBlockDto.GalleryPayload(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> WorkspaceFixtures.projectBuilder().blocks(null).build())
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void projectValidationCollectsAllErrorsWithStablePrecedence() {
        ContentBlockDto invalidBlock = new ContentBlockDto(
                uuid(20),
                0,
                true,
                ContentBlockDto.Width.STANDARD,
                ContentBlockDto.Alignment.LEFT,
                ContentBlockDto.Emphasis.NONE,
                0,
                new ContentBlockDto.LinkPayload(
                        URI.create("http://example.test"), true, actionCopy()));
        ProjectWorkspaceDto project = WorkspaceFixtures.projectBuilder()
                .translations(Map.of(
                        LocaleCode.EN,
                        WorkspaceFixtures.project().translations().get(LocaleCode.EN)))
                .blocks(List.of(invalidBlock))
                .build();

        DomainException first = projectFailure(project);
        DomainException second = projectFailure(project);

        assertThat(first.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(first.code()).isEqualTo("PROJECT_TRANSLATION_INCOMPLETE");
        assertThat(first.fieldErrors()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "translations", LOCALES_REQUIRED,
                "blocks[0].columns", "must be 1 to 4",
                "blocks[0].url", HTTPS_REQUIRED));
        assertThat(second.code()).isEqualTo(first.code());
        assertThat(second.status()).isEqualTo(first.status());
        assertThat(second.fieldErrors()).isEqualTo(first.fieldErrors());
    }

    @Test
    void repeatedValidationOnTheSameComponentDoesNotLeakErrorsAcrossCalls() {
        ProjectWorkspaceDto missingTranslations = WorkspaceFixtures.projectBuilder()
                .translations(Map.of(
                        LocaleCode.EN,
                        WorkspaceFixtures.project().translations().get(LocaleCode.EN)))
                .build();
        ProjectWorkspaceDto valid = WorkspaceFixtures.projectWithoutMedia();
        ProjectWorkspaceDto missingSlug = WorkspaceFixtures.projectBuilder()
                .slug(null)
                .build();

        DomainException first = projectFailure(missingTranslations);
        assertThatCode(() -> validator.validateProject(valid))
                .doesNotThrowAnyException();
        DomainException second = projectFailure(missingSlug);

        assertThat(first.code()).isEqualTo("PROJECT_TRANSLATION_INCOMPLETE");
        assertThat(first.fieldErrors()).containsOnly(
                Map.entry("translations", LOCALES_REQUIRED));
        assertThat(second.code()).isEqualTo("CONTENT_BLOCK_INVALID");
        assertThat(second.fieldErrors()).containsOnly(Map.entry("slug", "required"));
    }

    @Test
    void blankOrNullSlugDoesNotCrashAndRequiredBeatsFormat() {
        DomainException nullSlug = projectFailure(
                WorkspaceFixtures.projectBuilder().slug(null).build());
        DomainException blankSlug = projectFailure(
                WorkspaceFixtures.projectBuilder().slug("  ").build());
        DomainException malformedSlug = projectFailure(
                WorkspaceFixtures.projectBuilder().slug("Invalid Slug").build());

        assertThat(nullSlug.code()).isEqualTo("CONTENT_BLOCK_INVALID");
        assertThat(nullSlug.fieldErrors()).containsOnly(Map.entry("slug", "required"));
        assertThat(blankSlug.fieldErrors()).containsOnly(Map.entry("slug", "required"));
        assertThat(malformedSlug.fieldErrors()).containsOnly(Map.entry(
                "slug", "slug must be lowercase ASCII words separated by hyphens"));
    }

    @Test
    void siteChecksEveryApprovedTopLevelLocaleContainer() {
        SiteWorkspaceDto base = WorkspaceFixtures.site(0L);
        SiteWorkspaceDto.Hero hero = new SiteWorkspaceDto.Hero(
                base.hero().id(),
                base.hero().version(),
                base.hero().mediaAssetId(),
                base.hero().objectPosition(),
                base.hero().credit(),
                base.hero().sourceUrl(),
                englishOnly(base.hero().copy()));
        SiteWorkspaceDto.Roadmap roadmap = new SiteWorkspaceDto.Roadmap(
                englishOnly(base.roadmap().header()), base.roadmap().stages());
        SiteWorkspaceDto invalid = new SiteWorkspaceDto(
                base.siteId(),
                base.version(),
                base.monogram(),
                base.email(),
                englishOnly(base.identity()),
                englishOnly(base.seo()),
                englishOnly(base.accessibility()),
                base.navigation(),
                hero,
                englishOnly(base.about()),
                base.facts(),
                base.profileSkills(),
                englishOnly(base.work()),
                roadmap,
                englishOnly(base.contact()),
                englishOnly(base.privacy()),
                base.socialLinks(),
                base.resumes());

        DomainException failure = siteFailure(invalid);

        assertThat(failure.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(failure.code()).isEqualTo("SITE_WORKSPACE_INVALID");
        assertThat(failure.fieldErrors()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "identity", LOCALES_REQUIRED,
                "seo", LOCALES_REQUIRED,
                "accessibility", LOCALES_REQUIRED,
                "hero.copy", LOCALES_REQUIRED,
                "about", LOCALES_REQUIRED,
                "work", LOCALES_REQUIRED,
                "roadmap.header", LOCALES_REQUIRED,
                "contact", LOCALES_REQUIRED,
                "privacy", LOCALES_REQUIRED));
    }

    @Test
    void siteTraversalIsNullSafeForHeroAndRoadmap() {
        SiteWorkspaceDto base = WorkspaceFixtures.site(0L);
        SiteWorkspaceDto invalid = copySite(base, base.identity(), null, null);

        DomainException failure = siteFailure(invalid);

        assertThat(failure.code()).isEqualTo("SITE_WORKSPACE_INVALID");
        assertThat(failure.fieldErrors()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "hero", "required",
                "roadmap", "required"));
    }

    @Test
    void siteRequiresMonogramAndEmailWithoutDiscardingLocaleErrors() {
        SiteWorkspaceDto base = WorkspaceFixtures.site(0L);
        SiteWorkspaceDto invalid = new SiteWorkspaceDto(
                base.siteId(),
                base.version(),
                " ",
                null,
                englishOnly(base.identity()),
                base.seo(),
                base.accessibility(),
                base.navigation(),
                base.hero(),
                base.about(),
                base.facts(),
                base.profileSkills(),
                base.work(),
                base.roadmap(),
                base.contact(),
                base.privacy(),
                base.socialLinks(),
                base.resumes());

        DomainException failure = siteFailure(invalid);

        assertThat(failure.fieldErrors()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "monogram", "required",
                "email", "required",
                "identity", LOCALES_REQUIRED));
    }

    @Test
    void everyPayloadBranchHasFocusedCoverage() {
        assertInvalidPayload(
                new ContentBlockDto.MarkdownPayload(Map.of(LocaleCode.EN, "Body")),
                Map.of("blocks[0].markdown", LOCALES_REQUIRED));
        assertInvalidPayload(
                new ContentBlockDto.ImagePayload(null),
                Map.of("blocks[0].mediaAssetId", "required"));
        assertInvalidPayload(
                new ContentBlockDto.GalleryPayload(List.of(uuid(30))),
                Map.of("blocks[0].mediaAssetIds", "gallery requires at least two images"));
        assertInvalidPayload(
                new ContentBlockDto.VideoPayload(
                        "DAILYMOTION",
                        URI.create("http://example.test/video"),
                        null,
                        Map.of(LocaleCode.EN, new ContentBlockDto.BlockCopy("Video", "Copy"))),
                Map.of(
                        "blocks[0].url", HTTPS_REQUIRED,
                        "blocks[0].provider", "unsupported video provider",
                        "blocks[0].copy", LOCALES_REQUIRED));
        assertInvalidPayload(
                new ContentBlockDto.CodePayload(
                        "code", "text", false,
                        Map.of(LocaleCode.EN, new ContentBlockDto.BlockCopy("Code", "Copy"))),
                Map.of("blocks[0].copy", LOCALES_REQUIRED));
        assertInvalidPayload(
                new ContentBlockDto.QuotePayload(Map.of(
                        LocaleCode.EN, new ContentBlockDto.QuoteCopy("Quote", "Source"))),
                Map.of("blocks[0].copy", LOCALES_REQUIRED));
        assertInvalidPayload(
                new ContentBlockDto.MetricsPayload(List.of()),
                Map.of("blocks[0].metrics", "metrics block cannot be empty"));
        assertInvalidPayload(
                new ContentBlockDto.MetricsPayload(List.of(new ContentBlockDto.Metric(
                        uuid(31),
                        0,
                        BigDecimal.TEN,
                        Map.of(LocaleCode.EN,
                                new ContentBlockDto.MetricCopy("Metric", "10", "%"))))),
                Map.of("blocks[0].metrics[0].copy", LOCALES_REQUIRED));
        assertInvalidPayload(
                new ContentBlockDto.DownloadPayload(null, null, actionCopy()),
                Map.of(
                        "blocks[0]",
                        "download requires exactly one media asset or external URL"));
        assertInvalidPayload(
                new ContentBlockDto.DownloadPayload(
                        uuid(32), URI.create("https://example.test/file"), actionCopy()),
                Map.of(
                        "blocks[0]",
                        "download requires exactly one media asset or external URL"));
        assertInvalidPayload(
                new ContentBlockDto.DownloadPayload(
                        null, URI.create("ftp://example.test/file"), actionCopy()),
                Map.of("blocks[0].externalUrl", HTTPS_REQUIRED));
        assertInvalidPayload(
                new ContentBlockDto.LinkPayload(
                        URI.create("mailto:portfolio@example.test"), false, actionCopy()),
                Map.of("blocks[0].url", HTTPS_REQUIRED));
        assertInvalidPayload(null, Map.of(
                "blocks[0].type", "unsupported content block payload"));
    }

    @Test
    void allPayloadTypesAcceptStructurallyValidBilingualDraftsWithBlankLeaves() {
        URI uppercaseHttps = URI.create("HTTPS://example.test/video");
        assertThat(uppercaseHttps.getScheme()).isEqualTo("HTTPS");
        List<ContentBlockDto.Payload> payloads = List.of(
                new ContentBlockDto.MarkdownPayload(localized("", " ")),
                new ContentBlockDto.ImagePayload(uuid(50)),
                new ContentBlockDto.GalleryPayload(List.of(uuid(51), uuid(52))),
                new ContentBlockDto.VideoPayload(
                        "BILIBILI", uppercaseHttps, uuid(53), blankBlockCopy()),
                new ContentBlockDto.CodePayload("", "", false, blankBlockCopy()),
                new ContentBlockDto.QuotePayload(blankQuoteCopy()),
                new ContentBlockDto.MetricsPayload(List.of(new ContentBlockDto.Metric(
                        uuid(54), 0, BigDecimal.ONE, blankMetricCopy()))),
                new ContentBlockDto.DownloadPayload(uuid(55), null, blankActionCopy()),
                new ContentBlockDto.LinkPayload(
                        URI.create("https://example.test/link"), false, blankActionCopy()));
        List<ContentBlockDto> blocks = new ArrayList<>();
        for (int index = 0; index < payloads.size(); index++) {
            blocks.add(block(index, payloads.get(index)));
        }

        assertThatCode(() -> validator.validateProject(
                        WorkspaceFixtures.projectBuilder().blocks(blocks).build()))
                .doesNotThrowAnyException();
    }

    @Test
    void everySupportedVideoProviderAcceptsHttps() {
        List<String> providers = List.of("BILIBILI", "YOUTUBE", "VIMEO");
        List<ContentBlockDto> blocks = new ArrayList<>();
        for (int index = 0; index < providers.size(); index++) {
            blocks.add(block(index, new ContentBlockDto.VideoPayload(
                    providers.get(index),
                    URI.create("https://example.test/video/" + index),
                    null,
                    blankBlockCopy())));
        }

        assertThatCode(() -> validator.validateProject(
                        WorkspaceFixtures.projectBuilder().blocks(blocks).build()))
                .doesNotThrowAnyException();
    }

    @Test
    void invalidMatrixKeepsLocaleProviderAndUrlErrorsAtDistinctBlockIndices() {
        List<ContentBlockDto> blocks = List.of(
                block(0, new ContentBlockDto.MarkdownPayload(
                        Map.of(LocaleCode.ZH_CN, "正文"))),
                block(1, new ContentBlockDto.VideoPayload(
                        "youtube",
                        URI.create("https://example.test/lowercase"),
                        null,
                        blankBlockCopy())),
                block(2, new ContentBlockDto.VideoPayload(
                        null,
                        URI.create("https://example.test/null-provider"),
                        null,
                        blankBlockCopy())),
                block(3, new ContentBlockDto.LinkPayload(
                        null, false, blankActionCopy())));

        DomainException failure = projectFailure(
                WorkspaceFixtures.projectBuilder().blocks(blocks).build());

        assertThat(failure.code()).isEqualTo("CONTENT_BLOCK_INVALID");
        assertThat(failure.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(failure.fieldErrors()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "blocks[0].markdown", LOCALES_REQUIRED,
                "blocks[1].provider", "unsupported video provider",
                "blocks[2].provider", "unsupported video provider",
                "blocks[3].url", HTTPS_REQUIRED));
    }

    @Test
    void metricLocaleErrorsUseDistinctIndexQualifiedPaths() {
        ContentBlockDto.Metric first = new ContentBlockDto.Metric(
                uuid(40),
                0,
                BigDecimal.ONE,
                Map.of(LocaleCode.EN, new ContentBlockDto.MetricCopy("One", "1", "")));
        ContentBlockDto.Metric second = new ContentBlockDto.Metric(
                uuid(41),
                1,
                BigDecimal.TEN,
                Map.of(LocaleCode.EN, new ContentBlockDto.MetricCopy("Ten", "10", "")));

        assertInvalidPayload(
                new ContentBlockDto.MetricsPayload(List.of(first, second)),
                Map.of(
                        "blocks[0].metrics[0].copy", LOCALES_REQUIRED,
                        "blocks[0].metrics[1].copy", LOCALES_REQUIRED));
    }

    private void assertInvalidPayload(
            ContentBlockDto.Payload payload,
            Map<String, String> expectedErrors) {
        ProjectWorkspaceDto project = WorkspaceFixtures.projectBuilder()
                .blocks(List.of(block(payload)))
                .build();

        DomainException failure = projectFailure(project);

        assertThat(failure.code()).isEqualTo("CONTENT_BLOCK_INVALID");
        assertThat(failure.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(failure.fieldErrors())
                .as(payload == null ? "null payload" : payload.getClass().getSimpleName())
                .containsExactlyInAnyOrderEntriesOf(expectedErrors);
    }

    private DomainException projectFailure(ProjectWorkspaceDto project) {
        DomainException failure = catchThrowableOfType(
                DomainException.class,
                () -> validator.validateProject(project));
        assertThat(failure).isNotNull();
        return failure;
    }

    private DomainException siteFailure(SiteWorkspaceDto site) {
        DomainException failure = catchThrowableOfType(
                DomainException.class,
                () -> validator.validateSite(site));
        assertThat(failure).isNotNull();
        return failure;
    }

    private static ContentBlockDto block(ContentBlockDto.Payload payload) {
        return block(0, payload);
    }

    private static ContentBlockDto block(
            int index,
            ContentBlockDto.Payload payload) {
        return new ContentBlockDto(
                uuid(100 + index),
                index,
                true,
                ContentBlockDto.Width.STANDARD,
                ContentBlockDto.Alignment.LEFT,
                ContentBlockDto.Emphasis.NONE,
                1,
                payload);
    }

    private static Map<LocaleCode, ContentBlockDto.ActionCopy> blankActionCopy() {
        return localized(
                new ContentBlockDto.ActionCopy("", " "),
                new ContentBlockDto.ActionCopy(" ", ""));
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

    private static Map<LocaleCode, ContentBlockDto.ActionCopy> actionCopy() {
        return Map.of(
                LocaleCode.ZH_CN, new ContentBlockDto.ActionCopy("链接", "说明"),
                LocaleCode.EN, new ContentBlockDto.ActionCopy("Link", "Description"));
    }

    private static Map<LocaleCode, ContentBlockDto.BlockCopy> blockCopy() {
        return Map.of(
                LocaleCode.ZH_CN, new ContentBlockDto.BlockCopy("标题", "说明"),
                LocaleCode.EN, new ContentBlockDto.BlockCopy("Title", "Description"));
    }

    private static Map<LocaleCode, ContentBlockDto.QuoteCopy> quoteCopy() {
        return Map.of(
                LocaleCode.ZH_CN, new ContentBlockDto.QuoteCopy("引用", "来源"),
                LocaleCode.EN, new ContentBlockDto.QuoteCopy("Quote", "Source"));
    }

    private static Map<LocaleCode, ContentBlockDto.MetricCopy> metricCopy() {
        return Map.of(
                LocaleCode.ZH_CN, new ContentBlockDto.MetricCopy("指标", "1", "%"),
                LocaleCode.EN, new ContentBlockDto.MetricCopy("Metric", "1", "%"));
    }

    private static <T> Map<LocaleCode, T> localized(T chinese, T english) {
        return Map.of(LocaleCode.ZH_CN, chinese, LocaleCode.EN, english);
    }

    private static <T> Map<LocaleCode, T> englishOnly(Map<LocaleCode, T> source) {
        return Map.of(LocaleCode.EN, source.get(LocaleCode.EN));
    }

    private static SiteWorkspaceDto copySite(
            SiteWorkspaceDto base,
            Map<LocaleCode, SiteWorkspaceDto.IdentityCopy> identity,
            SiteWorkspaceDto.Hero hero,
            SiteWorkspaceDto.Roadmap roadmap) {
        return new SiteWorkspaceDto(
                base.siteId(),
                base.version(),
                base.monogram(),
                base.email(),
                identity,
                base.seo(),
                base.accessibility(),
                base.navigation(),
                hero,
                base.about(),
                base.facts(),
                base.profileSkills(),
                base.work(),
                roadmap,
                base.contact(),
                base.privacy(),
                base.socialLinks(),
                base.resumes());
    }

    private static ProjectWorkspaceDto copyProject(
            ProjectWorkspaceDto base,
            String slug,
            Map<LocaleCode, ProjectWorkspaceDto.ProjectCopy> translations,
            List<ContentBlockDto> blocks) {
        return new ProjectWorkspaceDto(
                base.id(),
                base.externalKey(),
                slug,
                base.number(),
                base.sortOrder(),
                base.featured(),
                base.visible(),
                base.publicationDirty(),
                base.version(),
                translations,
                base.tags(),
                base.skills(),
                base.media(),
                blocks);
    }

    private static void assertUnmodifiable(Runnable mutation) {
        assertThatThrownBy(mutation::run)
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static void assertComponentNames(Class<?> type, String... expected) {
        assertThat(Arrays.stream(type.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName))
                .containsExactly(expected);
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString(String.format(
                "30000000-0000-4000-8000-%012d", suffix));
    }
}
