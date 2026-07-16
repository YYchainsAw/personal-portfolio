package xyz.yychainsaw.portfolio.auth.web;

import java.io.Serial;
import java.io.Serializable;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import xyz.yychainsaw.portfolio.auth.crypto.EncryptedTotpSecret;

public record PendingTotpEnrollment(
        UUID enrollmentId,
        UUID adminId,
        long adminVersion,
        UUID sessionMetadataId,
        EncryptedTotpSecret encryptedSecret,
        Instant issuedAt,
        Instant expiresAt,
        int failures) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    public static final String SESSION_KEY =
            PendingTotpEnrollment.class.getName() + ".pending";
    public static final Duration LIFETIME = Duration.ofMinutes(10);
    public static final int MAX_FAILURES = 5;

    public PendingTotpEnrollment {
        Objects.requireNonNull(enrollmentId, "enrollment id is required");
        Objects.requireNonNull(adminId, "admin id is required");
        if (adminVersion < 0) {
            throw new IllegalArgumentException("administrator version is invalid");
        }
        Objects.requireNonNull(sessionMetadataId, "session metadata id is required");
        encryptedSecret = snapshot(Objects.requireNonNull(
                encryptedSecret, "encrypted TOTP secret is required"));
        Objects.requireNonNull(issuedAt, "issued timestamp is required");
        Objects.requireNonNull(expiresAt, "expiry timestamp is required");
        Instant exactExpiry;
        try {
            exactExpiry = issuedAt.plus(LIFETIME);
        } catch (DateTimeException | ArithmeticException invalidLifetime) {
            throw new IllegalArgumentException("pending enrollment lifetime is invalid");
        }
        if (!expiresAt.equals(exactExpiry)) {
            throw new IllegalArgumentException("pending enrollment lifetime is invalid");
        }
        if (failures < 0 || failures > MAX_FAILURES) {
            throw new IllegalArgumentException("pending enrollment failure count is invalid");
        }
    }

    @Override
    public EncryptedTotpSecret encryptedSecret() {
        return snapshot(encryptedSecret);
    }

    public PendingTotpEnrollment failedAgain() {
        if (failures >= MAX_FAILURES) {
            throw new IllegalArgumentException("pending enrollment attempt budget is exhausted");
        }
        int incremented;
        try {
            incremented = Math.incrementExact(failures);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("pending enrollment failure count is invalid");
        }
        return new PendingTotpEnrollment(
                enrollmentId,
                adminId,
                adminVersion,
                sessionMetadataId,
                encryptedSecret,
                issuedAt,
                expiresAt,
                incremented);
    }

    @Override
    public String toString() {
        return "PendingTotpEnrollment[enrollmentId=<redacted>"
                + ", adminId=<redacted>"
                + ", adminVersion=" + adminVersion
                + ", sessionMetadataId=<redacted>"
                + ", encryptedSecret=<redacted>"
                + ", issuedAt=" + issuedAt
                + ", expiresAt=" + expiresAt
                + ", failures=" + failures + ']';
    }

    private static EncryptedTotpSecret snapshot(EncryptedTotpSecret secret) {
        return new EncryptedTotpSecret(
                secret.keyVersion(), secret.nonce(), secret.ciphertext());
    }
}
