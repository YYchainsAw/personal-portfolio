# Portfolio Deployment, Backup, and Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship one traceable portfolio release to the Ubuntu 22.04 Tencent Lighthouse host behind BaoTa Nginx, retain safe rollback paths, and prove that PostgreSQL, media, API, public pages, and admin can be restored within RPO 24 hours and RTO 4 hours.

**Architecture:** Docker builds the public frontend, admin frontend, Spring Boot image, and portable PostgreSQL image archive from one Git commit and one reviewed image lock. Content-addressed public assets are copied into a shared host directory while admin releases remain immutable and switch by symlink. Production Compose runs only `portfolio-api` and PostgreSQL; BaoTa's host Nginx owns public TLS and reverse proxying. A host-side snapshot keeper holds the plan-02 PostgreSQL advisory barrier, exports one MVCC snapshot to the database dump and media-manifest readers, copies a closed set of actual media bytes, and publishes immutable encrypted backup sets through separate uploader/verifier/pruner credentials before recording redacted outcomes.

**Tech Stack:** Ubuntu 22.04, Docker Engine/Compose 26, BaoTa Nginx, Java 17, Spring Boot 3.5.7, Node.js 22.18, admin Vite 8.1.5, existing public Vite 8.1.4, PostgreSQL 17, Bash, systemd, age, rclone, curl, OpenSSL.

## Global Constraints

- Complete plans 01–06 before the public production cutover. This phase may be developed earlier, but smoke tests must target the final contracts.
- Production Compose contains exactly `portfolio-api` and `postgres`. Do not run a second public Nginx, Redis, a queue, or an admin database UI.
- PostgreSQL has no host/public port. The API binds only `127.0.0.1:18080`; BaoTa Nginx is the only public entry point.
- Use the plan-03 manifest contract: `frontend/dist/.vite/manifest.json` is copied into the Spring image at classpath `/public-assets/.vite/manifest.json`; the matching `releaseId` is passed as `PORTFOLIO_RELEASE_ID`.
- `npm --prefix frontend run build` outputs `frontend/dist`; `npm --prefix admin-web run build` outputs `admin-web/dist` with base `/admin/`.
- Every release is built from one clean Git commit. `releaseId` remains `{12-char Git SHA}-{12-char SHA-256 prefix of the public Vite manifest}`; release acceptance additionally binds the final API OCI image ID/repository digest, server JAR SHA-256, admin/public/operations canonical tree hashes, API/PostgreSQL image-archive hashes, and canonical bundle-payload hash.
- Prefer building and testing on the development workstation or a trusted CI runner with sufficient RAM. The lightweight production host installs a checksummed static/operations bundle plus API and pinned PostgreSQL 17 image archives and does not need Node, Maven, source code, Testcontainers, or registry availability; an on-host build is allowed only when preflight confirms capacity and uses the same contract.
- Never overwrite or delete a hash-named public asset during deployment. Keep manifests and files needed by the latest three releases before garbage collection.
- Secrets live under `/etc/portfolio` with root-only permissions or in BaoTa's protected configuration, never in Git, image layers, release metadata, shell history, or logs.
- BaoTa Nginx must overwrite client forwarding headers. Spring trusts forwarded headers only from the loopback reverse proxy.
- `yychainsaw.xyz` ICP is currently in final review. For a mainland-China server, public DNS/domain cutover remains blocked until the approval result and ICP number are recorded. Building, local-IP smoke tests, backups, and private staging are allowed before that gate.
- Database migrations are forward-compatible. Rollback switches application/static release only; never run an automatic Flyway down migration.
- Database and media backup must continue when the API is down. A manifest without an independent object copy is not a media backup.
- Backup targets use a separate bucket and credential; prefer a different region or account. TOTP master encryption and backup decryption keys remain outside the server.
- One backup set uses PostgreSQL session advisory keys `(1347375700,1296385097)`: the snapshot keeper holds `pg_advisory_lock_shared` on a dedicated keeper connection from snapshot export through media closure publication, while plan 02's `ArchivedMediaCleanupJobHandler` holds `pg_advisory_lock` through every physical Local/COS deletion. Never replace either side with a transaction-scoped or JVM-only lock.
- Media backup always follows provider values in the exported database manifest. A single set can and normally will contain both `LOCAL` and `TENCENT_COS`; changing the default upload provider never excludes old-provider bytes.
- Backup remote uploader, verifier, and retention-pruner credentials are distinct. Retained backup blobs are versioned/object-locked where supported, and no delete command may operate until account, bucket, non-root prefix, retention manifests, and credential role are all proven.
- Every deployable release commit, including tracked no-secret Compose/Nginx templates and runbooks, must be anchored by an immutable protected tag in a private remote Git repository. The server does not store a source checkout or Git credential.
- Before any restore script deletes or replaces data, it must prove the target is an isolated drill path and never the production volume.
- Every task uses a failing contract check first, verifies the exact success condition, and ends with a focused commit.

## File Map

- `Dockerfile`: multi-stage source tests, frontend builds, Spring image, and static release export.
- `.dockerignore`: excludes Git metadata, local secrets, build output, IDE state, and media data.
- `deploy/docker-compose.prod.yml`: API and PostgreSQL only.
- `deploy/.env.example`: names and safe defaults only.
- `deploy/image-lock.env`: reviewed image tags used by builds and Compose.
- `deploy/nginx/portfolio-http.conf`: `http`-scope rate-limit zones.
- `deploy/nginx/portfolio-site.conf.example`: BaoTa site locations, static assets, proxy, headers, and TLS integration notes.
- `deploy/scripts/build-release.sh`: one-commit release builder.
- `deploy/scripts/package-release.sh`: portable static/operations plus API/PostgreSQL image archives and checksum manifest for the lightweight host.
- `deploy/scripts/install-release-bundle.sh`: checksum/path-safe production-host bundle installation.
- `deploy/scripts/preflight.sh`: host, region, ICP, secrets, disk, and dependency gates.
- `deploy/scripts/deploy-release.sh`: backup, asset copy, migration/start, smoke, and atomic admin switch.
- `deploy/scripts/rollback-release.sh`: application/admin rollback without database reversal.
- `deploy/scripts/smoke.sh`: distinct API-loopback, local-Nginx, and public-domain route checks.
- `deploy/scripts/prune-releases.sh`: safe three-release retention and asset reachability cleanup.
- `deploy/backup/*`: database/media backup, verification, retention, remote copy, and failure notification.
- `deploy/systemd/*`: backup and maintenance service/timer units.
- `deploy/restore/*`: isolated restore Compose file and quarterly drill script.
- `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/system/operations/*`: authenticated operational status read API.
- `docs/operations/production-runbook.md`: install, deploy, rollback, DNS/ICP, and incident procedures.
- `docs/operations/backup-recovery.md`: RPO/RTO, key custody, backup, verification, and drill evidence.

---

### Task 1: Build all artifacts from one commit and one manifest

**Files:**
- Create: `Dockerfile`
- Create: `.dockerignore`
- Create: `deploy/image-lock.env`
- Create: `deploy/scripts/build-release.sh`
- Create: `deploy/scripts/package-release.sh`
- Create: `deploy/tests/release-artifact-contract.sh`

**Interfaces:**
- Consumes: plan-01 Maven project, plan-04 `admin-web`, plan-05 `frontend`, plan-03 classpath manifest path, and the reviewed immutable PostgreSQL 17 digest.
- Produces: image `portfolio-api:{releaseId}`, immutable `{admin,public-assets,ops,images,release.json,bundle-manifest.json}`, and a portable checksummed bundle containing static/operations files plus compressed API and PostgreSQL image archives.

- [ ] **Step 1: Write the failing release artifact contract**

```bash
#!/usr/bin/env bash
set -euo pipefail

release_dir="${1:?release directory required}"
release_id="${2:?release id required}"

canonical_tree_sha() {
  local root="${1:?tree root required}"
  (
    cd "$root"
    find . -type f -print0 | LC_ALL=C sort -z |
      while IFS= read -r -d '' path; do
        printf '%s\0%s\0' "${path#./}" "$(sha256sum -- "$path" | awk '{print $1}')"
      done
  ) | sha256sum | awk '{print $1}'
}

test -f "$release_dir/admin/index.html"
test -d "$release_dir/admin/assets"
test -f "$release_dir/public-assets/.vite/manifest.json"
test -d "$release_dir/public-assets/assets"
test -d "$release_dir/ops/deploy"
test -f "$release_dir/images/portfolio-api.oci.tar.zst"
test -f "$release_dir/images/postgres-17.oci.tar.zst"
test -f "$release_dir/release.json"
test -f "$release_dir/bundle-manifest.json"
jq -e --arg id "$release_id" '
  .releaseId == $id and
  (.gitCommit | test("^[0-9a-f]{40}$")) and
  (.manifestSha256 | test("^[0-9a-f]{64}$")) and
  (.sourceContinuityRef | startswith("refs/tags/portfolio-release/")) and
  (.apiImageId | test("^sha256:[0-9a-f]{64}$")) and
  ((.apiImageRepoDigest == null) or (.apiImageRepoDigest | test("@sha256:[0-9a-f]{64}$"))) and
  (.postgresImageRef | test("@sha256:[0-9a-f]{64}$")) and
  (.postgresImageId | test("^sha256:[0-9a-f]{64}$")) and
  ([.jarSha256,.adminTreeSha256,.publicTreeSha256,.opsTreeSha256,
    .apiImageArchiveSha256,.postgresImageArchiveSha256,.bundlePayloadSha256]
   | all(test("^[0-9a-f]{64}$")))
' \
  "$release_dir/release.json" >/dev/null

test "$(docker image inspect --format '{{.Id}}' "portfolio-api:$release_id")" = \
  "$(jq -r '.apiImageId' "$release_dir/release.json")"
test "$(docker image inspect --format '{{.Id}}' "$(jq -r '.postgresImageRef' "$release_dir/release.json")")" = \
  "$(jq -r '.postgresImageId' "$release_dir/release.json")"
container_id="$(docker create "portfolio-api:$release_id")"
jar_path="$(mktemp)"
trap 'docker rm -f "$container_id" >/dev/null 2>&1 || true; rm -f "$jar_path"' EXIT
docker cp "$container_id:/app/portfolio-server.jar" "$jar_path"
actual_manifest="$(unzip -p "$jar_path" BOOT-INF/classes/public-assets/.vite/manifest.json | sha256sum | cut -d' ' -f1)"
expected_manifest="$(sha256sum "$release_dir/public-assets/.vite/manifest.json" | cut -d' ' -f1)"
test "$actual_manifest" = "$expected_manifest"
test "$(sha256sum "$jar_path" | awk '{print $1}')" = "$(jq -r '.jarSha256' "$release_dir/release.json")"
test "$(canonical_tree_sha "$release_dir/admin")" = "$(jq -r '.adminTreeSha256' "$release_dir/release.json")"
test "$(canonical_tree_sha "$release_dir/public-assets")" = "$(jq -r '.publicTreeSha256' "$release_dir/release.json")"
test "$(canonical_tree_sha "$release_dir/ops")" = "$(jq -r '.opsTreeSha256' "$release_dir/release.json")"
test "$(sha256sum "$release_dir/images/portfolio-api.oci.tar.zst" | awk '{print $1}')" = \
  "$(jq -r '.apiImageArchiveSha256' "$release_dir/release.json")"
test "$(sha256sum "$release_dir/images/postgres-17.oci.tar.zst" | awk '{print $1}')" = \
  "$(jq -r '.postgresImageArchiveSha256' "$release_dir/release.json")"
payload_sha="$(jq -Sc '{releaseId,gitCommit,manifestSha256,sourceContinuityRef,apiImageId,
  apiImageRepoDigest,postgresImageRef,postgresImageId,jarSha256,adminTreeSha256,
  publicTreeSha256,opsTreeSha256,apiImageArchiveSha256,postgresImageArchiveSha256}' \
  "$release_dir/release.json" | sha256sum | awk '{print $1}')"
test "$payload_sha" = "$(jq -r '.bundlePayloadSha256' "$release_dir/release.json")"
(cd "$release_dir" && sha256sum --check --strict bundle-manifest.json)
```

`canonical_tree_sha` hashes the NUL-delimited sequence of normalized relative file path plus file SHA-256 under `LC_ALL=C`; it never includes timestamps, ownership, directory metadata, symlinks, or untracked files. `bundlePayloadSha256` is the canonical JSON identity hash shown above, which avoids a self-referential `release.json`; the final outer `.tar.zst` additionally has a detached SHA-256 sidecar verified before extraction.

- [ ] **Step 2: Run the contract and verify it fails**

On Ubuntu or WSL from repository root:

```bash
bash deploy/tests/release-artifact-contract.sh /tmp/missing-release missing-id
```

Expected: non-zero exit because no release exists.

- [ ] **Step 3: Create the multi-stage Dockerfile**

Use these named stages and contracts:

```dockerfile
ARG NODE_IMAGE=node:22.18.0-bookworm-slim
ARG JAVA_BUILD_IMAGE=eclipse-temurin:17-jdk-jammy
ARG JAVA_RUNTIME_IMAGE=eclipse-temurin:17-jre-jammy

FROM ${NODE_IMAGE} AS public-deps
WORKDIR /src/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci

FROM public-deps AS public-test
COPY frontend/ ./
RUN npm run test -- --run && npm run type-check

FROM public-deps AS public-build
COPY frontend/ ./
RUN npm run build

FROM ${NODE_IMAGE} AS admin-deps
WORKDIR /src/admin-web
COPY admin-web/package.json admin-web/package-lock.json ./
RUN npm ci

FROM admin-deps AS admin-test
COPY admin-web/ ./
RUN npm run test -- --run && npm run type-check

FROM admin-deps AS admin-build
COPY admin-web/ ./
RUN npm run build

FROM ${JAVA_BUILD_IMAGE} AS server-test
WORKDIR /src/backend-parent
COPY backend-parent/ ./
RUN ./mvnw -B verify

FROM ${JAVA_BUILD_IMAGE} AS server-build
WORKDIR /src/backend-parent
COPY backend-parent/ ./
COPY --from=public-build /src/frontend/dist/.vite/manifest.json \
  portfolio-server/src/main/resources/public-assets/.vite/manifest.json
RUN ./mvnw -B -pl portfolio-server -am -DskipTests package

FROM scratch AS release-files
COPY --from=public-build /src/frontend/dist/ /public-assets/
COPY --from=admin-build /src/admin-web/dist/ /admin/

FROM ${JAVA_RUNTIME_IMAGE} AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --create-home portfolio
WORKDIR /app
COPY --from=server-build --chown=portfolio:portfolio \
  /src/backend-parent/portfolio-server/target/portfolio-server.jar /app/portfolio-server.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/portfolio-server.jar"]
```

If the Maven artifact name differs after plan 01, configure `<finalName>portfolio-server</finalName>` there rather than using a wildcard copy. The test stage requires Testcontainers access to the Docker socket when run on the build host; if BuildKit cannot expose it safely, run `./mvnw verify` in the checked-out workspace before the image build and keep `server-test` for CI with an approved runner.

- [ ] **Step 4: Implement deterministic release assembly**

`build-release.sh` must:

1. require a clean tracked worktree and a commit reachable by `HEAD`;
2. pull each reviewed base tag once, resolve its immutable repository digest, and pass that full digest to every Docker target in this run;
3. build `public-test`, `admin-test`, and the Maven verification target;
4. export `release-files` to a temporary directory beside `${PORTFOLIO_RELEASE_ROOT}`;
5. copy every tracked no-secret file currently present beneath `deploy/` and `docs/operations/`, plus `README.md`, into `ops/` with normalized relative paths; reject tracked `.env`, key, certificate, credential, media, database, or backup material rather than silently omitting it;
6. compute the full SHA-256 of `public-assets/.vite/manifest.json` and form the unchanged `releaseId` from Git SHA and manifest hash prefixes;
7. build `portfolio-api:{releaseId}` from the same checkout and resolved base digests, and inspect its mandatory content-addressed `.Id`; record a repository digest as well when registry transport is configured;
8. require configured private remote `SOURCE_CONTINUITY_REMOTE` to expose protected tag `refs/tags/portfolio-release/{releaseId}` at exactly that full commit; fail with a deterministic operator instruction when the tag is absent or differs, never push automatically or store a Git credential on the server;
9. pull PostgreSQL by the reviewed `postgres:17-bookworm@sha256:...` reference, inspect its content ID, export both API and PostgreSQL images with `docker save | zstd` into `images/`, and prove disposable `docker load` round trips reproduce the recorded image IDs without changing production tags;
10. copy the API JAR out of a disposable container, compute its SHA-256, compute canonical admin/public/operations tree hashes, and compute both image-archive hashes;
11. create `release.json` with release ID, full commit, full manifest hash, protected source-continuity ref, API image tag/content ID/optional repository digest, PostgreSQL digest reference/content ID, JAR hash, all three tree hashes, both image-archive hashes, canonical `bundlePayloadSha256`, and UTC build time;
12. create `bundle-manifest.json` as GNU `sha256sum --check` entries for every regular file beneath `admin/`, `public-assets/`, `ops/`, and `images/`, but not `release.json` or `bundle-manifest.json`; separately record `release.json` SHA-256 in the detached outer-bundle envelope to avoid circular hashing;
13. run `release-artifact-contract.sh` against the staging directory, then move it atomically beneath `${PORTFOLIO_RELEASE_ROOT}` (default `artifacts/releases` on a build runner; `/opt/portfolio/releases` only for an explicitly approved on-host build);
14. print only the release ID.

Use a staging directory on the same filesystem as `PORTFOLIO_RELEASE_ROOT`, `umask 027`, a trap that removes only the resolved staging path, and `mv` on that filesystem. Refuse to replace an existing release directory unless every recorded hash and image ID is identical. `package-release.sh` creates one outer `portfolio-{releaseId}.tar.zst`, a detached envelope containing archive SHA-256, byte size, release ID, and `release.json` SHA-256, then extracts into a disposable root, loads both images into disposable tags, and reruns the complete artifact contract before declaring the bundle ready. An exact-tag recovery rebuild must match every recorded digest/tree/archive field, not merely `releaseId` and public manifest; a mismatch means the rebuild is not the original release and must not be deployed.

- [ ] **Step 5: Create `.dockerignore` and image lock**

Exclude `.git`, `.idea`, `.env`, `*.key`, `*.pem`, `node_modules`, `dist`, `target`, local media, database volumes, logs, and Codex visualization output. `deploy/image-lock.env` contains reviewed public image tags only:

```dotenv
NODE_IMAGE=node:22.18.0-bookworm-slim
JAVA_BUILD_IMAGE=eclipse-temurin:17-jdk-jammy
JAVA_RUNTIME_IMAGE=eclipse-temurin:17-jre-jammy
POSTGRES_IMAGE=postgres:17-bookworm
```

Record resolved immutable digests and only the protected tag name—not a credential-bearing remote URL—in `release.json`. The tagged commit contains `.dockerignore`, Compose/Nginx templates, scripts, and runbooks, so it is the no-secret source/config continuity copy required for recovery. Write the chosen PostgreSQL digest into the protected release env so `docker compose` starts the reviewed major-17 image, rather than resolving a moving tag during deployment.

The same PostgreSQL digest is mandatory in `images/postgres-17.oci.tar.zst`; deployment verifies the loaded local image ID before Compose starts it and does not require registry availability.

- [ ] **Step 6: Build a real release and run the contract**

```bash
export PORTFOLIO_RELEASE_ROOT="$PWD/artifacts/releases"
install -d "$PORTFOLIO_RELEASE_ROOT"
release_id="$(bash deploy/scripts/build-release.sh)"
bash deploy/tests/release-artifact-contract.sh "$PORTFOLIO_RELEASE_ROOT/$release_id" "$release_id"
bash deploy/scripts/package-release.sh "$release_id"
```

Expected: PASS; the API/JAR/admin/public/operations identities and both API/PostgreSQL image archives match exactly, and the detached outer-bundle checksum verifies after a disposable extraction/load round trip.

- [ ] **Step 7: Commit the build slice**

```bash
git add Dockerfile .dockerignore deploy/image-lock.env deploy/scripts/build-release.sh deploy/scripts/package-release.sh deploy/tests/release-artifact-contract.sh
git commit -m "build: create traceable portfolio releases"
```

---

### Task 2: Define production Compose, secrets, and local-only health

**Files:**
- Create: `deploy/docker-compose.prod.yml`
- Modify: `deploy/.env.example`
- Modify: `backend-parent/portfolio-server/src/main/resources/application-prod.yml`
- Create: `deploy/tests/compose-contract.sh`

**Interfaces:**
- Consumes: plan-01 application profiles, plan-02 Local/COS configuration, plan-06 SMTP/HMAC configuration.
- Produces: two-container production topology and `127.0.0.1:18080` API boundary.

- [x] **Step 1: Write the failing Compose contract**

```bash
#!/usr/bin/env bash
set -euo pipefail
config="$(docker compose --env-file deploy/tests/compose.test.env -f deploy/docker-compose.prod.yml config --format json)"
test "$(jq '.services | keys | sort' <<<"$config")" = '["portfolio-api","postgres"]'
jq -e '.services.postgres.ports == null' <<<"$config" >/dev/null
jq -e '.services.postgres.image | test("@sha256:[0-9a-f]{64}$")' <<<"$config" >/dev/null
jq -e '.services["portfolio-api"].ports[0].host_ip == "127.0.0.1"' <<<"$config" >/dev/null
jq -e '.services["portfolio-api"].depends_on.postgres.condition == "service_healthy"' <<<"$config" >/dev/null
```

Create a test env containing dummy non-secret values, run `bash deploy/tests/compose-contract.sh`, and expect failure because the Compose file is missing.

- [x] **Step 2: Create production Compose**

```yaml
name: portfolio
services:
  postgres:
    image: ${POSTGRES_IMAGE:?POSTGRES_IMAGE digest reference is required}
    restart: unless-stopped
    env_file: /etc/portfolio/postgres.env
    volumes:
      - postgres-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U $$POSTGRES_USER -d $$POSTGRES_DB"]
      interval: 10s
      timeout: 5s
      retries: 12
      start_period: 20s
    stop_grace_period: 60s
    logging:
      driver: json-file
      options: { max-size: "20m", max-file: "5" }

  portfolio-api:
    image: ${PORTFOLIO_IMAGE:?PORTFOLIO_IMAGE is required}
    restart: unless-stopped
    env_file: /etc/portfolio/portfolio.env
    environment:
      SPRING_PROFILES_ACTIVE: prod
      PORTFOLIO_RELEASE_ID: ${PORTFOLIO_RELEASE_ID:?PORTFOLIO_RELEASE_ID is required}
      PORTFOLIO_COS_STAGING_ROOT: /tmp/portfolio-cos-staging
    depends_on:
      postgres: { condition: service_healthy }
    ports:
      - "127.0.0.1:18080:8080"
    volumes:
      - local-media:/var/lib/portfolio/media
    tmpfs:
      - /tmp:size=128m,mode=1777,noexec,nosuid,nodev
    read_only: true
    security_opt:
      - no-new-privileges:true
    cap_drop:
      - ALL
    healthcheck:
      test: ["CMD", "curl", "--fail", "--silent", "--show-error", "http://127.0.0.1:8080/actuator/health/readiness"]
      interval: 15s
      timeout: 5s
      retries: 12
      start_period: 60s
    stop_grace_period: 45s
    logging:
      driver: json-file
      options: { max-size: "20m", max-file: "5" }

volumes:
  postgres-data:
  local-media:
```

The Task-1 runtime stage installs `curl`, so the health check validates the local readiness response rather than only testing that a TCP port is open. The rendered Compose contract requires `java.io.tmpdir` and `PORTFOLIO_COS_STAGING_ROOT` to remain under this size-limited ephemeral tmpfs, rejects persistent/unbounded process temporary storage, and verifies no scratch path resolves inside the durable Local media volume. Provision `local-media` once with an owner-only, no-follow `.portfolio-volume-id` containing a random opaque value stored separately as protected `PORTFOLIO_LOCAL_VOLUME_ID`; a new or wrong empty volume must not be accepted merely because it is writable.

- [x] **Step 3: Add production configuration safeguards**

`application-prod.yml` must disable Hibernate/schema initialization, expose only Actuator health, trust native forwarded headers only under the loopback proxy policy, emit structured JSON logs, reject blank encryption/HMAC secrets, keep SQL/session/media initialization under Flyway, and set the administrator session cookie to `Secure`, `HttpOnly`, and `SameSite=Strict` exactly as plan 01 specifies.

`.env.example` lists every variable name from plans 01–06 and these deployment fields with blank/safe values. Split real files:

```text
/etc/portfolio/postgres.env   root:root 0600
/etc/portfolio/portfolio.env  root:root 0600
/etc/portfolio/release.env    root:portfolio-deploy 0640
```

Generate passwords with `openssl rand -base64 32` and application secrets with `openssl rand -base64 48`; never print generated values after writing protected files.

- [x] **Step 4: Run config checks**

Verification (2026-07-18): the production Compose contract passed after a real
Compose v2 JSON render without starting either service. Java 17/Maven 3.9.11
focused suites passed 70/70 across production profile safeguards, Local-only,
COS-default, mixed Local/COS bean selection, and COS staging behavior. No
database connection or container data mutation occurred.

```bash
bash deploy/tests/compose-contract.sh
docker compose --env-file /etc/portfolio/release.env -f deploy/docker-compose.prod.yml config --quiet
```

Expected: PASS, exactly two services, no PostgreSQL port, API loopback only.

- [x] **Step 5: Commit the topology**

```bash
git add deploy/docker-compose.prod.yml deploy/.env.example deploy/tests/compose-contract.sh backend-parent/portfolio-server/src/main/resources/application-prod.yml
git commit -m "ops: define private production topology"
```

---

### Task 3: Configure BaoTa Nginx, security headers, and the ICP cutover gate

**Files:**
- Create: `deploy/nginx/portfolio-http.conf`
- Create: `deploy/nginx/portfolio-site.conf.example`
- Create: `deploy/nginx/baota.env.example`
- Create: `deploy/scripts/render-nginx.sh`
- Create: `deploy/scripts/preflight.sh`
- Create: `deploy/tests/nginx-contract.sh`

**Interfaces:**
- Consumes: shared `/opt/portfolio/assets`, `/opt/portfolio/current-admin`, API loopback port, explicit media origin, Tencent region/jurisdiction, and ICP state.
- Produces: TLS host routing for `yychainsaw.xyz` and `www.yychainsaw.xyz` after approval.

- [x] **Step 1: Write failing Nginx and ICP gate checks**

The test renders a config and asserts:

- `/assets/` aliases `/opt/portfolio/assets/` with immutable caching;
- `/admin/` uses `current-admin` and falls back only inside admin;
- `/api/` always proxies and never falls back to HTML;
- public HTML/project/privacy routes proxy to API;
- `/actuator` is denied;
- forwarded IP headers use `$remote_addr`, not incoming forwarded values;
- login/contact/events use named Nginx limits;
- CSP contains only the configured media/frame origins;
- mainland jurisdiction plus unapproved ICP exits non-zero before public config installation;
- BaoTa binary/prefix/config/PID values are absolute, the PID owns ports 80/443, and syntax/reload use that exact binary and config;
- every A/AAAA answer for both public hosts equals the explicitly recorded expected address set, with no stale or conflicting record.

Run with `SERVER_JURISDICTION=MAINLAND_CN ICP_APPROVED=false` and expect the preflight to fail with `ICP approval required for mainland public cutover`.

- [x] **Step 2: Define `http`-scope rate zones**

```nginx
limit_req_zone $binary_remote_addr zone=portfolio_login:10m rate=5r/m;
limit_req_zone $binary_remote_addr zone=portfolio_contact:10m rate=5r/m;
limit_req_zone $binary_remote_addr zone=portfolio_events:10m rate=60r/m;
```

Install this once through BaoTa's Nginx `http` include mechanism. Application limits remain mandatory because Nginx limits are only the outer layer.

- [x] **Step 3: Create the site routing contract**

The rendered `server` configuration must include these location semantics:

```nginx
location ^~ /assets/ {
    alias /opt/portfolio/assets/;
    try_files $uri =404;
    add_header Cache-Control "public, max-age=31536000, immutable" always;
}

location = /admin { return 308 /admin/; }
location ^~ /admin/assets/ {
    alias /opt/portfolio/current-admin/assets/;
    try_files $uri =404;
    add_header Cache-Control "public, max-age=31536000, immutable" always;
}
location /admin/ {
    alias /opt/portfolio/current-admin/;
    try_files $uri $uri/ /admin/index.html;
    add_header Cache-Control "no-store" always;
}

location ^~ /actuator/ { return 404; }
location = /api/admin/auth/login {
    limit_req zone=portfolio_login burst=3 nodelay;
    proxy_pass http://127.0.0.1:18080;
    include /www/server/nginx/conf/portfolio-proxy.conf;
}
location = /api/public/contact {
    limit_req zone=portfolio_contact burst=5 nodelay;
    proxy_pass http://127.0.0.1:18080;
    include /www/server/nginx/conf/portfolio-proxy.conf;
}
location = /api/public/events {
    limit_req zone=portfolio_events burst=20 nodelay;
    proxy_pass http://127.0.0.1:18080;
    include /www/server/nginx/conf/portfolio-proxy.conf;
}
location ^~ /api/ {
    proxy_pass http://127.0.0.1:18080;
    include /www/server/nginx/conf/portfolio-proxy.conf;
}
location / {
    proxy_pass http://127.0.0.1:18080;
    include /www/server/nginx/conf/portfolio-proxy.conf;
}
```

The proxy include sets `Host $host`, `X-Real-IP $remote_addr`, `X-Forwarded-For $remote_addr`, `X-Forwarded-Proto $scheme`, timeouts, request size, and buffering without using `$proxy_add_x_forwarded_for`. Preserve `Range`/`If-Range` for LocalStorage media.

- [x] **Step 4: Add strict headers and render allowlists**

Render HSTS only in the HTTPS server after TLS is verified. Add CSP, `X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`, a restricted `Permissions-Policy`, and frame denial. The render script accepts a single validated HTTPS `MEDIA_ORIGIN` and an allowlist of external video frame origins; it rejects whitespace, semicolons, quotes, paths, HTTP, and wildcard hosts.

Do not duplicate headers inconsistently between nested locations. Put the generated security headers in one included file and use `always`.

- [x] **Step 5: Implement jurisdiction and approval preflight**

Require the operator to record:

```dotenv
TENCENT_REGION=ap-guangzhou
SERVER_JURISDICTION=MAINLAND_CN
ICP_APPROVED=false
ICP_NUMBER=
PUBLIC_DOMAIN_ENABLED=false
PUBLIC_HOSTS=yychainsaw.xyz,www.yychainsaw.xyz
EXPECTED_PUBLIC_IPV4=203.0.113.10
EXPECTED_PUBLIC_IPV6=NONE
NGINX_BIN=/www/server/nginx/sbin/nginx
NGINX_PREFIX=/www/server/nginx/
NGINX_CONF=/www/server/nginx/conf/nginx.conf
NGINX_PID=/www/server/nginx/logs/nginx.pid
```

The example IPv4 is documentation-only and must be replaced with the confirmed server address. Install the reviewed values as `/etc/portfolio/nginx.env` owned `root:portfolio-deploy` mode `0640`. `preflight.sh --public-cutover` fails when jurisdiction is `MAINLAND_CN` and any of `ICP_APPROVED=true`, nonblank `ICP_NUMBER`, or `PUBLIC_DOMAIN_ENABLED=true` is missing. For every `PUBLIC_HOSTS` entry, normalize and sort `dig +short A` and `dig +short AAAA`; require exact equality with the recorded expected set, require zero AAAA records when the value is `NONE`, and reject extra/stale/conflicting answers rather than accepting mere resolution.

Resolve all four Nginx paths with `realpath`, require absolute values beneath the reviewed BaoTa installation, require the process in `NGINX_PID` to own ports 80/443 and its executable to equal `NGINX_BIN`, and use only `"$NGINX_BIN" -p "$NGINX_PREFIX" -c "$NGINX_CONF" -t` and `"$NGINX_BIN" -p "$NGINX_PREFIX" -c "$NGINX_CONF" -s reload`. The same command pair is used by preflight, deploy, rollback, and recovery; generic `nginx` or `systemctl reload nginx` calls are forbidden. Preflight also checks TLS files, API loopback, disk headroom, Docker 26, Compose availability, `jq`, `dig`, `unzip`, `zstd`, `curl`, `age`, `rclone`, clock synchronization, protected file permissions, the release bundle's exact `PORTFOLIO_RELEASE_ID`, protected nonblank `PORTFOLIO_LOCAL_VOLUME_ID`, `PORTFOLIO_JOBS_WORKER_ENABLED=true`, `PORTFOLIO_STAGING_CLEANUP_ENABLED=true`, and `PORTFOLIO_MEDIA_CLEANUP_ENABLED=true` now that plan 03's complete reference checker is installed. It verifies the mounted Local root and owner-only no-follow `.portfolio-volume-id` before application start, compares the marker in constant time without logging either value, and rejects a missing, replaced, wrong, or extra-mounted root. It counts Local staging entries without crossing filesystems, rejects a count at or above the application's configured hard scan ceiling, and requires the latest sanitized cleanup scan duration to remain below the reviewed fraction of the job lease. It also verifies rendered/running `/tmp` is a size-limited `noexec,nosuid,nodev` tmpfs and that COS/JVM scratch resolves beneath it, never beneath durable media. Preflight builds a required-provider set from configured adapters, the default writer, and distinct provider/bucket/region locations already referenced by `media_asset`; an unconfigured or location-mismatched historical provider fails closed. During explicit bucket provisioning, before the first deployment, the operator runs `install-cos-staging-lifecycle.sh --apply` once for every required COS location with short-lived lifecycle-read/configure authority, verifies the read-back, and discards that credential. Every routine preflight is read-only: for each required COS location it invokes `verify-cos-staging-lifecycle.sh --check-live` with separate short-lived lifecycle-read authority and fails before workers are enabled unless that bucket has the exact enabled `staging/` one-day rule; the runtime media credential must lack lifecycle-management permission. For required Local storage, preflight additionally requires the canonical root to be writable with adequate headroom and the durable staging scheduler to be enabled. A mixed Local/COS installation must pass both independent gates regardless of the current default writer. For Hong Kong/overseas, preflight records that the mainland ICP gate does not apply but still requires an explicit jurisdiction value.

Until review completes, use `PUBLIC_DOMAIN_ENABLED=false`; run private smoke tests through `curl --resolve yychainsaw.xyz:443:127.0.0.1` only after a local test certificate is configured, or use direct loopback API checks.

- [x] **Step 6: Validate and commit**

Verification (2026-07-18): the isolated Nginx contract passed in Ubuntu 22.04,
ShellCheck reported zero findings, and a real temporary Nginx instance passed
syntax validation plus request-level checks for the shared public asset alias,
administrator asset alias, administrator SPA fallback, and missing-asset 404.
The contract uses only fixtures/stubs and does not contact public DNS, Tencent
COS, BaoTa, or a database. The mainland public-cutover branch remains fail
closed until the final ICP approval, record number, DNS answers, and TLS/runtime
evidence are installed on the production host.

```bash
bash deploy/tests/nginx-contract.sh
sudo bash -c 'set -a; source /etc/portfolio/nginx.env; set +a; "$NGINX_BIN" -p "$NGINX_PREFIX" -c "$NGINX_CONF" -t'
```

Expected: rendered syntax passes; negative ICP fixture fails and approved/overseas fixtures pass.

```bash
git add deploy/nginx deploy/scripts/render-nginx.sh deploy/scripts/preflight.sh deploy/tests/nginx-contract.sh
git commit -m "ops: secure nginx and gate domain cutover"
```

---

### Task 4: Deploy atomically, smoke test, retain assets, and roll back

**Files:**
- Create: `deploy/scripts/install-release-bundle.sh`
- Create: `deploy/scripts/validate-bundle-tar.py`
- Create: `deploy/scripts/deploy-release.sh`
- Create: `deploy/scripts/smoke.sh`
- Create: `deploy/scripts/rollback-release.sh`
- Create: `deploy/scripts/prune-releases.sh`
- Create: `deploy/tests/deploy-state-machine.sh`

**Interfaces:**
- Consumes: release artifact, production Compose, Nginx, the backup command contract implemented in Task 5, and all final HTTP contracts.
- Produces: a locked deployment state machine with current/previous release markers.

- [ ] **Step 1: Write a failing filesystem state-machine test**

Use a temporary fake `/opt/portfolio` root and stub Docker/curl commands. Prove that:

- a failed backup prevents any switch;
- a bundle with a mismatched checksum, absolute/parent/backslash path, symlink, hardlink, FIFO, socket, block/character device, global/per-entry PAX header, duplicate normalized entry, or any type other than regular file/directory is rejected before extraction or `docker load`;
- API and PostgreSQL archive round trips must reproduce the exact `release.json` image IDs, and a conflicting pre-existing tag aborts without retagging it;
- a failed API health check keeps the previous admin symlink;
- public assets copy without replacing same-name files and abort on checksum mismatch;
- successful smoke writes `current-release` and retains `previous-release`;
- rollback changes image/release env and admin symlink but does not run a database-down command;
- pruning never removes files referenced by the latest three public manifests.

- [ ] **Step 2: Implement a deployment lock and preconditions**

Transfer the portable bundle over authenticated SSH/rsync or a private artifact store into `/opt/portfolio/incoming`; transfer is complete only after its local SHA-256, byte size, release ID, and embedded `release.json` SHA-256 match the detached out-of-band envelope. Before extraction, stream `zstd -dc` into `validate-bundle-tar.py`. The validator uses Python's `tarfile` reader and `PurePosixPath`, accepts only regular files and directories, rejects absolute/empty/dot/parent/backslash paths, symlink, hardlink, FIFO, socket, device, sparse entry, every global/per-entry PAX header, duplicate normalized names, duplicate case-folded names, and any path outside exactly `portfolio-{releaseId}/`. The package writer therefore emits reproducible POSIX ustar only.

After validation, extract into an empty same-filesystem directory with ownership/permissions restoration disabled, verify every `bundle-manifest.json` entry, load API and PostgreSQL archives into disposable tags, require their image IDs to equal `release.json`, and only then install the intended tags without replacing a different pre-existing ID. Run `release-artifact-contract.sh` and atomically install `/opt/portfolio/releases/{releaseId}`. The installer never extracts as a path supplied by archive content and removes only its resolved staging directory on failure.

Then acquire `flock /run/lock/portfolio-deploy.lock`. Resolve every supplied path with `realpath` and require releases under `/opt/portfolio/releases`. Validate the installed artifact, clean Nginx syntax, protected env permissions, current database health, disk space, and `preflight.sh`; use `--public-cutover` only for the DNS/domain switch.

- [ ] **Step 3: Implement the exact deployment sequence**

`deploy-release.sh {releaseId}` performs:

1. record current release and image;
2. run the Task-5 on-demand `backup-set.sh` flow and verify its encrypted database plus mixed-provider media closure before migration;
3. copy public hashed files from the release to `/opt/portfolio/assets` with create-only behavior and SHA-256 equality checks on existing names;
4. atomically replace `/etc/portfolio/release.env` with the new image tag and release ID;
5. when using a private registry, pull API by its recorded repository digest; otherwise verify the API image ID loaded from the bundle, and always verify the pinned PostgreSQL image ID loaded from its bundled archive without resolving a tag;
6. run `docker compose up -d postgres` and wait for health;
7. run the application migration/start with `docker compose up -d portfolio-api`;
8. wait for readiness and run `smoke.sh api-local --base-url http://127.0.0.1:18080`;
9. when Local is in the required-provider set, derive the same most-recent nonfuture 04:00 Hong Kong boundary date and 24-hour-prior cutoff as `StagingCleanupScheduler`, then wait a bounded interval for the exact `media-staging-cleanup:{PORTFOLIO_RELEASE_ID}:{yyyy-MM-dd}` job and canonical payload from this release to exist and reach `SUCCEEDED`; a stale success row from any other release cannot satisfy the gate. For every required COS location, retain the matching successful live verifier evidence from preflight; mixed installations require all evidence;
10. create a new symlink and atomically rename it to `/opt/portfolio/current-admin`;
11. source `/etc/portfolio/nginx.env`, run the exact recorded BaoTa test/reload command pair, then run `smoke.sh nginx-local --resolve yychainsaw.xyz:${NGINX_LOCAL_PORT}:127.0.0.1` against the loopback-only local Nginx fixture;
12. only when `preflight.sh --public-cutover` passes, run `smoke.sh public --base-url https://yychainsaw.xyz` against real DNS/TLS;
13. write current/previous release marker files atomically;
14. run safe release/asset pruning and record a redacted deployment result.

If steps 7–12 fail, restore the previous release env, start the previous API image, restore the previous admin symlink, run the exact BaoTa syntax/reload command pair, and leave the forward database migration in place. Exit non-zero and preserve diagnostic logs without secrets.

- [ ] **Step 4: Cover the local and public smoke matrix**

`smoke.sh` has three non-overlapping entry modes and refuses an unknown/missing mode:

- `api-local --base-url http://127.0.0.1:18080` checks readiness, public/admin API response shape, ETag behavior, JSON error routing, and server-rendered public HTML without depending on Nginx static files;
- `nginx-local --resolve yychainsaw.xyz:${NGINX_LOCAL_PORT}:127.0.0.1` checks the loopback-only BaoTa fixture that reuses the production locations/includes, current admin symlink, public hashed assets, proxy routes, media delivery, and security/cache headers without public DNS; it never treats the API loopback as an Nginx origin;
- `public --base-url https://yychainsaw.xyz` first requires the ICP/DNS/TLS cutover gate, makes no `--resolve` override, and repeats the Nginx matrix against the real public endpoint.

Across the applicable modes, check status, content type, cache policy, and representative content for:

```text
GET /actuator/health/readiness       local API only, status UP
GET /                               302 to /zh-CN
GET /zh-CN                          indexable HTML and zh-CN markers
GET /en                             indexable HTML and en markers
GET /zh-CN/privacy                  canonical and hreflang
GET /api/public/site?locale=zh-CN   JSON and ETag
GET /api/public/projects?locale=en  JSON and ETag
GET /sitemap.xml                    XML and current project URL
GET /robots.txt                     text/plain
GET /admin/                         admin HTML, no-store
GET /assets/{knownHashFile}         immutable cache and expected SHA-256
GET /api/not-a-route                JSON 404, never admin/public HTML
```

After content exists, add a current project, old-slug redirect, public media, login page, contact validation, and authenticated login/TOTP smoke using a dedicated operator workflow that never places credentials on the command line. `nginx-local` and `public` must GET the public-media URL and compare its SHA-256/content type with the selected `media_variant`; a database-only success is insufficient.

`deploy-state-machine.sh` covers worker-disabled and cleanup-disabled preflight failures, missing/wrong/replaced Local volume markers, persistent or oversized/non-hardened tmp mounts, COS scratch escaping tmpfs, Local-only, COS-only, and mixed-provider gates, a COS bucket missing its installed rule, stale cleanup success from a previous release, the before-04:00 boundary date, canonical cutoff mismatch, current-release cleanup timeout/failure, and the all-evidence success path. No fixture may mutate the real bucket, storage root, or production database.

- [ ] **Step 5: Implement safe three-release pruning**

Keep the current release, previous release, and newest third release. Parse their Vite manifests and admin files into an allowlist. Delete only release directories older than those three after resolving the path under `/opt/portfolio/releases`. Remove a shared public asset only if no retained manifest references its exact relative path and its age exceeds seven days. Never follow symlinks during deletion.

- [ ] **Step 6: Verify deploy and rollback in a disposable environment**

Run the state-machine test with a stub backup command now. After Task 5 implements and verifies the real backup command, deploy two fixture releases to an isolated Compose project and roll back once. Confirm old HTML can still load its hash asset after the second deployment and after rollback. Do not run this script against production between Tasks 4 and 5.

- [ ] **Step 7: Commit the release controller**

```bash
git add deploy/scripts/install-release-bundle.sh deploy/scripts/validate-bundle-tar.py deploy/scripts/deploy-release.sh deploy/scripts/smoke.sh deploy/scripts/rollback-release.sh deploy/scripts/prune-releases.sh deploy/tests/deploy-state-machine.sh
git commit -m "ops: deploy and roll back portfolio releases"
```

---

### Task 5: Create independent encrypted database and media backups

**Files:**
- Create: `deploy/backup/lib.sh`
- Create: `deploy/backup/backup-set.sh`
- Create: `deploy/backup/backup-database.sh`
- Create: `deploy/backup/backup-media.sh`
- Create: `deploy/backup/verify-artifact.sh`
- Create: `deploy/backup/validate-media-tar.py`
- Create: `deploy/backup/prune-remote.sh`
- Create: `deploy/backup/notify-failure.sh`
- Create: `deploy/tests/backup-contract.sh`
- Create: `deploy/systemd/portfolio-backup.service`
- Create: `deploy/systemd/portfolio-backup.timer`
- Create: `deploy/systemd/portfolio-backup-prune.service`
- Create: `deploy/systemd/portfolio-backup-prune.timer`

**Interfaces:**
- Consumes: PostgreSQL container, Local/COS storage metadata, age recipient, rclone remotes with separate credentials, and `maintenance_run`.
- Produces: nightly encrypted database/media backup sets, encrypted manifests, remote checksums, and failure email independent of API health.

- [ ] **Step 1: Write failing backup safety tests**

With stubbed `docker`, `psql`, `pg_dump`, `age`, and `rclone`, assert:

- `backup-set.sh` opens one dedicated keeper connection, acquires session-level `pg_advisory_lock_shared(1347375700,1296385097)`, exports one repeatable-read MVCC snapshot, passes that exact snapshot ID to both `pg_dump` and the media-manifest reader, and does not unlock until the complete remote set is published and verified;
- database backup uses PostgreSQL custom format and runs `pg_restore --list` before encryption;
- remote upload failure makes the whole run fail;
- a fixture containing interleaved `LOCAL` and `TENCENT_COS` assets follows each row's provider without reading a global source-mode switch;
- source-reader, uploader, verifier, and pruner principals/config files are distinct, and the backup process cannot use the pruner credential;
- the encrypted authoritative media manifest contains the database dump SHA-256 plus every original/variant source identity, while the non-secret self-contained set manifest contains every artifact checksum and every referenced `blobs/{sha256}` path;
- every set contains an encrypted LocalStorage tar, including a valid empty tar when the snapshot has zero LOCAL rows, and COS bytes are created once at immutable `blobs/{sha256}` keys;
- sample counts are `0` for an empty COS closure, `total` when `total < 20`, and otherwise `min(total, 200, max(20, ceil(total * 0.01)))`;
- retention garbage collection builds the union of blob hashes referenced by every retained verified daily/weekly/monthly set and never deletes a referenced blob or any object outside the reviewed non-root prefix;
- no command logs a password, age identity, COS secret, email, or message body;
- daily/weekly/monthly retention keeps 7/4/6 sets;
- a failure invokes the independent notification command and inserts a redacted failed `maintenance_run`.

- [ ] **Step 2: Define protected backup configuration**

Use `/etc/portfolio/backup.env` at `0600`:

```dotenv
BACKUP_AGE_RECIPIENT=age1_public_recipient_value
BACKUP_REMOTE=portfolio-backup:yychainsaw-portfolio-backup
BACKUP_PREFIX=production
BACKUP_UPLOAD_RCLONE_CONFIG=/etc/portfolio/rclone-backup-uploader.conf
BACKUP_VERIFY_RCLONE_CONFIG=/etc/portfolio/rclone-backup-verifier.conf
BACKUP_PRUNE_RCLONE_CONFIG=/etc/portfolio/rclone-backup-pruner.conf
MEDIA_SOURCE_RCLONE_REMOTE=portfolio-media:yychainsaw-portfolio-media
MEDIA_SOURCE_RCLONE_CONFIG=/etc/portfolio/rclone-media-reader.conf
LOCAL_MEDIA_ROOT=/var/lib/portfolio/media
BACKUP_EMAIL_TO=operator-address
BACKUP_TIMEZONE=Asia/Hong_Kong
```

The values shown are documentation syntax, not usable credentials. The real age private identity is stored off-server. Provider choice comes exclusively from each exported `media_asset.provider` row; configuration must not contain a global media source mode. All four rclone config files are root-only and identify different principals. The production media reader has only source-bucket list/read permission. The backup uploader has only the destination prefix permissions required to list/stat and create immutable objects beneath `sets/`, `blobs/`, and `drill-reports/`, but no object-body read or delete. The verifier has list/read but no put/delete. The pruner has list, read limited to set manifests/verification markers, create-only access beneath `gc-reports/`, and narrowly scoped delete/version-delete beneath reviewed `sets/` and `blobs/` candidates; it cannot read database/media object bodies, overwrite a report, or put anywhere else, and is not exposed to the nightly backup service.

`lib.sh` resolves both source and backup accounts/buckets and normalizes `BACKUP_PREFIX` before any write or delete. It rejects an empty value, `/`, `.`, the bucket root, leading `/`, `..`, backslashes, control characters, a source destination equal to the backup account/bucket, or a resolved path outside exactly `${BACKUP_REMOTE}/${BACKUP_PREFIX}/{sets,blobs,drill-reports,gc-reports}`. It also proves the source-reader, uploader, verifier, and pruner principal IDs differ. `notify-failure.sh` uses a root-only `msmtp` config or a Tencent monitoring channel independent of `portfolio-api`.

Enable destination-bucket versioning before the first set. When the provider supports Object Lock/WORM, enable it at bucket creation for both `sets/` and `blobs/`, record its retention mode in the runbook, and never grant the uploader a bypass. Object Lock protects versions only until their recorded retention expiry; it does not make the pruner safe by itself. The pruner must still prove prefix, role, retained-set reachability, version ID, and retention expiry before deletion. Where Object Lock is unavailable, versioning, content-addressed immutable writes, separate credentials, and the same reachability gate remain mandatory.

- [ ] **Step 3: Implement one snapshot keeper and database backup**

`backup-set.sh` is the only production entry point. For each UTC timestamped set it:

1. create a `0700` temporary directory below `/var/backups/portfolio/staging`;
2. launch one long-lived `psql -X -A -t -q --set ON_ERROR_STOP=1` coprocess on a dedicated keeper connection, acquire `pg_advisory_lock_shared(1347375700,1296385097)`, then begin `ISOLATION LEVEL REPEATABLE READ READ ONLY` and call `pg_export_snapshot()`;
3. pass the returned snapshot ID through protected file descriptors, never command logs, to `pg_dump --format=custom --no-owner --snapshot="$snapshot_id"` and to a second manifest-reader transaction that begins repeatable-read/read-only and executes `SET TRANSACTION SNAPSHOT` before querying;
4. wait for both snapshot consumers to finish, then commit the keeper transaction while leaving its session and shared advisory lock alive; the exported snapshot is now closed but physical media deletion remains blocked;
5. run matching PostgreSQL 17 `pg_restore --list` and require entries for `flyway_schema_history`, `publication`, `content_revision`, `revision_media_reference`, `media_asset`, `media_variant`, `contact_message`, and `admin_user`;
6. compute plaintext dump SHA-256 and non-PII row-count metadata, encrypt with `age -r "$BACKUP_AGE_RECIPIENT"`, compute encrypted SHA-256, and stage the encrypted dump for the same set;
7. invoke the media-closure work in Step 4, upload all set artifacts, publish the canonical `set-manifest.json` last, verify it and its closure with the verifier credential, and only then call `pg_advisory_unlock_shared(1347375700,1296385097)` and close the keeper connection;
8. remove plaintext staging files and record matching `DATABASE_BACKUP` and `MEDIA_BACKUP` results whose artifact checksum is the verified set-manifest checksum.

The coprocess protocol uses explicit ready/snapshot/commit/unlock markers and checks that exactly one row reports the unlock succeeded. A trap terminates the keeper safely on every signal/error, records a redacted failure, and notifies even when the API is down. The shared session lock deliberately matches plan 02's session-level exclusive `pg_advisory_lock(1347375700,1296385097)`: if cleanup already owns the lock, backup waits; once backup owns it, no Local/COS physical deletion can begin until the verified remote closure exists. Never substitute `pg_advisory_xact_lock*`, two unrelated connections, or an application/JVM mutex.

Inserting `maintenance_run` uses `docker compose exec -T postgres psql` with a static parameterized SQL file and includes only run ID, allowlisted run type/status, timestamps, artifact checksum, and redacted category. Snapshot/export commands and maintenance writes must use the same pinned PostgreSQL 17 client image as production.

- [ ] **Step 4: Implement the mixed-provider media closure**

The manifest-reader transaction exports every original from `media_asset` and every row from `media_variant` with asset/variant ID, per-row provider, bucket, region, object key, MIME type, byte size, plaintext SHA-256, and snapshot/set ID—no caption, filename, translation, or visitor data. After `pg_dump` finishes, canonical assembly adds that dump's plaintext SHA-256 to the unchanged exported row set before encrypting the authoritative manifest. A single manifest may freely interleave `LOCAL` and `TENCENT_COS`; dispatch each row independently and fail closed on any other provider.

Use this remote layout:

```text
{BACKUP_PREFIX}/
├── blobs/{plaintext-sha256}
└── sets/{setId}/
    ├── database.dump.age
    ├── local-media.tar.age
    ├── media-manifest.json.age
    ├── set-manifest.json
    └── VERIFIED
```

For each `TENCENT_COS` row, read with the production media-reader credential, verify byte count and plaintext SHA-256, and create `blobs/{sha256}` with `--immutable`; a pre-existing blob is reused only after the verifier independently downloads it and proves the same SHA-256. The destination bucket is private, TLS-only, versioned, and encrypted at rest with provider-managed or KMS-backed server-side encryption. Never overwrite a blob path, and never put source bucket, region, or object key into the non-secret set manifest.

For all `LOCAL` rows, resolve every object key beneath `LOCAL_MEDIA_ROOT`, reject symlinks/path traversal, verify each byte count/SHA-256, and create one deterministic POSIX ustar containing exactly the snapshot's LOCAL originals/variants. Every set includes this tar even when it is empty. Validate it before encryption, encrypt it with age, upload it beneath that set, and verify the remote ciphertext checksum. No Local object is represented only by a database row or manifest.

Encrypt the authoritative mixed-provider manifest with age. `set-manifest.json` is canonical, non-secret, and self-contained: it records set/snapshot ID, database/local-tar/encrypted-manifest remote paths and ciphertext hashes, counts, immutable daily/weekly/monthly eligibility flags, and the complete sorted list of referenced `blobs/{sha256}` paths plus sizes/hashes. Upload it only after every referenced artifact exists. The verifier downloads the set manifest through its own credential, validates canonical shape/checksum, and checks every artifact. Only after that read-only verification succeeds may the uploader create immutable `VERIFIED` containing the set-manifest checksum; the verifier then reads that marker back and matches it before the keeper unlocks. An upload marker or partially written set is never eligible for retention or unlock.

For COS content verification let `total` be the number of distinct blob hashes in this set. If `total == 0`, sample `0`; if `total < 20`, sample every blob; otherwise use `sample = min(total, 200, max(20, ceil(total * 0.01)))`. Choose deterministic set-ID-seeded hashes, download them using only the verifier credential, and recompute plaintext SHA-256. Plain Local entries are all verified before tar creation, encrypted-artifact integrity is checked remotely nightly, and Task 7 performs a full decrypt/extract verification.

- [ ] **Step 5: Implement 7 daily, 4 weekly, and 6 monthly retention**

Every run creates an immutable daily set. Its self-contained manifest marks weekly eligibility when the set is the configured first-weekday run and monthly eligibility on calendar day 1. The pruner retains the newest 7 daily-eligible, 4 weekly-eligible, and 6 monthly-eligible distinct verified set IDs; it never copies/relabels blobs or rewrites a set. Restoring a set never depends on an earlier set or a mutable retention index.

`prune-remote.sh` runs with only the pruner credential. It verifies remote account, bucket, non-root prefix, role, versioning/Object-Lock state, every retained set checksum, and every immutable `VERIFIED` marker; writes and remotely persists a proposed deletion manifest; then rebuilds the union of all `blobs/{sha256}` paths referenced by every retained daily/weekly/monthly set. It may delete an expired unretained set's set-local artifacts and may garbage-collect a blob only when no retained set references it, the blob is not younger than the safety window, all protected versions are beyond retention, and the candidate remains beneath `${BACKUP_PREFIX}/blobs/`. Reject an empty retained-set collection unless an operator supplies the documented disaster-reset confirmation; never issue an rclone delete at remote or bucket root.

- [ ] **Step 6: Install the host timer**

`portfolio-backup.timer` runs nightly at 02:30 `Asia/Hong_Kong` with `Persistent=true` and randomized delay up to 15 minutes. The oneshot service uses hardening (`NoNewPrivileges`, `PrivateTmp`, protected system/home), allows Docker/rclone network needs, sets a 3-hour timeout, exposes source/uploader/verifier but not pruner credentials, and invokes `backup-set.sh` even if the API container is stopped. `portfolio-backup-prune.timer` runs after the expected backup window with its own hardened service and exposes only the pruner configuration; it may read retention manifests/markers and create one immutable GC report, but cannot upload backup data or read database/media object bodies.

- [ ] **Step 7: Verify a real backup and commit**

```bash
sudo systemd-analyze verify deploy/systemd/portfolio-backup.service deploy/systemd/portfolio-backup.timer deploy/systemd/portfolio-backup-prune.service deploy/systemd/portfolio-backup-prune.timer
sudo bash deploy/backup/backup-set.sh --verify-upload
sudo bash deploy/backup/prune-remote.sh --dry-run
bash deploy/tests/backup-contract.sh
```

Expected: one encrypted, self-contained verified set contains the database, the encrypted Local tar, encrypted mixed-provider manifest, and every required immutable COS blob; the common snapshot/lock contract and sample checks pass, the pruner dry-run proves a non-root bounded candidate set, and no plaintext backup remains in staging.

```bash
git add deploy/backup deploy/systemd/portfolio-backup.service deploy/systemd/portfolio-backup.timer deploy/systemd/portfolio-backup-prune.service deploy/systemd/portfolio-backup-prune.timer deploy/tests/backup-contract.sh
git commit -m "ops: back up database and media independently"
```

---

### Task 6: Expose redacted operational status in the admin API

**Files:**
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/system/operations/OperationsStatusService.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/system/operations/OperationsStatus.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/system/operations/MaintenanceRunMapper.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/system/operations/MaintenanceView.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/system/operations/AdminOperationsController.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/system/operations/AdminOperationsControllerTest.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/system/operations/MaintenanceRunMapperTest.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/system/operations/OperationsStatusServiceTest.java`

**Interfaces:**
- Consumes: plan-02 `maintenance_run` and plan-01 admin authentication.
- Produces: the sole `GET /api/admin/system/operations` status contract, consumed only by plan 04's admin `SettingsView` operations section (`OperationsStatus.vue`); the dashboard makes no operations request.

- [x] **Step 1: Write the failing status API test**

Seed successful/failed maintenance rows and verify the endpoint returns the latest database backup, media backup, analytics aggregation, contact retention, media cleanup, deployment, and restore drill. Assert `401` when logged out and prove that artifact paths, bucket names, object keys, credentials, exception messages, PII, and raw job payloads are absent.

- [x] **Step 2: Run the focused test and verify failure**

```powershell
.\mvnw.cmd -pl portfolio-server -am -Dtest=AdminOperationsControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL because the operations endpoint does not exist.

- [x] **Step 3: Implement a redacted status model**

```java
public record OperationsStatus(
    MaintenanceView databaseBackup,
    MaintenanceView mediaBackup,
    MaintenanceView analyticsAggregation,
    MaintenanceView contactRetention,
    MaintenanceView mediaCleanup,
    MaintenanceView deployment,
    MaintenanceView restoreDrill,
    Instant serverTime
) {}

public record MaintenanceView(
    String type, String status, Instant startedAt, Instant finishedAt,
    String artifactChecksum, String errorCategory
) {}
```

Return only allowlisted run types and a mapped error category. Set `Cache-Control: no-store`. The endpoint is read-only; backup scripts remain host-owned and no web endpoint may start restore, shell, backup, deployment, or key rotation.

- [x] **Step 4: Run tests and commit**

Run the Step 2 command.

Expected: PASS and no operational secret/path appears.

Verification (2026-07-18): this endpoint was delivered early with plan-04 Task 12 so the complete settings route has no missing backend dependency. The isolated suite passed 10/10 under Java 17 and Maven 3.9.11. It covers authenticated 200/no-store and anonymous 401/no-store behavior, all eight stable root keys and six nested keys including explicit nulls, seven exact allowlisted run types, latest-row ordering, safe failure categories, lowercase SHA-256, read-only `REPEATABLE_READ`, real CGLIB proxy creation, and source/SQL scans proving `error_summary`, `details`, paths, buckets, object keys, credentials, and payloads are never read or exposed. No database was connected by the suite.

```powershell
git add backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/system/operations backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/system/operations
git commit -m "feat(admin): complete security and operations settings"
```

---

### Task 7: Prove full isolated recovery within RPO and RTO

**Files:**
- Create: `deploy/restore/docker-compose.restore.yml`
- Create: `deploy/restore/restore-drill.sh`
- Create: `deploy/restore/resolve-media-closure.sql`
- Create: `deploy/restore/verify-restored-media.sh`
- Create: `deploy/restore/record-drill-result.sh`
- Create: `deploy/restore/record-drill-result.sql`
- Create: `deploy/tests/restore-safety-contract.sh`
- Create: `deploy/systemd/portfolio-restore-reminder.service`
- Create: `deploy/systemd/portfolio-restore-reminder.timer`

**Interfaces:**
- Consumes: one verified self-contained backup-set manifest, its database/Local/COS closure, retained API image/static release or their exact protected Git-tag rebuild, tracked Nginx/Compose configuration, a required historical revision ID, and an offline age identity supplied interactively.
- Produces: an isolated quarterly drill with measured RPO/RTO, a remotely persisted redacted report, and only then an idempotent production `RESTORE_DRILL` maintenance record.

- [ ] **Step 1: Write failing restore safety tests**

Assert the script refuses to run unless:

- `RESTORE_ENV=isolated`;
- the resolved root begins `/srv/portfolio-restore/` and is not `/`, `/opt/portfolio`, `/var/lib/docker`, or a production volume;
- the Compose project name begins `portfolio-restore-`;
- restore API ports bind loopback and differ from 18080;
- production Compose services/volumes are not referenced;
- the operator supplies an age identity via protected file descriptor or root-only temporary mount, not an argument/environment value;
- chosen set manifest, `VERIFIED` marker, encrypted artifacts, and immutable blob references pass verifier-credential checksum verification before decrypt/restore;
- the selected release either has a checksummed image/static bundle or its `sourceContinuityRef` resolves to the recorded commit in the private remote and a trusted build runner reproduces every Task-1 release digest/tree field before transfer;
- the required revision closure is the union of every non-null current `publication.current_revision_id` and at least one operator-selected, existing, non-current historical `content_revision.id`, and every referenced original/variant is present in the selected set;
- one fixture has both LOCAL and COS rows: LOCAL maps only to the isolated media volume, COS maps only to a dedicated non-production drill bucket/prefix, and no production bucket/key is left in the drill database;
- a decrypted Local tar containing an absolute/parent/backslash path, symlink, hardlink, FIFO, socket, block/character device, sparse entry, global/per-entry PAX header (including `path`/`linkpath`), duplicate normalized name, duplicate case-folded name, or entry outside the manifest allowlist is rejected before extraction;
- current public variants and historical-only variants are fetched through actual local Nginx/API routes and their response bytes plus `Content-Type` match the restored database;
- the redacted checksummed drill report is uploaded and read back from the independent remote before any production `maintenance_run` insert is attempted.

- [ ] **Step 2: Create an isolated restore topology**

Use separate PostgreSQL/media volumes, network, Compose project, and loopback API port `28080`. Do not mount `/var/lib/portfolio`, `/opt/portfolio/assets`, `/etc/portfolio`, or production Docker volumes. Provision a dedicated non-production COS drill bucket or an allowlisted drill-only prefix with short-lived credentials; it must differ from both production media and backup destinations. Restore-specific secrets are generated for the drill and destroyed afterward. No production media credential is mounted; externally supplied drill credentials are rotated/revoked after the run.

`deploy/backup/validate-media-tar.py` is reused before any Local extraction. It parses the entire decrypted archive without extracting, accepts only POSIX ustar regular files/directories whose normalized relative paths equal the complete LOCAL list in the authoritative backup manifest, and rejects every link/special/sparse/PAX type, path traversal, repeated normalized/case-folded member, type conflict, unexpected member, or missing expected member. After full validation, its safe extraction mode writes only explicitly selected closure members one at a time to constructed paths in an empty resolved directory beneath the isolated root, ignores archive ownership/permissions, and never follows links or calls an unchecked `extractall`.

- [ ] **Step 3: Implement the timed restore drill**

`restore-drill.sh` records monotonic start time and performs:

1. download the selected `VERIFIED` set manifest/marker and release metadata with the verifier credential; obtain the checksummed release bundle, or rebuild from the exact protected private-Git tag on a trusted runner and require every Task-1 release identity/digest/tree field to match before transfer;
2. validate the self-contained manifest, marker checksum, encrypted artifact checksums, and every referenced `blobs/{sha256}` path before decryption;
3. decrypt the PostgreSQL dump and authoritative mixed-provider media manifest using the offline identity supplied without shell history;
4. start isolated PostgreSQL 17 from the pinned archived image, create the non-login restore equivalents of required role names, and run `pg_restore --clean --if-exists --no-owner` into the empty drill database; grant the drill application's login role membership in `portfolio_runtime` and verify runtime DML but no DDL;
5. run the static `resolve-media-closure.sql`: union every non-null current publication revision with the operator-selected historical revision IDs, require every selected history ID to exist and be non-current, join `revision_media_reference` to `media_variant`/`media_asset`, expand each referenced asset to its original plus required READY variants, and require an exact asset/variant/provider/size/MIME/SHA match in the decrypted backup manifest;
6. decrypt the set's Local tar, run `validate-media-tar.py` against the complete authoritative LOCAL manifest before extraction, use its safe selected-member mode to write only the required closure into the isolated volume, and verify every restored original/variant byte count and SHA-256;
7. for each COS closure row independently, download its immutable backup `blobs/{sha256}` with the verifier credential, verify bytes, upload it to `drills/{drillId}/blobs/{sha256}` in the dedicated drill COS destination, and apply a generated parameterized mapping only in the drill database so provider stays `TENCENT_COS` while bucket/region/original and variant object keys point exclusively at the drill location; LOCAL rows keep provider `LOCAL` and resolve exclusively beneath the isolated volume;
8. assert the mapping covers every closure row exactly once, no restored row points at a production/backup account, bucket, prefix, or host path, and all Local/COS bytes still match recorded size/SHA/MIME before starting the selected retained or exact-tag-rebuilt API with outbound SMTP/jobs disabled;
9. stage the matching admin/public assets and production-equivalent local Nginx fixture, securely supply the off-server TOTP master key or run isolated `admin-recover` after a drill-only recovery point, and authenticate through Nginx without putting credentials on a command line;
10. for every current published media reference, `curl --fail-with-body` its `/api/public/media/{assetId}/{variant}` URL through the local Nginx fixture; for every selected historical-only reference, fetch authenticated `/api/admin/media/{assetId}/preview/{variant}` through the same fixture and follow only the allowlisted drill-COS redirect. Compare the final body SHA-256 and exact normalized `Content-Type` with the restored row, so a database/direct-storage check alone cannot pass;
11. read a current project and the selected historical revision through the API, execute “restore historical revision to new draft” without publishing, and prove the new draft preserves its mixed-provider media references;
12. measure backup age at start (RPO) and monotonic elapsed restore time (RTO), then create a canonical report containing only drill UUID, set/release IDs, status, timestamps/durations, counts, checksum summaries, and an allowlisted error category—never paths, account/bucket/object keys, hosts, credentials, exception text, SQL, PII, or TOTP material;
13. upload the report plus detached SHA-256 to `${BACKUP_PREFIX}/drill-reports/{drillId}/` with the uploader credential and read both back with the verifier credential. Only after that succeeds, run `record-drill-result.sh` on the production host: it accepts only UUID, `SUCCEEDED|FAILED`, RFC-3339 timestamps, 64-hex report checksum, and an allowlisted category, passes them as quoted variables into static `record-drill-result.sql`, and idempotently inserts the fixed type `RESTORE_DRILL` into `maintenance_run`. If production is unavailable, keep the verified remote report and retry this exact idempotent write; the drill is not complete until the production row matches the remote checksum;
14. stop the isolated project, revoke drill COS credentials, remove only verified drill paths, and delete the bounded drill prefix only after the remote report and production maintenance row are proven.

The success and error traps use the same report ordering. A failed drill exits non-zero, but first attempts to persist a redacted `FAILED` report remotely and then records only its verified checksum plus an allowlisted error category in production. A database insert can never precede remote verification, and failure to write the production row leaves the remote report intact for the idempotent reconciler instead of rewriting or discarding evidence.

Success requires backup age at drill start ≤24 hours and elapsed time ≤4 hours.

- [ ] **Step 4: Install quarterly reminder, not unattended restore**

The timer runs on the first Monday of January, April, July, and October and sends an operator reminder/checklist. It must not have access to the age private identity and must not automatically restore production data.

- [ ] **Step 5: Execute one full drill**

```bash
sudo systemd-analyze verify deploy/systemd/portfolio-restore-reminder.service deploy/systemd/portfolio-restore-reminder.timer
bash deploy/tests/restore-safety-contract.sh
sudo RESTORE_ENV=isolated bash deploy/restore/restore-drill.sh --root /srv/portfolio-restore/quarterly-drill --historical-revision "$HISTORICAL_REVISION_ID"
```

Expected: safety contract passes; the independently verified remote report and matching production maintenance row record RPO ≤24h/RTO ≤4h; current and selected historical content are readable; historical restore creates a new draft; every required Local/COS byte passes direct and real Nginx/API SHA/content-type checks.

- [ ] **Step 6: Commit the recovery harness**

```bash
git add deploy/restore deploy/tests/restore-safety-contract.sh deploy/systemd/portfolio-restore-reminder.service deploy/systemd/portfolio-restore-reminder.timer
git commit -m "ops: prove isolated portfolio recovery"
```

---

### Task 8: Write the production and recovery runbooks and perform the release gate

**Files:**
- Create: `docs/operations/production-runbook.md`
- Create: `docs/operations/backup-recovery.md`
- Create: `docs/operations/release-evidence-template.md`
- Modify: `README.md`

**Interfaces:**
- Consumes: all seven plans and all operational scripts.
- Produces: one operator-grade handoff with no undocumented destructive or secret-bearing step.

- [ ] **Step 1: Document production installation and normal operations**

The production runbook must cover:

- confirmed Tencent region/jurisdiction and where the evidence is recorded;
- current ICP state, approval/number recording, DNS cutover and rollback;
- Ubuntu updates, Docker 26/Compose, non-root deploy group, directory ownership, firewall, BaoTa Nginx ownership, TLS, and clock sync;
- secret generation, file permissions, opaque Local volume identity provisioning/rotation, bounded ephemeral JVM/COS scratch, COS/SMTP/rclone least privilege, separate short-lived COS lifecycle-operations credentials, idempotent lifecycle installation during bucket provisioning, independent read-only verification during every preflight, Local staging-scheduler activation evidence, and off-server key custody;
- first database start, Flyway validation, one-time admin bootstrap, TOTP enrollment, and recovery-code offline storage;
- release build, deploy, smoke, rollback, three-release retention, and public-asset cleanup;
- private remote Git/protected release-tag continuity for source plus no-secret Compose/Nginx/runbook configuration, and exact-tag rebuild when no release bundle survives;
- certificate renewal, disk/log monitoring, SMTP/COS failure, job/email dead rows, DB unavailable, failed migration, and compromised credential response;
- safe `admin-recover` requiring a verified recovery point before session/TOTP reset.

- [ ] **Step 2: Document backup and recovery evidence**

The recovery runbook must state RPO 24h/RTO 4h, database/media/static/config/source coverage, the shared advisory-lock/MVCC-snapshot sequence, per-row LOCAL/COS dispatch, immutable `blobs/{sha256}` plus self-contained sets, encrypted Local tar handling, uploader/verifier/pruner least privilege, versioning/Object-Lock boundaries, exact empty/small/large sample formula, 7/4/6 reachability-based garbage collection, remote separation, encryption, failure notification, key loss implications, quarterly drill selection, malicious-tar rejection, current-plus-historical media-closure resolution, mixed-provider drill mapping, actual Nginx/API byte verification, remote-report-before-production-record ordering, exact restore order, validation checklist, measured timestamps, cleanup proof, and credential rotation after a real incident.

Never copy a real secret, bucket key, visitor email, private IP, TOTP seed, recovery code, or backup identity into evidence.

- [ ] **Step 3: Execute the complete release gate**

```bash
docker build --target public-test .
docker build --target admin-test .
cd backend-parent && ./mvnw -B verify && cd ..
bash deploy/tests/release-artifact-contract.sh "/opt/portfolio/releases/$PORTFOLIO_RELEASE_ID" "$PORTFOLIO_RELEASE_ID"
bash deploy/tests/compose-contract.sh
bash deploy/tests/nginx-contract.sh
bash deploy/tests/cos-staging-lifecycle-contract.sh
bash deploy/tests/deploy-state-machine.sh
bash deploy/tests/backup-contract.sh
bash deploy/tests/restore-safety-contract.sh
```

Expected: every command exits 0. Then run `deploy/scripts/smoke.sh` locally; after ICP approval and public cutover, run its public mode.

- [ ] **Step 4: Scan repository and rendered artifacts for forbidden values**

```bash
git grep -n -E '(BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY|SecretId=|SecretKey=|SMTP_PASSWORD=.+|POSTGRES_PASSWORD=.+|AGE-SECRET-KEY-|otpauth://)' -- ':!docs/superpowers/plans/*'
```

Expected: no match outside deliberate redacted test fixtures. Also inspect Docker history, release JSON, Compose-rendered config storage, Nginx output, systemd journal, and backup manifests.

- [ ] **Step 5: Commit documentation**

```bash
git add docs/operations/production-runbook.md docs/operations/backup-recovery.md docs/operations/release-evidence-template.md README.md
git commit -m "docs: add production and recovery runbooks"
```

---

## Phase Completion Gate

- [ ] One clean commit produces matching public manifest, static release, admin release, Spring image, and `releaseId`.
- [ ] Production Compose contains only API/PostgreSQL; PostgreSQL is private and API listens only on loopback.
- [ ] Nginx serves immutable shared hash assets, isolates admin fallback, proxies every API route, overwrites forwarding headers, denies Actuator, and passes syntax/security tests.
- [ ] Mainland public DNS/domain cutover cannot run until final ICP approval and number are explicitly recorded; the current final-review state remains blocked only at that gate.
- [ ] Deployment creates and verifies a database recovery point before migration, then performs health/smoke checks before admin/Nginx switch.
- [ ] A failed deploy automatically returns to the previous API/admin release without reversing Flyway.
- [ ] Current and previous HTML can load their referenced public hash assets; pruning preserves all assets referenced by the latest three releases.
- [ ] Nightly database custom-format dump and mixed-provider media manifest use one exported MVCC snapshot while the shared plan-02 advisory barrier remains held through verified remote-closure publication.
- [ ] Every self-contained set contains a verified encrypted database dump, encrypted Local tar (including the empty case), encrypted authoritative manifest, and every immutable COS `blobs/{sha256}` object required by its per-row provider data.
- [ ] Uploader, verifier, and pruner credentials are distinct and least-privileged; remote account/bucket/non-root prefix/versioning/retention gates pass before any write or delete.
- [ ] Sampling handles empty and fewer-than-20 sets exactly, and retention keeps 7 daily, 4 weekly, and 6 monthly verified sets while garbage collection deletes no blob referenced by any retained set.
- [ ] The exact release commit and no-secret operations configuration are anchored by a protected private-Git tag, and an exact-tag rebuild reproduces the recorded release ID when the portable bundle is unavailable.
- [ ] Admin shows redacted backup/maintenance status but cannot start host operations.
- [ ] An isolated full recovery safely validates Local tar members, restores the union of current plus selected historical media across mixed LOCAL/COS mappings, and proves actual Nginx/API response SHA/content type within RPO 24h/RTO 4h.
- [ ] A redacted checksummed drill report is verified in the independent remote before the matching idempotent production `RESTORE_DRILL` maintenance record is written.
- [ ] Runbooks and release evidence contain no credentials or visitor data.
