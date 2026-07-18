package xyz.yychainsaw.portfolio.message.application;

import java.util.Objects;
import java.util.UUID;

public record ContactSubmissionResult(boolean accepted, UUID messageId) {
    public ContactSubmissionResult {
        if (!accepted) {
            throw new IllegalArgumentException("contact result must be accepted");
        }
    }

    public static ContactSubmissionResult accepted(UUID messageId) {
        return new ContactSubmissionResult(
                true, Objects.requireNonNull(messageId, "message ID is required"));
    }

    public static ContactSubmissionResult acceptedWithoutIdentifier() {
        return new ContactSubmissionResult(true, null);
    }
}
