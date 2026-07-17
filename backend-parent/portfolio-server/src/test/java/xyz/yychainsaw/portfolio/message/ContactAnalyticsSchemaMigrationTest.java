package xyz.yychainsaw.portfolio.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import xyz.yychainsaw.portfolio.common.ratelimit.RateLimitProperties;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
@Isolated
class ContactAnalyticsSchemaMigrationTest extends PostgresIntegrationTestBase {
    private static final String HEX_KEY = "a".repeat(64);
    private static final List<String> TABLES = List.of(
            "analytics_daily",
            "analytics_event",
            "contact_message",
            "email_outbox");

    @Autowired JdbcClient jdbc;
    @Autowired RateLimitProperties rateLimits;

    @Test
    void flywayAppliesTheForwardContactAndAnalyticsMigrations() {
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
                        where table_schema = 'portfolio'
                          and table_name = any(cast(:tables as text[]))
                        order by table_name
                        """)
                .param("tables", TABLES.toArray(String[]::new))
                .query(String.class)
                .list();

        assertThat(versions).endsWith("9", "10");
        assertThat(tables).containsExactlyElementsOf(TABLES);
    }

    @Test
    void schemaContainsOnlyTheApprovedContactAndPrivacyAnalyticsFields() {
        List<ColumnName> columns = migratorJdbc().sql("""
                        select table_name, column_name
                        from information_schema.columns
                        where table_schema = 'portfolio'
                          and table_name = any(cast(:tables as text[]))
                        order by table_name, ordinal_position
                        """)
                .param("tables", TABLES.toArray(String[]::new))
                .query((resultSet, rowNumber) -> new ColumnName(
                        resultSet.getString("table_name"),
                        resultSet.getString("column_name")))
                .list();

        assertThat(columns)
                .extracting(ColumnName::table, ColumnName::column)
                .containsExactly(
                        tuple("analytics_daily", "site_date"),
                        tuple("analytics_daily", "metric"),
                        tuple("analytics_daily", "event_type"),
                        tuple("analytics_daily", "dimension"),
                        tuple("analytics_daily", "dimension_value"),
                        tuple("analytics_daily", "metric_count"),
                        tuple("analytics_daily", "aggregation_version"),
                        tuple("analytics_daily", "updated_at"),
                        tuple("analytics_event", "id"),
                        tuple("analytics_event", "client_event_id"),
                        tuple("analytics_event", "site_date"),
                        tuple("analytics_event", "received_at"),
                        tuple("analytics_event", "visitor_day_key"),
                        tuple("analytics_event", "session_day_key"),
                        tuple("analytics_event", "event_type"),
                        tuple("analytics_event", "page_key"),
                        tuple("analytics_event", "project_id"),
                        tuple("analytics_event", "referrer_domain"),
                        tuple("analytics_event", "device_class"),
                        tuple("analytics_event", "locale"),
                        tuple("analytics_event", "rules_version"),
                        tuple("analytics_event", "created_at"),
                        tuple("contact_message", "id"),
                        tuple("contact_message", "visitor_name"),
                        tuple("contact_message", "visitor_email"),
                        tuple("contact_message", "subject"),
                        tuple("contact_message", "body"),
                        tuple("contact_message", "status"),
                        tuple("contact_message", "dedupe_key"),
                        tuple("contact_message", "privacy_accepted_at"),
                        tuple("contact_message", "version"),
                        tuple("contact_message", "created_at"),
                        tuple("contact_message", "updated_at"),
                        tuple("email_outbox", "id"),
                        tuple("email_outbox", "contact_message_id"),
                        tuple("email_outbox", "template_name"),
                        tuple("email_outbox", "to_address"),
                        tuple("email_outbox", "stable_message_id"),
                        tuple("email_outbox", "status"),
                        tuple("email_outbox", "attempts"),
                        tuple("email_outbox", "next_attempt_at"),
                        tuple("email_outbox", "lease_owner"),
                        tuple("email_outbox", "lease_until"),
                        tuple("email_outbox", "last_error_summary"),
                        tuple("email_outbox", "created_at"),
                        tuple("email_outbox", "sent_at"),
                        tuple("email_outbox", "updated_at"));
        assertThat(columns)
                .extracting(ColumnName::column)
                .doesNotContain(
                        "ip",
                        "ip_address",
                        "ip_hash",
                        "browser_id",
                        "visitor_id",
                        "session_id",
                        "user_agent",
                        "url_query");
    }

    @Test
    void sensitiveAndDimensionTextColumnsAreExplicitlyBounded() {
        List<BoundedColumn> columns = migratorJdbc().sql("""
                        select table_name,
                               column_name,
                               data_type,
                               character_maximum_length,
                               is_nullable = 'YES' nullable
                        from information_schema.columns
                        where table_schema = 'portfolio'
                          and table_name = any(cast(:tables as text[]))
                          and character_maximum_length is not null
                        order by table_name, ordinal_position
                        """)
                .param("tables", TABLES.toArray(String[]::new))
                .query((resultSet, rowNumber) -> new BoundedColumn(
                        resultSet.getString("table_name"),
                        resultSet.getString("column_name"),
                        resultSet.getString("data_type"),
                        resultSet.getInt("character_maximum_length"),
                        resultSet.getBoolean("nullable")))
                .list();

        assertThat(columns).containsExactly(
                varchar("analytics_daily", "metric", 24),
                varchar("analytics_daily", "event_type", 32),
                varchar("analytics_daily", "dimension", 24),
                varchar("analytics_daily", "dimension_value", 253),
                varchar("analytics_daily", "aggregation_version", 32),
                character("analytics_event", "visitor_day_key", 64),
                character("analytics_event", "session_day_key", 64),
                varchar("analytics_event", "event_type", 32),
                varchar("analytics_event", "page_key", 200),
                nullableVarchar("analytics_event", "referrer_domain", 253),
                varchar("analytics_event", "device_class", 16),
                varchar("analytics_event", "locale", 10),
                varchar("analytics_event", "rules_version", 32),
                varchar("contact_message", "visitor_name", 100),
                varchar("contact_message", "visitor_email", 320),
                varchar("contact_message", "subject", 160),
                varchar("contact_message", "body", 5000),
                varchar("contact_message", "status", 24),
                character("contact_message", "dedupe_key", 64),
                varchar("email_outbox", "template_name", 80),
                varchar("email_outbox", "to_address", 320),
                varchar("email_outbox", "stable_message_id", 255),
                varchar("email_outbox", "status", 24),
                nullableVarchar("email_outbox", "lease_owner", 120),
                nullableVarchar("email_outbox", "last_error_summary", 500));
    }

    @Test
    void schemaInstallsTheRequiredKeysChecksIndexesAndTimestampTriggers() {
        List<String> constraints = migratorJdbc().sql("""
                        select relation.relname || ':' || constraint_record.conname
                        from pg_catalog.pg_constraint constraint_record
                        join pg_catalog.pg_class relation
                          on relation.oid = constraint_record.conrelid
                        join pg_catalog.pg_namespace namespace
                          on namespace.oid = relation.relnamespace
                        where namespace.nspname = 'portfolio'
                          and relation.relname = any(cast(:tables as text[]))
                        order by relation.relname, constraint_record.conname
                        """)
                .param("tables", TABLES.toArray(String[]::new))
                .query(String.class)
                .list();
        List<IndexShape> indexes = migratorJdbc().sql("""
                        select index_relation.relname,
                               index_record.indisunique,
                               pg_catalog.pg_get_indexdef(index_relation.oid) definition,
                               coalesce(
                                   pg_catalog.pg_get_expr(
                                       index_record.indpred, index_record.indrelid
                                   ),
                                   ''
                               ) predicate
                        from pg_catalog.pg_index index_record
                        join pg_catalog.pg_class index_relation
                          on index_relation.oid = index_record.indexrelid
                        join pg_catalog.pg_namespace namespace
                          on namespace.oid = index_relation.relnamespace
                        where namespace.nspname = 'portfolio'
                          and index_relation.relname = any(cast(:indexes as text[]))
                        order by index_relation.relname
                        """)
                .param("indexes", new String[] {
                    "analytics_daily_report_idx",
                    "analytics_event_date_idx",
                    "analytics_event_dedupe_idx",
                    "analytics_event_retention_idx",
                    "contact_message_dedupe_idx",
                    "contact_message_inbox_idx",
                    "contact_message_retention_idx",
                    "email_outbox_expired_lease_idx",
                    "email_outbox_ready_idx"
                })
                .query((resultSet, rowNumber) -> new IndexShape(
                        resultSet.getString("relname"),
                        resultSet.getBoolean("indisunique"),
                        resultSet.getString("definition"),
                        resultSet.getString("predicate")))
                .list();
        List<TriggerShape> triggers = migratorJdbc().sql("""
                        select relation.relname,
                               trigger_record.tgname,
                               trigger_record.tgenabled,
                               trigger_record.tgtype,
                               function_namespace.nspname,
                               function_record.proname
                        from pg_catalog.pg_trigger trigger_record
                        join pg_catalog.pg_class relation
                          on relation.oid = trigger_record.tgrelid
                        join pg_catalog.pg_namespace namespace
                          on namespace.oid = relation.relnamespace
                        join pg_catalog.pg_proc function_record
                          on function_record.oid = trigger_record.tgfoid
                        join pg_catalog.pg_namespace function_namespace
                          on function_namespace.oid = function_record.pronamespace
                        where namespace.nspname = 'portfolio'
                          and relation.relname = any(cast(:tables as text[]))
                          and not trigger_record.tgisinternal
                        order by relation.relname, trigger_record.tgname
                        """)
                .param("tables", TABLES.toArray(String[]::new))
                .query((resultSet, rowNumber) -> new TriggerShape(
                        resultSet.getString("relname"),
                        resultSet.getString("tgname"),
                        resultSet.getString("tgenabled"),
                        resultSet.getInt("tgtype"),
                        resultSet.getString("nspname"),
                        resultSet.getString("proname")))
                .list();

        assertThat(constraints).contains(
                "analytics_daily:analytics_daily_dimension_ck",
                "analytics_daily:analytics_daily_dimension_value_ck",
                "analytics_daily:analytics_daily_event_type_ck",
                "analytics_daily:analytics_daily_hmac_leak_ck",
                "analytics_daily:analytics_daily_metric_ck",
                "analytics_daily:analytics_daily_metric_count_ck",
                "analytics_daily:analytics_daily_metric_event_ck",
                "analytics_daily:analytics_daily_pk",
                "analytics_event:analytics_event_client_event_id_uk",
                "analytics_event:analytics_event_device_class_ck",
                "analytics_event:analytics_event_event_type_ck",
                "analytics_event:analytics_event_locale_ck",
                "analytics_event:analytics_event_page_key_ck",
                "analytics_event:analytics_event_pk",
                "analytics_event:analytics_event_referrer_domain_ck",
                "analytics_event:analytics_event_session_day_key_ck",
                "analytics_event:analytics_event_site_date_ck",
                "analytics_event:analytics_event_visitor_day_key_ck",
                "contact_message:contact_message_dedupe_key_ck",
                "contact_message:contact_message_pk",
                "contact_message:contact_message_privacy_time_ck",
                "contact_message:contact_message_status_ck",
                "contact_message:contact_message_version_ck",
                "email_outbox:email_outbox_attempts_ck",
                "email_outbox:email_outbox_contact_message_fk",
                "email_outbox:email_outbox_contact_message_id_uk",
                "email_outbox:email_outbox_lease_state_ck",
                "email_outbox:email_outbox_pk",
                "email_outbox:email_outbox_sent_at_ck",
                "email_outbox:email_outbox_stable_message_id_uk",
                "email_outbox:email_outbox_status_ck");
        assertThat(indexes).extracting(IndexShape::name).containsExactly(
                "analytics_daily_report_idx",
                "analytics_event_date_idx",
                "analytics_event_dedupe_idx",
                "analytics_event_retention_idx",
                "contact_message_dedupe_idx",
                "contact_message_inbox_idx",
                "contact_message_retention_idx",
                "email_outbox_expired_lease_idx",
                "email_outbox_ready_idx");
        assertThat(indexes).allSatisfy(index -> assertThat(index.unique()).isFalse());
        assertThat(requireIndex(indexes, "analytics_daily_report_idx").definition())
                .contains("(site_date, dimension, metric, metric_count DESC)");
        assertThat(requireIndex(indexes, "analytics_event_date_idx").definition())
                .contains("(site_date, event_type)");
        assertThat(requireIndex(indexes, "analytics_event_dedupe_idx").definition())
                .contains(
                        "(session_day_key, event_type, page_key, project_id, received_at DESC)");
        assertThat(requireIndex(indexes, "analytics_event_retention_idx").definition())
                .contains("(received_at, id)");
        assertThat(requireIndex(indexes, "contact_message_dedupe_idx").definition())
                .contains("(dedupe_key, created_at DESC)");
        assertThat(requireIndex(indexes, "contact_message_inbox_idx").definition())
                .contains("(status, created_at DESC, id DESC)");
        assertThat(requireIndex(indexes, "contact_message_retention_idx").definition())
                .contains("(created_at, id)");
        IndexShape expiredLease = requireIndex(indexes, "email_outbox_expired_lease_idx");
        assertThat(expiredLease.definition()).contains("(lease_until, created_at, id)");
        assertThat(expiredLease.predicate()).contains("SENDING");
        IndexShape readyOutbox = requireIndex(indexes, "email_outbox_ready_idx");
        assertThat(readyOutbox.definition()).contains("(next_attempt_at, created_at, id)");
        assertThat(readyOutbox.predicate()).contains("PENDING", "FAILED");
        assertThat(indexes)
                .filteredOn(index -> !index.name().startsWith("email_outbox_"))
                .extracting(IndexShape::predicate)
                .containsOnly("");
        assertThat(triggers).containsExactly(
                new TriggerShape(
                        "analytics_daily",
                        "analytics_daily_set_updated_at",
                        "O",
                        19,
                        "portfolio",
                        "set_updated_at"),
                new TriggerShape(
                        "contact_message",
                        "contact_message_set_updated_at",
                        "O",
                        19,
                        "portfolio",
                        "set_updated_at"),
                new TriggerShape(
                        "email_outbox",
                        "email_outbox_set_updated_at",
                        "O",
                        19,
                        "portfolio",
                        "set_updated_at"));
    }

    @Test
    void runtimeHasOnlyRequiredDmlAndNoSchemaOrTruncatePrivileges() {
        for (String table : TABLES) {
            List<String> effective = List.of(
                            "SELECT",
                            "INSERT",
                            "UPDATE",
                            "DELETE",
                            "TRUNCATE",
                            "REFERENCES",
                            "TRIGGER",
                            "MAINTAIN")
                    .stream()
                    .filter(privilege -> hasTablePrivilege(table, privilege))
                    .toList();
            List<String> expected = switch (table) {
                case "analytics_daily", "analytics_event", "contact_message" ->
                    List.of("SELECT", "INSERT", "DELETE");
                case "email_outbox" -> List.of("SELECT", "INSERT");
                default -> throw new IllegalStateException("unexpected table " + table);
            };
            assertThat(effective).as(table + " effective table privileges").isEqualTo(expected);

            boolean canUpdateAnyColumn = jdbc.sql("""
                            select has_any_column_privilege(
                                cast(:table as text), cast('UPDATE' as text)
                            )
                            """)
                    .param("table", "portfolio." + table)
                    .query(Boolean.class)
                    .single();
            assertThat(canUpdateAnyColumn)
                    .as(table + " effective column UPDATE")
                    .isEqualTo(!table.equals("analytics_event"));
        }
        assertThat(jdbc.sql("""
                        select has_schema_privilege(current_user, 'portfolio', 'CREATE')
                        """)
                .query(Boolean.class)
                .single())
                .isFalse();
    }

    @Test
    void directAclsExposeOnlyTheCapabilityRoleAndNeverGrantOptions() {
        List<TablePrivilege> tablePrivileges = migratorJdbc().sql("""
                        select relation.relname,
                               privilege.privilege_type,
                               privilege.is_grantable
                        from pg_catalog.pg_class relation
                        join pg_catalog.pg_namespace namespace
                          on namespace.oid = relation.relnamespace
                        cross join lateral pg_catalog.aclexplode(
                            coalesce(
                                relation.relacl,
                                pg_catalog.acldefault('r', relation.relowner)
                            )
                        ) privilege
                        join pg_catalog.pg_roles grantee_role
                          on grantee_role.oid = privilege.grantee
                        where namespace.nspname = 'portfolio'
                          and relation.relname = any(cast(:tables as text[]))
                          and grantee_role.rolname = 'portfolio_runtime_access'
                        order by relation.relname, privilege.privilege_type
                        """)
                .param("tables", TABLES.toArray(String[]::new))
                .query((resultSet, rowNumber) -> new TablePrivilege(
                        resultSet.getString("relname"),
                        resultSet.getString("privilege_type"),
                        resultSet.getBoolean("is_grantable")))
                .list();
        List<ColumnPrivilege> columnPrivileges = migratorJdbc().sql("""
                        select relation.relname,
                               attribute.attname,
                               privilege.privilege_type,
                               privilege.is_grantable
                        from pg_catalog.pg_class relation
                        join pg_catalog.pg_namespace namespace
                          on namespace.oid = relation.relnamespace
                        join pg_catalog.pg_attribute attribute
                          on attribute.attrelid = relation.oid
                         and attribute.attnum > 0
                         and not attribute.attisdropped
                        cross join lateral pg_catalog.aclexplode(attribute.attacl) privilege
                        join pg_catalog.pg_roles grantee_role
                          on grantee_role.oid = privilege.grantee
                        where namespace.nspname = 'portfolio'
                          and relation.relname = any(cast(:tables as text[]))
                          and attribute.attacl is not null
                          and grantee_role.rolname = 'portfolio_runtime_access'
                        order by relation.relname, attribute.attname, privilege.privilege_type
                        """)
                .param("tables", TABLES.toArray(String[]::new))
                .query((resultSet, rowNumber) -> new ColumnPrivilege(
                        resultSet.getString("relname"),
                        resultSet.getString("attname"),
                        resultSet.getString("privilege_type"),
                        resultSet.getBoolean("is_grantable")))
                .list();
        Long leakedPrivileges = migratorJdbc().sql("""
                        select count(*)
                        from (
                            select privilege.grantee
                            from pg_catalog.pg_class relation
                            join pg_catalog.pg_namespace namespace
                              on namespace.oid = relation.relnamespace
                            cross join lateral pg_catalog.aclexplode(
                                coalesce(
                                    relation.relacl,
                                    pg_catalog.acldefault('r', relation.relowner)
                                )
                            ) privilege
                            where namespace.nspname = 'portfolio'
                              and relation.relname = any(cast(:tables as text[]))
                            union all
                            select privilege.grantee
                            from pg_catalog.pg_class relation
                            join pg_catalog.pg_namespace namespace
                              on namespace.oid = relation.relnamespace
                            join pg_catalog.pg_attribute attribute
                              on attribute.attrelid = relation.oid
                             and attribute.attnum > 0
                             and not attribute.attisdropped
                            cross join lateral pg_catalog.aclexplode(attribute.attacl) privilege
                            where namespace.nspname = 'portfolio'
                              and relation.relname = any(cast(:tables as text[]))
                              and attribute.attacl is not null
                        ) grants
                        left join pg_catalog.pg_roles grantee_role
                          on grantee_role.oid = grants.grantee
                        where grants.grantee = 0
                           or grantee_role.rolname = 'test_runtime'
                        """)
                .param("tables", TABLES.toArray(String[]::new))
                .query(Long.class)
                .single();
        List<String> owners = migratorJdbc().sql("""
                        select distinct owner_role.rolname
                        from pg_catalog.pg_class relation
                        join pg_catalog.pg_namespace namespace
                          on namespace.oid = relation.relnamespace
                        join pg_catalog.pg_roles owner_role
                          on owner_role.oid = relation.relowner
                        where namespace.nspname = 'portfolio'
                          and relation.relname = any(cast(:tables as text[]))
                        order by owner_role.rolname
                        """)
                .param("tables", TABLES.toArray(String[]::new))
                .query(String.class)
                .list();

        assertThat(tablePrivileges).containsExactly(
                new TablePrivilege("analytics_daily", "DELETE", false),
                new TablePrivilege("analytics_daily", "INSERT", false),
                new TablePrivilege("analytics_daily", "SELECT", false),
                new TablePrivilege("analytics_event", "DELETE", false),
                new TablePrivilege("analytics_event", "INSERT", false),
                new TablePrivilege("analytics_event", "SELECT", false),
                new TablePrivilege("contact_message", "DELETE", false),
                new TablePrivilege("contact_message", "INSERT", false),
                new TablePrivilege("contact_message", "SELECT", false),
                new TablePrivilege("email_outbox", "INSERT", false),
                new TablePrivilege("email_outbox", "SELECT", false));
        assertThat(columnPrivileges).containsExactly(
                new ColumnPrivilege("analytics_daily", "aggregation_version", "UPDATE", false),
                new ColumnPrivilege("analytics_daily", "metric_count", "UPDATE", false),
                new ColumnPrivilege("analytics_daily", "updated_at", "UPDATE", false),
                new ColumnPrivilege("contact_message", "status", "UPDATE", false),
                new ColumnPrivilege("contact_message", "updated_at", "UPDATE", false),
                new ColumnPrivilege("contact_message", "version", "UPDATE", false),
                new ColumnPrivilege("email_outbox", "attempts", "UPDATE", false),
                new ColumnPrivilege("email_outbox", "last_error_summary", "UPDATE", false),
                new ColumnPrivilege("email_outbox", "lease_owner", "UPDATE", false),
                new ColumnPrivilege("email_outbox", "lease_until", "UPDATE", false),
                new ColumnPrivilege("email_outbox", "next_attempt_at", "UPDATE", false),
                new ColumnPrivilege("email_outbox", "sent_at", "UPDATE", false),
                new ColumnPrivilege("email_outbox", "status", "UPDATE", false),
                new ColumnPrivilege("email_outbox", "updated_at", "UPDATE", false));
        assertThat(leakedPrivileges).isZero();
        assertThat(owners).containsExactly("test_migrator");
    }

    @Test
    void databaseRejectsInvalidSensitiveStateAndIdentityLeaks() {
        UUID messageId = UUID.randomUUID();
        UUID leaseMessageId = UUID.randomUUID();
        UUID sentMessageId = UUID.randomUUID();
        UUID validEventId = UUID.randomUUID();
        insertContact(messageId);
        insertContact(leaseMessageId);
        insertContact(sentMessageId);
        try {
            insertPendingOutbox(UUID.randomUUID(), messageId, "message-1@example.invalid");

            assertConstraintViolation(
                    "23505",
                    "email_outbox_contact_message_id_uk",
                    () -> insertPendingOutbox(
                            UUID.randomUUID(), messageId, "message-2@example.invalid"));
            assertConstraintViolation(
                    "23514",
                    "email_outbox_lease_state_ck",
                    () -> migratorJdbc().sql("""
                                    insert into portfolio.email_outbox(
                                        id, contact_message_id, template_name, to_address,
                                        stable_message_id, status, attempts, next_attempt_at
                                    ) values (
                                        :id, :messageId, 'portfolio-contact',
                                        'owner@example.invalid', :messageIdHeader,
                                        'SENDING', 0, '2026-07-14T10:00:00Z'
                                    )
                                    """)
                            .param("id", UUID.randomUUID())
                            .param("messageId", leaseMessageId)
                            .param("messageIdHeader", "lease@example.invalid")
                            .update());
            assertConstraintViolation(
                    "23514",
                    "email_outbox_lease_state_ck",
                    () -> migratorJdbc().sql("""
                                    insert into portfolio.email_outbox(
                                        id, contact_message_id, template_name, to_address,
                                        stable_message_id, status, attempts, next_attempt_at,
                                        lease_owner, lease_until
                                    ) values (
                                        :id, :messageId, 'portfolio-contact',
                                        'owner@example.invalid', :messageIdHeader,
                                        'SENDING', 0, '2026-07-14T10:00:00Z',
                                        '   ', '2026-07-14T10:05:00Z'
                                    )
                                    """)
                            .param("id", UUID.randomUUID())
                            .param("messageId", leaseMessageId)
                            .param("messageIdHeader", "blank-lease@example.invalid")
                            .update());
            assertConstraintViolation(
                    "23514",
                    "email_outbox_sent_at_ck",
                    () -> migratorJdbc().sql("""
                                    insert into portfolio.email_outbox(
                                        id, contact_message_id, template_name, to_address,
                                        stable_message_id, status, attempts, next_attempt_at
                                    ) values (
                                        :id, :messageId, 'portfolio-contact',
                                        'owner@example.invalid', :messageIdHeader,
                                        'SENT', 1, '2026-07-14T10:00:00Z'
                                    )
                                    """)
                            .param("id", UUID.randomUUID())
                            .param("messageId", sentMessageId)
                            .param("messageIdHeader", "sent@example.invalid")
                            .update());
            assertConstraintViolation(
                    "23503",
                    "email_outbox_contact_message_fk",
                    () -> insertPendingOutbox(
                            UUID.randomUUID(), UUID.randomUUID(), "orphan@example.invalid"));

            assertSqlState(
                    "42501",
                    () -> jdbc.sql("""
                                    update portfolio.contact_message
                                    set visitor_email = 'changed@example.invalid'
                                    where id = :id
                                    """)
                            .param("id", messageId)
                            .update());
            assertThat(jdbc.sql("""
                            update portfolio.contact_message
                            set status = 'READ', version = version + 1
                            where id = :id
                            """)
                    .param("id", messageId)
                    .update())
                    .isOne();

            assertConstraintViolation(
                    "23514",
                    "analytics_event_visitor_day_key_ck",
                    () -> migratorJdbc().sql("""
                                    insert into portfolio.analytics_event(
                                        id, client_event_id, site_date, received_at,
                                        visitor_day_key, session_day_key, event_type,
                                        page_key, device_class, locale, rules_version
                                    ) values (
                                        :id, :clientEventId, '2026-07-14',
                                        '2026-07-14T10:00:00Z', :visitorKey, :sessionKey,
                                        'PAGE_VIEW', 'HOME', 'DESKTOP', 'en',
                                        'analytics-rules-v1'
                                    )
                                    """)
                            .param("id", UUID.randomUUID())
                            .param("clientEventId", UUID.randomUUID())
                            .param("visitorKey", "A".repeat(64))
                            .param("sessionKey", HEX_KEY)
                            .update());
            assertConstraintViolation(
                    "23514",
                    "analytics_event_site_date_ck",
                    () -> insertAnalyticsEvent(
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            "2026-07-14",
                            "2026-07-14T16:30:00Z",
                            "HOME",
                            "example.com"));
            assertConstraintViolation(
                    "23514",
                    "analytics_event_page_key_ck",
                    () -> insertAnalyticsEvent(
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            "2026-07-14",
                            "2026-07-14T10:00:00Z",
                            "UNLISTED_PAGE",
                            "example.com"));
            assertConstraintViolation(
                    "23514",
                    "analytics_event_referrer_domain_ck",
                    () -> insertAnalyticsEvent(
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            "2026-07-14",
                            "2026-07-14T10:00:00Z",
                            "HOME",
                            "https://example.com/path?visitor=1"));

            assertThat(insertAnalyticsEvent(
                            validEventId,
                            UUID.randomUUID(),
                            "2026-07-14",
                            "2026-07-14T10:00:00Z",
                            "HOME",
                            "example.com"))
                    .isOne();

            assertConstraintViolation(
                    "23514",
                    "analytics_daily_hmac_leak_ck",
                    () -> migratorJdbc().sql("""
                                    insert into portfolio.analytics_daily(
                                        site_date, metric, event_type, dimension,
                                        dimension_value, metric_count, aggregation_version
                                    ) values (
                                        '2026-07-14', 'EVENT_COUNT', 'PAGE_VIEW',
                                        'REFERRER', :leakedIdentity, 1,
                                        'analytics-rules-v1'
                                    )
                                    """)
                            .param(
                                    "leakedIdentity",
                                    HEX_KEY.substring(0, 32)
                                            + "."
                                            + HEX_KEY.substring(32)
                                            + ".example")
                            .update());
            assertConstraintViolation(
                    "23514",
                    "analytics_daily_metric_event_ck",
                    () -> migratorJdbc().sql("""
                                    insert into portfolio.analytics_daily(
                                        site_date, metric, event_type, dimension,
                                        dimension_value, metric_count, aggregation_version
                                    ) values (
                                        '2026-07-14', 'PV', 'OUTBOUND_CLICK',
                                        'ALL', '(all)', 1, 'analytics-rules-v1'
                                    )
                                    """)
                            .update());
            assertConstraintViolation(
                    "23514",
                    "analytics_daily_metric_count_ck",
                    () -> migratorJdbc().sql("""
                                    insert into portfolio.analytics_daily(
                                        site_date, metric, event_type, dimension,
                                        dimension_value, metric_count, aggregation_version
                                    ) values (
                                        '2026-07-14', 'PV', 'PAGE_VIEW',
                                        'ALL', '(all)', -1, 'analytics-rules-v1'
                                    )
                                    """)
                            .update());
            assertThat(migratorJdbc().sql("""
                            insert into portfolio.analytics_daily(
                                site_date, metric, event_type, dimension,
                                dimension_value, metric_count, aggregation_version
                            ) values
                                ('2099-01-01', 'EVENT_COUNT', 'PAGE_VIEW',
                                 'ALL', '(all)', 1, 'analytics-rules-v1'),
                                ('2099-01-01', 'EVENT_COUNT', 'PAGE_VIEW',
                                 'PAGE', 'PROJECT_DETAIL', 1, 'analytics-rules-v1'),
                                ('2099-01-01', 'EVENT_COUNT', 'PAGE_VIEW',
                                 'PROJECT', '00000000-0000-4000-8000-000000000001',
                                 1, 'analytics-rules-v1'),
                                ('2099-01-01', 'EVENT_COUNT', 'PAGE_VIEW',
                                 'REFERRER', 'example.com', 1, 'analytics-rules-v1'),
                                ('2099-01-01', 'EVENT_COUNT', 'PAGE_VIEW',
                                 'DEVICE', 'DESKTOP', 1, 'analytics-rules-v1'),
                                ('2099-01-01', 'EVENT_COUNT', 'PAGE_VIEW',
                                 'LOCALE', 'zh-CN', 1, 'analytics-rules-v1')
                            """).update()).isEqualTo(6);
        } finally {
            migratorJdbc().sql("delete from portfolio.analytics_event where id = :id")
                    .param("id", validEventId)
                    .update();
            migratorJdbc().sql("delete from portfolio.analytics_daily where site_date = '2099-01-01'")
                    .update();
            deleteContact(messageId);
            deleteContact(leaseMessageId);
            deleteContact(sentMessageId);
        }
    }

    private int insertAnalyticsEvent(
            UUID id,
            UUID clientEventId,
            String siteDate,
            String receivedAt,
            String pageKey,
            String referrerDomain) {
        return migratorJdbc().sql("""
                        insert into portfolio.analytics_event(
                            id, client_event_id, site_date, received_at,
                            visitor_day_key, session_day_key, event_type,
                            page_key, referrer_domain, device_class, locale, rules_version
                        ) values (
                            :id, :clientEventId, cast(:siteDate as date),
                            cast(:receivedAt as timestamptz), :visitorKey, :sessionKey,
                            'PAGE_VIEW', :pageKey, :referrerDomain,
                            'DESKTOP', 'en', 'analytics-rules-v1'
                        )
                        """)
                .param("id", id)
                .param("clientEventId", clientEventId)
                .param("siteDate", siteDate)
                .param("receivedAt", receivedAt)
                .param("visitorKey", HEX_KEY)
                .param("sessionKey", "b".repeat(64))
                .param("pageKey", pageKey)
                .param("referrerDomain", referrerDomain)
                .update();
    }

    @Test
    void plan01RegistersTheExactPublicRateLimitPolicies() {
        assertThat(rateLimits.policies().get("public-contact"))
                .isEqualTo(new RateLimitProperties.Policy(5, Duration.ofMinutes(15)));
        assertThat(rateLimits.policies().get("public-events"))
                .isEqualTo(new RateLimitProperties.Policy(60, Duration.ofMinutes(1)));
    }

    private record ColumnName(String table, String column) {}

    private record BoundedColumn(
            String table, String column, String type, int maximumLength, boolean nullable) {}

    private record IndexShape(
            String name, boolean unique, String definition, String predicate) {}

    private record TriggerShape(
            String table,
            String trigger,
            String enabled,
            int type,
            String functionSchema,
            String functionName) {}

    private record TablePrivilege(String table, String privilege, boolean grantable) {}

    private record ColumnPrivilege(
            String table, String column, String privilege, boolean grantable) {}

    private boolean hasTablePrivilege(String table, String privilege) {
        return jdbc.sql("""
                        select has_table_privilege(
                            cast(:table as text), cast(:privilege as text)
                        )
                        """)
                .param("table", "portfolio." + table)
                .param("privilege", privilege)
                .query(Boolean.class)
                .single();
    }

    private static IndexShape requireIndex(List<IndexShape> indexes, String name) {
        return indexes.stream()
                .filter(index -> index.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static BoundedColumn varchar(String table, String column, int length) {
        return new BoundedColumn(table, column, "character varying", length, false);
    }

    private static BoundedColumn nullableVarchar(String table, String column, int length) {
        return new BoundedColumn(table, column, "character varying", length, true);
    }

    private static BoundedColumn character(String table, String column, int length) {
        return new BoundedColumn(table, column, "character", length, false);
    }

    private void insertContact(UUID id) {
        migratorJdbc().sql("""
                        insert into portfolio.contact_message(
                            id, visitor_name, visitor_email, subject, body, status,
                            dedupe_key, privacy_accepted_at, created_at, updated_at
                        ) values (
                            :id, 'Schema Test', 'visitor@example.invalid',
                            'Schema contract', 'Schema contract body', 'UNREAD',
                            :dedupeKey, '2026-07-14T09:59:59Z',
                            '2026-07-14T10:00:00Z', '2026-07-14T10:00:00Z'
                        )
                        """)
                .param("id", id)
                .param("dedupeKey", HEX_KEY)
                .update();
    }

    private void insertPendingOutbox(UUID id, UUID messageId, String stableMessageId) {
        migratorJdbc().sql("""
                        insert into portfolio.email_outbox(
                            id, contact_message_id, template_name, to_address,
                            stable_message_id, status, attempts, next_attempt_at
                        ) values (
                            :id, :messageId, 'portfolio-contact',
                            'owner@example.invalid', :stableMessageId,
                            'PENDING', 0, '2026-07-14T10:00:00Z'
                        )
                        """)
                .param("id", id)
                .param("messageId", messageId)
                .param("stableMessageId", stableMessageId)
                .update();
    }

    private void deleteContact(UUID id) {
        migratorJdbc().sql("delete from portfolio.contact_message where id = :id")
                .param("id", id)
                .update();
    }

    private static void assertConstraintViolation(
            String sqlState, String constraint, Runnable operation) {
        SQLException failure = sqlFailure(operation);
        assertThat(failure.getSQLState()).isEqualTo(sqlState);
        assertThat((Object) failure).isInstanceOf(PSQLException.class);
        PSQLException postgresFailure = (PSQLException) failure;
        assertThat(postgresFailure.getServerErrorMessage()).isNotNull();
        assertThat(postgresFailure.getServerErrorMessage().getConstraint())
                .isEqualTo(constraint);
    }

    private static void assertSqlState(String sqlState, Runnable operation) {
        assertThat(sqlFailure(operation).getSQLState()).isEqualTo(sqlState);
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
}
