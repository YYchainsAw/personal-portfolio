package xyz.yychainsaw.portfolio.media.application;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import javax.imageio.stream.ImageOutputStreamImpl;

final class BoundedFileImageOutputStream extends ImageOutputStreamImpl {
    private static final String LIMIT_EXCEEDED =
            "encoded media exceeds its byte budget";

    private final FileChannel channel;
    private final long maximumLength;

    BoundedFileImageOutputStream(Path path, long maximumLength) throws IOException {
        if (path == null || maximumLength <= 0) {
            throw new IllegalArgumentException("encoded media output is invalid");
        }
        this.channel = FileChannel.open(
                path, READ, WRITE, TRUNCATE_EXISTING, NOFOLLOW_LINKS);
        this.maximumLength = maximumLength;
    }

    @Override
    public int read() throws IOException {
        ByteBuffer single = ByteBuffer.allocate(1);
        channel.position(streamPos);
        int count = channel.read(single);
        if (count < 0) {
            return -1;
        }
        streamPos++;
        bitOffset = 0;
        return single.array()[0] & 0xff;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        requireBounds(bytes, offset, length);
        if (length == 0) {
            return 0;
        }
        channel.position(streamPos);
        int count = channel.read(ByteBuffer.wrap(bytes, offset, length));
        if (count > 0) {
            streamPos += count;
            bitOffset = 0;
        }
        return count;
    }

    @Override
    public void write(int value) throws IOException {
        requireBudget(1);
        ByteBuffer single = ByteBuffer.wrap(new byte[] {(byte) value});
        channel.position(streamPos);
        while (single.hasRemaining()) {
            channel.write(single);
        }
        streamPos++;
        bitOffset = 0;
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
        requireBounds(bytes, offset, length);
        requireBudget(length);
        ByteBuffer buffer = ByteBuffer.wrap(bytes, offset, length);
        channel.position(streamPos);
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
        streamPos += length;
        bitOffset = 0;
    }

    @Override
    public long length() {
        try {
            return channel.size();
        } catch (IOException exception) {
            return -1;
        }
    }

    @Override
    public void seek(long position) throws IOException {
        if (position < 0 || position > maximumLength) {
            throw new IOException(LIMIT_EXCEEDED);
        }
        super.seek(position);
        channel.position(position);
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        try {
            super.close();
        } catch (IOException exception) {
            failure = exception;
        }
        try {
            channel.close();
        } catch (IOException exception) {
            if (failure == null) {
                failure = exception;
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void requireBudget(int additional) throws IOException {
        if (additional < 0 || streamPos > maximumLength - additional) {
            throw new IOException(LIMIT_EXCEEDED);
        }
    }

    private static void requireBounds(byte[] bytes, int offset, int length) {
        if (bytes == null
                || offset < 0
                || length < 0
                || offset > bytes.length - length) {
            throw new IndexOutOfBoundsException();
        }
    }
}
