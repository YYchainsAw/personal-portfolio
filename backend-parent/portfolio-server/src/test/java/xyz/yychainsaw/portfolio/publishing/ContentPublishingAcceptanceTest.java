package xyz.yychainsaw.portfolio.publishing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.HtmlUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import xyz.yychainsaw.portfolio.auth.CurrentAdminProvider;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.content.api.ProjectWorkspaceDto;
import xyz.yychainsaw.portfolio.content.api.SiteWorkspaceDto;
import xyz.yychainsaw.portfolio.content.importer.ImportReport;
import xyz.yychainsaw.portfolio.content.importer.PortfolioImportService;
import xyz.yychainsaw.portfolio.content.persistence.ProjectWorkspaceRepository;
import xyz.yychainsaw.portfolio.content.persistence.SiteWorkspaceRepository;
import xyz.yychainsaw.portfolio.media.application.MediaQueryService;
import xyz.yychainsaw.portfolio.media.staging.LocalStagingPolicyProperties;
import xyz.yychainsaw.portfolio.media.storage.LocalStorageService;
import xyz.yychainsaw.portfolio.publicapi.PublishedEnvelope;
import xyz.yychainsaw.portfolio.publicapi.PublicMediaDto;
import xyz.yychainsaw.portfolio.publicapi.PublicProjectDto;
import xyz.yychainsaw.portfolio.publishing.api.PublicationResult;
import xyz.yychainsaw.portfolio.publishing.api.PublishProjectCommand;
import xyz.yychainsaw.portfolio.publishing.api.PublishSiteCommand;
import xyz.yychainsaw.portfolio.publishing.application.PublicSnapshotQueryService;
import xyz.yychainsaw.portfolio.publishing.application.PublicationService;
import xyz.yychainsaw.portfolio.publishing.application.RestoreService;
import xyz.yychainsaw.portfolio.publishing.persistence.PublishingRepository;
import xyz.yychainsaw.portfolio.publishing.persistence.PublishingRepository.PublicationRow;
import xyz.yychainsaw.portfolio.publishing.snapshot.AggregateType;
import xyz.yychainsaw.portfolio.publishing.support.PublishingTestFixture;
import xyz.yychainsaw.portfolio.support.PostgresTestImage;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("integration")
@Isolated
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import({
    PublishingTestFixture.class,
    ContentPublishingAcceptanceTest.FixedAdminConfiguration.class,
    ContentPublishingAcceptanceTest.ImportCapacityConfiguration.class
})
class ContentPublishingAcceptanceTest {
    private static final Pattern BOOTSTRAP = Pattern.compile(
            "<template id=\"__PORTFOLIO_DATA__\">(.*?)</template>",
            Pattern.DOTALL);
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(PostgresTestImage.NAME)
                    .withDatabaseName("portfolio_test")
                    .withUsername("test_owner")
                    .withPassword("test_owner_password")
                    .withInitScript("db/test/00-test-roles.sql");
    private static final Path INPUT = Path.of(
            "src/test/resources/import/portfolio-v1-publishable.json");
    private static final Path ASSET_ROOT = Path.of(
            "src/test/resources/import/assets");
    private static final Path STORAGE_ROOT = isolatedStorageRoot();
    private static final String DRAFT_TITLE = "UNPUBLISHED ACCEPTANCE DRAFT";

    static {
        POSTGRES.start();
    }

    @Autowired PortfolioImportService importer;
    @Autowired PublicationService publications;
    @Autowired PublicSnapshotQueryService publicQueries;
    @Autowired ProjectWorkspaceRepository projects;
    @Autowired SiteWorkspaceRepository sites;
    @Autowired PublishingRepository publishing;
    @Autowired RestoreService restore;
    @Autowired PublishingTestFixture fixture;
    @Autowired MediaQueryService media;
    @Autowired LocalStorageService localStorage;
    @Autowired TransactionTemplate transactions;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @DynamicPropertySource
    static void acceptanceProperties(DynamicPropertyRegistry registry) {
        String url = portfolioJdbcUrl(POSTGRES.getJdbcUrl());
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> "test_runtime");
        registry.add("spring.datasource.password", () -> "runtime_test_password");
        registry.add("spring.flyway.url", () -> url);
        registry.add("spring.flyway.user", () -> "test_migrator");
        registry.add("spring.flyway.password", () -> "migrator_test_password");
        registry.add("portfolio.recovery.host", POSTGRES::getHost);
        registry.add("portfolio.recovery.port", POSTGRES::getFirstMappedPort);
        registry.add("portfolio.recovery.database", POSTGRES::getDatabaseName);
        registry.add("portfolio.recovery.username", () -> "test_migrator");
        registry.add("portfolio.recovery.password", () -> "migrator_test_password");
        registry.add("portfolio.security.totp.active-key-version", () -> "1");
        registry.add("portfolio.security.totp.key-ring",
                () -> "1=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");
        registry.add("server.servlet.session.cookie.secure", () -> "false");
        registry.add("portfolio.storage.local.root", STORAGE_ROOT::toString);
    }

    @BeforeEach
    void authenticatePublicationFixture() {
        fixture.ensureAdmin();
    }

    @AfterAll
    void removeAcceptanceStorage() throws IOException {
        requireDedicatedStorageRoot();
        localStorage.close();
        if (!Files.exists(STORAGE_ROOT)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(STORAGE_ROOT)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void importPublishEditRestoreRepublishKeepsEveryPublicSurfaceAtomic()
            throws Exception {
        ImportReport report = importer.commit(INPUT, ASSET_ROOT, sha256(INPUT));
        assertThat(report.committed()).isTrue();
        assertThat(report.hasStructureErrors()).isFalse();
        assertThat(report.issues()).isEmpty();
        assertThat(report.projectCount()).isEqualTo(3);
        assertThat(report.mediaCount()).isEqualTo(4);
        assertThat(report.tagCount()).isEqualTo(8);

        List<ProjectWorkspaceDto> imported = projects.findAll().stream()
                .sorted(Comparator.comparingInt(ProjectWorkspaceDto::sortOrder))
                .toList();
        assertThat(imported).hasSize(3);
        ProjectWorkspaceDto target = imported.get(0);
        List<ProjectWorkspaceDto> neverPublished = imported.subList(1, imported.size());

        publishImportedSite();
        String englishPrivacy = html("/en/privacy");
        String chinesePrivacy = html("/zh-CN/privacy");
        assertThat(englishPrivacy).contains("Privacy");
        assertThat(chinesePrivacy).contains("隐私说明");
        assertPrivacyBootstrap(bootstrap(englishPrivacy), "en");
        assertPrivacyBootstrap(bootstrap(chinesePrivacy), "zh-CN");

        String targetVariant = firstVariant(target);
        assertMediaNotFound(target.media().get(0).assetId(), targetVariant);

        PublicationRow catalogBefore = catalogPointer();
        PublicationResult first = publications.publishProject(new PublishProjectCommand(
                target.id(), target.version(), 0L, catalogBefore.version()));

        PublicationRow firstProjectPointer = projectPointer(target.id());
        PublicationRow firstCatalogPointer = catalogPointer();
        assertAtomicPointers(first, target, firstProjectPointer, firstCatalogPointer);

        String englishTitle = target.translations().get(LocaleCode.EN).title();
        String chineseTitle = target.translations().get(LocaleCode.ZH_CN).title();
        assertThat(englishTitle).isNotEqualTo(chineseTitle);

        PublishedEnvelope<PublicProjectDto> firstEnglish =
                publicQueries.project(target.slug(), LocaleCode.EN);
        PublishedEnvelope<PublicProjectDto> firstChinese =
                publicQueries.project(target.slug(), LocaleCode.ZH_CN);
        assertThat(firstEnglish.revisionVersion()).isEqualTo(first.aggregateVersion());
        assertThat(firstEnglish.data().title()).isEqualTo(englishTitle);
        assertThat(firstChinese.data().title()).isEqualTo(chineseTitle);
        assertLocalizedMedia(firstEnglish.data(), firstChinese.data(), target);

        JsonNode englishJson = publicProjectJson(target.slug(), "en");
        JsonNode chineseJson = publicProjectJson(target.slug(), "zh-CN");
        assertThat(englishJson.path("data").path("title").asText())
                .isEqualTo(englishTitle);
        assertThat(chineseJson.path("data").path("title").asText())
                .isEqualTo(chineseTitle);
        assertPublicJsonDoesNotLeakStorage(englishJson);
        assertPublicJsonDoesNotLeakStorage(chineseJson);

        String englishProjectHtml = html("/en/projects/" + target.slug());
        String chineseProjectHtml = html("/zh-CN/projects/" + target.slug());
        assertLocalizedProjectHtml(
                englishProjectHtml,
                chineseProjectHtml,
                target.slug(),
                englishTitle,
                chineseTitle);
        assertProjectBootstrap(
                bootstrap(englishProjectHtml), "en", target.slug(), englishTitle);
        assertProjectBootstrap(
                bootstrap(chineseProjectHtml), "zh-CN", target.slug(), chineseTitle);
        String sitemap = html("/sitemap.xml");
        assertThat(sitemap)
                .contains("/en/projects/" + target.slug())
                .contains("/zh-CN/projects/" + target.slug());
        for (ProjectWorkspaceDto hidden : neverPublished) {
            assertThat(sitemap).doesNotContain(hidden.slug());
            assertProjectNotFound(hidden.slug());
            assertMediaNotFound(hidden.media().get(0).assetId(), firstVariant(hidden));
        }

        String englishHome = html("/en");
        String chineseHome = html("/zh-CN");
        assertThat(englishHome).contains(HtmlUtils.htmlEscape(englishTitle));
        assertThat(chineseHome).contains(HtmlUtils.htmlEscape(chineseTitle));
        neverPublished.forEach(hidden -> {
            assertThat(englishHome).doesNotContain(hidden.slug());
            assertThat(chineseHome).doesNotContain(hidden.slug());
        });
        assertHomeBootstrap(bootstrap(englishHome), "en", target.slug(), englishTitle);
        assertHomeBootstrap(bootstrap(chineseHome), "zh-CN", target.slug(), chineseTitle);
        JsonNode englishCatalog = publicCatalogJson("en");
        JsonNode chineseCatalog = publicCatalogJson("zh-CN");

        PublicMediaDto publishedMedia = firstEnglish.data().media().get(0);
        mvc.perform(get(publishedMedia.src())).andExpect(status().isOk());
        assertMediaNotFound(UUID.randomUUID(), publishedMedia.variant());

        ProjectWorkspaceDto draftMediaSource = neverPublished.get(0);
        String draftSlug = target.slug() + "-draft";
        ProjectWorkspaceDto edited = transactions.execute(transaction -> {
            ProjectWorkspaceDto current = projects.require(target.id());
            projects.replace(
                    withDraftChanges(current, draftSlug, draftMediaSource.media()),
                    current.version());
            return projects.require(target.id());
        });
        assertThat(edited).isNotNull();
        assertThat(edited.publicationDirty()).isTrue();
        assertThat(edited.slug()).isEqualTo(draftSlug);
        assertThat(edited.translations().get(LocaleCode.EN).title())
                .isEqualTo(DRAFT_TITLE);
        assertThat(edited.media().get(0).assetId())
                .isEqualTo(draftMediaSource.media().get(0).assetId());

        var projectHistoryBeforeConflict =
                publishing.history(AggregateType.PROJECT, target.id());
        var catalogHistoryBeforeConflict = publishing.history(
                AggregateType.PROJECT_CATALOG, PublicationService.PROJECT_CATALOG_ID);
        assertThatThrownBy(() -> publications.publishProject(new PublishProjectCommand(
                        edited.id(),
                        edited.version(),
                        firstProjectPointer.version(),
                        catalogBefore.version())))
                .isInstanceOfSatisfying(DomainException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("CATALOG_VERSION_CONFLICT");
                    assertThat(failure.status()).isEqualTo(HttpStatus.CONFLICT);
                });
        assertThat(projectPointer(target.id())).isEqualTo(firstProjectPointer);
        assertThat(catalogPointer()).isEqualTo(firstCatalogPointer);
        assertThat(publishing.history(AggregateType.PROJECT, target.id()))
                .isEqualTo(projectHistoryBeforeConflict);
        assertThat(publishing.history(
                        AggregateType.PROJECT_CATALOG,
                        PublicationService.PROJECT_CATALOG_ID))
                .isEqualTo(catalogHistoryBeforeConflict);
        assertThat(publicQueries.project(target.slug(), LocaleCode.EN)).isEqualTo(firstEnglish);
        assertThat(publicQueries.project(target.slug(), LocaleCode.ZH_CN)).isEqualTo(firstChinese);
        assertThat(publicProjectJson(target.slug(), "en")).isEqualTo(englishJson);
        assertThat(publicProjectJson(target.slug(), "zh-CN")).isEqualTo(chineseJson);
        assertThat(publicCatalogJson("en")).isEqualTo(englishCatalog);
        assertThat(publicCatalogJson("zh-CN")).isEqualTo(chineseCatalog);
        assertThat(html("/en")).isEqualTo(englishHome);
        assertThat(html("/zh-CN")).isEqualTo(chineseHome);
        assertProjectNotFound(draftSlug);
        String draftSitemap = html("/sitemap.xml");
        assertThat(draftSitemap)
                .contains("/en/projects/" + target.slug())
                .contains("/zh-CN/projects/" + target.slug())
                .doesNotContain(draftSlug);
        assertThat(html("/en/projects/" + target.slug()))
                .isEqualTo(englishProjectHtml)
                .doesNotContain(DRAFT_TITLE);
        assertThat(html("/zh-CN/projects/" + target.slug()))
                .isEqualTo(chineseProjectHtml)
                .doesNotContain(DRAFT_TITLE);
        mvc.perform(get(publishedMedia.src())).andExpect(status().isOk());
        assertMediaNotFound(
                draftMediaSource.media().get(0).assetId(), firstVariant(draftMediaSource));

        restore.restore(first.revisionId(), edited.version());
        ProjectWorkspaceDto restored = projects.require(target.id());
        assertThat(restored.publicationDirty()).isTrue();
        assertThat(restored.slug()).isEqualTo(target.slug());
        assertThat(restored.translations().get(LocaleCode.EN).title())
                .isEqualTo(englishTitle);
        assertThat(restored.media()).isEqualTo(target.media());
        assertThat(projectPointer(target.id())).isEqualTo(firstProjectPointer);
        assertThat(catalogPointer()).isEqualTo(firstCatalogPointer);
        assertThat(publicQueries.project(target.slug(), LocaleCode.EN)).isEqualTo(firstEnglish);
        assertThat(publicProjectJson(target.slug(), "en")).isEqualTo(englishJson);

        PublicationResult second = publications.publishProject(new PublishProjectCommand(
                restored.id(),
                restored.version(),
                firstProjectPointer.version(),
                firstCatalogPointer.version()));
        PublicationRow secondProjectPointer = projectPointer(target.id());
        PublicationRow secondCatalogPointer = catalogPointer();
        assertAtomicPointers(second, restored, secondProjectPointer, secondCatalogPointer);
        assertThat(second.revisionId()).isNotEqualTo(first.revisionId());
        assertThat(second.aggregateVersion()).isEqualTo(first.aggregateVersion() + 1L);
        assertThat(second.catalogVersion()).isEqualTo(firstCatalogPointer.version() + 1L);

        PublishedEnvelope<PublicProjectDto> republishedEnglish =
                publicQueries.project(target.slug(), LocaleCode.EN);
        PublishedEnvelope<PublicProjectDto> republishedChinese =
                publicQueries.project(target.slug(), LocaleCode.ZH_CN);
        assertThat(republishedEnglish.data()).isEqualTo(firstEnglish.data());
        assertThat(republishedChinese.data()).isEqualTo(firstChinese.data());
        assertThat(republishedEnglish.checksum()).isEqualTo(firstEnglish.checksum());
        assertThat(republishedEnglish.revisionVersion())
                .isEqualTo(second.aggregateVersion());
        assertThat(html("/en/projects/" + target.slug()))
                .contains(HtmlUtils.htmlEscape(englishTitle))
                .doesNotContain(DRAFT_TITLE);
        mvc.perform(get(publishedMedia.src())).andExpect(status().isOk());
    }

    private void publishImportedSite() {
        UUID resumeAssetId = fixture.persistReadyDocument();
        SiteWorkspaceDto ready = transactions.execute(transaction -> {
            SiteWorkspaceDto imported = sites.require();
            SiteWorkspaceDto publishable = withBackendOnlyPublicationFields(
                    imported, resumeAssetId);
            sites.replace(publishable, imported.version());
            return sites.require();
        });
        assertThat(ready).isNotNull();
        PublicationRow pointer = publishing.find(
                        AggregateType.SITE, SiteWorkspaceDto.SITE_ID)
                .orElseThrow();
        publications.publishSite(new PublishSiteCommand(
                ready.version(), pointer.version()));

        var english = publicQueries.site(LocaleCode.EN).data();
        var chinese = publicQueries.site(LocaleCode.ZH_CN).data();
        assertThat(english.identity().displayName())
                .isNotEqualTo(chinese.identity().displayName());
        assertThat(english.hero().media().alt())
                .isNotEqualTo(chinese.hero().media().alt());
    }

    private static SiteWorkspaceDto withBackendOnlyPublicationFields(
            SiteWorkspaceDto source, UUID resumeAssetId) {
        Map<LocaleCode, SiteWorkspaceDto.PrivacyCopy> privacy = Map.of(
                LocaleCode.ZH_CN,
                new SiteWorkspaceDto.PrivacyCopy(
                        "隐私说明", "本站仅处理提供页面与必要安全功能所需的数据。"),
                LocaleCode.EN,
                new SiteWorkspaceDto.PrivacyCopy(
                        "Privacy", "This site processes only data required for pages and essential security."));
        List<SiteWorkspaceDto.ResumeDocument> resumes = List.of(
                new SiteWorkspaceDto.ResumeDocument(
                        UUID.randomUUID(), LocaleCode.ZH_CN, resumeAssetId,
                        "2026.1", true, LocalDate.of(2026, 7, 18)),
                new SiteWorkspaceDto.ResumeDocument(
                        UUID.randomUUID(), LocaleCode.EN, resumeAssetId,
                        "2026.1", true, LocalDate.of(2026, 7, 18)));
        return new SiteWorkspaceDto(
                source.siteId(),
                source.version(),
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
                privacy,
                source.socialLinks(),
                resumes);
    }

    private void assertAtomicPointers(
            PublicationResult result,
            ProjectWorkspaceDto workspace,
            PublicationRow projectPointer,
            PublicationRow catalogPointer) {
        assertThat(projectPointer.status()).isEqualTo("PUBLISHED");
        assertThat(projectPointer.currentRevisionId()).isEqualTo(result.revisionId());
        assertThat(projectPointer.currentSlug()).isEqualTo(workspace.slug());
        assertThat(projectPointer.version()).isEqualTo(result.aggregateVersion());
        assertThat(catalogPointer.status()).isEqualTo("PUBLISHED");
        assertThat(catalogPointer.currentRevisionId())
                .isEqualTo(result.catalogRevisionId());
        assertThat(catalogPointer.version()).isEqualTo(result.catalogVersion());
        assertThat(catalogPointer.publishedAt()).isEqualTo(projectPointer.publishedAt());
        assertThat(projects.require(workspace.id()).publicationDirty()).isFalse();
    }

    private void assertLocalizedMedia(
            PublicProjectDto english,
            PublicProjectDto chinese,
            ProjectWorkspaceDto workspace) {
        PublicMediaDto en = english.media().get(0);
        PublicMediaDto zh = chinese.media().get(0);
        ProjectWorkspaceDto.ProjectMedia source = workspace.media().get(0);
        var sourceDescriptor = media.requireReadyAsset(source.assetId());
        var englishCopy = sourceDescriptor.copyByLocale().get("en");
        var chineseCopy = sourceDescriptor.copyByLocale().get("zh-CN");
        assertThat(en.assetId()).isEqualTo(source.assetId());
        assertThat(zh.assetId()).isEqualTo(source.assetId());
        assertThat(englishCopy).isNotNull();
        assertThat(chineseCopy).isNotNull();
        assertThat(en.alt()).isEqualTo(englishCopy.alt());
        assertThat(zh.alt()).isEqualTo(chineseCopy.alt());
        assertThat(en.caption()).isEqualTo(
                englishCopy.caption() == null ? "" : englishCopy.caption());
        assertThat(zh.caption()).isEqualTo(
                chineseCopy.caption() == null ? "" : chineseCopy.caption());
        assertThat(en.credit()).isEqualTo(source.credit());
        assertThat(zh.credit()).isEqualTo(source.credit());
        assertThat(en.sourceUrl()).isEqualTo(source.sourceUrl().toString());
        assertThat(zh.sourceUrl()).isEqualTo(source.sourceUrl().toString());
        assertThat(en.src()).isEqualTo(
                "/api/public/media/" + en.assetId() + '/' + en.variant());
        assertThat(zh.src()).isEqualTo(
                "/api/public/media/" + zh.assetId() + '/' + zh.variant());
    }

    private static void assertLocalizedProjectHtml(
            String english,
            String chinese,
            String slug,
            String englishTitle,
            String chineseTitle) {
        assertThat(english)
                .contains("<html lang=\"en\"")
                .contains("rel=\"canonical\" href=\"https://yychainsaw.xyz/en/projects/"
                        + slug + "\"")
                .contains("hreflang=\"zh-CN\"")
                .contains("property=\"og:url\"")
                .contains("type=\"application/ld+json\"")
                .contains("\"@type\":\"CreativeWork\"")
                .contains("<template id=\"__PORTFOLIO_DATA__\">")
                .contains("<h1>" + HtmlUtils.htmlEscape(englishTitle) + "</h1>");
        assertThat(chinese)
                .contains("<html lang=\"zh-CN\"")
                .contains("rel=\"canonical\" href=\"https://yychainsaw.xyz/zh-CN/projects/"
                        + slug + "\"")
                .contains("hreflang=\"en\"")
                .contains("property=\"og:url\"")
                .contains("type=\"application/ld+json\"")
                .contains("\"@type\":\"CreativeWork\"")
                .contains("<template id=\"__PORTFOLIO_DATA__\">")
                .contains("<h1>" + HtmlUtils.htmlEscape(chineseTitle) + "</h1>");
    }

    private void assertProjectBootstrap(
            JsonNode bootstrap,
            String locale,
            String slug,
            String title) {
        assertThat(bootstrap.path("kind").asText()).isEqualTo("project");
        assertThat(bootstrap.path("locale").asText()).isEqualTo(locale);
        assertThat(bootstrap.path("site").isObject()).isTrue();
        assertThat(bootstrap.path("catalog").isArray()).isTrue();
        assertThat(bootstrap.path("catalog").size()).isEqualTo(1);
        assertThat(bootstrap.path("catalog").get(0).path("slug").asText())
                .isEqualTo(slug);
        JsonNode project = bootstrap.path("project");
        assertThat(project.path("slug").asText()).isEqualTo(slug);
        assertThat(project.path("title").asText()).isEqualTo(title);
        assertThat(project.path("blocks").isArray()).isTrue();
        assertPublicJsonDoesNotLeakStorage(bootstrap);
    }

    private void assertHomeBootstrap(
            JsonNode bootstrap,
            String locale,
            String slug,
            String title) {
        assertThat(bootstrap.path("kind").asText()).isEqualTo("home");
        assertThat(bootstrap.path("locale").asText()).isEqualTo(locale);
        assertThat(bootstrap.path("site").isObject()).isTrue();
        JsonNode catalog = bootstrap.path("catalog");
        assertThat(catalog.isArray()).isTrue();
        assertThat(catalog.size()).isEqualTo(1);
        assertThat(catalog.get(0).path("slug").asText()).isEqualTo(slug);
        assertThat(catalog.get(0).path("title").asText()).isEqualTo(title);
        assertPublicJsonDoesNotLeakStorage(bootstrap);
    }

    private static void assertPrivacyBootstrap(JsonNode bootstrap, String locale) {
        assertThat(bootstrap.path("kind").asText()).isEqualTo("privacy");
        assertThat(bootstrap.path("locale").asText()).isEqualTo(locale);
        assertThat(bootstrap.path("site").isObject()).isTrue();
        assertPublicJsonDoesNotLeakStorage(bootstrap);
    }

    private JsonNode bootstrap(String html) throws Exception {
        Matcher matcher = BOOTSTRAP.matcher(html);
        assertThat(matcher.find()).as("inert bootstrap template").isTrue();
        return json.readTree(matcher.group(1));
    }

    private JsonNode publicProjectJson(String slug, String locale) throws Exception {
        MvcResult result = mvc.perform(get("/api/public/projects/{slug}", slug)
                        .param("locale", locale))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode publicCatalogJson(String locale) throws Exception {
        MvcResult result = mvc.perform(get("/api/public/projects").param("locale", locale))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
    }

    private String html(String path) throws Exception {
        return mvc.perform(get(path))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private void assertMediaNotFound(UUID assetId, String variant) throws Exception {
        mvc.perform(get(
                        "/api/public/media/{assetId}/{variant}", assetId, variant))
                .andExpect(status().isNotFound());
    }

    private void assertProjectNotFound(String slug) throws Exception {
        mvc.perform(get("/api/public/projects/{slug}", slug).param("locale", "en"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/public/projects/{slug}", slug).param("locale", "zh-CN"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/en/projects/{slug}", slug))
                .andExpect(status().isNotFound());
        mvc.perform(get("/zh-CN/projects/{slug}", slug))
                .andExpect(status().isNotFound());
    }

    private String firstVariant(ProjectWorkspaceDto project) {
        return media.requireReadyAsset(project.media().get(0).assetId())
                .variants().get(0).variantName();
    }

    private static ProjectWorkspaceDto withDraftChanges(
            ProjectWorkspaceDto source,
            String slug,
            List<ProjectWorkspaceDto.ProjectMedia> media) {
        EnumMap<LocaleCode, ProjectWorkspaceDto.ProjectCopy> copy =
                new EnumMap<>(source.translations());
        ProjectWorkspaceDto.ProjectCopy english = copy.get(LocaleCode.EN);
        copy.put(LocaleCode.EN, new ProjectWorkspaceDto.ProjectCopy(
                english.status(),
                english.eyebrow(),
                DRAFT_TITLE,
                english.summary(),
                english.seoTitle(),
                english.seoDescription()));
        return new ProjectWorkspaceDto(
                source.id(),
                source.externalKey(),
                slug,
                source.number(),
                source.sortOrder(),
                source.featured(),
                source.visible(),
                source.publicationDirty(),
                source.version(),
                copy,
                source.tags(),
                source.skills(),
                media,
                source.blocks());
    }

    private static void assertPublicJsonDoesNotLeakStorage(JsonNode value) {
        assertThat(value.toString()).doesNotContain(
                "\"provider\"",
                "\"objectKey\"",
                "\"bucket\"",
                "\"region\"");
    }

    private PublicationRow projectPointer(UUID projectId) {
        return publishing.find(AggregateType.PROJECT, projectId).orElseThrow();
    }

    private PublicationRow catalogPointer() {
        return publishing.find(
                        AggregateType.PROJECT_CATALOG,
                        PublicationService.PROJECT_CATALOG_ID)
                .orElseThrow();
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private static Path isolatedStorageRoot() {
        String configured = System.getProperty("portfolio.storage.local.root");
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("integration-test storage root is required");
        }
        Path configuredRoot = Path.of(configured)
                .toAbsolutePath()
                .normalize();
        Path base = acceptanceStorageBase();
        Path candidate = configuredRoot.resolveSibling(
                "portfolio-publishing-acceptance-media");
        if (!configuredRoot.startsWith(base) || !candidate.startsWith(base)) {
            throw new IllegalStateException("integration-test storage root is unsafe");
        }
        return candidate;
    }

    private static String portfolioJdbcUrl(String jdbcUrl) {
        String separator = jdbcUrl.contains("?") ? "&" : "?";
        return jdbcUrl + separator + "currentSchema=portfolio";
    }

    private static void requireDedicatedStorageRoot() {
        Path fileName = STORAGE_ROOT.getFileName();
        if (fileName == null
                || !"portfolio-publishing-acceptance-media".equals(fileName.toString())
                || STORAGE_ROOT.equals(STORAGE_ROOT.getRoot())
                || !STORAGE_ROOT.startsWith(acceptanceStorageBase())) {
            throw new IllegalStateException("acceptance-test storage root is unsafe");
        }
    }

    private static Path acceptanceStorageBase() {
        return Path.of(
                        System.getProperty("user.home"),
                        ".portfolio-test",
                        "portfolio-server")
                .toAbsolutePath()
                .normalize();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ImportCapacityConfiguration {
        @Bean
        @Primary
        LocalStagingPolicyProperties acceptanceImportStagingPolicy() {
            return new LocalStagingPolicyProperties(8, 80, 16);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedAdminConfiguration {
        @Bean
        @Primary
        CurrentAdminProvider acceptanceCurrentAdmin() {
            return () -> PublishingTestFixture.ADMIN_ID;
        }
    }

}
