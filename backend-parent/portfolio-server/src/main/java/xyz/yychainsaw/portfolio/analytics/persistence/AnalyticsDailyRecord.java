package xyz.yychainsaw.portfolio.analytics.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import xyz.yychainsaw.portfolio.analytics.domain.AnalyticsEventType;

public record AnalyticsDailyRecord(
        LocalDate siteDate,
        String metric,
        AnalyticsEventType eventType,
        String dimension,
        String dimensionValue,
        long metricCount,
        String aggregationVersion,
        Instant updatedAt) {
    public AnalyticsDailyRecord {
        Objects.requireNonNull(siteDate, "analytics daily site date is required");
        Objects.requireNonNull(metric, "analytics daily metric is required");
        Objects.requireNonNull(eventType, "analytics daily event type is required");
        Objects.requireNonNull(dimension, "analytics daily dimension is required");
        Objects.requireNonNull(dimensionValue, "analytics daily dimension value is required");
        Objects.requireNonNull(
                aggregationVersion, "analytics aggregation version is required");
        Objects.requireNonNull(updatedAt, "analytics daily update time is required");
        if (metricCount < 0) {
            throw new IllegalArgumentException("analytics daily metric count is invalid");
        }
    }

    @Override
    public String toString() {
        return "AnalyticsDailyRecord[fields=<redacted>]";
    }
}
