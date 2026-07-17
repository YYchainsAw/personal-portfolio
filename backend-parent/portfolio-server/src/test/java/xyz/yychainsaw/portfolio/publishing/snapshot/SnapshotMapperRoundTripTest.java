package xyz.yychainsaw.portfolio.publishing.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.content.api.ContentBlockDto;
import xyz.yychainsaw.portfolio.content.api.ProjectWorkspaceDto;
import xyz.yychainsaw.portfolio.content.api.SiteWorkspaceDto;
import xyz.yychainsaw.portfolio.content.persistence.support.ContentPersistenceFixtures;
import xyz.yychainsaw.portfolio.content.support.WorkspaceFixtures;
import xyz.yychainsaw.portfolio.media.application.MediaAssetDescriptor;
import xyz.yychainsaw.portfolio.media.application.MediaCopyDescriptor;
import xyz.yychainsaw.portfolio.media.application.MediaQueryService;
import xyz.yychainsaw.portfolio.media.application.MediaVariantDescriptor;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.LocaleV1;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.ProjectCatalogSnapshotMapperV1;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.ProjectSnapshotMapperV1;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.ProjectSnapshotV1;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.PublishedBlockV1;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.PublishedMediaV1;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.SiteSnapshotMapperV1;

class SnapshotMapperRoundTripTest {
    private static final UUID DUPLICATE_BLOCK_ID =
            UUID.fromString("84000000-0000-4000-8000-000000000099");
    private static final UUID SECOND_PROJECT_ID =
            UUID.fromString("82000000-0000-4000-8000-000000000099");
    private static final UUID SITE_CURRENT_RESUME_ASSET_ID =
            UUID.fromString("86000000-0000-4000-8000-000000000001");
    private static final UUID SITE_ARCHIVED_RESUME_ASSET_ID =
            UUID.fromString("86000000-0000-4000-8000-000000000002");
    private static final UUID SITE_HERO_ASSET_ID =
            UUID.fromString("86000000-0000-4000-8000-000000000003");
    private static final UUID SITE_CURRENT_RESUME_ID =
            UUID.fromString("86000000-0000-4000-8000-000000000011");
    private static final UUID SITE_ARCHIVED_RESUME_ID =
            UUID.fromString("86000000-0000-4000-8000-000000000012");
    private static final String STORAGE_SECRET = "must-not-appear-in-public-json";

    private final SnapshotCodec codec =
            new SnapshotCodec(new ObjectMapper().findAndRegisterModules());

    @Test
    void projectSnapshotIsCanonicalAndRestoresTheEditableAggregate() {
        ProjectWorkspaceDto workspace = WorkspaceFixtures.projectWithoutMedia();
        ProjectSnapshotMapper mapper =
                new ProjectSnapshotMapperV1(new RecordingMediaQueryService());

        ProjectSnapshotV1 snapshot = mapper.toSnapshot(workspace);
        EncodedSnapshot first = codec.encode(snapshot);
        EncodedSnapshot second = codec.encode(snapshot);

        assertThat(first.schemaVersion()).isEqualTo(1);
        assertThat(first.json()).isEqualTo(second.json());
        assertThat(first.sha256()).isEqualTo(second.sha256());
        assertThat(first.sha256()).matches("[0-9a-f]{64}");
        assertThat(first.sha256()).isEqualTo(sha256(first.json()));

        ProjectWorkspaceDto restored = mapper.restore(
                codec.decode(first.json(), ProjectSnapshotV1.class), 37L);
        assertThat(restored)
                .usingRecursiveComparison()
                .ignoringFields("version", "publicationDirty")
                .isEqualTo(workspace);
        assertThat(restored.version()).isEqualTo(37L);
        assertThat(restored.visible()).isTrue();
        assertThat(restored.publicationDirty()).isTrue();
    }

    @Test
    void canonicalJsonDoesNotDependOnMapInsertionOrderAndRejectsBadJson() {
        Map<String, Integer> reverse = new LinkedHashMap<>();
        reverse.put("z", 2);
        reverse.put("a", 1);
        Map<String, Integer> forward = new LinkedHashMap<>();
        forward.put("a", 1);
        forward.put("z", 2);

        assertThat(codec.encode(reverse).json()).isEqualTo("{\"a\":1,\"z\":2}");
        assertThat(codec.encode(forward)).isEqualTo(codec.encode(reverse));
        assertThat(codec.encode(new ReverseDeclaredProperties(2, 1)).json())
                .isEqualTo("{\"a\":1,\"z\":2}");
        assertThatThrownBy(() -> codec.decode("{broken", ProjectSnapshotV1.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid publication snapshot");
    }

    @Test
    void localizedMediaAttributionSurvivesCanonicalCodecRoundTrip() {
        UUID assetId = UUID.fromString("85000000-0000-4000-8000-000000000001");
        PublishedMediaV1 media = new PublishedMediaV1(
                assetId,
                "image/webp",
                2048L,
                "a".repeat(64),
                Map.of(
                        LocaleV1.ZH_CN,
                        new PublishedMediaV1.MediaCopy(
                                "场景", "引擎截图", "易嘉轩", "https://example.test/zh/source"),
                        LocaleV1.EN,
                        new PublishedMediaV1.MediaCopy(
                                "Scene", "In-engine capture", "Yi Jiaxuan",
                                "https://example.test/en/source")),
                List.of(new PublishedMediaV1.Variant(
                        "1280", 1280, 720, 1024L, "b".repeat(64))));

        String json = codec.encode(media).json();
        PublishedMediaV1 decoded = codec.decode(json, PublishedMediaV1.class);

        assertThat(decoded).usingRecursiveComparison().isEqualTo(media);
        assertThat(decoded.copy().get(LocaleV1.EN).sourceUrl())
                .isEqualTo("https://example.test/en/source");
        assertThat(json).doesNotContain(
                "signedUrl", "objectKey", "bucket", "region", "provider");
    }

    @Test
    void projectMapperCoversEveryPayloadAndCanonicalizesReferencedMedia() {
        RecordingMediaQueryService mediaQuery = new RecordingMediaQueryService();
        ProjectSnapshotMapper mapper = new ProjectSnapshotMapperV1(mediaQuery);
        ProjectWorkspaceDto workspace = withNonCanonicalMediaEncounterOrder(
                ContentPersistenceFixtures.projectWithAllPayloads());
        List<UUID> expectedAssetOrder = ContentPersistenceFixtures.PROJECT_ASSET_IDS.stream()
                .sorted(Comparator.comparing(UUID::toString))
                .toList();
        Map<UUID, Integer> expectedAssetCalls = new HashMap<>();
        expectedAssetOrder.forEach(assetId -> expectedAssetCalls.put(assetId, 1));

        ProjectSnapshotV1 snapshot = mapper.toSnapshot(workspace);

        assertThat(snapshot.blocks())
                .extracting(block -> block.payload().getClass().getName())
                .contains(
                        PublishedBlockV1.MarkdownPayloadV1.class.getName(),
                        PublishedBlockV1.ImagePayloadV1.class.getName(),
                        PublishedBlockV1.GalleryPayloadV1.class.getName(),
                        PublishedBlockV1.VideoPayloadV1.class.getName(),
                        PublishedBlockV1.CodePayloadV1.class.getName(),
                        PublishedBlockV1.QuotePayloadV1.class.getName(),
                        PublishedBlockV1.MetricsPayloadV1.class.getName(),
                        PublishedBlockV1.DownloadPayloadV1.class.getName(),
                        PublishedBlockV1.LinkPayloadV1.class.getName());
        assertThat(snapshot.media())
                .extracting(PublishedMediaV1::assetId)
                .containsExactlyElementsOf(expectedAssetOrder);
        assertThat(snapshot.media()).allSatisfy(media -> {
            assertThat(media.variants())
                    .extracting(PublishedMediaV1.Variant::name)
                    .containsExactly("alpha", "zeta");
            assertLocalizedCopy(media, LocaleV1.ZH_CN, "zh-CN");
            assertLocalizedCopy(media, LocaleV1.EN, "en");
        });
        assertThat(mediaQuery.assetCalls)
                .containsExactlyInAnyOrderEntriesOf(expectedAssetCalls);

        String json = codec.encode(snapshot).json();
        assertThat(json).doesNotContain(
                STORAGE_SECRET, "signedUrl", "objectKey", "bucket", "region",
                "storageProvider", "TENCENT_COS");
        ProjectSnapshotV1 decoded = codec.decode(json, ProjectSnapshotV1.class);
        assertThat(decoded).usingRecursiveComparison().isEqualTo(snapshot);

        ProjectWorkspaceDto restored = mapper.restore(decoded, 91L);
        assertThat(restored)
                .usingRecursiveComparison()
                .ignoringFields("version", "publicationDirty")
                .isEqualTo(workspace);
        assertThat(restored.version()).isEqualTo(91L);
        assertThat(restored.publicationDirty()).isTrue();
    }

    @Test
    void siteSnapshotRestoresHistoricalContentWithCurrentWorkspaceVersions() {
        RecordingMediaQueryService mediaQuery = new RecordingMediaQueryService();
        SiteSnapshotMapper mapper = new SiteSnapshotMapperV1(mediaQuery);
        SiteWorkspaceDto historical = siteWithNonCanonicalMediaEncounterOrder();
        SiteWorkspaceDto expectedHistoricalPublishableSubset = copySite(
                historical,
                historical.hero(),
                List.of(historical.resumes().get(0)));
        SiteWorkspaceDto currentBase = WorkspaceFixtures.site(44L);
        SiteWorkspaceDto.Hero currentHero = new SiteWorkspaceDto.Hero(
                currentBase.hero().id(),
                88L,
                currentBase.hero().mediaAssetId(),
                currentBase.hero().objectPosition(),
                currentBase.hero().credit(),
                currentBase.hero().sourceUrl(),
                currentBase.hero().copy());
        SiteWorkspaceDto current = ContentPersistenceFixtures.withHero(currentBase, currentHero);

        var snapshot = mapper.toSnapshot(historical);
        EncodedSnapshot encoded = codec.encode(snapshot);
        SiteWorkspaceDto restored = mapper.restore(
                codec.decode(encoded.json(),
                        xyz.yychainsaw.portfolio.publishing.snapshot.v1.SiteSnapshotV1.class),
                current);

        assertThat(snapshot.content().resumes())
                .singleElement()
                .satisfies(resume -> {
                    assertThat(resume.id()).isEqualTo(SITE_CURRENT_RESUME_ID);
                    assertThat(resume.mediaAssetId()).isEqualTo(SITE_CURRENT_RESUME_ASSET_ID);
                    assertThat(resume.current()).isTrue();
                });
        assertThat(snapshot.media())
                .extracting(PublishedMediaV1::assetId)
                .containsExactly(
                        SITE_CURRENT_RESUME_ASSET_ID,
                        SITE_HERO_ASSET_ID);
        assertThat(mediaQuery.assetCalls)
                .containsEntry(SITE_CURRENT_RESUME_ASSET_ID, 1)
                .containsEntry(SITE_HERO_ASSET_ID, 1)
                .doesNotContainKey(SITE_ARCHIVED_RESUME_ASSET_ID)
                .hasSize(2);
        assertThat(restored)
                .usingRecursiveComparison()
                .ignoringFields("version", "hero.version")
                .isEqualTo(expectedHistoricalPublishableSubset);
        assertThat(restored.version()).isEqualTo(44L);
        assertThat(restored.hero().version()).isEqualTo(88L);
        assertThat(encoded.json()).doesNotContain("\"version\":");
    }

    @Test
    void catalogUsesSuppliedProjectOrderAndReassignsZeroBasedSortOrder() {
        ProjectSnapshotMapper projectMapper =
                new ProjectSnapshotMapperV1(new RecordingMediaQueryService());
        ProjectSnapshotV1 first = projectMapper.toSnapshot(
                ContentPersistenceFixtures.projectWithAllPayloads());
        ProjectSnapshotV1 second = copyProjectSnapshot(
                first, SECOND_PROJECT_ID, "second-project", "02", 77);
        ProjectCatalogSnapshotMapper catalogMapper = new ProjectCatalogSnapshotMapperV1();

        var catalog = catalogMapper.fromCurrentProjects(List.of(second, first));

        assertThat(catalog.projects())
                .extracting(card -> card.projectId())
                .containsExactly(SECOND_PROJECT_ID, first.projectId());
        assertThat(catalog.projects())
                .extracting(card -> card.sortOrder())
                .containsExactly(0, 1);
        assertThat(catalog.projects()).allSatisfy(card -> {
            assertThat(card.cover().assetId())
                    .isEqualTo(ContentPersistenceFixtures.PROJECT_ASSET_IDS.get(0));
            assertThat(card.copy().get(LocaleV1.EN).tags()).containsExactly("Gameplay");
        });
    }

    @Test
    void registryDispatchesSchemaOneAndRejectsEveryUnknownVersionEntryPoint() {
        RecordingMediaQueryService mediaQuery = new RecordingMediaQueryService();
        SiteSnapshotMapper siteMapper = new SiteSnapshotMapperV1(mediaQuery);
        ProjectSnapshotMapper projectMapper = new ProjectSnapshotMapperV1(mediaQuery);
        ProjectCatalogSnapshotMapper catalogMapper = new ProjectCatalogSnapshotMapperV1();
        SnapshotMapperRegistry registry =
                new SnapshotMapperRegistry(codec, siteMapper, projectMapper, catalogMapper);
        ProjectWorkspaceDto workspace = WorkspaceFixtures.projectWithoutMedia();
        EncodedSnapshot encoded = codec.encode(projectMapper.toSnapshot(workspace));

        assertThat(registry.readProject(1, encoded.json()))
                .isEqualTo(projectMapper.toSnapshot(workspace));
        assertThat(registry.restoreProject(1, encoded.json(), 12L).version()).isEqualTo(12L);

        assertUnsupported(() -> registry.readSite(2, "{broken"));
        assertUnsupported(() -> registry.readProject(2, "{broken"));
        assertUnsupported(() -> registry.readCatalog(2, "{broken"));
        assertUnsupported(() -> registry.restoreSite(
                2, "{broken", WorkspaceFixtures.site(0L)));
        assertUnsupported(() -> registry.restoreProject(2, "{broken", 0L));
    }

    @Test
    void v1WireContractsStayIndependentFromEditableContentDtos() throws IOException {
        Path sourceDirectory = snapshotV1SourceDirectory();
        List<String> wireContracts = List.of(
                "LocaleV1.java",
                "PublishedMediaV1.java",
                "SiteContentV1.java",
                "PublishedBlockV1.java",
                "SiteSnapshotV1.java",
                "ProjectSnapshotV1.java",
                "ProjectCatalogSnapshotV1.java");

        for (String source : wireContracts) {
            assertThat(Files.readString(sourceDirectory.resolve(source)))
                    .as(source)
                    .doesNotContain("xyz.yychainsaw.portfolio.content.api");
        }
        assertThatThrownBy(() -> LocaleV1.from("fr"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unsupported snapshot locale: fr");
    }

    private static void assertLocalizedCopy(
            PublishedMediaV1 media, LocaleV1 locale, String localeValue) {
        PublishedMediaV1.MediaCopy copy = media.copy().get(locale);
        String suffix = media.assetId().toString();
        assertThat(copy.alt()).isEqualTo("alt-" + localeValue + "-" + suffix);
        assertThat(copy.caption()).isEqualTo("caption-" + localeValue + "-" + suffix);
        assertThat(copy.credit()).isEqualTo("credit-" + localeValue + "-" + suffix);
        assertThat(copy.sourceUrl())
                .isEqualTo("https://example.test/" + localeValue + "/" + suffix);
    }

    private static ProjectWorkspaceDto withNonCanonicalMediaEncounterOrder(
            ProjectWorkspaceDto source) {
        List<ContentBlockDto> blocks = new ArrayList<>(source.blocks());
        Collections.reverse(blocks);
        blocks.add(new ContentBlockDto(
                DUPLICATE_BLOCK_ID,
                blocks.size(),
                true,
                ContentBlockDto.Width.STANDARD,
                ContentBlockDto.Alignment.LEFT,
                ContentBlockDto.Emphasis.NONE,
                1,
                new ContentBlockDto.ImagePayload(
                        ContentPersistenceFixtures.PROJECT_ASSET_IDS.get(0))));
        return new ProjectWorkspaceDto(
                source.id(),
                source.externalKey(),
                source.slug(),
                source.number(),
                source.sortOrder(),
                source.featured(),
                source.visible(),
                source.publicationDirty(),
                source.version(),
                source.translations(),
                source.tags(),
                source.skills(),
                source.media(),
                blocks);
    }

    private static SiteWorkspaceDto siteWithNonCanonicalMediaEncounterOrder() {
        SiteWorkspaceDto source = ContentPersistenceFixtures.siteWithMedia(7L);
        SiteWorkspaceDto.Hero sourceHero = source.hero();
        SiteWorkspaceDto.Hero hero = new SiteWorkspaceDto.Hero(
                sourceHero.id(),
                sourceHero.version(),
                SITE_HERO_ASSET_ID,
                sourceHero.objectPosition(),
                sourceHero.credit(),
                sourceHero.sourceUrl(),
                sourceHero.copy());
        SiteWorkspaceDto.ResumeDocument sourceResume = source.resumes().get(0);
        SiteWorkspaceDto.ResumeDocument currentResume = new SiteWorkspaceDto.ResumeDocument(
                SITE_CURRENT_RESUME_ID,
                sourceResume.locale(),
                SITE_CURRENT_RESUME_ASSET_ID,
                sourceResume.versionLabel(),
                true,
                sourceResume.documentDate());
        SiteWorkspaceDto.ResumeDocument archivedResume = new SiteWorkspaceDto.ResumeDocument(
                SITE_ARCHIVED_RESUME_ID,
                sourceResume.locale(),
                SITE_ARCHIVED_RESUME_ASSET_ID,
                "2025.1",
                false,
                sourceResume.documentDate().minusYears(1));
        return copySite(source, hero, List.of(currentResume, archivedResume));
    }

    private static SiteWorkspaceDto copySite(
            SiteWorkspaceDto source,
            SiteWorkspaceDto.Hero hero,
            List<SiteWorkspaceDto.ResumeDocument> resumes) {
        return new SiteWorkspaceDto(
                source.siteId(),
                source.version(),
                source.monogram(),
                source.email(),
                source.identity(),
                source.seo(),
                source.accessibility(),
                source.navigation(),
                hero,
                source.about(),
                source.facts(),
                source.profileSkills(),
                source.work(),
                source.roadmap(),
                source.contact(),
                source.privacy(),
                source.socialLinks(),
                resumes);
    }

    private static ProjectSnapshotV1 copyProjectSnapshot(
            ProjectSnapshotV1 source,
            UUID projectId,
            String slug,
            String number,
            int staleSortOrder) {
        return new ProjectSnapshotV1(
                1,
                projectId,
                "second-project",
                slug,
                number,
                staleSortOrder,
                source.featured(),
                source.translations(),
                source.tags(),
                source.skills(),
                source.projectMedia(),
                source.blocks(),
                source.media());
    }

    private static void assertUnsupported(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(DomainException.class, error -> {
                    assertThat(error.code()).isEqualTo("SNAPSHOT_SCHEMA_UNSUPPORTED");
                    assertThat(error.status().value()).isEqualTo(422);
                    assertThat(error.fieldErrors())
                            .containsExactly(Map.entry("snapshotSchemaVersion", "2"));
                });
    }

    private static String sha256(String json) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new AssertionError(error);
        }
    }

    private static Path snapshotV1SourceDirectory() {
        List<Path> candidates = List.of(
                Path.of("src/main/java/xyz/yychainsaw/portfolio/publishing/snapshot/v1"),
                Path.of("portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing/snapshot/v1"),
                Path.of("backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing/snapshot/v1"));
        return candidates.stream()
                .filter(Files::isDirectory)
                .findFirst()
                .orElseThrow(() -> new AssertionError("snapshot V1 source directory not found"));
    }

    private static final class RecordingMediaQueryService implements MediaQueryService {
        private final Map<UUID, Integer> assetCalls = new HashMap<>();

        @Override
        public MediaAssetDescriptor requireReadyAsset(UUID assetId) {
            assetCalls.merge(assetId, 1, Integer::sum);
            return descriptor(assetId);
        }

        @Override
        public MediaVariantDescriptor requireReadyVariant(UUID assetId, String variantName) {
            return descriptor(assetId).variants().stream()
                    .filter(variant -> variant.variantName().equals(variantName))
                    .findFirst()
                    .orElseThrow();
        }

        private static MediaAssetDescriptor descriptor(UUID assetId) {
            return new MediaAssetDescriptor(
                    assetId,
                    "READY",
                    "image/png",
                    4096L,
                    "a".repeat(64),
                    Map.of(
                            "zh-CN", copy(assetId, "zh-CN"),
                            "en", copy(assetId, "en")),
                    List.of(
                            variant(assetId, "zeta", 1920, 1080, "b"),
                            variant(assetId, "alpha", 640, 360, "c")));
        }

        private static MediaCopyDescriptor copy(UUID assetId, String locale) {
            String suffix = assetId.toString();
            return new MediaCopyDescriptor(
                    "alt-" + locale + "-" + suffix,
                    "caption-" + locale + "-" + suffix,
                    "credit-" + locale + "-" + suffix,
                    "https://example.test/" + locale + "/" + suffix);
        }

        private static MediaVariantDescriptor variant(
                UUID assetId, String name, int width, int height, String checksumPrefix) {
            return new MediaVariantDescriptor(
                    assetId,
                    name,
                    "READY",
                    StorageProvider.TENCENT_COS,
                    STORAGE_SECRET + "-bucket",
                    STORAGE_SECRET + "-region",
                    STORAGE_SECRET + "-object-key",
                    "image/webp",
                    width * 10L,
                    checksumPrefix.repeat(64),
                    width,
                    height);
        }
    }

    private record ReverseDeclaredProperties(int z, int a) {}
}
