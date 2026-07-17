package xyz.yychainsaw.portfolio.content.persistence;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import xyz.yychainsaw.portfolio.common.error.DomainException;

final class ContentPersistenceErrors {
    private static final Pattern CONSTRAINT_NAME = Pattern.compile(
            "constraint\\s+[\\\"']([a-zA-Z0-9_]+)[\\\"']",
            Pattern.CASE_INSENSITIVE);

    private ContentPersistenceErrors() {}

    static DomainException versionConflict() {
        return new DomainException(
                "CONTENT_VERSION_CONFLICT",
                HttpStatus.CONFLICT,
                Map.of("version", "workspace was changed by another request"));
    }

    static DomainException identityMismatch() {
        return new DomainException(
                "CONTENT_IDENTITY_MISMATCH",
                HttpStatus.UNPROCESSABLE_ENTITY,
                Map.of("id", "workspace identity does not match the target"));
    }

    static DomainException invalid(String field, String message) {
        return new DomainException(
                "CONTENT_WORKSPACE_INVALID",
                HttpStatus.UNPROCESSABLE_ENTITY,
                Map.of(field, message));
    }

    static DomainException corrupt(String field) {
        return new DomainException(
                "CONTENT_PERSISTENCE_CORRUPT",
                HttpStatus.INTERNAL_SERVER_ERROR,
                Map.of(field, "stored workspace is inconsistent"));
    }

    static DomainException siteMissing() {
        return new DomainException(
                "CONTENT_SITE_INVARIANT_BROKEN",
                HttpStatus.INTERNAL_SERVER_ERROR,
                Map.of());
    }

    static DomainException projectNotFound() {
        return new DomainException(
                "PROJECT_NOT_FOUND",
                HttpStatus.NOT_FOUND,
                Map.of("projectId", "project does not exist"));
    }

    static DomainException taxonomyNotFound() {
        return new DomainException(
                "TAXONOMY_NOT_FOUND",
                HttpStatus.NOT_FOUND,
                Map.of("taxonomyId", "taxonomy entry does not exist"));
    }

    static DomainException slugConflict() {
        return conflict("CONTENT_SLUG_CONFLICT", "slug", "slug is already in use");
    }

    static DomainException externalKeyConflict() {
        return conflict(
                "CONTENT_EXTERNAL_KEY_CONFLICT",
                "externalKey",
                "external key is already in use");
    }

    static DomainException catalogOrderConflict() {
        return conflict(
                "CONTENT_CATALOG_ORDER_CONFLICT",
                "sortOrder",
                "catalog order conflicts with another project");
    }

    static DomainException uniqueConflict(String field) {
        return conflict(
                "CONTENT_UNIQUE_CONFLICT",
                field,
                "value must be unique within its parent");
    }

    static RuntimeException translateConstraint(RuntimeException exception) {
        String constraint = constraintName(exception);
        if (constraint == null) {
            return exception;
        }
        return switch (constraint) {
            case "project_slug_uk" -> slugConflict();
            case "project_external_key_uk" -> externalKeyConflict();
            case "project_sort_order_uk" -> catalogOrderConflict();

            case "site_navigation_item_site_target_uk" ->
                    uniqueConflict("navigation.target");
            case "site_navigation_item_site_sort_order_uk" ->
                    uniqueConflict("navigation.sortOrder");
            case "social_link_site_platform_uk" ->
                    uniqueConflict("socialLinks.platform");
            case "social_link_site_sort_order_uk" ->
                    uniqueConflict("socialLinks.sortOrder");
            case "profile_fact_site_external_key_uk" ->
                    uniqueConflict("facts.externalKey");
            case "profile_fact_site_sort_order_uk" ->
                    uniqueConflict("facts.sortOrder");
            case "profile_skill_site_external_key_uk" ->
                    uniqueConflict("profileSkills.externalKey");
            case "profile_skill_site_sort_order_uk" ->
                    uniqueConflict("profileSkills.sortOrder");
            case "roadmap_stage_site_external_key_uk" ->
                    uniqueConflict("roadmap.stages.externalKey");
            case "roadmap_stage_site_sort_order_uk" ->
                    uniqueConflict("roadmap.stages.sortOrder");
            case "roadmap_outcome_stage_sort_order_uk" ->
                    uniqueConflict("roadmap.stages.outcomes.sortOrder");
            case "resume_document_current_locale_uk" ->
                    uniqueConflict("resumes.currentLocale");
            case "tag_normalized_key_uk" -> uniqueConflict("tags.normalizedKey");
            case "skill_normalized_key_uk" -> uniqueConflict("skills.normalizedKey");
            case "project_tag_project_sort_order_uk" ->
                    uniqueConflict("tags.sortOrder");
            case "project_skill_project_sort_order_uk" ->
                    uniqueConflict("skills.sortOrder");
            case "project_media_pk" ->
                    uniqueConflict("media.assetUsage");
            case "project_media_project_usage_sort_order_uk" ->
                    uniqueConflict("media.sortOrder");
            case "project_content_block_project_sort_order_uk" ->
                    uniqueConflict("blocks.sortOrder");
            case "content_block_metric_block_sort_order_uk" ->
                    uniqueConflict("blocks.metrics.sortOrder");
            case "project_tag_tag_fk", "project_skill_skill_fk" ->
                    invalid("taxonomy", "referenced taxonomy entry does not exist");

            case "project_sort_order_ck" ->
                    invalid("sortOrder", "must be non-negative");
            case "project_slug_ck" ->
                    invalid("slug", "must use lowercase URL-safe segments");
            case "project_tag_sort_order_ck" ->
                    invalid("tags.sortOrder", "must be non-negative");
            case "project_skill_sort_order_ck" ->
                    invalid("skills.sortOrder", "must be non-negative");
            case "project_media_usage_ck" ->
                    invalid("media.usage", "unsupported media usage");
            case "project_media_sort_order_ck" ->
                    invalid("media.sortOrder", "must be non-negative");
            case "project_media_layout_ck" ->
                    invalid("media.layout", "unsupported media layout");
            case "project_media_source_url_ck" ->
                    invalid("media.sourceUrl", "must use HTTPS");
            case "project_content_block_type_ck" ->
                    invalid("blocks.type", "unsupported block type");
            case "project_content_block_sort_order_ck" ->
                    invalid("blocks.sortOrder", "must be non-negative");
            case "project_content_block_width_ck" ->
                    invalid("blocks.width", "unsupported block width");
            case "project_content_block_alignment_ck" ->
                    invalid("blocks.alignment", "unsupported block alignment");
            case "project_content_block_emphasis_ck" ->
                    invalid("blocks.emphasis", "unsupported block emphasis");
            case "project_content_block_columns_ck" ->
                    invalid("blocks.columns", "must be between 1 and 4");
            case "content_block_media_role_ck" ->
                    invalid("blocks.media.role", "unsupported media role");
            case "content_block_media_sort_order_ck" ->
                    invalid("blocks.media.sortOrder", "must be non-negative");
            case "content_block_video_provider_ck" ->
                    invalid("blocks.video.provider", "unsupported video provider");
            case "content_block_video_url_ck" ->
                    invalid("blocks.video.url", "must use HTTPS");
            case "content_block_action_type_ck", "content_block_action_target_type_ck",
                    "content_block_action_target_ck" ->
                    invalid("blocks.action.target", "invalid action target");
            case "content_block_metric_sort_order_ck" ->
                    invalid("blocks.metrics.sortOrder", "must be non-negative");

            case "site_navigation_item_target_ck" ->
                    invalid("navigation.target", "invalid navigation target");
            case "site_navigation_item_sort_order_ck" ->
                    invalid("navigation.sortOrder", "must be non-negative");
            case "hero_media_source_url_ck" ->
                    invalid("hero.sourceUrl", "must use HTTPS");
            case "social_link_url_ck" ->
                    invalid("socialLinks.url", "must use HTTPS");
            case "social_link_sort_order_ck" ->
                    invalid("socialLinks.sortOrder", "must be non-negative");
            case "profile_fact_sort_order_ck" ->
                    invalid("facts.sortOrder", "must be non-negative");
            case "profile_skill_sort_order_ck" ->
                    invalid("profileSkills.sortOrder", "must be non-negative");
            case "roadmap_stage_sort_order_ck" ->
                    invalid("roadmap.stages.sortOrder", "must be non-negative");
            case "roadmap_outcome_sort_order_ck" ->
                    invalid("roadmap.stages.outcomes.sortOrder", "must be non-negative");
            default -> exception;
        };
    }

    private static String constraintName(Throwable throwable) {
        Set<Throwable> visited = new HashSet<>();
        for (Throwable current = throwable;
                current != null && visited.add(current);
                current = current.getCause()) {
            String message = current.getMessage();
            if (message == null) {
                continue;
            }
            Matcher matcher = CONSTRAINT_NAME.matcher(message);
            if (matcher.find()) {
                return matcher.group(1).toLowerCase(java.util.Locale.ROOT);
            }
        }
        return null;
    }

    private static DomainException conflict(String code, String field, String message) {
        return new DomainException(code, HttpStatus.CONFLICT, Map.of(field, message));
    }
}
