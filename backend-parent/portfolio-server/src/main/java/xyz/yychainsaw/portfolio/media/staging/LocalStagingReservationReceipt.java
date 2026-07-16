package xyz.yychainsaw.portfolio.media.staging;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

public final class LocalStagingReservationReceipt {
    private final LocalStagingReservation reservation;

    LocalStagingReservationReceipt(LocalStagingReservation reservation) {
        this.reservation = Objects.requireNonNull(
                reservation, "local staging reservation is required");
    }

    public LocalStagingReservation reservation() {
        return reservation;
    }

    public UUID assetId() {
        return reservation.assetId();
    }

    public String sha256() {
        return reservation.sha256();
    }

    public String mimeType() {
        return reservation.mimeType();
    }

    public long generation() {
        return reservation.generation();
    }

    public UUID cleanupJobId() {
        return reservation.cleanupJobId();
    }

    public OffsetDateTime reservedAt() {
        return reservation.reservedAt();
    }

    public OffsetDateTime cleanupAfter() {
        return reservation.cleanupAfter();
    }

    @Override
    public String toString() {
        return "LocalStagingReservationReceipt[redacted]";
    }
}
