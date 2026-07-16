package xyz.yychainsaw.portfolio.api.admin.media;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record MediaTranslationInput(
        @NotNull(message = "must be provided")
        @Pattern(regexp = "(?:zh-CN|en)", message = "must be zh-CN or en")
        String locale,
        @Size(max = 500, message = "must be at most 500 UTF-16 units")
        String altText,
        @Size(max = 1000, message = "must be at most 1000 UTF-16 units")
        String caption,
        @Size(max = 300, message = "must be at most 300 UTF-16 units")
        String credit,
        @Size(max = 2048, message = "must be at most 2048 UTF-16 units")
        @Pattern(regexp = "https://[^\\s]+", message = "must be an HTTPS URL")
        String sourceUrl) { }
