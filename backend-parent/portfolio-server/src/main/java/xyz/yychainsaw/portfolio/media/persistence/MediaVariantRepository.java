package xyz.yychainsaw.portfolio.media.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MediaVariantRepository {
    private static final String COLUMNS = """
            id, asset_id, variant_name, format, object_key, mime_type,
            byte_size, width, height, sha256, status, created_at
            """;
    private static final RowMapper<MediaVariantRecord> ROW_MAPPER =
            MediaVariantRepository::map;

    private final JdbcClient jdbc;

    public MediaVariantRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc is required");
    }

    public boolean insertReadyIfAbsent(MediaVariantRecord.Insert insert) {
        Objects.requireNonNull(insert, "media variant insert is required");
        return jdbc.sql("""
                        with eligible_asset as materialized (
                            select id
                            from portfolio.media_asset
                            where id=:assetId and status='PROCESSING'
                            for key share
                        )
                        insert into portfolio.media_variant(
                            id, asset_id, variant_name, format, object_key, mime_type,
                            byte_size, width, height, sha256, status
                        )
                        select
                            :id, eligible_asset.id, :variantName, :format,
                            :objectKey, :mimeType, :byteSize, :width, :height,
                            :sha256, 'READY'
                        from eligible_asset
                        on conflict (asset_id, variant_name) do nothing
                        returning id
                        """)
                .param("id", insert.id(), Types.OTHER)
                .param("assetId", insert.assetId(), Types.OTHER)
                .param("variantName", insert.variantName(), Types.VARCHAR)
                .param("format", insert.format(), Types.VARCHAR)
                .param("objectKey", insert.objectKey(), Types.VARCHAR)
                .param("mimeType", insert.mimeType(), Types.VARCHAR)
                .param("byteSize", insert.byteSize(), Types.BIGINT)
                .param("width", insert.width(), Types.INTEGER)
                .param("height", insert.height(), Types.INTEGER)
                .param("sha256", insert.sha256(), Types.VARCHAR)
                .query(UUID.class)
                .optional()
                .isPresent();
    }

    /**
     * Serializes completion against in-flight child writes. The parent lock blocks a
     * writer's eligibility/key-share check; current child locks fence their mutation or
     * deletion until the exact-set transaction commits. Future child mutation APIs must
     * use this same graph lock and retain the PROCESSING-state fence.
     */
    public boolean lockAssetGraph(UUID assetId) {
        Objects.requireNonNull(assetId, "media asset id is required");
        boolean parentExists = jdbc.sql("""
                        select id
                        from portfolio.media_asset
                        where id=:assetId
                        for update
                        """)
                .param("assetId", assetId, Types.OTHER)
                .query(UUID.class)
                .optional()
                .isPresent();
        if (!parentExists) {
            return false;
        }
        jdbc.sql("""
                        select id
                        from portfolio.media_variant
                        where asset_id=:assetId
                        order by id
                        for update
                        """)
                .param("assetId", assetId, Types.OTHER)
                .query(UUID.class)
                .list();
        return true;
    }

    public Optional<MediaVariantRecord> findByAssetAndName(
            UUID assetId, String variantName) {
        Objects.requireNonNull(assetId, "media asset id is required");
        if (variantName == null || variantName.isBlank()) {
            throw new IllegalArgumentException("media variant name is invalid");
        }
        return jdbc.sql("select " + COLUMNS + " from portfolio.media_variant "
                        + "where asset_id=:assetId and variant_name=:variantName")
                .param("assetId", assetId, Types.OTHER)
                .param("variantName", variantName, Types.VARCHAR)
                .query(ROW_MAPPER)
                .optional();
    }

    public List<MediaVariantRecord> findByAssetId(UUID assetId) {
        Objects.requireNonNull(assetId, "media asset id is required");
        return jdbc.sql("select " + COLUMNS + " from portfolio.media_variant "
                        + "where asset_id=:assetId order by variant_name")
                .param("assetId", assetId, Types.OTHER)
                .query(ROW_MAPPER)
                .list();
    }

    public int deleteByAssetId(UUID assetId) {
        Objects.requireNonNull(assetId, "media asset id is required");
        return jdbc.sql("delete from portfolio.media_variant where asset_id=:assetId")
                .param("assetId", assetId, Types.OTHER)
                .update();
    }

    private static MediaVariantRecord map(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new MediaVariantRecord(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("asset_id", UUID.class),
                resultSet.getString("variant_name"),
                resultSet.getString("format"),
                resultSet.getString("object_key"),
                resultSet.getString("mime_type"),
                resultSet.getLong("byte_size"),
                resultSet.getObject("width", Integer.class),
                resultSet.getObject("height", Integer.class),
                resultSet.getString("sha256"),
                resultSet.getString("status"),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
