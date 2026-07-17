package xyz.yychainsaw.portfolio.content.persistence.mybatis;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TaxonomyMapper {
    List<Map<String, Object>> selectTags();

    List<Map<String, Object>> selectSkills();

    Map<String, Object> selectTagForUpdate(@Param("id") UUID id);

    Map<String, Object> selectSkillForUpdate(@Param("id") UUID id);

    List<UUID> lockProjectsLinkedToTag(@Param("id") UUID id);

    List<UUID> lockProjectsLinkedToSkill(@Param("id") UUID id);

    int insertTag(
            @Param("id") UUID id,
            @Param("normalizedKey") String normalizedKey,
            @Param("updatedAt") Instant updatedAt);

    int updateTag(
            @Param("id") UUID id,
            @Param("expectedVersion") long expectedVersion,
            @Param("updatedAt") Instant updatedAt);

    int updateSkill(
            @Param("id") UUID id,
            @Param("expectedVersion") long expectedVersion,
            @Param("updatedAt") Instant updatedAt);

    int deleteTagTranslations(@Param("id") UUID id);

    int deleteSkillTranslations(@Param("id") UUID id);

    int insertTagTranslations(@Param("rows") List<Map<String, Object>> rows);

    int insertSkillTranslations(@Param("rows") List<Map<String, Object>> rows);

    int markProjectsDirty(
            @Param("projectIds") Collection<UUID> projectIds,
            @Param("updatedAt") Instant updatedAt);
}
