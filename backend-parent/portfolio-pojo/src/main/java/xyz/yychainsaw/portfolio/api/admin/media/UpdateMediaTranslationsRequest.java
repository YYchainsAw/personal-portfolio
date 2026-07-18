package xyz.yychainsaw.portfolio.api.admin.media;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

public record UpdateMediaTranslationsRequest(
        @NotNull(message = "must be provided")
        @PositiveOrZero(message = "must be zero or greater")
        Long expectedVersion,
        @NotNull(message = "must be provided")
        @Size(min = 2, max = 2, message = "must contain exactly two translations")
        List<@NotNull(message = "must be provided") @Valid MediaTranslationInput> translations) { }
