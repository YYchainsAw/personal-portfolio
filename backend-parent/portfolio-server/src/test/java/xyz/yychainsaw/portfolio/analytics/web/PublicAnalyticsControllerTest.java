package xyz.yychainsaw.portfolio.analytics.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Isolated;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import xyz.yychainsaw.portfolio.analytics.persistence.AnalyticsEventMapper;
import xyz.yychainsaw.portfolio.analytics.persistence.AnalyticsEventRecord;
import xyz.yychainsaw.portfolio.common.ratelimit.RateLimitDecision;
import xyz.yychainsaw.portfolio.common.ratelimit.RateLimiter;
import xyz.yychainsaw.portfolio.publishing.application.CurrentPublicationQuery;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
@AutoConfigureMockMvc
@Isolated
@ExtendWith(OutputCaptureExtension.class)
class PublicAnalyticsControllerTest extends PostgresIntegrationTestBase {
    private static final String EVENTS_PATH = "/api/public/events";
    private static final String CSRF_PATH = "/api/admin/auth/csrf";
    private static final String XSRF_COOKIE = "XSRF-TOKEN";
    private static final String RATE_POLICY = "public-events";
    private static final String RAW_CLIENT_IP = "203.0.113.9";
    private static final String USER_AGENT =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X)";
    private static final String VISITOR_ID = browserId(0x20);
    private static final String SESSION_ID = browserId(0x50);
    private static final String REFERRER =
            "https://WWW.GitHub.COM/private/path?identity=value#secret";
    private static final UUID EVENT_ID =
            UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID PROJECT_ID =
            UUID.fromString("20000000-0000-4000-8000-000000000002");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AnalyticsEventMapper events;
    @Autowired JdbcClient jdbc;

    @MockitoBean RateLimiter limiter;
    @MockitoBean CurrentPublicationQuery publications;

    @BeforeEach
    void prepare() {
        clearEvents();
        reset(limiter, publications);
        when(limiter.consume(anyString(), anyString())).thenReturn(RateLimitDecision.allow());
        when(publications.isCurrentPublishedProject(PROJECT_ID)).thenReturn(true);
    }

    @AfterEach
    void cleanUp() {
        try {
            clearEvents();
        } finally {
            reset(limiter, publications);
        }
    }

    @Test
    void consentedBatchUsesCsrfHashedRateSubjectAndPersistsOnlyCoarseData(
            CapturedOutput output) throws Exception {
        MvcResult result = performJson(csrf(), json.writeValueAsBytes(validBatch()));

        assertNoContent(result);
        assertThat(result.getResponse().getCookies())
                .extracting(Cookie::getName)
                .doesNotContain("PORTFOLIO_SESSION", "ANALYTICS", "analytics");
        assertThat(jdbc.sql("select count(*) from portfolio.spring_session")
                        .query(Long.class).single())
                .isZero();
        assertThat(events.count()).isEqualTo(1);
        AnalyticsEventRecord row = events.findAll().get(0);
        assertThat(row.visitorDayKey()).matches("[0-9a-f]{64}");
        assertThat(row.sessionDayKey()).matches("[0-9a-f]{64}");
        assertThat(row.referrerDomain()).isEqualTo("github.com");
        assertThat(row.deviceClass().name()).isEqualTo("MOBILE");
        assertThat(row.toString()).doesNotContain(VISITOR_ID, SESSION_ID, REFERRER);

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(limiter).consume(eq(RATE_POLICY), subject.capture());
        assertHashedSubject(subject.getValue());
        verifyNoMoreInteractions(limiter);
        assertNoSensitiveOutput(output.getAll());
    }

    @Test
    void noConsentShortCircuitsBeforeIdentifierEventOrRateLimitValidation()
            throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("analyticsConsent", false);
        request.put("visitorId", Map.of("raw", "not-validated"));
        request.put("sessionId", 12345);
        request.put("events", "not-an-array");

        assertNoContent(performJson(csrf(), json.writeValueAsBytes(request)));

        assertThat(events.count()).isZero();
        verifyNoInteractions(limiter, publications);
    }

    @Test
    void missingOrNullConsentIsSuppressedWithoutAnyApplicationSideEffect()
            throws Exception {
        Map<String, Object> missing = new LinkedHashMap<>();
        missing.put("visitorId", "not-validated");
        Map<String, Object> explicitNull = new LinkedHashMap<>();
        explicitNull.put("analyticsConsent", null);
        explicitNull.put("events", Map.of("not", "validated"));

        assertNoContent(performJson(csrf(), json.writeValueAsBytes(missing)));
        assertNoContent(performJson(csrf(), json.writeValueAsBytes(explicitNull)));

        assertThat(events.count()).isZero();
        verifyNoInteractions(limiter, publications);
    }

    @Test
    void dntShortCircuitsBeforeBodyParsingAndRateLimit() throws Exception {
        CsrfExchange csrf = csrf();
        MvcResult result = mvc.perform(jsonRequest("{".getBytes(UTF_8))
                        .header("DNT", "1")
                        .cookie(csrf.cookie())
                        .header(csrf.headerName(), csrf.token()))
                .andReturn();

        assertNoContent(result);
        assertThat(events.count()).isZero();
        verifyNoInteractions(limiter, publications);
    }

    @Test
    void privacySignalsStillEnforceRawBodyLimitWithoutParsing() throws Exception {
        CsrfExchange csrf = csrf();
        for (String header : List.of("DNT", "Sec-GPC")) {
            MvcResult result = mvc.perform(jsonRequest(paddedNoConsentJson(32_769))
                            .header(header, "1")
                            .cookie(csrf.cookie())
                            .header(csrf.headerName(), csrf.token()))
                    .andReturn();
            assertProblem(result, 413, "PAYLOAD_TOO_LARGE");
        }

        assertThat(events.count()).isZero();
        verifyNoInteractions(limiter, publications);
    }

    @Test
    void csrfIsRequiredEvenForNoConsentAndDntShortCircuits() throws Exception {
        byte[] noConsent = json.writeValueAsBytes(Map.of("analyticsConsent", false));

        assertProblem(mvc.perform(jsonRequest(noConsent)).andReturn(), 403, "CSRF_INVALID");
        assertProblem(mvc.perform(jsonRequest(noConsent).header("DNT", "1")).andReturn(),
                403, "CSRF_INVALID");
        CsrfExchange csrf = csrf();
        assertProblem(mvc.perform(jsonRequest(noConsent)
                                .cookie(csrf.cookie())
                                .header(csrf.headerName(), csrf.token() + "-invalid"))
                        .andReturn(),
                403,
                "CSRF_INVALID");

        assertThat(events.count()).isZero();
        verifyNoInteractions(limiter, publications);
    }

    @Test
    void malformedUnknownDuplicateAndExtraEventPropertiesAreRejected()
            throws Exception {
        Map<String, Object> unknownRoot = validBatch();
        unknownRoot.put("screenWidth", 1920);
        Map<String, Object> unknownEvent = validBatch();
        event(unknownEvent).put("revenue", "private-value");
        Map<String, Object> wrongConsent = validBatch();
        wrongConsent.put("analyticsConsent", "true");
        String valid = json.writeValueAsString(validBatch());
        String duplicate = valid.replace(
                "\"analyticsConsent\":true",
                "\"analyticsConsent\":true,\"analyticsConsent\":true");
        assertThat(duplicate).isNotEqualTo(valid);
        CsrfExchange csrf = csrf();

        for (byte[] body : List.of(
                valid.substring(0, valid.length() - 1).getBytes(UTF_8),
                json.writeValueAsBytes(unknownRoot),
                json.writeValueAsBytes(unknownEvent),
                json.writeValueAsBytes(wrongConsent),
                duplicate.getBytes(UTF_8))) {
            assertProblem(performJson(csrf, body), 400, "MALFORMED_REQUEST");
        }

        assertThat(events.count()).isZero();
        verifyNoInteractions(limiter, publications);
    }

    @Test
    void rawBodyBoundaryIsExactAndCheckedBeforeNoConsentShortcut() throws Exception {
        CsrfExchange csrf = csrf();

        assertNoContent(performJson(csrf, paddedNoConsentJson(32_768)));
        assertProblem(performJson(csrf, paddedNoConsentJson(32_769)),
                413, "PAYLOAD_TOO_LARGE");

        assertThat(events.count()).isZero();
        verifyNoInteractions(limiter, publications);
    }

    @Test
    void nonJsonContentIsRejectedBeforeAnalyticsCode() throws Exception {
        CsrfExchange csrf = csrf();
        MvcResult result = mvc.perform(post(EVENTS_PATH)
                        .with(trustedClient())
                        .cookie(csrf.cookie())
                        .header(csrf.headerName(), csrf.token())
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("not-json"))
                .andReturn();

        assertProblem(result, 415, "UNSUPPORTED_MEDIA_TYPE");
        assertThat(events.count()).isZero();
        verifyNoInteractions(limiter, publications);
    }

    @Test
    void consentedSemanticFailuresUseStablePrivateErrorContract() throws Exception {
        Map<String, Object> invalidId = validBatch();
        invalidId.put("visitorId", "not-base64url");
        Map<String, Object> emptyEvents = validBatch();
        emptyEvents.put("events", List.of());
        Map<String, Object> tooMany = validBatch();
        tooMany.put("events", java.util.Collections.nCopies(21, validEvent()));
        Map<String, Object> unknownPage = validBatch();
        event(unknownPage).put("pageKey", "PRIVATE_PATH?identity=1");
        Map<String, Object> unpublished = validProjectBatch();
        when(publications.isCurrentPublishedProject(PROJECT_ID)).thenReturn(false);

        for (Map<String, Object> request : List.of(
                invalidId, emptyEvents, tooMany, unknownPage, unpublished)) {
            assertProblem(
                    performJson(csrf(), json.writeValueAsBytes(request)),
                    422,
                    "ANALYTICS_EVENT_INVALID");
        }

        assertThat(events.count()).isZero();
    }

    @Test
    void semanticFailureNeverLogsOrReturnsRawAnalyticsIdentity(CapturedOutput output)
            throws Exception {
        String rawVisitor = "private-visitor-id-that-must-never-appear";
        Map<String, Object> invalid = validBatch();
        invalid.put("visitorId", rawVisitor);

        MvcResult result = performJson(csrf(), json.writeValueAsBytes(invalid));

        assertProblem(result, 422, "ANALYTICS_EVENT_INVALID");
        assertThat(result.getResponse().getContentAsString(UTF_8))
                .doesNotContain(rawVisitor, SESSION_ID, REFERRER, USER_AGENT, RAW_CLIENT_IP);
        assertThat(output.getAll())
                .doesNotContain(rawVisitor, SESSION_ID, REFERRER, USER_AGENT, RAW_CLIENT_IP);
        assertThat(events.count()).isZero();
    }

    @Test
    void crawlerIsIgnoredAfterOneHashedRateLimitDecision() throws Exception {
        Map<String, Object> consentOnly = Map.of("analyticsConsent", true);

        MvcResult result = performJson(
                csrf(),
                json.writeValueAsBytes(consentOnly),
                trustedClient(),
                "Example preview crawler/1.0");

        assertNoContent(result);
        assertThat(events.count()).isZero();
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(limiter).consume(eq(RATE_POLICY), subject.capture());
        assertHashedSubject(subject.getValue());
        verifyNoMoreInteractions(limiter);
        verifyNoInteractions(publications);
    }

    @Test
    void crawlerDetectionExaminesMultipleUserAgentHeaderValues() throws Exception {
        CsrfExchange csrf = csrf();
        MvcResult result = mvc.perform(jsonRequest(json.writeValueAsBytes(validBatch()))
                        .with(trustedClient())
                        .header(HttpHeaders.USER_AGENT, "Friendly Browser", "Preview crawler")
                        .cookie(csrf.cookie())
                        .header(csrf.headerName(), csrf.token()))
                .andReturn();

        assertNoContent(result);
        assertThat(events.count()).isZero();
        verify(limiter).consume(eq(RATE_POLICY), anyString());
        verifyNoMoreInteractions(limiter);
        verifyNoInteractions(publications);
    }

    @Test
    void rateLimitDenialReturnsRetryAfterAndPersistsNothing() throws Exception {
        when(limiter.consume(anyString(), anyString())).thenReturn(RateLimitDecision.deny(37));

        MvcResult result = performJson(csrf(), json.writeValueAsBytes(validBatch()));

        JsonNode problem = assertProblem(result, 429, "ANALYTICS_RATE_LIMITED");
        assertThat(result.getResponse().getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("37");
        assertThat(problem.path("retryAfterSeconds").asLong()).isEqualTo(37);
        assertThat(events.count()).isZero();
        verifyNoInteractions(publications);
    }

    @Test
    void onlyTrustedProxyAddressAffectsHashedSubject() throws Exception {
        byte[] noCrawler = json.writeValueAsBytes(validBatch());
        performJson(csrf(), noCrawler, client("198.51.100.20", "203.0.113.41"), USER_AGENT);
        clearEvents();
        performJson(csrf(), noCrawler, client("198.51.100.20"), USER_AGENT);
        clearEvents();
        performJson(csrf(), noCrawler, client("127.0.0.1", "203.0.113.42"), USER_AGENT);
        clearEvents();
        performJson(csrf(), noCrawler, client("203.0.113.42"), USER_AGENT);

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(limiter, org.mockito.Mockito.times(4))
                .consume(eq(RATE_POLICY), subject.capture());
        List<String> subjects = subject.getAllValues();
        assertThat(subjects).allSatisfy(PublicAnalyticsControllerTest::assertHashedSubject);
        assertThat(subjects.get(0)).isEqualTo(subjects.get(1));
        assertThat(subjects.get(2)).isNotEqualTo(subjects.get(0));
        assertThat(subjects.get(2)).isEqualTo(subjects.get(3));
    }

    private MvcResult performJson(CsrfExchange csrf, byte[] body) throws Exception {
        return performJson(csrf, body, trustedClient(), USER_AGENT);
    }

    private MvcResult performJson(
            CsrfExchange csrf,
            byte[] body,
            RequestPostProcessor client,
            String userAgent) throws Exception {
        return mvc.perform(jsonRequest(body)
                        .with(client)
                        .header(HttpHeaders.USER_AGENT, userAgent)
                        .cookie(csrf.cookie())
                        .header(csrf.headerName(), csrf.token()))
                .andReturn();
    }

    private MockHttpServletRequestBuilder jsonRequest(byte[] body) {
        return post(EVENTS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private void clearEvents() {
        migratorJdbc().sql("truncate table portfolio.analytics_event").update();
    }

    private CsrfExchange csrf() throws Exception {
        MvcResult source = mvc.perform(get(CSRF_PATH)).andReturn();
        assertThat(source.getResponse().getStatus()).isEqualTo(200);
        Cookie cookie = source.getResponse().getCookie(XSRF_COOKIE);
        assertThat(cookie).isNotNull();
        JsonNode response = body(source);
        String headerName = response.path("headerName").asText();
        String token = response.path("token").asText();
        assertThat(headerName).isEqualTo("X-XSRF-TOKEN");
        assertThat(token).isNotBlank();
        return new CsrfExchange(cookie, headerName, token);
    }

    private void assertNoContent(MvcResult result) {
        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(result.getResponse().getContentAsByteArray()).isEmpty();
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
    }

    private JsonNode assertProblem(MvcResult result, int status, String code) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertThat(result.getResponse().getContentType())
                .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        JsonNode problem = body(result);
        assertThat(problem.path("status").asInt()).isEqualTo(status);
        assertThat(problem.path("code").asText()).isEqualTo(code);
        assertThat(problem.path("traceId").asText()).matches("[0-9a-f]{32}");
        return problem;
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    private Map<String, Object> validBatch() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("analyticsConsent", true);
        request.put("visitorId", VISITOR_ID);
        request.put("sessionId", SESSION_ID);
        request.put("events", List.of(validEvent()));
        return request;
    }

    private Map<String, Object> validProjectBatch() {
        Map<String, Object> request = validBatch();
        Map<String, Object> project = new LinkedHashMap<>(validEvent());
        project.put("type", "PROJECT_VIEW");
        project.put("pageKey", "PROJECT_DETAIL");
        project.put("projectId", PROJECT_ID);
        request.put("events", List.of(project));
        return request;
    }

    private Map<String, Object> validEvent() {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", EVENT_ID);
        event.put("type", "PAGE_VIEW");
        event.put("pageKey", "HOME");
        event.put("projectId", null);
        event.put("referrer", REFERRER);
        event.put("locale", "en");
        return event;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> event(Map<String, Object> batch) {
        return (Map<String, Object>) ((List<?>) batch.get("events")).get(0);
    }

    private byte[] paddedNoConsentJson(int rawBytes) throws Exception {
        byte[] encoded = json.writeValueAsBytes(Map.of("analyticsConsent", false));
        assertThat(encoded.length).isLessThan(rawBytes);
        byte[] padded = new byte[rawBytes];
        Arrays.fill(padded, (byte) ' ');
        System.arraycopy(encoded, 0, padded, 0, encoded.length);
        return padded;
    }

    private static void assertHashedSubject(String subject) {
        assertThat(subject)
                .matches("[0-9a-f]{64}")
                .isNotEqualTo(RAW_CLIENT_IP)
                .doesNotContain(VISITOR_ID, SESSION_ID);
    }

    private static void assertNoSensitiveOutput(String output) {
        assertThat(output)
                .doesNotContain(
                        RAW_CLIENT_IP,
                        USER_AGENT,
                        VISITOR_ID,
                        SESSION_ID,
                        REFERRER,
                        "private/path",
                        "identity=value");
    }

    private static RequestPostProcessor trustedClient() {
        return client("127.0.0.1", RAW_CLIENT_IP);
    }

    private static RequestPostProcessor client(
            String remoteAddress, String... forwardedAddresses) {
        return request -> {
            request.setRemoteAddr(remoteAddress);
            for (String forwardedAddress : forwardedAddresses) {
                request.addHeader("X-Real-IP", forwardedAddress);
            }
            return request;
        };
    }

    private static String browserId(int firstByte) {
        byte[] value = new byte[16];
        for (int index = 0; index < value.length; index++) {
            value[index] = (byte) (firstByte + index);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private record CsrfExchange(Cookie cookie, String headerName, String token) {}
}
