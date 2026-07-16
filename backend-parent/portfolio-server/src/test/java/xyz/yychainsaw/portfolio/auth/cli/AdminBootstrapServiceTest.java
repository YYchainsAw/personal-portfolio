package xyz.yychainsaw.portfolio.auth.cli;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.Console;
import java.io.IOError;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.test.context.TestPropertySource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionCallback;
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
import xyz.yychainsaw.portfolio.auth.persistence.AdminUserRepository;
import xyz.yychainsaw.portfolio.auth.persistence.RecoveryCodeRepository;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
@Import(AdminBootstrapServiceTest.BootstrapTestConfiguration.class)
@TestPropertySource(properties = "portfolio.security.session.cleanup-interval=PT24H")
@Isolated
class AdminBootstrapServiceTest extends PostgresIntegrationTestBase {
    private static final String USERNAME = "YYchainsaw.Admin";
    private static final String PASSWORD = "PortfolioStrong!2026";
    private static final String PASSWORD_HASH = "sha256$password-hash";
    private static final String TOTP_SECRET = "JBSWY3DPEHPK3PXP";
    private static final String TOTP_CODE = "123456";
    private static final String RECOVERY_CONFIRM_PROMPT =
            "Type RECOVER ADMIN to create a dump and replace administrator credentials: ";
    private static final String RECOVERY_TOTP_PROMPT = "Current six-digit TOTP: ";
    private static final String RECOVERY_CHECKSUM = "a".repeat(64);
    private static final String RECOVERY_URI_HEADING =
            "Add this provisioning URI to the authenticator:";
    private static final String RECOVERY_CODES_HEADING =
            "Store these one-time recovery codes offline; they will not be shown again:";
    private static final String PROVISIONING_URI =
            "otpauth://totp/Portfolio:YYchainsaw.Admin?secret=JBSWY3DPEHPK3PXP";
    private static final Instant NOW = Instant.parse("2026-07-15T08:09:10.123456Z");
    private static final Pattern RECOVERY_PATTERN =
            Pattern.compile("[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}");
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

    @Autowired AdminBootstrapService bootstrap;
    @Autowired AdminUserRepository admins;
    @Autowired RecoveryCodeRepository recoveryCodes;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired PasswordPolicy passwordPolicy;
    @Autowired TotpService totp;
    @Autowired RecoveryCodeGenerator recoveryGenerator;
    @Autowired RecoveryCodeService recoveryService;
    @Autowired RecordingAuditService audit;
    @Autowired JdbcAuditService realAudit;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcClient jdbc;
    @Autowired Clock clock;

    @BeforeEach
    void resetAudit() {
        audit.clear();
    }

    @AfterEach
    void restoreInterruptFlag() {
        Thread.interrupted();
    }

    @Test
    void publicApiAnnotationsAndTransactionBoundaryAreExact() throws Exception {
        assertThat(SecretConsole.class.isInterface()).isTrue();
        assertThat(publicMethodSignatures(SecretConsole.class))
                .containsExactlyInAnyOrder(
                        "println(java.lang.String):void",
                        "readLine(java.lang.String):java.lang.String",
                        "readSecret(java.lang.String):[C");

        assertThat(Modifier.isFinal(SystemSecretConsole.class.getModifiers())).isTrue();
        assertThat(SystemSecretConsole.class.getAnnotation(Component.class)).isNotNull();
        assertThat(SystemSecretConsole.class.getConstructor()).isNotNull();
        Constructor<SystemSecretConsole> consoleConstructor =
                SystemSecretConsole.class.getDeclaredConstructor(Console.class);
        assertThat(Modifier.isPublic(consoleConstructor.getModifiers())).isFalse();
        assertThat(Modifier.isProtected(consoleConstructor.getModifiers())).isFalse();
        assertThat(Modifier.isPrivate(consoleConstructor.getModifiers())).isFalse();
        assertThat(Arrays.stream(SystemSecretConsole.class.getDeclaredFields())
                        .filter(field -> !Modifier.isStatic(field.getModifiers())))
                .singleElement()
                .extracting(Field::getType)
                .isEqualTo(Console.class);

        assertThat(Modifier.isFinal(AdminBootstrapService.class.getModifiers())).isTrue();
        assertThat(AdminBootstrapService.class.getAnnotation(Service.class)).isNotNull();
        assertThat(AdminBootstrapService.class.getAnnotation(Transactional.class)).isNull();
        Method complete = AdminBootstrapService.class.getMethod(
                "complete", AdminBootstrapService.Enrollment.class, char[].class);
        assertThat(complete.getAnnotation(Transactional.class)).isNull();
        assertThat(AdminBootstrapService.class.getMethod(
                        "prepare", String.class, char[].class).getReturnType())
                .isEqualTo(AdminBootstrapService.Enrollment.class);
        String enrollmentName = AdminBootstrapService.Enrollment.class.getName();
        assertThat(publicMethodSignatures(AdminBootstrapService.class))
                .containsExactlyInAnyOrder(
                        "complete(" + enrollmentName + ",[C):void",
                        "prepare(java.lang.String,[C):" + enrollmentName);

        assertThat(Modifier.isFinal(AdminCliRunner.class.getModifiers())).isTrue();
        assertThat(AdminCliRunner.class.getAnnotation(Component.class)).isNotNull();
        assertThat(AdminCliRunner.class.getAnnotation(Order.class).value())
                .isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        assertThat(AdminCliRunner.class.getInterfaces())
                .contains(org.springframework.boot.ApplicationRunner.class);
        assertThat(AdminCliRunner.class.getDeclaredConstructors())
                .singleElement()
                .extracting(Constructor::getParameterTypes)
                .isEqualTo(new Class<?>[] {
                    SecretConsole.class,
                    AdminBootstrapService.class,
                    AdminRecoveryService.class,
                    TotpKeyReencryptionService.class
                });

        Class<AdminBootstrapService.Enrollment> enrollment =
                AdminBootstrapService.Enrollment.class;
        assertThat(Modifier.isFinal(enrollment.getModifiers())).isTrue();
        assertThat(enrollment.isRecord()).isFalse();
        assertThat(Serializable.class.isAssignableFrom(enrollment)).isFalse();
        assertThat(Arrays.stream(enrollment.getDeclaredConstructors()))
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers()));
        assertThat(publicMethodSignatures(enrollment))
                .containsExactlyInAnyOrder(
                        "adminId():java.util.UUID",
                        "close():void",
                        "provisioningUri():java.lang.String",
                        "takePlaintextRecoveryCodes():java.util.List",
                        "toString():java.lang.String",
                        "username():java.lang.String");
    }

    @Test
    void constructorsRejectEveryNullDependencyWithFrozenMessages() {
        UnitFixture fixture = new UnitFixture();
        assertConstructorFailure(fixture, Dependency.ADMINS,
                "administrator repository is required");
        assertConstructorFailure(fixture, Dependency.RECOVERY_REPOSITORY,
                "recovery-code repository is required");
        assertConstructorFailure(fixture, Dependency.ENCODER,
                "password encoder is required");
        assertConstructorFailure(fixture, Dependency.POLICY,
                "password policy is required");
        assertConstructorFailure(fixture, Dependency.TOTP,
                "TOTP service is required");
        assertConstructorFailure(fixture, Dependency.GENERATOR,
                "recovery-code generator is required");
        assertConstructorFailure(fixture, Dependency.RECOVERY_SERVICE,
                "recovery-code service is required");
        assertConstructorFailure(fixture, Dependency.AUDIT,
                "audit service is required");
        assertConstructorFailure(fixture, Dependency.TRANSACTIONS,
                "transaction template is required");
        assertConstructorFailure(fixture, Dependency.CLOCK, "clock is required");

        TransactionTemplate wrongPropagation = new TransactionTemplate();
        wrongPropagation.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> fixture.construct(Dependency.NONE, wrongPropagation))
                .withMessage("bootstrap transaction template must use REQUIRED propagation");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AdminCliRunner(
                        null,
                        bootstrap,
                        mock(AdminRecoveryService.class),
                        mock(TotpKeyReencryptionService.class)))
                .withMessage("secret console is required");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AdminCliRunner(
                        mock(SecretConsole.class),
                        null,
                        mock(AdminRecoveryService.class),
                        mock(TotpKeyReencryptionService.class)))
                .withMessage("bootstrap service is required");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AdminCliRunner(
                        mock(SecretConsole.class),
                        bootstrap,
                        null,
                        mock(TotpKeyReencryptionService.class)))
                .withMessage("recovery service is required");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AdminCliRunner(
                        mock(SecretConsole.class),
                        bootstrap,
                        mock(AdminRecoveryService.class),
                        null))
                .withMessage("TOTP re-encryption service is required");
    }

    @Test
    void systemConsoleRejectsNullsAndUnavailableConsoleBeforeAnyFallback() {
        SystemSecretConsole unavailable = new SystemSecretConsole(null);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> unavailable.readLine(null))
                .withMessage("prompt is required");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> unavailable.readSecret(null))
                .withMessage("prompt is required");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> unavailable.println(null))
                .withMessage("value is required");

        for (ThrowingInvocation invocation : List.<ThrowingInvocation>of(
                () -> unavailable.readLine("line"),
                () -> unavailable.readSecret("secret"),
                () -> unavailable.println("value"))) {
            assertThatIllegalStateException()
                    .isThrownBy(invocation::run)
                    .withMessage("administrator CLI requires an interactive system console")
                    .withNoCause();
        }
    }

    @Test
    void systemConsoleUsesSafeFormattedReadsAndReturnsConsoleOwnedSecret() {
        Console provider = mock(Console.class);
        char[] secret = "console-secret".toCharArray();
        when(provider.readLine("%s", "visible 100%: ")).thenReturn("visible");
        when(provider.readPassword("%s", "secret 100%: ")).thenReturn(secret);
        SystemSecretConsole console = new SystemSecretConsole(provider);

        assertThat(console.readLine("visible 100%: ")).isEqualTo("visible");
        assertThat(console.readSecret("secret 100%: ")).isSameAs(secret);
        verify(provider).readLine("%s", "visible 100%: ");
        verify(provider).readPassword("%s", "secret 100%: ");
    }

    @Test
    void systemConsoleTranslatesCancellationInterruptionAndProviderFailuresCauseFree() {
        Console cancelled = mock(Console.class);
        when(cancelled.readLine("%s", "line")).thenReturn(null);
        when(cancelled.readPassword("%s", "secret")).thenReturn(null);
        SystemSecretConsole cancelledConsole = new SystemSecretConsole(cancelled);
        assertFixedCauseFree(() -> cancelledConsole.readLine("line"),
                "administrator CLI input was cancelled");
        assertFixedCauseFree(() -> cancelledConsole.readSecret("secret"),
                "administrator CLI input was cancelled");

        Console beforeProvider = mock(Console.class);
        Thread.currentThread().interrupt();
        assertFixedCauseFree(() -> new SystemSecretConsole(beforeProvider).readLine("line"),
                "administrator CLI input was cancelled");
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        verifyNoInteractions(beforeProvider);
        Thread.interrupted();

        Console afterProvider = mock(Console.class);
        char[] acquired = "acquired-secret".toCharArray();
        when(afterProvider.readPassword("%s", "secret")).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return acquired;
        });
        assertFixedCauseFree(() -> new SystemSecretConsole(afterProvider).readSecret("secret"),
                "administrator CLI input was cancelled");
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        assertWiped(acquired);
        Thread.interrupted();

        for (Throwable providerFailure : List.of(
                new IllegalStateException("provider leaked detail"),
                new IOError(new IOException("provider leaked detail")))) {
            Console failing = mock(Console.class);
            when(failing.readLine("%s", "line")).thenThrow(providerFailure);
            assertFixedCauseFree(() -> new SystemSecretConsole(failing).readLine("line"),
                    "administrator CLI input failed");
        }
    }

    @Test
    void systemConsoleFlushesAndChecksOutputAndTranslatesEveryFailureCauseFree() {
        Console provider = mock(Console.class);
        PrintWriter writer = mock(PrintWriter.class);
        when(provider.writer()).thenReturn(writer);
        SystemSecretConsole console = new SystemSecretConsole(provider);
        console.println("safe value");
        InOrder order = inOrder(writer);
        order.verify(writer).println("safe value");
        order.verify(writer).flush();
        order.verify(writer).checkError();

        Console checkErrorProvider = mock(Console.class);
        PrintWriter checkErrorWriter = mock(PrintWriter.class);
        when(checkErrorProvider.writer()).thenReturn(checkErrorWriter);
        when(checkErrorWriter.checkError()).thenReturn(true);
        assertFixedCauseFree(
                () -> new SystemSecretConsole(checkErrorProvider).println("safe"),
                "administrator CLI output failed");

        for (Throwable providerFailure : List.of(
                new IllegalStateException("provider leaked detail"),
                new IOError(new IOException("provider leaked detail")))) {
            Console failing = mock(Console.class);
            when(failing.writer()).thenThrow(providerFailure);
            assertFixedCauseFree(() -> new SystemSecretConsole(failing).println("safe"),
                    "administrator CLI output failed");
        }

        Console interruptedProvider = mock(Console.class);
        PrintWriter interruptedWriter = mock(PrintWriter.class);
        when(interruptedProvider.writer()).thenReturn(interruptedWriter);
        doAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return null;
        }).when(interruptedWriter).println("safe");
        assertFixedCauseFree(
                () -> new SystemSecretConsole(interruptedProvider).println("safe"),
                "administrator CLI output failed");
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void systemConsoleSanitizesPasswordReadAndEveryOutputStageFailure() {
        for (Throwable providerFailure : providerFailures()) {
            Console failingRead = mock(Console.class);
            when(failingRead.readPassword("%s", "secret")).thenThrow(providerFailure);
            assertFixedCauseFree(
                    () -> new SystemSecretConsole(failingRead).readSecret("secret"),
                    "administrator CLI input failed");
        }

        for (OutputFailureStage stage : OutputFailureStage.values()) {
            for (Throwable providerFailure : providerFailures()) {
                Console provider = mock(Console.class);
                PrintWriter writer = mock(PrintWriter.class);
                when(provider.writer()).thenReturn(writer);
                switch (stage) {
                    case WRITER -> when(provider.writer()).thenThrow(providerFailure);
                    case PRINTLN -> doThrow(providerFailure).when(writer).println("safe");
                    case FLUSH -> doThrow(providerFailure).when(writer).flush();
                    case CHECK_ERROR -> when(writer.checkError()).thenThrow(providerFailure);
                }
                assertFixedCauseFree(
                        () -> new SystemSecretConsole(provider).println("safe"),
                        "administrator CLI output failed");
            }
        }
    }

    @Test
    void prepareValidatesUsernameAndAlwaysConsumesPassword() {
        for (String invalid : Arrays.asList(
                null, "ab", "a".repeat(65), " admin", "admin ", "adm in", "管理员", "admin+")) {
            UnitFixture fixture = new UnitFixture();
            char[] supplied = password();
            Throwable failure = catchThrowable(() -> fixture.service.prepare(invalid, supplied));
            assertDomain(failure, "ADMIN_USERNAME_INVALID", HttpStatus.UNPROCESSABLE_ENTITY,
                    Map.of("username",
                            "账号必须为 3–64 位 ASCII 字母、数字、点、下划线或连字符"));
            assertWiped(supplied);
            verify(fixture.encoder, never()).encode(any());
        }

        UnitFixture nullPassword = new UnitFixture();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> nullPassword.service.prepare(USERNAME, null))
                .withMessage("password is required");

        UnitFixture alreadyInitialized = new UnitFixture();
        when(alreadyInitialized.admins.count()).thenReturn(1L);
        char[] rejected = password();
        Throwable failure = catchThrowable(
                () -> alreadyInitialized.service.prepare(USERNAME, rejected));
        assertDomain(failure, "ADMIN_ALREADY_INITIALIZED", HttpStatus.CONFLICT, Map.of());
        assertWiped(rejected);
        verifyNoInteractions(alreadyInitialized.policy);

        UnitFixture jdbcFailure = new UnitFixture();
        when(jdbcFailure.admins.count()).thenThrow(new IllegalStateException("database unavailable"));
        char[] jdbcPassword = password();
        assertThatIllegalStateException()
                .isThrownBy(() -> jdbcFailure.service.prepare(USERNAME, jdbcPassword))
                .withMessage("database unavailable");
        assertWiped(jdbcPassword);
    }

    @Test
    void prepareUsesCharSequenceProvidersWipesEarlyAndPerformsNoWriteOrTransaction() {
        UnitFixture fixture = new UnitFixture();
        char[] supplied = password();
        AtomicBoolean policySawCharBuffer = new AtomicBoolean();
        AtomicBoolean encoderSawCharBuffer = new AtomicBoolean();
        doAnswer(invocation -> {
            CharSequence value = invocation.getArgument(0);
            policySawCharBuffer.set(value instanceof CharBuffer);
            assertThat(value.toString()).isEqualTo(PASSWORD);
            return null;
        }).when(fixture.policy).requireStrong(any());
        when(fixture.encoder.encode(any())).thenAnswer(invocation -> {
            CharSequence value = invocation.getArgument(0);
            encoderSawCharBuffer.set(value instanceof CharBuffer);
            assertThat(value.toString()).isEqualTo(PASSWORD);
            return PASSWORD_HASH;
        });
        when(fixture.totp.beginEnrollment(any(), eq(USERNAME))).thenAnswer(invocation -> {
            assertWiped(supplied);
            return totpEnrollment();
        });

        try (AdminBootstrapService.Enrollment enrollment =
                fixture.service.prepare(USERNAME, supplied)) {
            assertThat(enrollment.adminId()).isNotNull();
            assertThat(enrollment.username()).isEqualTo(USERNAME);
            assertThat(enrollment.provisioningUri()).isEqualTo(PROVISIONING_URI);
            assertThat(enrollment.toString())
                    .contains(
                            "passwordHash=<redacted>",
                            "plaintextTotpSecret=<redacted>",
                            "encryptedTotpSecret=<redacted>",
                            "provisioningUri=<redacted>",
                            "plaintextRecoveryCodes=<redacted>",
                            "recoveryCodeHashes=<redacted>")
                    .doesNotContain(
                            PASSWORD_HASH,
                            TOTP_SECRET,
                            PROVISIONING_URI,
                            RECOVERY_CODES.get(0),
                            fixture.recoveryHashes.get(0));
            assertThatIllegalStateException()
                    .isThrownBy(enrollment::takePlaintextRecoveryCodes)
                    .withMessage("bootstrap recovery codes are not available");
        }

        assertWiped(supplied);
        assertThat(policySawCharBuffer).isTrue();
        assertThat(encoderSawCharBuffer).isTrue();
        verify(fixture.generator).generate(10);
        verify(fixture.recoveryService).hashAll(RECOVERY_CODES);
        verify(fixture.admins, never()).insert(any());
        verify(fixture.recoveryRepository, never()).replace(any(), any());
        verifyNoInteractions(fixture.audit);
        assertThat(fixture.transactions.executions()).isZero();
    }

    @Test
    void prepareWipesOnPolicyAndEncoderFailureAndSanitizesEncoderErrors() {
        UnitFixture policyFailure = new UnitFixture();
        doThrow(new DomainException(
                        "PASSWORD_POLICY_VIOLATION",
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        Map.of("password", "generic")))
                .when(policyFailure.policy).requireStrong(any());
        char[] policyPassword = password();
        assertThatThrownBy(() -> policyFailure.service.prepare(USERNAME, policyPassword))
                .isInstanceOf(DomainException.class);
        assertWiped(policyPassword);

        UnitFixture encoderFailure = new UnitFixture();
        when(encoderFailure.encoder.encode(any()))
                .thenThrow(new IllegalStateException("provider leaked password " + PASSWORD));
        char[] encoderPassword = password();
        assertFixedCauseFree(
                () -> encoderFailure.service.prepare(USERNAME, encoderPassword),
                "password hashing failed");
        assertWiped(encoderPassword);

        for (String invalidHash : Arrays.asList(null, "   ", "h".repeat(256))) {
            UnitFixture invalidProvider = new UnitFixture();
            when(invalidProvider.encoder.encode(any())).thenReturn(invalidHash);
            char[] invalidPassword = password();
            assertFixedCauseFree(
                    () -> invalidProvider.service.prepare(USERNAME, invalidPassword),
                    "password provider returned an invalid hash");
            assertWiped(invalidPassword);
        }
    }

    @Test
    void enrollmentRejectsInvalidProviderMaterialAndDefensivelyRetainsEncryptedSecret() {
        UnitFixture nullEnrollment = new UnitFixture();
        when(nullEnrollment.totp.beginEnrollment(any(), anyString())).thenReturn(null);
        char[] first = password();
        assertThatIllegalStateException()
                .isThrownBy(() -> nullEnrollment.service.prepare(USERNAME, first))
                .withMessage("TOTP provider returned an invalid enrollment");
        assertWiped(first);

        UnitFixture invalidUri = new UnitFixture();
        when(invalidUri.totp.beginEnrollment(any(), anyString())).thenReturn(
                new TotpService.Enrollment(TOTP_SECRET, encryptedSecret(), "https://unsafe"));
        char[] second = password();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> invalidUri.service.prepare(USERNAME, second));
        assertWiped(second);

        UnitFixture duplicateCodes = new UnitFixture();
        when(duplicateCodes.generator.generate(10))
                .thenReturn(java.util.Collections.nCopies(10, RECOVERY_CODES.get(0)));
        when(duplicateCodes.recoveryService.hashAll(any()))
                .thenReturn(duplicateCodes.recoveryHashes);
        char[] third = password();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> duplicateCodes.service.prepare(USERNAME, third));
        assertWiped(third);

        UnitFixture fixture = new UnitFixture();
        EncryptedTotpSecret source = encryptedSecret();
        when(fixture.totp.beginEnrollment(any(), anyString()))
                .thenReturn(new TotpService.Enrollment(TOTP_SECRET, source, PROVISIONING_URI));
        try (AdminBootstrapService.Enrollment enrollment =
                fixture.service.prepare(USERNAME, password())) {
            byte[] leakedCopy = source.nonce();
            Arrays.fill(leakedCopy, (byte) 99);
            String rendered = enrollment.toString();
            assertThat(rendered).doesNotContain(Arrays.toString(leakedCopy), TOTP_SECRET);
        }
    }

    @Test
    void completeRejectsNullMalformedAndWrongTotpBeforePersistenceWorkAndWipesInput() {
        UnitFixture fixture = new UnitFixture();
        AdminBootstrapService.Enrollment enrollment =
                fixture.service.prepare(USERNAME, password());
        clearInvocations(
                fixture.admins,
                fixture.recoveryRepository,
                fixture.totp,
                fixture.audit,
                fixture.clock);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> fixture.service.complete(enrollment, null))
                .withMessage("TOTP code is required");

        char[] nullEnrollmentCode = TOTP_CODE.toCharArray();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> fixture.service.complete(null, nullEnrollmentCode))
                .withMessage("enrollment is required");
        assertWiped(nullEnrollmentCode);

        char[] malformed = "12A456".toCharArray();
        Throwable malformedFailure = catchThrowable(
                () -> fixture.service.complete(enrollment, malformed));
        assertDomain(malformedFailure, "INVALID_BOOTSTRAP_TOTP", HttpStatus.UNAUTHORIZED, Map.of());
        assertWiped(malformed);
        verifyNoInteractions(
                fixture.admins,
                fixture.recoveryRepository,
                fixture.totp,
                fixture.audit,
                fixture.clock);
        assertThat(fixture.transactions.executions()).isZero();

        when(fixture.totp.verifyEnrollment(TOTP_SECRET, TOTP_CODE)).thenReturn(false);
        char[] wrong = TOTP_CODE.toCharArray();
        Throwable wrongFailure = catchThrowable(
                () -> fixture.service.complete(enrollment, wrong));
        assertDomain(wrongFailure, "INVALID_BOOTSTRAP_TOTP", HttpStatus.UNAUTHORIZED, Map.of());
        assertWiped(wrong);
        verify(fixture.totp).verifyEnrollment(TOTP_SECRET, TOTP_CODE);
        verifyNoInteractions(
                fixture.admins,
                fixture.recoveryRepository,
                fixture.audit,
                fixture.clock);
        assertThat(fixture.transactions.executions()).isZero();
        enrollment.close();
    }

    @Test
    void completeRejectsAmbientTransactionBeforeCollaboratorsAndPreservesTotpWipe() {
        UnitFixture fixture = new UnitFixture();
        AdminBootstrapService.Enrollment enrollment =
                fixture.service.prepare(USERNAME, password());
        clearInvocations(
                fixture.admins,
                fixture.recoveryRepository,
                fixture.totp,
                fixture.audit,
                fixture.clock);
        char[] code = TOTP_CODE.toCharArray();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThatIllegalStateException()
                    .isThrownBy(() -> fixture.service.complete(enrollment, code))
                    .withMessage("administrator bootstrap requires no ambient transaction");
        });

        assertWiped(code);
        verifyNoInteractions(
                fixture.admins,
                fixture.recoveryRepository,
                fixture.totp,
                fixture.audit,
                fixture.clock);
        assertThat(fixture.transactions.executions()).isZero();
        enrollment.close();
    }

    @Test
    void completeWipesBeforeTransactionAndKeepsDuplicateTranslationInsertOnly() {
        UnitFixture fixture = new UnitFixture();
        char[] code = TOTP_CODE.toCharArray();
        AdminBootstrapService.Enrollment enrollment =
                fixture.service.prepare(USERNAME, password());
        when(fixture.totp.verifyEnrollment(TOTP_SECRET, TOTP_CODE)).thenReturn(true);
        doAnswer(invocation -> {
            assertWiped(code);
            throw new DuplicateKeyException("provider leaked singleton detail");
        }).when(fixture.admins).insert(any());

        Throwable duplicate = catchThrowable(() -> fixture.service.complete(enrollment, code));
        assertDomain(duplicate, "ADMIN_ALREADY_INITIALIZED", HttpStatus.CONFLICT, Map.of());
        assertThat(duplicate).hasNoCause();
        assertWiped(code);
        verify(fixture.recoveryRepository, never()).replace(any(), any());
        verifyNoInteractions(fixture.audit);

        UnitFixture childDuplicate = new UnitFixture();
        AdminBootstrapService.Enrollment childEnrollment =
                childDuplicate.service.prepare(USERNAME, password());
        when(childDuplicate.totp.verifyEnrollment(TOTP_SECRET, TOTP_CODE)).thenReturn(true);
        DuplicateKeyException recoveryFailure =
                new DuplicateKeyException("recovery duplicate must not be translated");
        doThrow(recoveryFailure)
                .when(childDuplicate.recoveryRepository).replace(any(), any());
        char[] secondCode = TOTP_CODE.toCharArray();
        assertThatThrownBy(() -> childDuplicate.service.complete(childEnrollment, secondCode))
                .isSameAs(recoveryFailure);
        assertWiped(secondCode);
        verifyNoInteractions(childDuplicate.audit);

        UnitFixture transactionFailure = new UnitFixture();
        AdminBootstrapService.Enrollment failedEnrollment =
                transactionFailure.service.prepare(USERNAME, password());
        when(transactionFailure.totp.verifyEnrollment(TOTP_SECRET, TOTP_CODE)).thenReturn(true);
        transactionFailure.transactions.failWith(new SyntheticAuditException());
        char[] thirdCode = TOTP_CODE.toCharArray();
        assertThatExceptionOfType(SyntheticAuditException.class)
                .isThrownBy(() -> transactionFailure.service.complete(
                        failedEnrollment, thirdCode));
        assertWiped(thirdCode);

        enrollment.close();
        childEnrollment.close();
        failedEnrollment.close();
    }

    @Test
    void enrollmentCloseIsIdempotentFromEveryStateAndClearsAllSensitiveReferences()
            throws Exception {
        UnitFixture preparedFixture = new UnitFixture();
        AdminBootstrapService.Enrollment prepared =
                preparedFixture.service.prepare(USERNAME, password());
        prepared.close();
        prepared.close();
        assertAllSensitiveReferencesCleared(prepared);
        assertThatIllegalStateException()
                .isThrownBy(prepared::provisioningUri)
                .withMessage("bootstrap enrollment is not prepared");
        assertThatIllegalStateException()
                .isThrownBy(prepared::takePlaintextRecoveryCodes)
                .withMessage("bootstrap recovery codes are not available");
        char[] preparedCode = TOTP_CODE.toCharArray();
        assertThatIllegalStateException()
                .isThrownBy(() -> preparedFixture.service.complete(prepared, preparedCode))
                .withMessage("bootstrap enrollment is not prepared");
        assertWiped(preparedCode);

        UnitFixture committedFixture = new UnitFixture();
        AdminBootstrapService.Enrollment committed =
                committedFixture.service.prepare(USERNAME, password());
        committedFixture.service.complete(committed, TOTP_CODE.toCharArray());
        assertCommittedReferencesReleased(committed);
        committed.close();
        committed.close();
        assertAllSensitiveReferencesCleared(committed);
        assertThatIllegalStateException().isThrownBy(committed::provisioningUri);
        assertThatIllegalStateException().isThrownBy(committed::takePlaintextRecoveryCodes);

        UnitFixture consumedFixture = new UnitFixture();
        AdminBootstrapService.Enrollment consumed =
                consumedFixture.service.prepare(USERNAME, password());
        consumedFixture.service.complete(consumed, TOTP_CODE.toCharArray());
        assertThat(consumed.takePlaintextRecoveryCodes())
                .containsExactlyElementsOf(RECOVERY_CODES);
        assertAllSensitiveReferencesCleared(consumed);
        consumed.close();
        consumed.close();
        assertAllSensitiveReferencesCleared(consumed);
        assertThatIllegalStateException().isThrownBy(consumed::provisioningUri);
        assertThatIllegalStateException().isThrownBy(consumed::takePlaintextRecoveryCodes);
    }

    @Test
    void enrollmentCloseCannotRaceCompletionMaterialMidTransaction() throws Exception {
        UnitFixture fixture = new UnitFixture();
        AdminBootstrapService.Enrollment enrollment =
                fixture.service.prepare(USERNAME, password());
        CountDownLatch verifierEntered = new CountDownLatch(1);
        CountDownLatch releaseVerifier = new CountDownLatch(1);
        when(fixture.totp.verifyEnrollment(TOTP_SECRET, TOTP_CODE)).thenAnswer(invocation -> {
            verifierEntered.countDown();
            await(releaseVerifier, "TOTP verifier release timed out");
            return true;
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> completion = null;
        Future<?> closing = null;
        try {
            completion = executor.submit(
                    () -> fixture.service.complete(enrollment, TOTP_CODE.toCharArray()));
            await(verifierEntered, "TOTP verifier was not entered");
            CountDownLatch closeStarted = new CountDownLatch(1);
            AtomicReference<Thread> closerThread = new AtomicReference<>();
            closing = executor.submit(() -> {
                closerThread.set(Thread.currentThread());
                closeStarted.countDown();
                enrollment.close();
            });
            await(closeStarted, "enrollment close was not scheduled");
            awaitBlocked(closerThread, closing,
                    "enrollment close did not block behind completion");

            releaseVerifier.countDown();
            completion.get(10, SECONDS);
            closing.get(10, SECONDS);
            assertAllSensitiveReferencesCleared(enrollment);
            verify(fixture.admins).insert(any());
            verify(fixture.recoveryRepository).replace(any(), eq(fixture.recoveryHashes));
            verify(fixture.audit).record(any());
        } finally {
            releaseVerifier.countDown();
            for (Future<?> future : Arrays.asList(completion, closing)) {
                if (future != null && !future.isDone()) {
                    future.cancel(true);
                }
            }
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, SECONDS)).isTrue();
            enrollment.close();
        }
    }

    @Test
    void enrollmentValidatesEveryTotpRecoveryAndHashProviderBoundary() {
        for (String invalidSeed : Arrays.asList(null, "", "   ", "s".repeat(257))) {
            UnitFixture fixture = new UnitFixture();
            when(fixture.totp.beginEnrollment(any(), anyString())).thenReturn(
                    new TotpService.Enrollment(
                            invalidSeed, encryptedSecret(), PROVISIONING_URI));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> fixture.service.prepare(USERNAME, password()));
        }
        for (String invalidUri : Arrays.asList(
                null, "", "   ", "https://unsafe", "otpauth://totp/" + "x".repeat(2034))) {
            UnitFixture fixture = new UnitFixture();
            when(fixture.totp.beginEnrollment(any(), anyString())).thenReturn(
                    new TotpService.Enrollment(TOTP_SECRET, encryptedSecret(), invalidUri));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> fixture.service.prepare(USERNAME, password()));
        }
        UnitFixture nullEncrypted = new UnitFixture();
        when(nullEncrypted.totp.beginEnrollment(any(), anyString())).thenReturn(
                new TotpService.Enrollment(TOTP_SECRET, null, PROVISIONING_URI));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> nullEncrypted.service.prepare(USERNAME, password()));

        for (List<String> invalidCodes : invalidRecoveryCodeLists()) {
            UnitFixture fixture = new UnitFixture();
            when(fixture.generator.generate(10)).thenReturn(invalidCodes);
            when(fixture.recoveryService.hashAll(any())).thenReturn(fixture.recoveryHashes);
            assertThatThrownBy(() -> fixture.service.prepare(USERNAME, password()))
                    .isInstanceOfAny(IllegalArgumentException.class, IllegalStateException.class);
        }
        for (List<String> invalidHashes : invalidRecoveryHashLists()) {
            UnitFixture fixture = new UnitFixture();
            when(fixture.recoveryService.hashAll(any())).thenReturn(invalidHashes);
            assertThatThrownBy(() -> fixture.service.prepare(USERNAME, password()))
                    .isInstanceOfAny(IllegalArgumentException.class, IllegalStateException.class);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void prepareDefensivelySnapshotsMutableProviderListsBeforePersistenceAndHandoff() {
        UnitFixture fixture = new UnitFixture();
        List<String> mutableCodes = new ArrayList<>(RECOVERY_CODES);
        List<String> originalHashes = fixture.recoveryHashes;
        List<String> mutableHashes = new ArrayList<>(originalHashes);
        when(fixture.generator.generate(10)).thenReturn(mutableCodes);
        when(fixture.recoveryService.hashAll(any())).thenAnswer(invocation -> {
            List<String> supplied = invocation.getArgument(0);
            assertThat(supplied).containsExactlyElementsOf(RECOVERY_CODES);
            assertThat(supplied).isNotSameAs(mutableCodes);
            return mutableHashes;
        });
        AdminBootstrapService.Enrollment enrollment =
                fixture.service.prepare(USERNAME, password());
        mutableCodes.clear();
        mutableCodes.add("AAAA-AAAA-AAAA");
        mutableHashes.clear();
        mutableHashes.add("mutated-hash");

        try (enrollment) {
            fixture.service.complete(enrollment, TOTP_CODE.toCharArray());
            ArgumentCaptor<List<String>> hashesCaptor = ArgumentCaptor.forClass(List.class);
            verify(fixture.recoveryRepository).replace(any(), hashesCaptor.capture());
            assertThat(hashesCaptor.getValue()).containsExactlyElementsOf(originalHashes);
            assertThat(enrollment.takePlaintextRecoveryCodes())
                    .containsExactlyElementsOf(RECOVERY_CODES);
        }
    }

    @Test
    void committedEnrollmentReplayFailsLocallyWithoutAnyCollaboratorOrTransactionWork() {
        UnitFixture fixture = new UnitFixture();
        AdminBootstrapService.Enrollment enrollment =
                fixture.service.prepare(USERNAME, password());
        fixture.service.complete(enrollment, TOTP_CODE.toCharArray());
        int transactionExecutions = fixture.transactions.executions();
        clearInvocations(
                fixture.admins,
                fixture.recoveryRepository,
                fixture.totp,
                fixture.audit,
                fixture.clock);

        char[] replay = TOTP_CODE.toCharArray();
        assertThatIllegalStateException()
                .isThrownBy(() -> fixture.service.complete(enrollment, replay))
                .withMessage("bootstrap enrollment is not prepared");
        assertWiped(replay);
        verifyNoInteractions(
                fixture.admins,
                fixture.recoveryRepository,
                fixture.totp,
                fixture.audit,
                fixture.clock);
        assertThat(fixture.transactions.executions()).isEqualTo(transactionExecutions);
        enrollment.close();
    }

    @Test
    void committedBootstrapPersistsExactAdminRecoverySetAndCapturedAudit() {
        audit.clear();
        char[] suppliedPassword = password();
        AdminBootstrapService.Enrollment enrollment =
                bootstrap.prepare(USERNAME, suppliedPassword);
        UUID adminId = enrollment.adminId();
        try (enrollment) {
            assertWiped(suppliedPassword);
            assertThat(countAdmin(adminId)).isZero();
            assertThat(countRecovery(adminId)).isZero();
            assertThat(audit.commands()).isEmpty();

            char[] code = TOTP_CODE.toCharArray();
            bootstrap.complete(enrollment, code);
            assertWiped(code);

            AdminDatabaseRow admin = jdbc.sql("""
                            select username, password_hash, status, totp_key_version,
                                   totp_nonce, totp_ciphertext, last_login_at, version,
                                   created_at, updated_at
                            from portfolio.admin_user
                            where id=:adminId
                            """)
                    .param("adminId", adminId)
                    .query((resultSet, rowNumber) -> new AdminDatabaseRow(
                            resultSet.getString("username"),
                            resultSet.getString("password_hash"),
                            resultSet.getString("status"),
                            resultSet.getInt("totp_key_version"),
                            resultSet.getBytes("totp_nonce"),
                            resultSet.getBytes("totp_ciphertext"),
                            resultSet.getObject("last_login_at"),
                            resultSet.getLong("version"),
                            resultSet.getObject("created_at", java.time.OffsetDateTime.class)
                                    .toInstant(),
                            resultSet.getObject("updated_at", java.time.OffsetDateTime.class)
                                    .toInstant()))
                    .single();
            assertThat(admin.username()).isEqualTo(USERNAME);
            assertThat(admin.passwordHash())
                    .isNotEqualTo(PASSWORD)
                    .doesNotContain(PASSWORD);
            assertThat(passwordEncoder.matches(PASSWORD, admin.passwordHash())).isTrue();
            assertThat(admin.status()).isEqualTo("ACTIVE");
            assertThat(admin.keyVersion()).isOne();
            assertThat(admin.nonce()).hasSize(12);
            assertThat(admin.ciphertext())
                    .hasSizeGreaterThanOrEqualTo(17)
                    .doesNotContain(TOTP_SECRET.getBytes(StandardCharsets.US_ASCII));
            assertThat(admin.lastLoginAt()).isNull();
            assertThat(admin.version()).isZero();
            assertThat(admin.createdAt()).isEqualTo(NOW);
            assertThat(admin.updatedAt()).isEqualTo(NOW);

            List<String> hashes = jdbc.sql("""
                            select code_hash from portfolio.totp_recovery_code
                            where admin_id=:adminId
                            order by created_at, id
                            """)
                    .param("adminId", adminId)
                    .query(String.class)
                    .list();
            assertThat(hashes).hasSize(10).doesNotHaveDuplicates();
            for (int index = 0; index < RECOVERY_CODES.size(); index++) {
                int matchingIndex = index;
                assertThat(hashes).noneMatch(
                        hash -> hash.equals(RECOVERY_CODES.get(matchingIndex)));
                assertThat(hashes).anyMatch(
                        hash -> passwordEncoder.matches(RECOVERY_CODES.get(matchingIndex), hash));
            }

            assertThat(audit.commands()).singleElement().satisfies(command -> {
                assertThat(command.actorAdminId()).isEqualTo(adminId);
                assertThat(command.action()).isEqualTo("ADMIN_BOOTSTRAPPED");
                assertThat(command.targetType()).isEqualTo("ADMIN");
                assertThat(command.targetId()).isEqualTo(adminId.toString());
                assertThat(command.outcome()).isEqualTo(AuditOutcome.SUCCESS);
                assertThat(command.metadata()).containsExactlyEntriesOf(
                        Map.of("channel", "LOCAL_CLI"));
            });

            assertThatIllegalStateException()
                    .isThrownBy(enrollment::provisioningUri)
                    .withMessage("bootstrap enrollment is not prepared");
            List<String> plaintext = enrollment.takePlaintextRecoveryCodes();
            assertThat(plaintext).containsExactlyElementsOf(RECOVERY_CODES);
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> plaintext.add("ABCD-EFGH-JKLN"));
            assertThatIllegalStateException()
                    .isThrownBy(enrollment::takePlaintextRecoveryCodes)
                    .withMessage("bootstrap recovery codes are not available");
            assertThat(enrollment.toString())
                    .doesNotContain(PASSWORD, PASSWORD_HASH, TOTP_SECRET, PROVISIONING_URI,
                            RECOVERY_CODES.get(0), hashes.get(0));
        } finally {
            cleanupAdmin(adminId);
        }
    }

    @Test
    void committedEnrollmentCannotReplayAndPreparedCompetitorLosesAtSingletonBarrier() {
        audit.clear();
        AdminBootstrapService.Enrollment winner =
                bootstrap.prepare(USERNAME, password());
        AdminBootstrapService.Enrollment loser =
                bootstrap.prepare("Second.Admin", password());
        UUID winnerId = winner.adminId();
        UUID loserId = loser.adminId();
        try (winner; loser) {
            bootstrap.complete(winner, TOTP_CODE.toCharArray());
            List<RecoveryDatabaseRow> recoveryBeforeReplay = recoverySnapshot(winnerId);

            char[] replayCode = TOTP_CODE.toCharArray();
            assertThatIllegalStateException()
                    .isThrownBy(() -> bootstrap.complete(winner, replayCode))
                    .withMessage("bootstrap enrollment is not prepared");
            assertWiped(replayCode);

            char[] losingCode = TOTP_CODE.toCharArray();
            Throwable losingFailure = catchThrowable(
                    () -> bootstrap.complete(loser, losingCode));
            assertDomain(losingFailure,
                    "ADMIN_ALREADY_INITIALIZED", HttpStatus.CONFLICT, Map.of());
            assertWiped(losingCode);

            char[] laterPassword = password();
            Throwable laterFailure = catchThrowable(
                    () -> bootstrap.prepare("Later.Admin", laterPassword));
            assertDomain(laterFailure,
                    "ADMIN_ALREADY_INITIALIZED", HttpStatus.CONFLICT, Map.of());
            assertWiped(laterPassword);

            assertThat(countAdmin(winnerId)).isOne();
            assertThat(countAdmin(loserId)).isZero();
            assertThat(countRecovery(winnerId)).isEqualTo(10);
            assertThat(countRecovery(loserId)).isZero();
            assertThat(audit.commands()).hasSize(1);
            assertThat(recoverySnapshot(winnerId)).isEqualTo(recoveryBeforeReplay);
            assertThat(recoveryBeforeReplay)
                    .allSatisfy(row -> assertThat(row.usedAt()).isNull());
        } finally {
            try {
                cleanupAdmin(loserId);
            } finally {
                cleanupAdmin(winnerId);
            }
        }
    }

    @Test
    void auditFailureRollsBackAdministratorAndRecoveryRows() {
        AuditService failure = command -> {
            throw new SyntheticAuditException();
        };
        AdminBootstrapService failing = integrationService(totp, failure);
        AdminBootstrapService.Enrollment enrollment =
                failing.prepare("Rollback.Admin", password());
        UUID adminId = enrollment.adminId();
        try (enrollment) {
            char[] code = TOTP_CODE.toCharArray();
            assertThatExceptionOfType(SyntheticAuditException.class)
                    .isThrownBy(() -> failing.complete(enrollment, code));
            assertWiped(code);
            assertThat(countAdmin(adminId)).isZero();
            assertThat(countRecovery(adminId)).isZero();
            assertThat(enrollment.provisioningUri()).isEqualTo(PROVISIONING_URI);
        } finally {
            cleanupAdmin(adminId);
        }
    }

    @Test
    void realAuditDelegateIsVisibleInsideOwnedTransactionThenEverythingRollsBack() {
        AtomicBoolean observed = new AtomicBoolean();
        AuditService delegateThenFail = command -> {
            realAudit.record(command);
            Long exactRows = jdbc.sql("""
                            select count(*) from portfolio.audit_log
                            where actor_admin_id=:adminId
                              and action='ADMIN_BOOTSTRAPPED'
                              and target_type='ADMIN'
                              and target_id=:targetId
                              and outcome='SUCCESS'
                              and metadata->>'channel'='LOCAL_CLI'
                            """)
                    .param("adminId", command.actorAdminId())
                    .param("targetId", command.targetId())
                    .query(Long.class)
                    .single();
            observed.set(exactRows == 1L);
            throw new SyntheticAuditException();
        };
        AdminBootstrapService failing = integrationService(totp, delegateThenFail);
        AdminBootstrapService.Enrollment enrollment =
                failing.prepare("RealAudit.Admin", password());
        UUID adminId = enrollment.adminId();
        try (enrollment) {
            assertThatExceptionOfType(SyntheticAuditException.class)
                    .isThrownBy(() -> failing.complete(enrollment, TOTP_CODE.toCharArray()));
            assertThat(observed).isTrue();
            assertThat(countAdmin(adminId)).isZero();
            assertThat(countRecovery(adminId)).isZero();
            assertThat(countAudit(adminId)).isZero();
        } finally {
            cleanupAdmin(adminId);
        }
    }

    @Test
    void concurrentPreparedEnrollmentsYieldOneCommitOneConflictAndOneAudit() throws Exception {
        audit.clear();
        TotpService barrierTotp = mock(TotpService.class);
        when(barrierTotp.beginEnrollment(any(), anyString()))
                .thenReturn(totpEnrollment());
        CyclicBarrier barrier = new CyclicBarrier(2);
        when(barrierTotp.verifyEnrollment(TOTP_SECRET, TOTP_CODE)).thenAnswer(invocation -> {
            try {
                barrier.await(10, TimeUnit.SECONDS);
            } catch (Exception failure) {
                throw new IllegalStateException("test TOTP barrier failed", failure);
            }
            return true;
        });
        AdminBootstrapService concurrent = integrationService(barrierTotp, audit);
        AdminBootstrapService.Enrollment first =
                concurrent.prepare("Concurrent.One", password());
        AdminBootstrapService.Enrollment second =
                concurrent.prepare("Concurrent.Two", password());
        List<AdminBootstrapService.Enrollment> enrollments = List.of(first, second);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<Throwable>> futures = new ArrayList<>();
        try {
            for (AdminBootstrapService.Enrollment enrollment : enrollments) {
                futures.add(executor.submit(() -> catchThrowable(
                        () -> concurrent.complete(enrollment, TOTP_CODE.toCharArray()))));
            }
            List<Throwable> outcomes = Arrays.asList(
                    futures.get(0).get(30, SECONDS),
                    futures.get(1).get(30, SECONDS));
            assertThat(outcomes.stream().filter(java.util.Objects::isNull).count()).isOne();
            assertThat(outcomes.stream().filter(DomainException.class::isInstance).count()).isOne();
            DomainException conflict = outcomes.stream()
                    .filter(DomainException.class::isInstance)
                    .map(DomainException.class::cast)
                    .findFirst()
                    .orElseThrow();
            assertDomain(conflict,
                    "ADMIN_ALREADY_INITIALIZED", HttpStatus.CONFLICT, Map.of());

            long adminRows = countAdmin(first.adminId()) + countAdmin(second.adminId());
            long recoveryRows = countRecovery(first.adminId()) + countRecovery(second.adminId());
            assertThat(adminRows).isOne();
            assertThat(recoveryRows).isEqualTo(10);
            assertThat(audit.commands()).hasSize(1);
        } finally {
            for (Future<?> future : futures) {
                if (!future.isDone()) {
                    future.cancel(true);
                }
            }
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, SECONDS)).isTrue();
            first.close();
            second.close();
            try {
                cleanupAdmin(second.adminId());
            } finally {
                cleanupAdmin(first.adminId());
            }
        }
    }

    @Test
    void runnerIsNoOpWithoutCommandEvenWhenUnrelatedOptionsExist() throws Exception {
        SecretConsole console = mock(SecretConsole.class);
        AdminBootstrapService service = mock(AdminBootstrapService.class);
        AdminCliRunner runner = runner(console, service);

        runner.run(arguments());
        runner.run(arguments("--spring.datasource.password=must-not-activate", "positional"));

        verifyNoInteractions(console, service);
    }

    @Test
    void runnerRejectsMalformedDuplicateUnknownAndAdditionalArgumentsBeforeInteraction()
            throws Exception {
        List<ArgumentFailure> failures = List.of(
                new ArgumentFailure(
                        new String[] {"--portfolio.cli.command"},
                        "portfolio CLI command must be supplied exactly once"),
                new ArgumentFailure(
                        new String[] {"--portfolio.cli.command="},
                        "portfolio CLI command must be supplied exactly once"),
                new ArgumentFailure(
                        new String[] {"--portfolio.cli.command=   "},
                        "portfolio CLI command must be supplied exactly once"),
                new ArgumentFailure(
                        new String[] {
                            "--portfolio.cli.command=admin-bootstrap",
                            "--portfolio.cli.command=admin-bootstrap"
                        },
                        "portfolio CLI command must be supplied exactly once"),
                new ArgumentFailure(
                        new String[] {
                            "--portfolio.cli.command",
                            "--portfolio.cli.command=admin-bootstrap"
                        },
                        "portfolio CLI command must be supplied exactly once"),
                new ArgumentFailure(
                        new String[] {"--portfolio.cli.command=unknown"},
                        "unknown portfolio CLI command"),
                new ArgumentFailure(
                        new String[] {"--portfolio.cli.command=admin-bootstrap", "position"},
                        "portfolio CLI accepts only its command option"),
                new ArgumentFailure(
                        new String[] {
                            "--portfolio.cli.command=admin-bootstrap", "--password=secret"
                        },
                        "portfolio CLI accepts only its command option"),
                new ArgumentFailure(
                        new String[] {
                            "--portfolio.cli.command=admin-bootstrap",
                            "--spring.datasource.password=secret"
                        },
                        "portfolio CLI accepts only its command option"),
                new ArgumentFailure(
                        new String[] {
                            "--portfolio.cli.command=admin-bootstrap", "--portfolio.cli.extra=x"
                        },
                        "portfolio CLI accepts only its command option"));

        for (ArgumentFailure failure : failures) {
            SecretConsole console = mock(SecretConsole.class);
            AdminBootstrapService service = mock(AdminBootstrapService.class);
            AdminCliRunner runner = runner(console, service);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> runner.run(arguments(failure.arguments())))
                    .withMessage(failure.message());
            verifyNoInteractions(console, service);
        }
    }

    @Test
    void runnerSuccessfulInteractionHasExactOrderAndWipesEveryArray() throws Exception {
        SecretConsole console = mock(SecretConsole.class);
        AdminBootstrapService service = mock(AdminBootstrapService.class);
        AdminBootstrapService.Enrollment enrollment =
                mock(AdminBootstrapService.Enrollment.class);
        char[] password = password();
        char[] confirmation = password();
        char[] code = TOTP_CODE.toCharArray();
        when(console.readLine("Administrator username: ")).thenReturn("  " + USERNAME + "  ");
        when(console.readSecret("New password: ")).thenReturn(password);
        when(console.readSecret("Repeat password: ")).thenReturn(confirmation);
        when(console.readSecret("Current six-digit TOTP: ")).thenReturn(code);
        when(service.prepare(eq(USERNAME), same(password))).thenAnswer(invocation -> {
            assertWiped(confirmation);
            return enrollment;
        });
        when(enrollment.provisioningUri()).thenReturn(PROVISIONING_URI);
        when(enrollment.takePlaintextRecoveryCodes()).thenReturn(RECOVERY_CODES);
        AtomicBoolean completed = new AtomicBoolean();
        doAnswer(invocation -> {
            Arrays.fill(code, '\0');
            assertWiped(code);
            completed.set(true);
            return null;
        }).when(service).complete(same(enrollment), same(code));
        doAnswer(invocation -> {
            assertThat(completed).isTrue();
            return null;
        }).when(console).println(
                "Store these one-time recovery codes offline; they will not be shown again:");

        runner(console, service).run(
                arguments("--portfolio.cli.command=admin-bootstrap"));

        assertWiped(password);
        assertWiped(confirmation);
        assertWiped(code);
        InOrder order = inOrder(console, service, enrollment);
        order.verify(console).readLine("Administrator username: ");
        order.verify(console).readSecret("New password: ");
        order.verify(console).readSecret("Repeat password: ");
        order.verify(service).prepare(eq(USERNAME), same(password));
        order.verify(console).println("Add this provisioning URI to the authenticator:");
        order.verify(enrollment).provisioningUri();
        order.verify(console).println(PROVISIONING_URI);
        order.verify(console).readSecret("Current six-digit TOTP: ");
        order.verify(service).complete(same(enrollment), same(code));
        order.verify(console).println(
                "Store these one-time recovery codes offline; they will not be shown again:");
        order.verify(enrollment).takePlaintextRecoveryCodes();
        for (String recoveryCode : RECOVERY_CODES) {
            order.verify(console).println(recoveryCode);
        }
        order.verify(enrollment).close();
        order.verifyNoMoreInteractions();
    }

    @Test
    void runnerRecoveryRequiresExactConfirmationAndHandsOffSecretsOnlyAfterCommit()
            throws Exception {
        SecretConsole refusedConsole = mock(SecretConsole.class);
        AdminBootstrapService bootstrap = mock(AdminBootstrapService.class);
        AdminRecoveryService recovery = mock(AdminRecoveryService.class);
        TotpKeyReencryptionService reencrypt = mock(TotpKeyReencryptionService.class);
        when(refusedConsole.readLine(RECOVERY_CONFIRM_PROMPT)).thenReturn(" RECOVER ADMIN ");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AdminCliRunner(
                                refusedConsole, bootstrap, recovery, reencrypt)
                        .run(arguments("--portfolio.cli.command=admin-recover")))
                .withMessage("administrator recovery was not confirmed")
                .withNoCause();
        verifyNoInteractions(bootstrap, recovery, reencrypt);
        verify(refusedConsole, never()).println(anyString());

        SecretConsole console = mock(SecretConsole.class);
        AdminRecoveryService.Enrollment enrollment =
                mock(AdminRecoveryService.Enrollment.class);
        char[] password = password();
        char[] repeated = password();
        char[] code = TOTP_CODE.toCharArray();
        when(console.readLine(RECOVERY_CONFIRM_PROMPT)).thenReturn("RECOVER ADMIN");
        when(console.readSecret("New password: ")).thenReturn(password);
        when(console.readSecret("Repeat password: ")).thenReturn(repeated);
        when(recovery.prepare(same(password))).thenAnswer(invocation -> {
            assertWiped(repeated);
            return enrollment;
        });
        when(enrollment.backupSha256()).thenReturn(RECOVERY_CHECKSUM);
        when(enrollment.provisioningUri()).thenReturn(PROVISIONING_URI);
        when(console.readSecret(RECOVERY_TOTP_PROMPT)).thenReturn(code);
        when(enrollment.takePlaintextRecoveryCodes()).thenReturn(RECOVERY_CODES);

        new AdminCliRunner(console, bootstrap, recovery, reencrypt)
                .run(arguments("--portfolio.cli.command=admin-recover"));

        assertWiped(password);
        assertWiped(repeated);
        assertWiped(code);
        InOrder order = inOrder(console, recovery, enrollment);
        order.verify(console).readLine(RECOVERY_CONFIRM_PROMPT);
        order.verify(console).readSecret("New password: ");
        order.verify(console).readSecret("Repeat password: ");
        order.verify(recovery).prepare(same(password));
        order.verify(enrollment).backupSha256();
        order.verify(console).println(
                "Database restore point SHA-256: " + RECOVERY_CHECKSUM);
        order.verify(console).println(RECOVERY_URI_HEADING);
        order.verify(enrollment).provisioningUri();
        order.verify(console).println(PROVISIONING_URI);
        order.verify(console).readSecret(RECOVERY_TOTP_PROMPT);
        order.verify(recovery).complete(same(enrollment), same(code));
        order.verify(console).println(RECOVERY_CODES_HEADING);
        order.verify(enrollment).takePlaintextRecoveryCodes();
        for (String recoveryCode : RECOVERY_CODES) {
            order.verify(console).println(recoveryCode);
        }
        order.verify(enrollment).close();
        order.verifyNoMoreInteractions();
        verifyNoInteractions(bootstrap, reencrypt);
    }

    @Test
    void runnerRecoveryCancellationAndPasswordFailuresWipeWithoutPreparingOrWriting()
            throws Exception {
        SecretConsole confirmationCancelled = mock(SecretConsole.class);
        AdminBootstrapService bootstrap = mock(AdminBootstrapService.class);
        AdminRecoveryService recovery = mock(AdminRecoveryService.class);
        TotpKeyReencryptionService reencrypt = mock(TotpKeyReencryptionService.class);
        when(confirmationCancelled.readLine(RECOVERY_CONFIRM_PROMPT)).thenReturn(null);

        assertFixedCauseFree(
                () -> recoveryRunner(
                                confirmationCancelled, bootstrap, recovery, reencrypt)
                        .run(arguments("--portfolio.cli.command=admin-recover")),
                "administrator CLI input was cancelled");
        verifyNoInteractions(bootstrap, recovery, reencrypt);
        verify(confirmationCancelled, never()).println(anyString());

        SecretConsole passwordCancelled = mock(SecretConsole.class);
        when(passwordCancelled.readLine(RECOVERY_CONFIRM_PROMPT)).thenReturn("RECOVER ADMIN");
        when(passwordCancelled.readSecret("New password: ")).thenReturn(null);

        assertFixedCauseFree(
                () -> recoveryRunner(passwordCancelled, bootstrap, recovery, reencrypt)
                        .run(arguments("--portfolio.cli.command=admin-recover")),
                "administrator CLI input was cancelled");
        verifyNoInteractions(bootstrap, recovery, reencrypt);
        verify(passwordCancelled, never()).println(anyString());

        for (RecoveryPasswordFailure stage : RecoveryPasswordFailure.values()) {
            SecretConsole console = mock(SecretConsole.class);
            AdminRecoveryService stageRecovery = mock(AdminRecoveryService.class);
            char[] first = password();
            char[] second = stage == RecoveryPasswordFailure.MISMATCH
                    ? "PortfolioDifferent!2026".toCharArray()
                    : password();
            RuntimeException inputFailure = new IllegalStateException(
                    "synthetic repeated-password input failure");
            when(console.readLine(RECOVERY_CONFIRM_PROMPT)).thenReturn("RECOVER ADMIN");
            when(console.readSecret("New password: ")).thenReturn(first);
            switch (stage) {
                case CANCELLED -> when(console.readSecret("Repeat password: ")).thenReturn(null);
                case PROVIDER_FAILURE -> when(console.readSecret("Repeat password: "))
                        .thenThrow(inputFailure);
                case MISMATCH -> when(console.readSecret("Repeat password: ")).thenReturn(second);
            }

            Throwable failure = catchThrowable(() -> recoveryRunner(
                            console, bootstrap, stageRecovery, reencrypt)
                    .run(arguments("--portfolio.cli.command=admin-recover")));

            if (stage == RecoveryPasswordFailure.CANCELLED) {
                assertThat(failure)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("administrator CLI input was cancelled")
                        .hasNoCause();
            } else if (stage == RecoveryPasswordFailure.PROVIDER_FAILURE) {
                assertThat(failure).isSameAs(inputFailure);
            } else {
                assertThat(failure)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("passwords differ")
                        .hasNoCause();
                assertWiped(second);
            }
            assertWiped(first);
            verifyNoInteractions(stageRecovery);
            verify(console, never()).println(anyString());
        }
        verifyNoInteractions(bootstrap, reencrypt);

        RecoveryCliFixture prepareFailure = recoveryCliFixture();
        RuntimeException providerFailure = new IllegalStateException(
                "synthetic recovery prepare failure");
        when(prepareFailure.recovery().prepare(same(prepareFailure.password())))
                .thenThrow(providerFailure);

        assertThatThrownBy(prepareFailure::run).isSameAs(providerFailure);
        assertWiped(prepareFailure.password());
        assertWiped(prepareFailure.repeated());
        verifyNoInteractions(prepareFailure.enrollment());
        verify(prepareFailure.console(), never()).println(anyString());
        verifyNoInteractions(prepareFailure.bootstrap(), prepareFailure.reencrypt());
    }

    @Test
    void runnerRecoveryPreCommitFailuresAlwaysCloseWithoutCodeAccess() {
        for (RecoveryPreCommitFailure stage : RecoveryPreCommitFailure.values()) {
            RecoveryCliFixture fixture = recoveryCliFixture();
            RuntimeException failure = new IllegalStateException(
                    "synthetic recovery pre-commit failure " + stage);
            switch (stage) {
                case CHECKSUM_OUTPUT -> doThrow(failure)
                        .when(fixture.console())
                        .println("Database restore point SHA-256: " + RECOVERY_CHECKSUM);
                case URI_HEADING_OUTPUT -> doThrow(failure)
                        .when(fixture.console())
                        .println(RECOVERY_URI_HEADING);
                case URI_OUTPUT -> doThrow(failure)
                        .when(fixture.console())
                        .println(PROVISIONING_URI);
                case TOTP_CANCELLED -> when(fixture.console().readSecret(RECOVERY_TOTP_PROMPT))
                        .thenReturn(null);
                case TOTP_PROVIDER_FAILURE -> when(
                                fixture.console().readSecret(RECOVERY_TOTP_PROMPT))
                        .thenThrow(failure);
                case COMPLETION -> doThrow(failure)
                        .when(fixture.recovery())
                        .complete(same(fixture.enrollment()), same(fixture.code()));
            }

            Throwable observed = catchThrowable(fixture::run);

            if (stage == RecoveryPreCommitFailure.TOTP_CANCELLED) {
                assertThat(observed)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("administrator CLI input was cancelled")
                        .hasNoCause();
            } else {
                assertThat(observed).isSameAs(failure);
            }
            assertWiped(fixture.password());
            assertWiped(fixture.repeated());
            if (stage == RecoveryPreCommitFailure.COMPLETION) {
                assertWiped(fixture.code());
                verify(fixture.recovery())
                        .complete(same(fixture.enrollment()), same(fixture.code()));
            } else {
                verify(fixture.recovery(), never()).complete(any(), any());
            }
            verify(fixture.enrollment()).close();
            verify(fixture.enrollment(), never()).takePlaintextRecoveryCodes();
            verifyNoInteractions(fixture.bootstrap(), fixture.reencrypt());
        }
    }

    @Test
    void runnerRecoveryPostCommitOutputFailuresCloseWithStrictNonReplayablePrefix() {
        RecoveryCliFixture headingFailure = recoveryCliFixture();
        RuntimeException headingOutput = new IllegalStateException(
                "synthetic recovery-code heading output failure");
        doThrow(headingOutput)
                .when(headingFailure.console())
                .println(RECOVERY_CODES_HEADING);

        assertThatThrownBy(headingFailure::run).isSameAs(headingOutput);

        assertWiped(headingFailure.password());
        assertWiped(headingFailure.repeated());
        assertWiped(headingFailure.code());
        InOrder headingOrder = inOrder(
                headingFailure.recovery(), headingFailure.console(), headingFailure.enrollment());
        headingOrder.verify(headingFailure.recovery())
                .complete(same(headingFailure.enrollment()), same(headingFailure.code()));
        headingOrder.verify(headingFailure.console()).println(RECOVERY_CODES_HEADING);
        headingOrder.verify(headingFailure.enrollment()).close();
        verify(headingFailure.enrollment(), never()).takePlaintextRecoveryCodes();
        verifyNoInteractions(headingFailure.bootstrap(), headingFailure.reencrypt());

        RecoveryCliFixture codeFailure = recoveryCliFixture();
        RuntimeException thirdCodeOutput = new IllegalStateException(
                "synthetic third recovery-code output failure");
        doThrow(thirdCodeOutput)
                .when(codeFailure.console())
                .println(RECOVERY_CODES.get(2));

        assertThatThrownBy(codeFailure::run).isSameAs(thirdCodeOutput);

        assertWiped(codeFailure.password());
        assertWiped(codeFailure.repeated());
        assertWiped(codeFailure.code());
        InOrder codeOrder = inOrder(
                codeFailure.recovery(), codeFailure.console(), codeFailure.enrollment());
        codeOrder.verify(codeFailure.recovery())
                .complete(same(codeFailure.enrollment()), same(codeFailure.code()));
        codeOrder.verify(codeFailure.console()).println(RECOVERY_CODES_HEADING);
        codeOrder.verify(codeFailure.enrollment()).takePlaintextRecoveryCodes();
        codeOrder.verify(codeFailure.console()).println(RECOVERY_CODES.get(0));
        codeOrder.verify(codeFailure.console()).println(RECOVERY_CODES.get(1));
        codeOrder.verify(codeFailure.console()).println(RECOVERY_CODES.get(2));
        codeOrder.verify(codeFailure.enrollment()).close();
        verify(codeFailure.console(), never()).println(RECOVERY_CODES.get(3));
        verify(codeFailure.enrollment()).backupSha256();
        verify(codeFailure.enrollment()).provisioningUri();
        verifyNoMoreInteractions(codeFailure.enrollment());
        verifyNoInteractions(codeFailure.bootstrap(), codeFailure.reencrypt());
    }

    @Test
    void runnerWipesFirstSecretWhenConfirmationCancelsAndRejectsMismatchBeforeServiceOutput()
            throws Exception {
        SecretConsole cancellationConsole = mock(SecretConsole.class);
        AdminBootstrapService cancellationService = mock(AdminBootstrapService.class);
        char[] first = password();
        when(cancellationConsole.readLine("Administrator username: ")).thenReturn(USERNAME);
        when(cancellationConsole.readSecret("New password: ")).thenReturn(first);
        when(cancellationConsole.readSecret("Repeat password: ")).thenReturn(null);

        assertFixedCauseFree(
                () -> runner(cancellationConsole, cancellationService).run(
                        arguments("--portfolio.cli.command=admin-bootstrap")),
                "administrator CLI input was cancelled");
        assertWiped(first);
        verifyNoInteractions(cancellationService);
        verify(cancellationConsole, never()).println(anyString());

        SecretConsole mismatchConsole = mock(SecretConsole.class);
        AdminBootstrapService mismatchService = mock(AdminBootstrapService.class);
        char[] mismatchFirst = password();
        char[] mismatchSecond = "DifferentStrong!2026".toCharArray();
        when(mismatchConsole.readLine("Administrator username: ")).thenReturn(USERNAME);
        when(mismatchConsole.readSecret("New password: ")).thenReturn(mismatchFirst);
        when(mismatchConsole.readSecret("Repeat password: ")).thenReturn(mismatchSecond);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> runner(mismatchConsole, mismatchService).run(
                        arguments("--portfolio.cli.command=admin-bootstrap")))
                .withMessage("passwords differ");
        assertWiped(mismatchFirst);
        assertWiped(mismatchSecond);
        verifyNoInteractions(mismatchService);
        verify(mismatchConsole, never()).println(anyString());
    }

    @Test
    void runnerPreCommitFailuresCloseEnrollmentAndNeverPrintRecoveryCodes() throws Exception {
        for (PreCommitFailure stage : PreCommitFailure.values()) {
            SecretConsole console = mock(SecretConsole.class);
            AdminBootstrapService service = mock(AdminBootstrapService.class);
            AdminBootstrapService.Enrollment enrollment =
                    mock(AdminBootstrapService.Enrollment.class);
            char[] password = password();
            char[] confirmation = password();
            char[] code = TOTP_CODE.toCharArray();
            when(console.readLine("Administrator username: ")).thenReturn(USERNAME);
            when(console.readSecret("New password: ")).thenReturn(password);
            when(console.readSecret("Repeat password: ")).thenReturn(confirmation);
            when(service.prepare(USERNAME, password)).thenReturn(enrollment);
            when(enrollment.provisioningUri()).thenReturn(PROVISIONING_URI);

            RuntimeException failure = new SyntheticAuditException();
            if (stage == PreCommitFailure.PROVISIONING_OUTPUT) {
                doThrow(failure).when(console).println(PROVISIONING_URI);
            } else {
                when(console.readSecret("Current six-digit TOTP: ")).thenReturn(code);
                doThrow(failure).when(service).complete(enrollment, code);
            }

            assertThatThrownBy(() -> runner(console, service).run(
                            arguments("--portfolio.cli.command=admin-bootstrap")))
                    .isSameAs(failure);
            assertWiped(password);
            assertWiped(confirmation);
            if (stage == PreCommitFailure.COMPLETION) {
                assertWiped(code);
            }
            verify(enrollment).close();
            verify(enrollment, never()).takePlaintextRecoveryCodes();
            verify(console, never()).println(
                    "Store these one-time recovery codes offline; they will not be shown again:");
            for (String recoveryCode : RECOVERY_CODES) {
                verify(console, never()).println(recoveryCode);
            }
        }
    }

    @Test
    void runnerPostCommitOutputFailureExposesOnlyPrefixAndNeverReplaysCodes() throws Exception {
        SecretConsole console = mock(SecretConsole.class);
        AdminBootstrapService service = mock(AdminBootstrapService.class);
        AdminBootstrapService.Enrollment enrollment =
                mock(AdminBootstrapService.Enrollment.class);
        char[] password = password();
        char[] confirmation = password();
        char[] code = TOTP_CODE.toCharArray();
        when(console.readLine("Administrator username: ")).thenReturn(USERNAME);
        when(console.readSecret("New password: ")).thenReturn(password);
        when(console.readSecret("Repeat password: ")).thenReturn(confirmation);
        when(console.readSecret("Current six-digit TOTP: ")).thenReturn(code);
        when(service.prepare(USERNAME, password)).thenReturn(enrollment);
        when(enrollment.provisioningUri()).thenReturn(PROVISIONING_URI);
        when(enrollment.takePlaintextRecoveryCodes()).thenReturn(RECOVERY_CODES);
        IllegalStateException output = new IllegalStateException(
                "administrator CLI output failed");
        doThrow(output).when(console).println(RECOVERY_CODES.get(2));

        assertThatThrownBy(() -> runner(console, service).run(
                        arguments("--portfolio.cli.command=admin-bootstrap")))
                .isSameAs(output);

        verify(service).complete(enrollment, code);
        verify(enrollment).takePlaintextRecoveryCodes();
        verify(console).println(RECOVERY_CODES.get(0));
        verify(console).println(RECOVERY_CODES.get(1));
        verify(console).println(RECOVERY_CODES.get(2));
        for (int index = 3; index < RECOVERY_CODES.size(); index++) {
            verify(console, never()).println(RECOVERY_CODES.get(index));
        }
        verify(enrollment).close();
        assertWiped(password);
        assertWiped(confirmation);
        assertWiped(code);
    }

    @Test
    void runnerDefensivelyRejectsNullFromAnyConsoleImplementation() throws Exception {
        for (NullRead nullRead : NullRead.values()) {
            SecretConsole console = mock(SecretConsole.class);
            AdminBootstrapService service = mock(AdminBootstrapService.class);
            AdminBootstrapService.Enrollment enrollment =
                    nullRead == NullRead.TOTP
                            ? mock(AdminBootstrapService.Enrollment.class)
                            : null;
            char[] password = password();
            char[] confirmation = password();
            when(console.readLine("Administrator username: "))
                    .thenReturn(nullRead == NullRead.USERNAME ? null : USERNAME);
            when(console.readSecret("New password: "))
                    .thenReturn(nullRead == NullRead.PASSWORD ? null : password);
            when(console.readSecret("Repeat password: "))
                    .thenReturn(nullRead == NullRead.CONFIRMATION ? null : confirmation);
            if (nullRead == NullRead.TOTP) {
                when(service.prepare(USERNAME, password)).thenReturn(enrollment);
                when(enrollment.provisioningUri()).thenReturn(PROVISIONING_URI);
                when(console.readSecret("Current six-digit TOTP: ")).thenReturn(null);
            }

            assertFixedCauseFree(
                    () -> runner(console, service).run(
                            arguments("--portfolio.cli.command=admin-bootstrap")),
                    "administrator CLI input was cancelled");
            if (nullRead != NullRead.USERNAME && nullRead != NullRead.PASSWORD) {
                assertWiped(password);
            }
            if (nullRead == NullRead.TOTP) {
                assertWiped(confirmation);
                verify(enrollment).close();
                verify(service, never()).complete(any(), any());
                verify(enrollment, never()).takePlaintextRecoveryCodes();
            }
        }
    }

    private AdminBootstrapService integrationService(TotpService totpService, AuditService auditService) {
        return new AdminBootstrapService(
                admins,
                recoveryCodes,
                passwordEncoder,
                passwordPolicy,
                totpService,
                recoveryGenerator,
                recoveryService,
                auditService,
                new TransactionTemplate(transactionManager),
                clock);
    }

    private long countAdmin(UUID adminId) {
        return jdbc.sql("select count(*) from portfolio.admin_user where id=:adminId")
                .param("adminId", adminId)
                .query(Long.class)
                .single();
    }

    private long countRecovery(UUID adminId) {
        return jdbc.sql("""
                        select count(*) from portfolio.totp_recovery_code
                        where admin_id=:adminId
                        """)
                .param("adminId", adminId)
                .query(Long.class)
                .single();
    }

    private long countAudit(UUID adminId) {
        return jdbc.sql("""
                        select count(*) from portfolio.audit_log
                        where actor_admin_id=:adminId
                        """)
                .param("adminId", adminId)
                .query(Long.class)
                .single();
    }

    private List<RecoveryDatabaseRow> recoverySnapshot(UUID adminId) {
        return jdbc.sql("""
                        select id, code_hash, used_at
                        from portfolio.totp_recovery_code
                        where admin_id=:adminId
                        order by id
                        """)
                .param("adminId", adminId)
                .query((resultSet, rowNumber) -> new RecoveryDatabaseRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("code_hash"),
                        resultSet.getObject("used_at")))
                .list();
    }

    private static void cleanupAdmin(UUID adminId) {
        JdbcClient migrator = migratorJdbc();
        try {
            migrator.sql("""
                            delete from portfolio.totp_recovery_code
                            where admin_id=:adminId
                            """)
                    .param("adminId", adminId)
                    .update();
        } finally {
            migrator.sql("delete from portfolio.admin_user where id=:adminId")
                    .param("adminId", adminId)
                    .update();
        }
    }

    private static ApplicationArguments arguments(String... arguments) {
        return new DefaultApplicationArguments(arguments);
    }

    private static AdminCliRunner runner(
            SecretConsole console, AdminBootstrapService bootstrap) {
        return new AdminCliRunner(
                console,
                bootstrap,
                mock(AdminRecoveryService.class),
                mock(TotpKeyReencryptionService.class));
    }

    private static AdminCliRunner recoveryRunner(
            SecretConsole console,
            AdminBootstrapService bootstrap,
            AdminRecoveryService recovery,
            TotpKeyReencryptionService reencrypt) {
        return new AdminCliRunner(console, bootstrap, recovery, reencrypt);
    }

    private static RecoveryCliFixture recoveryCliFixture() {
        SecretConsole console = mock(SecretConsole.class);
        AdminBootstrapService bootstrap = mock(AdminBootstrapService.class);
        AdminRecoveryService recovery = mock(AdminRecoveryService.class);
        TotpKeyReencryptionService reencrypt = mock(TotpKeyReencryptionService.class);
        AdminRecoveryService.Enrollment enrollment =
                mock(AdminRecoveryService.Enrollment.class);
        char[] password = password();
        char[] repeated = password();
        char[] code = TOTP_CODE.toCharArray();
        when(console.readLine(RECOVERY_CONFIRM_PROMPT)).thenReturn("RECOVER ADMIN");
        when(console.readSecret("New password: ")).thenReturn(password);
        when(console.readSecret("Repeat password: ")).thenReturn(repeated);
        when(recovery.prepare(same(password))).thenReturn(enrollment);
        when(enrollment.backupSha256()).thenReturn(RECOVERY_CHECKSUM);
        when(enrollment.provisioningUri()).thenReturn(PROVISIONING_URI);
        when(console.readSecret(RECOVERY_TOTP_PROMPT)).thenReturn(code);
        when(enrollment.takePlaintextRecoveryCodes()).thenReturn(RECOVERY_CODES);
        return new RecoveryCliFixture(
                console,
                bootstrap,
                recovery,
                reencrypt,
                enrollment,
                password,
                repeated,
                code);
    }

    private static char[] password() {
        return PASSWORD.toCharArray();
    }

    private static TotpService.Enrollment totpEnrollment() {
        return new TotpService.Enrollment(TOTP_SECRET, encryptedSecret(), PROVISIONING_URI);
    }

    private static EncryptedTotpSecret encryptedSecret() {
        byte[] nonce = new byte[12];
        byte[] ciphertext = new byte[24];
        Arrays.fill(nonce, (byte) 7);
        Arrays.fill(ciphertext, (byte) 11);
        return new EncryptedTotpSecret(1, nonce, ciphertext);
    }

    private static List<String> hashes() {
        TestPasswordEncoder encoder = new TestPasswordEncoder();
        return RECOVERY_CODES.stream().map(encoder::encode).toList();
    }

    private static List<List<String>> invalidRecoveryCodeLists() {
        List<String> withNull = new ArrayList<>(RECOVERY_CODES);
        withNull.set(3, null);
        List<String> wrongFormat = new ArrayList<>(RECOVERY_CODES);
        wrongFormat.set(3, "ABCI-EFGH-JKLM");
        List<String> duplicate = new ArrayList<>(RECOVERY_CODES);
        duplicate.set(3, duplicate.get(0));
        return Arrays.asList(
                null,
                List.of(),
                RECOVERY_CODES.subList(0, 9),
                withNull,
                wrongFormat,
                duplicate);
    }

    private static List<List<String>> invalidRecoveryHashLists() {
        List<String> valid = hashes();
        List<String> withNull = new ArrayList<>(valid);
        withNull.set(3, null);
        List<String> blank = new ArrayList<>(valid);
        blank.set(3, "   ");
        List<String> tooLong = new ArrayList<>(valid);
        tooLong.set(3, "h".repeat(256));
        List<String> duplicate = new ArrayList<>(valid);
        duplicate.set(3, duplicate.get(0));
        return Arrays.asList(
                null,
                List.of(),
                valid.subList(0, 9),
                withNull,
                blank,
                tooLong,
                duplicate);
    }

    private static List<Throwable> providerFailures() {
        return List.of(
                new IllegalStateException("provider leaked detail"),
                new IOError(new IOException("provider leaked detail")));
    }

    private static List<String> publicMethodSignatures(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(method -> method.getName()
                        + '(' + Arrays.stream(method.getParameterTypes())
                                .map(Class::getName)
                                .collect(java.util.stream.Collectors.joining(","))
                        + "):" + method.getReturnType().getName())
                .sorted()
                .toList();
    }

    private static void assertWiped(char[] value) {
        assertThat(value).containsOnly('\0');
    }

    private static void assertCommittedReferencesReleased(
            AdminBootstrapService.Enrollment enrollment) throws Exception {
        for (String fieldName : List.of(
                "passwordHash",
                "plaintextTotpSecret",
                "encryptedTotpSecret",
                "provisioningUri",
                "recoveryCodeHashes")) {
            assertThat(fieldValue(enrollment, fieldName)).isNull();
        }
        assertThat(fieldValue(enrollment, "plaintextRecoveryCodes")).isNotNull();
    }

    private static void assertAllSensitiveReferencesCleared(
            AdminBootstrapService.Enrollment enrollment) throws Exception {
        for (String fieldName : List.of(
                "passwordHash",
                "plaintextTotpSecret",
                "encryptedTotpSecret",
                "provisioningUri",
                "plaintextRecoveryCodes",
                "recoveryCodeHashes")) {
            assertThat(fieldValue(enrollment, fieldName)).as(fieldName).isNull();
        }
    }

    private static Object fieldValue(
            AdminBootstrapService.Enrollment enrollment, String fieldName) throws Exception {
        Field field = AdminBootstrapService.Enrollment.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(enrollment);
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

    private static void awaitBlocked(
            AtomicReference<Thread> threadReference, Future<?> future, String message) {
        long deadline = System.nanoTime() + SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Thread thread = threadReference.get();
            if (thread != null && thread.getState() == Thread.State.BLOCKED) {
                return;
            }
            if (future.isDone()) {
                throw new AssertionError("enrollment close completed before verifier release");
            }
            Thread.onSpinWait();
        }
        throw new AssertionError(message);
    }

    private static void assertFixedCauseFree(ThrowingInvocation invocation, String message) {
        assertThatIllegalStateException()
                .isThrownBy(invocation::run)
                .withMessage(message)
                .withNoCause();
    }

    private static void assertDomain(
            Throwable failure,
            String code,
            HttpStatus status,
            Map<String, String> fields) {
        assertThat(failure).isInstanceOf(DomainException.class);
        DomainException domain = (DomainException) failure;
        assertThat(domain.code()).isEqualTo(code);
        assertThat(domain.status()).isEqualTo(status);
        assertThat(domain.fieldErrors()).containsExactlyEntriesOf(fields);
    }

    private static void assertConstructorFailure(
            UnitFixture fixture, Dependency dependency, String message) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> fixture.construct(dependency, fixture.transactions))
                .withMessage(message);
    }

    @FunctionalInterface
    private interface ThrowingInvocation {
        void run() throws Exception;
    }

    private enum Dependency {
        NONE,
        ADMINS,
        RECOVERY_REPOSITORY,
        ENCODER,
        POLICY,
        TOTP,
        GENERATOR,
        RECOVERY_SERVICE,
        AUDIT,
        TRANSACTIONS,
        CLOCK
    }

    private enum PreCommitFailure {
        PROVISIONING_OUTPUT,
        COMPLETION
    }

    private enum RecoveryPasswordFailure {
        CANCELLED,
        PROVIDER_FAILURE,
        MISMATCH
    }

    private enum RecoveryPreCommitFailure {
        CHECKSUM_OUTPUT,
        URI_HEADING_OUTPUT,
        URI_OUTPUT,
        TOTP_CANCELLED,
        TOTP_PROVIDER_FAILURE,
        COMPLETION
    }

    private enum NullRead {
        USERNAME,
        PASSWORD,
        CONFIRMATION,
        TOTP
    }

    private enum OutputFailureStage {
        WRITER,
        PRINTLN,
        FLUSH,
        CHECK_ERROR
    }

    private record ArgumentFailure(String[] arguments, String message) {}

    private record RecoveryCliFixture(
            SecretConsole console,
            AdminBootstrapService bootstrap,
            AdminRecoveryService recovery,
            TotpKeyReencryptionService reencrypt,
            AdminRecoveryService.Enrollment enrollment,
            char[] password,
            char[] repeated,
            char[] code) {
        private void run() {
            recoveryRunner(console, bootstrap, recovery, reencrypt)
                    .run(arguments("--portfolio.cli.command=admin-recover"));
        }
    }

    private record AdminDatabaseRow(
            String username,
            String passwordHash,
            String status,
            int keyVersion,
            byte[] nonce,
            byte[] ciphertext,
            Object lastLoginAt,
            long version,
            Instant createdAt,
            Instant updatedAt) {}

    private record RecoveryDatabaseRow(UUID id, String hash, Object usedAt) {}

    private static final class SyntheticAuditException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static final class RecordingTransactionTemplate extends TransactionTemplate {
        private static final long serialVersionUID = 1L;

        private final AtomicInteger executions = new AtomicInteger();
        private RuntimeException failure;

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            executions.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            return action.doInTransaction(new SimpleTransactionStatus());
        }

        int executions() {
            return executions.get();
        }

        void failWith(RuntimeException failure) {
            this.failure = failure;
        }
    }

    private static final class UnitFixture {
        final AdminUserRepository admins = mock(AdminUserRepository.class);
        final RecoveryCodeRepository recoveryRepository = mock(RecoveryCodeRepository.class);
        final PasswordEncoder encoder = mock(PasswordEncoder.class);
        final PasswordPolicy policy = mock(PasswordPolicy.class);
        final TotpService totp = mock(TotpService.class);
        final RecoveryCodeGenerator generator = mock(RecoveryCodeGenerator.class);
        final RecoveryCodeService recoveryService = mock(RecoveryCodeService.class);
        final AuditService audit = mock(AuditService.class);
        final RecordingTransactionTemplate transactions = new RecordingTransactionTemplate();
        final Clock clock = mock(Clock.class);
        final List<String> recoveryHashes = hashes();
        final AdminBootstrapService service;

        UnitFixture() {
            when(admins.count()).thenReturn(0L);
            when(encoder.encode(any())).thenReturn(PASSWORD_HASH);
            when(totp.beginEnrollment(any(), anyString())).thenReturn(totpEnrollment());
            when(totp.verifyEnrollment(TOTP_SECRET, TOTP_CODE)).thenReturn(true);
            when(generator.generate(10)).thenReturn(RECOVERY_CODES);
            when(recoveryService.hashAll(RECOVERY_CODES)).thenReturn(recoveryHashes);
            when(clock.instant()).thenReturn(NOW);
            service = construct(Dependency.NONE, transactions);
        }

        AdminBootstrapService construct(
                Dependency nullDependency, TransactionTemplate transactionTemplate) {
            return new AdminBootstrapService(
                    nullDependency == Dependency.ADMINS ? null : admins,
                    nullDependency == Dependency.RECOVERY_REPOSITORY ? null : recoveryRepository,
                    nullDependency == Dependency.ENCODER ? null : encoder,
                    nullDependency == Dependency.POLICY ? null : policy,
                    nullDependency == Dependency.TOTP ? null : totp,
                    nullDependency == Dependency.GENERATOR ? null : generator,
                    nullDependency == Dependency.RECOVERY_SERVICE ? null : recoveryService,
                    nullDependency == Dependency.AUDIT ? null : audit,
                    nullDependency == Dependency.TRANSACTIONS ? null : transactionTemplate,
                    nullDependency == Dependency.CLOCK ? null : clock);
        }
    }

    static final class RecordingAuditService implements AuditService {
        private final CopyOnWriteArrayList<AuditCommand> commands =
                new CopyOnWriteArrayList<>();

        @Override
        public void record(AuditCommand command) {
            if (!TransactionSynchronizationManager.isActualTransactionActive()) {
                throw new IllegalStateException("bootstrap audit requires a transaction");
            }
            commands.add(command);
        }

        List<AuditCommand> commands() {
            return List.copyOf(commands);
        }

        void clear() {
            commands.clear();
        }
    }

    private static final class TestPasswordEncoder implements PasswordEncoder {
        @Override
        public String encode(CharSequence rawPassword) {
            if (rawPassword == null) {
                throw new IllegalArgumentException("raw password is required");
            }
            byte[] bytes = rawPassword.toString().getBytes(StandardCharsets.UTF_8);
            try {
                return "test-sha256$" + java.util.HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(bytes));
            } catch (java.security.NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 unavailable");
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            return MessageDigest.isEqual(
                    encode(rawPassword).getBytes(StandardCharsets.US_ASCII),
                    encodedPassword.getBytes(StandardCharsets.US_ASCII));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class BootstrapTestConfiguration {
        @Bean
        @Primary
        RecordingAuditService bootstrapRecordingAuditService() {
            return new RecordingAuditService();
        }

        @Bean
        @Primary
        PasswordEncoder bootstrapTestPasswordEncoder() {
            return new TestPasswordEncoder();
        }

        @Bean
        @Primary
        Clock bootstrapTestClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        @Primary
        TotpService bootstrapTestTotpService() {
            TotpService service = mock(TotpService.class);
            when(service.beginEnrollment(any(), anyString())).thenReturn(totpEnrollment());
            when(service.verifyEnrollment(TOTP_SECRET, TOTP_CODE)).thenReturn(true);
            return service;
        }

        @Bean
        @Primary
        RecoveryCodeGenerator bootstrapTestRecoveryCodeGenerator() {
            RecoveryCodeGenerator generator = mock(RecoveryCodeGenerator.class);
            when(generator.generate(10)).thenReturn(RECOVERY_CODES);
            return generator;
        }
    }
}
