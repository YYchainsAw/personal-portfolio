package xyz.yychainsaw.portfolio.publishing.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import xyz.yychainsaw.portfolio.content.api.SiteWorkspaceDto;
import xyz.yychainsaw.portfolio.media.application.MediaChangeListener;
import xyz.yychainsaw.portfolio.media.application.MediaChangeType;

@Component
public class ContentMediaChangeListener implements MediaChangeListener {
    private final JdbcClient jdbc;
    private final Clock clock;

    public ContentMediaChangeListener(JdbcClient jdbc, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void onMediaChanged(UUID assetId, MediaChangeType changeType) {
        Objects.requireNonNull(assetId, "assetId");
        Objects.requireNonNull(changeType, "changeType");
        Instant changedAt = clock.instant();

        boolean siteLocked = jdbc.sql("""
                        select site.id
                        from portfolio.site_profile site
                        where site.id = :siteId
                          and exists (
                            select 1
                            from portfolio.hero_media hero_media
                            join portfolio.hero_section hero on hero.id = hero_media.hero_id
                            where hero.site_id = site.id
                              and hero_media.media_asset_id = :assetId
                            union
                            select 1
                            from portfolio.resume_document resume
                            where resume.site_id = site.id
                              and resume.media_asset_id = :assetId
                          )
                        for update
                        """)
                .param("siteId", SiteWorkspaceDto.SITE_ID)
                .param("assetId", assetId)
                .query(UUID.class)
                .optional()
                .isPresent();
        if (siteLocked) {
            jdbc.sql("""
                            update portfolio.site_profile
                            set version=version+1, updated_at=:changedAt
                            where id=:siteId
                            """)
                    .param("changedAt", Timestamp.from(changedAt))
                    .param("siteId", SiteWorkspaceDto.SITE_ID)
                    .update();
        }

        List<UUID> projectIds = jdbc.sql("""
                        select project.id
                        from portfolio.project project
                        where project.id in (
                            select media.project_id
                            from portfolio.project_media media
                            where media.media_asset_id = :assetId
                            union
                            select block.project_id
                            from portfolio.content_block_media media
                            join portfolio.project_content_block block on block.id = media.block_id
                            where media.media_asset_id = :assetId
                            union
                            select block.project_id
                            from portfolio.content_block_video video
                            join portfolio.project_content_block block on block.id = video.block_id
                            where video.cover_asset_id = :assetId
                            union
                            select block.project_id
                            from portfolio.content_block_action action
                            join portfolio.project_content_block block on block.id = action.block_id
                            where action.media_asset_id = :assetId
                        )
                        order by project.id
                        for update
                        """)
                .param("assetId", assetId)
                .query(UUID.class)
                .list();
        for (UUID projectId : projectIds) {
            jdbc.sql("""
                            update portfolio.project
                            set version=version+1, publication_dirty=true, updated_at=:changedAt
                            where id=:projectId
                            """)
                    .param("changedAt", Timestamp.from(changedAt))
                    .param("projectId", projectId)
                    .update();
        }
    }
}
