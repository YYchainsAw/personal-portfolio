package xyz.yychainsaw.portfolio.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import xyz.yychainsaw.portfolio.analytics.domain.AnalyticsEventType;
import xyz.yychainsaw.portfolio.analytics.domain.DeviceClass;
import xyz.yychainsaw.portfolio.analytics.persistence.AnalyticsEventMapper;
import xyz.yychainsaw.portfolio.analytics.persistence.AnalyticsEventRecord;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
@Isolated
class AnalyticsDeduplicationIntegrationTest extends PostgresIntegrationTestBase {
    private static final ZoneId SITE_ZONE = ZoneId.of("Asia/Hong_Kong");
    private static final Instant BASE = Instant.parse("2026-07-14T12:00:00Z");
    private static final String VISITOR_KEY = "1".repeat(64);
    private static final String SESSION_KEY = "2".repeat(64);

    @Autowired AnalyticsEventDeduplicator deduplicator;
    @Autowired AnalyticsEventMapper mapper;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clearBefore() {
        mapper.deleteAll();
    }

    @AfterEach
    void clearAfter() {
        mapper.deleteAll();
    }

    @Test
    void concurrentSameTuplePersistsExactlyOneEvent() throws Exception {
        AnalyticsEventRecord first = event(UUID.randomUUID(), UUID.randomUUID(), BASE, "HOME");
        AnalyticsEventRecord second = event(UUID.randomUUID(), UUID.randomUUID(), BASE, "HOME");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> left = executor.submit(() -> persistTogether(first, ready, start));
            Future<Boolean> right = executor.submit(() -> persistTogether(second, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(Set.of(left.get(10, TimeUnit.SECONDS), right.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(false, true);
            assertThat(mapper.count()).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void duplicateWindowHasStableSubSecondBoundaries() {
        assertThat(deduplicator.persist(event(
                UUID.randomUUID(), UUID.randomUUID(), BASE, "HOME"))).isTrue();
        assertThat(deduplicator.persist(event(
                UUID.randomUUID(), UUID.randomUUID(),
                BASE.plus(9_999_999, ChronoUnit.MICROS), "HOME"))).isFalse();
        assertThat(deduplicator.persist(event(
                UUID.randomUUID(), UUID.randomUUID(),
                BASE.plus(10_000_001, ChronoUnit.MICROS), "HOME"))).isTrue();

        assertThat(mapper.count()).isEqualTo(2);
        assertThat(mapper.findAll())
                .extracting(AnalyticsEventRecord::receivedAt)
                .containsExactly(BASE, BASE.plus(10_000_001, ChronoUnit.MICROS));
    }

    @Test
    void exactTenSecondBoundaryIsStillSuppressed() {
        assertThat(deduplicator.persist(event(
                UUID.randomUUID(), UUID.randomUUID(), BASE, "HOME"))).isTrue();
        assertThat(deduplicator.persist(event(
                UUID.randomUUID(), UUID.randomUUID(), BASE.plusSeconds(10), "HOME")))
                .isFalse();

        assertThat(mapper.count()).isEqualTo(1);
    }

    @Test
    void clientEventIdMakesNetworkRetriesIdempotentAcrossTuples() {
        UUID clientEventId = UUID.randomUUID();
        assertThat(deduplicator.persist(event(
                UUID.randomUUID(), clientEventId, BASE, "HOME"))).isTrue();
        assertThat(deduplicator.persist(event(
                UUID.randomUUID(), clientEventId, BASE.plusSeconds(30), "WORK"))).isFalse();

        assertThat(mapper.count()).isEqualTo(1);
    }

    @Test
    void differentTuplesWithinTheWindowAreNotSuppressed() {
        assertThat(deduplicator.persist(event(
                UUID.randomUUID(), UUID.randomUUID(), BASE, "HOME"))).isTrue();
        assertThat(deduplicator.persist(event(
                UUID.randomUUID(), UUID.randomUUID(), BASE, "WORK"))).isTrue();

        assertThat(mapper.count()).isEqualTo(2);
    }

    @Test
    void concurrentProjectTupleAlsoPersistsExactlyOneEvent() throws Exception {
        UUID projectId = UUID.fromString("30000000-0000-4000-8000-000000000001");
        AnalyticsEventRecord first = event(
                UUID.randomUUID(), UUID.randomUUID(), BASE, "PROJECT_DETAIL", projectId);
        AnalyticsEventRecord second = event(
                UUID.randomUUID(), UUID.randomUUID(), BASE, "PROJECT_DETAIL", projectId);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> left = executor.submit(() -> persistTogether(first, ready, start));
            Future<Boolean> right = executor.submit(() -> persistTogether(second, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(Set.of(left.get(10, TimeUnit.SECONDS), right.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(false, true);
            assertThat(mapper.count()).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void persistenceShapeContainsNoRawRequestIdentityColumns() {
        assertThat(deduplicator.persist(event(
                UUID.randomUUID(), UUID.randomUUID(), BASE, "HOME"))).isTrue();

        List<String> columns = jdbc.sql("""
                        select column_name
                        from information_schema.columns
                        where table_schema='portfolio' and table_name='analytics_event'
                        order by ordinal_position
                        """)
                .query(String.class)
                .list();
        assertThat(columns)
                .doesNotContain(
                        "ip", "ip_hash", "user_agent", "visitor_id", "session_id",
                        "referrer", "request_headers", "query_string")
                .contains(
                        "visitor_day_key", "session_day_key", "referrer_domain",
                        "device_class", "rules_version");
        AnalyticsEventRecord row = mapper.findAll().get(0);
        assertThat(row.toString())
                .doesNotContain(VISITOR_KEY, SESSION_KEY)
                .contains("<redacted>");
    }

    @Test
    void mapperRefusesAdvisoryLockOutsideTransaction() {
        assertThatThrownBy(() -> mapper.acquireDedupeLock(123L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transaction");
    }

    private boolean persistTogether(
            AnalyticsEventRecord event, CountDownLatch ready, CountDownLatch start)
            throws Exception {
        ready.countDown();
        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
        return deduplicator.persist(event);
    }

    private static AnalyticsEventRecord event(
            UUID id, UUID clientEventId, Instant receivedAt, String pageKey) {
        return event(id, clientEventId, receivedAt, pageKey, null);
    }

    private static AnalyticsEventRecord event(
            UUID id,
            UUID clientEventId,
            Instant receivedAt,
            String pageKey,
            UUID projectId) {
        return new AnalyticsEventRecord(
                id,
                clientEventId,
                receivedAt.atZone(SITE_ZONE).toLocalDate(),
                receivedAt,
                VISITOR_KEY,
                SESSION_KEY,
                AnalyticsEventType.PAGE_VIEW,
                pageKey,
                projectId,
                "(direct)",
                DeviceClass.DESKTOP,
                LocaleCode.EN,
                "analytics-rules-v1",
                receivedAt);
    }
}
