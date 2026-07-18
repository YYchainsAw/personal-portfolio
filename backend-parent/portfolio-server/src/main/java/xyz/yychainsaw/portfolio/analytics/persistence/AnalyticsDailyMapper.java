package xyz.yychainsaw.portfolio.analytics.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import xyz.yychainsaw.portfolio.analytics.domain.AnalyticsEventType;

@Repository
public class AnalyticsDailyMapper {
    private static final RowMapper<AnalyticsDailyRecord> ROW_MAPPER =
            AnalyticsDailyMapper::map;
    private static final String COLUMNS = """
            site_date, metric, event_type, dimension, dimension_value,
            metric_count, aggregation_version, updated_at
            """;

    private final JdbcClient jdbc;

    public AnalyticsDailyMapper(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "analytics daily JDBC client is required");
    }

    public void acquireAggregationLocks(LocalDate siteDate, String aggregationVersion) {
        requireTransaction();
        setLocalLockTimeout();
        acquire(AnalyticsLockKeys.date(siteDate));
        acquire(AnalyticsLockKeys.version(siteDate, aggregationVersion));
    }

    public boolean hasRetentionCheckpoint(LocalDate siteDate) {
        requireTransaction();
        return jdbc.sql("""
                        select exists (
                            select 1
                            from portfolio.analytics_retention_checkpoint checkpoint
                            where checkpoint.site_date=:siteDate
                        )
                        """)
                .param("siteDate", Objects.requireNonNull(siteDate), Types.DATE)
                .query(Boolean.class)
                .single();
    }

    public int deleteDate(LocalDate siteDate) {
        return jdbc.sql("""
                        delete from portfolio.analytics_daily
                        where site_date=:siteDate
                        """)
                .param("siteDate", Objects.requireNonNull(siteDate), Types.DATE)
                .update();
    }

    public AggregationCounts insertAggregates(
            LocalDate siteDate, String aggregationVersion) {
        Objects.requireNonNull(siteDate, "analytics aggregation date is required");
        requireVersion(aggregationVersion);
        return jdbc.sql("""
                        with day_events as materialized (
                            select event_type,
                                   page_key,
                                   project_id,
                                   coalesce(referrer_domain, '(none)') referrer_domain,
                                   device_class,
                                   locale,
                                   visitor_day_key
                            from portfolio.analytics_event
                            where site_date=:siteDate
                        ), event_types(event_type) as (
                            values
                                ('PAGE_VIEW'::varchar),
                                ('PROJECT_VIEW'::varchar),
                                ('RESUME_DOWNLOAD'::varchar),
                                ('DEMO_DOWNLOAD'::varchar),
                                ('OUTBOUND_CLICK'::varchar)
                        ), all_rows(metric, event_type, dimension,
                                   dimension_value, metric_count) as (
                            select 'PV'::varchar,
                                   'PAGE_VIEW'::varchar,
                                   'ALL'::varchar,
                                   '(all)'::varchar,
                                   count(*)::bigint
                            from day_events
                            where event_type='PAGE_VIEW'
                            union all
                            select 'DAILY_UV'::varchar,
                                   'PAGE_VIEW'::varchar,
                                   'ALL'::varchar,
                                   '(all)'::varchar,
                                   count(distinct visitor_day_key)::bigint
                            from day_events
                            where event_type='PAGE_VIEW'
                            union all
                            select 'EVENT_COUNT'::varchar,
                                   types.event_type,
                                   'ALL'::varchar,
                                   '(all)'::varchar,
                                   count(events.event_type)::bigint
                            from event_types types
                            left join day_events events
                              on events.event_type=types.event_type
                            group by types.event_type
                        ), expanded as materialized (
                            select event.event_type,
                                   event.visitor_day_key,
                                   value.dimension,
                                   value.dimension_value
                            from day_events event
                            cross join lateral (
                                values
                                    ('PAGE'::varchar, event.page_key::varchar),
                                    ('REFERRER'::varchar,
                                        event.referrer_domain::varchar),
                                    ('DEVICE'::varchar, event.device_class::varchar),
                                    ('LOCALE'::varchar, event.locale::varchar)
                            ) value(dimension, dimension_value)
                            union all
                            select event_type,
                                   visitor_day_key,
                                   'PROJECT'::varchar,
                                   project_id::varchar
                            from day_events
                            where project_id is not null
                        ), dimension_rows(metric, event_type, dimension,
                                          dimension_value, metric_count) as (
                            select 'EVENT_COUNT'::varchar,
                                   event_type,
                                   dimension,
                                   dimension_value,
                                   count(*)::bigint
                            from expanded
                            group by event_type, dimension, dimension_value
                            union all
                            select 'PV'::varchar,
                                   'PAGE_VIEW'::varchar,
                                   dimension,
                                   dimension_value,
                                   count(*)::bigint
                            from expanded
                            where event_type='PAGE_VIEW'
                            group by dimension, dimension_value
                            union all
                            select 'DAILY_UV'::varchar,
                                   'PAGE_VIEW'::varchar,
                                   dimension,
                                   dimension_value,
                                   count(distinct visitor_day_key)::bigint
                            from expanded
                            where event_type='PAGE_VIEW'
                            group by dimension, dimension_value
                        ), metrics as (
                            select * from all_rows
                            union all
                            select * from dimension_rows
                        ), inserted as (
                            insert into portfolio.analytics_daily(
                                site_date,
                                metric,
                                event_type,
                                dimension,
                                dimension_value,
                                metric_count,
                                aggregation_version
                            )
                            select :siteDate,
                                   metric,
                                   event_type,
                                   dimension,
                                   dimension_value,
                                   metric_count,
                                   :aggregationVersion
                            from metrics
                            order by metric, event_type, dimension, dimension_value
                            on conflict (
                                site_date, metric, event_type,
                                dimension, dimension_value
                            ) do update
                            set metric_count=excluded.metric_count,
                                aggregation_version=excluded.aggregation_version
                            returning 1
                        )
                        select (select count(*) from day_events) input_count,
                               (select count(*) from inserted) output_count
                        """)
                .param("siteDate", siteDate, Types.DATE)
                .param("aggregationVersion", aggregationVersion, Types.VARCHAR)
                .query((row, rowNumber) -> new AggregationCounts(
                        row.getLong("input_count"),
                        row.getLong("output_count")))
                .single();
    }

    public List<AnalyticsDailyRecord> findByDate(LocalDate siteDate) {
        return jdbc.sql("select " + COLUMNS + """
                        from portfolio.analytics_daily
                        where site_date=:siteDate
                        order by metric, event_type, dimension, dimension_value
                        """)
                .param("siteDate", Objects.requireNonNull(siteDate), Types.DATE)
                .query(ROW_MAPPER)
                .list();
    }

    private void setLocalLockTimeout() {
        jdbc.sql("select pg_catalog.set_config('lock_timeout', '2s', true)")
                .query(String.class)
                .single();
    }

    private void acquire(long lockKey) {
        jdbc.sql("""
                        select 1
                        from (
                            select pg_catalog.pg_advisory_xact_lock(:lockKey)
                        ) locked
                        """)
                .param("lockKey", lockKey, Types.BIGINT)
                .query(Integer.class)
                .single();
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("analytics daily lock requires a transaction");
        }
    }

    private static void requireVersion(String value) {
        if (value == null || !value.matches("[a-z0-9][a-z0-9-]{0,31}")) {
            throw new IllegalArgumentException("analytics aggregation version is invalid");
        }
    }

    private static AnalyticsDailyRecord map(ResultSet row, int rowNumber)
            throws SQLException {
        return new AnalyticsDailyRecord(
                row.getObject("site_date", LocalDate.class),
                row.getString("metric"),
                AnalyticsEventType.valueOf(row.getString("event_type")),
                row.getString("dimension"),
                row.getString("dimension_value"),
                row.getLong("metric_count"),
                row.getString("aggregation_version"),
                row.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    public record AggregationCounts(long inputCount, long outputCount) {
        public AggregationCounts {
            if (inputCount < 0 || outputCount < 0) {
                throw new IllegalArgumentException("analytics aggregation counts are invalid");
            }
        }
    }
}
