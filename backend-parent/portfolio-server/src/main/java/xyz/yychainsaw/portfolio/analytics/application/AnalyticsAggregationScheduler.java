package xyz.yychainsaw.portfolio.analytics.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import xyz.yychainsaw.portfolio.system.job.BackgroundJobService;

@Component
@ConditionalOnWebApplication(type = Type.SERVLET)
@ConditionalOnProperty(
        prefix = "portfolio.jobs",
        name = "worker-enabled",
        havingValue = "true")
@ConditionalOnProperty(
        prefix = "portfolio.analytics",
        name = "maintenance-scheduling-enabled",
        havingValue = "true",
        matchIfMissing = true)
public final class AnalyticsAggregationScheduler {
    private static final String JOB_TYPE = "ANALYTICS_AGGREGATE";
    private static final ZoneId SITE_ZONE = ZoneId.of("Asia/Hong_Kong");
    private static final DateTimeFormatter HOUR_KEY =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH");

    private final BackgroundJobService jobs;
    private final AnalyticsRules rules;
    private final AnalyticsRetentionRepository retention;
    private final Clock clock;

    public AnalyticsAggregationScheduler(
            BackgroundJobService jobs,
            AnalyticsRules rules,
            AnalyticsRetentionRepository retention,
            Clock clock) {
        this.jobs = Objects.requireNonNull(jobs, "job service is required");
        this.rules = Objects.requireNonNull(rules, "analytics rules are required");
        this.retention = Objects.requireNonNull(
                retention, "analytics recovery repository is required");
        this.clock = Objects.requireNonNull(clock, "analytics scheduler clock is required");
    }

    @Order(0)
    @EventListener(classes = ApplicationReadyEvent.class)
    public void onApplicationReady() {
        ZonedDateTime now = currentSiteTime();
        enqueuePreviousDay(now.toLocalDate());
        enqueueCurrentHour(now);
        enqueueRecovery(now);
    }

    @Scheduled(cron = "0 15 0 * * *", zone = "Asia/Hong_Kong")
    public void enqueueDaily() {
        ZonedDateTime now = currentSiteTime();
        enqueuePreviousDay(now.toLocalDate());
    }

    @Scheduled(cron = "0 15 * * * *", zone = "Asia/Hong_Kong")
    public void enqueueHourly() {
        enqueueCurrentHour(currentSiteTime());
    }

    @Scheduled(cron = "0 45 * * * *", zone = "Asia/Hong_Kong")
    public void enqueueRecovery() {
        enqueueRecovery(currentSiteTime());
    }

    private void enqueuePreviousDay(LocalDate currentDate) {
        LocalDate siteDate = currentDate.minusDays(1);
        String encoded = siteDate.toString();
        jobs.enqueue(
                JOB_TYPE,
                "analytics-aggregate:" + encoded + ":" + rules.version(),
                Map.of(
                        "siteDate", encoded,
                        "aggregationVersion", rules.version()));
    }

    private void enqueueCurrentHour(ZonedDateTime siteTime) {
        String encodedDate = siteTime.toLocalDate().toString();
        String encodedHour = HOUR_KEY.format(siteTime);
        jobs.enqueue(
                JOB_TYPE,
                "analytics-aggregate:" + encodedDate + ":" + rules.version()
                        + ":hour:" + encodedHour,
                Map.of(
                        "siteDate", encodedDate,
                        "aggregationVersion", rules.version()));
    }

    private void enqueueRecovery(ZonedDateTime siteTime) {
        LocalDate currentDate = siteTime.toLocalDate();
        retention.findFirstIncompleteAggregateDate(
                        siteTime.toInstant(), currentDate.minusDays(28))
                .ifPresent(siteDate -> {
                    String encodedDate = siteDate.toString();
                    jobs.enqueue(
                            JOB_TYPE,
                            "analytics-aggregate:" + encodedDate + ":" + rules.version()
                                    + ":repair:" + HOUR_KEY.format(siteTime),
                            Map.of(
                                    "siteDate", encodedDate,
                                    "aggregationVersion", rules.version()));
                });
    }

    private ZonedDateTime currentSiteTime() {
        Instant now = Objects.requireNonNull(
                clock.instant(), "analytics scheduler clock returned no instant");
        return now.atZone(SITE_ZONE);
    }
}
