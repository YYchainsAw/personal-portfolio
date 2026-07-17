package xyz.yychainsaw.portfolio.publishing.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PageBootstrapContractTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test
    void unionShapesMatchTheBrowserContractWithoutAnEnvelope() throws Exception {
        var site = PublicPageFixtures.site("Published headline", true);
        var card = PublicPageFixtures.card();
        var project = PublicPageFixtures.project();

        assertThat(fields(json.readTree(json.writeValueAsString(
                                new PageBootstrap.Home("en", site, List.of(card))))))
                .containsExactlyInAnyOrder("kind", "locale", "site", "catalog");
        assertThat(fields(json.readTree(json.writeValueAsString(new PageBootstrap.Project(
                                "zh-CN", site, List.of(card), project)))))
                .containsExactlyInAnyOrder(
                        "kind", "locale", "site", "catalog", "project");
        assertThat(fields(json.readTree(json.writeValueAsString(
                                new PageBootstrap.Privacy("en", site)))))
                .containsExactlyInAnyOrder("kind", "locale", "site");
    }

    @Test
    void kindsAndLocalesAreClosedAndCatalogsAreDefensivelyCopied() {
        var site = PublicPageFixtures.site("Published headline", true);
        var mutable = new ArrayList<>(List.of(PublicPageFixtures.card()));
        var home = new PageBootstrap.Home("en", site, mutable);
        mutable.clear();

        assertThat(home.kind()).isEqualTo("home");
        assertThat(home.catalog()).hasSize(1);
        assertThatThrownBy(() -> home.catalog().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new PageBootstrap.Home(
                        "wrong", "en", site, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PageBootstrap.Privacy("fr", site))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static List<String> fields(com.fasterxml.jackson.databind.JsonNode node) {
        List<String> fields = new ArrayList<>();
        node.fieldNames().forEachRemaining(fields::add);
        return fields;
    }
}
