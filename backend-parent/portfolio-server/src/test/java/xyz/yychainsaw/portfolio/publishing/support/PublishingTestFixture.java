package xyz.yychainsaw.portfolio.publishing.support;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import xyz.yychainsaw.portfolio.content.api.ProjectWorkspaceDto;
import xyz.yychainsaw.portfolio.content.persistence.ProjectWorkspaceRepository;
import xyz.yychainsaw.portfolio.content.persistence.support.ContentPersistenceFixtures;
import xyz.yychainsaw.portfolio.media.application.MediaObjectKeys;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRecord;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRepository;
import xyz.yychainsaw.portfolio.media.persistence.MediaTranslationRecord;
import xyz.yychainsaw.portfolio.media.persistence.MediaTranslationRepository;
import xyz.yychainsaw.portfolio.media.persistence.MediaVariantRecord;
import xyz.yychainsaw.portfolio.media.persistence.MediaVariantRepository;
import xyz.yychainsaw.portfolio.publishing.persistence.PublishingRepository;

/**
 * Minimal PostgreSQL fixture for publication scenarios. Every generated project,
 * slug, and media identity is unique so later publication tests can compose these
 * building blocks without sharing mutable aggregate state.
 */
@TestComponent
public class PublishingTestFixture {
    public static final UUID ADMIN_ID =
            UUID.fromString("70000000-0000-4000-8000-000000000008");

    private static final String IMAGE_MIME_TYPE = "image/png";
    private static final List<Integer> VARIANT_WIDTHS = List.of(640, 1280);

    private final JdbcClient jdbc;
    private final ProjectWorkspaceRepository projects;
    private final PublishingRepository publishing;
    private final MediaAssetRepository mediaAssets;
    private final MediaVariantRepository mediaVariants;
    private final MediaTranslationRepository mediaTranslations;

    public PublishingTestFixture(
            JdbcClient jdbc,
            ProjectWorkspaceRepository projects,
            PublishingRepository publishing,
            MediaAssetRepository mediaAssets,
            MediaVariantRepository mediaVariants,
            MediaTranslationRepository mediaTranslations) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc is required");
        this.projects = Objects.requireNonNull(projects, "projects are required");
        this.publishing = Objects.requireNonNull(publishing, "publishing is required");
        this.mediaAssets = Objects.requireNonNull(mediaAssets, "media assets are required");
        this.mediaVariants = Objects.requireNonNull(
                mediaVariants, "media variants are required");
        this.mediaTranslations = Objects.requireNonNull(
                mediaTranslations, "media translations are required");
    }

    @Transactional
    public void ensureAdmin() {
        jdbc.sql("""
                        insert into portfolio.admin_user(
                            id, username, password_hash, status, totp_key_version,
                            totp_nonce, totp_ciphertext)
                        values (:id, 'publishing-integration-admin', '{noop}test',
                                'ACTIVE', 1, decode(repeat('00', 12), 'hex'),
                                decode('00', 'hex'))
                        on conflict (id) do nothing
                        """)
                .param("id", ADMIN_ID)
                .update();
    }

    /** Creates one complete bilingual project backed by one READY LOCAL cover. */
    @Transactional
    public ProjectWorkspaceDto persistReadyProject() {
        ensureAdmin();
        UUID projectId = UUID.randomUUID();
        UUID coverAssetId = UUID.randomUUID();
        String compactId = compact(projectId);
        String key = "publish-" + compactId;
        int sortOrder = jdbc.sql(
                        "select coalesce(max(sort_order), -1) + 1 from portfolio.project")
                .query(Integer.class)
                .single();

        persistReadyImage(coverAssetId);
        ProjectWorkspaceDto base = ContentPersistenceFixtures.simpleProject(
                projectId, key, sortOrder, 0L);
        ProjectWorkspaceDto workspace = new ProjectWorkspaceDto(
                base.id(),
                base.externalKey(),
                base.slug(),
                base.number(),
                base.sortOrder(),
                base.featured(),
                base.visible(),
                base.publicationDirty(),
                base.version(),
                base.translations(),
                base.tags(),
                base.skills(),
                List.of(new ProjectWorkspaceDto.ProjectMedia(
                        coverAssetId,
                        "COVER",
                        0,
                        "wide",
                        "50% 50%",
                        "Integration fixture",
                        URI.create("https://example.test/projects/" + compactId + "/cover"))),
                base.blocks());
        projects.insert(workspace);
        return projects.require(projectId);
    }

    /**
     * Creates the pre-publication pointer state needed to prove redirect rollback
     * without retaining a successful historical revision in the shared test database.
     */
    @Transactional
    public String seedArchivedProjectPointerWithOldSlug(UUID projectId) {
        Objects.requireNonNull(projectId, "project id is required");
        String oldSlug = "old-" + compact(projectId);
        publishing.ensureProjectPublication(projectId);
        int changed = jdbc.sql("""
                        update portfolio.publication
                        set status='ARCHIVED', current_revision_id=null,
                            current_slug=:oldSlug, version=0, published_at=null
                        where aggregate_type='PROJECT' and aggregate_id=:projectId
                        """)
                .param("oldSlug", oldSlug)
                .param("projectId", projectId)
                .update();
        if (changed != 1) {
            throw new IllegalStateException("archived project pointer was not seeded");
        }
        return oldSlug;
    }

    public UUID coverAssetId(UUID projectId) {
        return jdbc.sql("""
                        select media_asset_id
                        from portfolio.project_media
                        where project_id=:projectId and usage='COVER'
                        """)
                .param("projectId", projectId)
                .query(UUID.class)
                .single();
    }

    /** Replaces only the editable slug and returns the newly versioned workspace. */
    @Transactional
    public ProjectWorkspaceDto editProjectSlug(UUID projectId, String slug) {
        Objects.requireNonNull(projectId, "project id is required");
        if (slug == null || slug.isBlank()) {
            throw new IllegalArgumentException("project slug is required");
        }
        ProjectWorkspaceDto current = projects.require(projectId);
        ProjectWorkspaceDto changed = new ProjectWorkspaceDto(
                current.id(),
                current.externalKey(),
                slug,
                current.number(),
                current.sortOrder(),
                current.featured(),
                current.visible(),
                true,
                current.version(),
                current.translations(),
                current.tags(),
                current.skills(),
                current.media(),
                current.blocks());
        projects.replace(changed, current.version());
        return projects.require(projectId);
    }

    /** Replaces only the English title and returns the newly versioned workspace. */
    @Transactional
    public ProjectWorkspaceDto editProjectEnglishTitle(UUID projectId, String title) {
        Objects.requireNonNull(projectId, "project id is required");
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("English project title is required");
        }
        ProjectWorkspaceDto current = projects.require(projectId);
        ProjectWorkspaceDto changed =
                ContentPersistenceFixtures.withEnglishProjectTitle(current, title);
        projects.replace(changed, current.version());
        return projects.require(projectId);
    }

    public ProjectWorkspaceDto project(UUID projectId) {
        return projects.require(Objects.requireNonNull(projectId, "project id is required"));
    }

    /** Creates one READY LOCAL PDF with its canonical document variant. */
    @Transactional
    public UUID persistReadyDocument() {
        UUID assetId = UUID.randomUUID();
        String mimeType = "application/pdf";
        String assetSha256 = sha256("document:" + assetId);
        String objectKey = MediaObjectKeys.originalKey(assetId, assetSha256, mimeType);
        long byteSize = 4_096L;
        mediaAssets.insertProcessing(new MediaAssetRecord.Insert(
                assetId,
                StorageProvider.LOCAL,
                null,
                null,
                objectKey,
                "resume-" + compact(assetId) + ".pdf",
                mimeType,
                byteSize,
                null,
                null,
                assetSha256));
        boolean inserted = mediaVariants.insertReadyIfAbsent(
                new MediaVariantRecord.Insert(
                        UUID.randomUUID(),
                        assetId,
                        "document",
                        "PDF",
                        objectKey,
                        mimeType,
                        byteSize,
                        null,
                        null,
                        assetSha256));
        if (!inserted) {
            throw new IllegalStateException("READY document variant was not inserted");
        }
        mediaTranslations.replaceAll(assetId, List.of(
                new MediaTranslationRecord(
                        assetId, "zh-CN", "简历", "", "", null),
                new MediaTranslationRecord(
                        assetId, "en", "Resume", "", "", null)));
        if (mediaAssets.markReadyIfProcessing(assetId) != 1) {
            throw new IllegalStateException("document asset did not become READY");
        }
        return assetId;
    }

    private void persistReadyImage(UUID assetId) {
        String assetSha256 = sha256("asset:" + assetId);
        mediaAssets.insertProcessing(new MediaAssetRecord.Insert(
                assetId,
                StorageProvider.LOCAL,
                null,
                null,
                MediaObjectKeys.originalKey(assetId, assetSha256, IMAGE_MIME_TYPE),
                "cover-" + compact(assetId) + ".png",
                IMAGE_MIME_TYPE,
                4_096L,
                1280,
                720,
                assetSha256));

        for (int width : VARIANT_WIDTHS) {
            String name = "w" + width;
            String variantSha256 = sha256("variant:" + assetId + ':' + name);
            boolean inserted = mediaVariants.insertReadyIfAbsent(
                    new MediaVariantRecord.Insert(
                            UUID.randomUUID(),
                            assetId,
                            name,
                            "PNG",
                            MediaObjectKeys.variantKey(
                                    assetId, name, variantSha256, IMAGE_MIME_TYPE),
                            IMAGE_MIME_TYPE,
                            width == 640 ? 1_024L : 2_048L,
                            width,
                            width == 640 ? 360 : 720,
                            variantSha256));
            if (!inserted) {
                throw new IllegalStateException("READY media variant was not inserted");
            }
        }

        mediaTranslations.replaceAll(assetId, List.of(
                new MediaTranslationRecord(
                        assetId,
                        "zh-CN",
                        "项目封面",
                        "项目封面说明",
                        "易嘉轩",
                        "https://example.test/media/" + assetId + "/zh-CN"),
                new MediaTranslationRecord(
                        assetId,
                        "en",
                        "Project cover",
                        "Project cover caption",
                        "Yi Jiaxuan",
                        "https://example.test/media/" + assetId + "/en")));
        if (mediaAssets.markReadyIfProcessing(assetId) != 1) {
            throw new IllegalStateException("media asset did not become READY");
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String compact(UUID id) {
        return id.toString().replace("-", "").substring(0, 16);
    }
}
