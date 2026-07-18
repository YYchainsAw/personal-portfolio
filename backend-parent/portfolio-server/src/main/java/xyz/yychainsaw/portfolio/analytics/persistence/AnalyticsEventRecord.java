package xyz.yychainsaw.portfolio.analytics.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import xyz.yychainsaw.portfolio.analytics.domain.AnalyticsEventType;
import xyz.yychainsaw.portfolio.analytics.domain.DeviceClass;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;

public record AnalyticsEventRecord(
        UUID id,
        UUID clientEventId,
        LocalDate siteDate,
        Instant receivedAt,
        String visitorDayKey,
        String sessionDayKey,
        AnalyticsEventType eventType,
        String pageKey,
        UUID projectId,
        String referrerDomain,
        DeviceClass deviceClass,
        LocaleCode locale,
        String rulesVersion,
        Instant createdAt) {
    private static final ZoneId SITE_ZONE = ZoneId.of("Asia/Hong_Kong");
    private static final Pattern DAY_KEY = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern RULES_VERSION = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");

    public AnalyticsEventRecord {
        Objects.requireNonNull(id, "analytics event ID is required");
        Objects.requireNonNull(clientEventId, "analytics client event ID is required");
        Objects.requireNonNull(siteDate, "analytics site date is required");
        Objects.requireNonNull(receivedAt, "analytics receipt time is required");
        Objects.requireNonNull(eventType, "analytics event type is required");
        Objects.requireNonNull(deviceClass, "analytics device class is required");
        Objects.requireNonNull(locale, "analytics locale is required");
        Objects.requireNonNull(createdAt, "analytics creation time is required");
        requireMicrosecondPrecision(receivedAt, "analytics receipt time");
        requireMicrosecondPrecision(createdAt, "analytics creation time");
        if (!siteDate.equals(receivedAt.atZone(SITE_ZONE).toLocalDate())) {
            throw new IllegalArgumentException("analytics site date does not match receipt time");
        }
        requireDayKey(visitorDayKey, "visitor");
        requireDayKey(sessionDayKey, "session");
        if (pageKey == null || pageKey.isBlank() || pageKey.length() > 200) {
            throw new IllegalArgumentException("analytics page key is invalid");
        }
        if (referrerDomain == null
                || referrerDomain.isBlank()
                || referrerDomain.length() > 253) {
            throw new IllegalArgumentException("analytics referrer domain is invalid");
        }
        if (rulesVersion == null || !RULES_VERSION.matcher(rulesVersion).matches()) {
            throw new IllegalArgumentException("analytics rules version is invalid");
        }
    }

    @Override
    public String toString() {
        return "AnalyticsEventRecord[fields=<redacted>]";
    }

    private static void requireDayKey(String value, String namespace) {
        if (value == null || !DAY_KEY.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "analytics " + namespace + " day key is invalid");
        }
    }

    private static void requireMicrosecondPrecision(Instant value, String field) {
        if (value.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException(field + " must use microsecond precision");
        }
    }
}
