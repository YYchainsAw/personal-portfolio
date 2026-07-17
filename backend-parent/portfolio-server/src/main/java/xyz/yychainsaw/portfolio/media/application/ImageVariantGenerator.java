package xyz.yychainsaw.portfolio.media.application;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import org.springframework.stereotype.Component;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRecord;

@Component
final class ImageVariantGenerator {
    private static final int[] STANDARD_WIDTHS = {640, 1280, 1920, 2560, 3840};
    private static final int MAXIMUM_WIDTH = 3840;
    private static final long MAXIMUM_DECODE_PIXELS = 32_000_000L;
    private static final long MAXIMUM_VARIANT_PIXELS = 16_000_000L;
    private static final VariantFactory DEFAULT_VARIANT_FACTORY = GeneratedVariant::new;

    private final Path temporaryDirectory;
    private final MediaImageGate imageGate;
    private final VariantFactory variantFactory;

    ImageVariantGenerator() {
        this(
                MediaTemporaryFiles.defaultDirectory(),
                MediaImageGate.shared(),
                DEFAULT_VARIANT_FACTORY);
    }

    ImageVariantGenerator(Path temporaryDirectory) {
        this(temporaryDirectory, MediaImageGate.shared(), DEFAULT_VARIANT_FACTORY);
    }

    ImageVariantGenerator(Path temporaryDirectory, Semaphore permit) {
        this(temporaryDirectory, new MediaImageGate(permit), DEFAULT_VARIANT_FACTORY);
    }

    ImageVariantGenerator(Path temporaryDirectory, MediaImageGate imageGate) {
        this(temporaryDirectory, imageGate, DEFAULT_VARIANT_FACTORY);
    }

    ImageVariantGenerator(
            Path temporaryDirectory,
            Semaphore permit,
            VariantFactory variantFactory) {
        this(temporaryDirectory, new MediaImageGate(permit), variantFactory);
    }

    private ImageVariantGenerator(
            Path temporaryDirectory,
            MediaImageGate imageGate,
            VariantFactory variantFactory) {
        this.temporaryDirectory = MediaTemporaryFiles.requireDirectory(temporaryDirectory);
        this.imageGate = Objects.requireNonNull(imageGate, "media image gate is required");
        this.variantFactory = Objects.requireNonNull(
                variantFactory, "generated variant factory is required");
    }

    List<GeneratedVariant> generate(
            VerifiedMediaObject original, MediaAssetRecord asset) throws InterruptedException {
        List<GeneratedVariant> generated = new ArrayList<>();
        try {
            generateInto(original, asset, generated::add, false);
            return List.copyOf(generated);
        } catch (InterruptedException | RuntimeException exception) {
            closeBestEffort(generated);
            throw exception;
        }
    }

    void generateEach(
            VerifiedMediaObject original,
            MediaAssetRecord asset,
            Consumer<GeneratedVariant> consumer) throws InterruptedException {
        if (consumer == null) {
            throw failure();
        }
        generateInto(original, asset, consumer, true);
    }

    private void generateInto(
            VerifiedMediaObject original,
            MediaAssetRecord asset,
            Consumer<GeneratedVariant> consumer,
            boolean closeAfterConsumer) throws InterruptedException {
        if (original == null
                || asset == null
                || !("image/jpeg".equals(asset.mimeType())
                        || "image/png".equals(asset.mimeType()))
                || asset.width() == null
                || asset.height() == null
                || !asset.objectKey().equals(original.objectKey())
                || !asset.mimeType().equals(original.mimeType())
                || asset.byteSize() != original.byteSize()
                || !asset.sha256().equals(original.sha256())) {
            throw failure();
        }

        acquirePermit();
        BufferedImage source = null;
        try {
            try {
                source = decode(original, asset);
            } finally {
                closeWithRetry(original);
            }

            for (int width : targetWidths(
                    asset.width(), asset.height(), source.getWidth())) {
                int height = scaledHeight(asset.width(), asset.height(), width);
                BufferedImage resized = render(
                        source, width, height, "image/png".equals(asset.mimeType()));
                try {
                    GeneratedVariant variant =
                            encode(resized, width, height, asset.mimeType());
                    boolean accepted = false;
                    try {
                        consumer.accept(variant);
                        accepted = true;
                    } finally {
                        if (closeAfterConsumer || !accepted) {
                            closeWithRetry(variant);
                        }
                    }
                } finally {
                    resized.flush();
                }
            }
        } catch (IllegalStateException exception) {
            if (isFixedFailure(exception)) {
                throw exception;
            }
            throw failure();
        } catch (IOException | RuntimeException exception) {
            throw failure();
        } finally {
            if (source != null) {
                source.flush();
            }
            imageGate.release();
        }
    }

    static List<Integer> targetWidths(
            int sourceWidth, int sourceHeight, int decodedWidth) {
        if (sourceWidth <= 0
                || sourceHeight <= 0
                || decodedWidth <= 0
                || decodedWidth > sourceWidth) {
            throw failure();
        }
        int finalWidth = Math.min(sourceWidth, MAXIMUM_WIDTH);
        List<Integer> widths = new ArrayList<>(STANDARD_WIDTHS.length + 1);
        for (int standard : STANDARD_WIDTHS) {
            if (standard < sourceWidth) {
                widths.add(standard);
            }
        }
        if (widths.isEmpty() || widths.get(widths.size() - 1) != finalWidth) {
            widths.add(finalWidth);
        }
        for (int width : widths) {
            if (width > decodedWidth
                    || !fitsVariantBudget(sourceWidth, sourceHeight, width)) {
                throw failure();
            }
        }
        return List.copyOf(widths);
    }

    static int scaledHeight(int sourceWidth, int sourceHeight, int targetWidth) {
        if (sourceWidth <= 0
                || sourceHeight <= 0
                || targetWidth <= 0
                || targetWidth > sourceWidth
                || targetWidth > MAXIMUM_WIDTH) {
            throw failure();
        }
        long numerator = Math.multiplyExact((long) sourceHeight, targetWidth);
        long rounded = Math.addExact(numerator, sourceWidth / 2L) / sourceWidth;
        return (int) Math.max(1L, Math.min(rounded, Integer.MAX_VALUE));
    }

    static int sourceSubsampling(int width, int height) {
        if (width <= 0
                || height <= 0
                || (long) width * height > MediaFileInspector.PIXEL_LIMIT) {
            throw failure();
        }
        int sampling = 1;
        while (decodedPixels(width, height, sampling) > MAXIMUM_DECODE_PIXELS) {
            sampling++;
        }
        return sampling;
    }

    static void requireFinalizableDimensions(int width, int height) {
        int sampling = sourceSubsampling(width, height);
        targetWidths(width, height, ceilDivide(width, sampling));
    }

    static void configureJpeg(ImageWriteParam parameters) {
        if (parameters == null || !parameters.canWriteCompressed()) {
            throw failure();
        }
        parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        parameters.setCompressionQuality(0.92f);
        if (parameters.canWriteProgressive()) {
            parameters.setProgressiveMode(ImageWriteParam.MODE_DISABLED);
        }
    }

    private static long decodedPixels(int width, int height, int sampling) {
        return (long) ceilDivide(width, sampling) * ceilDivide(height, sampling);
    }

    private static int ceilDivide(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    private static boolean fitsVariantBudget(
            int sourceWidth, int sourceHeight, int targetWidth) {
        int height = scaledHeight(sourceWidth, sourceHeight, targetWidth);
        return (long) targetWidth * height <= MAXIMUM_VARIANT_PIXELS;
    }

    private static BufferedImage decode(
            VerifiedMediaObject original, MediaAssetRecord asset) throws IOException {
        ImageReader reader = null;
        try (ImageInputStream imageInput = original.openImageInput()) {
            Iterator<ImageReader> candidates = ImageIO.getImageReaders(imageInput);
            if (!candidates.hasNext()) {
                throw failure();
            }
            reader = candidates.next();
            AtomicBoolean warning = new AtomicBoolean();
            reader.addIIOReadWarningListener((source, message) -> warning.set(true));
            reader.setInput(imageInput, false, true);
            if (reader.getNumImages(true) != 1) {
                throw failure();
            }
            int sourceWidth = reader.getWidth(0);
            int sourceHeight = reader.getHeight(0);
            if (sourceWidth != asset.width() || sourceHeight != asset.height()) {
                throw failure();
            }
            int sampling = sourceSubsampling(sourceWidth, sourceHeight);
            ImageReadParam parameters = reader.getDefaultReadParam();
            parameters.setSourceSubsampling(sampling, sampling, 0, 0);
            BufferedImage decoded = reader.read(0, parameters);
            if (warning.get()
                    || decoded == null
                    || decoded.getWidth() != ceilDivide(sourceWidth, sampling)
                    || decoded.getHeight() != ceilDivide(sourceHeight, sampling)
                    || (long) decoded.getWidth() * decoded.getHeight()
                            > MAXIMUM_DECODE_PIXELS) {
                if (decoded != null) {
                    decoded.flush();
                }
                throw failure();
            }
            return decoded;
        } finally {
            if (reader != null) {
                try {
                    reader.dispose();
                } catch (RuntimeException ignored) {
                    // The fair permit is released by the caller's finally block.
                }
            }
        }
    }

    private static BufferedImage render(
            BufferedImage source, int width, int height, boolean preserveAlpha) {
        BufferedImage target = new BufferedImage(
                width,
                height,
                preserveAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(
                    RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(
                    RenderingHints.KEY_COLOR_RENDERING,
                    RenderingHints.VALUE_COLOR_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private GeneratedVariant encode(
            BufferedImage image, int width, int height, String mimeType)
            throws IOException {
        boolean jpeg = "image/jpeg".equals(mimeType);
        String format = jpeg ? "JPEG" : "PNG";
        Path target = MediaTemporaryFiles.create(
                temporaryDirectory, jpeg ? ".jpg" : ".png");
        ImageWriter writer = null;
        try {
            Iterator<ImageWriter> candidates = ImageIO.getImageWritersByFormatName(format);
            if (!candidates.hasNext()) {
                throw failure();
            }
            writer = candidates.next();
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            if (jpeg) {
                configureJpeg(parameters);
            }
            try (ImageOutputStream output = new BoundedFileImageOutputStream(
                    target, MediaFileInspector.IMAGE_BYTE_LIMIT)) {
                writer.setOutput(output);
                writer.write(null, new IIOImage(image, null, null), parameters);
                output.flush();
            }
            long size = Files.size(target);
            if (size <= 0 || size > MediaFileInspector.IMAGE_BYTE_LIMIT) {
                throw failure();
            }
            String sha256 = digest(target);
            return variantFactory.create(
                    target,
                    "w" + width,
                    format,
                    mimeType,
                    size,
                    width,
                    height,
                    sha256);
        } catch (IOException | RuntimeException exception) {
            MediaTemporaryFiles.deleteBestEffort(target);
            if (exception instanceof IllegalStateException state && isFixedFailure(state)) {
                throw state;
            }
            if (exception instanceof IOException io) {
                throw io;
            }
            throw failure();
        } finally {
            if (writer != null) {
                try {
                    writer.dispose();
                } catch (RuntimeException ignored) {
                    // The generation permit and temporary-file owner still unwind.
                }
            }
        }
    }

    private static String digest(Path path) throws IOException {
        MessageDigest digest = sha256Digest();
        byte[] buffer = new byte[8192];
        try (InputStream input = MediaTemporaryFiles.open(path)) {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) {
                    digest.update(buffer, 0, count);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void acquirePermit() throws InterruptedException {
        try {
            imageGate.acquire();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        }
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw failure();
        }
    }

    private static void closeBestEffort(List<GeneratedVariant> variants) {
        for (GeneratedVariant variant : variants) {
            closeTwiceBestEffort(variant);
        }
    }

    private static void closeWithRetry(GeneratedVariant variant) {
        try {
            variant.close();
        } catch (RuntimeException firstFailure) {
            closeTwiceBestEffort(variant);
            throw failure();
        }
    }

    private static void closeWithRetry(VerifiedMediaObject original) {
        try {
            original.close();
        } catch (RuntimeException firstFailure) {
            closeTwiceBestEffort(original);
            throw failure();
        }
    }

    private static void closeTwiceBestEffort(AutoCloseable owner) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                owner.close();
                return;
            } catch (Exception ignored) {
                // Preserve the fixed primary failure after a bounded cleanup retry.
            }
        }
    }

    @FunctionalInterface
    interface VariantFactory {
        GeneratedVariant create(
                Path path,
                String variantName,
                String format,
                String mimeType,
                long byteSize,
                int width,
                int height,
                String sha256);
    }

    private static boolean isFixedFailure(IllegalStateException exception) {
        return exception.getClass() == IllegalStateException.class
                && "MEDIA_FINALIZATION_FAILED".equals(exception.getMessage())
                && exception.getCause() == null;
    }

    private static IllegalStateException failure() {
        return MediaTemporaryFiles.failure();
    }
}
