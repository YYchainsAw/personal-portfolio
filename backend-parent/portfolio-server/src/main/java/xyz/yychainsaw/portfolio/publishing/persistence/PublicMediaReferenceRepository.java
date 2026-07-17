package xyz.yychainsaw.portfolio.publishing.persistence;

import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class PublicMediaReferenceRepository {
    private final JdbcClient jdbc;

    public PublicMediaReferenceRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc is required");
    }

    @Transactional(readOnly = true)
    public boolean isCurrentlyPublished(UUID assetId, String variantName) {
        Objects.requireNonNull(assetId, "assetId");
        if (variantName == null || variantName.isBlank()) {
            throw new IllegalArgumentException("variantName must not be blank");
        }
        return Boolean.TRUE.equals(jdbc.sql("""
                        select exists (
                            select 1
                            from portfolio.revision_media_reference rmr
                            join portfolio.publication p
                              on p.current_revision_id = rmr.revision_id
                            where rmr.asset_id = :assetId
                              and rmr.variant_name = :variantName
                              and p.status = 'PUBLISHED'
                        )
                        """)
                .param("assetId", assetId)
                .param("variantName", variantName)
                .query(Boolean.class)
                .single());
    }
}
