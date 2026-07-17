package xyz.yychainsaw.portfolio.media.application;

import java.util.List;
import java.util.UUID;

public record ImportedMedia(
        UUID assetId,
        String originalSha256,
        List<String> readyVariants) {
    public ImportedMedia {
        readyVariants = List.copyOf(readyVariants);
    }
}
