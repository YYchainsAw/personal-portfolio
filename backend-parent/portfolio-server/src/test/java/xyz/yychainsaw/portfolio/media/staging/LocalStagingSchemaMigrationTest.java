package xyz.yychainsaw.portfolio.media.staging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
class LocalStagingSchemaMigrationTest extends PostgresIntegrationTestBase {
    private static final String SHA256 = "a".repeat(64);

    @Autowired JdbcClient jdbc;

    @Test
    void flywayCreatesVersionSixPolicyAndReservationTables() {
        List<String> versions = migratorJdbc().sql("""
                        select version
                        from portfolio.flyway_schema_history
                        where success and version is not null
                        order by installed_rank
                        """)
                .query(String.class)
                .list();
        List<String> tables = migratorJdbc().sql("""
                        select table_name
                        from information_schema.tables
                        where table_schema='portfolio'
                          and table_name in (
                              'local_staging_policy', 'local_staging_reservation'
                          )
                        order by table_name
                        """)
                .query(String.class)
                .list();

        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(versions)
                    .containsExactly("1", "2", "3", "4", "5", "6");
            softly.assertThat(tables).containsExactly(
                    "local_staging_policy", "local_staging_reservation");
        });
    }

    @Test
    void runtimeHasOnlyPolicyBootstrapAndReservationLifecyclePrivileges() {
        List<String> policyPrivileges = explicitTablePrivileges("local_staging_policy");
        List<String> reservationPrivileges = explicitTablePrivileges(
                "local_staging_reservation");
        List<String> policyInsertColumns = migratorJdbc().sql("""
                        select attribute.attname
                        from pg_catalog.pg_class relation
                        join pg_catalog.pg_namespace namespace
                          on namespace.oid=relation.relnamespace
                        join pg_catalog.pg_attribute attribute
                          on attribute.attrelid=relation.oid
                        cross join lateral pg_catalog.aclexplode(attribute.attacl) privilege
                        join pg_catalog.pg_roles grantee_role
                          on grantee_role.oid=privilege.grantee
                        where namespace.nspname='portfolio'
                          and relation.relname='local_staging_policy'
                          and grantee_role.rolname='portfolio_runtime_access'
                          and privilege.privilege_type='INSERT'
                        order by attribute.attname
                        """)
                .query(String.class)
                .list();
        List<String> updateColumns = migratorJdbc().sql("""
                        select attribute.attname
                        from pg_catalog.pg_class relation
                        join pg_catalog.pg_namespace namespace
                          on namespace.oid=relation.relnamespace
                        join pg_catalog.pg_attribute attribute
                          on attribute.attrelid=relation.oid
                        cross join lateral pg_catalog.aclexplode(attribute.attacl) privilege
                        join pg_catalog.pg_roles grantee_role
                          on grantee_role.oid=privilege.grantee
                        where namespace.nspname='portfolio'
                          and relation.relname='local_staging_reservation'
                          and grantee_role.rolname='portfolio_runtime_access'
                          and privilege.privilege_type='UPDATE'
                        order by attribute.attname
                        """)
                .query(String.class)
                .list();
        Boolean publicHasPrivileges = migratorJdbc().sql("""
                        select has_table_privilege(
                                   'public', 'portfolio.local_staging_policy',
                                   'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER,MAINTAIN'
                               )
                            or has_table_privilege(
                                   'public', 'portfolio.local_staging_reservation',
                                   'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER,MAINTAIN'
                               )
                        """)
                .query(Boolean.class)
                .single();
        Boolean publicCanExecuteGuard = migratorJdbc().sql("""
                        select has_function_privilege(
                            'public',
                            'portfolio.enforce_local_staging_reservation_state()',
                            'EXECUTE'
                        )
                        """)
                .query(Boolean.class)
                .single();
        Boolean runtimeCanExecuteGuard = migratorJdbc().sql("""
                        select has_function_privilege(
                            'portfolio_runtime_access',
                            'portfolio.enforce_local_staging_reservation_state()',
                            'EXECUTE'
                        )
                        """)
                .query(Boolean.class)
                .single();
        Boolean runtimeCanUpdateGeneration = migratorJdbc().sql("""
                        select has_column_privilege(
                            'portfolio_runtime_access',
                            'portfolio.local_staging_reservation',
                            'generation',
                            'UPDATE'
                        )
                        """)
                .query(Boolean.class)
                .single();
        Boolean runtimeCanUpdateIdentity = migratorJdbc().sql("""
                        select has_column_privilege(
                            'portfolio_runtime_access',
                            'portfolio.local_staging_reservation',
                            'sha256',
                            'UPDATE'
                        )
                        """)
                .query(Boolean.class)
                .single();
        Boolean runtimeHasTableInsertOnPolicy = migratorJdbc().sql("""
                        select has_table_privilege(
                            'portfolio_runtime_access',
                            'portfolio.local_staging_policy',
                            'INSERT'
                        )
                        """)
                .query(Boolean.class)
                .single();
        Boolean runtimeCanInsertPolicyConfiguration = migratorJdbc().sql("""
                        select has_column_privilege(
                            'portfolio_runtime_access',
                            'portfolio.local_staging_policy',
                            'singleton_key',
                            'INSERT'
                        )
                        """)
                .query(Boolean.class)
                .single();
        Boolean runtimeCanInsertPolicyVolume = migratorJdbc().sql("""
                        select has_column_privilege(
                            'portfolio_runtime_access',
                            'portfolio.local_staging_policy',
                            'volume_id',
                            'INSERT'
                        )
                        """)
                .query(Boolean.class)
                .single();
        Boolean runtimeCanUpdatePolicyVolume = migratorJdbc().sql("""
                        select has_column_privilege(
                            'portfolio_runtime_access',
                            'portfolio.local_staging_policy',
                            'volume_id',
                            'UPDATE'
                        )
                        """)
                .query(Boolean.class)
                .single();

        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(policyPrivileges).containsExactly("SELECT");
            softly.assertThat(policyInsertColumns).containsExactly(
                    "active_capacity",
                    "reserved_headroom",
                    "scan_entry_ceiling",
                    "singleton_key",
                    "worst_case_entries_per_reservation");
            softly.assertThat(reservationPrivileges)
                    .containsExactly("DELETE", "INSERT", "SELECT");
            softly.assertThat(updateColumns)
                    .containsExactly("cleanup_job_id", "generation");
            softly.assertThat(publicHasPrivileges).isFalse();
            softly.assertThat(publicCanExecuteGuard).isFalse();
            softly.assertThat(runtimeCanExecuteGuard).isFalse();
            softly.assertThat(runtimeCanUpdateGeneration).isTrue();
            softly.assertThat(runtimeCanUpdateIdentity).isFalse();
            softly.assertThat(runtimeHasTableInsertOnPolicy).isFalse();
            softly.assertThat(runtimeCanInsertPolicyConfiguration).isTrue();
            softly.assertThat(runtimeCanInsertPolicyVolume).isFalse();
            softly.assertThat(runtimeCanUpdatePolicyVolume).isFalse();
            softly.assertThat(jdbc.sql("""
                            select has_table_privilege(
                                current_user,
                                'portfolio.local_staging_policy',
                                'UPDATE,DELETE'
                            )
                            """).query(Boolean.class).single()).isFalse();
        });
    }

    @Test
    void policyConstraintsUseFixedCardinalityAndStrictOverflowSafeCapacityMath() {
        assertSqlState("23514", """
                insert into portfolio.local_staging_policy(
                    singleton_key, active_capacity, scan_entry_ceiling,
                    worst_case_entries_per_reservation, reserved_headroom
                ) values (2, 1, 100, 6, 1)
                """, Map.of());
        assertSqlState("23514", """
                insert into portfolio.local_staging_policy(
                    singleton_key, active_capacity, scan_entry_ceiling,
                    worst_case_entries_per_reservation, reserved_headroom
                ) values (1, 1, 100, 5, 1)
                """, Map.of());
        assertSqlState("23514", """
                insert into portfolio.local_staging_policy(
                    singleton_key, active_capacity, scan_entry_ceiling,
                    worst_case_entries_per_reservation, reserved_headroom
                ) values (1, 16666, 100000, 6, 4)
                """, Map.of());
        assertSqlState("23514", """
                insert into portfolio.local_staging_policy(
                    singleton_key, active_capacity, scan_entry_ceiling,
                    worst_case_entries_per_reservation, reserved_headroom
                ) values (1, 0, 100, 6, 1)
                """, Map.of());
        assertSqlState("23514", """
                insert into portfolio.local_staging_policy(
                    singleton_key, active_capacity, scan_entry_ceiling,
                    worst_case_entries_per_reservation, reserved_headroom
                ) values (1, 1, 1000001, 6, 1)
                """, Map.of());
        assertThat(migratorJdbc().sql("""
                        select column_default like '%clock_timestamp%'
                        from information_schema.columns
                        where table_schema='portfolio'
                          and table_name='local_staging_policy'
                          and column_name='created_at'
                        """)
                .query(Boolean.class)
                .single()).isTrue();
    }

    @Test
    void reservationConstraintsAndTriggerProtectImmutableIdentityAndGeneration() {
        UUID jobId = insertJob();
        UUID successorJobId = insertJob();
        UUID assetId = UUID.randomUUID();
        try {
            assertSqlState("23514", reservationInsertSql(), Map.of(
                    "assetId", UUID.randomUUID(),
                    "jobId", jobId,
                    "sha256", "A".repeat(64),
                    "mimeType", "image/jpeg",
                    "generation", 0L,
                    "reservedAt", utc(OffsetDateTime.now(ZoneOffset.UTC)),
                    "cleanupAfter", utc(OffsetDateTime.now(ZoneOffset.UTC).plusHours(25))));
            assertSqlState("23514", reservationInsertSql(), Map.of(
                    "assetId", UUID.randomUUID(),
                    "jobId", jobId,
                    "sha256", SHA256,
                    "mimeType", "image/gif",
                    "generation", 0L,
                    "reservedAt", utc(OffsetDateTime.now(ZoneOffset.UTC)),
                    "cleanupAfter", utc(OffsetDateTime.now(ZoneOffset.UTC).plusHours(25))));
            assertSqlState("23514", reservationInsertSql(), Map.of(
                    "assetId", UUID.randomUUID(),
                    "jobId", jobId,
                    "sha256", SHA256,
                    "mimeType", "image/jpeg",
                    "generation", 1L,
                    "reservedAt", utc(OffsetDateTime.now(ZoneOffset.UTC)),
                    "cleanupAfter", utc(OffsetDateTime.now(ZoneOffset.UTC).plusHours(25))));

            OffsetDateTime reservedAt = OffsetDateTime.now(ZoneOffset.UTC);
            migratorJdbc().sql(reservationInsertSql())
                    .params(Map.of(
                            "assetId", assetId,
                            "jobId", jobId,
                            "sha256", SHA256,
                            "mimeType", "application/pdf",
                            "generation", 0L,
                            "reservedAt", utc(reservedAt),
                            "cleanupAfter", utc(reservedAt.plusHours(24))))
                    .update();
            assertSqlState("23505", reservationInsertSql(), Map.of(
                    "assetId", UUID.randomUUID(),
                    "jobId", jobId,
                    "sha256", SHA256,
                    "mimeType", "image/jpeg",
                    "generation", 0L,
                    "reservedAt", utc(reservedAt),
                    "cleanupAfter", utc(reservedAt.plusHours(24))));
            assertSqlState("23503", reservationInsertSql(), Map.of(
                    "assetId", UUID.randomUUID(),
                    "jobId", UUID.randomUUID(),
                    "sha256", SHA256,
                    "mimeType", "image/jpeg",
                    "generation", 0L,
                    "reservedAt", utc(reservedAt),
                    "cleanupAfter", utc(reservedAt.plusHours(24))));
            assertSqlState("23514", reservationInsertSql(), Map.of(
                    "assetId", UUID.randomUUID(),
                    "jobId", successorJobId,
                    "sha256", SHA256,
                    "mimeType", "image/png",
                    "generation", 0L,
                    "reservedAt", utc(reservedAt),
                    "cleanupAfter", utc(reservedAt.plusHours(23))));
            assertSqlState("23514", """
                    update portfolio.local_staging_reservation
                    set sha256=:sha256
                    where asset_id=:assetId
                    """, Map.of("assetId", assetId, "sha256", "b".repeat(64)));
            assertSqlState("23514", """
                    update portfolio.local_staging_reservation
                    set generation=2, cleanup_job_id=:cleanupJobId
                    where asset_id=:assetId
                    """, Map.of(
                            "assetId", assetId,
                            "cleanupJobId", successorJobId));
            assertSqlState("23514", """
                    update portfolio.local_staging_reservation
                    set generation=1, cleanup_job_id=:cleanupJobId
                    where asset_id=:assetId
                    """, Map.of("assetId", assetId, "cleanupJobId", jobId));
            assertSqlState("23514", """
                    update portfolio.local_staging_reservation
                    set reserved_at=:reservedAt,
                        generation=1,
                        cleanup_job_id=:cleanupJobId
                    where asset_id=:assetId
                    """, Map.of(
                            "assetId", assetId,
                            "reservedAt", utc(reservedAt.plusSeconds(1)),
                            "cleanupJobId", successorJobId));
            assertThat(migratorJdbc().sql("""
                            update portfolio.local_staging_reservation
                            set generation=1, cleanup_job_id=:cleanupJobId
                            where asset_id=:assetId
                            """)
                    .param("assetId", assetId)
                    .param("cleanupJobId", successorJobId)
                    .update()).isOne();
        } finally {
            migratorJdbc().sql("""
                            delete from portfolio.local_staging_reservation
                            where asset_id=:assetId
                            """).param("assetId", assetId).update();
            migratorJdbc().sql("delete from portfolio.background_job where id=:id")
                    .param("id", jobId).update();
            migratorJdbc().sql("delete from portfolio.background_job where id=:id")
                    .param("id", successorJobId).update();
        }
    }

    private List<String> explicitTablePrivileges(String table) {
        return migratorJdbc().sql("""
                        select privilege.privilege_type
                        from pg_catalog.pg_class relation
                        join pg_catalog.pg_namespace namespace
                          on namespace.oid=relation.relnamespace
                        cross join lateral pg_catalog.aclexplode(relation.relacl) privilege
                        join pg_catalog.pg_roles grantee_role
                          on grantee_role.oid=privilege.grantee
                        where namespace.nspname='portfolio'
                          and relation.relname=:table
                          and grantee_role.rolname='portfolio_runtime_access'
                        order by privilege.privilege_type
                        """)
                .param("table", table)
                .query(String.class)
                .list();
    }

    private UUID insertJob() {
        UUID id = UUID.randomUUID();
        migratorJdbc().sql("""
                        insert into portfolio.background_job(
                            id, job_type, idempotency_key, payload, status,
                            attempts, next_run_at
                        ) values (
                            :id, 'CLEAN_LOCAL_STAGING_OBJECT', :key, '{}'::jsonb,
                            'PENDING', 0, clock_timestamp() + interval '24 hours'
                        )
                        """)
                .param("id", id)
                .param("key", "schema-test:" + id)
                .update();
        return id;
    }

    private static String reservationInsertSql() {
        return """
                insert into portfolio.local_staging_reservation(
                    asset_id, sha256, mime_type, generation, cleanup_job_id,
                    reserved_at, cleanup_after
                ) values (
                    :assetId, :sha256, :mimeType, :generation, :jobId,
                    :reservedAt, :cleanupAfter
                )
                """;
    }

    private static OffsetDateTime utc(OffsetDateTime value) {
        return value.withOffsetSameInstant(ZoneOffset.UTC);
    }

    private static void assertSqlState(
            String expected, String sql, Map<String, ?> parameters) {
        Throwable failure = catchThrowable(() -> migratorJdbc().sql(sql)
                .params(parameters)
                .update());
        assertThat(failure).isNotNull();
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        assertThat(root).isInstanceOf(SQLException.class);
        assertThat(((SQLException) root).getSQLState()).isEqualTo(expected);
    }
}
