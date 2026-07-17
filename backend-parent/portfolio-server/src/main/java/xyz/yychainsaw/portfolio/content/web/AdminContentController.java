package xyz.yychainsaw.portfolio.content.web;

import java.util.List;
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
import xyz.yychainsaw.portfolio.content.api.CreateProjectWorkspaceRequest;
import xyz.yychainsaw.portfolio.content.api.ProjectWorkspaceDto;
import xyz.yychainsaw.portfolio.content.api.SiteWorkspaceDto;
import xyz.yychainsaw.portfolio.content.api.TaxonomyWorkspaceDto;
import xyz.yychainsaw.portfolio.content.api.UpdateProjectWorkspaceRequest;
import xyz.yychainsaw.portfolio.content.api.UpdateSiteWorkspaceRequest;
import xyz.yychainsaw.portfolio.content.api.UpdateTaxonomyRequest;
import xyz.yychainsaw.portfolio.content.application.ContentWorkspaceService;

@RestController
@RequestMapping("/api/admin")
@ConditionalOnWebApplication(type = Type.SERVLET)
public final class AdminContentController {
    private final ContentWorkspaceService content;

    public AdminContentController(ContentWorkspaceService content) {
        this.content = Objects.requireNonNull(
                content, "content workspace service is required");
    }

    @GetMapping("/site/workspace")
    public ResponseEntity<SiteWorkspaceDto> site() {
        return ok(content.site());
    }

    @PutMapping("/site/workspace")
    public ResponseEntity<SiteWorkspaceDto> updateSite(
            @RequestBody UpdateSiteWorkspaceRequest request) {
        Objects.requireNonNull(request, "request");
        return ok(content.updateSite(request.workspace(), request.expectedVersion()));
    }

    @GetMapping("/projects")
    public ResponseEntity<List<ProjectWorkspaceDto>> projects() {
        return ok(content.projects());
    }

    @PostMapping("/projects")
    public ResponseEntity<ProjectWorkspaceDto> createProject(
            @RequestBody CreateProjectWorkspaceRequest request) {
        Objects.requireNonNull(request, "request");
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(content.createProject(request.workspace()));
    }

    @GetMapping("/projects/{projectId}/workspace")
    public ResponseEntity<ProjectWorkspaceDto> project(
            @PathVariable UUID projectId) {
        return ok(content.project(projectId));
    }

    @PutMapping("/projects/{projectId}/workspace")
    public ResponseEntity<ProjectWorkspaceDto> updateProject(
            @PathVariable UUID projectId,
            @RequestBody UpdateProjectWorkspaceRequest request) {
        Objects.requireNonNull(request, "request");
        return ok(content.updateProject(
                projectId, request.workspace(), request.expectedVersion()));
    }

    @GetMapping("/tags")
    public ResponseEntity<List<TaxonomyWorkspaceDto>> tags() {
        return ok(content.tags());
    }

    @PutMapping("/tags/{tagId}")
    public ResponseEntity<TaxonomyWorkspaceDto> updateTag(
            @PathVariable UUID tagId,
            @RequestBody UpdateTaxonomyRequest request) {
        Objects.requireNonNull(request, "request");
        return ok(content.updateTag(
                tagId, request.names(), request.expectedVersion()));
    }

    @GetMapping("/skills")
    public ResponseEntity<List<TaxonomyWorkspaceDto>> skills() {
        return ok(content.skills());
    }

    @PutMapping("/skills/{skillId}")
    public ResponseEntity<TaxonomyWorkspaceDto> updateSkill(
            @PathVariable UUID skillId,
            @RequestBody UpdateTaxonomyRequest request) {
        Objects.requireNonNull(request, "request");
        return ok(content.updateSkill(
                skillId, request.names(), request.expectedVersion()));
    }

    private static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(body);
    }
}
