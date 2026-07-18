package xyz.yychainsaw.portfolio.analytics.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record AnalyticsSummary(
        long pageViews,
        long dailyUniqueVisitors,
        long projectViews,
        long resumeDownloads,
        long demoDownloads,
        long outboundClicks,
        Instant dataCompleteThrough,
        String zone,
        Map<String, String> definitions) {
    public AnalyticsSummary {
        if (pageViews < 0
                || dailyUniqueVisitors < 0
                || projectViews < 0
                || resumeDownloads < 0
                || demoDownloads < 0
                || outboundClicks < 0) {
            throw new IllegalArgumentException("analytics summary count is invalid");
        }
        if (zone == null || zone.isBlank()) {
            throw new IllegalArgumentException("analytics summary zone is required");
        }
        LinkedHashMap<String, String> orderedDefinitions = new LinkedHashMap<>();
        Objects.requireNonNull(
                        definitions, "analytics summary definitions are required")
                .forEach((key, value) -> orderedDefinitions.put(
                        Objects.requireNonNull(key, "analytics definition key is required"),
                        Objects.requireNonNull(value, "analytics definition text is required")));
        definitions = Collections.unmodifiableMap(orderedDefinitions);
    }
}
