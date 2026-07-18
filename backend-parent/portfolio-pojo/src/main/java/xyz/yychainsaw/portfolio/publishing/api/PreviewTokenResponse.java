package xyz.yychainsaw.portfolio.publishing.api;

import java.time.Instant;
import java.util.Objects;

public record PreviewTokenResponse(String token, Instant expiresAt) {
    public PreviewTokenResponse {
        Objects.requireNonNull(token, "preview token is required");
        Objects.requireNonNull(expiresAt, "preview token expiry is required");
    }

    @Override
    public String toString() {
        return "PreviewTokenResponse[token=<redacted>, expiresAt=" + expiresAt + ']';
    }
}
