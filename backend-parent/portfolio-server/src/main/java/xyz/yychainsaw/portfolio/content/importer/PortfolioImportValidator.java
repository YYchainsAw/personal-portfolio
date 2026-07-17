package xyz.yychainsaw.portfolio.content.importer;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;
import xyz.yychainsaw.portfolio.api.admin.media.StrictHttpsSourceUrl;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.media.application.MediaFileInspector;

@Component
@Conditional(PortfolioImportRuntimeCondition.class)
public final class PortfolioImportValidator {
    private static final Set<LocaleCode> REQUIRED_LOCALES =
            Set.of(LocaleCode.ZH_CN, LocaleCode.EN);
    private static final Pattern PUBLIC_ASSET = Pattern.compile("/images/[^/\\\\]+");
    private static final Pattern EXTERNAL_KEY =
            Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+");
    private static final Set<String> SUPPORTED_EXTENSIONS =
            Set.of("jpg", "jpeg", "png", "pdf");
    private static final Comparator<ImportIssue> ISSUE_ORDER =
            Comparator.comparing(ImportIssue::severity)
                    .thenComparing(ImportIssue::path)
                    .thenComparing(ImportIssue::code)
                    .thenComparing(ImportIssue::message);
    private final MediaFileInspector mediaFileInspector;

    public PortfolioImportValidator(MediaFileInspector mediaFileInspector) {
        this.mediaFileInspector = Objects.requireNonNull(
                mediaFileInspector, "media file inspector is required");
    }

    public List<ImportIssue> validate(PortfolioImportV1 payload, Path assetRoot) {
        Objects.requireNonNull(payload, "portfolio import payload is required");
        List<ImportIssue> issues = new ArrayList<>();

        if (payload.schemaVersion() != 1) {
            structure(
                    issues,
                    "schemaVersion",
                    "IMPORT_SCHEMA_VERSION_UNSUPPORTED",
                    "Schema version is unsupported");
        }
        validateIdentity(payload.identity(), issues);
        validateLocaleSet(payload.portfolioContent(), "portfolioContent", issues);
        validateMedia(payload, assetRoot, issues);

        PortfolioImportV1.PortfolioCopy chinese =
                payload.portfolioContent().get(LocaleCode.ZH_CN);
        PortfolioImportV1.PortfolioCopy english =
                payload.portfolioContent().get(LocaleCode.EN);
        if (chinese != null && english != null) {
            validateBilingualShape(payload, chinese, english, issues);
            validateStableIdentities(chinese, english, issues);
        }

        visitTranslationWarnings(payload, issues);
        visitFixedPlaceholders(payload, issues);
        return sortedIssues(issues);
    }

    private static void validateIdentity(
            PortfolioImportV1.Identity identity, List<ImportIssue> issues) {
        if (identity == null) {
            structure(issues, "identity", "IMPORT_JSON_INVALID", "Identity is invalid");
            return;
        }
        if (!boundedText(identity.monogram(), 16)) {
            invalid(issues, "identity.monogram");
        }
        if (!validEmail(identity.email(), 320)) {
            invalid(issues, "identity.email");
        }
    }

    private void validateMedia(
            PortfolioImportV1 payload, Path assetRoot, List<ImportIssue> issues) {
        Map<String, MediaMetadata> metadataByPath = new LinkedHashMap<>();
        PortfolioImportV1.HeroAsset hero = payload.heroAsset();
        if (hero == null) {
            invalid(issues, "heroAsset");
        } else {
            validateLocaleSet(hero.alt(), "heroAsset.alt", issues);
            validateMediaMetadata(
                    hero.image(),
                    hero.objectPosition(),
                    hero.credit(),
                    hero.sourceUrl(),
                    hero.alt(),
                    "heroAsset",
                    assetRoot,
                    metadataByPath,
                    issues);
        }

        Set<String> assetIds = new HashSet<>();
        List<PortfolioImportV1.ProjectAsset> projectAssets = payload.projectAssets();
        for (int index = 0; index < projectAssets.size(); index++) {
            PortfolioImportV1.ProjectAsset asset = projectAssets.get(index);
            String path = "projectAssets[" + index + "]";
            if (!assetIds.add(asset.id())) {
                duplicate(issues, path + ".id");
            }
            if (!validExternalKey(asset.id(), 96)) {
                invalid(issues, path + ".id");
            }
            if (!Set.of("wide", "standard").contains(asset.layout())) {
                invalid(issues, path + ".layout");
            }
            validateLocaleSet(asset.alt(), path + ".alt", issues);
            validateMediaMetadata(
                    asset.image(),
                    asset.objectPosition(),
                    asset.credit(),
                    asset.sourceUrl(),
                    asset.alt(),
                    path,
                    assetRoot,
                    metadataByPath,
                    issues);
        }
    }

    private void validateMediaMetadata(
            String publicPath,
            String objectPosition,
            String credit,
            URI sourceUrl,
            Map<LocaleCode, String> alt,
            String path,
            Path assetRoot,
            Map<String, MediaMetadata> metadataByPath,
            List<ImportIssue> issues) {
        if (!validAsset(assetRoot, publicPath)) {
            structure(
                    issues,
                    path + ".image",
                    "IMPORT_ASSET_PATH_INVALID",
                    "Asset path is invalid");
        }
        if (!boundedText(objectPosition, 64)) {
            invalid(issues, path + ".objectPosition");
        }
        if (!boundedText(credit, 300)) {
            invalid(issues, path + ".credit");
        }
        if (!validSourceUrl(sourceUrl)) {
            invalid(issues, path + ".sourceUrl");
        }
        if (alt != null) {
            for (LocaleCode locale : REQUIRED_LOCALES) {
                String value = alt.get(locale);
                if (value == null || value.length() > 500) {
                    invalid(issues, path + ".alt." + locale.value());
                }
            }
        }

        MediaMetadata metadata = new MediaMetadata(
                alt == null ? null : alt.get(LocaleCode.ZH_CN),
                alt == null ? null : alt.get(LocaleCode.EN),
                credit,
                sourceUrl == null ? null : sourceUrl.toASCIIString());
        MediaMetadata previous = metadataByPath.putIfAbsent(publicPath, metadata);
        if (previous != null && !previous.equals(metadata)) {
            structure(
                    issues,
                    path + ".image",
                    "IMPORT_JSON_INVALID",
                    "Public asset metadata conflicts");
        }
    }

    private static void validateBilingualShape(
            PortfolioImportV1 payload,
            PortfolioImportV1.PortfolioCopy chinese,
            PortfolioImportV1.PortfolioCopy english,
            List<ImportIssue> issues) {
        if (chinese.about().facts().size() != english.about().facts().size()) {
            invalid(issues, "portfolioContent.zh-CN.about.facts");
        }
        if (chinese.about().skills().size() != english.about().skills().size()) {
            invalid(issues, "portfolioContent.zh-CN.about.skills");
        }
        validateProjectShape(payload, chinese.projects(), english.projects(), issues);
        validateRoadmapShape(chinese.roadmap(), english.roadmap(), issues);

        if (!Objects.equals(payload.identity().email(), chinese.contact().email())) {
            invalid(issues, "portfolioContent.zh-CN.contact.email");
        }
        if (!Objects.equals(payload.identity().email(), english.contact().email())) {
            invalid(issues, "portfolioContent.en.contact.email");
        }
        if (!validEmail(chinese.contact().email(), 320)) {
            invalid(issues, "portfolioContent.zh-CN.contact.email");
        }
        if (!validEmail(english.contact().email(), 320)) {
            invalid(issues, "portfolioContent.en.contact.email");
        }
    }

    private static void validateProjectShape(
            PortfolioImportV1 payload,
            List<PortfolioImportV1.ProjectCopy> chinese,
            List<PortfolioImportV1.ProjectCopy> english,
            List<ImportIssue> issues) {
        duplicateProjectIds(chinese, "portfolioContent.zh-CN.projects", issues);
        duplicateProjectIds(english, "portfolioContent.en.projects", issues);

        Set<String> mediaIds = new HashSet<>();
        for (PortfolioImportV1.ProjectAsset asset : payload.projectAssets()) {
            mediaIds.add(asset.id());
        }
        Set<String> chineseIds = new LinkedHashSet<>();
        chinese.forEach(project -> chineseIds.add(project.id()));
        Set<String> englishIds = new LinkedHashSet<>();
        english.forEach(project -> englishIds.add(project.id()));
        if (!mediaIds.equals(chineseIds) || !mediaIds.equals(englishIds)) {
            structure(
                    issues,
                    "projectAssets",
                    "IMPORT_PROJECT_MEDIA_MISMATCH",
                    "Project media set does not match project content");
        }

        if (chinese.size() != english.size()) {
            structure(
                    issues,
                    "portfolioContent.zh-CN.projects",
                    "IMPORT_PROJECT_LOCALE_MISMATCH",
                    "Project locale counts do not match");
        }
        validateProjectBounds(chinese, "portfolioContent.zh-CN.projects", issues);
        validateProjectBounds(english, "portfolioContent.en.projects", issues);
        int common = Math.min(chinese.size(), english.size());
        boolean idMismatchReported = false;
        for (int index = 0; index < common; index++) {
            PortfolioImportV1.ProjectCopy zh = chinese.get(index);
            PortfolioImportV1.ProjectCopy en = english.get(index);
            if (!Objects.equals(zh.id(), en.id()) && !idMismatchReported) {
                structure(
                        issues,
                        "portfolioContent.zh-CN.projects[" + index + "].id",
                        "IMPORT_PROJECT_LOCALE_MISMATCH",
                        "Project locale order does not match");
                idMismatchReported = true;
            }
            if (!Objects.equals(zh.number(), en.number())) {
                structure(
                        issues,
                        "portfolioContent.zh-CN.projects[" + index + "].number",
                        "IMPORT_PROJECT_LOCALE_MISMATCH",
                        "Project numbers do not match");
            }
            if (zh.tags().size() != en.tags().size()) {
                structure(
                        issues,
                        "portfolioContent.zh-CN.projects[" + index + "].tags",
                        "IMPORT_TAG_LOCALE_MISMATCH",
                        "Project tag locale counts do not match");
            }
        }
        validateTagKeys(chinese, english, issues);
    }

    private static void validateProjectBounds(
            List<PortfolioImportV1.ProjectCopy> projects,
            String path,
            List<ImportIssue> issues) {
        for (int index = 0; index < projects.size(); index++) {
            PortfolioImportV1.ProjectCopy project = projects.get(index);
            if (!validExternalKey(project.id(), 96)) {
                invalid(issues, path + "[" + index + "].id");
            }
            if (!boundedText(project.number(), 16)) {
                invalid(issues, path + "[" + index + "].number");
            }
        }
    }

    private static void duplicateProjectIds(
            List<PortfolioImportV1.ProjectCopy> projects,
            String path,
            List<ImportIssue> issues) {
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < projects.size(); index++) {
            if (!seen.add(projects.get(index).id())) {
                duplicate(issues, path + "[" + index + "].id");
            }
        }
    }

    private static void validateTagKeys(
            List<PortfolioImportV1.ProjectCopy> chinese,
            List<PortfolioImportV1.ProjectCopy> english,
            List<ImportIssue> issues) {
        Map<String, TagDefinition> namesByKey = new LinkedHashMap<>();
        StableIdentityRegistry stableIds = new StableIdentityRegistry();
        for (int projectIndex = 0; projectIndex < english.size(); projectIndex++) {
            PortfolioImportV1.ProjectCopy enProject = english.get(projectIndex);
            PortfolioImportV1.ProjectCopy zhProject =
                    projectIndex < chinese.size() ? chinese.get(projectIndex) : null;
            Set<String> projectKeys = new HashSet<>();
            for (int tagIndex = 0; tagIndex < enProject.tags().size(); tagIndex++) {
                String englishName = enProject.tags().get(tagIndex);
                String enPath = "portfolioContent.en.projects[" + projectIndex + "].tags[" + tagIndex + "]";
                if (!englishName.isBlank()
                        && PortfolioImportSemantics.normalizeTagKey(englishName).isEmpty()) {
                    structure(
                            issues,
                            enPath,
                            "IMPORT_TAG_LOCALE_MISMATCH",
                            "English tag cannot produce a stable key");
                    continue;
                }
                String key = PortfolioImportSemantics.tagKey(
                        enProject.id(), tagIndex, englishName);
                if (key.length() > 96) {
                    invalid(issues, enPath);
                }
                if (!projectKeys.add(key)) {
                    duplicate(issues, enPath);
                }
                stableIds.register("tag", key, enPath, issues);

                if (zhProject == null || tagIndex >= zhProject.tags().size()) {
                    continue;
                }
                String chineseName = zhProject.tags().get(tagIndex);
                String zhPath = "portfolioContent.zh-CN.projects[" + projectIndex + "].tags[" + tagIndex + "]";
                TagDefinition definition =
                        new TagDefinition(projectIndex, new TagNames(chineseName, englishName));
                TagDefinition existing = namesByKey.putIfAbsent(key, definition);
                if (existing != null
                        && existing.projectIndex() != projectIndex
                        && !existing.names().equals(definition.names())) {
                    invalid(issues, zhPath);
                }
            }
        }
    }

    private static void validateRoadmapShape(
            PortfolioImportV1.Roadmap chinese,
            PortfolioImportV1.Roadmap english,
            List<ImportIssue> issues) {
        duplicateStageIds(
                chinese.stages(), "portfolioContent.zh-CN.roadmap.stages", issues);
        duplicateStageIds(english.stages(), "portfolioContent.en.roadmap.stages", issues);
        validateStageBounds(
                chinese.stages(), "portfolioContent.zh-CN.roadmap.stages", issues);
        validateStageBounds(
                english.stages(), "portfolioContent.en.roadmap.stages", issues);
        if (chinese.stages().size() != english.stages().size()) {
            structure(
                    issues,
                    "portfolioContent.zh-CN.roadmap.stages",
                    "IMPORT_ROADMAP_LOCALE_MISMATCH",
                    "Roadmap stage locale counts do not match");
        }
        int common = Math.min(chinese.stages().size(), english.stages().size());
        boolean idMismatchReported = false;
        for (int index = 0; index < common; index++) {
            PortfolioImportV1.RoadmapStage zh = chinese.stages().get(index);
            PortfolioImportV1.RoadmapStage en = english.stages().get(index);
            if (!Objects.equals(zh.id(), en.id()) && !idMismatchReported) {
                structure(
                        issues,
                        "portfolioContent.zh-CN.roadmap.stages[" + index + "].id",
                        "IMPORT_ROADMAP_LOCALE_MISMATCH",
                        "Roadmap stage locale order does not match");
                idMismatchReported = true;
            }
            if (!Objects.equals(zh.number(), en.number())) {
                structure(
                        issues,
                        "portfolioContent.zh-CN.roadmap.stages[" + index + "].number",
                        "IMPORT_ROADMAP_LOCALE_MISMATCH",
                        "Roadmap stage numbers do not match");
            }
            if (zh.outcomes().size() != en.outcomes().size()) {
                structure(
                        issues,
                        "portfolioContent.zh-CN.roadmap.stages[" + index + "].outcomes",
                        "IMPORT_ROADMAP_LOCALE_MISMATCH",
                        "Roadmap outcome locale counts do not match");
            }
        }
    }

    private static void duplicateStageIds(
            List<PortfolioImportV1.RoadmapStage> stages,
            String path,
            List<ImportIssue> issues) {
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < stages.size(); index++) {
            if (!seen.add(stages.get(index).id())) {
                duplicate(issues, path + "[" + index + "].id");
            }
        }
    }

    private static void validateStageBounds(
            List<PortfolioImportV1.RoadmapStage> stages,
            String path,
            List<ImportIssue> issues) {
        for (int index = 0; index < stages.size(); index++) {
            PortfolioImportV1.RoadmapStage stage = stages.get(index);
            if (!validExternalKey(stage.id(), 96)) {
                invalid(issues, path + "[" + index + "].id");
            }
            if (!boundedText(stage.number(), 16)) {
                invalid(issues, path + "[" + index + "].number");
            }
        }
    }

    private static void validateStableIdentities(
            PortfolioImportV1.PortfolioCopy chinese,
            PortfolioImportV1.PortfolioCopy english,
            List<ImportIssue> issues) {
        StableIdentityRegistry ids = new StableIdentityRegistry();
        for (int index = 0; index < english.projects().size(); index++) {
            ids.register(
                    "project",
                    english.projects().get(index).id(),
                    "portfolioContent.en.projects[" + index + "].id",
                    issues);
        }
        int facts = Math.min(chinese.about().facts().size(), english.about().facts().size());
        for (int index = 0; index < facts; index++) {
            ids.register(
                    "profile-fact",
                    "fact-" + index,
                    "portfolioContent.en.about.facts[" + index + "]",
                    issues);
        }
        int skills = Math.min(chinese.about().skills().size(), english.about().skills().size());
        for (int index = 0; index < skills; index++) {
            ids.register(
                    "profile-skill",
                    "skill-" + index,
                    "portfolioContent.en.about.skills[" + index + "]",
                    issues);
        }
        for (int stageIndex = 0; stageIndex < english.roadmap().stages().size(); stageIndex++) {
            PortfolioImportV1.RoadmapStage stage = english.roadmap().stages().get(stageIndex);
            String stagePath = "portfolioContent.en.roadmap.stages[" + stageIndex + "]";
            ids.register("roadmap-stage", stage.id(), stagePath + ".id", issues);
            for (int outcome = 0; outcome < stage.outcomes().size(); outcome++) {
                ids.register(
                        "roadmap-outcome",
                        stage.id() + ":" + outcome,
                        stagePath + ".outcomes[" + outcome + "]",
                        issues);
            }
        }
        for (String target : List.of("about", "work", "roadmap", "contact")) {
            ids.register("navigation", target, "portfolioContent.en.nav." + target, issues);
        }
        ids.register(
                "hero",
                "00000000-0000-0000-0000-000000000001",
                "portfolioContent.en.hero",
                issues);
    }

    private static void visitTranslationWarnings(
            PortfolioImportV1 payload, List<ImportIssue> issues) {
        warnBlank(issues, "identity.nameZh", payload.identity().nameZh());
        warnBlank(issues, "identity.nameEn", payload.identity().nameEn());
        visitAltWarnings(payload.heroAsset().alt(), "heroAsset.alt", issues);
        for (int index = 0; index < payload.projectAssets().size(); index++) {
            visitAltWarnings(
                    payload.projectAssets().get(index).alt(),
                    "projectAssets[" + index + "].alt",
                    issues);
        }
        visitCopyWarnings(
                payload.portfolioContent().get(LocaleCode.ZH_CN),
                "portfolioContent.zh-CN",
                issues);
        visitCopyWarnings(
                payload.portfolioContent().get(LocaleCode.EN),
                "portfolioContent.en",
                issues);
    }

    private static void visitAltWarnings(
            Map<LocaleCode, String> alt, String path, List<ImportIssue> issues) {
        if (alt == null) {
            return;
        }
        warnBlank(issues, path + ".zh-CN", alt.get(LocaleCode.ZH_CN));
        warnBlank(issues, path + ".en", alt.get(LocaleCode.EN));
    }

    private static void visitCopyWarnings(
            PortfolioImportV1.PortfolioCopy copy,
            String path,
            List<ImportIssue> issues) {
        if (copy == null) {
            return;
        }
        warnStrings(issues, path + ".seo", Map.of(
                "title", copy.seo().title(), "description", copy.seo().description()));
        warnStrings(issues, path + ".a11y", Map.of(
                "skip", copy.a11y().skip(),
                "primaryNav", copy.a11y().primaryNav(),
                "mobileNav", copy.a11y().mobileNav(),
                "openMenu", copy.a11y().openMenu(),
                "closeMenu", copy.a11y().closeMenu(),
                "language", copy.a11y().language(),
                "backToTop", copy.a11y().backToTop(),
                "projectTags", copy.a11y().projectTags()));
        warnStrings(issues, path + ".nav", Map.of(
                "about", copy.nav().about(),
                "work", copy.nav().work(),
                "roadmap", copy.nav().roadmap(),
                "contact", copy.nav().contact()));
        warnStrings(issues, path + ".hero", Map.ofEntries(
                Map.entry("eyebrow", copy.hero().eyebrow()),
                Map.entry("displayName", copy.hero().displayName()),
                Map.entry("secondaryName", copy.hero().secondaryName()),
                Map.entry("role", copy.hero().role()),
                Map.entry("headline", copy.hero().headline()),
                Map.entry("introduction", copy.hero().introduction()),
                Map.entry("availability", copy.hero().availability()),
                Map.entry("primaryCta", copy.hero().primaryCta()),
                Map.entry("secondaryCta", copy.hero().secondaryCta()),
                Map.entry("visualLabel", copy.hero().visualLabel()),
                Map.entry("stageLabel", copy.hero().stageLabel())));
        warnStrings(issues, path + ".about", Map.of(
                "label", copy.about().label(),
                "title", copy.about().title(),
                "statement", copy.about().statement(),
                "focusLabel", copy.about().focusLabel(),
                "focusTitle", copy.about().focusTitle(),
                "focusIntro", copy.about().focusIntro()));
        for (int index = 0; index < copy.about().facts().size(); index++) {
            PortfolioImportV1.Fact fact = copy.about().facts().get(index);
            warnBlank(issues, path + ".about.facts[" + index + "].label", fact.label());
            warnBlank(issues, path + ".about.facts[" + index + "].value", fact.value());
        }
        for (int index = 0; index < copy.about().skills().size(); index++) {
            PortfolioImportV1.ProfileSkill skill = copy.about().skills().get(index);
            warnBlank(issues, path + ".about.skills[" + index + "].name", skill.name());
            warnBlank(issues, path + ".about.skills[" + index + "].status", skill.status());
        }
        warnStrings(issues, path + ".work", Map.of(
                "label", copy.work().label(),
                "title", copy.work().title(),
                "introduction", copy.work().introduction(),
                "imageNotice", copy.work().imageNotice(),
                "openSlotLabel", copy.work().openSlotLabel(),
                "openSlotTitle", copy.work().openSlotTitle(),
                "openSlotText", copy.work().openSlotText(),
                "openSlotMeta", copy.work().openSlotMeta()));
        for (int index = 0; index < copy.projects().size(); index++) {
            PortfolioImportV1.ProjectCopy project = copy.projects().get(index);
            String projectPath = path + ".projects[" + index + "]";
            warnBlank(issues, projectPath + ".number", project.number());
            warnBlank(issues, projectPath + ".status", project.status());
            warnBlank(issues, projectPath + ".eyebrow", project.eyebrow());
            warnBlank(issues, projectPath + ".title", project.title());
            warnBlank(issues, projectPath + ".summary", project.summary());
            for (int tag = 0; tag < project.tags().size(); tag++) {
                warnBlank(issues, projectPath + ".tags[" + tag + "]", project.tags().get(tag));
            }
        }
        warnBlank(issues, path + ".roadmap.label", copy.roadmap().label());
        warnBlank(issues, path + ".roadmap.title", copy.roadmap().title());
        warnBlank(issues, path + ".roadmap.introduction", copy.roadmap().introduction());
        for (int index = 0; index < copy.roadmap().stages().size(); index++) {
            PortfolioImportV1.RoadmapStage stage = copy.roadmap().stages().get(index);
            String stagePath = path + ".roadmap.stages[" + index + "]";
            warnBlank(issues, stagePath + ".number", stage.number());
            warnBlank(issues, stagePath + ".period", stage.period());
            warnBlank(issues, stagePath + ".title", stage.title());
            warnBlank(issues, stagePath + ".summary", stage.summary());
            for (int outcome = 0; outcome < stage.outcomes().size(); outcome++) {
                warnBlank(
                        issues,
                        stagePath + ".outcomes[" + outcome + "]",
                        stage.outcomes().get(outcome));
            }
        }
        warnBlank(issues, path + ".contact.label", copy.contact().label());
        warnBlank(issues, path + ".contact.title", copy.contact().title());
        warnBlank(issues, path + ".contact.introduction", copy.contact().introduction());
        warnBlank(issues, path + ".contact.emailLabel", copy.contact().emailLabel());
        warnBlank(issues, path + ".contact.workCta", copy.contact().workCta());
        warnBlank(issues, path + ".contact.roadmapCta", copy.contact().roadmapCta());
        warnBlank(issues, path + ".contact.footerNote", copy.contact().footerNote());
    }

    private static void warnStrings(
            List<ImportIssue> issues, String path, Map<String, String> values) {
        values.forEach((field, value) -> warnBlank(issues, path + "." + field, value));
    }

    private static void warnBlank(List<ImportIssue> issues, String path, String value) {
        if (value != null && value.isBlank()) {
            warning(
                    issues,
                    path,
                    "IMPORT_TRANSLATION_INCOMPLETE",
                    "Translation is incomplete");
        }
    }

    private static void visitFixedPlaceholders(
            PortfolioImportV1 payload, List<ImportIssue> issues) {
        placeholder(
                issues,
                "identity.email",
                payload.identity().email(),
                "your-email@example.com");
        PortfolioImportV1.PortfolioCopy chinese =
                payload.portfolioContent().get(LocaleCode.ZH_CN);
        PortfolioImportV1.PortfolioCopy english =
                payload.portfolioContent().get(LocaleCode.EN);
        if (chinese != null) {
            placeholder(
                    issues,
                    "portfolioContent.zh-CN.hero.visualLabel",
                    chinese.hero().visualLabel(),
                    "视觉概念图 / 之后替换为本人 UE 截图");
            placeholder(
                    issues,
                    "portfolioContent.zh-CN.work.imageNotice",
                    chinese.work().imageNotice(),
                    "概念占位图，之后替换为本人 UE 截图");
            placeholder(
                    issues,
                    "portfolioContent.zh-CN.contact.emailLabel",
                    chinese.contact().emailLabel(),
                    "联系邮箱（待替换）");
        }
        if (english != null) {
            placeholder(
                    issues,
                    "portfolioContent.en.hero.visualLabel",
                    english.hero().visualLabel(),
                    "Visual concept image / replace with my own UE capture");
            placeholder(
                    issues,
                    "portfolioContent.en.work.imageNotice",
                    english.work().imageNotice(),
                    "Concept placeholder - to be replaced with my own UE capture");
            placeholder(
                    issues,
                    "portfolioContent.en.contact.emailLabel",
                    english.contact().emailLabel(),
                    "Email placeholder");
        }
    }

    private static void placeholder(
            List<ImportIssue> issues, String path, String actual, String expected) {
        if (expected.equals(actual)) {
            warning(
                    issues,
                    path,
                    "PLACEHOLDER_CONTENT_PRESENT",
                    "Placeholder content is still present");
        }
    }

    private static void validateLocaleSet(
            Map<LocaleCode, ?> values, String path, List<ImportIssue> issues) {
        if (values == null || !values.keySet().equals(REQUIRED_LOCALES)) {
            structure(
                    issues,
                    path,
                    "IMPORT_LOCALE_SET_INVALID",
                    "Locale set is invalid");
        }
    }

    private boolean validAsset(Path assetRoot, String publicPath) {
        if (assetRoot == null
                || publicPath == null
                || !publicPath.equals(publicPath.trim())
                || hasControl(publicPath)
                || !PUBLIC_ASSET.matcher(publicPath).matches()) {
            return false;
        }
        String filename = publicPath.substring("/images/".length());
        if (filename.equals(".") || filename.equals("..") || !supportedExtension(filename)) {
            return false;
        }
        try {
            Path root = assetRoot.toRealPath();
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            Path relative = Path.of(publicPath.substring(1));
            if (relative.isAbsolute()) {
                return false;
            }
            Path current = root;
            int segment = 0;
            int segments = relative.getNameCount();
            for (Path name : relative) {
                current = current.resolve(name);
                BasicFileAttributes attributes = Files.readAttributes(
                        current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther()) {
                    return false;
                }
                Path noFollow = current.toRealPath(LinkOption.NOFOLLOW_LINKS);
                Path followed = current.toRealPath();
                if (!noFollow.equals(followed) || !followed.startsWith(root)) {
                    return false;
                }
                segment++;
                if (segment < segments && !attributes.isDirectory()) {
                    return false;
                }
                if (segment == segments
                        && (!attributes.isRegularFile()
                                || !Files.isReadable(current)
                                || attributes.size() <= 0)) {
                    return false;
                }
            }
            if (segment != segments) {
                return false;
            }
            BasicFileAttributes attributes = Files.readAttributes(
                    current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            mediaFileInspector.validateExisting(
                    current, declaredMime(filename), attributes.size());
            return true;
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    private static String declaredMime(String filename) {
        String extension = filename.substring(filename.lastIndexOf('.') + 1)
                .toLowerCase(Locale.ROOT);
        return switch (extension) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "pdf" -> "application/pdf";
            default -> throw new IllegalArgumentException("unsupported media extension");
        };
    }

    private static boolean supportedExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0
                && dot + 1 < filename.length()
                && SUPPORTED_EXTENSIONS.contains(
                        filename.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static boolean validSourceUrl(URI sourceUrl) {
        if (sourceUrl == null) {
            return false;
        }
        try {
            String value = sourceUrl.toASCIIString();
            if (!value.startsWith("https://")) {
                return false;
            }
            StrictHttpsSourceUrl.requireValidNullable(value);
            return true;
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static boolean validExternalKey(String value, int maximum) {
        return boundedText(value, maximum) && EXTERNAL_KEY.matcher(value).matches();
    }

    private static boolean validEmail(String value, int maximum) {
        return value != null
                && value.length() <= maximum
                && value.equals(value.trim())
                && !hasControl(value)
                && EMAIL.matcher(value).matches();
    }

    private static boolean boundedText(String value, int maximum) {
        return value != null
                && !value.isBlank()
                && value.length() <= maximum
                && value.equals(value.trim())
                && !hasControl(value);
    }

    private static boolean hasControl(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }

    private static void invalid(List<ImportIssue> issues, String path) {
        structure(issues, path, "IMPORT_JSON_INVALID", "Value is invalid");
    }

    private static void duplicate(List<ImportIssue> issues, String path) {
        structure(issues, path, "IMPORT_DUPLICATE_ID", "Identifier is duplicated");
    }

    private static void structure(
            List<ImportIssue> issues, String path, String code, String message) {
        issues.add(new ImportIssue(
                ImportIssue.Severity.STRUCTURE_ERROR, path, code, message));
    }

    private static void warning(
            List<ImportIssue> issues, String path, String code, String message) {
        issues.add(new ImportIssue(
                ImportIssue.Severity.PUBLISH_WARNING, path, code, message));
    }

    private static List<ImportIssue> sortedIssues(List<ImportIssue> issues) {
        List<ImportIssue> result = new ArrayList<>(new LinkedHashSet<>(issues));
        result.sort(ISSUE_ORDER);
        return List.copyOf(result);
    }

    private record MediaMetadata(
            String chineseAlt, String englishAlt, String credit, String sourceUrl) {}

    private record TagNames(String chinese, String english) {}

    private record TagDefinition(int projectIndex, TagNames names) {}

    private static final class StableIdentityRegistry {
        private final Map<String, Map<UUID, String>> values = new HashMap<>();

        private void register(
                String namespace,
                String externalKey,
                String path,
                List<ImportIssue> issues) {
            UUID id = PortfolioImportSemantics.stableId(namespace, externalKey);
            String previous = values
                    .computeIfAbsent(namespace, ignored -> new HashMap<>())
                    .putIfAbsent(id, externalKey);
            if (previous != null && !previous.equals(externalKey)) {
                duplicate(issues, path);
            }
        }
    }
}
