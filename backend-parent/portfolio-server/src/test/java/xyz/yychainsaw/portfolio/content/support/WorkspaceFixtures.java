package xyz.yychainsaw.portfolio.content.support;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import xyz.yychainsaw.portfolio.content.api.ContentBlockDto;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.content.api.ProjectWorkspaceDto;
import xyz.yychainsaw.portfolio.content.api.SiteWorkspaceDto;

public final class WorkspaceFixtures {
    private static final UUID PROJECT_ID =
            uuid("20000000-0000-4000-8000-000000000001");
    private static final UUID HERO_ID =
            uuid("10000000-0000-4000-8000-000000000001");
    private static final UUID IMAGE_ASSET_ID =
            uuid("50000000-0000-4000-8000-000000000001");
    private static final UUID GALLERY_ASSET_ONE_ID =
            uuid("50000000-0000-4000-8000-000000000002");
    private static final UUID GALLERY_ASSET_TWO_ID =
            uuid("50000000-0000-4000-8000-000000000003");
    private static final UUID VIDEO_COVER_ASSET_ID =
            uuid("50000000-0000-4000-8000-000000000004");

    private WorkspaceFixtures() {}

    public static ProjectBuilder projectBuilder() {
        return new ProjectBuilder();
    }

    public static SiteWorkspaceDto site(long version) {
        SiteWorkspaceDto.NavigationItem workNavigation =
                new SiteWorkspaceDto.NavigationItem(
                        uuid("10000000-0000-4000-8000-000000000010"),
                        "work",
                        0,
                        true,
                        localized("作品", "Work"));
        SiteWorkspaceDto.NavigationItem contactNavigation =
                new SiteWorkspaceDto.NavigationItem(
                        uuid("10000000-0000-4000-8000-000000000011"),
                        "contact",
                        1,
                        true,
                        localized("联系", "Contact"));
        SiteWorkspaceDto.Hero hero = new SiteWorkspaceDto.Hero(
                HERO_ID,
                3L,
                null,
                "50% 50%",
                null,
                null,
                localized(
                        new SiteWorkspaceDto.HeroCopy(
                                "你好，我是",
                                "易嘉轩",
                                "Yi Jiaxuan",
                                "游戏开发学习者",
                                "用代码与交互构建可玩的世界",
                                "我正在学习 Unreal Engine，并持续完成可展示的游戏作品。",
                                "开放学习与合作交流",
                                "查看作品",
                                "了解路线",
                                "个人视觉",
                                "当前阶段"),
                        new SiteWorkspaceDto.HeroCopy(
                                "Hello, I am",
                                "Yi Jiaxuan",
                                "易嘉轩",
                                "Game development learner",
                                "Building playable worlds with code and interaction",
                                "I am learning Unreal Engine and completing portfolio-ready games.",
                                "Open to learning and collaboration",
                                "View work",
                                "See roadmap",
                                "Profile visual",
                                "Current stage")));
        SiteWorkspaceDto.ProfileFact fact = new SiteWorkspaceDto.ProfileFact(
                uuid("10000000-0000-4000-8000-000000000020"),
                "education",
                0,
                localized(
                        new SiteWorkspaceDto.LabelValueCopy("学校", "江西师范大学"),
                        new SiteWorkspaceDto.LabelValueCopy(
                                "University", "Jiangxi Normal University")));
        SiteWorkspaceDto.ProfileSkill skill = new SiteWorkspaceDto.ProfileSkill(
                uuid("10000000-0000-4000-8000-000000000021"),
                "unreal-engine",
                0,
                localized(
                        new SiteWorkspaceDto.SkillStatusCopy("Unreal Engine", "学习中"),
                        new SiteWorkspaceDto.SkillStatusCopy("Unreal Engine", "Learning")));
        SiteWorkspaceDto.RoadmapOutcome outcome = new SiteWorkspaceDto.RoadmapOutcome(
                uuid("10000000-0000-4000-8000-000000000031"),
                0,
                localized("完成一个可玩的 UE 原型", "Complete a playable UE prototype"));
        SiteWorkspaceDto.RoadmapStage stage = new SiteWorkspaceDto.RoadmapStage(
                uuid("10000000-0000-4000-8000-000000000030"),
                "ue-foundation",
                "01",
                0,
                true,
                localized(
                        new SiteWorkspaceDto.RoadmapStageCopy(
                                "现在", "夯实 UE 基础", "学习蓝图、C++ 与关卡制作。"),
                        new SiteWorkspaceDto.RoadmapStageCopy(
                                "Now", "Build UE foundations", "Study Blueprints, C++, and levels.")),
                List.of(outcome));

        return new SiteWorkspaceDto(
                SiteWorkspaceDto.SITE_ID,
                version,
                "YJX",
                "portfolio@example.test",
                localized(
                        new SiteWorkspaceDto.IdentityCopy("易嘉轩", "Yi Jiaxuan"),
                        new SiteWorkspaceDto.IdentityCopy("Yi Jiaxuan", "易嘉轩")),
                localized(
                        new SiteWorkspaceDto.SeoCopy(
                                "易嘉轩 · 游戏开发作品集", "易嘉轩的游戏开发学习与作品记录。"),
                        new SiteWorkspaceDto.SeoCopy(
                                "Yi Jiaxuan · Game Development Portfolio",
                                "Game development work and learning by Yi Jiaxuan.")),
                localized(
                        new SiteWorkspaceDto.AccessibilityCopy(
                                "跳到主要内容",
                                "主导航",
                                "移动导航",
                                "打开菜单",
                                "关闭菜单",
                                "语言",
                                "返回顶部",
                                "项目标签"),
                        new SiteWorkspaceDto.AccessibilityCopy(
                                "Skip to main content",
                                "Primary navigation",
                                "Mobile navigation",
                                "Open menu",
                                "Close menu",
                                "Language",
                                "Back to top",
                                "Project tags")),
                List.of(workNavigation, contactNavigation),
                hero,
                localized(
                        new SiteWorkspaceDto.AboutCopy(
                                "关于", "持续学习，也持续交付", "以可玩的成果验证每一次学习。",
                                "当前重点", "Unreal Engine", "从核心系统开始构建完整体验。"),
                        new SiteWorkspaceDto.AboutCopy(
                                "About", "Keep learning and shipping", "Prove learning through playable work.",
                                "Current focus", "Unreal Engine", "Build complete experiences from core systems.")),
                List.of(fact),
                List.of(skill),
                localized(
                        new SiteWorkspaceDto.WorkCopy(
                                "作品", "选择的项目", "这里记录可玩的原型与完整作品。",
                                "清晰项目图片将在完成后补充。", "开放位置", "下一项作品",
                                "为后续更完整的项目预留空间。", "持续更新"),
                        new SiteWorkspaceDto.WorkCopy(
                                "Work", "Selected projects", "Playable prototypes and finished work live here.",
                                "Clear project imagery will be added as work completes.", "Open slot", "Next project",
                                "Reserved for a more complete future project.", "Continuously updated")),
                new SiteWorkspaceDto.Roadmap(
                        localized(
                                new SiteWorkspaceDto.RoadmapHeaderCopy(
                                        "路线", "下一步发展", "以连续的小型作品积累工程与设计能力。"),
                                new SiteWorkspaceDto.RoadmapHeaderCopy(
                                        "Roadmap", "Where I am heading", "Build engineering and design skill through shipped projects.")),
                        List.of(stage)),
                localized(
                        new SiteWorkspaceDto.ContactCopy(
                                "联系", "一起交流游戏开发", "欢迎讨论作品、技术与学习路线。",
                                "邮箱", "查看作品", "查看路线", "感谢你的来访。"),
                        new SiteWorkspaceDto.ContactCopy(
                                "Contact", "Let's talk game development", "Open to discussing work, technology, and learning.",
                                "Email", "View work", "View roadmap", "Thanks for visiting.")),
                localized(
                        new SiteWorkspaceDto.PrivacyCopy(
                                "隐私", "本站仅处理提供页面与必要安全功能所需的数据。"),
                        new SiteWorkspaceDto.PrivacyCopy(
                                "Privacy", "This site processes only data needed for pages and essential security.")),
                List.of(new SiteWorkspaceDto.SocialLink(
                        uuid("10000000-0000-4000-8000-000000000040"),
                        "GitHub",
                        URI.create("https://github.com/YYchainsAw"),
                        0,
                        true)),
                List.of());
    }

    public static ProjectWorkspaceDto project() {
        return projectBuilder().build();
    }

    public static ProjectWorkspaceDto projectWithoutMedia() {
        return projectBuilder()
                .media(List.of())
                .blocks(List.of())
                .build();
    }

    public static ProjectWorkspaceDto projectWithImageGalleryAndVideoCover() {
        ContentBlockDto image = block(
                "40000000-0000-4000-8000-000000000001",
                0,
                new ContentBlockDto.ImagePayload(IMAGE_ASSET_ID));
        ContentBlockDto gallery = block(
                "40000000-0000-4000-8000-000000000002",
                1,
                new ContentBlockDto.GalleryPayload(
                        List.of(GALLERY_ASSET_ONE_ID, GALLERY_ASSET_TWO_ID)));
        ContentBlockDto video = block(
                "40000000-0000-4000-8000-000000000003",
                2,
                new ContentBlockDto.VideoPayload(
                        "YOUTUBE",
                        URI.create("https://www.youtube.com/watch?v=portfolio-test"),
                        VIDEO_COVER_ASSET_ID,
                        localized(
                                new ContentBlockDto.BlockCopy("演示视频", "玩法与关卡演示。"),
                                new ContentBlockDto.BlockCopy("Demo video", "Gameplay and level demonstration."))));
        return projectBuilder().blocks(List.of(image, gallery, video)).build();
    }

    public static ProjectWorkspaceDto withEnglishTitle(
            ProjectWorkspaceDto source,
            String title) {
        LinkedHashMap<LocaleCode, ProjectWorkspaceDto.ProjectCopy> translations =
                new LinkedHashMap<>(source.translations());
        ProjectWorkspaceDto.ProjectCopy english = translations.get(LocaleCode.EN);
        translations.put(
                LocaleCode.EN,
                new ProjectWorkspaceDto.ProjectCopy(
                        english.status(),
                        english.eyebrow(),
                        title,
                        english.summary(),
                        english.seoTitle(),
                        english.seoDescription()));
        return new ProjectWorkspaceDto(
                source.id(),
                source.externalKey(),
                source.slug(),
                source.number(),
                source.sortOrder(),
                source.featured(),
                source.visible(),
                source.publicationDirty(),
                source.version(),
                translations,
                source.tags(),
                source.skills(),
                source.media(),
                source.blocks());
    }

    private static ContentBlockDto block(
            String id,
            int sortOrder,
            ContentBlockDto.Payload payload) {
        return new ContentBlockDto(
                uuid(id),
                sortOrder,
                true,
                ContentBlockDto.Width.STANDARD,
                ContentBlockDto.Alignment.LEFT,
                ContentBlockDto.Emphasis.NONE,
                1,
                payload);
    }

    private static Map<LocaleCode, ProjectWorkspaceDto.ProjectCopy> projectTranslations() {
        return localized(
                new ProjectWorkspaceDto.ProjectCopy(
                        "开发中",
                        "游戏开发",
                        "项目测试",
                        "一个用于验证作品集内容工作区的完整双语项目。",
                        "项目测试 · 易嘉轩",
                        "易嘉轩的游戏开发项目测试。"),
                new ProjectWorkspaceDto.ProjectCopy(
                        "In progress",
                        "Game development",
                        "Project Test",
                        "A complete bilingual project for validating the portfolio workspace.",
                        "Project Test · Yi Jiaxuan",
                        "A game development project test by Yi Jiaxuan."));
    }

    private static List<ProjectWorkspaceDto.TaxonomyRef> projectTags() {
        return List.of(new ProjectWorkspaceDto.TaxonomyRef(
                uuid("20000000-0000-4000-8000-000000000010"),
                "gameplay",
                0,
                localized("玩法", "Gameplay")));
    }

    private static List<ProjectWorkspaceDto.TaxonomyRef> projectSkills() {
        return List.of(new ProjectWorkspaceDto.TaxonomyRef(
                uuid("20000000-0000-4000-8000-000000000011"),
                "unreal-engine",
                0,
                localized("虚幻引擎", "Unreal Engine")));
    }

    private static <T> Map<LocaleCode, T> localized(T chinese, T english) {
        return Map.of(LocaleCode.ZH_CN, chinese, LocaleCode.EN, english);
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }

    public static final class ProjectBuilder {
        private String slug = "project-test";
        private long version;
        private boolean publicationDirty = true;
        private Map<LocaleCode, ProjectWorkspaceDto.ProjectCopy> translations =
                projectTranslations();
        private List<ProjectWorkspaceDto.TaxonomyRef> tags = projectTags();
        private List<ProjectWorkspaceDto.TaxonomyRef> skills = projectSkills();
        private List<ProjectWorkspaceDto.ProjectMedia> media = List.of();
        private List<ContentBlockDto> blocks = List.of();

        private ProjectBuilder() {}

        public ProjectBuilder slug(String slug) {
            this.slug = slug;
            return this;
        }

        public ProjectBuilder translations(
                Map<LocaleCode, ProjectWorkspaceDto.ProjectCopy> translations) {
            this.translations = translations;
            return this;
        }

        public ProjectBuilder blocks(List<ContentBlockDto> blocks) {
            this.blocks = blocks;
            return this;
        }

        public ProjectBuilder media(List<ProjectWorkspaceDto.ProjectMedia> media) {
            this.media = media;
            return this;
        }

        public ProjectBuilder version(long version) {
            this.version = version;
            return this;
        }

        public ProjectBuilder publicationDirty(boolean publicationDirty) {
            this.publicationDirty = publicationDirty;
            return this;
        }

        public ProjectWorkspaceDto build() {
            return new ProjectWorkspaceDto(
                    PROJECT_ID,
                    "project-test",
                    slug,
                    "01",
                    0,
                    false,
                    true,
                    publicationDirty,
                    version,
                    translations,
                    tags,
                    skills,
                    media,
                    blocks);
        }
    }
}
