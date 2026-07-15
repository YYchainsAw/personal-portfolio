package xyz.yychainsaw.portfolio.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JdbcAuditService implements AuditService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcAuditService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    @Transactional
    public void record(AuditCommand command) {
        Objects.requireNonNull(command, "command");
        String metadata;
        try {
            metadata = objectMapper.writeValueAsString(command.metadata());
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "audit metadata must be JSON serializable", exception);
        }
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    insert into portfolio.audit_log
                    (id, actor_admin_id, action, target_type, target_id, outcome, trace_id, metadata)
                    values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))
                    """);
            statement.setObject(1, UUID.randomUUID());
            if (command.actorAdminId() == null) {
                statement.setNull(2, Types.OTHER);
            } else {
                statement.setObject(2, command.actorAdminId());
            }
            statement.setString(3, command.action());
            statement.setString(4, command.targetType());
            statement.setString(5, command.targetId());
            statement.setString(6, command.outcome().name());
            statement.setString(7, command.traceId());
            statement.setString(8, metadata);
            return statement;
        });
    }
}
