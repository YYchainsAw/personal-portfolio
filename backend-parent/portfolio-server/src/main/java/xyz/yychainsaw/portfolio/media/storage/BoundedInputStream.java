package xyz.yychainsaw.portfolio.media.storage;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

public final class BoundedInputStream extends FilterInputStream {
    private static final int SKIP_BUFFER_SIZE = 8 * 1024;

    private long remaining;
    private boolean closed;

    public BoundedInputStream(InputStream input, long length) {
        super(requireInput(input));
        if (length < 0) {
            throw new IllegalArgumentException("Invalid bounded stream length");
        }
        this.remaining = length;
    }

    @Override
    public int read() throws IOException {
        ensureOpen();
        if (remaining == 0) {
            return -1;
        }
        int value = in.read();
        if (value < 0) {
            throw prematureEnd();
        }
        remaining--;
        return value;
    }

    @Override
    public int read(byte[] destination, int offset, int length) throws IOException {
        ensureOpen();
        Objects.checkFromIndexSize(offset, length, destination.length);
        if (length == 0) {
            return 0;
        }
        if (remaining == 0) {
            return -1;
        }
        int requested = (int) Math.min((long) length, remaining);
        int count = in.read(destination, offset, requested);
        if (count < 0) {
            throw prematureEnd();
        }
        if (count == 0) {
            return 0;
        }
        remaining -= count;
        return count;
    }

    @Override
    public long skip(long requested) throws IOException {
        ensureOpen();
        if (requested <= 0 || remaining == 0) {
            return 0;
        }
        long target = Math.min(requested, remaining);
        byte[] buffer = new byte[(int) Math.min(SKIP_BUFFER_SIZE, target)];
        long skipped = 0;
        while (skipped < target) {
            int count = read(buffer, 0, (int) Math.min(buffer.length, target - skipped));
            if (count == 0) {
                continue;
            }
            skipped += count;
        }
        return skipped;
    }

    @Override
    public int available() throws IOException {
        ensureOpen();
        return (int) Math.min((long) in.available(), Math.min(remaining, Integer.MAX_VALUE));
    }

    @Override
    public boolean markSupported() {
        return false;
    }

    @Override
    public synchronized void mark(int readLimit) {
        // Deliberately unsupported. Callers can inspect markSupported() before attempting a reset.
    }

    @Override
    public synchronized void reset() throws IOException {
        throw new IOException("Mark/reset is not supported");
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        super.close();
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Storage stream is closed");
        }
    }

    private static InputStream requireInput(InputStream input) {
        if (input == null) {
            throw new IllegalArgumentException("Storage input is required");
        }
        return input;
    }

    private static IOException prematureEnd() {
        return new IOException("Unexpected end of storage object");
    }
}
