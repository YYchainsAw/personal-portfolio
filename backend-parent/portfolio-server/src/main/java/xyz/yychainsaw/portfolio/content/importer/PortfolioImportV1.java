package xyz.yychainsaw.portfolio.content.importer;

import java.net.URI;
import java.util.List;
import java.util.Map;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;

public record PortfolioImportV1(
        int schemaVersion,
        Identity identity,
        HeroAsset heroAsset,
        List<ProjectAsset> projectAssets,
        Map<LocaleCode, PortfolioCopy> portfolioContent
) {
    public PortfolioImportV1 {
        projectAssets = List.copyOf(projectAssets);
        portfolioContent = Map.copyOf(portfolioContent);
    }

    public record Identity(String monogram, String nameZh, String nameEn, String email) {}

    public record HeroAsset(
            String image,
            String objectPosition,
            String credit,
            URI sourceUrl,
            Map<LocaleCode, String> alt) {
        public HeroAsset {
            alt = Map.copyOf(alt);
        }
    }

    public record ProjectAsset(
            String id,
            String image,
            String layout,
            String objectPosition,
            String credit,
            URI sourceUrl,
            Map<LocaleCode, String> alt) {
        public ProjectAsset {
            alt = Map.copyOf(alt);
        }
    }

    public record PortfolioCopy(
            Seo seo,
            A11y a11y,
            Nav nav,
            Hero hero,
            About about,
            Work work,
            List<ProjectCopy> projects,
            Roadmap roadmap,
            Contact contact) {
        public PortfolioCopy {
            projects = List.copyOf(projects);
        }
    }

    public record Seo(String title, String description) {}

    public record A11y(
            String skip,
            String primaryNav,
            String mobileNav,
            String openMenu,
            String closeMenu,
            String language,
            String backToTop,
            String projectTags) {}

    public record Nav(String about, String work, String roadmap, String contact) {}

    public record Hero(
            String eyebrow,
            String displayName,
            String secondaryName,
            String role,
            String headline,
            String introduction,
            String availability,
            String primaryCta,
            String secondaryCta,
            String visualLabel,
            String stageLabel) {}

    public record About(
            String label,
            String title,
            String statement,
            String focusLabel,
            String focusTitle,
            String focusIntro,
            List<Fact> facts,
            List<ProfileSkill> skills) {
        public About {
            facts = List.copyOf(facts);
            skills = List.copyOf(skills);
        }
    }

    public record Fact(String label, String value) {}

    public record ProfileSkill(String name, String status) {}

    public record Work(
            String label,
            String title,
            String introduction,
            String imageNotice,
            String openSlotLabel,
            String openSlotTitle,
            String openSlotText,
            String openSlotMeta) {}

    public record ProjectCopy(
            String id,
            String number,
            String status,
            String eyebrow,
            String title,
            String summary,
            List<String> tags) {
        public ProjectCopy {
            tags = List.copyOf(tags);
        }
    }

    public record Roadmap(
            String label,
            String title,
            String introduction,
            List<RoadmapStage> stages) {
        public Roadmap {
            stages = List.copyOf(stages);
        }
    }

    public record RoadmapStage(
            String id,
            String number,
            String period,
            String title,
            String summary,
            List<String> outcomes) {
        public RoadmapStage {
            outcomes = List.copyOf(outcomes);
        }
    }

    public record Contact(
            String label,
            String title,
            String introduction,
            String emailLabel,
            String email,
            String workCta,
            String roadmapCta,
            String footerNote) {}
}
