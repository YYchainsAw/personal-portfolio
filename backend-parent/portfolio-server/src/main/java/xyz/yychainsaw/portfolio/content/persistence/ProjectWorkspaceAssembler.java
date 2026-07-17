package xyz.yychainsaw.portfolio.content.persistence;

import java.math.BigDecimal;
import java.net.URI;
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
import xyz.yychainsaw.portfolio.content.api.ContentBlockDto;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.content.api.ProjectWorkspaceDto;
import xyz.yychainsaw.portfolio.content.persistence.mybatis.ProjectWorkspaceMapper;

@Component
final class ProjectWorkspaceAssembler {
    private static final Set<LocaleCode> REQUIRED_LOCALES =
            Set.of(LocaleCode.ZH_CN, LocaleCode.EN);

    ProjectWorkspaceDto load(
            ProjectWorkspaceMapper mapper,
            Map<String, Object> root) {
        UUID projectId = uuid(root, "id");
        Map<LocaleCode, ProjectWorkspaceDto.ProjectCopy> translations = localized(
                mapper,
                projectId,
                ProjectTable.PROJECT_TRANSLATION,
                "project.translations",
                row -> new ProjectWorkspaceDto.ProjectCopy(
                        text(row, "status_label"), text(row, "eyebrow"), text(row, "title"),
                        text(row, "summary"), text(row, "seo_title"), text(row, "seo_description")));
        List<ProjectWorkspaceDto.TaxonomyRef> tags = taxonomy(
                rows(mapper, projectId, ProjectTable.PROJECT_TAG), "tag_id", "project.tags");
        List<ProjectWorkspaceDto.TaxonomyRef> skills = taxonomy(
                rows(mapper, projectId, ProjectTable.PROJECT_SKILL), "skill_id", "project.skills");
        List<ProjectWorkspaceDto.ProjectMedia> media = rows(mapper, projectId, ProjectTable.PROJECT_MEDIA).stream()
                .map(row -> new ProjectWorkspaceDto.ProjectMedia(
                        uuid(row, "media_asset_id"), text(row, "usage"), integer(row, "sort_order"),
                        text(row, "layout"), text(row, "object_position"), text(row, "credit"),
                        uri(row, "source_url")))
                .toList();
        List<ContentBlockDto> blocks = blocks(mapper, projectId);
        return new ProjectWorkspaceDto(
                projectId,
                text(root, "external_key"),
                text(root, "slug"),
                text(root, "number_label"),
                integer(root, "sort_order"),
                bool(root, "featured"),
                bool(root, "visible"),
                bool(root, "publication_dirty"),
                longValue(root, "version"),
                translations,
                tags,
                skills,
                media,
                blocks);
    }

    Map<String, Object> rootRow(ProjectWorkspaceDto workspace) {
        return row(
                "id", workspace.id(),
                "externalKey", workspace.externalKey(),
                "slug", workspace.slug(),
                "numberLabel", workspace.number(),
                "sortOrder", workspace.sortOrder(),
                "featured", workspace.featured(),
                "visible", workspace.visible());
    }

    void validate(ProjectWorkspaceDto workspace) {
        java.util.Objects.requireNonNull(workspace, "project workspace is required");
        validateNestedIdentity(workspace);
    }

    void replaceChildren(ProjectWorkspaceMapper mapper, ProjectWorkspaceDto workspace) {
        validate(workspace);
        deleteChildren(mapper, workspace.id());

        List<Map<String, Object>> translations = new ArrayList<>();
        workspace.translations().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    ProjectWorkspaceDto.ProjectCopy value = entry.getValue();
                    translations.add(row(
                            "projectId", workspace.id(), "locale", entry.getKey().value(),
                            "statusLabel", value.status(), "eyebrow", value.eyebrow(),
                            "title", value.title(), "summary", value.summary(),
                            "seoTitle", value.seoTitle(), "seoDescription", value.seoDescription()));
                });
        insert(mapper, ProjectTable.PROJECT_TRANSLATION, translations);
        insert(mapper, ProjectTable.PROJECT_TAG, taxonomyRows(workspace.id(), workspace.tags()));
        insert(mapper, ProjectTable.PROJECT_SKILL, taxonomyRows(workspace.id(), workspace.skills()));
        insert(mapper, ProjectTable.PROJECT_MEDIA, workspace.media().stream()
                .map(media -> row(
                        "projectId", workspace.id(), "mediaAssetId", media.assetId(),
                        "usage", media.usage(), "sortOrder", media.sortOrder(), "layout", media.layout(),
                        "objectPosition", media.objectPosition(), "credit", media.credit(),
                        "sourceUrl", media.sourceUrl().toString()))
                .toList());
        insertBlocks(mapper, workspace);
    }

    private List<ContentBlockDto> blocks(ProjectWorkspaceMapper mapper, UUID projectId) {
        Map<UUID, Map<LocaleCode, ContentBlockDto.BlockCopy>> genericCopy = localizedByParent(
                rows(mapper, projectId, ProjectTable.CONTENT_BLOCK_TRANSLATION),
                "block_id",
                "blocks.copy",
                row -> new ContentBlockDto.BlockCopy(text(row, "title"), text(row, "description")));
        Map<UUID, List<Map<String, Object>>> media = group(
                rows(mapper, projectId, ProjectTable.BLOCK_MEDIA), "block_id");
        Map<UUID, Map<LocaleCode, String>> markdown = localizedByParent(
                rows(mapper, projectId, ProjectTable.BLOCK_MARKDOWN_TRANSLATION),
                "block_id", "blocks.markdown", row -> text(row, "markdown"));
        Map<UUID, Map<String, Object>> video = single(
                rows(mapper, projectId, ProjectTable.BLOCK_VIDEO), "block_id", "blocks.video");
        Map<UUID, Map<String, Object>> code = single(
                rows(mapper, projectId, ProjectTable.BLOCK_CODE), "block_id", "blocks.code");
        Map<UUID, Map<LocaleCode, ContentBlockDto.QuoteCopy>> quote = localizedByParent(
                rows(mapper, projectId, ProjectTable.BLOCK_QUOTE_TRANSLATION),
                "block_id", "blocks.quote", row -> new ContentBlockDto.QuoteCopy(
                        text(row, "quote_text"), text(row, "source_text")));
        Map<UUID, Map<String, Object>> action = single(
                rows(mapper, projectId, ProjectTable.BLOCK_ACTION), "block_id", "blocks.action");
        Map<UUID, Map<LocaleCode, ContentBlockDto.MetricCopy>> metricCopy = localizedByParent(
                rows(mapper, projectId, ProjectTable.BLOCK_METRIC_TRANSLATION),
                "metric_id", "blocks.metrics.copy", row -> new ContentBlockDto.MetricCopy(
                        text(row, "label"), text(row, "value_text"), text(row, "suffix")));
        Map<UUID, List<ContentBlockDto.Metric>> metrics = new LinkedHashMap<>();
        for (Map<String, Object> row : rows(mapper, projectId, ProjectTable.BLOCK_METRIC)) {
            UUID blockId = uuid(row, "block_id");
            UUID metricId = uuid(row, "id");
            metrics.computeIfAbsent(blockId, ignored -> new ArrayList<>()).add(new ContentBlockDto.Metric(
                    metricId,
                    integer(row, "sort_order"),
                    decimal(row, "numeric_value"),
                    metricCopy.getOrDefault(metricId, Map.of())));
        }

        List<ContentBlockDto> result = new ArrayList<>();
        for (Map<String, Object> block : rows(mapper, projectId, ProjectTable.CONTENT_BLOCK)) {
            UUID blockId = uuid(block, "id");
            String type = text(block, "block_type");
            validatePayloadShape(
                    blockId,
                    type,
                    genericCopy,
                    media,
                    markdown,
                    video,
                    code,
                    quote,
                    action,
                    metrics);
            ContentBlockDto.Payload payload = switch (type) {
                case "MARKDOWN" -> new ContentBlockDto.MarkdownPayload(
                        markdown.getOrDefault(blockId, Map.of()));
                case "IMAGE" -> new ContentBlockDto.ImagePayload(
                        requireSingleMedia(media.getOrDefault(blockId, List.of()), "PRIMARY", blockId));
                case "GALLERY" -> new ContentBlockDto.GalleryPayload(
                        mediaByRole(media.getOrDefault(blockId, List.of()), "GALLERY"));
                case "VIDEO" -> videoPayload(blockId, required(video, blockId, "video"), genericCopy);
                case "CODE" -> codePayload(blockId, required(code, blockId, "code"), genericCopy);
                case "QUOTE" -> new ContentBlockDto.QuotePayload(
                        quote.getOrDefault(blockId, Map.of()));
                case "METRICS" -> new ContentBlockDto.MetricsPayload(
                        metrics.getOrDefault(blockId, List.of()));
                case "DOWNLOAD" -> downloadPayload(blockId, required(action, blockId, "action"), genericCopy);
                case "LINK" -> linkPayload(blockId, required(action, blockId, "action"), genericCopy);
                default -> throw ContentPersistenceErrors.corrupt("blocks.type");
            };
            result.add(new ContentBlockDto(
                    blockId,
                    integer(block, "sort_order"),
                    bool(block, "visible"),
                    ContentBlockDto.Width.valueOf(text(block, "width")),
                    ContentBlockDto.Alignment.valueOf(text(block, "alignment")),
                    ContentBlockDto.Emphasis.valueOf(text(block, "emphasis")),
                    integer(block, "columns"),
                    payload));
        }
        return result;
    }

    private void validatePayloadShape(
            UUID blockId,
            String type,
            Map<UUID, Map<LocaleCode, ContentBlockDto.BlockCopy>> genericCopy,
            Map<UUID, List<Map<String, Object>>> media,
            Map<UUID, Map<LocaleCode, String>> markdown,
            Map<UUID, Map<String, Object>> video,
            Map<UUID, Map<String, Object>> code,
            Map<UUID, Map<LocaleCode, ContentBlockDto.QuoteCopy>> quote,
            Map<UUID, Map<String, Object>> action,
            Map<UUID, List<ContentBlockDto.Metric>> metrics) {
        boolean genericAllowed = "VIDEO".equals(type)
                || "CODE".equals(type)
                || "DOWNLOAD".equals(type)
                || "LINK".equals(type);
        if ((!genericAllowed && genericCopy.containsKey(blockId))
                || (!"MARKDOWN".equals(type) && markdown.containsKey(blockId))
                || (!"VIDEO".equals(type) && video.containsKey(blockId))
                || (!"CODE".equals(type) && code.containsKey(blockId))
                || (!"QUOTE".equals(type) && quote.containsKey(blockId))
                || (!("DOWNLOAD".equals(type) || "LINK".equals(type))
                        && action.containsKey(blockId))
                || (!"METRICS".equals(type) && metrics.containsKey(blockId))) {
            throw ContentPersistenceErrors.corrupt("blocks." + blockId + ".subtype");
        }

        if ("MARKDOWN".equals(type)
                && !hasRequiredLocales(markdown.get(blockId))) {
            throw ContentPersistenceErrors.corrupt("blocks." + blockId + ".copy");
        }
        if ("QUOTE".equals(type)
                && !hasRequiredLocales(quote.get(blockId))) {
            throw ContentPersistenceErrors.corrupt("blocks." + blockId + ".copy");
        }
        if (genericAllowed
                && !hasRequiredLocales(genericCopy.get(blockId))) {
            throw ContentPersistenceErrors.corrupt("blocks." + blockId + ".copy");
        }
        if ("METRICS".equals(type)) {
            List<ContentBlockDto.Metric> metricRows =
                    metrics.getOrDefault(blockId, List.of());
            if (metricRows.isEmpty()
                    || metricRows.stream()
                            .map(ContentBlockDto.Metric::copy)
                            .anyMatch(copy -> !hasRequiredLocales(copy))) {
                throw ContentPersistenceErrors.corrupt("blocks." + blockId + ".metrics");
            }
        }

        List<Map<String, Object>> mediaRows = media.getOrDefault(blockId, List.of());
        if ("IMAGE".equals(type)) {
            if (mediaRows.size() != 1
                    || !"PRIMARY".equals(text(mediaRows.get(0), "role"))
                    || integer(mediaRows.get(0), "sort_order") != 0) {
                throw ContentPersistenceErrors.corrupt("blocks." + blockId + ".media");
            }
        } else if ("GALLERY".equals(type)) {
            if (mediaRows.size() < 2) {
                throw ContentPersistenceErrors.corrupt("blocks." + blockId + ".media");
            }
            for (int index = 0; index < mediaRows.size(); index++) {
                Map<String, Object> mediaRow = mediaRows.get(index);
                if (!"GALLERY".equals(text(mediaRow, "role"))
                        || integer(mediaRow, "sort_order") != index) {
                    throw ContentPersistenceErrors.corrupt("blocks." + blockId + ".media");
                }
            }
        } else if (!mediaRows.isEmpty()) {
            throw ContentPersistenceErrors.corrupt("blocks." + blockId + ".media");
        }
    }

    private boolean hasRequiredLocales(Map<LocaleCode, ?> values) {
        return values != null && values.keySet().equals(REQUIRED_LOCALES);
    }

    private ContentBlockDto.VideoPayload videoPayload(
            UUID blockId,
            Map<String, Object> row,
            Map<UUID, Map<LocaleCode, ContentBlockDto.BlockCopy>> copy) {
        return new ContentBlockDto.VideoPayload(
                text(row, "provider"),
                uri(row, "url"),
                nullableUuid(row, "cover_asset_id"),
                copy.getOrDefault(blockId, Map.of()));
    }

    private ContentBlockDto.CodePayload codePayload(
            UUID blockId,
            Map<String, Object> row,
            Map<UUID, Map<LocaleCode, ContentBlockDto.BlockCopy>> copy) {
        return new ContentBlockDto.CodePayload(
                text(row, "code_text"),
                text(row, "language"),
                bool(row, "show_line_numbers"),
                copy.getOrDefault(blockId, Map.of()));
    }

    private ContentBlockDto.DownloadPayload downloadPayload(
            UUID blockId,
            Map<String, Object> row,
            Map<UUID, Map<LocaleCode, ContentBlockDto.BlockCopy>> generic) {
        String targetType = text(row, "target_type");
        UUID mediaAssetId = nullableUuid(row, "media_asset_id");
        URI externalUrl = nullableUri(row, "url");
        boolean mediaTarget = "MEDIA".equals(targetType)
                && mediaAssetId != null
                && externalUrl == null;
        boolean externalTarget = "EXTERNAL".equals(targetType)
                && mediaAssetId == null
                && externalUrl != null;
        if (!"DOWNLOAD".equals(text(row, "action_type"))
                || (!mediaTarget && !externalTarget)
                || !Boolean.TRUE.equals(row.get("open_new_tab"))) {
            throw ContentPersistenceErrors.corrupt("blocks.download.type");
        }
        return new ContentBlockDto.DownloadPayload(
                mediaAssetId,
                externalUrl,
                actionCopy(generic.getOrDefault(blockId, Map.of())));
    }

    private ContentBlockDto.LinkPayload linkPayload(
            UUID blockId,
            Map<String, Object> row,
            Map<UUID, Map<LocaleCode, ContentBlockDto.BlockCopy>> generic) {
        if (!"LINK".equals(text(row, "action_type"))
                || !"EXTERNAL".equals(text(row, "target_type"))
                || row.get("media_asset_id") != null
                || row.get("url") == null
                || row.get("open_new_tab") == null) {
            throw ContentPersistenceErrors.corrupt("blocks.link.type");
        }
        return new ContentBlockDto.LinkPayload(
                uri(row, "url"),
                bool(row, "open_new_tab"),
                actionCopy(generic.getOrDefault(blockId, Map.of())));
    }

    private Map<LocaleCode, ContentBlockDto.ActionCopy> actionCopy(
            Map<LocaleCode, ContentBlockDto.BlockCopy> source) {
        EnumMap<LocaleCode, ContentBlockDto.ActionCopy> result = new EnumMap<>(LocaleCode.class);
        source.forEach((locale, copy) -> result.put(
                locale, new ContentBlockDto.ActionCopy(copy.title(), copy.description())));
        return result;
    }

    private void insertBlocks(ProjectWorkspaceMapper mapper, ProjectWorkspaceDto workspace) {
        List<Map<String, Object>> roots = new ArrayList<>();
        List<Map<String, Object>> genericCopy = new ArrayList<>();
        List<Map<String, Object>> media = new ArrayList<>();
        List<Map<String, Object>> markdown = new ArrayList<>();
        List<Map<String, Object>> video = new ArrayList<>();
        List<Map<String, Object>> code = new ArrayList<>();
        List<Map<String, Object>> quote = new ArrayList<>();
        List<Map<String, Object>> action = new ArrayList<>();
        List<Map<String, Object>> metrics = new ArrayList<>();
        List<Map<String, Object>> metricCopy = new ArrayList<>();

        for (ContentBlockDto block : workspace.blocks()) {
            String type = payloadType(block.payload());
            roots.add(row(
                    "id", block.id(), "projectId", workspace.id(), "blockType", type,
                    "sortOrder", block.sortOrder(), "visible", block.visible(),
                    "width", block.width().name(), "alignment", block.alignment().name(),
                    "emphasis", block.emphasis().name(), "columns", block.columns()));
            ContentBlockDto.Payload payload = block.payload();
            if (payload instanceof ContentBlockDto.MarkdownPayload value) {
                value.markdown().forEach((locale, text) -> markdown.add(row(
                        "blockId", block.id(), "locale", locale.value(), "markdown", text)));
            } else if (payload instanceof ContentBlockDto.ImagePayload value) {
                media.add(row("blockId", block.id(), "mediaAssetId", value.mediaAssetId(),
                        "role", "PRIMARY", "sortOrder", 0));
            } else if (payload instanceof ContentBlockDto.GalleryPayload value) {
                for (int index = 0; index < value.mediaAssetIds().size(); index++) {
                    media.add(row("blockId", block.id(), "mediaAssetId", value.mediaAssetIds().get(index),
                            "role", "GALLERY", "sortOrder", index));
                }
            } else if (payload instanceof ContentBlockDto.VideoPayload value) {
                video.add(row("blockId", block.id(), "provider", value.provider(),
                        "url", value.url().toString(), "coverAssetId", value.coverAssetId()));
                addGenericCopy(genericCopy, block.id(), value.copy());
            } else if (payload instanceof ContentBlockDto.CodePayload value) {
                code.add(row("blockId", block.id(), "codeText", value.code(),
                        "language", value.language(), "showLineNumbers", value.showLineNumbers()));
                addGenericCopy(genericCopy, block.id(), value.copy());
            } else if (payload instanceof ContentBlockDto.QuotePayload value) {
                value.copy().forEach((locale, copy) -> quote.add(row(
                        "blockId", block.id(), "locale", locale.value(),
                        "quoteText", copy.quote(), "sourceText", copy.source())));
            } else if (payload instanceof ContentBlockDto.MetricsPayload value) {
                for (ContentBlockDto.Metric metric : value.metrics()) {
                    metrics.add(row("id", metric.id(), "blockId", block.id(),
                            "sortOrder", metric.sortOrder(), "numericValue", metric.numericValue()));
                    metric.copy().forEach((locale, copy) -> metricCopy.add(row(
                            "metricId", metric.id(), "locale", locale.value(), "label", copy.label(),
                            "valueText", copy.value(), "suffix", copy.suffix())));
                }
            } else if (payload instanceof ContentBlockDto.DownloadPayload value) {
                boolean mediaTarget = value.mediaAssetId() != null;
                boolean externalTarget = value.externalUrl() != null;
                if (mediaTarget == externalTarget) {
                    throw ContentPersistenceErrors.invalid(
                            "blocks.download.target",
                            "exactly one media or external target is required");
                }
                action.add(row("blockId", block.id(), "actionType", "DOWNLOAD",
                        "targetType", mediaTarget ? "MEDIA" : "EXTERNAL",
                        "mediaAssetId", value.mediaAssetId(),
                        "url", value.externalUrl() == null ? null : value.externalUrl().toString(),
                        "openNewTab", true));
                addActionCopy(genericCopy, block.id(), value.copy());
            } else if (payload instanceof ContentBlockDto.LinkPayload value) {
                if (value.url() == null) {
                    throw ContentPersistenceErrors.invalid(
                            "blocks.link.url",
                            "external URL is required");
                }
                action.add(row("blockId", block.id(), "actionType", "LINK", "targetType", "EXTERNAL",
                        "mediaAssetId", null, "url", value.url().toString(),
                        "openNewTab", value.openNewTab()));
                addActionCopy(genericCopy, block.id(), value.copy());
            }
        }
        insert(mapper, ProjectTable.CONTENT_BLOCK, roots);
        insert(mapper, ProjectTable.CONTENT_BLOCK_TRANSLATION, genericCopy);
        insert(mapper, ProjectTable.BLOCK_MEDIA, media);
        insert(mapper, ProjectTable.BLOCK_MARKDOWN_TRANSLATION, markdown);
        insert(mapper, ProjectTable.BLOCK_VIDEO, video);
        insert(mapper, ProjectTable.BLOCK_CODE, code);
        insert(mapper, ProjectTable.BLOCK_QUOTE_TRANSLATION, quote);
        insert(mapper, ProjectTable.BLOCK_ACTION, action);
        insert(mapper, ProjectTable.BLOCK_METRIC, metrics);
        insert(mapper, ProjectTable.BLOCK_METRIC_TRANSLATION, metricCopy);
    }

    private void addGenericCopy(
            List<Map<String, Object>> rows,
            UUID blockId,
            Map<LocaleCode, ContentBlockDto.BlockCopy> copy) {
        copy.forEach((locale, value) -> rows.add(row(
                "blockId", blockId, "locale", locale.value(),
                "title", value.title(), "description", value.description())));
    }

    private void addActionCopy(
            List<Map<String, Object>> rows,
            UUID blockId,
            Map<LocaleCode, ContentBlockDto.ActionCopy> copy) {
        copy.forEach((locale, value) -> rows.add(row(
                "blockId", blockId, "locale", locale.value(),
                "title", value.label(), "description", value.description())));
    }

    private String payloadType(ContentBlockDto.Payload payload) {
        if (payload instanceof ContentBlockDto.MarkdownPayload) return "MARKDOWN";
        if (payload instanceof ContentBlockDto.ImagePayload) return "IMAGE";
        if (payload instanceof ContentBlockDto.GalleryPayload) return "GALLERY";
        if (payload instanceof ContentBlockDto.VideoPayload) return "VIDEO";
        if (payload instanceof ContentBlockDto.CodePayload) return "CODE";
        if (payload instanceof ContentBlockDto.QuotePayload) return "QUOTE";
        if (payload instanceof ContentBlockDto.MetricsPayload) return "METRICS";
        if (payload instanceof ContentBlockDto.DownloadPayload) return "DOWNLOAD";
        if (payload instanceof ContentBlockDto.LinkPayload) return "LINK";
        throw ContentPersistenceErrors.invalid("blocks.type", "unsupported content block payload");
    }

    private void validateNestedIdentity(ProjectWorkspaceDto workspace) {
        requireUnique(workspace.tags().stream().map(ProjectWorkspaceDto.TaxonomyRef::id).toList(), "tags.id");
        requireUnique(workspace.skills().stream().map(ProjectWorkspaceDto.TaxonomyRef::id).toList(), "skills.id");
        List<UUID> blockIds = workspace.blocks().stream().map(ContentBlockDto::id).toList();
        requireUnique(blockIds, "blocks.id");
        List<UUID> metricIds = workspace.blocks().stream()
                .map(ContentBlockDto::payload)
                .filter(ContentBlockDto.MetricsPayload.class::isInstance)
                .map(ContentBlockDto.MetricsPayload.class::cast)
                .flatMap(payload -> payload.metrics().stream())
                .map(ContentBlockDto.Metric::id)
                .toList();
        requireUnique(metricIds, "blocks.metrics.id");
        Set<UUID> nestedIds = new HashSet<>(blockIds);
        if (metricIds.stream().anyMatch(id -> !nestedIds.add(id))) {
            throw ContentPersistenceErrors.invalid(
                    "blocks.metrics.id",
                    "metric identities must not reuse a block identity");
        }
        for (ContentBlockDto block : workspace.blocks()) {
            if (block.payload() instanceof ContentBlockDto.GalleryPayload gallery) {
                requireUnique(gallery.mediaAssetIds(), "blocks.gallery.mediaAssetIds");
            } else if (block.payload() instanceof ContentBlockDto.DownloadPayload download) {
                boolean mediaTarget = download.mediaAssetId() != null;
                boolean externalTarget = download.externalUrl() != null;
                if (mediaTarget == externalTarget) {
                    throw ContentPersistenceErrors.invalid(
                            "blocks.download.target",
                            "exactly one media or external target is required");
                }
            } else if (block.payload() instanceof ContentBlockDto.LinkPayload link
                    && link.url() == null) {
                throw ContentPersistenceErrors.invalid(
                        "blocks.link.url",
                        "external URL is required");
            }
        }
    }

    private void requireUnique(List<UUID> ids, String field) {
        if (ids.stream().anyMatch(java.util.Objects::isNull)) {
            throw ContentPersistenceErrors.invalid(field, "identities must be non-null");
        }
        Set<UUID> unique = new HashSet<>(ids);
        if (unique.size() != ids.size()) {
            throw ContentPersistenceErrors.invalid(field, "identities must be unique within their parent");
        }
    }

    private void deleteChildren(ProjectWorkspaceMapper mapper, UUID projectId) {
        ProjectTable[] order = {
            ProjectTable.BLOCK_METRIC_TRANSLATION,
            ProjectTable.BLOCK_METRIC,
            ProjectTable.BLOCK_ACTION,
            ProjectTable.BLOCK_QUOTE_TRANSLATION,
            ProjectTable.BLOCK_CODE,
            ProjectTable.BLOCK_VIDEO,
            ProjectTable.BLOCK_MARKDOWN_TRANSLATION,
            ProjectTable.BLOCK_MEDIA,
            ProjectTable.CONTENT_BLOCK_TRANSLATION,
            ProjectTable.CONTENT_BLOCK,
            ProjectTable.PROJECT_MEDIA,
            ProjectTable.PROJECT_SKILL,
            ProjectTable.PROJECT_TAG,
            ProjectTable.PROJECT_TRANSLATION
        };
        for (ProjectTable table : order) {
            mapper.deleteOwnedRows(table.name(), projectId);
        }
    }

    private List<Map<String, Object>> taxonomyRows(
            UUID projectId,
            List<ProjectWorkspaceDto.TaxonomyRef> refs) {
        return refs.stream()
                .map(ref -> row("projectId", projectId, "taxonomyId", ref.id(), "sortOrder", ref.sortOrder()))
                .toList();
    }

    private List<ProjectWorkspaceDto.TaxonomyRef> taxonomy(
            List<Map<String, Object>> rows,
            String idKey,
            String field) {
        Map<UUID, TaxonomyAccumulator> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            UUID id = uuid(row, idKey);
            TaxonomyAccumulator item = grouped.computeIfAbsent(id, ignored -> new TaxonomyAccumulator(
                    id, text(row, "normalized_key"), integer(row, "sort_order")));
            String locale = text(row, "locale");
            if (locale != null && item.names.put(LocaleCode.from(locale), text(row, "name")) != null) {
                throw ContentPersistenceErrors.corrupt(field);
            }
        }
        return grouped.values().stream()
                .map(item -> new ProjectWorkspaceDto.TaxonomyRef(
                        item.id, item.normalizedKey, item.sortOrder, item.names))
                .toList();
    }

    private <T> Map<LocaleCode, T> localized(
            ProjectWorkspaceMapper mapper,
            UUID projectId,
            ProjectTable table,
            String field,
            Function<Map<String, Object>, T> factory) {
        EnumMap<LocaleCode, T> result = new EnumMap<>(LocaleCode.class);
        for (Map<String, Object> row : rows(mapper, projectId, table)) {
            if (result.put(LocaleCode.from(text(row, "locale")), factory.apply(row)) != null) {
                throw ContentPersistenceErrors.corrupt(field);
            }
        }
        return result;
    }

    private <T> Map<UUID, Map<LocaleCode, T>> localizedByParent(
            List<Map<String, Object>> rows,
            String parentKey,
            String field,
            Function<Map<String, Object>, T> factory) {
        Map<UUID, Map<LocaleCode, T>> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            UUID parent = uuid(row, parentKey);
            Map<LocaleCode, T> values = result.computeIfAbsent(parent, ignored -> new EnumMap<>(LocaleCode.class));
            if (values.put(LocaleCode.from(text(row, "locale")), factory.apply(row)) != null) {
                throw ContentPersistenceErrors.corrupt(field);
            }
        }
        return result;
    }

    private Map<UUID, List<Map<String, Object>>> group(
            List<Map<String, Object>> rows,
            String key) {
        Map<UUID, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            result.computeIfAbsent(uuid(row, key), ignored -> new ArrayList<>()).add(row);
        }
        return result;
    }

    private Map<UUID, Map<String, Object>> single(
            List<Map<String, Object>> rows,
            String key,
            String field) {
        Map<UUID, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            if (result.put(uuid(row, key), row) != null) {
                throw ContentPersistenceErrors.corrupt(field);
            }
        }
        return result;
    }

    private Map<String, Object> required(
            Map<UUID, Map<String, Object>> values,
            UUID id,
            String field) {
        Map<String, Object> value = values.get(id);
        if (value == null) {
            throw ContentPersistenceErrors.corrupt("blocks." + field);
        }
        return value;
    }

    private UUID requireSingleMedia(
            List<Map<String, Object>> rows,
            String role,
            UUID blockId) {
        List<UUID> values = mediaByRole(rows, role);
        if (values.size() != 1) {
            throw ContentPersistenceErrors.corrupt("blocks." + blockId + ".media");
        }
        return values.get(0);
    }

    private List<UUID> mediaByRole(List<Map<String, Object>> rows, String role) {
        return rows.stream()
                .filter(row -> role.equals(text(row, "role")))
                .map(row -> uuid(row, "media_asset_id"))
                .toList();
    }

    private List<Map<String, Object>> rows(
            ProjectWorkspaceMapper mapper,
            UUID projectId,
            ProjectTable table) {
        return mapper.selectRows(table.name(), projectId);
    }

    private void insert(
            ProjectWorkspaceMapper mapper,
            ProjectTable table,
            List<Map<String, Object>> rows) {
        if (!rows.isEmpty()) {
            mapper.insertRows(table.name(), rows);
        }
    }

    private static Map<String, Object> row(Object... values) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put((String) values[index], values[index + 1]);
        }
        return result;
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : value.toString();
    }

    private static UUID uuid(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof UUID id ? id : UUID.fromString(value.toString());
    }

    private static UUID nullableUuid(Map<String, Object> row, String key) {
        return row.get(key) == null ? null : uuid(row, key);
    }

    private static int integer(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).intValue();
    }

    private static long longValue(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private static boolean bool(Map<String, Object> row, String key) {
        return (Boolean) row.get(key);
    }

    private static BigDecimal decimal(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : (BigDecimal) value;
    }

    private static URI uri(Map<String, Object> row, String key) {
        return URI.create(text(row, key));
    }

    private static URI nullableUri(Map<String, Object> row, String key) {
        String value = text(row, key);
        return value == null ? null : URI.create(value);
    }

    private static final class TaxonomyAccumulator {
        private final UUID id;
        private final String normalizedKey;
        private final int sortOrder;
        private final EnumMap<LocaleCode, String> names = new EnumMap<>(LocaleCode.class);

        private TaxonomyAccumulator(UUID id, String normalizedKey, int sortOrder) {
            this.id = id;
            this.normalizedKey = normalizedKey;
            this.sortOrder = sortOrder;
        }
    }
}
