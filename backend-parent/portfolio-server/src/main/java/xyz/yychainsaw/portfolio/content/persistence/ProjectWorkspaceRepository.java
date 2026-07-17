package xyz.yychainsaw.portfolio.content.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import xyz.yychainsaw.portfolio.content.api.ProjectWorkspaceDto;

public interface ProjectWorkspaceRepository {
    Optional<ProjectWorkspaceDto> find(UUID projectId);

    ProjectWorkspaceDto require(UUID projectId);

    ProjectWorkspaceDto requireForUpdate(UUID projectId);

    List<ProjectWorkspaceDto> findAll();

    void insert(ProjectWorkspaceDto workspace);

    void replace(ProjectWorkspaceDto workspace, long expectedVersion);

    void markPublicationDirty(Collection<UUID> projectIds);

    void markPublished(UUID projectId, long expectedVersion);

    void updateCatalogOrder(List<UUID> projectIdsInOrder);

    default void insert(ProjectWorkspaceDto workspace, Instant updatedAt) {
        insert(workspace);
    }

    default void replace(
            ProjectWorkspaceDto workspace,
            long expectedVersion,
            Instant updatedAt) {
        replace(workspace, expectedVersion);
    }
}
