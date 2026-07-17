package xyz.yychainsaw.portfolio.publishing.snapshot.v1;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record PublishedMediaV1(
        UUID assetId,
        String contentType,
        long contentLength,
        String sha256,
        Map<LocaleV1, MediaCopy> copy,
        List<Variant> variants) {
    public PublishedMediaV1 {
        copy = Map.copyOf(copy);
        variants = List.copyOf(variants);
    }

    public record MediaCopy(String alt, String caption, String credit, String sourceUrl) {}

    public record Variant(String name, int width, int height, long bytes, String sha256) {}
}
