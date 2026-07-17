package xyz.yychainsaw.portfolio.content.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.time.TimeProvider;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import xyz.yychainsaw.portfolio.audit.AuditCommand;
import xyz.yychainsaw.portfolio.audit.AuditService;
import xyz.yychainsaw.portfolio.auth.crypto.TotpService;
import xyz.yychainsaw.portfolio.auth.model.AdminStatus;
import xyz.yychainsaw.portfolio.auth.model.AdminUser;
import xyz.yychainsaw.portfolio.auth.persistence.AdminUserRepository;
import xyz.yychainsaw.portfolio.content.api.CreateProjectWorkspaceRequest;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.content.api.ProjectWorkspaceDto;
import xyz.yychainsaw.portfolio.content.api.SiteWorkspaceDto;
import xyz.yychainsaw.portfolio.content.api.UpdateProjectWorkspaceRequest;
import xyz.yychainsaw.portfolio.content.api.UpdateSiteWorkspaceRequest;
import xyz.yychainsaw.portfolio.content.api.UpdateTaxonomyRequest;
import xyz.yychainsaw.portfolio.content.persistence.SiteWorkspaceRepository;
import xyz.yychainsaw.portfolio.content.support.WorkspaceFixtures;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "portfolio.security.session.cleanup-interval=PT24H")
@Isolated
class AdminContentControllerTest extends PostgresIntegrationTestBase {
    private static final String CSRF_PATH = "/api/admin/auth/csrf";
    private static final String PASSWORD_PATH = "/api/admin/auth/password";
    private static final String SECOND_FACTOR_PATH = "/api/admin/auth/second-factor";
    private static final String SESSION_COOKIE = "PORTFOLIO_SESSION";
    private static final String XSRF_COOKIE = "XSRF-TOKEN";
    private static final String ADMIN_PASSWORD = "Correct-Horse-Content-Battery-47!";
    private static final AtomicInteger REMOTE_SEQUENCE = new AtomicInteger();

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AdminUserRepository admins;
    @Autowired PasswordEncoder passwords;
    @Autowired TotpService totp;
    @Autowired CodeGenerator totpCodes;
    @Autowired TimeProvider totpTime;
    @Autowired JdbcClient jdbc;
    @Autowired SiteWorkspaceRepository sites;
    @MockitoSpyBean AuditService audit;

    private Fixture fixture;
    private UUID createdProjectId;
    private UUID tagId;
    private UUID skillId;

    @AfterEach
    void clean() {
        reset(audit);
        JdbcClient owner = migratorJdbc();
        if (createdProjectId != null) {
            owner.sql("delete from portfolio.project where id=:id")
                    .param("id", createdProjectId).update();
            createdProjectId = null;
        }
        if (tagId != null) {
            owner.sql("delete from portfolio.tag where id=:id")
                    .param("id", tagId).update();
            tagId = null;
        }
        if (skillId != null) {
            owner.sql("delete from portfolio.skill where id=:id")
                    .param("id", skillId).update();
            skillId = null;
        }
        if (fixture != null) {
            fixture.close();
            fixture = null;
        }
    }

    @Test
    void realLoginExercisesAllTenRoutesAndPersistsActorBearingAudits()
            throws Exception {
        Fixture admin = fixture();
        AuthenticatedSession active = login(admin);
        SiteWorkspaceDto currentSite = initializeSite();
        ProjectWorkspaceDto requestedProject = seedTaxonomyAndProjectRequest();

        assertSuccess(get("/api/admin/site/workspace").cookie(active.cookie()), 200);

        CsrfExchange siteCsrf = csrf();
        MvcResult siteUpdate = mvc.perform(withCsrf(put("/api/admin/site/workspace")
                        .cookie(active.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(new UpdateSiteWorkspaceRequest(
                                currentSite.version(),
                                WorkspaceFixtures.site(currentSite.version())))), siteCsrf))
                .andReturn();
        assertSuccess(siteUpdate, 200);
        assertThat(body(siteUpdate).path("version").asLong())
                .isEqualTo(currentSite.version() + 1);

        assertSuccess(get("/api/admin/projects").cookie(active.cookie()), 200);

        CsrfExchange createCsrf = csrf();
        MvcResult created = mvc.perform(withCsrf(post("/api/admin/projects")
                        .cookie(active.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(
                                new CreateProjectWorkspaceRequest(requestedProject))), createCsrf))
                .andReturn();
        assertSuccess(created, 201);
        JsonNode createdBody = body(created);
        createdProjectId = UUID.fromString(createdBody.path("id").asText());
        assertThat(createdProjectId).isNotEqualTo(requestedProject.id());
        assertThat(createdBody.path("version").asLong()).isZero();
        assertThat(createdBody.path("publicationDirty").asBoolean()).isTrue();

        assertSuccess(get("/api/admin/projects/{id}/workspace", createdProjectId)
                .cookie(active.cookie()), 200);
        ProjectWorkspaceDto createdWorkspace =
                json.treeToValue(createdBody, ProjectWorkspaceDto.class);
        CsrfExchange projectCsrf = csrf();
        MvcResult updatedProject = mvc.perform(withCsrf(
                        put("/api/admin/projects/{id}/workspace", createdProjectId)
                                .cookie(active.cookie())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsBytes(
                                        new UpdateProjectWorkspaceRequest(
                                                0, createdWorkspace))),
                        projectCsrf))
                .andReturn();
        assertSuccess(updatedProject, 200);
        assertThat(body(updatedProject).path("version").asLong()).isEqualTo(1);

        assertSuccess(get("/api/admin/tags").cookie(active.cookie()), 200);
        CsrfExchange tagCsrf = csrf();
        MvcResult updatedTag = mvc.perform(withCsrf(put("/api/admin/tags/{id}", tagId)
                        .cookie(active.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(new UpdateTaxonomyRequest(
                                0, localized("玩法更新", "Gameplay updated")))), tagCsrf))
                .andReturn();
        assertSuccess(updatedTag, 200);
        assertThat(body(updatedTag).path("version").asLong()).isEqualTo(1);

        assertSuccess(get("/api/admin/skills").cookie(active.cookie()), 200);
        CsrfExchange skillCsrf = csrf();
        MvcResult updatedSkill = mvc.perform(withCsrf(
                        put("/api/admin/skills/{id}", skillId)
                                .cookie(active.cookie())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsBytes(new UpdateTaxonomyRequest(
                                        0,
                                        localized("虚幻引擎更新", "Unreal Engine updated")))),
                        skillCsrf))
                .andReturn();
        assertSuccess(updatedSkill, 200);
        assertThat(body(updatedSkill).path("version").asLong()).isEqualTo(1);

        List<UUID> actors = jdbc.sql("""
                        select actor_admin_id
                        from portfolio.audit_log
                        where actor_admin_id=:actor
                          and action='CONTENT_WORKSPACE_UPDATED'
                        order by created_at, id
                        """)
                .param("actor", admin.adminId)
                .query(UUID.class)
                .list();
        assertThat(actors).hasSize(5).allMatch(admin.adminId::equals);
    }

    @Test
    void anonymousMissingRevokedAndNoCsrfRequestsAreRejected() throws Exception {
        assertProblem(mvc.perform(get("/api/admin/site/workspace")).andReturn(),
                401, "AUTHENTICATION_REQUIRED");

        Fixture admin = fixture();
        AuthenticatedSession active = login(admin);
        SiteWorkspaceDto current = initializeSite();

        MvcResult noCsrf = mvc.perform(put("/api/admin/site/workspace")
                        .cookie(active.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(new UpdateSiteWorkspaceRequest(
                                current.version(), current))))
                .andReturn();
        assertProblem(noCsrf, 403, "CSRF_INVALID");

        migratorJdbc().sql("""
                        delete from portfolio.admin_session_metadata
                        where session_primary_id=:primaryId
                        """)
                .param("primaryId", active.primaryId())
                .update();
        assertProblem(mvc.perform(get("/api/admin/site/workspace")
                        .cookie(active.cookie())).andReturn(),
                401, "AUTHENTICATION_REQUIRED");

        AuthenticatedSession second = login(admin);
        migratorJdbc().sql("""
                        update portfolio.admin_session_metadata
                        set status='REVOKED', ended_at=clock_timestamp(),
                            revocation_reason='TEST_REVOKED'
                        where session_primary_id=:primaryId
                        """)
                .param("primaryId", second.primaryId())
                .update();
        assertProblem(mvc.perform(get("/api/admin/site/workspace")
                        .cookie(second.cookie())).andReturn(),
                401, "AUTHENTICATION_REQUIRED");
    }

    @Test
    void staleAndPathMismatchResponsesAreStableAndCreateNoSuccessAudit()
            throws Exception {
        Fixture admin = fixture();
        AuthenticatedSession active = login(admin);
        SiteWorkspaceDto current = initializeSite();

        CsrfExchange staleCsrf = csrf();
        MvcResult stale = mvc.perform(withCsrf(put("/api/admin/site/workspace")
                        .cookie(active.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(new UpdateSiteWorkspaceRequest(
                                current.version() + 99,
                                WorkspaceFixtures.site(current.version())))), staleCsrf))
                .andReturn();
        assertProblem(stale, 409, "CONTENT_VERSION_CONFLICT");

        ProjectWorkspaceDto project = WorkspaceFixtures.project();
        CsrfExchange mismatchCsrf = csrf();
        MvcResult mismatch = mvc.perform(withCsrf(
                        put("/api/admin/projects/{id}/workspace", UUID.randomUUID())
                                .cookie(active.cookie())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsBytes(
                                        new UpdateProjectWorkspaceRequest(0, project))),
                        mismatchCsrf))
                .andReturn();
        assertProblem(mismatch, 422, "PROJECT_ID_MISMATCH");

        assertThat(contentAuditCount(admin.adminId)).isZero();
    }

    @Test
    void databaseCheckConstraintReturnsStable422AndRollsBackProjectCreate()
            throws Exception {
        Fixture admin = fixture();
        AuthenticatedSession active = login(admin);
        ProjectWorkspaceDto requested = seedTaxonomyAndProjectRequest();
        ProjectWorkspaceDto invalid = withSortOrder(requested, -1);

        CsrfExchange csrf = csrf();
        MvcResult result = mvc.perform(withCsrf(post("/api/admin/projects")
                        .cookie(active.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(
                                new CreateProjectWorkspaceRequest(invalid))), csrf))
                .andReturn();

        assertProblem(result, 422, "CONTENT_WORKSPACE_INVALID");
        assertThat(body(result).path("fieldErrors").path("sortOrder").asText())
                .isNotBlank();
        assertThat(jdbc.sql("select count(*) from portfolio.project where external_key=:key")
                .param("key", invalid.externalKey())
                .query(Long.class)
                .single()).isZero();
        assertThat(contentAuditCount(admin.adminId)).isZero();
    }

    @Test
    void auditFailureRollsBackTheWorkspaceMutation() throws Exception {
        Fixture admin = fixture();
        AuthenticatedSession active = login(admin);
        SiteWorkspaceDto before = initializeSite();
        doThrow(new IllegalStateException("forced content audit failure"))
                .when(audit)
                .record(argThat(AdminContentControllerTest::isContentAudit));

        CsrfExchange csrf = csrf();
        MvcResult result = mvc.perform(withCsrf(put("/api/admin/site/workspace")
                        .cookie(active.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(new UpdateSiteWorkspaceRequest(
                                before.version(),
                                WorkspaceFixtures.site(before.version())))), csrf))
                .andReturn();

        assertProblem(result, 500, "INTERNAL_ERROR");
        SiteWorkspaceDto after = sites.require();
        assertThat(after.version()).isEqualTo(before.version());
        assertThat(after).isEqualTo(before);
        assertThat(contentAuditCount(admin.adminId)).isZero();
    }

    private SiteWorkspaceDto initializeSite() {
        long version = migratorJdbc().sql("""
                        select version from portfolio.site_profile
                        where id=:siteId
                        """)
                .param("siteId", SiteWorkspaceDto.SITE_ID)
                .query(Long.class)
                .single();
        sites.replace(WorkspaceFixtures.site(version), version);
        return sites.require();
    }

    private ProjectWorkspaceDto seedTaxonomyAndProjectRequest() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        tagId = UUID.randomUUID();
        skillId = UUID.randomUUID();
        JdbcClient owner = migratorJdbc();
        owner.sql("insert into portfolio.tag(id, normalized_key) values (:id, :key)")
                .param("id", tagId).param("key", "tag-" + suffix).update();
        insertTaxonomyTranslations(owner, "tag_translation", "tag_id", tagId,
                "玩法", "Gameplay");
        owner.sql("insert into portfolio.skill(id, normalized_key) values (:id, :key)")
                .param("id", skillId).param("key", "skill-" + suffix).update();
        insertTaxonomyTranslations(owner, "skill_translation", "skill_id", skillId,
                "虚幻引擎", "Unreal Engine");

        ProjectWorkspaceDto base = WorkspaceFixtures.projectWithoutMedia();
        int sortOrder = owner.sql("select coalesce(max(sort_order), -1) + 1 from portfolio.project")
                .query(Integer.class)
                .single();
        return new ProjectWorkspaceDto(
                UUID.randomUUID(),
                "mvc-" + suffix,
                "mvc-" + suffix.substring(0, 12),
                "01",
                sortOrder,
                false,
                true,
                false,
                99,
                base.translations(),
                List.of(new ProjectWorkspaceDto.TaxonomyRef(
                        tagId, "tag-" + suffix, 0, localized("玩法", "Gameplay"))),
                List.of(new ProjectWorkspaceDto.TaxonomyRef(
                        skillId,
                        "skill-" + suffix,
                        0,
                        localized("虚幻引擎", "Unreal Engine"))),
                List.of(),
                List.of());
    }

    private static ProjectWorkspaceDto withSortOrder(
            ProjectWorkspaceDto source,
            int sortOrder) {
        return new ProjectWorkspaceDto(
                source.id(),
                source.externalKey(),
                source.slug(),
                source.number(),
                sortOrder,
                source.featured(),
                source.visible(),
                source.publicationDirty(),
                source.version(),
                source.translations(),
                source.tags(),
                source.skills(),
                source.media(),
                source.blocks());
    }

    private static void insertTaxonomyTranslations(
            JdbcClient owner,
            String table,
            String idColumn,
            UUID id,
            String chinese,
            String english) {
        String sql = "insert into portfolio." + table + "(" + idColumn
                + ", locale, name) values (:id, :locale, :name)";
        owner.sql(sql).param("id", id).param("locale", "zh-CN")
                .param("name", chinese).update();
        owner.sql(sql).param("id", id).param("locale", "en")
                .param("name", english).update();
    }

    private Fixture fixture() {
        fixture = new Fixture();
        return fixture;
    }

    private AuthenticatedSession login(Fixture admin) throws Exception {
        CsrfExchange csrf = csrf();
        String remote = nextRemote();
        MvcResult password = mvc.perform(withCsrf(post(PASSWORD_PATH)
                        .with(remote(remote))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of(
                                "username", admin.username,
                                "password", ADMIN_PASSWORD))), csrf))
                .andReturn();
        assertThat(password.getResponse().getStatus()).isEqualTo(200);
        Cookie pending = findResponseCookie(password, SESSION_COOKIE)
                .orElseThrow(() -> new AssertionError("pending session cookie was not set"));
        String primaryId = requirePrimaryId(pending.getValue());
        admin.primaryIds.add(primaryId);

        MvcResult second = mvc.perform(withCsrf(post(SECOND_FACTOR_PATH)
                        .cookie(pending)
                        .with(remote(remote))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of(
                                "method", "TOTP",
                                "code", currentCode(admin.totpSecret)))), csrf))
                .andReturn();
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        Cookie active = findResponseCookie(second, SESSION_COOKIE).orElse(pending);
        assertThat(requirePrimaryId(active.getValue())).isEqualTo(primaryId);
        return new AuthenticatedSession(active, primaryId);
    }

    private CsrfExchange csrf() throws Exception {
        MvcResult source = mvc.perform(get(CSRF_PATH)).andReturn();
        assertThat(source.getResponse().getStatus()).isEqualTo(200);
        Cookie cookie = findResponseCookie(source, XSRF_COOKIE)
                .orElseThrow(() -> new AssertionError("CSRF cookie was not set"));
        JsonNode body = body(source);
        return new CsrfExchange(
                body.path("headerName").asText(), body.path("token").asText(), cookie);
    }

    private String currentCode(String secret) throws Exception {
        return totpCodes.generate(secret, totpTime.getTime() / 30);
    }

    private String requirePrimaryId(String publicId) {
        return jdbc.sql("select primary_id from portfolio.spring_session where session_id=:id")
                .param("id", publicId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new AssertionError("Spring Session row is missing"));
    }

    private long contentAuditCount(UUID actorId) {
        return jdbc.sql("""
                        select count(*) from portfolio.audit_log
                        where actor_admin_id=:actor
                          and action='CONTENT_WORKSPACE_UPDATED'
                        """)
                .param("actor", actorId)
                .query(Long.class)
                .single();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    private void assertSuccess(
            MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
        assertSuccess(mvc.perform(request).andReturn(), expectedStatus);
    }

    private static void assertSuccess(MvcResult result, int expectedStatus) {
        assertThat(result.getResponse().getStatus()).isEqualTo(expectedStatus);
        assertNoStore(result);
    }

    private void assertProblem(MvcResult result, int status, String code)
            throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertNoStore(result);
        assertThat(body(result).path("code").asText()).isEqualTo(code);
    }

    private static void assertNoStore(MvcResult result) {
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL))
                .contains("no-store");
    }

    private static MockHttpServletRequestBuilder withCsrf(
            MockHttpServletRequestBuilder request, CsrfExchange csrf) {
        return request.cookie(csrf.cookie()).header(csrf.headerName(), csrf.token());
    }

    private static RequestPostProcessor remote(String address) {
        return request -> {
            request.setRemoteAddr(address);
            request.addHeader(HttpHeaders.USER_AGENT, "Task4-Content-Integration-Test/1.0");
            return request;
        };
    }

    private static String nextRemote() {
        int sequence = REMOTE_SEQUENCE.incrementAndGet();
        int third = 1 + Math.floorMod(sequence / 250, 250);
        int fourth = 1 + Math.floorMod(sequence, 250);
        return "198.18." + third + "." + fourth;
    }

    private static Optional<Cookie> findResponseCookie(MvcResult result, String name) {
        Cookie direct = result.getResponse().getCookie(name);
        if (direct != null) {
            return Optional.of(direct);
        }
        for (String header : result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)) {
            String first = header.split(";", 2)[0];
            int separator = first.indexOf('=');
            if (separator > 0 && first.substring(0, separator).trim().equals(name)) {
                return Optional.of(new Cookie(name, first.substring(separator + 1).trim()));
            }
        }
        return Optional.empty();
    }

    private static boolean isContentAudit(AuditCommand command) {
        return command != null && "CONTENT_WORKSPACE_UPDATED".equals(command.action());
    }

    private static Map<LocaleCode, String> localized(String chinese, String english) {
        return Map.of(LocaleCode.ZH_CN, chinese, LocaleCode.EN, english);
    }

    private final class Fixture implements AutoCloseable {
        private final UUID adminId = UUID.randomUUID();
        private final String username = "ContentAdmin" + adminId.toString().replace("-", "");
        private final String totpSecret;
        private final Set<String> primaryIds = new LinkedHashSet<>();

        private Fixture() {
            TotpService.Enrollment enrollment = totp.beginEnrollment(adminId, username);
            totpSecret = enrollment.plaintextSecret();
            Instant now = Instant.now();
            admins.insert(new AdminUser(
                    adminId,
                    username,
                    passwords.encode(ADMIN_PASSWORD),
                    AdminStatus.ACTIVE,
                    enrollment.encryptedSecret(),
                    null,
                    0,
                    now,
                    now));
        }

        @Override
        public void close() {
            JdbcClient owner = migratorJdbc();
            owner.sql("alter table portfolio.audit_log disable trigger audit_log_reject_mutation")
                    .update();
            try {
                owner.sql("delete from portfolio.audit_log where actor_admin_id=:id")
                        .param("id", adminId).update();
            } finally {
                owner.sql("alter table portfolio.audit_log enable trigger audit_log_reject_mutation")
                        .update();
            }
            for (String primaryId : primaryIds) {
                owner.sql("delete from portfolio.spring_session where primary_id=:id")
                        .param("id", primaryId).update();
            }
            owner.sql("delete from portfolio.admin_user where id=:id")
                    .param("id", adminId).update();
        }
    }

    private record CsrfExchange(String headerName, String token, Cookie cookie) {}

    private record AuthenticatedSession(Cookie cookie, String primaryId) {}
}
