package xyz.yychainsaw.portfolio.analytics.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import xyz.yychainsaw.portfolio.analytics.application.AnalyticsCollector;
import xyz.yychainsaw.portfolio.analytics.application.CollectAnalyticsCommand;
import xyz.yychainsaw.portfolio.auth.session.TrustedClientAddressResolver;
import xyz.yychainsaw.portfolio.common.error.DomainException;

@RestController
@RequestMapping("/api/public")
@ConditionalOnWebApplication(type = Type.SERVLET)
public final class PublicAnalyticsController {
    private static final int MAXIMUM_USER_AGENT_UNITS = 2_048;

    private final PublicAnalyticsBodyReader bodyReader;
    private final TrustedClientAddressResolver addresses;
    private final AnalyticsRateLimitSubjectHasher rateSubjects;
    private final AnalyticsCollector collector;

    public PublicAnalyticsController(
            PublicAnalyticsBodyReader bodyReader,
            TrustedClientAddressResolver addresses,
            AnalyticsRateLimitSubjectHasher rateSubjects,
            AnalyticsCollector collector) {
        this.bodyReader = Objects.requireNonNull(
                bodyReader, "analytics body reader is required");
        this.addresses = Objects.requireNonNull(
                addresses, "client addresses are required");
        this.rateSubjects = Objects.requireNonNull(
                rateSubjects, "analytics rate subjects are required");
        this.collector = Objects.requireNonNull(
                collector, "analytics collector is required");
    }

    @PostMapping(value = "/events", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> collect(
            HttpServletRequest request, HttpServletResponse response) {
        boolean privacySignal = privacySignalEnabled(request);
        byte[] requestBody = bodyReader.readBounded(request);
        if (privacySignal) {
            return noContent();
        }
        PublicAnalyticsBatchRequest body = bodyReader.parse(requestBody);
        if (!body.analyticsConsent()) {
            return noContent();
        }

        String rateSubject = rateSubjects.hash(addresses.resolve(request));
        List<CollectAnalyticsCommand.Event> events = mapEvents(body.events());
        CollectAnalyticsCommand command = new CollectAnalyticsCommand(
                body.visitorId(),
                body.sessionId(),
                boundedUserAgent(request),
                rateSubject,
                events);
        try {
            collector.collect(command);
            return noContent();
        } catch (DomainException expected) {
            copyRetryAfter(expected, response);
            throw expected;
        }
    }

    private static List<CollectAnalyticsCommand.Event> mapEvents(
            List<PublicAnalyticsEventRequest> events) {
        if (events == null) {
            return null;
        }
        List<CollectAnalyticsCommand.Event> commands = new ArrayList<>(events.size());
        for (PublicAnalyticsEventRequest event : events) {
            if (event == null) {
                commands.add(null);
                continue;
            }
            commands.add(new CollectAnalyticsCommand.Event(
                    event.eventId(),
                    event.type(),
                    event.pageKey(),
                    event.projectId(),
                    event.referrer(),
                    event.locale()));
        }
        return List.copyOf(commands);
    }

    private static boolean privacySignalEnabled(HttpServletRequest request) {
        return hasExactHeaderValue(request, "DNT", "1")
                || hasExactHeaderValue(request, "Sec-GPC", "1");
    }

    private static boolean hasExactHeaderValue(
            HttpServletRequest request, String name, String expected) {
        Enumeration<String> values = request.getHeaders(name);
        while (values != null && values.hasMoreElements()) {
            if (expected.equals(values.nextElement())) {
                return true;
            }
        }
        return false;
    }

    private static String boundedUserAgent(HttpServletRequest request) {
        StringBuilder combined = new StringBuilder();
        Enumeration<String> values = request.getHeaders(HttpHeaders.USER_AGENT);
        while (values != null
                && values.hasMoreElements()
                && combined.length() < MAXIMUM_USER_AGENT_UNITS) {
            String value = values.nextElement();
            if (value == null) {
                continue;
            }
            if (!combined.isEmpty()) {
                combined.append(' ');
            }
            int remaining = MAXIMUM_USER_AGENT_UNITS - combined.length();
            combined.append(value, 0, Math.min(value.length(), remaining));
        }
        return combined.toString();
    }

    private static ResponseEntity<Void> noContent() {
        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore())
                .build();
    }

    private static void copyRetryAfter(
            DomainException failure, HttpServletResponse response) {
        if (!"ANALYTICS_RATE_LIMITED".equals(failure.code())) {
            return;
        }
        String value = failure.fieldErrors().get("retryAfterSeconds");
        if (value != null && value.matches("[1-9][0-9]{0,9}")) {
            response.setHeader(HttpHeaders.RETRY_AFTER, value);
        }
    }
}
