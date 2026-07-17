package xyz.yychainsaw.portfolio.content.api;

public record UpdateProjectWorkspaceRequest(
        long expectedVersion,
        ProjectWorkspaceDto workspace) {}
