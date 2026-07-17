package xyz.yychainsaw.portfolio.media.application;

import java.util.Objects;
import java.util.concurrent.Semaphore;

/** One application-wide, fair, interruptible gate for every ImageIO path. */
final class MediaImageGate {
    private static final MediaImageGate SHARED =
            new MediaImageGate(new Semaphore(1, true));

    private final Semaphore permit;

    MediaImageGate(Semaphore permit) {
        this.permit = Objects.requireNonNull(permit, "media image permit is required");
        if (!permit.isFair()) {
            throw new IllegalArgumentException("media image permit must be fair");
        }
    }

    static MediaImageGate shared() {
        return SHARED;
    }

    void acquire() throws InterruptedException {
        permit.acquire();
    }

    void release() {
        permit.release();
    }
}
