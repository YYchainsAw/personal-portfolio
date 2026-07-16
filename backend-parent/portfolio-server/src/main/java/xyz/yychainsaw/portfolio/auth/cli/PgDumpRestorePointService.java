package xyz.yychainsaw.portfolio.auth.cli;

import static com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;
import static java.nio.file.attribute.PosixFilePermission.GROUP_WRITE;
import static java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import javax.net.SocketFactory;
import javax.net.ssl.SSLSocket;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public final class PgDumpRestorePointService implements DatabaseRestorePointService {
    private static final String FAILURE_MESSAGE =
            "database restore point could not be created";
    private static final String LOCK_NAME = ".portfolio-maintenance.lock";
    private static final String RUNTIME_IDENTITY_SQL = """
            select pg_catalog.current_database(),
                   pg_catalog.host(pg_catalog.inet_server_addr()),
                   pg_catalog.inet_server_port(),
                   coalesce((select ssl
                             from pg_catalog.pg_stat_ssl
                             where pid=pg_catalog.pg_backend_pid()), false),
                   coalesce((select encrypted
                             from pg_catalog.pg_stat_gssapi
                             where pid=pg_catalog.pg_backend_pid()), false)
            """;
    private static final String JDBC_PREFIX = "jdbc:postgresql://";
    private static final byte[] CUSTOM_ARCHIVE_MAGIC =
            "PGDMP".getBytes(StandardCharsets.US_ASCII);
    private static final int HASH_BUFFER_SIZE = 8192;
    private static final int MAXIMUM_JDBC_URL_LENGTH = 4096;
    private static final int MAXIMUM_DATABASE_NAME_LENGTH = 255;
    private static final int MAXIMUM_HOST_LENGTH = 255;
    private static final int NAME_ATTEMPTS = 16;
    private static final Duration TERMINATION_GRACE = Duration.ofSeconds(2);
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
            Set.copyOf(PosixFilePermissions.fromString("rwx------"));
    private static final Set<PosixFilePermission> FILE_PERMISSIONS =
            Set.copyOf(PosixFilePermissions.fromString("rw-------"));
    private static final Set<String> SAFE_JDBC_QUERY_KEYS =
            Set.of("currentschema");
    private static final Map<String, String> SAFE_DATA_SOURCE_PROPERTIES =
            Map.of("ApplicationName", "portfolio-server");

    private final RecoveryProperties properties;
    private final DataSource dataSource;
    private final Clock clock;
    private final ProcessLauncher processLauncher;
    private final RuntimeTransportInspector transportInspector;

    @Autowired
    public PgDumpRestorePointService(
            RecoveryProperties properties, DataSource dataSource, Clock clock) {
        this(
                properties,
                dataSource,
                clock,
                ProcessBuilder::start,
                PgDumpRestorePointService::inspectStandardTransport);
    }

    PgDumpRestorePointService(
            RecoveryProperties properties,
            DataSource dataSource,
            Clock clock,
            ProcessLauncher processLauncher,
            RuntimeTransportInspector transportInspector) {
        this.properties = require(properties, "recovery properties are required");
        this.dataSource = require(dataSource, "runtime data source is required");
        this.clock = require(clock, "UTC clock is required");
        if (!ZoneOffset.UTC.equals(clock.getZone().normalized())) {
            throw new IllegalArgumentException("restore-point clock must use UTC");
        }
        this.processLauncher = require(processLauncher, "process launcher is required");
        this.transportInspector = require(
                transportInspector, "runtime transport inspector is required");
    }

    @Override
    public RestorePoint create() {
        Path partialPath = null;
        boolean completed = false;
        try {
            Target target = deriveRuntimeTarget();
            TrustedExecutable executable = validateExecutable(properties.pgDumpBin());
            Path directory = prepareRecoveryDirectory();
            RestorePoint result;
            try (LockHandle ignored = acquireLock(directory)) {
                CreatedPartial partial = createPartial(directory);
                partialPath = partial.path();
                Path publishedPath = publishedPath(directory, partialPath);
                ValidatedArchive archive;
                try (FileChannel deletionGuard = openWindowsDeletionGuard(partialPath)) {
                    runPgDump(executable, target, partialPath);
                    archive = validateArchive(partialPath, partial.identity());
                }
                publish(partialPath, publishedPath, archive.identity(), directory);
                result = new RestorePoint(publishedPath, archive.sha256());
            }
            completed = true;
            return result;
        } catch (Exception failure) {
            throw fixedFailure();
        } finally {
            if (!completed) {
                deleteQuietly(partialPath);
            }
        }
    }

    private Target deriveRuntimeTarget() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            if (connection == null) {
                throw operationFailure();
            }
            DatabaseMetaData metadata = connection.getMetaData();
            if (metadata == null || !"PostgreSQL".equals(metadata.getDatabaseProductName())) {
                throw operationFailure();
            }
            Target declaredTarget = parseTarget(metadata.getURL());
            PeerEndpoint peer = require(
                    transportInspector.inspect(
                            dataSource,
                            connection,
                            declaredTarget.host(),
                            declaredTarget.port()),
                    "runtime peer is required");
            if (!validRuntimeText(peer.host(), MAXIMUM_HOST_LENGTH)
                    || peer.port() != declaredTarget.port()) {
                throw operationFailure();
            }
            try (PreparedStatement statement = connection.prepareStatement(RUNTIME_IDENTITY_SQL);
                    ResultSet result = statement.executeQuery()) {
                if (result == null || !result.next()) {
                    throw operationFailure();
                }
                String currentDatabase = result.getString(1);
                boolean databaseWasNull = result.wasNull();
                String serverAddress = result.getString(2);
                boolean serverAddressWasNull = result.wasNull();
                int serverPort = result.getInt(3);
                boolean serverPortWasNull = result.wasNull();
                boolean ssl = result.getBoolean(4);
                boolean sslWasNull = result.wasNull();
                boolean gssEncrypted = result.getBoolean(5);
                boolean gssWasNull = result.wasNull();
                if (databaseWasNull
                        || serverAddressWasNull
                        || serverPortWasNull
                        || sslWasNull
                        || gssWasNull
                        || !validRuntimeText(serverAddress, MAXIMUM_HOST_LENGTH)
                        || serverPort < 1
                        || serverPort > 65535
                        || ssl
                        || gssEncrypted
                        || !declaredTarget.database().equals(currentDatabase)
                        || result.next()) {
                    throw operationFailure();
                }
            }
            return new Target(peer.host(), peer.port(), declaredTarget.database());
        }
    }

    private static Target parseTarget(String jdbcUrl) throws OperationFailure {
        if (jdbcUrl == null
                || jdbcUrl.length() > MAXIMUM_JDBC_URL_LENGTH
                || !jdbcUrl.startsWith(JDBC_PREFIX)) {
            throw operationFailure();
        }
        try {
            URI uri = new URI(jdbcUrl.substring("jdbc:".length()));
            String authority = uri.getRawAuthority();
            String host = uri.getHost();
            String path = uri.getPath();
            if (uri.isOpaque()
                    || !"postgresql".equals(uri.getScheme())
                    || authority == null
                    || authority.indexOf(',') >= 0
                    || authority.indexOf('\\') >= 0
                    || uri.getRawUserInfo() != null
                    || uri.getFragment() != null
                    || host == null
                    || uri.getPort() < 1
                    || uri.getPort() > 65535
                    || path == null
                    || path.length() < 2
                    || path.charAt(0) != '/'
                    || path.indexOf('/', 1) >= 0) {
                throw operationFailure();
            }
            if (host.startsWith("[") && host.endsWith("]")) {
                host = host.substring(1, host.length() - 1);
            }
            String database = path.substring(1);
            if (!validRuntimeText(host, MAXIMUM_HOST_LENGTH)
                    || !validRuntimeText(database, MAXIMUM_DATABASE_NAME_LENGTH)
                    || unsafeQuery(uri.getRawQuery())) {
                throw operationFailure();
            }
            return new Target(host, uri.getPort(), database);
        } catch (IllegalArgumentException | URISyntaxException invalidTarget) {
            throw operationFailure();
        }
    }

    private static boolean unsafeQuery(String rawQuery) {
        if (rawQuery == null) {
            return false;
        }
        Set<String> observed = new HashSet<>();
        for (String rawParameter : rawQuery.split("&", -1)) {
            int separator = rawParameter.indexOf('=');
            if (separator < 1) {
                return true;
            }
            String key = URLDecoder.decode(
                            rawParameter.substring(0, separator), StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT);
            String value = URLDecoder.decode(
                    rawParameter.substring(separator + 1), StandardCharsets.UTF_8);
            if (!SAFE_JDBC_QUERY_KEYS.contains(key)
                    || !observed.add(key)
                    || value.isBlank()
                    || containsControl(value)) {
                return true;
            }
        }
        return false;
    }

    private TrustedExecutable validateExecutable(String configured) throws Exception {
        Path executable = Path.of(configured);
        if (!executable.isAbsolute() || !executable.equals(executable.normalize())) {
            throw operationFailure();
        }
        NodeIdentity identity = readNode(executable, NodeKind.REGULAR_FILE);
        if (!Files.isExecutable(executable)) {
            throw operationFailure();
        }
        requireTrustedExecutable(identity);
        for (Path parent = executable.getParent(); parent != null; parent = parent.getParent()) {
            requireTrustedReplaceableDirectory(parent);
        }
        return new TrustedExecutable(executable, identity);
    }

    private Path prepareRecoveryDirectory() throws Exception {
        Path directory = properties.directory();
        Path parent = directory.getParent();
        if (parent == null) {
            throw operationFailure();
        }
        NodeIdentity parentBefore = requireTrustedReplaceableDirectory(parent);
        if (!Files.exists(directory, NOFOLLOW_LINKS)) {
            try {
                createOwnerOnlyDirectory(directory);
            } catch (FileAlreadyExistsException concurrentCreation) {
                // The no-follow validation below decides whether the winner is trusted.
            }
        }
        NodeIdentity leaf = readNode(directory, NodeKind.DIRECTORY);
        requireCurrentOwner(leaf);
        requireExactPermissions(leaf, DIRECTORY_PERMISSIONS);
        NodeIdentity parentAfter = requireTrustedReplaceableDirectory(parent);
        if (!parentBefore.sameNodeAndSecurity(parentAfter)) {
            throw operationFailure();
        }
        return directory;
    }

    private LockHandle acquireLock(Path directory) throws Exception {
        Path lockPath = containedChild(directory, LOCK_NAME);
        NodeIdentity before = null;
        if (Files.exists(lockPath, NOFOLLOW_LINKS)) {
            before = readProtectedRegularFile(lockPath, true);
        }

        FileChannel channel = null;
        try {
            channel = openOwnerOnlyFile(lockPath, false);
            NodeIdentity after = readProtectedRegularFile(lockPath, true);
            if (before != null && !before.sameNodeAndSecurity(after)) {
                throw operationFailure();
            }
            FileLock lock = channel.tryLock();
            if (lock == null) {
                throw operationFailure();
            }
            return new LockHandle(channel, lock);
        } catch (Exception failure) {
            closeQuietly(channel);
            throw failure;
        }
    }

    private CreatedPartial createPartial(Path directory) throws Exception {
        Instant instant = clock.instant();
        for (int attempt = 0; attempt < NAME_ATTEMPTS; attempt++) {
            String name = "restore-" + instant.getEpochSecond() + '-' + instant.getNano()
                    + '-' + UUID.randomUUID() + ".part";
            Path candidate = containedChild(directory, name);
            boolean created = false;
            try {
                try (FileChannel ignored = openOwnerOnlyFile(candidate, true)) {
                    created = true;
                    // Atomic CREATE_NEW with owner-only permissions is the whole creation operation.
                }
                NodeIdentity identity = readProtectedRegularFile(candidate, true);
                if (identity.size() != 0) {
                    throw operationFailure();
                }
                return new CreatedPartial(candidate, identity);
            } catch (FileAlreadyExistsException collision) {
                continue;
            } catch (Exception failure) {
                if (created) {
                    deleteQuietly(candidate);
                }
                throw failure;
            }
        }
        throw operationFailure();
    }

    private void runPgDump(
            TrustedExecutable executable, Target target, Path partialPath) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(List.of(
                executable.path().toString(),
                "--format=custom",
                "--no-owner",
                "--no-acl",
                "--no-password"));
        Map<String, String> environment = builder.environment();
        environment.clear();
        environment.put("PGHOST", target.host());
        environment.put("PGPORT", Integer.toString(target.port()));
        environment.put("PGDATABASE", target.database());
        environment.put("PGUSER", properties.username());
        environment.put("PGPASSWORD", properties.password());
        environment.put("PGCONNECT_TIMEOUT", connectTimeoutSeconds(properties.timeout()));
        environment.put("PGSSLMODE", "disable");
        environment.put("PGGSSENCMODE", "disable");
        environment.put("LC_ALL", "C");
        environment.put("TZ", "UTC");
        builder.redirectOutput(partialPath.toFile());
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);

        Process process = null;
        try {
            try {
                TrustedExecutable atStart = validateExecutable(properties.pgDumpBin());
                if (!executable.path().equals(atStart.path())
                        || !executable.identity().sameStable(atStart.identity())) {
                    throw operationFailure();
                }
                process = processLauncher.start(builder);
            } finally {
                environment.clear();
            }
            if (process == null) {
                throw operationFailure();
            }
            process.getOutputStream().close();
            awaitSuccessfulExit(process);
        } catch (Exception failure) {
            if (process != null && process.isAlive()) {
                terminateBounded(process);
            }
            throw failure;
        }
    }

    private void awaitSuccessfulExit(Process process) throws Exception {
        boolean exited;
        try {
            exited = process.waitFor(properties.timeout().toNanos(), NANOSECONDS);
        } catch (InterruptedException interrupted) {
            try {
                terminateBounded(process);
            } finally {
                Thread.currentThread().interrupt();
            }
            throw operationFailure();
        }
        if (!exited) {
            terminateBounded(process);
            throw operationFailure();
        }
        if (process.exitValue() != 0) {
            throw operationFailure();
        }
    }

    private static void terminateBounded(Process process) throws OperationFailure {
        boolean interrupted = false;
        boolean exited = false;
        try {
            process.destroy();
            try {
                exited = process.waitFor(TERMINATION_GRACE.toNanos(), NANOSECONDS);
            } catch (InterruptedException terminationInterrupted) {
                interrupted = true;
            }
            if (!exited) {
                process.destroyForcibly();
                try {
                    exited = process.waitFor(TERMINATION_GRACE.toNanos(), NANOSECONDS);
                } catch (InterruptedException terminationInterrupted) {
                    interrupted = true;
                }
            }
            if (!exited || process.isAlive()) {
                throw operationFailure();
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private ValidatedArchive validateArchive(Path path, NodeIdentity created) throws Exception {
        NodeIdentity before = readProtectedRegularFile(path, true);
        if (!created.sameNodeAndSecurity(before)
                || before.size() < CUSTOM_ARCHIVE_MAGIC.length) {
            throw operationFailure();
        }

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[HASH_BUFFER_SIZE];
        byte[] observedMagic = new byte[CUSTOM_ARCHIVE_MAGIC.length];
        long total = 0;
        int magicBytes = 0;
        try (FileChannel channel = FileChannel.open(
                path, Set.of(READ, WRITE, NOFOLLOW_LINKS))) {
            if (channel.size() != before.size()) {
                throw operationFailure();
            }
            ByteBuffer byteBuffer = ByteBuffer.wrap(buffer);
            while (true) {
                byteBuffer.clear();
                int count = channel.read(byteBuffer);
                if (count < 0) {
                    break;
                }
                if (count == 0) {
                    continue;
                }
                if (magicBytes < observedMagic.length) {
                    int copy = Math.min(count, observedMagic.length - magicBytes);
                    System.arraycopy(buffer, 0, observedMagic, magicBytes, copy);
                    magicBytes += copy;
                }
                digest.update(buffer, 0, count);
                total += count;
            }
            if (total != before.size()
                    || magicBytes != observedMagic.length
                    || !MessageDigest.isEqual(CUSTOM_ARCHIVE_MAGIC, observedMagic)) {
                throw operationFailure();
            }
            channel.force(true);
            if (channel.size() != before.size()) {
                throw operationFailure();
            }
        } finally {
            Arrays.fill(buffer, (byte) 0);
            Arrays.fill(observedMagic, (byte) 0);
        }

        NodeIdentity after = readProtectedRegularFile(path, true);
        if (!before.sameStable(after) || !created.sameNodeAndSecurity(after)) {
            throw operationFailure();
        }
        return new ValidatedArchive(HexFormat.of().formatHex(digest.digest()), after);
    }

    private static void publish(
            Path partialPath,
            Path publishedPath,
            NodeIdentity validatedIdentity,
            Path directory) throws Exception {
        boolean linkCreated = false;
        try {
            Files.createLink(publishedPath, partialPath);
            linkCreated = true;
            NodeIdentity linkedIdentity = readNode(publishedPath, NodeKind.REGULAR_FILE);
            if (!validatedIdentity.sameStable(linkedIdentity)) {
                throw operationFailure();
            }
            Files.delete(partialPath);
            NodeIdentity publishedIdentity = readProtectedRegularFile(publishedPath, true);
            if (!validatedIdentity.sameStable(publishedIdentity)) {
                throw operationFailure();
            }
            syncDirectory(directory);
        } catch (Exception failure) {
            if (linkCreated) {
                deleteIfSameIdentityQuietly(publishedPath, validatedIdentity);
            }
            throw failure;
        }
    }

    private static Path publishedPath(Path directory, Path partialPath)
            throws OperationFailure {
        String partialName = partialPath.getFileName().toString();
        if (!partialName.endsWith(".part")) {
            throw operationFailure();
        }
        return containedChild(
                directory, partialName.substring(0, partialName.length() - ".part".length()) + ".dump");
    }

    private static Path containedChild(Path directory, String name) throws OperationFailure {
        if (name == null || name.isBlank() || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
            throw operationFailure();
        }
        Path child = directory.resolve(name).normalize();
        if (!directory.equals(child.getParent())) {
            throw operationFailure();
        }
        return child;
    }

    private static NodeIdentity requireTrustedReplaceableDirectory(Path directory)
            throws Exception {
        NodeIdentity identity = readNode(directory, NodeKind.DIRECTORY);
        if (identity.permissions() != null) {
            if (!trustedOwner(identity.owner()) || hasUnsafeWritePermission(identity.permissions())) {
                throw operationFailure();
            }
        }
        return identity;
    }

    private static void requireTrustedExecutable(NodeIdentity identity)
            throws OperationFailure {
        if (identity.permissions() != null
                && (!trustedOwner(identity.owner())
                        || hasUnsafeWritePermission(identity.permissions()))) {
            throw operationFailure();
        }
    }

    private static NodeIdentity readProtectedRegularFile(Path path, boolean requireSingleLink)
            throws Exception {
        NodeIdentity identity = readNode(path, NodeKind.REGULAR_FILE);
        requireCurrentOwner(identity);
        requireExactPermissions(identity, FILE_PERMISSIONS);
        if (requireSingleLink && supportsUnix(path)) {
            Object linkCount = Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS);
            if (!(linkCount instanceof Number number) || number.longValue() != 1L) {
                throw operationFailure();
            }
        }
        return identity;
    }

    private static NodeIdentity readNode(Path path, NodeKind kind) throws Exception {
        BasicFileAttributes attributes;
        String owner = null;
        Set<PosixFilePermission> permissions = null;
        if (supportsPosix(path)) {
            PosixFileAttributes posix = Files.readAttributes(
                    path, PosixFileAttributes.class, NOFOLLOW_LINKS);
            attributes = posix;
            owner = posix.owner().getName();
            permissions = Set.copyOf(posix.permissions());
        } else {
            attributes = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
        }
        if (attributes.isSymbolicLink()
                || (kind == NodeKind.REGULAR_FILE && !attributes.isRegularFile())
                || (kind == NodeKind.DIRECTORY && !attributes.isDirectory())) {
            throw operationFailure();
        }
        Object fileKey = attributes.fileKey();
        if (fileKey == null && supportsUnix(path)) {
            fileKey = Files.getAttribute(path, "unix:ino", NOFOLLOW_LINKS);
        }
        return new NodeIdentity(
                fileKey,
                attributes.creationTime(),
                owner,
                permissions,
                attributes.size());
    }

    private static void requireCurrentOwner(NodeIdentity identity) throws OperationFailure {
        if (identity.permissions() != null && !currentOwner(identity.owner())) {
            throw operationFailure();
        }
    }

    private static void requireExactPermissions(
            NodeIdentity identity, Set<PosixFilePermission> expected)
            throws OperationFailure {
        if (identity.permissions() != null && !identity.permissions().equals(expected)) {
            throw operationFailure();
        }
    }

    private static boolean trustedOwner(String owner) {
        return "root".equals(owner) || currentOwner(owner);
    }

    private static boolean currentOwner(String owner) {
        String current = System.getProperty("user.name", "");
        return !current.isBlank()
                && owner != null
                && (owner.equals(current)
                        || owner.endsWith("\\" + current)
                        || owner.endsWith("/" + current));
    }

    private static boolean hasUnsafeWritePermission(Set<PosixFilePermission> permissions) {
        return permissions.contains(GROUP_WRITE) || permissions.contains(OTHERS_WRITE);
    }

    private static void createOwnerOnlyDirectory(Path directory) throws IOException {
        if (supportsPosix(directory)) {
            Files.createDirectory(directory, ownerDirectoryAttribute());
        } else {
            Files.createDirectory(directory);
        }
    }

    private static FileChannel openOwnerOnlyFile(Path path, boolean createNew)
            throws IOException {
        Set<OpenOption> options = new HashSet<>();
        options.add(createNew ? CREATE_NEW : CREATE);
        options.add(WRITE);
        options.add(NOFOLLOW_LINKS);
        if (supportsPosix(path)) {
            return FileChannel.open(path, options, ownerFileAttribute());
        }
        return FileChannel.open(path, options);
    }

    private static FileChannel openWindowsDeletionGuard(Path path) throws IOException {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
            return null;
        }
        return FileChannel.open(path, Set.of(READ, NOFOLLOW_LINKS, NOSHARE_DELETE));
    }

    private static FileAttribute<Set<PosixFilePermission>> ownerDirectoryAttribute() {
        return PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS);
    }

    private static FileAttribute<Set<PosixFilePermission>> ownerFileAttribute() {
        return PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS);
    }

    private static boolean supportsPosix(Path path) {
        return path.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    private static boolean supportsUnix(Path path) {
        return path.getFileSystem().supportedFileAttributeViews().contains("unix");
    }

    private static void syncDirectory(Path directory) throws IOException {
        if (!supportsPosix(directory)) {
            return;
        }
        try (FileChannel channel = FileChannel.open(directory, READ)) {
            channel.force(true);
        } catch (UnsupportedOperationException unsupported) {
            // Some POSIX providers do not expose a forceable directory handle.
        }
    }

    private static String connectTimeoutSeconds(Duration timeout) {
        long nanoseconds = timeout.toNanos();
        long seconds = (nanoseconds + NANOSECONDS.convert(1, java.util.concurrent.TimeUnit.SECONDS) - 1)
                / NANOSECONDS.convert(1, java.util.concurrent.TimeUnit.SECONDS);
        return Long.toString(Math.max(1L, seconds));
    }

    private static boolean validRuntimeText(String value, int maximumLength) {
        return value != null
                && !value.isBlank()
                && value.length() <= maximumLength
                && !containsControl(value);
    }

    private static boolean containsControl(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }

    static PeerEndpoint inspectStandardTransport(
            DataSource dataSource,
            Connection connection,
            String declaredHost,
            int declaredPort) throws Exception {
        ClassLoader loader = PgDumpRestorePointService.class.getClassLoader();
        Class<?> hikariType = Class.forName("com.zaxxer.hikari.HikariDataSource", false, loader);
        if (!hikariType.isInstance(dataSource)) {
            throw operationFailure();
        }
        Object configured = hikariType.getMethod("getDataSourceProperties").invoke(dataSource);
        if (!(configured instanceof Properties properties)
                || properties.size() != SAFE_DATA_SOURCE_PROPERTIES.size()) {
            throw operationFailure();
        }
        for (Map.Entry<String, String> expected : SAFE_DATA_SOURCE_PROPERTIES.entrySet()) {
            if (!expected.getValue().equals(properties.getProperty(expected.getKey()))) {
                throw operationFailure();
            }
        }

        Class<?> pgConnectionType = Class.forName(
                "org.postgresql.jdbc.PgConnection", false, loader);
        if (!connection.isWrapperFor(pgConnectionType)) {
            throw operationFailure();
        }
        Object pgConnection = connection.unwrap(pgConnectionType);
        Object queryExecutor = pgConnectionType
                .getMethod("getQueryExecutor")
                .invoke(pgConnection);
        Object hostSpec = queryExecutor.getClass().getMethod("getHostSpec").invoke(queryExecutor);
        String effectiveHost = (String) hostSpec.getClass().getMethod("getHost").invoke(hostSpec);
        int effectivePort = (Integer) hostSpec.getClass().getMethod("getPort").invoke(hostSpec);
        Object localSocketAddress = hostSpec.getClass()
                .getMethod("getLocalSocketAddress")
                .invoke(hostSpec);
        if (effectiveHost == null
                || !stripIpv6Brackets(effectiveHost).equalsIgnoreCase(stripIpv6Brackets(declaredHost))
                || effectivePort != declaredPort
                || localSocketAddress != null) {
            throw operationFailure();
        }

        Class<?> executorBaseType = Class.forName(
                "org.postgresql.core.QueryExecutorBase", false, loader);
        Field streamField = executorBaseType.getDeclaredField("pgStream");
        if (!streamField.trySetAccessible()) {
            throw operationFailure();
        }
        Object stream = streamField.get(queryExecutor);
        if (stream == null) {
            throw operationFailure();
        }
        Object configuredFactory = stream.getClass().getMethod("getSocketFactory").invoke(stream);
        SocketFactory defaultFactory = SocketFactory.getDefault();
        if (configuredFactory != defaultFactory) {
            throw operationFailure();
        }
        requireDirectJvmRoute(declaredHost, declaredPort);
        Object socketValue = stream.getClass().getMethod("getSocket").invoke(stream);
        if (!(socketValue instanceof Socket socket)
                || socket instanceof SSLSocket
                || !socket.isConnected()
                || socket.isClosed()
                || !(socket.getRemoteSocketAddress() instanceof InetSocketAddress remote)
                || remote.isUnresolved()
                || remote.getAddress() == null
                || remote.getPort() != declaredPort) {
            throw operationFailure();
        }
        Object gssEncrypted = stream.getClass().getMethod("isGssEncrypted").invoke(stream);
        if (!(gssEncrypted instanceof Boolean encrypted) || encrypted) {
            throw operationFailure();
        }
        return new PeerEndpoint(remote.getAddress().getHostAddress(), remote.getPort());
    }

    private static void requireDirectJvmRoute(String declaredHost, int declaredPort)
            throws Exception {
        String socksProxyHost = System.getProperty("socksProxyHost");
        if (socksProxyHost != null && !socksProxyHost.isBlank()) {
            throw operationFailure();
        }
        ProxySelector selector = ProxySelector.getDefault();
        if (selector == null) {
            throw operationFailure();
        }
        URI destination = new URI(
                "socket",
                null,
                stripIpv6Brackets(declaredHost),
                declaredPort,
                null,
                null,
                null);
        List<Proxy> routes = selector.select(destination);
        if (routes == null || routes.size() != 1 || routes.get(0) != Proxy.NO_PROXY) {
            throw operationFailure();
        }
    }

    private static String stripIpv6Brackets(String host) {
        if (host != null && host.length() > 1 && host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    private static <T> T require(T value, String message) {
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private static IllegalStateException fixedFailure() {
        return new IllegalStateException(FAILURE_MESSAGE);
    }

    private static OperationFailure operationFailure() {
        return new OperationFailure();
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
            // The public error remains fixed and cause-free even when cleanup is best effort.
        }
    }

    private static void deleteIfSameIdentityQuietly(Path path, NodeIdentity expected) {
        if (path == null || expected == null) {
            return;
        }
        try {
            NodeIdentity actual = readNode(path, NodeKind.REGULAR_FILE);
            if (expected.sameStable(actual)) {
                Files.deleteIfExists(path);
            }
        } catch (Exception ignored) {
            // Never turn cleanup of an owned publication into deletion of a foreign path.
        }
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // The caller will surface the fixed restore-point failure.
        }
    }

    @FunctionalInterface
    interface ProcessLauncher {
        Process start(ProcessBuilder builder) throws IOException;
    }

    @FunctionalInterface
    interface RuntimeTransportInspector {
        PeerEndpoint inspect(
                DataSource dataSource,
                Connection connection,
                String declaredHost,
                int declaredPort) throws Exception;
    }

    private enum NodeKind {
        REGULAR_FILE,
        DIRECTORY
    }

    record PeerEndpoint(String host, int port) {}

    private record Target(String host, int port, String database) {}

    private record TrustedExecutable(Path path, NodeIdentity identity) {}

    private record CreatedPartial(Path path, NodeIdentity identity) {}

    private record ValidatedArchive(String sha256, NodeIdentity identity) {}

    private record NodeIdentity(
            Object fileKey,
            FileTime creationTime,
            String owner,
            Set<PosixFilePermission> permissions,
            long size) {
        boolean sameNodeAndSecurity(NodeIdentity other) {
            if (other == null) {
                return false;
            }
            boolean sameKey = fileKey != null || other.fileKey != null
                    ? Objects.equals(fileKey, other.fileKey)
                    : Objects.equals(creationTime, other.creationTime);
            return sameKey
                    && Objects.equals(owner, other.owner)
                    && Objects.equals(permissions, other.permissions);
        }

        boolean sameStable(NodeIdentity other) {
            return sameNodeAndSecurity(other) && size == other.size;
        }
    }

    private static final class LockHandle implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;

        private LockHandle(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        @Override
        public void close() throws IOException {
            try {
                lock.release();
            } finally {
                channel.close();
            }
        }
    }

    private static final class OperationFailure extends Exception {
        private OperationFailure() {
            super(null, null, false, false);
        }
    }
}
