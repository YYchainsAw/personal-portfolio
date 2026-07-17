package xyz.yychainsaw.portfolio.message.application;

import java.time.Instant;
import java.util.UUID;

public interface MessageRetentionRepository {
    void start(UUID runId, Instant startedAt);

    int deleteExpiredBatch(Instant cutoff, int batchSize);

    void succeed(UUID runId, long deletedCount, Instant finishedAt);

    void fail(
            UUID runId,
            long deletedCount,
            Instant finishedAt,
            String safeErrorCode);
}
