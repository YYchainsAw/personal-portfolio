package xyz.yychainsaw.portfolio.media.persistence;

import java.util.List;
import java.util.Objects;

public record MediaAssetPage(List<MediaAssetRecord> items, long totalItems) {
    public MediaAssetPage {
        items = List.copyOf(Objects.requireNonNull(items, "media page items are required"));
        if (totalItems < 0 || items.size() > totalItems) {
            throw new IllegalArgumentException("media asset page is invalid");
        }
    }
}
