package xyz.yychainsaw.portfolio.system.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import xyz.yychainsaw.portfolio.auth.session.AdminSessionService;
import xyz.yychainsaw.portfolio.auth.session.AdminSessionService.ActiveSession;
import xyz.yychainsaw.portfolio.auth.web.AdminPrincipal;
import xyz.yychainsaw.portfolio.auth.web.LoginSubjectHasher;
import xyz.yychainsaw.portfolio.auth.web.SecurityProblemWriter;
import xyz.yychainsaw.portfolio.common.ratelimit.RateLimitProperties;
import xyz.yychainsaw.portfolio.config.SecurityConfiguration;

@WebMvcTest(AdminOperationsController.class)
@Import({SecurityConfiguration.class, SecurityProblemWriter.class})
@TestPropertySource(properties = {
        "server.servlet.session.cookie.secure=false",
        "portfolio.web.allow-development-cors=false",
        "spring.jackson.default-property-inclusion=non_null"
})
class AdminOperationsControllerTest {
    private static final UUID ADMIN_ID =
            UUID.fromString("99000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-07-18T02:00:00Z");
    private static final Instant START = Instant.parse("2026-07-18T01:00:00Z");
    private static final Instant FINISH = Instant.parse("2026-07-18T01:05:00Z");
    private static final String NO_STORE = CacheControl.noStore().getHeaderValue();
    private static final String CHECKSUM = "b".repeat(64);

    @Autowired MockMvc mvc;

    @MockitoBean OperationsStatusService operations;
    @MockitoBean AdminSessionService sessions;
    @MockitoBean LoginSubjectHasher subjects;
    @MockitoBean RateLimitProperties rateLimits;

    private MockHttpSession session;

    @BeforeEach
    void prepareActiveAdministrator() {
        session = new MockHttpSession(null, "99000000-0000-4000-8000-000000000010");
        given(sessions.requireActive(session.getId())).willReturn(new ActiveSession(
                UUID.fromString("99000000-0000-4000-8000-000000000011"),
                ADMIN_ID,
                session.getId(),
                NOW,
                NOW));
    }

    @Test
    void anonymousRequestsAreRejectedBeforeTheQueryRuns() throws Exception {
        mvc.perform(get("/api/admin/system/operations"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NO_STORE))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        verifyNoInteractions(operations);
    }

    @Test
    void returnsExactlySevenStableCardsAndServerTimeWithNoStore() throws Exception {
        given(operations.read()).willReturn(new OperationsStatus(
                succeeded("DATABASE_BACKUP"),
                failed("MEDIA_BACKUP"),
                running("ANALYTICS_AGGREGATE"),
                null,
                null,
                succeeded("DEPLOYMENT"),
                null,
                NOW));

        MvcResult result = mvc.perform(get("/api/admin/system/operations")
                        .session(session)
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NO_STORE))
                .andExpect(jsonPath("$.length()").value(8))
                .andExpect(jsonPath("$.databaseBackup.length()").value(6))
                .andExpect(jsonPath("$.databaseBackup.type").value("DATABASE_BACKUP"))
                .andExpect(jsonPath("$.databaseBackup.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.databaseBackup.startedAt")
                        .value("2026-07-18T01:00:00Z"))
                .andExpect(jsonPath("$.databaseBackup.finishedAt")
                        .value("2026-07-18T01:05:00Z"))
                .andExpect(jsonPath("$.databaseBackup.artifactChecksum")
                        .value(CHECKSUM))
                .andExpect(jsonPath("$.databaseBackup.errorCategory")
                        .value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.mediaBackup.errorCategory")
                        .value("MEDIA_BACKUP_FAILED"))
                .andExpect(jsonPath("$.analyticsAggregation.finishedAt")
                        .value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.analyticsAggregation.artifactChecksum")
                        .value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.analyticsAggregation.errorCategory")
                        .value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.contactRetention")
                        .value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.mediaCleanup")
                        .value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.deployment.type").value("DEPLOYMENT"))
                .andExpect(jsonPath("$.restoreDrill")
                        .value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.serverTime").value("2026-07-18T02:00:00Z"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain(
                "error_summary",
                "details",
                "payload",
                "objectKey",
                "bucket",
                "/srv/portfolio",
                "SecretKey",
                "visitorEmail",
                "stack trace");
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

    private static MaintenanceView succeeded(String type) {
        return new MaintenanceView(
                type, "SUCCEEDED", START, FINISH, CHECKSUM, null);
    }

    private static MaintenanceView failed(String type) {
        return new MaintenanceView(
                type,
                "FAILED",
                START,
                FINISH,
                null,
                MaintenanceView.safeErrorCategory(type, "FAILED"));
    }

    private static MaintenanceView running(String type) {
        return new MaintenanceView(
                type, "RUNNING", START, null, null, null);
    }
}
