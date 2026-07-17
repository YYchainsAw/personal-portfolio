package xyz.yychainsaw.portfolio.publishing.application;

import java.util.Optional;
import java.util.UUID;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;

public interface ProjectLabelQuery {
    Optional<String> findProjectTitle(UUID projectId, LocaleCode locale);
}
