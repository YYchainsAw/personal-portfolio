package xyz.yychainsaw.portfolio.content.persistence;

import java.time.Instant;
import xyz.yychainsaw.portfolio.content.api.SiteWorkspaceDto;

public interface SiteWorkspaceRepository {
    SiteWorkspaceDto require();

    void replace(SiteWorkspaceDto workspace, long expectedVersion);

    default void replace(
            SiteWorkspaceDto workspace,
            long expectedVersion,
            Instant updatedAt) {
        replace(workspace, expectedVersion);
    }
}
