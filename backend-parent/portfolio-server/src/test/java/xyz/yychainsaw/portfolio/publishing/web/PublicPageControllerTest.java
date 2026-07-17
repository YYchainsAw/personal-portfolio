package xyz.yychainsaw.portfolio.publishing.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.View;
import xyz.yychainsaw.portfolio.common.error.GlobalProblemHandler;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.publishing.persistence.PublishingRepository;

class PublicPageControllerTest {
    private static final String ETAG = '"' + "a".repeat(64) + '"';

    private PublicPageRenderer pages;
    private PublishingRepository publishing;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        pages = mock(PublicPageRenderer.class);
        publishing = mock(PublishingRepository.class);
        mvc = MockMvcBuilders.standaloneSetup(
                        new PublicPageController(pages, publishing))
                .setControllerAdvice(new GlobalProblemHandler())
                .build();
    }

    @Test
    void rootAlwaysRedirectsToChinese() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, "/zh-CN"));

        verifyNoInteractions(pages, publishing);
    }

    @Test
    void homeLocaleComesOnlyFromTheExactPath() throws Exception {
        when(pages.home(LocaleCode.EN)).thenReturn(page(ETAG));

        mvc.perform(get("/en")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "zh-CN")
                        .queryParam("locale", "zh-CN"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, no-cache"))
                .andExpect(header().string(HttpHeaders.ETAG, ETAG))
                .andExpect(content().string("rendered-page"));

        verify(pages).home(LocaleCode.EN);
        verifyNoInteractions(publishing);
    }

    @Test
    void exactStrongPageEtagReturns304WithNoBody() throws Exception {
        when(pages.home(LocaleCode.ZH_CN)).thenReturn(page(ETAG));

        mvc.perform(get("/zh-CN").header(HttpHeaders.IF_NONE_MATCH, ETAG))
                .andExpect(status().isNotModified())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, no-cache"))
                .andExpect(header().string(HttpHeaders.ETAG, ETAG))
                .andExpect(content().string(""));
    }

    @Test
    void weakWildcardListDuplicateWhitespaceAndMismatchedEtagsReturn200() throws Exception {
        when(pages.home(LocaleCode.EN)).thenReturn(page(ETAG));

        for (String candidate : List.of(
                "W/" + ETAG,
                "*",
                ETAG + ", \"other\"",
                " \t" + ETAG,
                '"' + "b".repeat(64) + '"')) {
            mvc.perform(get("/en").header(HttpHeaders.IF_NONE_MATCH, candidate))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.ETAG, ETAG))
                    .andExpect(content().string("rendered-page"));
        }
        mvc.perform(get("/en").header(HttpHeaders.IF_NONE_MATCH, ETAG, ETAG))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, ETAG))
                .andExpect(content().string("rendered-page"));
    }

    @Test
    void currentProjectRendersWithoutConsultingRedirects() throws Exception {
        when(pages.project(LocaleCode.EN, "current-slug"))
                .thenReturn(Optional.of(page(ETAG)));

        mvc.perform(get("/en/projects/current-slug"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, ETAG))
                .andExpect(content().string("rendered-page"));

        verify(pages).project(LocaleCode.EN, "current-slug");
        verifyNoInteractions(publishing);
    }

    @Test
    void historicalSlugRedirectsOnceToTheCurrentSlugAndPreservesLocale() throws Exception {
        when(pages.project(LocaleCode.ZH_CN, "old-slug")).thenReturn(Optional.empty());
        when(publishing.redirectTarget("old-slug")).thenReturn(Optional.of("current-slug"));

        mvc.perform(get("/zh-CN/projects/old-slug")
                        .queryParam("next", "https://evil.example"))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string(
                        HttpHeaders.LOCATION,
                        "/zh-CN/projects/current-slug"));

        verify(pages).project(LocaleCode.ZH_CN, "old-slug");
        verify(publishing).redirectTarget("old-slug");
    }

    @Test
    void unknownArchivedOrSelfRedirectingSlugReturnsStable404() throws Exception {
        for (Optional<String> target : List.of(
                Optional.<String>empty(), Optional.of("missing"))) {
            when(pages.project(LocaleCode.EN, "missing")).thenReturn(Optional.empty());
            when(publishing.redirectTarget("missing")).thenReturn(target);

            mvc.perform(get("/en/projects/missing"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("PUBLIC_CONTENT_NOT_FOUND"));
        }
    }

    @Test
    void privacyUsesTheLocaleFromItsPathAndSupportsRevalidation() throws Exception {
        when(pages.privacy(LocaleCode.EN)).thenReturn(page(ETAG));

        mvc.perform(get("/en/privacy"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, ETAG))
                .andExpect(content().string("rendered-page"));

        verify(pages).privacy(LocaleCode.EN);
    }

    private static PublicPageRenderer.PreparedPage page(String etag) {
        View view = (model, request, response) ->
                response.getWriter().write("rendered-page");
        return new PublicPageRenderer.PreparedPage(
                etag, new ModelAndView(view));
    }
}
