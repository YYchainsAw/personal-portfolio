package xyz.yychainsaw.portfolio.media.staging;

import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public record LocalStagingReservation(
        UUID assetId,
        String sha256,
        String mimeType,
        long generation,
        UUID cleanupJobId,
        OffsetDateTime reservedAt,
        OffsetDateTime cleanupAfter) {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> MIME_TYPES = Set.of(
            "image/jpeg", "image/png", "application/pdf");

    public LocalStagingReservation {
        if (assetId == null
                || sha256 == null
                || !SHA256.matcher(sha256).matches()
                || !MIME_TYPES.contains(mimeType)
                || generation < 0
                || cleanupJobId == null
                || !validTimes(reservedAt, cleanupAfter)) {
            throw new IllegalArgumentException("local staging reservation is invalid");
        }
    }

    private static boolean validTimes(
            OffsetDateTime reservedAt, OffsetDateTime cleanupAfter) {
        if (reservedAt == null
                || cleanupAfter == null
                || !ZoneOffset.UTC.equals(reservedAt.getOffset())
                || !ZoneOffset.UTC.equals(cleanupAfter.getOffset())) {
            return false;
        }
        try {
            return !cleanupAfter.isBefore(reservedAt.plusHours(24));
        } catch (DateTimeException | ArithmeticException invalid) {
            return false;
        }
    }

    @Override
    public String toString() {
        return "LocalStagingReservation[redacted]";
    }
}
