package xyz.yychainsaw.portfolio.message.web;

import jakarta.annotation.PreDestroy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnWebApplication(type = Type.SERVLET)
public final class ContactRateLimitSubjectHasher {
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String NAMESPACE = "public-contact-v1";
    private static final int KEY_BYTES = 32;
    private static final int MAXIMUM_ADDRESS_UNITS = 64;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final Object keyMonitor = new Object();
    private final byte[] key;

    public ContactRateLimitSubjectHasher() {
        this(randomKey());
    }

    ContactRateLimitSubjectHasher(byte[] key) {
        Objects.requireNonNull(key, "rate-limit HMAC key is required");
        if (key.length != KEY_BYTES) {
            throw new IllegalArgumentException("rate-limit HMAC key must contain 256 bits");
        }
        this.key = Arrays.copyOf(key, key.length);
    }

    public String hash(String canonicalAddress) {
        if (canonicalAddress == null
                || canonicalAddress.isEmpty()
                || canonicalAddress.length() > MAXIMUM_ADDRESS_UNITS
                || !isWellFormedUtf16(canonicalAddress)) {
            throw new IllegalStateException("contact rate-limit subject hashing failed");
        }
        byte[] keySnapshot;
        synchronized (keyMonitor) {
            keySnapshot = Arrays.copyOf(key, key.length);
        }
        byte[] output = null;
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(keySnapshot, HMAC_ALGORITHM));
            update(mac, NAMESPACE);
            update(mac, canonicalAddress);
            output = mac.doFinal();
            return lowercaseHex(output);
        } catch (GeneralSecurityException | RuntimeException failure) {
            throw new IllegalStateException("contact rate-limit subject hashing failed");
        } finally {
            Arrays.fill(keySnapshot, (byte) 0);
            if (output != null) {
                Arrays.fill(output, (byte) 0);
            }
        }
    }

    @PreDestroy
    private void destroyKey() {
        synchronized (keyMonitor) {
            Arrays.fill(key, (byte) 0);
        }
    }

    @Override
    public String toString() {
        return "ContactRateLimitSubjectHasher[key=<redacted>]";
    }

    private static byte[] randomKey() {
        byte[] generated = new byte[KEY_BYTES];
        try {
            new SecureRandom().nextBytes(generated);
            return generated;
        } catch (RuntimeException failure) {
            Arrays.fill(generated, (byte) 0);
            throw new IllegalStateException("contact rate-limit subject hashing failed");
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

    private static String lowercaseHex(byte[] value) {
        char[] encoded = new char[value.length * 2];
        for (int index = 0; index < value.length; index++) {
            int octet = Byte.toUnsignedInt(value[index]);
            encoded[index * 2] = HEX[octet >>> 4];
            encoded[index * 2 + 1] = HEX[octet & 0x0f];
        }
        return new String(encoded);
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
}
