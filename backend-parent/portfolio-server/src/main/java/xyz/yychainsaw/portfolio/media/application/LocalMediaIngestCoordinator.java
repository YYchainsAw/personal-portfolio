package xyz.yychainsaw.portfolio.media.application;

import java.io.InputStream;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.stereotype.Component;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;
import xyz.yychainsaw.portfolio.media.staging.LocalStagingKnownRollbackCleanup;
import xyz.yychainsaw.portfolio.media.staging.LocalStagingReservation;
import xyz.yychainsaw.portfolio.media.staging.LocalStagingReservationReceipt;
import xyz.yychainsaw.portfolio.media.staging.LocalStagingReservationService;
import xyz.yychainsaw.portfolio.media.storage.LocalPublicationAuthorization;
import xyz.yychainsaw.portfolio.media.storage.LocalPublicationFence;
import xyz.yychainsaw.portfolio.media.storage.LocalStagingPublication;
import xyz.yychainsaw.portfolio.media.storage.LocalStorageService;
import xyz.yychainsaw.portfolio.media.storage.StorageLocation;
import xyz.yychainsaw.portfolio.media.storage.StorageService;
import xyz.yychainsaw.portfolio.media.storage.StoredObject;

@Component
@ConditionalOnWebApplication(type = Type.SERVLET)
public final class LocalMediaIngestCoordinator {
    private static final String INVALID = "LOCAL_MEDIA_INGEST_INVALID";

    private final LocalStorageService localStorage;
    private final LocalStagingReservationService reservations;
    private final LocalPublicationFence publicationFence;
    private final LocalStagingKnownRollbackCleanup rollbackCleanup;

    public LocalMediaIngestCoordinator(
            LocalStorageService localStorage,
            LocalStagingReservationService reservations,
            LocalPublicationFence publicationFence,
            LocalStagingKnownRollbackCleanup rollbackCleanup) {
        this.localStorage = Objects.requireNonNull(
                localStorage, "local storage is required");
        this.reservations = Objects.requireNonNull(
                reservations, "local staging reservations are required");
        this.publicationFence = Objects.requireNonNull(
                publicationFence, "local publication fence is required");
        this.rollbackCleanup = Objects.requireNonNull(
                rollbackCleanup, "local rollback cleanup is required");
    }

    LocalMediaIngestSession open(
            StorageService writer,
            StorageLocation writerLocation,
            UUID assetId,
            String stagingKey,
            String sha256,
            String mimeType) {
        requireExactLocalWriter(writer, writerLocation);
        LocalStagingReservationReceipt receipt = Objects.requireNonNull(
                reservations.reserve(assetId, sha256, mimeType),
                "local staging reservation receipt is required");
        LocalStagingReservation reservation = Objects.requireNonNull(
                receipt.reservation(), "local staging reservation is required");
        requireExactInitialReservation(reservation, assetId, sha256, mimeType);
        LocalStagingPublication publication = new LocalStagingPublication(
                assetId,
                stagingKey,
                sha256,
                mimeType,
                writerLocation,
                reservation.generation(),
                reservation.cleanupJobId());
        LocalPublicationAuthorization authorization = Objects.requireNonNull(
                publicationFence.acquire(publication),
                "local publication authorization is required");
        return new Session(
                localStorage,
                reservations,
                rollbackCleanup,
                reservation,
                publication,
                authorization);
    }

    private void requireExactLocalWriter(
            StorageService writer, StorageLocation writerLocation) {
        boolean exact;
        try {
            exact = writer == localStorage
                    && writerLocation != null
                    && writer.provider() == StorageProvider.LOCAL
                    && localStorage.provider() == StorageProvider.LOCAL
                    && writerLocation.equals(writer.location())
                    && writerLocation.equals(localStorage.location());
        } catch (RuntimeException invalid) {
            throw fixed();
        }
        if (!exact) {
            throw fixed();
        }
    }

    private static void requireExactInitialReservation(
            LocalStagingReservation reservation,
            UUID assetId,
            String sha256,
            String mimeType) {
        if (!reservation.assetId().equals(assetId)
                || !reservation.sha256().equals(sha256)
                || !reservation.mimeType().equals(mimeType)
                || reservation.generation() != 0) {
            throw fixed();
        }
    }

    private static IllegalStateException fixed() {
        return new IllegalStateException(INVALID);
    }

    private static final class Session implements LocalMediaIngestSession {
        private final LocalStorageService localStorage;
        private final LocalStagingReservationService reservations;
        private final LocalStagingKnownRollbackCleanup rollbackCleanup;
        private final LocalStagingReservation reservation;
        private final LocalStagingPublication publication;
        private final LocalPublicationAuthorization authorization;
        private final long ownerThreadId = Thread.currentThread().getId();
        private final AtomicBoolean cleanupAttempted = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private boolean prepared;

        private Session(
                LocalStorageService localStorage,
                LocalStagingReservationService reservations,
                LocalStagingKnownRollbackCleanup rollbackCleanup,
                LocalStagingReservation reservation,
                LocalStagingPublication publication,
                LocalPublicationAuthorization authorization) {
            this.localStorage = localStorage;
            this.reservations = reservations;
            this.rollbackCleanup = rollbackCleanup;
            this.reservation = reservation;
            this.publication = publication;
            this.authorization = authorization;
        }

        @Override
        public synchronized void prepareOuterTransaction() {
            requireOwnerAndOpen();
            if (prepared) {
                throw fixed();
            }
            LocalStagingReservation locked =
                    reservations.lockCurrentForOuterTransaction(reservation);
            if (!reservation.equals(locked)) {
                throw fixed();
            }
            authorization.reauthenticate();
            prepared = true;
        }

        @Override
        public synchronized StoredObject publish(InputStream input, long contentLength) {
            requireOwnerAndOpen();
            if (!prepared) {
                throw fixed();
            }
            return localStorage.putReservedStaging(
                    authorization, publication, input, contentLength);
        }

        @Override
        public void cleanupKnownRollback() {
            requireOwnerAndOpen();
            if (!cleanupAttempted.compareAndSet(false, true)) {
                return;
            }
            try {
                rollbackCleanup.cleanupKnownRollback(authorization, reservation);
            } catch (RuntimeException ignored) {
                // Unknown cleanup or release outcome deliberately retains the reservation.
            }
        }

        @Override
        public void close() {
            requireOwner();
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            authorization.close();
        }

        private void requireOwnerAndOpen() {
            requireOwner();
            if (closed.get()) {
                throw fixed();
            }
        }

        private void requireOwner() {
            if (Thread.currentThread().getId() != ownerThreadId) {
                throw fixed();
            }
        }

        @Override
        public String toString() {
            return "LocalMediaIngestSession[redacted]";
        }
    }
}
