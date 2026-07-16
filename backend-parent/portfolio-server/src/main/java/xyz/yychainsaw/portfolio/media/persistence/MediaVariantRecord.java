package xyz.yychainsaw.portfolio.media.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import xyz.yychainsaw.portfolio.media.application.MediaObjectKeys;

public record MediaVariantRecord(
        UUID id,
        UUID assetId,
        String variantName,
        String format,
        String objectKey,
        String mimeType,
        long byteSize,
        Integer width,
        Integer height,
        String sha256,
        String status,
        Instant createdAt) {
    private static final Pattern NAME = Pattern.compile("(?:document|w[1-9][0-9]{0,9})");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> STATUSES = Set.of("PROCESSING", "READY", "FAILED");

    public MediaVariantRecord {
        validate(
                id,
                assetId,
                variantName,
                format,
                objectKey,
                mimeType,
                byteSize,
                width,
                height,
                sha256);
        if (!STATUSES.contains(status)) {
            throw invalid();
        }
        Objects.requireNonNull(createdAt, "media variant timestamp is required");
    }

    public boolean matches(Insert expected) {
        return expected != null
                && "READY".equals(status)
                && assetId.equals(expected.assetId())
                && variantName.equals(expected.variantName())
                && format.equals(expected.format())
                && objectKey.equals(expected.objectKey())
                && mimeType.equals(expected.mimeType())
                && byteSize == expected.byteSize()
                && Objects.equals(width, expected.width())
                && Objects.equals(height, expected.height())
                && sha256.equals(expected.sha256());
    }

    public record Insert(
            UUID id,
            UUID assetId,
            String variantName,
            String format,
            String objectKey,
            String mimeType,
            long byteSize,
            Integer width,
            Integer height,
            String sha256) {
        public Insert {
            validate(
                    id,
                    assetId,
                    variantName,
                    format,
                    objectKey,
                    mimeType,
                    byteSize,
                    width,
                    height,
                    sha256);
        }
    }

    private static void validate(
            UUID id,
            UUID assetId,
            String variantName,
            String format,
            String objectKey,
            String mimeType,
            long byteSize,
            Integer width,
            Integer height,
            String sha256) {
        Objects.requireNonNull(id, "media variant id is required");
        Objects.requireNonNull(assetId, "media asset id is required");
        if (variantName == null
                || !NAME.matcher(variantName).matches()
                || objectKey == null
                || objectKey.isBlank()
                || objectKey.length() > 512
                || byteSize <= 0
                || sha256 == null
                || !SHA256.matcher(sha256).matches()) {
            throw invalid();
        }
        if ("PDF".equals(format)) {
            if (!"document".equals(variantName)
                    || !"application/pdf".equals(mimeType)
                    || width != null
                    || height != null
                    || !objectKey.equals(
                            MediaObjectKeys.originalKey(assetId, sha256, mimeType))) {
                throw invalid();
            }
            return;
        }
        if (!("JPEG".equals(format) || "PNG".equals(format))
                || (("JPEG".equals(format)) != ("image/jpeg".equals(mimeType)))
                || width == null
                || height == null
                || width <= 0
                || height <= 0
                || !variantName.equals("w" + width)
                || !objectKey.equals(
                        MediaObjectKeys.variantKey(assetId, variantName, sha256, mimeType))) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("media variant is invalid");
    }
}
