package xyz.yychainsaw.portfolio.message.email;

import java.util.Objects;
import java.util.regex.Pattern;

public record LeasedEmail(
        String leaseOwner,
        int attempts,
        String templateName,
        ContactNotification notification) {
    private static final Pattern LEASE_OWNER = Pattern.compile("[!-~]{1,120}");
    private static final Pattern TEMPLATE_NAME =
            Pattern.compile("[a-z0-9][a-z0-9._-]{0,79}");

    public LeasedEmail {
        if (leaseOwner == null || !LEASE_OWNER.matcher(leaseOwner).matches()) {
            throw new IllegalArgumentException("email lease owner is invalid");
        }
        if (attempts < 1 || attempts > 10) {
            throw new IllegalArgumentException("email lease attempts are invalid");
        }
        if (templateName == null || !TEMPLATE_NAME.matcher(templateName).matches()) {
            throw new IllegalArgumentException("email template is invalid");
        }
        Objects.requireNonNull(notification, "contact notification is required");
    }

    @Override
    public String toString() {
        return "LeasedEmail[outboxId=" + notification.outboxId()
                + ", attempts=" + attempts + ", pii=<redacted>]";
    }
}
