package xyz.yychainsaw.portfolio.publicapi;

import java.util.Objects;
import java.util.UUID;

public record PublicMediaDto(
        UUID assetId,
        String variant,
        String src,
        String srcset,
        String alt,
        String caption,
        String credit,
        String sourceUrl,
        int width,
        int height) {
    public PublicMediaDto {
        Objects.requireNonNull(assetId, "assetId");
        Objects.requireNonNull(variant, "variant");
        Objects.requireNonNull(src, "src");
        Objects.requireNonNull(srcset, "srcset");
        Objects.requireNonNull(alt, "alt");
        caption = caption == null ? "" : caption;
        credit = credit == null ? "" : credit;
        sourceUrl = sourceUrl == null ? "" : sourceUrl;
    }
}
