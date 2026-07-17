package xyz.yychainsaw.portfolio.content.persistence.mybatis;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SiteWorkspaceMapper {
    Map<String, Object> selectProfile(@Param("siteId") UUID siteId);

    Map<String, Object> selectProfileForUpdate(@Param("siteId") UUID siteId);

    List<Map<String, Object>> selectRows(
            @Param("tableKey") String tableKey,
            @Param("siteId") UUID siteId);

    int updateRoot(
            @Param("siteId") UUID siteId,
            @Param("monogram") String monogram,
            @Param("email") String email,
            @Param("expectedVersion") long expectedVersion,
            @Param("updatedAt") Instant updatedAt);

    int deleteOwnedRows(
            @Param("tableKey") String tableKey,
            @Param("siteId") UUID siteId);

    int insertRows(
            @Param("tableKey") String tableKey,
            @Param("rows") List<Map<String, Object>> rows);
}
