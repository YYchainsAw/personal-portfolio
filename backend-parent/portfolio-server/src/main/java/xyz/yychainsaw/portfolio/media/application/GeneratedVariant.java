package xyz.yychainsaw.portfolio.media.application;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Pattern;

final class GeneratedVariant implements AutoCloseable {
    private static final Pattern NAME = Pattern.compile("w[1-9][0-9]{0,9}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final Path path;
    private final String variantName;
    private final String format;
    private final String mimeType;
    private final long byteSize;
    private final int width;
    private final int height;
    private final String sha256;
    private final Consumer<Path> deletion;
    private final AtomicBoolean closed = new AtomicBoolean();

    GeneratedVariant(
            Path path,
            String variantName,
            String format,
            String mimeType,
            long byteSize,
            int width,
            int height,
            String sha256) {
        this(
                path,
                variantName,
                format,
                mimeType,
                byteSize,
                width,
                height,
                sha256,
                MediaTemporaryFiles::delete);
    }

    GeneratedVariant(
            Path path,
            String variantName,
            String format,
            String mimeType,
            long byteSize,
            int width,
            int height,
            String sha256,
            Consumer<Path> deletion) {
        if (path == null
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || variantName == null
                || !NAME.matcher(variantName).matches()
                || !("JPEG".equals(format) || "PNG".equals(format))
                || !("image/jpeg".equals(mimeType) || "image/png".equals(mimeType))
                || (("JPEG".equals(format)) != ("image/jpeg".equals(mimeType)))
                || byteSize <= 0
                || width <= 0
                || height <= 0
                || sha256 == null
                || !SHA256.matcher(sha256).matches()
                || deletion == null) {
            throw MediaTemporaryFiles.failure();
        }
        this.path = path;
        this.variantName = variantName;
        this.format = format;
        this.mimeType = mimeType;
        this.byteSize = byteSize;
        this.width = width;
        this.height = height;
        this.sha256 = sha256;
        this.deletion = deletion;
    }

    String variantName() {
        return variantName;
    }

    String format() {
        return format;
    }

    String mimeType() {
        return mimeType;
    }

    long byteSize() {
        return byteSize;
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    String sha256() {
        return sha256;
    }

    InputStream openInput() throws IOException {
        if (closed.get()) {
            throw new IOException("generated variant is closed");
        }
        return MediaTemporaryFiles.open(path);
    }

    @Override
    public synchronized void close() {
        if (!closed.get()) {
            deletion.accept(path);
            closed.set(true);
        }
    }

    @Override
    public String toString() {
        return "GeneratedVariant[variantName=" + variantName
                + ", format=" + format
                + ", mimeType=" + mimeType
                + ", byteSize=" + byteSize
                + ", width=" + width
                + ", height=" + height
                + ", sha256=" + sha256 + ']';
    }
}
