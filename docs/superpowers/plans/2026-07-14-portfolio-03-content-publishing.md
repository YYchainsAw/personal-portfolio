# Portfolio Content Publishing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the normalized bilingual content workspace, deterministic one-time importer, immutable SITE/PROJECT/PROJECT_CATALOG publication model, preview/restore flow, public snapshot APIs, and localized SEO HTML surface defined by the approved design.

**Architecture:** Spring Boot remains a modular monolith under `xyz.yychainsaw.portfolio`. Editable content is normalized in PostgreSQL and exposed through aggregate repositories; publication creates canonical immutable JSONB revisions and atomically advances publication pointers. The public API and Thymeleaf renderer read only current publication pointers, while the Vue app consumes embedded initial data or the same public APIs.

**Tech Stack:** Java 17, Spring Boot 3.5.7, MyBatis-Plus 3.5.7, PostgreSQL 17, Flyway, Jackson, Thymeleaf, JUnit 5, Testcontainers, Node.js 22.18, Vite 8, and JSON Schema draft 2020-12; plan 05 owns the Vue/Vue Router consumer.

## Global Constraints

- This plan depends on plan 01 for the Maven modules, PostgreSQL/Testcontainers harness, authentication, CSRF, `CurrentAdminProvider`, `AuditService`, common error handling, and Flyway migrations V1/V2.
- This plan depends on plan 02 for Flyway migration V3, `media_asset`, `media_variant`, media translations, storage drivers, import ingestion, and READY-media queries.
- Use package root `xyz.yychainsaw.portfolio`; do not copy code or data from `QingLian-MyFitnessApp-Android-SpringBoot`.
- Use exactly `V4__content_workspace.sql` for normalized content and `V5__publishing.sql` for publication state.
- UUIDs are generated in Java except the fixed SITE and PROJECT_CATALOG IDs; all persisted timestamps are `timestamptz` written from `Clock` in UTC.
- Supported locales are exactly `zh-CN` and `en`; every translation table has a `(parent_id, locale)` unique or primary-key constraint.
- Public slugs are shared by both locales and match `^[a-z0-9]+(?:-[a-z0-9]+)*$`.
- Public readers never query workspace translation tables and never expose a draft, non-current revision, or archived publication.
- Published revisions and their media references are immutable; restoration writes a new draft and never edits history.
- Plan 03 registers the content/history media-reference checker before cleanup can run, but production scheduling and cleanup enablement remain disabled until plan 07.
- Every transaction that inserts or replaces a workspace, revision, restore, or import media reference must first lock every referenced READY asset through plan 02's `MediaQueryService` in deterministic UUID/variant order and retain those `FOR SHARE` locks until commit. These media locks precede every SITE/project/catalog `FOR UPDATE` lock globally, matching plan 02's media-change-listener path; a foreign key proves only identity, not READY lifecycle eligibility, and is not a substitute for this lock.
- No Redis, message queue, Elasticsearch, microservice, arbitrary EAV field, arbitrary HTML, uploaded video, or generic page builder is introduced.
- Use TDD for every task: add one focused failing test, observe the expected failure, add the smallest implementation, run the focused test, then run the affected module suite.
- Each task ends with the exact commit shown. Do not combine task commits.

## Cross-plan interface contract

Plan 01 must provide these exact types; plan 03 consumes them and does not redefine them:

```java
// backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/CurrentAdminProvider.java
package xyz.yychainsaw.portfolio.auth;

import java.util.UUID;

public interface CurrentAdminProvider {
    UUID requireAdminId();
}
```

```java
// backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/audit/AuditCommand.java
package xyz.yychainsaw.portfolio.audit;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import xyz.yychainsaw.portfolio.common.trace.TraceIds;

public record AuditCommand(
        UUID actorAdminId,
        String action,
        String targetType,
        String targetId,
        AuditOutcome outcome,
        String traceId,
        Map<String, String> metadata
) {
    public AuditCommand {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(targetType, "targetType");
        Objects.requireNonNull(outcome, "outcome");
        traceId = traceId == null || traceId.isBlank() ? TraceIds.current() : traceId;
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
    }
}
```

```java
package xyz.yychainsaw.portfolio.audit;

public enum AuditOutcome { SUCCESS, FAILURE }
```

```java
// backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/audit/AuditService.java
package xyz.yychainsaw.portfolio.audit;

public interface AuditService {
    void record(AuditCommand command);
}
```

```java
// backend-parent/portfolio-common/src/main/java/xyz/yychainsaw/portfolio/common/error/DomainException.java
package xyz.yychainsaw.portfolio.common.error;

import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;

public final class DomainException extends RuntimeException {
    private final String code;
    private final HttpStatus status;
    private final Map<String, String> fieldErrors;

    public DomainException(String code, HttpStatus status, Map<String, String> fieldErrors) {
        super(Objects.requireNonNull(code, "code"));
        this.code = code;
        this.status = Objects.requireNonNull(status, "status");
        this.fieldErrors = Map.copyOf(Objects.requireNonNull(fieldErrors, "fieldErrors"));
    }

    public String code() { return code; }
    public HttpStatus status() { return status; }
    public Map<String, String> fieldErrors() { return fieldErrors; }
}
```

Plan 03 consumes, but does not recreate, the following exact plan 02 storage contracts. Storage provider identity stays in `media.domain`; persisted object metadata and opened streams are different types:

```java
// owned by plan 02
package xyz.yychainsaw.portfolio.media.domain;

public enum StorageProvider {
    LOCAL,
    TENCENT_COS
}
```

```java
// owned by plan 02
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
// owned by plan 02
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
// owned by plan 02
package xyz.yychainsaw.portfolio.media.storage;

import java.io.InputStream;

public record StorageRead(
        InputStream inputStream,
        long contentLength,
        String contentType,
        String etag
) implements AutoCloseable {
    @Override
    public void close() throws Exception {
        inputStream.close();
    }
}
```

```java
// owned by plan 02
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
// owned by plan 02
package xyz.yychainsaw.portfolio.media.storage;

import xyz.yychainsaw.portfolio.media.domain.StorageProvider;

public interface StorageRouter {
    StorageService defaultWriter();
    StorageService require(StorageProvider provider);
}
```

```java
// backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/application/ImportMediaCommand.java
package xyz.yychainsaw.portfolio.media.application;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;

public record ImportMediaCommand(
        Path assetRoot,
        String publicPath,
        String usage,
        String objectPosition,
        String credit,
        URI sourceUrl,
        Map<String, String> altByLocale
) {
    public ImportMediaCommand {
        altByLocale = Map.copyOf(altByLocale);
    }
}
```

```java
// backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/application/ImportedMedia.java
package xyz.yychainsaw.portfolio.media.application;

import java.util.List;
import java.util.UUID;

public record ImportedMedia(UUID assetId, String originalSha256, List<String> readyVariants) {
    public ImportedMedia {
        readyVariants = List.copyOf(readyVariants);
    }
}
```

```java
// backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/application/MediaImportService.java
package xyz.yychainsaw.portfolio.media.application;

public interface MediaImportService {
    ImportedMedia importLocal(ImportMediaCommand command);
}
```

`MediaImportService.importLocal` must reject paths outside `assetRoot`, verify file signatures, create or reuse an asset by SHA-256, and join the caller's Spring transaction for media rows. Plan 02 owns cleanup of a temporary object left by a later database rollback.

```java
// backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/application/MediaAssetDescriptor.java
package xyz.yychainsaw.portfolio.media.application;

import java.util.List;
import java.util.UUID;

public record MediaAssetDescriptor(
        UUID assetId,
        String status,
        String mimeType,
        long byteSize,
        String sha256,
        java.util.Map<String, MediaCopyDescriptor> copyByLocale,
        List<MediaVariantDescriptor> variants
) {
    public MediaAssetDescriptor {
        copyByLocale = java.util.Map.copyOf(copyByLocale);
        variants = List.copyOf(variants);
    }
}
```

```java
// backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/application/MediaCopyDescriptor.java
package xyz.yychainsaw.portfolio.media.application;

public record MediaCopyDescriptor(String alt, String caption, String credit, String sourceUrl) {}
```

```java
// backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/application/MediaVariantDescriptor.java
package xyz.yychainsaw.portfolio.media.application;

import java.util.UUID;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;

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
```

```java
// backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/media/application/MediaQueryService.java
package xyz.yychainsaw.portfolio.media.application;

import java.util.UUID;

public interface MediaQueryService {
    MediaAssetDescriptor requireReadyAsset(UUID assetId);
    MediaVariantDescriptor requireReadyVariant(UUID assetId, String variantName);
}
```

Plan 02 also owns the cleanup extension point. Plan 03 implements it without changing these types:

```java
// owned by plan 02
package xyz.yychainsaw.portfolio.media.application;

import java.util.UUID;

public record MediaReference(String referenceType, UUID referenceId) {}
```

```java
// owned by plan 02
package xyz.yychainsaw.portfolio.media.application;

import java.util.List;
import java.util.UUID;

public interface MediaReferenceChecker {
    List<MediaReference> findReferences(UUID assetId);
}
```

Plan 02 invokes this change extension inside the same transaction as a media metadata/translation edit; plan 03 supplies the content listener:

```java
// owned by plan 02
package xyz.yychainsaw.portfolio.media.application;

public enum MediaChangeType {
    TRANSLATION_UPDATED,
    METADATA_UPDATED
}
```

```java
// owned by plan 02
package xyz.yychainsaw.portfolio.media.application;

import java.util.UUID;

public interface MediaChangeListener {
    void onMediaChanged(UUID assetId, MediaChangeType changeType);
}
```

## File responsibility map

- `frontend/scripts/export-portfolio.mjs`: load `portfolio.ts` through Vite, validate it, and write deterministic canonical JSON.
- `frontend/scripts/canonical-json.mjs`: recursively sort object keys while preserving array order.
- `frontend/schema/portfolio-import-v1.schema.json`: closed JSON Schema contract for the one-time import artifact.
- `backend-parent/portfolio-server/src/main/resources/db/migration/V4__content_workspace.sql`: all normalized editable content tables and constraints.
- `backend-parent/portfolio-server/src/main/resources/db/migration/V5__publishing.sql`: immutable revisions, publication pointers, redirects, media references, and fixed singleton rows.
- `content/model`: aggregate records and validation vocabulary; no persistence annotations.
- `content/persistence`: MyBatis mapper interfaces, row records, XML, assemblers, and transactional aggregate repositories.
- `content/application`: workspace query/update services and the one-time importer.
- `publishing/snapshot`: versioned snapshot records, canonical codec, and workspace-to-snapshot mappers.
- `publishing/application`: validation, publish/archive/reorder, restore, preview, public snapshot queries, downstream current-publication/label ports, and retained media-reference checking.
- `publishing/persistence`: row locks, CAS, revision/redirect/media-reference SQL.
- `publishing/web`: admin publication endpoints, public JSON endpoints, public media authorization, localized HTML, redirects, ETag, and sitemap.
- Plan 05 owns `frontend/src/services/portfolioApi.ts`, initial-payload consumption, localized routes/views, block rendering, and browser verification; plan 03 supplies their immutable server contracts only.

---

### Task 1: Deterministic Vite portfolio exporter and closed JSON Schema

**Files:**
- Create: `frontend/scripts/canonical-json.mjs`
- Create: `frontend/scripts/export-portfolio.mjs`
- Create: `frontend/scripts/export-portfolio.test.mjs`
- Create: `frontend/schema/portfolio-import-v1.schema.json`
- Modify: `frontend/package.json`
- Modify: `frontend/package-lock.json`

**Interfaces:**
- Consumes: exports `identity`, `heroAsset`, `projectAssets`, and `portfolioContent` from `frontend/src/data/portfolio.ts`.
- Produces: `canonicalize(value): unknown`, `assertValidPortfolioPayload(payload): Promise<void>`, `exportPortfolio({ outputPath }): Promise<{ outputPath: string; sha256: string }>` and canonical artifact `runtime/import/portfolio-v1.json` with `schemaVersion: 1`.

- [ ] **Step 1: Add a failing deterministic-export test**

```js
// frontend/scripts/export-portfolio.test.mjs
import assert from 'node:assert/strict'
import { readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'
import { assertValidPortfolioPayload, exportPortfolio } from './export-portfolio.mjs'

test('exports schema v1 deterministically and preserves project order', async () => {
  const first = join(tmpdir(), `portfolio-${crypto.randomUUID()}-a.json`)
  const second = join(tmpdir(), `portfolio-${crypto.randomUUID()}-b.json`)
  try {
    const a = await exportPortfolio({ outputPath: first })
    const b = await exportPortfolio({ outputPath: second })
    const firstBytes = await readFile(first)
    const secondBytes = await readFile(second)
    const payload = JSON.parse(firstBytes.toString('utf8'))
    assert.deepEqual(firstBytes, secondBytes)
    assert.equal(a.sha256, b.sha256)
    assert.equal(payload.schemaVersion, 1)
    assert.deepEqual(
      payload.portfolioContent.en.projects.map((project) => project.id),
      ['ue-environment-study', 'gameplay-prototype', 'development-log'],
    )
  } finally {
    await Promise.all([rm(first, { force: true }), rm(second, { force: true })])
  }
})

test('allows a present blank translation but rejects a missing required translation field', async () => {
  const output = join(tmpdir(), `portfolio-${crypto.randomUUID()}-schema.json`)
  try {
    await exportPortfolio({ outputPath: output })
    const payload = JSON.parse(await readFile(output, 'utf8'))
    payload.portfolioContent.en.hero.headline = ''
    await assert.doesNotReject(() => assertValidPortfolioPayload(payload))
    delete payload.portfolioContent.en.hero.headline
    await assert.rejects(() => assertValidPortfolioPayload(payload), /portfolio schema validation failed/)
  } finally {
    await rm(output, { force: true })
  }
})
```

- [ ] **Step 2: Add the test script and validator dependency, then verify the test fails**

Add these exact entries without changing existing versions:

```json
{
  "scripts": {
    "export:portfolio": "node scripts/export-portfolio.mjs --output ../runtime/import/portfolio-v1.json",
    "test:export": "node --test scripts/export-portfolio.test.mjs"
  },
  "devDependencies": {
    "ajv": "8.17.1"
  }
}
```

Run from `frontend/`:

```powershell
npm install
npm run test:export
```

Expected: FAIL with `ERR_MODULE_NOT_FOUND` for `export-portfolio.mjs`.

- [ ] **Step 3: Implement canonical serialization**

```js
// frontend/scripts/canonical-json.mjs
export const canonicalize = (value) => {
  if (Array.isArray(value)) return value.map(canonicalize)
  if (value === null || typeof value !== 'object') return value
  return Object.fromEntries(
    Object.keys(value)
      .sort((left, right) => left.localeCompare(right, 'en'))
      .map((key) => [key, canonicalize(value[key])]),
  )
}

export const canonicalStringify = (value) => `${JSON.stringify(canonicalize(value), null, 2)}\n`
```

- [ ] **Step 4: Create the closed import schema**

Create a draft-2020-12 schema with `additionalProperties: false` on every object. The top-level and reusable localization rules must be exactly:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://yychainsaw.xyz/schema/portfolio-import-v1.schema.json",
  "type": "object",
  "additionalProperties": false,
  "required": ["schemaVersion", "identity", "heroAsset", "projectAssets", "portfolioContent"],
  "properties": {
    "schemaVersion": { "const": 1 },
    "identity": { "$ref": "#/$defs/identity" },
    "heroAsset": { "$ref": "#/$defs/heroAsset" },
    "projectAssets": {
      "type": "array",
      "items": { "$ref": "#/$defs/projectAsset" }
    },
    "portfolioContent": {
      "type": "object",
      "additionalProperties": false,
      "required": ["zh-CN", "en"],
      "properties": {
        "zh-CN": { "$ref": "#/$defs/portfolioCopy" },
        "en": { "$ref": "#/$defs/portfolioCopy" }
      }
    }
  },
  "$defs": {
    "translationString": {
      "type": "string"
    },
    "localizedString": {
      "type": "object",
      "additionalProperties": false,
      "required": ["zh-CN", "en"],
      "properties": {
        "zh-CN": { "$ref": "#/$defs/translationString" },
        "en": { "$ref": "#/$defs/translationString" }
      }
    },
    "identity": {
      "type": "object",
      "additionalProperties": false,
      "required": ["monogram", "nameZh", "nameEn", "email"],
      "properties": {
        "monogram": { "type": "string", "minLength": 1 },
        "nameZh": { "$ref": "#/$defs/translationString" },
        "nameEn": { "$ref": "#/$defs/translationString" },
        "email": { "type": "string", "format": "email" }
      }
    },
    "heroAsset": {
      "type": "object",
      "additionalProperties": false,
      "required": ["image", "objectPosition", "credit", "sourceUrl", "alt"],
      "properties": {
        "image": { "type": "string", "pattern": "^/images/[^/]+$" },
        "objectPosition": { "type": "string", "minLength": 1 },
        "credit": { "type": "string", "minLength": 1 },
        "sourceUrl": { "type": "string", "format": "uri" },
        "alt": { "$ref": "#/$defs/localizedString" }
      }
    },
    "projectAsset": {
      "type": "object",
      "additionalProperties": false,
      "required": ["id", "image", "layout", "objectPosition", "credit", "sourceUrl", "alt"],
      "properties": {
        "id": { "type": "string", "pattern": "^[a-z0-9]+(?:-[a-z0-9]+)*$" },
        "image": { "type": "string", "pattern": "^/images/[^/]+$" },
        "layout": { "enum": ["wide", "standard"] },
        "objectPosition": { "type": "string", "minLength": 1 },
        "credit": { "type": "string", "minLength": 1 },
        "sourceUrl": { "type": "string", "format": "uri" },
        "alt": { "$ref": "#/$defs/localizedString" }
      }
    }
  }
}
```

Change the closing brace of the preceding `projectAsset` member to `},`, then insert these exact additional members into `$defs` before its closing brace (the repeated `additionalProperties: false` is intentional and keeps the import contract closed at every level):

```json
"portfolioCopy": {
  "type": "object",
  "additionalProperties": false,
  "required": ["seo", "a11y", "nav", "hero", "about", "work", "projects", "roadmap", "contact"],
  "properties": {
    "seo": { "$ref": "#/$defs/seo" },
    "a11y": { "$ref": "#/$defs/a11y" },
    "nav": { "$ref": "#/$defs/nav" },
    "hero": { "$ref": "#/$defs/hero" },
    "about": { "$ref": "#/$defs/about" },
    "work": { "$ref": "#/$defs/work" },
    "projects": { "type": "array", "items": { "$ref": "#/$defs/projectCopy" } },
    "roadmap": { "$ref": "#/$defs/roadmap" },
    "contact": { "$ref": "#/$defs/contact" }
  }
},
"seo": {
  "type": "object",
  "additionalProperties": false,
  "required": ["title", "description"],
  "properties": {
    "title": { "$ref": "#/$defs/translationString" },
    "description": { "$ref": "#/$defs/translationString" }
  }
},
"a11y": {
  "type": "object",
  "additionalProperties": false,
  "required": ["skip", "primaryNav", "mobileNav", "openMenu", "closeMenu", "language", "backToTop", "projectTags"],
  "properties": {
    "skip": { "$ref": "#/$defs/translationString" },
    "primaryNav": { "$ref": "#/$defs/translationString" },
    "mobileNav": { "$ref": "#/$defs/translationString" },
    "openMenu": { "$ref": "#/$defs/translationString" },
    "closeMenu": { "$ref": "#/$defs/translationString" },
    "language": { "$ref": "#/$defs/translationString" },
    "backToTop": { "$ref": "#/$defs/translationString" },
    "projectTags": { "$ref": "#/$defs/translationString" }
  }
},
"nav": {
  "type": "object",
  "additionalProperties": false,
  "required": ["about", "work", "roadmap", "contact"],
  "properties": {
    "about": { "$ref": "#/$defs/translationString" },
    "work": { "$ref": "#/$defs/translationString" },
    "roadmap": { "$ref": "#/$defs/translationString" },
    "contact": { "$ref": "#/$defs/translationString" }
  }
},
"hero": {
  "type": "object",
  "additionalProperties": false,
  "required": ["eyebrow", "displayName", "secondaryName", "role", "headline", "introduction", "availability", "primaryCta", "secondaryCta", "visualLabel", "stageLabel"],
  "properties": {
    "eyebrow": { "$ref": "#/$defs/translationString" },
    "displayName": { "$ref": "#/$defs/translationString" },
    "secondaryName": { "$ref": "#/$defs/translationString" },
    "role": { "$ref": "#/$defs/translationString" },
    "headline": { "$ref": "#/$defs/translationString" },
    "introduction": { "$ref": "#/$defs/translationString" },
    "availability": { "$ref": "#/$defs/translationString" },
    "primaryCta": { "$ref": "#/$defs/translationString" },
    "secondaryCta": { "$ref": "#/$defs/translationString" },
    "visualLabel": { "$ref": "#/$defs/translationString" },
    "stageLabel": { "$ref": "#/$defs/translationString" }
  }
},
"about": {
  "type": "object",
  "additionalProperties": false,
  "required": ["label", "title", "statement", "focusLabel", "focusTitle", "focusIntro", "facts", "skills"],
  "properties": {
    "label": { "$ref": "#/$defs/translationString" },
    "title": { "$ref": "#/$defs/translationString" },
    "statement": { "$ref": "#/$defs/translationString" },
    "focusLabel": { "$ref": "#/$defs/translationString" },
    "focusTitle": { "$ref": "#/$defs/translationString" },
    "focusIntro": { "$ref": "#/$defs/translationString" },
    "facts": { "type": "array", "items": { "$ref": "#/$defs/fact" } },
    "skills": { "type": "array", "items": { "$ref": "#/$defs/profileSkill" } }
  }
},
"fact": {
  "type": "object",
  "additionalProperties": false,
  "required": ["label", "value"],
  "properties": {
    "label": { "$ref": "#/$defs/translationString" },
    "value": { "$ref": "#/$defs/translationString" }
  }
},
"profileSkill": {
  "type": "object",
  "additionalProperties": false,
  "required": ["name", "status"],
  "properties": {
    "name": { "$ref": "#/$defs/translationString" },
    "status": { "$ref": "#/$defs/translationString" }
  }
},
"work": {
  "type": "object",
  "additionalProperties": false,
  "required": ["label", "title", "introduction", "imageNotice", "openSlotLabel", "openSlotTitle", "openSlotText", "openSlotMeta"],
  "properties": {
    "label": { "$ref": "#/$defs/translationString" },
    "title": { "$ref": "#/$defs/translationString" },
    "introduction": { "$ref": "#/$defs/translationString" },
    "imageNotice": { "$ref": "#/$defs/translationString" },
    "openSlotLabel": { "$ref": "#/$defs/translationString" },
    "openSlotTitle": { "$ref": "#/$defs/translationString" },
    "openSlotText": { "$ref": "#/$defs/translationString" },
    "openSlotMeta": { "$ref": "#/$defs/translationString" }
  }
},
"projectCopy": {
  "type": "object",
  "additionalProperties": false,
  "required": ["id", "number", "status", "eyebrow", "title", "summary", "tags"],
  "properties": {
    "id": { "type": "string", "pattern": "^[a-z0-9]+(?:-[a-z0-9]+)*$" },
    "number": { "$ref": "#/$defs/translationString" },
    "status": { "$ref": "#/$defs/translationString" },
    "eyebrow": { "$ref": "#/$defs/translationString" },
    "title": { "$ref": "#/$defs/translationString" },
    "summary": { "$ref": "#/$defs/translationString" },
    "tags": { "type": "array", "items": { "$ref": "#/$defs/translationString" } }
  }
},
"roadmap": {
  "type": "object",
  "additionalProperties": false,
  "required": ["label", "title", "introduction", "stages"],
  "properties": {
    "label": { "$ref": "#/$defs/translationString" },
    "title": { "$ref": "#/$defs/translationString" },
    "introduction": { "$ref": "#/$defs/translationString" },
    "stages": { "type": "array", "items": { "$ref": "#/$defs/roadmapStage" } }
  }
},
"roadmapStage": {
  "type": "object",
  "additionalProperties": false,
  "required": ["id", "number", "period", "title", "summary", "outcomes"],
  "properties": {
    "id": { "type": "string", "pattern": "^[a-z0-9]+(?:-[a-z0-9]+)*$" },
    "number": { "$ref": "#/$defs/translationString" },
    "period": { "$ref": "#/$defs/translationString" },
    "title": { "$ref": "#/$defs/translationString" },
    "summary": { "$ref": "#/$defs/translationString" },
    "outcomes": { "type": "array", "items": { "$ref": "#/$defs/translationString" } }
  }
},
"contact": {
  "type": "object",
  "additionalProperties": false,
  "required": ["label", "title", "introduction", "emailLabel", "email", "workCta", "roadmapCta", "footerNote"],
  "properties": {
    "label": { "$ref": "#/$defs/translationString" },
    "title": { "$ref": "#/$defs/translationString" },
    "introduction": { "$ref": "#/$defs/translationString" },
    "emailLabel": { "$ref": "#/$defs/translationString" },
    "email": { "type": "string", "format": "email" },
    "workCta": { "$ref": "#/$defs/translationString" },
    "roadmapCta": { "$ref": "#/$defs/translationString" },
    "footerNote": { "$ref": "#/$defs/translationString" }
  }
}
```

`translationString` intentionally has no `minLength`: every locale object and every named field remains present because its containing object's `required` array is closed, but `""` is the only schema-valid representation of a not-yet-translated value. Ajv must reject a missing `zh-CN`/`en` key, a missing named field, `null`, an unknown field, or the wrong scalar type. Java dry-run classifies a present blank translation as `IMPORT_TRANSLATION_INCOMPLETE` (`PUBLISH_WARNING`); drafts may retain it, while `PublicationValidator` rejects it as incomplete. Structural IDs, public paths, common media metadata, monogram, email, and URL formats retain the nonblank/pattern constraints shown above.

- [ ] **Step 5: Implement the Vite loader, schema validation, canonical write, and SHA-256**

```js
// frontend/scripts/export-portfolio.mjs
import { createHash } from 'node:crypto'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import Ajv2020 from 'ajv/dist/2020.js'
import addFormats from 'ajv-formats'
import { createServer } from 'vite'
import { canonicalStringify } from './canonical-json.mjs'

const frontendRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const schemaPath = resolve(frontendRoot, 'schema/portfolio-import-v1.schema.json')

export const assertValidPortfolioPayload = async (payload) => {
  const schema = JSON.parse(await readFile(schemaPath, 'utf8'))
  const ajv = new Ajv2020({ allErrors: true, strict: true })
  addFormats(ajv)
  const validate = ajv.compile(schema)
  if (!validate(payload)) {
    throw new Error(`portfolio schema validation failed: ${ajv.errorsText(validate.errors)}`)
  }
}

export const exportPortfolio = async ({ outputPath }) => {
  const server = await createServer({
    root: frontendRoot,
    appType: 'custom',
    logLevel: 'error',
    server: { middlewareMode: true },
  })
  try {
    const module = await server.ssrLoadModule('/src/data/portfolio.ts')
    const payload = {
      schemaVersion: 1,
      identity: module.identity,
      heroAsset: module.heroAsset,
      projectAssets: module.projectAssets,
      portfolioContent: module.portfolioContent,
    }
    await assertValidPortfolioPayload(payload)
    const bytes = canonicalStringify(payload)
    const absoluteOutput = resolve(frontendRoot, outputPath)
    await mkdir(dirname(absoluteOutput), { recursive: true })
    await writeFile(absoluteOutput, bytes, 'utf8')
    return {
      outputPath: absoluteOutput,
      sha256: createHash('sha256').update(bytes, 'utf8').digest('hex'),
    }
  } finally {
    await server.close()
  }
}

const outputFlag = process.argv.indexOf('--output')
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  if (outputFlag < 0 || !process.argv[outputFlag + 1]) {
    throw new Error('usage: node scripts/export-portfolio.mjs --output OUTPUT_PATH')
  }
  const result = await exportPortfolio({ outputPath: process.argv[outputFlag + 1] })
  process.stdout.write(`${JSON.stringify(result)}\n`)
}
```

Also add `"ajv-formats": "3.0.1"` to `devDependencies`, because the schema uses `email` and `uri` formats.

- [ ] **Step 6: Run the exporter tests and generate the ignored artifact**

Run from `frontend/`:

```powershell
npm run test:export
npm run export:portfolio
```

Expected: both commands exit `0`; the test reports `2 passed`, including the blank-versus-missing translation contract, and the exporter prints JSON containing a 64-character `sha256` and an output path ending in `runtime/import/portfolio-v1.json`.

- [ ] **Step 7: Commit the exporter contract**

```powershell
git add frontend/package.json frontend/package-lock.json frontend/scripts/canonical-json.mjs frontend/scripts/export-portfolio.mjs frontend/scripts/export-portfolio.test.mjs frontend/schema/portfolio-import-v1.schema.json
git commit -m "feat(content): add deterministic portfolio exporter"
```

### Task 2: Flyway V4 normalized bilingual content workspace

**Files:**
- Create: `backend-parent/portfolio-server/src/main/resources/db/migration/V4__content_workspace.sql`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/content/persistence/ContentWorkspaceMigrationTest.java`

**Interfaces:**
- Consumes: plan 01 Flyway/Testcontainers setup and plan 02 tables `media_asset(id uuid primary key)` plus `media_variant(asset_id uuid, variant_name varchar, unique(asset_id, variant_name))`.
- Produces: all normalized editable tables named in design section 7.2, the fixed workspace SITE row, locale/slug/block constraints, and optimistic `version` columns.

- [ ] **Step 1: Write a failing migration inventory test**

```java
// backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/content/persistence/ContentWorkspaceMigrationTest.java
package xyz.yychainsaw.portfolio.content.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
@ActiveProfiles("test")
class ContentWorkspaceMigrationTest extends PostgresIntegrationTestBase {
    @Autowired JdbcTemplate jdbc;

    @Test
    void v4CreatesEveryNormalizedWorkspaceTable() {
        List<String> actual = jdbc.queryForList(
                "select table_name from information_schema.tables " +
                "where table_schema = 'portfolio' order by table_name",
                String.class);
        assertThat(actual).contains(
                "site_profile", "site_profile_translation", "site_seo_translation",
                "site_accessibility_copy_translation", "site_navigation_item",
                "site_navigation_item_translation", "hero_section", "hero_section_translation",
                "hero_media", "about_section_translation", "work_section_translation",
                "contact_section_translation", "privacy_notice_translation", "social_link",
                "profile_fact", "profile_fact_translation", "profile_skill",
                "profile_skill_translation", "tag", "tag_translation", "skill",
                "skill_translation", "project", "project_translation", "project_tag",
                "project_skill", "project_media", "project_content_block",
                "project_content_block_translation", "content_block_media",
                "content_block_markdown_translation", "content_block_video",
                "content_block_code", "content_block_quote_translation",
                "content_block_action", "content_block_metric",
                "content_block_metric_translation", "roadmap_header_translation",
                "roadmap_stage", "roadmap_stage_translation", "roadmap_outcome",
                "roadmap_outcome_translation", "resume_document");
    }

    @Test
    void v4RejectsUnsupportedLocaleAndInvalidSlug() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> jdbc.update(
                "insert into site_profile_translation(site_id, locale, display_name, secondary_name) " +
                "values (?::uuid, 'fr', 'Nom', 'Name')",
                "00000000-0000-0000-0000-000000000001"))).isNotNull();
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> jdbc.update(
                "insert into project(id, external_key, slug, number_label, sort_order) " +
                "values (?::uuid, 'bad', 'Bad Slug', '01', 0)",
                "00000000-0000-0000-0000-000000000099"))).isNotNull();
    }

    @Test
    void runtimeCanPerformWorkspaceDmlButCannotCreateSchemaObjects() {
        UUID projectId = UUID.randomUUID();
        String key = "grant-" + projectId;
        int sortOrder = 1_000_000_000 + Math.floorMod(projectId.hashCode(), 1_000_000);

        assertThat(jdbc.update(
                "insert into project(id, external_key, slug, number_label, sort_order) " +
                "values (?, ?, ?, '99', ?)", projectId, key, key, sortOrder)).isEqualTo(1);
        assertThat(jdbc.update(
                "update project set featured=true where id=?", projectId)).isEqualTo(1);
        assertThat(jdbc.update("delete from project where id=?", projectId)).isEqualTo(1);

        assertThatThrownBy(() -> jdbc.execute(
                "create table v4_runtime_must_not_create(id integer)"))
                .hasMessageContaining("permission denied");
    }
}
```

- [ ] **Step 2: Run the focused test and observe the missing V4 failure**

Run from `backend-parent/`:

```powershell
.\mvnw.cmd -pl portfolio-server -am -Dtest=ContentWorkspaceMigrationTest test
```

Expected: FAIL because `site_profile` and the other V4 tables do not exist.

- [ ] **Step 3: Create V4 site, navigation, profile, and taxonomy tables**

Start `V4__content_workspace.sql` with this exact SQL:

```sql
create table site_profile (
    id uuid primary key,
    monogram varchar(16) not null default '',
    email varchar(320) not null default '',
    version bigint not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

insert into site_profile(id)
values ('00000000-0000-0000-0000-000000000001');

create table site_profile_translation (
    site_id uuid not null references site_profile(id) on delete cascade,
    locale varchar(5) not null check (locale in ('zh-CN', 'en')),
    display_name text not null,
    secondary_name text not null,
    primary key (site_id, locale)
);

create table site_seo_translation (
    site_id uuid not null references site_profile(id) on delete cascade,
    locale varchar(5) not null check (locale in ('zh-CN', 'en')),
    title text not null,
    description text not null,
    primary key (site_id, locale)
);

create table site_accessibility_copy_translation (
    site_id uuid not null references site_profile(id) on delete cascade,
    locale varchar(5) not null check (locale in ('zh-CN', 'en')),
    skip_text text not null,
    primary_nav text not null,
    mobile_nav text not null,
    open_menu text not null,
    close_menu text not null,
    language_text text not null,
    back_to_top text not null,
    project_tags text not null,
    primary key (site_id, locale)
);

create table site_navigation_item (
    id uuid primary key,
    site_id uuid not null references site_profile(id) on delete cascade,
    target varchar(32) not null check (target in ('about', 'work', 'roadmap', 'contact')),
    sort_order integer not null check (sort_order >= 0),
    visible boolean not null default true,
    unique (site_id, target),
    unique (site_id, sort_order)
);

create table site_navigation_item_translation (
    navigation_item_id uuid not null references site_navigation_item(id) on delete cascade,
    locale varchar(5) not null check (locale in ('zh-CN', 'en')),
    label text not null,
    primary key (navigation_item_id, locale)
);

create table hero_section (
    id uuid primary key,
    site_id uuid not null unique references site_profile(id) on delete cascade,
    version bigint not null default 0
);

create table hero_section_translation (
    hero_id uuid not null references hero_section(id) on delete cascade,
    locale varchar(5) not null check (locale in ('zh-CN', 'en')),
    eyebrow text not null,
    display_name text not null,
    secondary_name text not null,
    role_text text not null,
    headline text not null,
    introduction text not null,
    availability text not null,
    primary_cta text not null,
    secondary_cta text not null,
    visual_label text not null,
    stage_label text not null,
    primary key (hero_id, locale)
);

create table hero_media (
    hero_id uuid primary key references hero_section(id) on delete cascade,
    media_asset_id uuid not null references media_asset(id),
    object_position varchar(64) not null,
    credit text not null,
    source_url text not null check (source_url ~ '^https://')
);

create table about_section_translation (
    site_id uuid not null references site_profile(id) on delete cascade,
    locale varchar(5) not null check (locale in ('zh-CN', 'en')),
    label text not null,
    title text not null,
    statement text not null,
    focus_label text not null,
    focus_title text not null,
    focus_intro text not null,
    primary key (site_id, locale)
);

create table work_section_translation (
    site_id uuid not null references site_profile(id) on delete cascade,
    locale varchar(5) not null check (locale in ('zh-CN', 'en')),
    label text not null,
    title text not null,
    introduction text not null,
    image_notice text not null,
    open_slot_label text not null,
    open_slot_title text not null,
    open_slot_text text not null,
    open_slot_meta text not null,
    primary key (site_id, locale)
);

create table contact_section_translation (
    site_id uuid not null references site_profile(id) on delete cascade,
    locale varchar(5) not null check (locale in ('zh-CN', 'en')),
    label text not null,
    title text not null,
    introduction text not null,
    email_label text not null,
    work_cta text not null,
    roadmap_cta text not null,
    footer_note text not null,
    primary key (site_id, locale)
);

create table privacy_notice_translation (
    site_id uuid not null references site_profile(id) on delete cascade,
    locale varchar(5) not null check (locale in ('zh-CN', 'en')),
    title text not null default '',
    body_markdown text not null default '',
    primary key (site_id, locale)
);

create table social_link (
    id uuid primary key,
    site_id uuid not null references site_profile(id) on delete cascade,
    platform varchar(32) not null,
    url text not null check (url ~ '^https://'),
    sort_order integer not null check (sort_order >= 0),
    visible boolean not null default true,
    unique (site_id, platform),
    unique (site_id, sort_order)
);

create table profile_fact (
    id uuid primary key,
    site_id uuid not null references site_profile(id) on delete cascade,
    external_key varchar(96) not null,
    sort_order integer not null check (sort_order >= 0),
    unique (site_id, external_key),
    unique (site_id, sort_order)
);

create table profile_fact_translation (
    fact_id uuid not null references profile_fact(id) on delete cascade,
    locale varchar(5) not null check (locale in ('zh-CN', 'en')),
    label text not null,
    value_text text not null,
    primary key (fact_id, locale)
);

create table profile_skill (
    id uuid primary key,
    site_id uuid not null references site_profile(id) on delete cascade,
    external_key varchar(96) not null,
    sort_order integer not null check (sort_order >= 0),
    unique (site_id, external_key),
    unique (site_id, sort_order)
);

create table profile_skill_translation (
    profile_skill_id uuid not null references profile_skill(id) on delete cascade,
    locale varchar(5) not null check (locale in ('zh-CN', 'en')),
    name text not null,
    status_text text not null,
    primary key (profile_skill_id, locale)
);

create table tag (
    id uuid primary key,
    normalized_key varchar(96) not null unique,
    version bigint not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table tag_translation (
    tag_id uuid not null references tag(id) on delete cascade,
    locale varchar(5) not null check (locale in ('zh-CN', 'en')),
    name text not null,
    primary key (tag_id, locale)
);

create table skill (
    id uuid primary key,
    normalized_key varchar(96) not null unique,
    version bigint not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table skill_translation (
    skill_id uuid not null references skill(id) on delete cascade,
    locale varchar(5) not null check (locale in ('zh-CN', 'en')),
    name text not null,
    primary key (skill_id, locale)
);
```

- [ ] **Step 4: Append project, typed block, roadmap, and resume tables to V4**

```sql
create table project (
    id uuid primary key,
    external_key varchar(96) not null unique,
    slug varchar(120) not null unique check (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    number_label varchar(16) not null,
    sort_order integer not null check (sort_order >= 0),
    featured boolean not null default false,
    visible boolean not null default true,
    publication_dirty boolean not null default true,
    version bigint not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (sort_order)
);

create table project_translation (
    project_id uuid not null references project(id) on delete cascade,
    locale varchar(5) not null check (locale in ('zh-CN', 'en')),
    status_label text not null,
    eyebrow text not null,
    title text not null,
    summary text not null,
    seo_title text not null default '',
    seo_description text not null default '',
    primary key (project_id, locale)
);

create table project_tag (
    project_id uuid not null references project(id) on delete cascade,
    tag_id uuid not null references tag(id),
    sort_order integer not null check (sort_order >= 0),
    primary key (project_id, tag_id),
    unique (project_id, sort_order)
);

create table project_skill (
    project_id uuid not null references project(id) on delete cascade,
    skill_id uuid not null references skill(id),
    sort_order integer not null check (sort_order >= 0),
    primary key (project_id, skill_id),
    unique (project_id, sort_order)
);

create table project_media (
    project_id uuid not null references project(id) on delete cascade,
    media_asset_id uuid not null references media_asset(id),
    usage varchar(32) not null check (usage in ('COVER', 'CARD', 'DETAIL')),
    sort_order integer not null check (sort_order >= 0),
    layout varchar(16) not null check (layout in ('wide', 'standard')),
    object_position varchar(64) not null,
    credit text not null,
    source_url text not null check (source_url ~ '^https://'),
    primary key (project_id, media_asset_id, usage),
    unique (project_id, usage, sort_order)
);

create table project_content_block (
    id uuid primary key,
    project_id uuid not null references project(id) on delete cascade,
    block_type varchar(16) not null check (block_type in
        ('MARKDOWN', 'IMAGE', 'GALLERY', 'VIDEO', 'CODE', 'QUOTE', 'METRICS', 'DOWNLOAD', 'LINK')),
    sort_order integer not null check (sort_order >= 0),
    visible boolean not null default true,
    width varchar(16) not null default 'STANDARD' check (width in ('NARROW', 'STANDARD', 'WIDE', 'FULL')),
    alignment varchar(16) not null default 'LEFT' check (alignment in ('LEFT', 'CENTER', 'RIGHT')),
    emphasis varchar(16) not null default 'NONE' check (emphasis in ('NONE', 'SOFT', 'STRONG')),
    columns smallint not null default 1 check (columns between 1 and 4),
    version bigint not null default 0,
    unique (project_id, sort_order)
);

create table project_content_block_translation (
    block_id uuid not null references project_content_block(id) on delete cascade,
    locale varchar(5) not null check (locale in ('zh-CN', 'en')),
    title text,
    description text,
    primary key (block_id, locale)
);

create table content_block_media (
    block_id uuid not null references project_content_block(id) on delete cascade,
    media_asset_id uuid not null references media_asset(id),
    role varchar(16) not null check (role in ('PRIMARY', 'GALLERY', 'COVER', 'FILE')),
    sort_order integer not null check (sort_order >= 0),
    primary key (block_id, media_asset_id, role),
    unique (block_id, role, sort_order)
);

create table content_block_markdown_translation (
    block_id uuid not null references project_content_block(id) on delete cascade,
    locale varchar(5) not null check (locale in ('zh-CN', 'en')),
    markdown text not null,
    primary key (block_id, locale)
);

create table content_block_video (
    block_id uuid primary key references project_content_block(id) on delete cascade,
    provider varchar(16) not null check (provider in ('BILIBILI', 'YOUTUBE', 'VIMEO')),
    url text not null check (url ~ '^https://'),
    cover_asset_id uuid references media_asset(id)
);

create table content_block_code (
    block_id uuid primary key references project_content_block(id) on delete cascade,
    code_text text not null,
    language varchar(32) not null,
    show_line_numbers boolean not null default true
);

create table content_block_quote_translation (
    block_id uuid not null references project_content_block(id) on delete cascade,
    locale varchar(5) not null check (locale in ('zh-CN', 'en')),
    quote_text text not null,
    source_text text not null,
    primary key (block_id, locale)
);

create table content_block_action (
    block_id uuid primary key references project_content_block(id) on delete cascade,
    action_type varchar(16) not null check (action_type in ('DOWNLOAD', 'LINK')),
    target_type varchar(16) not null check (target_type in ('MEDIA', 'EXTERNAL')),
    media_asset_id uuid references media_asset(id),
    url text,
    open_new_tab boolean not null default true,
    check ((target_type = 'MEDIA' and media_asset_id is not null and url is null)
        or (target_type = 'EXTERNAL' and media_asset_id is null and url ~ '^https://'))
);

create table content_block_metric (
    id uuid primary key,
    block_id uuid not null references project_content_block(id) on delete cascade,
    sort_order integer not null check (sort_order >= 0),
    numeric_value numeric,
    unique (block_id, sort_order)
);

create table content_block_metric_translation (
    metric_id uuid not null references content_block_metric(id) on delete cascade,
    locale varchar(5) not null check (locale in ('zh-CN', 'en')),
    label text not null,
    value_text text not null,
    suffix text not null default '',
    primary key (metric_id, locale)
);

create table roadmap_header_translation (
    site_id uuid not null references site_profile(id) on delete cascade,
    locale varchar(5) not null check (locale in ('zh-CN', 'en')),
    label text not null,
    title text not null,
    introduction text not null,
    primary key (site_id, locale)
);

create table roadmap_stage (
    id uuid primary key,
    site_id uuid not null references site_profile(id) on delete cascade,
    external_key varchar(96) not null,
    number_label varchar(16) not null,
    sort_order integer not null check (sort_order >= 0),
    visible boolean not null default true,
    unique (site_id, external_key),
    unique (site_id, sort_order)
);

create table roadmap_stage_translation (
    stage_id uuid not null references roadmap_stage(id) on delete cascade,
    locale varchar(5) not null check (locale in ('zh-CN', 'en')),
    period text not null,
    title text not null,
    summary text not null,
    primary key (stage_id, locale)
);

create table roadmap_outcome (
    id uuid primary key,
    stage_id uuid not null references roadmap_stage(id) on delete cascade,
    sort_order integer not null check (sort_order >= 0),
    unique (stage_id, sort_order)
);

create table roadmap_outcome_translation (
    outcome_id uuid not null references roadmap_outcome(id) on delete cascade,
    locale varchar(5) not null check (locale in ('zh-CN', 'en')),
    outcome_text text not null,
    primary key (outcome_id, locale)
);

create table resume_document (
    id uuid primary key,
    site_id uuid not null references site_profile(id) on delete cascade,
    locale varchar(5) not null check (locale in ('zh-CN', 'en')),
    media_asset_id uuid not null references media_asset(id),
    version_label varchar(64) not null,
    is_current boolean not null default false,
    document_date date not null,
    created_at timestamptz not null default now()
);

create unique index uq_resume_current_locale
on resume_document(site_id, locale)
where is_current;

create index idx_project_dirty on project(publication_dirty) where publication_dirty;
create index idx_project_content_block_project on project_content_block(project_id, sort_order);
create index idx_roadmap_stage_site on roadmap_stage(site_id, sort_order);

-- The singleton SITE row is created by Flyway; runtime may read and edit it, not create/delete it.
grant select, update on table site_profile to portfolio_runtime;

-- UUID keys are application-generated, so V4 creates no sequences. Workspace replace/import
-- needs CRUD on every mutable child/root table and receives no schema CREATE or table TRUNCATE.
grant select, insert, update, delete on table
    site_profile_translation,
    site_seo_translation,
    site_accessibility_copy_translation,
    site_navigation_item,
    site_navigation_item_translation,
    hero_section,
    hero_section_translation,
    hero_media,
    about_section_translation,
    work_section_translation,
    contact_section_translation,
    privacy_notice_translation,
    social_link,
    profile_fact,
    profile_fact_translation,
    profile_skill,
    profile_skill_translation,
    tag,
    tag_translation,
    skill,
    skill_translation,
    project,
    project_translation,
    project_tag,
    project_skill,
    project_media,
    project_content_block,
    project_content_block_translation,
    content_block_media,
    content_block_markdown_translation,
    content_block_video,
    content_block_code,
    content_block_quote_translation,
    content_block_action,
    content_block_metric,
    content_block_metric_translation,
    roadmap_header_translation,
    roadmap_stage,
    roadmap_stage_translation,
    roadmap_outcome,
    roadmap_outcome_translation,
    resume_document
to portfolio_runtime;
```

- [ ] **Step 5: Run the migration tests and the full Flyway test set**

Run from `backend-parent/`:

```powershell
.\mvnw.cmd -pl portfolio-server -am -Dtest=ContentWorkspaceMigrationTest test
.\mvnw.cmd -pl portfolio-server -am -Dtest=*MigrationTest test
```

Expected: both commands exit `0`; `ContentWorkspaceMigrationTest` reports `3 tests run, 0 failures`, runtime DML succeeds while runtime DDL is denied, and Flyway applies V1 through V4 from an empty PostgreSQL database.

- [ ] **Step 6: Commit V4**

```powershell
git add backend-parent/portfolio-server/src/main/resources/db/migration/V4__content_workspace.sql backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/content/persistence/ContentWorkspaceMigrationTest.java
git commit -m "feat(content): add normalized workspace schema"
```

### Task 3: Typed workspace contracts and deterministic validation

**Files:**
- Create: `backend-parent/portfolio-pojo/src/main/java/xyz/yychainsaw/portfolio/content/api/LocaleCode.java`
- Create: `backend-parent/portfolio-pojo/src/main/java/xyz/yychainsaw/portfolio/content/api/SiteWorkspaceDto.java`
- Create: `backend-parent/portfolio-pojo/src/main/java/xyz/yychainsaw/portfolio/content/api/ProjectWorkspaceDto.java`
- Create: `backend-parent/portfolio-pojo/src/main/java/xyz/yychainsaw/portfolio/content/api/ContentBlockDto.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/content/application/WorkspaceValidator.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/content/application/WorkspaceValidatorTest.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/content/support/WorkspaceFixtures.java`

**Interfaces:**
- Consumes: `DomainException(String, HttpStatus, Map<String,String>)` from plan 01 and media IDs/READY state from plan 02.
- Produces: the single DTO vocabulary used by admin APIs, import mapping, snapshot mapping, restore, and public DTO projection; `WorkspaceValidator.validateSite` and `validateProject`.

- [ ] **Step 1: Write failing validation tests for locale completeness and unsafe blocks**

```java
// backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/content/application/WorkspaceValidatorTest.java
package xyz.yychainsaw.portfolio.content.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.content.api.ContentBlockDto;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.content.api.ProjectWorkspaceDto;
import xyz.yychainsaw.portfolio.content.support.WorkspaceFixtures;

class WorkspaceValidatorTest {
    private final WorkspaceValidator validator = new WorkspaceValidator();

    @Test
    void rejectsProjectWithoutBothTranslations() {
        ProjectWorkspaceDto project = WorkspaceFixtures.projectBuilder()
                .translations(Map.of(LocaleCode.EN, new ProjectWorkspaceDto.ProjectCopy(
                        "In progress", "Gameplay", "Prototype", "Summary", "SEO", "Description")))
                .build();
        assertThatThrownBy(() -> validator.validateProject(project))
                .isInstanceOf(DomainException.class)
                .extracting(error -> ((DomainException) error).code())
                .isEqualTo("PROJECT_TRANSLATION_INCOMPLETE");
    }

    @Test
    void rejectsUnsafeLinkProtocol() {
        ContentBlockDto block = new ContentBlockDto(
                UUID.randomUUID(), 0, true,
                ContentBlockDto.Width.STANDARD, ContentBlockDto.Alignment.LEFT,
                ContentBlockDto.Emphasis.NONE, 1,
                new ContentBlockDto.LinkPayload(
                        URI.create("javascript:alert(1)"), true,
                        Map.of(LocaleCode.ZH_CN, new ContentBlockDto.ActionCopy("链接", "说明"),
                                LocaleCode.EN, new ContentBlockDto.ActionCopy("Link", "Description"))));
        ProjectWorkspaceDto project = WorkspaceFixtures.projectBuilder().blocks(List.of(block)).build();
        assertThatThrownBy(() -> validator.validateProject(project))
                .isInstanceOf(DomainException.class)
                .extracting(error -> ((DomainException) error).code())
                .isEqualTo("CONTENT_BLOCK_INVALID");
    }
}
```

`WorkspaceFixtures.projectBuilder()` returns a complete two-locale project with empty media/blocks by default and exposes `translations(Map<LocaleCode,ProjectCopy>)`, `blocks(List<ContentBlockDto>)`, and `build()`. The same class defines `site(long version)`, `project()`, `projectWithoutMedia()`, and `withEnglishTitle(ProjectWorkspaceDto source, String title)`; each builds from fixed test constants so tests never rely on production seed data.

- [ ] **Step 2: Run the test and verify the contracts are absent**

Run from `backend-parent/`:

```powershell
.\mvnw.cmd -pl portfolio-server -am -Dtest=WorkspaceValidatorTest test
```

Expected: FAIL at compilation because `LocaleCode`, the workspace DTOs, and `WorkspaceValidator` do not exist.

- [ ] **Step 3: Define locale and site aggregate DTOs**

```java
// backend-parent/portfolio-pojo/src/main/java/xyz/yychainsaw/portfolio/content/api/LocaleCode.java
package xyz.yychainsaw.portfolio.content.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum LocaleCode {
    ZH_CN("zh-CN"),
    EN("en");

    private final String value;

    LocaleCode(String value) { this.value = value; }

    @JsonValue
    public String value() { return value; }

    @JsonCreator
    public static LocaleCode from(String value) {
        for (LocaleCode locale : values()) {
            if (locale.value.equals(value)) return locale;
        }
        throw new IllegalArgumentException("unsupported locale: " + value);
    }
}
```

```java
// backend-parent/portfolio-pojo/src/main/java/xyz/yychainsaw/portfolio/content/api/SiteWorkspaceDto.java
package xyz.yychainsaw.portfolio.content.api;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record SiteWorkspaceDto(
        UUID siteId,
        long version,
        String monogram,
        String email,
        Map<LocaleCode, IdentityCopy> identity,
        Map<LocaleCode, SeoCopy> seo,
        Map<LocaleCode, AccessibilityCopy> accessibility,
        List<NavigationItem> navigation,
        Hero hero,
        Map<LocaleCode, AboutCopy> about,
        List<ProfileFact> facts,
        List<ProfileSkill> profileSkills,
        Map<LocaleCode, WorkCopy> work,
        Roadmap roadmap,
        Map<LocaleCode, ContactCopy> contact,
        Map<LocaleCode, PrivacyCopy> privacy,
        List<SocialLink> socialLinks,
        List<ResumeDocument> resumes
) {
    public static final UUID SITE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    public SiteWorkspaceDto {
        identity = Map.copyOf(identity);
        seo = Map.copyOf(seo);
        accessibility = Map.copyOf(accessibility);
        navigation = List.copyOf(navigation);
        about = Map.copyOf(about);
        facts = List.copyOf(facts);
        profileSkills = List.copyOf(profileSkills);
        work = Map.copyOf(work);
        contact = Map.copyOf(contact);
        privacy = Map.copyOf(privacy);
        socialLinks = List.copyOf(socialLinks);
        resumes = List.copyOf(resumes);
    }

    public record IdentityCopy(String displayName, String secondaryName) {}
    public record SeoCopy(String title, String description) {}
    public record AccessibilityCopy(
            String skip, String primaryNav, String mobileNav, String openMenu,
            String closeMenu, String language, String backToTop, String projectTags) {}
    public record NavigationItem(UUID id, String target, int sortOrder, boolean visible,
                                 Map<LocaleCode, String> labels) {
        public NavigationItem { labels = Map.copyOf(labels); }
    }
    public record Hero(UUID id, long version, UUID mediaAssetId, String objectPosition,
                       String credit, URI sourceUrl, Map<LocaleCode, HeroCopy> copy) {
        public Hero { copy = Map.copyOf(copy); }
    }
    public record HeroCopy(
            String eyebrow, String displayName, String secondaryName, String role,
            String headline, String introduction, String availability, String primaryCta,
            String secondaryCta, String visualLabel, String stageLabel) {}
    public record AboutCopy(String label, String title, String statement, String focusLabel,
                            String focusTitle, String focusIntro) {}
    public record ProfileFact(UUID id, String externalKey, int sortOrder,
                              Map<LocaleCode, LabelValueCopy> copy) {
        public ProfileFact { copy = Map.copyOf(copy); }
    }
    public record ProfileSkill(UUID id, String externalKey, int sortOrder,
                               Map<LocaleCode, SkillStatusCopy> copy) {
        public ProfileSkill { copy = Map.copyOf(copy); }
    }
    public record LabelValueCopy(String label, String value) {}
    public record SkillStatusCopy(String name, String status) {}
    public record WorkCopy(String label, String title, String introduction, String imageNotice,
                           String openSlotLabel, String openSlotTitle, String openSlotText,
                           String openSlotMeta) {}
    public record Roadmap(Map<LocaleCode, RoadmapHeaderCopy> header, List<RoadmapStage> stages) {
        public Roadmap { header = Map.copyOf(header); stages = List.copyOf(stages); }
    }
    public record RoadmapHeaderCopy(String label, String title, String introduction) {}
    public record RoadmapStage(UUID id, String externalKey, String number, int sortOrder,
                               boolean visible, Map<LocaleCode, RoadmapStageCopy> copy,
                               List<RoadmapOutcome> outcomes) {
        public RoadmapStage { copy = Map.copyOf(copy); outcomes = List.copyOf(outcomes); }
    }
    public record RoadmapStageCopy(String period, String title, String summary) {}
    public record RoadmapOutcome(UUID id, int sortOrder, Map<LocaleCode, String> text) {
        public RoadmapOutcome { text = Map.copyOf(text); }
    }
    public record ContactCopy(String label, String title, String introduction, String emailLabel,
                              String workCta, String roadmapCta, String footerNote) {}
    public record PrivacyCopy(String title, String bodyMarkdown) {}
    public record SocialLink(UUID id, String platform, URI url, int sortOrder, boolean visible) {}
    public record ResumeDocument(UUID id, LocaleCode locale, UUID mediaAssetId,
                                 String versionLabel, boolean current, LocalDate documentDate) {}
}
```

- [ ] **Step 4: Define project and sealed content-block DTOs**

```java
// backend-parent/portfolio-pojo/src/main/java/xyz/yychainsaw/portfolio/content/api/ContentBlockDto.java
package xyz.yychainsaw.portfolio.content.api;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ContentBlockDto(
        UUID id, int sortOrder, boolean visible, Width width, Alignment alignment,
        Emphasis emphasis, int columns, Payload payload) {
    public enum Width { NARROW, STANDARD, WIDE, FULL }
    public enum Alignment { LEFT, CENTER, RIGHT }
    public enum Emphasis { NONE, SOFT, STRONG }
    public record BlockCopy(String title, String description) {}
    public record ActionCopy(String label, String description) {}
    public record QuoteCopy(String quote, String source) {}
    public record Metric(UUID id, int sortOrder, java.math.BigDecimal numericValue,
                         Map<LocaleCode, MetricCopy> copy) {
        public Metric { copy = Map.copyOf(copy); }
    }
    public record MetricCopy(String label, String value, String suffix) {}

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = MarkdownPayload.class, name = "MARKDOWN"),
            @JsonSubTypes.Type(value = ImagePayload.class, name = "IMAGE"),
            @JsonSubTypes.Type(value = GalleryPayload.class, name = "GALLERY"),
            @JsonSubTypes.Type(value = VideoPayload.class, name = "VIDEO"),
            @JsonSubTypes.Type(value = CodePayload.class, name = "CODE"),
            @JsonSubTypes.Type(value = QuotePayload.class, name = "QUOTE"),
            @JsonSubTypes.Type(value = MetricsPayload.class, name = "METRICS"),
            @JsonSubTypes.Type(value = DownloadPayload.class, name = "DOWNLOAD"),
            @JsonSubTypes.Type(value = LinkPayload.class, name = "LINK")
    })
    public sealed interface Payload permits MarkdownPayload, ImagePayload, GalleryPayload,
            VideoPayload, CodePayload, QuotePayload, MetricsPayload, DownloadPayload, LinkPayload {}

    public record MarkdownPayload(Map<LocaleCode, String> markdown) implements Payload {
        public MarkdownPayload { markdown = Map.copyOf(markdown); }
    }
    public record ImagePayload(UUID mediaAssetId) implements Payload {}
    public record GalleryPayload(List<UUID> mediaAssetIds) implements Payload {
        public GalleryPayload { mediaAssetIds = List.copyOf(mediaAssetIds); }
    }
    public record VideoPayload(String provider, URI url, UUID coverAssetId,
                               Map<LocaleCode, BlockCopy> copy) implements Payload {
        public VideoPayload { copy = Map.copyOf(copy); }
    }
    public record CodePayload(String code, String language, boolean showLineNumbers,
                              Map<LocaleCode, BlockCopy> copy) implements Payload {
        public CodePayload { copy = Map.copyOf(copy); }
    }
    public record QuotePayload(Map<LocaleCode, QuoteCopy> copy) implements Payload {
        public QuotePayload { copy = Map.copyOf(copy); }
    }
    public record MetricsPayload(List<Metric> metrics) implements Payload {
        public MetricsPayload { metrics = List.copyOf(metrics); }
    }
    public record DownloadPayload(UUID mediaAssetId, URI externalUrl,
                                  Map<LocaleCode, ActionCopy> copy) implements Payload {
        public DownloadPayload { copy = Map.copyOf(copy); }
    }
    public record LinkPayload(URI url, boolean openNewTab,
                              Map<LocaleCode, ActionCopy> copy) implements Payload {
        public LinkPayload { copy = Map.copyOf(copy); }
    }
}
```

```java
// backend-parent/portfolio-pojo/src/main/java/xyz/yychainsaw/portfolio/content/api/ProjectWorkspaceDto.java
package xyz.yychainsaw.portfolio.content.api;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ProjectWorkspaceDto(
        UUID id, String externalKey, String slug, String number, int sortOrder,
        boolean featured, boolean visible, boolean publicationDirty, long version,
        Map<LocaleCode, ProjectCopy> translations, List<TaxonomyRef> tags,
        List<TaxonomyRef> skills, List<ProjectMedia> media, List<ContentBlockDto> blocks) {
    public ProjectWorkspaceDto {
        translations = Map.copyOf(translations);
        tags = List.copyOf(tags);
        skills = List.copyOf(skills);
        media = List.copyOf(media);
        blocks = List.copyOf(blocks);
    }
    public record ProjectCopy(String status, String eyebrow, String title, String summary,
                              String seoTitle, String seoDescription) {}
    public record TaxonomyRef(UUID id, String normalizedKey, int sortOrder,
                              Map<LocaleCode, String> names) {
        public TaxonomyRef { names = Map.copyOf(names); }
    }
    public record ProjectMedia(UUID assetId, String usage, int sortOrder, String layout,
                               String objectPosition, String credit, URI sourceUrl) {}
}
```

- [ ] **Step 5: Implement deterministic workspace validation**

`WorkspaceValidator` must collect every error in a `TreeMap`, then throw one `DomainException` with HTTP `422`. The following implementation checks every top-level site translation map and every block payload:

```java
// backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/content/application/WorkspaceValidator.java
package xyz.yychainsaw.portfolio.content.application;

import java.net.URI;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.content.api.ContentBlockDto;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.content.api.ProjectWorkspaceDto;
import xyz.yychainsaw.portfolio.content.api.SiteWorkspaceDto;

public final class WorkspaceValidator {
    public void validateSite(SiteWorkspaceDto site) {
        TreeMap<String, String> errors = new TreeMap<>();
        requireText(errors, "monogram", site.monogram());
        requireText(errors, "email", site.email());
        requireLocales(errors, "identity", site.identity());
        requireLocales(errors, "seo", site.seo());
        requireLocales(errors, "accessibility", site.accessibility());
        requireLocales(errors, "hero.copy", site.hero().copy());
        requireLocales(errors, "about", site.about());
        requireLocales(errors, "work", site.work());
        requireLocales(errors, "roadmap.header", site.roadmap().header());
        requireLocales(errors, "contact", site.contact());
        requireLocales(errors, "privacy", site.privacy());
        finish("SITE_WORKSPACE_INVALID", errors);
    }

    public void validateProject(ProjectWorkspaceDto project) {
        TreeMap<String, String> errors = new TreeMap<>();
        requireText(errors, "slug", project.slug());
        if (!project.slug().matches("^[a-z0-9]+(?:-[a-z0-9]+)*$")) {
            errors.put("slug", "slug must be lowercase ASCII words separated by hyphens");
        }
        requireLocales(errors, "translations", project.translations());
        for (int index = 0; index < project.blocks().size(); index++) {
            validateBlock(errors, "blocks[" + index + "]", project.blocks().get(index));
        }
        finish(errors.keySet().stream().anyMatch(key -> key.startsWith("translations"))
                ? "PROJECT_TRANSLATION_INCOMPLETE" : "CONTENT_BLOCK_INVALID", errors);
    }

    private void validateBlock(Map<String, String> errors, String path, ContentBlockDto block) {
        if (block.columns() < 1 || block.columns() > 4) errors.put(path + ".columns", "must be 1 to 4");
        ContentBlockDto.Payload payload = block.payload();
        if (payload instanceof ContentBlockDto.MarkdownPayload markdown) {
            requireLocales(errors, path + ".markdown", markdown.markdown());
        } else if (payload instanceof ContentBlockDto.ImagePayload image) {
            requireId(errors, path + ".mediaAssetId", image.mediaAssetId());
        } else if (payload instanceof ContentBlockDto.GalleryPayload gallery) {
            if (gallery.mediaAssetIds().size() < 2) errors.put(path + ".mediaAssetIds", "gallery requires at least two images");
        } else if (payload instanceof ContentBlockDto.VideoPayload video) {
            requireHttps(errors, path + ".url", video.url());
            if (!java.util.Set.of("BILIBILI", "YOUTUBE", "VIMEO").contains(video.provider())) {
                errors.put(path + ".provider", "unsupported video provider");
            }
            requireLocales(errors, path + ".copy", video.copy());
        } else if (payload instanceof ContentBlockDto.CodePayload code) {
            requireLocales(errors, path + ".copy", code.copy());
        } else if (payload instanceof ContentBlockDto.QuotePayload quote) {
            requireLocales(errors, path + ".copy", quote.copy());
        } else if (payload instanceof ContentBlockDto.MetricsPayload metrics) {
            if (metrics.metrics().isEmpty()) errors.put(path + ".metrics", "metrics block cannot be empty");
            metrics.metrics().forEach(metric -> requireLocales(errors, path + ".metrics.copy", metric.copy()));
        } else if (payload instanceof ContentBlockDto.DownloadPayload download) {
            if ((download.mediaAssetId() == null) == (download.externalUrl() == null)) {
                errors.put(path, "download requires exactly one media asset or external URL");
            }
            if (download.externalUrl() != null) requireHttps(errors, path + ".externalUrl", download.externalUrl());
            requireLocales(errors, path + ".copy", download.copy());
        } else if (payload instanceof ContentBlockDto.LinkPayload link) {
            requireHttps(errors, path + ".url", link.url());
            requireLocales(errors, path + ".copy", link.copy());
        } else {
            errors.put(path + ".type", "unsupported content block payload");
        }
    }

    private void requireHttps(Map<String, String> errors, String path, URI value) {
        if (value == null || !"https".equalsIgnoreCase(value.getScheme())) errors.put(path, "HTTPS URL required");
    }
    private void requireId(Map<String, String> errors, String path, Object value) {
        if (value == null) errors.put(path, "required");
    }
    private void requireText(Map<String, String> errors, String path, String value) {
        if (value == null || value.isBlank()) errors.put(path, "required");
    }
    private void requireLocales(Map<String, String> errors, String path, Map<LocaleCode, ?> values) {
        if (!values.keySet().equals(java.util.Set.of(LocaleCode.ZH_CN, LocaleCode.EN))) {
            errors.put(path, "exactly zh-CN and en are required");
        }
    }
    private void finish(String code, Map<String, String> errors) {
        if (!errors.isEmpty()) throw new DomainException(code, HttpStatus.UNPROCESSABLE_ENTITY, errors);
    }
}
```

This source uses Java 17 `instanceof` patterns only; do not enable preview language features.

- [ ] **Step 6: Run focused tests and the POJO/server compile**

Run from `backend-parent/`:

```powershell
.\mvnw.cmd -pl portfolio-server -am -Dtest=WorkspaceValidatorTest test
.\mvnw.cmd -pl portfolio-server -am -DskipTests compile
```

Expected: both commands exit `0`; the validator test reports `2 tests run, 0 failures`, and Java 17 compilation succeeds without `--enable-preview`.

- [ ] **Step 7: Commit typed workspace contracts**

```powershell
git add backend-parent/portfolio-pojo/src/main/java/xyz/yychainsaw/portfolio/content/api backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/content/application/WorkspaceValidator.java backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/content/application/WorkspaceValidatorTest.java
git commit -m "feat(content): define typed workspace contracts"
```

### Task 4: Aggregate persistence and authenticated workspace APIs

**Files:**
- Create: `backend-parent/portfolio-pojo/src/main/java/xyz/yychainsaw/portfolio/content/api/UpdateSiteWorkspaceRequest.java`
- Create: `backend-parent/portfolio-pojo/src/main/java/xyz/yychainsaw/portfolio/content/api/UpdateProjectWorkspaceRequest.java`
- Create: `backend-parent/portfolio-pojo/src/main/java/xyz/yychainsaw/portfolio/content/api/TaxonomyWorkspaceDto.java`
- Create: `backend-parent/portfolio-pojo/src/main/java/xyz/yychainsaw/portfolio/content/api/UpdateTaxonomyRequest.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/content/persistence/SiteWorkspaceRepository.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/content/persistence/ProjectWorkspaceRepository.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/content/persistence/TaxonomyRepository.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/content/persistence/mybatis/SiteWorkspaceMapper.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/content/persistence/mybatis/ProjectWorkspaceMapper.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/content/persistence/mybatis/TaxonomyMapper.java`
- Create: `backend-parent/portfolio-server/src/main/resources/mapper/content/SiteWorkspaceMapper.xml`
- Create: `backend-parent/portfolio-server/src/main/resources/mapper/content/ProjectWorkspaceMapper.xml`
- Create: `backend-parent/portfolio-server/src/main/resources/mapper/content/TaxonomyMapper.xml`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/content/application/ContentWorkspaceService.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/content/web/AdminContentController.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/content/persistence/ContentWorkspaceRepositoryTest.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/content/web/AdminContentControllerTest.java`

**Interfaces:**
- Consumes: Task 3 DTOs and validator, plan 01 `CurrentAdminProvider`, Spring Security admin rules, and common `DomainException` mapping.
- Produces: `SiteWorkspaceRepository`, `ProjectWorkspaceRepository`, `TaxonomyRepository`, `ContentWorkspaceService`, and `/api/admin/site/workspace` plus `/api/admin/projects` workspace endpoints.

Use these exact repository contracts:

```java
package xyz.yychainsaw.portfolio.content.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import xyz.yychainsaw.portfolio.content.api.ProjectWorkspaceDto;
import xyz.yychainsaw.portfolio.content.api.SiteWorkspaceDto;
import xyz.yychainsaw.portfolio.content.api.TaxonomyWorkspaceDto;

public interface SiteWorkspaceRepository {
    SiteWorkspaceDto require();
    void replace(SiteWorkspaceDto workspace, long expectedVersion);
}

public interface ProjectWorkspaceRepository {
    Optional<ProjectWorkspaceDto> find(UUID projectId);
    ProjectWorkspaceDto require(UUID projectId);
    ProjectWorkspaceDto requireForUpdate(UUID projectId);
    List<ProjectWorkspaceDto> findAll();
    void insert(ProjectWorkspaceDto workspace);
    void replace(ProjectWorkspaceDto workspace, long expectedVersion);
    void markPublicationDirty(java.util.Collection<UUID> projectIds);
    void markPublished(UUID projectId, long expectedVersion);
    void updateCatalogOrder(List<UUID> projectIdsInOrder);
}

public interface TaxonomyRepository {
    List<TaxonomyWorkspaceDto> findTags();
    List<TaxonomyWorkspaceDto> findSkills();
    void replaceImportTags(List<ProjectWorkspaceDto.TaxonomyRef> tags);
    void updateTag(UUID id, java.util.Map<xyz.yychainsaw.portfolio.content.api.LocaleCode, String> names,
                   long expectedVersion);
    void updateSkill(UUID id, java.util.Map<xyz.yychainsaw.portfolio.content.api.LocaleCode, String> names,
                     long expectedVersion);
}
```

- [ ] **Step 1: Write a failing repository round-trip and optimistic-lock test**

```java
// backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/content/persistence/ContentWorkspaceRepositoryTest.java
package xyz.yychainsaw.portfolio.content.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.content.api.SiteWorkspaceDto;
import xyz.yychainsaw.portfolio.content.support.WorkspaceFixtures;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
@ActiveProfiles("test")
class ContentWorkspaceRepositoryTest extends PostgresIntegrationTestBase {
    @Autowired SiteWorkspaceRepository sites;

    @Test
    void siteRoundTripsAndRejectsAStaleVersion() {
        SiteWorkspaceDto initial = WorkspaceFixtures.site(0L);
        sites.replace(initial, 0L);
        SiteWorkspaceDto saved = sites.require();
        assertThat(saved.email()).isEqualTo("portfolio@example.test");
        assertThat(saved.identity()).containsOnlyKeys(
                xyz.yychainsaw.portfolio.content.api.LocaleCode.ZH_CN,
                xyz.yychainsaw.portfolio.content.api.LocaleCode.EN);
        assertThatThrownBy(() -> sites.replace(WorkspaceFixtures.site(1L), 0L))
                .isInstanceOf(DomainException.class)
                .extracting(error -> ((DomainException) error).code())
                .isEqualTo("CONTENT_VERSION_CONFLICT");
    }
}
```

- [ ] **Step 2: Run the repository test and observe the missing bean**

Run from `backend-parent/`:

```powershell
.\mvnw.cmd -pl portfolio-server -am -Dtest=ContentWorkspaceRepositoryTest test
```

Expected: FAIL because `SiteWorkspaceRepository` has no implementation bean.

- [ ] **Step 3: Implement MyBatis row mappers and aggregate replacement**

Define mapper methods by table ownership. Each `replace` operation must first execute one optimistic root update and fail before touching children when its row count is zero:

```java
// backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/content/persistence/mybatis/SiteWorkspaceMapper.java
package xyz.yychainsaw.portfolio.content.persistence.mybatis;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SiteWorkspaceMapper {
    Map<String, Object> selectProfile(UUID siteId);
    List<Map<String, Object>> selectRows(@Param("tableKey") String tableKey, @Param("siteId") UUID siteId);
    int updateRoot(@Param("siteId") UUID siteId, @Param("monogram") String monogram,
                   @Param("email") String email, @Param("expectedVersion") long expectedVersion);
    void deleteOwnedRows(@Param("tableKey") String tableKey, @Param("siteId") UUID siteId);
    void insertRows(@Param("tableKey") String tableKey, @Param("rows") List<Map<String, Object>> rows);
}
```

`tableKey` is never supplied by a request. The repository maps a closed enum to XML statement branches. The exact enum is:

```java
enum SiteTable {
    PROFILE_TRANSLATION, SEO_TRANSLATION, ACCESSIBILITY_TRANSLATION,
    NAVIGATION, NAVIGATION_TRANSLATION, HERO, HERO_TRANSLATION, HERO_MEDIA,
    ABOUT_TRANSLATION, WORK_TRANSLATION, CONTACT_TRANSLATION, PRIVACY_TRANSLATION,
    SOCIAL_LINK, PROFILE_FACT, PROFILE_FACT_TRANSLATION, PROFILE_SKILL,
    PROFILE_SKILL_TRANSLATION, ROADMAP_HEADER_TRANSLATION, ROADMAP_STAGE,
    ROADMAP_STAGE_TRANSLATION, ROADMAP_OUTCOME, ROADMAP_OUTCOME_TRANSLATION,
    RESUME_DOCUMENT
}
```

In `SiteWorkspaceMapper.xml`, implement a `<choose>` branch for every enum value; no `${tableName}` substitution is allowed. The root CAS is exact:

```xml
<update id="updateRoot">
  update site_profile
  set monogram = #{monogram}, email = #{email}, version = version + 1, updated_at = now()
  where id = #{siteId} and version = #{expectedVersion}
</update>
```

Apply the same closed-enum pattern to project-owned tables with this exact enum:

```java
enum ProjectTable {
    PROJECT_TRANSLATION, PROJECT_TAG, PROJECT_SKILL, PROJECT_MEDIA,
    CONTENT_BLOCK, CONTENT_BLOCK_TRANSLATION, BLOCK_MEDIA,
    BLOCK_MARKDOWN_TRANSLATION, BLOCK_VIDEO, BLOCK_CODE,
    BLOCK_QUOTE_TRANSLATION, BLOCK_ACTION, BLOCK_METRIC, BLOCK_METRIC_TRANSLATION
}
```

`ProjectWorkspaceMapper.updateRoot` must use:

```sql
update project
set slug = #{slug}, number_label = #{number}, sort_order = #{sortOrder},
    featured = #{featured}, visible = #{visible}, publication_dirty = true,
    version = version + 1, updated_at = now()
where id = #{id} and version = #{expectedVersion}
```

`TaxonomyMapper` owns `tag`, `tag_translation`, `skill`, and `skill_translation`; changing a taxonomy calls `ProjectWorkspaceRepository.markPublicationDirty` for every linked project in the same transaction.

- [ ] **Step 4: Implement transactional repositories and conflict mapping**

```java
// central behavior in the SiteWorkspaceRepository implementation
@org.springframework.stereotype.Repository
final class MyBatisSiteWorkspaceRepository implements SiteWorkspaceRepository {
    private final SiteWorkspaceMapper mapper;
    private final SiteWorkspaceAssembler assembler;

    MyBatisSiteWorkspaceRepository(SiteWorkspaceMapper mapper, SiteWorkspaceAssembler assembler) {
        this.mapper = mapper;
        this.assembler = assembler;
    }

    @Override
    public SiteWorkspaceDto require() {
        return assembler.load(mapper, SiteWorkspaceDto.SITE_ID);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void replace(SiteWorkspaceDto workspace, long expectedVersion) {
        int changed = mapper.updateRoot(workspace.siteId(), workspace.monogram(), workspace.email(), expectedVersion);
        if (changed != 1) {
            throw new xyz.yychainsaw.portfolio.common.error.DomainException(
                    "CONTENT_VERSION_CONFLICT",
                    org.springframework.http.HttpStatus.CONFLICT,
                    java.util.Map.of("version", "workspace was changed by another request"));
        }
        assembler.replaceChildren(mapper, workspace);
    }
}
```

Create focused `SiteWorkspaceAssembler` and `ProjectWorkspaceAssembler` classes in the same persistence package. They must convert every Task 3 record field to/from the exact V4 table column; child lists are sorted by `sort_order`, and translation maps reject duplicate locale rows rather than overwriting them.

- [ ] **Step 5: Run the round-trip test**

Run from `backend-parent/`:

```powershell
.\mvnw.cmd -pl portfolio-server -am -Dtest=ContentWorkspaceRepositoryTest test
```

Expected: PASS with `1 test run, 0 failures`.

- [ ] **Step 6: Write failing authenticated API tests**

```java
// backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/content/web/AdminContentControllerTest.java
package xyz.yychainsaw.portfolio.content.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
@AutoConfigureMockMvc
class AdminContentControllerTest extends PostgresIntegrationTestBase {
    @Autowired MockMvc mvc;

    @Test
    void anonymousCannotReadWorkspace() throws Exception {
        mvc.perform(get("/api/admin/site/workspace")).andExpect(status().isUnauthorized());
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(username = "admin", roles = "ADMIN")
    void staleSiteUpdateReturns409() throws Exception {
        mvc.perform(put("/api/admin/site/workspace")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"expectedVersion\":999,\"workspace\":null}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONTENT_VERSION_CONFLICT"));
    }
}
```

- [ ] **Step 7: Implement service and controller endpoints**

```java
// backend-parent/portfolio-pojo/src/main/java/xyz/yychainsaw/portfolio/content/api/UpdateSiteWorkspaceRequest.java
package xyz.yychainsaw.portfolio.content.api;
public record UpdateSiteWorkspaceRequest(long expectedVersion, SiteWorkspaceDto workspace) {}

// backend-parent/portfolio-pojo/src/main/java/xyz/yychainsaw/portfolio/content/api/UpdateProjectWorkspaceRequest.java
package xyz.yychainsaw.portfolio.content.api;
public record UpdateProjectWorkspaceRequest(long expectedVersion, ProjectWorkspaceDto workspace) {}

// backend-parent/portfolio-pojo/src/main/java/xyz/yychainsaw/portfolio/content/api/TaxonomyWorkspaceDto.java
package xyz.yychainsaw.portfolio.content.api;
public record TaxonomyWorkspaceDto(java.util.UUID id, String normalizedKey, long version,
        java.util.Map<LocaleCode, String> names) {
    public TaxonomyWorkspaceDto { names = java.util.Map.copyOf(names); }
}

// backend-parent/portfolio-pojo/src/main/java/xyz/yychainsaw/portfolio/content/api/UpdateTaxonomyRequest.java
package xyz.yychainsaw.portfolio.content.api;
public record UpdateTaxonomyRequest(long expectedVersion,
        java.util.Map<LocaleCode, String> names) {
    public UpdateTaxonomyRequest { names = java.util.Map.copyOf(names); }
}
```

```java
// backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/content/application/ContentWorkspaceService.java
package xyz.yychainsaw.portfolio.content.application;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import xyz.yychainsaw.portfolio.content.api.ProjectWorkspaceDto;
import xyz.yychainsaw.portfolio.content.api.SiteWorkspaceDto;
import xyz.yychainsaw.portfolio.content.persistence.ProjectWorkspaceRepository;
import xyz.yychainsaw.portfolio.content.persistence.SiteWorkspaceRepository;

@Service
public final class ContentWorkspaceService {
    private final SiteWorkspaceRepository sites;
    private final ProjectWorkspaceRepository projects;
    private final WorkspaceValidator validator;

    public ContentWorkspaceService(SiteWorkspaceRepository sites, ProjectWorkspaceRepository projects,
                                   WorkspaceValidator validator) {
        this.sites = sites;
        this.projects = projects;
        this.validator = validator;
    }
    public SiteWorkspaceDto site() { return sites.require(); }
    public void updateSite(SiteWorkspaceDto site, long expectedVersion) {
        validator.validateSite(site);
        sites.replace(site, expectedVersion);
    }
    public List<ProjectWorkspaceDto> projects() { return projects.findAll(); }
    public ProjectWorkspaceDto project(UUID id) { return projects.require(id); }
    public void updateProject(ProjectWorkspaceDto project, long expectedVersion) {
        validator.validateProject(project);
        projects.replace(project, expectedVersion);
    }
}
```

The service skeleton above is subordinate to the cross-plan media-lifecycle contract: each mutation method is one caller-owned transaction, extracts the complete referenced asset/variant set from the request or an unlocked consistent read, sorts it deterministically, calls `MediaQueryService.requireReadyAsset` / `requireReadyVariant` for every reference, and only then takes SITE/project/catalog row locks and performs `replace` plus success audit. After taking content locks it must re-read and validate the expected version before writing. The importer, publish snapshot writer, and restore path use the same media-before-content order and transaction boundary. This prevents archive or cleanup from crossing a concurrent durable-reference insertion and prevents translation-vs-publish deadlocks; relying on the media foreign key alone is forbidden.

`AdminContentController` exposes exactly:

```text
GET  /api/admin/site/workspace
PUT  /api/admin/site/workspace
GET  /api/admin/projects
POST /api/admin/projects
GET  /api/admin/projects/{projectId}/workspace
PUT  /api/admin/projects/{projectId}/workspace
GET  /api/admin/tags
PUT  /api/admin/tags/{tagId}
GET  /api/admin/skills
PUT  /api/admin/skills/{skillId}
```

Each taxonomy PUT passes `UpdateTaxonomyRequest.names/expectedVersion` to the matching repository CAS; a zero-row update raises `CONTENT_VERSION_CONFLICT`. Each mutation requires CSRF through plan 01, obtains `CurrentAdminProvider.requireAdminId()`, and records `CONTENT_WORKSPACE_UPDATED` through `AuditService.record` only after the repository succeeds.

- [ ] **Step 8: Run API, repository, and security tests**

Run from `backend-parent/`:

```powershell
.\mvnw.cmd -pl portfolio-server -am -Dtest=ContentWorkspaceRepositoryTest,AdminContentControllerTest,ContentMediaReferenceConcurrencyTest test
```

Expected: PASS; anonymous GET is `401`, stale update is `409`, repository round-trip remains green, and real PostgreSQL archive-vs-reference plus cleanup-vs-reference races prove the reference transaction holds READY `FOR SHARE` locks through commit.

- [ ] **Step 9: Commit aggregate persistence and APIs**

```powershell
git add backend-parent/portfolio-pojo/src/main/java/xyz/yychainsaw/portfolio/content/api backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/content backend-parent/portfolio-server/src/main/resources/mapper/content backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/content
git commit -m "feat(content): add workspace persistence and APIs"
```

### Task 5: Typed Java dry-run and transactional one-time import

**Files:**
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/content/importer/PortfolioImportV1.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/content/importer/ImportIssue.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/content/importer/ImportReport.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/content/importer/PortfolioImportReader.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/content/importer/PortfolioImportValidator.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/content/importer/PortfolioImportMapper.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/content/importer/PortfolioImportService.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/content/importer/PortfolioImportCli.java`
- Modify: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/PortfolioApplication.java`
- Modify: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/cli/AdminCliRunner.java`
- Modify: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/PortfolioApplicationTest.java`
- Modify: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/auth/cli/AdminCliRunnerTest.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/content/importer/PortfolioImportServiceTest.java`
- Create: `backend-parent/portfolio-server/src/test/resources/import/portfolio-v1-valid.json`
- Create: `backend-parent/portfolio-server/src/test/resources/import/portfolio-v1-incomplete-translations.json`
- Create: `backend-parent/portfolio-server/src/test/resources/import/portfolio-v1-mismatched-media.json`
- Create: `backend-parent/portfolio-server/src/test/resources/import/assets/images/game-dev-hero.jpg`
- Create: `backend-parent/portfolio-server/src/test/resources/import/assets/images/ue-environment-study.jpg`
- Create: `backend-parent/portfolio-server/src/test/resources/import/assets/images/gameplay-prototype.jpg`
- Create: `backend-parent/portfolio-server/src/test/resources/import/assets/images/development-log.jpg`

**Interfaces:**
- Consumes: Task 1 canonical JSON, Task 4 repositories, plan 02 `MediaImportService`, Jackson, `TransactionTemplate`, and the fixed SITE ID.
- Produces: `PortfolioImportService.dryRun(Path, Path, String)` and `commit(Path, Path, String)`, deterministic `ImportReport`, and non-web CLI mode.

Use these exact report types:

```java
package xyz.yychainsaw.portfolio.content.importer;

public record ImportIssue(Severity severity, String path, String code, String message) {
    public enum Severity { STRUCTURE_ERROR, PUBLISH_WARNING }
}
```

```java
package xyz.yychainsaw.portfolio.content.importer;

import java.util.List;

public record ImportReport(
        String sha256,
        boolean committed,
        int projectCount,
        int mediaCount,
        int tagCount,
        List<ImportIssue> issues
) {
    public ImportReport { issues = List.copyOf(issues); }
    public boolean hasStructureErrors() {
        return issues.stream().anyMatch(issue -> issue.severity() == ImportIssue.Severity.STRUCTURE_ERROR);
    }
}
```

- [ ] **Step 1: Write failing tests for fixed warnings and all-or-nothing structure errors**

```java
// backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/content/importer/PortfolioImportServiceTest.java
package xyz.yychainsaw.portfolio.content.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
@ActiveProfiles("test")
class PortfolioImportServiceTest extends PostgresIntegrationTestBase {
    @Autowired PortfolioImportService service;
    @Autowired JdbcTemplate jdbc;

    @Test
    void dryRunReportsEveryFixedPlaceholderPathInStableOrder() throws Exception {
        Path input = Path.of("src/test/resources/import/portfolio-v1-valid.json");
        ImportReport report = service.dryRun(
                input, Path.of("src/test/resources/import/assets"), sha256(input));
        assertThat(report.committed()).isFalse();
        assertThat(report.issues()).extracting(ImportIssue::path).containsExactly(
                "identity.email",
                "portfolioContent.en.contact.emailLabel",
                "portfolioContent.en.hero.visualLabel",
                "portfolioContent.en.work.imageNotice",
                "portfolioContent.zh-CN.contact.emailLabel",
                "portfolioContent.zh-CN.hero.visualLabel",
                "portfolioContent.zh-CN.work.imageNotice");
    }

    @Test
    void presentBlankTranslationsAreStableWarningsRatherThanStructureErrors() throws Exception {
        Path input = Path.of("src/test/resources/import/portfolio-v1-incomplete-translations.json");
        ImportReport report = service.dryRun(
                input, Path.of("src/test/resources/import/assets"), sha256(input));
        assertThat(report.hasStructureErrors()).isFalse();
        assertThat(report.issues())
                .allMatch(issue -> issue.severity() == ImportIssue.Severity.PUBLISH_WARNING)
                .allMatch(issue -> issue.code().equals("IMPORT_TRANSLATION_INCOMPLETE"))
                .extracting(ImportIssue::path)
                .containsExactly(
                        "heroAsset.alt.en",
                        "portfolioContent.en.hero.headline",
                        "portfolioContent.zh-CN.projects[0].summary");
    }

    @Test
    void mismatchedProjectAndMediaIdsWriteNothing() throws Exception {
        int before = jdbc.queryForObject("select count(*) from project", Integer.class);
        Path input = Path.of("src/test/resources/import/portfolio-v1-mismatched-media.json");
        ImportReport report = service.commit(
                input, Path.of("src/test/resources/import/assets"), sha256(input));
        assertThat(report.hasStructureErrors()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from project", Integer.class)).isEqualTo(before);
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
```

`portfolio-v1-incomplete-translations.json` is copied from the valid fixture, replaces all seven fixed placeholders with final non-placeholder values, and changes exactly the three asserted leaf values to `""`. It retains both locale keys and every required property, proving that absence and blankness are different contracts.

Copy the four named image files from `frontend/public/images/` into the test asset tree byte-for-byte; the fixture JSON continues to use `/images/{filename}`, so the path-containment check exercises the same shape as production without depending on the frontend module's working directory.

- [ ] **Step 2: Run the import test and observe missing importer types**

Run from `backend-parent/`:

```powershell
.\mvnw.cmd -pl portfolio-server -am -Dtest=PortfolioImportServiceTest test
```

Expected: FAIL at compilation because `PortfolioImportService` and report types do not exist.

- [ ] **Step 3: Define the typed JSON model without maps for known fields**

`PortfolioImportV1` must be a record with these exact nested records; only locale containers are maps keyed by `LocaleCode`:

```java
package xyz.yychainsaw.portfolio.content.importer;

import java.net.URI;
import java.util.List;
import java.util.Map;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;

public record PortfolioImportV1(
        int schemaVersion,
        Identity identity,
        HeroAsset heroAsset,
        List<ProjectAsset> projectAssets,
        Map<LocaleCode, PortfolioCopy> portfolioContent
) {
    public record Identity(String monogram, String nameZh, String nameEn, String email) {}
    public record HeroAsset(String image, String objectPosition, String credit, URI sourceUrl,
                            Map<LocaleCode, String> alt) {}
    public record ProjectAsset(String id, String image, String layout, String objectPosition,
                               String credit, URI sourceUrl, Map<LocaleCode, String> alt) {}
    public record PortfolioCopy(Seo seo, A11y a11y, Nav nav, Hero hero, About about, Work work,
                                List<ProjectCopy> projects, Roadmap roadmap, Contact contact) {}
    public record Seo(String title, String description) {}
    public record A11y(String skip, String primaryNav, String mobileNav, String openMenu,
                        String closeMenu, String language, String backToTop, String projectTags) {}
    public record Nav(String about, String work, String roadmap, String contact) {}
    public record Hero(String eyebrow, String displayName, String secondaryName, String role,
                       String headline, String introduction, String availability,
                       String primaryCta, String secondaryCta, String visualLabel, String stageLabel) {}
    public record About(String label, String title, String statement, String focusLabel,
                        String focusTitle, String focusIntro, List<Fact> facts,
                        List<ProfileSkill> skills) {}
    public record Fact(String label, String value) {}
    public record ProfileSkill(String name, String status) {}
    public record Work(String label, String title, String introduction, String imageNotice,
                       String openSlotLabel, String openSlotTitle, String openSlotText,
                       String openSlotMeta) {}
    public record ProjectCopy(String id, String number, String status, String eyebrow,
                              String title, String summary, List<String> tags) {}
    public record Roadmap(String label, String title, String introduction, List<RoadmapStage> stages) {}
    public record RoadmapStage(String id, String number, String period, String title,
                               String summary, List<String> outcomes) {}
    public record Contact(String label, String title, String introduction, String emailLabel,
                          String email, String workCta, String roadmapCta, String footerNote) {}
}
```

- [ ] **Step 4: Implement byte-level checksum and strict typed reading**

```java
// backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/content/importer/PortfolioImportReader.java
package xyz.yychainsaw.portfolio.content.importer;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class PortfolioImportReader {
    private final ObjectMapper mapper;

    public PortfolioImportReader(ObjectMapper base) {
        this.mapper = base.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public ReadResult read(Path input) {
        try {
            byte[] bytes = Files.readAllBytes(input);
            PortfolioImportV1 payload = mapper.readValue(bytes, PortfolioImportV1.class);
            return new ReadResult(payload, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch (IOException | NoSuchAlgorithmException error) {
            throw new IllegalArgumentException("cannot read canonical portfolio import", error);
        }
    }

    public record ReadResult(PortfolioImportV1 payload, String sha256) {}
}
```

- [ ] **Step 5: Implement deterministic structural validation and fixed placeholder paths**

`PortfolioImportValidator.validate` returns issues sorted first by severity and then by path. Implement these exact placeholder predicates, not keyword scanning:

```java
private static final Map<String, String> FIXED_PLACEHOLDERS = Map.of(
        "identity.email", "your-email@example.com",
        "portfolioContent.zh-CN.hero.visualLabel", "视觉概念图 / 之后替换为本人 UE 截图",
        "portfolioContent.en.hero.visualLabel", "Visual concept image / replace with my own UE capture",
        "portfolioContent.zh-CN.work.imageNotice", "概念占位图，之后替换为本人 UE 截图",
        "portfolioContent.en.work.imageNotice", "Concept placeholder - to be replaced with my own UE capture",
        "portfolioContent.zh-CN.contact.emailLabel", "联系邮箱（待替换）",
        "portfolioContent.en.contact.emailLabel", "Email placeholder"
);
```

Structural validation must add these codes and paths:

| Code | Path | Condition |
|---|---|---|
| `IMPORT_SCHEMA_VERSION_UNSUPPORTED` | `schemaVersion` | value is not `1` |
| `IMPORT_CHECKSUM_MISMATCH` | `$` | expected SHA-256 differs from file bytes |
| `IMPORT_REQUIRED_FIELD_MISSING` | concrete object path | a required locale key or named property is absent/null rather than present as a string (possibly `""` for a translation) |
| `IMPORT_LOCALE_SET_INVALID` | `portfolioContent` or any localized alt | keys are not exactly `zh-CN` and `en` |
| `IMPORT_DUPLICATE_ID` | concrete array path | duplicate project, stage, or generated stable ID |
| `IMPORT_PROJECT_MEDIA_MISMATCH` | `projectAssets` | project IDs and asset IDs are not identical sets |
| `IMPORT_PROJECT_LOCALE_MISMATCH` | `portfolioContent.*.projects` | project IDs/order differ between locales |
| `IMPORT_TAG_LOCALE_MISMATCH` | project tags path | two locale arrays differ in length or normalized positional key |
| `IMPORT_ROADMAP_LOCALE_MISMATCH` | roadmap path | stage IDs/order or outcome counts differ |
| `IMPORT_ASSET_PATH_INVALID` | media image path | value is not exactly a `/images/{single-file-name}` public path, or stripping its one leading slash and resolving it beneath `assetRoot` traverses through a symlink/outside the real root or reaches an unreadable file |

The two publish-warning codes are exact: `PLACEHOLDER_CONTENT_PRESENT` for the seven fixed path/value pairs and `IMPORT_TRANSLATION_INCOMPLETE` for every present string represented by schema `$defs.translationString` whose `isBlank()` is true. Visit those leaves explicitly in DTO declaration/array order, then sort all issues by severity enum order, path, and code; do not use reflection or stop after the first blank. A blank translated value never becomes a structure error and may be stored in a draft, but `PublicationValidator` still rejects it before publication.

Normalization for a nonblank English tag key is `Normalizer.normalize(enName, NFKC).strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9+.#]+", "-").replaceAll("(^-|-$)", "")`. If the English translation is blank, import the draft with deterministic provisional key `untranslated-` plus the lowercase UUID from namespace `tag-missing:{projectId}:{tagIndex}` and emit the translation warning; never merge two provisional keys. A nonblank English value whose normalization is empty remains `IMPORT_TAG_LOCALE_MISMATCH` because it cannot be a stable taxonomy key.

- [ ] **Step 6: Implement mapping and transactional commit**

`PortfolioImportMapper` must generate stable UUIDs with `UUID.nameUUIDFromBytes((namespace + ":" + externalKey).getBytes(UTF_8))`, using namespaces `project`, `tag`, `profile-fact`, `profile-skill`, `roadmap-stage`, and `roadmap-outcome`. The English project ID is the shared slug. Imported projects have empty detail blocks, `featured=false`, `visible=true`, and `publicationDirty=true`.

The mapper returns this exact aggregate; tags are persisted before projects so every `project_tag` foreign key resolves:

```java
public record MappedImport(
        xyz.yychainsaw.portfolio.content.api.SiteWorkspaceDto site,
        java.util.List<xyz.yychainsaw.portfolio.content.api.ProjectWorkspaceDto> projects,
        java.util.List<xyz.yychainsaw.portfolio.content.api.ProjectWorkspaceDto.TaxonomyRef> tags,
        int mediaCount
) {
    public MappedImport {
        projects = java.util.List.copyOf(projects);
        tags = java.util.List.copyOf(tags);
    }
    public int tagCount() { return tags.size(); }
}
```

```java
// central flow in PortfolioImportService
@org.springframework.stereotype.Service
public final class PortfolioImportService {
    private final PortfolioImportReader reader;
    private final PortfolioImportValidator validator;
    private final PortfolioImportMapper mapper;
    private final xyz.yychainsaw.portfolio.media.application.MediaImportService media;
    private final xyz.yychainsaw.portfolio.content.persistence.SiteWorkspaceRepository sites;
    private final xyz.yychainsaw.portfolio.content.persistence.ProjectWorkspaceRepository projects;
    private final xyz.yychainsaw.portfolio.content.persistence.TaxonomyRepository taxonomies;
    private final org.springframework.transaction.support.TransactionTemplate transactions;

    public PortfolioImportService(
            PortfolioImportReader reader,
            PortfolioImportValidator validator,
            PortfolioImportMapper mapper,
            xyz.yychainsaw.portfolio.media.application.MediaImportService media,
            xyz.yychainsaw.portfolio.content.persistence.SiteWorkspaceRepository sites,
            xyz.yychainsaw.portfolio.content.persistence.ProjectWorkspaceRepository projects,
            xyz.yychainsaw.portfolio.content.persistence.TaxonomyRepository taxonomies,
            org.springframework.transaction.support.TransactionTemplate transactions) {
        this.reader = reader;
        this.validator = validator;
        this.mapper = mapper;
        this.media = media;
        this.sites = sites;
        this.projects = projects;
        this.taxonomies = taxonomies;
        this.transactions = transactions;
    }

    public ImportReport dryRun(java.nio.file.Path input, java.nio.file.Path assetRoot, String expectedSha256) {
        PortfolioImportReader.ReadResult read = reader.read(input);
        return validator.report(read.payload(), read.sha256(), expectedSha256, assetRoot, false);
    }

    public ImportReport commit(java.nio.file.Path input, java.nio.file.Path assetRoot, String expectedSha256) {
        PortfolioImportReader.ReadResult read = reader.read(input);
        ImportReport report = validator.report(read.payload(), read.sha256(), expectedSha256, assetRoot, false);
        if (report.hasStructureErrors()) return report;
        return transactions.execute(status -> {
            if (sites.require().version() != 0 || !projects.findAll().isEmpty()) {
                throw new xyz.yychainsaw.portfolio.common.error.DomainException(
                        "IMPORT_ALREADY_COMPLETED", org.springframework.http.HttpStatus.CONFLICT,
                        java.util.Map.of("import", "workspace is not empty"));
            }
            PortfolioImportMapper.MappedImport mapped = mapper.map(read.payload(), assetRoot, media);
            sites.replace(mapped.site(), 0L);
            taxonomies.replaceImportTags(mapped.tags());
            mapped.projects().forEach(projects::insert);
            PortfolioImportMapper.assertEquivalent(read.payload(), sites.require(), projects.findAll());
            return new ImportReport(read.sha256(), true, mapped.projects().size(),
                    mapped.mediaCount(), mapped.tagCount(), report.issues());
        });
    }
}
```

Any exception from media import, taxonomy/project insertion, or `assertEquivalent` must escape the transaction so all database rows roll back. `dryRun` calls only the reader and validator; it must never call `mapper.map`, `MediaImportService`, a repository mutation, or `TransactionTemplate`.

- [ ] **Step 7: Add the non-web CLI adapter**

`PortfolioImportCli` is enabled only when `portfolio.cli.command=import`. Read these required properties: `portfolio.import.input`, `portfolio.import.asset-root`, `portfolio.import.sha256`, and `portfolio.import.commit`. Print exactly one JSON `ImportReport` to stdout and exit with code `2` for structure errors, `3` for a domain conflict, and `0` otherwise. Do not print content fields or absolute asset paths.

Extend `PortfolioApplication`'s pre-context command parser at the same time: `import` becomes an explicit non-web command, and only its command token plus the exact four `portfolio.import.*` options above are accepted (each exactly once, no positional or arbitrary Spring options). `PortfolioApplication` itself selects `WebApplicationType.NONE`, so the invocation must not pass `spring.main.web-application-type`. `AdminCliRunner` must return without handling the already preflight-approved `import` command; `PortfolioImportCli` is its sole owner. Tests must prove unknown/duplicate/missing/extra options fail before context creation, maintenance commands retain their old one-option boundary, and the real import NONE context contains the plan 02 `MediaImportService` stack.

Dry-run command from repository root after Task 1 generates the artifact:

```powershell
Push-Location frontend
$export = npm run --silent export:portfolio | Select-Object -Last 1 | ConvertFrom-Json
Pop-Location
java -jar backend-parent/portfolio-server/target/portfolio-server.jar --portfolio.cli.command=import --portfolio.import.input=runtime/import/portfolio-v1.json --portfolio.import.asset-root=frontend/public --portfolio.import.sha256=$($export.sha256) --portfolio.import.commit=false
```

Expected: exit `0`; JSON has `committed:false`, `projectCount:3`, and seven fixed publish warnings.

- [ ] **Step 8: Run import tests, exporter tests, and the real dry-run**

Run from `backend-parent/`:

```powershell
.\mvnw.cmd -pl portfolio-server -am -Dtest=PortfolioImportServiceTest test
```

Run from `frontend/`:

```powershell
npm run test:export
npm run export:portfolio
```

Expected: all commands exit `0`; importer tests report `3 tests run, 0 failures`; generated JSON passes both Node schema validation and Java typed deserialization.

- [ ] **Step 9: Commit the importer**

```powershell
git add backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/content/importer backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/content/importer backend-parent/portfolio-server/src/test/resources/import
git commit -m "feat(content): add transactional portfolio importer"
```

### Task 6: Flyway V5 immutable revisions and publication pointers

**Files:**
- Create: `backend-parent/portfolio-server/src/main/resources/db/migration/V5__publishing.sql`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/publishing/persistence/PublishingMigrationTest.java`

**Interfaces:**
- Consumes: V4 project/workspace IDs, plan 02 `media_asset` and `(asset_id, variant_name)` media variant key, and the runtime/migration database roles from plan 01.
- Produces: `content_revision`, `publication`, `slug_redirect`, `revision_media_reference`, fixed singleton publication rows, indexes, and immutability triggers.

- [ ] **Step 1: Write failing singleton and immutability tests**

```java
// backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/publishing/persistence/PublishingMigrationTest.java
package xyz.yychainsaw.portfolio.publishing.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
@ActiveProfiles("test")
class PublishingMigrationTest extends PostgresIntegrationTestBase {
    @Autowired JdbcTemplate jdbc;

    @Test
    void createsFixedSiteAndCatalogPublicationRows() {
        assertThat(jdbc.queryForList(
                "select aggregate_type, aggregate_id::text, version from publication order by aggregate_type"))
                .extracting(row -> row.get("aggregate_type") + ":" + row.get("aggregate_id") + ":" + row.get("version"))
                .containsExactly(
                        "PROJECT_CATALOG:00000000-0000-0000-0000-000000000002:0",
                        "SITE:00000000-0000-0000-0000-000000000001:0");
    }

    @Test
    void installsImmutabilityTriggersOnBothHistoryTables() {
        List<String> triggers = jdbc.queryForList(
                "select tgname from pg_trigger where not tgisinternal " +
                "and tgname in ('content_revision_immutable', 'revision_media_reference_immutable') " +
                "order by tgname",
                String.class);
        assertThat(triggers).containsExactly(
                "content_revision_immutable", "revision_media_reference_immutable");
    }
}
```

- [ ] **Step 2: Run the migration test and observe missing V5 tables**

Run from `backend-parent/`:

```powershell
.\mvnw.cmd -pl portfolio-server -am -Dtest=PublishingMigrationTest test
```

Expected: FAIL because `publication` and `content_revision` do not exist.

- [ ] **Step 3: Create V5 with fixed singleton rows and CAS-ready versions**

```sql
create table content_revision (
    id uuid primary key,
    aggregate_type varchar(24) not null check (aggregate_type in ('SITE', 'PROJECT', 'PROJECT_CATALOG')),
    aggregate_id uuid not null,
    version bigint not null check (version > 0),
    snapshot_schema_version integer not null check (snapshot_schema_version > 0),
    snapshot jsonb not null,
    checksum varchar(64) not null check (checksum ~ '^[0-9a-f]{64}$'),
    published_by uuid not null references admin_user(id),
    published_at timestamptz not null default now(),
    unique (aggregate_type, aggregate_id, version)
);

create table publication (
    aggregate_type varchar(24) not null check (aggregate_type in ('SITE', 'PROJECT', 'PROJECT_CATALOG')),
    aggregate_id uuid not null,
    status varchar(16) not null check (status in ('PUBLISHED', 'ARCHIVED')),
    current_revision_id uuid references content_revision(id),
    current_slug varchar(120),
    version bigint not null default 0 check (version >= 0),
    published_at timestamptz,
    primary key (aggregate_type, aggregate_id),
    check ((status = 'ARCHIVED') or current_revision_id is not null),
    check (current_slug is null or current_slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$')
);

insert into publication(aggregate_type, aggregate_id, status, version)
values
    ('SITE', '00000000-0000-0000-0000-000000000001', 'ARCHIVED', 0),
    ('PROJECT_CATALOG', '00000000-0000-0000-0000-000000000002', 'ARCHIVED', 0);

create table slug_redirect (
    old_slug varchar(120) primary key check (old_slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    project_id uuid not null references project(id) on delete cascade,
    new_slug varchar(120) not null check (new_slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    created_at timestamptz not null default now(),
    check (old_slug <> new_slug)
);

create table revision_media_reference (
    revision_id uuid not null references content_revision(id) on delete restrict,
    asset_id uuid not null references media_asset(id) on delete restrict,
    variant_name varchar(64) not null,
    usage varchar(32) not null,
    primary key (revision_id, asset_id, variant_name, usage),
    foreign key (asset_id, variant_name) references media_variant(asset_id, variant_name) on delete restrict
);

create index idx_content_revision_aggregate
on content_revision(aggregate_type, aggregate_id, version desc);

create unique index uq_publication_current_slug
on publication(current_slug)
where status = 'PUBLISHED' and current_slug is not null;

create index idx_revision_media_asset
on revision_media_reference(asset_id, variant_name);

create or replace function reject_published_history_mutation()
returns trigger language plpgsql as $$
begin
    raise exception 'published revisions are immutable' using errcode = '55000';
    return null;
end;
$$;

create trigger content_revision_immutable
before update or delete on content_revision
for each row execute function reject_published_history_mutation();

create trigger revision_media_reference_immutable
before update or delete on revision_media_reference
for each row execute function reject_published_history_mutation();
```

After the tables, grant the runtime role from plan 01 `SELECT, INSERT` on `content_revision` and `revision_media_reference`, `SELECT, INSERT, UPDATE` on `publication`, and `SELECT, INSERT` on `slug_redirect`. Do not grant runtime `UPDATE` or `DELETE` on history tables or `DELETE` on redirects. The online application has no trigger-bypass flag; a separately reviewed offline history-purge run must use the plan 01 migration-owner credential to disable/re-enable these two triggers around explicit revision IDs after backup and an audit tombstone.

- [ ] **Step 4: Run V5, full migration, and empty-database tests**

Run from `backend-parent/`:

```powershell
.\mvnw.cmd -pl portfolio-server -am -Dtest=PublishingMigrationTest test
.\mvnw.cmd -pl portfolio-server -am -Dtest=*MigrationTest test
```

Expected: PASS; V1 through V5 apply from empty PostgreSQL, the two singleton rows have version `0`, and both immutable-history triggers are installed. Task 8 verifies their runtime SQL-state behavior after creating a real revision through the application service.

- [ ] **Step 5: Commit V5**

```powershell
git add backend-parent/portfolio-server/src/main/resources/db/migration/V5__publishing.sql backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/publishing/persistence/PublishingMigrationTest.java
git commit -m "feat(publishing): add immutable publication schema"
```

### Task 7: Versioned snapshot records, canonical codec, and mapper registry

**Files:**
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing/snapshot/AggregateType.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing/snapshot/EncodedSnapshot.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing/snapshot/SnapshotCodec.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing/snapshot/v1/LocaleV1.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing/snapshot/v1/PublishedMediaV1.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing/snapshot/v1/SiteContentV1.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing/snapshot/v1/PublishedBlockV1.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing/snapshot/v1/SiteSnapshotV1.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing/snapshot/v1/ProjectSnapshotV1.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing/snapshot/v1/ProjectCatalogSnapshotV1.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing/snapshot/SiteSnapshotMapper.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing/snapshot/ProjectSnapshotMapper.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing/snapshot/ProjectCatalogSnapshotMapper.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing/snapshot/v1/SiteSnapshotMapperV1.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing/snapshot/v1/ProjectSnapshotMapperV1.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing/snapshot/v1/ProjectCatalogSnapshotMapperV1.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing/snapshot/SnapshotMapperRegistry.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing/snapshot/PublishingConfiguration.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/publishing/snapshot/SnapshotMapperRoundTripTest.java`

**Interfaces:**
- Consumes: Task 3 workspace DTOs, Task 4 repositories, and plan 02 `MediaQueryService`.
- Produces: canonical schema-version-1 JSON/checksum, complete media-reference sets, typed restore mapping, and a registry that rejects unknown versions.

Use these exact roots:

```java
package xyz.yychainsaw.portfolio.publishing.snapshot;
public enum AggregateType { SITE, PROJECT, PROJECT_CATALOG }
```

```java
package xyz.yychainsaw.portfolio.publishing.snapshot;
public record EncodedSnapshot(int schemaVersion, String json, String sha256) {}
```

`PublishedMediaV1` stores no signed URL:

```java
// LocaleV1.java
package xyz.yychainsaw.portfolio.publishing.snapshot.v1;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum LocaleV1 {
    ZH_CN("zh-CN"), EN("en");
    private final String value;
    LocaleV1(String value) { this.value = value; }
    @JsonValue public String value() { return value; }
    @JsonCreator
    public static LocaleV1 from(String value) {
        for (LocaleV1 locale : values()) if (locale.value.equals(value)) return locale;
        throw new IllegalArgumentException("unsupported snapshot locale: " + value);
    }
}

// PublishedMediaV1.java
package xyz.yychainsaw.portfolio.publishing.snapshot.v1;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record PublishedMediaV1(
        UUID assetId,
        String contentType,
        long contentLength,
        String sha256,
        Map<LocaleV1, MediaCopy> copy,
        List<Variant> variants
) {
    public PublishedMediaV1 { copy = Map.copyOf(copy); variants = List.copyOf(variants); }
    public record MediaCopy(String alt, String caption, String credit, String sourceUrl) {}
    public record Variant(String name, int width, int height, long bytes, String sha256) {}
}
```

- [ ] **Step 1: Write a failing canonical and round-trip test**

```java
// backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/publishing/snapshot/SnapshotMapperRoundTripTest.java
package xyz.yychainsaw.portfolio.publishing.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import xyz.yychainsaw.portfolio.content.api.ProjectWorkspaceDto;
import xyz.yychainsaw.portfolio.content.support.WorkspaceFixtures;
import xyz.yychainsaw.portfolio.media.application.MediaQueryService;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.ProjectSnapshotMapperV1;

class SnapshotMapperRoundTripTest {
    private final SnapshotCodec codec =
            new SnapshotCodec(new ObjectMapper().findAndRegisterModules());
    private final ProjectSnapshotMapper mapper =
            new ProjectSnapshotMapperV1(mock(MediaQueryService.class));

    @Test
    void projectSnapshotIsCanonicalAndRestoresTheEditableAggregate() {
        ProjectWorkspaceDto workspace = WorkspaceFixtures.projectWithoutMedia();
        var snapshot = mapper.toSnapshot(workspace);
        EncodedSnapshot first = codec.encode(snapshot);
        EncodedSnapshot second = codec.encode(snapshot);
        assertThat(first.json()).isEqualTo(second.json());
        assertThat(first.sha256()).matches("[0-9a-f]{64}");
        assertThat(mapper.restore(codec.decode(first.json(),
                xyz.yychainsaw.portfolio.publishing.snapshot.v1.ProjectSnapshotV1.class),
                workspace.version()))
                .usingRecursiveComparison()
                .ignoringFields("version", "publicationDirty")
                .isEqualTo(workspace);
    }

    @Test
    void localizedMediaAttributionSurvivesCanonicalCodecRoundTrip() {
        var media = new xyz.yychainsaw.portfolio.publishing.snapshot.v1.PublishedMediaV1(
                java.util.UUID.randomUUID(), "image/webp", 2048L, "a".repeat(64),
                java.util.Map.of(
                        xyz.yychainsaw.portfolio.publishing.snapshot.v1.LocaleV1.EN,
                        new xyz.yychainsaw.portfolio.publishing.snapshot.v1.PublishedMediaV1.MediaCopy(
                                "Scene", "In-engine capture", "YY Chainsaw",
                                "https://example.com/source")),
                java.util.List.of(new xyz.yychainsaw.portfolio.publishing.snapshot.v1.PublishedMediaV1.Variant(
                        "1280", 1280, 720, 1024L, "b".repeat(64))));
        var decoded = codec.decode(codec.encode(media).json(),
                xyz.yychainsaw.portfolio.publishing.snapshot.v1.PublishedMediaV1.class);
        assertThat(decoded).usingRecursiveComparison().isEqualTo(media);
        assertThat(decoded.copy().get(
                xyz.yychainsaw.portfolio.publishing.snapshot.v1.LocaleV1.EN).sourceUrl())
                .isEqualTo("https://example.com/source");
    }
}
```

Add a mapper case using `WorkspaceFixtures.projectWithImageGalleryAndVideoCover()` and a stubbed plan 02 `MediaAssetDescriptor` whose English/Chinese `MediaCopyDescriptor` values have distinct `sourceUrl` values; assert the mapped `PublishedMediaV1.copy` preserves each locale's exact alt/caption/credit/sourceUrl before the codec assertion. This is not satisfied by copying the project-level `project_media.source_url` into every block asset.

- [ ] **Step 2: Run the test and observe missing snapshot classes**

Run from `backend-parent/`:

```powershell
.\mvnw.cmd -pl portfolio-server -am -Dtest=SnapshotMapperRoundTripTest test
```

Expected: FAIL at compilation because `SnapshotCodec` and mapper classes do not exist.

- [ ] **Step 3: Define immutable V1 roots**

```java
// SiteContentV1.java
package xyz.yychainsaw.portfolio.publishing.snapshot.v1;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record SiteContentV1(
        String monogram, String email,
        Map<LocaleV1, IdentityCopyV1> identity,
        Map<LocaleV1, SeoCopyV1> seo,
        Map<LocaleV1, AccessibilityCopyV1> accessibility,
        List<NavigationItemV1> navigation,
        HeroV1 hero,
        Map<LocaleV1, AboutCopyV1> about,
        List<ProfileFactV1> facts,
        List<ProfileSkillV1> profileSkills,
        Map<LocaleV1, WorkCopyV1> work,
        RoadmapV1 roadmap,
        Map<LocaleV1, ContactCopyV1> contact,
        Map<LocaleV1, PrivacyCopyV1> privacy,
        List<SocialLinkV1> socialLinks,
        List<ResumeDocumentV1> resumes
) {
    public SiteContentV1 {
        identity = Map.copyOf(identity); seo = Map.copyOf(seo);
        accessibility = Map.copyOf(accessibility); navigation = List.copyOf(navigation);
        about = Map.copyOf(about); facts = List.copyOf(facts);
        profileSkills = List.copyOf(profileSkills); work = Map.copyOf(work);
        contact = Map.copyOf(contact); privacy = Map.copyOf(privacy);
        socialLinks = List.copyOf(socialLinks); resumes = List.copyOf(resumes);
    }
    public record IdentityCopyV1(String displayName, String secondaryName) {}
    public record SeoCopyV1(String title, String description) {}
    public record AccessibilityCopyV1(String skip, String primaryNav, String mobileNav,
            String openMenu, String closeMenu, String language, String backToTop,
            String projectTags) {}
    public record NavigationItemV1(UUID id, String target, int sortOrder, boolean visible,
            Map<LocaleV1, String> labels) {
        public NavigationItemV1 { labels = Map.copyOf(labels); }
    }
    public record HeroV1(UUID id, UUID mediaAssetId, String objectPosition, String credit,
            URI sourceUrl, Map<LocaleV1, HeroCopyV1> copy) {
        public HeroV1 { copy = Map.copyOf(copy); }
    }
    public record HeroCopyV1(String eyebrow, String displayName, String secondaryName,
            String role, String headline, String introduction, String availability,
            String primaryCta, String secondaryCta, String visualLabel, String stageLabel) {}
    public record AboutCopyV1(String label, String title, String statement, String focusLabel,
            String focusTitle, String focusIntro) {}
    public record ProfileFactV1(UUID id, String externalKey, int sortOrder,
            Map<LocaleV1, LabelValueCopyV1> copy) {
        public ProfileFactV1 { copy = Map.copyOf(copy); }
    }
    public record ProfileSkillV1(UUID id, String externalKey, int sortOrder,
            Map<LocaleV1, SkillStatusCopyV1> copy) {
        public ProfileSkillV1 { copy = Map.copyOf(copy); }
    }
    public record LabelValueCopyV1(String label, String value) {}
    public record SkillStatusCopyV1(String name, String status) {}
    public record WorkCopyV1(String label, String title, String introduction,
            String imageNotice, String openSlotLabel, String openSlotTitle,
            String openSlotText, String openSlotMeta) {}
    public record RoadmapV1(Map<LocaleV1, RoadmapHeaderCopyV1> header,
            List<RoadmapStageV1> stages) {
        public RoadmapV1 { header = Map.copyOf(header); stages = List.copyOf(stages); }
    }
    public record RoadmapHeaderCopyV1(String label, String title, String introduction) {}
    public record RoadmapStageV1(UUID id, String externalKey, String number, int sortOrder,
            boolean visible, Map<LocaleV1, RoadmapStageCopyV1> copy,
            List<RoadmapOutcomeV1> outcomes) {
        public RoadmapStageV1 { copy = Map.copyOf(copy); outcomes = List.copyOf(outcomes); }
    }
    public record RoadmapStageCopyV1(String period, String title, String summary) {}
    public record RoadmapOutcomeV1(UUID id, int sortOrder, Map<LocaleV1, String> text) {
        public RoadmapOutcomeV1 { text = Map.copyOf(text); }
    }
    public record ContactCopyV1(String label, String title, String introduction,
            String emailLabel, String workCta, String roadmapCta, String footerNote) {}
    public record PrivacyCopyV1(String title, String bodyMarkdown) {}
    public record SocialLinkV1(UUID id, String platform, URI url, int sortOrder, boolean visible) {}
    public record ResumeDocumentV1(UUID id, LocaleV1 locale, UUID mediaAssetId,
            String versionLabel, boolean current, LocalDate documentDate) {}
}

// SiteSnapshotV1.java
package xyz.yychainsaw.portfolio.publishing.snapshot.v1;

import java.util.List;
import java.util.UUID;

public record SiteSnapshotV1(int schemaVersion, UUID siteId, SiteContentV1 content,
                             List<PublishedMediaV1> media) {
    public SiteSnapshotV1 {
        if (schemaVersion != 1) throw new IllegalArgumentException("SiteSnapshotV1 requires schema 1");
        media = List.copyOf(media);
    }
}
```

```java
// PublishedBlockV1.java
package xyz.yychainsaw.portfolio.publishing.snapshot.v1;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record PublishedBlockV1(UUID id, int sortOrder, boolean visible, WidthV1 width,
        AlignmentV1 alignment, EmphasisV1 emphasis, int columns, PayloadV1 payload) {
    public enum WidthV1 { NARROW, STANDARD, WIDE, FULL }
    public enum AlignmentV1 { LEFT, CENTER, RIGHT }
    public enum EmphasisV1 { NONE, SOFT, STRONG }
    public record BlockCopyV1(String title, String description) {}
    public record ActionCopyV1(String label, String description) {}
    public record QuoteCopyV1(String quote, String source) {}
    public record MetricV1(UUID id, int sortOrder, BigDecimal numericValue,
            Map<LocaleV1, MetricCopyV1> copy) {
        public MetricV1 { copy = Map.copyOf(copy); }
    }
    public record MetricCopyV1(String label, String value, String suffix) {}

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = MarkdownPayloadV1.class, name = "MARKDOWN"),
            @JsonSubTypes.Type(value = ImagePayloadV1.class, name = "IMAGE"),
            @JsonSubTypes.Type(value = GalleryPayloadV1.class, name = "GALLERY"),
            @JsonSubTypes.Type(value = VideoPayloadV1.class, name = "VIDEO"),
            @JsonSubTypes.Type(value = CodePayloadV1.class, name = "CODE"),
            @JsonSubTypes.Type(value = QuotePayloadV1.class, name = "QUOTE"),
            @JsonSubTypes.Type(value = MetricsPayloadV1.class, name = "METRICS"),
            @JsonSubTypes.Type(value = DownloadPayloadV1.class, name = "DOWNLOAD"),
            @JsonSubTypes.Type(value = LinkPayloadV1.class, name = "LINK")
    })
    public sealed interface PayloadV1 permits MarkdownPayloadV1, ImagePayloadV1,
            GalleryPayloadV1, VideoPayloadV1, CodePayloadV1, QuotePayloadV1,
            MetricsPayloadV1, DownloadPayloadV1, LinkPayloadV1 {}
    public record MarkdownPayloadV1(Map<LocaleV1, String> markdown) implements PayloadV1 {
        public MarkdownPayloadV1 { markdown = Map.copyOf(markdown); }
    }
    public record ImagePayloadV1(UUID mediaAssetId) implements PayloadV1 {}
    public record GalleryPayloadV1(List<UUID> mediaAssetIds) implements PayloadV1 {
        public GalleryPayloadV1 { mediaAssetIds = List.copyOf(mediaAssetIds); }
    }
    public record VideoPayloadV1(String provider, URI url, UUID coverAssetId,
            Map<LocaleV1, BlockCopyV1> copy) implements PayloadV1 {
        public VideoPayloadV1 { copy = Map.copyOf(copy); }
    }
    public record CodePayloadV1(String code, String language, boolean showLineNumbers,
            Map<LocaleV1, BlockCopyV1> copy) implements PayloadV1 {
        public CodePayloadV1 { copy = Map.copyOf(copy); }
    }
    public record QuotePayloadV1(Map<LocaleV1, QuoteCopyV1> copy) implements PayloadV1 {
        public QuotePayloadV1 { copy = Map.copyOf(copy); }
    }
    public record MetricsPayloadV1(List<MetricV1> metrics) implements PayloadV1 {
        public MetricsPayloadV1 { metrics = List.copyOf(metrics); }
    }
    public record DownloadPayloadV1(UUID mediaAssetId, URI externalUrl,
            Map<LocaleV1, ActionCopyV1> copy) implements PayloadV1 {
        public DownloadPayloadV1 { copy = Map.copyOf(copy); }
    }
    public record LinkPayloadV1(URI url, boolean openNewTab,
            Map<LocaleV1, ActionCopyV1> copy) implements PayloadV1 {
        public LinkPayloadV1 { copy = Map.copyOf(copy); }
    }
}

// ProjectSnapshotV1.java
package xyz.yychainsaw.portfolio.publishing.snapshot.v1;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ProjectSnapshotV1(
        int schemaVersion, UUID projectId, String externalKey, String slug, String number,
        int sortOrder, boolean featured, Map<LocaleV1, ProjectCopyV1> translations,
        List<TaxonomyRefV1> tags, List<TaxonomyRefV1> skills,
        List<ProjectMediaV1> projectMedia, List<PublishedBlockV1> blocks,
        List<PublishedMediaV1> media) {
    public ProjectSnapshotV1 {
        if (schemaVersion != 1) throw new IllegalArgumentException("ProjectSnapshotV1 requires schema 1");
        translations = Map.copyOf(translations); tags = List.copyOf(tags);
        skills = List.copyOf(skills); projectMedia = List.copyOf(projectMedia);
        blocks = List.copyOf(blocks); media = List.copyOf(media);
    }
    public record ProjectCopyV1(String status, String eyebrow, String title, String summary,
                                String seoTitle, String seoDescription) {}
    public record TaxonomyRefV1(UUID id, String normalizedKey, int sortOrder,
                                Map<LocaleV1, String> names) {
        public TaxonomyRefV1 { names = Map.copyOf(names); }
    }
    public record ProjectMediaV1(UUID assetId, String usage, int sortOrder, String layout,
                                 String objectPosition, String credit, URI sourceUrl) {}
}
```

```java
// ProjectCatalogSnapshotV1.java
package xyz.yychainsaw.portfolio.publishing.snapshot.v1;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ProjectCatalogSnapshotV1(int schemaVersion, List<Card> projects) {
    public ProjectCatalogSnapshotV1 {
        if (schemaVersion != 1) throw new IllegalArgumentException("ProjectCatalogSnapshotV1 requires schema 1");
        projects = List.copyOf(projects);
    }
    public record Card(UUID projectId, String slug, String number, int sortOrder, boolean featured,
                       Map<LocaleV1, CardCopy> copy, PublishedMediaV1 cover) {
        public Card { copy = Map.copyOf(copy); }
    }
    public record CardCopy(String status, String eyebrow, String title, String summary,
                           List<String> tags) {
        public CardCopy { tags = List.copyOf(tags); }
    }
}
```

No class under `snapshot/v1` may import `xyz.yychainsaw.portfolio.content.api`; the V1 records deliberately duplicate workspace fields so later editable DTO changes cannot mutate old JSON semantics. Treat every class under `snapshot/v1` as an immutable wire contract after first production publish. A future field change creates `snapshot/v2`; it never edits these record components. Add an architecture assertion to `SnapshotMapperRoundTripTest` that reads every `snapshot/v1/*.java` source and fails if it contains `xyz.yychainsaw.portfolio.content.api`.

- [ ] **Step 4: Implement canonical JSON and SHA-256**

```java
package xyz.yychainsaw.portfolio.publishing.snapshot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class SnapshotCodec {
    private final ObjectMapper mapper;

    public SnapshotCodec(ObjectMapper base) {
        mapper = base.copy()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    public EncodedSnapshot encode(Object value) {
        try {
            String json = mapper.writeValueAsString(value);
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.getBytes(StandardCharsets.UTF_8)));
            return new EncodedSnapshot(1, json, sha);
        } catch (JsonProcessingException | NoSuchAlgorithmException error) {
            throw new IllegalStateException("cannot encode publication snapshot", error);
        }
    }

    public <T> T decode(String json, Class<T> type) {
        try { return mapper.readValue(json, type); }
        catch (JsonProcessingException error) {
            throw new IllegalArgumentException("invalid publication snapshot", error);
        }
    }
}
```

In tests, construct with `new ObjectMapper().findAndRegisterModules()`; production receives the Boot-managed `ObjectMapper` through this bean:

```java
package xyz.yychainsaw.portfolio.publishing.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PublishingConfiguration {
    @Bean
    SnapshotCodec snapshotCodec(ObjectMapper objectMapper) {
        return new SnapshotCodec(objectMapper);
    }
}
```

- [ ] **Step 5: Implement mapper and media-reference rules**

`SiteSnapshotMapper.toSnapshot` explicitly converts every Task 3 site field to the corresponding `SiteContentV1` field, excludes root/Hero workspace versions, resolves Hero/current resume assets through `MediaQueryService`, and requires every referenced variant to be READY. `ProjectSnapshotMapperV1` explicitly converts every workspace enum/nested record to its V1 counterpart, excludes workspace `version`, `visible`, and `publicationDirty`, embeds translated tag/skill display values, resolves all project/block/cover/download media, and returns a deduplicated list sorted by `(assetId, variantName)`. Every resolved `MediaCopyDescriptor` maps all four locale-specific fields (`alt`, `caption`, `credit`, `sourceUrl`) into `PublishedMediaV1.MediaCopy`; codec and round-trip assertions compare all four, so attribution cannot disappear during encode/decode/restore. `restore` performs the inverse conversion, sets `version` to the current workspace version supplied by the caller, sets Hero version from the current workspace Hero during site restore, and sets `publicationDirty=true`.

Use these exact mapper signatures:

```java
public interface SiteSnapshotMapper {
    SiteSnapshotV1 toSnapshot(SiteWorkspaceDto workspace);
    SiteWorkspaceDto restore(SiteSnapshotV1 snapshot, SiteWorkspaceDto currentWorkspace);
}

public interface ProjectSnapshotMapper {
    ProjectSnapshotV1 toSnapshot(ProjectWorkspaceDto workspace);
    ProjectWorkspaceDto restore(ProjectSnapshotV1 snapshot, long currentWorkspaceVersion);
}

public interface ProjectCatalogSnapshotMapper {
    ProjectCatalogSnapshotV1 fromCurrentProjects(java.util.List<ProjectSnapshotV1> projects);
}
```

`SnapshotMapperRegistry` exposes:

```java
public SiteWorkspaceDto restoreSite(int schemaVersion, String json, SiteWorkspaceDto currentWorkspace);
public ProjectWorkspaceDto restoreProject(int schemaVersion, String json, long currentVersion);
```

For any schema other than `1`, throw `new DomainException("SNAPSHOT_SCHEMA_UNSUPPORTED", HttpStatus.UNPROCESSABLE_ENTITY, Map.of("snapshotSchemaVersion", Integer.toString(schemaVersion)))`.

- [ ] **Step 6: Run round-trip and media-resolution tests**

Run from `backend-parent/`:

```powershell
.\mvnw.cmd -pl portfolio-server -am -Dtest=SnapshotMapperRoundTripTest test
```

Expected: PASS; repeated encoding is byte-identical, SHA-256 is stable, and workspace → snapshot → restored workspace differs only in the intentionally ignored draft fields.

- [ ] **Step 7: Commit snapshot contracts and mappers**

```powershell
git add backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing/snapshot backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/publishing/snapshot
git commit -m "feat(publishing): add versioned snapshot mappers"
```

### Task 8: Atomic SITE, PROJECT, and PROJECT_CATALOG publication service

**Files:**
- Create: `backend-parent/portfolio-pojo/src/main/java/xyz/yychainsaw/portfolio/publishing/api/PublishSiteCommand.java`
- Create: `backend-parent/portfolio-pojo/src/main/java/xyz/yychainsaw/portfolio/publishing/api/PublishProjectCommand.java`
- Create: `backend-parent/portfolio-pojo/src/main/java/xyz/yychainsaw/portfolio/publishing/api/ArchiveProjectCommand.java`
- Create: `backend-parent/portfolio-pojo/src/main/java/xyz/yychainsaw/portfolio/publishing/api/ReorderCatalogCommand.java`
- Create: `backend-parent/portfolio-pojo/src/main/java/xyz/yychainsaw/portfolio/publishing/api/PublicationResult.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing/persistence/PublishingRepository.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing/persistence/mybatis/PublishingMapper.java`
- Create: `backend-parent/portfolio-server/src/main/resources/mapper/publishing/PublishingMapper.xml`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing/application/PublicationValidator.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing/application/PublicationService.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing/application/CurrentPublicationQuery.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing/application/ProjectLabelQuery.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing/application/JdbcPublishingReadQueries.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing/application/ContentMediaReferenceChecker.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing/application/ContentMediaChangeListener.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing/web/AdminPublishingController.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/publishing/application/PublicationServiceTest.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/publishing/application/CatalogPublicationConcurrencyTest.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/publishing/application/PublishingReadPortsTest.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/publishing/application/ContentMediaReferenceCleanupTest.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/publishing/application/ContentMediaChangeListenerTest.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/publishing/support/PublishingTestFixture.java`

**Interfaces:**
- Consumes: workspace repositories/validator, Task 7 snapshot mappers/codec, plan 01 admin/audit contracts plus the test-only `PostgresIntegrationTestBase#migratorDataSource()` helper, plan 02 READY-media queries, and V5 tables.
- Produces: transactional publish/archive/reorder commands, publication history, revision media references, fixed catalog locking/CAS, admin publishing endpoints, plan 06 current-project/label read ports, and plan 02 retained-reference plus media-change dirty propagation.

Use exact command/result types:

```java
public record PublishSiteCommand(long expectedWorkspaceVersion, long expectedPublicationVersion) {}
public record PublishProjectCommand(UUID projectId, long expectedWorkspaceVersion,
                                    long expectedProjectPublicationVersion,
                                    long expectedCatalogVersion) {}
public record ArchiveProjectCommand(UUID projectId, long expectedProjectPublicationVersion,
                                    long expectedCatalogVersion) {}
public record ReorderCatalogCommand(long expectedCatalogVersion, List<UUID> projectIdsInOrder) {
    public ReorderCatalogCommand { projectIdsInOrder = List.copyOf(projectIdsInOrder); }
}
public record PublicationResult(UUID revisionId, long aggregateVersion,
                                UUID catalogRevisionId, Long catalogVersion,
                                String checksum) {}
```

- [ ] **Step 1: Write a failing atomic project/catalog publication test**

```java
// backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/publishing/application/PublicationServiceTest.java
package xyz.yychainsaw.portfolio.publishing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.ActiveProfiles;
import xyz.yychainsaw.portfolio.publishing.api.PublishProjectCommand;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
@ActiveProfiles("test")
@org.springframework.transaction.annotation.Transactional
class PublicationServiceTest extends PostgresIntegrationTestBase {
    @Autowired PublicationService publications;
    @Autowired JdbcTemplate jdbc;
    @Autowired xyz.yychainsaw.portfolio.publishing.support.PublishingTestFixture fixture;

    @Test
    void publishingProjectMovesProjectAndCatalogPointersInOneTransaction() {
        var project = fixture.persistReadyProject();
        var result = publications.publishProject(new PublishProjectCommand(
                project.id(), project.version(), 0L, 0L));
        assertThat(result.catalogRevisionId()).isNotNull();
        assertThat(jdbc.queryForObject(
                "select count(*) from publication where status='PUBLISHED' and " +
                "((aggregate_type='PROJECT' and aggregate_id=?::uuid) or " +
                "(aggregate_type='PROJECT_CATALOG' and aggregate_id='00000000-0000-0000-0000-000000000002'))",
                Integer.class, project.id().toString())).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "select publication_dirty from project where id=?::uuid", Boolean.class,
                project.id().toString())).isFalse();
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void migratorTriggerAndRuntimePrivilegeAreIndependentImmutabilityLayers() throws Exception {
        UUID revisionId = UUID.randomUUID();
        try (Connection connection = migratorDataSource().getConnection()) {
            connection.setAutoCommit(false);
            JdbcTemplate migrator = new JdbcTemplate(
                    new SingleConnectionDataSource(connection, true));
            UUID adminId = migrator.query(
                    "select id from admin_user order by created_at limit 1",
                    rs -> rs.next() ? rs.getObject(1, UUID.class) : null);
            if (adminId == null) {
                adminId = UUID.randomUUID();
                migrator.update("""
                        insert into admin_user(
                            id, username, password_hash, status, totp_key_version,
                            totp_nonce, totp_ciphertext)
                        values (?, ?, '{noop}test', 'ACTIVE', 1,
                                decode(repeat('00', 12), 'hex'), decode('00', 'hex'))
                        """, adminId, "migration-test-" + adminId.toString().substring(0, 8));
            }
            migrator.update("""
                    insert into content_revision(
                        id, aggregate_type, aggregate_id, version,
                        snapshot_schema_version, snapshot, checksum, published_by)
                    values (?, 'PROJECT', ?, 1, 1, '{}'::jsonb, repeat('a', 64), ?)
                    """, revisionId, UUID.randomUUID(), adminId);

            assertThatThrownBy(() -> migrator.update(
                    "update content_revision set checksum=repeat('b', 64) where id=?",
                    revisionId))
                    .hasMessageContaining("published revisions are immutable");
            connection.rollback();
        }

        assertThat(jdbc.queryForObject(
                "select has_table_privilege(current_user, " +
                "'portfolio.content_revision', 'UPDATE')", Boolean.class)).isFalse();
        assertThatThrownBy(() -> jdbc.update(
                "update content_revision set checksum=repeat('b', 64) where id=?",
                revisionId))
                .hasMessageContaining("permission denied");
    }
}
```

`PublishingTestFixture` is a test-only Spring component backed by the Task 4 repositories, plan 02's LOCAL test storage, and `PublicationService`. It exposes these exact helpers: `persistReadyProject()`, `publishProjectTwice()`, `publishProjectWithTitle(String)`, `editWorkspaceTitle(String)`, `mediaReferencedByCurrentRevision()`, `mediaReferencedOnlyByHistoricalRevision()`, `mediaReferencedOnlyByWorkspace()`, `publishSiteAndProject()`, and `editProjectWorkspaceTitle(String)`. `publishProjectTwice()` returns `PublishedProjectState(UUID projectId, UUID oldRevisionId, UUID currentRevisionId, long workspaceVersion)`. Every scenario uses unique UUIDs/slugs and registers cleanup through transaction rollback; media helpers create three distinct READY assets/variants in LOCAL test storage.

- [ ] **Step 2: Run the publication test and observe the missing service**

Run from `backend-parent/`:

```powershell
.\mvnw.cmd -pl portfolio-server -am -Dtest=PublicationServiceTest test
```

Expected: FAIL at compilation because `PublicationService` does not exist.

- [ ] **Step 3: Implement the locking/CAS persistence contract**

```java
package xyz.yychainsaw.portfolio.publishing.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import xyz.yychainsaw.portfolio.publishing.snapshot.AggregateType;

public interface PublishingRepository {
    void ensureProjectPublication(UUID projectId);
    PublicationRow lock(AggregateType type, UUID aggregateId);
    Optional<PublicationRow> find(AggregateType type, UUID aggregateId);
    List<PublicationRow> findPublishedProjects();
    Optional<PublicationRow> findPublishedProjectBySlug(String slug);
    Optional<String> redirectTarget(String oldSlug);
    void insertRevision(RevisionRow revision);
    void insertMediaReferences(UUID revisionId, List<MediaReferenceRow> references);
    boolean casPublish(AggregateType type, UUID aggregateId, long expectedVersion,
                       UUID revisionId, String slug, Instant publishedAt);
    boolean casArchive(AggregateType type, UUID aggregateId, long expectedVersion, Instant archivedAt);
    boolean currentSlugOrRedirectExists(String slug, UUID excludingProjectId);
    void insertRedirect(String oldSlug, String newSlug, UUID projectId);
    RevisionRow requireRevision(UUID revisionId);
    List<RevisionRow> history(AggregateType type, UUID aggregateId);

    record PublicationRow(AggregateType type, UUID aggregateId, String status,
                          UUID currentRevisionId, String currentSlug, long version,
                          Instant publishedAt) {}
    record RevisionRow(UUID id, AggregateType type, UUID aggregateId, long version,
                       int schemaVersion, String json, String checksum,
                       UUID publishedBy, Instant publishedAt) {}
    record MediaReferenceRow(UUID assetId, String variantName, String usage) {}
}
```

`PublishingMapper.xml` must implement `lock` with:

```sql
select aggregate_type, aggregate_id, status, current_revision_id, current_slug, version, published_at
from publication
where aggregate_type = #{type} and aggregate_id = #{aggregateId}
for update
```

and `casPublish` with:

```sql
update publication
set status='PUBLISHED', current_revision_id=#{revisionId}, current_slug=#{slug},
    version=version+1, published_at=#{publishedAt}
where aggregate_type=#{type} and aggregate_id=#{aggregateId} and version=#{expectedVersion}
```

Every project publish, archive, slug change, and reorder calls `lock(PROJECT_CATALOG, PROJECT_CATALOG_ID)` before it reads any current project publication. A CAS row count other than one maps to `CATALOG_VERSION_CONFLICT` or `PUBLICATION_VERSION_CONFLICT` with HTTP `409`.

- [ ] **Step 4: Implement publication-only validation**

`PublicationValidator` first delegates to `WorkspaceValidator`, then rejects the seven fixed placeholder paths from Task 5, every blank required translated leaf, blank SEO/cover fields, a missing current resume for either locale, non-READY media, missing bilingual media alt, a nonblank media `sourceUrl` that is not HTTPS, and invalid block subtype/card requirements. Translation completeness is an explicit typed walk over every `SiteWorkspaceDto`/`ProjectWorkspaceDto` copy record and each nested navigation/fact/skill/roadmap/outcome/taxonomy/block/action/metric item; it emits deterministic locale-qualified field paths and never relies on reflection or merely checks map keys. Thus incomplete translations remain saveable/importable drafts but cannot become a revision. For every referenced asset it compares all locale `alt/caption/credit/sourceUrl` values in the plan 02 descriptor with the `PublishedMediaV1.MediaCopy` values produced by the snapshot mapper; any loss or mismatch is a deterministic media field error before revision insertion. Error codes are:

```text
SITE_NOT_PUBLISHABLE
PROJECT_NOT_PUBLISHABLE
MEDIA_NOT_READY
MEDIA_TRANSLATION_INCOMPLETE
PLACEHOLDER_CONTENT_PRESENT
```

All failures use HTTP `422` and deterministic field paths. `PublicationValidator` returns a deduplicated, sorted `List<PublishingRepository.MediaReferenceRow>` containing every selected published variant; it never trusts media metadata embedded by the request. For a media-backed DOWNLOAD, it resolves the READY `MediaAssetDescriptor` itself and verifies that its `mimeType`, `byteSize`, SHA-256, and selected variant agree with the `PublishedMediaV1` produced by the snapshot mapper; a mismatch is `MEDIA_NOT_READY` and no revision is inserted. External HTTPS downloads have no media descriptor.

- [ ] **Step 5: Implement the project/catalog transaction**

The core service order must be exact:

```java
@org.springframework.transaction.annotation.Transactional
public PublicationResult publishProject(PublishProjectCommand command) {
    UUID adminId = currentAdmin.requireAdminId();
    var catalogPointer = publishing.lock(AggregateType.PROJECT_CATALOG, PROJECT_CATALOG_ID);
    requireVersion(catalogPointer.version(), command.expectedCatalogVersion(), "catalogVersion");
    ProjectWorkspaceDto workspace = projects.requireForUpdate(command.projectId());
    if (workspace.version() != command.expectedWorkspaceVersion()) {
        throw conflict("CONTENT_VERSION_CONFLICT", "workspaceVersion");
    }
    publishing.ensureProjectPublication(workspace.id());
    var projectPointer = publishing.lock(AggregateType.PROJECT, workspace.id());
    requireVersion(projectPointer.version(), command.expectedProjectPublicationVersion(), "publicationVersion");
    if (publishing.currentSlugOrRedirectExists(workspace.slug(), workspace.id())) {
        throw conflict("SLUG_CONFLICT", "slug");
    }

    ProjectSnapshotV1 projectSnapshot = projectMapper.toSnapshot(workspace);
    var projectMedia = validator.validateProject(workspace, projectSnapshot);
    var encodedProject = codec.encode(projectSnapshot);
    var projectRevision = newRevision(AggregateType.PROJECT, workspace.id(),
            projectPointer.version() + 1, encodedProject, adminId);
    publishing.insertRevision(projectRevision);
    publishing.insertMediaReferences(projectRevision.id(), projectMedia);

    java.util.List<ProjectSnapshotV1> current = loadCurrentProjectsReplacing(projectSnapshot);
    ProjectCatalogSnapshotV1 catalogSnapshot = catalogMapper.fromCurrentProjects(current);
    var encodedCatalog = codec.encode(catalogSnapshot);
    var catalogRevision = newRevision(AggregateType.PROJECT_CATALOG, PROJECT_CATALOG_ID,
            catalogPointer.version() + 1, encodedCatalog, adminId);
    publishing.insertRevision(catalogRevision);
    publishing.insertMediaReferences(catalogRevision.id(), catalogMedia(catalogSnapshot));

    if (projectPointer.currentSlug() != null && !projectPointer.currentSlug().equals(workspace.slug())) {
        publishing.insertRedirect(projectPointer.currentSlug(), workspace.slug(), workspace.id());
    }
    requireCas(publishing.casPublish(AggregateType.PROJECT, workspace.id(), projectPointer.version(),
            projectRevision.id(), workspace.slug(), clock.instant()), "PUBLICATION_VERSION_CONFLICT");
    requireCas(publishing.casPublish(AggregateType.PROJECT_CATALOG, PROJECT_CATALOG_ID,
            catalogPointer.version(), catalogRevision.id(), null, clock.instant()), "CATALOG_VERSION_CONFLICT");
    projects.markPublished(workspace.id(), workspace.version());
    audit.record(publicationAudit(adminId, "PROJECT_PUBLISHED", workspace.id(), projectRevision.id()));
    return new PublicationResult(projectRevision.id(), projectPointer.version() + 1,
            catalogRevision.id(), catalogPointer.version() + 1, encodedProject.sha256());
}
```

`publicationAudit` returns `new AuditCommand(adminId, action, "PROJECT", projectId.toString(), AuditOutcome.SUCCESS, null, Map.of("revisionId", revisionId.toString()))`; site/archive/reorder variants use target types `SITE` or `PROJECT_CATALOG` and string-only metadata, matching plan 01 exactly.

The `PROJECT_CATALOG_ID` constant is exactly `UUID.fromString("00000000-0000-0000-0000-000000000002")`. Add `updateCatalogOrder(List<UUID> projectIdsInOrder)` to `ProjectWorkspaceRepository`; it updates only `sort_order` in the supplied deterministic order while the catalog publication lock is held. `requireForUpdate` uses a `SELECT` query ending in `FOR UPDATE`; `markPublished` clears `publication_dirty` only when the workspace version still matches. `publishSite` follows the same revision/validation/CAS sequence without catalog work. `archiveProject` locks catalog before reading/locking the project pointer, writes a new catalog revision, archives the project pointer, and writes no new PROJECT revision. `reorderCatalog` locks and validates catalog first, verifies the supplied IDs equal the current published project ID set exactly once, updates workspace sort orders, generates one catalog revision, and CAS-advances only the catalog pointer.

- [ ] **Step 6: Add exact admin publishing routes**

```text
POST /api/admin/publishing/site
POST /api/admin/publishing/projects/{projectId}
POST /api/admin/publishing/projects/{projectId}/archive
PUT  /api/admin/publishing/catalog/order
GET  /api/admin/publishing/{aggregateType}/{aggregateId}/history
```

`AdminPublishingController` checks path/body project IDs match, requires the plan 01 authenticated admin and CSRF, returns `PublicationResult`, and delegates every error to the common RFC-style error handler.

- [ ] **Step 7: Write failing PostgreSQL integration tests for downstream read ports and cleanup protection**

Create `PublishingReadPortsTest` against the real plan 01 Testcontainers PostgreSQL database. Do not mock `publication`, `content_revision`, or catalog JSON:

```java
package xyz.yychainsaw.portfolio.publishing.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.publishing.support.PublishingTestFixture;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PublishingReadPortsTest extends PostgresIntegrationTestBase {
    @Autowired CurrentPublicationQuery currentPublication;
    @Autowired ProjectLabelQuery labels;
    @Autowired PublishingTestFixture fixture;

    @Test
    void onlyAProjectWithItsCurrentRevisionInTheCurrentPublishedCatalogIsCurrent() {
        var current = fixture.publishProjectTwice();
        assertThat(currentPublication.isCurrentPublishedProject(current.projectId())).isTrue();

        UUID unpublished = fixture.persistReadyProject().id();
        assertThat(currentPublication.isCurrentPublishedProject(unpublished)).isFalse();

        UUID archived = fixture.publishThenArchiveProject();
        assertThat(currentPublication.isCurrentPublishedProject(archived)).isFalse();

        UUID historicalCatalogOnly = fixture.projectPresentOnlyInRetainedCatalogRevision();
        assertThat(currentPublication.isCurrentPublishedProject(historicalCatalogOnly)).isFalse();
    }

    @Test
    void projectLabelsRemainAvailableAfterArchiveAndMissingIdsStayEmpty() {
        UUID projectId = fixture.publishProjectWithLocalizedTitles("当前标题", "Current title");
        assertThat(labels.findProjectTitle(projectId, LocaleCode.EN)).contains("Current title");
        fixture.archivePublishedProject(projectId);
        assertThat(labels.findProjectTitle(projectId, LocaleCode.ZH_CN)).contains("当前标题");
        assertThat(labels.findProjectTitle(fixture.persistReadyProject().id(), LocaleCode.EN)).isEmpty();
        assertThat(labels.findProjectTitle(UUID.randomUUID(), LocaleCode.EN)).isEmpty();
    }
}
```

Create `ContentMediaReferenceCleanupTest` with the actual `ContentMediaReferenceChecker`, plan 02 `ArchivedMediaCleanupJobHandler`, LOCAL test storage, and PostgreSQL rows. The test must directly invoke the handler; the production schedule remains disabled:

```java
package xyz.yychainsaw.portfolio.publishing.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import xyz.yychainsaw.portfolio.publishing.support.PublishingTestFixture;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
@ActiveProfiles("test")
class ContentMediaReferenceCleanupTest extends PostgresIntegrationTestBase {
    @Autowired ContentMediaReferenceChecker references;
    @Autowired PublishingTestFixture fixture;

    @ParameterizedTest
    @EnumSource(PublishingTestFixture.WorkspaceMediaLocation.class)
    void everyNormalizedWorkspaceMediaLocationIsReported(
            PublishingTestFixture.WorkspaceMediaLocation location) {
        var state = fixture.archivedMediaReferencedFromWorkspace(location);
        assertThat(references.findReferences(state.assetId()))
                .containsExactly(state.expectedReference());
    }

    @ParameterizedTest
    @EnumSource(PublishingTestFixture.CleanupReferenceKind.class)
    void workspaceAndEveryRetainedRevisionKindBlockPhysicalDeletion(
            PublishingTestFixture.CleanupReferenceKind kind) {
        var state = fixture.archivedMediaReferencedFrom(kind);
        assertThat(references.findReferences(state.assetId())).isNotEmpty();

        fixture.runPlan02PhysicalCleanup(state.assetId());

        assertThat(fixture.mediaRowExists(state.assetId())).isTrue();
        assertThat(fixture.storageObjectExists(state.objectKey())).isTrue();
    }

    @Test
    void anAssetWithNoWorkspaceOrRevisionReferenceCanBePhysicallyDeleted() {
        var state = fixture.unreferencedArchivedMediaOlderThanThirtyDays();
        assertThat(references.findReferences(state.assetId())).isEmpty();

        fixture.runPlan02PhysicalCleanup(state.assetId());

        assertThat(fixture.mediaRowExists(state.assetId())).isFalse();
        assertThat(fixture.storageObjectExists(state.objectKey())).isFalse();
    }
}
```

Create `ContentMediaChangeListenerTest` against the same PostgreSQL workspace tables. Invoke the listener directly with both plan 02 change types; plan 02 separately verifies that it invokes listeners in the media-update transaction:

```java
package xyz.yychainsaw.portfolio.publishing.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import xyz.yychainsaw.portfolio.media.application.MediaChangeType;
import xyz.yychainsaw.portfolio.publishing.support.PublishingTestFixture;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ContentMediaChangeListenerTest extends PostgresIntegrationTestBase {
    @Autowired ContentMediaChangeListener listener;
    @Autowired PublishingTestFixture fixture;

    @Test
    void sharedAssetBumpsSiteOnceAndEveryAffectedProjectOnceWithoutChangingPublicRevisions() {
        var state = fixture.mediaSharedByHeroResumeAndTwoProjects();
        var before = fixture.workspaceAndPublicationVersions(state);

        listener.onMediaChanged(state.assetId(), MediaChangeType.TRANSLATION_UPDATED);

        var after = fixture.workspaceAndPublicationVersions(state);
        assertThat(after.siteWorkspaceVersion()).isEqualTo(before.siteWorkspaceVersion() + 1);
        assertThat(after.projectWorkspaceVersions())
                .containsExactlyEntriesOf(before.projectWorkspaceVersions().entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                java.util.Map.Entry::getKey,
                                entry -> entry.getValue() + 1)));
        assertThat(after.projectDirty().values()).containsOnly(true);
        assertThat(after.publicationVersions()).isEqualTo(before.publicationVersions());
        assertThat(after.publicationRevisionIds()).isEqualTo(before.publicationRevisionIds());
    }

    @Test
    void unreferencedMediaChangesNoWorkspaceOrPublicationRow() {
        UUID assetId = fixture.unreferencedReadyMedia();
        var before = fixture.allWorkspaceAndPublicationVersions();

        listener.onMediaChanged(assetId, MediaChangeType.METADATA_UPDATED);

        assertThat(fixture.allWorkspaceAndPublicationVersions()).isEqualTo(before);
    }
}
```

The shared fixture references the same asset twice in SITE (`hero_media` and current `resume_document`) and from two distinct PROJECTs across `project_media`, `content_block_media`, `content_block_video.cover_asset_id`, and `content_block_action.media_asset_id`. The assertions prove deduplication: SITE and each project advance exactly once even if the asset appears in several rows.

`WorkspaceMediaLocation` contains exactly `HERO`, `RESUME`, `PROJECT_MEDIA`, `BLOCK_IMAGE_OR_GALLERY`, `VIDEO_COVER`, and `BLOCK_DOWNLOAD`; each fixture inserts through the corresponding normalized V4 table. `CleanupReferenceKind` contains exactly `WORKSPACE`, `CURRENT_REVISION`, `OLD_RETAINED_REVISION`, and `ARCHIVED_REVISION`. `OLD_RETAINED_REVISION` is no longer any publication pointer's current revision; `ARCHIVED_REVISION` belongs to a publication whose status is `ARCHIVED`; both retain their `revision_media_reference` rows. `expectedReference()` exposes only `referenceType/referenceId`. The fixture never exposes copy, slug, title, provider credentials, bucket, or object key through `MediaReference`.

Run from `backend-parent/`:

```powershell
.\mvnw.cmd -pl portfolio-server -am -Dtest=PublishingReadPortsTest,ContentMediaReferenceCleanupTest,ContentMediaChangeListenerTest test
```

Expected: FAIL at compilation because the plan 03 read ports, content reference checker, and media-change listener do not exist.

- [ ] **Step 8: Implement exact current-publication and archived-label ports for plan 06**

These interfaces are the only publication/content dependencies plan 06 analytics may import:

```java
// CurrentPublicationQuery.java
package xyz.yychainsaw.portfolio.publishing.application;

import java.util.UUID;

public interface CurrentPublicationQuery {
    boolean isCurrentPublishedProject(UUID projectId);
}
```

```java
// ProjectLabelQuery.java
package xyz.yychainsaw.portfolio.publishing.application;

import java.util.Optional;
import java.util.UUID;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;

public interface ProjectLabelQuery {
    Optional<String> findProjectTitle(UUID projectId, LocaleCode locale);
}
```

`JdbcPublishingReadQueries` is a read-only `@Repository` implementing both interfaces. `isCurrentPublishedProject` executes this PostgreSQL query and returns its single boolean. This deliberately requires a matching current PROJECT revision, a current PUBLISHED catalog revision, and membership in that catalog; presence in an old catalog or old project revision is insufficient:

```sql
select exists (
    select 1
    from publication project_publication
    join content_revision project_revision
      on project_revision.id = project_publication.current_revision_id
     and project_revision.aggregate_type = 'PROJECT'
     and project_revision.aggregate_id = project_publication.aggregate_id
     and project_revision.snapshot_schema_version = 1
    join publication catalog_publication
      on catalog_publication.aggregate_type = 'PROJECT_CATALOG'
     and catalog_publication.aggregate_id = '00000000-0000-0000-0000-000000000002'
     and catalog_publication.status = 'PUBLISHED'
    join content_revision catalog_revision
      on catalog_revision.id = catalog_publication.current_revision_id
     and catalog_revision.aggregate_type = 'PROJECT_CATALOG'
     and catalog_revision.aggregate_id = catalog_publication.aggregate_id
     and catalog_revision.snapshot_schema_version = 1
    cross join lateral jsonb_array_elements(
        case when jsonb_typeof(catalog_revision.snapshot -> 'projects') = 'array'
             then catalog_revision.snapshot -> 'projects'
             else '[]'::jsonb end
    ) catalog_project
    where project_publication.aggregate_type = 'PROJECT'
      and project_publication.aggregate_id = ?
      and project_publication.status = 'PUBLISHED'
      and catalog_project ->> 'projectId' = project_publication.aggregate_id::text
)
```

`findProjectTitle` executes `select pt.title from project p join project_translation pt on pt.project_id=p.id join publication pub on pub.aggregate_type='PROJECT' and pub.aggregate_id=p.id and pub.status in ('PUBLISHED','ARCHIVED') where p.id=? and pt.locale=?`. It reads the current workspace title for both published and archived projects, returns `Optional.empty()` for never-published/removed/missing projects, and never makes that admin analytics label public. Plan 06 falls back to the UUID text when the optional is empty.

- [ ] **Step 9: Implement one-query media-reference protection and lock-safe dirty propagation**

`ContentMediaReferenceChecker` is a Spring `@Component` implementing plan 02 `MediaReferenceChecker`. It executes exactly one SQL statement per asset and maps the ordered rows to immutable `MediaReference` values:

```sql
select reference_type, reference_id
from (
    select 'SITE_WORKSPACE'::text as reference_type, h.site_id as reference_id
    from hero_media hm
    join hero_section h on h.id = hm.hero_id
    where hm.media_asset_id = :assetId
    union
    select 'SITE_WORKSPACE', rd.site_id
    from resume_document rd
    where rd.media_asset_id = :assetId
    union
    select 'PROJECT_WORKSPACE', pm.project_id
    from project_media pm
    where pm.media_asset_id = :assetId
    union
    select 'PROJECT_WORKSPACE', pcb.project_id
    from content_block_media cbm
    join project_content_block pcb on pcb.id = cbm.block_id
    where cbm.media_asset_id = :assetId
    union
    select 'PROJECT_WORKSPACE', pcb.project_id
    from content_block_video cbv
    join project_content_block pcb on pcb.id = cbv.block_id
    where cbv.cover_asset_id = :assetId
    union
    select 'PROJECT_WORKSPACE', pcb.project_id
    from content_block_action cba
    join project_content_block pcb on pcb.id = cba.block_id
    where cba.media_asset_id = :assetId
    union
    select 'CONTENT_REVISION', rmr.revision_id
    from revision_media_reference rmr
    where rmr.asset_id = :assetId
) reference
order by reference_type, reference_id
```

The six workspace branches cover every normalized media FK, including IMAGE/GALLERY media, VIDEO covers, and media-backed DOWNLOAD blocks. Immutable snapshot JSON is covered through the exhaustive `revision_media_reference` rows written at publication; never search versioned JSON with ad hoc paths. The revision branch intentionally does not join `publication`: current, superseded-but-retained, and archived revisions all block storage deletion until an explicitly reviewed offline history purge removes their reference rows. Results are deduplicated by `UNION`, stably ordered, and redacted to the three fixed reference types plus UUIDs.

Registering this checker makes plan 02 archive checks and direct cleanup-handler invocations see workspace and history references. Keep the production scheduler/handler enable flag false in plans 02/03; plan 07 enables cleanup only after its deployment and recovery checks are complete.

`ContentMediaChangeListener` is a Spring `@Component` implementing plan 02 `MediaChangeListener`. Both `TRANSLATION_UPDATED` and `METADATA_UPDATED` can change a future snapshot, so they follow the same transaction-participating algorithm; reject null IDs/types before querying:

1. Lock the fixed SITE row first only when the asset is referenced by `hero_media` or any `resume_document`. Use one `SELECT site_profile.id` with an `EXISTS` subquery that unions those two asset-ID sources and end the statement with `FOR UPDATE`; then increment `site_profile.version` and set `updated_at=clock.instant()` exactly once. SITE uses its workspace-version advance as the dirty signal; do not add an independent publication row or move its published pointer.
2. Select every affected project through a `UNION` over `project_media`, `content_block_media`, `content_block_video.cover_asset_id`, and `content_block_action.media_asset_id`; join each typed-block table through `project_content_block.project_id`, deduplicate, order by project UUID, and lock the matching `project` rows with `FOR UPDATE` in that order.
3. For each locked project execute `update project set version=version+1, publication_dirty=true, updated_at=:changedAt where id=:projectId`. Repeated references within one project therefore cause one increment, while a shared asset marks every project.
4. Never update `publication`, `content_revision`, `revision_media_reference`, or `slug_redirect`. The next explicit publication creates new snapshots; existing public API/HTML checksums and pointers remain unchanged.

These locks participate in `MediaManagementService`'s existing transaction. A concurrent aggregate replacement either commits first and is followed by the listener's increment, or observes a stale expected version after the listener commits; no edit or dirty signal is lost. Any listener/query failure escapes and makes plan 02 roll back the media translation/metadata edit as well.

This listener already enters with the media asset `FOR UPDATE` lock held, so it defines the global media-before-content lock order. All workspace, import, publish, and restore transactions must acquire their deterministic media `FOR SHARE` set before any SITE/project/catalog `FOR UPDATE`; add a real PostgreSQL translation-vs-publish concurrency case that proves both transactions complete without SQLSTATE `40P01` and that version/dirty signals remain exact.

- [ ] **Step 10: Run the downstream port and physical-cleanup integration tests**

Run from `backend-parent/`:

```powershell
.\mvnw.cmd -pl portfolio-server -am -Dtest=PublishingReadPortsTest,ContentMediaReferenceCleanupTest,ContentMediaChangeListenerTest test
```

Expected: PASS against PostgreSQL and LOCAL storage; current project is true, unpublished/archived/old-catalog-only projects are false, current/archived labels resolve, all six workspace locations are found, current/old/archived revision references prevent physical deletion, a shared media change bumps SITE/two projects once without moving publication pointers, and only a fully unreferenced asset is removed.

- [ ] **Step 11: Write and run catalog concurrency and redirect tests**

The concurrency test uses two `ExecutorService` tasks, a `CountDownLatch`, and two distinct transactions. Both submit the same `expectedCatalogVersion`; assert exactly one returns success, exactly one throws `CATALOG_VERSION_CONFLICT`, catalog version increments once, and its project ID set is complete. Add a sequential slug-change test asserting the old slug row is inserted in the same transaction and a later project cannot claim either current or historical slug.

Run from `backend-parent/`:

```powershell
.\mvnw.cmd -pl portfolio-server -am -Dtest=PublicationServiceTest,CatalogPublicationConcurrencyTest test
```

Expected: PASS; project/catalog pointers move together, dirty state clears, one concurrent operation gets `409`, and no slug or catalog update is lost.

- [ ] **Step 12: Run the publication and migration suites**

Run from `backend-parent/`:

```powershell
.\mvnw.cmd -pl portfolio-server -am -Dtest=*Publication*Test,*PublishingReadPortsTest,*ContentMediaReferenceCleanupTest,*ContentMediaChangeListenerTest,*MigrationTest test
```

Expected: exit `0` with no failed tests; direct history mutation remains rejected.

- [ ] **Step 13: Commit atomic publishing**

```powershell
git add backend-parent/portfolio-pojo/src/main/java/xyz/yychainsaw/portfolio/publishing/api backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing backend-parent/portfolio-server/src/main/resources/mapper/publishing backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/publishing
git commit -m "feat(publishing): publish project and catalog atomically"
```

### Task 9: Restore-to-draft and authenticated short-lived preview

**Files:**
- Create: `backend-parent/portfolio-pojo/src/main/java/xyz/yychainsaw/portfolio/publishing/api/RestoreRevisionRequest.java`
- Create: `backend-parent/portfolio-pojo/src/main/java/xyz/yychainsaw/portfolio/publishing/api/PreviewTokenRequest.java`
- Create: `backend-parent/portfolio-pojo/src/main/java/xyz/yychainsaw/portfolio/publishing/api/PreviewTokenResponse.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing/application/RestoreService.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing/application/PreviewTokenService.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing/application/PreviewService.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing/config/PreviewProperties.java`
- Modify: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing/web/AdminPublishingController.java`
- Modify: `backend-parent/portfolio-server/src/main/resources/application.yml`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/publishing/application/RestoreServiceTest.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/publishing/application/PreviewTokenServiceTest.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/publishing/support/MutableClock.java`

**Interfaces:**
- Consumes: immutable history, snapshot registry, workspace repositories, current admin, audit service, and `Clock`.
- Produces: restore of SITE/PROJECT into new editable drafts, 10-minute HMAC preview tokens bound to admin/aggregate/workspace version, and authenticated preview snapshots without revision writes.

Use exact API records:

```java
public record RestoreRevisionRequest(long expectedWorkspaceVersion) {}
public record PreviewTokenRequest(AggregateType aggregateType, UUID aggregateId,
                                  long workspaceVersion, LocaleCode locale) {}
public record PreviewTokenResponse(String token, Instant expiresAt) {}
```

- [ ] **Step 1: Write failing restore and tampered-token tests**

```java
// RestoreServiceTest.java
@Test
void restoringProjectCopiesHistoryIntoDraftWithoutMovingPublication() {
    var state = fixture.publishProjectTwice();
    UUID currentRevisionBefore = state.currentRevisionId();
    restore.restore(state.oldRevisionId(), state.workspaceVersion());
    assertThat(projects.require(state.projectId()).publicationDirty()).isTrue();
    assertThat(publishing.find(AggregateType.PROJECT, state.projectId()).orElseThrow().currentRevisionId())
            .isEqualTo(currentRevisionBefore);
}
```

```java
// PreviewTokenServiceTest.java
@Test
void rejectsTamperedExpiredAndCrossAdminTokens() {
    PreviewTokenResponse issued = tokens.issue(new PreviewTokenRequest(
            AggregateType.PROJECT, PROJECT_ID, 7L, LocaleCode.EN), ADMIN_ID);
    assertThatThrownBy(() -> tokens.verify(issued.token() + "x", ADMIN_ID))
            .isInstanceOf(DomainException.class)
            .extracting(error -> ((DomainException) error).code())
            .isEqualTo("PREVIEW_TOKEN_INVALID");
    assertThatThrownBy(() -> tokens.verify(issued.token(), OTHER_ADMIN_ID))
            .isInstanceOf(DomainException.class)
            .extracting(error -> ((DomainException) error).code())
            .isEqualTo("PREVIEW_TOKEN_INVALID");
    clock.advance(java.time.Duration.ofMinutes(11));
    assertThatThrownBy(() -> tokens.verify(issued.token(), ADMIN_ID))
            .isInstanceOf(DomainException.class);
}
```

`MutableClock` is a package-private `Clock` implementation backed by an `AtomicReference<Instant>`; `instant()` returns the reference, `getZone()` returns UTC, `withZone` rejects non-UTC zones, and `advance(Duration duration)` atomically adds the duration. Initialize it to `2026-07-14T00:00:00Z` in the preview test so expiry assertions are deterministic.

- [ ] **Step 2: Run focused tests and observe missing services**

Run from `backend-parent/`:

```powershell
.\mvnw.cmd -pl portfolio-server -am -Dtest=RestoreServiceTest,PreviewTokenServiceTest test
```

Expected: FAIL at compilation because restore and preview services do not exist.

- [ ] **Step 3: Implement restore as a workspace-only transaction**

```java
@org.springframework.transaction.annotation.Transactional
public void restore(UUID revisionId, long expectedWorkspaceVersion) {
    UUID adminId = currentAdmin.requireAdminId();
    PublishingRepository.RevisionRow revision = publishing.requireRevision(revisionId);
    switch (revision.type()) {
        case SITE -> {
            SiteWorkspaceDto current = sites.require();
            requireExpected(current.version(), expectedWorkspaceVersion);
            SiteWorkspaceDto restored = registry.restoreSite(
                    revision.schemaVersion(), revision.json(), current);
            sites.replace(restored, current.version());
        }
        case PROJECT -> {
            ProjectWorkspaceDto current = projects.require(revision.aggregateId());
            requireExpected(current.version(), expectedWorkspaceVersion);
            ProjectWorkspaceDto restored = registry.restoreProject(
                    revision.schemaVersion(), revision.json(), current.version());
            projects.replace(restored, current.version());
        }
        case PROJECT_CATALOG -> throw new DomainException(
                "CATALOG_RESTORE_NOT_ALLOWED", HttpStatus.UNPROCESSABLE_ENTITY,
                Map.of("revisionId", "catalog is regenerated from current project publications"));
    }
    audit.record(new AuditCommand(adminId, "REVISION_RESTORED_TO_DRAFT",
            revision.type().name(), revision.aggregateId().toString(), AuditOutcome.SUCCESS,
            null, Map.of("revisionId", revisionId.toString())));
}
```

Do not insert a `content_revision`, move `publication`, restore a historical catalog snapshot, or delete the current workspace first. The repository replacement transaction preserves IDs and increments workspace version once.

- [ ] **Step 4: Implement compact signed preview claims**

Configure:

```yaml
portfolio:
  preview:
    hmac-key: ${PORTFOLIO_PREVIEW_HMAC_KEY}
    ttl: PT10M
```

The production key must decode from Base64 to at least 32 bytes. The token is `base64url(canonical-json).base64url(HMAC-SHA256(payload))`. Claims are:

```java
record PreviewClaims(UUID adminId, AggregateType aggregateType, UUID aggregateId,
                     long workspaceVersion, LocaleCode locale, Instant expiresAt, UUID nonce) {}
```

`verify` uses `MessageDigest.isEqual`, rejects expiry, checks the session admin equals `claims.adminId`, and returns `PREVIEW_TOKEN_INVALID` with HTTP `403` for every failure without revealing which check failed.

- [ ] **Step 5: Implement preview without history writes**

```java
public Object preview(PreviewClaims claims) {
    return switch (claims.aggregateType()) {
        case SITE -> {
            SiteWorkspaceDto workspace = sites.require();
            requireVersion(workspace.version(), claims.workspaceVersion());
            workspaceValidator.validateSite(workspace);
            yield siteMapper.toSnapshot(workspace);
        }
        case PROJECT -> {
            ProjectWorkspaceDto workspace = projects.require(claims.aggregateId());
            requireVersion(workspace.version(), claims.workspaceVersion());
            workspaceValidator.validateProject(workspace);
            ProjectSnapshotV1 snapshot = projectMapper.toSnapshot(workspace);
            yield snapshot;
        }
        case PROJECT_CATALOG -> throw new DomainException(
                "CATALOG_PREVIEW_NOT_ALLOWED", HttpStatus.UNPROCESSABLE_ENTITY,
                Map.of("aggregateType", "catalog preview is derived from project previews"));
    };
}
```

Enum switch expressions are part of Java 17 and require no preview flag. Preview deliberately runs structural workspace validation, not publication-readiness validation, so an authenticated administrator can inspect placeholder-bearing drafts; snapshot mapping still rejects missing/non-READY media needed to render the selected draft.

- [ ] **Step 6: Add authenticated routes and security tests**

Add:

```text
POST /api/admin/publishing/preview-tokens
GET  /api/admin/publishing/previews/{token}
POST /api/admin/publishing/revisions/{revisionId}/restore
```

Issuing and consuming a token both require an authenticated admin session; mutation routes require CSRF. A preview request with a valid token but no session returns `401`, a different admin returns `403`, and a stale workspace version returns `409`.

- [ ] **Step 7: Run restore, preview, publication, and security tests**

Run from `backend-parent/`:

```powershell
.\mvnw.cmd -pl portfolio-server -am -Dtest=RestoreServiceTest,PreviewTokenServiceTest,*PublishingControllerTest test
```

Expected: PASS; historical pointers remain unchanged by restore, unknown schema is `422`, tampered/expired token is `403`, unauthenticated preview is `401`, and no preview creates a revision.

- [ ] **Step 8: Commit restore and preview**

```powershell
git add backend-parent/portfolio-pojo/src/main/java/xyz/yychainsaw/portfolio/publishing/api backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing backend-parent/portfolio-server/src/main/resources/application.yml backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/publishing
git commit -m "feat(publishing): add restore and preview flows"
```

### Task 10: Public snapshot APIs and publication-gated media delivery

**Files:**
- Create: `backend-parent/portfolio-pojo/src/main/java/xyz/yychainsaw/portfolio/publicapi/PublishedEnvelope.java`
- Create: `backend-parent/portfolio-pojo/src/main/java/xyz/yychainsaw/portfolio/publicapi/PublicSiteDto.java`
- Create: `backend-parent/portfolio-pojo/src/main/java/xyz/yychainsaw/portfolio/publicapi/PublicMediaDto.java`
- Create: `backend-parent/portfolio-pojo/src/main/java/xyz/yychainsaw/portfolio/publicapi/PublicBlockDto.java`
- Create: `backend-parent/portfolio-pojo/src/main/java/xyz/yychainsaw/portfolio/publicapi/PublicProjectCardDto.java`
- Create: `backend-parent/portfolio-pojo/src/main/java/xyz/yychainsaw/portfolio/publicapi/PublicProjectDto.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing/application/PublicSnapshotQueryService.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing/application/PublicProjectionMapper.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing/application/SafeMarkdownRenderer.java`
- Modify: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing/snapshot/PublishingConfiguration.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing/persistence/PublicMediaReferenceRepository.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing/web/PublicContentController.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing/web/PublicMediaController.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing/web/HttpEtag.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/publishing/web/PublicContentControllerTest.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/publishing/web/PublicMediaControllerTest.java`
- Modify: `backend-parent/portfolio-server/pom.xml`

**Interfaces:**
- Consumes: current publication pointers/revisions, Task 7 codec, plan 02 `MediaQueryService`/`StorageRouter`, and common errors.
- Produces: locale-projected JSON APIs, revision-checksum ETags, and media reads authorized only by a current PUBLISHED revision reference.

Use this exact envelope:

```java
package xyz.yychainsaw.portfolio.publicapi;

public record PublishedEnvelope<T>(long revisionVersion, String checksum, T data) {}
```

Use these locale-projected DTO contracts. They contain no workspace versions, storage keys, provider/bucket names, or signed COS URLs:

```java
// PublicMediaDto.java
package xyz.yychainsaw.portfolio.publicapi;

import java.util.UUID;

public record PublicMediaDto(UUID assetId, String variant, String src, String srcset,
        String alt, String caption, String credit, String sourceUrl,
        int width, int height) {
    public PublicMediaDto {
        java.util.Objects.requireNonNull(assetId);
        java.util.Objects.requireNonNull(variant);
        java.util.Objects.requireNonNull(src);
        java.util.Objects.requireNonNull(srcset);
        java.util.Objects.requireNonNull(alt);
        caption = caption == null ? "" : caption;
        credit = credit == null ? "" : credit;
        sourceUrl = sourceUrl == null ? "" : sourceUrl;
    }
}
```

```java
// PublicSiteDto.java
package xyz.yychainsaw.portfolio.publicapi;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PublicSiteDto(
        Identity identity, Seo seo, Accessibility accessibility,
        List<NavigationItem> navigation, Hero hero, About about, Work work,
        Roadmap roadmap, Contact contact, Privacy privacy,
        List<SocialLink> socialLinks, Resume resume) {
    public PublicSiteDto {
        navigation = List.copyOf(navigation);
        socialLinks = List.copyOf(socialLinks);
        java.util.Objects.requireNonNull(resume);
    }
    public record Identity(String monogram, String displayName, String secondaryName,
                           String email) {}
    public record Seo(String title, String description) {}
    public record Accessibility(String skip, String primaryNav, String mobileNav,
            String openMenu, String closeMenu, String language, String backToTop,
            String projectTags) {}
    public record NavigationItem(String target, int sortOrder, String label) {}
    public record Hero(String eyebrow, String displayName, String secondaryName, String role,
            String headline, String introduction, String availability, String primaryCta,
            String secondaryCta, String visualLabel, String stageLabel,
            String objectPosition, String credit, String sourceUrl, PublicMediaDto media) {}
    public record About(String label, String title, String statement, String focusLabel,
            String focusTitle, String focusIntro, List<Fact> facts, List<Skill> skills) {
        public About { facts = List.copyOf(facts); skills = List.copyOf(skills); }
    }
    public record Fact(String label, String value) {}
    public record Skill(String name, String status) {}
    public record Work(String label, String title, String introduction, String imageNotice,
            String openSlotLabel, String openSlotTitle, String openSlotText,
            String openSlotMeta) {}
    public record Roadmap(String label, String title, String introduction,
            List<RoadmapStage> stages) {
        public Roadmap { stages = List.copyOf(stages); }
    }
    public record RoadmapStage(UUID id, String number, String period, String title,
            String summary, List<String> outcomes) {
        public RoadmapStage { outcomes = List.copyOf(outcomes); }
    }
    public record Contact(String label, String title, String introduction, String emailLabel,
            String email, String workCta, String roadmapCta, String footerNote) {}
    public record Privacy(String title, String html) {}
    public record SocialLink(String platform, String url) {}
    public record Resume(String label, LocalDate documentDate, String href) {}
}
```

```java
// PublicProjectCardDto.java
package xyz.yychainsaw.portfolio.publicapi;

import java.util.List;
import java.util.UUID;

public record PublicProjectCardDto(UUID projectId, String slug, String number, int sortOrder,
        boolean featured, String status, String eyebrow, String title, String summary,
        List<String> tags, PublicMediaDto cover) {
    public PublicProjectCardDto { tags = List.copyOf(tags); }
}
```

```java
// PublicBlockDto.java
package xyz.yychainsaw.portfolio.publicapi;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record PublicBlockDto(UUID id, String type, int sortOrder, String width, String alignment,
        String emphasis, int columns, Payload payload) {
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = Markdown.class, name = "MARKDOWN"),
            @JsonSubTypes.Type(value = Image.class, name = "IMAGE"),
            @JsonSubTypes.Type(value = Gallery.class, name = "GALLERY"),
            @JsonSubTypes.Type(value = Video.class, name = "VIDEO"),
            @JsonSubTypes.Type(value = Code.class, name = "CODE"),
            @JsonSubTypes.Type(value = Quote.class, name = "QUOTE"),
            @JsonSubTypes.Type(value = Metrics.class, name = "METRICS"),
            @JsonSubTypes.Type(value = Download.class, name = "DOWNLOAD"),
            @JsonSubTypes.Type(value = Link.class, name = "LINK")
    })
    public sealed interface Payload permits Markdown, Image, Gallery, Video, Code,
            Quote, Metrics, Download, Link {}
    public record Markdown(String html) implements Payload {}
    public record Image(PublicMediaDto media) implements Payload {}
    public record Gallery(List<PublicMediaDto> media) implements Payload {
        public Gallery { media = List.copyOf(media); }
    }
    public record Video(String provider, String embedUrl, PublicMediaDto cover,
                        String title, String description) implements Payload {}
    public record Code(String code, String language, boolean showLineNumbers,
                       String title, String description) implements Payload {}
    public record Quote(String quote, String source) implements Payload {}
    public record Metrics(List<Metric> metrics) implements Payload {
        public Metrics { metrics = List.copyOf(metrics); }
    }
    public record Metric(UUID id, BigDecimal numericValue, String label,
                         String value, String suffix) {}
    public record Download(String href, String label, String description,
                           String mimeType, Long byteSize) implements Payload {}
    public record Link(String href, boolean openNewTab, String label,
                       String description) implements Payload {}
}
```

```java
// PublicProjectDto.java
package xyz.yychainsaw.portfolio.publicapi;

import java.util.List;
import java.util.UUID;

public record PublicProjectDto(UUID projectId, String slug, String number, boolean featured,
        String status, String eyebrow, String title, String summary, String seoTitle,
        String seoDescription, List<String> tags, List<String> skills,
        List<PublicMediaDto> media, List<PublicBlockDto> blocks) {
    public PublicProjectDto {
        tags = List.copyOf(tags); skills = List.copyOf(skills);
        media = List.copyOf(media); blocks = List.copyOf(blocks);
    }
}
```

- [ ] **Step 1: Write failing tests proving drafts and historical media stay private**

```java
// PublicContentControllerTest.java
@Test
void publicProjectReadsCurrentRevisionAndNeverWorkspace() throws Exception {
    fixture.publishProjectWithTitle("Published title");
    fixture.editWorkspaceTitle("Draft title");
    mvc.perform(get("/api/public/projects/gameplay-prototype").queryParam("locale", "en"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.title").value("Published title"))
            .andExpect(header().string("ETag", org.hamcrest.Matchers.matchesPattern("\"[0-9a-f]{64}\"")));
}
```

```java
// PublicMediaControllerTest.java
@Test
void onlyMediaReferencedByTheCurrentPublishedRevisionIsReadable() throws Exception {
    UUID current = fixture.mediaReferencedByCurrentRevision();
    UUID old = fixture.mediaReferencedOnlyByHistoricalRevision();
    UUID draft = fixture.mediaReferencedOnlyByWorkspace();
    mvc.perform(get("/api/public/media/{id}/1280", current)).andExpect(status().isOk());
    mvc.perform(get("/api/public/media/{id}/1280", old)).andExpect(status().isNotFound());
    mvc.perform(get("/api/public/media/{id}/1280", draft)).andExpect(status().isNotFound());
    mvc.perform(get("/api/public/media/{id}/1280", UUID.randomUUID())).andExpect(status().isNotFound());
}
```

- [ ] **Step 2: Run public controller tests and observe 404/missing-bean failures**

Run from `backend-parent/`:

```powershell
.\mvnw.cmd -pl portfolio-server -am -Dtest=PublicContentControllerTest,PublicMediaControllerTest test
```

Expected: FAIL because public controllers and queries are absent.

- [ ] **Step 3: Implement current-publication-only snapshot queries**

Add the pinned Markdown renderer dependency to `portfolio-server/pom.xml`:

```xml
<dependency>
  <groupId>org.commonmark</groupId>
  <artifactId>commonmark</artifactId>
  <version>0.24.0</version>
</dependency>
```

```java
// SafeMarkdownRenderer.java
package xyz.yychainsaw.portfolio.publishing.application;

import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

public final class SafeMarkdownRenderer {
    private final Parser parser = Parser.builder().build();
    private final HtmlRenderer renderer = HtmlRenderer.builder()
            .escapeHtml(true)
            .sanitizeUrls(true)
            .build();

    public String render(String markdown) {
        return renderer.render(parser.parse(markdown));
    }
}
```

Register `SafeMarkdownRenderer` as a bean. `PublicProjectionMapper` is the only place that converts Markdown to HTML; it selects exactly one `LocaleV1`, calls this renderer, and produces `PublicBlockDto.Markdown`. It maps media to stable `/api/public/media/{assetId}/{variant}` paths and a width-sorted `srcset`, never to object-store keys. Every media projection selects the matching locale's `PublishedMediaV1.MediaCopy` and maps all four values: `alt` verbatim, plus `caption`, `credit`, and `sourceUrl` normalized from null to `""`; nonblank source URLs must remain HTTPS. Apply the same helper to project media, catalog covers, IMAGE/GALLERY payloads, and VIDEO covers so attribution is never limited to Hero. Video projection accepts only YouTube (`youtube.com`, `youtu.be`), Vimeo (`vimeo.com`), and Bilibili (`bilibili.com`, `player.bilibili.com`), emits a canonical HTTPS embed URL, and rejects every other host with `PROJECT_NOT_PUBLISHABLE`. Link/download projection permits only HTTPS, plus a stable current-public-media URL for media downloads. A media-backed `Download` looks up the matching `PublishedMediaV1` embedded in the same PROJECT snapshot and sets nonblank `mimeType` plus nonnegative `byteSize`; an external HTTPS download sets both fields to `null`. Add controller JSON assertions for media-backed/external downloads and for localized `credit/sourceUrl` on IMAGE, every GALLERY item, and a VIDEO cover so a mapper cannot silently omit attribution, omit required file metadata, or invent metadata for an external URL.

```java
package xyz.yychainsaw.portfolio.publishing.application;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.content.api.SiteWorkspaceDto;
import xyz.yychainsaw.portfolio.publicapi.PublishedEnvelope;
import xyz.yychainsaw.portfolio.publicapi.PublicProjectCardDto;
import xyz.yychainsaw.portfolio.publicapi.PublicProjectDto;
import xyz.yychainsaw.portfolio.publicapi.PublicSiteDto;
import xyz.yychainsaw.portfolio.publishing.persistence.PublishingRepository;
import xyz.yychainsaw.portfolio.publishing.snapshot.AggregateType;
import xyz.yychainsaw.portfolio.publishing.snapshot.SnapshotCodec;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.ProjectCatalogSnapshotV1;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.ProjectSnapshotV1;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.SiteSnapshotV1;

@Service
public final class PublicSnapshotQueryService {
    private static final UUID PROJECT_CATALOG_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private final PublishingRepository publishing;
    private final SnapshotCodec codec;
    private final PublicProjectionMapper projections;

    public PublicSnapshotQueryService(PublishingRepository publishing, SnapshotCodec codec,
                                      PublicProjectionMapper projections) {
        this.publishing = publishing;
        this.codec = codec;
        this.projections = projections;
    }

    public PublishedEnvelope<PublicSiteDto> site(LocaleCode locale) {
        var revision = requireCurrent(AggregateType.SITE, SiteWorkspaceDto.SITE_ID);
        var snapshot = codec.decode(revision.json(), SiteSnapshotV1.class);
        return new PublishedEnvelope<>(revision.version(), revision.checksum(), projections.site(snapshot, locale));
    }

    public PublishedEnvelope<java.util.List<PublicProjectCardDto>> catalog(LocaleCode locale) {
        var revision = requireCurrent(AggregateType.PROJECT_CATALOG, PROJECT_CATALOG_ID);
        var snapshot = codec.decode(revision.json(), ProjectCatalogSnapshotV1.class);
        return new PublishedEnvelope<>(revision.version(), revision.checksum(), projections.catalog(snapshot, locale));
    }

    public PublishedEnvelope<PublicProjectDto> project(String slug, LocaleCode locale) {
        var publication = publishing.findPublishedProjectBySlug(slug)
                .orElseThrow(this::projectNotFound);
        var revision = publishing.requireRevision(publication.currentRevisionId());
        var snapshot = codec.decode(revision.json(), ProjectSnapshotV1.class);
        return new PublishedEnvelope<>(revision.version(), revision.checksum(), projections.project(snapshot, locale));
    }

    private PublishingRepository.RevisionRow requireCurrent(AggregateType type, java.util.UUID id) {
        var pointer = publishing.find(type, id).filter(row -> row.status().equals("PUBLISHED"))
                .orElseThrow(() -> notFound(type));
        return publishing.requireRevision(pointer.currentRevisionId());
    }

    private DomainException projectNotFound() {
        return new DomainException("PROJECT_NOT_FOUND", HttpStatus.NOT_FOUND,
                java.util.Map.of());
    }
    private DomainException notFound(AggregateType type) {
        return new DomainException(type == AggregateType.SITE
                ? "SITE_NOT_FOUND" : "PROJECT_CATALOG_NOT_FOUND",
                HttpStatus.NOT_FOUND, java.util.Map.of());
    }
}
```

Use the `findPublishedProjectBySlug` and `redirectTarget` repository methods declared in Task 8. Neither query joins workspace tables.

- [ ] **Step 4: Implement public JSON routes and API ETags**

Expose exactly:

```text
GET /api/public/site?locale=zh-CN
GET /api/public/projects?locale=zh-CN
GET /api/public/projects/{slug}?locale=zh-CN
```

`HttpEtag.api(checksum, locale)` returns the quoted SHA-256 of `checksum + "\n" + locale.value()`. If `If-None-Match` exactly matches, return `304` with no body; otherwise return `200`, `Cache-Control: public, no-cache`, ETag, and `PublishedEnvelope`. Unsupported locale is `400`; unpublished/missing content is `404`.

- [ ] **Step 5: Implement current-reference media authorization**

```sql
select exists (
    select 1
    from revision_media_reference rmr
    join publication p on p.current_revision_id = rmr.revision_id
    where rmr.asset_id = #{assetId}
      and rmr.variant_name = #{variantName}
      and p.status = 'PUBLISHED'
)
```

`PublicMediaReferenceRepository.isCurrentlyPublished(assetId, variantName)` executes exactly that query. `PublicMediaController` returns `404` before calling `MediaQueryService` when it is false, so a stale conditional request never reveals an unpublished reference. It then loads `MediaVariantDescriptor variant`, selects `StorageService storage = storageRouter.require(variant.provider())`, derives the quoted strong ETag only from `variant.sha256()`, and never reconstructs metadata from a key. For LOCAL, honor `If-None-Match` with `304` only after the publication check; parse at most one RFC 7233 bytes range; honor the range only when an optional `If-Range` strong ETag matches, otherwise send the full representation; call `storage.open(variant.objectKey(), optionalRange)`; and return `200` or `206`, `Cache-Control: public, no-cache`, `Accept-Ranges: bytes`, the strong ETag, `Content-Range` computed from the requested range and `variant.byteSize()`, and an `InputStreamResource` wrapping `StorageRead.inputStream()`. For TENCENT_COS, reject Range with `416` and otherwise return `302` to `storage.signedGet(variant.objectKey(), Duration.ofMinutes(5))` with the variant ETag and `Cache-Control: no-store`; never expose the signed URL in JSON. A signed URL may remain valid for five minutes after archive, matching the approved withdrawal boundary. Tests cover Local `200/206/304`, mismatched `If-Range -> 200`, multi-range/unsatisfiable `416`, publication removal after a cached ETag -> `404`, COS Range `416`, and COS signed redirect/no-store.

- [ ] **Step 6: Run current/draft/history/media/ETag tests**

Run from `backend-parent/`:

```powershell
.\mvnw.cmd -pl portfolio-server -am -Dtest=PublicContentControllerTest,PublicMediaControllerTest test
```

Expected: PASS; workspace edits do not leak, a matching API ETag returns `304`, current media is readable, and draft/history/archive/random media returns `404`.

- [ ] **Step 7: Commit public JSON and media delivery**

```powershell
git add backend-parent/portfolio-pojo/src/main/java/xyz/yychainsaw/portfolio/publicapi backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/publishing/web
git commit -m "feat(public): expose published content and media"
```

### Task 11: Localized Thymeleaf HTML, composite ETags, redirects, and sitemap

**Files:**
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing/config/PublicRenderProperties.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing/web/AssetManifestService.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing/web/CompositeEtagService.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing/web/SafeInitialJson.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing/web/PageBootstrap.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing/web/PublicPageRenderer.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing/web/PublicPageController.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing/web/SitemapController.java`
- Create: `backend-parent/portfolio-server/src/main/resources/templates/public/home.html`
- Create: `backend-parent/portfolio-server/src/main/resources/templates/public/project.html`
- Create: `backend-parent/portfolio-server/src/main/resources/templates/public/privacy.html`
- Create: `backend-parent/portfolio-server/src/main/resources/static/robots.txt`
- Modify: `backend-parent/portfolio-server/src/main/resources/application.yml`
- Create: `backend-parent/portfolio-server/src/test/resources/application-test.yml`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/publishing/web/PublicHtmlContractTest.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/publishing/web/CompositeEtagServiceTest.java`
- Create: `backend-parent/portfolio-server/src/test/resources/vite/manifest.json`

**Interfaces:**
- Consumes: Task 10 published query service, redirect lookup, current revisions/checksums, and a Vite manifest generated from the same Git commit.
- Produces: `/`, localized home/project/privacy HTML, `301` old-slug redirects, dynamic sitemap, static robots, safe initial JSON, and composite dependency-aware ETags.

- [ ] **Step 1: Write a failing ETag dependency matrix test**

```java
// CompositeEtagServiceTest.java
package xyz.yychainsaw.portfolio.publishing.web;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;

class CompositeEtagServiceTest {
    private final CompositeEtagService etags = new CompositeEtagService();

    @Test
    void homeAndProjectEtagsCoverEveryRenderedDependency() {
        String home = etags.home("site-a", "catalog-a", "release-a", 1, LocaleCode.EN);
        assertThat(etags.home("site-b", "catalog-a", "release-a", 1, LocaleCode.EN)).isNotEqualTo(home);
        assertThat(etags.home("site-a", "catalog-b", "release-a", 1, LocaleCode.EN)).isNotEqualTo(home);
        assertThat(etags.home("site-a", "catalog-a", "release-b", 1, LocaleCode.EN)).isNotEqualTo(home);
        assertThat(etags.home("site-a", "catalog-a", "release-a", 2, LocaleCode.EN)).isNotEqualTo(home);
        assertThat(etags.home("site-a", "catalog-a", "release-a", 1, LocaleCode.ZH_CN)).isNotEqualTo(home);
        String project = etags.project("site-a", "catalog-a", "project-a", "release-a", 1, LocaleCode.EN);
        assertThat(etags.project("site-a", "catalog-a", "project-b", "release-a", 1, LocaleCode.EN))
                .isNotEqualTo(project);
    }
}
```

- [ ] **Step 2: Run ETag test and observe the missing service**

Run from `backend-parent/`:

```powershell
.\mvnw.cmd -pl portfolio-server -am -Dtest=CompositeEtagServiceTest test
```

Expected: FAIL at compilation because `CompositeEtagService` is absent.

- [ ] **Step 3: Implement release-aware composite ETags**

```java
package xyz.yychainsaw.portfolio.publishing.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;

public final class CompositeEtagService {
    public String home(String site, String catalog, String release, int template, LocaleCode locale) {
        return hash(java.util.List.of("home", site, catalog, release,
                Integer.toString(template), locale.value()));
    }
    public String project(String site, String catalog, String project, String release,
                          int template, LocaleCode locale) {
        return hash(java.util.List.of("project", site, catalog, project, release,
                Integer.toString(template), locale.value()));
    }
    public String privacy(String site, String release, int template, LocaleCode locale) {
        return hash(java.util.List.of("privacy", site, release,
                Integer.toString(template), locale.value()));
    }
    public String sitemap(String catalog) { return hash(java.util.List.of("sitemap", catalog)); }
    private String hash(java.util.List<String> values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                digest.update(value.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return "\"" + HexFormat.of().formatHex(digest.digest()) + "\"";
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
```

Configure and validate startup properties:

```yaml
portfolio:
  render:
    release-id: ${PORTFOLIO_RELEASE_ID}
    vite-manifest: ${PORTFOLIO_VITE_MANIFEST:classpath:/public-assets/.vite/manifest.json}
    template-schema-version: 1
    public-base-url: ${PORTFOLIO_PUBLIC_BASE_URL:https://yychainsaw.xyz}
```

`PORTFOLIO_RELEASE_ID` is the Git commit plus Vite manifest SHA-256 assembled by the deployment plan. Empty release ID fails application startup outside the `test` profile. Create `backend-parent/portfolio-server/src/test/resources/application-test.yml` so every inherited `@ActiveProfiles("test")` integration context has a deterministic nonblank release and reads the manifest from the file created by this task:

```yaml
portfolio:
  render:
    release-id: ${PORTFOLIO_RELEASE_ID:test-release}
    vite-manifest: ${PORTFOLIO_VITE_MANIFEST:classpath:/vite/manifest.json}
```

Do not point tests at `classpath:/public-assets/.vite/manifest.json`; the test resource path is exactly `src/test/resources/vite/manifest.json` and therefore its classpath URI is exactly `classpath:/vite/manifest.json`.

- [ ] **Step 4: Implement safe initial JSON and manifest lookup**

```java
package xyz.yychainsaw.portfolio.publishing.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class SafeInitialJson {
    private final ObjectMapper mapper;
    public SafeInitialJson(ObjectMapper mapper) { this.mapper = mapper; }
    public String serialize(Object value) {
        try {
            return mapper.writeValueAsString(value)
                    .replace("&", "\\u0026")
                    .replace("<", "\\u003c")
                    .replace(">", "\\u003e")
                    .replace("\u2028", "\\u2028")
                    .replace("\u2029", "\\u2029");
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("cannot serialize initial portfolio data", error);
        }
    }
}
```

The server-side bootstrap record mirrors the browser union without untyped maps:

```java
package xyz.yychainsaw.portfolio.publishing.web;

import java.util.List;
import xyz.yychainsaw.portfolio.publicapi.PublicProjectCardDto;
import xyz.yychainsaw.portfolio.publicapi.PublicProjectDto;
import xyz.yychainsaw.portfolio.publicapi.PublicSiteDto;

public sealed interface PageBootstrap permits PageBootstrap.Home,
        PageBootstrap.Project, PageBootstrap.Privacy {
    String kind();
    String locale();

    record Home(String kind, String locale, PublicSiteDto site,
                List<PublicProjectCardDto> catalog) implements PageBootstrap {
        public Home {
            if (!"home".equals(kind)) throw new IllegalArgumentException("home bootstrap kind");
            catalog = List.copyOf(catalog);
        }
        public Home(String locale, PublicSiteDto site, List<PublicProjectCardDto> catalog) {
            this("home", locale, site, catalog);
        }
    }
    record Project(String kind, String locale, PublicSiteDto site,
            List<PublicProjectCardDto> catalog, PublicProjectDto project) implements PageBootstrap {
        public Project {
            if (!"project".equals(kind)) throw new IllegalArgumentException("project bootstrap kind");
            catalog = List.copyOf(catalog);
        }
        public Project(String locale, PublicSiteDto site, List<PublicProjectCardDto> catalog,
                       PublicProjectDto project) {
            this("project", locale, site, catalog, project);
        }
    }
    record Privacy(String kind, String locale, PublicSiteDto site) implements PageBootstrap {
        public Privacy {
            if (!"privacy".equals(kind)) throw new IllegalArgumentException("privacy bootstrap kind");
        }
        public Privacy(String locale, PublicSiteDto site) { this("privacy", locale, site); }
    }
}
```

`AssetManifestService` reads the configured Vite manifest once at startup and requires entry `src/main.ts`. Its public contract is exactly:

```java
public interface AssetManifestService {
    String entryJs();
    java.util.List<String> css();
}
```

The methods return `/assets/{hashed-filename}` public paths, and `css()` is immutable and preserves manifest order. Parse only the closed fields `file`, `src`, `isEntry`, and `css`; require `isEntry=true`, reject an absolute/scheme-bearing asset path, and fail startup on a missing manifest, entry, JS file, or CSS list. The test manifest is exactly:

```json
{
  "src/main.ts": {
    "file": "assets/index-test123.js",
    "src": "src/main.ts",
    "isEntry": true,
    "css": ["assets/index-test123.css"]
  }
}
```

- [ ] **Step 5: Write failing localized HTML contract tests**

```java
// PublicHtmlContractTest.java
@Test
void englishProjectHtmlContainsSeoInitialDataAndNoDraft() throws Exception {
    fixture.publishSiteAndProject();
    fixture.editProjectWorkspaceTitle("Draft only");
    mvc.perform(get("/en/projects/gameplay-prototype"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "public, no-cache"))
            .andExpect(content().string(containsString("<html lang=\"en\"")))
            .andExpect(content().string(containsString("rel=\"canonical\"")))
            .andExpect(content().string(containsString("hreflang=\"zh-CN\"")))
            .andExpect(content().string(containsString("application/ld+json")))
            .andExpect(content().string(containsString("id=\"__PORTFOLIO_DATA__\"")))
            .andExpect(content().string(containsString("Published title")))
            .andExpect(content().string(not(containsString("Draft only"))));
}
```

Also test `/` returns `302 /zh-CN`, an old slug returns `301 /{locale}/projects/{newSlug}`, an unknown slug returns `404`, and sitemap contains both locale URLs only for current published projects.

- [ ] **Step 6: Implement request-time localized rendering**

`PublicPageController` routes are exact:

```java
// PublicPageRenderer.java
package xyz.yychainsaw.portfolio.publishing.web;

import java.util.Optional;
import org.springframework.web.servlet.ModelAndView;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;

public interface PublicPageRenderer {
    PreparedPage home(LocaleCode locale);
    Optional<PreparedPage> project(LocaleCode locale, String slug);
    PreparedPage privacy(LocaleCode locale);
    record PreparedPage(String etag, ModelAndView view) {}
}
```

`SnapshotPublicPageRenderer` is a package-private `@Service` implementation in the same file. It reads only `PublicSnapshotQueryService`; builds `PageBootstrap.Home`, `.Project`, or `.Privacy`; serializes bootstrap and JSON-LD through `SafeInitialJson`; adds `site`, `catalog`, `project`, `canonical`, `zhUrl`, `enUrl`, `assets`, `initialJson`, and `structuredData` model attributes; and computes the relevant ETag from the exact envelope checksums plus `PublicRenderProperties.releaseId/templateSchemaVersion`. `project` returns empty only when no current published slug exists. It never catches an unknown snapshot schema or falls back to workspace data.

```java
// central methods in PublicPageController.java
@GetMapping("/")
ResponseEntity<Void> root() {
    return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/zh-CN")).build();
}

@GetMapping({"/zh-CN", "/en"})
ModelAndView home(HttpServletRequest request, HttpServletResponse response,
                  @RequestHeader(value = "If-None-Match", required = false) String inm) {
    return respond(pages.home(locale(request)), inm, response);
}

@GetMapping({"/zh-CN/projects/{slug}", "/en/projects/{slug}"})
ModelAndView project(@PathVariable String slug, HttpServletRequest request,
                     HttpServletResponse response,
                     @RequestHeader(value = "If-None-Match", required = false) String inm) {
    LocaleCode locale = locale(request);
    var page = pages.project(locale, slug);
    if (page.isPresent()) return respond(page.orElseThrow(), inm, response);
    var target = publishing.redirectTarget(slug);
    if (target.isEmpty()) throw notFound();
    RedirectView redirect = new RedirectView(
            "/" + locale.value() + "/projects/" + target.orElseThrow(), false, false);
    redirect.setExposeModelAttributes(false);
    redirect.setStatusCode(HttpStatus.MOVED_PERMANENTLY);
    return new ModelAndView(redirect);
}

@GetMapping({"/zh-CN/privacy", "/en/privacy"})
ModelAndView privacy(HttpServletRequest request,
                     HttpServletResponse response,
                     @RequestHeader(value = "If-None-Match", required = false) String inm) {
    return respond(pages.privacy(locale(request)), inm, response);
}

private ModelAndView respond(PublicPageRenderer.PreparedPage page, String inm,
                             HttpServletResponse response) {
    response.setHeader("Cache-Control", "public, no-cache");
    response.setHeader("ETag", page.etag());
    if (page.etag().equals(inm)) {
        response.setStatus(HttpStatus.NOT_MODIFIED.value());
        return null;
    }
    return page.view();
}

private LocaleCode locale(HttpServletRequest request) {
    return LocaleCode.from(request.getRequestURI().split("/", 3)[1]);
}
```

Inject `PublicPageRenderer pages` and `PublishingRepository publishing`; `notFound()` returns `DomainException("PUBLIC_CONTENT_NOT_FOUND", HttpStatus.NOT_FOUND, Map.of())`. Resolve locale from the first path segment, not `Accept-Language`. Home reads SITE and CATALOG; project reads SITE, CATALOG, and PROJECT; privacy reads SITE. Compute the exact composite ETag before rendering. A match returns `304` with no body. `redirectTarget` must join the redirect's project to its current `PUBLISHED` pointer and return that pointer's current slug, so an archived target is never redirected; lookup happens only after current-slug lookup fails and preserves locale.

- [ ] **Step 7: Create indexable Thymeleaf shells**

Every template includes matching title, description, canonical, paired hreflang, Open Graph, JSON-LD, current locale body copy, Vite CSS/JS, and inert initial JSON. Use this safe data carrier rather than an executable inline script:

```html
<template id="__PORTFOLIO_DATA__" th:utext="${initialJson}"></template>
```

The home body renders the published Hero headline/introduction and a semantic list of published project cards; project body renders title, summary, tags, and each visible content block; privacy body renders the localized privacy title and sanitized Markdown output. Vue may replace/enhance this markup after reading the template, but a no-JavaScript crawler still receives meaningful headings, links, image alt text, and body copy.

`home.html` begins with:

```html
<!doctype html>
<html th:lang="${locale}" lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title th:text="${site.seo.title}">Portfolio</title>
  <meta name="description" th:content="${site.seo.description}">
  <link rel="canonical" th:href="${canonical}">
  <link rel="alternate" hreflang="zh-CN" th:href="${zhUrl}">
  <link rel="alternate" hreflang="en" th:href="${enUrl}">
  <meta property="og:type" content="website">
  <meta property="og:title" th:content="${site.seo.title}">
  <meta property="og:description" th:content="${site.seo.description}">
  <meta property="og:url" th:content="${canonical}">
  <meta property="og:image" th:content="${site.hero.media.src}">
  <link th:each="css : ${assets.css}" rel="stylesheet" th:href="${css}">
  <script type="application/ld+json" th:utext="${structuredData}"></script>
</head>
<body>
  <main id="app">
    <section aria-labelledby="hero-title">
      <h1 id="hero-title" th:text="${site.hero.headline}">Portfolio</h1>
      <p th:text="${site.hero.introduction}"></p>
    </section>
    <section aria-labelledby="projects-title">
      <h2 id="projects-title" th:text="${site.work.title}">Projects</h2>
      <article th:each="project : ${catalog}">
        <h3><a th:href="@{'/' + ${locale} + '/projects/' + ${project.slug}}"
               th:text="${project.title}"></a></h3>
        <p th:text="${project.summary}"></p>
      </article>
    </section>
  </main>
  <template id="__PORTFOLIO_DATA__" th:utext="${initialJson}"></template>
  <script type="module" th:src="${assets.entryJs}"></script>
</body>
</html>
```

`project.html` uses the project's own SEO fields and renders every public block subtype for no-JavaScript clients:

```html
<!doctype html>
<html th:lang="${locale}" lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title th:text="${project.seoTitle}">Project</title>
  <meta name="description" th:content="${project.seoDescription}">
  <link rel="canonical" th:href="${canonical}">
  <link rel="alternate" hreflang="zh-CN" th:href="${zhUrl}">
  <link rel="alternate" hreflang="en" th:href="${enUrl}">
  <meta property="og:type" content="article">
  <meta property="og:title" th:content="${project.seoTitle}">
  <meta property="og:description" th:content="${project.seoDescription}">
  <meta property="og:url" th:content="${canonical}">
  <meta property="og:image" th:if="${!project.media.isEmpty()}"
        th:content="${project.media[0].src}">
  <link th:each="css : ${assets.css}" rel="stylesheet" th:href="${css}">
  <script type="application/ld+json" th:utext="${structuredData}"></script>
</head>
<body>
  <main id="app">
    <article>
      <h1 th:text="${project.title}">Project</h1>
      <p th:text="${project.summary}"></p>
      <ul aria-label="Tags"><li th:each="tag : ${project.tags}" th:text="${tag}"></li></ul>
      <section th:each="block : ${project.blocks}" th:switch="${block.type}">
        <div th:case="'MARKDOWN'" th:utext="${block.payload.html}"></div>
        <figure th:case="'IMAGE'"><img th:src="${block.payload.media.src}"
          th:srcset="${block.payload.media.srcset}" th:alt="${block.payload.media.alt}">
          <figcaption><span th:text="${block.payload.media.caption}"></span>
            <span th:text="${block.payload.media.credit}"></span>
            <a th:if="${!block.payload.media.sourceUrl.isEmpty()}"
               th:href="${block.payload.media.sourceUrl}" rel="noreferrer noopener">Source</a>
          </figcaption></figure>
        <div th:case="'GALLERY'" role="group"><figure th:each="item : ${block.payload.media}">
          <img th:src="${item.src}" th:srcset="${item.srcset}" th:alt="${item.alt}">
          <figcaption><span th:text="${item.caption}"></span><span th:text="${item.credit}"></span>
            <a th:if="${!item.sourceUrl.isEmpty()}" th:href="${item.sourceUrl}"
               rel="noreferrer noopener">Source</a></figcaption></figure></div>
        <div th:case="'VIDEO'"><figure th:if="${block.payload.cover != null}">
          <img th:src="${block.payload.cover.src}" th:srcset="${block.payload.cover.srcset}"
               th:alt="${block.payload.cover.alt}"><figcaption>
            <span th:text="${block.payload.cover.credit}"></span>
            <a th:if="${!block.payload.cover.sourceUrl.isEmpty()}"
               th:href="${block.payload.cover.sourceUrl}" rel="noreferrer noopener">Source</a>
          </figcaption></figure><p><a th:href="${block.payload.embedUrl}"
          th:text="${block.payload.title}" rel="noreferrer noopener"></a></p></div>
        <pre th:case="'CODE'"><code th:text="${block.payload.code}"></code></pre>
        <blockquote th:case="'QUOTE'"><p th:text="${block.payload.quote}"></p>
          <cite th:text="${block.payload.source}"></cite></blockquote>
        <dl th:case="'METRICS'"><th:block th:each="metric : ${block.payload.metrics}">
          <dt th:text="${metric.label}"></dt><dd th:text="${metric.value + metric.suffix}"></dd>
        </th:block></dl>
        <p th:case="'DOWNLOAD'"><a th:href="${block.payload.href}"
          th:text="${block.payload.label}"></a>
          <span th:if="${block.payload.mimeType != null}"
                th:text="${block.payload.mimeType + ' · ' + block.payload.byteSize + ' bytes'}"></span></p>
        <p th:case="'LINK'"><a th:href="${block.payload.href}"
          th:text="${block.payload.label}" rel="noreferrer noopener"></a></p>
      </section>
    </article>
  </main>
  <template id="__PORTFOLIO_DATA__" th:utext="${initialJson}"></template>
  <script type="module" th:src="${assets.entryJs}"></script>
</body>
</html>
```

`privacy.html` uses the same canonical/hreflang/Open Graph/JSON-LD/Vite tags and this body:

```html
<main id="app">
  <article>
    <h1 th:text="${site.privacy.title}">Privacy</h1>
    <div th:utext="${site.privacy.html}"></div>
  </article>
</main>
<template id="__PORTFOLIO_DATA__" th:utext="${initialJson}"></template>
<script type="module" th:src="${assets.entryJs}"></script>
```

The only `th:utext` values are `initialJson`/`structuredData` from `SafeInitialJson` and `PublicBlockDto.Markdown`/privacy HTML from `SafeMarkdownRenderer`. Code, quotes, metrics, captions, titles, and link labels always use `th:text`; no workspace field or client-provided HTML reaches `th:utext` directly.

- [ ] **Step 8: Implement dynamic sitemap and static robots**

`GET /sitemap.xml` reads the current PROJECT_CATALOG pointer at request time, emits `/zh-CN`, `/en`, both locale URLs for each current card, and `/zh-CN/privacy` plus `/en/privacy`. Set `Content-Type: application/xml`, `Cache-Control: public, no-cache`, and ETag `hash("sitemap", catalogChecksum)`. `robots.txt` is:

```text
User-agent: *
Allow: /
Disallow: /admin
Disallow: /api/admin
Sitemap: https://yychainsaw.xyz/sitemap.xml
```

- [ ] **Step 9: Run HTML, redirect, sitemap, and cache-matrix tests**

Run from `backend-parent/`:

```powershell
.\mvnw.cmd -pl portfolio-server -am -Dtest=PublicHtmlContractTest,CompositeEtagServiceTest test
```

Expected: PASS; both locales have canonical/hreflang/OG/JSON-LD, draft text is absent, matching composite ETag returns `304`, each dependency change returns `200` with a new ETag, old slug is `301`, and sitemap lists only current publications.

- [ ] **Step 10: Commit the SEO renderer**

```powershell
git add backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/publishing backend-parent/portfolio-server/src/main/resources/templates/public backend-parent/portfolio-server/src/main/resources/static/robots.txt backend-parent/portfolio-server/src/main/resources/application.yml backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/publishing/web backend-parent/portfolio-server/src/test/resources/application-test.yml backend-parent/portfolio-server/src/test/resources/vite/manifest.json
git commit -m "feat(public): render localized SEO pages"
```

## Plan 05 public-site handoff contract

Plan 03 owns the one-time `portfolio.ts` exporter, Java public DTOs, published JSON endpoints, publication-gated media URLs, server `PageBootstrap`, Thymeleaf first-response rendering, composite ETags, redirects, sitemap, and the server-side Vite-manifest reader. It does not create or modify Vue runtime types, stores, routes, views, renderers, SEO synchronization, Vitest/Playwright configuration, or public-site CSS; those files and browser behaviors belong exclusively to plan 05.

Plan 05 must mirror, without renaming or reshaping, Task 10's `PublishedEnvelope<T>`, `PublicSiteDto`, `PublicMediaDto`, `PublicProjectCardDto`, `PublicProjectDto`, and `PublicBlockDto`, plus Task 11's `PageBootstrap` union. `PublicMediaDto` includes non-null string `credit` and `sourceUrl` fields (missing source data is `""`), and IMAGE/GALLERY/VIDEO-cover UI exposes attribution/source links when present. DOWNLOAD payloads expose `mimeType: string | null` and `byteSize: number | null`; both are non-null for media-backed downloads and null for external HTTPS downloads, and the public UI displays them when present. Plan 05 must use stable media paths of the form `/api/public/media/{assetId}/{variant}`, preserve the nested polymorphic `payload.type` emitted by Jackson, and produce `frontend/dist/.vite/manifest.json` with entry `src/main.ts`. Plan 03 consumes that manifest through configuration but never builds or copies frontend assets.

### Task 12: End-to-end content publication acceptance gate

**Files:**
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/publishing/ContentPublishingAcceptanceTest.java`
- Create: `backend-parent/portfolio-server/src/test/resources/import/portfolio-v1-publishable.json`

**Interfaces:**
- Consumes: every Task 1-11 deliverable and the plan 01/02 implementations.
- Produces: one repeatable server acceptance gate proving import, draft isolation, atomic publish, restore, public media, localized HTML, and the plan 05 handoff contracts.

- [ ] **Step 1: Add a failing backend acceptance test**

```java
// backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/publishing/ContentPublishingAcceptanceTest.java
package xyz.yychainsaw.portfolio.publishing;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.content.importer.PortfolioImportService;
import xyz.yychainsaw.portfolio.content.persistence.ProjectWorkspaceRepository;
import xyz.yychainsaw.portfolio.publishing.api.PublishProjectCommand;
import xyz.yychainsaw.portfolio.publishing.application.PublicSnapshotQueryService;
import xyz.yychainsaw.portfolio.publishing.application.PublicationService;
import xyz.yychainsaw.portfolio.publishing.application.RestoreService;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
@ActiveProfiles("test")
@org.springframework.transaction.annotation.Transactional
class ContentPublishingAcceptanceTest extends PostgresIntegrationTestBase {
    @Autowired PortfolioImportService importer;
    @Autowired PublicationService publications;
    @Autowired PublicSnapshotQueryService publicQueries;
    @Autowired ProjectWorkspaceRepository projects;
    @Autowired RestoreService restore;

    @Test
    void importPublishEditRestoreRepublishKeepsPublicStateAtomic() throws Exception {
        Path input = Path.of("src/test/resources/import/portfolio-v1-publishable.json");
        var report = importer.commit(
                input, Path.of("src/test/resources/import/assets"), sha256(input));
        assertThat(report.committed()).isTrue();
        var project = projects.findAll().get(0);
        var first = publications.publishProject(new PublishProjectCommand(
                project.id(), project.version(), 0L, 0L));
        String firstTitle = publicQueries.project(project.slug(), LocaleCode.EN).data().title();

        var edited = xyz.yychainsaw.portfolio.content.support.WorkspaceFixtures
                .withEnglishTitle(projects.require(project.id()), "Unpublished edit");
        projects.replace(edited, project.version());
        assertThat(publicQueries.project(project.slug(), LocaleCode.EN).data().title()).isEqualTo(firstTitle);

        restore.restore(first.revisionId(), projects.require(project.id()).version());
        assertThat(publicQueries.project(project.slug(), LocaleCode.EN).data().title()).isEqualTo(firstTitle);
        var restored = projects.require(project.id());
        assertThat(restored.publicationDirty()).isTrue();
        publications.publishProject(new PublishProjectCommand(
                restored.id(), restored.version(), first.aggregateVersion(), first.catalogVersion()));
        assertThat(publicQueries.project(project.slug(), LocaleCode.EN).data().title()).isEqualTo(firstTitle);
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
```

Build `portfolio-v1-publishable.json` from the Task 5 valid fixture, replacing all seven fixed placeholder values with final non-placeholder bilingual copy while preserving the same project/media IDs and `/images/{filename}` paths. The test uses Task 5's isolated asset directory and never depends on the frontend module at test runtime.

- [ ] **Step 2: Run the full backend slice**

Run from `backend-parent/`:

```powershell
.\mvnw.cmd -pl portfolio-server -am clean verify
```

Expected: exit `0`; Flyway builds V1-V5 from empty PostgreSQL, import/publish/concurrency/restore/public API/media/HTML/cache/security tests all pass, and `ContentPublishingAcceptanceTest` passes.

- [ ] **Step 3: Run the real-source import dry-run**

Run from `frontend/`:

```powershell
npm run export:portfolio
```

Then run from repository root:

```powershell
$sha = (Get-FileHash 'runtime/import/portfolio-v1.json' -Algorithm SHA256).Hash.ToLowerInvariant()
java -jar backend-parent/portfolio-server/target/portfolio-server.jar --portfolio.cli.command=import --portfolio.import.input=runtime/import/portfolio-v1.json --portfolio.import.asset-root=frontend/public --portfolio.import.sha256=$sha --portfolio.import.commit=false
```

Expected: exit `0`; report is `committed:false`, `projectCount:3`, has no structure errors, and lists exactly the seven approved publish-warning paths.

- [ ] **Step 4: Commit the acceptance gate**

```powershell
git add backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/publishing/ContentPublishingAcceptanceTest.java backend-parent/portfolio-server/src/test/resources/import/portfolio-v1-publishable.json
git commit -m "test(publishing): add content acceptance gate"
```

## Final verification checklist

- [ ] `git status --short` shows only intentional implementation files; no generated `runtime/import/portfolio-v1.json`, secrets, build output, or database volume is staged.
- [ ] `git log --oneline -12` shows one commit per task in the documented order.
- [ ] `V4__content_workspace.sql` and `V5__publishing.sql` apply from an empty PostgreSQL 17 container.
- [ ] Exporting the unchanged `portfolio.ts` twice produces byte-identical JSON and SHA-256.
- [ ] Dry-run performs no database/media write; every structure error produces zero content rows.
- [ ] Required locale keys/fields cannot be omitted; present blank translations import as stably ordered `IMPORT_TRANSLATION_INCOMPLETE` warnings and still cannot publish.
- [ ] SITE cannot publish while any of the seven fixed placeholder paths retains its approved placeholder value.
- [ ] Editing a published workspace never changes JSON APIs, HTML, sitemap, or media authorization until publication commits.
- [ ] PROJECT and PROJECT_CATALOG pointers move in one transaction; stale catalog version returns `409` with no lost update.
- [ ] Restore copies SITE/PROJECT history into a new dirty draft and never moves a publication pointer.
- [ ] Current public media reads succeed; draft, old-revision, archived, and random UUID reads return `404`.
- [ ] Localized media alt/caption/credit/sourceUrl survive descriptor-to-snapshot-to-public projection, and IMAGE/GALLERY/VIDEO covers expose safe attribution without storage details.
- [ ] `ContentMediaReferenceChecker` reports every normalized workspace FK plus every retained `revision_media_reference`; plan 02 cleanup deletes only when that combined result is empty.
- [ ] Plan 02 media translation/metadata changes invoke `ContentMediaChangeListener` in the same transaction, bump SITE and every affected PROJECT workspace exactly once, and never move a published pointer.
- [ ] `CurrentPublicationQuery` returns true only for a current PUBLISHED project present in the current PUBLISHED catalog, while `ProjectLabelQuery` resolves current/archived titles and returns empty for missing IDs.
- [ ] Home/project/privacy ETags include every documented content and release dependency.
- [ ] Both locale pages contain matching canonical, hreflang, Open Graph, JSON-LD, indexable body text, and inert initial JSON.
- [ ] Plan 05's TypeScript contract mirrors the Java public DTOs/bootstrap and is the sole owner of Vue routes, runtime fetches, visual rendering, and browser tests.
