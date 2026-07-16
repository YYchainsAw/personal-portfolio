package xyz.yychainsaw.portfolio.auth.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import xyz.yychainsaw.portfolio.auth.session.AdminSessionRepository.SessionView;
import xyz.yychainsaw.portfolio.auth.session.AdminSessionService;
import xyz.yychainsaw.portfolio.auth.session.AdminSessionService.ActiveSession;
import xyz.yychainsaw.portfolio.auth.session.AdminSessionStatus;
import xyz.yychainsaw.portfolio.common.error.DomainException;

@RestController
@RequestMapping("/api/admin/security/sessions")
@ConditionalOnWebApplication(type = Type.SERVLET)
public final class AdminSecurityController {
    private static final String REVOCATION_REASON = "ADMIN_REQUEST";
    private static final String CLEAR_SITE_DATA = "Clear-Site-Data";

    private final SecurityCurrentAdminProvider current;
    private final AdminSessionService sessions;
    private final SecurityContextLogoutHandler logout;
    private final ParentMutationGate parentMutations;

    public AdminSecurityController(
            SecurityCurrentAdminProvider current,
            AdminSessionService sessions,
            SecurityContextLogoutHandler logout,
            ParentMutationGate parentMutations) {
        this.current = Objects.requireNonNull(current, "current administrator is required");
        this.sessions = Objects.requireNonNull(sessions, "session service is required");
        this.logout = Objects.requireNonNull(logout, "logout handler is required");
        this.parentMutations = Objects.requireNonNull(
                parentMutations, "parent-mutation gate is required");
    }

    @GetMapping
    public ResponseEntity<List<SessionResponse>> list(HttpServletRequest request) {
        AdminPrincipal principal = current.requirePrincipal();
        HttpSession session = requireSession(request);
        List<SessionResponse> body = sessions.list(principal.id(), session.getId()).stream()
                .map(SessionResponse::from)
                .toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    @PostMapping("/{metadataId}/revoke")
    public ResponseEntity<Void> revoke(
            @PathVariable UUID metadataId,
            HttpServletRequest request,
            HttpServletResponse response) {
        Objects.requireNonNull(metadataId, "metadata id is required");
        AdminPrincipal principal = current.requirePrincipal();
        ActiveSession active = requireCurrentActive(request, principal.id());
        boolean currentSession = active.metadataId().equals(metadataId);
        Authentication localAuthentication =
                SecurityContextHolder.getContext().getAuthentication();

        try (ParentMutationGate.Lease ignored = parentMutations.acquire(response)) {
            sessions.revoke(metadataId, active, REVOCATION_REASON);
            if (currentSession) {
                response.setHeader(CLEAR_SITE_DATA, "\"cookies\"");
                logout.logout(request, response, localAuthentication);
            }
            return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
        }
    }

    private static HttpSession requireSession(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            throw authenticationRequired();
        }
        return session;
    }

    private static ActiveSession requireCurrentActive(
            HttpServletRequest request, UUID adminId) {
        Object value = request.getAttribute(
                SessionMetadataEnforcementFilter.ACTIVE_SESSION_ATTRIBUTE);
        if (!(value instanceof ActiveSession active) || !active.adminId().equals(adminId)) {
            throw authenticationRequired();
        }
        return active;
    }

    private static DomainException authenticationRequired() {
        return new DomainException(
                "AUTHENTICATION_REQUIRED", HttpStatus.UNAUTHORIZED, Map.of());
    }

    public record SessionResponse(
            UUID id,
            AdminSessionStatus status,
            Instant createdAt,
            Instant endedAt,
            long lastAccessMillis,
            String clientSummary,
            String reason,
            boolean current) {
        public SessionResponse {
            Objects.requireNonNull(id, "metadata id is required");
            Objects.requireNonNull(status, "session status is required");
            Objects.requireNonNull(createdAt, "created timestamp is required");
            if (lastAccessMillis < 0) {
                throw new IllegalArgumentException("last-access timestamp is invalid");
            }
            if (clientSummary == null
                    || clientSummary.isBlank()
                    || !clientSummary.equals(clientSummary.trim())
                    || clientSummary.length() > 255) {
                throw new IllegalArgumentException("client summary is invalid");
            }
            if (status == AdminSessionStatus.ACTIVE) {
                if (endedAt != null || reason != null) {
                    throw new IllegalArgumentException("active session is terminal");
                }
            } else {
                Objects.requireNonNull(endedAt, "ended timestamp is required");
                if (reason == null || !reason.matches("[A-Z0-9_]{1,64}")) {
                    throw new IllegalArgumentException("session reason is invalid");
                }
                if (current) {
                    throw new IllegalArgumentException("terminal session cannot be current");
                }
            }
        }

        private static SessionResponse from(SessionView view) {
            Objects.requireNonNull(view, "session view is required");
            return new SessionResponse(
                    view.id(),
                    view.status(),
                    view.createdAt(),
                    view.endedAt(),
                    view.lastAccessMillis(),
                    view.clientSummary(),
                    view.reason(),
                    view.current());
        }
    }
}
