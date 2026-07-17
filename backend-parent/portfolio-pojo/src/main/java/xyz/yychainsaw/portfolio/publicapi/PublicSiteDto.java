package xyz.yychainsaw.portfolio.publicapi;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record PublicSiteDto(
        Identity identity,
        Seo seo,
        Accessibility accessibility,
        List<NavigationItem> navigation,
        Hero hero,
        About about,
        Work work,
        Roadmap roadmap,
        Contact contact,
        Privacy privacy,
        List<SocialLink> socialLinks,
        Resume resume) {
    public PublicSiteDto {
        navigation = List.copyOf(navigation);
        socialLinks = List.copyOf(socialLinks);
        Objects.requireNonNull(resume, "resume");
    }

    public record Identity(
            String monogram,
            String displayName,
            String secondaryName,
            String email) {
    }

    public record Seo(String title, String description) {
    }

    public record Accessibility(
            String skip,
            String primaryNav,
            String mobileNav,
            String openMenu,
            String closeMenu,
            String language,
            String backToTop,
            String projectTags) {
    }

    public record NavigationItem(String target, int sortOrder, String label) {
    }

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
            String stageLabel,
            String objectPosition,
            String credit,
            String sourceUrl,
            PublicMediaDto media) {
    }

    public record About(
            String label,
            String title,
            String statement,
            String focusLabel,
            String focusTitle,
            String focusIntro,
            List<Fact> facts,
            List<Skill> skills) {
        public About {
            facts = List.copyOf(facts);
            skills = List.copyOf(skills);
        }
    }

    public record Fact(String label, String value) {
    }

    public record Skill(String name, String status) {
    }

    public record Work(
            String label,
            String title,
            String introduction,
            String imageNotice,
            String openSlotLabel,
            String openSlotTitle,
            String openSlotText,
            String openSlotMeta) {
    }

    public record Roadmap(String label, String title, String introduction, List<RoadmapStage> stages) {
        public Roadmap {
            stages = List.copyOf(stages);
        }
    }

    public record RoadmapStage(
            UUID id,
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
            String footerNote) {
    }

    public record Privacy(String title, String html) {
    }

    public record SocialLink(String platform, String url) {
    }

    public record Resume(String label, LocalDate documentDate, String href) {
    }
}
