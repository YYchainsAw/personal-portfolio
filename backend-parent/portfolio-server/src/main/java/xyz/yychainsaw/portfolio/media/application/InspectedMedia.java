package xyz.yychainsaw.portfolio.media.application;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

public final class InspectedMedia implements AutoCloseable {
    private final String mimeType;
    private final String extension;
    private final long byteSize;
    private final String sha256;
    private final Integer width;
    private final Integer height;
    private final String originalFilename;
    private final Path temporaryFile;
    private boolean closed;

    InspectedMedia(
            String mimeType,
            String extension,
            long byteSize,
            String sha256,
            Integer width,
            Integer height,
            String originalFilename,
            Path temporaryFile) {
        this.mimeType = Objects.requireNonNull(mimeType, "media MIME type is required");
        this.extension = Objects.requireNonNull(extension, "media extension is required");
        this.byteSize = byteSize;
        this.sha256 = Objects.requireNonNull(sha256, "media SHA-256 is required");
        this.width = width;
        this.height = height;
        this.originalFilename =
                Objects.requireNonNull(originalFilename, "media filename is required");
        this.temporaryFile =
                Objects.requireNonNull(temporaryFile, "media temporary file is required");
    }

    public String mimeType() {
        return mimeType;
    }

    public String extension() {
        return extension;
    }

    public long byteSize() {
        return byteSize;
    }

    public String sha256() {
        return sha256;
    }

    public Integer width() {
        return width;
    }

    public Integer height() {
        return height;
    }

    public String originalFilename() {
        return originalFilename;
    }

    synchronized InputStream openStream() throws IOException {
        if (closed
                || !Files.isRegularFile(temporaryFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("inspection temporary file is unavailable");
        }
        return Files.newInputStream(
                temporaryFile, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        Files.deleteIfExists(temporaryFile);
        closed = true;
    }

    @Override
    public String toString() {
        return "InspectedMedia[redacted]";
    }
}
