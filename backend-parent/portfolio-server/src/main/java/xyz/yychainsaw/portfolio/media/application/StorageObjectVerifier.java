package xyz.yychainsaw.portfolio.media.application;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import xyz.yychainsaw.portfolio.media.storage.StorageRead;
import xyz.yychainsaw.portfolio.media.storage.StorageService;

@Component
final class StorageObjectVerifier {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> MIME_TYPES =
            Set.of("image/jpeg", "image/png", "application/pdf");
    private static final int BUFFER_SIZE = 8192;

    private final Path temporaryDirectory;

    StorageObjectVerifier() {
        this(MediaTemporaryFiles.defaultDirectory());
    }

    StorageObjectVerifier(Path temporaryDirectory) {
        this.temporaryDirectory = MediaTemporaryFiles.requireDirectory(temporaryDirectory);
    }

    VerifiedMediaObject verify(
            StorageService storage,
            String objectKey,
            String expectedMimeType,
            long expectedLength,
            String expectedSha256) {
        requireExpected(
                storage, objectKey, expectedMimeType, expectedLength, expectedSha256);
        StorageRead read;
        try {
            read = storage.open(objectKey, Optional.empty());
        } catch (RuntimeException openFailure) {
            throw MediaTemporaryFiles.failure();
        }
        return verifyOpened(
                read, objectKey, expectedMimeType, expectedLength, expectedSha256);
    }

    Optional<VerifiedMediaObject> verifyIfOpenable(
            StorageService storage,
            String objectKey,
            String expectedMimeType,
            long expectedLength,
            String expectedSha256) {
        requireExpected(
                storage, objectKey, expectedMimeType, expectedLength, expectedSha256);
        StorageRead read;
        try {
            read = storage.open(objectKey, Optional.empty());
        } catch (RuntimeException openFailure) {
            return Optional.empty();
        }
        return Optional.of(verifyOpened(
                read, objectKey, expectedMimeType, expectedLength, expectedSha256));
    }

    private VerifiedMediaObject verifyOpened(
            StorageRead read,
            String objectKey,
            String expectedMimeType,
            long expectedLength,
            String expectedSha256) {
        Path temporary = null;
        try {
            try (read) {
                requireMetadata(read, expectedMimeType, expectedLength);
                temporary = MediaTemporaryFiles.create(
                        temporaryDirectory, suffix(expectedMimeType));
                String actualSha = copyExact(read.inputStream(), temporary, expectedLength);
                if (!expectedSha256.equals(actualSha)
                        || Files.size(temporary) != expectedLength) {
                    throw MediaTemporaryFiles.failure();
                }
            }
            return new VerifiedMediaObject(
                    temporary,
                    objectKey,
                    expectedMimeType,
                    expectedLength,
                    expectedSha256,
                    true);
        } catch (IOException | RuntimeException exception) {
            MediaTemporaryFiles.deleteBestEffort(temporary);
            throw MediaTemporaryFiles.failure();
        }
    }

    private static void requireExpected(
            StorageService storage,
            String objectKey,
            String mimeType,
            long length,
            String sha256) {
        long limit = "application/pdf".equals(mimeType)
                ? MediaFileInspector.PDF_BYTE_LIMIT
                : MediaFileInspector.IMAGE_BYTE_LIMIT;
        if (storage == null
                || objectKey == null
                || objectKey.isBlank()
                || !MIME_TYPES.contains(mimeType)
                || length <= 0
                || length > limit
                || sha256 == null
                || !SHA256.matcher(sha256).matches()) {
            throw MediaTemporaryFiles.failure();
        }
    }

    private static void requireMetadata(
            StorageRead read, String expectedMimeType, long expectedLength) {
        if (read == null
                || read.range() == null
                || read.range().isPresent()
                || read.totalLength() != expectedLength
                || read.contentLength() != expectedLength
                || !expectedMimeType.equals(read.contentType())) {
            throw MediaTemporaryFiles.failure();
        }
    }

    private static String copyExact(InputStream input, Path target, long expectedLength)
            throws IOException {
        MessageDigest digest = sha256Digest();
        byte[] buffer = new byte[BUFFER_SIZE];
        long copied = 0;
        try (FileChannel channel = FileChannel.open(
                        target, WRITE, TRUNCATE_EXISTING, NOFOLLOW_LINKS);
                OutputStream output = Channels.newOutputStream(channel)) {
            while (copied < expectedLength) {
                int requested = (int) Math.min(buffer.length, expectedLength - copied);
                int count = input.read(buffer, 0, requested);
                if (count < 0) {
                    throw MediaTemporaryFiles.failure();
                }
                if (count == 0) {
                    int single = input.read();
                    if (single < 0) {
                        throw MediaTemporaryFiles.failure();
                    }
                    output.write(single);
                    digest.update((byte) single);
                    copied++;
                    continue;
                }
                output.write(buffer, 0, count);
                digest.update(buffer, 0, count);
                copied += count;
            }
            if (input.read() >= 0) {
                throw MediaTemporaryFiles.failure();
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw MediaTemporaryFiles.failure();
        }
    }

    private static String suffix(String mimeType) {
        return switch (mimeType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "application/pdf" -> ".pdf";
            default -> throw MediaTemporaryFiles.failure();
        };
    }
}
