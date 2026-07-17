package xyz.yychainsaw.portfolio.publishing.snapshot.v1;

import java.util.List;
import java.util.UUID;

public record SiteSnapshotV1(
        int schemaVersion,
        UUID siteId,
        SiteContentV1 content,
        List<PublishedMediaV1> media) {
    public SiteSnapshotV1 {
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("SiteSnapshotV1 requires schema 1");
        }
        media = List.copyOf(media);
    }
}
