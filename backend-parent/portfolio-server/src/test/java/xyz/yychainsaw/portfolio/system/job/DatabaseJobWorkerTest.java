package xyz.yychainsaw.portfolio.system.job;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest(properties = {
    "portfolio.jobs.worker-enabled=true",
    "portfolio.jobs.initial-delay=PT24H",
    "portfolio.jobs.poll-delay=PT24H",
    "portfolio.security.session.cleanup-interval=PT24H"
})
@Import(DatabaseJobWorkerTest.WorkerTestConfiguration.class)
@Isolated
class DatabaseJobWorkerTest extends PostgresIntegrationTestBase {
    private static final Instant NOW = Instant.parse("2026-07-16T13:45:56.123456Z");

    @Autowired DatabaseJobWorker worker;
    @Autowired BackgroundJobService service;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcClient jdbc;
    @Autowired ApplicationContext applicationContext;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ControllableJobHandler handler;

    private final Set<UUID> trackedIds = new LinkedHashSet<>();
    private String keyPrefix;

    @BeforeEach
    void setUp() {
        keyPrefix = "task4-worker-" + UUID.randomUUID() + '-';
        handler.reset();
    }

    @AfterEach
    void cleanUp() {
        handler.release();
        var ids = trackedIds.isEmpty()
                ? Set.of(new UUID(0L, 0L))
                : Set.copyOf(trackedIds);
        new NamedParameterJdbcTemplate(migratorDataSource()).update("""
                        delete from portfolio.background_job
                        where id in (:ids)
                           or idempotency_key like :prefix
                        """,
                new MapSqlParameterSource()
                        .addValue("ids", ids)
                        .addValue("prefix", keyPrefix + '%'));
        trackedIds.clear();
    }

    @Test
    void successfulHandlerRunsOutsideATransactionAndCompletesTheExactAttemptFence() {
        UUID id = track(service.enqueue(
                "TEST", key("success"), Map.of("mode", "SUCCESS", "value", 7)));

        worker.poll();

        JobRow row = job(id);
        assertThat(handler.calls()).isOne();
        assertThat(handler.transactionActive()).isFalse();
        assertThat(handler.payload()).isEqualTo(
                objectMapper.valueToTree(Map.of("mode", "SUCCESS", "value", 7)));
        assertThat(handler.context().jobId()).isEqualTo(id);
        assertThat(handler.context().attemptFence()).isOne();
        assertThat(handler.context().leaseOwner())
                .startsWith("portfolio-worker-");
        assertThat(handler.context().toString())
                .doesNotContain(id.toString(), handler.context().leaseOwner());
        assertThat(row.status()).isEqualTo("SUCCEEDED");
        assertThat(row.attempts()).isOne();
        assertThat(row.leaseOwner()).isNull();
        assertThat(row.leaseUntil()).isNull();
        assertThat(row.lastErrorSummary()).isNull();
    }

    @Test
    void anEmptyPollDoesNothing() {
        worker.poll();

        assertThat(handler.calls()).isZero();
    }

    @Test
    void onePollProcessesAtMostOneOfMultipleReadyJobs() {
        UUID first = track(service.enqueue(
                "TEST", key("one-per-poll-a"), Map.of("mode", "SUCCESS")));
        UUID second = track(service.enqueue(
                "TEST", key("one-per-poll-b"), Map.of("mode", "SUCCESS")));

        worker.poll();

        assertThat(handler.calls()).isOne();
        assertThat(List.of(job(first).status(), job(second).status()))
                .containsExactlyInAnyOrder("SUCCEEDED", "PENDING");
    }

    @Test
    void oneWorkerKeepsTheSameOwnerAcrossPolls() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> current = null;
        try {
            UUID first = track(service.enqueue(
                    "TEST", key("stable-owner-a"), Map.of("mode", "BLOCK_SUCCESS")));
            current = executor.submit(worker::poll);
            handler.awaitEntered();
            String firstOwner = job(first).leaseOwner();
            handler.release();
            current.get(10, SECONDS);

            handler.reset();
            UUID second = track(service.enqueue(
                    "TEST", key("stable-owner-b"), Map.of("mode", "BLOCK_SUCCESS")));
            current = executor.submit(worker::poll);
            handler.awaitEntered();
            String secondOwner = job(second).leaseOwner();
            handler.release();
            current.get(10, SECONDS);

            assertThat(firstOwner).isNotBlank();
            assertThat(secondOwner).isEqualTo(firstOwner);
            assertThat(job(first).status()).isEqualTo("SUCCEEDED");
            assertThat(job(second).status()).isEqualTo("SUCCEEDED");
        } finally {
            handler.release();
            if (current != null && !current.isDone()) {
                current.cancel(true);
            }
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, SECONDS)).isTrue();
        }
    }

    @Test
    void handlerFailureIsContainedAndPersistsOnlyTheFixedSafeCode() {
        UUID id = track(service.enqueue(
                "TEST", key("failure"), Map.of("mode", "FAIL")));

        worker.poll();

        JobRow row = job(id);
        assertThat(handler.calls()).isOne();
        assertThat(handler.transactionActive()).isFalse();
        assertThat(row.status()).isEqualTo("FAILED");
        assertThat(row.attempts()).isOne();
        assertThat(row.nextRunAt()).isEqualTo(NOW.plusSeconds(2));
        assertThat(row.leaseOwner()).isNull();
        assertThat(row.leaseUntil()).isNull();
        assertThat(row.lastErrorSummary()).isEqualTo("JOB_HANDLER_FAILED");
        assertThat(row.lastErrorSummary())
                .doesNotContain("secret", "player@example.com", "/private", "\r", "\n");
    }

    @Test
    void staleSuccessCannotOverwriteAReclaimedAttempt() throws Exception {
        UUID id = track(service.enqueue(
                "TEST", key("stale-success"), Map.of("mode", "BLOCK_SUCCESS")));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> future = executor.submit(worker::poll);
        try {
            handler.awaitEntered();
            expireLease(id);
            LeasedJob replacement = service.leaseNext(
                    "replacement-success", Duration.ofMinutes(30)).orElseThrow();
            assertThat(replacement.attempts()).isEqualTo(2);

            handler.release();
            future.get(10, SECONDS);

            JobRow row = job(id);
            assertThat(row.status()).isEqualTo("RUNNING");
            assertThat(row.attempts()).isEqualTo(2);
            assertThat(row.leaseOwner()).isEqualTo("replacement-success");
            assertThat(row.lastErrorSummary()).isNull();
        } finally {
            handler.release();
            if (!future.isDone()) {
                future.cancel(true);
            }
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, SECONDS)).isTrue();
        }
    }

    @Test
    void staleFailureCannotOverwriteAReclaimedAttempt() throws Exception {
        UUID id = track(service.enqueue(
                "TEST", key("stale-failure"), Map.of("mode", "BLOCK_FAIL")));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> future = executor.submit(worker::poll);
        try {
            handler.awaitEntered();
            expireLease(id);
            LeasedJob replacement = service.leaseNext(
                    "replacement-failure", Duration.ofMinutes(30)).orElseThrow();
            assertThat(replacement.attempts()).isEqualTo(2);

            handler.release();
            future.get(10, SECONDS);

            JobRow row = job(id);
            assertThat(row.status()).isEqualTo("RUNNING");
            assertThat(row.attempts()).isEqualTo(2);
            assertThat(row.leaseOwner()).isEqualTo("replacement-failure");
            assertThat(row.lastErrorSummary()).isNull();
        } finally {
            handler.release();
            if (!future.isDone()) {
                future.cancel(true);
            }
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, SECONDS)).isTrue();
        }
    }

    @Test
    void unknownPersistedTypeFailsSafelyWithoutCallingARegisteredHandler() {
        UUID id = insertPending("unknown", "REMOVED_HANDLER", Map.of("value", 1));

        worker.poll();

        JobRow row = job(id);
        assertThat(handler.calls()).isZero();
        assertThat(row.status()).isEqualTo("FAILED");
        assertThat(row.attempts()).isOne();
        assertThat(row.nextRunAt()).isEqualTo(NOW.plusSeconds(2));
        assertThat(row.leaseOwner()).isNull();
        assertThat(row.leaseUntil()).isNull();
        assertThat(row.lastErrorSummary()).isEqualTo("JOB_HANDLER_UNAVAILABLE");
    }

    @Test
    void pollRejectsAnAmbientTransactionBeforeItLeasesWork() {
        UUID id = track(service.enqueue(
                "TEST", key("ambient"), Map.of("mode", "SUCCESS")));
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> worker.poll()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("job worker requires no ambient transaction")
                .hasNoCause();

        assertThat(handler.calls()).isZero();
        assertThat(job(id).status()).isEqualTo("PENDING");
        assertThat(job(id).attempts()).isZero();
    }

    @Test
    void interruptedHandlerPreservesTheFlagAndNeverReportsSuccessOrFailure() throws Exception {
        UUID id = track(service.enqueue(
                "TEST", key("interrupt"), Map.of("mode", "INTERRUPT")));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> future = executor.submit(worker::poll);
        try {
            handler.awaitEntered();
            assertThat(job(id).status()).isEqualTo("RUNNING");
            assertThat(job(id).attempts()).isOne();

            future.cancel(true);
            handler.awaitInterrupted();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, SECONDS)).isTrue();

            JobRow row = job(id);
            assertThat(handler.interruptFlagPreserved()).isTrue();
            assertThat(row.status()).isEqualTo("RUNNING");
            assertThat(row.attempts()).isOne();
            assertThat(row.leaseOwner()).isNotBlank();
            assertThat(row.leaseUntil()).isAfter(NOW);
            assertThat(row.lastErrorSummary()).isNull();
        } finally {
            handler.release();
            if (!future.isDone()) {
                future.cancel(true);
            }
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, SECONDS)).isTrue();
        }
    }

    @Test
    void propagatedInterruptedExceptionIsRestoredAndNeverReportedAsFailure() throws Exception {
        UUID id = track(service.enqueue(
                "TEST", key("propagated-interrupt"),
                Map.of("mode", "PROPAGATE_INTERRUPT")));
        AtomicBoolean workerReturnedInterrupted = new AtomicBoolean();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> future = executor.submit(() -> {
            worker.poll();
            workerReturnedInterrupted.set(Thread.currentThread().isInterrupted());
        });
        try {
            handler.awaitEntered();
            assertThat(job(id).status()).isEqualTo("RUNNING");

            future.cancel(true);
            handler.awaitInterrupted();
            executor.shutdown();
            assertThat(executor.awaitTermination(10, SECONDS)).isTrue();

            JobRow row = job(id);
            assertThat(workerReturnedInterrupted).isTrue();
            assertThat(row.status()).isEqualTo("RUNNING");
            assertThat(row.attempts()).isOne();
            assertThat(row.leaseOwner()).isNotBlank();
            assertThat(row.lastErrorSummary()).isNull();
        } finally {
            handler.release();
            if (!future.isDone()) {
                future.cancel(true);
            }
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, SECONDS)).isTrue();
        }
    }

    @Test
    void contextShutdownInterruptsTheHandlerWithinTheBoundAndLeavesItsLeaseRunning()
            throws Exception {
        UUID id = track(service.enqueue(
                "TEST", key("context-shutdown"), Map.of("mode", "INTERRUPT_RETURN")));
        GenericApplicationContext context = new GenericApplicationContext();
        WorkerShutdownSignal shutdownSignal = new WorkerShutdownSignal();
        context.registerBean(
                DatabaseJobWorker.SCHEDULER_BEAN,
                ThreadPoolTaskScheduler.class,
                () -> new BackgroundJobWorkerSchedulingConfiguration()
                        .backgroundJobTaskScheduler(
                                Duration.ofMillis(200), shutdownSignal));
        context.refresh();
        ThreadPoolTaskScheduler scheduler =
                context.getBean(DatabaseJobWorker.SCHEDULER_BEAN, ThreadPoolTaskScheduler.class);
        try {
            scheduler.scheduleWithFixedDelay(worker::poll, Instant.now(), Duration.ofDays(1));
            handler.awaitEntered();
            assertThat(job(id).status()).isEqualTo("RUNNING");

            context.close();

            handler.awaitInterrupted();
            JobRow row = job(id);
            assertThat(handler.calls()).isOne();
            assertThat(handler.interruptFlagPreserved()).isTrue();
            assertThat(row.status()).isEqualTo("RUNNING");
            assertThat(row.attempts()).isOne();
            assertThat(row.leaseOwner()).isNotBlank();
            assertThat(row.leaseUntil()).isAfter(NOW);
            assertThat(row.lastErrorSummary()).isNull();
            assertThat(scheduler.getScheduledExecutor().isTerminated()).isTrue();
            assertThat(handler.handlingThread()).isNotNull();
            assertThat(handler.handlingThread().isAlive()).isFalse();
        } finally {
            scheduler.getScheduledExecutor().shutdownNow();
            handler.release();
            scheduler.getScheduledExecutor().awaitTermination(10, SECONDS);
            if (context.isActive()) {
                context.close();
            }
        }
    }

    @Test
    void contextCloseReturnsAfterOneBoundWhenAnInterruptedHandlerIsStillUnwinding()
            throws Exception {
        UUID id = track(service.enqueue(
                "TEST", key("bounded-context-shutdown"),
                Map.of("mode", "INTERRUPT_HOLD")));
        GenericApplicationContext context = new GenericApplicationContext();
        WorkerShutdownSignal shutdownSignal = new WorkerShutdownSignal();
        context.registerBean(
                DatabaseJobWorker.SCHEDULER_BEAN,
                ThreadPoolTaskScheduler.class,
                () -> new BackgroundJobWorkerSchedulingConfiguration()
                        .backgroundJobTaskScheduler(
                                Duration.ofMillis(100), shutdownSignal));
        context.refresh();
        ThreadPoolTaskScheduler scheduler =
                context.getBean(DatabaseJobWorker.SCHEDULER_BEAN, ThreadPoolTaskScheduler.class);
        ExecutorService closer = Executors.newSingleThreadExecutor();
        Future<?> closeFuture = null;
        try {
            scheduler.scheduleWithFixedDelay(worker::poll, Instant.now(), Duration.ofDays(1));
            handler.awaitEntered();
            closeFuture = closer.submit(context::close);

            handler.awaitInterrupted();
            closeFuture.get(3, SECONDS);
            assertThat(handler.handlingThread()).isNotNull();
            assertThat(handler.handlingThread().isAlive()).isTrue();
            assertThat(scheduler.getScheduledExecutor().isShutdown()).isTrue();
            assertThat(scheduler.getScheduledExecutor().isTerminated()).isFalse();

            handler.release();
            assertThat(scheduler.getScheduledExecutor().awaitTermination(10, SECONDS)).isTrue();
            handler.handlingThread().join(10_000);
            assertThat(handler.handlingThread().isAlive()).isFalse();
            assertThat(job(id).status()).isEqualTo("RUNNING");
        } finally {
            handler.release();
            scheduler.getScheduledExecutor().shutdownNow();
            scheduler.getScheduledExecutor().awaitTermination(10, SECONDS);
            if (context.isActive()) {
                context.close();
            }
            if (closeFuture != null && !closeFuture.isDone()) {
                closeFuture.cancel(true);
            }
            closer.shutdownNow();
            assertThat(closer.awaitTermination(10, SECONDS)).isTrue();
        }
    }

    @Test
    void backgroundSchedulerIsIndependentFromTheApplicationScheduler() throws Exception {
        assertThat(applicationContext.containsBean("taskScheduler")).isTrue();
        ThreadPoolTaskScheduler background = applicationContext.getBean(
                DatabaseJobWorker.SCHEDULER_BEAN, ThreadPoolTaskScheduler.class);
        ThreadPoolTaskScheduler application = applicationContext.getBean(
                "taskScheduler", ThreadPoolTaskScheduler.class);
        assertThat(background).isNotSameAs(application);
        assertThat(background.getPoolSize()).isOne();
        assertThat(background.getThreadNamePrefix())
                .isEqualTo("portfolio-background-job-");
        assertThat(background.getScheduledThreadPoolExecutor()
                        .getContinueExistingPeriodicTasksAfterShutdownPolicy())
                .isFalse();
        assertThat(background.getScheduledThreadPoolExecutor()
                        .getExecuteExistingDelayedTasksAfterShutdownPolicy())
                .isFalse();
        assertThat(background.getScheduledThreadPoolExecutor().getRemoveOnCancelPolicy())
                .isTrue();

        CountDownLatch backgroundEntered = new CountDownLatch(1);
        CountDownLatch releaseBackground = new CountDownLatch(1);
        CountDownLatch applicationRan = new CountDownLatch(1);
        background.execute(() -> {
            backgroundEntered.countDown();
            try {
                releaseBackground.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            assertThat(backgroundEntered.await(10, SECONDS)).isTrue();
            application.execute(applicationRan::countDown);
            assertThat(applicationRan.await(2, SECONDS)).isTrue();
        } finally {
            releaseBackground.countDown();
        }
    }

    @Test
    void shutdownAfterLeasingDoesNotStartAHandlerOrCompleteTheLease() {
        WorkerShutdownSignal shutdown = new WorkerShutdownSignal();
        BackgroundJobService stoppingService = mock(BackgroundJobService.class);
        LeasedJob leased = new LeasedJob(
                UUID.randomUUID(), "TEST", objectMapper.valueToTree(Map.of("value", 1)),
                1, "stopping-worker");
        when(stoppingService.leaseNext("stopping-worker", Duration.ofMinutes(30)))
                .thenAnswer(invocation -> {
                    shutdown.beginShutdown();
                    return Optional.of(leased);
                });
        DatabaseJobWorker localWorker = new DatabaseJobWorker(
                stoppingService,
                new JobHandlerRegistry(List.of(handler)),
                Duration.ofMinutes(30),
                "stopping-worker",
                shutdown);

        localWorker.poll();

        assertThat(handler.calls()).isZero();
        verify(stoppingService, never()).succeed(any(), anyString(), anyInt());
        verify(stoppingService, never()).fail(any(), anyString(), anyInt(), anyString());
    }

    @Test
    void leaseAndCompletionFailuresAreContainedWithoutEscapingTheScheduler() throws Exception {
        BackgroundJobService failingLeaseService = mock(BackgroundJobService.class);
        when(failingLeaseService.leaseNext("contained-worker", Duration.ofMinutes(30)))
                .thenThrow(new IllegalStateException("private lease failure"));
        DatabaseJobWorker leaseWorker = new DatabaseJobWorker(
                failingLeaseService,
                new JobHandlerRegistry(List.of(handler)),
                Duration.ofMinutes(30),
                "contained-worker");

        assertThatCode(leaseWorker::poll).doesNotThrowAnyException();
        assertThat(handler.calls()).isZero();

        BackgroundJobService failingCompletionService = mock(BackgroundJobService.class);
        LeasedJob leased = new LeasedJob(
                UUID.randomUUID(), "TEST",
                objectMapper.valueToTree(Map.of("mode", "SUCCESS")),
                1, "contained-worker");
        when(failingCompletionService.leaseNext(
                        "contained-worker", Duration.ofMinutes(30)))
                .thenReturn(Optional.of(leased));
        when(failingCompletionService.succeed(leased.id(), "contained-worker", 1))
                .thenThrow(new IllegalStateException("private completion failure"));
        DatabaseJobWorker completionWorker = new DatabaseJobWorker(
                failingCompletionService,
                new JobHandlerRegistry(List.of(handler)),
                Duration.ofMinutes(30),
                "contained-worker");

        assertThatCode(completionWorker::poll).doesNotThrowAnyException();
        assertThat(handler.calls()).isOne();

        handler.reset();
        BackgroundJobService failingTransitionService = mock(BackgroundJobService.class);
        LeasedJob failed = new LeasedJob(
                UUID.randomUUID(), "TEST",
                objectMapper.valueToTree(Map.of("mode", "FAIL")),
                1, "contained-worker");
        when(failingTransitionService.leaseNext(
                        "contained-worker", Duration.ofMinutes(30)))
                .thenReturn(Optional.of(failed));
        when(failingTransitionService.fail(
                        failed.id(), "contained-worker", 1, "JOB_HANDLER_FAILED"))
                .thenThrow(new IllegalStateException("private transition failure"));
        DatabaseJobWorker transitionWorker = new DatabaseJobWorker(
                failingTransitionService,
                new JobHandlerRegistry(List.of(handler)),
                Duration.ofMinutes(30),
                "contained-worker");

        assertThatCode(transitionWorker::poll).doesNotThrowAnyException();
        assertThat(handler.calls()).isOne();
        verify(failingTransitionService)
                .fail(failed.id(), "contained-worker", 1, "JOB_HANDLER_FAILED");
    }

    @Test
    void workerRequiresBothAnExplicitPropertyAndAServletApplication() {
        new ApplicationContextRunner()
                .withUserConfiguration(DatabaseJobWorker.class)
                .withPropertyValues("portfolio.jobs.worker-enabled=true")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(DatabaseJobWorker.class));

        new WebApplicationContextRunner()
                .withUserConfiguration(DatabaseJobWorker.class)
                .run(context -> assertThat(context)
                        .doesNotHaveBean(DatabaseJobWorker.class));

        new WebApplicationContextRunner()
                .withUserConfiguration(DatabaseJobWorker.class)
                .withPropertyValues("portfolio.jobs.worker-enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(DatabaseJobWorker.class));
    }

    @Test
    void schedulerRejectsNonMillisecondAndUnboundedShutdownTimeouts() {
        BackgroundJobWorkerSchedulingConfiguration configuration =
                new BackgroundJobWorkerSchedulingConfiguration();
        WorkerShutdownSignal signal = new WorkerShutdownSignal();

        for (Duration invalid : List.of(
                Duration.ZERO,
                Duration.ofNanos(1),
                Duration.ofMinutes(5).plusMillis(1))) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> configuration.backgroundJobTaskScheduler(
                            invalid, signal))
                    .withMessage("job scheduler shutdown timeout is invalid")
                    .withNoCause();
        }
        assertThatIllegalArgumentException()
                .isThrownBy(() -> configuration.backgroundJobTaskScheduler(null, signal))
                .withMessage("job scheduler shutdown timeout is invalid")
                .withNoCause();
    }

    private UUID insertPending(String suffix, String jobType, Map<String, ?> payload) {
        UUID id = track(UUID.randomUUID());
        migratorJdbc().sql("""
                        insert into portfolio.background_job(
                            id, job_type, idempotency_key, payload, status, attempts,
                            next_run_at, created_at, updated_at
                        ) values (
                            :id, :jobType, :key, cast(:payload as jsonb), 'PENDING', 0,
                            :nextRunAt, :createdAt, :updatedAt
                        )
                        """)
                .param("id", id)
                .param("jobType", jobType)
                .param("key", key(suffix))
                .param("payload", json(payload))
                .param("nextRunAt", utc(NOW), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("createdAt", utc(NOW), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("updatedAt", utc(NOW), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        return id;
    }

    private void expireLease(UUID id) {
        migratorJdbc().sql("""
                        update portfolio.background_job
                        set lease_until=:expired
                        where id=:id
                        """)
                .param("expired", utc(NOW.minusNanos(1_000)),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .param("id", id)
                .update();
    }

    private JobRow job(UUID id) {
        return jdbc.sql("""
                        select status, attempts, next_run_at, lease_owner, lease_until,
                               last_error_summary
                        from portfolio.background_job
                        where id=:id
                        """)
                .param("id", id)
                .query((resultSet, rowNumber) -> new JobRow(
                        resultSet.getString("status"),
                        resultSet.getInt("attempts"),
                        resultSet.getObject("next_run_at", OffsetDateTime.class).toInstant(),
                        resultSet.getString("lease_owner"),
                        nullableInstant(resultSet.getObject("lease_until", OffsetDateTime.class)),
                        resultSet.getString("last_error_summary")))
                .single();
    }

    private String json(Map<String, ?> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("test payload serialization failed", exception);
        }
    }

    private String key(String suffix) {
        return keyPrefix + suffix;
    }

    private UUID track(UUID id) {
        trackedIds.add(id);
        return id;
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant nullableInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class WorkerTestConfiguration {
        @Bean
        @Primary
        Clock fixedWorkerClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        ControllableJobHandler controllableJobHandler() {
            return new ControllableJobHandler();
        }
    }

    static final class ControllableJobHandler implements JobHandler {
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<JsonNode> payload = new AtomicReference<>();
        private final AtomicReference<JobExecutionContext> context = new AtomicReference<>();
        private final AtomicBoolean transactionActive = new AtomicBoolean();
        private final AtomicBoolean interruptFlagPreserved = new AtomicBoolean();
        private final AtomicReference<Thread> handlingThread = new AtomicReference<>();
        private volatile CountDownLatch entered = new CountDownLatch(1);
        private volatile CountDownLatch release = new CountDownLatch(1);
        private volatile CountDownLatch interrupted = new CountDownLatch(1);

        @Override
        public String jobType() {
            return "TEST";
        }

        @Override
        public void handle(JsonNode value) throws InterruptedException {
            calls.incrementAndGet();
            handlingThread.set(Thread.currentThread());
            payload.set(value.deepCopy());
            transactionActive.set(
                    TransactionSynchronizationManager.isActualTransactionActive());
            String mode = value.path("mode").asText();
            if (mode.equals("FAIL")) {
                throw new SyntheticHandlerException(
                        "token=secret player@example.com /private\r\nstack detail");
            }
            if (mode.equals("INTERRUPT")
                    || mode.equals("INTERRUPT_RETURN")
                    || mode.equals("INTERRUPT_HOLD")) {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    if (mode.equals("INTERRUPT_HOLD")) {
                        interrupted.countDown();
                        while (true) {
                            try {
                                release.await();
                                break;
                            } catch (InterruptedException repeated) {
                                // The fixture deliberately holds until released.
                            }
                        }
                    }
                    Thread.currentThread().interrupt();
                    interruptFlagPreserved.set(Thread.currentThread().isInterrupted());
                    interrupted.countDown();
                    if (mode.equals("INTERRUPT")) {
                        throw new SyntheticHandlerException("private interruption detail");
                    }
                }
            }
            if (mode.equals("PROPAGATE_INTERRUPT")) {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    interrupted.countDown();
                    throw exception;
                }
            }
            if (mode.equals("BLOCK_SUCCESS")) {
                entered.countDown();
                release.await();
            }
            if (mode.equals("BLOCK_FAIL")) {
                entered.countDown();
                release.await();
                throw new SyntheticHandlerException("private stale failure detail");
            }
        }

        @Override
        public void handle(JobExecutionContext execution, JsonNode value)
                throws InterruptedException {
            context.set(execution);
            handle(value);
        }

        int calls() {
            return calls.get();
        }

        JsonNode payload() {
            JsonNode value = payload.get();
            return value == null ? null : value.deepCopy();
        }

        JobExecutionContext context() {
            return context.get();
        }

        boolean transactionActive() {
            return transactionActive.get();
        }

        boolean interruptFlagPreserved() {
            return interruptFlagPreserved.get();
        }

        Thread handlingThread() {
            return handlingThread.get();
        }

        void awaitEntered() throws InterruptedException {
            assertThat(entered.await(10, SECONDS)).isTrue();
        }

        void awaitInterrupted() throws InterruptedException {
            assertThat(interrupted.await(10, SECONDS)).isTrue();
        }

        void release() {
            release.countDown();
        }

        void reset() {
            calls.set(0);
            payload.set(null);
            context.set(null);
            transactionActive.set(false);
            interruptFlagPreserved.set(false);
            handlingThread.set(null);
            entered = new CountDownLatch(1);
            release = new CountDownLatch(1);
            interrupted = new CountDownLatch(1);
        }
    }

    private static final class SyntheticHandlerException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private SyntheticHandlerException(String message) {
            super(message);
        }
    }

    private record JobRow(
            String status,
            int attempts,
            Instant nextRunAt,
            String leaseOwner,
            Instant leaseUntil,
            String lastErrorSummary) {}
}
