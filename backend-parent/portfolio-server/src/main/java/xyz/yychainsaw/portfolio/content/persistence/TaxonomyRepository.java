package xyz.yychainsaw.portfolio.content.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.content.api.ProjectWorkspaceDto;
import xyz.yychainsaw.portfolio.content.api.TaxonomyWorkspaceDto;

public interface TaxonomyRepository {
    List<TaxonomyWorkspaceDto> findTags();

    List<TaxonomyWorkspaceDto> findSkills();

    void replaceImportTags(List<ProjectWorkspaceDto.TaxonomyRef> tags);

    void updateTag(UUID id, Map<LocaleCode, String> names, long expectedVersion);

    void updateSkill(UUID id, Map<LocaleCode, String> names, long expectedVersion);

    default void updateTag(
            UUID id,
            Map<LocaleCode, String> names,
            long expectedVersion,
            Instant updatedAt) {
        updateTag(id, names, expectedVersion);
    }

    default void updateSkill(
            UUID id,
            Map<LocaleCode, String> names,
            long expectedVersion,
            Instant updatedAt) {
        updateSkill(id, names, expectedVersion);
    }
}
