package xyz.yychainsaw.portfolio.publishing.api;

import java.util.UUID;

public record ArchiveProjectCommand(
        UUID projectId,
        long expectedProjectPublicationVersion,
        long expectedCatalogVersion) { }
