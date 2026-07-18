package xyz.yychainsaw.portfolio.analytics.application;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcAnalyticsMaintenanceRunRepository
        implements AnalyticsMaintenanceRunRepository {
    private static final String AGGREGATION_RUN_TYPE = "ANALYTICS_AGGREGATE";
    private static final String RETENTION_RUN_TYPE = "ANALYTICS_RETENTION";
    private static final String AGGREGATION_FAILURE = "ANALYTICS_AGGREGATION_FAILED";
    private static final String RETENTION_FAILURE = "ANALYTICS_RETENTION_FAILED";
    private static final int TRANSACTION_TIMEOUT_SECONDS = 5;

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    public JdbcAnalyticsMaintenanceRunRepository(
            JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "analytics maintenance JDBC client is required");
        this.transaction = transactionTemplate(transactionManager);
    }

    @Override
    public void startAggregation(UUID runId, Instant startedAt) {
        insertRun(
                runId,
                AGGREGATION_RUN_TYPE,
                startedAt,
                "jsonb_build_object('input_count', 0, 'output_count', 0)",
                null);
    }

    @Override
    public void succeedAggregation(
            UUID runId, long inputCount, long outputCount, Instant finishedAt) {
        updateAggregation(
                runId, inputCount, outputCount, finishedAt, "SUCCEEDED", null);
    }

    @Override
    public void failAggregation(
            UUID runId,
            long inputCount,
            long outputCount,
            Instant finishedAt,
            String safeErrorCode) {
        if (!AGGREGATION_FAILURE.equals(safeErrorCode)) {
            throw new IllegalArgumentException("analytics aggregation error code is invalid");
        }
        updateAggregation(
                runId,
                inputCount,
                outputCount,
                finishedAt,
                "FAILED",
                AGGREGATION_FAILURE);
    }

    @Override
    public void startRetention(UUID runId, Instant startedAt, Instant cutoff) {
        requireEpochSecond(cutoff);
        insertRun(
                runId,
                RETENTION_RUN_TYPE,
                startedAt,
                "jsonb_build_object(" +
                        "'deleted_count', 0, " +
                        "'cutoff_epoch_second', cast(:cutoffEpochSecond as bigint))",
                cutoff.getEpochSecond());
    }

    @Override
    public void succeedRetention(
            UUID runId, long deletedCount, Instant cutoff, Instant finishedAt) {
        updateRetention(runId, deletedCount, cutoff, finishedAt, "SUCCEEDED", null);
    }

    @Override
    public void failRetention(
            UUID runId,
            long deletedCount,
            Instant cutoff,
            Instant finishedAt,
            String safeErrorCode) {
        if (!RETENTION_FAILURE.equals(safeErrorCode)) {
            throw new IllegalArgumentException("analytics retention error code is invalid");
        }
        updateRetention(
                runId,
                deletedCount,
                cutoff,
                finishedAt,
                "FAILED",
                RETENTION_FAILURE);
    }

    private void insertRun(
            UUID runId,
            String runType,
            Instant startedAt,
            String detailsExpression,
            Long cutoffEpochSecond) {
        UUID id = Objects.requireNonNull(runId, "analytics maintenance run ID is required");
        OffsetDateTime started = timestamp(startedAt, "analytics maintenance start time is required");
        execute(() -> {
            JdbcClient.StatementSpec statement = jdbc.sql("""
                            insert into portfolio.maintenance_run(
                                id, run_type, status, details, started_at
                            ) values (
                                :id, :runType, 'RUNNING',
                                """ + detailsExpression + ", :startedAt)")
                    .param("id", id, Types.OTHER)
                    .param("runType", runType, Types.VARCHAR)
                    .param("startedAt", started, Types.TIMESTAMP_WITH_TIMEZONE);
            if (cutoffEpochSecond != null) {
                statement = statement.param(
                        "cutoffEpochSecond", cutoffEpochSecond, Types.BIGINT);
            }
            requireSingleRow(statement.update());
        });
    }

    private void updateAggregation(
            UUID runId,
            long inputCount,
            long outputCount,
            Instant finishedAt,
            String status,
            String errorCode) {
        requireCount(inputCount);
        requireCount(outputCount);
        UUID id = Objects.requireNonNull(runId, "analytics maintenance run ID is required");
        OffsetDateTime finished = timestamp(
                finishedAt, "analytics maintenance finish time is required");
        execute(() -> requireSingleRow(jdbc.sql("""
                        update portfolio.maintenance_run
                        set status=:status,
                            error_summary=:errorCode,
                            details=jsonb_build_object(
                                'input_count', cast(:inputCount as bigint),
                                'output_count', cast(:outputCount as bigint)
                            ),
                            finished_at=:finishedAt
                        where id=:id
                          and run_type=:runType
                          and status='RUNNING'
                        """)
                .param("status", status, Types.VARCHAR)
                .param("errorCode", errorCode, Types.VARCHAR)
                .param("inputCount", inputCount, Types.BIGINT)
                .param("outputCount", outputCount, Types.BIGINT)
                .param("finishedAt", finished, Types.TIMESTAMP_WITH_TIMEZONE)
                .param("id", id, Types.OTHER)
                .param("runType", AGGREGATION_RUN_TYPE, Types.VARCHAR)
                .update()));
    }

    private void updateRetention(
            UUID runId,
            long deletedCount,
            Instant cutoff,
            Instant finishedAt,
            String status,
            String errorCode) {
        requireCount(deletedCount);
        requireEpochSecond(cutoff);
        UUID id = Objects.requireNonNull(runId, "analytics maintenance run ID is required");
        OffsetDateTime finished = timestamp(
                finishedAt, "analytics maintenance finish time is required");
        execute(() -> requireSingleRow(jdbc.sql("""
                        update portfolio.maintenance_run
                        set status=:status,
                            error_summary=:errorCode,
                            details=jsonb_build_object(
                                'deleted_count', cast(:deletedCount as bigint),
                                'cutoff_epoch_second',
                                    cast(:cutoffEpochSecond as bigint)
                            ),
                            finished_at=:finishedAt
                        where id=:id
                          and run_type=:runType
                          and status='RUNNING'
                        """)
                .param("status", status, Types.VARCHAR)
                .param("errorCode", errorCode, Types.VARCHAR)
                .param("deletedCount", deletedCount, Types.BIGINT)
                .param("cutoffEpochSecond", cutoff.getEpochSecond(), Types.BIGINT)
                .param("finishedAt", finished, Types.TIMESTAMP_WITH_TIMEZONE)
                .param("id", id, Types.OTHER)
                .param("runType", RETENTION_RUN_TYPE, Types.VARCHAR)
                .update()));
    }

    private void execute(Runnable action) {
        try {
            transaction.executeWithoutResult(status -> action.run());
        } catch (RuntimeException exception) {
            throw new IllegalStateException("ANALYTICS_MAINTENANCE_RUN_FAILED");
        }
    }

    private static TransactionTemplate transactionTemplate(
            PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(Objects.requireNonNull(
                transactionManager, "analytics transaction manager is required"));
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setTimeout(TRANSACTION_TIMEOUT_SECONDS);
        return template;
    }

    private static OffsetDateTime timestamp(Instant value, String message) {
        return OffsetDateTime.ofInstant(
                Objects.requireNonNull(value, message).truncatedTo(ChronoUnit.MICROS),
                ZoneOffset.UTC);
    }

    private static void requireEpochSecond(Instant cutoff) {
        if (Objects.requireNonNull(cutoff, "analytics retention cutoff is required")
                .getEpochSecond() < 0) {
            throw new IllegalArgumentException("analytics retention cutoff is invalid");
        }
    }

    private static void requireCount(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("analytics maintenance count is invalid");
        }
    }

    private static void requireSingleRow(int affectedRows) {
        if (affectedRows != 1) {
            throw new IllegalStateException("ANALYTICS_MAINTENANCE_RUN_FAILED");
        }
    }
}
