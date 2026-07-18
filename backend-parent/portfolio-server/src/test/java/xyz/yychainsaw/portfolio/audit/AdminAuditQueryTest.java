package xyz.yychainsaw.portfolio.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.time.TimeProvider;
import jakarta.servlet.http.Cookie;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import xyz.yychainsaw.portfolio.api.admin.audit.AdminAuditItem;
import xyz.yychainsaw.portfolio.api.admin.audit.AdminAuditPage;
import xyz.yychainsaw.portfolio.auth.CurrentAdminProvider;
import xyz.yychainsaw.portfolio.auth.crypto.TotpService;
import xyz.yychainsaw.portfolio.auth.model.AdminStatus;
import xyz.yychainsaw.portfolio.auth.model.AdminUser;
import xyz.yychainsaw.portfolio.auth.persistence.AdminUserRepository;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "portfolio.security.session.cleanup-interval=PT24H")
@Isolated
@ExtendWith(OutputCaptureExtension.class)
class AdminAuditQueryTest extends PostgresIntegrationTestBase {
    private static final String AUDIT_PATH = "/api/admin/audit";
    private static final String CSRF_PATH = "/api/admin/auth/csrf";
    private static final String PASSWORD_PATH = "/api/admin/auth/password";
    private static final String SECOND_FACTOR_PATH = "/api/admin/auth/second-factor";
    private static final String SESSION_COOKIE = "PORTFOLIO_SESSION";
    private static final String XSRF_COOKIE = "XSRF-TOKEN";
    private static final String ADMIN_PASSWORD = "Correct-Horse-Audit-Battery-47!";
    private static final AtomicInteger REMOTE_SEQUENCE = new AtomicInteger();

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AdminUserRepository admins;
    @Autowired PasswordEncoder passwords;
    @Autowired TotpService totp;
    @Autowired CodeGenerator totpCodes;
    @Autowired TimeProvider totpTime;
    @Autowired JdbcClient jdbc;
    @MockitoSpyBean CurrentAdminProvider currentAdmin;
    @MockitoSpyBean AdminAuditQueryRepository queries;
    @MockitoSpyBean AuditMetadataRedactor redactor;

    private Fixture fixture;

    @AfterEach
    void cleanFixture() {
        reset(currentAdmin, queries, redactor);
        if (fixture != null) {
            fixture.close();
            fixture = null;
        }
    }

    @Test
    void requiresAuthenticationSupportsHeadAndNeverCaches() throws Exception {
        MvcResult unauthenticated = mvc.perform(get(AUDIT_PATH)).andReturn();
        assertProblem(unauthenticated, 401, "AUTHENTICATION_REQUIRED", Map.of());

        Fixture admin = fixture();
        AuthenticatedSession active = login(admin);
        MvcResult page = mvc.perform(get(AUDIT_PATH).cookie(active.cookie())).andReturn();
        assertThat(page.getResponse().getStatus()).isEqualTo(200);
        assertNoStore(page);

        MvcResult headers = mvc.perform(head(AUDIT_PATH).cookie(active.cookie())).andReturn();
        assertThat(headers.getResponse().getStatus()).isEqualTo(200);
        assertNoStore(headers);
        assertThat(headers.getResponse().getContentAsByteArray()).isEmpty();

        MvcResult mutation = mvc.perform(post(AUDIT_PATH).cookie(active.cookie())).andReturn();
        assertProblem(mutation, 403, "CSRF_INVALID", Map.of());
    }

    @Test
    void pagesByIndexedKeysetWithoutOverlapAndRedactsMetadata() throws Exception {
        Fixture admin = fixture();
        AuthenticatedSession active = login(admin);
        String action = admin.action("PAGE");
        Instant firstAt = Instant.parse("2026-07-16T10:01:00.123456Z");
        Instant tiedAt = Instant.parse("2026-07-16T10:02:00.123456Z");
        Instant newestAt = Instant.parse("2026-07-16T10:03:00.123456Z");
        UUID first = id(100);
        UUID tiedFirst = id(101);
        UUID tiedSecond = id(102);
        UUID newest = id(103);
        insertAudit(first, null, action, "SUCCESS", firstAt, admin.adminId.toString(),
                Map.of("channel", "SYSTEM", "password", "never-return"));
        insertAudit(tiedSecond, admin.adminId, action, "FAILURE", tiedAt,
                admin.adminId.toString(), Map.of("method", "RECOVERY_CODE"));
        insertAudit(tiedFirst, admin.adminId, action, "SUCCESS", tiedAt,
                admin.adminId.toString(), Map.of("method", "TOTP", "rawIp", "203.0.113.9"));
        insertAudit(newest, admin.adminId, action, "SUCCESS", newestAt,
                admin.adminId.toString(), Map.of("stage", "CONFIRM", "staleActor", "false"));

        MvcResult firstPage = mvc.perform(get(AUDIT_PATH)
                        .cookie(active.cookie())
                        .param("action", action)
                        .param("limit", "2"))
                .andReturn();
        assertThat(firstPage.getResponse().getStatus()).isEqualTo(200);
        assertNoStore(firstPage);
        JsonNode firstBody = body(firstPage);
        assertThat(ids(firstBody)).containsExactly(newest.toString(), tiedFirst.toString());
        assertThat(firstBody.path("items").path(0).path("metadata").path("stage").asText())
                .isEqualTo("CONFIRM");
        assertThat(firstBody.toString())
                .doesNotContain("never-return", "rawIp", "203.0.113.9", "password");
        String cursor = firstBody.path("nextCursor").asText();
        assertThat(cursor).isNotBlank().doesNotContain(newestAt.toString(), newest.toString());

        UUID concurrentNewer = id(104);
        insertAudit(concurrentNewer, admin.adminId, action, "SUCCESS",
                Instant.parse("2026-07-16T10:04:00.123456Z"), admin.adminId.toString(),
                Map.of("next", "IGNORED_BY_OLD_CURSOR"));

        MvcResult secondPage = mvc.perform(get(AUDIT_PATH)
                        .cookie(active.cookie())
                        .param("action", action)
                        .param("limit", "2")
                        .param("cursor", cursor))
                .andReturn();
        assertThat(secondPage.getResponse().getStatus()).isEqualTo(200);
        JsonNode secondBody = body(secondPage);
        assertThat(ids(secondBody)).containsExactly(tiedSecond.toString(), first.toString());
        assertThat(secondBody.has("nextCursor")).isFalse();
        assertThat(secondBody.path("items").path(1).has("actorAdminId")).isFalse();
        assertThat(ids(firstBody)).doesNotContainAnyElementsOf(ids(secondBody));
        assertThat(ids(secondBody)).doesNotContain(concurrentNewer.toString());
    }

    @Test
    void combinesExactFiltersWithInclusiveFromAndExclusiveTo() throws Exception {
        Fixture admin = fixture();
        AuthenticatedSession active = login(admin);
        String action = admin.action("FILTER");
        insertAudit(id(201), admin.adminId, action, "SUCCESS",
                Instant.parse("2026-07-16T11:01:00Z"), admin.adminId.toString(), Map.of());
        insertAudit(id(202), admin.adminId, action, "FAILURE",
                Instant.parse("2026-07-16T11:02:00Z"), admin.adminId.toString(), Map.of());
        insertAudit(id(203), admin.adminId, action, "SUCCESS",
                Instant.parse("2026-07-16T11:03:00Z"), admin.adminId.toString(), Map.of());
        insertAudit(id(204), admin.adminId, action, "SUCCESS",
                Instant.parse("2026-07-16T11:04:00Z"), admin.adminId.toString(), Map.of());

        MvcResult result = mvc.perform(get(AUDIT_PATH)
                        .cookie(active.cookie())
                        .param("action", action)
                        .param("outcome", "success")
                        .param("from", "2026-07-16T11:03:00Z")
                        .param("to", "2026-07-16T11:04:00Z"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(ids(body(result))).containsExactly(id(203).toString());

        MvcResult noMatch = mvc.perform(get(AUDIT_PATH)
                        .cookie(active.cookie())
                        .param("action", admin.action("NO_MATCH")))
                .andReturn();
        assertThat(noMatch.getResponse().getStatus()).isEqualTo(200);
        JsonNode noMatchBody = body(noMatch);
        assertThat(noMatchBody.path("items")).isEmpty();
        assertThat(noMatchBody.has("nextCursor")).isFalse();

        String nullableAction = admin.action("NULLABLE_SYSTEM");
        insertAudit(id(205), null, nullableAction, "SUCCESS",
                Instant.parse("2026-07-16T11:05:00.123456Z"), null, Map.of());
        MvcResult nullable = mvc.perform(get(AUDIT_PATH)
                        .cookie(active.cookie())
                        .param("action", nullableAction))
                .andReturn();
        JsonNode nullableItem = body(nullable).path("items").path(0);
        assertThat(nullable.getResponse().getStatus()).isEqualTo(200);
        assertThat(nullableItem.path("id").asText()).isEqualTo(id(205).toString());
        assertThat(nullableItem.has("actorAdminId")).isFalse();
        assertThat(nullableItem.has("targetId")).isFalse();
    }

    @Test
    void defaultsToFiftyAndAcceptsOneAndOneHundred() throws Exception {
        Fixture admin = fixture();
        AuthenticatedSession active = login(admin);
        String action = admin.action("LIMITS");
        Instant base = Instant.parse("2026-07-16T12:00:00Z");
        for (int index = 0; index < 101; index++) {
            insertAudit(
                    id(600 + index),
                    admin.adminId,
                    action,
                    "SUCCESS",
                    base.plusSeconds(index),
                    admin.adminId.toString(),
                    Map.of());
        }

        JsonNode defaultPage = body(mvc.perform(get(AUDIT_PATH)
                        .cookie(active.cookie()).param("action", action))
                .andReturn());
        assertThat(defaultPage.path("items").size()).isEqualTo(50);
        assertThat(defaultPage.path("nextCursor").asText()).isNotBlank();

        JsonNode single = body(mvc.perform(get(AUDIT_PATH)
                        .cookie(active.cookie()).param("action", action).param("limit", "1"))
                .andReturn());
        assertThat(single.path("items").size()).isEqualTo(1);
        assertThat(single.path("nextCursor").asText()).isNotBlank();

        JsonNode maximum = body(mvc.perform(get(AUDIT_PATH)
                        .cookie(active.cookie()).param("action", action).param("limit", "100"))
                .andReturn());
        assertThat(maximum.path("items").size()).isEqualTo(100);
        String cursor = maximum.path("nextCursor").asText();
        assertThat(cursor).isNotBlank();

        JsonNode last = body(mvc.perform(get(AUDIT_PATH)
                        .cookie(active.cookie())
                        .param("action", action)
                        .param("limit", "100")
                        .param("cursor", cursor))
                .andReturn());
        assertThat(last.path("items").size()).isEqualTo(1);
        assertThat(last.has("nextCursor")).isFalse();
    }

    @Test
    void rejectsEveryInvalidQueryBeforeRepositoryAccess() throws Exception {
        Fixture admin = fixture();
        AuthenticatedSession active = login(admin);
        List<InvalidQuery> invalid = List.of(
                new InvalidQuery("limit", "0", "AUDIT_QUERY_INVALID", "limit"),
                new InvalidQuery("limit", "01", "AUDIT_QUERY_INVALID", "limit"),
                new InvalidQuery("limit", "101", "AUDIT_QUERY_INVALID", "limit"),
                new InvalidQuery("limit", "+1", "AUDIT_QUERY_INVALID", "limit"),
                new InvalidQuery("limit", "-1", "AUDIT_QUERY_INVALID", "limit"),
                new InvalidQuery("limit", "1.0", "AUDIT_QUERY_INVALID", "limit"),
                new InvalidQuery("limit", "abc", "AUDIT_QUERY_INVALID", "limit"),
                new InvalidQuery("limit", "9".repeat(17), "AUDIT_QUERY_INVALID", "limit"),
                new InvalidQuery("action", "lowercase", "AUDIT_QUERY_INVALID", "action"),
                new InvalidQuery("action", "A".repeat(129), "AUDIT_QUERY_INVALID", "action"),
                new InvalidQuery("outcome", "UNKNOWN", "AUDIT_QUERY_INVALID", "outcome"),
                new InvalidQuery("outcome", "S".repeat(17),
                        "AUDIT_QUERY_INVALID", "outcome"),
                new InvalidQuery("from", "2026-07-16T10:00:00.0000001Z",
                        "AUDIT_QUERY_INVALID", "from"),
                new InvalidQuery("from", "2".repeat(65), "AUDIT_QUERY_INVALID", "from"),
                new InvalidQuery("from", "1969-12-31T23:59:59Z",
                        "AUDIT_QUERY_INVALID", "from"),
                new InvalidQuery("to", "not-an-instant", "AUDIT_QUERY_INVALID", "to"),
                new InvalidQuery("cursor", "not-a-cursor", "AUDIT_CURSOR_INVALID", "cursor"),
                new InvalidQuery("cursor", "A".repeat(87), "AUDIT_CURSOR_INVALID", "cursor"));

        for (InvalidQuery query : invalid) {
            clearInvocations(queries);
            MvcResult result = mvc.perform(get(AUDIT_PATH)
                            .cookie(active.cookie())
                            .param(query.parameter(), query.value()))
                    .andReturn();
            assertProblem(result, 422, query.code(), Map.of(query.field(), "invalid"));
            verifyNoInteractions(queries);
        }

        clearInvocations(queries);
        MvcResult range = mvc.perform(get(AUDIT_PATH)
                        .cookie(active.cookie())
                        .param("from", "2026-07-16T12:00:00Z")
                        .param("to", "2026-07-16T12:00:00Z"))
                .andReturn();
        assertProblem(range, 422, "AUDIT_QUERY_INVALID", Map.of("to", "invalid"));
        verifyNoInteractions(queries);

        clearInvocations(queries);
        MvcResult reversed = mvc.perform(get(AUDIT_PATH)
                        .cookie(active.cookie())
                        .param("from", "2026-07-16T12:00:01Z")
                        .param("to", "2026-07-16T12:00:00Z"))
                .andReturn();
        assertProblem(reversed, 422, "AUDIT_QUERY_INVALID", Map.of("to", "invalid"));
        verifyNoInteractions(queries);
    }

    @Test
    void cursorCodecIsCanonicalBoundedCauseFreeAndNeverLogged(CapturedOutput output)
            throws Exception {
        AuditCursor expected = new AuditCursor(
                Instant.parse("2026-07-16T10:02:03.123456Z"), id(301));
        String encoded = expected.encode();
        assertThat(encoded).hasSizeLessThanOrEqualTo(86).doesNotContain("=");
        assertThat(AuditCursor.decode(encoded)).isEqualTo(expected);

        String nonCanonicalAlias = nonCanonicalAlias(encoded);
        assertThat(Base64.getUrlDecoder().decode(nonCanonicalAlias))
                .containsExactly(Base64.getUrlDecoder().decode(encoded));

        List<String> invalid = List.of(
                " ",
                "A",
                encoded + "=",
                "A".repeat(87),
                nonCanonicalAlias,
                token("wrong-shape"),
                token("2026-07-16T10:02:03Z\n" + id(301) + "\nextra"),
                token("2026-07-16T10:02:03.1Z\n" + id(301)),
                token("2026-07-16T10:02:03.1234567Z\n" + id(301)),
                token("1969-12-31T23:59:59Z\n" + id(301)),
                token("+10000-01-01T00:00:00Z\n" + id(301)),
                token("2026-07-16T10:02:03Z\n"
                        + "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa".toUpperCase()),
                Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(new byte[] {(byte) 0xc3, 0x28}));
        for (String value : invalid) {
            assertThatThrownBy(() -> AuditCursor.decode(value))
                    .isInstanceOfSatisfying(DomainException.class, failure -> {
                        assertThat(failure.code()).isEqualTo("AUDIT_CURSOR_INVALID");
                        assertThat(failure.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(failure.fieldErrors()).containsExactlyEntriesOf(
                                Map.of("cursor", "invalid"));
                        assertThat(failure).hasNoCause();
                        if (value.length() >= 8) {
                            assertThat(failure.toString()).doesNotContain(value);
                        }
                    });
        }

        Fixture admin = fixture();
        AuthenticatedSession active = login(admin);
        String decodedMarker = "decoded-cursor-secret-" + id(302);
        String rawMarker = token(decodedMarker);
        MvcResult invalidHttp = mvc.perform(get(AUDIT_PATH)
                        .cookie(active.cookie())
                        .param("cursor", rawMarker))
                .andReturn();
        assertProblem(invalidHttp, 422, "AUDIT_CURSOR_INVALID",
                Map.of("cursor", "invalid"));
        assertThat(invalidHttp.getResolvedException())
                .isInstanceOfSatisfying(DomainException.class, failure -> {
                    assertThat(failure).hasNoCause();
                    assertThat(failure.fieldErrors())
                            .containsExactlyEntriesOf(Map.of("cursor", "invalid"));
                });
        assertThat(invalidHttp.getResponse().getContentAsString())
                .doesNotContain(rawMarker, decodedMarker);
        assertThat(output.getAll()).doesNotContain(rawMarker, decodedMarker);
    }

    @Test
    void metadataRedactorUsesTextOnlyAllowlistAndUnicodeCodePoints() {
        AuditMetadataRedactor metadata = new AuditMetadataRedactor(json);
        String supplementary = "\ud83d\ude00".repeat(129);
        Map<String, String> safe = metadata.redact("""
                {"stage":"PASSWORD","next":"SECOND_FACTOR","method":"TOTP",
                 "reason":"EXPLICIT","channel":"LOCAL_CLI","backupSha256":"abc",
                 "recoveryCodeCount":"10","revokedOtherSessions":"2",
                 "staleActor":"false","fromKeyVersion":"1","toKeyVersion":"2",
                 "previousStatus":"UNREAD","newStatus":"READ",
                 "previousEmailStatus":"FAILED","newEmailStatus":"PENDING",
                 "createdDate":"2026-07-18",
                 "password":"secret","rawIp":"203.0.113.8",
                 "userAgent":"user-agent-secret","sessionId":"session-secret",
                 "recoveryCode":"recovery-secret","totpSecret":"totp-secret",
                 "databaseUrl":"jdbc:postgresql://database-secret",
                 "unknown":{"method":"nested"},"filePath":"C:/secret",
                 "exception":"sql-exception-secret"}
                """);
        assertThat(safe).containsOnlyKeys(
                "stage", "next", "method", "reason", "channel", "backupSha256",
                "recoveryCodeCount", "revokedOtherSessions", "staleActor",
                "fromKeyVersion", "toKeyVersion", "previousStatus", "newStatus",
                "previousEmailStatus", "newEmailStatus", "createdDate");
        assertThat(safe.toString()).doesNotContain(
                "secret", "rawIp", "userAgent", "sessionId", "totpSecret",
                "databaseUrl", "unknown", "filePath", "exception",
                "user-agent-secret", "recovery-secret", "totp-secret",
                "database-secret", "sql-exception-secret");

        Map<String, String> textOnly = metadata.redact("""
                {"stage":42,"next":true,"method":null,"reason":[],"channel":{},
                 "backupSha256":"safe","fromKeyVersion":1,"toKeyVersion":2}
                """);
        assertThat(textOnly).containsExactlyEntriesOf(Map.of("backupSha256", "safe"));
        String truncated = metadata.redact(
                "{\"reason\":\"" + supplementary + "\"}").get("reason");
        assertThat(truncated.codePointCount(0, truncated.length())).isEqualTo(128);
        assertThat(truncated).isEqualTo("\ud83d\ude00".repeat(128));
        assertThatThrownBy(() -> metadata.redact("not-json"))
                .isInstanceOf(IllegalStateException.class)
                .hasNoCause()
                .hasMessageNotContaining("not-json");
        assertThatThrownBy(() -> metadata.redact("[]"))
                .isInstanceOf(IllegalStateException.class)
                .hasNoCause();
    }

    @Test
    void publicDtosAreDefensiveAndRejectImpossibleOutcome() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("method", "TOTP");
        AdminAuditItem item = new AdminAuditItem(
                id(401), null, "TEST", "ADMIN", null, "SUCCESS", "trace-401",
                source, Instant.parse("2026-07-16T10:00:00Z"));
        source.clear();
        assertThat(item.metadata()).containsExactlyEntriesOf(Map.of("method", "TOTP"));
        assertThatThrownBy(() -> item.metadata().put("stage", "PASSWORD"))
                .isInstanceOf(UnsupportedOperationException.class);

        List<AdminAuditItem> sourceItems = new ArrayList<>();
        sourceItems.add(item);
        AdminAuditPage page = new AdminAuditPage(sourceItems, null);
        sourceItems.clear();
        assertThat(page.items()).containsExactly(item);
        assertThatThrownBy(() -> page.items().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatIllegalArgumentException().isThrownBy(() -> new AdminAuditItem(
                id(402), null, "TEST", "ADMIN", null, "OTHER", "trace-402",
                Map.of(), Instant.parse("2026-07-16T10:00:00Z")));

        assertThat(AdminAuditItem.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly(
                        "id", "actorAdminId", "action", "targetType", "targetId",
                        "outcome", "traceId", "metadata", "timestamp");
        assertThat(AdminAuditItem.class.getRecordComponents())
                .extracting(RecordComponent::getType)
                .containsExactly(
                        UUID.class, UUID.class, String.class, String.class, String.class,
                        String.class, String.class, Map.class, Instant.class);
        assertThat(AdminAuditPage.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("items", "nextCursor");
        assertThat(AdminAuditPage.class.getRecordComponents())
                .extracting(RecordComponent::getType)
                .containsExactly(List.class, String.class);

        Instant timestamp = Instant.parse("2026-07-16T10:00:00Z");
        assertThatThrownBy(() -> new AdminAuditItem(
                null, null, "TEST", "ADMIN", null, "SUCCESS", "trace", Map.of(), timestamp))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AdminAuditItem(
                id(410), null, null, "ADMIN", null, "SUCCESS", "trace", Map.of(), timestamp))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AdminAuditItem(
                id(411), null, "TEST", null, null, "SUCCESS", "trace", Map.of(), timestamp))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AdminAuditItem(
                id(412), null, "TEST", "ADMIN", null, null, "trace", Map.of(), timestamp))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AdminAuditItem(
                id(413), null, "TEST", "ADMIN", null, "SUCCESS", null, Map.of(), timestamp))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AdminAuditItem(
                id(414), null, "TEST", "ADMIN", null, "SUCCESS", "trace", null, timestamp))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AdminAuditItem(
                id(415), null, "TEST", "ADMIN", null, "SUCCESS", "trace", Map.of(), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AdminAuditPage(null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void providerAndQueryDependenciesCannotForgePublicErrorsOrLogs(CapturedOutput output)
            throws Exception {
        Fixture admin = fixture();
        AuthenticatedSession active = login(admin);

        DomainException providerSecret = secretFailure("provider-secret");
        doThrow(providerSecret).when(currentAdmin).requireAdminId();
        clearInvocations(queries);
        MvcResult provider = mvc.perform(get(AUDIT_PATH).cookie(active.cookie())).andReturn();
        assertFreshInternal(provider, providerSecret, "provider-secret");
        verifyNoInteractions(queries);

        reset(currentAdmin);
        DomainException repositorySecret = secretFailure("sql-and-metadata-secret");
        doThrow(repositorySecret).when(queries).find(any(), anyInt());
        MvcResult repository = mvc.perform(get(AUDIT_PATH).cookie(active.cookie())).andReturn();
        assertFreshInternal(repository, repositorySecret, "sql-and-metadata-secret");

        reset(queries);
        IllegalStateException redactorSecret =
                new IllegalStateException("redactor-cause-secret");
        doThrow(redactorSecret)
                .when(redactor).redact(anyString());
        String action = admin.action("REDACTOR");
        insertAudit(id(403), admin.adminId, action, "SUCCESS",
                Instant.parse("2026-07-16T10:00:00Z"), admin.adminId.toString(),
                Map.of("method", "TOTP"));
        MvcResult redaction = mvc.perform(get(AUDIT_PATH)
                        .cookie(active.cookie()).param("action", action))
                .andReturn();
        assertFreshInternal(redaction, redactorSecret, "redactor-cause-secret");

        reset(redactor);
        doReturn(null).when(currentAdmin).requireAdminId();
        clearInvocations(queries);
        MvcResult nullProvider = mvc.perform(get(AUDIT_PATH).cookie(active.cookie())).andReturn();
        assertProblem(nullProvider, 500, "INTERNAL_ERROR", Map.of());
        verifyNoInteractions(queries);
        assertThat(output.getAll()).doesNotContain(
                "provider-secret", "provider-secret-cause",
                "sql-and-metadata-secret", "sql-and-metadata-secret-cause",
                "redactor-cause-secret");
    }

    @Test
    void repositoryShapeAndPostgresPlanUseTheCompositeCursorBoundary() throws Exception {
        assertThat(Modifier.isFinal(AdminAuditQueryRepository.class.getModifiers())).isFalse();
        String source = repositorySource();
        String compact = source.replaceAll("\\s+", " ").toLowerCase();
        assertThat(compact)
                .contains("from portfolio.audit_log")
                .contains("created_at<=:cursorat")
                .contains("created_at<:cursorat")
                .contains("created_at=:cursorat and id>:cursorid")
                .contains("order by created_at desc, id asc limit :fetchlimit")
                .contains("types.timestamp_with_timezone")
                .contains("types.other")
                .contains("getobject(\"created_at\", offsetdatetime.class)")
                .contains("getobject(\"id\", uuid.class)")
                .contains("getobject(\"actor_admin_id\", uuid.class)")
                .doesNotContain(" offset ", "sql.append(query.", "+ query.");

        String plan;
        try (Connection connection = migratorDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("""
                            create table portfolio.task13_audit_plan (
                                id uuid not null,
                                created_at timestamptz not null
                            )
                            """);
                    statement.execute("""
                            insert into portfolio.task13_audit_plan (id, created_at)
                            select ('00000000-0000-0000-0000-'
                                        || lpad(value::text, 12, '0'))::uuid,
                                   timestamptz '2026-01-01T00:00:00Z'
                                        + value * interval '1 microsecond'
                            from generate_series(1, 100000) as value
                            """);
                    statement.execute("""
                            create index task13_audit_plan_cursor_ix
                            on portfolio.task13_audit_plan (created_at desc, id asc)
                            """);
                    statement.execute("analyze portfolio.task13_audit_plan");
                    statement.execute("set local enable_seqscan=off");
                }
                try (PreparedStatement explain = connection.prepareStatement("""
                        explain (analyze, buffers, format text)
                        select id
                        from portfolio.task13_audit_plan
                        where created_at <= ?
                          and (created_at < ? or (created_at = ? and id > ?))
                        order by created_at desc, id asc
                        limit 101
                        """)) {
                    Object cursorAt = Instant.parse("2026-01-01T00:00:00.050000Z")
                            .atOffset(ZoneOffset.UTC);
                    explain.setObject(1, cursorAt, Types.TIMESTAMP_WITH_TIMEZONE);
                    explain.setObject(2, cursorAt, Types.TIMESTAMP_WITH_TIMEZONE);
                    explain.setObject(3, cursorAt, Types.TIMESTAMP_WITH_TIMEZONE);
                    explain.setObject(4, id(50000), Types.OTHER);
                    try (ResultSet rows = explain.executeQuery()) {
                        StringBuilder collected = new StringBuilder();
                        while (rows.next()) {
                            collected.append(rows.getString(1)).append('\n');
                        }
                        plan = collected.toString();
                    }
                }
            } finally {
                connection.rollback();
            }
        }
        assertThat(migratorJdbc().sql(
                        "select to_regclass('portfolio.task13_audit_plan') is null")
                .query(Boolean.class)
                .single()).isTrue();
        assertThat(plan)
                .contains("Index Cond:")
                .contains("created_at <=")
                .doesNotContain("Sort", "Bitmap");
        Matcher removed = Pattern.compile("Rows Removed by Filter: (\\d+)").matcher(plan);
        while (removed.find()) {
            assertThat(Long.parseLong(removed.group(1))).isLessThanOrEqualTo(1L);
        }
    }

    @Test
    void runtimeCanReadButCannotMutateImmutableAuditRows() {
        Fixture admin = fixture();
        String action = admin.action("IMMUTABLE");
        UUID id = id(501);
        insertAudit(id, admin.adminId, action, "SUCCESS",
                Instant.parse("2026-07-16T10:00:00Z"), admin.adminId.toString(), Map.of());
        assertThat(jdbc.sql("select action from portfolio.audit_log where id=:id")
                        .param("id", id).query(String.class).single())
                .isEqualTo(action);
        assertThatThrownBy(() -> jdbc.sql(
                        "update portfolio.audit_log set outcome='FAILURE' where id=:id")
                .param("id", id).update()).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.sql("delete from portfolio.audit_log where id=:id")
                .param("id", id).update()).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.sql("truncate table portfolio.audit_log").update())
                .isInstanceOf(RuntimeException.class);
    }

    private Fixture fixture() {
        fixture = new Fixture();
        return fixture;
    }

    private AuthenticatedSession login(Fixture admin) throws Exception {
        CsrfExchange csrf = csrf();
        String remote = nextRemote();
        MvcResult password = mvc.perform(withCsrf(post(PASSWORD_PATH)
                        .with(remote(remote))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of(
                                "username", admin.username,
                                "password", ADMIN_PASSWORD))), csrf))
                .andReturn();
        assertThat(password.getResponse().getStatus()).isEqualTo(200);
        Cookie pending = findResponseCookie(password, SESSION_COOKIE)
                .orElseThrow(() -> new AssertionError("pending session cookie was not set"));
        String primaryId = requirePrimaryId(pending.getValue());
        admin.primaryIds.add(primaryId);

        MvcResult second = mvc.perform(withCsrf(post(SECOND_FACTOR_PATH)
                        .cookie(pending)
                        .with(remote(remote))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of(
                                "method", "TOTP",
                                "code", currentCode(admin.totpSecret)))), csrf))
                .andReturn();
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        Cookie active = findResponseCookie(second, SESSION_COOKIE).orElse(pending);
        assertThat(requirePrimaryId(active.getValue())).isEqualTo(primaryId);
        return new AuthenticatedSession(active, primaryId);
    }

    private CsrfExchange csrf() throws Exception {
        MvcResult source = mvc.perform(get(CSRF_PATH)).andReturn();
        assertThat(source.getResponse().getStatus()).isEqualTo(200);
        Cookie cookie = findResponseCookie(source, XSRF_COOKIE)
                .orElseThrow(() -> new AssertionError("CSRF cookie was not set"));
        JsonNode body = body(source);
        return new CsrfExchange(
                body.path("headerName").asText(), body.path("token").asText(), cookie);
    }

    private void insertAudit(
            UUID id,
            UUID actorAdminId,
            String action,
            String outcome,
            Instant createdAt,
            String targetId,
            Map<String, ?> metadata) {
        jdbc.sql("""
                        insert into portfolio.audit_log
                            (id, actor_admin_id, action, target_type, target_id, outcome,
                             trace_id, metadata, created_at)
                        values (:id, :actor, :action, 'ADMIN', :target, :outcome,
                                :trace, cast(:metadata as jsonb), :createdAt)
                        """)
                .param("id", id)
                .param("actor", actorAdminId, Types.OTHER)
                .param("action", action)
                .param("target", targetId, Types.VARCHAR)
                .param("outcome", outcome)
                .param("trace", "trace-" + id)
                .param("metadata", writeJson(metadata))
                .param(
                        "createdAt",
                        createdAt.atOffset(ZoneOffset.UTC),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        if (fixture != null) {
            fixture.auditIds.add(id);
            fixture.actions.add(action);
        }
    }

    private String writeJson(Map<String, ?> value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception failure) {
            throw new AssertionError("fixture metadata serialization failed", failure);
        }
    }

    private String currentCode(String secret) throws Exception {
        return totpCodes.generate(secret, totpTime.getTime() / 30);
    }

    private String requirePrimaryId(String publicId) {
        return jdbc.sql("select primary_id from portfolio.spring_session where session_id=:id")
                .param("id", publicId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new AssertionError("Spring Session row is missing"));
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    private static List<String> ids(JsonNode page) {
        List<String> result = new ArrayList<>();
        page.path("items").forEach(item -> result.add(item.path("id").asText()));
        return List.copyOf(result);
    }

    private void assertFreshInternal(
            MvcResult result, Throwable supplied, String marker) throws Exception {
        assertProblem(result, 500, "INTERNAL_ERROR", Map.of());
        assertThat(result.getResolvedException())
                .isInstanceOf(DomainException.class)
                .isNotSameAs(supplied);
        DomainException resolved = (DomainException) result.getResolvedException();
        assertThat(resolved).hasNoCause();
        assertThat(resolved.fieldErrors()).isEmpty();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(marker);
    }

    private void assertProblem(
            MvcResult result, int status, String code, Map<String, String> fields)
            throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertNoStore(result);
        JsonNode problem = body(result);
        assertThat(problem.path("code").asText()).isEqualTo(code);
        Map<String, String> actualFields = json.convertValue(
                problem.path("fieldErrors"), new TypeReference<>() {});
        assertThat(actualFields).containsExactlyInAnyOrderEntriesOf(fields);
    }

    private static void assertNoStore(MvcResult result) {
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL))
                .contains("no-store");
    }

    private static MockHttpServletRequestBuilder withCsrf(
            MockHttpServletRequestBuilder request, CsrfExchange csrf) {
        return request.cookie(csrf.cookie()).header(csrf.headerName(), csrf.token());
    }

    private static RequestPostProcessor remote(String address) {
        return request -> {
            request.setRemoteAddr(address);
            request.addHeader(HttpHeaders.USER_AGENT, "Task13-Integration-Test/1.0");
            return request;
        };
    }

    private static String nextRemote() {
        int sequence = REMOTE_SEQUENCE.incrementAndGet();
        int third = 1 + Math.floorMod(sequence / 250, 250);
        int fourth = 1 + Math.floorMod(sequence, 250);
        return "198.19." + third + "." + fourth;
    }

    private static Optional<Cookie> findResponseCookie(MvcResult result, String name) {
        Cookie direct = result.getResponse().getCookie(name);
        if (direct != null) {
            return Optional.of(direct);
        }
        for (String header : result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)) {
            String first = header.split(";", 2)[0];
            int separator = first.indexOf('=');
            if (separator > 0 && first.substring(0, separator).trim().equals(name)) {
                return Optional.of(new Cookie(name, first.substring(separator + 1).trim()));
            }
        }
        return Optional.empty();
    }

    private static DomainException secretFailure(String marker) {
        DomainException failure = new DomainException(
                "FORGED_" + marker.toUpperCase().replace('-', '_'),
                HttpStatus.I_AM_A_TEAPOT,
                Map.of("secret", marker));
        failure.initCause(new IllegalStateException(marker + "-cause"));
        return failure;
    }

    private static UUID id(int suffix) {
        return UUID.fromString("00000000-0000-0000-0000-" + String.format("%012d", suffix));
    }

    private static String token(String decoded) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(decoded.getBytes(StandardCharsets.UTF_8));
    }

    private static String nonCanonicalAlias(String canonical) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        int remainder = canonical.length() % 4;
        if (remainder != 2 && remainder != 3) {
            throw new AssertionError("fixture token has no unused Base64 bits");
        }
        int last = alphabet.indexOf(canonical.charAt(canonical.length() - 1));
        if (last < 0) {
            throw new AssertionError("fixture token is not Base64url");
        }
        int alias = last | 1;
        return canonical.substring(0, canonical.length() - 1) + alphabet.charAt(alias);
    }

    private static String repositorySource() throws Exception {
        Path source = Path.of(System.getProperty("user.dir"),
                "src", "main", "java", "xyz", "yychainsaw", "portfolio", "audit",
                "AdminAuditQueryRepository.java");
        assertThat(source).exists();
        return Files.readString(source, StandardCharsets.UTF_8);
    }

    private final class Fixture implements AutoCloseable {
        private final UUID adminId = UUID.randomUUID();
        private final String username = "AuditAdmin" + adminId.toString().replace("-", "");
        private final String totpSecret;
        private final Set<UUID> auditIds = new LinkedHashSet<>();
        private final Set<UUID> metadataIds = new LinkedHashSet<>();
        private final Set<String> actions = new LinkedHashSet<>();
        private final Set<String> primaryIds = new LinkedHashSet<>();

        private Fixture() {
            TotpService.Enrollment enrollment = totp.beginEnrollment(adminId, username);
            totpSecret = enrollment.plaintextSecret();
            Instant now = Instant.now();
            admins.insert(new AdminUser(
                    adminId,
                    username,
                    passwords.encode(ADMIN_PASSWORD),
                    AdminStatus.ACTIVE,
                    enrollment.encryptedSecret(),
                    null,
                    0,
                    now,
                    now));
        }

        private String action(String purpose) {
            String action = "TASK13_" + purpose + "_" + adminId.toString().replace("-", "")
                    .toUpperCase();
            actions.add(action);
            return action;
        }

        @Override
        public void close() {
            JdbcClient owner = migratorJdbc();
            metadataIds.addAll(owner.sql("""
                            select id
                            from portfolio.admin_session_metadata
                            where admin_id=:id
                            """)
                    .param("id", adminId)
                    .query(UUID.class)
                    .list());
            primaryIds.addAll(owner.sql("""
                            select session_primary_id
                            from portfolio.admin_session_metadata
                            where admin_id=:id and session_primary_id is not null
                            """)
                    .param("id", adminId)
                    .query(String.class)
                    .list());
            owner.sql("""
                            alter table portfolio.audit_log
                            disable trigger audit_log_reject_mutation
                            """).update();
            try {
                owner.sql("""
                                delete from portfolio.audit_log
                                where actor_admin_id=:id or target_id=:target
                                """)
                        .param("id", adminId)
                        .param("target", adminId.toString())
                        .update();
                for (UUID auditId : auditIds) {
                    owner.sql("delete from portfolio.audit_log where id=:id")
                            .param("id", auditId)
                            .update();
                }
                for (UUID metadataId : metadataIds) {
                    owner.sql("delete from portfolio.audit_log where target_id=:target")
                            .param("target", metadataId.toString())
                            .update();
                }
                for (String action : actions) {
                    owner.sql("delete from portfolio.audit_log where action=:action")
                            .param("action", action)
                            .update();
                }
            } finally {
                owner.sql("""
                                alter table portfolio.audit_log
                                enable trigger audit_log_reject_mutation
                                """).update();
            }
            for (String primaryId : primaryIds) {
                owner.sql("delete from portfolio.spring_session where primary_id=:id")
                        .param("id", primaryId)
                        .update();
            }
            owner.sql("delete from portfolio.admin_user where id=:id")
                    .param("id", adminId)
                    .update();
        }
    }

    private record CsrfExchange(String headerName, String token, Cookie cookie) {
    }

    private record AuthenticatedSession(Cookie cookie, String stablePrimaryId) {
    }

    private record InvalidQuery(String parameter, String value, String code, String field) {
    }
}
