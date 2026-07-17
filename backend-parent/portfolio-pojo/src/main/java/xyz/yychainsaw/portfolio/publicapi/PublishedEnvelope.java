package xyz.yychainsaw.portfolio.publicapi;

public record PublishedEnvelope<T>(long revisionVersion, String checksum, T data) {
}
