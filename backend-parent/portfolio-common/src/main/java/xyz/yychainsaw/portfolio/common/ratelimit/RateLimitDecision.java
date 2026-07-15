package xyz.yychainsaw.portfolio.common.ratelimit;

public record RateLimitDecision(boolean allowed, long retryAfterSeconds) {
    public RateLimitDecision {
        if (retryAfterSeconds < 0) {
            throw new IllegalArgumentException("retryAfterSeconds must be non-negative");
        }
    }

    public static RateLimitDecision allow() {
        return new RateLimitDecision(true, 0);
    }

    public static RateLimitDecision deny(long seconds) {
        return new RateLimitDecision(false, Math.max(1, seconds));
    }
}
