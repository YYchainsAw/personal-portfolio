package xyz.yychainsaw.portfolio.content.persistence;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.content.api.ProjectWorkspaceDto;
import xyz.yychainsaw.portfolio.content.api.TaxonomyWorkspaceDto;
import xyz.yychainsaw.portfolio.content.persistence.mybatis.TaxonomyMapper;

@Repository
public class MyBatisTaxonomyRepository implements TaxonomyRepository {
    private final TaxonomyMapper mapper;
    private final Clock clock;

    MyBatisTaxonomyRepository(TaxonomyMapper mapper, Clock clock) {
        this.mapper = Objects.requireNonNull(mapper, "taxonomy mapper is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<TaxonomyWorkspaceDto> findTags() {
        return assemble(mapper.selectTags(), "tags");
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<TaxonomyWorkspaceDto> findSkills() {
        return assemble(mapper.selectSkills(), "skills");
    }

    @Override
    @Transactional
    public void replaceImportTags(List<ProjectWorkspaceDto.TaxonomyRef> tags) {
        Objects.requireNonNull(tags, "tags are required");
        List<ProjectWorkspaceDto.TaxonomyRef> supplied = tags.stream()
                .map(tag -> Objects.requireNonNull(tag, "tag is required"))
                .toList();
        if (supplied.stream().map(ProjectWorkspaceDto.TaxonomyRef::id).anyMatch(Objects::isNull)
                || supplied.stream().map(ProjectWorkspaceDto.TaxonomyRef::normalizedKey).anyMatch(Objects::isNull)
                || new HashSet<>(supplied.stream().map(ProjectWorkspaceDto.TaxonomyRef::id).toList()).size()
                        != supplied.size()
                || new HashSet<>(supplied.stream().map(ProjectWorkspaceDto.TaxonomyRef::normalizedKey).toList()).size()
                        != supplied.size()) {
            throw ContentPersistenceErrors.invalid(
                    "tags",
                    "tag identities and normalized keys must be non-null and unique");
        }
        List<ProjectWorkspaceDto.TaxonomyRef> ordered = supplied.stream()
                .sorted(java.util.Comparator.comparing(ProjectWorkspaceDto.TaxonomyRef::id))
                .toList();

        Instant updatedAt = clock.instant();
        try {
            for (ProjectWorkspaceDto.TaxonomyRef tag : ordered) {
                Map<String, Object> stored = mapper.selectTagForUpdate(tag.id());
                if (stored != null) {
                    if (!Objects.equals(stored.get("normalized_key"), tag.normalizedKey())) {
                        throw ContentPersistenceErrors.identityMismatch();
                    }
                    // Existing names are authoritative. Import payloads cannot rename taxonomy.
                    continue;
                }
                mapper.insertTag(tag.id(), tag.normalizedKey(), updatedAt);
                List<Map<String, Object>> names = translationRows(tag.id(), tag.names());
                if (!names.isEmpty()) {
                    mapper.insertTagTranslations(names);
                }
            }
        } catch (RuntimeException exception) {
            throw ContentPersistenceErrors.translateConstraint(exception);
        }
    }

    @Override
    @Transactional
    public void updateTag(UUID id, Map<LocaleCode, String> names, long expectedVersion) {
        updateTag(id, names, expectedVersion, clock.instant());
    }

    @Override
    @Transactional
    public void updateTag(
            UUID id,
            Map<LocaleCode, String> names,
            long expectedVersion,
            Instant updatedAt) {
        update(id, names, expectedVersion, updatedAt, true);
    }

    @Override
    @Transactional
    public void updateSkill(UUID id, Map<LocaleCode, String> names, long expectedVersion) {
        updateSkill(id, names, expectedVersion, clock.instant());
    }

    @Override
    @Transactional
    public void updateSkill(
            UUID id,
            Map<LocaleCode, String> names,
            long expectedVersion,
            Instant updatedAt) {
        update(id, names, expectedVersion, updatedAt, false);
    }

    private void update(
            UUID id,
            Map<LocaleCode, String> names,
            long expectedVersion,
            Instant updatedAt,
            boolean tag) {
        Objects.requireNonNull(id, "taxonomy id is required");
        Objects.requireNonNull(names, "taxonomy names are required");
        Objects.requireNonNull(updatedAt, "updatedAt is required");
        EnumMap<LocaleCode, String> validatedNames = new EnumMap<>(LocaleCode.class);
        names.forEach((locale, name) -> validatedNames.put(
                Objects.requireNonNull(locale, "taxonomy locale is required"),
                Objects.requireNonNull(name, "taxonomy name is required")));

        Map<String, Object> root = tag
                ? mapper.selectTagForUpdate(id)
                : mapper.selectSkillForUpdate(id);
        if (root == null) {
            throw ContentPersistenceErrors.taxonomyNotFound();
        }
        if (((Number) root.get("version")).longValue() != expectedVersion) {
            throw ContentPersistenceErrors.versionConflict();
        }

        List<UUID> linkedProjects = tag
                ? mapper.lockProjectsLinkedToTag(id)
                : mapper.lockProjectsLinkedToSkill(id);
        int changed = tag
                ? mapper.updateTag(id, expectedVersion, updatedAt)
                : mapper.updateSkill(id, expectedVersion, updatedAt);
        if (changed != 1) {
            throw ContentPersistenceErrors.versionConflict();
        }

        if (tag) {
            mapper.deleteTagTranslations(id);
        } else {
            mapper.deleteSkillTranslations(id);
        }
        List<Map<String, Object>> rows = translationRows(id, validatedNames);
        if (!rows.isEmpty()) {
            if (tag) {
                mapper.insertTagTranslations(rows);
            } else {
                mapper.insertSkillTranslations(rows);
            }
        }
        if (!linkedProjects.isEmpty()) {
            mapper.markProjectsDirty(linkedProjects, updatedAt);
        }
    }

    private List<TaxonomyWorkspaceDto> assemble(
            List<Map<String, Object>> rows,
            String field) {
        LinkedHashMap<UUID, TaxonomyAccumulator> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            UUID id = uuid(row.get("id"));
            TaxonomyAccumulator value = grouped.computeIfAbsent(
                    id,
                    ignored -> new TaxonomyAccumulator(
                            id,
                            Objects.toString(row.get("normalized_key")),
                            ((Number) row.get("version")).longValue()));
            Object localeValue = row.get("locale");
            if (localeValue == null) {
                continue;
            }
            LocaleCode locale;
            try {
                locale = LocaleCode.from(localeValue.toString());
            } catch (IllegalArgumentException exception) {
                throw ContentPersistenceErrors.corrupt(field + ".locale");
            }
            if (value.names.put(locale, Objects.toString(row.get("name"))) != null) {
                throw ContentPersistenceErrors.corrupt(field + ".names");
            }
        }
        return grouped.values().stream()
                .map(value -> new TaxonomyWorkspaceDto(
                        value.id,
                        value.normalizedKey,
                        value.version,
                        value.names))
                .toList();
    }

    private List<Map<String, Object>> translationRows(
            UUID id,
            Map<LocaleCode, String> names) {
        List<Map<String, Object>> rows = new ArrayList<>();
        names.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> rows.add(Map.of(
                        "id", id,
                        "locale", entry.getKey().value(),
                        "name", entry.getValue())));
        return rows;
    }

    private UUID uuid(Object value) {
        return value instanceof UUID id ? id : UUID.fromString(value.toString());
    }

    private static final class TaxonomyAccumulator {
        private final UUID id;
        private final String normalizedKey;
        private final long version;
        private final EnumMap<LocaleCode, String> names = new EnumMap<>(LocaleCode.class);

        private TaxonomyAccumulator(UUID id, String normalizedKey, long version) {
            this.id = id;
            this.normalizedKey = normalizedKey;
            this.version = version;
        }
    }
}
