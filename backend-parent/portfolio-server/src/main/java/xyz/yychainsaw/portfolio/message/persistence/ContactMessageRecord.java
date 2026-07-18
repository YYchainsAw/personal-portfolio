package xyz.yychainsaw.portfolio.message.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public record ContactMessageRecord(
        UUID id,
        String visitorName,
        String visitorEmail,
        String subject,
        String body,
        String status,
        String dedupeKey,
        Instant privacyAcceptedAt,
        int version,
        Instant createdAt,
        Instant updatedAt) {
    private static final Set<String> STATUSES =
            Set.of("UNREAD", "READ", "ARCHIVED", "SPAM");
    private static final Pattern DEDUPE_KEY = Pattern.compile("[0-9a-f]{64}");

    public ContactMessageRecord {
        Objects.requireNonNull(id, "contact message id is required");
        requireBounded(visitorName, 100, "contact visitor name is invalid");
        requireBounded(visitorEmail, 320, "contact visitor email is invalid");
        requireBounded(subject, 160, "contact subject is invalid");
        requireBounded(body, 5_000, "contact body is invalid");
        if (status == null || !STATUSES.contains(status)) {
            throw new IllegalArgumentException("contact message status is invalid");
        }
        if (dedupeKey == null || !DEDUPE_KEY.matcher(dedupeKey).matches()) {
            throw new IllegalArgumentException("contact message dedupe key is invalid");
        }
        Objects.requireNonNull(
                privacyAcceptedAt, "contact privacy acceptance timestamp is required");
        if (version < 0) {
            throw new IllegalArgumentException("contact message version is invalid");
        }
        Objects.requireNonNull(createdAt, "contact message created timestamp is required");
        Objects.requireNonNull(updatedAt, "contact message updated timestamp is required");
        if (privacyAcceptedAt.isAfter(createdAt)) {
            throw new IllegalArgumentException(
                    "contact privacy acceptance timestamp is invalid");
        }
    }

    @Override
    public String toString() {
        return "ContactMessageRecord[id=" + id
                + ", status=" + status
                + ", version=" + version
                + ", pii=<redacted>]";
    }

    private static void requireBounded(String value, int maximum, String message) {
        if (value == null || value.codePointCount(0, value.length()) > maximum) {
            throw new IllegalArgumentException(message);
        }
    }
}
