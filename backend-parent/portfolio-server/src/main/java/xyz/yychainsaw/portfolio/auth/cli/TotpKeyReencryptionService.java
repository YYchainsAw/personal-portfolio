package xyz.yychainsaw.portfolio.auth.cli;

import java.util.Map;
import java.util.OptionalLong;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.yychainsaw.portfolio.audit.AuditCommand;
import xyz.yychainsaw.portfolio.audit.AuditOutcome;
import xyz.yychainsaw.portfolio.audit.AuditService;
import xyz.yychainsaw.portfolio.auth.crypto.EncryptedTotpSecret;
import xyz.yychainsaw.portfolio.auth.crypto.TotpEnvelopeCrypto;
import xyz.yychainsaw.portfolio.auth.model.AdminUser;
import xyz.yychainsaw.portfolio.auth.persistence.AdminUserRepository;
import xyz.yychainsaw.portfolio.common.error.DomainException;

@Service
public final class TotpKeyReencryptionService {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final String MAINTENANCE_ERROR =
            "TOTP key re-encryption could not be completed";

    private final DatabaseRestorePointService restorePoints;
    private final AdminUserRepository admins;
    private final TotpEnvelopeCrypto crypto;
    private final AuditService audit;
    private final TransactionTemplate transactions;

    public TotpKeyReencryptionService(
            DatabaseRestorePointService restorePoints,
            AdminUserRepository admins,
            TotpEnvelopeCrypto crypto,
            AuditService audit,
            TransactionTemplate transactions) {
        this.restorePoints = require(
                restorePoints, "database restore-point service is required");
        this.admins = require(admins, "administrator repository is required");
        this.crypto = require(crypto, "TOTP envelope crypto is required");
        this.audit = require(audit, "audit service is required");
        this.transactions = require(transactions, "transaction template is required");
        if (transactions.getPropagationBehavior()
                != TransactionDefinition.PROPAGATION_REQUIRED) {
            throw new IllegalArgumentException(
                    "TOTP key re-encryption transaction template must use REQUIRED propagation");
        }
    }

    public TotpKeyReencryptionResult reencryptToActiveKey() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "TOTP key re-encryption requires no ambient transaction");
        }

        try {
            return performReencryption();
        } catch (CasConflict conflict) {
            throw versionConflict();
        } catch (RuntimeException failure) {
            throw maintenanceFailure();
        }
    }

    private TotpKeyReencryptionResult performReencryption() {
        AdminUser admin = admins.requireOnlyAdmin();
        int previousKeyVersion = admin.totpSecret().keyVersion();
        int activeKeyVersion = crypto.activeKeyVersion();
        if (activeKeyVersion < 1) {
            throw new IllegalStateException("active TOTP key version is invalid");
        }
        if (previousKeyVersion == activeKeyVersion) {
            return new TotpKeyReencryptionResult(
                    false, activeKeyVersion, activeKeyVersion, null);
        }

        DatabaseRestorePointService.RestorePoint restorePoint = restorePoints.create();
        if (restorePoint == null || !isCanonicalSha256(restorePoint.sha256())) {
            throw new IllegalStateException("database restore point is invalid");
        }

        EncryptedTotpSecret replacement = crypto.reencrypt(admin.id(), admin.totpSecret());
        if (replacement == null || replacement.keyVersion() != activeKeyVersion) {
            throw new IllegalStateException("TOTP crypto returned an invalid replacement");
        }

        String backupSha256 = restorePoint.sha256();
        transactions.executeWithoutResult(status -> persist(
                admin,
                previousKeyVersion,
                activeKeyVersion,
                replacement,
                backupSha256));

        return new TotpKeyReencryptionResult(
                true, previousKeyVersion, activeKeyVersion, backupSha256);
    }

    private void persist(
            AdminUser admin,
            int previousKeyVersion,
            int activeKeyVersion,
            EncryptedTotpSecret replacement,
            String backupSha256) {
        long requiredNewVersion;
        try {
            requiredNewVersion = Math.addExact(admin.version(), 1L);
        } catch (ArithmeticException overflow) {
            throw new IllegalStateException("administrator version cannot be incremented");
        }

        OptionalLong updatedVersion = admins.updateTotpIfVersion(
                admin.id(), admin.version(), previousKeyVersion, replacement);
        if (updatedVersion.isEmpty()) {
            throw new CasConflict();
        }
        if (updatedVersion.getAsLong() != requiredNewVersion) {
            throw new IllegalStateException(
                    "administrator CAS returned an invalid version");
        }

        audit.record(new AuditCommand(
                admin.id(),
                "TOTP_KEY_REENCRYPTED",
                "ADMIN",
                admin.id().toString(),
                AuditOutcome.SUCCESS,
                null,
                Map.of(
                        "fromKeyVersion", Integer.toString(previousKeyVersion),
                        "toKeyVersion", Integer.toString(activeKeyVersion),
                        "channel", "LOCAL_CLI",
                        "backupSha256", backupSha256)));
    }

    private static boolean isCanonicalSha256(String value) {
        return value != null && SHA_256.matcher(value).matches();
    }

    private static DomainException versionConflict() {
        return new DomainException(
                "AUTH_VERSION_CONFLICT", HttpStatus.CONFLICT, Map.of());
    }

    private static IllegalStateException maintenanceFailure() {
        return new IllegalStateException(MAINTENANCE_ERROR);
    }

    private static <T> T require(T value, String message) {
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private static final class CasConflict extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
