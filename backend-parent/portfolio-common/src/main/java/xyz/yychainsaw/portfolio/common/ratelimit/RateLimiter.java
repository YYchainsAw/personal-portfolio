package xyz.yychainsaw.portfolio.common.ratelimit;

public interface RateLimiter {
    RateLimitDecision consume(String policy, String subject);
}
