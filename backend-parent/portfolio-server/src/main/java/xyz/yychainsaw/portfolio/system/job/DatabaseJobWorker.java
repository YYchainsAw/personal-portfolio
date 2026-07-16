package xyz.yychainsaw.portfolio.system.job;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@ConditionalOnWebApplication(type = Type.SERVLET)
@ConditionalOnProperty(
        prefix = "portfolio.jobs",
        name = "worker-enabled",
        havingValue = "true")
public final class DatabaseJobWorker {
    static final String SCHEDULER_BEAN = "backgroundJobTaskScheduler";

    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseJobWorker.class);
    private static final Duration MAX_LEASE_DURATION = Duration.ofHours(24);

    private final BackgroundJobService service;
    private final JobHandlerRegistry handlers;
    private final Duration leaseDuration;
    private final String workerId;
    private final WorkerShutdownSignal shutdownSignal;

    @Autowired
    public DatabaseJobWorker(
            BackgroundJobService service,
            JobHandlerRegistry handlers,
            @Value("${portfolio.jobs.lease-duration:PT30M}") Duration leaseDuration,
            WorkerShutdownSignal shutdownSignal) {
        this(
                service,
                handlers,
                leaseDuration,
                "portfolio-worker-" + UUID.randomUUID(),
                shutdownSignal);
    }

    DatabaseJobWorker(
            BackgroundJobService service,
            JobHandlerRegistry handlers,
            Duration leaseDuration,
            String workerId) {
        this(service, handlers, leaseDuration, workerId, new WorkerShutdownSignal());
    }

    DatabaseJobWorker(
            BackgroundJobService service,
            JobHandlerRegistry handlers,
            Duration leaseDuration,
            String workerId,
            WorkerShutdownSignal shutdownSignal) {
        this.service = Objects.requireNonNull(service, "job service is required");
        this.handlers = Objects.requireNonNull(handlers, "job handlers are required");
        if (leaseDuration == null
                || leaseDuration.isZero()
                || leaseDuration.isNegative()
                || leaseDuration.compareTo(MAX_LEASE_DURATION) > 0) {
            throw new IllegalArgumentException("lease duration is invalid");
        }
        if (!BackgroundJobService.isValidWorkerId(workerId)) {
            throw new IllegalArgumentException("worker id is invalid");
        }
        this.leaseDuration = leaseDuration;
        this.workerId = workerId;
        this.shutdownSignal =
                Objects.requireNonNull(shutdownSignal, "job shutdown signal is required");
    }

    @Scheduled(
            initialDelayString = "${portfolio.jobs.initial-delay:PT5S}",
            fixedDelayString = "${portfolio.jobs.poll-delay:PT1S}",
            scheduler = SCHEDULER_BEAN)
    public void poll() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("job worker requires no ambient transaction");
        }
        if (shouldStop()) {
            return;
        }

        Optional<LeasedJob> leased;
        try {
            leased = service.leaseNext(workerId, leaseDuration);
        } catch (RuntimeException exception) {
            if (shouldStop()) {
                return;
            }
            LOGGER.error("Background job lease failed");
            return;
        }
        if (leased == null || leased.isEmpty()) {
            return;
        }
        if (shouldStop()) {
            return;
        }

        LeasedJob job = leased.get();
        Optional<JobHandler> handler = handlers.find(job.jobType());
        if (handler.isEmpty()) {
            failSafely(job, "JOB_HANDLER_UNAVAILABLE");
            return;
        }
        if (shouldStop()) {
            return;
        }

        try {
            handler.get().handle(job.payload());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Background job handler was interrupted");
            return;
        } catch (Exception exception) {
            if (shouldStop()) {
                LOGGER.warn("Background job handler was interrupted");
                return;
            }
            failSafely(job, "JOB_HANDLER_FAILED");
            return;
        }

        if (shouldStop()) {
            LOGGER.warn("Background job handler was interrupted");
            return;
        }
        try {
            service.succeed(
                    job.id(), job.leaseOwner(), job.attempts());
        } catch (RuntimeException exception) {
            LOGGER.error("Background job acknowledgement failed");
        }
    }

    private void failSafely(LeasedJob job, String summaryCode) {
        if (shouldStop()) {
            return;
        }
        try {
            service.fail(
                    job.id(), job.leaseOwner(), job.attempts(), summaryCode);
        } catch (RuntimeException exception) {
            LOGGER.error("Background job failure transition failed");
        }
    }

    private boolean shouldStop() {
        return shutdownSignal.isStopping() || Thread.currentThread().isInterrupted();
    }
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = Type.SERVLET)
@ConditionalOnProperty(
        prefix = "portfolio.jobs",
        name = "worker-enabled",
        havingValue = "true")
class BackgroundJobWorkerSchedulingConfiguration {
    private static final Logger LOGGER =
            LoggerFactory.getLogger(BackgroundJobWorkerSchedulingConfiguration.class);

    @Bean
    WorkerShutdownSignal workerShutdownSignal() {
        return new WorkerShutdownSignal();
    }

    @Bean(name = "taskScheduler")
    @ConditionalOnMissingBean(name = "taskScheduler")
    ThreadPoolTaskScheduler applicationTaskScheduler(
            ThreadPoolTaskSchedulerBuilder builder) {
        return builder.build();
    }

    @Bean(name = DatabaseJobWorker.SCHEDULER_BEAN)
    ThreadPoolTaskScheduler backgroundJobTaskScheduler(
            @Value("${portfolio.jobs.shutdown-await:PT30S}") Duration shutdownAwait,
            WorkerShutdownSignal shutdownSignal) {
        if (shutdownAwait == null
                || shutdownAwait.isNegative()
                || shutdownAwait.isZero()
                || shutdownAwait.compareTo(Duration.ofMinutes(5)) > 0
                || shutdownAwait.toMillis() == 0) {
            throw new IllegalArgumentException("job scheduler shutdown timeout is invalid");
        }
        long awaitMillis = shutdownAwait.toMillis();

        ThreadPoolTaskScheduler scheduler = new InterruptingTaskScheduler(shutdownSignal);
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("portfolio-background-job-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationMillis(awaitMillis);
        scheduler.setErrorHandler(ignored ->
                LOGGER.error("Background job scheduler task failed"));
        return scheduler;
    }
}

final class InterruptingTaskScheduler extends ThreadPoolTaskScheduler {
    private final AtomicBoolean shutdownStarted = new AtomicBoolean();
    private final WorkerShutdownSignal shutdownSignal;

    InterruptingTaskScheduler(WorkerShutdownSignal shutdownSignal) {
        this.shutdownSignal =
                Objects.requireNonNull(shutdownSignal, "job shutdown signal is required");
    }

    @Override
    protected void initiateEarlyShutdown() {
        shutdownSignal.beginShutdown();
        if (shutdownStarted.compareAndSet(false, true)) {
            super.shutdown();
        }
    }

    @Override
    public void stop(Runnable callback) {
        if (shutdownStarted.get()) {
            callback.run();
            return;
        }
        super.stop(callback);
    }

    @Override
    public void destroy() {
        shutdownSignal.beginShutdown();
        if (shutdownStarted.compareAndSet(false, true)) {
            super.destroy();
        }
    }
}

final class WorkerShutdownSignal {
    private final AtomicBoolean stopping = new AtomicBoolean();

    void beginShutdown() {
        stopping.set(true);
    }

    boolean isStopping() {
        return stopping.get();
    }
}
