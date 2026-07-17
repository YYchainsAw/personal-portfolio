package xyz.yychainsaw.portfolio.message.web;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import xyz.yychainsaw.portfolio.auth.session.AdminSessionService;
import xyz.yychainsaw.portfolio.auth.session.AdminSessionService.ActiveSession;
import xyz.yychainsaw.portfolio.auth.web.AdminPrincipal;
import xyz.yychainsaw.portfolio.auth.web.LoginSubjectHasher;
import xyz.yychainsaw.portfolio.auth.web.SecurityProblemWriter;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.common.ratelimit.RateLimitProperties;
import xyz.yychainsaw.portfolio.config.SecurityConfiguration;
import xyz.yychainsaw.portfolio.message.application.EmailDeliveryView;
import xyz.yychainsaw.portfolio.message.application.MessageDetail;
import xyz.yychainsaw.portfolio.message.application.MessageInboxService;
import xyz.yychainsaw.portfolio.message.application.MessagePage;
import xyz.yychainsaw.portfolio.message.application.MessageStatus;
import xyz.yychainsaw.portfolio.message.application.MessageSummary;

@WebMvcTest(AdminMessageController.class)
@org.springframework.context.annotation.Import({
        AdminMessageStatusBodyReader.class,
        SecurityConfiguration.class,
        SecurityProblemWriter.class
})
@TestPropertySource(properties = {
        "server.servlet.session.cookie.secure=false",
        "portfolio.web.allow-development-cors=false"
})
class AdminMessageControllerTest {
    private static final UUID ADMIN_ID =
            UUID.fromString("94000000-0000-4000-8000-000000000001");
    private static final UUID MESSAGE_ID =
            UUID.fromString("94000000-0000-4000-8000-000000000002");
    private static final Instant CREATED_AT = Instant.parse("2026-07-18T01:02:03Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-07-18T01:03:03Z");
    private static final String NO_STORE = CacheControl.noStore().getHeaderValue();

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @MockitoBean MessageInboxService messages;
    @MockitoBean AdminSessionService sessions;
    @MockitoBean LoginSubjectHasher subjects;
    @MockitoBean RateLimitProperties rateLimits;

    private MockHttpSession session;

    @BeforeEach
    void prepareActiveAdminSession() {
        session = new MockHttpSession(null, "94000000-0000-4000-8000-000000000010");
        given(sessions.requireActive(session.getId())).willReturn(new ActiveSession(
                UUID.fromString("94000000-0000-4000-8000-000000000011"),
                ADMIN_ID,
                session.getId(),
                CREATED_AT,
                CREATED_AT));
    }

    @Test
    void allRoutesRequireAnActiveAdministratorSession() throws Exception {
        mvc.perform(get("/api/admin/messages"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NO_STORE))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mvc.perform(get("/api/admin/messages/{id}", MESSAGE_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NO_STORE))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mvc.perform(patch("/api/admin/messages/{id}/status", MESSAGE_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"READ\",\"version\":0}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/messages/{id}/email/retry", MESSAGE_ID)
                        .with(csrf()))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/admin/messages/{id}", MESSAGE_ID).with(csrf()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(messages);
    }

    @Test
    void everyMutationRequiresCsrfBeforeCallingTheService() throws Exception {
        mvc.perform(authenticated(patch("/api/admin/messages/{id}/status", MESSAGE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"READ\",\"version\":0}")))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NO_STORE))
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mvc.perform(authenticated(post(
                                "/api/admin/messages/{id}/email/retry", MESSAGE_ID)))
                .andExpect(status().isForbidden());
        mvc.perform(authenticated(delete("/api/admin/messages/{id}", MESSAGE_ID)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(messages);
    }

    @Test
    void listReturnsOnlySummaryFieldsAndNoStore() throws Exception {
        MessageSummary summary = new MessageSummary(
                MESSAGE_ID,
                "Visitor <script>",
                "visitor@example.com",
                "<b>Subject</b>",
                MessageStatus.UNREAD,
                "FAILED",
                CREATED_AT,
                3);
        given(messages.list("UNREAD", null, "30"))
                .willReturn(new MessagePage(List.of(summary), "next-token"));

        mvc.perform(authenticated(get("/api/admin/messages")
                        .param("status", "UNREAD")
                        .param("limit", "30")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NO_STORE))
                .andExpect(jsonPath("$.items[0].id").value(MESSAGE_ID.toString()))
                .andExpect(jsonPath("$.items[0].visitorName").value("Visitor <script>"))
                .andExpect(jsonPath("$.items[0].visitorEmail")
                        .value("visitor@example.com"))
                .andExpect(jsonPath("$.items[0].subject").value("<b>Subject</b>"))
                .andExpect(jsonPath("$.items[0].status").value("UNREAD"))
                .andExpect(jsonPath("$.items[0].emailStatus").value("FAILED"))
                .andExpect(jsonPath("$.items[0].version").value(3))
                .andExpect(jsonPath("$.items[0].body").doesNotExist())
                .andExpect(jsonPath("$.items[0].privacyAcceptedAt").doesNotExist())
                .andExpect(jsonPath("$.items[0].stableMessageId").doesNotExist())
                .andExpect(jsonPath("$.nextCursor").value("next-token"));

        verify(messages).list("UNREAD", null, "30");
    }

    @Test
    void detailReturnsTheSixSafeDeliveryFieldsAndNoStore() throws Exception {
        MessageDetail detail = detail(MessageStatus.UNREAD, 3);
        given(messages.detail(MESSAGE_ID)).willReturn(detail);

        mvc.perform(authenticated(get("/api/admin/messages/{id}", MESSAGE_ID)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NO_STORE))
                .andExpect(jsonPath("$.body").value("<script>alert(1)</script>"))
                .andExpect(jsonPath("$.email.status").value("FAILED"))
                .andExpect(jsonPath("$.email.attempts").value(2))
                .andExpect(jsonPath("$.email.nextAttemptAt")
                        .value("2026-07-18T01:04:03Z"))
                .andExpect(jsonPath("$.email.sentAt").doesNotExist())
                .andExpect(jsonPath("$.email.updatedAt")
                        .value("2026-07-18T01:03:03Z"))
                .andExpect(jsonPath("$.email.errorCategory")
                        .value("SMTP_DELIVERY_FAILED"))
                .andExpect(jsonPath("$.email.lastErrorSummary").doesNotExist())
                .andExpect(jsonPath("$.email.toAddress").doesNotExist())
                .andExpect(jsonPath("$.email.stableMessageId").doesNotExist());

        verify(messages).detail(MESSAGE_ID);
    }

    @Test
    void statusPatchUsesTheLoadedVersionAndReturnsUpdatedDetail() throws Exception {
        MessageDetail updated = detail(MessageStatus.READ, 4);
        given(messages.updateStatus(MESSAGE_ID, MessageStatus.READ, 3))
                .willReturn(updated);

        mvc.perform(authenticated(patch("/api/admin/messages/{id}/status", MESSAGE_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(
                                new UpdateMessageStatusRequest(MessageStatus.READ, 3)))))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NO_STORE))
                .andExpect(jsonPath("$.status").value("READ"))
                .andExpect(jsonPath("$.version").value(4));

        verify(messages).updateStatus(MESSAGE_ID, MessageStatus.READ, 3);
    }

    @Test
    void staleStatusVersionIsReturnedAsTheStableConflict() throws Exception {
        given(messages.updateStatus(MESSAGE_ID, MessageStatus.READ, 2))
                .willThrow(new DomainException(
                        "MESSAGE_VERSION_CONFLICT", HttpStatus.CONFLICT, Map.of()));

        mvc.perform(authenticated(patch("/api/admin/messages/{id}/status", MESSAGE_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"READ\",\"version\":2}")))
                .andExpect(status().isConflict())
                .andExpect(header().string(
                        HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.code").value("MESSAGE_VERSION_CONFLICT"));
    }

    @Test
    void retryAndDeleteReturnNoContentAndNoStore() throws Exception {
        mvc.perform(authenticated(post("/api/admin/messages/{id}/email/retry", MESSAGE_ID)
                        .with(csrf())))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NO_STORE));
        mvc.perform(authenticated(delete("/api/admin/messages/{id}", MESSAGE_ID)
                        .with(csrf())))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NO_STORE));

        verify(messages).retryEmail(MESSAGE_ID);
        verify(messages).delete(MESSAGE_ID);
    }

    @Test
    void nullAndInvalidStatusRequestsNeverReachTheService() throws Exception {
        mvc.perform(authenticated(patch("/api/admin/messages/{id}/status", MESSAGE_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("null")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REQUEST_BODY_INVALID"));
        mvc.perform(authenticated(patch("/api/admin/messages/{id}/status", MESSAGE_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":null,\"version\":-1}")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(messages);
    }

    @Test
    void statusBodyRejectsUnknownDuplicateTrailingAndInvalidEnumValues()
            throws Exception {
        mvc.perform(authenticated(patch("/api/admin/messages/{id}/status", MESSAGE_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"READ\",\"version\":0,\"body\":\"secret\"}")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NO_STORE))
                .andExpect(jsonPath("$.code").value("REQUEST_BODY_INVALID"));
        mvc.perform(authenticated(patch("/api/admin/messages/{id}/status", MESSAGE_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"READ\",\"status\":\"SPAM\",\"version\":0}")))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NO_STORE))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        mvc.perform(authenticated(patch("/api/admin/messages/{id}/status", MESSAGE_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"READ\",\"version\":0} {}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        mvc.perform(authenticated(patch("/api/admin/messages/{id}/status", MESSAGE_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"read\",\"version\":0}")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.status").value("invalid"));

        verifyNoInteractions(messages);
    }

    private MessageDetail detail(MessageStatus status, int version) {
        return new MessageDetail(
                MESSAGE_ID,
                "Visitor",
                "visitor@example.com",
                "Subject",
                "<script>alert(1)</script>",
                status,
                new EmailDeliveryView(
                        "FAILED",
                        2,
                        Instant.parse("2026-07-18T01:04:03Z"),
                        null,
                        UPDATED_AT,
                        "SMTP_DELIVERY_FAILED"),
                CREATED_AT,
                CREATED_AT,
                UPDATED_AT,
                version);
    }

    private MockHttpServletRequestBuilder authenticated(
            MockHttpServletRequestBuilder request) {
        return request.session(session).with(admin());
    }

    private RequestPostProcessor admin() {
        AdminPrincipal principal = new AdminPrincipal(ADMIN_ID, "yychainsaw");
        UsernamePasswordAuthenticationToken authenticated =
                UsernamePasswordAuthenticationToken.authenticated(
                        principal,
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        return authentication(authenticated);
    }
}
