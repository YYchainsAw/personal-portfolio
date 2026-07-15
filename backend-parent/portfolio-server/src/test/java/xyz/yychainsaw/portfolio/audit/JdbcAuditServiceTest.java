package xyz.yychainsaw.portfolio.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
class JdbcAuditServiceTest extends PostgresIntegrationTestBase {
    @Autowired AuditService auditService;
    @Autowired JdbcClient jdbc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper objectMapper;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void persistsMetadataAsAJsonObjectWithExactAuditFields() {
        String trace = uniqueTrace("metadata");

        auditService.record(new AuditCommand(
                null,
                "AUTH_REJECTED",
                "ADMIN",
                null,
                AuditOutcome.FAILURE,
                trace,
                Map.of("stage", "PASSWORD")));

        assertThat(rowsByTrace(trace)).singleElement().satisfies(row -> {
            assertThat(row.id()).isNotNull();
            assertThat(row.actorAdminId()).isNull();
            assertThat(row.action()).isEqualTo("AUTH_REJECTED");
            assertThat(row.targetType()).isEqualTo("ADMIN");
            assertThat(row.targetId()).isNull();
            assertThat(row.outcome()).isEqualTo("FAILURE");
            assertThat(row.traceId()).isEqualTo(trace);
            assertThat(row.metadataType()).isEqualTo("object");
            assertThat(row.metadataMatches()).isTrue();
        });
    }

    @Test
    void runtimeRoleCannotUpdateAuditRows() {
        String trace = uniqueTrace("runtime-acl");
        record(trace, "RUNTIME_ORIGINAL");

        assertSqlState(() -> jdbc.sql("""
                        update portfolio.audit_log
                        set action = 'RUNTIME_MUTATED'
                        where trace_id = :trace
                        """).param("trace", trace).update(), "42501");

        assertThat(rowsByTrace(trace)).singleElement()
                .extracting(AuditRow::action)
                .isEqualTo("RUNTIME_ORIGINAL");
    }

    @Test
    void ownerTriggerRejectsOrdinaryUpdateDeleteAndTruncate() {
        String trace = uniqueTrace("owner-trigger");
        record(trace, "OWNER_ORIGINAL");
        JdbcClient owner = migratorJdbc();

        assertSqlState(() -> owner.sql("""
                        update portfolio.audit_log
                        set action = 'OWNER_MUTATED'
                        where trace_id = :trace
                        """).param("trace", trace).update(), "55000");
        assertSqlState(() -> owner.sql("""
                        delete from portfolio.audit_log
                        where trace_id = :trace
                        """).param("trace", trace).update(), "55000");
        assertSqlState(
                () -> owner.sql("truncate table portfolio.audit_log").update(),
                "55000");

        assertThat(rowsByTrace(trace)).singleElement()
                .extracting(AuditRow::action)
                .isEqualTo("OWNER_ORIGINAL");
    }

    @Test
    void participatesInAnOuterTransactionRollback() {
        String trace = uniqueTrace("outer-rollback");
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            record(trace, "ROLLED_BACK");
            assertThat(countByTrace(trace)).isOne();
            status.setRollbackOnly();
        });

        assertThat(countByTrace(trace)).isZero();
    }

    @Test
    void unknownActorFailsWithForeignKeyStateAndLeavesNoRow() {
        String trace = uniqueTrace("unknown-actor");
        UUID nonexistentActor = UUID.randomUUID();
        AuditCommand command = new AuditCommand(
                nonexistentActor,
                "ACTOR_LOOKUP",
                "ADMIN",
                null,
                AuditOutcome.FAILURE,
                trace,
                Map.of());

        assertSqlState(() -> auditService.record(command), "23503");

        assertThat(countByTrace(trace)).isZero();
    }

    @Test
    void rejectsANullCommandImmediatelyWithItsParameterName() {
        assertThatNullPointerException()
                .isThrownBy(() -> auditService.record(null))
                .withMessage("command");
    }

    @Test
    void constructorRejectsNullDependenciesWithTheirParameterNames() {
        assertThatNullPointerException()
                .isThrownBy(() -> new JdbcAuditService(null, objectMapper))
                .withMessage("jdbc");
        assertThatNullPointerException()
                .isThrownBy(() -> new JdbcAuditService(jdbcTemplate, null))
                .withMessage("objectMapper");
    }

    @Test
    void wrapsJacksonSerializationFailureWithASafeMessageBeforeSql() {
        String trace = uniqueTrace("json-failure");
        ObjectMapper failingObjectMapper = new ObjectMapper() {
            @Override
            public String writeValueAsString(Object value) throws JsonProcessingException {
                throw new JsonProcessingException("serialization failure") {};
            }
        };
        JdbcAuditService service = new JdbcAuditService(jdbcTemplate, failingObjectMapper);
        AuditCommand command = new AuditCommand(
                null,
                "JSON_FAILURE",
                "AUDIT",
                null,
                AuditOutcome.FAILURE,
                trace,
                Map.of("field", "value"));

        assertThatThrownBy(() -> service.record(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("audit metadata must be JSON serializable")
                .hasCauseInstanceOf(JsonProcessingException.class);
        assertThat(countByTrace(trace)).isZero();
    }

    private void record(String trace, String action) {
        auditService.record(new AuditCommand(
                null,
                action,
                "AUDIT",
                null,
                AuditOutcome.SUCCESS,
                trace,
                Map.of()));
    }

    private List<AuditRow> rowsByTrace(String trace) {
        return jdbc.sql("""
                select id,
                       actor_admin_id,
                       action,
                       target_type,
                       target_id,
                       outcome,
                       trace_id,
                       jsonb_typeof(metadata) as metadata_type,
                       metadata = cast('{"stage":"PASSWORD"}' as jsonb) as metadata_matches
                from portfolio.audit_log
                where trace_id = :trace
                """).param("trace", trace).query((resultSet, rowNumber) -> new AuditRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("actor_admin_id", UUID.class),
                        resultSet.getString("action"),
                        resultSet.getString("target_type"),
                        resultSet.getString("target_id"),
                        resultSet.getString("outcome"),
                        resultSet.getString("trace_id"),
                        resultSet.getString("metadata_type"),
                        resultSet.getBoolean("metadata_matches")))
                .list();
    }

    private int countByTrace(String trace) {
        return jdbc.sql("""
                select count(*)
                from portfolio.audit_log
                where trace_id = :trace
                """).param("trace", trace).query(Integer.class).single();
    }

    private static String uniqueTrace(String purpose) {
        return "audit-" + purpose + "-" + UUID.randomUUID();
    }

    private static void assertSqlState(ThrowingCallable operation, String expectedState) {
        DataAccessException exception =
                catchThrowableOfType(DataAccessException.class, operation);

        assertThat(exception).isNotNull();
        assertThat(exception.getRootCause())
                .isInstanceOfSatisfying(SQLException.class, sqlException ->
                        assertThat(sqlException.getSQLState()).isEqualTo(expectedState));
    }

    private record AuditRow(
            UUID id,
            UUID actorAdminId,
            String action,
            String targetType,
            String targetId,
            String outcome,
            String traceId,
            String metadataType,
            boolean metadataMatches) {
    }
}
