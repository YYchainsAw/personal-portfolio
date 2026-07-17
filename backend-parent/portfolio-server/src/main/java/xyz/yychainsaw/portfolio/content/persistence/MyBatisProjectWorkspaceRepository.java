package xyz.yychainsaw.portfolio.content.persistence;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import xyz.yychainsaw.portfolio.content.api.ContentBlockDto;
import xyz.yychainsaw.portfolio.content.api.ProjectWorkspaceDto;
import xyz.yychainsaw.portfolio.content.persistence.mybatis.ProjectWorkspaceMapper;

@Repository
public class MyBatisProjectWorkspaceRepository implements ProjectWorkspaceRepository {
    private final ProjectWorkspaceMapper mapper;
    private final ProjectWorkspaceAssembler assembler;
    private final Clock clock;

    MyBatisProjectWorkspaceRepository(
            ProjectWorkspaceMapper mapper,
            ProjectWorkspaceAssembler assembler,
            Clock clock) {
        this.mapper = Objects.requireNonNull(mapper, "project workspace mapper is required");
        this.assembler = Objects.requireNonNull(assembler, "project workspace assembler is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<ProjectWorkspaceDto> find(UUID projectId) {
        if (projectId == null) {
            return Optional.empty();
        }
        Map<String, Object> root = mapper.selectProject(projectId);
        return root == null ? Optional.empty() : Optional.of(assembler.load(mapper, root));
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ProjectWorkspaceDto require(UUID projectId) {
        if (projectId == null) {
            throw ContentPersistenceErrors.projectNotFound();
        }
        Map<String, Object> root = mapper.selectProject(projectId);
        if (root == null) {
            throw ContentPersistenceErrors.projectNotFound();
        }
        return assembler.load(mapper, root);
    }

    @Override
    @Transactional
    public ProjectWorkspaceDto requireForUpdate(UUID projectId) {
        if (projectId == null) {
            throw ContentPersistenceErrors.projectNotFound();
        }
        Map<String, Object> root = mapper.selectProjectForUpdate(projectId);
        if (root == null) {
            throw ContentPersistenceErrors.projectNotFound();
        }
        return assembler.load(mapper, root);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<ProjectWorkspaceDto> findAll() {
        return mapper.selectAllProjects().stream()
                .map(root -> assembler.load(mapper, root))
                .toList();
    }

    @Override
    @Transactional
    public void insert(ProjectWorkspaceDto workspace) {
        insertAt(workspace, clock.instant());
    }

    @Override
    @Transactional
    public void insert(ProjectWorkspaceDto workspace, Instant updatedAt) {
        insertAt(workspace, updatedAt);
    }

    @Override
    @Transactional
    public void replace(ProjectWorkspaceDto workspace, long expectedVersion) {
        replaceAt(workspace, expectedVersion, clock.instant());
    }

    @Override
    @Transactional
    public void replace(ProjectWorkspaceDto workspace, long expectedVersion, Instant updatedAt) {
        replaceAt(workspace, expectedVersion, updatedAt);
    }

    @Override
    @Transactional
    public void markPublicationDirty(Collection<UUID> projectIds) {
        Objects.requireNonNull(projectIds, "project ids are required");
        List<UUID> ids = projectIds.stream()
                .map(id -> Objects.requireNonNull(id, "project id is required"))
                .distinct()
                .sorted()
                .toList();
        if (!ids.isEmpty()) {
            mapper.markPublicationDirty(ids, clock.instant());
        }
    }

    @Override
    @Transactional
    public void markPublished(UUID projectId, long expectedVersion) {
        markPublished(projectId, expectedVersion, clock.instant());
    }

    @Override
    @Transactional
    public void markPublished(UUID projectId, long expectedVersion, Instant updatedAt) {
        Objects.requireNonNull(projectId, "project id is required");
        Objects.requireNonNull(updatedAt, "updatedAt is required");
        if (mapper.markPublished(projectId, expectedVersion, updatedAt) == 1) {
            return;
        }
        if (mapper.selectProject(projectId) == null) {
            throw ContentPersistenceErrors.projectNotFound();
        }
        throw ContentPersistenceErrors.versionConflict();
    }

    @Override
    @Transactional
    public void updateCatalogOrder(List<UUID> projectIdsInOrder) {
        updateCatalogOrder(projectIdsInOrder, clock.instant());
    }

    @Override
    @Transactional
    public void updateCatalogOrder(List<UUID> projectIdsInOrder, Instant updatedAt) {
        Objects.requireNonNull(projectIdsInOrder, "catalog order is required");
        Objects.requireNonNull(updatedAt, "updatedAt is required");
        if (projectIdsInOrder.isEmpty()) {
            return;
        }
        List<UUID> requested = List.copyOf(projectIdsInOrder);
        if (requested.stream().anyMatch(Objects::isNull)
                || new HashSet<>(requested).size() != requested.size()) {
            throw ContentPersistenceErrors.invalid(
                    "projectIdsInOrder",
                    "project identities must be non-null and unique");
        }

        List<Map<String, Object>> lockedRows = mapper.lockCatalog();
        LinkedHashMap<UUID, Integer> current = new LinkedHashMap<>();
        for (Map<String, Object> row : lockedRows) {
            current.put(uuid(row.get("id")), ((Number) row.get("sort_order")).intValue());
        }
        if (!current.keySet().containsAll(requested)) {
            throw ContentPersistenceErrors.projectNotFound();
        }

        List<UUID> finalOrder = new ArrayList<>(requested);
        current.entrySet().stream()
                .sorted(Map.Entry.<UUID, Integer>comparingByValue()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey)
                .filter(id -> !requested.contains(id))
                .forEach(finalOrder::add);
        if (finalOrder.isEmpty()) {
            return;
        }

        long maxSortOrder = current.values().stream().mapToLong(Integer::longValue).max().orElse(-1L);
        long temporaryBase = maxSortOrder + 1L;
        long temporaryLast = temporaryBase + finalOrder.size() - 1L;
        if (temporaryBase < 0L || temporaryLast > Integer.MAX_VALUE) {
            throw ContentPersistenceErrors.catalogOrderConflict();
        }

        try {
            mapper.moveCatalogToTemporary((int) temporaryBase);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int index = 0; index < finalOrder.size(); index++) {
                rows.add(Map.of("id", finalOrder.get(index), "sortOrder", index));
            }
            mapper.assignCatalogOrder(rows, updatedAt);
        } catch (RuntimeException exception) {
            throw ContentPersistenceErrors.translateConstraint(exception);
        }
    }

    private void insertAt(ProjectWorkspaceDto workspace, Instant updatedAt) {
        requireWorkspace(workspace, updatedAt);
        if (workspace.version() != 0L) {
            throw ContentPersistenceErrors.invalid("version", "new projects must start at version 0");
        }
        if (!workspace.publicationDirty()) {
            throw ContentPersistenceErrors.invalid(
                    "publicationDirty",
                    "new projects must start with unpublished changes");
        }
        assembler.validate(workspace);
        validateTaxonomy(workspace);
        validateNestedOwnership(workspace);
        try {
            mapper.insertRoot(assembler.rootRow(workspace), updatedAt);
            assembler.replaceChildren(mapper, workspace);
        } catch (RuntimeException exception) {
            throw ContentPersistenceErrors.translateConstraint(exception);
        }
    }

    private void replaceAt(
            ProjectWorkspaceDto workspace,
            long expectedVersion,
            Instant updatedAt) {
        requireWorkspace(workspace, updatedAt);
        assembler.validate(workspace);
        Map<String, Object> locked = mapper.selectProjectForUpdate(workspace.id());
        if (locked == null) {
            throw ContentPersistenceErrors.projectNotFound();
        }
        String storedExternalKey = Objects.toString(locked.get("external_key"), null);
        if (!Objects.equals(storedExternalKey, workspace.externalKey())) {
            throw ContentPersistenceErrors.identityMismatch();
        }
        validateTaxonomy(workspace);
        validateNestedOwnership(workspace);
        try {
            int changed = mapper.updateRoot(assembler.rootRow(workspace), expectedVersion, updatedAt);
            if (changed != 1) {
                throw ContentPersistenceErrors.versionConflict();
            }
            assembler.replaceChildren(mapper, workspace);
        } catch (RuntimeException exception) {
            throw ContentPersistenceErrors.translateConstraint(exception);
        }
    }

    private void requireWorkspace(ProjectWorkspaceDto workspace, Instant updatedAt) {
        Objects.requireNonNull(workspace, "project workspace is required");
        Objects.requireNonNull(updatedAt, "updatedAt is required");
        if (workspace.id() == null) {
            throw ContentPersistenceErrors.invalid("id", "project identity is required");
        }
    }

    private void validateTaxonomy(ProjectWorkspaceDto workspace) {
        validateTaxonomyIds(
                workspace.tags().stream().map(ProjectWorkspaceDto.TaxonomyRef::id).toList(),
                "tags",
                true);
        validateTaxonomyIds(
                workspace.skills().stream().map(ProjectWorkspaceDto.TaxonomyRef::id).toList(),
                "skills",
                false);
    }

    private void validateTaxonomyIds(List<UUID> ids, String field, boolean tags) {
        if (ids.stream().anyMatch(Objects::isNull) || new HashSet<>(ids).size() != ids.size()) {
            throw ContentPersistenceErrors.invalid(
                    field + ".id",
                    "taxonomy identities must be non-null and unique");
        }
        if (ids.isEmpty()) {
            return;
        }
        Set<UUID> requested = Set.copyOf(ids);
        Set<UUID> existing = Set.copyOf(tags
                ? mapper.selectExistingTagIds(requested)
                : mapper.selectExistingSkillIds(requested));
        if (!existing.equals(requested)) {
            throw ContentPersistenceErrors.invalid(
                    field + ".id",
                    "referenced taxonomy entry does not exist");
        }
    }

    private void validateNestedOwnership(ProjectWorkspaceDto workspace) {
        List<UUID> blockIds = workspace.blocks().stream()
                .map(ContentBlockDto::id)
                .toList();
        if (!blockIds.isEmpty()) {
            requireOwnedByProject(
                    mapper.selectBlockOwners(blockIds),
                    workspace.id(),
                    "blocks.id");
        }
        List<UUID> metricIds = workspace.blocks().stream()
                .map(ContentBlockDto::payload)
                .filter(ContentBlockDto.MetricsPayload.class::isInstance)
                .map(ContentBlockDto.MetricsPayload.class::cast)
                .flatMap(payload -> payload.metrics().stream())
                .map(ContentBlockDto.Metric::id)
                .toList();
        if (!metricIds.isEmpty()) {
            requireOwnedByProject(
                    mapper.selectMetricOwners(metricIds),
                    workspace.id(),
                    "blocks.metrics.id");
        }
    }

    private void requireOwnedByProject(
            List<Map<String, Object>> owners,
            UUID projectId,
            String field) {
        boolean foreignIdentity = owners.stream()
                .map(row -> uuid(row.get("project_id")))
                .anyMatch(ownerId -> !ownerId.equals(projectId));
        if (foreignIdentity) {
            throw ContentPersistenceErrors.invalid(
                    field,
                    "nested identity is already owned by another project");
        }
    }

    private UUID uuid(Object value) {
        return value instanceof UUID id ? id : UUID.fromString(value.toString());
    }
}
