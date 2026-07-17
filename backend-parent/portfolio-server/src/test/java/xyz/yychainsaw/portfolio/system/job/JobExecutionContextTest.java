package xyz.yychainsaw.portfolio.system.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JobExecutionContextTest {
    @Test
    void acceptsOnlyAnExactLeaseFenceAndRedactsItsIdentity() {
        UUID jobId = UUID.randomUUID();
        JobExecutionContext context =
                new JobExecutionContext(jobId, "worker-private-id", 10);

        assertThat(context.jobId()).isEqualTo(jobId);
        assertThat(context.leaseOwner()).isEqualTo("worker-private-id");
        assertThat(context.attemptFence()).isEqualTo(10);
        assertThat(context.toString())
                .isEqualTo("JobExecutionContext[redacted]")
                .doesNotContain(jobId.toString(), "worker-private-id", "10");

        for (Runnable invalid : List.<Runnable>of(
                () -> new JobExecutionContext(null, "worker", 1),
                () -> new JobExecutionContext(jobId, null, 1),
                () -> new JobExecutionContext(jobId, "has space", 1),
                () -> new JobExecutionContext(jobId, "worker", 0),
                () -> new JobExecutionContext(jobId, "worker", 11))) {
            assertThatIllegalArgumentException()
                    .isThrownBy(invalid::run)
                    .withMessage("job execution context is invalid")
                    .withNoCause();
        }
    }
}
