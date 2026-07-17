package xyz.yychainsaw.portfolio.message.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class EmailOutboxMapper {
    private static final String COLUMNS = """
            id, contact_message_id, template_name, to_address, stable_message_id,
            status, attempts, next_attempt_at, lease_owner, lease_until,
            last_error_summary, created_at, sent_at, updated_at
            """;
    private static final RowMapper<EmailOutboxRecord> ROW_MAPPER =
            EmailOutboxMapper::map;

    private final JdbcClient jdbc;

    public EmailOutboxMapper(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc is required");
    }

    public int insert(EmailOutboxRecord record) {
        Objects.requireNonNull(record, "email outbox record is required");
        return jdbc.sql("""
                        insert into portfolio.email_outbox(
                            id, contact_message_id, template_name, to_address,
                            stable_message_id, status, attempts, next_attempt_at,
                            lease_owner, lease_until, last_error_summary, created_at,
                            sent_at, updated_at
                        ) values (
                            :id, :contactMessageId, :templateName, :toAddress,
                            :stableMessageId, :status, :attempts, :nextAttemptAt,
                            :leaseOwner, :leaseUntil, :lastErrorSummary, :createdAt,
                            :sentAt, :updatedAt
                        )
                        """)
                .param("id", record.id(), Types.OTHER)
                .param("contactMessageId", record.contactMessageId(), Types.OTHER)
                .param("templateName", record.templateName(), Types.VARCHAR)
                .param("toAddress", record.toAddress(), Types.VARCHAR)
                .param("stableMessageId", record.stableMessageId(), Types.VARCHAR)
                .param("status", record.status(), Types.VARCHAR)
                .param("attempts", record.attempts(), Types.INTEGER)
                .param(
                        "nextAttemptAt",
                        toOffsetDateTime(record.nextAttemptAt()),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .param("leaseOwner", record.leaseOwner(), Types.VARCHAR)
                .param(
                        "leaseUntil",
                        toOffsetDateTime(record.leaseUntil()),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .param("lastErrorSummary", record.lastErrorSummary(), Types.VARCHAR)
                .param(
                        "createdAt",
                        toOffsetDateTime(record.createdAt()),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .param(
                        "sentAt",
                        toOffsetDateTime(record.sentAt()),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .param(
                        "updatedAt",
                        toOffsetDateTime(record.updatedAt()),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    public List<EmailOutboxRecord> findByMessageId(UUID messageId) {
        Objects.requireNonNull(messageId, "contact message id is required");
        return jdbc.sql("select " + COLUMNS
                        + " from portfolio.email_outbox where contact_message_id=:messageId")
                .param("messageId", messageId, Types.OTHER)
                .query(ROW_MAPPER)
                .list();
    }

    public Optional<EmailOutboxRecord> findById(UUID id) {
        Objects.requireNonNull(id, "email outbox id is required");
        return jdbc.sql("select " + COLUMNS
                        + " from portfolio.email_outbox where id=:id")
                .param("id", id, Types.OTHER)
                .query(ROW_MAPPER)
                .optional();
    }

    public EmailOutboxRecord require(UUID id) {
        return findById(id).orElseThrow(() ->
                new IllegalStateException("email outbox row was not found"));
    }

    public long count() {
        return jdbc.sql("select count(*) from portfolio.email_outbox")
                .query(Long.class)
                .single();
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant nullableInstant(ResultSet resultSet, String column)
            throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static EmailOutboxRecord map(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new EmailOutboxRecord(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("contact_message_id", UUID.class),
                resultSet.getString("template_name"),
                resultSet.getString("to_address"),
                resultSet.getString("stable_message_id"),
                resultSet.getString("status"),
                resultSet.getInt("attempts"),
                resultSet.getObject("next_attempt_at", OffsetDateTime.class).toInstant(),
                resultSet.getString("lease_owner"),
                nullableInstant(resultSet, "lease_until"),
                resultSet.getString("last_error_summary"),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                nullableInstant(resultSet, "sent_at"),
                resultSet.getObject("updated_at", OffsetDateTime.class).toInstant());
    }
}
