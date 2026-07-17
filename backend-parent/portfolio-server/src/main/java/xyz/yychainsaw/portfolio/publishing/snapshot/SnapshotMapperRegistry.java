package xyz.yychainsaw.portfolio.publishing.snapshot;

import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.content.api.ProjectWorkspaceDto;
import xyz.yychainsaw.portfolio.content.api.SiteWorkspaceDto;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.ProjectCatalogSnapshotV1;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.ProjectSnapshotV1;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.SiteSnapshotV1;

@Component
public final class SnapshotMapperRegistry {
    private static final int SUPPORTED_SCHEMA_VERSION = 1;

    private final SnapshotCodec codec;
    private final SiteSnapshotMapper siteMapper;
    private final ProjectSnapshotMapper projectMapper;
    private final ProjectCatalogSnapshotMapper catalogMapper;

    public SnapshotMapperRegistry(
            SnapshotCodec codec,
            SiteSnapshotMapper siteMapper,
            ProjectSnapshotMapper projectMapper,
            ProjectCatalogSnapshotMapper catalogMapper) {
        this.codec = Objects.requireNonNull(codec, "codec");
        this.siteMapper = Objects.requireNonNull(siteMapper, "siteMapper");
        this.projectMapper = Objects.requireNonNull(projectMapper, "projectMapper");
        this.catalogMapper = Objects.requireNonNull(catalogMapper, "catalogMapper");
    }

    public SiteWorkspaceDto restoreSite(
            int schemaVersion, String json, SiteWorkspaceDto currentWorkspace) {
        requireSupportedSchema(schemaVersion);
        SiteSnapshotV1 snapshot = codec.decode(json, SiteSnapshotV1.class);
        return siteMapper.restore(snapshot, currentWorkspace);
    }

    public ProjectWorkspaceDto restoreProject(
            int schemaVersion, String json, long currentVersion) {
        requireSupportedSchema(schemaVersion);
        ProjectSnapshotV1 snapshot = codec.decode(json, ProjectSnapshotV1.class);
        return projectMapper.restore(snapshot, currentVersion);
    }

    public SiteSnapshotV1 readSite(int schemaVersion, String json) {
        requireSupportedSchema(schemaVersion);
        return codec.decode(json, SiteSnapshotV1.class);
    }

    public ProjectSnapshotV1 readProject(int schemaVersion, String json) {
        requireSupportedSchema(schemaVersion);
        return codec.decode(json, ProjectSnapshotV1.class);
    }

    public ProjectCatalogSnapshotV1 readCatalog(int schemaVersion, String json) {
        requireSupportedSchema(schemaVersion);
        return codec.decode(json, ProjectCatalogSnapshotV1.class);
    }

    private static void requireSupportedSchema(int schemaVersion) {
        if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            throw new DomainException(
                    "SNAPSHOT_SCHEMA_UNSUPPORTED",
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    Map.of("snapshotSchemaVersion", Integer.toString(schemaVersion)));
        }
    }
}
