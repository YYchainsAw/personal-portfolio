package xyz.yychainsaw.portfolio.system.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.zone.ZoneRules;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BackgroundJobService {
    static final int MAX_ATTEMPTS = 10;

    private static final Logger LOGGER = LoggerFactory.getLogger(BackgroundJobService.class);

    private static final int MAX_WORKER_ID_LENGTH = 120;
    private static final Duration MAX_LEASE_DURATION = Duration.ofHours(24);
    private static final Set<String> FAILURE_CODES = Set.of(
            "JOB_FAILED",
            "JOB_HANDLER_FAILED",
            "JOB_HANDLER_UNAVAILABLE",
            "JOB_HANDLER_INTERRUPTED",
            "JOB_ATTEMPTS_EXHAUSTED");

    private final BackgroundJobMapper mapper;
    private final JobPayloadCodec payloadCodec;
    private final Clock clock;
    private final JobHandlerRegistry handlers;

    public BackgroundJobService(
            BackgroundJobMapper mapper,
            ObjectMapper objectMapper,
            Clock clock,
            JobHandlerRegistry handlers) {
        this(mapper, new JobPayloadCodec(objectMapper), clock, handlers);
    }

    @Autowired
    public BackgroundJobService(
            BackgroundJobMapper mapper,
            JobPayloadCodec payloadCodec,
            Clock clock,
            JobHandlerRegistry handlers) {
        this.mapper = Objects.requireNonNull(mapper, "job mapper is required");
        this.payloadCodec = Objects.requireNonNull(
                payloadCodec, "job payload codec is required");
        this.clock = requireUtcClock(clock);
        this.handlers = Objects.requireNonNull(handlers, "job handlers are required");
    }

    @Transactional
    public UUID enqueue(
            String jobType, String idempotencyKey, Map<String, ?> payload) {
        requireRegisteredJobType(jobType);
        JobPayloadCodec.requireIdempotencyKey(idempotencyKey);
        String serialized = payloadCodec.serialize(payload);
        OffsetDateTime now = now();
        UUID candidateId = UUID.randomUUID();

        Optional<UUID> inserted;
        try {
            inserted = mapper.insertIfAbsent(
                    candidateId, jobType, idempotencyKey, serialized, now);
        } catch (RuntimeException exception) {
            throw fixedFailure("JOB_ENQUEUE_FAILED");
        }
        if (inserted.isPresent()) {
            return inserted.get();
        }

        ExistingBackgroundJobRow existing;
        try {
            existing = mapper.findByIdempotencyKey(idempotencyKey, serialized)
                    .orElseThrow(() -> fixedFailure("JOB_ENQUEUE_FAILED"));
        } catch (RuntimeException exception) {
            if (isFixedFailure(exception, "JOB_ENQUEUE_FAILED")) {
                throw exception;
            }
            throw fixedFailure("JOB_ENQUEUE_FAILED");
        }

        if (!existing.jobType().equals(jobType)
                || !existing.payloadMatches()) {
            throw fixedFailure("JOB_IDEMPOTENCY_CONFLICT");
        }
        return existing.id();
    }

    @Transactional
    public Optional<LeasedJob> leaseNext(String workerId, Duration leaseDuration) {
        requireWorkerId(workerId);
        requireLeaseDuration(leaseDuration);
        OffsetDateTime now = now();
        OffsetDateTime leaseUntil;
        try {
            leaseUntil = now.plus(leaseDuration);
        } catch (DateTimeException | ArithmeticException exception) {
            throw invalidLeaseDuration();
        }

        Optional<BackgroundJobRow> exhausted;
        try {
            if (mapper.quarantineNextCorruptExpired(
                    now, "JOB_STATE_INVALID") == 1) {
                LOGGER.warn("Quarantined one corrupt expired background-job lease");
            }
            exhausted = mapper.deadLetterNextExhausted(
                    now, "JOB_ATTEMPTS_EXHAUSTED");
        } catch (RuntimeException exception) {
            throw fixedFailure("JOB_LEASE_FAILED");
        }
        exhausted.ifPresent(row -> invokeDeadLetter(
                row,
                "JOB_ATTEMPTS_EXHAUSTED",
                new JobExecutionContext(
                        row.id(), row.leaseOwner(), row.attempts())));

        Optional<BackgroundJobRow> claimed;
        try {
            claimed = mapper.claimNext(workerId, now, leaseUntil);
        } catch (RuntimeException exception) {
            throw fixedFailure("JOB_LEASE_FAILED");
        }
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        BackgroundJobRow row = claimed.get();
        try {
            return Optional.of(new LeasedJob(
                    row.id(),
                    row.jobType(),
                    parseStoredPayload(row.payloadJson()),
                    row.attempts(),
                    row.leaseOwner()));
        } catch (RuntimeException exception) {
            throw fixedFailure("JOB_LEASE_FAILED");
        }
    }

    @Transactional
    public boolean succeed(UUID jobId, String leaseOwner, int attemptFence) {
        requireCompletionFence(jobId, leaseOwner, attemptFence);
        try {
            return mapper.succeed(jobId, leaseOwner, attemptFence) == 1;
        } catch (RuntimeException exception) {
            throw fixedFailure("JOB_COMPLETION_FAILED");
        }
    }

    @Transactional
    public boolean fail(
            UUID jobId,
            String leaseOwner,
            int attemptFence,
            String safeSummaryCode) {
        requireCompletionFence(jobId, leaseOwner, attemptFence);
        String summary = normalizeFailureCode(safeSummaryCode);
        OffsetDateTime now = now();
        OffsetDateTime nextRunAt = now;
        if (attemptFence < MAX_ATTEMPTS) {
            long delaySeconds = Math.min(1L << attemptFence, 3_600L);
            try {
                nextRunAt = now.plusSeconds(delaySeconds);
            } catch (DateTimeException | ArithmeticException exception) {
                throw fixedFailure("JOB_COMPLETION_FAILED");
            }
        }

        Optional<BackgroundJobRow> transitioned;
        try {
            transitioned = mapper.fail(
                    jobId, leaseOwner, attemptFence, summary, nextRunAt);
        } catch (RuntimeException exception) {
            throw fixedFailure("JOB_COMPLETION_FAILED");
        }
        if (transitioned.isEmpty()) {
            return false;
        }
        BackgroundJobRow row = transitioned.get();
        if ("DEAD".equals(row.status())) {
            invokeDeadLetter(
                    row,
                    summary,
                    new JobExecutionContext(jobId, leaseOwner, attemptFence));
        }
        return true;
    }

    private void requireRegisteredJobType(String jobType) {
        if (!JobHandlerRegistry.isCanonicalType(jobType) || !handlers.contains(jobType)) {
            throw new IllegalArgumentException("job type is invalid");
        }
    }

    private JsonNode parseStoredPayload(String payloadJson) {
        try {
            return payloadCodec.parseStored(payloadJson);
        } catch (IllegalArgumentException exception) {
            throw fixedFailure("JOB_PAYLOAD_INVALID");
        }
    }

    private void invokeDeadLetter(
            BackgroundJobRow row,
            String summaryCode,
            JobExecutionContext context) {
        Optional<JobHandler> handler = handlers.find(row.jobType());
        if (handler.isEmpty()) {
            return;
        }
        JsonNode payload;
        try {
            payload = payloadCodec.parseStored(row.payloadJson());
        } catch (IllegalArgumentException exception) {
            LOGGER.warn(
                    "Skipped dead-letter hook for job {} because its stored payload is invalid",
                    row.id());
            return;
        }
        try {
            handler.get().onDeadLetter(context, payload, summaryCode);
        } catch (Exception exception) {
            throw fixedFailure("job dead-letter hook failed");
        }
    }

    private OffsetDateTime now() {
        try {
            Instant instant = Objects.requireNonNull(clock.instant(), "clock returned no instant");
            return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
        } catch (RuntimeException exception) {
            throw fixedFailure("JOB_CLOCK_FAILED");
        }
    }

    static boolean isValidWorkerId(String workerId) {
        return isVisibleAscii(workerId, MAX_WORKER_ID_LENGTH);
    }

    private static void requireWorkerId(String workerId) {
        if (!isValidWorkerId(workerId)) {
            throw new IllegalArgumentException("worker id is invalid");
        }
    }

    private static void requireLeaseDuration(Duration leaseDuration) {
        if (leaseDuration == null
                || leaseDuration.isZero()
                || leaseDuration.isNegative()
                || leaseDuration.compareTo(MAX_LEASE_DURATION) > 0) {
            throw invalidLeaseDuration();
        }
    }

    private static void requireCompletionFence(
            UUID jobId, String leaseOwner, int attemptFence) {
        if (jobId == null
                || !isValidWorkerId(leaseOwner)
                || attemptFence < 1
                || attemptFence > MAX_ATTEMPTS) {
            throw new IllegalArgumentException("job completion fence is invalid");
        }
    }

    private static String normalizeFailureCode(String summaryCode) {
        return summaryCode != null && FAILURE_CODES.contains(summaryCode)
                ? summaryCode
                : "JOB_FAILED";
    }

    private static boolean isVisibleAscii(String value, int maximumLength) {
        if (value == null
                || value.isEmpty()
                || value.length() > maximumLength
                || !value.equals(value.trim())) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x21 || character > 0x7e) {
                return false;
            }
        }
        return true;
    }

    private static Clock requireUtcClock(Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("job clock is required");
        }
        ZoneRules rules = clock.getZone().getRules();
        if (!rules.isFixedOffset()
                || !ZoneOffset.UTC.equals(rules.getOffset(Instant.EPOCH))) {
            throw new IllegalArgumentException("job clock must use UTC");
        }
        return clock;
    }

    private static IllegalArgumentException invalidLeaseDuration() {
        return new IllegalArgumentException("lease duration is invalid");
    }

    private static IllegalStateException fixedFailure(String message) {
        return new IllegalStateException(message);
    }

    private static boolean isFixedFailure(RuntimeException exception, String message) {
        return exception.getClass() == IllegalStateException.class
                && message.equals(exception.getMessage())
                && exception.getCause() == null;
    }

}
