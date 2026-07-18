package xyz.yychainsaw.portfolio.analytics.application;

import java.time.Instant;
import java.util.UUID;

public interface AnalyticsMaintenanceRunRepository {
    void startAggregation(UUID runId, Instant startedAt);

    void succeedAggregation(
            UUID runId, long inputCount, long outputCount, Instant finishedAt);

    void failAggregation(
            UUID runId,
            long inputCount,
            long outputCount,
            Instant finishedAt,
            String safeErrorCode);

    void startRetention(UUID runId, Instant startedAt, Instant cutoff);

    void succeedRetention(
            UUID runId, long deletedCount, Instant cutoff, Instant finishedAt);

    void failRetention(
            UUID runId,
            long deletedCount,
            Instant cutoff,
            Instant finishedAt,
            String safeErrorCode);
}
