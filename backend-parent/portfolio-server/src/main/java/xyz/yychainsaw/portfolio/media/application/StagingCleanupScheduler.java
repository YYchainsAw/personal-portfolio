package xyz.yychainsaw.portfolio.media.application;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import xyz.yychainsaw.portfolio.media.storage.TencentCosStorageService;
import xyz.yychainsaw.portfolio.system.job.BackgroundJobService;

@Component
@ConditionalOnWebApplication(type = Type.SERVLET)
@ConditionalOnProperty(
        prefix = "portfolio",
        name = {"jobs.worker-enabled", "media.staging-cleanup.enabled"},
        havingValue = "true")
public final class StagingCleanupScheduler {
    private static final Pattern RELEASE_ID =
            Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Duration MINIMUM_RETRY = Duration.ofSeconds(1);
    private static final Duration MAXIMUM_RETRY = Duration.ofHours(1);
    private static final String RETRY_INTERVAL =
            "${portfolio.media.staging-cleanup.retry-interval:PT5M}";

    private final BackgroundJobService jobs;
    private final Clock clock;
    private final String releaseId;
    private final List<TencentCosStorageService> cosServices;
    private final AtomicBoolean scratchRetryPending = new AtomicBoolean();

    @Autowired
    public StagingCleanupScheduler(
            BackgroundJobService jobs,
            Clock clock,
            @Value("${portfolio.release-id}") String releaseId,
            List<TencentCosStorageService> cosServices,
            @Value(RETRY_INTERVAL) String retryInterval) {
        this(jobs, clock, releaseId, cosServices, parseRetryInterval(retryInterval));
    }

    StagingCleanupScheduler(
            BackgroundJobService jobs,
            Clock clock,
            String releaseId,
            List<TencentCosStorageService> cosServices,
            Duration retryInterval) {
        this.jobs = Objects.requireNonNull(jobs, "job service is required");
        this.clock = Objects.requireNonNull(clock, "cleanup clock is required");
        if (releaseId == null || !RELEASE_ID.matcher(releaseId).matches()) {
            throw new IllegalArgumentException(
                    "media staging cleanup release id is invalid");
        }
        if (retryInterval == null
                || retryInterval.compareTo(MINIMUM_RETRY) < 0
                || retryInterval.compareTo(MAXIMUM_RETRY) > 0) {
            throw new IllegalArgumentException(
                    "media staging cleanup retry interval is invalid");
        }
        this.releaseId = releaseId;
        this.cosServices = List.copyOf(Objects.requireNonNull(
                cosServices, "COS storage services are required"));
    }

    StagingCleanupScheduler(
            BackgroundJobService jobs,
            Clock clock,
            String releaseId,
            List<TencentCosStorageService> cosServices) {
        this(jobs, clock, releaseId, cosServices, Duration.ofMinutes(5));
    }

    @EventListener(classes = ApplicationReadyEvent.class)
    public void onApplicationReady() {
        enqueueAndScavengeScratch();
    }

    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Hong_Kong")
    public void enqueueDaily() {
        enqueueAndScavengeScratch();
    }

    @Scheduled(initialDelayString = RETRY_INTERVAL, fixedDelayString = RETRY_INTERVAL)
    public void retryPendingBoundary() {
        StagingCleanupBoundary boundary = enqueueCurrentBoundary();
        if (scratchRetryPending.get()) {
            scavengeScratch(boundary);
        }
    }

    private void enqueueAndScavengeScratch() {
        scratchRetryPending.set(true);
        StagingCleanupBoundary boundary = enqueueCurrentBoundary();
        scavengeScratch(boundary);
    }

    private synchronized void scavengeScratch(StagingCleanupBoundary boundary) {
        for (TencentCosStorageService cosService : cosServices) {
            try {
                cosService.cleanupScratch(boundary.cutoff());
            } catch (RuntimeException failure) {
                scratchRetryPending.set(true);
                throw new IllegalStateException("MEDIA_STAGING_CLEANUP_FAILED");
            }
        }
        scratchRetryPending.set(false);
    }

    private StagingCleanupBoundary enqueueCurrentBoundary() {
        StagingCleanupBoundary boundary = StagingCleanupBoundary.current(clock);
        jobs.enqueue(
                "CLEAN_MEDIA_STAGING",
                "media-staging-cleanup:" + releaseId + ":" + boundary.boundaryDate(),
                Map.of("cutoffEpochSecond", boundary.cutoff().getEpochSecond()));
        return boundary;
    }

    private static Duration parseRetryInterval(String value) {
        try {
            return Duration.parse(value);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(
                    "media staging cleanup retry interval is invalid");
        }
    }
}
