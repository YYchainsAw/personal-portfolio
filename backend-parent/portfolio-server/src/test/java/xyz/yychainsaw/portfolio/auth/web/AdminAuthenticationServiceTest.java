package xyz.yychainsaw.portfolio.auth.web;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.session.web.http.HttpSessionIdResolver;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import xyz.yychainsaw.portfolio.audit.AuditCommand;
import xyz.yychainsaw.portfolio.audit.AuditOutcome;
import xyz.yychainsaw.portfolio.audit.AuditService;
import xyz.yychainsaw.portfolio.auth.RecoveryCodeService;
import xyz.yychainsaw.portfolio.auth.crypto.EncryptedTotpSecret;
import xyz.yychainsaw.portfolio.auth.crypto.TotpProperties;
import xyz.yychainsaw.portfolio.auth.crypto.TotpService;
import xyz.yychainsaw.portfolio.auth.model.AdminStatus;
import xyz.yychainsaw.portfolio.auth.model.AdminUser;
import xyz.yychainsaw.portfolio.auth.persistence.AdminUserRepository;
import xyz.yychainsaw.portfolio.auth.session.AdminSessionService;
import xyz.yychainsaw.portfolio.auth.session.AdminSessionService.ActiveSession;
import xyz.yychainsaw.portfolio.auth.session.ClientSummaryFactory;
import xyz.yychainsaw.portfolio.auth.session.TrustedClientAddressResolver;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.common.ratelimit.RateLimitDecision;
import xyz.yychainsaw.portfolio.common.ratelimit.RateLimitProperties;
import xyz.yychainsaw.portfolio.common.ratelimit.RateLimiter;

class AdminAuthenticationServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-15T10:15:30Z");
    private static final UUID ADMIN_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID METADATA_ID =
            UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final String PUBLIC_ID = "30000000-0000-0000-0000-000000000003";
    private static final String PRIMARY_ID = "40000000-0000-0000-0000-000000000004";
    private static final String USERNAME = "YYchainsaw";
    private static final String PASSWORD = "correct horse battery staple";
    private static final String STORED_HASH = "$argon2id$stored-password-hash";
    private static final String DUMMY_HASH = "$argon2id$dummy-password-hash";
    private static final String CLIENT_SUMMARY = "Chrome/Linux @ 203.0.113.x";

    @AfterEach
    void clearThreadState() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void principalAndPendingAreSerializableBoundedAndRedacted() throws Exception {
        String maximumUsername = "a".repeat(64);
        AdminPrincipal principal = new AdminPrincipal(ADMIN_ID, maximumUsername);
        PendingSecondFactor pending =
                new PendingSecondFactor(UUID.randomUUID(), ADMIN_ID, 7, NOW, 2);

        assertThat(roundTrip(principal)).isEqualTo(principal);
        assertThat(roundTrip(pending)).isEqualTo(pending);
        assertThat(principal.getName()).isEqualTo(maximumUsername);
        assertThat(ObjectStreamClass.lookup(AdminPrincipal.class).getSerialVersionUID()).isOne();
        assertThat(ObjectStreamClass.lookup(PendingSecondFactor.class).getSerialVersionUID()).isOne();
        assertThat(PendingSecondFactor.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly("challengeId", "adminId", "adminVersion", "issuedAt", "failures");
        assertThat(pending.failedAgain().failures()).isEqualTo(3);
        assertThat(pending.failures()).isEqualTo(2);
        assertThat(principal.toString()).contains("<redacted>").doesNotContain(maximumUsername);
        assertThat(pending.toString())
                .contains("<redacted>")
                .doesNotContain(pending.challengeId().toString(), ADMIN_ID.toString());
        assertThatThrownBy(() -> new AdminPrincipal(ADMIN_ID, " two spaces "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdminPrincipal(ADMIN_ID, "\u6613\u5609\u8f69"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdminPrincipal(null, USERNAME))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new PendingSecondFactor(
                        UUID.randomUUID(), ADMIN_ID, -1, NOW, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PendingSecondFactor(
                        UUID.randomUUID(), ADMIN_ID, 0, NOW, Integer.MAX_VALUE).failedAgain())
                .isInstanceOf(ArithmeticException.class);
        assertThat(SecondFactorMethod.values())
                .containsExactly(SecondFactorMethod.TOTP, SecondFactorMethod.RECOVERY_CODE);
    }

    @Test
    void authDtosKeepWireFieldsButRedactEverySecretDiagnostic() throws Exception {
        ObjectMapper json = new ObjectMapper();
        AdminAuthController.PasswordStageRequest password =
                new AdminAuthController.PasswordStageRequest(USERNAME, PASSWORD);
        AdminAuthController.SecondFactorRequest factor =
                new AdminAuthController.SecondFactorRequest(SecondFactorMethod.TOTP, "123456");
        AdminAuthController.CsrfResponse csrf =
                new AdminAuthController.CsrfResponse("X-XSRF-TOKEN", "_csrf", "csrf-secret");

        assertThat(json.readTree(json.writeValueAsBytes(password)).path("username").asText())
                .isEqualTo(USERNAME);
        assertThat(json.readTree(json.writeValueAsBytes(password)).path("password").asText())
                .isEqualTo(PASSWORD);
        assertThat(json.readTree(json.writeValueAsBytes(factor)).path("method").asText())
                .isEqualTo("TOTP");
        assertThat(json.readTree(json.writeValueAsBytes(csrf)).path("token").asText())
                .isEqualTo("csrf-secret");
        assertThat(password.toString()).contains("<redacted>").doesNotContain(PASSWORD);
        assertThat(factor.toString()).contains("<redacted>").doesNotContain("123456");
        assertThat(csrf.toString()).contains("<redacted>").doesNotContain("csrf-secret");
    }

    @Test
    void requestValidationHasOnlyCheapUnitCeilingsAndStructuralMethod() throws Exception {
        ObjectMapper json = new ObjectMapper();
        for (String body : List.of(
                "{\"code\":\"123456\"}",
                "{\"method\":null,\"code\":\"123456\"}",
                "{\"method\":\"SMS\",\"code\":\"123456\"}")) {
            assertThatThrownBy(() -> json.readValue(
                            body, AdminAuthController.SecondFactorRequest.class))
                    .isInstanceOf(Exception.class);
        }
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertThat(validator.validate(
                            new AdminAuthController.PasswordStageRequest(null, null)))
                    .isEmpty();
            assertThat(validator.validate(new AdminAuthController.PasswordStageRequest(
                            "u".repeat(129), "p".repeat(257))))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .containsExactlyInAnyOrder("username", "password");
            assertThat(validator.validate(new AdminAuthController.SecondFactorRequest(
                            SecondFactorMethod.TOTP, "1".repeat(65))))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .containsExactly("code");
        }
    }

    @Test
    void serviceHasOnlyTheFrozenPublicApiAndProxyableRequiredTransaction() throws Exception {
        assertThat(Modifier.isFinal(AdminAuthenticationService.class.getModifiers())).isFalse();
        ConditionalOnWebApplication condition =
                AdminAuthenticationService.class.getAnnotation(ConditionalOnWebApplication.class);
        assertThat(condition).isNotNull();
        assertThat(condition.type()).isEqualTo(Type.SERVLET);

        Method password = AdminAuthenticationService.class.getDeclaredMethod(
                "passwordStage", String.class, String.class, HttpServletRequest.class);
        Method factor = AdminAuthenticationService.class.getDeclaredMethod(
                "secondFactor", SecondFactorMethod.class, String.class,
                HttpServletRequest.class, HttpServletResponse.class);
        assertThat(password.getReturnType()).isEqualTo(Instant.class);
        assertThat(factor.getReturnType()).isEqualTo(Optional.class);
        assertThat(Modifier.isPublic(password.getModifiers())).isTrue();
        assertThat(Modifier.isPublic(factor.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(factor.getModifiers())).isFalse();
        Transactional transactional = factor.getAnnotation(Transactional.class);
        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRED);
    }

    @Test
    void constructorRejectsEveryNullDependencyAndCreatesExactlyOneDummyHash() throws Exception {
        Fixture fixture = new Fixture();
        assertThat(fixture.passwords.encodeInputs).hasSize(1);
        assertThat(fixture.passwords.encodeInputs.get(0))
                .isNotBlank()
                .doesNotContain(USERNAME, PASSWORD, STORED_HASH);

        Constructor<?> constructor = Arrays.stream(AdminAuthenticationService.class.getConstructors())
                .filter(candidate -> candidate.getParameterCount() == 14)
                .findFirst()
                .orElseThrow();
        Object[] dependencies = fixture.dependencies();
        for (int index = 0; index < dependencies.length; index++) {
            Object[] withNull = dependencies.clone();
            withNull[index] = null;
            assertThatThrownBy(() -> constructor.newInstance(withNull))
                    .isInstanceOf(InvocationTargetException.class)
                    .satisfies(thrown -> assertThat(thrown.getCause())
                            .isInstanceOf(RuntimeException.class)
                            .hasNoCause());
        }
    }

    @Test
    void dummyHashProviderFailuresAreFixedAndCauseFree() {
        RecordingPasswordEncoder throwing = new RecordingPasswordEncoder();
        throwing.encodeFailure = new IllegalStateException("raw-provider-detail");
        assertThatThrownBy(() -> new Fixture(16, 2, throwing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("dummy password hashing failed")
                .hasNoCause();

        RecordingPasswordEncoder invalid = new RecordingPasswordEncoder();
        invalid.encoded = " ";
        assertThatThrownBy(() -> new Fixture(16, 2, invalid))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("password provider returned an invalid dummy hash")
                .hasNoCause();
    }

    @Test
    void loginSubjectsAreKeyedBoundedDomainSeparatedAndWiped() throws Exception {
        TrustedClientAddressResolver addresses = mock(TrustedClientAddressResolver.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(addresses.resolve(request)).thenReturn("203.0.113.17");
        byte[] suppliedKey = deterministicKey();
        LoginSubjectHasher hasher = new LoginSubjectHasher(addresses, suppliedKey);

        String login = hasher.hash(request, "  YYCHAINSaw  ");
        assertThat(hasher.hash(request, "yychainsaw")).isEqualTo(login);
        assertThat(login).matches("[0-9a-f]{64}").doesNotContain(USERNAME, "203.0.113.17");
        assertThat(Set.of(
                        login,
                        hasher.hashSecurity(request, ADMIN_ID),
                        hasher.hashSecondFactor(ADMIN_ID),
                        hasher.hashSessionId(PUBLIC_ID)))
                .hasSize(4);
        assertThat(hasher.hash(request, null))
                .isEqualTo(hasher.hash(request, "\uD800"))
                .isEqualTo(hasher.hash(request, "x".repeat(129)));
        suppliedKey[0] ^= 0x7f;
        assertThat(hasher.hash(request, "yychainsaw")).isEqualTo(login);
        assertThat(hasher.toString()).contains("<redacted>").doesNotContain(login, PUBLIC_ID);

        Method destroy = Arrays.stream(LoginSubjectHasher.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PreDestroy.class))
                .findFirst()
                .orElseThrow();
        destroy.setAccessible(true);
        destroy.invoke(hasher);
        for (Field field : LoginSubjectHasher.class.getDeclaredFields()) {
            if (field.getType() == byte[].class && !Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                assertThat((byte[]) field.get(hasher)).containsOnly((byte) 0);
            }
        }
    }

    @Test
    void hasherHasOneAutowiredProductionConstructorAndOnePackagePrivateTestConstructor() {
        assertThat(Modifier.isFinal(LoginSubjectHasher.class.getModifiers())).isTrue();
        Constructor<?>[] constructors = LoginSubjectHasher.class.getDeclaredConstructors();
        assertThat(constructors).hasSize(2);
        assertThat(Arrays.stream(constructors)
                        .filter(constructor -> constructor.getParameterCount() == 1)
                        .filter(constructor -> constructor.isAnnotationPresent(Autowired.class)))
                .hasSize(1);
        assertThat(Arrays.stream(constructors)
                        .filter(constructor -> constructor.getParameterCount() == 2)
                        .filter(constructor -> !Modifier.isPublic(constructor.getModifiers())
                                && !Modifier.isPrivate(constructor.getModifiers())
                                && !Modifier.isProtected(constructor.getModifiers())))
                .hasSize(1);
    }

    @Test
    void passwordFailuresAreGenericAndAlwaysPerformExactlyOneBoundedMatch() {
        Fixture fixture = new Fixture();
        fixture.passwords.matches = (raw, encoded) -> false;
        MockHttpServletRequest request = fixture.request();

        assertAuthenticationFailed(() -> fixture.service.passwordStage(
                "missing-admin", PASSWORD, request));
        assertThat(fixture.passwords.matchInputs)
                .containsExactly(new MatchInput(PASSWORD, DUMMY_HASH));
        verify(fixture.admins).findByUsername("missing-admin");
        verify(fixture.audit).record(argThat(command -> rejectedPassword(command)));

        fixture.passwords.matchInputs.clear();
        clearInvocations(fixture.admins, fixture.audit);
        when(fixture.admins.findByUsername(USERNAME))
                .thenReturn(Optional.of(fixture.admin(AdminStatus.ACTIVE)));
        assertAuthenticationFailed(() -> fixture.service.passwordStage(USERNAME, PASSWORD, request));
        assertThat(fixture.passwords.matchInputs)
                .containsExactly(new MatchInput(PASSWORD, STORED_HASH));
        verify(fixture.audit).record(argThat(command -> rejectedPassword(command)));

        fixture.passwords.matchInputs.clear();
        clearInvocations(fixture.admins, fixture.audit);
        when(fixture.admins.findByUsername(USERNAME))
                .thenReturn(Optional.of(fixture.admin(AdminStatus.DISABLED)));
        assertAuthenticationFailed(() -> fixture.service.passwordStage(USERNAME, PASSWORD, request));
        assertThat(fixture.passwords.matchInputs)
                .containsExactly(new MatchInput(PASSWORD, STORED_HASH));
        verify(fixture.audit).record(argThat(command -> rejectedPassword(command)));
    }

    @Test
    void malformedAndOverCodePointCredentialsNeverReachRepositoryOrArgonInput() {
        Fixture fixture = new Fixture();
        MockHttpServletRequest request = fixture.request();
        String malformed = "bad\uD800name";
        String oversizedPassword = "\ud83d\ude00".repeat(129);

        assertAuthenticationFailed(() -> fixture.service.passwordStage(
                malformed, oversizedPassword, request));
        verify(fixture.admins, never()).findByUsername(anyString());
        assertThat(fixture.passwords.matchInputs).hasSize(1);
        assertThat(fixture.passwords.matchInputs.get(0).encoded()).isEqualTo(DUMMY_HASH);
        assertThat(fixture.passwords.matchInputs.get(0).raw())
                .isNotEqualTo(oversizedPassword)
                .hasSizeLessThanOrEqualTo(128);
    }

    @Test
    void passwordVerificationProviderFailureIsFixedAndCauseFree() {
        Fixture fixture = new Fixture();
        MockHttpServletRequest request = fixture.request();
        fixture.allowPassword();
        fixture.passwords.matches = (raw, encoded) -> {
            throw new IllegalStateException("raw-argon-provider-detail");
        };

        assertThatThrownBy(() -> fixture.service.passwordStage(USERNAME, PASSWORD, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("password verification failed")
                .hasNoCause();
        verifyNoInteractions(fixture.audit);
    }

    @Test
    void passwordStageDoesNotCreateASessionOnlyToClearOldState() {
        Fixture fixture = new Fixture();
        fixture.allowPassword();
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertAuthenticationFailed(() -> fixture.service.passwordStage(
                "missing-admin", "wrong", request));
        assertThat(request.getSession(false)).isNull();
    }

    @Test
    void passwordRateLimitClearsAndTombstonesAnOlderChallengeBeforeLookupOrMatch() {
        Fixture fixture = new Fixture();
        MockHttpServletRequest request = fixture.request();
        fixture.allowPassword();
        fixture.service.passwordStage(USERNAME, PASSWORD, request);
        PendingSecondFactor old = fixture.pending(request);

        clearInvocations(fixture.admins, fixture.audit, fixture.totp);
        fixture.passwords.matchInputs.clear();
        when(fixture.limiter.consume(eq("admin-login"), anyString()))
                .thenReturn(RateLimitDecision.deny(7));
        assertRateLimited(
                () -> fixture.service.passwordStage(USERNAME, "wrong", request), "7");
        assertThat(request.getSession(false).getAttribute(PendingSecondFactor.SESSION_KEY)).isNull();
        verifyNoInteractions(fixture.admins);
        assertThat(fixture.passwords.matchInputs).isEmpty();

        request.getSession(false).setAttribute(PendingSecondFactor.SESSION_KEY, old);
        assertRateLimited(() -> fixture.service.secondFactor(
                SecondFactorMethod.TOTP, "123456", request, new MockHttpServletResponse()), null);
        verifyNoInteractions(fixture.totp);
    }

    @Test
    void acceptedAuditCommitsBeforePendingPublicationAndFailurePublishesNothing() {
        Fixture fixture = new Fixture();
        MockHttpServletRequest request = fixture.request();
        fixture.allowPassword();

        Instant expiry = fixture.service.passwordStage(USERNAME, PASSWORD, request);
        PendingSecondFactor pending = fixture.pending(request);
        assertThat(pending.adminId()).isEqualTo(ADMIN_ID);
        assertThat(pending.adminVersion()).isEqualTo(7);
        assertThat(pending.issuedAt()).isEqualTo(NOW);
        assertThat(pending.failures()).isZero();
        assertThat(expiry).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        verify(fixture.audit).record(argThat(command -> acceptedPassword(command)));
        verifyNoInteractions(fixture.sessions, fixture.strategy, fixture.contexts);

        Fixture failingAudit = new Fixture();
        MockHttpServletRequest failedRequest = failingAudit.request();
        failingAudit.allowPassword();
        org.mockito.Mockito.doThrow(new IllegalStateException("safe-audit-failure"))
                .when(failingAudit.audit).record(any());
        assertThatThrownBy(() -> failingAudit.service.passwordStage(
                        USERNAME, PASSWORD, failedRequest))
                .isInstanceOf(IllegalStateException.class);
        assertThat(failedRequest.getSession(false)
                        .getAttribute(PendingSecondFactor.SESSION_KEY))
                .isNull();
    }

    @Test
    void restoredPendingFailsClosedAfterServiceRestartBeforeProviderOrSql() {
        Fixture fixture = new Fixture();
        MockHttpServletRequest request = fixture.request();
        fixture.allowPassword();
        fixture.service.passwordStage(USERNAME, PASSWORD, request);
        clearInvocations(fixture.admins, fixture.totp, fixture.recovery);

        AdminAuthenticationService restarted = fixture.newService();
        assertThat(restarted.secondFactor(
                        SecondFactorMethod.TOTP, "123456", request,
                        new MockHttpServletResponse()))
                .isEmpty();
        assertThat(request.getSession(false).getAttribute(PendingSecondFactor.SESSION_KEY)).isNull();
        verify(fixture.admins, never()).findByIdForUpdate(any());
        verifyNoInteractions(fixture.totp, fixture.recovery);
    }

    @Test
    void replacementTombstoneCannotRecreateTheOldChallengeBudget() {
        Fixture fixture = new Fixture();
        MockHttpServletRequest request = fixture.request();
        fixture.allowPassword();
        fixture.service.passwordStage(USERNAME, PASSWORD, request);
        PendingSecondFactor old = fixture.pending(request);
        fixture.service.passwordStage(USERNAME, PASSWORD, request);

        request.getSession(false).setAttribute(PendingSecondFactor.SESSION_KEY, old);
        clearInvocations(fixture.admins, fixture.totp);
        assertRateLimited(() -> fixture.service.secondFactor(
                SecondFactorMethod.TOTP, "123456", request, new MockHttpServletResponse()), null);
        verify(fixture.admins, never()).findByIdForUpdate(any());
        verifyNoInteractions(fixture.totp);
    }

    @Test
    void challengeGateHasOneLifetimeBoundProviderBudgetAndRetainsExhaustionTombstone() {
        Fixture fixture = new Fixture(16, 1, new RecordingPasswordEncoder());
        MockHttpServletRequest request = fixture.request();
        fixture.allowPassword();
        fixture.service.passwordStage(USERNAME, PASSWORD, request);
        PendingSecondFactor original = fixture.pending(request);
        fixture.allowSecondFactor(false);

        assertThat(fixture.service.secondFactor(
                        SecondFactorMethod.TOTP, "000000", request,
                        new MockHttpServletResponse()))
                .isEmpty();
        verify(fixture.totp, times(1)).verify(eq(ADMIN_ID), any(), eq("000000"));
        assertThat(request.getSession(false).getAttribute(PendingSecondFactor.SESSION_KEY)).isNull();

        request.getSession(false).setAttribute(PendingSecondFactor.SESSION_KEY, original);
        assertRateLimited(() -> fixture.service.secondFactor(
                SecondFactorMethod.TOTP, "000000", request, new MockHttpServletResponse()), null);
        verify(fixture.totp, times(1)).verify(eq(ADMIN_ID), any(), anyString());
    }

    @Test
    void challengeGateCapacityFailsClosedAndLazyExpiryReclaimsCapacity() {
        Fixture fixture = new Fixture(1, 2, new RecordingPasswordEncoder());
        MockHttpServletRequest first = fixture.request();
        MockHttpServletRequest second = fixture.request();
        fixture.allowPassword();
        fixture.service.passwordStage(USERNAME, PASSWORD, first);

        assertRateLimited(() -> fixture.service.passwordStage(
                USERNAME, PASSWORD, second), null);
        assertThat(second.getSession(false).getAttribute(PendingSecondFactor.SESSION_KEY)).isNull();

        fixture.clock.advance(Duration.ofMinutes(5).plusMillis(1));
        assertThat(fixture.service.passwordStage(USERNAME, PASSWORD, second))
                .isEqualTo(fixture.clock.instant().plus(Duration.ofMinutes(5)));
        assertThat(fixture.pending(second)).isNotNull();
    }

    @Test
    void gateAtomicallyAllowsOnlyOneConcurrentProviderInvocation() throws Exception {
        Fixture fixture = new Fixture(16, 1, new RecordingPasswordEncoder());
        MockHttpServletRequest request = fixture.request();
        fixture.allowPassword();
        fixture.service.passwordStage(USERNAME, PASSWORD, request);
        fixture.allowSecondFactor(false);
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        when(fixture.totp.verify(eq(ADMIN_ID), any(), eq("000000"))).thenAnswer(invocation -> {
            providerEntered.countDown();
            assertThat(releaseProvider.await(2, SECONDS)).isTrue();
            return false;
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = pool.submit(() -> callFactor(fixture.service, request));
            assertThat(providerEntered.await(2, SECONDS)).isTrue();
            Future<Object> second = pool.submit(() -> callFactor(fixture.service, request));
            Object denied = second.get(2, SECONDS);
            assertThat(denied).isInstanceOf(DomainException.class);
            assertThat(((DomainException) denied).code()).isEqualTo("RATE_LIMITED");
            releaseProvider.countDown();
            assertThat(first.get(2, SECONDS)).isEqualTo(Optional.empty());
            verify(fixture.totp, times(1)).verify(eq(ADMIN_ID), any(), eq("000000"));
        } finally {
            releaseProvider.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void invalidPendingTimesAndFactorCodeFailBeforeProviderOrSql() {
        Fixture fixture = new Fixture();
        MockHttpServletRequest request = fixture.request();
        HttpSession session = request.getSession(false);
        for (PendingSecondFactor pending : List.of(
                new PendingSecondFactor(UUID.randomUUID(), ADMIN_ID, 7, NOW.plusSeconds(1), 0),
                new PendingSecondFactor(UUID.randomUUID(), ADMIN_ID, 7,
                        Instant.MAX.minusSeconds(1), 0),
                new PendingSecondFactor(UUID.randomUUID(), ADMIN_ID, 7,
                        NOW.minus(Duration.ofMinutes(5)), 0))) {
            session.setAttribute(PendingSecondFactor.SESSION_KEY, pending);
            assertThat(fixture.service.secondFactor(
                            SecondFactorMethod.TOTP, "123456", request,
                            new MockHttpServletResponse()))
                    .isEmpty();
        }
        session.setAttribute(PendingSecondFactor.SESSION_KEY, "wrong-type");
        assertThat(fixture.service.secondFactor(
                        SecondFactorMethod.TOTP, "123456", request,
                        new MockHttpServletResponse()))
                .isEmpty();
        verify(fixture.admins, never()).findByIdForUpdate(any());
        verifyNoInteractions(fixture.totp, fixture.recovery);
    }

    @Test
    void overlongFactorIsRejectedAfterParentLockWithoutInvokingEitherProvider() {
        Fixture fixture = new Fixture();
        MockHttpServletRequest request = fixture.request();
        fixture.allowPassword();
        fixture.service.passwordStage(USERNAME, PASSWORD, request);
        fixture.allowSecondFactor(false);
        clearInvocations(fixture.totp, fixture.recovery, fixture.audit);

        assertThat(fixture.service.secondFactor(
                        SecondFactorMethod.TOTP, "1".repeat(65), request,
                        new MockHttpServletResponse()))
                .isEmpty();
        verify(fixture.admins).findByIdForUpdate(ADMIN_ID);
        verifyNoInteractions(fixture.totp, fixture.recovery);
        verify(fixture.audit).record(argThat(command -> rejectedFactor(command, "TOTP")));
    }

    @Test
    void staleVersionAndDisabledParentAreTerminalBeforeFactorProvider() {
        Fixture fixture = new Fixture();
        MockHttpServletRequest request = fixture.request();
        fixture.allowPassword();
        fixture.service.passwordStage(USERNAME, PASSWORD, request);
        when(fixture.admins.findByIdForUpdate(ADMIN_ID)).thenReturn(Optional.of(
                new AdminUser(ADMIN_ID, USERNAME, STORED_HASH, AdminStatus.ACTIVE,
                        new EncryptedTotpSecret(1, new byte[12], new byte[17]), null, 8,
                        NOW.minus(Duration.ofDays(10)), NOW)));

        assertThat(fixture.service.secondFactor(
                        SecondFactorMethod.TOTP, "123456", request,
                        new MockHttpServletResponse()))
                .isEmpty();
        verifyNoInteractions(fixture.totp, fixture.recovery);
        assertThat(request.getSession(false).getAttribute(PendingSecondFactor.SESSION_KEY)).isNull();
    }

    @Test
    void wrongFactorLocksParentFirstCommitsSafeAuditAndBoundsPendingState() {
        Fixture fixture = new Fixture();
        MockHttpServletRequest request = fixture.request();
        fixture.allowPassword();
        fixture.service.passwordStage(USERNAME, PASSWORD, request);
        clearInvocations(fixture.admins, fixture.sessions, fixture.summaries, fixture.audit);
        fixture.allowSecondFactor(false);

        assertThat(fixture.service.secondFactor(
                        SecondFactorMethod.TOTP, "000000", request,
                        new MockHttpServletResponse()))
                .isEmpty();
        assertThat(fixture.pending(request).failures()).isEqualTo(1);
        InOrder order = inOrder(fixture.sessions, fixture.summaries, fixture.admins,
                fixture.totp, fixture.audit);
        order.verify(fixture.sessions).requireSpringPrimaryId(anyString());
        order.verify(fixture.summaries).create(request);
        order.verify(fixture.admins).findByIdForUpdate(ADMIN_ID);
        order.verify(fixture.totp).verify(eq(ADMIN_ID), any(), eq("000000"));
        order.verify(fixture.audit).record(argThat(command -> rejectedFactor(command, "TOTP")));
        verify(fixture.admins, never()).updateLastLogin(any(), any());
        verify(fixture.sessions, never()).start(any(), anyString(), anyString());
    }

    @Test
    void providerFailureIsNotDowngradedToAnAuthenticationMismatch() {
        Fixture fixture = new Fixture();
        MockHttpServletRequest request = fixture.request();
        fixture.allowPassword();
        fixture.service.passwordStage(USERNAME, PASSWORD, request);
        fixture.allowSecondFactor(false);
        when(fixture.totp.verify(eq(ADMIN_ID), any(), anyString()))
                .thenThrow(new IllegalStateException("TOTP generation failed"));
        clearInvocations(fixture.audit);

        assertThatThrownBy(() -> fixture.service.secondFactor(
                        SecondFactorMethod.TOTP, "000000", request,
                        new MockHttpServletResponse()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("TOTP generation failed");
        verifyNoInteractions(fixture.audit);
        verify(fixture.admins, never()).updateLastLogin(any(), any());
    }

    @Test
    void successfulTotpUsesFrozenGlobalOrderStableIdAndExactAuthentication() {
        Fixture fixture = new Fixture();
        MockHttpServletRequest request = fixture.request();
        MockHttpServletResponse response = new MockHttpServletResponse();
        fixture.allowPassword();
        fixture.service.passwordStage(USERNAME, PASSWORD, request);
        clearInvocations(fixture.admins, fixture.sessions, fixture.summaries, fixture.audit,
                fixture.strategy, fixture.contexts);
        fixture.allowSecondFactor(true);

        Optional<AdminPrincipal> result = fixture.service.secondFactor(
                SecondFactorMethod.TOTP, "123456", request, response);

        assertThat(result).contains(new AdminPrincipal(ADMIN_ID, USERNAME));
        InOrder order = inOrder(fixture.sessions, fixture.summaries, fixture.admins,
                fixture.totp, fixture.audit, fixture.strategy, fixture.contexts);
        order.verify(fixture.sessions).requireSpringPrimaryId(PUBLIC_ID);
        order.verify(fixture.summaries).create(request);
        order.verify(fixture.admins).findByIdForUpdate(ADMIN_ID);
        order.verify(fixture.totp).verify(eq(ADMIN_ID), any(), eq("123456"));
        order.verify(fixture.admins).updateLastLogin(ADMIN_ID, NOW);
        order.verify(fixture.audit).record(argThat(command -> loginSucceeded(command, "TOTP")));
        order.verify(fixture.strategy).onAuthentication(
                argThat(AdminAuthenticationServiceTest::exactAdminAuthentication),
                eq(request), eq(response));
        order.verify(fixture.contexts).saveContext(
                argThat(context -> exactAdminAuthentication(context.getAuthentication())),
                eq(request), eq(response));
        order.verify(fixture.sessions).start(ADMIN_ID, PRIMARY_ID, CLIENT_SUMMARY);
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .satisfies(authentication -> assertThat(exactAdminAuthentication(authentication)).isTrue());
        assertThat(request.getSession(false).getAttribute(PendingSecondFactor.SESSION_KEY)).isNull();
    }

    @Test
    void recoveryBranchRunsUnderParentLockAndDoesNotInvokeTotp() {
        Fixture fixture = new Fixture();
        MockHttpServletRequest request = fixture.request();
        fixture.allowPassword();
        fixture.service.passwordStage(USERNAME, PASSWORD, request);
        fixture.allowSecondFactor(true);
        when(fixture.recovery.consume(ADMIN_ID, "ABCD-EFGH-JKLM")).thenReturn(true);

        assertThat(fixture.service.secondFactor(
                        SecondFactorMethod.RECOVERY_CODE, "ABCD-EFGH-JKLM", request,
                        new MockHttpServletResponse()))
                .contains(new AdminPrincipal(ADMIN_ID, USERNAME));
        InOrder order = inOrder(fixture.admins, fixture.recovery, fixture.sessions, fixture.audit);
        order.verify(fixture.admins).findByIdForUpdate(ADMIN_ID);
        order.verify(fixture.recovery).consume(ADMIN_ID, "ABCD-EFGH-JKLM");
        order.verify(fixture.admins).updateLastLogin(ADMIN_ID, NOW);
        order.verify(fixture.audit).record(argThat(command ->
                loginSucceeded(command, "RECOVERY_CODE")));
        order.verify(fixture.sessions).start(ADMIN_ID, PRIMARY_ID, CLIENT_SUMMARY);
        verifyNoInteractions(fixture.totp);
    }

    @Test
    void contextPersistenceFailureClearsThreadAndStoredSessionContextBeforeRethrow() {
        Fixture fixture = new Fixture();
        MockHttpServletRequest request = fixture.request();
        fixture.allowPassword();
        fixture.service.passwordStage(USERNAME, PASSWORD, request);
        fixture.allowSecondFactor(true);
        request.getSession(false).setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                SecurityContextHolder.createEmptyContext());
        org.mockito.Mockito.doThrow(new IllegalStateException("session save failed"))
                .when(fixture.contexts).saveContext(any(), eq(request), any());

        assertThatThrownBy(() -> fixture.service.secondFactor(
                        SecondFactorMethod.TOTP, "123456", request,
                        new MockHttpServletResponse()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("session save failed");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(request.getSession(false).getAttribute(
                        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
                .isNull();
        verify(fixture.admins).updateLastLogin(ADMIN_ID, NOW);
        verify(fixture.sessions, never()).start(any(), anyString(), anyString());
        verify(fixture.audit).record(argThat(command -> loginSucceeded(command, "TOTP")));
    }

    @Test
    void metadataFailureNeverReentersSessionPersistenceBeforeOuterRollback() {
        Fixture fixture = new Fixture();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = org.mockito.Mockito.spy(new MockHttpSession(null, PUBLIC_ID));
        request.setSession(session);
        fixture.allowPassword();
        fixture.service.passwordStage(USERNAME, PASSWORD, request);
        fixture.allowSecondFactor(true);
        clearInvocations(session);
        org.mockito.Mockito.doThrow(new IllegalStateException("metadata insert failed"))
                .when(fixture.sessions).start(ADMIN_ID, PRIMARY_ID, CLIENT_SUMMARY);

        assertThatThrownBy(() -> fixture.service.secondFactor(
                        SecondFactorMethod.TOTP, "123456", request,
                        new MockHttpServletResponse()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("metadata insert failed");

        verify(session, times(1)).removeAttribute(PendingSecondFactor.SESSION_KEY);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void metadataFilterLooksUpActiveSessionExactlyOnceAndPublishesPrivateAttribute()
            throws Exception {
        AdminSessionService sessions = mock(AdminSessionService.class);
        SecurityContextRepository contexts = mock(SecurityContextRepository.class);
        SecurityProblemWriter problems = mock(SecurityProblemWriter.class);
        SessionMetadataEnforcementFilter filter =
                new SessionMetadataEnforcementFilter(sessions, contexts, problems);
        MockHttpServletRequest request = protectedRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        ActiveSession active = new ActiveSession(
                METADATA_ID, ADMIN_ID, PRIMARY_ID, NOW.minusSeconds(60), NOW);
        when(sessions.requireActive(PUBLIC_ID)).thenReturn(active);
        SecurityContextHolder.getContext().setAuthentication(adminAuthentication());

        filter.doFilter(request, response, chain);

        verify(sessions, times(1)).requireActive(PUBLIC_ID);
        verify(chain).doFilter(request, response);
        assertThat(request.getAttribute(SessionMetadataEnforcementFilter.ACTIVE_SESSION_ATTRIBUTE))
                .isSameAs(active);
        verifyNoInteractions(problems);
    }

    @Test
    void metadataFilterDistinguishesExpectedInvalidationFromRetryableInternalFailure()
            throws Exception {
        AdminSessionService sessions = mock(AdminSessionService.class);
        SecurityContextRepository contexts = mock(SecurityContextRepository.class);
        SecurityProblemWriter problems = mock(SecurityProblemWriter.class);
        SessionMetadataEnforcementFilter filter =
                new SessionMetadataEnforcementFilter(sessions, contexts, problems);

        MockHttpServletRequest invalid = protectedRequest();
        MockHttpSession invalidSession = (MockHttpSession) invalid.getSession(false);
        SecurityContextHolder.getContext().setAuthentication(adminAuthentication());
        when(sessions.requireActive(PUBLIC_ID)).thenThrow(authenticationRequired());
        filter.doFilter(invalid, new MockHttpServletResponse(), mock(FilterChain.class));
        assertThat(invalidSession.isInvalid()).isTrue();
        verify(problems).write(any(), eq(HttpStatus.UNAUTHORIZED),
                eq("AUTHENTICATION_REQUIRED"));

        clearInvocations(problems, contexts);
        org.mockito.Mockito.reset(sessions);
        SecurityContextHolder.clearContext();
        MockHttpServletRequest retryable = protectedRequest();
        SecurityContextHolder.getContext().setAuthentication(adminAuthentication());
        when(sessions.requireActive(PUBLIC_ID))
                .thenThrow(new IllegalStateException("database detail must not escape"));
        filter.doFilter(retryable, new MockHttpServletResponse(), mock(FilterChain.class));
        assertThat(((MockHttpSession) retryable.getSession(false)).isInvalid()).isFalse();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(problems).write(any(), eq(HttpStatus.INTERNAL_SERVER_ERROR), eq("INTERNAL_ERROR"));
    }

    @Test
    void metadataFilterRejectsForeignPrincipalButLeavesRoleDenialToAuthorization()
            throws Exception {
        AdminSessionService sessions = mock(AdminSessionService.class);
        SecurityContextRepository contexts = mock(SecurityContextRepository.class);
        SecurityProblemWriter problems = mock(SecurityProblemWriter.class);
        SessionMetadataEnforcementFilter filter =
                new SessionMetadataEnforcementFilter(sessions, contexts, problems);
        MockHttpServletRequest request = protectedRequest();
        FilterChain chain = mock(FilterChain.class);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        "foreign", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        filter.doFilter(request, new MockHttpServletResponse(), chain);
        verify(problems).write(any(), eq(HttpStatus.UNAUTHORIZED),
                eq("AUTHENTICATION_REQUIRED"));
        verifyNoInteractions(sessions);

        clearInvocations(problems, chain);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        new AdminPrincipal(ADMIN_ID, USERNAME), null, List.of()));
        filter.doFilter(protectedRequest(), new MockHttpServletResponse(), chain);
        verify(chain).doFilter(any(), any());
        verifyNoInteractions(sessions, problems);
    }

    @Test
    void currentAdminRequiresExactRoleAndMatchingFilterValidatedOwner() {
        MockHttpServletRequest request = protectedRequest();
        ActiveSession active = new ActiveSession(
                METADATA_ID, ADMIN_ID, PRIMARY_ID, NOW.minusSeconds(60), NOW);
        request.setAttribute(SessionMetadataEnforcementFilter.ACTIVE_SESSION_ATTRIBUTE, active);
        SecurityCurrentAdminProvider provider = new SecurityCurrentAdminProvider(request);
        SecurityContextHolder.getContext().setAuthentication(adminAuthentication());

        assertThat(provider.requireAdminId()).isEqualTo(ADMIN_ID);
        assertThat(provider.requirePrincipal()).isEqualTo(new AdminPrincipal(ADMIN_ID, USERNAME));

        request.setAttribute(SessionMetadataEnforcementFilter.ACTIVE_SESSION_ATTRIBUTE,
                new ActiveSession(METADATA_ID, UUID.randomUUID(), PRIMARY_ID,
                        NOW.minusSeconds(60), NOW));
        assertThatThrownBy(provider::requireAdminId)
                .isInstanceOfSatisfying(DomainException.class,
                        failure -> assertThat(failure.code()).isEqualTo("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void persistenceConcurrencyFilterSerializesSameAndReverseOrderedCookieSets()
            throws Exception {
        AttributeSessionIdResolver resolver = new AttributeSessionIdResolver();
        LoginSubjectHasher hasher = mock(LoginSubjectHasher.class);
        when(hasher.hashSessionId(PUBLIC_ID)).thenReturn("b".repeat(64));
        when(hasher.hashSessionId(PRIMARY_ID)).thenReturn("a".repeat(64));
        SecurityProblemWriter problems = mock(SecurityProblemWriter.class);
        SessionPersistenceConcurrencyFilter filter = new SessionPersistenceConcurrencyFilter(
                resolver, hasher, rateProperties(8), problems);
        MockHttpServletRequest firstRequest = requestWithIds(PUBLIC_ID, PRIMARY_ID);
        MockHttpServletRequest secondRequest = requestWithIds(PRIMARY_ID, PUBLIC_ID);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        FilterChain firstChain = (request, response) -> {
            firstEntered.countDown();
            await(releaseFirst);
        };
        FilterChain secondChain = (request, response) -> secondEntered.countDown();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> runFilter(filter, firstRequest, firstChain));
            assertThat(firstEntered.await(2, SECONDS)).isTrue();
            Future<?> second = pool.submit(() -> runFilter(filter, secondRequest, secondChain));
            assertThat(secondEntered.await(150, MILLISECONDS)).isFalse();
            releaseFirst.countDown();
            first.get(2, SECONDS);
            second.get(2, SECONDS);
            assertThat(secondEntered.getCount()).isZero();
            verifyNoInteractions(problems);
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void controllerGateStartsAfterSecondFactorBindingAndRejectsWithoutQueueing()
            throws Exception {
        Fixture fixture = new Fixture();
        ParentMutationGate gate = new ParentMutationGate();
        AdminAuthController controller = new AdminAuthController(
                fixture.service,
                mock(SecurityCurrentAdminProvider.class),
                fixture.sessions,
                mock(SecurityContextLogoutHandler.class),
                gate);
        AdminAuthController.SecondFactorRequest alreadyBoundBody =
                new AdminAuthController.SecondFactorRequest(
                        SecondFactorMethod.TOTP, "123456");
        CountDownLatch gateHeld = new CountDownLatch(1);
        CountDownLatch releaseGate = new CountDownLatch(1);
        MockHttpServletResponse rejected = new MockHttpServletResponse();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = pool.submit(() -> {
                try (ParentMutationGate.Lease ignored =
                             gate.acquire(new MockHttpServletResponse())) {
                    gateHeld.countDown();
                    await(releaseGate);
                }
                return null;
            });
            assertThat(gateHeld.await(2, SECONDS)).isTrue();

            assertThatThrownBy(() -> controller.secondFactor(
                            alreadyBoundBody,
                            pendingSecondFactorRequest(
                                    "/api/admin/auth/second-factor", PUBLIC_ID),
                            rejected))
                    .isInstanceOfSatisfying(DomainException.class, failure -> {
                        assertThat(failure.code()).isEqualTo("RATE_LIMITED");
                        assertThat(failure.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    });
            assertThat(rejected.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
            verify(fixture.sessions, never()).requireSpringPrimaryId(anyString());

            MockHttpServletResponse noPendingResponse = new MockHttpServletResponse();
            assertThatThrownBy(() -> controller.secondFactor(
                            alreadyBoundBody,
                            new MockHttpServletRequest(),
                            noPendingResponse))
                    .isInstanceOfSatisfying(DomainException.class, failure -> {
                        assertThat(failure.code()).isEqualTo("AUTHENTICATION_FAILED");
                        assertThat(failure.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    });
            assertThat(noPendingResponse.getHeader(HttpHeaders.RETRY_AFTER)).isNull();

            releaseGate.countDown();
            holder.get(2, SECONDS);
        } finally {
            releaseGate.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void controllersShareParentMutationGateAndReleaseItAfterFailure()
            throws Exception {
        Fixture fixture = new Fixture();
        ParentMutationGate gate = new ParentMutationGate();
        SecurityCurrentAdminProvider current = mock(SecurityCurrentAdminProvider.class);
        SecurityContextLogoutHandler logout = mock(SecurityContextLogoutHandler.class);
        when(current.requirePrincipal()).thenReturn(new AdminPrincipal(ADMIN_ID, USERNAME));
        AdminAuthController authController = new AdminAuthController(
                fixture.service, current, fixture.sessions, logout, gate);
        AdminSecurityController securityController = new AdminSecurityController(
                current, fixture.sessions, logout, gate);
        ActiveSession active = new ActiveSession(
                METADATA_ID, ADMIN_ID, PRIMARY_ID, NOW.minusSeconds(60), NOW);
        MockHttpServletRequest request = protectedRequest();
        request.setAttribute(SessionMetadataEnforcementFilter.ACTIVE_SESSION_ATTRIBUTE, active);
        CountDownLatch gateHeld = new CountDownLatch(1);
        CountDownLatch releaseGate = new CountDownLatch(1);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = pool.submit(() -> {
                try (ParentMutationGate.Lease ignored =
                             gate.acquire(new MockHttpServletResponse())) {
                    gateHeld.countDown();
                    await(releaseGate);
                }
                return null;
            });
            assertThat(gateHeld.await(2, SECONDS)).isTrue();

            MockHttpServletResponse logoutRejected = new MockHttpServletResponse();
            assertThatThrownBy(() -> authController.logout(request, logoutRejected))
                    .isInstanceOfSatisfying(DomainException.class, failure ->
                            assertThat(failure.code()).isEqualTo("RATE_LIMITED"));
            assertThat(logoutRejected.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("1");

            MockHttpServletResponse revokeRejected = new MockHttpServletResponse();
            assertThatThrownBy(() -> securityController.revoke(
                            METADATA_ID, request, revokeRejected))
                    .isInstanceOfSatisfying(DomainException.class, failure ->
                            assertThat(failure.code()).isEqualTo("RATE_LIMITED"));
            assertThat(revokeRejected.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
            verify(fixture.sessions, never()).revoke(any(), any(), anyString());

            releaseGate.countDown();
            holder.get(2, SECONDS);

            org.mockito.Mockito.doThrow(new IllegalStateException("revoke failed"))
                    .when(fixture.sessions).revoke(METADATA_ID, ADMIN_ID, "LOGOUT");
            assertThatThrownBy(() -> authController.logout(
                            request, new MockHttpServletResponse()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("revoke failed");
            try (ParentMutationGate.Lease ignored =
                         gate.acquire(new MockHttpServletResponse())) {
                assertThat(ignored).isNotNull();
            }
        } finally {
            releaseGate.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void persistenceConcurrencyFilterRejectsExcessRawCookieValuesBeforeUuidFiltering()
            throws Exception {
        AttributeSessionIdResolver resolver = new AttributeSessionIdResolver();
        LoginSubjectHasher hasher = mock(LoginSubjectHasher.class);
        SecurityProblemWriter problems = mock(SecurityProblemWriter.class);
        SessionPersistenceConcurrencyFilter filter = new SessionPersistenceConcurrencyFilter(
                resolver, hasher, rateProperties(8), problems);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(requestWithIds("invalid-1", "invalid-2", "invalid-3"), response, chain);

        verify(chain, never()).doFilter(any(), any());
        verifyNoInteractions(hasher);
        verify(problems).write(
                eq(response), eq(HttpStatus.TOO_MANY_REQUESTS), eq("RATE_LIMITED"));
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
    }

    @Test
    void persistenceConcurrencyFilterUnwindsPartialCapacityAndReleasesAfterException()
            throws Exception {
        AttributeSessionIdResolver resolver = new AttributeSessionIdResolver();
        LoginSubjectHasher hasher = mock(LoginSubjectHasher.class);
        when(hasher.hashSessionId(PUBLIC_ID)).thenReturn("a".repeat(64));
        when(hasher.hashSessionId(PRIMARY_ID)).thenReturn("b".repeat(64));
        SecurityProblemWriter problems = mock(SecurityProblemWriter.class);
        SessionPersistenceConcurrencyFilter filter = new SessionPersistenceConcurrencyFilter(
                resolver, hasher, rateProperties(1), problems);

        FilterChain shouldNotRun = mock(FilterChain.class);
        filter.doFilter(requestWithIds(PUBLIC_ID, PRIMARY_ID),
                new MockHttpServletResponse(), shouldNotRun);
        verify(shouldNotRun, never()).doFilter(any(), any());
        verify(problems).write(any(), eq(HttpStatus.TOO_MANY_REQUESTS), eq("RATE_LIMITED"));

        AtomicInteger entries = new AtomicInteger();
        filter.doFilter(requestWithIds(PUBLIC_ID), new MockHttpServletResponse(),
                (request, response) -> entries.incrementAndGet());
        assertThat(entries).hasValue(1);

        assertThatThrownBy(() -> filter.doFilter(
                        requestWithIds(PUBLIC_ID), new MockHttpServletResponse(),
                        (request, response) -> { throw new ServletException("chain failed"); }))
                .isInstanceOf(ServletException.class);
        filter.doFilter(requestWithIds(PUBLIC_ID), new MockHttpServletResponse(),
                (request, response) -> entries.incrementAndGet());
        assertThat(entries).hasValue(2);
    }

    @Test
    void persistenceConcurrencyHasherFailureIsControlledBeforeInnerSessionLoad()
            throws Exception {
        AttributeSessionIdResolver resolver = new AttributeSessionIdResolver();
        LoginSubjectHasher hasher = mock(LoginSubjectHasher.class);
        when(hasher.hashSessionId(PUBLIC_ID))
                .thenThrow(new IllegalStateException("raw-public-session-id"));
        SecurityProblemWriter problems = mock(SecurityProblemWriter.class);
        SessionPersistenceConcurrencyFilter filter = new SessionPersistenceConcurrencyFilter(
                resolver, hasher, rateProperties(8), problems);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(requestWithIds("not-a-uuid", PUBLIC_ID),
                new MockHttpServletResponse(), chain);
        verify(hasher, times(1)).hashSessionId(PUBLIC_ID);
        verify(chain, never()).doFilter(any(), any());
        verify(problems).write(any(), eq(HttpStatus.INTERNAL_SERVER_ERROR), eq("INTERNAL_ERROR"));
        assertThat(filter.toString()).doesNotContain(PUBLIC_ID, "raw-public-session-id");
    }

    private static Object callFactor(
            AdminAuthenticationService service, MockHttpServletRequest request) {
        try {
            return service.secondFactor(
                    SecondFactorMethod.TOTP, "000000", request,
                    new MockHttpServletResponse());
        } catch (RuntimeException failure) {
            return failure;
        }
    }

    private static boolean rejectedPassword(AuditCommand command) {
        return command.actorAdminId() == null
                && command.action().equals("AUTH_PASSWORD_REJECTED")
                && command.targetType().equals("ADMIN")
                && command.targetId() == null
                && command.outcome() == AuditOutcome.FAILURE
                && command.metadata().equals(Map.of("stage", "PASSWORD"));
    }

    private static boolean acceptedPassword(AuditCommand command) {
        return command.actorAdminId() == null
                && command.action().equals("AUTH_PASSWORD_ACCEPTED")
                && command.targetType().equals("ADMIN")
                && ADMIN_ID.toString().equals(command.targetId())
                && command.outcome() == AuditOutcome.SUCCESS
                && command.metadata().equals(Map.of("next", "SECOND_FACTOR"));
    }

    private static boolean rejectedFactor(AuditCommand command, String method) {
        return command.actorAdminId() == null
                && command.action().equals("AUTH_SECOND_FACTOR_REJECTED")
                && command.targetType().equals("ADMIN")
                && ADMIN_ID.toString().equals(command.targetId())
                && command.outcome() == AuditOutcome.FAILURE
                && command.metadata().equals(Map.of("method", method));
    }

    private static boolean loginSucceeded(AuditCommand command, String method) {
        return ADMIN_ID.equals(command.actorAdminId())
                && command.action().equals("AUTH_LOGIN_SUCCEEDED")
                && command.targetType().equals("ADMIN")
                && ADMIN_ID.toString().equals(command.targetId())
                && command.outcome() == AuditOutcome.SUCCESS
                && command.metadata().equals(Map.of("method", method));
    }

    private static boolean exactAdminAuthentication(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal().equals(new AdminPrincipal(ADMIN_ID, USERNAME))
                && authentication.getCredentials() == null
                && authentication.getAuthorities().equals(
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private static Authentication adminAuthentication() {
        return UsernamePasswordAuthenticationToken.authenticated(
                new AdminPrincipal(ADMIN_ID, USERNAME), null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private static DomainException authenticationRequired() {
        return new DomainException("AUTHENTICATION_REQUIRED", HttpStatus.UNAUTHORIZED, Map.of());
    }

    private static void assertAuthenticationFailed(ThrowingCall call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(DomainException.class, failure -> {
                    DomainException domain = (DomainException) failure;
                    assertThat(domain.code()).isEqualTo("AUTHENTICATION_FAILED");
                    assertThat(domain.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(domain.fieldErrors()).isEmpty();
                    assertThat(domain).hasNoCause();
                });
    }

    private static void assertRateLimited(ThrowingCall call, String expectedRetry) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(DomainException.class, failure -> {
                    DomainException domain = (DomainException) failure;
                    assertThat(domain.code()).isEqualTo("RATE_LIMITED");
                    assertThat(domain.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(domain).hasNoCause();
                    assertThat(Integer.parseInt(domain.fieldErrors()
                                    .get("retryAfterSeconds")))
                            .isPositive();
                    if (expectedRetry != null) {
                        assertThat(domain.fieldErrors().get("retryAfterSeconds"))
                                .isEqualTo(expectedRetry);
                    }
                });
    }

    private static MockHttpServletRequest protectedRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/auth/me");
        request.setSession(new MockHttpSession(null, PUBLIC_ID));
        return request;
    }

    private static MockHttpServletRequest requestWithIds(String... ids) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AttributeSessionIdResolver.ATTRIBUTE, List.of(ids));
        return request;
    }

    private static MockHttpServletRequest pendingSecondFactorRequest(
            String path, String sessionId) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        MockHttpSession session = new MockHttpSession(null, sessionId);
        session.setAttribute(PendingSecondFactor.SESSION_KEY, new PendingSecondFactor(
                UUID.randomUUID(), ADMIN_ID, 7, NOW, 0));
        request.setSession(session);
        return request;
    }

    private static RateLimitProperties rateProperties(int maximumSubjects) {
        return new RateLimitProperties(maximumSubjects, Map.of(
                "admin-login", new RateLimitProperties.Policy(5, Duration.ofMinutes(1)),
                "admin-security", new RateLimitProperties.Policy(20, Duration.ofMinutes(1))));
    }

    private static void runFilter(
            jakarta.servlet.Filter filter,
            MockHttpServletRequest request,
            FilterChain chain) {
        try {
            filter.doFilter(request, new MockHttpServletResponse(), chain);
        } catch (IOException | ServletException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void await(CountDownLatch latch) throws ServletException {
        try {
            if (!latch.await(2, SECONDS)) {
                throw new ServletException("timed out waiting for test latch");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ServletException("interrupted waiting for test latch");
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T roundTrip(T value) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(value);
        }
        try (ObjectInputStream input = new ObjectInputStream(
                new ByteArrayInputStream(bytes.toByteArray()))) {
            return (T) input.readObject();
        }
    }

    private static byte[] deterministicKey() {
        byte[] key = new byte[32];
        for (int index = 0; index < key.length; index++) {
            key[index] = (byte) index;
        }
        return key;
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }

    private record MatchInput(String raw, String encoded) { }

    private static final class RecordingPasswordEncoder implements PasswordEncoder {
        private String encoded = DUMMY_HASH;
        private RuntimeException encodeFailure;
        private BiPredicate<String, String> matches = (raw, hash) -> false;
        private final List<String> encodeInputs = new ArrayList<>();
        private final List<MatchInput> matchInputs = new ArrayList<>();

        @Override
        public String encode(CharSequence rawPassword) {
            encodeInputs.add(rawPassword == null ? null : rawPassword.toString());
            if (encodeFailure != null) {
                throw encodeFailure;
            }
            return encoded;
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            String raw = rawPassword == null ? null : rawPassword.toString();
            matchInputs.add(new MatchInput(raw, encodedPassword));
            return matches.test(raw, encodedPassword);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant = NOW;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("test clock is UTC only");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }

    private static final class AttributeSessionIdResolver implements HttpSessionIdResolver {
        private static final String ATTRIBUTE = AttributeSessionIdResolver.class.getName() + ".ids";

        @Override
        @SuppressWarnings("unchecked")
        public List<String> resolveSessionIds(HttpServletRequest request) {
            Object value = request.getAttribute(ATTRIBUTE);
            return value instanceof List<?> ids ? (List<String>) ids : List.of();
        }

        @Override
        public void setSessionId(HttpServletRequest request, HttpServletResponse response,
                String sessionId) { }

        @Override
        public void expireSession(HttpServletRequest request, HttpServletResponse response) { }
    }

    private static final class Fixture {
        private final AdminUserRepository admins = mock(AdminUserRepository.class);
        private final RecordingPasswordEncoder passwords;
        private final TotpService totp = mock(TotpService.class);
        private final RecoveryCodeService recovery = mock(RecoveryCodeService.class);
        private final TotpProperties totpProperties;
        private final RateLimiter limiter = mock(RateLimiter.class);
        private final LoginSubjectHasher hasher;
        private final AdminSessionService sessions = mock(AdminSessionService.class);
        private final ClientSummaryFactory summaries = mock(ClientSummaryFactory.class);
        private final SessionAuthenticationStrategy strategy =
                mock(SessionAuthenticationStrategy.class);
        private final SecurityContextRepository contexts = mock(SecurityContextRepository.class);
        private final AuditService audit = mock(AuditService.class);
        private final MutableClock clock = new MutableClock();
        private final RateLimitProperties rateProperties;
        private final AdminAuthenticationService service;

        Fixture() {
            this(16, 2, new RecordingPasswordEncoder());
        }

        Fixture(int maximumSubjects, int maximumAttempts, RecordingPasswordEncoder passwords) {
            this.passwords = passwords;
            this.totpProperties = new TotpProperties(
                    1, "1=dummy", "YYchainsaw", Duration.ofMinutes(5), maximumAttempts);
            this.rateProperties = rateProperties(maximumSubjects);
            TrustedClientAddressResolver addresses = mock(TrustedClientAddressResolver.class);
            when(addresses.resolve(any())).thenReturn("203.0.113.17");
            this.hasher = new LoginSubjectHasher(addresses, deterministicKey());
            when(limiter.consume(anyString(), anyString())).thenReturn(RateLimitDecision.allow());
            when(sessions.requireSpringPrimaryId(anyString())).thenReturn(PRIMARY_ID);
            when(summaries.create(any())).thenReturn(CLIENT_SUMMARY);
            when(sessions.start(any(), anyString(), anyString())).thenReturn(METADATA_ID);
            this.service = newService();
        }

        Object[] dependencies() {
            return new Object[] {
                admins, passwords, totp, recovery, totpProperties, limiter, hasher, sessions,
                summaries, strategy, contexts, audit, clock, rateProperties
            };
        }

        AdminAuthenticationService newService() {
            return new AdminAuthenticationService(
                    admins, passwords, totp, recovery, totpProperties, limiter, hasher, sessions,
                    summaries, strategy, contexts, audit, clock, rateProperties);
        }

        MockHttpServletRequest request() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setSession(new MockHttpSession(null, PUBLIC_ID));
            return request;
        }

        PendingSecondFactor pending(MockHttpServletRequest request) {
            return (PendingSecondFactor) request.getSession(false)
                    .getAttribute(PendingSecondFactor.SESSION_KEY);
        }

        AdminUser admin(AdminStatus status) {
            return new AdminUser(
                    ADMIN_ID,
                    USERNAME,
                    STORED_HASH,
                    status,
                    new EncryptedTotpSecret(1, new byte[12], new byte[17]),
                    null,
                    7,
                    NOW.minus(Duration.ofDays(10)),
                    NOW.minus(Duration.ofDays(1)));
        }

        void allowPassword() {
            when(admins.findByUsername(USERNAME)).thenReturn(Optional.of(admin(AdminStatus.ACTIVE)));
            passwords.matches = (raw, encoded) -> PASSWORD.equals(raw) && STORED_HASH.equals(encoded);
        }

        void allowSecondFactor(boolean valid) {
            when(admins.findByIdForUpdate(ADMIN_ID))
                    .thenReturn(Optional.of(admin(AdminStatus.ACTIVE)));
            when(totp.verify(eq(ADMIN_ID), any(), anyString())).thenReturn(valid);
            when(recovery.consume(eq(ADMIN_ID), anyString())).thenReturn(valid);
        }
    }
}
