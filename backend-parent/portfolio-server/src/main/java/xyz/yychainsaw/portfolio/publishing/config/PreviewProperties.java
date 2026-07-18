package xyz.yychainsaw.portfolio.publishing.config;

import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("portfolio.preview")
public final class PreviewProperties {
    private static final int MINIMUM_KEY_BYTES = 32;
    private static final Duration MAXIMUM_TTL = Duration.ofMinutes(10);

    private final byte[] hmacKey;
    private final Duration ttl;

    public PreviewProperties(String hmacKey, Duration ttl) {
        this.hmacKey = decodeKey(hmacKey);
        this.ttl = requireTtl(ttl);
    }

    public byte[] hmacKey() {
        return Arrays.copyOf(hmacKey, hmacKey.length);
    }

    public Duration ttl() {
        return ttl;
    }

    @Override
    public String toString() {
        return "PreviewProperties[hmacKey=<redacted>, ttl=" + ttl + ']';
    }

    private static byte[] decodeKey(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return new byte[0];
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException failure) {
            throw invalidKey();
        }
        if (decoded.length < MINIMUM_KEY_BYTES
                || !Base64.getEncoder().encodeToString(decoded).equals(encoded)) {
            Arrays.fill(decoded, (byte) 0);
            throw invalidKey();
        }
        return decoded;
    }

    private static Duration requireTtl(Duration value) {
        if (value == null
                || value.isZero()
                || value.isNegative()
                || value.compareTo(MAXIMUM_TTL) > 0) {
            throw new IllegalArgumentException("invalid preview token TTL");
        }
        return value;
    }

    private static IllegalArgumentException invalidKey() {
        return new IllegalArgumentException("invalid preview HMAC key");
    }
}
