package xyz.yychainsaw.portfolio.auth.cli;

import java.nio.CharBuffer;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.yychainsaw.portfolio.audit.AuditCommand;
import xyz.yychainsaw.portfolio.audit.AuditOutcome;
import xyz.yychainsaw.portfolio.audit.AuditService;
import xyz.yychainsaw.portfolio.auth.RecoveryCodeService;
import xyz.yychainsaw.portfolio.auth.crypto.EncryptedTotpSecret;
import xyz.yychainsaw.portfolio.auth.crypto.PasswordPolicy;
import xyz.yychainsaw.portfolio.auth.crypto.RecoveryCodeGenerator;
import xyz.yychainsaw.portfolio.auth.crypto.TotpService;
import xyz.yychainsaw.portfolio.auth.model.AdminStatus;
import xyz.yychainsaw.portfolio.auth.model.AdminUser;
import xyz.yychainsaw.portfolio.auth.persistence.AdminUserRepository;
import xyz.yychainsaw.portfolio.auth.persistence.RecoveryCodeRepository;
import xyz.yychainsaw.portfolio.common.error.DomainException;

@Service
public final class AdminBootstrapService {
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._-]{3,64}");
    private static final Pattern RECOVERY_CODE =
            Pattern.compile("[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}");
    private static final String USERNAME_ERROR =
            "账号必须为 3–64 位 ASCII 字母、数字、点、下划线或连字符";

    private final AdminUserRepository admins;
    private final RecoveryCodeRepository recoveryCodes;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final TotpService totp;
    private final RecoveryCodeGenerator recoveryCodeGenerator;
    private final RecoveryCodeService recoveryCodeService;
    private final AuditService audit;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public AdminBootstrapService(
            AdminUserRepository admins,
            RecoveryCodeRepository recoveryCodes,
            PasswordEncoder passwordEncoder,
            PasswordPolicy passwordPolicy,
            TotpService totp,
            RecoveryCodeGenerator recoveryCodeGenerator,
            RecoveryCodeService recoveryCodeService,
            AuditService audit,
            TransactionTemplate transactions,
            Clock clock) {
        this.admins = require(admins, "administrator repository is required");
        this.recoveryCodes = require(
                recoveryCodes, "recovery-code repository is required");
        this.passwordEncoder = require(passwordEncoder, "password encoder is required");
        this.passwordPolicy = require(passwordPolicy, "password policy is required");
        this.totp = require(totp, "TOTP service is required");
        this.recoveryCodeGenerator = require(
                recoveryCodeGenerator, "recovery-code generator is required");
        this.recoveryCodeService = require(
                recoveryCodeService, "recovery-code service is required");
        this.audit = require(audit, "audit service is required");
        this.transactions = require(transactions, "transaction template is required");
        if (transactions.getPropagationBehavior()
                != TransactionDefinition.PROPAGATION_REQUIRED) {
            throw new IllegalArgumentException(
                    "bootstrap transaction template must use REQUIRED propagation");
        }
        this.clock = require(clock, "clock is required");
    }

    public Enrollment prepare(String username, char[] password) {
        if (password == null) {
            throw new IllegalArgumentException("password is required");
        }
        try {
            if (admins.count() != 0) {
                throw alreadyInitialized();
            }
            if (username == null || !USERNAME.matcher(username).matches()) {
                throw invalidUsername();
            }

            passwordPolicy.requireStrong(CharBuffer.wrap(password));
            String passwordHash = encodePassword(password);
            wipe(password);

            UUID adminId = UUID.randomUUID();
            TotpService.Enrollment totpEnrollment = totp.beginEnrollment(adminId, username);
            if (totpEnrollment == null) {
                throw new IllegalStateException("TOTP provider returned an invalid enrollment");
            }
            List<String> plaintextCodes = immutableSnapshot(
                    recoveryCodeGenerator.generate(10),
                    "recovery-code generator returned an invalid set");
            List<String> codeHashes = immutableSnapshot(
                    recoveryCodeService.hashAll(plaintextCodes),
                    "recovery-code service returned an invalid hash set");

            return new Enrollment(
                    adminId,
                    username,
                    passwordHash,
                    totpEnrollment.plaintextSecret(),
                    totpEnrollment.encryptedSecret(),
                    totpEnrollment.provisioningUri(),
                    plaintextCodes,
                    codeHashes);
        } finally {
            wipe(password);
        }
    }

    public void complete(Enrollment enrollment, char[] totpCode) {
        if (totpCode == null) {
            throw new IllegalArgumentException("TOTP code is required");
        }
        try {
            if (enrollment == null) {
                throw new IllegalArgumentException("enrollment is required");
            }
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                throw new IllegalStateException(
                        "administrator bootstrap requires no ambient transaction");
            }

            synchronized (enrollment) {
                enrollment.requirePrepared();
                if (!isSixAsciiDigits(totpCode)) {
                    throw invalidTotp();
                }

                boolean verified = verifyEnrollment(enrollment, totpCode);
                wipe(totpCode);
                if (!verified) {
                    throw invalidTotp();
                }

                Instant now = clock.instant();
                transactions.executeWithoutResult(status -> persist(enrollment, now));
                enrollment.markCommitted();
            }
        } finally {
            wipe(totpCode);
        }
    }

    private String encodePassword(char[] password) {
        String hash;
        try {
            hash = passwordEncoder.encode(CharBuffer.wrap(password));
        } catch (RuntimeException providerFailure) {
            throw new IllegalStateException("password hashing failed");
        }
        if (!validBoundedText(hash, 255)) {
            throw new IllegalStateException("password provider returned an invalid hash");
        }
        return hash;
    }

    private boolean verifyEnrollment(Enrollment enrollment, char[] totpCode) {
        String shortLivedCode = new String(totpCode);
        return totp.verifyEnrollment(enrollment.plaintextTotpSecret, shortLivedCode);
    }

    private void persist(Enrollment enrollment, Instant now) {
        AdminUser admin = new AdminUser(
                enrollment.adminId,
                enrollment.username,
                enrollment.passwordHash,
                AdminStatus.ACTIVE,
                enrollment.encryptedTotpSecret,
                null,
                0,
                now,
                now);
        try {
            admins.insert(admin);
        } catch (DuplicateKeyException duplicate) {
            throw alreadyInitialized();
        }
        recoveryCodes.replace(enrollment.adminId, enrollment.recoveryCodeHashes);
        audit.record(new AuditCommand(
                enrollment.adminId,
                "ADMIN_BOOTSTRAPPED",
                "ADMIN",
                enrollment.adminId.toString(),
                AuditOutcome.SUCCESS,
                null,
                Map.of("channel", "LOCAL_CLI")));
    }

    private static boolean isSixAsciiDigits(char[] code) {
        if (code.length != 6) {
            return false;
        }
        for (char character : code) {
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }

    private static DomainException alreadyInitialized() {
        return new DomainException(
                "ADMIN_ALREADY_INITIALIZED", HttpStatus.CONFLICT, Map.of());
    }

    private static DomainException invalidUsername() {
        return new DomainException(
                "ADMIN_USERNAME_INVALID",
                HttpStatus.UNPROCESSABLE_ENTITY,
                Map.of("username", USERNAME_ERROR));
    }

    private static DomainException invalidTotp() {
        return new DomainException(
                "INVALID_BOOTSTRAP_TOTP", HttpStatus.UNAUTHORIZED, Map.of());
    }

    private static boolean validBoundedText(String value, int maximumLength) {
        return value != null && !value.isBlank() && value.length() <= maximumLength;
    }

    private static <T> T require(T value, String message) {
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private static <T> List<T> immutableSnapshot(List<T> values, String message) {
        if (values == null) {
            throw new IllegalStateException(message);
        }
        try {
            return List.copyOf(values);
        } catch (RuntimeException invalidValues) {
            throw new IllegalStateException(message);
        }
    }

    private static void wipe(char[] value) {
        if (value != null) {
            Arrays.fill(value, '\0');
        }
    }

    public static final class Enrollment implements AutoCloseable {
        private final UUID adminId;
        private final String username;

        private String passwordHash;
        private String plaintextTotpSecret;
        private EncryptedTotpSecret encryptedTotpSecret;
        private String provisioningUri;
        private List<String> plaintextRecoveryCodes;
        private List<String> recoveryCodeHashes;
        private State state;

        private Enrollment(
                UUID adminId,
                String username,
                String passwordHash,
                String plaintextTotpSecret,
                EncryptedTotpSecret encryptedTotpSecret,
                String provisioningUri,
                List<String> plaintextRecoveryCodes,
                List<String> recoveryCodeHashes) {
            this.adminId = requireEnrollmentValue(adminId, "administrator ID is required");
            if (username == null || !USERNAME.matcher(username).matches()) {
                throw new IllegalArgumentException("administrator username is invalid");
            }
            this.username = username;
            if (!validBoundedText(passwordHash, 255)) {
                throw new IllegalArgumentException("password hash is invalid");
            }
            this.passwordHash = passwordHash;
            if (!validBoundedText(plaintextTotpSecret, 256)) {
                throw new IllegalArgumentException("TOTP plaintext is invalid");
            }
            this.plaintextTotpSecret = plaintextTotpSecret;
            EncryptedTotpSecret encrypted = requireEnrollmentValue(
                    encryptedTotpSecret, "encrypted TOTP secret is required");
            this.encryptedTotpSecret = new EncryptedTotpSecret(
                    encrypted.keyVersion(), encrypted.nonce(), encrypted.ciphertext());
            if (!validBoundedText(provisioningUri, 2048)
                    || !provisioningUri.startsWith("otpauth://totp/")) {
                throw new IllegalArgumentException("TOTP provisioning URI is invalid");
            }
            this.provisioningUri = provisioningUri;
            this.plaintextRecoveryCodes = validatedRecoveryCodes(plaintextRecoveryCodes);
            this.recoveryCodeHashes = validatedRecoveryHashes(recoveryCodeHashes);
            this.state = State.PREPARED;
        }

        public UUID adminId() {
            return adminId;
        }

        public String username() {
            return username;
        }

        public synchronized String provisioningUri() {
            requirePrepared();
            return provisioningUri;
        }

        public synchronized List<String> takePlaintextRecoveryCodes() {
            if (state != State.COMMITTED || plaintextRecoveryCodes == null) {
                throw new IllegalStateException(
                        "bootstrap recovery codes are not available");
            }
            List<String> snapshot = plaintextRecoveryCodes;
            plaintextRecoveryCodes = null;
            state = State.CONSUMED;
            return snapshot;
        }

        @Override
        public synchronized void close() {
            if (state == State.CLOSED) {
                return;
            }
            clearAllSensitiveReferences();
            state = State.CLOSED;
        }

        @Override
        public synchronized String toString() {
            return "Enrollment[adminId=" + adminId
                    + ", username=" + username
                    + ", passwordHash=<redacted>"
                    + ", plaintextTotpSecret=<redacted>"
                    + ", encryptedTotpSecret=<redacted>"
                    + ", provisioningUri=<redacted>"
                    + ", plaintextRecoveryCodes=<redacted>"
                    + ", recoveryCodeHashes=<redacted>"
                    + ", state=" + state
                    + ']';
        }

        private void requirePrepared() {
            if (state != State.PREPARED) {
                throw new IllegalStateException("bootstrap enrollment is not prepared");
            }
        }

        private void markCommitted() {
            requirePrepared();
            passwordHash = null;
            plaintextTotpSecret = null;
            encryptedTotpSecret = null;
            provisioningUri = null;
            recoveryCodeHashes = null;
            state = State.COMMITTED;
        }

        private void clearAllSensitiveReferences() {
            passwordHash = null;
            plaintextTotpSecret = null;
            encryptedTotpSecret = null;
            provisioningUri = null;
            plaintextRecoveryCodes = null;
            recoveryCodeHashes = null;
        }

        private static List<String> validatedRecoveryCodes(List<String> values) {
            if (values == null || values.size() != 10) {
                throw new IllegalArgumentException("recovery-code set is invalid");
            }
            Set<String> distinct = new HashSet<>();
            for (String value : values) {
                if (value == null
                        || !RECOVERY_CODE.matcher(value).matches()
                        || !distinct.add(value)) {
                    throw new IllegalArgumentException("recovery-code set is invalid");
                }
            }
            return List.copyOf(values);
        }

        private static List<String> validatedRecoveryHashes(List<String> values) {
            if (values == null || values.size() != 10) {
                throw new IllegalArgumentException("recovery-code hash set is invalid");
            }
            Set<String> distinct = new HashSet<>();
            for (String value : values) {
                if (!validBoundedText(value, 255) || !distinct.add(value)) {
                    throw new IllegalArgumentException("recovery-code hash set is invalid");
                }
            }
            return List.copyOf(values);
        }

        private static <T> T requireEnrollmentValue(T value, String message) {
            if (value == null) {
                throw new IllegalArgumentException(message);
            }
            return value;
        }

        private enum State {
            PREPARED,
            COMMITTED,
            CONSUMED,
            CLOSED
        }
    }
}
