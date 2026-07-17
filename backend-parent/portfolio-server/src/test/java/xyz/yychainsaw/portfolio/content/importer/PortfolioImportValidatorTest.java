package xyz.yychainsaw.portfolio.content.importer;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.media.application.MediaFileInspector;

class PortfolioImportValidatorTest {
    private final PortfolioImportValidator validator =
            new PortfolioImportValidator(new MediaFileInspector());

    @TempDir Path assetRoot;

    @BeforeEach
    void createValidAssets() throws IOException {
        Path images = Files.createDirectories(assetRoot.resolve("images"));
        writePng(images.resolve("hero.png"));
        writePng(images.resolve("project.png"));
    }

    @Test
    void reportsExactlyTheSevenFrozenPlaceholdersInStableOrder() {
        List<ImportIssue> issues = validator.validate(
                PortfolioImportTestData.placeholderPayload(), assetRoot);

        assertThat(issues)
                .allMatch(issue -> issue.severity() == ImportIssue.Severity.PUBLISH_WARNING)
                .allMatch(issue -> issue.code().equals("PLACEHOLDER_CONTENT_PRESENT"))
                .extracting(ImportIssue::path)
                .containsExactly(
                        "identity.email",
                        "portfolioContent.en.contact.emailLabel",
                        "portfolioContent.en.hero.visualLabel",
                        "portfolioContent.en.work.imageNotice",
                        "portfolioContent.zh-CN.contact.emailLabel",
                        "portfolioContent.zh-CN.hero.visualLabel",
                        "portfolioContent.zh-CN.work.imageNotice");
    }

    @Test
    void canonicalBackendFixtureAndAssetsProduceOnlyTheSevenFrozenWarnings() {
        PortfolioImportReader.ReadResult read = new PortfolioImportReader(new ObjectMapper())
                .read(Path.of("src/test/resources/import/portfolio-v1-valid.json"));

        List<ImportIssue> issues = validator.validate(
                read.payload().orElseThrow(), Path.of("src/test/resources/import/assets"));

        assertThat(issues)
                .allMatch(issue -> issue.severity() == ImportIssue.Severity.PUBLISH_WARNING)
                .allMatch(issue -> issue.code().equals("PLACEHOLDER_CONTENT_PRESENT"))
                .extracting(ImportIssue::path)
                .containsExactly(
                        "identity.email",
                        "portfolioContent.en.contact.emailLabel",
                        "portfolioContent.en.hero.visualLabel",
                        "portfolioContent.en.work.imageNotice",
                        "portfolioContent.zh-CN.contact.emailLabel",
                        "portfolioContent.zh-CN.hero.visualLabel",
                        "portfolioContent.zh-CN.work.imageNotice");
    }

    @Test
    void reportsExactlyEveryPresentBlankTranslationLeaf() {
        List<ImportIssue> issues = validator.validate(
                PortfolioImportTestData.incompleteTranslationPayload(), assetRoot);

        assertThat(issues)
                .allMatch(issue -> issue.severity() == ImportIssue.Severity.PUBLISH_WARNING)
                .allMatch(issue -> issue.code().equals("IMPORT_TRANSLATION_INCOMPLETE"))
                .extracting(ImportIssue::path)
                .containsExactly(
                        "heroAsset.alt.en",
                        "portfolioContent.en.hero.headline",
                        "portfolioContent.zh-CN.projects[0].summary");
    }

    @Test
    void reportsFrozenBilingualMismatchAndLaterDuplicatePaths() {
        PortfolioImportV1 base = PortfolioImportTestData.validPayload();
        PortfolioImportV1.PortfolioCopy english = base.portfolioContent().get(LocaleCode.EN);
        PortfolioImportV1.PortfolioCopy chinese = base.portfolioContent().get(LocaleCode.ZH_CN);

        PortfolioImportV1.ProjectCopy duplicatedEnglishProject = project(
                english.projects().get(0), "project-one", "02", List.of("UE5", "ue5"));
        PortfolioImportV1.PortfolioCopy changedEnglish = copy(
                english,
                List.of(english.projects().get(0), duplicatedEnglishProject),
                english.roadmap(),
                english.about(),
                contact(english.contact(), "other@example.com"));
        PortfolioImportV1.PortfolioCopy changedChinese = copy(
                chinese,
                List.of(project(chinese.projects().get(0), "project-one", "99", List.of("引擎"))),
                roadmapWithDuplicateStage(chinese.roadmap()),
                new PortfolioImportV1.About(
                        chinese.about().label(),
                        chinese.about().title(),
                        chinese.about().statement(),
                        chinese.about().focusLabel(),
                        chinese.about().focusTitle(),
                        chinese.about().focusIntro(),
                        List.of(),
                        chinese.about().skills()),
                chinese.contact());
        PortfolioImportV1 changed = new PortfolioImportV1(
                base.schemaVersion(),
                base.identity(),
                base.heroAsset(),
                List.of(base.projectAssets().get(0), base.projectAssets().get(0)),
                Map.of(LocaleCode.EN, changedEnglish, LocaleCode.ZH_CN, changedChinese));

        List<ImportIssue> issues = validator.validate(changed, assetRoot);

        assertThat(issues).extracting(ImportIssue::path, ImportIssue::code).contains(
                org.assertj.core.groups.Tuple.tuple("projectAssets[1].id", "IMPORT_DUPLICATE_ID"),
                org.assertj.core.groups.Tuple.tuple(
                        "portfolioContent.en.projects[1].id", "IMPORT_DUPLICATE_ID"),
                org.assertj.core.groups.Tuple.tuple(
                        "portfolioContent.en.projects[1].tags[1]", "IMPORT_DUPLICATE_ID"),
                org.assertj.core.groups.Tuple.tuple(
                        "portfolioContent.zh-CN.about.facts", "IMPORT_JSON_INVALID"),
                org.assertj.core.groups.Tuple.tuple(
                        "portfolioContent.zh-CN.projects", "IMPORT_PROJECT_LOCALE_MISMATCH"),
                org.assertj.core.groups.Tuple.tuple(
                        "portfolioContent.zh-CN.projects[0].number",
                        "IMPORT_PROJECT_LOCALE_MISMATCH"),
                org.assertj.core.groups.Tuple.tuple(
                        "portfolioContent.zh-CN.roadmap.stages",
                        "IMPORT_ROADMAP_LOCALE_MISMATCH"),
                org.assertj.core.groups.Tuple.tuple(
                        "portfolioContent.zh-CN.roadmap.stages[1].id", "IMPORT_DUPLICATE_ID"),
                org.assertj.core.groups.Tuple.tuple(
                        "portfolioContent.en.contact.email", "IMPORT_JSON_INVALID"));
        assertThat(issues).isSortedAccordingTo(issueComparator());
    }

    @Test
    void acceptsExactDuplicatePublicPathMetadataButRejectsLaterConflict() {
        PortfolioImportV1 base = PortfolioImportTestData.validPayload();
        PortfolioImportV1.HeroAsset hero = base.heroAsset();
        PortfolioImportV1.ProjectAsset exact = new PortfolioImportV1.ProjectAsset(
                "project-one",
                hero.image(),
                "wide",
                hero.objectPosition(),
                hero.credit(),
                hero.sourceUrl(),
                hero.alt());
        PortfolioImportV1 accepted = withProjectAssets(base, List.of(exact));
        assertThat(validator.validate(accepted, assetRoot)).isEmpty();

        PortfolioImportV1.ProjectAsset conflict = new PortfolioImportV1.ProjectAsset(
                exact.id(),
                exact.image(),
                exact.layout(),
                exact.objectPosition(),
                "Different credit",
                exact.sourceUrl(),
                exact.alt());
        PortfolioImportV1 rejected = withProjectAssets(base, List.of(conflict));

        assertThat(validator.validate(rejected, assetRoot))
                .extracting(ImportIssue::path, ImportIssue::code)
                .contains(org.assertj.core.groups.Tuple.tuple(
                        "projectAssets[0].image", "IMPORT_JSON_INVALID"));
    }

    @Test
    void rejectsInvalidOutsideUnsupportedAndEmptyAssetPathsAtConcreteLeaves() throws Exception {
        PortfolioImportV1 base = PortfolioImportTestData.validPayload();
        Path images = assetRoot.resolve("images");
        Files.writeString(images.resolve("empty.png"), "");
        Files.writeString(images.resolve("notes.txt"), "content");
        List<PortfolioImportV1.ProjectAsset> assets = new ArrayList<>();
        assets.add(asset(base.projectAssets().get(0), "a", "/images/../hero.png"));
        assets.add(asset(base.projectAssets().get(0), "b", "/images/empty.png"));
        assets.add(asset(base.projectAssets().get(0), "c", "/images/notes.txt"));
        assets.add(asset(base.projectAssets().get(0), "d", "/images/project.png "));
        assets.add(asset(base.projectAssets().get(0), "e", "/images/project\u0001.png"));
        PortfolioImportV1 changed = withProjectAssets(base, assets);

        List<ImportIssue> issues = validator.validate(changed, assetRoot);

        assertThat(issues).extracting(ImportIssue::path, ImportIssue::code).contains(
                org.assertj.core.groups.Tuple.tuple(
                        "projectAssets[0].image", "IMPORT_ASSET_PATH_INVALID"),
                org.assertj.core.groups.Tuple.tuple(
                        "projectAssets[1].image", "IMPORT_ASSET_PATH_INVALID"),
                org.assertj.core.groups.Tuple.tuple(
                        "projectAssets[2].image", "IMPORT_ASSET_PATH_INVALID"));
        assertThat(issues).extracting(ImportIssue::path, ImportIssue::code).contains(
                org.assertj.core.groups.Tuple.tuple(
                        "projectAssets[3].image", "IMPORT_ASSET_PATH_INVALID"),
                org.assertj.core.groups.Tuple.tuple(
                        "projectAssets[4].image", "IMPORT_ASSET_PATH_INVALID"));
    }

    @Test
    void rejectsCorruptMediaContentAtTheConcreteAssetLeaf() throws Exception {
        PortfolioImportV1 base = PortfolioImportTestData.validPayload();
        Path corrupt = assetRoot.resolve("images/corrupt.jpg");
        Files.writeString(corrupt, "this is not a JPEG");
        PortfolioImportV1.ProjectAsset project = base.projectAssets().get(0);
        PortfolioImportV1 changed = withProjectAssets(
                base, List.of(asset(project, project.id(), "/images/corrupt.jpg")));

        assertThat(validator.validate(changed, assetRoot))
                .extracting(ImportIssue::path, ImportIssue::code)
                .contains(org.assertj.core.groups.Tuple.tuple(
                        "projectAssets[0].image", "IMPORT_ASSET_PATH_INVALID"));
    }

    @Test
    void rejectsUppercaseHttpsSchemeAtTheConcreteSourceLeaf() {
        PortfolioImportV1 base = PortfolioImportTestData.validPayload();
        PortfolioImportV1.HeroAsset hero = base.heroAsset();
        PortfolioImportV1 changed = new PortfolioImportV1(
                base.schemaVersion(),
                base.identity(),
                new PortfolioImportV1.HeroAsset(
                        hero.image(),
                        hero.objectPosition(),
                        hero.credit(),
                        URI.create("HTTPS://example.com/portfolio-source"),
                        hero.alt()),
                base.projectAssets(),
                base.portfolioContent());

        assertThat(validator.validate(changed, assetRoot))
                .extracting(ImportIssue::path, ImportIssue::code)
                .contains(org.assertj.core.groups.Tuple.tuple(
                        "heroAsset.sourceUrl", "IMPORT_JSON_INVALID"));
    }

    private static PortfolioImportV1 withProjectAssets(
            PortfolioImportV1 base, List<PortfolioImportV1.ProjectAsset> projectAssets) {
        return new PortfolioImportV1(
                base.schemaVersion(),
                base.identity(),
                base.heroAsset(),
                projectAssets,
                base.portfolioContent());
    }

    private static PortfolioImportV1.ProjectAsset asset(
            PortfolioImportV1.ProjectAsset base, String id, String image) {
        return new PortfolioImportV1.ProjectAsset(
                id,
                image,
                base.layout(),
                base.objectPosition(),
                base.credit(),
                base.sourceUrl(),
                base.alt());
    }

    private static PortfolioImportV1.ProjectCopy project(
            PortfolioImportV1.ProjectCopy base, String id, String number, List<String> tags) {
        return new PortfolioImportV1.ProjectCopy(
                id,
                number,
                base.status(),
                base.eyebrow(),
                base.title(),
                base.summary(),
                tags);
    }

    private static PortfolioImportV1.PortfolioCopy copy(
            PortfolioImportV1.PortfolioCopy base,
            List<PortfolioImportV1.ProjectCopy> projects,
            PortfolioImportV1.Roadmap roadmap,
            PortfolioImportV1.About about,
            PortfolioImportV1.Contact contact) {
        return new PortfolioImportV1.PortfolioCopy(
                base.seo(),
                base.a11y(),
                base.nav(),
                base.hero(),
                about,
                base.work(),
                projects,
                roadmap,
                contact);
    }

    private static PortfolioImportV1.Contact contact(
            PortfolioImportV1.Contact base, String email) {
        return new PortfolioImportV1.Contact(
                base.label(),
                base.title(),
                base.introduction(),
                base.emailLabel(),
                email,
                base.workCta(),
                base.roadmapCta(),
                base.footerNote());
    }

    private static PortfolioImportV1.Roadmap roadmapWithDuplicateStage(
            PortfolioImportV1.Roadmap base) {
        PortfolioImportV1.RoadmapStage stage = base.stages().get(0);
        return new PortfolioImportV1.Roadmap(
                base.label(), base.title(), base.introduction(), List.of(stage, stage));
    }

    private static Comparator<ImportIssue> issueComparator() {
        return Comparator.comparing(ImportIssue::severity)
                .thenComparing(ImportIssue::path)
                .thenComparing(ImportIssue::code)
                .thenComparing(ImportIssue::message);
    }

    private static void writePng(Path path) throws IOException {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        if (!ImageIO.write(image, "png", path.toFile())) {
            throw new IOException("PNG writer unavailable");
        }
    }
}
