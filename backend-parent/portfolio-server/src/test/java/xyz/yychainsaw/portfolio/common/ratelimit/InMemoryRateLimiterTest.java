package xyz.yychainsaw.portfolio.common.ratelimit;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;
import xyz.yychainsaw.portfolio.common.ratelimit.RateLimitProperties.Policy;

class InMemoryRateLimiterTest {
    private static final String POLICY = "admin-login";
    private static final Instant WINDOW_START = Instant.parse("2026-07-14T12:00:00Z");

    @Test
    void deniesAtTheLimitAndRollsOverExactlyAtTheConfiguredBoundary() {
        MutableClock clock = new MutableClock(WINDOW_START);
        InMemoryRateLimiter limiter = limiter(10_000, 2, Duration.ofMinutes(1), clock);

        assertThat(limiter.consume(POLICY, "sha256-subject")).isEqualTo(RateLimitDecision.allow());
        assertThat(limiter.consume(POLICY, "sha256-subject")).isEqualTo(RateLimitDecision.allow());
        assertThat(limiter.consume(POLICY, "sha256-subject"))
                .isEqualTo(RateLimitDecision.deny(60));

        clock.advance(Duration.ofSeconds(59));
        assertThat(limiter.consume(POLICY, "sha256-subject"))
                .isEqualTo(RateLimitDecision.deny(1));

        clock.advance(Duration.ofSeconds(1));
        assertThat(limiter.consume(POLICY, "sha256-subject")).isEqualTo(RateLimitDecision.allow());
    }

    @Test
    void failsClosedAtCapacityWithoutEvictingAnActiveSubject() {
        MutableClock clock = new MutableClock(WINDOW_START);
        InMemoryRateLimiter limiter = limiter(1, 2, Duration.ofMinutes(1), clock);

        assertThat(limiter.consume(POLICY, "subject-a")).isEqualTo(RateLimitDecision.allow());
        assertThat(limiter.consume(POLICY, "subject-b")).isEqualTo(RateLimitDecision.deny(60));
        assertThat(limiter.consume(POLICY, "subject-a")).isEqualTo(RateLimitDecision.allow());
        assertThat(limiter.consume(POLICY, "subject-b")).isEqualTo(RateLimitDecision.deny(60));
        assertThat(limiter.consume(POLICY, "subject-a")).isEqualTo(RateLimitDecision.deny(60));

        clock.advance(Duration.ofSeconds(59));
        assertThat(limiter.consume(POLICY, "subject-b")).isEqualTo(RateLimitDecision.deny(1));

        clock.advance(Duration.ofSeconds(1));
        assertThat(limiter.consume(POLICY, "subject-b")).isEqualTo(RateLimitDecision.allow());
        assertThat(limiter.consume(POLICY, "subject-a")).isEqualTo(RateLimitDecision.deny(60));
    }

    @Test
    void clockRollbackAfterCleanupCannotGrantTheSameEffectiveWindowTwice() {
        MutableClock clock = new MutableClock(WINDOW_START.plusSeconds(59));
        InMemoryRateLimiter limiter = limiter(2, 1, Duration.ofMinutes(1), clock);

        assertThat(limiter.consume(POLICY, "subject-a")).isEqualTo(RateLimitDecision.allow());
        assertThat(limiter.consume(POLICY, "subject-b")).isEqualTo(RateLimitDecision.allow());

        clock.advance(Duration.ofSeconds(1));
        assertThat(limiter.consume(POLICY, "subject-c")).isEqualTo(RateLimitDecision.allow());

        clock.advance(Duration.ofSeconds(-1));
        assertThat(limiter.consume(POLICY, "subject-a")).isEqualTo(RateLimitDecision.allow());

        clock.advance(Duration.ofSeconds(1));
        assertThat(limiter.consume(POLICY, "subject-a"))
                .isEqualTo(RateLimitDecision.deny(60));
    }

    @Test
    void rejectsMissingBlankAndUnknownPoliciesAndSubjects() {
        InMemoryRateLimiter limiter = limiter(
                10, 1, Duration.ofMinutes(1), new MutableClock(WINDOW_START));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> limiter.consume(null, "subject"))
                .withMessageContaining("policy");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> limiter.consume("   ", "subject"))
                .withMessageContaining("policy");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> limiter.consume("unknown", "subject"))
                .withMessageContaining("unknown rate-limit policy")
                .withMessageContaining("unknown");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> limiter.consume(POLICY, null))
                .withMessageContaining("subject");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> limiter.consume(POLICY, "   "))
                .withMessageContaining("subject");
    }

    @Test
    void rejectsInvalidPropertyCollectionsAndDefensivelyCopiesValidOnes() {
        Policy valid = new Policy(1, Duration.ofSeconds(1));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RateLimitProperties(0, Map.of(POLICY, valid)))
                .withMessageContaining("maximumSubjects");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RateLimitProperties(1, null))
                .withMessageContaining("policies");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RateLimitProperties(1, Map.of()))
                .withMessageContaining("policies");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RateLimitProperties(1, Map.of("   ", valid)))
                .withMessageContaining("policy name");

        Map<String, Policy> nullName = new HashMap<>();
        nullName.put(null, valid);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RateLimitProperties(1, nullName))
                .withMessageContaining("policy name");

        Map<String, Policy> nullPolicy = new HashMap<>();
        nullPolicy.put(POLICY, null);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RateLimitProperties(1, nullPolicy))
                .withMessageContaining("policy")
                .withMessageContaining(POLICY);

        Map<String, Policy> source = new HashMap<>();
        source.put(POLICY, valid);
        RateLimitProperties properties = new RateLimitProperties(1, source);
        source.clear();
        assertThat(properties.policies()).containsOnlyKeys(POLICY);
        assertThatThrownBy(() -> properties.policies().put("other", valid))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsLimitsAndWindowsThatCannotFormAUsablePolicy() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Policy(0, Duration.ofSeconds(1)))
                .withMessageContaining("limit");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Policy(1, null))
                .withMessageContaining("window");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Policy(1, Duration.ZERO))
                .withMessageContaining("window");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Policy(1, Duration.ofSeconds(-1)))
                .withMessageContaining("window");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Policy(1, Duration.ofMillis(999)))
                .withMessageContaining("window")
                .withMessageContaining("one second");
    }

    @Test
    void concurrentConsumersCannotOversubscribeOneSubjectLimit() throws Exception {
        InMemoryRateLimiter limiter = limiter(
                100, 10, Duration.ofMinutes(1), new MutableClock(WINDOW_START));

        List<RateLimitDecision> decisions = consumeConcurrently(limiter, 64, ignored -> "same-subject");

        assertThat(decisions).filteredOn(RateLimitDecision::allowed).hasSize(10);
        assertThat(decisions).filteredOn(decision -> !decision.allowed()).hasSize(54);
    }

    @Test
    void concurrentNewSubjectsCannotExceedTheConfiguredCapacity() throws Exception {
        InMemoryRateLimiter limiter = limiter(
                1, 10, Duration.ofMinutes(1), new MutableClock(WINDOW_START));

        List<RateLimitDecision> decisions = consumeConcurrently(
                limiter, 32, attempt -> "subject-" + attempt);

        assertThat(decisions).filteredOn(RateLimitDecision::allowed).hasSize(1);
        assertThat(decisions).filteredOn(decision -> !decision.allowed()).hasSize(31);
        assertThat(decisions)
                .filteredOn(decision -> !decision.allowed())
                .allSatisfy(decision -> assertThat(decision.retryAfterSeconds()).isPositive());
    }

    @Test
    void denialFactoriesAlwaysExposeAPositiveRetryDelay() {
        assertThat(RateLimitDecision.deny(0))
                .isEqualTo(new RateLimitDecision(false, 1));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RateLimitDecision(false, -1))
                .withMessageContaining("retryAfterSeconds");
    }

    private static InMemoryRateLimiter limiter(
            int maximumSubjects, int limit, Duration window, Clock clock) {
        RateLimitProperties properties = new RateLimitProperties(
                maximumSubjects, Map.of(POLICY, new Policy(limit, window)));
        return new InMemoryRateLimiter(properties, clock);
    }

    private static List<RateLimitDecision> consumeConcurrently(
            InMemoryRateLimiter limiter, int attempts, IntFunction<String> subject) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(16, attempts));
        CountDownLatch start = new CountDownLatch(1);
        List<Future<RateLimitDecision>> futures = new ArrayList<>();
        try {
            for (int attempt = 0; attempt < attempts; attempt++) {
                int index = attempt;
                futures.add(executor.submit(() -> {
                    start.await();
                    return limiter.consume(POLICY, subject.apply(index));
                }));
            }
            start.countDown();

            List<RateLimitDecision> decisions = new ArrayList<>();
            for (Future<RateLimitDecision> future : futures) {
                decisions.add(future.get(5, SECONDS));
            }
            return decisions;
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, SECONDS)).isTrue();
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
