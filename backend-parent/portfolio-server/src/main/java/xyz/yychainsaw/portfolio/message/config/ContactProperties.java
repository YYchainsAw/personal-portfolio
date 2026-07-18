package xyz.yychainsaw.portfolio.message.config;

import jakarta.annotation.PreDestroy;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("portfolio.contact")
public final class ContactProperties {
    private static final int MINIMUM_SECRET_BYTES = 32;
    private static final int MAXIMUM_MAIL_ID_DOMAIN_LENGTH = 198;
    private static final Pattern DOMAIN_LABEL =
            Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
    private static final Pattern LOCAL_PART = Pattern.compile(
            "[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]{1,64}");

    private final byte[] dedupeSecret;
    private final String ownerEmail;
    private final String mailIdDomain;

    public ContactProperties(
            String dedupeSecret, String ownerEmail, String mailIdDomain) {
        this.dedupeSecret = decodeSecret(dedupeSecret);
        this.ownerEmail = normalizeOwnerEmail(ownerEmail);
        this.mailIdDomain = normalizeDomain(mailIdDomain, true);
    }

    public byte[] dedupeSecret() {
        synchronized (dedupeSecret) {
            return Arrays.copyOf(dedupeSecret, dedupeSecret.length);
        }
    }

    public String ownerEmail() {
        return ownerEmail;
    }

    public String mailIdDomain() {
        return mailIdDomain;
    }

    @PreDestroy
    private void destroySecret() {
        synchronized (dedupeSecret) {
            Arrays.fill(dedupeSecret, (byte) 0);
        }
    }

    @Override
    public String toString() {
        return "ContactProperties[dedupeSecret=<redacted>, ownerEmail=<redacted>, "
                + "mailIdDomain=" + mailIdDomain + ']';
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

    private static String normalizeOwnerEmail(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (!value.equals(value.trim())
                || value.length() > 320
                || containsControlOrWhitespace(value)) {
            throw invalidOwnerEmail();
        }
        int separator = value.indexOf('@');
        if (separator <= 0
                || separator != value.lastIndexOf('@')
                || separator == value.length() - 1) {
            throw invalidOwnerEmail();
        }
        String local = value.substring(0, separator);
        if (!LOCAL_PART.matcher(local).matches()
                || local.startsWith(".")
                || local.endsWith(".")
                || local.contains("..")) {
            throw invalidOwnerEmail();
        }
        String domain = normalizeDomain(value.substring(separator + 1), false);
        return local + '@' + domain;
    }

    private static String normalizeDomain(String value, boolean allowEmpty) {
        if (value == null || value.isBlank()) {
            if (allowEmpty) {
                return "";
            }
            throw invalidOwnerEmail();
        }
        if (!value.equals(value.trim())
                || value.length() > MAXIMUM_MAIL_ID_DOMAIN_LENGTH
                || !value.chars().allMatch(character -> character < 128)) {
            throw invalidDomain();
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        String[] labels = normalized.split("\\.", -1);
        if (labels.length < 2) {
            throw invalidDomain();
        }
        for (String label : labels) {
            if (!DOMAIN_LABEL.matcher(label).matches()) {
                throw invalidDomain();
            }
        }
        return normalized;
    }

    private static boolean containsControlOrWhitespace(String value) {
        return value.chars().anyMatch(character ->
                Character.isISOControl(character) || Character.isWhitespace(character));
    }

    private static IllegalArgumentException invalidSecret() {
        return new IllegalArgumentException("invalid contact dedupe secret");
    }

    private static IllegalArgumentException invalidOwnerEmail() {
        return new IllegalArgumentException("invalid contact owner email");
    }

    private static IllegalArgumentException invalidDomain() {
        return new IllegalArgumentException("invalid contact mail ID domain");
    }
}
