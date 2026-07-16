package xyz.yychainsaw.portfolio.auth.web;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PendingSecondFactor(
        UUID challengeId,
        UUID adminId,
        long adminVersion,
        Instant issuedAt,
        int failures) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    public static final String SESSION_KEY = PendingSecondFactor.class.getName() + ".pending";

    public PendingSecondFactor {
        Objects.requireNonNull(challengeId, "challenge id is required");
        Objects.requireNonNull(adminId, "admin id is required");
        Objects.requireNonNull(issuedAt, "issuance timestamp is required");
        if (adminVersion < 0) {
            throw new IllegalArgumentException("admin version must not be negative");
        }
        if (failures < 0) {
            throw new IllegalArgumentException("failure count must not be negative");
        }
    }

    public PendingSecondFactor failedAgain() {
        return new PendingSecondFactor(
                challengeId, adminId, adminVersion, issuedAt, Math.incrementExact(failures));
    }

    @Override
    public String toString() {
        return "PendingSecondFactor[challengeId=<redacted>, adminId=<redacted>"
                + ", adminVersion=" + adminVersion
                + ", issuedAt=" + issuedAt
                + ", failures=" + failures + ']';
    }
}
