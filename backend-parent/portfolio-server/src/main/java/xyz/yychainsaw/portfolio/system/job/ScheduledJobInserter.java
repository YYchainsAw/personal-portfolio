package xyz.yychainsaw.portfolio.system.job;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
public class ScheduledJobInserter {
    private static final Duration MAXIMUM_DELAY = Duration.ofDays(365);
    private static final RowMapper<ScheduledJobInsert> INSERT_RESULT_MAPPER =
            (resultSet, rowNumber) -> new ScheduledJobInsert(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getObject("database_now", OffsetDateTime.class),
                    resultSet.getObject("next_run_at", OffsetDateTime.class));

    private final JdbcClient jdbc;
    private final JobPayloadCodec payloadCodec;

    public ScheduledJobInserter(JdbcClient jdbc, JobPayloadCodec payloadCodec) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc is required");
        this.payloadCodec = Objects.requireNonNull(
                payloadCodec, "job payload codec is required");
    }

    public ScheduledJobInsert insertAfter(
            String jobType,
            String idempotencyKey,
            Map<String, ?> payload,
            Duration delay) {
        requireAmbientTransaction();
        JobPayloadCodec.requireJobType(jobType);
        JobPayloadCodec.requireIdempotencyKey(idempotencyKey);
        long delaySeconds = requireDelaySeconds(delay);
        String payloadJson = payloadCodec.serialize(payload);
        UUID jobId = UUID.randomUUID();

        return jdbc.sql("""
                        with database_time as materialized (
                            select clock_timestamp() as database_now
                        ), inserted as (
                            insert into portfolio.background_job(
                                id, job_type, idempotency_key, payload, status,
                                attempts, next_run_at, created_at, updated_at
                            )
                            select :id, :jobType, :idempotencyKey,
                                   cast(:payloadJson as jsonb), 'PENDING', 0,
                                   database_time.database_now
                                       + (:delaySeconds * interval '1 second'),
                                   database_time.database_now,
                                   database_time.database_now
                            from database_time
                            returning id, created_at, next_run_at
                        )
                        select inserted.id,
                               inserted.created_at as database_now,
                               inserted.next_run_at
                        from inserted
                        """)
                .param("id", jobId)
                .param("jobType", jobType)
                .param("idempotencyKey", idempotencyKey)
                .param("payloadJson", payloadJson)
                .param("delaySeconds", delaySeconds)
                .query(INSERT_RESULT_MAPPER)
                .single();
    }

    public UUID insertAfterIfAbsent(
            String jobType,
            String idempotencyKey,
            Map<String, ?> payload,
            Duration delay) {
        requireAmbientTransaction();
        JobPayloadCodec.requireJobType(jobType);
        JobPayloadCodec.requireIdempotencyKey(idempotencyKey);
        long delaySeconds = requireDelaySeconds(delay);
        String payloadJson = payloadCodec.serialize(payload);
        UUID jobId = UUID.randomUUID();

        Optional<UUID> inserted = jdbc.sql("""
                        with database_time as materialized (
                            select clock_timestamp() as database_now
                        )
                        insert into portfolio.background_job(
                            id, job_type, idempotency_key, payload, status,
                            attempts, next_run_at, created_at, updated_at
                        )
                        select :id, :jobType, :idempotencyKey,
                               cast(:payloadJson as jsonb), 'PENDING', 0,
                               database_time.database_now
                                   + (:delaySeconds * interval '1 second'),
                               database_time.database_now,
                               database_time.database_now
                        from database_time
                        on conflict (idempotency_key) do nothing
                        returning id
                        """)
                .param("id", jobId)
                .param("jobType", jobType)
                .param("idempotencyKey", idempotencyKey)
                .param("payloadJson", payloadJson)
                .param("delaySeconds", delaySeconds)
                .query(UUID.class)
                .optional();
        if (inserted.isPresent()) {
            return inserted.get();
        }
        return jdbc.sql("""
                        select job.id
                        from portfolio.background_job job
                        where job.idempotency_key=:idempotencyKey
                          and job.job_type=:jobType
                          and job.payload=cast(:payloadJson as jsonb)
                        """)
                .param("idempotencyKey", idempotencyKey)
                .param("jobType", jobType)
                .param("payloadJson", payloadJson)
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "JOB_IDEMPOTENCY_CONFLICT"));
    }

    private static void requireAmbientTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("JOB_INSERT_REQUIRES_TRANSACTION");
        }
    }

    private static long requireDelaySeconds(Duration delay) {
        if (delay == null
                || delay.isNegative()
                || delay.compareTo(MAXIMUM_DELAY) > 0
                || delay.getNano() != 0) {
            throw new IllegalArgumentException("job schedule delay is invalid");
        }
        try {
            return delay.getSeconds();
        } catch (ArithmeticException invalid) {
            throw new IllegalArgumentException("job schedule delay is invalid");
        }
    }
}
