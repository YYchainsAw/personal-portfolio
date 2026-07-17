package xyz.yychainsaw.portfolio.media.storage;

import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public record LocalStagingAuditExpectation(
        UUID assetId, String sha256, String mimeType) {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> MIME_TYPES = Set.of(
            "image/jpeg", "image/png", "application/pdf");

    public LocalStagingAuditExpectation {
        if (assetId == null
                || sha256 == null
                || !SHA256.matcher(sha256).matches()
                || !MIME_TYPES.contains(mimeType)) {
            throw new IllegalArgumentException(
                    "local staging audit expectation is invalid");
        }
    }

    @Override
    public String toString() {
        return "LocalStagingAuditExpectation[redacted]";
    }
}
