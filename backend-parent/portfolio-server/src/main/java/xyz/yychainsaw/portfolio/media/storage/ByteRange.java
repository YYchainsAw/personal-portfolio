package xyz.yychainsaw.portfolio.media.storage;

public record ByteRange(long startInclusive, long endInclusive) {
    public ByteRange {
        if (startInclusive < 0 || endInclusive < startInclusive) {
            throw new IllegalArgumentException("Invalid byte range");
        }
        try {
            Math.addExact(Math.subtractExact(endInclusive, startInclusive), 1);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Invalid byte range", exception);
        }
    }

    public long length() {
        return endInclusive - startInclusive + 1;
    }
}
