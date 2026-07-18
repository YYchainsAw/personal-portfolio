package xyz.yychainsaw.portfolio.message.email;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class EmailOutboxRepository {
    private static final int TRANSACTION_TIMEOUT_SECONDS = 5;
    private static final int MAXIMUM_BATCH_SIZE = 100;
    private static final int MAXIMUM_ATTEMPTS = 10;
    private static final int MAXIMUM_PERSISTED_ATTEMPTS = Integer.MAX_VALUE;
    private static final Pattern LEASE_OWNER = Pattern.compile("[!-~]{1,120}");
    private static final Pattern SAFE_ERROR_SUMMARY =
            Pattern.compile("[A-Za-z0-9_$.-]{1,160}\\|[A-Z][A-Z0-9_]{0,79}");
    private static final String EXPIRED_SUMMARY = "LeaseExpired|DELIVERY_INTERRUPTED";
    private static final RowMapper<LeasedEmail> LEASED_EMAIL_MAPPER =
            EmailOutboxRepository::mapLeasedEmail;

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    public EmailOutboxRepository(
            JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc is required");
        this.transaction = transactionTemplate(transactionManager);
    }

    public List<LeasedEmail> recoverExpiredAndClaim(
            String leaseOwner,
            Instant now,
            Duration leaseDuration,
            int limit) {
        requireLeaseOwner(leaseOwner);
        Instant claimedAt = toDatabaseInstant(now, "claim timestamp is required");
        if (leaseDuration == null
                || leaseDuration.isZero()
                || leaseDuration.isNegative()
                || leaseDuration.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("email lease duration is invalid");
        }
        if (limit < 1 || limit > MAXIMUM_BATCH_SIZE) {
            throw new IllegalArgumentException("email claim limit is invalid");
        }
        Instant leaseUntil;
        try {
            leaseUntil = claimedAt.plus(leaseDuration).truncatedTo(ChronoUnit.MICROS);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("email lease duration is invalid");
        }

        List<LeasedEmail> claimed = transaction.execute(status -> {
            recoverExpired(claimedAt, limit);
            return claim(leaseOwner, claimedAt, leaseUntil, limit);
        });
        return claimed == null ? List.of() : List.copyOf(claimed);
    }

    public boolean renewLease(
            UUID outboxId,
            String leaseOwner,
            int attempts,
            Instant leaseUntil) {
        requireIdentity(outboxId, leaseOwner, attempts);
        Instant normalizedLeaseUntil =
                toDatabaseInstant(leaseUntil, "email lease timestamp is required");
        Integer updated = transaction.execute(status -> jdbc.sql("""
                        update portfolio.email_outbox
                        set lease_until=:leaseUntil,
                            updated_at=clock_timestamp()
                        where id=:id
                          and status='SENDING'
                          and lease_owner=:leaseOwner
                          and attempts=:attempts
                        """)
                .param("leaseUntil", toOffsetDateTime(normalizedLeaseUntil), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("id", outboxId, Types.OTHER)
                .param("leaseOwner", leaseOwner, Types.VARCHAR)
                .param("attempts", attempts, Types.INTEGER)
                .update());
        return updated != null && updated == 1;
    }

    public boolean markSent(
            UUID outboxId,
            String leaseOwner,
            int attempts,
            Instant sentAt) {
        requireIdentity(outboxId, leaseOwner, attempts);
        Instant normalizedSentAt = toDatabaseInstant(sentAt, "email sent timestamp is required");
        Integer updated = transaction.execute(status -> jdbc.sql("""
                        update portfolio.email_outbox
                        set status='SENT',
                            lease_owner=null,
                            lease_until=null,
                            last_error_summary=null,
                            sent_at=:sentAt,
                            updated_at=:sentAt
                        where id=:id
                          and status='SENDING'
                          and lease_owner=:leaseOwner
                          and attempts=:attempts
                        """)
                .param("sentAt", toOffsetDateTime(normalizedSentAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("id", outboxId, Types.OTHER)
                .param("leaseOwner", leaseOwner, Types.VARCHAR)
                .param("attempts", attempts, Types.INTEGER)
                .update());
        return updated != null && updated == 1;
    }

    public boolean markFailed(
            UUID outboxId,
            String leaseOwner,
            int attempts,
            Instant nextAttemptAt,
            String safeSummary) {
        requireIdentity(outboxId, leaseOwner, attempts);
        Instant normalizedNextAttempt =
                toDatabaseInstant(nextAttemptAt, "email retry timestamp is required");
        if (safeSummary == null || !SAFE_ERROR_SUMMARY.matcher(safeSummary).matches()) {
            throw new IllegalArgumentException("email error summary is invalid");
        }
        Integer updated = transaction.execute(status -> jdbc.sql("""
                        update portfolio.email_outbox
                        set status=case when attempts >= :maximumAttempts
                                        then 'DEAD' else 'FAILED' end,
                            next_attempt_at=:nextAttemptAt,
                            lease_owner=null,
                            lease_until=null,
                            last_error_summary=:safeSummary,
                            sent_at=null,
                            updated_at=clock_timestamp()
                        where id=:id
                          and status='SENDING'
                          and lease_owner=:leaseOwner
                          and attempts=:attempts
                        """)
                .param("maximumAttempts", MAXIMUM_ATTEMPTS, Types.INTEGER)
                .param("nextAttemptAt", toOffsetDateTime(normalizedNextAttempt), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("safeSummary", safeSummary, Types.VARCHAR)
                .param("id", outboxId, Types.OTHER)
                .param("leaseOwner", leaseOwner, Types.VARCHAR)
                .param("attempts", attempts, Types.INTEGER)
                .update());
        return updated != null && updated == 1;
    }

    private int recoverExpired(Instant now, int limit) {
        return jdbc.sql("""
                        with expired as (
                            select outbox.id
                            from portfolio.email_outbox outbox
                            where outbox.status='SENDING'
                              and outbox.lease_until < :now
                            order by outbox.lease_until, outbox.created_at, outbox.id
                            for update skip locked
                            limit :limit
                        )
                        update portfolio.email_outbox outbox
                        set status=case when outbox.attempts >= :maximumAttempts
                                        then 'DEAD' else 'FAILED' end,
                            next_attempt_at=case
                                when outbox.attempts >= :maximumAttempts then :now
                                else least(outbox.next_attempt_at, :now)
                            end,
                            lease_owner=null,
                            lease_until=null,
                            last_error_summary=:summary,
                            sent_at=null,
                            updated_at=:now
                        from expired
                        where outbox.id=expired.id
                        """)
                .param("maximumAttempts", MAXIMUM_ATTEMPTS, Types.INTEGER)
                .param("now", toOffsetDateTime(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("limit", limit, Types.INTEGER)
                .param("summary", EXPIRED_SUMMARY, Types.VARCHAR)
                .update();
    }

    private List<LeasedEmail> claim(
            String leaseOwner,
            Instant now,
            Instant leaseUntil,
            int limit) {
        return jdbc.sql("""
                        with candidates as (
                            select outbox.id
                            from portfolio.email_outbox outbox
                            where (
                                  (outbox.status='PENDING'
                                   and outbox.attempts < :maximumPersistedAttempts)
                                  or
                                  (outbox.status='FAILED'
                                   and outbox.attempts < :maximumAttempts)
                              )
                              and outbox.next_attempt_at <= :now
                              and (outbox.lease_until is null or outbox.lease_until < :now)
                            order by outbox.next_attempt_at, outbox.created_at, outbox.id
                            for update skip locked
                            limit :limit
                        ), leased as (
                            update portfolio.email_outbox outbox
                            set status='SENDING',
                                attempts=outbox.attempts + 1,
                                lease_owner=:leaseOwner,
                                lease_until=:leaseUntil,
                                last_error_summary=null,
                                updated_at=:now
                            from candidates
                            where outbox.id=candidates.id
                            returning outbox.id,
                                      outbox.contact_message_id,
                                      outbox.template_name,
                                      outbox.to_address,
                                      outbox.stable_message_id,
                                      outbox.attempts,
                                      outbox.lease_owner,
                                      outbox.next_attempt_at,
                                      outbox.created_at
                        )
                        select leased.id as outbox_id,
                               leased.template_name,
                               leased.to_address,
                               leased.stable_message_id,
                               leased.attempts,
                               leased.lease_owner,
                               leased.next_attempt_at,
                               leased.created_at as outbox_created_at,
                               message.visitor_name,
                               message.visitor_email,
                               message.subject,
                               message.body,
                               message.created_at as received_at
                        from leased
                        join portfolio.contact_message message
                          on message.id=leased.contact_message_id
                        order by leased.next_attempt_at, leased.created_at, leased.id
                        """)
                .param("maximumAttempts", MAXIMUM_ATTEMPTS, Types.INTEGER)
                .param(
                        "maximumPersistedAttempts",
                        MAXIMUM_PERSISTED_ATTEMPTS,
                        Types.INTEGER)
                .param("now", toOffsetDateTime(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("limit", limit, Types.INTEGER)
                .param("leaseOwner", leaseOwner, Types.VARCHAR)
                .param("leaseUntil", toOffsetDateTime(leaseUntil), Types.TIMESTAMP_WITH_TIMEZONE)
                .query(LEASED_EMAIL_MAPPER)
                .list();
    }

    private static LeasedEmail mapLeasedEmail(ResultSet resultSet, int rowNumber)
            throws SQLException {
        UUID outboxId = resultSet.getObject("outbox_id", UUID.class);
        ContactNotification notification = new ContactNotification(
                outboxId,
                resultSet.getString("stable_message_id"),
                resultSet.getString("to_address"),
                resultSet.getString("visitor_email"),
                resultSet.getString("visitor_name"),
                resultSet.getString("subject"),
                resultSet.getString("body"),
                resultSet.getObject("received_at", OffsetDateTime.class).toInstant());
        return new LeasedEmail(
                resultSet.getString("lease_owner"),
                resultSet.getInt("attempts"),
                resultSet.getString("template_name"),
                notification);
    }

    private static TransactionTemplate transactionTemplate(
            PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transaction manager is required"));
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setTimeout(TRANSACTION_TIMEOUT_SECONDS);
        return template;
    }

    private static void requireIdentity(
            UUID outboxId, String leaseOwner, int attempts) {
        Objects.requireNonNull(outboxId, "email outbox id is required");
        requireLeaseOwner(leaseOwner);
        if (attempts < 1) {
            throw new IllegalArgumentException("email lease attempts are invalid");
        }
    }

    private static void requireLeaseOwner(String value) {
        if (value == null || !LEASE_OWNER.matcher(value).matches()) {
            throw new IllegalArgumentException("email lease owner is invalid");
        }
    }

    private static Instant toDatabaseInstant(Instant value, String message) {
        return Objects.requireNonNull(value, message).truncatedTo(ChronoUnit.MICROS);
    }

    private static OffsetDateTime toOffsetDateTime(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
