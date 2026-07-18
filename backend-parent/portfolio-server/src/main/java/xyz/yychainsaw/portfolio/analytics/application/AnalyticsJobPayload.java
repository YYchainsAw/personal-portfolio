package xyz.yychainsaw.portfolio.analytics.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;

final class AnalyticsJobPayload {
    private static final long MAXIMUM_SITE_DATE_AGE_DAYS = 31;

    private AnalyticsJobPayload() {}

    static LocalDate requireSiteDate(
            JsonNode payload, LocalDate currentSiteDate, String errorCode) {
        if (payload == null
                || !payload.isObject()
                || payload.size() != 1
                || !payload.has("siteDate")) {
            throw invalid(errorCode);
        }
        JsonNode value = payload.get("siteDate");
        return requireDate(value, currentSiteDate, errorCode);
    }

    static LocalDate requireAggregationSiteDate(
            JsonNode payload,
            LocalDate currentSiteDate,
            String aggregationVersion,
            String errorCode) {
        if (payload == null
                || !payload.isObject()
                || payload.size() != 2
                || !payload.has("siteDate")
                || !payload.has("aggregationVersion")) {
            throw invalid(errorCode);
        }
        JsonNode version = payload.get("aggregationVersion");
        if (version == null
                || !version.isTextual()
                || !aggregationVersion.equals(version.textValue())) {
            throw invalid(errorCode);
        }
        return requireDate(payload.get("siteDate"), currentSiteDate, errorCode);
    }

    private static LocalDate requireDate(
            JsonNode value, LocalDate currentSiteDate, String errorCode) {
        if (value == null || !value.isTextual()) {
            throw invalid(errorCode);
        }

        String encoded = value.textValue();
        LocalDate siteDate;
        try {
            siteDate = LocalDate.parse(encoded);
        } catch (RuntimeException exception) {
            throw invalid(errorCode);
        }
        if (!siteDate.toString().equals(encoded)
                || siteDate.isAfter(currentSiteDate)
                || siteDate.isBefore(currentSiteDate.minusDays(MAXIMUM_SITE_DATE_AGE_DAYS))) {
            throw invalid(errorCode);
        }
        return siteDate;
    }

    private static IllegalArgumentException invalid(String errorCode) {
        return new IllegalArgumentException(errorCode);
    }
}
