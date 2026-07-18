package xyz.yychainsaw.portfolio.analytics.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
public final class AnalyticsRetentionScheduler {
    private static final String JOB_TYPE = "ANALYTICS_RETENTION";
    private static final ZoneId SITE_ZONE = ZoneId.of("Asia/Hong_Kong");

    private final BackgroundJobService jobs;
    private final Clock clock;

    public AnalyticsRetentionScheduler(BackgroundJobService jobs, Clock clock) {
        this.jobs = Objects.requireNonNull(jobs, "job service is required");
        this.clock = Objects.requireNonNull(clock, "analytics scheduler clock is required");
    }

    @Order(1)
    @EventListener(classes = ApplicationReadyEvent.class)
    public void onApplicationReady() {
        enqueueCurrentDay();
    }

    @Scheduled(cron = "0 15 2 * * *", zone = "Asia/Hong_Kong")
    public void enqueueDaily() {
        enqueueCurrentDay();
    }

    void enqueueCurrentDay() {
        Instant now = Objects.requireNonNull(
                clock.instant(), "analytics scheduler clock returned no instant");
        LocalDate siteDate = now.atZone(SITE_ZONE).toLocalDate();
        String encoded = siteDate.toString();
        jobs.enqueue(
                JOB_TYPE,
                "analytics-retention:" + encoded,
                Map.of("siteDate", encoded));
    }
}
