package xyz.yychainsaw.portfolio.media.application;

import java.util.UUID;

public interface MediaQueryService {
    /**
     * Resolves a READY asset while taking a PostgreSQL row share lock. Callers that
     * create a durable reference must invoke this inside the same transaction as
     * the reference insert so cleanup cannot transition the asset concurrently.
     */
    MediaAssetDescriptor requireReadyAsset(UUID assetId);

    /**
     * Resolves a READY variant under the same transaction-scoped row share-lock
     * protocol as {@link #requireReadyAsset(UUID)}.
     */
    MediaVariantDescriptor requireReadyVariant(UUID assetId, String variantName);
}
