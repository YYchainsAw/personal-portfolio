package xyz.yychainsaw.portfolio.media.staging;

import java.util.Optional;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRecord;

/**
 * Exact, fence-owning Local cleanup boundary. Implementations must revalidate the
 * reservation and optional immutable asset identity under the publication fence.
 */
@FunctionalInterface
public interface LocalStagingObjectCleanupPort {
    LocalStagingObjectCleanupResult cleanup(
            LocalStagingReservation reservation, Optional<MediaAssetRecord> asset)
            throws Exception;
}
