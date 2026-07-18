package xyz.yychainsaw.portfolio.publishing.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.media.application.MediaAssetDescriptor;
import xyz.yychainsaw.portfolio.media.application.MediaQueryAccessGuard;
import xyz.yychainsaw.portfolio.media.application.MediaQueryService;
import xyz.yychainsaw.portfolio.media.application.MediaVariantDescriptor;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.PublishedMediaV1;

/**
 * Acquires the complete, deterministic READY-media share-lock plan used by a
 * restore transaction. All asset rows are locked before any variant row.
 */
@Component
@ConditionalOnWebApplication(type = Type.SERVLET)
public final class PublishingMediaLockCoordinator {
    private static final Comparator<UUID> UUID_ORDER =
            Comparator.comparing(UUID::toString);
    private static final Comparator<MediaQueryAccessGuard.VariantKey> VARIANT_ORDER =
            Comparator.comparing(
                            (MediaQueryAccessGuard.VariantKey key) ->
                                    key.assetId().toString())
                    .thenComparing(MediaQueryAccessGuard.VariantKey::variantName);

    private final MediaQueryService media;

    public PublishingMediaLockCoordinator(MediaQueryService media) {
        this.media = Objects.requireNonNull(media, "media query service is required");
    }

    public LockedMediaPlan lockRestoreMedia(
            Set<UUID> referencedAssetIds,
            List<PublishedMediaV1> snapshotMedia) {
        Set<UUID> requested = immutableAssetSet(referencedAssetIds);
        List<PublishedMediaV1> historical = List.copyOf(Objects.requireNonNull(
                snapshotMedia, "snapshot media is required"));
        Set<UUID> snapshotAssetIds = snapshotAssetIds(historical);
        if (!requested.equals(snapshotAssetIds)) {
            throw new IllegalStateException(
                    "restored workspace and revision media assets do not match");
        }

        List<UUID> orderedAssets = new ArrayList<>(requested);
        orderedAssets.sort(UUID_ORDER);
        TreeSet<MediaQueryAccessGuard.VariantKey> variants =
                new TreeSet<>(VARIANT_ORDER);

        for (UUID assetId : orderedAssets) {
            MediaAssetDescriptor descriptor = requireReadyAsset(assetId);
            List<MediaVariantDescriptor> descriptorVariants = descriptor.variants();
            if (descriptorVariants == null) {
                throw mediaNotReady(assetId);
            }
            for (MediaVariantDescriptor variant : descriptorVariants) {
                if (variant == null
                        || !assetId.equals(variant.assetId())
                        || variant.variantName() == null
                        || variant.variantName().isBlank()) {
                    throw mediaNotReady(assetId);
                }
                MediaQueryAccessGuard.VariantKey key =
                        new MediaQueryAccessGuard.VariantKey(
                                assetId, variant.variantName());
                if (!variants.add(key)) {
                    throw mediaNotReady(assetId);
                }
            }
        }

        for (MediaQueryAccessGuard.VariantKey key : variants) {
            requireReadyVariant(key.assetId(), key.variantName());
        }

        TreeSet<MediaQueryAccessGuard.VariantKey> historicalVariants =
                historicalVariantKeys(historical);
        for (MediaQueryAccessGuard.VariantKey key : historicalVariants) {
            if (!variants.contains(key)) {
                throw mediaNotReady(key.assetId());
            }
        }
        return new LockedMediaPlan(requested, variants);
    }

    private MediaAssetDescriptor requireReadyAsset(UUID assetId) {
        MediaAssetDescriptor descriptor;
        try {
            descriptor = media.requireReadyAsset(assetId);
        } catch (DomainException failure) {
            throw mediaNotReady(assetId);
        }
        if (descriptor == null
                || !assetId.equals(descriptor.assetId())
                || !"READY".equals(descriptor.status())) {
            throw mediaNotReady(assetId);
        }
        return descriptor;
    }

    private void requireReadyVariant(UUID assetId, String variantName) {
        MediaVariantDescriptor descriptor;
        try {
            descriptor = media.requireReadyVariant(assetId, variantName);
        } catch (DomainException failure) {
            throw mediaNotReady(assetId);
        }
        if (descriptor == null
                || !assetId.equals(descriptor.assetId())
                || !variantName.equals(descriptor.variantName())
                || !"READY".equals(descriptor.status())) {
            throw mediaNotReady(assetId);
        }
    }

    private static Set<UUID> immutableAssetSet(Set<UUID> assetIds) {
        Objects.requireNonNull(assetIds, "referenced asset ids are required");
        TreeSet<UUID> copied = new TreeSet<>(UUID_ORDER);
        for (UUID assetId : assetIds) {
            if (assetId == null) {
                throw new IllegalStateException(
                        "restored workspace contains an invalid media asset id");
            }
            copied.add(assetId);
        }
        return Set.copyOf(copied);
    }

    private static Set<UUID> snapshotAssetIds(List<PublishedMediaV1> snapshotMedia) {
        Set<UUID> assetIds = new HashSet<>();
        for (PublishedMediaV1 published : snapshotMedia) {
            if (published == null
                    || published.assetId() == null
                    || !assetIds.add(published.assetId())) {
                throw new IllegalStateException(
                        "revision contains invalid or duplicate media assets");
            }
        }
        return Set.copyOf(assetIds);
    }

    private static TreeSet<MediaQueryAccessGuard.VariantKey> historicalVariantKeys(
            List<PublishedMediaV1> snapshotMedia) {
        TreeSet<MediaQueryAccessGuard.VariantKey> keys =
                new TreeSet<>(VARIANT_ORDER);
        for (PublishedMediaV1 published : snapshotMedia) {
            for (PublishedMediaV1.Variant variant : published.variants()) {
                if (variant == null
                        || variant.name() == null
                        || variant.name().isBlank()) {
                    throw mediaNotReady(published.assetId());
                }
                if (!keys.add(new MediaQueryAccessGuard.VariantKey(
                        published.assetId(), variant.name()))) {
                    throw mediaNotReady(published.assetId());
                }
            }
        }
        return keys;
    }

    private static DomainException mediaNotReady(UUID assetId) {
        return new DomainException(
                "MEDIA_NOT_READY",
                HttpStatus.UNPROCESSABLE_ENTITY,
                Map.of(
                        "media[" + assetId + "]",
                        "a READY media asset and variant are required"));
    }

    public record LockedMediaPlan(
            Set<UUID> assetIds,
            Set<MediaQueryAccessGuard.VariantKey> variants) {
        public LockedMediaPlan {
            assetIds = Set.copyOf(Objects.requireNonNull(
                    assetIds, "locked asset ids are required"));
            variants = Set.copyOf(Objects.requireNonNull(
                    variants, "locked variants are required"));
            for (MediaQueryAccessGuard.VariantKey variant : variants) {
                if (!assetIds.contains(variant.assetId())) {
                    throw new IllegalArgumentException(
                            "locked variant asset must belong to the asset plan");
                }
            }
        }
    }
}
