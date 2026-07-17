package xyz.yychainsaw.portfolio.message.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.common.ratelimit.RateLimitDecision;
import xyz.yychainsaw.portfolio.common.ratelimit.RateLimiter;
import xyz.yychainsaw.portfolio.message.config.ContactProperties;
import xyz.yychainsaw.portfolio.message.persistence.ContactMessageMapper;
import xyz.yychainsaw.portfolio.message.persistence.EmailOutboxMapper;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest(properties = {
    "portfolio.contact.dedupe-secret=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
    "portfolio.contact.owner-email=portfolio-owner@example.com",
    "portfolio.contact.mail-id-domain=yychainsaw.xyz",
    "portfolio.jobs.worker-enabled=false"
})
@Import(ContactMessageServiceTest.ContactClockConfiguration.class)
@Isolated
class ContactMessageServiceTest extends PostgresIntegrationTestBase {
    private static final Instant BASE_TIME = Instant.parse("2026-07-14T10:00:00Z");
    private static final String POLICY = "public-contact";
    private static final String SUBJECT_KEY = "a".repeat(64);
    private static final String PII_NAME = "PII Name Sentinel";
    private static final String PII_EMAIL = "pii.sentinel@example.com";
    private static final String PII_SUBJECT = "PII Subject Sentinel";
    private static final String PII_BODY = "PII Body Sentinel";
    private static final String PII_RATE_SUBJECT = "pii-rate-subject-sentinel";

    @Autowired ContactMessageService service;
    @Autowired ContactFingerprintService fingerprints;
    @Autowired JdbcClient jdbc;
    @Autowired MutableClock clock;
    @Autowired ContactProperties properties;
    @Autowired ContactMessageMapper contactMapper;
    @Autowired EmailOutboxMapper outboxMapper;

    @MockitoBean RateLimiter limiter;

    private final JdbcClient owner = migratorJdbc();

    @BeforeEach
    void setUp() {
        dropFaultInjectionObjects();
        cleanRows();
        clock.set(BASE_TIME);
        reset(limiter);
        when(limiter.consume(anyString(), anyString())).thenReturn(RateLimitDecision.allow());
    }

    @AfterEach
    void cleanUp() {
        dropFaultInjectionObjects();
        cleanRows();
    }

    @Test
    void acceptedSubmissionCommitsOneMessageAndOneOutboxWithOneMicrosecondClockValue() {
        Instant rawTime = Instant.parse("2026-07-14T10:00:00.123456789Z");
        Instant persistedTime = Instant.parse("2026-07-14T10:00:00.123456Z");
        clock.set(rawTime);

        ContactSubmissionResult result = service.submit(validCommand());

        assertThat(result.accepted()).isTrue();
        assertThat(result.messageId()).isNotNull();
        assertThat(messageCount()).isOne();
        assertThat(outboxCount()).isOne();
        assertThat(contactMapper.findById(result.messageId())).isPresent();
        assertThat(contactMapper.count()).isOne();
        assertThat(outboxMapper.findByMessageId(result.messageId())).hasSize(1);
        assertThat(outboxMapper.count()).isOne();

        ContactRow message = message(result.messageId());
        assertThat(message.visitorName()).isEqualTo("Player One");
        assertThat(message.visitorEmail()).isEqualTo("player@example.com");
        assertThat(message.subject()).isEqualTo("UE collaboration");
        assertThat(message.body()).isEqualTo("I would like to discuss your project.");
        assertThat(message.status()).isEqualTo("UNREAD");
        assertThat(message.dedupeKey()).matches("[0-9a-f]{64}");
        assertThat(message.version()).isZero();
        assertThat(message.privacyAcceptedAt()).isEqualTo(persistedTime);
        assertThat(message.createdAt()).isEqualTo(persistedTime);
        assertThat(message.updatedAt()).isEqualTo(persistedTime);

        OutboxRow outbox = outbox(result.messageId());
        assertThat(outbox.stableMessageId())
                .isEqualTo("<portfolio-contact-" + result.messageId() + "@yychainsaw.xyz>");
        assertThat(outbox.status()).isEqualTo("PENDING");
        assertThat(outbox.attempts()).isZero();
        assertThat(outbox.nextAttemptAt()).isEqualTo(persistedTime);
        assertThat(outbox.createdAt()).isEqualTo(persistedTime);
        assertThat(outbox.updatedAt()).isEqualTo(persistedTime);
        assertThat(outbox.leaseOwner()).isNull();
        assertThat(outbox.leaseUntil()).isNull();
        assertThat(outbox.sentAt()).isNull();
        verify(limiter).consume(POLICY, SUBJECT_KEY);
    }

    @Test
    void populatedHoneypotAcceptsOtherwiseInvalidInputWithoutLimiterClockOrDatabaseEffects() {
        SubmitContactCommand bot = new SubmitContactCommand(
                null,
                null,
                null,
                null,
                "https://spam.invalid",
                false,
                PII_RATE_SUBJECT);

        ContactSubmissionResult result = service.submit(bot);

        assertThat(result.accepted()).isTrue();
        assertThat(result.messageId()).isNull();
        assertThat(messageCount()).isZero();
        assertThat(outboxCount()).isZero();
        verifyNoInteractions(limiter);
    }

    @Test
    void duplicateReturnsGenericAcceptanceButStillConsumesTheQuota() {
        SubmitContactCommand command = validCommand();

        ContactSubmissionResult first = service.submit(command);
        ContactSubmissionResult duplicate = service.submit(command);

        assertThat(first.accepted()).isTrue();
        assertThat(first.messageId()).isNotNull();
        assertThat(duplicate.accepted()).isTrue();
        assertThat(duplicate.messageId()).isNull();
        assertThat(messageCount()).isOne();
        assertThat(outboxCount()).isOne();
        verify(limiter, times(2)).consume(POLICY, SUBJECT_KEY);
    }

    @Test
    void tenMinuteWindowIsInclusiveAndExpiresOneMicrosecondLater() {
        SubmitContactCommand command = validCommand();
        ContactSubmissionResult first = service.submit(command);

        clock.set(BASE_TIME.plus(Duration.ofMinutes(10)).minusNanos(1_000));
        ContactSubmissionResult justInside = service.submit(command);
        clock.set(BASE_TIME.plus(Duration.ofMinutes(10)));
        ContactSubmissionResult exactBoundary = service.submit(command);
        clock.set(BASE_TIME.plus(Duration.ofMinutes(10)).plusNanos(1_000));
        ContactSubmissionResult justOutside = service.submit(command);

        assertThat(first.messageId()).isNotNull();
        assertThat(justInside.messageId()).isNull();
        assertThat(exactBoundary.messageId()).isNull();
        assertThat(justOutside.messageId()).isNotNull();
        assertThat(messageCount()).isEqualTo(2);
        assertThat(outboxCount()).isEqualTo(2);
        verify(limiter, times(4)).consume(POLICY, SUBJECT_KEY);
    }

    @Test
    void concurrentEqualSubmissionsCommitExactlyOneMessageAndOneOutbox() throws Exception {
        installSlowMessageInsertTrigger();
        SubmitContactCommand command = validCommand();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ContactSubmissionResult> first = executor.submit(() -> {
                ready.countDown();
                await(start, "concurrent submission start");
                return service.submit(command);
            });
            Future<ContactSubmissionResult> second = executor.submit(() -> {
                ready.countDown();
                await(start, "concurrent submission start");
                return service.submit(command);
            });

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<ContactSubmissionResult> results =
                    List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

            assertThat(results).allSatisfy(result -> assertThat(result.accepted()).isTrue());
            assertThat(results.stream()
                            .map(ContactSubmissionResult::messageId)
                            .filter(Objects::nonNull)
                            .toList())
                    .hasSize(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(messageCount()).isOne();
        assertThat(outboxCount()).isOne();
        verify(limiter, times(2)).consume(POLICY, SUBJECT_KEY);
    }

    @Test
    void realOutboxFailureRollsBackMessageAndReleasesDedupeLockForRetry() {
        installFailingOutboxTrigger();
        SubmitContactCommand command = validCommand();

        assertThatThrownBy(() -> service.submit(command)).isInstanceOf(RuntimeException.class);
        assertThat(messageCount()).isZero();
        assertThat(outboxCount()).isZero();

        dropFaultInjectionObjects();
        clock.set(BASE_TIME.plusSeconds(1));
        ContactSubmissionResult retried = service.submit(command);

        assertThat(retried.accepted()).isTrue();
        assertThat(retried.messageId()).isNotNull();
        assertThat(messageCount()).isOne();
        assertThat(outboxCount()).isOne();
        verify(limiter, times(2)).consume(POLICY, SUBJECT_KEY);
    }

    @Test
    void zeroAffectedOutboxInsertFailsClosedAndRollsBackTheMessage() {
        installSkippedOutboxTrigger();

        assertThatThrownBy(() -> service.submit(validCommand()))
                .isInstanceOf(RuntimeException.class);

        assertThat(messageCount()).isZero();
        assertThat(outboxCount()).isZero();
        verify(limiter).consume(POLICY, SUBJECT_KEY);
    }

    @Test
    void contendedDedupeLockTimesOutWithoutWritesAndCanBeRetried() throws Exception {
        SubmitContactCommand command = validCommand();
        String dedupeKey = fingerprints.contactKey(command);
        long lockKey = Long.parseUnsignedLong(dedupeKey.substring(0, 16), 16);

        try (Connection connection = migratorDataSource().getConnection();
                PreparedStatement lock = connection.prepareStatement(
                        "select pg_catalog.pg_advisory_xact_lock(?)")) {
            connection.setAutoCommit(false);
            lock.setLong(1, lockKey);
            lock.execute();

            Instant started = Instant.now();
            Throwable failure = catchThrowable(() -> service.submit(command));
            assertThat(failure).isNotNull();
            assertThat(sqlState(failure)).isEqualTo("55P03");
            assertThat(Duration.between(started, Instant.now()))
                    .isLessThan(Duration.ofSeconds(4));
            assertThat(messageCount()).isZero();
            assertThat(outboxCount()).isZero();
            connection.rollback();
        }

        ContactSubmissionResult retry = service.submit(command);
        assertThat(retry.messageId()).isNotNull();
        assertThat(messageCount()).isOne();
        assertThat(outboxCount()).isOne();
        verify(limiter, times(2)).consume(POLICY, SUBJECT_KEY);
    }

    @Test
    void dedupeLockMapperRejectsCallsWithoutAnActiveTransaction() {
        assertThatThrownBy(() -> contactMapper.acquireDedupeLock("b".repeat(64)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("contact dedupe lock requires a transaction");
    }

    @Test
    void nfcEmailDomainCaseAndLineEndingVariantsShareOneFingerprint() {
        SubmitContactCommand decomposed = new SubmitContactCommand(
                "  Jose\u0301  ",
                "  Player@EXAMPLE.COM  ",
                "  Cafe\u0301  ",
                "  First line\r\nSecond line  ",
                "",
                true,
                SUBJECT_KEY);
        SubmitContactCommand canonical = new SubmitContactCommand(
                "Jos\u00e9",
                "Player@example.com",
                "Caf\u00e9",
                "First line\nSecond line",
                null,
                true,
                SUBJECT_KEY);

        ContactSubmissionResult first = service.submit(decomposed);
        ContactSubmissionResult duplicate = service.submit(canonical);

        assertThat(first.messageId()).isNotNull();
        assertThat(duplicate.messageId()).isNull();
        ContactRow stored = message(first.messageId());
        assertThat(stored.visitorName()).isEqualTo("Jos\u00e9");
        assertThat(stored.visitorEmail()).isEqualTo("Player@example.com");
        assertThat(stored.subject()).isEqualTo("Caf\u00e9");
        assertThat(stored.body()).isEqualTo("First line\nSecond line");
        assertThat(messageCount()).isOne();
        assertThat(outboxCount()).isOne();
        verify(limiter, times(2)).consume(POLICY, SUBJECT_KEY);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidSubmissions")
    void invalidRealSubmissionReturns422AndWritesNothing(
            String description, SubmitContactCommand command) {
        DomainException failure = catchThrowableOfType(
                () -> service.submit(command), DomainException.class);

        assertThat(failure).as(description).isNotNull();
        assertThat(failure.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(failure.toString())
                .doesNotContain(command.email(), command.subject(), command.body());
        assertThat(messageCount()).isZero();
        assertThat(outboxCount()).isZero();
        verify(limiter).consume(POLICY, SUBJECT_KEY);
    }

    @Test
    void piiBearingTypesRedactTheirStringRepresentations() throws Exception {
        SubmitContactCommand command = new SubmitContactCommand(
                PII_NAME,
                PII_EMAIL,
                PII_SUBJECT,
                PII_BODY,
                "",
                true,
                PII_RATE_SUBJECT);

        assertRedacted(command);
        assertRedacted(newPiiRecord(
                "xyz.yychainsaw.portfolio.message.persistence.ContactMessageRecord"));
        assertRedacted(newPiiRecord(
                "xyz.yychainsaw.portfolio.message.persistence.EmailOutboxRecord"));
        assertRedacted(newPiiRecord(
                "xyz.yychainsaw.portfolio.message.web.PublicContactRequest"));
        assertThat(properties.toString())
                .contains("<redacted>")
                .doesNotContain(
                        "portfolio-owner@example.com",
                        "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");
    }

    private static Stream<Arguments> invalidSubmissions() {
        return Stream.of(
                Arguments.of("privacy consent is required", command(
                        "Player One", "player@example.com", "Subject", "Body", false)),
                Arguments.of("subject controls are rejected", command(
                        "Player One", "player@example.com", "Bad\u0007subject", "Body", true)),
                Arguments.of("body controls other than line breaks are rejected", command(
                        "Player One", "player@example.com", "Subject", "Bad\u0001body", true)),
                Arguments.of("Unicode visual blanks are rejected", command(
                        "\u00a0\u2007", "player@example.com", "Subject", "Body", true)),
                Arguments.of("single-line Unicode separators are rejected", command(
                        "Player One", "player@example.com", "Bad\u2028subject", "Body", true)),
                Arguments.of("bidirectional format controls are rejected", command(
                        "Player One", "player@example.com", "Subject", "Bad\u202ebody", true)),
                Arguments.of("invalid email is rejected", command(
                        "Player One", "not-an-email", "Subject", "Body", true)),
                Arguments.of("name length is bounded", command(
                        "n".repeat(101), "player@example.com", "Subject", "Body", true)),
                Arguments.of("email length is bounded", command(
                        "Player One", "a".repeat(309) + "@example.com", "Subject", "Body", true)),
                Arguments.of("subject length is bounded", command(
                        "Player One", "player@example.com", "s".repeat(161), "Body", true)),
                Arguments.of("body length is bounded", command(
                        "Player One", "player@example.com", "Subject", "b".repeat(5001), true)));
    }

    private static SubmitContactCommand command(
            String name, String email, String subject, String body, boolean privacyAccepted) {
        return new SubmitContactCommand(
                name, email, subject, body, "", privacyAccepted, SUBJECT_KEY);
    }

    private static SubmitContactCommand validCommand() {
        return new SubmitContactCommand(
                "Player One",
                "player@example.com",
                "UE collaboration",
                "I would like to discuss your project.",
                "",
                true,
                SUBJECT_KEY);
    }

    private long messageCount() {
        return jdbc.sql("select count(*) from portfolio.contact_message")
                .query(Long.class)
                .single();
    }

    private long outboxCount() {
        return jdbc.sql("select count(*) from portfolio.email_outbox")
                .query(Long.class)
                .single();
    }

    private ContactRow message(UUID id) {
        return jdbc.sql("""
                        select visitor_name, visitor_email, subject, body, status,
                               dedupe_key, privacy_accepted_at, version, created_at, updated_at
                        from portfolio.contact_message
                        where id = :id
                        """)
                .param("id", id)
                .query((resultSet, rowNumber) -> new ContactRow(
                        resultSet.getString("visitor_name"),
                        resultSet.getString("visitor_email"),
                        resultSet.getString("subject"),
                        resultSet.getString("body"),
                        resultSet.getString("status"),
                        resultSet.getString("dedupe_key").stripTrailing(),
                        instant(resultSet.getObject("privacy_accepted_at", OffsetDateTime.class)),
                        resultSet.getInt("version"),
                        instant(resultSet.getObject("created_at", OffsetDateTime.class)),
                        instant(resultSet.getObject("updated_at", OffsetDateTime.class))))
                .single();
    }

    private OutboxRow outbox(UUID messageId) {
        return jdbc.sql("""
                        select stable_message_id, status, attempts, next_attempt_at,
                               lease_owner, lease_until, created_at, sent_at, updated_at
                        from portfolio.email_outbox
                        where contact_message_id = :messageId
                        """)
                .param("messageId", messageId)
                .query((resultSet, rowNumber) -> new OutboxRow(
                        resultSet.getString("stable_message_id"),
                        resultSet.getString("status"),
                        resultSet.getInt("attempts"),
                        instant(resultSet.getObject("next_attempt_at", OffsetDateTime.class)),
                        resultSet.getString("lease_owner"),
                        nullableInstant(resultSet.getObject("lease_until", OffsetDateTime.class)),
                        instant(resultSet.getObject("created_at", OffsetDateTime.class)),
                        nullableInstant(resultSet.getObject("sent_at", OffsetDateTime.class)),
                        instant(resultSet.getObject("updated_at", OffsetDateTime.class))))
                .single();
    }

    private void installSlowMessageInsertTrigger() {
        owner.sql("""
                        create or replace function portfolio.test_contact_slow_insert()
                        returns trigger language plpgsql as $$
                        begin
                          perform pg_sleep(0.75);
                          return new;
                        end
                        $$
                        """).update();
        owner.sql("""
                        create trigger test_contact_slow_insert
                        before insert on portfolio.contact_message
                        for each row execute function portfolio.test_contact_slow_insert()
                        """).update();
    }

    private void installFailingOutboxTrigger() {
        owner.sql("""
                        create or replace function portfolio.test_contact_outbox_failure()
                        returns trigger language plpgsql as $$
                        begin
                          raise exception using
                            errcode = 'P0001', message = 'forced contact outbox failure';
                        end
                        $$
                        """).update();
        owner.sql("""
                        create trigger test_contact_outbox_failure
                        before insert on portfolio.email_outbox
                        for each row execute function portfolio.test_contact_outbox_failure()
                        """).update();
    }

    private void installSkippedOutboxTrigger() {
        owner.sql("""
                        create or replace function portfolio.test_contact_outbox_skip()
                        returns trigger language plpgsql as $$
                        begin
                          return null;
                        end
                        $$
                        """).update();
        owner.sql("""
                        create trigger test_contact_outbox_skip
                        before insert on portfolio.email_outbox
                        for each row execute function portfolio.test_contact_outbox_skip()
                        """).update();
    }

    private void dropFaultInjectionObjects() {
        owner.sql("drop trigger if exists test_contact_slow_insert on portfolio.contact_message")
                .update();
        owner.sql("drop trigger if exists test_contact_outbox_failure on portfolio.email_outbox")
                .update();
        owner.sql("drop trigger if exists test_contact_outbox_skip on portfolio.email_outbox")
                .update();
        owner.sql("drop function if exists portfolio.test_contact_slow_insert()")
                .update();
        owner.sql("drop function if exists portfolio.test_contact_outbox_failure()")
                .update();
        owner.sql("drop function if exists portfolio.test_contact_outbox_skip()")
                .update();
    }

    private void cleanRows() {
        owner.sql("delete from portfolio.email_outbox").update();
        owner.sql("delete from portfolio.contact_message").update();
    }

    private static void await(CountDownLatch latch, String description) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for " + description);
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting for " + description, failure);
        }
    }

    private static Instant instant(OffsetDateTime value) {
        return value.toInstant();
    }

    private static Instant nullableInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static String sqlState(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sqlException) {
                return sqlException.getSQLState();
            }
        }
        return null;
    }

    private static void assertRedacted(Object value) {
        assertThat(value.toString())
                .contains("<redacted>")
                .doesNotContain(
                        PII_NAME,
                        PII_EMAIL,
                        PII_SUBJECT,
                        PII_BODY,
                        PII_RATE_SUBJECT);
    }

    private static Object newPiiRecord(String className) throws Exception {
        Class<?> type = Class.forName(className);
        assertThat(type.isRecord()).as(className + " is a record").isTrue();
        RecordComponent[] components = type.getRecordComponents();
        Class<?>[] parameterTypes = Stream.of(components)
                .map(RecordComponent::getType)
                .toArray(Class<?>[]::new);
        Constructor<?> constructor = type.getDeclaredConstructor(parameterTypes);
        constructor.setAccessible(true);
        Object[] values = Stream.of(components)
                .map(ContactMessageServiceTest::valueFor)
                .toArray();
        return constructor.newInstance(values);
    }

    private static Object valueFor(RecordComponent component) {
        String name = component.getName().toLowerCase();
        Class<?> type = component.getType();
        if (type == String.class) {
            if (name.contains("lease") || name.contains("error")) {
                return null;
            }
            if (name.contains("template")) {
                return "contact-notification-v1";
            }
            if (name.contains("name")) {
                return PII_NAME;
            }
            if (name.contains("email") || name.equals("to") || name.contains("address")) {
                return PII_EMAIL;
            }
            if (name.contains("subject") && !name.contains("rate")) {
                return PII_SUBJECT;
            }
            if (name.contains("body") || name.equals("message")) {
                return PII_BODY;
            }
            if (name.contains("ratelimit") || name.contains("rate_limit")) {
                return PII_RATE_SUBJECT;
            }
            if (name.contains("dedupe")) {
                return "a".repeat(64);
            }
            if (name.contains("stable")) {
                return "<portfolio-contact-00000000-0000-4000-8000-000000000001"
                        + "@yychainsaw.xyz>";
            }
            if (name.contains("status")) {
                return component.getDeclaringRecord().getSimpleName().contains("Outbox")
                        ? "PENDING"
                        : "UNREAD";
            }
            return "";
        }
        if (type == UUID.class) {
            return UUID.fromString("00000000-0000-4000-8000-000000000001");
        }
        if (type == Instant.class) {
            return name.contains("lease") || name.contains("sent") ? null : BASE_TIME;
        }
        if (type == boolean.class || type == Boolean.class) {
            return true;
        }
        if (type == int.class || type == Integer.class) {
            return 0;
        }
        if (type == long.class || type == Long.class) {
            return 0L;
        }
        if (type.isEnum()) {
            return type.getEnumConstants()[0];
        }
        return null;
    }

    private record ContactRow(
            String visitorName,
            String visitorEmail,
            String subject,
            String body,
            String status,
            String dedupeKey,
            Instant privacyAcceptedAt,
            int version,
            Instant createdAt,
            Instant updatedAt) {}

    private record OutboxRow(
            String stableMessageId,
            String status,
            int attempts,
            Instant nextAttemptAt,
            String leaseOwner,
            Instant leaseUntil,
            Instant createdAt,
            Instant sentAt,
            Instant updatedAt) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class ContactClockConfiguration {
        @Bean
        @Primary
        MutableClock contactTestClock() {
            return new MutableClock(BASE_TIME);
        }
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> current;

        MutableClock(Instant initial) {
            this.current = new AtomicReference<>(Objects.requireNonNull(initial));
        }

        void set(Instant value) {
            current.set(Objects.requireNonNull(value));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            Objects.requireNonNull(zone);
            return ZoneOffset.UTC.equals(zone) ? this : Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }
}
