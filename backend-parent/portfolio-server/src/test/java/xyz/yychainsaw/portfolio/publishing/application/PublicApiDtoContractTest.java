package xyz.yychainsaw.portfolio.publishing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import xyz.yychainsaw.portfolio.publicapi.PublicBlockDto;
import xyz.yychainsaw.portfolio.publicapi.PublicMediaDto;
import xyz.yychainsaw.portfolio.publicapi.PublicProjectCardDto;
import xyz.yychainsaw.portfolio.publicapi.PublicProjectDto;
import xyz.yychainsaw.portfolio.publicapi.PublicSiteDto;
import xyz.yychainsaw.portfolio.publicapi.PublishedEnvelope;

class PublicApiDtoContractTest {
    private static final UUID ASSET_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID PROJECT_ID =
            UUID.fromString("20000000-0000-0000-0000-000000000001");

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test
    void jacksonEmitsBothOuterAndNestedPayloadDiscriminatorsForEveryBlockType() {
        PublicMediaDto media = media();
        Map<String, PublicBlockDto.Payload> payloads = new LinkedHashMap<>();
        payloads.put("MARKDOWN", new PublicBlockDto.Markdown("<p>body</p>"));
        payloads.put("IMAGE", new PublicBlockDto.Image(media));
        payloads.put("GALLERY", new PublicBlockDto.Gallery(List.of(media)));
        payloads.put("VIDEO", new PublicBlockDto.Video(
                "youtube", "https://www.youtube.com/embed/video", media, "Title", "Description"));
        payloads.put("CODE", new PublicBlockDto.Code(
                "return true;", "java", true, "Code", "Description"));
        payloads.put("QUOTE", new PublicBlockDto.Quote("Quote", "Source"));
        payloads.put("METRICS", new PublicBlockDto.Metrics(List.of(
                new PublicBlockDto.Metric(
                        UUID.fromString("30000000-0000-0000-0000-000000000001"),
                        new BigDecimal("42.5"), "Players", "42.5", "%"))));
        payloads.put("DOWNLOAD", new PublicBlockDto.Download(
                "/api/public/media/" + ASSET_ID + "/document",
                "Download", "PDF", "application/pdf", 2048L));
        payloads.put("LINK", new PublicBlockDto.Link(
                "https://example.com", true, "Visit", "Project source"));

        payloads.forEach((type, payload) -> {
            PublicBlockDto block = new PublicBlockDto(
                    UUID.randomUUID(), type, 0, "WIDE", "LEFT", "NONE", 1, payload);
            JsonNode tree = json.valueToTree(block);

            assertThat(tree.path("type").asText()).isEqualTo(type);
            assertThat(tree.path("payload").path("type").asText()).isEqualTo(type);
        });
    }

    @Test
    void mediaNormalizesOptionalAttributionToNonNullStrings() {
        PublicMediaDto media = new PublicMediaDto(
                ASSET_ID,
                "w1280",
                "/api/public/media/" + ASSET_ID + "/w1280",
                "/api/public/media/" + ASSET_ID + "/w640 640w",
                "Alternative text",
                null,
                null,
                null,
                1280,
                720);

        assertThat(media.caption()).isEmpty();
        assertThat(media.credit()).isEmpty();
        assertThat(media.sourceUrl()).isEmpty();
    }

    @Test
    void projectAndNestedPayloadCollectionsAreDefensiveCopies() {
        ArrayList<String> mutableTags = new ArrayList<>(List.of("UE", "Java"));
        ArrayList<String> mutableSkills = new ArrayList<>(List.of("Unreal Engine"));
        ArrayList<PublicMediaDto> mutableMedia = new ArrayList<>(List.of(media()));
        ArrayList<PublicMediaDto> mutableGallery = new ArrayList<>(List.of(media()));
        PublicBlockDto.Gallery gallery = new PublicBlockDto.Gallery(mutableGallery);
        ArrayList<PublicBlockDto> mutableBlocks = new ArrayList<>(List.of(new PublicBlockDto(
                UUID.randomUUID(), "GALLERY", 0, "WIDE", "LEFT", "NONE", 2, gallery)));
        PublicProjectDto project = new PublicProjectDto(
                PROJECT_ID,
                "gameplay-prototype",
                "01",
                true,
                "In progress",
                "Prototype",
                "Gameplay Prototype",
                "Summary",
                "SEO title",
                "SEO description",
                mutableTags,
                mutableSkills,
                mutableMedia,
                mutableBlocks);

        mutableTags.clear();
        mutableSkills.clear();
        mutableMedia.clear();
        mutableGallery.clear();
        mutableBlocks.clear();

        assertThat(project.tags()).containsExactly("UE", "Java");
        assertThat(project.skills()).containsExactly("Unreal Engine");
        assertThat(project.media()).hasSize(1);
        assertThat(project.blocks()).hasSize(1);
        assertThat(gallery.media()).hasSize(1);
        assertThatThrownBy(() -> project.tags().add("mutable"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> gallery.media().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> project.blocks().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void metricsCollectionIsAlsoAStableDefensiveCopy() {
        ArrayList<PublicBlockDto.Metric> mutableMetrics = new ArrayList<>(List.of(
                new PublicBlockDto.Metric(
                        UUID.fromString("30000000-0000-0000-0000-000000000001"),
                        new BigDecimal("42.5"), "Players", "42.5", "%")));
        PublicBlockDto.Metrics metrics = new PublicBlockDto.Metrics(mutableMetrics);

        mutableMetrics.clear();

        assertThat(metrics.metrics()).hasSize(1);
        assertThatThrownBy(() -> metrics.metrics().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void siteAndCardCollectionsAreDefensiveCopies() {
        ArrayList<PublicSiteDto.NavigationItem> navigation = new ArrayList<>(List.of(
                new PublicSiteDto.NavigationItem("work", 0, "Work")));
        ArrayList<String> tags = new ArrayList<>(List.of("Game"));
        PublicSiteDto site = site(navigation);
        PublicProjectCardDto card = new PublicProjectCardDto(
                PROJECT_ID, "gameplay-prototype", "01", 0, true,
                "In progress", "Prototype", "Gameplay Prototype", "Summary", tags, media());

        navigation.clear();
        tags.clear();

        assertThat(site.navigation()).extracting(PublicSiteDto.NavigationItem::target)
                .containsExactly("work");
        assertThat(card.tags()).containsExactly("Game");
        assertThatThrownBy(() -> site.navigation().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> card.tags().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void publicJsonContainsEnvelopeMetadataButNoWorkspaceOrStorageInternals() {
        PublicProjectDto project = new PublicProjectDto(
                PROJECT_ID,
                "gameplay-prototype",
                "01",
                true,
                "In progress",
                "Prototype",
                "Gameplay Prototype",
                "Summary",
                "SEO title",
                "SEO description",
                List.of("Game"),
                List.of("Unreal Engine"),
                List.of(media()),
                List.of());
        PublishedEnvelope<PublicProjectDto> envelope = new PublishedEnvelope<>(
                7L, "a".repeat(64), project);

        JsonNode tree = json.valueToTree(envelope);

        assertThat(tree.path("revisionVersion").asLong()).isEqualTo(7L);
        assertThat(tree.path("checksum").asText()).isEqualTo("a".repeat(64));
        assertThat(tree.path("data").path("title").asText()).isEqualTo("Gameplay Prototype");
        assertFields(tree, "revisionVersion", "checksum", "data");
        assertFields(
                tree.path("data"),
                "projectId", "slug", "number", "featured", "status", "eyebrow", "title",
                "summary", "seoTitle", "seoDescription", "tags", "skills", "media", "blocks");
        assertFields(
                tree.path("data").path("media").get(0),
                "assetId", "variant", "src", "srcset", "alt", "caption", "credit",
                "sourceUrl", "width", "height");
        assertNoFieldNamed(tree, List.of(
                "workspaceVersion",
                "publicationDirty",
                "snapshotSchemaVersion",
                "bucket",
                "region",
                "objectKey",
                "sha256",
                "publishedBy"));
    }

    @Test
    void siteJsonUsesTheExactTopLevelAndHeroFieldNames() {
        JsonNode tree = json.valueToTree(site(List.of(
                new PublicSiteDto.NavigationItem("work", 0, "Work"))));

        assertFields(
                tree,
                "identity", "seo", "accessibility", "navigation", "hero", "about", "work",
                "roadmap", "contact", "privacy", "socialLinks", "resume");
        assertFields(
                tree.path("hero"),
                "eyebrow", "displayName", "secondaryName", "role", "headline",
                "introduction", "availability", "primaryCta", "secondaryCta", "visualLabel",
                "stageLabel", "objectPosition", "credit", "sourceUrl", "media");
    }

    private static PublicMediaDto media() {
        String prefix = "/api/public/media/" + ASSET_ID + '/';
        return new PublicMediaDto(
                ASSET_ID,
                "w1280",
                prefix + "w1280",
                prefix + "w640 640w, " + prefix + "w1280 1280w",
                "Alternative text",
                "Caption",
                "Credit",
                "https://example.com/source",
                1280,
                720);
    }

    private static PublicSiteDto site(List<PublicSiteDto.NavigationItem> navigation) {
        return new PublicSiteDto(
                new PublicSiteDto.Identity("YJX", "易嘉轩", "Yi Jiaxuan", "hello@example.com"),
                new PublicSiteDto.Seo("Portfolio", "Description"),
                new PublicSiteDto.Accessibility(
                        "Skip", "Primary", "Mobile", "Open", "Close", "Language", "Top", "Tags"),
                navigation,
                new PublicSiteDto.Hero(
                        "Hello", "易嘉轩", "Yi Jiaxuan", "Game developer", "Headline", "Intro",
                        "Available", "Work", "About", "Visual", "Stage", "50% 50%",
                        "Hero credit", "https://example.com/hero", media()),
                new PublicSiteDto.About(
                        "About", "About me", "Statement", "Focus", "Game development", "Intro",
                        List.of(new PublicSiteDto.Fact("University", "JXNU")),
                        List.of(new PublicSiteDto.Skill("UE", "Learning"))),
                new PublicSiteDto.Work("Work", "Projects", "Intro", "Notice", "Open", "Next", "Text", "Meta"),
                new PublicSiteDto.Roadmap(
                        "Roadmap", "Next", "Intro",
                        List.of(new PublicSiteDto.RoadmapStage(
                                UUID.randomUUID(), "01", "2026", "Learn", "Summary", List.of("Ship")))),
                new PublicSiteDto.Contact(
                        "Contact", "Let's talk", "Intro", "Email", "hello@example.com", "Work", "Roadmap", "Footer"),
                new PublicSiteDto.Privacy("Privacy", "<p>Privacy</p>"),
                List.of(new PublicSiteDto.SocialLink("GitHub", "https://github.com/example")),
                new PublicSiteDto.Resume("Resume", LocalDate.of(2026, 7, 1),
                        "/api/public/media/" + ASSET_ID + "/document"));
    }

    private static void assertNoFieldNamed(JsonNode node, List<String> forbidden) {
        if (node.isObject()) {
            node.fieldNames().forEachRemaining(name -> assertThat(forbidden).doesNotContain(name));
            node.elements().forEachRemaining(child -> assertNoFieldNamed(child, forbidden));
        } else if (node.isArray()) {
            node.elements().forEachRemaining(child -> assertNoFieldNamed(child, forbidden));
        }
    }

    private static void assertFields(JsonNode node, String... expected) {
        ArrayList<String> actual = new ArrayList<>();
        node.fieldNames().forEachRemaining(actual::add);
        assertThat(actual).containsExactlyInAnyOrder(expected);
    }
}
