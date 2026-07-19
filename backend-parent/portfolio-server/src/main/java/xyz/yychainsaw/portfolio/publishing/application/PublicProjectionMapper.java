package xyz.yychainsaw.portfolio.publishing.application;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.publicapi.PublicBlockDto;
import xyz.yychainsaw.portfolio.publicapi.PublicMediaDto;
import xyz.yychainsaw.portfolio.publicapi.PublicProjectCardDto;
import xyz.yychainsaw.portfolio.publicapi.PublicProjectDto;
import xyz.yychainsaw.portfolio.publicapi.PublicSiteDto;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.LocaleV1;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.ProjectCatalogSnapshotV1;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.ProjectSnapshotV1;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.PublishedBlockV1;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.PublishedMediaV1;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.SiteContentV1;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.SiteSnapshotV1;

@Component
public class PublicProjectionMapper {
    private static final String SITE_ERROR = "SITE_NOT_PUBLISHABLE";
    private static final String PROJECT_ERROR = "PROJECT_NOT_PUBLISHABLE";
    private static final String CATALOG_ERROR = "PROJECT_CATALOG_NOT_PUBLISHABLE";
    private static final Pattern IMAGE_VARIANT_NAME = Pattern.compile("w[1-9][0-9]{0,9}");
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{3,64}");
    private static final Pattern VIMEO_ID = Pattern.compile("[0-9]{3,20}");
    private static final Pattern BILIBILI_ID = Pattern.compile("[A-Za-z0-9]{6,32}");

    private final SafeMarkdownRenderer markdown;

    public PublicProjectionMapper(SafeMarkdownRenderer markdown) {
        this.markdown = Objects.requireNonNull(markdown, "markdown");
    }

    public PublicSiteDto site(SiteSnapshotV1 snapshot, LocaleCode locale) {
        SiteSnapshotV1 source = required(snapshot, SITE_ERROR, "snapshot");
        LocaleV1 selected = locale(locale, SITE_ERROR);
        SiteContentV1 content = required(source.content(), SITE_ERROR, "content");
        Map<UUID, PublishedMediaV1> media = mediaIndex(source.media(), SITE_ERROR, "media");

        SiteContentV1.IdentityCopyV1 identity = localized(
                content.identity(), selected, SITE_ERROR, "content.identity");
        SiteContentV1.SeoCopyV1 seo = localized(
                content.seo(), selected, SITE_ERROR, "content.seo");
        SiteContentV1.AccessibilityCopyV1 accessibility = localized(
                content.accessibility(), selected, SITE_ERROR, "content.accessibility");
        SiteContentV1.HeroV1 hero = required(content.hero(), SITE_ERROR, "content.hero");
        SiteContentV1.HeroCopyV1 heroCopy = localized(
                hero.copy(), selected, SITE_ERROR, "content.hero.copy");
        SiteContentV1.AboutCopyV1 about = localized(
                content.about(), selected, SITE_ERROR, "content.about");
        SiteContentV1.WorkCopyV1 work = localized(
                content.work(), selected, SITE_ERROR, "content.work");
        SiteContentV1.RoadmapV1 roadmap = required(
                content.roadmap(), SITE_ERROR, "content.roadmap");
        SiteContentV1.RoadmapHeaderCopyV1 roadmapHeader = localized(
                roadmap.header(), selected, SITE_ERROR, "content.roadmap.header");
        SiteContentV1.ContactCopyV1 contact = localized(
                content.contact(), selected, SITE_ERROR, "content.contact");
        SiteContentV1.PrivacyCopyV1 privacy = localized(
                content.privacy(), selected, SITE_ERROR, "content.privacy");

        return new PublicSiteDto(
                new PublicSiteDto.Identity(
                        text(content.monogram(), SITE_ERROR, "content.monogram"),
                        text(identity.displayName(), SITE_ERROR, "content.identity.displayName"),
                        text(identity.secondaryName(), SITE_ERROR, "content.identity.secondaryName"),
                        text(content.email(), SITE_ERROR, "content.email")),
                new PublicSiteDto.Seo(
                        text(seo.title(), SITE_ERROR, "content.seo.title"),
                        text(seo.description(), SITE_ERROR, "content.seo.description")),
                new PublicSiteDto.Accessibility(
                        text(accessibility.skip(), SITE_ERROR, "content.accessibility.skip"),
                        text(accessibility.primaryNav(), SITE_ERROR,
                                "content.accessibility.primaryNav"),
                        text(accessibility.mobileNav(), SITE_ERROR,
                                "content.accessibility.mobileNav"),
                        text(accessibility.openMenu(), SITE_ERROR,
                                "content.accessibility.openMenu"),
                        text(accessibility.closeMenu(), SITE_ERROR,
                                "content.accessibility.closeMenu"),
                        text(accessibility.language(), SITE_ERROR,
                                "content.accessibility.language"),
                        text(accessibility.backToTop(), SITE_ERROR,
                                "content.accessibility.backToTop"),
                        text(accessibility.projectTags(), SITE_ERROR,
                                "content.accessibility.projectTags")),
                navigation(content.navigation(), selected),
                new PublicSiteDto.Hero(
                        text(heroCopy.eyebrow(), SITE_ERROR, "content.hero.eyebrow"),
                        text(heroCopy.displayName(), SITE_ERROR, "content.hero.displayName"),
                        text(heroCopy.secondaryName(), SITE_ERROR,
                                "content.hero.secondaryName"),
                        text(heroCopy.role(), SITE_ERROR, "content.hero.role"),
                        text(heroCopy.headline(), SITE_ERROR, "content.hero.headline"),
                        text(heroCopy.introduction(), SITE_ERROR,
                                "content.hero.introduction"),
                        text(heroCopy.availability(), SITE_ERROR,
                                "content.hero.availability"),
                        text(heroCopy.primaryCta(), SITE_ERROR, "content.hero.primaryCta"),
                        text(heroCopy.secondaryCta(), SITE_ERROR,
                                "content.hero.secondaryCta"),
                        text(heroCopy.visualLabel(), SITE_ERROR,
                                "content.hero.visualLabel"),
                        text(heroCopy.stageLabel(), SITE_ERROR,
                                "content.hero.stageLabel"),
                        optionalText(hero.objectPosition()),
                        optionalText(hero.credit()),
                        optionalHttps(hero.sourceUrl(), SITE_ERROR, "content.hero.sourceUrl"),
                        hero.mediaAssetId() == null
                                ? null
                                : visualMedia(requireMedia(
                                                media, hero.mediaAssetId(), SITE_ERROR,
                                                "content.hero.mediaAssetId"),
                                        selected,
                                        SITE_ERROR,
                                        "content.hero.media")),
                new PublicSiteDto.About(
                        text(about.label(), SITE_ERROR, "content.about.label"),
                        text(about.title(), SITE_ERROR, "content.about.title"),
                        text(about.statement(), SITE_ERROR, "content.about.statement"),
                        text(about.focusLabel(), SITE_ERROR, "content.about.focusLabel"),
                        text(about.focusTitle(), SITE_ERROR, "content.about.focusTitle"),
                        text(about.focusIntro(), SITE_ERROR, "content.about.focusIntro"),
                        facts(content.facts(), selected),
                        skills(content.profileSkills(), selected)),
                new PublicSiteDto.Work(
                        text(work.label(), SITE_ERROR, "content.work.label"),
                        text(work.title(), SITE_ERROR, "content.work.title"),
                        text(work.introduction(), SITE_ERROR, "content.work.introduction"),
                        text(work.imageNotice(), SITE_ERROR, "content.work.imageNotice"),
                        text(work.openSlotLabel(), SITE_ERROR,
                                "content.work.openSlotLabel"),
                        text(work.openSlotTitle(), SITE_ERROR,
                                "content.work.openSlotTitle"),
                        text(work.openSlotText(), SITE_ERROR,
                                "content.work.openSlotText"),
                        text(work.openSlotMeta(), SITE_ERROR,
                                "content.work.openSlotMeta")),
                new PublicSiteDto.Roadmap(
                        text(roadmapHeader.label(), SITE_ERROR,
                                "content.roadmap.header.label"),
                        text(roadmapHeader.title(), SITE_ERROR,
                                "content.roadmap.header.title"),
                        text(roadmapHeader.introduction(), SITE_ERROR,
                                "content.roadmap.header.introduction"),
                        roadmapStages(roadmap.stages(), selected)),
                new PublicSiteDto.Contact(
                        text(contact.label(), SITE_ERROR, "content.contact.label"),
                        text(contact.title(), SITE_ERROR, "content.contact.title"),
                        text(contact.introduction(), SITE_ERROR,
                                "content.contact.introduction"),
                        text(contact.emailLabel(), SITE_ERROR,
                                "content.contact.emailLabel"),
                        text(content.email(), SITE_ERROR, "content.email"),
                        text(contact.workCta(), SITE_ERROR, "content.contact.workCta"),
                        text(contact.roadmapCta(), SITE_ERROR,
                                "content.contact.roadmapCta"),
                        text(contact.footerNote(), SITE_ERROR,
                                "content.contact.footerNote")),
                new PublicSiteDto.Privacy(
                        text(privacy.title(), SITE_ERROR, "content.privacy.title"),
                        markdown.render(text(
                                privacy.bodyMarkdown(), SITE_ERROR,
                                "content.privacy.bodyMarkdown"))),
                socialLinks(content.socialLinks()),
                resume(content.resumes(), media, selected));
    }

    public List<PublicProjectCardDto> catalog(
            ProjectCatalogSnapshotV1 snapshot, LocaleCode locale) {
        ProjectCatalogSnapshotV1 source = required(snapshot, CATALOG_ERROR, "snapshot");
        LocaleV1 selected = locale(locale, CATALOG_ERROR);
        List<ProjectCatalogSnapshotV1.Card> cards = required(
                source.projects(), CATALOG_ERROR, "projects");
        return cards.stream()
                .map(card -> required(card, CATALOG_ERROR, "projects[]"))
                .sorted(Comparator.comparingInt(ProjectCatalogSnapshotV1.Card::sortOrder)
                        .thenComparing(card -> uuidText(card.projectId())))
                .map(card -> projectCard(card, selected))
                .toList();
    }

    public PublicProjectDto project(ProjectSnapshotV1 snapshot, LocaleCode locale) {
        ProjectSnapshotV1 source = required(snapshot, PROJECT_ERROR, "snapshot");
        LocaleV1 selected = locale(locale, PROJECT_ERROR);
        ProjectSnapshotV1.ProjectCopyV1 copy = localized(
                source.translations(), selected, PROJECT_ERROR, "translations");
        Map<UUID, PublishedMediaV1> media = mediaIndex(
                source.media(), PROJECT_ERROR, "media");

        List<String> tags = source.tags().stream()
                .map(tag -> required(tag, PROJECT_ERROR, "tags[]"))
                .sorted(Comparator.comparingInt(ProjectSnapshotV1.TaxonomyRefV1::sortOrder)
                        .thenComparing(tag -> uuidText(tag.id())))
                .map(tag -> text(localized(
                                tag.names(), selected, PROJECT_ERROR,
                                "tags." + uuidText(tag.id()) + ".names"),
                        PROJECT_ERROR,
                        "tags." + uuidText(tag.id()) + ".name"))
                .toList();
        List<String> skills = source.skills().stream()
                .map(skill -> required(skill, PROJECT_ERROR, "skills[]"))
                .sorted(Comparator.comparingInt(ProjectSnapshotV1.TaxonomyRefV1::sortOrder)
                        .thenComparing(skill -> uuidText(skill.id())))
                .map(skill -> text(localized(
                                skill.names(), selected, PROJECT_ERROR,
                                "skills." + uuidText(skill.id()) + ".names"),
                        PROJECT_ERROR,
                        "skills." + uuidText(skill.id()) + ".name"))
                .toList();
        List<PublicMediaDto> projectMedia = source.projectMedia().stream()
                .map(item -> required(item, PROJECT_ERROR, "projectMedia[]"))
                .sorted(Comparator.comparingInt(ProjectSnapshotV1.ProjectMediaV1::sortOrder)
                        .thenComparing(item -> uuidText(item.assetId())))
                .map(item -> visualMedia(requireMedia(
                                media,
                                item.assetId(),
                                PROJECT_ERROR,
                                "projectMedia." + uuidText(item.assetId())),
                        selected,
                        PROJECT_ERROR,
                        "projectMedia." + uuidText(item.assetId())))
                .toList();
        List<PublicBlockDto> blocks = source.blocks().stream()
                .map(block -> required(block, PROJECT_ERROR, "blocks[]"))
                .filter(PublishedBlockV1::visible)
                .sorted(Comparator.comparingInt(PublishedBlockV1::sortOrder)
                        .thenComparing(block -> uuidText(block.id())))
                .map(block -> block(block, selected, media))
                .toList();

        return new PublicProjectDto(
                required(source.projectId(), PROJECT_ERROR, "projectId"),
                text(source.slug(), PROJECT_ERROR, "slug"),
                text(source.number(), PROJECT_ERROR, "number"),
                source.featured(),
                text(copy.status(), PROJECT_ERROR, "translations.status"),
                text(copy.eyebrow(), PROJECT_ERROR, "translations.eyebrow"),
                text(copy.title(), PROJECT_ERROR, "translations.title"),
                text(copy.summary(), PROJECT_ERROR, "translations.summary"),
                text(copy.seoTitle(), PROJECT_ERROR, "translations.seoTitle"),
                text(copy.seoDescription(), PROJECT_ERROR,
                        "translations.seoDescription"),
                tags,
                skills,
                projectMedia,
                blocks);
    }

    /**
     * Validates externally navigable block targets without requiring complete
     * localized copy, so draft previews share the public URL policy.
     */
    public void validateProjectSafetyTargets(ProjectSnapshotV1 snapshot) {
        ProjectSnapshotV1 source = required(snapshot, PROJECT_ERROR, "snapshot");
        List<PublishedBlockV1> blocks = required(
                source.blocks(), PROJECT_ERROR, "blocks");
        for (PublishedBlockV1 candidate : blocks) {
            PublishedBlockV1 block = required(candidate, PROJECT_ERROR, "blocks[]");
            if (!block.visible()) {
                continue;
            }
            String path = "blocks." + uuidText(block.id()) + ".payload";
            PublishedBlockV1.PayloadV1 payload = required(
                    block.payload(), PROJECT_ERROR, path);
            if (payload instanceof PublishedBlockV1.VideoPayloadV1 video) {
                canonicalVideo(video.provider(), video.url(), path + ".url");
            } else if (payload instanceof PublishedBlockV1.DownloadPayloadV1 download
                    && download.externalUrl() != null) {
                https(download.externalUrl(), PROJECT_ERROR, path + ".externalUrl");
            } else if (payload instanceof PublishedBlockV1.LinkPayloadV1 link) {
                https(link.url(), PROJECT_ERROR, path + ".url");
            }
        }
    }

    private PublicProjectCardDto projectCard(
            ProjectCatalogSnapshotV1.Card card, LocaleV1 locale) {
        UUID projectId = required(card.projectId(), CATALOG_ERROR, "projects.projectId");
        String path = "projects." + projectId;
        ProjectCatalogSnapshotV1.CardCopy copy = localized(
                card.copy(), locale, CATALOG_ERROR, path + ".copy");
        PublishedMediaV1 cover = required(card.cover(), CATALOG_ERROR, path + ".cover");
        return new PublicProjectCardDto(
                projectId,
                text(card.slug(), CATALOG_ERROR, path + ".slug"),
                text(card.number(), CATALOG_ERROR, path + ".number"),
                card.sortOrder(),
                card.featured(),
                text(copy.status(), CATALOG_ERROR, path + ".status"),
                text(copy.eyebrow(), CATALOG_ERROR, path + ".eyebrow"),
                text(copy.title(), CATALOG_ERROR, path + ".title"),
                text(copy.summary(), CATALOG_ERROR, path + ".summary"),
                copy.tags().stream()
                        .map(tag -> text(tag, CATALOG_ERROR, path + ".tags"))
                        .toList(),
                visualMedia(cover, locale, CATALOG_ERROR, path + ".cover"));
    }

    private List<PublicSiteDto.NavigationItem> navigation(
            List<SiteContentV1.NavigationItemV1> source, LocaleV1 locale) {
        return source.stream()
                .map(item -> required(item, SITE_ERROR, "content.navigation[]"))
                .filter(SiteContentV1.NavigationItemV1::visible)
                .sorted(Comparator.comparingInt(SiteContentV1.NavigationItemV1::sortOrder)
                        .thenComparing(item -> uuidText(item.id())))
                .map(item -> new PublicSiteDto.NavigationItem(
                        text(item.target(), SITE_ERROR, "content.navigation.target"),
                        item.sortOrder(),
                        text(localized(
                                        item.labels(), locale, SITE_ERROR,
                                        "content.navigation." + uuidText(item.id()) + ".labels"),
                                SITE_ERROR,
                                "content.navigation." + uuidText(item.id()) + ".label")))
                .toList();
    }

    private List<PublicSiteDto.Fact> facts(
            List<SiteContentV1.ProfileFactV1> source, LocaleV1 locale) {
        return source.stream()
                .map(fact -> required(fact, SITE_ERROR, "content.facts[]"))
                .sorted(Comparator.comparingInt(SiteContentV1.ProfileFactV1::sortOrder)
                        .thenComparing(fact -> uuidText(fact.id())))
                .map(fact -> {
                    SiteContentV1.LabelValueCopyV1 copy = localized(
                            fact.copy(), locale, SITE_ERROR,
                            "content.facts." + uuidText(fact.id()) + ".copy");
                    return new PublicSiteDto.Fact(
                            text(copy.label(), SITE_ERROR, "content.facts.label"),
                            text(copy.value(), SITE_ERROR, "content.facts.value"));
                })
                .toList();
    }

    private List<PublicSiteDto.Skill> skills(
            List<SiteContentV1.ProfileSkillV1> source, LocaleV1 locale) {
        return source.stream()
                .map(skill -> required(skill, SITE_ERROR, "content.profileSkills[]"))
                .sorted(Comparator.comparingInt(SiteContentV1.ProfileSkillV1::sortOrder)
                        .thenComparing(skill -> uuidText(skill.id())))
                .map(skill -> {
                    SiteContentV1.SkillStatusCopyV1 copy = localized(
                            skill.copy(), locale, SITE_ERROR,
                            "content.profileSkills." + uuidText(skill.id()) + ".copy");
                    return new PublicSiteDto.Skill(
                            text(copy.name(), SITE_ERROR, "content.profileSkills.name"),
                            text(copy.status(), SITE_ERROR, "content.profileSkills.status"));
                })
                .toList();
    }

    private List<PublicSiteDto.RoadmapStage> roadmapStages(
            List<SiteContentV1.RoadmapStageV1> source, LocaleV1 locale) {
        return source.stream()
                .map(stage -> required(stage, SITE_ERROR, "content.roadmap.stages[]"))
                .filter(SiteContentV1.RoadmapStageV1::visible)
                .sorted(Comparator.comparingInt(SiteContentV1.RoadmapStageV1::sortOrder)
                        .thenComparing(stage -> uuidText(stage.id())))
                .map(stage -> {
                    String path = "content.roadmap.stages." + uuidText(stage.id());
                    SiteContentV1.RoadmapStageCopyV1 copy = localized(
                            stage.copy(), locale, SITE_ERROR, path + ".copy");
                    List<String> outcomes = stage.outcomes().stream()
                            .map(outcome -> required(
                                    outcome, SITE_ERROR, path + ".outcomes[]"))
                            .sorted(Comparator.comparingInt(
                                            SiteContentV1.RoadmapOutcomeV1::sortOrder)
                                    .thenComparing(outcome -> uuidText(outcome.id())))
                            .map(outcome -> text(localized(
                                            outcome.text(), locale, SITE_ERROR,
                                            path + ".outcomes."
                                                    + uuidText(outcome.id()) + ".text"),
                                    SITE_ERROR,
                                    path + ".outcomes." + uuidText(outcome.id())))
                            .toList();
                    return new PublicSiteDto.RoadmapStage(
                            required(stage.id(), SITE_ERROR, path + ".id"),
                            text(stage.number(), SITE_ERROR, path + ".number"),
                            text(copy.period(), SITE_ERROR, path + ".period"),
                            text(copy.title(), SITE_ERROR, path + ".title"),
                            text(copy.summary(), SITE_ERROR, path + ".summary"),
                            outcomes);
                })
                .toList();
    }

    private List<PublicSiteDto.SocialLink> socialLinks(
            List<SiteContentV1.SocialLinkV1> source) {
        return source.stream()
                .map(link -> required(link, SITE_ERROR, "content.socialLinks[]"))
                .filter(SiteContentV1.SocialLinkV1::visible)
                .sorted(Comparator.comparingInt(SiteContentV1.SocialLinkV1::sortOrder)
                        .thenComparing(link -> uuidText(link.id())))
                .map(link -> new PublicSiteDto.SocialLink(
                        text(link.platform(), SITE_ERROR, "content.socialLinks.platform"),
                        https(link.url(), SITE_ERROR, "content.socialLinks.url")))
                .toList();
    }

    private PublicSiteDto.Resume resume(
            List<SiteContentV1.ResumeDocumentV1> source,
            Map<UUID, PublishedMediaV1> media,
            LocaleV1 locale) {
        if (source.isEmpty()) {
            return null;
        }
        List<SiteContentV1.ResumeDocumentV1> current = source.stream()
                .map(resume -> required(resume, SITE_ERROR, "content.resumes[]"))
                .filter(SiteContentV1.ResumeDocumentV1::current)
                .filter(resume -> resume.locale() == locale)
                .toList();
        if (current.size() != 1) {
            throw invalid(
                    SITE_ERROR,
                    "content.resumes." + locale.value() + ".current",
                    "exactly one current resume is required");
        }
        SiteContentV1.ResumeDocumentV1 resume = current.get(0);
        PublishedMediaV1 document = requireMedia(
                media, resume.mediaAssetId(), SITE_ERROR, "content.resumes.mediaAssetId");
        PublishedMediaV1.Variant variant = documentVariant(
                document, SITE_ERROR, "content.resumes.media");
        return new PublicSiteDto.Resume(
                text(resume.versionLabel(), SITE_ERROR, "content.resumes.versionLabel"),
                required(resume.documentDate(), SITE_ERROR, "content.resumes.documentDate"),
                mediaPath(document.assetId(), variant.name()));
    }

    private PublicBlockDto block(
            PublishedBlockV1 source,
            LocaleV1 locale,
            Map<UUID, PublishedMediaV1> media) {
        String path = "blocks." + uuidText(source.id());
        BlockPayload projected = payload(source.payload(), locale, media, path + ".payload");
        return new PublicBlockDto(
                required(source.id(), PROJECT_ERROR, path + ".id"),
                projected.type(),
                source.sortOrder(),
                required(source.width(), PROJECT_ERROR, path + ".width").name(),
                required(source.alignment(), PROJECT_ERROR, path + ".alignment").name(),
                required(source.emphasis(), PROJECT_ERROR, path + ".emphasis").name(),
                source.columns(),
                projected.payload());
    }

    private BlockPayload payload(
            PublishedBlockV1.PayloadV1 payload,
            LocaleV1 locale,
            Map<UUID, PublishedMediaV1> media,
            String path) {
        PublishedBlockV1.PayloadV1 source = required(payload, PROJECT_ERROR, path);
        if (source instanceof PublishedBlockV1.MarkdownPayloadV1 value) {
            String localized = localized(
                    value.markdown(), locale, PROJECT_ERROR, path + ".markdown");
            return new BlockPayload(
                    "MARKDOWN",
                    new PublicBlockDto.Markdown(markdown.render(text(
                            localized, PROJECT_ERROR, path + ".markdown"))));
        }
        if (source instanceof PublishedBlockV1.ImagePayloadV1 value) {
            return new BlockPayload(
                    "IMAGE",
                    new PublicBlockDto.Image(visualMedia(
                            requireMedia(
                                    media, value.mediaAssetId(), PROJECT_ERROR,
                                    path + ".mediaAssetId"),
                            locale,
                            PROJECT_ERROR,
                            path + ".media")));
        }
        if (source instanceof PublishedBlockV1.GalleryPayloadV1 value) {
            List<PublicMediaDto> items = value.mediaAssetIds().stream()
                    .map(assetId -> visualMedia(requireMedia(
                                    media, assetId, PROJECT_ERROR,
                                    path + ".mediaAssetIds." + uuidText(assetId)),
                            locale,
                            PROJECT_ERROR,
                            path + ".media." + uuidText(assetId)))
                    .toList();
            return new BlockPayload("GALLERY", new PublicBlockDto.Gallery(items));
        }
        if (source instanceof PublishedBlockV1.VideoPayloadV1 value) {
            VideoTarget target = canonicalVideo(value.provider(), value.url(), path + ".url");
            PublishedBlockV1.BlockCopyV1 copy = localized(
                    value.copy(), locale, PROJECT_ERROR, path + ".copy");
            PublicMediaDto cover = value.coverAssetId() == null
                    ? null
                    : visualMedia(requireMedia(
                                    media, value.coverAssetId(), PROJECT_ERROR,
                                    path + ".coverAssetId"),
                            locale,
                            PROJECT_ERROR,
                            path + ".cover");
            return new BlockPayload(
                    "VIDEO",
                    new PublicBlockDto.Video(
                            target.provider(),
                            target.embedUrl(),
                            cover,
                            text(copy.title(), PROJECT_ERROR, path + ".title"),
                            text(copy.description(), PROJECT_ERROR, path + ".description")));
        }
        if (source instanceof PublishedBlockV1.CodePayloadV1 value) {
            PublishedBlockV1.BlockCopyV1 copy = localized(
                    value.copy(), locale, PROJECT_ERROR, path + ".copy");
            return new BlockPayload(
                    "CODE",
                    new PublicBlockDto.Code(
                            text(value.code(), PROJECT_ERROR, path + ".code"),
                            text(value.language(), PROJECT_ERROR, path + ".language"),
                            value.showLineNumbers(),
                            text(copy.title(), PROJECT_ERROR, path + ".title"),
                            text(copy.description(), PROJECT_ERROR, path + ".description")));
        }
        if (source instanceof PublishedBlockV1.QuotePayloadV1 value) {
            PublishedBlockV1.QuoteCopyV1 copy = localized(
                    value.copy(), locale, PROJECT_ERROR, path + ".copy");
            return new BlockPayload(
                    "QUOTE",
                    new PublicBlockDto.Quote(
                            text(copy.quote(), PROJECT_ERROR, path + ".quote"),
                            text(copy.source(), PROJECT_ERROR, path + ".source")));
        }
        if (source instanceof PublishedBlockV1.MetricsPayloadV1 value) {
            List<PublicBlockDto.Metric> metrics = value.metrics().stream()
                    .map(metric -> required(metric, PROJECT_ERROR, path + ".metrics[]"))
                    .sorted(Comparator.comparingInt(PublishedBlockV1.MetricV1::sortOrder)
                            .thenComparing(metric -> uuidText(metric.id())))
                    .map(metric -> {
                        PublishedBlockV1.MetricCopyV1 copy = localized(
                                metric.copy(), locale, PROJECT_ERROR,
                                path + ".metrics." + uuidText(metric.id()) + ".copy");
                        return new PublicBlockDto.Metric(
                                required(metric.id(), PROJECT_ERROR, path + ".metrics.id"),
                                required(metric.numericValue(), PROJECT_ERROR,
                                        path + ".metrics.numericValue"),
                                text(copy.label(), PROJECT_ERROR, path + ".metrics.label"),
                                text(copy.value(), PROJECT_ERROR, path + ".metrics.value"),
                                text(copy.suffix(), PROJECT_ERROR, path + ".metrics.suffix"));
                    })
                    .toList();
            return new BlockPayload("METRICS", new PublicBlockDto.Metrics(metrics));
        }
        if (source instanceof PublishedBlockV1.DownloadPayloadV1 value) {
            PublishedBlockV1.ActionCopyV1 copy = localized(
                    value.copy(), locale, PROJECT_ERROR, path + ".copy");
            boolean mediaBacked = value.mediaAssetId() != null;
            boolean external = value.externalUrl() != null;
            if (mediaBacked == external) {
                throw invalid(
                        PROJECT_ERROR,
                        path,
                        "exactly one download target is required");
            }
            if (mediaBacked) {
                PublishedMediaV1 document = requireMedia(
                        media, value.mediaAssetId(), PROJECT_ERROR, path + ".mediaAssetId");
                PublishedMediaV1.Variant variant = downloadVariant(
                        document, PROJECT_ERROR, path + ".media");
                if (document.contentLength() < 0) {
                    throw invalid(PROJECT_ERROR, path + ".byteSize", "must be nonnegative");
                }
                return new BlockPayload(
                        "DOWNLOAD",
                        new PublicBlockDto.Download(
                                mediaPath(document.assetId(), variant.name()),
                                text(copy.label(), PROJECT_ERROR, path + ".label"),
                                text(copy.description(), PROJECT_ERROR, path + ".description"),
                                text(document.contentType(), PROJECT_ERROR, path + ".mimeType"),
                                document.contentLength()));
            }
            return new BlockPayload(
                    "DOWNLOAD",
                    new PublicBlockDto.Download(
                            https(value.externalUrl(), PROJECT_ERROR, path + ".externalUrl"),
                            text(copy.label(), PROJECT_ERROR, path + ".label"),
                            text(copy.description(), PROJECT_ERROR, path + ".description"),
                            null,
                            null));
        }
        if (source instanceof PublishedBlockV1.LinkPayloadV1 value) {
            PublishedBlockV1.ActionCopyV1 copy = localized(
                    value.copy(), locale, PROJECT_ERROR, path + ".copy");
            return new BlockPayload(
                    "LINK",
                    new PublicBlockDto.Link(
                            https(value.url(), PROJECT_ERROR, path + ".url"),
                            value.openNewTab(),
                            text(copy.label(), PROJECT_ERROR, path + ".label"),
                            text(copy.description(), PROJECT_ERROR, path + ".description")));
        }
        throw invalid(PROJECT_ERROR, path, "unsupported block payload");
    }

    private static PublicMediaDto visualMedia(
            PublishedMediaV1 media,
            LocaleV1 locale,
            String code,
            String path) {
        UUID assetId = required(media.assetId(), code, path + ".assetId");
        PublishedMediaV1.MediaCopy copy = localized(
                media.copy(), locale, code, path + ".copy");
        String alt = required(copy.alt(), code, path + ".copy.alt");
        String sourceUrl = optionalHttps(copy.sourceUrl(), code, path + ".copy.sourceUrl");

        List<PublishedMediaV1.Variant> variants = checkedVariants(
                media, code, path, true);
        PublishedMediaV1.Variant primary = variants.get(variants.size() - 1);
        String srcset = variants.stream()
                .map(variant -> mediaPath(assetId, variant.name()) + ' ' + variant.width() + "w")
                .reduce((left, right) -> left + ", " + right)
                .orElseThrow();
        return new PublicMediaDto(
                assetId,
                primary.name(),
                mediaPath(assetId, primary.name()),
                srcset,
                alt,
                copy.caption(),
                copy.credit(),
                sourceUrl,
                primary.width(),
                primary.height());
    }

    private static PublishedMediaV1.Variant downloadVariant(
            PublishedMediaV1 media, String code, String path) {
        List<PublishedMediaV1.Variant> variants = checkedVariants(media, code, path, false);
        return variants.stream()
                .filter(variant -> "document".equals(variant.name()))
                .findFirst()
                .orElse(variants.get(variants.size() - 1));
    }

    private static PublishedMediaV1.Variant documentVariant(
            PublishedMediaV1 media, String code, String path) {
        return checkedVariants(media, code, path, false).stream()
                .filter(variant -> "document".equals(variant.name()))
                .findFirst()
                .orElseThrow(() -> invalid(
                        code, path + ".variants.document", "document variant is required"));
    }

    private static List<PublishedMediaV1.Variant> checkedVariants(
            PublishedMediaV1 media,
            String code,
            String path,
            boolean requireDimensions) {
        List<PublishedMediaV1.Variant> source = required(
                media.variants(), code, path + ".variants");
        if (source.isEmpty()) {
            throw invalid(code, path + ".variants", "at least one variant is required");
        }
        List<PublishedMediaV1.Variant> variants = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Set<Integer> imageWidths = new HashSet<>();
        for (int index = 0; index < source.size(); index++) {
            PublishedMediaV1.Variant variant = required(
                    source.get(index), code, path + ".variants[" + index + "]");
            String name = text(variant.name(), code, path + ".variants[" + index + "].name");
            if (!names.add(name)) {
                throw invalid(code, path + ".variants." + name, "variant name is duplicated");
            }
            if (variant.bytes() < 0) {
                throw invalid(code, path + ".variants." + name + ".bytes", "must be nonnegative");
            }
            boolean document = "document".equals(name);
            boolean image = IMAGE_VARIANT_NAME.matcher(name).matches();
            if (!document && !image) {
                throw invalid(code, path + ".variants." + name, "variant name is invalid");
            }
            if (document && (requireDimensions || variant.width() != 0 || variant.height() != 0)) {
                throw invalid(
                        code,
                        path + ".variants." + name + ".dimensions",
                        "document dimensions must be zero");
            }
            if (image && (variant.width() <= 0
                    || variant.height() <= 0
                    || !name.equals("w" + variant.width()))) {
                throw invalid(
                        code,
                        path + ".variants." + name + ".dimensions",
                        "image variant name and dimensions must agree");
            }
            if (image && !imageWidths.add(variant.width())) {
                throw invalid(
                        code,
                        path + ".variants." + name + ".width",
                        "image variant width is duplicated");
            }
            variants.add(variant);
        }
        variants.sort(Comparator.comparingInt(PublishedMediaV1.Variant::width)
                .thenComparing(PublishedMediaV1.Variant::name));
        return List.copyOf(variants);
    }

    private static Map<UUID, PublishedMediaV1> mediaIndex(
            List<PublishedMediaV1> source, String code, String path) {
        Map<UUID, PublishedMediaV1> result = new LinkedHashMap<>();
        for (int index = 0; index < required(source, code, path).size(); index++) {
            PublishedMediaV1 media = required(
                    source.get(index), code, path + '[' + index + ']');
            UUID assetId = required(media.assetId(), code, path + '[' + index + "].assetId");
            if (result.putIfAbsent(assetId, media) != null) {
                throw invalid(code, path + '.' + assetId, "media asset is duplicated");
            }
        }
        return Map.copyOf(result);
    }

    private static PublishedMediaV1 requireMedia(
            Map<UUID, PublishedMediaV1> media,
            UUID assetId,
            String code,
            String path) {
        UUID requiredId = required(assetId, code, path);
        PublishedMediaV1 value = media.get(requiredId);
        if (value == null) {
            throw invalid(code, path, "referenced media is absent from the snapshot");
        }
        return value;
    }

    private static VideoTarget canonicalVideo(String provider, URI source, String path) {
        String requestedProvider = text(provider, PROJECT_ERROR, path + ".provider")
                .toLowerCase(Locale.ROOT);
        URI uri = safeHttps(source, PROJECT_ERROR, path);
        if (uri.getRawFragment() != null) {
            throw invalid(PROJECT_ERROR, path, "video fragments are not allowed");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String canonicalProvider;
        String id;
        String embed;
        if (host.equals("youtu.be")) {
            canonicalProvider = "youtube";
            requireNoQuery(uri, path);
            id = singlePathSegment(uri.getRawPath());
            requireVideoId(id, VIDEO_ID, path);
            embed = "https://www.youtube.com/embed/" + id;
        } else if (host.equals("youtube.com")
                || host.equals("www.youtube.com")) {
            canonicalProvider = "youtube";
            if ("/watch".equals(uri.getRawPath())) {
                id = onlyQueryParameter(uri.getRawQuery(), "v");
            } else if (uri.getRawPath() != null
                    && (uri.getRawPath().startsWith("/embed/")
                            || uri.getRawPath().startsWith("/shorts/"))) {
                requireNoQuery(uri, path);
                id = singlePathSegment(uri.getRawPath().substring(
                        uri.getRawPath().indexOf('/', 1)));
            } else {
                id = null;
            }
            requireVideoId(id, VIDEO_ID, path);
            embed = "https://www.youtube.com/embed/" + id;
        } else if (host.equals("vimeo.com") || host.equals("www.vimeo.com")) {
            canonicalProvider = "vimeo";
            requireNoQuery(uri, path);
            id = singlePathSegment(uri.getRawPath());
            requireVideoId(id, VIMEO_ID, path);
            embed = "https://player.vimeo.com/video/" + id;
        } else if (host.equals("bilibili.com") || host.equals("www.bilibili.com")) {
            canonicalProvider = "bilibili";
            requireNoQuery(uri, path);
            String rawPath = uri.getRawPath();
            id = rawPath != null && rawPath.startsWith("/video/")
                    ? singlePathSegment(rawPath.substring("/video".length()))
                    : null;
            requireVideoId(id, BILIBILI_ID, path);
            embed = "https://player.bilibili.com/player.html?bvid=" + id;
        } else if (host.equals("player.bilibili.com")) {
            canonicalProvider = "bilibili";
            if (!"/player.html".equals(uri.getRawPath())) {
                throw invalid(PROJECT_ERROR, path, "unsupported Bilibili player path");
            }
            id = onlyQueryParameter(uri.getRawQuery(), "bvid");
            requireVideoId(id, BILIBILI_ID, path);
            embed = "https://player.bilibili.com/player.html?bvid=" + id;
        } else {
            throw invalid(PROJECT_ERROR, path, "unsupported video host");
        }
        if (!canonicalProvider.equals(requestedProvider)) {
            throw invalid(PROJECT_ERROR, path + ".provider", "video provider does not match URL");
        }
        return new VideoTarget(canonicalProvider, embed);
    }

    private static URI safeHttps(URI uri, String code, String path) {
        URI value = required(uri, code, path);
        String scheme = value.getScheme();
        String host = value.getHost();
        if (!"https".equalsIgnoreCase(scheme)
                || host == null
                || host.isBlank()
                || value.getRawAuthority() == null
                || value.getRawUserInfo() != null
                || value.getRawFragment() != null
                || containsCrLf(value.toString())
                || value.getPort() != -1 && value.getPort() != 443) {
            throw invalid(code, path, "absolute HTTPS URL required");
        }
        return value;
    }

    private static boolean containsCrLf(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0
                || lower.contains("%0d")
                || lower.contains("%0a");
    }

    private static String https(URI uri, String code, String path) {
        return safeHttps(uri, code, path).toString();
    }

    private static String optionalHttps(String value, String code, String path) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            safeHttps(URI.create(value), code, path);
            return value;
        } catch (IllegalArgumentException failure) {
            throw invalid(code, path, "absolute HTTPS URL required");
        }
    }

    private static String optionalHttps(URI value, String code, String path) {
        return value == null ? "" : https(value, code, path);
    }

    private static String optionalText(String value) {
        return value == null ? "" : value;
    }

    private static String singlePathSegment(String rawPath) {
        if (rawPath == null || rawPath.length() < 2 || rawPath.charAt(0) != '/') {
            return null;
        }
        String value = rawPath.substring(1);
        return value.isEmpty() || value.indexOf('/') >= 0 ? null : value;
    }

    private static void requireNoQuery(URI uri, String path) {
        if (uri.getRawQuery() != null) {
            throw invalid(PROJECT_ERROR, path, "video query is not allowed");
        }
    }

    private static String onlyQueryParameter(String rawQuery, String name) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return null;
        }
        String[] items = rawQuery.split("&", -1);
        if (items.length != 1) {
            return null;
        }
        String item = items[0];
        int equals = item.indexOf('=');
        if (equals <= 0 || !name.equals(item.substring(0, equals))) {
            return null;
        }
        String value = item.substring(equals + 1);
        return value.isEmpty() ? null : value;
    }

    private static void requireVideoId(String id, Pattern pattern, String path) {
        if (id == null || !pattern.matcher(id).matches()) {
            throw invalid(PROJECT_ERROR, path, "valid video identifier required");
        }
    }

    private static LocaleV1 locale(LocaleCode locale, String code) {
        if (locale == null) {
            throw invalid(code, "locale", "locale is required");
        }
        return switch (locale) {
            case ZH_CN -> LocaleV1.ZH_CN;
            case EN -> LocaleV1.EN;
        };
    }

    private static <T> T localized(
            Map<LocaleV1, T> source,
            LocaleV1 locale,
            String code,
            String path) {
        Map<LocaleV1, T> values = required(source, code, path);
        T value = values.get(locale);
        if (value == null) {
            throw invalid(code, path + '.' + locale.value(), "localized value is required");
        }
        return value;
    }

    private static String text(String value, String code, String path) {
        if (value == null || value.isBlank()) {
            throw invalid(code, path, "nonblank value is required");
        }
        return value;
    }

    private static <T> T required(T value, String code, String path) {
        if (value == null) {
            throw invalid(code, path, "value is required");
        }
        return value;
    }

    private static String mediaPath(UUID assetId, String variant) {
        return "/api/public/media/" + assetId + '/' + variant;
    }

    private static String uuidText(UUID value) {
        return value == null ? "missing" : value.toString();
    }

    private static DomainException invalid(String code, String path, String message) {
        return new DomainException(
                code,
                HttpStatus.UNPROCESSABLE_ENTITY,
                Map.of(path, message));
    }

    private record BlockPayload(String type, PublicBlockDto.Payload payload) {
    }

    private record VideoTarget(String provider, String embedUrl) {
    }
}
