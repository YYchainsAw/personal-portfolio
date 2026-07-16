package xyz.yychainsaw.portfolio.system.job;

import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class BackgroundJobRetentionRepository {
    static final int MAXIMUM_DATABASE_BATCH_SIZE = 1_000;

    private final JdbcClient jdbc;

    public BackgroundJobRetentionRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc is required");
    }

    public int deleteExpiredTerminalBatch(int batchSize) {
        if (batchSize < 1 || batchSize > MAXIMUM_DATABASE_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "background job retention batch size is invalid");
        }
        Integer deleted;
        try {
            deleted = jdbc.sql("""
                            select portfolio.delete_expired_terminal_background_jobs(
                                :batchSize
                            )
                            """)
                    .param("batchSize", batchSize)
                    .query(Integer.class)
                    .single();
        } catch (RuntimeException failure) {
            throw new IllegalStateException("BACKGROUND_JOB_RETENTION_FAILED");
        }
        if (deleted == null || deleted < 0 || deleted > batchSize) {
            throw new IllegalStateException(
                    "BACKGROUND_JOB_RETENTION_RESULT_INVALID");
        }
        return deleted;
    }
}
