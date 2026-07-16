package xyz.yychainsaw.portfolio.media.storage;

public final class StorageRangeNotSatisfiableException extends RuntimeException {
    private final long totalLength;

    public StorageRangeNotSatisfiableException(long totalLength) {
        super("Storage byte range is not satisfiable");
        if (totalLength < 0) {
            throw new IllegalArgumentException("Invalid total storage length");
        }
        this.totalLength = totalLength;
    }

    public long totalLength() {
        return totalLength;
    }
}
