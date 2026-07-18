#!/usr/bin/env bash
set -euo pipefail
set +x
umask 077

SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=deploy/restore/adapters/adapter-lib.sh
source "$SCRIPT_DIRECTORY/adapter-lib.sh"

require_location() {
  adapter_require_value RESTORE_DRILL_COS_ACCOUNT_ID
  adapter_require_value RESTORE_DRILL_COS_BUCKET
  adapter_require_value RESTORE_DRILL_COS_REGION
  adapter_require_value RESTORE_DRILL_COS_REMOTE
  adapter_parse_remote "$RESTORE_DRILL_COS_REMOTE" RESTORE_DRILL_COS_REMOTE
}

main() {
  (($# >= 1)) || adapter_fail 'COS transfer operation is required'
  local operation="$1"; shift
  local blob='' sha='' output='' input='' config=''
  local account='' bucket='' region='' key=''
  while (($#)); do
    case "$1" in
      --blob) blob="$2"; shift 2 ;;
      --sha256) sha="$2"; shift 2 ;;
      --output) output="$2"; shift 2 ;;
      --input) input="$2"; shift 2 ;;
      --verifier-config|--config) config="$2"; shift 2 ;;
      --account) account="$2"; shift 2 ;;
      --bucket) bucket="$2"; shift 2 ;;
      --region) region="$2"; shift 2 ;;
      --key) key="$2"; shift 2 ;;
      *) adapter_fail 'COS transfer received an unsupported argument' ;;
    esac
  done
  adapter_require_drill_context
  adapter_resolve_command RESTORE_RCLONE_COMMAND rclone
  adapter_is_sha256 "$sha" || adapter_fail 'COS object checksum is invalid'
  case "$operation" in
    fetch-backup)
      [[ "$blob" == "blobs/$sha" ]] || adapter_fail 'backup blob path is not immutable and content-addressed'
      [[ "$output" == "$RESTORE_ROOT/work/"* && ! -L "$output" ]] ||
        adapter_fail 'backup blob output escaped the drill work directory'
      config="${config:-${BACKUP_VERIFY_RCLONE_CONFIG:-}}"
      adapter_require_private_file "$config" 'backup verifier rclone config'
      adapter_require_value BACKUP_REMOTE
      adapter_require_value BACKUP_PREFIX
      adapter_parse_remote "$BACKUP_REMOTE" BACKUP_REMOTE
      adapter_safe_relative_path "$BACKUP_PREFIX" || adapter_fail 'BACKUP_PREFIX is invalid'
      adapter_rclone_copyto "$config" \
        "${BACKUP_REMOTE%/}/${BACKUP_PREFIX%/}/$blob" "$output" ||
        adapter_fail 'immutable backup blob download failed'
      ;;
    upload-drill)
      adapter_require_disposed_identity
      require_location
      [[ "$account" == "$RESTORE_DRILL_COS_ACCOUNT_ID" &&
         "$bucket" == "$RESTORE_DRILL_COS_BUCKET" &&
         "$region" == "$RESTORE_DRILL_COS_REGION" ]] ||
        adapter_fail 'drill upload location does not match the reviewed isolated destination'
      [[ "$key" == "drills/$RESTORE_DRILL_ID/blobs/$sha" ]] ||
        adapter_fail 'drill upload key escaped its content-addressed drill prefix'
      [[ "$input" == "$RESTORE_ROOT/work/"* && -f "$input" && ! -L "$input" ]] ||
        adapter_fail 'drill upload input escaped the work directory'
      [[ "$(sha256sum -- "$input" | awk '{print $1}')" == "$sha" ]] ||
        adapter_fail 'drill upload input checksum does not match'
      config="${config:-${RESTORE_DRILL_COS_CONFIG:-}}"
      adapter_require_private_file "$config" 'drill COS rclone config'
      "$RESTORE_RCLONE_COMMAND" --config "$config" copyto --immutable \
        "$input" "${RESTORE_DRILL_COS_REMOTE%/}/$key" \
        --retries 3 --low-level-retries 10 --contimeout 10s --timeout 5m ||
        adapter_fail 'drill COS upload failed'
      ;;
    download-drill)
      adapter_require_disposed_identity
      require_location
      [[ "$account" == "$RESTORE_DRILL_COS_ACCOUNT_ID" &&
         "$bucket" == "$RESTORE_DRILL_COS_BUCKET" &&
         "$region" == "$RESTORE_DRILL_COS_REGION" ]] ||
        adapter_fail 'drill read-back location does not match the isolated destination'
      [[ "$key" == "drills/$RESTORE_DRILL_ID/blobs/$sha" ]] ||
        adapter_fail 'drill read-back key escaped its content-addressed drill prefix'
      [[ "$output" == "$RESTORE_ROOT/"* && ! -L "$output" ]] ||
        adapter_fail 'drill read-back output escaped the restore root'
      config="${config:-${RESTORE_DRILL_COS_CONFIG:-}}"
      adapter_require_private_file "$config" 'drill COS rclone config'
      adapter_rclone_copyto "$config" "${RESTORE_DRILL_COS_REMOTE%/}/$key" "$output" ||
        adapter_fail 'drill COS read-back failed'
      ;;
    *) adapter_fail 'COS transfer operation must be fetch-backup, upload-drill, or download-drill' ;;
  esac
}

main "$@"
