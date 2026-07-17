package xyz.yychainsaw.portfolio.publishing.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.util.UriComponentsBuilder;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.publicapi.PublicMediaDto;
import xyz.yychainsaw.portfolio.publicapi.PublicProjectCardDto;
import xyz.yychainsaw.portfolio.publicapi.PublicProjectDto;
import xyz.yychainsaw.portfolio.publicapi.PublicSiteDto;
import xyz.yychainsaw.portfolio.publicapi.PublishedEnvelope;
import xyz.yychainsaw.portfolio.publishing.application.PublicSnapshotQueryService;
import xyz.yychainsaw.portfolio.publishing.config.PublicRenderProperties;

public interface PublicPageRenderer {
    PreparedPage home(LocaleCode locale);

    Optional<PreparedPage> project(LocaleCode locale, String slug);

    PreparedPage privacy(LocaleCode locale);

    record PreparedPage(String etag, ModelAndView view) {
        public PreparedPage {
            Objects.requireNonNull(etag, "page ETag is required");
            Objects.requireNonNull(view, "page view is required");
        }
    }
}

@Service
@ConditionalOnWebApplication(type = Type.SERVLET)
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class SnapshotPublicPageRenderer implements PublicPageRenderer {
    private final PublicSnapshotQueryService queries;
    private final PublicRenderProperties properties;
    private final AssetManifestService manifest;
    private final CompositeEtagService etags;
    private final SafeInitialJson json;

    SnapshotPublicPageRenderer(
            PublicSnapshotQueryService queries,
            PublicRenderProperties properties,
            AssetManifestService manifest,
            CompositeEtagService etags,
            SafeInitialJson json) {
        this.queries = Objects.requireNonNull(
                queries, "public snapshot query service is required");
        this.properties = Objects.requireNonNull(
                properties, "public render properties are required");
        this.manifest = Objects.requireNonNull(
                manifest, "asset manifest is required");
        this.etags = Objects.requireNonNull(etags, "composite ETag service is required");
        this.json = Objects.requireNonNull(json, "safe initial JSON is required");
    }

    @Override
    public PreparedPage home(LocaleCode locale) {
        LocaleCode requiredLocale = requireLocale(locale);
        PublishedEnvelope<PublicSiteDto> site = queries.site(requiredLocale);
        PublishedEnvelope<List<PublicProjectCardDto>> catalog = queries.catalog(requiredLocale);
        String etag = etags.home(
                site.checksum(),
                catalog.checksum(),
                properties.releaseId(),
                properties.templateSchemaVersion(),
                requiredLocale);

        String canonical = pageUrl(requiredLocale);
        PublicMediaDto heroMedia = site.data().hero().media();
        String image = heroMedia == null ? null : absoluteMediaUrl(heroMedia.src());
        StructuredData structuredData = new PersonStructuredData(
                "https://schema.org",
                "Person",
                site.data().identity().displayName(),
                canonical,
                site.data().socialLinks().stream()
                        .map(PublicSiteDto.SocialLink::url)
                        .toList());
        ModelAndView view = baseView(
                "public/home",
                requiredLocale,
                canonical,
                pageUrl(LocaleCode.ZH_CN),
                pageUrl(LocaleCode.EN),
                image,
                new PageBootstrap.Home(
                        requiredLocale.value(), site.data(), catalog.data()),
                structuredData);
        view.addObject("site", site.data());
        view.addObject("catalog", catalog.data());
        return new PreparedPage(etag, view);
    }

    @Override
    public Optional<PreparedPage> project(LocaleCode locale, String slug) {
        LocaleCode requiredLocale = requireLocale(locale);
        PublishedEnvelope<PublicProjectDto> project;
        try {
            project = queries.project(slug, requiredLocale);
        } catch (DomainException missing) {
            if (missing.status() == HttpStatus.NOT_FOUND
                    && "PROJECT_NOT_FOUND".equals(missing.code())) {
                return Optional.empty();
            }
            throw missing;
        }

        PublishedEnvelope<PublicSiteDto> site = queries.site(requiredLocale);
        PublishedEnvelope<List<PublicProjectCardDto>> catalog = queries.catalog(requiredLocale);
        String etag = etags.project(
                site.checksum(),
                catalog.checksum(),
                project.checksum(),
                properties.releaseId(),
                properties.templateSchemaVersion(),
                requiredLocale);

        String canonical = projectUrl(requiredLocale, project.data().slug());
        PublicMediaDto cover = projectCover(catalog.data(), project.data());
        String image = cover == null ? null : absoluteMediaUrl(cover.src());
        StructuredData structuredData = new CreativeWorkStructuredData(
                "https://schema.org",
                "CreativeWork",
                project.data().title(),
                project.data().summary(),
                canonical);
        ModelAndView view = baseView(
                "public/project",
                requiredLocale,
                canonical,
                projectUrl(LocaleCode.ZH_CN, project.data().slug()),
                projectUrl(LocaleCode.EN, project.data().slug()),
                image,
                new PageBootstrap.Project(
                        requiredLocale.value(),
                        site.data(),
                        catalog.data(),
                        project.data()),
                structuredData);
        view.addObject("site", site.data());
        view.addObject("catalog", catalog.data());
        view.addObject("project", project.data());
        view.addObject("cover", cover);
        return Optional.of(new PreparedPage(etag, view));
    }

    @Override
    public PreparedPage privacy(LocaleCode locale) {
        LocaleCode requiredLocale = requireLocale(locale);
        PublishedEnvelope<PublicSiteDto> site = queries.site(requiredLocale);
        String etag = etags.privacy(
                site.checksum(),
                properties.releaseId(),
                properties.templateSchemaVersion(),
                requiredLocale);

        String canonical = privacyUrl(requiredLocale);
        StructuredData structuredData = new WebPageStructuredData(
                "https://schema.org",
                "WebPage",
                site.data().privacy().title(),
                canonical);
        ModelAndView view = baseView(
                "public/privacy",
                requiredLocale,
                canonical,
                privacyUrl(LocaleCode.ZH_CN),
                privacyUrl(LocaleCode.EN),
                null,
                new PageBootstrap.Privacy(requiredLocale.value(), site.data()),
                structuredData);
        view.addObject("site", site.data());
        return new PreparedPage(etag, view);
    }

    private ModelAndView baseView(
            String viewName,
            LocaleCode locale,
            String canonical,
            String zhUrl,
            String enUrl,
            String ogImage,
            PageBootstrap bootstrap,
            StructuredData structuredData) {
        ModelAndView view = new ModelAndView(viewName);
        view.addObject("locale", locale.value());
        view.addObject("canonical", canonical);
        view.addObject("zhUrl", zhUrl);
        view.addObject("enUrl", enUrl);
        view.addObject("ogImage", ogImage);
        view.addObject("assets", new PageAssets(manifest.entryJs(), manifest.css()));
        view.addObject("initialJson", json.serialize(bootstrap));
        view.addObject("structuredData", json.serialize(structuredData));
        return view;
    }

    private String pageUrl(LocaleCode locale) {
        return publicUrl(locale.value());
    }

    private String privacyUrl(LocaleCode locale) {
        return publicUrl(locale.value(), "privacy");
    }

    private String projectUrl(LocaleCode locale, String slug) {
        return publicUrl(locale.value(), "projects", slug);
    }

    private String publicUrl(String... segments) {
        return UriComponentsBuilder.fromUri(properties.publicBaseUrl())
                .pathSegment(segments)
                .build()
                .encode()
                .toUriString();
    }

    private String absoluteMediaUrl(String path) {
        URI media = URI.create(Objects.requireNonNull(path, "media path is required"));
        if (media.isAbsolute()
                || media.getRawAuthority() != null
                || !path.startsWith("/")
                || path.startsWith("//")) {
            throw new IllegalStateException("public media URL must be an absolute path");
        }
        return properties.publicBaseUrl().resolve(media).toString();
    }

    private static PublicMediaDto projectCover(
            List<PublicProjectCardDto> catalog,
            PublicProjectDto project) {
        return catalog.stream()
                .filter(card -> project.projectId().equals(card.projectId()))
                .map(PublicProjectCardDto::cover)
                .filter(Objects::nonNull)
                .findFirst()
                .orElseGet(() -> project.media().stream().findFirst().orElse(null));
    }

    private static LocaleCode requireLocale(LocaleCode locale) {
        return Objects.requireNonNull(locale, "locale is required");
    }

    private record PageAssets(String entryJs, List<String> css) {
        private PageAssets {
            Objects.requireNonNull(entryJs, "entry JS is required");
            css = List.copyOf(css);
        }
    }

    private sealed interface StructuredData permits
            PersonStructuredData, CreativeWorkStructuredData, WebPageStructuredData {
    }

    private record PersonStructuredData(
            @JsonProperty("@context") String context,
            @JsonProperty("@type") String type,
            String name,
            String url,
            List<String> sameAs) implements StructuredData {
        private PersonStructuredData {
            sameAs = List.copyOf(sameAs);
        }
    }

    private record CreativeWorkStructuredData(
            @JsonProperty("@context") String context,
            @JsonProperty("@type") String type,
            String name,
            String description,
            String url) implements StructuredData {
    }

    private record WebPageStructuredData(
            @JsonProperty("@context") String context,
            @JsonProperty("@type") String type,
            String name,
            String url) implements StructuredData {
    }
}
