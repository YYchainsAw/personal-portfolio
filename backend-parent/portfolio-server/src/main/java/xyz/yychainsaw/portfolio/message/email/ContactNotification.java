package xyz.yychainsaw.portfolio.message.email;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ContactNotification(
        UUID outboxId,
        String stableMessageId,
        String to,
        String replyTo,
        String visitorName,
        String subject,
        String body,
        Instant receivedAt) {
    public ContactNotification {
        Objects.requireNonNull(outboxId, "email outbox id is required");
        requireBounded(stableMessageId, 255, "stable message ID is invalid");
        requireBounded(to, 320, "notification recipient is invalid");
        requireBounded(replyTo, 320, "notification reply-to is invalid");
        requireBounded(visitorName, 100, "notification visitor name is invalid");
        requireBounded(subject, 160, "notification subject is invalid");
        requireBounded(body, 5_000, "notification body is invalid");
        Objects.requireNonNull(receivedAt, "notification received timestamp is required");
    }

    @Override
    public String toString() {
        return "ContactNotification[outboxId=" + outboxId + ", pii=<redacted>]";
    }

    private static void requireBounded(String value, int maximum, String message) {
        if (value == null
                || value.isBlank()
                || value.codePointCount(0, value.length()) > maximum) {
            throw new IllegalArgumentException(message);
        }
    }
}
