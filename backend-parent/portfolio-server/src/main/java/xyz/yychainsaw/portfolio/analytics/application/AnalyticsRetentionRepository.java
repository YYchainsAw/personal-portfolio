package xyz.yychainsaw.portfolio.analytics.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface AnalyticsRetentionRepository {
    boolean hasCompleteAggregateCoverage(Instant cutoff);

    Optional<LocalDate> findFirstIncompleteExpiredDate(Instant cutoff);

    Optional<LocalDate> findFirstIncompleteAggregateDate(
            Instant receivedBefore, LocalDate earliestSiteDate);

    int deleteExpiredBatch(Instant cutoff, int batchSize);

    RetentionSliceTail deleteFinalBatchAndScheduleSuccessor(
            Instant cutoff,
            int batchSize,
            UUID parentJobId,
            int parentAttemptFence,
            LocalDate payloadSiteDate);

    record RetentionSliceTail(int deletedCount, boolean remaining) {
        public RetentionSliceTail {
            if (deletedCount < 0) {
                throw new IllegalArgumentException(
                        "analytics retention tail count is invalid");
            }
        }
    }
}
