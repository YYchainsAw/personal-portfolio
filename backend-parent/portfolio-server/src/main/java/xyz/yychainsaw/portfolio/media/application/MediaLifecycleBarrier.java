package xyz.yychainsaw.portfolio.media.application;

public interface MediaLifecycleBarrier {
    int NAMESPACE_KEY = 1_347_375_700; // ASCII PORT
    int MEDIA_KEY = 1_296_385_097; // ASCII MEDI

    AutoCloseable acquireExclusiveDeletionLease();
}
