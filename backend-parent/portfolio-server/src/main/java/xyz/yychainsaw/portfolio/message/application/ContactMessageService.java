package xyz.yychainsaw.portfolio.message.application;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.common.ratelimit.RateLimitDecision;
import xyz.yychainsaw.portfolio.common.ratelimit.RateLimiter;
import xyz.yychainsaw.portfolio.message.config.ContactProperties;
import xyz.yychainsaw.portfolio.message.persistence.ContactMessageMapper;
import xyz.yychainsaw.portfolio.message.persistence.ContactMessageRecord;
import xyz.yychainsaw.portfolio.message.persistence.EmailOutboxMapper;
import xyz.yychainsaw.portfolio.message.persistence.EmailOutboxRecord;

@Service
@ConditionalOnWebApplication(type = Type.SERVLET)
public final class ContactMessageService {
    private static final String RATE_POLICY = "public-contact";
    private static final String CONTACT_STATUS = "UNREAD";
    private static final String OUTBOX_STATUS = "PENDING";
    private static final String TEMPLATE_NAME = "contact-notification-v1";
    private static final Duration DUPLICATE_WINDOW = Duration.ofMinutes(10);
    private static final Pattern RATE_SUBJECT = Pattern.compile("[0-9a-f]{64}");
    private static final long MAXIMUM_RETRY_AFTER_SECONDS = 9_999_999_999L;
    private static final int TRANSACTION_TIMEOUT_SECONDS = 5;

    private final RateLimiter rateLimiter;
    private final Validator validator;
    private final ContactFingerprintService fingerprints;
    private final ContactMessageMapper contacts;
    private final EmailOutboxMapper outbox;
    private final ContactProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final Supplier<UUID> uuidGenerator;

    @Autowired
    public ContactMessageService(
            RateLimiter rateLimiter,
            Validator validator,
            ContactFingerprintService fingerprints,
            ContactMessageMapper contacts,
            EmailOutboxMapper outbox,
            ContactProperties properties,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this(
                rateLimiter,
                validator,
                fingerprints,
                contacts,
                outbox,
                properties,
                transactionManager,
                clock,
                UUID::randomUUID);
    }

    ContactMessageService(
            RateLimiter rateLimiter,
            Validator validator,
            ContactFingerprintService fingerprints,
            ContactMessageMapper contacts,
            EmailOutboxMapper outbox,
            ContactProperties properties,
            PlatformTransactionManager transactionManager,
            Clock clock,
            Supplier<UUID> uuidGenerator) {
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rate limiter is required");
        this.validator = Objects.requireNonNull(validator, "validator is required");
        this.fingerprints = Objects.requireNonNull(
                fingerprints, "contact fingerprint service is required");
        this.contacts = Objects.requireNonNull(contacts, "contact mapper is required");
        this.outbox = Objects.requireNonNull(outbox, "email outbox mapper is required");
        this.properties = requireConfigured(properties);
        this.transaction = transactionTemplate(transactionManager);
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.uuidGenerator = Objects.requireNonNull(
                uuidGenerator, "UUID generator is required");
    }

    public ContactSubmissionResult submit(SubmitContactCommand command) {
        Objects.requireNonNull(command, "contact command is required");
        if (isHoneypotPopulated(command.website())) {
            return ContactSubmissionResult.acceptedWithoutIdentifier();
        }

        String rateLimitSubject = requireRateLimitSubject(command.rateLimitSubject());
        consumeRateLimit(rateLimitSubject);
        SubmitContactCommand normalized = validateAndNormalize(command, rateLimitSubject);
        String dedupeKey = fingerprints.contactKey(normalized);

        ContactSubmissionResult result = transaction.execute(status ->
                persist(normalized, dedupeKey));
        return Objects.requireNonNull(result, "contact transaction returned no result");
    }

    private ContactSubmissionResult persist(
            SubmitContactCommand command, String dedupeKey) {
        contacts.acquireDedupeLock(dedupeKey);
        Instant acceptedAt = Objects.requireNonNull(
                        clock.instant(), "clock returned no instant")
                .truncatedTo(ChronoUnit.MICROS);
        Instant cutoff = acceptedAt.minus(DUPLICATE_WINDOW);
        if (contacts.existsByDedupeKeySince(dedupeKey, cutoff)) {
            return ContactSubmissionResult.acceptedWithoutIdentifier();
        }

        UUID messageId = nextUuid();
        ContactMessageRecord message = new ContactMessageRecord(
                messageId,
                command.name(),
                command.email(),
                command.subject(),
                command.body(),
                CONTACT_STATUS,
                dedupeKey,
                acceptedAt,
                0,
                acceptedAt,
                acceptedAt);
        requireSingleRow(contacts.insert(message), "contact message insert failed");

        EmailOutboxRecord notification = new EmailOutboxRecord(
                nextUuid(),
                messageId,
                TEMPLATE_NAME,
                properties.ownerEmail(),
                stableMessageId(messageId),
                OUTBOX_STATUS,
                0,
                acceptedAt,
                null,
                null,
                null,
                acceptedAt,
                null,
                acceptedAt);
        requireSingleRow(outbox.insert(notification), "contact outbox insert failed");
        return ContactSubmissionResult.accepted(messageId);
    }

    private SubmitContactCommand validateAndNormalize(
            SubmitContactCommand command, String rateLimitSubject) {
        Map<String, String> fieldErrors = new TreeMap<>();
        String name = normalizeSingleLine(command.name(), "name", true, fieldErrors);
        String email = normalizeEmail(command.email(), fieldErrors);
        String subject = normalizeSingleLine(
                command.subject(), "subject", true, fieldErrors);
        String body = normalizeBody(command.body(), fieldErrors);
        String website = normalizeSingleLine(
                command.website() == null ? "" : command.website(),
                "website",
                false,
                fieldErrors);
        SubmitContactCommand normalized = new SubmitContactCommand(
                name,
                email,
                subject,
                body,
                website,
                command.privacyAccepted(),
                rateLimitSubject);

        Set<ConstraintViolation<SubmitContactCommand>> violations =
                validator.validate(normalized);
        for (ConstraintViolation<SubmitContactCommand> violation : violations) {
            String field = publicField(violation.getPropertyPath().toString());
            String message = violation.getMessage();
            fieldErrors.putIfAbsent(
                    field, message == null || message.isBlank() ? "invalid" : message);
        }
        if (!fieldErrors.isEmpty()) {
            throw new DomainException(
                    "VALIDATION_ERROR",
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    Map.copyOf(fieldErrors));
        }
        return normalized;
    }

    private void consumeRateLimit(String subject) {
        RateLimitDecision decision;
        try {
            decision = rateLimiter.consume(RATE_POLICY, subject);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("contact rate limiting failed");
        }
        if (decision == null) {
            throw new IllegalStateException("contact rate limiting failed");
        }
        if (decision.allowed()) {
            return;
        }
        long retryAfter = Math.max(
                1,
                Math.min(decision.retryAfterSeconds(), MAXIMUM_RETRY_AFTER_SECONDS));
        throw new DomainException(
                "CONTACT_RATE_LIMITED",
                HttpStatus.TOO_MANY_REQUESTS,
                Map.of("retryAfterSeconds", Long.toString(retryAfter)));
    }

    private String stableMessageId(UUID messageId) {
        return "<portfolio-contact-" + messageId + '@' + properties.mailIdDomain() + '>';
    }

    private UUID nextUuid() {
        return Objects.requireNonNull(uuidGenerator.get(), "UUID generator returned null");
    }

    private static ContactProperties requireConfigured(ContactProperties properties) {
        ContactProperties configured = Objects.requireNonNull(
                properties, "contact properties are required");
        if (configured.ownerEmail().isBlank()) {
            throw new IllegalStateException("contact owner email is not configured");
        }
        if (configured.mailIdDomain().isBlank()) {
            throw new IllegalStateException("contact mail ID domain is not configured");
        }
        return configured;
    }

    private static TransactionTemplate transactionTemplate(
            PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(Objects.requireNonNull(
                transactionManager, "transaction manager is required"));
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setTimeout(TRANSACTION_TIMEOUT_SECONDS);
        return template;
    }

    private static void requireSingleRow(int affected, String message) {
        if (affected != 1) {
            throw new IllegalStateException(message);
        }
    }

    private static boolean isHoneypotPopulated(String value) {
        return value != null && !value.isBlank();
    }

    private static String requireRateLimitSubject(String value) {
        if (value == null || !RATE_SUBJECT.matcher(value).matches()) {
            throw new IllegalStateException("contact rate-limit subject is invalid");
        }
        return value;
    }

    private static String normalizeSingleLine(
            String value,
            String field,
            boolean required,
            Map<String, String> errors) {
        if (value == null) {
            return null;
        }
        if (!isWellFormedUtf16(value)) {
            errors.putIfAbsent(field, "invalid");
            return value;
        }
        String normalized = stripUnicodeWhitespace(
                Normalizer.normalize(value, Normalizer.Form.NFC));
        if (containsDisallowedControl(normalized, false)) {
            errors.putIfAbsent(field, "invalid");
        }
        if (required && isUnicodeBlank(normalized)) {
            errors.putIfAbsent(field, "must not be blank");
        }
        return normalized;
    }

    private static String normalizeEmail(String value, Map<String, String> errors) {
        String normalized = normalizeSingleLine(value, "email", true, errors);
        if (normalized == null) {
            return null;
        }
        int separator = normalized.lastIndexOf('@');
        if (separator <= 0 || separator != normalized.indexOf('@')) {
            return normalized;
        }
        return normalized.substring(0, separator + 1)
                + normalized.substring(separator + 1).toLowerCase(java.util.Locale.ROOT);
    }

    private static String normalizeBody(String value, Map<String, String> errors) {
        if (value == null) {
            return null;
        }
        if (!isWellFormedUtf16(value)) {
            errors.putIfAbsent("message", "invalid");
            return value;
        }
        String lineNormalized = value.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace('\u2028', '\n')
                .replace('\u2029', '\n');
        String normalized = stripUnicodeWhitespace(
                Normalizer.normalize(lineNormalized, Normalizer.Form.NFC));
        if (containsDisallowedControl(normalized, true)) {
            errors.putIfAbsent("message", "invalid");
        }
        if (isUnicodeBlank(normalized)) {
            errors.putIfAbsent("message", "must not be blank");
        }
        return normalized;
    }

    private static boolean containsDisallowedControl(String value, boolean allowLineFeed) {
        return value.codePoints().anyMatch(character ->
                Character.isISOControl(character)
                                && !(allowLineFeed && character == '\n')
                        || Character.getType(character) == Character.LINE_SEPARATOR
                        || Character.getType(character) == Character.PARAGRAPH_SEPARATOR
                        || isForbiddenFormat(character));
    }

    private static boolean isForbiddenFormat(int character) {
        return Character.getType(character) == Character.FORMAT
                && character != 0x200c
                && character != 0x200d;
    }

    private static boolean isUnicodeBlank(String value) {
        return value.isEmpty() || value.codePoints().allMatch(
                ContactMessageService::isUnicodeWhitespace);
    }

    private static String stripUnicodeWhitespace(String value) {
        int start = 0;
        while (start < value.length()) {
            int character = value.codePointAt(start);
            if (!isUnicodeWhitespace(character)) {
                break;
            }
            start += Character.charCount(character);
        }
        int end = value.length();
        while (end > start) {
            int character = value.codePointBefore(end);
            if (!isUnicodeWhitespace(character)) {
                break;
            }
            end -= Character.charCount(character);
        }
        return value.substring(start, end);
    }

    private static boolean isUnicodeWhitespace(int character) {
        return Character.isWhitespace(character) || Character.isSpaceChar(character);
    }

    private static boolean isWellFormedUtf16(String value) {
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

    private static String publicField(String field) {
        return "body".equals(field) ? "message" : field;
    }
}
