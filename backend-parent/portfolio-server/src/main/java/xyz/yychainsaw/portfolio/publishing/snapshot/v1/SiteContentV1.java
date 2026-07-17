package xyz.yychainsaw.portfolio.publishing.snapshot.v1;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record SiteContentV1(
        String monogram,
        String email,
        Map<LocaleV1, IdentityCopyV1> identity,
        Map<LocaleV1, SeoCopyV1> seo,
        Map<LocaleV1, AccessibilityCopyV1> accessibility,
        List<NavigationItemV1> navigation,
        HeroV1 hero,
        Map<LocaleV1, AboutCopyV1> about,
        List<ProfileFactV1> facts,
        List<ProfileSkillV1> profileSkills,
        Map<LocaleV1, WorkCopyV1> work,
        RoadmapV1 roadmap,
        Map<LocaleV1, ContactCopyV1> contact,
        Map<LocaleV1, PrivacyCopyV1> privacy,
        List<SocialLinkV1> socialLinks,
        List<ResumeDocumentV1> resumes) {
    public SiteContentV1 {
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

    public record IdentityCopyV1(String displayName, String secondaryName) {}

    public record SeoCopyV1(String title, String description) {}

    public record AccessibilityCopyV1(
            String skip,
            String primaryNav,
            String mobileNav,
            String openMenu,
            String closeMenu,
            String language,
            String backToTop,
            String projectTags) {}

    public record NavigationItemV1(
            UUID id,
            String target,
            int sortOrder,
            boolean visible,
            Map<LocaleV1, String> labels) {
        public NavigationItemV1 {
            labels = Map.copyOf(labels);
        }
    }

    public record HeroV1(
            UUID id,
            UUID mediaAssetId,
            String objectPosition,
            String credit,
            URI sourceUrl,
            Map<LocaleV1, HeroCopyV1> copy) {
        public HeroV1 {
            copy = Map.copyOf(copy);
        }
    }

    public record HeroCopyV1(
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

    public record AboutCopyV1(
            String label,
            String title,
            String statement,
            String focusLabel,
            String focusTitle,
            String focusIntro) {}

    public record ProfileFactV1(
            UUID id,
            String externalKey,
            int sortOrder,
            Map<LocaleV1, LabelValueCopyV1> copy) {
        public ProfileFactV1 {
            copy = Map.copyOf(copy);
        }
    }

    public record ProfileSkillV1(
            UUID id,
            String externalKey,
            int sortOrder,
            Map<LocaleV1, SkillStatusCopyV1> copy) {
        public ProfileSkillV1 {
            copy = Map.copyOf(copy);
        }
    }

    public record LabelValueCopyV1(String label, String value) {}

    public record SkillStatusCopyV1(String name, String status) {}

    public record WorkCopyV1(
            String label,
            String title,
            String introduction,
            String imageNotice,
            String openSlotLabel,
            String openSlotTitle,
            String openSlotText,
            String openSlotMeta) {}

    public record RoadmapV1(
            Map<LocaleV1, RoadmapHeaderCopyV1> header,
            List<RoadmapStageV1> stages) {
        public RoadmapV1 {
            header = Map.copyOf(header);
            stages = List.copyOf(stages);
        }
    }

    public record RoadmapHeaderCopyV1(String label, String title, String introduction) {}

    public record RoadmapStageV1(
            UUID id,
            String externalKey,
            String number,
            int sortOrder,
            boolean visible,
            Map<LocaleV1, RoadmapStageCopyV1> copy,
            List<RoadmapOutcomeV1> outcomes) {
        public RoadmapStageV1 {
            copy = Map.copyOf(copy);
            outcomes = List.copyOf(outcomes);
        }
    }

    public record RoadmapStageCopyV1(String period, String title, String summary) {}

    public record RoadmapOutcomeV1(
            UUID id,
            int sortOrder,
            Map<LocaleV1, String> text) {
        public RoadmapOutcomeV1 {
            text = Map.copyOf(text);
        }
    }

    public record ContactCopyV1(
            String label,
            String title,
            String introduction,
            String emailLabel,
            String workCta,
            String roadmapCta,
            String footerNote) {}

    public record PrivacyCopyV1(String title, String bodyMarkdown) {}

    public record SocialLinkV1(
            UUID id,
            String platform,
            URI url,
            int sortOrder,
            boolean visible) {}

    public record ResumeDocumentV1(
            UUID id,
            LocaleV1 locale,
            UUID mediaAssetId,
            String versionLabel,
            boolean current,
            LocalDate documentDate) {}
}
