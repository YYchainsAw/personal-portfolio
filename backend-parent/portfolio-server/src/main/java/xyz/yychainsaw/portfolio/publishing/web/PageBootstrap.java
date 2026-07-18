package xyz.yychainsaw.portfolio.publishing.web;

import java.util.List;
import java.util.Objects;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.publicapi.PublicProjectCardDto;
import xyz.yychainsaw.portfolio.publicapi.PublicProjectDto;
import xyz.yychainsaw.portfolio.publicapi.PublicSiteDto;

public sealed interface PageBootstrap permits PageBootstrap.Home,
        PageBootstrap.Project, PageBootstrap.Privacy {
    String kind();

    String locale();

    record Home(
            String kind,
            String locale,
            PublicSiteDto site,
            List<PublicProjectCardDto> catalog) implements PageBootstrap {
        public Home {
            requireKind(kind, "home");
            locale = requireLocale(locale);
            site = Objects.requireNonNull(site, "site is required");
            catalog = List.copyOf(Objects.requireNonNull(catalog, "catalog is required"));
        }

        public Home(
                String locale,
                PublicSiteDto site,
                List<PublicProjectCardDto> catalog) {
            this("home", locale, site, catalog);
        }
    }

    record Project(
            String kind,
            String locale,
            PublicSiteDto site,
            List<PublicProjectCardDto> catalog,
            PublicProjectDto project) implements PageBootstrap {
        public Project {
            requireKind(kind, "project");
            locale = requireLocale(locale);
            site = Objects.requireNonNull(site, "site is required");
            catalog = List.copyOf(Objects.requireNonNull(catalog, "catalog is required"));
            project = Objects.requireNonNull(project, "project is required");
        }

        public Project(
                String locale,
                PublicSiteDto site,
                List<PublicProjectCardDto> catalog,
                PublicProjectDto project) {
            this("project", locale, site, catalog, project);
        }
    }

    record Privacy(
            String kind,
            String locale,
            PublicSiteDto site) implements PageBootstrap {
        public Privacy {
            requireKind(kind, "privacy");
            locale = requireLocale(locale);
            site = Objects.requireNonNull(site, "site is required");
        }

        public Privacy(String locale, PublicSiteDto site) {
            this("privacy", locale, site);
        }
    }

    private static void requireKind(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(expected + " bootstrap kind is required");
        }
    }

    private static String requireLocale(String value) {
        LocaleCode.from(Objects.requireNonNull(value, "locale is required"));
        return value;
    }
}
