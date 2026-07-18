package xyz.yychainsaw.portfolio.analytics.web;

import java.util.List;

public record PublicAnalyticsBatchRequest(
        boolean analyticsConsent,
        String visitorId,
        String sessionId,
        List<PublicAnalyticsEventRequest> events) {

    public PublicAnalyticsBatchRequest {
        events = events == null ? null : List.copyOf(events);
    }

    @Override
    public String toString() {
        return "PublicAnalyticsBatchRequest[analyticsConsent=<redacted>, "
                + "visitorId=<redacted>, sessionId=<redacted>, events=<redacted>]";
    }
}
