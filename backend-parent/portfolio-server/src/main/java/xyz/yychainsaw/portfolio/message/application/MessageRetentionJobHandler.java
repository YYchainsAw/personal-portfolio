package xyz.yychainsaw.portfolio.message.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import xyz.yychainsaw.portfolio.system.job.JobHandler;

@Component
public final class MessageRetentionJobHandler implements JobHandler {
    public static final int BATCH_SIZE = 500;

    private static final String JOB_TYPE = "CONTACT_RETENTION";
    private static final String INVALID_PAYLOAD = "CONTACT_RETENTION_PAYLOAD_INVALID";
    private static final String FAILURE_CODE = "CONTACT_RETENTION_FAILED";
    private static final ZoneId HONG_KONG = ZoneId.of("Asia/Hong_Kong");
    private static final long MAXIMUM_SITE_DATE_AGE_DAYS = 31;

    private final MessageRetentionRepository repository;
    private final Clock clock;
    private final Supplier<UUID> runIds;

    @Autowired
    public MessageRetentionJobHandler(
            MessageRetentionRepository repository, Clock clock) {
        this(repository, clock, UUID::randomUUID);
    }

    MessageRetentionJobHandler(
            MessageRetentionRepository repository,
            Clock clock,
            Supplier<UUID> runIds) {
        this.repository = Objects.requireNonNull(
                repository, "retention repository is required");
        this.clock = Objects.requireNonNull(clock, "retention clock is required");
        this.runIds = Objects.requireNonNull(runIds, "retention run ids are required");
    }

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public void handle(JsonNode payload) {
        Instant startedAt;
        try {
            startedAt = now();
        } catch (RuntimeException exception) {
            throw failure();
        }
        requireSiteDate(
                payload, startedAt.atZone(HONG_KONG).toLocalDate());
        Instant cutoff = cutoff(startedAt);

        UUID runId;
        try {
            runId = Objects.requireNonNull(runIds.get());
        } catch (RuntimeException exception) {
            throw failure();
        }

        boolean started = false;
        long deletedCount = 0;
        try {
            repository.start(runId, startedAt);
            started = true;

            int deleted;
            do {
                deleted = repository.deleteExpiredBatch(cutoff, BATCH_SIZE);
                if (deleted < 0 || deleted > BATCH_SIZE) {
                    throw failure();
                }
                deletedCount = Math.addExact(deletedCount, deleted);
            } while (deleted == BATCH_SIZE);
            repository.succeed(runId, deletedCount, completionTime(startedAt));
        } catch (RuntimeException exception) {
            if (started) {
                recordFailure(runId, deletedCount, startedAt);
            }
            throw failure();
        }
    }

    private static LocalDate requireSiteDate(
            JsonNode payload, LocalDate currentSiteDate) {
        if (payload == null
                || !payload.isObject()
                || payload.size() != 1
                || !payload.has("siteDate")) {
            throw invalidPayload();
        }
        JsonNode value = payload.get("siteDate");
        if (value == null || !value.isTextual()) {
            throw invalidPayload();
        }

        String encoded = value.textValue();
        LocalDate siteDate;
        try {
            siteDate = LocalDate.parse(encoded);
            if (!siteDate.toString().equals(encoded)) {
                throw invalidPayload();
            }
        } catch (RuntimeException exception) {
            if (isInvalidPayload(exception)) {
                throw (IllegalArgumentException) exception;
            }
            throw invalidPayload();
        }

        LocalDate oldestAllowed = currentSiteDate.minusDays(
                MAXIMUM_SITE_DATE_AGE_DAYS);
        if (siteDate.isAfter(currentSiteDate) || siteDate.isBefore(oldestAllowed)) {
            throw invalidPayload();
        }
        return siteDate;
    }

    private static Instant cutoff(Instant executionTime) {
        try {
            return executionTime.atZone(HONG_KONG).minusYears(1).toInstant();
        } catch (RuntimeException exception) {
            throw invalidPayload();
        }
    }

    private void recordFailure(
            UUID runId, long deletedCount, Instant startedAt) {
        try {
            repository.fail(
                    runId,
                    deletedCount,
                    completionTime(startedAt),
                    FAILURE_CODE);
        } catch (RuntimeException ignored) {
            // The externally visible failure remains fixed and contains no dependency data.
        }
    }

    private Instant completionTime(Instant startedAt) {
        Instant candidate = now();
        return candidate.isBefore(startedAt) ? startedAt : candidate;
    }

    private Instant now() {
        return Objects.requireNonNull(clock.instant());
    }

    private static boolean isInvalidPayload(RuntimeException exception) {
        return exception.getClass() == IllegalArgumentException.class
                && INVALID_PAYLOAD.equals(exception.getMessage())
                && exception.getCause() == null;
    }

    private static IllegalArgumentException invalidPayload() {
        return new IllegalArgumentException(INVALID_PAYLOAD);
    }

    private static IllegalStateException failure() {
        return new IllegalStateException(FAILURE_CODE);
    }
}
