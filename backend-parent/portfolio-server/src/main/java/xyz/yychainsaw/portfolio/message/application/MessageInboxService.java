package xyz.yychainsaw.portfolio.message.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.yychainsaw.portfolio.audit.AuditCommand;
import xyz.yychainsaw.portfolio.audit.AuditOutcome;
import xyz.yychainsaw.portfolio.audit.AuditService;
import xyz.yychainsaw.portfolio.auth.CurrentAdminProvider;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.message.application.MessageInboxRepository.InboxMessageRow;
import xyz.yychainsaw.portfolio.message.application.MessageInboxRepository.InboxMessageMutationRow;
import xyz.yychainsaw.portfolio.message.application.MessageInboxRepository.InboxMessageSummaryRow;
import xyz.yychainsaw.portfolio.message.application.MessageInboxRepository.InboxMessageStatusRow;

@Service
@ConditionalOnWebApplication(type = Type.SERVLET)
public final class MessageInboxService {
    private static final int DEFAULT_LIMIT = 30;
    private static final int MAXIMUM_LIMIT = 100;
    private static final int LIMIT_RAW_LENGTH = 16;
    private static final int TRANSACTION_TIMEOUT_SECONDS = 5;
    private static final ZoneId SITE_ZONE = ZoneId.of("Asia/Hong_Kong");
    private static final Pattern INTEGER = Pattern.compile("-?[0-9]{1,15}");
    private static final Set<String> SAFE_ERROR_CATEGORIES = Set.of(
            "SMTP_AUTHENTICATION_FAILED",
            "SMTP_CONNECTION_FAILED",
            "MESSAGE_PREPARATION_FAILED",
            "SMTP_DELIVERY_FAILED",
            "UNEXPECTED_DELIVERY_FAILURE",
            "DELIVERY_INTERRUPTED");

    private final CurrentAdminProvider currentAdmin;
    private final MessageInboxRepository repository;
    private final AuditService audit;
    private final TransactionOperations transactions;
    private final Clock clock;

    @Autowired
    public MessageInboxService(
            CurrentAdminProvider currentAdmin,
            MessageInboxRepository repository,
            AuditService audit,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this(
                currentAdmin,
                repository,
                audit,
                transactionTemplate(transactionManager),
                clock);
    }

    MessageInboxService(
            CurrentAdminProvider currentAdmin,
            MessageInboxRepository repository,
            AuditService audit,
            TransactionOperations transactions,
            Clock clock) {
        this.currentAdmin = Objects.requireNonNull(
                currentAdmin, "current administrator provider is required");
        this.repository = Objects.requireNonNull(repository, "message repository is required");
        this.audit = Objects.requireNonNull(audit, "audit service is required");
        this.transactions = Objects.requireNonNull(
                transactions, "message transactions are required");
        this.clock = Objects.requireNonNull(clock, "message clock is required");
    }

    public MessagePage list(String requestedStatus, String cursorToken, String requestedLimit) {
        requireActor();
        MessageStatus status = parseStatus(requestedStatus);
        MessageCursor cursor = parseCursor(cursorToken);
        int limit = parseLimit(requestedLimit);
        List<InboxMessageSummaryRow> fetched = repository.findPage(
                status, cursor, limit + 1);
        boolean hasMore = fetched.size() > limit;
        List<InboxMessageSummaryRow> selected = List.copyOf(
                fetched.subList(0, Math.min(limit, fetched.size())));
        List<MessageSummary> items = selected.stream()
                .map(MessageInboxService::summary)
                .toList();
        String nextCursor = null;
        if (hasMore) {
            InboxMessageSummaryRow last = selected.get(selected.size() - 1);
            nextCursor = new MessageCursor(last.createdAt(), last.id()).encode();
        }
        return new MessagePage(items, nextCursor);
    }

    public MessageDetail detail(UUID messageId) {
        requireActor();
        return detail(requireMessage(messageId));
    }

    public MessageDetail updateStatus(
            UUID messageId, MessageStatus newStatus, int expectedVersion) {
        UUID actor = requireActor();
        if (messageId == null || newStatus == null || expectedVersion < 0) {
            throw statusInvalid();
        }
        MessageDetail result = transactions.execute(transaction -> {
            InboxMessageStatusRow before = repository.findStatusById(messageId)
                    .orElseThrow(MessageInboxService::notFound);
            if (before.version() != expectedVersion) {
                throw versionConflict();
            }
            int updated = repository.updateStatus(
                    messageId, newStatus, expectedVersion, now());
            if (updated != 1) {
                throw versionConflict();
            }
            InboxMessageRow after = repository.findById(messageId)
                    .orElseThrow(MessageInboxService::internal);
            audit(actor, "MESSAGE_STATUS_UPDATE", messageId, Map.of(
                    "previousStatus", before.status().name(),
                    "newStatus", newStatus.name(),
                    "createdDate", createdDate(before.createdAt())));
            return detail(after);
        });
        return Objects.requireNonNull(result, "message status transaction returned no result");
    }

    public void retryEmail(UUID messageId) {
        UUID actor = requireActor();
        if (messageId == null) {
            throw notFound();
        }
        transactions.execute(transaction -> {
            InboxMessageMutationRow before = repository.lockMutationById(messageId)
                    .orElseThrow(MessageInboxService::notFound);
            if ((!"FAILED".equals(before.emailStatus())
                            && !"DEAD".equals(before.emailStatus()))
                    || before.emailAttempts() == Integer.MAX_VALUE) {
                throw emailRetryConflict();
            }
            int updated = repository.retryEmail(messageId, before.emailStatus(), now());
            if (updated != 1) {
                throw emailRetryConflict();
            }
            audit(actor, "MESSAGE_EMAIL_RETRY", messageId, Map.of(
                    "previousEmailStatus", before.emailStatus(),
                    "newEmailStatus", "PENDING",
                    "createdDate", createdDate(before.createdAt())));
            return null;
        });
    }

    public void delete(UUID messageId) {
        UUID actor = requireActor();
        if (messageId == null) {
            throw notFound();
        }
        transactions.execute(transaction -> {
            InboxMessageMutationRow before = repository.lockMutationById(messageId)
                    .orElseThrow(MessageInboxService::notFound);
            Instant deletionTime = now();
            if ("SENDING".equals(before.emailStatus())
                    && (before.emailLeaseUntil() == null
                            || !before.emailLeaseUntil().isBefore(deletionTime))) {
                throw deleteConflict();
            }
            if (repository.delete(messageId) != 1) {
                throw notFound();
            }
            audit(actor, "MESSAGE_DELETE", messageId, Map.of(
                    "previousStatus", before.status().name(),
                    "newStatus", "DELETED",
                    "createdDate", createdDate(before.createdAt())));
            return null;
        });
    }

    private UUID requireActor() {
        return Objects.requireNonNull(
                currentAdmin.requireAdminId(), "current administrator id is required");
    }

    private InboxMessageRow requireMessage(UUID messageId) {
        if (messageId == null) {
            throw notFound();
        }
        return repository.findById(messageId).orElseThrow(MessageInboxService::notFound);
    }

    private void audit(
            UUID actor,
            String action,
            UUID messageId,
            Map<String, String> metadata) {
        audit.record(new AuditCommand(
                actor,
                action,
                "CONTACT_MESSAGE",
                messageId.toString(),
                AuditOutcome.SUCCESS,
                null,
                metadata));
    }

    private Instant now() {
        return Objects.requireNonNull(clock.instant(), "message clock returned no instant")
                .truncatedTo(ChronoUnit.MICROS);
    }

    private static String createdDate(Instant createdAt) {
        return createdAt.atZone(SITE_ZONE).toLocalDate().toString();
    }

    private static MessageStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (!value.equals(value.strip())) {
            throw queryInvalid("status");
        }
        try {
            return MessageStatus.valueOf(value);
        } catch (IllegalArgumentException invalid) {
            throw queryInvalid("status");
        }
    }

    private static MessageCursor parseCursor(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return MessageCursor.decode(value);
    }

    private static int parseLimit(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_LIMIT;
        }
        String normalized = value.strip();
        if (value.length() > LIMIT_RAW_LENGTH || !INTEGER.matcher(normalized).matches()) {
            throw queryInvalid("limit");
        }
        long parsed;
        try {
            parsed = Long.parseLong(normalized);
        } catch (NumberFormatException invalid) {
            throw queryInvalid("limit");
        }
        return (int) Math.max(1L, Math.min(MAXIMUM_LIMIT, parsed));
    }

    private static MessageSummary summary(InboxMessageSummaryRow row) {
        return new MessageSummary(
                row.id(),
                row.visitorName(),
                row.visitorEmail(),
                row.subject(),
                row.status(),
                row.emailStatus(),
                row.createdAt(),
                row.version());
    }

    private static MessageDetail detail(InboxMessageRow row) {
        return new MessageDetail(
                row.id(),
                row.visitorName(),
                row.visitorEmail(),
                row.subject(),
                row.body(),
                row.status(),
                new EmailDeliveryView(
                        row.emailStatus(),
                        row.emailAttempts(),
                        row.emailNextAttemptAt(),
                        row.emailSentAt(),
                        row.emailUpdatedAt(),
                        errorCategory(row.lastErrorSummary())),
                row.privacyAcceptedAt(),
                row.createdAt(),
                row.updatedAt(),
                row.version());
    }

    private static String errorCategory(String summary) {
        if (summary == null) {
            return null;
        }
        int separator = summary.indexOf('|');
        if (separator <= 0 || separator != summary.lastIndexOf('|')) {
            return null;
        }
        String category = summary.substring(separator + 1);
        return SAFE_ERROR_CATEGORIES.contains(category) ? category : null;
    }

    private static TransactionTemplate transactionTemplate(
            PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(Objects.requireNonNull(
                transactionManager, "transaction manager is required"));
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setTimeout(TRANSACTION_TIMEOUT_SECONDS);
        return template;
    }

    private static DomainException queryInvalid(String field) {
        return new DomainException(
                "MESSAGE_QUERY_INVALID",
                HttpStatus.UNPROCESSABLE_ENTITY,
                Map.of(field, "invalid"));
    }

    private static DomainException statusInvalid() {
        return new DomainException(
                "MESSAGE_STATUS_INVALID",
                HttpStatus.UNPROCESSABLE_ENTITY,
                Map.of("status", "invalid"));
    }

    private static DomainException notFound() {
        return new DomainException("MESSAGE_NOT_FOUND", HttpStatus.NOT_FOUND, Map.of());
    }

    private static DomainException versionConflict() {
        return new DomainException(
                "MESSAGE_VERSION_CONFLICT", HttpStatus.CONFLICT, Map.of());
    }

    private static DomainException emailRetryConflict() {
        return new DomainException(
                "MESSAGE_EMAIL_RETRY_CONFLICT", HttpStatus.CONFLICT, Map.of());
    }

    private static DomainException deleteConflict() {
        return new DomainException(
                "MESSAGE_DELETE_CONFLICT", HttpStatus.CONFLICT, Map.of());
    }

    private static DomainException internal() {
        return new DomainException("INTERNAL_ERROR", HttpStatus.INTERNAL_SERVER_ERROR, Map.of());
    }
}
