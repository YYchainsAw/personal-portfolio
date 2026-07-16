package xyz.yychainsaw.portfolio.audit;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import xyz.yychainsaw.portfolio.api.admin.audit.AdminAuditItem;

@Repository
class AdminAuditQueryRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final AuditMetadataRedactor metadata;

    AdminAuditQueryRepository(
            NamedParameterJdbcTemplate jdbc, AuditMetadataRedactor metadata) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC template is required");
        this.metadata = Objects.requireNonNull(metadata, "metadata redactor is required");
    }

    List<AdminAuditItem> find(Query query, int fetchLimit) {
        Objects.requireNonNull(query, "audit query is required");
        if (fetchLimit < 2 || fetchLimit > 101) {
            throw new IllegalArgumentException("audit fetch limit is invalid");
        }

        StringBuilder sql = new StringBuilder("""
                select id, actor_admin_id, action, target_type, target_id, outcome,
                       trace_id, metadata::text as metadata, created_at
                from portfolio.audit_log
                where 1=1
                """);
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        if (query.action() != null) {
            sql.append(" and action=:action");
            parameters.addValue("action", query.action(), Types.VARCHAR);
        }
        if (query.outcome() != null) {
            sql.append(" and outcome=:outcome");
            parameters.addValue("outcome", query.outcome(), Types.VARCHAR);
        }
        if (query.from() != null) {
            sql.append(" and created_at>=:from");
            parameters.addValue("from", utc(query.from()), Types.TIMESTAMP_WITH_TIMEZONE);
        }
        if (query.to() != null) {
            sql.append(" and created_at<:to");
            parameters.addValue("to", utc(query.to()), Types.TIMESTAMP_WITH_TIMEZONE);
        }
        if (query.cursor() != null) {
            sql.append(" and created_at<=:cursorAt"
                    + " and (created_at<:cursorAt"
                    + " or (created_at=:cursorAt and id>:cursorId))");
            parameters.addValue(
                    "cursorAt", utc(query.cursor().createdAt()), Types.TIMESTAMP_WITH_TIMEZONE);
            parameters.addValue("cursorId", query.cursor().id(), Types.OTHER);
        }
        sql.append(" order by created_at desc, id asc limit :fetchLimit");
        parameters.addValue("fetchLimit", fetchLimit, Types.INTEGER);
        return List.copyOf(jdbc.query(sql.toString(), parameters, this::map));
    }

    private AdminAuditItem map(ResultSet result, int rowNumber) throws SQLException {
        OffsetDateTime createdAt = result.getObject("created_at", OffsetDateTime.class);
        return new AdminAuditItem(
                result.getObject("id", UUID.class),
                result.getObject("actor_admin_id", UUID.class),
                result.getString("action"),
                result.getString("target_type"),
                result.getString("target_id"),
                result.getString("outcome"),
                result.getString("trace_id"),
                metadata.redact(result.getString("metadata")),
                Objects.requireNonNull(createdAt, "stored audit timestamp is required").toInstant());
    }

    private static OffsetDateTime utc(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    record Query(
            String action,
            String outcome,
            Instant from,
            Instant to,
            AuditCursor cursor) {
    }
}
