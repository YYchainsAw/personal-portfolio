package xyz.yychainsaw.portfolio.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import xyz.yychainsaw.portfolio.common.error.DomainException;

class MediaFileInspectorTest {
    private static final byte[] PDF =
            "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);

    @TempDir
    Path temporaryDirectory;

    @ParameterizedTest
    @MethodSource("validImages")
    void acceptsBaselineProgressiveAlphaAndInterlacedImages(
            String filename, String mimeType, byte[] bytes, String extension)
            throws Exception {
        CloseCountingInputStream input = new CloseCountingInputStream(bytes);

        try (InspectedMedia media = inspect(filename, mimeType, bytes.length, input)) {
            assertThat(media.mimeType()).isEqualTo(mimeType);
            assertThat(media.extension()).isEqualTo(extension);
            assertThat(media.byteSize()).isEqualTo(bytes.length);
            assertThat(media.sha256()).matches("[0-9a-f]{64}");
            assertThat(media.width()).isEqualTo(4);
            assertThat(media.height()).isEqualTo(3);
            assertThat(media.originalFilename()).endsWith('.' + extension);
            assertThat(input.closeCalls()).isOne();
            assertThat(fileCount()).isOne();
        }

        assertThat(input.closeCalls()).isOne();
        assertDirectoryEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"%PDF-1.0\n%%EOF", "%PDF-1.7\n%%EOF\n", "%PDF-2.0\n%%EOF\r\n"})
    void acceptsSupportedPdfEnvelopeVersions(String envelope) throws Exception {
        byte[] bytes = envelope.getBytes(StandardCharsets.US_ASCII);

        try (InspectedMedia media = inspect(
                "document", "application/pdf", bytes.length,
                new CloseCountingInputStream(bytes))) {
            assertThat(media.originalFilename()).isEqualTo("document.pdf");
            assertThat(media.width()).isNull();
            assertThat(media.height()).isNull();
        }
        assertDirectoryEmpty();
    }

    @Test
    void acceptsPdfAtExactThirtyMibLimit() throws Exception {
        byte[] exact = paddedPdf((int) MediaFileInspector.PDF_BYTE_LIMIT);
        try (InspectedMedia media = inspect(
                "max.pdf", "application/pdf", exact.length,
                new CloseCountingInputStream(exact))) {
            assertThat(media.byteSize()).isEqualTo(MediaFileInspector.PDF_BYTE_LIMIT);
        }
        assertDirectoryEmpty();
    }

    @Test
    void acceptsPngAtExactTwentyFiveMibLimit() throws Exception {
        byte[] exact = paddedPng((int) MediaFileInspector.IMAGE_BYTE_LIMIT);
        try (InspectedMedia media = inspect(
                "max.png", "image/png", exact.length,
                new CloseCountingInputStream(exact))) {
            assertThat(media.byteSize()).isEqualTo(MediaFileInspector.IMAGE_BYTE_LIMIT);
            assertThat(media.width()).isEqualTo(4);
        }
        assertDirectoryEmpty();
    }

    @Test
    void acceptsJpegAtExactTwentyFiveMibLimit() throws Exception {
        byte[] exact = paddedJpeg((int) MediaFileInspector.IMAGE_BYTE_LIMIT);
        try (InspectedMedia media = inspect(
                "max.jpeg", "image/jpeg", exact.length,
                new CloseCountingInputStream(exact))) {
            assertThat(media.byteSize()).isEqualTo(MediaFileInspector.IMAGE_BYTE_LIMIT);
            assertThat(media.originalFilename()).isEqualTo("max.jpg");
        }
        assertDirectoryEmpty();
    }

    @ParameterizedTest
    @MethodSource("aboveLimits")
    void rejectsDeclaredSizeOneByteAboveCeilingBeforeReading(
            String mimeType, long size) {
        CloseCountingInputStream input = new CloseCountingInputStream(new byte[] {1});

        DomainException failure = failureOf(() -> inspect(
                "private-name", mimeType, size, input));

        assertFrozen(failure, "MEDIA_TOO_LARGE", HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(input.readCalls()).isZero();
        assertThat(input.closeCalls()).isOne();
        assertDirectoryEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "", " ", "IMAGE/PNG", "image/jpg", "image/png; charset=binary", "image/*", "*/*"
    })
    void rejectsNonCanonicalDeclaredMimeBeforeReading(String mimeType) {
        CloseCountingInputStream input = new CloseCountingInputStream(PDF);

        DomainException failure = failureOf(() -> inspect(
                "private-name", mimeType, PDF.length, input));

        assertFrozen(failure, "MEDIA_TYPE_NOT_ALLOWED", HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(input.readCalls()).isZero();
        assertThat(input.closeCalls()).isOne();
    }

    @Test
    void rejectsNullMimeAndNonpositiveSizesWithFrozenPrecedence() {
        CloseCountingInputStream nullMime = new CloseCountingInputStream(PDF);
        assertFrozen(
                failureOf(() -> inspect("x", null, 0, nullMime)),
                "MEDIA_TYPE_NOT_ALLOWED",
                HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(nullMime.readCalls()).isZero();

        for (long size : List.of(0L, -1L)) {
            CloseCountingInputStream input = new CloseCountingInputStream(PDF);
            assertFrozen(
                    failureOf(() -> inspect("x", "application/pdf", size, input)),
                    "MEDIA_SIZE_MISMATCH",
                    HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(input.readCalls()).isZero();
            assertThat(input.closeCalls()).isOne();
        }
    }

    @Test
    void rejectsEmptyShortLongAndLyingBodySizes() {
        assertCode("MEDIA_SIZE_MISMATCH", () -> inspect(
                "x.pdf", "application/pdf", 1, new CloseCountingInputStream(new byte[0])));
        assertCode("MEDIA_SIZE_MISMATCH", () -> inspect(
                "x.pdf", "application/pdf", PDF.length + 1L,
                new CloseCountingInputStream(PDF)));
        assertCode("MEDIA_SIZE_MISMATCH", () -> inspect(
                "x.pdf", "application/pdf", PDF.length - 1L,
                new CloseCountingInputStream(PDF)));
        byte[] overCeiling = new byte[(int) MediaFileInspector.PDF_BYTE_LIMIT + 1];
        assertCode("MEDIA_SIZE_MISMATCH", () -> inspect(
                "x.pdf", "application/pdf", MediaFileInspector.PDF_BYTE_LIMIT,
                new CloseCountingInputStream(overCeiling)));
        assertDirectoryEmpty();
    }

    @Test
    void makesProgressWhenBulkReadReturnsZeroAndClosesExactlyOnce() throws Exception {
        ZeroProgressInputStream input = new ZeroProgressInputStream(PDF);

        try (InspectedMedia ignored = inspect(
                "document.pdf", "application/pdf", PDF.length, input)) {
            assertThat(input.zeroReads()).isOne();
            assertThat(input.closeCalls()).isOne();
        }
        assertThat(input.closeCalls()).isOne();
        assertDirectoryEmpty();
    }

    @Test
    void sanitizesReadAndCloseFailuresAndPreservesExistingValidationFailure() {
        FailingReadInputStream readFailure = new FailingReadInputStream();
        assertFrozen(
                failureOf(() -> inspect("x.pdf", "application/pdf", 1, readFailure)),
                "MEDIA_UPLOAD_FAILED",
                HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(readFailure.closeCalls()).isOne();

        CloseFailingInputStream closeFailure = new CloseFailingInputStream(PDF);
        assertFrozen(
                failureOf(() -> inspect(
                        "x.pdf", "application/pdf", PDF.length, closeFailure)),
                "MEDIA_UPLOAD_FAILED",
                HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(closeFailure.closeCalls()).isOne();

        CloseFailingInputStream validationAndCloseFailure =
                new CloseFailingInputStream("<svg/>".getBytes(StandardCharsets.US_ASCII));
        assertFrozen(
                failureOf(() -> inspect(
                        "x.png", "image/png", 6, validationAndCloseFailure)),
                "MEDIA_SIGNATURE_NOT_ALLOWED",
                HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(validationAndCloseFailure.closeCalls()).isOne();
        assertDirectoryEmpty();
    }

    @Test
    void sanitizesDependencyControlledDomainExceptionFromOwnedInput() {
        DomainException dependencyFailure = new DomainException(
                "DEPENDENCY_SECRET",
                HttpStatus.I_AM_A_TEAPOT,
                Map.of("secret", "value"));
        dependencyFailure.initCause(new IllegalStateException("cause secret"));
        dependencyFailure.addSuppressed(new IllegalStateException("suppressed secret"));
        CloseCountingInputStream input = new CloseCountingInputStream(PDF) {
            @Override
            public int read(byte[] bytes, int offset, int length) {
                throw dependencyFailure;
            }
        };

        DomainException failure = failureOf(() -> inspect(
                "x.pdf", "application/pdf", PDF.length, input));

        assertFrozen(failure, "MEDIA_UPLOAD_FAILED", HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(input.closeCalls()).isOne();
        assertDirectoryEmpty();
    }

    @ParameterizedTest
    @MethodSource("spoofedBodies")
    void rejectsSvgHtmlAndExecutableSpoofing(byte[] bytes) {
        assertCode("MEDIA_SIGNATURE_NOT_ALLOWED", () -> inspect(
                "spoof.png", "image/png", bytes.length,
                new CloseCountingInputStream(bytes)));
        assertDirectoryEmpty();
    }

    @ParameterizedTest
    @MethodSource("supportedMimeMismatches")
    void rejectsSupportedSignatureMimeMismatch(byte[] bytes, String declaredMime) {
        assertCode("MEDIA_MIME_MISMATCH", () -> inspect(
                "mismatch", declaredMime, bytes.length,
                new CloseCountingInputStream(bytes)));
        assertDirectoryEmpty();
    }

    @ParameterizedTest
    @MethodSource("corruptJpegs")
    void rejectsTruncatedCorruptAndPolyglotJpeg(byte[] bytes) {
        assertCode("MEDIA_CORRUPT", () -> inspect(
                "photo.jpg", "image/jpeg", bytes.length,
                new CloseCountingInputStream(bytes)));
        assertDirectoryEmpty();
    }

    @ParameterizedTest
    @MethodSource("corruptPngs")
    void rejectsBadPngCrcOrderIendApngAndPolyglots(byte[] bytes) {
        assertCode("MEDIA_CORRUPT", () -> inspect(
                "photo.png", "image/png", bytes.length,
                new CloseCountingInputStream(bytes)));
        assertDirectoryEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "%PDF-1.8\n%%EOF", "%PDF-2.1\n%%EOF", "%PDF-X.Y\n%%EOF",
        "%PDF-1.7\n", "%PDF-1.7\n%%EOF<script>"
    })
    void rejectsUnsupportedTruncatedAndTrailingPdf(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        assertCode("MEDIA_CORRUPT", () -> inspect(
                "document.pdf", "application/pdf", bytes.length,
                new CloseCountingInputStream(bytes)));
        assertDirectoryEmpty();
    }

    @Test
    void rejectsNearLimitPdfWhitespaceWithoutPerByteReverseIo() {
        byte[] bytes = new byte[(int) MediaFileInspector.PDF_BYTE_LIMIT];
        Arrays.fill(bytes, (byte) ' ');
        byte[] header = "%PDF-1.7".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(header, 0, bytes, 0, header.length);

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            assertCode("MEDIA_CORRUPT", () -> inspect(
                    "document.pdf", "application/pdf", bytes.length,
                    new CloseCountingInputStream(bytes)));
            assertDirectoryEmpty();
        });
    }

    @Test
    void dimensionAndPixelArithmeticIsExactAndOverflowSafe() {
        assertThatCode(() -> MediaFileInspector.validateDimensions(10_000, 8_000))
                .doesNotThrowAnyException();
        assertCode("MEDIA_PIXEL_LIMIT_EXCEEDED", () ->
                MediaFileInspector.validateDimensions(10_001, 8_000));
        assertCode("MEDIA_DIMENSIONS_INVALID", () ->
                MediaFileInspector.validateDimensions(0, 1));
        assertCode("MEDIA_DIMENSIONS_INVALID", () ->
                MediaFileInspector.validateDimensions((long) Integer.MAX_VALUE + 1, 1));
        assertCode("MEDIA_PIXEL_LIMIT_EXCEEDED", () ->
                MediaFileInspector.validateDimensions(Integer.MAX_VALUE, Integer.MAX_VALUE));
    }

    @ParameterizedTest
    @MethodSource("filenameCases")
    void normalizesPathsUnicodeControlsBlankNamesAndSuffixes(
            String input, String extension, String expected) {
        assertThat(MediaFileInspector.normalizeFilename(input, extension)).isEqualTo(expected);
    }

    @Test
    void capsFilenameByUnicodeCodePointsWhilePreservingCanonicalSuffix() {
        String normalized = MediaFileInspector.normalizeFilename(
                "\ud83d\ude00".repeat(300) + ".exe", "png");

        assertThat(normalized.codePointCount(0, normalized.length())).isEqualTo(255);
        assertThat(normalized).endsWith(".png");
        assertThat(normalized).doesNotContain(".exe");
    }

    @Test
    void commandAndInspectionToStringNeverRevealRequestOrPathData() throws Exception {
        UploadMediaCommand command = new UploadMediaCommand(
                "private/secret.pdf", "application/pdf", PDF.length,
                new CloseCountingInputStream(PDF));
        assertThat(command.toString()).isEqualTo("UploadMediaCommand[redacted]");
        assertThat(command.toString()).doesNotContain("private", "secret", "InputStream");

        try (InspectedMedia media = new MediaFileInspector(temporaryDirectory).inspect(command)) {
            assertThat(media.toString()).isEqualTo("InspectedMedia[redacted]");
            assertThat(media.toString()).doesNotContain(temporaryDirectory.toString());
        }
        assertDirectoryEmpty();
    }

    private InspectedMedia inspect(
            String filename, String mimeType, long size, InputStream input) {
        return new MediaFileInspector(temporaryDirectory)
                .inspect(new UploadMediaCommand(filename, mimeType, size, input));
    }

    private static Stream<Arguments> validImages() throws IOException {
        return Stream.of(
                Arguments.of("baseline.jpeg", "image/jpeg", image("jpeg", false, false), "jpg"),
                Arguments.of("progressive.jpg", "image/jpeg", image("jpeg", true, false), "jpg"),
                Arguments.of("alpha.png", "image/png", image("png", false, true), "png"),
                Arguments.of("interlaced.png", "image/png", image("png", true, true), "png"));
    }

    private static Stream<Arguments> aboveLimits() {
        return Stream.of(
                Arguments.of("image/jpeg", MediaFileInspector.IMAGE_BYTE_LIMIT + 1),
                Arguments.of("image/png", MediaFileInspector.IMAGE_BYTE_LIMIT + 1),
                Arguments.of("application/pdf", MediaFileInspector.PDF_BYTE_LIMIT + 1));
    }

    private static Stream<byte[]> spoofedBodies() {
        return Stream.of(
                "<svg xmlns='http://www.w3.org/2000/svg'></svg>".getBytes(StandardCharsets.UTF_8),
                "<!doctype html><script>alert(1)</script>".getBytes(StandardCharsets.UTF_8),
                new byte[] {'M', 'Z', (byte) 0x90, 0, 3, 0});
    }

    private static Stream<Arguments> supportedMimeMismatches() throws IOException {
        return Stream.of(
                Arguments.of(image("jpeg", false, false), "image/png"),
                Arguments.of(image("png", false, true), "application/pdf"),
                Arguments.of(PDF, "image/jpeg"));
    }

    private static Stream<byte[]> corruptJpegs() throws IOException {
        byte[] jpeg = image("jpeg", false, false);
        byte[] truncated = Arrays.copyOf(jpeg, jpeg.length - 1);
        byte[] trailing = Arrays.copyOf(jpeg, jpeg.length + 3);
        trailing[jpeg.length] = '<';
        trailing[jpeg.length + 1] = 'x';
        trailing[jpeg.length + 2] = '>';
        byte[] concatenated = concat(jpeg, jpeg);
        byte[] invalidLength = jpeg.clone();
        invalidLength[4] = 0;
        invalidLength[5] = 1;
        byte[] invalidStuffing = insertBeforeEoi(jpeg, new byte[] {(byte) 0xff, (byte) 0xff, 0});
        return Stream.of(truncated, trailing, concatenated, invalidLength, invalidStuffing);
    }

    private static Stream<byte[]> corruptPngs() throws IOException {
        byte[] png = image("png", false, true);
        byte[] badCrc = png.clone();
        int idat = chunkOffset(badCrc, "IDAT");
        int idatLength = readInt(badCrc, idat);
        badCrc[idat + 8 + idatLength] ^= 1;
        byte[] missingIend = Arrays.copyOf(png, png.length - 12);
        byte[] badOrder = insertBeforeIend(png, chunk("PLTE", new byte[] {0, 0, 0}));
        byte[] apng = insertAfterIhdr(png, chunk("acTL", new byte[8]));
        byte[] badReservedBit = insertBeforeIend(png, chunk("rust", new byte[0]));
        byte[] trailing = concat(png, new byte[] {'<', 'x', '>'});
        byte[] concatenated = concat(png, png);
        return Stream.of(
                badCrc, missingIend, badOrder, apng, badReservedBit, trailing, concatenated);
    }

    private static Stream<Arguments> filenameCases() {
        return Stream.of(
                Arguments.of("C:\\folder\\photo.jpeg", "jpg", "photo.jpg"),
                Arguments.of("/var/tmp/../../photo.exe", "png", "photo.png"),
                Arguments.of("re\u0301sume.JPG", "jpg", "r\u00e9sume.jpg"),
                Arguments.of("a\u0000b\u202Ename.pdf", "pdf", "abname.pdf"),
                Arguments.of("   ...   ", "pdf", "upload.pdf"),
                Arguments.of(".", "jpg", "upload.jpg"),
                Arguments.of("..", "png", "upload.png"),
                Arguments.of("misleading.svg", "pdf", "misleading.pdf"),
                Arguments.of("extensionless", "jpg", "extensionless.jpg"),
                Arguments.of(null, "pdf", "upload.pdf"));
    }

    private static byte[] image(String format, boolean progressive, boolean alpha)
            throws IOException {
        int type = alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage image = new BufferedImage(4, 3, type);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int opacity = alpha ? 64 + x * 40 : 255;
                image.setRGB(x, y, new Color(x * 50, y * 70, 100, opacity).getRGB());
            }
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName(format).next();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(imageOutput);
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            if (parameters.canWriteProgressive()) {
                parameters.setProgressiveMode(progressive
                        ? ImageWriteParam.MODE_DEFAULT
                        : ImageWriteParam.MODE_DISABLED);
            }
            writer.write(null, new IIOImage(image, null, null), parameters);
        } finally {
            writer.dispose();
            image.flush();
        }
        return output.toByteArray();
    }

    private static byte[] paddedPdf(int targetSize) {
        byte[] bytes = new byte[targetSize];
        Arrays.fill(bytes, (byte) ' ');
        byte[] header = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);
        byte[] eof = "\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(header, 0, bytes, 0, header.length);
        System.arraycopy(eof, 0, bytes, bytes.length - eof.length, eof.length);
        return bytes;
    }

    private static byte[] paddedPng(int targetSize) throws IOException {
        byte[] png = image("png", false, true);
        int insertedSize = targetSize - png.length;
        if (insertedSize < 12) {
            throw new IllegalArgumentException("PNG target is too small");
        }
        int dataLength = insertedSize - 12;
        ByteArrayOutputStream output = new ByteArrayOutputStream(targetSize);
        int iend = png.length - 12;
        output.write(png, 0, iend);
        writeInt(output, dataLength);
        byte[] type = "ruSt".getBytes(StandardCharsets.US_ASCII);
        output.write(type);
        CRC32 crc = new CRC32();
        crc.update(type);
        byte[] zeros = new byte[8192];
        int remaining = dataLength;
        while (remaining > 0) {
            int count = Math.min(zeros.length, remaining);
            output.write(zeros, 0, count);
            crc.update(zeros, 0, count);
            remaining -= count;
        }
        writeInt(output, (int) crc.getValue());
        output.write(png, iend, png.length - iend);
        return output.toByteArray();
    }

    private static byte[] paddedJpeg(int targetSize) throws IOException {
        byte[] jpeg = image("jpeg", false, false);
        int padding = targetSize - jpeg.length;
        if (padding < 4) {
            throw new IllegalArgumentException("JPEG target is too small");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(targetSize);
        output.write(jpeg, 0, jpeg.length - 2);
        int remaining = padding;
        while (remaining > 0) {
            int total = Math.min(65_537, remaining);
            int leftover = remaining - total;
            if (leftover > 0 && leftover < 4) {
                total -= 4 - leftover;
            }
            output.write(0xff);
            output.write(0xfe);
            int segmentLength = total - 2;
            output.write((segmentLength >>> 8) & 0xff);
            output.write(segmentLength & 0xff);
            output.write(new byte[total - 4]);
            remaining -= total;
        }
        output.write(0xff);
        output.write(0xd9);
        return output.toByteArray();
    }

    private static byte[] chunk(String type, byte[] data) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(data.length + 12);
        writeInt(output, data.length);
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        output.write(typeBytes);
        output.write(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        writeInt(output, (int) crc.getValue());
        return output.toByteArray();
    }

    private static byte[] insertBeforeIend(byte[] png, byte[] chunk) {
        int iend = png.length - 12;
        return concat(Arrays.copyOf(png, iend), chunk, Arrays.copyOfRange(png, iend, png.length));
    }

    private static byte[] insertAfterIhdr(byte[] png, byte[] chunk) {
        int afterHeader = 8 + 12 + readInt(png, 8);
        return concat(
                Arrays.copyOf(png, afterHeader),
                chunk,
                Arrays.copyOfRange(png, afterHeader, png.length));
    }

    private static byte[] insertBeforeEoi(byte[] jpeg, byte[] data) {
        return concat(
                Arrays.copyOf(jpeg, jpeg.length - 2),
                data,
                Arrays.copyOfRange(jpeg, jpeg.length - 2, jpeg.length));
    }

    private static int chunkOffset(byte[] png, String wanted) {
        int offset = 8;
        while (offset + 12 <= png.length) {
            int length = readInt(png, offset);
            String type = new String(png, offset + 4, 4, StandardCharsets.US_ASCII);
            if (wanted.equals(type)) {
                return offset;
            }
            offset += 12 + length;
        }
        throw new IllegalArgumentException("PNG chunk missing: " + wanted);
    }

    private static int readInt(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes, offset, 4).getInt();
    }

    private static void writeInt(ByteArrayOutputStream output, int value) {
        output.write((value >>> 24) & 0xff);
        output.write((value >>> 16) & 0xff);
        output.write((value >>> 8) & 0xff);
        output.write(value & 0xff);
    }

    private static byte[] concat(byte[]... values) {
        int size = Arrays.stream(values).mapToInt(value -> value.length).sum();
        byte[] combined = new byte[size];
        int offset = 0;
        for (byte[] value : values) {
            System.arraycopy(value, 0, combined, offset, value.length);
            offset += value.length;
        }
        return combined;
    }

    private static DomainException failureOf(ThrowingOperation operation) {
        Throwable failure = catchThrowable(operation::run);
        assertThat(failure).isExactlyInstanceOf(DomainException.class);
        return (DomainException) failure;
    }

    private static void assertCode(String expectedCode, ThrowingOperation operation) {
        assertThat(failureOf(operation).code()).isEqualTo(expectedCode);
    }

    private static void assertFrozen(
            DomainException failure, String code, HttpStatus status) {
        assertThat(failure.code()).isEqualTo(code);
        assertThat(failure.status()).isEqualTo(status);
        assertThat(failure.fieldErrors()).isEmpty();
        assertThat(failure).hasNoCause();
        assertThat(failure.getSuppressed()).isEmpty();
        assertThat(failure.getMessage()).isEqualTo(code);
    }

    private long fileCount() throws IOException {
        try (Stream<Path> files = Files.list(temporaryDirectory)) {
            return files.count();
        }
    }

    private void assertDirectoryEmpty() {
        assertThatCode(() -> assertThat(fileCount()).isZero()).doesNotThrowAnyException();
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run() throws Exception;
    }

    private static class CloseCountingInputStream extends FilterInputStream {
        private final AtomicInteger readCalls = new AtomicInteger();
        private final AtomicInteger closeCalls = new AtomicInteger();

        private CloseCountingInputStream(byte[] bytes) {
            super(new ByteArrayInputStream(bytes));
        }

        @Override
        public int read() throws IOException {
            readCalls.incrementAndGet();
            return super.read();
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            readCalls.incrementAndGet();
            return super.read(bytes, offset, length);
        }

        @Override
        public void close() throws IOException {
            closeCalls.incrementAndGet();
            super.close();
        }

        int readCalls() {
            return readCalls.get();
        }

        int closeCalls() {
            return closeCalls.get();
        }
    }

    private static final class ZeroProgressInputStream extends CloseCountingInputStream {
        private final AtomicInteger zeroReads = new AtomicInteger();
        private boolean returnedZero;

        private ZeroProgressInputStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (!returnedZero) {
                returnedZero = true;
                zeroReads.incrementAndGet();
                return 0;
            }
            return super.read(bytes, offset, length);
        }

        private int zeroReads() {
            return zeroReads.get();
        }
    }

    private static final class FailingReadInputStream extends InputStream {
        private int closeCalls;

        @Override
        public int read() throws IOException {
            throw new IOException("secret read failure");
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            throw new IOException("secret bulk read failure");
        }

        @Override
        public void close() {
            closeCalls++;
        }

        private int closeCalls() {
            return closeCalls;
        }
    }

    private static final class CloseFailingInputStream extends CloseCountingInputStream {
        private CloseFailingInputStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws IOException {
            super.close();
            throw new IOException("secret close failure");
        }
    }
}
