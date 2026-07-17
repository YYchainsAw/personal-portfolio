package xyz.yychainsaw.portfolio.media.application;

public final class MediaRangeNotSatisfiableException extends RuntimeException {
    private final long totalLength;

    public MediaRangeNotSatisfiableException(long totalLength) {
        super("Media byte range is not satisfiable");
        if (totalLength < 0) {
            throw new IllegalArgumentException("Invalid media total length");
        }
        this.totalLength = totalLength;
    }

    public long totalLength() {
        return totalLength;
    }
}
