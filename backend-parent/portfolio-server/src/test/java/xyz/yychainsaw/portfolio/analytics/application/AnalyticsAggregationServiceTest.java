package xyz.yychainsaw.portfolio.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import xyz.yychainsaw.portfolio.analytics.domain.AnalyticsEventType;
import xyz.yychainsaw.portfolio.analytics.domain.DeviceClass;
import xyz.yychainsaw.portfolio.analytics.persistence.AnalyticsDailyMapper;
import xyz.yychainsaw.portfolio.analytics.persistence.AnalyticsDailyRecord;
import xyz.yychainsaw.portfolio.analytics.persistence.AnalyticsEventMapper;
import xyz.yychainsaw.portfolio.analytics.persistence.AnalyticsEventRecord;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
@Isolated
@Import(AnalyticsAggregationServiceTest.FixedClockConfiguration.class)
class AnalyticsAggregationServiceTest extends PostgresIntegrationTestBase {
    private static final LocalDate SITE_DATE = LocalDate.parse("2026-07-14");
    private static final UUID PROJECT_ID =
            UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final String VISITOR_A = "a".repeat(64);
    private static final String VISITOR_B = "b".repeat(64);
    private static final String SESSION_A = "c".repeat(64);
    private static final String SESSION_B = "d".repeat(64);

    @Autowired AnalyticsAggregationService service;
    @Autowired AnalyticsDailyMapper dailyMapper;
    @Autowired AnalyticsEventMapper eventMapper;
    @Autowired AnalyticsEventDeduplicator deduplicator;
    @Autowired AnalyticsMaintenanceRunRepository maintenanceRuns;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clearBefore() {
        clearAnalytics();
    }

    @AfterEach
    void clearAfter() {
        clearAnalytics();
    }

    @Test
    void rebuildsEveryDeclaredDimensionAndMetricFromOneFixedSiteDay() {
        insertFixedFixture();

        AnalyticsAggregationService.AggregationResult result = service.rebuild(SITE_DATE);

        assertThat(result.inputCount()).isEqualTo(8);
        assertThat(result.outputCount()).isEqualTo(56);
        List<AnalyticsDailyRecord> rows = dailyMapper.findByDate(SITE_DATE);
        assertThat(rows).hasSize(56).allSatisfy(row -> {
            assertThat(row.siteDate()).isEqualTo(SITE_DATE);
            assertThat(row.aggregationVersion()).isEqualTo("analytics-rules-v1");
            assertThat(row.updatedAt()).isNotNull();
        });
        assertThat(rows.stream().map(AnalyticsAggregationServiceTest::metricKey).toList())
                .containsExactlyInAnyOrderElementsOf(expectedMetricKeys());
    }

    @Test
    void repeatedRebuildIsDateScopedAndCountEquivalent() {
        insertFixedFixture();
        LocalDate otherDate = SITE_DATE.minusDays(1);
        insertDailySentinel(otherDate);

        AnalyticsAggregationService.AggregationResult first = service.rebuild(SITE_DATE);
        List<String> firstRows = dailyMapper.findByDate(SITE_DATE).stream()
                .map(AnalyticsAggregationServiceTest::metricKey)
                .toList();
        AnalyticsAggregationService.AggregationResult second = service.rebuild(SITE_DATE);
        List<String> secondRows = dailyMapper.findByDate(SITE_DATE).stream()
                .map(AnalyticsAggregationServiceTest::metricKey)
                .toList();

        assertThat(first).isEqualTo(second);
        assertThat(second).isEqualTo(new AnalyticsAggregationService.AggregationResult(8, 56));
        assertThat(secondRows).containsExactlyElementsOf(firstRows);
        assertThat(dailyMapper.findByDate(otherDate))
                .singleElement()
                .satisfies(row -> assertThat(row.metricCount()).isEqualTo(99));
    }

    @Test
    void anEmptyDateStillGetsStableAllDimensionZeroRows() {
        AnalyticsAggregationService.AggregationResult result = service.rebuild(SITE_DATE);

        assertThat(result).isEqualTo(new AnalyticsAggregationService.AggregationResult(0, 7));
        assertThat(dailyMapper.findByDate(SITE_DATE).stream()
                        .map(AnalyticsAggregationServiceTest::metricKey)
                        .toList())
                .containsExactlyInAnyOrder(
                        "DAILY_UV|PAGE_VIEW|ALL|(all)|0",
                        "PV|PAGE_VIEW|ALL|(all)|0",
                        "EVENT_COUNT|PAGE_VIEW|ALL|(all)|0",
                        "EVENT_COUNT|PROJECT_VIEW|ALL|(all)|0",
                        "EVENT_COUNT|RESUME_DOWNLOAD|ALL|(all)|0",
                        "EVENT_COUNT|DEMO_DOWNLOAD|ALL|(all)|0",
                        "EVENT_COUNT|OUTBOUND_CLICK|ALL|(all)|0");
    }

    @Test
    void nullReferrerUsesTheExplicitNoneDimension() {
        Instant receivedAt = Instant.parse("2026-07-14T08:00:00Z");
        assertThat(jdbc.sql("""
                        insert into portfolio.analytics_event(
                            id, client_event_id, site_date, received_at,
                            visitor_day_key, session_day_key, event_type, page_key,
                            project_id, referrer_domain, device_class, locale,
                            rules_version, created_at
                        ) values (
                            '42000000-0000-4000-8000-000000000001',
                            '42000000-0000-4000-8000-000000000002',
                            :siteDate, :receivedAt,
                            :visitorKey, :sessionKey, 'PAGE_VIEW', 'HOME',
                            null, null, 'DESKTOP', 'en',
                            'analytics-rules-v1', :receivedAt
                        )
                        """)
                .param("siteDate", SITE_DATE)
                .param("receivedAt", receivedAt.atOffset(ZoneOffset.UTC))
                .param("visitorKey", "1".repeat(64))
                .param("sessionKey", "2".repeat(64))
                .update()).isOne();

        service.rebuild(SITE_DATE);

        assertThat(dailyMapper.findByDate(SITE_DATE).stream()
                        .map(AnalyticsAggregationServiceTest::metricKey)
                        .toList())
                .contains(
                        "PV|PAGE_VIEW|REFERRER|(none)|1",
                        "DAILY_UV|PAGE_VIEW|REFERRER|(none)|1",
                        "EVENT_COUNT|PAGE_VIEW|REFERRER|(none)|1");
    }

    @Test
    void failedInsertRollsBackTheDateDeleteAndRedactsTheDatabaseFailure() {
        insertFixedFixture();
        insertDailySentinel(SITE_DATE);
        JdbcClient owner = migratorJdbc();
        owner.sql("""
                        create or replace function portfolio.analytics_test_reject_insert()
                        returns trigger
                        language plpgsql
                        as $$
                        begin
                            raise exception 'private analytics fixture failure';
                        end;
                        $$
                        """).update();
        owner.sql("""
                        create trigger analytics_test_reject_insert
                        before insert on portfolio.analytics_daily
                        for each statement
                        execute function portfolio.analytics_test_reject_insert()
                        """).update();
        try {
            assertThatThrownBy(() -> service.rebuild(SITE_DATE))
                    .isExactlyInstanceOf(IllegalStateException.class)
                    .hasMessage("ANALYTICS_AGGREGATION_FAILED")
                    .hasMessageNotContaining("private")
                    .hasNoCause();
        } finally {
            owner.sql("""
                            drop trigger if exists analytics_test_reject_insert
                            on portfolio.analytics_daily
                            """).update();
            owner.sql("""
                            drop function if exists portfolio.analytics_test_reject_insert()
                            """).update();
        }

        assertThat(dailyMapper.findByDate(SITE_DATE))
                .singleElement()
                .satisfies(row -> assertThat(row.metricCount()).isEqualTo(99));
    }

    @Test
    void concurrentRebuildsSerializeToOneEquivalentDateSnapshot() throws Exception {
        insertFixedFixture();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<AnalyticsAggregationService.AggregationResult> left = executor.submit(
                    () -> rebuildTogether(ready, start));
            Future<AnalyticsAggregationService.AggregationResult> right = executor.submit(
                    () -> rebuildTogether(ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(left.get(15, TimeUnit.SECONDS))
                    .isEqualTo(new AnalyticsAggregationService.AggregationResult(8, 56));
            assertThat(right.get(15, TimeUnit.SECONDS))
                    .isEqualTo(new AnalyticsAggregationService.AggregationResult(8, 56));
            List<AnalyticsDailyRecord> rows = dailyMapper.findByDate(SITE_DATE);
            assertThat(rows).hasSize(56).allSatisfy(row -> assertThat(
                    row.aggregationVersion()).isEqualTo("analytics-rules-v1"));
            assertThat(rows.stream().map(AnalyticsAggregationServiceTest::metricKey).toList())
                    .containsExactlyInAnyOrderElementsOf(expectedMetricKeys());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void aggregationHandlerRecordsOnlyInputAndOutputCounts() {
        insertFixedFixture();
        UUID runId = UUID.fromString("95000000-0000-4000-8000-000000000005");
        AnalyticsAggregationJobHandler handler = new AnalyticsAggregationJobHandler(
                service,
                maintenanceRuns,
                new AnalyticsRules(),
                Clock.fixed(Instant.parse("2026-07-18T08:30:00Z"), ZoneOffset.UTC),
                () -> runId);

        handler.handle(new ObjectMapper().valueToTree(
                Map.of(
                        "siteDate", SITE_DATE.toString(),
                        "aggregationVersion", "analytics-rules-v1")));

        AggregationRun run = jdbc.sql("""
                        select status,
                               (details ->> 'input_count')::bigint input_count,
                               (details ->> 'output_count')::bigint output_count,
                               error_summary,
                               details::text details
                        from portfolio.maintenance_run
                        where id=:id
                        """)
                .param("id", runId)
                .query((row, number) -> new AggregationRun(
                        row.getString("status"),
                        row.getLong("input_count"),
                        row.getLong("output_count"),
                        row.getString("error_summary"),
                        row.getString("details")))
                .single();
        assertThat(handler.jobType()).isEqualTo("ANALYTICS_AGGREGATE");
        assertThat(run.status()).isEqualTo("SUCCEEDED");
        assertThat(run.inputCount()).isEqualTo(8);
        assertThat(run.outputCount()).isEqualTo(56);
        assertThat(run.errorSummary()).isNull();
        assertThat(run.details()).doesNotContain(VISITOR_A, VISITOR_B, SITE_DATE.toString());
    }

    private AnalyticsAggregationService.AggregationResult rebuildTogether(
            CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
        return service.rebuild(SITE_DATE);
    }

    private void insertFixedFixture() {
        insertEvent(1, "2026-07-14T01:00:00Z", VISITOR_A, SESSION_A,
                AnalyticsEventType.PAGE_VIEW, "HOME", null, "(direct)",
                DeviceClass.DESKTOP, LocaleCode.ZH_CN);
        insertEvent(2, "2026-07-14T01:01:00Z", VISITOR_A, SESSION_A,
                AnalyticsEventType.PAGE_VIEW, "HOME", null, "(direct)",
                DeviceClass.DESKTOP, LocaleCode.ZH_CN);
        insertEvent(3, "2026-07-14T02:00:00Z", VISITOR_B, SESSION_B,
                AnalyticsEventType.PAGE_VIEW, "PROJECT_DETAIL", PROJECT_ID, "github.com",
                DeviceClass.MOBILE, LocaleCode.EN);
        insertEvent(4, "2026-07-14T03:00:00Z", VISITOR_A, SESSION_A,
                AnalyticsEventType.PROJECT_VIEW, "PROJECT_DETAIL", PROJECT_ID, "(direct)",
                DeviceClass.DESKTOP, LocaleCode.ZH_CN);
        insertEvent(5, "2026-07-14T04:00:00Z", VISITOR_B, SESSION_B,
                AnalyticsEventType.PROJECT_VIEW, "PROJECT_DETAIL", PROJECT_ID, "github.com",
                DeviceClass.MOBILE, LocaleCode.EN);
        insertEvent(6, "2026-07-14T05:00:00Z", VISITOR_A, SESSION_A,
                AnalyticsEventType.RESUME_DOWNLOAD, "WORK", null, "(direct)",
                DeviceClass.DESKTOP, LocaleCode.ZH_CN);
        insertEvent(7, "2026-07-14T06:00:00Z", VISITOR_B, SESSION_B,
                AnalyticsEventType.DEMO_DOWNLOAD, "PROJECT_DETAIL", PROJECT_ID, "github.com",
                DeviceClass.MOBILE, LocaleCode.EN);
        insertEvent(8, "2026-07-14T07:00:00Z", VISITOR_B, SESSION_B,
                AnalyticsEventType.OUTBOUND_CLICK, "PROJECT_DETAIL", PROJECT_ID, "github.com",
                DeviceClass.MOBILE, LocaleCode.EN);
        Instant repeatedAt = Instant.parse("2026-07-14T01:00:05Z");
        assertThat(deduplicator.persist(new AnalyticsEventRecord(
                uuid(9, 1),
                uuid(9, 2),
                SITE_DATE,
                repeatedAt,
                VISITOR_A,
                SESSION_A,
                AnalyticsEventType.PAGE_VIEW,
                "HOME",
                null,
                "(direct)",
                DeviceClass.DESKTOP,
                LocaleCode.ZH_CN,
                "analytics-rules-v1",
                repeatedAt))).isFalse();
        assertThat(eventMapper.count()).isEqualTo(8);
    }

    private void insertEvent(
            int sequence,
            String receivedAt,
            String visitorKey,
            String sessionKey,
            AnalyticsEventType eventType,
            String pageKey,
            UUID projectId,
            String referrer,
            DeviceClass device,
            LocaleCode locale) {
        Instant received = Instant.parse(receivedAt);
        int inserted = eventMapper.insertIgnoreClientRetry(new AnalyticsEventRecord(
                uuid(sequence, 1),
                uuid(sequence, 2),
                SITE_DATE,
                received,
                visitorKey,
                sessionKey,
                eventType,
                pageKey,
                projectId,
                referrer,
                device,
                locale,
                "analytics-rules-v1",
                received));
        assertThat(inserted).isOne();
    }

    private void insertDailySentinel(LocalDate date) {
        assertThat(jdbc.sql("""
                        insert into portfolio.analytics_daily(
                            site_date, metric, event_type, dimension,
                            dimension_value, metric_count, aggregation_version
                        ) values (
                            :siteDate, 'PV', 'PAGE_VIEW', 'ALL',
                            '(all)', 99, 'analytics-rules-v1'
                        )
                        """)
                .param("siteDate", date)
                .update()).isOne();
    }

    private void clearAnalytics() {
        migratorJdbc()
                .sql("""
                        truncate table
                            portfolio.analytics_event,
                            portfolio.analytics_retention_checkpoint
                        """)
                .update();
        jdbc.sql("delete from portfolio.analytics_daily").update();
        migratorJdbc()
                .sql("delete from portfolio.maintenance_run where run_type like 'ANALYTICS_%'")
                .update();
    }

    private static UUID uuid(int sequence, int suffix) {
        return UUID.fromString("40000000-0000-4000-8000-%012d".formatted(sequence * 10 + suffix));
    }

    private static String metricKey(AnalyticsDailyRecord row) {
        return "%s|%s|%s|%s|%d".formatted(
                row.metric(),
                row.eventType().name(),
                row.dimension(),
                row.dimensionValue(),
                row.metricCount());
    }

    private static List<String> expectedMetricKeys() {
        return List.of(
                "PV|PAGE_VIEW|ALL|(all)|3",
                "DAILY_UV|PAGE_VIEW|ALL|(all)|2",
                "EVENT_COUNT|PAGE_VIEW|ALL|(all)|3",
                "EVENT_COUNT|PROJECT_VIEW|ALL|(all)|2",
                "EVENT_COUNT|RESUME_DOWNLOAD|ALL|(all)|1",
                "EVENT_COUNT|DEMO_DOWNLOAD|ALL|(all)|1",
                "EVENT_COUNT|OUTBOUND_CLICK|ALL|(all)|1",
                "PV|PAGE_VIEW|PAGE|HOME|2",
                "PV|PAGE_VIEW|PAGE|PROJECT_DETAIL|1",
                "DAILY_UV|PAGE_VIEW|PAGE|HOME|1",
                "DAILY_UV|PAGE_VIEW|PAGE|PROJECT_DETAIL|1",
                "EVENT_COUNT|PAGE_VIEW|PAGE|HOME|2",
                "EVENT_COUNT|PAGE_VIEW|PAGE|PROJECT_DETAIL|1",
                "EVENT_COUNT|PROJECT_VIEW|PAGE|PROJECT_DETAIL|2",
                "EVENT_COUNT|RESUME_DOWNLOAD|PAGE|WORK|1",
                "EVENT_COUNT|DEMO_DOWNLOAD|PAGE|PROJECT_DETAIL|1",
                "EVENT_COUNT|OUTBOUND_CLICK|PAGE|PROJECT_DETAIL|1",
                "PV|PAGE_VIEW|PROJECT|" + PROJECT_ID + "|1",
                "DAILY_UV|PAGE_VIEW|PROJECT|" + PROJECT_ID + "|1",
                "EVENT_COUNT|PAGE_VIEW|PROJECT|" + PROJECT_ID + "|1",
                "EVENT_COUNT|PROJECT_VIEW|PROJECT|" + PROJECT_ID + "|2",
                "EVENT_COUNT|DEMO_DOWNLOAD|PROJECT|" + PROJECT_ID + "|1",
                "EVENT_COUNT|OUTBOUND_CLICK|PROJECT|" + PROJECT_ID + "|1",
                "PV|PAGE_VIEW|REFERRER|(direct)|2",
                "PV|PAGE_VIEW|REFERRER|github.com|1",
                "DAILY_UV|PAGE_VIEW|REFERRER|(direct)|1",
                "DAILY_UV|PAGE_VIEW|REFERRER|github.com|1",
                "EVENT_COUNT|PAGE_VIEW|REFERRER|(direct)|2",
                "EVENT_COUNT|PAGE_VIEW|REFERRER|github.com|1",
                "EVENT_COUNT|PROJECT_VIEW|REFERRER|(direct)|1",
                "EVENT_COUNT|PROJECT_VIEW|REFERRER|github.com|1",
                "EVENT_COUNT|RESUME_DOWNLOAD|REFERRER|(direct)|1",
                "EVENT_COUNT|DEMO_DOWNLOAD|REFERRER|github.com|1",
                "EVENT_COUNT|OUTBOUND_CLICK|REFERRER|github.com|1",
                "PV|PAGE_VIEW|DEVICE|DESKTOP|2",
                "PV|PAGE_VIEW|DEVICE|MOBILE|1",
                "DAILY_UV|PAGE_VIEW|DEVICE|DESKTOP|1",
                "DAILY_UV|PAGE_VIEW|DEVICE|MOBILE|1",
                "EVENT_COUNT|PAGE_VIEW|DEVICE|DESKTOP|2",
                "EVENT_COUNT|PAGE_VIEW|DEVICE|MOBILE|1",
                "EVENT_COUNT|PROJECT_VIEW|DEVICE|DESKTOP|1",
                "EVENT_COUNT|PROJECT_VIEW|DEVICE|MOBILE|1",
                "EVENT_COUNT|RESUME_DOWNLOAD|DEVICE|DESKTOP|1",
                "EVENT_COUNT|DEMO_DOWNLOAD|DEVICE|MOBILE|1",
                "EVENT_COUNT|OUTBOUND_CLICK|DEVICE|MOBILE|1",
                "PV|PAGE_VIEW|LOCALE|zh-CN|2",
                "PV|PAGE_VIEW|LOCALE|en|1",
                "DAILY_UV|PAGE_VIEW|LOCALE|zh-CN|1",
                "DAILY_UV|PAGE_VIEW|LOCALE|en|1",
                "EVENT_COUNT|PAGE_VIEW|LOCALE|zh-CN|2",
                "EVENT_COUNT|PAGE_VIEW|LOCALE|en|1",
                "EVENT_COUNT|PROJECT_VIEW|LOCALE|zh-CN|1",
                "EVENT_COUNT|PROJECT_VIEW|LOCALE|en|1",
                "EVENT_COUNT|RESUME_DOWNLOAD|LOCALE|zh-CN|1",
                "EVENT_COUNT|DEMO_DOWNLOAD|LOCALE|en|1",
                "EVENT_COUNT|OUTBOUND_CLICK|LOCALE|en|1");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock analyticsAggregationTestClock() {
            return Clock.fixed(
                    Instant.parse("2026-07-18T08:30:00Z"), ZoneOffset.UTC);
        }
    }

    private record AggregationRun(
            String status,
            long inputCount,
            long outputCount,
            String errorSummary,
            String details) {}
}
