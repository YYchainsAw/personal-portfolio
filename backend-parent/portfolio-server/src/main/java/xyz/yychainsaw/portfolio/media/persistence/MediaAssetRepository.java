package xyz.yychainsaw.portfolio.media.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import xyz.yychainsaw.portfolio.api.admin.media.MediaAssetView;
import xyz.yychainsaw.portfolio.media.domain.MediaStatus;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;

@Repository
public class MediaAssetRepository {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
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

    public Optional<MediaAssetRecord> findByIdForUpdate(UUID id) {
        Objects.requireNonNull(id, "media asset id is required");
        return jdbc.sql("select " + COLUMNS
                        + " from portfolio.media_asset where id=:id for update")
                .param("id", id, Types.OTHER)
                .query(ROW_MAPPER)
                .optional();
    }

    public Optional<MediaAssetRecord> findByIdForShare(UUID id) {
        Objects.requireNonNull(id, "media asset id is required");
        return jdbc.sql("select " + COLUMNS
                        + " from portfolio.media_asset where id=:id for share")
                .param("id", id, Types.OTHER)
                .query(ROW_MAPPER)
                .optional();
    }

    public List<MediaAssetRecord> findReadyBySha256(String sha256) {
        return findReadyBySha256(sha256, false);
    }

    public List<MediaAssetRecord> findReadyBySha256ForShare(String sha256) {
        return findReadyBySha256(sha256, true);
    }

    public void lockImportSha256(String sha256) {
        requireSha256(sha256);
        long lockKey = Long.parseUnsignedLong(sha256.substring(0, 16), 16);
        jdbc.sql("select 1 from (select pg_advisory_xact_lock(:lockKey)) locked")
                .param("lockKey", lockKey, Types.BIGINT)
                .query(Integer.class)
                .single();
    }

    public List<MediaAssetRecord> findImportCandidatesBySha256ForShare(
            String sha256) {
        requireSha256(sha256);
        return jdbc.sql("select " + COLUMNS + """
                        from portfolio.media_asset
                        where sha256=:sha256
                          and status in ('READY', 'PROCESSING')
                        order by created_at, id
                        for share
                        """)
                .param("sha256", sha256, Types.VARCHAR)
                .query(ROW_MAPPER)
                .list();
    }

    private List<MediaAssetRecord> findReadyBySha256(
            String sha256, boolean lockForShare) {
        requireSha256(sha256);
        String lockClause = lockForShare ? " for share" : "";
        return jdbc.sql("select " + COLUMNS + """
                        from portfolio.media_asset
                        where sha256=:sha256 and status='READY'
                        order by created_at, id
                        """ + lockClause)
                .param("sha256", sha256, Types.VARCHAR)
                .query(ROW_MAPPER)
                .list();
    }

    private static void requireSha256(String sha256) {
        if (sha256 == null || !SHA256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("media SHA-256 is invalid");
        }
    }

    public MediaAssetPage findPage(
            int page, int size, Optional<MediaStatus> requestedStatus) {
        if (page < 0 || size <= 0 || requestedStatus == null) {
            throw new IllegalArgumentException("media page request is invalid");
        }
        long offset;
        try {
            offset = Math.multiplyExact((long) page, size);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("media page request is invalid", overflow);
        }
        String status = requestedStatus.map(MediaStatus::name).orElse(null);
        List<PageRow> pageRows = jdbc.sql("""
                        with filtered as materialized (
                            select
                        """ + COLUMNS + """
                            from portfolio.media_asset
                            where (:status is null or status=:status)
                        ), page_rows as (
                            select *
                            from filtered
                            order by created_at desc, id desc
                            limit :size offset :offset
                        ), total as (
                            select count(*) as total_items
                            from filtered
                        )
                        select page_rows.*, total.total_items
                        from total
                        left join page_rows on true
                        order by page_rows.created_at desc nulls last,
                                 page_rows.id desc nulls last
                        """)
                .param("status", status, Types.VARCHAR)
                .param("size", size, Types.INTEGER)
                .param("offset", offset, Types.BIGINT)
                .query((resultSet, rowNumber) -> new PageRow(
                        resultSet.getObject("id", UUID.class) == null
                                ? null
                                : map(resultSet, rowNumber),
                        resultSet.getLong("total_items")))
                .list();
        if (pageRows.isEmpty()) {
            throw new IllegalStateException("media page query returned no total");
        }
        long total = pageRows.get(0).totalItems();
        List<MediaAssetRecord> rows = new ArrayList<>(pageRows.size());
        for (PageRow pageRow : pageRows) {
            if (pageRow.totalItems() != total) {
                throw new IllegalStateException("media page query returned inconsistent totals");
            }
            if (pageRow.asset() != null) {
                rows.add(pageRow.asset());
            }
        }
        return new MediaAssetPage(rows, total);
    }

    public List<UUID> findArchivedIdsAtOrBefore(
            Instant cutoff, UUID afterExclusive, int limit) {
        Objects.requireNonNull(cutoff, "media cleanup cutoff is required");
        if (limit <= 0 || limit > 1_000) {
            throw new IllegalArgumentException("media cleanup batch size is invalid");
        }
        return jdbc.sql("""
                        select id
                        from portfolio.media_asset
                        where status='ARCHIVED'
                          and archived_at <= :cutoff
                          and (
                              cast(:afterExclusive as uuid) is null
                              or id > cast(:afterExclusive as uuid)
                          )
                        order by id
                        limit :limit
                        """)
                .param(
                        "cutoff",
                        OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .param("afterExclusive", afterExclusive, Types.OTHER)
                .param("limit", limit, Types.INTEGER)
                .query(UUID.class)
                .list();
    }

    public int markPendingDelete(UUID id, long expectedVersion, Instant cutoff) {
        requireCleanupFence(id, expectedVersion);
        Objects.requireNonNull(cutoff, "media cleanup cutoff is required");
        return jdbc.sql("""
                        update portfolio.media_asset
                        set status='PENDING_DELETE'
                        where id=:id
                          and version=:expectedVersion
                          and status='ARCHIVED'
                          and archived_at <= :cutoff
                        """)
                .param("id", id, Types.OTHER)
                .param("expectedVersion", expectedVersion, Types.BIGINT)
                .param(
                        "cutoff",
                        OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    public int deletePending(UUID id, long expectedVersion) {
        requireCleanupFence(id, expectedVersion);
        return jdbc.sql("""
                        delete from portfolio.media_asset
                        where id=:id
                          and version=:expectedVersion
                          and status='PENDING_DELETE'
                        """)
                .param("id", id, Types.OTHER)
                .param("expectedVersion", expectedVersion, Types.BIGINT)
                .update();
    }

    public Optional<MediaAssetRecord> incrementVersion(UUID id, long expectedVersion) {
        Objects.requireNonNull(id, "media asset id is required");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("media asset version is invalid");
        }
        return jdbc.sql("""
                        update portfolio.media_asset
                        set version=version + 1
                        where id=:id
                          and version=:expectedVersion
                          and status <> 'PENDING_DELETE'
                        returning
                        """ + COLUMNS)
                .param("id", id, Types.OTHER)
                .param("expectedVersion", expectedVersion, Types.BIGINT)
                .query(ROW_MAPPER)
                .optional();
    }

    public Optional<MediaAssetRecord> archive(UUID id, long expectedVersion) {
        Objects.requireNonNull(id, "media asset id is required");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("media asset version is invalid");
        }
        return jdbc.sql("""
                        update portfolio.media_asset
                        set status='ARCHIVED',
                            archived_at=clock_timestamp(),
                            version=version + 1
                        where id=:id
                          and version=:expectedVersion
                          and status in ('READY', 'FAILED')
                        returning
                        """ + COLUMNS)
                .param("id", id, Types.OTHER)
                .param("expectedVersion", expectedVersion, Types.BIGINT)
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

    private static void requireCleanupFence(UUID id, long expectedVersion) {
        Objects.requireNonNull(id, "media asset id is required");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("media asset version is invalid");
        }
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

    private record PageRow(MediaAssetRecord asset, long totalItems) {}
}
