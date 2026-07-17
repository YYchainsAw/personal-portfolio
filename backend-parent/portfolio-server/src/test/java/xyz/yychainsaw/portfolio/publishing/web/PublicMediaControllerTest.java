package xyz.yychainsaw.portfolio.publishing.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import xyz.yychainsaw.portfolio.common.error.GlobalProblemHandler;
import xyz.yychainsaw.portfolio.media.application.MediaQueryService;
import xyz.yychainsaw.portfolio.media.application.MediaVariantDescriptor;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;
import xyz.yychainsaw.portfolio.media.storage.ByteRange;
import xyz.yychainsaw.portfolio.media.storage.StorageException;
import xyz.yychainsaw.portfolio.media.storage.StorageLocation;
import xyz.yychainsaw.portfolio.media.storage.StorageRead;
import xyz.yychainsaw.portfolio.media.storage.StorageRouter;
import xyz.yychainsaw.portfolio.media.storage.StorageService;
import xyz.yychainsaw.portfolio.publishing.persistence.PublicMediaReferenceRepository;

@ExtendWith(MockitoExtension.class)
class PublicMediaControllerTest {
    private static final UUID ASSET_ID = UUID.fromString(
            "71000000-0000-4000-8000-000000000001");
    private static final String VARIANT_NAME = "w640";
    private static final String VARIANT_SHA256 = "b".repeat(64);
    private static final String STRONG_ETAG = '"' + VARIANT_SHA256 + '"';
    private static final String OBJECT_KEY =
            "media/71000000-0000-4000-8000-000000000001/w640/image.png";
    private static final byte[] BYTES = new byte[] {1, 2, 3, 4};
    private static final String LOCAL_CACHE_CONTROL = "public, no-cache";
    private static final String COS_BUCKET = "portfolio-1234567890";
    private static final String COS_REGION = "ap-guangzhou";
    private static final String DOCUMENT_VARIANT_NAME = "document";
    private static final String DOCUMENT_OBJECT_KEY =
            "media/71000000-0000-4000-8000-000000000001/document/file.pdf";
    private static final byte[] PDF_BYTES = new byte[] {37, 80, 68, 70};

    @Mock PublicMediaReferenceRepository references;
    @Mock MediaQueryService media;
    @Mock StorageRouter storageRouter;
    @Mock StorageService storage;

    private PublicMediaController controller;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        controller = new PublicMediaController(references, media, storageRouter);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalProblemHandler())
                .build();
    }

    @Test
    void unpublishedReferenceReturns404BeforeMediaEtagRangeOrStorageWork()
            throws Exception {
        when(references.isCurrentlyPublished(ASSET_ID, VARIANT_NAME)).thenReturn(false);

        mvc.perform(get("/api/public/media/{id}/{variant}", ASSET_ID, VARIANT_NAME)
                        .header(HttpHeaders.IF_NONE_MATCH, STRONG_ETAG)
                        .header(HttpHeaders.RANGE, "bytes=0-0"))
                .andExpect(status().isNotFound());

        verify(references).isCurrentlyPublished(ASSET_ID, VARIANT_NAME);
        verifyNoInteractions(media, storageRouter, storage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1280", "w0", "W640"})
    void invalidPublicVariantIsRejectedBeforeThePublicationRepository(
            String invalidVariant) throws Exception {
        mvc.perform(get("/api/public/media/{id}/{variant}", ASSET_ID, invalidVariant))
                .andExpect(status().isNotFound());

        verifyNoInteractions(references, media, storageRouter, storage);
    }

    @Test
    void localFullResponseUsesDatabaseMetadataStreamsSynchronouslyAndClosesRead()
            throws Exception {
        MediaVariantDescriptor variant = arrangeCurrentLocal();
        CloseTrackingInputStream input = new CloseTrackingInputStream(BYTES);
        when(storage.open(OBJECT_KEY, Optional.empty())).thenReturn(read(
                input,
                BYTES.length,
                Optional.empty(),
                BYTES.length,
                "image/png",
                VARIANT_SHA256));

        mvc.perform(get("/api/public/media/{id}/{variant}", ASSET_ID, VARIANT_NAME))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, LOCAL_CACHE_CONTROL))
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andExpect(header().string(HttpHeaders.ETAG, STRONG_ETAG))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, BYTES.length))
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(BYTES));

        verify(storage).open(variant.objectKey(), Optional.empty());
        assertThat(input.closeCount).isEqualTo(1);
    }

    @Test
    void localDocumentStreamsWithPdfMetadataAndClosesExactlyOnce()
            throws Exception {
        MediaVariantDescriptor variant = localDocumentVariant();
        arrangeCurrent(variant, new StorageLocation(StorageProvider.LOCAL, null, null));
        CloseTrackingInputStream input = new CloseTrackingInputStream(PDF_BYTES);
        when(storage.open(DOCUMENT_OBJECT_KEY, Optional.empty())).thenReturn(read(
                input,
                PDF_BYTES.length,
                Optional.empty(),
                PDF_BYTES.length,
                MediaType.APPLICATION_PDF_VALUE,
                VARIANT_SHA256));

        mvc.perform(get(
                        "/api/public/media/{id}/{variant}",
                        ASSET_ID,
                        DOCUMENT_VARIANT_NAME))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(content().bytes(PDF_BYTES));

        verify(storage).open(DOCUMENT_OBJECT_KEY, Optional.empty());
        assertThat(input.closeCount).isEqualTo(1);
    }

    @Test
    void localRangeUsesActualStorageReadRangeAndLengthFor206Headers()
            throws Exception {
        arrangeCurrentLocal();
        ByteRange requested = new ByteRange(1, 3);
        ByteRange actuallyServed = new ByteRange(2, 3);
        CloseTrackingInputStream input = new CloseTrackingInputStream(new byte[] {3, 4});
        when(storage.open(OBJECT_KEY, Optional.of(requested))).thenReturn(read(
                input,
                BYTES.length,
                Optional.of(actuallyServed),
                actuallyServed.length(),
                "image/png",
                VARIANT_SHA256));

        mvc.perform(get("/api/public/media/{id}/{variant}", ASSET_ID, VARIANT_NAME)
                        .header(HttpHeaders.RANGE, "bytes=1-3"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, LOCAL_CACHE_CONTROL))
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andExpect(header().string(HttpHeaders.ETAG, STRONG_ETAG))
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 2-3/4"))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, 2L))
                .andExpect(content().bytes(new byte[] {3, 4}));

        verify(storage).open(OBJECT_KEY, Optional.of(requested));
        assertThat(input.closeCount).isEqualTo(1);
    }

    @Test
    void mismatchedIfRangeFallsBackToTheWholeLocalRepresentation()
            throws Exception {
        arrangeCurrentLocal();
        CloseTrackingInputStream input = new CloseTrackingInputStream(BYTES);
        when(storage.open(OBJECT_KEY, Optional.empty())).thenReturn(read(
                input,
                BYTES.length,
                Optional.empty(),
                BYTES.length,
                "image/png",
                VARIANT_SHA256));

        mvc.perform(get("/api/public/media/{id}/{variant}", ASSET_ID, VARIANT_NAME)
                        .header(HttpHeaders.RANGE, "bytes=1-2")
                        .header(HttpHeaders.IF_RANGE, '"' + "c".repeat(64) + '"'))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.CONTENT_RANGE))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, BYTES.length))
                .andExpect(content().bytes(BYTES));

        verify(storage).open(OBJECT_KEY, Optional.empty());
        assertThat(input.closeCount).isEqualTo(1);
    }

    @Test
    void matchingLocalIfNoneMatchReturns304OnlyAfterThePublicationGate()
            throws Exception {
        arrangeCurrentLocal();

        mvc.perform(get("/api/public/media/{id}/{variant}", ASSET_ID, VARIANT_NAME)
                        .header(HttpHeaders.IF_NONE_MATCH, STRONG_ETAG))
                .andExpect(status().isNotModified())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, LOCAL_CACHE_CONTROL))
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andExpect(header().string(HttpHeaders.ETAG, STRONG_ETAG))
                .andExpect(content().string(""));

        verify(references).isCurrentlyPublished(ASSET_ID, VARIANT_NAME);
        verify(media).requireReadyVariant(ASSET_ID, VARIANT_NAME);
        verify(storageRouter).require(StorageProvider.LOCAL);
        verify(storage).provider();
        verify(storage).location();
        verify(storage, never()).open(anyString(), any());
        verify(storage, never()).signedGet(anyString(), any());
    }

    @Test
    void matchingLocalIfNoneMatchDoesNotBypassTheLocationGate() throws Exception {
        arrangeCurrentLocal();
        when(storage.location()).thenReturn(new StorageLocation(
                StorageProvider.TENCENT_COS, COS_BUCKET, COS_REGION));

        mvc.perform(get("/api/public/media/{id}/{variant}", ASSET_ID, VARIANT_NAME)
                        .header(HttpHeaders.IF_NONE_MATCH, STRONG_ETAG))
                .andExpect(status().isInternalServerError());

        verify(storage, never()).open(anyString(), any());
    }

    @Test
    void publicationRemovalAfterCachedEtagReturns404InsteadOf304()
            throws Exception {
        when(references.isCurrentlyPublished(ASSET_ID, VARIANT_NAME))
                .thenReturn(true, false);
        MediaVariantDescriptor variant = localVariant();
        when(media.requireReadyVariant(ASSET_ID, VARIANT_NAME)).thenReturn(variant);
        lenient().when(storageRouter.require(StorageProvider.LOCAL)).thenReturn(storage);
        lenient().when(storage.provider()).thenReturn(StorageProvider.LOCAL);
        lenient().when(storage.location()).thenReturn(
                new StorageLocation(StorageProvider.LOCAL, null, null));

        mvc.perform(get("/api/public/media/{id}/{variant}", ASSET_ID, VARIANT_NAME)
                        .header(HttpHeaders.IF_NONE_MATCH, STRONG_ETAG))
                .andExpect(status().isNotModified());
        mvc.perform(get("/api/public/media/{id}/{variant}", ASSET_ID, VARIANT_NAME)
                        .header(HttpHeaders.IF_NONE_MATCH, STRONG_ETAG))
                .andExpect(status().isNotFound());

        verify(references, times(2)).isCurrentlyPublished(ASSET_ID, VARIANT_NAME);
        verify(media, times(1)).requireReadyVariant(ASSET_ID, VARIANT_NAME);
        verify(storage, never()).open(anyString(), any());
        verify(storage, never()).signedGet(anyString(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "bytes=0-1,2-3",
            "bytes=4-",
            "bytes=-0",
            "items=0-1"
    })
    void malformedMultipleAndUnsatisfiableLocalRangesReturn416WithoutARead(
            String range) throws Exception {
        arrangeCurrentLocal();

        mvc.perform(get("/api/public/media/{id}/{variant}", ASSET_ID, VARIANT_NAME)
                        .header(HttpHeaders.RANGE, range))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes */4"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, LOCAL_CACHE_CONTROL))
                .andExpect(content().string(""));

        verify(storage, never()).open(anyString(), any());
        verify(storage, never()).signedGet(anyString(), any());
    }

    @Test
    void storageLocationMismatchFailsBeforeReadOrRedirect()
            throws Exception {
        arrangeCurrentCos();
        when(storage.location()).thenReturn(new StorageLocation(
                StorageProvider.TENCENT_COS, COS_BUCKET, "ap-shanghai"));

        mvc.perform(get("/api/public/media/{id}/{variant}", ASSET_ID, VARIANT_NAME))
                .andExpect(status().isInternalServerError())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION));

        verify(storage, never()).open(anyString(), any());
        verify(storage, never()).signedGet(anyString(), any());
    }

    @Test
    void storageProviderMismatchFailsBeforeReadOrRedirect() throws Exception {
        arrangeCurrentCos();
        when(storage.provider()).thenReturn(StorageProvider.LOCAL);

        mvc.perform(get("/api/public/media/{id}/{variant}", ASSET_ID, VARIANT_NAME))
                .andExpect(status().isInternalServerError())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION));

        verify(storage, never()).open(anyString(), any());
        verify(storage, never()).signedGet(anyString(), any());
    }

    @Test
    void mismatchedStorageTotalLengthClosesThePreparedReadAndFailsClosed()
            throws Exception {
        arrangeCurrentLocal();
        CloseTrackingInputStream input = new CloseTrackingInputStream(
                new byte[] {1, 2, 3, 4, 5});
        when(storage.open(OBJECT_KEY, Optional.empty())).thenReturn(read(
                input,
                5,
                Optional.empty(),
                5,
                "image/png",
                VARIANT_SHA256));

        mvc.perform(get("/api/public/media/{id}/{variant}", ASSET_ID, VARIANT_NAME))
                .andExpect(status().isInternalServerError());

        assertThat(input.closeCount).isEqualTo(1);
    }

    @Test
    void mismatchedStorageMimeTypeClosesThePreparedReadAndFailsClosed()
            throws Exception {
        arrangeCurrentLocal();
        CloseTrackingInputStream input = new CloseTrackingInputStream(BYTES);
        when(storage.open(OBJECT_KEY, Optional.empty())).thenReturn(read(
                input,
                BYTES.length,
                Optional.empty(),
                BYTES.length,
                "image/jpeg",
                VARIANT_SHA256));

        mvc.perform(get("/api/public/media/{id}/{variant}", ASSET_ID, VARIANT_NAME))
                .andExpect(status().isInternalServerError());

        assertThat(input.closeCount).isEqualTo(1);
    }

    @Test
    void mismatchedLocalStorageEtagClosesThePreparedReadAndFailsClosed()
            throws Exception {
        arrangeCurrentLocal();
        CloseTrackingInputStream input = new CloseTrackingInputStream(BYTES);
        when(storage.open(OBJECT_KEY, Optional.empty())).thenReturn(read(
                input,
                BYTES.length,
                Optional.empty(),
                BYTES.length,
                "image/png",
                "d".repeat(64)));

        mvc.perform(get("/api/public/media/{id}/{variant}", ASSET_ID, VARIANT_NAME))
                .andExpect(status().isInternalServerError());

        assertThat(input.closeCount).isEqualTo(1);
    }

    @Test
    void shortStorageBodyFailsClosedAndClosesTheRead() throws Exception {
        arrangeCurrentLocal();
        CloseTrackingInputStream input = new CloseTrackingInputStream(new byte[] {1, 2});
        when(storage.open(OBJECT_KEY, Optional.empty())).thenReturn(read(
                input,
                BYTES.length,
                Optional.empty(),
                BYTES.length,
                "image/png",
                VARIANT_SHA256));

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThatThrownBy(() -> controller.read(
                        ASSET_ID, VARIANT_NAME, new HttpHeaders(), response))
                .isInstanceOf(StorageException.class);

        assertThat(response.getContentAsByteArray()).isEqualTo(new byte[] {1, 2});
        assertThat(input.closeCount).isEqualTo(1);
    }

    @Test
    void longStorageBodyIsNotServedPastDeclaredLengthAndClosesTheRead()
            throws Exception {
        arrangeCurrentLocal();
        CloseTrackingInputStream input = new CloseTrackingInputStream(
                new byte[] {1, 2, 3, 4, 5});
        when(storage.open(OBJECT_KEY, Optional.empty())).thenReturn(read(
                input,
                BYTES.length,
                Optional.empty(),
                BYTES.length,
                "image/png",
                VARIANT_SHA256));

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThatThrownBy(() -> controller.read(
                        ASSET_ID, VARIANT_NAME, new HttpHeaders(), response))
                .isInstanceOf(StorageException.class);

        assertThat(response.getContentAsByteArray()).isEqualTo(BYTES);
        assertThat(input.closeCount).isEqualTo(1);
    }

    @Test
    void inputFailureDuringStreamingStillClosesTheRead() throws Exception {
        arrangeCurrentLocal();
        FailingInputStream input = new FailingInputStream();
        when(storage.open(OBJECT_KEY, Optional.empty())).thenReturn(read(
                input,
                BYTES.length,
                Optional.empty(),
                BYTES.length,
                "image/png",
                VARIANT_SHA256));

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThatThrownBy(() -> controller.read(
                        ASSET_ID, VARIANT_NAME, new HttpHeaders(), response))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("synthetic public media read failure");

        assertThat(input.readAttempted).isTrue();
        assertThat(input.closeCount).isEqualTo(1);
    }

    @Test
    void outputPreparationFailureStillClosesTheReadExactlyOnce() throws Exception {
        arrangeCurrentLocal();
        CloseTrackingInputStream input = new CloseTrackingInputStream(BYTES);
        when(storage.open(OBJECT_KEY, Optional.empty())).thenReturn(read(
                input,
                BYTES.length,
                Optional.empty(),
                BYTES.length,
                "image/png",
                VARIANT_SHA256));

        assertThatThrownBy(() -> controller.read(
                        ASSET_ID,
                        VARIANT_NAME,
                        new HttpHeaders(),
                        new OutputFailingResponse()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("synthetic public media output failure");

        assertThat(input.closeCount).isEqualTo(1);
    }

    @Test
    void cosRejectsEveryRangeWithoutOpeningOrSigning() throws Exception {
        arrangeCurrentCos();

        mvc.perform(get("/api/public/media/{id}/{variant}", ASSET_ID, VARIANT_NAME)
                        .header(HttpHeaders.RANGE, "bytes=0-0"))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes */4"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().string(""));

        verify(storage, never()).open(anyString(), any());
        verify(storage, never()).signedGet(anyString(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"bytes=0-0", "bytes=0-1,2-3", "items=0-0"})
    void cosDocumentRejectsAnyRangeEvenWithMismatchedIfRange(
            String range) throws Exception {
        arrangeCurrentCosDocument();

        mvc.perform(get(
                                "/api/public/media/{id}/{variant}",
                                ASSET_ID,
                                DOCUMENT_VARIANT_NAME)
                        .header(HttpHeaders.RANGE, range)
                        .header(HttpHeaders.IF_RANGE, '"' + "c".repeat(64) + '"'))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(header().string(
                        HttpHeaders.CONTENT_RANGE, "bytes */" + PDF_BYTES.length))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().string(""));

        verify(storage, never()).open(anyString(), any());
        verify(storage, never()).signedGet(anyString(), any());
    }

    @Test
    void cosDocumentIgnoresMatchingIfNoneMatchAndIssuesANewRedirect()
            throws Exception {
        arrangeCurrentCosDocument();
        URI signed = officialCosDocumentUri();
        when(storage.signedGet(DOCUMENT_OBJECT_KEY, Duration.ofMinutes(5)))
                .thenReturn(signed);

        mvc.perform(get(
                                "/api/public/media/{id}/{variant}",
                                ASSET_ID,
                                DOCUMENT_VARIANT_NAME)
                        .header(HttpHeaders.IF_NONE_MATCH, STRONG_ETAG))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, signed.toString()))
                .andExpect(header().string(HttpHeaders.ETAG, STRONG_ETAG))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().string(""));

        verify(storage).signedGet(DOCUMENT_OBJECT_KEY, Duration.ofMinutes(5));
        verify(storage, never()).open(anyString(), any());
    }

    @Test
    void cosRedirectUsesOnlyAnOfficialFiveMinuteSignedUrlAndNoStore()
            throws Exception {
        arrangeCurrentCos();
        URI signed = officialCosUri();
        when(storage.signedGet(OBJECT_KEY, Duration.ofMinutes(5))).thenReturn(signed);

        mvc.perform(get("/api/public/media/{id}/{variant}", ASSET_ID, VARIANT_NAME))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, signed.toString()))
                .andExpect(header().string(HttpHeaders.ETAG, STRONG_ETAG))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().string(""));

        verify(storage).signedGet(OBJECT_KEY, Duration.ofMinutes(5));
        verify(storage, never()).open(anyString(), any());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidOfficialCosUris")
    void cosRejectsInvalidOfficialUriWithoutLeakingIt(
            String caseName, URI malicious) throws Exception {
        arrangeCurrentCos();
        when(storage.signedGet(OBJECT_KEY, Duration.ofMinutes(5))).thenReturn(malicious);

        MvcResult result = mvc.perform(
                        get("/api/public/media/{id}/{variant}", ASSET_ID, VARIANT_NAME))
                .andExpect(status().isInternalServerError())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(
                        malicious.toASCIIString(),
                        OBJECT_KEY,
                        COS_BUCKET,
                        COS_REGION,
                        "q-sign-algorithm");
        verify(storage).signedGet(OBJECT_KEY, Duration.ofMinutes(5));
    }

    private static Stream<Arguments> invalidOfficialCosUris() {
        String host = COS_BUCKET + ".cos." + COS_REGION + ".myqcloud.com";
        String path = '/' + OBJECT_KEY;
        String query = "?q-sign-algorithm=sha1";
        return Stream.of(
                Arguments.of("http", URI.create("http://" + host + path + query)),
                Arguments.of(
                        "userinfo",
                        URI.create("https://attacker@" + host + path + query)),
                Arguments.of(
                        "non-default port",
                        URI.create("https://" + host + ":8443" + path + query)),
                Arguments.of(
                        "fragment",
                        URI.create("https://" + host + path + query + "#secret")),
                Arguments.of(
                        "wrong path",
                        URI.create("https://" + host + "/media/wrong.png" + query)),
                Arguments.of(
                        "missing query",
                        URI.create("https://" + host + path)),
                Arguments.of(
                        "empty query",
                        URI.create("https://" + host + path + '?')),
                Arguments.of(
                        "wrong bucket",
                        URI.create("https://other-bucket.cos." + COS_REGION
                                + ".myqcloud.com" + path + query)),
                Arguments.of(
                        "wrong region",
                        URI.create("https://" + COS_BUCKET
                                + ".cos.ap-shanghai.myqcloud.com" + path + query)));
    }

    private MediaVariantDescriptor arrangeCurrentLocal() {
        MediaVariantDescriptor variant = localVariant();
        arrangeCurrent(variant, new StorageLocation(StorageProvider.LOCAL, null, null));
        return variant;
    }

    private MediaVariantDescriptor arrangeCurrentCos() {
        MediaVariantDescriptor variant = cosVariant();
        arrangeCurrent(variant, new StorageLocation(
                StorageProvider.TENCENT_COS, COS_BUCKET, COS_REGION));
        return variant;
    }

    private MediaVariantDescriptor arrangeCurrentCosDocument() {
        MediaVariantDescriptor variant = cosDocumentVariant();
        arrangeCurrent(variant, new StorageLocation(
                StorageProvider.TENCENT_COS, COS_BUCKET, COS_REGION));
        return variant;
    }

    private void arrangeCurrent(
            MediaVariantDescriptor variant, StorageLocation location) {
        when(references.isCurrentlyPublished(variant.assetId(), variant.variantName()))
                .thenReturn(true);
        when(media.requireReadyVariant(variant.assetId(), variant.variantName()))
                .thenReturn(variant);
        lenient().when(storageRouter.require(variant.provider())).thenReturn(storage);
        lenient().when(storage.provider()).thenReturn(variant.provider());
        lenient().when(storage.location()).thenReturn(location);
    }

    private static MediaVariantDescriptor localVariant() {
        return variant(StorageProvider.LOCAL, null, null);
    }

    private static MediaVariantDescriptor cosVariant() {
        return variant(StorageProvider.TENCENT_COS, COS_BUCKET, COS_REGION);
    }

    private static MediaVariantDescriptor localDocumentVariant() {
        return documentVariant(StorageProvider.LOCAL, null, null);
    }

    private static MediaVariantDescriptor cosDocumentVariant() {
        return documentVariant(StorageProvider.TENCENT_COS, COS_BUCKET, COS_REGION);
    }

    private static MediaVariantDescriptor variant(
            StorageProvider provider, String bucket, String region) {
        return new MediaVariantDescriptor(
                ASSET_ID,
                VARIANT_NAME,
                "READY",
                provider,
                bucket,
                region,
                OBJECT_KEY,
                "image/png",
                BYTES.length,
                VARIANT_SHA256,
                640,
                360);
    }

    private static MediaVariantDescriptor documentVariant(
            StorageProvider provider, String bucket, String region) {
        return new MediaVariantDescriptor(
                ASSET_ID,
                DOCUMENT_VARIANT_NAME,
                "READY",
                provider,
                bucket,
                region,
                DOCUMENT_OBJECT_KEY,
                MediaType.APPLICATION_PDF_VALUE,
                PDF_BYTES.length,
                VARIANT_SHA256,
                0,
                0);
    }

    private static StorageRead read(
            InputStream input,
            long totalLength,
            Optional<ByteRange> range,
            long contentLength,
            String contentType,
            String etag) {
        return new StorageRead(
                input, totalLength, range, contentLength, contentType, etag);
    }

    private static URI officialCosUri() {
        return URI.create("https://" + COS_BUCKET + ".cos." + COS_REGION
                + ".myqcloud.com/" + OBJECT_KEY + "?q-sign-algorithm=sha1");
    }

    private static URI officialCosDocumentUri() {
        return URI.create("https://" + COS_BUCKET + ".cos." + COS_REGION
                + ".myqcloud.com/" + DOCUMENT_OBJECT_KEY
                + "?q-sign-algorithm=sha1");
    }

    private static final class OutputFailingResponse extends HttpServletResponseWrapper {
        private OutputFailingResponse() {
            super(new MockHttpServletResponse());
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            throw new IOException("synthetic public media output failure");
        }
    }

    private static final class CloseTrackingInputStream extends ByteArrayInputStream {
        private int closeCount;

        private CloseTrackingInputStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws IOException {
            closeCount++;
            super.close();
        }
    }

    private static final class FailingInputStream extends InputStream {
        private boolean readAttempted;
        private int closeCount;

        @Override
        public int read() throws IOException {
            readAttempted = true;
            throw new IOException("synthetic public media read failure");
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            readAttempted = true;
            throw new IOException("synthetic public media read failure");
        }

        @Override
        public void close() {
            closeCount++;
        }
    }
}
