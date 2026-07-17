package xyz.yychainsaw.portfolio.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.yychainsaw.portfolio.media.domain.MediaStatus;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRecord;

class ImageVariantGeneratorTest {
    private static final UUID ASSET_ID =
            UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final Instant NOW = Instant.parse("2026-07-16T00:00:00Z");

    @TempDir Path temporaryDirectory;

    @Test
    void targetWidthsAreAscendingDeduplicatedAndNeverUpscale() {
        assertThat(ImageVariantGenerator.targetWidths(400, 200, 400))
                .containsExactly(400);
        assertThat(ImageVariantGenerator.targetWidths(640, 360, 640))
                .containsExactly(640);
        assertThat(ImageVariantGenerator.targetWidths(800, 450, 800))
                .containsExactly(640, 800);
        assertThat(ImageVariantGenerator.targetWidths(1920, 1080, 1920))
                .containsExactly(640, 1280, 1920);
        assertThat(ImageVariantGenerator.targetWidths(5000, 2813, 5000))
                .containsExactly(640, 1280, 1920, 2560, 3840);
        assertThat(ImageVariantGenerator.targetWidths(5000, 4000, 5000))
                .containsExactly(640, 1280, 1920, 2560, 3840);
        assertThatThrownBy(() ->
                        ImageVariantGenerator.targetWidths(5000, 16000, 2500))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("MEDIA_FINALIZATION_FAILED")
                .hasNoCause();
    }

    @Test
    void aspectRatioUsesOverflowSafeHalfUpRoundingAndAtLeastOnePixel() {
        assertThat(ImageVariantGenerator.scaledHeight(3, 2, 2)).isEqualTo(1);
        assertThat(ImageVariantGenerator.scaledHeight(4, 3, 2)).isEqualTo(2);
        assertThat(ImageVariantGenerator.scaledHeight(Integer.MAX_VALUE, 1, 3840))
                .isOne();
        assertThat(ImageVariantGenerator.scaledHeight(1, Integer.MAX_VALUE, 1))
                .isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void decodeAndOutputBudgetsSupportEightKWithoutEverUpscaling() {
        assertThat(ImageVariantGenerator.sourceSubsampling(8000, 4000)).isOne();
        assertThat(ImageVariantGenerator.sourceSubsampling(8001, 4000)).isEqualTo(2);
        assertThat(ImageVariantGenerator.sourceSubsampling(7680, 4320)).isEqualTo(2);
        assertThat(ImageVariantGenerator.targetWidths(7680, 4320, 3840))
                .containsExactly(640, 1280, 1920, 2560, 3840);
        assertThatThrownBy(() ->
                        ImageVariantGenerator.targetWidths(3840, 20833, 1920))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("MEDIA_FINALIZATION_FAILED")
                .hasNoCause();
        assertThatThrownBy(() ->
                        ImageVariantGenerator.targetWidths(6000, 6000, 3000))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("MEDIA_FINALIZATION_FAILED")
                .hasNoCause();

        assertThatThrownBy(() ->
                        ImageVariantGenerator.targetWidths(1, 80_000_000, 1))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("MEDIA_FINALIZATION_FAILED")
                .hasNoCause();
    }

    @Test
    void pngVariantsAreNewArgbImagesThatPreserveAlphaAndOwnTemporaryFiles()
            throws Exception {
        Path source = writeImage("png", 800, 400, true);
        MediaAssetRecord asset = asset(source, "image/png", 800, 400);
        Semaphore permit = new Semaphore(1, true);
        ImageVariantGenerator generator =
                new ImageVariantGenerator(temporaryDirectory, permit);

        try (VerifiedMediaObject original = verified(source, asset)) {
            assertThat(original.toString())
                    .doesNotContain(original.objectKey())
                    .doesNotContain(original.sha256())
                    .doesNotContain(source.toString());
            List<GeneratedVariant> variants = generator.generate(original, asset);
            try {
                assertThat(variants)
                        .extracting(GeneratedVariant::variantName)
                        .containsExactly("w640", "w800");
                assertThat(variants)
                        .extracting(GeneratedVariant::format)
                        .containsOnly("PNG");
                assertThat(variants)
                        .extracting(GeneratedVariant::mimeType)
                        .containsOnly("image/png");
                assertThat(variants)
                        .extracting(GeneratedVariant::width)
                        .containsExactly(640, 800);
                assertThat(variants)
                        .extracting(GeneratedVariant::height)
                        .containsExactly(320, 400);

                BufferedImage decoded = decode(variants.get(0));
                assertThat(decoded.getColorModel().hasAlpha()).isTrue();
                assertThat((decoded.getRGB(320, 160) >>> 24) & 0xff).isBetween(74, 80);
                assertThat(variants.get(0).toString())
                        .doesNotContain("portfolio-media-variant-")
                        .doesNotContain(temporaryDirectory.toString());
                assertExactDigest(variants.get(0));
            } finally {
                variants.forEach(GeneratedVariant::close);
            }
        }

        assertThat(permit.availablePermits()).isOne();
        assertThat(filesExcept(temporaryDirectory, source)).isEmpty();
    }

    @Test
    void pngReencodingStripsTextExifXmpAndIccMetadata() throws Exception {
        Path source = writeImage("png", 640, 320, true);
        addPngMetadata(source);
        MediaAssetRecord asset = asset(source, "image/png", 640, 320);
        ImageVariantGenerator generator = new ImageVariantGenerator(temporaryDirectory);

        try (VerifiedMediaObject original = verified(source, asset)) {
            List<GeneratedVariant> variants = generator.generate(original, asset);
            try {
                assertThat(variants).hasSize(1);
                byte[] encoded = readAll(variants.get(0));
                assertThat(pngChunkTypes(encoded))
                        .containsOnly("IHDR", "IDAT", "IEND")
                        .doesNotContain("tEXt", "iTXt", "eXIf", "iCCP");
                assertThat(new String(encoded, StandardCharsets.ISO_8859_1))
                        .doesNotContain("SECRET_");
            } finally {
                variants.forEach(GeneratedVariant::close);
            }
        }
    }

    @Test
    void jpegUsesExplicitQualityAndNonProgressiveRgbEncoding() throws Exception {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("JPEG").next();
        try {
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            ImageVariantGenerator.configureJpeg(parameters);
            assertThat(parameters.getCompressionMode())
                    .isEqualTo(ImageWriteParam.MODE_EXPLICIT);
            assertThat(parameters.getCompressionQuality()).isEqualTo(0.92f);
            assertThat(parameters.getProgressiveMode())
                    .isEqualTo(ImageWriteParam.MODE_DISABLED);
        } finally {
            writer.dispose();
        }

        Path source = writeImage("jpg", 640, 320, false);
        addJpegMetadata(source);
        MediaAssetRecord asset = asset(source, "image/jpeg", 640, 320);
        ImageVariantGenerator generator = new ImageVariantGenerator(temporaryDirectory);

        try (VerifiedMediaObject original = verified(source, asset)) {
            List<GeneratedVariant> variants = generator.generate(original, asset);
            try {
                assertThat(variants).hasSize(1);
                GeneratedVariant variant = variants.get(0);
                assertThat(variant.variantName()).isEqualTo("w640");
                assertThat(variant.format()).isEqualTo("JPEG");
                assertThat(variant.mimeType()).isEqualTo("image/jpeg");
                assertThat(decode(variant).getColorModel().hasAlpha()).isFalse();
                byte[] encoded = readAll(variant);
                assertThat(hasJpegMarker(encoded, 0xc0)).isTrue();
                assertThat(hasJpegMarker(encoded, 0xc2)).isFalse();
                assertThat(hasJpegMarker(encoded, 0xe1)).isFalse();
                assertThat(hasJpegMarker(encoded, 0xe2)).isFalse();
                assertThat(hasJpegMarker(encoded, 0xfe)).isFalse();
                assertThat(new String(encoded, java.nio.charset.StandardCharsets.ISO_8859_1))
                        .doesNotContain("SECRET_");
                assertExactDigest(variant);
            } finally {
                variants.forEach(GeneratedVariant::close);
            }
        }
    }

    @Test
    void persistedDimensionMismatchFailsClosedAndReleasesFairPermit() throws Exception {
        Path source = writeImage("png", 9, 7, true);
        MediaAssetRecord asset = asset(source, "image/png", 8, 7);
        Semaphore permit = new Semaphore(1, true);
        ImageVariantGenerator generator =
                new ImageVariantGenerator(temporaryDirectory, permit);

        try (VerifiedMediaObject original = verified(source, asset)) {
            assertThatThrownBy(() -> generator.generate(original, asset))
                    .isExactlyInstanceOf(IllegalStateException.class)
                    .hasMessage("MEDIA_FINALIZATION_FAILED")
                    .hasNoCause();
        }

        assertThat(permit.availablePermits()).isOne();
        assertThat(filesExcept(temporaryDirectory, source)).isEmpty();
    }

    @Test
    void interruptWhileWaitingForPermitPropagatesAndNeverReleasesAnUnownedPermit()
            throws Exception {
        Path source = writeImage("png", 9, 7, true);
        MediaAssetRecord asset = asset(source, "image/png", 9, 7);
        Semaphore unavailable = new Semaphore(0, true);
        ImageVariantGenerator generator =
                new ImageVariantGenerator(temporaryDirectory, unavailable);
        java.util.concurrent.atomic.AtomicReference<InterruptResult> result =
                new java.util.concurrent.atomic.AtomicReference<>();

        Thread worker = new Thread(() -> {
            try (VerifiedMediaObject original = verifiedUnchecked(source, asset)) {
                generator.generate(original, asset);
                result.set(new InterruptResult(null, Thread.currentThread().isInterrupted()));
            } catch (Throwable failure) {
                result.set(new InterruptResult(failure, Thread.currentThread().isInterrupted()));
            }
        }, "variant-interrupt-test");
        worker.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!unavailable.hasQueuedThreads() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(unavailable.hasQueuedThreads()).isTrue();

        worker.interrupt();
        worker.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(worker.isAlive()).isFalse();
        assertThat(result.get().failure())
                .isExactlyInstanceOf(InterruptedException.class)
                .hasNoCause();
        assertThat(result.get().interrupted()).isTrue();
        assertThat(unavailable.availablePermits()).isZero();
    }

    @Test
    void productionStreamingApiOwnsOnlyOneEncodedVariantAtATime() throws Exception {
        Path source = writeImage("png", 800, 400, true);
        MediaAssetRecord asset = asset(source, "image/png", 800, 400);
        ImageVariantGenerator generator = new ImageVariantGenerator(temporaryDirectory);
        java.util.concurrent.atomic.AtomicReference<GeneratedVariant> previous =
                new java.util.concurrent.atomic.AtomicReference<>();
        List<String> names = new java.util.ArrayList<>();

        try (VerifiedMediaObject original = verified(source, asset)) {
            generator.generateEach(original, asset, current -> {
                GeneratedVariant prior = previous.getAndSet(current);
                if (prior != null) {
                    assertThatThrownBy(prior::openInput)
                            .isExactlyInstanceOf(IOException.class);
                }
                names.add(current.variantName());
                try {
                    assertThat(filesExcept(temporaryDirectory, source)).hasSize(1);
                } catch (IOException exception) {
                    throw new AssertionError(exception);
                }
            });
        }

        assertThat(names).containsExactly("w640", "w800");
        assertThatThrownBy(previous.get()::openInput)
                .isExactlyInstanceOf(IOException.class);
        assertThat(filesExcept(temporaryDirectory, source)).isEmpty();
    }

    @Test
    void productionStreamingRetriesARefusedVariantDeleteAndStillFailsClosed()
            throws Exception {
        Path source = writeImage("png", 640, 320, true);
        MediaAssetRecord asset = asset(source, "image/png", 640, 320);
        Semaphore permit = new Semaphore(1, true);
        java.util.concurrent.atomic.AtomicInteger deletes =
                new java.util.concurrent.atomic.AtomicInteger();
        ImageVariantGenerator generator = new ImageVariantGenerator(
                temporaryDirectory,
                permit,
                (path, name, format, mime, size, width, height, sha) ->
                        new GeneratedVariant(
                                path,
                                name,
                                format,
                                mime,
                                size,
                                width,
                                height,
                                sha,
                                owned -> {
                                    if (deletes.incrementAndGet() == 1) {
                                        throw new IllegalStateException(
                                                "MEDIA_FINALIZATION_FAILED");
                                    }
                                    try {
                                        Files.deleteIfExists(owned);
                                    } catch (IOException exception) {
                                        throw new IllegalStateException(
                                                "MEDIA_FINALIZATION_FAILED");
                                    }
                                }));

        try (VerifiedMediaObject original = verified(source, asset)) {
            assertThatThrownBy(() -> generator.generateEach(original, asset, ignored -> {}))
                    .isExactlyInstanceOf(IllegalStateException.class)
                    .hasMessage("MEDIA_FINALIZATION_FAILED")
                    .hasNoCause();
        }

        assertThat(deletes).hasValue(2);
        assertThat(permit.availablePermits()).isOne();
        assertThat(filesExcept(temporaryDirectory, source)).isEmpty();
    }

    @Test
    void sourceOwnerDeleteIsRetriedWithoutLeakingTheVerifiedTemporary()
            throws Exception {
        Path source = writeImage("png", 640, 320, true);
        MediaAssetRecord asset = asset(source, "image/png", 640, 320);
        Semaphore permit = new Semaphore(1, true);
        java.util.concurrent.atomic.AtomicInteger deletes =
                new java.util.concurrent.atomic.AtomicInteger();
        VerifiedMediaObject original = new VerifiedMediaObject(
                source,
                asset.objectKey(),
                asset.mimeType(),
                asset.byteSize(),
                asset.sha256(),
                true,
                owned -> {
                    if (deletes.incrementAndGet() == 1) {
                        throw new IllegalStateException("MEDIA_FINALIZATION_FAILED");
                    }
                    try {
                        Files.deleteIfExists(owned);
                    } catch (IOException exception) {
                        throw new IllegalStateException("MEDIA_FINALIZATION_FAILED");
                    }
                });
        ImageVariantGenerator generator =
                new ImageVariantGenerator(temporaryDirectory, permit);

        assertThatThrownBy(() -> generator.generate(original, asset))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("MEDIA_FINALIZATION_FAILED")
                .hasNoCause();

        assertThat(deletes).hasValue(2);
        assertThat(source).doesNotExist();
        assertThat(permit.availablePermits()).isOne();
        assertThat(filesExcept(temporaryDirectory, source)).isEmpty();
    }

    @Test
    void temporaryOwnersRetryDeletionAfterTheFirstCloseFails() throws Exception {
        Path generatedPath = Files.write(
                temporaryDirectory.resolve("generated-retry.png"), new byte[] {1});
        java.util.concurrent.atomic.AtomicInteger generatedDeletes =
                new java.util.concurrent.atomic.AtomicInteger();
        GeneratedVariant generated = new GeneratedVariant(
                generatedPath,
                "w1",
                "PNG",
                "image/png",
                1,
                1,
                1,
                "a".repeat(64),
                path -> {
                    if (generatedDeletes.incrementAndGet() == 1) {
                        throw new IllegalStateException("MEDIA_FINALIZATION_FAILED");
                    }
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException exception) {
                        throw new IllegalStateException("MEDIA_FINALIZATION_FAILED");
                    }
                });

        assertThatThrownBy(generated::close)
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("MEDIA_FINALIZATION_FAILED");
        try (InputStream ignored = generated.openInput()) {
            assertThat(ignored.read()).isEqualTo(1);
        }
        generated.close();
        generated.close();
        assertThat(generatedDeletes).hasValue(2);
        assertThat(generatedPath).doesNotExist();

        Path verifiedPath = Files.write(
                temporaryDirectory.resolve("verified-retry.png"), new byte[] {2});
        java.util.concurrent.atomic.AtomicInteger verifiedDeletes =
                new java.util.concurrent.atomic.AtomicInteger();
        VerifiedMediaObject verified = new VerifiedMediaObject(
                verifiedPath,
                "originals/id/hash.png",
                "image/png",
                1,
                "b".repeat(64),
                true,
                path -> {
                    if (verifiedDeletes.incrementAndGet() == 1) {
                        throw new IllegalStateException("MEDIA_FINALIZATION_FAILED");
                    }
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException exception) {
                        throw new IllegalStateException("MEDIA_FINALIZATION_FAILED");
                    }
                });

        assertThatThrownBy(verified::close)
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("MEDIA_FINALIZATION_FAILED");
        verified.close();
        verified.close();
        assertThat(verifiedDeletes).hasValue(2);
        assertThat(verifiedPath).doesNotExist();
    }

    @Test
    void fileBackedImageOutputAbortsBeforeCrossingItsEncodedByteBudget()
            throws Exception {
        Path target = MediaTemporaryFiles.create(temporaryDirectory, ".png");
        try {
            try (BoundedFileImageOutputStream output =
                    new BoundedFileImageOutputStream(target, 4)) {
                output.write(new byte[] {1, 2, 3, 4});
                assertThatThrownBy(() -> output.write(5))
                        .isExactlyInstanceOf(IOException.class)
                        .hasMessage("encoded media exceeds its byte budget");
            }
            assertThat(Files.readAllBytes(target)).containsExactly(1, 2, 3, 4);
        } finally {
            MediaTemporaryFiles.deleteBestEffort(target);
        }
    }

    private Path writeImage(String format, int width, int height, boolean alpha)
            throws IOException {
        BufferedImage image = new BufferedImage(
                width,
                height,
                alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(alpha ? new Color(10, 20, 30, 77) : new Color(40, 80, 120));
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        Path path = temporaryDirectory.resolve("source-" + format + '-' + width + '.' + format);
        assertThat(ImageIO.write(image, format, path.toFile())).isTrue();
        return path;
    }

    private static VerifiedMediaObject verified(Path source, MediaAssetRecord asset)
            throws IOException {
        return new VerifiedMediaObject(
                source,
                asset.objectKey(),
                asset.mimeType(),
                Files.size(source),
                sha256(Files.readAllBytes(source)),
                false);
    }

    private static VerifiedMediaObject verifiedUnchecked(
            Path source, MediaAssetRecord asset) {
        try {
            return verified(source, asset);
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static MediaAssetRecord asset(
            Path source, String mimeType, int width, int height) throws IOException {
        String sha256 = sha256(Files.readAllBytes(source));
        return new MediaAssetRecord(
                ASSET_ID,
                StorageProvider.LOCAL,
                null,
                null,
                MediaObjectKeys.originalKey(ASSET_ID, sha256, mimeType),
                "source",
                mimeType,
                Files.size(source),
                width,
                height,
                sha256,
                MediaStatus.PROCESSING,
                null,
                0,
                NOW,
                NOW);
    }

    private static BufferedImage decode(GeneratedVariant variant) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(readAll(variant)));
    }

    private static byte[] readAll(GeneratedVariant variant) throws IOException {
        try (InputStream input = variant.openInput()) {
            return input.readAllBytes();
        }
    }

    private static void assertExactDigest(GeneratedVariant variant) throws Exception {
        byte[] bytes = readAll(variant);
        assertThat(variant.byteSize()).isEqualTo(bytes.length);
        assertThat(variant.sha256()).isEqualTo(sha256(bytes));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static boolean hasJpegMarker(byte[] bytes, int marker) {
        for (int index = 0; index + 1 < bytes.length; index++) {
            if ((bytes[index] & 0xff) == 0xff && (bytes[index + 1] & 0xff) == marker) {
                return true;
            }
        }
        return false;
    }

    private static void addJpegMetadata(Path path) throws IOException {
        byte[] jpeg = Files.readAllBytes(path);
        ByteArrayOutputStream output = new ByteArrayOutputStream(jpeg.length + 128);
        output.write(jpeg, 0, 2);
        writeJpegSegment(output, 0xe1, "Exif\0\0SECRET_EXIF".getBytes(
                java.nio.charset.StandardCharsets.ISO_8859_1));
        writeJpegSegment(output, 0xe1, "http://ns.adobe.com/xap/1.0/\0SECRET_XMP".getBytes(
                java.nio.charset.StandardCharsets.ISO_8859_1));
        writeJpegSegment(output, 0xe2, "VENDOR_APP2_SECRET_ICC".getBytes(
                java.nio.charset.StandardCharsets.ISO_8859_1));
        writeJpegSegment(output, 0xfe, "SECRET_COMMENT".getBytes(
                java.nio.charset.StandardCharsets.ISO_8859_1));
        output.write(jpeg, 2, jpeg.length - 2);
        Files.write(path, output.toByteArray());
    }

    private static void addPngMetadata(Path path) throws IOException {
        byte[] png = Files.readAllBytes(path);
        int afterHeader = 8 + 12 + readInt(png, 8);
        ByteArrayOutputStream profile = new ByteArrayOutputStream();
        try (DeflaterOutputStream compressed = new DeflaterOutputStream(profile)) {
            compressed.write(ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData());
        }
        byte[] iccp = concat(
                "profile\0".getBytes(StandardCharsets.ISO_8859_1),
                new byte[] {0},
                profile.toByteArray());
        byte[] itxt = concat(
                "XML:com.adobe.xmp\0".getBytes(StandardCharsets.ISO_8859_1),
                new byte[] {0, 0, 0, 0},
                "SECRET_XMP".getBytes(StandardCharsets.UTF_8));
        byte[] exif = new byte[] {
            0x4d, 0x4d, 0, 0x2a, 0, 0, 0, 8, 0, 0, 0, 0, 0, 0
        };
        Files.write(path, concat(
                Arrays.copyOf(png, afterHeader),
                pngChunk("tEXt", "Comment\0SECRET_TEXT".getBytes(
                        StandardCharsets.ISO_8859_1)),
                pngChunk("iTXt", itxt),
                pngChunk("eXIf", exif),
                pngChunk("iCCP", iccp),
                Arrays.copyOfRange(png, afterHeader, png.length)));
    }

    private static List<String> pngChunkTypes(byte[] png) {
        List<String> types = new ArrayList<>();
        int offset = 8;
        while (offset + 12 <= png.length) {
            int length = readInt(png, offset);
            types.add(new String(png, offset + 4, 4, StandardCharsets.US_ASCII));
            offset += 12 + length;
        }
        return List.copyOf(types);
    }

    private static byte[] pngChunk(String type, byte[] payload) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(payload.length + 12);
        writeInt32(output, payload.length);
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        output.write(name);
        output.write(payload);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(payload);
        writeInt32(output, (int) crc.getValue());
        return output.toByteArray();
    }

    private static int readInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 24)
                | ((bytes[offset + 1] & 0xff) << 16)
                | ((bytes[offset + 2] & 0xff) << 8)
                | (bytes[offset + 3] & 0xff);
    }

    private static void writeInt32(ByteArrayOutputStream output, int value) {
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

    private static void writeJpegSegment(
            ByteArrayOutputStream output, int marker, byte[] payload) {
        output.write(0xff);
        output.write(marker);
        int length = payload.length + 2;
        output.write((length >>> 8) & 0xff);
        output.write(length & 0xff);
        output.writeBytes(payload);
    }

    private static List<Path> filesExcept(Path directory, Path source) throws IOException {
        try (var paths = Files.list(directory)) {
            return paths.filter(path -> !path.equals(source)).toList();
        }
    }

    private record InterruptResult(Throwable failure, boolean interrupted) {}
}
