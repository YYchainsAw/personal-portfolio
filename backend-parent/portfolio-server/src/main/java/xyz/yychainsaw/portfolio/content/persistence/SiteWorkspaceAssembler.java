package xyz.yychainsaw.portfolio.content.persistence;

import java.net.URI;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.content.api.SiteWorkspaceDto;
import xyz.yychainsaw.portfolio.content.persistence.mybatis.SiteWorkspaceMapper;

@Component
final class SiteWorkspaceAssembler {
    SiteWorkspaceDto load(SiteWorkspaceMapper mapper, UUID siteId) {
        Map<String, Object> root = mapper.selectProfile(siteId);
        if (root == null) {
            throw ContentPersistenceErrors.siteMissing();
        }

        Map<LocaleCode, SiteWorkspaceDto.IdentityCopy> identity = localized(
                mapper, SiteTable.PROFILE_TRANSLATION, "identity", row ->
                        new SiteWorkspaceDto.IdentityCopy(text(row, "display_name"), text(row, "secondary_name")));
        Map<LocaleCode, SiteWorkspaceDto.SeoCopy> seo = localized(
                mapper, SiteTable.SEO_TRANSLATION, "seo", row ->
                        new SiteWorkspaceDto.SeoCopy(text(row, "title"), text(row, "description")));
        Map<LocaleCode, SiteWorkspaceDto.AccessibilityCopy> accessibility = localized(
                mapper, SiteTable.ACCESSIBILITY_TRANSLATION, "accessibility", row ->
                        new SiteWorkspaceDto.AccessibilityCopy(
                                text(row, "skip_text"),
                                text(row, "primary_nav"),
                                text(row, "mobile_nav"),
                                text(row, "open_menu"),
                                text(row, "close_menu"),
                                text(row, "language_text"),
                                text(row, "back_to_top"),
                                text(row, "project_tags")));

        Map<UUID, Map<LocaleCode, String>> navigationLabels = localizedStringsByParent(
                mapper, SiteTable.NAVIGATION_TRANSLATION, "navigation_item_id", "label", "navigation.labels");
        List<SiteWorkspaceDto.NavigationItem> navigation = rows(mapper, SiteTable.NAVIGATION).stream()
                .map(row -> new SiteWorkspaceDto.NavigationItem(
                        uuid(row, "id"),
                        text(row, "target"),
                        integer(row, "sort_order"),
                        bool(row, "visible"),
                        navigationLabels.getOrDefault(uuid(row, "id"), Map.of())))
                .toList();

        SiteWorkspaceDto.Hero hero = loadHero(mapper);
        Map<LocaleCode, SiteWorkspaceDto.AboutCopy> about = localized(
                mapper, SiteTable.ABOUT_TRANSLATION, "about", row ->
                        new SiteWorkspaceDto.AboutCopy(
                                text(row, "label"), text(row, "title"), text(row, "statement"),
                                text(row, "focus_label"), text(row, "focus_title"), text(row, "focus_intro")));

        Map<UUID, Map<LocaleCode, SiteWorkspaceDto.LabelValueCopy>> factCopy = localizedByParent(
                mapper,
                SiteTable.PROFILE_FACT_TRANSLATION,
                "fact_id",
                "facts.copy",
                row -> new SiteWorkspaceDto.LabelValueCopy(text(row, "label"), text(row, "value_text")));
        List<SiteWorkspaceDto.ProfileFact> facts = rows(mapper, SiteTable.PROFILE_FACT).stream()
                .map(row -> new SiteWorkspaceDto.ProfileFact(
                        uuid(row, "id"), text(row, "external_key"), integer(row, "sort_order"),
                        factCopy.getOrDefault(uuid(row, "id"), Map.of())))
                .toList();

        Map<UUID, Map<LocaleCode, SiteWorkspaceDto.SkillStatusCopy>> profileSkillCopy = localizedByParent(
                mapper,
                SiteTable.PROFILE_SKILL_TRANSLATION,
                "profile_skill_id",
                "profileSkills.copy",
                row -> new SiteWorkspaceDto.SkillStatusCopy(text(row, "name"), text(row, "status_text")));
        List<SiteWorkspaceDto.ProfileSkill> profileSkills = rows(mapper, SiteTable.PROFILE_SKILL).stream()
                .map(row -> new SiteWorkspaceDto.ProfileSkill(
                        uuid(row, "id"), text(row, "external_key"), integer(row, "sort_order"),
                        profileSkillCopy.getOrDefault(uuid(row, "id"), Map.of())))
                .toList();

        Map<LocaleCode, SiteWorkspaceDto.WorkCopy> work = localized(
                mapper, SiteTable.WORK_TRANSLATION, "work", row ->
                        new SiteWorkspaceDto.WorkCopy(
                                text(row, "label"), text(row, "title"), text(row, "introduction"),
                                text(row, "image_notice"), text(row, "open_slot_label"),
                                text(row, "open_slot_title"), text(row, "open_slot_text"),
                                text(row, "open_slot_meta")));
        SiteWorkspaceDto.Roadmap roadmap = loadRoadmap(mapper);
        Map<LocaleCode, SiteWorkspaceDto.ContactCopy> contact = localized(
                mapper, SiteTable.CONTACT_TRANSLATION, "contact", row ->
                        new SiteWorkspaceDto.ContactCopy(
                                text(row, "label"), text(row, "title"), text(row, "introduction"),
                                text(row, "email_label"), text(row, "work_cta"),
                                text(row, "roadmap_cta"), text(row, "footer_note")));
        Map<LocaleCode, SiteWorkspaceDto.PrivacyCopy> privacy = localized(
                mapper, SiteTable.PRIVACY_TRANSLATION, "privacy", row ->
                        new SiteWorkspaceDto.PrivacyCopy(text(row, "title"), text(row, "body_markdown")));
        List<SiteWorkspaceDto.SocialLink> socialLinks = rows(mapper, SiteTable.SOCIAL_LINK).stream()
                .map(row -> new SiteWorkspaceDto.SocialLink(
                        uuid(row, "id"), text(row, "platform"), uri(row, "url"),
                        integer(row, "sort_order"), bool(row, "visible")))
                .toList();
        List<SiteWorkspaceDto.ResumeDocument> resumes = rows(mapper, SiteTable.RESUME_DOCUMENT).stream()
                .map(row -> new SiteWorkspaceDto.ResumeDocument(
                        uuid(row, "id"), locale(row), uuid(row, "media_asset_id"),
                        text(row, "version_label"), bool(row, "is_current"), localDate(row, "document_date")))
                .toList();

        return new SiteWorkspaceDto(
                uuid(root, "id"), longValue(root, "version"), text(root, "monogram"), text(root, "email"),
                identity, seo, accessibility, navigation, hero, about, facts, profileSkills, work,
                roadmap, contact, privacy, socialLinks, resumes);
    }

    void validate(SiteWorkspaceDto workspace) {
        validateHeroMedia(workspace.hero());
        validateNestedIdentity(workspace);
    }

    void replaceChildren(SiteWorkspaceMapper mapper, SiteWorkspaceDto workspace) {
        validate(workspace);
        deleteChildren(mapper, workspace.siteId());
        insert(mapper, SiteTable.PROFILE_TRANSLATION, localizedRows(
                workspace.siteId(), workspace.identity(), value -> row(
                        "displayName", value.displayName(), "secondaryName", value.secondaryName())));
        insert(mapper, SiteTable.SEO_TRANSLATION, localizedRows(
                workspace.siteId(), workspace.seo(), value -> row(
                        "title", value.title(), "description", value.description())));
        insert(mapper, SiteTable.ACCESSIBILITY_TRANSLATION, localizedRows(
                workspace.siteId(), workspace.accessibility(), value -> row(
                        "skipText", value.skip(), "primaryNav", value.primaryNav(),
                        "mobileNav", value.mobileNav(), "openMenu", value.openMenu(),
                        "closeMenu", value.closeMenu(), "languageText", value.language(),
                        "backToTop", value.backToTop(), "projectTags", value.projectTags())));

        List<Map<String, Object>> navigation = new ArrayList<>();
        List<Map<String, Object>> navigationTranslations = new ArrayList<>();
        for (SiteWorkspaceDto.NavigationItem item : workspace.navigation()) {
            navigation.add(row("id", item.id(), "siteId", workspace.siteId(), "target", item.target(),
                    "sortOrder", item.sortOrder(), "visible", item.visible()));
            item.labels().forEach((locale, label) -> navigationTranslations.add(row(
                    "navigationItemId", item.id(), "locale", locale.value(), "label", label)));
        }
        insert(mapper, SiteTable.NAVIGATION, navigation);
        insert(mapper, SiteTable.NAVIGATION_TRANSLATION, navigationTranslations);
        insertHero(mapper, workspace);

        insert(mapper, SiteTable.ABOUT_TRANSLATION, localizedRows(
                workspace.siteId(), workspace.about(), value -> row(
                        "label", value.label(), "title", value.title(), "statement", value.statement(),
                        "focusLabel", value.focusLabel(), "focusTitle", value.focusTitle(),
                        "focusIntro", value.focusIntro())));
        List<Map<String, Object>> facts = new ArrayList<>();
        List<Map<String, Object>> factTranslations = new ArrayList<>();
        for (SiteWorkspaceDto.ProfileFact fact : workspace.facts()) {
            facts.add(row("id", fact.id(), "siteId", workspace.siteId(), "externalKey", fact.externalKey(),
                    "sortOrder", fact.sortOrder()));
            fact.copy().forEach((locale, value) -> factTranslations.add(row(
                    "factId", fact.id(), "locale", locale.value(), "label", value.label(),
                    "valueText", value.value())));
        }
        insert(mapper, SiteTable.PROFILE_FACT, facts);
        insert(mapper, SiteTable.PROFILE_FACT_TRANSLATION, factTranslations);

        List<Map<String, Object>> skills = new ArrayList<>();
        List<Map<String, Object>> skillTranslations = new ArrayList<>();
        for (SiteWorkspaceDto.ProfileSkill skill : workspace.profileSkills()) {
            skills.add(row("id", skill.id(), "siteId", workspace.siteId(), "externalKey", skill.externalKey(),
                    "sortOrder", skill.sortOrder()));
            skill.copy().forEach((locale, value) -> skillTranslations.add(row(
                    "profileSkillId", skill.id(), "locale", locale.value(), "name", value.name(),
                    "statusText", value.status())));
        }
        insert(mapper, SiteTable.PROFILE_SKILL, skills);
        insert(mapper, SiteTable.PROFILE_SKILL_TRANSLATION, skillTranslations);

        insert(mapper, SiteTable.WORK_TRANSLATION, localizedRows(
                workspace.siteId(), workspace.work(), value -> row(
                        "label", value.label(), "title", value.title(), "introduction", value.introduction(),
                        "imageNotice", value.imageNotice(), "openSlotLabel", value.openSlotLabel(),
                        "openSlotTitle", value.openSlotTitle(), "openSlotText", value.openSlotText(),
                        "openSlotMeta", value.openSlotMeta())));
        insertRoadmap(mapper, workspace);
        insert(mapper, SiteTable.CONTACT_TRANSLATION, localizedRows(
                workspace.siteId(), workspace.contact(), value -> row(
                        "label", value.label(), "title", value.title(), "introduction", value.introduction(),
                        "emailLabel", value.emailLabel(), "workCta", value.workCta(),
                        "roadmapCta", value.roadmapCta(), "footerNote", value.footerNote())));
        insert(mapper, SiteTable.PRIVACY_TRANSLATION, localizedRows(
                workspace.siteId(), workspace.privacy(), value -> row(
                        "title", value.title(), "bodyMarkdown", value.bodyMarkdown())));

        insert(mapper, SiteTable.SOCIAL_LINK, workspace.socialLinks().stream()
                .map(link -> row("id", link.id(), "siteId", workspace.siteId(), "platform", link.platform(),
                        "url", link.url().toString(), "sortOrder", link.sortOrder(), "visible", link.visible()))
                .toList());
        insert(mapper, SiteTable.RESUME_DOCUMENT, workspace.resumes().stream()
                .map(resume -> row("id", resume.id(), "siteId", workspace.siteId(),
                        "locale", resume.locale().value(), "mediaAssetId", resume.mediaAssetId(),
                        "versionLabel", resume.versionLabel(), "current", resume.current(),
                        "documentDate", resume.documentDate()))
                .toList());
    }

    private SiteWorkspaceDto.Hero loadHero(SiteWorkspaceMapper mapper) {
        List<Map<String, Object>> heroes = rows(mapper, SiteTable.HERO);
        if (heroes.isEmpty()) {
            return null;
        }
        if (heroes.size() != 1) {
            throw ContentPersistenceErrors.corrupt("hero");
        }
        Map<String, Object> hero = heroes.get(0);
        UUID heroId = uuid(hero, "id");
        Map<LocaleCode, SiteWorkspaceDto.HeroCopy> copy = localized(
                mapper, SiteTable.HERO_TRANSLATION, "hero.copy", row ->
                        new SiteWorkspaceDto.HeroCopy(
                                text(row, "eyebrow"), text(row, "display_name"), text(row, "secondary_name"),
                                text(row, "role_text"), text(row, "headline"), text(row, "introduction"),
                                text(row, "availability"), text(row, "primary_cta"),
                                text(row, "secondary_cta"), text(row, "visual_label"), text(row, "stage_label")));
        List<Map<String, Object>> mediaRows = rows(mapper, SiteTable.HERO_MEDIA);
        if (mediaRows.size() > 1) {
            throw ContentPersistenceErrors.corrupt("hero.media");
        }
        if (mediaRows.isEmpty()) {
            return new SiteWorkspaceDto.Hero(heroId, longValue(hero, "version"), null, null, null, null, copy);
        }
        Map<String, Object> media = mediaRows.get(0);
        return new SiteWorkspaceDto.Hero(
                heroId,
                longValue(hero, "version"),
                uuid(media, "media_asset_id"),
                text(media, "object_position"),
                text(media, "credit"),
                uri(media, "source_url"),
                copy);
    }

    private SiteWorkspaceDto.Roadmap loadRoadmap(SiteWorkspaceMapper mapper) {
        Map<LocaleCode, SiteWorkspaceDto.RoadmapHeaderCopy> header = localized(
                mapper, SiteTable.ROADMAP_HEADER_TRANSLATION, "roadmap.header", row ->
                        new SiteWorkspaceDto.RoadmapHeaderCopy(
                                text(row, "label"), text(row, "title"), text(row, "introduction")));
        Map<UUID, Map<LocaleCode, SiteWorkspaceDto.RoadmapStageCopy>> stageCopy = localizedByParent(
                mapper, SiteTable.ROADMAP_STAGE_TRANSLATION, "stage_id", "roadmap.stages.copy", row ->
                        new SiteWorkspaceDto.RoadmapStageCopy(
                                text(row, "period"), text(row, "title"), text(row, "summary")));
        Map<UUID, Map<LocaleCode, String>> outcomeText = localizedStringsByParent(
                mapper, SiteTable.ROADMAP_OUTCOME_TRANSLATION, "outcome_id", "outcome_text",
                "roadmap.stages.outcomes.text");
        Map<UUID, List<SiteWorkspaceDto.RoadmapOutcome>> outcomesByStage = new LinkedHashMap<>();
        for (Map<String, Object> row : rows(mapper, SiteTable.ROADMAP_OUTCOME)) {
            UUID stageId = uuid(row, "stage_id");
            UUID outcomeId = uuid(row, "id");
            outcomesByStage.computeIfAbsent(stageId, ignored -> new ArrayList<>()).add(
                    new SiteWorkspaceDto.RoadmapOutcome(
                            outcomeId,
                            integer(row, "sort_order"),
                            outcomeText.getOrDefault(outcomeId, Map.of())));
        }
        List<SiteWorkspaceDto.RoadmapStage> stages = rows(mapper, SiteTable.ROADMAP_STAGE).stream()
                .map(row -> {
                    UUID stageId = uuid(row, "id");
                    return new SiteWorkspaceDto.RoadmapStage(
                            stageId,
                            text(row, "external_key"),
                            text(row, "number_label"),
                            integer(row, "sort_order"),
                            bool(row, "visible"),
                            stageCopy.getOrDefault(stageId, Map.of()),
                            outcomesByStage.getOrDefault(stageId, List.of()));
                })
                .toList();
        return new SiteWorkspaceDto.Roadmap(header, stages);
    }

    private void insertHero(SiteWorkspaceMapper mapper, SiteWorkspaceDto workspace) {
        SiteWorkspaceDto.Hero hero = workspace.hero();
        if (hero == null) {
            return;
        }
        insert(mapper, SiteTable.HERO, List.of(row(
                "id", hero.id(), "siteId", workspace.siteId(), "version", hero.version())));
        List<Map<String, Object>> translations = new ArrayList<>();
        hero.copy().forEach((locale, value) -> translations.add(row(
                "heroId", hero.id(), "locale", locale.value(), "eyebrow", value.eyebrow(),
                "displayName", value.displayName(), "secondaryName", value.secondaryName(),
                "roleText", value.role(), "headline", value.headline(),
                "introduction", value.introduction(), "availability", value.availability(),
                "primaryCta", value.primaryCta(), "secondaryCta", value.secondaryCta(),
                "visualLabel", value.visualLabel(), "stageLabel", value.stageLabel())));
        insert(mapper, SiteTable.HERO_TRANSLATION, translations);
        if (hero.mediaAssetId() != null) {
            insert(mapper, SiteTable.HERO_MEDIA, List.of(row(
                    "heroId", hero.id(), "mediaAssetId", hero.mediaAssetId(),
                    "objectPosition", hero.objectPosition(), "credit", hero.credit(),
                    "sourceUrl", hero.sourceUrl().toString())));
        }
    }

    private void insertRoadmap(SiteWorkspaceMapper mapper, SiteWorkspaceDto workspace) {
        if (workspace.roadmap() == null) {
            return;
        }
        insert(mapper, SiteTable.ROADMAP_HEADER_TRANSLATION, localizedRows(
                workspace.siteId(), workspace.roadmap().header(), value -> row(
                        "label", value.label(), "title", value.title(), "introduction", value.introduction())));
        List<Map<String, Object>> stages = new ArrayList<>();
        List<Map<String, Object>> stageTranslations = new ArrayList<>();
        List<Map<String, Object>> outcomes = new ArrayList<>();
        List<Map<String, Object>> outcomeTranslations = new ArrayList<>();
        for (SiteWorkspaceDto.RoadmapStage stage : workspace.roadmap().stages()) {
            stages.add(row("id", stage.id(), "siteId", workspace.siteId(),
                    "externalKey", stage.externalKey(), "numberLabel", stage.number(),
                    "sortOrder", stage.sortOrder(), "visible", stage.visible()));
            stage.copy().forEach((locale, value) -> stageTranslations.add(row(
                    "stageId", stage.id(), "locale", locale.value(), "period", value.period(),
                    "title", value.title(), "summary", value.summary())));
            for (SiteWorkspaceDto.RoadmapOutcome outcome : stage.outcomes()) {
                outcomes.add(row("id", outcome.id(), "stageId", stage.id(), "sortOrder", outcome.sortOrder()));
                outcome.text().forEach((locale, value) -> outcomeTranslations.add(row(
                        "outcomeId", outcome.id(), "locale", locale.value(), "outcomeText", value)));
            }
        }
        insert(mapper, SiteTable.ROADMAP_STAGE, stages);
        insert(mapper, SiteTable.ROADMAP_STAGE_TRANSLATION, stageTranslations);
        insert(mapper, SiteTable.ROADMAP_OUTCOME, outcomes);
        insert(mapper, SiteTable.ROADMAP_OUTCOME_TRANSLATION, outcomeTranslations);
    }

    private void deleteChildren(SiteWorkspaceMapper mapper, UUID siteId) {
        SiteTable[] order = {
            SiteTable.RESUME_DOCUMENT,
            SiteTable.HERO_MEDIA,
            SiteTable.HERO_TRANSLATION,
            SiteTable.HERO,
            SiteTable.NAVIGATION_TRANSLATION,
            SiteTable.NAVIGATION,
            SiteTable.PROFILE_FACT_TRANSLATION,
            SiteTable.PROFILE_FACT,
            SiteTable.PROFILE_SKILL_TRANSLATION,
            SiteTable.PROFILE_SKILL,
            SiteTable.ROADMAP_OUTCOME_TRANSLATION,
            SiteTable.ROADMAP_OUTCOME,
            SiteTable.ROADMAP_STAGE_TRANSLATION,
            SiteTable.ROADMAP_STAGE,
            SiteTable.ROADMAP_HEADER_TRANSLATION,
            SiteTable.SOCIAL_LINK,
            SiteTable.PRIVACY_TRANSLATION,
            SiteTable.CONTACT_TRANSLATION,
            SiteTable.WORK_TRANSLATION,
            SiteTable.ABOUT_TRANSLATION,
            SiteTable.ACCESSIBILITY_TRANSLATION,
            SiteTable.SEO_TRANSLATION,
            SiteTable.PROFILE_TRANSLATION
        };
        for (SiteTable table : order) {
            mapper.deleteOwnedRows(table.name(), siteId);
        }
    }

    private void validateHeroMedia(SiteWorkspaceDto.Hero hero) {
        if (hero == null) {
            return;
        }
        boolean asset = hero.mediaAssetId() != null;
        boolean position = hero.objectPosition() != null;
        boolean credit = hero.credit() != null;
        boolean source = hero.sourceUrl() != null;
        if (!(asset == position && asset == credit && asset == source)) {
            throw ContentPersistenceErrors.invalid(
                    "hero.media", "mediaAssetId, objectPosition, credit and sourceUrl must be all null or all present");
        }
    }

    private void validateNestedIdentity(SiteWorkspaceDto workspace) {
        requireUnique(
                workspace.navigation().stream()
                        .map(SiteWorkspaceDto.NavigationItem::id)
                        .toList(),
                "navigation.id");
        requireUnique(
                workspace.socialLinks().stream()
                        .map(SiteWorkspaceDto.SocialLink::id)
                        .toList(),
                "socialLinks.id");
        requireUnique(
                workspace.facts().stream()
                        .map(SiteWorkspaceDto.ProfileFact::id)
                        .toList(),
                "facts.id");
        requireUnique(
                workspace.profileSkills().stream()
                        .map(SiteWorkspaceDto.ProfileSkill::id)
                        .toList(),
                "profileSkills.id");
        if (workspace.roadmap() != null) {
            requireUnique(
                    workspace.roadmap().stages().stream()
                            .map(SiteWorkspaceDto.RoadmapStage::id)
                            .toList(),
                    "roadmap.stages.id");
            requireUnique(
                    workspace.roadmap().stages().stream()
                            .flatMap(stage -> stage.outcomes().stream())
                            .map(SiteWorkspaceDto.RoadmapOutcome::id)
                            .toList(),
                    "roadmap.stages.outcomes.id");
        }
        requireUnique(
                workspace.resumes().stream()
                        .map(SiteWorkspaceDto.ResumeDocument::id)
                        .toList(),
                "resumes.id");
    }

    private void requireUnique(List<UUID> ids, String field) {
        if (ids.stream().anyMatch(java.util.Objects::isNull)) {
            throw ContentPersistenceErrors.invalid(
                    field,
                    "identities must be non-null");
        }
        Set<UUID> unique = new HashSet<>(ids);
        if (unique.size() != ids.size()) {
            throw ContentPersistenceErrors.invalid(
                    field,
                    "identities must be unique within their collection");
        }
    }

    private <T> Map<LocaleCode, T> localized(
            SiteWorkspaceMapper mapper,
            SiteTable table,
            String field,
            Function<Map<String, Object>, T> factory) {
        EnumMap<LocaleCode, T> result = new EnumMap<>(LocaleCode.class);
        for (Map<String, Object> row : rows(mapper, table)) {
            LocaleCode locale = locale(row);
            if (result.put(locale, factory.apply(row)) != null) {
                throw ContentPersistenceErrors.corrupt(field);
            }
        }
        return result;
    }

    private <T> Map<UUID, Map<LocaleCode, T>> localizedByParent(
            SiteWorkspaceMapper mapper,
            SiteTable table,
            String parentKey,
            String field,
            Function<Map<String, Object>, T> factory) {
        Map<UUID, Map<LocaleCode, T>> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows(mapper, table)) {
            UUID parent = uuid(row, parentKey);
            Map<LocaleCode, T> values = result.computeIfAbsent(parent, ignored -> new EnumMap<>(LocaleCode.class));
            if (values.put(locale(row), factory.apply(row)) != null) {
                throw ContentPersistenceErrors.corrupt(field);
            }
        }
        return result;
    }

    private Map<UUID, Map<LocaleCode, String>> localizedStringsByParent(
            SiteWorkspaceMapper mapper,
            SiteTable table,
            String parentKey,
            String valueKey,
            String field) {
        return localizedByParent(mapper, table, parentKey, field, row -> text(row, valueKey));
    }

    private <T> List<Map<String, Object>> localizedRows(
            UUID siteId,
            Map<LocaleCode, T> values,
            Function<T, Map<String, Object>> factory) {
        List<Map<String, Object>> result = new ArrayList<>();
        values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    Map<String, Object> row = new LinkedHashMap<>(factory.apply(entry.getValue()));
                    row.put("siteId", siteId);
                    row.put("locale", entry.getKey().value());
                    result.add(row);
                });
        return result;
    }

    private List<Map<String, Object>> rows(SiteWorkspaceMapper mapper, SiteTable table) {
        return mapper.selectRows(table.name(), SiteWorkspaceDto.SITE_ID);
    }

    private void insert(SiteWorkspaceMapper mapper, SiteTable table, List<Map<String, Object>> rows) {
        if (!rows.isEmpty()) {
            mapper.insertRows(table.name(), rows);
        }
    }

    private static Map<String, Object> row(Object... values) {
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            row.put((String) values[index], values[index + 1]);
        }
        return row;
    }

    private static LocaleCode locale(Map<String, Object> row) {
        return LocaleCode.from(text(row, "locale"));
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : value.toString();
    }

    private static UUID uuid(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof UUID id ? id : UUID.fromString(value.toString());
    }

    private static long longValue(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private static int integer(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).intValue();
    }

    private static boolean bool(Map<String, Object> row, String key) {
        return (Boolean) row.get(key);
    }

    private static URI uri(Map<String, Object> row, String key) {
        String value = text(row, key);
        return value == null ? null : URI.create(value);
    }

    private static LocalDate localDate(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof LocalDate date) {
            return date;
        }
        if (value instanceof Date date) {
            return date.toLocalDate();
        }
        return LocalDate.parse(value.toString());
    }
}
