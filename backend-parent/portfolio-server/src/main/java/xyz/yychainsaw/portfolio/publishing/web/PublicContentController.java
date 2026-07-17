package xyz.yychainsaw.portfolio.publishing.web;

import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.publicapi.PublishedEnvelope;
import xyz.yychainsaw.portfolio.publicapi.PublicProjectCardDto;
import xyz.yychainsaw.portfolio.publicapi.PublicProjectDto;
import xyz.yychainsaw.portfolio.publicapi.PublicSiteDto;
import xyz.yychainsaw.portfolio.publishing.application.PublicSnapshotQueryService;

@RestController
@RequestMapping("/api/public")
@ConditionalOnWebApplication(type = Type.SERVLET)
public final class PublicContentController {
    private static final String PUBLIC_REVALIDATE = "public, no-cache";

    private final PublicSnapshotQueryService queries;

    public PublicContentController(PublicSnapshotQueryService queries) {
        this.queries = Objects.requireNonNull(
                queries, "public snapshot query service is required");
    }

    @GetMapping("/site")
    public PublishedEnvelope<PublicSiteDto> site(
            @RequestParam String locale,
            @RequestHeader HttpHeaders requestHeaders,
            HttpServletResponse response) {
        LocaleCode parsed = parseLocale(locale);
        return respond(queries.site(parsed), parsed, requestHeaders, response);
    }

    @GetMapping("/projects")
    public PublishedEnvelope<List<PublicProjectCardDto>> catalog(
            @RequestParam String locale,
            @RequestHeader HttpHeaders requestHeaders,
            HttpServletResponse response) {
        LocaleCode parsed = parseLocale(locale);
        return respond(queries.catalog(parsed), parsed, requestHeaders, response);
    }

    @GetMapping("/projects/{slug}")
    public PublishedEnvelope<PublicProjectDto> project(
            @PathVariable String slug,
            @RequestParam String locale,
            @RequestHeader HttpHeaders requestHeaders,
            HttpServletResponse response) {
        LocaleCode parsed = parseLocale(locale);
        return respond(queries.project(slug, parsed), parsed, requestHeaders, response);
    }

    private static <T> PublishedEnvelope<T> respond(
            PublishedEnvelope<T> envelope,
            LocaleCode locale,
            HttpHeaders requestHeaders,
            HttpServletResponse response) {
        Objects.requireNonNull(envelope, "published envelope is required");
        Objects.requireNonNull(requestHeaders, "request headers are required");
        String etag = HttpEtag.api(envelope.checksum(), locale);
        response.setHeader(HttpHeaders.CACHE_CONTROL, PUBLIC_REVALIDATE);
        response.setHeader(HttpHeaders.ETAG, etag);
        List<String> candidates = requestHeaders.get(HttpHeaders.IF_NONE_MATCH);
        if (candidates != null
                && candidates.size() == 1
                && etag.equals(candidates.get(0))) {
            response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            return null;
        }
        response.setStatus(HttpServletResponse.SC_OK);
        return envelope;
    }

    private static LocaleCode parseLocale(String value) {
        try {
            return LocaleCode.from(value);
        } catch (RuntimeException unsupported) {
            throw new DomainException(
                    "LOCALE_UNSUPPORTED", HttpStatus.BAD_REQUEST, Map.of());
        }
    }
}
