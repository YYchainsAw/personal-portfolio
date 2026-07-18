#!/usr/bin/env bash
set -euo pipefail
set +x
umask 077

SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=deploy/restore/adapters/adapter-lib.sh
source "$SCRIPT_DIRECTORY/adapter-lib.sh"

WORK_DIRECTORY=''
cleanup() {
  [[ -z "$WORK_DIRECTORY" || ! -d "$WORK_DIRECTORY" ]] || rm -rf -- "$WORK_DIRECTORY"
}
trap cleanup EXIT HUP INT TERM

main() {
  (($# >= 1)) || adapter_fail 'report transfer operation is required'
  local operation="$1"; shift
  local drill_id='' remote_prefix='' report='' checksum='' expected_sha=''
  local config=''
  while (($#)); do
    case "$1" in
      --drill-id) drill_id="$2"; shift 2 ;;
      --remote-prefix) remote_prefix="$2"; shift 2 ;;
      --report) report="$2"; shift 2 ;;
      --checksum) checksum="$2"; shift 2 ;;
      --expected-sha) expected_sha="$2"; shift 2 ;;
      --uploader-config|--verifier-config|--config) config="$2"; shift 2 ;;
      *) adapter_fail 'report transfer received an unsupported argument' ;;
    esac
  done
  adapter_require_drill_context
  adapter_require_disposed_identity
  adapter_resolve_command RESTORE_RCLONE_COMMAND rclone
  [[ "$drill_id" == "$RESTORE_DRILL_ID" ]] || adapter_fail 'report drill ID does not match the restore context'
  [[ "$remote_prefix" == "${BACKUP_PREFIX%/}/drill-reports/$RESTORE_DRILL_ID" ]] ||
    adapter_fail 'report remote prefix escaped the drill report namespace'
  adapter_require_value BACKUP_REMOTE
  adapter_parse_remote "$BACKUP_REMOTE" BACKUP_REMOTE
  local remote_root="${BACKUP_REMOTE%/}/$remote_prefix"
  case "$operation" in
    upload)
      adapter_require_private_file "$config" 'report uploader rclone config'
      [[ "$report" == "$RESTORE_ROOT/"* && -f "$report" && ! -L "$report" ]] ||
        adapter_fail 'report file escaped the restore root'
      [[ "$checksum" == "$RESTORE_ROOT/"* && -f "$checksum" && ! -L "$checksum" ]] ||
        adapter_fail 'report checksum escaped the restore root'
      local report_sha checksum_value
      report_sha="$(sha256sum -- "$report" | awk '{print $1}')"
      checksum_value="$(awk 'NR == 1 && NF == 2 && $2 == "report.json" {print $1}' "$checksum")"
      [[ "$(wc -l <"$checksum")" -eq 1 && "$checksum_value" == "$report_sha" ]] ||
        adapter_fail 'report checksum file does not exactly describe report.json'
      jq -e --arg id "$RESTORE_DRILL_ID" '.drillId == $id' "$report" >/dev/null ||
        adapter_fail 'report JSON does not belong to this drill'
      "$RESTORE_RCLONE_COMMAND" --config "$config" copyto --immutable \
        "$report" "$remote_root/report.json" --retries 3 --low-level-retries 10 ||
        adapter_fail 'report upload failed'
      "$RESTORE_RCLONE_COMMAND" --config "$config" copyto --immutable \
        "$checksum" "$remote_root/report.sha256" --retries 3 --low-level-retries 10 ||
        adapter_fail 'report checksum upload failed'
      ;;
    verify)
      adapter_require_private_file "$config" 'report verifier rclone config'
      adapter_is_sha256 "$expected_sha" || adapter_fail 'expected report checksum is invalid'
      local readback
      readback="$(mktemp -d -- "$RESTORE_ROOT/work/report-readback.XXXXXX")"
      chmod 0700 "$readback"
      WORK_DIRECTORY="$readback"
      adapter_rclone_copyto "$config" "$remote_root/report.json" "$readback/report.json" ||
        adapter_fail 'report verifier could not read report.json'
      adapter_rclone_copyto "$config" "$remote_root/report.sha256" "$readback/report.sha256" ||
        adapter_fail 'report verifier could not read report.sha256'
      [[ "$(sha256sum -- "$readback/report.json" | awk '{print $1}')" == "$expected_sha" ]] ||
        adapter_fail 'read-back report bytes do not match the expected checksum'
      [[ "$(wc -l <"$readback/report.sha256")" -eq 1 &&
         "$(awk 'NF == 2 && $2 == "report.json" {print $1}' "$readback/report.sha256")" == "$expected_sha" ]] ||
        adapter_fail 'read-back report checksum file is invalid'
      jq -e --arg id "$RESTORE_DRILL_ID" '.drillId == $id' "$readback/report.json" >/dev/null ||
        adapter_fail 'read-back report belongs to another drill'
      rm -rf -- "$readback"
      WORK_DIRECTORY=''
      ;;
    *) adapter_fail 'report transfer operation must be upload or verify' ;;
  esac
}

main "$@"
