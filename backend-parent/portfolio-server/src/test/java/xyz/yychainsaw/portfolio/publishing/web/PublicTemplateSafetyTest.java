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
