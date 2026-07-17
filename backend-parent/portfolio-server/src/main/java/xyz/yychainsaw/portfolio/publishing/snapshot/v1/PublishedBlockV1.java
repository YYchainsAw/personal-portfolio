package xyz.yychainsaw.portfolio.publishing.snapshot.v1;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record PublishedBlockV1(
        UUID id,
        int sortOrder,
        boolean visible,
        WidthV1 width,
        AlignmentV1 alignment,
        EmphasisV1 emphasis,
        int columns,
        PayloadV1 payload) {
    public enum WidthV1 {
        NARROW,
        STANDARD,
        WIDE,
        FULL
    }

    public enum AlignmentV1 {
        LEFT,
        CENTER,
        RIGHT
    }

    public enum EmphasisV1 {
        NONE,
        SOFT,
        STRONG
    }

    public record BlockCopyV1(String title, String description) {}

    public record ActionCopyV1(String label, String description) {}

    public record QuoteCopyV1(String quote, String source) {}

    public record MetricV1(
            UUID id,
            int sortOrder,
            BigDecimal numericValue,
            Map<LocaleV1, MetricCopyV1> copy) {
        public MetricV1 {
            copy = Map.copyOf(copy);
        }
    }

    public record MetricCopyV1(String label, String value, String suffix) {}

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = MarkdownPayloadV1.class, name = "MARKDOWN"),
            @JsonSubTypes.Type(value = ImagePayloadV1.class, name = "IMAGE"),
            @JsonSubTypes.Type(value = GalleryPayloadV1.class, name = "GALLERY"),
            @JsonSubTypes.Type(value = VideoPayloadV1.class, name = "VIDEO"),
            @JsonSubTypes.Type(value = CodePayloadV1.class, name = "CODE"),
            @JsonSubTypes.Type(value = QuotePayloadV1.class, name = "QUOTE"),
            @JsonSubTypes.Type(value = MetricsPayloadV1.class, name = "METRICS"),
            @JsonSubTypes.Type(value = DownloadPayloadV1.class, name = "DOWNLOAD"),
            @JsonSubTypes.Type(value = LinkPayloadV1.class, name = "LINK")
    })
    public sealed interface PayloadV1 permits
            MarkdownPayloadV1,
            ImagePayloadV1,
            GalleryPayloadV1,
            VideoPayloadV1,
            CodePayloadV1,
            QuotePayloadV1,
            MetricsPayloadV1,
            DownloadPayloadV1,
            LinkPayloadV1 {}

    public record MarkdownPayloadV1(
            Map<LocaleV1, String> markdown) implements PayloadV1 {
        public MarkdownPayloadV1 {
            markdown = Map.copyOf(markdown);
        }
    }

    public record ImagePayloadV1(UUID mediaAssetId) implements PayloadV1 {}

    public record GalleryPayloadV1(List<UUID> mediaAssetIds) implements PayloadV1 {
        public GalleryPayloadV1 {
            mediaAssetIds = List.copyOf(mediaAssetIds);
        }
    }

    public record VideoPayloadV1(
            String provider,
            URI url,
            UUID coverAssetId,
            Map<LocaleV1, BlockCopyV1> copy) implements PayloadV1 {
        public VideoPayloadV1 {
            copy = Map.copyOf(copy);
        }
    }

    public record CodePayloadV1(
            String code,
            String language,
            boolean showLineNumbers,
            Map<LocaleV1, BlockCopyV1> copy) implements PayloadV1 {
        public CodePayloadV1 {
            copy = Map.copyOf(copy);
        }
    }

    public record QuotePayloadV1(
            Map<LocaleV1, QuoteCopyV1> copy) implements PayloadV1 {
        public QuotePayloadV1 {
            copy = Map.copyOf(copy);
        }
    }

    public record MetricsPayloadV1(List<MetricV1> metrics) implements PayloadV1 {
        public MetricsPayloadV1 {
            metrics = List.copyOf(metrics);
        }
    }

    public record DownloadPayloadV1(
            UUID mediaAssetId,
            URI externalUrl,
            Map<LocaleV1, ActionCopyV1> copy) implements PayloadV1 {
        public DownloadPayloadV1 {
            copy = Map.copyOf(copy);
        }
    }

    public record LinkPayloadV1(
            URI url,
            boolean openNewTab,
            Map<LocaleV1, ActionCopyV1> copy) implements PayloadV1 {
        public LinkPayloadV1 {
            copy = Map.copyOf(copy);
        }
    }
}
