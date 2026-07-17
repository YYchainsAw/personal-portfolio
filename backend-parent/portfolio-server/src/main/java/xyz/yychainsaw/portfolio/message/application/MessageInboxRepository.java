package xyz.yychainsaw.portfolio.message.application;

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
public class MessageInboxRepository {
    private static final int MAXIMUM_FETCH_LIMIT = 101;
    private static final int MAXIMUM_EMAIL_ATTEMPTS = Integer.MAX_VALUE;
    private static final String SUMMARY_COLUMNS = """
            message.id as message_id,
            message.visitor_name,
            message.visitor_email,
            message.subject,
            message.status as message_status,
            message.version,
            message.created_at as message_created_at,
            outbox.status as email_status
            """;
    private static final String DETAIL_COLUMNS = """
            message.id as message_id,
            message.visitor_name,
            message.visitor_email,
            message.subject,
            message.body,
            message.status as message_status,
            message.privacy_accepted_at,
            message.version,
            message.created_at as message_created_at,
            message.updated_at as message_updated_at,
            outbox.status as email_status,
            outbox.attempts as email_attempts,
            outbox.next_attempt_at as email_next_attempt_at,
            outbox.sent_at as email_sent_at,
            outbox.updated_at as email_updated_at,
            outbox.last_error_summary
            """;
    private static final String STATUS_COLUMNS = """
            message.id as message_id,
            message.status as message_status,
            message.version,
            message.created_at as message_created_at
            """;
    private static final String MUTATION_COLUMNS = """
            message.id as message_id,
            message.status as message_status,
            message.created_at as message_created_at,
            outbox.status as email_status,
            outbox.attempts as email_attempts,
            outbox.lease_until as email_lease_until
            """;
    private static final RowMapper<InboxMessageSummaryRow> SUMMARY_ROW_MAPPER =
            MessageInboxRepository::mapSummary;
    private static final RowMapper<InboxMessageRow> DETAIL_ROW_MAPPER =
            MessageInboxRepository::mapDetail;
    private static final RowMapper<InboxMessageStatusRow> STATUS_ROW_MAPPER =
            MessageInboxRepository::mapStatus;
    private static final RowMapper<InboxMessageMutationRow> MUTATION_ROW_MAPPER =
            MessageInboxRepository::mapMutation;

    private final JdbcClient jdbc;

    public MessageInboxRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc is required");
    }

    List<InboxMessageSummaryRow> findPage(
            MessageStatus status, MessageCursor cursor, int fetchLimit) {
        if (fetchLimit < 2 || fetchLimit > MAXIMUM_FETCH_LIMIT) {
            throw new IllegalArgumentException("message fetch limit is invalid");
        }
        StringBuilder sql = new StringBuilder("select ")
                .append(SUMMARY_COLUMNS)
                .append("""
                         from portfolio.contact_message message
                         join portfolio.email_outbox outbox
                           on outbox.contact_message_id=message.id
                         where 1=1
                        """);
        if (status != null) {
            sql.append(" and message.status=:status");
        }
        if (cursor != null) {
            sql.append(" and (message.created_at<:cursorAt"
                    + " or (message.created_at=:cursorAt and message.id<:cursorId))");
        }
        sql.append(" order by message.created_at desc, message.id desc limit :fetchLimit");

        JdbcClient.StatementSpec statement = jdbc.sql(sql.toString());
        if (status != null) {
            statement = statement.param("status", status.name(), Types.VARCHAR);
        }
        if (cursor != null) {
            statement = statement
                    .param(
                            "cursorAt",
                            utc(cursor.createdAt()),
                            Types.TIMESTAMP_WITH_TIMEZONE)
                    .param("cursorId", cursor.id(), Types.OTHER);
        }
        return List.copyOf(statement
                .param("fetchLimit", fetchLimit, Types.INTEGER)
                .query(SUMMARY_ROW_MAPPER)
                .list());
    }

    Optional<InboxMessageRow> findById(UUID messageId) {
        Objects.requireNonNull(messageId, "contact message id is required");
        return jdbc.sql("select " + DETAIL_COLUMNS + """
                         from portfolio.contact_message message
                         join portfolio.email_outbox outbox
                           on outbox.contact_message_id=message.id
                         where message.id=:messageId
                        """)
                .param("messageId", messageId, Types.OTHER)
                .query(DETAIL_ROW_MAPPER)
                .optional();
    }

    Optional<InboxMessageStatusRow> findStatusById(UUID messageId) {
        Objects.requireNonNull(messageId, "contact message id is required");
        return jdbc.sql("select " + STATUS_COLUMNS + """
                         from portfolio.contact_message message
                         where message.id=:messageId
                        """)
                .param("messageId", messageId, Types.OTHER)
                .query(STATUS_ROW_MAPPER)
                .optional();
    }

    Optional<InboxMessageMutationRow> lockMutationById(UUID messageId) {
        Objects.requireNonNull(messageId, "contact message id is required");
        return jdbc.sql("select " + MUTATION_COLUMNS + """
                         from portfolio.contact_message message
                         join portfolio.email_outbox outbox
                           on outbox.contact_message_id=message.id
                         where message.id=:messageId
                         for update of message, outbox
                        """)
                .param("messageId", messageId, Types.OTHER)
                .query(MUTATION_ROW_MAPPER)
                .optional();
    }

    int updateStatus(
            UUID messageId,
            MessageStatus status,
            int expectedVersion,
            Instant updatedAt) {
        Objects.requireNonNull(messageId, "contact message id is required");
        Objects.requireNonNull(status, "contact message status is required");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("contact message version is invalid");
        }
        return jdbc.sql("""
                        update portfolio.contact_message
                        set status=:status,
                            version=version + 1,
                            updated_at=:updatedAt
                        where id=:messageId
                          and version=:expectedVersion
                        """)
                .param("status", status.name(), Types.VARCHAR)
                .param("updatedAt", utc(updatedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("messageId", messageId, Types.OTHER)
                .param("expectedVersion", expectedVersion, Types.INTEGER)
                .update();
    }

    int retryEmail(UUID messageId, String expectedStatus, Instant nextAttemptAt) {
        Objects.requireNonNull(messageId, "contact message id is required");
        if (!"FAILED".equals(expectedStatus) && !"DEAD".equals(expectedStatus)) {
            throw new IllegalArgumentException("email retry status is invalid");
        }
        return jdbc.sql("""
                        update portfolio.email_outbox
                        set status='PENDING',
                            next_attempt_at=:nextAttemptAt,
                            lease_owner=null,
                            lease_until=null,
                            last_error_summary=null,
                            sent_at=null,
                            updated_at=:nextAttemptAt
                        where contact_message_id=:messageId
                          and status=:expectedStatus
                          and attempts < :maximumAttempts
                        """)
                .param(
                        "nextAttemptAt",
                        utc(nextAttemptAt),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .param("messageId", messageId, Types.OTHER)
                .param("expectedStatus", expectedStatus, Types.VARCHAR)
                .param("maximumAttempts", MAXIMUM_EMAIL_ATTEMPTS, Types.INTEGER)
                .update();
    }

    int delete(UUID messageId) {
        Objects.requireNonNull(messageId, "contact message id is required");
        return jdbc.sql("delete from portfolio.contact_message where id=:messageId")
                .param("messageId", messageId, Types.OTHER)
                .update();
    }

    private static InboxMessageSummaryRow mapSummary(
            ResultSet result, int rowNumber) throws SQLException {
        return new InboxMessageSummaryRow(
                result.getObject("message_id", UUID.class),
                result.getString("visitor_name"),
                result.getString("visitor_email"),
                result.getString("subject"),
                MessageStatus.valueOf(result.getString("message_status")),
                result.getString("email_status"),
                instant(result, "message_created_at"),
                result.getInt("version"));
    }

    private static InboxMessageRow mapDetail(ResultSet result, int rowNumber)
            throws SQLException {
        return new InboxMessageRow(
                result.getObject("message_id", UUID.class),
                result.getString("visitor_name"),
                result.getString("visitor_email"),
                result.getString("subject"),
                result.getString("body"),
                MessageStatus.valueOf(result.getString("message_status")),
                result.getString("email_status"),
                result.getInt("email_attempts"),
                instant(result, "email_next_attempt_at"),
                nullableInstant(result, "email_sent_at"),
                instant(result, "email_updated_at"),
                result.getString("last_error_summary"),
                instant(result, "privacy_accepted_at"),
                instant(result, "message_created_at"),
                instant(result, "message_updated_at"),
                result.getInt("version"));
    }

    private static InboxMessageStatusRow mapStatus(
            ResultSet result, int rowNumber) throws SQLException {
        return new InboxMessageStatusRow(
                result.getObject("message_id", UUID.class),
                MessageStatus.valueOf(result.getString("message_status")),
                instant(result, "message_created_at"),
                result.getInt("version"));
    }

    private static InboxMessageMutationRow mapMutation(
            ResultSet result, int rowNumber) throws SQLException {
        return new InboxMessageMutationRow(
                result.getObject("message_id", UUID.class),
                MessageStatus.valueOf(result.getString("message_status")),
                result.getString("email_status"),
                result.getInt("email_attempts"),
                nullableInstant(result, "email_lease_until"),
                instant(result, "message_created_at"));
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        return Objects.requireNonNull(
                        result.getObject(column, OffsetDateTime.class),
                        "stored message timestamp is required")
                .toInstant();
    }

    private static Instant nullableInstant(ResultSet result, String column)
            throws SQLException {
        OffsetDateTime value = result.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant value) {
        return OffsetDateTime.ofInstant(
                Objects.requireNonNull(value, "message timestamp is required"),
                ZoneOffset.UTC);
    }

    record InboxMessageRow(
            UUID id,
            String visitorName,
            String visitorEmail,
            String subject,
            String body,
            MessageStatus status,
            String emailStatus,
            int emailAttempts,
            Instant emailNextAttemptAt,
            Instant emailSentAt,
            Instant emailUpdatedAt,
            String lastErrorSummary,
            Instant privacyAcceptedAt,
            Instant createdAt,
            Instant updatedAt,
            int version) {
        @Override
        public String toString() {
            return "InboxMessageRow[id=" + id
                    + ", status=" + status
                    + ", emailStatus=" + emailStatus
                    + ", version=" + version
                    + ", pii=<redacted>]";
        }
    }

    record InboxMessageSummaryRow(
            UUID id,
            String visitorName,
            String visitorEmail,
            String subject,
            MessageStatus status,
            String emailStatus,
            Instant createdAt,
            int version) {
        @Override
        public String toString() {
            return "InboxMessageSummaryRow[id=" + id
                    + ", status=" + status
                    + ", emailStatus=" + emailStatus
                    + ", createdAt=" + createdAt
                    + ", version=" + version
                    + ", pii=<redacted>]";
        }
    }

    record InboxMessageStatusRow(
            UUID id,
            MessageStatus status,
            Instant createdAt,
            int version) {
    }

    record InboxMessageMutationRow(
            UUID id,
            MessageStatus status,
            String emailStatus,
            int emailAttempts,
            Instant emailLeaseUntil,
            Instant createdAt) {
    }
}
