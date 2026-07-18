package xyz.yychainsaw.portfolio.media.web;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import xyz.yychainsaw.portfolio.api.admin.media.MediaAssetView;
import xyz.yychainsaw.portfolio.api.admin.media.MediaPageView;
import xyz.yychainsaw.portfolio.api.admin.media.UpdateMediaTranslationsRequest;
import xyz.yychainsaw.portfolio.media.application.AdminMediaPreviewService;
import xyz.yychainsaw.portfolio.media.application.MediaManagementService;
import xyz.yychainsaw.portfolio.media.application.MediaRangeNotSatisfiableException;
import xyz.yychainsaw.portfolio.media.application.MediaUploadService;
import xyz.yychainsaw.portfolio.media.application.UploadMediaCommand;

@RestController
@RequestMapping("/api/admin/media")
@ConditionalOnWebApplication(type = Type.SERVLET)
public final class AdminMediaController {
    private static final List<String> SAFE_PREVIEW_HEADERS = List.of(
            HttpHeaders.ACCEPT_RANGES,
            HttpHeaders.CONTENT_DISPOSITION,
            HttpHeaders.CONTENT_LENGTH,
            HttpHeaders.CONTENT_RANGE,
            HttpHeaders.CONTENT_TYPE,
            HttpHeaders.ETAG,
            HttpHeaders.LOCATION,
            "X-Content-Type-Options");

    private final MediaUploadService uploads;
    private final MediaManagementService media;
    private final AdminMediaPreviewService previews;

    public AdminMediaController(
            MediaUploadService uploads,
            MediaManagementService media,
            AdminMediaPreviewService previews) {
        this.uploads = Objects.requireNonNull(uploads, "media upload service is required");
        this.media = Objects.requireNonNull(media, "media management service is required");
        this.previews = Objects.requireNonNull(previews, "media preview service is required");
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MediaAssetView> upload(@RequestPart("file") MultipartFile file)
            throws IOException {
        UploadMediaCommand command = new UploadMediaCommand(
                Objects.requireNonNullElse(file.getOriginalFilename(), "upload"),
                file.getContentType(),
                file.getSize(),
                file.getInputStream());
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(uploads.upload(command));
    }

    @GetMapping
    public ResponseEntity<MediaPageView> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "24") int size,
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(media.list(page, size, status));
    }

    @GetMapping("/{id}")
    public ResponseEntity<MediaAssetView> get(@PathVariable UUID id) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(media.get(id));
    }

    @PutMapping("/{id}/translations")
    public ResponseEntity<MediaAssetView> translations(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateMediaTranslationsRequest input) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(media.updateTranslations(
                        id, input.expectedVersion(), input.translations()));
    }

    @GetMapping("/{id}/preview/{variant}")
    public void preview(
            @PathVariable UUID id,
            @PathVariable String variant,
            @RequestHeader HttpHeaders requestHeaders,
            HttpServletResponse servletResponse) throws IOException {
        ResponseEntity<StreamingResponseBody> response = Objects.requireNonNull(
                previews.preview(id, variant, requestHeaders),
                "media preview response is required");
        StreamingResponseBody body = response.getBody();
        try (PreviewBodyLease ignored = lease(body)) {
            servletResponse.setStatus(response.getStatusCode().value());
            copyPreviewHeaders(response.getHeaders(), servletResponse);
            servletResponse.setHeader(
                    HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
            if (body != null) {
                body.writeTo(servletResponse.getOutputStream());
            }
        }
    }

    @ExceptionHandler(MediaRangeNotSatisfiableException.class)
    public ResponseEntity<Void> rangeNotSatisfiable(
            MediaRangeNotSatisfiableException failure) {
        return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                .header(
                        HttpHeaders.CONTENT_RANGE,
                        "bytes */" + failure.totalLength())
                .cacheControl(CacheControl.noStore())
                .build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> archive(@PathVariable UUID id) {
        media.archive(id);
        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore())
                .build();
    }

    private static void copyPreviewHeaders(
            HttpHeaders source, HttpServletResponse target) {
        for (String name : SAFE_PREVIEW_HEADERS) {
            List<String> values = source.get(name);
            if (values == null || values.isEmpty()) {
                continue;
            }
            target.setHeader(name, values.get(0));
            for (int index = 1; index < values.size(); index++) {
                target.addHeader(name, values.get(index));
            }
        }
    }

    private static PreviewBodyLease lease(StreamingResponseBody body) {
        if (!(body instanceof AutoCloseable closeable)) {
            return () -> { };
        }
        return () -> {
            try {
                closeable.close();
            } catch (IOException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IOException("media preview body close failed");
            }
        };
    }

    @FunctionalInterface
    private interface PreviewBodyLease extends AutoCloseable {
        @Override
        void close() throws IOException;
    }
}
