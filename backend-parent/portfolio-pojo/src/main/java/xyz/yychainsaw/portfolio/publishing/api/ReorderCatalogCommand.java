package xyz.yychainsaw.portfolio.publishing.api;

import java.util.List;
import java.util.UUID;

public record ReorderCatalogCommand(long expectedCatalogVersion, List<UUID> projectIdsInOrder) {
    public ReorderCatalogCommand {
        projectIdsInOrder = List.copyOf(projectIdsInOrder);
    }
}
