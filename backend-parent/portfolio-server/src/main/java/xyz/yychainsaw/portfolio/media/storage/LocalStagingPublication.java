package xyz.yychainsaw.portfolio.media.storage;

import java.util.UUID;
import java.util.regex.Pattern;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;

public record LocalStagingPublication(
        UUID assetId,
        String objectKey,
        String sha256,
        String mimeType,
        StorageLocation location,
        long generation,
        UUID cleanupJobId) {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final String INVALID = "Local staging publication is invalid";

    public LocalStagingPublication {
        if (assetId == null
                || cleanupJobId == null
                || location == null
                || generation < 0
                || sha256 == null
                || !SHA256.matcher(sha256).matches()
                || !isSupportedMimeType(mimeType)) {
            throw new IllegalArgumentException(INVALID);
        }
        try {
            ObjectKey.parse(objectKey);
        } catch (RuntimeException invalidKey) {
            throw new IllegalArgumentException(INVALID);
        }
    }

    void requireInitialLocalIdentity() {
        requireLocalIdentity();
        if (generation != 0) {
            throw invalidAuthorization();
        }
    }

    void requireLocalIdentity() {
        String extension = switch (mimeType) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "application/pdf" -> "pdf";
            default -> throw invalidAuthorization();
        };
        String expectedKey = "staging/" + assetId + '/' + sha256 + '.' + extension;
        if (location.provider() != StorageProvider.LOCAL
                || location.bucket() != null
                || location.region() != null
                || !expectedKey.equals(objectKey)) {
            throw invalidAuthorization();
        }
    }

    @Override
    public String toString() {
        return "LocalStagingPublication[REDACTED]";
    }

    private static boolean isSupportedMimeType(String mimeType) {
        return "image/jpeg".equals(mimeType)
                || "image/png".equals(mimeType)
                || "application/pdf".equals(mimeType);
    }

    private static StorageException invalidAuthorization() {
        return new StorageException("LOCAL_STAGING_AUTHORIZATION_INVALID");
    }
}
