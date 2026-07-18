package xyz.yychainsaw.portfolio.publishing.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** A thread-safe UTC clock whose instant can be advanced deterministically by tests. */
public final class MutableClock extends Clock {
    private final AtomicReference<Instant> current;

    public MutableClock(Instant initial) {
        current = new AtomicReference<>(Objects.requireNonNull(initial, "initial instant is required"));
    }

    public void set(Instant instant) {
        current.set(Objects.requireNonNull(instant, "instant is required"));
    }

    public void advance(Duration duration) {
        Objects.requireNonNull(duration, "duration is required");
        current.updateAndGet(instant -> instant.plus(duration));
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        Objects.requireNonNull(zone, "zone is required");
        if (!ZoneOffset.UTC.equals(zone.normalized())) {
            throw new IllegalArgumentException("mutable test clock only supports UTC");
        }
        return this;
    }

    @Override
    public Instant instant() {
        return current.get();
    }
}
