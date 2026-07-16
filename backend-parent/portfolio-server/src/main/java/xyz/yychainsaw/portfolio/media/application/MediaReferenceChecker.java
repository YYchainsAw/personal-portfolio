package xyz.yychainsaw.portfolio.media.application;

import java.util.List;
import java.util.UUID;

public interface MediaReferenceChecker {
    List<MediaReference> findReferences(UUID assetId);
}
