package xyz.yychainsaw.portfolio.auth.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.yychainsaw.portfolio.api.admin.audit.AdminAuditItem;
import xyz.yychainsaw.portfolio.audit.AuditCommand;
import xyz.yychainsaw.portfolio.audit.AuditOutcome;
import xyz.yychainsaw.portfolio.audit.AuditService;
import xyz.yychainsaw.portfolio.audit.AdminAuditQueryService;
import xyz.yychainsaw.portfolio.audit.JdbcAuditService;
import xyz.yychainsaw.portfolio.auth.crypto.EncryptedTotpSecret;
import xyz.yychainsaw.portfolio.auth.crypto.TotpEnvelopeCrypto;
import xyz.yychainsaw.portfolio.auth.crypto.TotpProperties;
import xyz.yychainsaw.portfolio.auth.crypto.TotpService;
import xyz.yychainsaw.portfolio.auth.model.AdminStatus;
import xyz.yychainsaw.portfolio.auth.model.AdminUser;
import xyz.yychainsaw.portfolio.auth.persistence.AdminUserRepository;
import xyz.yychainsaw.portfolio.auth.persistence.RecoveryCodeRepository;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
@Import(TotpKeyReencryptionServiceTest.ReencryptionTestConfiguration.class)
@TestPropertySource(properties = "portfolio.security.session.cleanup-interval=PT24H")
@Isolated
class TotpKeyReencryptionServiceTest extends PostgresIntegrationTestBase {
    private static final String KEY_ONE =
            "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    private static final String KEY_TWO =
            "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8=";
    private static final String SEED = "JBSWY3DPEHPK3PXP";
    private static final String BACKUP_SHA256 = "a".repeat(64);
    private static final String MAINTENANCE_ERROR =
            "TOTP key re-encryption could not be completed";
    private static final long FIXED_TOTP_COUNTER = 63_333_333L;
    private static final Instant CREATED_AT = Instant.parse("2026-07-16T01:02:03.123456Z");
    private static final String CONFIRM_PROMPT =
            "Type REENCRYPT TOTP KEY to create a dump and re-encrypt the TOTP key: ";
    private static final String REFUSAL = "TOTP key re-encryption was not confirmed";
    private static final String CHANGED = "TOTP key re-encryption completed.";
    private static final String UNCHANGED =
            "TOTP key already uses the active encryption key.";

    @Autowired TotpKeyReencryptionService service;
    @Autowired AdminUserRepository admins;
    @Autowired RecoveryCodeRepository recoveryCodes;
    @Autowired FakeRestorePointService restorePoints;
    @Autowired SwitchableAuditService audit;
    @Autowired AdminAuditQueryService auditQueries;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper objectMapper;
    @MockitoSpyBean(name = "task14TotpEnvelopeCrypto") TotpEnvelopeCrypto crypto;

    private final Set<UUID> adminIds = new java.util.LinkedHashSet<>();
    private final Set<UUID> auditIds = new java.util.LinkedHashSet<>();

    @BeforeEach
    void resetDoubles() {
        restorePoints.reset();
        audit.reset();
        reset(crypto);
    }

    @AfterEach
    void cleanOwnedRows() {
        JdbcClient owner = migratorJdbc();
        for (UUID adminId : adminIds) {
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
                    owner.sql("delete from portfolio.audit_log where id=:id")
                            .param("id", auditId)
                            .update();
                }
            } finally {
                owner.sql("alter table portfolio.audit_log enable trigger audit_log_reject_mutation")
                        .update();
            }
        } finally {
            for (UUID adminId : adminIds) {
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
            adminIds.clear();
            auditIds.clear();
            Thread.interrupted();
        }
    }

    @Test
    void publicApiResultInvariantsAndCasBoundaryAreFrozen() throws Exception {
        assertThat(Modifier.isFinal(TotpKeyReencryptionService.class.getModifiers())).isTrue();
        assertThat(TotpKeyReencryptionService.class.getAnnotation(Service.class)).isNotNull();
        assertThat(TotpKeyReencryptionService.class.getAnnotation(Transactional.class)).isNull();
        assertThat(Arrays.stream(TotpKeyReencryptionService.class.getDeclaredMethods())
                        .filter(method -> Modifier.isPublic(method.getModifiers())))
                .extracting(Method::getName)
                .containsExactly("reencryptToActiveKey");
        assertThat(TotpKeyReencryptionService.class.getDeclaredConstructors())
                .singleElement()
                .extracting(Constructor::getParameterTypes)
                .isEqualTo(new Class<?>[] {
                    DatabaseRestorePointService.class,
                    AdminUserRepository.class,
                    TotpEnvelopeCrypto.class,
                    AuditService.class,
                    TransactionTemplate.class
                });

        assertThat(TotpKeyReencryptionResult.class.isRecord()).isTrue();
        assertThat(Serializable.class.isAssignableFrom(TotpKeyReencryptionResult.class)).isFalse();
        assertThat(Arrays.stream(TotpKeyReencryptionResult.class.getRecordComponents())
                        .map(component -> component.getName() + ':' + component.getType().getName()))
                .containsExactly(
                        "changed:boolean",
                        "previousKeyVersion:int",
                        "activeKeyVersion:int",
                        "backupSha256:java.lang.String");
        assertThat(new TotpKeyReencryptionResult(false, 2, 2, null).toString())
                .doesNotContain("path=", "nonce=", "ciphertext=", SEED);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TotpKeyReencryptionResult(true, 2, 2, BACKUP_SHA256));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TotpKeyReencryptionResult(true, 1, 2, null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TotpKeyReencryptionResult(false, 1, 2, null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TotpKeyReencryptionResult(false, 2, 2, BACKUP_SHA256));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TotpKeyReencryptionResult(true, 1, 2, "A".repeat(64)));

        Method cas = AdminUserRepository.class.getMethod(
                "updateTotpIfVersion",
                UUID.class,
                long.class,
                int.class,
                EncryptedTotpSecret.class);
        assertThat(cas.getReturnType()).isEqualTo(OptionalLong.class);
        assertThat(cas.getAnnotation(Transactional.class).propagation())
                .isEqualTo(Propagation.MANDATORY);
    }

    @Test
    void currentKeyIsAnAuditAndBackupFreeNoop() {
        Fixture fixture = insertFixture(2);
        Snapshot before = snapshot(fixture.adminId());

        TotpKeyReencryptionResult result = service.reencryptToActiveKey();

        assertThat(result).isEqualTo(new TotpKeyReencryptionResult(false, 2, 2, null));
        assertThat(restorePoints.calls()).isZero();
        verify(crypto, never()).reencrypt(any(UUID.class), any(EncryptedTotpSecret.class));
        assertThat(snapshot(fixture.adminId())).isEqualTo(before);
        assertThat(auditRows(fixture.adminId())).isEmpty();
    }

    @Test
    void successfulRotationKeepsSeedPasswordRecoveryAndSessionsAndWritesExactAudit() {
        Fixture fixture = insertFixture(1);
        Snapshot before = snapshot(fixture.adminId());

        TotpKeyReencryptionResult result = service.reencryptToActiveKey();

        assertThat(result).isEqualTo(
                new TotpKeyReencryptionResult(true, 1, 2, BACKUP_SHA256));
        assertThat(restorePoints.calls()).isEqualTo(1);
        Snapshot after = snapshot(fixture.adminId());
        assertThat(after.admin().version()).isEqualTo(before.admin().version() + 1);
        assertThat(after.admin().passwordHash()).isEqualTo(before.admin().passwordHash());
        assertThat(after.admin().status()).isEqualTo(before.admin().status());
        assertThat(after.admin().lastLoginAt()).isEqualTo(before.admin().lastLoginAt());
        assertThat(after.admin().totpSecret().keyVersion()).isEqualTo(2);
        assertThat(after.admin().totpSecret().nonce())
                .isNotEqualTo(before.admin().totpSecret().nonce());
        assertThat(after.admin().totpSecret().ciphertext())
                .isNotEqualTo(before.admin().totpSecret().ciphertext());
        assertThat(activeCrypto().decrypt(fixture.adminId(), after.admin().totpSecret()))
                .isEqualTo(SEED);
        assertThat(v2OnlyCrypto().decrypt(fixture.adminId(), after.admin().totpSecret()))
                .isEqualTo(SEED);
        assertThat(after.recoveryHashes()).isEqualTo(before.recoveryHashes());
        assertThat(after.sessionStates()).isEqualTo(before.sessionStates());

        List<AuditRow> rows = auditRows(fixture.adminId());
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.action()).isEqualTo("TOTP_KEY_REENCRYPTED");
            assertThat(row.targetType()).isEqualTo("ADMIN");
            assertThat(row.targetId()).isEqualTo(fixture.adminId().toString());
            assertThat(row.outcome()).isEqualTo("SUCCESS");
            assertThat(row.metadata()).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "fromKeyVersion", "1",
                    "toKeyVersion", "2",
                    "channel", "LOCAL_CLI",
                    "backupSha256", BACKUP_SHA256));
        });
    }

    @Test
    void staleCasIsConflictAndCreatesNoPartialAuditOrCredentialMutation() {
        Fixture fixture = insertFixture(1);
        EncryptedTotpSecret oldSecret = fixture.initial().totpSecret();
        Instant competingLogin = CREATED_AT.plusSeconds(30);
        restorePoints.afterCreate(() -> admins.updateLastLogin(fixture.adminId(), competingLogin));

        assertThatThrownBy(service::reencryptToActiveKey)
                .isInstanceOfSatisfying(DomainException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("AUTH_VERSION_CONFLICT");
                    assertThat(failure.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(failure.fieldErrors()).isEmpty();
                    assertThat(failure.getCause()).isNull();
                });

        AdminUser after = admins.findById(fixture.adminId()).orElseThrow();
        assertThat(after.version()).isEqualTo(fixture.initial().version() + 1);
        assertThat(after.lastLoginAt()).isEqualTo(competingLogin);
        assertThat(after.totpSecret()).isEqualTo(oldSecret);
        assertThat(auditRows(fixture.adminId())).isEmpty();
    }

    @Test
    void presentCasVersionMustAdvanceByExactlyOneBeforeAudit() {
        Fixture fixture = insertFixture(1);
        Snapshot before = snapshot(fixture.adminId());
        AdminUserRepository invalidVersionAdmins = spy(new AdminUserRepository(jdbc));
        doReturn(OptionalLong.of(fixture.initial().version() + 2))
                .when(invalidVersionAdmins)
                .updateTotpIfVersion(
                        eq(fixture.adminId()),
                        eq(fixture.initial().version()),
                        eq(1),
                        any(EncryptedTotpSecret.class));
        TotpKeyReencryptionService invalidVersionService = new TotpKeyReencryptionService(
                restorePoints,
                invalidVersionAdmins,
                crypto,
                audit,
                new TransactionTemplate(transactionManager));

        assertThatThrownBy(invalidVersionService::reencryptToActiveKey)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(MAINTENANCE_ERROR)
                .hasNoCause();

        assertThat(restorePoints.calls()).isOne();
        verify(crypto).reencrypt(any(UUID.class), any(EncryptedTotpSecret.class));
        assertThat(snapshot(fixture.adminId())).isEqualTo(before);
        assertThat(auditRows(fixture.adminId())).isEmpty();
    }

    @Test
    void auditFailureRollsBackEnvelopeAndVersionCauseFree() {
        Fixture fixture = insertFixture(1);
        Snapshot before = snapshot(fixture.adminId());
        audit.failAfterDelegate();

        assertThatThrownBy(service::reencryptToActiveKey)
                .isInstanceOf(IllegalStateException.class)
                .hasNoCause()
                .hasMessageNotContaining("AUDIT_FAILURE_MARKER");

        assertThat(snapshot(fixture.adminId())).isEqualTo(before);
        assertThat(auditRows(fixture.adminId())).isEmpty();
    }

    @Test
    void backupFailureIsCauseFreeAndPerformsNoCryptoOrDatabaseMutation() {
        Fixture fixture = insertFixture(1);
        Snapshot before = snapshot(fixture.adminId());
        String marker = "BACKUP_FAILURE_MARKER";
        restorePoints.failWith(new MarkerFailure(marker));

        assertThatThrownBy(service::reencryptToActiveKey)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(MAINTENANCE_ERROR)
                .hasNoCause()
                .hasMessageNotContaining(marker);

        assertThat(restorePoints.calls()).isOne();
        verify(crypto, never()).reencrypt(any(UUID.class), any(EncryptedTotpSecret.class));
        assertThat(snapshot(fixture.adminId())).isEqualTo(before);
        assertThat(auditRows(fixture.adminId())).isEmpty();
    }

    @Test
    void cryptoFailureKeepsTheCreatedBackupButPerformsNoDatabaseMutation() {
        Fixture fixture = insertFixture(1);
        Snapshot before = snapshot(fixture.adminId());
        String marker = "CRYPTO_FAILURE_MARKER";
        doThrow(new MarkerFailure(marker))
                .when(crypto)
                .reencrypt(any(UUID.class), any(EncryptedTotpSecret.class));

        assertThatThrownBy(service::reencryptToActiveKey)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(MAINTENANCE_ERROR)
                .hasNoCause()
                .hasMessageNotContaining(marker);

        assertThat(restorePoints.calls()).isOne();
        verify(crypto, times(1)).reencrypt(any(UUID.class), any(EncryptedTotpSecret.class));
        assertThat(snapshot(fixture.adminId())).isEqualTo(before);
        assertThat(auditRows(fixture.adminId())).isEmpty();
    }

    @Test
    void commitFailureRollsBackEnvelopeVersionAndAuditAndIsCauseFree() {
        Fixture fixture = insertFixture(1);
        Snapshot before = snapshot(fixture.adminId());
        String marker = "COMMIT_FAILURE_MARKER";
        TransactionTemplate failingTransactions = new TransactionTemplate(
                new CommitFailingTransactionManager(transactionManager, marker));
        TotpKeyReencryptionService commitFailingService = new TotpKeyReencryptionService(
                restorePoints, admins, crypto, audit, failingTransactions);

        assertThatThrownBy(commitFailingService::reencryptToActiveKey)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(MAINTENANCE_ERROR)
                .hasNoCause()
                .hasMessageNotContaining(marker);

        assertThat(restorePoints.calls()).isOne();
        verify(crypto, times(1)).reencrypt(any(UUID.class), any(EncryptedTotpSecret.class));
        assertThat(snapshot(fixture.adminId())).isEqualTo(before);
        assertThat(auditRows(fixture.adminId())).isEmpty();
    }

    @Test
    void ambientTransactionIsRejectedBeforeBackupCryptoOrDatabaseMutation() {
        Fixture fixture = insertFixture(1);
        Snapshot before = snapshot(fixture.adminId());

        new TransactionTemplate(transactionManager).executeWithoutResult(ignored ->
                assertThatThrownBy(service::reencryptToActiveKey)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("TOTP key re-encryption requires no ambient transaction")
                        .hasNoCause());

        assertThat(restorePoints.calls()).isZero();
        verifyNoInteractions(crypto);
        assertThat(snapshot(fixture.adminId())).isEqualTo(before);
        assertThat(auditRows(fixture.adminId())).isEmpty();
    }

    @Test
    void concurrentCallsThatReadTheSameVersionProduceOneSuccessAndOneConflict() throws Exception {
        Fixture fixture = insertFixture(1);
        restorePoints.synchronizeNextTwoCreates();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Object> outcomes;
        try {
            Future<Object> first = executor.submit(() -> {
                start.await();
                return captureReencryptionOutcome();
            });
            Future<Object> second = executor.submit(() -> {
                start.await();
                return captureReencryptionOutcome();
            });
            start.countDown();
            outcomes = List.of(
                    first.get(20, TimeUnit.SECONDS),
                    second.get(20, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        List<TotpKeyReencryptionResult> successes = outcomes.stream()
                .filter(TotpKeyReencryptionResult.class::isInstance)
                .map(TotpKeyReencryptionResult.class::cast)
                .toList();
        List<DomainException> conflicts = outcomes.stream()
                .filter(DomainException.class::isInstance)
                .map(DomainException.class::cast)
                .toList();
        assertThat(successes)
                .containsExactly(new TotpKeyReencryptionResult(true, 1, 2, BACKUP_SHA256));
        assertThat(conflicts).singleElement().satisfies(failure -> {
            assertThat(failure.code()).isEqualTo("AUTH_VERSION_CONFLICT");
            assertThat(failure.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(failure.fieldErrors()).isEmpty();
            assertThat(failure.getCause()).isNull();
        });
        assertThat(restorePoints.calls()).isEqualTo(2);
        verify(crypto, times(2)).reencrypt(any(UUID.class), any(EncryptedTotpSecret.class));

        AdminUser after = admins.findById(fixture.adminId()).orElseThrow();
        assertThat(after.version()).isEqualTo(fixture.initial().version() + 1);
        assertThat(after.totpSecret().keyVersion()).isEqualTo(2);
        assertThat(activeCrypto().decrypt(fixture.adminId(), after.totpSecret())).isEqualTo(SEED);
        assertThat(auditRows(fixture.adminId())).hasSize(1);
    }

    @Test
    void rotationPreservesTheTotpFactorAtAFixedCounter() throws Exception {
        Fixture fixture = insertFixture(1);
        TotpProperties verificationProperties = properties(
                2, "1=" + KEY_ONE + ",2=" + KEY_TWO);
        DefaultCodeGenerator generator = new DefaultCodeGenerator();
        TotpService verifier = new TotpService(
                verificationProperties,
                new TotpEnvelopeCrypto(verificationProperties),
                () -> SEED,
                generator,
                () -> FIXED_TOTP_COUNTER * 30L);
        String fixedCode = generator.generate(SEED, FIXED_TOTP_COUNTER);
        assertThat(verifier.verify(
                        fixture.adminId(), fixture.initial().totpSecret(), fixedCode))
                .isTrue();

        service.reencryptToActiveKey();

        EncryptedTotpSecret replacement = admins.findById(fixture.adminId())
                .orElseThrow()
                .totpSecret();
        assertThat(verifier.verify(fixture.adminId(), replacement, fixedCode)).isTrue();
    }

    @Test
    void task13AuditQueryExposesOnlySafeReencryptionMetadata() {
        Fixture fixture = insertFixture(1);

        service.reencryptToActiveKey();

        List<AdminAuditItem> visible = auditQueries.find(
                        null, "TOTP_KEY_REENCRYPTED", "SUCCESS", null, null, "100")
                .items()
                .stream()
                .filter(item -> fixture.adminId().equals(item.actorAdminId()))
                .toList();
        assertThat(visible).singleElement().satisfies(item -> {
            assertThat(item.metadata()).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "fromKeyVersion", "1",
                    "toKeyVersion", "2",
                    "channel", "LOCAL_CLI",
                    "backupSha256", BACKUP_SHA256));
            assertThat(item.metadata().toString())
                    .doesNotContain(SEED, "nonce", "ciphertext", ".dump", "filePath");
        });
    }

    @Test
    void cliRequiresExactConfirmationAndChangedOutputIsSafeAndOrdered() {
        SecretConsole refusedConsole = mock(SecretConsole.class);
        AdminBootstrapService bootstrap = mock(AdminBootstrapService.class);
        AdminRecoveryService recovery = mock(AdminRecoveryService.class);
        TotpKeyReencryptionService maintenance = mock(TotpKeyReencryptionService.class);
        when(refusedConsole.readLine(CONFIRM_PROMPT)).thenReturn(" REENCRYPT TOTP KEY ");

        assertThatThrownBy(() -> new AdminCliRunner(
                        refusedConsole, bootstrap, recovery, maintenance)
                .run(arguments("--portfolio.cli.command=totp-reencrypt")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasNoCause()
                .hasMessage(REFUSAL);
        verifyNoInteractions(bootstrap, recovery, maintenance);
        verify(refusedConsole, never()).println(any());

        SecretConsole console = mock(SecretConsole.class);
        when(console.readLine(CONFIRM_PROMPT)).thenReturn("REENCRYPT TOTP KEY");
        when(maintenance.reencryptToActiveKey()).thenReturn(
                new TotpKeyReencryptionResult(true, 1, 2, BACKUP_SHA256));

        new AdminCliRunner(console, bootstrap, recovery, maintenance)
                .run(arguments("--portfolio.cli.command=totp-reencrypt"));

        InOrder order = inOrder(console, maintenance);
        order.verify(console).readLine(CONFIRM_PROMPT);
        order.verify(maintenance).reencryptToActiveKey();
        order.verify(console).println(CHANGED);
        order.verify(console).println("TOTP key version: 1 -> 2");
        order.verify(console).println("Database restore point SHA-256: " + BACKUP_SHA256);
        order.verifyNoMoreInteractions();
        verifyNoInteractions(bootstrap, recovery);
    }

    @Test
    void cliNoopNeverPrintsChecksumOrCredentialMaterial() {
        SecretConsole console = mock(SecretConsole.class);
        AdminBootstrapService bootstrap = mock(AdminBootstrapService.class);
        AdminRecoveryService recovery = mock(AdminRecoveryService.class);
        TotpKeyReencryptionService maintenance = mock(TotpKeyReencryptionService.class);
        when(console.readLine(CONFIRM_PROMPT)).thenReturn("REENCRYPT TOTP KEY");
        when(maintenance.reencryptToActiveKey()).thenReturn(
                new TotpKeyReencryptionResult(false, 2, 2, null));

        new AdminCliRunner(console, bootstrap, recovery, maintenance)
                .run(arguments("--portfolio.cli.command=totp-reencrypt"));

        InOrder order = inOrder(console, maintenance);
        order.verify(console).readLine(CONFIRM_PROMPT);
        order.verify(maintenance).reencryptToActiveKey();
        order.verify(console).println(UNCHANGED);
        order.verify(console).println("TOTP key version: 2 -> 2");
        order.verifyNoMoreInteractions();
        verify(console, never()).println(org.mockito.ArgumentMatchers.contains("SHA-256"));
        verifyNoInteractions(bootstrap, recovery);
    }

    private Object captureReencryptionOutcome() {
        try {
            return service.reencryptToActiveKey();
        } catch (RuntimeException failure) {
            return failure;
        }
    }

    private Fixture insertFixture(int keyVersion) {
        UUID adminId = UUID.randomUUID();
        adminIds.add(adminId);
        TotpEnvelopeCrypto source = keyVersion == 1 ? v1Crypto() : activeCrypto();
        AdminUser admin = new AdminUser(
                adminId,
                "task14-" + adminId.toString().substring(0, 8),
                "password-hash-" + adminId,
                AdminStatus.DISABLED,
                source.encrypt(adminId, SEED),
                CREATED_AT.plusSeconds(1),
                7,
                CREATED_AT,
                CREATED_AT);
        admins.insert(admin);
        recoveryCodes.replace(adminId, List.of("recovery-hash-a", "recovery-hash-b"));
        jdbc.sql("""
                        insert into portfolio.admin_session_metadata
                            (id, admin_id, session_primary_id, status, client_summary)
                        values (:id, :adminId, null, 'ACTIVE', 'Task14/Fixture @ local')
                        """)
                .param("id", UUID.randomUUID())
                .param("adminId", adminId)
                .update();
        return new Fixture(adminId, admin);
    }

    private Snapshot snapshot(UUID adminId) {
        AdminUser admin = admins.findById(adminId).orElseThrow();
        List<String> hashes = jdbc.sql("""
                        select code_hash from portfolio.totp_recovery_code
                        where admin_id=:adminId order by id
                        """)
                .param("adminId", adminId)
                .query(String.class)
                .list();
        List<String> sessions = jdbc.sql("""
                        select status || ':' || coalesce(revocation_reason, '') || ':' || version
                        from portfolio.admin_session_metadata
                        where admin_id=:adminId order by id
                        """)
                .param("adminId", adminId)
                .query(String.class)
                .list();
        return new Snapshot(admin, List.copyOf(hashes), List.copyOf(sessions));
    }

    private List<AuditRow> auditRows(UUID adminId) {
        List<RawAuditRow> raw = jdbc.sql("""
                        select id, action, target_type, target_id, outcome, metadata::text metadata
                        from portfolio.audit_log
                        where actor_admin_id=:adminId order by created_at, id
                        """)
                .param("adminId", adminId)
                .query((rs, row) -> new RawAuditRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("action"),
                        rs.getString("target_type"),
                        rs.getString("target_id"),
                        rs.getString("outcome"),
                        rs.getString("metadata")))
                .list();
        List<AuditRow> rows = new ArrayList<>(raw.size());
        for (RawAuditRow row : raw) {
            try {
                Map<String, String> metadata = objectMapper.readValue(
                        row.metadata(), new TypeReference<Map<String, String>>() {});
                rows.add(new AuditRow(
                        row.id(), row.action(), row.targetType(), row.targetId(),
                        row.outcome(), Map.copyOf(metadata)));
                auditIds.add(row.id());
            } catch (java.io.IOException invalidJson) {
                throw new AssertionError("audit metadata was not readable", invalidJson);
            }
        }
        return List.copyOf(rows);
    }

    private static TotpEnvelopeCrypto v1Crypto() {
        return new TotpEnvelopeCrypto(properties(1, "1=" + KEY_ONE));
    }

    private static TotpEnvelopeCrypto activeCrypto() {
        return new TotpEnvelopeCrypto(properties(2, "1=" + KEY_ONE + ",2=" + KEY_TWO));
    }

    private static TotpEnvelopeCrypto v2OnlyCrypto() {
        return new TotpEnvelopeCrypto(properties(2, "2=" + KEY_TWO));
    }

    private static TotpProperties properties(int activeVersion, String keyRing) {
        return new TotpProperties(activeVersion, keyRing, "yychainsaw.xyz", Duration.ofMinutes(5), 5);
    }

    private static DefaultApplicationArguments arguments(String... values) {
        return new DefaultApplicationArguments(values);
    }

    private record Fixture(UUID adminId, AdminUser initial) {}

    private record Snapshot(
            AdminUser admin, List<String> recoveryHashes, List<String> sessionStates) {}

    private record RawAuditRow(
            UUID id, String action, String targetType, String targetId,
            String outcome, String metadata) {}

    private record AuditRow(
            UUID id, String action, String targetType, String targetId,
            String outcome, Map<String, String> metadata) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class ReencryptionTestConfiguration {
        @Bean
        @Primary
        TotpProperties task14TotpProperties() {
            return properties(2, "1=" + KEY_ONE + ",2=" + KEY_TWO);
        }

        @Bean
        @Primary
        TotpEnvelopeCrypto task14TotpEnvelopeCrypto() {
            return activeCrypto();
        }

        @Bean
        @Primary
        FakeRestorePointService fakeRestorePointService() {
            return new FakeRestorePointService();
        }

        @Bean
        @Primary
        SwitchableAuditService switchableAuditService(JdbcAuditService delegate) {
            return new SwitchableAuditService(delegate);
        }
    }

    static final class FakeRestorePointService implements DatabaseRestorePointService {
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<Runnable> afterCreate = new AtomicReference<>();
        private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
        private final AtomicReference<CountDownLatch> synchronizedCreates = new AtomicReference<>();

        @Override
        public RestorePoint create() {
            calls.incrementAndGet();
            RuntimeException configuredFailure = failure.getAndSet(null);
            if (configuredFailure != null) {
                throw configuredFailure;
            }
            CountDownLatch barrier = synchronizedCreates.get();
            if (barrier != null) {
                barrier.countDown();
                try {
                    if (!barrier.await(10, TimeUnit.SECONDS)) {
                        throw new MarkerFailure("RESTORE_POINT_BARRIER_TIMEOUT");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new MarkerFailure("RESTORE_POINT_BARRIER_INTERRUPTED");
                }
            }
            Runnable callback = afterCreate.getAndSet(null);
            if (callback != null) {
                callback.run();
            }
            return new RestorePoint(
                    Path.of(System.getProperty("java.io.tmpdir"), UUID.randomUUID() + ".dump")
                            .toAbsolutePath()
                            .normalize(),
                    BACKUP_SHA256);
        }

        void afterCreate(Runnable callback) {
            afterCreate.set(callback);
        }

        void failWith(RuntimeException configuredFailure) {
            failure.set(configuredFailure);
        }

        void synchronizeNextTwoCreates() {
            synchronizedCreates.set(new CountDownLatch(2));
        }

        int calls() {
            return calls.get();
        }

        void reset() {
            calls.set(0);
            afterCreate.set(null);
            failure.set(null);
            synchronizedCreates.set(null);
        }
    }

    static final class SwitchableAuditService implements AuditService {
        private final JdbcAuditService delegate;
        private final CopyOnWriteArrayList<AuditCommand> commands = new CopyOnWriteArrayList<>();
        private volatile boolean failAfterDelegate;

        SwitchableAuditService(JdbcAuditService delegate) {
            this.delegate = delegate;
        }

        @Override
        public void record(AuditCommand command) {
            commands.add(command);
            delegate.record(command);
            if (failAfterDelegate) {
                throw new MarkerFailure("AUDIT_FAILURE_MARKER");
            }
        }

        void failAfterDelegate() {
            failAfterDelegate = true;
        }

        void reset() {
            failAfterDelegate = false;
            commands.clear();
        }
    }

    static final class CommitFailingTransactionManager implements PlatformTransactionManager {
        private final PlatformTransactionManager delegate;
        private final String marker;

        CommitFailingTransactionManager(PlatformTransactionManager delegate, String marker) {
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
            throw new MarkerFailure(marker);
        }

        @Override
        public void rollback(TransactionStatus status) {
            delegate.rollback(status);
        }
    }

    static final class MarkerFailure extends RuntimeException {
        MarkerFailure(String message) {
            super(message);
        }
    }
}
