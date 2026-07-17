package xyz.yychainsaw.portfolio.media.staging;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import xyz.yychainsaw.portfolio.system.job.ScheduledJobInsert;
import xyz.yychainsaw.portfolio.system.job.ScheduledJobInserter;

@Service
@ConditionalOnWebApplication(type = Type.SERVLET)
public class TransactionalLocalStagingSuccessorService
        implements LocalStagingSuccessorService {
    static final Duration SUCCESSOR_DELAY = Duration.ofHours(1);

    private final LocalStagingReservationRepository reservations;
    private final ScheduledJobInserter jobs;

    public TransactionalLocalStagingSuccessorService(
            LocalStagingReservationRepository reservations, ScheduledJobInserter jobs) {
        this.reservations = Objects.requireNonNull(
                reservations, "local staging reservations are required");
        this.jobs = Objects.requireNonNull(jobs, "scheduled job inserter is required");
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public boolean scheduleFromHandler(LocalStagingReservation expected) {
        return schedule(expected);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean scheduleFromDeadLetter(LocalStagingReservation expected) {
        return schedule(expected);
    }

    private boolean schedule(LocalStagingReservation expected) {
        Objects.requireNonNull(expected, "expected local staging reservation is required");
        try {
            Optional<LocalStagingReservation> locked =
                    reservations.findByAssetIdForUpdate(expected.assetId());
            if (locked.isEmpty() || !locked.get().equals(expected)) {
                return false;
            }

            long nextGeneration;
            try {
                nextGeneration = Math.incrementExact(expected.generation());
            } catch (ArithmeticException exhausted) {
                throw fixed("LOCAL_STAGING_SUCCESSOR_GENERATION_EXHAUSTED");
            }
            LocalStagingCleanupPayload successor = new LocalStagingCleanupPayload(
                    expected.assetId(), nextGeneration, expected.mimeType(), expected.sha256());
            ScheduledJobInsert inserted = jobs.insertAfter(
                    LocalStagingCleanupPayload.JOB_TYPE,
                    successor.idempotencyKey(),
                    successor.toJobPayload(),
                    SUCCESSOR_DELAY);
            if (!reservations.advanceSuccessorExact(expected, inserted.jobId())) {
                throw fixed("LOCAL_STAGING_SUCCESSOR_CAS_FAILED");
            }
            return true;
        } catch (RuntimeException failure) {
            if (isFixed(failure, "LOCAL_STAGING_SUCCESSOR_GENERATION_EXHAUSTED")
                    || isFixed(failure, "LOCAL_STAGING_SUCCESSOR_CAS_FAILED")) {
                throw failure;
            }
            throw fixed("LOCAL_STAGING_SUCCESSOR_FAILED");
        }
    }

    private static IllegalStateException fixed(String code) {
        return new IllegalStateException(code);
    }

    private static boolean isFixed(RuntimeException failure, String code) {
        return failure.getClass() == IllegalStateException.class
                && code.equals(failure.getMessage())
                && failure.getCause() == null;
    }
}
