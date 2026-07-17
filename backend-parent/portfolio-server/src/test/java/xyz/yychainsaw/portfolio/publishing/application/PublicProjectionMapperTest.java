package xyz.yychainsaw.portfolio.publishing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.publicapi.PublicBlockDto;
import xyz.yychainsaw.portfolio.publicapi.PublicMediaDto;
import xyz.yychainsaw.portfolio.publicapi.PublicProjectCardDto;
import xyz.yychainsaw.portfolio.publicapi.PublicProjectDto;
import xyz.yychainsaw.portfolio.publicapi.PublicSiteDto;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.LocaleV1;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.ProjectCatalogSnapshotV1;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.ProjectSnapshotV1;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.PublishedBlockV1;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.PublishedMediaV1;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.SiteContentV1;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.SiteSnapshotV1;

class PublicProjectionMapperTest {
    private static final UUID SITE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PROJECT_ID =
            UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID HERO_ASSET =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID RESUME_ZH_ASSET =
            UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID RESUME_EN_ASSET =
            UUID.fromString("10000000-0000-0000-0000-000000000003");
    private static final UUID COVER_ASSET =
            UUID.fromString("10000000-0000-0000-0000-000000000004");
    private static final UUID DETAIL_ASSET =
            UUID.fromString("10000000-0000-0000-0000-000000000005");
    private static final UUID GALLERY_ASSET =
            UUID.fromString("10000000-0000-0000-0000-000000000006");
    private static final UUID VIDEO_ASSET =
            UUID.fromString("10000000-0000-0000-0000-000000000007");
    private static final UUID DOWNLOAD_ASSET =
            UUID.fromString("10000000-0000-0000-0000-000000000008");

    private final PublicProjectionMapper projections =
            new PublicProjectionMapper(new SafeMarkdownRenderer());

    @Test
    void siteUsesExactEnglishCopyAndFiltersAndSortsEveryPublicCollection() {
        PublicSiteDto site = projections.site(siteSnapshot(), LocaleCode.EN);

        assertThat(site.identity())
                .isEqualTo(new PublicSiteDto.Identity(
                        "YJX", "Yi Jiaxuan", "易嘉轩", "hello@yychainsaw.xyz"));
        assertThat(site.seo().title()).isEqualTo("Yi Jiaxuan — Game Developer");
        assertThat(site.accessibility().skip()).isEqualTo("Skip to content");
        assertThat(site.navigation())
                .extracting(PublicSiteDto.NavigationItem::target)
                .containsExactly("work", "about");
        assertThat(site.navigation())
                .extracting(PublicSiteDto.NavigationItem::label)
                .containsExactly("Work", "About");
        assertThat(site.about().facts())
                .extracting(PublicSiteDto.Fact::label)
                .containsExactly("University", "Year");
        assertThat(site.about().skills())
                .extracting(PublicSiteDto.Skill::name)
                .containsExactly("Unreal Engine", "Java");
        assertThat(site.roadmap().stages())
                .extracting(PublicSiteDto.RoadmapStage::number)
                .containsExactly("01", "02");
        assertThat(site.roadmap().stages().get(0).outcomes())
                .containsExactly("Learn gameplay framework", "Ship a prototype");
        assertThat(site.socialLinks())
                .extracting(PublicSiteDto.SocialLink::platform)
                .containsExactly("GitHub", "Bilibili");

        assertThat(site.hero().headline()).isEqualTo("I build playable worlds.");
        assertThat(site.hero().credit()).isEqualTo("Direct hero credit");
        assertThat(site.hero().sourceUrl()).isEqualTo("https://example.com/direct-hero");
        assertThat(site.hero().media().alt()).isEqualTo("English hero alt");
        assertThat(site.hero().media().caption()).isEqualTo("English hero caption");
        assertThat(site.hero().media().credit()).isEqualTo("English hero credit");
        assertThat(site.hero().media().sourceUrl())
                .isEqualTo("https://example.com/en/hero");
        assertResponsiveImage(site.hero().media(), HERO_ASSET);

        assertThat(site.privacy().title()).isEqualTo("Privacy");
        assertThat(site.privacy().html())
                .contains("<strong>privacy</strong>")
                .doesNotContain("<script>");
        assertThat(site.resume())
                .isEqualTo(new PublicSiteDto.Resume(
                        "Resume EN current",
                        LocalDate.of(2026, 7, 2),
                        mediaPath(RESUME_EN_ASSET, "document")));
    }

    @Test
    void siteUsesExactChineseCopyWithoutEnglishFallback() {
        PublicSiteDto site = projections.site(siteSnapshot(), LocaleCode.ZH_CN);

        assertThat(site.identity().displayName()).isEqualTo("易嘉轩");
        assertThat(site.identity().secondaryName()).isEqualTo("Yi Jiaxuan");
        assertThat(site.hero().headline()).isEqualTo("我创造可玩的世界。");
        assertThat(site.navigation())
                .extracting(PublicSiteDto.NavigationItem::label)
                .containsExactly("作品", "关于");
        assertThat(site.hero().media().credit()).isEqualTo("中文hero媒体署名");
        assertThat(site.resume().label()).isEqualTo("中文简历 2026");
        assertThat(site.resume().href()).isEqualTo(mediaPath(RESUME_ZH_ASSET, "document"));
    }

    @Test
    void catalogUsesExactLocaleStableCardOrderAndLocalizedCoverAttribution() {
        ProjectCatalogSnapshotV1 source = new ProjectCatalogSnapshotV1(
                1,
                List.of(
                        catalogCard(
                                UUID.fromString("20000000-0000-0000-0000-000000000002"),
                                "second", 2, GALLERY_ASSET, "Second", "第二个"),
                        catalogCard(PROJECT_ID, "first", 1, COVER_ASSET, "First", "第一个")));

        List<PublicProjectCardDto> cards = projections.catalog(source, LocaleCode.EN);

        assertThat(cards).extracting(PublicProjectCardDto::slug)
                .containsExactly("first", "second");
        assertThat(cards.get(0).title()).isEqualTo("First");
        assertThat(cards.get(0).tags()).containsExactly("Game", "Prototype");
        assertThat(cards.get(0).cover().credit()).isEqualTo("English cover credit");
        assertThat(cards.get(0).cover().sourceUrl())
                .isEqualTo("https://example.com/en/cover");
        assertResponsiveImage(cards.get(0).cover(), COVER_ASSET);
    }

    @Test
    void projectUsesExactLocaleAndStableTaxonomyProjectMediaAndBlockOrder() {
        PublicProjectDto project = projections.project(projectSnapshot(), LocaleCode.EN);

        assertThat(project.projectId()).isEqualTo(PROJECT_ID);
        assertThat(project.title()).isEqualTo("Gameplay Prototype");
        assertThat(project.tags()).containsExactly("Game", "Prototype");
        assertThat(project.skills()).containsExactly("Unreal Engine", "C++");
        assertThat(project.media()).extracting(PublicMediaDto::assetId)
                .containsExactly(COVER_ASSET, DETAIL_ASSET);
        assertThat(project.media().get(0).credit()).isEqualTo("English cover credit");
        assertThat(project.blocks()).extracting(PublicBlockDto::sortOrder)
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        assertThat(project.blocks()).extracting(PublicBlockDto::type)
                .containsExactly(
                        "MARKDOWN", "IMAGE", "GALLERY", "VIDEO", "CODE",
                        "QUOTE", "METRICS", "DOWNLOAD", "DOWNLOAD", "LINK");
    }

    @Test
    void projectMapsAllNinePayloadKindsAndEveryNestedMediaAttributionPath() {
        PublicProjectDto project = projections.project(projectSnapshot(), LocaleCode.EN);
        Map<String, List<PublicBlockDto>> byType = project.blocks().stream()
                .collect(java.util.stream.Collectors.groupingBy(PublicBlockDto::type));

        PublicBlockDto.Markdown markdown = (PublicBlockDto.Markdown)
                byType.get("MARKDOWN").get(0).payload();
        assertThat(markdown.html())
                .contains("<strong>published</strong>")
                .doesNotContain("<script>");

        PublicBlockDto.Image image = (PublicBlockDto.Image)
                byType.get("IMAGE").get(0).payload();
        assertThat(image.media().assetId()).isEqualTo(DETAIL_ASSET);
        assertThat(image.media().credit()).isEqualTo("English detail credit");
        assertThat(image.media().sourceUrl()).isEqualTo("https://example.com/en/detail");

        PublicBlockDto.Gallery gallery = (PublicBlockDto.Gallery)
                byType.get("GALLERY").get(0).payload();
        assertThat(gallery.media()).extracting(PublicMediaDto::assetId)
                .containsExactly(GALLERY_ASSET, COVER_ASSET);
        assertThat(gallery.media()).extracting(PublicMediaDto::credit)
                .containsExactly("English gallery credit", "English cover credit");
        assertThat(gallery.media()).extracting(PublicMediaDto::sourceUrl)
                .containsExactly(
                        "https://example.com/en/gallery", "https://example.com/en/cover");

        PublicBlockDto.Video video = (PublicBlockDto.Video)
                byType.get("VIDEO").get(0).payload();
        assertThat(video.provider()).isEqualTo("youtube");
        assertThat(video.embedUrl()).isEqualTo("https://www.youtube.com/embed/AbC_123-xyZ");
        assertThat(video.title()).isEqualTo("Gameplay video");
        assertThat(video.cover().credit()).isEqualTo("English video credit");
        assertThat(video.cover().sourceUrl()).isEqualTo("https://example.com/en/video");

        PublicBlockDto.Code code = (PublicBlockDto.Code)
                byType.get("CODE").get(0).payload();
        assertThat(code.code()).isEqualTo("return true;");
        assertThat(code.language()).isEqualTo("cpp");
        assertThat(code.showLineNumbers()).isTrue();
        assertThat(code.title()).isEqualTo("Ability code");

        PublicBlockDto.Quote quote = (PublicBlockDto.Quote)
                byType.get("QUOTE").get(0).payload();
        assertThat(quote.quote()).isEqualTo("Make it playable.");
        assertThat(quote.source()).isEqualTo("Yi Jiaxuan");

        PublicBlockDto.Metrics metrics = (PublicBlockDto.Metrics)
                byType.get("METRICS").get(0).payload();
        assertThat(metrics.metrics()).extracting(PublicBlockDto.Metric::label)
                .containsExactly("Frame rate", "Playtesters");
        assertThat(metrics.metrics().get(0).numericValue()).isEqualByComparingTo("60");

        List<PublicBlockDto> downloads = byType.get("DOWNLOAD");
        PublicBlockDto.Download mediaDownload =
                (PublicBlockDto.Download) downloads.get(0).payload();
        assertThat(mediaDownload.href()).isEqualTo(mediaPath(DOWNLOAD_ASSET, "document"));
        assertThat(mediaDownload.mimeType()).isEqualTo("application/pdf");
        assertThat(mediaDownload.byteSize()).isEqualTo(8192L);
        PublicBlockDto.Download externalDownload =
                (PublicBlockDto.Download) downloads.get(1).payload();
        assertThat(externalDownload.href()).isEqualTo("https://example.com/design-notes.pdf");
        assertThat(externalDownload.mimeType()).isNull();
        assertThat(externalDownload.byteSize()).isNull();

        PublicBlockDto.Link link = (PublicBlockDto.Link)
                byType.get("LINK").get(0).payload();
        assertThat(link.href()).isEqualTo("https://github.com/YYchainsAw/project");
        assertThat(link.openNewTab()).isTrue();
        assertThat(link.label()).isEqualTo("Source code");
    }

    @ParameterizedTest
    @MethodSource("safeVideoUrls")
    void canonicalizesOnlySupportedVideoUrls(
            String provider, String source, String expectedEmbedUrl) {
        ProjectSnapshotV1 project = projectWithSingleVideo(provider, URI.create(source));

        PublicBlockDto.Video video = (PublicBlockDto.Video) projections
                .project(project, LocaleCode.EN)
                .blocks().get(0).payload();

        assertThat(video.provider()).isEqualTo(provider);
        assertThat(video.embedUrl()).isEqualTo(expectedEmbedUrl);
    }

    @ParameterizedTest
    @MethodSource("unsafeVideoUrls")
    void rejectsUnsupportedOrDeceptiveVideoHosts(String provider, String source) {
        assertNotPublishableWithoutEcho(
                () -> projections.project(
                        projectWithSingleVideo(provider, URI.create(source)), LocaleCode.EN),
                "PROJECT_NOT_PUBLISHABLE",
                source);
    }

    @ParameterizedTest
    @MethodSource("unsafeExternalUrls")
    void rejectsUnsafeExternalUrlsWithoutEchoingThem(String source) {
        PublishedBlockV1 unsafeLink = block(
                1,
                new PublishedBlockV1.LinkPayloadV1(
                        URI.create(source),
                        true,
                        both(
                                new PublishedBlockV1.ActionCopyV1("Source", "Description"),
                                new PublishedBlockV1.ActionCopyV1("Source", "Description"))));

        assertNotPublishableWithoutEcho(
                () -> projections.project(
                        withBlocks(projectSnapshot(), List.of(unsafeLink)), LocaleCode.EN),
                "PROJECT_NOT_PUBLISHABLE",
                source);
    }

    @Test
    void rejectsUnsafeExternalLinksAndDownloads() {
        PublishedBlockV1 unsafeLink = block(
                1,
                new PublishedBlockV1.LinkPayloadV1(
                        URI.create("javascript:alert(1)"),
                        true,
                        both(
                                new PublishedBlockV1.ActionCopyV1("源码", "描述"),
                                new PublishedBlockV1.ActionCopyV1("Source", "Description"))));
        PublishedBlockV1 unsafeDownload = block(
                2,
                new PublishedBlockV1.DownloadPayloadV1(
                        null,
                        URI.create("http://example.com/file.zip"),
                        both(
                                new PublishedBlockV1.ActionCopyV1("下载", "描述"),
                                new PublishedBlockV1.ActionCopyV1("Download", "Description"))));

        assertNotPublishable(
                () -> projections.project(
                        withBlocks(projectSnapshot(), List.of(unsafeLink)), LocaleCode.EN),
                "PROJECT_NOT_PUBLISHABLE");
        assertNotPublishable(
                () -> projections.project(
                        withBlocks(projectSnapshot(), List.of(unsafeDownload)), LocaleCode.EN),
                "PROJECT_NOT_PUBLISHABLE");
    }

    @Test
    void rejectsMissingExactLocaleWithoutFallingBack() {
        ProjectSnapshotV1 source = projectSnapshot();
        ProjectSnapshotV1 missingEnglish = new ProjectSnapshotV1(
                1,
                source.projectId(),
                source.externalKey(),
                source.slug(),
                source.number(),
                source.sortOrder(),
                source.featured(),
                Map.of(LocaleV1.ZH_CN, source.translations().get(LocaleV1.ZH_CN)),
                source.tags(),
                source.skills(),
                source.projectMedia(),
                source.blocks(),
                source.media());

        assertNotPublishable(
                () -> projections.project(missingEnglish, LocaleCode.EN),
                "PROJECT_NOT_PUBLISHABLE");
    }

    @Test
    void rejectsMissingLocalizedMediaCopy() {
        PublishedMediaV1 cover = imageMedia(COVER_ASSET, "cover");
        PublishedMediaV1 missingEnglish = new PublishedMediaV1(
                cover.assetId(),
                cover.contentType(),
                cover.contentLength(),
                cover.sha256(),
                Map.of(LocaleV1.ZH_CN, cover.copy().get(LocaleV1.ZH_CN)),
                cover.variants());
        ProjectSnapshotV1 source = projectSnapshot();
        ArrayList<PublishedMediaV1> media = new ArrayList<>(source.media());
        media.removeIf(item -> item.assetId().equals(COVER_ASSET));
        media.add(missingEnglish);

        assertNotPublishable(
                () -> projections.project(withMedia(source, media), LocaleCode.EN),
                "PROJECT_NOT_PUBLISHABLE");
    }

    @Test
    void rejectsReferencedMediaMissingFromTheSnapshot() {
        ProjectSnapshotV1 source = projectSnapshot();
        List<PublishedMediaV1> media = source.media().stream()
                .filter(item -> !item.assetId().equals(DETAIL_ASSET))
                .toList();
        PublishedBlockV1 image = block(1, new PublishedBlockV1.ImagePayloadV1(DETAIL_ASSET));

        assertNotPublishable(
                () -> projections.project(
                        withBlocks(withMedia(source, media), List.of(image)), LocaleCode.EN),
                "PROJECT_NOT_PUBLISHABLE");
    }

    @Test
    void rejectsMediaWithoutAnyPublicVariant() {
        PublishedMediaV1 cover = imageMedia(COVER_ASSET, "cover");
        PublishedMediaV1 noVariants = new PublishedMediaV1(
                cover.assetId(),
                cover.contentType(),
                cover.contentLength(),
                cover.sha256(),
                cover.copy(),
                List.of());
        ProjectSnapshotV1 source = projectSnapshot();
        ArrayList<PublishedMediaV1> media = new ArrayList<>(source.media());
        media.removeIf(item -> item.assetId().equals(COVER_ASSET));
        media.add(noVariants);

        assertNotPublishable(
                () -> projections.project(withMedia(source, media), LocaleCode.EN),
                "PROJECT_NOT_PUBLISHABLE");
    }

    @Test
    void sortsResponsiveVariantsByWidthWhenSnapshotInputIsReversed() {
        PublicMediaDto cover = projections.project(projectSnapshot(), LocaleCode.EN).media().get(0);

        assertResponsiveImage(cover, COVER_ASSET);
    }

    @ParameterizedTest(name = "rejects invalid image variant: {0}")
    @MethodSource("invalidImageVariants")
    void rejectsInvalidImageVariantNamesAndDimensions(
            String description, List<PublishedMediaV1.Variant> variants) {
        ProjectSnapshotV1 source = projectSnapshot();
        PublishedMediaV1 cover = imageMedia(COVER_ASSET, "cover");
        PublishedMediaV1 invalid = new PublishedMediaV1(
                cover.assetId(),
                cover.contentType(),
                cover.contentLength(),
                cover.sha256(),
                cover.copy(),
                variants);

        assertNotPublishable(
                () -> projections.project(
                        withMedia(source, replaceMedia(source.media(), invalid)), LocaleCode.EN),
                "PROJECT_NOT_PUBLISHABLE");
    }

    @Test
    void rejectsNonHttpsLocalizedMediaSourceUrl() {
        PublishedMediaV1 cover = imageMedia(COVER_ASSET, "cover");
        PublishedMediaV1 unsafe = new PublishedMediaV1(
                cover.assetId(),
                cover.contentType(),
                cover.contentLength(),
                cover.sha256(),
                both(
                        cover.copy().get(LocaleV1.ZH_CN),
                        new PublishedMediaV1.MediaCopy(
                                "Alt", "Caption", "Credit", "http://example.com/source")),
                cover.variants());
        ProjectSnapshotV1 source = projectSnapshot();
        ArrayList<PublishedMediaV1> media = new ArrayList<>(source.media());
        media.removeIf(item -> item.assetId().equals(COVER_ASSET));
        media.add(unsafe);

        assertNotPublishable(
                () -> projections.project(withMedia(source, media), LocaleCode.EN),
                "PROJECT_NOT_PUBLISHABLE");
    }

    @Test
    void rejectsCatalogCardWithoutCover() {
        ProjectCatalogSnapshotV1.Card card = catalogCard(
                PROJECT_ID, "missing-cover", 0, COVER_ASSET, "Title", "标题");
        ProjectCatalogSnapshotV1 source = new ProjectCatalogSnapshotV1(
                1,
                List.of(new ProjectCatalogSnapshotV1.Card(
                        card.projectId(), card.slug(), card.number(), card.sortOrder(),
                        card.featured(), card.copy(), null)));

        assertNotPublishable(
                () -> projections.catalog(source, LocaleCode.EN),
                "PROJECT_CATALOG_NOT_PUBLISHABLE");
    }

    @Test
    void rejectsSiteMissingRequestedIdentityLocale() {
        SiteSnapshotV1 source = siteSnapshot();
        SiteContentV1 content = source.content();
        SiteSnapshotV1 invalid = withSiteIdentityHeroAndResumes(
                source,
                Map.of(LocaleV1.ZH_CN, content.identity().get(LocaleV1.ZH_CN)),
                content.hero(),
                content.resumes());

        assertNotPublishable(
                () -> projections.site(invalid, LocaleCode.EN),
                "SITE_NOT_PUBLISHABLE");
    }

    @Test
    void rejectsSiteWithoutCurrentResumeForRequestedLocale() {
        SiteSnapshotV1 source = siteSnapshot();
        SiteContentV1 content = source.content();
        SiteSnapshotV1 invalid = withSiteIdentityHeroAndResumes(
                source,
                content.identity(),
                content.hero(),
                content.resumes().stream()
                        .filter(resume -> resume.locale() != LocaleV1.EN || !resume.current())
                        .toList());

        assertNotPublishable(
                () -> projections.site(invalid, LocaleCode.EN),
                "SITE_NOT_PUBLISHABLE");
    }

    @Test
    void rejectsCurrentResumeMediaWithoutADocumentVariant() {
        SiteSnapshotV1 source = siteSnapshot();
        List<PublishedMediaV1> invalidMedia = source.media().stream()
                .map(media -> media.assetId().equals(RESUME_EN_ASSET)
                        ? imageMedia(RESUME_EN_ASSET, "resume-image")
                        : media)
                .toList();
        SiteSnapshotV1 invalid = new SiteSnapshotV1(
                source.schemaVersion(), source.siteId(), source.content(), invalidMedia);

        assertNotPublishable(
                () -> projections.site(invalid, LocaleCode.EN),
                "SITE_NOT_PUBLISHABLE");
    }

    @Test
    void allowsHeroWithoutMediaWhileKeepingDirectAttribution() {
        SiteSnapshotV1 source = siteSnapshot();
        SiteContentV1.HeroV1 hero = source.content().hero();
        SiteContentV1.HeroV1 withoutMedia = new SiteContentV1.HeroV1(
                hero.id(),
                null,
                null,
                null,
                null,
                hero.copy());
        SiteSnapshotV1 snapshot = withSiteIdentityHeroAndResumes(
                source,
                source.content().identity(),
                withoutMedia,
                source.content().resumes());
        snapshot = new SiteSnapshotV1(
                snapshot.schemaVersion(),
                snapshot.siteId(),
                snapshot.content(),
                snapshot.media().stream()
                        .filter(item -> !item.assetId().equals(HERO_ASSET))
                        .toList());

        PublicSiteDto site = projections.site(snapshot, LocaleCode.EN);

        assertThat(site.hero().media()).isNull();
        assertThat(site.hero().objectPosition()).isEmpty();
        assertThat(site.hero().credit()).isEmpty();
        assertThat(site.hero().sourceUrl()).isEmpty();
    }

    private static Stream<Arguments> safeVideoUrls() {
        return Stream.of(
                Arguments.of(
                        "youtube",
                        "https://www.youtube.com/watch?v=AbC_123-xyZ",
                        "https://www.youtube.com/embed/AbC_123-xyZ"),
                Arguments.of(
                        "youtube",
                        "https://youtu.be/AbC_123-xyZ",
                        "https://www.youtube.com/embed/AbC_123-xyZ"),
                Arguments.of(
                        "youtube",
                        "HTTPS://WWW.YOUTUBE.COM/watch?v=AbC_123-xyZ",
                        "https://www.youtube.com/embed/AbC_123-xyZ"),
                Arguments.of(
                        "vimeo",
                        "https://vimeo.com/987654321",
                        "https://player.vimeo.com/video/987654321"),
                Arguments.of(
                        "bilibili",
                        "https://www.bilibili.com/video/BV1xx411c7mD",
                        "https://player.bilibili.com/player.html?bvid=BV1xx411c7mD"),
                Arguments.of(
                        "bilibili",
                        "https://player.bilibili.com/player.html?bvid=BV1xx411c7mD",
                        "https://player.bilibili.com/player.html?bvid=BV1xx411c7mD"));
    }

    private static Stream<Arguments> unsafeVideoUrls() {
        return Stream.of(
                Arguments.of("youtube", "https://youtube.com.evil.example/watch?v=abc"),
                Arguments.of("youtube", "http://www.youtube.com/watch?v=abc"),
                Arguments.of("youtube", "https://attacker@www.youtube.com/watch?v=abc"),
                Arguments.of("youtube", "https://www.youtube.com/watch?v=abc#fragment"),
                Arguments.of(
                        "youtube",
                        "https://www.youtube.com/watch?v=abc&unexpected=value"),
                Arguments.of("youtube", "https://www.youtube.com/watch?v=abc&v=def"),
                Arguments.of("youtube", "https://youtu.be/AbC_123-xyZ?unexpected=value"),
                Arguments.of(
                        "youtube",
                        "https://www.youtube.com/embed/AbC_123-xyZ?unexpected=value"),
                Arguments.of(
                        "youtube",
                        "https://www.youtube.com/shorts/AbC_123-xyZ?unexpected=value"),
                Arguments.of("vimeo", "https://www.youtube.com/watch?v=abc"),
                Arguments.of("vimeo", "https://vimeo.com/987654321?unexpected=value"),
                Arguments.of("youtube", "DaTa:text/html,video"),
                Arguments.of("youtube", "JaVaScRiPt:alert(1)"),
                Arguments.of("youtube", "https://www.youtube.com/watch"),
                Arguments.of("youtube", "https://www.youtube.com/watch?v="),
                Arguments.of("vimeo", "https://example.com/987654321"),
                Arguments.of("bilibili", "https://bilibili.com.evil.example/video/BV1xx411c7mD"),
                Arguments.of(
                        "bilibili",
                        "https://www.bilibili.com/video/BV1xx411c7mD?unexpected=value"),
                Arguments.of(
                        "bilibili",
                        "https://player.bilibili.com/player.html?bvid=BV1xx411c7mD&x=1"));
    }

    private static Stream<String> unsafeExternalUrls() {
        return Stream.of(
                "https://user@example.com/private",
                "https://example.com/source#fragment",
                "DaTa:text/html,unsafe",
                "JaVaScRiPt:alert(1)");
    }

    private static Stream<Arguments> invalidImageVariants() {
        return Stream.of(
                Arguments.of(
                        "legacy numeric name",
                        List.of(new PublishedMediaV1.Variant(
                                "1280", 1280, 720, 25_000L, digest("legacy-name")))),
                Arguments.of(
                        "zero width name",
                        List.of(new PublishedMediaV1.Variant(
                                "w0", 0, 720, 25_000L, digest("zero-width")))),
                Arguments.of(
                        "name-width mismatch",
                        List.of(new PublishedMediaV1.Variant(
                                "w640", 1280, 720, 25_000L, digest("mismatch")))));
    }

    private static SiteSnapshotV1 siteSnapshot() {
        SiteContentV1 content = new SiteContentV1(
                "YJX",
                "hello@yychainsaw.xyz",
                both(
                        new SiteContentV1.IdentityCopyV1("易嘉轩", "Yi Jiaxuan"),
                        new SiteContentV1.IdentityCopyV1("Yi Jiaxuan", "易嘉轩")),
                both(
                        new SiteContentV1.SeoCopyV1("易嘉轩 — 游戏开发", "中文描述"),
                        new SiteContentV1.SeoCopyV1(
                                "Yi Jiaxuan — Game Developer", "English description")),
                both(accessibility("跳到正文"), accessibility("Skip to content")),
                List.of(
                        navigation("hidden", 0, false, "隐藏", "Hidden"),
                        navigation("about", 2, true, "关于", "About"),
                        navigation("work", 1, true, "作品", "Work")),
                new SiteContentV1.HeroV1(
                        UUID.fromString("40000000-0000-0000-0000-000000000001"),
                        HERO_ASSET,
                        "52% 40%",
                        "Direct hero credit",
                        URI.create("https://example.com/direct-hero"),
                        both(
                                heroCopy("我创造可玩的世界。", "在学 UE"),
                                heroCopy("I build playable worlds.", "Learning UE"))),
                both(
                        new SiteContentV1.AboutCopyV1(
                                "关于", "关于我", "陈述", "方向", "游戏开发", "介绍"),
                        new SiteContentV1.AboutCopyV1(
                                "About", "About me", "Statement", "Focus", "Game development", "Intro")),
                List.of(
                        fact(2, "年级", "大三", "Year", "Junior"),
                        fact(1, "学校", "江西师范大学", "University", "JXNU")),
                List.of(
                        skill(2, "Java", "熟悉", "Java", "Comfortable"),
                        skill(1, "虚幻引擎", "学习中", "Unreal Engine", "Learning")),
                both(
                        workCopy("作品"),
                        workCopy("Work")),
                new SiteContentV1.RoadmapV1(
                        both(
                                new SiteContentV1.RoadmapHeaderCopyV1("路线", "下一步", "中文介绍"),
                                new SiteContentV1.RoadmapHeaderCopyV1("Roadmap", "What's next", "English intro")),
                        List.of(
                                roadmapStage(0, false, "00", "隐藏", "Hidden"),
                                roadmapStage(2, true, "02", "发布作品", "Publish portfolio"),
                                roadmapStage(1, true, "01", "学习玩法框架", "Learn gameplay framework"))),
                both(
                        contactCopy("联系"),
                        contactCopy("Contact")),
                both(
                        new SiteContentV1.PrivacyCopyV1("隐私", "**隐私**说明"),
                        new SiteContentV1.PrivacyCopyV1(
                                "Privacy", "A **privacy** note.\n\n<script>alert(1)</script>")),
                List.of(
                        social("Hidden", "https://example.com/hidden", 0, false),
                        social("Bilibili", "https://space.bilibili.com/1", 2, true),
                        social("GitHub", "https://github.com/YYchainsAw", 1, true)),
                List.of(
                        resume(LocaleV1.EN, RESUME_EN_ASSET, "Resume EN old", false,
                                LocalDate.of(2025, 1, 1)),
                        resume(LocaleV1.EN, RESUME_EN_ASSET, "Resume EN current", true,
                                LocalDate.of(2026, 7, 2)),
                        resume(LocaleV1.ZH_CN, RESUME_ZH_ASSET, "中文简历 2026", true,
                                LocalDate.of(2026, 7, 1))));
        return new SiteSnapshotV1(
                1,
                SITE_ID,
                content,
                List.of(
                        documentMedia(RESUME_EN_ASSET),
                        imageMedia(HERO_ASSET, "hero"),
                        documentMedia(RESUME_ZH_ASSET)));
    }

    private static SiteSnapshotV1 withSiteIdentityHeroAndResumes(
            SiteSnapshotV1 source,
            Map<LocaleV1, SiteContentV1.IdentityCopyV1> identity,
            SiteContentV1.HeroV1 hero,
            List<SiteContentV1.ResumeDocumentV1> resumes) {
        SiteContentV1 content = source.content();
        SiteContentV1 replacement = new SiteContentV1(
                content.monogram(),
                content.email(),
                identity,
                content.seo(),
                content.accessibility(),
                content.navigation(),
                hero,
                content.about(),
                content.facts(),
                content.profileSkills(),
                content.work(),
                content.roadmap(),
                content.contact(),
                content.privacy(),
                content.socialLinks(),
                resumes);
        return new SiteSnapshotV1(
                source.schemaVersion(), source.siteId(), replacement, source.media());
    }

    private static ProjectSnapshotV1 projectSnapshot() {
        return new ProjectSnapshotV1(
                1,
                PROJECT_ID,
                "gameplay-prototype",
                "gameplay-prototype",
                "01",
                4,
                true,
                both(
                        new ProjectSnapshotV1.ProjectCopyV1(
                                "进行中", "玩法原型", "游戏玩法原型", "中文摘要", "中文 SEO", "中文 SEO 描述"),
                        new ProjectSnapshotV1.ProjectCopyV1(
                                "In progress", "Prototype", "Gameplay Prototype", "Summary",
                                "Gameplay Prototype SEO", "English SEO description")),
                List.of(
                        taxonomy(2, "prototype", "原型", "Prototype"),
                        taxonomy(1, "game", "游戏", "Game")),
                List.of(
                        taxonomy(2, "cpp", "C++", "C++"),
                        taxonomy(1, "unreal-engine", "虚幻引擎", "Unreal Engine")),
                List.of(
                        projectMedia(DETAIL_ASSET, "DETAIL", 2),
                        projectMedia(COVER_ASSET, "COVER", 1)),
                richBlocks(),
                List.of(
                        imageMedia(VIDEO_ASSET, "video"),
                        documentMedia(DOWNLOAD_ASSET),
                        imageMedia(GALLERY_ASSET, "gallery"),
                        imageMedia(DETAIL_ASSET, "detail"),
                        imageMedia(COVER_ASSET, "cover")));
    }

    private static List<PublishedBlockV1> richBlocks() {
        return List.of(
                hiddenBlock(),
                block(10, new PublishedBlockV1.LinkPayloadV1(
                        URI.create("https://github.com/YYchainsAw/project"),
                        true,
                        both(
                                new PublishedBlockV1.ActionCopyV1("源代码", "项目源代码"),
                                new PublishedBlockV1.ActionCopyV1("Source code", "Project source")))),
                block(9, new PublishedBlockV1.DownloadPayloadV1(
                        null,
                        URI.create("https://example.com/design-notes.pdf"),
                        both(
                                new PublishedBlockV1.ActionCopyV1("设计说明", "外部 PDF"),
                                new PublishedBlockV1.ActionCopyV1("Design notes", "External PDF")))),
                block(8, new PublishedBlockV1.DownloadPayloadV1(
                        DOWNLOAD_ASSET,
                        null,
                        both(
                                new PublishedBlockV1.ActionCopyV1("下载构建", "PDF"),
                                new PublishedBlockV1.ActionCopyV1("Download build", "PDF")))),
                block(7, new PublishedBlockV1.MetricsPayloadV1(List.of(
                        metric(2, "试玩者", "12", "人", "Playtesters", "12", " players"),
                        metric(1, "帧率", "60", " FPS", "Frame rate", "60", " FPS")))),
                block(6, new PublishedBlockV1.QuotePayloadV1(both(
                        new PublishedBlockV1.QuoteCopyV1("让它可玩。", "易嘉轩"),
                        new PublishedBlockV1.QuoteCopyV1("Make it playable.", "Yi Jiaxuan")))),
                block(5, new PublishedBlockV1.CodePayloadV1(
                        "return true;",
                        "cpp",
                        true,
                        both(
                                new PublishedBlockV1.BlockCopyV1("能力代码", "代码说明"),
                                new PublishedBlockV1.BlockCopyV1("Ability code", "Code description")))),
                block(4, new PublishedBlockV1.VideoPayloadV1(
                        "youtube",
                        URI.create("https://www.youtube.com/watch?v=AbC_123-xyZ"),
                        VIDEO_ASSET,
                        both(
                                new PublishedBlockV1.BlockCopyV1("玩法视频", "视频说明"),
                                new PublishedBlockV1.BlockCopyV1("Gameplay video", "Video description")))),
                block(3, new PublishedBlockV1.GalleryPayloadV1(
                        List.of(GALLERY_ASSET, COVER_ASSET))),
                block(2, new PublishedBlockV1.ImagePayloadV1(DETAIL_ASSET)),
                block(1, new PublishedBlockV1.MarkdownPayloadV1(both(
                        "**已发布**内容",
                        "A **published** section.\n\n<script>alert(1)</script>"))));
    }

    private static ProjectSnapshotV1 projectWithSingleVideo(String provider, URI uri) {
        PublishedBlockV1 video = block(
                1,
                new PublishedBlockV1.VideoPayloadV1(
                        provider,
                        uri,
                        VIDEO_ASSET,
                        both(
                                new PublishedBlockV1.BlockCopyV1("视频", "描述"),
                                new PublishedBlockV1.BlockCopyV1("Video", "Description"))));
        return withBlocks(projectSnapshot(), List.of(video));
    }

    private static ProjectCatalogSnapshotV1.Card catalogCard(
            UUID projectId,
            String slug,
            int sortOrder,
            UUID assetId,
            String englishTitle,
            String chineseTitle) {
        String prefix = assetId.equals(COVER_ASSET) ? "cover" : "gallery";
        return new ProjectCatalogSnapshotV1.Card(
                projectId,
                slug,
                String.format("%02d", sortOrder),
                sortOrder,
                true,
                both(
                        new ProjectCatalogSnapshotV1.CardCopy(
                                "进行中", "项目", chineseTitle, "中文摘要", List.of("游戏", "原型")),
                        new ProjectCatalogSnapshotV1.CardCopy(
                                "In progress", "Project", englishTitle, "Summary",
                                List.of("Game", "Prototype"))),
                imageMedia(assetId, prefix));
    }

    private static SiteContentV1.AccessibilityCopyV1 accessibility(String skip) {
        return new SiteContentV1.AccessibilityCopyV1(
                skip, "Primary navigation", "Mobile navigation", "Open menu", "Close menu",
                "Language", "Back to top", "Project tags");
    }

    private static SiteContentV1.NavigationItemV1 navigation(
            String target, int sortOrder, boolean visible, String zh, String en) {
        return new SiteContentV1.NavigationItemV1(
                UUID.nameUUIDFromBytes(("nav-" + target).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                target,
                sortOrder,
                visible,
                both(zh, en));
    }

    private static SiteContentV1.HeroCopyV1 heroCopy(String headline, String availability) {
        return new SiteContentV1.HeroCopyV1(
                "Eyebrow", "Yi Jiaxuan", "易嘉轩", "Game developer", headline,
                "Introduction", availability, "View work", "About me", "Visual", "Stage");
    }

    private static SiteContentV1.ProfileFactV1 fact(
            int sortOrder, String zhLabel, String zhValue, String enLabel, String enValue) {
        return new SiteContentV1.ProfileFactV1(
                UUID.nameUUIDFromBytes(("fact-" + sortOrder).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "fact-" + sortOrder,
                sortOrder,
                both(
                        new SiteContentV1.LabelValueCopyV1(zhLabel, zhValue),
                        new SiteContentV1.LabelValueCopyV1(enLabel, enValue)));
    }

    private static SiteContentV1.ProfileSkillV1 skill(
            int sortOrder, String zhName, String zhStatus, String enName, String enStatus) {
        return new SiteContentV1.ProfileSkillV1(
                UUID.nameUUIDFromBytes(("skill-" + sortOrder).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "skill-" + sortOrder,
                sortOrder,
                both(
                        new SiteContentV1.SkillStatusCopyV1(zhName, zhStatus),
                        new SiteContentV1.SkillStatusCopyV1(enName, enStatus)));
    }

    private static SiteContentV1.WorkCopyV1 workCopy(String label) {
        return new SiteContentV1.WorkCopyV1(
                label, "Projects", "Introduction", "Image notice", "Open slot", "Next work",
                "Open text", "Open meta");
    }

    private static SiteContentV1.RoadmapStageV1 roadmapStage(
            int sortOrder, boolean visible, String number, String zhTitle, String enTitle) {
        return new SiteContentV1.RoadmapStageV1(
                UUID.nameUUIDFromBytes(("stage-" + sortOrder).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "stage-" + sortOrder,
                number,
                sortOrder,
                visible,
                both(
                        new SiteContentV1.RoadmapStageCopyV1("2026", zhTitle, "中文摘要"),
                        new SiteContentV1.RoadmapStageCopyV1("2026", enTitle, "English summary")),
                List.of(
                        new SiteContentV1.RoadmapOutcomeV1(
                                UUID.nameUUIDFromBytes(("outcome-b-" + sortOrder)
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                                2,
                                both("发布原型", "Ship a prototype")),
                        new SiteContentV1.RoadmapOutcomeV1(
                                UUID.nameUUIDFromBytes(("outcome-a-" + sortOrder)
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                                1,
                                both("学习玩法框架", "Learn gameplay framework"))));
    }

    private static SiteContentV1.ContactCopyV1 contactCopy(String label) {
        return new SiteContentV1.ContactCopyV1(
                label, "Let's talk", "Introduction", "Email", "View work", "Roadmap", "Footer");
    }

    private static SiteContentV1.SocialLinkV1 social(
            String platform, String url, int sortOrder, boolean visible) {
        return new SiteContentV1.SocialLinkV1(
                UUID.nameUUIDFromBytes(("social-" + platform)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                platform,
                URI.create(url),
                sortOrder,
                visible);
    }

    private static SiteContentV1.ResumeDocumentV1 resume(
            LocaleV1 locale, UUID assetId, String label, boolean current, LocalDate date) {
        return new SiteContentV1.ResumeDocumentV1(
                UUID.nameUUIDFromBytes(("resume-" + locale + '-' + label)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                locale,
                assetId,
                label,
                current,
                date);
    }

    private static ProjectSnapshotV1.TaxonomyRefV1 taxonomy(
            int sortOrder, String key, String zh, String en) {
        return new ProjectSnapshotV1.TaxonomyRefV1(
                UUID.nameUUIDFromBytes(("taxonomy-" + key)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                key,
                sortOrder,
                both(zh, en));
    }

    private static ProjectSnapshotV1.ProjectMediaV1 projectMedia(
            UUID assetId, String usage, int sortOrder) {
        return new ProjectSnapshotV1.ProjectMediaV1(
                assetId,
                usage,
                sortOrder,
                "wide",
                "50% 50%",
                "Direct project media credit",
                URI.create("https://example.com/direct-project-media"));
    }

    private static PublishedBlockV1 hiddenBlock() {
        return new PublishedBlockV1(
                UUID.fromString("50000000-0000-0000-0000-000000000099"),
                0,
                false,
                PublishedBlockV1.WidthV1.STANDARD,
                PublishedBlockV1.AlignmentV1.LEFT,
                PublishedBlockV1.EmphasisV1.NONE,
                1,
                new PublishedBlockV1.MarkdownPayloadV1(both("隐藏", "Hidden")));
    }

    private static PublishedBlockV1 block(int sortOrder, PublishedBlockV1.PayloadV1 payload) {
        return new PublishedBlockV1(
                UUID.nameUUIDFromBytes(("block-" + sortOrder + '-' + payload.getClass().getSimpleName())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                sortOrder,
                true,
                PublishedBlockV1.WidthV1.WIDE,
                PublishedBlockV1.AlignmentV1.LEFT,
                PublishedBlockV1.EmphasisV1.SOFT,
                payload instanceof PublishedBlockV1.GalleryPayloadV1 ? 2 : 1,
                payload);
    }

    private static PublishedBlockV1.MetricV1 metric(
            int sortOrder,
            String zhLabel,
            String zhValue,
            String zhSuffix,
            String enLabel,
            String enValue,
            String enSuffix) {
        return new PublishedBlockV1.MetricV1(
                UUID.nameUUIDFromBytes(("metric-" + sortOrder)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                sortOrder,
                new BigDecimal(enValue),
                both(
                        new PublishedBlockV1.MetricCopyV1(zhLabel, zhValue, zhSuffix),
                        new PublishedBlockV1.MetricCopyV1(enLabel, enValue, enSuffix)));
    }

    private static PublishedMediaV1 imageMedia(UUID assetId, String prefix) {
        return new PublishedMediaV1(
                assetId,
                "image/jpeg",
                50_000L,
                digest(prefix + "-asset"),
                both(
                        new PublishedMediaV1.MediaCopy(
                                "中文" + prefix + "替代文本",
                                "中文" + prefix + "说明",
                                "中文" + prefix + "媒体署名",
                                "https://example.com/zh/" + prefix),
                        new PublishedMediaV1.MediaCopy(
                                "English " + prefix + " alt",
                                "English " + prefix + " caption",
                                "English " + prefix + " credit",
                                "https://example.com/en/" + prefix)),
                List.of(
                        new PublishedMediaV1.Variant(
                                "w1280", 1280, 720, 25_000L, digest(prefix + "-1280")),
                        new PublishedMediaV1.Variant(
                                "w640", 640, 360, 10_000L, digest(prefix + "-640"))));
    }

    private static PublishedMediaV1 documentMedia(UUID assetId) {
        return new PublishedMediaV1(
                assetId,
                "application/pdf",
                8192L,
                digest(assetId + "-asset"),
                both(
                        new PublishedMediaV1.MediaCopy("中文 PDF", null, null, null),
                        new PublishedMediaV1.MediaCopy("English PDF", null, null, null)),
                List.of(new PublishedMediaV1.Variant(
                        "document", 0, 0, 8192L, digest(assetId + "-document"))));
    }

    private static ProjectSnapshotV1 withBlocks(
            ProjectSnapshotV1 source, List<PublishedBlockV1> blocks) {
        return new ProjectSnapshotV1(
                1,
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
                blocks,
                source.media());
    }

    private static ProjectSnapshotV1 withMedia(
            ProjectSnapshotV1 source, List<PublishedMediaV1> media) {
        return new ProjectSnapshotV1(
                1,
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

    private static List<PublishedMediaV1> replaceMedia(
            List<PublishedMediaV1> source, PublishedMediaV1 replacement) {
        ArrayList<PublishedMediaV1> media = new ArrayList<>(source);
        media.removeIf(item -> item.assetId().equals(replacement.assetId()));
        media.add(replacement);
        return List.copyOf(media);
    }

    private static void assertResponsiveImage(PublicMediaDto media, UUID assetId) {
        assertThat(media.variant()).isEqualTo("w1280");
        assertThat(media.src()).isEqualTo(mediaPath(assetId, "w1280"));
        assertThat(media.srcset()).isEqualTo(
                mediaPath(assetId, "w640") + " 640w, "
                        + mediaPath(assetId, "w1280") + " 1280w");
        assertThat(media.width()).isEqualTo(1280);
        assertThat(media.height()).isEqualTo(720);
    }

    private static String mediaPath(UUID assetId, String variant) {
        return "/api/public/media/" + assetId + '/' + variant;
    }

    private static String digest(String seed) {
        String hex = java.util.HexFormat.of().formatHex(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return (hex + "0".repeat(64)).substring(0, 64);
    }

    private static <T> Map<LocaleV1, T> both(T chinese, T english) {
        return Map.of(LocaleV1.ZH_CN, chinese, LocaleV1.EN, english);
    }

    private static void assertNotPublishable(ThrowingCallable action, String code) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(DomainException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(code);
                    assertThat(failure.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(failure.fieldErrors()).isNotEmpty();
                });
    }

    private static void assertNotPublishableWithoutEcho(
            ThrowingCallable action, String code, String sensitiveValue) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(DomainException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(code);
                    assertThat(failure.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(failure.fieldErrors()).isNotEmpty();
                    assertThat(failure.getMessage()).doesNotContain(sensitiveValue);
                    assertThat(failure.fieldErrors().toString()).doesNotContain(sensitiveValue);
                });
    }
}
