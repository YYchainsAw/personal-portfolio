package xyz.yychainsaw.portfolio.auth.session;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.yychainsaw.portfolio.audit.AuditCommand;
import xyz.yychainsaw.portfolio.audit.AuditOutcome;
import xyz.yychainsaw.portfolio.audit.AuditService;
import xyz.yychainsaw.portfolio.auth.model.AdminStatus;
import xyz.yychainsaw.portfolio.auth.model.AdminUser;
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

    public ActiveSession requireCurrentSessionInCurrentTransaction(
            UUID adminId, ActiveSession expected) {
        return findCurrentSessionInCurrentTransaction(adminId, expected)
                .orElseThrow(AdminSessionService::unauthorized);
    }

    public Optional<ActiveSession> findCurrentSessionInCurrentTransaction(
            UUID adminId, ActiveSession expected) {
        Objects.requireNonNull(adminId, "admin id is required");
        Objects.requireNonNull(expected, "current session is required");
        requireAmbientTransaction("current-session lock requires an ambient transaction");
        if (!adminId.equals(expected.adminId())) {
            return Optional.empty();
        }

        AdminUser admin = adminRepository.findByIdForUpdate(adminId).orElse(null);
        if (admin == null) {
            return Optional.empty();
        }
        AdminSessionRepository.SessionRow row = repository
                .findByMetadataIdForUpdate(expected.metadataId(), adminId)
                .orElse(null);
        if (row == null) {
            return Optional.empty();
        }
        Instant now = Objects.requireNonNull(clock.instant(), "clock returned no instant");
        if (admin.status() != AdminStatus.ACTIVE
                || !isCurrentActive(row, expected, now)) {
            return Optional.empty();
        }
        return Optional.of(expected);
    }

    public List<AdminSessionRepository.TerminalSession>
            markOtherSessionsRevokedInCurrentTransaction(
                    UUID adminId, ActiveSession current, String reason) {
        Objects.requireNonNull(adminId, "admin id is required");
        Objects.requireNonNull(current, "current session is required");
        AdminSessionRepository.requireReason(reason);
        requireAmbientTransaction("session marking requires an ambient transaction");
        requireCurrentSessionInCurrentTransaction(adminId, current);

        List<AdminSessionRepository.TerminalSession> revoked =
                repository.markOtherRevoked(
                        adminId, current.metadataId(), reason,
                        Objects.requireNonNull(clock.instant(), "clock returned no instant"));
        for (AdminSessionRepository.TerminalSession terminal : revoked) {
            audit.record(new AuditCommand(
                    adminId,
                    "SESSION_REVOKED",
                    "ADMIN_SESSION",
                    terminal.metadataId().toString(),
                    AuditOutcome.SUCCESS,
                    null,
                    Map.of("reason", reason)));
        }
        return List.copyOf(revoked);
    }

    public List<AdminSessionRepository.TerminalSession>
            markAllSessionsRevokedInCurrentTransaction(UUID adminId, String reason) {
        Objects.requireNonNull(adminId, "admin id is required");
        AdminSessionRepository.requireReason(reason);
        requireAmbientTransaction("all-session marking requires an ambient transaction");

        List<AdminSessionRepository.TerminalSession> revoked = List.copyOf(
                repository.markAllRevoked(
                        adminId,
                        reason,
                        Objects.requireNonNull(clock.instant(), "clock returned no instant")));
        for (AdminSessionRepository.TerminalSession terminal : revoked) {
            audit.record(new AuditCommand(
                    adminId,
                    "SESSION_REVOKED",
                    "ADMIN_SESSION",
                    terminal.metadataId().toString(),
                    AuditOutcome.SUCCESS,
                    null,
                    Map.of("reason", reason)));
        }
        return revoked;
    }

    public void deleteMarkedSessions(
            List<AdminSessionRepository.TerminalSession> marked) {
        requireNoAmbientTransaction(
                "marked-session deletion requires no ambient transaction");
        List<AdminSessionRepository.TerminalSession> snapshot = List.copyOf(
                Objects.requireNonNull(marked, "marked sessions are required"));
        for (AdminSessionRepository.TerminalSession terminal : snapshot) {
            deleteBestEffort(terminal.primaryId());
        }
    }

    public void deleteAllSpringSessionsBestEffort() {
        requireNoAmbientTransaction(
                "all-session deletion requires no ambient transaction");
        try {
            repository.deleteAllSpringSessions();
        } catch (RuntimeException exception) {
            log.warn(
                    "Spring Session bulk deletion deferred for retry type={}",
                    exception.getClass().getName());
        }
    }

    public void revoke(UUID metadataId, ActiveSession actor, String reason) {
        Objects.requireNonNull(metadataId, "metadata id is required");
        Objects.requireNonNull(actor, "actor session is required");
        AdminSessionRepository.requireReason(reason);
        requireNoAmbientTransaction("session revoke requires no ambient transaction");

        AdminSessionRepository.TerminalSession revoked = transactions.execute(status -> {
            UUID actorAdminId = actor.adminId();
            ActiveSession lockedActor =
                    requireCurrentSessionInCurrentTransaction(actorAdminId, actor);
            if (!metadataId.equals(lockedActor.metadataId())) {
                AdminSessionRepository.SessionRow existing = repository
                        .findByMetadataIdForUpdate(metadataId, actorAdminId)
                        .orElseThrow(() -> new DomainException(
                                "SESSION_NOT_FOUND", HttpStatus.NOT_FOUND, Map.of()));
                if (existing.status() != AdminSessionStatus.ACTIVE) {
                    throw new DomainException(
                            "SESSION_NOT_ACTIVE", HttpStatus.CONFLICT, Map.of());
                }
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
        deleteMarkedSessions(List.of(revoked));
    }

    void deleteBestEffort(String primaryId) {
        if (primaryId == null) {
            return;
        }
        try {
            repository.deleteSpringSession(primaryId);
        } catch (RuntimeException exception) {
            log.warn(
                    "Spring Session row deletion deferred for retry type={}",
                    exception.getClass().getName());
        }
    }

    private boolean isCurrentActive(
            AdminSessionRepository.SessionRow row,
            ActiveSession expected,
            Instant now) {
        boolean absoluteExpired;
        try {
            absoluteExpired = !row.createdAt()
                    .plus(properties.absoluteLifetime())
                    .isAfter(now);
        } catch (DateTimeException | ArithmeticException invalidLifetime) {
            return false;
        }
        return row.status() == AdminSessionStatus.ACTIVE
                && row.adminId().equals(expected.adminId())
                && row.metadataId().equals(expected.metadataId())
                && row.springSessionPrimaryId() != null
                && row.springSessionPrimaryId().equals(expected.springSessionPrimaryId())
                && row.createdAt().equals(expected.createdAt())
                && row.expiryMillis() > now.toEpochMilli()
                && !absoluteExpired;
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

    private static void requireAmbientTransaction(String message) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
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
