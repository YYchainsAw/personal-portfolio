package xyz.yychainsaw.portfolio.message.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
public class ContactMessageMapper {
    private static final Pattern DEDUPE_KEY = Pattern.compile("[0-9a-f]{64}");
    private static final String COLUMNS = """
            id, visitor_name, visitor_email, subject, body, status, dedupe_key,
            privacy_accepted_at, version, created_at, updated_at
            """;
    private static final RowMapper<ContactMessageRecord> ROW_MAPPER =
            ContactMessageMapper::map;

    private final JdbcClient jdbc;

    public ContactMessageMapper(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc is required");
    }

    public void acquireDedupeLock(String dedupeKey) {
        requireDedupeKey(dedupeKey);
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("contact dedupe lock requires a transaction");
        }
        jdbc.sql("select pg_catalog.set_config('lock_timeout', '2s', true)")
                .query(String.class)
                .single();
        long lockKey = Long.parseUnsignedLong(dedupeKey.substring(0, 16), 16);
        jdbc.sql("select 1 from (select pg_catalog.pg_advisory_xact_lock(:lockKey)) locked")
                .param("lockKey", lockKey, Types.BIGINT)
                .query(Integer.class)
                .single();
    }

    public boolean existsByDedupeKeySince(String dedupeKey, Instant since) {
        requireDedupeKey(dedupeKey);
        Objects.requireNonNull(since, "contact dedupe timestamp is required");
        return jdbc.sql("""
                        select exists (
                            select 1
                            from portfolio.contact_message
                            where dedupe_key=:dedupeKey
                              and created_at >= :since
                        )
                        """)
                .param("dedupeKey", dedupeKey, Types.CHAR)
                .param(
                        "since",
                        OffsetDateTime.ofInstant(since, ZoneOffset.UTC),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .query(Boolean.class)
                .single();
    }

    public int insert(ContactMessageRecord record) {
        Objects.requireNonNull(record, "contact message record is required");
        return jdbc.sql("""
                        insert into portfolio.contact_message(
                            id, visitor_name, visitor_email, subject, body, status,
                            dedupe_key, privacy_accepted_at, version, created_at, updated_at
                        ) values (
                            :id, :visitorName, :visitorEmail, :subject, :body, :status,
                            :dedupeKey, :privacyAcceptedAt, :version, :createdAt, :updatedAt
                        )
                        """)
                .param("id", record.id(), Types.OTHER)
                .param("visitorName", record.visitorName(), Types.VARCHAR)
                .param("visitorEmail", record.visitorEmail(), Types.VARCHAR)
                .param("subject", record.subject(), Types.VARCHAR)
                .param("body", record.body(), Types.VARCHAR)
                .param("status", record.status(), Types.VARCHAR)
                .param("dedupeKey", record.dedupeKey(), Types.CHAR)
                .param(
                        "privacyAcceptedAt",
                        toOffsetDateTime(record.privacyAcceptedAt()),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .param("version", record.version(), Types.INTEGER)
                .param(
                        "createdAt",
                        toOffsetDateTime(record.createdAt()),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .param(
                        "updatedAt",
                        toOffsetDateTime(record.updatedAt()),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    public Optional<ContactMessageRecord> findById(UUID id) {
        Objects.requireNonNull(id, "contact message id is required");
        return jdbc.sql("select " + COLUMNS
                        + " from portfolio.contact_message where id=:id")
                .param("id", id, Types.OTHER)
                .query(ROW_MAPPER)
                .optional();
    }

    public long count() {
        return jdbc.sql("select count(*) from portfolio.contact_message")
                .query(Long.class)
                .single();
    }

    private static void requireDedupeKey(String dedupeKey) {
        if (dedupeKey == null || !DEDUPE_KEY.matcher(dedupeKey).matches()) {
            throw new IllegalArgumentException("contact message dedupe key is invalid");
        }
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static ContactMessageRecord map(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new ContactMessageRecord(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("visitor_name"),
                resultSet.getString("visitor_email"),
                resultSet.getString("subject"),
                resultSet.getString("body"),
                resultSet.getString("status"),
                resultSet.getString("dedupe_key"),
                resultSet.getObject("privacy_accepted_at", OffsetDateTime.class).toInstant(),
                resultSet.getInt("version"),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("updated_at", OffsetDateTime.class).toInstant());
    }
}
