package xyz.yychainsaw.portfolio.api.admin.media;

import java.util.List;
import java.util.Objects;

public record MediaPageView(
        List<MediaAssetView> items,
        int page,
        int size,
        long totalItems,
        int totalPages) {
    public MediaPageView {
        items = List.copyOf(Objects.requireNonNull(items, "media page items are required"));
        if (page < 0 || size <= 0 || totalItems < 0 || totalPages < 0 || items.size() > size) {
            throw invalid();
        }
        long expectedPages = totalItems / size + (totalItems % size == 0 ? 0 : 1);
        if (expectedPages > Integer.MAX_VALUE || totalPages != (int) expectedPages) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("media page is invalid");
    }
}
