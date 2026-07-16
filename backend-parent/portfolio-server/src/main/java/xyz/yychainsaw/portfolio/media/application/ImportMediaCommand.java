package xyz.yychainsaw.portfolio.media.application;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;

public record ImportMediaCommand(
        Path assetRoot,
        String publicPath,
        String usage,
        String objectPosition,
        String credit,
        URI sourceUrl,
        Map<String, String> altByLocale) {
    public ImportMediaCommand {
        altByLocale = Map.copyOf(altByLocale);
    }
}
