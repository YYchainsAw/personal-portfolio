package xyz.yychainsaw.portfolio.media.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import xyz.yychainsaw.portfolio.api.admin.media.MediaAssetView;
import xyz.yychainsaw.portfolio.media.application.MediaObjectKeys;
import xyz.yychainsaw.portfolio.media.domain.MediaStatus;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;

public record MediaAssetRecord(
        UUID id,
        StorageProvider provider,
        String bucket,
        String region,
        String objectKey,
        String originalFilename,
        String mimeType,
        long byteSize,
        Integer width,
        Integer height,
        String sha256,
        MediaStatus status,
        Instant archivedAt,
        long version,
        Instant createdAt,
        Instant updatedAt) {
    private static final Set<String> MIME_TYPES =
            Set.of("image/jpeg", "image/png", "application/pdf");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final long MAX_PIXELS = 80_000_000L;

    public MediaAssetRecord {
        validateIdentity(
                id, provider, bucket, region, objectKey, originalFilename,
                mimeType, byteSize, width, height, sha256);
        Objects.requireNonNull(status, "media status is required");
        if ((status == MediaStatus.ARCHIVED || status == MediaStatus.PENDING_DELETE)
                != (archivedAt != null)) {
            throw new IllegalArgumentException("media archive state is invalid");
        }
        if (version < 0) {
            throw new IllegalArgumentException("media version is invalid");
        }
        Objects.requireNonNull(createdAt, "media created timestamp is required");
        Objects.requireNonNull(updatedAt, "media updated timestamp is required");
    }

    public MediaAssetView toView() {
        return new MediaAssetView(
                id,
                originalFilename,
                mimeType,
                byteSize,
                width,
                height,
                sha256,
                status.name(),
                version,
                createdAt,
                updatedAt);
    }

    public record Insert(
            UUID id,
            StorageProvider provider,
            String bucket,
            String region,
            String objectKey,
            String originalFilename,
            String mimeType,
            long byteSize,
            Integer width,
            Integer height,
            String sha256) {
        public Insert {
            validateIdentity(
                    id, provider, bucket, region, objectKey, originalFilename,
                    mimeType, byteSize, width, height, sha256);
        }
    }

    private static void validateIdentity(
            UUID id,
            StorageProvider provider,
            String bucket,
            String region,
            String objectKey,
            String originalFilename,
            String mimeType,
            long byteSize,
            Integer width,
            Integer height,
            String sha256) {
        Objects.requireNonNull(id, "media asset id is required");
        Objects.requireNonNull(provider, "media storage provider is required");
        if (provider == StorageProvider.LOCAL) {
            if (bucket != null || region != null) {
                throw new IllegalArgumentException("local media storage metadata is invalid");
            }
        } else {
            requireBoundedText(bucket, 128, "remote media storage metadata is invalid");
            requireBoundedText(region, 64, "remote media storage metadata is invalid");
        }
        requireBoundedText(objectKey, 512, "media object key is invalid");
        requireBoundedText(originalFilename, 255, "media filename is invalid");
        if (!MIME_TYPES.contains(mimeType)) {
            throw new IllegalArgumentException("media MIME type is invalid");
        }
        if (byteSize <= 0) {
            throw new IllegalArgumentException("media byte size is invalid");
        }
        requireDimensions(mimeType, width, height);
        if (sha256 == null || !SHA256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("media SHA-256 is invalid");
        }
        if (!objectKey.equals(MediaObjectKeys.originalKey(id, sha256, mimeType))) {
            throw new IllegalArgumentException("media object key is invalid");
        }
    }

    private static void requireDimensions(String mimeType, Integer width, Integer height) {
        if ("application/pdf".equals(mimeType)) {
            if (width != null || height != null) {
                throw new IllegalArgumentException("media dimensions are invalid");
            }
            return;
        }
        if (width == null || height == null || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("media dimensions are invalid");
        }
        if ((long) width * height > MAX_PIXELS) {
            throw new IllegalArgumentException("media pixel count is invalid");
        }
    }

    private static void requireBoundedText(String value, int maximum, String message) {
        if (!isText(value)
                || value.codePointCount(0, value.length()) > maximum) {
            throw new IllegalArgumentException(message);
        }
    }

    private static boolean isText(String value) {
        return value != null && !value.isBlank() && value.equals(value.trim());
    }
}
