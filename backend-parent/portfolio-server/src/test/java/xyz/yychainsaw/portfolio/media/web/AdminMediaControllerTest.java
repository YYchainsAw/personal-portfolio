package xyz.yychainsaw.portfolio.media.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import xyz.yychainsaw.portfolio.api.admin.media.MediaAssetView;
import xyz.yychainsaw.portfolio.api.admin.media.MediaPageView;
import xyz.yychainsaw.portfolio.auth.session.AdminSessionService;
import xyz.yychainsaw.portfolio.auth.session.AdminSessionService.ActiveSession;
import xyz.yychainsaw.portfolio.auth.web.AdminPrincipal;
import xyz.yychainsaw.portfolio.auth.web.LoginSubjectHasher;
import xyz.yychainsaw.portfolio.auth.web.SecurityProblemWriter;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.common.ratelimit.RateLimitProperties;
import xyz.yychainsaw.portfolio.config.SecurityConfiguration;
import xyz.yychainsaw.portfolio.media.application.AdminMediaPreviewService;
import xyz.yychainsaw.portfolio.media.application.MediaManagementService;
import xyz.yychainsaw.portfolio.media.application.MediaRangeNotSatisfiableException;
import xyz.yychainsaw.portfolio.media.application.MediaUploadService;
import xyz.yychainsaw.portfolio.media.application.UploadMediaCommand;

@WebMvcTest(AdminMediaController.class)
@org.springframework.context.annotation.Import({
        SecurityConfiguration.class,
        SecurityProblemWriter.class
})
@TestPropertySource(properties = {
        "server.servlet.session.cookie.secure=false",
        "portfolio.web.allow-development-cors=false"
})
class AdminMediaControllerTest {
    private static final UUID ADMIN_ID = UUID.fromString(
            "10000000-0000-4000-8000-000000000001");
    private static final UUID ASSET_ID = UUID.fromString(
            "20000000-0000-4000-8000-000000000001");
    private static final String SHA256 = "a".repeat(64);
    private static final String NO_STORE = CacheControl.noStore().getHeaderValue();

    @Autowired MockMvc mvc;
    @Autowired AdminMediaController controller;

    @MockitoBean MediaUploadService uploads;
    @MockitoBean MediaManagementService media;
    @MockitoBean AdminMediaPreviewService previews;
    @MockitoBean AdminSessionService sessions;
    @MockitoBean LoginSubjectHasher subjects;
    @MockitoBean RateLimitProperties rateLimits;

    private MockHttpSession session;

    @BeforeEach
    void prepareActiveAdminSession() {
        session = new MockHttpSession(
                null, "50000000-0000-4000-8000-000000000002");
        Instant now = Instant.parse("2026-07-17T00:00:00Z");
        given(sessions.requireActive(session.getId())).willReturn(new ActiveSession(
                UUID.fromString("50000000-0000-4000-8000-000000000001"),
                ADMIN_ID,
                session.getId(),
                now,
                now));
    }

    @Test
    void anonymousAdminMediaRequestReturnsJson401WithoutCallingServices() throws Exception {
        mvc.perform(get("/api/admin/media"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NO_STORE))
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mvc.perform(get("/api/admin/media/{id}", ASSET_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NO_STORE))
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        verifyNoInteractions(uploads, media, previews);
    }

    @Test
    void authenticatedMutationsWithoutCsrfReturn403BeforeCallingServices() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "hero.png", "image/png", new byte[] {1, 2, 3});

        mvc.perform(multipart("/api/admin/media")
                        .file(file)
                        .session(session)
                        .with(admin()))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NO_STORE))
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mvc.perform(put("/api/admin/media/{id}/translations", ASSET_ID)
                        .session(session)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validTranslationsRequestJson()))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NO_STORE))
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mvc.perform(delete("/api/admin/media/{id}", ASSET_ID)
                        .session(session)
                        .with(admin()))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NO_STORE))
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));

        verifyNoInteractions(uploads, media, previews);
    }

    @Test
    void uploadReturns201NoStoreAndPassesOnlyTheMultipartMetadataAndStream()
            throws Exception {
        byte[] bytes = new byte[] {(byte) 0x89, 'P', 'N', 'G'};
        MockMultipartFile file = new MockMultipartFile(
                "file", "hero.png", "image/png", bytes);
        given(uploads.upload(any(UploadMediaCommand.class))).willReturn(asset());

        mvc.perform(multipart("/api/admin/media")
                        .file(file)
                        .session(session)
                        .with(admin())
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NO_STORE))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(ASSET_ID.toString()))
                .andExpect(jsonPath("$.originalFilename").value("hero.png"))
                .andExpect(jsonPath("$.status").value("READY"));

        ArgumentCaptor<UploadMediaCommand> command =
                ArgumentCaptor.forClass(UploadMediaCommand.class);
        verify(uploads).upload(command.capture());
        assertThat(command.getValue().filename()).isEqualTo("hero.png");
        assertThat(command.getValue().declaredContentType()).isEqualTo("image/png");
        assertThat(command.getValue().declaredSize()).isEqualTo(bytes.length);
        assertThat(command.getValue().input().readAllBytes()).containsExactly(bytes);
    }

    @Test
    void uploadUsesSafeFilenameFallbackWhenMultipartFilenameIsNull() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        InputStream input = new ByteArrayInputStream(new byte[] {1});
        when(file.getOriginalFilename()).thenReturn(null);
        when(file.getContentType()).thenReturn("image/png");
        when(file.getSize()).thenReturn(1L);
        when(file.getInputStream()).thenReturn(input);
        given(uploads.upload(any(UploadMediaCommand.class))).willReturn(asset());

        ResponseEntity<MediaAssetView> response = controller.upload(file);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo(NO_STORE);
        ArgumentCaptor<UploadMediaCommand> command =
                ArgumentCaptor.forClass(UploadMediaCommand.class);
        verify(uploads).upload(command.capture());
        assertThat(command.getValue().filename()).isEqualTo("upload");
        assertThat(command.getValue().input()).isSameAs(input);
    }

    @Test
    void listUsesStableDefaultsAndReturnsJsonNoStore() throws Exception {
        MediaPageView page = new MediaPageView(List.of(asset()), 0, 24, 1, 1);
        given(media.list(0, 24, null)).willReturn(page);

        mvc.perform(get("/api/admin/media")
                        .session(session)
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NO_STORE))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(24))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].id").value(ASSET_ID.toString()));

        verify(media).list(0, 24, null);
    }

    @Test
    void getReturnsTheExactAssetJsonWithNoStore() throws Exception {
        given(media.get(ASSET_ID)).willReturn(asset());

        mvc.perform(get("/api/admin/media/{id}", ASSET_ID)
                        .session(session)
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NO_STORE))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(ASSET_ID.toString()))
                .andExpect(jsonPath("$.originalFilename").value("hero.png"))
                .andExpect(jsonPath("$.mimeType").value("image/png"))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.sha256").value(SHA256));

        verify(media).get(ASSET_ID);
    }

    @Test
    void translationsValidateElementsAndReturnTheUpdatedJsonNoStore() throws Exception {
        given(media.updateTranslations(eq(ASSET_ID), eq(2L), any())).willReturn(asset());

        mvc.perform(put("/api/admin/media/{id}/translations", ASSET_ID)
                        .session(session)
                        .with(admin())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validTranslationsRequestJson()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NO_STORE))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(ASSET_ID.toString()))
                .andExpect(jsonPath("$.sha256").value(SHA256));

        verify(media).updateTranslations(eq(ASSET_ID), eq(2L), any());
    }

    @Test
    void translationsRejectAnInvalidListElementBeforeTheService() throws Exception {
        mvc.perform(put("/api/admin/media/{id}/translations", ASSET_ID)
                        .session(session)
                        .with(admin())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":2,"translations":[
                                  {"locale":"fr","altText":"Invalid"},
                                  {"locale":"en","altText":"Valid"}
                                ]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(media);
    }

    @Test
    void translationsRequireAnExplicitExpectedVersionBeforeTheService() throws Exception {
        mvc.perform(put("/api/admin/media/{id}/translations", ASSET_ID)
                        .session(session)
                        .with(admin())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"translations":[
                                  {"locale":"zh-CN","altText":"Gameplay zh"},
                                  {"locale":"en","altText":"Gameplay"}
                                ]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.expectedVersion")
                        .value("must be provided"));

        verifyNoInteractions(media);
    }

    @Test
    void translationsExposeTheStableNestedSourceUrlValidationPath() throws Exception {
        mvc.perform(put("/api/admin/media/{id}/translations", ASSET_ID)
                        .session(session)
                        .with(admin())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":2,"translations":[
                                  {"locale":"zh-CN","sourceUrl":"https://01.02.03.04/"},
                                  {"locale":"en","sourceUrl":null}
                                ]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath(
                                "$.fieldErrors['translations[0].sourceUrl']")
                        .value("must be an HTTPS URL"));

        verifyNoInteractions(media);
    }

    @Test
    void staleTranslationVersionReturnsARecognizableConflictProblem() throws Exception {
        given(media.updateTranslations(eq(ASSET_ID), eq(2L), any()))
                .willThrow(new DomainException(
                        "MEDIA_VERSION_CONFLICT", HttpStatus.CONFLICT, Map.of()));

        mvc.perform(put("/api/admin/media/{id}/translations", ASSET_ID)
                        .session(session)
                        .with(admin())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validTranslationsRequestJson()))
                .andExpect(status().isConflict())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NO_STORE))
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MEDIA_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty());

        verify(media).updateTranslations(eq(ASSET_ID), eq(2L), any());
    }

    @Test
    void previewForwardsConditionalHeadersPreservesSafeHeadersAndAddsNoStore()
            throws Exception {
        ResponseEntity<StreamingResponseBody> redirect = ResponseEntity
                .status(HttpStatus.FOUND)
                .location(URI.create("https://preview.example/signed"))
                .eTag("\"safe-etag\"")
                .build();
        given(previews.preview(eq(ASSET_ID), eq("w640"), any(HttpHeaders.class)))
                .willReturn(redirect);

        MvcResult result = mvc.perform(get("/api/admin/media/{id}/preview/{variant}",
                                ASSET_ID, "w640")
                        .session(session)
                        .with(admin())
                        .header(HttpHeaders.RANGE, "bytes=0-3")
                        .header(HttpHeaders.IF_NONE_MATCH, "\"previous\""))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NO_STORE))
                .andExpect(header().string(
                        HttpHeaders.LOCATION, "https://preview.example/signed"))
                .andExpect(header().string(HttpHeaders.ETAG, "\"safe-etag\""))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("objectKey", "private/", "staging/");
        ArgumentCaptor<HttpHeaders> headers = ArgumentCaptor.forClass(HttpHeaders.class);
        verify(previews).preview(eq(ASSET_ID), eq("w640"), headers.capture());
        assertThat(headers.getValue().getFirst(HttpHeaders.RANGE)).isEqualTo("bytes=0-3");
        assertThat(headers.getValue().getFirst(HttpHeaders.IF_NONE_MATCH))
                .isEqualTo("\"previous\"");
    }

    @Test
    void streamedPreviewStaysSynchronousSoTheSessionSaveLockCoversTheBody()
            throws Exception {
        byte[] bytes = new byte[] {1, 2, 3, 4};
        ResponseEntity<StreamingResponseBody> stream = ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .contentLength(bytes.length)
                .body(output -> output.write(bytes));
        given(previews.preview(eq(ASSET_ID), eq("w640"), any(HttpHeaders.class)))
                .willReturn(stream);

        mvc.perform(get("/api/admin/media/{id}/preview/{variant}", ASSET_ID, "w640")
                        .session(session)
                        .with(admin()))
                .andExpect(request().asyncNotStarted())
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NO_STORE))
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(bytes));
    }

    @Test
    void previewClosesAnOpenedBodyWhenTheServletOutputCannotBeAcquired()
            throws Exception {
        CloseTrackingBody body = new CloseTrackingBody();
        ResponseEntity<StreamingResponseBody> stream = ResponseEntity
                .<StreamingResponseBody>ok()
                .contentType(MediaType.IMAGE_PNG)
                .contentLength(4)
                .body(body);
        given(previews.preview(eq(ASSET_ID), eq("w640"), any(HttpHeaders.class)))
                .willReturn(stream);
        HttpServletResponse servletResponse = mock(HttpServletResponse.class);
        when(servletResponse.getOutputStream())
                .thenThrow(new IOException("synthetic output failure"));

        assertThatThrownBy(() -> controller.preview(
                        ASSET_ID, "w640", new HttpHeaders(), servletResponse))
                .isInstanceOf(IOException.class)
                .hasMessage("synthetic output failure");

        assertThat(body.writeAttempted).isFalse();
        assertThat(body.closed).isTrue();
    }

    @Test
    void unsatisfiablePreviewRangeReturnsEmpty416WithOnlyTheSafeLengthHeader()
            throws Exception {
        given(previews.preview(eq(ASSET_ID), eq("w640"), any(HttpHeaders.class)))
                .willThrow(new MediaRangeNotSatisfiableException(4096));

        mvc.perform(get("/api/admin/media/{id}/preview/{variant}", ASSET_ID, "w640")
                        .session(session)
                        .with(admin())
                        .header(HttpHeaders.RANGE, "bytes=4096-"))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes */4096"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NO_STORE))
                .andExpect(content().string(""));
    }

    @Test
    void deleteArchivesAndReturns204NoStore() throws Exception {
        mvc.perform(delete("/api/admin/media/{id}", ASSET_ID)
                        .session(session)
                        .with(admin())
                        .with(csrf()))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NO_STORE))
                .andExpect(content().string(""));

        verify(media).archive(ASSET_ID);
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

    private static String validTranslationsRequestJson() {
        return """
                {
                  "expectedVersion": 2,
                  "translations": [
                    {"locale":"zh-CN","altText":"Gameplay zh","caption":"Scene zh",
                     "credit":"Yi Jiaxuan","sourceUrl":"https://example.com/zh"},
                    {"locale":"en","altText":"Gameplay","caption":"Combat scene",
                     "credit":"Yi Jiaxuan","sourceUrl":"https://example.com/en"}
                  ]
                }
                """;
    }

    private static MediaAssetView asset() {
        return new MediaAssetView(
                ASSET_ID,
                "hero.png",
                "image/png",
                4,
                640,
                360,
                SHA256,
                "READY",
                2,
                Instant.parse("2026-07-17T00:00:00Z"),
                Instant.parse("2026-07-17T00:00:01Z"));
    }

    private static final class CloseTrackingBody
            implements StreamingResponseBody, AutoCloseable {
        private boolean writeAttempted;
        private boolean closed;

        @Override
        public void writeTo(java.io.OutputStream output) {
            writeAttempted = true;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
