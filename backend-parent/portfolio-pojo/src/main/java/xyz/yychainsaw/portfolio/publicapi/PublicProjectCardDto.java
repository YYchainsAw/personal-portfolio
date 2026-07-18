package xyz.yychainsaw.portfolio.publicapi;

import java.util.List;
import java.util.UUID;

public record PublicProjectCardDto(
        UUID projectId,
        String slug,
        String number,
        int sortOrder,
        boolean featured,
        String status,
        String eyebrow,
        String title,
        String summary,
        List<String> tags,
        PublicMediaDto cover) {
    public PublicProjectCardDto {
        tags = List.copyOf(tags);
    }
}
