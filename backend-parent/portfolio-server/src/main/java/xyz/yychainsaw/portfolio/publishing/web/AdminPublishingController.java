package xyz.yychainsaw.portfolio.publishing.web;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.publishing.api.ArchiveProjectCommand;
import xyz.yychainsaw.portfolio.publishing.api.PublicationResult;
import xyz.yychainsaw.portfolio.publishing.api.PublishProjectCommand;
import xyz.yychainsaw.portfolio.publishing.api.PublishSiteCommand;
import xyz.yychainsaw.portfolio.publishing.api.ReorderCatalogCommand;
import xyz.yychainsaw.portfolio.publishing.application.PublicationService;
import xyz.yychainsaw.portfolio.publishing.persistence.PublishingRepository.RevisionRow;
import xyz.yychainsaw.portfolio.publishing.snapshot.AggregateType;

@RestController
@RequestMapping("/api/admin/publishing")
@ConditionalOnWebApplication(type = Type.SERVLET)
public final class AdminPublishingController {
    private final PublicationService publishing;

    public AdminPublishingController(PublicationService publishing) {
        this.publishing = Objects.requireNonNull(
                publishing, "publication service is required");
    }

    @PostMapping("/site")
    public ResponseEntity<PublicationResult> publishSite(
            @RequestBody(required = false) PublishSiteCommand command) {
        return ok(publishing.publishSite(requireBody(command)));
    }

    @PostMapping("/projects/{projectId}")
    public ResponseEntity<PublicationResult> publishProject(
            @PathVariable UUID projectId,
            @RequestBody(required = false) PublishProjectCommand command) {
        PublishProjectCommand required = requireBody(command);
        requireMatchingProjectId(projectId, required.projectId());
        return ok(publishing.publishProject(required));
    }

    @PostMapping("/projects/{projectId}/archive")
    public ResponseEntity<PublicationResult> archiveProject(
            @PathVariable UUID projectId,
            @RequestBody(required = false) ArchiveProjectCommand command) {
        ArchiveProjectCommand required = requireBody(command);
        requireMatchingProjectId(projectId, required.projectId());
        return ok(publishing.archiveProject(required));
    }

    @PutMapping("/catalog/order")
    public ResponseEntity<PublicationResult> reorderCatalog(
            @RequestBody(required = false) ReorderCatalogCommand command) {
        return ok(publishing.reorderCatalog(requireBody(command)));
    }

    @GetMapping("/{aggregateType}/{aggregateId}/history")
    public ResponseEntity<List<RevisionSummaryDto>> history(
            @PathVariable AggregateType aggregateType,
            @PathVariable UUID aggregateId) {
        List<RevisionSummaryDto> summaries = publishing.history(aggregateType, aggregateId)
                .stream()
                .map(AdminPublishingController::summary)
                .toList();
        return ok(summaries);
    }

    private static RevisionSummaryDto summary(RevisionRow revision) {
        return new RevisionSummaryDto(
                revision.id(),
                revision.type(),
                revision.aggregateId(),
                revision.version(),
                revision.schemaVersion(),
                revision.checksum(),
                revision.publishedBy(),
                revision.publishedAt());
    }

    private static <T> T requireBody(T body) {
        if (body == null) {
            throw new DomainException(
                    "REQUEST_BODY_INVALID",
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    Map.of("request", "request body is required"));
        }
        return body;
    }

    private static void requireMatchingProjectId(UUID pathId, UUID commandId) {
        if (!Objects.requireNonNull(pathId, "projectId").equals(commandId)) {
            throw new DomainException(
                    "PROJECT_ID_MISMATCH",
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    Map.of("projectId", "path and command project ids must match"));
        }
    }

    private static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(body);
    }
}
