#!/usr/bin/env bash
# shellcheck disable=SC2016
set -euo pipefail
set +x
umask 077

SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
readonly SCRIPT_DIRECTORY
readonly SQL_FILE="$SCRIPT_DIRECTORY/record-drill-result.sql"

fail() {
  printf 'Restore drill record failed: %s\n' "$1" >&2
  exit 1
}

require_protected_file() {
  local path="$1"
  local label="$2"
  [[ "$path" == /* && ! -L "$path" && -f "$path" ]] ||
    fail "$label is missing, non-absolute, or linked"
  local mode owner
  mode="$(stat -Lc '%a' -- "$path")" || fail "$label metadata is unavailable"
  owner="$(stat -Lc '%u' -- "$path")" || fail "$label owner is unavailable"
  (((8#$mode & 0022) == 0)) || fail "$label is writable by group or other"
  ((owner == EUID)) || fail "$label is not owned by the invoking operator"
}

valid_timestamp() {
  local value="$1"
  [[ "$value" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{1,9})?Z$ ]] ||
    return 1
  date -u -d "$value" +%s >/dev/null 2>&1
}

resolve_production_compose() {
  # Direct path overrides exist only for the hermetic contract fixture.  In
  # production the active Compose file is derived from the atomic
  # current-release marker; there is intentionally no /opt/portfolio/current
  # symlink to follow.
  if [[ -n "${RESTORE_PRODUCTION_COMPOSE_FILE:-}" ]]; then
    [[ "${RESTORE_RECORD_CONTRACT_FIXTURE:-false}" == true && -f /.dockerenv ]] ||
      fail 'production Compose path override is restricted to the isolated contract fixture'
    printf '%s\n' "$RESTORE_PRODUCTION_COMPOSE_FILE"
    return
  fi
  local portfolio_root='/opt/portfolio'
  [[ -d "$portfolio_root" && ! -L "$portfolio_root" &&
     "$(realpath -e -- "$portfolio_root")" == "$portfolio_root" ]] ||
    fail 'production portfolio root is missing, linked, or non-canonical'
  local marker="$portfolio_root/current-release"
  require_protected_file "$marker" production-current-release
  [[ "$(wc -l <"$marker")" -eq 1 ]] || fail 'production current-release marker is malformed'
  local release_id
  release_id="$(tr -d '\r\n' <"$marker")"
  [[ "$release_id" =~ ^[0-9a-f]{12}-[0-9a-f]{12}$ ]] ||
    fail 'production current-release marker has an invalid release ID'
  local release_root="$portfolio_root/releases/$release_id"
  [[ -d "$release_root" && ! -L "$release_root" &&
     "$(realpath -e -- "$release_root")" == "$release_root" ]] ||
    fail 'production current release directory is missing, linked, or escaped'
  local metadata="$release_root/release.json"
  require_protected_file "$metadata" production-release-metadata
  jq -e --arg id "$release_id" '.releaseId == $id' "$metadata" >/dev/null ||
    fail 'production release metadata disagrees with current-release'
  local compose_file="$release_root/ops/deploy/docker-compose.prod.yml"
  require_protected_file "$compose_file" production-Compose
  printf '%s\n' "$compose_file"
}

main() {
  local compose_name
  while IFS='=' read -r compose_name _; do
    [[ "$compose_name" == COMPOSE_* ]] || continue
    unset "$compose_name"
  done < <(env)
  local drill_id='' status='' started_at='' finished_at='' report_sha='' category=''
  while (($#)); do
    case "$1" in
      --drill-id) [[ -z "$drill_id" && $# -ge 2 ]] || fail 'duplicate or missing --drill-id'; drill_id="$2"; shift 2 ;;
      --status) [[ -z "$status" && $# -ge 2 ]] || fail 'duplicate or missing --status'; status="$2"; shift 2 ;;
      --started-at) [[ -z "$started_at" && $# -ge 2 ]] || fail 'duplicate or missing --started-at'; started_at="$2"; shift 2 ;;
      --finished-at) [[ -z "$finished_at" && $# -ge 2 ]] || fail 'duplicate or missing --finished-at'; finished_at="$2"; shift 2 ;;
      --report-sha) [[ -z "$report_sha" && $# -ge 2 ]] || fail 'duplicate or missing --report-sha'; report_sha="$2"; shift 2 ;;
      --category) [[ -z "$category" && $# -ge 2 ]] || fail 'duplicate or missing --category'; category="$2"; shift 2 ;;
      *) fail 'only drill ID, status, timestamps, report checksum, and category are accepted' ;;
    esac
  done

  [[ "$drill_id" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$ ]] ||
    fail 'drill ID is not a canonical UUID'
  [[ "$status" == SUCCEEDED || "$status" == FAILED ]] ||
    fail 'record status must be SUCCEEDED or FAILED'
  valid_timestamp "$started_at" || fail 'started timestamp is not canonical RFC-3339 UTC'
  valid_timestamp "$finished_at" || fail 'finished timestamp is not canonical RFC-3339 UTC'
  local started_epoch finished_epoch
  started_epoch="$(date -u -d "$started_at" +%s)"
  finished_epoch="$(date -u -d "$finished_at" +%s)"
  ((finished_epoch >= started_epoch)) || fail 'finished timestamp precedes started timestamp'
  [[ "$report_sha" =~ ^[0-9a-f]{64}$ ]] || fail 'report checksum must be lowercase SHA-256'
  case "$category" in
    NONE|RPO_EXCEEDED|RTO_EXCEEDED|BACKUP_VERIFICATION_FAILED|RELEASE_VERIFICATION_FAILED|DATABASE_RESTORE_FAILED|MEDIA_CLOSURE_FAILED|LOCAL_MEDIA_FAILED|COS_MEDIA_FAILED|ROUTE_VERIFICATION_FAILED|HISTORICAL_RESTORE_FAILED|REMOTE_REPORT_FAILED|PRODUCTION_RECORD_FAILED|INTERRUPTED|INTERNAL_FAILURE) ;;
    *) fail 'record category is not allowlisted' ;;
  esac
  if [[ "$status" == SUCCEEDED && "$category" != NONE ]]; then
    fail 'a successful drill must use category NONE'
  fi
  if [[ "$status" == FAILED && "$category" == NONE ]]; then
    fail 'a failed drill must use a redacted failure category'
  fi

  local compose_file
  compose_file="$(resolve_production_compose)"
  local release_env="${RESTORE_PRODUCTION_RELEASE_ENV:-/etc/portfolio/release.env}"
  if [[ "$release_env" != /etc/portfolio/release.env ]]; then
    [[ "${RESTORE_RECORD_CONTRACT_FIXTURE:-false}" == true && -f /.dockerenv ]] ||
      fail 'production release env override is restricted to the isolated contract fixture'
  fi
  require_protected_file "$compose_file" production-Compose
  require_protected_file "$release_env" production-release-env
  [[ -f "$SQL_FILE" ]] || fail 'static record SQL is missing'

  local docker_command="${RESTORE_DOCKER_COMMAND:-docker}"
  command -v "$docker_command" >/dev/null 2>&1 || [[ -x "$docker_command" ]] ||
    fail 'Docker command is unavailable'

  if ! "$docker_command" compose \
      --project-name portfolio \
      --env-file "$release_env" \
      -f "$compose_file" \
      exec -T postgres sh -eu -c \
      'exec psql -X --no-psqlrc -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" "$@"' \
      portfolio-restore-record \
      --set=run_type=RESTORE_DRILL \
      --set="drill_id=$drill_id" \
      --set="status=$status" \
      --set="started_at=$started_at" \
      --set="finished_at=$finished_at" \
      --set="report_sha=$report_sha" \
      --set="error_category=$category" \
      -f - <"$SQL_FILE"; then
    fail 'idempotent production RESTORE_DRILL write failed'
  fi

  printf '%s\n' 'Production RESTORE_DRILL record matches the independently verified report'
}

main "$@"
