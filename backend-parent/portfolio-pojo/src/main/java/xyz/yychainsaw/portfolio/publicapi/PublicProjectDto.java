package xyz.yychainsaw.portfolio.publicapi;

import java.util.List;
import java.util.UUID;

public record PublicProjectDto(
        UUID projectId,
        String slug,
        String number,
        boolean featured,
        String status,
        String eyebrow,
        String title,
        String summary,
        String seoTitle,
        String seoDescription,
        List<String> tags,
        List<String> skills,
        List<PublicMediaDto> media,
        List<PublicBlockDto> blocks) {
    public PublicProjectDto {
        tags = List.copyOf(tags);
        skills = List.copyOf(skills);
        media = List.copyOf(media);
        blocks = List.copyOf(blocks);
    }
}
