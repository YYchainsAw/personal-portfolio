package xyz.yychainsaw.portfolio.publishing.api;

public record PublishSiteCommand(long expectedWorkspaceVersion, long expectedPublicationVersion) { }
