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
    void flywayHistoryDoesNotExposeTablePrivilegesToPublicOrRuntime() {
        Boolean publicHasAnyTablePrivilege = migratorJdbc().sql("""
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
                      and relation.relname = 'flyway_schema_history'
                      and relation.relkind = 'r'
                      and privilege.grantee = 0
                )
                """).query(Boolean.class).single();
        Boolean runtimeHasAnyTablePrivilege = jdbc.sql("""
                select has_table_privilege(
                    current_user,
                    'portfolio.flyway_schema_history',
                    'SELECT, INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER, MAINTAIN'
                )
                """).query(Boolean.class).single();

        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(publicHasAnyTablePrivilege)
                    .as("PUBLIC privilege on Flyway history")
                    .isFalse();
            softly.assertThat(runtimeHasAnyTablePrivilege)
                    .as("runtime table privilege on Flyway history")
                    .isFalse();
        });
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
    void userSchemaDirectAclIsLimitedToNonGrantableCapabilityUsage() {
        List<SchemaAclEntry> protectedRoleAcl = migratorJdbc().sql("""
                select grantee_role.rolname,
                       namespace.nspname,
                       privilege.privilege_type,
                       privilege.is_grantable
                from pg_catalog.pg_namespace as namespace
                cross join lateral pg_catalog.aclexplode(
                    coalesce(namespace.nspacl,
                             pg_catalog.acldefault('n', namespace.nspowner))
                ) as privilege
                join pg_catalog.pg_roles as grantee_role
                  on grantee_role.oid = privilege.grantee
                where namespace.nspname !~ '^pg_'
                  and namespace.nspname <> 'information_schema'
                  and grantee_role.rolname in (
                      'portfolio_runtime_access', 'test_runtime'
                  )
                order by grantee_role.rolname,
                         namespace.nspname,
                         privilege.privilege_type
                """).query((resultSet, rowNumber) -> new SchemaAclEntry(
                        resultSet.getString("rolname"),
                        resultSet.getString("nspname"),
                        resultSet.getString("privilege_type"),
                        resultSet.getBoolean("is_grantable")))
                .list();
        Boolean runtimeCanCreate = jdbc.sql(
                        "select has_schema_privilege(current_user, 'portfolio', 'CREATE')")
                .query(Boolean.class).single();

        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(protectedRoleAcl)
                    .as("direct user-schema ACL for runtime and capability")
                    .containsExactly(new SchemaAclEntry(
                            "portfolio_runtime_access", "portfolio", "USAGE", false));
            softly.assertThat(runtimeCanCreate)
                    .as("runtime effective CREATE on portfolio")
                    .isFalse();
        });
    }

    @Test
    void databaseAclIsExactNonGrantableAndEffectiveForProtectedRoles() {
        JdbcClient migrator = migratorJdbc();
        List<DatabaseAclEntry> directAcl = migrator.sql("""
                select case
                           when privilege.grantee = 0 then 'PUBLIC'
                           else grantee_role.rolname
                       end as grantee,
                       privilege.privilege_type,
                       privilege.is_grantable
                from pg_catalog.pg_database as database
                cross join lateral pg_catalog.aclexplode(
                    coalesce(database.datacl,
                             pg_catalog.acldefault('d', database.datdba))
                ) as privilege
                left join pg_catalog.pg_roles as grantee_role
                  on grantee_role.oid = privilege.grantee
                where database.datname = current_database()
                  and (
                      privilege.grantee = 0
                      or grantee_role.rolname in (
                          'portfolio_runtime_access', 'test_runtime', 'test_migrator'
                      )
                  )
                order by grantee, privilege.privilege_type
                """).query((resultSet, rowNumber) -> new DatabaseAclEntry(
                        resultSet.getString("grantee"),
                        resultSet.getString("privilege_type"),
                        resultSet.getBoolean("is_grantable")))
                .list();
        List<DatabaseEffectivePrivileges> effectivePrivileges = migrator.sql("""
                select protected_role.rolname,
                       pg_catalog.has_database_privilege(
                           protected_role.oid, database.oid, 'CONNECT'
                       ) as can_connect,
                       pg_catalog.has_database_privilege(
                           protected_role.oid, database.oid, 'CREATE'
                       ) as can_create,
                       pg_catalog.has_database_privilege(
                           protected_role.oid, database.oid, 'TEMPORARY'
                       ) as can_create_temporary
                from pg_catalog.pg_roles as protected_role
                cross join pg_catalog.pg_database as database
                where database.datname = current_database()
                  and protected_role.rolname in (
                      'portfolio_runtime_access', 'test_runtime', 'test_migrator'
                  )
                order by protected_role.rolname
                """).query((resultSet, rowNumber) -> new DatabaseEffectivePrivileges(
                        resultSet.getString("rolname"),
                        resultSet.getBoolean("can_connect"),
                        resultSet.getBoolean("can_create"),
                        resultSet.getBoolean("can_create_temporary")))
                .list();

        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(directAcl)
                    .as("exact direct database ACL for PUBLIC and protected roles")
                    .containsExactly(
                            new DatabaseAclEntry("test_migrator", "CONNECT", false),
                            new DatabaseAclEntry("test_migrator", "CREATE", false),
                            new DatabaseAclEntry("test_runtime", "CONNECT", false));
            softly.assertThat(effectivePrivileges)
                    .as("effective protected-role database privileges")
                    .containsExactly(
                            new DatabaseEffectivePrivileges(
                                    "portfolio_runtime_access", false, false, false),
                            new DatabaseEffectivePrivileges(
                                    "test_migrator", true, true, false),
                            new DatabaseEffectivePrivileges(
                                    "test_runtime", true, false, false));
        });
    }

    @Test
    void migrationRoleFutureObjectsDoNotGrantImplicitRuntimeOrPublicPrivilegesByDefault()
            throws SQLException {
        String uniqueSuffix = UUID.randomUUID().toString().replace("-", "");
        String portfolioTableName = "default_acl_table_" + uniqueSuffix;
        String alternateSchemaName = "default_acl_schema_" + uniqueSuffix;
        String alternateTableName = "global_default_acl_table";
        String functionName = "default_acl_function_" + uniqueSuffix;

        try (Connection connection = migratorDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try {
                JdbcClient migrator = JdbcClient.create(new SingleConnectionDataSource(connection, true));
                migrator.sql("create schema %s".formatted(alternateSchemaName)).update();
                migrator.sql("create table %s.%s(id integer)"
                        .formatted(alternateSchemaName, alternateTableName)).update();
                migrator.sql("create table portfolio.%s(id integer)"
                        .formatted(portfolioTableName)).update();
                migrator.sql("""
                        create function portfolio.%s()
                        returns integer
                        language sql
                        as 'select 1'
                        """.formatted(functionName)).update();

                Boolean publicHasPortfolioTablePrivilege = migrator.sql("""
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
                        """).param("tableName", portfolioTableName)
                        .query(Boolean.class).single();
                Boolean publicHasAlternateTablePrivilege = migrator.sql("""
                        select exists (
                            select 1
                            from pg_catalog.pg_class as relation
                            join pg_catalog.pg_namespace as namespace
                              on namespace.oid = relation.relnamespace
                            cross join lateral pg_catalog.aclexplode(
                                coalesce(relation.relacl,
                                         pg_catalog.acldefault('r', relation.relowner))
                            ) as privilege
                            where namespace.nspname = :schemaName
                              and relation.relname = :tableName
                              and relation.relkind = 'r'
                              and privilege.grantee = 0
                        )
                        """).param("schemaName", alternateSchemaName)
                        .param("tableName", alternateTableName)
                        .query(Boolean.class).single();
                Boolean capabilityHasProbeTablePrivilege = migrator.sql("""
                        select exists (
                            select 1
                            from pg_catalog.pg_class as relation
                            join pg_catalog.pg_namespace as namespace
                              on namespace.oid = relation.relnamespace
                            cross join lateral pg_catalog.aclexplode(
                                coalesce(relation.relacl,
                                         pg_catalog.acldefault('r', relation.relowner))
                            ) as privilege
                            where relation.relkind = 'r'
                              and (
                                  (
                                      namespace.nspname = 'portfolio'
                                      and relation.relname = :portfolioTableName
                                  )
                                  or (
                                      namespace.nspname = :alternateSchemaName
                                      and relation.relname = :alternateTableName
                                  )
                              )
                              and privilege.grantee = (
                                  select capability_role.oid
                                  from pg_catalog.pg_roles as capability_role
                                  where capability_role.rolname = 'portfolio_runtime_access'
                              )
                        )
                        """).param("portfolioTableName", portfolioTableName)
                        .param("alternateSchemaName", alternateSchemaName)
                        .param("alternateTableName", alternateTableName)
                        .query(Boolean.class).single();
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
                Boolean capabilityHasFunctionExecute = migrator.sql("""
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
                              and privilege.grantee = (
                                  select capability_role.oid
                                  from pg_catalog.pg_roles as capability_role
                                  where capability_role.rolname = 'portfolio_runtime_access'
                              )
                              and privilege.privilege_type = 'EXECUTE'
                        )
                        """).param("functionName", functionName)
                        .query(Boolean.class).single();

                SoftAssertions.assertSoftly(softly -> {
                    softly.assertThat(publicHasPortfolioTablePrivilege)
                            .as("PUBLIC privilege on future portfolio table")
                            .isFalse();
                    softly.assertThat(publicHasAlternateTablePrivilege)
                            .as("PUBLIC privilege on future alternate-schema table")
                            .isFalse();
                    softly.assertThat(capabilityHasProbeTablePrivilege)
                            .as("capability privilege on a future probe table")
                            .isFalse();
                    softly.assertThat(publicHasFunctionExecute)
                            .as("PUBLIC EXECUTE on future function")
                            .isFalse();
                    softly.assertThat(capabilityHasFunctionExecute)
                            .as("capability EXECUTE on future function")
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
        List<MembershipEdge> protectedRoleEdges = migrator.sql("""
                select granted_role.rolname as granted_role,
                       member_role.rolname as member_role,
                       membership.admin_option,
                       membership.inherit_option,
                       membership.set_option
                from pg_catalog.pg_auth_members as membership
                join pg_catalog.pg_roles as granted_role
                  on granted_role.oid = membership.roleid
                join pg_catalog.pg_roles as member_role
                  on member_role.oid = membership.member
                where granted_role.rolname in (
                          'portfolio_runtime_access', 'test_runtime', 'test_migrator'
                      )
                   or member_role.rolname in (
                          'portfolio_runtime_access', 'test_runtime', 'test_migrator'
                      )
                order by granted_role.rolname, member_role.rolname
                """).query((resultSet, rowNumber) -> new MembershipEdge(
                        resultSet.getString("granted_role"),
                        resultSet.getString("member_role"),
                        resultSet.getBoolean("admin_option"),
                        resultSet.getBoolean("inherit_option"),
                        resultSet.getBoolean("set_option")))
                .list();
        Boolean effectiveUsage = jdbc.sql(
                        "select pg_has_role(current_user, 'portfolio_runtime_access', 'USAGE')")
                .query(Boolean.class).single();
        Boolean effectiveSet = jdbc.sql(
                        "select pg_has_role(current_user, 'portfolio_runtime_access', 'SET')")
                .query(Boolean.class).single();

        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(capabilityCanLogin).as("capability LOGIN").isFalse();
            softly.assertThat(capabilityInherits).as("capability INHERIT").isFalse();
            softly.assertThat(protectedRoleEdges)
                    .as("complete protected-role incident graph")
                    .containsExactly(new MembershipEdge(
                            "portfolio_runtime_access", "test_runtime", false, true, false));
            softly.assertThat(effectiveUsage).as("runtime capability USAGE").isTrue();
            softly.assertThat(effectiveSet).as("runtime capability SET").isFalse();
        });
    }

    @Test
    void loginRoleSettingsContainOnlyRoleWidePortfolioSearchPath() {
        List<RoleSetting> roleSettings = migratorJdbc().sql("""
                select configured_role.rolname,
                       role_setting.setdatabase,
                       pg_catalog.cardinality(role_setting.setconfig) as setting_count,
                       configuration.setting_value
                from pg_catalog.pg_db_role_setting as role_setting
                join pg_catalog.pg_roles as configured_role
                  on configured_role.oid = role_setting.setrole
                cross join lateral pg_catalog.unnest(role_setting.setconfig)
                  as configuration(setting_value)
                where configured_role.rolname in ('test_migrator', 'test_runtime')
                order by configured_role.rolname,
                         role_setting.setdatabase,
                         configuration.setting_value
                """).query((resultSet, rowNumber) -> new RoleSetting(
                        resultSet.getString("rolname"),
                        resultSet.getLong("setdatabase"),
                        resultSet.getInt("setting_count"),
                        resultSet.getString("setting_value")))
                .list();

        assertThat(roleSettings).containsExactly(
                new RoleSetting("test_migrator", 0L, 1, "search_path=portfolio, public"),
                new RoleSetting("test_runtime", 0L, 1, "search_path=portfolio, public"));
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

    private record SchemaAclEntry(
            String grantee, String schema, String privilege, boolean grantable) {}

    private record DatabaseAclEntry(String grantee, String privilege, boolean grantable) {}

    private record DatabaseEffectivePrivileges(
            String role, boolean connect, boolean create, boolean temporary) {}

    private record MembershipEdge(
            String grantedRole,
            String memberRole,
            boolean admin,
            boolean inherit,
            boolean set) {}

    private record RoleSetting(
            String role, long databaseOid, int settingCount, String setting) {}
}
