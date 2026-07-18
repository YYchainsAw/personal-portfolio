package xyz.yychainsaw.portfolio.message.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public record EmailOutboxRecord(
        UUID id,
        UUID contactMessageId,
        String templateName,
        String toAddress,
        String stableMessageId,
        String status,
        int attempts,
        Instant nextAttemptAt,
        String leaseOwner,
        Instant leaseUntil,
        String lastErrorSummary,
        Instant createdAt,
        Instant sentAt,
        Instant updatedAt) {
    private static final Set<String> STATUSES =
            Set.of("PENDING", "SENDING", "SENT", "FAILED", "DEAD", "CANCELED");
    private static final Pattern TEMPLATE_NAME =
            Pattern.compile("[a-z0-9][a-z0-9._-]{0,79}");
    private static final Pattern STABLE_MESSAGE_ID = Pattern.compile(
            "<portfolio-contact-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"
                    + "@[a-z0-9](?:[a-z0-9.-]{0,196}[a-z0-9])?>");

    public EmailOutboxRecord {
        Objects.requireNonNull(id, "email outbox id is required");
        Objects.requireNonNull(contactMessageId, "contact message id is required");
        requireBounded(templateName, 80, "email outbox template is invalid");
        if (!TEMPLATE_NAME.matcher(templateName).matches()) {
            throw new IllegalArgumentException("email outbox template is invalid");
        }
        requireBounded(toAddress, 320, "email outbox recipient is invalid");
        if (!isSafeAddress(toAddress)) {
            throw new IllegalArgumentException("email outbox recipient is invalid");
        }
        requireBounded(stableMessageId, 255, "email outbox message id is invalid");
        if (!STABLE_MESSAGE_ID.matcher(stableMessageId).matches()) {
            throw new IllegalArgumentException("email outbox message id is invalid");
        }
        if (status == null || !STATUSES.contains(status)) {
            throw new IllegalArgumentException("email outbox status is invalid");
        }
        if (attempts < 0) {
            throw new IllegalArgumentException("email outbox attempts are invalid");
        }
        Objects.requireNonNull(nextAttemptAt, "email outbox next-attempt timestamp is required");
        requireNullableBounded(leaseOwner, 120, "email outbox lease owner is invalid");
        validateLease(status, leaseOwner, leaseUntil);
        requireNullableBounded(
                lastErrorSummary, 500, "email outbox error summary is invalid");
        Objects.requireNonNull(createdAt, "email outbox created timestamp is required");
        if ("SENT".equals(status) != (sentAt != null)) {
            throw new IllegalArgumentException("email outbox sent state is invalid");
        }
        Objects.requireNonNull(updatedAt, "email outbox updated timestamp is required");
    }

    @Override
    public String toString() {
        return "EmailOutboxRecord[id=" + id
                + ", contactMessageId=" + contactMessageId
                + ", status=" + status
                + ", attempts=" + attempts
                + ", pii=<redacted>]";
    }

    private static void validateLease(
            String status, String leaseOwner, Instant leaseUntil) {
        if ("SENDING".equals(status)) {
            if (leaseOwner == null
                    || leaseOwner.isBlank()
                    || !leaseOwner.equals(leaseOwner.trim())
                    || leaseUntil == null) {
                throw new IllegalArgumentException("email outbox lease state is invalid");
            }
            return;
        }
        if (leaseOwner != null || leaseUntil != null) {
            throw new IllegalArgumentException("email outbox lease state is invalid");
        }
    }

    private static void requireBounded(String value, int maximum, String message) {
        if (value == null
                || value.isBlank()
                || value.codePointCount(0, value.length()) > maximum) {
            throw new IllegalArgumentException(message);
        }
    }

    private static boolean isSafeAddress(String value) {
        int separator = value.indexOf('@');
        return separator > 0
                && separator == value.lastIndexOf('@')
                && separator < value.length() - 1
                && value.chars().allMatch(character -> character >= 0x21 && character <= 0x7e);
    }

    private static void requireNullableBounded(
            String value, int maximum, String message) {
        if (value != null && value.codePointCount(0, value.length()) > maximum) {
            throw new IllegalArgumentException(message);
        }
    }
}
