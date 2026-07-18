package xyz.yychainsaw.portfolio.analytics.application;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import xyz.yychainsaw.portfolio.analytics.domain.AnalyticsEventType;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;

public record AnalyticsQuery(
        LocalDate from,
        LocalDate to,
        Metric metric,
        AnalyticsEventType eventType,
        Dimension dimension,
        LocaleCode locale,
        int limit) {
    private static final long MAXIMUM_INCLUSIVE_DAYS = 366;

    public AnalyticsQuery {
        Objects.requireNonNull(from, "analytics query start date is required");
        Objects.requireNonNull(to, "analytics query end date is required");
        if (to.isBefore(from)
                || ChronoUnit.DAYS.between(from, to) + 1 > MAXIMUM_INCLUSIVE_DAYS) {
            throw new IllegalArgumentException("analytics query date range is invalid");
        }

        boolean summary = metric == null
                && eventType == null
                && dimension == null
                && locale != null
                && limit == 0;
        boolean timeseries = metric != null
                && eventType != null
                && dimension == null
                && locale == null
                && limit == 0;
        boolean breakdown = metric != null
                && eventType != null
                && dimension != null
                && locale == null
                && limit >= 1
                && limit <= 100;
        if (!(summary || timeseries || breakdown)) {
            throw new IllegalArgumentException("analytics query shape is invalid");
        }
        if (metric != null && !metric.supports(eventType)) {
            throw new IllegalArgumentException("analytics metric and event type are incompatible");
        }
    }

    public static AnalyticsQuery summary(
            LocalDate from, LocalDate to, LocaleCode locale) {
        return new AnalyticsQuery(from, to, null, null, null, locale, 0);
    }

    public static AnalyticsQuery timeseries(
            LocalDate from,
            LocalDate to,
            Metric metric,
            AnalyticsEventType eventType) {
        return new AnalyticsQuery(from, to, metric, eventType, null, null, 0);
    }

    public static AnalyticsQuery breakdown(
            LocalDate from,
            LocalDate to,
            Metric metric,
            AnalyticsEventType eventType,
            Dimension dimension,
            int limit) {
        return new AnalyticsQuery(
                from, to, metric, eventType, dimension, null, limit);
    }

    public Kind kind() {
        if (locale != null) {
            return Kind.SUMMARY;
        }
        return dimension == null ? Kind.TIMESERIES : Kind.BREAKDOWN;
    }

    public enum Kind {
        SUMMARY,
        TIMESERIES,
        BREAKDOWN
    }

    public enum Metric {
        PV,
        DAILY_UV,
        EVENT_COUNT;

        private boolean supports(AnalyticsEventType eventType) {
            return this == EVENT_COUNT || eventType == AnalyticsEventType.PAGE_VIEW;
        }
    }

    public enum Dimension {
        PAGE,
        PROJECT,
        REFERRER,
        DEVICE,
        LOCALE
    }
}
