#!/usr/bin/env bash
set -euo pipefail
set +x
umask 077
export LC_ALL=C

SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=deploy/backup/lib.sh
source "$SCRIPT_DIRECTORY/lib.sh"

WORK_DIRECTORY=''
KEEPER_INPUT_FD=''
KEEPER_OUTPUT_FD=''
KEEPER_PID=''
KEEPER_LOCKED=false
KEEPER_UNLOCKED=false
DATABASE_PID=''
BACKUP_COMPLETED=false
FAILURE_CATEGORY=CONFIGURATION_FAILED
SET_ID=''
BACKUP_STARTED_AT=''
DATABASE_RUN_ID=''
MEDIA_RUN_ID=''
BACKUP_AGE_COMMAND="${BACKUP_AGE_COMMAND:-}"

now_utc() {
  "$BACKUP_DATE_COMMAND" -u '+%Y-%m-%dT%H:%M:%SZ'
}

keeper_send() {
  local statement="$1"
  [[ -n "$KEEPER_INPUT_FD" ]] || backup_fail 'snapshot keeper input is unavailable'
  printf '%s\n' "$statement" >&"$KEEPER_INPUT_FD"
}

keeper_read_match() {
  local pattern="$1"
  local label="$2"
  local line
  while IFS= read -r -t "$BACKUP_KEEPER_TIMEOUT_SECONDS" -u "$KEEPER_OUTPUT_FD" line; do
    line="${line%$'\r'}"
    [[ -n "$line" ]] || continue
    if [[ "$line" =~ $pattern ]]; then
      printf '%s\n' "$line"
      return 0
    fi
  done
  backup_fail "$label was not received from the snapshot keeper"
}

start_keeper() {
  coproc PORTFOLIO_SNAPSHOT_KEEPER {
    "$BACKUP_POSTGRES_CLIENT_COMMAND" psql -A -t -q --set ON_ERROR_STOP=1 2>/dev/null
  }
  KEEPER_OUTPUT_FD="${PORTFOLIO_SNAPSHOT_KEEPER[0]}"
  KEEPER_INPUT_FD="${PORTFOLIO_SNAPSHOT_KEEPER[1]}"
  KEEPER_PID="$PORTFOLIO_SNAPSHOT_KEEPER_PID"

  keeper_send "SELECT 'KEEPER_READY';"
  keeper_read_match '^KEEPER_READY$' 'snapshot keeper ready marker' >/dev/null
  keeper_send 'SELECT pg_advisory_lock_shared(1347375700,1296385097);'
  keeper_send "SELECT 'LOCK_ACQUIRED';"
  keeper_read_match '^LOCK_ACQUIRED$' 'shared media lifecycle lock marker' >/dev/null
  KEEPER_LOCKED=true
  keeper_send 'BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;'
  keeper_send "SELECT 'SNAPSHOT:' || pg_export_snapshot();"
}

commit_keeper_snapshot() {
  keeper_send 'COMMIT;'
  keeper_send "SELECT 'SNAPSHOT_COMMITTED';"
  keeper_read_match '^SNAPSHOT_COMMITTED$' 'snapshot commit marker' >/dev/null
}

unlock_keeper() {
  keeper_send "SELECT 'UNLOCK:' || pg_advisory_unlock_shared(1347375700,1296385097);"
  keeper_send "SELECT 'KEEPER_DONE';"
  local line unlock_rows=0 unlock_success=0
  while IFS= read -r -t "$BACKUP_KEEPER_TIMEOUT_SECONDS" -u "$KEEPER_OUTPUT_FD" line; do
    line="${line%$'\r'}"
    [[ -n "$line" ]] || continue
    if [[ "$line" == UNLOCK:* ]]; then
      ((unlock_rows += 1))
      [[ "$line" == UNLOCK:t ]] && ((unlock_success += 1))
    elif [[ "$line" == KEEPER_DONE ]]; then
      break
    fi
  done
  [[ "$unlock_rows" -eq 1 && "$unlock_success" -eq 1 ]] ||
    backup_fail 'snapshot keeper did not report exactly one successful shared unlock'
  KEEPER_UNLOCKED=true
  KEEPER_LOCKED=false
  keeper_send '\q'
  exec {KEEPER_INPUT_FD}>&-
  KEEPER_INPUT_FD=''
  wait "$KEEPER_PID" || backup_fail 'snapshot keeper exited unsuccessfully'
  KEEPER_PID=''
  exec {KEEPER_OUTPUT_FD}<&-
  KEEPER_OUTPUT_FD=''
}

abort_keeper() {
  set +e
  if [[ -n "$KEEPER_INPUT_FD" ]]; then
    printf '%s\n' \
      'ROLLBACK;' \
      'SELECT pg_advisory_unlock_shared(1347375700,1296385097);' \
      '\q' 1>&"$KEEPER_INPUT_FD" 2>/dev/null
    exec {KEEPER_INPUT_FD}>&- 2>/dev/null
    KEEPER_INPUT_FD=''
  fi
  if [[ -n "$KEEPER_PID" ]]; then
    kill "$KEEPER_PID" 2>/dev/null
    wait "$KEEPER_PID" 2>/dev/null
    KEEPER_PID=''
  fi
  if [[ -n "$KEEPER_OUTPUT_FD" ]]; then
    exec {KEEPER_OUTPUT_FD}<&- 2>/dev/null
    KEEPER_OUTPUT_FD=''
  fi
  KEEPER_LOCKED=false
}

record_failed_runs() {
  local finished_at="$1"
  [[ -n "$DATABASE_RUN_ID" && -n "$MEDIA_RUN_ID" && -n "$BACKUP_STARTED_AT" ]] || return 0
  backup_record_maintenance \
    "$DATABASE_RUN_ID" DATABASE_BACKUP FAILED \
    "$BACKUP_STARTED_AT" "$finished_at" '' "$FAILURE_CATEGORY" >/dev/null 2>&1 || true
  backup_record_maintenance \
    "$MEDIA_RUN_ID" MEDIA_BACKUP FAILED \
    "$BACKUP_STARTED_AT" "$finished_at" '' "$FAILURE_CATEGORY" >/dev/null 2>&1 || true
}

notify_failure() {
  local finished_at="$1"
  [[ -n "$SET_ID" && -n "$BACKUP_STARTED_AT" && -x "${BACKUP_NOTIFY_COMMAND:-}" ]] || return 0
  if ! jq -S -c -n \
      --arg setId "$SET_ID" \
      --arg startedAt "$BACKUP_STARTED_AT" \
      --arg finishedAt "$finished_at" \
      --arg category "$FAILURE_CATEGORY" \
      '{schemaVersion:1,setId:$setId,startedAt:$startedAt,finishedAt:$finishedAt,category:$category}' |
      "$BACKUP_NOTIFY_COMMAND" >/dev/null 2>&1; then
    backup_note 'independent failure notification did not complete'
  fi
}

cleanup() {
  set +e
  if [[ -n "$DATABASE_PID" ]]; then
    kill "$DATABASE_PID" 2>/dev/null
    wait "$DATABASE_PID" 2>/dev/null
    DATABASE_PID=''
  fi
  if [[ "$KEEPER_LOCKED" == true || -n "$KEEPER_PID" ]]; then
    abort_keeper
  fi
  if [[ -n "$WORK_DIRECTORY" && -d "$WORK_DIRECTORY" ]]; then
    rm -rf -- "$WORK_DIRECTORY"
  fi
}

on_exit() {
  local status="$?"
  trap - EXIT HUP INT TERM
  if [[ "$BACKUP_COMPLETED" != true ]]; then
    cleanup
    local finished_at
    finished_at="$(now_utc 2>/dev/null || printf '%s' "$BACKUP_STARTED_AT")"
    backup_is_rfc3339_utc "$finished_at" || finished_at="$BACKUP_STARTED_AT"
    record_failed_runs "$finished_at"
    notify_failure "$finished_at"
  else
    cleanup
  fi
  exit "$status"
}

on_signal() {
  local status="$1"
  trap - HUP INT TERM
  FAILURE_CATEGORY=INTERNAL_FAILED
  exit "$status"
}

trap on_exit EXIT
trap 'on_signal 129' HUP
trap 'on_signal 130' INT
trap 'on_signal 143' TERM

sample_count() {
  local total="$1"
  if ((total == 0)); then
    printf '0\n'
  elif ((total < 20)); then
    printf '%s\n' "$total"
  else
    local value=$(((total + 99) / 100))
    ((value < 20)) && value=20
    ((value > 200)) && value=200
    ((value > total)) && value="$total"
    printf '%s\n' "$value"
  fi
}

encrypt_artifact() {
  local plaintext="$1"
  local ciphertext="$2"
  [[ ! -L "$plaintext" && -f "$plaintext" && ! -e "$ciphertext" ]] ||
    backup_fail 'encryption input or output path is invalid'
  if ! "$BACKUP_AGE_COMMAND" -r "$BACKUP_AGE_RECIPIENT" -o "$ciphertext" "$plaintext" \
      >/dev/null 2>&1; then
    backup_fail 'age encryption failed'
  fi
  [[ ! -L "$ciphertext" && -s "$ciphertext" ]] || backup_fail 'age produced no ciphertext'
}

[[ $# -eq 1 && "$1" == --verify-upload ]] ||
  backup_fail 'usage: backup-set.sh --verify-upload'

# Establish a redacted failure identity before validating the rest of the
# protected configuration. This lets topology/credential mistakes notify even
# though no snapshot or remote write has started.
for bootstrap_command in cut jq tr; do
  command -v "$bootstrap_command" >/dev/null 2>&1 ||
    backup_fail "$bootstrap_command is required"
done
BACKUP_DATE_COMMAND="${BACKUP_DATE_COMMAND:-$(command -v date 2>/dev/null || true)}"
BACKUP_OPENSSL_COMMAND="${BACKUP_OPENSSL_COMMAND:-$(command -v openssl 2>/dev/null || true)}"
BACKUP_NOTIFY_COMMAND="${BACKUP_NOTIFY_COMMAND:-$SCRIPT_DIRECTORY/notify-failure.sh}"
BACKUP_POSTGRES_CLIENT_COMMAND="${BACKUP_POSTGRES_CLIENT_COMMAND:-$SCRIPT_DIRECTORY/postgres-client.sh}"
backup_resolve_command BACKUP_DATE_COMMAND date
backup_resolve_command BACKUP_OPENSSL_COMMAND openssl
backup_resolve_command BACKUP_NOTIFY_COMMAND notify-failure.sh
BACKUP_STARTED_AT="$(now_utc)"
backup_is_rfc3339_utc "$BACKUP_STARTED_AT" || backup_fail 'backup clock returned an invalid start time'
set_timestamp="$("$BACKUP_DATE_COMMAND" -u '+%Y%m%dT%H%M%SZ')"
[[ "$set_timestamp" =~ ^[0-9]{8}T[0-9]{6}Z$ ]] || backup_fail 'backup clock returned an invalid set timestamp'
set_suffix="$(backup_random_hex 6)"
SET_ID="$set_timestamp-$set_suffix"
backup_is_set_id "$SET_ID" || backup_fail 'generated set ID is invalid'
DATABASE_RUN_ID="$(backup_create_uuid)"
MEDIA_RUN_ID="$(backup_create_uuid)"

for forbidden_name in BACKUP_AGE_IDENTITY AGE_SECRET_KEY AGE_SECRET_KEY_FILE COS_SECRET_ID COS_SECRET_KEY SMTP_PASSWORD; do
  [[ -z "${!forbidden_name:-}" ]] ||
    backup_fail 'private decrypt/runtime credentials must not enter the nightly backup service'
done

for command_name in awk base64 cmp dirname flock grep install jq mkdir mktemp realpath sha256sum stat tr wc; do
  command -v "$command_name" >/dev/null 2>&1 || backup_fail "$command_name is required"
done

backup_initialize_commands
BACKUP_DATABASE_COMMAND="${BACKUP_DATABASE_COMMAND:-$SCRIPT_DIRECTORY/backup-database.sh}"
BACKUP_MEDIA_COMMAND="${BACKUP_MEDIA_COMMAND:-$SCRIPT_DIRECTORY/backup-media.sh}"
BACKUP_VERIFY_COMMAND="${BACKUP_VERIFY_COMMAND:-$SCRIPT_DIRECTORY/verify-artifact.sh}"
backup_resolve_command BACKUP_DATABASE_COMMAND backup-database.sh
backup_resolve_command BACKUP_MEDIA_COMMAND backup-media.sh
backup_resolve_command BACKUP_VERIFY_COMMAND verify-artifact.sh

BACKUP_KEEPER_TIMEOUT_SECONDS="${BACKUP_KEEPER_TIMEOUT_SECONDS:-300}"
[[ "$BACKUP_KEEPER_TIMEOUT_SECONDS" =~ ^[1-9][0-9]{0,3}$ ]] ||
  backup_fail 'BACKUP_KEEPER_TIMEOUT_SECONDS is invalid'
backup_require_value BACKUP_AGE_RECIPIENT
[[ "$BACKUP_AGE_RECIPIENT" =~ ^age1[0-9a-z]{20,100}$ ]] ||
  backup_fail 'BACKUP_AGE_RECIPIENT is not a public age recipient'
backup_require_value PORTFOLIO_RELEASE_ID
[[ "$PORTFOLIO_RELEASE_ID" =~ ^[0-9a-f]{12}-[0-9a-f]{12}$ ]] ||
  backup_fail 'PORTFOLIO_RELEASE_ID is invalid'
backup_require_value BACKUP_TIMEZONE
BACKUP_ZONEINFO_ROOT="${BACKUP_ZONEINFO_ROOT:-/usr/share/zoneinfo}"
[[ "$BACKUP_TIMEZONE" =~ ^[A-Za-z_+-]+(/[A-Za-z0-9_+-]+)+$ &&
   "$BACKUP_ZONEINFO_ROOT" == /* && ! -L "$BACKUP_ZONEINFO_ROOT" &&
   -d "$BACKUP_ZONEINFO_ROOT" &&
   ! -L "$BACKUP_ZONEINFO_ROOT/$BACKUP_TIMEZONE" &&
   -f "$BACKUP_ZONEINFO_ROOT/$BACKUP_TIMEZONE" ]] || backup_fail 'BACKUP_TIMEZONE is invalid'
BACKUP_WEEKLY_DAY="${BACKUP_WEEKLY_DAY:-1}"
[[ "$BACKUP_WEEKLY_DAY" =~ ^[1-7]$ ]] || backup_fail 'BACKUP_WEEKLY_DAY must be 1 through 7'
backup_validate_topology nightly
backup_acquire_operation_lock
unset LOCAL_MEDIA_ROOT
backup_resolve_local_media_volume

BACKUP_STAGING_ROOT="${BACKUP_STAGING_ROOT:-/var/backups/portfolio/staging}"
[[ "$BACKUP_STAGING_ROOT" == /* && "$BACKUP_STAGING_ROOT" != / && ! -L "$BACKUP_STAGING_ROOT" ]] ||
  backup_fail 'BACKUP_STAGING_ROOT is unsafe'
backup_ensure_private_directory "$BACKUP_STAGING_ROOT" 'backup staging root'
WORK_DIRECTORY="$(mktemp -d "$BACKUP_STAGING_ROOT/$SET_ID.XXXXXX")"
chmod 0700 "$WORK_DIRECTORY"
backup_require_private_directory "$WORK_DIRECTORY" 'backup set staging directory'

database_dump="$WORK_DIRECTORY/database.dump"
database_list="$WORK_DIRECTORY/database.list"
database_metadata="$WORK_DIRECTORY/database-metadata.json"
media_export="$WORK_DIRECTORY/media-export.json"
local_tar="$WORK_DIRECTORY/local-media.tar"
local_allowlist="$WORK_DIRECTORY/local-media-allowlist.json"
media_manifest="$WORK_DIRECTORY/media-manifest.json"
blobs_metadata="$WORK_DIRECTORY/blobs.json"

FAILURE_CATEGORY=SNAPSHOT_FAILED
start_keeper
snapshot_marker="$(keeper_read_match '^SNAPSHOT:[A-Za-z0-9-]{8,128}$' 'exported snapshot marker')"
snapshot_id="${snapshot_marker#SNAPSHOT:}"

exec {database_snapshot_fd}<<<"$snapshot_id"
FAILURE_CATEGORY=DATABASE_FAILED
"$BACKUP_DATABASE_COMMAND" \
  --snapshot-fd "$database_snapshot_fd" \
  --output "$database_dump" \
  --list-output "$database_list" \
  --metadata-output "$database_metadata" &
DATABASE_PID="$!"
exec {database_snapshot_fd}<&-

exec {media_snapshot_fd}<<<"$snapshot_id"
FAILURE_CATEGORY=MEDIA_FAILED
"$BACKUP_MEDIA_COMMAND" export \
  --snapshot-fd "$media_snapshot_fd" \
  --output "$media_export"
exec {media_snapshot_fd}<&-

if ! wait "$DATABASE_PID"; then
  DATABASE_PID=''
  FAILURE_CATEGORY=DATABASE_FAILED
  backup_fail 'database snapshot consumer failed'
fi
DATABASE_PID=''
commit_keeper_snapshot

database_plain_sha="$(jq -r '.plaintextSha256' "$database_metadata")"
backup_is_sha256 "$database_plain_sha" || backup_fail 'database metadata checksum is invalid'
FAILURE_CATEGORY=MEDIA_FAILED
"$BACKUP_MEDIA_COMMAND" package \
  --export "$media_export" \
  --database-sha "$database_plain_sha" \
  --set-id "$SET_ID" \
  --local-tar "$local_tar" \
  --local-allowlist "$local_allowlist" \
  --manifest-output "$media_manifest" \
  --blobs-output "$blobs_metadata" \
  --work-directory "$WORK_DIRECTORY"

database_cipher="$WORK_DIRECTORY/database.dump.age"
local_cipher="$WORK_DIRECTORY/local-media.tar.age"
manifest_cipher="$WORK_DIRECTORY/media-manifest.json.age"
encrypt_artifact "$database_dump" "$database_cipher"
encrypt_artifact "$local_tar" "$local_cipher"
encrypt_artifact "$media_manifest" "$manifest_cipher"

snapshot_id="$(jq -r '.snapshotId' "$media_export")"
total_objects="$(jq '.counts.totalObjects' "$blobs_metadata")"
local_objects="$(jq '.counts.localObjects' "$blobs_metadata")"
cos_objects="$(jq '.counts.cosObjects' "$blobs_metadata")"
distinct_blobs="$(jq '.counts.distinctCosBlobs' "$blobs_metadata")"
cos_samples="$(sample_count "$distinct_blobs")"

calendar_day="$(TZ="$BACKUP_TIMEZONE" "$BACKUP_DATE_COMMAND" '+%Y-%m-%d')"
calendar_weekday="$(TZ="$BACKUP_TIMEZONE" "$BACKUP_DATE_COMMAND" '+%u')"
[[ "$calendar_day" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ && "$calendar_weekday" =~ ^[1-7]$ ]] ||
  backup_fail 'backup calendar returned invalid retention metadata'
weekly=false
monthly=false
[[ "$calendar_weekday" == "$BACKUP_WEEKLY_DAY" ]] && weekly=true
[[ "${calendar_day##*-}" == 01 ]] && monthly=true
manifest_finished_at="$(now_utc)"
backup_is_rfc3339_utc "$manifest_finished_at" || backup_fail 'backup clock returned an invalid finish time'
[[ "$manifest_finished_at" == "$BACKUP_STARTED_AT" || "$manifest_finished_at" > "$BACKUP_STARTED_AT" ]] ||
  backup_fail 'backup finish time precedes its start time'

set_manifest="$WORK_DIRECTORY/set-manifest.json"
jq -S -c -n \
  --arg setId "$SET_ID" \
  --arg releaseId "$PORTFOLIO_RELEASE_ID" \
  --arg snapshotId "$snapshot_id" \
  --arg backupStartedAt "$BACKUP_STARTED_AT" \
  --arg backupFinishedAt "$manifest_finished_at" \
  --argjson weekly "$weekly" \
  --argjson monthly "$monthly" \
  --arg databaseSha "$(backup_sha256_file "$database_cipher")" \
  --argjson databaseSize "$(backup_file_size "$database_cipher")" \
  --arg localSha "$(backup_sha256_file "$local_cipher")" \
  --argjson localSize "$(backup_file_size "$local_cipher")" \
  --arg manifestSha "$(backup_sha256_file "$manifest_cipher")" \
  --argjson manifestSize "$(backup_file_size "$manifest_cipher")" \
  --argjson totalObjects "$total_objects" \
  --argjson localObjects "$local_objects" \
  --argjson cosObjects "$cos_objects" \
  --argjson distinctCosBlobs "$distinct_blobs" \
  --argjson cosVerificationSamples "$cos_samples" \
  --slurpfile blobDocument "$blobs_metadata" \
  '{schemaVersion:1,setId:$setId,releaseId:$releaseId,snapshotId:$snapshotId,
    backupStartedAt:$backupStartedAt,backupFinishedAt:$backupFinishedAt,
    retention:{daily:true,weekly:$weekly,monthly:$monthly},
    counts:{totalObjects:$totalObjects,localObjects:$localObjects,cosObjects:$cosObjects,
      distinctCosBlobs:$distinctCosBlobs,cosVerificationSamples:$cosVerificationSamples},
    artifacts:{
      databaseDump:{file:"database.dump.age",ciphertextSha256:$databaseSha,byteSize:$databaseSize},
      localMediaTar:{file:"local-media.tar.age",ciphertextSha256:$localSha,byteSize:$localSize},
      mediaManifest:{file:"media-manifest.json.age",ciphertextSha256:$manifestSha,byteSize:$manifestSize}
    },
    blobs:$blobDocument[0].blobs}' >"$set_manifest"

# Plaintext data, source identities, and the exported snapshot leave memory and
# private staging before any set-local object is published remotely.
rm -f -- \
  "$database_dump" "$database_list" "$database_metadata" \
  "$media_export" "$local_tar" "$local_allowlist" "$media_manifest" "$blobs_metadata"
unset snapshot_id snapshot_marker database_plain_sha

FAILURE_CATEGORY=UPLOAD_FAILED
for artifact in "$database_cipher" "$local_cipher" "$manifest_cipher"; do
  filename="$(basename "$artifact")"
  if ! backup_upload_immutable "$artifact" "$(backup_upload_remote_path "$SET_ID" "$filename")" \
      >/dev/null 2>&1; then
    backup_fail 'isolated upload-attempt artifact upload failed'
  fi
done
if ! backup_upload_immutable "$set_manifest" \
    "$(backup_upload_remote_path "$SET_ID" set-manifest.json)" >/dev/null 2>&1; then
  backup_fail 'isolated upload-attempt manifest upload failed'
fi

set_manifest_sha="$(backup_sha256_file "$set_manifest")"
FAILURE_CATEGORY=VERIFY_FAILED
verified_sha="$("$BACKUP_VERIFY_COMMAND" set \
  --set-id "$SET_ID" \
  --expected-manifest-sha "$set_manifest_sha" \
  --local-manifest "$set_manifest" \
  --work-directory "$WORK_DIRECTORY")"
[[ "$verified_sha" == "$set_manifest_sha" ]] || backup_fail 'set verifier returned a mismatched checksum'

verified_marker="$WORK_DIRECTORY/VERIFIED"
printf '%s\n' "$set_manifest_sha" >"$verified_marker"
FAILURE_CATEGORY=UPLOAD_FAILED
if ! backup_upload_immutable "$verified_marker" \
    "$(backup_upload_remote_path "$SET_ID" VERIFIED)" >/dev/null 2>&1; then
  backup_fail 'isolated upload-attempt verification marker upload failed'
fi
FAILURE_CATEGORY=VERIFY_FAILED
marker_sha="$("$BACKUP_VERIFY_COMMAND" marker \
  --set-id "$SET_ID" \
  --expected-manifest-sha "$set_manifest_sha" \
  --work-directory "$WORK_DIRECTORY")"
[[ "$marker_sha" == "$set_manifest_sha" ]] || backup_fail 'verification marker read-back failed'

# A completed restore point is one atomic object. All multi-object writes stay
# under uploading/{setId}; failed attempts can never appear in sets/.
completion_record="$WORK_DIRECTORY/completion-record.json"
jq -S -c -n \
  --arg setId "$SET_ID" \
  --arg uploadPrefix "uploading/$SET_ID" \
  --arg manifestSha256 "$set_manifest_sha" \
  --argjson manifestByteSize "$(backup_file_size "$set_manifest")" \
  '{schemaVersion:2,setId:$setId,uploadPrefix:$uploadPrefix,
    manifestSha256:$manifestSha256,manifestByteSize:$manifestByteSize}' \
  >"$completion_record"
FAILURE_CATEGORY=UPLOAD_FAILED
if ! backup_upload_immutable "$completion_record" \
    "$(backup_set_remote_path "$SET_ID" VERIFIED)" >/dev/null 2>&1; then
  backup_fail 'atomic completed-set publication failed'
fi
FAILURE_CATEGORY=VERIFY_FAILED
completion_sha="$("$BACKUP_VERIFY_COMMAND" completion \
  --set-id "$SET_ID" \
  --expected-manifest-sha "$set_manifest_sha" \
  --local-record "$completion_record" \
  --work-directory "$WORK_DIRECTORY")"
[[ "$completion_sha" == "$set_manifest_sha" ]] ||
  backup_fail 'completed-set record read-back failed'

unlock_keeper
[[ "$KEEPER_UNLOCKED" == true ]] || backup_fail 'verified backup did not release its shared lock'

finished_at="$(now_utc)"
backup_is_rfc3339_utc "$finished_at" || backup_fail 'backup clock returned an invalid completion time'
FAILURE_CATEGORY=MAINTENANCE_WRITE_FAILED
backup_record_maintenance \
  "$DATABASE_RUN_ID" DATABASE_BACKUP SUCCEEDED \
  "$BACKUP_STARTED_AT" "$finished_at" "$set_manifest_sha" NONE
backup_record_maintenance \
  "$MEDIA_RUN_ID" MEDIA_BACKUP SUCCEEDED \
  "$BACKUP_STARTED_AT" "$finished_at" "$set_manifest_sha" NONE

BACKUP_COMPLETED=true
backup_note 'encrypted database and mixed-provider media set was remotely verified'
