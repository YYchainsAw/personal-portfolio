package xyz.yychainsaw.portfolio.analytics.web;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import xyz.yychainsaw.portfolio.analytics.application.AnalyticsBreakdownItem;
import xyz.yychainsaw.portfolio.analytics.application.AnalyticsPoint;
import xyz.yychainsaw.portfolio.analytics.application.AnalyticsQuery;
import xyz.yychainsaw.portfolio.analytics.application.AnalyticsQuery.Dimension;
import xyz.yychainsaw.portfolio.analytics.application.AnalyticsQuery.Metric;
import xyz.yychainsaw.portfolio.analytics.application.AnalyticsReportService;
import xyz.yychainsaw.portfolio.analytics.application.AnalyticsSummary;
import xyz.yychainsaw.portfolio.analytics.domain.AnalyticsEventType;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;

@RestController
@RequestMapping("/api/admin/analytics")
@ConditionalOnWebApplication(type = Type.SERVLET)
public final class AdminAnalyticsController {
    private static final String SITE_ZONE = "Asia/Hong_Kong";
    private static final long MAXIMUM_DATE_DIFFERENCE = 365;

    private final AnalyticsReportService reports;

    public AdminAnalyticsController(AnalyticsReportService reports) {
        this.reports = Objects.requireNonNull(
                reports, "analytics report service is required");
    }

    @GetMapping("/summary")
    public ResponseEntity<AnalyticsSummary> summary(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String locale,
            @RequestParam(required = false) String zone) {
        requireSiteZone(zone);
        DateRange range = dateRange(from, to);
        AnalyticsQuery query = AnalyticsQuery.summary(
                range.from(), range.to(), locale(locale));
        return ok(reports.summary(query));
    }

    @GetMapping("/timeseries")
    public ResponseEntity<List<AnalyticsPoint>> timeseries(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String metric,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String zone) {
        requireSiteZone(zone);
        DateRange range = dateRange(from, to);
        Metric parsedMetric = metric(metric);
        AnalyticsEventType parsedEventType = eventType(eventType);
        requireCompatible(parsedMetric, parsedEventType);
        AnalyticsQuery query = AnalyticsQuery.timeseries(
                range.from(), range.to(), parsedMetric, parsedEventType);
        return ok(reports.timeseries(query));
    }

    @GetMapping("/breakdown")
    public ResponseEntity<List<AnalyticsBreakdownItem>> breakdown(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String metric,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String dimension,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String zone) {
        requireSiteZone(zone);
        DateRange range = dateRange(from, to);
        Metric parsedMetric = metric(metric);
        AnalyticsEventType parsedEventType = eventType(eventType);
        requireCompatible(parsedMetric, parsedEventType);
        AnalyticsQuery query = AnalyticsQuery.breakdown(
                range.from(),
                range.to(),
                parsedMetric,
                parsedEventType,
                dimension(dimension),
                limit(limit));
        return ok(reports.breakdown(query));
    }

    private static DateRange dateRange(String fromValue, String toValue) {
        LocalDate from = date("from", fromValue);
        LocalDate to = date("to", toValue);
        long difference = ChronoUnit.DAYS.between(from, to);
        if (difference < 0 || difference > MAXIMUM_DATE_DIFFERENCE) {
            throw invalid("to");
        }
        return new DateRange(from, to);
    }

    private static LocalDate date(String field, String value) {
        if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            throw invalid(field);
        }
        try {
            LocalDate parsed = LocalDate.parse(value);
            if (!parsed.toString().equals(value)) {
                throw invalid(field);
            }
            return parsed;
        } catch (DateTimeParseException failure) {
            throw invalid(field);
        }
    }

    private static LocaleCode locale(String value) {
        try {
            return LocaleCode.from(value);
        } catch (RuntimeException failure) {
            throw invalid("locale");
        }
    }

    private static Metric metric(String value) {
        try {
            return Metric.valueOf(value);
        } catch (RuntimeException failure) {
            throw invalid("metric");
        }
    }

    private static AnalyticsEventType eventType(String value) {
        try {
            return AnalyticsEventType.valueOf(value);
        } catch (RuntimeException failure) {
            throw invalid("eventType");
        }
    }

    private static Dimension dimension(String value) {
        try {
            return Dimension.valueOf(value);
        } catch (RuntimeException failure) {
            throw invalid("dimension");
        }
    }

    private static int limit(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,2}")) {
            throw invalid("limit");
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed > 100) {
                throw invalid("limit");
            }
            return parsed;
        } catch (NumberFormatException failure) {
            throw invalid("limit");
        }
    }

    private static void requireCompatible(
            Metric metric, AnalyticsEventType eventType) {
        if (metric != Metric.EVENT_COUNT
                && eventType != AnalyticsEventType.PAGE_VIEW) {
            throw invalid("eventType");
        }
    }

    private static void requireSiteZone(String zone) {
        if (!SITE_ZONE.equals(zone)) {
            throw new DomainException(
                    "ANALYTICS_ZONE_UNSUPPORTED",
                    HttpStatus.BAD_REQUEST,
                    Map.of("zone", "invalid"));
        }
    }

    private static DomainException invalid(String field) {
        return new DomainException(
                "ANALYTICS_QUERY_INVALID",
                HttpStatus.UNPROCESSABLE_ENTITY,
                Map.of(field, "invalid"));
    }

    private static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(body);
    }

    private record DateRange(LocalDate from, LocalDate to) {}
}
