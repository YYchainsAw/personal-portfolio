# Independent encrypted backup branch

`backup-set.sh --verify-upload` is the only nightly entry point. It keeps one
PostgreSQL session alive from the shared media-lifecycle advisory lock through
remote read-back of the immutable `VERIFIED` marker. Database and media exports
consume the same exported repeatable-read snapshot; the snapshot transaction is
committed after both readers finish, while the session lock remains held until
the encrypted closure is independently verified.

The systemd units call the stable root-owned host wrapper
`/usr/local/libexec/portfolio/backup-dispatch.sh`; install it from this directory
as `root:root` with mode `0755`. The release installer must atomically replace
that exact path from `ops/deploy/backup/backup-dispatch.sh` before enabling or
starting either timer; a missing, writable, or stale dispatcher is a deployment
gate, not a condition the unit may work around. The wrapper reads
`/opt/portfolio/current-release` for every run, validates the marker and matching
`release.json`, then resolves the script and Compose file beneath
`/opt/portfolio/releases/{releaseId}`. It does not depend on an undeclared
`current` symlink, and therefore follows Task 4's atomic marker rollback
semantics.

Local bytes are never read from the container-only `/var/lib/portfolio/media`
path. The entry point inspects the fixed external Docker volume, proves its
Mountpoint equals `PORTFOLIO_LOCAL_HOST_ROOT`, validates the owner-only
`.portfolio-volume-id` marker against the protected value, and only then exports
that host root to the no-follow tar builder. A wrong root or marker stops before
the database snapshot is opened, so it cannot silently create a valid empty tar.
The shipped unit deliberately grants read access only to Docker's default
Mountpoint, `/var/lib/docker/volumes/portfolio-local-media/_data`. Production
installation must prove both `docker info` reports `/var/lib/docker` as its root
and `docker volume inspect portfolio-local-media` reports that exact Mountpoint.
If Docker's data-root or the named volume changes, deployment remains blocked
until `backup.env`, both systemd path policies, and their contract tests are
reviewed and changed together.

The canonical set ID is `YYYYMMDDTHHMMSSZ-12hex`. A canonical
`set-manifest.json` has exactly these top-level fields:
`artifacts`, `backupFinishedAt`, `backupStartedAt`, `blobs`, `counts`,
`releaseId`, `retention`, `schemaVersion`, `setId`, and `snapshotId`.
`releaseId` is the validated `12hex-12hex` release selected by the stable
dispatcher; `snapshotId` is the PostgreSQL exported-snapshot identifier shared
by the database dump and media export. Artifact keys and filenames are fixed as
`databaseDump` / `database.dump.age`, `localMediaTar` /
`local-media.tar.age`, and `mediaManifest` / `media-manifest.json.age`; each
declares ciphertext SHA-256 and byte size. Each content-addressed blob declares
its `blobs/{sha256}` path, SHA-256, and byte size. The encrypted media manifest
binds every source object identity and the database plaintext SHA-256 to the
same set and snapshot.

Remote namespaces are intentionally disjoint. Multi-object writes occur only
under `uploading/{setId}`. A restore point becomes visible atomically when the
uploader creates the sole `sets/{setId}/VERIFIED` JSON completion object; that
record binds the upload prefix plus the manifest SHA-256 and byte size. Content
bytes remain under `blobs/{sha256}`, restore drill reports under
`drill-reports/{drillId}`, and immutable pruning proposals under
`gc-reports/{reportId}.json`. A failed second-artifact upload therefore leaves
only an incomplete `uploading/` attempt and can never resemble a completed set.
No generic or caller-supplied namespace is accepted. Stale incomplete uploads
are eligible for the same exact-version, retention-reviewed garbage collection
only after the safety age, and only while no atomic completion record references
their upload prefix.

The host never stores an age private identity. Four root-only rclone configs use
four different principals: production-media reader, backup uploader, backup
verifier, and retention pruner. The nightly systemd unit cannot access the
pruner config; the pruning unit cannot access Docker, database, source-media,
uploader, verifier, or mail credentials. Nightly upload and pruning also take
the same non-blocking root-only `BACKUP_OPERATION_LOCK`. A concurrent run exits
before it reads or mutates the remote namespace.

## Tencent COS pruning guard

The tracked `prune-guard.example.sh` is the production wrapper despite its
historical filename; it executes `cos-prune-guard.py` from the same immutable
release. Leave `BACKUP_PRUNE_GUARD_COMMAND` blank to use it. The implementation
uses rclone only for the proposal's normal namespace reads and immutable report
upload. Safety-critical identity, version enumeration, retention queries, and
deletes use the official Tencent APIs and always carry the exact version ID.
There is no generic rclone `delete`, `deletefile`, or `purge` path.

The pruner never uses the host Python environment. Before enabling its timer,
install the reviewed lock from the current verified release as root:

```bash
systemctl disable --now portfolio-backup-prune.timer
bash "$release_root/ops/deploy/backup/install-cos-prune-runtime.sh"
/opt/portfolio/cos-prune-venv/bin/python3 -B \
  "$release_root/ops/deploy/backup/cos-prune-guard.py" runtime-check
```

The installer accepts no arguments. It requires the existing canonical
`/opt/portfolio` root to remain root-owned mode `0711` and preserves its group;
it never changes `/opt/portfolio` or the standard `/run/lock` metadata. Its
fixed lock order is `/run/lock/portfolio/cos-prune-runtime.lock`, then the same
`/var/backups/portfolio/operation.lock` used by backup and pruning. No runtime
recovery, replacement, or old-runtime removal starts before both locks are
held. Record the allowed dependency network boundary and pre-load/cache the
reviewed artifacts before the maintenance window; Python installation uses
`--require-hashes --no-deps --no-build-isolation` and the exact artifacts in
`requirements-cos-prune.txt`.

The production venv is always `/opt/portfolio/cos-prune-venv`. Its marker binds
the lock SHA-256, the CPython `3.10` ABI, and the exact versions of all 12
runtime distributions: `certifi`, `charset-normalizer`, `cos-python-sdk-v5`,
`crcmod`, `idna`, `pycryptodome`, `requests`, `six`,
`tencentcloud-sdk-python-common`, `tencentcloud-sdk-python-sts`, `urllib3`, and
`xmltodict`. Build tooling (`pip` and `setuptools`) is removed before the venv
is activated, and any missing, extra, or version-drifted distribution fails the
runtime gate. `3.10` pins the Ubuntu 22.04 ABI, not a Python patch release;
Ubuntu-signed security patch updates inside that ABI remain required.

Success ends with `PASS: installed hash-locked COS prune runtime <lock-sha>`;
the following `runtime-check` must return exactly `SAFE` with no stderr. A lock
upgrade requires the prune timer to stay disabled until the new venv, marker,
runtime check, and initial readiness dry-run have all succeeded.

Create `/etc/portfolio/cos-backup-pruner-api.json` as `root:root`, mode `0600`,
without a symlink or hard link:

```json
{"schemaVersion":1,"secretId":"...","secretKey":"...","securityToken":null}
```

For temporary credentials, `securityToken` is the matching STS token. This file
and `rclone-backup-pruner.conf` must represent the same least-privilege pruner
principal. STS `GetCallerIdentity` is still called on every guard invocation;
the returned account ID, principal ID, ARN, and principal type must match the
exact `backup.env` allowlist. Bucket, region, rclone bucket root, and the
non-root prefix must match independently as well. Root identities are rejected;
the pruner must use a dedicated least-privilege CAM user, CAM role, or federated
user. Wildcards and bucket/prefix root candidates are rejected. Every retention
request signs the exact `versionId`; temporary credentials additionally place
`x-cos-security-token` in both the signed-header set and the actual request.
An unsigned/missing token or version binding is rejected before network I/O.

Before the first upload, Tencent must enable Object Lock for the bucket. Set:

- bucket versioning to `Enabled`, never `Suspended`;
- bucket Object Lock to `Enabled` with default mode `COMPLIANCE`;
- default retention to at least `BACKUP_COS_OBJECT_LOCK_MINIMUM_DAYS` (14 by
  default), so every new set object and blob receives a lock automatically;
- a CAM bucket policy that limits writes to the uploader, reads to the verifier,
  and exact-version list/read/retention/delete calls under `production/` to the
  pruner. Do not grant a bucket-wide wildcard delete.

Tencent's documented Object Lock API (reviewed 2026-06-03) exposes COMPLIANCE
retention but no independent legal-hold operation. The provider therefore pins
the explicit capability value
`NOT_SUPPORTED_BY_TENCENT_COS_API_2026-06-03`; any API/SDK change or custom
provider response different from that value fails closed and requires a new
security review. It must never be reinterpreted as “legal hold is off.”

The guard requires all of the following before it creates a review ticket:

- a complete, marker-progressing `ListObjectVersions` traversal, including
  every object version and delete marker; Tencent may return an empty
  `NextVersionIdMarker`, so progression is bound to the exact marker pair;
- two identical version snapshots, with no duplicate, omitted, null-ID,
  historical, non-current, or delete-marker entry for a candidate;
- exactly five current immutable objects for a set, with the remote manifest,
  `VERIFIED` marker, artifact sizes, and local policy evidence all agreeing;
- an exact read-back of each remote manifest and marker by version ID;
- an exact current-version HEAD plus COMPLIANCE `RetainUntilDate` at or before
  the COS server `Date`; unknown, active, or non-COMPLIANCE retention fails;
- a schema-3 GC proposal whose observed sets are partitioned exactly into
  protected and deletable sets, whose protected blob closure is exact, and
  whose stale incomplete-upload candidates are independently enumerated;
- exact current versions for every protected/reachable blob, including listed
  size and a streamed SHA-256 that must equal both its content-addressed key and
  every manifest reference. The protected-set fingerprint commits this entire
  version/size/hash closure.

“Protected” is deliberately broader than the 7 daily / 4 weekly / 6 monthly
union. It includes every observed set not yet old enough for the 14-day safety
window, plus the current and all policy-retained sets. A blob can be proposed
only when at least one complete deletable set references it and no protected
manifest references it. Orphan/unowned blobs are never opportunistically
deleted.

`review-candidate` writes a new mode-0600 ticket that binds destination,
proposal SHA-256, candidate path, exact version records, protected-set
fingerprint, blob owners/size/hash, issue time, and nonce. A fresh ticket is
valid for one hour. Before any provider mutation, `prune-remote.sh` copies the
exact proposal, per-candidate evidence, and tickets into a root-only directory
under `/var/backups/portfolio/prune-transactions`, asks the guard to create each
ticket-adjacent ACTIVE exact-version state, fsyncs the complete `.new` tree,
atomically activates it, and fsyncs the parent directory. A later process must
resume an active transaction before it may create a new proposal. The ACTIVE
state—not an expired or recreated ticket—authorizes only the still-unfinished
subset of the originally reviewed versions.

For sets the guard deletes the exact reviewed `VERIFIED` version first so a
partial provider failure cannot leave an apparently restorable set. A blob is
processed only after no live completed manifest/VERIFIED closure references it;
after every blob/upload mutation, the guard reloads the completed-set universe
and rejects any recreated completion or reference. It proves each candidate has
no remaining version or delete marker and proves the protected closure did not
change. Any pagination gap, concurrent write, disappeared protected set,
response mismatch, new/reappeared version, or failed read-back stops the run.

Power-loss states are deliberately unambiguous: `.new.*` has never mutated the
provider and is safe to discard; an active digest-named directory is complete
and must resume; a `.done.*` directory means provider work and the local commit
rename were durable and needs cleanup only. Completion renames active to
`.done.*`, fsyncs the transaction root, then removes it. Symlink, hard-link,
truncated, extra-entry, wrong-owner, or wrong-mode local state fails closed. The
exact-version requests prevent recovery failure from broadening a delete target.

`prune-remote.sh --dry-run` performs every review and persists the immutable GC
proposal but never invokes `delete-reviewed`. If
`/var/backups/portfolio/prune-initial-dry-run.json` is absent, even an ordinary
invocation is automatically reduced to this non-destructive mode. A successful
run atomically records root-only mode-0600 readiness evidence binding the exact
destination, 7/4/6+safety policy, guard, wrapper, and requirements-lock digests.
The dedicated `portfolio-backup-prune-readiness.service` calls the stable
dispatcher's `prune-dry-run` mode under the same sandbox as the real pruner.
Unit installation must verify the fixed runtime and this service before it may
enable `portfolio-backup-prune.timer`; failure leaves the timer disabled. An
invalid, linked, hard-linked, truncated, or stale readiness marker fails closed.

A normal run reviews every set, stale upload, and blob before activating a
deletion transaction. The offline test
`deploy/tests/cos-prune-guard-contract.sh` exercises the real guard state
machine with no network or credentials, including pagination, malicious
identity, version/delete-marker, Object Lock, active retention, ticket tamper,
concurrent-change, protected blob digest drift, persistent transaction recovery,
false delete confirmation, and read-back failures.

### Custom provider protocol

`BACKUP_COS_PRUNE_API_COMMAND` is an offline-test seam only. Production rejects
it even if the environment variable is set. Test mode itself is accepted only
when the guard and wrapper are root/euid-owned, single-link, non-writable by
group/other, and staged at the fixed private test source root; the provider
command must be an executable private file under its separate fixed fixture
root. An environment flag or a copied production-path script cannot enable the
seam. The command is invoked as
`COMMAND OPERATION`, receives one bounded canonical JSON object on stdin, must
write exactly one JSON response on stdout, no stderr, and return zero. Supported
operations are `inspect-destination`, `list-versions`, `read-version`,
`hash-version`, `get-retention`, and `delete-version`. Schemas are strict and versioned in
`cos-prune-guard.py`; unknown or extra fields fail. `list-versions` must echo the
request's bucket, region, prefix and current markers and provide
`isTruncated`, both next-marker fields, and entries containing key, non-null
version ID, `OBJECT`/`DELETE_MARKER`, latest flag, last-modified, size, and ETag.
`read-version`, `get-retention`, and `delete-version` are bound to the exact key
and version ID. Provider errors must emit no credential-bearing diagnostics.

If the remote set and `VERIFIED` marker succeed but the final maintenance SQL
write fails, the set remains a valid restore candidate. The service exits failed,
sends the redacted `MAINTENANCE_WRITE_FAILED` notification, and never removes or
relabels that verified set; operators reconcile only the missing bookkeeping.

Failure notification is independent of `portfolio-api` and uses a root-only
msmtp configuration. Maintenance SQL contains only UUIDs, allowlisted run type
and status, timestamps, the verified set-manifest checksum, and a redacted error
category.
