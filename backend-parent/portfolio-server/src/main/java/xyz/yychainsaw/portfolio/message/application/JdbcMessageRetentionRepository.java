package xyz.yychainsaw.portfolio.message.application;

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
public class JdbcMessageRetentionRepository
        implements MessageRetentionRepository {
    private static final String RUN_TYPE = "CONTACT_RETENTION";
    private static final String FAILURE_CODE = "CONTACT_RETENTION_FAILED";
    private static final int MAXIMUM_BATCH_SIZE = 500;
    private static final int TRANSACTION_TIMEOUT_SECONDS = 5;

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    public JdbcMessageRetentionRepository(
            JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc is required");
        this.transaction = transactionTemplate(transactionManager);
    }

    @Override
    public void start(UUID runId, Instant startedAt) {
        UUID id = Objects.requireNonNull(runId, "retention run id is required");
        OffsetDateTime started = toDatabaseTimestamp(
                startedAt, "retention start timestamp is required");
        execute(() -> {
            int inserted = jdbc.sql("""
                            insert into portfolio.maintenance_run(
                                id,
                                run_type,
                                status,
                                details,
                                started_at
                            ) values (
                                :id,
                                :runType,
                                'RUNNING',
                                jsonb_build_object('deleted_count', 0),
                                :startedAt
                            )
                            """)
                    .param("id", id, Types.OTHER)
                    .param("runType", RUN_TYPE, Types.VARCHAR)
                    .param("startedAt", started, Types.TIMESTAMP_WITH_TIMEZONE)
                    .update();
            requireSingleRow(inserted);
        });
    }

    @Override
    public int deleteExpiredBatch(Instant cutoff, int batchSize) {
        OffsetDateTime normalizedCutoff = toDatabaseTimestamp(
                cutoff, "retention cutoff is required");
        if (batchSize < 1 || batchSize > MAXIMUM_BATCH_SIZE) {
            throw new IllegalArgumentException("retention batch size is invalid");
        }

        Integer deleted = executeWithResult(() -> jdbc.sql("""
                        with paired as materialized (
                            select message.id
                            from portfolio.contact_message message
                            join portfolio.email_outbox outbox
                              on outbox.contact_message_id=message.id
                            where message.created_at < :cutoff
                              and (
                                  outbox.status <> 'SENDING'
                                  or outbox.lease_until < clock_timestamp()
                              )
                            order by message.created_at, message.id
                            for update of message, outbox skip locked
                            limit :batchSize
                        ), capacity as (
                            select :batchSize - count(*)::integer as remaining
                            from paired
                        ), orphaned as materialized (
                            select message.id
                            from portfolio.contact_message message
                            where message.created_at < :cutoff
                              and not exists (
                                  select 1
                                  from portfolio.email_outbox outbox
                                  where outbox.contact_message_id=message.id
                              )
                            order by message.created_at, message.id
                            for update of message skip locked
                            limit (select remaining from capacity)
                        ), candidates as (
                            select paired.id from paired
                            union all
                            select orphaned.id from orphaned
                        )
                        delete from portfolio.contact_message message
                        using candidates
                        where message.id=candidates.id
                        """)
                .param("cutoff", normalizedCutoff, Types.TIMESTAMP_WITH_TIMEZONE)
                .param("batchSize", batchSize, Types.INTEGER)
                .update());
        if (deleted == null || deleted < 0 || deleted > batchSize) {
            throw failure();
        }
        return deleted;
    }

    @Override
    public void succeed(UUID runId, long deletedCount, Instant finishedAt) {
        updateTerminalRun(runId, deletedCount, finishedAt, "SUCCEEDED", null);
    }

    @Override
    public void fail(
            UUID runId,
            long deletedCount,
            Instant finishedAt,
            String safeErrorCode) {
        if (!FAILURE_CODE.equals(safeErrorCode)) {
            throw new IllegalArgumentException("retention error code is invalid");
        }
        updateTerminalRun(
                runId, deletedCount, finishedAt, "FAILED", FAILURE_CODE);
    }

    private void updateTerminalRun(
            UUID runId,
            long deletedCount,
            Instant finishedAt,
            String status,
            String errorCode) {
        UUID id = Objects.requireNonNull(runId, "retention run id is required");
        requireDeletedCount(deletedCount);
        OffsetDateTime finished = toDatabaseTimestamp(
                finishedAt, "retention finish timestamp is required");
        execute(() -> {
            int updated = jdbc.sql("""
                            update portfolio.maintenance_run
                            set status=:status,
                                error_summary=:errorCode,
                                details=jsonb_build_object(
                                    'deleted_count', cast(:deletedCount as bigint)
                                ),
                                finished_at=:finishedAt
                            where id=:id
                              and run_type=:runType
                              and status='RUNNING'
                            """)
                    .param("status", status, Types.VARCHAR)
                    .param("errorCode", errorCode, Types.VARCHAR)
                    .param("deletedCount", deletedCount, Types.BIGINT)
                    .param("finishedAt", finished, Types.TIMESTAMP_WITH_TIMEZONE)
                    .param("id", id, Types.OTHER)
                    .param("runType", RUN_TYPE, Types.VARCHAR)
                    .update();
            requireSingleRow(updated);
        });
    }

    private void execute(Runnable action) {
        try {
            transaction.executeWithoutResult(status -> action.run());
        } catch (RuntimeException exception) {
            throw failure();
        }
    }

    private <T> T executeWithResult(java.util.function.Supplier<T> action) {
        try {
            return transaction.execute(status -> action.get());
        } catch (RuntimeException exception) {
            throw failure();
        }
    }

    private static TransactionTemplate transactionTemplate(
            PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(
                Objects.requireNonNull(
                        transactionManager, "transaction manager is required"));
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setTimeout(TRANSACTION_TIMEOUT_SECONDS);
        return template;
    }

    private static OffsetDateTime toDatabaseTimestamp(
            Instant value, String message) {
        Instant normalized = Objects.requireNonNull(value, message)
                .truncatedTo(ChronoUnit.MICROS);
        return OffsetDateTime.ofInstant(normalized, ZoneOffset.UTC);
    }

    private static void requireDeletedCount(long deletedCount) {
        if (deletedCount < 0) {
            throw new IllegalArgumentException("retention deleted count is invalid");
        }
    }

    private static void requireSingleRow(int affectedRows) {
        if (affectedRows != 1) {
            throw failure();
        }
    }

    private static IllegalStateException failure() {
        return new IllegalStateException(FAILURE_CODE);
    }
}
