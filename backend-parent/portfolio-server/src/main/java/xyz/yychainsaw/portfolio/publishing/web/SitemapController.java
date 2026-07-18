package xyz.yychainsaw.portfolio.publishing.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.publicapi.PublicProjectCardDto;
import xyz.yychainsaw.portfolio.publishing.application.PublicSnapshotQueryService;
import xyz.yychainsaw.portfolio.publishing.config.PublicRenderProperties;

@RestController
@ConditionalOnWebApplication(type = Type.SERVLET)
public final class SitemapController {
    private static final String PUBLIC_REVALIDATE = "public, no-cache";
    private static final String SITEMAP_NAMESPACE =
            "http://www.sitemaps.org/schemas/sitemap/0.9";

    private final PublicSnapshotQueryService queries;
    private final PublicRenderProperties properties;
    private final CompositeEtagService etags;

    public SitemapController(
            PublicSnapshotQueryService queries,
            PublicRenderProperties properties,
            CompositeEtagService etags) {
        this.queries = Objects.requireNonNull(
                queries, "public snapshot query service is required");
        this.properties = Objects.requireNonNull(
                properties, "public render properties are required");
        this.etags = Objects.requireNonNull(etags, "composite ETag service is required");
    }

    @GetMapping(value = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
    public String sitemap(
            @RequestHeader HttpHeaders requestHeaders,
            HttpServletResponse response) {
        var catalog = queries.catalog(LocaleCode.ZH_CN);
        String etag = etags.sitemap(catalog.checksum());
        response.setHeader(HttpHeaders.CACHE_CONTROL, PUBLIC_REVALIDATE);
        response.setHeader(HttpHeaders.ETAG, etag);
        response.setContentType(MediaType.APPLICATION_XML_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        List<String> candidates = requestHeaders.get(HttpHeaders.IF_NONE_MATCH);
        if (candidates != null
                && candidates.size() == 1
                && etag.equals(candidates.get(0))) {
            response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            return null;
        }
        response.setStatus(HttpServletResponse.SC_OK);
        return xml(urls(catalog.data()));
    }

    private List<String> urls(List<PublicProjectCardDto> catalog) {
        List<String> urls = new ArrayList<>(catalog.size() * 2 + 4);
        urls.add(publicUrl(LocaleCode.ZH_CN.value()));
        urls.add(publicUrl(LocaleCode.EN.value()));
        for (PublicProjectCardDto project : catalog) {
            urls.add(publicUrl(
                    LocaleCode.ZH_CN.value(), "projects", project.slug()));
            urls.add(publicUrl(
                    LocaleCode.EN.value(), "projects", project.slug()));
        }
        urls.add(publicUrl(LocaleCode.ZH_CN.value(), "privacy"));
        urls.add(publicUrl(LocaleCode.EN.value(), "privacy"));
        return List.copyOf(urls);
    }

    private String publicUrl(String... segments) {
        return UriComponentsBuilder.fromUri(properties.publicBaseUrl())
                .pathSegment(segments)
                .build()
                .encode()
                .toUriString();
    }

    private static String xml(List<String> urls) {
        try {
            StringWriter output = new StringWriter();
            XMLStreamWriter xml = XMLOutputFactory.newFactory()
                    .createXMLStreamWriter(output);
            xml.writeStartDocument(StandardCharsets.UTF_8.name(), "1.0");
            xml.writeStartElement("urlset");
            xml.writeDefaultNamespace(SITEMAP_NAMESPACE);
            for (String url : urls) {
                xml.writeStartElement("url");
                xml.writeStartElement("loc");
                xml.writeCharacters(url);
                xml.writeEndElement();
                xml.writeEndElement();
            }
            xml.writeEndElement();
            xml.writeEndDocument();
            xml.close();
            return output.toString();
        } catch (XMLStreamException failure) {
            throw new IllegalStateException("cannot render sitemap", failure);
        }
    }
}
