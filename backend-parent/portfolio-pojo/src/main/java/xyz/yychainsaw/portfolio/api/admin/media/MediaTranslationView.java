package xyz.yychainsaw.portfolio.api.admin.media;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;
import java.util.Set;

@JsonInclude(JsonInclude.Include.ALWAYS)
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
        altText = Objects.requireNonNullElse(altText, "");
        caption = Objects.requireNonNullElse(caption, "");
        credit = Objects.requireNonNullElse(credit, "");
        requireBounded(altText, 500, "media translation alt text is invalid");
        requireBounded(caption, 1000, "media translation caption is invalid");
        requireBounded(credit, 300, "media translation credit is invalid");
        sourceUrl = requireSourceUrl(sourceUrl);
    }

    private static void requireBounded(String value, int maximum, String message) {
        if (value.length() > maximum) {
            throw new IllegalArgumentException(message);
        }
    }

    private static String requireSourceUrl(String value) {
        try {
            return StrictHttpsSourceUrl.requireValidNullable(value);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "media translation source URL is invalid");
        }
    }
}
