package xyz.yychainsaw.portfolio.common.ratelimit;

import java.time.Clock;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public final class InMemoryRateLimiter implements RateLimiter {
    private final RateLimitProperties properties;
    private final Clock clock;
    private final Map<Key, Bucket> buckets = new HashMap<>();
    private long latestEpochSecond = Long.MIN_VALUE;

    public InMemoryRateLimiter(RateLimitProperties properties, Clock clock) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public RateLimitDecision consume(String policy, String subject) {
        if (policy == null || policy.isBlank()) {
            throw new IllegalArgumentException("policy is required");
        }
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("subject is required");
        }

        RateLimitProperties.Policy configured = properties.policies().get(policy);
        if (configured == null) {
            throw new IllegalArgumentException("unknown rate-limit policy: " + policy);
        }

        Key key = new Key(policy, subject);
        synchronized (buckets) {
            long now = Math.max(latestEpochSecond, clock.instant().getEpochSecond());
            latestEpochSecond = now;
            Bucket existing = buckets.get(key);

            if (existing != null && existing.expiresAtEpochSecond() > now) {
                if (existing.used() >= configured.limit()) {
                    return RateLimitDecision.deny(
                            secondsUntil(existing.expiresAtEpochSecond(), now));
                }
                buckets.put(key, new Bucket(existing.expiresAtEpochSecond(), existing.used() + 1));
                return RateLimitDecision.allow();
            }

            if (existing == null && buckets.size() >= properties.maximumSubjects()) {
                long earliestActiveExpiry = removeExpiredAndFindEarliestActiveExpiry(now);
                if (buckets.size() >= properties.maximumSubjects()) {
                    return RateLimitDecision.deny(secondsUntil(earliestActiveExpiry, now));
                }
            }

            long expiresAtEpochSecond = nextWindowBoundary(
                    now, configured.window().toSeconds());
            buckets.put(key, new Bucket(expiresAtEpochSecond, 1));
            return RateLimitDecision.allow();
        }
    }

    private long removeExpiredAndFindEarliestActiveExpiry(long now) {
        long earliestActiveExpiry = Long.MAX_VALUE;
        Iterator<Map.Entry<Key, Bucket>> iterator = buckets.entrySet().iterator();
        while (iterator.hasNext()) {
            Bucket bucket = iterator.next().getValue();
            if (bucket.expiresAtEpochSecond() <= now) {
                iterator.remove();
            } else {
                earliestActiveExpiry = Math.min(
                        earliestActiveExpiry, bucket.expiresAtEpochSecond());
            }
        }
        return earliestActiveExpiry;
    }

    private static long nextWindowBoundary(long now, long windowSeconds) {
        long remaining = windowSeconds - Math.floorMod(now, windowSeconds);
        return Math.addExact(now, remaining);
    }

    private static long secondsUntil(long expiresAtEpochSecond, long now) {
        if (expiresAtEpochSecond <= now) {
            return 1;
        }
        try {
            return Math.subtractExact(expiresAtEpochSecond, now);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private record Key(String policy, String subject) {}

    private record Bucket(long expiresAtEpochSecond, int used) {}
}
