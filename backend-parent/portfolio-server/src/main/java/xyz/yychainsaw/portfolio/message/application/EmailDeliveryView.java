package xyz.yychainsaw.portfolio.message.application;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

public record EmailDeliveryView(
        String status,
        int attempts,
        Instant nextAttemptAt,
        Instant sentAt,
        Instant updatedAt,
        String errorCategory) {
    private static final Set<String> STATUSES =
            Set.of("PENDING", "SENDING", "SENT", "FAILED", "DEAD", "CANCELED");
    private static final Set<String> ERROR_CATEGORIES = Set.of(
            "SMTP_AUTHENTICATION_FAILED",
            "SMTP_CONNECTION_FAILED",
            "MESSAGE_PREPARATION_FAILED",
            "SMTP_DELIVERY_FAILED",
            "UNEXPECTED_DELIVERY_FAILURE",
            "DELIVERY_INTERRUPTED");

    public EmailDeliveryView {
        if (status == null || !STATUSES.contains(status)) {
            throw new IllegalArgumentException("email delivery status is invalid");
        }
        if (attempts < 0) {
            throw new IllegalArgumentException("email delivery attempts are invalid");
        }
        Objects.requireNonNull(nextAttemptAt, "email next-attempt timestamp is required");
        Objects.requireNonNull(updatedAt, "email updated timestamp is required");
        if ("SENT".equals(status) != (sentAt != null)) {
            throw new IllegalArgumentException("email delivery sent state is invalid");
        }
        if (errorCategory != null && !ERROR_CATEGORIES.contains(errorCategory)) {
            throw new IllegalArgumentException("email delivery error category is invalid");
        }
    }
}
