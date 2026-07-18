package xyz.yychainsaw.portfolio.message.email;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.yychainsaw.portfolio.message.persistence.ContactMessageMapper;
import xyz.yychainsaw.portfolio.message.persistence.ContactMessageRecord;
import xyz.yychainsaw.portfolio.message.persistence.EmailOutboxMapper;
import xyz.yychainsaw.portfolio.message.persistence.EmailOutboxRecord;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest(properties = {
        "portfolio.email.enabled=false",
        "management.health.mail.enabled=false",
        "portfolio.jobs.worker-enabled=false"
})
@Isolated
class EmailOutboxLeaseIntegrationTest extends PostgresIntegrationTestBase {
    private static final Instant NOW =
            Instant.parse("2026-07-18T08:00:00.123456Z");
    private static final Duration LEASE_DURATION = Duration.ofMinutes(2);
    private static final String TEMPLATE = "contact-notification-v1";
    private static final String RECIPIENT = "owner@yychainsaw.xyz";

    @Autowired EmailOutboxRepository repository;
    @Autowired ContactMessageMapper contacts;
    @Autowired EmailOutboxMapper outbox;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired HealthContributorRegistry healthContributors;

    @BeforeEach
    @AfterEach
    void clearContactData() {
        JdbcClient owner = migratorJdbc();
        owner.sql("delete from portfolio.email_outbox").update();
        owner.sql("delete from portfolio.contact_message").update();
    }

    @Test
    void readyBoundaryIsInclusiveToTheMicrosecondAndFutureRowsStayPending() {
        Seed before = insertOutbox(
                "PENDING", 0, NOW.minusNanos(1_000), NOW.minusSeconds(3),
                null, null, null, null);
        Seed equal = insertOutbox(
                "FAILED", 2, NOW, NOW.minusSeconds(2),
                null, null, "SMTP_TRANSIENT", null);
        Seed after = insertOutbox(
                "PENDING", 0, NOW.plusNanos(1_000), NOW.minusSeconds(1),
                null, null, null, null);

        List<LeasedEmail> leased = repository.recoverExpiredAndClaim(
                "boundary-claim", NOW, LEASE_DURATION, 10);

        assertThat(ids(leased)).containsExactly(before.outboxId(), equal.outboxId());
        assertThat(state(before.outboxId()).status()).isEqualTo("SENDING");
        assertThat(state(equal.outboxId()).attempts()).isEqualTo(3);
        assertThat(state(after.outboxId()))
                .extracting(OutboxState::status, OutboxState::attempts)
                .containsExactly("PENDING", 0);
    }

    @Test
    void disabledDeliveryDoesNotRegisterAnSmtpHealthProbe() {
        assertThat(healthContributors.getContributor("mail")).isNull();
    }

    @Test
    void leaseExpirationMustBeStrictlyBeforeNowToRecover() {
        Seed expired = insertOutbox(
                "SENDING", 3, NOW.minusSeconds(20), NOW.minusSeconds(4),
                "crashed-claim", NOW.minusNanos(1_000), null, null);
        Seed equal = insertOutbox(
                "SENDING", 4, NOW.minusSeconds(20), NOW.minusSeconds(3),
                "still-owned-claim", NOW, null, null);

        List<LeasedEmail> leased = repository.recoverExpiredAndClaim(
                "recovery-claim", NOW, LEASE_DURATION, 10);

        assertThat(ids(leased)).containsExactly(expired.outboxId());
        assertThat(leased.get(0))
                .extracting(LeasedEmail::leaseOwner, LeasedEmail::attempts)
                .containsExactly("recovery-claim", 4);
        assertThat(state(expired.outboxId()))
                .extracting(
                        OutboxState::status,
                        OutboxState::attempts,
                        OutboxState::leaseOwner,
                        OutboxState::leaseUntil)
                .containsExactly(
                        "SENDING", 4, "recovery-claim", NOW.plus(LEASE_DURATION));
        assertThat(state(equal.outboxId()))
                .extracting(
                        OutboxState::status,
                        OutboxState::attempts,
                        OutboxState::leaseOwner,
                        OutboxState::leaseUntil)
                .containsExactly("SENDING", 4, "still-owned-claim", NOW);
    }

    @Test
    void terminalAndUnexpiredSendingRowsAreNeverClaimed() {
        Seed sent = insertOutbox(
                "SENT", 1, NOW.minusSeconds(10), NOW.minusSeconds(5),
                null, null, null, NOW.minusSeconds(1));
        Seed dead = insertOutbox(
                "DEAD", 10, NOW.minusSeconds(10), NOW.minusSeconds(4),
                null, null, "RETRY_EXHAUSTED", null);
        Seed canceled = insertOutbox(
                "CANCELED", 0, NOW.minusSeconds(10), NOW.minusSeconds(3),
                null, null, null, null);
        Seed owned = insertOutbox(
                "SENDING", 2, NOW.minusSeconds(10), NOW.minusSeconds(2),
                "active-claim", NOW.plusNanos(1_000), null, null);

        assertThat(repository.recoverExpiredAndClaim(
                        "terminal-claim", NOW, LEASE_DURATION, 10))
                .isEmpty();
        assertThat(state(sent.outboxId()).status()).isEqualTo("SENT");
        assertThat(state(dead.outboxId()).status()).isEqualTo("DEAD");
        assertThat(state(canceled.outboxId()).status()).isEqualTo("CANCELED");
        assertThat(state(owned.outboxId()).leaseOwner()).isEqualTo("active-claim");
    }

    @Test
    void claimIncrementsAttemptsOnceAndPreservesEveryNotificationField() {
        Seed seed = insertOutbox(
                "FAILED", 4, NOW.minusSeconds(1), NOW.minusSeconds(30),
                null, null, "SMTP_TRANSIENT", null);

        LeasedEmail leased = repository.recoverExpiredAndClaim(
                        "field-stability-claim", NOW, LEASE_DURATION, 1)
                .get(0);

        assertThat(leased.leaseOwner()).isEqualTo("field-stability-claim");
        assertThat(leased.attempts()).isEqualTo(5);
        assertThat(leased.templateName()).isEqualTo(TEMPLATE);
        assertThat(leased.notification())
                .extracting(
                        ContactNotification::outboxId,
                        ContactNotification::stableMessageId,
                        ContactNotification::to,
                        ContactNotification::replyTo,
                        ContactNotification::visitorName,
                        ContactNotification::subject,
                        ContactNotification::body,
                        ContactNotification::receivedAt)
                .containsExactly(
                        seed.outboxId(),
                        seed.stableMessageId(),
                        RECIPIENT,
                        seed.visitorEmail(),
                        seed.visitorName(),
                        seed.subject(),
                        seed.body(),
                        seed.createdAt());
        assertThat(state(seed.outboxId()))
                .extracting(
                        OutboxState::status,
                        OutboxState::attempts,
                        OutboxState::templateName,
                        OutboxState::toAddress,
                        OutboxState::stableMessageId)
                .containsExactly(
                        "SENDING", 5, TEMPLATE, RECIPIENT, seed.stableMessageId());
    }

    @Test
    void claimsUseReadyOrderAndHonorTheBatchLimit() {
        Seed third = insertOutbox(
                "PENDING", 0, NOW.minusSeconds(1), NOW.minusSeconds(30),
                null, null, null, null);
        Seed first = insertOutbox(
                "PENDING", 0, NOW.minusSeconds(3), NOW.minusSeconds(10),
                null, null, null, null);
        Seed fourth = insertOutbox(
                "FAILED", 1, NOW.minusSeconds(1), NOW.minusSeconds(20),
                null, null, "SMTP_TRANSIENT", null);
        Seed second = insertOutbox(
                "FAILED", 2, NOW.minusSeconds(2), NOW.minusSeconds(40),
                null, null, "SMTP_TRANSIENT", null);

        List<LeasedEmail> firstBatch = repository.recoverExpiredAndClaim(
                "batch-one", NOW, LEASE_DURATION, 2);
        List<LeasedEmail> secondBatch = repository.recoverExpiredAndClaim(
                "batch-two", NOW, LEASE_DURATION, 2);

        assertThat(ids(firstBatch)).containsExactly(first.outboxId(), second.outboxId());
        assertThat(ids(secondBatch)).containsExactly(third.outboxId(), fourth.outboxId());
        assertThat(firstBatch).hasSize(2);
        assertThat(secondBatch).hasSize(2);
    }

    @Test
    void concurrentClaimersReceiveDisjointCompleteBatches() throws Exception {
        List<UUID> expected = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            expected.add(insertOutbox(
                            "PENDING",
                            0,
                            NOW.minusSeconds(10L - index),
                            NOW.minusSeconds(30L - index),
                            null,
                            null,
                            null,
                            null)
                    .outboxId());
        }
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<List<LeasedEmail>> first = executor.submit(() -> claimAfterGate(
                    "concurrent-a", ready, start, 4));
            Future<List<LeasedEmail>> second = executor.submit(() -> claimAfterGate(
                    "concurrent-b", ready, start, 4));
            assertThat(ready.await(10, SECONDS)).isTrue();
            start.countDown();

            Set<UUID> firstIds = new HashSet<>(ids(first.get(10, SECONDS)));
            Set<UUID> secondIds = new HashSet<>(ids(second.get(10, SECONDS)));
            Set<UUID> union = new HashSet<>(firstIds);
            union.addAll(secondIds);

            assertThat(firstIds).hasSize(4);
            assertThat(secondIds).hasSize(4);
            assertThat(firstIds).doesNotContainAnyElementsOf(secondIds);
            assertThat(union).containsExactlyInAnyOrderElementsOf(expected);
            assertThat(expected)
                    .allSatisfy(id -> assertThat(state(id).attempts()).isOne());
        } finally {
            start.countDown();
            shutdown(executor);
        }
    }

    @Test
    void lockedHeadRowIsSkippedAndTheNextRowIsClaimedWithoutWaiting()
            throws Exception {
        Seed head = insertOutbox(
                "PENDING", 0, NOW.minusSeconds(2), NOW.minusSeconds(10),
                null, null, null, null);
        Seed next = insertOutbox(
                "PENDING", 0, NOW.minusSeconds(1), NOW.minusSeconds(9),
                null, null, null, null);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> holder = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    assertThat(jdbc.sql("""
                                    select id
                                    from portfolio.email_outbox
                                    where id=:id
                                    for update
                                    """)
                            .param("id", head.outboxId(), Types.OTHER)
                            .query(UUID.class)
                            .single()).isEqualTo(head.outboxId());
                    locked.countDown();
                    await(release, "locked row was not released");
                });
            });
            assertThat(locked.await(10, SECONDS)).isTrue();

            Future<List<LeasedEmail>> claim = executor.submit(() ->
                    repository.recoverExpiredAndClaim(
                            "skip-locked-claim", NOW, LEASE_DURATION, 1));

            assertThat(ids(claim.get(2, SECONDS))).containsExactly(next.outboxId());
            assertThat(state(head.outboxId()).status()).isEqualTo("PENDING");
            release.countDown();
            holder.get(10, SECONDS);
        } finally {
            release.countDown();
            shutdown(executor);
        }
    }

    @Test
    void oldOwnerAndAttemptCannotRenewOrCompleteAReclaimedLease() {
        Seed seed = insertOutbox(
                "SENDING", 1, NOW.minusSeconds(30), NOW.minusSeconds(40),
                "old-claim", NOW.minusNanos(1_000), null, null);
        LeasedEmail reclaimed = repository.recoverExpiredAndClaim(
                        "new-claim", NOW, LEASE_DURATION, 1)
                .get(0);
        assertThat(reclaimed.attempts()).isEqualTo(2);

        repository.renewLease(
                seed.outboxId(), "old-claim", 1, NOW.plus(Duration.ofHours(1)));
        repository.markSent(seed.outboxId(), "old-claim", 1, NOW.plusSeconds(1));
        repository.markFailed(
                seed.outboxId(),
                "old-claim",
                1,
                NOW.plusSeconds(30),
                "OldFailure|STALE_LEASE");
        repository.markFailed(
                seed.outboxId(),
                "new-claim",
                1,
                NOW.plusSeconds(30),
                "WrongAttempt|STALE_LEASE");

        assertThat(state(seed.outboxId()))
                .extracting(
                        OutboxState::status,
                        OutboxState::attempts,
                        OutboxState::leaseOwner,
                        OutboxState::leaseUntil,
                        OutboxState::lastErrorSummary,
                        OutboxState::sentAt)
                .containsExactly(
                        "SENDING",
                        2,
                        "new-claim",
                        NOW.plus(LEASE_DURATION),
                        null,
                        null);
    }

    @Test
    void separateClaimTokensFenceSequentialClaimsFromTheSameInstance() {
        Seed seed = insertOutbox(
                "PENDING", 0, NOW.minusSeconds(1), NOW.minusSeconds(10),
                null, null, null, null);
        LeasedEmail first = repository.recoverExpiredAndClaim(
                        "instance-a:claim-1", NOW, LEASE_DURATION, 1)
                .get(0);
        setLeaseUntil(seed.outboxId(), NOW.minusNanos(1_000));

        LeasedEmail second = repository.recoverExpiredAndClaim(
                        "instance-a:claim-2", NOW, LEASE_DURATION, 1)
                .get(0);
        repository.markSent(
                seed.outboxId(), first.leaseOwner(), first.attempts(), NOW.plusSeconds(1));

        assertThat(first.leaseOwner()).isNotEqualTo(second.leaseOwner());
        assertThat(second.attempts()).isEqualTo(2);
        assertThat(state(seed.outboxId()))
                .extracting(
                        OutboxState::status,
                        OutboxState::attempts,
                        OutboxState::leaseOwner,
                        OutboxState::sentAt)
                .containsExactly("SENDING", 2, "instance-a:claim-2", null);
    }

    @Test
    void matchingLeaseCanRenewAndThenMarkSent() {
        Seed seed = insertOutbox(
                "PENDING", 0, NOW.minusSeconds(1), NOW.minusSeconds(10),
                null, null, null, null);
        LeasedEmail leased = repository.recoverExpiredAndClaim(
                        "success-claim", NOW, LEASE_DURATION, 1)
                .get(0);
        Instant renewedUntil = NOW.plus(Duration.ofMinutes(5));
        Instant sentAt = NOW.plusSeconds(4).plusNanos(1_000);

        repository.renewLease(
                seed.outboxId(), leased.leaseOwner(), leased.attempts(), renewedUntil);
        assertThat(state(seed.outboxId()).leaseUntil()).isEqualTo(renewedUntil);
        repository.markSent(
                seed.outboxId(), leased.leaseOwner(), leased.attempts(), sentAt);

        assertThat(state(seed.outboxId()))
                .extracting(
                        OutboxState::status,
                        OutboxState::attempts,
                        OutboxState::leaseOwner,
                        OutboxState::leaseUntil,
                        OutboxState::sentAt,
                        OutboxState::stableMessageId)
                .containsExactly(
                        "SENT", 1, null, null, sentAt, seed.stableMessageId());
        assertThat(repository.recoverExpiredAndClaim(
                        "never-resend", renewedUntil.plusSeconds(1), LEASE_DURATION, 1))
                .isEmpty();
    }

    @Test
    void matchingLeaseCanFailAndBecomesReadyAtTheRequestedMicrosecond() {
        Seed seed = insertOutbox(
                "PENDING", 0, NOW.minusSeconds(1), NOW.minusSeconds(10),
                null, null, null, null);
        LeasedEmail leased = repository.recoverExpiredAndClaim(
                        "failure-claim", NOW, LEASE_DURATION, 1)
                .get(0);
        Instant retryAt = NOW.plusSeconds(30).plusNanos(1_000);

        repository.markFailed(
                seed.outboxId(),
                leased.leaseOwner(),
                leased.attempts(),
                retryAt,
                "MailSendException|SMTP_TRANSIENT");

        assertThat(state(seed.outboxId()))
                .extracting(
                        OutboxState::status,
                        OutboxState::attempts,
                        OutboxState::nextAttemptAt,
                        OutboxState::leaseOwner,
                        OutboxState::leaseUntil,
                        OutboxState::lastErrorSummary,
                        OutboxState::sentAt)
                .containsExactly(
                        "FAILED",
                        1,
                        retryAt,
                        null,
                        null,
                        "MailSendException|SMTP_TRANSIENT",
                        null);
        assertThat(repository.recoverExpiredAndClaim(
                        "too-early", retryAt.minusNanos(1_000), LEASE_DURATION, 1))
                .isEmpty();
        assertThat(repository.recoverExpiredAndClaim(
                        "retry-claim", retryAt, LEASE_DURATION, 1))
                .singleElement()
                .satisfies(retry -> {
                    assertThat(retry.notification().outboxId()).isEqualTo(seed.outboxId());
                    assertThat(retry.attempts()).isEqualTo(2);
                });
    }

    @Test
    void tenthFailureMovesTheLeasedNotificationToDeadAndItCannotBeClaimedAgain() {
        Seed seed = insertOutbox(
                "SENDING",
                10,
                NOW.minusSeconds(1),
                NOW.minusSeconds(10),
                "tenth-attempt-claim",
                NOW.plus(LEASE_DURATION),
                null,
                null);

        assertThat(repository.markFailed(
                        seed.outboxId(),
                        "tenth-attempt-claim",
                        10,
                        NOW,
                        "MailSendException|SMTP_DELIVERY_FAILED"))
                .isTrue();

        assertThat(state(seed.outboxId()))
                .extracting(
                        OutboxState::status,
                        OutboxState::attempts,
                        OutboxState::nextAttemptAt,
                        OutboxState::leaseOwner,
                        OutboxState::leaseUntil,
                        OutboxState::lastErrorSummary,
                        OutboxState::sentAt)
                .containsExactly(
                        "DEAD",
                        10,
                        NOW,
                        null,
                        null,
                        "MailSendException|SMTP_DELIVERY_FAILED",
                        null);
        assertThat(repository.recoverExpiredAndClaim(
                        "dead-row-claim", NOW.plus(Duration.ofDays(1)), LEASE_DURATION, 1))
                .isEmpty();
    }

    @Test
    void manuallyRetriedDeadNotificationCanBeClaimedAtElevenAndSentWithItsStableFence() {
        Seed seed = insertOutbox(
                "DEAD",
                10,
                NOW.minusSeconds(1),
                NOW.minusSeconds(10),
                null,
                null,
                "MailSendException|SMTP_DELIVERY_FAILED",
                null);
        makePendingForManualRetry(seed.outboxId(), NOW);

        LeasedEmail leased = repository.recoverExpiredAndClaim(
                        "manual-retry-claim", NOW, LEASE_DURATION, 1)
                .get(0);

        assertThat(leased.attempts()).isEqualTo(11);
        assertThat(leased.notification().stableMessageId()).isEqualTo(seed.stableMessageId());
        assertThat(repository.markSent(
                        seed.outboxId(), leased.leaseOwner(), 10, NOW.plusSeconds(1)))
                .isFalse();
        assertThat(repository.markSent(
                        seed.outboxId(), "different-manual-claim", 11, NOW.plusSeconds(1)))
                .isFalse();
        assertThat(state(seed.outboxId()))
                .extracting(
                        OutboxState::status,
                        OutboxState::attempts,
                        OutboxState::leaseOwner,
                        OutboxState::stableMessageId)
                .containsExactly(
                        "SENDING", 11, "manual-retry-claim", seed.stableMessageId());

        assertThat(repository.markSent(
                        seed.outboxId(), leased.leaseOwner(), 11, NOW.plusSeconds(1)))
                .isTrue();
        assertThat(state(seed.outboxId()))
                .extracting(
                        OutboxState::status,
                        OutboxState::attempts,
                        OutboxState::leaseOwner,
                        OutboxState::sentAt,
                        OutboxState::stableMessageId)
                .containsExactly(
                        "SENT", 11, null, NOW.plusSeconds(1), seed.stableMessageId());
    }

    @Test
    void eleventhFailureReturnsAManuallyRetriedNotificationToDead() {
        Seed seed = insertOutbox(
                "DEAD",
                10,
                NOW.minusSeconds(1),
                NOW.minusSeconds(10),
                null,
                null,
                "MailSendException|SMTP_DELIVERY_FAILED",
                null);
        makePendingForManualRetry(seed.outboxId(), NOW);
        LeasedEmail leased = repository.recoverExpiredAndClaim(
                        "eleventh-failure-claim", NOW, LEASE_DURATION, 1)
                .get(0);

        assertThat(repository.markFailed(
                        seed.outboxId(),
                        leased.leaseOwner(),
                        leased.attempts(),
                        NOW.plus(Duration.ofDays(1)),
                        "MailSendException|SMTP_DELIVERY_FAILED"))
                .isTrue();

        assertThat(state(seed.outboxId()))
                .extracting(
                        OutboxState::status,
                        OutboxState::attempts,
                        OutboxState::leaseOwner,
                        OutboxState::leaseUntil,
                        OutboxState::lastErrorSummary,
                        OutboxState::stableMessageId)
                .containsExactly(
                        "DEAD",
                        11,
                        null,
                        null,
                        "MailSendException|SMTP_DELIVERY_FAILED",
                        seed.stableMessageId());
        assertThat(repository.recoverExpiredAndClaim(
                        "do-not-auto-retry-eleven",
                        NOW.plus(Duration.ofDays(2)),
                        LEASE_DURATION,
                        1))
                .isEmpty();
    }

    @Test
    void expiredEleventhLeaseIsRecoveredToDeadWithoutLosingItsStableIdentity() {
        Seed seed = insertOutbox(
                "DEAD",
                10,
                NOW.minusSeconds(1),
                NOW.minusSeconds(10),
                null,
                null,
                "MailSendException|SMTP_DELIVERY_FAILED",
                null);
        makePendingForManualRetry(seed.outboxId(), NOW);
        LeasedEmail leased = repository.recoverExpiredAndClaim(
                        "crashed-eleventh-claim", NOW, LEASE_DURATION, 1)
                .get(0);
        assertThat(leased.attempts()).isEqualTo(11);
        setLeaseUntil(seed.outboxId(), NOW.minusNanos(1_000));

        assertThat(repository.recoverExpiredAndClaim(
                        "post-crash-claim", NOW, LEASE_DURATION, 1))
                .isEmpty();

        assertThat(state(seed.outboxId()))
                .extracting(
                        OutboxState::status,
                        OutboxState::attempts,
                        OutboxState::leaseOwner,
                        OutboxState::leaseUntil,
                        OutboxState::lastErrorSummary,
                        OutboxState::stableMessageId)
                .containsExactly(
                        "DEAD",
                        11,
                        null,
                        null,
                        "LeaseExpired|DELIVERY_INTERRUPTED",
                        seed.stableMessageId());
    }

    @Test
    void pendingClaimCanReachButNeverOverflowTheLargestIntegerAttempt() {
        Seed incrementable = insertOutbox(
                "PENDING",
                Integer.MAX_VALUE - 1,
                NOW.minusSeconds(2),
                NOW.minusSeconds(10),
                null,
                null,
                null,
                null);
        Seed exhausted = insertOutbox(
                "PENDING",
                Integer.MAX_VALUE,
                NOW.minusSeconds(1),
                NOW.minusSeconds(9),
                null,
                null,
                null,
                null);

        List<LeasedEmail> leased = repository.recoverExpiredAndClaim(
                "largest-attempt-claim", NOW, LEASE_DURATION, 10);

        assertThat(leased)
                .singleElement()
                .satisfies(email -> {
                    assertThat(email.notification().outboxId()).isEqualTo(incrementable.outboxId());
                    assertThat(email.attempts()).isEqualTo(Integer.MAX_VALUE);
                    assertThat(repository.markSent(
                                    incrementable.outboxId(),
                                    email.leaseOwner(),
                                    email.attempts(),
                                    NOW.plusSeconds(1)))
                            .isTrue();
                });
        assertThat(state(exhausted.outboxId()))
                .extracting(OutboxState::status, OutboxState::attempts)
                .containsExactly("PENDING", Integer.MAX_VALUE);
    }

    @Test
    void deletingTheContactCascadesItsOutboxRow() {
        Seed seed = insertOutbox(
                "PENDING", 0, NOW.minusSeconds(1), NOW.minusSeconds(10),
                null, null, null, null);

        assertThat(migratorJdbc().sql("""
                        delete from portfolio.contact_message where id=:id
                        """)
                .param("id", seed.contactMessageId(), Types.OTHER)
                .update()).isOne();

        assertThat(migratorJdbc().sql("""
                        select count(*) from portfolio.email_outbox where id=:id
                        """)
                .param("id", seed.outboxId(), Types.OTHER)
                .query(Long.class)
                .single()).isZero();
    }

    private List<LeasedEmail> claimAfterGate(
            String token, CountDownLatch ready, CountDownLatch start, int limit) {
        ready.countDown();
        await(start, "concurrent claim start timed out");
        return repository.recoverExpiredAndClaim(
                token, NOW, LEASE_DURATION, limit);
    }

    private Seed insertOutbox(
            String status,
            int attempts,
            Instant nextAttemptAt,
            Instant createdAt,
            String leaseOwner,
            Instant leaseUntil,
            String lastErrorSummary,
            Instant sentAt) {
        UUID contactMessageId = UUID.randomUUID();
        UUID outboxId = UUID.randomUUID();
        String visitorName = "Lease Test Visitor";
        String visitorEmail = "lease-test@example.com";
        String subject = "Lease integration subject";
        String body = "Private lease integration body";
        String dedupeKey = contactMessageId.toString().replace("-", "").repeat(2);
        String stableMessageId =
                "<portfolio-contact-" + contactMessageId + "@yychainsaw.xyz>";
        contacts.insert(new ContactMessageRecord(
                contactMessageId,
                visitorName,
                visitorEmail,
                subject,
                body,
                "UNREAD",
                dedupeKey,
                createdAt.minusSeconds(1),
                0,
                createdAt,
                createdAt));
        outbox.insert(new EmailOutboxRecord(
                outboxId,
                contactMessageId,
                TEMPLATE,
                RECIPIENT,
                stableMessageId,
                status,
                attempts,
                nextAttemptAt,
                leaseOwner,
                leaseUntil,
                lastErrorSummary,
                createdAt,
                sentAt,
                createdAt));
        return new Seed(
                outboxId,
                contactMessageId,
                stableMessageId,
                visitorName,
                visitorEmail,
                subject,
                body,
                createdAt);
    }

    private void setLeaseUntil(UUID outboxId, Instant leaseUntil) {
        assertThat(migratorJdbc().sql("""
                        update portfolio.email_outbox
                        set lease_until=:leaseUntil
                        where id=:id
                        """)
                .param(
                        "leaseUntil",
                        OffsetDateTime.ofInstant(leaseUntil, ZoneOffset.UTC),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .param("id", outboxId, Types.OTHER)
                .update()).isOne();
    }

    private void makePendingForManualRetry(UUID outboxId, Instant nextAttemptAt) {
        assertThat(migratorJdbc().sql("""
                        update portfolio.email_outbox
                        set status='PENDING',
                            next_attempt_at=:nextAttemptAt,
                            lease_owner=null,
                            lease_until=null,
                            last_error_summary=null,
                            sent_at=null
                        where id=:id
                        """)
                .param(
                        "nextAttemptAt",
                        OffsetDateTime.ofInstant(nextAttemptAt, ZoneOffset.UTC),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .param("id", outboxId, Types.OTHER)
                .update()).isOne();
    }

    private OutboxState state(UUID outboxId) {
        return migratorJdbc().sql("""
                        select status, attempts, next_attempt_at, lease_owner,
                               lease_until, last_error_summary, sent_at,
                               template_name, to_address, stable_message_id
                        from portfolio.email_outbox
                        where id=:id
                        """)
                .param("id", outboxId, Types.OTHER)
                .query(EmailOutboxLeaseIntegrationTest::mapState)
                .single();
    }

    private static OutboxState mapState(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new OutboxState(
                resultSet.getString("status"),
                resultSet.getInt("attempts"),
                instant(resultSet, "next_attempt_at"),
                resultSet.getString("lease_owner"),
                instant(resultSet, "lease_until"),
                resultSet.getString("last_error_summary"),
                instant(resultSet, "sent_at"),
                resultSet.getString("template_name"),
                resultSet.getString("to_address"),
                resultSet.getString("stable_message_id"));
    }

    private static Instant instant(ResultSet resultSet, String column)
            throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static List<UUID> ids(List<LeasedEmail> leased) {
        return leased.stream()
                .map(email -> email.notification().outboxId())
                .toList();
    }

    private static void await(CountDownLatch latch, String message) {
        try {
            if (!latch.await(10, SECONDS)) {
                throw new IllegalStateException(message);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(message, interrupted);
        }
    }

    private static void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, SECONDS)).isTrue();
    }

    private record Seed(
            UUID outboxId,
            UUID contactMessageId,
            String stableMessageId,
            String visitorName,
            String visitorEmail,
            String subject,
            String body,
            Instant createdAt) {}

    private record OutboxState(
            String status,
            int attempts,
            Instant nextAttemptAt,
            String leaseOwner,
            Instant leaseUntil,
            String lastErrorSummary,
            Instant sentAt,
            String templateName,
            String toAddress,
            String stableMessageId) {}
}
