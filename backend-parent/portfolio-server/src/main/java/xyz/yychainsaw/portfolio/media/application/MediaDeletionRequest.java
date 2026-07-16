package xyz.yychainsaw.portfolio.media.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record MediaDeletionRequest(UUID assetId, long version, Instant cutoff) {
    public MediaDeletionRequest {
        Objects.requireNonNull(assetId, "media asset id is required");
        if (version < 0) {
            throw new IllegalArgumentException("media asset version is invalid");
        }
        Objects.requireNonNull(cutoff, "media cleanup cutoff is required");
    }
}
