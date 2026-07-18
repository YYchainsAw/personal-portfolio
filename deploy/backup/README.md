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

Remote namespaces are intentionally disjoint: restore candidates live under
`sets/{setId}` and `blobs/{sha256}`, restore drill reports under
`drill-reports/{drillId}`, and immutable pruning proposals under
`gc-reports/{reportId}.json`. No generic or caller-supplied namespace is
accepted.

The host never stores an age private identity. Four root-only rclone configs use
four different principals: production-media reader, backup uploader, backup
verifier, and retention pruner. The nightly systemd unit cannot access the
pruner config; the pruning unit cannot access Docker, database, source-media,
uploader, verifier, or mail credentials.

Before the first production run, enable destination-bucket versioning and, when
available, Object Lock/WORM for `sets/` and `blobs/`. Install a root-owned
`BACKUP_PRUNE_GUARD_COMMAND` that implements these fail-closed operations:

- `verify-destination`: return exactly `SAFE` only for the configured account,
  bucket, pruner principal, non-root prefix, versioning, and retention policy.
- `review-candidate`: return exactly `SAFE` only after resolving every candidate
  version ID and proving that protected retention has expired.
- `delete-reviewed`: delete only those reviewed versions and return exactly
  `DELETED`; it must reject bucket roots, prefix roots, wildcards, and drift.

The repository deliberately does not pretend a generic rclone delete can prove
Tencent COS version/Object-Lock state. Production pruning therefore remains
blocked until that provider-specific adapter and the four real principals are
installed and independently reviewed. `prune-remote.sh --dry-run` still requires
the guard, writes an immutable GC proposal, and performs no deletion.

If the remote set and `VERIFIED` marker succeed but the final maintenance SQL
write fails, the set remains a valid restore candidate. The service exits failed,
sends the redacted `MAINTENANCE_WRITE_FAILED` notification, and never removes or
relabels that verified set; operators reconcile only the missing bookkeeping.

Failure notification is independent of `portfolio-api` and uses a root-only
msmtp configuration. Maintenance SQL contains only UUIDs, allowlisted run type
and status, timestamps, the verified set-manifest checksum, and a redacted error
category.
