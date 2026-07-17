package xyz.yychainsaw.portfolio.publishing.snapshot;

public record EncodedSnapshot(int schemaVersion, String json, String sha256) {}
