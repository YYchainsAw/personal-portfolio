package xyz.yychainsaw.portfolio.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.media.domain.MediaStatus;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRecord;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRepository;
import xyz.yychainsaw.portfolio.media.persistence.MediaVariantRecord;
import xyz.yychainsaw.portfolio.media.persistence.MediaVariantRepository;
import xyz.yychainsaw.portfolio.media.storage.ByteRange;
import xyz.yychainsaw.portfolio.media.storage.StorageException;
import xyz.yychainsaw.portfolio.media.storage.StorageLocation;
import xyz.yychainsaw.portfolio.media.storage.StorageRead;
import xyz.yychainsaw.portfolio.media.storage.StorageRouter;
import xyz.yychainsaw.portfolio.media.storage.StorageService;

@ExtendWith(MockitoExtension.class)
class AdminMediaPreviewServiceTest {
    private static final UUID ASSET_ID = UUID.fromString(
            "31000000-0000-4000-8000-000000000001");
    private static final UUID VARIANT_ID = UUID.fromString(
            "32000000-0000-4000-8000-000000000001");
    private static final Instant CREATED = Instant.parse("2026-07-17T00:00:00Z");
    private static final String ASSET_SHA = "a".repeat(64);
    private static final String VARIANT_SHA = "b".repeat(64);
    private static final String STRONG_ETAG = '"' + VARIANT_SHA + '"';
    private static final byte[] IMAGE_BYTES = new byte[] {1, 2, 3, 4};
    private static final String COS_BUCKET = "portfolio-1234567890";
    private static final String COS_REGION = "ap-guangzhou";

    @Mock
    private MediaAssetRepository assets;

    @Mock
    private MediaVariantRepository variants;

    @Mock
    private StorageRouter storageRouter;

    @Mock
    private StorageService storage;

    private AdminMediaPreviewService service;

    @BeforeEach
    void setUp() {
        service = new AdminMediaPreviewService(assets, variants, storageRouter);
    }

    @Test
    void localFullPreviewUsesDatabaseStrongEtagAndClosesAfterStreaming() throws Exception {
        MediaAssetRecord asset = imageAsset(StorageProvider.LOCAL, MediaStatus.READY);
        MediaVariantRecord variant = imageVariant("READY");
        CloseTrackingInputStream input = new CloseTrackingInputStream(IMAGE_BYTES);
        arrange(asset, variant, new StorageLocation(StorageProvider.LOCAL, null, null));
        when(storage.open(variant.objectKey(), Optional.empty())).thenReturn(new StorageRead(
                input,
                IMAGE_BYTES.length,
                Optional.empty(),
                IMAGE_BYTES.length,
                "image/jpeg",
                VARIANT_SHA));

        ResponseEntity<StreamingResponseBody> response =
                service.preview(ASSET_ID, "w2", new HttpHeaders());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertCommonStreamHeaders(
                response.getHeaders(), IMAGE_BYTES.length, "image/jpeg", STRONG_ETAG);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isNull();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        response.getBody().writeTo(output);
        assertThat(output.toByteArray()).containsExactly(IMAGE_BYTES);
        assertThat(input.closed).isTrue();
    }

    @Test
    void unconsumedPreviewBodyOwnsAndClosesTheOpenedStorageRead() throws Exception {
        MediaAssetRecord asset = imageAsset(StorageProvider.LOCAL, MediaStatus.READY);
        MediaVariantRecord variant = imageVariant("READY");
        CloseTrackingInputStream input = new CloseTrackingInputStream(IMAGE_BYTES);
        arrange(asset, variant, new StorageLocation(StorageProvider.LOCAL, null, null));
        when(storage.open(variant.objectKey(), Optional.empty())).thenReturn(new StorageRead(
                input,
                IMAGE_BYTES.length,
                Optional.empty(),
                IMAGE_BYTES.length,
                "image/jpeg",
                VARIANT_SHA));

        StreamingResponseBody body = service
                .preview(ASSET_ID, "w2", new HttpHeaders())
                .getBody();

        assertThat(body).isInstanceOf(AutoCloseable.class);
        ((AutoCloseable) body).close();
        assertThat(input.closed).isTrue();
        assertThatThrownBy(() -> body.writeTo(new ByteArrayOutputStream()))
                .isInstanceOf(IOException.class)
                .hasMessage("media preview body is unavailable")
                .hasNoCause();
    }

    @Test
    void localRangePreviewReturns206WithExactContentRange() throws Exception {
        MediaAssetRecord asset = imageAsset(StorageProvider.LOCAL, MediaStatus.READY);
        MediaVariantRecord variant = imageVariant("READY");
        ByteRange requested = new ByteRange(1, 2);
        CloseTrackingInputStream input = new CloseTrackingInputStream(new byte[] {2, 3});
        arrange(asset, variant, new StorageLocation(StorageProvider.LOCAL, null, null));
        when(storage.open(variant.objectKey(), Optional.of(requested))).thenReturn(new StorageRead(
                input,
                IMAGE_BYTES.length,
                Optional.of(requested),
                requested.length(),
                "image/jpeg",
                VARIANT_SHA));
        HttpHeaders request = new HttpHeaders();
        request.set(HttpHeaders.RANGE, "bytes=1-2");

        ResponseEntity<StreamingResponseBody> response =
                service.preview(ASSET_ID, "w2", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertCommonStreamHeaders(response.getHeaders(), 2, "image/jpeg", STRONG_ETAG);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE))
                .isEqualTo("bytes 1-2/4");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        response.getBody().writeTo(output);
        assertThat(output.toByteArray()).containsExactly(2, 3);
        assertThat(input.closed).isTrue();
    }

    @Test
    void ifRangeMismatchFallsBackToWholeLocalRepresentation() {
        MediaAssetRecord asset = imageAsset(StorageProvider.LOCAL, MediaStatus.READY);
        MediaVariantRecord variant = imageVariant("READY");
        arrange(asset, variant, new StorageLocation(StorageProvider.LOCAL, null, null));
        when(storage.open(variant.objectKey(), Optional.empty())).thenReturn(new StorageRead(
                new ByteArrayInputStream(IMAGE_BYTES),
                IMAGE_BYTES.length,
                Optional.empty(),
                IMAGE_BYTES.length,
                "image/jpeg",
                VARIANT_SHA));
        HttpHeaders request = new HttpHeaders();
        request.set(HttpHeaders.RANGE, "bytes=1-2");
        request.set(HttpHeaders.IF_RANGE, '"' + "c".repeat(64) + '"');

        ResponseEntity<StreamingResponseBody> response =
                service.preview(ASSET_ID, "w2", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentLength()).isEqualTo(IMAGE_BYTES.length);
        verify(storage).open(variant.objectKey(), Optional.empty());
    }

    @Test
    void matchingIfNoneMatchReturns304WithoutResolvingStorage() {
        MediaAssetRecord asset = imageAsset(StorageProvider.LOCAL, MediaStatus.READY);
        MediaVariantRecord variant = imageVariant("READY");
        when(assets.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(variants.findByAssetAndName(ASSET_ID, "w2")).thenReturn(Optional.of(variant));
        HttpHeaders request = new HttpHeaders();
        request.set(HttpHeaders.IF_NONE_MATCH, "W/" + STRONG_ETAG);

        ResponseEntity<StreamingResponseBody> response =
                service.preview(ASSET_ID, "w2", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(response.getHeaders().getETag()).isEqualTo(STRONG_ETAG);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getBody()).isNull();
        verify(storageRouter, never()).require(StorageProvider.LOCAL);
    }

    @Test
    void cosImageRedirectUsesExactLocationAndFiveMinuteSignedUrl() {
        MediaAssetRecord asset = imageAsset(StorageProvider.TENCENT_COS, MediaStatus.ARCHIVED);
        MediaVariantRecord variant = imageVariant("READY");
        URI signed = URI.create(
                "https://portfolio-1234567890.cos.ap-guangzhou.myqcloud.com/"
                        + variant.objectKey() + "?q-sign-algorithm=sha1");
        arrange(asset, variant, new StorageLocation(
                StorageProvider.TENCENT_COS, COS_BUCKET, COS_REGION));
        when(storage.signedGet(variant.objectKey(), Duration.ofMinutes(5))).thenReturn(signed);

        ResponseEntity<StreamingResponseBody> response =
                service.preview(ASSET_ID, "w2", new HttpHeaders());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().getLocation()).isEqualTo(signed);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getHeaders().getETag()).isEqualTo(STRONG_ETAG);
        assertThat(response.getBody()).isNull();
        verify(storage).signedGet(variant.objectKey(), Duration.ofMinutes(5));
        verify(storage, never()).open(eq(variant.objectKey()), eq(Optional.empty()));
    }

    @Test
    void cosImageRejectsAnUnsignedOfficialObjectUrl() {
        MediaAssetRecord asset = imageAsset(
                StorageProvider.TENCENT_COS, MediaStatus.READY);
        MediaVariantRecord variant = imageVariant("READY");
        arrange(asset, variant, new StorageLocation(
                StorageProvider.TENCENT_COS, COS_BUCKET, COS_REGION));
        URI unsigned = URI.create(
                "https://portfolio-1234567890.cos.ap-guangzhou.myqcloud.com/"
                        + variant.objectKey());
        when(storage.signedGet(variant.objectKey(), Duration.ofMinutes(5)))
                .thenReturn(unsigned);

        assertStorageMismatch(
                () -> service.preview(ASSET_ID, "w2", new HttpHeaders()));
    }

    @Test
    void cosImageRejectsEveryRangeWithoutOpeningOrSigning() {
        MediaAssetRecord asset = imageAsset(StorageProvider.TENCENT_COS, MediaStatus.READY);
        MediaVariantRecord variant = imageVariant("READY");
        arrange(asset, variant, new StorageLocation(
                StorageProvider.TENCENT_COS, COS_BUCKET, COS_REGION));
        HttpHeaders request = new HttpHeaders();
        request.set(HttpHeaders.RANGE, "bytes=0-0");

        assertThatThrownBy(() -> service.preview(ASSET_ID, "w2", request))
                .isInstanceOfSatisfying(MediaRangeNotSatisfiableException.class, failure ->
                        assertThat(failure.totalLength()).isEqualTo(IMAGE_BYTES.length));
        verify(storage, never()).signedGet(eq(variant.objectKey()), eq(Duration.ofMinutes(5)));
        verify(storage, never()).open(eq(variant.objectKey()), eq(Optional.empty()));
    }

    @Test
    void cosPdfStreamsWithAttachmentNosniffAndOpaqueProviderEtag() throws Exception {
        byte[] pdf = "%PDF-2.0\n%%EOF\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        String pdfSha = "d".repeat(64);
        MediaAssetRecord asset = pdfAsset(StorageProvider.TENCENT_COS, MediaStatus.ARCHIVED, pdf.length, pdfSha);
        MediaVariantRecord variant = pdfVariant("READY", pdf.length, pdfSha);
        CloseTrackingInputStream input = new CloseTrackingInputStream(pdf);
        arrange(asset, variant, new StorageLocation(
                StorageProvider.TENCENT_COS, COS_BUCKET, COS_REGION));
        when(storage.open(variant.objectKey(), Optional.empty())).thenReturn(new StorageRead(
                input,
                pdf.length,
                Optional.empty(),
                pdf.length,
                "application/pdf",
                "cos-provider-etag"));

        ResponseEntity<StreamingResponseBody> response =
                service.preview(ASSET_ID, "document", new HttpHeaders());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertCommonStreamHeaders(
                response.getHeaders(), pdf.length, "application/pdf", '"' + pdfSha + '"');
        assertThat(response.getHeaders().getContentDisposition().getType())
                .isEqualTo("attachment");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options"))
                .isEqualTo("nosniff");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        response.getBody().writeTo(output);
        assertThat(output.toByteArray()).containsExactly(pdf);
        assertThat(input.closed).isTrue();
        verify(storage, never()).signedGet(eq(variant.objectKey()), eq(Duration.ofMinutes(5)));
    }

    @Test
    void mismatchedStorageLocationFailsBeforeOpeningOrSigning() {
        MediaAssetRecord asset = imageAsset(StorageProvider.TENCENT_COS, MediaStatus.READY);
        MediaVariantRecord variant = imageVariant("READY");
        arrange(asset, variant, new StorageLocation(
                StorageProvider.TENCENT_COS, COS_BUCKET, "ap-shanghai"));

        assertStorageMismatch(() -> service.preview(ASSET_ID, "w2", new HttpHeaders()));
        verify(storage, never()).signedGet(eq(variant.objectKey()), eq(Duration.ofMinutes(5)));
        verify(storage, never()).open(eq(variant.objectKey()), eq(Optional.empty()));
    }

    @Test
    void mismatchedStorageProviderFailsBeforeOpening() {
        MediaAssetRecord asset = imageAsset(StorageProvider.LOCAL, MediaStatus.READY);
        MediaVariantRecord variant = imageVariant("READY");
        arrange(asset, variant, new StorageLocation(StorageProvider.LOCAL, null, null));
        when(storage.provider()).thenReturn(StorageProvider.TENCENT_COS);

        assertStorageMismatch(() -> service.preview(ASSET_ID, "w2", new HttpHeaders()));
        verify(storage, never()).open(eq(variant.objectKey()), eq(Optional.empty()));
    }

    @Test
    void mismatchedLocalProviderEtagClosesReadBeforeFailing() {
        MediaAssetRecord asset = imageAsset(StorageProvider.LOCAL, MediaStatus.READY);
        MediaVariantRecord variant = imageVariant("READY");
        CloseTrackingInputStream input = new CloseTrackingInputStream(IMAGE_BYTES);
        arrange(asset, variant, new StorageLocation(StorageProvider.LOCAL, null, null));
        when(storage.open(variant.objectKey(), Optional.empty())).thenReturn(new StorageRead(
                input,
                IMAGE_BYTES.length,
                Optional.empty(),
                IMAGE_BYTES.length,
                "image/jpeg",
                "e".repeat(64)));

        assertStorageMismatch(() -> service.preview(ASSET_ID, "w2", new HttpHeaders()));
        assertThat(input.closed).isTrue();
    }

    @Test
    void mismatchedStorageLengthRangeOrMimeClosesEveryRead() {
        MediaAssetRecord asset = imageAsset(StorageProvider.LOCAL, MediaStatus.READY);
        MediaVariantRecord variant = imageVariant("READY");
        arrange(asset, variant, new StorageLocation(StorageProvider.LOCAL, null, null));
        CloseTrackingInputStream wrongTotal = new CloseTrackingInputStream(new byte[5]);
        CloseTrackingInputStream wrongRange = new CloseTrackingInputStream(new byte[] {1, 2});
        CloseTrackingInputStream wrongMime = new CloseTrackingInputStream(IMAGE_BYTES);
        when(storage.open(variant.objectKey(), Optional.empty()))
                .thenReturn(new StorageRead(
                        wrongTotal, 5, Optional.empty(), 5, "image/jpeg", VARIANT_SHA))
                .thenReturn(new StorageRead(
                        wrongRange,
                        IMAGE_BYTES.length,
                        Optional.of(new ByteRange(0, 1)),
                        2,
                        "image/jpeg",
                        VARIANT_SHA))
                .thenReturn(new StorageRead(
                        wrongMime,
                        IMAGE_BYTES.length,
                        Optional.empty(),
                        IMAGE_BYTES.length,
                        "image/png",
                        VARIANT_SHA));

        assertStorageMismatch(() -> service.preview(ASSET_ID, "w2", new HttpHeaders()));
        assertStorageMismatch(() -> service.preview(ASSET_ID, "w2", new HttpHeaders()));
        assertStorageMismatch(() -> service.preview(ASSET_ID, "w2", new HttpHeaders()));

        assertThat(wrongTotal.closed).isTrue();
        assertThat(wrongRange.closed).isTrue();
        assertThat(wrongMime.closed).isTrue();
    }

    @Test
    void shortOrLongStorageBodyFailsClosedAndClosesInput() {
        MediaAssetRecord asset = imageAsset(StorageProvider.LOCAL, MediaStatus.READY);
        MediaVariantRecord variant = imageVariant("READY");
        arrange(asset, variant, new StorageLocation(StorageProvider.LOCAL, null, null));
        CloseTrackingInputStream shortInput = new CloseTrackingInputStream(new byte[] {1, 2});
        CloseTrackingInputStream longInput = new CloseTrackingInputStream(new byte[] {1, 2, 3, 4, 5});
        when(storage.open(variant.objectKey(), Optional.empty()))
                .thenReturn(new StorageRead(
                        shortInput,
                        IMAGE_BYTES.length,
                        Optional.empty(),
                        IMAGE_BYTES.length,
                        "image/jpeg",
                        VARIANT_SHA))
                .thenReturn(new StorageRead(
                        longInput,
                        IMAGE_BYTES.length,
                        Optional.empty(),
                        IMAGE_BYTES.length,
                        "image/jpeg",
                        VARIANT_SHA));

        ResponseEntity<StreamingResponseBody> shortResponse =
                service.preview(ASSET_ID, "w2", new HttpHeaders());
        assertStorageMismatch(() -> write(shortResponse, new ByteArrayOutputStream()));
        ResponseEntity<StreamingResponseBody> longResponse =
                service.preview(ASSET_ID, "w2", new HttpHeaders());
        assertStorageMismatch(() -> write(longResponse, new ByteArrayOutputStream()));

        assertThat(shortInput.closed).isTrue();
        assertThat(longInput.closed).isTrue();
    }

    @Test
    void outputFailureStillClosesStorageRead() {
        MediaAssetRecord asset = imageAsset(StorageProvider.LOCAL, MediaStatus.READY);
        MediaVariantRecord variant = imageVariant("READY");
        CloseTrackingInputStream input = new CloseTrackingInputStream(IMAGE_BYTES);
        arrange(asset, variant, new StorageLocation(StorageProvider.LOCAL, null, null));
        when(storage.open(variant.objectKey(), Optional.empty())).thenReturn(new StorageRead(
                input,
                IMAGE_BYTES.length,
                Optional.empty(),
                IMAGE_BYTES.length,
                "image/jpeg",
                VARIANT_SHA));
        ResponseEntity<StreamingResponseBody> response =
                service.preview(ASSET_ID, "w2", new HttpHeaders());

        assertThatThrownBy(() -> write(response, new FailingOutputStream()))
                .isInstanceOf(IOException.class)
                .hasMessage("client disconnected");
        assertThat(input.closed).isTrue();
    }

    @Test
    void missingOrPendingDeleteAssetIsHiddenAsNotFound() {
        when(assets.findById(ASSET_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(imageAsset(
                        StorageProvider.LOCAL, MediaStatus.PENDING_DELETE)));

        assertDomainFailure(() -> service.preview(ASSET_ID, "w2", new HttpHeaders()),
                "MEDIA_NOT_FOUND", HttpStatus.NOT_FOUND);
        assertDomainFailure(() -> service.preview(ASSET_ID, "w2", new HttpHeaders()),
                "MEDIA_NOT_FOUND", HttpStatus.NOT_FOUND);
        verify(variants, never()).findByAssetAndName(ASSET_ID, "w2");
    }

    @Test
    void missingVariantIsNotFoundAndNonReadyVariantIsNotReady() {
        MediaAssetRecord asset = imageAsset(StorageProvider.LOCAL, MediaStatus.ARCHIVED);
        when(assets.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(variants.findByAssetAndName(ASSET_ID, "w2"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(imageVariant("FAILED")));

        assertDomainFailure(() -> service.preview(ASSET_ID, "w2", new HttpHeaders()),
                "MEDIA_NOT_FOUND", HttpStatus.NOT_FOUND);
        assertDomainFailure(() -> service.preview(ASSET_ID, "w2", new HttpHeaders()),
                "MEDIA_NOT_READY", HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void nullIdentityAndBlankVariantAreHiddenWithoutRepositoryCalls() {
        assertDomainFailure(() -> service.preview(null, "w2", new HttpHeaders()),
                "MEDIA_NOT_FOUND", HttpStatus.NOT_FOUND);
        assertDomainFailure(() -> service.preview(ASSET_ID, " ", new HttpHeaders()),
                "MEDIA_NOT_FOUND", HttpStatus.NOT_FOUND);

        verify(assets, never()).findById(ASSET_ID);
        verify(variants, never()).findByAssetAndName(ASSET_ID, " ");
    }

    private void arrange(
            MediaAssetRecord asset,
            MediaVariantRecord variant,
            StorageLocation location) {
        when(assets.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(variants.findByAssetAndName(ASSET_ID, variant.variantName()))
                .thenReturn(Optional.of(variant));
        when(storageRouter.require(asset.provider())).thenReturn(storage);
        when(storage.provider()).thenReturn(asset.provider());
        when(storage.location()).thenReturn(location);
    }

    private static MediaAssetRecord imageAsset(
            StorageProvider provider, MediaStatus status) {
        String bucket = provider == StorageProvider.LOCAL ? null : COS_BUCKET;
        String region = provider == StorageProvider.LOCAL ? null : COS_REGION;
        Instant archivedAt = status == MediaStatus.ARCHIVED
                        || status == MediaStatus.PENDING_DELETE
                ? CREATED
                : null;
        return new MediaAssetRecord(
                ASSET_ID,
                provider,
                bucket,
                region,
                MediaObjectKeys.originalKey(ASSET_ID, ASSET_SHA, "image/jpeg"),
                "work.jpg",
                "image/jpeg",
                10,
                10,
                10,
                ASSET_SHA,
                status,
                archivedAt,
                1,
                CREATED,
                CREATED);
    }

    private static MediaAssetRecord pdfAsset(
            StorageProvider provider,
            MediaStatus status,
            long byteSize,
            String sha256) {
        String bucket = provider == StorageProvider.LOCAL ? null : COS_BUCKET;
        String region = provider == StorageProvider.LOCAL ? null : COS_REGION;
        Instant archivedAt = status == MediaStatus.ARCHIVED
                        || status == MediaStatus.PENDING_DELETE
                ? CREATED
                : null;
        return new MediaAssetRecord(
                ASSET_ID,
                provider,
                bucket,
                region,
                MediaObjectKeys.originalKey(ASSET_ID, sha256, "application/pdf"),
                "design.pdf",
                "application/pdf",
                byteSize,
                null,
                null,
                sha256,
                status,
                archivedAt,
                1,
                CREATED,
                CREATED);
    }

    private static MediaVariantRecord imageVariant(String status) {
        return new MediaVariantRecord(
                VARIANT_ID,
                ASSET_ID,
                "w2",
                "JPEG",
                MediaObjectKeys.variantKey(
                        ASSET_ID, "w2", VARIANT_SHA, "image/jpeg"),
                "image/jpeg",
                IMAGE_BYTES.length,
                2,
                1,
                VARIANT_SHA,
                status,
                CREATED);
    }

    private static MediaVariantRecord pdfVariant(
            String status, long byteSize, String sha256) {
        return new MediaVariantRecord(
                VARIANT_ID,
                ASSET_ID,
                "document",
                "PDF",
                MediaObjectKeys.originalKey(ASSET_ID, sha256, "application/pdf"),
                "application/pdf",
                byteSize,
                null,
                null,
                sha256,
                status,
                CREATED);
    }

    private static void assertCommonStreamHeaders(
            HttpHeaders headers,
            long contentLength,
            String contentType,
            String expectedEtag) {
        assertThat(headers.getCacheControl()).isEqualTo("no-store");
        assertThat(headers.getETag()).isEqualTo(expectedEtag);
        assertThat(headers.getFirst(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
        assertThat(headers.getContentLength()).isEqualTo(contentLength);
        assertThat(headers.getContentType()).hasToString(contentType);
    }

    private static void assertDomainFailure(
            ThrowingInvocation invocation, String code, HttpStatus status) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(DomainException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(code);
                    assertThat(failure.status()).isEqualTo(status);
                    assertThat(failure.fieldErrors()).isEqualTo(Map.of());
                });
    }

    private static void assertStorageMismatch(ThrowingInvocation invocation) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(StorageException.class, failure ->
                        assertThat(failure.code())
                                .isEqualTo("MEDIA_PREVIEW_STORAGE_MISMATCH"));
    }

    private static void write(
            ResponseEntity<StreamingResponseBody> response, OutputStream output)
            throws Exception {
        response.getBody().writeTo(output);
    }

    @FunctionalInterface
    private interface ThrowingInvocation {
        void run() throws Exception;
    }

    private static final class CloseTrackingInputStream extends ByteArrayInputStream {
        private boolean closed;

        private CloseTrackingInputStream(byte[] input) {
            super(input);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }

    private static final class FailingOutputStream extends OutputStream {
        @Override
        public void write(int value) throws IOException {
            throw new IOException("client disconnected");
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            throw new IOException("client disconnected");
        }
    }
}
