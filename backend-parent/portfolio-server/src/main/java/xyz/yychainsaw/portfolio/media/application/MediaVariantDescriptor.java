package xyz.yychainsaw.portfolio.media.application;

import java.util.UUID;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;

public record MediaVariantDescriptor(
        UUID assetId,
        String variantName,
        String status,
        StorageProvider provider,
        String bucket,
        String region,
        String objectKey,
        String mimeType,
        long byteSize,
        String sha256,
        int width,
        int height) { }
