package xyz.yychainsaw.portfolio.auth.cli;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.CharBuffer;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.yychainsaw.portfolio.audit.AuditCommand;
import xyz.yychainsaw.portfolio.audit.AuditOutcome;
import xyz.yychainsaw.portfolio.audit.AuditService;
import xyz.yychainsaw.portfolio.audit.JdbcAuditService;
import xyz.yychainsaw.portfolio.auth.RecoveryCodeService;
import xyz.yychainsaw.portfolio.auth.crypto.EncryptedTotpSecret;
import xyz.yychainsaw.portfolio.auth.crypto.PasswordPolicy;
import xyz.yychainsaw.portfolio.auth.crypto.RecoveryCodeGenerator;
import xyz.yychainsaw.portfolio.auth.crypto.TotpService;
import xyz.yychainsaw.portfolio.auth.model.AdminStatus;
import xyz.yychainsaw.portfolio.auth.model.AdminUser;
import xyz.yychainsaw.portfolio.auth.persistence.AdminUserRepository;
import xyz.yychainsaw.portfolio.auth.persistence.RecoveryCodeRepository;
import xyz.yychainsaw.portfolio.auth.session.AdminSessionRepository;
import xyz.yychainsaw.portfolio.auth.session.AdminSessionService;
import xyz.yychainsaw.portfolio.auth.session.SessionProperties;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
@Import(AdminRecoveryServiceTest.RecoveryTestConfiguration.class)
@TestPropertySource(properties = "portfolio.security.session.cleanup-interval=PT24H")
@Isolated
class AdminRecoveryServiceTest extends PostgresIntegrationTestBase {
    private static final UUID ADMIN_ID =
            UUID.fromString("301417e1-f2a5-4ea3-b715-5c087371415f");
    private static final long ADMIN_VERSION = 7L;
    private static final String PASSWORD = "PortfolioRecovery!2026";
    private static final String PASSWORD_HASH = "test$password-hash";
    private static final String TOTP_SECRET = "JBSWY3DPEHPK3PXP";
    private static final String TOTP_CODE = "123456";
    private static final String PROVISIONING_URI =
            "otpauth://totp/Portfolio:YYchainsaw.Admin?secret=" + TOTP_SECRET;
    private static final String BACKUP_SHA256 = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-07-16T08:09:10.123456Z");
    private static final List<String> RECOVERY_CODES = List.of(
            "ABCD-EFGH-JKLM",
            "BCDE-FGHJ-KLMN",
            "CDEF-GHJK-LMNP",
            "DEFG-HJKL-MNPQ",
            "EFGH-JKLM-NPQR",
            "FGHJ-KLMN-PQRS",
            "GHJK-LMNP-QRST",
            "HJKL-MNPQ-RSTU",
            "JKLM-NPQR-STUV",
            "KLMN-PQRS-TUVW");
    private static final List<String> RECOVERY_HASHES = List.of(
            "hash-01", "hash-02", "hash-03", "hash-04", "hash-05",
            "hash-06", "hash-07", "hash-08", "hash-09", "hash-10");
    private static final List<String> OLD_RECOVERY_HASHES =
            List.of("old-recovery-hash-a", "old-recovery-hash-b");

    @Autowired PlatformTransactionManager transactionManager;
    @Autowired AdminUserRepository integrationAdmins;
    @Autowired RecoveryCodeRepository integrationRecoveryCodes;
    @Autowired AdminSessionRepository integrationSessionRepository;
    @Autowired SessionProperties integrationSessionProperties;
    @Autowired JdbcAuditService integrationAudit;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;

    private final Set<UUID> integrationAdminIds = new LinkedHashSet<>();
    private final Set<String> integrationSpringPrimaryIds = new LinkedHashSet<>();

    @AfterEach
    void restoreInterruptFlag() {
        Thread.interrupted();
    }

    @AfterEach
    void cleanOwnedIntegrationRows() {
        if (integrationAdminIds.isEmpty() && integrationSpringPrimaryIds.isEmpty()) {
            return;
        }
        JdbcClient owner = migratorJdbc();
        Set<UUID> auditIds = new LinkedHashSet<>();
        for (UUID adminId : integrationAdminIds) {
            auditIds.addAll(owner.sql("""
                            select id from portfolio.audit_log
                            where actor_admin_id=:adminId
                            """)
                    .param("adminId", adminId)
                    .query(UUID.class)
                    .list());
        }
        try {
            owner.sql("alter table portfolio.audit_log disable trigger audit_log_reject_mutation")
                    .update();
            try {
                for (UUID auditId : auditIds) {
                    owner.sql("delete from portfolio.audit_log where id=:auditId")
                            .param("auditId", auditId)
                            .update();
                }
            } finally {
                owner.sql("alter table portfolio.audit_log enable trigger audit_log_reject_mutation")
                        .update();
            }
        } finally {
            for (UUID adminId : integrationAdminIds) {
                owner.sql("delete from portfolio.admin_session_metadata where admin_id=:adminId")
                        .param("adminId", adminId)
                        .update();
                owner.sql("delete from portfolio.totp_recovery_code where admin_id=:adminId")
                        .param("adminId", adminId)
                        .update();
                owner.sql("delete from portfolio.admin_user where id=:adminId")
                        .param("adminId", adminId)
                        .update();
            }
            for (String primaryId : integrationSpringPrimaryIds) {
                owner.sql("delete from portfolio.spring_session where primary_id=:primaryId")
                        .param("primaryId", primaryId)
                        .update();
            }
            integrationAdminIds.clear();
            integrationSpringPrimaryIds.clear();
        }
    }

    @Test
    void realHikariPgJdbcTransportInspectorPinsTheLivePeerAndRuntimeCanReadTransportState()
            throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            ClassLoader loader = PgDumpRestorePointService.class.getClassLoader();
            Class<?> hikariType = Class.forName(
                    "com.zaxxer.hikari.HikariDataSource", false, loader);
            assertThat(hikariType.isInstance(dataSource)).isTrue();
            assertThat((java.util.Properties) hikariType
                            .getMethod("getDataSourceProperties")
                            .invoke(dataSource))
                    .containsExactly(Map.entry("ApplicationName", "portfolio-server"));
            Class<?> pgConnectionType = Class.forName(
                    "org.postgresql.jdbc.PgConnection", false, loader);
            assertThat(connection.isWrapperFor(pgConnectionType)).isTrue();
            Object pgConnection = connection.unwrap(pgConnectionType);
            Object queryExecutor = pgConnectionType
                    .getMethod("getQueryExecutor")
                    .invoke(pgConnection);
            Object hostSpec = queryExecutor.getClass().getMethod("getHostSpec").invoke(queryExecutor);
            assertThat(hostSpec.getClass().getMethod("getHost").invoke(hostSpec))
                    .isEqualTo(POSTGRES.getHost());
            assertThat(hostSpec.getClass().getMethod("getPort").invoke(hostSpec))
                    .isEqualTo(POSTGRES.getFirstMappedPort());
            assertThat(hostSpec.getClass().getMethod("getLocalSocketAddress").invoke(hostSpec))
                    .isNull();
            Class<?> executorBaseType = Class.forName(
                    "org.postgresql.core.QueryExecutorBase", false, loader);
            java.lang.reflect.Field streamField = executorBaseType.getDeclaredField("pgStream");
            assertThat(streamField.trySetAccessible()).isTrue();
            Object stream = streamField.get(queryExecutor);
            assertThat(stream).isNotNull();
            assertThat(stream.getClass().getMethod("getSocketFactory").invoke(stream))
                    .isSameAs(javax.net.SocketFactory.getDefault());
            Object socket = stream.getClass().getMethod("getSocket").invoke(stream);
            assertThat(socket).isInstanceOf(java.net.Socket.class);
            assertThat(socket).isNotInstanceOf(javax.net.ssl.SSLSocket.class);
            assertThat(((java.net.Socket) socket).getRemoteSocketAddress())
                    .isInstanceOf(java.net.InetSocketAddress.class);
            assertThat(stream.getClass().getMethod("isGssEncrypted").invoke(stream))
                    .isEqualTo(false);

            PgDumpRestorePointService.PeerEndpoint peer =
                    PgDumpRestorePointService.inspectStandardTransport(
                            dataSource,
                            connection,
                            POSTGRES.getHost(),
                            POSTGRES.getFirstMappedPort());

            assertThat(peer.host()).isNotBlank();
            assertThat(peer.port()).isEqualTo(POSTGRES.getFirstMappedPort());

            try (PreparedStatement statement = connection.prepareStatement("""
                    select pg_catalog.current_database(),
                           pg_catalog.host(pg_catalog.inet_server_addr()),
                           pg_catalog.inet_server_port(),
                           coalesce((select ssl
                                     from pg_catalog.pg_stat_ssl
                                     where pid=pg_catalog.pg_backend_pid()), false),
                           coalesce((select encrypted
                                     from pg_catalog.pg_stat_gssapi
                                     where pid=pg_catalog.pg_backend_pid()), false)
                    """);
                    ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo(POSTGRES.getDatabaseName());
                assertThat(result.getString(2)).isNotBlank();
                assertThat(result.getInt(3)).isPositive();
                assertThat(result.getBoolean(4)).isFalse();
                assertThat(result.getBoolean(5)).isFalse();
                assertThat(result.next()).isFalse();
            }
        }
    }

    @Test
    void realHikariPgJdbcTransportInspectorRejectsJvmSocksRouting() throws Exception {
        ProxySelector original = ProxySelector.getDefault();
        AtomicReference<URI> selected = new AtomicReference<>();
        ProxySelector socks = new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                selected.set(uri);
                if (!POSTGRES.getHost().equalsIgnoreCase(uri.getHost())) {
                    return List.of(Proxy.NO_PROXY);
                }
                return List.of(new Proxy(
                        Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", 1080)));
            }

            @Override
            public void connectFailed(URI uri, SocketAddress address, IOException failure) {
                // The inspector must reject the route without attempting a connection.
            }
        };

        try (Connection connection = dataSource.getConnection()) {
            ProxySelector.setDefault(socks);
            assertThatThrownBy(() -> PgDumpRestorePointService.inspectStandardTransport(
                            dataSource,
                            connection,
                            POSTGRES.getHost(),
                            POSTGRES.getFirstMappedPort()))
                    .isInstanceOf(Exception.class)
                    .hasNoCause();
            assertThat(selected.get()).isNotNull();
            assertThat(selected.get().getScheme()).isEqualTo("socket");
            assertThat(selected.get().getHost()).isEqualToIgnoringCase(POSTGRES.getHost());
            assertThat(selected.get().getPort()).isEqualTo(POSTGRES.getFirstMappedPort());
        } finally {
            ProxySelector.setDefault(original);
        }
    }

    @Test
    void publicApiConstructorAndTransactionContractsAreExact() throws Exception {
        assertThat(Modifier.isFinal(AdminRecoveryService.class.getModifiers())).isTrue();
        assertThat(AdminRecoveryService.class.getAnnotation(Service.class)).isNotNull();
        assertThat(AdminRecoveryService.class.getAnnotation(Transactional.class)).isNull();
        assertThat(publicMethodSignatures(AdminRecoveryService.class))
                .containsExactlyInAnyOrder(
                        "complete(" + AdminRecoveryService.Enrollment.class.getName()
                                + ",[C):void",
                        "prepare([C):" + AdminRecoveryService.Enrollment.class.getName());
        for (Method method : AdminRecoveryService.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers())) {
                assertThat(method.getAnnotation(Transactional.class)).isNull();
            }
        }

        Constructor<AdminRecoveryService> constructor = AdminRecoveryService.class.getConstructor(
                DatabaseRestorePointService.class,
                AdminUserRepository.class,
                RecoveryCodeRepository.class,
                RecoveryCodeService.class,
                RecoveryCodeGenerator.class,
                PasswordPolicy.class,
                PasswordEncoder.class,
                TotpService.class,
                AdminSessionService.class,
                AuditService.class,
                TransactionTemplate.class);
        assertThat(constructor.getParameterTypes()).containsExactly(
                DatabaseRestorePointService.class,
                AdminUserRepository.class,
                RecoveryCodeRepository.class,
                RecoveryCodeService.class,
                RecoveryCodeGenerator.class,
                PasswordPolicy.class,
                PasswordEncoder.class,
                TotpService.class,
                AdminSessionService.class,
                AuditService.class,
                TransactionTemplate.class);

        Class<AdminRecoveryService.Enrollment> enrollment =
                AdminRecoveryService.Enrollment.class;
        assertThat(Modifier.isPublic(enrollment.getModifiers())).isTrue();
        assertThat(Modifier.isStatic(enrollment.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(enrollment.getModifiers())).isTrue();
        assertThat(enrollment.isRecord()).isFalse();
        assertThat(Serializable.class.isAssignableFrom(enrollment)).isFalse();
        assertThat(AutoCloseable.class.isAssignableFrom(enrollment)).isTrue();
        assertThat(Arrays.stream(enrollment.getDeclaredConstructors()))
                .allMatch(candidate -> Modifier.isPrivate(candidate.getModifiers()));
        assertThat(publicMethodSignatures(enrollment))
                .containsExactlyInAnyOrder(
                        "backupSha256():java.lang.String",
                        "close():void",
                        "provisioningUri():java.lang.String",
                        "takePlaintextRecoveryCodes():java.util.List",
                        "toString():java.lang.String");

        assertMandatory(AdminUserRepository.class.getMethod(
                "replaceCredentialsIfVersion",
                UUID.class,
                long.class,
                String.class,
                EncryptedTotpSecret.class));
        assertMandatory(AdminUserRepository.class.getMethod(
                "updateTotpIfVersion",
                UUID.class,
                long.class,
                int.class,
                EncryptedTotpSecret.class));
        assertThat(AdminUserRepository.class.getMethod("requireOnlyAdmin").getReturnType())
                .isEqualTo(AdminUser.class);
        assertThat(AdminSessionRepository.class.getMethod(
                        "markAllRevoked", UUID.class, String.class, Instant.class)
                .getGenericReturnType().getTypeName())
                .isEqualTo("java.util.List<"
                        + AdminSessionRepository.TerminalSession.class.getName() + ">");
        assertThat(AdminSessionRepository.class.getMethod("deleteAllSpringSessions")
                        .getReturnType())
                .isEqualTo(int.class);
    }

    @Test
    void constructorRejectsEveryNullDependencyAndNonRequiredTemplate() {
        UnitFixture fixture = new UnitFixture();
        for (Dependency dependency : Dependency.values()) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> fixture.construct(dependency, fixture.transactions));
        }

        RecordingTransactionTemplate requiresNew = new RecordingTransactionTemplate();
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> fixture.construct(null, requiresNew));
    }

    @Test
    void prepareWipesPasswordBeforeBackupAndFollowsTheBackupGateOrder() {
        UnitFixture fixture = new UnitFixture();
        char[] password = PASSWORD.toCharArray();
        fixture.passwordArray.set(password);

        AdminRecoveryService.Enrollment enrollment = fixture.service.prepare(password);

        assertWiped(password);
        assertThat(fixture.events).containsExactly(
                "password.policy",
                "password.encode",
                "admin.read",
                "backup.create",
                "totp.enroll",
                "recovery.generate",
                "recovery.hash");
        assertThat(CharBuffer.class.isAssignableFrom(fixture.encoderInputType.get())).isTrue();
        assertThat(enrollment.provisioningUri()).isEqualTo(PROVISIONING_URI);
        assertThat(enrollment.backupSha256()).isEqualTo(BACKUP_SHA256);
        assertThatThrownBy(enrollment::takePlaintextRecoveryCodes)
                .isInstanceOf(IllegalStateException.class);
        enrollment.close();
    }

    @Test
    void backupFailureIsCauseFreeAndCreatesNoReplacementMaterialOrMutation() {
        UnitFixture fixture = new UnitFixture();
        char[] password = PASSWORD.toCharArray();
        fixture.passwordArray.set(password);
        doAnswer(invocation -> {
            fixture.events.add("backup.create");
            assertWiped(password);
            throw new IllegalStateException("database restore point could not be created");
        }).when(fixture.restore).create();

        Throwable failure = catchThrowable(() -> fixture.service.prepare(password));

        assertCauseFreeAndMarkerFree(failure, PASSWORD, TOTP_SECRET);
        assertWiped(password);
        assertThat(fixture.events).containsExactly(
                "password.policy", "password.encode", "admin.read", "backup.create");
        verifyNoInteractions(
                fixture.totp,
                fixture.generator,
                fixture.recoveryService,
                fixture.recoveryRepository,
                fixture.sessions,
                fixture.audit);
        assertThat(fixture.transactions.executions()).isZero();
    }

    @Test
    void preparePreservesOnlyTheCanonicalPasswordPolicyErrorAndSanitizesDependencyDomains() {
        UnitFixture policyFixture = new UnitFixture();
        char[] weakPassword = PASSWORD.toCharArray();
        Throwable canonicalFailure = catchThrowable(
                () -> new PasswordPolicy().requireStrong("weak"));
        assertThat(canonicalFailure).isInstanceOf(DomainException.class);
        DomainException canonical = (DomainException) canonicalFailure;
        String policyMarker = "PASSWORD_POLICY_CAUSE_MARKER";
        DomainException policyViolation = new DomainException(
                "PASSWORD_POLICY_VIOLATION",
                HttpStatus.UNPROCESSABLE_ENTITY,
                canonical.fieldErrors());
        policyViolation.initCause(new SyntheticAuditException(policyMarker));
        policyViolation.addSuppressed(
                new SyntheticAuditException(policyMarker + "_SUPPRESSED"));
        doThrow(policyViolation).when(policyFixture.policy).requireStrong(any());

        Throwable policyFailure = catchThrowable(
                () -> policyFixture.service.prepare(weakPassword));

        assertThat(policyFailure).isNotSameAs(policyViolation);
        assertThat(policyFailure).isInstanceOf(DomainException.class);
        DomainException sanitizedPolicyFailure = (DomainException) policyFailure;
        assertThat(sanitizedPolicyFailure.code()).isEqualTo(canonical.code());
        assertThat(sanitizedPolicyFailure.status()).isEqualTo(canonical.status());
        assertThat(sanitizedPolicyFailure.fieldErrors())
                .containsExactlyEntriesOf(canonical.fieldErrors());
        assertCauseFreeAndMarkerFree(policyFailure, policyMarker);
        assertThat(policyFailure.getSuppressed()).isEmpty();
        assertWiped(weakPassword);
        verifyNoInteractions(
                policyFixture.encoder,
                policyFixture.admins,
                policyFixture.restore,
                policyFixture.totp,
                policyFixture.generator,
                policyFixture.recoveryService,
                policyFixture.recoveryRepository,
                policyFixture.sessions,
                policyFixture.audit);

        UnitFixture nonCanonicalPolicyFixture = new UnitFixture();
        char[] nonCanonicalPassword = PASSWORD.toCharArray();
        doThrow(new DomainException(
                        "PASSWORD_POLICY_VIOLATION",
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        Map.of("password", "FORGED_POLICY_FIELD_MARKER")))
                .when(nonCanonicalPolicyFixture.policy)
                .requireStrong(any());

        Throwable nonCanonicalFailure = catchThrowable(
                () -> nonCanonicalPolicyFixture.service.prepare(nonCanonicalPassword));

        assertCauseFreeAndMarkerFree(
                nonCanonicalFailure, "FORGED_POLICY_FIELD_MARKER");
        assertThat(nonCanonicalFailure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("administrator recovery could not be completed");
        assertWiped(nonCanonicalPassword);

        for (boolean failAfterBackup : List.of(false, true)) {
            UnitFixture dependencyFixture = new UnitFixture();
            char[] password = PASSWORD.toCharArray();
            String marker = failAfterBackup
                    ? "totp-domain-secret-marker"
                    : "admin-domain-secret-marker";
            DomainException forged = new DomainException(
                    "FORGED_DEPENDENCY_ERROR",
                    HttpStatus.I_AM_A_TEAPOT,
                    Map.of("secret", marker));
            if (failAfterBackup) {
                when(dependencyFixture.totp.beginEnrollment(ADMIN_ID, "YYchainsaw.Admin"))
                        .thenThrow(forged);
            } else {
                when(dependencyFixture.admins.requireOnlyAdmin()).thenThrow(forged);
            }

            Throwable failure = catchThrowable(
                    () -> dependencyFixture.service.prepare(password));

            assertCauseFreeAndMarkerFree(failure, marker, "FORGED_DEPENDENCY_ERROR");
            assertThat(failure).hasMessage("administrator recovery could not be completed");
            assertWiped(password);
            assertThat(dependencyFixture.transactions.executions()).isZero();
        }
    }

    @Test
    void publicEntriesRejectAmbientTransactionsBeforeCollaboratorsAndWipeInputs() {
        UnitFixture prepareFixture = new UnitFixture();
        char[] password = PASSWORD.toCharArray();
        TransactionTemplate realTransactions = new TransactionTemplate(transactionManager);

        realTransactions.executeWithoutResult(status -> assertThatIllegalStateException()
                .isThrownBy(() -> prepareFixture.service.prepare(password)));

        assertWiped(password);
        prepareFixture.verifyNoCollaboratorInteractions();

        UnitFixture completeFixture = new UnitFixture();
        AdminRecoveryService.Enrollment enrollment =
                completeFixture.service.prepare(PASSWORD.toCharArray());
        completeFixture.clearCollaboratorInvocations();
        completeFixture.events.clear();
        char[] code = TOTP_CODE.toCharArray();

        realTransactions.executeWithoutResult(status -> assertThatIllegalStateException()
                .isThrownBy(() -> completeFixture.service.complete(enrollment, code)));

        assertWiped(code);
        completeFixture.verifyNoCollaboratorInteractions();
        assertThat(completeFixture.events).isEmpty();
        assertThat(enrollment.provisioningUri()).isEqualTo(PROVISIONING_URI);
        enrollment.close();
    }

    @Test
    void malformedTotpIsRejectedOutsideTheTransactionAndEnrollmentRemainsPrepared() {
        UnitFixture fixture = new UnitFixture();
        AdminRecoveryService.Enrollment enrollment =
                fixture.service.prepare(PASSWORD.toCharArray());
        fixture.clearCollaboratorInvocations();
        fixture.events.clear();
        char[] malformed = "12x456".toCharArray();

        Throwable failure = catchThrowable(() -> fixture.service.complete(enrollment, malformed));

        assertDomain(failure, "INVALID_RECOVERY_TOTP", HttpStatus.UNAUTHORIZED);
        assertWiped(malformed);
        verifyNoInteractions(
                fixture.totp,
                fixture.admins,
                fixture.recoveryRepository,
                fixture.sessions,
                fixture.audit);
        assertThat(fixture.transactions.executions()).isZero();
        assertThat(enrollment.provisioningUri()).isEqualTo(PROVISIONING_URI);
        enrollment.close();
    }

    @Test
    void enrollmentIsRedactedOneShotAndCloseIsIdempotent() {
        UnitFixture fixture = new UnitFixture();
        AdminRecoveryService.Enrollment enrollment =
                fixture.service.prepare(PASSWORD.toCharArray());

        assertThat(enrollment.toString())
                .doesNotContain(PASSWORD_HASH, TOTP_SECRET, PROVISIONING_URI, BACKUP_SHA256)
                .doesNotContain(RECOVERY_CODES.get(0), RECOVERY_HASHES.get(0));
        assertThatThrownBy(enrollment::takePlaintextRecoveryCodes)
                .isInstanceOf(IllegalStateException.class);

        enrollment.close();
        enrollment.close();

        assertThatThrownBy(enrollment::provisioningUri)
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(enrollment::backupSha256)
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(enrollment::takePlaintextRecoveryCodes)
                .isInstanceOf(IllegalStateException.class);
        assertThat(enrollment.toString())
                .doesNotContain(PASSWORD_HASH, TOTP_SECRET, PROVISIONING_URI, BACKUP_SHA256)
                .doesNotContain(RECOVERY_CODES.get(0), RECOVERY_HASHES.get(0));
    }

    @Test
    void staleCasRollsBackBeforeChildRowsAndPreservesPreparedEnrollment() {
        UnitFixture fixture = new UnitFixture();
        AdminRecoveryService.Enrollment enrollment =
                fixture.service.prepare(PASSWORD.toCharArray());
        fixture.clearCollaboratorInvocations();
        fixture.events.clear();
        doAnswer(invocation -> {
                    fixture.events.add("admin.cas");
                    return OptionalLong.empty();
                })
                .when(fixture.admins)
                .replaceCredentialsIfVersion(
                        eq(ADMIN_ID), eq(ADMIN_VERSION), anyString(), any());
        char[] code = TOTP_CODE.toCharArray();

        Throwable failure = catchThrowable(() -> fixture.service.complete(enrollment, code));

        assertDomain(failure, "AUTH_VERSION_CONFLICT", HttpStatus.CONFLICT);
        assertWiped(code);
        assertThat(fixture.events).containsExactly(
                "totp.verify", "tx.begin", "admin.cas", "tx.rollback");
        verifyNoInteractions(fixture.recoveryRepository, fixture.sessions, fixture.audit);
        assertThat(enrollment.provisioningUri()).isEqualTo(PROVISIONING_URI);
        enrollment.close();
    }

    @Test
    void presentCasVersionMustAdvanceByExactlyOneBeforeAnyChildMutation() {
        UnitFixture fixture = new UnitFixture();
        AdminRecoveryService.Enrollment enrollment =
                fixture.service.prepare(PASSWORD.toCharArray());
        fixture.clearCollaboratorInvocations();
        fixture.events.clear();
        doAnswer(invocation -> {
                    fixture.events.add("admin.cas");
                    return OptionalLong.of(ADMIN_VERSION + 2);
                })
                .when(fixture.admins)
                .replaceCredentialsIfVersion(
                        eq(ADMIN_ID), eq(ADMIN_VERSION), anyString(), any());
        char[] code = TOTP_CODE.toCharArray();

        Throwable failure = catchThrowable(() -> fixture.service.complete(enrollment, code));

        assertCauseFreeAndMarkerFree(failure);
        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("administrator recovery could not be completed");
        assertWiped(code);
        assertThat(fixture.events).containsExactly(
                "totp.verify", "tx.begin", "admin.cas", "tx.rollback");
        verifyNoInteractions(fixture.recoveryRepository, fixture.sessions, fixture.audit);
        assertThat(enrollment.provisioningUri()).isEqualTo(PROVISIONING_URI);
        enrollment.close();
    }

    @Test
    void successfulCompletionUsesFrozenMutationOrderCommitsBeforeDeleteAndHandsCodesOnce() {
        UnitFixture fixture = new UnitFixture();
        AdminRecoveryService.Enrollment enrollment =
                fixture.service.prepare(PASSWORD.toCharArray());
        List<String> originalCodes = List.copyOf(RECOVERY_CODES);
        List<String> originalHashes = List.copyOf(RECOVERY_HASHES);
        fixture.generatedCodes.set(0, "ZZZZ-ZZZZ-ZZZZ");
        fixture.generatedHashes.set(0, "mutated-hash");
        fixture.clearCollaboratorInvocations();
        fixture.events.clear();
        fixture.enrollmentAtDelete.set(enrollment);
        char[] code = TOTP_CODE.toCharArray();

        fixture.service.complete(enrollment, code);

        assertWiped(code);
        assertThat(fixture.events).containsExactly(
                "totp.verify",
                "tx.begin",
                "admin.cas",
                "recovery.replace",
                "sessions.mark",
                "audit.ADMIN_RECOVERED",
                "tx.commit",
                "sessions.deleteAll");
        verify(fixture.admins).replaceCredentialsIfVersion(
                eq(ADMIN_ID), eq(ADMIN_VERSION), eq(PASSWORD_HASH), any());
        verify(fixture.recoveryRepository).replace(ADMIN_ID, originalHashes);
        verify(fixture.sessions)
                .markAllSessionsRevokedInCurrentTransaction(ADMIN_ID, "ADMIN_RECOVERY");
        ArgumentCaptor<AuditCommand> audit = ArgumentCaptor.forClass(AuditCommand.class);
        verify(fixture.audit).record(audit.capture());
        assertThat(audit.getValue()).satisfies(command -> {
            assertThat(command.actorAdminId()).isEqualTo(ADMIN_ID);
            assertThat(command.action()).isEqualTo("ADMIN_RECOVERED");
            assertThat(command.targetType()).isEqualTo("ADMIN");
            assertThat(command.targetId()).isEqualTo(ADMIN_ID.toString());
            assertThat(command.outcome()).isEqualTo(AuditOutcome.SUCCESS);
            assertThat(command.metadata()).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "channel", "LOCAL_CLI", "backupSha256", BACKUP_SHA256));
        });
        assertThat(enrollment.takePlaintextRecoveryCodes()).containsExactlyElementsOf(originalCodes);
        assertThatThrownBy(enrollment::takePlaintextRecoveryCodes)
                .isInstanceOf(IllegalStateException.class);
        enrollment.close();
    }

    @Test
    void auditFailureRollsBackOwnedTransactionAndNeverAttemptsPhysicalDeletion() {
        UnitFixture fixture = new UnitFixture();
        AdminRecoveryService.Enrollment enrollment =
                fixture.service.prepare(PASSWORD.toCharArray());
        fixture.clearCollaboratorInvocations();
        fixture.events.clear();
        doAnswer(invocation -> {
                    AuditCommand command = invocation.getArgument(0);
                    fixture.events.add("audit." + command.action());
                    throw new SyntheticAuditException("audit-secret-marker");
                })
                .when(fixture.audit)
                .record(any());
        char[] code = TOTP_CODE.toCharArray();

        Throwable failure = catchThrowable(() -> fixture.service.complete(enrollment, code));

        assertCauseFreeAndMarkerFree(failure, "audit-secret-marker", TOTP_SECRET, PASSWORD_HASH);
        assertWiped(code);
        assertThat(fixture.events).containsExactly(
                "totp.verify",
                "tx.begin",
                "admin.cas",
                "recovery.replace",
                "sessions.mark",
                "audit.ADMIN_RECOVERED",
                "tx.rollback");
        verify(fixture.sessions, never()).deleteAllSpringSessionsBestEffort();
        assertThat(enrollment.provisioningUri()).isEqualTo(PROVISIONING_URI);
        enrollment.close();
    }

    @Test
    void closeWaitsForValidationAndOwnedTransactionThenMayCancelUnclaimedHandoff()
            throws Exception {
        UnitFixture fixture = new UnitFixture();
        AdminRecoveryService.Enrollment enrollment =
                fixture.service.prepare(PASSWORD.toCharArray());
        fixture.clearCollaboratorInvocations();
        fixture.events.clear();
        CountDownLatch verifierEntered = new CountDownLatch(1);
        CountDownLatch releaseVerifier = new CountDownLatch(1);
        doAnswer(invocation -> {
            fixture.events.add("totp.verify");
            verifierEntered.countDown();
            await(releaseVerifier, "verifier was not released");
            return true;
        }).when(fixture.totp).verifyEnrollment(TOTP_SECRET, TOTP_CODE);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        char[] code = TOTP_CODE.toCharArray();
        try {
            Future<?> completion = executor.submit(() -> fixture.service.complete(enrollment, code));
            assertThat(verifierEntered.await(5, SECONDS)).isTrue();
            Future<?> closing = executor.submit(enrollment::close);

            assertThatThrownBy(() -> closing.get(200, MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            releaseVerifier.countDown();
            completion.get(5, SECONDS);
            closing.get(5, SECONDS);
        } finally {
            releaseVerifier.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, SECONDS)).isTrue();
        }

        assertWiped(code);
        assertThatThrownBy(enrollment::takePlaintextRecoveryCodes)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void realPostgresRecoveryCommitsAtomicallyBeforeDeleteAndDeleteSeesCommittedRows()
            throws Exception {
        IntegrationFixture fixture = insertIntegrationFixture();
        AdminSessionService sessions = spy(integrationSessions(
                integrationSessionRepository, integrationAudit));
        AtomicReference<CommittedView> observedAtDelete = new AtomicReference<>();
        doAnswer(invocation -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isFalse();
                    observedAtDelete.set(committedViewFromSeparateConnection(fixture));
                    invocation.callRealMethod();
                    return null;
                })
                .when(sessions)
                .deleteAllSpringSessionsBestEffort();
        AdminRecoveryService service = integrationRecoveryService(sessions, integrationAudit);
        char[] password = PASSWORD.toCharArray();
        char[] code = TOTP_CODE.toCharArray();

        try (AdminRecoveryService.Enrollment enrollment = service.prepare(password)) {
            service.complete(enrollment, code);

            assertThat(enrollment.takePlaintextRecoveryCodes())
                    .containsExactlyElementsOf(RECOVERY_CODES);
        }

        assertWiped(password);
        assertWiped(code);
        assertThat(observedAtDelete.get()).isEqualTo(new CommittedView(
                PASSWORD_HASH,
                "ACTIVE",
                ADMIN_VERSION + 1,
                RECOVERY_HASHES.size(),
                fixture.metadataIds().size(),
                fixture.metadataIds().size(),
                1,
                fixture.springPrimaryIds().size()));
        AdminUser updated = integrationAdmins.findById(fixture.adminId()).orElseThrow();
        assertThat(updated.passwordHash()).isEqualTo(PASSWORD_HASH);
        assertThat(updated.status()).isEqualTo(AdminStatus.ACTIVE);
        assertThat(updated.version()).isEqualTo(ADMIN_VERSION + 1);
        assertThat(updated.totpSecret()).isEqualTo(encryptedSecret());
        assertThat(integrationRecoveryCodes.findUnused(fixture.adminId()))
                .extracting(RecoveryCodeRepository.StoredCode::hash)
                .containsExactlyInAnyOrderElementsOf(RECOVERY_HASHES);
        assertThat(metadataRows(fixture.adminId()))
                .allSatisfy(row -> {
                    assertThat(row.status()).isEqualTo("REVOKED");
                    assertThat(row.reason()).isEqualTo("ADMIN_RECOVERY");
                    assertThat(row.version()).isEqualTo(1L);
                    assertThat(row.endedAt()).isEqualTo(NOW);
                });
        List<AuditDbRow> audits = auditRows(fixture.adminId());
        assertThat(audits)
                .extracting(AuditDbRow::action)
                .containsExactlyInAnyOrder(
                        "SESSION_REVOKED", "SESSION_REVOKED", "ADMIN_RECOVERED");
        assertThat(audits)
                .filteredOn(row -> "SESSION_REVOKED".equals(row.action()))
                .allSatisfy(row -> {
                    assertThat(row.targetType()).isEqualTo("ADMIN_SESSION");
                    assertThat(row.outcome()).isEqualTo("SUCCESS");
                    assertThat(row.reason()).isEqualTo("ADMIN_RECOVERY");
                })
                .extracting(AuditDbRow::targetId)
                .containsExactlyInAnyOrderElementsOf(fixture.metadataIds().stream()
                        .map(UUID::toString)
                        .toList());
        assertThat(audits)
                .filteredOn(row -> "ADMIN_RECOVERED".equals(row.action()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.targetType()).isEqualTo("ADMIN");
                    assertThat(row.targetId()).isEqualTo(fixture.adminId().toString());
                    assertThat(row.channel()).isEqualTo("LOCAL_CLI");
                    assertThat(row.backupSha256()).isEqualTo(BACKUP_SHA256);
                });
        assertThat(existingSpringPrimaryIds(fixture)).isEmpty();
    }

    @Test
    void realPostgresRecoveryRollsBackEveryMutationWhenFinalAuditFails() {
        IntegrationFixture fixture = insertIntegrationFixture();
        AuditService failingAudit = command -> {
            integrationAudit.record(command);
            if ("ADMIN_RECOVERED".equals(command.action())) {
                throw new SyntheticAuditException("REAL_AUDIT_FAILURE_MARKER");
            }
        };
        AdminSessionService sessions = integrationSessions(
                integrationSessionRepository, failingAudit);
        AdminRecoveryService service = integrationRecoveryService(sessions, failingAudit);
        char[] code = TOTP_CODE.toCharArray();

        try (AdminRecoveryService.Enrollment enrollment =
                service.prepare(PASSWORD.toCharArray())) {
            IntegrationSnapshot before = integrationSnapshot(fixture);

            Throwable failure = catchThrowable(() -> service.complete(enrollment, code));

            assertCauseFreeAndMarkerFree(failure, "REAL_AUDIT_FAILURE_MARKER");
            assertThat(integrationSnapshot(fixture)).isEqualTo(before);
            assertThat(enrollment.provisioningUri()).isEqualTo(PROVISIONING_URI);
        }
        assertWiped(code);
    }

    @Test
    void realPostgresCommitFailureRollsBackEveryMutationAndSkipsPhysicalDeletion() {
        IntegrationFixture fixture = insertIntegrationFixture();
        AdminSessionService sessions = spy(integrationSessions(
                integrationSessionRepository, integrationAudit));
        String marker = "REAL_COMMIT_FAILURE_MARKER";
        TransactionTemplate failingTransactions = new TransactionTemplate(
                new CommitFailingTransactionManager(transactionManager, marker));
        AdminRecoveryService service = integrationRecoveryService(
                sessions, integrationAudit, failingTransactions);
        char[] code = TOTP_CODE.toCharArray();

        try (AdminRecoveryService.Enrollment enrollment =
                service.prepare(PASSWORD.toCharArray())) {
            IntegrationSnapshot before = integrationSnapshot(fixture);

            Throwable failure = catchThrowable(() -> service.complete(enrollment, code));

            assertCauseFreeAndMarkerFree(failure, marker);
            assertThat(integrationSnapshot(fixture)).isEqualTo(before);
            assertThat(enrollment.provisioningUri()).isEqualTo(PROVISIONING_URI);
            verify(sessions, never()).deleteAllSpringSessionsBestEffort();
        }
        assertWiped(code);
    }

    @Test
    void realPostgresStaleCasPreservesTheCompetingCommitAndAllChildRows() {
        IntegrationFixture fixture = insertIntegrationFixture();
        AdminSessionService sessions = integrationSessions(
                integrationSessionRepository, integrationAudit);
        AdminRecoveryService service = integrationRecoveryService(sessions, integrationAudit);
        char[] code = TOTP_CODE.toCharArray();

        try (AdminRecoveryService.Enrollment enrollment =
                service.prepare(PASSWORD.toCharArray())) {
            Instant competingLogin = NOW.plusSeconds(30);
            integrationAdmins.updateLastLogin(fixture.adminId(), competingLogin);
            assertThat(integrationAdmins.findById(fixture.adminId()).orElseThrow().version())
                    .isEqualTo(ADMIN_VERSION + 1);
            IntegrationSnapshot afterCompetingCommit = integrationSnapshot(fixture);

            Throwable failure = catchThrowable(() -> service.complete(enrollment, code));

            assertDomain(failure, "AUTH_VERSION_CONFLICT", HttpStatus.CONFLICT);
            assertThat(integrationSnapshot(fixture)).isEqualTo(afterCompetingCommit);
            assertThat(enrollment.provisioningUri()).isEqualTo(PROVISIONING_URI);
        }
        assertWiped(code);
    }

    @Test
    void postCommitDeleteFailureCannotRollBackAndTheCleanupIsRetryable() {
        IntegrationFixture fixture = insertIntegrationFixture();
        AdminSessionRepository repository = spy(new AdminSessionRepository(jdbc));
        doThrow(new SyntheticAuditException("REAL_DELETE_FAILURE_MARKER"))
                .doCallRealMethod()
                .when(repository)
                .deleteAllSpringSessions();
        AdminSessionService sessions = integrationSessions(repository, integrationAudit);
        AdminRecoveryService service = integrationRecoveryService(sessions, integrationAudit);

        try (AdminRecoveryService.Enrollment enrollment =
                service.prepare(PASSWORD.toCharArray())) {
            service.complete(enrollment, TOTP_CODE.toCharArray());
            IntegrationSnapshot committedBeforeRetry = integrationSnapshot(fixture);
            assertThat(committedBeforeRetry.springPrimaryIds())
                    .containsExactlyInAnyOrderElementsOf(fixture.springPrimaryIds());

            sessions.deleteAllSpringSessionsBestEffort();

            IntegrationSnapshot afterRetry = integrationSnapshot(fixture);
            assertThat(afterRetry.admin()).isEqualTo(committedBeforeRetry.admin());
            assertThat(afterRetry.recoveryHashes())
                    .isEqualTo(committedBeforeRetry.recoveryHashes());
            assertThat(afterRetry.metadata()).hasSameSizeAs(committedBeforeRetry.metadata());
            for (int index = 0; index < afterRetry.metadata().size(); index++) {
                assertThat(afterRetry.metadata().get(index))
                        .usingRecursiveComparison()
                        .ignoringFields("primaryId")
                        .isEqualTo(committedBeforeRetry.metadata().get(index));
                assertThat(afterRetry.metadata().get(index).primaryId()).isNull();
            }
            assertThat(afterRetry.audits()).isEqualTo(committedBeforeRetry.audits());
            assertThat(afterRetry.springPrimaryIds()).isEmpty();
        }
        verify(repository, times(2)).deleteAllSpringSessions();
    }

    @Test
    void markAllBlocksOnOneLockedActiveRowThenRevokesEveryRowWithoutSkipLocked()
            throws Exception {
        IntegrationFixture fixture = insertIntegrationFixture();
        AdminSessionService sessions = integrationSessions(
                integrationSessionRepository, integrationAudit);
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        CountDownLatch rowLocked = new CountDownLatch(1);
        CountDownLatch releaseRow = new CountDownLatch(1);
        CountDownLatch markingEntered = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<Void> locker = null;
        Future<List<AdminSessionRepository.TerminalSession>> marking = null;
        try {
            UUID lockedId = fixture.metadataIds().get(0);
            locker = executor.submit(() -> {
                transactions.executeWithoutResult(status -> {
                    assertThat(jdbc.sql("""
                                    select id from portfolio.admin_session_metadata
                                    where id=:metadataId for update
                                    """)
                            .param("metadataId", lockedId)
                            .query(UUID.class)
                            .single()).isEqualTo(lockedId);
                    rowLocked.countDown();
                    await(releaseRow, "locked metadata row was not released");
                });
                return null;
            });
            await(rowLocked, "metadata row was not locked");

            marking = executor.submit(() -> transactions.execute(status -> {
                assertThat(integrationAdmins.findByIdForUpdate(fixture.adminId())).isPresent();
                markingEntered.countDown();
                return sessions.markAllSessionsRevokedInCurrentTransaction(
                        fixture.adminId(), "ADMIN_RECOVERY");
            }));
            await(markingEntered, "mark-all transaction did not enter");
            Future<List<AdminSessionRepository.TerminalSession>> blockedMarking = marking;
            assertThatThrownBy(() -> blockedMarking.get(1, SECONDS))
                    .as("mark-all must wait for the separately locked ACTIVE row")
                    .isInstanceOf(TimeoutException.class);

            releaseRow.countDown();
            locker.get(20, SECONDS);
            List<AdminSessionRepository.TerminalSession> marked = marking.get(20, SECONDS);

            assertThat(marked)
                    .extracting(terminal -> terminal.metadataId().toString())
                    .containsExactlyElementsOf(fixture.metadataIds().stream()
                            .map(UUID::toString)
                            .sorted()
                            .toList());
            assertThat(metadataRows(fixture.adminId()))
                    .extracting(MetadataDbRow::status)
                    .containsOnly("REVOKED");
            assertThat(auditRows(fixture.adminId()))
                    .extracting(AuditDbRow::action)
                    .containsOnly("SESSION_REVOKED");
        } finally {
            releaseRow.countDown();
            if (locker != null && !locker.isDone()) {
                locker.cancel(true);
            }
            if (marking != null && !marking.isDone()) {
                marking.cancel(true);
            }
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, SECONDS)).isTrue();
        }
    }

    @Test
    void sessionMaintenanceBoundariesAndPerSessionAuditsAreExact() {
        AdminSessionRepository repository = mock(AdminSessionRepository.class);
        AdminUserRepository admins = mock(AdminUserRepository.class);
        SessionProperties properties = mock(SessionProperties.class);
        AuditService audit = mock(AuditService.class);
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AdminSessionService service = new AdminSessionService(
                repository, admins, properties, audit, transactions, clock);

        assertThatIllegalStateException()
                .isThrownBy(() -> service.markAllSessionsRevokedInCurrentTransaction(
                        ADMIN_ID, "ADMIN_RECOVERY"));
        verifyNoInteractions(repository, audit);

        transactions.executeWithoutResult(status -> assertThatIllegalStateException()
                .isThrownBy(service::deleteAllSpringSessionsBestEffort));
        verifyNoInteractions(repository, audit);

        AdminSessionRepository.TerminalSession first = terminal(1);
        AdminSessionRepository.TerminalSession second = terminal(2);
        when(repository.markAllRevoked(ADMIN_ID, "ADMIN_RECOVERY", NOW))
                .thenReturn(List.of(first, second));
        List<AdminSessionRepository.TerminalSession> marked = transactions.execute(status ->
                service.markAllSessionsRevokedInCurrentTransaction(
                        ADMIN_ID, "ADMIN_RECOVERY"));

        assertThat(marked).containsExactly(first, second);
        assertThatThrownBy(() -> marked.add(first))
                .isInstanceOf(UnsupportedOperationException.class);
        InOrder order = inOrder(repository, audit);
        order.verify(repository).markAllRevoked(ADMIN_ID, "ADMIN_RECOVERY", NOW);
        ArgumentCaptor<AuditCommand> sessionAudits = ArgumentCaptor.forClass(AuditCommand.class);
        order.verify(audit, times(2)).record(sessionAudits.capture());
        assertThat(sessionAudits.getAllValues()).hasSize(2);
        assertSessionAudit(sessionAudits.getAllValues().get(0), first);
        assertSessionAudit(sessionAudits.getAllValues().get(1), second);

        when(repository.deleteAllSpringSessions()).thenReturn(2);
        service.deleteAllSpringSessionsBestEffort();
        verify(repository).deleteAllSpringSessions();
    }

    private AdminRecoveryService integrationRecoveryService(
            AdminSessionService sessions, AuditService audit) {
        return integrationRecoveryService(
                sessions, audit, new TransactionTemplate(transactionManager));
    }

    private AdminRecoveryService integrationRecoveryService(
            AdminSessionService sessions,
            AuditService audit,
            TransactionTemplate transactions) {
        DatabaseRestorePointService restore = () -> restorePoint();
        RecoveryCodeService recoveryService = mock(RecoveryCodeService.class);
        RecoveryCodeGenerator generator = mock(RecoveryCodeGenerator.class);
        PasswordPolicy policy = mock(PasswordPolicy.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        TotpService totp = mock(TotpService.class);
        when(encoder.encode(any(CharSequence.class))).thenReturn(PASSWORD_HASH);
        when(totp.beginEnrollment(any(UUID.class), anyString()))
                .thenReturn(new TotpService.Enrollment(
                        TOTP_SECRET, encryptedSecret(), PROVISIONING_URI));
        when(generator.generate(10)).thenReturn(RECOVERY_CODES);
        when(recoveryService.hashAll(any())).thenReturn(RECOVERY_HASHES);
        when(totp.verifyEnrollment(TOTP_SECRET, TOTP_CODE)).thenReturn(true);
        return new AdminRecoveryService(
                restore,
                integrationAdmins,
                integrationRecoveryCodes,
                recoveryService,
                generator,
                policy,
                encoder,
                totp,
                sessions,
                audit,
                transactions);
    }

    private AdminSessionService integrationSessions(
            AdminSessionRepository repository, AuditService audit) {
        return new AdminSessionService(
                repository,
                integrationAdmins,
                integrationSessionProperties,
                audit,
                new TransactionTemplate(transactionManager),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private IntegrationFixture insertIntegrationFixture() {
        UUID adminId = UUID.randomUUID();
        integrationAdminIds.add(adminId);
        AdminUser initial = new AdminUser(
                adminId,
                "task14-recovery-" + adminId.toString().substring(0, 8),
                "old-password-hash-" + adminId,
                AdminStatus.DISABLED,
                integrationInitialSecret(),
                null,
                ADMIN_VERSION,
                NOW.minusSeconds(3600),
                NOW.minusSeconds(60));
        integrationAdmins.insert(initial);
        integrationRecoveryCodes.replace(adminId, OLD_RECOVERY_HASHES);

        List<String> springPrimaryIds = List.of(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString());
        integrationSpringPrimaryIds.addAll(springPrimaryIds);
        for (int index = 0; index < springPrimaryIds.size(); index++) {
            insertSpringSession(
                    springPrimaryIds.get(index),
                    UUID.randomUUID().toString(),
                    NOW.minusSeconds(120 + index),
                    NOW.minusSeconds(20 + index),
                    NOW.plusSeconds(1800));
        }

        List<UUID> metadataIds = List.of(UUID.randomUUID(), UUID.randomUUID());
        for (int index = 0; index < metadataIds.size(); index++) {
            insertMetadata(
                    metadataIds.get(index),
                    adminId,
                    springPrimaryIds.get(index),
                    NOW.minusSeconds(120 + index),
                    NOW.minusSeconds(40 + index));
        }
        return new IntegrationFixture(
                adminId, List.copyOf(metadataIds), List.copyOf(springPrimaryIds));
    }

    private void insertSpringSession(
            String primaryId,
            String publicSessionId,
            Instant createdAt,
            Instant lastAccessAt,
            Instant expiryAt) {
        assertThat(jdbc.sql("""
                        insert into portfolio.spring_session
                            (primary_id, session_id, creation_time, last_access_time,
                             max_inactive_interval, expiry_time, principal_name)
                        values (:primaryId, :sessionId, :createdAt, :lastAccessAt,
                                1800, :expiryAt, 'admin')
                        """)
                .param("primaryId", primaryId)
                .param("sessionId", publicSessionId)
                .param("createdAt", createdAt.toEpochMilli())
                .param("lastAccessAt", lastAccessAt.toEpochMilli())
                .param("expiryAt", expiryAt.toEpochMilli())
                .update()).isOne();
    }

    private void insertMetadata(
            UUID metadataId,
            UUID adminId,
            String primaryId,
            Instant createdAt,
            Instant lastActivityAt) {
        assertThat(jdbc.sql("""
                        insert into portfolio.admin_session_metadata
                            (id, admin_id, session_primary_id, status, created_at,
                             last_activity_at, client_summary)
                        values (:id, :adminId, :primaryId, 'ACTIVE', :createdAt,
                                :lastActivityAt, 'Task14/Recovery @ local')
                        """)
                .param("id", metadataId, Types.OTHER)
                .param("adminId", adminId, Types.OTHER)
                .param("primaryId", primaryId)
                .param("createdAt", createdAt.atOffset(ZoneOffset.UTC),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .param("lastActivityAt", lastActivityAt.atOffset(ZoneOffset.UTC),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .update()).isOne();
    }

    private IntegrationSnapshot integrationSnapshot(IntegrationFixture fixture) {
        AdminUser admin = integrationAdmins.findById(fixture.adminId()).orElseThrow();
        AdminDbRow adminRow = new AdminDbRow(
                admin.passwordHash(),
                admin.status(),
                admin.totpSecret().keyVersion(),
                Base64.getEncoder().encodeToString(admin.totpSecret().nonce()),
                Base64.getEncoder().encodeToString(admin.totpSecret().ciphertext()),
                admin.lastLoginAt(),
                admin.version(),
                admin.createdAt(),
                admin.updatedAt());
        List<String> recoveryHashes = jdbc.sql("""
                        select code_hash from portfolio.totp_recovery_code
                        where admin_id=:adminId order by code_hash
                        """)
                .param("adminId", fixture.adminId(), Types.OTHER)
                .query(String.class)
                .list();
        return new IntegrationSnapshot(
                adminRow,
                List.copyOf(recoveryHashes),
                metadataRows(fixture.adminId()),
                auditRows(fixture.adminId()),
                existingSpringPrimaryIds(fixture));
    }

    private List<MetadataDbRow> metadataRows(UUID adminId) {
        List<MetadataDbRow> rows = jdbc.sql("""
                        select id, session_primary_id, status, revocation_reason, version,
                               created_at, last_activity_at, ended_at
                        from portfolio.admin_session_metadata
                        where admin_id=:adminId order by id
                        """)
                .param("adminId", adminId, Types.OTHER)
                .query((resultSet, rowNumber) -> new MetadataDbRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("session_primary_id"),
                        resultSet.getString("status"),
                        resultSet.getString("revocation_reason"),
                        resultSet.getLong("version"),
                        instant(resultSet, "created_at"),
                        instant(resultSet, "last_activity_at"),
                        instant(resultSet, "ended_at")))
                .list();
        return List.copyOf(rows);
    }

    private List<AuditDbRow> auditRows(UUID adminId) {
        List<AuditDbRow> rows = jdbc.sql("""
                        select id, action, target_type, target_id, outcome,
                               metadata->>'reason' reason,
                               metadata->>'channel' channel,
                               metadata->>'backupSha256' backup_sha256,
                               metadata::text metadata
                        from portfolio.audit_log
                        where actor_admin_id=:adminId order by created_at, id
                        """)
                .param("adminId", adminId, Types.OTHER)
                .query((resultSet, rowNumber) -> new AuditDbRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("action"),
                        resultSet.getString("target_type"),
                        resultSet.getString("target_id"),
                        resultSet.getString("outcome"),
                        resultSet.getString("reason"),
                        resultSet.getString("channel"),
                        resultSet.getString("backup_sha256"),
                        resultSet.getString("metadata")))
                .list();
        return List.copyOf(rows);
    }

    private List<String> existingSpringPrimaryIds(IntegrationFixture fixture) {
        List<String> existing = new ArrayList<>();
        for (String primaryId : fixture.springPrimaryIds()) {
            if (jdbc.sql("""
                            select count(*) from portfolio.spring_session
                            where primary_id=:primaryId
                            """)
                    .param("primaryId", primaryId)
                    .query(Integer.class)
                    .single() == 1) {
                existing.add(primaryId);
            }
        }
        existing.sort(String::compareTo);
        return List.copyOf(existing);
    }

    private CommittedView committedViewFromSeparateConnection(IntegrationFixture fixture)
            throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getAutoCommit()).isTrue();
            String passwordHash;
            String status;
            long version;
            try (PreparedStatement statement = connection.prepareStatement("""
                    select password_hash, status, version
                    from portfolio.admin_user where id=?
                    """)) {
                statement.setObject(1, fixture.adminId());
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    passwordHash = resultSet.getString("password_hash");
                    status = resultSet.getString("status");
                    version = resultSet.getLong("version");
                    assertThat(resultSet.next()).isFalse();
                }
            }
            int recoveryCount = queryCount(
                    connection,
                    "select count(*) from portfolio.totp_recovery_code where admin_id=?",
                    fixture.adminId());
            int revokedCount = queryCount(
                    connection,
                    """
                    select count(*) from portfolio.admin_session_metadata
                    where admin_id=? and status='REVOKED'
                      and revocation_reason='ADMIN_RECOVERY'
                    """,
                    fixture.adminId());
            int sessionAuditCount = queryCount(
                    connection,
                    """
                    select count(*) from portfolio.audit_log
                    where actor_admin_id=? and action='SESSION_REVOKED'
                    """,
                    fixture.adminId());
            int recoveryAuditCount = queryCount(
                    connection,
                    """
                    select count(*) from portfolio.audit_log
                    where actor_admin_id=? and action='ADMIN_RECOVERED'
                    """,
                    fixture.adminId());
            int springCount = 0;
            for (String primaryId : fixture.springPrimaryIds()) {
                springCount += querySpringCount(connection, primaryId);
            }
            return new CommittedView(
                    passwordHash,
                    status,
                    version,
                    recoveryCount,
                    revokedCount,
                    sessionAuditCount,
                    recoveryAuditCount,
                    springCount);
        }
    }

    private static int queryCount(Connection connection, String sql, UUID adminId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, adminId);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                int count = resultSet.getInt(1);
                assertThat(resultSet.next()).isFalse();
                return count;
            }
        }
    }

    private static int querySpringCount(Connection connection, String primaryId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select count(*) from portfolio.spring_session where primary_id=?
                """)) {
            statement.setString(1, primaryId);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                int count = resultSet.getInt(1);
                assertThat(resultSet.next()).isFalse();
                return count;
            }
        }
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private record IntegrationFixture(
            UUID adminId, List<UUID> metadataIds, List<String> springPrimaryIds) {
        private IntegrationFixture {
            metadataIds = List.copyOf(metadataIds);
            springPrimaryIds = List.copyOf(springPrimaryIds);
        }
    }

    private record AdminDbRow(
            String passwordHash,
            AdminStatus status,
            int keyVersion,
            String nonce,
            String ciphertext,
            Instant lastLoginAt,
            long version,
            Instant createdAt,
            Instant updatedAt) {}

    private record MetadataDbRow(
            UUID id,
            String primaryId,
            String status,
            String reason,
            long version,
            Instant createdAt,
            Instant lastActivityAt,
            Instant endedAt) {}

    private record AuditDbRow(
            UUID id,
            String action,
            String targetType,
            String targetId,
            String outcome,
            String reason,
            String channel,
            String backupSha256,
            String metadata) {}

    private record IntegrationSnapshot(
            AdminDbRow admin,
            List<String> recoveryHashes,
            List<MetadataDbRow> metadata,
            List<AuditDbRow> audits,
            List<String> springPrimaryIds) {
        private IntegrationSnapshot {
            recoveryHashes = List.copyOf(recoveryHashes);
            metadata = List.copyOf(metadata);
            audits = List.copyOf(audits);
            springPrimaryIds = List.copyOf(springPrimaryIds);
        }
    }

    private record CommittedView(
            String passwordHash,
            String status,
            long version,
            int recoveryCount,
            int revokedCount,
            int sessionAuditCount,
            int recoveryAuditCount,
            int springCount) {}

    private static void assertMandatory(Method method) {
        Transactional transactional = method.getAnnotation(Transactional.class);
        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
    }

    private static List<String> publicMethodSignatures(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(method -> method.getName()
                        + '(' + Arrays.stream(method.getParameterTypes())
                                .map(Class::getName)
                                .collect(Collectors.joining(","))
                        + "):" + method.getReturnType().getName())
                .sorted()
                .toList();
    }

    private static void assertWiped(char[] value) {
        assertThat(value).containsOnly('\0');
    }

    private static void assertDomain(Throwable failure, String code, HttpStatus status) {
        assertThat(failure).isInstanceOf(DomainException.class);
        DomainException domain = (DomainException) failure;
        assertThat(domain.code()).isEqualTo(code);
        assertThat(domain.status()).isEqualTo(status);
        assertThat(domain.fieldErrors()).isEmpty();
        assertThat(domain.getCause()).isNull();
    }

    private static void assertCauseFreeAndMarkerFree(Throwable failure, String... markers) {
        assertThat(failure).isInstanceOf(RuntimeException.class);
        assertThat(failure.getCause()).isNull();
        for (String marker : markers) {
            assertThat(failure.getMessage()).doesNotContain(marker);
            assertThat(failure.toString()).doesNotContain(marker);
        }
    }

    private static AdminSessionRepository.TerminalSession terminal(int suffix) {
        return new AdminSessionRepository.TerminalSession(
                UUID.fromString("00000000-0000-0000-0000-00000000000" + suffix),
                ADMIN_ID,
                UUID.fromString("10000000-0000-0000-0000-00000000000" + suffix).toString(),
                "ADMIN_RECOVERY");
    }

    private static void assertSessionAudit(
            AuditCommand command, AdminSessionRepository.TerminalSession terminal) {
        assertThat(command.actorAdminId()).isEqualTo(ADMIN_ID);
        assertThat(command.action()).isEqualTo("SESSION_REVOKED");
        assertThat(command.targetType()).isEqualTo("ADMIN_SESSION");
        assertThat(command.targetId()).isEqualTo(terminal.metadataId().toString());
        assertThat(command.outcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(command.metadata()).containsExactlyEntriesOf(
                Map.of("reason", "ADMIN_RECOVERY"));
    }

    private static void await(CountDownLatch latch, String message) {
        try {
            if (!latch.await(10, SECONDS)) {
                throw new IllegalStateException(message);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test coordination was interrupted");
        }
    }

    private enum Dependency {
        RESTORE,
        ADMINS,
        RECOVERY_REPOSITORY,
        RECOVERY_SERVICE,
        GENERATOR,
        POLICY,
        ENCODER,
        TOTP,
        SESSIONS,
        AUDIT,
        TRANSACTIONS
    }

    private static final class SyntheticAuditException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private SyntheticAuditException(String message) {
            super(message);
        }
    }

    private static final class CommitFailingTransactionManager
            implements PlatformTransactionManager {
        private final PlatformTransactionManager delegate;
        private final String marker;

        private CommitFailingTransactionManager(
                PlatformTransactionManager delegate, String marker) {
            this.delegate = delegate;
            this.marker = marker;
        }

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return delegate.getTransaction(definition);
        }

        @Override
        public void commit(TransactionStatus status) {
            delegate.rollback(status);
            throw new SyntheticAuditException(marker);
        }

        @Override
        public void rollback(TransactionStatus status) {
            delegate.rollback(status);
        }
    }

    private static final class RecordingTransactionTemplate extends TransactionTemplate {
        private static final long serialVersionUID = 1L;

        private final List<String> events;
        private int executions;

        private RecordingTransactionTemplate() {
            this(new CopyOnWriteArrayList<>());
        }

        private RecordingTransactionTemplate(List<String> events) {
            this.events = events;
        }

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            executions++;
            events.add("tx.begin");
            try {
                T result = action.doInTransaction(new SimpleTransactionStatus());
                events.add("tx.commit");
                return result;
            } catch (RuntimeException | Error failure) {
                events.add("tx.rollback");
                throw failure;
            }
        }

        private int executions() {
            return executions;
        }
    }

    private static final class UnitFixture {
        private final List<String> events = new CopyOnWriteArrayList<>();
        private final DatabaseRestorePointService restore =
                mock(DatabaseRestorePointService.class);
        private final AdminUserRepository admins = mock(AdminUserRepository.class);
        private final RecoveryCodeRepository recoveryRepository =
                mock(RecoveryCodeRepository.class);
        private final RecoveryCodeService recoveryService = mock(RecoveryCodeService.class);
        private final RecoveryCodeGenerator generator = mock(RecoveryCodeGenerator.class);
        private final PasswordPolicy policy = mock(PasswordPolicy.class);
        private final PasswordEncoder encoder = mock(PasswordEncoder.class);
        private final TotpService totp = mock(TotpService.class);
        private final AdminSessionService sessions = mock(AdminSessionService.class);
        private final AuditService audit = mock(AuditService.class);
        private final RecordingTransactionTemplate transactions =
                new RecordingTransactionTemplate(events);
        private final List<String> generatedCodes = new ArrayList<>(RECOVERY_CODES);
        private final List<String> generatedHashes = new ArrayList<>(RECOVERY_HASHES);
        private final AtomicReference<char[]> passwordArray = new AtomicReference<>();
        private final AtomicReference<Class<?>> encoderInputType = new AtomicReference<>();
        private final AtomicReference<AdminRecoveryService.Enrollment> enrollmentAtDelete =
                new AtomicReference<>();
        private final AdminRecoveryService service;

        private UnitFixture() {
            doAnswer(invocation -> {
                        events.add("password.policy");
                        CharSequence password = invocation.getArgument(0);
                        assertThat(password).isInstanceOf(CharBuffer.class);
                        assertThat(password.toString()).isEqualTo(PASSWORD);
                        return null;
                    })
                    .when(policy)
                    .requireStrong(any());
            when(encoder.encode(any())).thenAnswer(invocation -> {
                events.add("password.encode");
                CharSequence password = invocation.getArgument(0);
                encoderInputType.set(password.getClass());
                assertThat(password).isInstanceOf(CharBuffer.class);
                assertThat(password.toString()).isEqualTo(PASSWORD);
                return PASSWORD_HASH;
            });
            when(admins.requireOnlyAdmin()).thenAnswer(invocation -> {
                events.add("admin.read");
                return admin();
            });
            when(restore.create()).thenAnswer(invocation -> {
                events.add("backup.create");
                char[] caller = passwordArray.get();
                if (caller != null) {
                    assertWiped(caller);
                }
                return restorePoint();
            });
            when(totp.beginEnrollment(ADMIN_ID, "YYchainsaw.Admin"))
                    .thenAnswer(invocation -> {
                        events.add("totp.enroll");
                        return new TotpService.Enrollment(
                                TOTP_SECRET, encryptedSecret(), PROVISIONING_URI);
                    });
            when(generator.generate(10)).thenAnswer(invocation -> {
                events.add("recovery.generate");
                return generatedCodes;
            });
            when(recoveryService.hashAll(any())).thenAnswer(invocation -> {
                events.add("recovery.hash");
                return generatedHashes;
            });
            when(totp.verifyEnrollment(TOTP_SECRET, TOTP_CODE)).thenAnswer(invocation -> {
                events.add("totp.verify");
                return true;
            });
            when(admins.replaceCredentialsIfVersion(
                            eq(ADMIN_ID), eq(ADMIN_VERSION), anyString(), any()))
                    .thenAnswer(invocation -> {
                        events.add("admin.cas");
                        return OptionalLong.of(ADMIN_VERSION + 1);
                    });
            doAnswer(invocation -> {
                        events.add("recovery.replace");
                        return null;
                    })
                    .when(recoveryRepository)
                    .replace(any(), any());
            when(sessions.markAllSessionsRevokedInCurrentTransaction(
                            ADMIN_ID, "ADMIN_RECOVERY"))
                    .thenAnswer(invocation -> {
                        events.add("sessions.mark");
                        return List.of(terminal(1), terminal(2));
                    });
            doAnswer(invocation -> {
                        AuditCommand command = invocation.getArgument(0);
                        events.add("audit." + command.action());
                        return null;
                    })
                    .when(audit)
                    .record(any());
            doAnswer(invocation -> {
                        events.add("sessions.deleteAll");
                        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                                .isFalse();
                        AdminRecoveryService.Enrollment current = enrollmentAtDelete.get();
                        if (current != null) {
                            assertThatThrownBy(current::backupSha256)
                                    .isInstanceOf(IllegalStateException.class);
                        }
                        return null;
                    })
                    .when(sessions)
                    .deleteAllSpringSessionsBestEffort();
            service = construct(null, transactions);
        }

        private AdminRecoveryService construct(
                Dependency nullDependency, TransactionTemplate transactionTemplate) {
            return new AdminRecoveryService(
                    nullDependency == Dependency.RESTORE ? null : restore,
                    nullDependency == Dependency.ADMINS ? null : admins,
                    nullDependency == Dependency.RECOVERY_REPOSITORY
                            ? null
                            : recoveryRepository,
                    nullDependency == Dependency.RECOVERY_SERVICE ? null : recoveryService,
                    nullDependency == Dependency.GENERATOR ? null : generator,
                    nullDependency == Dependency.POLICY ? null : policy,
                    nullDependency == Dependency.ENCODER ? null : encoder,
                    nullDependency == Dependency.TOTP ? null : totp,
                    nullDependency == Dependency.SESSIONS ? null : sessions,
                    nullDependency == Dependency.AUDIT ? null : audit,
                    nullDependency == Dependency.TRANSACTIONS ? null : transactionTemplate);
        }

        private void clearCollaboratorInvocations() {
            clearInvocations(
                    restore,
                    admins,
                    recoveryRepository,
                    recoveryService,
                    generator,
                    policy,
                    encoder,
                    totp,
                    sessions,
                    audit);
        }

        private void verifyNoCollaboratorInteractions() {
            verifyNoInteractions(
                    restore,
                    admins,
                    recoveryRepository,
                    recoveryService,
                    generator,
                    policy,
                    encoder,
                    totp,
                    sessions,
                    audit);
        }
    }

    private static AdminUser admin() {
        return new AdminUser(
                ADMIN_ID,
                "YYchainsaw.Admin",
                "old-password-hash",
                AdminStatus.ACTIVE,
                encryptedSecret(),
                null,
                ADMIN_VERSION,
                NOW.minusSeconds(3600),
                NOW.minusSeconds(60));
    }

    private static EncryptedTotpSecret encryptedSecret() {
        byte[] nonce = new byte[12];
        byte[] ciphertext = new byte[24];
        Arrays.fill(nonce, (byte) 7);
        Arrays.fill(ciphertext, (byte) 11);
        return new EncryptedTotpSecret(1, nonce, ciphertext);
    }

    private static EncryptedTotpSecret integrationInitialSecret() {
        byte[] nonce = new byte[12];
        byte[] ciphertext = new byte[24];
        Arrays.fill(nonce, (byte) 3);
        Arrays.fill(ciphertext, (byte) 5);
        return new EncryptedTotpSecret(1, nonce, ciphertext);
    }

    private static DatabaseRestorePointService.RestorePoint restorePoint() {
        Path path = Path.of(System.getProperty("java.io.tmpdir"))
                .resolve("portfolio-recovery-test.dump")
                .toAbsolutePath()
                .normalize();
        return new DatabaseRestorePointService.RestorePoint(path, BACKUP_SHA256);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RecoveryTestConfiguration {
        @Bean
        @Primary
        DatabaseRestorePointService recoveryTestRestorePointService() {
            DatabaseRestorePointService service = mock(DatabaseRestorePointService.class);
            when(service.create()).thenReturn(restorePoint());
            return service;
        }
    }
}
