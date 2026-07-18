package xyz.yychainsaw.portfolio.publishing.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SafeMarkdownRendererTest {
    private final SafeMarkdownRenderer renderer = new SafeMarkdownRenderer();

    @Test
    void rendersOrdinaryMarkdown() {
        String html = renderer.render("# Heading\n\nA **safe** paragraph.");

        assertThat(html)
                .contains("<h1>Heading</h1>")
                .contains("A <strong>safe</strong> paragraph.");
    }

    @Test
    void escapesRawHtmlInsteadOfTrustingPublishedMarkdown() {
        String html = renderer.render("<script>alert('x')</script>\n\n<img src=x onerror=alert(1)>");

        assertThat(html)
                .doesNotContain("<script>")
                .doesNotContain("<img")
                .contains("&lt;script&gt;")
                .contains("&lt;img");
    }

    @Test
    void sanitizesJavascriptAndDataLinks() {
        String html = renderer.render(
                "[javascript](javascript:alert(1)) [data](data:text/html;base64,PHNjcmlwdD4=)");

        assertThat(html)
                .contains("javascript")
                .contains("data")
                .doesNotContain("javascript:")
                .doesNotContain("data:text/html");
    }

    @Test
    void stripsObfuscatedDangerousSchemesFromLinksAndImages() {
        String html = renderer.render("""
                [entity](java&#x73;cript:alert(1))
                [percent](jav%61script:alert(1))
                [control](java%0Ascript:alert(1))
                [mixed](JaVaScRiPt:alert(1))
                ![image](DaTa:image/svg+xml;base64,PHN2Zz48L3N2Zz4=)
                """);

        assertThat(html)
                .contains("entity", "percent", "control", "mixed", "image")
                .doesNotContain("<a ")
                .doesNotContain("<img ")
                .doesNotContainIgnoringCase("javascript:")
                .doesNotContainIgnoringCase("data:");
    }

    @Test
    void preservesAbsoluteHttpsLinks() {
        String html = renderer.render("[source](https://example.com/work?q=portfolio)");

        assertThat(html).contains("href=\"https://example.com/work?q=portfolio\"");
    }
}
