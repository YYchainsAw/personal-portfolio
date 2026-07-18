package xyz.yychainsaw.portfolio.message.config;

import java.time.Duration;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("portfolio.email")
public final class EmailOutboxProperties {
    private static final Duration MINIMUM_POLL_INTERVAL = Duration.ofMillis(100);
    private static final Duration MAXIMUM_POLL_INTERVAL = Duration.ofHours(1);
    private static final Duration MAXIMUM_LEASE_DURATION = Duration.ofHours(24);
    private static final int MAXIMUM_BATCH_SIZE = 100;
    private static final Pattern LOCAL_PART = Pattern.compile(
            "[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]{1,64}");
    private static final Pattern DOMAIN_LABEL =
            Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?");

    private final boolean enabled;
    private final String from;
    private final Duration pollInterval;
    private final Duration leaseDuration;
    private final int batchSize;

    public EmailOutboxProperties(
            boolean enabled,
            String from,
            @DefaultValue("10s") Duration pollInterval,
            @DefaultValue("2m") Duration leaseDuration,
            @DefaultValue("10") int batchSize) {
        this.enabled = enabled;
        this.from = requireFrom(from, enabled);
        this.pollInterval = requirePollInterval(pollInterval);
        this.leaseDuration = requireLeaseDuration(leaseDuration);
        this.batchSize = requireBatchSize(batchSize);
    }

    public boolean enabled() {
        return enabled;
    }

    public String from() {
        return from;
    }

    public Duration pollInterval() {
        return pollInterval;
    }

    public Duration leaseDuration() {
        return leaseDuration;
    }

    public int batchSize() {
        return batchSize;
    }

    @Override
    public String toString() {
        return "EmailOutboxProperties[enabled=" + enabled
                + ", from=<redacted>, pollInterval=" + pollInterval
                + ", leaseDuration=" + leaseDuration
                + ", batchSize=" + batchSize + ']';
    }

    private static String requireFrom(String value, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) {
                throw new IllegalArgumentException("email sender address is required");
            }
            return "";
        }
        if (!value.equals(value.trim())
                || value.length() > 320
                || !value.chars().allMatch(character -> character >= 0x21 && character <= 0x7e)) {
            throw new IllegalArgumentException("email sender address is invalid");
        }
        int separator = value.indexOf('@');
        if (separator <= 0
                || separator != value.lastIndexOf('@')
                || separator == value.length() - 1) {
            throw new IllegalArgumentException("email sender address is invalid");
        }
        String local = value.substring(0, separator);
        String domain = value.substring(separator + 1);
        if (!LOCAL_PART.matcher(local).matches()
                || local.startsWith(".")
                || local.endsWith(".")
                || local.contains("..")
                || domain.length() > 253) {
            throw new IllegalArgumentException("email sender address is invalid");
        }
        String[] labels = domain.split("\\.", -1);
        if (labels.length < 2) {
            throw new IllegalArgumentException("email sender address is invalid");
        }
        for (String label : labels) {
            if (!DOMAIN_LABEL.matcher(label).matches()) {
                throw new IllegalArgumentException("email sender address is invalid");
            }
        }
        return value;
    }

    private static Duration requirePollInterval(Duration value) {
        if (value == null
                || value.compareTo(MINIMUM_POLL_INTERVAL) < 0
                || value.compareTo(MAXIMUM_POLL_INTERVAL) > 0
                || value.toMillis() == 0) {
            throw new IllegalArgumentException("email poll interval is invalid");
        }
        return value;
    }

    private static Duration requireLeaseDuration(Duration value) {
        if (value == null
                || value.isZero()
                || value.isNegative()
                || value.compareTo(MAXIMUM_LEASE_DURATION) > 0
                || value.toMillis() == 0) {
            throw new IllegalArgumentException("email lease duration is invalid");
        }
        return value;
    }

    private static int requireBatchSize(int value) {
        if (value < 1 || value > MAXIMUM_BATCH_SIZE) {
            throw new IllegalArgumentException("email batch size is invalid");
        }
        return value;
    }
}
