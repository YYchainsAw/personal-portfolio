package xyz.yychainsaw.portfolio.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.ByteArrayResource;
import xyz.yychainsaw.portfolio.analytics.domain.AnalyticsEventType;
import xyz.yychainsaw.portfolio.analytics.domain.DeviceClass;

class AnalyticsRulesTest {
    private static final String VISITOR_ID = "abcdefghijklmnopqrstuv";
    private static final String SESSION_ID = "zyxwvutsrqponmlkjihgfe";

    private final AnalyticsRules rules = new AnalyticsRules();

    @Test
    void loadsTheExactVersionedAllowlistAndProjectRequirements() {
        assertThat(rules.version()).isEqualTo("analytics-rules-v1");
        assertThat(rules.isAllowedPageKey("HOME")).isTrue();
        assertThat(rules.isAllowedPageKey("PROJECT_DETAIL")).isTrue();
        assertThat(rules.isAllowedPageKey("home")).isFalse();
        assertThat(rules.isAllowedPageKey("PRIVATE")).isFalse();
        assertThat(rules.isAllowedPageKey(null)).isFalse();

        assertThat(rules.projectRequired(AnalyticsEventType.PAGE_VIEW)).isFalse();
        assertThat(rules.projectRequired(AnalyticsEventType.PROJECT_VIEW)).isTrue();
        assertThat(rules.projectRequired(AnalyticsEventType.RESUME_DOWNLOAD)).isFalse();
        assertThat(rules.projectRequired(AnalyticsEventType.DEMO_DOWNLOAD)).isTrue();
        assertThat(rules.projectRequired(AnalyticsEventType.OUTBOUND_CLICK)).isFalse();
        assertThatThrownBy(() -> rules.projectRequired(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("analytics event type is required");
    }

    @Test
    void detectsVersionedCrawlerTokensWithoutCaseSensitivity() {
        assertThat(rules.isCrawler("GoogleBot/2.1")).isTrue();
        assertThat(rules.isCrawler("Example CRAWLER/1.0")).isTrue();
        assertThat(rules.isCrawler("Social Preview Agent")).isTrue();
        assertThat(rules.isCrawler("FriendlySpider")).isTrue();
        assertThat(rules.isCrawler("Mozilla/5.0")).isFalse();
        assertThat(rules.isCrawler(null)).isFalse();
    }

    @Test
    void classifiesOnlyCoarseDeviceFamilies() {
        assertThat(rules.classifyDevice(
                        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) Mobile"))
                .isEqualTo(DeviceClass.MOBILE);
        assertThat(rules.classifyDevice(
                        "Mozilla/5.0 (Linux; Android 14; Pixel Tablet) AppleWebKit/537.36"))
                .isEqualTo(DeviceClass.TABLET);
        assertThat(rules.classifyDevice(
                        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36"))
                .isEqualTo(DeviceClass.DESKTOP);
        assertThat(rules.classifyDevice("curl/8.0")).isEqualTo(DeviceClass.OTHER);
        assertThat(rules.classifyDevice(" ")).isEqualTo(DeviceClass.OTHER);
    }

    @Test
    void normalizesSafeAbsoluteReferrersWithoutDnsResolution() {
        assertThat(normalize(null)).isEqualTo("(direct)");
        assertThat(normalize("   ")).isEqualTo("(direct)");
        assertThat(normalize("https://YYCHAINSAW.XYZ./work?q=private#fragment"))
                .isEqualTo("(direct)");
        assertThat(normalize("https://WWW.YYCHAINSAW.XYZ/work"))
                .isEqualTo("(direct)");
        assertThat(normalize("https://evilyychainsaw.xyz/work"))
                .isEqualTo("(none)");
        assertThat(normalize("HTTP://WWW.GitHub.COM:8080/path?q=private#fragment"))
                .isEqualTo("github.com");
        assertThat(normalize("https://github.com.evil.invalid/path"))
                .isEqualTo("(none)");
        assertThat(normalize("https://evilgithub.com/path"))
                .isEqualTo("(none)");
        assertThat(normalize("https://BÜCHER.example/path"))
                .isEqualTo("(none)");
        assertThat(normalize("https://host-that-does-not-exist.invalid/path"))
                .isEqualTo("(none)");
    }

    @ParameterizedTest
    @MethodSource("unsafeReferrers")
    void rejectsUnsafeOrIdentityBearingReferrers(String referrer) {
        assertThatThrownBy(() -> normalize(referrer))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid analytics referrer")
                .hasMessageNotContaining(VISITOR_ID)
                .hasMessageNotContaining(SESSION_ID);
    }

    @ParameterizedTest
    @MethodSource("invalidRuleDocuments")
    void rejectsDuplicateMissingUnknownAndWronglyTypedRules(String yaml) {
        ByteArrayResource resource = new ByteArrayResource(
                yaml.getBytes(StandardCharsets.UTF_8), "analytics-rules-test.yml");

        assertThatThrownBy(() -> new AnalyticsRules(resource))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("invalid analytics rules");
    }

    private String normalize(String referrer) {
        return rules.normalizeReferrer(
                referrer, "YYCHAINSAW.XYZ.", VISITOR_ID, SESSION_ID);
    }

    private static Stream<String> unsafeReferrers() {
        String splitDailyKey = "a".repeat(32) + "." + "b".repeat(32);
        return Stream.of(
                "/relative/path",
                "ftp://example.com/file",
                "https://user:secret@example.com/path",
                "https://127.0.0.1/path",
                "https://[2001:db8::1]/path",
                "https://example.com:0/path",
                "https://example.com:65536/path",
                " https://example.com/path",
                "https://" + VISITOR_ID + ".example/path",
                "https://" + SESSION_ID + ".example/path",
                "https://" + splitDailyKey + ".example/path");
    }

    private static Stream<String> invalidRuleDocuments() {
        String valid = validRules();
        return Stream.of(
                valid + "unknown: true\n",
                valid + "version: analytics-rules-v1\n",
                valid.replace(
                        "PAGE_VIEW: { projectRequired: false }",
                        "PAGE_VIEW: { projectRequired: false, unknown: true }"),
                valid.replace(
                        "PAGE_VIEW: { projectRequired: false }",
                        "PAGE_VIEW: { projectRequired: \"false\" }"),
                valid.replace(
                        "PAGE_VIEW: { projectRequired: false }",
                        "PAGE_VIEW: { projectRequired: true }"),
                valid.replace("  OUTBOUND_CLICK: { projectRequired: false }\n", ""),
                valid.replace(
                        "  OUTBOUND_CLICK: { projectRequired: false }",
                        "  OUTBOUND_CLICK: { projectRequired: false }\n"
                                + "  UNKNOWN_EVENT: { projectRequired: false }"),
                valid.replace("  - PROJECT_DETAIL\n", ""),
                valid.replace("  - HOME\n", "  - HOME\n  - HOME\n"),
                valid.replace("  - PROJECT_DETAIL\n", "  - PROJECT_DETAIL\n  - PRIVATE\n"),
                valid.replace("  - preview\n", ""),
                valid.replace("  - bot\n", "  - bot\n  - bot\n"),
                valid.replace("  - preview\n", "  - preview\n  - unknown\n"),
                valid.replace("  - zhihu.com\n", ""),
                valid.replace("  - baidu.com\n", "  - baidu.com\n  - baidu.com\n"),
                valid.replace("  - zhihu.com\n", "  - zhihu.com\n  - example.com\n"),
                valid.replace("version: analytics-rules-v1", "version: analytics-rules-v2"));
    }

    private static String validRules() {
        return """
                version: analytics-rules-v1
                eventTypes:
                  PAGE_VIEW: { projectRequired: false }
                  PROJECT_VIEW: { projectRequired: true }
                  RESUME_DOWNLOAD: { projectRequired: false }
                  DEMO_DOWNLOAD: { projectRequired: true }
                  OUTBOUND_CLICK: { projectRequired: false }
                pageKeys:
                  - HOME
                  - ABOUT
                  - WORK
                  - ROADMAP
                  - CONTACT
                  - PRIVACY
                  - PROJECT_DETAIL
                crawlerTokens:
                  - bot
                  - crawler
                  - spider
                  - preview
                referrerDomains:
                  - baidu.com
                  - bing.com
                  - bilibili.com
                  - csdn.net
                  - discord.com
                  - duckduckgo.com
                  - gitee.com
                  - github.com
                  - google.com
                  - juejin.cn
                  - linkedin.com
                  - reddit.com
                  - t.co
                  - x.com
                  - youtube.com
                  - zhihu.com
                """;
    }
}
