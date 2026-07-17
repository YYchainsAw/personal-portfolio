package xyz.yychainsaw.portfolio.publishing.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.web.util.UriComponentsBuilder;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.publishing.persistence.PublishingRepository;

@Controller
@ConditionalOnWebApplication(type = Type.SERVLET)
public final class PublicPageController {
    private static final String PUBLIC_REVALIDATE = "public, no-cache";

    private final PublicPageRenderer pages;
    private final PublishingRepository publishing;

    public PublicPageController(
            PublicPageRenderer pages,
            PublishingRepository publishing) {
        this.pages = Objects.requireNonNull(pages, "public page renderer is required");
        this.publishing = Objects.requireNonNull(
                publishing, "publishing repository is required");
    }

    @GetMapping("/")
    public ResponseEntity<Void> root() {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create("/zh-CN"))
                .build();
    }

    @GetMapping({"/zh-CN", "/en"})
    public ModelAndView home(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestHeader HttpHeaders requestHeaders) {
        return respond(pages.home(locale(request)), requestHeaders, response);
    }

    @GetMapping({"/zh-CN/projects/{slug}", "/en/projects/{slug}"})
    public ModelAndView project(
            @PathVariable String slug,
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestHeader HttpHeaders requestHeaders) {
        LocaleCode locale = locale(request);
        var page = pages.project(locale, slug);
        if (page.isPresent()) {
            return respond(page.orElseThrow(), requestHeaders, response);
        }

        String target = publishing.redirectTarget(slug)
                .filter(value -> !value.equals(slug))
                .orElseThrow(PublicPageController::notFound);
        String location = UriComponentsBuilder.fromPath("/")
                .pathSegment(locale.value(), "projects", target)
                .build()
                .encode()
                .toUriString();
        RedirectView redirect = new RedirectView(location, false, false);
        redirect.setExposeModelAttributes(false);
        redirect.setPropagateQueryParams(false);
        redirect.setStatusCode(HttpStatus.MOVED_PERMANENTLY);
        return new ModelAndView(redirect);
    }

    @GetMapping({"/zh-CN/privacy", "/en/privacy"})
    public ModelAndView privacy(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestHeader HttpHeaders requestHeaders) {
        return respond(pages.privacy(locale(request)), requestHeaders, response);
    }

    private static ModelAndView respond(
            PublicPageRenderer.PreparedPage page,
            HttpHeaders requestHeaders,
            HttpServletResponse response) {
        Objects.requireNonNull(page, "prepared page is required");
        Objects.requireNonNull(requestHeaders, "request headers are required");
        response.setHeader(HttpHeaders.CACHE_CONTROL, PUBLIC_REVALIDATE);
        response.setHeader(HttpHeaders.ETAG, page.etag());
        List<String> candidates = requestHeaders.get(HttpHeaders.IF_NONE_MATCH);
        if (candidates != null
                && candidates.size() == 1
                && page.etag().equals(candidates.get(0))) {
            response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            return null;
        }
        response.setStatus(HttpServletResponse.SC_OK);
        return page.view();
    }

    private static LocaleCode locale(HttpServletRequest request) {
        String[] segments = Objects.requireNonNull(
                        request, "request is required")
                .getRequestURI()
                .split("/", 3);
        if (segments.length < 2) {
            throw notFound();
        }
        try {
            return LocaleCode.from(segments[1]);
        } catch (IllegalArgumentException unsupported) {
            throw notFound();
        }
    }

    private static DomainException notFound() {
        return new DomainException(
                "PUBLIC_CONTENT_NOT_FOUND", HttpStatus.NOT_FOUND, Map.of());
    }
}
