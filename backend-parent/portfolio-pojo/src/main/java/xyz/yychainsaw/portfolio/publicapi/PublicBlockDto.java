package xyz.yychainsaw.portfolio.publicapi;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record PublicBlockDto(
        UUID id,
        String type,
        int sortOrder,
        String width,
        String alignment,
        String emphasis,
        int columns,
        Payload payload) {
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = Markdown.class, name = "MARKDOWN"),
            @JsonSubTypes.Type(value = Image.class, name = "IMAGE"),
            @JsonSubTypes.Type(value = Gallery.class, name = "GALLERY"),
            @JsonSubTypes.Type(value = Video.class, name = "VIDEO"),
            @JsonSubTypes.Type(value = Code.class, name = "CODE"),
            @JsonSubTypes.Type(value = Quote.class, name = "QUOTE"),
            @JsonSubTypes.Type(value = Metrics.class, name = "METRICS"),
            @JsonSubTypes.Type(value = Download.class, name = "DOWNLOAD"),
            @JsonSubTypes.Type(value = Link.class, name = "LINK")
    })
    public sealed interface Payload permits
            Markdown, Image, Gallery, Video, Code, Quote, Metrics, Download, Link {
    }

    public record Markdown(String html) implements Payload {
    }

    public record Image(PublicMediaDto media) implements Payload {
    }

    public record Gallery(List<PublicMediaDto> media) implements Payload {
        public Gallery {
            media = List.copyOf(media);
        }
    }

    public record Video(
            String provider,
            String embedUrl,
            PublicMediaDto cover,
            String title,
            String description) implements Payload {
    }

    public record Code(
            String code,
            String language,
            boolean showLineNumbers,
            String title,
            String description) implements Payload {
    }

    public record Quote(String quote, String source) implements Payload {
    }

    public record Metrics(List<Metric> metrics) implements Payload {
        public Metrics {
            metrics = List.copyOf(metrics);
        }
    }

    public record Metric(
            UUID id,
            BigDecimal numericValue,
            String label,
            String value,
            String suffix) {
    }

    public record Download(
            String href,
            String label,
            String description,
            String mimeType,
            Long byteSize) implements Payload {
    }

    public record Link(
            String href,
            boolean openNewTab,
            String label,
            String description) implements Payload {
    }
}
