package xyz.yychainsaw.portfolio.publishing.application;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.content.api.ContentBlockDto;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.content.api.ProjectWorkspaceDto;
import xyz.yychainsaw.portfolio.content.api.SiteWorkspaceDto;
import xyz.yychainsaw.portfolio.content.application.WorkspaceValidator;
import xyz.yychainsaw.portfolio.media.application.MediaAssetDescriptor;
import xyz.yychainsaw.portfolio.media.application.MediaCopyDescriptor;
import xyz.yychainsaw.portfolio.media.application.MediaQueryService;
import xyz.yychainsaw.portfolio.media.application.MediaVariantDescriptor;
import xyz.yychainsaw.portfolio.publishing.persistence.PublishingRepository;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.LocaleV1;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.ProjectCatalogSnapshotV1;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.ProjectSnapshotV1;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.PublishedMediaV1;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.SiteSnapshotV1;

@Component
public final class PublicationValidator {
    private static final List<LocaleCode> LOCALES =
            List.of(LocaleCode.ZH_CN, LocaleCode.EN);
    private static final Set<String> PROJECT_MEDIA_USAGES =
            Set.of("COVER", "CARD", "DETAIL");
    private static final Set<String> PROJECT_MEDIA_LAYOUTS =
            Set.of("wide", "standard");
    private static final String READY = "READY";
    private static final String REQUIRED = "required";
    private static final String HTTPS_REQUIRED = "HTTPS URL required";
    private static final Comparator<UUID> ASSET_ORDER =
            Comparator.comparing(UUID::toString);
    private static final Comparator<PublishingRepository.MediaReferenceRow> REFERENCE_ORDER =
            Comparator
                    .comparing((PublishingRepository.MediaReferenceRow row) ->
                            row.assetId().toString())
                    .thenComparing(PublishingRepository.MediaReferenceRow::variantName)
                    .thenComparing(PublishingRepository.MediaReferenceRow::usage);

    private final WorkspaceValidator workspaceValidator;
    private final MediaQueryService mediaQueryService;

    public PublicationValidator(
            WorkspaceValidator workspaceValidator,
            MediaQueryService mediaQueryService) {
        this.workspaceValidator = Objects.requireNonNull(
                workspaceValidator, "workspace validator is required");
        this.mediaQueryService = Objects.requireNonNull(
                mediaQueryService, "media query service is required");
    }

    public List<PublishingRepository.MediaReferenceRow> validateSite(
            SiteWorkspaceDto workspace, SiteSnapshotV1 snapshot) {
        validateSiteWorkspace(workspace);
        if (snapshot == null) {
            throw notPublishable("SITE_NOT_PUBLISHABLE", "snapshot");
        }
        return validateMedia(
                siteUsages(workspace),
                collapseMedia(snapshot.media(), false));
    }

    public void validateSiteWorkspace(SiteWorkspaceDto workspace) {
        workspaceValidator.validateSite(workspace);
        validateSitePlaceholders(workspace);
        validateSiteContent(workspace);
    }

    public List<PublishingRepository.MediaReferenceRow> validateProject(
            ProjectWorkspaceDto workspace, ProjectSnapshotV1 snapshot) {
        validateProjectWorkspace(workspace);
        if (snapshot == null) {
            throw notPublishable("PROJECT_NOT_PUBLISHABLE", "snapshot");
        }
        return validateMedia(
                projectUsages(workspace),
                collapseMedia(snapshot.media(), false));
    }

    public void validateProjectWorkspace(ProjectWorkspaceDto workspace) {
        workspaceValidator.validateProject(workspace);
        validateProjectContent(workspace);
    }

    public List<PublishingRepository.MediaReferenceRow> validateCatalog(
            ProjectCatalogSnapshotV1 snapshot) {
        validateCatalogStructure(snapshot);
        TreeMap<String, String> errors = new TreeMap<>();
        List<PublishedMediaV1> covers = new ArrayList<>();
        Map<UUID, Set<String>> usages = new HashMap<>();
        for (int index = 0; index < snapshot.projects().size(); index++) {
            ProjectCatalogSnapshotV1.Card card = snapshot.projects().get(index);
            String path = "projects[" + index + "]";
            if (card.cover() == null) {
                errors.put(path + ".cover", REQUIRED);
                continue;
            }
            covers.add(card.cover());
            addUsage(usages, card.cover().assetId(), "CATALOG_COVER");
        }
        finish("PROJECT_NOT_PUBLISHABLE", errors);
        return validateMedia(usages, collapseMedia(covers, true));
    }

    public void validateCatalogStructure(ProjectCatalogSnapshotV1 snapshot) {
        if (snapshot == null) {
            throw notPublishable("PROJECT_NOT_PUBLISHABLE", "snapshot");
        }
        TreeMap<String, String> errors = new TreeMap<>();
        for (int index = 0; index < snapshot.projects().size(); index++) {
            ProjectCatalogSnapshotV1.Card card = snapshot.projects().get(index);
            String path = "projects[" + index + "]";
            validateCatalogCard(errors, path, card);
        }
        finish("PROJECT_NOT_PUBLISHABLE", errors);
    }

    private static void validateCatalogCard(
            Map<String, String> errors,
            String path,
            ProjectCatalogSnapshotV1.Card card) {
        if (card.projectId() == null) {
            errors.put(path + ".projectId", REQUIRED);
        }
        requireText(errors, path + ".slug", card.slug());
        requireText(errors, path + ".number", card.number());
        for (LocaleV1 locale : List.of(LocaleV1.ZH_CN, LocaleV1.EN)) {
            String localePath = path + ".copy." + locale.value();
            ProjectCatalogSnapshotV1.CardCopy copy = card.copy().get(locale);
            if (copy == null) {
                errors.put(localePath, REQUIRED);
                continue;
            }
            requireText(errors, localePath + ".status", copy.status());
            requireText(errors, localePath + ".eyebrow", copy.eyebrow());
            requireText(errors, localePath + ".title", copy.title());
            requireText(errors, localePath + ".summary", copy.summary());
            for (int tagIndex = 0; tagIndex < copy.tags().size(); tagIndex++) {
                requireText(
                        errors,
                        localePath + ".tags[" + tagIndex + "]",
                        copy.tags().get(tagIndex));
            }
        }
    }

    private static void validateSitePlaceholders(SiteWorkspaceDto site) {
        TreeMap<String, String> errors = new TreeMap<>();
        placeholder(
                errors,
                "identity.email",
                site.email(),
                "your-email@example.com");
        placeholder(
                errors,
                "portfolioContent.zh-CN.hero.visualLabel",
                site.hero().copy().get(LocaleCode.ZH_CN).visualLabel(),
                "视觉概念图 / 之后替换为本人 UE 截图");
        placeholder(
                errors,
                "portfolioContent.en.hero.visualLabel",
                site.hero().copy().get(LocaleCode.EN).visualLabel(),
                "Visual concept image / replace with my own UE capture");
        placeholder(
                errors,
                "portfolioContent.zh-CN.work.imageNotice",
                site.work().get(LocaleCode.ZH_CN).imageNotice(),
                "概念占位图，之后替换为本人 UE 截图");
        placeholder(
                errors,
                "portfolioContent.en.work.imageNotice",
                site.work().get(LocaleCode.EN).imageNotice(),
                "Concept placeholder - to be replaced with my own UE capture");
        placeholder(
                errors,
                "portfolioContent.zh-CN.contact.emailLabel",
                site.contact().get(LocaleCode.ZH_CN).emailLabel(),
                "联系邮箱（待替换）");
        placeholder(
                errors,
                "portfolioContent.en.contact.emailLabel",
                site.contact().get(LocaleCode.EN).emailLabel(),
                "Email placeholder");
        finish("PLACEHOLDER_CONTENT_PRESENT", errors);
    }

    private static void validateSiteContent(SiteWorkspaceDto site) {
        TreeMap<String, String> errors = new TreeMap<>();
        visitLocalized(errors, "identity", site.identity(), (path, copy) -> {
            requireText(errors, path + ".displayName", copy.displayName());
            requireText(errors, path + ".secondaryName", copy.secondaryName());
        });
        visitLocalized(errors, "seo", site.seo(), (path, copy) -> {
            requireText(errors, path + ".title", copy.title());
            requireText(errors, path + ".description", copy.description());
        });
        visitLocalized(errors, "accessibility", site.accessibility(), (path, copy) -> {
            requireText(errors, path + ".skip", copy.skip());
            requireText(errors, path + ".primaryNav", copy.primaryNav());
            requireText(errors, path + ".mobileNav", copy.mobileNav());
            requireText(errors, path + ".openMenu", copy.openMenu());
            requireText(errors, path + ".closeMenu", copy.closeMenu());
            requireText(errors, path + ".language", copy.language());
            requireText(errors, path + ".backToTop", copy.backToTop());
            requireText(errors, path + ".projectTags", copy.projectTags());
        });
        for (int index = 0; index < site.navigation().size(); index++) {
            SiteWorkspaceDto.NavigationItem item = site.navigation().get(index);
            String path = "navigation[" + index + "]";
            requireText(errors, path + ".target", item.target());
            visitLocalizedStrings(errors, path + ".labels", item.labels());
        }
        visitLocalized(errors, "hero.copy", site.hero().copy(), (path, copy) -> {
            requireText(errors, path + ".eyebrow", copy.eyebrow());
            requireText(errors, path + ".displayName", copy.displayName());
            requireText(errors, path + ".secondaryName", copy.secondaryName());
            requireText(errors, path + ".role", copy.role());
            requireText(errors, path + ".headline", copy.headline());
            requireText(errors, path + ".introduction", copy.introduction());
            requireText(errors, path + ".availability", copy.availability());
            requireText(errors, path + ".primaryCta", copy.primaryCta());
            requireText(errors, path + ".secondaryCta", copy.secondaryCta());
            requireText(errors, path + ".visualLabel", copy.visualLabel());
            requireText(errors, path + ".stageLabel", copy.stageLabel());
        });
        if (site.hero().mediaAssetId() != null) {
            requireText(errors, "hero.objectPosition", site.hero().objectPosition());
            requireText(errors, "hero.credit", site.hero().credit());
            requireHttps(errors, "hero.sourceUrl", site.hero().sourceUrl());
        }
        visitLocalized(errors, "about", site.about(), (path, copy) -> {
            requireText(errors, path + ".label", copy.label());
            requireText(errors, path + ".title", copy.title());
            requireText(errors, path + ".statement", copy.statement());
            requireText(errors, path + ".focusLabel", copy.focusLabel());
            requireText(errors, path + ".focusTitle", copy.focusTitle());
            requireText(errors, path + ".focusIntro", copy.focusIntro());
        });
        for (int index = 0; index < site.facts().size(); index++) {
            SiteWorkspaceDto.ProfileFact fact = site.facts().get(index);
            String path = "facts[" + index + "]";
            requireText(errors, path + ".externalKey", fact.externalKey());
            visitLocalized(errors, path + ".copy", fact.copy(), (copyPath, copy) -> {
                requireText(errors, copyPath + ".label", copy.label());
                requireText(errors, copyPath + ".value", copy.value());
            });
        }
        for (int index = 0; index < site.profileSkills().size(); index++) {
            SiteWorkspaceDto.ProfileSkill skill = site.profileSkills().get(index);
            String path = "profileSkills[" + index + "]";
            requireText(errors, path + ".externalKey", skill.externalKey());
            visitLocalized(errors, path + ".copy", skill.copy(), (copyPath, copy) -> {
                requireText(errors, copyPath + ".name", copy.name());
                requireText(errors, copyPath + ".status", copy.status());
            });
        }
        visitLocalized(errors, "work", site.work(), (path, copy) -> {
            requireText(errors, path + ".label", copy.label());
            requireText(errors, path + ".title", copy.title());
            requireText(errors, path + ".introduction", copy.introduction());
            requireText(errors, path + ".imageNotice", copy.imageNotice());
            requireText(errors, path + ".openSlotLabel", copy.openSlotLabel());
            requireText(errors, path + ".openSlotTitle", copy.openSlotTitle());
            requireText(errors, path + ".openSlotText", copy.openSlotText());
            requireText(errors, path + ".openSlotMeta", copy.openSlotMeta());
        });
        visitLocalized(errors, "roadmap.header", site.roadmap().header(), (path, copy) -> {
            requireText(errors, path + ".label", copy.label());
            requireText(errors, path + ".title", copy.title());
            requireText(errors, path + ".introduction", copy.introduction());
        });
        for (int stageIndex = 0;
                stageIndex < site.roadmap().stages().size();
                stageIndex++) {
            SiteWorkspaceDto.RoadmapStage stage =
                    site.roadmap().stages().get(stageIndex);
            String path = "roadmap.stages[" + stageIndex + "]";
            requireText(errors, path + ".externalKey", stage.externalKey());
            requireText(errors, path + ".number", stage.number());
            visitLocalized(errors, path + ".copy", stage.copy(), (copyPath, copy) -> {
                requireText(errors, copyPath + ".period", copy.period());
                requireText(errors, copyPath + ".title", copy.title());
                requireText(errors, copyPath + ".summary", copy.summary());
            });
            for (int outcomeIndex = 0;
                    outcomeIndex < stage.outcomes().size();
                    outcomeIndex++) {
                visitLocalizedStrings(
                        errors,
                        path + ".outcomes[" + outcomeIndex + "].text",
                        stage.outcomes().get(outcomeIndex).text());
            }
        }
        visitLocalized(errors, "contact", site.contact(), (path, copy) -> {
            requireText(errors, path + ".label", copy.label());
            requireText(errors, path + ".title", copy.title());
            requireText(errors, path + ".introduction", copy.introduction());
            requireText(errors, path + ".emailLabel", copy.emailLabel());
            requireText(errors, path + ".workCta", copy.workCta());
            requireText(errors, path + ".roadmapCta", copy.roadmapCta());
            requireText(errors, path + ".footerNote", copy.footerNote());
        });
        visitLocalized(errors, "privacy", site.privacy(), (path, copy) -> {
            requireText(errors, path + ".title", copy.title());
            requireText(errors, path + ".bodyMarkdown", copy.bodyMarkdown());
        });
        for (int index = 0; index < site.socialLinks().size(); index++) {
            SiteWorkspaceDto.SocialLink link = site.socialLinks().get(index);
            String path = "socialLinks[" + index + "]";
            requireText(errors, path + ".platform", link.platform());
            requireHttps(errors, path + ".url", link.url());
        }
        validateResumes(errors, site.resumes());
        finish("SITE_NOT_PUBLISHABLE", errors);
    }

    private static void validateResumes(
            Map<String, String> errors,
            List<SiteWorkspaceDto.ResumeDocument> resumes) {
        // A downloadable resume is useful, but a new portfolio must be publishable
        // before its owner has prepared one. Once any resume is added, retain the
        // bilingual current-document invariant.
        if (resumes.isEmpty()) {
            return;
        }
        for (LocaleCode locale : LOCALES) {
            long currentCount = resumes.stream()
                    .filter(SiteWorkspaceDto.ResumeDocument::current)
                    .filter(resume -> resume.locale() == locale)
                    .count();
            if (currentCount != 1L) {
                errors.put(
                        "resumes." + locale.value() + ".current",
                        "exactly one current resume is required");
            }
        }
        for (int index = 0; index < resumes.size(); index++) {
            SiteWorkspaceDto.ResumeDocument resume = resumes.get(index);
            String path = "resumes[" + index + "]";
            if (resume.locale() == null) {
                errors.put(path + ".locale", REQUIRED);
            }
            if (resume.mediaAssetId() == null) {
                errors.put(path + ".mediaAssetId", REQUIRED);
            }
            requireText(errors, path + ".versionLabel", resume.versionLabel());
            if (resume.documentDate() == null) {
                errors.put(path + ".documentDate", REQUIRED);
            }
        }
    }

    private static void validateProjectContent(ProjectWorkspaceDto project) {
        TreeMap<String, String> errors = new TreeMap<>();
        requireText(errors, "externalKey", project.externalKey());
        requireText(errors, "number", project.number());
        visitLocalized(errors, "translations", project.translations(), (path, copy) -> {
            requireText(errors, path + ".status", copy.status());
            requireText(errors, path + ".eyebrow", copy.eyebrow());
            requireText(errors, path + ".title", copy.title());
            requireText(errors, path + ".summary", copy.summary());
            requireText(errors, path + ".seoTitle", copy.seoTitle());
            requireText(errors, path + ".seoDescription", copy.seoDescription());
        });
        validateTaxonomy(errors, "tags", project.tags());
        validateTaxonomy(errors, "skills", project.skills());
        boolean hasCover = false;
        for (int index = 0; index < project.media().size(); index++) {
            ProjectWorkspaceDto.ProjectMedia media = project.media().get(index);
            String path = "media[" + index + "]";
            if (media.assetId() == null) {
                errors.put(path + ".assetId", REQUIRED);
            }
            if (!PROJECT_MEDIA_USAGES.contains(media.usage())) {
                errors.put(path + ".usage", "must be COVER, CARD, or DETAIL");
            }
            hasCover |= "COVER".equals(media.usage());
            if (!PROJECT_MEDIA_LAYOUTS.contains(media.layout())) {
                errors.put(path + ".layout", "must be wide or standard");
            }
            requireText(errors, path + ".objectPosition", media.objectPosition());
            requireText(errors, path + ".credit", media.credit());
            requireHttps(errors, path + ".sourceUrl", media.sourceUrl());
        }
        if (!hasCover) {
            errors.put("media.cover", REQUIRED);
        }
        for (int index = 0; index < project.blocks().size(); index++) {
            validatePublishedBlock(
                    errors, "blocks[" + index + "]", project.blocks().get(index));
        }
        finish("PROJECT_NOT_PUBLISHABLE", errors);
    }

    private static void validateTaxonomy(
            Map<String, String> errors,
            String path,
            List<ProjectWorkspaceDto.TaxonomyRef> values) {
        for (int index = 0; index < values.size(); index++) {
            ProjectWorkspaceDto.TaxonomyRef value = values.get(index);
            String itemPath = path + "[" + index + "]";
            requireText(errors, itemPath + ".normalizedKey", value.normalizedKey());
            visitLocalizedStrings(errors, itemPath + ".names", value.names());
        }
    }

    private static void validatePublishedBlock(
            Map<String, String> errors,
            String path,
            ContentBlockDto block) {
        ContentBlockDto.Payload payload = block.payload();
        if (payload instanceof ContentBlockDto.MarkdownPayload markdown) {
            visitLocalizedStrings(errors, path + ".markdown", markdown.markdown());
        } else if (payload instanceof ContentBlockDto.VideoPayload video) {
            visitLocalized(errors, path + ".copy", video.copy(), (copyPath, copy) -> {
                requireText(errors, copyPath + ".title", copy.title());
                requireText(errors, copyPath + ".description", copy.description());
            });
        } else if (payload instanceof ContentBlockDto.CodePayload code) {
            requireText(errors, path + ".code", code.code());
            requireText(errors, path + ".language", code.language());
            visitLocalized(errors, path + ".copy", code.copy(), (copyPath, copy) -> {
                requireText(errors, copyPath + ".title", copy.title());
                requireText(errors, copyPath + ".description", copy.description());
            });
        } else if (payload instanceof ContentBlockDto.QuotePayload quote) {
            visitLocalized(errors, path + ".copy", quote.copy(), (copyPath, copy) -> {
                requireText(errors, copyPath + ".quote", copy.quote());
                requireText(errors, copyPath + ".source", copy.source());
            });
        } else if (payload instanceof ContentBlockDto.MetricsPayload metrics) {
            for (int index = 0; index < metrics.metrics().size(); index++) {
                ContentBlockDto.Metric metric = metrics.metrics().get(index);
                String metricPath = path + ".metrics[" + index + "]";
                if (metric.numericValue() == null) {
                    errors.put(metricPath + ".numericValue", REQUIRED);
                }
                visitLocalized(
                        errors,
                        metricPath + ".copy",
                        metric.copy(),
                        (copyPath, copy) -> {
                            requireText(errors, copyPath + ".label", copy.label());
                            requireText(errors, copyPath + ".value", copy.value());
                            requireText(errors, copyPath + ".suffix", copy.suffix());
                        });
            }
        } else if (payload instanceof ContentBlockDto.DownloadPayload download) {
            visitLocalized(errors, path + ".copy", download.copy(), (copyPath, copy) -> {
                requireText(errors, copyPath + ".label", copy.label());
                requireText(errors, copyPath + ".description", copy.description());
            });
        } else if (payload instanceof ContentBlockDto.LinkPayload link) {
            visitLocalized(errors, path + ".copy", link.copy(), (copyPath, copy) -> {
                requireText(errors, copyPath + ".label", copy.label());
                requireText(errors, copyPath + ".description", copy.description());
            });
        }
    }

    private List<PublishingRepository.MediaReferenceRow> validateMedia(
            Map<UUID, Set<String>> usages,
            CollapsedMedia collapsed) {
        TreeMap<String, String> readyErrors = new TreeMap<>(collapsed.errors());
        Map<UUID, MediaAssetDescriptor> descriptors = new HashMap<>();
        List<UUID> assetIds = new ArrayList<>(usages.keySet());
        assetIds.sort(ASSET_ORDER);
        for (UUID assetId : assetIds) {
            String path = mediaPath(assetId);
            PublishedMediaV1 published = collapsed.media().get(assetId);
            if (published == null) {
                readyErrors.put(path, REQUIRED);
                continue;
            }
            MediaAssetDescriptor descriptor;
            try {
                descriptor = mediaQueryService.requireReadyAsset(assetId);
            } catch (DomainException failure) {
                readyErrors.put(path + ".status", "READY media required");
                continue;
            }
            if (descriptor == null) {
                readyErrors.put(path + ".status", "READY media required");
                continue;
            }
            descriptors.put(assetId, descriptor);
            compareReadyAsset(readyErrors, path, assetId, descriptor, published);
        }
        for (UUID extra : collapsed.media().keySet()) {
            if (!usages.containsKey(extra)) {
                readyErrors.put(mediaPath(extra) + ".usage", "unreferenced media");
            }
        }
        finish("MEDIA_NOT_READY", readyErrors);

        TreeMap<String, String> translationErrors = new TreeMap<>();
        for (UUID assetId : assetIds) {
            compareMediaCopy(
                    translationErrors,
                    mediaPath(assetId),
                    descriptors.get(assetId),
                    collapsed.media().get(assetId));
        }
        finish("MEDIA_TRANSLATION_INCOMPLETE", translationErrors);

        TreeSet<PublishingRepository.MediaReferenceRow> references =
                new TreeSet<>(REFERENCE_ORDER);
        for (UUID assetId : assetIds) {
            PublishedMediaV1 media = collapsed.media().get(assetId);
            for (PublishedMediaV1.Variant variant : media.variants()) {
                for (String usage : usages.get(assetId)) {
                    references.add(new PublishingRepository.MediaReferenceRow(
                            assetId, variant.name(), usage));
                }
            }
        }
        return List.copyOf(references);
    }

    private void compareReadyAsset(
            Map<String, String> errors,
            String path,
            UUID expectedAssetId,
            MediaAssetDescriptor descriptor,
            PublishedMediaV1 published) {
        if (!READY.equals(descriptor.status())) {
            errors.put(path + ".status", "must be READY");
        }
        if (!expectedAssetId.equals(descriptor.assetId())
                || !expectedAssetId.equals(published.assetId())) {
            errors.put(path + ".assetId", "does not match referenced asset");
        }
        mismatch(errors, path + ".contentType", descriptor.mimeType(), published.contentType());
        mismatch(errors, path + ".contentLength", descriptor.byteSize(), published.contentLength());
        mismatch(errors, path + ".sha256", descriptor.sha256(), published.sha256());

        TreeMap<String, MediaVariantDescriptor> descriptorVariants = new TreeMap<>();
        boolean descriptorDuplicate = false;
        for (MediaVariantDescriptor variant : descriptor.variants()) {
            if (variant == null
                    || variant.variantName() == null
                    || descriptorVariants.putIfAbsent(variant.variantName(), variant) != null) {
                descriptorDuplicate = true;
            }
        }
        TreeMap<String, PublishedMediaV1.Variant> publishedVariants = new TreeMap<>();
        boolean publishedDuplicate = false;
        for (PublishedMediaV1.Variant variant : published.variants()) {
            if (variant == null
                    || variant.name() == null
                    || publishedVariants.putIfAbsent(variant.name(), variant) != null) {
                publishedDuplicate = true;
            }
        }
        if (descriptorDuplicate
                || publishedDuplicate
                || !descriptorVariants.keySet().equals(publishedVariants.keySet())) {
            errors.put(path + ".variants", "variant set does not match READY media");
        }
        for (Map.Entry<String, PublishedMediaV1.Variant> entry :
                publishedVariants.entrySet()) {
            String name = entry.getKey();
            if (!descriptorVariants.containsKey(name)) {
                continue;
            }
            String variantPath = path + ".variants." + name;
            MediaVariantDescriptor selected;
            try {
                selected = mediaQueryService.requireReadyVariant(expectedAssetId, name);
            } catch (DomainException failure) {
                errors.put(variantPath + ".status", "READY variant required");
                continue;
            }
            if (selected == null || !READY.equals(selected.status())) {
                errors.put(variantPath + ".status", "must be READY");
                if (selected == null) {
                    continue;
                }
            }
            if (!expectedAssetId.equals(selected.assetId())
                    || !name.equals(selected.variantName())) {
                errors.put(variantPath + ".identity", "does not match selected variant");
            }
            MediaVariantDescriptor listed = descriptorVariants.get(name);
            mismatch(
                    errors,
                    variantPath + ".contentType",
                    listed.mimeType(),
                    selected.mimeType());
            mismatch(errors, variantPath + ".width", listed.width(), selected.width());
            mismatch(errors, variantPath + ".height", listed.height(), selected.height());
            mismatch(errors, variantPath + ".bytes", listed.byteSize(), selected.byteSize());
            mismatch(errors, variantPath + ".sha256", listed.sha256(), selected.sha256());
            PublishedMediaV1.Variant publishedVariant = entry.getValue();
            mismatch(errors, variantPath + ".width", selected.width(), publishedVariant.width());
            mismatch(errors, variantPath + ".height", selected.height(), publishedVariant.height());
            mismatch(errors, variantPath + ".bytes", selected.byteSize(), publishedVariant.bytes());
            mismatch(errors, variantPath + ".sha256", selected.sha256(), publishedVariant.sha256());
        }
    }

    private static void compareMediaCopy(
            Map<String, String> errors,
            String path,
            MediaAssetDescriptor descriptor,
            PublishedMediaV1 published) {
        for (LocaleCode locale : LOCALES) {
            String localePath = path + ".copy." + locale.value();
            MediaCopyDescriptor descriptorCopy =
                    descriptor.copyByLocale().get(locale.value());
            PublishedMediaV1.MediaCopy publishedCopy =
                    published.copy().get(LocaleV1.from(locale.value()));
            if (descriptorCopy == null || publishedCopy == null) {
                errors.put(localePath + ".alt", REQUIRED);
                errors.put(localePath + ".caption", REQUIRED);
                errors.put(localePath + ".credit", REQUIRED);
                errors.put(localePath + ".sourceUrl", REQUIRED);
                continue;
            }
            requireText(errors, localePath + ".alt", descriptorCopy.alt());
            requireText(errors, localePath + ".alt", publishedCopy.alt());
            requireHttpsIfPresent(errors, localePath + ".sourceUrl", descriptorCopy.sourceUrl());
            requireHttpsIfPresent(errors, localePath + ".sourceUrl", publishedCopy.sourceUrl());
            mismatch(errors, localePath + ".alt", descriptorCopy.alt(), publishedCopy.alt());
            mismatch(
                    errors,
                    localePath + ".caption",
                    descriptorCopy.caption(),
                    publishedCopy.caption());
            mismatch(
                    errors,
                    localePath + ".credit",
                    descriptorCopy.credit(),
                    publishedCopy.credit());
            mismatch(
                    errors,
                    localePath + ".sourceUrl",
                    descriptorCopy.sourceUrl(),
                    publishedCopy.sourceUrl());
        }
    }

    private static Map<UUID, Set<String>> siteUsages(SiteWorkspaceDto site) {
        Map<UUID, Set<String>> usages = new HashMap<>();
        addUsage(usages, site.hero().mediaAssetId(), "HERO");
        site.resumes().stream()
                .filter(SiteWorkspaceDto.ResumeDocument::current)
                .forEach(resume -> addUsage(usages, resume.mediaAssetId(), "RESUME"));
        return usages;
    }

    private static Map<UUID, Set<String>> projectUsages(ProjectWorkspaceDto project) {
        Map<UUID, Set<String>> usages = new HashMap<>();
        project.media().forEach(media -> addUsage(usages, media.assetId(), media.usage()));
        for (ContentBlockDto block : project.blocks()) {
            ContentBlockDto.Payload payload = block.payload();
            if (payload instanceof ContentBlockDto.ImagePayload image) {
                addUsage(usages, image.mediaAssetId(), "BLOCK_IMAGE");
            } else if (payload instanceof ContentBlockDto.GalleryPayload gallery) {
                gallery.mediaAssetIds().forEach(
                        assetId -> addUsage(usages, assetId, "BLOCK_GALLERY"));
            } else if (payload instanceof ContentBlockDto.VideoPayload video) {
                addUsage(usages, video.coverAssetId(), "BLOCK_VIDEO_COVER");
            } else if (payload instanceof ContentBlockDto.DownloadPayload download) {
                addUsage(usages, download.mediaAssetId(), "BLOCK_DOWNLOAD");
            }
        }
        return usages;
    }

    private static void addUsage(
            Map<UUID, Set<String>> usages, UUID assetId, String usage) {
        if (assetId == null || usage == null || usage.isBlank()) {
            return;
        }
        usages.computeIfAbsent(assetId, ignored -> new TreeSet<>()).add(usage);
    }

    private static CollapsedMedia collapseMedia(
            List<PublishedMediaV1> values, boolean allowIdenticalDuplicates) {
        TreeMap<UUID, PublishedMediaV1> media = new TreeMap<>(ASSET_ORDER);
        TreeMap<String, String> errors = new TreeMap<>();
        for (PublishedMediaV1 value : values) {
            if (value == null || value.assetId() == null) {
                errors.put("media", "media identity is required");
                continue;
            }
            PublishedMediaV1 prior = media.putIfAbsent(value.assetId(), value);
            if (prior != null && (!allowIdenticalDuplicates || !prior.equals(value))) {
                errors.put(mediaPath(value.assetId()) + ".duplicates", "duplicate media");
            }
        }
        return new CollapsedMedia(Map.copyOf(media), Map.copyOf(errors));
    }

    private static <T> void visitLocalized(
            Map<String, String> errors,
            String path,
            Map<LocaleCode, T> values,
            BiConsumer<String, T> visitor) {
        for (LocaleCode locale : LOCALES) {
            String localePath = path + "." + locale.value();
            T value = values == null ? null : values.get(locale);
            if (value == null) {
                errors.put(localePath, REQUIRED);
            } else {
                visitor.accept(localePath, value);
            }
        }
    }

    private static void visitLocalizedStrings(
            Map<String, String> errors,
            String path,
            Map<LocaleCode, String> values) {
        visitLocalized(errors, path, values, (localePath, value) ->
                requireText(errors, localePath, value));
    }

    private static void placeholder(
            Map<String, String> errors,
            String path,
            String actual,
            String expected) {
        if (expected.equals(actual)) {
            errors.put(path, "placeholder content must be replaced");
        }
    }

    private static void requireText(
            Map<String, String> errors, String path, String value) {
        if (value == null || value.isBlank()) {
            errors.put(path, REQUIRED);
        }
    }

    private static void requireHttps(
            Map<String, String> errors, String path, URI value) {
        if (value == null || !"https".equalsIgnoreCase(value.getScheme())) {
            errors.put(path, HTTPS_REQUIRED);
        }
    }

    private static void requireHttpsIfPresent(
            Map<String, String> errors, String path, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            URI parsed = URI.create(value);
            if (!"https".equalsIgnoreCase(parsed.getScheme())) {
                errors.put(path, HTTPS_REQUIRED);
            }
        } catch (IllegalArgumentException failure) {
            errors.put(path, HTTPS_REQUIRED);
        }
    }

    private static void mismatch(
            Map<String, String> errors, String path, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            errors.put(path, "does not match READY media");
        }
    }

    private static DomainException notPublishable(String code, String path) {
        return new DomainException(
                code,
                HttpStatus.UNPROCESSABLE_ENTITY,
                Map.of(path, REQUIRED));
    }

    private static void finish(String code, Map<String, String> errors) {
        if (!errors.isEmpty()) {
            throw new DomainException(
                    code,
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    new TreeMap<>(errors));
        }
    }

    private static String mediaPath(UUID assetId) {
        return "media[" + assetId + "]";
    }

    private record CollapsedMedia(
            Map<UUID, PublishedMediaV1> media,
            Map<String, String> errors) { }
}
