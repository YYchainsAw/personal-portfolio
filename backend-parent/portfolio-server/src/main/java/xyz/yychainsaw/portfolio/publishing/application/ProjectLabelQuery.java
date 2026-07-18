package xyz.yychainsaw.portfolio.publishing.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;

public interface ProjectLabelQuery {
    Optional<String> findProjectTitle(UUID projectId, LocaleCode locale);

    default Map<UUID, String> findProjectTitles(
            Set<UUID> projectIds, LocaleCode locale) {
        Objects.requireNonNull(projectIds, "projectIds");
        Objects.requireNonNull(locale, "locale");
        LinkedHashMap<UUID, String> labels = new LinkedHashMap<>();
        for (UUID projectId : projectIds) {
            findProjectTitle(
                            Objects.requireNonNull(projectId, "projectId"),
                            locale)
                    .ifPresent(title -> labels.put(projectId, title));
        }
        return Map.copyOf(labels);
    }
}
