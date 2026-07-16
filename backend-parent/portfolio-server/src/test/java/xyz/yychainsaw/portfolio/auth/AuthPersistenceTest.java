package xyz.yychainsaw.portfolio.auth;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.yychainsaw.portfolio.auth.crypto.EncryptedTotpSecret;
import xyz.yychainsaw.portfolio.auth.model.AdminStatus;
import xyz.yychainsaw.portfolio.auth.model.AdminUser;
import xyz.yychainsaw.portfolio.auth.persistence.AdminUserRepository;
import xyz.yychainsaw.portfolio.auth.persistence.RecoveryCodeRepository;
import xyz.yychainsaw.portfolio.auth.persistence.RecoveryCodeRepository.StoredCode;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
@Isolated
class AuthPersistenceTest extends PostgresIntegrationTestBase {
    private static final String RECOVERY_CODE = "ABCD-EFGH-JKLM";
    private static final String SECOND_RECOVERY_CODE = "MNPQ-RSTU-VWXY";
    private static final Instant CREATED_AT = Instant.parse("2026-07-15T01:02:03.123456Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-07-15T02:03:04.654321Z");

    @Autowired AdminUserRepository admins;
    @Autowired RecoveryCodeRepository recoveryCodes;
    @Autowired RecoveryCodeService recoveryCodeService;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void persistenceTestClassDoesNotSupplyAnAmbientTransaction() {
        assertThat(AuthPersistenceTest.class.getDeclaredAnnotation(Transactional.class)).isNull();
    }

    @Test
    void adminModelValidatesCodePointAndSecretBoundariesAndRedactsSecrets() {
        AdminUser shortest = admin(UUID.randomUUID(), "abc", null);
        AdminUser longest = admin(UUID.randomUUID(), "\uD83D\uDE00".repeat(64), null);
        assertThat(shortest.username()).hasSize(3);
        assertThat(longest.username().codePointCount(0, longest.username().length())).isEqualTo(64);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> admin(UUID.randomUUID(), "ab", null))
                .withMessage("username must contain between 3 and 64 code points");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> admin(UUID.randomUUID(), "\uD83D\uDE00".repeat(65), null))
                .withMessage("username must contain between 3 and 64 code points");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> admin(UUID.randomUUID(), " admin", null))
                .withMessage("username must already be trimmed");

        AdminUser maximumHash = new AdminUser(
                UUID.randomUUID(),
                "admin",
                "h".repeat(255),
                AdminStatus.ACTIVE,
                secret(1),
                null,
                0,
                CREATED_AT,
                UPDATED_AT);
        assertThat(maximumHash.passwordHash()).hasSize(255);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AdminUser(
                        UUID.randomUUID(),
                        "admin",
                        "h".repeat(256),
                        AdminStatus.ACTIVE,
                        secret(1),
                        null,
                        0,
                        CREATED_AT,
                        UPDATED_AT))
                .withMessage("password hash must contain between 1 and 255 characters");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AdminUser(
                        UUID.randomUUID(),
                        "admin",
                        "   ",
                        AdminStatus.ACTIVE,
                        secret(1),
                        null,
                        0,
                        CREATED_AT,
                        UPDATED_AT))
                .withMessage("password hash must contain between 1 and 255 characters");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AdminUser(
                        UUID.randomUUID(),
                        "admin",
                        null,
                        AdminStatus.ACTIVE,
                        secret(1),
                        null,
                        0,
                        CREATED_AT,
                        UPDATED_AT))
                .withMessage("password hash must contain between 1 and 255 characters");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AdminUser(
                        UUID.randomUUID(),
                        "admin",
                        "hash",
                        AdminStatus.ACTIVE,
                        secret(1),
                        null,
                        -1,
                        CREATED_AT,
                        UPDATED_AT))
                .withMessage("version must not be negative");

        assertRequiredAdminField(null, "admin id is required", RequiredField.ID);
        assertRequiredAdminField(null, "admin status is required", RequiredField.STATUS);
        assertRequiredAdminField(null, "encrypted TOTP secret is required", RequiredField.SECRET);
        assertRequiredAdminField(null, "created timestamp is required", RequiredField.CREATED_AT);
        assertRequiredAdminField(null, "updated timestamp is required", RequiredField.UPDATED_AT);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AdminUser(
                        UUID.randomUUID(),
                        null,
                        "hash",
                        AdminStatus.ACTIVE,
                        secret(1),
                        null,
                        0,
                        CREATED_AT,
                        UPDATED_AT))
                .withMessage("username is required");

        AdminUser safe = new AdminUser(
                UUID.randomUUID(),
                "PortfolioAdmin",
                "very-sensitive-password-hash",
                AdminStatus.DISABLED,
                secret(7),
                null,
                3,
                CREATED_AT,
                UPDATED_AT);
        assertThat(safe.toString())
                .contains("username=PortfolioAdmin", "passwordHash=<redacted>", "totpSecret=<redacted>")
                .doesNotContain("very-sensitive-password-hash", "nonce=[", "ciphertext=[");
    }

    @Test
    void storedCodeValidatesBoundariesAndRedactsItsHash() {
        UUID id = UUID.randomUUID();
        StoredCode maximum = new StoredCode(id, "h".repeat(255));
        assertThat(maximum.hash()).hasSize(255);
        assertThat(maximum.toString())
                .contains(id.toString(), "hash=<redacted>")
                .doesNotContain("h".repeat(32));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StoredCode(null, "hash"))
                .withMessage("recovery-code id is required");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StoredCode(id, "  "))
                .withMessage("recovery-code hash must contain between 1 and 255 characters");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StoredCode(id, null))
                .withMessage("recovery-code hash must contain between 1 and 255 characters");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StoredCode(id, "h".repeat(256)))
                .withMessage("recovery-code hash must contain between 1 and 255 characters");
    }

    @Test
    void hashAllNormalizesOnlyAfterCompleteValidationAndReturnsImmutableHashes() {
        RecoveryCodeRepository repository = mock(RecoveryCodeRepository.class);
        RecordingPasswordEncoder provider = new RecordingPasswordEncoder();
        RecoveryCodeService service = new RecoveryCodeService(repository, provider);

        List<String> hashes = service.hashAll(List.of("\tabcd-efgh-jklm \r", "mnpq-rstu-vwxy"));
        assertThat(provider.encodedValues()).containsExactly(RECOVERY_CODE, SECOND_RECOVERY_CODE);
        assertThat(hashes).containsExactly("encoded-1", "encoded-2");
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> hashes.add("another"));

        RecordingPasswordEncoder validationProvider = new RecordingPasswordEncoder();
        RecoveryCodeService validating = new RecoveryCodeService(repository, validationProvider);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> validating.hashAll(List.of(RECOVERY_CODE, "ABCD-EFGH-JKLI")))
                .withMessage("recovery code format is invalid");
        assertThat(validationProvider.encodedValues()).isEmpty();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> validating.hashAll(List.of(RECOVERY_CODE, " abcd-efgh-jklm ")))
                .withMessage("recovery codes must be distinct");
        assertThat(validationProvider.encodedValues()).isEmpty();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> validating.hashAll(List.of()))
                .withMessage("recovery-code count must be between 1 and 10");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> validating.hashAll(null))
                .withMessage("recovery-code count must be between 1 and 10");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> validating.hashAll(java.util.Collections.nCopies(11, RECOVERY_CODE)))
                .withMessage("recovery-code count must be between 1 and 10");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> validating.hashAll(Arrays.asList(RECOVERY_CODE, null)))
                .withMessage("recovery code format is invalid");
        assertThat(validationProvider.encodedValues()).isEmpty();
    }

    @Test
    void hashAndMatchProviderFailuresAreCauseFreeAndDoNotLeakProviderMaterial() {
        RecoveryCodeRepository repository = mock(RecoveryCodeRepository.class);
        PasswordEncoder encodeFailure = new PasswordEncoder() {
            @Override
            public String encode(CharSequence rawPassword) {
                throw new IllegalStateException("provider leaked " + rawPassword);
            }

            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                return false;
            }
        };
        Throwable encodingFailure = catchThrowable(
                () -> new RecoveryCodeService(repository, encodeFailure).hashAll(List.of(RECOVERY_CODE)));
        assertThat(encodingFailure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("recovery-code hashing failed")
                .hasNoCause();
        assertThat(encodingFailure.toString()).doesNotContain(RECOVERY_CODE, "provider leaked");

        UUID adminId = UUID.randomUUID();
        when(repository.findUnused(adminId))
                .thenReturn(List.of(new StoredCode(UUID.randomUUID(), "sensitive-provider-hash")));
        PasswordEncoder matchFailure = new PasswordEncoder() {
            @Override
            public String encode(CharSequence rawPassword) {
                return "unused";
            }

            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                throw new IllegalStateException("provider leaked " + rawPassword + " " + encodedPassword);
            }
        };
        Throwable matchingFailure = catchThrowable(
                () -> new RecoveryCodeService(repository, matchFailure).consume(adminId, RECOVERY_CODE));
        assertThat(matchingFailure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("recovery-code verification failed")
                .hasNoCause();
        assertThat(matchingFailure.toString())
                .doesNotContain(RECOVERY_CODE, "sensitive-provider-hash", "provider leaked");
    }

    @Test
    void hashAllRejectsInvalidOrDuplicateProviderResultsWithoutLeakingThem() {
        RecoveryCodeRepository repository = mock(RecoveryCodeRepository.class);
        PasswordEncoder oversized = fixedEncoder("s".repeat(256));
        Throwable oversizedFailure = catchThrowable(
                () -> new RecoveryCodeService(repository, oversized).hashAll(List.of(RECOVERY_CODE)));
        assertThat(oversizedFailure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("recovery-code provider returned an invalid hash")
                .hasNoCause();
        assertThat(oversizedFailure.toString()).doesNotContain("s".repeat(32));

        for (String invalidHash : Arrays.asList(null, "   ")) {
            Throwable invalidFailure = catchThrowable(() -> new RecoveryCodeService(
                            repository, fixedEncoder(invalidHash))
                    .hashAll(List.of(RECOVERY_CODE)));
            assertThat(invalidFailure)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("recovery-code provider returned an invalid hash")
                    .hasNoCause();
        }

        PasswordEncoder duplicate = fixedEncoder("same-provider-hash");
        Throwable duplicateFailure = catchThrowable(() -> new RecoveryCodeService(repository, duplicate)
                .hashAll(List.of(RECOVERY_CODE, SECOND_RECOVERY_CODE)));
        assertThat(duplicateFailure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("recovery-code provider returned duplicate hashes")
                .hasNoCause();
        assertThat(duplicateFailure.toString()).doesNotContain("same-provider-hash");
    }

    @Test
    void consumeScansEveryStoredHashBeforeOneCasAndRejectsMultipleMatches() {
        UUID adminId = UUID.randomUUID();
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        UUID thirdId = UUID.randomUUID();
        List<StoredCode> stored = List.of(
                new StoredCode(firstId, "stored-one"),
                new StoredCode(secondId, "stored-two"),
                new StoredCode(thirdId, "stored-three"));

        RecoveryCodeRepository repository = mock(RecoveryCodeRepository.class);
        when(repository.findUnused(adminId)).thenReturn(stored);
        when(repository.markUsed(adminId, secondId)).thenReturn(true);
        MatchingPasswordEncoder oneMatch = new MatchingPasswordEncoder(List.of("stored-two"));
        RecoveryCodeService service = new RecoveryCodeService(repository, oneMatch);
        assertThat(service.consume(adminId, " abcd-efgh-jklm ")).isTrue();
        assertThat(oneMatch.visitedHashes()).containsExactly("stored-one", "stored-two", "stored-three");
        verify(repository).markUsed(adminId, secondId);

        RecoveryCodeRepository corruptRepository = mock(RecoveryCodeRepository.class);
        when(corruptRepository.findUnused(adminId)).thenReturn(stored);
        MatchingPasswordEncoder duplicateMatch =
                new MatchingPasswordEncoder(List.of("stored-one", "stored-three"));
        Throwable failure = catchThrowable(() -> new RecoveryCodeService(corruptRepository, duplicateMatch)
                .consume(adminId, RECOVERY_CODE));
        assertThat(duplicateMatch.visitedHashes())
                .containsExactly("stored-one", "stored-two", "stored-three");
        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("multiple recovery-code hashes matched")
                .hasNoCause();
        assertThat(failure.toString()).doesNotContain("stored-one", "stored-three", RECOVERY_CODE);
        verify(corruptRepository, never()).markUsed(any(UUID.class), any(UUID.class));

        RecoveryCodeRepository noMatchRepository = mock(RecoveryCodeRepository.class);
        when(noMatchRepository.findUnused(adminId)).thenReturn(stored);
        MatchingPasswordEncoder noMatch = new MatchingPasswordEncoder(List.of());
        assertThat(new RecoveryCodeService(noMatchRepository, noMatch)
                        .consume(adminId, RECOVERY_CODE))
                .isFalse();
        assertThat(noMatch.visitedHashes()).containsExactly("stored-one", "stored-two", "stored-three");
        verify(noMatchRepository, never()).markUsed(any(UUID.class), any(UUID.class));

        RecoveryCodeRepository emptyRepository = mock(RecoveryCodeRepository.class);
        when(emptyRepository.findUnused(adminId)).thenReturn(List.of());
        PasswordEncoder unusedProvider = mock(PasswordEncoder.class);
        assertThat(new RecoveryCodeService(emptyRepository, unusedProvider)
                        .consume(adminId, RECOVERY_CODE))
                .isFalse();
        verifyNoInteractions(unusedProvider);
        verify(emptyRepository, never()).markUsed(any(UUID.class), any(UUID.class));
    }

    @Test
    void consumeRejectsInvalidExternalCodesBeforeRepositoryOrProviderWork() {
        RecoveryCodeRepository repository = mock(RecoveryCodeRepository.class);
        PasswordEncoder provider = mock(PasswordEncoder.class);
        RecoveryCodeService service = new RecoveryCodeService(repository, provider);
        UUID adminId = UUID.randomUUID();

        assertThat(service.consume(adminId, null)).isFalse();
        assertThat(service.consume(adminId, "bad-code")).isFalse();
        assertThat(service.consume(adminId, "A".repeat(300))).isFalse();
        assertThatNullPointerException()
                .isThrownBy(() -> service.consume(null, RECOVERY_CODE))
                .withMessage("admin id is required");
        verifyNoInteractions(repository, provider);
    }

    @Test
    @Transactional
    void repositoryAndServiceInputsAreValidatedBeforeJdbcWork() {
        assertThatNullPointerException()
                .isThrownBy(() -> new AdminUserRepository(null))
                .withMessage("jdbc is required");
        assertThatNullPointerException()
                .isThrownBy(() -> new RecoveryCodeRepository(null))
                .withMessage("jdbc is required");
        assertThatNullPointerException()
                .isThrownBy(() -> new RecoveryCodeService(null, encoder))
                .withMessage("recovery-code repository is required");
        assertThatNullPointerException()
                .isThrownBy(() -> new RecoveryCodeService(recoveryCodes, null))
                .withMessage("password encoder is required");

        assertThat(admins.findByUsername(null)).isEmpty();
        assertThat(admins.findByUsername("   ")).isEmpty();
        assertThat(admins.findByUsername("ab")).isEmpty();
        assertThat(admins.findByUsername("a".repeat(65))).isEmpty();
        assertThatNullPointerException()
                .isThrownBy(() -> admins.findById(null))
                .withMessage("admin id is required");
        assertThatNullPointerException()
                .isThrownBy(() -> admins.insert(null))
                .withMessage("admin user is required");
        assertThatNullPointerException()
                .isThrownBy(() -> admins.updateLastLogin(null, CREATED_AT))
                .withMessage("admin id is required");
        assertThatNullPointerException()
                .isThrownBy(() -> admins.updateLastLogin(UUID.randomUUID(), null))
                .withMessage("last-login timestamp is required");
        assertThatNullPointerException()
                .isThrownBy(() -> admins.replaceCredentials(null, "hash", secret(1)))
                .withMessage("admin id is required");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> admins.replaceCredentials(UUID.randomUUID(), null, secret(1)))
                .withMessage("password hash must contain between 1 and 255 characters");
        assertThatNullPointerException()
                .isThrownBy(() -> admins.replaceCredentials(UUID.randomUUID(), "hash", null))
                .withMessage("encrypted TOTP secret is required");
        assertThatNullPointerException()
                .isThrownBy(() -> admins.updatePassword(null, "hash"))
                .withMessage("admin id is required");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> admins.updatePassword(UUID.randomUUID(), null))
                .withMessage("password hash must contain between 1 and 255 characters");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> admins.updatePassword(UUID.randomUUID(), " "))
                .withMessage("password hash must contain between 1 and 255 characters");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> admins.updatePassword(UUID.randomUUID(), "h".repeat(256)))
                .withMessage("password hash must contain between 1 and 255 characters");
        assertThatNullPointerException()
                .isThrownBy(() -> admins.updateTotp(null, secret(1)))
                .withMessage("admin id is required");
        assertThatNullPointerException()
                .isThrownBy(() -> admins.updateTotp(UUID.randomUUID(), null))
                .withMessage("encrypted TOTP secret is required");
        assertThatNullPointerException()
                .isThrownBy(() -> admins.bumpSecurityVersion(null))
                .withMessage("admin id is required");

        RecoveryCodeRepository direct = new RecoveryCodeRepository(jdbc);
        UUID adminId = UUID.randomUUID();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> direct.replace(adminId, List.of()))
                .withMessage("recovery-code hash count must be between 1 and 10");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> direct.replace(adminId, null))
                .withMessage("recovery-code hash count must be between 1 and 10");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> direct.replace(adminId, java.util.Collections.nCopies(11, "hash")))
                .withMessage("recovery-code hash count must be between 1 and 10");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> direct.replace(adminId, List.of("hash", "hash")))
                .withMessage("recovery-code hashes must be distinct");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> direct.replace(adminId, List.of(" ")))
                .withMessage("recovery-code hash must contain between 1 and 255 characters");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> direct.replace(adminId, Arrays.asList("hash", null)))
                .withMessage("recovery-code hash must contain between 1 and 255 characters");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> direct.replace(adminId, List.of("h".repeat(256))))
                .withMessage("recovery-code hash must contain between 1 and 255 characters");
        assertThatNullPointerException()
                .isThrownBy(() -> direct.replace(null, List.of("hash")))
                .withMessage("admin id is required");
        assertThatNullPointerException()
                .isThrownBy(() -> direct.findUnused(null))
                .withMessage("admin id is required");
        assertThatNullPointerException()
                .isThrownBy(() -> direct.markUsed(null, UUID.randomUUID()))
                .withMessage("admin id is required");
        assertThatNullPointerException()
                .isThrownBy(() -> direct.markUsed(adminId, null))
                .withMessage("recovery-code id is required");
    }

    @Test
    void findByIdForUpdateRejectsNullBeforeJdbcWork() {
        JdbcClient mockedJdbc = mock(JdbcClient.class);
        AdminUserRepository direct = new AdminUserRepository(mockedJdbc);

        assertThatNullPointerException()
                .isThrownBy(() -> direct.findByIdForUpdate(null))
                .withMessage("admin id is required");

        verifyNoInteractions(mockedJdbc);
    }

    @Test
    @Transactional
    void findByIdForUpdateReturnsEmptyOrUsesTheExactAdminMapperWithQualifiedSql() {
        setHostileSearchPath();
        UUID adminId = UUID.randomUUID();
        AdminUser expected = admin(
                adminId,
                "LockMappedAdmin",
                Instant.parse("2026-07-15T03:04:05.123456Z"),
                AdminStatus.DISABLED,
                7,
                secret(11));
        admins.insert(expected);

        assertThat(admins.findByIdForUpdate(UUID.randomUUID())).isEmpty();
        assertThat(admins.findByIdForUpdate(adminId)).contains(expected);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void findByIdForUpdateRequiresAnExistingTransactionBeforeJdbcWork() {
        assertThatThrownBy(() -> admins.findByIdForUpdate(UUID.randomUUID()))
                .isInstanceOf(IllegalTransactionStateException.class)
                .hasMessageContaining("existing transaction")
                .hasMessageContaining("mandatory");
        assertThatThrownBy(() -> admins.updatePassword(UUID.randomUUID(), "hash"))
                .isInstanceOf(IllegalTransactionStateException.class)
                .hasMessageContaining("existing transaction")
                .hasMessageContaining("mandatory");
        assertThatThrownBy(() -> admins.updateTotp(UUID.randomUUID(), secret(1)))
                .isInstanceOf(IllegalTransactionStateException.class)
                .hasMessageContaining("existing transaction")
                .hasMessageContaining("mandatory");
        assertThatThrownBy(() -> admins.bumpSecurityVersion(UUID.randomUUID()))
                .isInstanceOf(IllegalTransactionStateException.class)
                .hasMessageContaining("existing transaction")
                .hasMessageContaining("mandatory");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void findByIdForUpdateHoldsTheRealPostgresRowLockUntilItsTransactionCompletes()
            throws Exception {
        UUID adminId = UUID.randomUUID();
        AdminUser expected = admin(
                adminId,
                "LockedAdmin",
                Instant.parse("2026-07-15T04:05:06.654321Z"),
                AdminStatus.ACTIVE,
                3,
                secret(12));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        List<Future<AdminUser>> futures = new ArrayList<>();
        try {
            admins.insert(expected);
            futures.add(executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                AdminUser locked = admins.findByIdForUpdate(adminId).orElseThrow();
                firstLocked.countDown();
                awaitWithoutChecked(releaseFirst, "first admin lock was not released");
                return locked;
            })));
            await(firstLocked, "first transaction did not acquire the admin lock");

            futures.add(executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                secondStarted.countDown();
                return admins.findByIdForUpdate(adminId).orElseThrow();
            })));
            await(secondStarted, "second transaction did not attempt the admin lock");

            assertThatExceptionOfType(TimeoutException.class)
                    .as("the second transaction must block on the first transaction's row lock")
                    .isThrownBy(() -> futures.get(1).get(1, SECONDS));

            releaseFirst.countDown();
            assertThat(futures.get(0).get(20, SECONDS)).isEqualTo(expected);
            assertThat(futures.get(1).get(20, SECONDS)).isEqualTo(expected);
        } finally {
            releaseFirst.countDown();
            try {
                stopExecutor(executor, futures);
            } finally {
                cleanupCommittedAdmin(adminId);
            }
        }
    }

    @Test
    @Transactional
    void adminPersistenceRoundTripsNullLastLoginAndUpdatesUnderHostileSearchPath() {
        setHostileSearchPath();
        UUID adminId = UUID.randomUUID();
        AdminUser original = admin(adminId, "PortfolioAdmin", null, AdminStatus.DISABLED, 0, secret(1));

        assertThat(admins.count()).isZero();
        admins.insert(original);
        assertThat(admins.count()).isOne();
        assertThat(admins.findByUsername("portfolioadmin")).contains(original);
        assertThat(admins.findByUsername("PORTFOLIOADMIN").orElseThrow().username())
                .isEqualTo("PortfolioAdmin");
        assertThat(admins.findById(adminId)).contains(original);

        Instant loginAt = Instant.parse("2026-07-15T03:04:05.222333Z");
        admins.updateLastLogin(adminId, loginAt);
        AdminUser afterLogin = admins.findById(adminId).orElseThrow();
        assertThat(afterLogin.lastLoginAt()).isEqualTo(loginAt);
        assertThat(afterLogin.version()).isEqualTo(1);

        EncryptedTotpSecret replacementSecret = secret(9);
        admins.replaceCredentials(adminId, "replacement-password-hash", replacementSecret);
        AdminUser afterReplacement = admins.findById(adminId).orElseThrow();
        assertThat(afterReplacement.passwordHash()).isEqualTo("replacement-password-hash");
        assertThat(afterReplacement.totpSecret()).isEqualTo(replacementSecret);
        assertThat(afterReplacement.status()).isEqualTo(AdminStatus.ACTIVE);
        assertThat(afterReplacement.version()).isEqualTo(2);

        UUID missing = UUID.randomUUID();
        assertThatThrownBy(() -> admins.updateLastLogin(missing, loginAt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("admin last-login update affected an unexpected number of rows");
        assertThatThrownBy(() -> admins.replaceCredentials(missing, "hash", secret(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("admin credential update affected an unexpected number of rows");
    }

    @Test
    void securitySettingsUpdatesAdvanceAndReturnOnlyTheirIntendedSecurityEpoch() {
        UUID adminId = UUID.randomUUID();
        EncryptedTotpSecret originalSecret = secret(3);
        EncryptedTotpSecret replacement = secret(4);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        try {
            transaction.executeWithoutResult(status -> {
                setHostileSearchPath();
                admins.insert(admin(
                        adminId, "SecurityEpochAdmin", null,
                        AdminStatus.DISABLED, 7, originalSecret));
            });

            Long passwordVersion = transaction.execute(status -> {
                setHostileSearchPath();
                return admins.updatePassword(adminId, "new-password-hash");
            });
            assertThat(passwordVersion).isEqualTo(8L);
            AdminUser afterPassword = admins.findById(adminId).orElseThrow();
            assertThat(afterPassword.passwordHash()).isEqualTo("new-password-hash");
            assertThat(afterPassword.totpSecret()).isEqualTo(originalSecret);
            assertThat(afterPassword.status()).isEqualTo(AdminStatus.DISABLED);
            assertThat(afterPassword.version()).isEqualTo(8);

            Long totpVersion = transaction.execute(status -> {
                setHostileSearchPath();
                return admins.updateTotp(adminId, replacement);
            });
            assertThat(totpVersion).isEqualTo(9L);
            AdminUser afterTotp = admins.findById(adminId).orElseThrow();
            assertThat(afterTotp.passwordHash()).isEqualTo("new-password-hash");
            assertThat(afterTotp.totpSecret()).isEqualTo(replacement);
            assertThat(afterTotp.status()).isEqualTo(AdminStatus.DISABLED);
            assertThat(afterTotp.version()).isEqualTo(9);

            Long bumpedVersion = transaction.execute(status -> {
                setHostileSearchPath();
                return admins.bumpSecurityVersion(adminId);
            });
            assertThat(bumpedVersion).isEqualTo(10L);
            AdminUser afterBump = admins.findById(adminId).orElseThrow();
            assertThat(afterBump.passwordHash()).isEqualTo("new-password-hash");
            assertThat(afterBump.totpSecret()).isEqualTo(replacement);
            assertThat(afterBump.status()).isEqualTo(AdminStatus.DISABLED);
            assertThat(afterBump.version()).isEqualTo(10);

            UUID missing = UUID.randomUUID();
            assertThatThrownBy(() -> transaction.execute(status ->
                    admins.updatePassword(missing, "hash")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("admin password update affected an unexpected number of rows");
            assertThatThrownBy(() -> transaction.execute(status ->
                    admins.updateTotp(missing, secret(5))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("admin TOTP update affected an unexpected number of rows");
            assertThatThrownBy(() -> transaction.execute(status ->
                    admins.bumpSecurityVersion(missing)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("admin security-version update affected an unexpected number of rows");
        } finally {
            cleanupCommittedAdmin(adminId);
        }
    }

    @Test
    @Transactional
    void insertPreservesNonNullLastLoginAndUtcEnvelopeRoundTrip() {
        UUID adminId = UUID.randomUUID();
        Instant loginAt = Instant.parse("2025-12-31T23:59:59.999999Z");
        AdminUser original = admin(adminId, "TimeAdmin", loginAt, AdminStatus.ACTIVE, 4, secret(6));
        admins.insert(original);

        AdminUser fetched = admins.findById(adminId).orElseThrow();
        assertThat(fetched).isEqualTo(original);
        assertThat(fetched.lastLoginAt()).isEqualTo(loginAt);
        assertThat(fetched.createdAt()).isEqualTo(CREATED_AT);
        assertThat(fetched.updatedAt()).isEqualTo(UPDATED_AT);
        assertThat(fetched.totpSecret()).isEqualTo(secret(6));
    }

    @Test
    @Transactional
    void secondSingletonInsertRetainsPostgresUniqueViolation() {
        admins.insert(admin(UUID.randomUUID(), "FirstAdmin", null));
        Throwable failure = catchThrowable(() -> admins.insert(admin(UUID.randomUUID(), "SecondAdmin", null)));
        assertThat(failure).isNotNull();
        Throwable root = rootCause(failure);
        assertThat(root).isInstanceOf(SQLException.class);
        assertThat(((SQLException) root).getSQLState()).isEqualTo("23505");
    }

    @Test
    @Transactional
    void recoveryReplacementOrderingAndSequentialSingleUseWorkWithHostileSearchPath() {
        setHostileSearchPath();
        UUID adminId = UUID.randomUUID();
        admins.insert(admin(adminId, "RecoveryAdmin", null));
        recoveryCodes.replace(adminId, List.of("old-hash-one", "old-hash-two"));

        List<String> hashes = recoveryCodeService.hashAll(List.of(RECOVERY_CODE, SECOND_RECOVERY_CODE));
        recoveryCodes.replace(adminId, hashes);
        List<StoredCode> available = recoveryCodes.findUnused(adminId);
        assertThat(available).extracting(StoredCode::hash).containsExactlyElementsOf(hashes);
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> available.add(new StoredCode(UUID.randomUUID(), "hash")));

        assertThat(recoveryCodeService.consume(adminId, " abcd-efgh-jklm\t")).isTrue();
        assertThat(recoveryCodeService.consume(adminId, RECOVERY_CODE)).isFalse();
        assertThat(recoveryCodes.findUnused(adminId))
                .extracting(StoredCode::hash)
                .containsExactly(hashes.get(1));
        assertThat(recoveryCodes.markUsed(adminId, UUID.randomUUID())).isFalse();
    }

    @Test
    @Transactional
    void unusedRecoveryCodesHaveStableCreatedAtThenIdOrdering() {
        UUID adminId = UUID.randomUUID();
        admins.insert(admin(adminId, "OrderingAdmin", null));
        UUID firstId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID secondId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID laterId = UUID.fromString("00000000-0000-0000-0000-000000000000");

        insertRecoveryCode(laterId, adminId, "later", "2026-07-15T02:00:00Z");
        insertRecoveryCode(secondId, adminId, "same-time-second", "2026-07-15T01:00:00Z");
        insertRecoveryCode(firstId, adminId, "same-time-first", "2026-07-15T01:00:00Z");

        assertThat(recoveryCodes.findUnused(adminId))
                .extracting(StoredCode::id)
                .containsExactly(firstId, secondId, laterId);
    }

    @Test
    @Transactional
    void recoveryReplacementRequiresItsExactExistingParent() {
        assertThatThrownBy(() -> recoveryCodes.replace(UUID.randomUUID(), List.of("hash")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("administrator does not exist");
    }

    @Test
    @Transactional
    void recoveryConsumptionRequiresItsExactExistingParent() {
        assertThatThrownBy(() -> recoveryCodes.markUsed(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("administrator does not exist");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void replacementParticipatesInOuterRollbackAndRestoresCommittedSet() {
        UUID adminId = UUID.randomUUID();
        List<String> oldHashes = List.of("committed-old-one", "committed-old-two");
        List<String> newHashes = List.of("rolled-back-new-one", "rolled-back-new-two");
        try {
            admins.insert(admin(adminId, "RollbackAdmin", null));
            recoveryCodes.replace(adminId, oldHashes);

            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                recoveryCodes.replace(adminId, newHashes);
                assertThat(recoveryCodes.findUnused(adminId))
                        .extracting(StoredCode::hash)
                        .containsExactlyElementsOf(newHashes);
                status.setRollbackOnly();
            });

            assertThat(recoveryCodes.findUnused(adminId))
                    .extracting(StoredCode::hash)
                    .containsExactlyElementsOf(oldHashes);
        } finally {
            cleanupCommittedAdmin(adminId);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentReplacementsLeaveExactlyOneCompleteSet() throws Exception {
        UUID adminId = UUID.randomUUID();
        List<String> setA = List.of("set-a-one", "set-a-two", "set-a-three");
        List<String> setB = List.of("set-b-one", "set-b-two");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<>();
        try {
            admins.insert(admin(adminId, "ReplaceAdmin", null));
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                assertThat(jdbc.sql("""
                                select id from portfolio.admin_user
                                where id=:adminId for update
                                """)
                        .param("adminId", adminId)
                        .query(UUID.class)
                        .single())
                        .isEqualTo(adminId);
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    await(start, "concurrent replacement start timed out");
                    recoveryCodes.replace(adminId, setA);
                    return null;
                }));
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    await(start, "concurrent replacement start timed out");
                    recoveryCodes.replace(adminId, setB);
                    return null;
                }));
                awaitWithoutChecked(ready, "replacement workers did not become ready");
                start.countDown();
                for (Future<Void> future : futures) {
                    assertThatExceptionOfType(TimeoutException.class)
                            .as("each replacement must block on the locked admin parent")
                            .isThrownBy(() -> future.get(1, SECONDS));
                }
            });
            for (Future<Void> future : futures) {
                future.get(20, SECONDS);
            }

            List<String> finalHashes = recoveryCodes.findUnused(adminId).stream()
                    .map(StoredCode::hash)
                    .toList();
            Set<String> finalSet = new HashSet<>(finalHashes);
            assertThat(finalHashes).hasSize(finalSet.size());
            assertThat(finalSet.equals(Set.copyOf(setA)) || finalSet.equals(Set.copyOf(setB))).isTrue();
        } finally {
            start.countDown();
            try {
                stopExecutor(executor, futures);
            } finally {
                cleanupCommittedAdmin(adminId);
            }
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentConsumptionUsesOneAdminBoundCasExactlyOnce() throws Exception {
        UUID adminId = UUID.randomUUID();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            admins.insert(admin(adminId, "ConsumeAdmin", null));
            recoveryCodes.replace(adminId, List.of(encoder.encode(RECOVERY_CODE)));

            BarrierPasswordEncoder barrierEncoder = new BarrierPasswordEncoder(encoder);
            RecoveryCodeService concurrentService = transactionalService(barrierEncoder);
            assertThat(AopUtils.isAopProxy(recoveryCodeService)).isTrue();
            assertThat(AopUtils.isAopProxy(concurrentService)).isTrue();

            futures.add(executor.submit(() -> concurrentService.consume(adminId, RECOVERY_CODE)));
            futures.add(executor.submit(() -> concurrentService.consume(adminId, RECOVERY_CODE)));
            boolean first = futures.get(0).get(30, SECONDS);
            boolean second = futures.get(1).get(30, SECONDS);

            assertThat(List.of(first, second)).containsExactlyInAnyOrder(true, false);
            assertThat(barrierEncoder.matchesCalls()).isEqualTo(2);
            Long usedRows = migratorJdbc()
                    .sql("""
                            select count(*) from portfolio.totp_recovery_code
                            where admin_id=:adminId and used_at is not null
                            """)
                    .param("adminId", adminId)
                    .query(Long.class)
                    .single();
            assertThat(usedRows).isOne();
            assertThat(recoveryCodeService.consume(adminId, RECOVERY_CODE)).isFalse();
        } finally {
            try {
                stopExecutor(executor, futures);
            } finally {
                cleanupCommittedAdmin(adminId);
            }
        }
    }

    private RecoveryCodeService transactionalService(PasswordEncoder provider) {
        RecoveryCodeService target = new RecoveryCodeService(recoveryCodes, provider);
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(new TransactionInterceptor(
                transactionManager, new AnnotationTransactionAttributeSource()));
        return (RecoveryCodeService) proxyFactory.getProxy();
    }

    private void setHostileSearchPath() {
        jdbc.sql("set local search_path = pg_catalog").update();
    }

    private void insertRecoveryCode(UUID id, UUID adminId, String hash, String createdAt) {
        int changed = jdbc.sql("""
                        insert into portfolio.totp_recovery_code(id, admin_id, code_hash, created_at)
                        values (:id, :adminId, :hash, cast(:createdAt as timestamptz))
                        """)
                .param("id", id)
                .param("adminId", adminId)
                .param("hash", hash)
                .param("createdAt", createdAt)
                .update();
        assertThat(changed).isOne();
    }

    private static AdminUser admin(UUID id, String username, Instant lastLoginAt) {
        return admin(id, username, lastLoginAt, AdminStatus.ACTIVE, 0, secret(1));
    }

    private static AdminUser admin(
            UUID id,
            String username,
            Instant lastLoginAt,
            AdminStatus status,
            long version,
            EncryptedTotpSecret encryptedSecret) {
        return new AdminUser(
                id,
                username,
                "stored-password-hash",
                status,
                encryptedSecret,
                lastLoginAt,
                version,
                CREATED_AT,
                UPDATED_AT);
    }

    private static EncryptedTotpSecret secret(int seed) {
        byte[] nonce = new byte[12];
        byte[] ciphertext = new byte[17];
        Arrays.fill(nonce, (byte) seed);
        Arrays.fill(ciphertext, (byte) (seed + 1));
        return new EncryptedTotpSecret(seed, nonce, ciphertext);
    }

    private static void assertRequiredAdminField(
            Object ignored, String message, RequiredField requiredField) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AdminUser(
                        requiredField == RequiredField.ID ? null : UUID.randomUUID(),
                        "admin",
                        "hash",
                        requiredField == RequiredField.STATUS ? null : AdminStatus.ACTIVE,
                        requiredField == RequiredField.SECRET ? null : secret(1),
                        null,
                        0,
                        requiredField == RequiredField.CREATED_AT ? null : CREATED_AT,
                        requiredField == RequiredField.UPDATED_AT ? null : UPDATED_AT))
                .withMessage(message);
    }

    private static PasswordEncoder fixedEncoder(String result) {
        return new PasswordEncoder() {
            @Override
            public String encode(CharSequence rawPassword) {
                return result;
            }

            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                return false;
            }
        };
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static void await(CountDownLatch latch, String failureMessage) throws InterruptedException {
        if (!latch.await(10, SECONDS)) {
            throw new IllegalStateException(failureMessage);
        }
    }

    private static void awaitWithoutChecked(CountDownLatch latch, String failureMessage) {
        try {
            await(latch, failureMessage);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test coordination was interrupted");
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

    private static void cleanupCommittedAdmin(UUID adminId) {
        JdbcClient migrator = migratorJdbc();
        migrator.sql("delete from portfolio.totp_recovery_code where admin_id=:adminId")
                .param("adminId", adminId)
                .update();
        migrator.sql("delete from portfolio.admin_user where id=:adminId")
                .param("adminId", adminId)
                .update();
    }

    private enum RequiredField {
        ID,
        STATUS,
        SECRET,
        CREATED_AT,
        UPDATED_AT
    }

    private static final class RecordingPasswordEncoder implements PasswordEncoder {
        private final List<String> encodedValues = new ArrayList<>();

        @Override
        public String encode(CharSequence rawPassword) {
            encodedValues.add(rawPassword.toString());
            return "encoded-" + encodedValues.size();
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            return false;
        }

        List<String> encodedValues() {
            return List.copyOf(encodedValues);
        }
    }

    private static final class MatchingPasswordEncoder implements PasswordEncoder {
        private final List<String> matches;
        private final List<String> visitedHashes = new ArrayList<>();

        private MatchingPasswordEncoder(List<String> matches) {
            this.matches = List.copyOf(matches);
        }

        @Override
        public String encode(CharSequence rawPassword) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            visitedHashes.add(encodedPassword);
            return matches.contains(encodedPassword);
        }

        List<String> visitedHashes() {
            return List.copyOf(visitedHashes);
        }
    }

    private static final class BarrierPasswordEncoder implements PasswordEncoder {
        private final PasswordEncoder delegate;
        private final CyclicBarrier barrier = new CyclicBarrier(2);
        private final AtomicInteger matchesCalls = new AtomicInteger();

        private BarrierPasswordEncoder(PasswordEncoder delegate) {
            this.delegate = delegate;
        }

        @Override
        public String encode(CharSequence rawPassword) {
            return delegate.encode(rawPassword);
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            boolean result = delegate.matches(rawPassword, encodedPassword);
            int invocation = matchesCalls.incrementAndGet();
            if (invocation <= 2) {
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    throw new IllegalStateException("test barrier failed", failure);
                }
            }
            return result;
        }

        int matchesCalls() {
            return matchesCalls.get();
        }
    }
}
