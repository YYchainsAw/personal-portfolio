package xyz.yychainsaw.portfolio.analytics;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Isolated;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import xyz.yychainsaw.portfolio.analytics.application.AnalyticsAggregationService;
import xyz.yychainsaw.portfolio.analytics.application.AnalyticsQuery;
import xyz.yychainsaw.portfolio.analytics.application.AnalyticsReportService;
import xyz.yychainsaw.portfolio.analytics.application.AnalyticsRetentionJobHandler;
import xyz.yychainsaw.portfolio.analytics.application.AnalyticsSummary;
import xyz.yychainsaw.portfolio.analytics.persistence.AnalyticsEventMapper;
import xyz.yychainsaw.portfolio.analytics.persistence.AnalyticsEventRecord;
import xyz.yychainsaw.portfolio.common.ratelimit.RateLimitDecision;
import xyz.yychainsaw.portfolio.common.ratelimit.RateLimiter;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;
import xyz.yychainsaw.portfolio.system.job.JobExecutionContext;

@SpringBootTest(properties = "portfolio.analytics.maintenance-scheduling-enabled=false")
@AutoConfigureMockMvc
@Isolated
@ExtendWith(OutputCaptureExtension.class)
@Import(AnalyticsPrivacyContractTest.MutableClockConfiguration.class)
class AnalyticsPrivacyContractTest extends PostgresIntegrationTestBase {
    private static final String EVENTS_PATH = "/api/public/events";
    private static final String CSRF_PATH = "/api/admin/auth/csrf";
    private static final String XSRF_COOKIE = "XSRF-TOKEN";
    private static final ZoneId SITE_ZONE = ZoneId.of("Asia/Hong_Kong");
    private static final Instant DEDUPE_BASE = Instant.parse("2026-07-14T12:00:00Z");
    private static final Instant BEFORE_HONG_KONG_MIDNIGHT =
            Instant.parse("2026-07-14T15:59:59.999999Z");
    private static final Instant AFTER_HONG_KONG_MIDNIGHT =
            Instant.parse("2026-07-14T16:00:00Z");
    private static final LocalDate FIRST_SITE_DATE = LocalDate.parse("2026-07-14");
    private static final LocalDate SECOND_SITE_DATE = LocalDate.parse("2026-07-15");
    private static final Instant RETENTION_NOW = Instant.parse("2026-07-17T08:30:00Z");
    private static final Instant RETENTION_CUTOFF =
            Instant.parse("2026-06-17T08:30:00Z");
    private static final Instant EXPIRED_EVENT =
            Instant.parse("2026-06-17T08:29:59.999999Z");
    private static final LocalDate RETENTION_SITE_DATE = LocalDate.parse("2026-06-17");
    private static final UUID RETENTION_JOB_ID =
            UUID.fromString("98000000-0000-4000-8000-000000000001");

    private static final String RAW_CLIENT_IP = "198.51.100.77";
    private static final String RAW_VISITOR_ID = browserId(0x20);
    private static final String RAW_SESSION_ID = browserId(0x50);
    private static final String RAW_REFERRER =
            "https://GitHub.COM/private/path?email=privacy-contact@example.invalid"
                    + "&message=private-contact-body#raw-fragment";
    private static final String FULL_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) PrivacyContractFullUserAgent/2026.07";
    private static final String CONTACT_EMAIL = "privacy-contact@example.invalid";
    private static final String CONTACT_BODY = "private-contact-body";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired AnalyticsEventMapper events;
    @Autowired AnalyticsAggregationService aggregation;
    @Autowired AnalyticsReportService reports;
    @Autowired AnalyticsRetentionJobHandler retention;
    @Autowired MutableClock clock;

    @MockitoBean RateLimiter limiter;

    @BeforeEach
    void prepare() {
        clearAnalytics();
        clock.set(DEDUPE_BASE);
        reset(limiter);
        when(limiter.consume(anyString(), anyString())).thenReturn(RateLimitDecision.allow());
    }

    @AfterEach
    void cleanUp() {
        try {
            clearAnalytics();
        } finally {
            reset(limiter);
        }
    }

    @Test
    void noConsentAndDntProduceNoRowAndExposeNoRequestIdentity(
            CapturedOutput output) throws Exception {
        CsrfExchange csrf = csrf();
        Map<String, Object> noConsent = new LinkedHashMap<>();
        noConsent.put("analyticsConsent", false);
        noConsent.put("visitorId", RAW_VISITOR_ID);
        noConsent.put("sessionId", RAW_SESSION_ID);
        noConsent.put("events", "deliberately-not-validated");

        assertNoContent(performJson(csrf, json.writeValueAsBytes(noConsent)));
        assertNoContent(mvc.perform(jsonRequest("{".getBytes(UTF_8))
                        .with(trustedClient())
                        .header(HttpHeaders.USER_AGENT, FULL_USER_AGENT)
                        .header("DNT", "1")
                        .cookie(csrf.cookie())
                        .header(csrf.headerName(), csrf.token()))
                .andReturn());

        assertThat(events.count()).isZero();
        verifyNoInteractions(limiter);
        assertNoForbiddenValues(output.getAll());
    }

    @Test
    void consentedJourneyEnforcesDedupeSiteDaysReportsAndThirtyDayPurge(
            CapturedOutput output) throws Exception {
        CsrfExchange csrf = csrf();

        assertNoContent(submit(csrf, DEDUPE_BASE, eventId(1), "HOME"));
        assertNoContent(submit(
                csrf, DEDUPE_BASE.plusSeconds(10), eventId(2), "HOME"));
        assertThat(events.count()).isOne();

        Instant justOutsideWindow = DEDUPE_BASE.plusSeconds(10).plusNanos(1_000);
        assertNoContent(submit(csrf, justOutsideWindow, eventId(3), "HOME"));
        assertThat(events.findAll())
                .extracting(AnalyticsEventRecord::receivedAt)
                .containsExactly(DEDUPE_BASE, justOutsideWindow);

        assertNoContent(submit(
                csrf, BEFORE_HONG_KONG_MIDNIGHT, eventId(4), "WORK"));
        assertNoContent(submit(
                csrf, AFTER_HONG_KONG_MIDNIGHT, eventId(5), "WORK"));

        List<AnalyticsEventRecord> currentRows = events.findAll();
        assertThat(currentRows)
                .extracting(AnalyticsEventRecord::siteDate)
                .containsExactly(
                        FIRST_SITE_DATE,
                        FIRST_SITE_DATE,
                        FIRST_SITE_DATE,
                        SECOND_SITE_DATE);
        assertThat(currentRows.stream()
                        .filter(row -> row.siteDate().equals(FIRST_SITE_DATE))
                        .map(AnalyticsEventRecord::visitorDayKey)
                        .distinct())
                .hasSize(1);
        String secondDayVisitorKey = currentRows.stream()
                .filter(row -> row.siteDate().equals(SECOND_SITE_DATE))
                .map(AnalyticsEventRecord::visitorDayKey)
                .findFirst()
                .orElseThrow();
        assertThat(secondDayVisitorKey)
                .isNotEqualTo(currentRows.get(0).visitorDayKey());
        assertThat(currentRows).allSatisfy(row -> {
            assertThat(row.referrerDomain()).isEqualTo("github.com");
            assertThat(row.visitorDayKey()).matches("[0-9a-f]{64}");
            assertThat(row.sessionDayKey()).matches("[0-9a-f]{64}");
            assertThat(row.toString())
                    .isEqualTo("AnalyticsEventRecord[fields=<redacted>]")
                    .doesNotContain(RAW_VISITOR_ID, RAW_SESSION_ID);
        });

        assertThat(aggregation.rebuild(FIRST_SITE_DATE).inputCount()).isEqualTo(3);
        assertThat(aggregation.rebuild(SECOND_SITE_DATE).inputCount()).isOne();

        AnalyticsSummary chinese = reports.summary(AnalyticsQuery.summary(
                FIRST_SITE_DATE, SECOND_SITE_DATE, LocaleCode.ZH_CN));
        AnalyticsSummary english = reports.summary(AnalyticsQuery.summary(
                FIRST_SITE_DATE, SECOND_SITE_DATE, LocaleCode.EN));
        assertSummary(chinese);
        assertSummary(english);
        assertThat(chinese.definitions())
                .containsOnlyKeys("PV", "DAILY_UV", "EVENT_COUNT");
        assertThat(english.definitions())
                .containsOnlyKeys("PV", "DAILY_UV", "EVENT_COUNT");
        assertThat(chinese.definitions().values()).allSatisfy(
                definition -> assertThat(definition).isNotBlank());
        assertThat(english.definitions().values()).allSatisfy(
                definition -> assertThat(definition).isNotBlank());
        assertThat(chinese.definitions()).isNotEqualTo(english.definitions());
        Instant expectedWatermark = jdbc.sql("""
                        select max(updated_at) data_complete_through
                        from portfolio.analytics_daily
                        where site_date between :from and :to
                          and dimension='ALL'
                          and dimension_value='(all)'
                        """)
                .param("from", FIRST_SITE_DATE)
                .param("to", SECOND_SITE_DATE)
                .query((row, number) -> row.getObject(
                        "data_complete_through", OffsetDateTime.class).toInstant())
                .single();
        assertThat(chinese.dataCompleteThrough()).isEqualTo(expectedWatermark);
        assertThat(english.dataCompleteThrough()).isEqualTo(expectedWatermark);

        assertNoContent(submit(csrf, EXPIRED_EVENT, eventId(6), "ABOUT"));
        assertNoContent(submit(csrf, RETENTION_CUTOFF, eventId(7), "CONTACT"));
        assertThat(events.count()).isEqualTo(6);

        clock.set(RETENTION_NOW);
        JsonNode payload = json.valueToTree(Map.of(
                "siteDate", RETENTION_NOW.atZone(SITE_ZONE).toLocalDate().toString()));
        retention.handle(
                new JobExecutionContext(
                        RETENTION_JOB_ID, "analytics-privacy-contract-worker", 1),
                payload);

        assertThat(events.findAll())
                .extracting(AnalyticsEventRecord::receivedAt)
                .doesNotContain(EXPIRED_EVENT)
                .contains(RETENTION_CUTOFF);
        assertThat(events.count()).isEqualTo(5);
        assertThat(jdbc.sql("""
                        select metric_count
                        from portfolio.analytics_daily
                        where site_date=:siteDate
                          and metric='PV'
                          and event_type='PAGE_VIEW'
                          and dimension='ALL'
                          and dimension_value='(all)'
                        """)
                .param("siteDate", RETENTION_SITE_DATE)
                .query(Long.class)
                .single()).isEqualTo(2);
        assertThat(jdbc.sql("""
                        select count(*)
                        from portfolio.analytics_retention_checkpoint
                        where site_date=:siteDate
                        """)
                .param("siteDate", RETENTION_SITE_DATE)
                .query(Long.class)
                .single()).isOne();
        assertSummary(reports.summary(AnalyticsQuery.summary(
                FIRST_SITE_DATE, SECOND_SITE_DATE, LocaleCode.EN)));

        ArgumentCaptor<String> subjects = ArgumentCaptor.forClass(String.class);
        verify(limiter, times(7)).consume(eq("public-events"), subjects.capture());
        assertThat(subjects.getAllValues())
                .hasSize(7)
                .containsOnly(subjects.getValue())
                .allSatisfy(subject -> assertThat(subject)
                        .matches("[0-9a-f]{64}")
                        .isNotEqualTo(RAW_CLIENT_IP));
        String ipDerivedRateSubject = subjects.getValue();

        String persistedAnalytics = analyticsPersistenceSnapshot();
        assertNoForbiddenValues(persistedAnalytics, ipDerivedRateSubject);
        assertNoForbiddenValues(output.getAll(), ipDerivedRateSubject);
    }

    @Test
    void analyticsSchemaWhitelistsOnlyCoarseIdentityFreeColumns() {
        List<String> eventColumns = jdbc.sql("""
                        select column_name
                        from information_schema.columns
                        where table_schema='portfolio'
                          and table_name='analytics_event'
                        order by ordinal_position
                        """)
                .query(String.class)
                .list();
        assertThat(eventColumns).containsExactly(
                "id",
                "client_event_id",
                "site_date",
                "received_at",
                "visitor_day_key",
                "session_day_key",
                "event_type",
                "page_key",
                "project_id",
                "referrer_domain",
                "device_class",
                "locale",
                "rules_version",
                "created_at");

        List<String> analyticsColumns = jdbc.sql("""
                        select column_name
                        from information_schema.columns
                        where table_schema='portfolio'
                          and table_name like 'analytics!_%' escape '!'
                        order by table_name, ordinal_position
                        """)
                .query(String.class)
                .list();
        assertThat(analyticsColumns).doesNotContain(
                "ip",
                "ip_address",
                "ip_hash",
                "hashed_ip",
                "visitor_id",
                "raw_visitor_id",
                "session_id",
                "raw_session_id",
                "user_agent",
                "full_user_agent",
                "request_headers",
                "query_string",
                "referrer",
                "name",
                "email",
                "subject",
                "body",
                "message",
                "contact_email",
                "contact_body");
    }

    private MvcResult submit(
            CsrfExchange csrf, Instant receivedAt, UUID eventId, String pageKey)
            throws Exception {
        clock.set(receivedAt);
        return performJson(csrf, json.writeValueAsBytes(batch(eventId, pageKey)));
    }

    private MvcResult performJson(CsrfExchange csrf, byte[] body) throws Exception {
        return mvc.perform(jsonRequest(body)
                        .with(trustedClient())
                        .header(HttpHeaders.USER_AGENT, FULL_USER_AGENT)
                        .cookie(csrf.cookie())
                        .header(csrf.headerName(), csrf.token()))
                .andReturn();
    }

    private static Map<String, Object> batch(UUID eventId, String pageKey) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", eventId);
        event.put("type", "PAGE_VIEW");
        event.put("pageKey", pageKey);
        event.put("projectId", null);
        event.put("referrer", RAW_REFERRER);
        event.put("locale", "en");

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("analyticsConsent", true);
        request.put("visitorId", RAW_VISITOR_ID);
        request.put("sessionId", RAW_SESSION_ID);
        request.put("events", List.of(event));
        return request;
    }

    private MockHttpServletRequestBuilder jsonRequest(byte[] body) {
        return post(EVENTS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private CsrfExchange csrf() throws Exception {
        MvcResult source = mvc.perform(get(CSRF_PATH)).andReturn();
        assertThat(source.getResponse().getStatus()).isEqualTo(200);
        Cookie cookie = source.getResponse().getCookie(XSRF_COOKIE);
        assertThat(cookie).isNotNull();
        JsonNode response = json.readTree(source.getResponse().getContentAsByteArray());
        String headerName = response.path("headerName").asText();
        String token = response.path("token").asText();
        assertThat(headerName).isEqualTo("X-XSRF-TOKEN");
        assertThat(token).isNotBlank();
        return new CsrfExchange(cookie, headerName, token);
    }

    private static void assertNoContent(MvcResult result) {
        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(result.getResponse().getContentAsByteArray()).isEmpty();
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL))
                .contains("no-store");
    }

    private static void assertSummary(AnalyticsSummary summary) {
        assertThat(summary.pageViews()).isEqualTo(4);
        assertThat(summary.dailyUniqueVisitors()).isEqualTo(2);
        assertThat(summary.projectViews()).isZero();
        assertThat(summary.resumeDownloads()).isZero();
        assertThat(summary.demoDownloads()).isZero();
        assertThat(summary.outboundClicks()).isZero();
        assertThat(summary.dataCompleteThrough()).isNotNull();
        assertThat(summary.zone()).isEqualTo("Asia/Hong_Kong");
    }

    private String analyticsPersistenceSnapshot() {
        List<String> rows = new ArrayList<>();
        rows.add(jdbc.sql("""
                        select coalesce(string_agg(to_jsonb(event)::text, E'\n'), '')
                        from portfolio.analytics_event event
                        """)
                .query(String.class)
                .single());
        rows.add(jdbc.sql("""
                        select coalesce(string_agg(to_jsonb(daily)::text, E'\n'), '')
                        from portfolio.analytics_daily daily
                        """)
                .query(String.class)
                .single());
        rows.add(jdbc.sql("""
                        select coalesce(string_agg(to_jsonb(run)::text, E'\n'), '')
                        from portfolio.maintenance_run run
                        where run_type like 'ANALYTICS_%'
                        """)
                .query(String.class)
                .single());
        return String.join("\n", rows);
    }

    private static void assertNoForbiddenValues(String value, String... additional) {
        assertThat(value).doesNotContain(
                RAW_CLIENT_IP,
                RAW_VISITOR_ID,
                RAW_SESSION_ID,
                RAW_REFERRER,
                "private/path",
                "raw-fragment",
                FULL_USER_AGENT,
                CONTACT_EMAIL,
                CONTACT_BODY);
        if (additional.length > 0) {
            assertThat(value).doesNotContain(additional);
        }
    }

    private void clearAnalytics() {
        migratorJdbc().sql("""
                        truncate table portfolio.analytics_event,
                                       portfolio.analytics_retention_checkpoint
                        """).update();
        migratorJdbc().sql("delete from portfolio.analytics_daily").update();
        migratorJdbc().sql("""
                        delete from portfolio.maintenance_run
                        where run_type like 'ANALYTICS_%'
                        """).update();
        migratorJdbc().sql("""
                        delete from portfolio.background_job
                        where idempotency_key like 'analytics-retention-next:%'
                        """).update();
    }

    private static UUID eventId(int sequence) {
        return UUID.fromString(
                "97000000-0000-4000-8000-%012d".formatted(sequence));
    }

    private static String browserId(int firstByte) {
        byte[] value = new byte[16];
        for (int index = 0; index < value.length; index++) {
            value[index] = (byte) (firstByte + index);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static RequestPostProcessor trustedClient() {
        return request -> {
            request.setRemoteAddr("127.0.0.1");
            request.addHeader("X-Real-IP", RAW_CLIENT_IP);
            return request;
        };
    }

    private record CsrfExchange(Cookie cookie, String headerName, String token) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class MutableClockConfiguration {
        @Bean
        @Primary
        MutableClock analyticsPrivacyContractClock() {
            return new MutableClock(DEDUPE_BASE);
        }
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> current;

        MutableClock(Instant initial) {
            current = new AtomicReference<>(initial);
        }

        void set(Instant instant) {
            current.set(instant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return ZoneOffset.UTC.equals(zone) ? this : Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }
}
