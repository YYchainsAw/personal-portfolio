package xyz.yychainsaw.portfolio.publishing.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.publicapi.PublicProjectCardDto;
import xyz.yychainsaw.portfolio.publicapi.PublishedEnvelope;
import xyz.yychainsaw.portfolio.publishing.application.PublicSnapshotQueryService;
import xyz.yychainsaw.portfolio.publishing.config.PublicRenderProperties;

class SitemapControllerTest {
    private static final String CHECKSUM = "a".repeat(64);

    private PublicSnapshotQueryService queries;
    private CompositeEtagService etags;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        queries = mock(PublicSnapshotQueryService.class);
        PublicRenderProperties properties = mock(PublicRenderProperties.class);
        when(properties.publicBaseUrl()).thenReturn(URI.create("https://yychainsaw.xyz"));
        etags = new CompositeEtagService();
        mvc = MockMvcBuilders.standaloneSetup(
                        new SitemapController(queries, properties, etags))
                .build();
    }

    @Test
    void sitemapUsesOnlyTheCurrentCatalogAndConfiguredPublicOrigin() throws Exception {
        when(queries.catalog(LocaleCode.ZH_CN)).thenReturn(catalog());

        mvc.perform(get("/sitemap.xml")
                        .header(HttpHeaders.HOST, "evil.example")
                        .header("Forwarded", "host=evil.example;proto=http")
                        .header("X-Forwarded-Host", "evil.example"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_XML))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, no-cache"))
                .andExpect(header().string(HttpHeaders.ETAG, etags.sitemap(CHECKSUM)))
                .andExpect(content().string(containsString(
                        "<loc>https://yychainsaw.xyz/zh-CN</loc>")))
                .andExpect(content().string(containsString(
                        "<loc>https://yychainsaw.xyz/en</loc>")))
                .andExpect(content().string(containsString(
                        "<loc>https://yychainsaw.xyz/zh-CN/projects/gameplay-prototype</loc>")))
                .andExpect(content().string(containsString(
                        "<loc>https://yychainsaw.xyz/en/projects/gameplay-prototype</loc>")))
                .andExpect(content().string(containsString(
                        "<loc>https://yychainsaw.xyz/zh-CN/privacy</loc>")))
                .andExpect(content().string(containsString(
                        "<loc>https://yychainsaw.xyz/en/privacy</loc>")))
                .andExpect(content().string(not(containsString("draft-only"))))
                .andExpect(content().string(not(containsString("evil.example"))));

        verify(queries).catalog(LocaleCode.ZH_CN);
    }

    @Test
    void exactSitemapEtagReturns304AndNonExactCandidatesRenderXml() throws Exception {
        when(queries.catalog(LocaleCode.ZH_CN)).thenReturn(catalog());
        String etag = etags.sitemap(CHECKSUM);

        mvc.perform(get("/sitemap.xml").header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isNotModified())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, no-cache"))
                .andExpect(header().string(HttpHeaders.ETAG, etag))
                .andExpect(content().string(""));

        for (String candidate : List.of(
                "W/" + etag,
                "*",
                etag + ", \"other\"",
                '"' + "b".repeat(64) + '"')) {
            mvc.perform(get("/sitemap.xml")
                            .header(HttpHeaders.IF_NONE_MATCH, candidate))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.ETAG, etag))
                    .andExpect(content().string(containsString("<urlset")));
        }
        mvc.perform(get("/sitemap.xml")
                        .header(HttpHeaders.IF_NONE_MATCH, etag, etag))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<urlset")));
    }

    private static PublishedEnvelope<List<PublicProjectCardDto>> catalog() {
        return new PublishedEnvelope<>(
                7L,
                CHECKSUM,
                List.of(new PublicProjectCardDto(
                        UUID.fromString("10000000-0000-4000-8000-000000000010"),
                        "gameplay-prototype",
                        "01",
                        0,
                        true,
                        "Published",
                        "Featured",
                        "Gameplay prototype",
                        "Published project",
                        List.of("UE"),
                        null)));
    }
}
