package xyz.yychainsaw.portfolio.content.persistence.mybatis;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ProjectWorkspaceMapper {
    Map<String, Object> selectProject(@Param("projectId") UUID projectId);

    Map<String, Object> selectProjectForUpdate(@Param("projectId") UUID projectId);

    List<Map<String, Object>> selectAllProjects();

    List<Map<String, Object>> selectRows(
            @Param("tableKey") String tableKey,
            @Param("projectId") UUID projectId);

    List<UUID> selectExistingTagIds(@Param("ids") Collection<UUID> ids);

    List<UUID> selectExistingSkillIds(@Param("ids") Collection<UUID> ids);

    List<Map<String, Object>> selectBlockOwners(@Param("ids") Collection<UUID> ids);

    List<Map<String, Object>> selectMetricOwners(@Param("ids") Collection<UUID> ids);

    int insertRoot(
            @Param("workspace") Map<String, Object> workspace,
            @Param("updatedAt") Instant updatedAt);

    int updateRoot(
            @Param("workspace") Map<String, Object> workspace,
            @Param("expectedVersion") long expectedVersion,
            @Param("updatedAt") Instant updatedAt);

    int deleteOwnedRows(
            @Param("tableKey") String tableKey,
            @Param("projectId") UUID projectId);

    int insertRows(
            @Param("tableKey") String tableKey,
            @Param("rows") List<Map<String, Object>> rows);

    int markPublicationDirty(
            @Param("projectIds") Collection<UUID> projectIds,
            @Param("updatedAt") Instant updatedAt);

    int markPublished(
            @Param("projectId") UUID projectId,
            @Param("expectedVersion") long expectedVersion,
            @Param("updatedAt") Instant updatedAt);

    List<Map<String, Object>> lockCatalog();

    int moveCatalogToTemporary(@Param("temporaryBase") int temporaryBase);

    int assignCatalogOrder(
            @Param("rows") List<Map<String, Object>> rows,
            @Param("updatedAt") Instant updatedAt);
}
