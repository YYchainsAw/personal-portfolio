package xyz.yychainsaw.portfolio.publishing.snapshot;

import xyz.yychainsaw.portfolio.content.api.SiteWorkspaceDto;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.SiteSnapshotV1;

public interface SiteSnapshotMapper {
    SiteSnapshotV1 toSnapshot(SiteWorkspaceDto workspace);

    SiteWorkspaceDto restore(SiteSnapshotV1 snapshot, SiteWorkspaceDto currentWorkspace);
}
