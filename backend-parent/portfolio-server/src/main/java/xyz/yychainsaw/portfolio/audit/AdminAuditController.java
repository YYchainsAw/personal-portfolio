package xyz.yychainsaw.portfolio.audit;

import java.util.Map;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import xyz.yychainsaw.portfolio.api.admin.audit.AdminAuditPage;
import xyz.yychainsaw.portfolio.auth.CurrentAdminProvider;
import xyz.yychainsaw.portfolio.common.error.DomainException;

@RestController
@RequestMapping("/api/admin/audit")
@ConditionalOnWebApplication(type = Type.SERVLET)
public final class AdminAuditController {
    private final CurrentAdminProvider current;
    private final AdminAuditQueryService queries;

    public AdminAuditController(
            CurrentAdminProvider current, AdminAuditQueryService queries) {
        this.current = Objects.requireNonNull(
                current, "current administrator provider is required");
        this.queries = Objects.requireNonNull(queries, "audit query service is required");
    }

    @GetMapping
    public ResponseEntity<AdminAuditPage> find(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String limit) {
        requireCurrentAdministrator();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(queries.find(cursor, action, outcome, from, to, limit));
    }

    @RequestMapping(method = RequestMethod.HEAD)
    public ResponseEntity<Void> head(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String limit) {
        requireCurrentAdministrator();
        queries.find(cursor, action, outcome, from, to, limit);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .build();
    }

    private void requireCurrentAdministrator() {
        try {
            Objects.requireNonNull(
                    current.requireAdminId(), "current administrator id is required");
        } catch (RuntimeException failure) {
            throw internal();
        }
    }

    private static DomainException internal() {
        return new DomainException("INTERNAL_ERROR", HttpStatus.INTERNAL_SERVER_ERROR, Map.of());
    }
}
