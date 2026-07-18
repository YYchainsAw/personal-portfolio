package xyz.yychainsaw.portfolio.publishing.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;

class CompositeEtagServiceTest {
    private final CompositeEtagService etags = new CompositeEtagService();

    @Test
    void homeEtagCoversEveryRenderedDependency() {
        String baseline = etags.home(
                "site-a", "catalog-a", "release-a", 1, LocaleCode.EN);

        assertThat(etags.home(
                        "site-b", "catalog-a", "release-a", 1, LocaleCode.EN))
                .isNotEqualTo(baseline);
        assertThat(etags.home(
                        "site-a", "catalog-b", "release-a", 1, LocaleCode.EN))
                .isNotEqualTo(baseline);
        assertThat(etags.home(
                        "site-a", "catalog-a", "release-b", 1, LocaleCode.EN))
                .isNotEqualTo(baseline);
        assertThat(etags.home(
                        "site-a", "catalog-a", "release-a", 2, LocaleCode.EN))
                .isNotEqualTo(baseline);
        assertThat(etags.home(
                        "site-a", "catalog-a", "release-a", 1, LocaleCode.ZH_CN))
                .isNotEqualTo(baseline);
    }

    @Test
    void projectEtagCoversSiteCatalogProjectReleaseTemplateAndLocale() {
        String baseline = etags.project(
                "site-a",
                "catalog-a",
                "project-a",
                "release-a",
                1,
                LocaleCode.EN);

        assertThat(etags.project(
                        "site-b",
                        "catalog-a",
                        "project-a",
                        "release-a",
                        1,
                        LocaleCode.EN))
                .isNotEqualTo(baseline);
        assertThat(etags.project(
                        "site-a",
                        "catalog-b",
                        "project-a",
                        "release-a",
                        1,
                        LocaleCode.EN))
                .isNotEqualTo(baseline);
        assertThat(etags.project(
                        "site-a",
                        "catalog-a",
                        "project-b",
                        "release-a",
                        1,
                        LocaleCode.EN))
                .isNotEqualTo(baseline);
        assertThat(etags.project(
                        "site-a",
                        "catalog-a",
                        "project-a",
                        "release-b",
                        1,
                        LocaleCode.EN))
                .isNotEqualTo(baseline);
        assertThat(etags.project(
                        "site-a",
                        "catalog-a",
                        "project-a",
                        "release-a",
                        2,
                        LocaleCode.EN))
                .isNotEqualTo(baseline);
        assertThat(etags.project(
                        "site-a",
                        "catalog-a",
                        "project-a",
                        "release-a",
                        1,
                        LocaleCode.ZH_CN))
                .isNotEqualTo(baseline);
    }

    @Test
    void privacyAndSitemapEtagsCoverTheirExactDependenciesAndAreQuotedSha256() {
        String privacy = etags.privacy("site-a", "release-a", 1, LocaleCode.EN);
        assertThat(etags.privacy("site-b", "release-a", 1, LocaleCode.EN))
                .isNotEqualTo(privacy);
        assertThat(etags.privacy("site-a", "release-b", 1, LocaleCode.EN))
                .isNotEqualTo(privacy);
        assertThat(etags.privacy("site-a", "release-a", 2, LocaleCode.EN))
                .isNotEqualTo(privacy);
        assertThat(etags.privacy("site-a", "release-a", 1, LocaleCode.ZH_CN))
                .isNotEqualTo(privacy);

        String sitemap = etags.sitemap("catalog-a");
        assertThat(etags.sitemap("catalog-b")).isNotEqualTo(sitemap);
        assertThat(privacy).matches("\"[0-9a-f]{64}\"");
        assertThat(sitemap).matches("\"[0-9a-f]{64}\"");
    }

    @Test
    void etagsAreDeterministicAndNullDelimitedAcrossPageDomains() {
        String home = etags.home(
                "site-a", "catalog-a", "release-a", 1, LocaleCode.EN);

        assertThat(etags.home(
                        "site-a", "catalog-a", "release-a", 1, LocaleCode.EN))
                .isEqualTo(home);
        assertThat(etags.home("ab", "c", "release-a", 1, LocaleCode.EN))
                .isNotEqualTo(etags.home(
                        "a", "bc", "release-a", 1, LocaleCode.EN));
        assertThat(etags.project(
                        "site-a",
                        "catalog-a",
                        "project-a",
                        "release-a",
                        1,
                        LocaleCode.EN))
                .isNotEqualTo(home);
    }
}
