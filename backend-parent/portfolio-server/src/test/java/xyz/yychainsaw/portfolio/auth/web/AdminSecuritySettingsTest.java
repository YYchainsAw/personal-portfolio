package xyz.yychainsaw.portfolio.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.time.TimeProvider;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import xyz.yychainsaw.portfolio.auth.RecoveryCodeService;
import xyz.yychainsaw.portfolio.audit.AuditCommand;
import xyz.yychainsaw.portfolio.audit.AuditService;
import xyz.yychainsaw.portfolio.auth.crypto.EncryptedTotpSecret;
import xyz.yychainsaw.portfolio.auth.crypto.PasswordPolicy;
import xyz.yychainsaw.portfolio.auth.crypto.RecoveryCodeGenerator;
import xyz.yychainsaw.portfolio.auth.crypto.TotpService;
import xyz.yychainsaw.portfolio.auth.model.AdminStatus;
import xyz.yychainsaw.portfolio.auth.model.AdminUser;
import xyz.yychainsaw.portfolio.auth.persistence.AdminUserRepository;
import xyz.yychainsaw.portfolio.auth.persistence.RecoveryCodeRepository;
import xyz.yychainsaw.portfolio.auth.session.AdminSessionCleanupJob;
import xyz.yychainsaw.portfolio.auth.session.AdminSessionRepository;
import xyz.yychainsaw.portfolio.auth.session.AdminSessionService;
import xyz.yychainsaw.portfolio.auth.session.SessionProperties;
import xyz.yychainsaw.portfolio.auth.session.TrustedClientAddressResolver;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.common.ratelimit.RateLimitProperties;
import xyz.yychainsaw.portfolio.common.ratelimit.RateLimiter;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "portfolio.security.session.cleanup-interval=PT24H")
@Isolated
class AdminSecuritySettingsTest extends PostgresIntegrationTestBase {
    private static final Instant ISSUED_AT = Instant.parse("2026-07-16T08:09:10Z");
    private static final String CSRF_PATH = "/api/admin/auth/csrf";
    private static final String PASSWORD_LOGIN_PATH = "/api/admin/auth/password";
    private static final String SECOND_FACTOR_PATH = "/api/admin/auth/second-factor";
    private static final String ME_PATH = "/api/admin/auth/me";
    private static final String PASSWORD_PATH = "/api/admin/security/password";
    private static final String ENROLLMENT_PATH = "/api/admin/security/totp/enrollment";
    private static final String CONFIRM_PATH = "/api/admin/security/totp/confirm";
    private static final String REGENERATE_PATH =
            "/api/admin/security/recovery-codes/regenerate";
    private static final String SESSION_COOKIE = "PORTFOLIO_SESSION";
    private static final String XSRF_COOKIE = "XSRF-TOKEN";
    private static final String ADMIN_USERNAME = "SettingsAdmin";
    private static final String ADMIN_PASSWORD = "Correct-Horse-Battery-47!";
    private static final String NEW_PASSWORD = "Changed-Horse-Battery-58!";
    private static final String OLD_RECOVERY_CODE = "ABCD-EFGH-JKLM";
    private static final AtomicInteger REMOTE_SEQUENCE = new AtomicInteger();

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoSpyBean AdminUserRepository admins;
    @MockitoSpyBean RecoveryCodeRepository recoveryCodes;
    @Autowired RecoveryCodeGenerator recoveryGenerator;
    @MockitoSpyBean RecoveryCodeService recoveryCodeService;
    @MockitoSpyBean PasswordEncoder passwords;
    @MockitoSpyBean AdminSessionService sessions;
    @MockitoSpyBean AdminSessionRepository sessionRepository;
    @MockitoSpyBean AuditService audit;
    @Autowired PasswordPolicy passwordPolicy;
    @Autowired TotpService totp;
    @Autowired RateLimiter limiter;
    @Autowired LoginSubjectHasher subjects;
    @Autowired Clock clock;
    @Autowired TransactionTemplate transactions;
    @Autowired SessionProperties sessionProperties;
    @Autowired AdminSessionCleanupJob cleanup;
    @Autowired CodeGenerator totpCodes;
    @Autowired TimeProvider totpTime;
    @Autowired JdbcClient jdbc;
    @Autowired ApplicationContext context;
    @Autowired DataSource dataSource;

    private Fixture fixture;

    @AfterEach
    void cleanFixture() {
        if (fixture != null) {
            fixture.close();
            fixture = null;
        }
    }

    @Test
    void pendingEnrollmentIsAnExactTenMinuteEncryptedSerializableSnapshot() throws Exception {
        UUID enrollmentId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        UUID metadataId = UUID.randomUUID();
        byte[] nonce = new byte[12];
        byte[] ciphertext = new byte[17];
        Arrays.fill(nonce, (byte) 7);
        Arrays.fill(ciphertext, (byte) 9);
        EncryptedTotpSecret encrypted = new EncryptedTotpSecret(1, nonce, ciphertext);

        PendingTotpEnrollment pending = new PendingTotpEnrollment(
                enrollmentId,
                adminId,
                41,
                metadataId,
                encrypted,
                ISSUED_AT,
                ISSUED_AT.plus(Duration.ofMinutes(10)),
                0);

        assertThat(pending).isInstanceOf(Serializable.class);
        assertThat(PendingTotpEnrollment.SESSION_KEY)
                .isEqualTo(PendingTotpEnrollment.class.getName() + ".pending");
        assertThat(pending.encryptedSecret()).isEqualTo(encrypted).isNotSameAs(encrypted);
        assertThat(roundTrip(pending)).isEqualTo(pending);
        assertThat(Arrays.stream(PendingTotpEnrollment.class.getRecordComponents())
                        .map(component -> component.getName().toLowerCase()))
                .noneMatch(name -> name.contains("plaintext")
                        || name.contains("seed")
                        || name.contains("uri"));
        assertThat(pending.toString())
                .contains("enrollmentId=<redacted>", "adminId=<redacted>",
                        "sessionMetadataId=<redacted>", "encryptedSecret=<redacted>")
                .doesNotContain(enrollmentId.toString(), adminId.toString(),
                        metadataId.toString(), Arrays.toString(nonce),
                        Arrays.toString(ciphertext));

        PendingTotpEnrollment fifth = new PendingTotpEnrollment(
                enrollmentId,
                adminId,
                41,
                metadataId,
                encrypted,
                ISSUED_AT,
                ISSUED_AT.plus(Duration.ofMinutes(10)),
                4).failedAgain();
        assertThat(fifth.failures()).isEqualTo(5);
        assertThatIllegalArgumentException().isThrownBy(fifth::failedAgain);
    }

    @Test
    void pendingEnrollmentRejectsEveryInvalidLifetimeVersionAndFailureState() {
        UUID enrollmentId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        UUID metadataId = UUID.randomUUID();
        EncryptedTotpSecret encrypted =
                new EncryptedTotpSecret(1, new byte[12], new byte[17]);

        for (Instant invalidExpiry : List.of(
                ISSUED_AT,
                ISSUED_AT.plus(Duration.ofMinutes(10)).minusNanos(1),
                ISSUED_AT.plus(Duration.ofMinutes(10)).plusNanos(1))) {
            assertThatIllegalArgumentException().isThrownBy(() -> new PendingTotpEnrollment(
                    enrollmentId, adminId, 0, metadataId, encrypted,
                    ISSUED_AT, invalidExpiry, 0));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> new PendingTotpEnrollment(
                enrollmentId, adminId, -1, metadataId, encrypted,
                ISSUED_AT, ISSUED_AT.plus(Duration.ofMinutes(10)), 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new PendingTotpEnrollment(
                enrollmentId, adminId, 0, metadataId, encrypted,
                ISSUED_AT, ISSUED_AT.plus(Duration.ofMinutes(10)), -1));
        assertThatIllegalArgumentException().isThrownBy(() -> new PendingTotpEnrollment(
                enrollmentId, adminId, 0, metadataId, encrypted,
                ISSUED_AT, ISSUED_AT.plus(Duration.ofMinutes(10)), 6));
        assertThatIllegalArgumentException().isThrownBy(() -> new PendingTotpEnrollment(
                enrollmentId, adminId, 0, metadataId, encrypted,
                Instant.MAX.minusSeconds(1), Instant.MAX, 0));
    }

    @Test
    void enrollmentHashUsesTheExistingKeyedDigestWithASeparateDomain() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 11);
        LoginSubjectHasher hasher = new LoginSubjectHasher(
                mock(TrustedClientAddressResolver.class), key);
        UUID id = UUID.fromString("6f3ebf16-5ce2-4a15-a824-5c087bad53af");

        String enrollment = hasher.hashTotpEnrollment(id);

        assertThat(enrollment).matches("[0-9a-f]{64}");
        assertThat(enrollment).isNotEqualTo(hasher.hashSecondFactor(id));
        assertThatThrownBy(() -> hasher.hashTotpEnrollment(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("enrollment id is required");
        assertThat(hasher.toString()).doesNotContain(enrollment, Arrays.toString(key));
    }

    @Test
    void settingsBeansAreServletOnlyAndExposeOnlyTheFourBoundPostRoutes() throws Exception {
        assertServletOnlyService(AdminSecuritySettingsService.class);
        assertThat(AdminSecuritySettingsController.class.getAnnotation(RestController.class))
                .isNotNull();
        assertServletOnly(AdminSecuritySettingsController.class);
        assertThat(AdminSecuritySettingsController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/admin/security");

        assertPostRoute("password", "/password");
        assertPostRoute("enrollment", "/totp/enrollment");
        assertPostRoute("confirm", "/totp/confirm");
        assertPostRoute("regenerate", "/recovery-codes/regenerate");
        assertThat(Arrays.stream(AdminSecuritySettingsController.class.getDeclaredMethods())
                        .filter(method -> method.getAnnotation(PostMapping.class) != null))
                .hasSize(4);
    }

    @Test
    void requestRecordsApplyOnlyTransportCeilingsAndRedactEverySecret() {
        AdminSecuritySettingsController.PasswordChangeRequest password =
                new AdminSecuritySettingsController.PasswordChangeRequest(
                        "current-secret", "123456", "new-secret");
        AdminSecuritySettingsController.ReauthenticationRequest reauthentication =
                new AdminSecuritySettingsController.ReauthenticationRequest(
                        "current-secret", "123456");
        AdminSecuritySettingsController.TotpConfirmRequest confirmation =
                new AdminSecuritySettingsController.TotpConfirmRequest(
                        UUID.randomUUID().toString(), "654321");

        assertThat(new AdminSecuritySettingsController.PasswordChangeRequest(
                null, "", null)).isNotNull();
        assertThat(new AdminSecuritySettingsController.ReauthenticationRequest(
                null, "")).isNotNull();
        assertThat(new AdminSecuritySettingsController.TotpConfirmRequest(
                null, "")).isNotNull();
        assertThatIllegalArgumentException().isThrownBy(() ->
                new AdminSecuritySettingsController.PasswordChangeRequest(
                        "x".repeat(257), "123456", "new-secret"));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new AdminSecuritySettingsController.PasswordChangeRequest(
                        "current-secret", "1".repeat(65), "new-secret"));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new AdminSecuritySettingsController.PasswordChangeRequest(
                        "current-secret", "123456", "x".repeat(257)));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new AdminSecuritySettingsController.ReauthenticationRequest(
                        "x".repeat(257), "123456"));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new AdminSecuritySettingsController.TotpConfirmRequest(
                        "x".repeat(65), "123456"));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new AdminSecuritySettingsController.TotpConfirmRequest(
                        UUID.randomUUID().toString(), "1".repeat(65)));
        assertThat(password.toString()).doesNotContain("current-secret", "123456", "new-secret");
        assertThat(reauthentication.toString()).doesNotContain("current-secret", "123456");
        assertThat(confirmation.toString()).doesNotContain("654321", confirmation.enrollmentId());
    }

    @Test
    void oneTimeDeliveriesArePrivateConstructionNonSerializableAndRedacted() {
        for (Class<?> delivery : List.of(
                AdminSecuritySettingsService.EnrollmentDelivery.class,
                AdminSecuritySettingsService.RecoveryCodesDelivery.class)) {
            assertThat(AutoCloseable.class.isAssignableFrom(delivery)).isTrue();
            assertThat(Serializable.class.isAssignableFrom(delivery)).isFalse();
            assertThat(delivery.getDeclaredConstructors())
                    .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers()));
            assertThat(delivery.getDeclaredFields())
                    .noneMatch(field -> Modifier.isPublic(field.getModifiers()));
        }
    }

    @Test
    void allFourRoutesRequireAuthenticationAndCsrfWithoutCreatingRejectedSessions()
            throws Exception {
        CsrfExchange csrf = csrf();
        long before = springSessionCount();
        for (Endpoint endpoint : endpoints()) {
            MvcResult unauthenticated = mvc.perform(withCsrf(post(endpoint.path())
                            .with(remote(nextRemote()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsBytes(endpoint.map())), csrf))
                    .andReturn();
            assertProblem(unauthenticated, 401, "AUTHENTICATION_REQUIRED");
        }
        assertThat(springSessionCount()).isEqualTo(before);

        Fixture admin = fixture();
        AuthenticatedSession active = login(admin, csrf, nextRemote());
        for (Endpoint endpoint : endpoints()) {
            MvcResult missingCsrf = mvc.perform(post(endpoint.path())
                            .cookie(active.cookie())
                            .with(remote(nextRemote()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsBytes(endpoint.map())))
                    .andReturn();
            assertProblem(missingCsrf, 403, "CSRF_INVALID");
        }
    }

    @Test
    void passwordChangeUsesUnifiedReauthenticationThenRetainsOnlyCurrentSession()
            throws Exception {
        Fixture admin = fixture();
        CsrfExchange csrf = csrf();
        AuthenticatedSession current = login(admin, csrf, nextRemote());
        AuthenticatedSession other = login(admin, csrf, nextRemote());
        AdminUser before = admins.findById(admin.adminId).orElseThrow();
        List<String> recoveryBefore = storedRecoveryHashes(admin.adminId);

        String remote = nextRemote();
        MvcResult wrongPassword = performSettings(
                PASSWORD_PATH,
                Map.of("currentPassword", "wrong-password", "currentTotp", currentCode(admin.totpSecret),
                        "newPassword", NEW_PASSWORD),
                current.cookie(), csrf, remote);
        MvcResult wrongTotp = performSettings(
                PASSWORD_PATH,
                Map.of("currentPassword", ADMIN_PASSWORD, "currentTotp", "000000",
                        "newPassword", NEW_PASSWORD),
                current.cookie(), csrf, remote);
        assertProblem(wrongPassword, 401, "AUTHENTICATION_FAILED");
        assertProblem(wrongTotp, 401, "AUTHENTICATION_FAILED");
        assertThat(normalizedProblem(wrongPassword)).isEqualTo(normalizedProblem(wrongTotp));
        assertThat(admins.findById(admin.adminId).orElseThrow().passwordHash())
                .isEqualTo(before.passwordHash());
        assertThat(storedRecoveryHashes(admin.adminId)).isEqualTo(recoveryBefore);
        assertThat(metadataStatus(current.stablePrimaryId())).isEqualTo("ACTIVE");
        assertThat(metadataStatus(other.stablePrimaryId())).isEqualTo("ACTIVE");

        MvcResult changed = performSettings(
                PASSWORD_PATH,
                Map.of("currentPassword", ADMIN_PASSWORD,
                        "currentTotp", currentCode(admin.totpSecret),
                        "newPassword", NEW_PASSWORD),
                current.cookie(), csrf, nextRemote());
        assertThat(changed.getResponse().getStatus()).isEqualTo(204);
        assertNoStore(changed);

        AdminUser after = admins.findById(admin.adminId).orElseThrow();
        assertThat(after.version()).isEqualTo(before.version() + 1);
        assertThat(passwords.matches(NEW_PASSWORD, after.passwordHash())).isTrue();
        assertThat(after.totpSecret()).isEqualTo(before.totpSecret());
        assertThat(storedRecoveryHashes(admin.adminId)).isEqualTo(recoveryBefore);
        assertThat(metadataStatus(current.stablePrimaryId())).isEqualTo("ACTIVE");
        assertThat(metadataStatus(other.stablePrimaryId())).isEqualTo("REVOKED");
        assertThat(springSessionExists(current.stablePrimaryId())).isTrue();
        assertThat(springSessionExists(other.stablePrimaryId())).isFalse();
        assertThat(mvc.perform(get(ME_PATH).cookie(current.cookie())).andReturn()
                .getResponse().getStatus()).isEqualTo(200);
        assertProblem(mvc.perform(get(ME_PATH).cookie(other.cookie())).andReturn(),
                401, "AUTHENTICATION_REQUIRED");
        assertThat(auditCount("ADMIN_PASSWORD_CHANGED", admin.adminId)).isOne();
    }

    @Test
    void enrollmentAndConfirmationKeepOnlyEncryptedPendingStateAndDeliverCodesOnce()
            throws Exception {
        Fixture admin = fixture();
        CsrfExchange csrf = csrf();
        AuthenticatedSession current = login(admin, csrf, nextRemote());
        AuthenticatedSession other = login(admin, csrf, nextRemote());
        AdminUser before = admins.findById(admin.adminId).orElseThrow();
        long versionBefore = before.version();

        MvcResult started = performSettings(
                ENROLLMENT_PATH,
                Map.of("currentPassword", ADMIN_PASSWORD,
                        "currentTotp", currentCode(admin.totpSecret)),
                current.cookie(), csrf, nextRemote());
        assertThat(started.getResponse().getStatus()).isEqualTo(200);
        assertNoStore(started);
        JsonNode startBody = body(started);
        String enrollmentId = startBody.path("enrollmentId").asText();
        assertThat(enrollmentId).isEqualTo(UUID.fromString(enrollmentId).toString());
        String provisioningUri = startBody.path("provisioningUri").asText();
        assertThat(provisioningUri).startsWith("otpauth://totp/");
        String newSecret = queryValue(provisioningUri, "secret");
        Instant expiresAt = Instant.parse(startBody.path("expiresAt").asText());
        PendingTotpEnrollment pending = pending(current.stablePrimaryId());
        assertThat(pending.enrollmentId().toString()).isEqualTo(enrollmentId);
        assertThat(pending.adminId()).isEqualTo(admin.adminId);
        assertThat(pending.sessionMetadataId())
                .isEqualTo(metadataId(current.stablePrimaryId()));
        assertThat(pending.expiresAt()).isEqualTo(expiresAt);
        assertThat(Duration.between(pending.issuedAt(), pending.expiresAt()))
                .isEqualTo(Duration.ofMinutes(10));
        assertThat(sessionAttributeBytes(current.stablePrimaryId()))
                .doesNotContain(provisioningUri, newSecret, admin.totpSecret);
        assertThat(admins.findById(admin.adminId).orElseThrow().version())
                .isEqualTo(versionBefore + 1);
        assertThat(metadataStatus(other.stablePrimaryId())).isEqualTo("REVOKED");

        String newCode = currentCode(newSecret);
        MvcResult wrongId = performSettings(
                CONFIRM_PATH,
                Map.of("enrollmentId", UUID.randomUUID().toString(), "newTotp", newCode),
                current.cookie(), csrf, nextRemote());
        assertProblem(wrongId, 401, "AUTHENTICATION_FAILED");
        assertThat(pending(current.stablePrimaryId()).failures()).isOne();

        MvcResult confirmed = performSettings(
                CONFIRM_PATH,
                Map.of("enrollmentId", enrollmentId, "newTotp", newCode),
                current.cookie(), csrf, nextRemote());
        assertThat(confirmed.getResponse().getStatus()).isEqualTo(200);
        assertNoStore(confirmed);
        List<String> delivered = new ArrayList<>();
        body(confirmed).path("recoveryCodes").forEach(node -> delivered.add(node.asText()));
        assertThat(delivered).hasSize(10).doesNotHaveDuplicates();
        assertThat(sessionAttribute(current.stablePrimaryId(), PendingTotpEnrollment.SESSION_KEY))
                .isEmpty();
        AdminUser after = admins.findById(admin.adminId).orElseThrow();
        assertThat(after.version()).isEqualTo(versionBefore + 2);
        assertThat(totp.verify(admin.adminId, after.totpSecret(), newCode)).isTrue();
        String oldFactor = oldFactorRejectedByReplacement(
                admin.adminId, admin.totpSecret, before.totpSecret(), after.totpSecret());
        assertThat(totp.verify(admin.adminId, after.totpSecret(), oldFactor)).isFalse();
        assertThat(storedRecoveryHashes(admin.adminId))
                .hasSize(10)
                .allSatisfy(hash -> assertThat(delivered).noneMatch(hash::contains));
        assertThat(recoveryCodeService.consume(admin.adminId, OLD_RECOVERY_CODE)).isFalse();
        assertThat(recoveryCodeService.consume(admin.adminId, delivered.get(0))).isTrue();
        assertThat(recoveryCodeService.consume(admin.adminId, delivered.get(0))).isFalse();

        MvcResult replay = performSettings(
                CONFIRM_PATH,
                Map.of("enrollmentId", enrollmentId, "newTotp", newCode),
                current.cookie(), csrf, nextRemote());
        assertProblem(replay, 409, "TOTP_ENROLLMENT_EXPIRED");
        assertThat(auditCount("ADMIN_TOTP_ENROLLMENT_STARTED", admin.adminId)).isOne();
        assertThat(auditCount("ADMIN_TOTP_CHANGED", admin.adminId)).isOne();
    }

    @Test
    void passwordPolicyViolationIs422AndLeavesEverySecurityStateUnchanged()
            throws Exception {
        Fixture admin = fixture();
        CsrfExchange csrf = csrf();
        AuthenticatedSession current = login(admin, csrf, nextRemote());
        AuthenticatedSession other = login(admin, csrf, nextRemote());
        AdminUser before = admins.findById(admin.adminId).orElseThrow();
        List<String> recoveryBefore = storedRecoveryHashes(admin.adminId);

        MvcResult result = performSettings(
                PASSWORD_PATH,
                Map.of("currentPassword", ADMIN_PASSWORD,
                        "currentTotp", currentCode(admin.totpSecret),
                        "newPassword", "too-weak"),
                current.cookie(), csrf, nextRemote());

        assertProblem(result, 422, "PASSWORD_POLICY_VIOLATION");
        assertSecurityStateUnchanged(admin.adminId, current, before, recoveryBefore);
        assertThat(metadataStatus(other.stablePrimaryId())).isEqualTo("ACTIVE");
        assertThat(auditCount("ADMIN_PASSWORD_CHANGED", admin.adminId)).isZero();
    }

    @Test
    void fifthWrongConfirmationIsTerminalAndAReplacementTombstonesTheOldCandidate()
            throws Exception {
        Fixture admin = fixture();
        CsrfExchange csrf = csrf();
        AuthenticatedSession current = login(admin, csrf, nextRemote());
        JsonNode first = body(performSettings(
                ENROLLMENT_PATH,
                Map.of("currentPassword", ADMIN_PASSWORD,
                        "currentTotp", currentCode(admin.totpSecret)),
                current.cookie(), csrf, nextRemote()));
        String firstId = first.path("enrollmentId").asText();
        JsonNode replacement = body(performSettings(
                ENROLLMENT_PATH,
                Map.of("currentPassword", ADMIN_PASSWORD,
                        "currentTotp", currentCode(admin.totpSecret)),
                current.cookie(), csrf, nextRemote()));
        String replacementId = replacement.path("enrollmentId").asText();
        assertThat(replacementId).isNotEqualTo(firstId);

        MvcResult old = performSettings(
                CONFIRM_PATH,
                Map.of("enrollmentId", firstId, "newTotp", "000000"),
                current.cookie(), csrf, nextRemote());
        assertProblem(old, 401, "AUTHENTICATION_FAILED");
        assertThat(pending(current.stablePrimaryId()).enrollmentId().toString())
                .isEqualTo(replacementId);

        for (int attempt = 2; attempt <= 5; attempt++) {
            MvcResult wrong = performSettings(
                    CONFIRM_PATH,
                    Map.of("enrollmentId", replacementId, "newTotp", "000000"),
                    current.cookie(), csrf, nextRemote());
            assertProblem(wrong, 401, "AUTHENTICATION_FAILED");
        }
        assertThat(sessionAttribute(current.stablePrimaryId(), PendingTotpEnrollment.SESSION_KEY))
                .isEmpty();
        MvcResult exhausted = performSettings(
                CONFIRM_PATH,
                Map.of("enrollmentId", replacementId,
                        "newTotp", currentCode(queryValue(
                                replacement.path("provisioningUri").asText(), "secret"))),
                current.cookie(), csrf, nextRemote());
        assertProblem(exhausted, 409, "TOTP_ENROLLMENT_EXPIRED");
    }

    @Test
    void recoveryRegenerationBumpsEpochInvalidatesPendingAndReturnsTenHashedCodes()
            throws Exception {
        Fixture admin = fixture();
        CsrfExchange csrf = csrf();
        AuthenticatedSession current = login(admin, csrf, nextRemote());
        AuthenticatedSession other = login(admin, csrf, nextRemote());
        performSettings(
                ENROLLMENT_PATH,
                Map.of("currentPassword", ADMIN_PASSWORD,
                        "currentTotp", currentCode(admin.totpSecret)),
                current.cookie(), csrf, nextRemote());
        long versionBefore = admins.findById(admin.adminId).orElseThrow().version();

        MvcResult regenerated = performSettings(
                REGENERATE_PATH,
                Map.of("currentPassword", ADMIN_PASSWORD,
                        "currentTotp", currentCode(admin.totpSecret)),
                current.cookie(), csrf, nextRemote());
        assertThat(regenerated.getResponse().getStatus()).isEqualTo(200);
        assertNoStore(regenerated);
        List<String> delivered = new ArrayList<>();
        body(regenerated).path("recoveryCodes")
                .forEach(node -> delivered.add(node.asText()));
        assertThat(delivered).hasSize(10).doesNotHaveDuplicates();
        assertThat(storedRecoveryHashes(admin.adminId))
                .allSatisfy(hash -> assertThat(delivered).noneMatch(hash::contains));
        assertThat(admins.findById(admin.adminId).orElseThrow().version())
                .isEqualTo(versionBefore + 1);
        assertThat(sessionAttribute(current.stablePrimaryId(), PendingTotpEnrollment.SESSION_KEY))
                .isEmpty();
        assertThat(recoveryCodeService.consume(admin.adminId, OLD_RECOVERY_CODE)).isFalse();
        assertThat(recoveryCodeService.consume(admin.adminId, delivered.get(0))).isTrue();
        assertThat(recoveryCodeService.consume(admin.adminId, delivered.get(0))).isFalse();
        assertThat(metadataStatus(current.stablePrimaryId())).isEqualTo("ACTIVE");
        assertThat(metadataStatus(other.stablePrimaryId())).isEqualTo("REVOKED");
        assertThat(auditCount("ADMIN_RECOVERY_CODES_REGENERATED", admin.adminId)).isOne();
    }

    @Test
    void sharedCrossEndpointBudgetAllowsTenSemanticAttemptsAndDeniesEleventh()
            throws Exception {
        Fixture admin = fixture();
        CsrfExchange csrf = csrf();
        AuthenticatedSession current = login(admin, csrf, nextRemote());
        AdminUser before = admins.findById(admin.adminId).orElseThrow();
        List<String> codesBefore = storedRecoveryHashes(admin.adminId);
        String remote = nextRemote();

        for (int attempt = 0; attempt < 10; attempt++) {
            String path = attempt % 3 == 0 ? PASSWORD_PATH
                    : attempt % 3 == 1 ? ENROLLMENT_PATH : REGENERATE_PATH;
            Map<String, String> values = path.equals(PASSWORD_PATH)
                    ? Map.of("currentPassword", "wrong", "currentTotp", "bad",
                            "newPassword", NEW_PASSWORD)
                    : Map.of("currentPassword", "wrong", "currentTotp", "bad");
            MvcResult result = performSettings(
                    path, values, current.cookie(), csrf, remote);
            assertProblem(result, 401, "AUTHENTICATION_FAILED");
        }
        MvcResult limited = performSettings(
                CONFIRM_PATH,
                Map.of("enrollmentId", "not-a-uuid", "newTotp", "bad"),
                current.cookie(), csrf, remote);
        assertProblem(limited, 429, "RATE_LIMITED");
        String retryAfter = limited.getResponse().getHeader(HttpHeaders.RETRY_AFTER);
        assertThat(retryAfter).matches("[1-9][0-9]{0,9}");
        assertThat(body(limited).path("fieldErrors").path("retryAfterSeconds").asText())
                .isEqualTo(retryAfter);
        AdminUser after = admins.findById(admin.adminId).orElseThrow();
        assertThat(after.version()).isEqualTo(before.version());
        assertThat(after.passwordHash()).isEqualTo(before.passwordHash());
        assertThat(after.totpSecret()).isEqualTo(before.totpSecret());
        assertThat(storedRecoveryHashes(admin.adminId)).isEqualTo(codesBefore);
        assertThat(metadataStatus(current.stablePrimaryId())).isEqualTo("ACTIVE");
    }

    @Test
    void settingsVersionAdvanceInvalidatesAnAlreadyIssuedTaskElevenPendingLogin()
            throws Exception {
        Fixture admin = fixture();
        CsrfExchange csrf = csrf();
        AuthenticatedSession current = login(admin, csrf, nextRemote());
        long versionBefore = admins.findById(admin.adminId).orElseThrow().version();

        MvcResult passwordStage = mvc.perform(withCsrf(post(PASSWORD_LOGIN_PATH)
                        .with(remote(nextRemote()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of(
                                "username", ADMIN_USERNAME,
                                "password", ADMIN_PASSWORD))), csrf))
                .andReturn();
        assertThat(passwordStage.getResponse().getStatus()).isEqualTo(200);
        Cookie pendingCookie = findResponseCookie(passwordStage, SESSION_COOKIE)
                .orElseThrow(() -> new AssertionError("pending login cookie was not set"));
        String pendingPrimaryId = requireStablePrimaryId(pendingCookie.getValue());
        admin.primaryIds.add(pendingPrimaryId);
        assertThat(sessionAttribute(pendingPrimaryId, PendingSecondFactor.SESSION_KEY)).isPresent();

        MvcResult advanced = performSettings(
                ENROLLMENT_PATH,
                Map.of("currentPassword", ADMIN_PASSWORD,
                        "currentTotp", currentCode(admin.totpSecret)),
                current.cookie(), csrf, nextRemote());
        assertThat(advanced.getResponse().getStatus()).isEqualTo(200);
        assertThat(admins.findById(admin.adminId).orElseThrow().version())
                .isEqualTo(versionBefore + 1);

        MvcResult stale = mvc.perform(withCsrf(post(SECOND_FACTOR_PATH)
                        .cookie(pendingCookie)
                        .with(remote(nextRemote()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of(
                                "method", "TOTP",
                                "code", currentCode(admin.totpSecret)))), csrf))
                .andReturn();
        assertProblem(stale, 401, "AUTHENTICATION_FAILED");
        assertThat(sessionAttribute(pendingPrimaryId, PendingSecondFactor.SESSION_KEY)).isEmpty();
    }

    @Test
    void disabledAdministratorWithActiveMetadataGetsUnifiedZeroBusinessWriteRejection()
            throws Exception {
        Fixture admin = fixture();
        CsrfExchange csrf = csrf();
        AuthenticatedSession current = login(admin, csrf, nextRemote());
        AdminUser before = admins.findById(admin.adminId).orElseThrow();
        List<String> recoveryBefore = storedRecoveryHashes(admin.adminId);
        assertThat(jdbc.sql("update portfolio.admin_user set status='DISABLED' where id=:id")
                .param("id", admin.adminId).update()).isOne();

        for (Endpoint endpoint : endpoints()) {
            MvcResult result = performSettings(
                    endpoint.path(), endpoint.map(), current.cookie(), csrf, nextRemote());
            assertProblem(result, 401, "AUTHENTICATION_FAILED");
        }
        AdminUser after = admins.findById(admin.adminId).orElseThrow();
        assertThat(after.status()).isEqualTo(AdminStatus.DISABLED);
        assertThat(after.version()).isEqualTo(before.version());
        assertThat(after.passwordHash()).isEqualTo(before.passwordHash());
        assertThat(after.totpSecret()).isEqualTo(before.totpSecret());
        assertThat(storedRecoveryHashes(admin.adminId)).isEqualTo(recoveryBefore);
        assertThat(metadataStatus(current.stablePrimaryId())).isEqualTo("ACTIVE");
        assertThat(sessionAttribute(current.stablePrimaryId(), PendingTotpEnrollment.SESSION_KEY))
                .isEmpty();
    }

    @Test
    void passwordEncoderDomainFailureIsSanitizedWithoutChangingSecurityState()
            throws Exception {
        Fixture admin = fixture();
        CsrfExchange csrf = csrf();
        AuthenticatedSession current = login(admin, csrf, nextRemote());
        AdminUser before = admins.findById(admin.adminId).orElseThrow();
        List<String> recoveryBefore = storedRecoveryHashes(admin.adminId);
        doThrow(leakingDependencyFailure()).doCallRealMethod()
                .when(passwords).encode(NEW_PASSWORD);
        String remote = nextRemote();

        MvcResult result = performSettings(
                PASSWORD_PATH,
                Map.of("currentPassword", ADMIN_PASSWORD,
                        "currentTotp", currentCode(admin.totpSecret),
                        "newPassword", NEW_PASSWORD),
                current.cookie(), csrf, remote);

        assertSanitizedInternal(result);
        assertSecurityStateUnchanged(admin.adminId, current, before, recoveryBefore);

        MvcResult retried = performSettings(
                PASSWORD_PATH,
                Map.of("currentPassword", ADMIN_PASSWORD,
                        "currentTotp", currentCode(admin.totpSecret),
                        "newPassword", NEW_PASSWORD),
                current.cookie(), csrf, remote);
        assertThat(retried.getResponse().getStatus()).isEqualTo(204);
        assertNoStore(retried);
        assertThat(passwords.matches(
                NEW_PASSWORD,
                admins.findById(admin.adminId).orElseThrow().passwordHash())).isTrue();
    }

    @Test
    void recoveryHashDomainFailureIsSanitizedWithoutChangingSecurityState()
            throws Exception {
        Fixture admin = fixture();
        CsrfExchange csrf = csrf();
        AuthenticatedSession current = login(admin, csrf, nextRemote());
        AdminUser before = admins.findById(admin.adminId).orElseThrow();
        List<String> recoveryBefore = storedRecoveryHashes(admin.adminId);
        doThrow(leakingDependencyFailure()).when(recoveryCodeService).hashAll(anyList());

        MvcResult result = performSettings(
                REGENERATE_PATH,
                Map.of("currentPassword", ADMIN_PASSWORD,
                        "currentTotp", currentCode(admin.totpSecret)),
                current.cookie(), csrf, nextRemote());

        assertSanitizedInternal(result);
        assertSecurityStateUnchanged(admin.adminId, current, before, recoveryBefore);
    }

    @Test
    void currentTotpProviderFailureIsFreshlySanitizedWithoutStateWrites()
            throws Exception {
        Fixture admin = fixture();
        CsrfExchange csrf = csrf();
        AuthenticatedSession current = login(admin, csrf, nextRemote());
        AdminUser before = admins.findById(admin.adminId).orElseThrow();
        List<String> recoveryBefore = storedRecoveryHashes(admin.adminId);
        TotpService failingTotp = org.mockito.Mockito.spy(totp);
        doThrow(leakingDependencyFailure()).when(failingTotp)
                .verify(eq(admin.adminId), any(EncryptedTotpSecret.class), anyString());
        AdminSecuritySettingsService target = freshSettings(
                admins, recoveryCodes, recoveryCodeService, recoveryGenerator,
                failingTotp, limiter, subjects, sessions, audit, clock);

        DomainException failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                DomainException.class,
                () -> target.changePassword(
                        admin.adminId,
                        sessions.requireActive(current.cookie().getValue()),
                        ADMIN_PASSWORD,
                        currentCode(admin.totpSecret),
                        NEW_PASSWORD,
                        requestWith(new MockHttpSession())));

        assertFreshInternal(failure);
        assertSecurityStateUnchanged(admin.adminId, current, before, recoveryBefore);
    }

    @Test
    void totpEnrollmentGenerationFailureIsFreshlySanitizedWithoutEpochAdvance()
            throws Exception {
        Fixture admin = fixture();
        CsrfExchange csrf = csrf();
        AuthenticatedSession current = login(admin, csrf, nextRemote());
        AdminUser before = admins.findById(admin.adminId).orElseThrow();
        List<String> recoveryBefore = storedRecoveryHashes(admin.adminId);
        TotpService failingTotp = org.mockito.Mockito.spy(totp);
        doThrow(leakingDependencyFailure()).when(failingTotp)
                .beginEnrollment(admin.adminId, ADMIN_USERNAME);
        AdminSecuritySettingsService target = freshSettings(
                admins, recoveryCodes, recoveryCodeService, recoveryGenerator,
                failingTotp, limiter, subjects, sessions, audit, clock);

        DomainException failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                DomainException.class,
                () -> {
                    try (AdminSecuritySettingsService.EnrollmentDelivery ignored =
                            target.beginTotpEnrollment(
                                    admin.adminId,
                                    sessions.requireActive(current.cookie().getValue()),
                                    ADMIN_PASSWORD,
                                    currentCode(admin.totpSecret),
                                    requestWith(new MockHttpSession()))) {
                        throw new AssertionError("enrollment unexpectedly returned a delivery");
                    }
                });

        assertFreshInternal(failure);
        assertSecurityStateUnchanged(admin.adminId, current, before, recoveryBefore);
        assertThat(auditCount("ADMIN_TOTP_ENROLLMENT_STARTED", admin.adminId)).isZero();
    }

    @Test
    void candidateTotpProviderFailureIsSanitizedAndItsLeaseCanRetry()
            throws Exception {
        Fixture admin = fixture();
        CsrfExchange csrf = csrf();
        AuthenticatedSession current = login(admin, csrf, nextRemote());
        TotpService failingTotp = org.mockito.Mockito.spy(totp);
        AdminSecuritySettingsService target = freshSettings(
                admins, recoveryCodes, recoveryCodeService, recoveryGenerator,
                failingTotp, limiter, subjects, sessions, audit, clock);
        DirectEnrollment enrollment = directEnrollment(target, admin, current);
        String newCode = currentCode(queryValue(
                enrollment.material().provisioningUri(), "secret"));
        doThrow(leakingDependencyFailure()).doCallRealMethod().when(failingTotp)
                .verify(eq(admin.adminId), any(EncryptedTotpSecret.class), anyString());

        DomainException failure = confirmationFailure(target, enrollment, newCode);

        assertRetryableConfirmationRollback(enrollment, failure, null);
        assertSuccessfulConfirmationRetry(target, enrollment, newCode);
    }

    @Test
    void recoveryGeneratorFailureIsFreshlySanitizedWithoutStateWrites()
            throws Exception {
        Fixture admin = fixture();
        CsrfExchange csrf = csrf();
        AuthenticatedSession current = login(admin, csrf, nextRemote());
        AdminUser before = admins.findById(admin.adminId).orElseThrow();
        List<String> recoveryBefore = storedRecoveryHashes(admin.adminId);
        RecoveryCodeGenerator failingGenerator = mock(RecoveryCodeGenerator.class);
        doThrow(leakingDependencyFailure()).when(failingGenerator).generate(10);
        AdminSecuritySettingsService target = freshSettings(
                admins, recoveryCodes, recoveryCodeService, failingGenerator,
                totp, limiter, subjects, sessions, audit, clock);

        DomainException failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                DomainException.class,
                () -> {
                    try (AdminSecuritySettingsService.RecoveryCodesDelivery ignored =
                            target.regenerateRecoveryCodes(
                                    admin.adminId,
                                    sessions.requireActive(current.cookie().getValue()),
                                    ADMIN_PASSWORD,
                                    currentCode(admin.totpSecret),
                                    requestWith(new MockHttpSession()))) {
                        throw new AssertionError("regeneration unexpectedly returned a delivery");
                    }
                });

        assertFreshInternal(failure);
        assertSecurityStateUnchanged(admin.adminId, current, before, recoveryBefore);
    }

    @Test
    void limiterAndSecuritySubjectFailuresAreFreshlySanitizedBeforeStateWrites()
            throws Exception {
        Fixture admin = fixture();
        CsrfExchange csrf = csrf();
        AuthenticatedSession current = login(admin, csrf, nextRemote());
        AdminUser before = admins.findById(admin.adminId).orElseThrow();
        List<String> recoveryBefore = storedRecoveryHashes(admin.adminId);
        RateLimiter failingLimiter = mock(RateLimiter.class);
        doThrow(leakingDependencyFailure()).when(failingLimiter)
                .consume(eq("admin-security"), anyString());
        LoginSubjectHasher failingSubjects = mock(LoginSubjectHasher.class);
        doThrow(new IllegalStateException("credential-secret")).when(failingSubjects)
                .hashSecurity(any(HttpServletRequest.class), eq(admin.adminId));
        MockHttpServletRequest request = requestWith(new MockHttpSession());

        AdminSecuritySettingsService limiterTarget = freshSettings(
                admins, recoveryCodes, recoveryCodeService, recoveryGenerator,
                totp, failingLimiter, subjects, sessions, audit, clock);
        DomainException limiterFailure = org.assertj.core.api.Assertions.catchThrowableOfType(
                DomainException.class,
                () -> limiterTarget.changePassword(
                        admin.adminId,
                        sessions.requireActive(current.cookie().getValue()),
                        ADMIN_PASSWORD,
                        currentCode(admin.totpSecret),
                        NEW_PASSWORD,
                        request));
        assertFreshInternal(limiterFailure);

        AdminSecuritySettingsService subjectTarget = freshSettings(
                admins, recoveryCodes, recoveryCodeService, recoveryGenerator,
                totp, limiter, failingSubjects, sessions, audit, clock);
        DomainException subjectFailure = org.assertj.core.api.Assertions.catchThrowableOfType(
                DomainException.class,
                () -> subjectTarget.changePassword(
                        admin.adminId,
                        sessions.requireActive(current.cookie().getValue()),
                        ADMIN_PASSWORD,
                        currentCode(admin.totpSecret),
                        NEW_PASSWORD,
                        request));
        assertFreshInternal(subjectFailure);
        assertSecurityStateUnchanged(admin.adminId, current, before, recoveryBefore);
    }

    @Test
    void clockFailureIsFreshlySanitizedBeforeEnrollmentMutation()
            throws Exception {
        Fixture admin = fixture();
        CsrfExchange csrf = csrf();
        AuthenticatedSession current = login(admin, csrf, nextRemote());
        AdminUser before = admins.findById(admin.adminId).orElseThrow();
        List<String> recoveryBefore = storedRecoveryHashes(admin.adminId);
        Clock failingClock = mock(Clock.class);
        doThrow(leakingDependencyFailure()).when(failingClock).instant();
        AdminSecuritySettingsService target = freshSettings(
                admins, recoveryCodes, recoveryCodeService, recoveryGenerator,
                totp, limiter, subjects, sessions, audit, failingClock);

        DomainException failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                DomainException.class,
                () -> {
                    try (AdminSecuritySettingsService.EnrollmentDelivery ignored =
                            target.beginTotpEnrollment(
                                    admin.adminId,
                                    sessions.requireActive(current.cookie().getValue()),
                                    ADMIN_PASSWORD,
                                    currentCode(admin.totpSecret),
                                    requestWith(new MockHttpSession()))) {
                        throw new AssertionError("enrollment unexpectedly returned a delivery");
                    }
                });

        assertFreshInternal(failure);
        assertSecurityStateUnchanged(admin.adminId, current, before, recoveryBefore);
        assertThat(auditCount("ADMIN_TOTP_ENROLLMENT_STARTED", admin.adminId)).isZero();
    }

    @Test
    void persistenceDomainFailureIsSanitizedAndRollsBackTheMutation()
            throws Exception {
        Fixture admin = fixture();
        CsrfExchange csrf = csrf();
        AuthenticatedSession current = login(admin, csrf, nextRemote());
        AdminUser before = admins.findById(admin.adminId).orElseThrow();
        List<String> recoveryBefore = storedRecoveryHashes(admin.adminId);
        doThrow(leakingDependencyFailure()).when(sessionRepository)
                .markOtherRevoked(
                        eq(admin.adminId), any(UUID.class), anyString(), any(Instant.class));

        MvcResult result = performSettings(
                PASSWORD_PATH,
                Map.of("currentPassword", ADMIN_PASSWORD,
                        "currentTotp", currentCode(admin.totpSecret),
                        "newPassword", NEW_PASSWORD),
                current.cookie(), csrf, nextRemote());

        assertSanitizedInternal(result);
        assertSecurityStateUnchanged(admin.adminId, current, before, recoveryBefore);
    }

    @Test
    void sessionDomainFailureIsSanitizedWithoutChangingSecurityState()
            throws Exception {
        Fixture admin = fixture();
        CsrfExchange csrf = csrf();
        AuthenticatedSession current = login(admin, csrf, nextRemote());
        AdminUser before = admins.findById(admin.adminId).orElseThrow();
        List<String> recoveryBefore = storedRecoveryHashes(admin.adminId);
        doThrow(leakingDependencyFailure()).when(sessions)
                .findCurrentSessionInCurrentTransaction(
                        eq(admin.adminId), any(AdminSessionService.ActiveSession.class));

        MvcResult result = performSettings(
                PASSWORD_PATH,
                Map.of("currentPassword", ADMIN_PASSWORD,
                        "currentTotp", currentCode(admin.totpSecret),
                        "newPassword", NEW_PASSWORD),
                current.cookie(), csrf, nextRemote());

        assertSanitizedInternal(result);
        assertSecurityStateUnchanged(admin.adminId, current, before, recoveryBefore);
    }

    @Test
    void forgedAuthenticationRequiredFromInitialSessionValidationIsSanitized()
            throws Exception {
        Fixture admin = fixture();
        CsrfExchange csrf = csrf();
        AuthenticatedSession current = login(admin, csrf, nextRemote());
        AdminUser before = admins.findById(admin.adminId).orElseThrow();
        List<String> recoveryBefore = storedRecoveryHashes(admin.adminId);
        doThrow(forgedAuthenticationRequiredFailure()).when(sessions)
                .findCurrentSessionInCurrentTransaction(
                        eq(admin.adminId), any(AdminSessionService.ActiveSession.class));

        MvcResult result = performSettings(
                PASSWORD_PATH,
                Map.of("currentPassword", ADMIN_PASSWORD,
                        "currentTotp", currentCode(admin.totpSecret),
                        "newPassword", NEW_PASSWORD),
                current.cookie(), csrf, nextRemote());

        assertSanitizedInternal(result);
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("AUTHENTICATION_REQUIRED");
        assertSecurityStateUnchanged(admin.adminId, current, before, recoveryBefore);
        assertThat(auditCount("ADMIN_SECURITY_REAUTH_REJECTED", admin.adminId)).isZero();
    }

    @Test
    void forgedAuthenticationRequiredFromFinalSessionValidationIsSanitizedAndRollsBack()
            throws Exception {
        Fixture admin = fixture();
        CsrfExchange csrf = csrf();
        AuthenticatedSession current = login(admin, csrf, nextRemote());
        AdminUser before = admins.findById(admin.adminId).orElseThrow();
        List<String> recoveryBefore = storedRecoveryHashes(admin.adminId);
        AtomicInteger validations = new AtomicInteger();
        doAnswer(invocation -> {
            if (validations.incrementAndGet() == 2) {
                throw forgedAuthenticationRequiredFailure();
            }
            return invocation.callRealMethod();
        }).when(sessions).findCurrentSessionInCurrentTransaction(
                eq(admin.adminId), any(AdminSessionService.ActiveSession.class));

        MvcResult result = performSettings(
                PASSWORD_PATH,
                Map.of("currentPassword", ADMIN_PASSWORD,
                        "currentTotp", currentCode(admin.totpSecret),
                        "newPassword", NEW_PASSWORD),
                current.cookie(), csrf, nextRemote());

        assertSanitizedInternal(result);
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("AUTHENTICATION_REQUIRED");
        assertThat(validations).hasValue(2);
        assertSecurityStateUnchanged(admin.adminId, current, before, recoveryBefore);
        assertThat(auditCount("ADMIN_SECURITY_REAUTH_REJECTED", admin.adminId)).isZero();
        assertThat(auditCount("ADMIN_PASSWORD_CHANGED", admin.adminId)).isZero();
    }

    @Test
    void auditDomainFailureIsSanitizedAndRollsBackTheCredentialMutation()
            throws Exception {
        Fixture admin = fixture();
        CsrfExchange csrf = csrf();
        AuthenticatedSession current = login(admin, csrf, nextRemote());
        AdminUser before = admins.findById(admin.adminId).orElseThrow();
        List<String> recoveryBefore = storedRecoveryHashes(admin.adminId);
        doThrow(leakingDependencyFailure()).when(audit).record(argThat(command ->
                "ADMIN_PASSWORD_CHANGED".equals(command.action())));

        MvcResult result = performSettings(
                PASSWORD_PATH,
                Map.of("currentPassword", ADMIN_PASSWORD,
                        "currentTotp", currentCode(admin.totpSecret),
                        "newPassword", NEW_PASSWORD),
                current.cookie(), csrf, nextRemote());

        assertSanitizedInternal(result);
        assertSecurityStateUnchanged(admin.adminId, current, before, recoveryBefore);
        assertThat(auditCount("ADMIN_PASSWORD_CHANGED", admin.adminId)).isZero();
    }

    @Test
    void confirmationCredentialUpdateFailureRollsBackAndKeepsCandidateRetryable()
            throws Exception {
        Fixture admin = fixture();
        CsrfExchange csrf = csrf();
        AuthenticatedSession current = login(admin, csrf, nextRemote());
        AdminSecuritySettingsService target = freshSettings();
        DirectEnrollment enrollment = directEnrollment(target, admin, current);
        String newCode = currentCode(queryValue(
                enrollment.material().provisioningUri(), "secret"));
        AdminUserRepository adminSpy = AopTestUtils.getUltimateTargetObject(admins);
        doThrow(leakingDependencyFailure()).doCallRealMethod().when(adminSpy)
                .updateTotp(eq(admin.adminId), any(EncryptedTotpSecret.class));

        DomainException failure = confirmationFailure(target, enrollment, newCode);

        assertRetryableConfirmationRollback(enrollment, failure, null);
        assertSuccessfulConfirmationRetry(target, enrollment, newCode);
    }

    @Test
    void confirmationRecoveryReplacementFailureRollsBackCredentialAndKeepsCandidateRetryable()
            throws Exception {
        Fixture admin = fixture();
        CsrfExchange csrf = csrf();
        AuthenticatedSession current = login(admin, csrf, nextRemote());
        AdminSecuritySettingsService target = freshSettings();
        DirectEnrollment enrollment = directEnrollment(target, admin, current);
        String newCode = currentCode(queryValue(
                enrollment.material().provisioningUri(), "secret"));
        RecoveryCodeRepository recoverySpy =
                AopTestUtils.getUltimateTargetObject(recoveryCodes);
        doThrow(leakingDependencyFailure()).doCallRealMethod().when(recoverySpy)
                .replace(eq(admin.adminId), anyList());

        DomainException failure = confirmationFailure(target, enrollment, newCode);

        assertRetryableConfirmationRollback(enrollment, failure, null);
        assertSuccessfulConfirmationRetry(target, enrollment, newCode);
    }

    @Test
    void confirmationSessionMarkFailureRollsBackCredentialRecoveryAndSessionState()
            throws Exception {
        Fixture admin = fixture();
        CsrfExchange csrf = csrf();
        AuthenticatedSession current = login(admin, csrf, nextRemote());
        AdminSecuritySettingsService target = freshSettings();
        DirectEnrollment enrollment = directEnrollment(target, admin, current);
        AuthenticatedSession other = activeSessionWithoutSecurityVersionAdvance(admin);
        String newCode = currentCode(queryValue(
                enrollment.material().provisioningUri(), "secret"));
        doThrow(leakingDependencyFailure()).doCallRealMethod().when(sessions)
                .markOtherSessionsRevokedInCurrentTransaction(
                        eq(admin.adminId), any(AdminSessionService.ActiveSession.class),
                        eq("TOTP_CHANGED"));

        DomainException failure = confirmationFailure(target, enrollment, newCode);

        assertRetryableConfirmationRollback(enrollment, failure, other);
        assertSuccessfulConfirmationRetry(target, enrollment, newCode);
        assertThat(metadataStatus(other.stablePrimaryId())).isEqualTo("REVOKED");
    }

    @Test
    void confirmationPerSessionAuditFailureRollsBackMarkedSessionAndAllCredentialWrites()
            throws Exception {
        Fixture admin = fixture();
        CsrfExchange csrf = csrf();
        AuthenticatedSession current = login(admin, csrf, nextRemote());
        AdminSecuritySettingsService target = freshSettings();
        DirectEnrollment enrollment = directEnrollment(target, admin, current);
        AuthenticatedSession other = activeSessionWithoutSecurityVersionAdvance(admin);
        UUID otherMetadataId = metadataId(other.stablePrimaryId());
        String newCode = currentCode(queryValue(
                enrollment.material().provisioningUri(), "secret"));
        AtomicInteger matchingAudits = new AtomicInteger();
        doAnswer(invocation -> {
            AuditCommand command = invocation.getArgument(0);
            if ("SESSION_REVOKED".equals(command.action())
                    && matchingAudits.getAndIncrement() == 0) {
                throw leakingDependencyFailure();
            }
            return invocation.callRealMethod();
        }).when(audit).record(any(AuditCommand.class));

        DomainException failure = confirmationFailure(target, enrollment, newCode);

        assertRetryableConfirmationRollback(enrollment, failure, other);
        assertThat(auditTargetCount("SESSION_REVOKED", otherMetadataId.toString())).isZero();
        assertSuccessfulConfirmationRetry(target, enrollment, newCode);
        assertThat(metadataStatus(other.stablePrimaryId())).isEqualTo("REVOKED");
        assertThat(auditTargetCount("SESSION_REVOKED", otherMetadataId.toString())).isOne();
    }

    @Test
    void confirmationSuccessAuditFailureReturnsNoCodesAndRetryDeliversExactlyOnce()
            throws Exception {
        Fixture admin = fixture();
        CsrfExchange csrf = csrf();
        AuthenticatedSession current = login(admin, csrf, nextRemote());
        JsonNode started = body(performSettings(
                ENROLLMENT_PATH,
                Map.of("currentPassword", ADMIN_PASSWORD,
                        "currentTotp", currentCode(admin.totpSecret)),
                current.cookie(), csrf, nextRemote()));
        PendingTotpEnrollment pendingBefore = pending(current.stablePrimaryId());
        AdminUser adminBefore = admins.findById(admin.adminId).orElseThrow();
        List<String> recoveryBefore = storedRecoveryHashes(admin.adminId);
        String newCode = currentCode(queryValue(
                started.path("provisioningUri").asText(), "secret"));
        AtomicInteger matchingAudits = new AtomicInteger();
        doAnswer(invocation -> {
            AuditCommand command = invocation.getArgument(0);
            if ("ADMIN_TOTP_CHANGED".equals(command.action())
                    && matchingAudits.getAndIncrement() == 0) {
                throw leakingDependencyFailure();
            }
            return invocation.callRealMethod();
        }).when(audit).record(any(AuditCommand.class));

        MvcResult failed = performSettings(
                CONFIRM_PATH,
                Map.of("enrollmentId", started.path("enrollmentId").asText(),
                        "newTotp", newCode),
                current.cookie(), csrf, nextRemote());

        assertSanitizedInternal(failed);
        assertThat(body(failed).has("recoveryCodes")).isFalse();
        assertThat(pending(current.stablePrimaryId())).isEqualTo(pendingBefore);
        AdminUser afterFailure = admins.findById(admin.adminId).orElseThrow();
        assertThat(afterFailure.version()).isEqualTo(adminBefore.version());
        assertThat(afterFailure.totpSecret()).isEqualTo(adminBefore.totpSecret());
        assertThat(storedRecoveryHashes(admin.adminId)).isEqualTo(recoveryBefore);
        assertThat(auditCount("ADMIN_TOTP_CHANGED", admin.adminId)).isZero();

        MvcResult retried = performSettings(
                CONFIRM_PATH,
                Map.of("enrollmentId", started.path("enrollmentId").asText(),
                        "newTotp", newCode),
                current.cookie(), csrf, nextRemote());
        assertThat(retried.getResponse().getStatus()).isEqualTo(200);
        assertNoStore(retried);
        assertThat(body(retried).path("recoveryCodes")).hasSize(10);
        assertThat(sessionAttribute(
                current.stablePrimaryId(), PendingTotpEnrollment.SESSION_KEY)).isEmpty();
        assertThat(auditCount("ADMIN_TOTP_CHANGED", admin.adminId)).isOne();
    }

    @Test
    void wrongConfirmationConsumesItsAttemptAndAuditsWhenTheActorTurnsStale()
            throws Exception {
        Fixture admin = fixture();
        CsrfExchange csrf = csrf();
        AuthenticatedSession current = login(admin, csrf, nextRemote());
        JsonNode started = body(performSettings(
                ENROLLMENT_PATH,
                Map.of("currentPassword", ADMIN_PASSWORD,
                        "currentTotp", currentCode(admin.totpSecret)),
                current.cookie(), csrf, nextRemote()));
        String enrollmentId = started.path("enrollmentId").asText();
        UUID currentMetadataId = metadataId(current.stablePrimaryId());
        CountDownLatch rejectionValidationEntered = new CountDownLatch(1);
        CountDownLatch allowRejectionValidation = new CountDownLatch(1);
        AtomicInteger validations = new AtomicInteger();
        doAnswer(invocation -> {
            if (validations.incrementAndGet() == 2) {
                rejectionValidationEntered.countDown();
                if (!allowRejectionValidation.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("rejection validation was not released");
                }
            }
            return invocation.callRealMethod();
        }).when(sessions).findCurrentSessionInCurrentTransaction(
                eq(admin.adminId), any(AdminSessionService.ActiveSession.class));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<MvcResult> future = null;
        try {
            future = executor.submit(() -> performSettings(
                    CONFIRM_PATH,
                    Map.of("enrollmentId", enrollmentId, "newTotp", "000000"),
                    current.cookie(), csrf, nextRemote()));
            assertThat(rejectionValidationEntered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(jdbc.sql("""
                            update portfolio.admin_session_metadata
                            set status='REVOKED', ended_at=clock_timestamp(),
                                revocation_reason='RACE_TEST', version=version+1
                            where id=:id and status='ACTIVE'
                            """)
                    .param("id", currentMetadataId)
                    .update()).isOne();
            allowRejectionValidation.countDown();

            MvcResult result = future.get(20, TimeUnit.SECONDS);
            assertProblem(result, 401, "AUTHENTICATION_FAILED");
            assertThat(pending(current.stablePrimaryId()).failures()).isOne();
            assertThat(auditCount("ADMIN_TOTP_CONFIRM_REJECTED", admin.adminId)).isOne();
            assertThat(jdbc.sql("""
                            select actor_admin_id from portfolio.audit_log
                            where action='ADMIN_TOTP_CONFIRM_REJECTED'
                              and target_id=:target
                            order by created_at desc limit 1
                            """)
                    .param("target", admin.adminId.toString())
                    .query(UUID.class)
                    .optional()).isEmpty();
            assertThat(jdbc.sql("""
                            select metadata ->> 'staleActor' from portfolio.audit_log
                            where action='ADMIN_TOTP_CONFIRM_REJECTED'
                              and target_id=:target
                            order by created_at desc limit 1
                            """)
                    .param("target", admin.adminId.toString())
                    .query(String.class)
                    .single()).isEqualTo("true");
        } finally {
            allowRejectionValidation.countDown();
            if (future != null && !future.isDone()) {
                future.cancel(true);
            }
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void restoredPendingEnrollmentWithoutItsProcessGateFailsClosed()
            throws Exception {
        Fixture admin = fixture();
        CsrfExchange csrf = csrf();
        AuthenticatedSession current = login(admin, csrf, nextRemote());
        JsonNode started = body(performSettings(
                ENROLLMENT_PATH,
                Map.of("currentPassword", ADMIN_PASSWORD,
                        "currentTotp", currentCode(admin.totpSecret)),
                current.cookie(), csrf, nextRemote()));
        PendingTotpEnrollment restored = pending(current.stablePrimaryId());
        AdminUser before = admins.findById(admin.adminId).orElseThrow();
        MockHttpSession restoredSession = new MockHttpSession();
        restoredSession.setAttribute(PendingTotpEnrollment.SESSION_KEY, roundTrip(restored));
        MockHttpServletRequest restoredRequest = requestWith(restoredSession);
        AdminSecuritySettingsService restarted = freshSettings();

        DomainException failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                DomainException.class,
                () -> restarted.confirmTotp(
                        admin.adminId,
                        sessions.requireActive(current.cookie().getValue()),
                        started.path("enrollmentId").asText(),
                        currentCode(queryValue(
                                started.path("provisioningUri").asText(), "secret")),
                        restoredRequest));

        assertThat(failure).isNotNull();
        assertThat(failure.code()).isEqualTo("TOTP_ENROLLMENT_EXPIRED");
        assertThat(failure.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(failure.getCause()).isNull();
        assertThat(restoredSession.getAttribute(PendingTotpEnrollment.SESSION_KEY)).isNull();
        AdminUser after = admins.findById(admin.adminId).orElseThrow();
        assertThat(after.version()).isEqualTo(before.version());
        assertThat(after.totpSecret()).isEqualTo(before.totpSecret());
    }

    @Test
    void pendingEnrollmentIsRejectedAtItsExactExpiryBoundary()
            throws Exception {
        Fixture admin = fixture();
        CsrfExchange csrf = csrf();
        AuthenticatedSession current = login(admin, csrf, nextRemote());
        AdjustableClock adjustable = new AdjustableClock(clock.instant());
        AdminSecuritySettingsService target = freshSettings(
                admins, recoveryCodes, recoveryCodeService, recoveryGenerator,
                totp, limiter, subjects, sessions, audit, adjustable);
        DirectEnrollment enrollment = directEnrollment(target, admin, current);
        adjustable.set(enrollment.pending().expiresAt());

        DomainException failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                DomainException.class,
                () -> target.confirmTotp(
                        admin.adminId,
                        enrollment.active(),
                        enrollment.material().enrollmentId().toString(),
                        currentCode(queryValue(
                                enrollment.material().provisioningUri(), "secret")),
                        enrollment.request()));

        assertEnrollmentExpired(failure);
        assertThat(enrollment.session().getAttribute(PendingTotpEnrollment.SESSION_KEY)).isNull();
        assertConfirmationStateUnchanged(enrollment);
    }

    @Test
    void pendingEnrollmentIssuedInTheFutureIsRejectedAndTombstoned()
            throws Exception {
        Fixture admin = fixture();
        CsrfExchange csrf = csrf();
        AuthenticatedSession current = login(admin, csrf, nextRemote());
        AdjustableClock adjustable = new AdjustableClock(clock.instant());
        AdminSecuritySettingsService target = freshSettings(
                admins, recoveryCodes, recoveryCodeService, recoveryGenerator,
                totp, limiter, subjects, sessions, audit, adjustable);
        DirectEnrollment enrollment = directEnrollment(target, admin, current);
        adjustable.set(enrollment.pending().issuedAt().minusNanos(1));

        DomainException failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                DomainException.class,
                () -> target.confirmTotp(
                        admin.adminId,
                        enrollment.active(),
                        enrollment.material().enrollmentId().toString(),
                        currentCode(queryValue(
                                enrollment.material().provisioningUri(), "secret")),
                        enrollment.request()));

        assertEnrollmentExpired(failure);
        assertThat(enrollment.session().getAttribute(PendingTotpEnrollment.SESSION_KEY)).isNull();
        assertConfirmationStateUnchanged(enrollment);
    }

    @Test
    void pendingEnrollmentCannotBeConfirmedFromAnotherCurrentSession()
            throws Exception {
        Fixture admin = fixture();
        CsrfExchange csrf = csrf();
        AuthenticatedSession current = login(admin, csrf, nextRemote());
        AdminSecuritySettingsService target = freshSettings();
        DirectEnrollment enrollment = directEnrollment(target, admin, current);
        AuthenticatedSession other = activeSessionWithoutSecurityVersionAdvance(admin);
        assertThat(admins.findById(admin.adminId).orElseThrow().version())
                .as("wrong-session isolation must not also create a stale-version candidate")
                .isEqualTo(enrollment.afterStart().version());
        MockHttpSession copiedSession = new MockHttpSession();
        copiedSession.setAttribute(
                PendingTotpEnrollment.SESSION_KEY,
                roundTrip(enrollment.pending()));
        MockHttpServletRequest copiedRequest = requestWith(copiedSession);

        DomainException failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                DomainException.class,
                () -> target.confirmTotp(
                        admin.adminId,
                        sessions.requireActive(other.cookie().getValue()),
                        enrollment.material().enrollmentId().toString(),
                        currentCode(queryValue(
                                enrollment.material().provisioningUri(), "secret")),
                        copiedRequest));

        assertEnrollmentExpired(failure);
        assertThat(copiedSession.getAttribute(PendingTotpEnrollment.SESSION_KEY)).isNull();
        assertConfirmationStateUnchanged(enrollment);
    }

    @Test
    void pendingEnrollmentWithAStaleSecurityVersionIsRejectedBeforeCredentialSwap()
            throws Exception {
        Fixture admin = fixture();
        CsrfExchange csrf = csrf();
        AuthenticatedSession current = login(admin, csrf, nextRemote());
        AdminSecuritySettingsService target = freshSettings();
        DirectEnrollment enrollment = directEnrollment(target, admin, current);
        long externallyAdvanced = transactions.execute(status ->
                admins.bumpSecurityVersion(admin.adminId));

        DomainException failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                DomainException.class,
                () -> target.confirmTotp(
                        admin.adminId,
                        enrollment.active(),
                        enrollment.material().enrollmentId().toString(),
                        currentCode(queryValue(
                                enrollment.material().provisioningUri(), "secret")),
                        enrollment.request()));

        assertEnrollmentExpired(failure);
        assertThat(enrollment.session().getAttribute(PendingTotpEnrollment.SESSION_KEY)).isNull();
        AdminUser after = admins.findById(admin.adminId).orElseThrow();
        assertThat(externallyAdvanced).isEqualTo(enrollment.afterStart().version() + 1);
        assertThat(after.version()).isEqualTo(externallyAdvanced);
        assertThat(after.totpSecret()).isEqualTo(enrollment.afterStart().totpSecret());
        assertThat(storedRecoveryHashes(admin.adminId))
                .isEqualTo(enrollment.recoveryAfterStart());
        assertThat(auditCount("ADMIN_TOTP_CHANGED", admin.adminId)).isZero();
    }

    @Test
    void publicationFailureExposesNoUriButRetainsTheCommittedFailClosedEpoch()
            throws Exception {
        Fixture admin = fixture();
        CsrfExchange csrf = csrf();
        AuthenticatedSession current = login(admin, csrf, nextRemote());
        AdminUser before = admins.findById(admin.adminId).orElseThrow();
        MockHttpSession brokenPublication = new MockHttpSession() {
            @Override
            public void setAttribute(String name, Object value) {
                if (PendingTotpEnrollment.SESSION_KEY.equals(name)) {
                    throw leakingDependencyFailure();
                }
                super.setAttribute(name, value);
            }
        };
        MockHttpServletRequest request = requestWith(brokenPublication);

        DomainException failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                DomainException.class,
                () -> freshSettings().beginTotpEnrollment(
                        admin.adminId,
                        sessions.requireActive(current.cookie().getValue()),
                        ADMIN_PASSWORD,
                        currentCode(admin.totpSecret),
                        request));

        assertThat(failure).isNotNull();
        assertThat(failure.code()).isEqualTo("INTERNAL_ERROR");
        assertThat(failure.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(failure.fieldErrors()).isEmpty();
        assertThat(failure.getCause()).isNull();
        assertThat(brokenPublication.getAttribute(PendingTotpEnrollment.SESSION_KEY)).isNull();
        AdminUser after = admins.findById(admin.adminId).orElseThrow();
        assertThat(after.version()).isEqualTo(before.version() + 1);
        assertThat(after.totpSecret()).isEqualTo(before.totpSecret());
        assertThat(auditCount("ADMIN_TOTP_ENROLLMENT_STARTED", admin.adminId)).isOne();
    }

    @Test
    void pendingRemovalFailureCannotReplayAnAlreadyCommittedTotpChange()
            throws Exception {
        Fixture admin = fixture();
        CsrfExchange csrf = csrf();
        AuthenticatedSession current = login(admin, csrf, nextRemote());
        AdminSecuritySettingsService fresh = freshSettings();
        MockHttpSession staleSession = new MockHttpSession() {
            @Override
            public void removeAttribute(String name) {
                if (PendingTotpEnrollment.SESSION_KEY.equals(name)) {
                    throw new IllegalStateException("synthetic removal failure");
                }
                super.removeAttribute(name);
            }
        };
        MockHttpServletRequest request = requestWith(staleSession);
        AdminSecuritySettingsService.EnrollmentMaterial material;
        try (AdminSecuritySettingsService.EnrollmentDelivery delivery =
                fresh.beginTotpEnrollment(
                        admin.adminId,
                        sessions.requireActive(current.cookie().getValue()),
                        ADMIN_PASSWORD,
                        currentCode(admin.totpSecret),
                        request)) {
            material = delivery.take();
        }
        String newCode = currentCode(queryValue(material.provisioningUri(), "secret"));
        List<String> delivered;
        try (AdminSecuritySettingsService.RecoveryCodesDelivery delivery = fresh.confirmTotp(
                admin.adminId,
                sessions.requireActive(current.cookie().getValue()),
                material.enrollmentId().toString(),
                newCode,
                request)) {
            delivered = delivery.take();
        }

        assertThat(delivered).hasSize(10).doesNotHaveDuplicates();
        assertThat(storedRecoveryHashes(admin.adminId))
                .allSatisfy(hash -> assertThat(delivered).noneMatch(hash::contains));
        assertThat(staleSession.getAttribute(PendingTotpEnrollment.SESSION_KEY))
                .isInstanceOf(PendingTotpEnrollment.class);
        assertThat(totp.verify(
                admin.adminId,
                admins.findById(admin.adminId).orElseThrow().totpSecret(),
                newCode)).isTrue();
        DomainException replay = org.assertj.core.api.Assertions.catchThrowableOfType(
                DomainException.class,
                () -> fresh.confirmTotp(
                        admin.adminId,
                        sessions.requireActive(current.cookie().getValue()),
                        material.enrollmentId().toString(),
                        newCode,
                        request));
        assertThat(replay).isNotNull();
        assertThat(replay.code()).isEqualTo("TOTP_ENROLLMENT_EXPIRED");
        assertThat(auditCount("ADMIN_TOTP_CHANGED", admin.adminId)).isOne();
    }

    @Test
    void physicalSessionDeletionFailureDoesNotRollBackCommittedCredentials()
            throws Exception {
        Fixture admin = fixture();
        CsrfExchange csrf = csrf();
        AuthenticatedSession current = login(admin, csrf, nextRemote());
        AuthenticatedSession other = login(admin, csrf, nextRemote());
        UUID otherMetadataId = metadataId(other.stablePrimaryId());
        long versionBefore = admins.findById(admin.adminId).orElseThrow().version();
        doThrow(leakingDependencyFailure()).when(sessions).deleteMarkedSessions(anyList());

        MvcResult result = performSettings(
                PASSWORD_PATH,
                Map.of("currentPassword", ADMIN_PASSWORD,
                        "currentTotp", currentCode(admin.totpSecret),
                        "newPassword", NEW_PASSWORD),
                current.cookie(), csrf, nextRemote());

        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(admins.findById(admin.adminId).orElseThrow().version())
                .isEqualTo(versionBefore + 1);
        assertThat(metadataStatus(current.stablePrimaryId())).isEqualTo("ACTIVE");
        assertThat(metadataStatus(other.stablePrimaryId())).isEqualTo("REVOKED");
        assertThat(springSessionExists(other.stablePrimaryId())).isTrue();
        assertThat(auditCount("ADMIN_PASSWORD_CHANGED", admin.adminId)).isOne();
        assertThat(auditTargetCount("SESSION_REVOKED", otherMetadataId.toString())).isOne();

        cleanup.runOnce();
        assertThat(springSessionExists(other.stablePrimaryId())).isFalse();
        assertThat(jdbc.sql("""
                        select session_primary_id from portfolio.admin_session_metadata
                        where id=:id
                        """)
                .param("id", otherMetadataId)
                .query(String.class)
                .optional()).isEmpty();
        assertThat(auditCount("ADMIN_PASSWORD_CHANGED", admin.adminId)).isOne();
        assertThat(auditTargetCount("SESSION_REVOKED", otherMetadataId.toString())).isOne();

        cleanup.runOnce();
        assertThat(auditCount("ADMIN_PASSWORD_CHANGED", admin.adminId)).isOne();
        assertThat(auditTargetCount("SESSION_REVOKED", otherMetadataId.toString())).isOne();
    }

    @Test
    void pendingPublicationRemovalAndPhysicalDeletionRunWithoutAmbientBusinessTransaction()
            throws Exception {
        Fixture admin = fixture();
        CsrfExchange csrf = csrf();
        AuthenticatedSession current = login(admin, csrf, nextRemote());
        AtomicInteger setCalls = new AtomicInteger();
        AtomicInteger removeCalls = new AtomicInteger();
        AtomicInteger ambientSetCalls = new AtomicInteger();
        AtomicInteger ambientRemoveCalls = new AtomicInteger();
        AtomicInteger deleteCalls = new AtomicInteger();
        AtomicInteger ambientDeleteCalls = new AtomicInteger();
        MockHttpSession probingSession = new MockHttpSession() {
            @Override
            public void setAttribute(String name, Object value) {
                if (PendingTotpEnrollment.SESSION_KEY.equals(name)) {
                    setCalls.incrementAndGet();
                    if (TransactionSynchronizationManager.isActualTransactionActive()) {
                        ambientSetCalls.incrementAndGet();
                    }
                }
                super.setAttribute(name, value);
            }

            @Override
            public void removeAttribute(String name) {
                if (PendingTotpEnrollment.SESSION_KEY.equals(name)) {
                    removeCalls.incrementAndGet();
                    if (TransactionSynchronizationManager.isActualTransactionActive()) {
                        ambientRemoveCalls.incrementAndGet();
                    }
                }
                super.removeAttribute(name);
            }
        };
        doAnswer(invocation -> {
            deleteCalls.incrementAndGet();
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                ambientDeleteCalls.incrementAndGet();
            }
            return invocation.callRealMethod();
        }).when(sessionRepository).deleteSpringSession(anyString());
        AdminSecuritySettingsService target = freshSettings();
        MockHttpServletRequest request = requestWith(probingSession);
        AdminSecuritySettingsService.EnrollmentMaterial material;
        try (AdminSecuritySettingsService.EnrollmentDelivery delivery =
                target.beginTotpEnrollment(
                        admin.adminId,
                        sessions.requireActive(current.cookie().getValue()),
                        ADMIN_PASSWORD,
                        currentCode(admin.totpSecret),
                        request)) {
            material = delivery.take();
        }
        AuthenticatedSession other = activeSessionWithoutSecurityVersionAdvance(admin);
        String newCode = currentCode(queryValue(material.provisioningUri(), "secret"));

        try (AdminSecuritySettingsService.RecoveryCodesDelivery delivery =
                target.confirmTotp(
                        admin.adminId,
                        sessions.requireActive(current.cookie().getValue()),
                        material.enrollmentId().toString(),
                        newCode,
                        request)) {
            assertThat(delivery.take()).hasSize(10).doesNotHaveDuplicates();
        }

        assertThat(setCalls).hasValue(1);
        assertThat(removeCalls).hasValueGreaterThanOrEqualTo(1);
        assertThat(deleteCalls).hasValue(1);
        assertThat(ambientSetCalls).hasValue(0);
        assertThat(ambientRemoveCalls).hasValue(0);
        assertThat(ambientDeleteCalls).hasValue(0);
        assertThat(metadataStatus(other.stablePrimaryId())).isEqualTo("REVOKED");
        assertThat(springSessionExists(other.stablePrimaryId())).isFalse();
    }

    @Test
    void concurrentConfirmationHasAtMostOneCommittedDelivery()
            throws Exception {
        Fixture admin = fixture();
        CsrfExchange csrf = csrf();
        AuthenticatedSession current = login(admin, csrf, nextRemote());
        AdminSessionService.ActiveSession active =
                sessions.requireActive(current.cookie().getValue());
        AdminSecuritySettingsService fresh = freshSettings();
        MockHttpSession firstSession = new MockHttpSession();
        MockHttpServletRequest firstRequest = requestWith(firstSession);
        AdminSecuritySettingsService.EnrollmentMaterial material;
        try (AdminSecuritySettingsService.EnrollmentDelivery delivery =
                fresh.beginTotpEnrollment(
                        admin.adminId,
                        active,
                        ADMIN_PASSWORD,
                        currentCode(admin.totpSecret),
                        firstRequest)) {
            material = delivery.take();
        }
        PendingTotpEnrollment pending = (PendingTotpEnrollment) firstSession.getAttribute(
                PendingTotpEnrollment.SESSION_KEY);
        MockHttpSession secondSession = new MockHttpSession();
        secondSession.setAttribute(
                PendingTotpEnrollment.SESSION_KEY,
                roundTrip(pending));
        MockHttpServletRequest secondRequest = requestWith(secondSession);
        long pendingVersion = admins.findById(admin.adminId).orElseThrow().version();
        String newCode = currentCode(queryValue(material.provisioningUri(), "secret"));
        CyclicBarrier bothPrepared = new CyclicBarrier(2);
        doAnswer(invocation -> {
            bothPrepared.await(10, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(recoveryCodeService).hashAll(anyList());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (MockHttpServletRequest request : List.of(firstRequest, secondRequest)) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    try (AdminSecuritySettingsService.RecoveryCodesDelivery delivery =
                            fresh.confirmTotp(
                                    admin.adminId,
                                    active,
                                    material.enrollmentId().toString(),
                                    newCode,
                                    request)) {
                        assertThat(delivery.take()).hasSize(10).doesNotHaveDuplicates();
                        return "SUCCESS";
                    } catch (DomainException failure) {
                        return failure.code();
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<String> results = List.of(
                    futures.get(0).get(20, TimeUnit.SECONDS),
                    futures.get(1).get(20, TimeUnit.SECONDS));
            assertThat(results).containsExactlyInAnyOrder(
                    "SUCCESS", "TOTP_ENROLLMENT_EXPIRED");
            assertThat(admins.findById(admin.adminId).orElseThrow().version())
                    .isEqualTo(pendingVersion + 1);
            assertThat(auditCount("ADMIN_TOTP_CHANGED", admin.adminId)).isOne();
            assertThat(firstSession.getAttribute(PendingTotpEnrollment.SESSION_KEY)).isNull();
            assertThat(secondSession.getAttribute(PendingTotpEnrollment.SESSION_KEY)).isNull();
        } finally {
            start.countDown();
            for (Future<?> future : futures) {
                if (!future.isDone()) {
                    future.cancel(true);
                }
            }
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void parentGateContentionIsRejectedImmediatelyBeforeASecondServiceEntry()
            throws Exception {
        Fixture admin = fixture();
        CsrfExchange csrf = csrf();
        AuthenticatedSession current = login(admin, csrf, nextRemote());
        AuthenticatedSession contender = login(admin, csrf, nextRemote());
        CountDownLatch firstInsideService = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger validations = new AtomicInteger();
        doAnswer(invocation -> {
            if (validations.incrementAndGet() == 1) {
                firstInsideService.countDown();
                if (!releaseFirst.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("first settings request was not released");
                }
            }
            return invocation.callRealMethod();
        }).when(sessions).findCurrentSessionInCurrentTransaction(
                eq(admin.adminId), any(AdminSessionService.ActiveSession.class));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<MvcResult> first = executor.submit(() -> performSettings(
                PASSWORD_PATH,
                Map.of("currentPassword", ADMIN_PASSWORD,
                        "currentTotp", currentCode(admin.totpSecret),
                        "newPassword", NEW_PASSWORD),
                current.cookie(), csrf, nextRemote()));
        try {
            assertThat(firstInsideService.await(10, TimeUnit.SECONDS)).isTrue();
            long startedAt = System.nanoTime();
            MvcResult contended = performSettings(
                    REGENERATE_PATH,
                    Map.of("currentPassword", ADMIN_PASSWORD,
                            "currentTotp", currentCode(admin.totpSecret)),
                    contender.cookie(), csrf, nextRemote());
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - startedAt);
            assertProblem(contended, 429, "RATE_LIMITED");
            assertThat(elapsedMillis).isLessThan(2_000L);
            assertThat(contended.getResponse().getHeader(HttpHeaders.RETRY_AFTER))
                    .isEqualTo("1");
            assertThat(validations).hasValue(1);
            releaseFirst.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(204);
        } finally {
            releaseFirst.countDown();
            if (!first.isDone()) {
                first.cancel(true);
            }
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void passwordFlowCompletesWithAOneConnectionPool()
            throws Exception {
        Fixture admin = fixture();
        CsrfExchange csrf = csrf();
        AuthenticatedSession current = login(admin, csrf, nextRemote());
        HikariDataSource hikari = (HikariDataSource) dataSource;
        int previousMinimum = hikari.getMinimumIdle();
        int previousMaximum = hikari.getMaximumPoolSize();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<MvcResult> future = null;
        try {
            hikari.setMinimumIdle(0);
            hikari.setMaximumPoolSize(1);
            hikari.getHikariPoolMXBean().softEvictConnections();
            future = executor.submit(() -> performSettings(
                    PASSWORD_PATH,
                    Map.of("currentPassword", ADMIN_PASSWORD,
                            "currentTotp", currentCode(admin.totpSecret),
                            "newPassword", NEW_PASSWORD),
                    current.cookie(), csrf, nextRemote()));
            MvcResult result = future.get(20, TimeUnit.SECONDS);
            assertThat(result.getResponse().getStatus()).isEqualTo(204);
            assertThat(passwords.matches(
                    NEW_PASSWORD,
                    admins.findById(admin.adminId).orElseThrow().passwordHash())).isTrue();
        } finally {
            if (future != null && !future.isDone()) {
                future.cancel(true);
            }
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            hikari.setMaximumPoolSize(previousMaximum);
            hikari.setMinimumIdle(previousMinimum);
        }
    }

    private Fixture fixture() {
        assertThat(fixture).as("one fixture per test").isNull();
        fixture = new Fixture();
        return fixture;
    }

    private void assertSecurityStateUnchanged(
            UUID adminId,
            AuthenticatedSession current,
            AdminUser before,
            List<String> recoveryBefore) {
        AdminUser after = admins.findById(adminId).orElseThrow();
        assertThat(after.version()).isEqualTo(before.version());
        assertThat(after.passwordHash()).isEqualTo(before.passwordHash());
        assertThat(after.totpSecret()).isEqualTo(before.totpSecret());
        assertThat(storedRecoveryHashes(adminId)).isEqualTo(recoveryBefore);
        assertThat(metadataStatus(current.stablePrimaryId())).isEqualTo("ACTIVE");
        assertThat(springSessionExists(current.stablePrimaryId())).isTrue();
    }

    private AdminSecuritySettingsService freshSettings() {
        return freshSettings(
                admins,
                recoveryCodes,
                recoveryCodeService,
                recoveryGenerator,
                totp,
                limiter,
                subjects,
                sessions,
                audit,
                clock);
    }

    private AdminSecuritySettingsService freshSettings(
            AdminUserRepository targetAdmins,
            RecoveryCodeRepository targetRecoveryCodes,
            RecoveryCodeService targetRecoveryCodeService,
            RecoveryCodeGenerator targetRecoveryGenerator,
            TotpService targetTotp,
            RateLimiter targetLimiter,
            LoginSubjectHasher targetSubjects,
            AdminSessionService targetSessions,
            AuditService targetAudit,
            Clock targetClock) {
        return new AdminSecuritySettingsService(
                targetAdmins,
                targetRecoveryCodes,
                targetRecoveryCodeService,
                targetRecoveryGenerator,
                passwords,
                passwordPolicy,
                targetTotp,
                targetLimiter,
                targetSubjects,
                targetSessions,
                targetAudit,
                transactions,
                targetClock,
                context.getBean(RateLimitProperties.class));
    }

    private DirectEnrollment directEnrollment(
            AdminSecuritySettingsService target,
            Fixture admin,
            AuthenticatedSession current) throws Exception {
        AdminSessionService.ActiveSession active =
                sessions.requireActive(current.cookie().getValue());
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest request = requestWith(session);
        AdminSecuritySettingsService.EnrollmentMaterial material;
        try (AdminSecuritySettingsService.EnrollmentDelivery delivery =
                target.beginTotpEnrollment(
                        admin.adminId,
                        active,
                        ADMIN_PASSWORD,
                        currentCode(admin.totpSecret),
                        request)) {
            material = delivery.take();
            assertThatThrownBy(delivery::take)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("enrollment delivery is not available");
        }
        PendingTotpEnrollment pending = (PendingTotpEnrollment) session.getAttribute(
                PendingTotpEnrollment.SESSION_KEY);
        assertThat(pending).isNotNull();
        return new DirectEnrollment(
                active,
                session,
                request,
                material,
                pending,
                admins.findById(admin.adminId).orElseThrow(),
                storedRecoveryHashes(admin.adminId));
    }

    private DomainException confirmationFailure(
            AdminSecuritySettingsService target,
            DirectEnrollment enrollment,
            String newCode) {
        return org.assertj.core.api.Assertions.catchThrowableOfType(
                DomainException.class,
                () -> {
                    try (AdminSecuritySettingsService.RecoveryCodesDelivery ignored =
                            target.confirmTotp(
                                    enrollment.pending().adminId(),
                                    enrollment.active(),
                                    enrollment.material().enrollmentId().toString(),
                                    newCode,
                                    enrollment.request())) {
                        throw new AssertionError("confirmation unexpectedly returned a delivery");
                    }
                });
    }

    private void assertRetryableConfirmationRollback(
            DirectEnrollment enrollment,
            DomainException failure,
            AuthenticatedSession other) {
        assertFreshInternal(failure);
        assertThat(enrollment.session().getAttribute(PendingTotpEnrollment.SESSION_KEY))
                .isEqualTo(enrollment.pending());
        assertConfirmationStateUnchanged(enrollment);
        if (other != null) {
            assertThat(metadataStatus(other.stablePrimaryId())).isEqualTo("ACTIVE");
            assertThat(springSessionExists(other.stablePrimaryId())).isTrue();
        }
    }

    private void assertSuccessfulConfirmationRetry(
            AdminSecuritySettingsService target,
            DirectEnrollment enrollment,
            String newCode) {
        List<String> delivered;
        try (AdminSecuritySettingsService.RecoveryCodesDelivery delivery =
                target.confirmTotp(
                        enrollment.pending().adminId(),
                        enrollment.active(),
                        enrollment.material().enrollmentId().toString(),
                        newCode,
                        enrollment.request())) {
            delivered = delivery.take();
            assertThatThrownBy(delivery::take)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("recovery-code delivery is not available");
        }
        assertThat(delivered).hasSize(10).doesNotHaveDuplicates();
        assertThat(enrollment.session().getAttribute(PendingTotpEnrollment.SESSION_KEY)).isNull();
        AdminUser after = admins.findById(enrollment.pending().adminId()).orElseThrow();
        assertThat(after.version()).isEqualTo(enrollment.afterStart().version() + 1);
        assertThat(totp.verify(enrollment.pending().adminId(), after.totpSecret(), newCode))
                .isTrue();
        assertThat(storedRecoveryHashes(enrollment.pending().adminId()))
                .hasSize(10)
                .allSatisfy(hash -> assertThat(delivered).noneMatch(hash::contains));
        assertThat(auditCount("ADMIN_TOTP_CHANGED", enrollment.pending().adminId())).isOne();
    }

    private void assertConfirmationStateUnchanged(DirectEnrollment enrollment) {
        AdminUser after = admins.findById(enrollment.pending().adminId()).orElseThrow();
        assertThat(after.version()).isEqualTo(enrollment.afterStart().version());
        assertThat(after.passwordHash()).isEqualTo(enrollment.afterStart().passwordHash());
        assertThat(after.totpSecret()).isEqualTo(enrollment.afterStart().totpSecret());
        assertThat(storedRecoveryHashes(enrollment.pending().adminId()))
                .isEqualTo(enrollment.recoveryAfterStart());
        assertThat(auditCount("ADMIN_TOTP_CHANGED", enrollment.pending().adminId())).isZero();
    }

    private static void assertEnrollmentExpired(DomainException failure) {
        assertThat(failure).isNotNull();
        assertThat(failure.code()).isEqualTo("TOTP_ENROLLMENT_EXPIRED");
        assertThat(failure.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(failure.fieldErrors()).isEmpty();
        assertThat(failure).hasNoCause();
    }

    private static void assertFreshInternal(DomainException failure) {
        assertThat(failure).isNotNull();
        assertThat(failure.code()).isEqualTo("INTERNAL_ERROR");
        assertThat(failure.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(failure.fieldErrors()).isEmpty();
        assertThat(failure).hasNoCause();
        assertThat(failure.toString()).doesNotContain(
                "DEPENDENCY_LEAK", "credential-secret", "credential-cause-secret");
    }

    private static MockHttpServletRequest requestWith(MockHttpSession session) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        request.setRemoteAddr(nextRemote());
        request.addHeader(HttpHeaders.USER_AGENT, "Task12-Restored-Session-Test/1.0");
        return request;
    }

    private void assertSanitizedInternal(MvcResult result) throws Exception {
        assertProblem(result, 500, "INTERNAL_ERROR");
        assertThat(body(result).path("fieldErrors").isEmpty()).isTrue();
        assertThat(body(result).has("cause")).isFalse();
        assertThat(result.getResolvedException()).isInstanceOfSatisfying(
                DomainException.class, AdminSecuritySettingsTest::assertFreshInternal);
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(
                        "DEPENDENCY_LEAK", "credential-secret", "credential-cause-secret");
    }

    private static DomainException leakingDependencyFailure() {
        return new DomainException(
                "DEPENDENCY_LEAK",
                HttpStatus.I_AM_A_TEAPOT,
                Map.of("credential", "credential-secret"));
    }

    private static DomainException forgedAuthenticationRequiredFailure() {
        DomainException failure = new DomainException(
                "AUTHENTICATION_REQUIRED",
                HttpStatus.UNAUTHORIZED,
                Map.of("credential", "credential-secret"));
        failure.initCause(new IllegalStateException("credential-cause-secret"));
        return failure;
    }

    private CsrfExchange csrf() throws Exception {
        MvcResult source = mvc.perform(get(CSRF_PATH)).andReturn();
        assertThat(source.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = body(source);
        Cookie cookie = findResponseCookie(source, XSRF_COOKIE)
                .orElseThrow(() -> new AssertionError("CSRF cookie was not set"));
        return new CsrfExchange(
                body.path("headerName").asText(), body.path("token").asText(), cookie);
    }

    private AuthenticatedSession login(Fixture admin, CsrfExchange csrf, String remote)
            throws Exception {
        MvcResult password = mvc.perform(withCsrf(post(PASSWORD_LOGIN_PATH)
                        .with(remote(remote))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of(
                                "username", ADMIN_USERNAME,
                                "password", ADMIN_PASSWORD))), csrf))
                .andReturn();
        assertThat(password.getResponse().getStatus()).isEqualTo(200);
        Cookie pendingCookie = findResponseCookie(password, SESSION_COOKIE)
                .orElseThrow(() -> new AssertionError("pending session cookie was not set"));
        String stable = requireStablePrimaryId(pendingCookie.getValue());
        admin.primaryIds.add(stable);

        MvcResult second = mvc.perform(withCsrf(post(SECOND_FACTOR_PATH)
                        .cookie(pendingCookie)
                        .with(remote(remote))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of(
                                "method", "TOTP",
                                "code", currentCode(admin.totpSecret)))), csrf))
                .andReturn();
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        Cookie active = findResponseCookie(second, SESSION_COOKIE).orElse(pendingCookie);
        assertThat(requireStablePrimaryId(active.getValue())).isEqualTo(stable);
        return new AuthenticatedSession(active, stable);
    }

    private AuthenticatedSession activeSessionWithoutSecurityVersionAdvance(Fixture admin) {
        String primaryId = UUID.randomUUID().toString();
        String publicId = UUID.randomUUID().toString();
        long now = clock.instant().toEpochMilli();
        jdbc.sql("""
                        insert into portfolio.spring_session
                            (primary_id, session_id, creation_time, last_access_time,
                             max_inactive_interval, expiry_time, principal_name)
                        values (:primaryId, :publicId, :now, :now, :inactive,
                                :expiry, null)
                        """)
                .param("primaryId", primaryId)
                .param("publicId", publicId)
                .param("now", now)
                .param("inactive", 1_800)
                .param("expiry", now + Duration.ofMinutes(30).toMillis())
                .update();
        sessions.start(admin.adminId, primaryId, "Task12 wrong-session isolation");
        admin.primaryIds.add(primaryId);
        return new AuthenticatedSession(new Cookie(SESSION_COOKIE, publicId), primaryId);
    }

    private MvcResult performSettings(
            String path,
            Map<String, String> values,
            Cookie session,
            CsrfExchange csrf,
            String remote) throws Exception {
        return mvc.perform(withCsrf(post(path)
                        .cookie(session)
                        .with(remote(remote))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(values)), csrf))
                .andReturn();
    }

    private static MockHttpServletRequestBuilder withCsrf(
            MockHttpServletRequestBuilder request, CsrfExchange csrf) {
        return request.cookie(csrf.cookie()).header(csrf.headerName(), csrf.token());
    }

    private static RequestPostProcessor remote(String address) {
        return request -> {
            request.setRemoteAddr(address);
            request.addHeader(HttpHeaders.USER_AGENT, "Task12-Integration-Test/1.0");
            return request;
        };
    }

    private static String nextRemote() {
        int sequence = REMOTE_SEQUENCE.incrementAndGet();
        int third = 1 + Math.floorMod(sequence / 250, 250);
        int fourth = 1 + Math.floorMod(sequence, 250);
        return "198.19." + third + "." + fourth;
    }

    private String currentCode(String secret) throws Exception {
        return totpCodes.generate(secret, totpTime.getTime() / 30);
    }

    private String oldFactorRejectedByReplacement(
            UUID adminId,
            String oldPlaintext,
            EncryptedTotpSecret oldEncrypted,
            EncryptedTotpSecret replacement) throws Exception {
        long currentCounter = totpTime.getTime() / 30;
        for (long offset = -2; offset <= 2; offset++) {
            String candidate = totpCodes.generate(oldPlaintext, currentCounter + offset);
            if (totp.verify(adminId, oldEncrypted, candidate)
                    && !totp.verify(adminId, replacement, candidate)) {
                return candidate;
            }
        }
        throw new AssertionError("no currently valid old factor was rejected by the replacement");
    }

    private String requireStablePrimaryId(String publicId) {
        return jdbc.sql("select primary_id from portfolio.spring_session where session_id=:id")
                .param("id", publicId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new AssertionError("Spring Session row is missing"));
    }

    private PendingTotpEnrollment pending(String primaryId) throws Exception {
        byte[] bytes = sessionAttribute(primaryId, PendingTotpEnrollment.SESSION_KEY)
                .orElseThrow(() -> new AssertionError("pending enrollment is missing"));
        Object value = deserialize(bytes);
        assertThat(value).isInstanceOf(PendingTotpEnrollment.class);
        return (PendingTotpEnrollment) value;
    }

    private Optional<byte[]> sessionAttribute(String primaryId, String name) {
        return jdbc.sql("""
                        select attribute_bytes from portfolio.spring_session_attributes
                        where session_primary_id=:primaryId and attribute_name=:name
                        """)
                .param("primaryId", primaryId)
                .param("name", name)
                .query((rs, rowNumber) -> rs.getBytes("attribute_bytes"))
                .optional();
    }

    private String sessionAttributeBytes(String primaryId) {
        ByteArrayOutputStream combined = new ByteArrayOutputStream();
        jdbc.sql("""
                        select attribute_bytes from portfolio.spring_session_attributes
                        where session_primary_id=:primaryId order by attribute_name
                        """)
                .param("primaryId", primaryId)
                .query((rs, rowNumber) -> rs.getBytes("attribute_bytes"))
                .list()
                .forEach(combined::writeBytes);
        return combined.toString(StandardCharsets.ISO_8859_1);
    }

    private UUID metadataId(String primaryId) {
        return jdbc.sql("""
                        select id from portfolio.admin_session_metadata
                        where session_primary_id=:primaryId
                        """)
                .param("primaryId", primaryId)
                .query(UUID.class)
                .single();
    }

    private String metadataStatus(String primaryId) {
        return jdbc.sql("""
                        select status from portfolio.admin_session_metadata
                        where session_primary_id=:primaryId
                           or id in (
                               select id from portfolio.admin_session_metadata
                               where admin_id=:adminId and session_primary_id is null
                               order by created_at desc limit 1)
                        order by (session_primary_id=:primaryId) desc nulls last
                        limit 1
                        """)
                .param("primaryId", primaryId)
                .param("adminId", fixture.adminId)
                .query(String.class)
                .single();
    }

    private boolean springSessionExists(String primaryId) {
        return jdbc.sql("select count(*) from portfolio.spring_session where primary_id=:id")
                .param("id", primaryId).query(Long.class).single() == 1;
    }

    private long springSessionCount() {
        return jdbc.sql("select count(*) from portfolio.spring_session")
                .query(Long.class).single();
    }

    private List<String> storedRecoveryHashes(UUID adminId) {
        return jdbc.sql("""
                        select code_hash from portfolio.totp_recovery_code
                        where admin_id=:adminId order by created_at, id
                        """)
                .param("adminId", adminId)
                .query(String.class)
                .list();
    }

    private long auditCount(String action, UUID adminId) {
        return jdbc.sql("""
                        select count(*) from portfolio.audit_log
                        where action=:action and target_id=:target
                        """)
                .param("action", action)
                .param("target", adminId.toString())
                .query(Long.class)
                .single();
    }

    private long auditTargetCount(String action, String targetId) {
        return jdbc.sql("""
                        select count(*) from portfolio.audit_log
                        where action=:action and target_id=:target
                        """)
                .param("action", action)
                .param("target", targetId)
                .query(Long.class)
                .single();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    private JsonNode normalizedProblem(MvcResult result) throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode normalized =
                (com.fasterxml.jackson.databind.node.ObjectNode) body(result).deepCopy();
        normalized.remove("traceId");
        return normalized;
    }

    private void assertProblem(MvcResult result, int status, String code) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertNoStore(result);
        assertThat(body(result).path("code").asText()).isEqualTo(code);
    }

    private static void assertNoStore(MvcResult result) {
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL))
                .contains("no-store");
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
                return Optional.of(new Cookie(
                        name, first.substring(separator + 1).trim()));
            }
        }
        return Optional.empty();
    }

    private static Object deserialize(byte[] value) throws IOException, ClassNotFoundException {
        try (ObjectInputStream input = new ObjectInputStream(
                new ByteArrayInputStream(value))) {
            return input.readObject();
        }
    }

    private static String queryValue(String uri, String name) {
        return Arrays.stream(URI.create(uri).getRawQuery().split("&"))
                .map(value -> value.split("=", 2))
                .filter(value -> URLDecoder.decode(value[0], StandardCharsets.UTF_8)
                        .equals(name))
                .map(value -> URLDecoder.decode(value[1], StandardCharsets.UTF_8))
                .findFirst()
                .orElseThrow();
    }

    private static List<Endpoint> endpoints() {
        return List.of(
                new Endpoint(PASSWORD_PATH, Map.of(
                        "currentPassword", ADMIN_PASSWORD,
                        "currentTotp", "123456",
                        "newPassword", NEW_PASSWORD)),
                new Endpoint(ENROLLMENT_PATH, Map.of(
                        "currentPassword", ADMIN_PASSWORD,
                        "currentTotp", "123456")),
                new Endpoint(CONFIRM_PATH, Map.of(
                        "enrollmentId", UUID.randomUUID().toString(),
                        "newTotp", "123456")),
                new Endpoint(REGENERATE_PATH, Map.of(
                        "currentPassword", ADMIN_PASSWORD,
                        "currentTotp", "123456")));
    }

    private static Object roundTrip(Serializable value) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(value);
        }
        try (ObjectInputStream input = new ObjectInputStream(
                new ByteArrayInputStream(bytes.toByteArray()))) {
            return input.readObject();
        }
    }

    private static void assertServletOnlyService(Class<?> type) {
        assertThat(type.getAnnotation(Service.class)).isNotNull();
        assertServletOnly(type);
    }

    private static void assertServletOnly(Class<?> type) {
        ConditionalOnWebApplication condition =
                type.getAnnotation(ConditionalOnWebApplication.class);
        assertThat(condition).isNotNull();
        assertThat(condition.type()).isEqualTo(Type.SERVLET);
    }

    private static void assertPostRoute(String methodName, String route) throws Exception {
        Method method = Arrays.stream(AdminSecuritySettingsController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        assertThat(method.getAnnotation(PostMapping.class).value()).containsExactly(route);
    }

    private final class Fixture implements AutoCloseable {
        private final UUID adminId = UUID.randomUUID();
        private final String totpSecret;
        private final Set<String> primaryIds = new LinkedHashSet<>();

        private Fixture() {
            TotpService.Enrollment enrollment = totp.beginEnrollment(adminId, ADMIN_USERNAME);
            totpSecret = enrollment.plaintextSecret();
            Instant now = Instant.now();
            admins.insert(new AdminUser(
                    adminId,
                    ADMIN_USERNAME,
                    passwords.encode(ADMIN_PASSWORD),
                    AdminStatus.ACTIVE,
                    enrollment.encryptedSecret(),
                    null,
                    0,
                    now,
                    now));
            recoveryCodes.replace(
                    adminId, recoveryCodeService.hashAll(List.of(OLD_RECOVERY_CODE)));
        }

        @Override
        public void close() {
            JdbcClient owner = migratorJdbc();
            owner.sql("delete from portfolio.admin_session_metadata where admin_id=:id")
                    .param("id", adminId).update();
            for (String primaryId : primaryIds) {
                owner.sql("delete from portfolio.spring_session where primary_id=:id")
                        .param("id", primaryId).update();
            }
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
            } finally {
                owner.sql("""
                                alter table portfolio.audit_log
                                enable trigger audit_log_reject_mutation
                                """).update();
            }
            owner.sql("delete from portfolio.admin_user where id=:id")
                    .param("id", adminId).update();
        }
    }

    private record CsrfExchange(String headerName, String token, Cookie cookie) {
    }

    private record AuthenticatedSession(Cookie cookie, String stablePrimaryId) {
    }

    private record DirectEnrollment(
            AdminSessionService.ActiveSession active,
            MockHttpSession session,
            MockHttpServletRequest request,
            AdminSecuritySettingsService.EnrollmentMaterial material,
            PendingTotpEnrollment pending,
            AdminUser afterStart,
            List<String> recoveryAfterStart) {
        private DirectEnrollment {
            recoveryAfterStart = List.copyOf(recoveryAfterStart);
        }
    }

    private static final class AdjustableClock extends Clock {
        private Instant current;

        private AdjustableClock(Instant current) {
            this.current = current;
        }

        private void set(Instant value) {
            current = value;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("test clock supports UTC only");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }

    private record Endpoint(String path, Map<String, String> map) {
    }

}
