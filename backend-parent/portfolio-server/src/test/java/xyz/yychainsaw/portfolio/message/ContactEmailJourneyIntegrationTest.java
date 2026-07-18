package xyz.yychainsaw.portfolio.message;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.MailSendException;
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
import xyz.yychainsaw.portfolio.common.ratelimit.RateLimitDecision;
import xyz.yychainsaw.portfolio.common.ratelimit.RateLimiter;
import xyz.yychainsaw.portfolio.message.config.EmailOutboxProperties;
import xyz.yychainsaw.portfolio.message.email.ContactNotification;
import xyz.yychainsaw.portfolio.message.email.EmailOutboxRepository;
import xyz.yychainsaw.portfolio.message.email.EmailOutboxWorker;
import xyz.yychainsaw.portfolio.message.email.EmailSenderPort;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest(properties = {
        "portfolio.email.enabled=false",
        "management.health.mail.enabled=false",
        "portfolio.jobs.worker-enabled=false",
        "portfolio.analytics.maintenance-scheduling-enabled=false"
})
@AutoConfigureMockMvc
@Isolated
@ExtendWith(OutputCaptureExtension.class)
class ContactEmailJourneyIntegrationTest extends PostgresIntegrationTestBase {
    private static final String CONTACT_PATH = "/api/public/contact";
    private static final String CSRF_PATH = "/api/admin/auth/csrf";
    private static final String VISITOR_NAME = "Journey Visitor 易嘉轩";
    private static final String VISITOR_EMAIL = "journey.visitor@example.com";
    private static final String VISITOR_SUBJECT = "UE journey collaboration";
    private static final String VISITOR_BODY =
            "Private journey body: I would like to discuss a game project.";
    private static final String VISITOR_IP = "203.0.113.91";
    private static final String SESSION_PRIMARY_ID =
            "98000000-0000-4000-8000-000000000010";
    private static final String SESSION_PUBLIC_ID =
            "98000000-0000-4000-8000-000000000011";
    private static final Set<String> REDACTED_AUDIT_KEYS = Set.of(
            "visitorName",
            "visitorEmail",
            "subject",
            "body",
            "stableMessageId",
            "toAddress",
            "lastErrorSummary");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired EmailOutboxRepository outbox;
    @Autowired Clock clock;

    @MockitoBean RateLimiter limiter;
    @MockitoBean AdminSessionService sessions;

    private UUID adminId;

    @BeforeEach
    void prepareJourney() throws IOException {
        deleteContactRows();
        clearSession();
        reset(limiter, sessions);
        when(limiter.consume(anyString(), anyString()))
                .thenReturn(RateLimitDecision.allow());

        adminId = ensureAdmin();
        insertAuthenticatedSession();
        Instant now = clock.instant();
        given(sessions.requireActive(SESSION_PUBLIC_ID)).willReturn(new ActiveSession(
                UUID.fromString("98000000-0000-4000-8000-000000000012"),
                adminId,
                SESSION_PRIMARY_ID,
                now.minusSeconds(1),
                now));
    }

    @AfterEach
    void cleanJourney() {
        try {
            deleteContactRows();
            clearSession();
        } finally {
            reset(limiter, sessions);
        }
    }

    @Test
    void contactSubmissionSurvivesMailFailureAndCompletesTheRedactedAdminJourney(
            CapturedOutput output) throws Exception {
        CsrfExchange csrfExchange = csrfExchange();
        MvcResult publicResult = submitContact(csrfExchange);

        assertThat(publicResult.getResponse().getStatus()).isEqualTo(202);
        assertThat(publicResult.getResponse().getHeader(HttpHeaders.CACHE_CONTROL))
                .contains("no-store");
        JsonNode acceptance = body(publicResult);
        List<String> responseFields = new ArrayList<>();
        acceptance.fieldNames().forEachRemaining(responseFields::add);
        assertThat(responseFields).containsExactly("accepted");
        assertThat(acceptance.path("accepted").asBoolean()).isTrue();

        UUID messageId = singleUuid("""
                select id
                from portfolio.contact_message
                where visitor_email=:visitorEmail
                """, "visitorEmail", VISITOR_EMAIL, Types.VARCHAR);
        UUID outboxId = singleUuid("""
                select id
                from portfolio.email_outbox
                where contact_message_id=:messageId
                """, "messageId", messageId, Types.OTHER);
        String publicBody = publicResult.getResponse().getContentAsString(UTF_8);
        assertThat(publicBody).doesNotContain(
                messageId.toString(),
                outboxId.toString(),
                VISITOR_NAME,
                VISITOR_EMAIL,
                VISITOR_SUBJECT,
                VISITOR_BODY,
                VISITOR_IP);

        mvc.perform(admin(get("/api/admin/messages")
                        .param("status", "UNREAD")
                        .param("limit", "30")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(messageId.toString()))
                .andExpect(jsonPath("$.items[0].status").value("UNREAD"))
                .andExpect(jsonPath("$.items[0].version").value(0));

        EmailSenderPort sender = mock(EmailSenderPort.class);
        EmailOutboxWorker worker = worker(sender);
        doThrow(new MailSendException("SMTP failure echoed "
                        + VISITOR_NAME + ' ' + VISITOR_EMAIL + ' '
                        + VISITOR_SUBJECT + ' ' + VISITOR_BODY + ' ' + VISITOR_IP))
                .when(sender)
                .send(any(ContactNotification.class));

        worker.runOnce();

        verify(sender).send(any(ContactNotification.class));
        assertThat(emailState(outboxId))
                .extracting(
                        EmailState::status,
                        EmailState::attempts,
                        EmailState::lastErrorSummary,
                        EmailState::sentAt)
                .containsExactly(
                        "FAILED",
                        1,
                        "MailSendException|SMTP_DELIVERY_FAILED",
                        null);
        assertThat(rowCount(
                "portfolio.contact_message", "id", messageId, Types.OTHER)).isOne();
        mvc.perform(admin(get("/api/admin/messages/{id}", messageId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNREAD"))
                .andExpect(jsonPath("$.body").value(VISITOR_BODY))
                .andExpect(jsonPath("$.email.status").value("FAILED"))
                .andExpect(jsonPath("$.email.errorCategory")
                        .value("SMTP_DELIVERY_FAILED"));

        mvc.perform(admin(withCsrf(post(
                                "/api/admin/messages/{id}/email/retry", messageId),
                        csrfExchange)))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
        assertThat(emailState(outboxId))
                .extracting(
                        EmailState::status,
                        EmailState::attempts,
                        EmailState::lastErrorSummary)
                .containsExactly("PENDING", 1, null);

        reset(sender);
        worker.runOnce();

        verify(sender).send(any(ContactNotification.class));
        assertThat(emailState(outboxId))
                .extracting(
                        EmailState::status,
                        EmailState::attempts,
                        EmailState::lastErrorSummary)
                .containsExactly("SENT", 2, null);
        assertThat(emailState(outboxId).sentAt()).isNotNull();
        mvc.perform(admin(get("/api/admin/messages/{id}", messageId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email.status").value("SENT"))
                .andExpect(jsonPath("$.email.attempts").value(2));

        mvc.perform(admin(withCsrf(
                        patch("/api/admin/messages/{id}/status", messageId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of(
                                "status", "ARCHIVED",
                                "version", 0))),
                        csrfExchange)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.status").value("ARCHIVED"))
                .andExpect(jsonPath("$.version").value(1));
        assertThat(messageState(messageId))
                .extracting(MessageState::status, MessageState::version)
                .containsExactly("ARCHIVED", 1);

        mvc.perform(admin(withCsrf(
                        delete("/api/admin/messages/{id}", messageId),
                        csrfExchange)))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));

        assertThat(rowCount(
                "portfolio.contact_message", "id", messageId, Types.OTHER)).isZero();
        assertThat(rowCount(
                "portfolio.email_outbox", "id", outboxId, Types.OTHER)).isZero();
        assertThat(jdbc.sql("""
                        select count(*)
                        from portfolio.contact_message
                        where visitor_name=:visitorName
                           or visitor_email=:visitorEmail
                           or subject=:subject
                           or body=:body
                        """)
                .param("visitorName", VISITOR_NAME, Types.VARCHAR)
                .param("visitorEmail", VISITOR_EMAIL, Types.VARCHAR)
                .param("subject", VISITOR_SUBJECT, Types.VARCHAR)
                .param("body", VISITOR_BODY, Types.VARCHAR)
                .query(Long.class)
                .single()).isZero();

        assertRedactedMutationAudits(messageId);
        assertThat(output.getAll()).doesNotContain(
                messageId.toString(),
                VISITOR_NAME,
                VISITOR_EMAIL,
                VISITOR_SUBJECT,
                VISITOR_BODY,
                VISITOR_IP);
    }

    private MvcResult submitContact(CsrfExchange csrfExchange) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("name", VISITOR_NAME);
        request.put("email", VISITOR_EMAIL);
        request.put("subject", VISITOR_SUBJECT);
        request.put("message", VISITOR_BODY);
        request.put("website", "");
        request.put("privacyAccepted", true);
        return mvc.perform(post(CONTACT_PATH)
                        .with(servletRequest -> {
                            servletRequest.setRemoteAddr(VISITOR_IP);
                            return servletRequest;
                        })
                        .cookie(csrfExchange.cookie())
                        .header(csrfExchange.headerName(), csrfExchange.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(request)))
                .andReturn();
    }

    private CsrfExchange csrfExchange() throws Exception {
        MvcResult result = mvc.perform(get(CSRF_PATH)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        Cookie cookie = result.getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        JsonNode response = body(result);
        return new CsrfExchange(
                cookie,
                response.path("headerName").asText(),
                response.path("token").asText());
    }

    private EmailOutboxWorker worker(EmailSenderPort sender) {
        EmailOutboxProperties properties = new EmailOutboxProperties(
                true,
                "portfolio-notify@example.com",
                Duration.ofHours(1),
                Duration.ofMinutes(2),
                10);
        return new EmailOutboxWorker(outbox, sender, properties, clock);
    }

    private void assertRedactedMutationAudits(UUID messageId) throws Exception {
        List<AuditRow> rows = migratorJdbc().sql("""
                        select actor_admin_id, action, target_type, outcome,
                               metadata::text as metadata_json
                        from portfolio.audit_log
                        where target_id=:targetId
                          and action in (
                              'MESSAGE_EMAIL_RETRY',
                              'MESSAGE_STATUS_UPDATE',
                              'MESSAGE_DELETE')
                        order by action
                        """)
                .param("targetId", messageId.toString(), Types.VARCHAR)
                .query((resultSet, rowNumber) -> new AuditRow(
                        resultSet.getObject("actor_admin_id", UUID.class),
                        resultSet.getString("action"),
                        resultSet.getString("target_type"),
                        resultSet.getString("outcome"),
                        resultSet.getString("metadata_json")))
                .list();

        assertThat(rows).extracting(AuditRow::action)
                .containsExactly(
                        "MESSAGE_DELETE",
                        "MESSAGE_EMAIL_RETRY",
                        "MESSAGE_STATUS_UPDATE");
        for (AuditRow row : rows) {
            assertThat(row.actorAdminId()).isEqualTo(adminId);
            assertThat(row.targetType()).isEqualTo("CONTACT_MESSAGE");
            assertThat(row.outcome()).isEqualTo("SUCCESS");
            JsonNode metadata = json.readTree(row.metadataJson());
            List<String> keys = new ArrayList<>();
            metadata.fieldNames().forEachRemaining(keys::add);
            assertThat(keys).contains("createdDate");
            assertThat(keys).doesNotContainAnyElementsOf(REDACTED_AUDIT_KEYS);
            assertThat(metadata.toString()).doesNotContain(
                    VISITOR_NAME,
                    VISITOR_EMAIL,
                    VISITOR_SUBJECT,
                    VISITOR_BODY,
                    VISITOR_IP);
            if ("MESSAGE_EMAIL_RETRY".equals(row.action())) {
                assertThat(keys).containsExactlyInAnyOrder(
                        "createdDate", "previousEmailStatus", "newEmailStatus");
            } else {
                assertThat(keys).containsExactlyInAnyOrder(
                        "createdDate", "previousStatus", "newStatus");
            }
        }
    }

    private MessageState messageState(UUID messageId) {
        return jdbc.sql("""
                        select status, version
                        from portfolio.contact_message
                        where id=:messageId
                        """)
                .param("messageId", messageId, Types.OTHER)
                .query((resultSet, rowNumber) -> new MessageState(
                        resultSet.getString("status"),
                        resultSet.getInt("version")))
                .single();
    }

    private EmailState emailState(UUID outboxId) {
        return jdbc.sql("""
                        select status, attempts, last_error_summary, sent_at
                        from portfolio.email_outbox
                        where id=:outboxId
                        """)
                .param("outboxId", outboxId, Types.OTHER)
                .query((resultSet, rowNumber) -> {
                    OffsetDateTime sentAt = resultSet.getObject(
                            "sent_at", OffsetDateTime.class);
                    return new EmailState(
                            resultSet.getString("status"),
                            resultSet.getInt("attempts"),
                            resultSet.getString("last_error_summary"),
                            sentAt == null ? null : sentAt.toInstant());
                })
                .single();
    }

    private UUID singleUuid(
            String sql, String parameter, Object value, int sqlType) {
        return jdbc.sql(sql)
                .param(parameter, value, sqlType)
                .query(UUID.class)
                .single();
    }

    private long rowCount(
            String table, String column, Object value, int sqlType) {
        return jdbc.sql("select count(*) from " + table + " where " + column + "=:value")
                .param("value", value, sqlType)
                .query(Long.class)
                .single();
    }

    private MockHttpServletRequestBuilder admin(
            MockHttpServletRequestBuilder request) {
        Cookie sessionCookie = new Cookie("PORTFOLIO_SESSION", SESSION_PUBLIC_ID);
        sessionCookie.setPath("/");
        return request.cookie(sessionCookie);
    }

    private static MockHttpServletRequestBuilder withCsrf(
            MockHttpServletRequestBuilder request, CsrfExchange csrfExchange) {
        return request.cookie(csrfExchange.cookie())
                .header(csrfExchange.headerName(), csrfExchange.token());
    }

    private UUID ensureAdmin() {
        JdbcClient owner = migratorJdbc();
        List<UUID> existing = owner.sql("select id from portfolio.admin_user limit 1")
                .query(UUID.class)
                .list();
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        UUID id = UUID.fromString("98000000-0000-4000-8000-000000000001");
        owner.sql("""
                        insert into portfolio.admin_user(
                            id, username, password_hash, status, totp_key_version,
                            totp_nonce, totp_ciphertext)
                        values (:id, 'contact-journey-admin', '{noop}test',
                                'ACTIVE', 1, decode(repeat('00', 12), 'hex'),
                                decode('00', 'hex'))
                        """)
                .param("id", id, Types.OTHER)
                .update();
        return id;
    }

    private void insertAuthenticatedSession() throws IOException {
        long now = System.currentTimeMillis();
        assertThat(jdbc.sql("""
                        insert into portfolio.spring_session(
                            primary_id, session_id, creation_time, last_access_time,
                            max_inactive_interval, expiry_time, principal_name)
                        values (
                            :primaryId, :sessionId, :now, :now,
                            1800, :expiry, 'contact-journey-admin')
                        """)
                .param("primaryId", SESSION_PRIMARY_ID)
                .param("sessionId", SESSION_PUBLIC_ID)
                .param("now", now)
                .param("expiry", now + 1_800_000L)
                .update()).isOne();

        AdminPrincipal principal = new AdminPrincipal(adminId, "contact-journey-admin");
        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        principal,
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);

        assertThat(jdbc.sql("""
                        insert into portfolio.spring_session_attributes(
                            session_primary_id, attribute_name, attribute_bytes)
                        values (
                            :primaryId, 'SPRING_SECURITY_CONTEXT', :bytes)
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

    private static byte[] serialize(Object value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(value);
        }
        return bytes.toByteArray();
    }

    private void deleteContactRows() {
        migratorJdbc().sql("delete from portfolio.contact_message").update();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    private record CsrfExchange(Cookie cookie, String headerName, String token) {}

    private record MessageState(String status, int version) {}

    private record EmailState(
            String status, int attempts, String lastErrorSummary, Instant sentAt) {}

    private record AuditRow(
            UUID actorAdminId,
            String action,
            String targetType,
            String outcome,
            String metadataJson) {}
}
