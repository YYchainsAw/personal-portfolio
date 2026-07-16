package xyz.yychainsaw.portfolio.system.job;

import java.time.OffsetDateTime;
import java.util.UUID;

public record BackgroundJobRow(
        UUID id,
        String jobType,
        String payloadJson,
        String status,
        int attempts,
        String leaseOwner,
        OffsetDateTime nextRunAt) {
}

record ExistingBackgroundJobRow(UUID id, String jobType, boolean payloadMatches) {
}
