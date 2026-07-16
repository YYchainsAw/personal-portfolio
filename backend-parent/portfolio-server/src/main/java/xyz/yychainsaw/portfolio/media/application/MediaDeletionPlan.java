package xyz.yychainsaw.portfolio.media.application;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRecord;

public record MediaDeletionPlan(
        MediaAssetRecord asset, Instant cutoff, List<String> objectKeys) {
    public MediaDeletionPlan {
        Objects.requireNonNull(asset, "media asset is required");
        Objects.requireNonNull(cutoff, "media cleanup cutoff is required");
        List<String> supplied = List.copyOf(
                Objects.requireNonNull(objectKeys, "media object keys are required"));
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String objectKey : supplied) {
            if (objectKey == null || objectKey.isBlank()) {
                throw new IllegalArgumentException("media object key is invalid");
            }
            unique.add(objectKey);
        }
        objectKeys = List.copyOf(unique);
    }
}
