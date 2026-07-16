package xyz.yychainsaw.portfolio.media.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record MediaAssetDescriptor(
        UUID assetId,
        String status,
        String mimeType,
        long byteSize,
        String sha256,
        Map<String, MediaCopyDescriptor> copyByLocale,
        List<MediaVariantDescriptor> variants) {
    public MediaAssetDescriptor {
        copyByLocale = Map.copyOf(copyByLocale);
        variants = List.copyOf(variants);
    }
}
