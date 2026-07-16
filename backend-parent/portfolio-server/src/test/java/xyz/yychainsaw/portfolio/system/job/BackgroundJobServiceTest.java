package xyz.yychainsaw.portfolio.system.job;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest(properties = {
    "portfolio.jobs.worker-enabled=false",
    "portfolio.jobs.initial-delay=PT24H",
    "portfolio.jobs.poll-delay=PT24H",
    "portfolio.security.session.cleanup-interval=PT24H"
})
@Import(BackgroundJobServiceTest.JobTestConfiguration.class)
@Isolated
class BackgroundJobServiceTest extends PostgresIntegrationTestBase {
    private static final Instant NOW = Instant.parse("2026-07-16T12:34:56.123456Z");
    private static final Duration LEASE = Duration.ofMinutes(30);

    @Autowired BackgroundJobService service;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired RecordingJobHandler handler;

    private final Set<UUID> trackedIds = new LinkedHashSet<>();
    private String keyPrefix;

    @BeforeEach
    void setUp() {
        keyPrefix = "task4-service-" + UUID.randomUUID() + '-';
        handler.reset();
    }

    @AfterEach
    void cleanUp() {
        handler.release();
        List<UUID> ids = trackedIds.isEmpty()
                ? List.of(new UUID(0L, 0L))
                : List.copyOf(trackedIds);
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
    void enqueuePersistsARegisteredJsonObjectWithExactInitialState() {
        Map<String, Object> payload = Map.of(
                "value", 1,
                "nested", Map.of("enabled", true));

        UUID id = track(service.enqueue("TEST", key("initial"), payload));

        JobRow row = job(id);
        assertThat(row.id()).isEqualTo(id);
        assertThat(row.jobType()).isEqualTo("TEST");
        assertThat(row.idempotencyKey()).isEqualTo(key("initial"));
        assertThat(row.payload()).isEqualTo(objectNode(payload));
        assertThat(row.status()).isEqualTo("PENDING");
        assertThat(row.attempts()).isZero();
        assertThat(row.nextRunAt()).isEqualTo(NOW);
        assertThat(row.leaseOwner()).isNull();
        assertThat(row.leaseUntil()).isNull();
        assertThat(row.lastErrorSummary()).isNull();
        assertThat(row.createdAt()).isEqualTo(NOW);
        assertThat(row.updatedAt()).isEqualTo(NOW);
    }

    @Test
    void duplicateEnqueueReturnsTheOriginalIdWithoutMutatingAnyColumnOrTimestamp() {
        UUID id = track(service.enqueue(
                "TEST", key("duplicate"), Map.of("version", "original")));
        migratorJdbc().sql("""
                        update portfolio.background_job
                        set status='RUNNING', attempts=4, lease_owner='worker-original',
                            lease_until=:leaseUntil, last_error_summary='PREVIOUS_FAILURE'
                        where id=:id
                        """)
                .param("leaseUntil", utc(NOW.plus(Duration.ofMinutes(10))),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .param("id", id)
                .update();
        JobRow before = job(id);

        UUID duplicate = service.enqueue(
                "TEST", key("duplicate"), Map.of("version", "original"));

        assertThat(duplicate).isEqualTo(id);
        assertThat(job(id)).isEqualTo(before);
        assertThat(countByKey(key("duplicate"))).isOne();
    }

    @Test
    void duplicateKeyRejectsDifferentPayloadWithoutMutatingTheOriginal() {
        UUID id = track(service.enqueue(
                "TEST", key("idempotency-conflict"), Map.of("version", "original")));
        JobRow before = job(id);

        assertThatThrownBy(() -> service.enqueue(
                        "TEST", key("idempotency-conflict"), Map.of("version", "changed")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("JOB_IDEMPOTENCY_CONFLICT")
                .hasNoCause();

        assertThat(job(id)).isEqualTo(before);
        assertThat(countByKey(key("idempotency-conflict"))).isOne();
    }

    @Test
    void duplicateKeyRejectsADifferentRegisteredTypeWithoutMutatingTheOriginal() {
        UUID id = track(service.enqueue(
                "TEST", key("idempotency-type-conflict"), Map.of("version", "original")));
        JobRow before = job(id);

        assertThatThrownBy(() -> service.enqueue(
                        "SECOND", key("idempotency-type-conflict"),
                        Map.of("version", "original")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("JOB_IDEMPOTENCY_CONFLICT")
                .hasNoCause();

        assertThat(job(id)).isEqualTo(before);
        assertThat(countByKey(key("idempotency-type-conflict"))).isOne();
    }

    @Test
    void duplicatePayloadUsesPostgresJsonbNumericSemantics() {
        UUID id = track(service.enqueue(
                "TEST", key("numeric-semantics"), Map.of("number", 1)));

        UUID duplicate = service.enqueue(
                "TEST", key("numeric-semantics"),
                Map.of("number", new BigDecimal("1.0")));

        assertThat(duplicate).isEqualTo(id);
        assertThat(countByKey(key("numeric-semantics"))).isOne();

        UUID precise = track(service.enqueue(
                "TEST", key("numeric-precision"),
                Map.of("number", new BigDecimal("1.0000000000000000000000000000001"))));
        JobRow before = job(precise);
        assertThatThrownBy(() -> service.enqueue(
                        "TEST", key("numeric-precision"),
                        Map.of("number", new BigDecimal(
                                "1.0000000000000000000000000000002"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("JOB_IDEMPOTENCY_CONFLICT")
                .hasNoCause();
        assertThat(job(precise)).isEqualTo(before);
    }

    @Test
    void concurrentDuplicateEnqueueReturnsOneStableIdAndOneRow() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<UUID>> futures = new ArrayList<>();
        try {
            for (int worker = 0; worker < 2; worker++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    await(start, "enqueue workers did not start");
                    return service.enqueue(
                            "TEST", key("concurrent-duplicate"), Map.of("value", 1));
                }));
            }
            await(ready, "enqueue workers were not ready");
            start.countDown();

            UUID first = futures.get(0).get(30, SECONDS);
            UUID second = futures.get(1).get(30, SECONDS);
            track(first);
            track(second);
            assertThat(first).isEqualTo(second);
            assertThat(countByKey(key("concurrent-duplicate"))).isOne();
        } finally {
            start.countDown();
            stopExecutor(executor, futures);
        }
    }

    @Test
    void concurrentDifferentPayloadsCommitOneRowAndRejectTheLoser() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<EnqueueOutcome>> futures = new ArrayList<>();
        try {
            for (int version : List.of(1, 2)) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    await(start, "enqueue workers did not start");
                    try {
                        return new EnqueueOutcome(service.enqueue(
                                "TEST", key("concurrent-conflict"),
                                Map.of("version", version)), null);
                    } catch (IllegalStateException exception) {
                        return new EnqueueOutcome(null, exception.getMessage());
                    }
                }));
            }
            await(ready, "enqueue workers were not ready");
            start.countDown();

            List<EnqueueOutcome> outcomes = List.of(
                    futures.get(0).get(30, SECONDS), futures.get(1).get(30, SECONDS));
            List<UUID> committedIds = outcomes.stream()
                    .map(EnqueueOutcome::id)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            assertThat(committedIds).hasSize(1);
            UUID committed = committedIds.get(0);
            track(committed);
            assertThat(outcomes)
                    .extracting(EnqueueOutcome::errorCode)
                    .containsExactlyInAnyOrder(null, "JOB_IDEMPOTENCY_CONFLICT");
            assertThat(countByKey(key("concurrent-conflict"))).isOne();
            assertThat(job(committed).payload().path("version").asInt()).isIn(1, 2);
        } finally {
            start.countDown();
            stopExecutor(executor, futures);
        }
    }

    @Test
    void payloadLimitUsesSerializedUtf8BytesAndIncludesExactlySixteenKibibytes()
            throws Exception {
        int limit = 16 * 1024;
        int envelope = objectMapper.writeValueAsBytes(Map.of("data", "")).length;
        Map<String, Object> exact = Map.of("data", "a".repeat(limit - envelope));
        Map<String, Object> oversized = Map.of("data", "a".repeat(limit - envelope + 1));
        Map<String, Object> multibyteOversized = Map.of("data", "界".repeat(6000));
        assertThat(objectMapper.writeValueAsBytes(exact)).hasSize(limit);
        assertThat(objectMapper.writeValueAsBytes(oversized)).hasSize(limit + 1);
        assertThat(((String) multibyteOversized.get("data")).length()).isLessThan(limit);
        assertThat(objectMapper.writeValueAsBytes(multibyteOversized).length).isGreaterThan(limit);

        UUID exactId = track(service.enqueue("TEST", key("payload-exact"), exact));
        assertThat(service.enqueue("TEST", key("payload-exact"), exact)).isEqualTo(exactId);

        LeasedJob exactLease = service.leaseNext("payload-worker", LEASE).orElseThrow();
        assertThat(exactLease.id()).isEqualTo(exactId);
        assertThat(objectMapper.writeValueAsBytes(exactLease.payload())).hasSize(limit);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.enqueue(
                        "TEST", key("payload-oversized"), oversized))
                .withMessage("job payload is invalid")
                .withNoCause();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.enqueue(
                        "TEST", key("payload-multibyte"), multibyteOversized))
                .withMessage("job payload is invalid")
                .withNoCause();
        assertThat(countByKey(key("payload-oversized"))).isZero();
        assertThat(countByKey(key("payload-multibyte"))).isZero();
    }

    @Test
    void numericPayloadsRemainExactAndExpansionUsesTheSameTotalPayloadBudget() {
        BigDecimal precise = new BigDecimal(
                "0.123456789012345678901234567890123456789");
        UUID id = track(service.enqueue(
                "TEST", key("precise-number"), Map.of("number", precise)));

        LeasedJob leased = service.leaseNext("numeric-worker", LEASE).orElseThrow();

        assertThat(leased.id()).isEqualTo(id);
        assertThat(leased.payload().path("number").decimalValue())
                .isEqualByComparingTo(precise);

        BigDecimal largeButBounded = new BigDecimal("1E+1001");
        UUID boundedId = track(service.enqueue(
                "TEST", key("bounded-expanded-number"),
                Map.of("number", largeButBounded)));
        LeasedJob boundedLease = service.leaseNext("bounded-numeric-worker", LEASE)
                .orElseThrow();
        assertThat(boundedLease.id()).isEqualTo(boundedId);
        assertThat(boundedLease.payload().path("number").decimalValue())
                .isEqualByComparingTo(largeButBounded);

        BigDecimal aboveJacksonPlainScale = new BigDecimal("1E+10000");
        UUID scaleBoundaryId = track(service.enqueue(
                "TEST", key("jackson-scale-boundary"),
                Map.of("number", aboveJacksonPlainScale)));
        LeasedJob scaleBoundaryLease = service.leaseNext(
                        "jackson-scale-worker", LEASE)
                .orElseThrow();
        assertThat(scaleBoundaryLease.id()).isEqualTo(scaleBoundaryId);
        assertThat(scaleBoundaryLease.payload().path("number").decimalValue())
                .isEqualByComparingTo(aboveJacksonPlainScale);

        BigDecimal fractionalScaleBoundary = new BigDecimal("1E-10000");
        UUID fractionalBoundaryId = track(service.enqueue(
                "TEST", key("fractional-scale-boundary"),
                Map.of("number", fractionalScaleBoundary)));
        LeasedJob fractionalBoundaryLease = service.leaseNext(
                        "fractional-scale-worker", LEASE)
                .orElseThrow();
        assertThat(fractionalBoundaryLease.id()).isEqualTo(fractionalBoundaryId);
        assertThat(fractionalBoundaryLease.payload().path("number").decimalValue())
                .isEqualByComparingTo(fractionalScaleBoundary);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.enqueue(
                        "TEST", key("over-budget-expanded-number"),
                        Map.of("number", new BigDecimal("1E+20000"))))
                .withMessage("job payload is invalid")
                .withNoCause();
        assertThat(countByKey(key("over-budget-expanded-number"))).isZero();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.enqueue(
                        "TEST", key("aggregate-expanded-numbers"),
                        Map.of(
                                "first", new BigDecimal("1E+9000"),
                                "second", new BigDecimal("1E+9000"))))
                .withMessage("job payload is invalid")
                .withNoCause();
        assertThat(countByKey(key("aggregate-expanded-numbers"))).isZero();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.enqueue(
                        "TEST", key("over-budget-scaled-zero"),
                        Map.of("number", new BigDecimal("0E-20000"))))
                .withMessage("job payload is invalid")
                .withNoCause();
        assertThat(countByKey(key("over-budget-scaled-zero"))).isZero();
    }

    @Test
    void nullAndUnserializablePayloadsFailBeforeSqlWithOneSafeBoundary() {
        Map<String, Object> cyclic = new HashMap<>();
        cyclic.put("self", cyclic);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.enqueue("TEST", key("payload-null"), null))
                .withMessage("job payload is invalid")
                .withNoCause();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.enqueue("TEST", key("payload-cycle"), cyclic))
                .withMessage("job payload is invalid")
                .withNoCause();
        assertThat(countByKey(key("payload-null"))).isZero();
        assertThat(countByKey(key("payload-cycle"))).isZero();
    }

    @Test
    void enqueueRequiresARegisteredCanonicalTypeAndAVisibleAsciiBoundedKey() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.enqueue(
                        "UNKNOWN", key("unknown-type"), Map.of()))
                .withMessage("job type is invalid")
                .withNoCause();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.enqueue(
                        "test", key("noncanonical-type"), Map.of()))
                .withMessage("job type is invalid")
                .withNoCause();

        for (String invalidKey : List.of(
                " ", " leading", "trailing ", "contains space", "界", "k".repeat(161))) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> service.enqueue("TEST", invalidKey, Map.of()))
                    .withMessage("job idempotency key is invalid")
                    .withNoCause();
        }
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.enqueue("TEST", null, Map.of()))
                .withMessage("job idempotency key is invalid")
                .withNoCause();

        String maximumKey = "k".repeat(160);
        track(service.enqueue("TEST", maximumKey, Map.of()));
        assertThat(countByKey(maximumKey)).isOne();
    }

    @Test
    void twoWorkersCannotLeaseTheSameJob() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<Optional<LeasedJob>>> futures = new ArrayList<>();
        try {
            for (int iteration = 0; iteration < 25; iteration++) {
                UUID id = track(service.enqueue(
                        "TEST", key("lease-race-" + iteration), Map.of("iteration", iteration)));
                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);
                futures.clear();
                for (String owner : List.of("worker-a", "worker-b")) {
                    futures.add(executor.submit(() -> {
                        ready.countDown();
                        await(start, "lease workers did not start");
                        return service.leaseNext(owner, LEASE);
                    }));
                }
                await(ready, "lease workers were not ready");
                start.countDown();
                List<LeasedJob> leased = new ArrayList<>();
                for (Future<Optional<LeasedJob>> future : futures) {
                    future.get(30, SECONDS).ifPresent(leased::add);
                }

                assertThat(leased).singleElement().satisfies(job -> {
                    assertThat(job.id()).isEqualTo(id);
                    assertThat(job.attempts()).isOne();
                    assertThat(job.leaseOwner()).isIn("worker-a", "worker-b");
                });
                assertThat(job(id).attempts()).isOne();
            }
        } finally {
            stopExecutor(executor, futures);
        }
    }

    @Test
    void skipLockedLeasesTheSecondReadyRowWhileTheFirstRowIsExplicitlyLocked()
            throws Exception {
        UUID first = insertJob(
                "locked-first", "TEST", objectNode(Map.of("order", 1)),
                "PENDING", 0, NOW.minusSeconds(2), null, null,
                NOW.minusSeconds(2), null);
        UUID second = insertJob(
                "ready-second", "TEST", objectNode(Map.of("order", 2)),
                "PENDING", 0, NOW.minusSeconds(1), null, null,
                NOW.minusSeconds(1), null);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Optional<LeasedJob>> future = null;
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (PreparedStatement statement = blocker.prepareStatement("""
                    select id from portfolio.background_job where id=? for update
                    """)) {
                statement.setObject(1, first);
                assertThat(statement.executeQuery().next()).isTrue();
            }

            future = executor.submit(() -> service.leaseNext("worker-skip", LEASE));
            Optional<LeasedJob> leased = future.get(5, SECONDS);

            assertThat(leased).get().extracting(LeasedJob::id).isEqualTo(second);
            assertThat(job(first).status()).isEqualTo("PENDING");
            blocker.rollback();
        } finally {
            if (future != null && !future.isDone()) {
                future.cancel(true);
            }
            stopExecutor(executor, future == null ? List.of() : List.of(future));
        }
    }

    @Test
    void leasingIsDeterministicAndIgnoresFutureAndTerminalRows() {
        insertJob(
                "terminal", "TEST", objectNode(Map.of()),
                "SUCCEEDED", 1, NOW.minusSeconds(100), null, null,
                NOW.minusSeconds(100), null);
        UUID future = insertJob(
                "future", "TEST", objectNode(Map.of()),
                "PENDING", 0, NOW.plusSeconds(1), null, null,
                NOW.minusSeconds(100), null);
        UUID laterCreated = insertJob(
                "later-created", "TEST", objectNode(Map.of()),
                "PENDING", 0, NOW.minusSeconds(1), null, null,
                NOW.minusSeconds(1), null);
        UUID earlierCreated = insertJob(
                "earlier-created", "TEST", objectNode(Map.of()),
                "FAILED", 1, NOW.minusSeconds(1), null, null,
                NOW.minusSeconds(2), "JOB_FAILED");
        UUID earliestRun = insertJob(
                "earliest-run", "TEST", objectNode(Map.of()),
                "PENDING", 0, NOW.minusSeconds(2), null, null,
                NOW.minusSeconds(1), null);

        assertThat(service.leaseNext("ordering-worker", LEASE))
                .get().extracting(LeasedJob::id).isEqualTo(earliestRun);
        assertThat(service.leaseNext("ordering-worker", LEASE))
                .get().extracting(LeasedJob::id).isEqualTo(earlierCreated);
        assertThat(service.leaseNext("ordering-worker", LEASE))
                .get().extracting(LeasedJob::id).isEqualTo(laterCreated);
        assertThat(service.leaseNext("ordering-worker", LEASE)).isEmpty();
        assertThat(job(future).status()).isEqualTo("PENDING");
    }

    @Test
    void aRunningLeaseIsReclaimedOnlyWhenItIsStrictlyOlderThanNow() {
        UUID id = insertJob(
                "lease-equality", "TEST", objectNode(Map.of()),
                "RUNNING", 1, NOW.minusSeconds(60), "same-worker", NOW,
                NOW.minusSeconds(60), null);

        assertThat(service.leaseNext("replacement", LEASE)).isEmpty();
        assertThat(job(id).attempts()).isOne();

        migratorJdbc().sql("""
                        update portfolio.background_job
                        set lease_until=:expired
                        where id=:id
                        """)
                .param("expired", utc(NOW.minusNanos(1_000)),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .param("id", id)
                .update();

        assertThat(service.leaseNext("replacement", LEASE)).get().satisfies(leased -> {
            assertThat(leased.id()).isEqualTo(id);
            assertThat(leased.attempts()).isEqualTo(2);
            assertThat(leased.leaseOwner()).isEqualTo("replacement");
        });
        JobRow reclaimed = job(id);
        assertThat(reclaimed.status()).isEqualTo("RUNNING");
        assertThat(reclaimed.leaseUntil()).isEqualTo(NOW.plus(LEASE));
    }

    @Test
    void theAttemptFenceRejectsAnOldCompletionAfterTheSameOwnerReclaimsTheLease() {
        UUID id = insertJob(
                "same-owner-fence", "TEST", objectNode(Map.of()),
                "RUNNING", 1, NOW.minusSeconds(60), "same-worker",
                NOW.minusNanos(1_000), NOW.minusSeconds(60), null);

        LeasedJob reclaimed = service.leaseNext("same-worker", LEASE).orElseThrow();
        assertThat(reclaimed.attempts()).isEqualTo(2);
        JobRow currentLease = job(id);

        assertThat(service.succeed(id, "same-worker", 1)).isFalse();
        assertThat(service.fail(id, "same-worker", 1, "STALE_FAILURE")).isFalse();
        assertThat(job(id)).isEqualTo(currentLease);
        assertThat(service.succeed(id, "same-worker", 2)).isTrue();
        assertThat(job(id).status()).isEqualTo("SUCCEEDED");
        assertThat(job(id).leaseOwner()).isNull();
        assertThat(job(id).leaseUntil()).isNull();
    }

    @ParameterizedTest(name = "attempt {0} retries after {1} seconds")
    @CsvSource({
        "1,2",
        "2,4",
        "3,8",
        "4,16",
        "5,32",
        "6,64",
        "7,128",
        "8,256",
        "9,512"
    })
    void failureUsesTheAttemptFenceForExactExponentialRetry(
            int attempt, long expectedDelaySeconds) {
        UUID id = insertJob(
                "retry-" + attempt, "TEST", objectNode(Map.of("attempt", attempt)),
                "RUNNING", attempt, NOW.minusSeconds(60), "retry-worker",
                NOW.plus(LEASE), NOW.minusSeconds(60), null);

        assertThat(service.fail(id, "retry-worker", attempt, "JOB_HANDLER_FAILED")).isTrue();

        JobRow row = job(id);
        assertThat(row.status()).isEqualTo("FAILED");
        assertThat(row.attempts()).isEqualTo(attempt);
        assertThat(row.nextRunAt()).isEqualTo(NOW.plusSeconds(expectedDelaySeconds));
        assertThat(row.leaseOwner()).isNull();
        assertThat(row.leaseUntil()).isNull();
        assertThat(row.lastErrorSummary()).isEqualTo("JOB_HANDLER_FAILED");
    }

    @Test
    void tenthFailureDeadLettersWithoutSchedulingAnEleventhAttempt() {
        UUID id = insertJob(
                "tenth-failure", "TEST", objectNode(Map.of()),
                "RUNNING", 10, NOW.minusSeconds(60), "final-worker",
                NOW.plus(LEASE), NOW.minusSeconds(60), null);

        assertThat(service.fail(id, "final-worker", 10, "JOB_HANDLER_FAILED")).isTrue();

        JobRow row = job(id);
        assertThat(row.status()).isEqualTo("DEAD");
        assertThat(row.attempts()).isEqualTo(10);
        assertThat(row.nextRunAt()).isEqualTo(NOW);
        assertThat(row.leaseOwner()).isNull();
        assertThat(row.leaseUntil()).isNull();
        assertThat(row.lastErrorSummary()).isEqualTo("JOB_HANDLER_FAILED");
        assertThat(handler.deadLetterCodes()).containsExactly("JOB_HANDLER_FAILED");
        assertThat(service.leaseNext("eleventh-worker", LEASE)).isEmpty();
    }

    @Test
    void tenthFailureAndItsDeadLetterHookCommitOrRollBackAsOneTransaction() {
        String committedProbeKey = key("tenth-committed-probe");
        UUID committedId = insertJob(
                "tenth-committed", "TEST",
                objectNode(Map.of(
                        "deadHook", "PROBE",
                        "probeKey", committedProbeKey)),
                "RUNNING", 10, NOW.minusSeconds(60), "committed-worker",
                NOW.plus(LEASE), NOW.minusSeconds(60), null);

        assertThat(service.fail(
                        committedId,
                        "committed-worker",
                        10,
                        "JOB_HANDLER_FAILED"))
                .isTrue();

        assertThat(job(committedId).status()).isEqualTo("DEAD");
        assertThat(countByKey(committedProbeKey)).isOne();
        assertThat(handler.deadLetterTransactionActive()).isTrue();

        String rolledBackProbeKey = key("tenth-rolled-back-probe");
        UUID rolledBackId = insertJob(
                "tenth-rolled-back", "TEST",
                objectNode(Map.of(
                        "deadHook", "THROW",
                        "probeKey", rolledBackProbeKey)),
                "RUNNING", 10, NOW.minusSeconds(60), "rolled-back-worker",
                NOW.plus(LEASE), NOW.minusSeconds(60), null);
        JobRow before = job(rolledBackId);

        assertThatThrownBy(() -> service.fail(
                        rolledBackId,
                        "rolled-back-worker",
                        10,
                        "JOB_HANDLER_FAILED"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("job dead-letter hook failed")
                .hasNoCause();

        assertThat(job(rolledBackId)).isEqualTo(before);
        assertThat(countByKey(rolledBackProbeKey)).isZero();
    }

    @Test
    void everyAllowedFailureCodeIsStoredExactly() {
        List<String> allowed = List.of(
                "JOB_FAILED",
                "JOB_HANDLER_FAILED",
                "JOB_HANDLER_UNAVAILABLE",
                "JOB_HANDLER_INTERRUPTED",
                "JOB_ATTEMPTS_EXHAUSTED");

        for (int index = 0; index < allowed.size(); index++) {
            String code = allowed.get(index);
            UUID id = insertJob(
                    "allowed-summary-" + index, "TEST", objectNode(Map.of()),
                    "RUNNING", 1, NOW.minusSeconds(60), "summary-worker",
                    NOW.plus(LEASE), NOW.minusSeconds(60), null);

            assertThat(service.fail(id, "summary-worker", 1, code)).isTrue();
            assertThat(job(id).lastErrorSummary()).isEqualTo(code);
        }
    }

    @Test
    void invalidFailureTextIsReplacedByTheFixedSafeCode() {
        UUID id = insertJob(
                "invalid-summary", "TEST", objectNode(Map.of()),
                "RUNNING", 1, NOW.minusSeconds(60), "summary-worker",
                NOW.plus(LEASE), NOW.minusSeconds(60), null);

        assertThat(service.fail(
                        id,
                        "summary-worker",
                        1,
                        "PASSWORD_SECRET123"))
                .isTrue();

        assertThat(job(id).lastErrorSummary()).isEqualTo("JOB_FAILED");
    }

    @Test
    void nullFailureCodeIsAlsoReplacedByTheFixedSafeCode() {
        UUID id = insertJob(
                "null-summary", "TEST", objectNode(Map.of()),
                "RUNNING", 1, NOW.minusSeconds(60), "summary-worker",
                NOW.plus(LEASE), NOW.minusSeconds(60), null);

        assertThat(service.fail(id, "summary-worker", 1, null)).isTrue();

        assertThat(job(id).lastErrorSummary()).isEqualTo("JOB_FAILED");
    }

    @Test
    void exhaustedAttemptRecoveryMovesTheRowAndItsHookProbeToDeadAtomically() {
        String probeKey = key("exhausted-probe");
        UUID id = insertJob(
                "exhausted", "TEST",
                objectNode(Map.of("deadHook", "PROBE", "probeKey", probeKey)),
                "RUNNING", 10, NOW.minusSeconds(60), "crashed-worker",
                NOW.minusNanos(1_000), NOW.minusSeconds(60), null);

        assertThat(service.leaseNext("replacement", LEASE)).isEmpty();

        JobRow row = job(id);
        assertThat(row.status()).isEqualTo("DEAD");
        assertThat(row.attempts()).isEqualTo(10);
        assertThat(row.lastErrorSummary()).isEqualTo("JOB_ATTEMPTS_EXHAUSTED");
        assertThat(row.leaseOwner()).isNull();
        assertThat(row.leaseUntil()).isNull();
        assertThat(countByKey(probeKey)).isOne();
        assertThat(handler.deadLetterTransactionActive()).isTrue();
        assertThat(handler.deadLetterCodes()).containsExactly("JOB_ATTEMPTS_EXHAUSTED");
    }

    @Test
    void concurrentExhaustedRecoveryCommitsTheDeadLetterHookExactlyOnce()
            throws Exception {
        String probeKey = key("concurrent-exhausted-probe");
        UUID id = insertJob(
                "concurrent-exhausted", "TEST",
                objectNode(Map.of("deadHook", "PROBE", "probeKey", probeKey)),
                "RUNNING", 10, NOW.minusSeconds(60), "crashed-worker",
                NOW.minusNanos(1_000), NOW.minusSeconds(60), null);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Optional<LeasedJob>>> futures = new ArrayList<>();
        try {
            for (String owner : List.of("recovery-a", "recovery-b")) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    await(start, "recovery workers did not start");
                    return service.leaseNext(owner, LEASE);
                }));
            }
            await(ready, "recovery workers were not ready");
            start.countDown();

            assertThat(futures.get(0).get(30, SECONDS)).isEmpty();
            assertThat(futures.get(1).get(30, SECONDS)).isEmpty();
            JobRow row = job(id);
            assertThat(row.status()).isEqualTo("DEAD");
            assertThat(row.attempts()).isEqualTo(10);
            assertThat(row.lastErrorSummary()).isEqualTo("JOB_ATTEMPTS_EXHAUSTED");
            assertThat(countByKey(probeKey)).isOne();
            assertThat(handler.deadLetterCodes()).containsExactly("JOB_ATTEMPTS_EXHAUSTED");
        } finally {
            start.countDown();
            stopExecutor(executor, futures);
        }
    }

    @Test
    void exhaustedAttemptHookFailureRollsBackTheDeadTransitionAndProbe() {
        String probeKey = key("exhausted-rollback-probe");
        UUID id = insertJob(
                "exhausted-rollback", "TEST",
                objectNode(Map.of("deadHook", "THROW", "probeKey", probeKey)),
                "RUNNING", 10, NOW.minusSeconds(60), "crashed-worker",
                NOW.minusNanos(1_000), NOW.minusSeconds(60), null);
        JobRow before = job(id);

        assertThatThrownBy(() -> service.leaseNext("replacement", LEASE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("job dead-letter hook failed")
                .hasNoCause();

        assertThat(job(id)).isEqualTo(before);
        assertThat(countByKey(probeKey)).isZero();
        assertThat(handler.deadLetterTransactionActive()).isTrue();
    }

    @Test
    void leasedPayloadIsDefensivelyCopiedOnConstructionAndAccess() {
        UUID id = track(service.enqueue(
                "TEST", key("defensive-json"), Map.of("value", 1)));
        LeasedJob leased = service.leaseNext("defensive-worker", LEASE).orElseThrow();
        assertThat(leased.id()).isEqualTo(id);

        JsonNode firstView = leased.payload();
        ((ObjectNode) firstView).put("value", 99);

        assertThat(leased.payload().path("value").asInt()).isOne();
        assertThat(job(id).payload().path("value").asInt()).isOne();
    }

    @Test
    void leasedJobAndHandlerRegistryEnforceTheirPackageContracts() {
        ObjectNode original = objectNode(Map.of("value", 1));
        LeasedJob leased = new LeasedJob(
                UUID.randomUUID(), "TEST", original, 1, "contract-worker");
        original.put("value", 99);
        assertThat(leased.payload().path("value").asInt()).isOne();

        ObjectNode validPayload = objectNode(Map.of());
        List<Runnable> invalidRows = List.of(
                () -> new LeasedJob(null, "TEST", validPayload, 1, "contract-worker"),
                () -> new LeasedJob(
                        UUID.randomUUID(), "test", validPayload, 1, "contract-worker"),
                () -> new LeasedJob(
                        UUID.randomUUID(), "TEST", objectMapper.valueToTree(List.of()),
                        1, "contract-worker"),
                () -> new LeasedJob(
                        UUID.randomUUID(), "TEST", validPayload, 0, "contract-worker"),
                () -> new LeasedJob(
                        UUID.randomUUID(), "TEST", validPayload, 11, "contract-worker"),
                () -> new LeasedJob(
                        UUID.randomUUID(), "TEST", validPayload, 1, "invalid owner"));
        for (Runnable invalidRow : invalidRows) {
            assertThatIllegalArgumentException()
                    .isThrownBy(invalidRow::run)
                    .withMessage("leased job is invalid")
                    .withNoCause();
        }

        assertThatThrownBy(() -> new JobHandlerRegistry(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("job handlers are required")
                .hasNoCause();
        List<JobHandler> nullHandler = new ArrayList<>();
        nullHandler.add(null);
        assertThatThrownBy(() -> new JobHandlerRegistry(nullHandler))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("job handler is invalid")
                .hasNoCause();
        assertThatThrownBy(() -> new JobHandlerRegistry(
                        List.of(jobHandler("private-invalid-type"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("job handler is invalid")
                .hasNoCause();
        assertThatThrownBy(() -> new JobHandlerRegistry(
                        List.of(handler, jobHandler("TEST"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("duplicate job handler")
                .hasNoCause();
    }

    @Test
    void leaseValidationRejectsUnsafeOwnersAndDurationsBeforeSql() {
        UUID id = track(service.enqueue(
                "TEST", key("lease-validation"), Map.of()));

        for (String invalidOwner : List.of(" ", " leading", "trailing ", "has space", "界",
                "w".repeat(121))) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> service.leaseNext(invalidOwner, LEASE))
                    .withMessage("worker id is invalid")
                    .withNoCause();
        }
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.leaseNext(null, LEASE))
                .withMessage("worker id is invalid")
                .withNoCause();
        for (Duration invalidDuration : List.of(
                Duration.ZERO,
                Duration.ofNanos(-1),
                Duration.ofHours(24).plusNanos(1))) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> service.leaseNext("valid-worker", invalidDuration))
                    .withMessage("lease duration is invalid")
                    .withNoCause();
        }
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.leaseNext("valid-worker", null))
                .withMessage("lease duration is invalid")
                .withNoCause();
        assertThat(job(id).status()).isEqualTo("PENDING");
    }

    @Test
    void clocksMustBeUtcAndInvalidInstantsOrLeaseOverflowNeverReachSql() {
        JobHandlerRegistry registry = new JobHandlerRegistry(List.of(handler));
        BackgroundJobMapper constructorMapper = mock(BackgroundJobMapper.class);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new BackgroundJobService(
                        constructorMapper, objectMapper, null, registry))
                .withMessage("job clock is required")
                .withNoCause();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new BackgroundJobService(
                        constructorMapper,
                        objectMapper,
                        Clock.fixed(NOW, ZoneOffset.ofHours(8)),
                        registry))
                .withMessage("job clock must use UTC")
                .withNoCause();
        verifyNoInteractions(constructorMapper);

        BackgroundJobMapper nullInstantMapper = mock(BackgroundJobMapper.class);
        Clock nullInstantClock = mock(Clock.class);
        when(nullInstantClock.getZone()).thenReturn(ZoneOffset.UTC);
        when(nullInstantClock.instant()).thenReturn(null);
        BackgroundJobService nullInstantService = new BackgroundJobService(
                nullInstantMapper, objectMapper, nullInstantClock, registry);

        assertThatThrownBy(() -> nullInstantService.enqueue(
                        "TEST", key("null-clock-instant"), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("JOB_CLOCK_FAILED")
                .hasNoCause();
        verifyNoInteractions(nullInstantMapper);

        OffsetDateTime lastUtcDateTime = OffsetDateTime.of(
                999_999_999, 12, 31, 23, 59, 59, 999_999_999, ZoneOffset.UTC);
        BackgroundJobMapper overflowMapper = mock(BackgroundJobMapper.class);
        BackgroundJobService overflowService = new BackgroundJobService(
                overflowMapper,
                objectMapper,
                Clock.fixed(lastUtcDateTime.toInstant(), ZoneOffset.UTC),
                registry);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> overflowService.leaseNext(
                        "overflow-worker", Duration.ofNanos(1)))
                .withMessage("lease duration is invalid")
                .withNoCause();
        verifyNoInteractions(overflowMapper);
    }

    @Test
    void persistenceFailuresAreRecreatedAsFixedCauseFreeServiceErrors() {
        JobHandlerRegistry registry = new JobHandlerRegistry(List.of(handler));

        BackgroundJobMapper enqueueMapper = mock(BackgroundJobMapper.class);
        when(enqueueMapper.insertIfAbsent(
                        any(), anyString(), anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("private enqueue detail"));
        BackgroundJobService enqueueService = new BackgroundJobService(
                enqueueMapper, objectMapper, Clock.fixed(NOW, ZoneOffset.UTC), registry);
        assertFixedFailure(
                () -> enqueueService.enqueue("TEST", key("mapper-enqueue"), Map.of()),
                "JOB_ENQUEUE_FAILED");

        BackgroundJobMapper leaseMapper = mock(BackgroundJobMapper.class);
        when(leaseMapper.deadLetterNextExhausted(any(), anyString()))
                .thenThrow(new IllegalStateException("private lease detail"));
        BackgroundJobService leaseService = new BackgroundJobService(
                leaseMapper, objectMapper, Clock.fixed(NOW, ZoneOffset.UTC), registry);
        assertFixedFailure(
                () -> leaseService.leaseNext("mapper-worker", LEASE),
                "JOB_LEASE_FAILED");

        BackgroundJobMapper completionMapper = mock(BackgroundJobMapper.class);
        when(completionMapper.succeed(any(), anyString(), anyInt()))
                .thenThrow(new IllegalStateException("private completion detail"));
        BackgroundJobService completionService = new BackgroundJobService(
                completionMapper, objectMapper, Clock.fixed(NOW, ZoneOffset.UTC), registry);
        assertFixedFailure(
                () -> completionService.succeed(UUID.randomUUID(), "mapper-worker", 1),
                "JOB_COMPLETION_FAILED");

        BackgroundJobMapper failureMapper = mock(BackgroundJobMapper.class);
        when(failureMapper.fail(any(), anyString(), anyInt(), anyString(), any()))
                .thenThrow(new IllegalStateException("private failure detail"));
        BackgroundJobService failureService = new BackgroundJobService(
                failureMapper, objectMapper, Clock.fixed(NOW, ZoneOffset.UTC), registry);
        assertFixedFailure(
                () -> failureService.fail(
                        UUID.randomUUID(), "mapper-worker", 1, "JOB_HANDLER_FAILED"),
                "JOB_COMPLETION_FAILED");
    }

    @Test
    void everyQueueStatementWorksWithAHostileSearchPath() {
        UUID exhaustedId = insertJob(
                "hostile-exhausted", "TEST", objectNode(Map.of()),
                "RUNNING", 10, NOW.minusSeconds(60), "crashed-worker",
                NOW.minusNanos(1_000), NOW.minusSeconds(60), null);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        HostilePathOutcome outcome = transaction.execute(status -> {
            jdbc.sql("set local search_path=pg_catalog").update();
            assertThat(service.leaseNext("hostile-recovery-worker", LEASE)).isEmpty();

            String retryKey = key("hostile-retry");
            UUID retryId = service.enqueue("TEST", retryKey, Map.of("value", 1));
            assertThat(service.enqueue("TEST", retryKey, Map.of("value", 1)))
                    .isEqualTo(retryId);
            LeasedJob retry = service.leaseNext("hostile-retry-worker", LEASE).orElseThrow();
            assertThat(retry.id()).isEqualTo(retryId);
            assertThat(service.fail(
                            retryId,
                            "hostile-retry-worker",
                            retry.attempts(),
                            "JOB_HANDLER_FAILED"))
                    .isTrue();

            UUID successId = service.enqueue(
                    "TEST", key("hostile-success"), Map.of("value", 2));
            LeasedJob success = service.leaseNext("hostile-success-worker", LEASE).orElseThrow();
            assertThat(success.id()).isEqualTo(successId);
            assertThat(service.succeed(
                            successId,
                            "hostile-success-worker",
                            success.attempts()))
                    .isTrue();
            return new HostilePathOutcome(retryId, successId);
        });

        assertThat(outcome).isNotNull();
        track(outcome.retryId());
        track(outcome.successId());
        assertThat(job(exhaustedId).status()).isEqualTo("DEAD");
        assertThat(job(exhaustedId).lastErrorSummary())
                .isEqualTo("JOB_ATTEMPTS_EXHAUSTED");
        assertThat(job(outcome.retryId()).status()).isEqualTo("FAILED");
        assertThat(job(outcome.successId()).status()).isEqualTo("SUCCEEDED");
    }

    private UUID insertJob(
            String suffix,
            String jobType,
            JsonNode payload,
            String status,
            int attempts,
            Instant nextRunAt,
            String leaseOwner,
            Instant leaseUntil,
            Instant createdAt,
            String lastErrorSummary) {
        UUID id = track(UUID.randomUUID());
        migratorJdbc().sql("""
                        insert into portfolio.background_job(
                            id, job_type, idempotency_key, payload, status, attempts,
                            next_run_at, lease_owner, lease_until, last_error_summary,
                            created_at, updated_at
                        ) values (
                            :id, :jobType, :key, cast(:payload as jsonb), :status, :attempts,
                            :nextRunAt, :leaseOwner, :leaseUntil, :lastErrorSummary,
                            :createdAt, :updatedAt
                        )
                        """)
                .param("id", id)
                .param("jobType", jobType)
                .param("key", key(suffix))
                .param("payload", payload.toString())
                .param("status", status)
                .param("attempts", attempts)
                .param("nextRunAt", utc(nextRunAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("leaseOwner", leaseOwner, Types.VARCHAR)
                .param("leaseUntil", nullableUtc(leaseUntil), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("lastErrorSummary", lastErrorSummary, Types.VARCHAR)
                .param("createdAt", utc(createdAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("updatedAt", utc(createdAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        return id;
    }

    private JobRow job(UUID id) {
        return jdbc.sql("""
                        select id, job_type, idempotency_key, payload::text, status, attempts,
                               next_run_at, lease_owner, lease_until, last_error_summary,
                               created_at, updated_at
                        from portfolio.background_job
                        where id=:id
                        """)
                .param("id", id)
                .query((resultSet, rowNumber) -> new JobRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("job_type"),
                        resultSet.getString("idempotency_key"),
                        readJson(resultSet.getString("payload")),
                        resultSet.getString("status"),
                        resultSet.getInt("attempts"),
                        instant(resultSet.getObject("next_run_at", OffsetDateTime.class)),
                        resultSet.getString("lease_owner"),
                        instant(resultSet.getObject("lease_until", OffsetDateTime.class)),
                        resultSet.getString("last_error_summary"),
                        instant(resultSet.getObject("created_at", OffsetDateTime.class)),
                        instant(resultSet.getObject("updated_at", OffsetDateTime.class))))
                .single();
    }

    private long countByKey(String idempotencyKey) {
        return jdbc.sql("""
                        select count(*) from portfolio.background_job
                        where idempotency_key=:key
                        """)
                .param("key", idempotencyKey)
                .query(Long.class)
                .single();
    }

    private String key(String suffix) {
        return keyPrefix + suffix;
    }

    private UUID track(UUID id) {
        trackedIds.add(id);
        return id;
    }

    private ObjectNode objectNode(Map<String, ?> value) {
        return objectMapper.valueToTree(value);
    }

    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            throw new IllegalStateException("test fixture JSON was invalid", exception);
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static OffsetDateTime nullableUtc(Instant instant) {
        return instant == null ? null : utc(instant);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static void await(CountDownLatch latch, String message) throws InterruptedException {
        if (!latch.await(10, SECONDS)) {
            throw new IllegalStateException(message);
        }
    }

    private static void assertFixedFailure(Runnable action, String message) {
        assertThatThrownBy(action::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(message)
                .hasNoCause();
    }

    private static JobHandler jobHandler(String jobType) {
        return new JobHandler() {
            @Override
            public String jobType() {
                return jobType;
            }

            @Override
            public void handle(JsonNode payload) {
                // Contract-only fixture.
            }
        };
    }

    private static void stopExecutor(
            ExecutorService executor, List<? extends Future<?>> futures) {
        for (Future<?> future : futures) {
            if (!future.isDone()) {
                future.cancel(true);
            }
        }
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(10, SECONDS)) {
                throw new IllegalStateException("test executor did not terminate");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test executor shutdown was interrupted");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class JobTestConfiguration {
        @Bean
        @Primary
        Clock fixedJobClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        RecordingJobHandler recordingJobHandler(JdbcClient jdbc) {
            return new RecordingJobHandler(jdbc);
        }

        @Bean
        SecondaryJobHandler secondaryJobHandler() {
            return new SecondaryJobHandler();
        }
    }

    static final class SecondaryJobHandler implements JobHandler {
        @Override
        public String jobType() {
            return "SECOND";
        }

        @Override
        public void handle(JsonNode payload) {
            // Registration-only fixture for idempotency type conflicts.
        }
    }

    static final class RecordingJobHandler implements JobHandler {
        private final JdbcClient jdbc;
        private final List<String> deadLetterCodes = new CopyOnWriteArrayList<>();
        private boolean deadLetterTransactionActive;

        private RecordingJobHandler(JdbcClient jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public String jobType() {
            return "TEST";
        }

        @Override
        public void handle(JsonNode payload) {
            // Service tests invoke only the dead-letter port.
        }

        @Override
        public void onDeadLetter(JsonNode payload, String safeSummaryCode) {
            deadLetterTransactionActive =
                    TransactionSynchronizationManager.isActualTransactionActive();
            deadLetterCodes.add(safeSummaryCode);
            String mode = payload.path("deadHook").asText("");
            if (mode.equals("PROBE") || mode.equals("THROW")) {
                String probeKey = payload.path("probeKey").asText();
                jdbc.sql("""
                                insert into portfolio.background_job(
                                    id, job_type, idempotency_key, payload, status, attempts,
                                    next_run_at, created_at, updated_at
                                ) values (
                                    :id, 'TEST', :key, '{}'::jsonb, 'PENDING', 0,
                                    :nextRunAt, :createdAt, :updatedAt
                                )
                                """)
                        .param("id", UUID.randomUUID())
                        .param("key", probeKey)
                        .param("nextRunAt", utc(NOW.plus(Duration.ofDays(1))),
                                Types.TIMESTAMP_WITH_TIMEZONE)
                        .param("createdAt", utc(NOW), Types.TIMESTAMP_WITH_TIMEZONE)
                        .param("updatedAt", utc(NOW), Types.TIMESTAMP_WITH_TIMEZONE)
                        .update();
            }
            if (mode.equals("THROW")) {
                throw new SyntheticHookException("private hook detail");
            }
        }

        List<String> deadLetterCodes() {
            return List.copyOf(deadLetterCodes);
        }

        boolean deadLetterTransactionActive() {
            return deadLetterTransactionActive;
        }

        void reset() {
            deadLetterCodes.clear();
            deadLetterTransactionActive = false;
        }

        void release() {
            // Symmetric with the worker test fixture.
        }
    }

    private static final class SyntheticHookException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private SyntheticHookException(String message) {
            super(message);
        }
    }

    private record JobRow(
            UUID id,
            String jobType,
            String idempotencyKey,
            JsonNode payload,
            String status,
            int attempts,
            Instant nextRunAt,
            String leaseOwner,
            Instant leaseUntil,
            String lastErrorSummary,
            Instant createdAt,
            Instant updatedAt) {}

    private record EnqueueOutcome(UUID id, String errorCode) {}

    private record HostilePathOutcome(UUID retryId, UUID successId) {}
}
