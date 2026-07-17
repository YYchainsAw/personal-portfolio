package xyz.yychainsaw.portfolio.content.api;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ProjectWorkspaceDto(
        UUID id,
        String externalKey,
        String slug,
        String number,
        int sortOrder,
        boolean featured,
        boolean visible,
        boolean publicationDirty,
        long version,
        Map<LocaleCode, ProjectCopy> translations,
        List<TaxonomyRef> tags,
        List<TaxonomyRef> skills,
        List<ProjectMedia> media,
        List<ContentBlockDto> blocks
) {
    public ProjectWorkspaceDto {
        translations = Map.copyOf(translations);
        tags = List.copyOf(tags);
        skills = List.copyOf(skills);
        media = List.copyOf(media);
        blocks = List.copyOf(blocks);
    }

    public record ProjectCopy(
            String status,
            String eyebrow,
            String title,
            String summary,
            String seoTitle,
            String seoDescription) {}

    public record TaxonomyRef(
            UUID id,
            String normalizedKey,
            int sortOrder,
            Map<LocaleCode, String> names) {
        public TaxonomyRef {
            names = Map.copyOf(names);
        }
    }

    public record ProjectMedia(
            UUID assetId,
            String usage,
            int sortOrder,
            String layout,
            String objectPosition,
            String credit,
            URI sourceUrl) {}
}
