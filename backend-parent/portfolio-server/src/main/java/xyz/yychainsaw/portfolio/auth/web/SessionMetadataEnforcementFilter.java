package xyz.yychainsaw.portfolio.auth.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.util.UrlPathHelper;
import org.springframework.web.filter.OncePerRequestFilter;
import xyz.yychainsaw.portfolio.auth.session.AdminSessionService;
import xyz.yychainsaw.portfolio.auth.session.AdminSessionService.ActiveSession;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.common.trace.TraceIds;

public final class SessionMetadataEnforcementFilter extends OncePerRequestFilter {
    static final String ACTIVE_SESSION_ATTRIBUTE =
            SessionMetadataEnforcementFilter.class.getName() + ".activeSession";
    private static final String ADMIN_ROLE = "ROLE_ADMIN";
    private static final String CLEAR_SITE_DATA = "Clear-Site-Data";
    private static final Logger log =
            LoggerFactory.getLogger(SessionMetadataEnforcementFilter.class);

    private final AdminSessionService sessions;
    private final SecurityContextRepository contexts;
    private final SecurityProblemWriter problems;
    private final RequestMatcher protectedAdminApi =
            new AntPathRequestMatcher("/api/admin/**", null, true, new UrlPathHelper());

    public SessionMetadataEnforcementFilter(
            AdminSessionService sessions,
            SecurityContextRepository contexts,
            SecurityProblemWriter problems) {
        this.sessions = Objects.requireNonNull(sessions, "session service is required");
        this.contexts = Objects.requireNonNull(contexts, "security context repository is required");
        this.problems = Objects.requireNonNull(problems, "problem writer is required");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !protectedAdminApi.matches(request);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!(authentication.getPrincipal() instanceof AdminPrincipal principal)) {
            rejectAndInvalidate(request, response);
            return;
        }
        if (!hasAdminRole(authentication)) {
            filterChain.doFilter(request, response);
            return;
        }

        ActiveSession active;
        try {
            HttpSession session = request.getSession(false);
            if (session == null) {
                throw authenticationRequired();
            }
            active = sessions.requireActive(session.getId());
            if (!active.adminId().equals(principal.id())) {
                throw authenticationRequired();
            }
        } catch (DomainException expected) {
            if (isAuthenticationRequired(expected)) {
                rejectAndInvalidate(request, response);
            } else {
                internalFailure(response, expected);
            }
            return;
        } catch (RuntimeException unexpected) {
            internalFailure(response, unexpected);
            return;
        }

        request.setAttribute(ACTIVE_SESSION_ATTRIBUTE, active);
        filterChain.doFilter(request, response);
    }

    private void rejectAndInvalidate(
            HttpServletRequest request, HttpServletResponse response) throws IOException {
        SecurityContextHolder.clearContext();
        try {
            contexts.saveContext(
                    SecurityContextHolder.createEmptyContext(), request, response);
        } catch (RuntimeException cleanupFailure) {
            logCleanupFailure(cleanupFailure);
        }
        HttpSession session = request.getSession(false);
        if (session != null) {
            try {
                session.invalidate();
            } catch (RuntimeException cleanupFailure) {
                logCleanupFailure(cleanupFailure);
            }
        }
        response.setHeader(CLEAR_SITE_DATA, "\"cookies\"");
        problems.write(response, HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED");
    }

    private void internalFailure(HttpServletResponse response, RuntimeException failure)
            throws IOException {
        log.error(
                "Administrator session enforcement failed traceId={} type={}",
                TraceIds.current(),
                failure.getClass().getName());
        SecurityContextHolder.clearContext();
        problems.write(response, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");
    }

    private static boolean hasAdminRole(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> ADMIN_ROLE.equals(authority.getAuthority()));
    }

    private static boolean isAuthenticationRequired(DomainException failure) {
        return failure.status() == HttpStatus.UNAUTHORIZED
                && "AUTHENTICATION_REQUIRED".equals(failure.code());
    }

    private static DomainException authenticationRequired() {
        return new DomainException(
                "AUTHENTICATION_REQUIRED", HttpStatus.UNAUTHORIZED, Map.of());
    }

    private static void logCleanupFailure(RuntimeException failure) {
        log.warn(
                "Administrator session cleanup deferred traceId={} type={}",
                TraceIds.current(),
                failure.getClass().getName());
    }
}
