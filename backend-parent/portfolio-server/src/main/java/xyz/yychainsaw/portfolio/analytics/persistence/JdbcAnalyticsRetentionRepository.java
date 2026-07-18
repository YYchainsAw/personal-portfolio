package xyz.yychainsaw.portfolio.analytics.persistence;

import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.yychainsaw.portfolio.analytics.application.AnalyticsRetentionRepository;
import xyz.yychainsaw.portfolio.system.job.ScheduledJobInserter;

@Repository
public class JdbcAnalyticsRetentionRepository implements AnalyticsRetentionRepository {
    private static final int MAXIMUM_BATCH_SIZE = 5_000;
    private static final int TRANSACTION_TIMEOUT_SECONDS = 15;
    private static final String COVERAGE_MISSING =
            "ANALYTICS_RETENTION_COVERAGE_MISSING";
    private static final String JOB_TYPE = "ANALYTICS_RETENTION";
    private static final Duration SUCCESSOR_DELAY = Duration.ofSeconds(5);

    private final JdbcClient jdbc;
    private final ScheduledJobInserter jobs;
    private final TransactionTemplate transaction;

    public JdbcAnalyticsRetentionRepository(
            JdbcClient jdbc,
            ScheduledJobInserter jobs,
            PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "analytics retention JDBC client is required");
        this.jobs = Objects.requireNonNull(jobs, "scheduled job inserter is required");
        this.transaction = transactionTemplate(transactionManager);
    }

    @Override
    public boolean hasCompleteAggregateCoverage(Instant cutoff) {
        OffsetDateTime boundary = timestamp(cutoff);
        try {
            Boolean covered = transaction.execute(status -> coverage(boundary, null));
            return Boolean.TRUE.equals(covered);
        } catch (RuntimeException exception) {
            throw failure();
        }
    }

    @Override
    public Optional<LocalDate> findFirstIncompleteExpiredDate(Instant cutoff) {
        OffsetDateTime boundary = timestamp(cutoff);
        try {
            Optional<LocalDate> missing = transaction.execute(
                    status -> firstMissingCoverage(boundary, null, null));
            return missing == null ? Optional.empty() : missing;
        } catch (RuntimeException exception) {
            throw failure();
        }
    }

    @Override
    public Optional<LocalDate> findFirstIncompleteAggregateDate(
            Instant receivedBefore, LocalDate earliestSiteDate) {
        OffsetDateTime boundary = timestamp(receivedBefore);
        LocalDate earliest = Objects.requireNonNull(
                earliestSiteDate, "analytics aggregation scan start is required");
        try {
            Optional<LocalDate> missing = transaction.execute(
                    status -> firstMissingCoverage(boundary, null, earliest));
            return missing == null ? Optional.empty() : missing;
        } catch (RuntimeException exception) {
            throw failure();
        }
    }

    @Override
    public int deleteExpiredBatch(
            Instant cutoff, int batchSize) {
        OffsetDateTime boundary = timestamp(cutoff);
        requireBatchSize(batchSize);
        try {
            Integer deleted = transaction.execute(status -> deleteLockedBatch(
                    boundary, batchSize));
            if (deleted == null || deleted < 0 || deleted > batchSize) {
                throw failure();
            }
            return deleted;
        } catch (RuntimeException exception) {
            if (isCoverageMissing(exception)) {
                throw exception;
            }
            throw failure();
        }
    }

    @Override
    public RetentionSliceTail deleteFinalBatchAndScheduleSuccessor(
            Instant cutoff,
            int batchSize,
            UUID parentJobId,
            int parentAttemptFence,
            LocalDate payloadSiteDate) {
        OffsetDateTime boundary = timestamp(cutoff);
        requireBatchSize(batchSize);
        UUID parent = Objects.requireNonNull(
                parentJobId, "analytics retention parent job is required");
        if (parentAttemptFence < 1) {
            throw new IllegalArgumentException(
                    "analytics retention parent attempt fence is invalid");
        }
        LocalDate payloadDate = Objects.requireNonNull(
                payloadSiteDate, "analytics retention payload date is required");
        try {
            RetentionSliceTail result = transaction.execute(status -> {
                int deleted = deleteLockedBatch(boundary, batchSize);
                boolean remaining = hasExpiredEvents(boundary);
                if (remaining) {
                    jobs.insertAfterIfAbsent(
                            JOB_TYPE,
                            "analytics-retention-next:" + parent + ":" + parentAttemptFence,
                            Map.of("siteDate", payloadDate.toString()),
                            SUCCESSOR_DELAY);
                }
                return new RetentionSliceTail(deleted, remaining);
            });
            if (result == null
                    || result.deletedCount() > batchSize
                    || (result.deletedCount() == 0 && result.remaining())) {
                throw failure();
            }
            return result;
        } catch (RuntimeException exception) {
            if (isCoverageMissing(exception)) {
                throw exception;
            }
            throw failure();
        }
    }

    private boolean hasExpiredEvents(OffsetDateTime cutoff) {
        return jdbc.sql("""
                        select exists (
                            select 1
                            from portfolio.analytics_event event
                            where event.received_at < :cutoff
                        )
                        """)
                .param("cutoff", cutoff, Types.TIMESTAMP_WITH_TIMEZONE)
                .query(Boolean.class)
                .single();
    }

    private int deleteLockedBatch(
            OffsetDateTime cutoff, int batchSize) {
        setLocalLockTimeout();
        while (true) {
            LocalDate siteDate = jdbc.sql("""
                            select event.site_date
                            from portfolio.analytics_event event
                            where event.received_at < :cutoff
                            order by event.received_at, event.id
                            limit 1
                            """)
                    .param("cutoff", cutoff, Types.TIMESTAMP_WITH_TIMEZONE)
                    .query(LocalDate.class)
                    .optional()
                    .orElse(null);
            if (siteDate == null) {
                return 0;
            }
            acquireDateLock(siteDate);
            requireCoverageAndCheckpoint(cutoff, siteDate);
            int deleted = jdbc.sql("""
                            select portfolio.purge_analytics_event_batch(
                                :siteDate, :cutoff, :batchSize
                            )
                            """)
                    .param("siteDate", siteDate, Types.DATE)
                    .param("cutoff", cutoff, Types.TIMESTAMP_WITH_TIMEZONE)
                    .param("batchSize", batchSize, Types.INTEGER)
                    .query(Integer.class)
                    .single();
            if (deleted > 0) {
                return deleted;
            }
        }
    }

    private void requireCoverageAndCheckpoint(
            OffsetDateTime cutoff, LocalDate siteDate) {
        Optional<String> checkpointVersion = jdbc.sql("""
                        select checkpoint.aggregation_version
                        from portfolio.analytics_retention_checkpoint checkpoint
                        where checkpoint.site_date=:siteDate
                        """)
                .param("siteDate", siteDate, Types.DATE)
                .query(String.class)
                .optional();
        if (checkpointVersion.isPresent()) {
            if (!hasCheckpointBaseRows(siteDate, checkpointVersion.get())) {
                throw coverageMissing();
            }
            return;
        }
        if (!coverage(cutoff, siteDate)) {
            throw coverageMissing();
        }
        String aggregationVersion = requiredAggregationVersion(siteDate);
        String preparedVersion = jdbc.sql("""
                        select portfolio.prepare_analytics_retention_checkpoint(
                            :siteDate, :cutoff
                        )
                        """)
                .param("siteDate", siteDate, Types.DATE)
                .param("cutoff", cutoff, Types.TIMESTAMP_WITH_TIMEZONE)
                .query(String.class)
                .single();
        if (!aggregationVersion.equals(preparedVersion)) {
            throw failure();
        }
    }

    private boolean hasCheckpointBaseRows(
            LocalDate siteDate, String aggregationVersion) {
        return jdbc.sql("""
                        with required(metric, event_type) as (
                            values
                                ('PV'::varchar, 'PAGE_VIEW'::varchar),
                                ('DAILY_UV'::varchar, 'PAGE_VIEW'::varchar),
                                ('EVENT_COUNT'::varchar, 'PAGE_VIEW'::varchar),
                                ('EVENT_COUNT'::varchar, 'PROJECT_VIEW'::varchar),
                                ('EVENT_COUNT'::varchar, 'RESUME_DOWNLOAD'::varchar),
                                ('EVENT_COUNT'::varchar, 'DEMO_DOWNLOAD'::varchar),
                                ('EVENT_COUNT'::varchar, 'OUTBOUND_CLICK'::varchar)
                        )
                        select count(*)=7
                        from portfolio.analytics_daily daily
                        join required required_metric
                          on required_metric.metric=daily.metric
                         and required_metric.event_type=daily.event_type
                        where daily.site_date=:siteDate
                          and daily.dimension='ALL'
                          and daily.dimension_value='(all)'
                          and daily.aggregation_version=:aggregationVersion
                        """)
                .param("siteDate", siteDate, Types.DATE)
                .param("aggregationVersion", aggregationVersion, Types.VARCHAR)
                .query(Boolean.class)
                .single();
    }

    private String requiredAggregationVersion(LocalDate siteDate) {
        return jdbc.sql("""
                        with required(metric, event_type) as (
                            values
                                ('PV'::varchar, 'PAGE_VIEW'::varchar),
                                ('DAILY_UV'::varchar, 'PAGE_VIEW'::varchar),
                                ('EVENT_COUNT'::varchar, 'PAGE_VIEW'::varchar),
                                ('EVENT_COUNT'::varchar, 'PROJECT_VIEW'::varchar),
                                ('EVENT_COUNT'::varchar, 'RESUME_DOWNLOAD'::varchar),
                                ('EVENT_COUNT'::varchar, 'DEMO_DOWNLOAD'::varchar),
                                ('EVENT_COUNT'::varchar, 'OUTBOUND_CLICK'::varchar)
                        )
                        select min(daily.aggregation_version)
                        from portfolio.analytics_daily daily
                        join required required_metric
                          on required_metric.metric=daily.metric
                         and required_metric.event_type=daily.event_type
                        where daily.site_date=:siteDate
                          and daily.dimension='ALL'
                          and daily.dimension_value='(all)'
                        """)
                .param("siteDate", siteDate, Types.DATE)
                .query(String.class)
                .single();
    }

    private boolean coverage(
            OffsetDateTime cutoff,
            LocalDate onlyDate) {
        return firstMissingCoverage(cutoff, onlyDate, null).isEmpty();
    }

    private Optional<LocalDate> firstMissingCoverage(
            OffsetDateTime cutoff,
            LocalDate onlyDate,
            LocalDate earliestDate) {
        String datePredicate = (onlyDate == null
                ? ""
                : " and event.site_date=:onlyDate")
                + (earliestDate == null
                        ? ""
                        : " and event.site_date>=:earliestDate");
        JdbcClient.StatementSpec statement = jdbc.sql("""
                        with candidate_dates as materialized (
                            select distinct event.site_date
                            from portfolio.analytics_event event
                            where event.received_at < :cutoff
                        """ + datePredicate + """
                        ), required(metric, event_type) as (
                            values
                                ('PV'::varchar, 'PAGE_VIEW'::varchar),
                                ('DAILY_UV'::varchar, 'PAGE_VIEW'::varchar),
                                ('EVENT_COUNT'::varchar, 'PAGE_VIEW'::varchar),
                                ('EVENT_COUNT'::varchar, 'PROJECT_VIEW'::varchar),
                                ('EVENT_COUNT'::varchar, 'RESUME_DOWNLOAD'::varchar),
                                ('EVENT_COUNT'::varchar, 'DEMO_DOWNLOAD'::varchar),
                                ('EVENT_COUNT'::varchar, 'OUTBOUND_CLICK'::varchar)
                        ), raw_event_counts as (
                            select event.site_date,
                                   event.event_type,
                                   count(*)::bigint metric_count
                            from portfolio.analytics_event event
                            join candidate_dates candidate
                              on candidate.site_date=event.site_date
                            group by event.site_date, event.event_type
                        ), raw_page_counts as (
                            select event.site_date,
                                   count(*)::bigint page_views,
                                   count(distinct event.visitor_day_key)::bigint daily_uv
                            from portfolio.analytics_event event
                            join candidate_dates candidate
                              on candidate.site_date=event.site_date
                            where event.event_type='PAGE_VIEW'
                            group by event.site_date
                        )
                        select candidate.site_date
                        from candidate_dates candidate
                        where (
                            not exists (
                                select 1
                                from portfolio.analytics_retention_checkpoint checkpoint
                                where checkpoint.site_date=candidate.site_date
                            )
                            and not portfolio.analytics_date_has_exact_aggregate_coverage(
                                candidate.site_date
                            )
                        ) or exists (
                            select 1
                            from required required_metric
                            where not exists (
                                select 1
                                from portfolio.analytics_daily daily
                                where daily.site_date=candidate.site_date
                                  and daily.metric=required_metric.metric
                                  and daily.event_type=required_metric.event_type
                                  and daily.dimension='ALL'
                                  and daily.dimension_value='(all)'
                            )
                        ) or 1 <> (
                            select count(distinct daily.aggregation_version)
                            from portfolio.analytics_daily daily
                            join required required_metric
                              on required_metric.metric=daily.metric
                             and required_metric.event_type=daily.event_type
                            where daily.site_date=candidate.site_date
                              and daily.dimension='ALL'
                              and daily.dimension_value='(all)'
                        ) or exists (
                            select 1
                            from raw_event_counts raw
                            left join portfolio.analytics_daily daily
                              on daily.site_date=raw.site_date
                             and daily.metric='EVENT_COUNT'
                             and daily.event_type=raw.event_type
                             and daily.dimension='ALL'
                             and daily.dimension_value='(all)'
                            where raw.site_date=candidate.site_date
                              and (daily.metric_count is null
                               or daily.metric_count < raw.metric_count
                              )
                        ) or exists (
                            select 1
                            from raw_page_counts raw
                            left join portfolio.analytics_daily pv
                              on pv.site_date=raw.site_date
                             and pv.metric='PV'
                             and pv.event_type='PAGE_VIEW'
                             and pv.dimension='ALL'
                             and pv.dimension_value='(all)'
                            left join portfolio.analytics_daily uv
                              on uv.site_date=raw.site_date
                             and uv.metric='DAILY_UV'
                             and uv.event_type='PAGE_VIEW'
                             and uv.dimension='ALL'
                             and uv.dimension_value='(all)'
                            where raw.site_date=candidate.site_date
                              and (pv.metric_count is null
                               or pv.metric_count < raw.page_views
                               or uv.metric_count is null
                               or uv.metric_count < raw.daily_uv
                              )
                        )
                        order by candidate.site_date
                        limit 1
                        """)
                .param("cutoff", cutoff, Types.TIMESTAMP_WITH_TIMEZONE);
        if (onlyDate != null) {
            statement = statement.param("onlyDate", onlyDate, Types.DATE);
        }
        if (earliestDate != null) {
            statement = statement.param("earliestDate", earliestDate, Types.DATE);
        }
        return statement.query(LocalDate.class).optional();
    }

    private void setLocalLockTimeout() {
        jdbc.sql("select pg_catalog.set_config('lock_timeout', '2s', true)")
                .query(String.class)
                .single();
    }

    private void acquireDateLock(LocalDate siteDate) {
        jdbc.sql("""
                        select 1
                        from (
                            select pg_catalog.pg_advisory_xact_lock(:lockKey)
                        ) locked
                        """)
                .param("lockKey", AnalyticsLockKeys.date(siteDate), Types.BIGINT)
                .query(Integer.class)
                .single();
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

    private static OffsetDateTime timestamp(Instant value) {
        return OffsetDateTime.ofInstant(
                Objects.requireNonNull(value, "analytics retention cutoff is required"),
                ZoneOffset.UTC);
    }

    private static void requireBatchSize(int value) {
        if (value < 1 || value > MAXIMUM_BATCH_SIZE) {
            throw new IllegalArgumentException("analytics retention batch size is invalid");
        }
    }

    private static boolean isCoverageMissing(RuntimeException exception) {
        return exception.getClass() == IllegalStateException.class
                && COVERAGE_MISSING.equals(exception.getMessage())
                && exception.getCause() == null;
    }

    private static IllegalStateException coverageMissing() {
        return new IllegalStateException(COVERAGE_MISSING);
    }

    private static IllegalStateException failure() {
        return new IllegalStateException("ANALYTICS_RETENTION_REPOSITORY_FAILED");
    }
}
