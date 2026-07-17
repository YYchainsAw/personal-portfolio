package xyz.yychainsaw.portfolio.content.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;

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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import xyz.yychainsaw.portfolio.auth.session.AdminSessionService;
import xyz.yychainsaw.portfolio.auth.session.AdminSessionService.ActiveSession;
import xyz.yychainsaw.portfolio.auth.web.AdminPrincipal;
import xyz.yychainsaw.portfolio.auth.web.LoginSubjectHasher;
import xyz.yychainsaw.portfolio.auth.web.SecurityProblemWriter;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.common.ratelimit.RateLimitProperties;
import xyz.yychainsaw.portfolio.config.SecurityConfiguration;
import xyz.yychainsaw.portfolio.content.api.CreateProjectWorkspaceRequest;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.content.api.ProjectWorkspaceDto;
import xyz.yychainsaw.portfolio.content.api.TaxonomyWorkspaceDto;
import xyz.yychainsaw.portfolio.content.api.UpdateProjectWorkspaceRequest;
import xyz.yychainsaw.portfolio.content.api.UpdateSiteWorkspaceRequest;
import xyz.yychainsaw.portfolio.content.api.UpdateTaxonomyRequest;
import xyz.yychainsaw.portfolio.content.application.ContentWorkspaceService;
import xyz.yychainsaw.portfolio.content.support.WorkspaceFixtures;

@WebMvcTest(AdminContentController.class)
@org.springframework.context.annotation.Import({
        SecurityConfiguration.class,
        SecurityProblemWriter.class
})
@TestPropertySource(properties = {
        "server.servlet.session.cookie.secure=false",
        "portfolio.web.allow-development-cors=false"
})
class AdminContentControllerSliceTest {
    private static final UUID ADMIN_ID =
            UUID.fromString("91000000-0000-4000-8000-000000000001");
    private static final UUID TAG_ID =
            UUID.fromString("91000000-0000-4000-8000-000000000002");
    private static final UUID SKILL_ID =
            UUID.fromString("91000000-0000-4000-8000-000000000003");
    private static final String NO_STORE = CacheControl.noStore().getHeaderValue();

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @MockitoBean ContentWorkspaceService content;
    @MockitoBean AdminSessionService sessions;
    @MockitoBean LoginSubjectHasher subjects;
    @MockitoBean RateLimitProperties rateLimits;

    private MockHttpSession session;
    private ProjectWorkspaceDto project;
    private TaxonomyWorkspaceDto tag;
    private TaxonomyWorkspaceDto skill;

    @BeforeEach
    void prepareActiveAdminSession() {
        session = new MockHttpSession(null, "91000000-0000-4000-8000-000000000010");
        Instant now = Instant.parse("2026-07-17T00:00:00Z");
        given(sessions.requireActive(session.getId())).willReturn(new ActiveSession(
                UUID.fromString("91000000-0000-4000-8000-000000000011"),
                ADMIN_ID,
                session.getId(),
                now,
                now));
        project = WorkspaceFixtures.project();
        tag = new TaxonomyWorkspaceDto(
                TAG_ID,
                "gameplay",
                1,
                localized("玩法", "Gameplay"));
        skill = new TaxonomyWorkspaceDto(
                SKILL_ID,
                "unreal-engine",
                2,
                localized("虚幻引擎", "Unreal Engine"));
    }

    @Test
    void anonymousMissingSessionAndRevokedSessionAreUnauthorized() throws Exception {
        mvc.perform(get("/api/admin/site/workspace"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NO_STORE))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        MockHttpSession missingMetadata =
                new MockHttpSession(null, "91000000-0000-4000-8000-000000000012");
        given(sessions.requireActive(missingMetadata.getId()))
                .willThrow(authenticationRequired());
        mvc.perform(get("/api/admin/site/workspace")
                        .session(missingMetadata)
                        .with(admin()))
                .andExpect(status().isUnauthorized());

        given(sessions.requireActive(session.getId())).willThrow(authenticationRequired());
        mvc.perform(get("/api/admin/site/workspace")
                        .session(session)
                        .with(admin()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(content);
    }

    @Test
    void allTenRoutesReturnTheirDocumentedStatusBodiesAndNoStore() throws Exception {
        given(content.site()).willReturn(WorkspaceFixtures.site(0));
        given(content.updateSite(any(), eq(0L))).willReturn(WorkspaceFixtures.site(1));
        given(content.projects()).willReturn(List.of(project));
        given(content.createProject(any())).willReturn(project);
        given(content.project(project.id())).willReturn(project);
        given(content.updateProject(eq(project.id()), any(), eq(0L))).willReturn(project);
        given(content.tags()).willReturn(List.of(tag));
        given(content.updateTag(eq(TAG_ID), any(), eq(1L))).willReturn(tag);
        given(content.skills()).willReturn(List.of(skill));
        given(content.updateSkill(eq(SKILL_ID), any(), eq(2L))).willReturn(skill);

        assertOkNoStore(get("/api/admin/site/workspace"))
                .andExpect(jsonPath("$.siteId").value(
                        WorkspaceFixtures.site(0).siteId().toString()));
        assertOkNoStore(put("/api/admin/site/workspace")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(new UpdateSiteWorkspaceRequest(
                                0, WorkspaceFixtures.site(0)))))
                .andExpect(jsonPath("$.version").value(1));
        assertOkNoStore(get("/api/admin/projects"))
                .andExpect(jsonPath("$[0].id").value(project.id().toString()));

        mvc.perform(authenticated(post("/api/admin/projects")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(
                                new CreateProjectWorkspaceRequest(project)))))
                .andExpect(status().isCreated())
                .andExpect(header().string(
                        HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.id").value(project.id().toString()));

        assertOkNoStore(get("/api/admin/projects/{id}/workspace", project.id()))
                .andExpect(jsonPath("$.slug").value(project.slug()));
        assertOkNoStore(put("/api/admin/projects/{id}/workspace", project.id())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(
                                new UpdateProjectWorkspaceRequest(0, project))))
                .andExpect(jsonPath("$.id").value(project.id().toString()));
        assertOkNoStore(get("/api/admin/tags"))
                .andExpect(jsonPath("$[0].normalizedKey").value("gameplay"));
        assertOkNoStore(put("/api/admin/tags/{id}", TAG_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(
                                new UpdateTaxonomyRequest(1, tag.names()))))
                .andExpect(jsonPath("$.version").value(1));
        assertOkNoStore(get("/api/admin/skills"))
                .andExpect(jsonPath("$[0].normalizedKey").value("unreal-engine"));
        assertOkNoStore(put("/api/admin/skills/{id}", SKILL_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(
                                new UpdateTaxonomyRequest(2, skill.names()))))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void everyMutationRequiresCsrfBeforeCallingTheService() throws Exception {
        mvc.perform(authenticated(put("/api/admin/site/workspace")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NO_STORE))
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mvc.perform(authenticated(post("/api/admin/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")))
                .andExpect(status().isForbidden());
        mvc.perform(authenticated(put("/api/admin/projects/{id}/workspace", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")))
                .andExpect(status().isForbidden());
        mvc.perform(authenticated(put("/api/admin/tags/{id}", TAG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")))
                .andExpect(status().isForbidden());
        mvc.perform(authenticated(put("/api/admin/skills/{id}", SKILL_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(content);
    }

    @Test
    void pathMismatchAndStaleVersionUseStableProblemCodes() throws Exception {
        UUID wrongPath = UUID.fromString("91000000-0000-4000-8000-000000000020");
        given(content.updateProject(eq(wrongPath), any(), anyLong())).willThrow(
                new DomainException(
                        "PROJECT_ID_MISMATCH",
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        Map.of("id", "path and workspace project ids must match")));
        mvc.perform(authenticated(put("/api/admin/projects/{id}/workspace", wrongPath)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(
                                new UpdateProjectWorkspaceRequest(0, project)))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string(
                        HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.code").value("PROJECT_ID_MISMATCH"));

        given(content.updateSite(any(), eq(999L))).willThrow(new DomainException(
                "CONTENT_VERSION_CONFLICT", HttpStatus.CONFLICT, Map.of()));
        mvc.perform(authenticated(put("/api/admin/site/workspace")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(new UpdateSiteWorkspaceRequest(
                                999, WorkspaceFixtures.site(0))))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONTENT_VERSION_CONFLICT"));
    }

    private org.springframework.test.web.servlet.ResultActions assertOkNoStore(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        return mvc.perform(authenticated(request))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NO_STORE));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authenticated(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) {
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

    private static Map<LocaleCode, String> localized(String chinese, String english) {
        return Map.of(LocaleCode.ZH_CN, chinese, LocaleCode.EN, english);
    }

    private static DomainException authenticationRequired() {
        return new DomainException(
                "AUTHENTICATION_REQUIRED", HttpStatus.UNAUTHORIZED, Map.of());
    }
}
