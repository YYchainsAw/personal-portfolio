package xyz.yychainsaw.portfolio.message.application;

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
        prefix = "portfolio.jobs",
        name = "worker-enabled",
        havingValue = "true")
@ConditionalOnProperty(
        prefix = "portfolio.message.retention",
        name = "scheduling-enabled",
        havingValue = "true",
        matchIfMissing = true)
public final class MessageRetentionScheduler {
    private static final String JOB_TYPE = "CONTACT_RETENTION";
    private static final ZoneId HONG_KONG = ZoneId.of("Asia/Hong_Kong");

    private final BackgroundJobService jobs;
    private final Clock clock;

    public MessageRetentionScheduler(BackgroundJobService jobs, Clock clock) {
        this.jobs = Objects.requireNonNull(jobs, "job service is required");
        this.clock = Objects.requireNonNull(clock, "retention clock is required");
    }

    @EventListener(classes = ApplicationReadyEvent.class)
    public void onApplicationReady() {
        enqueueCurrentDay();
    }

    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Hong_Kong")
    public void enqueueDaily() {
        enqueueCurrentDay();
    }

    void enqueueCurrentDay() {
        Instant now = Objects.requireNonNull(
                clock.instant(), "retention clock returned no instant");
        LocalDate siteDate = now.atZone(HONG_KONG).toLocalDate();
        String encodedDate = siteDate.toString();
        jobs.enqueue(
                JOB_TYPE,
                "contact-retention:" + encodedDate,
                Map.of("siteDate", encodedDate));
    }
}
