package xyz.yychainsaw.portfolio.auth.session;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("portfolio.security.session")
public record SessionProperties(
        Duration absoluteLifetime,
        Duration cleanupInterval,
        List<String> trustedProxyAddresses) {
    private static final Duration MAXIMUM_ABSOLUTE_LIFETIME = Duration.ofDays(30);
    private static final Duration MAXIMUM_CLEANUP_INTERVAL = Duration.ofHours(24);
    private static final List<String> DEFAULT_TRUSTED_PROXIES = List.of("127.0.0.1", "::1");

    public SessionProperties {
        absoluteLifetime = requireDuration(
                absoluteLifetime,
                "absolute lifetime is required",
                "absolute lifetime is invalid",
                MAXIMUM_ABSOLUTE_LIFETIME);
        cleanupInterval = requireDuration(
                cleanupInterval,
                "cleanup interval is required",
                "cleanup interval is invalid",
                MAXIMUM_CLEANUP_INTERVAL);
        trustedProxyAddresses = validateTrustedProxyAddresses(trustedProxyAddresses);
    }

    private static Duration requireDuration(
            Duration value, String missingMessage, String invalidMessage, Duration maximum) {
        if (value == null) {
            throw new IllegalArgumentException(missingMessage);
        }
        if (value.isZero()
                || value.isNegative()
                || value.compareTo(maximum) > 0
                || value.getNano() % 1_000_000 != 0) {
            throw new IllegalArgumentException(invalidMessage);
        }
        try {
            if (value.toMillis() <= 0) {
                throw new IllegalArgumentException(invalidMessage);
            }
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(invalidMessage);
        }
        return value;
    }

    private static List<String> validateTrustedProxyAddresses(List<String> configured) {
        if (configured == null) {
            return DEFAULT_TRUSTED_PROXIES;
        }
        if (configured.isEmpty() || configured.size() > 16) {
            throw new IllegalArgumentException(
                    "trusted proxy addresses must contain between 1 and 16 values");
        }
        Set<String> distinct = new HashSet<>();
        for (String address : configured) {
            if (TrustedClientAddressResolver.parseStrictLiteral(address) == null) {
                throw new IllegalArgumentException(
                        "trusted proxy address must be a strict IP literal");
            }
            if (!distinct.add(address)) {
                throw new IllegalArgumentException("trusted proxy addresses must be distinct");
            }
        }
        return List.copyOf(configured);
    }
}
