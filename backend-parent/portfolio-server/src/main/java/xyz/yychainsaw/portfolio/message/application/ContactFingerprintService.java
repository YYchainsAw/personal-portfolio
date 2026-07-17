package xyz.yychainsaw.portfolio.message.application;

import jakarta.annotation.PreDestroy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.stereotype.Component;
import xyz.yychainsaw.portfolio.message.config.ContactProperties;

@Component
@ConditionalOnWebApplication(type = Type.SERVLET)
public final class ContactFingerprintService {
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int MINIMUM_KEY_BYTES = 32;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final Object keyMonitor = new Object();
    private final byte[] key;

    public ContactFingerprintService(ContactProperties properties) {
        Objects.requireNonNull(properties, "contact properties are required");
        byte[] configured = properties.dedupeSecret();
        if (configured.length < MINIMUM_KEY_BYTES) {
            Arrays.fill(configured, (byte) 0);
            throw new IllegalStateException("contact dedupe secret is not configured");
        }
        key = configured;
    }

    public String contactKey(SubmitContactCommand command) {
        Objects.requireNonNull(command, "contact command is required");
        return digest(
                "contact-content-v1",
                command.email(),
                command.subject(),
                command.body());
    }

    @PreDestroy
    private void destroyKey() {
        synchronized (keyMonitor) {
            Arrays.fill(key, (byte) 0);
        }
    }

    @Override
    public String toString() {
        return "ContactFingerprintService[key=<redacted>]";
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
                update(mac, Objects.requireNonNull(value, "fingerprint value is required"));
            }
            output = mac.doFinal();
            return lowercaseHex(output);
        } catch (GeneralSecurityException | RuntimeException failure) {
            throw new IllegalStateException("contact fingerprinting failed");
        } finally {
            Arrays.fill(keySnapshot, (byte) 0);
            if (output != null) {
                Arrays.fill(output, (byte) 0);
            }
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
}
