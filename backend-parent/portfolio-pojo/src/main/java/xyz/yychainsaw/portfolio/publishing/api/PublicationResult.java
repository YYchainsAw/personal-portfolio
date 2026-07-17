package xyz.yychainsaw.portfolio.publishing.api;

import java.util.UUID;

public record PublicationResult(
        UUID revisionId,
        long aggregateVersion,
        UUID catalogRevisionId,
        Long catalogVersion,
        String checksum) { }
