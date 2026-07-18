package xyz.yychainsaw.portfolio.publishing.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.publicapi.PublicProjectCardDto;
import xyz.yychainsaw.portfolio.publicapi.PublishedEnvelope;
import xyz.yychainsaw.portfolio.publishing.application.PublicSnapshotQueryService;
import xyz.yychainsaw.portfolio.publishing.config.PublicRenderProperties;

class SnapshotPublicPageRendererTest {
    private static final String SITE_CHECKSUM = "a".repeat(64);
    private static final String CATALOG_CHECKSUM = "b".repeat(64);
    private static final String PROJECT_CHECKSUM = "c".repeat(64);

    private PublicSnapshotQueryService queries;
    private PublicRenderProperties properties;
    private AssetManifestService manifest;
    private CompositeEtagService etags;
    private SnapshotPublicPageRenderer renderer;

    @BeforeEach
    void setUp() {
        queries = mock(PublicSnapshotQueryService.class);
        properties = mock(PublicRenderProperties.class);
        manifest = mock(AssetManifestService.class);
        etags = new CompositeEtagService();
        when(properties.releaseId()).thenReturn("release-a");
        when(properties.templateSchemaVersion()).thenReturn(1);
        when(properties.publicBaseUrl()).thenReturn(URI.create("https://yychainsaw.xyz"));
        when(manifest.entryJs()).thenReturn("/assets/index-test123.js");
        when(manifest.css()).thenReturn(List.of("/assets/index-test123.css"));
        renderer = new SnapshotPublicPageRenderer(
                queries,
                properties,
                manifest,
                etags,
                new SafeInitialJson(new ObjectMapper().findAndRegisterModules()));
    }

    @Test
    void homeUsesOnlySiteAndCatalogAndBuildsAbsoluteSeoFromTheSameData() {
        var site = PublicPageFixtures.site("Published headline", true);
        List<PublicProjectCardDto> catalog = List.of(PublicPageFixtures.card());
        when(queries.site(LocaleCode.EN))
                .thenReturn(new PublishedEnvelope<>(7L, SITE_CHECKSUM, site));
        when(queries.catalog(LocaleCode.EN))
                .thenReturn(new PublishedEnvelope<>(8L, CATALOG_CHECKSUM, catalog));

        var page = renderer.home(LocaleCode.EN);

        assertThat(page.etag()).isEqualTo(etags.home(
                SITE_CHECKSUM,
                CATALOG_CHECKSUM,
                "release-a",
                1,
                LocaleCode.EN));
        assertThat(page.view().getViewName()).isEqualTo("public/home");
        assertThat(page.view().getModel())
                .containsEntry("locale", "en")
                .containsEntry("canonical", "https://yychainsaw.xyz/en")
                .containsEntry("zhUrl", "https://yychainsaw.xyz/zh-CN")
                .containsEntry("enUrl", "https://yychainsaw.xyz/en")
                .containsEntry("site", site)
                .containsEntry("catalog", catalog);
        assertThat(page.view().getModel().get("ogImage").toString())
                .startsWith("https://yychainsaw.xyz/api/public/media/");
        assertThat(page.view().getModel().get("initialJson").toString())
                .contains("\"kind\":\"home\"")
                .contains("Published headline");
        assertThat(page.view().getModel().get("structuredData").toString())
                .contains("\"@type\":\"Person\"")
                .contains("\"sameAs\":[\"https://github.com/YYchainsAw\"]");
        verify(queries).site(LocaleCode.EN);
        verify(queries).catalog(LocaleCode.EN);
        verifyNoMoreInteractions(queries);
    }

    @Test
    void projectPrefersTheMatchingCatalogCoverAndUsesPublicDataForJsonLd() {
        var site = PublicPageFixtures.site("Published headline", true);
        var card = PublicPageFixtures.card();
        var project = PublicPageFixtures.project();
        when(queries.project("gameplay-prototype", LocaleCode.EN))
                .thenReturn(new PublishedEnvelope<>(9L, PROJECT_CHECKSUM, project));
        when(queries.site(LocaleCode.EN))
                .thenReturn(new PublishedEnvelope<>(7L, SITE_CHECKSUM, site));
        when(queries.catalog(LocaleCode.EN))
                .thenReturn(new PublishedEnvelope<>(
                        8L, CATALOG_CHECKSUM, List.of(card)));

        var page = renderer.project(LocaleCode.EN, "gameplay-prototype").orElseThrow();

        assertThat(page.etag()).isEqualTo(etags.project(
                SITE_CHECKSUM,
                CATALOG_CHECKSUM,
                PROJECT_CHECKSUM,
                "release-a",
                1,
                LocaleCode.EN));
        assertThat(page.view().getModel())
                .containsEntry("cover", card.cover())
                .containsEntry(
                        "canonical",
                        "https://yychainsaw.xyz/en/projects/gameplay-prototype");
        assertThat(page.view().getModel().get("ogImage").toString())
                .isEqualTo("https://yychainsaw.xyz" + card.cover().src());
        assertThat(page.view().getModel().get("structuredData").toString())
                .contains("\"@type\":\"CreativeWork\"")
                .contains("\"name\":\"Published title\"")
                .contains("\"description\":\"Published summary\"")
                .doesNotContain("Published SEO title");
    }

    @Test
    void onlyExactProjectNotFoundBecomesEmptyAndOtherFailuresPropagate() {
        DomainException missing = new DomainException(
                "PROJECT_NOT_FOUND", HttpStatus.NOT_FOUND, Map.of());
        when(queries.project("missing", LocaleCode.EN)).thenThrow(missing);

        assertThat(renderer.project(LocaleCode.EN, "missing")).isEmpty();
        verify(queries, never()).site(LocaleCode.EN);
        verify(queries, never()).catalog(LocaleCode.EN);

        DomainException unsupported = new DomainException(
                "SNAPSHOT_SCHEMA_UNSUPPORTED",
                HttpStatus.UNPROCESSABLE_ENTITY,
                Map.of());
        when(queries.project("unsupported", LocaleCode.EN)).thenThrow(unsupported);

        assertThatThrownBy(() -> renderer.project(LocaleCode.EN, "unsupported"))
                .isSameAs(unsupported);
    }

    @Test
    void privacyAndMediaLessHomeDoNotReadUnneededAggregatesOrDereferenceMedia() {
        var site = PublicPageFixtures.site("Published headline", false);
        when(queries.site(LocaleCode.ZH_CN))
                .thenReturn(new PublishedEnvelope<>(7L, SITE_CHECKSUM, site));
        when(queries.catalog(LocaleCode.ZH_CN))
                .thenReturn(new PublishedEnvelope<>(
                        8L, CATALOG_CHECKSUM, List.of(PublicPageFixtures.card())));

        var home = renderer.home(LocaleCode.ZH_CN);
        assertThat(home.view().getModel()).containsEntry("ogImage", null);

        var privacy = renderer.privacy(LocaleCode.ZH_CN);
        assertThat(privacy.etag()).isEqualTo(etags.privacy(
                SITE_CHECKSUM, "release-a", 1, LocaleCode.ZH_CN));
        assertThat(privacy.view().getModel().get("structuredData").toString())
                .contains("\"@type\":\"WebPage\"")
                .contains("\"name\":\"Privacy\"");
        verify(queries).catalog(LocaleCode.ZH_CN);
        verify(queries, never()).project(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }
}
