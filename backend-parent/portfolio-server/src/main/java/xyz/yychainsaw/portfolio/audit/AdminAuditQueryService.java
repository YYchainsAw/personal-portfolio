package xyz.yychainsaw.portfolio.audit;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import xyz.yychainsaw.portfolio.api.admin.audit.AdminAuditItem;
import xyz.yychainsaw.portfolio.api.admin.audit.AdminAuditPage;
import xyz.yychainsaw.portfolio.common.error.DomainException;

@Service
public final class AdminAuditQueryService {
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAXIMUM_LIMIT = 100;
    private static final int CURSOR_RAW_LIMIT = 86;
    private static final int ACTION_RAW_LIMIT = 128;
    private static final int OUTCOME_RAW_LIMIT = 16;
    private static final int TIMESTAMP_RAW_LIMIT = 64;
    private static final int LIMIT_RAW_LIMIT = 16;

    private final AdminAuditQueryRepository repository;

    public AdminAuditQueryService(AdminAuditQueryRepository repository) {
        this.repository = Objects.requireNonNull(repository, "audit repository is required");
    }

    public AdminAuditPage find(
            String cursorValue,
            String actionValue,
            String outcomeValue,
            String fromValue,
            String toValue,
            String limitValue) {
        String cursorToken = cursor(cursorValue);
        String action = normalized("action", actionValue, ACTION_RAW_LIMIT);
        if (action != null && !action.matches("[A-Z0-9_]{1,96}")) {
            throw invalid("action");
        }
        String outcome = normalized("outcome", outcomeValue, OUTCOME_RAW_LIMIT);
        if (outcome != null) {
            outcome = outcome.toUpperCase(Locale.ROOT);
            if (!"SUCCESS".equals(outcome) && !"FAILURE".equals(outcome)) {
                throw invalid("outcome");
            }
        }
        Instant from = timestamp("from", fromValue);
        Instant to = timestamp("to", toValue);
        if (from != null && to != null && !from.isBefore(to)) {
            throw invalid("to");
        }
        int limit = limit(limitValue);
        AuditCursor cursor = cursorToken == null ? null : AuditCursor.decode(cursorToken);

        try {
            List<AdminAuditItem> rows = List.copyOf(Objects.requireNonNull(
                    repository.find(
                            new AdminAuditQueryRepository.Query(
                                    action, outcome, from, to, cursor),
                            limit + 1),
                    "audit repository returned no rows"));
            boolean hasMore = rows.size() > limit;
            List<AdminAuditItem> items = List.copyOf(
                    rows.subList(0, Math.min(limit, rows.size())));
            String nextCursor = null;
            if (hasMore) {
                AdminAuditItem last = items.get(items.size() - 1);
                nextCursor = new AuditCursor(last.timestamp(), last.id()).encode();
            }
            return new AdminAuditPage(items, nextCursor);
        } catch (RuntimeException failure) {
            throw internal();
        }
    }

    private static String cursor(String value) {
        if (value == null) {
            return null;
        }
        if (value.length() > CURSOR_RAW_LIMIT) {
            throw cursorInvalid();
        }
        return value.isBlank() ? null : value;
    }

    private static String normalized(String field, String value, int rawLimit) {
        if (value == null) {
            return null;
        }
        if (value.length() > rawLimit) {
            throw invalid(field);
        }
        return value.isBlank() ? null : value.strip();
    }

    private static int limit(String value) {
        String normalized = normalized("limit", value, LIMIT_RAW_LIMIT);
        if (normalized == null) {
            return DEFAULT_LIMIT;
        }
        if (!normalized.matches("[1-9][0-9]{0,2}")) {
            throw invalid("limit");
        }
        int parsed;
        try {
            parsed = Integer.parseInt(normalized);
        } catch (NumberFormatException failure) {
            throw invalid("limit");
        }
        if (parsed > MAXIMUM_LIMIT) {
            throw invalid("limit");
        }
        return parsed;
    }

    private static Instant timestamp(String field, String value) {
        String normalized = normalized(field, value, TIMESTAMP_RAW_LIMIT);
        if (normalized == null) {
            return null;
        }
        try {
            Instant parsed = Instant.parse(normalized);
            if (!parsed.toString().equals(normalized) || !AuditCursor.isSupported(parsed)) {
                throw invalid(field);
            }
            return parsed;
        } catch (DateTimeParseException failure) {
            throw invalid(field);
        }
    }

    private static DomainException invalid(String field) {
        return new DomainException(
                "AUDIT_QUERY_INVALID",
                HttpStatus.UNPROCESSABLE_ENTITY,
                Map.of(field, "invalid"));
    }

    private static DomainException cursorInvalid() {
        return new DomainException(
                "AUDIT_CURSOR_INVALID",
                HttpStatus.UNPROCESSABLE_ENTITY,
                Map.of("cursor", "invalid"));
    }

    private static DomainException internal() {
        return new DomainException("INTERNAL_ERROR", HttpStatus.INTERNAL_SERVER_ERROR, Map.of());
    }
}
