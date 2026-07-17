package xyz.yychainsaw.portfolio.content.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.yychainsaw.portfolio.audit.AuditCommand;
import xyz.yychainsaw.portfolio.audit.AuditOutcome;
import xyz.yychainsaw.portfolio.audit.AuditService;
import xyz.yychainsaw.portfolio.auth.CurrentAdminProvider;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.content.api.ContentBlockDto;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.content.api.ProjectWorkspaceDto;
import xyz.yychainsaw.portfolio.content.api.SiteWorkspaceDto;
import xyz.yychainsaw.portfolio.content.api.TaxonomyWorkspaceDto;
import xyz.yychainsaw.portfolio.content.persistence.ProjectWorkspaceRepository;
import xyz.yychainsaw.portfolio.content.persistence.SiteWorkspaceRepository;
import xyz.yychainsaw.portfolio.content.persistence.TaxonomyRepository;
import xyz.yychainsaw.portfolio.media.application.MediaQueryService;

@Service
@ConditionalOnWebApplication(type = Type.SERVLET)
public final class ContentWorkspaceService {
    private static final Comparator<UUID> UUID_ORDER =
            Comparator.comparing(UUID::toString);
    private static final Set<LocaleCode> LOCALES =
            Set.of(LocaleCode.ZH_CN, LocaleCode.EN);

    private final CurrentAdminProvider currentAdmin;
    private final SiteWorkspaceRepository sites;
    private final ProjectWorkspaceRepository projects;
    private final TaxonomyRepository taxonomy;
    private final WorkspaceValidator validator;
    private final MediaQueryService media;
    private final AuditService audit;
    private final TransactionOperations transactions;
    private final Clock clock;
    private final Supplier<UUID> projectIds;

    @Autowired
    public ContentWorkspaceService(
            CurrentAdminProvider currentAdmin,
            SiteWorkspaceRepository sites,
            ProjectWorkspaceRepository projects,
            TaxonomyRepository taxonomy,
            WorkspaceValidator validator,
            MediaQueryService media,
            AuditService audit,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this(
                currentAdmin,
                sites,
                projects,
                taxonomy,
                validator,
                media,
                audit,
                new TransactionTemplate(Objects.requireNonNull(
                        transactionManager, "transaction manager is required")),
                clock,
                UUID::randomUUID);
    }

    ContentWorkspaceService(
            CurrentAdminProvider currentAdmin,
            SiteWorkspaceRepository sites,
            ProjectWorkspaceRepository projects,
            TaxonomyRepository taxonomy,
            WorkspaceValidator validator,
            MediaQueryService media,
            AuditService audit,
            TransactionOperations transactions,
            Clock clock,
            Supplier<UUID> projectIds) {
        this.currentAdmin = Objects.requireNonNull(
                currentAdmin, "current administrator provider is required");
        this.sites = Objects.requireNonNull(sites, "site repository is required");
        this.projects = Objects.requireNonNull(projects, "project repository is required");
        this.taxonomy = Objects.requireNonNull(taxonomy, "taxonomy repository is required");
        this.validator = Objects.requireNonNull(validator, "workspace validator is required");
        this.media = Objects.requireNonNull(media, "media query service is required");
        this.audit = Objects.requireNonNull(audit, "audit service is required");
        this.transactions = Objects.requireNonNull(
                transactions, "transaction operations are required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.projectIds = Objects.requireNonNull(projectIds, "project ids are required");
    }

    public SiteWorkspaceDto site() {
        return sites.require();
    }

    public SiteWorkspaceDto updateSite(
            SiteWorkspaceDto workspace, long expectedVersion) {
        requireSiteIdentity(workspace);
        validator.validateSite(workspace);
        List<UUID> referencedMedia = siteMedia(workspace);
        UUID actor = requireActor();
        Instant updatedAt = clock.instant();
        SiteWorkspaceDto result = transactions.execute(status -> {
            requireReady(referencedMedia);
            sites.replace(workspace, expectedVersion, updatedAt);
            audit(actor, "SITE_WORKSPACE", workspace.siteId(), expectedVersion);
            return sites.require();
        });
        return Objects.requireNonNull(result, "site workspace transaction returned no result");
    }

    public List<ProjectWorkspaceDto> projects() {
        return projects.findAll();
    }

    public ProjectWorkspaceDto project(UUID projectId) {
        return projects.require(Objects.requireNonNull(projectId, "projectId"));
    }

    public ProjectWorkspaceDto createProject(ProjectWorkspaceDto requested) {
        Objects.requireNonNull(requested, "workspace");
        UUID generatedId = Objects.requireNonNull(
                projectIds.get(), "generated project id is required");
        ProjectWorkspaceDto workspace = copyProjectRoot(requested, generatedId, 0, true);
        requireProjectStructure(workspace);
        validator.validateProject(workspace);
        List<UUID> referencedMedia = projectMedia(workspace);
        UUID actor = requireActor();
        Instant updatedAt = clock.instant();
        ProjectWorkspaceDto result = transactions.execute(status -> {
            requireReady(referencedMedia);
            projects.insert(workspace, updatedAt);
            audit(actor, "PROJECT_WORKSPACE", generatedId, -1);
            return projects.require(generatedId);
        });
        return Objects.requireNonNull(result, "project create transaction returned no result");
    }

    public ProjectWorkspaceDto updateProject(
            UUID projectId,
            ProjectWorkspaceDto workspace,
            long expectedVersion) {
        requireProjectPath(projectId, workspace);
        requireProjectStructure(workspace);
        validator.validateProject(workspace);

        // This unlocked identity read cannot rename anything. Identity is checked again
        // after the media locks and project row lock are held.
        ProjectWorkspaceDto observed = projects.require(projectId);
        requireExternalKey(observed, workspace);

        List<UUID> referencedMedia = projectMedia(workspace);
        UUID actor = requireActor();
        Instant updatedAt = clock.instant();
        ProjectWorkspaceDto result = transactions.execute(status -> {
            requireReady(referencedMedia);
            ProjectWorkspaceDto locked = projects.requireForUpdate(projectId);
            requireExternalKey(locked, workspace);
            if (locked.version() != expectedVersion) {
                throw versionConflict();
            }
            projects.replace(workspace, expectedVersion, updatedAt);
            audit(actor, "PROJECT_WORKSPACE", projectId, expectedVersion);
            return projects.require(projectId);
        });
        return Objects.requireNonNull(result, "project update transaction returned no result");
    }

    public List<TaxonomyWorkspaceDto> tags() {
        return taxonomy.findTags();
    }

    public TaxonomyWorkspaceDto updateTag(
            UUID tagId,
            Map<LocaleCode, String> names,
            long expectedVersion) {
        return updateTaxonomy(tagId, names, expectedVersion, true);
    }

    public List<TaxonomyWorkspaceDto> skills() {
        return taxonomy.findSkills();
    }

    public TaxonomyWorkspaceDto updateSkill(
            UUID skillId,
            Map<LocaleCode, String> names,
            long expectedVersion) {
        return updateTaxonomy(skillId, names, expectedVersion, false);
    }

    private TaxonomyWorkspaceDto updateTaxonomy(
            UUID id,
            Map<LocaleCode, String> requestedNames,
            long expectedVersion,
            boolean tag) {
        Objects.requireNonNull(id, "taxonomy id");
        Map<LocaleCode, String> names = requireTaxonomyNames(requestedNames);
        UUID actor = requireActor();
        Instant updatedAt = clock.instant();
        TaxonomyWorkspaceDto result = transactions.execute(status -> {
            if (tag) {
                taxonomy.updateTag(id, names, expectedVersion, updatedAt);
            } else {
                taxonomy.updateSkill(id, names, expectedVersion, updatedAt);
            }
            audit(actor, tag ? "TAG" : "SKILL", id, expectedVersion);
            List<TaxonomyWorkspaceDto> values =
                    tag ? taxonomy.findTags() : taxonomy.findSkills();
            return values.stream()
                    .filter(value -> value.id().equals(id))
                    .findFirst()
                    .orElseThrow(() -> taxonomyNotFound(tag));
        });
        return Objects.requireNonNull(result, "taxonomy update transaction returned no result");
    }

    private void requireReady(List<UUID> referencedMedia) {
        for (UUID assetId : referencedMedia) {
            media.requireReadyAsset(assetId);
        }
    }

    private void audit(
            UUID actor,
            String targetType,
            UUID targetId,
            long expectedVersion) {
        Map<String, String> metadata = expectedVersion < 0
                ? Map.of("operation", "CREATE")
                : Map.of("expectedVersion", Long.toString(expectedVersion));
        audit.record(new AuditCommand(
                actor,
                "CONTENT_WORKSPACE_UPDATED",
                targetType,
                targetId.toString(),
                AuditOutcome.SUCCESS,
                null,
                metadata));
    }

    private UUID requireActor() {
        return Objects.requireNonNull(
                currentAdmin.requireAdminId(),
                "current administrator id is required");
    }

    private static List<UUID> siteMedia(SiteWorkspaceDto workspace) {
        TreeSet<UUID> references = new TreeSet<>(UUID_ORDER);
        if (workspace.hero().mediaAssetId() != null) {
            references.add(workspace.hero().mediaAssetId());
        }
        for (SiteWorkspaceDto.ResumeDocument resume : workspace.resumes()) {
            references.add(resume.mediaAssetId());
        }
        return List.copyOf(references);
    }

    private static List<UUID> projectMedia(ProjectWorkspaceDto workspace) {
        TreeSet<UUID> references = new TreeSet<>(UUID_ORDER);
        for (ProjectWorkspaceDto.ProjectMedia item : workspace.media()) {
            references.add(item.assetId());
        }
        for (ContentBlockDto block : workspace.blocks()) {
            ContentBlockDto.Payload payload = block.payload();
            if (payload instanceof ContentBlockDto.ImagePayload image) {
                references.add(image.mediaAssetId());
            } else if (payload instanceof ContentBlockDto.GalleryPayload gallery) {
                references.addAll(gallery.mediaAssetIds());
            } else if (payload instanceof ContentBlockDto.VideoPayload video
                    && video.coverAssetId() != null) {
                references.add(video.coverAssetId());
            } else if (payload instanceof ContentBlockDto.DownloadPayload download
                    && download.mediaAssetId() != null) {
                references.add(download.mediaAssetId());
            }
        }
        return List.copyOf(references);
    }

    private static void requireSiteIdentity(SiteWorkspaceDto workspace) {
        if (workspace == null || !SiteWorkspaceDto.SITE_ID.equals(workspace.siteId())) {
            throw invalid("SITE_WORKSPACE_INVALID", "siteId", "fixed SITE identity required");
        }
        SiteWorkspaceDto.Hero hero = workspace.hero();
        if (hero != null) {
            boolean absent = hero.mediaAssetId() == null
                    && hero.objectPosition() == null
                    && hero.credit() == null
                    && hero.sourceUrl() == null;
            boolean present = hero.mediaAssetId() != null
                    && hero.objectPosition() != null
                    && hero.credit() != null
                    && hero.sourceUrl() != null;
            if (!absent && !present) {
                throw invalid(
                        "SITE_WORKSPACE_INVALID",
                        "hero.media",
                        "hero media fields must be all present or all absent");
            }
        }
        for (int index = 0; index < workspace.resumes().size(); index++) {
            SiteWorkspaceDto.ResumeDocument resume = workspace.resumes().get(index);
            if (resume == null || resume.mediaAssetId() == null) {
                throw invalid(
                        "SITE_WORKSPACE_INVALID",
                        "resumes[" + index + "].mediaAssetId",
                        "required");
            }
        }
    }

    private static void requireProjectPath(
            UUID projectId, ProjectWorkspaceDto workspace) {
        if (projectId == null || workspace == null || !projectId.equals(workspace.id())) {
            throw invalid(
                    "PROJECT_ID_MISMATCH",
                    "id",
                    "path and workspace project ids must match");
        }
    }

    private static void requireProjectStructure(ProjectWorkspaceDto workspace) {
        if (workspace.externalKey() == null || workspace.externalKey().isBlank()) {
            throw invalid(
                    "PROJECT_WORKSPACE_INVALID", "externalKey", "required");
        }
        for (int index = 0; index < workspace.media().size(); index++) {
            ProjectWorkspaceDto.ProjectMedia item = workspace.media().get(index);
            if (item == null || item.assetId() == null) {
                throw invalid(
                        "PROJECT_WORKSPACE_INVALID",
                        "media[" + index + "].assetId",
                        "required");
            }
        }
        Set<UUID> ids = new HashSet<>();
        for (int index = 0; index < workspace.blocks().size(); index++) {
            ContentBlockDto block = workspace.blocks().get(index);
            if (block == null) {
                throw invalid(
                        "CONTENT_BLOCK_INVALID",
                        "blocks[" + index + "]",
                        "required");
            }
            if (block.id() == null || !ids.add(block.id())) {
                throw invalid(
                        "CONTENT_BLOCK_INVALID",
                        "blocks[" + index + "].id",
                        "unique id required");
            }
            ContentBlockDto.Payload payload = block.payload();
            if (payload instanceof ContentBlockDto.ImagePayload image
                    && image.mediaAssetId() == null) {
                throw invalid(
                        "CONTENT_BLOCK_INVALID",
                        "blocks[" + index + "].mediaAssetId",
                        "required");
            }
            if (payload instanceof ContentBlockDto.GalleryPayload gallery) {
                if (gallery.mediaAssetIds() == null) {
                    throw invalid(
                            "CONTENT_BLOCK_INVALID",
                            "blocks[" + index + "].mediaAssetIds",
                            "required");
                }
                for (int mediaIndex = 0;
                        mediaIndex < gallery.mediaAssetIds().size();
                        mediaIndex++) {
                    if (gallery.mediaAssetIds().get(mediaIndex) == null) {
                        throw invalid(
                                "CONTENT_BLOCK_INVALID",
                                "blocks[" + index + "].mediaAssetIds[" + mediaIndex + "]",
                                "required");
                    }
                }
            }
            if (payload instanceof ContentBlockDto.MetricsPayload metrics) {
                for (int metricIndex = 0;
                        metricIndex < metrics.metrics().size();
                        metricIndex++) {
                    UUID metricId = metrics.metrics().get(metricIndex).id();
                    if (metricId == null || !ids.add(metricId)) {
                        throw invalid(
                                "CONTENT_BLOCK_INVALID",
                                "blocks[" + index + "].metrics[" + metricIndex + "].id",
                                "unique id required");
                    }
                }
            }
        }
    }

    private static void requireExternalKey(
            ProjectWorkspaceDto existing, ProjectWorkspaceDto requested) {
        if (!Objects.equals(existing.externalKey(), requested.externalKey())) {
            throw invalid(
                    "PROJECT_IDENTITY_IMMUTABLE",
                    "externalKey",
                    "project external key cannot be changed");
        }
    }

    private static Map<LocaleCode, String> requireTaxonomyNames(
            Map<LocaleCode, String> requested) {
        if (requested == null || !requested.keySet().equals(LOCALES)) {
            throw invalid(
                    "TAXONOMY_TRANSLATION_INVALID",
                    "names",
                    "exactly zh-CN and en are required");
        }
        for (Map.Entry<LocaleCode, String> entry : requested.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isBlank()) {
                throw invalid(
                        "TAXONOMY_TRANSLATION_INVALID",
                        "names." + entry.getKey().value(),
                        "required");
            }
        }
        return Map.copyOf(requested);
    }

    private static ProjectWorkspaceDto copyProjectRoot(
            ProjectWorkspaceDto source,
            UUID id,
            long version,
            boolean publicationDirty) {
        return new ProjectWorkspaceDto(
                id,
                source.externalKey(),
                source.slug(),
                source.number(),
                source.sortOrder(),
                source.featured(),
                source.visible(),
                publicationDirty,
                version,
                source.translations(),
                source.tags(),
                source.skills(),
                source.media(),
                source.blocks());
    }

    private static DomainException invalid(
            String code, String field, String message) {
        return new DomainException(code, HttpStatus.UNPROCESSABLE_ENTITY, Map.of(field, message));
    }

    private static DomainException versionConflict() {
        return new DomainException(
                "CONTENT_VERSION_CONFLICT",
                HttpStatus.CONFLICT,
                Map.of("version", "workspace was changed by another request"));
    }

    private static DomainException taxonomyNotFound(boolean tag) {
        return new DomainException(
                tag ? "TAG_NOT_FOUND" : "SKILL_NOT_FOUND",
                HttpStatus.NOT_FOUND,
                Map.of());
    }
}
