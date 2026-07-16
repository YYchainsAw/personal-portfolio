package xyz.yychainsaw.portfolio.media.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import xyz.yychainsaw.portfolio.api.admin.media.MediaAssetView;
import xyz.yychainsaw.portfolio.media.domain.MediaStatus;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;

@Repository
public class MediaAssetRepository {
    private static final String COLUMNS = """
            id, provider, bucket, region, object_key, original_filename, mime_type,
            byte_size, width, height, sha256, status, archived_at, version,
            created_at, updated_at
            """;
    private static final RowMapper<MediaAssetRecord> ROW_MAPPER =
            MediaAssetRepository::map;

    private final JdbcClient jdbc;

    public MediaAssetRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc is required");
    }

    public MediaAssetRecord insertProcessing(MediaAssetRecord.Insert insert) {
        Objects.requireNonNull(insert, "media insert is required");
        return jdbc.sql("""
                        insert into portfolio.media_asset(
                            id, provider, bucket, region, object_key, original_filename,
                            mime_type, byte_size, width, height, sha256, status
                        ) values (
                            :id, :provider, :bucket, :region, :objectKey, :originalFilename,
                            :mimeType, :byteSize, :width, :height, :sha256, 'PROCESSING'
                        )
                        returning
                        """ + COLUMNS)
                .param("id", insert.id(), Types.OTHER)
                .param("provider", insert.provider().name(), Types.VARCHAR)
                .param("bucket", insert.bucket(), Types.VARCHAR)
                .param("region", insert.region(), Types.VARCHAR)
                .param("objectKey", insert.objectKey(), Types.VARCHAR)
                .param("originalFilename", insert.originalFilename(), Types.VARCHAR)
                .param("mimeType", insert.mimeType(), Types.VARCHAR)
                .param("byteSize", insert.byteSize(), Types.BIGINT)
                .param("width", insert.width(), Types.INTEGER)
                .param("height", insert.height(), Types.INTEGER)
                .param("sha256", insert.sha256(), Types.VARCHAR)
                .query(ROW_MAPPER)
                .single();
    }

    public Optional<MediaAssetRecord> findById(UUID id) {
        Objects.requireNonNull(id, "media asset id is required");
        return jdbc.sql("select " + COLUMNS + " from portfolio.media_asset where id=:id")
                .param("id", id, Types.OTHER)
                .query(ROW_MAPPER)
                .optional();
    }

    public int markReadyIfProcessing(UUID id) {
        return transitionProcessing(id, "READY");
    }

    public int markFailedIfProcessing(UUID id) {
        return transitionProcessing(id, "FAILED");
    }

    public MediaAssetView toView(MediaAssetRecord record) {
        return Objects.requireNonNull(record, "media asset record is required").toView();
    }

    private int transitionProcessing(UUID id, String targetStatus) {
        Objects.requireNonNull(id, "media asset id is required");
        return jdbc.sql("""
                        update portfolio.media_asset
                        set status=:targetStatus, version=version + 1
                        where id=:id and status='PROCESSING'
                        """)
                .param("targetStatus", targetStatus, Types.VARCHAR)
                .param("id", id, Types.OTHER)
                .update();
    }

    private static MediaAssetRecord map(ResultSet resultSet, int rowNumber)
            throws SQLException {
        OffsetDateTime archivedAt =
                resultSet.getObject("archived_at", OffsetDateTime.class);
        return new MediaAssetRecord(
                resultSet.getObject("id", UUID.class),
                StorageProvider.valueOf(resultSet.getString("provider")),
                resultSet.getString("bucket"),
                resultSet.getString("region"),
                resultSet.getString("object_key"),
                resultSet.getString("original_filename"),
                resultSet.getString("mime_type"),
                resultSet.getLong("byte_size"),
                resultSet.getObject("width", Integer.class),
                resultSet.getObject("height", Integer.class),
                resultSet.getString("sha256"),
                MediaStatus.valueOf(resultSet.getString("status")),
                archivedAt == null ? null : archivedAt.toInstant(),
                resultSet.getLong("version"),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("updated_at", OffsetDateTime.class).toInstant());
    }
}
