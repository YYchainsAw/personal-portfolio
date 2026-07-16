package xyz.yychainsaw.portfolio.media.staging;

import xyz.yychainsaw.portfolio.media.storage.LocalPublicationAuthorization;

@FunctionalInterface
public interface LocalStagingKnownRollbackCleanup {
    boolean cleanupKnownRollback(
            LocalPublicationAuthorization authorization,
            LocalStagingReservation reservation);
}
