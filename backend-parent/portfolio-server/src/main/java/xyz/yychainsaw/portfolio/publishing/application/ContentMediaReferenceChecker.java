package xyz.yychainsaw.portfolio.publishing.application;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import xyz.yychainsaw.portfolio.media.application.MediaReference;
import xyz.yychainsaw.portfolio.media.application.MediaReferenceChecker;

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
        return List.copyOf(jdbc.sql("""
                        select reference_type, reference_id
                        from (
                            select 'SITE_WORKSPACE'::text as reference_type,
                                   h.site_id as reference_id
                            from portfolio.hero_media hm
                            join portfolio.hero_section h on h.id = hm.hero_id
                            where hm.media_asset_id = :assetId

                            union

                            select 'SITE_WORKSPACE', rd.site_id
                            from portfolio.resume_document rd
                            where rd.media_asset_id = :assetId

                            union

                            select 'PROJECT_WORKSPACE', pm.project_id
                            from portfolio.project_media pm
                            where pm.media_asset_id = :assetId

                            union

                            select 'PROJECT_WORKSPACE', pcb.project_id
                            from portfolio.content_block_media cbm
                            join portfolio.project_content_block pcb on pcb.id = cbm.block_id
                            where cbm.media_asset_id = :assetId

                            union

                            select 'PROJECT_WORKSPACE', pcb.project_id
                            from portfolio.content_block_video cbv
                            join portfolio.project_content_block pcb on pcb.id = cbv.block_id
                            where cbv.cover_asset_id = :assetId

                            union

                            select 'PROJECT_WORKSPACE', pcb.project_id
                            from portfolio.content_block_action cba
                            join portfolio.project_content_block pcb on pcb.id = cba.block_id
                            where cba.media_asset_id = :assetId

                            union

                            select 'CONTENT_REVISION', rmr.revision_id
                            from portfolio.revision_media_reference rmr
                            where rmr.asset_id = :assetId
                        ) reference
                        order by reference_type, reference_id
                        """)
                .param("assetId", assetId)
                .query(REFERENCE_ROW_MAPPER)
                .list());
    }
}
