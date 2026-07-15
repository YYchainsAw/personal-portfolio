package xyz.yychainsaw.portfolio.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
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

        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(schemaCreate).as("runtime schema CREATE").isFalse();
            softly.assertThat(databaseTemporary).as("runtime database TEMPORARY").isFalse();
            softly.assertThat(auditUpdate).as("runtime audit UPDATE").isFalse();
            softly.assertThat(auditDelete).as("runtime audit DELETE").isFalse();
            softly.assertThat(auditTruncate).as("runtime audit TRUNCATE").isFalse();
        });
    }

    @Test
    void migrationRoleFutureObjectsDoNotGrantPublicPrivilegesByDefault() throws SQLException {
        String uniqueSuffix = UUID.randomUUID().toString().replace("-", "");
        String tableName = "default_acl_table_" + uniqueSuffix;
        String functionName = "default_acl_function_" + uniqueSuffix;

        try (Connection connection = migratorDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try {
                JdbcClient migrator = JdbcClient.create(new SingleConnectionDataSource(connection, true));
                migrator.sql("create table portfolio.%s(id integer)".formatted(tableName)).update();
                migrator.sql("""
                        create function portfolio.%s()
                        returns integer
                        language sql
                        as 'select 1'
                        """.formatted(functionName)).update();

                Boolean publicHasTablePrivilege = migrator.sql("""
                        select exists (
                            select 1
                            from pg_catalog.pg_class as relation
                            join pg_catalog.pg_namespace as namespace
                              on namespace.oid = relation.relnamespace
                            cross join lateral pg_catalog.aclexplode(
                                coalesce(relation.relacl,
                                         pg_catalog.acldefault('r', relation.relowner))
                            ) as privilege
                            where namespace.nspname = 'portfolio'
                              and relation.relname = :tableName
                              and relation.relkind = 'r'
                              and privilege.grantee = 0
                        )
                        """).param("tableName", tableName).query(Boolean.class).single();
                Boolean publicHasFunctionExecute = migrator.sql("""
                        select exists (
                            select 1
                            from pg_catalog.pg_proc as procedure
                            join pg_catalog.pg_namespace as namespace
                              on namespace.oid = procedure.pronamespace
                            cross join lateral pg_catalog.aclexplode(
                                coalesce(procedure.proacl,
                                         pg_catalog.acldefault('f', procedure.proowner))
                            ) as privilege
                            where namespace.nspname = 'portfolio'
                              and procedure.proname = :functionName
                              and procedure.pronargs = 0
                              and procedure.prokind = 'f'
                              and privilege.grantee = 0
                              and privilege.privilege_type = 'EXECUTE'
                        )
                        """).param("functionName", functionName).query(Boolean.class).single();

                SoftAssertions.assertSoftly(softly -> {
                    softly.assertThat(publicHasTablePrivilege)
                            .as("PUBLIC privilege on future table")
                            .isFalse();
                    softly.assertThat(publicHasFunctionExecute)
                            .as("PUBLIC EXECUTE on future function")
                            .isFalse();
                });
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void runtimeCapabilityRoleContractIsMinimalAndInherited() {
        JdbcClient migrator = migratorJdbc();
        Boolean capabilityCanLogin = migrator.sql("""
                select rolcanlogin
                from pg_catalog.pg_roles
                where rolname = 'portfolio_runtime_access'
                """).query(Boolean.class).single();
        Boolean capabilityInherits = migrator.sql("""
                select rolinherit
                from pg_catalog.pg_roles
                where rolname = 'portfolio_runtime_access'
                """).query(Boolean.class).single();
        List<String> capabilityMembers = migrator.sql("""
                select member_role.rolname
                from pg_catalog.pg_auth_members as membership
                join pg_catalog.pg_roles as capability_role
                  on capability_role.oid = membership.roleid
                join pg_catalog.pg_roles as member_role
                  on member_role.oid = membership.member
                where capability_role.rolname = 'portfolio_runtime_access'
                order by member_role.rolname
                """).query(String.class).list();
        List<String> capabilityUpstreamRoles = migrator.sql("""
                select granted_role.rolname
                from pg_catalog.pg_auth_members as membership
                join pg_catalog.pg_roles as capability_role
                  on capability_role.oid = membership.member
                join pg_catalog.pg_roles as granted_role
                  on granted_role.oid = membership.roleid
                where capability_role.rolname = 'portfolio_runtime_access'
                order by granted_role.rolname
                """).query(String.class).list();
        List<String> runtimeMemberships = migrator.sql("""
                select granted_role.rolname
                from pg_catalog.pg_auth_members as membership
                join pg_catalog.pg_roles as runtime_role
                  on runtime_role.oid = membership.member
                join pg_catalog.pg_roles as granted_role
                  on granted_role.oid = membership.roleid
                where runtime_role.rolname = 'test_runtime'
                order by granted_role.rolname
                """).query(String.class).list();
        MembershipOptions membershipOptions = migrator.sql("""
                select membership.admin_option,
                       membership.inherit_option,
                       membership.set_option
                from pg_catalog.pg_auth_members as membership
                join pg_catalog.pg_roles as capability_role
                  on capability_role.oid = membership.roleid
                join pg_catalog.pg_roles as runtime_role
                  on runtime_role.oid = membership.member
                where capability_role.rolname = 'portfolio_runtime_access'
                  and runtime_role.rolname = 'test_runtime'
                """).query((resultSet, rowNumber) -> new MembershipOptions(
                        resultSet.getBoolean("admin_option"),
                        resultSet.getBoolean("inherit_option"),
                        resultSet.getBoolean("set_option"))).single();
        Boolean effectiveUsage = jdbc.sql(
                        "select pg_has_role(current_user, 'portfolio_runtime_access', 'USAGE')")
                .query(Boolean.class).single();
        Boolean effectiveSet = jdbc.sql(
                        "select pg_has_role(current_user, 'portfolio_runtime_access', 'SET')")
                .query(Boolean.class).single();

        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(capabilityCanLogin).as("capability LOGIN").isFalse();
            softly.assertThat(capabilityInherits).as("capability INHERIT").isFalse();
            softly.assertThat(capabilityMembers).containsExactly("test_runtime");
            softly.assertThat(capabilityUpstreamRoles).isEmpty();
            softly.assertThat(runtimeMemberships).containsExactly("portfolio_runtime_access");
            softly.assertThat(membershipOptions)
                    .isEqualTo(new MembershipOptions(false, true, false));
            softly.assertThat(effectiveUsage).as("runtime capability USAGE").isTrue();
            softly.assertThat(effectiveSet).as("runtime capability SET").isFalse();
        });
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

    private record MembershipOptions(boolean admin, boolean inherit, boolean set) {}
}
