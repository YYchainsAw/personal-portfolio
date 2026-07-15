package xyz.yychainsaw.portfolio.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.List;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
class MigrationSmokeTest extends PostgresIntegrationTestBase {
    @Autowired JdbcClient jdbc;

    @Test
    void flywayAppliesFoundationVersionsInOrderAndCreatesSessionTables() {
        List<String> versions = migratorJdbc()
                .sql("""
                        select version from flyway_schema_history
                        where success and version is not null
                        order by installed_rank
                        """)
                .query(String.class).list();
        assertThat(versions).containsExactly("1", "2");

        Integer tableCount = jdbc.sql("""
                select count(*) from information_schema.tables
                where table_schema='portfolio' and table_name in
                ('spring_session','spring_session_attributes','admin_user',
                 'totp_recovery_code','admin_session_metadata','audit_log')
                """).query(Integer.class).single();
        assertThat(tableCount).isEqualTo(6);
    }

    @Test
    void runtimeAccountCannotCreateTables() {
        assertRuntimeDdlPermissionDenied("""
                do $$
                begin
                    create table runtime_must_not_create(id integer);
                    raise exception 'runtime persistent table creation unexpectedly allowed';
                end;
                $$
                """);
    }

    @Test
    void runtimeAccountCannotCreateTemporaryTables() {
        assertRuntimeDdlPermissionDenied("""
                do $$
                begin
                    create temporary table runtime_must_not_create_temp(id integer);
                    raise exception 'runtime temporary table creation unexpectedly allowed';
                end;
                $$
                """);
    }

    @Test
    void runtimePrivilegeMatrixIsLeastPrivilege() {
        Boolean schemaCreate = jdbc.sql(
                        "select has_schema_privilege(current_user, 'portfolio', 'CREATE')")
                .query(Boolean.class).single();
        Boolean databaseTemporary = jdbc.sql(
                        "select has_database_privilege(current_user, current_database(), 'TEMPORARY')")
                .query(Boolean.class).single();
        Boolean auditUpdate = jdbc.sql(
                        "select has_table_privilege(current_user, 'portfolio.audit_log', 'UPDATE')")
                .query(Boolean.class).single();
        Boolean auditDelete = jdbc.sql(
                        "select has_table_privilege(current_user, 'portfolio.audit_log', 'DELETE')")
                .query(Boolean.class).single();
        Boolean auditTruncate = jdbc.sql(
                        "select has_table_privilege(current_user, 'portfolio.audit_log', 'TRUNCATE')")
                .query(Boolean.class).single();
        Boolean runtimeAccessUsage = jdbc.sql(
                        "select pg_has_role(current_user, 'portfolio_runtime_access', 'USAGE')")
                .query(Boolean.class).single();
        Boolean runtimeAccessSet = jdbc.sql(
                        "select pg_has_role(current_user, 'portfolio_runtime_access', 'SET')")
                .query(Boolean.class).single();

        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(schemaCreate).as("runtime schema CREATE").isFalse();
            softly.assertThat(databaseTemporary).as("runtime database TEMPORARY").isFalse();
            softly.assertThat(auditUpdate).as("runtime audit UPDATE").isFalse();
            softly.assertThat(auditDelete).as("runtime audit DELETE").isFalse();
            softly.assertThat(auditTruncate).as("runtime audit TRUNCATE").isFalse();
            softly.assertThat(runtimeAccessUsage).as("runtime capability role USAGE").isTrue();
            softly.assertThat(runtimeAccessSet).as("runtime capability role SET").isFalse();
        });
    }

    @Test
    void migrationRoleFutureFunctionsDoNotGrantPublicExecuteByDefault() {
        Boolean noPublicExecute = migratorJdbc().sql("""
                with effective_default as (
                    select coalesce(
                        (
                            select defaclacl
                            from pg_default_acl
                            where defaclrole = current_user::regrole
                              and defaclnamespace = 0
                              and defaclobjtype = 'f'
                        ),
                        acldefault('f', current_user::regrole)
                    ) as acl
                )
                select not exists (
                    select 1
                    from effective_default
                    cross join lateral aclexplode(effective_default.acl) as privilege
                    where privilege.grantee = 0
                      and privilege.privilege_type = 'EXECUTE'
                )
                """).query(Boolean.class).single();

        assertThat(noPublicExecute).isTrue();
    }

    @Test
    void auditActorForeignKeyRestrictsAdminDeletion() {
        String deleteRule = migratorJdbc().sql("""
                select referential.delete_rule
                from information_schema.referential_constraints referential
                join information_schema.key_column_usage key_column
                  on key_column.constraint_catalog = referential.constraint_catalog
                 and key_column.constraint_schema = referential.constraint_schema
                 and key_column.constraint_name = referential.constraint_name
                where key_column.table_schema = 'portfolio'
                  and key_column.table_name = 'audit_log'
                  and key_column.column_name = 'actor_admin_id'
                """).query(String.class).single();

        assertThat(deleteRule).isEqualTo("RESTRICT");
    }

    @Test
    void portfolioSchemaIsOwnedByTheMigrationRole() {
        JdbcClient migrator = migratorJdbc();
        String migrationRole = migrator.sql("select current_user").query(String.class).single();
        String schemaOwner = migrator.sql("""
                select pg_get_userbyid(nspowner)
                from pg_namespace
                where nspname = 'portfolio'
                """).query(String.class).single();

        assertThat(schemaOwner).isEqualTo(migrationRole);
    }

    private void assertRuntimeDdlPermissionDenied(String ddl) {
        assertThatThrownBy(() -> jdbc.sql(ddl).update())
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("permission denied");
    }
}
