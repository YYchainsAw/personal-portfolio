package xyz.yychainsaw.portfolio.message.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest(properties = {
        "portfolio.email.enabled=false",
        "management.health.mail.enabled=false",
        "portfolio.jobs.worker-enabled=false"
})
@Isolated
class MessageRetentionIntegrationTest extends PostgresIntegrationTestBase {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-07-18T08:30:00Z");
    private static final Instant CUTOFF = Instant.parse("2025-07-18T08:30:00Z");
    private static final UUID SUCCESS_RUN_ID =
            UUID.fromString("96000000-0000-4000-8000-000000000001");
    private static final UUID FAILURE_RUN_ID =
            UUID.fromString("96000000-0000-4000-8000-000000000002");
    private static final String PRIVATE_EMAIL =
            "retention-private@example.invalid";
    private static final String PRIVATE_BODY =
            "Private contact body that must never enter maintenance metadata";
    private static final String PRIVATE_DEPENDENCY_FAILURE =
            "victim@example.invalid on private-db.internal";

    @Autowired JdbcMessageRetentionRepository repository;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    @AfterEach
    void clearRetentionFixtures() {
        JdbcClient owner = migratorJdbc();
        owner.sql("delete from portfolio.email_outbox").update();
        owner.sql("delete from portfolio.contact_message").update();
        owner.sql("""
                        delete from portfolio.maintenance_run
                        where id=:successRunId or id=:failureRunId
                        """)
                .param("successRunId", SUCCESS_RUN_ID, Types.OTHER)
                .param("failureRunId", FAILURE_RUN_ID, Types.OTHER)
                .update();
    }

    @Test
    void deletesOnlyRowsStrictlyBeforeTheCutoffAndKeepsTheExactBoundary() {
        Seed expired = insertPairedMessage(
                CUTOFF.minusNanos(1_000), "PENDING");
        Seed boundary = insertPairedMessage(CUTOFF, "PENDING");

        assertThat(repository.deleteExpiredBatch(CUTOFF, 500)).isOne();

        assertThat(contactExists(expired.contactId())).isFalse();
        assertThat(outboxExists(expired.outboxId())).isFalse();
        assertThat(contactExists(boundary.contactId())).isTrue();
        assertThat(outboxExists(boundary.outboxId())).isTrue();
        assertThat(repository.deleteExpiredBatch(CUTOFF, 500)).isZero();
    }

    @Test
    void deletesFiveHundredThenOneAndCascadesAllOutboxRows() {
        bulkInsertPairedMessages(501, CUTOFF.minusSeconds(1));

        assertThat(repository.deleteExpiredBatch(CUTOFF, 500)).isEqualTo(500);
        assertThat(contactCount()).isOne();
        assertThat(outboxCount()).isOne();

        assertThat(repository.deleteExpiredBatch(CUTOFF, 500)).isOne();
        assertThat(contactCount()).isZero();
        assertThat(outboxCount()).isZero();
        assertThat(repository.deleteExpiredBatch(CUTOFF, 500)).isZero();
    }

    @Test
    void skipsExpiredMessagesWhoseOutboxIsActivelySending() {
        Seed pending = insertPairedMessage(
                CUTOFF.minusSeconds(2), "PENDING");
        Seed sending = insertPairedMessage(
                CUTOFF.minusSeconds(1), "SENDING");
        Seed expiredLease = insertPairedMessage(
                CUTOFF.minusSeconds(3), "SENDING");
        setLeaseUntil(expiredLease.outboxId(), CUTOFF.minusSeconds(1));

        assertThat(repository.deleteExpiredBatch(CUTOFF, 500)).isEqualTo(2);

        assertThat(contactExists(pending.contactId())).isFalse();
        assertThat(outboxExists(pending.outboxId())).isFalse();
        assertThat(contactExists(expiredLease.contactId())).isFalse();
        assertThat(outboxExists(expiredLease.outboxId())).isFalse();
        assertThat(contactExists(sending.contactId())).isTrue();
        assertThat(outboxExists(sending.outboxId())).isTrue();
        assertThat(repository.deleteExpiredBatch(CUTOFF, 500)).isZero();
    }

    @Test
    void successfulRunPersistsOnlyTheDeletedCountAndNoContactData()
            throws Exception {
        insertPairedMessage(CUTOFF.minusSeconds(1), "PENDING");
        MessageRetentionJobHandler handler = handler(repository, SUCCESS_RUN_ID);

        handler.handle(payload());

        RunState run = runState(SUCCESS_RUN_ID);
        assertThat(run.runType()).isEqualTo("CONTACT_RETENTION");
        assertThat(run.status()).isEqualTo("SUCCEEDED");
        assertThat(run.artifactChecksum()).isNull();
        assertThat(run.errorSummary()).isNull();
        assertThat(JSON.readTree(run.details()))
                .isEqualTo(JSON.createObjectNode().put("deleted_count", 1));
        assertThat(run.persistedText())
                .doesNotContain(PRIVATE_EMAIL, PRIVATE_BODY);
        assertThat(run.finishedAt()).isNotNull();
    }

    @Test
    void failedRunPersistsOnlyZeroCountAndTheFixedRedactedErrorCode()
            throws Exception {
        MessageRetentionRepository failingRepository =
                failingAfterStartRepository();
        MessageRetentionJobHandler handler =
                handler(failingRepository, FAILURE_RUN_ID);

        assertThatThrownBy(() -> handler.handle(payload()))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("CONTACT_RETENTION_FAILED")
                .hasNoCause();

        RunState run = runState(FAILURE_RUN_ID);
        assertThat(run.runType()).isEqualTo("CONTACT_RETENTION");
        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.artifactChecksum()).isNull();
        assertThat(run.errorSummary()).isEqualTo("CONTACT_RETENTION_FAILED");
        assertThat(JSON.readTree(run.details()))
                .isEqualTo(JSON.createObjectNode().put("deleted_count", 0));
        assertThat(run.persistedText())
                .doesNotContain(PRIVATE_DEPENDENCY_FAILURE, "victim@example.invalid");
        assertThat(run.finishedAt()).isNotNull();
    }

    private MessageRetentionRepository failingAfterStartRepository() {
        return new MessageRetentionRepository() {
            @Override
            public void start(UUID runId, Instant startedAt) {
                repository.start(runId, startedAt);
            }

            @Override
            public int deleteExpiredBatch(Instant cutoff, int batchSize) {
                throw new IllegalStateException(PRIVATE_DEPENDENCY_FAILURE);
            }

            @Override
            public void succeed(
                    UUID runId, long deletedCount, Instant finishedAt) {
                repository.succeed(runId, deletedCount, finishedAt);
            }

            @Override
            public void fail(
                    UUID runId,
                    long deletedCount,
                    Instant finishedAt,
                    String safeErrorCode) {
                repository.fail(
                        runId, deletedCount, finishedAt, safeErrorCode);
            }
        };
    }

    private static MessageRetentionJobHandler handler(
            MessageRetentionRepository target, UUID runId) {
        return new MessageRetentionJobHandler(
                target,
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> runId);
    }

    private static JsonNode payload() {
        return JSON.createObjectNode().put("siteDate", "2026-07-18");
    }

    private Seed insertPairedMessage(Instant createdAt, String outboxStatus) {
        UUID contactId = UUID.randomUUID();
        UUID outboxId = UUID.randomUUID();
        OffsetDateTime databaseCreatedAt = databaseTimestamp(createdAt);
        JdbcClient owner = migratorJdbc();
        assertThat(owner.sql("""
                        insert into portfolio.contact_message(
                            id, visitor_name, visitor_email, subject, body,
                            status, dedupe_key, privacy_accepted_at,
                            created_at, updated_at
                        ) values (
                            :id, 'Retention Private Visitor', :visitorEmail,
                            'Retention integration fixture', :body, 'UNREAD',
                            :dedupeKey, :privacyAcceptedAt, :createdAt, :createdAt
                        )
                        """)
                .param("id", contactId, Types.OTHER)
                .param("visitorEmail", PRIVATE_EMAIL, Types.VARCHAR)
                .param("body", PRIVATE_BODY, Types.VARCHAR)
                .param("dedupeKey", "a".repeat(64), Types.CHAR)
                .param(
                        "privacyAcceptedAt",
                        databaseTimestamp(createdAt.minusSeconds(1)),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .param(
                        "createdAt",
                        databaseCreatedAt,
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .update()).isOne();

        String leaseOwner = "SENDING".equals(outboxStatus)
                ? "retention-integration-lease"
                : null;
        assertThat(owner.sql("""
                        insert into portfolio.email_outbox(
                            id, contact_message_id, template_name, to_address,
                            stable_message_id, status, attempts, next_attempt_at,
                            lease_owner, lease_until, created_at, updated_at
                        ) values (
                            :id, :contactId, 'contact-notification-v1',
                            'owner@yychainsaw.xyz', :stableMessageId, :status,
                            0, :createdAt, :leaseOwner,
                            case when :status='SENDING'
                                 then clock_timestamp() + interval '1 hour'
                                 else null end,
                            :createdAt, :createdAt
                        )
                        """)
                .param("id", outboxId, Types.OTHER)
                .param("contactId", contactId, Types.OTHER)
                .param(
                        "stableMessageId",
                        "<retention-" + contactId + "@yychainsaw.xyz>",
                        Types.VARCHAR)
                .param("status", outboxStatus, Types.VARCHAR)
                .param(
                        "createdAt",
                        databaseCreatedAt,
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .param("leaseOwner", leaseOwner, Types.VARCHAR)
                .update()).isOne();
        return new Seed(contactId, outboxId);
    }

    private void bulkInsertPairedMessages(int count, Instant createdAt) {
        OffsetDateTime timestamp = databaseTimestamp(createdAt);
        JdbcClient owner = migratorJdbc();
        assertThat(owner.sql("""
                        insert into portfolio.contact_message(
                            id, visitor_name, visitor_email, subject, body,
                            status, dedupe_key, privacy_accepted_at,
                            created_at, updated_at
                        )
                        select gen_random_uuid(), 'Retention Batch Visitor',
                               :visitorEmail, 'Retention batch fixture', :body,
                               'UNREAD', :dedupeKey, :privacyAcceptedAt,
                               :createdAt, :createdAt
                        from generate_series(1, :fixtureCount)
                        """)
                .param("visitorEmail", PRIVATE_EMAIL, Types.VARCHAR)
                .param("body", PRIVATE_BODY, Types.VARCHAR)
                .param("dedupeKey", "b".repeat(64), Types.CHAR)
                .param(
                        "privacyAcceptedAt",
                        databaseTimestamp(createdAt.minusSeconds(1)),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .param("createdAt", timestamp, Types.TIMESTAMP_WITH_TIMEZONE)
                .param("fixtureCount", count, Types.INTEGER)
                .update()).isEqualTo(count);
        assertThat(owner.sql("""
                        insert into portfolio.email_outbox(
                            id, contact_message_id, template_name, to_address,
                            stable_message_id, status, attempts,
                            next_attempt_at, created_at, updated_at
                        )
                        select gen_random_uuid(), message.id,
                               'contact-notification-v1',
                               'owner@yychainsaw.xyz',
                               '<retention-' || message.id || '@yychainsaw.xyz>',
                               'PENDING', 0, :createdAt, :createdAt, :createdAt
                        from portfolio.contact_message message
                        where message.subject='Retention batch fixture'
                        """)
                .param("createdAt", timestamp, Types.TIMESTAMP_WITH_TIMEZONE)
                .update()).isEqualTo(count);
    }

    private void setLeaseUntil(UUID outboxId, Instant leaseUntil) {
        migratorJdbc().sql("""
                        update portfolio.email_outbox
                        set lease_until=:leaseUntil
                        where id=:id
                        """)
                .param(
                        "leaseUntil",
                        databaseTimestamp(leaseUntil),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .param("id", outboxId, Types.OTHER)
                .update();
    }

    private RunState runState(UUID runId) {
        return jdbc.sql("""
                        select run_type, status, artifact_checksum,
                               error_summary, details::text as details,
                               concat_ws('|', error_summary, details::text)
                                   as persisted_text,
                               finished_at
                        from portfolio.maintenance_run
                        where id=:id
                        """)
                .param("id", runId, Types.OTHER)
                .query(MessageRetentionIntegrationTest::mapRunState)
                .single();
    }

    private static RunState mapRunState(ResultSet resultSet, int rowNumber)
            throws SQLException {
        OffsetDateTime finishedAt = resultSet.getObject(
                "finished_at", OffsetDateTime.class);
        return new RunState(
                resultSet.getString("run_type"),
                resultSet.getString("status"),
                resultSet.getString("artifact_checksum"),
                resultSet.getString("error_summary"),
                resultSet.getString("details"),
                resultSet.getString("persisted_text"),
                finishedAt == null ? null : finishedAt.toInstant());
    }

    private boolean contactExists(UUID contactId) {
        return migratorJdbc().sql("""
                        select exists(
                            select 1 from portfolio.contact_message where id=:id
                        )
                        """)
                .param("id", contactId, Types.OTHER)
                .query(Boolean.class)
                .single();
    }

    private boolean outboxExists(UUID outboxId) {
        return migratorJdbc().sql("""
                        select exists(
                            select 1 from portfolio.email_outbox where id=:id
                        )
                        """)
                .param("id", outboxId, Types.OTHER)
                .query(Boolean.class)
                .single();
    }

    private long contactCount() {
        return migratorJdbc().sql("select count(*) from portfolio.contact_message")
                .query(Long.class)
                .single();
    }

    private long outboxCount() {
        return migratorJdbc().sql("select count(*) from portfolio.email_outbox")
                .query(Long.class)
                .single();
    }

    private static OffsetDateTime databaseTimestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record Seed(UUID contactId, UUID outboxId) {}

    private record RunState(
            String runType,
            String status,
            String artifactChecksum,
            String errorSummary,
            String details,
            String persistedText,
            Instant finishedAt) {}
}
