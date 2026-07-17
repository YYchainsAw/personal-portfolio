package xyz.yychainsaw.portfolio.content.api;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record SiteWorkspaceDto(
        UUID siteId,
        long version,
        String monogram,
        String email,
        Map<LocaleCode, IdentityCopy> identity,
        Map<LocaleCode, SeoCopy> seo,
        Map<LocaleCode, AccessibilityCopy> accessibility,
        List<NavigationItem> navigation,
        Hero hero,
        Map<LocaleCode, AboutCopy> about,
        List<ProfileFact> facts,
        List<ProfileSkill> profileSkills,
        Map<LocaleCode, WorkCopy> work,
        Roadmap roadmap,
        Map<LocaleCode, ContactCopy> contact,
        Map<LocaleCode, PrivacyCopy> privacy,
        List<SocialLink> socialLinks,
        List<ResumeDocument> resumes
) {
    public static final UUID SITE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    public SiteWorkspaceDto {
        identity = Map.copyOf(identity);
        seo = Map.copyOf(seo);
        accessibility = Map.copyOf(accessibility);
        navigation = List.copyOf(navigation);
        about = Map.copyOf(about);
        facts = List.copyOf(facts);
        profileSkills = List.copyOf(profileSkills);
        work = Map.copyOf(work);
        contact = Map.copyOf(contact);
        privacy = Map.copyOf(privacy);
        socialLinks = List.copyOf(socialLinks);
        resumes = List.copyOf(resumes);
    }

    public record IdentityCopy(String displayName, String secondaryName) {}

    public record SeoCopy(String title, String description) {}

    public record AccessibilityCopy(
            String skip,
            String primaryNav,
            String mobileNav,
            String openMenu,
            String closeMenu,
            String language,
            String backToTop,
            String projectTags) {}

    public record NavigationItem(
            UUID id,
            String target,
            int sortOrder,
            boolean visible,
            Map<LocaleCode, String> labels) {
        public NavigationItem {
            labels = Map.copyOf(labels);
        }
    }

    public record Hero(
            UUID id,
            long version,
            UUID mediaAssetId,
            String objectPosition,
            String credit,
            URI sourceUrl,
            Map<LocaleCode, HeroCopy> copy) {
        public Hero {
            copy = Map.copyOf(copy);
        }
    }

    public record HeroCopy(
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

    public record AboutCopy(
            String label,
            String title,
            String statement,
            String focusLabel,
            String focusTitle,
            String focusIntro) {}

    public record ProfileFact(
            UUID id,
            String externalKey,
            int sortOrder,
            Map<LocaleCode, LabelValueCopy> copy) {
        public ProfileFact {
            copy = Map.copyOf(copy);
        }
    }

    public record ProfileSkill(
            UUID id,
            String externalKey,
            int sortOrder,
            Map<LocaleCode, SkillStatusCopy> copy) {
        public ProfileSkill {
            copy = Map.copyOf(copy);
        }
    }

    public record LabelValueCopy(String label, String value) {}

    public record SkillStatusCopy(String name, String status) {}

    public record WorkCopy(
            String label,
            String title,
            String introduction,
            String imageNotice,
            String openSlotLabel,
            String openSlotTitle,
            String openSlotText,
            String openSlotMeta) {}

    public record Roadmap(
            Map<LocaleCode, RoadmapHeaderCopy> header,
            List<RoadmapStage> stages) {
        public Roadmap {
            header = Map.copyOf(header);
            stages = List.copyOf(stages);
        }
    }

    public record RoadmapHeaderCopy(String label, String title, String introduction) {}

    public record RoadmapStage(
            UUID id,
            String externalKey,
            String number,
            int sortOrder,
            boolean visible,
            Map<LocaleCode, RoadmapStageCopy> copy,
            List<RoadmapOutcome> outcomes) {
        public RoadmapStage {
            copy = Map.copyOf(copy);
            outcomes = List.copyOf(outcomes);
        }
    }

    public record RoadmapStageCopy(String period, String title, String summary) {}

    public record RoadmapOutcome(
            UUID id,
            int sortOrder,
            Map<LocaleCode, String> text) {
        public RoadmapOutcome {
            text = Map.copyOf(text);
        }
    }

    public record ContactCopy(
            String label,
            String title,
            String introduction,
            String emailLabel,
            String workCta,
            String roadmapCta,
            String footerNote) {}

    public record PrivacyCopy(String title, String bodyMarkdown) {}

    public record SocialLink(
            UUID id,
            String platform,
            URI url,
            int sortOrder,
            boolean visible) {}

    public record ResumeDocument(
            UUID id,
            LocaleCode locale,
            UUID mediaAssetId,
            String versionLabel,
            boolean current,
            LocalDate documentDate) {}
}
