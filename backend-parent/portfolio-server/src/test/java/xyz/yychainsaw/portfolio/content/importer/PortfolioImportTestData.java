package xyz.yychainsaw.portfolio.content.importer;

import java.net.URI;
import java.util.List;
import java.util.Map;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;

final class PortfolioImportTestData {
    private PortfolioImportTestData() {}

    static PortfolioImportV1 validPayload() {
        return payload(false, false);
    }

    static PortfolioImportV1 placeholderPayload() {
        return payload(true, false);
    }

    static PortfolioImportV1 incompleteTranslationPayload() {
        return payload(false, true);
    }

    private static PortfolioImportV1 payload(boolean placeholders, boolean blanks) {
        String email = placeholders ? "your-email@example.com" : "dev@example.com";
        Map<LocaleCode, String> heroAlt = Map.of(
                LocaleCode.ZH_CN, "英雄图替代文本",
                LocaleCode.EN, blanks ? "" : "Hero image alternative text");
        return new PortfolioImportV1(
                1,
                new PortfolioImportV1.Identity("YJX", "易嘉轩", "Jiaxuan Yi", email),
                new PortfolioImportV1.HeroAsset(
                        "/images/hero.png",
                        "center",
                        "Hero credit",
                        URI.create("https://example.com/hero"),
                        heroAlt),
                List.of(new PortfolioImportV1.ProjectAsset(
                        "project-one",
                        "/images/project.png",
                        "wide",
                        "center",
                        "Project credit",
                        URI.create("https://example.com/project"),
                        Map.of(
                                LocaleCode.ZH_CN, "项目图替代文本",
                                LocaleCode.EN, "Project image alternative text"))),
                Map.of(
                        LocaleCode.ZH_CN,
                        copy(LocaleCode.ZH_CN, email, placeholders, blanks),
                        LocaleCode.EN,
                        copy(LocaleCode.EN, email, placeholders, blanks)));
    }

    private static PortfolioImportV1.PortfolioCopy copy(
            LocaleCode locale, String email, boolean placeholders, boolean blanks) {
        boolean chinese = locale == LocaleCode.ZH_CN;
        String prefix = chinese ? "中文" : "English";
        String visualLabel = placeholders
                ? (chinese
                        ? "视觉概念图 / 之后替换为本人 UE 截图"
                        : "Visual concept image / replace with my own UE capture")
                : prefix + " visual label";
        String imageNotice = placeholders
                ? (chinese
                        ? "概念占位图，之后替换为本人 UE 截图"
                        : "Concept placeholder - to be replaced with my own UE capture")
                : prefix + " image notice";
        String emailLabel = placeholders
                ? (chinese ? "联系邮箱（待替换）" : "Email placeholder")
                : prefix + " email label";
        String headline = !chinese && blanks ? "" : prefix + " headline";
        String projectSummary = chinese && blanks ? "" : prefix + " project summary";

        return new PortfolioImportV1.PortfolioCopy(
                new PortfolioImportV1.Seo(prefix + " title", prefix + " description"),
                new PortfolioImportV1.A11y(
                        prefix + " skip",
                        prefix + " primary nav",
                        prefix + " mobile nav",
                        prefix + " open menu",
                        prefix + " close menu",
                        prefix + " language",
                        prefix + " back to top",
                        prefix + " project tags"),
                new PortfolioImportV1.Nav(
                        prefix + " about",
                        prefix + " work",
                        prefix + " roadmap",
                        prefix + " contact"),
                new PortfolioImportV1.Hero(
                        prefix + " eyebrow",
                        prefix + " display name",
                        prefix + " secondary name",
                        prefix + " role",
                        headline,
                        prefix + " introduction",
                        prefix + " availability",
                        prefix + " primary CTA",
                        prefix + " secondary CTA",
                        visualLabel,
                        prefix + " stage label"),
                new PortfolioImportV1.About(
                        prefix + " about label",
                        prefix + " about title",
                        prefix + " statement",
                        prefix + " focus label",
                        prefix + " focus title",
                        prefix + " focus intro",
                        List.of(new PortfolioImportV1.Fact(
                                prefix + " fact label", prefix + " fact value")),
                        List.of(new PortfolioImportV1.ProfileSkill(
                                prefix + " skill name", prefix + " skill status"))),
                new PortfolioImportV1.Work(
                        prefix + " work label",
                        prefix + " work title",
                        prefix + " work introduction",
                        imageNotice,
                        prefix + " open slot label",
                        prefix + " open slot title",
                        prefix + " open slot text",
                        prefix + " open slot meta"),
                List.of(new PortfolioImportV1.ProjectCopy(
                        "project-one",
                        "01",
                        prefix + " project status",
                        prefix + " project eyebrow",
                        prefix + " project title",
                        projectSummary,
                        List.of(chinese ? "引擎" : "UE5"))),
                new PortfolioImportV1.Roadmap(
                        prefix + " roadmap label",
                        prefix + " roadmap title",
                        prefix + " roadmap introduction",
                        List.of(new PortfolioImportV1.RoadmapStage(
                                "stage-one",
                                "01",
                                prefix + " period",
                                prefix + " stage title",
                                prefix + " stage summary",
                                List.of(prefix + " outcome")))),
                new PortfolioImportV1.Contact(
                        prefix + " contact label",
                        prefix + " contact title",
                        prefix + " contact introduction",
                        emailLabel,
                        email,
                        prefix + " work CTA",
                        prefix + " roadmap CTA",
                        prefix + " footer note"));
    }
}
