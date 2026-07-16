package xyz.yychainsaw.portfolio.media.storage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

public final class LocalPublicationAuthorization implements AutoCloseable {
    private static final String INVALID = "LOCAL_STAGING_AUTHORIZATION_INVALID";
    private static final String DEADLINE = "LOCAL_PUBLICATION_DEADLINE_EXCEEDED";
    private static final Pattern CANONICAL_VOLUME_ID = Pattern.compile("[0-9a-f]{64}");

    private final LocalStagingPublication publication;
    private final String volumeId;
    private final byte[] volumeIdBytes;
    private final long ownerThreadId;
    private final long deadlineNanos;
    private final LongSupplier nanoTime;
    private final FenceLease lease;
    private boolean active = true;
    private boolean publicationClaimed;

    LocalPublicationAuthorization(
            LocalStagingPublication publication,
            String volumeId,
            long deadlineNanos,
            LongSupplier nanoTime,
            FenceLease lease) {
        this.publication = Objects.requireNonNull(publication, "publication is required");
        if (volumeId == null || !CANONICAL_VOLUME_ID.matcher(volumeId).matches()) {
            throw new IllegalArgumentException("local publication volume id is invalid");
        }
        this.volumeId = volumeId;
        this.volumeIdBytes = volumeId.getBytes(StandardCharsets.US_ASCII);
        this.nanoTime = Objects.requireNonNull(nanoTime, "nano-time source is required");
        this.lease = Objects.requireNonNull(lease, "publication fence lease is required");
        this.deadlineNanos = deadlineNanos;
        this.ownerThreadId = Thread.currentThread().getId();
    }

    synchronized void require(
            LocalStagingPublication candidate, String candidateVolumeId) {
        requireLiveExact(candidate, candidateVolumeId);
    }

    synchronized void claim(
            LocalStagingPublication candidate, String candidateVolumeId) {
        if (publicationClaimed) {
            throw new StorageException(INVALID);
        }
        requireLiveExact(candidate, candidateVolumeId);
        publicationClaimed = true;
    }

    synchronized void requireVolume(String candidateVolumeId) {
        requireLiveVolume(candidateVolumeId);
    }

    void reauthenticate(
            LocalStagingPublication candidate, String candidateVolumeId) {
        require(candidate, candidateVolumeId);
        try {
            lease.reauthenticate();
        } catch (RuntimeException invalidFence) {
            throw new StorageException(INVALID);
        }
        require(candidate, candidateVolumeId);
    }

    public void reauthenticate() {
        reauthenticate(publication, volumeId);
    }

    public void reauthenticateVolume(String candidateVolumeId) {
        reauthenticate(publication, candidateVolumeId);
    }

    private void requireLiveExact(
            LocalStagingPublication candidate, String candidateVolumeId) {
        if (candidate == null || !publication.equals(candidate)) {
            throw new StorageException(INVALID);
        }
        requireLiveVolume(candidateVolumeId);
    }

    private void requireLiveVolume(String candidateVolumeId) {
        if (!active
                || ownerThreadId != Thread.currentThread().getId()
                || !sameVolume(candidateVolumeId)) {
            throw new StorageException(INVALID);
        }
        if (nanoTime.getAsLong() >= deadlineNanos) {
            throw new StorageException(DEADLINE);
        }
        boolean held;
        try {
            held = lease.isHeld();
        } catch (RuntimeException failure) {
            held = false;
        }
        if (!held) {
            throw new StorageException(INVALID);
        }
    }

    private boolean sameVolume(String candidateVolumeId) {
        return candidateVolumeId != null
                && CANONICAL_VOLUME_ID.matcher(candidateVolumeId).matches()
                && MessageDigest.isEqual(
                        volumeIdBytes,
                        candidateVolumeId.getBytes(StandardCharsets.US_ASCII));
    }

    @Override
    public void close() {
        FenceLease release;
        synchronized (this) {
            if (!active) {
                return;
            }
            active = false;
            release = lease;
        }
        release.close();
    }

    @Override
    public String toString() {
        return "LocalPublicationAuthorization[REDACTED]";
    }

    interface FenceLease {
        boolean isHeld();

        default void reauthenticate() {
            if (!isHeld()) {
                throw new StorageException(INVALID);
            }
        }

        void close();
    }
}
