package xyz.yychainsaw.portfolio.publishing.snapshot;

import java.util.List;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.ProjectCatalogSnapshotV1;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.ProjectSnapshotV1;

public interface ProjectCatalogSnapshotMapper {
    ProjectCatalogSnapshotV1 fromCurrentProjects(List<ProjectSnapshotV1> projects);
}
