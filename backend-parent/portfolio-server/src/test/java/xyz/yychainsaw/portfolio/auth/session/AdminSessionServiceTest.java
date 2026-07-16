package xyz.yychainsaw.portfolio.auth.session;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Modifier;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.mockito.InOrder;
import org.slf4j.MDC;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.context.TestPropertySource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import xyz.yychainsaw.portfolio.audit.AuditCommand;
import xyz.yychainsaw.portfolio.audit.AuditOutcome;
import xyz.yychainsaw.portfolio.audit.AuditService;
import xyz.yychainsaw.portfolio.audit.JdbcAuditService;
import xyz.yychainsaw.portfolio.auth.crypto.EncryptedTotpSecret;
import xyz.yychainsaw.portfolio.auth.model.AdminStatus;
import xyz.yychainsaw.portfolio.auth.model.AdminUser;
import xyz.yychainsaw.portfolio.auth.persistence.AdminUserRepository;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
@Import(AdminSessionServiceTest.AuditTestConfiguration.class)
@TestPropertySource(properties = "portfolio.security.session.cleanup-interval=PT24H")
@Isolated
class AdminSessionServiceTest extends PostgresIntegrationTestBase {
    private static final Duration ABSOLUTE_LIFETIME = Duration.ofHours(8);
    private static final Duration CLEANUP_INTERVAL = Duration.ofMinutes(1);
    private static final Instant NOW = Instant.parse("2026-07-15T08:00:00.123456Z");

    @Autowired AdminSessionRepository repository;
    @Autowired AdminUserRepository admins;
    @Autowired TrustedClientAddressResolver addresses;
    @Autowired ClientSummaryFactory summaries;
    @Autowired AdminSessionService sessions;
    @Autowired AdminSessionCleanupJob cleanup;
    @Autowired SessionProperties properties;
    @Autowired RecordingAuditService audit;
    @Autowired JdbcAuditService realAudit;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetTestState() {
        audit.reset();
        MDC.clear();
    }

    @AfterEach
    void clearTraceState() {
        MDC.clear();
    }

    @Test
    void taskNineTypesExposeTheBoundCanonicalSurfaceAndSpringProxy() throws Exception {
        assertThat(AdminSessionStatus.values())
                .extracting(Enum::name)
                .containsExactly("ACTIVE", "REVOKED", "EXPIRED");
        assertThat(properties.absoluteLifetime()).isEqualTo(ABSOLUTE_LIFETIME);
        assertThat(properties.cleanupInterval()).isEqualTo(Duration.ofHours(24));
        assertThat(properties.trustedProxyAddresses()).containsExactly("127.0.0.1", "::1");
        assertThat(AopUtils.isAopProxy(repository)).isTrue();
        assertThat(Modifier.isFinal(AdminSessionRepository.class.getModifiers())).isFalse();

        Scheduled scheduled = AdminSessionCleanupJob.class.getMethod("scheduledRun")
                .getAnnotation(Scheduled.class);
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.initialDelayString())
                .isEqualTo("${portfolio.security.session.cleanup-interval:PT1M}");
        assertThat(scheduled.fixedDelayString())
                .isEqualTo("${portfolio.security.session.cleanup-interval:PT1M}");
    }

    @Test
    void sessionPropertiesEnforceExactMillisecondsBoundsDefaultsAndDefensiveCopies() {
        SessionProperties defaults = new SessionProperties(
                Duration.ofDays(30), Duration.ofHours(24), null);
        assertThat(defaults.trustedProxyAddresses()).containsExactly("127.0.0.1", "::1");
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> defaults.trustedProxyAddresses().add("192.0.2.1"));

        List<String> mutable = new ArrayList<>(List.of("172.18.0.1"));
        SessionProperties copied = new SessionProperties(
                Duration.ofMillis(1), Duration.ofHours(24), mutable);
        mutable.add("172.18.0.2");
        assertThat(copied.trustedProxyAddresses()).containsExactly("172.18.0.1");

        assertThatIllegalArgumentException().isThrownBy(() -> new SessionProperties(
                null, CLEANUP_INTERVAL, null)).withMessage("absolute lifetime is required");
        assertThatIllegalArgumentException().isThrownBy(() -> new SessionProperties(
                ABSOLUTE_LIFETIME, null, null)).withMessage("cleanup interval is required");
        for (Duration invalid : List.of(Duration.ZERO, Duration.ofMillis(-1), Duration.ofNanos(1))) {
            assertThatIllegalArgumentException().isThrownBy(() -> new SessionProperties(
                    invalid, CLEANUP_INTERVAL, null));
            assertThatIllegalArgumentException().isThrownBy(() -> new SessionProperties(
                    ABSOLUTE_LIFETIME, invalid, null));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> new SessionProperties(
                Duration.ofDays(30).plusMillis(1), CLEANUP_INTERVAL, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new SessionProperties(
                ABSOLUTE_LIFETIME, Duration.ofHours(24).plusMillis(1), null));
        assertThatIllegalArgumentException().isThrownBy(() -> new SessionProperties(
                ABSOLUTE_LIFETIME, CLEANUP_INTERVAL, List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new SessionProperties(
                ABSOLUTE_LIFETIME, CLEANUP_INTERVAL, Collections.nCopies(17, "192.0.2.1")));
        assertThatIllegalArgumentException().isThrownBy(() -> new SessionProperties(
                ABSOLUTE_LIFETIME, CLEANUP_INTERVAL, List.of("hostname.example")));
        assertThatIllegalArgumentException().isThrownBy(() -> new SessionProperties(
                ABSOLUTE_LIFETIME, CLEANUP_INTERVAL, List.of(" ")));

        assertThat(new SessionProperties(
                Duration.ofMillis(1), Duration.ofHours(24), List.of("127.0.0.1")))
                .isNotNull();
    }

    @Test
    void configurationBinderDistinguishesMissingIndexedOverrideAndInvalidExplicitLists() {
        SessionProperties missing = bind(Map.of(
                "portfolio.security.session.absolute-lifetime", "PT8H",
                "portfolio.security.session.cleanup-interval", "PT1M"));
        assertThat(missing.trustedProxyAddresses()).containsExactly("127.0.0.1", "::1");

        Map<String, Object> environment = Map.of(
                "PORTFOLIO_SECURITY_SESSION_ABSOLUTELIFETIME", "PT8H",
                "PORTFOLIO_SECURITY_SESSION_CLEANUPINTERVAL", "PT1M",
                "PORTFOLIO_SECURITY_SESSION_TRUSTEDPROXYADDRESSES_0", "172.18.0.1");
        SystemEnvironmentPropertySource source =
                new SystemEnvironmentPropertySource(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, environment);
        MockEnvironment bindEnvironment = new MockEnvironment();
        bindEnvironment.getPropertySources().addFirst(source);
        ConfigurationPropertySources.attach(bindEnvironment);
        SessionProperties overridden = Binder.get(bindEnvironment)
                .bind("portfolio.security.session", Bindable.of(SessionProperties.class))
                .orElseThrow(() -> new AssertionError("environment properties did not bind"));
        assertThat(overridden.trustedProxyAddresses()).containsExactly("172.18.0.1");

        assertThatThrownBy(() -> bind(Map.of(
                "portfolio.security.session.absolute-lifetime", "PT8H",
                "portfolio.security.session.cleanup-interval", "PT1M",
                "portfolio.security.session.trusted-proxy-addresses", "")))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);

        Map<String, Object> tooMany = new java.util.LinkedHashMap<>();
        tooMany.put("portfolio.security.session.absolute-lifetime", "PT8H");
        tooMany.put("portfolio.security.session.cleanup-interval", "PT1M");
        for (int index = 0; index < 17; index++) {
            tooMany.put("portfolio.security.session.trusted-proxy-addresses[" + index + "]",
                    "192.0.2." + (index + 1));
        }
        assertThatThrownBy(() -> bind(tooMany))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructorsRejectNullDependenciesAndRecordsRedactSessionIdentifiers() {
        TransactionTemplate transactions = transactions();
        AuditService auditService = mock(AuditService.class);
        AdminUserRepository adminUsers = mock(AdminUserRepository.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        assertThatNullPointerException().isThrownBy(() -> new AdminSessionRepository(null))
                .withMessage("jdbc is required");
        assertThatNullPointerException().isThrownBy(() -> new TrustedClientAddressResolver(null))
                .withMessage("properties are required");
        assertThatNullPointerException().isThrownBy(() -> new ClientSummaryFactory(null))
                .withMessage("addresses are required");
        assertThatNullPointerException().isThrownBy(() -> new AdminSessionService(
                null, adminUsers, properties, auditService, transactions, clock))
                .withMessage("repository is required");
        assertThatNullPointerException().isThrownBy(() -> new AdminSessionService(
                repository, null, properties, auditService, transactions, clock))
                .withMessage("admin repository is required");
        assertThatNullPointerException().isThrownBy(() -> new AdminSessionService(
                repository, adminUsers, null, auditService, transactions, clock))
                .withMessage("properties are required");
        assertThatNullPointerException().isThrownBy(() -> new AdminSessionService(
                repository, adminUsers, properties, null, transactions, clock))
                .withMessage("audit is required");
        assertThatNullPointerException().isThrownBy(() -> new AdminSessionService(
                repository, adminUsers, properties, auditService, null, clock))
                .withMessage("transactions are required");
        assertThatNullPointerException().isThrownBy(() -> new AdminSessionService(
                repository, adminUsers, properties, auditService, transactions, null))
                .withMessage("clock is required");
        assertThatNullPointerException().isThrownBy(() -> new AdminSessionCleanupJob(
                null, sessions, properties, auditService, transactions, clock))
                .withMessage("repository is required");
        assertThatNullPointerException().isThrownBy(() -> new AdminSessionCleanupJob(
                repository, null, properties, auditService, transactions, clock))
                .withMessage("service is required");
        assertThatNullPointerException().isThrownBy(() -> new AdminSessionCleanupJob(
                repository, sessions, null, auditService, transactions, clock))
                .withMessage("properties are required");
        assertThatNullPointerException().isThrownBy(() -> new AdminSessionCleanupJob(
                repository, sessions, properties, null, transactions, clock))
                .withMessage("audit is required");
        assertThatNullPointerException().isThrownBy(() -> new AdminSessionCleanupJob(
                repository, sessions, properties, auditService, null, clock))
                .withMessage("transactions are required");
        assertThatNullPointerException().isThrownBy(() -> new AdminSessionCleanupJob(
                repository, sessions, properties, auditService, transactions, null))
                .withMessage("clock is required");

        String primary = uuid(101);
        UUID metadataId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        AdminSessionRepository.SessionRow row = new AdminSessionRepository.SessionRow(
                metadataId, adminId, primary, AdminSessionStatus.ACTIVE, NOW,
                NOW.toEpochMilli(), NOW.plusSeconds(60).toEpochMilli());
        AdminSessionRepository.TerminalSession terminal =
                new AdminSessionRepository.TerminalSession(metadataId, adminId, primary, "ADMIN_REQUEST");
        AdminSessionService.ActiveSession active = new AdminSessionService.ActiveSession(
                metadataId, adminId, primary, NOW, NOW);
        assertThat(row.toString()).contains("springSessionPrimaryId=<redacted>").doesNotContain(primary);
        assertThat(terminal.toString()).contains("primaryId=<redacted>").doesNotContain(primary);
        assertThat(active.toString()).contains("springSessionPrimaryId=<redacted>").doesNotContain(primary);
    }

    @Test
    void malformedPublicIdsAreGenericBeforeJdbcAndProgrammerInputsAreStrict() {
        AdminSessionRepository mockedRepository = mock(AdminSessionRepository.class);
        AdminUserRepository mockedAdmins = mock(AdminUserRepository.class);
        AdminSessionService service = new AdminSessionService(
                mockedRepository, mockedAdmins, properties, audit, transactions(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        for (String invalid : java.util.Arrays.asList(
                null, "", " ", "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA",
                "not-a-session", "x".repeat(37))) {
            assertUnauthorized(() -> service.requireSpringPrimaryId(invalid));
            assertUnauthorized(() -> service.requireActive(invalid));
            assertUnauthorized(() -> service.list(UUID.randomUUID(), invalid));
        }
        verifyNoInteractions(mockedRepository, mockedAdmins);

        String missing = uuid(102);
        when(mockedRepository.findPrimaryIdByPublicSessionId(missing)).thenReturn(Optional.empty());
        when(mockedRepository.findByPublicSessionId(missing)).thenReturn(Optional.empty());
        assertUnauthorized(() -> service.requireSpringPrimaryId(missing));
        assertUnauthorized(() -> service.requireActive(missing));

        assertThatNullPointerException().isThrownBy(() -> service.start(null, uuid(1), "Other/Other @ local"))
                .withMessage("admin id is required");
        assertThatIllegalArgumentException().isThrownBy(() -> service.start(
                UUID.randomUUID(), "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA",
                "Other/Other @ local"));
        assertThatIllegalArgumentException().isThrownBy(() -> service.start(
                UUID.randomUUID(), uuid(1), " summary "));
        assertThatIllegalArgumentException().isThrownBy(() -> service.start(
                UUID.randomUUID(), uuid(1), "x".repeat(256)));
        assertThatIllegalArgumentException().isThrownBy(() -> service.revoke(
                UUID.randomUUID(), active(UUID.randomUUID(), UUID.randomUUID(), uuid(98)),
                "bad-reason"));
        assertThatIllegalArgumentException().isThrownBy(() -> service.revoke(
                UUID.randomUUID(), active(UUID.randomUUID(), UUID.randomUUID(), uuid(99)),
                "R".repeat(65)));
    }

    @Test
    void trustedPeerResolutionRejectsSpoofingAndCanonicalizesStrictLiterals() {
        TrustedClientAddressResolver defaultResolver = new TrustedClientAddressResolver(
                new SessionProperties(ABSOLUTE_LIFETIME, CLEANUP_INTERVAL, null));
        assertThat(defaultResolver.resolve(request("127.0.0.1", "203.0.113.7", null)))
                .isEqualTo("203.0.113.7");
        assertThat(defaultResolver.resolve(request("::1", "2001:db8::7", null)))
                .isEqualTo("2001:db8::7");

        SessionProperties gatewayProperties = new SessionProperties(
                ABSOLUTE_LIFETIME, CLEANUP_INTERVAL, List.of("172.18.0.1", "2001:db8::1"));
        TrustedClientAddressResolver resolver = new TrustedClientAddressResolver(gatewayProperties);

        assertThat(resolver.resolve(request("172.18.0.1", "203.0.113.9", null)))
                .isEqualTo("203.0.113.9");
        assertThat(resolver.resolve(request("198.51.100.8", "203.0.113.9", null)))
                .isEqualTo("198.51.100.8");
        assertThat(resolver.resolve(request("2001:0db8:0:0:0:0:0:1", "203.0.113.9", null)))
                .isEqualTo("203.0.113.9");

        List<String> invalidForwarded = List.of(
                "203.0.113.1, 198.51.100.1",
                " 203.0.113.1",
                "203.0.113.1 ",
                "example.test",
                "203.0.113.1:443",
                "[2001:db8::1]:443",
                "fe80::1%eth0",
                "256.0.0.1",
                "1.2.3",
                "2001:::1",
                "::ffff:192.0.2.1",
                "a".repeat(46));
        for (String invalid : invalidForwarded) {
            assertThat(resolver.resolve(request("172.18.0.1", invalid, null)))
                    .as("invalid forwarded value %s", invalid)
                    .isEqualTo("unknown");
        }

        MockHttpServletRequest duplicate = request("172.18.0.1", null, null);
        duplicate.addHeader("X-Real-IP", "203.0.113.1");
        duplicate.addHeader("X-Real-IP", "203.0.113.2");
        assertThat(resolver.resolve(duplicate)).isEqualTo("unknown");
        assertThat(resolver.resolve(request("172.18.0.1", null, null))).isEqualTo("unknown");
        assertThat(resolver.resolve(request("not-an-address", "203.0.113.9", null)))
                .isEqualTo("unknown");

        String compressed = resolver.resolve(request("172.18.0.1", "2001:db8:1:2::abcd", null));
        String expanded = resolver.resolve(request(
                "172.18.0.1", "2001:0db8:0001:0002:0000:0000:0000:abcd", null));
        assertThat(compressed).isEqualTo(expanded);
        assertThat(TrustedClientAddressResolver.parseStrictLiteral("203.0.113.9").toString())
                .contains("<redacted>")
                .doesNotContain("203.0.113.9", "[-53");

        assertThatIllegalArgumentException().isThrownBy(() -> new TrustedClientAddressResolver(
                new SessionProperties(ABSOLUTE_LIFETIME, CLEANUP_INTERVAL,
                        List.of("::1", "0:0:0:0:0:0:0:1"))));
        assertThatIllegalArgumentException().isThrownBy(() -> new SessionProperties(
                ABSOLUTE_LIFETIME, CLEANUP_INTERVAL, java.util.Arrays.asList("127.0.0.1", null)));
    }

    @Test
    void summariesMaskAddressBytesAndUseBoundedFixedBrowserAndOsLabels() {
        TrustedClientAddressResolver gateway = new TrustedClientAddressResolver(new SessionProperties(
                ABSOLUTE_LIFETIME, CLEANUP_INTERVAL, List.of("172.18.0.1")));
        ClientSummaryFactory factory = new ClientSummaryFactory(gateway);

        String androidUa = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0 Safari/537.36";
        assertThat(factory.create(request("172.18.0.1", "203.0.113.99", androidUa)))
                .isEqualTo("Chrome/Android @ 203.0.113.x");

        String iosUa = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) "
                + "AppleWebKit/605.1.15 Version/17.5 Mobile/15E148 Safari/604.1";
        assertThat(factory.create(request("172.18.0.1", "2001:db8:1:2:beef:cafe:1:2", iosUa)))
                .isEqualTo("Safari/iOS @ 2001:db8:1:2::");
        assertThat(factory.create(request(
                "172.18.0.1", "2001:0db8:0001:0002:0000:0000:0000:0001", iosUa)))
                .isEqualTo("Safari/iOS @ 2001:db8:1:2::");

        assertThat(factory.create(request("172.18.0.1", null, null)))
                .isEqualTo("Other/Other @ unknown");
        assertThat(factory.create(request("::1", "198.51.100.5", null)))
                .isEqualTo("Other/Other @ local");
        assertThat(factory.create(request(
                "172.18.0.1", "203.0.113.4", "X".repeat(512) + "Chrome/126 Windows")))
                .isEqualTo("Other/Other @ 203.0.113.x");

        String summary = factory.create(request("172.18.0.1", "2001:db8:1:2:ffff:ffff:ffff:ffff", iosUa));
        assertThat(summary)
                .hasSizeLessThan(255)
                .doesNotContain("ffff", "iPhone", "Mozilla", "2001:db8:1:2:beef");
    }

    @Test
    void ambientTransactionGuardsRunBeforeClockRepositoryAuditOrDeleteWork() {
        AdminSessionRepository mockedRepository = mock(AdminSessionRepository.class);
        AdminUserRepository mockedAdmins = mock(AdminUserRepository.class);
        AuditService mockedAudit = mock(AuditService.class);
        Clock mockedClock = mock(Clock.class);
        AdminSessionService guardedService = new AdminSessionService(
                mockedRepository, mockedAdmins, properties, mockedAudit, transactions(), mockedClock);
        AdminSessionService mockedService = mock(AdminSessionService.class);
        AdminSessionCleanupJob guardedCleanup = new AdminSessionCleanupJob(
                mockedRepository, mockedService, properties, mockedAudit, transactions(), mockedClock);

        transactions().executeWithoutResult(status -> {
            AdminSessionService.ActiveSession actor = active(
                    UUID.randomUUID(), UUID.randomUUID(), uuid(189));
            assertThatIllegalStateException().isThrownBy(() -> guardedService.revoke(
                    UUID.randomUUID(), actor, "ADMIN_REQUEST"))
                    .withMessage("session revoke requires no ambient transaction");
            assertThatIllegalStateException().isThrownBy(() ->
                    guardedService.deleteMarkedSessions(List.of()))
                    .withMessage("marked-session deletion requires no ambient transaction");
            assertThatIllegalStateException().isThrownBy(guardedCleanup::runOnce)
                    .withMessage("session cleanup requires no ambient transaction");
        });

        verifyNoInteractions(mockedRepository, mockedAdmins, mockedAudit, mockedClock, mockedService);
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    void revokeLocksTheActorAdminBeforeSessionMetadataAndAuditThenDeletesAfterCommit() {
        UUID actorAdminId = UUID.randomUUID();
        UUID metadataId = UUID.randomUUID();
        String primaryId = uuid(191);
        AdminUserRepository mockedAdmins = mock(AdminUserRepository.class);
        AdminSessionRepository mockedRepository = mock(AdminSessionRepository.class);
        AuditService mockedAudit = mock(AuditService.class);
        Clock fixedClock = Clock.fixed(NOW, ZoneOffset.UTC);
        AdminSessionRepository.SessionRow active = new AdminSessionRepository.SessionRow(
                metadataId,
                actorAdminId,
                primaryId,
                AdminSessionStatus.ACTIVE,
                NOW.minusSeconds(60),
                NOW.minusSeconds(10).toEpochMilli(),
                NOW.plusSeconds(600).toEpochMilli());
        AdminSessionService.ActiveSession actor = active(
                metadataId, actorAdminId, primaryId);
        AdminSessionRepository.TerminalSession terminal =
                new AdminSessionRepository.TerminalSession(
                        metadataId, actorAdminId, primaryId, "ADMIN_REQUEST");
        when(mockedAdmins.findByIdForUpdate(actorAdminId))
                .thenReturn(Optional.of(admin(actorAdminId)));
        when(mockedRepository.findByMetadataIdForUpdate(metadataId, actorAdminId))
                .thenReturn(Optional.of(active));
        when(mockedRepository.markRevoked(metadataId, actorAdminId, "ADMIN_REQUEST", NOW))
                .thenReturn(Optional.of(terminal));
        when(mockedRepository.deleteSpringSession(primaryId)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return 1;
        });
        AdminSessionService service = new AdminSessionService(
                mockedRepository,
                mockedAdmins,
                properties,
                mockedAudit,
                transactions(),
                fixedClock);

        service.revoke(metadataId, actor, "ADMIN_REQUEST");

        InOrder order = inOrder(mockedAdmins, mockedRepository, mockedAudit);
        order.verify(mockedAdmins).findByIdForUpdate(actorAdminId);
        order.verify(mockedRepository).findByMetadataIdForUpdate(metadataId, actorAdminId);
        order.verify(mockedRepository).markRevoked(
                metadataId, actorAdminId, "ADMIN_REQUEST", NOW);
        order.verify(mockedAudit).record(any(AuditCommand.class));
        order.verify(mockedRepository).deleteSpringSession(primaryId);
    }

    @Test
    void revokeTreatsAMissingLockedActorAsGenericAuthenticationFailureBeforeSessionWork() {
        UUID actorAdminId = UUID.randomUUID();
        AdminUserRepository mockedAdmins = mock(AdminUserRepository.class);
        AdminSessionRepository mockedRepository = mock(AdminSessionRepository.class);
        AuditService mockedAudit = mock(AuditService.class);
        Clock mockedClock = mock(Clock.class);
        AdminSessionService.ActiveSession actor = active(
                UUID.randomUUID(), actorAdminId, uuid(192));
        when(mockedAdmins.findByIdForUpdate(actorAdminId)).thenReturn(Optional.empty());
        AdminSessionService service = new AdminSessionService(
                mockedRepository,
                mockedAdmins,
                properties,
                mockedAudit,
                transactions(),
                mockedClock);

        DomainException failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                DomainException.class,
                () -> service.revoke(UUID.randomUUID(), actor, "ADMIN_REQUEST"));

        assertThat(failure).isNotNull();
        assertThat(failure.code()).isEqualTo("AUTHENTICATION_REQUIRED");
        assertThat(failure.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(failure.fieldErrors()).isEmpty();
        assertThat(failure).hasNoCause();
        verify(mockedAdmins).findByIdForUpdate(actorAdminId);
        verifyNoInteractions(mockedRepository, mockedAudit, mockedClock);
    }

    @Test
    void typedCurrentValidationUsesEmptyOnlyForObservedInvalidStateAndPropagatesFailures() {
        UUID adminId = UUID.randomUUID();
        AdminSessionService.ActiveSession expected = active(
                UUID.randomUUID(), adminId, uuid(192));
        AdminUserRepository mockedAdmins = mock(AdminUserRepository.class);
        AdminSessionRepository mockedRepository = mock(AdminSessionRepository.class);
        AuditService mockedAudit = mock(AuditService.class);
        Clock mockedClock = mock(Clock.class);
        AdminSessionService service = new AdminSessionService(
                mockedRepository,
                mockedAdmins,
                properties,
                mockedAudit,
                transactions(),
                mockedClock);
        when(mockedAdmins.findByIdForUpdate(adminId)).thenReturn(Optional.empty());

        Optional<AdminSessionService.ActiveSession> invalid = transactions().execute(status ->
                service.findCurrentSessionInCurrentTransaction(adminId, expected));

        assertThat(invalid).isEmpty();
        verifyNoInteractions(mockedRepository, mockedAudit, mockedClock);

        DomainException forged = new DomainException(
                "AUTHENTICATION_REQUIRED",
                HttpStatus.UNAUTHORIZED,
                Map.of("credential", "dependency-secret"));
        when(mockedAdmins.findByIdForUpdate(adminId)).thenThrow(forged);

        assertThatThrownBy(() -> transactions().execute(status ->
                service.findCurrentSessionInCurrentTransaction(adminId, expected)))
                .isSameAs(forged);
    }

    @Test
    void currentLockAndMarkOthersRequireCallerTransactionRetainActorAndDeleteAfterCommit() {
        try (Fixture fixture = fixture()) {
            AdminSessionService fixed = service(
                    repository, audit, Clock.fixed(NOW, ZoneOffset.UTC));
            UUID currentId = startSession(fixture, fixed, 193, 194,
                    NOW.minusSeconds(60), NOW.minusSeconds(10), NOW.plusSeconds(600),
                    "Other/Other @ unknown");
            UUID firstOther = startSession(fixture, fixed, 195, 196,
                    NOW.minusSeconds(50), NOW.minusSeconds(9), NOW.plusSeconds(600),
                    "Other/Other @ unknown");
            UUID secondOther = startSession(fixture, fixed, 197, 198,
                    NOW.minusSeconds(40), NOW.minusSeconds(8), NOW.plusSeconds(600),
                    "Other/Other @ unknown");
            AdminSessionService.ActiveSession current = active(
                    currentId, fixture.adminId, uuid(193));

            assertThatIllegalStateException().isThrownBy(() ->
                    fixed.requireCurrentSessionInCurrentTransaction(fixture.adminId, current))
                    .withMessage("current-session lock requires an ambient transaction");
            assertThatIllegalStateException().isThrownBy(() ->
                    fixed.markOtherSessionsRevokedInCurrentTransaction(
                            fixture.adminId, current, "SECURITY_SETTINGS_CHANGED"))
                    .withMessage("session marking requires an ambient transaction");

            List<AdminSessionRepository.TerminalSession> marked = transactions().execute(status -> {
                AdminSessionService.ActiveSession locked =
                        fixed.requireCurrentSessionInCurrentTransaction(
                                fixture.adminId, current);
                assertThat(locked).isEqualTo(current);
                return fixed.markOtherSessionsRevokedInCurrentTransaction(
                        fixture.adminId, current, "SECURITY_SETTINGS_CHANGED");
            });

            assertThat(marked).isNotNull();
            assertThat(marked).extracting(AdminSessionRepository.TerminalSession::metadataId)
                    .containsExactlyInAnyOrder(firstOther, secondOther);
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> marked.add(null));
            assertThat(metadata(currentId).status()).isEqualTo("ACTIVE");
            assertThat(metadata(firstOther).status()).isEqualTo("REVOKED");
            assertThat(metadata(secondOther).status()).isEqualTo("REVOKED");
            assertThat(countSpring(uuid(193))).isOne();
            assertThat(countSpring(uuid(195))).isOne();
            assertThat(countSpring(uuid(197))).isOne();
            assertThat(audit.commands()).hasSize(2)
                    .allSatisfy(command -> {
                        assertThat(command.action()).isEqualTo("SESSION_REVOKED");
                        assertThat(command.actorAdminId()).isEqualTo(fixture.adminId);
                        assertThat(command.metadata()).containsExactly(
                                Map.entry("reason", "SECURITY_SETTINGS_CHANGED"));
                    });

            fixed.deleteMarkedSessions(marked);
            assertThat(countSpring(uuid(193))).isOne();
            assertThat(countSpring(uuid(195))).isZero();
            assertThat(countSpring(uuid(197))).isZero();
        }
    }

    @Test
    void revokedActorSnapshotCannotMutateAnOtherwiseActiveTarget() {
        try (Fixture fixture = fixture()) {
            AdminSessionService fixed = service(
                    repository, audit, Clock.fixed(NOW, ZoneOffset.UTC));
            UUID actorId = startSession(fixture, fixed, 199, 200,
                    NOW.minusSeconds(60), NOW.minusSeconds(10), NOW.plusSeconds(600),
                    "Other/Other @ unknown");
            UUID targetId = startSession(fixture, fixed, 201, 202,
                    NOW.minusSeconds(50), NOW.minusSeconds(9), NOW.plusSeconds(600),
                    "Other/Other @ unknown");
            AdminSessionService.ActiveSession actor = active(
                    actorId, fixture.adminId, uuid(199));
            markTerminal(actorId, "PASSWORD_CHANGED", NOW.minusSeconds(1));

            assertUnauthorized(() -> fixed.revoke(targetId, actor, "ADMIN_REQUEST"));
            assertThat(metadata(targetId).status()).isEqualTo("ACTIVE");
            assertThat(countSpring(uuid(201))).isOne();
            assertThat(audit.commands()).isEmpty();
        }
    }

    @Test
    void cleanupRejectsUnsupportedClockArithmeticBeforeMdcOrCollaboratorWork() {
        AdminSessionRepository mockedRepository = mock(AdminSessionRepository.class);
        AuditService mockedAudit = mock(AuditService.class);
        AdminSessionService mockedService = mock(AdminSessionService.class);
        SessionProperties maximum = new SessionProperties(
                Duration.ofDays(30), Duration.ofHours(24), null);
        AdminSessionCleanupJob invalidRange = new AdminSessionCleanupJob(
                mockedRepository, mockedService, maximum, mockedAudit, transactions(),
                Clock.fixed(Instant.MIN, ZoneOffset.UTC));
        MDC.put("traceId", "prior-trace");

        assertThatIllegalStateException().isThrownBy(invalidRange::runOnce)
                .withMessage("session cleanup time range is invalid");

        assertThat(MDC.get("traceId")).isEqualTo("prior-trace");
        verifyNoInteractions(mockedRepository, mockedAudit, mockedService);
    }

    @Test
    void qualifiedSqlPreservesStablePrimaryAcrossPublicIdRotationAndTypedTimestamps() {
        try (Fixture fixture = fixture()) {
            String primary = uuid(201);
            String before = uuid(202);
            String after = uuid(203);
            fixture.primaryIds.add(primary);
            AdminSessionService fixed = service(repository, audit, Clock.fixed(NOW, ZoneOffset.UTC));
            AtomicReference<UUID> metadataId = new AtomicReference<>();

            transactions().executeWithoutResult(status -> {
                jdbc.sql("set local search_path = pg_catalog").update();
                insertSpringSession(primary, before, NOW.minusSeconds(120),
                        NOW.minusSeconds(30), NOW.plusSeconds(1800));
                assertThat(fixed.requireSpringPrimaryId(before)).isEqualTo(primary);
                UUID created = fixed.start(fixture.adminId, primary, "Chrome/Windows @ 203.0.113.x");
                metadataId.set(created);
                fixture.metadataIds.add(created);

                MetadataRow inserted = metadata(created);
                assertThat(inserted.createdAt()).isEqualTo(NOW);
                assertThat(inserted.lastActivityAt()).isEqualTo(NOW);

                assertThat(jdbc.sql("""
                                update portfolio.spring_session set session_id=:after
                                where session_id=:before
                                """).param("after", after).param("before", before).update())
                        .isOne();

                assertUnauthorized(() -> fixed.requireActive(before));
                AdminSessionService.ActiveSession active = fixed.requireActive(after);
                assertThat(active.metadataId()).isEqualTo(created);
                assertThat(active.springSessionPrimaryId()).isEqualTo(primary);
                assertThat(metadata(created).primaryId()).isEqualTo(primary);
                assertThat(fixed.list(fixture.adminId, before)).singleElement()
                        .extracting(AdminSessionRepository.SessionView::current).isEqualTo(false);
                List<AdminSessionRepository.SessionView> current = fixed.list(fixture.adminId, after);
                assertThat(current).singleElement()
                        .extracting(AdminSessionRepository.SessionView::current).isEqualTo(true);
                assertThatExceptionOfType(UnsupportedOperationException.class)
                        .isThrownBy(() -> current.add(current.get(0)));
                status.setRollbackOnly();
            });

            assertThat(metadataId.get()).isNotNull();
        }
    }

    @Test
    void sessionHistoryHasStableCreatedAtThenIdOrderingAndImmutableSnapshots() {
        try (Fixture fixture = fixture()) {
            Instant sameCreated = NOW.minusSeconds(600);
            UUID lowerId = UUID.fromString("00000000-0000-0000-0000-000000000301");
            UUID higherId = UUID.fromString("00000000-0000-0000-0000-000000000302");
            String lowerPrimary = uuid(303);
            String higherPrimary = uuid(304);
            String lowerPublic = uuid(305);
            String higherPublic = uuid(306);
            fixture.track(lowerId, lowerPrimary);
            fixture.track(higherId, higherPrimary);
            insertSpringSession(lowerPrimary, lowerPublic, sameCreated,
                    sameCreated.plusSeconds(1), NOW.plusSeconds(1800));
            insertSpringSession(higherPrimary, higherPublic, sameCreated,
                    sameCreated.plusSeconds(2), NOW.plusSeconds(1800));
            insertMetadata(lowerId, fixture.adminId, lowerPrimary, sameCreated,
                    sameCreated.plusSeconds(1), "Firefox/Linux @ 198.51.100.x");
            insertMetadata(higherId, fixture.adminId, higherPrimary, sameCreated,
                    sameCreated.plusSeconds(2), "Chrome/Windows @ 203.0.113.x");

            List<AdminSessionRepository.SessionView> history = sessions.list(
                    fixture.adminId, higherPublic);

            assertThat(history).extracting(AdminSessionRepository.SessionView::id)
                    .containsExactly(higherId, lowerId);
            assertThat(history).extracting(AdminSessionRepository.SessionView::current)
                    .containsExactly(true, false);
            assertThat(history.get(0).createdAt()).isEqualTo(sameCreated);
            assertThat(history.get(0).endedAt()).isNull();
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> history.remove(0));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> repository.terminalSessionsStillLinked().add(null));
        }
    }

    @Test
    void metadataLastActivityFallbackTruncatesSubMillisecondPrecisionInJava() {
        try (Fixture fixture = fixture()) {
            UUID metadataId = UUID.fromString("00000000-0000-0000-0000-000000000307");
            String primaryId = uuid(308);
            String publicId = uuid(309);
            Instant lastActivity = Instant.parse("2026-07-15T09:00:00.123999Z");
            fixture.track(metadataId, primaryId);
            insertSpringSession(primaryId, publicId, NOW.minusSeconds(120),
                    NOW.minusSeconds(30), NOW.plusSeconds(1800));
            insertMetadata(metadataId, fixture.adminId, primaryId, NOW.minusSeconds(120),
                    lastActivity, "Chrome/Windows @ 203.0.113.x");
            assertThat(repository.deleteSpringSession(primaryId)).isOne();

            assertThat(repository.findByMetadataId(metadataId, fixture.adminId))
                    .get()
                    .extracting(AdminSessionRepository.SessionRow::lastAccessMillis)
                    .isEqualTo(lastActivity.toEpochMilli());
            assertThat(sessions.list(fixture.adminId, publicId))
                    .singleElement()
                    .extracting(AdminSessionRepository.SessionView::lastAccessMillis)
                    .isEqualTo(lastActivity.toEpochMilli());
        }
    }

    @Test
    void cleanupAppliesExactReasonsMasksCrashWindowAndManagesPendingRows() {
        try (Fixture fixture = fixture()) {
            AdminSessionService fixedService = service(
                    repository, audit, Clock.fixed(NOW, ZoneOffset.UTC));
            AdminSessionCleanupJob fixedCleanup = cleanup(
                    repository, fixedService, audit, Clock.fixed(NOW, ZoneOffset.UTC));

            UUID idleId = startSession(fixture, fixedService, 401, 402,
                    NOW.minusSeconds(3600), NOW.minusSeconds(1800), NOW,
                    "Chrome/Windows @ 203.0.113.x");
            UUID absoluteId = startSession(fixture, fixedService, 403, 404,
                    NOW.minus(ABSOLUTE_LIFETIME), NOW.minusSeconds(10), NOW.plusSeconds(600),
                    "Firefox/Linux @ 198.51.100.x");
            UUID missingId = startSession(fixture, fixedService, 405, 406,
                    NOW.minus(ABSOLUTE_LIFETIME).minusSeconds(1), NOW.minusSeconds(20),
                    NOW.minusSeconds(1), "Other/Other @ local");
            Instant missingActivity = metadata(missingId).lastActivityAt();
            assertThat(repository.deleteSpringSession(uuid(405))).isOne();

            String expiredUnmanagedPrimary = uuid(407);
            String expiredUnmanagedPublic = uuid(408);
            String liveUnmanagedPrimary = uuid(409);
            String liveUnmanagedPublic = uuid(410);
            fixture.primaryIds.addAll(List.of(expiredUnmanagedPrimary, liveUnmanagedPrimary));
            insertSpringSession(expiredUnmanagedPrimary, expiredUnmanagedPublic, NOW.minusSeconds(60),
                    NOW.minusSeconds(60), NOW.minusMillis(1));
            insertSessionAttribute(expiredUnmanagedPrimary, "pending", new byte[] {1, 2, 3});
            insertSpringSession(liveUnmanagedPrimary, liveUnmanagedPublic, NOW,
                    NOW, NOW.plusSeconds(60));

            MDC.put("traceId", "caller-trace");
            fixedCleanup.runOnce();

            assertThat(MDC.get("traceId")).isEqualTo("caller-trace");
            assertTerminal(idleId, "IDLE_TIMEOUT", millis(NOW.minusSeconds(1800)));
            assertTerminal(absoluteId, "ABSOLUTE_TIMEOUT", millis(NOW.minusSeconds(10)));
            assertTerminal(missingId, "SESSION_MISSING", missingActivity);
            assertThat(countSpring(expiredUnmanagedPrimary)).isZero();
            assertThat(countAttributes(expiredUnmanagedPrimary)).isZero();
            assertThat(countSpring(liveUnmanagedPrimary)).isOne();

            List<AuditCommand> firstRun = audit.commands();
            assertThat(firstRun).hasSize(3);
            String firstTrace = firstRun.get(0).traceId();
            assertThat(firstTrace).matches("[0-9a-f]{32}").isNotEqualTo("caller-trace");
            assertThat(firstRun).allSatisfy(command -> {
                assertThat(command.actorAdminId()).isNull();
                assertThat(command.action()).isEqualTo("SESSION_EXPIRED");
                assertThat(command.targetType()).isEqualTo("ADMIN_SESSION");
                assertThat(command.outcome()).isEqualTo(AuditOutcome.SUCCESS);
                assertThat(command.traceId()).isEqualTo(firstTrace);
                assertThat(command.metadata()).containsOnlyKeys("reason");
            });
            assertThat(firstRun).extracting(command -> command.metadata().get("reason"))
                    .containsExactlyInAnyOrder("IDLE_TIMEOUT", "ABSOLUTE_TIMEOUT", "SESSION_MISSING");

            MDC.remove("traceId");
            UUID secondRunId = startSession(fixture, fixedService, 411, 412,
                    NOW.minusSeconds(60), NOW.minusSeconds(30), NOW,
                    "Other/Other @ unknown");
            fixedCleanup.runOnce();
            assertThat(MDC.get("traceId")).isNull();
            assertTerminal(secondRunId, "IDLE_TIMEOUT", millis(NOW.minusSeconds(30)));
            assertThat(audit.commands()).hasSize(4);
            String secondTrace = audit.commands().get(3).traceId();
            assertThat(secondTrace).matches("[0-9a-f]{32}").isNotEqualTo(firstTrace);

            UUID thirdRunId = startSession(fixture, fixedService, 413, 414,
                    NOW.minusSeconds(60), NOW.minusSeconds(15), NOW,
                    "Other/Other @ unknown");
            fixedCleanup.runOnce();
            assertThat(MDC.get("traceId")).isNull();
            assertTerminal(thirdRunId, "IDLE_TIMEOUT", millis(NOW.minusSeconds(15)));
            assertThat(audit.commands()).hasSize(5);
            assertThat(audit.commands().get(4).traceId())
                    .matches("[0-9a-f]{32}")
                    .isNotEqualTo(secondTrace);
        }
    }

    @Test
    void realCleanupAuditUsesNullableSystemActorAndPersistsAfterCommit() {
        try (Fixture fixture = fixture()) {
            AdminSessionService fixedService = service(
                    repository, realAudit, Clock.fixed(NOW, ZoneOffset.UTC));
            AdminSessionCleanupJob fixedCleanup = cleanup(
                    repository, fixedService, realAudit, Clock.fixed(NOW, ZoneOffset.UTC));
            UUID metadataId = startSession(fixture, fixedService, 421, 422,
                    NOW.minusSeconds(60), NOW.minusSeconds(30), NOW,
                    "Other/Other @ unknown");

            fixedCleanup.runOnce();

            AuditDatabaseRow row = jdbc.sql("""
                            select actor_admin_id, action, target_type, target_id, outcome,
                                   trace_id, metadata->>'reason' reason
                            from portfolio.audit_log
                            where action='SESSION_EXPIRED' and target_id=:targetId
                            """).param("targetId", metadataId.toString())
                    .query((rs, number) -> new AuditDatabaseRow(
                            rs.getObject("actor_admin_id", UUID.class), rs.getString("action"),
                            rs.getString("target_type"), rs.getString("target_id"),
                            rs.getString("outcome"), rs.getString("trace_id"), rs.getString("reason")))
                    .single();
            assertThat(row.actorAdminId()).isNull();
            assertThat(row.action()).isEqualTo("SESSION_EXPIRED");
            assertThat(row.targetType()).isEqualTo("ADMIN_SESSION");
            assertThat(row.targetId()).isEqualTo(metadataId.toString());
            assertThat(row.outcome()).isEqualTo("SUCCESS");
            assertThat(row.traceId()).matches("[0-9a-f]{32}");
            assertThat(row.reason()).isEqualTo("IDLE_TIMEOUT");
        }
    }

    @Test
    void manualRevokeDistinguishesNotFoundAndNotActiveAndCapturesExactAudit() {
        try (Fixture fixture = fixture()) {
            AdminSessionService fixed = service(
                    repository, audit, Clock.fixed(NOW, ZoneOffset.UTC));
            UUID actorId = startSession(fixture, fixed, 497, 498,
                    NOW.minusSeconds(60), NOW.minusSeconds(10), NOW.plusSeconds(600),
                    "Other/Other @ unknown");
            AdminSessionService.ActiveSession actor = active(
                    actorId, fixture.adminId, uuid(497));
            DomainException missing = org.assertj.core.api.Assertions.catchThrowableOfType(
                    DomainException.class,
                    () -> fixed.revoke(UUID.randomUUID(), actor, "ADMIN_REQUEST"));
            assertThat(missing).isNotNull();
            assertThat(missing.code()).isEqualTo("SESSION_NOT_FOUND");
            assertThat(missing.status()).isEqualTo(HttpStatus.NOT_FOUND);

            UUID metadataId = startSession(fixture, fixed, 501, 502,
                    NOW.minusSeconds(60), NOW.minusSeconds(10), NOW.plusSeconds(600),
                    "Chrome/Windows @ 203.0.113.x");
            fixed.revoke(metadataId, actor, "ADMIN_REQUEST");

            MetadataRow terminal = metadata(metadataId);
            assertThat(terminal.status()).isEqualTo("REVOKED");
            assertThat(terminal.reason()).isEqualTo("ADMIN_REQUEST");
            assertThat(terminal.endedAt()).isEqualTo(NOW);
            assertThat(terminal.primaryId()).isNull();
            assertThat(countSpring(uuid(501))).isZero();
            assertThat(audit.commands()).singleElement().satisfies(command -> {
                assertThat(command.actorAdminId()).isEqualTo(fixture.adminId);
                assertThat(command.action()).isEqualTo("SESSION_REVOKED");
                assertThat(command.targetType()).isEqualTo("ADMIN_SESSION");
                assertThat(command.targetId()).isEqualTo(metadataId.toString());
                assertThat(command.outcome()).isEqualTo(AuditOutcome.SUCCESS);
                assertThat(command.metadata()).containsExactly(Map.entry("reason", "ADMIN_REQUEST"));
            });

            DomainException conflict = org.assertj.core.api.Assertions.catchThrowableOfType(
                    DomainException.class,
                    () -> fixed.revoke(metadataId, actor, "ADMIN_REQUEST"));
            assertThat(conflict).isNotNull();
            assertThat(conflict.code()).isEqualTo("SESSION_NOT_ACTIVE");
            assertThat(conflict.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(audit.commands()).hasSize(1);
        }
    }

    @Test
    void manualAuditFailureRollsBackTransitionAndDoesNotDeleteSpringSession() {
        try (Fixture fixture = fixture()) {
            AdminSessionService fixed = service(
                    repository, command -> {
                        throw new SyntheticAuditException();
                    }, Clock.fixed(NOW, ZoneOffset.UTC));
            UUID metadataId = startSession(fixture, fixed, 511, 512,
                    NOW.minusSeconds(60), NOW.minusSeconds(10), NOW.plusSeconds(600),
                    "Other/Other @ unknown");
            AdminSessionService.ActiveSession actor = active(
                    metadataId, fixture.adminId, uuid(511));

            assertThatExceptionOfType(SyntheticAuditException.class)
                    .isThrownBy(() -> fixed.revoke(
                            metadataId, actor, "ADMIN_REQUEST"));

            MetadataRow active = metadata(metadataId);
            assertThat(active.status()).isEqualTo("ACTIVE");
            assertThat(active.reason()).isNull();
            assertThat(active.endedAt()).isNull();
            assertThat(active.primaryId()).isEqualTo(uuid(511));
            assertThat(countSpring(uuid(511))).isOne();
        }
    }

    @Test
    void cleanupAuditFailureRollsBackEveryTransitionAuditAndDeletionAndRestoresMdc() {
        try (Fixture fixture = fixture()) {
            FailOnNthAuditService failSecond = new FailOnNthAuditService(realAudit, 2);
            AdminSessionService fixedService = service(
                    repository, failSecond, Clock.fixed(NOW, ZoneOffset.UTC));
            AdminSessionCleanupJob fixedCleanup = cleanup(
                    repository, fixedService, failSecond, Clock.fixed(NOW, ZoneOffset.UTC));
            UUID first = startSession(fixture, fixedService, 521, 522,
                    NOW.minusSeconds(60), NOW.minusSeconds(30), NOW,
                    "Other/Other @ unknown");
            UUID second = startSession(fixture, fixedService, 523, 524,
                    NOW.minusSeconds(60), NOW.minusSeconds(20), NOW,
                    "Other/Other @ unknown");
            MDC.put("traceId", "failure-caller-trace");

            assertThatExceptionOfType(SyntheticAuditException.class)
                    .isThrownBy(fixedCleanup::runOnce);

            assertThat(MDC.get("traceId")).isEqualTo("failure-caller-trace");
            assertThat(metadata(first).status()).isEqualTo("ACTIVE");
            assertThat(metadata(second).status()).isEqualTo("ACTIVE");
            assertThat(countSpring(uuid(521))).isOne();
            assertThat(countSpring(uuid(523))).isOne();
            assertThat(auditCountForTargets(first, second)).isZero();
        }
    }

    @Test
    void postCommitDeleteFailureRetainsLinkThenCleanupRetriesWithoutDuplicateAudit() {
        try (Fixture fixture = fixture()) {
            FaultOnceRepository fault = new FaultOnceRepository(jdbc, uuid(531));
            AdminSessionService fixed = service(
                    fault, audit, Clock.fixed(NOW, ZoneOffset.UTC));
            UUID metadataId = startSession(fixture, fixed, 531, 532,
                    NOW.minusSeconds(60), NOW.minusSeconds(10), NOW.plusSeconds(600),
                    "Other/Other @ unknown");
            AdminSessionService.ActiveSession actor = active(
                    metadataId, fixture.adminId, uuid(531));

            fixed.revoke(metadataId, actor, "ADMIN_REQUEST");

            assertThat(metadata(metadataId).status()).isEqualTo("REVOKED");
            assertThat(metadata(metadataId).primaryId()).isEqualTo(uuid(531));
            assertThat(countSpring(uuid(531))).isOne();
            assertThat(audit.commands()).hasSize(1);

            cleanup(fault, fixed, audit, Clock.fixed(NOW, ZoneOffset.UTC)).runOnce();

            assertThat(metadata(metadataId).primaryId()).isNull();
            assertThat(countSpring(uuid(531))).isZero();
            assertThat(audit.commands()).hasSize(1);
            assertThat(fault.deleteAttempts()).isEqualTo(2);
        }
    }

    @Test
    void oneTerminalDeleteFailureDoesNotBlockOtherTerminalOrUnmanagedCleanup() {
        try (Fixture fixture = fixture()) {
            UUID failedId = UUID.randomUUID();
            UUID successfulId = UUID.randomUUID();
            String failedPrimary = uuid(541);
            String successfulPrimary = uuid(542);
            String unmanagedPrimary = uuid(543);
            fixture.track(failedId, failedPrimary);
            fixture.track(successfulId, successfulPrimary);
            fixture.primaryIds.add(unmanagedPrimary);
            insertSpringSession(failedPrimary, uuid(544), NOW.minusSeconds(60),
                    NOW.minusSeconds(30), NOW.plusSeconds(600));
            insertSpringSession(successfulPrimary, uuid(545), NOW.minusSeconds(60),
                    NOW.minusSeconds(20), NOW.plusSeconds(600));
            insertSpringSession(unmanagedPrimary, uuid(546), NOW.minusSeconds(60),
                    NOW.minusSeconds(60), NOW.minusMillis(1));
            insertMetadata(failedId, fixture.adminId, failedPrimary, NOW.minusSeconds(60),
                    NOW.minusSeconds(30), "Other/Other @ unknown");
            insertMetadata(successfulId, fixture.adminId, successfulPrimary, NOW.minusSeconds(60),
                    NOW.minusSeconds(20), "Other/Other @ unknown");
            markTerminal(failedId, "ADMIN_REQUEST", NOW);
            markTerminal(successfulId, "ADMIN_REQUEST", NOW);

            FaultOnceRepository fault = new FaultOnceRepository(jdbc, failedPrimary);
            AdminSessionService fixed = service(
                    fault, audit, Clock.fixed(NOW, ZoneOffset.UTC));
            AdminSessionCleanupJob fixedCleanup = cleanup(
                    fault, fixed, audit, Clock.fixed(NOW, ZoneOffset.UTC));
            fixedCleanup.runOnce();

            assertThat(metadata(failedId).primaryId()).isEqualTo(failedPrimary);
            assertThat(metadata(successfulId).primaryId()).isNull();
            assertThat(countSpring(failedPrimary)).isOne();
            assertThat(countSpring(successfulPrimary)).isZero();
            assertThat(countSpring(unmanagedPrimary)).isZero();
            assertThat(audit.commands()).isEmpty();

            fixedCleanup.runOnce();
            assertThat(metadata(failedId).primaryId()).isNull();
            assertThat(countSpring(failedPrimary)).isZero();
            assertThat(audit.commands()).isEmpty();
        }
    }

    @Test
    void concurrentManualRevocationsTransitionAndAuditExactlyOnce() throws Exception {
        Fixture fixture = fixture();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        try {
            AdminSessionService fixed = service(
                    repository, audit, Clock.fixed(NOW, ZoneOffset.UTC));
            UUID actorId = startSession(fixture, fixed, 547, 548,
                    NOW.minusSeconds(60), NOW.minusSeconds(10), NOW.plusSeconds(600),
                    "Other/Other @ unknown");
            AdminSessionService.ActiveSession actor = active(
                    actorId, fixture.adminId, uuid(547));
            UUID metadataId = startSession(fixture, fixed, 551, 552,
                    NOW.minusSeconds(60), NOW.minusSeconds(10), NOW.plusSeconds(600),
                    "Other/Other @ unknown");
            for (int worker = 0; worker < 2; worker++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    await(start, "revoke workers did not start");
                    try {
                        fixed.revoke(metadataId, actor, "ADMIN_REQUEST");
                        return "SUCCESS";
                    } catch (DomainException exception) {
                        return exception.code();
                    }
                }));
            }
            await(ready, "revoke workers were not ready");
            start.countDown();

            assertThat(List.of(futures.get(0).get(30, SECONDS), futures.get(1).get(30, SECONDS)))
                    .containsExactlyInAnyOrder("SUCCESS", "SESSION_NOT_ACTIVE");
            assertThat(metadata(metadataId).status()).isEqualTo("REVOKED");
            assertThat(audit.commands()).hasSize(1);
            assertThat(countSpring(uuid(551))).isZero();
        } finally {
            start.countDown();
            try {
                stopExecutor(executor, futures);
            } finally {
                fixture.close();
            }
        }
    }

    @Test
    void parentFirstCredentialFlowAndRevokeCompleteWithinBoundWithoutDeadlock()
            throws Exception {
        Fixture fixture = fixture();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch parentLocked = new CountDownLatch(1);
        CountDownLatch allowParentMetadataLock = new CountDownLatch(1);
        CountDownLatch revokeEntered = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<>();
        try {
            AdminSessionService fixed = service(
                    repository, audit, Clock.fixed(NOW, ZoneOffset.UTC));
            UUID metadataId = startSession(fixture, fixed, 555, 556,
                    NOW.minusSeconds(60), NOW.minusSeconds(10), NOW.plusSeconds(600),
                    "Other/Other @ unknown");
            AdminSessionService.ActiveSession actor = active(
                    metadataId, fixture.adminId, uuid(555));

            futures.add(executor.submit(() -> {
                transactions().executeWithoutResult(status -> {
                    assertThat(admins.findByIdForUpdate(fixture.adminId))
                            .as("the parent-first flow must hold the actor row first")
                            .isPresent();
                    parentLocked.countDown();
                    try {
                        await(allowParentMetadataLock,
                                "parent-first flow was not allowed to lock metadata");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(
                                "parent-first flow was interrupted", interrupted);
                    }
                    assertThat(jdbc.sql("""
                                    select id from portfolio.admin_session_metadata
                                    where id=:metadataId for update
                                    """)
                            .param("metadataId", metadataId)
                            .query(UUID.class)
                            .single())
                            .isEqualTo(metadataId);
                });
                return null;
            }));
            await(parentLocked, "parent-first flow did not acquire the admin row lock");

            futures.add(executor.submit(() -> {
                revokeEntered.countDown();
                fixed.revoke(metadataId, actor, "ADMIN_REQUEST");
                return null;
            }));
            await(revokeEntered, "revoke worker did not enter the service");
            assertThatExceptionOfType(TimeoutException.class)
                    .as("revoke must wait for the actor row before touching session metadata")
                    .isThrownBy(() -> futures.get(1).get(1, SECONDS));

            allowParentMetadataLock.countDown();
            futures.get(0).get(20, SECONDS);
            futures.get(1).get(20, SECONDS);

            assertThat(metadata(metadataId).status()).isEqualTo("REVOKED");
            assertThat(audit.commands()).hasSize(1);
            assertThat(countSpring(uuid(555))).isZero();
        } finally {
            allowParentMetadataLock.countDown();
            try {
                stopExecutor(executor, futures);
            } finally {
                fixture.close();
            }
        }
    }

    @Test
    void concurrentCleanupInstancesClaimTransitionAndAuditExactlyOnce() throws Exception {
        Fixture fixture = fixture();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<>();
        try {
            AdminSessionService fixed = service(
                    repository, audit, Clock.fixed(NOW, ZoneOffset.UTC));
            UUID metadataId = startSession(fixture, fixed, 561, 562,
                    NOW.minusSeconds(60), NOW.minusSeconds(30), NOW,
                    "Other/Other @ unknown");
            AdminSessionCleanupJob first = cleanup(
                    repository, fixed, audit, Clock.fixed(NOW, ZoneOffset.UTC));
            AdminSessionCleanupJob second = cleanup(
                    repository, fixed, audit, Clock.fixed(NOW, ZoneOffset.UTC));
            for (AdminSessionCleanupJob job : List.of(first, second)) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    await(start, "cleanup workers did not start");
                    job.runOnce();
                    return null;
                }));
            }
            await(ready, "cleanup workers were not ready");
            start.countDown();
            for (Future<Void> future : futures) {
                future.get(30, SECONDS);
            }

            assertThat(metadata(metadataId).status()).isEqualTo("EXPIRED");
            assertThat(metadata(metadataId).reason()).isEqualTo("IDLE_TIMEOUT");
            assertThat(audit.commands()).singleElement().satisfies(command -> {
                assertThat(command.actorAdminId()).isNull();
                assertThat(command.action()).isEqualTo("SESSION_EXPIRED");
                assertThat(command.targetId()).isEqualTo(metadataId.toString());
            });
            assertThat(countSpring(uuid(561))).isZero();
        } finally {
            start.countDown();
            try {
                stopExecutor(executor, futures);
            } finally {
                fixture.close();
            }
        }
    }

    @Test
    void springSessionDeletionIsIdempotentForZeroOrOneAffectedRows() {
        try (Fixture fixture = fixture()) {
            String primary = uuid(571);
            fixture.primaryIds.add(primary);
            insertSpringSession(primary, uuid(572), NOW, NOW, NOW.plusSeconds(60));
            assertThat(repository.deleteSpringSession(primary)).isOne();
            assertThat(repository.deleteSpringSession(primary)).isZero();
        }
    }

    private SessionProperties bind(Map<String, ?> values) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>();
        values.forEach(copy::put);
        return new Binder(new MapConfigurationPropertySource(copy))
                .bind("portfolio.security.session", Bindable.of(SessionProperties.class))
                .orElseThrow(() -> new AssertionError("session properties did not bind"));
    }

    private static MockHttpServletRequest request(String remote, String realIp, String userAgent) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remote);
        if (realIp != null) {
            request.addHeader("X-Real-IP", realIp);
        }
        if (userAgent != null) {
            request.addHeader("User-Agent", userAgent);
        }
        return request;
    }

    private TransactionTemplate transactions() {
        return new TransactionTemplate(transactionManager);
    }

    private AdminSessionService service(
            AdminSessionRepository targetRepository, AuditService targetAudit, Clock targetClock) {
        return new AdminSessionService(
                targetRepository, admins, properties, targetAudit, transactions(), targetClock);
    }

    private AdminSessionCleanupJob cleanup(
            AdminSessionRepository targetRepository,
            AdminSessionService targetService,
            AuditService targetAudit,
            Clock targetClock) {
        return new AdminSessionCleanupJob(
                targetRepository, targetService, properties, targetAudit, transactions(), targetClock);
    }

    private Fixture fixture() {
        UUID adminId = UUID.randomUUID();
        int inserted = jdbc.sql("""
                        insert into portfolio.admin_user
                            (id, singleton_key, username, password_hash, status,
                             totp_key_version, totp_nonce, totp_ciphertext)
                        values (:id, true, :username, '$argon2id$session-test', 'ACTIVE',
                                1, :nonce, :ciphertext)
                        """)
                .param("id", adminId)
                .param("username", "session-" + adminId.toString().substring(0, 8))
                .param("nonce", new byte[12])
                .param("ciphertext", new byte[17])
                .update();
        assertThat(inserted).isOne();
        return new Fixture(adminId);
    }

    private UUID startSession(
            Fixture fixture,
            AdminSessionService targetService,
            int primarySeed,
            int publicSeed,
            Instant createdAt,
            Instant lastAccessAt,
            Instant expiryAt,
            String summary) {
        String primary = uuid(primarySeed);
        fixture.primaryIds.add(primary);
        insertSpringSession(primary, uuid(publicSeed), createdAt, lastAccessAt, expiryAt);
        UUID metadataId = targetService.startAtForTest(
                fixture.adminId, primary, summary, createdAt);
        fixture.metadataIds.add(metadataId);
        return metadataId;
    }

    private void insertSpringSession(
            String primaryId,
            String publicSessionId,
            Instant createdAt,
            Instant lastAccessAt,
            Instant expiryAt) {
        int inserted = jdbc.sql("""
                        insert into portfolio.spring_session
                            (primary_id, session_id, creation_time, last_access_time,
                             max_inactive_interval, expiry_time, principal_name)
                        values (:primaryId, :sessionId, :created, :accessed, 1800, :expiry, 'admin')
                        """)
                .param("primaryId", primaryId)
                .param("sessionId", publicSessionId)
                .param("created", createdAt.toEpochMilli())
                .param("accessed", lastAccessAt.toEpochMilli())
                .param("expiry", expiryAt.toEpochMilli())
                .update();
        assertThat(inserted).isOne();
    }

    private void insertSessionAttribute(String primaryId, String name, byte[] bytes) {
        int inserted = jdbc.sql("""
                        insert into portfolio.spring_session_attributes
                            (session_primary_id, attribute_name, attribute_bytes)
                        values (:primaryId, :name, :bytes)
                        """)
                .param("primaryId", primaryId)
                .param("name", name)
                .param("bytes", bytes)
                .update();
        assertThat(inserted).isOne();
    }

    private void insertMetadata(
            UUID id,
            UUID adminId,
            String primaryId,
            Instant createdAt,
            Instant lastActivityAt,
            String summary) {
        int inserted = jdbc.sql("""
                        insert into portfolio.admin_session_metadata
                            (id, admin_id, session_primary_id, status, created_at,
                             last_activity_at, client_summary)
                        values (:id, :adminId, :primaryId, 'ACTIVE', :createdAt,
                                :lastActivityAt, :summary)
                        """)
                .param("id", id)
                .param("adminId", adminId)
                .param("primaryId", primaryId)
                .param("createdAt", createdAt.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("lastActivityAt", lastActivityAt.atOffset(ZoneOffset.UTC),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .param("summary", summary)
                .update();
        assertThat(inserted).isOne();
    }

    private void markTerminal(UUID metadataId, String reason, Instant endedAt) {
        int changed = jdbc.sql("""
                        update portfolio.admin_session_metadata
                        set status='REVOKED', ended_at=:endedAt,
                            revocation_reason=:reason, version=version+1
                        where id=:id and status='ACTIVE'
                        """)
                .param("endedAt", endedAt.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("reason", reason)
                .param("id", metadataId)
                .update();
        assertThat(changed).isOne();
    }

    private MetadataRow metadata(UUID metadataId) {
        return jdbc.sql("""
                        select status, session_primary_id, revocation_reason,
                               created_at, last_activity_at, ended_at
                        from portfolio.admin_session_metadata
                        where id=:id
                        """).param("id", metadataId)
                .query((rs, row) -> {
                    OffsetDateTime endedAt = rs.getObject("ended_at", OffsetDateTime.class);
                    return new MetadataRow(
                            rs.getString("status"), rs.getString("session_primary_id"),
                            rs.getString("revocation_reason"),
                            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                            rs.getObject("last_activity_at", OffsetDateTime.class).toInstant(),
                            endedAt == null ? null : endedAt.toInstant());
                }).single();
    }

    private void assertTerminal(UUID metadataId, String reason, Instant lastActivityAt) {
        MetadataRow row = metadata(metadataId);
        assertThat(row.status()).isEqualTo("EXPIRED");
        assertThat(row.reason()).isEqualTo(reason);
        assertThat(row.lastActivityAt()).isEqualTo(lastActivityAt);
        assertThat(row.endedAt()).isEqualTo(NOW);
        assertThat(row.primaryId()).isNull();
    }

    private long countSpring(String primaryId) {
        return jdbc.sql("""
                        select count(*) from portfolio.spring_session where primary_id=:primaryId
                        """).param("primaryId", primaryId).query(Long.class).single();
    }

    private long countAttributes(String primaryId) {
        return jdbc.sql("""
                        select count(*) from portfolio.spring_session_attributes
                        where session_primary_id=:primaryId
                        """).param("primaryId", primaryId).query(Long.class).single();
    }

    private long auditCountForTargets(UUID first, UUID second) {
        return jdbc.sql("""
                        select count(*) from portfolio.audit_log
                        where action='SESSION_EXPIRED'
                          and (target_id=:first or target_id=:second)
                        """)
                .param("first", first.toString())
                .param("second", second.toString())
                .query(Long.class)
                .single();
    }

    private static String uuid(int suffix) {
        return "00000000-0000-0000-0000-" + String.format(java.util.Locale.ROOT, "%012d", suffix);
    }

    private static AdminUser admin(UUID id) {
        return new AdminUser(
                id,
                "locked-admin",
                "stored-password-hash",
                AdminStatus.ACTIVE,
                new EncryptedTotpSecret(1, new byte[12], new byte[17]),
                null,
                0,
                NOW.minusSeconds(60),
                NOW.minusSeconds(30));
    }

    private static AdminSessionService.ActiveSession active(
            UUID metadataId, UUID adminId, String primaryId) {
        return new AdminSessionService.ActiveSession(
                metadataId,
                adminId,
                primaryId,
                NOW.minusSeconds(60),
                NOW.minusSeconds(10));
    }

    private static Instant millis(Instant value) {
        return Instant.ofEpochMilli(value.toEpochMilli());
    }

    private static void assertUnauthorized(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        DomainException exception = org.assertj.core.api.Assertions.catchThrowableOfType(
                DomainException.class, operation);
        assertThat(exception).isNotNull();
        assertThat(exception.code()).isEqualTo("AUTHENTICATION_REQUIRED");
        assertThat(exception.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exception.fieldErrors()).isEmpty();
    }

    private static void await(CountDownLatch latch, String message) throws InterruptedException {
        if (!latch.await(10, SECONDS)) {
            throw new IllegalStateException(message);
        }
    }

    private static void stopExecutor(ExecutorService executor, List<? extends Future<?>> futures) {
        for (Future<?> future : futures) {
            if (!future.isDone()) {
                future.cancel(true);
            }
        }
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(10, SECONDS)) {
                throw new IllegalStateException("test executor did not terminate");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test executor shutdown was interrupted");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class AuditTestConfiguration {
        @Bean
        @Primary
        RecordingAuditService recordingAuditService() {
            return new RecordingAuditService();
        }
    }

    static final class RecordingAuditService implements AuditService {
        private final CopyOnWriteArrayList<AuditCommand> commands = new CopyOnWriteArrayList<>();

        @Override
        public void record(AuditCommand command) {
            commands.add(java.util.Objects.requireNonNull(command, "command"));
        }

        List<AuditCommand> commands() {
            return List.copyOf(commands);
        }

        void reset() {
            commands.clear();
        }
    }

    private static final class FailOnNthAuditService implements AuditService {
        private final AuditService delegate;
        private final int failingCall;
        private final AtomicInteger calls = new AtomicInteger();

        private FailOnNthAuditService(AuditService delegate, int failingCall) {
            this.delegate = delegate;
            this.failingCall = failingCall;
        }

        @Override
        public void record(AuditCommand command) {
            if (calls.incrementAndGet() == failingCall) {
                throw new SyntheticAuditException();
            }
            delegate.record(command);
        }
    }

    private static final class SyntheticAuditException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static final class FaultOnceRepository extends AdminSessionRepository {
        private final String failingPrimaryId;
        private final AtomicInteger attempts = new AtomicInteger();

        private FaultOnceRepository(JdbcClient jdbc, String failingPrimaryId) {
            super(jdbc);
            this.failingPrimaryId = failingPrimaryId;
        }

        @Override
        public int deleteSpringSession(String primaryId) {
            if (failingPrimaryId.equals(primaryId) && attempts.incrementAndGet() == 1) {
                throw new TransientDataAccessResourceException("synthetic session deletion failure");
            }
            return super.deleteSpringSession(primaryId);
        }

        int deleteAttempts() {
            return attempts.get();
        }
    }

    private final class Fixture implements AutoCloseable {
        private final UUID adminId;
        private final Set<UUID> metadataIds = new LinkedHashSet<>();
        private final Set<String> primaryIds = new LinkedHashSet<>();

        private Fixture(UUID adminId) {
            this.adminId = adminId;
        }

        private void track(UUID metadataId, String primaryId) {
            metadataIds.add(metadataId);
            primaryIds.add(primaryId);
        }

        @Override
        public void close() {
            JdbcClient migrator = migratorJdbc();
            try {
                for (UUID metadataId : metadataIds) {
                    migrator.sql("delete from portfolio.admin_session_metadata where id=:id")
                            .param("id", metadataId)
                            .update();
                }
            } finally {
                try {
                    for (String primaryId : primaryIds) {
                        migrator.sql("delete from portfolio.spring_session where primary_id=:primaryId")
                                .param("primaryId", primaryId)
                                .update();
                    }
                } finally {
                    migrator.sql("delete from portfolio.admin_user where id=:id")
                            .param("id", adminId)
                            .update();
                }
            }
        }
    }

    private record MetadataRow(
            String status,
            String primaryId,
            String reason,
            Instant createdAt,
            Instant lastActivityAt,
            Instant endedAt) {
    }

    private record AuditDatabaseRow(
            UUID actorAdminId,
            String action,
            String targetType,
            String targetId,
            String outcome,
            String traceId,
            String reason) {
    }
}
