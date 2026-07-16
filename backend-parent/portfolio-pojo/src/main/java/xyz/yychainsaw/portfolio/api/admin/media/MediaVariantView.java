package xyz.yychainsaw.portfolio.api.admin.media;

import java.util.Set;
import java.util.regex.Pattern;

public record MediaVariantView(
        String name,
        Integer width,
        Integer height,
        String status) {
    private static final Pattern NAME = Pattern.compile("(?:document|w[1-9][0-9]{0,9})");
    private static final Set<String> STATUSES = Set.of("PROCESSING", "READY", "FAILED");

    public MediaVariantView {
        if (name == null || !NAME.matcher(name).matches() || !STATUSES.contains(status)) {
            throw invalid();
        }
        if ("document".equals(name)) {
            if (width != null || height != null) {
                throw invalid();
            }
        } else if (width == null
                || height == null
                || width <= 0
                || height <= 0
                || !name.equals("w" + width)) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("media variant view is invalid");
    }
}
