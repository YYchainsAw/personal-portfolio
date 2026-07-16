package xyz.yychainsaw.portfolio.media.application;

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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import xyz.yychainsaw.portfolio.system.job.BackgroundJobService;

@Component
@ConditionalOnWebApplication(type = Type.SERVLET)
@ConditionalOnProperty(
        prefix = "portfolio",
        name = {"jobs.worker-enabled", "media.cleanup.enabled"},
        havingValue = "true")
public final class MediaCleanupScheduler {
    private static final ZoneId HONG_KONG = ZoneId.of("Asia/Hong_Kong");

    private final BackgroundJobService jobs;
    private final Clock clock;

    public MediaCleanupScheduler(
            BackgroundJobService jobs,
            Clock clock,
            MediaReferenceResolver references) {
        this.jobs = Objects.requireNonNull(jobs, "job service is required");
        this.clock = Objects.requireNonNull(clock, "media cleanup clock is required");
        Objects.requireNonNull(references, "media references are required")
                .requireCheckerForCleanup();
    }

    @EventListener(classes = ApplicationReadyEvent.class)
    public void onApplicationReady() {
        enqueueCurrentDay();
    }

    @Scheduled(cron = "0 30 4 * * *", zone = "Asia/Hong_Kong")
    public void enqueueDaily() {
        enqueueCurrentDay();
    }

    private void enqueueCurrentDay() {
        Instant now = Objects.requireNonNull(
                clock.instant(), "media cleanup clock returned no instant");
        LocalDate day = now.atZone(HONG_KONG).toLocalDate();
        Instant cutoff = day.minusDays(30).atStartOfDay(HONG_KONG).toInstant();
        jobs.enqueue(
                "MEDIA_CLEANUP_SCAN",
                "media-cleanup-scan:" + day,
                Map.of("cutoffEpochSecond", cutoff.getEpochSecond()));
    }
}
