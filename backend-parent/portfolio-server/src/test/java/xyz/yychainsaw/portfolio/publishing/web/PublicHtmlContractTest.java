package xyz.yychainsaw.portfolio.publishing.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.publicapi.PublicBlockDto;
import xyz.yychainsaw.portfolio.publicapi.PublishedEnvelope;
import xyz.yychainsaw.portfolio.publishing.application.PublicSnapshotQueryService;
import xyz.yychainsaw.portfolio.publishing.config.PublicRenderProperties;
import xyz.yychainsaw.portfolio.publishing.persistence.PublishingRepository;

@WebMvcTest(PublicPageController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Import({
        SnapshotPublicPageRenderer.class,
        CompositeEtagService.class,
        SafeInitialJson.class
})
class PublicHtmlContractTest {
    private static final String SITE_CHECKSUM = "a".repeat(64);
    private static final String CATALOG_CHECKSUM = "b".repeat(64);
    private static final String PROJECT_CHECKSUM = "c".repeat(64);
    private static final Pattern BOOTSTRAP = Pattern.compile(
            "<template id=\"__PORTFOLIO_DATA__\">(.*?)</template>",
            Pattern.DOTALL);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @MockitoBean PublicSnapshotQueryService queries;
    @MockitoBean PublishingRepository publishing;
    @MockitoBean PublicRenderProperties properties;
    @MockitoBean AssetManifestService manifest;

    @BeforeEach
    void setUpPublishedData() {
        when(properties.releaseId()).thenReturn("release-a");
        when(properties.templateSchemaVersion()).thenReturn(1);
        when(properties.publicBaseUrl()).thenReturn(URI.create("https://yychainsaw.xyz"));
        when(manifest.entryJs()).thenReturn("/assets/index-test123.js");
        when(manifest.css()).thenReturn(List.of("/assets/index-test123.css"));
        when(queries.site(any(LocaleCode.class))).thenAnswer(invocation ->
                new PublishedEnvelope<>(
                        7L,
                        SITE_CHECKSUM,
                        PublicPageFixtures.site("Published headline", true)));
        when(queries.catalog(any(LocaleCode.class))).thenReturn(new PublishedEnvelope<>(
                8L, CATALOG_CHECKSUM, List.of(PublicPageFixtures.card())));
        when(queries.project("gameplay-prototype", LocaleCode.EN))
                .thenReturn(new PublishedEnvelope<>(
                        9L, PROJECT_CHECKSUM, PublicPageFixtures.project()));
        when(queries.project("gameplay-prototype", LocaleCode.ZH_CN))
                .thenReturn(new PublishedEnvelope<>(
                        9L, PROJECT_CHECKSUM, PublicPageFixtures.project()));
    }

    @Test
    void englishHomeIsIndexableSafeAndIndependentOfRequestHost() throws Exception {
        String html = mvc.perform(get("/en")
                        .header(HttpHeaders.HOST, "evil.example")
                        .header("X-Forwarded-Host", "evil.example"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, no-cache"))
                .andExpect(content().string(containsString("<html lang=\"en\"")))
                .andExpect(content().string(containsString(
                        "rel=\"canonical\" href=\"https://yychainsaw.xyz/en\"")))
                .andExpect(content().string(containsString(
                        "hreflang=\"zh-CN\" href=\"https://yychainsaw.xyz/zh-CN\"")))
                .andExpect(content().string(containsString(
                        "property=\"og:image\" content=\"https://yychainsaw.xyz/api/public/media/")))
                .andExpect(content().string(containsString("type=\"application/ld+json\"")))
                .andExpect(content().string(containsString("\"@type\":\"Person\"")))
                .andExpect(content().string(containsString("Published headline")))
                .andExpect(content().string(containsString("Published card title")))
                .andExpect(content().string(not(containsString("Draft only"))))
                .andExpect(content().string(not(containsString("evil.example"))))
                .andExpect(content().string(containsString(
                        "href=\"/assets/index-test123.css\"")))
                .andExpect(content().string(containsString(
                        "type=\"module\" src=\"/assets/index-test123.js\"")))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        JsonNode bootstrap = bootstrap(html);
        assertThat(bootstrap.path("kind").asText()).isEqualTo("home");
        assertThat(bootstrap.path("locale").asText()).isEqualTo("en");
        assertThat(bootstrap.path("site").path("hero").path("headline").asText())
                .isEqualTo("Published headline");
    }

    @Test
    void projectHtmlRendersAllPublicBlockKindsAndCatalogCover() throws Exception {
        String html = mvc.perform(get("/en/projects/gameplay-prototype"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<html lang=\"en\"")))
                .andExpect(content().string(containsString("Published title")))
                .andExpect(content().string(containsString("Published summary")))
                .andExpect(content().string(containsString(
                        "\"@type\":\"CreativeWork\"")))
                .andExpect(content().string(containsString(
                        "property=\"og:image\" content=\"https://yychainsaw.xyz/api/public/media/20000000-0000-4000-8000-000000000010/w1280\"")))
                .andExpect(content().string(containsString("Published <strong>markdown</strong>")))
                .andExpect(content().string(containsString("if (value &lt; 2) return;")))
                .andExpect(content().string(containsString("Published quote")))
                .andExpect(content().string(containsString("Frame rate")))
                .andExpect(content().string(containsString("application/pdf")))
                .andExpect(content().string(containsString("42 bytes")))
                .andExpect(content().string(containsString("Project source")))
                .andExpect(content().string(not(containsString("Draft only"))))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        for (String type : List.of(
                "MARKDOWN",
                "IMAGE",
                "GALLERY",
                "VIDEO",
                "CODE",
                "QUOTE",
                "METRICS",
                "DOWNLOAD",
                "LINK")) {
            assertThat(html).contains("data-block-type=\"" + type + "\"");
        }
        JsonNode bootstrap = bootstrap(html);
        assertThat(bootstrap.path("kind").asText()).isEqualTo("project");
        assertThat(bootstrap.path("project").path("slug").asText())
                .isEqualTo("gameplay-prototype");
    }

    @Test
    void sceneInteractionProjectFirstPaintUsesTheSameBundledCoverAsVue() throws Exception {
        String asset = "/assets/ue-scene-interaction-study-a1b2c3.webp";
        when(queries.project("ue-environment-study", LocaleCode.ZH_CN))
                .thenReturn(new PublishedEnvelope<>(
                        9L,
                        PROJECT_CHECKSUM,
                        PublicPageFixtures.project("ue-environment-study")));
        when(manifest.asset(SnapshotPublicPageRenderer.SCENE_INTERACTION_ASSET))
                .thenReturn(Optional.of(asset));

        String html = mvc.perform(get("/zh-CN/projects/ue-environment-study"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "property=\"og:image\" content=\"https://yychainsaw.xyz"
                                + asset + "\"")))
                .andExpect(content().string(containsString(
                        "src=\"" + asset + "\"")))
                .andExpect(content().string(containsString(
                        "srcset=\"" + asset + " 1672w\"")))
                .andExpect(content().string(containsString(
                        "alt=\"Unreal Engine 5 场景交互学习项目画面\"")))
                .andExpect(content().string(containsString("width=\"1672\"")))
                .andExpect(content().string(containsString("height=\"941\"")))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(bootstrap(html).path("project").path("slug").asText())
                .isEqualTo("ue-environment-study");
        assertThat(html).contains("\"@type\":\"CreativeWork\"");
    }

    @Test
    void privacyAndChinesePagesCarryLocalizedSeoAndPublishedHtml() throws Exception {
        mvc.perform(get("/zh-CN/privacy"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<html lang=\"zh-CN\"")))
                .andExpect(content().string(containsString(
                        "href=\"https://yychainsaw.xyz/zh-CN/privacy\"")))
                .andExpect(content().string(containsString("Published privacy policy")))
                .andExpect(content().string(containsString("\"@type\":\"WebPage\"")))
                .andExpect(content().string(containsString(
                        "id=\"__PORTFOLIO_DATA__\"")));
    }

    @Test
    void mediaLessHeroAndMaliciousBootstrapTextCannotBreakHtmlContainers() throws Exception {
        String malicious = "</template><script>alert('x')</script>\u2028\u2029";
        when(queries.site(LocaleCode.EN)).thenReturn(new PublishedEnvelope<>(
                10L, "d".repeat(64), PublicPageFixtures.site(malicious, false)));

        String html = mvc.perform(get("/en"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("property=\"og:image\""))))
                .andExpect(content().string(not(containsString(
                        "</template><script>alert('x')</script>"))))
                .andExpect(content().string(containsString(
                        "\\u003c/template\\u003e\\u003cscript\\u003e")))
                .andExpect(content().string(containsString("\\u2028\\u2029")))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(bootstrap(html).path("site").path("hero").path("headline").asText())
                .isEqualTo(malicious);
    }

    @Test
    void springJsonKeepsRequiredNullMembersInThePublicUnion() {
        JsonNode video = json.valueToTree(new PublicBlockDto.Video(
                "youtube",
                "https://www.youtube.com/embed/AbC_123-xyZ",
                null,
                "Video",
                "Description"));
        JsonNode download = json.valueToTree(new PublicBlockDto.Download(
                "https://example.test/design.pdf",
                "Download",
                "External download",
                null,
                null));

        assertThat(video.has("cover")).isTrue();
        assertThat(video.path("cover").isNull()).isTrue();
        assertThat(download.has("mimeType")).isTrue();
        assertThat(download.path("mimeType").isNull()).isTrue();
        assertThat(download.has("byteSize")).isTrue();
        assertThat(download.path("byteSize").isNull()).isTrue();
    }

    @Test
    void sourceOnlyMediaStillRendersIndependentSourceLinks() throws Exception {
        when(queries.project("gameplay-prototype", LocaleCode.EN))
                .thenReturn(new PublishedEnvelope<>(
                        11L,
                        "e".repeat(64),
                        PublicPageFixtures.projectWithSourceOnlyMedia()));

        String html = mvc.perform(get("/en/projects/gameplay-prototype"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Source-only media")))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(occurrences(
                        html,
                        "href=\"https://example.test/source-only\""))
                .isEqualTo(3);
    }

    private JsonNode bootstrap(String html) throws Exception {
        Matcher matcher = BOOTSTRAP.matcher(html);
        assertThat(matcher.find()).as("inert bootstrap template").isTrue();
        return json.readTree(matcher.group(1));
    }

    private static int occurrences(String value, String needle) {
        int matches = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            matches++;
            offset += needle.length();
        }
        return matches;
    }
}
