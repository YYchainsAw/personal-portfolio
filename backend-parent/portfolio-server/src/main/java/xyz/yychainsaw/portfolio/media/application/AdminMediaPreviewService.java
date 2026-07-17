package xyz.yychainsaw.portfolio.media.application;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
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
import xyz.yychainsaw.portfolio.media.storage.StorageRangeNotSatisfiableException;
import xyz.yychainsaw.portfolio.media.storage.StorageRead;
import xyz.yychainsaw.portfolio.media.storage.StorageRouter;
import xyz.yychainsaw.portfolio.media.storage.StorageService;

@Service
@ConditionalOnWebApplication(type = Type.SERVLET)
public final class AdminMediaPreviewService {
    private static final Duration SIGNED_GET_TTL = Duration.ofMinutes(5);
    private static final String STORAGE_MISMATCH = "MEDIA_PREVIEW_STORAGE_MISMATCH";
    private static final int COPY_BUFFER_SIZE = 64 * 1024;

    private final MediaAssetRepository assets;
    private final MediaVariantRepository variants;
    private final StorageRouter storageRouter;

    public AdminMediaPreviewService(
            MediaAssetRepository assets,
            MediaVariantRepository variants,
            StorageRouter storageRouter) {
        this.assets = Objects.requireNonNull(assets, "media repository is required");
        this.variants = Objects.requireNonNull(
                variants, "media variant repository is required");
        this.storageRouter = Objects.requireNonNull(
                storageRouter, "storage router is required");
    }

    public ResponseEntity<StreamingResponseBody> preview(
            UUID assetId, String variantName, HttpHeaders requestHeaders) {
        if (assetId == null || variantName == null || variantName.isBlank()) {
            throw notFound();
        }
        Objects.requireNonNull(requestHeaders, "request headers are required");

        MediaAssetRecord asset = assets.findById(assetId)
                .orElseThrow(AdminMediaPreviewService::notFound);
        if (asset.status() == MediaStatus.PENDING_DELETE) {
            throw notFound();
        }
        MediaVariantRecord variant = variants.findByAssetAndName(assetId, variantName)
                .orElseThrow(AdminMediaPreviewService::notFound);
        if (!"READY".equals(variant.status())) {
            throw notReady();
        }

        String strongEtag = '"' + variant.sha256() + '"';
        if (ifNoneMatchMatches(requestHeaders, strongEtag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .headers(baseHeaders(strongEtag))
                    .build();
        }

        MediaPreviewRange selectedRange = MediaPreviewRange.parse(
                requestHeaders, variant.byteSize(), variant.sha256());
        StorageService storage = storageRouter.require(asset.provider());
        requireExactLocation(asset, storage);

        if (asset.provider() == StorageProvider.TENCENT_COS
                && !"application/pdf".equals(variant.mimeType())) {
            if (selectedRange.rangeHeaderPresent()) {
                throw new MediaRangeNotSatisfiableException(variant.byteSize());
            }
            URI signed = storage.signedGet(variant.objectKey(), SIGNED_GET_TTL);
            requireOfficialCosUri(asset, variant, signed);
            HttpHeaders responseHeaders = baseHeaders(strongEtag);
            responseHeaders.setLocation(signed);
            return ResponseEntity.status(HttpStatus.FOUND)
                    .headers(responseHeaders)
                    .build();
        }

        return stream(asset, variant, selectedRange, storage);
    }

    private static ResponseEntity<StreamingResponseBody> stream(
            MediaAssetRecord asset,
            MediaVariantRecord variant,
            MediaPreviewRange selectedRange,
            StorageService storage) {
        Optional<ByteRange> requestedRange = selectedRange.byteRange();
        StorageRead read;
        try {
            read = storage.open(variant.objectKey(), requestedRange);
        } catch (StorageRangeNotSatisfiableException inconsistentStorage) {
            throw storageMismatch(inconsistentStorage);
        }
        try {
            requireExactRead(asset, variant, requestedRange, read);

            HttpHeaders responseHeaders = baseHeaders(selectedRange.strongEtag());
            responseHeaders.set(HttpHeaders.ACCEPT_RANGES, "bytes");
            responseHeaders.setContentType(MediaType.parseMediaType(variant.mimeType()));
            responseHeaders.setContentLength(read.contentLength());
            requestedRange.ifPresent(range -> responseHeaders.set(
                    HttpHeaders.CONTENT_RANGE,
                    "bytes " + range.startInclusive() + '-' + range.endInclusive()
                            + '/' + variant.byteSize()));
            responseHeaders.set("X-Content-Type-Options", "nosniff");
            if ("application/pdf".equals(variant.mimeType())) {
                responseHeaders.setContentDisposition(
                        ContentDisposition.attachment().filename("document.pdf").build());
            }

            StreamingResponseBody body = new StorageStreamingBody(read);
            HttpStatus status = requestedRange.isPresent()
                    ? HttpStatus.PARTIAL_CONTENT
                    : HttpStatus.OK;
            return ResponseEntity.status(status).headers(responseHeaders).body(body);
        } catch (RuntimeException failure) {
            closeAfterPreparationFailure(read, failure);
            throw failure;
        } catch (Error failure) {
            closeAfterPreparationFailure(read, failure);
            throw failure;
        }
    }

    private static void requireExactLocation(
            MediaAssetRecord asset, StorageService storage) {
        StorageLocation expected = new StorageLocation(
                asset.provider(), asset.bucket(), asset.region());
        StorageProvider actualProvider;
        StorageLocation actual;
        try {
            actualProvider = storage == null ? null : storage.provider();
            actual = storage == null ? null : storage.location();
        } catch (RuntimeException invalidStorage) {
            throw storageMismatch(invalidStorage);
        }
        if (actualProvider != asset.provider() || !expected.equals(actual)) {
            throw storageMismatch(null);
        }
    }

    private static void requireOfficialCosUri(
            MediaAssetRecord asset, MediaVariantRecord variant, URI signed) {
        String expectedHost = asset.bucket() + ".cos." + asset.region() + ".myqcloud.com";
        if (signed == null
                || !"https".equalsIgnoreCase(signed.getScheme())
                || !expectedHost.equalsIgnoreCase(signed.getHost())
                || !expectedHost.equalsIgnoreCase(signed.getRawAuthority())
                || signed.getRawUserInfo() != null
                || signed.getPort() != -1
                || signed.getRawFragment() != null
                || !('/' + variant.objectKey()).equals(signed.getRawPath())
                || signed.getRawQuery() == null
                || signed.getRawQuery().isBlank()) {
            throw storageMismatch(null);
        }
    }

    private static void requireExactRead(
            MediaAssetRecord asset,
            MediaVariantRecord variant,
            Optional<ByteRange> requestedRange,
            StorageRead read) {
        long expectedLength = requestedRange
                .map(ByteRange::length)
                .orElse(variant.byteSize());
        boolean matches = read != null
                && read.totalLength() == variant.byteSize()
                && requestedRange.equals(read.range())
                && read.contentLength() == expectedLength
                && variant.mimeType().equals(read.contentType());

        // LocalStorageService computes a full SHA-256 ETag on every open, so its
        // provider ETag must equal the immutable DB digest. Tencent COS exposes an
        // opaque provider ETag (normally MD5), while the HTTP ETag remains the DB
        // SHA-256; StorageRead itself already guarantees the COS ETag is nonblank.
        if (asset.provider() == StorageProvider.LOCAL) {
            matches = matches && read != null && variant.sha256().equals(read.etag());
        }
        if (!matches) {
            throw storageMismatch(null);
        }
    }

    private static void streamExactly(StorageRead read, OutputStream output)
            throws IOException {
        try (read) {
            Objects.requireNonNull(output, "preview output is required");
            InputStream input = read.inputStream();
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            long remaining = read.contentLength();
            while (remaining > 0) {
                int requested = (int) Math.min(buffer.length, remaining);
                int count = input.read(buffer, 0, requested);
                if (count < 0) {
                    throw storageMismatch(null);
                }
                if (count == 0) {
                    int single = input.read();
                    if (single < 0) {
                        throw storageMismatch(null);
                    }
                    output.write(single);
                    remaining--;
                    continue;
                }
                output.write(buffer, 0, count);
                remaining -= count;
            }
            if (input.read() >= 0) {
                throw storageMismatch(null);
            }
        }
    }

    private static final class StorageStreamingBody
            implements StreamingResponseBody, AutoCloseable {
        private final StorageRead read;
        private boolean available = true;

        private StorageStreamingBody(StorageRead read) {
            this.read = Objects.requireNonNull(read, "preview storage read is required");
        }

        @Override
        public void writeTo(OutputStream output) throws IOException {
            if (!claim()) {
                throw new IOException("media preview body is unavailable");
            }
            streamExactly(read, output);
        }

        @Override
        public void close() throws IOException {
            if (claim()) {
                read.close();
            }
        }

        private synchronized boolean claim() {
            if (!available) {
                return false;
            }
            available = false;
            return true;
        }
    }

    private static HttpHeaders baseHeaders(String strongEtag) {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("no-store");
        headers.setETag(strongEtag);
        return headers;
    }

    private static boolean ifNoneMatchMatches(
            HttpHeaders requestHeaders, String strongEtag) {
        List<String> values = requestHeaders.get(HttpHeaders.IF_NONE_MATCH);
        if (values == null) {
            return false;
        }
        for (String value : values) {
            if (value == null) {
                continue;
            }
            for (String candidate : value.split(",")) {
                String normalized = candidate.trim();
                if ("*".equals(normalized)
                        || strongEtag.equals(normalized)
                        || (normalized.startsWith("W/")
                                && strongEtag.equals(normalized.substring(2)))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void closeAfterPreparationFailure(
            StorageRead read, Throwable primary) {
        if (read == null) {
            return;
        }
        try {
            read.close();
        } catch (IOException closeFailure) {
            primary.addSuppressed(closeFailure);
        }
    }

    private static DomainException notFound() {
        return new DomainException("MEDIA_NOT_FOUND", HttpStatus.NOT_FOUND, Map.of());
    }

    private static DomainException notReady() {
        return new DomainException(
                "MEDIA_NOT_READY", HttpStatus.UNPROCESSABLE_ENTITY, Map.of());
    }

    private static StorageException storageMismatch(Throwable cause) {
        return new StorageException(STORAGE_MISMATCH, cause);
    }
}
