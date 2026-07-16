package xyz.yychainsaw.portfolio.auth.cli;

import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;
import static java.nio.file.attribute.PosixFilePermission.GROUP_READ;
import static java.nio.file.attribute.PosixFilePermission.GROUP_WRITE;
import static java.nio.file.attribute.PosixFilePermission.OTHERS_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PgDumpRestorePointServiceTest {
    private static final String JDBC_URL =
            "jdbc:postgresql://db.internal:5544/portfolio?currentSchema=portfolio";
    private static final String DATABASE = "portfolio";
    private static final String SERVER_ADDRESS = "192.0.2.44";
    private static final int SERVER_PORT = 5544;
    private static final String BACKEND_ADDRESS = "10.0.0.17";
    private static final int BACKEND_PORT = 5432;
    private static final String USERNAME = "portfolio_migrator";
    private static final String PASSWORD = "  pg-password-marker  ";
    private static final String FAILURE_MARKER = "provider-secret-marker";
    private static final String FIXED_FAILURE = "database restore point could not be created";
    private static final String LOCK_NAME = ".portfolio-maintenance.lock";
    private static final Instant NOW = Instant.parse("2026-07-16T04:05:06.123456Z");
    private static final byte[] VALID_ARCHIVE =
            "PGDMP\u0001\u000fvalidated-archive-payload".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] PREEXISTING_ARCHIVE =
            "preexisting-restore-point".getBytes(StandardCharsets.US_ASCII);

    @TempDir Path temporaryDirectory;

    private Path executable;
    private Path recoveryParent;

    @BeforeEach
    void prepareTrustedInputs() throws Exception {
        executable = currentJavaExecutable();
        recoveryParent = Files.createDirectory(temporaryDirectory.resolve("recovery-parent"));
        setPosixPermissionsIfSupported(
                recoveryParent, Set.of(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE));
    }

    @AfterEach
    void clearInterruptStatus() {
        Thread.interrupted();
    }

    @Test
    void propertiesAndRestorePointValidateBoundsNormalizeAndRedactSensitiveState() {
        Path recovery = recoveryParent.resolve("nested").toAbsolutePath().normalize();
        RecoveryProperties properties = properties(recovery, executable.toString(), Duration.ofMinutes(2));

        assertThat(properties.directory()).isEqualTo(recovery);
        assertThat(properties.pgDumpBin()).isEqualTo(executable.toString());
        assertThat(properties.username()).isEqualTo(USERNAME);
        assertThat(properties.password()).isEqualTo(PASSWORD);
        assertThat(properties.timeout()).isEqualTo(Duration.ofMinutes(2));
        assertThat(properties.toString())
                .doesNotContain(
                        recovery.toString(), executable.toString(), USERNAME, PASSWORD, DATABASE)
                .contains("<redacted>");

        String checksum = "a".repeat(64);
        Path artifact = recovery.resolve("admin-maintenance.dump");
        DatabaseRestorePointService.RestorePoint restorePoint =
                new DatabaseRestorePointService.RestorePoint(artifact, checksum);
        assertThat(restorePoint.path()).isEqualTo(artifact);
        assertThat(restorePoint.sha256()).isEqualTo(checksum);
        assertThat(restorePoint.toString())
                .doesNotContain(artifact.toString())
                .contains("<redacted>", checksum);

        assertThatIllegalArgumentException().isThrownBy(() -> new RecoveryProperties(
                Path.of("relative/recovery"), executable.toString(), "portfolio_migrator", "secret",
                Duration.ofMinutes(2)));
        assertThatIllegalArgumentException().isThrownBy(() -> properties(
                recovery, " ", Duration.ofMinutes(2)));
        assertThatIllegalArgumentException().isThrownBy(() -> new RecoveryProperties(
                recovery, executable.toString(), "bad\nuser", PASSWORD, Duration.ofMinutes(2)));
        assertThatIllegalArgumentException().isThrownBy(() -> new RecoveryProperties(
                recovery, executable.toString(), USERNAME, null, Duration.ofMinutes(2)));
        assertThatIllegalArgumentException().isThrownBy(() -> properties(
                recovery, executable.toString(), Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> properties(
                recovery, executable.toString(), Duration.ofMinutes(10).plusNanos(1)));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new DatabaseRestorePointService.RestorePoint(Path.of("relative.dump"), checksum));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new DatabaseRestorePointService.RestorePoint(artifact, "A".repeat(64)));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new DatabaseRestorePointService.RestorePoint(artifact, "a".repeat(63)));
    }

    @Test
    void successfulDumpDerivesOneRuntimeTargetUsesFixedProcessBoundaryAndReturnsStableArtifact()
            throws Exception {
        Path recovery = recoveryParent.resolve("success");
        MetadataDataSource dataSource = MetadataDataSource.success(JDBC_URL, DATABASE);
        RecordingLauncher launcher = RecordingLauncher.success();

        DatabaseRestorePointService.RestorePoint restorePoint =
                service(properties(recovery), dataSource, launcher).create();

        assertThat(dataSource.connectionCount()).isOne();
        assertThat(dataSource.currentDatabaseQueryCount()).isOne();
        assertThat(launcher.calls()).isOne();
        assertThat(launcher.commandAtStart()).containsExactly(
                executable.toString(),
                "--format=custom",
                "--no-owner",
                "--no-acl",
                "--no-password");
        assertThat(String.join(" ", launcher.commandAtStart()))
                .doesNotContain(
                        "db.internal", "5544", DATABASE, USERNAME, PASSWORD,
                        recovery.toString(), "--file");

        Map<String, String> environment = launcher.environmentAtStart();
        assertThat(environment.keySet()).containsExactlyInAnyOrder(
                "PGHOST", "PGPORT", "PGDATABASE", "PGUSER", "PGPASSWORD",
                "PGCONNECT_TIMEOUT", "PGSSLMODE", "PGGSSENCMODE", "LC_ALL", "TZ");
        assertThat(environment)
                .containsEntry("PGHOST", SERVER_ADDRESS)
                .containsEntry("PGPORT", "5544")
                .containsEntry("PGDATABASE", DATABASE)
                .containsEntry("PGUSER", USERNAME)
                .containsEntry("PGPASSWORD", PASSWORD)
                .containsEntry("PGSSLMODE", "disable")
                .containsEntry("PGGSSENCMODE", "disable")
                .containsEntry("LC_ALL", "C")
                .containsEntry("TZ", "UTC");
        assertThat(environment.get("PGCONNECT_TIMEOUT")).matches("[1-9][0-9]*");
        assertThat(Integer.parseInt(environment.get("PGCONNECT_TIMEOUT"))).isLessThanOrEqualTo(120);
        assertThat(launcher.builder().environment()).isEmpty();

        assertThat(launcher.outputAtStart()).isNotNull();
        assertThat(launcher.outputAtStart().getFileName().toString()).endsWith(".part");
        assertThat(launcher.errorRedirectAtStart()).isEqualTo(ProcessBuilder.Redirect.DISCARD);
        assertThat(launcher.process().stdinClosed()).isTrue();
        assertThat(launcher.process().unboundedWaitCalls()).isZero();

        Path artifact = restorePoint.path();
        assertThat(artifact.isAbsolute()).isTrue();
        assertThat(artifact.normalize().getParent()).isEqualTo(recovery.toAbsolutePath().normalize());
        assertThat(artifact.getFileName().toString()).endsWith(".dump");
        assertThat(Files.isRegularFile(artifact, LinkOption.NOFOLLOW_LINKS)).isTrue();
        assertThat(Files.isSymbolicLink(artifact)).isFalse();
        assertThat(Files.readAllBytes(artifact)).containsExactly(VALID_ARCHIVE);
        assertThat(restorePoint.sha256()).isEqualTo(sha256(VALID_ARCHIVE));
        assertThat(filesEndingWith(recovery, ".part")).isEmpty();
        assertThat(filesEndingWith(recovery, ".dump")).containsExactly(artifact);
        assertThat(restorePoint.toString()).doesNotContain(artifact.toString());
    }

    @Test
    void malformedAmbiguousAndSocketFactoryTargetsFailBeforeFilesystemAndProcess() throws Exception {
        List<String> rejected = List.of(
                "jdbc:postgresql:portfolio",
                "jdbc:postgresql://db.internal/portfolio",
                "jdbc:postgresql://db-one:5432,db-two:5432/portfolio",
                "jdbc:postgresql://db.internal:5432/portfolio?socketFactory=evil.Factory",
                "jdbc:postgresql://db.internal:5432/portfolio?currentSchema=one&currentSchema=two",
                "jdbc:postgresql://db.internal:5432/portfolio?%73slmode=require",
                "jdbc:postgresql://db.internal:5432/portfolio?ApplicationName=maintenance",
                "jdbc:mysql://db.internal:3306/portfolio",
                "not-a-jdbc-url");

        for (int index = 0; index < rejected.size(); index++) {
            Path recovery = recoveryParent.resolve("bad-target-" + index);
            MetadataDataSource dataSource = MetadataDataSource.success(rejected.get(index), DATABASE);
            RecordingLauncher launcher = RecordingLauncher.success();

            assertFixedFailure(() -> service(properties(recovery), dataSource, launcher).create(),
                    rejected.get(index));

            assertThat(launcher.calls()).isZero();
            assertThat(Files.exists(recovery, LinkOption.NOFOLLOW_LINKS)).isFalse();
        }
    }

    @Test
    void currentDatabaseMismatchAndMetadataFailureFailBeforeFilesystemAndProcess() throws Exception {
        Path mismatchRecovery = recoveryParent.resolve("database-mismatch");
        MetadataDataSource mismatch = MetadataDataSource.success(JDBC_URL, "different_database");
        RecordingLauncher mismatchLauncher = RecordingLauncher.success();
        assertFixedFailure(
                () -> service(properties(mismatchRecovery), mismatch, mismatchLauncher).create(),
                "different_database", JDBC_URL);
        assertThat(mismatch.currentDatabaseQueryCount()).isOne();
        assertThat(mismatchLauncher.calls()).isZero();
        assertThat(Files.exists(mismatchRecovery, LinkOption.NOFOLLOW_LINKS)).isFalse();

        Path failedRecovery = recoveryParent.resolve("metadata-failure");
        MetadataDataSource failed = MetadataDataSource.failure(
                new SQLException(FAILURE_MARKER + "-jdbc-url-password"));
        RecordingLauncher failedLauncher = RecordingLauncher.success();
        assertFixedFailure(
                () -> service(properties(failedRecovery), failed, failedLauncher).create(),
                FAILURE_MARKER, PASSWORD, failedRecovery.toString());
        assertThat(failedLauncher.calls()).isZero();
        assertThat(Files.exists(failedRecovery, LinkOption.NOFOLLOW_LINKS)).isFalse();
    }

    @Test
    void liveTcpEndpointIsPinnedAndTransportDriftFailsBeforeFilesystemOrProcess()
            throws Exception {
        List<MetadataDataSource> rejected = List.of(
                MetadataDataSource.transport(JDBC_URL, DATABASE, null, SERVER_PORT, false),
                MetadataDataSource.transport(
                        JDBC_URL, DATABASE, SERVER_ADDRESS, SERVER_PORT + 1, false),
                MetadataDataSource.transport(
                        JDBC_URL, DATABASE, SERVER_ADDRESS, SERVER_PORT, true),
                MetadataDataSource.transport(
                        JDBC_URL, DATABASE, SERVER_ADDRESS, SERVER_PORT, false, true),
                MetadataDataSource.transportWithBackend(
                        JDBC_URL,
                        DATABASE,
                        SERVER_ADDRESS,
                        SERVER_PORT,
                        null,
                        0,
                        false,
                        false),
                MetadataDataSource.success(
                        "jdbc:postgresql://db.internal:5544/portfolio"
                                + "?currentSchema=portfolio&sslmode=verify-full",
                        DATABASE));

        for (int index = 0; index < rejected.size(); index++) {
            Path recovery = recoveryParent.resolve("transport-drift-" + index);
            MetadataDataSource dataSource = rejected.get(index);
            RecordingLauncher launcher = RecordingLauncher.success();

            assertFixedFailure(
                    () -> service(properties(recovery), dataSource, launcher).create(),
                    JDBC_URL, SERVER_ADDRESS, PASSWORD);

            assertThat(launcher.calls()).isZero();
            assertThat(Files.exists(recovery, LinkOption.NOFOLLOW_LINKS)).isFalse();
        }
    }

    @Test
    void portableBareExecutableIsNeverResolvedThroughPath() {
        Path recovery = recoveryParent.resolve("bare-executable");
        RecoveryProperties portable = properties(recovery, "pg_dump", Duration.ofMinutes(2));
        MetadataDataSource dataSource = MetadataDataSource.success(JDBC_URL, DATABASE);
        RecordingLauncher launcher = RecordingLauncher.success();

        assertFixedFailure(() -> service(portable, dataSource, launcher).create(), "pg_dump", PASSWORD);

        assertThat(launcher.calls()).isZero();
        assertThat(Files.exists(recovery, LinkOption.NOFOLLOW_LINKS)).isFalse();
    }

    @Test
    void fixedClockCallsUseDistinctFilesWithoutReplacement() throws Exception {
        Path recovery = recoveryParent.resolve("collision-free");
        MetadataDataSource dataSource = MetadataDataSource.success(JDBC_URL, DATABASE);
        RecordingLauncher launcher = RecordingLauncher.success();
        PgDumpRestorePointService service = service(properties(recovery), dataSource, launcher);

        DatabaseRestorePointService.RestorePoint first = service.create();
        DatabaseRestorePointService.RestorePoint second = service.create();

        assertThat(first.path()).isNotEqualTo(second.path());
        assertThat(Files.readAllBytes(first.path())).containsExactly(VALID_ARCHIVE);
        assertThat(Files.readAllBytes(second.path())).containsExactly(VALID_ARCHIVE);
        assertThat(filesEndingWith(recovery, ".dump")).containsExactlyInAnyOrder(
                first.path(), second.path());
        assertThat(filesEndingWith(recovery, ".part")).isEmpty();
        assertThat(launcher.calls()).isEqualTo(2);
    }

    @Test
    void publishCollisionNeverReplacesOrDeletesThePreexistingArtifact() throws Exception {
        Path recovery = recoveryParent.resolve("publish-collision");
        RecordingLauncher launcher = new RecordingLauncher(LaunchMode.PUBLISH_COLLISION);

        assertFixedFailure(
                () -> service(
                                properties(recovery),
                                MetadataDataSource.success(JDBC_URL, DATABASE),
                                launcher)
                        .create(),
                PASSWORD, recovery.toString());

        Path collision = launcher.outputAtStart().resolveSibling(
                launcher.outputAtStart().getFileName().toString().replace(".part", ".dump"));
        assertThat(Files.readAllBytes(collision)).containsExactly(PREEXISTING_ARCHIVE);
        assertThat(filesEndingWith(recovery, ".dump")).containsExactly(collision);
        assertThat(filesEndingWith(recovery, ".part")).isEmpty();
    }

    @Test
    void startNonzeroAndInvalidArchiveFailuresAreCauseFreeAndLeaveNoPartialArtifact()
            throws Exception {
        for (LaunchMode mode : List.of(
                LaunchMode.START_FAILURE,
                LaunchMode.NON_ZERO,
                LaunchMode.EMPTY,
                LaunchMode.INVALID_MAGIC)) {
            Path recovery = recoveryParent.resolve("failure-" + mode.name().toLowerCase());
            RecordingLauncher launcher = new RecordingLauncher(mode);
            MetadataDataSource dataSource = MetadataDataSource.success(JDBC_URL, DATABASE);

            assertFixedFailure(
                    () -> service(properties(recovery), dataSource, launcher).create(),
                    FAILURE_MARKER, PASSWORD, JDBC_URL, recovery.toString());

            assertThat(filesEndingWith(recovery, ".part")).isEmpty();
            assertThat(filesEndingWith(recovery, ".dump")).isEmpty();
            assertThat(launcher.calls()).isOne();
            assertThat(launcher.builder().environment()).isEmpty();
        }
    }

    @Test
    void timeoutUsesOnlyBoundedGracefulForcedAndFinalDeathWaits() throws Exception {
        Path recovery = recoveryParent.resolve("timeout");
        RecordingLauncher launcher = new RecordingLauncher(LaunchMode.TIMEOUT);
        PgDumpRestorePointService service = service(
                properties(recovery, executable.toString(), Duration.ofMillis(50)),
                MetadataDataSource.success(JDBC_URL, DATABASE),
                launcher);

        assertFixedFailure(service::create, FAILURE_MARKER, PASSWORD, recovery.toString());

        ControlledProcess process = launcher.process();
        assertThat(process.unboundedWaitCalls()).isZero();
        assertThat(process.timedWaitCalls()).isGreaterThanOrEqualTo(3);
        assertThat(process.destroyCalls()).isOne();
        assertThat(process.forceDestroyCalls()).isOne();
        assertThat(process.isAlive()).isFalse();
        assertThat(process.stdinClosed()).isTrue();
        assertThat(filesEndingWith(recovery, ".part")).isEmpty();
        assertThat(filesEndingWith(recovery, ".dump")).isEmpty();
    }

    @Test
    void interruptionTerminatesChildCleansPartialAndRestoresInterruptStatus() throws Exception {
        Path recovery = recoveryParent.resolve("interrupted");
        RecordingLauncher launcher = new RecordingLauncher(LaunchMode.INTERRUPTED);
        PgDumpRestorePointService service = service(
                properties(recovery), MetadataDataSource.success(JDBC_URL, DATABASE), launcher);

        try {
            assertFixedFailure(service::create, FAILURE_MARKER, PASSWORD, recovery.toString());
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            ControlledProcess process = launcher.process();
            assertThat(process.destroyCalls()).isOne();
            assertThat(process.forceDestroyCalls()).isOne();
            assertThat(process.isAlive()).isFalse();
            assertThat(process.unboundedWaitCalls()).isZero();
            assertThat(filesEndingWith(recovery, ".part")).isEmpty();
            assertThat(filesEndingWith(recovery, ".dump")).isEmpty();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void interruptionRestoresTheFlagEvenWhenTheChildRefusesBoundedTermination()
            throws Exception {
        Path recovery = recoveryParent.resolve("interrupted-stubborn-child");
        RecordingLauncher launcher = new RecordingLauncher(LaunchMode.INTERRUPTED_STUBBORN);

        try {
            assertFixedFailure(
                    () -> service(
                                    properties(recovery),
                                    MetadataDataSource.success(JDBC_URL, DATABASE),
                                    launcher)
                            .create(),
                    FAILURE_MARKER, PASSWORD, recovery.toString());
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(launcher.process().isAlive()).isTrue();
            assertThat(launcher.process().destroyCalls()).isGreaterThanOrEqualTo(1);
            assertThat(launcher.process().forceDestroyCalls()).isGreaterThanOrEqualTo(1);
            assertThat(launcher.process().unboundedWaitCalls()).isZero();
        } finally {
            Thread.interrupted();
            launcher.process().release();
        }
        assertThat(filesEndingWith(recovery, ".part")).isEmpty();
        assertThat(filesEndingWith(recovery, ".dump")).isEmpty();
    }

    @Test
    void replacingThePrecreatedOutputIsDetectedBeforeArtifactPublication() throws Exception {
        Path recovery = recoveryParent.resolve("swapped-output");
        RecordingLauncher launcher = new RecordingLauncher(LaunchMode.SWAP_OUTPUT);

        assertFixedFailure(
                () -> service(
                                properties(recovery),
                                MetadataDataSource.success(JDBC_URL, DATABASE),
                                launcher)
                        .create(),
                PASSWORD, recovery.toString());

        assertThat(launcher.calls()).isOne();
        assertThat(filesEndingWith(recovery, ".part")).isEmpty();
        assertThat(filesEndingWith(recovery, ".dump")).isEmpty();
    }

    @Test
    void symbolicLinkRecoveryLeafAndNonRegularLockFailBeforeProcess() throws Exception {
        Path real = Files.createDirectory(recoveryParent.resolve("real-recovery"));
        setPosixPermissionsIfSupported(real, Set.of(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE));
        Path symlink = recoveryParent.resolve("symlink-recovery");
        createSymbolicLinkOrAbort(symlink, real);
        RecordingLauncher symlinkLauncher = RecordingLauncher.success();

        assertFixedFailure(
                () -> service(
                                properties(symlink),
                                MetadataDataSource.success(JDBC_URL, DATABASE),
                                symlinkLauncher)
                        .create(),
                symlink.toString());
        assertThat(symlinkLauncher.calls()).isZero();

        Path lockedRecovery = Files.createDirectory(recoveryParent.resolve("non-regular-lock"));
        setPosixPermissionsIfSupported(
                lockedRecovery, Set.of(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE));
        Files.createDirectory(lockedRecovery.resolve(LOCK_NAME));
        RecordingLauncher lockLauncher = RecordingLauncher.success();
        assertFixedFailure(
                () -> service(
                                properties(lockedRecovery),
                                MetadataDataSource.success(JDBC_URL, DATABASE),
                                lockLauncher)
                        .create(),
                LOCK_NAME, lockedRecovery.toString());
        assertThat(lockLauncher.calls()).isZero();
        assertThat(filesEndingWith(lockedRecovery, ".part")).isEmpty();
    }

    @Test
    void linuxUnsafeExecutableRecoveryParentAndLockModesFailBeforeProcess() throws Exception {
        assumePosix();
        MetadataDataSource target = MetadataDataSource.success(JDBC_URL, DATABASE);

        Path unsafeBinaryParent = Files.createDirectory(temporaryDirectory.resolve("unsafe-bin-parent"));
        Files.setPosixFilePermissions(unsafeBinaryParent, Set.of(
                OWNER_READ, OWNER_WRITE, OWNER_EXECUTE, GROUP_WRITE));
        Path unsafeBinary = Files.writeString(
                unsafeBinaryParent.resolve("pg_dump"), "test executable", CREATE_NEW, WRITE);
        Files.setPosixFilePermissions(unsafeBinary, Set.of(OWNER_READ, OWNER_EXECUTE));
        RecordingLauncher unsafeParentLauncher = RecordingLauncher.success();
        assertFixedFailure(
                () -> service(
                                properties(
                                        recoveryParent.resolve("unsafe-bin-parent-output"),
                                        unsafeBinary.toAbsolutePath().toString(),
                                        Duration.ofMinutes(2)),
                                target,
                                unsafeParentLauncher)
                        .create(),
                unsafeBinaryParent.toString());
        assertThat(unsafeParentLauncher.calls()).isZero();

        Path unsafeBinaryDirectory = Files.createDirectory(
                temporaryDirectory.resolve("unsafe-binary-directory"));
        Files.setPosixFilePermissions(
                unsafeBinaryDirectory, Set.of(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE));
        Path worldWritableBinary = Files.writeString(
                unsafeBinaryDirectory.resolve("pg_dump"), "test executable", CREATE_NEW, WRITE);
        Files.setPosixFilePermissions(worldWritableBinary, Set.of(
                OWNER_READ, OWNER_WRITE, OWNER_EXECUTE, GROUP_WRITE, OTHERS_READ));
        RecordingLauncher unsafeBinaryLauncher = RecordingLauncher.success();
        assertFixedFailure(
                () -> service(
                                properties(
                                        recoveryParent.resolve("unsafe-binary-output"),
                                        worldWritableBinary.toAbsolutePath().toString(),
                                        Duration.ofMinutes(2)),
                                target,
                                unsafeBinaryLauncher)
                        .create(),
                worldWritableBinary.toString());
        assertThat(unsafeBinaryLauncher.calls()).isZero();

        Path unsafeRecoveryParent = Files.createDirectory(
                temporaryDirectory.resolve("unsafe-recovery-parent"));
        Files.setPosixFilePermissions(unsafeRecoveryParent, Set.of(
                OWNER_READ, OWNER_WRITE, OWNER_EXECUTE, GROUP_WRITE));
        RecordingLauncher unsafeRecoveryLauncher = RecordingLauncher.success();
        assertFixedFailure(
                () -> service(
                                properties(unsafeRecoveryParent.resolve("leaf")),
                                target,
                                unsafeRecoveryLauncher)
                        .create(),
                unsafeRecoveryParent.toString());
        assertThat(unsafeRecoveryLauncher.calls()).isZero();

        Path unsafeLockRecovery = Files.createDirectory(
                recoveryParent.resolve("unsafe-lock-mode"));
        Files.setPosixFilePermissions(
                unsafeLockRecovery, Set.of(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE));
        Path lock = Files.write(unsafeLockRecovery.resolve(LOCK_NAME), new byte[0], CREATE_NEW, WRITE);
        Files.setPosixFilePermissions(lock, Set.of(OWNER_READ, OWNER_WRITE, GROUP_READ));
        RecordingLauncher unsafeLockLauncher = RecordingLauncher.success();
        assertFixedFailure(
                () -> service(properties(unsafeLockRecovery), target, unsafeLockLauncher).create(),
                lock.toString());
        assertThat(unsafeLockLauncher.calls()).isZero();
    }

    @Test
    void linuxExistingHardLinkedLockFailsBeforeProcess() throws Exception {
        assumePosix();
        Path recovery = Files.createDirectory(recoveryParent.resolve("hard-linked-lock"));
        Files.setPosixFilePermissions(recovery, Set.of(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE));
        Path lock = Files.write(recovery.resolve(LOCK_NAME), new byte[0], CREATE_NEW, WRITE);
        Files.setPosixFilePermissions(lock, Set.of(OWNER_READ, OWNER_WRITE));
        Files.createLink(recovery.resolve("lock-alias"), lock);
        RecordingLauncher launcher = RecordingLauncher.success();

        assertFixedFailure(
                () -> service(
                                properties(recovery),
                                MetadataDataSource.success(JDBC_URL, DATABASE),
                                launcher)
                        .create(),
                lock.toString());

        assertThat(launcher.calls()).isZero();
        assertThat(filesEndingWith(recovery, ".part")).isEmpty();
    }

    @Test
    void linuxArtifactsUseExactOwnerOnlyDirectoryLockAndDumpModes() throws Exception {
        assumePosix();
        Path recovery = recoveryParent.resolve("posix-modes");
        RecordingLauncher launcher = RecordingLauncher.success();

        DatabaseRestorePointService.RestorePoint restorePoint = service(
                        properties(recovery),
                        MetadataDataSource.success(JDBC_URL, DATABASE),
                        launcher)
                .create();

        assertThat(Files.getPosixFilePermissions(recovery))
                .containsExactlyInAnyOrder(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE);
        assertThat(Files.getPosixFilePermissions(recovery.resolve(LOCK_NAME)))
                .containsExactlyInAnyOrder(OWNER_READ, OWNER_WRITE);
        assertThat(Files.getPosixFilePermissions(restorePoint.path()))
                .containsExactlyInAnyOrder(OWNER_READ, OWNER_WRITE);
    }

    @Test
    void sharedDirectoryLockAllowsOnlyOneChildAcrossServiceInstances() throws Exception {
        Path recovery = recoveryParent.resolve("concurrent-lock");
        RecordingLauncher launcher = RecordingLauncher.blocking();
        MetadataDataSource dataSource = MetadataDataSource.success(JDBC_URL, DATABASE);
        PgDumpRestorePointService firstService = service(properties(recovery), dataSource, launcher);
        PgDumpRestorePointService secondService = service(properties(recovery), dataSource, launcher);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<DatabaseRestorePointService.RestorePoint> first = executor.submit(firstService::create);
        try {
            assertThat(launcher.awaitStarted(5, SECONDS)).isTrue();

            assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                    assertFixedFailure(secondService::create, PASSWORD, recovery.toString()));
            assertThat(launcher.calls()).isOne();
        } finally {
            launcher.releaseBlockingProcess();
            executor.shutdown();
        }

        DatabaseRestorePointService.RestorePoint completed = first.get(5, SECONDS);
        assertThat(Files.isRegularFile(completed.path(), LinkOption.NOFOLLOW_LINKS)).isTrue();
        assertThat(launcher.calls()).isOne();
        assertThat(executor.awaitTermination(5, SECONDS)).isTrue();
    }

    private PgDumpRestorePointService service(
            RecoveryProperties properties,
            MetadataDataSource dataSource,
            RecordingLauncher launcher) {
        return new PgDumpRestorePointService(
                properties,
                dataSource.dataSource(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                launcher,
                (ignoredDataSource, ignoredConnection, ignoredHost, ignoredPort) ->
                        new PgDumpRestorePointService.PeerEndpoint(
                                dataSource.serverAddress, dataSource.serverPort));
    }

    private RecoveryProperties properties(Path recovery) {
        return properties(recovery, executable.toString(), Duration.ofMinutes(2));
    }

    private static RecoveryProperties properties(
            Path recovery, String pgDumpBin, Duration timeout) {
        return new RecoveryProperties(
                recovery.toAbsolutePath().normalize(), pgDumpBin, USERNAME, PASSWORD, timeout);
    }

    private static void assertFixedFailure(ThrowingAction action, String... forbiddenMarkers) {
        assertThatThrownBy(action::run)
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage(FIXED_FAILURE)
                .hasNoCause()
                .satisfies(failure -> {
                    String rendered = failure.toString()
                            + Arrays.toString(failure.getStackTrace());
                    assertThat(rendered).doesNotContain(forbiddenMarkers);
                });
    }

    private static List<Path> filesEndingWith(Path directory, String suffix) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().endsWith(suffix))
                    .sorted()
                    .toList();
        }
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static Path currentJavaExecutable() throws IOException {
        String name = System.getProperty("os.name", "").toLowerCase().contains("windows")
                ? "java.exe" : "java";
        Path candidate = Path.of(System.getProperty("java.home"), "bin", name).toRealPath();
        if (!candidate.isAbsolute()
                || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                || !Files.isExecutable(candidate)
                || Files.isSymbolicLink(candidate)) {
            throw new IllegalStateException("test JVM executable is not a trusted regular file");
        }
        return candidate;
    }

    private static boolean supportsPosix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    private static void assumePosix() {
        Assumptions.assumeTrue(supportsPosix(), "POSIX-only ownership/mode assertion");
    }

    private static void setPosixPermissionsIfSupported(
            Path path, Set<PosixFilePermission> permissions) throws IOException {
        if (supportsPosix()) {
            Files.setPosixFilePermissions(path, permissions);
        }
    }

    private static void createSymbolicLinkOrAbort(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | SecurityException | IOException exception) {
            Assumptions.assumeTrue(false, "symbolic links are unavailable: " + exception.getClass());
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }

    private enum LaunchMode {
        SUCCESS,
        START_FAILURE,
        NON_ZERO,
        EMPTY,
        INVALID_MAGIC,
        TIMEOUT,
        INTERRUPTED,
        INTERRUPTED_STUBBORN,
        SWAP_OUTPUT,
        PUBLISH_COLLISION,
        BLOCKING
    }

    private static final class RecordingLauncher
            implements PgDumpRestorePointService.ProcessLauncher {
        private final LaunchMode mode;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<ProcessBuilder> builder = new AtomicReference<>();
        private final AtomicReference<List<String>> command = new AtomicReference<>();
        private final AtomicReference<Map<String, String>> environment = new AtomicReference<>();
        private final AtomicReference<Path> output = new AtomicReference<>();
        private final AtomicReference<ProcessBuilder.Redirect> errorRedirect = new AtomicReference<>();
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicReference<ControlledProcess> process = new AtomicReference<>();

        private RecordingLauncher(LaunchMode mode) {
            this.mode = mode;
        }

        static RecordingLauncher success() {
            return new RecordingLauncher(LaunchMode.SUCCESS);
        }

        static RecordingLauncher blocking() {
            return new RecordingLauncher(LaunchMode.BLOCKING);
        }

        @Override
        public Process start(ProcessBuilder candidate) throws IOException {
            calls.incrementAndGet();
            builder.set(candidate);
            command.set(List.copyOf(candidate.command()));
            environment.set(Map.copyOf(new LinkedHashMap<>(candidate.environment())));
            errorRedirect.set(candidate.redirectError());
            if (candidate.redirectOutput().file() == null) {
                throw new AssertionError("pg_dump stdout must target the protected partial file");
            }
            Path outputPath = candidate.redirectOutput().file().toPath();
            output.set(outputPath);

            if (mode == LaunchMode.START_FAILURE) {
                throw new IOException(FAILURE_MARKER + "-start-path-password");
            }
            switch (mode) {
                case EMPTY -> Files.write(outputPath, new byte[0], WRITE, TRUNCATE_EXISTING);
                case INVALID_MAGIC -> Files.write(
                        outputPath, "not-a-pgdump".getBytes(StandardCharsets.US_ASCII),
                        WRITE, TRUNCATE_EXISTING);
                case SWAP_OUTPUT -> {
                    Files.delete(outputPath);
                    Files.write(outputPath, VALID_ARCHIVE, CREATE_NEW, WRITE);
                }
                case PUBLISH_COLLISION -> {
                    Files.write(outputPath, VALID_ARCHIVE, WRITE, TRUNCATE_EXISTING);
                    Path published = outputPath.resolveSibling(
                            outputPath.getFileName().toString().replace(".part", ".dump"));
                    Files.write(published, PREEXISTING_ARCHIVE, CREATE_NEW, WRITE);
                }
                default -> Files.write(outputPath, VALID_ARCHIVE, WRITE, TRUNCATE_EXISTING);
            }

            ControlledProcess created = new ControlledProcess(mode, release);
            process.set(created);
            started.countDown();
            return created;
        }

        int calls() {
            return calls.get();
        }

        ProcessBuilder builder() {
            return builder.get();
        }

        List<String> commandAtStart() {
            return command.get();
        }

        Map<String, String> environmentAtStart() {
            return environment.get();
        }

        Path outputAtStart() {
            return output.get();
        }

        ProcessBuilder.Redirect errorRedirectAtStart() {
            return errorRedirect.get();
        }

        ControlledProcess process() {
            return process.get();
        }

        boolean awaitStarted(long timeout, TimeUnit unit) throws InterruptedException {
            return started.await(timeout, unit);
        }

        void releaseBlockingProcess() {
            release.countDown();
            ControlledProcess current = process.get();
            if (current != null) {
                current.release();
            }
        }
    }

    private static final class ControlledProcess extends Process {
        private final LaunchMode mode;
        private final CountDownLatch release;
        private final TrackingOutputStream stdin = new TrackingOutputStream();
        private final AtomicInteger timedWaits = new AtomicInteger();
        private final AtomicInteger unboundedWaits = new AtomicInteger();
        private final AtomicInteger destroys = new AtomicInteger();
        private final AtomicInteger forcedDestroys = new AtomicInteger();
        private final AtomicBoolean alive;
        private final AtomicBoolean interruptionDelivered = new AtomicBoolean();

        private ControlledProcess(LaunchMode mode, CountDownLatch release) {
            this.mode = mode;
            this.release = release;
            this.alive = new AtomicBoolean(
                    mode == LaunchMode.TIMEOUT
                            || mode == LaunchMode.INTERRUPTED
                            || mode == LaunchMode.INTERRUPTED_STUBBORN
                            || mode == LaunchMode.BLOCKING);
        }

        @Override
        public OutputStream getOutputStream() {
            return stdin;
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(
                    (FAILURE_MARKER + "-stderr").getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public int waitFor() {
            unboundedWaits.incrementAndGet();
            throw new AssertionError("unbounded process wait is forbidden");
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            timedWaits.incrementAndGet();
            if ((mode == LaunchMode.INTERRUPTED
                            || mode == LaunchMode.INTERRUPTED_STUBBORN)
                    && interruptionDelivered.compareAndSet(false, true)) {
                throw new InterruptedException(FAILURE_MARKER + "-interrupted");
            }
            if (mode == LaunchMode.BLOCKING && alive.get()) {
                if (release.await(timeout, unit)) {
                    alive.set(false);
                    return true;
                }
                return false;
            }
            return !alive.get();
        }

        @Override
        public int exitValue() {
            if (alive.get()) {
                throw new IllegalThreadStateException("process is still alive");
            }
            return mode == LaunchMode.NON_ZERO ? 17 : 0;
        }

        @Override
        public void destroy() {
            destroys.incrementAndGet();
        }

        @Override
        public Process destroyForcibly() {
            forcedDestroys.incrementAndGet();
            if (mode != LaunchMode.INTERRUPTED_STUBBORN) {
                alive.set(false);
                release.countDown();
            }
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }

        void release() {
            alive.set(false);
            release.countDown();
        }

        int timedWaitCalls() {
            return timedWaits.get();
        }

        int unboundedWaitCalls() {
            return unboundedWaits.get();
        }

        int destroyCalls() {
            return destroys.get();
        }

        int forceDestroyCalls() {
            return forcedDestroys.get();
        }

        boolean stdinClosed() {
            return stdin.closed();
        }
    }

    private static final class TrackingOutputStream extends ByteArrayOutputStream {
        private boolean closed;

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }

        boolean closed() {
            return closed;
        }
    }

    private static final class MetadataDataSource {
        private final String url;
        private final String currentDatabase;
        private final String serverAddress;
        private final int serverPort;
        private final boolean ssl;
        private final boolean gssEncrypted;
        private final String backendAddress;
        private final int backendPort;
        private final SQLException connectionFailure;
        private final DataSource dataSource;
        private final AtomicInteger connections = new AtomicInteger();
        private final AtomicInteger currentDatabaseQueries = new AtomicInteger();

        private MetadataDataSource(
                String url,
                String currentDatabase,
                String serverAddress,
                int serverPort,
                boolean ssl,
                boolean gssEncrypted,
                String backendAddress,
                int backendPort,
                SQLException connectionFailure) {
            this.url = url;
            this.currentDatabase = currentDatabase;
            this.serverAddress = serverAddress;
            this.serverPort = serverPort;
            this.ssl = ssl;
            this.gssEncrypted = gssEncrypted;
            this.backendAddress = backendAddress;
            this.backendPort = backendPort;
            this.connectionFailure = connectionFailure;
            this.dataSource = proxy(DataSource.class, this::handleDataSource);
        }

        static MetadataDataSource success(String url, String currentDatabase) {
            return transport(url, currentDatabase, SERVER_ADDRESS, SERVER_PORT, false, false);
        }

        static MetadataDataSource transport(
                String url,
                String currentDatabase,
                String serverAddress,
                int serverPort,
                boolean ssl) {
            return transport(url, currentDatabase, serverAddress, serverPort, ssl, false);
        }

        static MetadataDataSource transport(
                String url,
                String currentDatabase,
                String serverAddress,
                int serverPort,
                boolean ssl,
                boolean gssEncrypted) {
            return new MetadataDataSource(
                    url,
                    currentDatabase,
                    serverAddress,
                    serverPort,
                    ssl,
                    gssEncrypted,
                    BACKEND_ADDRESS,
                    BACKEND_PORT,
                    null);
        }

        static MetadataDataSource transportWithBackend(
                String url,
                String currentDatabase,
                String serverAddress,
                int serverPort,
                String backendAddress,
                int backendPort,
                boolean ssl,
                boolean gssEncrypted) {
            return new MetadataDataSource(
                    url,
                    currentDatabase,
                    serverAddress,
                    serverPort,
                    ssl,
                    gssEncrypted,
                    backendAddress,
                    backendPort,
                    null);
        }

        static MetadataDataSource failure(SQLException failure) {
            return new MetadataDataSource(
                    null, null, null, 0, false, false, null, 0, failure);
        }

        int connectionCount() {
            return connections.get();
        }

        int currentDatabaseQueryCount() {
            return currentDatabaseQueries.get();
        }

        DataSource dataSource() {
            return dataSource;
        }

        private Object handleDataSource(Object proxy, Method method, Object[] arguments)
                throws Throwable {
            return switch (method.getName()) {
                case "getConnection" -> {
                    connections.incrementAndGet();
                    if (connectionFailure != null) {
                        throw connectionFailure;
                    }
                    yield proxy(Connection.class, this::handleConnection);
                }
                case "unwrap" -> unwrap(proxy, arguments);
                case "isWrapperFor" -> isWrapperFor(proxy, arguments);
                case "toString" -> "MetadataDataSource[url=<redacted>]";
                default -> defaultValue(method.getReturnType());
            };
        }

        private Object handleConnection(Object proxy, Method method, Object[] arguments)
                throws Throwable {
            return switch (method.getName()) {
                case "getMetaData" -> proxy(DatabaseMetaData.class, this::handleMetadata);
                case "createStatement" -> proxy(Statement.class, this::handleStatement);
                case "prepareStatement" -> proxy(PreparedStatement.class, this::handleStatement);
                case "close" -> null;
                case "isClosed" -> false;
                case "isValid" -> true;
                case "getAutoCommit" -> true;
                case "unwrap" -> unwrap(proxy, arguments);
                case "isWrapperFor" -> isWrapperFor(proxy, arguments);
                case "toString" -> "MetadataConnection[url=<redacted>]";
                default -> defaultValue(method.getReturnType());
            };
        }

        private Object handleMetadata(Object proxy, Method method, Object[] arguments)
                throws Throwable {
            return switch (method.getName()) {
                case "getURL" -> url;
                case "getDatabaseProductName" -> "PostgreSQL";
                case "getUserName" -> "portfolio_runtime";
                case "unwrap" -> unwrap(proxy, arguments);
                case "isWrapperFor" -> isWrapperFor(proxy, arguments);
                case "toString" -> "DatabaseMetaData[url=<redacted>]";
                default -> defaultValue(method.getReturnType());
            };
        }

        private Object handleStatement(Object proxy, Method method, Object[] arguments)
                throws Throwable {
            return switch (method.getName()) {
                case "executeQuery" -> {
                    if (arguments != null && arguments.length > 0 && arguments[0] instanceof String sql) {
                        assertThat(sql.toLowerCase())
                                .contains("current_database()", "pg_stat_ssl", "pg_stat_gssapi");
                    }
                    currentDatabaseQueries.incrementAndGet();
                    yield resultSet();
                }
                case "close" -> null;
                case "unwrap" -> unwrap(proxy, arguments);
                case "isWrapperFor" -> isWrapperFor(proxy, arguments);
                case "toString" -> "CurrentDatabaseStatement";
                default -> defaultValue(method.getReturnType());
            };
        }

        private ResultSet resultSet() {
            AtomicBoolean delivered = new AtomicBoolean();
            AtomicReference<Boolean> wasNull = new AtomicReference<>(false);
            return proxy(ResultSet.class, (proxy, method, arguments) -> switch (method.getName()) {
                case "next" -> currentDatabase != null && delivered.compareAndSet(false, true);
                case "getString" -> {
                    int column = (Integer) arguments[0];
                    String value = column == 1 ? currentDatabase : backendAddress;
                    wasNull.set(value == null);
                    yield value;
                }
                case "getInt" -> {
                    wasNull.set(false);
                    yield backendPort;
                }
                case "getBoolean" -> {
                    wasNull.set(false);
                    int column = (Integer) arguments[0];
                    yield column == 4 ? ssl : gssEncrypted;
                }
                case "wasNull" -> wasNull.get();
                case "close" -> null;
                case "unwrap" -> unwrap(proxy, arguments);
                case "isWrapperFor" -> isWrapperFor(proxy, arguments);
                case "toString" -> "CurrentDatabaseResult[database=<redacted>]";
                default -> defaultValue(method.getReturnType());
            });
        }

        private static Object unwrap(Object proxy, Object[] arguments) throws SQLException {
            Class<?> type = (Class<?>) arguments[0];
            if (type.isInstance(proxy)) {
                return type.cast(proxy);
            }
            throw new SQLException("not a wrapper");
        }

        private static boolean isWrapperFor(Object proxy, Object[] arguments) {
            return ((Class<?>) arguments[0]).isInstance(proxy);
        }

        @SuppressWarnings("unchecked")
        private static <T> T proxy(Class<T> type, InvocationHandler handler) {
            return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
        }

        private static Object defaultValue(Class<?> type) {
            return type.isPrimitive() ? Array.get(Array.newInstance(type, 1), 0) : null;
        }
    }
}
