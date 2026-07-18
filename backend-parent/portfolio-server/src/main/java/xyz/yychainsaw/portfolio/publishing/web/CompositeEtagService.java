package xyz.yychainsaw.portfolio.publishing.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;

@Component
public final class CompositeEtagService {
    public String home(
            String site,
            String catalog,
            String release,
            int template,
            LocaleCode locale) {
        return hash(List.of(
                "home",
                site,
                catalog,
                release,
                Integer.toString(template),
                Objects.requireNonNull(locale, "locale is required").value()));
    }

    public String project(
            String site,
            String catalog,
            String project,
            String release,
            int template,
            LocaleCode locale) {
        return hash(List.of(
                "project",
                site,
                catalog,
                project,
                release,
                Integer.toString(template),
                Objects.requireNonNull(locale, "locale is required").value()));
    }

    public String privacy(
            String site,
            String release,
            int template,
            LocaleCode locale) {
        return hash(List.of(
                "privacy",
                site,
                release,
                Integer.toString(template),
                Objects.requireNonNull(locale, "locale is required").value()));
    }

    public String sitemap(String catalog) {
        return hash(List.of("sitemap", catalog));
    }

    private static String hash(List<String> values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                digest.update(Objects.requireNonNull(
                                value, "ETag dependency is required")
                        .getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return '"' + HexFormat.of().formatHex(digest.digest()) + '"';
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
