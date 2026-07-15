package xyz.yychainsaw.portfolio.auth.session;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.yychainsaw.portfolio.audit.AuditCommand;
import xyz.yychainsaw.portfolio.audit.AuditOutcome;
import xyz.yychainsaw.portfolio.audit.AuditService;
import xyz.yychainsaw.portfolio.common.trace.TraceIds;

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class AdminSessionCleanupJob {
    private static final String CLEANUP_INTERVAL =
            "${portfolio.security.session.cleanup-interval:PT1M}";

    private final AdminSessionRepository repository;
    private final AdminSessionService service;
    private final SessionProperties properties;
    private final AuditService audit;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public AdminSessionCleanupJob(
            AdminSessionRepository repository,
            AdminSessionService service,
            SessionProperties properties,
            AuditService audit,
            TransactionTemplate transactions,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository is required");
        this.service = Objects.requireNonNull(service, "service is required");
        this.properties = Objects.requireNonNull(properties, "properties are required");
        this.audit = Objects.requireNonNull(audit, "audit is required");
        this.transactions = Objects.requireNonNull(transactions, "transactions are required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    @Scheduled(initialDelayString = CLEANUP_INTERVAL, fixedDelayString = CLEANUP_INTERVAL)
    public void scheduledRun() {
        runOnce();
    }

    public void runOnce() {
        requireNoAmbientTransaction();
        Instant now = clock.instant();
        Instant absoluteCutoff;
        long nowMillis;
        try {
            absoluteCutoff = now.minus(properties.absoluteLifetime());
            nowMillis = now.toEpochMilli();
        } catch (DateTimeException | ArithmeticException exception) {
            throw new IllegalStateException("session cleanup time range is invalid");
        }

        String priorTrace = MDC.get(TraceIds.MDC_KEY);
        String runTrace = UUID.randomUUID().toString().replace("-", "");
        MDC.put(TraceIds.MDC_KEY, runTrace);
        try {
            List<AdminSessionRepository.TerminalSession> expired =
                    transactions.execute(status -> expireAndAudit(now, absoluteCutoff, runTrace));
            if (expired == null) {
                throw new IllegalStateException("session cleanup transaction returned no result");
            }

            for (AdminSessionRepository.TerminalSession terminal
                    : repository.terminalSessionsStillLinked()) {
                service.deleteBestEffort(terminal.primaryId());
            }
            repository.deleteExpiredUnmanagedSpringSessions(nowMillis);
        } finally {
            if (priorTrace == null) {
                MDC.remove(TraceIds.MDC_KEY);
            } else {
                MDC.put(TraceIds.MDC_KEY, priorTrace);
            }
        }
    }

    private List<AdminSessionRepository.TerminalSession> expireAndAudit(
            Instant now, Instant absoluteCutoff, String runTrace) {
        List<AdminSessionRepository.TerminalSession> expired =
                repository.expireDue(now, absoluteCutoff);
        for (AdminSessionRepository.TerminalSession session : expired) {
            audit.record(new AuditCommand(
                    null,
                    "SESSION_EXPIRED",
                    "ADMIN_SESSION",
                    session.metadataId().toString(),
                    AuditOutcome.SUCCESS,
                    runTrace,
                    Map.of("reason", session.reason())));
        }
        return List.copyOf(expired);
    }

    private static void requireNoAmbientTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "session cleanup requires no ambient transaction");
        }
    }
}
