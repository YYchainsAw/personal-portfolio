package xyz.yychainsaw.portfolio.analytics.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import xyz.yychainsaw.portfolio.system.job.JobHandler;

@Component
public final class AnalyticsAggregationJobHandler implements JobHandler {
    private static final String JOB_TYPE = "ANALYTICS_AGGREGATE";
    private static final String INVALID_PAYLOAD = "ANALYTICS_AGGREGATION_PAYLOAD_INVALID";
    private static final String FAILURE_CODE = "ANALYTICS_AGGREGATION_FAILED";
    private static final ZoneId SITE_ZONE = ZoneId.of("Asia/Hong_Kong");

    private final AnalyticsAggregationService service;
    private final AnalyticsMaintenanceRunRepository maintenanceRuns;
    private final AnalyticsRules rules;
    private final Clock clock;
    private final Supplier<UUID> runIds;

    @Autowired
    public AnalyticsAggregationJobHandler(
            AnalyticsAggregationService service,
            AnalyticsMaintenanceRunRepository maintenanceRuns,
            AnalyticsRules rules,
            Clock clock) {
        this(service, maintenanceRuns, rules, clock, UUID::randomUUID);
    }

    AnalyticsAggregationJobHandler(
            AnalyticsAggregationService service,
            AnalyticsMaintenanceRunRepository maintenanceRuns,
            AnalyticsRules rules,
            Clock clock,
            Supplier<UUID> runIds) {
        this.service = Objects.requireNonNull(service, "analytics aggregation service is required");
        this.maintenanceRuns = Objects.requireNonNull(
                maintenanceRuns, "analytics maintenance repository is required");
        this.rules = Objects.requireNonNull(rules, "analytics rules are required");
        this.clock = Objects.requireNonNull(clock, "analytics aggregation clock is required");
        this.runIds = Objects.requireNonNull(runIds, "analytics aggregation run IDs are required");
    }

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public void handle(JsonNode payload) {
        Instant startedAt = now();
        LocalDate siteDate = AnalyticsJobPayload.requireAggregationSiteDate(
                payload,
                startedAt.atZone(SITE_ZONE).toLocalDate(),
                rules.version(),
                INVALID_PAYLOAD);
        UUID runId = nextRunId();
        boolean started = false;
        long inputCount = 0;
        long outputCount = 0;
        try {
            maintenanceRuns.startAggregation(runId, startedAt);
            started = true;
            AnalyticsAggregationService.AggregationResult result = service.rebuild(siteDate);
            inputCount = result.inputCount();
            outputCount = result.outputCount();
            maintenanceRuns.succeedAggregation(
                    runId, inputCount, outputCount, completionTime(startedAt));
        } catch (RuntimeException exception) {
            if (started) {
                recordFailure(runId, inputCount, outputCount, startedAt);
            }
            throw failure();
        }
    }

    private void recordFailure(
            UUID runId, long inputCount, long outputCount, Instant startedAt) {
        try {
            maintenanceRuns.failAggregation(
                    runId,
                    inputCount,
                    outputCount,
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
