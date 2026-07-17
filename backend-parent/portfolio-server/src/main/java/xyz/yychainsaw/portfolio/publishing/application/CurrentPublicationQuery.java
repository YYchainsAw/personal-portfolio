package xyz.yychainsaw.portfolio.publishing.application;

import java.util.UUID;

public interface CurrentPublicationQuery {
    boolean isCurrentPublishedProject(UUID projectId);
}
