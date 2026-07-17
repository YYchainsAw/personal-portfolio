package xyz.yychainsaw.portfolio.publishing.api;

import java.util.UUID;
import xyz.yychainsaw.portfolio.publishing.snapshot.AggregateType;

public record PreviewTokenRequest(
        AggregateType aggregateType,
        UUID aggregateId,
        long workspaceVersion) {}
