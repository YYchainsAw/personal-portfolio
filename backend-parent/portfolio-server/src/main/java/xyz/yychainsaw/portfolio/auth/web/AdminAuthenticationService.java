package xyz.yychainsaw.portfolio.auth.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import xyz.yychainsaw.portfolio.audit.AuditCommand;
import xyz.yychainsaw.portfolio.audit.AuditOutcome;
import xyz.yychainsaw.portfolio.audit.AuditService;
import xyz.yychainsaw.portfolio.auth.RecoveryCodeService;
import xyz.yychainsaw.portfolio.auth.crypto.TotpProperties;
import xyz.yychainsaw.portfolio.auth.crypto.TotpService;
import xyz.yychainsaw.portfolio.auth.model.AdminStatus;
import xyz.yychainsaw.portfolio.auth.model.AdminUser;
import xyz.yychainsaw.portfolio.auth.persistence.AdminUserRepository;
import xyz.yychainsaw.portfolio.auth.session.AdminSessionService;
import xyz.yychainsaw.portfolio.auth.session.ClientSummaryFactory;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.common.ratelimit.RateLimitDecision;
import xyz.yychainsaw.portfolio.common.ratelimit.RateLimitProperties;
import xyz.yychainsaw.portfolio.common.ratelimit.RateLimiter;

@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AdminAuthenticationService {
    private static final String LOGIN_POLICY = "admin-login";
    private static final String DUMMY_PASSWORD_INPUT = "portfolio-dummy-password";
    private static final int MAXIMUM_USERNAME_CODE_POINTS = 64;
    private static final int MAXIMUM_PASSWORD_CODE_POINTS = 128;
    private static final int MAXIMUM_FACTOR_CODE_POINTS = 64;
    private static final int MAXIMUM_PASSWORD_HASH_UNITS = 255;
    private static final Pattern CANONICAL_USERNAME =
            Pattern.compile("[A-Za-z0-9._-]{3,64}");
    private static final SimpleGrantedAuthority ADMIN_AUTHORITY =
            new SimpleGrantedAuthority("ROLE_ADMIN");

    private final AdminUserRepository admins;
    private final PasswordEncoder passwords;
    private final TotpService totp;
    private final RecoveryCodeService recovery;
    private final TotpProperties totpProperties;
    private final RateLimiter limiter;
    private final LoginSubjectHasher subjects;
    private final AdminSessionService sessions;
    private final ClientSummaryFactory clientSummaries;
    private final SessionAuthenticationStrategy sessionStrategy;
    private final SecurityContextRepository securityContexts;
    private final AuditService audit;
    private final Clock clock;
    private final String dummyPasswordHash;
    private final ChallengeGate challengeGate;

    public AdminAuthenticationService(
            AdminUserRepository admins,
            PasswordEncoder passwords,
            TotpService totp,
            RecoveryCodeService recovery,
            TotpProperties totpProperties,
            RateLimiter limiter,
            LoginSubjectHasher subjects,
            AdminSessionService sessions,
            ClientSummaryFactory clientSummaries,
            SessionAuthenticationStrategy sessionStrategy,
            SecurityContextRepository securityContexts,
            AuditService audit,
            Clock clock,
            RateLimitProperties rateLimitProperties) {
        this.admins = Objects.requireNonNull(admins, "admin repository is required");
        this.passwords = Objects.requireNonNull(passwords, "password encoder is required");
        this.totp = Objects.requireNonNull(totp, "TOTP service is required");
        this.recovery = Objects.requireNonNull(recovery, "recovery-code service is required");
        this.totpProperties = Objects.requireNonNull(
                totpProperties, "TOTP properties are required");
        this.limiter = Objects.requireNonNull(limiter, "rate limiter is required");
        this.subjects = Objects.requireNonNull(subjects, "login subject hasher is required");
        this.sessions = Objects.requireNonNull(sessions, "admin session service is required");
        this.clientSummaries = Objects.requireNonNull(
                clientSummaries, "client summary factory is required");
        this.sessionStrategy = Objects.requireNonNull(
                sessionStrategy, "session authentication strategy is required");
        this.securityContexts = Objects.requireNonNull(
                securityContexts, "security context repository is required");
        this.audit = Objects.requireNonNull(audit, "audit service is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
        RateLimitProperties limits = Objects.requireNonNull(
                rateLimitProperties, "rate-limit properties are required");
        this.challengeGate = new ChallengeGate(
                limits.maximumSubjects(), totpProperties.maxSecondFactorAttempts());
        this.dummyPasswordHash = createDummyPasswordHash(passwords);
    }

    public Instant passwordStage(
            String username, String password, HttpServletRequest request) {
        Objects.requireNonNull(request, "request is required");

        HttpSession existingSession = request.getSession(false);
        clearPreviousPending(existingSession);

        RateLimitDecision loginLimit = consumeLoginLimit(request, username);
        if (!loginLimit.allowed()) {
            throw rateLimited(loginLimit.retryAfterSeconds());
        }

        boolean usernameBounded = isBoundedUtf16(username, MAXIMUM_USERNAME_CODE_POINTS);
        boolean passwordBounded = isBoundedUtf16(password, MAXIMUM_PASSWORD_CODE_POINTS);
        Optional<AdminUser> found = usernameBounded && passwordBounded
                ? admins.findByUsername(username)
                : Optional.empty();
        AdminUser candidate = found.orElse(null);
        boolean realHash = hasUsablePasswordHash(candidate);
        String encoded = realHash ? candidate.passwordHash() : dummyPasswordHash;
        String raw = passwordBounded ? password : DUMMY_PASSWORD_INPUT;

        boolean passwordMatched;
        try {
            passwordMatched = passwords.matches(raw, encoded);
        } catch (RuntimeException providerFailure) {
            throw new IllegalStateException("password verification failed");
        }

        if (!usernameBounded
                || !passwordBounded
                || !realHash
                || !passwordMatched
                || !isAuthenticatable(candidate)) {
            audit.record(passwordRejected());
            throw authenticationFailed();
        }

        Instant issuedAt = Objects.requireNonNull(clock.instant(), "clock returned no instant");
        Instant expiresAt = challengeExpiry(issuedAt);
        audit.record(passwordAccepted(candidate.id()));

        PendingSecondFactor pending = new PendingSecondFactor(
                UUID.randomUUID(), candidate.id(), candidate.version(), issuedAt, 0);
        String subject = subjects.hashSecondFactor(pending.challengeId());
        RateLimitDecision registration = challengeGate.register(subject, expiresAt, issuedAt);
        if (!registration.allowed()) {
            throw rateLimited(registration.retryAfterSeconds());
        }

        HttpSession targetSession = existingSession;
        try {
            if (targetSession == null) {
                targetSession = request.getSession(true);
            }
            if (targetSession == null) {
                throw new IllegalStateException("HTTP session creation failed");
            }
            targetSession.setAttribute(PendingSecondFactor.SESSION_KEY, pending);
        } catch (RuntimeException publicationFailure) {
            challengeGate.deny(subject);
            removePendingBestEffort(targetSession);
            throw publicationFailure;
        }
        return expiresAt;
    }

    @Transactional
    public Optional<AdminPrincipal> secondFactor(
            SecondFactorMethod method,
            String code,
            HttpServletRequest request,
            HttpServletResponse response) {
        Objects.requireNonNull(method, "second-factor method is required");
        Objects.requireNonNull(request, "request is required");
        Objects.requireNonNull(response, "response is required");

        HttpSession session = request.getSession(false);
        if (session == null) {
            return Optional.empty();
        }
        Object stored = session.getAttribute(PendingSecondFactor.SESSION_KEY);
        if (!(stored instanceof PendingSecondFactor pending)) {
            if (stored != null) {
                session.removeAttribute(PendingSecondFactor.SESSION_KEY);
            }
            return Optional.empty();
        }

        String challengeSubject;
        try {
            challengeSubject = subjects.hashSecondFactor(pending.challengeId());
        } catch (RuntimeException hashingFailure) {
            removePendingBestEffort(session);
            throw hashingFailure;
        }

        Instant now = Objects.requireNonNull(clock.instant(), "clock returned no instant");
        Instant expiresAt = nullableChallengeExpiry(pending.issuedAt());
        if (expiresAt == null
                || pending.issuedAt().isAfter(now)
                || !expiresAt.isAfter(now)
                || pending.failures() >= totpProperties.maxSecondFactorAttempts()) {
            rejectTerminal(session, challengeSubject);
            return Optional.empty();
        }

        ChallengeDecision challenge =
                challengeGate.consume(challengeSubject, expiresAt, now);
        if (!challenge.valid()) {
            removePendingBestEffort(session);
            return Optional.empty();
        }
        if (!challenge.limit().allowed()) {
            throw rateLimited(challenge.limit().retryAfterSeconds());
        }

        String stablePrimaryId;
        try {
            stablePrimaryId = sessions.requireSpringPrimaryId(session.getId());
        } catch (DomainException expected) {
            if (isAuthenticationRequired(expected)) {
                rejectTerminal(session, challengeSubject);
                return Optional.empty();
            }
            rejectTerminalBestEffort(session, challengeSubject);
            throw expected;
        } catch (RuntimeException failure) {
            rejectTerminalBestEffort(session, challengeSubject);
            throw failure;
        }

        boolean authenticationPublished = false;
        try {
            String clientSummary = clientSummaries.create(request);
            AdminUser admin = admins.findByIdForUpdate(pending.adminId()).orElse(null);
            if (!matchesPendingAdmin(admin, pending)) {
                rejectTerminal(session, challengeSubject);
                return Optional.empty();
            }

            boolean factorMatched = false;
            if (isBoundedUtf16(code, MAXIMUM_FACTOR_CODE_POINTS)) {
                factorMatched = switch (method) {
                    case TOTP -> totp.verify(admin.id(), admin.totpSecret(), code);
                    case RECOVERY_CODE -> recovery.consume(admin.id(), code);
                };
            }

            if (!factorMatched) {
                recordFactorMismatch(session, pending, method, challengeSubject);
                return Optional.empty();
            }

            admins.updateLastLogin(admin.id(), now);
            audit.record(loginSucceeded(admin.id(), method));

            AdminPrincipal principal = new AdminPrincipal(admin.id(), admin.username());
            Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                    principal, null, List.of(ADMIN_AUTHORITY));
            try {
                sessionStrategy.onAuthentication(authentication, request, response);
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(authentication);
                SecurityContextHolder.setContext(context);
                securityContexts.saveContext(context, request, response);
                session.removeAttribute(PendingSecondFactor.SESSION_KEY);
                challengeGate.deny(challengeSubject);
            } catch (RuntimeException persistenceFailure) {
                clearPartialAuthentication(session);
                rejectTerminalBestEffort(session, challengeSubject);
                throw persistenceFailure;
            }
            authenticationPublished = true;
            sessions.start(admin.id(), stablePrimaryId, clientSummary);
            return Optional.of(principal);
        } catch (RuntimeException failure) {
            if (authenticationPublished) {
                // Spring Session JDBC flushes in REQUIRES_NEW. Never re-enter the
                // session after the metadata FK has attempted to lock its stable
                // parent row; the controller invalidates after outer rollback.
                SecurityContextHolder.clearContext();
            } else {
                rejectTerminalBestEffort(session, challengeSubject);
            }
            throw failure;
        }
    }

    private void clearPreviousPending(HttpSession session) {
        if (session == null) {
            return;
        }
        Object previous = session.getAttribute(PendingSecondFactor.SESSION_KEY);
        if (previous == null) {
            return;
        }
        session.removeAttribute(PendingSecondFactor.SESSION_KEY);
        if (previous instanceof PendingSecondFactor pending) {
            String subject = subjects.hashSecondFactor(pending.challengeId());
            challengeGate.deny(subject);
        }
    }

    private RateLimitDecision consumeLoginLimit(
            HttpServletRequest request, String username) {
        String subject = subjects.hash(request, username);
        try {
            RateLimitDecision decision = limiter.consume(LOGIN_POLICY, subject);
            if (decision == null) {
                throw new IllegalStateException();
            }
            return decision;
        } catch (RuntimeException failure) {
            if (failure instanceof DomainException domain) {
                throw domain;
            }
            throw new IllegalStateException("login rate limiting failed");
        }
    }

    private void recordFactorMismatch(
            HttpSession session,
            PendingSecondFactor pending,
            SecondFactorMethod method,
            String challengeSubject) {
        PendingSecondFactor incremented = pending.failedAgain();
        if (incremented.failures() >= totpProperties.maxSecondFactorAttempts()) {
            session.removeAttribute(PendingSecondFactor.SESSION_KEY);
            challengeGate.deny(challengeSubject);
        } else {
            session.setAttribute(PendingSecondFactor.SESSION_KEY, incremented);
        }
        audit.record(factorRejected(pending.adminId(), method));
    }

    private void rejectTerminal(HttpSession session, String challengeSubject) {
        session.removeAttribute(PendingSecondFactor.SESSION_KEY);
        challengeGate.deny(challengeSubject);
    }

    private void rejectTerminalBestEffort(HttpSession session, String challengeSubject) {
        removePendingBestEffort(session);
        try {
            challengeGate.deny(challengeSubject);
        } catch (RuntimeException ignored) {
            // Preserve the already sanitized failure that caused terminal cleanup.
        }
    }

    private static void removePendingBestEffort(HttpSession session) {
        if (session == null) {
            return;
        }
        try {
            session.removeAttribute(PendingSecondFactor.SESSION_KEY);
        } catch (RuntimeException ignored) {
            // The session may already have been invalidated by the rotation strategy.
        }
    }

    private static void clearPartialAuthentication(HttpSession session) {
        SecurityContextHolder.clearContext();
        try {
            session.removeAttribute(
                    HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        } catch (RuntimeException ignored) {
            // A failed session strategy may already have invalidated the session.
        }
    }

    private static boolean matchesPendingAdmin(
            AdminUser admin, PendingSecondFactor pending) {
        return isAuthenticatable(admin)
                && admin.id().equals(pending.adminId())
                && admin.version() == pending.adminVersion();
    }

    private static boolean isAuthenticatable(AdminUser admin) {
        return admin != null
                && admin.id() != null
                && admin.status() == AdminStatus.ACTIVE
                && admin.version() >= 0
                && admin.totpSecret() != null
                && admin.username() != null
                && CANONICAL_USERNAME.matcher(admin.username()).matches();
    }

    private static boolean hasUsablePasswordHash(AdminUser admin) {
        return admin != null
                && admin.passwordHash() != null
                && !admin.passwordHash().isBlank()
                && admin.passwordHash().length() <= MAXIMUM_PASSWORD_HASH_UNITS;
    }

    private static boolean isBoundedUtf16(String value, int maximumCodePoints) {
        if (value == null) {
            return false;
        }
        int codePoints = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    return false;
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                return false;
            }
            codePoints++;
            if (codePoints > maximumCodePoints) {
                return false;
            }
        }
        return true;
    }

    private Instant challengeExpiry(Instant issuedAt) {
        Instant expiry = nullableChallengeExpiry(issuedAt);
        if (expiry == null) {
            throw new IllegalStateException("second-factor challenge expiry failed");
        }
        return expiry;
    }

    private Instant nullableChallengeExpiry(Instant issuedAt) {
        try {
            return issuedAt.plus(totpProperties.pendingLifetime());
        } catch (DateTimeException | ArithmeticException invalidTimestamp) {
            return null;
        }
    }

    private static String createDummyPasswordHash(PasswordEncoder passwords) {
        String encoded;
        try {
            encoded = passwords.encode(DUMMY_PASSWORD_INPUT);
        } catch (RuntimeException providerFailure) {
            throw new IllegalStateException("dummy password hashing failed");
        }
        if (encoded == null
                || encoded.isBlank()
                || encoded.length() > MAXIMUM_PASSWORD_HASH_UNITS) {
            throw new IllegalStateException(
                    "password provider returned an invalid dummy hash");
        }
        return encoded;
    }

    private static AuditCommand passwordRejected() {
        return new AuditCommand(
                null,
                "AUTH_PASSWORD_REJECTED",
                "ADMIN",
                null,
                AuditOutcome.FAILURE,
                null,
                Map.of("stage", "PASSWORD"));
    }

    private static AuditCommand passwordAccepted(UUID adminId) {
        return new AuditCommand(
                null,
                "AUTH_PASSWORD_ACCEPTED",
                "ADMIN",
                adminId.toString(),
                AuditOutcome.SUCCESS,
                null,
                Map.of("next", "SECOND_FACTOR"));
    }

    private static AuditCommand factorRejected(
            UUID adminId, SecondFactorMethod method) {
        return new AuditCommand(
                null,
                "AUTH_SECOND_FACTOR_REJECTED",
                "ADMIN",
                adminId.toString(),
                AuditOutcome.FAILURE,
                null,
                Map.of("method", method.name()));
    }

    private static AuditCommand loginSucceeded(
            UUID adminId, SecondFactorMethod method) {
        return new AuditCommand(
                adminId,
                "AUTH_LOGIN_SUCCEEDED",
                "ADMIN",
                adminId.toString(),
                AuditOutcome.SUCCESS,
                null,
                Map.of("method", method.name()));
    }

    private static boolean isAuthenticationRequired(DomainException failure) {
        return failure.status() == HttpStatus.UNAUTHORIZED
                && "AUTHENTICATION_REQUIRED".equals(failure.code());
    }

    private static DomainException authenticationFailed() {
        return new DomainException(
                "AUTHENTICATION_FAILED", HttpStatus.UNAUTHORIZED, Map.of());
    }

    private static DomainException rateLimited(long retryAfterSeconds) {
        long positive = Math.max(1, retryAfterSeconds);
        return new DomainException(
                "RATE_LIMITED",
                HttpStatus.TOO_MANY_REQUESTS,
                Map.of("retryAfterSeconds", Long.toString(positive)));
    }

    private static final class ChallengeGate {
        private final int capacity;
        private final int maximumAttempts;
        private final Map<String, ChallengeEntry> entries = new HashMap<>();

        private ChallengeGate(int capacity, int maximumAttempts) {
            if (capacity < 1 || maximumAttempts < 1) {
                throw new IllegalArgumentException("challenge gate limits must be positive");
            }
            this.capacity = capacity;
            this.maximumAttempts = maximumAttempts;
        }

        synchronized RateLimitDecision register(
                String subject, Instant expiresAt, Instant now) {
            requireGateInput(subject, expiresAt, now);
            cleanupExpired(now);
            ChallengeEntry collision = entries.get(subject);
            if (collision != null) {
                collision.denied = true;
                return RateLimitDecision.deny(retryAfter(now, collision.expiresAt));
            }
            if (!expiresAt.isAfter(now) || entries.size() >= capacity) {
                return RateLimitDecision.deny(1);
            }
            entries.put(subject, new ChallengeEntry(expiresAt));
            return RateLimitDecision.allow();
        }

        synchronized ChallengeDecision consume(
                String subject, Instant expectedExpiry, Instant now) {
            requireGateInput(subject, expectedExpiry, now);
            cleanupExpired(now);
            ChallengeEntry entry = entries.get(subject);
            if (entry == null) {
                return ChallengeDecision.invalid();
            }
            if (!entry.expiresAt.equals(expectedExpiry)) {
                entry.denied = true;
                return ChallengeDecision.invalid();
            }
            if (entry.denied || entry.attempts >= maximumAttempts) {
                entry.denied = true;
                return ChallengeDecision.denied(retryAfter(now, entry.expiresAt));
            }
            entry.attempts++;
            return ChallengeDecision.allowed();
        }

        synchronized void deny(String subject) {
            Objects.requireNonNull(subject, "challenge subject is required");
            ChallengeEntry entry = entries.get(subject);
            if (entry != null) {
                entry.denied = true;
            }
        }

        private void cleanupExpired(Instant now) {
            Iterator<ChallengeEntry> iterator = entries.values().iterator();
            while (iterator.hasNext()) {
                if (!iterator.next().expiresAt.isAfter(now)) {
                    iterator.remove();
                }
            }
        }

        private static void requireGateInput(
                String subject, Instant expiry, Instant now) {
            if (subject == null || subject.isBlank() || expiry == null || now == null) {
                throw new IllegalArgumentException("challenge gate input is invalid");
            }
        }

        private static long retryAfter(Instant now, Instant expiresAt) {
            long seconds;
            try {
                Duration remaining = Duration.between(now, expiresAt);
                seconds = remaining.getSeconds();
                if (remaining.getNano() > 0) {
                    seconds = Math.addExact(seconds, 1);
                }
            } catch (DateTimeException | ArithmeticException invalidDuration) {
                return 1;
            }
            return Math.max(1, seconds);
        }
    }

    private static final class ChallengeEntry {
        private final Instant expiresAt;
        private int attempts;
        private boolean denied;

        private ChallengeEntry(Instant expiresAt) {
            this.expiresAt = expiresAt;
        }
    }

    private record ChallengeDecision(boolean valid, RateLimitDecision limit) {
        private ChallengeDecision {
            Objects.requireNonNull(limit, "challenge decision is required");
        }

        static ChallengeDecision allowed() {
            return new ChallengeDecision(true, RateLimitDecision.allow());
        }

        static ChallengeDecision denied(long retryAfterSeconds) {
            return new ChallengeDecision(
                    true, RateLimitDecision.deny(retryAfterSeconds));
        }

        static ChallengeDecision invalid() {
            return new ChallengeDecision(false, RateLimitDecision.allow());
        }
    }
}
