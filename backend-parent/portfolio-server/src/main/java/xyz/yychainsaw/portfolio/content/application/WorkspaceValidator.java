package xyz.yychainsaw.portfolio.content.application;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.content.api.ContentBlockDto;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.content.api.ProjectWorkspaceDto;
import xyz.yychainsaw.portfolio.content.api.SiteWorkspaceDto;

@Component
public final class WorkspaceValidator {
    private static final Set<LocaleCode> SUPPORTED_LOCALES =
            Set.of(LocaleCode.ZH_CN, LocaleCode.EN);
    private static final Set<String> VIDEO_PROVIDERS =
            Set.of("BILIBILI", "YOUTUBE", "VIMEO");
    private static final String SLUG_PATTERN = "^[a-z0-9]+(?:-[a-z0-9]+)*$";

    public void validateSite(SiteWorkspaceDto site) {
        TreeMap<String, String> errors = new TreeMap<>();
        requireText(errors, "monogram", site.monogram());
        requireText(errors, "email", site.email());
        requireLocales(errors, "identity", site.identity());
        requireLocales(errors, "seo", site.seo());
        requireLocales(errors, "accessibility", site.accessibility());
        if (site.hero() == null) {
            errors.put("hero", "required");
        } else {
            requireLocales(errors, "hero.copy", site.hero().copy());
        }
        requireLocales(errors, "about", site.about());
        requireLocales(errors, "work", site.work());
        if (site.roadmap() == null) {
            errors.put("roadmap", "required");
        } else {
            requireLocales(errors, "roadmap.header", site.roadmap().header());
        }
        requireLocales(errors, "contact", site.contact());
        requireLocales(errors, "privacy", site.privacy());
        finish("SITE_WORKSPACE_INVALID", errors);
    }

    public void validateProject(ProjectWorkspaceDto project) {
        TreeMap<String, String> errors = new TreeMap<>();
        requireText(errors, "slug", project.slug());
        if (project.slug() != null
                && !project.slug().isBlank()
                && !project.slug().matches(SLUG_PATTERN)) {
            errors.put(
                    "slug",
                    "slug must be lowercase ASCII words separated by hyphens");
        }
        requireLocales(errors, "translations", project.translations());
        for (int index = 0; index < project.blocks().size(); index++) {
            validateBlock(errors, "blocks[" + index + "]", project.blocks().get(index));
        }
        String code = errors.keySet().stream()
                .anyMatch(key -> key.startsWith("translations"))
                ? "PROJECT_TRANSLATION_INCOMPLETE"
                : "CONTENT_BLOCK_INVALID";
        finish(code, errors);
    }

    private void validateBlock(
            Map<String, String> errors,
            String path,
            ContentBlockDto block) {
        if (block.columns() < 1 || block.columns() > 4) {
            errors.put(path + ".columns", "must be 1 to 4");
        }
        ContentBlockDto.Payload payload = block.payload();
        if (payload instanceof ContentBlockDto.MarkdownPayload markdown) {
            requireLocales(errors, path + ".markdown", markdown.markdown());
        } else if (payload instanceof ContentBlockDto.ImagePayload image) {
            requireId(errors, path + ".mediaAssetId", image.mediaAssetId());
        } else if (payload instanceof ContentBlockDto.GalleryPayload gallery) {
            if (gallery.mediaAssetIds().size() < 2) {
                errors.put(
                        path + ".mediaAssetIds",
                        "gallery requires at least two images");
            }
        } else if (payload instanceof ContentBlockDto.VideoPayload video) {
            requireHttps(errors, path + ".url", video.url());
            if (video.provider() == null || !VIDEO_PROVIDERS.contains(video.provider())) {
                errors.put(path + ".provider", "unsupported video provider");
            }
            requireLocales(errors, path + ".copy", video.copy());
        } else if (payload instanceof ContentBlockDto.CodePayload code) {
            requireLocales(errors, path + ".copy", code.copy());
        } else if (payload instanceof ContentBlockDto.QuotePayload quote) {
            requireLocales(errors, path + ".copy", quote.copy());
        } else if (payload instanceof ContentBlockDto.MetricsPayload metrics) {
            if (metrics.metrics().isEmpty()) {
                errors.put(path + ".metrics", "metrics block cannot be empty");
            }
            for (int index = 0; index < metrics.metrics().size(); index++) {
                requireLocales(
                        errors,
                        path + ".metrics[" + index + "].copy",
                        metrics.metrics().get(index).copy());
            }
        } else if (payload instanceof ContentBlockDto.DownloadPayload download) {
            if ((download.mediaAssetId() == null) == (download.externalUrl() == null)) {
                errors.put(
                        path,
                        "download requires exactly one media asset or external URL");
            }
            if (download.externalUrl() != null) {
                requireHttps(errors, path + ".externalUrl", download.externalUrl());
            }
            requireLocales(errors, path + ".copy", download.copy());
        } else if (payload instanceof ContentBlockDto.LinkPayload link) {
            requireHttps(errors, path + ".url", link.url());
            requireLocales(errors, path + ".copy", link.copy());
        } else {
            errors.put(path + ".type", "unsupported content block payload");
        }
    }

    private void requireHttps(
            Map<String, String> errors,
            String path,
            URI value) {
        if (value == null || !"https".equalsIgnoreCase(value.getScheme())) {
            errors.put(path, "HTTPS URL required");
        }
    }

    private void requireId(
            Map<String, String> errors,
            String path,
            Object value) {
        if (value == null) {
            errors.put(path, "required");
        }
    }

    private void requireText(
            Map<String, String> errors,
            String path,
            String value) {
        if (value == null || value.isBlank()) {
            errors.put(path, "required");
        }
    }

    private void requireLocales(
            Map<String, String> errors,
            String path,
            Map<LocaleCode, ?> values) {
        if (values == null || !values.keySet().equals(SUPPORTED_LOCALES)) {
            errors.put(path, "exactly zh-CN and en are required");
        }
    }

    private void finish(String code, Map<String, String> errors) {
        if (!errors.isEmpty()) {
            throw new DomainException(
                    code,
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    errors);
        }
    }
}
