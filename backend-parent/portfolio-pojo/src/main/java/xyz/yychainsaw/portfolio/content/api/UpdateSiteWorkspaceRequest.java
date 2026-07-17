package xyz.yychainsaw.portfolio.content.api;

public record UpdateSiteWorkspaceRequest(
        long expectedVersion,
        SiteWorkspaceDto workspace) {}
