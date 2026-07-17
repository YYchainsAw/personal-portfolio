package xyz.yychainsaw.portfolio.system.job;

import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnWebApplication(type = Type.SERVLET)
public class BackgroundJobRetentionService {
    static final int MAXIMUM_RUN_BATCH_SIZE = 500;
    static final int MAXIMUM_BATCHES_PER_RUN = 10;

    private final BackgroundJobRetentionRepository repository;
    private final int batchSize;
    private final int maximumBatches;

    public BackgroundJobRetentionService(
            BackgroundJobRetentionRepository repository,
            @Value("${portfolio.jobs.retention.batch-size:500}") int batchSize,
            @Value("${portfolio.jobs.retention.max-batches:10}") int maximumBatches) {
        this.repository = Objects.requireNonNull(
                repository, "background job retention repository is required");
        if (batchSize < 1
                || batchSize > MAXIMUM_RUN_BATCH_SIZE
                || maximumBatches < 1
                || maximumBatches > MAXIMUM_BATCHES_PER_RUN) {
            throw new IllegalArgumentException(
                    "background job retention configuration is invalid");
        }
        this.batchSize = batchSize;
        this.maximumBatches = maximumBatches;
    }

    @Transactional
    public BackgroundJobRetentionResult deleteExpiredTerminalJobs() {
        int deletedTotal = 0;
        int attemptedBatches = 0;
        try {
            while (attemptedBatches < maximumBatches) {
                int deleted = repository.deleteExpiredTerminalBatch(batchSize);
                if (deleted < 0 || deleted > batchSize) {
                    throw new IllegalStateException(
                            "BACKGROUND_JOB_RETENTION_RESULT_INVALID");
                }
                deletedTotal = Math.addExact(deletedTotal, deleted);
                attemptedBatches++;
                if (deleted < batchSize) {
                    break;
                }
            }
            return new BackgroundJobRetentionResult(
                    deletedTotal, attemptedBatches);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("BACKGROUND_JOB_RETENTION_FAILED");
        }
    }
}

record BackgroundJobRetentionResult(int deletedCount, int batches) {
    BackgroundJobRetentionResult {
        if (deletedCount < 0 || batches < 1
                || batches > BackgroundJobRetentionService.MAXIMUM_BATCHES_PER_RUN) {
            throw new IllegalArgumentException(
                    "background job retention result is invalid");
        }
    }
}
