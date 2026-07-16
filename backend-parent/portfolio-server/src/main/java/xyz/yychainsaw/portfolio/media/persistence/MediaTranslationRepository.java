package xyz.yychainsaw.portfolio.media.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MediaTranslationRepository {
    private static final RowMapper<MediaTranslationRecord> ROW_MAPPER =
            MediaTranslationRepository::map;

    private final JdbcClient jdbc;

    public MediaTranslationRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc is required");
    }

    public List<MediaTranslationRecord> findByAssetId(UUID assetId) {
        Objects.requireNonNull(assetId, "media asset id is required");
        return jdbc.sql("""
                        select asset_id, locale, alt_text, caption, credit, source_url
                        from portfolio.media_translation
                        where asset_id=:assetId
                        order by locale
                        """)
                .param("assetId", assetId, Types.OTHER)
                .query(ROW_MAPPER)
                .list();
    }

    public void replaceAll(UUID assetId, List<MediaTranslationRecord> records) {
        Objects.requireNonNull(assetId, "media asset id is required");
        List<MediaTranslationRecord> immutable = List.copyOf(
                Objects.requireNonNull(records, "media translations are required"));
        Set<String> locales = new HashSet<>();
        for (MediaTranslationRecord record : immutable) {
            if (record == null
                    || !assetId.equals(record.assetId())
                    || !locales.add(record.locale())) {
                throw new IllegalArgumentException("media translations are invalid");
            }
        }

        deleteByAssetId(assetId);
        for (MediaTranslationRecord record : immutable) {
            jdbc.sql("""
                            insert into portfolio.media_translation(
                                asset_id, locale, alt_text, caption, credit, source_url
                            ) values (
                                :assetId, :locale, :altText, :caption, :credit, :sourceUrl
                            )
                            """)
                    .param("assetId", record.assetId(), Types.OTHER)
                    .param("locale", record.locale(), Types.VARCHAR)
                    .param("altText", record.altText(), Types.VARCHAR)
                    .param("caption", record.caption(), Types.VARCHAR)
                    .param("credit", record.credit(), Types.VARCHAR)
                    .param("sourceUrl", record.sourceUrl(), Types.VARCHAR)
                    .update();
        }
    }

    public int deleteByAssetId(UUID assetId) {
        Objects.requireNonNull(assetId, "media asset id is required");
        return jdbc.sql("delete from portfolio.media_translation where asset_id=:assetId")
                .param("assetId", assetId, Types.OTHER)
                .update();
    }

    private static MediaTranslationRecord map(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new MediaTranslationRecord(
                resultSet.getObject("asset_id", UUID.class),
                resultSet.getString("locale"),
                resultSet.getString("alt_text"),
                resultSet.getString("caption"),
                resultSet.getString("credit"),
                resultSet.getString("source_url"));
    }
}
