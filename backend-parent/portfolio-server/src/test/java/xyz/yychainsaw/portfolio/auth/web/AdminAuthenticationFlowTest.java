package xyz.yychainsaw.portfolio.auth.web;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.exceptions.CodeGenerationException;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.session.FlushMode;
import org.springframework.session.SaveMode;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;
import xyz.yychainsaw.portfolio.PortfolioApplication;
import xyz.yychainsaw.portfolio.audit.AuditCommand;
import xyz.yychainsaw.portfolio.audit.AuditService;
import xyz.yychainsaw.portfolio.audit.JdbcAuditService;
import xyz.yychainsaw.portfolio.auth.RecoveryCodeService;
import xyz.yychainsaw.portfolio.auth.crypto.TotpEnvelopeCrypto;
import xyz.yychainsaw.portfolio.auth.model.AdminStatus;
import xyz.yychainsaw.portfolio.auth.model.AdminUser;
import xyz.yychainsaw.portfolio.auth.persistence.AdminUserRepository;
import xyz.yychainsaw.portfolio.auth.persistence.RecoveryCodeRepository;
import xyz.yychainsaw.portfolio.auth.session.AdminSessionRepository;
import xyz.yychainsaw.portfolio.config.SecurityConfiguration;
import org.springframework.test.context.TestPropertySource;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
@AutoConfigureMockMvc
@Import(AdminAuthenticationFlowTest.FlowTestConfiguration.class)
@TestPropertySource(properties = {
        "portfolio.security.session.cleanup-interval=PT24H",
        "portfolio.web.allow-development-cors=true",
        "portfolio.web.development-origin=http://localhost:5174"
})
@Isolated
class AdminAuthenticationFlowTest extends PostgresIntegrationTestBase {
    private static final String CSRF_PATH = "/api/admin/auth/csrf";
    private static final String PASSWORD_PATH = "/api/admin/auth/password";
    private static final String SECOND_FACTOR_PATH = "/api/admin/auth/second-factor";
    private static final String ME_PATH = "/api/admin/auth/me";
    private static final String LOGOUT_PATH = "/api/admin/auth/logout";
    private static final String SESSIONS_PATH = "/api/admin/security/sessions";
    private static final String SESSION_COOKIE = "PORTFOLIO_SESSION";
    private static final String XSRF_COOKIE = "XSRF-TOKEN";
    private static final String XSRF_HEADER = "X-XSRF-TOKEN";
    private static final String TRACE_HEADER = "X-Trace-Id";
    private static final String DEVELOPMENT_ORIGIN = "http://localhost:5174";
    private static final String ADMIN_USERNAME = "PortfolioAdmin";
    private static final String ADMIN_PASSWORD = "Correct-Horse-Battery-Staple-47!";
    private static final String WRONG_PASSWORD = "incorrect-password";
    private static final String TOTP_SECRET = "JBSWY3DPEHPK3PXP";
    private static final String RECOVERY_CODE = "ABCD-EFGH-JKLM";
    private static final Instant INITIAL_TIME = Instant.parse("2026-07-15T08:00:00Z");
    private static final Duration PENDING_LIFETIME = Duration.ofMinutes(5);
    private static final AtomicInteger REMOTE_SEQUENCE = new AtomicInteger();

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired FaultInjectingAdminUserRepository admins;
    @Autowired RecoveryCodeRepository recoveryCodes;
    @Autowired RecoveryCodeService recoveryCodeService;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    @Autowired TotpEnvelopeCrypto totpCrypto;
    @Autowired CodeGenerator totpCodes;
    @Autowired MonotonicTestClock clock;
    @Autowired RecordingAuditService audit;
    @Autowired FaultInjectingAdminSessionRepository sessionRepository;
    @Autowired ConcurrencyProbeFilter probe;
    @Autowired ApplicationContext applicationContext;
    @Autowired FilterChainProxy securityFilters;
    @Autowired Environment environment;

    private Fixture fixture;

    @BeforeEach
    void resetCollaboratorsAndAdvanceClock() {
        clock.advance(Duration.ofMinutes(1));
        admins.reset();
        audit.reset();
        sessionRepository.reset();
        probe.reset();
    }

    @AfterEach
    void cleanOnlyTrackedRowsAndTestHooks() {
        try {
            probe.reset();
            sessionRepository.reset();
            admins.reset();
            audit.reset();
        } finally {
            if (fixture != null) {
                fixture.close();
                fixture = null;
            }
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void csrfCookieProtocolAndDevelopmentCorsAreExactAndDefaultCorsIsClosed() throws Exception {
        long sessionsBefore = springSessionCount();
        CsrfExchange csrf = csrf();

        assertThat(csrf.headerName()).isEqualTo(XSRF_HEADER);
        assertThat(csrf.parameterName()).isEqualTo("_csrf");
        assertThat(csrf.cookie().getName()).isEqualTo(XSRF_COOKIE);
        assertThat(csrf.cookie().getPath()).isEqualTo("/");
        assertThat(csrf.cookie().isHttpOnly()).isFalse();
        assertThat(csrf.cookie().getSecure()).isFalse();
        assertThat(csrf.cookie().getDomain()).isNull();
        assertThat(csrf.cookie().getMaxAge()).isEqualTo(-1);
        assertThat(csrf.cookie().getAttribute("SameSite")).isEqualToIgnoringCase("Strict");
        assertThat(findResponseCookie(csrf.source(), SESSION_COOKIE)).isEmpty();
        assertThat(springSessionCount()).isEqualTo(sessionsBefore);

        MvcResult missing = mvc.perform(post(PASSWORD_PATH)
                        .with(remote(nextRemote()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordJson(ADMIN_USERNAME, WRONG_PASSWORD)))
                .andReturn();
        assertProblem(missing, 403, "CSRF_INVALID");

        MvcResult mismatch = mvc.perform(post(PASSWORD_PATH)
                        .with(remote(nextRemote()))
                        .cookie(csrf.cookie())
                        .header(XSRF_HEADER, csrf.token() + "-different")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordJson(ADMIN_USERNAME, WRONG_PASSWORD)))
                .andReturn();
        assertProblem(mismatch, 403, "CSRF_INVALID");

        Set<String> expectedMethods = Set.of("get", "post", "put", "patch", "delete", "options");
        for (String requested : List.of("GET", "POST", "PUT", "PATCH", "DELETE")) {
            MvcResult preflight = mvc.perform(options(PASSWORD_PATH)
                            .header(HttpHeaders.ORIGIN, DEVELOPMENT_ORIGIN)
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, requested)
                            .header(
                                    HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
                                    "content-type,x-xsrf-token"))
                    .andReturn();
            assertThat(preflight.getResponse().getStatus()).isEqualTo(200);
            assertThat(preflight.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                    .isEqualTo(DEVELOPMENT_ORIGIN);
            assertThat(preflight.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS))
                    .isEqualTo("true");
            assertThat(csv(preflight.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS)))
                    .containsExactlyInAnyOrderElementsOf(expectedMethods);
            assertThat(csv(preflight.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS)))
                    .containsExactlyInAnyOrder("content-type", "x-xsrf-token");
            assertThat(preflight.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_MAX_AGE))
                    .isEqualTo("3600");
        }

        for (String rejected : List.of(
                "http://localhost:5174.evil.example", "*", "null", "http://localhost:5174/path")) {
            MvcResult preflight = mvc.perform(options(PASSWORD_PATH)
                            .header(HttpHeaders.ORIGIN, rejected)
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type"))
                    .andReturn();
            assertThat(preflight.getResponse().getStatus()).isEqualTo(403);
            assertThat(preflight.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                    .isNull();
        }

        try (ConfigurableApplicationContext closedCors = startSecondaryServletContext(false)) {
            CorsConfigurationSource source = closedCors.getBean(
                    "corsConfigurationSource", CorsConfigurationSource.class);
            MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", PASSWORD_PATH);
            request.addHeader(HttpHeaders.ORIGIN, DEVELOPMENT_ORIGIN);
            request.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST");
            CorsConfiguration configuration = source.getCorsConfiguration(request);
            assertThat(configuration).isNotNull();
            assertThat(configuration.getAllowedOrigins()).isEmpty();
        }
    }

    @Test
    void filterTopologyPersistenceModesAndSecurityHeadersAreFrozen() throws Exception {
        assertThat(environment.getProperty("spring.session.jdbc.flush-mode"))
                .isEqualToIgnoringCase(FlushMode.IMMEDIATE.name());
        assertThat(environment.getProperty("spring.session.jdbc.save-mode"))
                .isEqualToIgnoringCase(SaveMode.ON_SET_ATTRIBUTE.name().replace('_', '-'));
        assertThat(applicationContext.getBean(SecurityConfiguration.class)).isNotNull();

        List<jakarta.servlet.Filter> chain = securityFilters.getFilters(ME_PATH);
        int csrfIndex = indexOf(chain, CsrfFilter.class);
        assertThat(csrfIndex).isNotNegative();
        List<jakarta.servlet.Filter> enforcement = chain.stream()
                .filter(SessionMetadataEnforcementFilter.class::isInstance)
                .toList();
        assertThat(enforcement).hasSize(1);
        assertThat(chain.indexOf(enforcement.get(0))).isGreaterThan(csrfIndex);

        List<FilterRegistrationBean<?>> registrations = applicationContext
                .getBeansOfType(FilterRegistrationBean.class)
                .values()
                .stream()
                .map(bean -> (FilterRegistrationBean<?>) bean)
                .collect(java.util.stream.Collectors.toList());
        List<FilterRegistrationBean<?>> outer = registrations.stream()
                .filter(bean -> bean.getFilter() instanceof SessionPersistenceConcurrencyFilter)
                .toList();
        assertThat(outer).hasSize(1);
        assertThat(outer.get(0).getOrder()).isEqualTo(SessionRepositoryFilter.DEFAULT_ORDER - 1);
        assertThat(outer.get(0).getUrlPatterns()).containsExactly("/*");
        assertThat(registrations).noneMatch(
                bean -> bean.getFilter() instanceof SessionMetadataEnforcementFilter);

        MvcResult secureApi = mvc.perform(get(CSRF_PATH).secure(true)).andReturn();
        assertThat(secureApi.getResponse().getStatus()).isEqualTo(200);
        assertThat(secureApi.getResponse().getHeader("Content-Security-Policy"))
                .isEqualTo("default-src 'none'; frame-ancestors 'none'");
        assertThat(secureApi.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(secureApi.getResponse().getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(secureApi.getResponse().getHeader("Referrer-Policy"))
                .isEqualTo("strict-origin-when-cross-origin");
        assertThat(secureApi.getResponse().getHeader("Permissions-Policy")).isNotBlank();
        assertThat(secureApi.getResponse().getHeader("Strict-Transport-Security"))
                .contains("max-age=31536000");
        assertThat(secureApi.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");

        MvcResult publicHtml = mvc.perform(get("/")).andReturn();
        assertThat(publicHtml.getResponse().getHeader("Content-Security-Policy")).isNull();

        String basic = Base64.getEncoder().encodeToString(
                (ADMIN_USERNAME + ":" + ADMIN_PASSWORD).getBytes(StandardCharsets.UTF_8));
        MvcResult basicAttempt = mvc.perform(get(ME_PATH).header(HttpHeaders.AUTHORIZATION, "Basic " + basic))
                .andReturn();
        assertProblem(basicAttempt, 401, "AUTHENTICATION_REQUIRED");
        assertThat(mvc.perform(get("/login")).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus())
                .isEqualTo(200);
        assertThat(mvc.perform(get("/actuator/info")).andReturn().getResponse().getStatus())
                .isIn(401, 403);
    }

    @Test
    void passwordThenTotpRotatesOnlyThePublicIdAndLogoutCannotResurrectTheSession()
            throws Exception {
        Fixture admin = fixture();
        CsrfExchange csrf = csrf();
        String remote = nextRemote();

        MvcResult password = performPassword(
                csrf, null, ADMIN_USERNAME, ADMIN_PASSWORD, remote, null);
        assertThat(password.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(password).path("next").asText()).isEqualTo("SECOND_FACTOR");
        assertThat(Instant.parse(body(password).path("expiresAt").asText()))
                .isEqualTo(clock.instant().plus(PENDING_LIFETIME));
        Cookie pendingCookie = requireSessionCookie(password, admin);
        String oldPublicId = pendingCookie.getValue();
        String stablePrimaryId = requireStablePrimaryId(oldPublicId);
        admin.trackPrimary(stablePrimaryId);

        MvcResult passwordOnly = mvc.perform(get(ME_PATH).cookie(pendingCookie)).andReturn();
        assertProblem(passwordOnly, 401, "AUTHENTICATION_REQUIRED");
        assertThat(metadataCount(admin.adminId())).isZero();

        String pendingBytes = sessionAttributeBytes(stablePrimaryId);
        String passwordHash = admins.findById(admin.adminId()).orElseThrow().passwordHash();
        assertThat(pendingBytes)
                .doesNotContain(ADMIN_PASSWORD, passwordHash, TOTP_SECRET, RECOVERY_CODE)
                .doesNotContain("SPRING_SECURITY_CONTEXT");

        Instant loginAt = clock.instant();
        String code = currentTotp();
        MvcResult authenticated = performSecondFactor(
                csrf, pendingCookie, "TOTP", code, remote, null);
        assertThat(authenticated.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(authenticated).path("id").asText()).isEqualTo(admin.adminId().toString());
        assertThat(body(authenticated).path("username").asText()).isEqualTo(ADMIN_USERNAME);

        Cookie activeCookie = requireSessionCookie(authenticated, admin);
        String newPublicId = activeCookie.getValue();
        assertCanonicalUuid(newPublicId);
        assertThat(newPublicId).isNotEqualTo(oldPublicId);
        assertThat(findPrimaryId(oldPublicId)).isEmpty();
        assertThat(requireStablePrimaryId(newPublicId)).isEqualTo(stablePrimaryId);

        assertProblem(mvc.perform(get(ME_PATH).cookie(pendingCookie)).andReturn(),
                401, "AUTHENTICATION_REQUIRED");
        MvcResult me = mvc.perform(get(ME_PATH).cookie(activeCookie)).andReturn();
        assertThat(me.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(me).path("username").asText()).isEqualTo(ADMIN_USERNAME);

        SpringRow spring = springRow(stablePrimaryId);
        assertThat(spring.publicId()).isEqualTo(newPublicId);
        assertThat(spring.principalName()).isEqualTo(ADMIN_USERNAME);
        MetadataRow metadata = activeMetadata(admin.adminId(), stablePrimaryId);
        admin.trackMetadata(metadata.id());
        assertThat(metadata.status()).isEqualTo("ACTIVE");
        assertThat(adminLastLogin(admin.adminId())).contains(loginAt);
        assertThat(audit.commands()).anySatisfy(command -> {
            assertThat(command.action()).isEqualTo("AUTH_LOGIN_SUCCEEDED");
            assertThat(command.actorAdminId()).isEqualTo(admin.adminId());
            assertThat(command.targetId()).isEqualTo(admin.adminId().toString());
            assertThat(command.metadata()).containsExactly(Map.entry("method", "TOTP"));
        });

        MvcResult listed = mvc.perform(get(SESSIONS_PATH).cookie(activeCookie)).andReturn();
        assertThat(listed.getResponse().getStatus()).isEqualTo(200);
        JsonNode row = body(listed).get(0);
        assertThat(row.path("id").asText()).isEqualTo(metadata.id().toString());
        assertThat(row.path("current").asBoolean()).isTrue();
        assertThat(listed.getResponse().getContentAsString())
                .doesNotContain(stablePrimaryId, newPublicId, oldPublicId);

        MvcResult logout = mvc.perform(withCsrf(post(LOGOUT_PATH).cookie(activeCookie), csrf))
                .andReturn();
        assertThat(logout.getResponse().getStatus()).isEqualTo(204);
        assertClearSiteData(logout);
        assertProblem(mvc.perform(get(ME_PATH).cookie(activeCookie)).andReturn(),
                401, "AUTHENTICATION_REQUIRED");
        assertThat(springRowCount(stablePrimaryId)).isZero();
        assertThat(metadata(metadata.id()).status()).isEqualTo("REVOKED");
        assertThat(metadata(metadata.id()).reason()).isEqualTo("LOGOUT");
    }

    @Test
    void recoveryCodeIsOneTimeAndItsExpectedRejectionAuditCommitsBeforeGeneric401()
            throws Exception {
        Fixture admin = fixture();
        CsrfExchange csrf = csrf();

        Cookie pending = requireSessionCookie(
                performPassword(csrf, null, ADMIN_USERNAME, ADMIN_PASSWORD, nextRemote(), null),
                admin);
        MvcResult first = performSecondFactor(
                csrf, pending, "RECOVERY_CODE", RECOVERY_CODE, nextRemote(), null);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        Cookie active = requireSessionCookie(first, admin);
        assertThat(usedRecoveryCount(admin.adminId())).isOne();

        audit.delegateFor("AUTH_SECOND_FACTOR_REJECTED", DelegateMode.DELEGATE);
        Cookie retryPending = requireSessionCookie(
                performPassword(csrf, null, ADMIN_USERNAME, ADMIN_PASSWORD, nextRemote(), null),
                admin);
        MvcResult reused = performSecondFactor(
                csrf, retryPending, "RECOVERY_CODE", RECOVERY_CODE, nextRemote(), null);
        assertProblem(reused, 401, "AUTHENTICATION_FAILED");
        assertThat(usedRecoveryCount(admin.adminId())).isOne();
        assertThat(metadataCount(admin.adminId())).isOne();
        assertThat(mvc.perform(get(ME_PATH).cookie(active)).andReturn().getResponse().getStatus())
                .isEqualTo(200);

        AuditDatabaseRow rejection = jdbc.sql("""
                        select actor_admin_id, action, outcome, metadata ->> 'method' method
                        from portfolio.audit_log
                        where action='AUTH_SECOND_FACTOR_REJECTED' and target_id=:target
                        order by created_at desc, id desc limit 1
                        """)
                .param("target", admin.adminId().toString())
                .query((rs, rowNumber) -> new AuditDatabaseRow(
                        rs.getObject("actor_admin_id", UUID.class),
                        rs.getString("action"),
                        rs.getString("outcome"),
                        rs.getString("method")))
                .single();
        assertThat(rejection.actorAdminId()).isNull();
        assertThat(rejection.action()).isEqualTo("AUTH_SECOND_FACTOR_REJECTED");
        assertThat(rejection.outcome()).isEqualTo("FAILURE");
        assertThat(rejection.method()).isEqualTo("RECOVERY_CODE");
    }

    @Test
    void credentialFailuresAreIndistinguishableAndPasswordRateLimitStartsOnAttemptSix()
            throws Exception {
        Fixture admin = fixture();
        CsrfExchange csrf = csrf();

        JsonNode wrong = normalizedProblem(performPassword(
                csrf, null, ADMIN_USERNAME, WRONG_PASSWORD, nextRemote(), null));
        JsonNode missing = normalizedProblem(performPassword(
                csrf, null, "MissingAdministrator", ADMIN_PASSWORD, nextRemote(), null));
        jdbc.sql("update portfolio.admin_user set status='DISABLED' where id=:id")
                .param("id", admin.adminId())
                .update();
        JsonNode disabled = normalizedProblem(performPassword(
                csrf, null, ADMIN_USERNAME, ADMIN_PASSWORD, nextRemote(), null));
        assertThat(wrong).isEqualTo(missing).isEqualTo(disabled);
        assertThat(wrong.path("code").asText()).isEqualTo("AUTHENTICATION_FAILED");
        assertThat(wrong.toString()).doesNotContain(ADMIN_USERNAME, "MissingAdministrator");

        String limitedRemote = nextRemote();
        for (int attempt = 1; attempt <= 5; attempt++) {
            MvcResult rejected = performPassword(
                    csrf, null, ADMIN_USERNAME, WRONG_PASSWORD, limitedRemote, null);
            assertProblem(rejected, 401, "AUTHENTICATION_FAILED");
        }
        MvcResult limited = performPassword(
                csrf, null, ADMIN_USERNAME, WRONG_PASSWORD, limitedRemote, null);
        assertProblem(limited, 429, "RATE_LIMITED");
        int retryAfter = Integer.parseInt(limited.getResponse().getHeader(HttpHeaders.RETRY_AFTER));
        assertThat(retryAfter).isPositive();
        assertThat(body(limited).path("fieldErrors").path("retryAfterSeconds").asInt())
                .isEqualTo(retryAfter);
        assertThat(limited.getResponse().getContentAsString()).doesNotContain(ADMIN_USERNAME);
        assertThat(springSessionCount()).isZero();
    }

    @Test
    void expiredAndStaleChallengesFailClosedWhileMalformedMethodsDoNotConsumeAChallenge()
            throws Exception {
        Fixture admin = fixture();
        CsrfExchange csrf = csrf();

        Cookie expiring = requireSessionCookie(
                performPassword(csrf, null, ADMIN_USERNAME, ADMIN_PASSWORD, nextRemote(), null),
                admin);
        clock.advance(PENDING_LIFETIME);
        MvcResult expired = performSecondFactor(
                csrf, expiring, "TOTP", currentTotp(), nextRemote(), null);
        assertProblem(expired, 401, "AUTHENTICATION_FAILED");
        assertThat(metadataCount(admin.adminId())).isZero();

        Cookie stale = requireSessionCookie(
                performPassword(csrf, null, ADMIN_USERNAME, ADMIN_PASSWORD, nextRemote(), null),
                admin);
        jdbc.sql("update portfolio.admin_user set version=version+1 where id=:id")
                .param("id", admin.adminId())
                .update();
        MvcResult staleResult = performSecondFactor(
                csrf, stale, "TOTP", currentTotp(), nextRemote(), null);
        assertProblem(staleResult, 401, "AUTHENTICATION_FAILED");
        assertThat(metadataCount(admin.adminId())).isZero();

        Cookie valid = requireSessionCookie(
                performPassword(csrf, null, ADMIN_USERNAME, ADMIN_PASSWORD, nextRemote(), null),
                admin);
        for (String malformed : List.of(
                "{\"code\":\"123456\"}",
                "{\"method\":null,\"code\":\"123456\"}",
                "{\"method\":\"SMS\",\"code\":\"123456\"}")) {
            MvcResult result = mvc.perform(withCsrf(post(SECOND_FACTOR_PATH)
                            .cookie(valid)
                            .with(remote(nextRemote()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(malformed), csrf))
                    .andReturn();
            assertProblem(result, 400, "MALFORMED_REQUEST");
        }
        MvcResult authenticated = performSecondFactor(
                csrf, valid, "TOTP", currentTotp(), nextRemote(), null);
        assertThat(authenticated.getResponse().getStatus()).isEqualTo(200);
        requireSessionCookie(authenticated, admin);
    }

    @Test
    void futureDisabledDeletedMalformedCodeAndRealProviderFailureLeaveNoStaleLogin()
            throws Exception {
        Fixture admin = fixture();
        CsrfExchange csrf = csrf();

        Cookie future = requireSessionCookie(
                performPassword(csrf, null, ADMIN_USERNAME, ADMIN_PASSWORD, nextRemote(), null),
                admin);
        String futurePrimary = requireStablePrimaryId(future.getValue());
        PendingSecondFactor captured = pendingAttribute(futurePrimary);
        replaceSessionAttribute(
                futurePrimary,
                PendingSecondFactor.SESSION_KEY,
                serialize(new PendingSecondFactor(
                        captured.challengeId(),
                        captured.adminId(),
                        captured.adminVersion(),
                        clock.instant().plusSeconds(1),
                        captured.failures())));
        assertProblem(
                performSecondFactor(
                        csrf, future, "TOTP", currentTotp(), nextRemote(), null),
                401,
                "AUTHENTICATION_FAILED");
        assertNoStaleLogin(admin);

        Cookie disabled = requireSessionCookie(
                performPassword(csrf, null, ADMIN_USERNAME, ADMIN_PASSWORD, nextRemote(), null),
                admin);
        assertThat(jdbc.sql("update portfolio.admin_user set status='DISABLED' where id=:id")
                .param("id", admin.adminId())
                .update()).isOne();
        assertProblem(
                performSecondFactor(
                        csrf, disabled, "TOTP", currentTotp(), nextRemote(), null),
                401,
                "AUTHENTICATION_FAILED");
        assertNoStaleLogin(admin);
        assertThat(jdbc.sql("update portfolio.admin_user set status='ACTIVE' where id=:id")
                .param("id", admin.adminId())
                .update()).isOne();

        Cookie malformed = requireSessionCookie(
                performPassword(csrf, null, ADMIN_USERNAME, ADMIN_PASSWORD, nextRemote(), null),
                admin);
        assertProblem(
                performSecondFactor(
                        csrf, malformed, "TOTP", "12AB56", nextRemote(), null),
                401,
                "AUTHENTICATION_FAILED");
        assertNoStaleLogin(admin);

        Cookie providerFailure = requireSessionCookie(
                performPassword(csrf, null, ADMIN_USERNAME, ADMIN_PASSWORD, nextRemote(), null),
                admin);
        String providerPrimary = requireStablePrimaryId(providerFailure.getValue());
        byte[] originalCiphertext = jdbc.sql(
                        "select totp_ciphertext from portfolio.admin_user where id=:id")
                .param("id", admin.adminId())
                .query((rs, rowNumber) -> rs.getBytes("totp_ciphertext"))
                .single();
        assertThat(jdbc.sql(
                        "update portfolio.admin_user set totp_ciphertext=:ciphertext where id=:id")
                .param("ciphertext", new byte[17])
                .param("id", admin.adminId())
                .update()).isOne();
        try {
            MvcResult failed = performSecondFactor(
                    csrf, providerFailure, "TOTP", currentTotp(), nextRemote(), null);
            assertProblem(failed, 500, "INTERNAL_ERROR");
            assertClearSiteData(failed);
            assertThat(failed.getResponse().getContentAsString())
                    .doesNotContain("TOTP ciphertext authentication failed", "SecurityException");
            assertThat(springRowCount(providerPrimary)).isZero();
            assertNoStaleLogin(admin);
        } finally {
            assertThat(jdbc.sql(
                            "update portfolio.admin_user set totp_ciphertext=:ciphertext where id=:id")
                    .param("ciphertext", originalCiphertext)
                    .param("id", admin.adminId())
                    .update()).isOne();
        }

        Cookie deleted = requireSessionCookie(
                performPassword(csrf, null, ADMIN_USERNAME, ADMIN_PASSWORD, nextRemote(), null),
                admin);
        assertThat(migratorJdbc().sql("delete from portfolio.admin_user where id=:id")
                .param("id", admin.adminId())
                .update()).isOne();
        assertProblem(
                performSecondFactor(
                        csrf, deleted, "TOTP", currentTotp(), nextRemote(), null),
                401,
                "AUTHENTICATION_FAILED");
        assertNoStaleLogin(admin);
    }

    @Test
    void fifthWrongFactorExhaustsTheChallengeAndConcurrentTotpRecoveryYieldAtMostOneLogin()
            throws Exception {
        Fixture admin = fixture();
        CsrfExchange csrf = csrf();

        Cookie exhausted = requireSessionCookie(
                performPassword(csrf, null, ADMIN_USERNAME, ADMIN_PASSWORD, nextRemote(), null),
                admin);
        String wrongCode = currentTotp().equals("000000") ? "999999" : "000000";
        for (int attempt = 1; attempt <= 5; attempt++) {
            MvcResult rejected = performSecondFactor(
                    csrf, exhausted, "TOTP", wrongCode, nextRemote(), null);
            assertProblem(rejected, 401, "AUTHENTICATION_FAILED");
        }
        MvcResult afterBudget = performSecondFactor(
                csrf, exhausted, "TOTP", currentTotp(), nextRemote(), null);
        assertProblem(afterBudget, 401, "AUTHENTICATION_FAILED");
        assertThat(audit.commands().stream()
                .filter(command -> command.action().equals("AUTH_SECOND_FACTOR_REJECTED")))
                .hasSize(5);
        assertThat(metadataCount(admin.adminId())).isZero();

        Cookie racing = requireSessionCookie(
                performPassword(csrf, null, ADMIN_USERNAME, ADMIN_PASSWORD, nextRemote(), null),
                admin);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<MvcResult>> futures = new ArrayList<>();
        try {
            futures.add(executor.submit(() -> {
                ready.countDown();
                await(start, "concurrent factor start was not released");
                return performSecondFactor(
                        csrf, racing, "TOTP", currentTotp(), nextRemote(), null);
            }));
            futures.add(executor.submit(() -> {
                ready.countDown();
                await(start, "concurrent factor start was not released");
                return performSecondFactor(
                        csrf, racing, "RECOVERY_CODE", RECOVERY_CODE, nextRemote(), null);
            }));
            await(ready, "concurrent factor workers were not ready");
            start.countDown();
            List<MvcResult> results = List.of(
                    futures.get(0).get(10, SECONDS), futures.get(1).get(10, SECONDS));
            assertThat(results).filteredOn(result -> result.getResponse().getStatus() == 200)
                    .hasSize(1);
            assertThat(results).filteredOn(result -> result.getResponse().getStatus() == 401)
                    .hasSize(1);
            for (MvcResult result : results) {
                if (result.getResponse().getStatus() == 200) {
                    requireSessionCookie(result, admin);
                } else {
                    assertProblem(result, 401, "AUTHENTICATION_FAILED");
                }
            }
            assertThat(metadataCount(admin.adminId())).isOne();
            assertThat(audit.commands().stream()
                    .filter(command -> command.action().equals("AUTH_LOGIN_SUCCEEDED")))
                    .hasSize(1);
            assertThat(usedRecoveryCount(admin.adminId())).isBetween(0L, 1L);
        } finally {
            start.countDown();
            for (Future<MvcResult> future : futures) {
                if (!future.isDone()) {
                    future.cancel(true);
                }
            }
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, SECONDS)).isTrue();
        }
    }

    @Test
    void fifthAttemptAndPasswordReplacementTombstonesRejectClonedStaleSnapshots()
            throws Exception {
        Fixture admin = fixture();
        CsrfExchange csrf = csrf();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        RepositoryGate fifthGate = null;
        RepositoryGate replacementGate = null;
        Future<MvcResult> fifthAttempt = null;
        Future<MvcResult> replacement = null;
        try {
            Cookie pending = requireSessionCookie(
                    performPassword(
                            csrf, null, ADMIN_USERNAME, ADMIN_PASSWORD, nextRemote(), null),
                    admin);
            String wrongCode = currentTotp().equals("000000") ? "999999" : "000000";
            for (int attempt = 1; attempt <= 4; attempt++) {
                assertProblem(
                        performSecondFactor(
                                csrf, pending, "TOTP", wrongCode, nextRemote(), null),
                        401,
                        "AUTHENTICATION_FAILED");
            }
            Cookie staleAtFifth = clonePendingSession(admin, pending);

            fifthGate = admins.pauseNextLockedLookup();
            RepositoryGate activeFifthGate = fifthGate;
            fifthAttempt = executor.submit(() -> performSecondFactor(
                    csrf, pending, "TOTP", wrongCode, nextRemote(), null));
            await(activeFifthGate.entered(), "fifth factor attempt did not reach the parent lock");

            MvcResult concurrentStale = performSecondFactor(
                    csrf, staleAtFifth, "TOTP", currentTotp(), nextRemote(), null);
            assertProblem(concurrentStale, 429, "RATE_LIMITED");
            assertPositiveRetryAfter(concurrentStale);

            activeFifthGate.release();
            MvcResult exhausted = fifthAttempt.get(10, SECONDS);
            assertProblem(exhausted, 401, "AUTHENTICATION_FAILED");

            MvcResult tombstoned = performSecondFactor(
                    csrf, staleAtFifth, "TOTP", currentTotp(), nextRemote(), null);
            assertProblem(tombstoned, 429, "RATE_LIMITED");
            assertMatchingRetryAfter(tombstoned);
            assertThat(audit.commands().stream()
                    .filter(command -> command.action().equals("AUTH_SECOND_FACTOR_REJECTED")))
                    .hasSize(5);
            assertNoStaleLogin(admin);

            Cookie replaceable = requireSessionCookie(
                    performPassword(
                            csrf, null, ADMIN_USERNAME, ADMIN_PASSWORD, nextRemote(), null),
                    admin);
            Cookie staleBeforeReplacement = clonePendingSession(admin, replaceable);
            replacementGate = admins.pauseNextUsernameLookup();
            RepositoryGate activeReplacementGate = replacementGate;
            replacement = executor.submit(() -> performPassword(
                    csrf,
                    replaceable,
                    ADMIN_USERNAME,
                    ADMIN_PASSWORD,
                    nextRemote(),
                    null));
            await(activeReplacementGate.entered(),
                    "replacement password request did not pass terminal cleanup");

            MvcResult replacedSnapshot = performSecondFactor(
                    csrf, staleBeforeReplacement, "TOTP", currentTotp(), nextRemote(), null);
            assertProblem(replacedSnapshot, 429, "RATE_LIMITED");
            assertMatchingRetryAfter(replacedSnapshot);

            activeReplacementGate.release();
            MvcResult replacementResult = replacement.get(10, SECONDS);
            assertThat(replacementResult.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(replacementResult).path("next").asText()).isEqualTo("SECOND_FACTOR");

            MvcResult authenticated = performSecondFactor(
                    csrf, replaceable, "TOTP", currentTotp(), nextRemote(), null);
            assertThat(authenticated.getResponse().getStatus()).isEqualTo(200);
            requireSessionCookie(authenticated, admin);
            assertThat(metadataCount(admin.adminId())).isOne();
            assertThat(audit.commands().stream()
                    .filter(command -> command.action().equals("AUTH_LOGIN_SUCCEEDED")))
                    .hasSize(1);
        } finally {
            if (fifthGate != null) {
                fifthGate.release();
            }
            if (replacementGate != null) {
                replacementGate.release();
            }
            if (fifthAttempt != null && !fifthAttempt.isDone()) {
                fifthAttempt.cancel(true);
            }
            if (replacement != null && !replacement.isDone()) {
                replacement.cancel(true);
            }
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, SECONDS)).isTrue();
        }
    }

    @Test
    void listDistinguishesCurrentSessionAndOtherThenCurrentRevocationStayFailClosed()
            throws Exception {
        Fixture admin = fixture();
        CsrfExchange csrf = csrf();
        AuthenticatedSession first = loginWithTotp(admin, csrf, nextRemote());
        clock.advance(Duration.ofSeconds(1));
        AuthenticatedSession second = loginWithTotp(admin, csrf, nextRemote());

        UUID firstMetadata = metadataIdForPrimary(first.stablePrimaryId());
        UUID secondMetadata = metadataIdForPrimary(second.stablePrimaryId());
        admin.trackMetadata(firstMetadata);
        admin.trackMetadata(secondMetadata);

        MvcResult listed = mvc.perform(get(SESSIONS_PATH).cookie(second.cookie())).andReturn();
        assertThat(listed.getResponse().getStatus()).isEqualTo(200);
        Map<String, Boolean> currentById = new LinkedHashMap<>();
        for (JsonNode row : body(listed)) {
            currentById.put(row.path("id").asText(), row.path("current").asBoolean());
        }
        assertThat(currentById)
                .containsEntry(firstMetadata.toString(), false)
                .containsEntry(secondMetadata.toString(), true);

        MvcResult revokeOther = mvc.perform(withCsrf(
                        post(SESSIONS_PATH + "/" + firstMetadata + "/revoke")
                                .cookie(second.cookie()), csrf))
                .andReturn();
        assertThat(revokeOther.getResponse().getStatus()).isEqualTo(204);
        assertProblem(mvc.perform(get(ME_PATH).cookie(first.cookie())).andReturn(),
                401, "AUTHENTICATION_REQUIRED");
        assertThat(mvc.perform(get(ME_PATH).cookie(second.cookie())).andReturn()
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(metadata(firstMetadata).reason()).isEqualTo("ADMIN_REQUEST");

        MvcResult revokeCurrent = mvc.perform(withCsrf(
                        post(SESSIONS_PATH + "/" + secondMetadata + "/revoke")
                                .cookie(second.cookie()), csrf))
                .andReturn();
        assertThat(revokeCurrent.getResponse().getStatus()).isEqualTo(204);
        assertClearSiteData(revokeCurrent);
        assertProblem(mvc.perform(get(ME_PATH).cookie(second.cookie())).andReturn(),
                401, "AUTHENTICATION_REQUIRED");
        assertThat(springRowCount(second.stablePrimaryId())).isZero();
        assertThat(metadata(secondMetadata).reason()).isEqualTo("ADMIN_REQUEST");
    }

    @Test
    void metadataInvalidationClearsTheSessionButRepositoryFailureRetainsItForRetry()
            throws Exception {
        Fixture admin = fixture();
        CsrfExchange csrf = csrf();
        AuthenticatedSession active = loginWithTotp(admin, csrf, nextRemote());
        UUID metadataId = metadataIdForPrimary(active.stablePrimaryId());
        admin.trackMetadata(metadataId);

        sessionRepository.failActiveLookup();
        MvcResult internal = mvc.perform(get(ME_PATH).cookie(active.cookie())).andReturn();
        assertProblem(internal, 500, "INTERNAL_ERROR");
        assertThat(internal.getResponse().getContentAsString())
                .doesNotContain("synthetic repository secret", "IllegalStateException");
        assertThat(springRowCount(active.stablePrimaryId())).isOne();
        sessionRepository.reset();
        assertThat(mvc.perform(get(ME_PATH).cookie(active.cookie())).andReturn()
                .getResponse().getStatus()).isEqualTo(200);

        Instant ended = clock.instant();
        jdbc.sql("""
                        update portfolio.admin_session_metadata
                        set status='REVOKED', ended_at=:ended,
                            revocation_reason='ADMIN_REQUEST', version=version+1
                        where id=:id
                        """)
                .param("ended", ended.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("id", metadataId)
                .update();
        MvcResult rejected = mvc.perform(get(ME_PATH).cookie(active.cookie())).andReturn();
        assertProblem(rejected, 401, "AUTHENTICATION_REQUIRED");
        assertClearSiteData(rejected);
        assertThat(springRowCount(active.stablePrimaryId())).isZero();
    }

    @Test
    void acceptedAuditAndSuccessAuditFailuresPublishNoUsableAuthentication() throws Exception {
        Fixture admin = fixture();
        CsrfExchange csrf = csrf();

        audit.delegateFor("AUTH_PASSWORD_ACCEPTED", DelegateMode.FAIL_BEFORE_DELEGATE);
        MvcResult passwordAuditFailure = performPassword(
                csrf, null, ADMIN_USERNAME, ADMIN_PASSWORD, nextRemote(), null);
        assertProblem(passwordAuditFailure, 500, "INTERNAL_ERROR");
        assertThat(findResponseCookie(passwordAuditFailure, SESSION_COOKIE)).isEmpty();
        assertThat(springSessionCount()).isZero();

        audit.reset();
        Cookie pending = requireSessionCookie(
                performPassword(csrf, null, ADMIN_USERNAME, ADMIN_PASSWORD, nextRemote(), null),
                admin);
        String stable = requireStablePrimaryId(pending.getValue());
        admin.trackPrimary(stable);
        audit.delegateFor("AUTH_LOGIN_SUCCEEDED", DelegateMode.DELEGATE_THEN_FAIL);
        MvcResult successAuditFailure = performSecondFactor(
                csrf, pending, "RECOVERY_CODE", RECOVERY_CODE, nextRemote(), null);
        assertProblem(successAuditFailure, 500, "INTERNAL_ERROR");
        assertClearSiteData(successAuditFailure);
        assertLoginBusinessRowsRolledBack(admin, stable);
        assertThat(realAuditCount("AUTH_LOGIN_SUCCEEDED", admin.adminId())).isZero();
    }

    @Test
    void realSpringSessionContextSaveFailureRollsBackBusinessRowsAndInvalidatesFailClosed()
            throws Exception {
        Fixture admin = fixture();
        CsrfExchange csrf = csrf();
        Cookie pending = requireSessionCookie(
                performPassword(csrf, null, ADMIN_USERNAME, ADMIN_PASSWORD, nextRemote(), null),
                admin);
        String stable = requireStablePrimaryId(pending.getValue());
        admin.trackPrimary(stable);
        audit.delegateFor("AUTH_LOGIN_SUCCEEDED", DelegateMode.DELEGATE);

        try (SessionAttributeFailure ignored = SessionAttributeFailure.install(stable)) {
            MvcResult failed = performSecondFactor(
                    csrf, pending, "RECOVERY_CODE", RECOVERY_CODE, nextRemote(), null);
            assertProblem(failed, 500, "INTERNAL_ERROR");
            assertClearSiteData(failed);
        }

        assertLoginBusinessRowsRolledBack(admin, stable);
        assertThat(realAuditCount("AUTH_LOGIN_SUCCEEDED", admin.adminId())).isZero();
        assertProblem(mvc.perform(get(ME_PATH).cookie(pending)).andReturn(),
                401, "AUTHENTICATION_REQUIRED");
    }

    @Test
    void authenticatedContextWithoutActiveMetadataIsRejectedAndInvalidatedOnFirstUse()
            throws Exception {
        Fixture admin = fixture();
        String stable = UUID.randomUUID().toString();
        String publicId = UUID.randomUUID().toString();
        admin.trackPrimary(stable);
        insertSpringSession(stable, publicId, ADMIN_USERNAME);

        AdminPrincipal principal = new AdminPrincipal(admin.adminId(), ADMIN_USERNAME);
        org.springframework.security.authentication.UsernamePasswordAuthenticationToken authentication =
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        principal, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        insertSessionAttribute(stable, "SPRING_SECURITY_CONTEXT", serialize(context));

        MvcResult rejected = mvc.perform(get(ME_PATH).cookie(sessionCookie(publicId))).andReturn();
        assertProblem(rejected, 401, "AUTHENTICATION_REQUIRED");
        assertClearSiteData(rejected);
        assertThat(springRowCount(stable)).isZero();
    }

    @Test
    void outerLeaseSpansFinalSpringSaveSoAnOldCookieCannotRestoreTheWinnerSnapshot()
            throws Exception {
        Fixture admin = fixture();
        CsrfExchange csrf = csrf();
        Cookie pending = requireSessionCookie(
                performPassword(csrf, null, ADMIN_USERNAME, ADMIN_PASSWORD, nextRemote(), null),
                admin);
        String oldPublicId = pending.getValue();
        String stable = requireStablePrimaryId(oldPublicId);
        admin.trackPrimary(stable);
        ProbeGate gate = probe.arm();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<MvcResult> winner = null;
        Future<MvcResult> loser = null;
        try {
            winner = executor.submit(() -> performSecondFactor(
                    csrf, pending, "TOTP", currentTotp(), nextRemote(), "winner"));
            await(gate.winnerBeforeSessionCommit(),
                    "winner did not reach the pre-Spring-Session-commit probe");

            SpringRow immediate = springRow(stable);
            assertThat(immediate.publicId()).isNotEqualTo(oldPublicId);
            assertThat(immediate.principalName()).isEqualTo(ADMIN_USERNAME);
            assertThat(securityContextAttributeCount(stable)).isOne();

            CountDownLatch loserTaskStarted = new CountDownLatch(1);
            loser = executor.submit(() -> {
                loserTaskStarted.countDown();
                return mvc.perform(get(ME_PATH)
                                .cookie(pending)
                                .header(ConcurrencyProbeFilter.PROBE_HEADER, "loser"))
                        .andReturn();
            });
            await(loserTaskStarted, "loser task did not start");
            assertThat(gate.loserEnteredInnerChain().await(300, java.util.concurrent.TimeUnit.MILLISECONDS))
                    .as("old-cookie waiter must remain outside Spring Session until winner final save")
                    .isFalse();

            gate.allowWinnerToReturnToSpringSession();
            await(gate.loserEnteredInnerChain(), "loser never entered after winner final save");

            MvcResult winnerResult = winner.get(10, SECONDS);
            MvcResult loserResult = loser.get(10, SECONDS);
            assertThat(winnerResult.getResponse().getStatus()).isEqualTo(200);
            Cookie winnerCookie = requireSessionCookie(winnerResult, admin);
            assertThat(winnerCookie.getValue()).isEqualTo(immediate.publicId());
            assertProblem(loserResult, 401, "AUTHENTICATION_REQUIRED");
            assertThat(findPrimaryId(oldPublicId)).isEmpty();
            assertThat(springRow(stable).publicId()).isEqualTo(winnerCookie.getValue());
            assertThat(securityContextAttributeCount(stable)).isOne();
        } finally {
            gate.allowWinnerToReturnToSpringSession();
            if (winner != null && !winner.isDone()) {
                winner.cancel(true);
            }
            if (loser != null && !loser.isDone()) {
                loser.cancel(true);
            }
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, SECONDS)).isTrue();
            probe.reset();
        }
    }

    private Fixture fixture() {
        if (fixture == null) {
            fixture = new Fixture();
        }
        return fixture;
    }

    private CsrfExchange csrf() throws Exception {
        MvcResult source = mvc.perform(get(CSRF_PATH)).andReturn();
        assertThat(source.getResponse().getStatus()).isEqualTo(200);
        assertThat(source.getResponse().getContentType())
                .startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(source.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        JsonNode response = body(source);
        Cookie cookie = findResponseCookie(source, XSRF_COOKIE)
                .orElseThrow(() -> new AssertionError("CSRF response did not set its cookie"));
        String token = response.path("token").asText();
        assertThat(token).isNotBlank();
        return new CsrfExchange(
                source,
                response.path("headerName").asText(),
                response.path("parameterName").asText(),
                token,
                cookie);
    }

    private MvcResult performPassword(
            CsrfExchange csrf,
            Cookie session,
            String username,
            String password,
            String remote,
            String marker) throws Exception {
        MockHttpServletRequestBuilder request = post(PASSWORD_PATH)
                .with(remote(remote))
                .contentType(MediaType.APPLICATION_JSON)
                .content(passwordJson(username, password));
        if (session != null) {
            request.cookie(session);
        }
        if (marker != null) {
            request.header(ConcurrencyProbeFilter.PROBE_HEADER, marker);
        }
        return mvc.perform(withCsrf(request, csrf)).andReturn();
    }

    private MvcResult performSecondFactor(
            CsrfExchange csrf,
            Cookie session,
            String method,
            String code,
            String remote,
            String marker) throws Exception {
        MockHttpServletRequestBuilder request = post(SECOND_FACTOR_PATH)
                .cookie(session)
                .with(remote(remote))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("method", method, "code", code)));
        if (marker != null) {
            request.header(ConcurrencyProbeFilter.PROBE_HEADER, marker);
        }
        return mvc.perform(withCsrf(request, csrf)).andReturn();
    }

    private AuthenticatedSession loginWithTotp(
            Fixture admin, CsrfExchange csrf, String remote) throws Exception {
        MvcResult password = performPassword(
                csrf, null, ADMIN_USERNAME, ADMIN_PASSWORD, remote, null);
        assertThat(password.getResponse().getStatus()).isEqualTo(200);
        Cookie pending = requireSessionCookie(password, admin);
        String stable = requireStablePrimaryId(pending.getValue());
        admin.trackPrimary(stable);
        MvcResult secondFactor = performSecondFactor(
                csrf, pending, "TOTP", currentTotp(), remote, null);
        assertThat(secondFactor.getResponse().getStatus()).isEqualTo(200);
        Cookie active = requireSessionCookie(secondFactor, admin);
        assertThat(requireStablePrimaryId(active.getValue())).isEqualTo(stable);
        return new AuthenticatedSession(active, stable);
    }

    private MockHttpServletRequestBuilder withCsrf(
            MockHttpServletRequestBuilder request, CsrfExchange csrf) {
        return request.cookie(csrf.cookie()).header(csrf.headerName(), csrf.token());
    }

    private String passwordJson(String username, String password) throws Exception {
        return json.writeValueAsString(Map.of("username", username, "password", password));
    }

    private String currentTotp() throws CodeGenerationException {
        return totpCodes.generate(TOTP_SECRET, clock.instant().getEpochSecond() / 30);
    }

    private Cookie requireSessionCookie(MvcResult result, Fixture owner) {
        Cookie cookie = findResponseCookie(result, SESSION_COOKIE)
                .orElseThrow(() -> new AssertionError("response did not set the session cookie"));
        assertThat(cookie.getDomain()).isNull();
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSecure()).isFalse();
        assertThat(cookie.getMaxAge()).isEqualTo(-1);
        assertThat(cookie.getAttribute("SameSite")).isEqualToIgnoringCase("Strict");
        assertCanonicalUuid(cookie.getValue());
        owner.trackCookie(cookie);
        return cookie;
    }

    private static Optional<Cookie> findResponseCookie(MvcResult result, String name) {
        Cookie direct = result.getResponse().getCookie(name);
        if (direct != null) {
            return Optional.of(direct);
        }
        for (String header : result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)) {
            String[] parts = header.split(";");
            int separator = parts[0].indexOf('=');
            if (separator < 1 || !parts[0].substring(0, separator).trim().equals(name)) {
                continue;
            }
            String value = unquote(parts[0].substring(separator + 1).trim());
            Cookie parsed = new Cookie(name, value);
            for (int index = 1; index < parts.length; index++) {
                String attribute = parts[index].trim();
                int equals = attribute.indexOf('=');
                String attributeName = (equals < 0 ? attribute : attribute.substring(0, equals))
                        .trim()
                        .toLowerCase(Locale.ROOT);
                String attributeValue = equals < 0
                        ? ""
                        : unquote(attribute.substring(equals + 1).trim());
                switch (attributeName) {
                    case "path" -> parsed.setPath(attributeValue);
                    case "domain" -> parsed.setDomain(attributeValue);
                    case "max-age" -> parsed.setMaxAge(Integer.parseInt(attributeValue));
                    case "secure" -> parsed.setSecure(true);
                    case "httponly" -> parsed.setHttpOnly(true);
                    case "samesite" -> parsed.setAttribute("SameSite", attributeValue);
                    default -> {
                        // Expires and extensions are not needed by these protocol assertions.
                    }
                }
            }
            return Optional.of(parsed);
        }
        return Optional.empty();
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static Cookie sessionCookie(String publicId) {
        Cookie cookie = new Cookie(SESSION_COOKIE, publicId);
        cookie.setPath("/");
        return cookie;
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor remote(
            String address) {
        return request -> {
            request.setRemoteAddr(address);
            request.addHeader(HttpHeaders.USER_AGENT, "Task11-Integration-Test/1.0");
            return request;
        };
    }

    private static String nextRemote() {
        int sequence = REMOTE_SEQUENCE.incrementAndGet();
        int third = 1 + Math.floorMod(sequence / 250, 250);
        int fourth = 1 + Math.floorMod(sequence, 250);
        return "198.18." + third + "." + fourth;
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    private JsonNode normalizedProblem(MvcResult result) throws Exception {
        assertProblem(result, 401, "AUTHENTICATION_FAILED");
        ObjectNode normalized = (ObjectNode) body(result).deepCopy();
        normalized.remove("traceId");
        return normalized;
    }

    private void assertProblem(MvcResult result, int status, String code) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertThat(MediaType.parseMediaType(result.getResponse().getContentType())
                .isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        String responseTrace = result.getResponse().getHeader(TRACE_HEADER);
        JsonNode problem = body(result);
        assertThat(responseTrace).matches("[0-9a-f]{32}");
        assertThat(problem.path("traceId").asText()).isEqualTo(responseTrace);
        assertThat(problem.path("status").asInt()).isEqualTo(status);
        assertThat(problem.path("code").asText()).isEqualTo(code);
        assertThat(problem.path("fieldErrors").isObject()).isTrue();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(ADMIN_PASSWORD, WRONG_PASSWORD, TOTP_SECRET, RECOVERY_CODE);
    }

    private void assertPositiveRetryAfter(MvcResult result) throws Exception {
        String header = result.getResponse().getHeader(HttpHeaders.RETRY_AFTER);
        assertThat(header).matches("[1-9][0-9]{0,9}");
    }

    private void assertMatchingRetryAfter(MvcResult result) throws Exception {
        assertPositiveRetryAfter(result);
        String header = result.getResponse().getHeader(HttpHeaders.RETRY_AFTER);
        assertThat(body(result).path("fieldErrors").path("retryAfterSeconds").asText())
                .isEqualTo(header);
    }

    private void assertNoStaleLogin(Fixture admin) {
        assertThat(metadataCount(admin.adminId())).isZero();
        assertThat(adminLastLogin(admin.adminId())).isEmpty();
    }

    private static void assertClearSiteData(MvcResult result) {
        assertThat(result.getResponse().getHeader("Clear-Site-Data")).contains("\"cookies\"");
    }

    private static void assertCanonicalUuid(String value) {
        assertThat(value).isEqualTo(UUID.fromString(value).toString());
    }

    private static Set<String> csv(String value) {
        assertThat(value).isNotBlank();
        Set<String> values = new LinkedHashSet<>();
        for (String item : value.split(",")) {
            values.add(item.trim().toLowerCase(Locale.ROOT));
        }
        return values;
    }

    private static int indexOf(List<jakarta.servlet.Filter> filters, Class<?> type) {
        for (int index = 0; index < filters.size(); index++) {
            if (type.isInstance(filters.get(index))) {
                return index;
            }
        }
        return -1;
    }

    private ConfigurableApplicationContext startSecondaryServletContext(boolean corsEnabled) {
        SpringApplication application = new SpringApplication(PortfolioApplication.class);
        application.setWebApplicationType(WebApplicationType.SERVLET);
        application.setRegisterShutdownHook(false);
        application.setDefaultProperties(secondaryContextProperties(corsEnabled));
        return application.run();
    }

    private Map<String, Object> secondaryContextProperties(boolean corsEnabled) {
        String separator = POSTGRES.getJdbcUrl().contains("?") ? "&" : "?";
        String databaseUrl = POSTGRES.getJdbcUrl() + separator + "currentSchema=portfolio";
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("PORTFOLIO_DB_URL", databaseUrl);
        properties.put("PORTFOLIO_DB_MIGRATOR_URL", databaseUrl);
        properties.put("PORTFOLIO_DB_RUNTIME_USER", "test_runtime");
        properties.put("PORTFOLIO_DB_RUNTIME_PASSWORD", "runtime_test_password");
        properties.put("PORTFOLIO_DB_MIGRATOR_USER", "test_migrator");
        properties.put("PORTFOLIO_DB_MIGRATOR_PASSWORD", "migrator_test_password");
        properties.put("PORTFOLIO_TOTP_ACTIVE_KEY_VERSION", "1");
        properties.put(
                "PORTFOLIO_TOTP_KEY_RING",
                "1=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");
        properties.put("PORTFOLIO_SESSION_COOKIE_SECURE", "false");
        properties.put("PORTFOLIO_ALLOW_DEVELOPMENT_CORS", Boolean.toString(corsEnabled));
        properties.put("PORTFOLIO_ADMIN_DEV_ORIGIN", DEVELOPMENT_ORIGIN);
        properties.put("spring.main.banner-mode", "off");
        properties.put("server.port", "0");
        properties.put("spring.datasource.url", databaseUrl);
        properties.put("spring.datasource.username", "test_runtime");
        properties.put("spring.datasource.password", "runtime_test_password");
        properties.put("spring.flyway.url", databaseUrl);
        properties.put("spring.flyway.user", "test_migrator");
        properties.put("spring.flyway.password", "migrator_test_password");
        properties.put("spring.session.jdbc.cleanup-cron", "-");
        properties.put("portfolio.security.session.cleanup-interval", "PT24H");
        properties.put("portfolio.security.totp.active-key-version", "1");
        properties.put(
                "portfolio.security.totp.key-ring",
                "1=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");
        properties.put("server.servlet.session.cookie.secure", "false");
        properties.put("portfolio.web.allow-development-cors", Boolean.toString(corsEnabled));
        properties.put("portfolio.web.development-origin", DEVELOPMENT_ORIGIN);
        properties.put(
                "portfolio.media.local-staging.active-capacity",
                environment.getRequiredProperty(
                        "portfolio.media.local-staging.active-capacity"));
        properties.put(
                "portfolio.media.local-staging.scan-entry-ceiling",
                environment.getRequiredProperty(
                        "portfolio.media.local-staging.scan-entry-ceiling"));
        properties.put(
                "portfolio.media.local-staging.reserved-headroom",
                environment.getRequiredProperty(
                        "portfolio.media.local-staging.reserved-headroom"));
        return Map.copyOf(properties);
    }

    private long springSessionCount() {
        return jdbc.sql("select count(*) from portfolio.spring_session")
                .query(Long.class)
                .single();
    }

    private Optional<String> findPrimaryId(String publicId) {
        return jdbc.sql("""
                        select btrim(primary_id) from portfolio.spring_session
                        where session_id=:sessionId
                        """)
                .param("sessionId", publicId)
                .query(String.class)
                .optional();
    }

    private String requireStablePrimaryId(String publicId) {
        return findPrimaryId(publicId)
                .orElseThrow(() -> new AssertionError("public session id has no JDBC row"));
    }

    private String sessionAttributeBytes(String primaryId) {
        ByteArrayOutputStream combined = new ByteArrayOutputStream();
        List<byte[]> values = jdbc.sql("""
                        select attribute_bytes from portfolio.spring_session_attributes
                        where session_primary_id=:primaryId order by attribute_name
                        """)
                .param("primaryId", primaryId)
                .query((rs, rowNumber) -> rs.getBytes("attribute_bytes"))
                .list();
        for (byte[] value : values) {
            combined.writeBytes(value);
        }
        return combined.toString(StandardCharsets.ISO_8859_1);
    }

    private byte[] sessionAttribute(String primaryId, String name) {
        return jdbc.sql("""
                        select attribute_bytes from portfolio.spring_session_attributes
                        where session_primary_id=:primaryId and attribute_name=:name
                        """)
                .param("primaryId", primaryId)
                .param("name", name)
                .query((rs, rowNumber) -> rs.getBytes("attribute_bytes"))
                .single();
    }

    private PendingSecondFactor pendingAttribute(String primaryId)
            throws IOException, ClassNotFoundException {
        Object value = deserialize(sessionAttribute(primaryId, PendingSecondFactor.SESSION_KEY));
        assertThat(value).isInstanceOf(PendingSecondFactor.class);
        return (PendingSecondFactor) value;
    }

    private void replaceSessionAttribute(String primaryId, String name, byte[] value) {
        int updated = jdbc.sql("""
                        update portfolio.spring_session_attributes set attribute_bytes=:bytes
                        where session_primary_id=:primaryId and attribute_name=:name
                        """)
                .param("bytes", value)
                .param("primaryId", primaryId)
                .param("name", name)
                .update();
        assertThat(updated).isOne();
    }

    private Cookie clonePendingSession(Fixture owner, Cookie source) {
        String sourcePrimary = requireStablePrimaryId(source.getValue());
        byte[] pending = sessionAttribute(sourcePrimary, PendingSecondFactor.SESSION_KEY);
        String clonePrimary = UUID.randomUUID().toString();
        String clonePublic = UUID.randomUUID().toString();
        owner.trackPrimary(clonePrimary);
        insertSpringSession(clonePrimary, clonePublic, null);
        insertSessionAttribute(clonePrimary, PendingSecondFactor.SESSION_KEY, pending);
        return sessionCookie(clonePublic);
    }

    private SpringRow springRow(String primaryId) {
        return jdbc.sql("""
                        select btrim(session_id) session_id, principal_name
                        from portfolio.spring_session where primary_id=:primaryId
                        """)
                .param("primaryId", primaryId)
                .query((rs, rowNumber) -> new SpringRow(
                        rs.getString("session_id").trim(), rs.getString("principal_name")))
                .single();
    }

    private long springRowCount(String primaryId) {
        return jdbc.sql("""
                        select count(*) from portfolio.spring_session where primary_id=:primaryId
                        """)
                .param("primaryId", primaryId)
                .query(Long.class)
                .single();
    }

    private long securityContextAttributeCount(String primaryId) {
        return jdbc.sql("""
                        select count(*) from portfolio.spring_session_attributes
                        where session_primary_id=:primaryId
                          and attribute_name='SPRING_SECURITY_CONTEXT'
                        """)
                .param("primaryId", primaryId)
                .query(Long.class)
                .single();
    }

    private long metadataCount(UUID adminId) {
        return jdbc.sql("""
                        select count(*) from portfolio.admin_session_metadata where admin_id=:adminId
                        """)
                .param("adminId", adminId)
                .query(Long.class)
                .single();
    }

    private MetadataRow activeMetadata(UUID adminId, String primaryId) {
        return jdbc.sql("""
                        select id, status, revocation_reason
                        from portfolio.admin_session_metadata
                        where admin_id=:adminId and session_primary_id=:primaryId
                        """)
                .param("adminId", adminId)
                .param("primaryId", primaryId)
                .query((rs, rowNumber) -> new MetadataRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("status"),
                        rs.getString("revocation_reason")))
                .single();
    }

    private MetadataRow metadata(UUID metadataId) {
        return jdbc.sql("""
                        select id, status, revocation_reason
                        from portfolio.admin_session_metadata where id=:id
                        """)
                .param("id", metadataId)
                .query((rs, rowNumber) -> new MetadataRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("status"),
                        rs.getString("revocation_reason")))
                .single();
    }

    private UUID metadataIdForPrimary(String primaryId) {
        return jdbc.sql("""
                        select id from portfolio.admin_session_metadata
                        where session_primary_id=:primaryId
                        """)
                .param("primaryId", primaryId)
                .query(UUID.class)
                .single();
    }

    private Optional<Instant> adminLastLogin(UUID adminId) {
        return jdbc.sql("select last_login_at from portfolio.admin_user where id=:id")
                .param("id", adminId)
                .query(OffsetDateTime.class)
                .optional()
                .map(OffsetDateTime::toInstant);
    }

    private long usedRecoveryCount(UUID adminId) {
        return jdbc.sql("""
                        select count(*) from portfolio.totp_recovery_code
                        where admin_id=:adminId and used_at is not null
                        """)
                .param("adminId", adminId)
                .query(Long.class)
                .single();
    }

    private long realAuditCount(String action, UUID adminId) {
        return jdbc.sql("""
                        select count(*) from portfolio.audit_log
                        where action=:action and target_id=:target
                        """)
                .param("action", action)
                .param("target", adminId.toString())
                .query(Long.class)
                .single();
    }

    private void assertLoginBusinessRowsRolledBack(Fixture admin, String stablePrimaryId) {
        assertThat(usedRecoveryCount(admin.adminId())).isZero();
        assertThat(adminLastLogin(admin.adminId())).isEmpty();
        assertThat(metadataCount(admin.adminId())).isZero();
        assertThat(securityContextAttributeCount(stablePrimaryId)).isZero();
        assertThat(springRowCount(stablePrimaryId)).isZero();
    }

    private void insertSpringSession(String primaryId, String publicId, String principalName) {
        // JdbcIndexedSessionRepository uses the system clock when deciding whether a row is
        // expired. Keep this synthetic row on that same clock; the injected business clock is
        // intentionally independent and may be behind wall time.
        long now = System.currentTimeMillis();
        int inserted = jdbc.sql("""
                        insert into portfolio.spring_session
                            (primary_id, session_id, creation_time, last_access_time,
                             max_inactive_interval, expiry_time, principal_name)
                        values (:primaryId, :sessionId, :now, :now, 1800, :expiry, :principal)
                        """)
                .param("primaryId", primaryId)
                .param("sessionId", publicId)
                .param("now", now)
                .param("expiry", now + Duration.ofMinutes(30).toMillis())
                .param("principal", principalName, Types.VARCHAR)
                .update();
        assertThat(inserted).isOne();
    }

    private void insertSessionAttribute(String primaryId, String name, byte[] value) {
        int inserted = jdbc.sql("""
                        insert into portfolio.spring_session_attributes
                            (session_primary_id, attribute_name, attribute_bytes)
                        values (:primaryId, :name, :bytes)
                        """)
                .param("primaryId", primaryId)
                .param("name", name)
                .param("bytes", value)
                .update();
        assertThat(inserted).isOne();
    }

    private static byte[] serialize(Object value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(value);
        }
        return bytes.toByteArray();
    }

    private static Object deserialize(byte[] value) throws IOException, ClassNotFoundException {
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(value))) {
            return input.readObject();
        }
    }

    private static void await(CountDownLatch latch, String message) throws InterruptedException {
        if (!latch.await(10, SECONDS)) {
            throw new AssertionError(message);
        }
    }

    private final class Fixture implements AutoCloseable {
        private final UUID adminId = UUID.randomUUID();
        private final Set<String> primaryIds = new CopyOnWriteArraySet<>();
        private final Set<UUID> metadataIds = new CopyOnWriteArraySet<>();
        private final AtomicBoolean closed = new AtomicBoolean();

        private Fixture() {
            Instant now = clock.instant();
            AdminUser admin = new AdminUser(
                    adminId,
                    ADMIN_USERNAME,
                    passwordEncoder.encode(ADMIN_PASSWORD),
                    AdminStatus.ACTIVE,
                    totpCrypto.encrypt(adminId, TOTP_SECRET),
                    null,
                    0,
                    now,
                    now);
            admins.insert(admin);
            recoveryCodes.replace(
                    adminId, recoveryCodeService.hashAll(List.of(RECOVERY_CODE)));
        }

        UUID adminId() {
            return adminId;
        }

        void trackCookie(Cookie cookie) {
            findPrimaryId(cookie.getValue()).ifPresent(primaryIds::add);
        }

        void trackPrimary(String primaryId) {
            assertCanonicalUuid(primaryId);
            primaryIds.add(primaryId);
        }

        void trackMetadata(UUID metadataId) {
            metadataIds.add(metadataId);
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            JdbcClient migrator = migratorJdbc();
            List<TrackedMetadata> children = migrator.sql("""
                            select id, btrim(session_primary_id) session_primary_id
                            from portfolio.admin_session_metadata where admin_id=:adminId
                            """)
                    .param("adminId", adminId)
                    .query((rs, rowNumber) -> new TrackedMetadata(
                            rs.getObject("id", UUID.class),
                            rs.getString("session_primary_id")))
                    .list();
            for (TrackedMetadata child : children) {
                metadataIds.add(child.id());
                if (child.primaryId() != null) {
                    primaryIds.add(child.primaryId().trim());
                }
            }

            for (UUID metadataId : List.copyOf(metadataIds)) {
                migrator.sql("delete from portfolio.admin_session_metadata where id=:id")
                        .param("id", metadataId)
                        .update();
            }
            migrator.sql("delete from portfolio.totp_recovery_code where admin_id=:adminId")
                    .param("adminId", adminId)
                    .update();
            for (String primaryId : List.copyOf(primaryIds)) {
                migrator.sql("delete from portfolio.spring_session where primary_id=:primaryId")
                        .param("primaryId", primaryId)
                        .update();
            }
            migrator.sql("delete from portfolio.admin_user where id=:adminId")
                    .param("adminId", adminId)
                    .update();
        }
    }

    private static final class SessionAttributeFailure implements AutoCloseable {
        private final String trigger;
        private final String function;

        private SessionAttributeFailure(String trigger, String function) {
            this.trigger = trigger;
            this.function = function;
        }

        static SessionAttributeFailure install(String stablePrimaryId) {
            assertCanonicalUuid(stablePrimaryId);
            String suffix = UUID.randomUUID().toString().replace("-", "");
            String trigger = "test_fail_session_attribute_" + suffix;
            String function = "test_fail_session_attribute_fn_" + suffix;
            JdbcClient migrator = migratorJdbc();
            migrator.sql(("""
                            create function portfolio.%s() returns trigger
                            language plpgsql as $$
                            begin
                                if new.attribute_name = 'SPRING_SECURITY_CONTEXT'
                                   and btrim(new.session_primary_id) = '%s' then
                                    raise exception 'synthetic spring session save failure'
                                        using errcode = '55000';
                                end if;
                                return new;
                            end;
                            $$
                            """).formatted(function, stablePrimaryId))
                    .update();
            migrator.sql(("""
                            create trigger %s before insert or update
                            on portfolio.spring_session_attributes
                            for each row execute function portfolio.%s()
                            """).formatted(trigger, function))
                    .update();
            return new SessionAttributeFailure(trigger, function);
        }

        @Override
        public void close() {
            JdbcClient migrator = migratorJdbc();
            migrator.sql("drop trigger if exists " + trigger
                            + " on portfolio.spring_session_attributes")
                    .update();
            migrator.sql("drop function if exists portfolio." + function + "()")
                    .update();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FlowTestConfiguration {
        @Bean
        @Primary
        MonotonicTestClock monotonicTestClock() {
            return new MonotonicTestClock(INITIAL_TIME);
        }

        @Bean
        @Primary
        RecordingAuditService recordingAuditService(JdbcAuditService delegate) {
            return new RecordingAuditService(delegate);
        }

        @Bean
        @Primary
        FaultInjectingAdminUserRepository faultInjectingAdminUserRepository(JdbcClient jdbc) {
            return new FaultInjectingAdminUserRepository(jdbc);
        }

        @Bean
        @Primary
        FaultInjectingAdminSessionRepository faultInjectingAdminSessionRepository(
                JdbcClient jdbc) {
            return new FaultInjectingAdminSessionRepository(jdbc);
        }

        @Bean
        ConcurrencyProbeFilter concurrencyProbeFilter() {
            return new ConcurrencyProbeFilter();
        }

        @Bean
        FilterRegistrationBean<ConcurrencyProbeFilter> concurrencyProbeRegistration(
                ConcurrencyProbeFilter filter) {
            FilterRegistrationBean<ConcurrencyProbeFilter> registration =
                    new FilterRegistrationBean<>(filter);
            registration.setName("task11ConcurrencyProbeFilter");
            registration.setOrder(SessionRepositoryFilter.DEFAULT_ORDER + 1);
            registration.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST));
            registration.setAsyncSupported(false);
            registration.addUrlPatterns("/*");
            return registration;
        }
    }

    static final class MonotonicTestClock extends Clock {
        private final AtomicLong epochMillis;

        MonotonicTestClock(Instant initial) {
            epochMillis = new AtomicLong(initial.toEpochMilli());
        }

        void advance(Duration duration) {
            if (duration == null || duration.isNegative() || duration.isZero()) {
                throw new IllegalArgumentException("test clock advances must be positive");
            }
            epochMillis.addAndGet(duration.toMillis());
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (ZoneOffset.UTC.equals(zone)) {
                return this;
            }
            return Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(epochMillis.get());
        }
    }

    static final class RecordingAuditService implements AuditService {
        private final JdbcAuditService realAudit;
        private final CopyOnWriteArrayList<AuditCommand> commands = new CopyOnWriteArrayList<>();
        private final AtomicReference<DelegateRule> rule = new AtomicReference<>();

        RecordingAuditService(JdbcAuditService realAudit) {
            this.realAudit = realAudit;
        }

        @Override
        public void record(AuditCommand command) {
            commands.add(java.util.Objects.requireNonNull(command, "command"));
            DelegateRule active = rule.get();
            if (active == null || !active.action().equals(command.action())) {
                return;
            }
            if (active.mode() == DelegateMode.FAIL_BEFORE_DELEGATE) {
                throw new SyntheticAuditFailure();
            }
            realAudit.record(command);
            if (active.mode() == DelegateMode.DELEGATE_THEN_FAIL) {
                throw new SyntheticAuditFailure();
            }
        }

        void delegateFor(String action, DelegateMode mode) {
            rule.set(new DelegateRule(action, mode));
        }

        List<AuditCommand> commands() {
            return List.copyOf(commands);
        }

        void reset() {
            commands.clear();
            rule.set(null);
        }
    }

    static class FaultInjectingAdminUserRepository extends AdminUserRepository {
        private final AtomicReference<RepositoryGate> lockedLookup = new AtomicReference<>();
        private final AtomicReference<RepositoryGate> usernameLookup = new AtomicReference<>();

        FaultInjectingAdminUserRepository(JdbcClient jdbc) {
            super(jdbc);
        }

        RepositoryGate pauseNextLockedLookup() {
            return arm(lockedLookup);
        }

        RepositoryGate pauseNextUsernameLookup() {
            return arm(usernameLookup);
        }

        @Override
        @Transactional(propagation = Propagation.MANDATORY)
        public Optional<AdminUser> findByIdForUpdate(UUID id) {
            pauseIfArmed(lockedLookup, "locked admin lookup release timed out");
            return super.findByIdForUpdate(id);
        }

        @Override
        public Optional<AdminUser> findByUsername(String username) {
            pauseIfArmed(usernameLookup, "username lookup release timed out");
            return super.findByUsername(username);
        }

        void reset() {
            release(lockedLookup.getAndSet(null));
            release(usernameLookup.getAndSet(null));
        }

        private static RepositoryGate arm(AtomicReference<RepositoryGate> target) {
            RepositoryGate gate = new RepositoryGate();
            if (!target.compareAndSet(null, gate)) {
                throw new IllegalStateException("repository probe was already armed");
            }
            return gate;
        }

        private static void pauseIfArmed(
                AtomicReference<RepositoryGate> target, String timeoutMessage) {
            RepositoryGate gate = target.getAndSet(null);
            if (gate == null) {
                return;
            }
            gate.entered().countDown();
            try {
                if (!gate.released().await(10, SECONDS)) {
                    throw new IllegalStateException(timeoutMessage);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("repository probe was interrupted");
            }
        }

        private static void release(RepositoryGate gate) {
            if (gate != null) {
                gate.release();
            }
        }
    }

    static class FaultInjectingAdminSessionRepository extends AdminSessionRepository {
        private final AtomicBoolean failActiveLookup = new AtomicBoolean();

        FaultInjectingAdminSessionRepository(JdbcClient jdbc) {
            super(jdbc);
        }

        @Override
        public Optional<SessionRow> findByPublicSessionId(String publicSessionId) {
            if (failActiveLookup.get()) {
                throw new IllegalStateException("synthetic repository secret");
            }
            return super.findByPublicSessionId(publicSessionId);
        }

        void failActiveLookup() {
            failActiveLookup.set(true);
        }

        void reset() {
            failActiveLookup.set(false);
        }
    }

    static final class ConcurrencyProbeFilter extends OncePerRequestFilter implements Ordered {
        static final String PROBE_HEADER = "X-Test-Session-Probe";

        private final AtomicReference<ProbeGate> gate = new AtomicReference<>();

        ProbeGate arm() {
            reset();
            ProbeGate armed = new ProbeGate();
            if (!gate.compareAndSet(null, armed)) {
                throw new IllegalStateException("probe was already armed");
            }
            return armed;
        }

        void reset() {
            ProbeGate previous = gate.getAndSet(null);
            if (previous != null) {
                previous.allowWinnerToReturnToSpringSession();
            }
        }

        @Override
        public int getOrder() {
            return SessionRepositoryFilter.DEFAULT_ORDER + 1;
        }

        @Override
        protected void doFilterInternal(
                HttpServletRequest request,
                HttpServletResponse response,
                FilterChain filterChain) throws ServletException, IOException {
            ProbeGate active = gate.get();
            String marker = request.getHeader(PROBE_HEADER);
            if (active != null && "loser".equals(marker)) {
                active.loserEnteredInnerChain().countDown();
            }
            try {
                filterChain.doFilter(request, response);
            } finally {
                if (active != null && "winner".equals(marker)) {
                    active.winnerBeforeSessionCommit().countDown();
                    try {
                        if (!active.winnerMayReturn().await(10, SECONDS)) {
                            throw new ServletException("winner probe release timed out");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new ServletException("winner probe interrupted", interrupted);
                    }
                }
            }
        }
    }

    static final class ProbeGate {
        private final CountDownLatch winnerBeforeSessionCommit = new CountDownLatch(1);
        private final CountDownLatch loserEnteredInnerChain = new CountDownLatch(1);
        private final CountDownLatch winnerMayReturn = new CountDownLatch(1);

        CountDownLatch winnerBeforeSessionCommit() {
            return winnerBeforeSessionCommit;
        }

        CountDownLatch loserEnteredInnerChain() {
            return loserEnteredInnerChain;
        }

        CountDownLatch winnerMayReturn() {
            return winnerMayReturn;
        }

        void allowWinnerToReturnToSpringSession() {
            winnerMayReturn.countDown();
        }
    }

    static final class RepositoryGate {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        CountDownLatch entered() {
            return entered;
        }

        CountDownLatch released() {
            return released;
        }

        void release() {
            released.countDown();
        }
    }

    private enum DelegateMode {
        FAIL_BEFORE_DELEGATE,
        DELEGATE,
        DELEGATE_THEN_FAIL
    }

    private static final class SyntheticAuditFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private record DelegateRule(String action, DelegateMode mode) {
    }

    private record CsrfExchange(
            MvcResult source,
            String headerName,
            String parameterName,
            String token,
            Cookie cookie) {
    }

    private record AuthenticatedSession(Cookie cookie, String stablePrimaryId) {
    }

    private record SpringRow(String publicId, String principalName) {
    }

    private record MetadataRow(UUID id, String status, String reason) {
    }

    private record AuditDatabaseRow(
            UUID actorAdminId, String action, String outcome, String method) {
    }

    private record TrackedMetadata(UUID id, String primaryId) {
    }
}
