package xyz.yychainsaw.portfolio.publishing.snapshot;

import xyz.yychainsaw.portfolio.content.api.ProjectWorkspaceDto;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.ProjectSnapshotV1;

public interface ProjectSnapshotMapper {
    ProjectSnapshotV1 toSnapshot(ProjectWorkspaceDto workspace);

    ProjectWorkspaceDto restore(ProjectSnapshotV1 snapshot, long currentWorkspaceVersion);
}
