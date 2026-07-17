package xyz.yychainsaw.portfolio.media.application;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import xyz.yychainsaw.portfolio.common.error.DomainException;

@Component
public final class MediaFileInspector {
    static final long IMAGE_BYTE_LIMIT = 25L * 1024 * 1024;
    static final long PDF_BYTE_LIMIT = 30L * 1024 * 1024;
    static final long PIXEL_LIMIT = 80_000_000L;
    static final int PNG_PARSE_BUFFER_SIZE = 8_192;
    static final int MAX_PNG_METADATA_CHUNKS = 4_096;
    static final int PDF_REVERSE_SCAN_BLOCK_SIZE = 8_192;

    private static final long VALIDATION_DECODE_PIXEL_LIMIT = 4_000_000L;
    private static final byte[] PNG_SIGNATURE = new byte[] {
        (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };
    private static final int PNG_IHDR = 0x49484452;
    private static final int PNG_PLTE = 0x504c5445;
    private static final int PNG_IDAT = 0x49444154;
    private static final int PNG_IEND = 0x49454e44;
    private static final int PNG_ACTL = 0x6163544c;
    private static final int PNG_FCTL = 0x6663544c;
    private static final int PNG_FDAT = 0x66644154;
    private static final byte[] PDF_SIGNATURE =
            "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final Pattern PDF_HEADER =
            Pattern.compile("%PDF-(?:1\\.[0-7]|2\\.0)");
    private final Path temporaryDirectory;
    private final MediaImageGate imageGate;

    public MediaFileInspector() {
        this(defaultTemporaryDirectory(), MediaImageGate.shared());
    }

    MediaFileInspector(Path temporaryDirectory) {
        this(temporaryDirectory, MediaImageGate.shared());
    }

    MediaFileInspector(Path temporaryDirectory, MediaImageGate imageGate) {
        if (temporaryDirectory == null
                || !Files.isDirectory(temporaryDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("media temporary directory is invalid");
        }
        this.temporaryDirectory = temporaryDirectory;
        this.imageGate = java.util.Objects.requireNonNull(
                imageGate, "media image gate is required");
    }

    public InspectedMedia inspect(UploadMediaCommand command) {
        if (command == null || command.input() == null) {
            throw requestInvalid().toDomainException();
        }

        InputStream input = command.input();
        Path temporaryFile = null;
        InspectedMedia inspected = null;
        DomainException primaryFailure = null;
        try {
            String declaredMime = requireDeclaredMime(command.declaredContentType());
            long declaredLimit = byteLimit(declaredMime);
            requireDeclaredSize(command.declaredSize(), declaredLimit);

            temporaryFile = createTemporaryFile();
            CopyResult copied = copyBounded(input, temporaryFile, declaredLimit);
            if (copied.byteSize() <= 0 || copied.byteSize() != command.declaredSize()) {
                throw sizeMismatch();
            }

            DetectedType detected = detect(temporaryFile);
            if (!detected.mimeType().equals(declaredMime)) {
                throw mimeMismatch();
            }
            if (copied.byteSize() > byteLimit(detected.mimeType())) {
                throw tooLarge();
            }

            Dimensions dimensions = switch (detected) {
                case PDF -> {
                    validatePdf(temporaryFile, copied.byteSize());
                    yield Dimensions.NONE;
                }
                case PNG -> validateImage(
                        temporaryFile, detected, validatePng(temporaryFile));
                case JPEG -> validateImage(
                        temporaryFile, detected, validateJpeg(temporaryFile));
            };
            String filename = normalizeFilename(command.filename(), detected.extension());
            inspected = new InspectedMedia(
                    detected.mimeType(),
                    detected.extension(),
                    copied.byteSize(),
                    copied.sha256(),
                    dimensions.width(),
                    dimensions.height(),
                    filename,
                    temporaryFile);
        } catch (InspectionFailure validationFailure) {
            primaryFailure = validationFailure.toDomainException();
        } catch (IOException | RuntimeException dependencyFailure) {
            primaryFailure = uploadFailed().toDomainException();
        }

        boolean closeFailed = false;
        try {
            input.close();
        } catch (IOException | RuntimeException ignored) {
            closeFailed = true;
        }
        if (primaryFailure != null || closeFailed) {
            deleteBestEffort(temporaryFile);
            if (primaryFailure != null) {
                throw primaryFailure;
            }
            throw uploadFailed().toDomainException();
        }
        return inspected;
    }

    static void validateDimensions(long width, long height) {
        try {
            requireValidDimensions(width, height);
        } catch (InspectionFailure failure) {
            throw failure.toDomainException();
        }
    }

    private static void requireValidDimensions(long width, long height) {
        if (width <= 0
                || height <= 0
                || width > Integer.MAX_VALUE
                || height > Integer.MAX_VALUE) {
            throw dimensionsInvalid();
        }
        if (width > Long.MAX_VALUE / height || width * height > PIXEL_LIMIT) {
            throw pixelLimitExceeded();
        }
    }

    static String normalizeFilename(String filename, String extension) {
        String normalized = filename == null
                ? ""
                : Normalizer.normalize(filename, Normalizer.Form.NFC);
        int slash = Math.max(normalized.lastIndexOf('/'), normalized.lastIndexOf('\\'));
        String basename = normalized.substring(slash + 1);

        StringBuilder filtered = new StringBuilder(basename.length());
        basename.codePoints().forEach(codePoint -> {
            int type = Character.getType(codePoint);
            if (type != Character.CONTROL
                    && type != Character.FORMAT
                    && type != Character.SURROGATE) {
                filtered.appendCodePoint(codePoint);
            }
        });
        String stem = trimDotsAndWhitespace(filtered.toString());
        if (stem.isEmpty() || ".".equals(stem) || "..".equals(stem)) {
            stem = "upload";
        } else {
            int suffix = stem.lastIndexOf('.');
            if (suffix >= 0) {
                stem = trimDotsAndWhitespace(stem.substring(0, suffix));
            }
            if (stem.isEmpty() || ".".equals(stem) || "..".equals(stem)) {
                stem = "upload";
            }
        }

        String canonicalSuffix = '.' + extension;
        int maximumStemCodePoints = 255 - canonicalSuffix.length();
        int stemCodePoints = stem.codePointCount(0, stem.length());
        if (stemCodePoints > maximumStemCodePoints) {
            stem = stem.substring(0, stem.offsetByCodePoints(0, maximumStemCodePoints));
        }
        return stem + canonicalSuffix;
    }

    private static String requireDeclaredMime(String declaredMime) {
        if (!"image/jpeg".equals(declaredMime)
                && !"image/png".equals(declaredMime)
                && !"application/pdf".equals(declaredMime)) {
            throw typeNotAllowed();
        }
        return declaredMime;
    }

    private static void requireDeclaredSize(long declaredSize, long limit) {
        if (declaredSize <= 0) {
            throw sizeMismatch();
        }
        if (declaredSize > limit) {
            throw tooLarge();
        }
    }

    private Path createTemporaryFile() throws IOException {
        return MediaTemporaryFiles.create(temporaryDirectory, ".upload");
    }

    private static CopyResult copyBounded(
            InputStream input, Path target, long limit) throws IOException {
        MessageDigest digest = sha256Digest();
        byte[] buffer = new byte[8192];
        long copied = 0;
        try (FileChannel channel = FileChannel.open(
                        target,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        LinkOption.NOFOLLOW_LINKS);
                OutputStream output = Channels.newOutputStream(channel)) {
            long budget = limit + 1;
            while (copied < budget) {
                int requested = (int) Math.min(buffer.length, budget - copied);
                int count = input.read(buffer, 0, requested);
                if (count < 0) {
                    break;
                }
                if (count == 0) {
                    int single = input.read();
                    if (single < 0) {
                        break;
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
        }
        return new CopyResult(copied, HexFormat.of().formatHex(digest.digest()));
    }

    private static DetectedType detect(Path path) throws IOException {
        byte[] prefix = new byte[PNG_SIGNATURE.length];
        int count;
        try (InputStream input = Files.newInputStream(
                path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            count = input.readNBytes(prefix, 0, prefix.length);
        }
        if (count >= 3
                && (prefix[0] & 0xff) == 0xff
                && (prefix[1] & 0xff) == 0xd8
                && (prefix[2] & 0xff) == 0xff) {
            return DetectedType.JPEG;
        }
        if (count == PNG_SIGNATURE.length
                && MessageDigest.isEqual(prefix, PNG_SIGNATURE)) {
            return DetectedType.PNG;
        }
        if (count >= PDF_SIGNATURE.length
                && startsWith(prefix, PDF_SIGNATURE)) {
            return DetectedType.PDF;
        }
        throw signatureNotAllowed();
    }

    private static Dimensions validatePng(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(
                path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            return validatePng(channel, channel.size());
        }
    }

    static Dimensions validatePng(SeekableByteChannel channel, long size)
            throws IOException {
        if (size < PNG_SIGNATURE.length + 12L) {
            throw corrupt();
        }
        PngInput input = new PngInput(channel, size);
        boolean firstChunk = true;
        boolean seenHeader = false;
        boolean seenPalette = false;
        boolean seenImageData = false;
        boolean imageDataEnded = false;
        int metadataChunks = 0;
        int colorType = -1;
        int bitDepth = -1;
        long width = -1;
        long height = -1;
        byte[] header = new byte[13];
        CRC32 crc = new CRC32();

        while (input.position() < size) {
            if (input.remaining() < 12) {
                throw corrupt();
            }
            long length = input.readUnsignedInt();
            int type = input.readInt();
            if (!isChunkType(type) || length > input.remaining() - 4) {
                throw corrupt();
            }
            crc.reset();
            updatePngTypeCrc(crc, type);
            boolean headerRead = type == PNG_IHDR && length == header.length;
            if (headerRead) {
                input.readAndUpdateCrc(header, crc);
            } else {
                input.updateCrcAndSkip(length, crc);
            }
            if (input.readUnsignedInt() != crc.getValue()) {
                throw corrupt();
            }

            if (type == PNG_ACTL
                    || type == PNG_FCTL
                    || type == PNG_FDAT
                    || (isUppercaseAscii(pngTypeByte(type, 0))
                            && type != PNG_IHDR
                            && type != PNG_PLTE
                            && type != PNG_IDAT
                            && type != PNG_IEND)) {
                throw corrupt();
            }
            // IDAT fragmentation is intentionally unbounded; only metadata is capped.
            if (type != PNG_IHDR && type != PNG_IDAT && type != PNG_IEND
                    && ++metadataChunks > MAX_PNG_METADATA_CHUNKS) {
                throw corrupt();
            }
            if (type == PNG_IHDR) {
                if (!firstChunk || seenHeader || length != header.length || !headerRead) {
                    throw corrupt();
                }
                width = unsignedInt(header, 0);
                height = unsignedInt(header, 4);
                bitDepth = Byte.toUnsignedInt(header[8]);
                colorType = Byte.toUnsignedInt(header[9]);
                int compression = Byte.toUnsignedInt(header[10]);
                int filter = Byte.toUnsignedInt(header[11]);
                int interlace = Byte.toUnsignedInt(header[12]);
                if (!validPngColor(bitDepth, colorType)
                        || compression != 0
                        || filter != 0
                        || (interlace != 0 && interlace != 1)) {
                    throw corrupt();
                }
                seenHeader = true;
            } else if (!seenHeader) {
                throw corrupt();
            } else if (type == PNG_PLTE) {
                if (seenPalette
                        || seenImageData
                        || length < 3
                        || length > 768
                        || length % 3 != 0
                        || colorType == 0
                        || colorType == 4
                        || (colorType == 3 && length / 3 > (1L << bitDepth))) {
                    throw corrupt();
                }
                seenPalette = true;
            } else if (type == PNG_IDAT) {
                if (imageDataEnded) {
                    throw corrupt();
                }
                seenImageData = true;
            } else {
                if (seenImageData) {
                    imageDataEnded = true;
                }
                if (type == PNG_IEND) {
                    if (length != 0
                            || !seenImageData
                            || (colorType == 3 && !seenPalette)
                            || input.position() != size) {
                        throw corrupt();
                    }
                    return new Dimensions(toDimension(width), toDimension(height));
                }
            }
            firstChunk = false;
        }
        throw corrupt();
    }

    private static Dimensions validateJpeg(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length < 4
                || (bytes[0] & 0xff) != 0xff
                || (bytes[1] & 0xff) != 0xd8) {
            throw corrupt();
        }
        boolean seenFrame = false;
        boolean seenScan = false;
        int width = -1;
        int height = -1;
        int index = 2;
        while (index < bytes.length) {
            if ((bytes[index] & 0xff) != 0xff) {
                throw corrupt();
            }
            int markerStart = index;
            while (index < bytes.length && (bytes[index] & 0xff) == 0xff) {
                index++;
            }
            if (index >= bytes.length) {
                throw corrupt();
            }
            int marker = bytes[index++] & 0xff;
            if (marker == 0x00 || marker == 0xd8 || (marker >= 0xd0 && marker <= 0xd7)) {
                throw corrupt();
            }
            if (marker == 0xd9) {
                if (!seenFrame || !seenScan || index != bytes.length) {
                    throw corrupt();
                }
                return new Dimensions(width, height);
            }
            if (marker == 0x01) {
                continue;
            }
            if (index + 2 > bytes.length) {
                throw corrupt();
            }
            int length = ((bytes[index] & 0xff) << 8) | (bytes[index + 1] & 0xff);
            if (length < 2 || index + length > bytes.length) {
                throw corrupt();
            }
            int data = index + 2;
            int end = index + length;
            if (isStartOfFrame(marker)) {
                if (marker != 0xc0 && marker != 0xc2) {
                    throw corrupt();
                }
                if (seenFrame || length < 8) {
                    throw corrupt();
                }
                int precision = bytes[data] & 0xff;
                height = ((bytes[data + 1] & 0xff) << 8) | (bytes[data + 2] & 0xff);
                width = ((bytes[data + 3] & 0xff) << 8) | (bytes[data + 4] & 0xff);
                int components = bytes[data + 5] & 0xff;
                if (precision != 8
                        || components < 1
                        || components > 4
                        || length != 8 + 3 * components) {
                    throw corrupt();
                }
                seenFrame = true;
            }
            if (marker == 0xda) {
                if (!seenFrame || length < 6) {
                    throw corrupt();
                }
                int components = bytes[data] & 0xff;
                if (components < 1
                        || components > 4
                        || length != 6 + 2 * components) {
                    throw corrupt();
                }
                seenScan = true;
                index = end;
                boolean foundMarker = false;
                while (index < bytes.length) {
                    if ((bytes[index] & 0xff) != 0xff) {
                        index++;
                        continue;
                    }
                    markerStart = index;
                    index++;
                    while (index < bytes.length && (bytes[index] & 0xff) == 0xff) {
                        index++;
                    }
                    if (index >= bytes.length) {
                        throw corrupt();
                    }
                    int entropyMarker = bytes[index] & 0xff;
                    if (entropyMarker == 0x00) {
                        if (index - markerStart != 1) {
                            throw corrupt();
                        }
                        index++;
                        continue;
                    }
                    if (entropyMarker >= 0xd0 && entropyMarker <= 0xd7) {
                        index++;
                        continue;
                    }
                    index = markerStart;
                    foundMarker = true;
                    break;
                }
                if (!foundMarker) {
                    throw corrupt();
                }
                continue;
            }
            index = end;
        }
        throw corrupt();
    }

    private Dimensions validateImage(
            Path path, DetectedType detected, Dimensions structuralDimensions) {
        requireValidDimensions(
                structuralDimensions.width(), structuralDimensions.height());
        try {
            ImageVariantGenerator.requireFinalizableDimensions(
                    structuralDimensions.width(), structuralDimensions.height());
        } catch (IllegalStateException incompatibleGeometry) {
            throw pixelLimitExceeded();
        }
        boolean acquired = false;
        ImageReader reader = null;
        try {
            imageGate.acquire();
            acquired = true;
            try (ImageInputStream imageInput = ImageIO.createImageInputStream(path.toFile())) {
                if (imageInput == null) {
                    throw corrupt();
                }
                Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
                if (!readers.hasNext()) {
                    throw corrupt();
                }
                reader = readers.next();
                AtomicBoolean warning = new AtomicBoolean();
                reader.addIIOReadWarningListener((source, message) -> warning.set(true));
                reader.setInput(imageInput, false, true);
                if (reader.getNumImages(true) != 1) {
                    throw corrupt();
                }
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width != structuralDimensions.width()
                        || height != structuralDimensions.height()) {
                    throw corrupt();
                }
                int factor = subsamplingFactor(width, height);
                ImageReadParam parameters = reader.getDefaultReadParam();
                parameters.setSourceSubsampling(factor, factor, 0, 0);
                BufferedImage decoded = reader.read(0, parameters);
                int expectedWidth = ceilDivide(width, factor);
                int expectedHeight = ceilDivide(height, factor);
                if (decoded == null
                        || decoded.getWidth() != expectedWidth
                        || decoded.getHeight() != expectedHeight
                        || warning.get()) {
                    if (decoded != null) {
                        decoded.flush();
                    }
                    throw corrupt();
                }
                decoded.flush();
                return new Dimensions(width, height);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw uploadFailed();
        } catch (InspectionFailure failure) {
            throw failure;
        } catch (IOException | RuntimeException decodeFailure) {
            throw corrupt();
        } finally {
            try {
                if (reader != null) {
                    reader.dispose();
                }
            } catch (RuntimeException ignored) {
                // Reader disposal cannot mask validation and must not strand the gate.
            } finally {
                if (acquired) {
                    imageGate.release();
                }
            }
        }
    }

    private static void validatePdf(Path path, long size) throws IOException {
        if (size < 13) {
            throw corrupt();
        }
        try (FileChannel channel = FileChannel.open(
                path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            String header = new String(readBytes(channel, 8), StandardCharsets.US_ASCII);
            if (!PDF_HEADER.matcher(header).matches()) {
                throw corrupt();
            }
            long index = lastNonWhitespace(channel, size);
            byte[] eof = "%%EOF".getBytes(StandardCharsets.US_ASCII);
            if (index + 1 < eof.length) {
                throw corrupt();
            }
            channel.position(index + 1 - eof.length);
            byte[] actual = readBytes(channel, eof.length);
            for (int offset = 0; offset < eof.length; offset++) {
                if (actual[offset] != eof[offset]) {
                    throw corrupt();
                }
            }
        }
    }

    static long lastNonWhitespace(SeekableByteChannel channel, long size)
            throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(PDF_REVERSE_SCAN_BLOCK_SIZE);
        long end = size;
        while (end > 0) {
            int length = (int) Math.min(buffer.capacity(), end);
            long start = end - length;
            buffer.clear();
            buffer.limit(length);
            channel.position(start);
            readFully(channel, buffer);
            for (int offset = length - 1; offset >= 0; offset--) {
                if (!isPdfWhitespace(buffer.array()[offset])) {
                    return start + offset;
                }
            }
            end = start;
        }
        return -1;
    }

    private static long byteLimit(String mimeType) {
        return "application/pdf".equals(mimeType) ? PDF_BYTE_LIMIT : IMAGE_BYTE_LIMIT;
    }

    private static int subsamplingFactor(int width, int height) {
        int factor = 1;
        while ((long) ceilDivide(width, factor) * ceilDivide(height, factor)
                > VALIDATION_DECODE_PIXEL_LIMIT) {
            factor++;
        }
        return factor;
    }

    private static int ceilDivide(int value, int divisor) {
        return (int) ((value + (long) divisor - 1) / divisor);
    }

    private static int toDimension(long value) {
        if (value > Integer.MAX_VALUE) {
            throw dimensionsInvalid();
        }
        return (int) value;
    }

    private static boolean validPngColor(int bitDepth, int colorType) {
        return switch (colorType) {
            case 0 -> bitDepth == 1
                    || bitDepth == 2
                    || bitDepth == 4
                    || bitDepth == 8
                    || bitDepth == 16;
            case 2, 4, 6 -> bitDepth == 8 || bitDepth == 16;
            case 3 -> bitDepth == 1 || bitDepth == 2 || bitDepth == 4 || bitDepth == 8;
            default -> false;
        };
    }

    private static boolean isStartOfFrame(int marker) {
        return marker >= 0xc0
                && marker <= 0xcf
                && marker != 0xc4
                && marker != 0xc8
                && marker != 0xcc;
    }

    private static boolean isChunkType(int type) {
        for (int index = 0; index < 4; index++) {
            int character = pngTypeByte(type, index);
            if (!isUppercaseAscii(character)
                    && (character < 'a' || character > 'z')) {
                return false;
            }
        }
        return (pngTypeByte(type, 2) & 0x20) == 0;
    }

    private static boolean isUppercaseAscii(int value) {
        return value >= 'A' && value <= 'Z';
    }

    private static int pngTypeByte(int type, int index) {
        return (type >>> ((3 - index) * 8)) & 0xff;
    }

    private static void updatePngTypeCrc(CRC32 crc, int type) {
        for (int index = 0; index < 4; index++) {
            crc.update(pngTypeByte(type, index));
        }
    }

    private static long unsignedInt(byte[] bytes, int offset) {
        return ((long) (bytes[offset] & 0xff) << 24)
                | ((long) (bytes[offset + 1] & 0xff) << 16)
                | ((long) (bytes[offset + 2] & 0xff) << 8)
                | (bytes[offset + 3] & 0xffL);
    }

    private static byte[] readBytes(FileChannel channel, int length) throws IOException {
        byte[] bytes = new byte[length];
        readFully(channel, ByteBuffer.wrap(bytes));
        return bytes;
    }

    private static void readFully(SeekableByteChannel channel, ByteBuffer target)
            throws IOException {
        while (target.hasRemaining()) {
            if (channel.read(target) < 0) {
                throw new IOException("unexpected inspection EOF");
            }
        }
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (value[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPdfWhitespace(byte value) {
        int unsigned = value & 0xff;
        return unsigned == 0
                || unsigned == 9
                || unsigned == 10
                || unsigned == 12
                || unsigned == 13
                || unsigned == 32;
    }

    private static String trimDotsAndWhitespace(String value) {
        int start = 0;
        int end = value.length();
        while (start < end) {
            int codePoint = value.codePointAt(start);
            if (codePoint != '.'
                    && !Character.isWhitespace(codePoint)
                    && !Character.isSpaceChar(codePoint)) {
                break;
            }
            start += Character.charCount(codePoint);
        }
        while (end > start) {
            int codePoint = value.codePointBefore(end);
            if (codePoint != '.'
                    && !Character.isWhitespace(codePoint)
                    && !Character.isSpaceChar(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }
        return value.substring(start, end);
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private static Path defaultTemporaryDirectory() {
        String value = System.getProperty("java.io.tmpdir");
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("media temporary directory is unavailable");
        }
        return Path.of(value);
    }

    private static void deleteBestEffort(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException | RuntimeException ignored) {
            // A process-level temporary-file scavenger owns refused deletions.
        }
    }

    private static InspectionFailure requestInvalid() {
        return failure("MEDIA_REQUEST_INVALID", HttpStatus.UNPROCESSABLE_ENTITY);
    }

    private static InspectionFailure tooLarge() {
        return failure("MEDIA_TOO_LARGE", HttpStatus.PAYLOAD_TOO_LARGE);
    }

    private static InspectionFailure typeNotAllowed() {
        return failure("MEDIA_TYPE_NOT_ALLOWED", HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    private static InspectionFailure signatureNotAllowed() {
        return failure("MEDIA_SIGNATURE_NOT_ALLOWED", HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    private static InspectionFailure mimeMismatch() {
        return failure("MEDIA_MIME_MISMATCH", HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    private static InspectionFailure sizeMismatch() {
        return failure("MEDIA_SIZE_MISMATCH", HttpStatus.UNPROCESSABLE_ENTITY);
    }

    private static InspectionFailure corrupt() {
        return failure("MEDIA_CORRUPT", HttpStatus.UNPROCESSABLE_ENTITY);
    }

    private static InspectionFailure dimensionsInvalid() {
        return failure("MEDIA_DIMENSIONS_INVALID", HttpStatus.UNPROCESSABLE_ENTITY);
    }

    private static InspectionFailure pixelLimitExceeded() {
        return failure("MEDIA_PIXEL_LIMIT_EXCEEDED", HttpStatus.UNPROCESSABLE_ENTITY);
    }

    private static InspectionFailure uploadFailed() {
        return failure("MEDIA_UPLOAD_FAILED", HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private static InspectionFailure failure(String code, HttpStatus status) {
        return new InspectionFailure(code, status);
    }

    private static final class InspectionFailure extends RuntimeException {
        private final String code;
        private final HttpStatus status;

        private InspectionFailure(String code, HttpStatus status) {
            super(null, null, false, false);
            this.code = code;
            this.status = status;
        }

        private DomainException toDomainException() {
            return new DomainException(code, status, Map.of());
        }
    }

    private enum DetectedType {
        JPEG("image/jpeg", "jpg"),
        PNG("image/png", "png"),
        PDF("application/pdf", "pdf");

        private final String mimeType;
        private final String extension;

        DetectedType(String mimeType, String extension) {
            this.mimeType = mimeType;
            this.extension = extension;
        }

        private String mimeType() {
            return mimeType;
        }

        private String extension() {
            return extension;
        }
    }

    private record CopyResult(long byteSize, String sha256) {}

    private static final class PngInput {
        private final SeekableByteChannel channel;
        private final long size;
        private final ByteBuffer buffer = ByteBuffer.allocate(PNG_PARSE_BUFFER_SIZE);
        private long position;

        private PngInput(SeekableByteChannel channel, long size) throws IOException {
            this.channel = channel;
            this.size = size;
            this.position = PNG_SIGNATURE.length;
            buffer.limit(0);
            channel.position(position);
        }

        private long position() {
            return position;
        }

        private long remaining() {
            return size - position;
        }

        private long readUnsignedInt() throws IOException {
            return Integer.toUnsignedLong(readInt());
        }

        private int readInt() throws IOException {
            return (readUnsignedByte() << 24)
                    | (readUnsignedByte() << 16)
                    | (readUnsignedByte() << 8)
                    | readUnsignedByte();
        }

        private int readUnsignedByte() throws IOException {
            requireBufferedByte();
            position++;
            return buffer.get() & 0xff;
        }

        private void readAndUpdateCrc(byte[] target, CRC32 crc)
                throws IOException {
            int offset = 0;
            while (offset < target.length) {
                requireBufferedByte();
                int count = Math.min(buffer.remaining(), target.length - offset);
                buffer.get(target, offset, count);
                crc.update(target, offset, count);
                offset += count;
                position += count;
            }
        }

        private void updateCrcAndSkip(long length, CRC32 crc) throws IOException {
            long remaining = length;
            while (remaining > 0) {
                requireBufferedByte();
                int count = (int) Math.min(buffer.remaining(), remaining);
                int offset = buffer.arrayOffset() + buffer.position();
                crc.update(buffer.array(), offset, count);
                buffer.position(buffer.position() + count);
                position += count;
                remaining -= count;
            }
        }

        private void requireBufferedByte() throws IOException {
            if (buffer.hasRemaining()) {
                return;
            }
            if (position >= size) {
                throw new IOException("unexpected inspection EOF");
            }
            buffer.clear();
            buffer.limit((int) Math.min(buffer.capacity(), size - position));
            int count = channel.read(buffer);
            if (count <= 0) {
                throw new IOException("unexpected inspection EOF");
            }
            buffer.flip();
        }
    }

    record Dimensions(Integer width, Integer height) {
        private static final Dimensions NONE = new Dimensions(null, null);
    }
}
