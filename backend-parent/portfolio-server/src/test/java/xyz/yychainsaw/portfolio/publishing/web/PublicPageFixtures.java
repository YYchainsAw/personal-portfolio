package xyz.yychainsaw.portfolio.publishing.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import xyz.yychainsaw.portfolio.publicapi.PublicBlockDto;
import xyz.yychainsaw.portfolio.publicapi.PublicMediaDto;
import xyz.yychainsaw.portfolio.publicapi.PublicProjectCardDto;
import xyz.yychainsaw.portfolio.publicapi.PublicProjectDto;
import xyz.yychainsaw.portfolio.publicapi.PublicSiteDto;

final class PublicPageFixtures {
    static final UUID PROJECT_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000010");
    private static final UUID CARD_ASSET_ID =
            UUID.fromString("20000000-0000-4000-8000-000000000010");
    private static final UUID PROJECT_ASSET_ID =
            UUID.fromString("20000000-0000-4000-8000-000000000011");

    private PublicPageFixtures() {
    }

    static PublicSiteDto site(String headline, boolean withHeroMedia) {
        return new PublicSiteDto(
                new PublicSiteDto.Identity(
                        "YY", "Yi Jiaxuan", "易嘉轩", "hi@yychainsaw.xyz"),
                new PublicSiteDto.Seo(
                        "Yi Jiaxuan — Game Developer", "Published portfolio description"),
                new PublicSiteDto.Accessibility(
                        "Skip",
                        "Primary navigation",
                        "Mobile navigation",
                        "Open menu",
                        "Close menu",
                        "Language",
                        "Back to top",
                        "Project tags"),
                List.of(new PublicSiteDto.NavigationItem("work", 0, "Work")),
                new PublicSiteDto.Hero(
                        "Hello",
                        "Yi Jiaxuan",
                        "易嘉轩",
                        "Game Developer",
                        headline,
                        "Published introduction",
                        "Available",
                        "View work",
                        "Contact",
                        "Portrait",
                        "Stage",
                        "50% 50%",
                        "Photo credit",
                        "https://example.test/source",
                        withHeroMedia ? cardMedia() : null),
                new PublicSiteDto.About(
                        "About",
                        "About me",
                        "Published statement",
                        "Focus",
                        "Unreal Engine",
                        "Learning UE",
                        List.of(new PublicSiteDto.Fact("University", "JXNU")),
                        List.of(new PublicSiteDto.Skill("UE", "Learning"))),
                new PublicSiteDto.Work(
                        "Work",
                        "Published projects",
                        "Selected work",
                        "Images",
                        "Open slot",
                        "Next project",
                        "Expandable",
                        "Coming soon"),
                new PublicSiteDto.Roadmap(
                        "Roadmap",
                        "Next steps",
                        "Published roadmap",
                        List.of(new PublicSiteDto.RoadmapStage(
                                UUID.fromString("30000000-0000-4000-8000-000000000010"),
                                "01",
                                "2026",
                                "Build",
                                "Build games",
                                List.of("Prototype")))),
                new PublicSiteDto.Contact(
                        "Contact",
                        "Let's talk",
                        "Published contact copy",
                        "Email",
                        "hi@yychainsaw.xyz",
                        "Work",
                        "Roadmap",
                        "Footer"),
                new PublicSiteDto.Privacy(
                        "Privacy", "<p>Published privacy policy</p>"),
                List.of(new PublicSiteDto.SocialLink(
                        "GitHub", "https://github.com/YYchainsAw")),
                new PublicSiteDto.Resume(
                        "Resume",
                        LocalDate.parse("2026-07-14"),
                        "/api/public/media/20000000-0000-4000-8000-000000000012/document"));
    }

    static PublicProjectCardDto card() {
        return new PublicProjectCardDto(
                PROJECT_ID,
                "gameplay-prototype",
                "01",
                0,
                true,
                "Published",
                "Featured work",
                "Published card title",
                "Published card summary",
                List.of("UE", "C++"),
                cardMedia());
    }

    static PublicProjectDto project() {
        PublicMediaDto media = projectMedia();
        return new PublicProjectDto(
                PROJECT_ID,
                "gameplay-prototype",
                "01",
                true,
                "Published",
                "Featured work",
                "Published title",
                "Published summary",
                "Published SEO title",
                "Published SEO description",
                List.of("UE", "Gameplay"),
                List.of("C++"),
                List.of(media),
                List.of(
                        block(0, "MARKDOWN", new PublicBlockDto.Markdown(
                                "<p>Published <strong>markdown</strong></p>")),
                        block(1, "IMAGE", new PublicBlockDto.Image(media)),
                        block(2, "GALLERY", new PublicBlockDto.Gallery(
                                List.of(media, cardMedia()))),
                        block(3, "VIDEO", new PublicBlockDto.Video(
                                "youtube",
                                "https://www.youtube.com/embed/AbC_123-xyZ",
                                media,
                                "Published video",
                                "Video description")),
                        block(4, "CODE", new PublicBlockDto.Code(
                                "if (value < 2) return;",
                                "cpp",
                                true,
                                "Code sample",
                                "Code description")),
                        block(5, "QUOTE", new PublicBlockDto.Quote(
                                "Published quote", "Yi Jiaxuan")),
                        block(6, "METRICS", new PublicBlockDto.Metrics(List.of(
                                new PublicBlockDto.Metric(
                                        UUID.fromString(
                                                "40000000-0000-4000-8000-000000000010"),
                                        new BigDecimal("60"),
                                        "Frame rate",
                                        "60",
                                        " FPS")))),
                        block(7, "DOWNLOAD", new PublicBlockDto.Download(
                                "/api/public/media/20000000-0000-4000-8000-000000000012/document",
                                "Download design document",
                                "Published download",
                                "application/pdf",
                                42L)),
                        block(8, "LINK", new PublicBlockDto.Link(
                                "https://example.test/project",
                                true,
                                "Project source",
                                "Published link"))));
    }

    static PublicProjectDto project(String slug) {
        PublicProjectDto project = project();
        return new PublicProjectDto(
                project.projectId(),
                slug,
                project.number(),
                project.featured(),
                project.status(),
                project.eyebrow(),
                project.title(),
                project.summary(),
                project.seoTitle(),
                project.seoDescription(),
                project.tags(),
                project.skills(),
                project.media(),
                project.blocks());
    }

    static PublicProjectDto projectWithSourceOnlyMedia() {
        PublicProjectDto base = project();
        String mediaBase = "/api/public/media/" + PROJECT_ASSET_ID;
        PublicMediaDto sourceOnly = new PublicMediaDto(
                PROJECT_ASSET_ID,
                "w1280",
                mediaBase + "/w1280",
                mediaBase + "/w640 640w, " + mediaBase + "/w1280 1280w",
                "Source-only media",
                "",
                "",
                "https://example.test/source-only",
                1280,
                720);
        return new PublicProjectDto(
                base.projectId(),
                base.slug(),
                base.number(),
                base.featured(),
                base.status(),
                base.eyebrow(),
                base.title(),
                base.summary(),
                base.seoTitle(),
                base.seoDescription(),
                base.tags(),
                base.skills(),
                List.of(sourceOnly),
                List.of(
                        block(0, "IMAGE", new PublicBlockDto.Image(sourceOnly)),
                        block(1, "GALLERY", new PublicBlockDto.Gallery(List.of(sourceOnly))),
                        block(2, "VIDEO", new PublicBlockDto.Video(
                                "youtube",
                                "https://www.youtube.com/embed/AbC_123-xyZ",
                                sourceOnly,
                                "Source-only video",
                                "Video description"))));
    }

    static PublicMediaDto cardMedia() {
        return media(CARD_ASSET_ID, "Published card cover");
    }

    static PublicMediaDto projectMedia() {
        return media(PROJECT_ASSET_ID, "Published project image");
    }

    private static PublicBlockDto block(
            int sortOrder,
            String type,
            PublicBlockDto.Payload payload) {
        return new PublicBlockDto(
                UUID.nameUUIDFromBytes(("block-" + sortOrder).getBytes()),
                type,
                sortOrder,
                "wide",
                "left",
                "normal",
                1,
                payload);
    }

    private static PublicMediaDto media(UUID assetId, String alt) {
        String base = "/api/public/media/" + assetId;
        return new PublicMediaDto(
                assetId,
                "w1280",
                base + "/w1280",
                base + "/w640 640w, " + base + "/w1280 1280w",
                alt,
                "Published caption",
                "Published credit",
                "https://example.test/source",
                1280,
                720);
    }
}
