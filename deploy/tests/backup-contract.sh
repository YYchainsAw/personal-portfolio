#!/usr/bin/env bash
set -euo pipefail
set +x
umask 077

export LC_ALL=C

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
VALIDATOR="$REPOSITORY_ROOT/deploy/backup/validate-media-tar.py"
BACKUP_SET="$REPOSITORY_ROOT/deploy/backup/backup-set.sh"
BACKUP_DISPATCH="$REPOSITORY_ROOT/deploy/backup/backup-dispatch.sh"
BACKUP_MEDIA="$REPOSITORY_ROOT/deploy/backup/backup-media.sh"
VERIFY_ARTIFACT="$REPOSITORY_ROOT/deploy/backup/verify-artifact.sh"
PRUNE_REMOTE="$REPOSITORY_ROOT/deploy/backup/prune-remote.sh"
PRUNE_GUARD_EXAMPLE="$REPOSITORY_ROOT/deploy/backup/prune-guard.example.sh"
NOTIFY_FAILURE="$REPOSITORY_ROOT/deploy/backup/notify-failure.sh"
BACKUP_SERVICE="$REPOSITORY_ROOT/deploy/systemd/portfolio-backup.service"
BACKUP_TIMER="$REPOSITORY_ROOT/deploy/systemd/portfolio-backup.timer"
PRUNE_SERVICE="$REPOSITORY_ROOT/deploy/systemd/portfolio-backup-prune.service"
PRUNE_READINESS_SERVICE="$REPOSITORY_ROOT/deploy/systemd/portfolio-backup-prune-readiness.service"
PRUNE_TIMER="$REPOSITORY_ROOT/deploy/systemd/portfolio-backup-prune.timer"
WORK_DIRECTORY=''
COMMAND_LOG=''
MAIL_CAPTURE=''
declare -a BACKUP_ENVIRONMENT=()

fail() {
  printf 'backup contract failed: %s\n' "$1" >&2
  exit 1
}

cleanup() {
  if [[ "${KEEP_BACKUP_CONTRACT_FIXTURE:-0}" == 1 ]]; then
    printf 'backup contract fixture retained at %s\n' "$WORK_DIRECTORY" >&2
    return
  fi
  if [[ -n "$WORK_DIRECTORY" && -d "$WORK_DIRECTORY" ]]; then
    rm -rf -- "$WORK_DIRECTORY"
  fi
}
trap cleanup EXIT HUP INT TERM

write_allowlist() {
  local body_a_sha body_b_sha
  body_a_sha="$(printf 'alpha' | sha256sum | awk '{print $1}')"
  body_b_sha="$(printf 'bravo' | sha256sum | awk '{print $1}')"
  jq -Scn \
    --arg first "$body_a_sha" \
    --arg second "$body_b_sha" \
    '{schemaVersion:1,entries:[
      {path:"original/aa/asset-a.webp",size:5,sha256:$first},
      {path:"variants/bb/asset-b-thumb.webp",size:5,sha256:$second}
    ]}' >"$WORK_DIRECTORY/allowlist.json"
  jq -Scn \
    '{schemaVersion:1,entries:[]}' >"$WORK_DIRECTORY/empty-allowlist.json"
  jq -Scn \
    '{schemaVersion:1,paths:["variants/bb/asset-b-thumb.webp"]}' \
    >"$WORK_DIRECTORY/selection.json"
}

make_tar() {
  local kind="$1"
  local output="$2"
  python3 - "$kind" "$output" <<'PY'
import io
import sys
import tarfile

kind, output = sys.argv[1:]


def add_dir(archive, name):
    member = tarfile.TarInfo(name)
    member.type = tarfile.DIRTYPE
    member.mode = 0o700
    member.mtime = 0
    archive.addfile(member)


def add_file(archive, name, body):
    member = tarfile.TarInfo(name)
    member.size = len(body)
    member.mode = 0o600
    member.mtime = 0
    archive.addfile(member, io.BytesIO(body))


if kind == "empty":
    with tarfile.open(output, "w", format=tarfile.USTAR_FORMAT):
        pass
    raise SystemExit(0)

archive_format = tarfile.PAX_FORMAT if kind.startswith("pax-") else tarfile.USTAR_FORMAT
options = {"format": archive_format}
if kind == "pax-global":
    options["pax_headers"] = {"comment": "forbidden"}

with tarfile.open(output, "w", **options) as archive:
    add_dir(archive, "original")
    add_dir(archive, "original/aa")
    add_dir(archive, "variants")
    add_dir(archive, "variants/bb")
    add_file(archive, "original/aa/asset-a.webp", b"alpha")
    if kind != "missing":
        second_body = b"wrong" if kind == "hash" else b"bravo"
        add_file(archive, "variants/bb/asset-b-thumb.webp", second_body)

    if kind == "valid" or kind in {"missing", "hash"}:
        pass
    elif kind == "absolute":
        add_file(archive, "/absolute", b"x")
    elif kind == "parent":
        add_file(archive, "original/../escape", b"x")
    elif kind == "backslash":
        add_file(archive, "original\\escape", b"x")
    elif kind == "unexpected":
        add_file(archive, "original/aa/unexpected", b"x")
    elif kind in {"symlink", "hardlink", "fifo", "character", "block", "socket", "sparse"}:
        member = tarfile.TarInfo("original/aa/forbidden")
        member.type = {
            "symlink": tarfile.SYMTYPE,
            "hardlink": tarfile.LNKTYPE,
            "fifo": tarfile.FIFOTYPE,
            "character": tarfile.CHRTYPE,
            "block": tarfile.BLKTYPE,
            "socket": b"s",
            "sparse": tarfile.GNUTYPE_SPARSE,
        }[kind]
        if kind in {"symlink", "hardlink"}:
            member.linkname = "original/aa/asset-a.webp"
        archive.addfile(member)
    elif kind == "pax-entry":
        member = tarfile.TarInfo("original/aa/pax")
        member.size = 1
        member.pax_headers = {"comment": "forbidden"}
        archive.addfile(member, io.BytesIO(b"x"))
    elif kind == "pax-global":
        add_file(archive, "original/aa/global", b"x")
    elif kind == "duplicate":
        add_file(archive, "original/aa/asset-a.webp", b"alpha")
    elif kind == "casefold":
        add_file(archive, "ORIGINAL/aa/asset-a.webp", b"alpha")
    elif kind == "file-parent":
        add_file(archive, "original/conflict", b"x")
        add_file(archive, "original/conflict/child", b"x")
    else:
        raise SystemExit(f"unknown fixture kind: {kind}")
PY
}

expect_reject() {
  local kind="$1"
  local expected="$2"
  local log="$WORK_DIRECTORY/$kind.log"
  if python3 "$VALIDATOR" --allowlist "$WORK_DIRECTORY/allowlist.json" \
      "$WORK_DIRECTORY/$kind.tar" >"$log" 2>&1; then
    fail "$kind media tar unexpectedly passed"
  fi
  grep -F "$expected" "$log" >/dev/null ||
    fail "$kind media tar failed for an unexpected reason"
}

test_media_tar_validation() {
  write_allowlist
  local kind
  for kind in \
    valid empty missing hash absolute parent backslash unexpected symlink hardlink \
    fifo character block socket sparse pax-entry pax-global duplicate casefold file-parent
  do
    make_tar "$kind" "$WORK_DIRECTORY/$kind.tar"
  done

  local destination="$WORK_DIRECTORY/extracted"
  mkdir "$destination"
  python3 "$VALIDATOR" \
    --allowlist "$WORK_DIRECTORY/allowlist.json" \
    --select "$WORK_DIRECTORY/selection.json" \
    --destination "$destination" \
    "$WORK_DIRECTORY/valid.tar" >"$WORK_DIRECTORY/valid.log" 2>&1 ||
    fail 'valid mixed Local tar was rejected'
  [[ ! -e "$destination/original/aa/asset-a.webp" ]] ||
    fail 'safe extraction wrote an unselected member'
  local extracted_sha expected_sha
  extracted_sha="$(sha256sum "$destination/variants/bb/asset-b-thumb.webp" | awk '{print $1}')"
  expected_sha="$(printf 'bravo' | sha256sum | awk '{print $1}')"
  [[ "$extracted_sha" == "$expected_sha" ]] ||
    fail 'selected extraction changed media bytes'

  python3 "$VALIDATOR" \
    --allowlist "$WORK_DIRECTORY/empty-allowlist.json" \
    "$WORK_DIRECTORY/empty.tar" >"$WORK_DIRECTORY/empty.log" 2>&1 ||
    fail 'valid deterministic empty Local tar was rejected'

  expect_reject missing 'missing an allowlisted file'
  expect_reject hash 'SHA-256 does not match'
  expect_reject absolute 'absolute or contains a backslash'
  expect_reject parent 'parent component'
  expect_reject backslash 'absolute or contains a backslash'
  expect_reject unexpected 'outside the allowlist'
  for kind in symlink hardlink fifo character block socket; do
    expect_reject "$kind" 'link or special entry'
  done
  expect_reject sparse 'sparse archive'
  expect_reject pax-entry 'PAX headers'
  expect_reject pax-global 'PAX headers'
  expect_reject duplicate 'duplicate normalized path'
  expect_reject casefold 'duplicate case-folded path'
  expect_reject file-parent 'outside the allowlist'

  local unsafe_destination="$WORK_DIRECTORY/not-empty"
  mkdir "$unsafe_destination"
  printf 'sentinel\n' >"$unsafe_destination/sentinel"
  if python3 "$VALIDATOR" \
      --allowlist "$WORK_DIRECTORY/allowlist.json" \
      --select "$WORK_DIRECTORY/selection.json" \
      --destination "$unsafe_destination" \
      "$WORK_DIRECTORY/valid.tar" >"$WORK_DIRECTORY/destination.log" 2>&1; then
    fail 'non-empty extraction destination unexpectedly passed'
  fi
  grep -F 'destination must be empty' "$WORK_DIRECTORY/destination.log" >/dev/null ||
    fail 'non-empty extraction destination failed for an unexpected reason'

  local build_root="$WORK_DIRECTORY/build-root"
  mkdir -p "$build_root/original/aa" "$build_root/variants/bb"
  printf 'alpha' >"$build_root/original/aa/asset-a.webp"
  printf 'bravo' >"$build_root/variants/bb/asset-b-thumb.webp"
  python3 "$VALIDATOR" \
    --allowlist "$WORK_DIRECTORY/allowlist.json" \
    --build-root "$build_root" \
    --build-output "$WORK_DIRECTORY/built-a.tar" >/dev/null 2>&1 ||
    fail 'safe deterministic Local tar build failed'
  python3 "$VALIDATOR" \
    --allowlist "$WORK_DIRECTORY/allowlist.json" \
    --build-root "$build_root" \
    --build-output "$WORK_DIRECTORY/built-b.tar" >/dev/null 2>&1 ||
    fail 'second deterministic Local tar build failed'
  cmp -s "$WORK_DIRECTORY/built-a.tar" "$WORK_DIRECTORY/built-b.tar" ||
    fail 'Local tar build is not byte-for-byte deterministic'

  local empty_root="$WORK_DIRECTORY/empty-root"
  mkdir "$empty_root"
  python3 "$VALIDATOR" \
    --allowlist "$WORK_DIRECTORY/empty-allowlist.json" \
    --build-root "$empty_root" \
    --build-output "$WORK_DIRECTORY/built-empty.tar" >/dev/null 2>&1 ||
    fail 'deterministic empty Local tar build failed'
  python3 "$VALIDATOR" \
    --allowlist "$WORK_DIRECTORY/empty-allowlist.json" \
    "$WORK_DIRECTORY/built-empty.tar" >/dev/null 2>&1 ||
    fail 'built empty Local tar failed validation'

  local symlink_root="$WORK_DIRECTORY/symlink-root"
  mkdir -p "$symlink_root/original" "$symlink_root/variants/bb"
  ln -s "$build_root/original/aa" "$symlink_root/original/aa"
  printf 'bravo' >"$symlink_root/variants/bb/asset-b-thumb.webp"
  if python3 "$VALIDATOR" \
      --allowlist "$WORK_DIRECTORY/allowlist.json" \
      --build-root "$symlink_root" \
      --build-output "$WORK_DIRECTORY/symlink-built.tar" >"$WORK_DIRECTORY/symlink-build.log" 2>&1; then
    fail 'Local tar builder followed a source symlink'
  fi
  grep -F 'cannot be opened safely' "$WORK_DIRECTORY/symlink-build.log" >/dev/null ||
    fail 'source symlink failed for an unexpected reason'
}

write_backup_fixture_manifest() {
  local local_original='local-alpha'
  local local_variant='local-thumb'
  local cos_original='cos-original'
  local cos_variant='cos-variant'
  local local_original_sha local_variant_sha cos_original_sha cos_variant_sha
  local_original_sha="$(printf '%s' "$local_original" | sha256sum | awk '{print $1}')"
  local_variant_sha="$(printf '%s' "$local_variant" | sha256sum | awk '{print $1}')"
  cos_original_sha="$(printf '%s' "$cos_original" | sha256sum | awk '{print $1}')"
  cos_variant_sha="$(printf '%s' "$cos_variant" | sha256sum | awk '{print $1}')"

  mkdir -p \
    "$LOCAL_MEDIA_ROOT/original" \
    "$LOCAL_MEDIA_ROOT/variants" \
    "$SOURCE_STORE/cos"
  printf '%s' "$local_original" >"$LOCAL_MEDIA_ROOT/original/local-a.bin"
  printf '%s' "$local_variant" >"$LOCAL_MEDIA_ROOT/variants/local-thumb.bin"
  printf '%s' "$cos_original" >"$SOURCE_STORE/cos/original.bin"
  printf '%s' "$cos_variant" >"$SOURCE_STORE/cos/variant.bin"

  jq -S -c -n \
    --arg localOriginalSha "$local_original_sha" \
    --arg localVariantSha "$local_variant_sha" \
    --arg cosOriginalSha "$cos_original_sha" \
    --arg cosVariantSha "$cos_variant_sha" \
    --argjson localOriginalSize "${#local_original}" \
    --argjson localVariantSize "${#local_variant}" \
    --argjson cosOriginalSize "${#cos_original}" \
    --argjson cosVariantSize "${#cos_variant}" \
    '{schemaVersion:1,snapshotId:"fixture-snapshot-0001",
      rowCounts:{flywaySchemaHistory:7,publication:1,contentRevision:2,
        revisionMediaReference:2,mediaAsset:2,mediaVariant:2,contactMessage:0,adminUser:1},
      rows:[
        {assetId:"11111111-1111-4111-8111-111111111111",objectKind:"ORIGINAL",variantName:"ORIGINAL",
          provider:"LOCAL",bucket:null,region:null,objectKey:"original/local-a.bin",
          mimeType:"application/octet-stream",byteSize:$localOriginalSize,sha256:$localOriginalSha},
        {assetId:"22222222-2222-4222-8222-222222222222",objectKind:"ORIGINAL",variantName:"ORIGINAL",
          provider:"TENCENT_COS",bucket:"media-bucket",region:"ap-guangzhou",objectKey:"cos/original.bin",
          mimeType:"application/octet-stream",byteSize:$cosOriginalSize,sha256:$cosOriginalSha},
        {assetId:"11111111-1111-4111-8111-111111111111",objectKind:"VARIANT",variantName:"thumb",
          provider:"LOCAL",bucket:null,region:null,objectKey:"variants/local-thumb.bin",
          mimeType:"application/octet-stream",byteSize:$localVariantSize,sha256:$localVariantSha},
        {assetId:"22222222-2222-4222-8222-222222222222",objectKind:"VARIANT",variantName:"public",
          provider:"TENCENT_COS",bucket:"media-bucket",region:"ap-guangzhou",objectKey:"cos/variant.bin",
          mimeType:"application/octet-stream",byteSize:$cosVariantSize,sha256:$cosVariantSha}
      ]}' >"$MEDIA_EXPORT_FIXTURE"
}

write_stub_commands() {
  cat >"$BIN_DIRECTORY/date" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
case "$*" in
  '-u +%Y-%m-%dT%H:%M:%SZ') printf '%s\n' '2026-07-01T18:30:00Z' ;;
  '-u +%Y%m%dT%H%M%SZ') printf '%s\n' '20260701T183000Z' ;;
  '-u +%s') printf '%s\n' '1798761600' ;;
  '+%Y-%m-%d') printf '%s\n' '2026-07-02' ;;
  '+%u') printf '%s\n' '4' ;;
  *) exec /usr/bin/date "$@" ;;
esac
STUB

  cat >"$BIN_DIRECTORY/sync" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
/usr/bin/sync "$@"
if [[ "${FIXTURE_SYNC_KILL_AT:-}" =~ ^[12]$ &&
      "$*" == "-f $BACKUP_PRUNE_TRANSACTION_ROOT" ]]; then
  counter_file="$FIXTURE_ROOT/transaction-root-sync-count"
  count=0
  [[ ! -f "$counter_file" ]] || count="$(<"$counter_file")"
  ((count += 1))
  printf '%s\n' "$count" >"$counter_file"
  if [[ "$count" == "$FIXTURE_SYNC_KILL_AT" ]]; then
    kill -KILL "$PPID"
  fi
fi
STUB

  cat >"$BIN_DIRECTORY/age" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
recipient='' output='' input=''
while (($#)); do
  case "$1" in
    -r) recipient="$2"; shift 2 ;;
    -o) output="$2"; shift 2 ;;
    *) input="$1"; shift ;;
  esac
done
[[ "$recipient" == "$BACKUP_AGE_RECIPIENT" && -n "$output" && -f "$input" ]]
printf 'AGE-FIXTURE\n' >"$output"
cat "$input" >>"$output"
printf 'age-encrypt:%s\n' "$(basename "$input")" >>"$FIXTURE_COMMAND_LOG"
STUB

  cat >"$BIN_DIRECTORY/postgres-client" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
mode="$1"
shift
case "$mode" in
  pg_dump)
    custom=false snapshot=false no_owner=false
    for argument in "$@"; do
      case "$argument" in
        --format=custom) custom=true ;;
        --snapshot=fixture-snapshot-0001) snapshot=true ;;
        --no-owner) no_owner=true ;;
      esac
    done
    [[ "$custom" == true && "$snapshot" == true && "$no_owner" == true ]]
    printf '%s\n' 'pg-dump-custom-fixture'
    printf '%s\n' 'pg-dump:custom:snapshot-ok' >>"$FIXTURE_COMMAND_LOG"
    ;;
  pg_restore)
    [[ "$*" == '--list' ]]
    cat >/dev/null
    for relation in \
      flyway_schema_history publication content_revision revision_media_reference \
      media_asset media_variant contact_message admin_user
    do
      printf '1; 1259 1 TABLE portfolio %s owner\n' "$relation"
    done
    printf '%s\n' 'pg-restore:list' >>"$FIXTURE_COMMAND_LOG"
    ;;
  psql)
    run_type='' status='' category=''
    for argument in "$@"; do
      case "$argument" in
        run_type=*) run_type="${argument#run_type=}" ;;
        status=*) status="${argument#status=}" ;;
        error_category=*) category="${argument#error_category=}" ;;
      esac
    done
    if [[ -n "$run_type" ]]; then
      cat >/dev/null
      if [[ "${FIXTURE_MAINTENANCE_FAIL_ONCE:-}" == "$run_type:$status" &&
           ! -e "$FIXTURE_ROOT/maintenance-failed-once" ]]; then
        : >"$FIXTURE_ROOT/maintenance-failed-once"
        printf 'maintenance-write-failed:%s:%s\n' "$run_type" "$status" >>"$FIXTURE_COMMAND_LOG"
        exit 72
      fi
      printf 'maintenance:%s:%s:%s\n' "$run_type" "$status" "$category" >>"$FIXTURE_COMMAND_LOG"
      exit 0
    fi
    IFS= read -r first || exit 1
    if [[ "$first" == "\\set backup_snapshot 'fixture-snapshot-0001'" ]]; then
      sql="$(cat)"
      grep -F "SET TRANSACTION SNAPSHOT :'backup_snapshot';" <<<"$sql" >/dev/null
      cat "$FIXTURE_MEDIA_EXPORT"
      printf '%s\n' 'media-reader:snapshot-ok' >>"$FIXTURE_COMMAND_LOG"
      exit 0
    fi
    printf '%s\n' 'keeper:start' >>"$FIXTURE_COMMAND_LOG"
    handle_line() {
      local line="$1"
      case "$line" in
        *KEEPER_READY*) printf '%s\n' KEEPER_READY ;;
        *pg_advisory_lock_shared*) printf '%s\n' 'keeper:lock-shared' >>"$FIXTURE_COMMAND_LOG" ;;
        *LOCK_ACQUIRED*) printf '%s\n' LOCK_ACQUIRED ;;
        *pg_export_snapshot*)
          printf '%s\n' SNAPSHOT:fixture-snapshot-0001
          printf '%s\n' 'keeper:snapshot-exported' >>"$FIXTURE_COMMAND_LOG"
          ;;
        COMMIT*) printf '%s\n' 'keeper:snapshot-commit' >>"$FIXTURE_COMMAND_LOG" ;;
        *SNAPSHOT_COMMITTED*) printf '%s\n' SNAPSHOT_COMMITTED ;;
        *pg_advisory_unlock_shared*)
          printf '%s\n' UNLOCK:t
          printf '%s\n' 'keeper:unlock-shared' >>"$FIXTURE_COMMAND_LOG"
          ;;
        *KEEPER_DONE*) printf '%s\n' KEEPER_DONE ;;
        '\q') exit 0 ;;
      esac
    }
    handle_line "$first"
    while IFS= read -r line; do
      handle_line "$line"
    done
    ;;
  *) exit 2 ;;
esac
STUB

  cat >"$BIN_DIRECTORY/docker" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1" == volume && "$2" == inspect && "$3" == --format ]]
[[ "$4" == '{{.Name}}|{{.Mountpoint}}' && "$5" == "$PORTFOLIO_LOCAL_VOLUME_NAME" ]]
mountpoint="${FIXTURE_VOLUME_MOUNTPOINT_OVERRIDE:-$FIXTURE_LOCAL_VOLUME_ROOT}"
printf '%s|%s\n' "$PORTFOLIO_LOCAL_VOLUME_NAME" "$mountpoint"
printf '%s\n' 'docker-volume-inspect:redacted' >>"$FIXTURE_COMMAND_LOG"
STUB

  cat >"$BIN_DIRECTORY/rclone" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1" == --config ]]
config="$2"
shift 2
action="$1"
shift
case "$config" in
  "$MEDIA_SOURCE_RCLONE_CONFIG") role=source ;;
  "$BACKUP_UPLOAD_RCLONE_CONFIG") role=uploader ;;
  "$BACKUP_VERIFY_RCLONE_CONFIG") role=verifier ;;
  "$BACKUP_PRUNE_RCLONE_CONFIG") role=pruner ;;
  *) exit 90 ;;
esac
remote_path() {
  local value="$1"
  case "$value" in
    "$FIXTURE_BACKUP_REMOTE_BASE") printf '%s\n' "$FIXTURE_BACKUP_STORE" ;;
    "$FIXTURE_BACKUP_REMOTE_BASE"/*) printf '%s/%s\n' "$FIXTURE_BACKUP_STORE" "${value#"$FIXTURE_BACKUP_REMOTE_BASE"/}" ;;
    "$FIXTURE_MEDIA_REMOTE_BASE"/*) printf '%s/%s\n' "$FIXTURE_SOURCE_STORE" "${value#"$FIXTURE_MEDIA_REMOTE_BASE"/}" ;;
    *) return 1 ;;
  esac
}
case "$action" in
  copyto)
    immutable=false
    if [[ "${1:-}" == --immutable ]]; then immutable=true; shift; fi
    [[ "${1:-}" == -- ]] && shift
    source="$1"
    destination="$2"
    case "$role" in
      source)
        source_path="$(remote_path "$source")"
        [[ -f "$source_path" ]]
        cp -- "$source_path" "$destination"
        printf 'source-read:%s\n' "${source#"$FIXTURE_MEDIA_REMOTE_BASE"/}" >>"$FIXTURE_COMMAND_LOG"
        ;;
      uploader)
        destination_path="$(remote_path "$destination")"
        [[ "${FIXTURE_UPLOAD_FAIL_PATTERN:-}" != '' && "$destination" == *"$FIXTURE_UPLOAD_FAIL_PATTERN"* ]] && exit 71
        [[ "$immutable" == true && ! -e "$destination_path" ]]
        mkdir -p -- "$(dirname "$destination_path")"
        cp -- "$source" "$destination_path"
        printf 'upload:%s\n' "${destination#"$FIXTURE_BACKUP_REMOTE_BASE"/}" >>"$FIXTURE_COMMAND_LOG"
        ;;
      verifier)
        source_path="$(remote_path "$source")"
        [[ -f "$source_path" ]]
        cp -- "$source_path" "$destination"
        printf '%s-read:%s\n' "$role" "${source#"$FIXTURE_BACKUP_REMOTE_BASE"/}" >>"$FIXTURE_COMMAND_LOG"
        ;;
      pruner)
        if [[ "$immutable" == true ]]; then
          destination_path="$(remote_path "$destination")"
          [[ ! -e "$destination_path" ]]
          mkdir -p -- "$(dirname "$destination_path")"
          cp -- "$source" "$destination_path"
          printf 'pruner-report:%s\n' "${destination#"$FIXTURE_BACKUP_REMOTE_BASE"/}" >>"$FIXTURE_COMMAND_LOG"
        else
          source_path="$(remote_path "$source")"
          [[ -f "$source_path" ]]
          cp -- "$source_path" "$destination"
          printf 'pruner-read:%s\n' "${source#"$FIXTURE_BACKUP_REMOTE_BASE"/}" >>"$FIXTURE_COMMAND_LOG"
        fi
        ;;
    esac
    ;;
  lsf)
    [[ "$role" == pruner ]]
    while [[ "${1:-}" == --* ]]; do
      if [[ "$1" == --max-depth ]]; then shift 2; else shift; fi
    done
    [[ "${1:-}" == -- ]] && shift
    root="$(remote_path "$1")"
    if [[ -d "$root" ]]; then
      find "$root" -mindepth 1 -maxdepth 1 -type d -printf '%f/\n' | LC_ALL=C sort
    fi
    printf 'pruner-list:%s\n' "${1##*/}" >>"$FIXTURE_COMMAND_LOG"
    ;;
  lsjson)
    [[ "$role" == pruner ]]
    while [[ "${1:-}" == --* ]]; do shift; done
    [[ "${1:-}" == -- ]] && shift
    root="$(remote_path "$1")"
    first=true
    printf '['
    if [[ -d "$root" ]]; then
      while IFS= read -r file; do
        [[ -n "$file" ]] || continue
        [[ "$first" == true ]] || printf ','
        first=false
        jq -c -n \
          --arg Path "$(basename "$file")" \
          --arg ModTime '2026-01-01T00:00:00Z' \
          --argjson Size "$(stat -Lc '%s' "$file")" \
          '{Path:$Path,ModTime:$ModTime,Size:$Size}'
      done < <(find "$root" -mindepth 1 -maxdepth 1 -type f -print | LC_ALL=C sort)
    fi
    printf ']\n'
    printf 'pruner-list:%s\n' "${1##*/}" >>"$FIXTURE_COMMAND_LOG"
    ;;
  *) exit 91 ;;
esac
STUB

  cat >"$BIN_DIRECTORY/msmtp" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
cat >"$FIXTURE_MAIL_CAPTURE"
printf '%s\n' 'smtp-send:redacted' >>"$FIXTURE_COMMAND_LOG"
STUB

  cat >"$BIN_DIRECTORY/prune-guard" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
mode="$1"
shift
kind='destination' relative='prefix' ticket_output=''
prepare_only=false
while (($#)); do
  case "$1" in
    --kind) kind="$2"; shift 2 ;;
    --relative-path) relative="$2"; shift 2 ;;
    --ticket-output) ticket_output="$2"; shift 2 ;;
    --prepare-only) prepare_only=true; shift ;;
    --prefix)
      [[ -n "$2" && "$2" != / && "$2" != . ]]
      shift 2
      ;;
    *) shift 2 ;;
  esac
done
[[ "$relative" != / && "$relative" != . && "$relative" != '' ]]
printf 'guard:%s:%s:%s\n' "$mode" "$kind" "$relative" >>"$FIXTURE_COMMAND_LOG"
case "$mode" in
  verify-destination) printf '%s\n' SAFE ;;
  review-candidate)
    [[ "$ticket_output" == /* ]]
    printf '{"schemaVersion":1}\n' >"$ticket_output"
    chmod 0600 "$ticket_output"
    printf '%s\n' SAFE
    ;;
  delete-reviewed)
    if [[ "$prepare_only" == true ]]; then
      if [[ "${FIXTURE_GUARD_KILL_ON_PREPARE:-}" == 1 &&
            ! -e "$FIXTURE_ROOT/prepare-kill-fired" ]]; then
        : >"$FIXTURE_ROOT/prepare-kill-fired"
        kill -KILL "$PPID"
      fi
      printf '%s\n' PREPARED
    else
      printf '%s\n' DELETED
    fi
    ;;
  *) exit 2 ;;
esac
STUB

  cat >"$BIN_DIRECTORY/backup-database" <<'STUB'
#!/usr/bin/env bash
exec bash "$FIXTURE_REPOSITORY_ROOT/deploy/backup/backup-database.sh" "$@"
STUB
  cat >"$BIN_DIRECTORY/backup-media" <<'STUB'
#!/usr/bin/env bash
exec bash "$FIXTURE_REPOSITORY_ROOT/deploy/backup/backup-media.sh" "$@"
STUB
  cat >"$BIN_DIRECTORY/verify-artifact" <<'STUB'
#!/usr/bin/env bash
exec bash "$FIXTURE_REPOSITORY_ROOT/deploy/backup/verify-artifact.sh" "$@"
STUB
  cat >"$BIN_DIRECTORY/validate-media-tar" <<'STUB'
#!/usr/bin/env bash
exec python3 -B "$FIXTURE_REPOSITORY_ROOT/deploy/backup/validate-media-tar.py" "$@"
STUB
  cat >"$BIN_DIRECTORY/notify-failure" <<'STUB'
#!/usr/bin/env bash
exec bash "$FIXTURE_REPOSITORY_ROOT/deploy/backup/notify-failure.sh" "$@"
STUB
  chmod 0700 "$BIN_DIRECTORY"/*
}

setup_backup_fixtures() {
  FIXTURE_ROOT="$WORK_DIRECTORY/backup-fixture"
  BIN_DIRECTORY="$FIXTURE_ROOT/bin"
  CONFIG_DIRECTORY="$FIXTURE_ROOT/config"
  REMOTE_STORE="$FIXTURE_ROOT/backup-store"
  SOURCE_STORE="$FIXTURE_ROOT/source-store"
  LOCAL_MEDIA_ROOT="$FIXTURE_ROOT/local-media"
  STAGING_ROOT="$FIXTURE_ROOT/staging"
  PRUNE_STAGING_ROOT="$FIXTURE_ROOT/prune-staging"
  PRUNE_TRANSACTION_ROOT="$FIXTURE_ROOT/prune-transactions"
  PRUNE_READINESS_FILE="$FIXTURE_ROOT/prune-initial-dry-run.json"
  ZONEINFO_ROOT="$FIXTURE_ROOT/zoneinfo"
  MEDIA_EXPORT_FIXTURE="$FIXTURE_ROOT/media-export.json"
  COMMAND_LOG="$FIXTURE_ROOT/commands.log"
  MAIL_CAPTURE="$FIXTURE_ROOT/mail.txt"
  mkdir -m 0700 \
    "$FIXTURE_ROOT" "$BIN_DIRECTORY" "$CONFIG_DIRECTORY" "$REMOTE_STORE" \
    "$SOURCE_STORE" "$LOCAL_MEDIA_ROOT" "$STAGING_ROOT" "$PRUNE_STAGING_ROOT" \
    "$PRUNE_TRANSACTION_ROOT" \
    "$ZONEINFO_ROOT"
  mkdir -p "$ZONEINFO_ROOT/Asia"
  printf 'fixture-zoneinfo\n' >"$ZONEINFO_ROOT/Asia/Hong_Kong"
  : >"$COMMAND_LOG"

  SOURCE_CONFIG="$CONFIG_DIRECTORY/source.conf"
  UPLOAD_CONFIG="$CONFIG_DIRECTORY/uploader.conf"
  VERIFY_CONFIG="$CONFIG_DIRECTORY/verifier.conf"
  PRUNE_CONFIG="$CONFIG_DIRECTORY/pruner.conf"
  MSMTP_CONFIG="$CONFIG_DIRECTORY/msmtp.conf"
  printf '%s\n' 'source-credential-MUST-NOT-LEAK' >"$SOURCE_CONFIG"
  printf '%s\n' 'uploader-credential-MUST-NOT-LEAK' >"$UPLOAD_CONFIG"
  printf '%s\n' 'verifier-credential-MUST-NOT-LEAK' >"$VERIFY_CONFIG"
  printf '%s\n' 'pruner-credential-MUST-NOT-LEAK' >"$PRUNE_CONFIG"
  printf '%s\n' 'smtp-password-MUST-NOT-LEAK' >"$MSMTP_CONFIG"
  chmod 0600 "$CONFIG_DIRECTORY"/*

  write_backup_fixture_manifest
  printf '%s\n' 'volume-contract-id' >"$LOCAL_MEDIA_ROOT/.portfolio-volume-id"
  chmod 0600 "$LOCAL_MEDIA_ROOT/.portfolio-volume-id"
  write_stub_commands

  BACKUP_ENVIRONMENT=(
    "PATH=$BIN_DIRECTORY:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    "FIXTURE_REPOSITORY_ROOT=$REPOSITORY_ROOT"
    "FIXTURE_ROOT=$FIXTURE_ROOT"
    "FIXTURE_COMMAND_LOG=$COMMAND_LOG"
    "FIXTURE_MAIL_CAPTURE=$MAIL_CAPTURE"
    "FIXTURE_MEDIA_EXPORT=$MEDIA_EXPORT_FIXTURE"
    "FIXTURE_BACKUP_STORE=$REMOTE_STORE"
    "FIXTURE_SOURCE_STORE=$SOURCE_STORE"
    "FIXTURE_LOCAL_VOLUME_ROOT=$LOCAL_MEDIA_ROOT"
    'FIXTURE_BACKUP_REMOTE_BASE=portfolio-backup:backup-bucket'
    'FIXTURE_MEDIA_REMOTE_BASE=portfolio-media:media-bucket'
    "BACKUP_POSTGRES_CLIENT_COMMAND=$BIN_DIRECTORY/postgres-client"
    "BACKUP_DOCKER_COMMAND=$BIN_DIRECTORY/docker"
    "BACKUP_RCLONE_COMMAND=$BIN_DIRECTORY/rclone"
    "BACKUP_AGE_COMMAND=$BIN_DIRECTORY/age"
    "BACKUP_DATE_COMMAND=$BIN_DIRECTORY/date"
    "BACKUP_DATABASE_COMMAND=$BIN_DIRECTORY/backup-database"
    "BACKUP_MEDIA_COMMAND=$BIN_DIRECTORY/backup-media"
    "BACKUP_VERIFY_COMMAND=$BIN_DIRECTORY/verify-artifact"
    "BACKUP_MEDIA_TAR_COMMAND=$BIN_DIRECTORY/validate-media-tar"
    "BACKUP_NOTIFY_COMMAND=$BIN_DIRECTORY/notify-failure"
    "BACKUP_MSMTP_COMMAND=$BIN_DIRECTORY/msmtp"
    "BACKUP_PRUNE_GUARD_COMMAND=$BIN_DIRECTORY/prune-guard"
    "BACKUP_MSMTP_CONFIG=$MSMTP_CONFIG"
    'BACKUP_EMAIL_TO=operator@example.test'
    'BACKUP_EMAIL_FROM=backup@example.test'
    'BACKUP_AGE_RECIPIENT=age1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq'
    'BACKUP_REMOTE=portfolio-backup:backup-bucket'
    'BACKUP_PREFIX=production'
    'BACKUP_DESTINATION_ACCOUNT_ID=backup-account'
    'BACKUP_DESTINATION_BUCKET=backup-bucket'
    'BACKUP_DESTINATION_REGION=ap-guangzhou'
    "BACKUP_UPLOAD_RCLONE_CONFIG=$UPLOAD_CONFIG"
    "BACKUP_VERIFY_RCLONE_CONFIG=$VERIFY_CONFIG"
    "BACKUP_PRUNE_RCLONE_CONFIG=$PRUNE_CONFIG"
    'BACKUP_UPLOAD_PRINCIPAL_ID=backup-uploader'
    'BACKUP_VERIFY_PRINCIPAL_ID=backup-verifier'
    'BACKUP_PRUNE_PRINCIPAL_ID=backup-pruner'
    'MEDIA_SOURCE_RCLONE_REMOTE=portfolio-media:media-bucket'
    "MEDIA_SOURCE_RCLONE_CONFIG=$SOURCE_CONFIG"
    'MEDIA_SOURCE_ACCOUNT_ID=media-account'
    'MEDIA_SOURCE_BUCKET=media-bucket'
    'MEDIA_SOURCE_REGION=ap-guangzhou'
    'MEDIA_SOURCE_PRINCIPAL_ID=media-reader'
    'PORTFOLIO_LOCAL_VOLUME_NAME=portfolio-local-media'
    "PORTFOLIO_LOCAL_HOST_ROOT=$LOCAL_MEDIA_ROOT"
    'PORTFOLIO_LOCAL_VOLUME_ID=volume-contract-id'
    "BACKUP_STAGING_ROOT=$STAGING_ROOT"
    "BACKUP_PRUNE_STAGING_ROOT=$PRUNE_STAGING_ROOT"
    "BACKUP_PRUNE_TRANSACTION_ROOT=$PRUNE_TRANSACTION_ROOT"
    "BACKUP_PRUNE_READINESS_FILE=$PRUNE_READINESS_FILE"
    "BACKUP_OPERATION_LOCK=$FIXTURE_ROOT/operation.lock"
    'BACKUP_TIMEZONE=Asia/Hong_Kong'
    "BACKUP_ZONEINFO_ROOT=$ZONEINFO_ROOT"
    'BACKUP_WEEKLY_DAY=4'
    'BACKUP_RETENTION_SAFETY_DAYS=14'
    'PORTFOLIO_RELEASE_ID=aaaaaaaaaaaa-bbbbbbbbbbbb'
    'BACKUP_KEEPER_TIMEOUT_SECONDS=10'
  )
}

event_line() {
  local needle="$1"
  awk -v needle="$needle" 'index($0, needle) {print NR; exit}' "$COMMAND_LOG"
}

assert_before() {
  local first="$1"
  local second="$2"
  local first_line second_line
  first_line="$(event_line "$first")"
  second_line="$(event_line "$second")"
  [[ "$first_line" =~ ^[1-9][0-9]*$ && "$second_line" =~ ^[1-9][0-9]*$ &&
     "$first_line" -lt "$second_line" ]] ||
    fail "expected $first before $second"
}

assert_regex_before() {
  local first_regex="$1"
  local second_regex="$2"
  local first_line second_line
  first_line="$(awk -v pattern="$first_regex" '$0 ~ pattern {print NR; exit}' "$COMMAND_LOG")"
  second_line="$(awk -v pattern="$second_regex" '$0 ~ pattern {print NR; exit}' "$COMMAND_LOG")"
  [[ "$first_line" =~ ^[1-9][0-9]*$ && "$second_line" =~ ^[1-9][0-9]*$ &&
     "$first_line" -lt "$second_line" ]] ||
    fail "expected /$first_regex/ before /$second_regex/"
}

assert_no_fixture_secret() {
  local path="$1"
  for secret in \
    source-credential-MUST-NOT-LEAK \
    uploader-credential-MUST-NOT-LEAK \
    verifier-credential-MUST-NOT-LEAK \
    pruner-credential-MUST-NOT-LEAK \
    smtp-password-MUST-NOT-LEAK
  do
    if grep -F "$secret" "$path" >/dev/null 2>&1; then
      fail 'a protected fixture value entered command output or logs'
    fi
  done
}

expect_command_failure() {
  local label="$1" expected="$2"
  shift 2
  local log="$FIXTURE_ROOT/$label.log"
  if "$@" >"$log" 2>&1; then
    fail "$label unexpectedly succeeded"
  fi
  grep -F "$expected" "$log" >/dev/null || {
    sed -n '1,100p' "$log" >&2
    fail "$label failed for an unexpected reason"
  }
}

exercise_private_directory_metadata_contract() {
  local label="$1" directory="$2"
  shift 2
  local -a command=("$@")
  local observed
  [[ "$(stat -Lc '%u:%g:%a' -- "$directory")" == 0:0:700 ]] ||
    fail "$label fixture did not start root:root mode 0700"

  chmod 0750 "$directory"
  observed="$(stat -Lc '%d:%i:%u:%g:%a' -- "$directory")"
  expect_command_failure "$label-mode" 'must be root:root mode 0700' "${command[@]}"
  [[ "$(stat -Lc '%d:%i:%u:%g:%a' -- "$directory")" == "$observed" ]] ||
    fail "$label unsafe mode was repaired or replaced"
  chmod 0700 "$directory"

  chown 0:1234 "$directory"
  observed="$(stat -Lc '%d:%i:%u:%g:%a' -- "$directory")"
  expect_command_failure "$label-group" 'must be root:root mode 0700' "${command[@]}"
  [[ "$(stat -Lc '%d:%i:%u:%g:%a' -- "$directory")" == "$observed" ]] ||
    fail "$label unsafe group was repaired or replaced"
  chown 0:0 "$directory"

  chown 0:1234 "$directory"
  chmod 0750 "$directory"
  observed="$(stat -Lc '%d:%i:%u:%g:%a' -- "$directory")"
  expect_command_failure "$label-group-mode" 'must be root:root mode 0700' "${command[@]}"
  [[ "$(stat -Lc '%d:%i:%u:%g:%a' -- "$directory")" == "$observed" ]] ||
    fail "$label unsafe group/mode combination was repaired or replaced"
  chown 0:0 "$directory"
  chmod 0700 "$directory"

  chown 1234:0 "$directory"
  observed="$(stat -Lc '%d:%i:%u:%g:%a' -- "$directory")"
  expect_command_failure "$label-owner" 'must be root:root mode 0700' "${command[@]}"
  [[ "$(stat -Lc '%d:%i:%u:%g:%a' -- "$directory")" == "$observed" ]] ||
    fail "$label unsafe owner was repaired or replaced"
  chown 0:0 "$directory"
}

test_existing_private_directories_are_never_repaired() {
  local operation_lock="$FIXTURE_ROOT/operation.lock"
  local readiness_parent="$FIXTURE_ROOT/readiness-parent"
  local nested_real="$FIXTURE_ROOT/nested-real"
  local nested_alias="$FIXTURE_ROOT/nested-alias"

  rm -f -- "$operation_lock"
  exercise_private_directory_metadata_contract operation-lock-parent "$FIXTURE_ROOT" \
    env "${BACKUP_ENVIRONMENT[@]}" bash "$BACKUP_SET" --verify-upload

  ln -s -- "$FIXTURE_ROOT/missing-operation-lock" "$operation_lock"
  expect_command_failure operation-lock-dangling-symlink \
    'shared backup/prune operation lock path is unsafe' \
    env "${BACKUP_ENVIRONMENT[@]}" bash "$BACKUP_SET" --verify-upload
  [[ -L "$operation_lock" && "$(readlink -- "$operation_lock")" == "$FIXTURE_ROOT/missing-operation-lock" ]] ||
    fail 'dangling operation lock was followed, repaired, or replaced'
  rm -- "$operation_lock"

  mkdir -m 0700 -- "$nested_real"
  ln -s -- "$nested_real" "$nested_alias"
  expect_command_failure operation-lock-symlink-ancestor \
    'shared backup/prune operation lock path is unsafe' \
    env "${BACKUP_ENVIRONMENT[@]}" \
      "BACKUP_OPERATION_LOCK=$nested_alias/operation.lock" \
      bash "$BACKUP_SET" --verify-upload
  expect_command_failure operation-lock-dotdot \
    'shared backup/prune operation lock path is unsafe' \
    env "${BACKUP_ENVIRONMENT[@]}" \
      "BACKUP_OPERATION_LOCK=$nested_real/../operation-dotdot.lock" \
      bash "$BACKUP_SET" --verify-upload
  expect_command_failure operation-lock-double-slash \
    'shared backup/prune operation lock path is unsafe' \
    env "${BACKUP_ENVIRONMENT[@]}" \
      "BACKUP_OPERATION_LOCK=$FIXTURE_ROOT//operation.lock" \
      bash "$BACKUP_SET" --verify-upload
  [[ ! -e "$nested_real/operation.lock" && ! -e "$FIXTURE_ROOT/operation-dotdot.lock" ]] ||
    fail 'non-canonical operation lock path created a file'

  exercise_private_directory_metadata_contract backup-staging "$STAGING_ROOT" \
    env "${BACKUP_ENVIRONMENT[@]}" bash "$BACKUP_SET" --verify-upload
  exercise_private_directory_metadata_contract prune-staging "$PRUNE_STAGING_ROOT" \
    env "${BACKUP_ENVIRONMENT[@]}" bash "$PRUNE_REMOTE" --dry-run
  exercise_private_directory_metadata_contract prune-transaction "$PRUNE_TRANSACTION_ROOT" \
    env "${BACKUP_ENVIRONMENT[@]}" bash "$PRUNE_REMOTE" --dry-run

  mkdir -m 0700 -- "$readiness_parent"
  exercise_private_directory_metadata_contract prune-readiness-parent "$readiness_parent" \
    env "${BACKUP_ENVIRONMENT[@]}" \
      "BACKUP_PRUNE_READINESS_FILE=$readiness_parent/readiness.json" \
      bash "$PRUNE_REMOTE" --dry-run

  expect_command_failure backup-staging-symlink-ancestor \
    'backup staging root path is unsafe' \
    env "${BACKUP_ENVIRONMENT[@]}" \
      "BACKUP_STAGING_ROOT=$nested_alias/staging" \
      bash "$BACKUP_SET" --verify-upload
  expect_command_failure backup-staging-dotdot \
    'backup staging root path is unsafe' \
    env "${BACKUP_ENVIRONMENT[@]}" \
      "BACKUP_STAGING_ROOT=$nested_real/../staging-dotdot" \
      bash "$BACKUP_SET" --verify-upload
  expect_command_failure backup-staging-double-slash \
    'backup staging root path is unsafe' \
    env "${BACKUP_ENVIRONMENT[@]}" \
      "BACKUP_STAGING_ROOT=$FIXTURE_ROOT//staging" \
      bash "$BACKUP_SET" --verify-upload
  [[ ! -e "$nested_real/staging" && ! -e "$FIXTURE_ROOT/staging-dotdot" ]] ||
    fail 'non-canonical backup staging path created a directory'

  rm -- "$nested_alias"
  rmdir -- "$nested_real" "$readiness_parent"
  [[ "$(stat -Lc '%u:%g:%a' -- "$FIXTURE_ROOT")" == 0:0:700 &&
     "$(stat -Lc '%u:%g:%a' -- "$STAGING_ROOT")" == 0:0:700 &&
     "$(stat -Lc '%u:%g:%a' -- "$PRUNE_STAGING_ROOT")" == 0:0:700 &&
     "$(stat -Lc '%u:%g:%a' -- "$PRUNE_TRANSACTION_ROOT")" == 0:0:700 ]] ||
    fail 'private-directory rejection tests did not restore their fixtures'
}

test_sample_policy() {
  local fixture total expected actual
  for fixture in \
    0:0 1:1 19:19 20:20 1999:20 2000:20 2001:21 20000:200 20001:200 50000:200
  do
    total="${fixture%%:*}"
    expected="${fixture##*:}"
    actual="$(bash "$VERIFY_ARTIFACT" sample-count "$total")"
    [[ "$actual" == "$expected" ]] || fail "COS sample policy failed for total=$total"
  done
}

test_successful_backup_set() {
  : >"$COMMAND_LOG"
  local run_log="$FIXTURE_ROOT/backup-success.log"
  local private_metadata_before private_metadata_after
  private_metadata_before="$(stat -Lc '%d:%i:%u:%g:%a' -- "$FIXTURE_ROOT")|$(stat -Lc '%d:%i:%u:%g:%a' -- "$STAGING_ROOT")"
  env "${BACKUP_ENVIRONMENT[@]}" \
    bash "$BACKUP_SET" --verify-upload >"$run_log" 2>&1 || {
      sed -n '1,200p' "$run_log" >&2
      fail 'stubbed encrypted backup set failed'
    }
  private_metadata_after="$(stat -Lc '%d:%i:%u:%g:%a' -- "$FIXTURE_ROOT")|$(stat -Lc '%d:%i:%u:%g:%a' -- "$STAGING_ROOT")"
  [[ "$private_metadata_after" == "$private_metadata_before" ]] ||
    fail 'successful backup changed operation-lock or staging-root metadata'

  local sets_root="$REMOTE_STORE/production/sets"
  [[ -d "$sets_root" ]] || fail 'successful backup did not create the sets namespace'
  local -a set_directories=()
  mapfile -t set_directories < <(find "$sets_root" -mindepth 1 -maxdepth 1 -type d -print)
  [[ "${#set_directories[@]}" -eq 1 ]] || fail 'successful backup did not publish exactly one set'
  local completed_set_directory="${set_directories[0]}"
  SUCCESS_SET_ID="$(basename "$completed_set_directory")"
  [[ "$SUCCESS_SET_ID" =~ ^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{12}$ ]] ||
    fail 'published set ID is invalid'
  [[ "$(find "$completed_set_directory" -mindepth 1 -maxdepth 1 -type f -printf '%f\n')" == VERIFIED ]] ||
    fail 'completed sets namespace contains non-atomic multi-object state'
  local completion_record="$completed_set_directory/VERIFIED"
  jq -e --arg setId "$SUCCESS_SET_ID" '
    .schemaVersion == 2 and .setId == $setId and
    .uploadPrefix == ("uploading/" + $setId) and
    (.manifestSha256 | test("^[0-9a-f]{64}$")) and .manifestByteSize > 0
  ' "$completion_record" >/dev/null || fail 'atomic completed-set record is invalid'
  SUCCESS_SET_DIRECTORY="$REMOTE_STORE/production/uploading/$SUCCESS_SET_ID"
  [[ -d "$SUCCESS_SET_DIRECTORY" ]] || fail 'completed set upload closure is missing'
  for filename in \
    database.dump.age local-media.tar.age media-manifest.json.age set-manifest.json VERIFIED
  do
    [[ -f "$SUCCESS_SET_DIRECTORY/$filename" ]] || fail "successful set omits $filename"
  done

  local set_manifest="$SUCCESS_SET_DIRECTORY/set-manifest.json"
  jq -S -c . "$set_manifest" >"$FIXTURE_ROOT/canonical-set.json"
  cmp -s "$set_manifest" "$FIXTURE_ROOT/canonical-set.json" ||
    fail 'set manifest is not canonical JSON'
  jq -e --arg setId "$SUCCESS_SET_ID" '
    .schemaVersion == 1 and .setId == $setId and
    .releaseId == "aaaaaaaaaaaa-bbbbbbbbbbbb" and
    (.snapshotId | test("^[A-Za-z0-9-]{8,128}$")) and
    (.backupStartedAt <= .backupFinishedAt) and
    .counts == {totalObjects:4,localObjects:2,cosObjects:2,distinctCosBlobs:2,cosVerificationSamples:2} and
    .retention == {daily:true,weekly:true,monthly:false} and
    .artifacts.databaseDump.file == "database.dump.age" and
    .artifacts.localMediaTar.file == "local-media.tar.age" and
    .artifacts.mediaManifest.file == "media-manifest.json.age" and
    (.blobs | length == 2) and
    ((.blobs | map(.path)) == (.blobs | map(.path) | sort | unique)) and
    all(.blobs[]; .path == ("blobs/" + .sha256))
  ' "$set_manifest" >/dev/null || fail 'self-contained set manifest has incorrect closure metadata'

  local marker manifest_sha
  manifest_sha="$(sha256sum "$set_manifest" | awk '{print $1}')"
  marker="$(tr -d '\r\n' <"$SUCCESS_SET_DIRECTORY/VERIFIED")"
  [[ "$marker" == "$manifest_sha" ]] || fail 'VERIFIED marker does not match set manifest'
  [[ "$(jq -r '.manifestSha256' "$completion_record")" == "$manifest_sha" ]] ||
    fail 'completed-set record does not bind the exact upload manifest'
  for artifact_name in databaseDump localMediaTar mediaManifest; do
    local filename expected_sha expected_size
    filename="$(jq -r --arg name "$artifact_name" '.artifacts[$name].file' "$set_manifest")"
    expected_sha="$(jq -r --arg name "$artifact_name" '.artifacts[$name].ciphertextSha256' "$set_manifest")"
    expected_size="$(jq -r --arg name "$artifact_name" '.artifacts[$name].byteSize' "$set_manifest")"
    [[ "$(sha256sum "$SUCCESS_SET_DIRECTORY/$filename" | awk '{print $1}')" == "$expected_sha" ]] ||
      fail 'set artifact checksum is not self-contained'
    [[ "$(stat -Lc '%s' "$SUCCESS_SET_DIRECTORY/$filename")" == "$expected_size" ]] ||
      fail 'set artifact byte count is not self-contained'
  done

  tail -n +2 "$SUCCESS_SET_DIRECTORY/media-manifest.json.age" >"$FIXTURE_ROOT/decrypted-media.json"
  local expected_dump_sha
  expected_dump_sha="$(printf '%s\n' 'pg-dump-custom-fixture' | sha256sum | awk '{print $1}')"
  jq -e --arg dumpSha "$expected_dump_sha" '
    .schemaVersion == 1 and .databaseDumpPlaintextSha256 == $dumpSha and
    (.rows | length == 4) and
    ([.rows[].provider] == ["LOCAL","TENCENT_COS","LOCAL","TENCENT_COS"]) and
    all(.rows[]; has("objectKey") and has("sha256") and has("byteSize"))
  ' "$FIXTURE_ROOT/decrypted-media.json" >/dev/null ||
    fail 'encrypted authoritative manifest does not preserve the mixed-provider snapshot closure'

  jq -S -c '
    [.rows[] | select(.provider == "LOCAL") |
      {path:.objectKey,size:.byteSize,sha256:.sha256}] | sort_by(.path) |
    {schemaVersion:1,entries:.}
  ' "$FIXTURE_ROOT/decrypted-media.json" >"$FIXTURE_ROOT/remote-local-allowlist.json"
  tail -n +2 "$SUCCESS_SET_DIRECTORY/local-media.tar.age" >"$FIXTURE_ROOT/decrypted-local.tar"
  python3 "$VALIDATOR" \
    --allowlist "$FIXTURE_ROOT/remote-local-allowlist.json" \
    "$FIXTURE_ROOT/decrypted-local.tar" >/dev/null 2>&1 ||
    fail 'encrypted Local tar does not contain the complete snapshot closure'

  while IFS=$'\t' read -r path sha size; do
    [[ -f "$REMOTE_STORE/production/$path" ]] || fail 'set references a missing immutable blob'
    [[ "$(sha256sum "$REMOTE_STORE/production/$path" | awk '{print $1}')" == "$sha" ]] ||
      fail 'immutable blob bytes do not match their content-addressed path'
    [[ "$(stat -Lc '%s' "$REMOTE_STORE/production/$path")" == "$size" ]] ||
      fail 'immutable blob size does not match its set manifest'
  done < <(jq -r '.blobs[] | [.path,.sha256,(.byteSize|tostring)] | @tsv' "$set_manifest")

  [[ "$(grep -Fxc 'keeper:start' "$COMMAND_LOG")" -eq 1 ]] ||
    fail 'backup did not open exactly one snapshot keeper connection'
  [[ "$(grep -Fxc 'keeper:unlock-shared' "$COMMAND_LOG")" -eq 1 ]] ||
    fail 'backup did not receive exactly one shared advisory unlock result'
  assert_before 'keeper:lock-shared' 'pg-dump:custom:snapshot-ok'
  assert_before 'keeper:lock-shared' 'media-reader:snapshot-ok'
  assert_before 'media-reader:snapshot-ok' 'keeper:snapshot-commit'
  assert_before 'pg-dump:custom:snapshot-ok' 'keeper:snapshot-commit'
  assert_before 'keeper:snapshot-commit' 'upload:production/uploading/'
  assert_before 'upload:production/uploading/' 'verifier-read:production/uploading/'
  assert_regex_before '^verifier-read:.*/VERIFIED$' '^keeper:unlock-shared$'
  assert_before 'keeper:unlock-shared' 'maintenance:DATABASE_BACKUP:SUCCEEDED:NONE'
  grep -F 'source-read:cos/original.bin' "$COMMAND_LOG" >/dev/null ||
    fail 'per-row COS original dispatch did not use the source-reader credential'
  grep -F 'source-read:cos/variant.bin' "$COMMAND_LOG" >/dev/null ||
    fail 'per-row COS variant dispatch did not use the source-reader credential'
  if grep -F 'pruner-' "$COMMAND_LOG" >/dev/null; then
    fail 'nightly backup used the pruner credential'
  fi
  if grep -F 'fixture-snapshot-0001' "$COMMAND_LOG" "$run_log" >/dev/null; then
    fail 'snapshot identifier entered command logs'
  fi
  [[ "$(find "$STAGING_ROOT" -mindepth 1 -print -quit)" == '' ]] ||
    fail 'successful backup left plaintext or ciphertext staging behind'
  assert_no_fixture_secret "$COMMAND_LOG"
  assert_no_fixture_secret "$run_log"
}

test_upload_failure_is_atomic() {
  : >"$COMMAND_LOG"
  : >"$MAIL_CAPTURE"
  local run_log="$FIXTURE_ROOT/backup-upload-failure.log"
  local markers_before markers_after
  markers_before="$(find "$REMOTE_STORE/production/sets" -name VERIFIED -type f | wc -l)"
  if env "${BACKUP_ENVIRONMENT[@]}" \
      FIXTURE_UPLOAD_FAIL_PATTERN=local-media.tar.age \
      bash "$BACKUP_SET" --verify-upload >"$run_log" 2>&1; then
    fail 'remote upload failure unexpectedly produced a successful backup'
  fi
  markers_after="$(find "$REMOTE_STORE/production/sets" -name VERIFIED -type f | wc -l)"
  [[ "$markers_after" == "$markers_before" ]] ||
    fail 'failed upload published a VERIFIED marker'
  local -a orphan_uploads=()
  mapfile -t orphan_uploads < <(
    find "$REMOTE_STORE/production/uploading" -mindepth 1 -maxdepth 1 -type d -print |
      while IFS= read -r candidate; do
        candidate_id="$(basename "$candidate")"
        [[ -e "$REMOTE_STORE/production/sets/$candidate_id/VERIFIED" ]] || printf '%s\n' "$candidate"
      done
  )
  [[ "${#orphan_uploads[@]}" -eq 1 ]] ||
    fail 'second-artifact failure did not leave exactly one isolated upload attempt'
  local orphan_upload_id
  orphan_upload_id="$(basename "${orphan_uploads[0]}")"
  [[ "$(find "${orphan_uploads[0]}" -mindepth 1 -maxdepth 1 -type f -printf '%f\n')" == database.dump.age ]] ||
    fail 'second-artifact failure escaped the isolated uploading namespace contract'
  grep -F 'maintenance:DATABASE_BACKUP:FAILED:UPLOAD_FAILED' "$COMMAND_LOG" >/dev/null ||
    fail 'upload failure did not record redacted database maintenance failure'
  grep -F 'maintenance:MEDIA_BACKUP:FAILED:UPLOAD_FAILED' "$COMMAND_LOG" >/dev/null ||
    fail 'upload failure did not record redacted media maintenance failure'
  grep -F 'smtp-send:redacted' "$COMMAND_LOG" >/dev/null ||
    fail 'upload failure did not invoke independent SMTP notification'
  grep -F 'Category: UPLOAD_FAILED' "$MAIL_CAPTURE" >/dev/null ||
    fail 'failure notification omitted its redacted category'
  if grep -F 'operator@example.test' "$COMMAND_LOG" "$run_log" >/dev/null; then
    fail 'failure email address entered command logs'
  fi
  if grep -F 'The independent portfolio backup failed' "$COMMAND_LOG" "$run_log" >/dev/null; then
    fail 'failure message body entered command logs'
  fi
  [[ "$(find "$STAGING_ROOT" -mindepth 1 -print -quit)" == '' ]] ||
    fail 'failed backup left plaintext staging behind'

  local retry_log="$FIXTURE_ROOT/backup-after-partial-upload.log"
  env "${BACKUP_ENVIRONMENT[@]}" \
    bash "$BACKUP_SET" --verify-upload >"$retry_log" 2>&1 || {
      sed -n '1,160p' "$retry_log" >&2
      fail 'a later backup could not publish after an isolated partial upload'
    }
  [[ "$(find "$REMOTE_STORE/production/sets" -name VERIFIED -type f | wc -l)" -eq $((markers_before + 1)) ]] ||
    fail 'later backup did not publish exactly one new atomic completion record'
  [[ ! -e "$REMOTE_STORE/production/sets/$orphan_upload_id/VERIFIED" ]] ||
    fail 'later backup accidentally completed an earlier partial upload attempt'

  : >"$COMMAND_LOG"
  local prune_log="$FIXTURE_ROOT/prune-after-partial-upload.log"
  env "${BACKUP_ENVIRONMENT[@]}" \
    bash "$PRUNE_REMOTE" --dry-run >"$prune_log" 2>&1 || {
      sed -n '1,200p' "$prune_log" >&2
      fail 'partial upload blocked a subsequent verified backup prune proposal'
    }
  local partial_report
  partial_report="$(find "$REMOTE_STORE/production/gc-reports" -type f -name '*.json' -print -quit)"
  jq -e --arg upload "$orphan_upload_id" '
    .schemaVersion == 3 and (.deleteUploads | index($upload) != null)
  ' "$partial_report" >/dev/null ||
    fail 'safe GC proposal did not isolate the stale incomplete upload attempt'
  grep -F "guard:review-candidate:upload:uploading/$orphan_upload_id" "$COMMAND_LOG" >/dev/null ||
    fail 'stale partial upload did not receive exact-version retention review'
  assert_no_fixture_secret "$COMMAND_LOG"
  assert_no_fixture_secret "$run_log"
}

test_credential_separation_fails_closed() {
  : >"$COMMAND_LOG"
  : >"$MAIL_CAPTURE"
  local run_log="$FIXTURE_ROOT/backup-duplicate-principal.log"
  if env "${BACKUP_ENVIRONMENT[@]}" \
      BACKUP_VERIFY_PRINCIPAL_ID=backup-uploader \
      bash "$BACKUP_SET" --verify-upload >"$run_log" 2>&1; then
    fail 'backup accepted duplicate uploader and verifier principals'
  fi
  grep -F 'rclone principal identities must all be distinct' "$run_log" >/dev/null ||
    fail 'duplicate principal failed for an unexpected reason'
  grep -F 'maintenance:DATABASE_BACKUP:FAILED:CONFIGURATION_FAILED' "$COMMAND_LOG" >/dev/null ||
    fail 'early configuration failure did not attempt redacted maintenance recording'
  grep -F 'smtp-send:redacted' "$COMMAND_LOG" >/dev/null ||
    fail 'early configuration failure did not invoke independent notification'
  if grep -E '^(keeper:|source-read:|upload:|verifier-read:)' "$COMMAND_LOG" >/dev/null; then
    fail 'early configuration failure reached snapshot or remote data commands'
  fi
}

test_local_volume_identity_fails_closed() {
  local wrong_root="$FIXTURE_ROOT/wrong-local-root"
  mkdir -m 0700 "$wrong_root"
  printf '%s\n' 'volume-contract-id' >"$wrong_root/.portfolio-volume-id"
  chmod 0600 "$wrong_root/.portfolio-volume-id"
  : >"$COMMAND_LOG"
  if env "${BACKUP_ENVIRONMENT[@]}" \
      PORTFOLIO_LOCAL_HOST_ROOT="$wrong_root" \
      bash "$BACKUP_SET" --verify-upload >"$FIXTURE_ROOT/wrong-volume-root.log" 2>&1; then
    fail 'backup accepted a configured host root different from the named-volume Mountpoint'
  fi
  grep -F 'Mountpoint does not match PORTFOLIO_LOCAL_HOST_ROOT' \
    "$FIXTURE_ROOT/wrong-volume-root.log" >/dev/null ||
    fail 'wrong Local host root failed for an unexpected reason'
  if grep -F 'keeper:start' "$COMMAND_LOG" >/dev/null; then
    fail 'wrong Local host root opened a snapshot and could create an empty tar'
  fi

  : >"$COMMAND_LOG"
  if env "${BACKUP_ENVIRONMENT[@]}" \
      PORTFOLIO_LOCAL_VOLUME_ID=marker-value-MUST-NOT-LEAK \
      bash "$BACKUP_SET" --verify-upload >"$FIXTURE_ROOT/wrong-volume-marker.log" 2>&1; then
    fail 'backup accepted a Local volume identity-marker mismatch'
  fi
  grep -F 'Local volume identity does not match the protected value' \
    "$FIXTURE_ROOT/wrong-volume-marker.log" >/dev/null ||
    fail 'wrong Local volume marker failed for an unexpected reason'
  if grep -F 'marker-value-MUST-NOT-LEAK' \
      "$COMMAND_LOG" "$FIXTURE_ROOT/wrong-volume-marker.log" >/dev/null; then
    fail 'protected Local volume identity entered command output'
  fi
  if grep -F 'keeper:start' "$COMMAND_LOG" >/dev/null; then
    fail 'wrong Local volume marker opened a snapshot and could create an empty tar'
  fi
}

test_verified_set_survives_maintenance_failure() {
  : >"$COMMAND_LOG"
  : >"$MAIL_CAPTURE"
  rm -f -- "$FIXTURE_ROOT/maintenance-failed-once"
  local markers_before markers_after run_log="$FIXTURE_ROOT/maintenance-failure.log"
  markers_before="$(find "$REMOTE_STORE/production/sets" -name VERIFIED -type f | wc -l)"
  if env "${BACKUP_ENVIRONMENT[@]}" \
      FIXTURE_MAINTENANCE_FAIL_ONCE=DATABASE_BACKUP:SUCCEEDED \
      bash "$BACKUP_SET" --verify-upload >"$run_log" 2>&1; then
    fail 'maintenance write failure unexpectedly returned backup success'
  fi
  markers_after="$(find "$REMOTE_STORE/production/sets" -name VERIFIED -type f | wc -l)"
  [[ "$markers_after" -eq $((markers_before + 1)) ]] ||
    fail 'maintenance failure removed or failed to preserve the already verified set'
  assert_regex_before '^verifier-read:.*/VERIFIED$' '^keeper:unlock-shared$'
  assert_before 'keeper:unlock-shared' 'maintenance-write-failed:DATABASE_BACKUP:SUCCEEDED'
  grep -F 'maintenance:DATABASE_BACKUP:FAILED:MAINTENANCE_WRITE_FAILED' "$COMMAND_LOG" >/dev/null ||
    fail 'maintenance failure was not retried with an explicit redacted category'
  grep -F 'Category: MAINTENANCE_WRITE_FAILED' "$MAIL_CAPTURE" >/dev/null ||
    fail 'maintenance failure notification did not explain verified-set bookkeeping semantics'
  [[ "$(find "$STAGING_ROOT" -mindepth 1 -print -quit)" == '' ]] ||
    fail 'maintenance failure left plaintext staging behind'
}

test_release_marker_dispatch() {
  local dispatch_root="$FIXTURE_ROOT/dispatch-root"
  local release_id=aaaaaaaaaaaa-bbbbbbbbbbbb
  local release_root="$dispatch_root/releases/$release_id"
  mkdir -p "$release_root/ops/deploy/backup"
  printf '%s\n' "$release_id" >"$dispatch_root/current-release"
  jq -S -c -n --arg releaseId "$release_id" '{releaseId:$releaseId}' >"$release_root/release.json"
  printf '%s\n' 'services: {}' >"$release_root/ops/deploy/docker-compose.prod.yml"
  cat >"$release_root/ops/deploy/backup/backup-set.sh" <<'STUB'
#!/usr/bin/env bash
printf '%s|%s|%s\n' "$PORTFOLIO_RELEASE_ID" "$BACKUP_COMPOSE_FILE" "$1"
STUB
cat >"$release_root/ops/deploy/backup/prune-remote.sh" <<'STUB'
#!/usr/bin/env bash
printf '%s|%s|%s\n' "$PORTFOLIO_RELEASE_ID" "$BACKUP_COMPOSE_FILE" "${1:-prune}"
STUB
  local output
  output="$(PORTFOLIO_ROOT="$dispatch_root" bash "$BACKUP_DISPATCH" backup)" ||
    fail 'stable backup dispatch could not resolve current-release'
  [[ "$output" == "$release_id|$release_root/ops/deploy/docker-compose.prod.yml|--verify-upload" ]] ||
    fail 'stable backup dispatch did not bind script and Compose to the same release marker'
  output="$(PORTFOLIO_ROOT="$dispatch_root" bash "$BACKUP_DISPATCH" prune-dry-run)" ||
    fail 'stable backup dispatch could not invoke the readiness dry-run mode'
  [[ "$output" == "$release_id|$release_root/ops/deploy/docker-compose.prod.yml|--dry-run" ]] ||
    fail 'readiness dispatch did not bind --dry-run to the current release pruner'
  jq -S -c -n --arg releaseId cccccccccccc-dddddddddddd '{releaseId:$releaseId}' \
    >"$release_root/release.json"
  if PORTFOLIO_ROOT="$dispatch_root" bash "$BACKUP_DISPATCH" backup \
      >"$FIXTURE_ROOT/dispatch-mismatch.log" 2>&1; then
    fail 'stable backup dispatch accepted release metadata drift'
  fi
  grep -F 'metadata does not match current-release' "$FIXTURE_ROOT/dispatch-mismatch.log" >/dev/null ||
    fail 'release marker drift failed for an unexpected reason'
}

seed_prune_fixture() {
  rm -rf -- "$REMOTE_STORE/production"
  mkdir -p "$REMOTE_STORE/production/sets" "$REMOTE_STORE/production/blobs"
  local shared_body='shared-retained-blob'
  PRUNE_SHARED_SHA="$(printf '%s' "$shared_body" | sha256sum | awk '{print $1}')"
  printf '%s' "$shared_body" >"$REMOTE_STORE/production/blobs/$PRUNE_SHARED_SHA"
  local index set_id finished weekly monthly blob_body blob_sha manifest
  for index in $(seq 1 28); do
    if ((index <= 20)); then
      finished="$(/usr/bin/date -u -d "2026-06-01 +$index days" '+%Y-%m-%dT%H:%M:%SZ')"
    else
      # Eight recent daily sets make index 21 fall outside the seven-daily
      # union while remaining inside the 14-day safety window.
      finished="$(/usr/bin/date -u -d "2026-12-01 +$index days" '+%Y-%m-%dT%H:%M:%SZ')"
    fi
    set_id="$(/usr/bin/date -u -d "$finished" '+%Y%m%dT%H%M%SZ')-$(printf '%012x' "$index")"
    weekly=false
    monthly=false
    if ((index <= 20)); then
      ((index % 2 == 0)) && weekly=true
      ((index % 3 == 0)) && monthly=true
    fi
    blob_body="blob-$index"
    blob_sha="$(printf '%s' "$blob_body" | sha256sum | awk '{print $1}')"
    printf '%s' "$blob_body" >"$REMOTE_STORE/production/blobs/$blob_sha"
    if ((index == 21)); then
      PRUNE_GRACE_SET="$set_id"
      PRUNE_GRACE_BLOB="blobs/$blob_sha"
    fi
    mkdir "$REMOTE_STORE/production/sets/$set_id"
    manifest="$REMOTE_STORE/production/sets/$set_id/set-manifest.json"
    jq -S -c -n \
      --arg setId "$set_id" \
      --arg finished "$finished" \
      --arg blobSha "$blob_sha" \
      --arg sharedSha "$PRUNE_SHARED_SHA" \
      --argjson blobSize "${#blob_body}" \
      --argjson sharedSize "${#shared_body}" \
      --argjson weekly "$weekly" \
      --argjson monthly "$monthly" \
      '{schemaVersion:1,setId:$setId,backupFinishedAt:$finished,
        retention:{daily:true,weekly:$weekly,monthly:$monthly},
        blobs:[
          {path:("blobs/"+$blobSha),sha256:$blobSha,byteSize:$blobSize},
          {path:("blobs/"+$sharedSha),sha256:$sharedSha,byteSize:$sharedSize}
        ] | sort_by(.path)}' >"$manifest"
    sha256sum "$manifest" | awk '{print $1}' >"$REMOTE_STORE/production/sets/$set_id/VERIFIED"
  done
}

clear_prune_transaction_root() {
  local resolved
  resolved="$(realpath -e -- "$PRUNE_TRANSACTION_ROOT")" ||
    fail 'prune transaction test root cannot be resolved'
  [[ "$resolved" == "$FIXTURE_ROOT/prune-transactions" &&
     ! -L "$PRUNE_TRANSACTION_ROOT" && -d "$PRUNE_TRANSACTION_ROOT" ]] ||
    fail 'refusing to clear a prune transaction root outside the fixture'
  find "$resolved" -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +
}

test_reachability_pruning() {
  seed_prune_fixture
  : >"$COMMAND_LOG"
  local run_log="$FIXTURE_ROOT/prune.log"
  local private_metadata_before private_metadata_after
  private_metadata_before="$(stat -Lc '%d:%i:%u:%g:%a' -- "$FIXTURE_ROOT")|$(stat -Lc '%d:%i:%u:%g:%a' -- "$PRUNE_STAGING_ROOT")|$(stat -Lc '%d:%i:%u:%g:%a' -- "$PRUNE_TRANSACTION_ROOT")"
  env "${BACKUP_ENVIRONMENT[@]}" \
    bash "$PRUNE_REMOTE" --dry-run >"$run_log" 2>&1 || {
      sed -n '1,200p' "$run_log" >&2
      fail 'stubbed 7/4/6 pruning dry-run failed'
    }
  private_metadata_after="$(stat -Lc '%d:%i:%u:%g:%a' -- "$FIXTURE_ROOT")|$(stat -Lc '%d:%i:%u:%g:%a' -- "$PRUNE_STAGING_ROOT")|$(stat -Lc '%d:%i:%u:%g:%a' -- "$PRUNE_TRANSACTION_ROOT")"
  [[ "$private_metadata_after" == "$private_metadata_before" ]] ||
    fail 'successful prune dry-run changed operation, staging, or transaction root metadata'
  local -a reports=()
  mapfile -t reports < <(find "$REMOTE_STORE/production/gc-reports" -type f -name '*.json' -print)
  [[ "${#reports[@]}" -eq 1 ]] || fail 'pruner did not persist exactly one immutable GC proposal'
  local report="${reports[0]}"
  jq -e '
    .schemaVersion == 3 and
    .prefix == "production" and
    .destination == {accountId:"backup-account",bucket:"backup-bucket",region:"ap-guangzhou",principalId:"backup-pruner"} and
    .policy == {daily:7,weekly:4,monthly:6,safetyDays:14} and
    (.currentSet == .observedSets[-1].setId) and
    ((.protectedSets | sort) == ((.observedSets | map(.setId)) - .deleteSets | sort)) and
    (.retainedSets - .protectedSets | length == 0) and
    ((.reachableBlobs | sort | unique) == .reachableBlobs) and
    (.retainedSets | length == 16) and
    (.protectedSets | length == 17) and
    (.deleteSets | length == 11) and
    all(.deleteBlobs[]; test("^blobs/[0-9a-f]{64}$")) and
    (.deleteUploads | type == "array")
  ' "$report" >/dev/null || fail 'retention proposal does not implement 7/4/6 distinct-set union'
  jq -e --arg setId "$PRUNE_GRACE_SET" --arg blob "$PRUNE_GRACE_BLOB" '
    (.retainedSets | index($setId) == null) and
    (.protectedSets | index($setId) != null) and
    (.deleteSets | index($setId) == null) and
    (.reachableBlobs | index($blob) != null) and
    (.deleteBlobs | index($blob) == null)
  ' "$report" >/dev/null ||
    fail 'a non-retained set inside the safety window was not protected with its blob closure'
  if jq -e --arg shared "blobs/$PRUNE_SHARED_SHA" '.deleteBlobs | index($shared) != null' "$report" >/dev/null; then
    fail 'reachability pruning selected a blob referenced by every retained set'
  fi
  local retained_set retained_manifest retained_blob
  while IFS= read -r retained_set; do
    retained_manifest="$REMOTE_STORE/production/sets/$retained_set/set-manifest.json"
    while IFS= read -r retained_blob; do
      if jq -e --arg blob "$retained_blob" '.deleteBlobs | index($blob) != null' "$report" >/dev/null; then
        fail 'reachability pruning selected a retained-set blob'
      fi
    done < <(jq -r '.blobs[].path' "$retained_manifest")
  done < <(jq -r '.retainedSets[]' "$report")
  if grep -F 'guard:delete-reviewed' "$COMMAND_LOG" >/dev/null; then
    fail 'prune dry-run invoked a deletion adapter'
  fi
  grep -F 'guard:verify-destination:destination:prefix' "$COMMAND_LOG" >/dev/null ||
    fail 'pruner did not verify destination/versioning/role boundary'
  grep -F 'guard:review-candidate:' "$COMMAND_LOG" >/dev/null ||
    fail 'pruner did not review version-scoped deletion candidates'
  if grep -E '^(source-read|upload:|verifier-read|smtp-send|maintenance:)' "$COMMAND_LOG" >/dev/null; then
    fail 'pruner used a forbidden source/uploader/verifier/database/mail capability'
  fi
  if grep -Eq '(^|[[:space:]])(delete|deletefile|purge)([[:space:]]|$)' "$PRUNE_REMOTE"; then
    fail 'pruner contains an unguarded generic rclone deletion command'
  fi

  rm -rf -- "$REMOTE_STORE/production/sets"
  mkdir "$REMOTE_STORE/production/sets"
  if env "${BACKUP_ENVIRONMENT[@]}" \
      bash "$PRUNE_REMOTE" --dry-run >"$FIXTURE_ROOT/prune-empty.log" 2>&1; then
    fail 'pruner accepted an empty retained-set collection without disaster confirmation'
  fi
  grep -F 'disaster-reset confirmation is required' "$FIXTURE_ROOT/prune-empty.log" >/dev/null ||
    fail 'empty retained-set pruning failed for an unexpected reason'
}

test_prune_transaction_power_loss_recovery() {
  local run_log restart_log first_resume first_review active_directory plan plan_backup

  seed_prune_fixture
  clear_prune_transaction_root
  ln -s -- "$FIXTURE_ROOT" "$PRUNE_TRANSACTION_ROOT/unsafe-link"
  : >"$COMMAND_LOG"
  if env "${BACKUP_ENVIRONMENT[@]}" bash "$PRUNE_REMOTE" --dry-run \
      >"$FIXTURE_ROOT/prune-unsafe-root-entry.log" 2>&1; then
    fail 'symlinked persistent transaction root entry unexpectedly passed'
  fi
  rm -f -- "$PRUNE_TRANSACTION_ROOT/unsafe-link"
  grep -F 'transaction root contains an unsafe entry' \
    "$FIXTURE_ROOT/prune-unsafe-root-entry.log" >/dev/null ||
    fail 'symlinked persistent transaction entry failed for an unexpected reason'
  if grep -F 'guard:delete-reviewed:' "$COMMAND_LOG" >/dev/null; then
    fail 'symlinked persistent transaction root entry reached provider deletion'
  fi

  rm -f -- "$FIXTURE_ROOT/prepare-kill-fired" "$FIXTURE_ROOT/transaction-root-sync-count"
  run_log="$FIXTURE_ROOT/prune-kill-before-activation.log"
  if env "${BACKUP_ENVIRONMENT[@]}" FIXTURE_GUARD_KILL_ON_PREPARE=1 \
      bash "$PRUNE_REMOTE" >"$run_log" 2>&1; then
    fail 'SIGKILL before transaction activation unexpectedly completed pruning'
  fi
  [[ "$(find "$PRUNE_TRANSACTION_ROOT" -mindepth 1 -maxdepth 1 -type d -name '.new.*' | wc -l)" -eq 1 ]] ||
    fail 'pre-activation SIGKILL did not leave exactly one non-destructive .new build'
  env "${BACKUP_ENVIRONMENT[@]}" bash "$PRUNE_REMOTE" --dry-run \
    >"$FIXTURE_ROOT/prune-recover-new.log" 2>&1 || {
    sed -n '1,160p' "$FIXTURE_ROOT/prune-recover-new.log" >&2
    fail 'restart could not safely discard a never-activated transaction build'
  }
  [[ "$(find "$PRUNE_TRANSACTION_ROOT" -mindepth 1 -print -quit)" == '' ]] ||
    fail 'restart left a non-destructive .new transaction behind'

  seed_prune_fixture
  clear_prune_transaction_root
  rm -f -- "$FIXTURE_ROOT/transaction-root-sync-count"
  run_log="$FIXTURE_ROOT/prune-kill-after-activation.log"
  if env "${BACKUP_ENVIRONMENT[@]}" FIXTURE_SYNC_KILL_AT=1 \
      bash "$PRUNE_REMOTE" >"$run_log" 2>&1; then
    fail 'SIGKILL immediately after durable activation unexpectedly completed pruning'
  fi
  [[ "$(find "$PRUNE_TRANSACTION_ROOT" -mindepth 1 -maxdepth 1 -type d \
      -regextype posix-extended -regex '.*/[0-9a-f]{64}-[0-9a-f]{64}-[0-9a-f]{64}' | wc -l)" -eq 1 ]] ||
    fail 'post-activation SIGKILL lost the active exact-version transaction'
  active_directory="$(find "$PRUNE_TRANSACTION_ROOT" -mindepth 1 -maxdepth 1 -type d \
    -regextype posix-extended -regex '.*/[0-9a-f]{64}-[0-9a-f]{64}-[0-9a-f]{64}' -print -quit)"
  plan="$active_directory/transaction-plan.json"

  ln -s -- "$FIXTURE_ROOT" "$active_directory/unexpected-link"
  : >"$COMMAND_LOG"
  if env "${BACKUP_ENVIRONMENT[@]}" bash "$PRUNE_REMOTE" \
      >"$FIXTURE_ROOT/prune-active-symlink.log" 2>&1; then
    fail 'symlink inside an active transaction unexpectedly passed'
  fi
  rm -f -- "$active_directory/unexpected-link"
  grep -F 'contains an unexpected entry' "$FIXTURE_ROOT/prune-active-symlink.log" >/dev/null ||
    fail 'active transaction symlink failed for an unexpected reason'
  if grep -F 'guard:delete-reviewed:' "$COMMAND_LOG" >/dev/null; then
    fail 'active transaction symlink reached provider deletion'
  fi

  ln -- "$plan" "$FIXTURE_ROOT/transaction-plan.hardlink"
  : >"$COMMAND_LOG"
  if env "${BACKUP_ENVIRONMENT[@]}" bash "$PRUNE_REMOTE" \
      >"$FIXTURE_ROOT/prune-active-hardlink.log" 2>&1; then
    fail 'hard-linked active transaction plan unexpectedly passed'
  fi
  rm -f -- "$FIXTURE_ROOT/transaction-plan.hardlink"
  grep -F 'must be a singly linked root-owned file' "$FIXTURE_ROOT/prune-active-hardlink.log" >/dev/null ||
    fail 'hard-linked active transaction plan failed for an unexpected reason'
  if grep -F 'guard:delete-reviewed:' "$COMMAND_LOG" >/dev/null; then
    fail 'hard-linked active transaction plan reached provider deletion'
  fi

  plan_backup="$FIXTURE_ROOT/transaction-plan.backup"
  cp -- "$plan" "$plan_backup"
  chmod 0600 "$plan_backup"
  : >"$plan"
  : >"$COMMAND_LOG"
  if env "${BACKUP_ENVIRONMENT[@]}" bash "$PRUNE_REMOTE" \
      >"$FIXTURE_ROOT/prune-active-truncated.log" 2>&1; then
    fail 'truncated active transaction plan unexpectedly passed'
  fi
  grep -F 'transaction plan is invalid' "$FIXTURE_ROOT/prune-active-truncated.log" >/dev/null ||
    fail 'truncated active transaction plan failed for an unexpected reason'
  if grep -F 'guard:delete-reviewed:' "$COMMAND_LOG" >/dev/null; then
    fail 'truncated active transaction plan reached provider deletion'
  fi
  cp -- "$plan_backup" "$plan"
  chmod 0600 "$plan"

  : >"$COMMAND_LOG"
  restart_log="$FIXTURE_ROOT/prune-resume-active.log"
  env "${BACKUP_ENVIRONMENT[@]}" bash "$PRUNE_REMOTE" >"$restart_log" 2>&1 || {
    sed -n '1,160p' "$restart_log" >&2
    fail 'next process could not resume the active exact-version transaction'
  }
  first_resume="$(event_line 'guard:delete-reviewed:')"
  first_review="$(event_line 'guard:review-candidate:')"
  [[ -n "$first_resume" && -n "$first_review" && "$first_resume" -lt "$first_review" ]] ||
    fail 'next process issued a fresh proposal before resuming its pending transaction'
  [[ "$(find "$PRUNE_TRANSACTION_ROOT" -mindepth 1 -print -quit)" == '' ]] ||
    fail 'completed resumed transaction was not locally committed and cleaned'

  seed_prune_fixture
  clear_prune_transaction_root
  rm -f -- "$FIXTURE_ROOT/transaction-root-sync-count"
  run_log="$FIXTURE_ROOT/prune-kill-after-done-rename.log"
  if env "${BACKUP_ENVIRONMENT[@]}" FIXTURE_SYNC_KILL_AT=2 \
      bash "$PRUNE_REMOTE" >"$run_log" 2>&1; then
    fail 'SIGKILL during committed local cleanup unexpectedly completed pruning'
  fi
  [[ "$(find "$PRUNE_TRANSACTION_ROOT" -mindepth 1 -maxdepth 1 -type d -name '.done.*' | wc -l)" -eq 1 ]] ||
    fail 'cleanup-window SIGKILL did not leave a committed .done transaction'
  : >"$COMMAND_LOG"
  env "${BACKUP_ENVIRONMENT[@]}" bash "$PRUNE_REMOTE" --dry-run \
    >"$FIXTURE_ROOT/prune-clean-done.log" 2>&1 ||
    fail 'restart could not clean a committed .done transaction'
  [[ "$(find "$PRUNE_TRANSACTION_ROOT" -mindepth 1 -print -quit)" == '' ]] ||
    fail 'committed .done transaction was not safely garbage-collected'
  if grep -F 'guard:delete-reviewed:' "$COMMAND_LOG" >/dev/null; then
    fail 'restart replayed provider deletion for an already committed .done transaction'
  fi
}

test_initial_prune_dry_run_gate() {
  local first_log="$FIXTURE_ROOT/prune-initial-gate.log"
  local second_log="$FIXTURE_ROOT/prune-after-initial-gate.log"
  local saved_marker="$FIXTURE_ROOT/prune-readiness.saved"
  local failure_log
  seed_prune_fixture
  rm -f -- "$PRUNE_READINESS_FILE"
  : >"$COMMAND_LOG"
  env "${BACKUP_ENVIRONMENT[@]}" bash "$PRUNE_REMOTE" >"$first_log" 2>&1 || {
    sed -n '1,160p' "$first_log" >&2
    fail 'first ordinary prune invocation did not complete as a dry-run'
  }
  [[ -f "$PRUNE_READINESS_FILE" && ! -L "$PRUNE_READINESS_FILE" &&
     "$(stat -Lc '%u:%h:%a' -- "$PRUNE_READINESS_FILE")" == "$(id -u):1:600" ]] ||
    fail 'initial prune dry-run did not commit private single-link readiness evidence'
  jq -e '
    keys == ["binding","completedAt","schemaVersion"] and .schemaVersion == 1 and
    (.completedAt | test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")) and
    (.binding | keys == ["destination","guardSha256","policy","requirementsSha256","wrapperSha256"]) and
    .binding.policy == {daily:7,monthly:6,safetyDays:14,weekly:4}
  ' "$PRUNE_READINESS_FILE" >/dev/null ||
    fail 'initial prune readiness evidence has an invalid exact binding shape'
  grep -F 'initial prune execution completed as a non-destructive dry-run' "$first_log" >/dev/null ||
    fail 'first ordinary prune did not report its non-destructive bootstrap state'
  if grep -F 'guard:delete-reviewed:' "$COMMAND_LOG" >/dev/null; then
    fail 'first ordinary prune invocation reached a provider deletion'
  fi

  cp -- "$PRUNE_READINESS_FILE" "$saved_marker"
  chmod 0600 "$saved_marker"
  ln -- "$PRUNE_READINESS_FILE" "$FIXTURE_ROOT/prune-readiness.hardlink"
  : >"$COMMAND_LOG"
  failure_log="$FIXTURE_ROOT/prune-readiness-hardlink.log"
  if env "${BACKUP_ENVIRONMENT[@]}" bash "$PRUNE_REMOTE" --dry-run >"$failure_log" 2>&1; then
    fail 'hard-linked readiness marker unexpectedly authorized pruning'
  fi
  rm -f -- "$FIXTURE_ROOT/prune-readiness.hardlink"
  grep -F 'must be a singly linked root-owned file' "$failure_log" >/dev/null ||
    fail 'hard-linked readiness marker failed for an unexpected reason'
  if grep -F 'guard:delete-reviewed:' "$COMMAND_LOG" >/dev/null; then
    fail 'hard-linked readiness marker reached provider deletion'
  fi

  printf '{\n' >"$PRUNE_READINESS_FILE"
  failure_log="$FIXTURE_ROOT/prune-readiness-truncated.log"
  if env "${BACKUP_ENVIRONMENT[@]}" bash "$PRUNE_REMOTE" --dry-run >"$failure_log" 2>&1; then
    fail 'truncated readiness marker unexpectedly authorized pruning'
  fi
  grep -F 'readiness evidence is invalid or stale' "$failure_log" >/dev/null ||
    fail 'truncated readiness marker failed for an unexpected reason'
  cp -- "$saved_marker" "$PRUNE_READINESS_FILE"
  chmod 0600 "$PRUNE_READINESS_FILE"

  rm -f -- "$PRUNE_READINESS_FILE"
  ln -s -- "$saved_marker" "$PRUNE_READINESS_FILE"
  failure_log="$FIXTURE_ROOT/prune-readiness-symlink.log"
  if env "${BACKUP_ENVIRONMENT[@]}" bash "$PRUNE_REMOTE" --dry-run >"$failure_log" 2>&1; then
    fail 'symlinked readiness marker unexpectedly authorized pruning'
  fi
  grep -F 'readiness path is unsafe' "$failure_log" >/dev/null ||
    fail 'symlinked readiness marker failed for an unexpected reason'
  rm -f -- "$PRUNE_READINESS_FILE"
  cp -- "$saved_marker" "$PRUNE_READINESS_FILE"
  chmod 0600 "$PRUNE_READINESS_FILE"

  : >"$COMMAND_LOG"
  env "${BACKUP_ENVIRONMENT[@]}" bash "$PRUNE_REMOTE" >"$second_log" 2>&1 || {
    sed -n '1,160p' "$second_log" >&2
    fail 'a later prune could not pass the durable initial dry-run gate'
  }
  grep -F 'guard:delete-reviewed:' "$COMMAND_LOG" >/dev/null ||
    fail 'a later prune remained permanently dry-run after readiness was committed'
}

test_prune_guard_is_mandatory() {
  : >"$COMMAND_LOG"
  if env "${BACKUP_ENVIRONMENT[@]}" \
      BACKUP_PRUNE_GUARD_COMMAND=/missing/portfolio-backup-prune-guard \
      bash "$PRUNE_REMOTE" --dry-run >"$FIXTURE_ROOT/prune-missing-guard.log" 2>&1; then
    fail 'pruner ran without its provider-specific version/Object-Lock guard'
  fi
  grep -F 'BACKUP_PRUNE_GUARD_COMMAND must name an executable absolute regular file' \
    "$FIXTURE_ROOT/prune-missing-guard.log" >/dev/null ||
    fail 'missing prune guard failed for an unexpected reason'
  [[ ! -s "$COMMAND_LOG" ]] || fail 'missing prune guard reached a remote or deletion command'
  if bash "$PRUNE_GUARD_EXAMPLE" verify-destination >"$FIXTURE_ROOT/guard-example.log" 2>&1; then
    fail 'repository COS prune guard authorized an incomplete invocation'
  fi
  [[ -s "$FIXTURE_ROOT/guard-example.log" ]] ||
    fail 'repository COS prune guard did not explain its rejected invocation'
  if grep -Fx 'SAFE' "$FIXTURE_ROOT/guard-example.log" >/dev/null; then
    fail 'repository COS prune guard emitted authorization on failure'
  fi
}

test_systemd_contract() {
  local backup_calendar prune_calendar calendar label
  backup_calendar="$(sed -n 's/^OnCalendar=//p' "$BACKUP_TIMER")"
  prune_calendar="$(sed -n 's/^OnCalendar=//p' "$PRUNE_TIMER")"
  [[ "$backup_calendar" == '*-*-* 18:30:00 UTC' ]] ||
    fail 'nightly timer is not the Ubuntu 22.04-compatible 02:30 Asia/Hong_Kong UTC equivalent'
  [[ "$prune_calendar" == '*-*-* 22:30:00 UTC' ]] ||
    fail 'prune timer is not the Ubuntu 22.04-compatible 06:30 Asia/Hong_Kong UTC equivalent'
  for label in backup prune; do
    if [[ "$label" == backup ]]; then calendar="$backup_calendar"; else calendar="$prune_calendar"; fi
    systemd-analyze calendar "$calendar" >"$FIXTURE_ROOT/systemd-calendar-$label.log" 2>&1 || {
      sed -n '1,80p' "$FIXTURE_ROOT/systemd-calendar-$label.log" >&2
      fail "$label timer OnCalendar is not parseable by Ubuntu 22.04 systemd"
    }
    grep -F 'Normalized form:' "$FIXTURE_ROOT/systemd-calendar-$label.log" >/dev/null ||
      fail "$label timer calendar parser returned no normalized schedule"
  done
  grep -F 'RandomizedDelaySec=15m' "$BACKUP_TIMER" >/dev/null ||
    fail 'nightly timer lacks its bounded randomized delay'
  grep -F 'Persistent=true' "$BACKUP_TIMER" >/dev/null ||
    fail 'nightly timer is not persistent'
  for setting in \
    'NoNewPrivileges=true' 'PrivateTmp=true' 'ProtectSystem=strict' \
    'ProtectHome=true' 'CapabilityBoundingSet=' 'TimeoutStartSec=3h'; do
    grep -F "$setting" "$BACKUP_SERVICE" >/dev/null ||
      fail "backup service lacks hardening setting $setting"
  done
  grep -F 'InaccessiblePaths=/etc/portfolio/rclone-backup-pruner.conf' "$BACKUP_SERVICE" >/dev/null ||
    fail 'nightly service can access the pruner credential'
  grep -F 'ExecStart=/usr/bin/bash /usr/local/libexec/portfolio/backup-dispatch.sh backup' \
    "$BACKUP_SERVICE" >/dev/null || fail 'nightly service does not use the stable current-release dispatcher'
  grep -Fx 'UnsetEnvironment=BACKUP_AGE_IDENTITY AGE_SECRET_KEY AGE_SECRET_KEY_FILE COS_SECRET_ID COS_SECRET_KEY SMTP_PASSWORD' \
    "$BACKUP_SERVICE" >/dev/null || fail 'nightly service does not scrub deployment runtime secrets'
  grep -F 'ExecStart=/usr/bin/bash /usr/local/libexec/portfolio/backup-dispatch.sh prune' \
    "$PRUNE_SERVICE" >/dev/null || fail 'pruner service does not use the stable current-release dispatcher'
  grep -Fx 'ExecStart=/usr/bin/bash /usr/local/libexec/portfolio/backup-dispatch.sh prune-dry-run' \
    "$PRUNE_READINESS_SERVICE" >/dev/null ||
    fail 'prune readiness service does not invoke the explicit non-destructive dispatcher mode'
  local setting_prefix
  for setting_prefix in \
    'User=' 'Group=' 'EnvironmentFile=' 'UnsetEnvironment=' 'TimeoutStartSec=' \
    'UMask=' 'Nice=' 'NoNewPrivileges=' 'PrivateTmp=' 'PrivateDevices=' \
    'ProtectSystem=' 'ProtectHome=' 'ProtectClock=' 'ProtectKernelTunables=' \
    'ProtectKernelModules=' 'ProtectKernelLogs=' 'ProtectControlGroups=' \
    'ProtectHostname=' 'ProtectProc=' 'ProcSubset=' 'RestrictSUIDSGID=' \
    'RestrictRealtime=' 'RestrictNamespaces=' 'LockPersonality=' \
    'MemoryDenyWriteExecute=' 'SystemCallArchitectures=' 'CapabilityBoundingSet=' \
    'AmbientCapabilities=' 'RestrictAddressFamilies=' 'ReadWritePaths=' \
    'ReadOnlyPaths=' 'InaccessiblePaths='
  do
    cmp -s \
      <(grep "^$setting_prefix" "$PRUNE_SERVICE") \
      <(grep "^$setting_prefix" "$PRUNE_READINESS_SERVICE") ||
      fail "prune readiness sandbox differs from prune service for $setting_prefix"
  done
  for unit in "$BACKUP_SERVICE" "$PRUNE_SERVICE"; do
    grep -F 'ReadOnlyPaths=/usr/local/libexec/portfolio/backup-dispatch.sh' "$unit" >/dev/null ||
      fail 'backup unit does not pin the stable dispatcher read-only'
  done
  grep -F 'ReadOnlyPaths=/var/lib/docker/volumes/portfolio-local-media/_data' \
    "$BACKUP_SERVICE" >/dev/null || fail 'nightly service cannot read the fixed external volume Mountpoint'
  grep -F 'InaccessiblePaths=/var/run/docker.sock' "$PRUNE_SERVICE" >/dev/null ||
    fail 'pruner service can access Docker/PostgreSQL'
  grep -F 'InaccessiblePaths=/etc/portfolio/rclone-media-reader.conf' "$PRUNE_SERVICE" >/dev/null ||
    fail 'pruner service can access the production-media reader credential'
  grep -F '# 22:30 UTC is 06:30 Asia/Hong_Kong on the following calendar day.' \
    "$PRUNE_TIMER" >/dev/null || fail 'prune timer does not document its Hong Kong civil-time mapping'
  if grep -F '/opt/portfolio/current/' "$BACKUP_SERVICE" "$PRUNE_SERVICE" \
      "$REPOSITORY_ROOT/deploy/backup/postgres-client.sh" >/dev/null; then
    fail 'backup units or PostgreSQL client depend on an undeclared current symlink'
  fi
  grep -F 'BACKUP_COS_PRUNE_CREDENTIAL_FILE=/etc/portfolio/cos-backup-pruner-api.json' \
    "$REPOSITORY_ROOT/deploy/backup/backup.env.example" >/dev/null ||
    fail 'protected backup configuration does not pin the COS pruner API credential file'
}

main() {
  for command_name in \
    awk base64 chgrp chmod chown cmp cp find grep id jq ln mkdir python3 readlink realpath rmdir sed seq sha256sum sort stat systemd-analyze tail
  do
    command -v "$command_name" >/dev/null 2>&1 || fail "$command_name is required"
  done
  for required_file in \
    "$VALIDATOR" "$BACKUP_SET" "$BACKUP_DISPATCH" "$BACKUP_MEDIA" "$VERIFY_ARTIFACT" \
    "$PRUNE_REMOTE" "$PRUNE_GUARD_EXAMPLE" "$NOTIFY_FAILURE" "$BACKUP_SERVICE" "$BACKUP_TIMER" \
    "$PRUNE_SERVICE" "$PRUNE_READINESS_SERVICE" "$PRUNE_TIMER"
  do
    [[ -f "$required_file" ]] || fail 'a required encrypted-backup implementation file is missing'
  done
  WORK_DIRECTORY="$(mktemp -d)"
  test_media_tar_validation
  setup_backup_fixtures
  test_existing_private_directories_are_never_repaired
  test_sample_policy
  test_successful_backup_set
  test_upload_failure_is_atomic
  test_credential_separation_fails_closed
  test_local_volume_identity_fails_closed
  test_verified_set_survives_maintenance_failure
  test_release_marker_dispatch
  test_prune_guard_is_mandatory
  test_initial_prune_dry_run_gate
  test_prune_transaction_power_loss_recovery
  test_reachability_pruning
  test_systemd_contract
  printf '%s\n' 'PASS: encrypted database/media backup, verification, and 7/4/6 pruning contracts'
}

main "$@"
