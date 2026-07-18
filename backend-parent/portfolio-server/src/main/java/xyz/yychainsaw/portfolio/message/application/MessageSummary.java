package xyz.yychainsaw.portfolio.message.application;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record MessageSummary(
        UUID id,
        String visitorName,
        String visitorEmail,
        String subject,
        MessageStatus status,
        String emailStatus,
        Instant createdAt,
        int version) {
    private static final Set<String> EMAIL_STATUSES =
            Set.of("PENDING", "SENDING", "SENT", "FAILED", "DEAD", "CANCELED");

    public MessageSummary {
        Objects.requireNonNull(id, "message id is required");
        requireBounded(visitorName, 100, "message visitor name is invalid");
        requireBounded(visitorEmail, 320, "message visitor email is invalid");
        requireBounded(subject, 160, "message subject is invalid");
        Objects.requireNonNull(status, "message status is required");
        if (emailStatus == null || !EMAIL_STATUSES.contains(emailStatus)) {
            throw new IllegalArgumentException("message email status is invalid");
        }
        Objects.requireNonNull(createdAt, "message created timestamp is required");
        if (version < 0) {
            throw new IllegalArgumentException("message version is invalid");
        }
    }

    @Override
    public String toString() {
        return "MessageSummary[id=" + id
                + ", status=" + status
                + ", emailStatus=" + emailStatus
                + ", createdAt=" + createdAt
                + ", version=" + version
                + ", pii=<redacted>]";
    }

    private static void requireBounded(String value, int maximum, String message) {
        if (value == null
                || value.isBlank()
                || value.codePointCount(0, value.length()) > maximum) {
            throw new IllegalArgumentException(message);
        }
    }
}
