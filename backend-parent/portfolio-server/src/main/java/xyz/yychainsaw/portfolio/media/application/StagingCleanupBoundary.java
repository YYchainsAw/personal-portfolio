package xyz.yychainsaw.portfolio.media.application;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;

record StagingCleanupBoundary(LocalDate boundaryDate, Instant cutoff) {
    private static final ZoneId HONG_KONG = ZoneId.of("Asia/Hong_Kong");
    private static final LocalTime BOUNDARY_TIME = LocalTime.of(4, 0);
    private static final Duration RETENTION = Duration.ofHours(24);

    StagingCleanupBoundary {
        Objects.requireNonNull(boundaryDate, "cleanup boundary date is required");
        Objects.requireNonNull(cutoff, "cleanup cutoff is required");
    }

    static StagingCleanupBoundary current(Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("media staging cleanup clock is required");
        }
        try {
            Instant now = Objects.requireNonNull(
                    clock.instant(), "cleanup clock returned no instant");
            ZonedDateTime hongKongNow = now.atZone(HONG_KONG);
            LocalDate boundaryDate = hongKongNow.toLocalTime().isBefore(BOUNDARY_TIME)
                    ? hongKongNow.toLocalDate().minusDays(1)
                    : hongKongNow.toLocalDate();
            Instant boundary = boundaryDate.atTime(BOUNDARY_TIME)
                    .atZone(HONG_KONG)
                    .toInstant();
            return new StagingCleanupBoundary(boundaryDate, boundary.minus(RETENTION));
        } catch (DateTimeException | ArithmeticException | NullPointerException exception) {
            throw new IllegalStateException("MEDIA_STAGING_CLEANUP_TIME_INVALID");
        }
    }
}
