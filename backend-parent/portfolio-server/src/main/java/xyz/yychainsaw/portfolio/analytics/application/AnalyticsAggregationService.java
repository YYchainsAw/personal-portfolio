package xyz.yychainsaw.portfolio.analytics.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.yychainsaw.portfolio.analytics.persistence.AnalyticsDailyMapper;

@Service
public class AnalyticsAggregationService {
    private static final ZoneId SITE_ZONE = ZoneId.of("Asia/Hong_Kong");
    private static final Duration RAW_EVENT_RETENTION = Duration.ofDays(30);
    private static final int TRANSACTION_TIMEOUT_SECONDS = 15;
    private static final String DATE_EXPIRED = "ANALYTICS_AGGREGATION_DATE_EXPIRED";
    private static final String RETENTION_REPAIR_UNSAFE =
            "ANALYTICS_RETENTION_REPAIR_UNSAFE";
    private static final String FAILURE_CODE = "ANALYTICS_AGGREGATION_FAILED";

    private final AnalyticsDailyMapper mapper;
    private final AnalyticsRules rules;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public AnalyticsAggregationService(
            AnalyticsDailyMapper mapper,
            AnalyticsRules rules,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.mapper = Objects.requireNonNull(mapper, "analytics daily mapper is required");
        this.rules = Objects.requireNonNull(rules, "analytics rules are required");
        this.clock = Objects.requireNonNull(clock, "analytics aggregation clock is required");
        this.transaction = transactionTemplate(transactionManager);
    }

    public AggregationResult rebuild(LocalDate siteDate) {
        LocalDate requested = Objects.requireNonNull(
                siteDate, "analytics aggregation date is required");
        try {
            AggregationResult result = transaction.execute(
                    status -> rebuildInTransaction(requested, null));
            return Objects.requireNonNull(result);
        } catch (RuntimeException exception) {
            if (isFixedFailure(exception, DATE_EXPIRED)) {
                throw exception;
            }
            throw failure();
        }
    }

    public AggregationResult rebuildForRetention(
            LocalDate siteDate, Instant retentionCutoff) {
        LocalDate requested = Objects.requireNonNull(
                siteDate, "analytics retention repair date is required");
        Instant cutoff = Objects.requireNonNull(
                retentionCutoff, "analytics retention repair cutoff is required");
        try {
            AggregationResult result = transaction.execute(
                    status -> rebuildInTransaction(requested, cutoff));
            return Objects.requireNonNull(result);
        } catch (RuntimeException exception) {
            if (isFixedFailure(exception, RETENTION_REPAIR_UNSAFE)) {
                throw exception;
            }
            throw failure();
        }
    }

    private AggregationResult rebuildInTransaction(
            LocalDate siteDate, Instant retentionCutoff) {
        String version = rules.version();
        mapper.acquireAggregationLocks(siteDate, version);
        if (mapper.hasRetentionCheckpoint(siteDate)) {
            throw new IllegalStateException(retentionCutoff == null
                    ? DATE_EXPIRED
                    : RETENTION_REPAIR_UNSAFE);
        }
        Instant currentTime = now();
        if (retentionCutoff == null) {
            requireRebuildable(siteDate, currentTime);
        } else {
            requireRetentionRepairable(siteDate, retentionCutoff, currentTime);
        }
        mapper.deleteDate(siteDate);
        AnalyticsDailyMapper.AggregationCounts counts =
                mapper.insertAggregates(siteDate, version);
        return new AggregationResult(counts.inputCount(), counts.outputCount());
    }

    private Instant now() {
        return Objects.requireNonNull(clock.instant(), "analytics aggregation clock returned no instant");
    }

    private static void requireRebuildable(LocalDate siteDate, Instant currentTime) {
        LocalDate currentSiteDate = currentTime.atZone(SITE_ZONE).toLocalDate();
        Instant cutoff = currentTime.minus(RAW_EVENT_RETENTION);
        Instant siteDayStart = siteDate.atStartOfDay(SITE_ZONE).toInstant();
        if (siteDate.isAfter(currentSiteDate) || siteDayStart.isBefore(cutoff)) {
            throw new IllegalStateException(DATE_EXPIRED);
        }
    }

    private static void requireRetentionRepairable(
            LocalDate siteDate, Instant cutoff, Instant currentTime) {
        LocalDate currentSiteDate = currentTime.atZone(SITE_ZONE).toLocalDate();
        LocalDate cutoffSiteDate = cutoff.atZone(SITE_ZONE).toLocalDate();
        if (cutoff.isAfter(currentTime)
                || siteDate.isAfter(currentSiteDate)
                || siteDate.isAfter(cutoffSiteDate)) {
            throw new IllegalStateException(RETENTION_REPAIR_UNSAFE);
        }
    }

    private static TransactionTemplate transactionTemplate(
            PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(Objects.requireNonNull(
                transactionManager, "analytics transaction manager is required"));
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setTimeout(TRANSACTION_TIMEOUT_SECONDS);
        return template;
    }

    private static boolean isFixedFailure(RuntimeException exception, String message) {
        return exception.getClass() == IllegalStateException.class
                && message.equals(exception.getMessage())
                && exception.getCause() == null;
    }

    private static IllegalStateException failure() {
        return new IllegalStateException(FAILURE_CODE);
    }

    public record AggregationResult(long inputCount, long outputCount) {
        public AggregationResult {
            if (inputCount < 0 || outputCount < 0) {
                throw new IllegalArgumentException("analytics aggregation counts are invalid");
            }
        }
    }
}
