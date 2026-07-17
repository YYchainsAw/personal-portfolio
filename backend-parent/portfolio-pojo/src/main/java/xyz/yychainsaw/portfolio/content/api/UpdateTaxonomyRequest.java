package xyz.yychainsaw.portfolio.content.api;

import java.util.Map;
import java.util.Objects;

public record UpdateTaxonomyRequest(
        long expectedVersion,
        Map<LocaleCode, String> names) {
    public UpdateTaxonomyRequest {
        names = Map.copyOf(Objects.requireNonNull(names, "names"));
    }
}
