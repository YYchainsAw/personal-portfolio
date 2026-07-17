package xyz.yychainsaw.portfolio.publishing.snapshot.v1;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.content.api.SiteWorkspaceDto;
import xyz.yychainsaw.portfolio.media.application.MediaAssetDescriptor;
import xyz.yychainsaw.portfolio.media.application.MediaCopyDescriptor;
import xyz.yychainsaw.portfolio.media.application.MediaQueryService;
import xyz.yychainsaw.portfolio.media.application.MediaVariantDescriptor;
import xyz.yychainsaw.portfolio.publishing.snapshot.SiteSnapshotMapper;

@Component
public final class SiteSnapshotMapperV1 implements SiteSnapshotMapper {
    private static final int SCHEMA_VERSION = 1;
    private static final Comparator<UUID> ASSET_ORDER =
            Comparator.comparing(UUID::toString);

    private final MediaQueryService mediaQueryService;

    public SiteSnapshotMapperV1(MediaQueryService mediaQueryService) {
        this.mediaQueryService = Objects.requireNonNull(
                mediaQueryService, "mediaQueryService");
    }

    @Override
    public SiteSnapshotV1 toSnapshot(SiteWorkspaceDto workspace) {
        List<SiteWorkspaceDto.ResumeDocument> currentResumes = workspace.resumes().stream()
                .filter(SiteWorkspaceDto.ResumeDocument::current)
                .toList();
        Set<UUID> referencedAssets = new TreeSet<>(ASSET_ORDER);
        addAsset(referencedAssets, workspace.hero().mediaAssetId());
        currentResumes.forEach(resume -> addAsset(referencedAssets, resume.mediaAssetId()));
        List<PublishedMediaV1> media = referencedAssets.stream()
                .map(mediaQueryService::requireReadyAsset)
                .map(SiteSnapshotMapperV1::toPublishedMedia)
                .toList();

        return new SiteSnapshotV1(
                SCHEMA_VERSION,
                workspace.siteId(),
                new SiteContentV1(
                        workspace.monogram(),
                        workspace.email(),
                        toSnapshotLocales(
                                workspace.identity(),
                                copy -> new SiteContentV1.IdentityCopyV1(
                                        copy.displayName(), copy.secondaryName())),
                        toSnapshotLocales(
                                workspace.seo(),
                                copy -> new SiteContentV1.SeoCopyV1(
                                        copy.title(), copy.description())),
                        toSnapshotLocales(
                                workspace.accessibility(),
                                copy -> new SiteContentV1.AccessibilityCopyV1(
                                        copy.skip(),
                                        copy.primaryNav(),
                                        copy.mobileNav(),
                                        copy.openMenu(),
                                        copy.closeMenu(),
                                        copy.language(),
                                        copy.backToTop(),
                                        copy.projectTags())),
                        workspace.navigation().stream()
                                .map(SiteSnapshotMapperV1::toSnapshotNavigation)
                                .toList(),
                        toSnapshotHero(workspace.hero()),
                        toSnapshotLocales(
                                workspace.about(),
                                copy -> new SiteContentV1.AboutCopyV1(
                                        copy.label(),
                                        copy.title(),
                                        copy.statement(),
                                        copy.focusLabel(),
                                        copy.focusTitle(),
                                        copy.focusIntro())),
                        workspace.facts().stream()
                                .map(SiteSnapshotMapperV1::toSnapshotFact)
                                .toList(),
                        workspace.profileSkills().stream()
                                .map(SiteSnapshotMapperV1::toSnapshotSkill)
                                .toList(),
                        toSnapshotLocales(
                                workspace.work(),
                                copy -> new SiteContentV1.WorkCopyV1(
                                        copy.label(),
                                        copy.title(),
                                        copy.introduction(),
                                        copy.imageNotice(),
                                        copy.openSlotLabel(),
                                        copy.openSlotTitle(),
                                        copy.openSlotText(),
                                        copy.openSlotMeta())),
                        toSnapshotRoadmap(workspace.roadmap()),
                        toSnapshotLocales(
                                workspace.contact(),
                                copy -> new SiteContentV1.ContactCopyV1(
                                        copy.label(),
                                        copy.title(),
                                        copy.introduction(),
                                        copy.emailLabel(),
                                        copy.workCta(),
                                        copy.roadmapCta(),
                                        copy.footerNote())),
                        toSnapshotLocales(
                                workspace.privacy(),
                                copy -> new SiteContentV1.PrivacyCopyV1(
                                        copy.title(), copy.bodyMarkdown())),
                        workspace.socialLinks().stream()
                                .map(link -> new SiteContentV1.SocialLinkV1(
                                        link.id(),
                                        link.platform(),
                                        link.url(),
                                        link.sortOrder(),
                                        link.visible()))
                                .toList(),
                        currentResumes.stream()
                                .map(SiteSnapshotMapperV1::toSnapshotResume)
                                .toList()),
                media);
    }

    @Override
    public SiteWorkspaceDto restore(
            SiteSnapshotV1 snapshot, SiteWorkspaceDto currentWorkspace) {
        SiteContentV1 content = snapshot.content();
        return new SiteWorkspaceDto(
                snapshot.siteId(),
                currentWorkspace.version(),
                content.monogram(),
                content.email(),
                toWorkspaceLocales(
                        content.identity(),
                        copy -> new SiteWorkspaceDto.IdentityCopy(
                                copy.displayName(), copy.secondaryName())),
                toWorkspaceLocales(
                        content.seo(),
                        copy -> new SiteWorkspaceDto.SeoCopy(
                                copy.title(), copy.description())),
                toWorkspaceLocales(
                        content.accessibility(),
                        copy -> new SiteWorkspaceDto.AccessibilityCopy(
                                copy.skip(),
                                copy.primaryNav(),
                                copy.mobileNav(),
                                copy.openMenu(),
                                copy.closeMenu(),
                                copy.language(),
                                copy.backToTop(),
                                copy.projectTags())),
                content.navigation().stream()
                        .map(SiteSnapshotMapperV1::toWorkspaceNavigation)
                        .toList(),
                toWorkspaceHero(content.hero(), currentWorkspace.hero().version()),
                toWorkspaceLocales(
                        content.about(),
                        copy -> new SiteWorkspaceDto.AboutCopy(
                                copy.label(),
                                copy.title(),
                                copy.statement(),
                                copy.focusLabel(),
                                copy.focusTitle(),
                                copy.focusIntro())),
                content.facts().stream()
                        .map(SiteSnapshotMapperV1::toWorkspaceFact)
                        .toList(),
                content.profileSkills().stream()
                        .map(SiteSnapshotMapperV1::toWorkspaceSkill)
                        .toList(),
                toWorkspaceLocales(
                        content.work(),
                        copy -> new SiteWorkspaceDto.WorkCopy(
                                copy.label(),
                                copy.title(),
                                copy.introduction(),
                                copy.imageNotice(),
                                copy.openSlotLabel(),
                                copy.openSlotTitle(),
                                copy.openSlotText(),
                                copy.openSlotMeta())),
                toWorkspaceRoadmap(content.roadmap()),
                toWorkspaceLocales(
                        content.contact(),
                        copy -> new SiteWorkspaceDto.ContactCopy(
                                copy.label(),
                                copy.title(),
                                copy.introduction(),
                                copy.emailLabel(),
                                copy.workCta(),
                                copy.roadmapCta(),
                                copy.footerNote())),
                toWorkspaceLocales(
                        content.privacy(),
                        copy -> new SiteWorkspaceDto.PrivacyCopy(
                                copy.title(), copy.bodyMarkdown())),
                content.socialLinks().stream()
                        .map(link -> new SiteWorkspaceDto.SocialLink(
                                link.id(),
                                link.platform(),
                                link.url(),
                                link.sortOrder(),
                                link.visible()))
                        .toList(),
                content.resumes().stream()
                        .map(SiteSnapshotMapperV1::toWorkspaceResume)
                        .toList());
    }

    private static SiteContentV1.NavigationItemV1 toSnapshotNavigation(
            SiteWorkspaceDto.NavigationItem item) {
        return new SiteContentV1.NavigationItemV1(
                item.id(),
                item.target(),
                item.sortOrder(),
                item.visible(),
                toSnapshotLocales(item.labels(), Function.identity()));
    }

    private static SiteWorkspaceDto.NavigationItem toWorkspaceNavigation(
            SiteContentV1.NavigationItemV1 item) {
        return new SiteWorkspaceDto.NavigationItem(
                item.id(),
                item.target(),
                item.sortOrder(),
                item.visible(),
                toWorkspaceLocales(item.labels(), Function.identity()));
    }

    private static SiteContentV1.HeroV1 toSnapshotHero(SiteWorkspaceDto.Hero hero) {
        return new SiteContentV1.HeroV1(
                hero.id(),
                hero.mediaAssetId(),
                hero.objectPosition(),
                hero.credit(),
                hero.sourceUrl(),
                toSnapshotLocales(
                        hero.copy(),
                        copy -> new SiteContentV1.HeroCopyV1(
                                copy.eyebrow(),
                                copy.displayName(),
                                copy.secondaryName(),
                                copy.role(),
                                copy.headline(),
                                copy.introduction(),
                                copy.availability(),
                                copy.primaryCta(),
                                copy.secondaryCta(),
                                copy.visualLabel(),
                                copy.stageLabel())));
    }

    private static SiteWorkspaceDto.Hero toWorkspaceHero(
            SiteContentV1.HeroV1 hero, long currentHeroVersion) {
        return new SiteWorkspaceDto.Hero(
                hero.id(),
                currentHeroVersion,
                hero.mediaAssetId(),
                hero.objectPosition(),
                hero.credit(),
                hero.sourceUrl(),
                toWorkspaceLocales(
                        hero.copy(),
                        copy -> new SiteWorkspaceDto.HeroCopy(
                                copy.eyebrow(),
                                copy.displayName(),
                                copy.secondaryName(),
                                copy.role(),
                                copy.headline(),
                                copy.introduction(),
                                copy.availability(),
                                copy.primaryCta(),
                                copy.secondaryCta(),
                                copy.visualLabel(),
                                copy.stageLabel())));
    }

    private static SiteContentV1.ProfileFactV1 toSnapshotFact(
            SiteWorkspaceDto.ProfileFact fact) {
        return new SiteContentV1.ProfileFactV1(
                fact.id(),
                fact.externalKey(),
                fact.sortOrder(),
                toSnapshotLocales(
                        fact.copy(),
                        copy -> new SiteContentV1.LabelValueCopyV1(
                                copy.label(), copy.value())));
    }

    private static SiteWorkspaceDto.ProfileFact toWorkspaceFact(
            SiteContentV1.ProfileFactV1 fact) {
        return new SiteWorkspaceDto.ProfileFact(
                fact.id(),
                fact.externalKey(),
                fact.sortOrder(),
                toWorkspaceLocales(
                        fact.copy(),
                        copy -> new SiteWorkspaceDto.LabelValueCopy(
                                copy.label(), copy.value())));
    }

    private static SiteContentV1.ProfileSkillV1 toSnapshotSkill(
            SiteWorkspaceDto.ProfileSkill skill) {
        return new SiteContentV1.ProfileSkillV1(
                skill.id(),
                skill.externalKey(),
                skill.sortOrder(),
                toSnapshotLocales(
                        skill.copy(),
                        copy -> new SiteContentV1.SkillStatusCopyV1(
                                copy.name(), copy.status())));
    }

    private static SiteWorkspaceDto.ProfileSkill toWorkspaceSkill(
            SiteContentV1.ProfileSkillV1 skill) {
        return new SiteWorkspaceDto.ProfileSkill(
                skill.id(),
                skill.externalKey(),
                skill.sortOrder(),
                toWorkspaceLocales(
                        skill.copy(),
                        copy -> new SiteWorkspaceDto.SkillStatusCopy(
                                copy.name(), copy.status())));
    }

    private static SiteContentV1.RoadmapV1 toSnapshotRoadmap(
            SiteWorkspaceDto.Roadmap roadmap) {
        return new SiteContentV1.RoadmapV1(
                toSnapshotLocales(
                        roadmap.header(),
                        copy -> new SiteContentV1.RoadmapHeaderCopyV1(
                                copy.label(), copy.title(), copy.introduction())),
                roadmap.stages().stream()
                        .map(SiteSnapshotMapperV1::toSnapshotRoadmapStage)
                        .toList());
    }

    private static SiteWorkspaceDto.Roadmap toWorkspaceRoadmap(
            SiteContentV1.RoadmapV1 roadmap) {
        return new SiteWorkspaceDto.Roadmap(
                toWorkspaceLocales(
                        roadmap.header(),
                        copy -> new SiteWorkspaceDto.RoadmapHeaderCopy(
                                copy.label(), copy.title(), copy.introduction())),
                roadmap.stages().stream()
                        .map(SiteSnapshotMapperV1::toWorkspaceRoadmapStage)
                        .toList());
    }

    private static SiteContentV1.RoadmapStageV1 toSnapshotRoadmapStage(
            SiteWorkspaceDto.RoadmapStage stage) {
        return new SiteContentV1.RoadmapStageV1(
                stage.id(),
                stage.externalKey(),
                stage.number(),
                stage.sortOrder(),
                stage.visible(),
                toSnapshotLocales(
                        stage.copy(),
                        copy -> new SiteContentV1.RoadmapStageCopyV1(
                                copy.period(), copy.title(), copy.summary())),
                stage.outcomes().stream()
                        .map(outcome -> new SiteContentV1.RoadmapOutcomeV1(
                                outcome.id(),
                                outcome.sortOrder(),
                                toSnapshotLocales(outcome.text(), Function.identity())))
                        .toList());
    }

    private static SiteWorkspaceDto.RoadmapStage toWorkspaceRoadmapStage(
            SiteContentV1.RoadmapStageV1 stage) {
        return new SiteWorkspaceDto.RoadmapStage(
                stage.id(),
                stage.externalKey(),
                stage.number(),
                stage.sortOrder(),
                stage.visible(),
                toWorkspaceLocales(
                        stage.copy(),
                        copy -> new SiteWorkspaceDto.RoadmapStageCopy(
                                copy.period(), copy.title(), copy.summary())),
                stage.outcomes().stream()
                        .map(outcome -> new SiteWorkspaceDto.RoadmapOutcome(
                                outcome.id(),
                                outcome.sortOrder(),
                                toWorkspaceLocales(outcome.text(), Function.identity())))
                        .toList());
    }

    private static SiteContentV1.ResumeDocumentV1 toSnapshotResume(
            SiteWorkspaceDto.ResumeDocument resume) {
        return new SiteContentV1.ResumeDocumentV1(
                resume.id(),
                LocaleV1.from(resume.locale().value()),
                resume.mediaAssetId(),
                resume.versionLabel(),
                resume.current(),
                resume.documentDate());
    }

    private static SiteWorkspaceDto.ResumeDocument toWorkspaceResume(
            SiteContentV1.ResumeDocumentV1 resume) {
        return new SiteWorkspaceDto.ResumeDocument(
                resume.id(),
                LocaleCode.from(resume.locale().value()),
                resume.mediaAssetId(),
                resume.versionLabel(),
                resume.current(),
                resume.documentDate());
    }

    private static void addAsset(Set<UUID> referencedAssets, UUID assetId) {
        if (assetId != null) {
            referencedAssets.add(assetId);
        }
    }

    private static PublishedMediaV1 toPublishedMedia(MediaAssetDescriptor descriptor) {
        Map<LocaleV1, PublishedMediaV1.MediaCopy> copy = new EnumMap<>(LocaleV1.class);
        descriptor.copyByLocale().forEach((locale, value) ->
                copy.put(LocaleV1.from(locale), toPublishedMediaCopy(value)));
        List<PublishedMediaV1.Variant> variants = descriptor.variants().stream()
                .sorted(Comparator.comparing(MediaVariantDescriptor::variantName))
                .map(variant -> new PublishedMediaV1.Variant(
                        variant.variantName(),
                        variant.width(),
                        variant.height(),
                        variant.byteSize(),
                        variant.sha256()))
                .toList();
        return new PublishedMediaV1(
                descriptor.assetId(),
                descriptor.mimeType(),
                descriptor.byteSize(),
                descriptor.sha256(),
                copy,
                variants);
    }

    private static PublishedMediaV1.MediaCopy toPublishedMediaCopy(
            MediaCopyDescriptor copy) {
        return new PublishedMediaV1.MediaCopy(
                copy.alt(), copy.caption(), copy.credit(), copy.sourceUrl());
    }

    private static <T, R> Map<LocaleV1, R> toSnapshotLocales(
            Map<LocaleCode, T> source, Function<T, R> mapper) {
        Map<LocaleV1, R> result = new EnumMap<>(LocaleV1.class);
        source.forEach((locale, value) ->
                result.put(LocaleV1.from(locale.value()), mapper.apply(value)));
        return result;
    }

    private static <T, R> Map<LocaleCode, R> toWorkspaceLocales(
            Map<LocaleV1, T> source, Function<T, R> mapper) {
        Map<LocaleCode, R> result = new EnumMap<>(LocaleCode.class);
        source.forEach((locale, value) ->
                result.put(LocaleCode.from(locale.value()), mapper.apply(value)));
        return result;
    }
}
