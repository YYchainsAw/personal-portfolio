package xyz.yychainsaw.portfolio.analytics.application;

public record AnalyticsBreakdownItem(String dimensionValue, long value) {
    public AnalyticsBreakdownItem {
        if (dimensionValue == null || dimensionValue.isBlank()) {
            throw new IllegalArgumentException(
                    "analytics breakdown dimension value is required");
        }
        if (value < 0) {
            throw new IllegalArgumentException("analytics breakdown value is invalid");
        }
    }
}
