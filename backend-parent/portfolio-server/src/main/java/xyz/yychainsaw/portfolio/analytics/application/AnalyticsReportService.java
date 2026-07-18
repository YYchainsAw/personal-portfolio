package xyz.yychainsaw.portfolio.analytics.application;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.publishing.application.ProjectLabelQuery;

@Service
public class AnalyticsReportService {
    private static final String SITE_ZONE = "Asia/Hong_Kong";
    private static final Comparator<ResolvedBreakdown> BREAKDOWN_ORDER =
            Comparator.comparingLong(ResolvedBreakdown::value)
                    .reversed()
                    .thenComparing(ResolvedBreakdown::label)
                    .thenComparing(ResolvedBreakdown::originalKey);

    private final JdbcClient jdbc;
    private final ObjectProvider<ProjectLabelQuery> projectLabels;

    public AnalyticsReportService(
            JdbcClient jdbc, ObjectProvider<ProjectLabelQuery> projectLabels) {
        this.jdbc = Objects.requireNonNull(jdbc, "analytics report JDBC client is required");
        this.projectLabels = Objects.requireNonNull(
                projectLabels, "analytics project label provider is required");
    }

    @Transactional(readOnly = true)
    public AnalyticsSummary summary(AnalyticsQuery query) {
        requireKind(query, AnalyticsQuery.Kind.SUMMARY);
        try {
            SummaryRow row = jdbc.sql("""
                            with required(metric, event_type) as (
                                values
                                    ('PV'::varchar, 'PAGE_VIEW'::varchar),
                                    ('DAILY_UV'::varchar, 'PAGE_VIEW'::varchar),
                                    ('EVENT_COUNT'::varchar, 'PAGE_VIEW'::varchar),
                                    ('EVENT_COUNT'::varchar, 'PROJECT_VIEW'::varchar),
                                    ('EVENT_COUNT'::varchar, 'RESUME_DOWNLOAD'::varchar),
                                    ('EVENT_COUNT'::varchar, 'DEMO_DOWNLOAD'::varchar),
                                    ('EVENT_COUNT'::varchar, 'OUTBOUND_CLICK'::varchar)
                            ), daily as (
                                select aggregate.*
                                from portfolio.analytics_daily aggregate
                                join required required_metric
                                  on required_metric.metric=aggregate.metric
                                 and required_metric.event_type=aggregate.event_type
                                where aggregate.site_date between :from and :to
                                  and aggregate.dimension='ALL'
                                  and aggregate.dimension_value='(all)'
                            )
                            select
                                coalesce(sum(metric_count) filter (
                                    where metric='PV'
                                      and event_type='PAGE_VIEW'
                                ), 0) page_views,
                                coalesce(sum(metric_count) filter (
                                    where metric='DAILY_UV'
                                      and event_type='PAGE_VIEW'
                                ), 0) daily_unique_visitors,
                                coalesce(sum(metric_count) filter (
                                    where metric='EVENT_COUNT'
                                      and event_type='PROJECT_VIEW'
                                ), 0) project_views,
                                coalesce(sum(metric_count) filter (
                                    where metric='EVENT_COUNT'
                                      and event_type='RESUME_DOWNLOAD'
                                ), 0) resume_downloads,
                                coalesce(sum(metric_count) filter (
                                    where metric='EVENT_COUNT'
                                      and event_type='DEMO_DOWNLOAD'
                                ), 0) demo_downloads,
                                coalesce(sum(metric_count) filter (
                                    where metric='EVENT_COUNT'
                                      and event_type='OUTBOUND_CLICK'
                                ), 0) outbound_clicks,
                                case
                                    when count(*)=:expectedRows
                                     and count(distinct site_date)=:expectedDays
                                     and count(distinct (
                                         site_date, aggregation_version
                                     ))=:expectedDays
                                    then max(updated_at)
                                    else null
                                end data_complete_through
                            from daily
                            """)
                    .param("from", query.from(), Types.DATE)
                    .param("to", query.to(), Types.DATE)
                    .param("expectedDays", inclusiveDays(query), Types.BIGINT)
                    .param("expectedRows", inclusiveDays(query) * 7, Types.BIGINT)
                    .query(AnalyticsReportService::summaryRow)
                    .single();
            return new AnalyticsSummary(
                    row.pageViews(),
                    row.dailyUniqueVisitors(),
                    row.projectViews(),
                    row.resumeDownloads(),
                    row.demoDownloads(),
                    row.outboundClicks(),
                    row.dataCompleteThrough(),
                    SITE_ZONE,
                    definitions(query.locale()));
        } catch (RuntimeException failure) {
            throw internal();
        }
    }

    @Transactional(readOnly = true)
    public List<AnalyticsPoint> timeseries(AnalyticsQuery query) {
        requireKind(query, AnalyticsQuery.Kind.TIMESERIES);
        try {
            List<DailyCount> rows = jdbc.sql("""
                            select site_date, sum(metric_count) metric_count
                            from portfolio.analytics_daily
                            where site_date between :from and :to
                              and metric=:metric
                              and event_type=:eventType
                              and dimension='ALL'
                              and dimension_value='(all)'
                            group by site_date
                            order by site_date
                            """)
                    .param("from", query.from(), Types.DATE)
                    .param("to", query.to(), Types.DATE)
                    .param("metric", query.metric().name(), Types.VARCHAR)
                    .param("eventType", query.eventType().name(), Types.VARCHAR)
                    .query(AnalyticsReportService::dailyCount)
                    .list();
            Map<LocalDate, Long> counts = new HashMap<>();
            rows.forEach(row -> counts.put(row.date(), row.value()));

            List<AnalyticsPoint> points = new ArrayList<>();
            for (LocalDate date = query.from(); !date.isAfter(query.to()); date = date.plusDays(1)) {
                points.add(new AnalyticsPoint(date, counts.getOrDefault(date, 0L)));
            }
            return List.copyOf(points);
        } catch (RuntimeException failure) {
            throw internal();
        }
    }

    @Transactional(readOnly = true)
    public List<AnalyticsBreakdownItem> breakdown(AnalyticsQuery query) {
        requireKind(query, AnalyticsQuery.Kind.BREAKDOWN);
        try {
            List<RawBreakdown> rows = query.dimension() == AnalyticsQuery.Dimension.PROJECT
                    ? projectBreakdownRows(query)
                    : boundedBreakdownRows(query);
            return resolve(rows, query.dimension()).stream()
                    .sorted(BREAKDOWN_ORDER)
                    .limit(query.limit())
                    .map(row -> new AnalyticsBreakdownItem(row.label(), row.value()))
                    .toList();
        } catch (RuntimeException failure) {
            throw internal();
        }
    }

    private List<RawBreakdown> boundedBreakdownRows(AnalyticsQuery query) {
        return jdbc.sql("""
                        select dimension_value, sum(metric_count) metric_count
                        from portfolio.analytics_daily
                        where site_date between :from and :to
                          and metric=:metric
                          and event_type=:eventType
                          and dimension=:dimension
                        group by dimension_value
                        order by metric_count desc,
                                 dimension_value collate "C" asc
                        limit :limit
                        """)
                .param("from", query.from(), Types.DATE)
                .param("to", query.to(), Types.DATE)
                .param("metric", query.metric().name(), Types.VARCHAR)
                .param("eventType", query.eventType().name(), Types.VARCHAR)
                .param("dimension", query.dimension().name(), Types.VARCHAR)
                .param("limit", query.limit(), Types.INTEGER)
                .query(AnalyticsReportService::rawBreakdown)
                .list();
    }

    private List<RawBreakdown> projectBreakdownRows(AnalyticsQuery query) {
        return jdbc.sql("""
                        select dimension_value, sum(metric_count) metric_count
                        from portfolio.analytics_daily
                        where site_date between :from and :to
                          and metric=:metric
                          and event_type=:eventType
                          and dimension='PROJECT'
                        group by dimension_value
                        """)
                .param("from", query.from(), Types.DATE)
                .param("to", query.to(), Types.DATE)
                .param("metric", query.metric().name(), Types.VARCHAR)
                .param("eventType", query.eventType().name(), Types.VARCHAR)
                .query(AnalyticsReportService::rawBreakdown)
                .list();
    }

    private List<ResolvedBreakdown> resolve(
            List<RawBreakdown> rows, AnalyticsQuery.Dimension dimension) {
        if (dimension != AnalyticsQuery.Dimension.PROJECT) {
            return rows.stream()
                    .map(row -> new ResolvedBreakdown(
                            row.dimensionValue(), row.dimensionValue(), row.value()))
                    .toList();
        }

        ProjectLabelQuery labels = projectLabels.getIfAvailable();
        if (labels == null) {
            throw new IllegalStateException("analytics project label query is unavailable");
        }

        Map<String, UUID> projectIds = new HashMap<>();
        Set<UUID> canonicalIds = new LinkedHashSet<>();
        for (RawBreakdown row : rows) {
            canonicalUuid(row.dimensionValue()).ifPresent(projectId -> {
                projectIds.put(row.dimensionValue(), projectId);
                canonicalIds.add(projectId);
            });
        }
        Map<UUID, String> chinese = projectTitles(
                labels, canonicalIds, LocaleCode.ZH_CN);
        Set<UUID> missingChinese = new LinkedHashSet<>(canonicalIds);
        missingChinese.removeAll(chinese.keySet());
        Map<UUID, String> english = projectTitles(
                labels, missingChinese, LocaleCode.EN);

        return rows.stream()
                .map(row -> {
                    UUID projectId = projectIds.get(row.dimensionValue());
                    String label = projectId == null
                            ? row.dimensionValue()
                            : Optional.ofNullable(chinese.get(projectId))
                                    .orElseGet(() -> english.getOrDefault(
                                            projectId, row.dimensionValue()));
                    return new ResolvedBreakdown(
                            row.dimensionValue(), label, row.value());
                })
                .toList();
    }

    private static Map<UUID, String> projectTitles(
            ProjectLabelQuery labels, Set<UUID> projectIds, LocaleCode locale) {
        Map<UUID, String> found = Objects.requireNonNull(
                labels.findProjectTitles(Set.copyOf(projectIds), locale),
                "project label query returned no map");
        LinkedHashMap<UUID, String> normalized = new LinkedHashMap<>();
        found.forEach((projectId, title) -> normalizedTitle(title)
                .ifPresent(value -> normalized.put(
                        Objects.requireNonNull(projectId, "project label id is required"),
                        value)));
        return Map.copyOf(normalized);
    }

    private static Optional<UUID> canonicalUuid(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            return parsed.toString().equals(value)
                    ? Optional.of(parsed)
                    : Optional.empty();
        } catch (IllegalArgumentException invalidUuid) {
            return Optional.empty();
        }
    }

    private static Optional<String> normalizedTitle(String title) {
        return Optional.ofNullable(title)
                .filter(candidate -> !candidate.isBlank())
                .map(String::strip);
    }

    private static Map<String, String> definitions(LocaleCode locale) {
        LinkedHashMap<String, String> definitions = new LinkedHashMap<>();
        if (locale == LocaleCode.ZH_CN) {
            definitions.put(
                    "PV",
                    "页面浏览次数：每个通过同意、过滤和 10 秒去重的 PAGE_VIEW 事件计 1 次。");
            definitions.put(
                    "DAILY_UV",
                    "匿名日 UV：在 Asia/Hong_Kong 自然日内按匿名访客日键去重；"
                            + "跨日期范围按每日 UV 求和，不代表跨日、跨设备的独立人数。");
            definitions.put(
                    "EVENT_COUNT",
                    "事件次数：每个通过同意、过滤和 10 秒去重的指定事件计 1 次。");
        } else {
            definitions.put(
                    "PV",
                    "Page views: each consented PAGE_VIEW event that passes filtering "
                            + "and 10-second deduplication counts once.");
            definitions.put(
                    "DAILY_UV",
                    "Anonymous daily UV: distinct anonymous visitor-day keys within each "
                            + "Asia/Hong_Kong calendar day, summed across the requested dates; "
                            + "not unique people across days or devices.");
            definitions.put(
                    "EVENT_COUNT",
                    "Event count: each consented event of the selected type that passes "
                            + "filtering and 10-second deduplication counts once.");
        }
        return definitions;
    }

    private static SummaryRow summaryRow(ResultSet row, int rowNumber)
            throws SQLException {
        OffsetDateTime dataCompleteThrough =
                row.getObject("data_complete_through", OffsetDateTime.class);
        return new SummaryRow(
                exactLong(row, "page_views"),
                exactLong(row, "daily_unique_visitors"),
                exactLong(row, "project_views"),
                exactLong(row, "resume_downloads"),
                exactLong(row, "demo_downloads"),
                exactLong(row, "outbound_clicks"),
                dataCompleteThrough == null ? null : dataCompleteThrough.toInstant());
    }

    private static DailyCount dailyCount(ResultSet row, int rowNumber)
            throws SQLException {
        return new DailyCount(
                row.getObject("site_date", LocalDate.class),
                exactLong(row, "metric_count"));
    }

    private static RawBreakdown rawBreakdown(ResultSet row, int rowNumber)
            throws SQLException {
        return new RawBreakdown(
                row.getString("dimension_value"),
                exactLong(row, "metric_count"));
    }

    private static long exactLong(ResultSet row, String column) throws SQLException {
        BigDecimal value = Objects.requireNonNull(
                row.getBigDecimal(column), "analytics aggregate is required");
        return value.longValueExact();
    }

    private static long inclusiveDays(AnalyticsQuery query) {
        return ChronoUnit.DAYS.between(query.from(), query.to()) + 1;
    }

    private static void requireKind(
            AnalyticsQuery query, AnalyticsQuery.Kind expected) {
        if (Objects.requireNonNull(query, "analytics query is required").kind()
                != expected) {
            throw new IllegalArgumentException("analytics query kind is invalid");
        }
    }

    private static DomainException internal() {
        return new DomainException(
                "INTERNAL_ERROR", HttpStatus.INTERNAL_SERVER_ERROR, Map.of());
    }

    private record SummaryRow(
            long pageViews,
            long dailyUniqueVisitors,
            long projectViews,
            long resumeDownloads,
            long demoDownloads,
            long outboundClicks,
            Instant dataCompleteThrough) {}

    private record DailyCount(LocalDate date, long value) {}

    private record RawBreakdown(String dimensionValue, long value) {}

    private record ResolvedBreakdown(String originalKey, String label, long value) {}
}
