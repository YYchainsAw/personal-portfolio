package xyz.yychainsaw.portfolio.publishing.application;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;

@Repository
public class JdbcPublishingReadQueries
        implements CurrentPublicationQuery, ProjectLabelQuery {
    private static final UUID PROJECT_CATALOG_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");

    private final JdbcClient jdbc;

    public JdbcPublishingReadQueries(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc is required");
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isCurrentPublishedProject(UUID projectId) {
        Objects.requireNonNull(projectId, "projectId");
        return Boolean.TRUE.equals(jdbc.sql("""
                        select exists (
                            select 1
                            from portfolio.publication project_publication
                            join portfolio.content_revision project_revision
                              on project_revision.id = project_publication.current_revision_id
                             and project_revision.aggregate_type = 'PROJECT'
                             and project_revision.aggregate_id = project_publication.aggregate_id
                             and project_revision.snapshot_schema_version = 1
                            join portfolio.publication catalog_publication
                              on catalog_publication.aggregate_type = 'PROJECT_CATALOG'
                             and catalog_publication.aggregate_id = :catalogId
                             and catalog_publication.status = 'PUBLISHED'
                            join portfolio.content_revision catalog_revision
                              on catalog_revision.id = catalog_publication.current_revision_id
                             and catalog_revision.aggregate_type = 'PROJECT_CATALOG'
                             and catalog_revision.aggregate_id = catalog_publication.aggregate_id
                             and catalog_revision.snapshot_schema_version = 1
                            cross join lateral jsonb_array_elements(
                                case when jsonb_typeof(catalog_revision.snapshot -> 'projects') = 'array'
                                     then catalog_revision.snapshot -> 'projects'
                                     else '[]'::jsonb end
                            ) catalog_project
                            where project_publication.aggregate_type = 'PROJECT'
                              and project_publication.aggregate_id = :projectId
                              and project_publication.status = 'PUBLISHED'
                              and catalog_project ->> 'projectId' = project_publication.aggregate_id::text
                        )
                        """)
                .param("catalogId", PROJECT_CATALOG_ID)
                .param("projectId", projectId)
                .query(Boolean.class)
                .single());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> findProjectTitle(UUID projectId, LocaleCode locale) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(locale, "locale");
        return jdbc.sql("""
                        select translation.title
                        from portfolio.project project_workspace
                        join portfolio.project_translation translation
                          on translation.project_id = project_workspace.id
                        join portfolio.publication publication
                          on publication.aggregate_type = 'PROJECT'
                         and publication.aggregate_id = project_workspace.id
                         and publication.status in ('PUBLISHED', 'ARCHIVED')
                         and publication.current_revision_id is not null
                        where project_workspace.id = :projectId
                          and translation.locale = :locale
                        """)
                .param("projectId", projectId)
                .param("locale", locale.value())
                .query(String.class)
                .optional();
    }
}
