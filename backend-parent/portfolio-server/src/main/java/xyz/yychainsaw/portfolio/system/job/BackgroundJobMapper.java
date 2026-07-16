package xyz.yychainsaw.portfolio.system.job;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class BackgroundJobMapper {
    private static final RowMapper<BackgroundJobRow> JOB_ROW_MAPPER =
            (resultSet, rowNumber) -> new BackgroundJobRow(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getString("job_type"),
                    resultSet.getString("payload_json"),
                    resultSet.getString("status"),
                    resultSet.getInt("attempts"),
                    resultSet.getString("lease_owner"),
                    resultSet.getObject("next_run_at", OffsetDateTime.class));
    private static final RowMapper<ExistingBackgroundJobRow> EXISTING_JOB_ROW_MAPPER =
            (resultSet, rowNumber) -> new ExistingBackgroundJobRow(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getString("job_type"),
                    resultSet.getBoolean("payload_matches"));

    private final JdbcClient jdbc;

    public BackgroundJobMapper(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc is required");
    }

    public Optional<UUID> insertIfAbsent(
            UUID id,
            String jobType,
            String key,
            String payloadJson,
            OffsetDateTime now) {
        return jdbc.sql("""
                        insert into portfolio.background_job(
                            id, job_type, idempotency_key, payload, status, attempts,
                            next_run_at, created_at, updated_at
                        ) values (
                            :id, :jobType, :key, cast(:payloadJson as jsonb), 'PENDING', 0,
                            :now, :now, :now
                        )
                        on conflict (idempotency_key) do nothing
                        returning id
                        """)
                .param("id", id)
                .param("jobType", jobType)
                .param("key", key)
                .param("payloadJson", payloadJson)
                .param("now", now, Types.TIMESTAMP_WITH_TIMEZONE)
                .query(UUID.class)
                .optional();
    }

    public Optional<ExistingBackgroundJobRow> findByIdempotencyKey(
            String key, String payloadJson) {
        return jdbc.sql("""
                        select id, job_type,
                               payload = cast(:payloadJson as jsonb) as payload_matches
                        from portfolio.background_job
                        where idempotency_key=:key
                        """)
                .param("key", key)
                .param("payloadJson", payloadJson)
                .query(EXISTING_JOB_ROW_MAPPER)
                .optional();
    }

    public Optional<BackgroundJobRow> claimNext(
            String owner, OffsetDateTime now, OffsetDateTime leaseUntil) {
        return jdbc.sql("""
                        with candidate as (
                            select job.id
                            from portfolio.background_job job
                            where job.attempts < 10
                              and (
                                  (
                                      job.status in ('PENDING', 'FAILED')
                                      and job.next_run_at <= :now
                                  )
                                  or (
                                      job.status = 'RUNNING'
                                      and job.lease_until < :now
                                  )
                              )
                            order by job.next_run_at, job.created_at, job.id
                            for update skip locked
                            limit 1
                        )
                        update portfolio.background_job job
                        set status='RUNNING',
                            attempts=job.attempts + 1,
                            lease_owner=:owner,
                            lease_until=:leaseUntil,
                            last_error_summary=null
                        from candidate
                        where job.id=candidate.id
                        returning job.id, job.job_type,
                                  job.payload::text as payload_json,
                                  job.status, job.attempts, job.lease_owner,
                                  job.next_run_at
                        """)
                .param("owner", owner)
                .param("now", now, Types.TIMESTAMP_WITH_TIMEZONE)
                .param("leaseUntil", leaseUntil, Types.TIMESTAMP_WITH_TIMEZONE)
                .query(JOB_ROW_MAPPER)
                .optional();
    }

    public Optional<BackgroundJobRow> deadLetterNextExhausted(
            OffsetDateTime now, String summary) {
        return jdbc.sql("""
                        with candidate as (
                            select job.id
                            from portfolio.background_job job
                            where job.attempts >= 10
                              and (
                                  (
                                      job.status in ('PENDING', 'FAILED')
                                      and job.next_run_at <= :now
                                  )
                                  or (
                                      job.status = 'RUNNING'
                                      and job.lease_until < :now
                                  )
                              )
                            order by job.next_run_at, job.created_at, job.id
                            for update skip locked
                            limit 1
                        )
                        update portfolio.background_job job
                        set status='DEAD',
                            lease_owner=null,
                            lease_until=null,
                            last_error_summary=:summary
                        from candidate
                        where job.id=candidate.id
                        returning job.id, job.job_type,
                                  job.payload::text as payload_json,
                                  job.status, job.attempts, job.lease_owner,
                                  job.next_run_at
                        """)
                .param("now", now, Types.TIMESTAMP_WITH_TIMEZONE)
                .param("summary", summary)
                .query(JOB_ROW_MAPPER)
                .optional();
    }

    public int succeed(UUID id, String owner, int attempt) {
        return jdbc.sql("""
                        update portfolio.background_job
                        set status='SUCCEEDED', lease_owner=null, lease_until=null
                        where id=:id
                          and status='RUNNING'
                          and lease_owner=:owner
                          and attempts=:attempt
                        """)
                .param("id", id)
                .param("owner", owner)
                .param("attempt", attempt)
                .update();
    }

    public Optional<BackgroundJobRow> fail(
            UUID id,
            String owner,
            int attempt,
            String summary,
            OffsetDateTime nextRunAt) {
        return jdbc.sql("""
                        update portfolio.background_job job
                        set status=case
                                when job.attempts >= 10 then 'DEAD'
                                else 'FAILED'
                            end,
                            next_run_at=:nextRunAt,
                            lease_owner=null,
                            lease_until=null,
                            last_error_summary=:summary
                        where job.id=:id
                          and job.status='RUNNING'
                          and job.lease_owner=:owner
                          and job.attempts=:attempt
                        returning job.id, job.job_type,
                                  job.payload::text as payload_json,
                                  job.status, job.attempts, job.lease_owner,
                                  job.next_run_at
                        """)
                .param("id", id)
                .param("owner", owner)
                .param("attempt", attempt)
                .param("summary", summary)
                .param("nextRunAt", nextRunAt, Types.TIMESTAMP_WITH_TIMEZONE)
                .query(JOB_ROW_MAPPER)
                .optional();
    }
}
