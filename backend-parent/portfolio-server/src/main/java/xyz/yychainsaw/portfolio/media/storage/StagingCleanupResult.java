package xyz.yychainsaw.portfolio.media.storage;

import java.time.Duration;

public record StagingCleanupResult(
        long scanned, long candidates, long deleted, Duration elapsed) {
    public StagingCleanupResult {
        if (scanned < 0
                || candidates < 0
                || deleted < 0
                || elapsed == null
                || elapsed.isNegative()) {
            throw new IllegalArgumentException("Invalid staging cleanup result");
        }
    }
}
