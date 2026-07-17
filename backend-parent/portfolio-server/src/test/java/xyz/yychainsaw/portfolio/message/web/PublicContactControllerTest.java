package xyz.yychainsaw.portfolio.message.web;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import xyz.yychainsaw.portfolio.common.ratelimit.RateLimitDecision;
import xyz.yychainsaw.portfolio.common.ratelimit.RateLimiter;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
@AutoConfigureMockMvc
@Isolated
@ExtendWith(OutputCaptureExtension.class)
class PublicContactControllerTest extends PostgresIntegrationTestBase {
    private static final String CONTACT_PATH = "/api/public/contact";
    private static final String CSRF_PATH = "/api/admin/auth/csrf";
    private static final String XSRF_COOKIE = "XSRF-TOKEN";
    private static final String RATE_POLICY = "public-contact";
    private static final String RAW_CLIENT_IP = "203.0.113.9";
    private static final String NAME = "Player One";
    private static final String EMAIL = "player@example.com";
    private static final String INVALID_EMAIL = "player-at-example.invalid";
    private static final String SUBJECT = "UE collaboration";
    private static final String MESSAGE = "I would like to discuss your project.";
    private static final String HONEYPOT = "https://spam.invalid";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;

    @MockitoBean RateLimiter limiter;

    @BeforeEach
    void resetDatabaseAndLimiter() {
        deleteContactRows();
        reset(limiter);
        when(limiter.consume(anyString(), anyString())).thenReturn(RateLimitDecision.allow());
    }

    @AfterEach
    void cleanDatabaseAndLimiter() {
        try {
            deleteContactRows();
        } finally {
            reset(limiter);
        }
    }

    @Test
    void acceptedSubmissionUsesRealCsrfHashesRateSubjectAndReturnsOnlyAcceptance(
            CapturedOutput output) throws Exception {
        MvcResult result = performJson(csrf(), json.writeValueAsBytes(validRequest()));

        assertAccepted(result);
        assertDatabaseCounts(1, 1);

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(limiter).consume(eq(RATE_POLICY), subject.capture());
        assertHashedSubject(subject.getValue());
        verifyNoMoreInteractions(limiter);
        assertNoSensitiveData(output.getAll());
    }

    @Test
    void csrfFailuresHaveZeroLimiterOrDatabaseSideEffects(CapturedOutput output) throws Exception {
        byte[] request = json.writeValueAsBytes(validRequest());

        assertProblem(mvc.perform(jsonRequest(request)).andReturn(), 403, "CSRF_INVALID");
        assertProblem(
                mvc.perform(jsonRequest("{".getBytes(UTF_8))).andReturn(),
                403,
                "CSRF_INVALID");
        assertProblem(
                mvc.perform(jsonRequest(paddedHoneypotJson(32_769))).andReturn(),
                403,
                "CSRF_INVALID");

        CsrfExchange csrf = csrf();
        assertProblem(
                mvc.perform(jsonRequest(request).header(csrf.headerName(), csrf.token()))
                        .andReturn(),
                403,
                "CSRF_INVALID");
        assertProblem(
                mvc.perform(jsonRequest(request)
                                .cookie(csrf.cookie())
                                .header(csrf.headerName(), csrf.token() + "-different"))
                        .andReturn(),
                403,
                "CSRF_INVALID");

        assertDatabaseCounts(0, 0);
        verifyNoInteractions(limiter);
        assertNoSensitiveData(output.getAll());
    }

    @Test
    void populatedHoneypotReturnsGenericAcceptanceBeforeValidationOrRateLimit()
            throws Exception {
        byte[] otherwiseInvalid = json.writeValueAsBytes(Map.of("website", HONEYPOT));

        MvcResult result = performJson(csrf(), otherwiseInvalid);

        assertAccepted(result);
        assertDatabaseCounts(0, 0);
        verifyNoInteractions(limiter);
    }

    @Test
    void wellFormedFieldFailuresUseStable422ValidationContract(CapturedOutput output)
            throws Exception {
        Map<String, Object> missingConsent = validRequest();
        missingConsent.remove("privacyAccepted");
        Map<String, Object> invalidEmail = validRequest();
        invalidEmail.put("email", INVALID_EMAIL);
        Map<String, Object> overlongName = validRequest();
        overlongName.put("name", "n".repeat(101));
        CsrfExchange csrf = csrf();

        for (InvalidRequest invalid : List.of(
                new InvalidRequest(missingConsent, "privacyAccepted"),
                new InvalidRequest(invalidEmail, "email"),
                new InvalidRequest(overlongName, "name"))) {
            JsonNode problem = assertProblem(
                    performJson(csrf, json.writeValueAsBytes(invalid.body())),
                    422,
                    "VALIDATION_ERROR");
            assertThat(problem.path("fieldErrors").has(invalid.field())).isTrue();
        }

        assertDatabaseCounts(0, 0);
        assertNoSensitiveData(output.getAll());
    }

    @Test
    void malformedUnknownDuplicateAndWrongTypeJsonUseStable400Contract(CapturedOutput output)
            throws Exception {
        String valid = json.writeValueAsString(validRequest());
        Map<String, Object> unknown = validRequest();
        unknown.put("unexpected", "never-reflect-this");
        Map<String, Object> wrongType = validRequest();
        wrongType.put("name", Map.of("nested", true));
        Map<String, Object> numericText = validRequest();
        numericText.put("name", 123);
        Map<String, Object> stringBoolean = validRequest();
        stringBoolean.put("privacyAccepted", "true");
        String duplicate = valid.replace(
                "\"website\":\"\"", "\"website\":\"\",\"website\":\"\"");
        assertThat(duplicate).isNotEqualTo(valid);
        CsrfExchange csrf = csrf();

        for (byte[] request : List.of(
                valid.substring(0, valid.length() - 1).getBytes(UTF_8),
                json.writeValueAsBytes(unknown),
                duplicate.getBytes(UTF_8),
                json.writeValueAsBytes(wrongType),
                json.writeValueAsBytes(numericText),
                json.writeValueAsBytes(stringBoolean))) {
            assertProblem(performJson(csrf, request), 400, "MALFORMED_REQUEST");
        }

        assertDatabaseCounts(0, 0);
        verifyNoInteractions(limiter);
        assertNoSensitiveData(output.getAll());
    }

    @Test
    void rawJsonBoundaryAllows32768BytesButRejects32769WithoutConsumingQuota()
            throws Exception {
        CsrfExchange csrf = csrf();
        byte[] maximum = paddedHoneypotJson(32_768);
        byte[] oversized = paddedHoneypotJson(32_769);

        assertAccepted(performJson(csrf, maximum));
        assertProblem(performJson(csrf, oversized), 413, "PAYLOAD_TOO_LARGE");

        assertDatabaseCounts(0, 0);
        verifyNoInteractions(limiter);
    }

    @Test
    void nonJsonContentIsRejectedWith415BeforeApplicationCode(CapturedOutput output)
            throws Exception {
        CsrfExchange csrf = csrf();
        MockHttpServletRequestBuilder request = post(CONTACT_PATH)
                .with(trustedClient())
                .cookie(csrf.cookie())
                .header(csrf.headerName(), csrf.token())
                .contentType(MediaType.TEXT_PLAIN)
                .accept(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(validRequest()));

        assertProblem(mvc.perform(request).andReturn(), 415, "UNSUPPORTED_MEDIA_TYPE");
        assertDatabaseCounts(0, 0);
        verifyNoInteractions(limiter);
        assertNoSensitiveData(output.getAll());
    }

    @Test
    void rateLimitDenialReturnsRetryAfterAndWritesNothing(CapturedOutput output) throws Exception {
        reset(limiter);
        when(limiter.consume(anyString(), anyString())).thenReturn(RateLimitDecision.deny(37));

        MvcResult result = performJson(csrf(), json.writeValueAsBytes(validRequest()));

        JsonNode problem = assertProblem(result, 429, "CONTACT_RATE_LIMITED");
        assertThat(result.getResponse().getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("37");
        assertThat(problem.path("retryAfterSeconds").asLong()).isEqualTo(37);
        assertDatabaseCounts(0, 0);

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(limiter).consume(eq(RATE_POLICY), subject.capture());
        assertHashedSubject(subject.getValue());
        verifyNoMoreInteractions(limiter);
        assertNoSensitiveData(output.getAll());
    }

    @Test
    void onlyTrustedProxyHeadersChangeTheHashedRateLimitSubject() throws Exception {
        byte[] request = json.writeValueAsBytes(validRequest());

        performJson(csrf(), request, client("198.51.100.20", "203.0.113.41"));
        performJson(csrf(), request, client("198.51.100.20"));
        performJson(csrf(), request, client("127.0.0.1", "203.0.113.42"));
        performJson(csrf(), request, client("127.0.0.1", "not-an-ip"));
        performJson(
                csrf(),
                request,
                client("127.0.0.1", "203.0.113.43", "203.0.113.44"));
        performJson(csrf(), request, client("203.0.113.42"));

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(limiter, org.mockito.Mockito.times(6))
                .consume(eq(RATE_POLICY), subject.capture());
        List<String> subjects = subject.getAllValues();
        assertThat(subjects).allSatisfy(PublicContactControllerTest::assertHashedSubject);
        assertThat(subjects.get(0)).isEqualTo(subjects.get(1));
        assertThat(subjects.get(2)).isNotEqualTo(subjects.get(0));
        assertThat(subjects.get(3)).isEqualTo(subjects.get(4));
        assertThat(subjects.get(2)).isEqualTo(subjects.get(5));
        assertDatabaseCounts(1, 1);
        verifyNoMoreInteractions(limiter);
    }

    private MvcResult performJson(CsrfExchange csrf, byte[] request) throws Exception {
        return performJson(csrf, request, trustedClient());
    }

    private MvcResult performJson(
            CsrfExchange csrf, byte[] request, RequestPostProcessor client) throws Exception {
        return mvc.perform(jsonRequest(request, client)
                        .cookie(csrf.cookie())
                        .header(csrf.headerName(), csrf.token()))
                .andReturn();
    }

    private MockHttpServletRequestBuilder jsonRequest(byte[] request) {
        return jsonRequest(request, trustedClient());
    }

    private MockHttpServletRequestBuilder jsonRequest(
            byte[] request, RequestPostProcessor client) {
        return post(CONTACT_PATH)
                .with(client)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(request);
    }

    private CsrfExchange csrf() throws Exception {
        MvcResult source = mvc.perform(get(CSRF_PATH)).andReturn();
        assertThat(source.getResponse().getStatus()).isEqualTo(200);
        assertThat(source.getResponse().getContentType())
                .startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(source.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        JsonNode response = body(source);
        Cookie cookie = source.getResponse().getCookie(XSRF_COOKIE);
        assertThat(cookie).isNotNull();
        String headerName = response.path("headerName").asText();
        String token = response.path("token").asText();
        assertThat(headerName).isEqualTo("X-XSRF-TOKEN");
        assertThat(token).isNotBlank();
        return new CsrfExchange(cookie, headerName, token);
    }

    private void assertAccepted(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(202);
        assertThat(result.getResponse().getContentType())
                .startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL))
                .contains("no-store");
        JsonNode response = body(result);
        List<String> fields = new ArrayList<>();
        response.fieldNames().forEachRemaining(fields::add);
        assertThat(response.isObject()).isTrue();
        assertThat(fields).containsExactly("accepted");
        assertThat(response.path("accepted").asBoolean()).isTrue();
        assertNoSensitiveData(result.getResponse().getContentAsString(UTF_8));
    }

    private JsonNode assertProblem(MvcResult result, int status, String code) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertThat(result.getResponse().getContentType())
                .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL))
                .contains("no-store");
        JsonNode problem = body(result);
        assertThat(problem.path("status").asInt()).isEqualTo(status);
        assertThat(problem.path("code").asText()).isEqualTo(code);
        assertThat(problem.path("traceId").asText()).matches("[0-9a-f]{32}");
        assertThat(problem.path("fieldErrors").isObject()).isTrue();
        assertNoSensitiveData(result.getResponse().getContentAsString(UTF_8));
        return problem;
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    private Map<String, Object> validRequest() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("name", NAME);
        request.put("email", EMAIL);
        request.put("subject", SUBJECT);
        request.put("message", MESSAGE);
        request.put("website", "");
        request.put("privacyAccepted", true);
        return request;
    }

    private byte[] paddedHoneypotJson(int rawBytes) throws Exception {
        byte[] encoded = json.writeValueAsBytes(Map.of("website", HONEYPOT));
        assertThat(encoded.length).isLessThan(rawBytes);
        byte[] padded = new byte[rawBytes];
        Arrays.fill(padded, (byte) ' ');
        System.arraycopy(encoded, 0, padded, 0, encoded.length);
        assertThat(padded).hasSize(rawBytes);
        return padded;
    }

    private void assertDatabaseCounts(long messages, long outbox) {
        assertThat(rowCount("portfolio.contact_message")).isEqualTo(messages);
        assertThat(rowCount("portfolio.email_outbox")).isEqualTo(outbox);
    }

    private long rowCount(String table) {
        return jdbc.sql("select count(*) from " + table).query(Long.class).single();
    }

    private void deleteContactRows() {
        jdbc.sql("delete from portfolio.contact_message").update();
    }

    private static void assertHashedSubject(String subject) {
        assertThat(subject)
                .matches("[0-9a-f]{64}")
                .isNotEqualTo(RAW_CLIENT_IP)
                .doesNotContain(NAME, EMAIL, SUBJECT, MESSAGE);
    }

    private static void assertNoSensitiveData(String output) {
        assertThat(output)
                .doesNotContain(
                        NAME,
                        EMAIL,
                        INVALID_EMAIL,
                        SUBJECT,
                        MESSAGE,
                        HONEYPOT,
                        RAW_CLIENT_IP,
                        "messageId");
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

    private record CsrfExchange(Cookie cookie, String headerName, String token) {
    }

    private record InvalidRequest(Map<String, Object> body, String field) {
    }
}
