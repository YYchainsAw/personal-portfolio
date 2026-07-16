# Media storage operations

## Safety model

Storage location is immutable metadata on each media asset. Changing
`portfolio.storage.default-provider` changes only the destination of new uploads;
it does not copy, rewrite, or migrate existing objects. Reads and cleanup continue
to route through the provider, bucket, region, and object key recorded for each
asset.

Originals are immutable and private. They are never exposed as public bucket
objects, overwritten in place, or upscaled. Public delivery uses generated
variants. A provider migration is therefore a separate, audited operation and
must not be attempted by changing an environment variable.

## Production configuration

The `prod` profile binds these deployment values:

| Environment variable | Application property | Required operating rule |
| --- | --- | --- |
| `PORTFOLIO_RELEASE_ID` | `portfolio.release-id` | Exact immutable release identity; required by cleanup idempotency and preflight. |
| `PORTFOLIO_JOBS_WORKER_ENABLED` | `portfolio.jobs.worker-enabled` | Enables the durable database worker; defaults to `false`. |
| `PORTFOLIO_STAGING_CLEANUP_ENABLED` | `portfolio.media.staging-cleanup.enabled` | Enables startup and recurring staging cleanup only when the worker is also enabled; defaults to `false`. |
| `PORTFOLIO_MEDIA_CLEANUP_ENABLED` | `portfolio.media.cleanup.enabled` | Enables 30-day archived-asset deletion; this is a distinct gate and defaults to `false`. |
| `PORTFOLIO_LOCAL_STORAGE` | `portfolio.storage.local.root` | Durable Local provider root; production default is `/var/lib/portfolio/media`. |
| `PORTFOLIO_COS_STAGING_ROOT` | `portfolio.storage.cos.staging-root` | Per-node COS upload scratch; production default is `/tmp/portfolio-cos-staging`. |
| `COS_REGION`, `COS_BUCKET` | `portfolio.storage.cos.region`, `portfolio.storage.cos.bucket` | COS object location. |
| `COS_SECRET_ID`, `COS_SECRET_KEY` | `portfolio.storage.cos.secret-id`, `portfolio.storage.cos.secret-key` | Runtime object credential; inject at runtime and never log it. |
| `COS_SESSION_TOKEN` | `portfolio.storage.cos.session-token` | Optional STS token paired with a temporary runtime or live-smoke object credential; it is not the lifecycle-operations token. |

The repository's `deploy/.env.example` deliberately leaves the release ID,
COS values, and Local volume ID empty. It contains no usable credential, bucket,
or volume identity.

Development and tests use Local storage and keep the worker, staging cleanup,
and archived cleanup disabled. Production enables staging cleanup only when both
the worker gate and the staging-cleanup gate are explicitly `true`. Disabling
either gate removes the staging scheduler. The archived cleanup gate is
independent and must remain `false` until the complete content reference checker
and production preflight are installed.

## Local durable volume identity

`PORTFOLIO_LOCAL_STORAGE` must be a persistent, deployment-owned volume. During
volume provisioning, write one opaque identity value to
`<local-root>/.portfolio-volume-id`, owned and readable only by the deployment
account. Store the exact expected value in the protected deployment environment
as `PORTFOLIO_LOCAL_VOLUME_ID`; never commit, expose, or log it.

Before uploads or workers start, production preflight must compare the protected
expected identity with the file on the mounted volume and fail closed on a
missing file, mismatch, unreadable path, or wrong mount. This prevents a silent
start against an empty host directory or the wrong Docker volume. Creating a new
identity during ordinary startup is forbidden.

The Local staging scavenger owns only canonical application staging files that
are at least 24 hours old and pass the database reservation, filesystem identity,
and publication-fence checks. Per-object durable cleanup jobs are the primary
liveness mechanism; the bounded daily scan is defense in depth. A live
`PROCESSING` upload, unknown file, symlink/reparse point, young object, or
unverified identity is retained and reported rather than age-deleted.

## COS scratch and remote lifecycle

`PORTFOLIO_COS_STAGING_ROOT` is per-node upload scratch, not durable media and
not the remote COS lifecycle. It must resolve beneath the deployment's
size-limited ephemeral `/tmp` tmpfs (normally `/tmp/portfolio-cos-staging`) and
must never be beside, equal to, above, or inside the Local durable media root.
Monitor tmpfs usage and size it for bounded concurrent uploads. The staging
scheduler scavenges scratch entries older than the safe 24-hour boundary.

Remote COS staging is protected by a separately provisioned bucket lifecycle
rule. The repository rule has exactly one enabled rule matching prefix
`staging/` and expiring those objects after one day. It must not match
`originals/`, `variants/`, temporary `smoke/` keys, or backup prefixes. A
checked-in JSON file, an application property, or this document is not evidence
that the remote bucket rule is active.

The host that runs the lifecycle scripts needs Node.js for fail-closed JSON
validation, Python 3, and Tencent's official `cos-python-sdk-v5` package (the
import name is `qcloud_cos`). Verify the isolated operations environment before
supplying credentials:

```bash
node --version
python3 --version
python3 -c 'import qcloud_cos'
```

Install the SDK into an operator-owned virtual environment and activate that
environment before running either script. The scripts use the official
`get_bucket_lifecycle` and `put_bucket_lifecycle` APIs over HTTPS. Tencent's
[lifecycle SDK documentation](https://cloud.tencent.com/document/product/436/85936)
and [lifecycle permission guide](https://intl.cloud.tencent.com/zh/document/product/436/30580)
are the authoritative references for the provider API and CAM action names.

Provision or update the rule explicitly with:

```bash
bash deploy/scripts/install-cos-staging-lifecycle.sh --apply
```

The installer uses a separate, short-lived operations credential. It reads the
complete remote lifecycle configuration, rejects malformed, broad, or
overlapping rules, preserves unrelated safe rules, adds or retains the exact
repository rule idempotently, applies it, and verifies the read-back. It is a
bucket-provisioning action and must not run during routine application startup.
It must never print credentials.

Both lifecycle scripts disable inherited shell xtrace before reading any
credential. Do not modify them to re-enable `set -x`. Their sanitized-output
check reads credential sentinels from the child environment rather than placing
secret values in helper-process arguments. Run them from an isolated operator
shell whose process environment is not visible to untrusted local users.

Tencent COS replaces the bucket's complete lifecycle configuration on PUT. Run
the installer only inside an exclusive lifecycle-change window in which the COS
console, infrastructure automation, and other operators are not changing rules.
Immediately before PUT, the installer reads the complete configuration again
and compares a normalized rule snapshot with its initial read; observed drift
fails before any PUT. This second read narrows the race window but does not make
the provider's full-replacement API conditional, so it is not a substitute for
the exclusive change window. The final read-back must still equal the complete
merged configuration.

Supply `COS_REGION`, `COS_BUCKET`, `COS_SECRET_ID`, `COS_SECRET_KEY`, and the
mandatory temporary-credential `COS_SECURITY_TOKEN` only in that operator
shell. The principal is scoped to the one intended bucket and only
`name/cos:GetBucketLifecycle` plus `name/cos:PutBucketLifecycle`; revoke or let
the temporary credential expire immediately after the read-back succeeds.
`COS_SECURITY_TOKEN` is intentionally absent from `deploy/.env.example` because
it is not an application setting. Ensure `PORTFOLIO_COS_LIFECYCLE_COMMAND` is
unset for real provider operations; that hook exists only for the sanitized
local contract fixture.

`COS_SECURITY_TOKEN` belongs only to the Python lifecycle operations scripts.
The Java runtime and Java live smoke use `COS_SESSION_TOKEN` when their object
credential comes from STS. These variable names are deliberately distinct and
must not be substituted for one another.

Verify the live state independently with:

```bash
bash deploy/scripts/verify-cos-staging-lifecycle.sh --check-live
```

The verifier needs only lifecycle-read authority and fails closed unless the
exact enabled one-day `staging/` rule exists and no staging-intended rule can
reach originals, variants, smoke keys, or backups. The runtime media credential
must have object permissions only and must not receive bucket-lifecycle
permission. Production preflight runs the read-only verifier before enabling
upload/finalization workers; it does not run the installer.

For verification, supply the four `COS_*` location/credential values in an
isolated shell; `COS_SECURITY_TOKEN` is optional when the read-only principal is
not temporary. Scope this principal to only `name/cos:GetBucketLifecycle` on the
one intended bucket. Do not reuse the runtime media credential or the installer
credential.

Both sanitized offline contracts must pass before deployment:

```bash
bash deploy/tests/cos-staging-lifecycle-contract.sh
bash deploy/tests/cos-python-sdk-adapter-contract.sh
```

The lifecycle contract covers exact-rule idempotency, safe merge behavior,
concurrent-drift rejection before PUT, malformed or failed commands, unsafe
overlaps, disabled/missing/broad/wrong-day rules, read-back failure, and
credential-output rejection. The adapter contract injects a file-backed fake
`qcloud_cos` through a temporary `PYTHONPATH`; it makes no network request and
verifies the embedded official-SDK GET/PUT request shape,
`NoSuchLifecycleConfiguration` mapping, full `Rule` preservation, HTTPS/token
wiring (including the verifier's non-temporary `Token=None` path), and
cause-free credential handling. On Windows Git Bash, the adapter contract can
use the standard `py -3` launcher to create a private temporary `python3` shim;
the shim is removed with the rest of the contract work directory.

## Live storage smoke test

The `cos-live` Maven group is opt-in. Run it only with a disposable prefix and
temporary COS credentials after the lifecycle verifier passes:

```powershell
.\mvnw.cmd -pl portfolio-server -am -Dgroups=cos-live test
```

Supply `COS_REGION`, `COS_BUCKET`, `COS_SECRET_ID`, and `COS_SECRET_KEY` to the
Java process. When the smoke credential is temporary, also supply its matching
`COS_SESSION_TOKEN`; do not supply or reuse the lifecycle script's
`COS_SECURITY_TOKEN`.

The smoke suite writes only temporary `smoke/{UUID}/` keys and deletes its known
keys after every test. Confirm that the prefix is empty after the run. Normal
test and build commands exclude `cos-live` and must make no cloud call.

## Archive and physical deletion

Archiving is logical first. The cooling period is 30 days. Physical object
deletion remains disabled until plan 03 supplies and tests the complete checker
for workspace, current-revision, and retained historical-revision references.

An archived asset is eligible only after the complete checker reports no
references twice: first during the 30-day scan and again in the delete handler
immediately before provider I/O. A new reference at either check prevents
deletion. Backup tooling holds the shared media lifecycle advisory lock for the
complete database-and-media backup-set window; deletion holds the exclusive
lease across its final reference check and provider deletion.

Keep `PORTFOLIO_MEDIA_CLEANUP_ENABLED=false` until production preflight verifies
the release ID, Local volume identity, durable worker, staging cleanup, exact COS
lifecycle, and installed complete reference checker. Enabling staging cleanup
does not authorize archived-asset deletion.
