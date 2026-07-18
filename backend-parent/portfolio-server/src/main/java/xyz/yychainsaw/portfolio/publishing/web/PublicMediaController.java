package xyz.yychainsaw.portfolio.publishing.web;

import jakarta.servlet.http.HttpServletResponse;
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
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.media.application.MediaPreviewRange;
import xyz.yychainsaw.portfolio.media.application.MediaQueryService;
import xyz.yychainsaw.portfolio.media.application.MediaRangeNotSatisfiableException;
import xyz.yychainsaw.portfolio.media.application.MediaVariantDescriptor;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;
import xyz.yychainsaw.portfolio.media.storage.ByteRange;
import xyz.yychainsaw.portfolio.media.storage.StorageException;
import xyz.yychainsaw.portfolio.media.storage.StorageLocation;
import xyz.yychainsaw.portfolio.media.storage.StorageRangeNotSatisfiableException;
import xyz.yychainsaw.portfolio.media.storage.StorageRead;
import xyz.yychainsaw.portfolio.media.storage.StorageRouter;
import xyz.yychainsaw.portfolio.media.storage.StorageService;
import xyz.yychainsaw.portfolio.publishing.persistence.PublicMediaReferenceRepository;

@RestController
@RequestMapping("/api/public/media")
@ConditionalOnWebApplication(type = Type.SERVLET)
public final class PublicMediaController {
    private static final Pattern PUBLIC_VARIANT =
            Pattern.compile("(?:document|w[1-9][0-9]{0,9})");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Duration SIGNED_GET_TTL = Duration.ofMinutes(5);
    private static final String PUBLIC_CACHE_CONTROL = "public, no-cache";
    private static final String NO_STORE = "no-store";
    private static final String STORAGE_MISMATCH = "PUBLIC_MEDIA_STORAGE_MISMATCH";
    private static final int COPY_BUFFER_SIZE = 64 * 1024;

    private final PublicMediaReferenceRepository references;
    private final MediaQueryService media;
    private final StorageRouter storageRouter;

    public PublicMediaController(
            PublicMediaReferenceRepository references,
            MediaQueryService media,
            StorageRouter storageRouter) {
        this.references = Objects.requireNonNull(
                references, "public media reference repository is required");
        this.media = Objects.requireNonNull(media, "media query service is required");
        this.storageRouter = Objects.requireNonNull(
                storageRouter, "storage router is required");
    }

    @GetMapping("/{assetId}/{variant}")
    public void read(
            @PathVariable("assetId") UUID assetId,
            @PathVariable("variant") String variantName,
            @RequestHeader HttpHeaders requestHeaders,
            HttpServletResponse response) throws IOException {
        Objects.requireNonNull(requestHeaders, "request headers are required");
        Objects.requireNonNull(response, "HTTP response is required");

        if (variantName == null || !PUBLIC_VARIANT.matcher(variantName).matches()) {
            throw notFound();
        }
        if (!references.isCurrentlyPublished(assetId, variantName)) {
            throw notFound();
        }

        MediaVariantDescriptor variant = media.requireReadyVariant(assetId, variantName);
        requireExactDescriptor(assetId, variantName, variant);
        String strongEtag = strongEtag(variant.sha256());

        StorageService storage = storageRouter.require(variant.provider());
        requireExactLocation(variant, storage);

        switch (variant.provider()) {
            case LOCAL -> streamLocal(
                    variant, strongEtag, requestHeaders, response, storage);
            case TENCENT_COS -> redirectCos(
                    variant, strongEtag, requestHeaders, response, storage);
        }
    }

    private static void streamLocal(
            MediaVariantDescriptor variant,
            String strongEtag,
            HttpHeaders requestHeaders,
            HttpServletResponse response,
            StorageService storage) throws IOException {
        if (ifNoneMatchMatches(requestHeaders, strongEtag)) {
            setLocalBaseHeaders(response, strongEtag);
            response.setStatus(HttpStatus.NOT_MODIFIED.value());
            return;
        }

        MediaPreviewRange selectedRange;
        try {
            selectedRange = MediaPreviewRange.parse(
                    requestHeaders, variant.byteSize(), variant.sha256());
        } catch (MediaRangeNotSatisfiableException failure) {
            writeRangeNotSatisfiable(
                    response,
                    failure,
                    PUBLIC_CACHE_CONTROL,
                    strongEtag,
                    true);
            return;
        }

        Optional<ByteRange> requestedRange = selectedRange.byteRange();
        StorageRead opened;
        try {
            opened = storage.open(variant.objectKey(), requestedRange);
        } catch (StorageRangeNotSatisfiableException inconsistentStorage) {
            throw storageMismatch(inconsistentStorage);
        }

        try (StorageRead read = opened) {
            requireExactLocalRead(variant, requestedRange, read);
            MediaType contentType = exactMediaType(variant.mimeType());
            Optional<ByteRange> servedRange = read.range();

            setLocalBaseHeaders(response, strongEtag);
            response.setContentType(contentType.toString());
            response.setContentLengthLong(read.contentLength());
            if (servedRange.isPresent()) {
                ByteRange served = servedRange.orElseThrow();
                response.setHeader(
                        HttpHeaders.CONTENT_RANGE,
                        "bytes " + served.startInclusive() + '-'
                                + served.endInclusive() + '/' + read.totalLength());
                response.setStatus(HttpStatus.PARTIAL_CONTENT.value());
            } else {
                response.setStatus(HttpStatus.OK.value());
            }
            response.setHeader("X-Content-Type-Options", "nosniff");
            streamExactly(read, response.getOutputStream());
        }
    }

    private static void redirectCos(
            MediaVariantDescriptor variant,
            String strongEtag,
            HttpHeaders requestHeaders,
            HttpServletResponse response,
            StorageService storage) {
        if (requestHeaders.containsKey(HttpHeaders.RANGE)) {
            writeRangeNotSatisfiable(
                    response,
                    new MediaRangeNotSatisfiableException(variant.byteSize()),
                    NO_STORE,
                    strongEtag,
                    false);
            return;
        }

        URI signed = storage.signedGet(variant.objectKey(), SIGNED_GET_TTL);
        requireOfficialCosUri(variant, signed);
        response.setStatus(HttpStatus.FOUND.value());
        response.setHeader(HttpHeaders.LOCATION, signed.toASCIIString());
        response.setHeader(HttpHeaders.ETAG, strongEtag);
        response.setHeader(HttpHeaders.CACHE_CONTROL, NO_STORE);
    }

    private static void requireExactDescriptor(
            UUID requestedAssetId,
            String requestedVariantName,
            MediaVariantDescriptor variant) {
        boolean matches = variant != null
                && requestedAssetId.equals(variant.assetId())
                && requestedVariantName.equals(variant.variantName())
                && "READY".equals(variant.status())
                && variant.provider() != null
                && validText(variant.objectKey())
                && validText(variant.mimeType())
                && variant.byteSize() > 0
                && variant.sha256() != null
                && SHA256.matcher(variant.sha256()).matches();
        if (!matches) {
            throw storageMismatch(null);
        }
        try {
            exactMediaType(variant.mimeType());
        } catch (RuntimeException invalidMediaType) {
            throw storageMismatch(invalidMediaType);
        }
    }

    private static void requireExactLocation(
            MediaVariantDescriptor variant, StorageService storage) {
        try {
            StorageLocation expected = new StorageLocation(
                    variant.provider(), variant.bucket(), variant.region());
            StorageProvider actualProvider = storage == null ? null : storage.provider();
            StorageLocation actualLocation = storage == null ? null : storage.location();
            if (actualProvider != variant.provider() || !expected.equals(actualLocation)) {
                throw storageMismatch(null);
            }
        } catch (StorageException failure) {
            throw failure;
        } catch (RuntimeException invalidStorage) {
            throw storageMismatch(invalidStorage);
        }
    }

    private static void requireExactLocalRead(
            MediaVariantDescriptor variant,
            Optional<ByteRange> requestedRange,
            StorageRead read) {
        boolean matches = read != null
                && read.totalLength() == variant.byteSize()
                && variant.mimeType().equals(read.contentType())
                && variant.sha256().equals(read.etag());
        if (matches && requestedRange.isEmpty() && read.range().isPresent()) {
            matches = false;
        }
        if (matches && requestedRange.isPresent() && read.range().isPresent()) {
            ByteRange requested = requestedRange.orElseThrow();
            ByteRange served = read.range().orElseThrow();
            matches = served.startInclusive() >= requested.startInclusive()
                    && served.endInclusive() <= requested.endInclusive();
        }
        if (!matches) {
            throw storageMismatch(null);
        }
    }

    private static void requireOfficialCosUri(
            MediaVariantDescriptor variant, URI signed) {
        String expectedHost = variant.bucket() + ".cos." + variant.region()
                + ".myqcloud.com";
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

    private static void streamExactly(StorageRead read, OutputStream output)
            throws IOException {
        Objects.requireNonNull(output, "public media output is required");
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

    private static void setLocalBaseHeaders(
            HttpServletResponse response, String strongEtag) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, PUBLIC_CACHE_CONTROL);
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        response.setHeader(HttpHeaders.ETAG, strongEtag);
    }

    private static void writeRangeNotSatisfiable(
            HttpServletResponse response,
            MediaRangeNotSatisfiableException failure,
            String cacheControl,
            String strongEtag,
            boolean advertiseRanges) {
        response.setStatus(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE.value());
        response.setHeader(
                HttpHeaders.CONTENT_RANGE, "bytes */" + failure.totalLength());
        response.setHeader(HttpHeaders.CACHE_CONTROL, cacheControl);
        response.setHeader(HttpHeaders.ETAG, strongEtag);
        if (advertiseRanges) {
            response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        }
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
                        || normalized.startsWith("W/")
                                && strongEtag.equals(normalized.substring(2))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String strongEtag(String sha256) {
        if (sha256 == null || !SHA256.matcher(sha256).matches()) {
            throw storageMismatch(null);
        }
        return '"' + sha256 + '"';
    }

    private static MediaType exactMediaType(String value) {
        MediaType type = MediaType.parseMediaType(value);
        if (type.isWildcardType() || type.isWildcardSubtype()) {
            throw new IllegalArgumentException("wildcard media type is not allowed");
        }
        return type;
    }

    private static boolean validText(String value) {
        return value != null
                && !value.isBlank()
                && value.equals(value.trim())
                && value.indexOf('\r') < 0
                && value.indexOf('\n') < 0;
    }

    private static DomainException notFound() {
        return new DomainException("MEDIA_NOT_FOUND", HttpStatus.NOT_FOUND, Map.of());
    }

    private static StorageException storageMismatch(Throwable cause) {
        return new StorageException(STORAGE_MISMATCH, cause);
    }
}
