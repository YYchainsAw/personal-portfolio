#!/usr/bin/env bash
set -euo pipefail
set +x
umask 077
export LC_ALL=C

SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=deploy/backup/lib.sh
source "$SCRIPT_DIRECTORY/lib.sh"

dry_run=false
case "$#:$*" in
  0:) ;;
  '1:--dry-run') dry_run=true ;;
  *) backup_fail 'usage: prune-remote.sh [--dry-run]' ;;
esac

for forbidden_name in \
  BACKUP_AGE_IDENTITY AGE_SECRET_KEY COS_SECRET_ID COS_SECRET_KEY \
  PORTFOLIO_DB_RUNTIME_PASSWORD POSTGRES_PASSWORD SMTP_PASSWORD
do
  [[ -z "${!forbidden_name:-}" ]] ||
    backup_fail 'database, media-body, decrypt, and notification credentials are forbidden in the pruner service'
done

for command_name in awk grep install jq mktemp sed sha256sum sort stat tr wc; do
  command -v "$command_name" >/dev/null 2>&1 || backup_fail "$command_name is required"
done
backup_resolve_command BACKUP_RCLONE_COMMAND rclone
backup_resolve_command BACKUP_DATE_COMMAND date
backup_resolve_command BACKUP_OPENSSL_COMMAND openssl
backup_resolve_command BACKUP_PRUNE_GUARD_COMMAND portfolio-backup-prune-guard
backup_validate_topology prune

BACKUP_RETENTION_SAFETY_DAYS="${BACKUP_RETENTION_SAFETY_DAYS:-14}"
[[ "$BACKUP_RETENTION_SAFETY_DAYS" =~ ^[1-9][0-9]{0,3}$ ]] ||
  backup_fail 'BACKUP_RETENTION_SAFETY_DAYS is invalid'
BACKUP_PRUNE_STAGING_ROOT="${BACKUP_PRUNE_STAGING_ROOT:-/var/backups/portfolio/prune}"
[[ "$BACKUP_PRUNE_STAGING_ROOT" == /* && "$BACKUP_PRUNE_STAGING_ROOT" != / &&
   ! -L "$BACKUP_PRUNE_STAGING_ROOT" ]] || backup_fail 'prune staging root is unsafe'
install -d -m 0700 -- "$BACKUP_PRUNE_STAGING_ROOT"
backup_require_private_directory "$BACKUP_PRUNE_STAGING_ROOT" 'prune staging root'
work_directory="$(mktemp -d "$BACKUP_PRUNE_STAGING_ROOT/prune.XXXXXX")"
chmod 0700 "$work_directory"
trap 'rm -rf -- "$work_directory"' EXIT HUP INT TERM
mkdir -m 0700 "$work_directory/manifests"

guard_destination_output="$work_directory/destination.guard"
if ! "$BACKUP_PRUNE_GUARD_COMMAND" verify-destination \
    --remote "$BACKUP_REMOTE" \
    --prefix "$BACKUP_PREFIX" \
    --account "$BACKUP_DESTINATION_ACCOUNT_ID" \
    --bucket "$BACKUP_DESTINATION_BUCKET" \
    --principal "$BACKUP_PRUNE_PRINCIPAL_ID" \
    --rclone-config "$BACKUP_PRUNE_RCLONE_CONFIG" >"$guard_destination_output" 2>/dev/null; then
  backup_fail 'destination versioning, retention, prefix, or pruner role verification failed'
fi
[[ "$(tr -d '\r\n' <"$guard_destination_output")" == SAFE ]] ||
  backup_fail 'destination guard did not authorize the reviewed non-root prefix'

sets_listing="$work_directory/sets.list"
if ! backup_rclone "$BACKUP_PRUNE_RCLONE_CONFIG" lsf \
    --dirs-only --max-depth 1 -- "$(backup_remote_namespace_root sets)" \
    >"$sets_listing" 2>/dev/null; then
  backup_fail 'pruner could not list verified-set candidates'
fi

set_index="$work_directory/set-index.tsv"
: >"$set_index"
while IFS= read -r directory; do
  [[ -n "$directory" ]] || continue
  set_id="${directory%/}"
  backup_is_set_id "$set_id" || backup_fail 'set namespace contains an unexpected directory'
  [[ "$directory" == "$set_id/" ]] || backup_fail 'set directory listing is not canonical'
  manifest="$work_directory/manifests/$set_id.json"
  marker="$work_directory/manifests/$set_id.VERIFIED"
  if ! backup_rclone "$BACKUP_PRUNE_RCLONE_CONFIG" copyto -- \
      "$(backup_set_remote_path "$set_id" set-manifest.json)" "$manifest" >/dev/null 2>&1; then
    backup_fail 'pruner could not read a set manifest'
  fi
  if ! backup_rclone "$BACKUP_PRUNE_RCLONE_CONFIG" copyto -- \
      "$(backup_set_remote_path "$set_id" VERIFIED)" "$marker" >/dev/null 2>&1; then
    backup_fail 'pruner could not read a set verification marker'
  fi
  manifest_sha="$(backup_sha256_file "$manifest")"
  [[ "$(wc -l <"$marker")" -eq 1 && "$(tr -d '\r\n' <"$marker")" == "$manifest_sha" ]] ||
    backup_fail 'set verification marker does not match its manifest'
  jq -e --arg setId "$set_id" '
    .schemaVersion == 1 and .setId == $setId and
    (.backupFinishedAt | type == "string" and
      test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")) and
    (.retention | keys == ["daily","monthly","weekly"] and
      .daily == true and (.weekly | type == "boolean") and (.monthly | type == "boolean")) and
    (.blobs | type == "array") and
    all(.blobs[];
      (.path | type == "string" and test("^blobs/[0-9a-f]{64}$")) and
      (.sha256 | type == "string" and test("^[0-9a-f]{64}$")) and
      (.path == ("blobs/" + .sha256)) and
      (.byteSize | type == "number" and floor == . and . > 0)) and
    ((.blobs | map(.path)) == (.blobs | map(.path) | sort | unique))
  ' "$manifest" >/dev/null || backup_fail 'retention encountered an invalid self-contained set manifest'
  finished_at="$(jq -r '.backupFinishedAt' "$manifest")"
  daily="$(jq -r '.retention.daily' "$manifest")"
  weekly="$(jq -r '.retention.weekly' "$manifest")"
  monthly="$(jq -r '.retention.monthly' "$manifest")"
  printf '%s\t%s\t%s\t%s\t%s\n' \
    "$finished_at" "$set_id" "$daily" "$weekly" "$monthly" >>"$set_index"
done <"$sets_listing"

sort -r -o "$set_index" "$set_index"
declare -A retained=()
retain_class() {
  local column="$1"
  local limit="$2"
  local count=0 _finished set_id daily weekly monthly selected
  while IFS=$'\t' read -r _finished set_id daily weekly monthly; do
    [[ -n "$set_id" ]] || continue
    case "$column" in
      daily) selected="$daily" ;;
      weekly) selected="$weekly" ;;
      monthly) selected="$monthly" ;;
      *) backup_fail 'retention class is invalid' ;;
    esac
    if [[ "$selected" == true && "$count" -lt "$limit" ]]; then
      retained["$set_id"]=1
      ((count += 1))
    fi
  done <"$set_index"
}
retain_class daily 7
retain_class weekly 4
retain_class monthly 6

if ((${#retained[@]} == 0)); then
  [[ "${BACKUP_DISASTER_RESET_CONFIRMATION:-}" == I_UNDERSTAND_NO_VERIFIED_SET_WILL_REMAIN ]] ||
    backup_fail 'retained-set collection is empty; disaster-reset confirmation is required'
fi

retained_file="$work_directory/retained.txt"
printf '%s\n' "${!retained[@]}" | sed '/^$/d' | sort >"$retained_file"
reachable_blobs="$work_directory/reachable-blobs.txt"
: >"$reachable_blobs"
while IFS= read -r set_id; do
  [[ -n "$set_id" ]] || continue
  jq -r '.blobs[].path' "$work_directory/manifests/$set_id.json" >>"$reachable_blobs"
done <"$retained_file"
sort -u -o "$reachable_blobs" "$reachable_blobs"

now_epoch="$("$BACKUP_DATE_COMMAND" -u '+%s')"
[[ "$now_epoch" =~ ^[1-9][0-9]{8,}$ ]] || backup_fail 'pruner clock is invalid'
safety_seconds=$((BACKUP_RETENTION_SAFETY_DAYS * 86400))
delete_sets="$work_directory/delete-sets.txt"
: >"$delete_sets"
while IFS=$'\t' read -r finished_at set_id daily weekly monthly; do
  [[ -n "$set_id" ]] || continue
  [[ -z "${retained[$set_id]:-}" ]] || continue
  finished_epoch="$("$BACKUP_DATE_COMMAND" -u -d "$finished_at" '+%s')" ||
    backup_fail 'set completion time cannot be parsed'
  [[ "$finished_epoch" =~ ^[1-9][0-9]{8,}$ ]] || backup_fail 'set completion epoch is invalid'
  ((now_epoch - finished_epoch >= safety_seconds)) || continue
  printf '%s\n' "$set_id" >>"$delete_sets"
done <"$set_index"

blob_listing="$work_directory/blobs.json"
if ! backup_rclone "$BACKUP_PRUNE_RCLONE_CONFIG" lsjson \
    --files-only --recursive=false -- "$(backup_remote_namespace_root blobs)" \
    >"$blob_listing" 2>/dev/null; then
  backup_fail 'pruner could not list immutable blob metadata'
fi
jq -e '
  type == "array" and all(.[];
    (keys | index("Path") != null) and (keys | index("Size") != null) and
    (keys | index("ModTime") != null) and
    (.Path | type == "string" and test("^[0-9a-f]{64}$")) and
    (.Size | type == "number" and floor == . and . > 0) and
    (.ModTime | type == "string" and
      test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]+)?Z$")))
' "$blob_listing" >/dev/null || backup_fail 'blob namespace contains invalid metadata'
delete_blobs="$work_directory/delete-blobs.txt"
: >"$delete_blobs"
while IFS=$'\t' read -r sha mod_time; do
  [[ -n "$sha" ]] || continue
  grep -Fxq -- "blobs/$sha" "$reachable_blobs" && continue
  modified_epoch="$("$BACKUP_DATE_COMMAND" -u -d "$mod_time" '+%s')" ||
    backup_fail 'blob modification time cannot be parsed'
  ((now_epoch - modified_epoch >= safety_seconds)) || continue
  printf '%s\n' "$sha" >>"$delete_blobs"
done < <(jq -r '.[] | [.Path,.ModTime] | @tsv' "$blob_listing")

review_candidate() {
  local kind="$1"
  local relative="$2"
  local evidence="$3"
  local output
  output="$work_directory/review-$(printf '%s' "$kind:$relative" | sha256sum | awk '{print $1}').guard"
  if ! "$BACKUP_PRUNE_GUARD_COMMAND" review-candidate \
      --kind "$kind" \
      --relative-path "$relative" \
      --evidence "$evidence" \
      --remote "$BACKUP_REMOTE" \
      --prefix "$BACKUP_PREFIX" \
      --rclone-config "$BACKUP_PRUNE_RCLONE_CONFIG" >"$output" 2>/dev/null; then
    backup_fail 'a retention candidate failed version and retention review'
  fi
  [[ "$(tr -d '\r\n' <"$output")" == SAFE ]] ||
    backup_fail 'retention guard rejected a deletion candidate'
}

while IFS= read -r set_id; do
  [[ -n "$set_id" ]] || continue
  review_candidate set "sets/$set_id" "$work_directory/manifests/$set_id.json"
done <"$delete_sets"
while IFS= read -r sha; do
  [[ -n "$sha" ]] || continue
  review_candidate blob "blobs/$sha" "$blob_listing"
done <"$delete_blobs"

generated_at="$("$BACKUP_DATE_COMMAND" -u '+%Y-%m-%dT%H:%M:%SZ')"
backup_is_rfc3339_utc "$generated_at" || backup_fail 'GC report time is invalid'
report="$work_directory/gc-report.json"
jq -S -c -n \
  --arg generatedAt "$generated_at" \
  --arg prefix "$BACKUP_PREFIX" \
  --argjson safetyDays "$BACKUP_RETENTION_SAFETY_DAYS" \
  --rawfile retained "$retained_file" \
  --rawfile deleteSets "$delete_sets" \
  --rawfile deleteBlobs "$delete_blobs" \
  '{schemaVersion:1,generatedAt:$generatedAt,prefix:$prefix,
    policy:{daily:7,weekly:4,monthly:6,safetyDays:$safetyDays},
    retainedSets:($retained|split("\n")|map(select(length>0))|sort),
    deleteSets:($deleteSets|split("\n")|map(select(length>0))|sort),
    deleteBlobs:($deleteBlobs|split("\n")|map(select(length>0)|"blobs/"+.)|sort)}' \
  >"$report"
report_id="$("$BACKUP_DATE_COMMAND" -u '+%Y%m%dT%H%M%SZ')-$(backup_random_hex 6).json"
if ! backup_rclone "$BACKUP_PRUNE_RCLONE_CONFIG" copyto --immutable -- \
    "$report" "$(backup_remote_path gc-reports "$report_id")" >/dev/null 2>&1; then
  backup_fail 'immutable GC proposal upload failed'
fi

if [[ "$dry_run" == false ]]; then
  while IFS= read -r set_id; do
    [[ -n "$set_id" ]] || continue
    output="$work_directory/delete-set-$set_id.guard"
    "$BACKUP_PRUNE_GUARD_COMMAND" delete-reviewed \
      --kind set \
      --relative-path "sets/$set_id" \
      --evidence "$work_directory/manifests/$set_id.json" \
      --remote "$BACKUP_REMOTE" \
      --prefix "$BACKUP_PREFIX" \
      --rclone-config "$BACKUP_PRUNE_RCLONE_CONFIG" >"$output" 2>/dev/null ||
      backup_fail 'reviewed set deletion failed'
    [[ "$(tr -d '\r\n' <"$output")" == DELETED ]] ||
      backup_fail 'set deletion adapter did not confirm a version-scoped delete'
  done <"$delete_sets"
  while IFS= read -r sha; do
    [[ -n "$sha" ]] || continue
    output="$work_directory/delete-blob-$sha.guard"
    "$BACKUP_PRUNE_GUARD_COMMAND" delete-reviewed \
      --kind blob \
      --relative-path "blobs/$sha" \
      --evidence "$blob_listing" \
      --remote "$BACKUP_REMOTE" \
      --prefix "$BACKUP_PREFIX" \
      --rclone-config "$BACKUP_PRUNE_RCLONE_CONFIG" >"$output" 2>/dev/null ||
      backup_fail 'reviewed blob deletion failed'
    [[ "$(tr -d '\r\n' <"$output")" == DELETED ]] ||
      backup_fail 'blob deletion adapter did not confirm a version-scoped delete'
  done <"$delete_blobs"
fi

backup_note '7/4/6 reachability retention proposal completed under the reviewed prefix'
