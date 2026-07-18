package xyz.yychainsaw.portfolio.analytics.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import xyz.yychainsaw.portfolio.system.job.JobExecutionContext;
import xyz.yychainsaw.portfolio.system.job.JobHandler;

@Component
public final class AnalyticsRetentionJobHandler implements JobHandler {
    public static final int BATCH_SIZE = 5_000;
    static final int MAX_REPAIRS_PER_RUN = 8;
    static final int MAX_BATCHES_PER_RUN = 10;

    private static final String JOB_TYPE = "ANALYTICS_RETENTION";
    private static final String INVALID_PAYLOAD = "ANALYTICS_RETENTION_PAYLOAD_INVALID";
    private static final String FAILURE_CODE = "ANALYTICS_RETENTION_FAILED";
    private static final Duration RETENTION = Duration.ofDays(30);
    private static final ZoneId SITE_ZONE = ZoneId.of("Asia/Hong_Kong");

    private final AnalyticsRetentionRepository repository;
    private final AnalyticsMaintenanceRunRepository maintenanceRuns;
    private final AnalyticsAggregationService aggregationService;
    private final Clock clock;
    private final Supplier<UUID> runIds;

    @Autowired
    public AnalyticsRetentionJobHandler(
            AnalyticsRetentionRepository repository,
            AnalyticsMaintenanceRunRepository maintenanceRuns,
            AnalyticsAggregationService aggregationService,
            Clock clock) {
        this(repository, maintenanceRuns, aggregationService, clock, UUID::randomUUID);
    }

    AnalyticsRetentionJobHandler(
            AnalyticsRetentionRepository repository,
            AnalyticsMaintenanceRunRepository maintenanceRuns,
            AnalyticsAggregationService aggregationService,
            Clock clock,
            Supplier<UUID> runIds) {
        this.repository = Objects.requireNonNull(
                repository, "analytics retention repository is required");
        this.maintenanceRuns = Objects.requireNonNull(
                maintenanceRuns, "analytics maintenance repository is required");
        this.aggregationService = Objects.requireNonNull(
                aggregationService, "analytics aggregation service is required");
        this.clock = Objects.requireNonNull(clock, "analytics retention clock is required");
        this.runIds = Objects.requireNonNull(runIds, "analytics retention run IDs are required");
    }

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public void handle(JsonNode payload) {
        throw new IllegalStateException("ANALYTICS_RETENTION_CONTEXT_REQUIRED");
    }

    @Override
    public void handle(JobExecutionContext context, JsonNode payload) {
        if (context == null) {
            throw new IllegalStateException("ANALYTICS_RETENTION_CONTEXT_REQUIRED");
        }
        JobExecutionContext execution = context;
        Instant startedAt = now();
        LocalDate payloadSiteDate = AnalyticsJobPayload.requireSiteDate(
                payload,
                startedAt.atZone(SITE_ZONE).toLocalDate(),
                INVALID_PAYLOAD);
        Instant cutoff = startedAt.minus(RETENTION);
        UUID runId = nextRunId();
        boolean started = false;
        long deletedCount = 0;
        try {
            maintenanceRuns.startRetention(runId, startedAt, cutoff);
            started = true;
            repairMissingCoverage(cutoff);

            for (int batch = 1; batch <= MAX_BATCHES_PER_RUN; batch++) {
                int deleted;
                if (batch == MAX_BATCHES_PER_RUN) {
                    AnalyticsRetentionRepository.RetentionSliceTail tail =
                            repository.deleteFinalBatchAndScheduleSuccessor(
                                    cutoff,
                                    BATCH_SIZE,
                                    execution.jobId(),
                                    execution.attemptFence(),
                                    payloadSiteDate);
                    deleted = tail.deletedCount();
                } else {
                    deleted = repository.deleteExpiredBatch(cutoff, BATCH_SIZE);
                }
                if (deleted < 0 || deleted > BATCH_SIZE) {
                    throw failure();
                }
                deletedCount = Math.addExact(deletedCount, deleted);
                if (deleted == 0) {
                    break;
                }
            }
            maintenanceRuns.succeedRetention(
                    runId, deletedCount, cutoff, completionTime(startedAt));
        } catch (RuntimeException exception) {
            if (started) {
                recordFailure(runId, deletedCount, cutoff, startedAt);
            }
            throw failure();
        }
    }

    private void repairMissingCoverage(Instant cutoff) {
        for (int repaired = 0; repaired < MAX_REPAIRS_PER_RUN; repaired++) {
            if (repository.hasCompleteAggregateCoverage(cutoff)) {
                return;
            }
            LocalDate siteDate = repository.findFirstIncompleteExpiredDate(cutoff)
                    .orElseThrow(AnalyticsRetentionJobHandler::failure);
            aggregationService.rebuildForRetention(siteDate, cutoff);
        }
        if (!repository.hasCompleteAggregateCoverage(cutoff)) {
            throw failure();
        }
    }

    private void recordFailure(
            UUID runId, long deletedCount, Instant cutoff, Instant startedAt) {
        try {
            maintenanceRuns.failRetention(
                    runId,
                    deletedCount,
                    cutoff,
                    completionTime(startedAt),
                    FAILURE_CODE);
        } catch (RuntimeException ignored) {
            // The job-visible failure remains fixed and contains no dependency data.
        }
    }

    private UUID nextRunId() {
        try {
            return Objects.requireNonNull(runIds.get());
        } catch (RuntimeException exception) {
            throw failure();
        }
    }

    private Instant completionTime(Instant startedAt) {
        Instant candidate = now();
        return candidate.isBefore(startedAt) ? startedAt : candidate;
    }

    private Instant now() {
        try {
            return Objects.requireNonNull(clock.instant()).truncatedTo(ChronoUnit.SECONDS);
        } catch (RuntimeException exception) {
            throw failure();
        }
    }

    private static IllegalStateException failure() {
        return new IllegalStateException(FAILURE_CODE);
    }
}
