package xyz.yychainsaw.portfolio.auth.session;

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
public class AdminSessionRepository {
    private static final RowMapper<SessionRow> SESSION_ROW_MAPPER =
            AdminSessionRepository::mapSessionRow;
    private static final RowMapper<TerminalSession> TERMINAL_SESSION_MAPPER =
            AdminSessionRepository::mapTerminalSession;

    private final JdbcClient jdbc;

    public AdminSessionRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc is required");
    }

    public Optional<String> findPrimaryIdByPublicSessionId(String publicSessionId) {
        if (!isCanonicalSessionId(publicSessionId)) {
            return Optional.empty();
        }
        return jdbc.sql("""
                        select primary_id
                        from portfolio.spring_session
                        where session_id=:sessionId
                        """)
                .param("sessionId", publicSessionId)
                .query(String.class)
                .optional();
    }

    public UUID insertActive(
            UUID adminId, String primaryId, String summary, Instant createdAt) {
        Objects.requireNonNull(adminId, "admin id is required");
        requireStablePrimaryId(primaryId);
        requireClientSummary(summary);
        Objects.requireNonNull(createdAt, "created timestamp is required");
        UUID id = UUID.randomUUID();
        OffsetDateTime timestamp = toOffsetDateTime(createdAt);
        int changed = jdbc.sql("""
                        insert into portfolio.admin_session_metadata
                            (id, admin_id, session_primary_id, status, created_at,
                             last_activity_at, client_summary)
                        values (:id, :adminId, :primaryId, 'ACTIVE', :createdAt,
                                :lastActivityAt, :summary)
                        """)
                .param("id", id)
                .param("adminId", adminId)
                .param("primaryId", primaryId)
                .param("createdAt", timestamp, Types.TIMESTAMP_WITH_TIMEZONE)
                .param("lastActivityAt", timestamp, Types.TIMESTAMP_WITH_TIMEZONE)
                .param("summary", summary)
                .update();
        requireOne(changed, "session metadata insert affected an unexpected number of rows");
        return id;
    }

    public Optional<SessionRow> findByPublicSessionId(String publicSessionId) {
        if (!isCanonicalSessionId(publicSessionId)) {
            return Optional.empty();
        }
        return jdbc.sql("""
                        select m.id, m.admin_id, m.session_primary_id, m.status, m.created_at,
                               m.last_activity_at metadata_last_activity_at,
                               s.last_access_time, s.expiry_time
                        from portfolio.spring_session s
                        join portfolio.admin_session_metadata m
                          on m.session_primary_id=s.primary_id
                        where s.session_id=:sessionId
                        """)
                .param("sessionId", publicSessionId)
                .query(SESSION_ROW_MAPPER)
                .optional();
    }

    public Optional<SessionRow> findByMetadataId(UUID id, UUID adminId) {
        Objects.requireNonNull(id, "metadata id is required");
        Objects.requireNonNull(adminId, "admin id is required");
        return jdbc.sql("""
                        select m.id, m.admin_id, m.session_primary_id, m.status, m.created_at,
                               m.last_activity_at metadata_last_activity_at,
                               s.last_access_time,
                               coalesce(s.expiry_time, 0) expiry_time
                        from portfolio.admin_session_metadata m
                        left join portfolio.spring_session s
                          on s.primary_id=m.session_primary_id
                        where m.id=:id and m.admin_id=:adminId
                        """)
                .param("id", id)
                .param("adminId", adminId)
                .query(SESSION_ROW_MAPPER)
                .optional();
    }

    public List<SessionView> list(UUID adminId, String currentPublicSessionId) {
        Objects.requireNonNull(adminId, "admin id is required");
        if (!isCanonicalSessionId(currentPublicSessionId)) {
            throw new IllegalArgumentException("public session id is invalid");
        }
        List<SessionView> rows = jdbc.sql("""
                        select m.id, m.status, m.created_at, m.ended_at, m.client_summary,
                               m.revocation_reason,
                               m.last_activity_at metadata_last_activity_at,
                               s.last_access_time,
                               coalesce(s.session_id=:currentSessionId, false) is_current
                        from portfolio.admin_session_metadata m
                        left join portfolio.spring_session s
                          on s.primary_id=m.session_primary_id
                        where m.admin_id=:adminId
                        order by m.created_at desc, m.id desc
                        """)
                .param("adminId", adminId)
                .param("currentSessionId", currentPublicSessionId)
                .query((resultSet, rowNumber) -> new SessionView(
                        resultSet.getObject("id", UUID.class),
                        AdminSessionStatus.valueOf(resultSet.getString("status")),
                        instant(resultSet, "created_at"),
                        nullableInstant(resultSet, "ended_at"),
                        lastAccessMillis(resultSet),
                        resultSet.getString("client_summary"),
                        resultSet.getString("revocation_reason"),
                        resultSet.getBoolean("is_current")))
                .list();
        return List.copyOf(rows);
    }

    public Optional<TerminalSession> markRevoked(
            UUID id, UUID adminId, String reason, Instant now) {
        Objects.requireNonNull(id, "metadata id is required");
        Objects.requireNonNull(adminId, "admin id is required");
        requireReason(reason);
        Objects.requireNonNull(now, "revocation timestamp is required");
        return jdbc.sql("""
                        update portfolio.admin_session_metadata m
                        set status='REVOKED',
                            ended_at=:now,
                            last_activity_at=coalesce(
                                (select to_timestamp(s.last_access_time / 1000.0)
                                 from portfolio.spring_session s
                                 where s.primary_id=m.session_primary_id),
                                m.last_activity_at),
                            revocation_reason=:reason,
                            version=m.version+1
                        where m.id=:id and m.admin_id=:adminId and m.status='ACTIVE'
                        returning m.id, m.admin_id, m.session_primary_id, m.revocation_reason
                        """)
                .param("now", toOffsetDateTime(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("reason", reason)
                .param("id", id)
                .param("adminId", adminId)
                .query(TERMINAL_SESSION_MAPPER)
                .optional();
    }

    public List<TerminalSession> expireDue(Instant now, Instant absoluteCutoff) {
        Objects.requireNonNull(now, "expiry timestamp is required");
        Objects.requireNonNull(absoluteCutoff, "absolute cutoff is required");
        List<TerminalSession> expired = jdbc.sql("""
                        with claimed as (
                            select m.id,
                                   s.last_access_time,
                                   case
                                       when m.session_primary_id is null or s.primary_id is null
                                           then 'SESSION_MISSING'
                                       when s.expiry_time <= :nowMillis
                                           then 'IDLE_TIMEOUT'
                                       else 'ABSOLUTE_TIMEOUT'
                                   end reason
                            from portfolio.admin_session_metadata m
                            left join portfolio.spring_session s
                              on s.primary_id=m.session_primary_id
                            where m.status='ACTIVE'
                              and (m.session_primary_id is null
                                   or s.primary_id is null
                                   or s.expiry_time <= :nowMillis
                                   or m.created_at <= :absoluteCutoff)
                            order by m.id
                            for update of m skip locked
                        ), updated as (
                            update portfolio.admin_session_metadata m
                            set status='EXPIRED',
                                ended_at=:now,
                                last_activity_at=case
                                    when c.last_access_time is null then m.last_activity_at
                                    else to_timestamp(c.last_access_time / 1000.0)
                                end,
                                revocation_reason=c.reason,
                                version=m.version+1
                            from claimed c
                            where m.id=c.id and m.status='ACTIVE'
                            returning m.id, m.admin_id, m.session_primary_id,
                                      m.revocation_reason
                        )
                        select id, admin_id, session_primary_id, revocation_reason
                        from updated
                        order by id
                        """)
                .param("nowMillis", now.toEpochMilli())
                .param("absoluteCutoff", toOffsetDateTime(absoluteCutoff),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .param("now", toOffsetDateTime(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .query(TERMINAL_SESSION_MAPPER)
                .list();
        return List.copyOf(expired);
    }

    public List<TerminalSession> terminalSessionsStillLinked() {
        List<TerminalSession> terminal = jdbc.sql("""
                        select id, admin_id, session_primary_id, revocation_reason
                        from portfolio.admin_session_metadata
                        where status in ('REVOKED','EXPIRED')
                          and session_primary_id is not null
                        order by created_at, id
                        """)
                .query(TERMINAL_SESSION_MAPPER)
                .list();
        return List.copyOf(terminal);
    }

    public int deleteSpringSession(String primaryId) {
        requireStablePrimaryId(primaryId);
        int changed = jdbc.sql("""
                        delete from portfolio.spring_session where primary_id=:primaryId
                        """)
                .param("primaryId", primaryId)
                .update();
        if (changed < 0 || changed > 1) {
            throw new IllegalStateException(
                    "Spring Session deletion affected an unexpected number of rows");
        }
        return changed;
    }

    public int deleteExpiredUnmanagedSpringSessions(long nowMillis) {
        return jdbc.sql("""
                        delete from portfolio.spring_session s
                        where s.expiry_time <= :nowMillis
                          and not exists (
                              select 1 from portfolio.admin_session_metadata m
                              where m.session_primary_id=s.primary_id)
                        """)
                .param("nowMillis", nowMillis)
                .update();
    }

    static boolean isCanonicalSessionId(String value) {
        if (value == null || value.length() != 36) {
            return false;
        }
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    static String requireStablePrimaryId(String value) {
        if (!isCanonicalSessionId(value)) {
            throw new IllegalArgumentException("stable Spring primary id is invalid");
        }
        return value;
    }

    static String requireClientSummary(String value) {
        if (value == null
                || value.isBlank()
                || !value.equals(value.trim())
                || value.length() > 255) {
            throw new IllegalArgumentException("client summary is invalid");
        }
        return value;
    }

    static String requireReason(String value) {
        if (value == null || !value.matches("[A-Z0-9_]{1,64}")) {
            throw new IllegalArgumentException("session reason is invalid");
        }
        return value;
    }

    private static SessionRow mapSessionRow(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new SessionRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("admin_id", UUID.class),
                resultSet.getString("session_primary_id"),
                AdminSessionStatus.valueOf(resultSet.getString("status")),
                instant(resultSet, "created_at"),
                lastAccessMillis(resultSet),
                resultSet.getLong("expiry_time"));
    }

    private static TerminalSession mapTerminalSession(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new TerminalSession(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("admin_id", UUID.class),
                resultSet.getString("session_primary_id"),
                resultSet.getString("revocation_reason"));
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static long lastAccessMillis(ResultSet resultSet) throws SQLException {
        long springLastAccess = resultSet.getLong("last_access_time");
        if (!resultSet.wasNull()) {
            return springLastAccess;
        }
        return instant(resultSet, "metadata_last_activity_at").toEpochMilli();
    }

    private static Instant nullableInstant(ResultSet resultSet, String column)
            throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime toOffsetDateTime(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private static void requireOne(int changed, String message) {
        if (changed != 1) {
            throw new IllegalStateException(message);
        }
    }

    public record SessionRow(
            UUID metadataId,
            UUID adminId,
            String springSessionPrimaryId,
            AdminSessionStatus status,
            Instant createdAt,
            long lastAccessMillis,
            long expiryMillis) {
        public SessionRow {
            Objects.requireNonNull(metadataId, "metadata id is required");
            Objects.requireNonNull(adminId, "admin id is required");
            if (springSessionPrimaryId != null) {
                requireStablePrimaryId(springSessionPrimaryId);
            }
            Objects.requireNonNull(status, "session status is required");
            Objects.requireNonNull(createdAt, "created timestamp is required");
        }

        @Override
        public String toString() {
            return "SessionRow[metadataId=" + metadataId
                    + ", adminId=" + adminId
                    + ", springSessionPrimaryId=<redacted>"
                    + ", status=" + status
                    + ", createdAt=" + createdAt
                    + ", lastAccessMillis=" + lastAccessMillis
                    + ", expiryMillis=" + expiryMillis + "]";
        }
    }

    public record TerminalSession(
            UUID metadataId,
            UUID adminId,
            String primaryId,
            String reason) {
        public TerminalSession {
            Objects.requireNonNull(metadataId, "metadata id is required");
            Objects.requireNonNull(adminId, "admin id is required");
            if (primaryId != null) {
                requireStablePrimaryId(primaryId);
            }
            requireReason(reason);
        }

        @Override
        public String toString() {
            return "TerminalSession[metadataId=" + metadataId
                    + ", adminId=" + adminId
                    + ", primaryId=<redacted>"
                    + ", reason=" + reason + "]";
        }
    }

    public record SessionView(
            UUID id,
            AdminSessionStatus status,
            Instant createdAt,
            Instant endedAt,
            long lastAccessMillis,
            String clientSummary,
            String reason,
            boolean current) {
        public SessionView {
            Objects.requireNonNull(id, "metadata id is required");
            Objects.requireNonNull(status, "session status is required");
            Objects.requireNonNull(createdAt, "created timestamp is required");
            requireClientSummary(clientSummary);
            if (status == AdminSessionStatus.ACTIVE) {
                if (endedAt != null || reason != null) {
                    throw new IllegalArgumentException("ACTIVE session view is terminal");
                }
            } else {
                Objects.requireNonNull(endedAt, "ended timestamp is required");
                requireReason(reason);
            }
        }
    }
}
