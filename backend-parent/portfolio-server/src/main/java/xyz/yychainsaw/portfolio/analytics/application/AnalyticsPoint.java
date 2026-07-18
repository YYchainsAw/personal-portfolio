package xyz.yychainsaw.portfolio.analytics.application;

import java.time.LocalDate;
import java.util.Objects;

public record AnalyticsPoint(LocalDate date, long value) {
    public AnalyticsPoint {
        Objects.requireNonNull(date, "analytics point date is required");
        if (value < 0) {
            throw new IllegalArgumentException("analytics point value is invalid");
        }
    }
}
