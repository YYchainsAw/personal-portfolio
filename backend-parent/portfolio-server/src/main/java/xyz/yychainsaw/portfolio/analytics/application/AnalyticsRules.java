package xyz.yychainsaw.portfolio.analytics.application;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import xyz.yychainsaw.portfolio.analytics.domain.AnalyticsEventType;
import xyz.yychainsaw.portfolio.analytics.domain.DeviceClass;

@Component
public final class AnalyticsRules {
    private static final String RULES_RESOURCE = "analytics/analytics-rules-v1.yml";
    private static final String EXPECTED_VERSION = "analytics-rules-v1";
    private static final String DIRECT_REFERRER = "(direct)";
    private static final String UNKNOWN_REFERRER = "(none)";
    private static final Set<String> TOP_LEVEL_KEYS = Set.of(
            "version", "eventTypes", "pageKeys", "crawlerTokens", "referrerDomains");
    private static final Set<String> EVENT_RULE_KEYS = Set.of("projectRequired");
    private static final Map<AnalyticsEventType, Boolean> EXPECTED_PROJECT_REQUIREMENTS = Map.of(
            AnalyticsEventType.PAGE_VIEW, false,
            AnalyticsEventType.PROJECT_VIEW, true,
            AnalyticsEventType.RESUME_DOWNLOAD, false,
            AnalyticsEventType.DEMO_DOWNLOAD, true,
            AnalyticsEventType.OUTBOUND_CLICK, false);
    private static final Set<String> EXPECTED_PAGE_KEYS = Set.of(
            "HOME",
            "ABOUT",
            "WORK",
            "ROADMAP",
            "CONTACT",
            "PRIVACY",
            "PROJECT_DETAIL");
    private static final Set<String> EXPECTED_CRAWLER_TOKENS = Set.of(
            "bot", "crawler", "spider", "preview");
    private static final Set<String> EXPECTED_REFERRER_DOMAINS = Set.of(
            "baidu.com",
            "bing.com",
            "bilibili.com",
            "csdn.net",
            "discord.com",
            "duckduckgo.com",
            "gitee.com",
            "github.com",
            "google.com",
            "juejin.cn",
            "linkedin.com",
            "reddit.com",
            "t.co",
            "x.com",
            "youtube.com",
            "zhihu.com");
    private static final Pattern HOST_LABEL = Pattern.compile(
            "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
    private static final Pattern DAILY_KEY_IN_HOST = Pattern.compile(
            "(?i)[0-9a-f]{32}[.-]?[0-9a-f]{32}");

    private final String version;
    private final Map<AnalyticsEventType, Boolean> projectRequirements;
    private final Set<String> pageKeys;
    private final Set<String> crawlerTokens;
    private final Set<String> referrerDomains;

    public AnalyticsRules() {
        this(DefaultDefinition.INSTANCE);
    }

    AnalyticsRules(Resource resource) {
        this(load(Objects.requireNonNull(resource, "analytics rules resource is required")));
    }

    private AnalyticsRules(Definition definition) {
        version = definition.version();
        projectRequirements = definition.projectRequirements();
        pageKeys = definition.pageKeys();
        crawlerTokens = definition.crawlerTokens();
        referrerDomains = definition.referrerDomains();
    }

    public String version() {
        return version;
    }

    public boolean isAllowedPageKey(String pageKey) {
        return pageKey != null && pageKeys.contains(pageKey);
    }

    public boolean projectRequired(AnalyticsEventType eventType) {
        Objects.requireNonNull(eventType, "analytics event type is required");
        Boolean required = projectRequirements.get(eventType);
        if (required == null) {
            throw new IllegalArgumentException("unknown analytics event type");
        }
        return required;
    }

    public boolean isCrawler(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return false;
        }
        String normalized = userAgent.toLowerCase(Locale.ROOT);
        return crawlerTokens.stream().anyMatch(normalized::contains);
    }

    public DeviceClass classifyDevice(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return DeviceClass.OTHER;
        }
        String normalized = userAgent.toLowerCase(Locale.ROOT);
        if (containsAny(
                normalized,
                "ipad",
                "tablet",
                "kindle",
                "silk/",
                "playbook")
                || normalized.contains("android") && !normalized.contains("mobile")) {
            return DeviceClass.TABLET;
        }
        if (containsAny(
                normalized,
                "iphone",
                "ipod",
                "mobile",
                "windows phone",
                "opera mini",
                "opera mobi")) {
            return DeviceClass.MOBILE;
        }
        if (containsAny(
                normalized,
                "windows nt",
                "macintosh",
                "x11",
                "cros",
                "linux x86_64")) {
            return DeviceClass.DESKTOP;
        }
        return DeviceClass.OTHER;
    }

    public String normalizeReferrer(
            String referrer,
            String canonicalSiteHost,
            String visitorId,
            String sessionId) {
        String siteHost = normalizeHost(
                canonicalSiteHost, "invalid analytics canonical site host");
        if (referrer == null || referrer.isBlank()) {
            return DIRECT_REFERRER;
        }
        if (!referrer.equals(referrer.trim())) {
            throw invalidReferrer();
        }

        URI parsed;
        try {
            parsed = new URI(referrer);
        } catch (URISyntaxException failure) {
            throw invalidReferrer();
        }
        String scheme = parsed.getScheme();
        if (!parsed.isAbsolute()
                || parsed.isOpaque()
                || scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw invalidReferrer();
        }
        String authority = parsed.getRawAuthority();
        if (authority == null
                || authority.isEmpty()
                || authority.indexOf('@') >= 0
                || parsed.getRawUserInfo() != null
                || authority.indexOf('%') >= 0) {
            throw invalidReferrer();
        }

        String host = normalizeHost(authorityHost(authority), "invalid analytics referrer");
        if (containsIdentity(host, visitorId)
                || containsIdentity(host, sessionId)
                || DAILY_KEY_IN_HOST.matcher(host).find()) {
            throw invalidReferrer();
        }
        if (host.equals(siteHost) || host.endsWith('.' + siteHost)) {
            return DIRECT_REFERRER;
        }
        for (String allowed : referrerDomains) {
            if (host.equals(allowed) || host.endsWith('.' + allowed)) {
                return allowed;
            }
        }
        return UNKNOWN_REFERRER;
    }

    private static Definition load(Resource resource) {
        try {
            YamlMapFactoryBean loader = new YamlMapFactoryBean();
            loader.setResources(resource);
            loader.setSingleton(true);
            loader.afterPropertiesSet();
            Map<String, Object> root = loader.getObject();
            if (root == null) {
                throw invalidRules();
            }
            return parse(root);
        } catch (IllegalStateException failure) {
            if ("invalid analytics rules".equals(failure.getMessage())) {
                throw failure;
            }
            throw invalidRules(failure);
        } catch (RuntimeException failure) {
            throw invalidRules(failure);
        }
    }

    private static Definition parse(Map<?, ?> root) {
        requireExactKeys(root, TOP_LEVEL_KEYS);
        Object rawVersion = root.get("version");
        if (!(rawVersion instanceof String parsedVersion)
                || !EXPECTED_VERSION.equals(parsedVersion)) {
            throw invalidRules();
        }

        Map<?, ?> rawEvents = requireMap(root.get("eventTypes"));
        Set<String> expectedEventNames = new LinkedHashSet<>();
        for (AnalyticsEventType eventType : AnalyticsEventType.values()) {
            expectedEventNames.add(eventType.name());
        }
        requireExactKeys(rawEvents, expectedEventNames);

        EnumMap<AnalyticsEventType, Boolean> requirements =
                new EnumMap<>(AnalyticsEventType.class);
        for (AnalyticsEventType eventType : AnalyticsEventType.values()) {
            Map<?, ?> eventRule = requireMap(rawEvents.get(eventType.name()));
            requireExactKeys(eventRule, EVENT_RULE_KEYS);
            Object required = eventRule.get("projectRequired");
            if (!(required instanceof Boolean projectRequired)) {
                throw invalidRules();
            }
            if (!Objects.equals(
                    projectRequired, EXPECTED_PROJECT_REQUIREMENTS.get(eventType))) {
                throw invalidRules();
            }
            requirements.put(eventType, projectRequired);
        }

        Set<String> parsedPageKeys = requireExactStringList(
                root.get("pageKeys"), EXPECTED_PAGE_KEYS);
        Set<String> parsedCrawlerTokens = requireExactStringList(
                root.get("crawlerTokens"), EXPECTED_CRAWLER_TOKENS);
        if (parsedCrawlerTokens.stream().anyMatch(token ->
                !token.equals(token.toLowerCase(Locale.ROOT))
                        || !token.matches("[a-z]+"))) {
            throw invalidRules();
        }
        Set<String> parsedReferrerDomains = requireExactStringList(
                root.get("referrerDomains"), EXPECTED_REFERRER_DOMAINS);
        if (parsedReferrerDomains.stream().anyMatch(domain ->
                !normalizeHost(domain, "invalid analytics rules").equals(domain))) {
            throw invalidRules();
        }

        return new Definition(
                parsedVersion,
                Map.copyOf(requirements),
                Set.copyOf(parsedPageKeys),
                Set.copyOf(parsedCrawlerTokens),
                Set.copyOf(parsedReferrerDomains));
    }

    private static Map<?, ?> requireMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw invalidRules();
        }
        return map;
    }

    private static void requireExactKeys(Map<?, ?> value, Set<String> expected) {
        LinkedHashSet<String> actual = new LinkedHashSet<>();
        for (Object key : value.keySet()) {
            if (!(key instanceof String stringKey) || !actual.add(stringKey)) {
                throw invalidRules();
            }
        }
        if (!actual.equals(expected)) {
            throw invalidRules();
        }
    }

    private static Set<String> requireExactStringList(
            Object value, Set<String> expected) {
        if (!(value instanceof List<?> list)) {
            throw invalidRules();
        }
        LinkedHashSet<String> parsed = new LinkedHashSet<>();
        for (Object item : list) {
            if (!(item instanceof String stringItem)
                    || stringItem.isBlank()
                    || !parsed.add(stringItem)) {
                throw invalidRules();
            }
        }
        if (!parsed.equals(expected)) {
            throw invalidRules();
        }
        return parsed;
    }

    private static String authorityHost(String authority) {
        if (authority.startsWith("[") || authority.indexOf(']') >= 0) {
            throw invalidReferrer();
        }
        int separator = authority.lastIndexOf(':');
        if (separator < 0) {
            return authority;
        }
        if (authority.indexOf(':') != separator) {
            throw invalidReferrer();
        }
        String port = authority.substring(separator + 1);
        if (port.isEmpty() || !port.chars().allMatch(Character::isDigit)) {
            throw invalidReferrer();
        }
        try {
            int parsedPort = Integer.parseInt(port);
            if (parsedPort < 1 || parsedPort > 65_535) {
                throw invalidReferrer();
            }
        } catch (NumberFormatException failure) {
            throw invalidReferrer();
        }
        return authority.substring(0, separator);
    }

    private static String normalizeHost(String value, String failureMessage) {
        if (value == null || value.isEmpty() || !value.equals(value.trim())) {
            throw new IllegalArgumentException(failureMessage);
        }
        String withoutTrailingDot = value.endsWith(".")
                ? value.substring(0, value.length() - 1)
                : value;
        if (withoutTrailingDot.isEmpty()) {
            throw new IllegalArgumentException(failureMessage);
        }

        String ascii;
        try {
            ascii = IDN.toASCII(withoutTrailingDot, IDN.USE_STD3_ASCII_RULES)
                    .toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(failureMessage);
        }
        if (ascii.isEmpty() || ascii.length() > 253 || isNumericAddress(ascii)) {
            throw new IllegalArgumentException(failureMessage);
        }
        for (String label : ascii.split("\\.", -1)) {
            if (!HOST_LABEL.matcher(label).matches()) {
                throw new IllegalArgumentException(failureMessage);
            }
        }
        return ascii;
    }

    private static boolean isNumericAddress(String host) {
        return host.chars().allMatch(character ->
                character == '.' || character >= '0' && character <= '9');
    }

    private static boolean containsIdentity(String host, String rawIdentity) {
        return rawIdentity != null
                && !rawIdentity.isBlank()
                && host.contains(rawIdentity.toLowerCase(Locale.ROOT));
    }

    private static boolean containsAny(String value, String... tokens) {
        for (String token : tokens) {
            if (value.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static IllegalArgumentException invalidReferrer() {
        return new IllegalArgumentException("invalid analytics referrer");
    }

    private static IllegalStateException invalidRules() {
        return new IllegalStateException("invalid analytics rules");
    }

    private static IllegalStateException invalidRules(RuntimeException cause) {
        return new IllegalStateException("invalid analytics rules", cause);
    }

    private record Definition(
            String version,
            Map<AnalyticsEventType, Boolean> projectRequirements,
            Set<String> pageKeys,
            Set<String> crawlerTokens,
            Set<String> referrerDomains) {
    }

    private static final class DefaultDefinition {
        private static final Definition INSTANCE =
                load(new ClassPathResource(RULES_RESOURCE));

        private DefaultDefinition() {
        }
    }
}
