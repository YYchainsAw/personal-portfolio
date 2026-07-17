package xyz.yychainsaw.portfolio.content.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;

class PortfolioImportReaderTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final PortfolioImportReader reader = new PortfolioImportReader(mapper);

    @TempDir Path temporaryDirectory;

    @Test
    void readsAndHashesTheExactSameBytesBeforeTypedBinding() throws Exception {
        byte[] bytes = mapper.writeValueAsBytes(PortfolioImportTestData.validPayload());
        Path input = temporaryDirectory.resolve("portfolio.json");
        Files.write(input, bytes);

        PortfolioImportReader.ReadResult result = reader.read(input);

        assertThat(result.payload()).contains(PortfolioImportTestData.validPayload());
        assertThat(result.sha256()).isEqualTo(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes)));
        assertThat(result.issues()).isEmpty();
    }

    @Test
    void readsCanonicalBackendFixtureWithFrozenChecksumCountsAndTagKeys() {
        Path input = Path.of("src/test/resources/import/portfolio-v1-valid.json");

        PortfolioImportReader.ReadResult result = reader.read(input);

        assertThat(result.sha256())
                .isEqualTo("3c2ae523f9420de3c2954abd3f5f48b7bd942ff9fc6ae3d592b431a6c60e45cf");
        assertThat(result.issues()).isEmpty();
        PortfolioImportV1 payload = result.payload().orElseThrow();
        assertThat(PortfolioImportSemantics.counts(payload))
                .isEqualTo(new PortfolioImportSemantics.Counts(3, 4, 8));

        Set<String> keys = new LinkedHashSet<>();
        for (PortfolioImportV1.ProjectCopy project :
                payload.portfolioContent().get(LocaleCode.EN).projects()) {
            for (int index = 0; index < project.tags().size(); index++) {
                keys.add(PortfolioImportSemantics.tagKey(
                        project.id(), index, project.tags().get(index)));
            }
        }
        assertThat(keys)
                .containsExactlyInAnyOrder(
                        "ue5",
                        "blueprint",
                        "level-design",
                        "c++",
                        "gameplay",
                        "devlog",
                        "git-+-lfs",
                        "profiling");
    }

    @Test
    void collapsesDuplicateKeysAndTrailingTokensToOneStableRootIssue() throws Exception {
        for (String malformed : new String[] {
            "{\"schemaVersion\":1,\"schemaVersion\":1}", "{} {}"
        }) {
            Path input = temporaryDirectory.resolve(Integer.toHexString(malformed.hashCode()) + ".json");
            Files.writeString(input, malformed, StandardCharsets.UTF_8);

            PortfolioImportReader.ReadResult result = reader.read(input);

            assertThat(result.payload()).isEmpty();
            assertThat(result.issues())
                    .extracting(ImportIssue::severity, ImportIssue::path, ImportIssue::code)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple(
                            ImportIssue.Severity.STRUCTURE_ERROR,
                            "$",
                            "IMPORT_JSON_INVALID"));
        }
    }

    @Test
    void reportsNestedUnknownWrongTypeMissingNullFormatAndLocaleEvidenceAtConcretePaths()
            throws Exception {
        ObjectNode root = (ObjectNode) mapper.valueToTree(PortfolioImportTestData.validPayload());
        root.put("unknownRoot", true);
        ObjectNode identity = (ObjectNode) root.get("identity");
        identity.put("monogram", 42);
        identity.remove("nameZh");
        identity.putNull("nameEn");
        ((ObjectNode) root.get("heroAsset")).put("sourceUrl", "http://example.com/not-https");
        ObjectNode portfolioContent = (ObjectNode) root.get("portfolioContent");
        JsonNode english = portfolioContent.remove("en");
        portfolioContent.set("fr", english);
        ((ObjectNode) portfolioContent.at("/zh-CN/projects/0")).put("unexpected", "value");
        Path input = temporaryDirectory.resolve("invalid.json");
        mapper.writeValue(input.toFile(), root);

        PortfolioImportReader.ReadResult result = reader.read(input);

        assertThat(result.payload()).isEmpty();
        assertThat(result.issues()).extracting(ImportIssue::path, ImportIssue::code).contains(
                org.assertj.core.groups.Tuple.tuple("identity.monogram", "IMPORT_JSON_INVALID"),
                org.assertj.core.groups.Tuple.tuple(
                        "identity.nameZh", "IMPORT_REQUIRED_FIELD_MISSING"),
                org.assertj.core.groups.Tuple.tuple(
                        "identity.nameEn", "IMPORT_REQUIRED_FIELD_MISSING"),
                org.assertj.core.groups.Tuple.tuple("heroAsset.sourceUrl", "IMPORT_JSON_INVALID"),
                org.assertj.core.groups.Tuple.tuple("portfolioContent", "IMPORT_LOCALE_SET_INVALID"),
                org.assertj.core.groups.Tuple.tuple(
                        "portfolioContent.en", "IMPORT_REQUIRED_FIELD_MISSING"),
                org.assertj.core.groups.Tuple.tuple("portfolioContent.fr", "IMPORT_JSON_INVALID"),
                org.assertj.core.groups.Tuple.tuple(
                        "portfolioContent.zh-CN.projects[0].unexpected", "IMPORT_JSON_INVALID"),
                org.assertj.core.groups.Tuple.tuple("unknownRoot", "IMPORT_JSON_INVALID"));
        assertThat(result.issues()).isSortedAccordingTo(issueComparator());
    }

    @Test
    void rejectsUppercaseHttpsSchemeBeforeTypedBinding() throws Exception {
        ObjectNode root = (ObjectNode) mapper.valueToTree(PortfolioImportTestData.validPayload());
        ((ObjectNode) root.get("heroAsset"))
                .put("sourceUrl", "HTTPS://example.com/portfolio-source");
        Path input = temporaryDirectory.resolve("uppercase-https.json");
        mapper.writeValue(input.toFile(), root);

        PortfolioImportReader.ReadResult result = reader.read(input);

        assertThat(result.payload()).isEmpty();
        assertThat(result.issues())
                .extracting(ImportIssue::path, ImportIssue::code)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        "heroAsset.sourceUrl", "IMPORT_JSON_INVALID"));
    }

    @Test
    void acceptsPresentBlankTranslationStringsForSemanticWarnings() throws Exception {
        Path input = temporaryDirectory.resolve("blank.json");
        mapper.writeValue(input.toFile(), PortfolioImportTestData.incompleteTranslationPayload());

        PortfolioImportReader.ReadResult result = reader.read(input);

        assertThat(result.payload()).isPresent();
        assertThat(result.issues()).isEmpty();
    }

    @Test
    void turnsFileIoFailureIntoFixedCauseFreeDomainFailure() {
        Path missing = temporaryDirectory.resolve("private-absolute-name.json");

        assertThatThrownBy(() -> reader.read(missing))
                .isInstanceOfSatisfying(DomainException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("IMPORT_INPUT_UNREADABLE");
                    assertThat(failure.getCause()).isNull();
                    assertThat(failure.getMessage()).doesNotContain(missing.toString());
                });
    }

    private static Comparator<ImportIssue> issueComparator() {
        return Comparator.comparing(ImportIssue::severity)
                .thenComparing(ImportIssue::path)
                .thenComparing(ImportIssue::code)
                .thenComparing(ImportIssue::message);
    }
}
