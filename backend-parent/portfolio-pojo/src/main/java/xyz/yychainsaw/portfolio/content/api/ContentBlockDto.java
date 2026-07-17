package xyz.yychainsaw.portfolio.content.api;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ContentBlockDto(
        UUID id,
        int sortOrder,
        boolean visible,
        Width width,
        Alignment alignment,
        Emphasis emphasis,
        int columns,
        Payload payload
) {
    public enum Width { NARROW, STANDARD, WIDE, FULL }

    public enum Alignment { LEFT, CENTER, RIGHT }

    public enum Emphasis { NONE, SOFT, STRONG }

    public record BlockCopy(String title, String description) {}

    public record ActionCopy(String label, String description) {}

    public record QuoteCopy(String quote, String source) {}

    public record Metric(
            UUID id,
            int sortOrder,
            BigDecimal numericValue,
            Map<LocaleCode, MetricCopy> copy) {
        public Metric {
            copy = Map.copyOf(copy);
        }
    }

    public record MetricCopy(String label, String value, String suffix) {}

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = MarkdownPayload.class, name = "MARKDOWN"),
            @JsonSubTypes.Type(value = ImagePayload.class, name = "IMAGE"),
            @JsonSubTypes.Type(value = GalleryPayload.class, name = "GALLERY"),
            @JsonSubTypes.Type(value = VideoPayload.class, name = "VIDEO"),
            @JsonSubTypes.Type(value = CodePayload.class, name = "CODE"),
            @JsonSubTypes.Type(value = QuotePayload.class, name = "QUOTE"),
            @JsonSubTypes.Type(value = MetricsPayload.class, name = "METRICS"),
            @JsonSubTypes.Type(value = DownloadPayload.class, name = "DOWNLOAD"),
            @JsonSubTypes.Type(value = LinkPayload.class, name = "LINK")
    })
    public sealed interface Payload permits
            MarkdownPayload,
            ImagePayload,
            GalleryPayload,
            VideoPayload,
            CodePayload,
            QuotePayload,
            MetricsPayload,
            DownloadPayload,
            LinkPayload {}

    public record MarkdownPayload(
            Map<LocaleCode, String> markdown) implements Payload {
        public MarkdownPayload {
            markdown = Map.copyOf(markdown);
        }
    }

    public record ImagePayload(UUID mediaAssetId) implements Payload {}

    public record GalleryPayload(
            List<UUID> mediaAssetIds) implements Payload {
        public GalleryPayload {
            mediaAssetIds = List.copyOf(mediaAssetIds);
        }
    }

    public record VideoPayload(
            String provider,
            URI url,
            UUID coverAssetId,
            Map<LocaleCode, BlockCopy> copy) implements Payload {
        public VideoPayload {
            copy = Map.copyOf(copy);
        }
    }

    public record CodePayload(
            String code,
            String language,
            boolean showLineNumbers,
            Map<LocaleCode, BlockCopy> copy) implements Payload {
        public CodePayload {
            copy = Map.copyOf(copy);
        }
    }

    public record QuotePayload(
            Map<LocaleCode, QuoteCopy> copy) implements Payload {
        public QuotePayload {
            copy = Map.copyOf(copy);
        }
    }

    public record MetricsPayload(
            List<Metric> metrics) implements Payload {
        public MetricsPayload {
            metrics = List.copyOf(metrics);
        }
    }

    public record DownloadPayload(
            UUID mediaAssetId,
            URI externalUrl,
            Map<LocaleCode, ActionCopy> copy) implements Payload {
        public DownloadPayload {
            copy = Map.copyOf(copy);
        }
    }

    public record LinkPayload(
            URI url,
            boolean openNewTab,
            Map<LocaleCode, ActionCopy> copy) implements Payload {
        public LinkPayload {
            copy = Map.copyOf(copy);
        }
    }
}
