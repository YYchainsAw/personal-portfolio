package xyz.yychainsaw.portfolio.publishing.snapshot.v1;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ProjectSnapshotV1(
        int schemaVersion,
        UUID projectId,
        String externalKey,
        String slug,
        String number,
        int sortOrder,
        boolean featured,
        Map<LocaleV1, ProjectCopyV1> translations,
        List<TaxonomyRefV1> tags,
        List<TaxonomyRefV1> skills,
        List<ProjectMediaV1> projectMedia,
        List<PublishedBlockV1> blocks,
        List<PublishedMediaV1> media) {
    public ProjectSnapshotV1 {
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("ProjectSnapshotV1 requires schema 1");
        }
        translations = Map.copyOf(translations);
        tags = List.copyOf(tags);
        skills = List.copyOf(skills);
        projectMedia = List.copyOf(projectMedia);
        blocks = List.copyOf(blocks);
        media = List.copyOf(media);
    }

    public record ProjectCopyV1(
            String status,
            String eyebrow,
            String title,
            String summary,
            String seoTitle,
            String seoDescription) {}

    public record TaxonomyRefV1(
            UUID id,
            String normalizedKey,
            int sortOrder,
            Map<LocaleV1, String> names) {
        public TaxonomyRefV1 {
            names = Map.copyOf(names);
        }
    }

    public record ProjectMediaV1(
            UUID assetId,
            String usage,
            int sortOrder,
            String layout,
            String objectPosition,
            String credit,
            URI sourceUrl) {}
}
