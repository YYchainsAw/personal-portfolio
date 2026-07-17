package xyz.yychainsaw.portfolio.content.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class PortfolioImportSemanticsTest {
    @Test
    void derivesFrozenStableIdsAndTagKeys() {
        assertThat(PortfolioImportSemantics.stableId(
                        "project", "ue-environment-study"))
                .isEqualTo(UUID.fromString(
                        "d1228ce4-255d-33b6-909a-1eaec8c53374"));
        assertThat(PortfolioImportSemantics.normalizeTagKey("  Git + LFS  "))
                .isEqualTo("git-+-lfs");
        assertThat(PortfolioImportSemantics.tagKey(
                        "ue-environment-study", 2, " \t"))
                .isEqualTo(
                        "untranslated-67ddc656-25ab-3d53-9a36-308687b63873");
    }
}
