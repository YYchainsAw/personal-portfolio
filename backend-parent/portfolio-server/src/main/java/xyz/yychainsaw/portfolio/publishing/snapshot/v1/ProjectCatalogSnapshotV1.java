package xyz.yychainsaw.portfolio.publishing.snapshot.v1;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ProjectCatalogSnapshotV1(int schemaVersion, List<Card> projects) {
    public ProjectCatalogSnapshotV1 {
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("ProjectCatalogSnapshotV1 requires schema 1");
        }
        projects = List.copyOf(projects);
    }

    public record Card(
            UUID projectId,
            String slug,
            String number,
            int sortOrder,
            boolean featured,
            Map<LocaleV1, CardCopy> copy,
            PublishedMediaV1 cover) {
        public Card {
            copy = Map.copyOf(copy);
        }
    }

    public record CardCopy(
            String status,
            String eyebrow,
            String title,
            String summary,
            List<String> tags) {
        public CardCopy {
            tags = List.copyOf(tags);
        }
    }
}
