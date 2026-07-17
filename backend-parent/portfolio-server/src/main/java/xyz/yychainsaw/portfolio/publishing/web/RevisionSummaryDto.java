package xyz.yychainsaw.portfolio.publishing.web;

import java.time.Instant;
import java.util.UUID;
import xyz.yychainsaw.portfolio.publishing.snapshot.AggregateType;

public record RevisionSummaryDto(
        UUID id,
        AggregateType type,
        UUID aggregateId,
        long version,
        int schemaVersion,
        String checksum,
        UUID publishedBy,
        Instant publishedAt) { }
