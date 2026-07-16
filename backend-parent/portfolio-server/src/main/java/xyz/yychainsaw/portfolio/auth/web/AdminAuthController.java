package xyz.yychainsaw.portfolio.auth.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import xyz.yychainsaw.portfolio.auth.session.AdminSessionService;
import xyz.yychainsaw.portfolio.auth.session.AdminSessionService.ActiveSession;
import xyz.yychainsaw.portfolio.common.error.DomainException;

@RestController
@RequestMapping("/api/admin/auth")
@ConditionalOnWebApplication(type = Type.SERVLET)
public final class AdminAuthController {
    private static final String NEXT_SECOND_FACTOR = "SECOND_FACTOR";
    private static final String SESSION_COOKIE = "PORTFOLIO_SESSION";
    private static final String XSRF_COOKIE = "XSRF-TOKEN";
    private static final String CLEAR_SITE_DATA = "Clear-Site-Data";

    private final AdminAuthenticationService authentication;
    private final SecurityCurrentAdminProvider current;
    private final AdminSessionService sessions;
    private final SecurityContextLogoutHandler logout;
    private final ParentMutationGate parentMutations;

    public AdminAuthController(
            AdminAuthenticationService authentication,
            SecurityCurrentAdminProvider current,
            AdminSessionService sessions,
            SecurityContextLogoutHandler logout,
            ParentMutationGate parentMutations) {
        this.authentication = Objects.requireNonNull(
                authentication, "authentication service is required");
        this.current = Objects.requireNonNull(current, "current administrator is required");
        this.sessions = Objects.requireNonNull(sessions, "session service is required");
        this.logout = Objects.requireNonNull(logout, "logout handler is required");
        this.parentMutations = Objects.requireNonNull(
                parentMutations, "parent-mutation gate is required");
    }

    @GetMapping("/csrf")
    public ResponseEntity<CsrfResponse> csrf(CsrfToken token) {
        Objects.requireNonNull(token, "CSRF token is required");
        return noStore(new CsrfResponse(
                token.getHeaderName(), token.getParameterName(), token.getToken()));
    }

    @PostMapping("/password")
    public ResponseEntity<PasswordStageResponse> password(
            @Valid @RequestBody PasswordStageRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        try {
            Instant expiresAt = authentication.passwordStage(
                    body.username(), body.password(), request);
            return noStore(new PasswordStageResponse(NEXT_SECOND_FACTOR, expiresAt));
        } catch (DomainException expected) {
            copyRetryAfter(expected, response);
            throw expected;
        }
    }

    @PostMapping("/second-factor")
    public ResponseEntity<AdminResponse> secondFactor(
            @Valid @RequestBody SecondFactorRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        Optional<AdminPrincipal> result;
        try (ParentMutationGate.Lease ignored =
                     parentMutations.acquireSecondFactor(request, response)) {
            result = Objects.requireNonNull(
                    authentication.secondFactor(body.method(), body.code(), request, response),
                    "authentication service returned no result");
        } catch (DomainException expected) {
            copyRetryAfter(expected, response);
            throw expected;
        } catch (RuntimeException unexpected) {
            failClosed(request, response);
            throw unexpected;
        }
        AdminPrincipal principal = result.orElseThrow(AdminAuthController::authenticationFailed);
        return noStore(new AdminResponse(principal.id(), principal.username()));
    }

    @GetMapping("/me")
    public ResponseEntity<AdminResponse> me() {
        AdminPrincipal principal = current.requirePrincipal();
        return noStore(new AdminResponse(principal.id(), principal.username()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            HttpServletRequest request, HttpServletResponse response) {
        AdminPrincipal principal = current.requirePrincipal();
        ActiveSession active = requireCurrentActive(request, principal.id());
        Authentication localAuthentication =
                SecurityContextHolder.getContext().getAuthentication();
        try (ParentMutationGate.Lease ignored = parentMutations.acquire(response)) {
            sessions.revoke(active.metadataId(), active, "LOGOUT");
            response.setHeader(CLEAR_SITE_DATA, "\"cookies\"");
            logout.logout(request, response, localAuthentication);
            return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
        }
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

    private static void copyRetryAfter(
            DomainException failure, HttpServletResponse response) {
        if (!"RATE_LIMITED".equals(failure.code())) {
            return;
        }
        String value = failure.fieldErrors().get("retryAfterSeconds");
        if (value == null || !value.matches("[1-9][0-9]{0,9}")) {
            return;
        }
        response.setHeader(HttpHeaders.RETRY_AFTER, value);
    }

    private static void failClosed(
            HttpServletRequest request, HttpServletResponse response) {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            try {
                session.invalidate();
            } catch (IllegalStateException alreadyInvalid) {
                // The desired fail-closed state has already been reached.
            }
        }
        response.setHeader(CLEAR_SITE_DATA, "\"cookies\"");
        expireCookie(response, SESSION_COOKIE, true, request.isSecure());
        expireCookie(response, XSRF_COOKIE, false, request.isSecure());
    }

    private static void expireCookie(
            HttpServletResponse response, String name, boolean httpOnly, boolean secure) {
        ResponseCookie expired = ResponseCookie.from(name, "")
                .path("/")
                .httpOnly(httpOnly)
                .secure(secure)
                .sameSite("Strict")
                .maxAge(Duration.ZERO)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, expired.toString());
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static DomainException authenticationFailed() {
        return new DomainException(
                "AUTHENTICATION_FAILED", HttpStatus.UNAUTHORIZED, Map.of());
    }

    private static DomainException authenticationRequired() {
        return new DomainException(
                "AUTHENTICATION_REQUIRED", HttpStatus.UNAUTHORIZED, Map.of());
    }

    public record PasswordStageRequest(
            @Size(max = 128, message = "must be at most 128 UTF-16 units") String username,
            @Size(max = 256, message = "must be at most 256 UTF-16 units") String password) {
        @Override
        public String toString() {
            return "PasswordStageRequest[username=<redacted>, password=<redacted>]";
        }
    }

    public record SecondFactorRequest(
            SecondFactorMethod method,
            @Size(max = 64, message = "must be at most 64 UTF-16 units") String code) {
        public SecondFactorRequest {
            Objects.requireNonNull(method, "second-factor method is required");
        }

        @Override
        public String toString() {
            return "SecondFactorRequest[method=" + method + ", code=<redacted>]";
        }
    }

    public record CsrfResponse(String headerName, String parameterName, String token) {
        public CsrfResponse {
            headerName = requireText(headerName, "CSRF header name", 128);
            parameterName = requireText(parameterName, "CSRF parameter name", 128);
            token = requireText(token, "CSRF token", 4096);
        }

        @Override
        public String toString() {
            return "CsrfResponse[headerName=" + headerName
                    + ", parameterName=" + parameterName + ", token=<redacted>]";
        }
    }

    public record PasswordStageResponse(String next, Instant expiresAt) {
        public PasswordStageResponse {
            if (!NEXT_SECOND_FACTOR.equals(next)) {
                throw new IllegalArgumentException("next authentication stage is invalid");
            }
            Objects.requireNonNull(expiresAt, "expiry timestamp is required");
        }
    }

    public record AdminResponse(UUID id, String username) {
        public AdminResponse {
            Objects.requireNonNull(id, "administrator id is required");
            username = requireText(username, "administrator username", 64);
        }
    }

    private static String requireText(String value, String name, int maximumLength) {
        if (value == null
                || value.isBlank()
                || !value.equals(value.trim())
                || value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }
}

@Component
@ConditionalOnWebApplication(type = Type.SERVLET)
final class ParentMutationGate {
    private static final String RETRY_AFTER_SECONDS = "1";

    private final ReentrantLock mutation = new ReentrantLock();

    Lease acquireSecondFactor(
            HttpServletRequest request, HttpServletResponse response) {
        Objects.requireNonNull(request, "request is required");
        HttpSession session = request.getSession(false);
        if (session == null
                || !(session.getAttribute(PendingSecondFactor.SESSION_KEY)
                        instanceof PendingSecondFactor)) {
            return Lease.noop();
        }
        return acquire(response);
    }

    Lease acquire(HttpServletResponse response) {
        Objects.requireNonNull(response, "response is required");
        if (!mutation.tryLock()) {
            response.setHeader(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
            throw new DomainException(
                    "RATE_LIMITED",
                    HttpStatus.TOO_MANY_REQUESTS,
                    Map.of("retryAfterSeconds", RETRY_AFTER_SECONDS));
        }
        return new Lease(mutation);
    }

    static final class Lease implements AutoCloseable {
        private final ReentrantLock mutation;
        private boolean closed;

        private Lease(ReentrantLock mutation) {
            this.mutation = mutation;
        }

        static Lease noop() {
            return new Lease(null);
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            if (mutation != null) {
                mutation.unlock();
            }
            closed = true;
        }
    }
}
