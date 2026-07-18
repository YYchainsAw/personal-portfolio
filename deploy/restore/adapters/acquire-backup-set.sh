#!/usr/bin/env bash
set -euo pipefail
set +x
umask 077

SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=deploy/restore/adapters/adapter-lib.sh
source "$SCRIPT_DIRECTORY/adapter-lib.sh"
readonly TASK5_VERIFY="$SCRIPT_DIRECTORY/../../backup/verify-artifact.sh"

WORK_DIRECTORY=''
cleanup() {
  [[ -z "$WORK_DIRECTORY" || ! -d "$WORK_DIRECTORY" ]] || rm -rf -- "$WORK_DIRECTORY"
}
trap cleanup EXIT HUP INT TERM

main() {
  local set_id='' output='' verifier_config=''
  while (($#)); do
    case "$1" in
      --set-id) set_id="$2"; shift 2 ;;
      --output) output="$2"; shift 2 ;;
      --verifier-config) verifier_config="$2"; shift 2 ;;
      *) adapter_fail 'usage: acquire-backup-set.sh --set-id ID --output DIR --verifier-config FILE' ;;
    esac
  done
  adapter_require_drill_context
  adapter_is_set_id "$set_id" || adapter_fail 'backup set ID is invalid'
  [[ "$output" == "$RESTORE_ROOT/set" && ! -e "$output" && ! -L "$output" ]] ||
    adapter_fail 'backup set output must be a new drill-root set directory'
  adapter_require_output_beneath_root "$output"
  adapter_require_private_file "$verifier_config" 'backup verifier rclone config'
  adapter_resolve_command RESTORE_RCLONE_COMMAND rclone
  [[ -x "$TASK5_VERIFY" ]] || adapter_fail 'tracked Task5 verifier is unavailable'
  adapter_require_value BACKUP_REMOTE
  adapter_require_value BACKUP_PREFIX
  adapter_require_value BACKUP_DESTINATION_ACCOUNT_ID
  adapter_require_value BACKUP_DESTINATION_BUCKET
  adapter_require_value BACKUP_VERIFY_PRINCIPAL_ID
  adapter_parse_remote "$BACKUP_REMOTE" BACKUP_REMOTE
  adapter_safe_relative_path "$BACKUP_PREFIX" || adapter_fail 'BACKUP_PREFIX is invalid'

  WORK_DIRECTORY="$(mktemp -d -- "$RESTORE_ROOT/work/backup-acquire.XXXXXX")"
  chmod 0700 "$WORK_DIRECTORY"
  local candidate="$WORK_DIRECTORY/set"
  local verify_work="$WORK_DIRECTORY/task5-verify"
  mkdir -m 0700 -- "$candidate" "$verify_work"
  local remote_root="${BACKUP_REMOTE%/}/${BACKUP_PREFIX%/}/sets/$set_id"
  local manifest="$candidate/set-manifest.json"
  adapter_rclone_copyto "$verifier_config" "$remote_root/set-manifest.json" "$manifest" ||
    adapter_fail 'backup verifier could not acquire the set manifest'
  local manifest_sha
  manifest_sha="$(sha256sum -- "$manifest" | awk '{print $1}')"
  adapter_is_sha256 "$manifest_sha" || adapter_fail 'set manifest checksum is invalid'

  # Task5's verifier currently validates its full nightly topology even though
  # set verification only uses the verifier principal.  Supply private, empty
  # placeholders for every unused principal so no production source/uploader
  # credential is introduced into the restore process.
  local unused_source="$WORK_DIRECTORY/unused-source.conf"
  local unused_upload="$WORK_DIRECTORY/unused-upload.conf"
  local unused_prune="$WORK_DIRECTORY/unused-prune.conf"
  : >"$unused_source"; : >"$unused_upload"; : >"$unused_prune"
  chmod 0600 "$unused_source" "$unused_upload" "$unused_prune"
  env \
    BACKUP_RCLONE_COMMAND="$RESTORE_RCLONE_COMMAND" \
    BACKUP_REMOTE="$BACKUP_REMOTE" \
    BACKUP_PREFIX="$BACKUP_PREFIX" \
    BACKUP_DESTINATION_ACCOUNT_ID="$BACKUP_DESTINATION_ACCOUNT_ID" \
    BACKUP_DESTINATION_BUCKET="$BACKUP_DESTINATION_BUCKET" \
    BACKUP_VERIFY_RCLONE_CONFIG="$verifier_config" \
    BACKUP_UPLOAD_RCLONE_CONFIG="$unused_upload" \
    BACKUP_PRUNE_RCLONE_CONFIG="$unused_prune" \
    MEDIA_SOURCE_RCLONE_REMOTE='restore-unused:restore-unused.invalid' \
    MEDIA_SOURCE_RCLONE_CONFIG="$unused_source" \
    MEDIA_SOURCE_ACCOUNT_ID='restore-unused-source-account' \
    MEDIA_SOURCE_BUCKET='restore-unused.invalid' \
    MEDIA_SOURCE_REGION='restore-unused-region' \
    MEDIA_SOURCE_PRINCIPAL_ID='restore-unused-source-principal' \
    BACKUP_UPLOAD_PRINCIPAL_ID='restore-unused-upload-principal' \
    BACKUP_VERIFY_PRINCIPAL_ID="$BACKUP_VERIFY_PRINCIPAL_ID" \
    BACKUP_PRUNE_PRINCIPAL_ID='restore-unused-prune-principal' \
    "$TASK5_VERIFY" set \
      --set-id "$set_id" \
      --expected-manifest-sha "$manifest_sha" \
      --local-manifest "$manifest" \
      --work-directory "$verify_work" >/dev/null ||
    adapter_fail 'tracked Task5 set verification failed'
  env \
    BACKUP_RCLONE_COMMAND="$RESTORE_RCLONE_COMMAND" \
    BACKUP_REMOTE="$BACKUP_REMOTE" BACKUP_PREFIX="$BACKUP_PREFIX" \
    BACKUP_DESTINATION_ACCOUNT_ID="$BACKUP_DESTINATION_ACCOUNT_ID" \
    BACKUP_DESTINATION_BUCKET="$BACKUP_DESTINATION_BUCKET" \
    BACKUP_VERIFY_RCLONE_CONFIG="$verifier_config" \
    BACKUP_UPLOAD_RCLONE_CONFIG="$unused_upload" \
    BACKUP_PRUNE_RCLONE_CONFIG="$unused_prune" \
    MEDIA_SOURCE_RCLONE_REMOTE='restore-unused:restore-unused.invalid' \
    MEDIA_SOURCE_RCLONE_CONFIG="$unused_source" \
    MEDIA_SOURCE_ACCOUNT_ID='restore-unused-source-account' \
    MEDIA_SOURCE_BUCKET='restore-unused.invalid' \
    MEDIA_SOURCE_REGION='restore-unused-region' \
    MEDIA_SOURCE_PRINCIPAL_ID='restore-unused-source-principal' \
    BACKUP_UPLOAD_PRINCIPAL_ID='restore-unused-upload-principal' \
    BACKUP_VERIFY_PRINCIPAL_ID="$BACKUP_VERIFY_PRINCIPAL_ID" \
    BACKUP_PRUNE_PRINCIPAL_ID='restore-unused-prune-principal' \
    "$TASK5_VERIFY" marker \
      --set-id "$set_id" --expected-manifest-sha "$manifest_sha" \
      --work-directory "$verify_work" >/dev/null ||
    adapter_fail 'tracked Task5 VERIFIED marker verification failed'

  local filename
  for filename in database.dump.age local-media.tar.age media-manifest.json.age VERIFIED; do
    adapter_rclone_copyto "$verifier_config" "$remote_root/$filename" "$candidate/$filename" ||
      adapter_fail "backup verifier could not acquire $filename"
  done
  [[ "$(tr -d '\r\n' <"$candidate/VERIFIED")" == "$manifest_sha" ]] ||
    adapter_fail 'downloaded VERIFIED marker changed after Task5 verification'
  mv -- "$candidate" "$output"
  printf '%s\n' "$manifest_sha"
}

main "$@"
