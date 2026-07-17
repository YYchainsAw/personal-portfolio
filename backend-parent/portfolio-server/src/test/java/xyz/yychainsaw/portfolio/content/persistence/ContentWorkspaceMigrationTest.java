package xyz.yychainsaw.portfolio.content.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
class ContentWorkspaceMigrationTest extends PostgresIntegrationTestBase {
    private static final UUID SITE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final List<String> WORKSPACE_TABLES = List.of(
            "site_profile",
            "site_profile_translation",
            "site_seo_translation",
            "site_accessibility_copy_translation",
            "site_navigation_item",
            "site_navigation_item_translation",
            "hero_section",
            "hero_section_translation",
            "hero_media",
            "about_section_translation",
            "work_section_translation",
            "contact_section_translation",
            "privacy_notice_translation",
            "social_link",
            "profile_fact",
            "profile_fact_translation",
            "profile_skill",
            "profile_skill_translation",
            "tag",
            "tag_translation",
            "skill",
            "skill_translation",
            "project",
            "project_translation",
            "project_tag",
            "project_skill",
            "project_media",
            "project_content_block",
            "project_content_block_translation",
            "content_block_media",
            "content_block_markdown_translation",
            "content_block_video",
            "content_block_code",
            "content_block_quote_translation",
            "content_block_action",
            "content_block_metric",
            "content_block_metric_translation",
            "roadmap_header_translation",
            "roadmap_stage",
            "roadmap_stage_translation",
            "roadmap_outcome",
            "roadmap_outcome_translation",
            "resume_document");

    @Autowired JdbcClient jdbc;

    @Test
    void v7CreatesEveryNormalizedWorkspaceTableAndFixedSiteRow() {
        List<String> versions = migratorJdbc()
                .sql("""
                        select version
                        from portfolio.flyway_schema_history
                        where success and version is not null
                        order by installed_rank
                        """)
                .query(String.class)
                .list();
        List<String> actualTables = migratorJdbc()
                .sql("""
                        select table_name
                        from information_schema.tables
                        where table_schema = 'portfolio'
                        order by table_name
                        """)
                .query(String.class)
                .list();
        List<SiteRow> siteRows = migratorJdbc()
                .sql("""
                        select id, monogram, email, version
                        from portfolio.site_profile
                        order by id
                        """)
                .query((resultSet, rowNumber) -> new SiteRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("monogram"),
                        resultSet.getString("email"),
                        resultSet.getLong("version")))
                .list();

        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(versions).startsWith("1", "2", "3", "4", "5", "6", "7");
            softly.assertThat(actualTables).containsAll(WORKSPACE_TABLES);
            softly.assertThat(siteRows).containsExactly(new SiteRow(SITE_ID, "", "", 0L));
        });
    }

    @Test
    void v7RejectsInvalidLocaleSlugAndExternalActionWithNamedChecks() {
        assertConstraintViolation(
                "site_profile_translation_locale_ck",
                () -> jdbc.sql("""
                                insert into portfolio.site_profile_translation(
                                    site_id, locale, display_name, secondary_name
                                ) values (:siteId, 'fr', 'Nom', 'Name')
                                """)
                        .param("siteId", SITE_ID)
                        .update());

        assertConstraintViolation(
                "project_slug_ck",
                () -> jdbc.sql("""
                                insert into portfolio.project(
                                    id, external_key, slug, number_label, sort_order
                                ) values (:id, :externalKey, 'Bad Slug', '01', :sortOrder)
                                """)
                        .param("id", UUID.randomUUID())
                        .param("externalKey", "invalid-slug-" + UUID.randomUUID())
                        .param("sortOrder", 2_000_000_000)
                        .update());

        UUID projectId = UUID.randomUUID();
        UUID blockId = UUID.randomUUID();
        String key = "action-check-" + projectId;
        assertThat(jdbc.sql("""
                        insert into portfolio.project(
                            id, external_key, slug, number_label, sort_order
                        ) values (:id, :externalKey, :slug, '98', 1900000000)
                        """)
                .param("id", projectId)
                .param("externalKey", key)
                .param("slug", key)
                .update()).isOne();
        assertThat(jdbc.sql("""
                        insert into portfolio.project_content_block(
                            id, project_id, block_type, sort_order
                        ) values (:id, :projectId, 'LINK', 0)
                        """)
                .param("id", blockId)
                .param("projectId", projectId)
                .update()).isOne();
        try {
            assertConstraintViolation(
                    "content_block_action_target_ck",
                    () -> jdbc.sql("""
                                    insert into portfolio.content_block_action(
                                        block_id, action_type, target_type, url
                                    ) values (:blockId, 'LINK', 'EXTERNAL', null)
                                    """)
                            .param("blockId", blockId)
                            .update());
        } finally {
            assertThat(jdbc.sql("delete from portfolio.project where id=:id")
                    .param("id", projectId)
                    .update()).isOne();
        }
    }

    @Test
    void runtimeHasOnlyTheIntendedWorkspaceCapabilities() {
        UUID projectId = UUID.randomUUID();
        String key = "grant-" + projectId;
        int sortOrder = 1_000_000_000 + Math.floorMod(projectId.hashCode(), 1_000_000);

        assertThat(jdbc.sql("""
                        update portfolio.site_profile
                        set monogram=monogram, email=email, version=version, updated_at=updated_at
                        where id=:siteId
                        """)
                .param("siteId", SITE_ID)
                .update()).isOne();
        assertThat(jdbc.sql("""
                        insert into portfolio.project(
                            id, external_key, slug, number_label, sort_order
                        ) values (:id, :externalKey, :slug, '99', :sortOrder)
                        """)
                .param("id", projectId)
                .param("externalKey", key)
                .param("slug", key)
                .param("sortOrder", sortOrder)
                .update()).isOne();
        assertThat(jdbc.sql("""
                        update portfolio.project set featured=true where id=:id
                        """)
                .param("id", projectId)
                .update()).isOne();
        assertThat(jdbc.sql("delete from portfolio.project where id=:id")
                .param("id", projectId)
                .update()).isOne();

        assertPermissionDenied(() -> jdbc.sql("""
                        insert into portfolio.site_profile(id)
                        values (:id)
                        """)
                .param("id", UUID.randomUUID())
                .update());
        assertPermissionDenied(() -> jdbc.sql("""
                        delete from portfolio.site_profile where id=:siteId
                        """)
                .param("siteId", SITE_ID)
                .update());
        assertPermissionDenied(() -> jdbc.sql("""
                        update portfolio.site_profile set created_at=clock_timestamp()
                        where id=:siteId
                        """)
                .param("siteId", SITE_ID)
                .update());
        assertPermissionDenied(
                () -> jdbc.sql("create table portfolio.v7_runtime_must_not_create(id integer)")
                        .update());

        Long missingChildCrudPrivileges = migratorJdbc()
                .sql("""
                        select count(*)
                        from pg_catalog.pg_class relation
                        join pg_catalog.pg_namespace namespace
                          on namespace.oid=relation.relnamespace
                        cross join (values ('SELECT'), ('INSERT'), ('UPDATE'), ('DELETE'))
                          requested(privilege)
                        where namespace.nspname='portfolio'
                          and relation.relname::text = any(cast(:tables as text[]))
                          and relation.relname <> 'site_profile'
                          and not pg_catalog.has_table_privilege(
                              'portfolio_runtime_access', relation.oid, requested.privilege
                          )
                        """)
                .param("tables", WORKSPACE_TABLES.toArray(String[]::new))
                .query(Long.class)
                .single();
        Long dangerousRuntimePrivileges = migratorJdbc()
                .sql("""
                        select count(*)
                        from pg_catalog.pg_class relation
                        join pg_catalog.pg_namespace namespace
                          on namespace.oid=relation.relnamespace
                        cross join (values ('TRUNCATE'), ('REFERENCES'), ('TRIGGER'), ('MAINTAIN'))
                          requested(privilege)
                        where namespace.nspname='portfolio'
                          and relation.relname::text = any(cast(:tables as text[]))
                          and pg_catalog.has_table_privilege(
                              'portfolio_runtime_access', relation.oid, requested.privilege
                          )
                        """)
                .param("tables", WORKSPACE_TABLES.toArray(String[]::new))
                .query(Long.class)
                .single();
        Long publicPrivileges = migratorJdbc()
                .sql("""
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
                          and relation.relname::text = any(cast(:tables as text[]))
                          and privilege.grantee=0
                        """)
                .param("tables", WORKSPACE_TABLES.toArray(String[]::new))
                .query(Long.class)
                .single();

        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(missingChildCrudPrivileges).isZero();
            softly.assertThat(dangerousRuntimePrivileges).isZero();
            softly.assertThat(publicPrivileges).isZero();
        });
    }

    private static void assertConstraintViolation(String constraintName, Runnable operation) {
        SQLException failure = sqlFailure(operation);
        assertThat(failure.getSQLState()).isEqualTo("23514");
        assertThat(failure.getMessage()).contains(constraintName);
    }

    private static void assertPermissionDenied(Runnable operation) {
        assertThat(sqlFailure(operation).getSQLState()).isEqualTo("42501");
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

    private record SiteRow(UUID id, String monogram, String email, long version) {}
}
