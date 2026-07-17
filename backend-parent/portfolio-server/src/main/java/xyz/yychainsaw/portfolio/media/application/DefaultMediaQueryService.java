package xyz.yychainsaw.portfolio.media.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.media.domain.MediaStatus;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRecord;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRepository;
import xyz.yychainsaw.portfolio.media.persistence.MediaTranslationRecord;
import xyz.yychainsaw.portfolio.media.persistence.MediaTranslationRepository;
import xyz.yychainsaw.portfolio.media.persistence.MediaVariantRecord;
import xyz.yychainsaw.portfolio.media.persistence.MediaVariantRepository;

@Service
public final class DefaultMediaQueryService implements MediaQueryService {
    private static final Comparator<MediaVariantDescriptor> VARIANT_ORDER =
            Comparator.comparingInt(MediaVariantDescriptor::width)
                    .thenComparing(MediaVariantDescriptor::variantName);

    private final MediaAssetRepository assets;
    private final MediaVariantRepository variants;
    private final MediaTranslationRepository translations;
    private final MediaQueryAccessGuard accessGuard;

    public DefaultMediaQueryService(
            MediaAssetRepository assets,
            MediaVariantRepository variants,
            MediaTranslationRepository translations) {
        this(
                assets,
                variants,
                translations,
                MediaQueryAccessGuard.unrestricted());
    }

    @Autowired
    public DefaultMediaQueryService(
            MediaAssetRepository assets,
            MediaVariantRepository variants,
            MediaTranslationRepository translations,
            MediaQueryAccessGuard accessGuard) {
        this.assets = Objects.requireNonNull(assets, "media repository is required");
        this.variants = Objects.requireNonNull(
                variants, "media variant repository is required");
        this.translations = Objects.requireNonNull(
                translations, "media translation repository is required");
        this.accessGuard = Objects.requireNonNull(
                accessGuard, "media query access guard is required");
    }

    @Override
    public MediaAssetDescriptor requireReadyAsset(UUID assetId) {
        accessGuard.checkAsset(assetId);
        MediaAssetRecord asset = requireReadyRecord(assetId);
        Map<String, MediaCopyDescriptor> copyByLocale = new LinkedHashMap<>();
        for (MediaTranslationRecord translation : translations.findByAssetId(assetId)) {
            copyByLocale.put(
                    translation.locale(),
                    new MediaCopyDescriptor(
                            translation.altText(),
                            translation.caption(),
                            translation.credit(),
                            translation.sourceUrl()));
        }

        List<MediaVariantDescriptor> readyVariants = new ArrayList<>();
        for (MediaVariantRecord variant : variants.findByAssetId(assetId)) {
            if ("READY".equals(variant.status())) {
                readyVariants.add(toDescriptor(asset, variant));
            }
        }
        readyVariants.sort(VARIANT_ORDER);
        return new MediaAssetDescriptor(
                asset.id(),
                asset.status().name(),
                asset.mimeType(),
                asset.byteSize(),
                asset.sha256(),
                copyByLocale,
                readyVariants);
    }

    @Override
    public MediaVariantDescriptor requireReadyVariant(
            UUID assetId, String variantName) {
        accessGuard.checkVariant(assetId, variantName);
        MediaAssetRecord asset = requireReadyRecord(assetId);
        if (variantName == null || variantName.isBlank()) {
            throw notFound();
        }
        MediaVariantRecord variant = variants.findByAssetAndName(assetId, variantName)
                .orElseThrow(DefaultMediaQueryService::notFound);
        if (!"READY".equals(variant.status())) {
            throw notReady();
        }
        return toDescriptor(asset, variant);
    }

    private MediaAssetRecord requireReadyRecord(UUID assetId) {
        if (assetId == null) {
            throw notFound();
        }
        MediaAssetRecord asset = assets.findByIdForShare(assetId)
                .orElseThrow(DefaultMediaQueryService::notFound);
        if (asset.status() != MediaStatus.READY) {
            throw notReady();
        }
        return asset;
    }

    private static MediaVariantDescriptor toDescriptor(
            MediaAssetRecord asset, MediaVariantRecord variant) {
        int width = variant.width() == null ? 0 : variant.width();
        int height = variant.height() == null ? 0 : variant.height();
        return new MediaVariantDescriptor(
                asset.id(),
                variant.variantName(),
                variant.status(),
                asset.provider(),
                asset.bucket(),
                asset.region(),
                variant.objectKey(),
                variant.mimeType(),
                variant.byteSize(),
                variant.sha256(),
                width,
                height);
    }

    private static DomainException notFound() {
        return new DomainException("MEDIA_NOT_FOUND", HttpStatus.NOT_FOUND, Map.of());
    }

    private static DomainException notReady() {
        return new DomainException(
                "MEDIA_NOT_READY", HttpStatus.UNPROCESSABLE_ENTITY, Map.of());
    }
}
