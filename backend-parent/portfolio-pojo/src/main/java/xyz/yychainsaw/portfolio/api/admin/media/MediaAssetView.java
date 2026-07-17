package xyz.yychainsaw.portfolio.api.admin.media;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record MediaAssetView(
        UUID id,
        String originalFilename,
        String mimeType,
        long byteSize,
        Integer width,
        Integer height,
        String sha256,
        String status,
        long version,
        Instant createdAt,
        Instant updatedAt,
        List<MediaTranslationView> translations,
        List<MediaVariantView> variants) {
    private static final Set<String> MIME_TYPES =
            Set.of("image/jpeg", "image/png", "application/pdf");
    private static final Set<String> STATUSES = Set.of(
            "PROCESSING", "READY", "FAILED", "ARCHIVED", "PENDING_DELETE");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public MediaAssetView {
        Objects.requireNonNull(id, "media asset id is required");
        originalFilename = requireText(originalFilename, "media filename is required");
        if (originalFilename.codePointCount(0, originalFilename.length()) > 255) {
            throw new IllegalArgumentException("media filename is invalid");
        }
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
        if (!STATUSES.contains(status)) {
            throw new IllegalArgumentException("media status is invalid");
        }
        if (version < 0) {
            throw new IllegalArgumentException("media version is invalid");
        }
        Objects.requireNonNull(createdAt, "media created timestamp is required");
        Objects.requireNonNull(updatedAt, "media updated timestamp is required");
        translations = List.copyOf(Objects.requireNonNull(
                translations, "media translations are required"));
        variants = List.copyOf(Objects.requireNonNull(
                variants, "media variants are required"));
    }

    public MediaAssetView(
            UUID id,
            String originalFilename,
            String mimeType,
            long byteSize,
            Integer width,
            Integer height,
            String sha256,
            String status,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        this(
                id,
                originalFilename,
                mimeType,
                byteSize,
                width,
                height,
                sha256,
                status,
                version,
                createdAt,
                updatedAt,
                List.of(),
                List.of());
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            throw new IllegalArgumentException(message);
        }
        return value;
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
    }
}
