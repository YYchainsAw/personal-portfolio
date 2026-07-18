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
    printf '%s\n' 'pruner-list:sets' >>"$FIXTURE_COMMAND_LOG"
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
    printf '%s\n' 'pruner-list:blobs' >>"$FIXTURE_COMMAND_LOG"
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
kind='destination' relative='prefix'
while (($#)); do
  case "$1" in
    --kind) kind="$2"; shift 2 ;;
    --relative-path) relative="$2"; shift 2 ;;
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
  verify-destination|review-candidate) printf '%s\n' SAFE ;;
  delete-reviewed) printf '%s\n' DELETED ;;
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
  ZONEINFO_ROOT="$FIXTURE_ROOT/zoneinfo"
  MEDIA_EXPORT_FIXTURE="$FIXTURE_ROOT/media-export.json"
  COMMAND_LOG="$FIXTURE_ROOT/commands.log"
  MAIL_CAPTURE="$FIXTURE_ROOT/mail.txt"
  mkdir -m 0700 \
    "$FIXTURE_ROOT" "$BIN_DIRECTORY" "$CONFIG_DIRECTORY" "$REMOTE_STORE" \
    "$SOURCE_STORE" "$LOCAL_MEDIA_ROOT" "$STAGING_ROOT" "$PRUNE_STAGING_ROOT" \
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
  env "${BACKUP_ENVIRONMENT[@]}" \
    bash "$BACKUP_SET" --verify-upload >"$run_log" 2>&1 || {
      sed -n '1,200p' "$run_log" >&2
      fail 'stubbed encrypted backup set failed'
    }

  local sets_root="$REMOTE_STORE/production/sets"
  [[ -d "$sets_root" ]] || fail 'successful backup did not create the sets namespace'
  local -a set_directories=()
  mapfile -t set_directories < <(find "$sets_root" -mindepth 1 -maxdepth 1 -type d -print)
  [[ "${#set_directories[@]}" -eq 1 ]] || fail 'successful backup did not publish exactly one set'
  SUCCESS_SET_DIRECTORY="${set_directories[0]}"
  SUCCESS_SET_ID="$(basename "$SUCCESS_SET_DIRECTORY")"
  [[ "$SUCCESS_SET_ID" =~ ^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{12}$ ]] ||
    fail 'published set ID is invalid'
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
  assert_before 'keeper:snapshot-commit' 'upload:production/sets/'
  assert_before 'upload:production/sets/' 'verifier-read:production/sets/'
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
      FIXTURE_UPLOAD_FAIL_PATTERN=database.dump.age \
      bash "$BACKUP_SET" --verify-upload >"$run_log" 2>&1; then
    fail 'remote upload failure unexpectedly produced a successful backup'
  fi
  markers_after="$(find "$REMOTE_STORE/production/sets" -name VERIFIED -type f | wc -l)"
  [[ "$markers_after" == "$markers_before" ]] ||
    fail 'failed upload published a VERIFIED marker'
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
printf '%s|%s|prune\n' "$PORTFOLIO_RELEASE_ID" "$BACKUP_COMPOSE_FILE"
STUB
  local output
  output="$(PORTFOLIO_ROOT="$dispatch_root" bash "$BACKUP_DISPATCH" backup)" ||
    fail 'stable backup dispatch could not resolve current-release'
  [[ "$output" == "$release_id|$release_root/ops/deploy/docker-compose.prod.yml|--verify-upload" ]] ||
    fail 'stable backup dispatch did not bind script and Compose to the same release marker'
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
  for index in $(seq 1 20); do
    finished="$(/usr/bin/date -u -d "2026-06-01 +$index days" '+%Y-%m-%dT%H:%M:%SZ')"
    set_id="$(/usr/bin/date -u -d "$finished" '+%Y%m%dT%H%M%SZ')-$(printf '%012x' "$index")"
    weekly=false
    monthly=false
    ((index % 2 == 0)) && weekly=true
    ((index % 3 == 0)) && monthly=true
    blob_body="blob-$index"
    blob_sha="$(printf '%s' "$blob_body" | sha256sum | awk '{print $1}')"
    printf '%s' "$blob_body" >"$REMOTE_STORE/production/blobs/$blob_sha"
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

test_reachability_pruning() {
  seed_prune_fixture
  : >"$COMMAND_LOG"
  local run_log="$FIXTURE_ROOT/prune.log"
  env "${BACKUP_ENVIRONMENT[@]}" \
    bash "$PRUNE_REMOTE" --dry-run >"$run_log" 2>&1 || {
      sed -n '1,200p' "$run_log" >&2
      fail 'stubbed 7/4/6 pruning dry-run failed'
    }
  local -a reports=()
  mapfile -t reports < <(find "$REMOTE_STORE/production/gc-reports" -type f -name '*.json' -print)
  [[ "${#reports[@]}" -eq 1 ]] || fail 'pruner did not persist exactly one immutable GC proposal'
  local report="${reports[0]}"
  jq -e '
    .schemaVersion == 1 and
    .prefix == "production" and
    .policy == {daily:7,weekly:4,monthly:6,safetyDays:14} and
    (.retainedSets | length == 11) and
    (.deleteSets | length == 9) and
    all(.deleteBlobs[]; test("^blobs/[0-9a-f]{64}$"))
  ' "$report" >/dev/null || fail 'retention proposal does not implement 7/4/6 distinct-set union'
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
    fail 'repository prune-guard example authorized a destination'
  fi
  grep -F 'version/Object-Lock/version-ID review is mandatory' "$FIXTURE_ROOT/guard-example.log" >/dev/null ||
    fail 'fail-closed prune-guard example did not explain its external gate'
}

test_systemd_contract() {
  grep -F 'OnCalendar=*-*-* 02:30:00 Asia/Hong_Kong' "$BACKUP_TIMER" >/dev/null ||
    fail 'nightly timer is not fixed at 02:30 Asia/Hong_Kong'
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
  grep -F 'ExecStart=/usr/bin/bash /usr/local/libexec/portfolio/backup-dispatch.sh prune' \
    "$PRUNE_SERVICE" >/dev/null || fail 'pruner service does not use the stable current-release dispatcher'
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
  grep -F 'OnCalendar=*-*-* 06:30:00 Asia/Hong_Kong' "$PRUNE_TIMER" >/dev/null ||
    fail 'prune timer does not run after the expected backup window'
  if grep -F '/opt/portfolio/current/' "$BACKUP_SERVICE" "$PRUNE_SERVICE" \
      "$REPOSITORY_ROOT/deploy/backup/postgres-client.sh" >/dev/null; then
    fail 'backup units or PostgreSQL client depend on an undeclared current symlink'
  fi
  grep -F 'BACKUP_PRUNE_GUARD_COMMAND=/usr/local/sbin/portfolio-backup-prune-guard' \
    "$REPOSITORY_ROOT/deploy/backup/backup.env.example" >/dev/null ||
    fail 'protected backup configuration does not resolve the external prune guard absolutely'
}

main() {
  for command_name in \
    awk base64 cmp find grep jq python3 realpath sed seq sha256sum sort stat tail
  do
    command -v "$command_name" >/dev/null 2>&1 || fail "$command_name is required"
  done
  for required_file in \
    "$VALIDATOR" "$BACKUP_SET" "$BACKUP_DISPATCH" "$BACKUP_MEDIA" "$VERIFY_ARTIFACT" \
    "$PRUNE_REMOTE" "$PRUNE_GUARD_EXAMPLE" "$NOTIFY_FAILURE" "$BACKUP_SERVICE" "$BACKUP_TIMER" \
    "$PRUNE_SERVICE" "$PRUNE_TIMER"
  do
    [[ -f "$required_file" ]] || fail 'a required encrypted-backup implementation file is missing'
  done
  WORK_DIRECTORY="$(mktemp -d)"
  test_media_tar_validation
  setup_backup_fixtures
  test_sample_policy
  test_successful_backup_set
  test_upload_failure_is_atomic
  test_credential_separation_fails_closed
  test_local_volume_identity_fails_closed
  test_verified_set_survives_maintenance_failure
  test_release_marker_dispatch
  test_prune_guard_is_mandatory
  test_reachability_pruning
  test_systemd_contract
  printf '%s\n' 'PASS: encrypted database/media backup, verification, and 7/4/6 pruning contracts'
}

main "$@"
