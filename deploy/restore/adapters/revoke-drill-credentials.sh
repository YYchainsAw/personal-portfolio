#!/usr/bin/env bash
set -euo pipefail
set +x
umask 077

SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=deploy/restore/adapters/adapter-lib.sh
source "$SCRIPT_DIRECTORY/adapter-lib.sh"

main() {
  local drill_id='' config=''
  while (($#)); do
    case "$1" in
      --drill-id) drill_id="$2"; shift 2 ;;
      --config) config="$2"; shift 2 ;;
      *) adapter_fail 'credential revocation received an unsupported argument' ;;
    esac
  done
  adapter_require_disposed_identity
  adapter_require_value RESTORE_DRILL_ID
  adapter_is_uuid "$RESTORE_DRILL_ID" || adapter_fail 'RESTORE_DRILL_ID is invalid'
  [[ "${RESTORE_COMPOSE_PROJECT_NAME:-}" == "portfolio-restore-$RESTORE_DRILL_ID" ]] ||
    adapter_fail 'Compose project is not exactly bound to the drill UUID'
  [[ "$drill_id" == "$RESTORE_DRILL_ID" ]] || adapter_fail 'credential drill ID does not match the active drill'
  adapter_require_value RESTORE_SECRETS_DIRECTORY
  [[ "$RESTORE_SECRETS_DIRECTORY" == "/run/portfolio-restore-secrets/$RESTORE_DRILL_ID" ]] ||
    adapter_fail 'credential directory is not exactly bound to the drill UUID'
  adapter_require_private_directory "$RESTORE_SECRETS_DIRECTORY" 'drill secret directory'
  [[ -f "$RESTORE_SECRETS_DIRECTORY/.portfolio-restore-secrets" &&
     "$(<"$RESTORE_SECRETS_DIRECTORY/.portfolio-restore-secrets")" == "$RESTORE_DRILL_ID" ]] ||
    adapter_fail 'drill secret directory sentinel is invalid'
  [[ "$config" == "$RESTORE_SECRETS_DIRECTORY/"* && "$config" == "${RESTORE_DRILL_COS_CONFIG:-}" ]] ||
    adapter_fail 'credential revocation config escaped the drill secret directory'
  adapter_require_value RESTORE_DRILL_CREDENTIAL_EXPIRES_AT
  local expiry now
  expiry="$(date -u -d "$RESTORE_DRILL_CREDENTIAL_EXPIRES_AT" +%s 2>/dev/null || true)"
  now="$(date -u +%s)"
  [[ "$expiry" =~ ^[0-9]+$ && "$expiry" -ge $((now - 14400)) && "$expiry" -le $((now + 3600)) ]] ||
    adapter_fail 'drill credential expiry is outside the bounded restore window'
  if find "$RESTORE_SECRETS_DIRECTORY" -mindepth 1 \! -type f -print -quit | grep -q .; then
    adapter_fail 'drill secret directory contains a link, directory, or special entry'
  fi
  local secret
  while IFS= read -r -d '' secret; do
    chmod 0600 "$secret"
    : >"$secret"
    rm -f -- "$secret"
  done < <(find "$RESTORE_SECRETS_DIRECTORY" -mindepth 1 -maxdepth 1 -type f -print0)
  rmdir -- "$RESTORE_SECRETS_DIRECTORY" || adapter_fail 'drill secret directory could not be destroyed'
  [[ ! -e "$RESTORE_SECRETS_DIRECTORY" ]] || adapter_fail 'drill secrets remain after local credential revocation'
  # Tencent temporary STS credentials are non-renewable and not individually
  # revocable.  Bounded one-hour expiry plus destruction of every local copy is
  # the fail-closed revocation mechanism used by this drill.
}

main "$@"
