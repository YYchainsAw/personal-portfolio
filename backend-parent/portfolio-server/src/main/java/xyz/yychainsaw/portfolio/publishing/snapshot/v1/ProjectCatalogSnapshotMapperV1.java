package xyz.yychainsaw.portfolio.publishing.snapshot.v1;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.IntStream;
import org.springframework.stereotype.Component;
import xyz.yychainsaw.portfolio.publishing.snapshot.ProjectCatalogSnapshotMapper;

@Component
public final class ProjectCatalogSnapshotMapperV1 implements ProjectCatalogSnapshotMapper {
    private static final int SCHEMA_VERSION = 1;
    private static final String COVER_USAGE = "COVER";

    @Override
    public ProjectCatalogSnapshotV1 fromCurrentProjects(List<ProjectSnapshotV1> projects) {
        List<ProjectCatalogSnapshotV1.Card> cards = IntStream.range(0, projects.size())
                .mapToObj(index -> toCard(projects.get(index), index))
                .toList();
        return new ProjectCatalogSnapshotV1(SCHEMA_VERSION, cards);
    }

    private static ProjectCatalogSnapshotV1.Card toCard(
            ProjectSnapshotV1 project, int sortOrder) {
        return new ProjectCatalogSnapshotV1.Card(
                project.projectId(),
                project.slug(),
                project.number(),
                sortOrder,
                project.featured(),
                toCardCopy(project),
                findCover(project));
    }

    private static Map<LocaleV1, ProjectCatalogSnapshotV1.CardCopy> toCardCopy(
            ProjectSnapshotV1 project) {
        Map<LocaleV1, ProjectCatalogSnapshotV1.CardCopy> copy =
                new EnumMap<>(LocaleV1.class);
        project.translations().forEach((locale, translation) -> copy.put(
                locale,
                new ProjectCatalogSnapshotV1.CardCopy(
                        translation.status(),
                        translation.eyebrow(),
                        translation.title(),
                        translation.summary(),
                        project.tags().stream()
                                .map(tag -> tag.names().get(locale))
                                .filter(Objects::nonNull)
                                .toList())));
        return copy;
    }

    private static PublishedMediaV1 findCover(ProjectSnapshotV1 project) {
        UUID coverAssetId = project.projectMedia().stream()
                .filter(media -> COVER_USAGE.equals(media.usage()))
                .map(ProjectSnapshotV1.ProjectMediaV1::assetId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        if (coverAssetId == null) {
            return null;
        }
        return project.media().stream()
                .filter(media -> coverAssetId.equals(media.assetId()))
                .findFirst()
                .orElse(null);
    }
}
