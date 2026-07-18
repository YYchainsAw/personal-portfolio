package xyz.yychainsaw.portfolio.analytics.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import xyz.yychainsaw.portfolio.analytics.domain.AnalyticsEventType;
import xyz.yychainsaw.portfolio.analytics.domain.DeviceClass;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;

@Repository
public class AnalyticsEventMapper {
    private static final Pattern DAY_KEY = Pattern.compile("[0-9a-f]{64}");
    private static final String COLUMNS = """
            id, client_event_id, site_date, received_at, visitor_day_key,
            session_day_key, event_type, page_key, project_id, referrer_domain,
            device_class, locale, rules_version, created_at
            """;
    private static final RowMapper<AnalyticsEventRecord> ROW_MAPPER =
            AnalyticsEventMapper::map;

    private final JdbcClient jdbc;

    public AnalyticsEventMapper(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "analytics JDBC client is required");
    }

    public void acquireDedupeLock(long lockKey) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("analytics dedupe lock requires a transaction");
        }
        jdbc.sql("select pg_catalog.set_config('lock_timeout', '2s', true)")
                .query(String.class)
                .single();
        jdbc.sql("select 1 from (select pg_catalog.pg_advisory_xact_lock(:lockKey)) locked")
                .param("lockKey", lockKey, Types.BIGINT)
                .query(Integer.class)
                .single();
    }

    public boolean existsRecentTuple(AnalyticsEventRecord record, Instant cutoff) {
        Objects.requireNonNull(record, "analytics event is required");
        Objects.requireNonNull(cutoff, "analytics dedupe cutoff is required");
        requireDayKey(record.sessionDayKey());
        return jdbc.sql("""
                        select exists (
                            select 1
                            from portfolio.analytics_event
                            where session_day_key=:sessionDayKey
                              and event_type=:eventType
                              and page_key=:pageKey
                              and project_id is not distinct from :projectId
                              and received_at >= :cutoff
                        )
                        """)
                .param("sessionDayKey", record.sessionDayKey(), Types.CHAR)
                .param("eventType", record.eventType().name(), Types.VARCHAR)
                .param("pageKey", record.pageKey(), Types.VARCHAR)
                .param("projectId", record.projectId(), Types.OTHER)
                .param("cutoff", toOffsetDateTime(cutoff), Types.TIMESTAMP_WITH_TIMEZONE)
                .query(Boolean.class)
                .single();
    }

    public int insertIgnoreClientRetry(AnalyticsEventRecord record) {
        Objects.requireNonNull(record, "analytics event is required");
        return jdbc.sql("""
                        insert into portfolio.analytics_event(
                            id, client_event_id, site_date, received_at,
                            visitor_day_key, session_day_key, event_type, page_key,
                            project_id, referrer_domain, device_class, locale,
                            rules_version, created_at
                        ) values (
                            :id, :clientEventId, :siteDate, :receivedAt,
                            :visitorDayKey, :sessionDayKey, :eventType, :pageKey,
                            :projectId, :referrerDomain, :deviceClass, :locale,
                            :rulesVersion, :createdAt
                        )
                        on conflict (client_event_id) do nothing
                        """)
                .param("id", record.id(), Types.OTHER)
                .param("clientEventId", record.clientEventId(), Types.OTHER)
                .param("siteDate", record.siteDate(), Types.DATE)
                .param("receivedAt", toOffsetDateTime(record.receivedAt()),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .param("visitorDayKey", record.visitorDayKey(), Types.CHAR)
                .param("sessionDayKey", record.sessionDayKey(), Types.CHAR)
                .param("eventType", record.eventType().name(), Types.VARCHAR)
                .param("pageKey", record.pageKey(), Types.VARCHAR)
                .param("projectId", record.projectId(), Types.OTHER)
                .param("referrerDomain", record.referrerDomain(), Types.VARCHAR)
                .param("deviceClass", record.deviceClass().name(), Types.VARCHAR)
                .param("locale", record.locale().value(), Types.VARCHAR)
                .param("rulesVersion", record.rulesVersion(), Types.VARCHAR)
                .param("createdAt", toOffsetDateTime(record.createdAt()),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    public List<AnalyticsEventRecord> findAll() {
        return jdbc.sql("select " + COLUMNS
                        + " from portfolio.analytics_event order by received_at, id")
                .query(ROW_MAPPER)
                .list();
    }

    public long count() {
        return jdbc.sql("select count(*) from portfolio.analytics_event")
                .query(Long.class)
                .single();
    }

    public int deleteAll() {
        return jdbc.sql("delete from portfolio.analytics_event").update();
    }

    private static void requireDayKey(String value) {
        if (value == null || !DAY_KEY.matcher(value).matches()) {
            throw new IllegalArgumentException("analytics session day key is invalid");
        }
    }

    private static OffsetDateTime toOffsetDateTime(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static AnalyticsEventRecord map(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new AnalyticsEventRecord(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("client_event_id", UUID.class),
                resultSet.getObject("site_date", LocalDate.class),
                resultSet.getObject("received_at", OffsetDateTime.class).toInstant(),
                resultSet.getString("visitor_day_key"),
                resultSet.getString("session_day_key"),
                AnalyticsEventType.valueOf(resultSet.getString("event_type")),
                resultSet.getString("page_key"),
                resultSet.getObject("project_id", UUID.class),
                resultSet.getString("referrer_domain"),
                DeviceClass.valueOf(resultSet.getString("device_class")),
                LocaleCode.from(resultSet.getString("locale")),
                resultSet.getString("rules_version"),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
