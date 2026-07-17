package xyz.yychainsaw.portfolio.system.job;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

public record ScheduledJobInsert(
        UUID jobId, OffsetDateTime databaseNow, OffsetDateTime nextRunAt) {
    public ScheduledJobInsert {
        Objects.requireNonNull(jobId, "scheduled job id is required");
        Objects.requireNonNull(databaseNow, "scheduled job database time is required");
        Objects.requireNonNull(nextRunAt, "scheduled job run time is required");
        if (!ZoneOffset.UTC.equals(databaseNow.getOffset())
                || !ZoneOffset.UTC.equals(nextRunAt.getOffset())
                || nextRunAt.isBefore(databaseNow)) {
            throw new IllegalArgumentException("scheduled job result is invalid");
        }
    }
}
