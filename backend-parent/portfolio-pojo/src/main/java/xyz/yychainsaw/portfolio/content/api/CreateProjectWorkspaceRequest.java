package xyz.yychainsaw.portfolio.content.api;

/**
 * Project creation payload. The root identity, root version and publication state
 * are always replaced by server-owned values before persistence.
 */
public record CreateProjectWorkspaceRequest(ProjectWorkspaceDto workspace) {}
