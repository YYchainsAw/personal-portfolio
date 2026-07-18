package xyz.yychainsaw.portfolio.analytics.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.yychainsaw.portfolio.analytics.persistence.AnalyticsEventMapper;
import xyz.yychainsaw.portfolio.analytics.persistence.AnalyticsEventRecord;

@Service
public class AnalyticsEventDeduplicator {
    private static final Duration DUPLICATE_WINDOW = Duration.ofSeconds(10);
    private static final int TRANSACTION_TIMEOUT_SECONDS = 3;

    private final AnalyticsEventMapper events;
    private final AnalyticsPrivacyService privacy;
    private final TransactionTemplate transaction;

    public AnalyticsEventDeduplicator(
            AnalyticsEventMapper events,
            AnalyticsPrivacyService privacy,
            PlatformTransactionManager transactionManager) {
        this.events = Objects.requireNonNull(events, "analytics event mapper is required");
        this.privacy = Objects.requireNonNull(privacy, "analytics privacy service is required");
        this.transaction = transactionTemplate(transactionManager);
    }

    public boolean persist(AnalyticsEventRecord event) {
        Objects.requireNonNull(event, "analytics event is required");
        Boolean inserted = transaction.execute(ignored -> persistInTransaction(event));
        return Boolean.TRUE.equals(inserted);
    }

    private boolean persistInTransaction(AnalyticsEventRecord event) {
        long lockKey = privacy.dedupeLockKey(
                event.sessionDayKey(),
                event.eventType(),
                event.pageKey(),
                event.projectId());
        events.acquireDedupeLock(lockKey);
        Instant cutoff = event.receivedAt().minus(DUPLICATE_WINDOW);
        if (events.existsRecentTuple(event, cutoff)) {
            return false;
        }
        int inserted = events.insertIgnoreClientRetry(event);
        if (inserted < 0 || inserted > 1) {
            throw new IllegalStateException("analytics event insert returned an invalid count");
        }
        return inserted == 1;
    }

    private static TransactionTemplate transactionTemplate(
            PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(Objects.requireNonNull(
                transactionManager, "analytics transaction manager is required"));
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setTimeout(TRANSACTION_TIMEOUT_SECONDS);
        return template;
    }
}
