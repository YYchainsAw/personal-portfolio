package xyz.yychainsaw.portfolio.publishing.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.assertj.core.api.SoftAssertions;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
@Isolated
class PublishingMigrationTest extends PostgresIntegrationTestBase {
    private static final UUID SITE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PROJECT_CATALOG_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final List<String> PUBLISHING_TABLES = List.of(
            "content_revision",
            "publication",
            "revision_media_reference",
            "slug_redirect");

    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate transactions;

    @Test
    void v8CreatesPublishingTablesAndFixedSingletonPointers() {
        FreshMigrationState state = inFreshMigratedDatabase(clients -> {
            List<String> versions = clients.migrator().sql("""
                            select version
                            from portfolio.flyway_schema_history
                            where success and version is not null
                            order by installed_rank
                            """)
                    .query(String.class)
                    .list();
            List<String> tables = clients.migrator().sql("""
                            select table_name
                            from information_schema.tables
                            where table_schema='portfolio'
                              and table_name = any(cast(:tables as text[]))
                            order by table_name
                            """)
                    .param("tables", PUBLISHING_TABLES.toArray(String[]::new))
                    .query(String.class)
                    .list();
            List<PublicationPointer> pointers = clients.runtime().sql("""
                            select aggregate_type, aggregate_id, status,
                                   current_revision_id, current_slug, version, published_at
                            from portfolio.publication
                            where (aggregate_type='SITE' and aggregate_id=:siteId)
                               or (aggregate_type='PROJECT_CATALOG' and aggregate_id=:catalogId)
                            order by aggregate_type
                            """)
                    .param("siteId", SITE_ID)
                    .param("catalogId", PROJECT_CATALOG_ID)
                    .query((resultSet, rowNumber) -> new PublicationPointer(
                            resultSet.getString("aggregate_type"),
                            resultSet.getObject("aggregate_id", UUID.class),
                            resultSet.getString("status"),
                            resultSet.getObject("current_revision_id", UUID.class),
                            resultSet.getString("current_slug"),
                            resultSet.getLong("version"),
                            resultSet.getObject("published_at")))
                    .list();
            return new FreshMigrationState(versions, tables, pointers);
        });

        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(state.versions())
                    .startsWith("1", "2", "3", "4", "5", "6", "7", "8");
            softly.assertThat(state.tables()).containsExactlyElementsOf(PUBLISHING_TABLES);
            softly.assertThat(state.pointers()).containsExactly(
                    new PublicationPointer(
                            "PROJECT_CATALOG",
                            PROJECT_CATALOG_ID,
                            "ARCHIVED",
                            null,
                            null,
                            0L,
                            null),
                    new PublicationPointer(
                            "SITE", SITE_ID, "ARCHIVED", null, null, 0L, null));
        });
    }

    @Test
    void v8InstallsNamedPublishingConstraintsAndIndexes() {
        List<String> constraints = migratorJdbc().sql("""
                        select relation.relname || ':' || constraint_record.conname
                        from pg_catalog.pg_constraint constraint_record
                        join pg_catalog.pg_class relation
                          on relation.oid=constraint_record.conrelid
                        join pg_catalog.pg_namespace namespace
                          on namespace.oid=relation.relnamespace
                        where namespace.nspname='portfolio'
                          and relation.relname = any(cast(:tables as text[]))
                        order by relation.relname, constraint_record.conname
                        """)
                .param("tables", PUBLISHING_TABLES.toArray(String[]::new))
                .query(String.class)
                .list();
        String revisionUniqueColumns = migratorJdbc().sql("""
                        select pg_catalog.array_to_string(
                            array(
                                select attribute.attname
                                from unnest(constraint_record.conkey)
                                    with ordinality key_column(attnum, position)
                                join pg_catalog.pg_attribute attribute
                                  on attribute.attrelid=constraint_record.conrelid
                                 and attribute.attnum=key_column.attnum
                                order by key_column.position
                            ),
                            ','
                        )
                        from pg_catalog.pg_constraint constraint_record
                        where constraint_record.conrelid=
                                  'portfolio.content_revision'::regclass
                          and constraint_record.conname=
                                  'content_revision_aggregate_version_uk'
                          and constraint_record.contype='u'
                        """)
                .query(String.class)
                .single();
        List<ForeignKeyDefinition> foreignKeys = migratorJdbc().sql("""
                        select source_relation.relname source_table,
                               constraint_record.conname,
                               pg_catalog.array_to_string(
                                   array(
                                       select attribute.attname
                                       from unnest(constraint_record.conkey)
                                           with ordinality key_column(attnum, position)
                                       join pg_catalog.pg_attribute attribute
                                         on attribute.attrelid=constraint_record.conrelid
                                        and attribute.attnum=key_column.attnum
                                       order by key_column.position
                                   ),
                                   ','
                               ) source_columns,
                               target_namespace.nspname target_schema,
                               target_relation.relname target_table,
                               pg_catalog.array_to_string(
                                   array(
                                       select attribute.attname
                                       from unnest(constraint_record.confkey)
                                           with ordinality key_column(attnum, position)
                                       join pg_catalog.pg_attribute attribute
                                         on attribute.attrelid=constraint_record.confrelid
                                        and attribute.attnum=key_column.attnum
                                       order by key_column.position
                                   ),
                                   ','
                               ) target_columns,
                               constraint_record.confdeltype::text delete_action
                        from pg_catalog.pg_constraint constraint_record
                        join pg_catalog.pg_class source_relation
                          on source_relation.oid=constraint_record.conrelid
                        join pg_catalog.pg_namespace source_namespace
                          on source_namespace.oid=source_relation.relnamespace
                        join pg_catalog.pg_class target_relation
                          on target_relation.oid=constraint_record.confrelid
                        join pg_catalog.pg_namespace target_namespace
                          on target_namespace.oid=target_relation.relnamespace
                        where source_namespace.nspname='portfolio'
                          and source_relation.relname = any(cast(:tables as text[]))
                          and constraint_record.contype='f'
                        order by source_relation.relname, constraint_record.conname
                        """)
                .param("tables", PUBLISHING_TABLES.toArray(String[]::new))
                .query((resultSet, rowNumber) -> new ForeignKeyDefinition(
                        resultSet.getString("source_table"),
                        resultSet.getString("conname"),
                        resultSet.getString("source_columns"),
                        resultSet.getString("target_schema"),
                        resultSet.getString("target_table"),
                        resultSet.getString("target_columns"),
                        resultSet.getString("delete_action")))
                .list();
        List<IndexDefinition> indexes = migratorJdbc().sql("""
                        select index_relation.relname indexname,
                               index_record.indisunique,
                               pg_catalog.array_to_string(
                                   array(
                                       select attribute.attname
                                       from unnest(index_record.indkey)
                                           with ordinality key_column(attnum, position)
                                       join pg_catalog.pg_attribute attribute
                                         on attribute.attrelid=index_record.indrelid
                                        and attribute.attnum=key_column.attnum
                                       where key_column.position <= index_record.indnkeyatts
                                       order by key_column.position
                                   ),
                                   ','
                               ) key_columns,
                               pg_catalog.pg_get_expr(
                                   index_record.indpred,
                                   index_record.indrelid
                               ) predicate,
                               pg_catalog.pg_get_indexdef(index_relation.oid) indexdef
                        from pg_catalog.pg_index index_record
                        join pg_catalog.pg_class index_relation
                          on index_relation.oid=index_record.indexrelid
                        join pg_catalog.pg_namespace namespace
                          on namespace.oid=index_relation.relnamespace
                        where namespace.nspname='portfolio'
                          and index_relation.relname in (
                              'idx_content_revision_aggregate',
                              'idx_revision_media_asset',
                              'uq_publication_current_slug'
                          )
                        order by index_relation.relname
                        """)
                .query((resultSet, rowNumber) -> new IndexDefinition(
                        resultSet.getString("indexname"),
                        resultSet.getBoolean("indisunique"),
                        resultSet.getString("key_columns"),
                        resultSet.getString("predicate"),
                        resultSet.getString("indexdef")))
                .list();

        assertThat(constraints).contains(
                "content_revision:content_revision_admin_fk",
                "content_revision:content_revision_aggregate_type_ck",
                "content_revision:content_revision_aggregate_version_uk",
                "content_revision:content_revision_checksum_ck",
                "content_revision:content_revision_pk",
                "content_revision:content_revision_snapshot_schema_version_ck",
                "content_revision:content_revision_version_ck",
                "publication:publication_aggregate_type_ck",
                "publication:publication_current_revision_ck",
                "publication:publication_current_slug_ck",
                "publication:publication_pk",
                "publication:publication_revision_fk",
                "publication:publication_status_ck",
                "publication:publication_version_ck",
                "revision_media_reference:revision_media_reference_asset_fk",
                "revision_media_reference:revision_media_reference_pk",
                "revision_media_reference:revision_media_reference_revision_fk",
                "revision_media_reference:revision_media_reference_variant_fk",
                "slug_redirect:slug_redirect_distinct_ck",
                "slug_redirect:slug_redirect_new_slug_ck",
                "slug_redirect:slug_redirect_old_slug_ck",
                "slug_redirect:slug_redirect_pk",
                "slug_redirect:slug_redirect_project_fk");
        assertThat(revisionUniqueColumns)
                .isEqualTo("aggregate_type,aggregate_id,version");
        assertThat(foreignKeys).containsExactly(
                new ForeignKeyDefinition(
                        "content_revision",
                        "content_revision_admin_fk",
                        "published_by",
                        "portfolio",
                        "admin_user",
                        "id",
                        "r"),
                new ForeignKeyDefinition(
                        "publication",
                        "publication_revision_fk",
                        "current_revision_id",
                        "portfolio",
                        "content_revision",
                        "id",
                        "r"),
                new ForeignKeyDefinition(
                        "revision_media_reference",
                        "revision_media_reference_asset_fk",
                        "asset_id",
                        "portfolio",
                        "media_asset",
                        "id",
                        "r"),
                new ForeignKeyDefinition(
                        "revision_media_reference",
                        "revision_media_reference_revision_fk",
                        "revision_id",
                        "portfolio",
                        "content_revision",
                        "id",
                        "r"),
                new ForeignKeyDefinition(
                        "revision_media_reference",
                        "revision_media_reference_variant_fk",
                        "asset_id,variant_name",
                        "portfolio",
                        "media_variant",
                        "asset_id,variant_name",
                        "r"),
                new ForeignKeyDefinition(
                        "slug_redirect",
                        "slug_redirect_project_fk",
                        "project_id",
                        "portfolio",
                        "project",
                        "id",
                        "c"));
        assertThat(indexes)
                .extracting(
                        IndexDefinition::name,
                        IndexDefinition::unique,
                        IndexDefinition::keyColumns)
                .containsExactly(
                        tuple(
                                "idx_content_revision_aggregate",
                                false,
                                "aggregate_type,aggregate_id,version"),
                        tuple(
                                "idx_revision_media_asset",
                                false,
                                "asset_id,variant_name"),
                        tuple("uq_publication_current_slug", true, "current_slug"));
        assertThat(indexes.get(0).definition()).contains("version DESC");
        assertThat(indexes.get(0).predicate()).isNull();
        assertThat(indexes.get(1).predicate()).isNull();
        assertThat(indexes.get(2).predicate())
                .isEqualTo(
                        "(((status)::text = 'PUBLISHED'::text) "
                                + "AND (current_slug IS NOT NULL))");
    }

    @Test
    void v8InstallsEnabledRowLevelHistoryImmutabilityTriggers() {
        List<HistoryTrigger> triggers = migratorJdbc().sql("""
                        select relation.relname,
                               trigger_record.tgname,
                               trigger_record.tgenabled,
                               trigger_record.tgtype,
                               function_namespace.nspname || '.' || function_record.proname
                                   function_name
                        from pg_catalog.pg_trigger trigger_record
                        join pg_catalog.pg_class relation
                          on relation.oid=trigger_record.tgrelid
                        join pg_catalog.pg_namespace namespace
                          on namespace.oid=relation.relnamespace
                        join pg_catalog.pg_proc function_record
                          on function_record.oid=trigger_record.tgfoid
                        join pg_catalog.pg_namespace function_namespace
                          on function_namespace.oid=function_record.pronamespace
                        where namespace.nspname='portfolio'
                          and not trigger_record.tgisinternal
                          and trigger_record.tgname in (
                              'content_revision_immutable',
                              'revision_media_reference_immutable'
                          )
                        order by relation.relname
                        """)
                .query((resultSet, rowNumber) -> new HistoryTrigger(
                        resultSet.getString("relname"),
                        resultSet.getString("tgname"),
                        resultSet.getString("tgenabled"),
                        resultSet.getInt("tgtype"),
                        resultSet.getString("function_name")))
                .list();
        Boolean publicCanExecute = migratorJdbc().sql("""
                        select pg_catalog.has_function_privilege(
                            'public',
                            'portfolio.reject_published_history_mutation()',
                            'EXECUTE'
                        )
                        """)
                .query(Boolean.class)
                .single();
        Boolean runtimeCanExecute = migratorJdbc().sql("""
                        select pg_catalog.has_function_privilege(
                            'portfolio_runtime_access',
                            'portfolio.reject_published_history_mutation()',
                            'EXECUTE'
                        )
                        """)
                .query(Boolean.class)
                .single();

        assertThat(triggers)
                .extracting(
                        HistoryTrigger::table,
                        HistoryTrigger::name,
                        HistoryTrigger::enabled,
                        HistoryTrigger::type,
                        HistoryTrigger::functionName)
                .containsExactly(
                        tuple(
                                "content_revision",
                                "content_revision_immutable",
                                "O",
                                27,
                                "portfolio.reject_published_history_mutation"),
                        tuple(
                                "revision_media_reference",
                                "revision_media_reference_immutable",
                                "O",
                                27,
                                "portfolio.reject_published_history_mutation"));
        assertThat(publicCanExecute).isFalse();
        assertThat(runtimeCanExecute).isFalse();
    }

    @Test
    void runtimeHasOnlyTheApprovedPublishingCapabilities() {
        List<TablePrivilege> runtimePrivileges = migratorJdbc().sql("""
                        select relation.relname,
                               privilege.privilege_type,
                               privilege.is_grantable
                        from pg_catalog.pg_class relation
                        join pg_catalog.pg_namespace namespace
                          on namespace.oid=relation.relnamespace
                        cross join lateral pg_catalog.aclexplode(
                            coalesce(
                                relation.relacl,
                                pg_catalog.acldefault('r', relation.relowner)
                            )
                        ) privilege
                        join pg_catalog.pg_roles grantee_role
                          on grantee_role.oid=privilege.grantee
                        where namespace.nspname='portfolio'
                          and relation.relname = any(cast(:tables as text[]))
                          and grantee_role.rolname='portfolio_runtime_access'
                        order by relation.relname, privilege.privilege_type
                        """)
                .param("tables", PUBLISHING_TABLES.toArray(String[]::new))
                .query((resultSet, rowNumber) -> new TablePrivilege(
                        resultSet.getString("relname"),
                        resultSet.getString("privilege_type"),
                        resultSet.getBoolean("is_grantable")))
                .list();
        List<TablePrivilege> effectiveRuntimePrivileges = jdbc.sql("""
                        select relation.relname,
                               requested.privilege_type,
                               false is_grantable
                        from pg_catalog.pg_class relation
                        join pg_catalog.pg_namespace namespace
                          on namespace.oid=relation.relnamespace
                        cross join (values
                            ('SELECT'),
                            ('INSERT'),
                            ('UPDATE'),
                            ('DELETE'),
                            ('TRUNCATE'),
                            ('REFERENCES'),
                            ('TRIGGER'),
                            ('MAINTAIN')
                        ) requested(privilege_type)
                        where namespace.nspname='portfolio'
                          and relation.relname = any(cast(:tables as text[]))
                          and pg_catalog.has_table_privilege(
                              current_user,
                              relation.oid,
                              requested.privilege_type
                          )
                        order by relation.relname, requested.privilege_type
                        """)
                .param("tables", PUBLISHING_TABLES.toArray(String[]::new))
                .query((resultSet, rowNumber) -> new TablePrivilege(
                        resultSet.getString("relname"),
                        resultSet.getString("privilege_type"),
                        resultSet.getBoolean("is_grantable")))
                .list();
        Long publicPrivileges = migratorJdbc().sql("""
                        select count(*)
                        from pg_catalog.pg_class relation
                        join pg_catalog.pg_namespace namespace
                          on namespace.oid=relation.relnamespace
                        cross join lateral pg_catalog.aclexplode(
                            coalesce(
                                relation.relacl,
                                pg_catalog.acldefault('r', relation.relowner)
                            )
                        ) privilege
                        where namespace.nspname='portfolio'
                          and relation.relname = any(cast(:tables as text[]))
                          and privilege.grantee=0
                        """)
                .param("tables", PUBLISHING_TABLES.toArray(String[]::new))
                .query(Long.class)
                .single();
        Long protectedColumnPrivileges = migratorJdbc().sql("""
                        select count(*)
                        from pg_catalog.pg_class relation
                        join pg_catalog.pg_namespace namespace
                          on namespace.oid=relation.relnamespace
                        join pg_catalog.pg_attribute attribute
                          on attribute.attrelid=relation.oid
                         and attribute.attnum > 0
                         and not attribute.attisdropped
                        cross join lateral pg_catalog.aclexplode(attribute.attacl) privilege
                        where namespace.nspname='portfolio'
                          and relation.relname = any(cast(:tables as text[]))
                          and attribute.attacl is not null
                          and (
                              privilege.grantee=0
                              or privilege.grantee in (
                                  select role_record.oid
                                  from pg_catalog.pg_roles role_record
                                  where role_record.rolname in (
                                      'portfolio_runtime_access',
                                      'test_runtime'
                                  )
                              )
                          )
                        """)
                .param("tables", PUBLISHING_TABLES.toArray(String[]::new))
                .query(Long.class)
                .single();
        Long dangerousEffectiveColumnPrivileges = jdbc.sql("""
                        select count(*)
                        from pg_catalog.pg_class relation
                        join pg_catalog.pg_namespace namespace
                          on namespace.oid=relation.relnamespace
                        where namespace.nspname='portfolio'
                          and relation.relname = any(cast(:tables as text[]))
                          and (
                              pg_catalog.has_any_column_privilege(
                                  current_user,
                                  relation.oid,
                                  'REFERENCES'
                              )
                              or (
                                  relation.relname <> 'publication'
                                  and pg_catalog.has_any_column_privilege(
                                      current_user,
                                      relation.oid,
                                      'UPDATE'
                                  )
                              )
                          )
                        """)
                .param("tables", PUBLISHING_TABLES.toArray(String[]::new))
                .query(Long.class)
                .single();

        assertThat(runtimePrivileges).containsExactly(
                new TablePrivilege("content_revision", "INSERT", false),
                new TablePrivilege("content_revision", "SELECT", false),
                new TablePrivilege("publication", "INSERT", false),
                new TablePrivilege("publication", "SELECT", false),
                new TablePrivilege("publication", "UPDATE", false),
                new TablePrivilege("revision_media_reference", "INSERT", false),
                new TablePrivilege("revision_media_reference", "SELECT", false),
                new TablePrivilege("slug_redirect", "INSERT", false),
                new TablePrivilege("slug_redirect", "SELECT", false));
        assertThat(effectiveRuntimePrivileges).containsExactlyElementsOf(runtimePrivileges);
        assertThat(publicPrivileges).isZero();
        assertThat(protectedColumnPrivileges).isZero();
        assertThat(dangerousEffectiveColumnPrivileges).isZero();
    }

    @Test
    void publicationRejectsPointerlessPublishedRowsAndMalformedSlugs() {
        assertConstraintViolation(
                "publication_current_revision_ck",
                () -> inRollback(() -> jdbc.sql("""
                                insert into portfolio.publication(
                                    aggregate_type, aggregate_id, status,
                                    current_slug, version, published_at
                                ) values (
                                    'PROJECT', :aggregateId, 'PUBLISHED',
                                    'valid-slug', 1, clock_timestamp()
                                )
                                """)
                        .param("aggregateId", UUID.randomUUID())
                        .update()));
        assertConstraintViolation(
                "publication_current_slug_ck",
                () -> inRollback(() -> jdbc.sql("""
                                insert into portfolio.publication(
                                    aggregate_type, aggregate_id, status,
                                    current_slug, version
                                ) values (
                                    'PROJECT', :aggregateId, 'ARCHIVED',
                                    'Bad Slug', 0
                                )
                                """)
                        .param("aggregateId", UUID.randomUUID())
                        .update()));
    }

    private static <T> T inFreshMigratedDatabase(
            Function<FreshDatabaseClients, T> operation) {
        String databaseName =
                "portfolio_v8_" + UUID.randomUUID().toString().replace("-", "");
        JdbcClient admin = JdbcClient.create(dataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        admin.sql("create database " + databaseName + " owner test_migrator").update();
        try {
            admin.sql("revoke connect, create, temporary on database "
                            + databaseName
                            + " from public")
                    .update();
            admin.sql("grant connect on database "
                            + databaseName
                            + " to test_migrator, test_runtime")
                    .update();

            String databaseUrl = "jdbc:postgresql://"
                    + POSTGRES.getHost()
                    + ":"
                    + POSTGRES.getFirstMappedPort()
                    + "/"
                    + databaseName
                    + "?loggerLevel=OFF&currentSchema=portfolio";
            Flyway.configure()
                    .dataSource(
                            databaseUrl,
                            "test_migrator",
                            "migrator_test_password")
                    .defaultSchema("portfolio")
                    .schemas("portfolio")
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            FreshDatabaseClients clients = new FreshDatabaseClients(
                    JdbcClient.create(dataSource(
                            databaseUrl,
                            "test_migrator",
                            "migrator_test_password")),
                    JdbcClient.create(dataSource(
                            databaseUrl,
                            "test_runtime",
                            "runtime_test_password")));
            return operation.apply(clients);
        } finally {
            admin.sql("drop database " + databaseName + " with (force)").update();
        }
    }

    private static DriverManagerDataSource dataSource(
            String url, String username, String password) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        return dataSource;
    }

    private void inRollback(Runnable operation) {
        transactions.executeWithoutResult(status -> {
            status.setRollbackOnly();
            operation.run();
        });
    }

    private static void assertConstraintViolation(String constraintName, Runnable operation) {
        SQLException failure = sqlFailure(operation);
        assertThat(failure.getSQLState()).isEqualTo("23514");
        assertThat((Object) failure).isInstanceOf(PSQLException.class);
        PSQLException postgresFailure = (PSQLException) failure;
        assertThat(postgresFailure.getServerErrorMessage()).isNotNull();
        assertThat(postgresFailure.getServerErrorMessage().getConstraint())
                .isEqualTo(constraintName);
    }

    private static SQLException sqlFailure(Runnable operation) {
        Throwable failure = catchThrowable(operation::run);
        assertThat(failure).isNotNull();
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        assertThat(root).isInstanceOf(SQLException.class);
        return (SQLException) root;
    }

    private record PublicationPointer(
            String aggregateType,
            UUID aggregateId,
            String status,
            UUID currentRevisionId,
            String currentSlug,
            long version,
            Object publishedAt) {}

    private record FreshDatabaseClients(JdbcClient migrator, JdbcClient runtime) {}

    private record FreshMigrationState(
            List<String> versions,
            List<String> tables,
            List<PublicationPointer> pointers) {}

    private record IndexDefinition(
            String name,
            boolean unique,
            String keyColumns,
            String predicate,
            String definition) {}

    private record ForeignKeyDefinition(
            String sourceTable,
            String name,
            String sourceColumns,
            String targetSchema,
            String targetTable,
            String targetColumns,
            String deleteAction) {}

    private record HistoryTrigger(
            String table, String name, String enabled, int type, String functionName) {}

    private record TablePrivilege(
            String table, String privilege, boolean grantable) {}
}
