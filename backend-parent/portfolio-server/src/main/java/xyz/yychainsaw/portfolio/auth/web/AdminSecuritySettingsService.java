package xyz.yychainsaw.portfolio.auth.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.yychainsaw.portfolio.audit.AuditCommand;
import xyz.yychainsaw.portfolio.audit.AuditOutcome;
import xyz.yychainsaw.portfolio.audit.AuditService;
import xyz.yychainsaw.portfolio.auth.RecoveryCodeService;
import xyz.yychainsaw.portfolio.auth.crypto.EncryptedTotpSecret;
import xyz.yychainsaw.portfolio.auth.crypto.PasswordPolicy;
import xyz.yychainsaw.portfolio.auth.crypto.RecoveryCodeGenerator;
import xyz.yychainsaw.portfolio.auth.crypto.TotpService;
import xyz.yychainsaw.portfolio.auth.model.AdminStatus;
import xyz.yychainsaw.portfolio.auth.model.AdminUser;
import xyz.yychainsaw.portfolio.auth.persistence.AdminUserRepository;
import xyz.yychainsaw.portfolio.auth.persistence.RecoveryCodeRepository;
import xyz.yychainsaw.portfolio.auth.session.AdminSessionRepository.TerminalSession;
import xyz.yychainsaw.portfolio.auth.session.AdminSessionService;
import xyz.yychainsaw.portfolio.auth.session.AdminSessionService.ActiveSession;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.common.ratelimit.RateLimitDecision;
import xyz.yychainsaw.portfolio.common.ratelimit.RateLimitProperties;
import xyz.yychainsaw.portfolio.common.ratelimit.RateLimiter;

@Service
@ConditionalOnWebApplication(type = Type.SERVLET)
public final class AdminSecuritySettingsService {
    private static final String SECURITY_POLICY = "admin-security";
    private static final String NO_AMBIENT_TRANSACTION =
            "security-settings mutation requires no ambient transaction";
    private static final String DUMMY_PASSWORD_INPUT =
            "portfolio-security-settings-dummy-password";
    private static final int MAXIMUM_PASSWORD_UNITS = 256;
    private static final int MAXIMUM_FACTOR_UNITS = 64;
    private static final int MAXIMUM_PASSWORD_HASH_UNITS = 255;
    private static final int RECOVERY_CODE_COUNT = 10;

    private final AdminUserRepository admins;
    private final RecoveryCodeRepository recoveryCodes;
    private final RecoveryCodeService recoveryCodeService;
    private final RecoveryCodeGenerator recoveryGenerator;
    private final PasswordEncoder passwords;
    private final PasswordPolicy passwordPolicy;
    private final TotpService totp;
    private final RateLimiter limiter;
    private final LoginSubjectHasher subjects;
    private final AdminSessionService sessions;
    private final AuditService audit;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final EnrollmentGate enrollmentGate;
    private final String dummyPasswordHash;

    public AdminSecuritySettingsService(
            AdminUserRepository admins,
            RecoveryCodeRepository recoveryCodes,
            RecoveryCodeService recoveryCodeService,
            RecoveryCodeGenerator recoveryGenerator,
            PasswordEncoder passwords,
            PasswordPolicy passwordPolicy,
            TotpService totp,
            RateLimiter limiter,
            LoginSubjectHasher subjects,
            AdminSessionService sessions,
            AuditService audit,
            TransactionTemplate transactions,
            Clock clock,
            RateLimitProperties rateLimitProperties) {
        this.admins = Objects.requireNonNull(admins, "admin repository is required");
        this.recoveryCodes = Objects.requireNonNull(
                recoveryCodes, "recovery-code repository is required");
        this.recoveryCodeService = Objects.requireNonNull(
                recoveryCodeService, "recovery-code service is required");
        this.recoveryGenerator = Objects.requireNonNull(
                recoveryGenerator, "recovery-code generator is required");
        this.passwords = Objects.requireNonNull(passwords, "password encoder is required");
        this.passwordPolicy = Objects.requireNonNull(
                passwordPolicy, "password policy is required");
        this.totp = Objects.requireNonNull(totp, "TOTP service is required");
        this.limiter = Objects.requireNonNull(limiter, "rate limiter is required");
        this.subjects = Objects.requireNonNull(
                subjects, "login subject hasher is required");
        this.sessions = Objects.requireNonNull(sessions, "session service is required");
        this.audit = Objects.requireNonNull(audit, "audit service is required");
        this.transactions = Objects.requireNonNull(
                transactions, "transactions are required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
        RateLimitProperties limits = Objects.requireNonNull(
                rateLimitProperties, "rate-limit properties are required");
        if (!limits.policies().containsKey(SECURITY_POLICY)) {
            throw new IllegalArgumentException("admin-security rate-limit policy is required");
        }
        this.enrollmentGate = new EnrollmentGate(limits.maximumSubjects());
        this.dummyPasswordHash = createDummyPasswordHash(passwords);
    }

    public void changePassword(
            UUID adminId,
            ActiveSession currentSession,
            String currentPassword,
            String currentTotp,
            String newPassword,
            HttpServletRequest request) {
        requireInvocation(adminId, currentSession, request);
        consumeSecurityBudget(adminId, request);
        ReauthenticationProof proof = reauthenticate(
                adminId, currentSession, currentPassword, currentTotp);

        passwordPolicy.requireStrong(newPassword);
        String encoded = encodePassword(newPassword);
        MutationResult result = required(() -> {
            requireLockedActiveVersion(adminId, proof.adminVersion(), false);
            long newVersion = admins.updatePassword(adminId, encoded);
            requireAdvancedVersion(proof.adminVersion(), newVersion);
            requireCurrentForMutation(adminId, currentSession, false);
            List<TerminalSession> marked = sessions
                    .markOtherSessionsRevokedInCurrentTransaction(
                            adminId, currentSession, "PASSWORD_CHANGED");
            requireCurrentUnmarked(currentSession, marked);
            audit.record(successAudit(
                    adminId,
                    "ADMIN_PASSWORD_CHANGED",
                    Map.of("revokedOtherSessions", Integer.toString(marked.size()))));
            return new MutationResult(newVersion, marked);
        });

        deleteMarkedSessionsBestEffort(result.markedSessions());
        clearPendingBestEffort(request);
    }

    public EnrollmentDelivery beginTotpEnrollment(
            UUID adminId,
            ActiveSession currentSession,
            String currentPassword,
            String currentTotp,
            HttpServletRequest request) {
        requireInvocation(adminId, currentSession, request);
        consumeSecurityBudget(adminId, request);
        ReauthenticationProof proof = reauthenticate(
                adminId, currentSession, currentPassword, currentTotp);

        TotpService.Enrollment generated = beginEnrollment(adminId, proof.username());
        Instant issuedAt = currentInstant();
        Instant expiresAt = exactEnrollmentExpiry(issuedAt);
        UUID enrollmentId = UUID.randomUUID();
        String enrollmentSubject = hashEnrollment(enrollmentId);
        EnrollmentDelivery delivery = new EnrollmentDelivery(
                enrollmentId, generated.provisioningUri(), expiresAt);
        MutationResult result;
        try {
            result = required(() -> {
                requireLockedActiveVersion(adminId, proof.adminVersion(), false);
                long newVersion = admins.bumpSecurityVersion(adminId);
                requireAdvancedVersion(proof.adminVersion(), newVersion);
                requireCurrentForMutation(adminId, currentSession, false);
                List<TerminalSession> marked = sessions
                        .markOtherSessionsRevokedInCurrentTransaction(
                                adminId, currentSession, "TOTP_ENROLLMENT_STARTED");
                requireCurrentUnmarked(currentSession, marked);
                audit.record(successAudit(
                        adminId,
                        "ADMIN_TOTP_ENROLLMENT_STARTED",
                        Map.of("revokedOtherSessions", Integer.toString(marked.size()))));
                return new MutationResult(newVersion, marked);
            });
        } catch (RuntimeException failure) {
            delivery.close();
            throw failure;
        }

        PendingTotpEnrollment pending;
        try {
            pending = new PendingTotpEnrollment(
                    enrollmentId,
                    adminId,
                    result.adminVersion(),
                    currentSession.metadataId(),
                    generated.encryptedSecret(),
                    issuedAt,
                    expiresAt,
                    0);
        } catch (RuntimeException invalidGeneratedState) {
            delivery.close();
            deleteMarkedSessionsBestEffort(result.markedSessions());
            throw internalError();
        }

        try {
            HttpSession httpSession = requireHttpSession(request);
            Object previous = sessionAttribute(httpSession);
            RateLimitDecision registration = enrollmentGate.register(
                    enrollmentSubject, expiresAt, currentInstant());
            if (!registration.allowed()) {
                enrollmentGate.tombstone(enrollmentSubject);
                clearPendingBestEffort(httpSession, previous);
                throw rateLimited(registration.retryAfterSeconds());
            }
            try {
                setPending(httpSession, pending);
            } catch (RuntimeException publicationFailure) {
                enrollmentGate.tombstone(enrollmentSubject);
                clearPendingBestEffort(httpSession, previous);
                throw internalError();
            }
            tombstonePendingBestEffort(previous);
            return delivery.committed();
        } catch (RuntimeException failure) {
            delivery.close();
            throw failure;
        } finally {
            deleteMarkedSessionsBestEffort(result.markedSessions());
        }
    }

    public RecoveryCodesDelivery confirmTotp(
            UUID adminId,
            ActiveSession currentSession,
            String enrollmentId,
            String newTotp,
            HttpServletRequest request) {
        requireInvocation(adminId, currentSession, request);
        consumeSecurityBudget(adminId, request);
        HttpSession httpSession = requireHttpSession(request);
        ConfirmationPreflight preflight = confirmationPreflight(adminId, currentSession);

        Object stored = sessionAttribute(httpSession);
        if (!(stored instanceof PendingTotpEnrollment pending)) {
            if (stored != null) {
                removePendingBestEffort(httpSession);
            }
            throw enrollmentExpired();
        }

        String enrollmentSubject;
        try {
            enrollmentSubject = hashEnrollment(pending.enrollmentId());
        } catch (DomainException hashingFailure) {
            removePendingBestEffort(httpSession);
            throw hashingFailure;
        }
        Instant now = currentInstant();
        boolean gateAccepted = enrollmentGate.consume(
                enrollmentSubject, pending.expiresAt(), now);
        if (!gateAccepted
                || !validPending(
                        pending, adminId, currentSession, preflight.adminVersion(), now)) {
            rejectTerminalEnrollment(httpSession, enrollmentSubject);
            throw enrollmentExpired();
        }

        boolean idMatched = canonicalUuidMatches(enrollmentId, pending.enrollmentId());
        boolean codeMatched = verifyCandidateTotp(adminId, pending.encryptedSecret(), newTotp);
        if (!idMatched || !codeMatched) {
            persistConfirmationFailure(httpSession, pending, enrollmentSubject);
            recordConfirmationRejection(adminId, currentSession);
            throw authenticationFailed();
        }

        PreparedRecovery prepared = prepareRecoveryCodes();
        MutationResult result;
        try {
            result = required(() -> {
                requireLockedActiveVersion(adminId, pending.adminVersion(), true);
                long newVersion = admins.updateTotp(adminId, pending.encryptedSecret());
                requireAdvancedVersion(pending.adminVersion(), newVersion);
                recoveryCodes.replace(adminId, prepared.hashes());
                requireCurrentForMutation(adminId, currentSession, false);
                List<TerminalSession> marked = sessions
                        .markOtherSessionsRevokedInCurrentTransaction(
                                adminId, currentSession, "TOTP_CHANGED");
                requireCurrentUnmarked(currentSession, marked);
                audit.record(successAudit(
                        adminId,
                        "ADMIN_TOTP_CHANGED",
                        Map.of(
                                "recoveryCodeCount", Integer.toString(RECOVERY_CODE_COUNT),
                                "revokedOtherSessions", Integer.toString(marked.size()))));
                return new MutationResult(newVersion, marked);
            });
        } catch (DomainException failure) {
            prepared.close();
            if (isEnrollmentExpired(failure)) {
                rejectTerminalEnrollment(httpSession, enrollmentSubject);
            }
            throw failure;
        } catch (RuntimeException failure) {
            prepared.close();
            throw failure;
        }

        enrollmentGate.tombstone(enrollmentSubject);
        removePendingBestEffort(httpSession);
        deleteMarkedSessionsBestEffort(result.markedSessions());
        return prepared.commit();
    }

    public RecoveryCodesDelivery regenerateRecoveryCodes(
            UUID adminId,
            ActiveSession currentSession,
            String currentPassword,
            String currentTotp,
            HttpServletRequest request) {
        requireInvocation(adminId, currentSession, request);
        consumeSecurityBudget(adminId, request);
        ReauthenticationProof proof = reauthenticate(
                adminId, currentSession, currentPassword, currentTotp);
        PreparedRecovery prepared = prepareRecoveryCodes();
        MutationResult result;
        try {
            result = required(() -> {
                requireLockedActiveVersion(adminId, proof.adminVersion(), false);
                long newVersion = admins.bumpSecurityVersion(adminId);
                requireAdvancedVersion(proof.adminVersion(), newVersion);
                recoveryCodes.replace(adminId, prepared.hashes());
                requireCurrentForMutation(adminId, currentSession, false);
                List<TerminalSession> marked = sessions
                        .markOtherSessionsRevokedInCurrentTransaction(
                                adminId, currentSession, "RECOVERY_CODES_REGENERATED");
                requireCurrentUnmarked(currentSession, marked);
                audit.record(successAudit(
                        adminId,
                        "ADMIN_RECOVERY_CODES_REGENERATED",
                        Map.of(
                                "recoveryCodeCount", Integer.toString(RECOVERY_CODE_COUNT),
                                "revokedOtherSessions", Integer.toString(marked.size()))));
                return new MutationResult(newVersion, marked);
            });
        } catch (RuntimeException failure) {
            prepared.close();
            throw failure;
        }

        clearPendingBestEffort(request);
        deleteMarkedSessionsBestEffort(result.markedSessions());
        return prepared.commit();
    }

    private ReauthenticationProof reauthenticate(
            UUID adminId,
            ActiveSession currentSession,
            String currentPassword,
            String currentTotp) {
        ReauthenticationResult result = required(() -> {
            AdminUser admin = admins.findByIdForUpdate(adminId).orElse(null);
            boolean currentValid = admin != null
                    && currentSessionIsValid(adminId, currentSession);

            String encoded = admin == null ? dummyPasswordHash : admin.passwordHash();
            String rawPassword = boundedPassword(currentPassword);
            boolean passwordMatched = matchPassword(rawPassword, encoded);
            boolean totpMatched = verifyCurrentTotp(adminId, admin, currentTotp);
            boolean accepted = admin != null
                    && admin.status() == AdminStatus.ACTIVE
                    && currentValid
                    && passwordMatched
                    && totpMatched;
            if (!accepted) {
                audit.record(reauthenticationRejectedAudit(
                        admin == null ? null : adminId, adminId));
                return ReauthenticationResult.rejected();
            }
            return ReauthenticationResult.accepted(
                    new ReauthenticationProof(admin.version(), admin.username()));
        });
        if (!result.accepted()) {
            throw authenticationFailed();
        }
        return result.proof();
    }

    private ConfirmationPreflight confirmationPreflight(
            UUID adminId, ActiveSession currentSession) {
        ConfirmationPreflight result = required(() -> {
            AdminUser admin = admins.findByIdForUpdate(adminId).orElse(null);
            boolean currentValid = admin != null
                    && currentSessionIsValid(adminId, currentSession);
            boolean actorCurrent = admin != null
                    && admin.status() == AdminStatus.ACTIVE
                    && currentValid;
            if (!actorCurrent) {
                audit.record(confirmationRejectedAudit(
                        null, adminId, true));
                return ConfirmationPreflight.rejected();
            }
            return ConfirmationPreflight.accepted(admin.version());
        });
        if (!result.accepted()) {
            throw authenticationFailed();
        }
        return result;
    }

    private void recordConfirmationRejection(
            UUID adminId, ActiveSession currentSession) {
        required(() -> {
            AdminUser admin = admins.findByIdForUpdate(adminId).orElse(null);
            boolean currentValid = admin != null
                    && currentSessionIsValid(adminId, currentSession);
            boolean actorCurrent = admin != null
                    && admin.status() == AdminStatus.ACTIVE
                    && currentValid;
            audit.record(confirmationRejectedAudit(
                    actorCurrent ? adminId : null, adminId, !actorCurrent));
            return Boolean.TRUE;
        });
    }

    private void consumeSecurityBudget(UUID adminId, HttpServletRequest request) {
        RateLimitDecision decision;
        try {
            String subject = subjects.hashSecurity(request, adminId);
            decision = limiter.consume(SECURITY_POLICY, subject);
            if (decision == null) {
                throw new IllegalStateException("rate limiter returned no decision");
            }
        } catch (RuntimeException rateLimitFailure) {
            throw internalError();
        }
        if (!decision.allowed()) {
            throw rateLimited(decision.retryAfterSeconds());
        }
    }

    private PreparedRecovery prepareRecoveryCodes() {
        RecoveryCodesDelivery delivery = null;
        try {
            List<String> plaintext = recoveryGenerator.generate(RECOVERY_CODE_COUNT);
            requireExactDistinctValues(plaintext, RECOVERY_CODE_COUNT, "recovery codes");
            delivery = new RecoveryCodesDelivery(plaintext);
            List<String> hashes = recoveryCodeService.hashAll(plaintext);
            requireExactDistinctValues(hashes, RECOVERY_CODE_COUNT, "recovery-code hashes");
            return new PreparedRecovery(delivery, hashes);
        } catch (RuntimeException providerFailure) {
            if (delivery != null) {
                delivery.close();
            }
            throw internalError();
        }
    }

    private TotpService.Enrollment beginEnrollment(UUID adminId, String username) {
        try {
            TotpService.Enrollment enrollment = totp.beginEnrollment(adminId, username);
            if (enrollment == null
                    || enrollment.encryptedSecret() == null
                    || enrollment.provisioningUri() == null
                    || enrollment.provisioningUri().isBlank()) {
                throw new IllegalStateException("TOTP provider returned invalid enrollment");
            }
            return enrollment;
        } catch (RuntimeException providerFailure) {
            throw internalError();
        }
    }

    private String encodePassword(String password) {
        try {
            String encoded = passwords.encode(password);
            requirePasswordHash(encoded);
            return encoded;
        } catch (RuntimeException providerFailure) {
            throw internalError();
        }
    }

    private boolean matchPassword(String rawPassword, String encoded) {
        try {
            return passwords.matches(rawPassword, encoded);
        } catch (RuntimeException providerFailure) {
            throw internalError();
        }
    }

    private boolean verifyCurrentTotp(
            UUID adminId, AdminUser admin, String submittedCode) {
        try {
            if (admin == null) {
                return totp.verify(adminId, null, "");
            }
            return totp.verify(
                    adminId, admin.totpSecret(), boundedFactor(submittedCode));
        } catch (RuntimeException providerFailure) {
            throw internalError();
        }
    }

    private boolean verifyCandidateTotp(
            UUID adminId, EncryptedTotpSecret candidate, String submittedCode) {
        try {
            return totp.verify(adminId, candidate, boundedFactor(submittedCode));
        } catch (RuntimeException providerFailure) {
            throw internalError();
        }
    }

    private boolean currentSessionIsValid(
            UUID adminId, ActiveSession currentSession) {
        return sessions.findCurrentSessionInCurrentTransaction(adminId, currentSession)
                .isPresent();
    }

    private void requireCurrentForMutation(
            UUID adminId, ActiveSession currentSession, boolean enrollmentFailure) {
        if (sessions.findCurrentSessionInCurrentTransaction(adminId, currentSession)
                .isPresent()) {
            return;
        }
        if (enrollmentFailure) {
            throw decision(enrollmentExpired());
        }
        throw decision(authenticationFailed());
    }

    private AdminUser requireLockedActiveVersion(
            UUID adminId, long expectedVersion, boolean enrollmentFailure) {
        AdminUser admin = admins.findByIdForUpdate(adminId).orElse(null);
        if (admin == null
                || admin.status() != AdminStatus.ACTIVE
                || admin.version() != expectedVersion) {
            if (enrollmentFailure && admin != null && admin.status() == AdminStatus.ACTIVE) {
                throw decision(enrollmentExpired());
            }
            throw decision(authenticationFailed());
        }
        return admin;
    }

    private void persistConfirmationFailure(
            HttpSession session,
            PendingTotpEnrollment pending,
            String enrollmentSubject) {
        PendingTotpEnrollment incremented;
        try {
            incremented = pending.failedAgain();
            if (incremented.failures() >= PendingTotpEnrollment.MAX_FAILURES) {
                removePending(session);
                enrollmentGate.tombstone(enrollmentSubject);
            } else {
                setPending(session, incremented);
            }
        } catch (RuntimeException persistenceFailure) {
            enrollmentGate.tombstone(enrollmentSubject);
            removePendingBestEffort(session);
            throw internalError();
        }
    }

    private void deleteMarkedSessionsBestEffort(List<TerminalSession> marked) {
        try {
            sessions.deleteMarkedSessions(marked);
        } catch (RuntimeException ignored) {
            // Terminal metadata remains linked so the cleanup job can retry physical deletion.
        }
    }

    private static boolean validPending(
            PendingTotpEnrollment pending,
            UUID adminId,
            ActiveSession currentSession,
            long currentAdminVersion,
            Instant now) {
        return pending.adminId().equals(adminId)
                && pending.sessionMetadataId().equals(currentSession.metadataId())
                && pending.adminVersion() == currentAdminVersion
                && !pending.issuedAt().isAfter(now)
                && pending.expiresAt().isAfter(now)
                && pending.failures() < PendingTotpEnrollment.MAX_FAILURES;
    }

    private void rejectTerminalEnrollment(
            HttpSession session, String enrollmentSubject) {
        enrollmentGate.tombstone(enrollmentSubject);
        removePendingBestEffort(session);
    }

    private void clearPendingBestEffort(HttpServletRequest request) {
        requireNoAmbientTransaction();
        HttpSession session;
        try {
            session = request.getSession(false);
        } catch (RuntimeException unavailableSession) {
            return;
        }
        if (session == null) {
            return;
        }
        Object previous;
        try {
            previous = session.getAttribute(PendingTotpEnrollment.SESSION_KEY);
        } catch (RuntimeException unavailableSession) {
            return;
        }
        clearPendingBestEffort(session, previous);
    }

    private void clearPendingBestEffort(HttpSession session, Object previous) {
        removePendingBestEffort(session);
        tombstonePendingBestEffort(previous);
    }

    private void tombstonePendingBestEffort(Object value) {
        if (!(value instanceof PendingTotpEnrollment pending)) {
            return;
        }
        try {
            enrollmentGate.tombstone(subjects.hashTotpEnrollment(pending.enrollmentId()));
        } catch (RuntimeException ignored) {
            // Removal or the committed security version still makes the value unusable.
        }
    }

    private static HttpSession requireHttpSession(HttpServletRequest request) {
        requireNoAmbientTransaction();
        HttpSession session;
        try {
            session = request.getSession(false);
        } catch (RuntimeException unavailableSession) {
            throw internalError();
        }
        if (session == null) {
            throw authenticationFailed();
        }
        return session;
    }

    private static Object sessionAttribute(HttpSession session) {
        requireNoAmbientTransaction();
        try {
            return session.getAttribute(PendingTotpEnrollment.SESSION_KEY);
        } catch (RuntimeException unavailableSession) {
            throw internalError();
        }
    }

    private static void setPending(
            HttpSession session, PendingTotpEnrollment pending) {
        requireNoAmbientTransaction();
        session.setAttribute(
                PendingTotpEnrollment.SESSION_KEY,
                Objects.requireNonNull(pending, "pending enrollment is required"));
    }

    private static void removePending(HttpSession session) {
        requireNoAmbientTransaction();
        session.removeAttribute(PendingTotpEnrollment.SESSION_KEY);
    }

    private static void removePendingBestEffort(HttpSession session) {
        requireNoAmbientTransaction();
        try {
            session.removeAttribute(PendingTotpEnrollment.SESSION_KEY);
        } catch (RuntimeException ignored) {
            // A tombstone or version advance remains the authoritative fail-closed state.
        }
    }

    private Instant currentInstant() {
        try {
            return Objects.requireNonNull(clock.instant(), "clock returned no instant");
        } catch (RuntimeException clockFailure) {
            throw internalError();
        }
    }

    private String hashEnrollment(UUID enrollmentId) {
        try {
            return subjects.hashTotpEnrollment(enrollmentId);
        } catch (RuntimeException hashingFailure) {
            throw internalError();
        }
    }

    private static Instant exactEnrollmentExpiry(Instant issuedAt) {
        try {
            return issuedAt.plus(PendingTotpEnrollment.LIFETIME);
        } catch (DateTimeException | ArithmeticException invalidLifetime) {
            throw internalError();
        }
    }

    private static boolean canonicalUuidMatches(String submitted, UUID expected) {
        if (submitted == null || submitted.length() > MAXIMUM_FACTOR_UNITS) {
            return false;
        }
        try {
            UUID parsed = UUID.fromString(submitted);
            return parsed.equals(expected) && parsed.toString().equals(submitted);
        } catch (IllegalArgumentException invalidUuid) {
            return false;
        }
    }

    private static String boundedPassword(String value) {
        return isBoundedWellFormedUtf16(value, MAXIMUM_PASSWORD_UNITS)
                ? value
                : DUMMY_PASSWORD_INPUT;
    }

    private static String boundedFactor(String value) {
        return isBoundedWellFormedUtf16(value, MAXIMUM_FACTOR_UNITS) ? value : "";
    }

    private static boolean isBoundedWellFormedUtf16(String value, int maximumUnits) {
        if (value == null || value.length() > maximumUnits) {
            return false;
        }
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
        }
        return true;
    }

    private static void requirePasswordHash(String encoded) {
        if (encoded == null
                || encoded.isBlank()
                || encoded.length() > MAXIMUM_PASSWORD_HASH_UNITS) {
            throw new IllegalStateException("password provider returned an invalid hash");
        }
    }

    private static void requireExactDistinctValues(
            List<String> values, int expectedCount, String name) {
        if (values == null || values.size() != expectedCount) {
            throw new IllegalStateException(name + " have an invalid count");
        }
        Set<String> distinct = new HashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank() || !distinct.add(value)) {
                throw new IllegalStateException(name + " are invalid");
            }
        }
    }

    private static void requireAdvancedVersion(long previous, long current) {
        long expected;
        try {
            expected = Math.incrementExact(previous);
        } catch (ArithmeticException invalidVersion) {
            throw new IllegalStateException("administrator version cannot advance");
        }
        if (current != expected) {
            throw new IllegalStateException(
                    "administrator version advanced unexpectedly");
        }
    }

    private static void requireCurrentUnmarked(
            ActiveSession current, List<TerminalSession> marked) {
        for (TerminalSession terminal : marked) {
            if (terminal.metadataId().equals(current.metadataId())
                    || current.springSessionPrimaryId().equals(terminal.primaryId())) {
                throw new IllegalStateException("current session was selected for revocation");
            }
        }
    }

    private <T> T required(TransactionalWork<T> work) {
        Objects.requireNonNull(work, "transactional work is required");
        try {
            T result = transactions.execute(status -> work.execute());
            if (result == null) {
                throw new IllegalStateException("security transaction returned no result");
            }
            return result;
        } catch (ServiceDecision expected) {
            throw expected.failure();
        } catch (RuntimeException persistenceFailure) {
            throw internalError();
        }
    }

    private static AuditCommand successAudit(
            UUID adminId, String action, Map<String, String> metadata) {
        return new AuditCommand(
                adminId,
                action,
                "ADMIN",
                adminId.toString(),
                AuditOutcome.SUCCESS,
                null,
                metadata);
    }

    private static AuditCommand reauthenticationRejectedAudit(
            UUID actorAdminId, UUID targetAdminId) {
        return new AuditCommand(
                actorAdminId,
                "ADMIN_SECURITY_REAUTH_REJECTED",
                "ADMIN",
                targetAdminId.toString(),
                AuditOutcome.FAILURE,
                null,
                Map.of("stage", "REAUTH"));
    }

    private static AuditCommand confirmationRejectedAudit(
            UUID actorAdminId, UUID targetAdminId, boolean staleActor) {
        return new AuditCommand(
                actorAdminId,
                "ADMIN_TOTP_CONFIRM_REJECTED",
                "ADMIN",
                targetAdminId.toString(),
                AuditOutcome.FAILURE,
                null,
                Map.of(
                        "stage", "CONFIRM",
                        "staleActor", Boolean.toString(staleActor)));
    }

    private static String createDummyPasswordHash(PasswordEncoder encoder) {
        try {
            String encoded = encoder.encode(DUMMY_PASSWORD_INPUT);
            requirePasswordHash(encoded);
            return encoded;
        } catch (RuntimeException providerFailure) {
            throw new IllegalStateException("dummy password hashing failed");
        }
    }

    private static void requireInvocation(
            UUID adminId, ActiveSession currentSession, HttpServletRequest request) {
        Objects.requireNonNull(adminId, "administrator id is required");
        Objects.requireNonNull(currentSession, "current session is required");
        Objects.requireNonNull(request, "request is required");
        if (!adminId.equals(currentSession.adminId())) {
            throw new DomainException(
                    "AUTHENTICATION_REQUIRED", HttpStatus.UNAUTHORIZED, Map.of());
        }
        requireNoAmbientTransaction();
    }

    private static void requireNoAmbientTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(NO_AMBIENT_TRANSACTION);
        }
    }

    private static boolean isEnrollmentExpired(DomainException failure) {
        return failure.status() == HttpStatus.CONFLICT
                && "TOTP_ENROLLMENT_EXPIRED".equals(failure.code());
    }

    private static DomainException authenticationFailed() {
        return new DomainException(
                "AUTHENTICATION_FAILED", HttpStatus.UNAUTHORIZED, Map.of());
    }

    private static DomainException enrollmentExpired() {
        return new DomainException(
                "TOTP_ENROLLMENT_EXPIRED", HttpStatus.CONFLICT, Map.of());
    }

    private static DomainException rateLimited(long retryAfterSeconds) {
        long positive = Math.max(1, retryAfterSeconds);
        return new DomainException(
                "RATE_LIMITED",
                HttpStatus.TOO_MANY_REQUESTS,
                Map.of("retryAfterSeconds", Long.toString(positive)));
    }

    private static DomainException internalError() {
        return new DomainException(
                "INTERNAL_ERROR", HttpStatus.INTERNAL_SERVER_ERROR, Map.of());
    }

    private static ServiceDecision decision(DomainException failure) {
        return new ServiceDecision(failure);
    }

    @FunctionalInterface
    private interface TransactionalWork<T> {
        T execute();
    }

    private static final class ServiceDecision extends RuntimeException {
        private final DomainException failure;

        private ServiceDecision(DomainException failure) {
            super("security-settings decision", null, false, false);
            this.failure = Objects.requireNonNull(failure, "decision failure is required");
        }

        private DomainException failure() {
            return failure;
        }
    }

    private record ReauthenticationProof(long adminVersion, String username) {
        private ReauthenticationProof {
            if (adminVersion < 0) {
                throw new IllegalArgumentException("administrator version is invalid");
            }
            if (username == null || username.isBlank()) {
                throw new IllegalArgumentException("administrator username is invalid");
            }
        }

        @Override
        public String toString() {
            return "ReauthenticationProof[adminVersion=" + adminVersion
                    + ", username=<redacted>]";
        }
    }

    private record ReauthenticationResult(
            boolean accepted, ReauthenticationProof proof) {
        private ReauthenticationResult {
            if (accepted != (proof != null)) {
                throw new IllegalArgumentException("reauthentication result is invalid");
            }
        }

        static ReauthenticationResult accepted(ReauthenticationProof proof) {
            return new ReauthenticationResult(
                    true, Objects.requireNonNull(proof, "reauthentication proof is required"));
        }

        static ReauthenticationResult rejected() {
            return new ReauthenticationResult(false, null);
        }

        @Override
        public String toString() {
            return "ReauthenticationResult[accepted=" + accepted + ", proof=<redacted>]";
        }
    }

    private record ConfirmationPreflight(boolean accepted, long adminVersion) {
        private ConfirmationPreflight {
            if (accepted && adminVersion < 0 || !accepted && adminVersion != -1) {
                throw new IllegalArgumentException("confirmation preflight is invalid");
            }
        }

        static ConfirmationPreflight accepted(long adminVersion) {
            return new ConfirmationPreflight(true, adminVersion);
        }

        static ConfirmationPreflight rejected() {
            return new ConfirmationPreflight(false, -1);
        }
    }

    private record MutationResult(
            long adminVersion, List<TerminalSession> markedSessions) {
        private MutationResult {
            if (adminVersion < 0) {
                throw new IllegalArgumentException("administrator version is invalid");
            }
            markedSessions = List.copyOf(Objects.requireNonNull(
                    markedSessions, "marked sessions are required"));
        }
    }

    private static final class PreparedRecovery implements AutoCloseable {
        private final RecoveryCodesDelivery delivery;
        private final List<String> hashes;

        private PreparedRecovery(
                RecoveryCodesDelivery delivery, List<String> hashes) {
            this.delivery = Objects.requireNonNull(
                    delivery, "recovery-code delivery is required");
            this.hashes = List.copyOf(Objects.requireNonNull(
                    hashes, "recovery-code hashes are required"));
        }

        private List<String> hashes() {
            return hashes;
        }

        private RecoveryCodesDelivery commit() {
            return delivery.committed();
        }

        @Override
        public void close() {
            delivery.close();
        }

        @Override
        public String toString() {
            return "PreparedRecovery[delivery=<redacted>, hashes=<redacted>]";
        }
    }

    private static final class EnrollmentGate {
        private final int capacity;
        private final Map<String, EnrollmentEntry> entries = new HashMap<>();

        private EnrollmentGate(int capacity) {
            if (capacity < 1) {
                throw new IllegalArgumentException(
                        "enrollment gate capacity must be positive");
            }
            this.capacity = capacity;
        }

        synchronized RateLimitDecision register(
                String subject, Instant expiresAt, Instant now) {
            requireGateInput(subject, expiresAt, now);
            cleanupExpired(now);
            EnrollmentEntry collision = entries.get(subject);
            if (collision != null) {
                collision.tombstoned = true;
                return RateLimitDecision.deny(retryAfter(now, collision.expiresAt));
            }
            if (!expiresAt.isAfter(now) || entries.size() >= capacity) {
                return RateLimitDecision.deny(1);
            }
            entries.put(subject, new EnrollmentEntry(expiresAt));
            return RateLimitDecision.allow();
        }

        synchronized boolean consume(
                String subject, Instant expectedExpiry, Instant now) {
            requireGateInput(subject, expectedExpiry, now);
            cleanupExpired(now);
            EnrollmentEntry entry = entries.get(subject);
            if (entry == null) {
                return false;
            }
            if (entry.tombstoned
                    || !entry.expiresAt.equals(expectedExpiry)
                    || !entry.expiresAt.isAfter(now)
                    || entry.attempts >= PendingTotpEnrollment.MAX_FAILURES) {
                entry.tombstoned = true;
                return false;
            }
            entry.attempts++;
            return true;
        }

        synchronized void tombstone(String subject) {
            Objects.requireNonNull(subject, "enrollment subject is required");
            EnrollmentEntry entry = entries.get(subject);
            if (entry != null) {
                entry.tombstoned = true;
            }
        }

        private void cleanupExpired(Instant now) {
            Iterator<EnrollmentEntry> iterator = entries.values().iterator();
            while (iterator.hasNext()) {
                if (!iterator.next().expiresAt.isAfter(now)) {
                    iterator.remove();
                }
            }
        }

        private static void requireGateInput(
                String subject, Instant expiry, Instant now) {
            if (subject == null || subject.isBlank() || expiry == null || now == null) {
                throw new IllegalArgumentException("enrollment gate input is invalid");
            }
        }

        private static long retryAfter(Instant now, Instant expiresAt) {
            try {
                Duration remaining = Duration.between(now, expiresAt);
                long seconds = remaining.getSeconds();
                if (remaining.getNano() > 0) {
                    seconds = Math.incrementExact(seconds);
                }
                return Math.max(1, seconds);
            } catch (DateTimeException | ArithmeticException invalidDuration) {
                return 1;
            }
        }

        @Override
        public String toString() {
            return "EnrollmentGate[capacity=" + capacity + ", entries=<redacted>]";
        }
    }

    private static final class EnrollmentEntry {
        private final Instant expiresAt;
        private int attempts;
        private boolean tombstoned;

        private EnrollmentEntry(Instant expiresAt) {
            this.expiresAt = Objects.requireNonNull(
                    expiresAt, "enrollment expiry is required");
        }

        @Override
        public String toString() {
            return "EnrollmentEntry[expiresAt=" + expiresAt
                    + ", attempts=" + attempts + ", tombstoned=" + tombstoned + ']';
        }
    }

    private enum DeliveryState {
        PREPARED,
        COMMITTED,
        CONSUMED,
        CLOSED
    }

    public record EnrollmentMaterial(
            UUID enrollmentId, String provisioningUri, Instant expiresAt) {
        public EnrollmentMaterial {
            Objects.requireNonNull(enrollmentId, "enrollment id is required");
            if (provisioningUri == null || provisioningUri.isBlank()) {
                throw new IllegalArgumentException("provisioning URI is invalid");
            }
            Objects.requireNonNull(expiresAt, "expiry timestamp is required");
        }

        @Override
        public String toString() {
            return "EnrollmentMaterial[enrollmentId=<redacted>"
                    + ", provisioningUri=<redacted>, expiresAt=" + expiresAt + ']';
        }
    }

    public static final class EnrollmentDelivery implements AutoCloseable {
        private final UUID enrollmentId;
        private final Instant expiresAt;
        private char[] provisioningUri;
        private DeliveryState state = DeliveryState.PREPARED;

        private EnrollmentDelivery(
                UUID enrollmentId, String provisioningUri, Instant expiresAt) {
            this.enrollmentId = Objects.requireNonNull(
                    enrollmentId, "enrollment id is required");
            if (provisioningUri == null || provisioningUri.isBlank()) {
                throw new IllegalArgumentException("provisioning URI is invalid");
            }
            this.provisioningUri = provisioningUri.toCharArray();
            this.expiresAt = Objects.requireNonNull(
                    expiresAt, "expiry timestamp is required");
        }

        private EnrollmentDelivery committed() {
            requireState(DeliveryState.PREPARED);
            state = DeliveryState.COMMITTED;
            return this;
        }

        public EnrollmentMaterial take() {
            requireState(DeliveryState.COMMITTED);
            String value = new String(provisioningUri);
            Arrays.fill(provisioningUri, '\0');
            provisioningUri = null;
            state = DeliveryState.CONSUMED;
            return new EnrollmentMaterial(enrollmentId, value, expiresAt);
        }

        @Override
        public void close() {
            if (state == DeliveryState.CLOSED) {
                return;
            }
            if (provisioningUri != null) {
                Arrays.fill(provisioningUri, '\0');
                provisioningUri = null;
            }
            state = DeliveryState.CLOSED;
        }

        @Override
        public String toString() {
            return "EnrollmentDelivery[state=" + state
                    + ", enrollmentId=<redacted>, provisioningUri=<redacted>]";
        }

        private void requireState(DeliveryState expected) {
            if (state != expected) {
                throw new IllegalStateException("enrollment delivery is not available");
            }
        }
    }

    public static final class RecoveryCodesDelivery implements AutoCloseable {
        private List<char[]> recoveryCodes;
        private DeliveryState state = DeliveryState.PREPARED;

        private RecoveryCodesDelivery(List<String> recoveryCodes) {
            Objects.requireNonNull(recoveryCodes, "recovery codes are required");
            if (recoveryCodes.size() != RECOVERY_CODE_COUNT
                    || new HashSet<>(recoveryCodes).size() != RECOVERY_CODE_COUNT) {
                throw new IllegalArgumentException("recovery codes are invalid");
            }
            List<char[]> snapshot = new ArrayList<>(recoveryCodes.size());
            for (String recoveryCode : recoveryCodes) {
                if (recoveryCode == null || recoveryCode.isBlank()) {
                    clear(snapshot);
                    throw new IllegalArgumentException("recovery code is invalid");
                }
                snapshot.add(recoveryCode.toCharArray());
            }
            this.recoveryCodes = snapshot;
        }

        private RecoveryCodesDelivery committed() {
            requireState(DeliveryState.PREPARED);
            state = DeliveryState.COMMITTED;
            return this;
        }

        public List<String> take() {
            requireState(DeliveryState.COMMITTED);
            List<String> result = recoveryCodes.stream()
                    .map(String::new)
                    .toList();
            clear(recoveryCodes);
            recoveryCodes = null;
            state = DeliveryState.CONSUMED;
            return List.copyOf(result);
        }

        @Override
        public void close() {
            if (state == DeliveryState.CLOSED) {
                return;
            }
            if (recoveryCodes != null) {
                clear(recoveryCodes);
                recoveryCodes = null;
            }
            state = DeliveryState.CLOSED;
        }

        @Override
        public String toString() {
            return "RecoveryCodesDelivery[state=" + state
                    + ", recoveryCodes=<redacted>]";
        }

        private void requireState(DeliveryState expected) {
            if (state != expected) {
                throw new IllegalStateException("recovery-code delivery is not available");
            }
        }

        private static void clear(List<char[]> values) {
            values.forEach(value -> Arrays.fill(value, '\0'));
        }
    }
}
