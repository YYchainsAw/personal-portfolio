package xyz.yychainsaw.portfolio.auth.cli;

import java.nio.CharBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
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
import xyz.yychainsaw.portfolio.auth.model.AdminUser;
import xyz.yychainsaw.portfolio.auth.persistence.AdminUserRepository;
import xyz.yychainsaw.portfolio.auth.persistence.RecoveryCodeRepository;
import xyz.yychainsaw.portfolio.auth.session.AdminSessionService;
import xyz.yychainsaw.portfolio.common.error.DomainException;

@Service
public final class AdminRecoveryService {
    private static final int RECOVERY_CODE_COUNT = 10;
    private static final Pattern RECOVERY_CODE =
            Pattern.compile("[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}");
    private static final String FAILURE = "administrator recovery could not be completed";
    private static final Map<String, String> PASSWORD_POLICY_FIELDS = Map.of(
            "password", "\u5bc6\u7801\u987b\u4e3a 14\u2013128 \u4f4d\uff0c\u5e76\u5305\u542b"
                    + "\u5927\u5c0f\u5199\u5b57\u6bcd\u3001\u6570\u5b57\u548c\u7b26\u53f7");

    private final DatabaseRestorePointService restorePoints;
    private final AdminUserRepository admins;
    private final RecoveryCodeRepository recoveryCodes;
    private final RecoveryCodeService recoveryCodeService;
    private final RecoveryCodeGenerator recoveryCodeGenerator;
    private final PasswordPolicy passwordPolicy;
    private final PasswordEncoder passwordEncoder;
    private final TotpService totp;
    private final AdminSessionService sessions;
    private final AuditService audit;
    private final TransactionTemplate transactions;

    public AdminRecoveryService(
            DatabaseRestorePointService restorePoints,
            AdminUserRepository admins,
            RecoveryCodeRepository recoveryCodes,
            RecoveryCodeService recoveryCodeService,
            RecoveryCodeGenerator recoveryCodeGenerator,
            PasswordPolicy passwordPolicy,
            PasswordEncoder passwordEncoder,
            TotpService totp,
            AdminSessionService sessions,
            AuditService audit,
            TransactionTemplate transactions) {
        this.restorePoints = require(restorePoints, "restore-point service is required");
        this.admins = require(admins, "administrator repository is required");
        this.recoveryCodes = require(recoveryCodes, "recovery-code repository is required");
        this.recoveryCodeService = require(
                recoveryCodeService, "recovery-code service is required");
        this.recoveryCodeGenerator = require(
                recoveryCodeGenerator, "recovery-code generator is required");
        this.passwordPolicy = require(passwordPolicy, "password policy is required");
        this.passwordEncoder = require(passwordEncoder, "password encoder is required");
        this.totp = require(totp, "TOTP service is required");
        this.sessions = require(sessions, "session service is required");
        this.audit = require(audit, "audit service is required");
        this.transactions = require(transactions, "transaction template is required");
        if (transactions.getPropagationBehavior()
                != TransactionDefinition.PROPAGATION_REQUIRED) {
            throw new IllegalArgumentException(
                    "recovery transaction template must use REQUIRED propagation");
        }
    }

    public Enrollment prepare(char[] newPassword) {
        if (newPassword == null) {
            throw new IllegalArgumentException("password is required");
        }
        try {
            requireNoAmbientTransaction();

            String passwordHash;
            try {
                requireStrongPassword(newPassword);
                passwordHash = encodePassword(newPassword);
            } finally {
                wipe(newPassword);
            }

            AdminUser admin;
            DatabaseRestorePointService.RestorePoint restorePoint;
            try {
                admin = require(admins.requireOnlyAdmin(), "administrator is required");
                restorePoint = require(
                        restorePoints.create(), "restore point is required");
            } catch (RuntimeException failure) {
                throw maintenanceFailure();
            }

            try {
                TotpService.Enrollment totpEnrollment = require(
                        totp.beginEnrollment(admin.id(), admin.username()),
                        "TOTP provider returned an invalid enrollment");
                List<String> plaintextCodes = validatedRecoveryCodes(
                        recoveryCodeGenerator.generate(RECOVERY_CODE_COUNT));
                List<String> codeHashes = validatedRecoveryHashes(
                        recoveryCodeService.hashAll(plaintextCodes));
                return new Enrollment(
                        admin.id(),
                        admin.version(),
                        passwordHash,
                        totpEnrollment.plaintextSecret(),
                        totpEnrollment.encryptedSecret(),
                        totpEnrollment.provisioningUri(),
                        restorePoint.sha256(),
                        plaintextCodes,
                        codeHashes);
            } catch (RuntimeException failure) {
                throw maintenanceFailure();
            }
        } finally {
            wipe(newPassword);
        }
    }

    public void complete(Enrollment enrollment, char[] totpCode) {
        if (totpCode == null) {
            throw new IllegalArgumentException("TOTP code is required");
        }
        try {
            requireNoAmbientTransaction();
            if (enrollment == null) {
                throw new IllegalArgumentException("enrollment is required");
            }

            synchronized (enrollment) {
                enrollment.requirePrepared();
                if (!isSixAsciiDigits(totpCode)) {
                    throw invalidTotp();
                }

                boolean verified;
                try {
                    String shortLivedCode = new String(totpCode);
                    verified = totp.verifyEnrollment(
                            enrollment.plaintextTotpSecret, shortLivedCode);
                } catch (RuntimeException failure) {
                    throw maintenanceFailure();
                } finally {
                    wipe(totpCode);
                }
                if (!verified) {
                    throw invalidTotp();
                }

                try {
                    transactions.executeWithoutResult(status -> persist(enrollment));
                } catch (CasConflict conflict) {
                    throw versionConflict();
                } catch (RuntimeException failure) {
                    throw maintenanceFailure();
                }
                enrollment.markCommitted();
            }

            sessions.deleteAllSpringSessionsBestEffort();
        } finally {
            wipe(totpCode);
        }
    }

    private void persist(Enrollment enrollment) {
        OptionalLong changed = admins.replaceCredentialsIfVersion(
                enrollment.adminId,
                enrollment.expectedVersion,
                enrollment.passwordHash,
                enrollment.encryptedTotpSecret);
        long newVersion = changed.orElseThrow(CasConflict::new);
        requireAdvancedVersion(enrollment.expectedVersion, newVersion);

        recoveryCodes.replace(enrollment.adminId, enrollment.recoveryCodeHashes);
        sessions.markAllSessionsRevokedInCurrentTransaction(
                enrollment.adminId, "ADMIN_RECOVERY");
        audit.record(new AuditCommand(
                enrollment.adminId,
                "ADMIN_RECOVERED",
                "ADMIN",
                enrollment.adminId.toString(),
                AuditOutcome.SUCCESS,
                null,
                Map.of(
                        "channel", "LOCAL_CLI",
                        "backupSha256", enrollment.backupSha256)));
    }

    private String encodePassword(char[] password) {
        String encoded;
        try {
            encoded = passwordEncoder.encode(CharBuffer.wrap(password));
        } catch (RuntimeException failure) {
            throw maintenanceFailure();
        }
        if (!validBoundedText(encoded, 255)) {
            throw maintenanceFailure();
        }
        return encoded;
    }

    private void requireStrongPassword(char[] password) {
        try {
            passwordPolicy.requireStrong(CharBuffer.wrap(password));
        } catch (DomainException domain) {
            if ("PASSWORD_POLICY_VIOLATION".equals(domain.code())
                    && domain.status() == HttpStatus.UNPROCESSABLE_ENTITY
                    && PASSWORD_POLICY_FIELDS.equals(domain.fieldErrors())) {
                throw new DomainException(
                        "PASSWORD_POLICY_VIOLATION",
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        PASSWORD_POLICY_FIELDS);
            }
            throw maintenanceFailure();
        } catch (RuntimeException failure) {
            throw maintenanceFailure();
        }
    }

    private static void requireNoAmbientTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "administrator recovery requires no ambient transaction");
        }
    }

    private static void requireAdvancedVersion(long previous, long current) {
        long expected;
        try {
            expected = Math.incrementExact(previous);
        } catch (ArithmeticException invalidVersion) {
            throw maintenanceFailure();
        }
        if (current != expected) {
            throw maintenanceFailure();
        }
    }

    private static List<String> validatedRecoveryCodes(List<String> values) {
        if (values == null || values.size() != RECOVERY_CODE_COUNT) {
            throw maintenanceFailure();
        }
        Set<String> distinct = new HashSet<>();
        for (String value : values) {
            if (value == null
                    || !RECOVERY_CODE.matcher(value).matches()
                    || !distinct.add(value)) {
                throw maintenanceFailure();
            }
        }
        return List.copyOf(values);
    }

    private static List<String> validatedRecoveryHashes(List<String> values) {
        if (values == null || values.size() != RECOVERY_CODE_COUNT) {
            throw maintenanceFailure();
        }
        Set<String> distinct = new HashSet<>();
        for (String value : values) {
            if (!validBoundedText(value, 255) || !distinct.add(value)) {
                throw maintenanceFailure();
            }
        }
        return List.copyOf(values);
    }

    private static boolean isSixAsciiDigits(char[] code) {
        if (code.length != 6) {
            return false;
        }
        for (char value : code) {
            if (value < '0' || value > '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean validBoundedText(String value, int maximumLength) {
        return value != null && !value.isBlank() && value.length() <= maximumLength;
    }

    private static DomainException invalidTotp() {
        return new DomainException(
                "INVALID_RECOVERY_TOTP", HttpStatus.UNAUTHORIZED, Map.of());
    }

    private static DomainException versionConflict() {
        return new DomainException(
                "AUTH_VERSION_CONFLICT", HttpStatus.CONFLICT, Map.of());
    }

    private static IllegalStateException maintenanceFailure() {
        return new IllegalStateException(FAILURE);
    }

    private static final class CasConflict extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private CasConflict() {
            super(null, null, false, false);
        }
    }

    private static <T> T require(T value, String message) {
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private static void wipe(char[] value) {
        if (value != null) {
            Arrays.fill(value, '\0');
        }
    }

    public static final class Enrollment implements AutoCloseable {
        private final UUID adminId;
        private final long expectedVersion;

        private String passwordHash;
        private String plaintextTotpSecret;
        private EncryptedTotpSecret encryptedTotpSecret;
        private String provisioningUri;
        private String backupSha256;
        private List<String> plaintextRecoveryCodes;
        private List<String> recoveryCodeHashes;
        private State state;

        private Enrollment(
                UUID adminId,
                long expectedVersion,
                String passwordHash,
                String plaintextTotpSecret,
                EncryptedTotpSecret encryptedTotpSecret,
                String provisioningUri,
                String backupSha256,
                List<String> plaintextRecoveryCodes,
                List<String> recoveryCodeHashes) {
            this.adminId = require(adminId, "administrator ID is required");
            if (expectedVersion < 0) {
                throw new IllegalArgumentException("administrator version is invalid");
            }
            this.expectedVersion = expectedVersion;
            if (!validBoundedText(passwordHash, 255)) {
                throw new IllegalArgumentException("password hash is invalid");
            }
            this.passwordHash = passwordHash;
            if (!validBoundedText(plaintextTotpSecret, 256)) {
                throw new IllegalArgumentException("TOTP plaintext is invalid");
            }
            this.plaintextTotpSecret = plaintextTotpSecret;
            EncryptedTotpSecret encrypted = require(
                    encryptedTotpSecret, "encrypted TOTP secret is required");
            this.encryptedTotpSecret = new EncryptedTotpSecret(
                    encrypted.keyVersion(), encrypted.nonce(), encrypted.ciphertext());
            if (!validBoundedText(provisioningUri, 2048)
                    || !provisioningUri.startsWith(TotpService.PROVISIONING_URI_PREFIX)) {
                throw new IllegalArgumentException("TOTP provisioning URI is invalid");
            }
            this.provisioningUri = provisioningUri;
            if (backupSha256 == null || !backupSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("backup checksum is invalid");
            }
            this.backupSha256 = backupSha256;
            this.plaintextRecoveryCodes = validatedRecoveryCodes(plaintextRecoveryCodes);
            this.recoveryCodeHashes = validatedRecoveryHashes(recoveryCodeHashes);
            this.state = State.PREPARED;
        }

        public synchronized String provisioningUri() {
            requirePrepared();
            return provisioningUri;
        }

        public synchronized String backupSha256() {
            requirePrepared();
            return backupSha256;
        }

        public synchronized List<String> takePlaintextRecoveryCodes() {
            if (state != State.COMMITTED || plaintextRecoveryCodes == null) {
                throw new IllegalStateException("recovery codes are not available");
            }
            List<String> handoff = plaintextRecoveryCodes;
            plaintextRecoveryCodes = null;
            state = State.CONSUMED;
            return handoff;
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
            return "Enrollment[adminId=<redacted>"
                    + ", expectedVersion=<redacted>"
                    + ", passwordHash=<redacted>"
                    + ", plaintextTotpSecret=<redacted>"
                    + ", encryptedTotpSecret=<redacted>"
                    + ", provisioningUri=<redacted>"
                    + ", backupSha256=<redacted>"
                    + ", plaintextRecoveryCodes=<redacted>"
                    + ", recoveryCodeHashes=<redacted>"
                    + ", state=" + state + ']';
        }

        private void requirePrepared() {
            if (state != State.PREPARED) {
                throw new IllegalStateException("recovery enrollment is not prepared");
            }
        }

        private void markCommitted() {
            requirePrepared();
            passwordHash = null;
            plaintextTotpSecret = null;
            encryptedTotpSecret = null;
            provisioningUri = null;
            backupSha256 = null;
            recoveryCodeHashes = null;
            state = State.COMMITTED;
        }

        private void clearAllSensitiveReferences() {
            passwordHash = null;
            plaintextTotpSecret = null;
            encryptedTotpSecret = null;
            provisioningUri = null;
            backupSha256 = null;
            plaintextRecoveryCodes = null;
            recoveryCodeHashes = null;
        }

        private enum State {
            PREPARED,
            COMMITTED,
            CONSUMED,
            CLOSED
        }
    }
}
