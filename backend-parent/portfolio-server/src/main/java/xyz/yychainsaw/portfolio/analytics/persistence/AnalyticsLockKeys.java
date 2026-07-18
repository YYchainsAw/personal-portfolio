package xyz.yychainsaw.portfolio.analytics.persistence;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.Objects;

final class AnalyticsLockKeys {
    private static final String DATE_NAMESPACE = "portfolio:analytics:date:";
    private static final String VERSION_NAMESPACE = "portfolio:analytics:version:";

    private AnalyticsLockKeys() {}

    static long date(LocalDate siteDate) {
        return digest(DATE_NAMESPACE + Objects.requireNonNull(siteDate));
    }

    static long version(LocalDate siteDate, String aggregationVersion) {
        Objects.requireNonNull(siteDate, "analytics lock date is required");
        if (aggregationVersion == null || aggregationVersion.isBlank()) {
            throw new IllegalArgumentException("analytics lock version is invalid");
        }
        return digest(VERSION_NAMESPACE + siteDate + ":" + aggregationVersion);
    }

    private static long digest(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest).getLong();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("ANALYTICS_LOCK_KEY_UNAVAILABLE");
        }
    }
}
