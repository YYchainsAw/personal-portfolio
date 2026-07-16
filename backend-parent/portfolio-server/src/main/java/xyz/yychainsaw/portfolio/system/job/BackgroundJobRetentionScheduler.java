package xyz.yychainsaw.portfolio.system.job;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnWebApplication(type = Type.SERVLET)
@ConditionalOnProperty(
        prefix = "portfolio.jobs.retention",
        name = "enabled",
        havingValue = "true")
public final class BackgroundJobRetentionScheduler {
    private static final Logger LOGGER =
            LoggerFactory.getLogger(BackgroundJobRetentionScheduler.class);

    private final BackgroundJobRetentionService service;

    public BackgroundJobRetentionScheduler(BackgroundJobRetentionService service) {
        this.service = Objects.requireNonNull(
                service, "background job retention service is required");
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Hong_Kong")
    public void deleteDaily() {
        try {
            BackgroundJobRetentionResult result =
                    service.deleteExpiredTerminalJobs();
            if (result == null) {
                throw new IllegalStateException(
                        "BACKGROUND_JOB_RETENTION_RESULT_INVALID");
            }
            LOGGER.info(
                    "Background job retention completed deletedCount={} batches={}",
                    result.deletedCount(),
                    result.batches());
        } catch (RuntimeException failure) {
            throw new IllegalStateException("BACKGROUND_JOB_RETENTION_FAILED");
        }
    }
}
