package xyz.yychainsaw.portfolio.media.persistence;

import java.util.Set;
import java.util.UUID;
import xyz.yychainsaw.portfolio.api.admin.media.StrictHttpsSourceUrl;

public record MediaTranslationRecord(
        UUID assetId,
        String locale,
        String altText,
        String caption,
        String credit,
        String sourceUrl) {
    private static final Set<String> LOCALES = Set.of("zh-CN", "en");

    public MediaTranslationRecord {
        if (assetId == null || !LOCALES.contains(locale)) {
            throw invalid();
        }
        requireBounded(altText, 500);
        requireBounded(caption, 1000);
        requireBounded(credit, 300);
        requireSourceUrl(sourceUrl);
    }

    private static void requireBounded(String value, int maximum) {
        if (value != null && value.length() > maximum) {
            throw invalid();
        }
    }

    private static void requireSourceUrl(String value) {
        try {
            StrictHttpsSourceUrl.requireValidNullable(value);
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("media translation is invalid");
    }
}
