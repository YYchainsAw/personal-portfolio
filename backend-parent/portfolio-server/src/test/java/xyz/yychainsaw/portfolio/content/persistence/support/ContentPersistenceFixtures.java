package xyz.yychainsaw.portfolio.content.persistence.support;

import java.net.URI;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import xyz.yychainsaw.portfolio.content.api.ContentBlockDto;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.content.api.ProjectWorkspaceDto;
import xyz.yychainsaw.portfolio.content.api.SiteWorkspaceDto;
import xyz.yychainsaw.portfolio.content.support.WorkspaceFixtures;

public final class ContentPersistenceFixtures {
    public static final UUID HERO_ASSET_ID =
            UUID.fromString("81000000-0000-4000-8000-000000000001");
    public static final UUID RESUME_ASSET_ID =
            UUID.fromString("81000000-0000-4000-8000-000000000002");
    public static final UUID RESUME_ID =
            UUID.fromString("81000000-0000-4000-8000-000000000003");
    public static final UUID TAG_ID =
            UUID.fromString("82000000-0000-4000-8000-000000000001");
    public static final UUID SKILL_ID =
            UUID.fromString("82000000-0000-4000-8000-000000000002");
    public static final UUID PROJECT_ID =
            UUID.fromString("82000000-0000-4000-8000-000000000003");
    public static final List<UUID> PROJECT_ASSET_IDS = List.of(
            UUID.fromString("83000000-0000-4000-8000-000000000001"),
            UUID.fromString("83000000-0000-4000-8000-000000000002"),
            UUID.fromString("83000000-0000-4000-8000-000000000003"),
            UUID.fromString("83000000-0000-4000-8000-000000000004"),
            UUID.fromString("83000000-0000-4000-8000-000000000005"),
            UUID.fromString("83000000-0000-4000-8000-000000000006"));

    private ContentPersistenceFixtures() {}

    public static SiteWorkspaceDto siteWithMedia(long version) {
        SiteWorkspaceDto source = WorkspaceFixtures.site(version);
        SiteWorkspaceDto.Hero sourceHero = source.hero();
        SiteWorkspaceDto.Hero hero = new SiteWorkspaceDto.Hero(
                sourceHero.id(),
                sourceHero.version(),
                HERO_ASSET_ID,
                "50% 40%",
                "Portfolio portrait",
                URI.create("https://example.test/portrait"),
                sourceHero.copy());
        SiteWorkspaceDto.ResumeDocument resume = new SiteWorkspaceDto.ResumeDocument(
                RESUME_ID,
                LocaleCode.EN,
                RESUME_ASSET_ID,
                "2026.1",
                true,
                LocalDate.of(2026, 7, 17));
        return copySite(source, version, source.monogram(), hero, List.of(resume));
    }

    public static SiteWorkspaceDto siteWithoutMedia(long version) {
        return WorkspaceFixtures.site(version);
    }

    public static SiteWorkspaceDto withVersion(SiteWorkspaceDto source, long version) {
        return copySite(source, version, source.monogram(), source.hero(), source.resumes());
    }

    public static SiteWorkspaceDto withMonogram(SiteWorkspaceDto source, String monogram) {
        return copySite(source, source.version(), monogram, source.hero(), source.resumes());
    }

    public static SiteWorkspaceDto withHero(SiteWorkspaceDto source, SiteWorkspaceDto.Hero hero) {
        return copySite(source, source.version(), source.monogram(), hero, source.resumes());
    }

    public static SiteWorkspaceDto withResumes(
            SiteWorkspaceDto source,
            List<SiteWorkspaceDto.ResumeDocument> resumes) {
        return copySite(
                source,
                source.version(),
                source.monogram(),
                source.hero(),
                resumes);
    }

    public static ProjectWorkspaceDto projectWithAllPayloads() {
        return project(PROJECT_ID, "complete-project", "complete-project", 0, 0L, true, true);
    }

    public static ProjectWorkspaceDto simpleProject(
            UUID id, String key, int sortOrder, long version) {
        return project(id, key, key, sortOrder, version, false, false);
    }

    public static ProjectWorkspaceDto withProjectVersion(
            ProjectWorkspaceDto source, long version, boolean dirty) {
        return new ProjectWorkspaceDto(
                source.id(), source.externalKey(), source.slug(), source.number(), source.sortOrder(),
                source.featured(), source.visible(), dirty, version, source.translations(), source.tags(),
                source.skills(), source.media(), source.blocks());
    }

    public static ProjectWorkspaceDto withEnglishProjectTitle(
            ProjectWorkspaceDto source, String title) {
        Map<LocaleCode, ProjectWorkspaceDto.ProjectCopy> copy = new java.util.EnumMap<>(source.translations());
        ProjectWorkspaceDto.ProjectCopy english = copy.get(LocaleCode.EN);
        copy.put(LocaleCode.EN, new ProjectWorkspaceDto.ProjectCopy(
                english.status(), english.eyebrow(), title, english.summary(),
                english.seoTitle(), english.seoDescription()));
        return new ProjectWorkspaceDto(
                source.id(), source.externalKey(), source.slug(), source.number(), source.sortOrder(),
                source.featured(), source.visible(), source.publicationDirty(), source.version(), copy,
                source.tags(), source.skills(), source.media(), source.blocks());
    }

    private static ProjectWorkspaceDto project(
            UUID id,
            String externalKey,
            String slug,
            int sortOrder,
            long version,
            boolean taxonomy,
            boolean allPayloads) {
        Map<LocaleCode, ProjectWorkspaceDto.ProjectCopy> translations = Map.of(
                LocaleCode.ZH_CN, new ProjectWorkspaceDto.ProjectCopy(
                        "开发中", "作品", "完整项目", "持久化测试项目", "完整项目", "项目描述"),
                LocaleCode.EN, new ProjectWorkspaceDto.ProjectCopy(
                        "In progress", "Work", "Complete project", "Persistence test project",
                        "Complete project", "Project description"));
        List<ProjectWorkspaceDto.TaxonomyRef> tags = taxonomy
                ? List.of(new ProjectWorkspaceDto.TaxonomyRef(
                        TAG_ID, "gameplay", 0, localized("玩法", "Gameplay")))
                : List.of();
        List<ProjectWorkspaceDto.TaxonomyRef> skills = taxonomy
                ? List.of(new ProjectWorkspaceDto.TaxonomyRef(
                        SKILL_ID, "unreal-engine", 0, localized("虚幻引擎", "Unreal Engine")))
                : List.of();
        List<ProjectWorkspaceDto.ProjectMedia> media = allPayloads
                ? List.of(new ProjectWorkspaceDto.ProjectMedia(
                        PROJECT_ASSET_IDS.get(0), "COVER", 0, "wide", "50% 50%", "Project credit",
                        URI.create("https://example.test/project-cover")))
                : List.of();
        return new ProjectWorkspaceDto(
                id,
                externalKey,
                slug,
                String.format("%02d", sortOrder + 1),
                sortOrder,
                sortOrder == 0,
                true,
                true,
                version,
                translations,
                tags,
                skills,
                media,
                allPayloads ? allBlocks() : List.of());
    }

    private static List<ContentBlockDto> allBlocks() {
        return List.of(
                block(1, new ContentBlockDto.MarkdownPayload(localized("**正文**", "**Body**"))),
                block(2, new ContentBlockDto.ImagePayload(PROJECT_ASSET_IDS.get(1))),
                block(3, new ContentBlockDto.GalleryPayload(
                        List.of(PROJECT_ASSET_IDS.get(2), PROJECT_ASSET_IDS.get(3)))),
                block(4, new ContentBlockDto.VideoPayload(
                        "YOUTUBE", URI.create("https://youtube.com/watch?v=task4"),
                        PROJECT_ASSET_IDS.get(4), localized(
                                new ContentBlockDto.BlockCopy("视频", "演示"),
                                new ContentBlockDto.BlockCopy("Video", "Demo")))),
                block(5, new ContentBlockDto.CodePayload(
                        "int main() { return 0; }", "cpp", true, localized(
                                new ContentBlockDto.BlockCopy("代码", "示例"),
                                new ContentBlockDto.BlockCopy("Code", "Example")))),
                block(6, new ContentBlockDto.QuotePayload(localized(
                        new ContentBlockDto.QuoteCopy("持续创作", "易嘉轩"),
                        new ContentBlockDto.QuoteCopy("Keep shipping", "Yi Jiaxuan")))),
                block(7, new ContentBlockDto.MetricsPayload(List.of(new ContentBlockDto.Metric(
                        UUID.fromString("84000000-0000-4000-8000-000000000070"),
                        0,
                        new BigDecimal("60.5"),
                        localized(
                                new ContentBlockDto.MetricCopy("帧率", "60.5", " FPS"),
                                new ContentBlockDto.MetricCopy("Frame rate", "60.5", " FPS")))))),
                block(8, new ContentBlockDto.DownloadPayload(
                        PROJECT_ASSET_IDS.get(5), null, localized(
                                new ContentBlockDto.ActionCopy("下载", "获取文件"),
                                new ContentBlockDto.ActionCopy("Download", "Get file")))),
                block(9, new ContentBlockDto.LinkPayload(
                        URI.create("https://example.test/project"), false, localized(
                                new ContentBlockDto.ActionCopy("访问", "打开项目"),
                                new ContentBlockDto.ActionCopy("Visit", "Open project")))));
    }

    private static ContentBlockDto block(int index, ContentBlockDto.Payload payload) {
        return new ContentBlockDto(
                UUID.fromString(String.format("84000000-0000-4000-8000-%012d", index)),
                index - 1,
                index != 6,
                ContentBlockDto.Width.values()[(index - 1) % ContentBlockDto.Width.values().length],
                ContentBlockDto.Alignment.values()[(index - 1) % ContentBlockDto.Alignment.values().length],
                ContentBlockDto.Emphasis.values()[(index - 1) % ContentBlockDto.Emphasis.values().length],
                ((index - 1) % 4) + 1,
                payload);
    }

    private static <T> Map<LocaleCode, T> localized(T chinese, T english) {
        return Map.of(LocaleCode.ZH_CN, chinese, LocaleCode.EN, english);
    }

    private static SiteWorkspaceDto copySite(
            SiteWorkspaceDto source,
            long version,
            String monogram,
            SiteWorkspaceDto.Hero hero,
            List<SiteWorkspaceDto.ResumeDocument> resumes) {
        return new SiteWorkspaceDto(
                source.siteId(),
                version,
                monogram,
                source.email(),
                source.identity(),
                source.seo(),
                source.accessibility(),
                source.navigation(),
                hero,
                source.about(),
                source.facts(),
                source.profileSkills(),
                source.work(),
                source.roadmap(),
                source.contact(),
                source.privacy(),
                source.socialLinks(),
                resumes);
    }
}
