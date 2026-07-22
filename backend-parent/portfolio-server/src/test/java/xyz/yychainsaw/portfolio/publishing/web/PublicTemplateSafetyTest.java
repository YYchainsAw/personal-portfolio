package xyz.yychainsaw.portfolio.publishing.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class PublicTemplateSafetyTest {
    private static final Pattern UTEXT_ATTRIBUTE = Pattern.compile("th:utext\\b");
    private static final Pattern UTEXT_VALUE = Pattern.compile(
            "th:utext\\s*=\\s*(['\"])(.*?)\\1", Pattern.DOTALL);

    @Test
    void unescapedHtmlIsRestrictedToTheFourReviewedSafeSources() throws Exception {
        assertThat(utext(template("home")))
                .containsExactlyInAnyOrder("${structuredData}", "${initialJson}");
        assertThat(utext(template("project")))
                .containsExactlyInAnyOrder(
                        "${structuredData}",
                        "${block.payload.html}",
                        "${initialJson}");
        assertThat(utext(template("privacy")))
                .containsExactlyInAnyOrder(
                        "${structuredData}",
                        "${site.privacy.html}",
                        "${initialJson}");
    }

    @Test
    void projectTemplateKeepsEveryClosedPublicBlockKind() throws Exception {
        String project = template("project");
        for (String type : Set.of(
                "MARKDOWN",
                "IMAGE",
                "GALLERY",
                "VIDEO",
                "CODE",
                "QUOTE",
                "METRICS",
                "DOWNLOAD",
                "LINK")) {
            assertThat(project).contains("th:case=\"'" + type + "'\"");
        }
    }

    @Test
    void publicTemplatesUseOneNonLandmarkVueHostAndOneMarkedStructuredDataNode()
            throws Exception {
        for (String name : List.of("home", "project", "privacy")) {
            String template = template(name);
            assertThat(template).contains("<div id=\"app\">");
            assertThat(count(Pattern.compile("\\bdata-public-ssr\\b").matcher(template)))
                    .as("transient server-rendered roots in %s", name)
                    .isEqualTo(2);
            assertThat(template).doesNotContain("<div id=\"app\" data-public-ssr");
            assertThat(template).contains(
                    "<link rel=\"icon\" type=\"image/svg+xml\" href=\"/favicon.svg\">");
            assertThat(template).doesNotContain("<main id=\"app\">");
            assertThat(count(Pattern.compile("<main\\b").matcher(template))).isEqualTo(1);
            assertThat(count(Pattern.compile("<h1\\b").matcher(template))).isEqualTo(1);
            assertThat(template.indexOf("<div id=\"app\">"))
                    .isLessThan(template.indexOf("<main"));
            assertThat(template.indexOf("</main>"))
                    .isLessThan(template.indexOf("<template id=\"__PORTFOLIO_DATA__\""));
            assertThat(template).contains(
                    "<script type=\"application/ld+json\" data-portfolio-seo");
            assertThat(template).contains(
                    "<script type=\"module\" th:src=\"${assets.entryJs}\"></script>");
            assertThat(template).doesNotContain("<script type=\"module\">");
        }
    }

    @Test
    void notFoundTemplateIsAStyleableTransientShellWithoutIndexablePageState()
            throws Exception {
        String template = template("not-found");

        assertThat(utext(template)).isEmpty();
        assertThat(count(Pattern.compile("\\bdata-public-ssr\\b").matcher(template)))
                .as("transient server-rendered roots in not-found")
                .isEqualTo(2);
        assertThat(template)
                .contains("<div id=\"app\">")
                .contains("<main id=\"main-content\" class=\"not-found not-found--ssr\" data-public-ssr")
                .contains("<meta name=\"theme-color\" content=\"#080d12\">")
                .contains("<meta name=\"robots\" content=\"noindex, nofollow\" data-route-noindex>")
                .contains("<script type=\"module\" th:src=\"${assets.entryJs}\"></script>")
                .doesNotContain("rel=\"canonical\"")
                .doesNotContain("hreflang=")
                .doesNotContain("property=\"og:")
                .doesNotContain("application/ld+json")
                .doesNotContain("data-portfolio-seo")
                .doesNotContain("__PORTFOLIO_DATA__");
    }

    @Test
    void serverAndClientAttributionRulesStayAligned() throws Exception {
        String home = template("home");
        assertThat(home)
                .contains("<h1 id=\"hero-title\" th:text=\"${site.hero.displayName}\"")
                .doesNotContain("<h1 id=\"hero-title\" th:text=\"${site.hero.headline}\"")
                .doesNotContain("site.hero.media.credit")
                .doesNotContain("site.hero.media.sourceUrl")
                .contains("#strings.startsWith(site.hero.sourceUrl, 'https://')");

        String project = template("project");
        assertThat(project)
                .doesNotContain("or !#strings.isEmpty(block.payload.media.sourceUrl)")
                .doesNotContain("or !#strings.isEmpty(item.sourceUrl)")
                .doesNotContain("or !#strings.isEmpty(block.payload.cover.sourceUrl)")
                .contains("#strings.startsWith(block.payload.media.sourceUrl, 'https://')")
                .contains("#strings.startsWith(item.sourceUrl, 'https://')")
                .contains("#strings.startsWith(block.payload.cover.sourceUrl, 'https://')")
                .contains("th:text=\"${locale == 'zh-CN'} ? '来源' : 'Source'\"")
                .contains("target=\"_blank\" rel=\"noopener noreferrer\">Source</a>");
    }

    @Test
    void robotsContractIsExact() throws Exception {
        String robots = new ClassPathResource("static/robots.txt")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(robots).isEqualTo("""
                User-agent: *
                Allow: /
                Disallow: /admin
                Disallow: /api/admin
                Sitemap: https://yychainsaw.xyz/sitemap.xml
                """);
    }

    private static String template(String name) throws Exception {
        return new ClassPathResource("templates/public/" + name + ".html")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private static List<String> utext(String template) {
        List<String> values = new ArrayList<>();
        Matcher matcher = UTEXT_VALUE.matcher(template);
        while (matcher.find()) {
            values.add(matcher.group(2));
        }
        assertThat(values).hasSize(count(UTEXT_ATTRIBUTE.matcher(template)));
        return values;
    }

    private static int count(Matcher matcher) {
        int matches = 0;
        while (matcher.find()) {
            matches++;
        }
        return matches;
    }
}
