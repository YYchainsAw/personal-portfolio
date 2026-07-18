package xyz.yychainsaw.portfolio.publishing.web;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import xyz.yychainsaw.portfolio.auth.session.AdminSessionService;
import xyz.yychainsaw.portfolio.auth.session.AdminSessionService.ActiveSession;
import xyz.yychainsaw.portfolio.auth.web.AdminPrincipal;
import xyz.yychainsaw.portfolio.auth.web.LoginSubjectHasher;
import xyz.yychainsaw.portfolio.auth.web.SecurityProblemWriter;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.common.ratelimit.RateLimitProperties;
import xyz.yychainsaw.portfolio.config.SecurityConfiguration;
import xyz.yychainsaw.portfolio.content.api.SiteWorkspaceDto;
import xyz.yychainsaw.portfolio.publishing.api.ArchiveProjectCommand;
import xyz.yychainsaw.portfolio.publishing.api.PreviewTokenRequest;
import xyz.yychainsaw.portfolio.publishing.api.PreviewTokenResponse;
import xyz.yychainsaw.portfolio.publishing.api.PublicationResult;
import xyz.yychainsaw.portfolio.publishing.api.PublishProjectCommand;
import xyz.yychainsaw.portfolio.publishing.api.PublishSiteCommand;
import xyz.yychainsaw.portfolio.publishing.api.ReorderCatalogCommand;
import xyz.yychainsaw.portfolio.publishing.api.RestoreRevisionRequest;
import xyz.yychainsaw.portfolio.publishing.application.PreviewService;
import xyz.yychainsaw.portfolio.publishing.application.PreviewTokenService;
import xyz.yychainsaw.portfolio.publishing.application.PreviewTokenService.PreviewClaims;
import xyz.yychainsaw.portfolio.publishing.application.PublicationService;
import xyz.yychainsaw.portfolio.publishing.application.RestoreService;
import xyz.yychainsaw.portfolio.publishing.persistence.PublishingRepository.RevisionRow;
import xyz.yychainsaw.portfolio.publishing.snapshot.AggregateType;

@WebMvcTest(AdminPublishingController.class)
@org.springframework.context.annotation.Import({
        SecurityConfiguration.class,
        SecurityProblemWriter.class
})
@TestPropertySource(properties = {
        "server.servlet.session.cookie.secure=false",
        "portfolio.web.allow-development-cors=false"
})
class AdminPublishingControllerTest {
    private static final UUID ADMIN_ID =
            UUID.fromString("92000000-0000-4000-8000-000000000001");
    private static final UUID PROJECT_ID =
            UUID.fromString("92000000-0000-4000-8000-000000000002");
    private static final UUID SECOND_PROJECT_ID =
            UUID.fromString("92000000-0000-4000-8000-000000000003");
    private static final UUID REVISION_ID =
            UUID.fromString("92000000-0000-4000-8000-000000000004");
    private static final UUID CATALOG_REVISION_ID =
            UUID.fromString("92000000-0000-4000-8000-000000000005");
    private static final UUID PREVIEW_NONCE =
            UUID.fromString("92000000-0000-4000-8000-000000000006");
    private static final String PREVIEW_TOKEN = "payload.signature";
    private static final Instant PREVIEW_EXPIRES_AT =
            Instant.parse("2026-07-17T00:10:00Z");
    private static final String NO_STORE = CacheControl.noStore().getHeaderValue();

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @MockitoBean PublicationService publishing;
    @MockitoBean PreviewTokenService previewTokens;
    @MockitoBean PreviewService previews;
    @MockitoBean RestoreService restores;
    @MockitoBean AdminSessionService sessions;
    @MockitoBean LoginSubjectHasher subjects;
    @MockitoBean RateLimitProperties rateLimits;

    private MockHttpSession session;

    @BeforeEach
    void prepareActiveAdminSession() {
        session = new MockHttpSession(null, "92000000-0000-4000-8000-000000000010");
        Instant now = Instant.parse("2026-07-17T00:00:00Z");
        given(sessions.requireActive(session.getId())).willReturn(new ActiveSession(
                UUID.fromString("92000000-0000-4000-8000-000000000011"),
                ADMIN_ID,
                session.getId(),
                now,
                now));
    }

    @Test
    void anonymousMissingAndRevokedAdminSessionsAreUnauthorized() throws Exception {
        mvc.perform(get("/api/admin/publishing/SITE/{aggregateId}/history", PROJECT_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NO_STORE))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        MockHttpSession missing =
                new MockHttpSession(null, "92000000-0000-4000-8000-000000000012");
        given(sessions.requireActive(missing.getId())).willThrow(authenticationRequired());
        mvc.perform(get("/api/admin/publishing/SITE/{aggregateId}/history", PROJECT_ID)
                        .session(missing)
                        .with(admin()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        given(sessions.requireActive(session.getId())).willThrow(authenticationRequired());
        mvc.perform(authenticated(
                        get("/api/admin/publishing/SITE/{aggregateId}/history", PROJECT_ID)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        verifyNoInteractions(publishing);
    }

    @Test
    void everyMutationRequiresCsrfBeforeCallingPublicationService() throws Exception {
        mvc.perform(authenticated(post("/api/admin/publishing/site")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NO_STORE))
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mvc.perform(authenticated(post(
                                "/api/admin/publishing/projects/{projectId}", PROJECT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")))
                .andExpect(status().isForbidden());
        mvc.perform(authenticated(post(
                                "/api/admin/publishing/projects/{projectId}/archive", PROJECT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")))
                .andExpect(status().isForbidden());
        mvc.perform(authenticated(put("/api/admin/publishing/catalog/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(publishing);
    }

    @Test
    void allFiveRoutesReturnTheirBodiesAndNoStore() throws Exception {
        PublishSiteCommand site = new PublishSiteCommand(7, 11);
        PublishProjectCommand project =
                new PublishProjectCommand(PROJECT_ID, 7, 11, 13);
        ArchiveProjectCommand archive = new ArchiveProjectCommand(PROJECT_ID, 12, 14);
        ReorderCatalogCommand reorder =
                new ReorderCatalogCommand(15, List.of(SECOND_PROJECT_ID, PROJECT_ID));
        PublicationResult siteResult = result(12, null);
        PublicationResult projectResult = result(12, 14L);
        PublicationResult archiveResult = result(13, 15L);
        PublicationResult reorderResult = new PublicationResult(
                CATALOG_REVISION_ID, 16, null, null, "catalog-checksum");
        RevisionRow revision = new RevisionRow(
                REVISION_ID,
                AggregateType.PROJECT,
                PROJECT_ID,
                12,
                1,
                "{\"schemaVersion\":1}",
                "project-checksum",
                ADMIN_ID,
                Instant.parse("2026-07-17T00:01:00Z"));

        given(publishing.publishSite(site)).willReturn(siteResult);
        given(publishing.publishProject(project)).willReturn(projectResult);
        given(publishing.archiveProject(archive)).willReturn(archiveResult);
        given(publishing.reorderCatalog(reorder)).willReturn(reorderResult);
        given(publishing.history(AggregateType.PROJECT, PROJECT_ID))
                .willReturn(List.of(revision));

        assertOkNoStore(post("/api/admin/publishing/site")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(site)))
                .andExpect(jsonPath("$.revisionId").value(REVISION_ID.toString()))
                .andExpect(jsonPath("$.aggregateVersion").value(12));
        assertOkNoStore(post("/api/admin/publishing/projects/{projectId}", PROJECT_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(project)))
                .andExpect(jsonPath("$.catalogRevisionId")
                        .value(CATALOG_REVISION_ID.toString()))
                .andExpect(jsonPath("$.catalogVersion").value(14));
        assertOkNoStore(post(
                                "/api/admin/publishing/projects/{projectId}/archive", PROJECT_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(archive)))
                .andExpect(jsonPath("$.aggregateVersion").value(13))
                .andExpect(jsonPath("$.catalogVersion").value(15));
        assertOkNoStore(put("/api/admin/publishing/catalog/order")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(reorder)))
                .andExpect(jsonPath("$.revisionId")
                        .value(CATALOG_REVISION_ID.toString()))
                .andExpect(jsonPath("$.aggregateVersion").value(16));
        assertOkNoStore(get(
                        "/api/admin/publishing/{aggregateType}/{aggregateId}/history",
                        AggregateType.PROJECT,
                        PROJECT_ID))
                .andExpect(jsonPath("$[0].id").value(REVISION_ID.toString()))
                .andExpect(jsonPath("$[0].type").value("PROJECT"))
                .andExpect(jsonPath("$[0].aggregateId").value(PROJECT_ID.toString()))
                .andExpect(jsonPath("$[0].version").value(12))
                .andExpect(jsonPath("$[0].schemaVersion").value(1))
                .andExpect(jsonPath("$[0].checksum").value("project-checksum"))
                .andExpect(jsonPath("$[0].publishedBy").value(ADMIN_ID.toString()))
                .andExpect(jsonPath("$[0].publishedAt")
                        .value("2026-07-17T00:01:00Z"))
                .andExpect(jsonPath("$[0].json").doesNotExist());

        verify(publishing).publishSite(site);
        verify(publishing).publishProject(project);
        verify(publishing).archiveProject(archive);
        verify(publishing).reorderCatalog(reorder);
        verify(publishing).history(AggregateType.PROJECT, PROJECT_ID);
    }

    @Test
    void projectPathAndBodyMismatchIsRejectedWithoutCallingService() throws Exception {
        UUID bodyProjectId = SECOND_PROJECT_ID;
        PublishProjectCommand publish =
                new PublishProjectCommand(bodyProjectId, 7, 11, 13);
        ArchiveProjectCommand archive =
                new ArchiveProjectCommand(bodyProjectId, 11, 13);

        assertProjectIdMismatch(post(
                        "/api/admin/publishing/projects/{projectId}", PROJECT_ID)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(publish)));
        assertProjectIdMismatch(post(
                        "/api/admin/publishing/projects/{projectId}/archive", PROJECT_ID)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(archive)));

        verifyNoInteractions(publishing);
    }

    @Test
    void stalePublicationVersionFromServiceIsReturnedAsConflict() throws Exception {
        PublishSiteCommand command = new PublishSiteCommand(7, 999);
        given(publishing.publishSite(command)).willThrow(new DomainException(
                "PUBLICATION_VERSION_CONFLICT",
                HttpStatus.CONFLICT,
                Map.of("publicationVersion", "publication was changed")));

        mvc.perform(authenticated(post("/api/admin/publishing/site")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(command))))
                .andExpect(status().isConflict())
                .andExpect(header().string(
                        HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.code").value("PUBLICATION_VERSION_CONFLICT"));
    }

    @Test
    void historyGetDoesNotRequireCsrf() throws Exception {
        given(publishing.history(AggregateType.SITE, PROJECT_ID)).willReturn(List.of());

        assertOkNoStore(get(
                        "/api/admin/publishing/{aggregateType}/{aggregateId}/history",
                        AggregateType.SITE,
                        PROJECT_ID))
                .andExpect(jsonPath("$").isArray());

        verify(publishing).history(AggregateType.SITE, PROJECT_ID);
    }

    @Test
    void literalNullMutationBodiesReturnStableClientProblemWithoutServiceCalls()
            throws Exception {
        assertInvalidNullBody(post("/api/admin/publishing/site")
                .with(csrf()));
        assertInvalidNullBody(post(
                        "/api/admin/publishing/projects/{projectId}", PROJECT_ID)
                .with(csrf()));
        assertInvalidNullBody(post(
                        "/api/admin/publishing/projects/{projectId}/archive", PROJECT_ID)
                .with(csrf()));
        assertInvalidNullBody(put("/api/admin/publishing/catalog/order")
                .with(csrf()));

        verifyNoInteractions(publishing);
    }

    @Test
    void allThreePreviewAndRestoreRoutesReturnTheirBodiesAndNoStore() throws Exception {
        PreviewTokenRequest request =
                new PreviewTokenRequest(AggregateType.PROJECT, PROJECT_ID, 7);
        PreviewTokenResponse response =
                new PreviewTokenResponse(PREVIEW_TOKEN, PREVIEW_EXPIRES_AT);
        PreviewClaims claims = previewClaims(AggregateType.PROJECT, PROJECT_ID, 7);
        RestoreRevisionRequest restore = new RestoreRevisionRequest(11);
        Map<String, Object> snapshot = Map.of(
                "schemaVersion", 1,
                "projectId", PROJECT_ID.toString());

        given(previewTokens.issue(request, ADMIN_ID)).willReturn(response);
        given(previewTokens.verify(PREVIEW_TOKEN, ADMIN_ID)).willReturn(claims);
        given(previews.preview(claims)).willReturn(snapshot);

        assertOkNoStore(post("/api/admin/publishing/preview-tokens")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(request)))
                .andExpect(jsonPath("$.token").value(PREVIEW_TOKEN))
                .andExpect(jsonPath("$.expiresAt").value("2026-07-17T00:10:00Z"));
        assertOkNoStore(get(
                        "/api/admin/publishing/previews/{token}", PREVIEW_TOKEN))
                .andExpect(jsonPath("$.schemaVersion").value(1))
                .andExpect(jsonPath("$.projectId").value(PROJECT_ID.toString()));
        mvc.perform(authenticated(post(
                                "/api/admin/publishing/revisions/{revisionId}/restore",
                                REVISION_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(restore))))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NO_STORE));

        verify(previewTokens).issue(request, ADMIN_ID);
        verify(previewTokens).verify(PREVIEW_TOKEN, ADMIN_ID);
        verify(previews).preview(claims);
        verify(restores).restore(REVISION_ID, 11);
    }

    @Test
    void previewTokenIssueAndRevisionRestoreRequireCsrf() throws Exception {
        PreviewTokenRequest issue =
                new PreviewTokenRequest(AggregateType.SITE, SiteWorkspaceDto.SITE_ID, 7);
        RestoreRevisionRequest restore = new RestoreRevisionRequest(11);

        mvc.perform(authenticated(post("/api/admin/publishing/preview-tokens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(issue))))
                .andExpect(status().isForbidden())
                .andExpect(header().string(
                        HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mvc.perform(authenticated(post(
                                "/api/admin/publishing/revisions/{revisionId}/restore",
                                REVISION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(restore))))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NO_STORE))
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));

        verifyNoInteractions(previewTokens, previews, restores);
    }

    @Test
    void anonymousPreviewConsumptionIsUnauthorized() throws Exception {
        mvc.perform(get("/api/admin/publishing/previews/{token}", PREVIEW_TOKEN))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NO_STORE))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        verifyNoInteractions(previewTokens, previews);
    }

    @Test
    void previewTokenOwnedByAnotherAdminReturnsUniformForbiddenProblem()
            throws Exception {
        given(previewTokens.verify(PREVIEW_TOKEN, ADMIN_ID)).willThrow(new DomainException(
                "PREVIEW_TOKEN_INVALID", HttpStatus.FORBIDDEN, Map.of()));

        mvc.perform(authenticated(get(
                                "/api/admin/publishing/previews/{token}", PREVIEW_TOKEN)))
                .andExpect(status().isForbidden())
                .andExpect(header().string(
                        HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.code").value("PREVIEW_TOKEN_INVALID"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty());

        verify(previewTokens).verify(PREVIEW_TOKEN, ADMIN_ID);
        verifyNoInteractions(previews);
    }

    @Test
    void stalePreviewWorkspaceVersionIsReturnedAsConflict() throws Exception {
        PreviewClaims claims = previewClaims(AggregateType.PROJECT, PROJECT_ID, 7);
        given(previewTokens.verify(PREVIEW_TOKEN, ADMIN_ID)).willReturn(claims);
        given(previews.preview(claims)).willThrow(new DomainException(
                "CONTENT_VERSION_CONFLICT",
                HttpStatus.CONFLICT,
                Map.of("version", "workspace was changed by another request")));

        mvc.perform(authenticated(get(
                                "/api/admin/publishing/previews/{token}", PREVIEW_TOKEN)))
                .andExpect(status().isConflict())
                .andExpect(header().string(
                        HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.code").value("CONTENT_VERSION_CONFLICT"));

        verify(previewTokens).verify(PREVIEW_TOKEN, ADMIN_ID);
        verify(previews).preview(claims);
    }

    @Test
    void catalogPreviewTokenIssueIsRejectedAsUnprocessable() throws Exception {
        PreviewTokenRequest request = new PreviewTokenRequest(
                AggregateType.PROJECT_CATALOG,
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                7);
        given(previewTokens.issue(request, ADMIN_ID)).willThrow(new DomainException(
                "CATALOG_PREVIEW_NOT_ALLOWED",
                HttpStatus.UNPROCESSABLE_ENTITY,
                Map.of()));

        mvc.perform(authenticated(post("/api/admin/publishing/preview-tokens")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(request))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string(
                        HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.code").value("CATALOG_PREVIEW_NOT_ALLOWED"));

        verify(previewTokens).issue(request, ADMIN_ID);
        verifyNoInteractions(previews, restores);
    }

    @Test
    void literalNullPreviewTokenAndRestoreBodiesReturnStableClientProblem()
            throws Exception {
        assertInvalidNullBody(post("/api/admin/publishing/preview-tokens")
                .with(csrf()));
        assertInvalidNullBody(post(
                        "/api/admin/publishing/revisions/{revisionId}/restore",
                        REVISION_ID)
                .with(csrf()));

        verifyNoInteractions(previewTokens, previews, restores);
    }

    private PublicationResult result(long aggregateVersion, Long catalogVersion) {
        return new PublicationResult(
                REVISION_ID,
                aggregateVersion,
                catalogVersion == null ? null : CATALOG_REVISION_ID,
                catalogVersion,
                "project-checksum");
    }

    private static PreviewClaims previewClaims(
            AggregateType type, UUID aggregateId, long workspaceVersion) {
        return new PreviewClaims(
                ADMIN_ID,
                type,
                aggregateId,
                workspaceVersion,
                PREVIEW_EXPIRES_AT,
                PREVIEW_NONCE);
    }

    private ResultActions assertOkNoStore(MockHttpServletRequestBuilder request)
            throws Exception {
        return mvc.perform(authenticated(request))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NO_STORE));
    }

    private void assertProjectIdMismatch(MockHttpServletRequestBuilder request)
            throws Exception {
        mvc.perform(authenticated(request))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string(
                        HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.code").value("PROJECT_ID_MISMATCH"))
                .andExpect(jsonPath("$.fieldErrors.projectId")
                        .value("path and command project ids must match"));
    }

    private void assertInvalidNullBody(MockHttpServletRequestBuilder request)
            throws Exception {
        mvc.perform(authenticated(request
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("null")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string(
                        HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.code").value("REQUEST_BODY_INVALID"))
                .andExpect(jsonPath("$.fieldErrors.request")
                        .value("request body is required"));
    }

    private MockHttpServletRequestBuilder authenticated(
            MockHttpServletRequestBuilder request) {
        return request.session(session).with(admin());
    }

    private RequestPostProcessor admin() {
        AdminPrincipal principal = new AdminPrincipal(ADMIN_ID, "yychainsaw");
        UsernamePasswordAuthenticationToken authenticated =
                UsernamePasswordAuthenticationToken.authenticated(
                        principal,
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        return authentication(authenticated);
    }

    private static DomainException authenticationRequired() {
        return new DomainException(
                "AUTHENTICATION_REQUIRED", HttpStatus.UNAUTHORIZED, Map.of());
    }
}
