package xyz.yychainsaw.portfolio.publishing.application;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import xyz.yychainsaw.portfolio.media.application.MediaReference;
import xyz.yychainsaw.portfolio.media.application.MediaReferenceChecker;

/**
 * Resolves all normalized V7 workspace references to an asset. Revision/snapshot
 * references are added to this same checker when the V8 publishing schema lands.
 */
@Component
public final class ContentMediaReferenceChecker implements MediaReferenceChecker {
    private static final RowMapper<MediaReference> REFERENCE_ROW_MAPPER =
            (resultSet, rowNumber) -> new MediaReference(
                    resultSet.getString("reference_type"),
                    resultSet.getObject("reference_id", UUID.class));

    private final JdbcClient jdbc;

    public ContentMediaReferenceChecker(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc is required");
    }

    @Override
    public List<MediaReference> findReferences(UUID assetId) {
        Objects.requireNonNull(assetId, "assetId");
        return jdbc.sql("""
                        select distinct reference_type, reference_id
                        from (
                            select 'SITE_WORKSPACE'::varchar as reference_type,
                                   hero.site_id as reference_id
                            from portfolio.hero_media media
                            join portfolio.hero_section hero on hero.id = media.hero_id
                            where media.media_asset_id = :assetId

                            union all

                            select 'SITE_WORKSPACE'::varchar, document.site_id
                            from portfolio.resume_document document
                            where document.media_asset_id = :assetId

                            union all

                            select 'PROJECT_WORKSPACE'::varchar, media.project_id
                            from portfolio.project_media media
                            where media.media_asset_id = :assetId

                            union all

                            select 'PROJECT_WORKSPACE'::varchar, block.project_id
                            from portfolio.content_block_media media
                            join portfolio.project_content_block block
                              on block.id = media.block_id
                            where media.media_asset_id = :assetId

                            union all

                            select 'PROJECT_WORKSPACE'::varchar, block.project_id
                            from portfolio.content_block_video video
                            join portfolio.project_content_block block
                              on block.id = video.block_id
                            where video.cover_asset_id = :assetId

                            union all

                            select 'PROJECT_WORKSPACE'::varchar, block.project_id
                            from portfolio.content_block_action action
                            join portfolio.project_content_block block
                              on block.id = action.block_id
                            where action.media_asset_id = :assetId
                        ) references_by_workspace
                        order by reference_type, reference_id
                        """)
                .param("assetId", assetId)
                .query(REFERENCE_ROW_MAPPER)
                .list();
    }
}
