package xyz.yychainsaw.portfolio.content.importer;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;

final class PortfolioImportSemantics {
    private PortfolioImportSemantics() {}

    static UUID stableId(String namespace, String externalKey) {
        Objects.requireNonNull(namespace, "namespace is required");
        Objects.requireNonNull(externalKey, "external key is required");
        return UUID.nameUUIDFromBytes((namespace + ":" + externalKey).getBytes(UTF_8));
    }

    static String normalizeTagKey(String englishName) {
        Objects.requireNonNull(englishName, "English tag is required");
        return Normalizer.normalize(englishName, Normalizer.Form.NFKC)
                .strip()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9+.#]+", "-")
                .replaceAll("(^-|-$)", "");
    }

    static String tagKey(String projectId, int tagIndex, String englishName) {
        Objects.requireNonNull(projectId, "project id is required");
        Objects.requireNonNull(englishName, "English tag is required");
        if (!englishName.isBlank()) {
            return normalizeTagKey(englishName);
        }
        UUID missing = UUID.nameUUIDFromBytes(
                ("tag-missing:" + projectId + ":" + tagIndex).getBytes(UTF_8));
        return "untranslated-" + missing.toString().toLowerCase(Locale.ROOT);
    }

    static Counts counts(PortfolioImportV1 payload) {
        Objects.requireNonNull(payload, "portfolio import payload is required");
        PortfolioImportV1.PortfolioCopy english =
                payload.portfolioContent().get(LocaleCode.EN);
        int projectCount = english == null ? 0 : english.projects().size();
        int mediaCount = 1 + payload.projectAssets().size();
        Set<String> keys = new HashSet<>();
        if (english != null) {
            for (PortfolioImportV1.ProjectCopy project : english.projects()) {
                for (int index = 0; index < project.tags().size(); index++) {
                    String key = tagKey(project.id(), index, project.tags().get(index));
                    if (!key.isEmpty()) {
                        keys.add(key);
                    }
                }
            }
        }
        return new Counts(projectCount, mediaCount, keys.size());
    }

    record Counts(int projectCount, int mediaCount, int tagCount) {}
}
