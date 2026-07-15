package xyz.yychainsaw.portfolio.auth.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import xyz.yychainsaw.portfolio.auth.CurrentAdminProvider;
import xyz.yychainsaw.portfolio.auth.session.AdminSessionService.ActiveSession;
import xyz.yychainsaw.portfolio.common.error.DomainException;

@Component
@ConditionalOnWebApplication(type = Type.SERVLET)
public final class SecurityCurrentAdminProvider implements CurrentAdminProvider {
    private static final String ADMIN_ROLE = "ROLE_ADMIN";

    private final HttpServletRequest request;

    public SecurityCurrentAdminProvider(HttpServletRequest request) {
        this.request = Objects.requireNonNull(request, "request is required");
    }

    @Override
    public UUID requireAdminId() {
        return requirePrincipal().id();
    }

    public AdminPrincipal requirePrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AdminPrincipal principal)
                || authentication.getAuthorities().stream()
                        .noneMatch(authority -> ADMIN_ROLE.equals(authority.getAuthority()))) {
            throw authenticationRequired();
        }
        Object value = request.getAttribute(
                SessionMetadataEnforcementFilter.ACTIVE_SESSION_ATTRIBUTE);
        if (!(value instanceof ActiveSession active)
                || !active.adminId().equals(principal.id())) {
            throw authenticationRequired();
        }
        return principal;
    }

    private static DomainException authenticationRequired() {
        return new DomainException(
                "AUTHENTICATION_REQUIRED", HttpStatus.UNAUTHORIZED, Map.of());
    }
}
