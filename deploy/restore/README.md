# Isolated restore drill

This directory contains the production restore-drill implementation. The
operator procedure and evidence checklist are published with each release in:

- `docs/operations/production-runbook.md`
- `docs/operations/backup-recovery.md`

The drill is fail-closed. It never restores into the production Compose
project, production PostgreSQL volume, or a production/backup COS location.
The Compose project is exactly `portfolio-restore-<drill UUID>` and every
container, network, and volume is labelled with that UUID.

## External gates

Before invoking `restore-drill.sh`, the operator must provision all of the
following. These are deliberately not stored in Git:

1. A fresh canonical drill UUID and a root-owned, non-group/world-writable
   `/srv/portfolio-restore` parent. The per-drill root must not exist: the
   script creates it atomically with mode `0700` and rejects linked, mounted,
   bind-mounted, foreign-owned, pre-existing, or replaced paths.
2. A root-owned tmpfs directory at
   `/run/portfolio-restore-secrets/<drill UUID>` with mode `0700`, containing
   a `.portfolio-restore-secrets` file whose only line is the drill UUID.
3. Owner-only files in that directory named `app.env`, `postgres.env`,
   `backup-verifier.rclone.conf`, `report-uploader.rclone.conf`,
   `drill-cos.rclone.conf`, `tls.crt`, `tls.key`, `ca.crt`, and
   `admin-auth.json`. The application JDBC URLs must both be exactly
   `jdbc:postgresql://postgres:5432/<POSTGRES_DB>`.
   `app.env` must also bind the selected backup release through
   `PORTFOLIO_RELEASE_ID`; set `PORTFOLIO_STORAGE_DEFAULT_PROVIDER=LOCAL`,
   `PORTFOLIO_COS_ENABLED=true`, and supply only the drill-scoped
   `COS_REGION`, `COS_BUCKET`, `COS_SECRET_ID`, `COS_SECRET_KEY`, and
   `COS_SESSION_TOKEN`. Generate independent canonical Base64 values of at
   least 32 random bytes for `PORTFOLIO_PREVIEW_HMAC_KEY`,
   `PORTFOLIO_CONTACT_DEDUPE_SECRET`, and
   `PORTFOLIO_ANALYTICS_HMAC_SECRET`. Set
   `PORTFOLIO_TOTP_ACTIVE_KEY_VERSION=1` and create a single, fresh
   drill-specific 32-byte Base64 `PORTFOLIO_TOTP_KEY_RING=1=<key>` only so the
   restored application can start. Never copy the production TOTP master key
   ring into the drill.
4. A Tencent STS credential scoped only to the drill bucket/prefix, expiring
   in 15-60 minutes. Every cloud/API/report stage fails closed at five minutes
   before expiry so successful cleanup starts with a safety margin. Production,
   backup, and drill account/bucket/region triples and all four principals must
   be distinct.
5. The reviewed Nginx image already present by exact local image ID, plus a
   verified release bundle containing the API and PostgreSQL docker-save
   archives. All three restore services use `pull_policy: never`.
6. An offline age identity passed by protected file descriptor (preferred) or
   as the exact tmpfs file `age-identity`. It is removed immediately after the
   third decrypt attempt, including every failure path.
7. A current administrator password plus one unused, one-time recovery code in
   `admin-auth.json`, with `method` exactly `RECOVERY_CODE`. The code is
   consumed only in the restored drill database. Pre-generated TOTP values are
   rejected because their 30-second validity cannot survive acquisition,
   decryption, and database startup. The file is destroyed with all other
   drill secrets.

The repository-owned adapters under `adapters/` are mandatory and cannot be
replaced through environment variables. Only the `docker`, `age`, `rclone`,
and `curl` executable boundaries are configurable for reviewed installations
and offline contract fixtures.

Before the restored API starts, every `TENCENT_COS` asset and variant in the
entire drill database is atomically rebound to the exact drill bucket, region,
and UUID prefix. Rows outside the current-plus-selected-history closure receive
deterministic, non-existent `quarantine/` keys; closure rows are then replaced
by their independently verified exact mappings. A second full-database scan
rejects any production/backup tuple or any COS key outside the drill prefix.
`LOCAL` rows retain null bucket/region metadata and resolve only through the
isolated drill media volume.

Run the command exactly as documented under
[`精确执行入口`](../../docs/operations/backup-recovery.md#精确执行入口) in the
published recovery runbook. Do not copy a shortened command from an
implementation plan. A
successful run uploads the redacted report and checksum, independently reads
them back with the verifier principal, records the matching checksum in
production, tears down UUID-labelled resources, purges only the drill COS
prefix, and finally destroys the short-lived credential files.

The operational business objective remains RPO <= 24 hours and RTO <= 4 hours,
and both values are recorded and checked. A quarterly drill is intentionally
stricter: it must finish within the single STS credential's safety window. A
real disaster recovery that lasts longer than one hour must issue a new
short-lived credential with the same drill/disaster-only scope; never extend or
reuse a production principal.

## Quarterly reminder

Install and enable the tracked reminder from the release as root:

```bash
sudo /opt/portfolio/current-ops/deploy/restore/install-reminder.sh
systemctl is-enabled portfolio-restore-reminder.timer
systemctl is-active portfolio-restore-reminder.timer
systemctl list-timers --all portfolio-restore-reminder.timer
```

The installer validates both units, installs them as root-owned mode `0644`,
reloads systemd, enables and starts the timer, and prints its status. The timer
is a reminder only; it never starts a restore automatically.

The repository contract uses local boundary stubs and temporary PostgreSQL 17
and Nginx containers. It does not satisfy the final operational gate: a
quarterly exercise still requires real Tencent backup/readback credentials,
the real release archive, the complete deployed API, and operator-reviewed
evidence in the production environment.
