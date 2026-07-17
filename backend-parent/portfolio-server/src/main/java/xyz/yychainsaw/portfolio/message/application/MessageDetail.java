package xyz.yychainsaw.portfolio.message.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record MessageDetail(
        UUID id,
        String visitorName,
        String visitorEmail,
        String subject,
        String body,
        MessageStatus status,
        EmailDeliveryView email,
        Instant privacyAcceptedAt,
        Instant createdAt,
        Instant updatedAt,
        int version) {
    public MessageDetail {
        Objects.requireNonNull(id, "message id is required");
        requireBounded(visitorName, 100, "message visitor name is invalid");
        requireBounded(visitorEmail, 320, "message visitor email is invalid");
        requireBounded(subject, 160, "message subject is invalid");
        requireBounded(body, 5_000, "message body is invalid");
        Objects.requireNonNull(status, "message status is required");
        Objects.requireNonNull(email, "message email delivery is required");
        Objects.requireNonNull(
                privacyAcceptedAt, "message privacy acceptance timestamp is required");
        Objects.requireNonNull(createdAt, "message created timestamp is required");
        Objects.requireNonNull(updatedAt, "message updated timestamp is required");
        if (privacyAcceptedAt.isAfter(createdAt) || updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("message timestamp state is invalid");
        }
        if (version < 0) {
            throw new IllegalArgumentException("message version is invalid");
        }
    }

    @Override
    public String toString() {
        return "MessageDetail[id=" + id
                + ", status=" + status
                + ", email=" + email
                + ", privacyAcceptedAt=" + privacyAcceptedAt
                + ", createdAt=" + createdAt
                + ", updatedAt=" + updatedAt
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
