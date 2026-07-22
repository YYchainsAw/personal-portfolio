package xyz.yychainsaw.portfolio.publishing.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

class PublicNotFoundTemplateTest {
    private SpringTemplateEngine templates;

    @BeforeEach
    void setUp() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode("HTML");
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(false);

        templates = new SpringTemplateEngine();
        templates.setTemplateResolver(resolver);
    }

    @Test
    void englishShellLoadsTheManifestAndContainsNoIndexableSeoState() {
        String html = render("en");

        assertThat(html)
                .contains("<html lang=\"en\">")
                .contains("name=\"theme-color\" content=\"#080d12\"")
                .contains("name=\"robots\" content=\"noindex, nofollow\"")
                .contains("<title>Page not found · Portfolio</title>")
                .contains("id=\"app\"")
                .contains("id=\"main-content\"")
                .contains("class=\"public-site-header public-site-header--ssr\" data-public-ssr")
                .contains("class=\"not-found not-found--ssr\" data-public-ssr")
                .contains("class=\"not-found__status\"")
                .contains("rel=\"stylesheet\" href=\"/assets/index-test.css\"")
                .contains("type=\"module\" src=\"/assets/index-test.js\"")
                .doesNotContain("rel=\"canonical\"")
                .doesNotContain("hreflang=")
                .doesNotContain("property=\"og:")
                .doesNotContain("application/ld+json")
                .doesNotContain("data-portfolio-seo")
                .doesNotContain("__PORTFOLIO_DATA__")
                .doesNotContain("https://yychainsaw.xyz");
    }

    @Test
    void chineseShellSetsTheDocumentLocaleAndLocalizedFallbackCopy() {
        String html = render("zh-CN");

        assertThat(html)
                .contains("<html lang=\"zh-CN\">")
                .contains("<title>页面未找到 · Portfolio</title>")
                .contains("<h1 id=\"not-found-title\">页面未找到</h1>")
                .contains("href=\"/zh-CN\"")
                .doesNotContain("rel=\"canonical\"")
                .doesNotContain("property=\"og:")
                .doesNotContain("application/ld+json");
    }

    private String render(String locale) {
        Context context = new Context(Locale.ROOT);
        context.setVariable("locale", locale);
        context.setVariable("assets", Map.of(
                "entryJs", "/assets/index-test.js",
                "css", List.of("/assets/index-test.css")));
        return templates.process("public/not-found", context);
    }
}
