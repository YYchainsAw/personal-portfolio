package xyz.yychainsaw.portfolio.media.application;

import java.util.UUID;

public interface MediaQueryService {
    MediaAssetDescriptor requireReadyAsset(UUID assetId);

    MediaVariantDescriptor requireReadyVariant(UUID assetId, String variantName);
}
