# Portfolio Media Storage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build secure local/COS media storage, validated high-resolution uploads, responsive image variants, durable background processing, and authenticated media management for the portfolio CMS.

**Architecture:** `portfolio-server` owns media persistence and feature-local entities. A provider-neutral `StorageService` is selected by `StorageRouter`; immutable originals remain private, derived variants are addressed by stable asset/variant IDs, and database-backed jobs perform retryable image processing and cleanup without Redis or a message queue.

**Tech Stack:** Java 17, Spring Boot 3.5.7, Maven Wrapper 3.9.11, MyBatis-Plus 3.5.7, PostgreSQL 17, Flyway, Jackson, Tencent COS XML Java SDK 5.6.227, JUnit 5, Testcontainers.

## Global Constraints

- Complete `2026-07-14-portfolio-01-foundation-auth.md` first; its Spring Security, Flyway, `AuditService`, `CurrentAdminProvider`, and `DomainException` interfaces are required here.
- Use package root `xyz.yychainsaw.portfolio`; persistence entities remain inside the `media` and `system.job` feature packages.
- Use UUID primary keys, `timestamptz`, UTC persistence, and lowercase snake_case database names.
- Production defaults to Tencent lightweight object storage or COS; LocalStorage remains available for development and persistent-volume fallback.
- A provider change affects only new uploads. Reads route by each asset's stored provider; no configuration change silently migrates objects.
- Originals are immutable, private, never upscaled, and never overwritten. Public JPEG/PNG variants use width ceilings 640/1280/1920/2560/3840.
- Accept JPEG, PNG, and PDF only. Reject SVG, HTML, scripts, executables, MIME spoofing, path traversal, images over 25 MiB, and PDFs over 30 MiB.
- Do not add Redis, RabbitMQ, Kafka, MinIO, a second service, uploaded video, or AVIF.
- COS credentials come only from environment variables; tests and logs must never print them.
- Every production code change follows red-green-refactor and ends with the exact focused test command plus a commit.

## File Map

- `backend-parent/portfolio-server/src/main/resources/db/migration/V3__media_and_background_jobs.sql`: media and durable-job schema.
- `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/domain/*`: provider, asset, variant, and status types.
- `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/storage/*`: storage port, router, Local, and COS adapters.
- `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/application/*`: upload, validation, processing, listing, archive, and preview use cases.
- `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/persistence/*`: MyBatis-Plus records and mappers.
- `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/web/*`: authenticated admin media endpoints.
- `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/system/job/*`: database job queue, lease worker, and handler contract reused by plans 03, 06, and 07.
- `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/media/*`: schema, storage contract, upload, processing, and API tests.

---

### Task 1: Add the media and durable-job schema

**Files:**
- Create: `backend-parent/portfolio-server/src/main/resources/db/migration/V3__media_and_background_jobs.sql`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/media/MediaSchemaMigrationTest.java`

**Interfaces:**
- Consumes: Plan 01 `xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase` and Flyway-managed PostgreSQL 17 datasource.
- Produces: `media_asset`, `media_variant`, `media_translation`, `background_job`, and `maintenance_run` tables used by every task in this plan.

- [ ] **Step 1: Write the failing migration test**

```java
package xyz.yychainsaw.portfolio.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
class MediaSchemaMigrationTest extends PostgresIntegrationTestBase {
    @Autowired JdbcTemplate jdbc;

    @Test
    void flywayCreatesMediaAndJobTables() {
        List<String> tables = jdbc.queryForList("""
            select table_name from information_schema.tables
            where table_schema = 'portfolio'
              and table_name in ('media_asset','media_variant','media_translation','background_job','maintenance_run')
            order by table_name
            """, String.class);

        assertThat(tables).containsExactly(
            "background_job", "maintenance_run", "media_asset", "media_translation", "media_variant");
    }
}
```

- [ ] **Step 2: Run the migration test and verify it fails**

Run from `backend-parent`:

```powershell
.\mvnw.cmd -pl portfolio-server -am -Dtest=MediaSchemaMigrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL because `media_asset` and the other V3 tables do not exist.

- [ ] **Step 3: Create the V3 migration**

```sql
create table background_job (
    id uuid primary key,
    job_type varchar(64) not null,
    idempotency_key varchar(160) not null unique,
    payload jsonb not null,
    status varchar(24) not null check (status in ('PENDING','RUNNING','SUCCEEDED','FAILED','DEAD')),
    attempts integer not null default 0 check (attempts >= 0),
    next_run_at timestamptz not null,
    lease_owner varchar(120),
    lease_until timestamptz,
    last_error_summary varchar(500),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);
create index background_job_ready_idx on background_job (next_run_at, created_at)
    where status in ('PENDING','FAILED');
create index background_job_expired_lease_idx on background_job (lease_until)
    where status = 'RUNNING';

create table maintenance_run (
    id uuid primary key,
    run_type varchar(64) not null,
    status varchar(24) not null check (status in ('RUNNING','SUCCEEDED','FAILED')),
    artifact_checksum varchar(64),
    error_summary varchar(500),
    started_at timestamptz not null,
    finished_at timestamptz
);

create table media_asset (
    id uuid primary key,
    provider varchar(24) not null check (provider in ('LOCAL','TENCENT_COS')),
    bucket varchar(128),
    region varchar(64),
    object_key varchar(512) not null,
    original_filename varchar(255) not null,
    mime_type varchar(100) not null,
    byte_size bigint not null check (byte_size > 0),
    width integer,
    height integer,
    sha256 char(64) not null,
    status varchar(24) not null check (status in ('PROCESSING','READY','FAILED','ARCHIVED','PENDING_DELETE')),
    archived_at timestamptz,
    version integer not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique(provider, object_key)
);
create index media_asset_status_idx on media_asset(status, created_at desc);
create index media_asset_sha_idx on media_asset(sha256);

create table media_variant (
    id uuid primary key,
    asset_id uuid not null references media_asset(id) on delete restrict,
    variant_name varchar(32) not null,
    format varchar(16) not null check (format in ('JPEG','PNG','PDF')),
    object_key varchar(512) not null,
    mime_type varchar(100) not null,
    byte_size bigint not null check (byte_size > 0),
    width integer,
    height integer,
    sha256 char(64) not null,
    status varchar(24) not null check (status in ('PROCESSING','READY','FAILED')),
    created_at timestamptz not null default now(),
    unique(asset_id, variant_name)
);

create table media_translation (
    asset_id uuid not null references media_asset(id) on delete cascade,
    locale varchar(10) not null check (locale in ('zh-CN','en')),
    alt_text varchar(500),
    caption varchar(1000),
    credit varchar(300),
    source_url varchar(2048),
    primary key(asset_id, locale)
);

grant select, insert, update, delete on background_job to portfolio_runtime;
grant select, insert, update on maintenance_run to portfolio_runtime;
grant select, insert, update, delete on media_asset to portfolio_runtime;
grant select, insert, update, delete on media_variant to portfolio_runtime;
grant select, insert, update, delete on media_translation to portfolio_runtime;
```

- [ ] **Step 4: Re-run the migration test**

Run the Step 2 command.

Expected: PASS; Flyway validates V1, V2, and V3 and all five expected tables are present.

- [ ] **Step 5: Commit the schema slice**

```powershell
git add backend-parent/portfolio-server/src/main/resources/db/migration/V3__media_and_background_jobs.sql backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/media/MediaSchemaMigrationTest.java
git commit -m "feat(media): add media and job schema"
```

---

### Task 2: Define storage contracts and a safe LocalStorage adapter

**Files:**
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/domain/StorageProvider.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/storage/StoredObject.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/storage/ByteRange.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/storage/StorageRead.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/storage/BoundedInputStream.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/storage/StorageRangeNotSatisfiableException.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/storage/StorageException.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/storage/StorageService.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/storage/StorageRouter.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/storage/DefaultStorageRouter.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/storage/StorageDefaults.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/storage/LocalStorageProperties.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/storage/LocalStorageService.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/media/storage/LocalStorageServiceTest.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/media/storage/StorageRouterTest.java`

**Interfaces:**
- Consumes: Spring `Resource`, `ConfigurationProperties`, Java streams, and `StorageProvider` persisted by Task 1.
- Produces:
  - `StorageService#put(String, InputStream, long, String): StoredObject`
  - `StorageService#open(String, Optional<ByteRange>): StorageRead`
  - `StorageService#signedGet(String, Duration): URI`
  - `StorageService#exists(String): boolean`
  - `StorageService#copy(String, String): void`
  - `StorageService#delete(String): void`
  - `StorageRouter#defaultWriter(): StorageService`
  - `StorageRouter#require(StorageProvider): StorageService`

- [ ] **Step 1: Write the storage contract and Local path traversal tests**

```java
package xyz.yychainsaw.portfolio.media.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalStorageServiceTest {
    @TempDir Path root;

    @Test
    void writesAndReadsWithinConfiguredRoot() throws Exception {
        LocalStorageService service = new LocalStorageService(new LocalStorageProperties(root));
        byte[] bytes = "portfolio".getBytes(StandardCharsets.UTF_8);
        service.put("originals/asset.bin", new ByteArrayInputStream(bytes), bytes.length, "application/octet-stream");
        try (var input = service.open("originals/asset.bin", Optional.empty()).inputStream()) {
            assertThat(input.readAllBytes()).isEqualTo(bytes);
        }
    }

    @Test
    void rejectsTraversalOutsideRoot() {
        LocalStorageService service = new LocalStorageService(new LocalStorageProperties(root));
        assertThatThrownBy(() -> service.open("../../Windows/System32/config", Optional.empty()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid storage object key");
    }

    @Test
    void readsOnlyTheRequestedInclusiveRange() throws Exception {
        byte[] bytes = "portfolio".getBytes(StandardCharsets.UTF_8);
        service.put("variants/asset.txt", new ByteArrayInputStream(bytes), bytes.length, "text/plain");
        try (StorageRead read = service.open("variants/asset.txt", Optional.of(new ByteRange(1, 4)))) {
            assertThat(read.inputStream().readAllBytes()).isEqualTo("ortf".getBytes(StandardCharsets.UTF_8));
            assertThat(read.contentLength()).isEqualTo(4);
        }
    }
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

```powershell
.\mvnw.cmd -pl portfolio-server -am -Dtest=LocalStorageServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: compilation FAIL because the storage contracts and adapter are absent.

- [ ] **Step 3: Implement the exact storage interfaces and Local adapter**

```java
package xyz.yychainsaw.portfolio.media.storage;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;

public interface StorageService {
    StorageProvider provider();
    StoredObject put(String objectKey, InputStream input, long contentLength, String contentType);
    StorageRead open(String objectKey, Optional<ByteRange> range);
    URI signedGet(String objectKey, Duration ttl);
    boolean exists(String objectKey);
    void copy(String sourceKey, String targetKey);
    void delete(String objectKey);
}
```

```java
package xyz.yychainsaw.portfolio.media.storage;

import xyz.yychainsaw.portfolio.media.domain.StorageProvider;

public interface StorageRouter {
    StorageService defaultWriter();
    StorageService require(StorageProvider provider);
}
```

```java
package xyz.yychainsaw.portfolio.media.storage;

public record ByteRange(long startInclusive, long endInclusive) {
    public ByteRange {
        if (startInclusive < 0 || endInclusive < startInclusive) {
            throw new IllegalArgumentException("Invalid byte range");
        }
    }
}
```

```java
package xyz.yychainsaw.portfolio.media.storage;

import java.io.InputStream;

public record StorageRead(
    InputStream inputStream,
    long contentLength,
    String contentType,
    String etag
) implements AutoCloseable {
    @Override public void close() throws Exception { inputStream.close(); }
}
```

```java
package xyz.yychainsaw.portfolio.media.storage;

import xyz.yychainsaw.portfolio.media.domain.StorageProvider;

public record StoredObject(
    StorageProvider provider,
    String bucket,
    String region,
    String objectKey,
    long contentLength,
    String contentType,
    String etag
) {}
```

```java
package xyz.yychainsaw.portfolio.media.storage;

public final class StorageException extends RuntimeException {
    private final String code;
    public StorageException(String code, Throwable cause) { super(code, cause); this.code = code; }
    public String code() { return code; }
}
```

```java
package xyz.yychainsaw.portfolio.media.storage;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.security.MessageDigest;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;

public final class LocalStorageService implements StorageService {
    private final Path root;

    public LocalStorageService(LocalStorageProperties properties) {
        this.root = properties.root().toAbsolutePath().normalize();
    }

    @Override public StorageProvider provider() { return StorageProvider.LOCAL; }

    @Override
    public StoredObject put(String key, InputStream input, long length, String contentType) {
        Path target = resolve(key);
        Path temporary = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".part");
        try {
            Files.createDirectories(target.getParent());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream source = input; var output = Files.newOutputStream(temporary); var hashing = new java.security.DigestInputStream(source, digest)) {
                long copied = hashing.transferTo(output);
                if (copied != length) throw new IOException("Storage content length mismatch");
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return new StoredObject(StorageProvider.LOCAL, null, null, key, Files.size(target), contentType,
                HexFormat.of().formatHex(digest.digest()));
        } catch (Exception exception) {
            try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            throw new StorageException("LOCAL_WRITE_FAILED", exception);
        }
    }

    @Override
    public StorageRead open(String key, Optional<ByteRange> requestedRange) {
        Path target = resolve(key);
        try {
            long total = Files.size(target);
            String contentType = Files.probeContentType(target);
            if (requestedRange.isEmpty()) {
                return new StorageRead(Files.newInputStream(target), total, contentType, null);
            }
            ByteRange range = requestedRange.orElseThrow();
            if (range.endInclusive() >= total) {
                throw new StorageRangeNotSatisfiableException(total);
            }
            SeekableByteChannel channel = Files.newByteChannel(target);
            channel.position(range.startInclusive());
            long length = range.endInclusive() - range.startInclusive() + 1;
            InputStream bounded = new BoundedInputStream(Channels.newInputStream(channel), length);
            return new StorageRead(bounded, length, contentType, null);
        } catch (IOException exception) {
            throw new StorageException("LOCAL_READ_FAILED", exception);
        }
    }

    @Override public URI signedGet(String key, Duration ttl) {
        throw new UnsupportedOperationException("Local storage is streamed by the application");
    }

    @Override public boolean exists(String key) { return Files.isRegularFile(resolve(key)); }

    @Override
    public void copy(String sourceKey, String targetKey) {
        Path target = resolve(targetKey);
        Path temporary = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".part");
        try {
            Files.createDirectories(target.getParent());
            Files.copy(resolve(sourceKey), temporary, StandardCopyOption.REPLACE_EXISTING);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            throw new StorageException("LOCAL_COPY_FAILED", exception);
        }
    }

    @Override
    public void delete(String key) {
        try { Files.deleteIfExists(resolve(key)); }
        catch (IOException exception) { throw new StorageException("LOCAL_DELETE_FAILED", exception); }
    }

    private Path resolve(String key) {
        if (key == null || key.isBlank() || key.contains("\\")) throw new IllegalArgumentException("Invalid storage object key");
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root)) throw new IllegalArgumentException("Invalid storage object key");
        return resolved;
    }
}
```

`BoundedInputStream` extends `FilterInputStream`, tracks `remaining`, returns `-1` when it reaches zero, caps both single-byte and bulk reads, and closes the underlying channel-backed stream. `StorageRangeNotSatisfiableException` carries only `totalLength`; admin/public controllers translate it to `416` with `Content-Range: bytes */{totalLength}`. Add tests for exact range bytes, end equal to file size (416), multi-range rejection at the controller, and stream closure.

Create `StorageProvider` with values `LOCAL` and `TENCENT_COS`, `LocalStorageProperties` as `@ConfigurationProperties("portfolio.storage.local") record LocalStorageProperties(Path root)`, and `DefaultStorageRouter` as an immutable `EnumMap` implementation built from all `StorageService` beans plus `StorageDefaults`. `defaultWriter()` delegates to `require(defaults.defaultProvider())`; duplicate providers or a missing configured default throw `IllegalStateException`.

- [ ] **Step 4: Run the focused test and storage package tests**

```powershell
.\mvnw.cmd -pl portfolio-server -am -Dtest='LocalStorageServiceTest,*StorageRouterTest' -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS with traversal rejected and bytes round-tripped.

- [ ] **Step 5: Commit the local storage slice**

```powershell
git add backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/media/storage
git commit -m "feat(media): add storage contracts and local adapter"
```

---

### Task 3: Add the Tencent COS adapter behind a testable client port

**Files:**
- Modify: `backend-parent/pom.xml`
- Modify: `backend-parent/portfolio-server/pom.xml`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/storage/TencentCosProperties.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/storage/CosClientPort.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/storage/QcloudCosClientAdapter.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/storage/TencentCosStorageService.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/storage/StorageConfiguration.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/media/storage/TencentCosStorageServiceTest.java`

**Interfaces:**
- Consumes: `StorageService` and `StoredObject` from Task 2; environment variables `COS_SECRET_ID`, `COS_SECRET_KEY`, `COS_REGION`, `COS_BUCKET`.
- Produces: `TENCENT_COS` implementation that puts, reads, signs five-minute GET URLs, and deletes without exposing the SDK to application services.

- [ ] **Step 1: Write a fake-port COS test**

```java
@Test
void signsReadForRequestedTtlAndKeepsProviderMetadata() {
    FakeCosClient client = new FakeCosClient();
    TencentCosStorageService service = new TencentCosStorageService(
        client, new TencentCosProperties("ap-guangzhou", "portfolio-123", "id", "secret"));

    URI uri = service.signedGet("variants/a/1280.jpg", Duration.ofMinutes(5));

    assertThat(uri).isEqualTo(URI.create("https://signed.test/variants/a/1280.jpg?ttl=300"));
    assertThat(client.lastTtl()).isEqualTo(Duration.ofMinutes(5));
    assertThat(service.provider()).isEqualTo(StorageProvider.TENCENT_COS);
}
```

The nested `FakeCosClient` implements `CosClientPort` with in-memory bytes and records the requested TTL; it returns deterministic `https://signed.test/{key}?ttl={seconds}` URIs.
Add cases proving zero, negative, and more-than-five-minute TTLs are rejected before the client port is called.
At the SDK-adapter boundary, assert PDF uploads set attachment disposition while JPEG/PNG uploads do not.

- [ ] **Step 2: Run the test and verify compilation fails**

```powershell
.\mvnw.cmd -pl portfolio-server -am -Dtest=TencentCosStorageServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: compilation FAIL because `CosClientPort` and `TencentCosStorageService` do not exist.

- [ ] **Step 3: Add the SDK dependency and adapter**

Add property `<tencent-cos.version>5.6.227</tencent-cos.version>` to the parent and dependency `com.qcloud:cos_api:${tencent-cos.version}` to `portfolio-server`.

```java
package xyz.yychainsaw.portfolio.media.storage;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;

public interface CosClientPort {
    StoredObject put(String bucket, String key, InputStream input, long length, String contentType);
    StorageRead open(String bucket, String key);
    URI signGet(String bucket, String key, Duration ttl);
    boolean exists(String bucket, String key);
    void copy(String bucket, String sourceKey, String targetKey);
    void delete(String bucket, String key);
}
```

```java
package xyz.yychainsaw.portfolio.media.storage;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;

public final class TencentCosStorageService implements StorageService {
    private final CosClientPort client;
    private final TencentCosProperties properties;

    public TencentCosStorageService(CosClientPort client, TencentCosProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override public StorageProvider provider() { return StorageProvider.TENCENT_COS; }
    @Override public StoredObject put(String key, InputStream in, long length, String type) {
        return client.put(properties.bucket(), key, in, length, type);
    }
    @Override public StorageRead open(String key, Optional<ByteRange> range) {
        if (range.isPresent()) {
            throw new UnsupportedOperationException("COS ranges use a signed redirect");
        }
        return client.open(properties.bucket(), key);
    }
    @Override public URI signedGet(String key, Duration ttl) {
        if (ttl.isZero() || ttl.isNegative() || ttl.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("COS signed GET TTL must be within five minutes");
        }
        return client.signGet(properties.bucket(), key, ttl);
    }
    @Override public boolean exists(String key) { return client.exists(properties.bucket(), key); }
    @Override public void copy(String sourceKey, String targetKey) { client.copy(properties.bucket(), sourceKey, targetKey); }
    @Override public void delete(String key) { client.delete(properties.bucket(), key); }
}
```

`QcloudCosClientAdapter` must construct `ObjectMetadata` with content length/type, set `Content-Disposition: attachment; filename="document.pdf"` whenever content type is PDF, keep every object private (never set a public-read ACL), use `PutObjectRequest`, `COSClient#getObject`, `COSClient#doesObjectExist`, `COSClient#copyObject`, `GeneratePresignedUrlRequest` with `HttpMethodName.GET`, expiration `clock.instant().plus(ttl)`, and `COSClient#deleteObject`. `StorageConfiguration` creates `BasicCOSCredentials` only when profile `prod` is active and every COS property is nonblank. Implement `TencentCosProperties` as a final configuration class whose `toString()` redacts both credential fields; do not use a record or Lombok-generated `toString` for secrets, and never include request authorization headers in SDK logs.

- [ ] **Step 4: Run the fake-port tests and dependency resolution**

```powershell
.\mvnw.cmd -pl portfolio-server -am -Dtest=TencentCosStorageServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
.\mvnw.cmd -pl portfolio-server dependency:tree -Dincludes=com.qcloud:cos_api
```

Expected: test PASS and dependency tree contains exactly `com.qcloud:cos_api:jar:5.6.227`.

- [ ] **Step 5: Commit the COS adapter slice**

```powershell
git add backend-parent/pom.xml backend-parent/portfolio-server/pom.xml backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/storage backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/media/storage/TencentCosStorageServiceTest.java
git commit -m "feat(media): add Tencent COS storage adapter"
```

---

### Task 4: Implement the database-backed job queue and lease worker

**Files:**
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/system/job/BackgroundJobService.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/system/job/BackgroundJobMapper.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/system/job/BackgroundJobRow.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/system/job/LeasedJob.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/system/job/JobHandler.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/system/job/DatabaseJobWorker.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/system/job/BackgroundJobServiceTest.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/system/job/DatabaseJobWorkerTest.java`

**Interfaces:**
- Consumes: `background_job` from Task 1, Jackson `ObjectMapper`, Spring transactions and scheduling.
- Produces:
  - `BackgroundJobService#enqueue(String, String, Map<String, ?>): UUID`
  - `JobHandler#jobType(): String`
  - `JobHandler#handle(JsonNode): void`
  - at-least-once lease execution with idempotent handlers and exponential retry.

- [ ] **Step 1: Write the concurrent lease test**

```java
@Test
void twoWorkersCannotLeaseTheSameJob() throws Exception {
    UUID jobId = service.enqueue("TEST", "test:one", Map.of("value", 1));
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);

    Future<Optional<LeasedJob>> first = pool.submit(() -> { start.await(); return service.leaseNext("worker-a", Duration.ofMinutes(30)); });
    Future<Optional<LeasedJob>> second = pool.submit(() -> { start.await(); return service.leaseNext("worker-b", Duration.ofMinutes(30)); });
    start.countDown();

    List<UUID> leased = Stream.of(first.get(), second.get())
        .flatMap(Optional::stream).map(LeasedJob::id).toList();
    assertThat(leased).containsExactly(jobId);
}

@Test
void crashedWorkersExpiredLeaseCanBeClaimedAgain() {
    UUID jobId = insertRunningJob("dead-worker", now.minusSeconds(1));
    Optional<LeasedJob> reclaimed = service.leaseNext("replacement-worker", Duration.ofMinutes(30));
    assertThat(reclaimed).get().extracting(LeasedJob::id).isEqualTo(jobId);
}
```

- [ ] **Step 2: Run the job test and verify it fails**

```powershell
.\mvnw.cmd -pl portfolio-server -am -Dtest=BackgroundJobServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: compilation FAIL because the job service and lease types are absent.

- [ ] **Step 3: Implement enqueue, lease, success, and retry**

```java
public interface JobHandler {
    String jobType();
    void handle(JsonNode payload);
}

public record LeasedJob(UUID id, String jobType, JsonNode payload, int attempts, String leaseOwner) {}
```

```java
@Mapper
public interface BackgroundJobMapper {
    @Select("""
        select id, job_type, payload, attempts
        from background_job
        where (status in ('PENDING','FAILED') and next_run_at <= #{now})
           or (status = 'RUNNING' and lease_until < #{now})
        order by next_run_at, created_at
        for update skip locked
        limit 1
        """)
    BackgroundJobRow selectReadyForUpdate(OffsetDateTime now);

    @Update("""
        update background_job set status='RUNNING', lease_owner=#{owner}, lease_until=#{until},
          attempts=attempts+1, updated_at=now() where id=#{id}
        """)
    int markRunning(UUID id, String owner, OffsetDateTime until);
}
```

`BackgroundJobService#enqueue` accepts only a registered job type and a serialized payload no larger than 16 KiB, then executes `insert into background_job(id, job_type, idempotency_key, payload, status, attempts, next_run_at, created_at, updated_at) values (#{id}, #{jobType}, #{idempotencyKey}, cast(#{payload} as jsonb), 'PENDING', 0, #{nextRunAt}, #{now}, #{now}) on conflict (idempotency_key) do update set updated_at = background_job.updated_at returning id`, so duplicate enqueue returns the original ID without resetting its state. `leaseNext` is `@Transactional`, locks a ready or expired-RUNNING row, replaces its owner/lease, increments attempts, and returns its payload. `succeed(jobId, leaseOwner)` and `fail(jobId, leaseOwner, summary)` update only a RUNNING row still owned by that lease; a late worker cannot overwrite a reclaimed attempt. Success clears the lease and sets SUCCEEDED. Failure stores a 500-character sanitized summary, clears the lease, and schedules `now + min(2^attempts, 3600) seconds`; the tenth failure sets DEAD.

```java
@Scheduled(fixedDelayString = "${portfolio.jobs.poll-delay:1000}")
public void poll() {
    service.leaseNext(workerId, Duration.ofMinutes(30)).ifPresent(job -> {
        try {
            JobHandler handler = handlers.require(job.jobType());
            handler.handle(job.payload());
            service.succeed(job.id(), job.leaseOwner());
        } catch (Exception exception) {
            service.fail(job.id(), job.leaseOwner(), ErrorSummary.sanitize(exception));
        }
    });
}
```

- [ ] **Step 4: Run concurrency and retry tests**

```powershell
.\mvnw.cmd -pl portfolio-server -am -Dtest='BackgroundJobServiceTest,DatabaseJobWorkerTest' -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS; one of two workers leases the row, an expired RUNNING row is reclaimed, a stale owner cannot finish it, duplicate enqueue returns one ID, retry delay increases, unknown job types fail safely, and attempt ten becomes DEAD.

- [ ] **Step 5: Commit the durable-job slice**

```powershell
git add backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/system/job backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/system/job
git commit -m "feat(system): add durable background jobs"
```

---

### Task 5: Validate and persist immutable uploads

**Files:**
- Create: `backend-parent/portfolio-pojo/src/main/java/xyz/yychainsaw/portfolio/api/admin/media/MediaAssetView.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/domain/MediaAsset.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/domain/MediaStatus.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/persistence/MediaAssetRecord.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/persistence/MediaAssetMapper.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/application/MediaFileInspector.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/application/UploadMediaCommand.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/application/InspectedMedia.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/application/MediaUploadService.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/media/application/MediaUploadServiceTest.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/media/application/MediaFileInspectorTest.java`

**Interfaces:**
- Consumes: `StorageRouter`, `BackgroundJobService` from Task 4, `CurrentAdminProvider`, and `AuditService` from plan 01.
- Produces: `MediaUploadService#upload(UploadMediaCommand): MediaAssetView`; every new asset starts `PROCESSING`, storage first receives `staging/{assetId}/{sha256}.{ext}`, and the durable finalizer moves it to `originals/{assetId}/{sha256}.{ext}` before READY.

- [ ] **Step 1: Write failing signature and size validation tests**

```java
@Test
void rejectsSvgEvenWhenDeclaredAsPng() {
    byte[] svg = "<svg xmlns='http://www.w3.org/2000/svg'></svg>".getBytes(UTF_8);
    UploadMediaCommand command = new UploadMediaCommand("fake.png", "image/png", svg.length, new ByteArrayInputStream(svg));

    assertThatThrownBy(() -> service.upload(command))
        .isInstanceOf(DomainException.class)
        .extracting("code").isEqualTo("MEDIA_SIGNATURE_NOT_ALLOWED");
    verifyNoInteractions(storage, assetMapper, jobs);
}

@Test
void rejectsImageOverTwentyFiveMib() {
    UploadMediaCommand command = new UploadMediaCommand("large.jpg", "image/jpeg", 25L * 1024 * 1024 + 1, InputStream.nullInputStream());
    assertThatThrownBy(() -> service.upload(command))
        .isInstanceOf(DomainException.class)
        .extracting("code").isEqualTo("MEDIA_TOO_LARGE");
}
```

- [ ] **Step 2: Run and verify the tests fail**

```powershell
.\mvnw.cmd -pl portfolio-server -am -Dtest=MediaUploadServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: compilation FAIL because the upload command and service are missing.

- [ ] **Step 3: Implement bounded inspection and upload persistence**

```java
public record UploadMediaCommand(
    String filename,
    String declaredContentType,
    long declaredSize,
    InputStream input
) {}

public record InspectedMedia(
    String mimeType,
    String extension,
    long byteSize,
    String sha256,
    Integer width,
    Integer height,
    Path temporaryFile
) implements AutoCloseable {
    @Override public void close() throws IOException { Files.deleteIfExists(temporaryFile); }
}
```

`MediaFileInspector#inspect` must copy at most the applicable limit plus one byte to a server-created temporary file, compute SHA-256 while copying, recognize JPEG `FF D8 FF`, PNG `89 50 4E 47 0D 0A 1A 0A`, and PDF `%PDF-`, use an `ImageReader` to read width/height before full decoding, reject null/zero dimensions or more than 80,000,000 pixels, and return the detected type rather than trusting the request header. The actual byte count must match the multipart size, the declared MIME must exactly match the detected allowlisted MIME, and filenames must be NFC-normalized, stripped of path components/control characters, and capped at 255 characters before persistence.

```java
public MediaAssetView upload(UploadMediaCommand command) {
    try (InspectedMedia media = inspector.inspect(command)) {
        UUID id = uuidGenerator.get();
        StorageService storage = router.defaultWriter();
        StorageProvider provider = storage.provider();
        String stagingKey = "staging/" + id + "/" + media.sha256() + "." + media.extension();
        String finalKey = "originals/" + id + "/" + media.sha256() + "." + media.extension();
        StoredObject staged;
        try (InputStream input = Files.newInputStream(media.temporaryFile())) {
            staged = storage.put(stagingKey, input, media.byteSize(), media.mimeType());
        }
        try {
            return transactionTemplate.execute(status -> {
                assetMapper.insert(MediaAssetRecord.from(id, staged.provider(), staged.bucket(), staged.region(),
                    finalKey, command.filename(), media, MediaStatus.PROCESSING));
                jobs.enqueue("FINALIZE_MEDIA_UPLOAD", "media-finalize:" + id, Map.of(
                    "assetId", id.toString(), "stagingKey", stagingKey, "finalKey", finalKey));
                audit.record(AuditCommand.success(currentAdmin.requireAdminId(), "MEDIA_UPLOAD", "media_asset", id));
                return assetMapper.findView(id).orElseThrow();
            });
        } catch (RuntimeException databaseFailure) {
            try {
                storage.delete(stagingKey);
            } catch (RuntimeException cleanupFailure) {
                databaseFailure.addSuppressed(cleanupFailure);
            }
            throw databaseFailure;
        }
    } catch (IOException exception) {
        throw new DomainException("MEDIA_UPLOAD_FAILED", HttpStatus.INTERNAL_SERVER_ERROR, Map.of());
    }
}
```

Add an integration test that forces a real database write to fail after the staging write and asserts `storage.delete(stagingKey)` is called exactly once. Lifecycle ownership is deliberately split: Task 6 implements and tests the LocalStorage scavenger for canonical `staging/` objects at least 24 hours old, while Task 8 installs and fail-closed verifies the production COS lifecycle rule for the exact `staging/` prefix with one-day expiration. Valid originals are never placed under that expiring prefix. Task 5 retains indeterminate outcomes for those later lifecycle owners and does not claim either cleanup path is already active.

- [ ] **Step 4: Run upload tests**

```powershell
.\mvnw.cmd -pl portfolio-server -am -Dtest='MediaUploadServiceTest,MediaFileInspectorTest' -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS for valid JPEG/PNG/PDF, MIME spoofing, corrupt image, path-safe key, and both size limits.

- [ ] **Step 5: Commit the upload slice**

```powershell
git add backend-parent/portfolio-pojo/src/main/java/xyz/yychainsaw/portfolio/media backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/media/application
git commit -m "feat(media): validate and persist immutable uploads"
```

---

### Task 6: Finalize originals and generate responsive image variants

**Files:**
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/persistence/MediaVariantRecord.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/persistence/MediaVariantMapper.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/application/ImageVariantGenerator.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/application/GeneratedVariant.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/application/EncodedImage.java`
- Modify: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/application/FinalizeMediaUploadJobHandler.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/application/StagingCleanupJobHandler.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/application/StagingCleanupScheduler.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/media/application/ImageVariantGeneratorTest.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/media/application/FinalizeMediaUploadJobHandlerTest.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/media/application/StagingCleanupJobHandlerTest.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/media/application/StagingCleanupSchedulerTest.java`

**Interfaces:**
- Consumes: `JobHandler`, `StorageRouter`, `media_asset`, `media_variant`, deterministic storage keys, and Jackson job payloads.
- Produces: READY PDFs with `document` variant; READY images with `w640`, `w1280`, `w1920`, `w2560`, `w3840`, and a final `w{originalWidth}` variant when the source is narrower than the next ceiling; plus an activated, durable Local staging scavenger. No generated width exceeds the source width.

- [ ] **Step 1: Write failing no-upscale and EXIF-removal tests**

```java
@Test
void createsOnlyWidthsAtOrBelowTheSource() throws Exception {
    BufferedImage source = new BufferedImage(800, 400, BufferedImage.TYPE_INT_RGB);
    List<GeneratedVariant> variants = generator.generate(source, "image/jpeg");

    assertThat(variants).extracting(GeneratedVariant::name)
        .containsExactly("w640", "w800");
    assertThat(variants).extracting(GeneratedVariant::width)
        .containsExactly(640, 800);
}

@Test
void rejectsDecompressionBombDimensions() {
    assertThatThrownBy(() -> generator.validatePixelCount(20_000, 5_000))
        .isInstanceOf(DomainException.class)
        .extracting("code").isEqualTo("MEDIA_PIXEL_LIMIT_EXCEEDED");
}
```

- [ ] **Step 2: Run the processing tests and verify failure**

```powershell
.\mvnw.cmd -pl portfolio-server -am -Dtest='ImageVariantGeneratorTest,FinalizeMediaUploadJobHandlerTest' -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: compilation FAIL because variant generation and finalization are absent.

- [ ] **Step 3: Implement deterministic processing and idempotent finalization**

`FinalizeMediaUploadJobHandler` accepts only the exact Task 5 `{assetId}` payload and derives every key and storage fact from the persisted asset. It immediately succeeds without storage I/O for READY, FAILED, ARCHIVED, and PENDING_DELETE assets. A missing asset or unresolved PROCESSING failure is fixed and cause-free so the durable job retries.

Add `StorageLocation` to the storage contract. The chosen adapter's provider/bucket/region must exactly equal the persisted asset location before object access. Treat `exists()` only as a hint: every reused staging, original, or variant object must be opened and fully verified for MIME, exact length/EOF, and SHA-256. Promote staging through create-only copy. If copy reports already-exists or any unknown outcome, verify the original and proceed only when it is exact; never overwrite or delete a mismatched deterministic object. Delete staging best-effort only after the original is proven. A cleanup failure never reverses an eventual READY result.

Generate widths from standard values below the source plus `min(sourceWidth, 3840)`, de-duplicated and never upscaled. Decode only the fully SHA-verified original; require persisted dimensions; draw into new sRGB RGB/ARGB buffers; encode PNG without source metadata or JPEG with metadata disabled, progressive disabled, and fixed quality `0.92f`. Use one fair generation permit and owner-only no-follow temporary files. Every temporary/output owner closes on every path and hides its path from `toString` and errors.

Publish image variants create-only at `variants/{assetId}/{variantName}/{encodedSha256}.{jpg|png}`. Pass a freshly opened input directly to `StorageService.put` because the provider owns closure. On success validate the complete `StoredObject`, and on already-exists or unknown outcome fully verify the deterministic object. PDF creates only a `document` row referencing the verified original.

After all objects are verified, one short database-only transaction inserts each READY variant with `ON CONFLICT DO NOTHING`; conflicts are accepted only after exact immutable row equality. In that same transaction CAS the asset from PROCESSING to READY with `version=version+1`. A concurrent exact READY is idempotent; every other state rolls back. Never use `DO UPDATE`, and never hold a transaction across storage or ImageIO. Rollback/unknown commit retains immutable objects so retry can converge. The finalizer dead-letter hook performs only PROCESSING-to-FAILED inside Task 4's job-failure transaction; malformed payload and non-PROCESSING/missing assets are no-ops, while a repository failure rolls back the DEAD transition.

`StagingCleanupScheduler` is servlet-only and enabled only when both `portfolio.jobs.worker-enabled` and `portfolio.media.staging-cleanup.enabled` are true. It requires canonical `portfolio.release-id`. At startup and daily 04:00 `Asia/Hong_Kong`, derive the most recent nonfuture 04:00 boundary, subtract exactly 24 hours, and enqueue `CLEAN_MEDIA_STAGING` with key `media-staging-cleanup:{releaseId}:{yyyy-MM-dd}` and payload containing only `cutoffEpochSecond`. Startup enqueue failure propagates and aborts readiness; a validated fixed-delay retry (default five minutes) re-enqueues the same current-boundary key so a recurring failure does not wait a day. The handler requires an exact long-valued integer in `Instant` range and recomputes the maximum safe cutoff before scanning. Concrete `LocalStorageService` streams canonical `staging/{uuid}/{sha}.{jpg|png|pdf}` files only up to a configured hard entry ceiling; exceeding it fails before every deletion. A complete in-ceiling scan keeps only the oldest bounded candidate set, revalidates owner/identity/timestamp immediately before deleting at most the configured limit, and never touches young, unknown, other-prefix, or linked objects. Oldest-first selection prevents persistent young/unknown entries from starving eligible files without relying on unspecified directory order or a fake cursor. Sanitized scanned/candidate/deleted/duration observations feed an operational duration gate. Each scheduler replica also cleans only its own Tencent adapter's local tmpfs `@part-{uuid}` scratch through that adapter's access policy at startup/daily; the globally deduplicated database job never claims to clean every node's scratch and no remote COS object is listed or deleted. Repeated/concurrent runs are idempotent. Task 8/Plan 07 preflight enforces the same Local staging entry ceiling before workers start.

- [ ] **Step 4: Run processing, idempotency, and cleanup tests**

```powershell
.\mvnw.cmd -pl portfolio-server -am -Dtest='ImageVariantGeneratorTest,FinalizeMediaUploadJobHandlerTest,StagingCleanupJobHandlerTest,StagingCleanupSchedulerTest' -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS; an 800px image produces only 640/800, a 5000px image stops at 3840, a duplicate job produces one row per name, PDF produces `document`, Local staging files at least 24 hours old are removed, the exact boundary/newer/foreign-prefix cases remain, future or otherwise unsafe cutoffs fail before scanning, multiple scheduler instances of one release—including startup before 04:00 Hong Kong time—enqueue one identical boundary-derived job, and a different release ID creates distinct activation evidence while retaining the same safe cutoff.

- [ ] **Step 5: Commit the processing slice**

```powershell
git add backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/media/application
git commit -m "feat(media): generate responsive image variants"
```

---

### Task 7: Add authenticated media management, preview, and delayed cleanup

**Files:**
- Create: `backend-parent/portfolio-pojo/src/main/java/xyz/yychainsaw/portfolio/api/admin/media/MediaTranslationInput.java`
- Create: `backend-parent/portfolio-pojo/src/main/java/xyz/yychainsaw/portfolio/api/admin/media/MediaTranslationView.java`
- Create: `backend-parent/portfolio-pojo/src/main/java/xyz/yychainsaw/portfolio/api/admin/media/MediaVariantView.java`
- Create: `backend-parent/portfolio-pojo/src/main/java/xyz/yychainsaw/portfolio/api/admin/media/MediaPageView.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/application/MediaReferenceChecker.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/application/MediaReference.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/application/MediaChangeListener.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/application/MediaChangeType.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/application/ImportMediaCommand.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/application/ImportedMedia.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/application/MediaImportService.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/application/DefaultMediaImportService.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/application/MediaAssetDescriptor.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/application/MediaCopyDescriptor.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/application/MediaVariantDescriptor.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/application/MediaQueryService.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/application/DefaultMediaQueryService.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/application/MediaManagementService.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/application/MediaLifecycleBarrier.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/application/PostgresMediaLifecycleBarrier.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/application/ArchivedMediaCleanupJobHandler.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/application/MediaCleanupScheduler.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/web/AdminMediaController.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/media/web/AdminMediaControllerTest.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/media/application/MediaImportServiceTest.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/media/application/MediaQueryServiceTest.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/media/application/ArchivedMediaCleanupJobHandlerTest.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/media/application/MediaLifecycleBarrierIntegrationTest.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/media/application/MediaContractTest.java`

**Interfaces:**
- Consumes: Plan 01 authenticated `/api/admin/**`, CSRF token, `MediaUploadService`, `StorageRouter`, `AuditService`.
- Produces:
  - `POST /api/admin/media` multipart upload
  - `GET /api/admin/media?page=&size=&status=`
  - `PUT /api/admin/media/{id}/translations`
  - `GET /api/admin/media/{id}/preview/{variant}`
  - `DELETE /api/admin/media/{id}` archive request
  - `MediaReferenceChecker#findReferences(UUID): List<MediaReference>` for plan 03 to extend with content/revision references.
  - `MediaChangeListener#onMediaChanged(UUID, MediaChangeType)` for plan 03 to mark affected SITE/PROJECT workspaces dirty.
  - `MediaImportService#importLocal(ImportMediaCommand): ImportedMedia` for plan 03's CLI importer.
  - `MediaQueryService#requireReadyAsset` and `requireReadyVariant` for snapshot/public projection.
  - delayed `MEDIA_CLEANUP_SCAN` and `DELETE_MEDIA_ASSET` jobs that always recheck all registered reference checkers.
  - a session-level PostgreSQL advisory barrier shared with plan 07, preventing a backup snapshot from racing physical object deletion without holding a database transaction open during storage I/O.

- [ ] **Step 1: Write security, translation, and referenced-delete tests**

```java
@Test
void uploadRequiresLoginAndCsrf() throws Exception {
    mvc.perform(multipart("/api/admin/media").file(validPng()))
        .andExpect(status().isUnauthorized());
    mvc.perform(multipart("/api/admin/media").file(validPng()).with(adminSession()))
        .andExpect(status().isForbidden());
}

@Test
void referencedAssetCannotBeArchived() throws Exception {
    given(references.findReferences(ASSET_ID)).willReturn(List.of(new MediaReference("PROJECT", PROJECT_ID)));
    mvc.perform(delete("/api/admin/media/{id}", ASSET_ID).with(adminSession()).with(csrf()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("MEDIA_STILL_REFERENCED"));
}

@Test
void cleanupRechecksReferencesAndKeepsEveryObjectWhenAnyReferenceAppears() {
    MediaAssetRecord archived = archivedAssetOlderThanThirtyDays();
    given(references.findReferences(archived.id()))
        .willReturn(List.of(new MediaReference("CONTENT_REVISION", REVISION_ID)));

    cleanup.handle(deletePayload(archived.id()));

    verify(storage, never()).delete(anyString());
    assertThat(assetMapper.require(archived.id()).status()).isEqualTo(MediaStatus.ARCHIVED);
}
```

- [ ] **Step 2: Run controller tests and verify failure**

```powershell
.\mvnw.cmd -pl portfolio-server -am -Dtest=AdminMediaControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: compilation FAIL because the controller and management service are absent.

- [ ] **Step 3: Implement the endpoint contract**

```java
@RestController
@RequestMapping("/api/admin/media")
final class AdminMediaController {
    private final MediaUploadService uploads;
    private final MediaManagementService media;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    MediaAssetView upload(@RequestPart("file") MultipartFile file) throws IOException {
        return uploads.upload(new UploadMediaCommand(
            Objects.requireNonNullElse(file.getOriginalFilename(), "upload"),
            file.getContentType(), file.getSize(), file.getInputStream()));
    }

    @GetMapping MediaPageView list(@RequestParam int page, @RequestParam int size,
        @RequestParam(required = false) String status) { return media.list(page, size, status); }

    @PutMapping("/{id}/translations")
    MediaAssetView translations(@PathVariable UUID id, @Valid @RequestBody List<MediaTranslationInput> input) {
        return media.updateTranslations(id, input);
    }

    @GetMapping("/{id}/preview/{variant}")
    ResponseEntity<?> preview(@PathVariable UUID id, @PathVariable String variant,
        @RequestHeader HttpHeaders requestHeaders) {
        return media.preview(id, variant, requestHeaders);
    }

    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void archive(@PathVariable UUID id) { media.archive(id); }
}
```

`MediaTranslationInput` contains locale, altText, caption, credit, and sourceUrl; reject locales outside `zh-CN/en` and non-HTTPS source URLs. `archive` checks every `MediaReferenceChecker`, returns `MEDIA_STILL_REFERENCED` with HTTP 409 when nonempty, otherwise atomically sets ARCHIVED/`archived_at=now()` and audits. Preview verifies the asset exists and is not PENDING_DELETE, then streams Local with ETag/Range or returns a 302 to a five-minute COS signed URL with `Cache-Control: no-store`. Only known variant names from `media_variant` are accepted; never accept an object key from the path. PDF responses use `Content-Disposition: attachment` and `X-Content-Type-Options: nosniff`; the admin never embeds PDF bytes as HTML.

```java
package xyz.yychainsaw.portfolio.media.application;

public record MediaReference(String referenceType, UUID referenceId) {}

public interface MediaReferenceChecker {
    List<MediaReference> findReferences(UUID assetId);
}

public enum MediaChangeType {
    TRANSLATION_UPDATED,
    METADATA_UPDATED
}

public interface MediaChangeListener {
    void onMediaChanged(UUID assetId, MediaChangeType changeType);
}
```

Inject every checker as an ordered list and merge/deduplicate by `(referenceType, referenceId)`. Plan 03 contributes the content/workspace/history implementation; media code never queries content tables directly. `MediaManagementService.updateTranslations` calls every change listener inside the same database transaction after its media rows update; any dirty-mark failure rolls back both changes. Initial upload/finalization does not notify because no content can reference a non-READY new asset, and deterministic retry never changes approved bytes. Plan 03 contributes the listener that bumps SITE or marks all affected PROJECT workspaces dirty without touching published revisions.

- [ ] **Step 4: Implement the import and READY-media query contracts consumed by plan 03**

Use these exact records and interfaces so plan 03 does not depend on persistence or provider implementations:

```java
package xyz.yychainsaw.portfolio.media.application;

public record ImportMediaCommand(
    Path assetRoot,
    String publicPath,
    String usage,
    String objectPosition,
    String credit,
    URI sourceUrl,
    Map<String, String> altByLocale
) {
    public ImportMediaCommand { altByLocale = Map.copyOf(altByLocale); }
}

public record ImportedMedia(UUID assetId, String originalSha256, List<String> readyVariants) {
    public ImportedMedia { readyVariants = List.copyOf(readyVariants); }
}

public interface MediaImportService {
    ImportedMedia importLocal(ImportMediaCommand command);
}
```

```java
package xyz.yychainsaw.portfolio.media.application;

public record MediaCopyDescriptor(String alt, String caption, String credit, String sourceUrl) {}

public record MediaVariantDescriptor(
    UUID assetId,
    String variantName,
    String status,
    StorageProvider provider,
    String bucket,
    String region,
    String objectKey,
    String mimeType,
    long byteSize,
    String sha256,
    int width,
    int height
) {}

public record MediaAssetDescriptor(
    UUID assetId,
    String status,
    String mimeType,
    long byteSize,
    String sha256,
    Map<String, MediaCopyDescriptor> copyByLocale,
    List<MediaVariantDescriptor> variants
) {
    public MediaAssetDescriptor {
        copyByLocale = Map.copyOf(copyByLocale);
        variants = List.copyOf(variants);
    }
}

public interface MediaQueryService {
    MediaAssetDescriptor requireReadyAsset(UUID assetId);
    MediaVariantDescriptor requireReadyVariant(UUID assetId, String variantName);
}
```

`DefaultMediaImportService` resolves `publicPath` by stripping one leading slash and normalizing beneath `assetRoot`; reject absolute results, traversal, symlinks that resolve outside the real root, unreadable files, locale keys other than exactly `zh-CN/en`, and unsupported source URL schemes. It opens the resolved file, delegates signature/size/dimension checks to `MediaFileInspector`, and calls the same staged upload transaction with bilingual translation metadata. If an existing READY asset has the same SHA-256 and identical translations/credit/source URL, reuse it; otherwise create a new PROCESSING asset and durable finalization job. Because it joins plan 03's outer transaction, a later rollback can leave only a `staging/` object; the 24-hour Local/COS staging cleanup removes it. `readyVariants` contains the existing READY variant names for reuse and is empty for a newly queued asset.

`DefaultMediaQueryService` reads only READY assets/variants, returns variants sorted by width/name, maps nullable PDF dimensions to zero, and throws `MEDIA_NOT_READY` (`422`) for an existing non-READY asset and `MEDIA_NOT_FOUND` (`404`) for a missing ID/name. It never returns credentials or signed URLs. Add tests for path containment through symlink/`..`, invalid locale set, rollback staging orphan, same-SHA exact-metadata reuse, same-SHA different-alt non-reuse, deterministic sort, PDF zero dimensions, and every error code.

- [ ] **Step 5: Implement delayed physical cleanup with a final reference check**

`MediaCleanupScheduler` enqueues one `MEDIA_CLEANUP_SCAN` job per `Asia/Hong_Kong` day. The scan selects ARCHIVED rows whose `archived_at` is at least 30 days old, asks every `MediaReferenceChecker`, and enqueues `DELETE_MEDIA_ASSET` with idempotency key `media-delete:{assetId}:{assetVersion}` only when no checker reports a workspace, current revision, or retained historical revision reference. Plan 03 adds the content/revision checker before production cleanup is enabled.

`ArchivedMediaCleanupJobHandler` must:

1. acquire `MediaLifecycleBarrier.acquireExclusiveDeletionLease()` before the final reload and retain only that dedicated advisory-lock connection—not a transaction or row lock—through object deletion;
2. reload the asset and return unless its status is ARCHIVED or PENDING_DELETE;
3. re-run every checker; if any reference now exists, leave the current lifecycle state unchanged, audit `REFERENCE_BLOCKED`, and stop without deleting an object. An ARCHIVED asset remains ARCHIVED, while a PENDING_DELETE asset stays quarantined because a previous attempt may already have deleted only some provider objects; PENDING_DELETE never returns to ARCHIVED;
4. compare-and-set ARCHIVED to PENDING_DELETE, or continue an earlier PENDING_DELETE retry;
5. delete every recorded variant key and the original key through the row's provider, treating a missing object as success;
6. in a short transaction delete translations, variants, and the asset row only after every object deletion succeeds;
7. audit only asset ID, provider, object count, and result; never log object keys;
8. on partial provider failure leave PENDING_DELETE and throw so the durable job retries the same deterministic keys;
9. release the advisory lease in `finally` on success, failure, interrupt, or no-op.

Use this exact cross-plan lock contract:

```java
package xyz.yychainsaw.portfolio.media.application;

public interface MediaLifecycleBarrier {
    int NAMESPACE_KEY = 1347375700; // ASCII PORT
    int MEDIA_KEY = 1296385097;     // ASCII MEDI
    AutoCloseable acquireExclusiveDeletionLease();
}
```

`PostgresMediaLifecycleBarrier` obtains a dedicated datasource connection, executes `select pg_advisory_lock(?, ?)` with those two integer keys, and returns an idempotent lease whose `close()` executes `pg_advisory_unlock` and closes the connection. It sets a bounded acquisition/query timeout and preserves thread interruption. Plan 07's snapshot keeper uses `pg_advisory_lock_shared(1347375700,1296385097)` for the complete database/media backup-set window. Do not replace this with a JVM lock, transaction-scoped lock, or `maintenance_run` polling: backups run outside the API process and must exclude deletion across provider I/O.

The test covers an unreferenced old archive, a newly appeared reference, partial Local/COS deletion, retry after some objects are already absent, a 29-day asset, and a retained historical revision. A PostgreSQL concurrency test holds the shared backup lock, starts cleanup on another thread, proves zero provider delete calls until the shared lock releases, then proves cleanup completes and the dedicated lock connection closes. No automatic job may remove an asset before the 30-day cooling period.

- [ ] **Step 6: Run controller, import/query, and cleanup tests**

```powershell
.\mvnw.cmd -pl portfolio-server -am -Dtest='AdminMediaControllerTest,MediaManagementServiceTest,MediaImportServiceTest,MediaQueryServiceTest,ArchivedMediaCleanupJobHandlerTest,MediaLifecycleBarrierIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS for 401, missing CSRF 403, successful upload 201, both translations, safe preview, contained import, READY queries, rollback staging cleanup, 409 referenced archive, successful unreferenced archive, 30-day cooling, reference recheck, and idempotent physical cleanup.

- [ ] **Step 7: Commit the admin media slice**

```powershell
git add backend-parent/portfolio-pojo/src/main/java/xyz/yychainsaw/portfolio/media backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/media
git commit -m "feat(media): add authenticated media management"
```

---

### Task 8: Add storage profiles, contract suites, and the guarded live COS smoke test

**Files:**
- Modify: `backend-parent/portfolio-server/src/main/resources/application-dev.yml`
- Modify: `backend-parent/portfolio-server/src/main/resources/application-prod.yml`
- Modify: `backend-parent/portfolio-server/src/test/resources/application-test.yml`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/media/storage/AbstractStorageContractTest.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/media/storage/TencentCosLiveSmokeTest.java`
- Modify: `deploy/.env.example`
- Create: `deploy/cos/staging-lifecycle-rule.json`
- Create: `deploy/scripts/install-cos-staging-lifecycle.sh`
- Create: `deploy/scripts/verify-cos-staging-lifecycle.sh`
- Create: `deploy/tests/cos-staging-lifecycle-contract.sh`
- Create: `docs/operations/media-storage.md`

**Interfaces:**
- Consumes: both adapters, all media services, the `cos-live` JUnit tag, and plan 01 configuration conventions.
- Produces: reproducible Local tests, stubbed COS tests, opt-in real COS verification, documented provider switching, and a production-preflight-verified staging lifecycle for both providers.

- [ ] **Step 1: Write the reusable contract and prove the live test is skipped by default**

```java
abstract class AbstractStorageContractTest {
    abstract StorageService storage();

    @Test void putOpenCopyDeleteRoundTrips() throws Exception {
        byte[] bytes = "contract".getBytes(UTF_8);
        storage().put("contract/source.txt", new ByteArrayInputStream(bytes), bytes.length, "text/plain");
        storage().copy("contract/source.txt", "contract/copy.txt");
        try (StorageRead read = storage().open("contract/copy.txt", Optional.empty())) {
            assertThat(read.inputStream().readAllBytes()).isEqualTo(bytes);
        }
        storage().delete("contract/source.txt");
        storage().delete("contract/copy.txt");
    }
}
```

`TencentCosLiveSmokeTest` extends this contract, is annotated `@Tag("cos-live")`, requires all four `COS_*` environment variables through JUnit assumptions, and prefixes every key with `smoke/{UUID}/`; `@AfterEach` deletes that prefix's known keys.

- [ ] **Step 2: Run the normal suite and confirm no cloud call occurs**

```powershell
.\mvnw.cmd -pl portfolio-server -am test
```

Expected: PASS; Surefire excludes `cos-live`, and no test requires COS credentials.

- [ ] **Step 3: Add exact configuration and operations documentation**

```yaml
# application-dev.yml
portfolio:
  release-id: dev
  jobs:
    worker-enabled: false
  storage:
    default-provider: LOCAL
    local:
      root: ${PORTFOLIO_LOCAL_STORAGE:../runtime/media}
  media:
    staging-cleanup:
      enabled: false
    cleanup:
      enabled: false
      cooling-period: 30d
```

```yaml
# application-prod.yml
portfolio:
  release-id: ${PORTFOLIO_RELEASE_ID}
  jobs:
    worker-enabled: ${PORTFOLIO_JOBS_WORKER_ENABLED:false}
  storage:
    default-provider: TENCENT_COS
    local:
      root: ${PORTFOLIO_LOCAL_STORAGE:/var/lib/portfolio/media}
    cos:
      region: ${COS_REGION}
      bucket: ${COS_BUCKET}
      secret-id: ${COS_SECRET_ID}
      secret-key: ${COS_SECRET_KEY}
      staging-root: ${PORTFOLIO_COS_STAGING_ROOT:/tmp/portfolio-cos-staging}
  media:
    staging-cleanup:
      enabled: ${PORTFOLIO_STAGING_CLEANUP_ENABLED:false}
    cleanup:
      enabled: ${PORTFOLIO_MEDIA_CLEANUP_ENABLED:false}
      cooling-period: 30d
```

Add the four empty `COS_*` names, `PORTFOLIO_LOCAL_STORAGE`, `PORTFOLIO_COS_STAGING_ROOT=/tmp/portfolio-cos-staging`, `PORTFOLIO_LOCAL_VOLUME_ID`, `PORTFOLIO_JOBS_WORKER_ENABLED=true`, `PORTFOLIO_STAGING_CLEANUP_ENABLED=true`, and `PORTFOLIO_MEDIA_CLEANUP_ENABLED=false` to `deploy/.env.example`; do not add a real volume ID or credentials. Reuse the release bundle's required `PORTFOLIO_RELEASE_ID`. `application-prod.yml` maps the release ID to `portfolio.release-id`, the worker value to `portfolio.jobs.worker-enabled`, the scratch root to `portfolio.storage.cos.staging-root`, and the staging value to `portfolio.media.staging-cleanup.enabled`, while keeping both cleanup controls distinct from the 30-day archived-asset cleanup switch. The COS scratch root must resolve beneath the size-limited ephemeral `/tmp` mount and never beside or inside durable media objects. Provision the Local volume with a deployment-owned opaque `.portfolio-volume-id` whose exact protected expected value is checked before workers start; do not expose or log it. Keep physical archived-asset cleanup disabled until plan 03 installs and tests its workspace/current/retained-revision checker; plan 07's production preflight then requires the release ID, volume identity, worker, and both cleanup switches once the complete reference checker is installed.

`deploy/cos/staging-lifecycle-rule.json` is an operations artifact, not an application secret: it declares one enabled rule whose prefix is exactly `staging/` and whose expiration is one day. `deploy/scripts/install-cos-staging-lifecycle.sh --apply` uses a separate, short-lived operations credential to read the complete remote lifecycle configuration, refuse unsafe overlapping/broad rules, preserve unrelated safe rules, and idempotently add or retain the exact repository rule; it then performs a read-back verification and never logs credentials. The installer is an explicit bucket-provisioning action, not part of routine application startup or read-only preflight. `deploy/scripts/verify-cos-staging-lifecycle.sh --check-live` requires only lifecycle-read authority and fails closed unless the live bucket has that exact enabled rule and proves no staging-intended rule can match `originals/`, `variants/`, `smoke/`, or backup prefixes. The runtime media credential must not receive bucket-lifecycle permission. Production preflight in plan 07 runs only the verifier before enabling upload/finalization workers. Do not treat a checked-in JSON file, documentation statement, or runtime application property as proof that the remote lifecycle is active.

`deploy/tests/cos-staging-lifecycle-contract.sh` drives both scripts against sanitized local command fixtures. It proves the installer is idempotent for an exact existing rule, safely merges a missing rule without changing unrelated rules, rejects unsafe overlaps/malformed reads/apply or read-back failures, and never echoes credentials. It proves the verifier accepts only the exact enabled rule and rejects disabled, missing, broad-prefix, wrong-day, malformed, command-failure, and secret-bearing output cases. Task 8 runs this contract unconditionally. When a guarded live COS bucket and short-lived lifecycle-operations credential are present, Task 8 invokes the installer once and then the independent verifier; plan 07 repeats only the read-only verifier as mandatory production preflight.

`docs/operations/media-storage.md` must document: provider is per asset, changing the default does not migrate, originals remain private, the opaque Local volume identity prevents silent wrong-volume starts, the Local scavenger owns canonical staging files at least 24 hours old, per-node COS upload scratch is a bounded ephemeral tmpfs and is not the remote lifecycle, the separately verified COS rule expires only remote `staging/` after one day, live smoke keys are temporary, the cooling period is 30 days, and archived assets are not physically deleted until the complete reference checker reports no references twice (scan and delete handler).

- [ ] **Step 4: Run every media test and optionally the guarded live smoke**

```powershell
.\mvnw.cmd -pl portfolio-server -am -Dtest='*Media*,*Storage*,*BackgroundJob*' -Dsurefire.failIfNoSpecifiedTests=false test
```

```bash
bash deploy/tests/cos-staging-lifecycle-contract.sh
```

Expected: PASS with zero failures; the Local profile/context tests prove the durable worker and startup plus recurring cleanup enqueue are enabled together in production, and that cleanup is absent when either prerequisite is explicitly disabled; the lifecycle verifier contract exits 0 only for the exact COS rule.

Only when temporary COS credentials and a disposable prefix are configured:

```bash
bash deploy/scripts/install-cos-staging-lifecycle.sh --apply
bash deploy/scripts/verify-cos-staging-lifecycle.sh --check-live
```

```powershell
.\mvnw.cmd -pl portfolio-server -Dgroups=cos-live test
```

Expected: installer and independent read-only verifier both exit 0, the Maven smoke passes, and temporary `smoke/{UUID}/` objects are absent from the bucket.

- [ ] **Step 5: Commit and verify the complete media subsystem**

```powershell
git add backend-parent/portfolio-server/src/main/resources backend-parent/portfolio-server/src/test deploy/.env.example deploy/cos deploy/scripts/install-cos-staging-lifecycle.sh deploy/scripts/verify-cos-staging-lifecycle.sh deploy/tests/cos-staging-lifecycle-contract.sh docs/operations/media-storage.md
git commit -m "test(media): verify storage profiles and contracts"
.\mvnw.cmd -pl portfolio-server -am test
```

Expected: commit succeeds and the complete backend test suite exits 0.
