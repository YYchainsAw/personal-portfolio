package xyz.yychainsaw.portfolio.media.application;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import javax.imageio.stream.FileImageInputStream;
import javax.imageio.stream.ImageInputStream;

final class VerifiedMediaObject implements AutoCloseable {
    private static final Set<String> MIME_TYPES =
            Set.of("image/jpeg", "image/png", "application/pdf");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final Path path;
    private final String objectKey;
    private final String mimeType;
    private final long byteSize;
    private final String sha256;
    private final boolean deleteOnClose;
    private final Consumer<Path> deletion;
    private final AtomicBoolean closed = new AtomicBoolean();

    VerifiedMediaObject(
            Path path,
            String objectKey,
            String mimeType,
            long byteSize,
            String sha256,
            boolean deleteOnClose) {
        this(
                path,
                objectKey,
                mimeType,
                byteSize,
                sha256,
                deleteOnClose,
                MediaTemporaryFiles::delete);
    }

    VerifiedMediaObject(
            Path path,
            String objectKey,
            String mimeType,
            long byteSize,
            String sha256,
            boolean deleteOnClose,
            Consumer<Path> deletion) {
        if (path == null
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || objectKey == null
                || objectKey.isBlank()
                || !MIME_TYPES.contains(mimeType)
                || byteSize <= 0
                || sha256 == null
                || !SHA256.matcher(sha256).matches()
                || deletion == null) {
            throw MediaTemporaryFiles.failure();
        }
        this.path = path;
        this.objectKey = objectKey;
        this.mimeType = mimeType;
        this.byteSize = byteSize;
        this.sha256 = sha256;
        this.deleteOnClose = deleteOnClose;
        this.deletion = deletion;
    }

    String objectKey() {
        return objectKey;
    }

    String mimeType() {
        return mimeType;
    }

    long byteSize() {
        return byteSize;
    }

    String sha256() {
        return sha256;
    }

    InputStream openInput() throws IOException {
        if (closed.get()) {
            throw new IOException("verified media object is closed");
        }
        return MediaTemporaryFiles.open(path);
    }

    ImageInputStream openImageInput() throws IOException {
        if (closed.get()
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("verified media object is closed");
        }
        return new FileImageInputStream(path.toFile());
    }

    @Override
    public synchronized void close() {
        if (closed.get()) {
            return;
        }
        if (deleteOnClose) {
            deletion.accept(path);
        }
        closed.set(true);
    }

    @Override
    public String toString() {
        return "VerifiedMediaObject[mimeType=" + mimeType
                + ", byteSize=" + byteSize + ']';
    }
}
