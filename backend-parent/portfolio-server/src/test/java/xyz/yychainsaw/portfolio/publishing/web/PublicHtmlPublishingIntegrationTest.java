package xyz.yychainsaw.portfolio.publishing.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import xyz.yychainsaw.portfolio.auth.CurrentAdminProvider;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.content.api.ProjectWorkspaceDto;
import xyz.yychainsaw.portfolio.content.api.SiteWorkspaceDto;
import xyz.yychainsaw.portfolio.content.persistence.SiteWorkspaceRepository;
import xyz.yychainsaw.portfolio.content.persistence.support.ContentPersistenceFixtures;
import xyz.yychainsaw.portfolio.publishing.api.PublishProjectCommand;
import xyz.yychainsaw.portfolio.publishing.api.PublishSiteCommand;
import xyz.yychainsaw.portfolio.publishing.application.PublicationService;
import xyz.yychainsaw.portfolio.publishing.persistence.PublishingRepository;
import xyz.yychainsaw.portfolio.publishing.snapshot.AggregateType;
import xyz.yychainsaw.portfolio.publishing.support.PublishingTestFixture;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Transactional
@Isolated
@Import({
    PublishingTestFixture.class,
    PublicHtmlPublishingIntegrationTest.FixedAdminConfiguration.class
})
class PublicHtmlPublishingIntegrationTest extends PostgresIntegrationTestBase {
    private static final Pattern BOOTSTRAP = Pattern.compile(
            "<template id=\"__PORTFOLIO_DATA__\">(.*?)</template>",
            Pattern.DOTALL);
    private static final Pattern STRONG_ETAG = Pattern.compile("\"[0-9a-f]{64}\"");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PublicationService publications;
    @Autowired PublishingRepository publishing;
    @Autowired SiteWorkspaceRepository sites;
    @Autowired PublishingTestFixture fixture;
    @Autowired PublicPageRenderer pages;

    @BeforeEach
    void authenticatePublicationFixture() {
        fixture.ensureAdmin();
    }

    @Test
    void localizedHtmlAndSitemapReadPublishedRevisionsInsteadOfWorkspaceDrafts()
            throws Exception {
        publishSiteFixture();
        ProjectWorkspaceDto neverPublished = fixture.persistReadyProject();
        ProjectWorkspaceDto published = fixture.persistReadyProject();
        String englishTitle = published.translations().get(LocaleCode.EN).title();
        String chineseTitle = published.translations().get(LocaleCode.ZH_CN).title();
        assertThat(englishTitle).isNotEqualTo(chineseTitle);

        long catalogVersion = publishing
                .find(AggregateType.PROJECT_CATALOG, PublicationService.PROJECT_CATALOG_ID)
                .orElseThrow()
                .version();
        publications.publishProject(new PublishProjectCommand(
                published.id(), published.version(), 0L, catalogVersion));

        String draftTitle = "UNPUBLISHED-DRAFT-" + published.id();
        ProjectWorkspaceDto edited = fixture.editProjectEnglishTitle(
                published.id(), draftTitle);
        assertThat(edited.publicationDirty()).isTrue();
        assertThat(AopUtils.isAopProxy(pages)).isTrue();

        String englishProjectPath = "/en/projects/" + published.slug();
        String chineseProjectPath = "/zh-CN/projects/" + published.slug();
        String englishProject = html(englishProjectPath);
        String chineseProject = html(chineseProjectPath);
        assertSeo(
                englishProject,
                "en",
                "https://yychainsaw.xyz" + englishProjectPath,
                "https://yychainsaw.xyz" + chineseProjectPath,
                "https://yychainsaw.xyz" + englishProjectPath,
                "CreativeWork");
        assertSeo(
                chineseProject,
                "zh-CN",
                "https://yychainsaw.xyz" + chineseProjectPath,
                "https://yychainsaw.xyz" + chineseProjectPath,
                "https://yychainsaw.xyz" + englishProjectPath,
                "CreativeWork");
        assertThat(englishProject).contains(englishTitle).doesNotContain(draftTitle);
        assertThat(chineseProject).contains(chineseTitle).doesNotContain(draftTitle);
        assertThat(bootstrap(englishProject).path("project").path("title").asText())
                .isEqualTo(englishTitle);
        assertThat(bootstrap(chineseProject).path("project").path("title").asText())
                .isEqualTo(chineseTitle);

        String englishHome = html("/en");
        String chineseHome = html("/zh-CN");
        assertSeo(
                englishHome,
                "en",
                "https://yychainsaw.xyz/en",
                "https://yychainsaw.xyz/zh-CN",
                "https://yychainsaw.xyz/en",
                "Person");
        assertSeo(
                chineseHome,
                "zh-CN",
                "https://yychainsaw.xyz/zh-CN",
                "https://yychainsaw.xyz/zh-CN",
                "https://yychainsaw.xyz/en",
                "Person");
        assertCatalogContainsOnlyPublishedFixture(
                bootstrap(englishHome),
                published.slug(),
                englishTitle,
                neverPublished.slug());
        assertCatalogContainsOnlyPublishedFixture(
                bootstrap(chineseHome),
                published.slug(),
                chineseTitle,
                neverPublished.slug());
        assertThat(englishHome).doesNotContain(draftTitle);
        assertThat(chineseHome).doesNotContain(draftTitle);

        String englishPrivacy = html("/en/privacy");
        String chinesePrivacy = html("/zh-CN/privacy");
        assertSeo(
                englishPrivacy,
                "en",
                "https://yychainsaw.xyz/en/privacy",
                "https://yychainsaw.xyz/zh-CN/privacy",
                "https://yychainsaw.xyz/en/privacy",
                "WebPage");
        assertSeo(
                chinesePrivacy,
                "zh-CN",
                "https://yychainsaw.xyz/zh-CN/privacy",
                "https://yychainsaw.xyz/zh-CN/privacy",
                "https://yychainsaw.xyz/en/privacy",
                "WebPage");

        MvcResult sitemapResult = mvc.perform(get("/sitemap.xml")
                        .header(HttpHeaders.HOST, "evil.example"))
                .andExpect(status().isOk())
                .andReturn();
        String sitemap = sitemapResult.getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(sitemap)
                .contains("https://yychainsaw.xyz" + englishProjectPath)
                .contains("https://yychainsaw.xyz" + chineseProjectPath)
                .doesNotContain(neverPublished.slug())
                .doesNotContain(draftTitle)
                .doesNotContain("evil.example");
        assertStrongEtag(sitemapResult);
    }

    private void publishSiteFixture() {
        SiteWorkspaceDto current = sites.require();
        UUID resumeAssetId = fixture.persistReadyDocument();
        SiteWorkspaceDto withoutMedia =
                ContentPersistenceFixtures.siteWithoutMedia(current.version());
        SiteWorkspaceDto publishable = ContentPersistenceFixtures.withResumes(
                withoutMedia,
                List.of(
                        new SiteWorkspaceDto.ResumeDocument(
                                UUID.randomUUID(),
                                LocaleCode.ZH_CN,
                                resumeAssetId,
                                "2026.1",
                                true,
                                LocalDate.of(2026, 7, 18)),
                        new SiteWorkspaceDto.ResumeDocument(
                                UUID.randomUUID(),
                                LocaleCode.EN,
                                resumeAssetId,
                                "2026.1",
                                true,
                                LocalDate.of(2026, 7, 18))));
        sites.replace(
                publishable,
                current.version());
        SiteWorkspaceDto ready = sites.require();
        long publicationVersion = publishing
                .find(AggregateType.SITE, SiteWorkspaceDto.SITE_ID)
                .orElseThrow()
                .version();
        publications.publishSite(new PublishSiteCommand(
                ready.version(), publicationVersion));
    }

    private String html(String path) throws Exception {
        MvcResult result = mvc.perform(get(path)
                        .header(HttpHeaders.HOST, "evil.example")
                        .header("X-Forwarded-Host", "evil.example"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL))
                .isEqualTo("public, no-cache");
        assertStrongEtag(result);
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static void assertSeo(
            String html,
            String locale,
            String canonical,
            String chineseUrl,
            String englishUrl,
            String structuredType) {
        assertThat(html)
                .contains("<html lang=\"" + locale + "\"")
                .contains("rel=\"canonical\" href=\"" + canonical + "\"")
                .contains("hreflang=\"zh-CN\" href=\"" + chineseUrl + "\"")
                .contains("hreflang=\"en\" href=\"" + englishUrl + "\"")
                .contains("property=\"og:url\" content=\"" + canonical + "\"")
                .contains("type=\"application/ld+json\"")
                .contains("\"@type\":\"" + structuredType + "\"")
                .contains("id=\"__PORTFOLIO_DATA__\"")
                .doesNotContain("evil.example");
    }

    private void assertCatalogContainsOnlyPublishedFixture(
            JsonNode home,
            String publishedSlug,
            String publishedTitle,
            String neverPublishedSlug) {
        JsonNode catalog = home.path("catalog");
        JsonNode publishedCard = null;
        for (JsonNode card : catalog) {
            assertThat(card.path("slug").asText()).isNotEqualTo(neverPublishedSlug);
            if (publishedSlug.equals(card.path("slug").asText())) {
                publishedCard = card;
            }
        }
        assertThat(publishedCard).isNotNull();
        assertThat(publishedCard.path("title").asText()).isEqualTo(publishedTitle);
    }

    private JsonNode bootstrap(String html) throws Exception {
        Matcher matcher = BOOTSTRAP.matcher(html);
        assertThat(matcher.find()).as("inert bootstrap template").isTrue();
        return json.readTree(matcher.group(1));
    }

    private static void assertStrongEtag(MvcResult result) {
        assertThat(result.getResponse().getHeader(HttpHeaders.ETAG))
                .matches(STRONG_ETAG);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedAdminConfiguration {
        @Bean
        @Primary
        CurrentAdminProvider publicHtmlPublishingAdmin() {
            return () -> PublishingTestFixture.ADMIN_ID;
        }
    }
}
