package xyz.yychainsaw.portfolio.api.admin.media;

import java.net.URI;
import java.util.Set;

public record MediaTranslationView(
        String locale,
        String altText,
        String caption,
        String credit,
        String sourceUrl) {
    private static final Set<String> LOCALES = Set.of("zh-CN", "en");

    public MediaTranslationView {
        if (!LOCALES.contains(locale)) {
            throw new IllegalArgumentException("media translation locale is invalid");
        }
        requireBounded(altText, 500, "media translation alt text is invalid");
        requireBounded(caption, 1000, "media translation caption is invalid");
        requireBounded(credit, 300, "media translation credit is invalid");
        requireHttps(sourceUrl);
    }

    private static void requireBounded(String value, int maximum, String message) {
        if (value != null && value.length() > maximum) {
            throw new IllegalArgumentException(message);
        }
    }

    private static void requireHttps(String sourceUrl) {
        if (sourceUrl == null) {
            return;
        }
        if (sourceUrl.length() > 2048) {
            throw new IllegalArgumentException("media translation source URL is invalid");
        }
        URI parsed;
        try {
            parsed = URI.create(sourceUrl);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "media translation source URL is invalid", exception);
        }
        if (!"https".equalsIgnoreCase(parsed.getScheme())
                || parsed.getRawAuthority() == null
                || parsed.getRawAuthority().isBlank()) {
            throw new IllegalArgumentException("media translation source URL is invalid");
        }
    }
}
