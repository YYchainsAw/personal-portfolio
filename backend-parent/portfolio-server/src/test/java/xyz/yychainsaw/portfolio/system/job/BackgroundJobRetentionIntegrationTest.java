package xyz.yychainsaw.portfolio.system.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest(properties = "portfolio.jobs.retention.enabled=false")
@Isolated
class BackgroundJobRetentionIntegrationTest extends PostgresIntegrationTestBase {
    private static final String FUNCTION =
            "portfolio.delete_expired_terminal_background_jobs";

    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired BackgroundJobRetentionRepository repository;

    private final String keyPrefix = "retention-test:"
            + UUID.randomUUID() + ':';
    private final Set<UUID> trackedJobIds = new LinkedHashSet<>();

    @AfterEach
    void clearRetentionFixtures() {
        JdbcClient migrator = migratorJdbc();
        for (UUID jobId : trackedJobIds) {
            migrator.sql("""
                            delete from portfolio.local_staging_reservation
                            where cleanup_job_id=:jobId
                            """)
                    .param("jobId", jobId)
                    .update();
        }
        for (UUID jobId : trackedJobIds) {
            migrator.sql("""
                            delete from portfolio.background_job
                            where id=:jobId
                              and idempotency_key like :keyPrefix
                            """)
                    .param("jobId", jobId)
                    .param("keyPrefix", keyPrefix + '%')
                    .update();
        }
    }

    @Test
    @Transactional
    @Rollback
    void deletesOnlyOldUnreferencedSucceededAndDeadJobs() {
        UUID oldSucceeded = insertJob("SUCCEEDED", 20_000);
        UUID oldDead = insertJob("DEAD", 20_001);
        UUID youngSucceeded = insertJob("SUCCEEDED", 29);
        UUID youngDead = insertJob("DEAD", 1);
        UUID oldPending = insertJob("PENDING", 90);
        UUID oldRunning = insertJob("RUNNING", 90);
        UUID oldFailed = insertJob("FAILED", 90);
        UUID referencedDead = insertJob("DEAD", 20_002);
        insertReservation(referencedDead);

        int deleted = repository.deleteExpiredTerminalBatch(2);

        assertThat(deleted).isEqualTo(2);
        assertThat(existingJobIds())
                .doesNotContain(oldSucceeded, oldDead)
                .contains(
                        youngSucceeded,
                        youngDead,
                        oldPending,
                        oldRunning,
                        oldFailed,
                        referencedDead);
        assertThat(jdbc.sql("""
                        select count(*)
                        from portfolio.local_staging_reservation
                        where cleanup_job_id=:jobId
                        """)
                .param("jobId", referencedDead)
                .query(Long.class)
                .single()).isOne();
    }

    @Test
    @Transactional
    @Rollback
    void eachCallDeletesOneDeterministicBoundedBatchAndReturnsItsExactCount() {
        UUID oldest = insertJob("SUCCEEDED", 20_005);
        UUID secondOldest = insertJob("DEAD", 20_004);
        UUID thirdOldest = insertJob("SUCCEEDED", 20_003);
        UUID fourthOldest = insertJob("DEAD", 20_002);
        UUID fifthOldest = insertJob("SUCCEEDED", 20_001);

        assertThat(repository.deleteExpiredTerminalBatch(2)).isEqualTo(2);
        assertThat(existingJobIds())
                .doesNotContain(oldest, secondOldest)
                .contains(thirdOldest, fourthOldest, fifthOldest);

        assertThat(repository.deleteExpiredTerminalBatch(2)).isEqualTo(2);
        assertThat(existingJobIds())
                .doesNotContain(thirdOldest, fourthOldest)
                .containsExactly(fifthOldest);
        assertThat(repository.deleteExpiredTerminalBatch(2)).isOne();
    }

    @Test
    void databaseFunctionRejectsNullZeroAndOverOneThousandWithoutDeleting() {
        insertJob("SUCCEEDED", 60);
        insertJob("DEAD", 60);

        for (Integer invalid : new Integer[] {null, 0, 1_001}) {
            assertThatThrownBy(() -> callFunction(invalid))
                    .isInstanceOf(DataAccessException.class);
            assertThat(existingJobIds()).hasSize(2);
        }
    }

    @Test
    void repositoryRejectsOutOfRangeBatchesBeforeCallingTheDatabase() {
        for (int invalid : new int[] {-1, 0, 1_001}) {
            assertThatThrownBy(() -> repository.deleteExpiredTerminalBatch(invalid))
                    .isExactlyInstanceOf(IllegalArgumentException.class)
                    .hasMessage("background job retention batch size is invalid")
                    .hasNoCause();
        }
    }

    @Test
    void fixedFunctionSearchPathIgnoresHostileShadowObjects() throws Exception {
        UUID oldSucceeded = insertJob("SUCCEEDED", 20_000);
        String attackerSchema = "retention_attacker_"
                + UUID.randomUUID().toString().replace("-", "");
        JdbcClient migrator = migratorJdbc();
        migrator.sql("create schema " + attackerSchema).update();
        try {
            migrator.sql("grant usage on schema " + attackerSchema
                    + " to portfolio_runtime_access").update();
            migrator.sql("create table " + attackerSchema
                    + ".background_job(id uuid primary key)").update();
            migrator.sql("insert into " + attackerSchema
                    + ".background_job(id) values (:id)")
                    .param("id", UUID.randomUUID())
                    .update();
            migrator.sql("""
                    create function %s.clock_timestamp()
                    returns timestamptz
                    language sql
                    immutable
                    as 'select timestamptz ''1900-01-01 00:00:00+00'''
                    """.formatted(attackerSchema)).update();

            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("set local search_path = "
                            + attackerSchema + ", pg_temp, public");
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "select " + FUNCTION + "(?)")) {
                    statement.setInt(1, 1);
                    try (ResultSet result = statement.executeQuery()) {
                        assertThat(result.next()).isTrue();
                        assertThat(result.getInt(1)).isOne();
                        assertThat(result.next()).isFalse();
                    }
                }
                connection.rollback();
            }

            assertThat(existingJobIds()).containsExactly(oldSucceeded);
            assertThat(migrator.sql("select count(*) from "
                            + attackerSchema + ".background_job")
                    .query(Long.class)
                    .single()).isOne();
        } finally {
            migrator.sql("drop schema " + attackerSchema + " cascade").update();
        }
    }

    @Test
    void runtimeCanExecuteOnlyTheFunctionAndCannotDeleteTheTableDirectly() {
        UUID oldSucceeded = insertJob("SUCCEEDED", 20_000);

        assertThat(jdbc.sql("""
                        select pg_catalog.has_table_privilege(
                            current_user,
                            'portfolio.background_job',
                            'DELETE'
                        )
                        """).query(Boolean.class).single()).isFalse();
        assertThatThrownBy(() -> jdbc.sql("""
                        delete from portfolio.background_job where id=:id
                        """).param("id", oldSucceeded).update())
                .isInstanceOf(DataAccessException.class);
        assertThat(existingJobIds()).containsExactly(oldSucceeded);
        TransactionTemplate transaction =
                new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            assertThat(repository.deleteExpiredTerminalBatch(1)).isOne();
            assertThat(existingJobIds()).isEmpty();
            status.setRollbackOnly();
        });
        assertThat(existingJobIds()).containsExactly(oldSucceeded);
    }

    @Test
    void functionIsSecurityDefinerWithOnlyTheCapabilityExecuteGrant() {
        JdbcClient migrator = migratorJdbc();
        Boolean securityDefiner = migrator.sql("""
                        select procedure.prosecdef
                        from pg_catalog.pg_proc procedure
                        join pg_catalog.pg_namespace namespace
                          on namespace.oid=procedure.pronamespace
                        where namespace.nspname='portfolio'
                          and procedure.proname='delete_expired_terminal_background_jobs'
                          and procedure.pronargs=1
                        """).query(Boolean.class).single();
        String searchPath = migrator.sql("""
                        select pg_catalog.array_to_string(procedure.proconfig, ',')
                        from pg_catalog.pg_proc procedure
                        join pg_catalog.pg_namespace namespace
                          on namespace.oid=procedure.pronamespace
                        where namespace.nspname='portfolio'
                          and procedure.proname='delete_expired_terminal_background_jobs'
                          and procedure.pronargs=1
                        """).query(String.class).single();
        List<String> executeGrantees = migrator.sql("""
                        select case
                                   when role.rolname is null then 'PUBLIC'
                                   else role.rolname::text
                               end
                        from pg_catalog.pg_proc procedure
                        join pg_catalog.pg_namespace namespace
                          on namespace.oid=procedure.pronamespace
                        cross join lateral pg_catalog.aclexplode(
                            case
                                when procedure.proacl is null then
                                    pg_catalog.acldefault(
                                        'f', procedure.proowner
                                    )
                                else procedure.proacl
                            end
                        ) privilege
                        left join pg_catalog.pg_roles role
                          on role.oid=privilege.grantee
                        where namespace.nspname='portfolio'
                          and procedure.proname='delete_expired_terminal_background_jobs'
                          and procedure.pronargs=1
                          and privilege.privilege_type='EXECUTE'
                        order by 1
                        """).query(String.class).list();

        assertThat(securityDefiner).isTrue();
        assertThat(searchPath).isEqualTo("search_path=pg_catalog, pg_temp");
        assertThat(executeGrantees)
                .containsExactly("portfolio_runtime_access", "test_migrator")
                .doesNotContain("PUBLIC", "test_runtime");
        assertThat(jdbc.sql("""
                        select pg_catalog.has_function_privilege(
                            current_user,
                            'portfolio.delete_expired_terminal_background_jobs(integer)',
                            'EXECUTE'
                        )
                        """).query(Boolean.class).single()).isTrue();
    }

    @Test
    void callerFailureRollsBackEveryRowDeletedByTheFunction() {
        UUID succeeded = insertJob("SUCCEEDED", 20_000);
        UUID dead = insertJob("DEAD", 20_001);
        TransactionTemplate transaction =
                new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                    assertThat(repository.deleteExpiredTerminalBatch(2)).isEqualTo(2);
                    assertThat(existingJobIds()).isEmpty();
                    throw new IllegalStateException("TEST_RETENTION_ROLLBACK");
                }))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("TEST_RETENTION_ROLLBACK")
                .hasNoCause();

        assertThat(existingJobIds()).containsExactlyInAnyOrder(succeeded, dead);
    }

    @Test
    void flywayAppliesTheRetentionMigrationAfterThePersistentV4() {
        List<FlywayVersion> versions = migratorJdbc().sql("""
                        select version, description
                        from portfolio.flyway_schema_history
                        where success and version in ('4', '5')
                        order by installed_rank
                        """).query((resultSet, rowNumber) -> new FlywayVersion(
                                resultSet.getString("version"),
                                resultSet.getString("description")))
                .list();

        assertThat(versions).containsExactly(
                new FlywayVersion("4", "local staging reservations"),
                new FlywayVersion("5", "background job retention"));
    }

    private int callFunction(Integer batchSize) {
        return jdbc.sql("select " + FUNCTION + "(:batchSize)")
                .param("batchSize", batchSize, Types.INTEGER)
                .query(Integer.class)
                .single();
    }

    private UUID insertJob(String status, int ageDays) {
        UUID id = UUID.randomUUID();
        trackedJobIds.add(id);
        jdbc.sql("""
                        insert into portfolio.background_job(
                            id, job_type, idempotency_key, payload, status,
                            attempts, next_run_at, lease_owner, lease_until,
                            created_at, updated_at
                        ) values (
                            :id, 'RETENTION_TEST', :key, '{}'::jsonb, :status,
                            case when :status='RUNNING' then 1 else 0 end,
                            clock_timestamp(),
                            case when :status='RUNNING'
                                then 'retention-test-worker' else null end,
                            case when :status='RUNNING'
                                then clock_timestamp() + interval '1 hour' else null end,
                            clock_timestamp() - (:ageDays * interval '1 day'),
                            clock_timestamp() - (:ageDays * interval '1 day')
                        )
                """)
                .param("id", id)
                .param("key", keyPrefix + id)
                .param("status", status)
                .param("ageDays", ageDays)
                .update();
        return id;
    }

    private void insertReservation(UUID cleanupJobId) {
        jdbc.sql("""
                        insert into portfolio.local_staging_reservation(
                            asset_id, sha256, mime_type, generation,
                            cleanup_job_id, reserved_at, cleanup_after
                        ) values (
                            :assetId, :sha256, 'image/jpeg', 0,
                            :cleanupJobId, clock_timestamp(),
                            clock_timestamp() + interval '25 hours'
                        )
                        """)
                .param("assetId", UUID.randomUUID())
                .param("sha256", "a".repeat(64))
                .param("cleanupJobId", cleanupJobId)
                .update();
    }

    private List<UUID> existingJobIds() {
        return jdbc.sql("""
                        select id
                        from portfolio.background_job
                        where idempotency_key like :keyPrefix
                        order by id
                        """)
                .param("keyPrefix", keyPrefix + '%')
                .query(UUID.class)
                .list();
    }

    private record FlywayVersion(String version, String description) {
    }
}
