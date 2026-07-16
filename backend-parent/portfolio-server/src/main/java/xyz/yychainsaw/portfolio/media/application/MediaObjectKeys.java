package xyz.yychainsaw.portfolio.media.application;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public final class MediaObjectKeys {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern VARIANT_NAME = Pattern.compile("w[1-9][0-9]{0,9}");
    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "application/pdf", "pdf");

    private MediaObjectKeys() {}

    public static String stagingKey(UUID assetId, String sha256, String mimeType) {
        return key("staging", assetId, sha256, mimeType);
    }

    public static String originalKey(UUID assetId, String sha256, String mimeType) {
        return key("originals", assetId, sha256, mimeType);
    }

    public static String variantKey(
            UUID assetId, String variantName, String sha256, String mimeType) {
        Objects.requireNonNull(assetId, "media asset id is required");
        if (variantName == null || !VARIANT_NAME.matcher(variantName).matches()) {
            throw new IllegalArgumentException("media variant name is invalid");
        }
        if (sha256 == null || !SHA256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("media SHA-256 is invalid");
        }
        String extension = EXTENSIONS.get(mimeType);
        if (!("jpg".equals(extension) || "png".equals(extension))) {
            throw new IllegalArgumentException("media MIME type is invalid");
        }
        return "variants/" + assetId + '/' + variantName + '/' + sha256 + '.' + extension;
    }

    private static String key(
            String prefix, UUID assetId, String sha256, String mimeType) {
        Objects.requireNonNull(assetId, "media asset id is required");
        if (sha256 == null || !SHA256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("media SHA-256 is invalid");
        }
        String extension = EXTENSIONS.get(mimeType);
        if (extension == null) {
            throw new IllegalArgumentException("media MIME type is invalid");
        }
        return prefix + '/' + assetId + '/' + sha256 + '.' + extension;
    }
}
