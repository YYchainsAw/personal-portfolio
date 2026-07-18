package xyz.yychainsaw.portfolio.analytics.application;

import jakarta.annotation.PreDestroy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import xyz.yychainsaw.portfolio.analytics.config.AnalyticsProperties;
import xyz.yychainsaw.portfolio.analytics.domain.AnalyticsEventType;

@Component
public final class AnalyticsPrivacyService {
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String LOCK_NAMESPACE = "analytics-dedupe-lock-v1";
    private static final int MINIMUM_KEY_BYTES = 32;
    private static final int MAXIMUM_BROWSER_ID_UNITS = 64;
    private static final int MAXIMUM_PAGE_KEY_UNITS = 200;
    private static final Pattern DAY_KEY = Pattern.compile("[0-9a-f]{64}");
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final Object keyMonitor = new Object();
    private final byte[] key;

    public AnalyticsPrivacyService(AnalyticsProperties properties) {
        Objects.requireNonNull(properties, "analytics properties are required");
        this.key = properties.hmacSecret();
    }

    public String visitorDayKey(LocalDate siteDate, String visitorId) {
        return dailyKey(siteDate, "visitor", visitorId);
    }

    public String sessionDayKey(LocalDate siteDate, String sessionId) {
        return dailyKey(siteDate, "session", sessionId);
    }

    public long dedupeLockKey(
            String sessionDayKey,
            AnalyticsEventType eventType,
            String pageKey,
            UUID projectId) {
        requireDayKey(sessionDayKey);
        AnalyticsEventType requiredType = Objects.requireNonNull(
                eventType, "analytics event type is required");
        String requiredPageKey = requirePageKey(pageKey);

        byte[] keySnapshot = keySnapshot();
        byte[] output = null;
        try {
            Mac mac = newMac(keySnapshot);
            updateFramed(mac, LOCK_NAMESPACE);
            updateFramed(mac, sessionDayKey);
            updateFramed(mac, requiredType.name());
            updateFramed(mac, requiredPageKey);
            updateFramed(mac, projectId == null ? "" : projectId.toString());
            output = mac.doFinal();
            return ByteBuffer.wrap(output, 0, Long.BYTES).getLong();
        } catch (GeneralSecurityException | RuntimeException failure) {
            throw hashingFailed();
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
        return "AnalyticsPrivacyService[key=<redacted>]";
    }

    private String dailyKey(LocalDate siteDate, String domain, String identifier) {
        LocalDate requiredDate = Objects.requireNonNull(
                siteDate, "analytics site date is required");
        String requiredIdentifier = requireIdentifier(identifier);

        byte[] keySnapshot = keySnapshot();
        byte[] output = null;
        try {
            Mac mac = newMac(keySnapshot);
            updateRaw(mac, requiredDate.toString());
            updateRaw(mac, "\n" + domain + "\n");
            updateRaw(mac, requiredIdentifier);
            output = mac.doFinal();
            return lowercaseHex(output);
        } catch (GeneralSecurityException | RuntimeException failure) {
            throw hashingFailed();
        } finally {
            Arrays.fill(keySnapshot, (byte) 0);
            if (output != null) {
                Arrays.fill(output, (byte) 0);
            }
        }
    }

    private byte[] keySnapshot() {
        synchronized (keyMonitor) {
            if (key.length < MINIMUM_KEY_BYTES) {
                throw new IllegalStateException("analytics HMAC secret is not configured");
            }
            return Arrays.copyOf(key, key.length);
        }
    }

    private static Mac newMac(byte[] key) throws GeneralSecurityException {
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
        return mac;
    }

    private static void updateRaw(Mac mac, String value) {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        try {
            mac.update(encoded);
        } finally {
            Arrays.fill(encoded, (byte) 0);
        }
    }

    private static void updateFramed(Mac mac, String value) {
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

    private static String requireIdentifier(String value) {
        if (value == null
                || value.isEmpty()
                || value.length() > MAXIMUM_BROWSER_ID_UNITS
                || !isWellFormedUtf16(value)) {
            throw new IllegalArgumentException("analytics browser identifier is invalid");
        }
        return value;
    }

    private static void requireDayKey(String value) {
        if (value == null || !DAY_KEY.matcher(value).matches()) {
            throw new IllegalArgumentException("analytics session day key is invalid");
        }
    }

    private static String requirePageKey(String value) {
        if (value == null
                || value.isBlank()
                || value.length() > MAXIMUM_PAGE_KEY_UNITS
                || !isWellFormedUtf16(value)) {
            throw new IllegalArgumentException("analytics page key is invalid");
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

    private static String lowercaseHex(byte[] value) {
        char[] encoded = new char[value.length * 2];
        for (int index = 0; index < value.length; index++) {
            int octet = Byte.toUnsignedInt(value[index]);
            encoded[index * 2] = HEX[octet >>> 4];
            encoded[index * 2 + 1] = HEX[octet & 0x0f];
        }
        return new String(encoded);
    }

    private static IllegalStateException hashingFailed() {
        return new IllegalStateException("analytics privacy hashing failed");
    }
}
