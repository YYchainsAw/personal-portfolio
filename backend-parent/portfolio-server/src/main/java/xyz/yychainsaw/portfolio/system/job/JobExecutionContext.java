package xyz.yychainsaw.portfolio.system.job;

import java.util.UUID;

/** Immutable identity of the exact durable-job lease invoking a handler. */
public record JobExecutionContext(
        UUID jobId, String leaseOwner, int attemptFence) {

    public JobExecutionContext {
        if (jobId == null
                || !BackgroundJobService.isValidWorkerId(leaseOwner)
                || attemptFence < 1
                || attemptFence > BackgroundJobService.MAX_ATTEMPTS) {
            throw new IllegalArgumentException("job execution context is invalid");
        }
    }

    @Override
    public String toString() {
        return "JobExecutionContext[redacted]";
    }
}
