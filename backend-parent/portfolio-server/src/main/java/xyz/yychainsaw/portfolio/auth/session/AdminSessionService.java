package xyz.yychainsaw.portfolio.auth.session;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.yychainsaw.portfolio.audit.AuditCommand;
import xyz.yychainsaw.portfolio.audit.AuditOutcome;
import xyz.yychainsaw.portfolio.audit.AuditService;
import xyz.yychainsaw.portfolio.auth.persistence.AdminUserRepository;
import xyz.yychainsaw.portfolio.common.error.DomainException;

@Service
public final class AdminSessionService {
    private static final Logger log = LoggerFactory.getLogger(AdminSessionService.class);

    private final AdminSessionRepository repository;
    private final AdminUserRepository adminRepository;
    private final SessionProperties properties;
    private final AuditService audit;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public AdminSessionService(
            AdminSessionRepository repository,
            AdminUserRepository adminRepository,
            SessionProperties properties,
            AuditService audit,
            TransactionTemplate transactions,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository is required");
        this.adminRepository = Objects.requireNonNull(
                adminRepository, "admin repository is required");
        this.properties = Objects.requireNonNull(properties, "properties are required");
        this.audit = Objects.requireNonNull(audit, "audit is required");
        this.transactions = Objects.requireNonNull(transactions, "transactions are required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    public String requireSpringPrimaryId(String publicSessionId) {
        requireValidPublicSessionId(publicSessionId);
        return repository.findPrimaryIdByPublicSessionId(publicSessionId)
                .orElseThrow(AdminSessionService::unauthorized);
    }

    public UUID start(UUID adminId, String primaryId, String clientSummary) {
        return startAtForTest(adminId, primaryId, clientSummary, clock.instant());
    }

    UUID startAtForTest(
            UUID adminId, String primaryId, String summary, Instant createdAt) {
        Objects.requireNonNull(adminId, "admin id is required");
        AdminSessionRepository.requireStablePrimaryId(primaryId);
        AdminSessionRepository.requireClientSummary(summary);
        Objects.requireNonNull(createdAt, "created timestamp is required");
        return repository.insertActive(adminId, primaryId, summary, createdAt);
    }

    public ActiveSession requireActive(String publicSessionId) {
        requireValidPublicSessionId(publicSessionId);
        AdminSessionRepository.SessionRow row = repository
                .findByPublicSessionId(publicSessionId)
                .orElseThrow(AdminSessionService::unauthorized);
        Instant now = clock.instant();
        boolean absoluteExpired;
        try {
            absoluteExpired = !row.createdAt().plus(properties.absoluteLifetime()).isAfter(now);
        } catch (DateTimeException | ArithmeticException exception) {
            throw unauthorized();
        }
        if (row.status() != AdminSessionStatus.ACTIVE
                || row.expiryMillis() <= now.toEpochMilli()
                || absoluteExpired) {
            throw unauthorized();
        }
        return new ActiveSession(
                row.metadataId(),
                row.adminId(),
                row.springSessionPrimaryId(),
                row.createdAt(),
                Instant.ofEpochMilli(row.lastAccessMillis()));
    }

    public List<AdminSessionRepository.SessionView> list(
            UUID adminId, String currentPublicSessionId) {
        Objects.requireNonNull(adminId, "admin id is required");
        requireValidPublicSessionId(currentPublicSessionId);
        return List.copyOf(repository.list(adminId, currentPublicSessionId));
    }

    public void revoke(UUID metadataId, UUID actorAdminId, String reason) {
        Objects.requireNonNull(metadataId, "metadata id is required");
        Objects.requireNonNull(actorAdminId, "admin id is required");
        AdminSessionRepository.requireReason(reason);
        requireNoAmbientTransaction("session revoke requires no ambient transaction");

        AdminSessionRepository.TerminalSession revoked = transactions.execute(status -> {
            adminRepository.findByIdForUpdate(actorAdminId)
                    .orElseThrow(AdminSessionService::unauthorized);
            AdminSessionRepository.SessionRow existing = repository
                    .findByMetadataId(metadataId, actorAdminId)
                    .orElseThrow(() -> new DomainException(
                            "SESSION_NOT_FOUND", HttpStatus.NOT_FOUND, Map.of()));
            if (existing.status() != AdminSessionStatus.ACTIVE) {
                throw new DomainException(
                        "SESSION_NOT_ACTIVE", HttpStatus.CONFLICT, Map.of());
            }
            AdminSessionRepository.TerminalSession terminal = repository
                    .markRevoked(metadataId, actorAdminId, reason, clock.instant())
                    .orElseThrow(() -> new DomainException(
                            "SESSION_NOT_ACTIVE", HttpStatus.CONFLICT, Map.of()));
            audit.record(new AuditCommand(
                    actorAdminId,
                    "SESSION_REVOKED",
                    "ADMIN_SESSION",
                    metadataId.toString(),
                    AuditOutcome.SUCCESS,
                    null,
                    Map.of("reason", reason)));
            return terminal;
        });
        if (revoked == null) {
            throw new IllegalStateException("session revoke transaction returned no result");
        }
        deleteBestEffort(revoked.primaryId());
    }

    void deleteBestEffort(String primaryId) {
        if (primaryId == null) {
            return;
        }
        try {
            repository.deleteSpringSession(primaryId);
        } catch (DataAccessException exception) {
            log.warn(
                    "Spring Session row deletion deferred for retry type={}",
                    exception.getClass().getName());
        }
    }

    private static void requireValidPublicSessionId(String value) {
        if (!AdminSessionRepository.isCanonicalSessionId(value)) {
            throw unauthorized();
        }
    }

    private static void requireNoAmbientTransaction(String message) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(message);
        }
    }

    private static DomainException unauthorized() {
        return new DomainException(
                "AUTHENTICATION_REQUIRED", HttpStatus.UNAUTHORIZED, Map.of());
    }

    public record ActiveSession(
            UUID metadataId,
            UUID adminId,
            String springSessionPrimaryId,
            Instant createdAt,
            Instant lastAccessAt) {
        public ActiveSession {
            Objects.requireNonNull(metadataId, "metadata id is required");
            Objects.requireNonNull(adminId, "admin id is required");
            AdminSessionRepository.requireStablePrimaryId(springSessionPrimaryId);
            Objects.requireNonNull(createdAt, "created timestamp is required");
            Objects.requireNonNull(lastAccessAt, "last-access timestamp is required");
        }

        @Override
        public String toString() {
            return "ActiveSession[metadataId=" + metadataId
                    + ", adminId=" + adminId
                    + ", springSessionPrimaryId=<redacted>"
                    + ", createdAt=" + createdAt
                    + ", lastAccessAt=" + lastAccessAt + "]";
        }
    }
}
