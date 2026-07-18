package xyz.yychainsaw.portfolio.analytics.config;

import jakarta.annotation.PreDestroy;
import java.util.Arrays;
import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("portfolio.analytics")
public final class AnalyticsProperties {
    private static final int MINIMUM_SECRET_BYTES = 32;

    private final byte[] hmacSecret;

    public AnalyticsProperties(String hmacSecret) {
        this.hmacSecret = decodeSecret(hmacSecret);
    }

    public byte[] hmacSecret() {
        synchronized (hmacSecret) {
            return Arrays.copyOf(hmacSecret, hmacSecret.length);
        }
    }

    public boolean configured() {
        synchronized (hmacSecret) {
            return hmacSecret.length >= MINIMUM_SECRET_BYTES;
        }
    }

    @PreDestroy
    private void destroySecret() {
        synchronized (hmacSecret) {
            Arrays.fill(hmacSecret, (byte) 0);
        }
    }

    @Override
    public String toString() {
        return "AnalyticsProperties[hmacSecret=<redacted>]";
    }

    private static byte[] decodeSecret(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return new byte[0];
        }

        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException invalid) {
            throw invalidSecret();
        }
        if (decoded.length < MINIMUM_SECRET_BYTES
                || !Base64.getEncoder().encodeToString(decoded).equals(encoded)) {
            Arrays.fill(decoded, (byte) 0);
            throw invalidSecret();
        }
        return decoded;
    }

    private static IllegalArgumentException invalidSecret() {
        return new IllegalArgumentException("analytics HMAC secret is invalid");
    }
}
