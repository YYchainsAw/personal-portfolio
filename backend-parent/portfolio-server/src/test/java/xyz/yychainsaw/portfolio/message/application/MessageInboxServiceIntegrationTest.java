package xyz.yychainsaw.portfolio.message.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.yychainsaw.portfolio.audit.AuditService;
import xyz.yychainsaw.portfolio.common.error.DomainException;
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
class MessageInboxServiceIntegrationTest extends PostgresIntegrationTestBase {
    private static final Instant NOW = Instant.parse("2026-07-18T08:30:00.123456Z");
    private static final Instant CREATED_AT = Instant.parse("2026-07-17T23:00:00Z");
    private static final String TEMPLATE = "contact-notification-v1";
    private static final String RECIPIENT = "owner@yychainsaw.xyz";

    @Autowired MessageInboxRepository repository;
    @Autowired ContactMessageMapper contacts;
    @Autowired EmailOutboxMapper outbox;
    @Autowired AuditService audit;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcClient jdbc;

    private UUID adminId;
    private MessageInboxService service;

    @BeforeEach
    void prepare() {
        clearMessages();
        adminId = ensureAdmin();
        service = service(audit);
    }

    @AfterEach
    void clearMessages() {
        JdbcClient owner = migratorJdbc();
        owner.sql("delete from portfolio.email_outbox").update();
        owner.sql("delete from portfolio.contact_message").update();
    }

    @Test
    void cursorPaginationIsStableForEqualTimestampsAndFiltersByStatus() {
        UUID smallest = UUID.fromString("96000000-0000-4000-8000-000000000001");
        UUID middle = UUID.fromString("96000000-0000-4000-8000-000000000002");
        UUID largest = UUID.fromString("96000000-0000-4000-8000-000000000003");
        insert(smallest, "UNREAD", "PENDING", 0, CREATED_AT, null);
        insert(middle, "READ", "SENT", 1, CREATED_AT, null);
        insert(largest, "UNREAD", "FAILED", 2, CREATED_AT,
                "MailSendException|SMTP_DELIVERY_FAILED");

        MessagePage first = service.list(null, null, "2");
        MessagePage second = service.list(null, first.nextCursor(), "2");

        assertThat(first.items()).extracting(MessageSummary::id)
                .containsExactly(largest, middle);
        assertThat(first.nextCursor()).isNotBlank();
        assertThat(second.items()).extracting(MessageSummary::id)
                .containsExactly(smallest);
        assertThat(second.nextCursor()).isNull();
        assertThat(service.list("UNREAD", null, "30").items())
                .extracting(MessageSummary::id)
                .containsExactly(largest, smallest);
        assertThat(service.list(null, null, "0").items()).hasSize(1);
        assertThat(service.list(null, null, "999").items()).hasSize(3);
    }

    @Test
    void malformedCursorAndQueryValuesAreRejectedBeforeReadingRows() {
        assertThatThrownBy(() -> service.list("unread", null, "30"))
                .isInstanceOfSatisfying(DomainException.class, problem -> {
                    assertThat(problem.code()).isEqualTo("MESSAGE_QUERY_INVALID");
                    assertThat(problem.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                });
        assertThatThrownBy(() -> service.list(null, "not+base64", "30"))
                .isInstanceOfSatisfying(DomainException.class, problem ->
                        assertThat(problem.code()).isEqualTo("MESSAGE_CURSOR_INVALID"));
        assertThatThrownBy(() -> service.list(null, null, "1 OR 1=1"))
                .isInstanceOfSatisfying(DomainException.class, problem ->
                        assertThat(problem.code()).isEqualTo("MESSAGE_QUERY_INVALID"));
    }

    @Test
    void statusCasAndItsPiiFreeAuditCommitAtomically() {
        Seed seed = insert(
                UUID.randomUUID(),
                "UNREAD",
                "FAILED",
                2,
                CREATED_AT,
                "MailSendException|SMTP_DELIVERY_FAILED");

        MessageDetail updated = service.updateStatus(seed.messageId(), MessageStatus.READ, 0);

        assertThat(updated.status()).isEqualTo(MessageStatus.READ);
        assertThat(updated.version()).isOne();
        assertThat(messageState(seed.messageId()))
                .extracting(MessageState::status, MessageState::version)
                .containsExactly("READ", 1);
        assertAudit(
                seed.messageId(),
                "MESSAGE_STATUS_UPDATE",
                "previousStatus",
                "UNREAD",
                "newStatus",
                "READ");

        assertThatThrownBy(() ->
                        service.updateStatus(seed.messageId(), MessageStatus.SPAM, 0))
                .isInstanceOfSatisfying(DomainException.class, problem ->
                        assertThat(problem.code()).isEqualTo("MESSAGE_VERSION_CONFLICT"));
        assertThat(auditCount(seed.messageId(), "MESSAGE_STATUS_UPDATE")).isOne();
    }

    @Test
    void futureStatusVersionCannotCreateAnAuditWithTheWrongPreviousState() {
        Seed seed = insert(
                UUID.randomUUID(), "UNREAD", "PENDING", 0, CREATED_AT, null);

        assertThatThrownBy(() ->
                        service.updateStatus(seed.messageId(), MessageStatus.SPAM, 1))
                .isInstanceOfSatisfying(DomainException.class, problem ->
                        assertThat(problem.code()).isEqualTo("MESSAGE_VERSION_CONFLICT"));

        assertThat(messageState(seed.messageId()))
                .extracting(MessageState::status, MessageState::version)
                .containsExactly("UNREAD", 0);
        assertThat(auditCount(seed.messageId(), "MESSAGE_STATUS_UPDATE")).isZero();
    }

    @Test
    void auditFailureRollsBackAStatusChange() {
        Seed seed = insert(
                UUID.randomUUID(), "UNREAD", "PENDING", 0, CREATED_AT, null);
        MessageInboxService failing = service(command -> {
            throw new IllegalStateException("visitor@example.com private audit failure");
        });

        assertThatThrownBy(() ->
                        failing.updateStatus(seed.messageId(), MessageStatus.ARCHIVED, 0))
                .isExactlyInstanceOf(IllegalStateException.class);

        assertThat(messageState(seed.messageId()))
                .extracting(MessageState::status, MessageState::version)
                .containsExactly("UNREAD", 0);
        assertThat(auditCount(seed.messageId(), "MESSAGE_STATUS_UPDATE")).isZero();
    }

    @Test
    void manualRetryPreservesAttemptsAndStableIdentityAndCommitsItsAudit() {
        Seed seed = insert(
                UUID.randomUUID(),
                "UNREAD",
                "DEAD",
                10,
                CREATED_AT,
                "MailSendException|SMTP_DELIVERY_FAILED");

        service.retryEmail(seed.messageId());

        assertThat(emailState(seed.outboxId()))
                .extracting(
                        EmailState::status,
                        EmailState::attempts,
                        EmailState::nextAttemptAt,
                        EmailState::lastErrorSummary,
                        EmailState::stableMessageId)
                .containsExactly("PENDING", 10, NOW, null, seed.stableMessageId());
        assertAudit(
                seed.messageId(),
                "MESSAGE_EMAIL_RETRY",
                "previousEmailStatus",
                "DEAD",
                "newEmailStatus",
                "PENDING");

        assertThatThrownBy(() -> service.retryEmail(seed.messageId()))
                .isInstanceOfSatisfying(DomainException.class, problem ->
                        assertThat(problem.code()).isEqualTo(
                                "MESSAGE_EMAIL_RETRY_CONFLICT"));
        assertThat(auditCount(seed.messageId(), "MESSAGE_EMAIL_RETRY")).isOne();
    }

    @Test
    void manualRetryRejectsTheMaximumAttemptInsteadOfStrandingPendingMail() {
        Seed seed = insert(
                UUID.randomUUID(),
                "UNREAD",
                "DEAD",
                Integer.MAX_VALUE,
                CREATED_AT,
                "MailSendException|SMTP_DELIVERY_FAILED");

        assertThatThrownBy(() -> service.retryEmail(seed.messageId()))
                .isInstanceOfSatisfying(DomainException.class, problem ->
                        assertThat(problem.code()).isEqualTo(
                                "MESSAGE_EMAIL_RETRY_CONFLICT"));

        assertThat(emailState(seed.outboxId()))
                .extracting(EmailState::status, EmailState::attempts)
                .containsExactly("DEAD", Integer.MAX_VALUE);
        assertThat(auditCount(seed.messageId(), "MESSAGE_EMAIL_RETRY")).isZero();
    }

    @Test
    void retryAuditFailureRollsBackTheOutboxState() {
        Seed seed = insert(
                UUID.randomUUID(),
                "UNREAD",
                "FAILED",
                4,
                CREATED_AT,
                "MailSendException|SMTP_DELIVERY_FAILED");
        MessageInboxService failing = service(command -> {
            throw new IllegalStateException("private SMTP hostname");
        });

        assertThatThrownBy(() -> failing.retryEmail(seed.messageId()))
                .isExactlyInstanceOf(IllegalStateException.class);

        assertThat(emailState(seed.outboxId()))
                .extracting(
                        EmailState::status,
                        EmailState::attempts,
                        EmailState::lastErrorSummary)
                .containsExactly(
                        "FAILED", 4, "MailSendException|SMTP_DELIVERY_FAILED");
        assertThat(auditCount(seed.messageId(), "MESSAGE_EMAIL_RETRY")).isZero();
    }

    @Test
    void detailExposesOnlyAnAllowlistedErrorCategory() {
        Seed safe = insert(
                UUID.randomUUID(),
                "UNREAD",
                "FAILED",
                1,
                CREATED_AT.minusSeconds(2),
                "MailConnectException|SMTP_CONNECTION_FAILED");
        Seed corrupt = insert(
                UUID.randomUUID(),
                "UNREAD",
                "FAILED",
                1,
                CREATED_AT.minusSeconds(1),
                "smtp.private.host|visitor@example.com SMTP response");

        assertThat(service.detail(safe.messageId()).email().errorCategory())
                .isEqualTo("SMTP_CONNECTION_FAILED");
        assertThat(service.detail(corrupt.messageId()).email().errorCategory()).isNull();
        assertThat(service.detail(corrupt.messageId()).toString())
                .doesNotContain("visitor@example.com")
                .contains("pii=<redacted>");
    }

    @Test
    void deleteCascadesTheOutboxButRejectsAnActiveSmtpLease() {
        Seed deletable = insert(
                UUID.randomUUID(), "ARCHIVED", "FAILED", 3, CREATED_AT, null);

        service.delete(deletable.messageId());

        assertThat(contactCount(deletable.messageId())).isZero();
        assertThat(outboxCount(deletable.outboxId())).isZero();
        assertAudit(
                deletable.messageId(),
                "MESSAGE_DELETE",
                "previousStatus",
                "ARCHIVED",
                "newStatus",
                "DELETED");

        Seed sending = insert(
                UUID.randomUUID(), "UNREAD", "SENDING", 1, CREATED_AT, null);
        setActiveLease(sending.outboxId());
        assertThatThrownBy(() -> service.delete(sending.messageId()))
                .isInstanceOfSatisfying(DomainException.class, problem ->
                        assertThat(problem.code()).isEqualTo("MESSAGE_DELETE_CONFLICT"));
        assertThat(contactCount(sending.messageId())).isOne();
        assertThat(outboxCount(sending.outboxId())).isOne();

        Seed expiredLease = insert(
                UUID.randomUUID(), "UNREAD", "SENDING", 2, CREATED_AT, null);
        setLeaseUntil(expiredLease.outboxId(), NOW.minusSeconds(1));
        service.delete(expiredLease.messageId());
        assertThat(contactCount(expiredLease.messageId())).isZero();
        assertThat(outboxCount(expiredLease.outboxId())).isZero();
        assertAudit(
                expiredLease.messageId(),
                "MESSAGE_DELETE",
                "previousStatus",
                "UNREAD",
                "newStatus",
                "DELETED");
    }

    @Test
    void deleteAuditFailureRestoresBothMessageAndOutbox() {
        Seed seed = insert(
                UUID.randomUUID(), "SPAM", "DEAD", 10, CREATED_AT, null);
        MessageInboxService failing = service(command -> {
            throw new IllegalStateException("private visitor body");
        });

        assertThatThrownBy(() -> failing.delete(seed.messageId()))
                .isExactlyInstanceOf(IllegalStateException.class);

        assertThat(contactCount(seed.messageId())).isOne();
        assertThat(outboxCount(seed.outboxId())).isOne();
        assertThat(auditCount(seed.messageId(), "MESSAGE_DELETE")).isZero();
    }

    private MessageInboxService service(AuditService auditService) {
        return new MessageInboxService(
                () -> adminId,
                repository,
                auditService,
                new TransactionTemplate(transactionManager),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private UUID ensureAdmin() {
        JdbcClient owner = migratorJdbc();
        List<UUID> existing = owner.sql("select id from portfolio.admin_user limit 1")
                .query(UUID.class)
                .list();
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        UUID id = UUID.fromString("70000000-0000-4000-8000-000000000008");
        owner.sql("""
                        insert into portfolio.admin_user(
                            id, username, password_hash, status, totp_key_version,
                            totp_nonce, totp_ciphertext)
                        values (:id, 'publishing-integration-admin', '{noop}test',
                                'ACTIVE', 1, decode(repeat('00', 12), 'hex'),
                                decode('00', 'hex'))
                        """)
                .param("id", id, Types.OTHER)
                .update();
        return id;
    }

    private Seed insert(
            UUID messageId,
            String messageStatus,
            String emailStatus,
            int attempts,
            Instant createdAt,
            String lastErrorSummary) {
        UUID outboxId = UUID.randomUUID();
        String stableMessageId = "<portfolio-contact-" + messageId + "@yychainsaw.xyz>";
        contacts.insert(new ContactMessageRecord(
                messageId,
                "Visitor Sentinel",
                "visitor-sentinel@example.com",
                "Private subject sentinel",
                "Private body sentinel",
                messageStatus,
                messageId.toString().replace("-", "").repeat(2),
                createdAt.minusSeconds(1),
                0,
                createdAt,
                createdAt));
        String leaseOwner = "SENDING".equals(emailStatus) ? "seed-lease" : null;
        Instant leaseUntil = "SENDING".equals(emailStatus)
                ? NOW.plusSeconds(120)
                : null;
        Instant sentAt = "SENT".equals(emailStatus) ? createdAt.plusSeconds(1) : null;
        outbox.insert(new EmailOutboxRecord(
                outboxId,
                messageId,
                TEMPLATE,
                RECIPIENT,
                stableMessageId,
                emailStatus,
                attempts,
                createdAt,
                leaseOwner,
                leaseUntil,
                lastErrorSummary,
                createdAt,
                sentAt,
                createdAt));
        return new Seed(messageId, outboxId, stableMessageId);
    }

    private void setActiveLease(UUID outboxId) {
        setLeaseUntil(outboxId, NOW.plusSeconds(120));
    }

    private void setLeaseUntil(UUID outboxId, Instant leaseUntil) {
        migratorJdbc().sql("""
                        update portfolio.email_outbox
                        set lease_owner='active-delete-race',
                            lease_until=:leaseUntil
                        where id=:outboxId
                        """)
                .param(
                        "leaseUntil",
                        OffsetDateTime.ofInstant(leaseUntil, ZoneOffset.UTC),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .param("outboxId", outboxId, Types.OTHER)
                .update();
    }

    private MessageState messageState(UUID messageId) {
        return migratorJdbc().sql("""
                        select status, version
                        from portfolio.contact_message
                        where id=:messageId
                        """)
                .param("messageId", messageId, Types.OTHER)
                .query((result, row) -> new MessageState(
                        result.getString("status"), result.getInt("version")))
                .single();
    }

    private EmailState emailState(UUID outboxId) {
        return migratorJdbc().sql("""
                        select status, attempts, next_attempt_at,
                               last_error_summary, stable_message_id
                        from portfolio.email_outbox
                        where id=:outboxId
                        """)
                .param("outboxId", outboxId, Types.OTHER)
                .query((result, row) -> new EmailState(
                        result.getString("status"),
                        result.getInt("attempts"),
                        result.getObject("next_attempt_at", OffsetDateTime.class).toInstant(),
                        result.getString("last_error_summary"),
                        result.getString("stable_message_id")))
                .single();
    }

    private void assertAudit(
            UUID messageId,
            String action,
            String firstKey,
            String firstValue,
            String secondKey,
            String secondValue) {
        JdbcClient owner = migratorJdbc();
        assertThat(owner.sql("""
                        select actor_admin_id
                        from portfolio.audit_log
                        where target_id=:targetId and action=:action
                        order by created_at desc
                        limit 1
                        """)
                .param("targetId", messageId.toString(), Types.VARCHAR)
                .param("action", action, Types.VARCHAR)
                .query(UUID.class)
                .single()).isEqualTo(adminId);
        assertThat(owner.sql("""
                        select metadata->>:firstKey
                        from portfolio.audit_log
                        where target_id=:targetId and action=:action
                        order by created_at desc
                        limit 1
                        """)
                .param("firstKey", firstKey, Types.VARCHAR)
                .param("targetId", messageId.toString(), Types.VARCHAR)
                .param("action", action, Types.VARCHAR)
                .query(String.class)
                .single()).isEqualTo(firstValue);
        assertThat(owner.sql("""
                        select metadata->>:secondKey
                        from portfolio.audit_log
                        where target_id=:targetId and action=:action
                        order by created_at desc
                        limit 1
                        """)
                .param("secondKey", secondKey, Types.VARCHAR)
                .param("targetId", messageId.toString(), Types.VARCHAR)
                .param("action", action, Types.VARCHAR)
                .query(String.class)
                .single()).isEqualTo(secondValue);
        assertThat(owner.sql("""
                        select jsonb_exists(metadata, 'createdDate')
                               and not jsonb_exists_any(
                                   metadata,
                                   array[
                                       'visitorName', 'visitorEmail', 'subject', 'body',
                                       'stableMessageId', 'toAddress', 'lastErrorSummary'
                                   ]::text[]
                               )
                        from portfolio.audit_log
                        where target_id=:targetId and action=:action
                        order by created_at desc
                        limit 1
                        """)
                .param("targetId", messageId.toString(), Types.VARCHAR)
                .param("action", action, Types.VARCHAR)
                .query(Boolean.class)
                .single()).isTrue();
    }

    private long auditCount(UUID messageId, String action) {
        return migratorJdbc().sql("""
                        select count(*)
                        from portfolio.audit_log
                        where target_id=:targetId and action=:action
                        """)
                .param("targetId", messageId.toString(), Types.VARCHAR)
                .param("action", action, Types.VARCHAR)
                .query(Long.class)
                .single();
    }

    private long contactCount(UUID messageId) {
        return migratorJdbc().sql(
                        "select count(*) from portfolio.contact_message where id=:id")
                .param("id", messageId, Types.OTHER)
                .query(Long.class)
                .single();
    }

    private long outboxCount(UUID outboxId) {
        return migratorJdbc().sql(
                        "select count(*) from portfolio.email_outbox where id=:id")
                .param("id", outboxId, Types.OTHER)
                .query(Long.class)
                .single();
    }

    private record Seed(UUID messageId, UUID outboxId, String stableMessageId) {
    }

    private record MessageState(String status, int version) {
    }

    private record EmailState(
            String status,
            int attempts,
            Instant nextAttemptAt,
            String lastErrorSummary,
            String stableMessageId) {
    }
}
