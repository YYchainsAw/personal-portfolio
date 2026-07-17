package xyz.yychainsaw.portfolio.publishing.api;

import java.util.UUID;

public record PublishProjectCommand(
        UUID projectId,
        long expectedWorkspaceVersion,
        long expectedProjectPublicationVersion,
        long expectedCatalogVersion) { }
