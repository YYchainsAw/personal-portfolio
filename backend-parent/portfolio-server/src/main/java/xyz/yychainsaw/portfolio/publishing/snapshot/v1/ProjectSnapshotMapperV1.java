package xyz.yychainsaw.portfolio.publishing.snapshot.v1;

import java.util.ArrayList;
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
import xyz.yychainsaw.portfolio.content.api.ContentBlockDto;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.content.api.ProjectWorkspaceDto;
import xyz.yychainsaw.portfolio.media.application.MediaAssetDescriptor;
import xyz.yychainsaw.portfolio.media.application.MediaCopyDescriptor;
import xyz.yychainsaw.portfolio.media.application.MediaQueryService;
import xyz.yychainsaw.portfolio.media.application.MediaVariantDescriptor;
import xyz.yychainsaw.portfolio.publishing.snapshot.ProjectSnapshotMapper;

@Component
public final class ProjectSnapshotMapperV1 implements ProjectSnapshotMapper {
    private static final int SCHEMA_VERSION = 1;
    private static final Comparator<UUID> ASSET_ORDER =
            Comparator.comparing(UUID::toString);

    private final MediaQueryService mediaQueryService;

    public ProjectSnapshotMapperV1(MediaQueryService mediaQueryService) {
        this.mediaQueryService = Objects.requireNonNull(
                mediaQueryService, "mediaQueryService");
    }

    @Override
    public ProjectSnapshotV1 toSnapshot(ProjectWorkspaceDto workspace) {
        List<ProjectSnapshotV1.ProjectMediaV1> projectMedia = workspace.media().stream()
                .map(ProjectSnapshotMapperV1::toSnapshotProjectMedia)
                .toList();
        List<PublishedBlockV1> blocks = workspace.blocks().stream()
                .map(ProjectSnapshotMapperV1::toSnapshotBlock)
                .toList();

        Set<UUID> referencedAssets = new TreeSet<>(ASSET_ORDER);
        workspace.media().forEach(media -> addAsset(referencedAssets, media.assetId()));
        workspace.blocks().forEach(block -> collectBlockAssets(block.payload(), referencedAssets));
        List<PublishedMediaV1> media = referencedAssets.stream()
                .map(mediaQueryService::requireReadyAsset)
                .map(ProjectSnapshotMapperV1::toPublishedMedia)
                .toList();

        return new ProjectSnapshotV1(
                SCHEMA_VERSION,
                workspace.id(),
                workspace.externalKey(),
                workspace.slug(),
                workspace.number(),
                workspace.sortOrder(),
                workspace.featured(),
                toSnapshotLocales(
                        workspace.translations(),
                        copy -> new ProjectSnapshotV1.ProjectCopyV1(
                                copy.status(),
                                copy.eyebrow(),
                                copy.title(),
                                copy.summary(),
                                copy.seoTitle(),
                                copy.seoDescription())),
                workspace.tags().stream()
                        .map(ProjectSnapshotMapperV1::toSnapshotTaxonomy)
                        .toList(),
                workspace.skills().stream()
                        .map(ProjectSnapshotMapperV1::toSnapshotTaxonomy)
                        .toList(),
                projectMedia,
                blocks,
                media);
    }

    @Override
    public ProjectWorkspaceDto restore(
            ProjectSnapshotV1 snapshot, long currentWorkspaceVersion) {
        return new ProjectWorkspaceDto(
                snapshot.projectId(),
                snapshot.externalKey(),
                snapshot.slug(),
                snapshot.number(),
                snapshot.sortOrder(),
                snapshot.featured(),
                true,
                true,
                currentWorkspaceVersion,
                toWorkspaceLocales(
                        snapshot.translations(),
                        copy -> new ProjectWorkspaceDto.ProjectCopy(
                                copy.status(),
                                copy.eyebrow(),
                                copy.title(),
                                copy.summary(),
                                copy.seoTitle(),
                                copy.seoDescription())),
                snapshot.tags().stream()
                        .map(ProjectSnapshotMapperV1::toWorkspaceTaxonomy)
                        .toList(),
                snapshot.skills().stream()
                        .map(ProjectSnapshotMapperV1::toWorkspaceTaxonomy)
                        .toList(),
                snapshot.projectMedia().stream()
                        .map(ProjectSnapshotMapperV1::toWorkspaceProjectMedia)
                        .toList(),
                snapshot.blocks().stream()
                        .map(ProjectSnapshotMapperV1::toWorkspaceBlock)
                        .toList());
    }

    private static ProjectSnapshotV1.TaxonomyRefV1 toSnapshotTaxonomy(
            ProjectWorkspaceDto.TaxonomyRef taxonomy) {
        return new ProjectSnapshotV1.TaxonomyRefV1(
                taxonomy.id(),
                taxonomy.normalizedKey(),
                taxonomy.sortOrder(),
                toSnapshotLocales(taxonomy.names(), Function.identity()));
    }

    private static ProjectWorkspaceDto.TaxonomyRef toWorkspaceTaxonomy(
            ProjectSnapshotV1.TaxonomyRefV1 taxonomy) {
        return new ProjectWorkspaceDto.TaxonomyRef(
                taxonomy.id(),
                taxonomy.normalizedKey(),
                taxonomy.sortOrder(),
                toWorkspaceLocales(taxonomy.names(), Function.identity()));
    }

    private static ProjectSnapshotV1.ProjectMediaV1 toSnapshotProjectMedia(
            ProjectWorkspaceDto.ProjectMedia media) {
        return new ProjectSnapshotV1.ProjectMediaV1(
                media.assetId(),
                media.usage(),
                media.sortOrder(),
                media.layout(),
                media.objectPosition(),
                media.credit(),
                media.sourceUrl());
    }

    private static ProjectWorkspaceDto.ProjectMedia toWorkspaceProjectMedia(
            ProjectSnapshotV1.ProjectMediaV1 media) {
        return new ProjectWorkspaceDto.ProjectMedia(
                media.assetId(),
                media.usage(),
                media.sortOrder(),
                media.layout(),
                media.objectPosition(),
                media.credit(),
                media.sourceUrl());
    }

    private static PublishedBlockV1 toSnapshotBlock(ContentBlockDto block) {
        return new PublishedBlockV1(
                block.id(),
                block.sortOrder(),
                block.visible(),
                PublishedBlockV1.WidthV1.valueOf(block.width().name()),
                PublishedBlockV1.AlignmentV1.valueOf(block.alignment().name()),
                PublishedBlockV1.EmphasisV1.valueOf(block.emphasis().name()),
                block.columns(),
                toSnapshotPayload(block.payload()));
    }

    private static ContentBlockDto toWorkspaceBlock(PublishedBlockV1 block) {
        return new ContentBlockDto(
                block.id(),
                block.sortOrder(),
                block.visible(),
                ContentBlockDto.Width.valueOf(block.width().name()),
                ContentBlockDto.Alignment.valueOf(block.alignment().name()),
                ContentBlockDto.Emphasis.valueOf(block.emphasis().name()),
                block.columns(),
                toWorkspacePayload(block.payload()));
    }

    private static PublishedBlockV1.PayloadV1 toSnapshotPayload(
            ContentBlockDto.Payload payload) {
        if (payload instanceof ContentBlockDto.MarkdownPayload markdown) {
            return new PublishedBlockV1.MarkdownPayloadV1(
                    toSnapshotLocales(markdown.markdown(), Function.identity()));
        }
        if (payload instanceof ContentBlockDto.ImagePayload image) {
            return new PublishedBlockV1.ImagePayloadV1(image.mediaAssetId());
        }
        if (payload instanceof ContentBlockDto.GalleryPayload gallery) {
            return new PublishedBlockV1.GalleryPayloadV1(gallery.mediaAssetIds());
        }
        if (payload instanceof ContentBlockDto.VideoPayload video) {
            return new PublishedBlockV1.VideoPayloadV1(
                    video.provider(),
                    video.url(),
                    video.coverAssetId(),
                    toSnapshotLocales(
                            video.copy(),
                            copy -> new PublishedBlockV1.BlockCopyV1(
                                    copy.title(), copy.description())));
        }
        if (payload instanceof ContentBlockDto.CodePayload code) {
            return new PublishedBlockV1.CodePayloadV1(
                    code.code(),
                    code.language(),
                    code.showLineNumbers(),
                    toSnapshotLocales(
                            code.copy(),
                            copy -> new PublishedBlockV1.BlockCopyV1(
                                    copy.title(), copy.description())));
        }
        if (payload instanceof ContentBlockDto.QuotePayload quote) {
            return new PublishedBlockV1.QuotePayloadV1(
                    toSnapshotLocales(
                            quote.copy(),
                            copy -> new PublishedBlockV1.QuoteCopyV1(
                                    copy.quote(), copy.source())));
        }
        if (payload instanceof ContentBlockDto.MetricsPayload metrics) {
            return new PublishedBlockV1.MetricsPayloadV1(metrics.metrics().stream()
                    .map(ProjectSnapshotMapperV1::toSnapshotMetric)
                    .toList());
        }
        if (payload instanceof ContentBlockDto.DownloadPayload download) {
            return new PublishedBlockV1.DownloadPayloadV1(
                    download.mediaAssetId(),
                    download.externalUrl(),
                    toSnapshotLocales(
                            download.copy(),
                            copy -> new PublishedBlockV1.ActionCopyV1(
                                    copy.label(), copy.description())));
        }
        if (payload instanceof ContentBlockDto.LinkPayload link) {
            return new PublishedBlockV1.LinkPayloadV1(
                    link.url(),
                    link.openNewTab(),
                    toSnapshotLocales(
                            link.copy(),
                            copy -> new PublishedBlockV1.ActionCopyV1(
                                    copy.label(), copy.description())));
        }
        throw new IllegalArgumentException(
                "unsupported project block payload: " + payload.getClass().getName());
    }

    private static ContentBlockDto.Payload toWorkspacePayload(
            PublishedBlockV1.PayloadV1 payload) {
        if (payload instanceof PublishedBlockV1.MarkdownPayloadV1 markdown) {
            return new ContentBlockDto.MarkdownPayload(
                    toWorkspaceLocales(markdown.markdown(), Function.identity()));
        }
        if (payload instanceof PublishedBlockV1.ImagePayloadV1 image) {
            return new ContentBlockDto.ImagePayload(image.mediaAssetId());
        }
        if (payload instanceof PublishedBlockV1.GalleryPayloadV1 gallery) {
            return new ContentBlockDto.GalleryPayload(gallery.mediaAssetIds());
        }
        if (payload instanceof PublishedBlockV1.VideoPayloadV1 video) {
            return new ContentBlockDto.VideoPayload(
                    video.provider(),
                    video.url(),
                    video.coverAssetId(),
                    toWorkspaceLocales(
                            video.copy(),
                            copy -> new ContentBlockDto.BlockCopy(
                                    copy.title(), copy.description())));
        }
        if (payload instanceof PublishedBlockV1.CodePayloadV1 code) {
            return new ContentBlockDto.CodePayload(
                    code.code(),
                    code.language(),
                    code.showLineNumbers(),
                    toWorkspaceLocales(
                            code.copy(),
                            copy -> new ContentBlockDto.BlockCopy(
                                    copy.title(), copy.description())));
        }
        if (payload instanceof PublishedBlockV1.QuotePayloadV1 quote) {
            return new ContentBlockDto.QuotePayload(
                    toWorkspaceLocales(
                            quote.copy(),
                            copy -> new ContentBlockDto.QuoteCopy(
                                    copy.quote(), copy.source())));
        }
        if (payload instanceof PublishedBlockV1.MetricsPayloadV1 metrics) {
            return new ContentBlockDto.MetricsPayload(metrics.metrics().stream()
                    .map(ProjectSnapshotMapperV1::toWorkspaceMetric)
                    .toList());
        }
        if (payload instanceof PublishedBlockV1.DownloadPayloadV1 download) {
            return new ContentBlockDto.DownloadPayload(
                    download.mediaAssetId(),
                    download.externalUrl(),
                    toWorkspaceLocales(
                            download.copy(),
                            copy -> new ContentBlockDto.ActionCopy(
                                    copy.label(), copy.description())));
        }
        if (payload instanceof PublishedBlockV1.LinkPayloadV1 link) {
            return new ContentBlockDto.LinkPayload(
                    link.url(),
                    link.openNewTab(),
                    toWorkspaceLocales(
                            link.copy(),
                            copy -> new ContentBlockDto.ActionCopy(
                                    copy.label(), copy.description())));
        }
        throw new IllegalArgumentException(
                "unsupported snapshot block payload: " + payload.getClass().getName());
    }

    private static PublishedBlockV1.MetricV1 toSnapshotMetric(ContentBlockDto.Metric metric) {
        return new PublishedBlockV1.MetricV1(
                metric.id(),
                metric.sortOrder(),
                metric.numericValue(),
                toSnapshotLocales(
                        metric.copy(),
                        copy -> new PublishedBlockV1.MetricCopyV1(
                                copy.label(), copy.value(), copy.suffix())));
    }

    private static ContentBlockDto.Metric toWorkspaceMetric(PublishedBlockV1.MetricV1 metric) {
        return new ContentBlockDto.Metric(
                metric.id(),
                metric.sortOrder(),
                metric.numericValue(),
                toWorkspaceLocales(
                        metric.copy(),
                        copy -> new ContentBlockDto.MetricCopy(
                                copy.label(), copy.value(), copy.suffix())));
    }

    private static void collectBlockAssets(
            ContentBlockDto.Payload payload, Set<UUID> referencedAssets) {
        if (payload instanceof ContentBlockDto.ImagePayload image) {
            addAsset(referencedAssets, image.mediaAssetId());
        } else if (payload instanceof ContentBlockDto.GalleryPayload gallery) {
            gallery.mediaAssetIds().forEach(assetId -> addAsset(referencedAssets, assetId));
        } else if (payload instanceof ContentBlockDto.VideoPayload video) {
            addAsset(referencedAssets, video.coverAssetId());
        } else if (payload instanceof ContentBlockDto.DownloadPayload download) {
            addAsset(referencedAssets, download.mediaAssetId());
        }
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
