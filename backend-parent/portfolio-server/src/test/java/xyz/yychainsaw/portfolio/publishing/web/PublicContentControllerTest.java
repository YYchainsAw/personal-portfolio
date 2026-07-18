package xyz.yychainsaw.portfolio.publishing.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.common.error.GlobalProblemHandler;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.publicapi.PublishedEnvelope;
import xyz.yychainsaw.portfolio.publicapi.PublicProjectCardDto;
import xyz.yychainsaw.portfolio.publicapi.PublicProjectDto;
import xyz.yychainsaw.portfolio.publicapi.PublicSiteDto;
import xyz.yychainsaw.portfolio.publishing.application.PublicSnapshotQueryService;

class PublicContentControllerTest {
    private static final String CHECKSUM =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private PublicSnapshotQueryService queries;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        queries = mock(PublicSnapshotQueryService.class);
        mvc = MockMvcBuilders.standaloneSetup(new PublicContentController(queries))
                .setControllerAdvice(new GlobalProblemHandler())
                .build();
    }

    @Test
    void siteReturnsTheLocaleProjectionEnvelopeAndExactApiCacheHeaders() throws Exception {
        when(queries.site(LocaleCode.EN)).thenReturn(new PublishedEnvelope<>(
                9L, CHECKSUM, site()));

        mvc.perform(get("/api/public/site").queryParam("locale", "en"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, no-cache"))
                .andExpect(header().string(
                        HttpHeaders.ETAG,
                        "\"61b813898c02a97e7b8b3372ee431fbc8bbece6760430547c78a5ba864761ce8\""))
                .andExpect(jsonPath("$.revisionVersion").value(9))
                .andExpect(jsonPath("$.checksum").value(CHECKSUM))
                .andExpect(jsonPath("$.data.identity.displayName").value("Yi Jiaxuan"))
                .andExpect(jsonPath("$.data.resume.href")
                        .value("/api/public/media/10000000-0000-4000-8000-000000000010/document"));
        verify(queries).site(LocaleCode.EN);
    }

    @Test
    void anExactlyMatchingApiEtagReturns304WithNoBody() throws Exception {
        String etag = HttpEtag.api(CHECKSUM, LocaleCode.EN);
        when(queries.site(LocaleCode.EN)).thenReturn(new PublishedEnvelope<>(
                9L, CHECKSUM, site()));

        mvc.perform(get("/api/public/site")
                        .queryParam("locale", "en")
                        .header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isNotModified())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, no-cache"))
                .andExpect(header().string(HttpHeaders.ETAG, etag))
                .andExpect(content().string(""));
    }

    @Test
    void weakWildcardOrListApiEtagsDoNotCountAsExactMatches() throws Exception {
        String etag = HttpEtag.api(CHECKSUM, LocaleCode.EN);
        when(queries.site(LocaleCode.EN)).thenReturn(new PublishedEnvelope<>(
                9L, CHECKSUM, site()));

        for (String candidate : List.of(
                "W/" + etag,
                "*",
                etag + ", \"other\"",
                '"' + "f".repeat(64) + '"',
                HttpEtag.api(CHECKSUM, LocaleCode.ZH_CN))) {
            mvc.perform(get("/api/public/site")
                            .queryParam("locale", "en")
                            .header(HttpHeaders.IF_NONE_MATCH, candidate))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, no-cache"))
                    .andExpect(header().string(HttpHeaders.ETAG, etag))
                    .andExpect(jsonPath("$.data.identity.displayName").value("Yi Jiaxuan"));
        }
        mvc.perform(get("/api/public/site")
                        .queryParam("locale", "en")
                        .header(HttpHeaders.IF_NONE_MATCH, etag, etag))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, etag))
                .andExpect(jsonPath("$.data.identity.displayName").value("Yi Jiaxuan"));
    }

    @Test
    void catalogAndProjectRoutesUseTheExactWireLocalesAndSlug() throws Exception {
        PublicProjectCardDto card = card();
        PublicProjectDto project = project(card);
        when(queries.catalog(LocaleCode.ZH_CN)).thenReturn(new PublishedEnvelope<>(
                3L, CHECKSUM, List.of(card)));
        when(queries.project("gameplay-prototype", LocaleCode.EN))
                .thenReturn(new PublishedEnvelope<>(4L, CHECKSUM, project));

        mvc.perform(get("/api/public/projects").queryParam("locale", "zh-CN"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, no-cache"))
                .andExpect(header().string(
                        HttpHeaders.ETAG,
                        "\"b0e76b4e2492ca9b10e72e66f9b04197c33015b08fafe24573e9bf08f261a50b\""))
                .andExpect(jsonPath("$.data[0].title").value("玩法原型"));
        mvc.perform(get("/api/public/projects/gameplay-prototype")
                        .queryParam("locale", "en"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, no-cache"))
                .andExpect(header().string(
                        HttpHeaders.ETAG,
                        "\"61b813898c02a97e7b8b3372ee431fbc8bbece6760430547c78a5ba864761ce8\""))
                .andExpect(jsonPath("$.data.title").value("Gameplay Prototype"));
        verify(queries).catalog(LocaleCode.ZH_CN);
        verify(queries).project("gameplay-prototype", LocaleCode.EN);
    }

    @Test
    void exactCatalogAndProjectEtagsReturn304WithNoBodyAndCacheHeaders() throws Exception {
        PublicProjectCardDto card = card();
        String zhEtag = HttpEtag.api(CHECKSUM, LocaleCode.ZH_CN);
        String enEtag = HttpEtag.api(CHECKSUM, LocaleCode.EN);
        when(queries.catalog(LocaleCode.ZH_CN)).thenReturn(new PublishedEnvelope<>(
                3L, CHECKSUM, List.of(card)));
        when(queries.project("gameplay-prototype", LocaleCode.EN))
                .thenReturn(new PublishedEnvelope<>(4L, CHECKSUM, project(card)));

        mvc.perform(get("/api/public/projects")
                        .queryParam("locale", "zh-CN")
                        .header(HttpHeaders.IF_NONE_MATCH, zhEtag))
                .andExpect(status().isNotModified())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, no-cache"))
                .andExpect(header().string(HttpHeaders.ETAG, zhEtag))
                .andExpect(content().string(""));
        mvc.perform(get("/api/public/projects/gameplay-prototype")
                        .queryParam("locale", "en")
                        .header(HttpHeaders.IF_NONE_MATCH, enEtag))
                .andExpect(status().isNotModified())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, no-cache"))
                .andExpect(header().string(HttpHeaders.ETAG, enEtag))
                .andExpect(content().string(""));
    }

    @Test
    void theSameRevisionHasDifferentEtagsForZhAndEnglish() throws Exception {
        when(queries.site(LocaleCode.ZH_CN)).thenReturn(new PublishedEnvelope<>(
                9L, CHECKSUM, site()));
        when(queries.site(LocaleCode.EN)).thenReturn(new PublishedEnvelope<>(
                9L, CHECKSUM, site()));

        String zhEtag = mvc.perform(get("/api/public/site").queryParam("locale", "zh-CN"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, no-cache"))
                .andReturn()
                .getResponse()
                .getHeader(HttpHeaders.ETAG);
        String enEtag = mvc.perform(get("/api/public/site").queryParam("locale", "en"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, no-cache"))
                .andReturn()
                .getResponse()
                .getHeader(HttpHeaders.ETAG);

        assertThat(zhEtag).isNotNull().isNotEqualTo(enEtag);
        assertThat(enEtag).isNotNull();
    }

    @Test
    void invalidEnumNamesUnknownAndMissingLocalesAre400WithoutQueries() throws Exception {
        for (String route : List.of(
                "/api/public/site",
                "/api/public/projects",
                "/api/public/projects/gameplay-prototype")) {
            for (String value : List.of("ZH_CN", "EN", "fr", "", " en")) {
                mvc.perform(get(route).queryParam("locale", value))
                        .andExpect(status().isBadRequest());
            }
            mvc.perform(get(route)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(queries);
    }

    @Test
    void unpublishedContentReturnsAStable404Problem() throws Exception {
        when(queries.project("draft-only", LocaleCode.EN)).thenThrow(new DomainException(
                "PROJECT_NOT_FOUND", HttpStatus.NOT_FOUND, Map.of()));

        mvc.perform(get("/api/public/projects/draft-only").queryParam("locale", "en"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"))
                .andExpect(header().string("X-Trace-Id", matchesPattern("[0-9a-f]{32}")));
    }

    private static PublicProjectCardDto card() {
        return new PublicProjectCardDto(
                java.util.UUID.fromString("10000000-0000-4000-8000-000000000010"),
                "gameplay-prototype",
                "01",
                0,
                true,
                "进行中",
                "作品",
                "玩法原型",
                "说明",
                List.of("UE"),
                null);
    }

    private static PublicProjectDto project(PublicProjectCardDto card) {
        return new PublicProjectDto(
                card.projectId(),
                card.slug(),
                card.number(),
                card.featured(),
                "IN PROGRESS",
                "WORK",
                "Gameplay Prototype",
                "Summary",
                "SEO title",
                "SEO description",
                List.of("UE"),
                List.of("C++"),
                List.of(),
                List.of());
    }

    private static PublicSiteDto site() {
        return new PublicSiteDto(
                new PublicSiteDto.Identity("YY", "Yi Jiaxuan", "易嘉轩", "hi@example.test"),
                new PublicSiteDto.Seo("Portfolio", "Game developer"),
                null,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                new PublicSiteDto.Resume(
                        "Resume",
                        LocalDate.parse("2026-07-14"),
                        "/api/public/media/10000000-0000-4000-8000-000000000010/document"));
    }
}
