package xyz.yychainsaw.portfolio.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
class MediaSchemaMigrationTest extends PostgresIntegrationTestBase {
    private static final String VALID_SHA256 = "a".repeat(64);

    @Autowired JdbcClient jdbc;

    @Test
    void flywayCreatesTheMediaJobAndLocalStagingSchemaVersions() {
        List<String> versions = migratorJdbc()
                .sql("""
                        select version
                        from portfolio.flyway_schema_history
                        where success and version is not null
                        order by installed_rank
                        """)
                .query(String.class)
                .list();
        List<String> tables = migratorJdbc()
                .sql("""
                        select table_name
                        from information_schema.tables
                        where table_schema = 'portfolio'
                          and table_name in (
                              'media_asset', 'media_variant', 'media_translation',
                              'background_job', 'maintenance_run'
                          )
                        order by table_name
                        """)
                .query(String.class)
                .list();

        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(versions)
                    .containsExactly("1", "2", "3", "4", "5", "6");
            softly.assertThat(tables).containsExactly(
                    "background_job",
                    "maintenance_run",
                    "media_asset",
                    "media_translation",
                    "media_variant");
        });
    }

    @Test
    void runtimeCapabilityRoleHasOnlyTheIntendedTableAndUpdateColumnPrivileges() {
        List<TablePrivilege> tablePrivileges = migratorJdbc()
                .sql("""
                        select relation.relname,
                               privilege.privilege_type,
                               privilege.is_grantable
                        from pg_catalog.pg_class as relation
                        join pg_catalog.pg_namespace as namespace
                          on namespace.oid = relation.relnamespace
                        cross join lateral pg_catalog.aclexplode(
                            coalesce(
                                relation.relacl,
                                pg_catalog.acldefault('r', relation.relowner)
                            )
                        ) as privilege
                        join pg_catalog.pg_roles as grantee_role
                          on grantee_role.oid = privilege.grantee
                        where namespace.nspname = 'portfolio'
                          and relation.relkind = 'r'
                          and relation.relname in (
                              'media_asset', 'media_variant', 'media_translation',
                              'background_job', 'maintenance_run'
                          )
                          and grantee_role.rolname = 'portfolio_runtime_access'
                        order by relation.relname, privilege.privilege_type
                        """)
                .query((resultSet, rowNumber) -> new TablePrivilege(
                        resultSet.getString("relname"),
                        resultSet.getString("privilege_type"),
                        resultSet.getBoolean("is_grantable")))
                .list();
        List<ColumnPrivilege> updateColumns = migratorJdbc()
                .sql("""
                        select relation.relname,
                               attribute.attname,
                               privilege.privilege_type,
                               privilege.is_grantable
                        from pg_catalog.pg_class as relation
                        join pg_catalog.pg_namespace as namespace
                          on namespace.oid = relation.relnamespace
                        join pg_catalog.pg_attribute as attribute
                          on attribute.attrelid = relation.oid
                        cross join lateral pg_catalog.aclexplode(attribute.attacl) as privilege
                        join pg_catalog.pg_roles as grantee_role
                          on grantee_role.oid = privilege.grantee
                        where namespace.nspname = 'portfolio'
                          and relation.relname in (
                              'media_asset', 'background_job', 'maintenance_run'
                          )
                          and attribute.attnum > 0
                          and not attribute.attisdropped
                          and grantee_role.rolname = 'portfolio_runtime_access'
                        order by relation.relname, attribute.attname, privilege.privilege_type
                        """)
                .query((resultSet, rowNumber) -> new ColumnPrivilege(
                        resultSet.getString("relname"),
                        resultSet.getString("attname"),
                        resultSet.getString("privilege_type"),
                        resultSet.getBoolean("is_grantable")))
                .list();

        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(tablePrivileges)
                    .containsExactly(
                            new TablePrivilege("background_job", "INSERT", false),
                            new TablePrivilege("background_job", "SELECT", false),
                            new TablePrivilege("maintenance_run", "INSERT", false),
                            new TablePrivilege("maintenance_run", "SELECT", false),
                            new TablePrivilege("media_asset", "DELETE", false),
                            new TablePrivilege("media_asset", "INSERT", false),
                            new TablePrivilege("media_asset", "SELECT", false),
                            new TablePrivilege("media_translation", "DELETE", false),
                            new TablePrivilege("media_translation", "INSERT", false),
                            new TablePrivilege("media_translation", "SELECT", false),
                            new TablePrivilege("media_translation", "UPDATE", false),
                            new TablePrivilege("media_variant", "DELETE", false),
                            new TablePrivilege("media_variant", "INSERT", false),
                            new TablePrivilege("media_variant", "SELECT", false),
                            new TablePrivilege("media_variant", "UPDATE", false));
            softly.assertThat(updateColumns)
                    .containsExactly(
                            new ColumnPrivilege("background_job", "attempts", "UPDATE", false),
                            new ColumnPrivilege(
                                    "background_job", "last_error_summary", "UPDATE", false),
                            new ColumnPrivilege("background_job", "lease_owner", "UPDATE", false),
                            new ColumnPrivilege("background_job", "lease_until", "UPDATE", false),
                            new ColumnPrivilege("background_job", "next_run_at", "UPDATE", false),
                            new ColumnPrivilege("background_job", "status", "UPDATE", false),
                            new ColumnPrivilege(
                                    "maintenance_run", "artifact_checksum", "UPDATE", false),
                            new ColumnPrivilege("maintenance_run", "details", "UPDATE", false),
                            new ColumnPrivilege("maintenance_run", "error_summary", "UPDATE", false),
                            new ColumnPrivilege("maintenance_run", "finished_at", "UPDATE", false),
                            new ColumnPrivilege("maintenance_run", "status", "UPDATE", false),
                            new ColumnPrivilege("media_asset", "archived_at", "UPDATE", false),
                            new ColumnPrivilege("media_asset", "status", "UPDATE", false),
                            new ColumnPrivilege("media_asset", "version", "UPDATE", false));
        });
    }

    @Test
    void v3TablesExposeNoPublicOrDangerousRuntimePrivileges() {
        Long publicPrivilegeCount = migratorJdbc()
                .sql("""
                        select count(*)
                        from pg_catalog.pg_class as relation
                        join pg_catalog.pg_namespace as namespace
                          on namespace.oid = relation.relnamespace
                        cross join lateral pg_catalog.aclexplode(
                            coalesce(
                                relation.relacl,
                                pg_catalog.acldefault('r', relation.relowner)
                            )
                        ) as privilege
                        where namespace.nspname = 'portfolio'
                          and relation.relkind = 'r'
                          and relation.relname in (
                              'media_asset', 'media_variant', 'media_translation',
                              'background_job', 'maintenance_run'
                          )
                          and privilege.grantee = 0
                        """)
                .query(Long.class)
                .single();
        List<EffectivePrivilege> dangerousRuntimePrivileges = jdbc.sql("""
                        select relation.relname, requested.privilege
                        from pg_catalog.pg_class as relation
                        join pg_catalog.pg_namespace as namespace
                          on namespace.oid = relation.relnamespace
                        cross join (
                            values ('DELETE'), ('TRUNCATE'), ('REFERENCES'),
                                   ('TRIGGER'), ('MAINTAIN')
                        ) as requested(privilege)
                        where namespace.nspname = 'portfolio'
                          and relation.relkind = 'r'
                          and relation.relname in (
                              'media_asset', 'media_variant', 'media_translation',
                              'background_job', 'maintenance_run'
                          )
                          and (
                              requested.privilege <> 'DELETE'
                              or relation.relname in ('background_job', 'maintenance_run')
                          )
                          and pg_catalog.has_table_privilege(
                              current_user, relation.oid, requested.privilege
                          )
                        order by relation.relname, requested.privilege
                        """)
                .query((resultSet, rowNumber) -> new EffectivePrivilege(
                        resultSet.getString("relname"), resultSet.getString("privilege")))
                .list();

        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(publicPrivilegeCount).isZero();
            softly.assertThat(dangerousRuntimePrivileges).isEmpty();
        });
    }

    @Test
    void schemaDeclaresTheRequiredColumnTypesDefaultsAndChecks() {
        List<ColumnShape> columnShapes = migratorJdbc()
                .sql("""
                        select table_name,
                               column_name,
                               data_type,
                               character_maximum_length,
                               is_nullable,
                               column_default
                        from information_schema.columns
                        where table_schema = 'portfolio'
                          and (table_name, column_name) in (
                              ('maintenance_run', 'artifact_checksum'),
                              ('maintenance_run', 'details'),
                              ('media_asset', 'sha256'),
                              ('media_asset', 'version'),
                              ('media_variant', 'sha256')
                          )
                        order by table_name, column_name
                        """)
                .query((resultSet, rowNumber) -> new ColumnShape(
                        resultSet.getString("table_name"),
                        resultSet.getString("column_name"),
                        resultSet.getString("data_type"),
                        (Integer) resultSet.getObject("character_maximum_length"),
                        resultSet.getString("is_nullable"),
                        resultSet.getString("column_default")))
                .list();
        List<String> checkConstraints = migratorJdbc()
                .sql("""
                        select constraint_metadata.conname
                        from pg_catalog.pg_constraint as constraint_metadata
                        join pg_catalog.pg_class as relation
                          on relation.oid = constraint_metadata.conrelid
                        join pg_catalog.pg_namespace as namespace
                          on namespace.oid = relation.relnamespace
                        where namespace.nspname = 'portfolio'
                          and constraint_metadata.contype = 'c'
                          and relation.relname in (
                              'media_asset', 'media_variant', 'media_translation',
                              'background_job', 'maintenance_run'
                          )
                        order by relation.relname, constraint_metadata.conname
                        """)
                .query(String.class)
                .list();
        String storageIdentityDefinition = migratorJdbc()
                .sql("""
                        select pg_catalog.pg_get_constraintdef(constraint_metadata.oid)
                        from pg_catalog.pg_constraint as constraint_metadata
                        join pg_catalog.pg_class as relation
                          on relation.oid = constraint_metadata.conrelid
                        join pg_catalog.pg_namespace as namespace
                          on namespace.oid = relation.relnamespace
                        where namespace.nspname = 'portfolio'
                          and relation.relname = 'media_asset'
                          and constraint_metadata.conname = 'media_asset_storage_identity_uk'
                        """)
                .query(String.class)
                .optional()
                .orElse("");
        String detailsComment = migratorJdbc()
                .sql("""
                        select pg_catalog.col_description(relation.oid, attribute.attnum)
                        from pg_catalog.pg_class as relation
                        join pg_catalog.pg_namespace as namespace
                          on namespace.oid = relation.relnamespace
                        join pg_catalog.pg_attribute as attribute
                          on attribute.attrelid = relation.oid
                        where namespace.nspname = 'portfolio'
                          and relation.relname = 'maintenance_run'
                          and attribute.attname = 'details'
                        """)
                .query(String.class)
                .optional()
                .orElse("");

        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(columnShapes)
                    .containsExactly(
                            new ColumnShape(
                                    "maintenance_run",
                                    "artifact_checksum",
                                    "character varying",
                                    64,
                                    "YES",
                                    null),
                            new ColumnShape(
                                    "maintenance_run",
                                    "details",
                                    "jsonb",
                                    null,
                                    "NO",
                                    "'{}'::jsonb"),
                            new ColumnShape(
                                    "media_asset",
                                    "sha256",
                                    "character varying",
                                    64,
                                    "NO",
                                    null),
                            new ColumnShape(
                                    "media_asset", "version", "bigint", null, "NO", "0"),
                            new ColumnShape(
                                    "media_variant",
                                    "sha256",
                                    "character varying",
                                    64,
                                    "NO",
                                    null));
            softly.assertThat(checkConstraints)
                    .containsExactly(
                            "background_job_attempts_ck",
                            "background_job_idempotency_key_nonblank_ck",
                            "background_job_job_type_nonblank_ck",
                            "background_job_lease_ck",
                            "background_job_payload_object_ck",
                            "background_job_status_ck",
                            "maintenance_run_artifact_checksum_ck",
                            "maintenance_run_details_ck",
                            "maintenance_run_run_type_nonblank_ck",
                            "maintenance_run_status_ck",
                            "maintenance_run_timing_ck",
                            "media_asset_archive_ck",
                            "media_asset_byte_size_ck",
                            "media_asset_dimensions_ck",
                            "media_asset_mime_type_nonblank_ck",
                            "media_asset_object_key_nonblank_ck",
                            "media_asset_original_filename_nonblank_ck",
                            "media_asset_provider_ck",
                            "media_asset_sha256_ck",
                            "media_asset_status_ck",
                            "media_asset_storage_metadata_ck",
                            "media_asset_version_ck",
                            "media_translation_locale_ck",
                            "media_variant_byte_size_ck",
                            "media_variant_dimensions_ck",
                            "media_variant_format_ck",
                            "media_variant_mime_type_nonblank_ck",
                            "media_variant_object_key_nonblank_ck",
                            "media_variant_sha256_ck",
                            "media_variant_status_ck",
                            "media_variant_variant_name_nonblank_ck");
            softly.assertThat(storageIdentityDefinition)
                    .isEqualTo(
                            "UNIQUE NULLS NOT DISTINCT (provider, bucket, region, object_key)");
            softly.assertThat(detailsComment)
                    .contains("counts and cutoffs")
                    .contains("paths, object keys, credentials, exception text, or PII");
        });
    }

    @Test
    void databaseChecksRejectInvalidJobAndMaintenanceData() {
        assertCheckViolation(
                """
                insert into portfolio.background_job(
                    id, job_type, idempotency_key, payload, status, next_run_at
                ) values (:id, '   ', 'job-key', '{}'::jsonb, 'PENDING', clock_timestamp())
                """,
                Map.of("id", UUID.randomUUID()));
        assertCheckViolation(
                """
                insert into portfolio.background_job(
                    id, job_type, idempotency_key, payload, status, next_run_at
                ) values (:id, 'RENDER', '   ', '{}'::jsonb, 'PENDING', clock_timestamp())
                """,
                Map.of("id", UUID.randomUUID()));
        assertCheckViolation(
                """
                insert into portfolio.background_job(
                    id, job_type, idempotency_key, payload, status, next_run_at
                ) values (:id, 'RENDER', 'job-key', '[]'::jsonb, 'PENDING', clock_timestamp())
                """,
                Map.of("id", UUID.randomUUID()));
        assertCheckViolation(
                """
                insert into portfolio.background_job(
                    id, job_type, idempotency_key, payload, status, next_run_at
                ) values (:id, 'RENDER', 'job-key', '{}'::jsonb, 'RUNNING', clock_timestamp())
                """,
                Map.of("id", UUID.randomUUID()));
        assertCheckViolation(
                """
                insert into portfolio.background_job(
                    id, job_type, idempotency_key, payload, status, next_run_at,
                    lease_owner, lease_until
                ) values (
                    :id, 'RENDER', 'job-key', '{}'::jsonb, 'PENDING', clock_timestamp(),
                    'worker-1', clock_timestamp() + interval '1 minute'
                )
                """,
                Map.of("id", UUID.randomUUID()));
        assertCheckViolation(
                """
                insert into portfolio.maintenance_run(
                    id, run_type, status, started_at
                ) values (:id, '   ', 'RUNNING', clock_timestamp())
                """,
                Map.of("id", UUID.randomUUID()));
        assertCheckViolation(
                """
                insert into portfolio.maintenance_run(
                    id, run_type, status, artifact_checksum, started_at
                ) values (:id, 'BACKUP', 'RUNNING', :checksum, clock_timestamp())
                """,
                Map.of("id", UUID.randomUUID(), "checksum", "A".repeat(64)));
        assertCheckViolation(
                """
                insert into portfolio.maintenance_run(
                    id, run_type, status, details, started_at
                ) values (:id, 'RETENTION', 'RUNNING', '[]'::jsonb, clock_timestamp())
                """,
                Map.of("id", UUID.randomUUID()));
        assertCheckViolation(
                """
                insert into portfolio.maintenance_run(
                    id, run_type, status, details, started_at
                ) values (
                    :id, 'RETENTION', 'RUNNING',
                    '{"object_key":"must-not-be-recorded"}'::jsonb,
                    clock_timestamp()
                )
                """,
                Map.of("id", UUID.randomUUID()));
        assertCheckViolation(
                """
                insert into portfolio.maintenance_run(
                    id, run_type, status, details, started_at
                ) values (
                    :id, 'RETENTION', 'RUNNING', cast(:details as jsonb), clock_timestamp()
                )
                """,
                Map.of(
                        "id",
                        UUID.randomUUID(),
                        "details",
                        "{\"processed_count\":\"" + "x".repeat(8192) + "\"}"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidMaintenanceDetails")
    void maintenanceDetailsRejectNonAllowlistedKeysAndNonBigintValues(
            String description, String details) {
        assertCheckViolation(
                """
                insert into portfolio.maintenance_run(
                    id, run_type, status, details, started_at
                ) values (
                    :id, 'RETENTION', 'RUNNING', cast(:details as jsonb), clock_timestamp()
                )
                """,
                Map.of("id", UUID.randomUUID(), "details", details));
    }

    @Test
    void maintenanceDetailsAcceptEveryAllowlistedNonNegativeBigintKey() {
        UUID runId = UUID.randomUUID();
        String details = """
                {
                  "input_count": 0,
                  "output_count": 1,
                  "processed_count": 2,
                  "deleted_count": 3,
                  "failed_count": 4,
                  "skipped_count": 5,
                  "object_count": 6,
                  "cutoff_epoch_second": 9223372036854775807
                }
                """;
        JdbcClient migrator = migratorJdbc();
        try {
            int inserted = migrator.sql("""
                            insert into portfolio.maintenance_run(
                                id, run_type, status, details, started_at
                            ) values (
                                :id, 'RETENTION', 'RUNNING',
                                cast(:details as jsonb), clock_timestamp()
                            )
                            """)
                    .params(Map.of("id", runId, "details", details))
                    .update();
            Boolean detailsRoundTrip = migrator.sql("""
                            select details = cast(:details as jsonb)
                            from portfolio.maintenance_run
                            where id = :id
                            """)
                    .params(Map.of("id", runId, "details", details))
                    .query(Boolean.class)
                    .single();

            SoftAssertions.assertSoftly(softly -> {
                softly.assertThat(inserted).isOne();
                softly.assertThat(detailsRoundTrip).isTrue();
            });
        } finally {
            migrator.sql("delete from portfolio.maintenance_run where id = :id")
                    .param("id", runId)
                    .update();
        }
    }

    @Test
    void terminalMaintenanceRunRejectsFinishedAtBeforeStartedAt() {
        assertCheckViolation(
                """
                insert into portfolio.maintenance_run(
                    id, run_type, status, started_at, finished_at
                ) values (
                    :id, 'BACKUP', 'SUCCEEDED',
                    '2026-07-16T02:00:00Z'::timestamptz,
                    '2026-07-16T01:59:59.999999Z'::timestamptz
                )
                """,
                Map.of("id", UUID.randomUUID()));
    }

    @Test
    void databaseChecksRejectInvalidMediaAndEnforceNullSafeStorageIdentity() {
        assertCheckViolation(
                """
                insert into portfolio.media_asset(
                    id, provider, object_key, original_filename, mime_type,
                    byte_size, sha256, status
                ) values (
                    :id, 'LOCAL', '   ', 'photo.jpg', 'image/jpeg',
                    1, :sha256, 'PROCESSING'
                )
                """,
                Map.of("id", UUID.randomUUID(), "sha256", VALID_SHA256));
        assertCheckViolation(
                """
                insert into portfolio.media_asset(
                    id, provider, bucket, object_key, original_filename, mime_type,
                    byte_size, sha256, status
                ) values (
                    :id, 'LOCAL', 'unexpected-bucket', 'media/photo.jpg',
                    'photo.jpg', 'image/jpeg', 1, :sha256, 'PROCESSING'
                )
                """,
                Map.of("id", UUID.randomUUID(), "sha256", VALID_SHA256));
        assertCheckViolation(
                """
                insert into portfolio.media_asset(
                    id, provider, object_key, original_filename, mime_type,
                    byte_size, sha256, status
                ) values (
                    :id, 'TENCENT_COS', 'media/photo.jpg', 'photo.jpg', 'image/jpeg',
                    1, :sha256, 'PROCESSING'
                )
                """,
                Map.of("id", UUID.randomUUID(), "sha256", VALID_SHA256));
        assertCheckViolation(
                """
                insert into portfolio.media_asset(
                    id, provider, object_key, original_filename, mime_type,
                    byte_size, width, sha256, status
                ) values (
                    :id, 'LOCAL', 'media/photo.jpg', 'photo.jpg', 'image/jpeg',
                    1, 0, :sha256, 'PROCESSING'
                )
                """,
                Map.of("id", UUID.randomUUID(), "sha256", VALID_SHA256));
        assertCheckViolation(
                """
                insert into portfolio.media_asset(
                    id, provider, object_key, original_filename, mime_type,
                    byte_size, sha256, status
                ) values (
                    :id, 'LOCAL', 'media/photo.jpg', 'photo.jpg', 'image/jpeg',
                    1, :sha256, 'PROCESSING'
                )
                """,
                Map.of("id", UUID.randomUUID(), "sha256", "A".repeat(64)));
        assertCheckViolation(
                """
                insert into portfolio.media_asset(
                    id, provider, object_key, original_filename, mime_type,
                    byte_size, sha256, status, archived_at
                ) values (
                    :id, 'LOCAL', 'media/photo.jpg', 'photo.jpg', 'image/jpeg',
                    1, :sha256, 'READY', clock_timestamp()
                )
                """,
                Map.of("id", UUID.randomUUID(), "sha256", VALID_SHA256));
        assertCheckViolation(
                """
                insert into portfolio.media_asset(
                    id, provider, object_key, original_filename, mime_type,
                    byte_size, sha256, status
                ) values (
                    :id, 'LOCAL', 'media/photo.jpg', 'photo.jpg', 'image/jpeg',
                    1, :sha256, 'ARCHIVED'
                )
                """,
                Map.of("id", UUID.randomUUID(), "sha256", VALID_SHA256));

        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        String objectKey = "media/duplicate-" + UUID.randomUUID() + ".jpg";
        JdbcClient migrator = migratorJdbc();
        try {
            migrator.sql("""
                            insert into portfolio.media_asset(
                                id, provider, object_key, original_filename, mime_type,
                                byte_size, sha256, status
                            ) values (
                                :id, 'LOCAL', :objectKey, 'photo.jpg', 'image/jpeg',
                                1, :sha256, 'PROCESSING'
                            )
                            """)
                    .params(Map.of("id", firstId, "objectKey", objectKey, "sha256", VALID_SHA256))
                    .update();
            assertSqlState(
                    "23505",
                    """
                    insert into portfolio.media_asset(
                        id, provider, object_key, original_filename, mime_type,
                        byte_size, sha256, status
                    ) values (
                        :id, 'LOCAL', :objectKey, 'photo.jpg', 'image/jpeg',
                        1, :sha256, 'PROCESSING'
                    )
                    """,
                    Map.of("id", secondId, "objectKey", objectKey, "sha256", VALID_SHA256));
        } finally {
            migrator.sql("""
                            delete from portfolio.media_asset
                            where id = :firstId or id = :secondId
                            """)
                    .params(Map.of("firstId", firstId, "secondId", secondId))
                    .update();
        }
    }

    @Test
    void schemaDefinesRequiredForeignKeysIndexesClockDefaultsAndUpdateTriggers() {
        List<ForeignKeyShape> foreignKeys = migratorJdbc()
                .sql("""
                        select source.relname as source_table,
                               constraint_metadata.conname,
                               target_namespace.nspname as target_schema,
                               target.relname as target_table,
                               constraint_metadata.confdeltype::text as delete_action
                        from pg_catalog.pg_constraint as constraint_metadata
                        join pg_catalog.pg_class as source
                          on source.oid = constraint_metadata.conrelid
                        join pg_catalog.pg_namespace as source_namespace
                          on source_namespace.oid = source.relnamespace
                        join pg_catalog.pg_class as target
                          on target.oid = constraint_metadata.confrelid
                        join pg_catalog.pg_namespace as target_namespace
                          on target_namespace.oid = target.relnamespace
                        where source_namespace.nspname = 'portfolio'
                          and source.relname in ('media_variant', 'media_translation')
                          and constraint_metadata.contype = 'f'
                        order by source.relname, constraint_metadata.conname
                        """)
                .query((resultSet, rowNumber) -> new ForeignKeyShape(
                        resultSet.getString("source_table"),
                        resultSet.getString("conname"),
                        resultSet.getString("target_schema"),
                        resultSet.getString("target_table"),
                        resultSet.getString("delete_action")))
                .list();
        List<IndexShape> indexes = migratorJdbc()
                .sql("""
                        select index_relation.relname as index_name,
                               pg_catalog.pg_get_indexdef(index_relation.oid) as definition,
                               coalesce(
                                   pg_catalog.pg_get_expr(
                                       index_metadata.indpred, index_metadata.indrelid
                                   ),
                                   ''
                               ) as predicate
                        from pg_catalog.pg_index as index_metadata
                        join pg_catalog.pg_class as index_relation
                          on index_relation.oid = index_metadata.indexrelid
                        join pg_catalog.pg_namespace as namespace
                          on namespace.oid = index_relation.relnamespace
                        where namespace.nspname = 'portfolio'
                          and index_relation.relname in (
                              'background_job_ready_idx',
                              'background_job_expired_lease_idx',
                              'background_job_terminal_retention_idx',
                              'media_asset_status_idx',
                              'media_asset_sha_idx',
                              'media_asset_created_at_id_idx',
                              'media_asset_archived_idx'
                          )
                        order by index_relation.relname
                        """)
                .query((resultSet, rowNumber) -> new IndexShape(
                        resultSet.getString("index_name"),
                        resultSet.getString("definition"),
                        resultSet.getString("predicate")))
                .list();
        Map<String, IndexShape> indexesByName = indexes.stream()
                .collect(Collectors.toMap(IndexShape::name, Function.identity()));
        List<TriggerShape> triggers = migratorJdbc()
                .sql("""
                        select relation.relname as table_name,
                               trigger.tgname as trigger_name,
                               function_namespace.nspname as function_schema,
                               procedure.proname as function_name
                        from pg_catalog.pg_trigger as trigger
                        join pg_catalog.pg_class as relation
                          on relation.oid = trigger.tgrelid
                        join pg_catalog.pg_namespace as namespace
                          on namespace.oid = relation.relnamespace
                        join pg_catalog.pg_proc as procedure
                          on procedure.oid = trigger.tgfoid
                        join pg_catalog.pg_namespace as function_namespace
                          on function_namespace.oid = procedure.pronamespace
                        where namespace.nspname = 'portfolio'
                          and relation.relname in ('background_job', 'media_asset')
                          and not trigger.tgisinternal
                        order by relation.relname, trigger.tgname
                        """)
                .query((resultSet, rowNumber) -> new TriggerShape(
                        resultSet.getString("table_name"),
                        resultSet.getString("trigger_name"),
                        resultSet.getString("function_schema"),
                        resultSet.getString("function_name")))
                .list();
        List<ClockDefault> clockDefaults = migratorJdbc()
                .sql("""
                        select table_name, column_name
                        from information_schema.columns
                        where table_schema = 'portfolio'
                          and table_name in (
                              'media_asset', 'media_variant', 'media_translation',
                              'background_job', 'maintenance_run'
                          )
                          and column_default = 'clock_timestamp()'
                        order by table_name, column_name
                        """)
                .query((resultSet, rowNumber) -> new ClockDefault(
                        resultSet.getString("table_name"),
                        resultSet.getString("column_name")))
                .list();

        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(foreignKeys)
                    .containsExactly(
                            new ForeignKeyShape(
                                    "media_translation",
                                    "media_translation_asset_fk",
                                    "portfolio",
                                    "media_asset",
                                    "c"),
                            new ForeignKeyShape(
                                    "media_variant",
                                    "media_variant_asset_fk",
                                    "portfolio",
                                    "media_asset",
                                    "r"));
            softly.assertThat(indexes)
                    .extracting(IndexShape::name)
                    .containsExactly(
                            "background_job_expired_lease_idx",
                            "background_job_ready_idx",
                            "background_job_terminal_retention_idx",
                            "media_asset_archived_idx",
                            "media_asset_created_at_id_idx",
                            "media_asset_sha_idx",
                            "media_asset_status_idx");
            if (indexesByName.containsKey("background_job_ready_idx")) {
                softly.assertThat(indexesByName.get("background_job_ready_idx").definition())
                        .contains("(next_run_at, created_at)");
                softly.assertThat(indexesByName.get("background_job_ready_idx").predicate())
                        .contains("PENDING", "FAILED");
            }
            if (indexesByName.containsKey("background_job_expired_lease_idx")) {
                softly.assertThat(
                                indexesByName
                                        .get("background_job_expired_lease_idx")
                                        .predicate())
                        .contains("RUNNING");
            }
            if (indexesByName.containsKey("background_job_terminal_retention_idx")) {
                softly.assertThat(
                                indexesByName
                                        .get("background_job_terminal_retention_idx")
                                        .definition())
                        .contains("(updated_at, created_at, id)");
                softly.assertThat(
                                indexesByName
                                        .get("background_job_terminal_retention_idx")
                                        .predicate())
                        .contains("SUCCEEDED", "DEAD");
            }
            if (indexesByName.containsKey("media_asset_status_idx")) {
                softly.assertThat(indexesByName.get("media_asset_status_idx").definition())
                        .contains("(status, created_at DESC)");
            }
            if (indexesByName.containsKey("media_asset_created_at_id_idx")) {
                softly.assertThat(
                                indexesByName
                                        .get("media_asset_created_at_id_idx")
                                        .definition())
                        .contains("(created_at DESC, id DESC)");
            }
            if (indexesByName.containsKey("media_asset_archived_idx")) {
                softly.assertThat(indexesByName.get("media_asset_archived_idx").definition())
                        .contains("(archived_at, id)");
                softly.assertThat(indexesByName.get("media_asset_archived_idx").predicate())
                        .contains("ARCHIVED");
            }
            softly.assertThat(triggers)
                    .containsExactly(
                            new TriggerShape(
                                    "background_job",
                                    "background_job_set_updated_at",
                                    "portfolio",
                                    "set_updated_at"),
                            new TriggerShape(
                                    "media_asset",
                                    "media_asset_set_updated_at",
                                    "portfolio",
                                    "set_updated_at"));
            softly.assertThat(clockDefaults)
                    .containsExactly(
                            new ClockDefault("background_job", "created_at"),
                            new ClockDefault("background_job", "updated_at"),
                            new ClockDefault("media_asset", "created_at"),
                            new ClockDefault("media_asset", "updated_at"),
                            new ClockDefault("media_variant", "created_at"));
        });
    }

    private static void assertCheckViolation(String sql, Map<String, ?> parameters) {
        assertSqlState("23514", sql, parameters);
    }

    private static Stream<Arguments> invalidMaintenanceDetails() {
        return Stream.of(
                Arguments.of("unknown key", "{\"note\":1}"),
                Arguments.of("camelCase key", "{\"objectCount\":1}"),
                Arguments.of("nested object", "{\"processed_count\":{\"value\":1}}"),
                Arguments.of("array value", "{\"processed_count\":[1]}"),
                Arguments.of("token string value", "{\"processed_count\":\"token-value\"}"),
                Arguments.of(
                        "path string value",
                        "{\"cutoff_epoch_second\":\"/runtime/private/file\"}"),
                Arguments.of("boolean value", "{\"processed_count\":true}"),
                Arguments.of("null value", "{\"processed_count\":null}"),
                Arguments.of("negative integer", "{\"processed_count\":-1}"),
                Arguments.of("fractional number", "{\"processed_count\":1.5}"),
                Arguments.of(
                        "signed 64-bit overflow",
                        "{\"processed_count\":9223372036854775808}"));
    }

    private static void assertSqlState(
            String expectedSqlState, String sql, Map<String, ?> parameters) {
        Throwable failure = catchThrowable(() ->
                migratorJdbc().sql(sql).params(parameters).update());
        assertThat(failure).isNotNull();
        Throwable root = rootCause(failure);
        assertThat(root).isInstanceOf(SQLException.class);
        assertThat(((SQLException) root).getSQLState()).isEqualTo(expectedSqlState);
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private record TablePrivilege(String table, String privilege, boolean grantable) {}

    private record ColumnPrivilege(
            String table, String column, String privilege, boolean grantable) {}

    private record EffectivePrivilege(String table, String privilege) {}

    private record ColumnShape(
            String table,
            String column,
            String dataType,
            Integer maximumLength,
            String nullable,
            String defaultValue) {}

    private record ForeignKeyShape(
            String sourceTable,
            String constraint,
            String targetSchema,
            String targetTable,
            String deleteAction) {}

    private record IndexShape(String name, String definition, String predicate) {}

    private record TriggerShape(
            String table, String trigger, String functionSchema, String functionName) {}

    private record ClockDefault(String table, String column) {}
}
