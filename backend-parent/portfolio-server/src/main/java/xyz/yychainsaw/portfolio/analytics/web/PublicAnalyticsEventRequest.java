package xyz.yychainsaw.portfolio.analytics.web;

import java.util.UUID;
import xyz.yychainsaw.portfolio.analytics.domain.AnalyticsEventType;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;

public record PublicAnalyticsEventRequest(
        UUID eventId,
        AnalyticsEventType type,
        String pageKey,
        UUID projectId,
        String referrer,
        LocaleCode locale) {

    @Override
    public String toString() {
        return "PublicAnalyticsEventRequest[fields=<redacted>]";
    }
}
