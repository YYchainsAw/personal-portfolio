package xyz.yychainsaw.portfolio.content.api;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record TaxonomyWorkspaceDto(
        UUID id,
        String normalizedKey,
        long version,
        Map<LocaleCode, String> names) {
    public TaxonomyWorkspaceDto {
        names = Map.copyOf(Objects.requireNonNull(names, "names"));
    }
}
