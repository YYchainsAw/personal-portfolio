package xyz.yychainsaw.portfolio.analytics.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import xyz.yychainsaw.portfolio.auth.session.AdminSessionService;
import xyz.yychainsaw.portfolio.auth.session.AdminSessionService.ActiveSession;
import xyz.yychainsaw.portfolio.auth.web.AdminPrincipal;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.publishing.application.ProjectLabelQuery;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest(properties =
        "portfolio.analytics.maintenance-scheduling-enabled=false")
@AutoConfigureMockMvc
@Isolated
@Import(AdminAnalyticsControllerTest.ProjectLabelsConfiguration.class)
class AdminAnalyticsControllerTest extends PostgresIntegrationTestBase {
    private static final UUID ADMIN_ID =
            UUID.fromString("96000000-0000-4000-8000-000000000001");
    private static final UUID PROJECT_ALPHA =
            UUID.fromString("96000000-0000-4000-8000-000000000002");
    private static final UUID PROJECT_BETA =
            UUID.fromString("96000000-0000-4000-8000-000000000003");
    private static final UUID PROJECT_UNKNOWN =
            UUID.fromString("96000000-0000-4000-8000-000000000004");
    private static final Instant SESSION_TIME =
            Instant.parse("2026-07-18T01:02:03Z");
    private static final String SESSION_COOKIE = "PORTFOLIO_SESSION";
    private static final String SESSION_PRIMARY_ID =
            "96000000-0000-4000-8000-000000000010";
    private static final String SESSION_PUBLIC_ID =
            "96000000-0000-4000-8000-000000000011";
    private static final String NO_STORE = CacheControl.noStore().getHeaderValue();
    private static final String ZONE = "Asia/Hong_Kong";

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired TestProjectLabels projectLabels;

    @MockitoBean AdminSessionService sessions;

    @BeforeEach
    void prepare() throws IOException {
        clearAnalytics();
        clearSession();
        projectLabels.clear();
        insertAuthenticatedSession();
        given(sessions.requireActive(SESSION_PUBLIC_ID)).willReturn(new ActiveSession(
                UUID.fromString("96000000-0000-4000-8000-000000000012"),
                ADMIN_ID,
                SESSION_PUBLIC_ID,
                SESSION_TIME,
                SESSION_TIME));
    }

    @AfterEach
    void cleanUp() {
        clearAnalytics();
        clearSession();
        projectLabels.clear();
    }

    @Test
    void allReportRoutesRequireAnActiveAdministratorSession() throws Exception {
        mvc.perform(get("/api/admin/analytics/summary")
                        .param("from", "2026-07-01")
                        .param("to", "2026-07-14")
                        .param("locale", "zh-CN")
                        .param("zone", ZONE))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NO_STORE))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mvc.perform(get("/api/admin/analytics/timeseries")
                        .param("from", "2026-07-01")
                        .param("to", "2026-07-14")
                        .param("metric", "PV")
                        .param("eventType", "PAGE_VIEW")
                        .param("zone", ZONE))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NO_STORE))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mvc.perform(get("/api/admin/analytics/breakdown")
                        .param("from", "2026-07-01")
                        .param("to", "2026-07-14")
                        .param("metric", "EVENT_COUNT")
                        .param("eventType", "PROJECT_VIEW")
                        .param("dimension", "PROJECT")
                        .param("limit", "10")
                        .param("zone", ZONE))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NO_STORE))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void summaryReturnsExactTotalsLocalizedDefinitionsAndDataDelay() throws Exception {
        insertSummaryFixture();

        MvcResult result = mvc.perform(authenticated(get("/api/admin/analytics/summary")
                        .param("from", "2026-07-01")
                        .param("to", "2026-07-03")
                        .param("locale", "zh-CN")
                        .param("zone", ZONE)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NO_STORE))
                .andExpect(jsonPath("$.pageViews").value(15))
                .andExpect(jsonPath("$.dailyUniqueVisitors").value(10))
                .andExpect(jsonPath("$.projectViews").value(6))
                .andExpect(jsonPath("$.resumeDownloads").value(1))
                .andExpect(jsonPath("$.demoDownloads").value(3))
                .andExpect(jsonPath("$.outboundClicks").value(6))
                .andExpect(jsonPath("$.dataCompleteThrough")
                        .value("2026-07-04T00:45:00Z"))
                .andExpect(jsonPath("$.zone").value(ZONE))
                .andExpect(jsonPath("$.definitions.PV").isString())
                .andExpect(jsonPath("$.definitions.DAILY_UV").isString())
                .andExpect(jsonPath("$.definitions.EVENT_COUNT").isString())
                .andExpect(jsonPath("$.definitions.length()").value(3))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("日 UV");
        assertNoSensitiveFields(result);
    }

    @Test
    void summaryDefinitionsAreAvailableInEnglishThroughTheSameKeys()
            throws Exception {
        insertSummaryFixture();

        mvc.perform(authenticated(get("/api/admin/analytics/summary")
                        .param("from", "2026-07-01")
                        .param("to", "2026-07-03")
                        .param("locale", "en")
                        .param("zone", ZONE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.definitions.PV").isString())
                .andExpect(jsonPath("$.definitions.DAILY_UV").value(
                        org.hamcrest.Matchers.containsString("summed")))
                .andExpect(jsonPath("$.definitions.EVENT_COUNT").isString())
                .andExpect(jsonPath("$.definitions.length()").value(3));
    }

    @Test
    void emptySummaryKeepsAnExplicitNullAggregationWatermark() throws Exception {
        mvc.perform(authenticated(get("/api/admin/analytics/summary")
                        .param("from", "2026-07-01")
                        .param("to", "2026-07-03")
                        .param("locale", "zh-CN")
                        .param("zone", ZONE)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NO_STORE))
                .andExpect(jsonPath("$.pageViews").value(0))
                .andExpect(jsonPath("$.dailyUniqueVisitors").value(0))
                .andExpect(jsonPath("$.dataCompleteThrough").value(
                        org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.zone").value(ZONE));
    }

    @Test
    void incompleteSummaryDoesNotClaimAnAggregationWatermark() throws Exception {
        insertSummaryFixture();
        assertThat(migratorJdbc().sql("""
                        delete from portfolio.analytics_daily
                        where site_date='2026-07-02'
                          and metric='DAILY_UV'
                          and event_type='PAGE_VIEW'
                          and dimension='ALL'
                          and dimension_value='(all)'
                        """).update()).isOne();

        mvc.perform(authenticated(get("/api/admin/analytics/summary")
                        .param("from", "2026-07-01")
                        .param("to", "2026-07-03")
                        .param("locale", "zh-CN")
                        .param("zone", ZONE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyUniqueVisitors").value(10))
                .andExpect(jsonPath("$.dataCompleteThrough").value(
                        org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void timeseriesZeroFillsEveryMissingSiteDayWithoutCsrf() throws Exception {
        insertSummaryFixture();
        assertThat(migratorJdbc().sql("""
                        delete from portfolio.analytics_daily
                        where site_date='2026-07-02'
                          and metric='PV'
                          and event_type='PAGE_VIEW'
                          and dimension='ALL'
                          and dimension_value='(all)'
                        """).update()).isOne();

        MvcResult result = mvc.perform(authenticated(get("/api/admin/analytics/timeseries")
                        .param("from", "2026-07-01")
                        .param("to", "2026-07-03")
                        .param("metric", "PV")
                        .param("eventType", "PAGE_VIEW")
                        .param("zone", ZONE)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NO_STORE))
                .andExpect(jsonPath("$[0].date").value("2026-07-01"))
                .andExpect(jsonPath("$[0].value").value(10))
                .andExpect(jsonPath("$[1].date").value("2026-07-02"))
                .andExpect(jsonPath("$[1].value").value(0))
                .andExpect(jsonPath("$[2].date").value("2026-07-03"))
                .andExpect(jsonPath("$[2].value").value(5))
                .andReturn();

        assertNoSensitiveFields(result);
    }

    @Test
    void breakdownUsesStableValueThenDimensionValueOrderingAndLimit()
            throws Exception {
        insertDimension(
                LocalDate.parse("2026-07-01"), "DEVICE", "MOBILE", 2);
        insertDimension(
                LocalDate.parse("2026-07-01"), "DEVICE", "DESKTOP", 2);
        insertDimension(
                LocalDate.parse("2026-07-01"), "DEVICE", "TABLET", 1);
        insertDimension(
                LocalDate.parse("2026-07-03"), "DEVICE", "MOBILE", 1);
        insertDimension(
                LocalDate.parse("2026-07-03"), "DEVICE", "DESKTOP", 1);

        MvcResult result = mvc.perform(authenticated(get("/api/admin/analytics/breakdown")
                        .param("from", "2026-07-01")
                        .param("to", "2026-07-03")
                        .param("metric", "EVENT_COUNT")
                        .param("eventType", "PROJECT_VIEW")
                        .param("dimension", "DEVICE")
                        .param("limit", "2")
                        .param("zone", ZONE)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NO_STORE))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].dimensionValue").value("DESKTOP"))
                .andExpect(jsonPath("$[0].value").value(3))
                .andExpect(jsonPath("$[1].dimensionValue").value("MOBILE"))
                .andExpect(jsonPath("$[1].value").value(3))
                .andReturn();

        assertNoSensitiveFields(result);
    }

    @Test
    void projectBreakdownResolvesKnownTitlesAndRetainsUnknownUuid()
            throws Exception {
        projectLabels.put(PROJECT_ALPHA, LocaleCode.ZH_CN, "Zulu project");
        projectLabels.put(PROJECT_BETA, LocaleCode.ZH_CN, "Alpha project");
        insertProjectDimension(PROJECT_ALPHA, 4);
        insertProjectDimension(PROJECT_BETA, 4);
        insertProjectDimension(PROJECT_UNKNOWN, 2);

        mvc.perform(authenticated(get("/api/admin/analytics/breakdown")
                        .param("from", "2026-07-01")
                        .param("to", "2026-07-03")
                        .param("metric", "EVENT_COUNT")
                        .param("eventType", "PROJECT_VIEW")
                        .param("dimension", "PROJECT")
                        .param("limit", "1")
                        .param("zone", ZONE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].dimensionValue").value("Alpha project"));

        MvcResult result = mvc.perform(authenticated(get("/api/admin/analytics/breakdown")
                        .param("from", "2026-07-01")
                        .param("to", "2026-07-03")
                        .param("metric", "EVENT_COUNT")
                        .param("eventType", "PROJECT_VIEW")
                        .param("dimension", "PROJECT")
                        .param("limit", "10")
                        .param("zone", ZONE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].dimensionValue").value("Alpha project"))
                .andExpect(jsonPath("$[0].value").value(4))
                .andExpect(jsonPath("$[1].dimensionValue").value("Zulu project"))
                .andExpect(jsonPath("$[1].value").value(4))
                .andExpect(jsonPath("$[2].dimensionValue")
                        .value(PROJECT_UNKNOWN.toString()))
                .andExpect(jsonPath("$[2].value").value(2))
                .andReturn();

        assertNoSensitiveFields(result);
    }

    @Test
    void onlyTheFixedHongKongZoneIsAccepted() throws Exception {
        for (String path : List.of("summary", "timeseries", "breakdown")) {
            MockHttpServletRequestBuilder request = get("/api/admin/analytics/" + path)
                    .param("from", "2026-07-01")
                    .param("to", "2026-07-03")
                    .param("zone", "UTC");
            if (path.equals("summary")) {
                request.param("locale", "zh-CN");
            } else {
                request.param("metric", "EVENT_COUNT")
                        .param("eventType", "PROJECT_VIEW");
            }
            if (path.equals("breakdown")) {
                request.param("dimension", "PROJECT").param("limit", "10");
            }
            mvc.perform(authenticated(request))
                    .andExpect(status().isBadRequest())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NO_STORE))
                    .andExpect(jsonPath("$.code")
                            .value("ANALYTICS_ZONE_UNSUPPORTED"));
        }

        mvc.perform(authenticated(get("/api/admin/analytics/summary")
                        .param("from", "2026-07-01")
                        .param("to", "2026-07-03")
                        .param("locale", "zh-CN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("ANALYTICS_ZONE_UNSUPPORTED"))
                .andExpect(jsonPath("$.fieldErrors.zone").value("invalid"));
    }

    @Test
    void dateMetricDimensionAndLimitValidationUseStableErrors()
            throws Exception {
        assertInvalid(get("/api/admin/analytics/summary")
                        .param("from", "2026-07-03")
                        .param("to", "2026-07-01")
                        .param("locale", "zh-CN")
                        .param("zone", ZONE), "to");
        assertInvalid(get("/api/admin/analytics/summary")
                        .param("from", "2026-7-01")
                        .param("to", "2026-07-03")
                        .param("locale", "zh-CN")
                        .param("zone", ZONE), "from");
        assertInvalid(get("/api/admin/analytics/summary")
                        .param("to", "2026-07-03")
                        .param("locale", "zh-CN")
                        .param("zone", ZONE), "from");
        assertInvalid(get("/api/admin/analytics/summary")
                        .param("from", "2026-07-01")
                        .param("to", "2026-07-03")
                        .param("locale", "ZH-cn")
                        .param("zone", ZONE), "locale");
        assertInvalid(get("/api/admin/analytics/timeseries")
                        .param("from", "2026-07-01")
                        .param("to", "2026-07-03")
                        .param("metric", "PV")
                        .param("eventType", "PROJECT_VIEW")
                        .param("zone", ZONE), "eventType");
        assertInvalid(get("/api/admin/analytics/timeseries")
                        .param("from", "2026-07-01")
                        .param("to", "2026-07-03")
                        .param("metric", "pv")
                        .param("eventType", "PAGE_VIEW")
                        .param("zone", ZONE), "metric");
        assertInvalid(get("/api/admin/analytics/timeseries")
                        .param("from", "2026-07-01")
                        .param("to", "2026-07-03")
                        .param("metric", "EVENT_COUNT")
                        .param("eventType", "project_view")
                        .param("zone", ZONE), "eventType");
        assertInvalid(get("/api/admin/analytics/breakdown")
                        .param("from", "2026-07-01")
                        .param("to", "2026-07-03")
                        .param("metric", "EVENT_COUNT")
                        .param("eventType", "PROJECT_VIEW")
                        .param("dimension", "ALL")
                        .param("limit", "10")
                        .param("zone", ZONE), "dimension");
        for (String limit : List.of("0", "101", "abc", "01")) {
            assertInvalid(get("/api/admin/analytics/breakdown")
                            .param("from", "2026-07-01")
                            .param("to", "2026-07-03")
                            .param("metric", "EVENT_COUNT")
                            .param("eventType", "PROJECT_VIEW")
                            .param("dimension", "PROJECT")
                            .param("limit", limit)
                            .param("zone", ZONE), "limit");
        }

        mvc.perform(authenticated(get("/api/admin/analytics/breakdown")
                        .param("from", "2026-07-01")
                        .param("to", "2026-07-03")
                        .param("metric", "EVENT_COUNT")
                        .param("eventType", "PROJECT_VIEW")
                        .param("dimension", "PROJECT")
                        .param("limit", "100")
                        .param("zone", ZONE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void inclusiveRangesAllowAtMostThreeHundredSixtySixDays()
            throws Exception {
        mvc.perform(authenticated(get("/api/admin/analytics/timeseries")
                        .param("from", "2025-07-16")
                        .param("to", "2026-07-16")
                        .param("metric", "PV")
                        .param("eventType", "PAGE_VIEW")
                        .param("zone", ZONE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(366));
        mvc.perform(authenticated(get("/api/admin/analytics/timeseries")
                        .param("from", "2025-07-15")
                        .param("to", "2026-07-16")
                        .param("metric", "PV")
                        .param("eventType", "PAGE_VIEW")
                        .param("zone", ZONE)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ANALYTICS_QUERY_INVALID"))
                .andExpect(jsonPath("$.fieldErrors.to").value("invalid"));
    }

    private void insertSummaryFixture() {
        insertSummaryDay(
                LocalDate.parse("2026-07-01"),
                10,
                6,
                3,
                1,
                2,
                4,
                Instant.parse("2026-07-02T00:15:00Z"));
        insertSummaryDay(
                LocalDate.parse("2026-07-03"),
                5,
                4,
                3,
                0,
                1,
                2,
                Instant.parse("2026-07-04T00:45:00Z"));
        insertSummaryDay(
                LocalDate.parse("2026-07-02"),
                0,
                0,
                0,
                0,
                0,
                0,
                Instant.parse("2026-07-03T00:15:00Z"));
        insertDaily(
                LocalDate.parse("2026-07-03"),
                "EVENT_COUNT",
                "PROJECT_VIEW",
                "PROJECT",
                PROJECT_UNKNOWN.toString(),
                1_000,
                Instant.parse("2026-07-05T00:45:00Z"));
    }

    private void insertSummaryDay(
            LocalDate date,
            long pageViews,
            long dailyUv,
            long projectViews,
            long resumeDownloads,
            long demoDownloads,
            long outboundClicks,
            Instant updatedAt) {
        insertAll(date, "PV", "PAGE_VIEW", pageViews, updatedAt);
        insertAll(date, "DAILY_UV", "PAGE_VIEW", dailyUv, updatedAt);
        insertAll(date, "EVENT_COUNT", "PAGE_VIEW", 999, updatedAt);
        insertAll(date, "EVENT_COUNT", "PROJECT_VIEW", projectViews, updatedAt);
        insertAll(date, "EVENT_COUNT", "RESUME_DOWNLOAD", resumeDownloads, updatedAt);
        insertAll(date, "EVENT_COUNT", "DEMO_DOWNLOAD", demoDownloads, updatedAt);
        insertAll(date, "EVENT_COUNT", "OUTBOUND_CLICK", outboundClicks, updatedAt);
    }

    private void insertAll(
            LocalDate date,
            String metric,
            String eventType,
            long count,
            Instant updatedAt) {
        insertDaily(date, metric, eventType, "ALL", "(all)", count, updatedAt);
    }

    private void insertDimension(
            LocalDate date, String dimension, String value, long count) {
        insertDaily(
                date,
                "EVENT_COUNT",
                "PROJECT_VIEW",
                dimension,
                value,
                count,
                Instant.parse("2026-07-04T00:45:00Z"));
    }

    private void insertProjectDimension(UUID projectId, long count) {
        insertDimension(
                LocalDate.parse("2026-07-01"),
                "PROJECT",
                projectId.toString(),
                count);
    }

    private void insertDaily(
            LocalDate date,
            String metric,
            String eventType,
            String dimension,
            String dimensionValue,
            long count,
            Instant updatedAt) {
        assertThat(migratorJdbc().sql("""
                        insert into portfolio.analytics_daily(
                            site_date, metric, event_type, dimension,
                            dimension_value, metric_count,
                            aggregation_version, updated_at
                        ) values (
                            :siteDate, :metric, :eventType, :dimension,
                            :dimensionValue, :metricCount,
                            'analytics-rules-v1', :updatedAt
                        )
                        """)
                .param("siteDate", date)
                .param("metric", metric)
                .param("eventType", eventType)
                .param("dimension", dimension)
                .param("dimensionValue", dimensionValue)
                .param("metricCount", count)
                .param("updatedAt", OffsetDateTime.ofInstant(updatedAt, ZoneOffset.UTC))
                .update()).isOne();
    }

    private void clearAnalytics() {
        migratorJdbc().sql("""
                        truncate table portfolio.analytics_event,
                                       portfolio.analytics_retention_checkpoint
                        """).update();
        migratorJdbc().sql("delete from portfolio.analytics_daily").update();
    }

    private void assertInvalid(
            MockHttpServletRequestBuilder request, String field) throws Exception {
        mvc.perform(authenticated(request))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NO_STORE))
                .andExpect(jsonPath("$.code").value("ANALYTICS_QUERY_INVALID"))
                .andExpect(jsonPath("$.fieldErrors." + field).value("invalid"));
    }

    private static void assertNoSensitiveFields(MvcResult result) throws Exception {
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(
                        "analyticsEvent",
                        "analytics_event",
                        "visitorDayKey",
                        "visitor_day_key",
                        "sessionDayKey",
                        "session_day_key",
                        "contactMessage",
                        "contact_message",
                        "email",
                        "body");
    }

    private void insertAuthenticatedSession() throws IOException {
        long now = System.currentTimeMillis();
        assertThat(jdbc.sql("""
                        insert into portfolio.spring_session(
                            primary_id, session_id, creation_time, last_access_time,
                            max_inactive_interval, expiry_time, principal_name
                        ) values (
                            :primaryId, :sessionId, :now, :now,
                            1800, :expiry, 'yychainsaw'
                        )
                        """)
                .param("primaryId", SESSION_PRIMARY_ID)
                .param("sessionId", SESSION_PUBLIC_ID)
                .param("now", now)
                .param("expiry", now + 1_800_000L)
                .update()).isOne();

        AdminPrincipal principal = new AdminPrincipal(ADMIN_ID, "yychainsaw");
        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        principal,
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);

        assertThat(jdbc.sql("""
                        insert into portfolio.spring_session_attributes(
                            session_primary_id, attribute_name, attribute_bytes
                        ) values (
                            :primaryId, 'SPRING_SECURITY_CONTEXT', :bytes
                        )
                        """)
                .param("primaryId", SESSION_PRIMARY_ID)
                .param("bytes", serialize(context))
                .update()).isOne();
    }

    private void clearSession() {
        jdbc.sql("""
                        delete from portfolio.spring_session
                        where primary_id=:primaryId
                        """)
                .param("primaryId", SESSION_PRIMARY_ID)
                .update();
    }

    private MockHttpServletRequestBuilder authenticated(
            MockHttpServletRequestBuilder request) {
        Cookie cookie = new Cookie(SESSION_COOKIE, SESSION_PUBLIC_ID);
        cookie.setPath("/");
        return request.cookie(cookie);
    }

    private static byte[] serialize(Object value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(value);
        }
        return bytes.toByteArray();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProjectLabelsConfiguration {
        @Bean
        @Primary
        TestProjectLabels testProjectLabels() {
            return new TestProjectLabels();
        }
    }

    static final class TestProjectLabels implements ProjectLabelQuery {
        private final Map<Key, String> labels = new HashMap<>();

        @Override
        public Optional<String> findProjectTitle(UUID projectId, LocaleCode locale) {
            return Optional.ofNullable(labels.get(new Key(projectId, locale)));
        }

        void put(UUID projectId, LocaleCode locale, String title) {
            labels.put(new Key(projectId, locale), title);
        }

        void clear() {
            labels.clear();
        }

        private record Key(UUID projectId, LocaleCode locale) {}
    }
}
