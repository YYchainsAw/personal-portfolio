package xyz.yychainsaw.portfolio.content.importer;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.content.api.ProjectWorkspaceDto;
import xyz.yychainsaw.portfolio.content.api.SiteWorkspaceDto;
import xyz.yychainsaw.portfolio.media.application.MediaAssetDescriptor;
import xyz.yychainsaw.portfolio.media.application.MediaVariantDescriptor;

@Component
@Conditional(PortfolioImportRuntimeCondition.class)
public final class PortfolioImportMapper {
    private static final List<String> NAVIGATION_TARGETS =
            List.of("about", "work", "roadmap", "contact");

    public MappedImport map(
            PortfolioImportV1 payload,
            Map<String, ReadyMedia> mediaByPublicPath) {
        Objects.requireNonNull(payload, "portfolio import payload is required");
        Objects.requireNonNull(mediaByPublicPath, "media bindings are required");

        PortfolioImportV1.PortfolioCopy english = copy(payload, LocaleCode.EN);
        PortfolioImportV1.PortfolioCopy chinese = copy(payload, LocaleCode.ZH_CN);
        LinkedHashMap<String, ProjectWorkspaceDto.TaxonomyRef> globalTags =
                new LinkedHashMap<>();
        List<ProjectWorkspaceDto> projects = new ArrayList<>();
        Map<String, PortfolioImportV1.ProjectAsset> projectAssets = new LinkedHashMap<>();
        for (PortfolioImportV1.ProjectAsset asset : payload.projectAssets()) {
            projectAssets.put(asset.id(), asset);
        }

        for (int projectIndex = 0; projectIndex < english.projects().size(); projectIndex++) {
            PortfolioImportV1.ProjectCopy enProject = english.projects().get(projectIndex);
            PortfolioImportV1.ProjectCopy zhProject = chinese.projects().get(projectIndex);
            PortfolioImportV1.ProjectAsset sourceAsset = Objects.requireNonNull(
                    projectAssets.get(enProject.id()), "project media binding is missing");
            ReadyMedia media = requireMedia(mediaByPublicPath, sourceAsset.image());

            List<ProjectWorkspaceDto.TaxonomyRef> localTags = new ArrayList<>();
            for (int tagIndex = 0; tagIndex < enProject.tags().size(); tagIndex++) {
                String key = PortfolioImportSemantics.tagKey(
                        enProject.id(), tagIndex, enProject.tags().get(tagIndex));
                UUID tagId = PortfolioImportSemantics.stableId("tag", key);
                Map<LocaleCode, String> names = localized(
                        zhProject.tags().get(tagIndex), enProject.tags().get(tagIndex));
                ProjectWorkspaceDto.TaxonomyRef global = globalTags.computeIfAbsent(
                        key,
                        ignored -> new ProjectWorkspaceDto.TaxonomyRef(
                                tagId, key, globalTags.size(), names));
                localTags.add(new ProjectWorkspaceDto.TaxonomyRef(
                        global.id(), global.normalizedKey(), tagIndex, global.names()));
            }

            projects.add(new ProjectWorkspaceDto(
                    PortfolioImportSemantics.stableId("project", enProject.id()),
                    enProject.id(),
                    enProject.id(),
                    enProject.number(),
                    projectIndex,
                    false,
                    true,
                    true,
                    0,
                    localized(
                            projectCopy(zhProject),
                            projectCopy(enProject)),
                    localTags,
                    List.of(),
                    List.of(new ProjectWorkspaceDto.ProjectMedia(
                            media.asset().assetId(),
                            "COVER",
                            0,
                            sourceAsset.layout(),
                            sourceAsset.objectPosition(),
                            sourceAsset.credit(),
                            sourceAsset.sourceUrl())),
                    List.of()));
        }

        PortfolioImportSemantics.Counts counts = PortfolioImportSemantics.counts(payload);
        return new MappedImport(
                site(payload, mediaByPublicPath, chinese, english),
                projects,
                List.copyOf(globalTags.values()),
                counts.mediaCount());
    }

    private SiteWorkspaceDto site(
            PortfolioImportV1 payload,
            Map<String, ReadyMedia> mediaByPublicPath,
            PortfolioImportV1.PortfolioCopy chinese,
            PortfolioImportV1.PortfolioCopy english) {
        ReadyMedia heroMedia = requireMedia(
                mediaByPublicPath, payload.heroAsset().image());
        return new SiteWorkspaceDto(
                SiteWorkspaceDto.SITE_ID,
                1,
                payload.identity().monogram(),
                payload.identity().email(),
                localized(
                        new SiteWorkspaceDto.IdentityCopy(
                                payload.identity().nameZh(),
                                payload.identity().nameEn()),
                        new SiteWorkspaceDto.IdentityCopy(
                                payload.identity().nameEn(),
                                payload.identity().nameZh())),
                localized(seo(chinese.seo()), seo(english.seo())),
                localized(
                        accessibility(chinese.a11y()),
                        accessibility(english.a11y())),
                navigation(chinese.nav(), english.nav()),
                new SiteWorkspaceDto.Hero(
                        PortfolioImportSemantics.stableId(
                                "hero", SiteWorkspaceDto.SITE_ID.toString()),
                        0,
                        heroMedia.asset().assetId(),
                        payload.heroAsset().objectPosition(),
                        payload.heroAsset().credit(),
                        payload.heroAsset().sourceUrl(),
                        localized(heroCopy(chinese.hero()), heroCopy(english.hero()))),
                localized(about(chinese.about()), about(english.about())),
                facts(chinese.about().facts(), english.about().facts()),
                profileSkills(chinese.about().skills(), english.about().skills()),
                localized(work(chinese.work()), work(english.work())),
                roadmap(chinese.roadmap(), english.roadmap()),
                localized(contact(chinese.contact()), contact(english.contact())),
                localized(
                        new SiteWorkspaceDto.PrivacyCopy("", ""),
                        new SiteWorkspaceDto.PrivacyCopy("", "")),
                List.of(),
                List.of());
    }

    private static List<SiteWorkspaceDto.NavigationItem> navigation(
            PortfolioImportV1.Nav chinese,
            PortfolioImportV1.Nav english) {
        List<String> zhLabels = List.of(
                chinese.about(), chinese.work(), chinese.roadmap(), chinese.contact());
        List<String> enLabels = List.of(
                english.about(), english.work(), english.roadmap(), english.contact());
        List<SiteWorkspaceDto.NavigationItem> result = new ArrayList<>();
        for (int index = 0; index < NAVIGATION_TARGETS.size(); index++) {
            String target = NAVIGATION_TARGETS.get(index);
            result.add(new SiteWorkspaceDto.NavigationItem(
                    PortfolioImportSemantics.stableId("navigation", target),
                    target,
                    index,
                    true,
                    localized(zhLabels.get(index), enLabels.get(index))));
        }
        return result;
    }

    private static List<SiteWorkspaceDto.ProfileFact> facts(
            List<PortfolioImportV1.Fact> chinese,
            List<PortfolioImportV1.Fact> english) {
        List<SiteWorkspaceDto.ProfileFact> result = new ArrayList<>();
        for (int index = 0; index < english.size(); index++) {
            String externalKey = "fact-" + index;
            result.add(new SiteWorkspaceDto.ProfileFact(
                    PortfolioImportSemantics.stableId("profile-fact", externalKey),
                    externalKey,
                    index,
                    localized(
                            fact(chinese.get(index)),
                            fact(english.get(index)))));
        }
        return result;
    }

    private static List<SiteWorkspaceDto.ProfileSkill> profileSkills(
            List<PortfolioImportV1.ProfileSkill> chinese,
            List<PortfolioImportV1.ProfileSkill> english) {
        List<SiteWorkspaceDto.ProfileSkill> result = new ArrayList<>();
        for (int index = 0; index < english.size(); index++) {
            String externalKey = "skill-" + index;
            result.add(new SiteWorkspaceDto.ProfileSkill(
                    PortfolioImportSemantics.stableId("profile-skill", externalKey),
                    externalKey,
                    index,
                    localized(
                            profileSkill(chinese.get(index)),
                            profileSkill(english.get(index)))));
        }
        return result;
    }

    private static SiteWorkspaceDto.Roadmap roadmap(
            PortfolioImportV1.Roadmap chinese,
            PortfolioImportV1.Roadmap english) {
        List<SiteWorkspaceDto.RoadmapStage> stages = new ArrayList<>();
        for (int stageIndex = 0; stageIndex < english.stages().size(); stageIndex++) {
            PortfolioImportV1.RoadmapStage enStage = english.stages().get(stageIndex);
            PortfolioImportV1.RoadmapStage zhStage = chinese.stages().get(stageIndex);
            List<SiteWorkspaceDto.RoadmapOutcome> outcomes = new ArrayList<>();
            for (int outcomeIndex = 0;
                    outcomeIndex < enStage.outcomes().size();
                    outcomeIndex++) {
                outcomes.add(new SiteWorkspaceDto.RoadmapOutcome(
                        PortfolioImportSemantics.stableId(
                                "roadmap-outcome",
                                enStage.id() + ":" + outcomeIndex),
                        outcomeIndex,
                        localized(
                                zhStage.outcomes().get(outcomeIndex),
                                enStage.outcomes().get(outcomeIndex))));
            }
            stages.add(new SiteWorkspaceDto.RoadmapStage(
                    PortfolioImportSemantics.stableId("roadmap-stage", enStage.id()),
                    enStage.id(),
                    enStage.number(),
                    stageIndex,
                    true,
                    localized(stage(zhStage), stage(enStage)),
                    outcomes));
        }
        return new SiteWorkspaceDto.Roadmap(
                localized(
                        roadmapHeader(chinese),
                        roadmapHeader(english)),
                stages);
    }

    private static SiteWorkspaceDto.SeoCopy seo(PortfolioImportV1.Seo value) {
        return new SiteWorkspaceDto.SeoCopy(value.title(), value.description());
    }

    private static SiteWorkspaceDto.AccessibilityCopy accessibility(
            PortfolioImportV1.A11y value) {
        return new SiteWorkspaceDto.AccessibilityCopy(
                value.skip(),
                value.primaryNav(),
                value.mobileNav(),
                value.openMenu(),
                value.closeMenu(),
                value.language(),
                value.backToTop(),
                value.projectTags());
    }

    private static SiteWorkspaceDto.HeroCopy heroCopy(PortfolioImportV1.Hero value) {
        return new SiteWorkspaceDto.HeroCopy(
                value.eyebrow(),
                value.displayName(),
                value.secondaryName(),
                value.role(),
                value.headline(),
                value.introduction(),
                value.availability(),
                value.primaryCta(),
                value.secondaryCta(),
                value.visualLabel(),
                value.stageLabel());
    }

    private static SiteWorkspaceDto.AboutCopy about(PortfolioImportV1.About value) {
        return new SiteWorkspaceDto.AboutCopy(
                value.label(),
                value.title(),
                value.statement(),
                value.focusLabel(),
                value.focusTitle(),
                value.focusIntro());
    }

    private static SiteWorkspaceDto.LabelValueCopy fact(PortfolioImportV1.Fact value) {
        return new SiteWorkspaceDto.LabelValueCopy(value.label(), value.value());
    }

    private static SiteWorkspaceDto.SkillStatusCopy profileSkill(
            PortfolioImportV1.ProfileSkill value) {
        return new SiteWorkspaceDto.SkillStatusCopy(value.name(), value.status());
    }

    private static SiteWorkspaceDto.WorkCopy work(PortfolioImportV1.Work value) {
        return new SiteWorkspaceDto.WorkCopy(
                value.label(),
                value.title(),
                value.introduction(),
                value.imageNotice(),
                value.openSlotLabel(),
                value.openSlotTitle(),
                value.openSlotText(),
                value.openSlotMeta());
    }

    private static SiteWorkspaceDto.RoadmapHeaderCopy roadmapHeader(
            PortfolioImportV1.Roadmap value) {
        return new SiteWorkspaceDto.RoadmapHeaderCopy(
                value.label(), value.title(), value.introduction());
    }

    private static SiteWorkspaceDto.RoadmapStageCopy stage(
            PortfolioImportV1.RoadmapStage value) {
        return new SiteWorkspaceDto.RoadmapStageCopy(
                value.period(), value.title(), value.summary());
    }

    private static SiteWorkspaceDto.ContactCopy contact(PortfolioImportV1.Contact value) {
        return new SiteWorkspaceDto.ContactCopy(
                value.label(),
                value.title(),
                value.introduction(),
                value.emailLabel(),
                value.workCta(),
                value.roadmapCta(),
                value.footerNote());
    }

    private static ProjectWorkspaceDto.ProjectCopy projectCopy(
            PortfolioImportV1.ProjectCopy value) {
        return new ProjectWorkspaceDto.ProjectCopy(
                value.status(),
                value.eyebrow(),
                value.title(),
                value.summary(),
                value.title(),
                value.summary());
    }

    private static PortfolioImportV1.PortfolioCopy copy(
            PortfolioImportV1 payload, LocaleCode locale) {
        return Objects.requireNonNull(
                payload.portfolioContent().get(locale),
                "localized portfolio copy is missing");
    }

    private static ReadyMedia requireMedia(
            Map<String, ReadyMedia> mediaByPublicPath,
            String publicPath) {
        return Objects.requireNonNull(
                mediaByPublicPath.get(publicPath),
                "media binding is missing for " + publicPath);
    }

    private static <T> Map<LocaleCode, T> localized(T chinese, T english) {
        EnumMap<LocaleCode, T> result = new EnumMap<>(LocaleCode.class);
        result.put(LocaleCode.ZH_CN, chinese);
        result.put(LocaleCode.EN, english);
        return result;
    }

    public record ReadyMedia(
            MediaAssetDescriptor asset,
            List<MediaVariantDescriptor> variants) {
        public ReadyMedia {
            Objects.requireNonNull(asset, "media asset descriptor is required");
            variants = List.copyOf(variants);
        }
    }

    public record MappedImport(
            SiteWorkspaceDto site,
            List<ProjectWorkspaceDto> projects,
            List<ProjectWorkspaceDto.TaxonomyRef> tags,
            int mediaCount) {
        public MappedImport {
            Objects.requireNonNull(site, "site workspace is required");
            projects = List.copyOf(projects);
            tags = List.copyOf(tags);
        }

        public int tagCount() {
            return tags.size();
        }
    }
}
