package xyz.yychainsaw.portfolio.media.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.yychainsaw.portfolio.media.domain.MediaStatus;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRecord;

class MediaImageGateTest {
    private static final UUID ASSET_ID =
            UUID.fromString("11111111-2222-3333-4444-555555555555");

    @TempDir Path temporaryDirectory;

    @Test
    void inspectorAndFinalizerShareOneFairInterruptiblePermit() throws Exception {
        byte[] png = png();
        Path source = Files.write(temporaryDirectory.resolve("source.png"), png);
        MediaAssetRecord asset = new MediaAssetRecord(
                ASSET_ID,
                StorageProvider.LOCAL,
                null,
                null,
                MediaObjectKeys.originalKey(ASSET_ID, sha256(png), "image/png"),
                "source.png",
                "image/png",
                png.length,
                8,
                4,
                sha256(png),
                MediaStatus.PROCESSING,
                null,
                0,
                Instant.parse("2026-07-16T00:00:00Z"),
                Instant.parse("2026-07-16T00:00:00Z"));
        Semaphore permit = new Semaphore(0, true);
        MediaImageGate gate = new MediaImageGate(permit);
        MediaFileInspector inspector = new MediaFileInspector(temporaryDirectory, gate);
        ImageVariantGenerator generator = new ImageVariantGenerator(temporaryDirectory, gate);
        AtomicReference<Throwable> inspectionFailure = new AtomicReference<>();
        AtomicReference<Throwable> generationFailure = new AtomicReference<>();

        Thread inspection = new Thread(() -> {
            try (InspectedMedia ignored = inspector.inspect(new UploadMediaCommand(
                    "source.png",
                    "image/png",
                    png.length,
                    new ByteArrayInputStream(png)))) {
                // Completion releases the shared permit.
            } catch (Throwable failure) {
                inspectionFailure.set(failure);
            }
        }, "media-inspection-gate-test");
        Thread generation = new Thread(() -> {
            try (VerifiedMediaObject original = new VerifiedMediaObject(
                    source,
                    asset.objectKey(),
                    asset.mimeType(),
                    asset.byteSize(),
                    asset.sha256(),
                    false)) {
                generator.generateEach(original, asset, ignored -> {});
            } catch (Throwable failure) {
                generationFailure.set(failure);
            }
        }, "media-generation-gate-test");

        inspection.start();
        awaitQueueLength(permit, 1);
        generation.start();
        awaitQueueLength(permit, 2);
        permit.release();

        inspection.join(TimeUnit.SECONDS.toMillis(10));
        generation.join(TimeUnit.SECONDS.toMillis(10));
        assertThat(inspection.isAlive()).isFalse();
        assertThat(generation.isAlive()).isFalse();
        assertThat(inspectionFailure.get()).isNull();
        assertThat(generationFailure.get()).isNull();
        assertThat(permit.availablePermits()).isOne();
        assertThat(permit.isFair()).isTrue();
    }

    private static void awaitQueueLength(Semaphore permit, int expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (permit.getQueueLength() < expected && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertThat(permit.getQueueLength()).isGreaterThanOrEqualTo(expected);
    }

    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(8, 4, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            assertThat(ImageIO.write(image, "png", output)).isTrue();
            return output.toByteArray();
        } finally {
            image.flush();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
