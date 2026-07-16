package xyz.yychainsaw.portfolio.auth.web;

import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;
import xyz.yychainsaw.portfolio.auth.session.TrustedClientAddressResolver;

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class LoginSubjectHasher {
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int KEY_BYTES = 32;
    private static final int MAXIMUM_EXTERNAL_UNITS = 128;
    private static final String INVALID_INPUT = "<invalid-input>";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final TrustedClientAddressResolver addresses;
    private final Object keyMonitor = new Object();
    private final byte[] key;

    @Autowired
    public LoginSubjectHasher(TrustedClientAddressResolver addresses) {
        this.addresses = Objects.requireNonNull(addresses, "client addresses are required");
        this.key = randomKey();
    }

    LoginSubjectHasher(TrustedClientAddressResolver addresses, byte[] key) {
        this.addresses = Objects.requireNonNull(addresses, "client addresses are required");
        Objects.requireNonNull(key, "HMAC key is required");
        if (key.length != KEY_BYTES) {
            throw new IllegalArgumentException("HMAC key must contain 256 bits");
        }
        this.key = Arrays.copyOf(key, key.length);
    }

    public String hash(HttpServletRequest request, String username) {
        Objects.requireNonNull(request, "request is required");
        return digest(
                "admin-login",
                resolveAddress(request),
                normalizedLogin(username));
    }

    public String hashSecurity(HttpServletRequest request, UUID adminId) {
        Objects.requireNonNull(request, "request is required");
        Objects.requireNonNull(adminId, "admin id is required");
        return digest("admin-security", resolveAddress(request), adminId.toString());
    }

    public String hashSecondFactor(UUID challengeId) {
        Objects.requireNonNull(challengeId, "challenge id is required");
        return digest("admin-second-factor", challengeId.toString());
    }

    public String hashTotpEnrollment(UUID enrollmentId) {
        Objects.requireNonNull(enrollmentId, "enrollment id is required");
        return digest("admin-totp-enrollment", enrollmentId.toString());
    }

    public String hashSessionId(String publicSessionId) {
        return digest("admin-session-concurrency", boundedRaw(publicSessionId));
    }

    @PreDestroy
    private void destroyKey() {
        synchronized (keyMonitor) {
            Arrays.fill(key, (byte) 0);
        }
    }

    @Override
    public String toString() {
        return "LoginSubjectHasher[key=<redacted>]";
    }

    private String digest(String namespace, String... values) {
        byte[] keySnapshot;
        synchronized (keyMonitor) {
            keySnapshot = Arrays.copyOf(key, key.length);
        }

        byte[] output = null;
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(keySnapshot, HMAC_ALGORITHM));
            update(mac, namespace);
            for (String value : values) {
                update(mac, Objects.requireNonNull(value, "hash input is required"));
            }
            output = mac.doFinal();
            return lowercaseHex(output);
        } catch (GeneralSecurityException | RuntimeException failure) {
            throw new IllegalStateException("login subject hashing failed");
        } finally {
            Arrays.fill(keySnapshot, (byte) 0);
            if (output != null) {
                Arrays.fill(output, (byte) 0);
            }
        }
    }

    private String resolveAddress(HttpServletRequest request) {
        try {
            return Objects.requireNonNull(
                    addresses.resolve(request), "client address is required");
        } catch (RuntimeException failure) {
            throw new IllegalStateException("login subject hashing failed");
        }
    }

    private static void update(Mac mac, String value) {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        byte[] length = ByteBuffer.allocate(Integer.BYTES).putInt(encoded.length).array();
        try {
            mac.update(length);
            mac.update(encoded);
        } finally {
            Arrays.fill(length, (byte) 0);
            Arrays.fill(encoded, (byte) 0);
        }
    }

    private static String lowercaseHex(byte[] bytes) {
        char[] encoded = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            int value = Byte.toUnsignedInt(bytes[index]);
            encoded[index * 2] = HEX[value >>> 4];
            encoded[index * 2 + 1] = HEX[value & 0x0f];
        }
        return new String(encoded);
    }

    private static String normalizedLogin(String value) {
        String bounded = boundedRaw(value);
        return INVALID_INPUT.equals(bounded)
                ? INVALID_INPUT
                : bounded.trim().toLowerCase(Locale.ROOT);
    }

    private static String boundedRaw(String value) {
        if (value == null
                || value.length() > MAXIMUM_EXTERNAL_UNITS
                || !isWellFormedUtf16(value)) {
            return INVALID_INPUT;
        }
        return value;
    }

    private static boolean isWellFormedUtf16(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    return false;
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                return false;
            }
        }
        return true;
    }

    private static byte[] randomKey() {
        byte[] generated = new byte[KEY_BYTES];
        try {
            new SecureRandom().nextBytes(generated);
            return generated;
        } catch (RuntimeException failure) {
            Arrays.fill(generated, (byte) 0);
            throw new IllegalStateException("login subject hashing failed");
        }
    }
}
