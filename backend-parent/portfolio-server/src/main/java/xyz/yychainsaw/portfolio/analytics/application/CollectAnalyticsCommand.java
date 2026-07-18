package xyz.yychainsaw.portfolio.analytics.application;

import java.util.List;
import java.util.UUID;
import xyz.yychainsaw.portfolio.analytics.domain.AnalyticsEventType;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;

public record CollectAnalyticsCommand(
        String visitorId,
        String sessionId,
        String userAgent,
        String rateLimitSubject,
        List<Event> events) {

    public CollectAnalyticsCommand {
        events = events == null ? null : List.copyOf(events);
    }

    @Override
    public String toString() {
        return "CollectAnalyticsCommand[visitorId=<redacted>, sessionId=<redacted>, "
                + "userAgent=<redacted>, rateLimitSubject=<redacted>, events=<redacted>]";
    }

    public record Event(
            UUID eventId,
            AnalyticsEventType type,
            String pageKey,
            UUID projectId,
            String referrer,
            LocaleCode locale) {

        @Override
        public String toString() {
            return "Event[fields=<redacted>]";
        }
    }
}
