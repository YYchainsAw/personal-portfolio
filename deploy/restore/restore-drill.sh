#!/usr/bin/env bash
# shellcheck disable=SC2016
set -euo pipefail
set +x
umask 077

SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
readonly SCRIPT_DIRECTORY
readonly RESTORE_COMPOSE_YAML="$SCRIPT_DIRECTORY/docker-compose.restore.yml"
readonly CLOSURE_SQL="$SCRIPT_DIRECTORY/resolve-media-closure.sql"
readonly MEDIA_VERIFY_SCRIPT="$SCRIPT_DIRECTORY/verify-restored-media.sh"
readonly DEFAULT_RECORD_COMMAND="$SCRIPT_DIRECTORY/record-drill-result.sh"
readonly ADAPTER_DIRECTORY="$SCRIPT_DIRECTORY/adapters"
readonly DEFAULT_BACKUP_VERIFY_COMMAND="$ADAPTER_DIRECTORY/acquire-backup-set.sh"
readonly DEFAULT_RELEASE_VERIFY_COMMAND="$ADAPTER_DIRECTORY/verify-release-bundle.sh"
readonly DEFAULT_COS_TRANSFER_COMMAND="$ADAPTER_DIRECTORY/cos-transfer.sh"
readonly DEFAULT_APPLY_MEDIA_MAPPING_COMMAND="$ADAPTER_DIRECTORY/apply-media-mapping.sh"
readonly DEFAULT_ROUTE_START_COMMAND="$ADAPTER_DIRECTORY/start-routes.sh"
readonly DEFAULT_HTTP_FETCH_COMMAND="$ADAPTER_DIRECTORY/http-fetch.sh"
readonly DEFAULT_CONTENT_VERIFY_COMMAND="$ADAPTER_DIRECTORY/verify-content-flow.sh"
readonly DEFAULT_REPORT_COMMAND="$ADAPTER_DIRECTORY/report-transfer.sh"
readonly DEFAULT_REVOKE_COMMAND="$ADAPTER_DIRECTORY/revoke-drill-credentials.sh"
readonly DEFAULT_DRILL_CLEANUP_COMMAND="$ADAPTER_DIRECTORY/cleanup-drill.sh"
DEFAULT_MEDIA_TAR_VALIDATOR="$(realpath -e -- "$SCRIPT_DIRECTORY/../backup/validate-media-tar.py")"
readonly DEFAULT_MEDIA_TAR_VALIDATOR
readonly RESTORE_BASE='/srv/portfolio-restore'
readonly MAX_RPO_SECONDS=86400
readonly MAX_RTO_SECONDS=14400
readonly MIN_CREDENTIAL_LIFETIME_SECONDS=900
readonly CREDENTIAL_CLEANUP_MARGIN_SECONDS=300

ROOT=''
DRILL_ID=''
SET_ID=''
IDENTITY_FD=''
IDENTITY_FILE=''
IDENTITY_REFERENCE=''
IDENTITY_DISPOSED=false
RELEASE_ID=''
SET_MANIFEST_SHA=''
STARTED_AT=''
FINISHED_AT=''
START_WALL_EPOCH=0
START_MONOTONIC=0
RPO_SECONDS=0
RTO_SECONDS=0
CREDENTIAL_SUCCESS_DEADLINE_EPOCH=0
ERROR_CATEGORY='INTERNAL_FAILURE'
DRILL_CREDENTIALS_ACTIVE=false
REMOTE_REPORT_VERIFIED=false
PUBLISHING_FAILURE=false

DOCKER_COMMAND=''
AGE_COMMAND=''
BACKUP_VERIFY_COMMAND=''
RELEASE_VERIFY_COMMAND=''
MEDIA_TAR_VALIDATOR=''
COS_TRANSFER_COMMAND=''
APPLY_MEDIA_MAPPING_COMMAND=''
ROUTE_START_COMMAND=''
HTTP_FETCH_COMMAND=''
CONTENT_VERIFY_COMMAND=''
REPORT_UPLOAD_COMMAND=''
REPORT_VERIFY_COMMAND=''
RECORD_COMMAND=''
REVOKE_COMMAND="$DEFAULT_REVOKE_COMMAND"
DRILL_CLEANUP_COMMAND="$DEFAULT_DRILL_CLEANUP_COMMAND"

SET_DIRECTORY=''
WORK_DIRECTORY=''
LOCAL_MEDIA_ROOT=''
REPORT_FILE=''
REPORT_CHECKSUM_FILE=''
COUNTS_FILE=''
SECRETS_DIRECTORY=''

declare -a HISTORICAL_REVISIONS=()

die() {
  ERROR_CATEGORY="$1"
  printf 'Restore drill failed: %s\n' "$2" >&2
  exit 1
}

valid_error_category() {
  case "$1" in
    NONE|RPO_EXCEEDED|RTO_EXCEEDED|BACKUP_VERIFICATION_FAILED|RELEASE_VERIFICATION_FAILED|DATABASE_RESTORE_FAILED|MEDIA_CLOSURE_FAILED|LOCAL_MEDIA_FAILED|COS_MEDIA_FAILED|ROUTE_VERIFICATION_FAILED|HISTORICAL_RESTORE_FAILED|REMOTE_REPORT_FAILED|PRODUCTION_RECORD_FAILED|INTERRUPTED|INTERNAL_FAILURE) return 0 ;;
    *) return 1 ;;
  esac
}

utc_now() {
  date -u '+%Y-%m-%dT%H:%M:%SZ'
}

monotonic_seconds() {
  local uptime
  IFS=' ' read -r uptime _ </proc/uptime
  printf '%s\n' "${uptime%%.*}"
}

is_canonical_uuid() {
  [[ "$1" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$ ]]
}

is_safe_relative_file() {
  [[ "$1" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ ]]
}

require_protected_file() {
  local path="$1"
  local label="$2"
  [[ "$path" == /* && ! -L "$path" && -f "$path" ]] ||
    die INTERNAL_FAILURE "$label is missing, non-absolute, or linked"
  local mode owner
  mode="$(stat -Lc '%a' -- "$path")" || die INTERNAL_FAILURE "$label metadata is unavailable"
  owner="$(stat -Lc '%u' -- "$path")" || die INTERNAL_FAILURE "$label owner is unavailable"
  (((8#$mode & 0077) == 0)) || die INTERNAL_FAILURE "$label must be root-only"
  ((owner == EUID)) || die INTERNAL_FAILURE "$label must be owned by the drill operator"
}

require_protected_directory() {
  local path="$1"
  local label="$2"
  [[ "$path" == /* && ! -L "$path" && -d "$path" ]] ||
    die INTERNAL_FAILURE "$label is missing, non-absolute, or linked"
  local mode owner
  mode="$(stat -Lc '%a' -- "$path")" || die INTERNAL_FAILURE "$label metadata is unavailable"
  owner="$(stat -Lc '%u' -- "$path")" || die INTERNAL_FAILURE "$label owner is unavailable"
  (((8#$mode & 0077) == 0)) || die INTERNAL_FAILURE "$label must be owner-only"
  ((owner == EUID)) || die INTERNAL_FAILURE "$label must be owned by the drill operator"
}

require_exact_drill_secret_path() {
  local path="$1"
  local filename="$2"
  local label="$3"
  [[ "$path" == "$SECRETS_DIRECTORY/$filename" ]] ||
    die INTERNAL_FAILURE "$label must be $SECRETS_DIRECTORY/$filename"
  require_protected_file "$path" "$label"
}

env_file_value() {
  local file="$1"
  local key="$2"
  awk -F= -v key="$key" '
    $0 ~ /^[[:space:]]*#/ || $0 ~ /^[[:space:]]*$/ {next}
    $1 == key {count += 1; sub(/^[^=]*=/, ""); value = $0}
    END {if (count != 1) exit 1; print value}
  ' "$file"
}

validate_compose_env_file_shape() {
  local file="$1"
  awk '
    /^[[:space:]]*($|#)/ {next}
    /^[A-Z][A-Z0-9_]*=[^\r\n]*$/ {next}
    {exit 1}
  ' "$file" || die INTERNAL_FAILURE 'restore env file contains an unsupported or malformed assignment'
  local duplicates
  duplicates="$(awk -F= '/^[A-Z][A-Z0-9_]*=/{print $1}' "$file" | sort | uniq -d)"
  [[ -z "$duplicates" ]] || die INTERNAL_FAILURE 'restore env file contains duplicate keys'
}

require_exact_env_keys() {
  local file="$1" label="$2"
  shift 2
  local actual expected
  actual="$(awk -F= '/^[A-Z][A-Z0-9_]*=/{print $1}' "$file" | sort)"
  expected="$(printf '%s\n' "$@" | sort)"
  [[ "$actual" == "$expected" ]] ||
    die INTERNAL_FAILURE "$label must contain exactly the reviewed key allowlist"
}

require_base64_secret_shape() {
  local file="$1" key="$2" value
  value="$(env_file_value "$file" "$key")" ||
    die INTERNAL_FAILURE "$key must occur exactly once in the restore application env"
  [[ "$value" =~ ^[A-Za-z0-9+/]{43,}={0,2}$ &&
     $(( ${#value} % 4 )) -eq 0 ]] ||
    die INTERNAL_FAILURE "$key must be a canonical Base64 secret of at least 32 bytes"
  local decoded_size canonical
  decoded_size="$(printf '%s' "$value" | base64 --decode 2>/dev/null | wc -c)" ||
    die INTERNAL_FAILURE "$key must be a canonical Base64 secret of at least 32 bytes"
  canonical="$(printf '%s' "$value" | base64 --decode 2>/dev/null | base64 -w0)" ||
    die INTERNAL_FAILURE "$key must be a canonical Base64 secret of at least 32 bytes"
  [[ "$decoded_size" =~ ^[0-9]+$ && "$decoded_size" -ge 32 && "$canonical" == "$value" ]] ||
    die INTERNAL_FAILURE "$key must be a canonical Base64 secret of at least 32 bytes"
}

sanitize_compose_environment() {
  local name
  while IFS='=' read -r name _; do
    [[ "$name" == COMPOSE_* ]] || continue
    unset "$name"
  done < <(env)
}

sanitize_proxy_environment() {
  local name
  for name in HTTP_PROXY HTTPS_PROXY ALL_PROXY NO_PROXY http_proxy https_proxy all_proxy no_proxy; do
    unset "$name"
  done
}

reject_rclone_environment() {
  local name
  while IFS='=' read -r name _; do
    [[ "$name" == RCLONE_* ]] || continue
    die INTERNAL_FAILURE 'caller-supplied RCLONE_* environment is forbidden'
  done < <(env)
}

require_credential_deadline() {
  local phase="$1" now
  ((CREDENTIAL_SUCCESS_DEADLINE_EPOCH > 0)) ||
    die INTERNAL_FAILURE "credential safety deadline is unavailable before $phase"
  now="$(date -u +%s)"
  ((now < CREDENTIAL_SUCCESS_DEADLINE_EPOCH)) ||
    die INTERNAL_FAILURE "credential safety deadline expired before $phase"
}

resolve_command() {
  local configured="$1"
  local label="$2"
  local resolved=''
  [[ -n "$configured" ]] || die INTERNAL_FAILURE "$label is required"
  if [[ "$configured" == */* ]]; then
    [[ "$configured" == /* && ! -L "$configured" && -f "$configured" && -x "$configured" ]] ||
      die INTERNAL_FAILURE "$label must be an absolute executable regular file"
    resolved="$(realpath -e -- "$configured")" || die INTERNAL_FAILURE "$label cannot be resolved"
    [[ "$resolved" == "$configured" ]] || die INTERNAL_FAILURE "$label must not traverse a symbolic link"
  else
    resolved="$(command -v -- "$configured" 2>/dev/null || true)"
    [[ "$resolved" == /* && -x "$resolved" ]] || die INTERNAL_FAILURE "$label is unavailable"
  fi
  printf '%s\n' "$resolved"
}

validate_root_syntax() {
  [[ "$ROOT" == /* ]] || die INTERNAL_FAILURE 'restore root must be beneath /srv/portfolio-restore/'
  local resolved
  resolved="$(realpath -m -- "$ROOT" 2>/dev/null || true)"
  [[ "$resolved" == "$RESTORE_BASE/"* && "$resolved" != "$RESTORE_BASE" ]] ||
    die INTERNAL_FAILURE 'restore root must be beneath /srv/portfolio-restore/'
  case "$resolved" in
    /|/opt/portfolio|/opt/portfolio/*|/var/lib/docker|/var/lib/docker/*|/var/lib/portfolio|/var/lib/portfolio/*)
      die INTERNAL_FAILURE 'restore root must be beneath /srv/portfolio-restore/' ;;
  esac
  ROOT="$resolved"
}

validate_identity() {
  [[ "$SECRETS_DIRECTORY" == "/run/portfolio-restore-secrets/$DRILL_ID" ]] ||
    die INTERNAL_FAILURE 'drill secret directory is not bound to the drill UUID'
  require_protected_directory "$SECRETS_DIRECTORY" 'drill secret directory'
  [[ -f "$SECRETS_DIRECTORY/.portfolio-restore-secrets" &&
     ! -L "$SECRETS_DIRECTORY/.portfolio-restore-secrets" &&
     "$(<"$SECRETS_DIRECTORY/.portfolio-restore-secrets")" == "$DRILL_ID" ]] ||
    die INTERNAL_FAILURE 'drill secret directory sentinel is invalid'
  local secret_fs
  secret_fs="$(findmnt -T "$SECRETS_DIRECTORY" -n -o FSTYPE 2>/dev/null || true)"
  [[ "$secret_fs" == tmpfs ]] || die INTERNAL_FAILURE 'drill secret directory must be backed by tmpfs'
  if [[ -n "$IDENTITY_FD" && -n "$IDENTITY_FILE" ]]; then
    die INTERNAL_FAILURE 'offline age identity must be supplied by protected file descriptor or root-only tmpfs file'
  fi
  if [[ -n "$IDENTITY_FD" ]]; then
    [[ "$IDENTITY_FD" =~ ^[3-9][0-9]*$ ]] ||
      die INTERNAL_FAILURE 'offline age identity must be supplied by protected file descriptor or root-only tmpfs file (invalid descriptor number)'
    [[ -r "/proc/self/fd/$IDENTITY_FD" && -f "/proc/self/fd/$IDENTITY_FD" ]] ||
      die INTERNAL_FAILURE 'offline age identity must be supplied by protected file descriptor or root-only tmpfs file (descriptor is unavailable)'
    local descriptor_mode descriptor_owner
    descriptor_mode="$(stat -Lc '%a' -- "/proc/self/fd/$IDENTITY_FD")" ||
      die INTERNAL_FAILURE 'offline age identity must be supplied by protected file descriptor or root-only tmpfs file'
    descriptor_owner="$(stat -Lc '%u' -- "/proc/self/fd/$IDENTITY_FD")" ||
      die INTERNAL_FAILURE 'offline age identity must be supplied by protected file descriptor or root-only tmpfs file'
    ((((8#$descriptor_mode & 0077) == 0) && descriptor_owner == EUID)) ||
      die INTERNAL_FAILURE 'offline age identity must be supplied by protected file descriptor or root-only tmpfs file (descriptor permissions are invalid)'
    local supplied_fd="$IDENTITY_FD"
    IDENTITY_FILE="$SECRETS_DIRECTORY/age-identity"
    IDENTITY_REFERENCE="$IDENTITY_FILE"
    [[ ! -e "$IDENTITY_FILE" && ! -L "$IDENTITY_FILE" ]] ||
      die INTERNAL_FAILURE 'drill age identity target already exists'
    if ! dd if="/proc/self/fd/$IDENTITY_FD" of="$IDENTITY_FILE" bs=64K status=none; then
      { exec {IDENTITY_FD}<&-; } 2>/dev/null || true
      IDENTITY_FD=''
      die INTERNAL_FAILURE 'offline age identity could not be copied into drill tmpfs'
    fi
    if ! chmod 0600 "$IDENTITY_FILE"; then
      { exec {IDENTITY_FD}<&-; } 2>/dev/null || true
      IDENTITY_FD=''
      die INTERNAL_FAILURE 'offline age identity could not be protected in drill tmpfs'
    fi
    # The caller-owned descriptor is closed before any network, Docker, or
    # verifier adapter can inherit it.  Only the unexported tmpfs path remains.
    exec {IDENTITY_FD}<&-
    IDENTITY_FD=''
    [[ ! -e "/proc/self/fd/$supplied_fd" ]] ||
      die INTERNAL_FAILURE 'offline age identity descriptor remained open'
  fi
  if [[ -n "$IDENTITY_FILE" ]]; then
    [[ "$IDENTITY_FILE" == "$SECRETS_DIRECTORY/age-identity" ]] ||
      die INTERNAL_FAILURE 'offline age identity must be supplied by protected file descriptor or root-only tmpfs file'
    require_protected_file "$IDENTITY_FILE" 'offline age identity file'
    local resolved fs_type mode
    resolved="$(realpath -e -- "$IDENTITY_FILE")" ||
      die INTERNAL_FAILURE 'offline age identity must be supplied by protected file descriptor or root-only tmpfs file'
    [[ "$resolved" == "$SECRETS_DIRECTORY/age-identity" ]] ||
      die INTERNAL_FAILURE 'offline age identity must be supplied by protected file descriptor or root-only tmpfs file'
    fs_type="$(findmnt -T "$resolved" -n -o FSTYPE 2>/dev/null || true)"
    mode="$(stat -Lc '%a' -- "$resolved")"
    [[ "$fs_type" == tmpfs && "$mode" == 600 ]] ||
      die INTERNAL_FAILURE 'offline age identity must be supplied by protected file descriptor or root-only tmpfs file'
    IDENTITY_REFERENCE="$resolved"
    return
  fi
  die INTERNAL_FAILURE 'offline age identity must be supplied by protected file descriptor or root-only tmpfs file'
}

dispose_identity() {
  [[ "$IDENTITY_DISPOSED" == false ]] || return 0
  if [[ -n "$IDENTITY_FD" && "$IDENTITY_FD" =~ ^[3-9][0-9]*$ ]]; then
    { exec {IDENTITY_FD}<&-; } 2>/dev/null || true
  fi
  local candidate
  for candidate in "$IDENTITY_REFERENCE" "$IDENTITY_FILE"; do
    if [[ -n "$candidate" && "$candidate" == "$SECRETS_DIRECTORY/age-identity" &&
          -f "$candidate" && ! -L "$candidate" ]]; then
      chmod 0600 "$candidate" 2>/dev/null || true
      : >"$candidate" 2>/dev/null || true
      rm -f -- "$candidate" 2>/dev/null || true
    fi
  done
  IDENTITY_REFERENCE=''
  IDENTITY_FILE=''
  IDENTITY_FD=''
  IDENTITY_DISPOSED=true
  export RESTORE_IDENTITY_DISPOSED=true
}

validate_arguments_and_environment() {
  [[ "${RESTORE_ENV:-}" == isolated ]] || die INTERNAL_FAILURE 'RESTORE_ENV=isolated is required'
  if [[ -n "${RESTORE_AGE_IDENTITY:-}" || -n "${AGE_IDENTITY:-}" || -n "${AGE_IDENTITIES:-}" ||
        -n "${AGE_SECRET_KEY:-}" || -n "${AGE_KEY:-}" ]]; then
    die INTERNAL_FAILURE 'age identity values in arguments or environment are forbidden'
  fi

  while (($#)); do
    case "$1" in
      --root) [[ $# -ge 2 && -z "$ROOT" ]] || die INTERNAL_FAILURE 'invalid or duplicate --root'; ROOT="$2"; shift 2 ;;
      --drill-id) [[ $# -ge 2 && -z "$DRILL_ID" ]] || die INTERNAL_FAILURE 'invalid or duplicate --drill-id'; DRILL_ID="$2"; shift 2 ;;
      --set-id) [[ $# -ge 2 && -z "$SET_ID" ]] || die INTERNAL_FAILURE 'invalid or duplicate --set-id'; SET_ID="$2"; shift 2 ;;
      --historical-revision) [[ $# -ge 2 ]] || die INTERNAL_FAILURE 'missing --historical-revision value'; HISTORICAL_REVISIONS+=("$2"); shift 2 ;;
      --age-identity-fd) [[ $# -ge 2 && -z "$IDENTITY_FD" ]] || die INTERNAL_FAILURE 'invalid or duplicate --age-identity-fd'; IDENTITY_FD="$2"; shift 2 ;;
      --age-identity-file) [[ $# -ge 2 && -z "$IDENTITY_FILE" ]] || die INTERNAL_FAILURE 'invalid or duplicate --age-identity-file'; IDENTITY_FILE="$2"; shift 2 ;;
      --age-identity|--identity|-i) die INTERNAL_FAILURE 'age identity values in arguments or environment are forbidden' ;;
      *) die INTERNAL_FAILURE 'usage: restore-drill.sh --root DIR --drill-id UUID --set-id ID --historical-revision UUID [--historical-revision UUID ...] (--age-identity-fd FD | --age-identity-file FILE)' ;;
    esac
  done

  [[ -n "$ROOT" ]] || die INTERNAL_FAILURE 'restore root must be beneath /srv/portfolio-restore/'
  validate_root_syntax

  local api_port="${RESTORE_API_PORT:-28080}"
  local nginx_port="${RESTORE_NGINX_PORT:-28443}"
  if [[ ! "$api_port" =~ ^[1-9][0-9]{0,4}$ ]] || ((api_port > 65535 || api_port == 18080)); then
    die INTERNAL_FAILURE 'restore API port must be loopback-only and differ from 18080'
  fi
  if [[ ! "$nginx_port" =~ ^[1-9][0-9]{0,4}$ ]] || ((nginx_port > 65535 || nginx_port == api_port)); then
    die INTERNAL_FAILURE 'restore Nginx port must be loopback-only and distinct'
  fi
  export RESTORE_API_PORT="$api_port" RESTORE_NGINX_PORT="$nginx_port"

  is_canonical_uuid "$DRILL_ID" || die INTERNAL_FAILURE 'drill ID must be a canonical UUID'
  local expected_project="portfolio-restore-$DRILL_ID"
  [[ -z "${RESTORE_COMPOSE_PROJECT_NAME:-}" || "$RESTORE_COMPOSE_PROJECT_NAME" == "$expected_project" ]] ||
    die INTERNAL_FAILURE 'restore Compose project must exactly equal portfolio-restore-<drill UUID>'
  export RESTORE_COMPOSE_PROJECT_NAME="$expected_project"
  export RESTORE_DRILL_ID="$DRILL_ID"
  [[ "$SET_ID" =~ ^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{12}$ ]] || die INTERNAL_FAILURE 'backup set ID is invalid'
  ((${#HISTORICAL_REVISIONS[@]} > 0)) || die INTERNAL_FAILURE 'at least one historical revision is required'
  local revision
  declare -A seen_revisions=()
  for revision in "${HISTORICAL_REVISIONS[@]}"; do
    is_canonical_uuid "$revision" || die INTERNAL_FAILURE 'historical revision ID is invalid'
    [[ -z "${seen_revisions[$revision]:-}" ]] || die INTERNAL_FAILURE 'historical revision ID is duplicated'
    seen_revisions["$revision"]=1
  done
  SECRETS_DIRECTORY="/run/portfolio-restore-secrets/$DRILL_ID"
  export RESTORE_SECRETS_DIRECTORY="$SECRETS_DIRECTORY"
  DRILL_CREDENTIALS_ACTIVE=true
  reject_rclone_environment
  validate_identity
}

validate_isolated_env_files() {
  validate_compose_env_file_shape "$RESTORE_APP_ENV"
  validate_compose_env_file_shape "$RESTORE_POSTGRES_ENV"
  require_exact_env_keys "$RESTORE_APP_ENV" 'restore application env' \
    COS_BUCKET COS_REGION COS_SECRET_ID COS_SECRET_KEY COS_SESSION_TOKEN \
    PORTFOLIO_ANALYTICS_HMAC_SECRET \
    PORTFOLIO_ANALYTICS_MAINTENANCE_SCHEDULING_ENABLED \
    PORTFOLIO_CONTACT_DEDUPE_SECRET \
    PORTFOLIO_COS_ENABLED \
    PORTFOLIO_DB_MIGRATOR_PASSWORD PORTFOLIO_DB_MIGRATOR_URL \
    PORTFOLIO_DB_MIGRATOR_USER PORTFOLIO_DB_RUNTIME_PASSWORD \
    PORTFOLIO_DB_RUNTIME_USER PORTFOLIO_DB_URL \
    PORTFOLIO_EMAIL_ENABLED PORTFOLIO_JOBS_RETENTION_ENABLED \
    PORTFOLIO_JOBS_WORKER_ENABLED PORTFOLIO_MEDIA_CLEANUP_ENABLED \
    PORTFOLIO_PREVIEW_HMAC_KEY PORTFOLIO_RELEASE_ID \
    PORTFOLIO_STAGING_CLEANUP_ENABLED PORTFOLIO_STORAGE_DEFAULT_PROVIDER \
    PORTFOLIO_TOTP_ACTIVE_KEY_VERSION PORTFOLIO_TOTP_KEY_RING
  require_exact_env_keys "$RESTORE_POSTGRES_ENV" 'restore PostgreSQL env' \
    PORTFOLIO_DB_MIGRATOR_PASSWORD PORTFOLIO_DB_MIGRATOR_USER \
    PORTFOLIO_DB_RUNTIME_PASSWORD PORTFOLIO_DB_RUNTIME_USER \
    POSTGRES_DB POSTGRES_PASSWORD POSTGRES_USER
  local database postgres_user postgres_password runtime_user runtime_password migrator_user migrator_password
  database="$(env_file_value "$RESTORE_POSTGRES_ENV" POSTGRES_DB)" ||
    die INTERNAL_FAILURE 'POSTGRES_DB must occur exactly once in the drill PostgreSQL env'
  postgres_user="$(env_file_value "$RESTORE_POSTGRES_ENV" POSTGRES_USER)" ||
    die INTERNAL_FAILURE 'POSTGRES_USER must occur exactly once in the drill PostgreSQL env'
  postgres_password="$(env_file_value "$RESTORE_POSTGRES_ENV" POSTGRES_PASSWORD)" ||
    die INTERNAL_FAILURE 'POSTGRES_PASSWORD must occur exactly once in the drill PostgreSQL env'
  [[ "$database" =~ ^[a-z_][a-z0-9_]{0,62}$ ]] || die INTERNAL_FAILURE 'drill PostgreSQL database name is invalid'
  [[ "$postgres_user" == postgres && -n "$postgres_password" ]] ||
    die INTERNAL_FAILURE 'drill PostgreSQL bootstrap identity is invalid'
  runtime_user="$(env_file_value "$RESTORE_POSTGRES_ENV" PORTFOLIO_DB_RUNTIME_USER)" ||
    die INTERNAL_FAILURE 'drill runtime database user is missing'
  runtime_password="$(env_file_value "$RESTORE_POSTGRES_ENV" PORTFOLIO_DB_RUNTIME_PASSWORD)" ||
    die INTERNAL_FAILURE 'drill runtime database password is missing'
  migrator_user="$(env_file_value "$RESTORE_POSTGRES_ENV" PORTFOLIO_DB_MIGRATOR_USER)" ||
    die INTERNAL_FAILURE 'drill migrator database user is missing'
  migrator_password="$(env_file_value "$RESTORE_POSTGRES_ENV" PORTFOLIO_DB_MIGRATOR_PASSWORD)" ||
    die INTERNAL_FAILURE 'drill migrator database password is missing'
  [[ "$runtime_user" =~ ^[a-z_][a-z0-9_]{0,62}$ &&
     "$migrator_user" =~ ^[a-z_][a-z0-9_]{0,62}$ &&
     "$runtime_user" != "$migrator_user" &&
     -n "$runtime_password" && -n "$migrator_password" ]] ||
    die INTERNAL_FAILURE 'drill database roles or passwords are invalid'
  local expected_url="jdbc:postgresql://postgres:5432/$database"
  [[ "$(env_file_value "$RESTORE_APP_ENV" PORTFOLIO_DB_URL)" == "$expected_url" &&
     "$(env_file_value "$RESTORE_APP_ENV" PORTFOLIO_DB_MIGRATOR_URL)" == "$expected_url" &&
     "$(env_file_value "$RESTORE_APP_ENV" PORTFOLIO_DB_RUNTIME_USER)" == "$runtime_user" &&
     "$(env_file_value "$RESTORE_APP_ENV" PORTFOLIO_DB_RUNTIME_PASSWORD)" == "$runtime_password" &&
     "$(env_file_value "$RESTORE_APP_ENV" PORTFOLIO_DB_MIGRATOR_USER)" == "$migrator_user" &&
     "$(env_file_value "$RESTORE_APP_ENV" PORTFOLIO_DB_MIGRATOR_PASSWORD)" == "$migrator_password" ]] ||
    die INTERNAL_FAILURE 'restore application JDBC credentials must point only to postgres:5432 in the isolated Compose network'
  local app_release active_totp_version totp_key_ring drill_totp_key
  app_release="$(env_file_value "$RESTORE_APP_ENV" PORTFOLIO_RELEASE_ID)" ||
    die INTERNAL_FAILURE 'PORTFOLIO_RELEASE_ID must occur exactly once in the restore application env'
  [[ "$app_release" =~ ^[0-9a-f]{12}-[0-9a-f]{12}$ ]] ||
    die INTERNAL_FAILURE 'restore application release ID is invalid'
  require_base64_secret_shape "$RESTORE_APP_ENV" PORTFOLIO_PREVIEW_HMAC_KEY
  require_base64_secret_shape "$RESTORE_APP_ENV" PORTFOLIO_CONTACT_DEDUPE_SECRET
  require_base64_secret_shape "$RESTORE_APP_ENV" PORTFOLIO_ANALYTICS_HMAC_SECRET
  active_totp_version="$(env_file_value "$RESTORE_APP_ENV" PORTFOLIO_TOTP_ACTIVE_KEY_VERSION)" ||
    die INTERNAL_FAILURE 'active TOTP key version must occur exactly once in the restore application env'
  totp_key_ring="$(env_file_value "$RESTORE_APP_ENV" PORTFOLIO_TOTP_KEY_RING)" ||
    die INTERNAL_FAILURE 'TOTP key ring must occur exactly once in the restore application env'
  [[ "$active_totp_version" == 1 && "$totp_key_ring" == 1=* && "$totp_key_ring" != *,* ]] ||
    die INTERNAL_FAILURE 'restore application requires one drill-specific TOTP bootstrap key'
  drill_totp_key="${totp_key_ring#1=}"
  [[ -n "$drill_totp_key" ]] ||
    die INTERNAL_FAILURE 'restore application requires one drill-specific TOTP bootstrap key'
  local drill_totp_file="$SECRETS_DIRECTORY/.drill-totp.env"
  printf 'PORTFOLIO_DRILL_TOTP_KEY=%s\n' "$drill_totp_key" >"$drill_totp_file"
  chmod 0600 "$drill_totp_file"
  require_base64_secret_shape "$drill_totp_file" PORTFOLIO_DRILL_TOTP_KEY
  rm -f -- "$drill_totp_file"
  [[ "$(env_file_value "$RESTORE_APP_ENV" PORTFOLIO_STORAGE_DEFAULT_PROVIDER)" == LOCAL &&
     "$(env_file_value "$RESTORE_APP_ENV" PORTFOLIO_COS_ENABLED)" == true &&
     "$(env_file_value "$RESTORE_APP_ENV" COS_BUCKET)" == "$RESTORE_DRILL_COS_BUCKET" &&
     "$(env_file_value "$RESTORE_APP_ENV" COS_REGION)" == "$RESTORE_DRILL_COS_REGION" &&
     -n "$(env_file_value "$RESTORE_APP_ENV" COS_SECRET_ID)" &&
     -n "$(env_file_value "$RESTORE_APP_ENV" COS_SECRET_KEY)" &&
     -n "$(env_file_value "$RESTORE_APP_ENV" COS_SESSION_TOKEN)" ]] ||
    die INTERNAL_FAILURE 'restore application must use only the drill COS bucket and short-lived credential'
  local forbidden key value
  for key in SMTP_HOST SMTP_USERNAME SMTP_PASSWORD; do
    value="$(awk -F= -v key="$key" '$1 == key {sub(/^[^=]*=/, ""); print}' "$RESTORE_APP_ENV")"
    [[ -z "$value" ]] || die INTERNAL_FAILURE 'SMTP credentials are forbidden in the restore application env'
  done
  for key in PORTFOLIO_EMAIL_ENABLED PORTFOLIO_JOBS_WORKER_ENABLED PORTFOLIO_STAGING_CLEANUP_ENABLED PORTFOLIO_MEDIA_CLEANUP_ENABLED PORTFOLIO_ANALYTICS_MAINTENANCE_SCHEDULING_ENABLED PORTFOLIO_JOBS_RETENTION_ENABLED; do
    value="$(awk -F= -v key="$key" '$1 == key {sub(/^[^=]*=/, ""); print}' "$RESTORE_APP_ENV")"
    [[ -z "$value" || "$value" == false ]] ||
      die INTERNAL_FAILURE 'email, job, cleanup, and analytics schedulers must be disabled in the restore application env'
  done
  for forbidden in "$PRODUCTION_COS_BUCKET" "$BACKUP_DESTINATION_BUCKET"; do
    if grep -F -- "$forbidden" "$RESTORE_APP_ENV" >/dev/null; then
      die INTERNAL_FAILURE 'production or backup COS location is forbidden in the restore application env'
    fi
  done
}

require_tracked_adapter() {
  local variable="$1"
  local expected="$2"
  local configured="${!variable:-$expected}"
  [[ "$configured" == "$expected" ]] ||
    die INTERNAL_FAILURE "$variable cannot replace the tracked production adapter"
  resolve_command "$expected" "$variable"
}

validate_runtime_dependencies() {
  [[ -f "$RESTORE_COMPOSE_YAML" && -f "$CLOSURE_SQL" && -x "$MEDIA_VERIFY_SCRIPT" ]] ||
    die INTERNAL_FAILURE 'tracked restore topology or verifier is missing'

  DOCKER_COMMAND="$(resolve_command "${RESTORE_DOCKER_COMMAND:-docker}" RESTORE_DOCKER_COMMAND)"
  AGE_COMMAND="$(resolve_command "${RESTORE_AGE_COMMAND:-age}" RESTORE_AGE_COMMAND)"
  export RESTORE_DOCKER_COMMAND="$DOCKER_COMMAND"
  export RESTORE_AGE_COMMAND="$AGE_COMMAND"
  RESTORE_RCLONE_COMMAND="$(resolve_command "${RESTORE_RCLONE_COMMAND:-rclone}" RESTORE_RCLONE_COMMAND)"
  RESTORE_CURL_COMMAND="$(resolve_command "${RESTORE_CURL_COMMAND:-curl}" RESTORE_CURL_COMMAND)"
  export RESTORE_RCLONE_COMMAND RESTORE_CURL_COMMAND
  BACKUP_VERIFY_COMMAND="$(require_tracked_adapter RESTORE_BACKUP_VERIFY_COMMAND "$DEFAULT_BACKUP_VERIFY_COMMAND")"
  RELEASE_VERIFY_COMMAND="$(require_tracked_adapter RESTORE_RELEASE_VERIFY_COMMAND "$DEFAULT_RELEASE_VERIFY_COMMAND")"
  MEDIA_TAR_VALIDATOR="$(require_tracked_adapter RESTORE_MEDIA_TAR_VALIDATOR "$DEFAULT_MEDIA_TAR_VALIDATOR")"
  COS_TRANSFER_COMMAND="$(require_tracked_adapter RESTORE_COS_TRANSFER_COMMAND "$DEFAULT_COS_TRANSFER_COMMAND")"
  APPLY_MEDIA_MAPPING_COMMAND="$(require_tracked_adapter RESTORE_APPLY_MEDIA_MAPPING_COMMAND "$DEFAULT_APPLY_MEDIA_MAPPING_COMMAND")"
  ROUTE_START_COMMAND="$(require_tracked_adapter RESTORE_ROUTE_START_COMMAND "$DEFAULT_ROUTE_START_COMMAND")"
  HTTP_FETCH_COMMAND="$(require_tracked_adapter RESTORE_HTTP_FETCH_COMMAND "$DEFAULT_HTTP_FETCH_COMMAND")"
  CONTENT_VERIFY_COMMAND="$(require_tracked_adapter RESTORE_CONTENT_VERIFY_COMMAND "$DEFAULT_CONTENT_VERIFY_COMMAND")"
  REPORT_UPLOAD_COMMAND="$(require_tracked_adapter RESTORE_REPORT_UPLOAD_COMMAND "$DEFAULT_REPORT_COMMAND")"
  REPORT_VERIFY_COMMAND="$(require_tracked_adapter RESTORE_REPORT_VERIFY_COMMAND "$DEFAULT_REPORT_COMMAND")"
  RECORD_COMMAND="$(require_tracked_adapter RESTORE_RECORD_COMMAND "$DEFAULT_RECORD_COMMAND")"
  REVOKE_COMMAND="$(require_tracked_adapter RESTORE_DRILL_CREDENTIAL_REVOKE_COMMAND "$DEFAULT_REVOKE_COMMAND")"
  DRILL_CLEANUP_COMMAND="$(require_tracked_adapter RESTORE_DRILL_CLEANUP_COMMAND "$DEFAULT_DRILL_CLEANUP_COMMAND")"

  export RESTORE_ROOT="$ROOT" RESTORE_COMPOSE_FILE="$RESTORE_COMPOSE_YAML"
  local expected_app="$SECRETS_DIRECTORY/app.env"
  local expected_postgres="$SECRETS_DIRECTORY/postgres.env"
  local expected_verifier="$SECRETS_DIRECTORY/backup-verifier.rclone.conf"
  local expected_uploader="$SECRETS_DIRECTORY/report-uploader.rclone.conf"
  local expected_drill="$SECRETS_DIRECTORY/drill-cos.rclone.conf"
  [[ "${RESTORE_APP_ENV:-}" == "$expected_app" &&
     "${RESTORE_POSTGRES_ENV:-}" == "$expected_postgres" &&
     "${BACKUP_VERIFY_RCLONE_CONFIG:-}" == "$expected_verifier" &&
     "${BACKUP_UPLOAD_RCLONE_CONFIG:-}" == "$expected_uploader" &&
     "${RESTORE_DRILL_COS_CONFIG:-}" == "$expected_drill" ]] ||
    die INTERNAL_FAILURE 'restore env and rclone configs must come from the drill-specific tmpfs secret directory'
  require_exact_drill_secret_path "$RESTORE_APP_ENV" app.env RESTORE_APP_ENV
  require_exact_drill_secret_path "$RESTORE_POSTGRES_ENV" postgres.env RESTORE_POSTGRES_ENV
  require_exact_drill_secret_path "$BACKUP_VERIFY_RCLONE_CONFIG" backup-verifier.rclone.conf BACKUP_VERIFY_RCLONE_CONFIG
  require_exact_drill_secret_path "$BACKUP_UPLOAD_RCLONE_CONFIG" report-uploader.rclone.conf BACKUP_UPLOAD_RCLONE_CONFIG
  require_exact_drill_secret_path "$RESTORE_DRILL_COS_CONFIG" drill-cos.rclone.conf RESTORE_DRILL_COS_CONFIG
  require_exact_drill_secret_path "${RESTORE_TLS_CERTIFICATE:-}" tls.crt RESTORE_TLS_CERTIFICATE
  require_exact_drill_secret_path "${RESTORE_TLS_PRIVATE_KEY:-}" tls.key RESTORE_TLS_PRIVATE_KEY
  require_exact_drill_secret_path "${RESTORE_TLS_CA_CERT:-}" ca.crt RESTORE_TLS_CA_CERT
  require_exact_drill_secret_path "${RESTORE_ADMIN_AUTH_FILE:-}" admin-auth.json RESTORE_ADMIN_AUTH_FILE
  export RESTORE_ADMIN_COOKIE_JAR="$ROOT/work/admin-cookie.jar"

  local expected_nginx="$ROOT/release/runtime/nginx"
  local expected_admin="$ROOT/release/admin"
  local expected_public="$ROOT/release/public-assets"
  [[ -z "${RESTORE_NGINX_CONFIG_DIR:-}" || "$RESTORE_NGINX_CONFIG_DIR" == "$expected_nginx" ]] ||
    die INTERNAL_FAILURE 'restore Nginx config must be generated beneath the verified drill release'
  [[ -z "${RESTORE_ADMIN_ASSETS_ROOT:-}" || "$RESTORE_ADMIN_ASSETS_ROOT" == "$expected_admin" ]] ||
    die INTERNAL_FAILURE 'administrator assets must come from the verified drill release'
  [[ -z "${RESTORE_PUBLIC_ASSETS_ROOT:-}" || "$RESTORE_PUBLIC_ASSETS_ROOT" == "$expected_public" ]] ||
    die INTERNAL_FAILURE 'public assets must come from the verified drill release'
  export RESTORE_NGINX_CONFIG_DIR="$expected_nginx"
  export RESTORE_ADMIN_ASSETS_ROOT="$expected_admin"
  export RESTORE_PUBLIC_ASSETS_ROOT="$expected_public"

  local name
  for name in PRODUCTION_COS_ACCOUNT_ID PRODUCTION_COS_BUCKET PRODUCTION_COS_REGION PRODUCTION_COS_PRINCIPAL_ID BACKUP_DESTINATION_ACCOUNT_ID BACKUP_DESTINATION_BUCKET BACKUP_DESTINATION_REGION BACKUP_VERIFY_PRINCIPAL_ID BACKUP_UPLOAD_PRINCIPAL_ID RESTORE_DRILL_COS_ACCOUNT_ID RESTORE_DRILL_COS_BUCKET RESTORE_DRILL_COS_REGION RESTORE_DRILL_COS_PRINCIPAL_ID BACKUP_REMOTE RESTORE_RELEASE_REMOTE RESTORE_DRILL_COS_REMOTE BACKUP_PREFIX RESTORE_DRILL_CREDENTIAL_EXPIRES_AT RESTORE_TLS_SERVER_NAME; do
    [[ -n "${!name:-}" ]] || die INTERNAL_FAILURE "$name is required"
  done
  local production_location="$PRODUCTION_COS_ACCOUNT_ID@$PRODUCTION_COS_BUCKET@$PRODUCTION_COS_REGION"
  local backup_location="$BACKUP_DESTINATION_ACCOUNT_ID@$BACKUP_DESTINATION_BUCKET@$BACKUP_DESTINATION_REGION"
  local drill_location="$RESTORE_DRILL_COS_ACCOUNT_ID@$RESTORE_DRILL_COS_BUCKET@$RESTORE_DRILL_COS_REGION"
  [[ "$production_location" != "$backup_location" &&
     "$production_location" != "$drill_location" && "$backup_location" != "$drill_location" ]] ||
    die INTERNAL_FAILURE 'production, backup, and drill COS locations must all be distinct'
  [[ "$PRODUCTION_COS_PRINCIPAL_ID" != "$BACKUP_VERIFY_PRINCIPAL_ID" &&
     "$PRODUCTION_COS_PRINCIPAL_ID" != "$BACKUP_UPLOAD_PRINCIPAL_ID" &&
     "$PRODUCTION_COS_PRINCIPAL_ID" != "$RESTORE_DRILL_COS_PRINCIPAL_ID" &&
     "$BACKUP_VERIFY_PRINCIPAL_ID" != "$BACKUP_UPLOAD_PRINCIPAL_ID" &&
     "$BACKUP_VERIFY_PRINCIPAL_ID" != "$RESTORE_DRILL_COS_PRINCIPAL_ID" &&
     "$BACKUP_UPLOAD_PRINCIPAL_ID" != "$RESTORE_DRILL_COS_PRINCIPAL_ID" ]] ||
    die INTERNAL_FAILURE 'production, verifier, report uploader, and drill COS principals must all be distinct'
  [[ "${PRODUCTION_COS_LOCATIONS:-}" == "$production_location" &&
     "${BACKUP_DESTINATION_LOCATION:-}" == "$backup_location" ]] ||
    die INTERNAL_FAILURE 'reviewed production and backup COS location declarations are incomplete'
  [[ "$BACKUP_VERIFY_RCLONE_CONFIG" != "$BACKUP_UPLOAD_RCLONE_CONFIG" &&
     "$BACKUP_VERIFY_RCLONE_CONFIG" != "$RESTORE_DRILL_COS_CONFIG" &&
     "$BACKUP_UPLOAD_RCLONE_CONFIG" != "$RESTORE_DRILL_COS_CONFIG" ]] ||
    die INTERNAL_FAILURE 'verifier, report uploader, and drill COS credential files must be distinct'
  [[ "$PRODUCTION_COS_ACCOUNT_ID" =~ ^[A-Za-z0-9][A-Za-z0-9_.:@-]{1,127}$ &&
     "$BACKUP_DESTINATION_ACCOUNT_ID" =~ ^[A-Za-z0-9][A-Za-z0-9_.:@-]{1,127}$ &&
     "$RESTORE_DRILL_COS_ACCOUNT_ID" =~ ^[A-Za-z0-9][A-Za-z0-9_.:@-]{1,127}$ &&
     "$PRODUCTION_COS_BUCKET" =~ ^[a-z0-9][a-z0-9.-]{1,126}[a-z0-9]$ &&
     "$BACKUP_DESTINATION_BUCKET" =~ ^[a-z0-9][a-z0-9.-]{1,126}[a-z0-9]$ &&
     "$RESTORE_DRILL_COS_BUCKET" =~ ^[a-z0-9][a-z0-9.-]{1,126}[a-z0-9]$ &&
     "$PRODUCTION_COS_REGION" =~ ^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$ &&
     "$BACKUP_DESTINATION_REGION" =~ ^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$ &&
     "$RESTORE_DRILL_COS_REGION" =~ ^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$ ]] ||
    die INTERNAL_FAILURE 'production, backup, or drill COS identity is invalid'
  [[ "$RESTORE_DRILL_COS_REMOTE" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}:"$RESTORE_DRILL_COS_BUCKET"$ &&
     "$BACKUP_REMOTE" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}:"$BACKUP_DESTINATION_BUCKET"$ &&
     "$RESTORE_RELEASE_REMOTE" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}: ]] ||
    die INTERNAL_FAILURE 'restore rclone remotes do not match their reviewed buckets'
  [[ "$BACKUP_PREFIX" =~ ^[A-Za-z0-9][A-Za-z0-9._/-]{0,126}[A-Za-z0-9]$ &&
     "$BACKUP_PREFIX" != *'..'* ]] || die INTERNAL_FAILURE 'backup prefix is invalid'
  [[ "$RESTORE_TLS_SERVER_NAME" =~ ^[a-z0-9]([a-z0-9.-]{0,61}[a-z0-9])?$ &&
     "$RESTORE_TLS_SERVER_NAME" == *.* && "$RESTORE_TLS_SERVER_NAME" != *..* ]] ||
    die INTERNAL_FAILURE 'drill TLS server name is invalid'
  local credential_expiry now_epoch
  credential_expiry="$(date -u -d "$RESTORE_DRILL_CREDENTIAL_EXPIRES_AT" +%s 2>/dev/null || true)"
  now_epoch="$(date -u +%s)"
  [[ "$credential_expiry" =~ ^[0-9]+$ &&
     "$credential_expiry" -ge $((now_epoch + MIN_CREDENTIAL_LIFETIME_SECONDS)) &&
     "$credential_expiry" -le $((now_epoch + 3600)) ]] ||
    die INTERNAL_FAILURE 'drill cloud credential must have 15-60 minutes of remaining lifetime'
  CREDENTIAL_SUCCESS_DEADLINE_EPOCH=$((credential_expiry - CREDENTIAL_CLEANUP_MARGIN_SECONDS))
  export PRODUCTION_COS_LOCATIONS="$production_location" BACKUP_DESTINATION_LOCATION="$backup_location"

  validate_isolated_env_files
  [[ "${RESTORE_API_IMAGE:-}" =~ ^(portfolio-api-archive:[0-9a-f]{12}-[0-9a-f]{12}|sha256:[0-9a-f]{64})$ &&
     "${RESTORE_POSTGRES_IMAGE:-}" =~ ^(portfolio-postgres-17-archive:[0-9a-f]{12}-[0-9a-f]{12}|sha256:[0-9a-f]{64})$ &&
     "${RESTORE_NGINX_IMAGE:-}" =~ ^sha256:[0-9a-f]{64}$ ]] ||
    die INTERNAL_FAILURE 'restore images must be exact archive tags or exact local content IDs'
  "$DOCKER_COMMAND" image inspect "$RESTORE_NGINX_IMAGE" >/dev/null 2>&1 ||
    die INTERNAL_FAILURE 'reviewed restore Nginx image is not available locally'
}

prepare_root() {
  ((EUID == 0)) || die INTERNAL_FAILURE 'restore drill must run as root'
  [[ -d "$RESTORE_BASE" && ! -L "$RESTORE_BASE" ]] ||
    die INTERNAL_FAILURE 'restore base directory is missing or linked'
  [[ "$(realpath -e -- "$RESTORE_BASE")" == "$RESTORE_BASE" ]] ||
    die INTERNAL_FAILURE 'restore base directory is not canonical'
  local base_owner base_mode base_mount parent_identity root_identity
  base_owner="$(stat -Lc '%u' -- "$RESTORE_BASE")"
  base_mode="$(stat -Lc '%a' -- "$RESTORE_BASE")"
  [[ "$base_owner" == 0 && $((8#$base_mode & 0022)) -eq 0 ]] ||
    die INTERNAL_FAILURE 'restore base must be root-owned and not writable by group or other'
  base_mount="$(findmnt -T "$RESTORE_BASE" -n -o TARGET 2>/dev/null || true)"
  [[ -n "$base_mount" && "$base_mount" != "$RESTORE_BASE" ]] ||
    die INTERNAL_FAILURE 'restore base must not be a mount or bind alias'
  [[ ! -e "$ROOT" && ! -L "$ROOT" ]] ||
    die INTERNAL_FAILURE 'restore root must not exist before atomic creation'
  parent_identity="$(stat -Lc '%d:%i' -- "$RESTORE_BASE")"
  mkdir -m 0700 -- "$ROOT" || die INTERNAL_FAILURE 'restore root could not be created'
  [[ "$(realpath -e -- "$ROOT")" == "$ROOT" ]] || die INTERNAL_FAILURE 'restore root changed during creation'
  [[ "$(stat -Lc '%u:%a' -- "$ROOT")" == 0:700 &&
     "$(stat -Lc '%d:%i' -- "$RESTORE_BASE")" == "$parent_identity" &&
     "$(stat -Lc '%d' -- "$ROOT")" == "$(stat -Lc '%d' -- "$RESTORE_BASE")" ]] ||
    die INTERNAL_FAILURE 'restore root ownership, mode, filesystem, or parent changed during creation'
  root_identity="$(stat -Lc '%d:%i' -- "$ROOT")"
  [[ "$(findmnt -T "$ROOT" -n -o TARGET 2>/dev/null || true)" == "$base_mount" ]] ||
    die INTERNAL_FAILURE 'restore root became a mount or bind alias'
  printf '%s\n' "$DRILL_ID" >"$ROOT/.portfolio-restore-drill"
  SET_DIRECTORY="$ROOT/set"
  WORK_DIRECTORY="$ROOT/work"
  LOCAL_MEDIA_ROOT="$ROOT/local-media"
  REPORT_FILE="$ROOT/report.json"
  REPORT_CHECKSUM_FILE="$ROOT/report.sha256"
  COUNTS_FILE="$WORK_DIRECTORY/counts.json"
  mkdir -m 0700 -- "$WORK_DIRECTORY" "$LOCAL_MEDIA_ROOT"
  [[ "$(stat -Lc '%d:%i' -- "$ROOT")" == "$root_identity" &&
     "$(stat -Lc '%d:%i' -- "$RESTORE_BASE")" == "$parent_identity" ]] ||
    die INTERNAL_FAILURE 'restore root changed while preparing isolated work directories'
  export RESTORE_LOCAL_MEDIA_ROOT="$LOCAL_MEDIA_ROOT"
}

safe_artifact_path() {
  local manifest_key="$1"
  local field="$2"
  local file
  file="$(jq -er ".artifacts.${manifest_key}.${field}" "$SET_DIRECTORY/set-manifest.json")" ||
    die BACKUP_VERIFICATION_FAILED 'backup set artifact metadata is missing'
  is_safe_relative_file "$file" || die BACKUP_VERIFICATION_FAILED 'backup set artifact path is unsafe'
  local path="$SET_DIRECTORY/$file"
  [[ -f "$path" && ! -L "$path" ]] || die BACKUP_VERIFICATION_FAILED 'backup set artifact is missing or linked'
  [[ "$(realpath -e -- "$path")" == "$path" ]] || die BACKUP_VERIFICATION_FAILED 'backup set artifact escaped its set'
  printf '%s\n' "$path"
}

verify_file_checksum() {
  local path="$1"
  local expected="$2"
  [[ "$expected" =~ ^[0-9a-f]{64}$ ]] || return 1
  [[ "$(sha256sum -- "$path" | awk '{print $1}')" == "$expected" ]]
}

acquire_and_validate_backup_set() {
  require_credential_deadline 'backup acquisition'
  if ! "$BACKUP_VERIFY_COMMAND" \
      --set-id "$SET_ID" \
      --output "$SET_DIRECTORY" \
      --verifier-config "$BACKUP_VERIFY_RCLONE_CONFIG"; then
    die BACKUP_VERIFICATION_FAILED 'verified backup set acquisition failed'
  fi
  [[ -d "$SET_DIRECTORY" && ! -L "$SET_DIRECTORY" ]] ||
    die BACKUP_VERIFICATION_FAILED 'verified backup set acquisition failed'
  local manifest="$SET_DIRECTORY/set-manifest.json"
  local marker="$SET_DIRECTORY/VERIFIED"
  [[ -f "$manifest" && ! -L "$manifest" && -f "$marker" && ! -L "$marker" ]] ||
    die BACKUP_VERIFICATION_FAILED 'backup set manifest or VERIFIED marker is missing'

  jq -e --arg set_id "$SET_ID" '
    (keys == [
      "artifacts","backupFinishedAt","backupStartedAt","blobs","counts",
      "releaseId","retention","schemaVersion","setId","snapshotId"
    ]) and
    .schemaVersion == 1 and .setId == $set_id and
    (.releaseId | type == "string" and test("^[0-9a-f]{12}-[0-9a-f]{12}$")) and
    (.backupFinishedAt | type == "string" and test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")) and
    (.snapshotId | type == "string" and test("^[A-Za-z0-9-]{8,128}$")) and
    (.backupStartedAt | type == "string" and test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")) and
    (.backupStartedAt <= .backupFinishedAt) and
    (.retention | keys == ["daily","monthly","weekly"] and
      .daily == true and (.weekly | type == "boolean") and (.monthly | type == "boolean")) and
    (.counts | keys == [
      "cosObjects","cosVerificationSamples","distinctCosBlobs","localObjects","totalObjects"
    ] and all(.[]; type == "number" and floor == . and . >= 0)) and
    (.counts.totalObjects == (.counts.localObjects + .counts.cosObjects)) and
    (.counts.distinctCosBlobs == (.blobs | length)) and
    (.artifacts | keys == ["databaseDump","localMediaTar","mediaManifest"]) and
    (.artifacts.databaseDump.file == "database.dump.age") and
    (.artifacts.localMediaTar.file == "local-media.tar.age") and
    (.artifacts.mediaManifest.file == "media-manifest.json.age") and
    all(.artifacts[];
      (keys == ["byteSize","ciphertextSha256","file"]) and
      (.file | type == "string" and test("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")) and
      (.byteSize | type == "number" and floor == . and . > 0) and
      (.ciphertextSha256 | type == "string" and test("^[0-9a-f]{64}$"))) and
    (.blobs | type == "array") and
    all(.blobs[];
      (keys == ["byteSize","path","sha256"]) and
      (.sha256 | type == "string" and test("^[0-9a-f]{64}$")) and
      (.path == ("blobs/" + .sha256)) and
      (.byteSize | type == "number" and floor == . and . > 0)
    ) and
    ((.blobs | map(.path)) == (.blobs | map(.path) | sort | unique))
  ' "$manifest" >/dev/null || die BACKUP_VERIFICATION_FAILED 'backup set manifest is invalid'

  local canonical_manifest="$WORK_DIRECTORY/canonical-set-manifest.json"
  jq -Sc . "$manifest" >"$canonical_manifest" || die BACKUP_VERIFICATION_FAILED 'backup set manifest is not valid JSON'
  cmp -s -- "$manifest" "$canonical_manifest" ||
    die BACKUP_VERIFICATION_FAILED 'backup set manifest is not canonical compact JSON'
  local total_blobs declared_samples expected_samples
  total_blobs="$(jq '.blobs | length' "$manifest")"
  declared_samples="$(jq '.counts.cosVerificationSamples' "$manifest")"
  if ((total_blobs == 0)); then
    expected_samples=0
  elif ((total_blobs < 20)); then
    expected_samples="$total_blobs"
  else
    expected_samples=$(((total_blobs + 99) / 100))
    ((expected_samples < 20)) && expected_samples=20
    ((expected_samples > 200)) && expected_samples=200
    ((expected_samples > total_blobs)) && expected_samples="$total_blobs"
  fi
  [[ "$declared_samples" == "$expected_samples" ]] ||
    die BACKUP_VERIFICATION_FAILED 'backup set COS verification sample count is invalid'

  local marker_value marker_lines
  marker_lines="$(wc -l <"$marker")"
  marker_value="$(tr -d '\n' <"$marker")"
  [[ "$marker_lines" -eq 1 && "$marker_value" =~ ^[0-9a-f]{64}$ ]] ||
    die BACKUP_VERIFICATION_FAILED 'VERIFIED marker is invalid'
  SET_MANIFEST_SHA="$(sha256sum -- "$manifest" | awk '{print $1}')"
  [[ "$marker_value" == "$SET_MANIFEST_SHA" ]] ||
    die BACKUP_VERIFICATION_FAILED 'VERIFIED marker does not match the set manifest'

  local database_cipher local_cipher media_cipher
  database_cipher="$(safe_artifact_path databaseDump file)"
  local_cipher="$(safe_artifact_path localMediaTar file)"
  media_cipher="$(safe_artifact_path mediaManifest file)"
  verify_file_checksum "$database_cipher" "$(jq -r '.artifacts.databaseDump.ciphertextSha256' "$manifest")" ||
    die BACKUP_VERIFICATION_FAILED 'database ciphertext checksum does not match'
  verify_file_checksum "$local_cipher" "$(jq -r '.artifacts.localMediaTar.ciphertextSha256' "$manifest")" ||
    die BACKUP_VERIFICATION_FAILED 'Local tar ciphertext checksum does not match'
  verify_file_checksum "$media_cipher" "$(jq -r '.artifacts.mediaManifest.ciphertextSha256' "$manifest")" ||
    die BACKUP_VERIFICATION_FAILED 'media manifest ciphertext checksum does not match'
  [[ "$(stat -Lc '%s' -- "$database_cipher")" == "$(jq -r '.artifacts.databaseDump.byteSize' "$manifest")" &&
     "$(stat -Lc '%s' -- "$local_cipher")" == "$(jq -r '.artifacts.localMediaTar.byteSize' "$manifest")" &&
     "$(stat -Lc '%s' -- "$media_cipher")" == "$(jq -r '.artifacts.mediaManifest.byteSize' "$manifest")" ]] ||
    die BACKUP_VERIFICATION_FAILED 'backup set artifact byte size does not match'

  local verified_blob_directory="$WORK_DIRECTORY/verified-backup-blobs"
  mkdir -m 0700 -- "$verified_blob_directory"
  local blob_path blob_sha blob_size blob_output
  while IFS=$'\t' read -r blob_path blob_sha blob_size; do
    [[ -n "$blob_path" && -n "$blob_sha" ]] || continue
    blob_output="$verified_blob_directory/$blob_sha"
    require_credential_deadline 'backup blob verification'
    if ! "$COS_TRANSFER_COMMAND" fetch-backup \
        --blob "$blob_path" \
        --sha256 "$blob_sha" \
        --output "$blob_output" \
        --verifier-config "$BACKUP_VERIFY_RCLONE_CONFIG"; then
      die BACKUP_VERIFICATION_FAILED 'an immutable backup blob could not be independently downloaded'
    fi
    verify_file_checksum "$blob_output" "$blob_sha" ||
      die BACKUP_VERIFICATION_FAILED 'an immutable backup blob checksum does not match'
    if [[ -n "$blob_size" ]]; then
      [[ "$(stat -Lc '%s' -- "$blob_output")" == "$blob_size" ]] ||
        die BACKUP_VERIFICATION_FAILED 'an immutable backup blob size does not match'
    fi
  done < <(jq -r '.blobs[] | [.path,.sha256,(.byteSize|tostring)] | @tsv' "$manifest")

  RELEASE_ID="$(jq -r '.releaseId' "$manifest")"
  [[ "$(env_file_value "$RESTORE_APP_ENV" PORTFOLIO_RELEASE_ID)" == "$RELEASE_ID" ]] ||
    die RELEASE_VERIFICATION_FAILED 'restore application release ID does not match the selected backup set'
  local backup_finished_epoch
  backup_finished_epoch="$(date -u -d "$(jq -r '.backupFinishedAt' "$manifest")" +%s 2>/dev/null || true)"
  [[ "$backup_finished_epoch" =~ ^[0-9]+$ && "$START_WALL_EPOCH" -ge "$backup_finished_epoch" ]] ||
    die BACKUP_VERIFICATION_FAILED 'backup completion timestamp is invalid'
  RPO_SECONDS=$((START_WALL_EPOCH - backup_finished_epoch))
  ((RPO_SECONDS <= MAX_RPO_SECONDS)) || die RPO_EXCEEDED 'selected backup set exceeds the 24-hour RPO'

  require_credential_deadline 'release bundle verification'
  if ! "$RELEASE_VERIFY_COMMAND" \
      --release-id "$RELEASE_ID" \
      --set-manifest "$manifest" \
      --verifier-config "$BACKUP_VERIFY_RCLONE_CONFIG" \
      --output "$ROOT/release"; then
    die RELEASE_VERIFICATION_FAILED 'release continuity verification failed'
  fi
  local release_json="$ROOT/release/release.json"
  [[ -f "$release_json" && ! -L "$release_json" ]] ||
    die RELEASE_VERIFICATION_FAILED 'release verifier did not install complete release.json beneath the drill root'
  jq -e --arg id "$RELEASE_ID" '
    .releaseId == $id and
    (.apiImageId | type == "string" and test("^sha256:[0-9a-f]{64}$")) and
    (.postgresImageId | type == "string" and test("^sha256:[0-9a-f]{64}$")) and
    .apiImageTag == ("portfolio-api:" + $id) and
    .postgresImageTag == ("portfolio-postgres-17:" + $id)
  ' "$release_json" >/dev/null || die RELEASE_VERIFICATION_FAILED 'release verifier output identity is incomplete'
  local verified_api_id verified_postgres_id
  verified_api_id="$(jq -r '.apiImageId' "$release_json")"
  verified_postgres_id="$(jq -r '.postgresImageId' "$release_json")"
  [[ "$RESTORE_API_IMAGE" == "portfolio-api-archive:$RELEASE_ID" ||
     "$RESTORE_API_IMAGE" == "$verified_api_id" ]] ||
    die RELEASE_VERIFICATION_FAILED 'restore API image is not the exact verified archive tag or Config ID'
  [[ "$RESTORE_POSTGRES_IMAGE" == "portfolio-postgres-17-archive:$RELEASE_ID" ||
     "$RESTORE_POSTGRES_IMAGE" == "$verified_postgres_id" ]] ||
    die RELEASE_VERIFICATION_FAILED 'restore PostgreSQL image is not the exact verified archive tag or Config ID'
}

decrypt_artifacts() {
  local manifest="$SET_DIRECTORY/set-manifest.json"
  local database_cipher local_cipher media_cipher
  database_cipher="$(safe_artifact_path databaseDump file)"
  local_cipher="$(safe_artifact_path localMediaTar file)"
  media_cipher="$(safe_artifact_path mediaManifest file)"
  if ! "$AGE_COMMAND" --decrypt --identity "$IDENTITY_REFERENCE" --output "$WORK_DIRECTORY/database.dump" "$database_cipher"; then
    dispose_identity
    die BACKUP_VERIFICATION_FAILED 'database dump decryption failed'
  fi
  if ! "$AGE_COMMAND" --decrypt --identity "$IDENTITY_REFERENCE" --output "$WORK_DIRECTORY/local-media.tar" "$local_cipher"; then
    dispose_identity
    die BACKUP_VERIFICATION_FAILED 'Local media tar decryption failed'
  fi
  if ! "$AGE_COMMAND" --decrypt --identity "$IDENTITY_REFERENCE" --output "$WORK_DIRECTORY/media-manifest.json" "$media_cipher"; then
    dispose_identity
    die BACKUP_VERIFICATION_FAILED 'media manifest decryption failed'
  fi
  dispose_identity
  [[ -f "$WORK_DIRECTORY/database.dump" && -f "$WORK_DIRECTORY/local-media.tar" && -f "$WORK_DIRECTORY/media-manifest.json" ]] ||
    die BACKUP_VERIFICATION_FAILED 'decryption did not produce every required artifact'

  local database_plain_sha snapshot_id
  database_plain_sha="$(sha256sum -- "$WORK_DIRECTORY/database.dump" | awk '{print $1}')"
  snapshot_id="$(jq -r '.snapshotId' "$manifest")"
  jq -e --slurpfile set "$manifest" --arg set_id "$SET_ID" --arg snapshot_id "$snapshot_id" --arg database_sha "$database_plain_sha" '
    .schemaVersion == 1 and .setId == $set_id and .snapshotId == $snapshot_id and
    .databaseDumpPlaintextSha256 == $database_sha and (.rows | type == "array") and
    ($set[0].counts as $counts |
      (.rows | length) == $counts.totalObjects and
      ([.rows[] | select(.provider == "LOCAL")] | length) == $counts.localObjects and
      ([.rows[] | select(.provider == "TENCENT_COS")] | length) == $counts.cosObjects and
      ([.rows[] | select(.provider == "TENCENT_COS") |
          {byteSize:.byteSize,path:("blobs/" + .sha256),sha256:.sha256}] |
        sort_by(.path) | unique_by(.path)) == ($set[0].blobs | sort_by(.path)) and
      ([.rows[] | select(.provider == "TENCENT_COS") | .sha256] | unique | length) ==
        $counts.distinctCosBlobs)
  ' "$WORK_DIRECTORY/media-manifest.json" >/dev/null ||
    die BACKUP_VERIFICATION_FAILED 'authoritative media manifest and immutable set manifest do not match exactly'
}

compose() {
  "$DOCKER_COMMAND" compose --project-name "$RESTORE_COMPOSE_PROJECT_NAME" -f "$RESTORE_COMPOSE_YAML" "$@"
}

restore_database() {
  if ! compose up -d --wait --wait-timeout 180 restore-postgres; then
    die DATABASE_RESTORE_FAILED 'isolated PostgreSQL startup failed'
  fi

  if ! compose exec -T restore-postgres sh -eu -c '
    : "${POSTGRES_USER:?}" "${POSTGRES_DB:?}" \
      "${PORTFOLIO_DB_RUNTIME_USER:?}" "${PORTFOLIO_DB_RUNTIME_PASSWORD:?}" \
      "${PORTFOLIO_DB_MIGRATOR_USER:?}" "${PORTFOLIO_DB_MIGRATOR_PASSWORD:?}"
    exec psql -X --no-psqlrc -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
      --set="runtime_user=$PORTFOLIO_DB_RUNTIME_USER" \
      --set="runtime_password=$PORTFOLIO_DB_RUNTIME_PASSWORD" \
      --set="migrator_user=$PORTFOLIO_DB_MIGRATOR_USER" \
      --set="migrator_password=$PORTFOLIO_DB_MIGRATOR_PASSWORD"
  ' <<'SQL'
SELECT 1 / CASE WHEN
  :'runtime_user' ~ '^[a-z_][a-z0-9_]{0,62}$'
  AND :'migrator_user' ~ '^[a-z_][a-z0-9_]{0,62}$'
  AND :'runtime_user' <> :'migrator_user'
  AND :'runtime_user' <> 'portfolio_runtime_access'
  AND :'migrator_user' <> 'portfolio_runtime_access'
  AND :'runtime_user' <> current_user
  AND :'migrator_user' <> current_user
THEN 1 ELSE 0 END AS isolated_login_names_are_safe;
SELECT 'CREATE ROLE portfolio_runtime_access NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS'
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'portfolio_runtime_access')
\gexec
SELECT format(
  'CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L',
  :'runtime_user', :'runtime_password'
)
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'runtime_user')
\gexec
SELECT format(
  'ALTER ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L',
  :'runtime_user', :'runtime_password'
)
\gexec
SELECT format(
  'CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L',
  :'migrator_user', :'migrator_password'
)
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'migrator_user')
\gexec
SELECT format(
  'ALTER ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L',
  :'migrator_user', :'migrator_password'
)
\gexec
SQL
  then
    die DATABASE_RESTORE_FAILED 'isolated restore roles could not be prepared'
  fi

  if ! compose exec -T restore-postgres sh -eu -c '
    exec pg_restore --clean --if-exists --no-owner --exit-on-error \
      -U "$POSTGRES_USER" -d "$POSTGRES_DB"
  ' <"$WORK_DIRECTORY/database.dump"; then
    die DATABASE_RESTORE_FAILED 'pg_restore failed in the isolated database'
  fi

  if ! compose exec -T restore-postgres sh -eu -c '
    : "${POSTGRES_USER:?}" "${POSTGRES_DB:?}" \
      "${PORTFOLIO_DB_RUNTIME_USER:?}" "${PORTFOLIO_DB_MIGRATOR_USER:?}"
    exec psql -X --no-psqlrc -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
      --set="runtime_user=$PORTFOLIO_DB_RUNTIME_USER" \
      --set="migrator_user=$PORTFOLIO_DB_MIGRATOR_USER"
  ' <<'SQL'
REVOKE ALL ON SCHEMA portfolio FROM PUBLIC;
REVOKE ALL ON SCHEMA portfolio FROM portfolio_runtime_access;
REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA portfolio FROM PUBLIC;
REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA portfolio FROM portfolio_runtime_access;
REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA portfolio FROM PUBLIC;
REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA portfolio FROM portfolio_runtime_access;
REVOKE ALL PRIVILEGES ON ALL FUNCTIONS IN SCHEMA portfolio FROM PUBLIC;
REVOKE ALL PRIVILEGES ON ALL FUNCTIONS IN SCHEMA portfolio FROM portfolio_runtime_access;

GRANT USAGE ON SCHEMA portfolio TO portfolio_runtime_access;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA portfolio TO portfolio_runtime_access;

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE
  portfolio.spring_session,
  portfolio.spring_session_attributes,
  portfolio.totp_recovery_code,
  portfolio.media_variant,
  portfolio.media_translation,
  portfolio.site_profile_translation,
  portfolio.site_seo_translation,
  portfolio.site_accessibility_copy_translation,
  portfolio.site_navigation_item,
  portfolio.site_navigation_item_translation,
  portfolio.hero_section,
  portfolio.hero_section_translation,
  portfolio.hero_media,
  portfolio.about_section_translation,
  portfolio.work_section_translation,
  portfolio.contact_section_translation,
  portfolio.privacy_notice_translation,
  portfolio.social_link,
  portfolio.profile_fact,
  portfolio.profile_fact_translation,
  portfolio.profile_skill,
  portfolio.profile_skill_translation,
  portfolio.tag,
  portfolio.tag_translation,
  portfolio.skill,
  portfolio.skill_translation,
  portfolio.project,
  portfolio.project_translation,
  portfolio.project_tag,
  portfolio.project_skill,
  portfolio.project_media,
  portfolio.project_content_block,
  portfolio.project_content_block_translation,
  portfolio.content_block_media,
  portfolio.content_block_markdown_translation,
  portfolio.content_block_video,
  portfolio.content_block_code,
  portfolio.content_block_quote_translation,
  portfolio.content_block_action,
  portfolio.content_block_metric,
  portfolio.content_block_metric_translation,
  portfolio.roadmap_header_translation,
  portfolio.roadmap_stage,
  portfolio.roadmap_stage_translation,
  portfolio.roadmap_outcome,
  portfolio.roadmap_outcome_translation,
  portfolio.resume_document
TO portfolio_runtime_access;

GRANT SELECT, INSERT, UPDATE ON TABLE
  portfolio.admin_user,
  portfolio.admin_session_metadata,
  portfolio.publication
TO portfolio_runtime_access;
GRANT SELECT, INSERT ON TABLE
  portfolio.audit_log,
  portfolio.background_job,
  portfolio.maintenance_run,
  portfolio.content_revision,
  portfolio.revision_media_reference,
  portfolio.slug_redirect,
  portfolio.email_outbox,
  portfolio.analytics_event
TO portfolio_runtime_access;
GRANT SELECT, INSERT, DELETE ON TABLE
  portfolio.media_asset,
  portfolio.local_staging_reservation,
  portfolio.contact_message,
  portfolio.analytics_daily
TO portfolio_runtime_access;
GRANT SELECT ON TABLE
  portfolio.site_profile,
  portfolio.local_staging_policy,
  portfolio.analytics_retention_checkpoint
TO portfolio_runtime_access;

GRANT UPDATE (status, attempts, next_run_at, lease_owner, lease_until, last_error_summary)
  ON TABLE portfolio.background_job TO portfolio_runtime_access;
GRANT UPDATE (status, artifact_checksum, error_summary, details, finished_at)
  ON TABLE portfolio.maintenance_run TO portfolio_runtime_access;
GRANT UPDATE (status, archived_at, version)
  ON TABLE portfolio.media_asset TO portfolio_runtime_access;
GRANT UPDATE (generation, cleanup_job_id)
  ON TABLE portfolio.local_staging_reservation TO portfolio_runtime_access;
GRANT INSERT (singleton_key, active_capacity, scan_entry_ceiling, worst_case_entries_per_reservation, reserved_headroom)
  ON TABLE portfolio.local_staging_policy TO portfolio_runtime_access;
GRANT UPDATE (monogram, email, version, updated_at)
  ON TABLE portfolio.site_profile TO portfolio_runtime_access;
GRANT UPDATE (status, version, updated_at)
  ON TABLE portfolio.contact_message TO portfolio_runtime_access;
GRANT UPDATE (status, attempts, next_attempt_at, lease_owner, lease_until, last_error_summary, sent_at, updated_at)
  ON TABLE portfolio.email_outbox TO portfolio_runtime_access;
GRANT UPDATE (metric_count, aggregation_version, updated_at)
  ON TABLE portfolio.analytics_daily TO portfolio_runtime_access;

GRANT EXECUTE ON FUNCTION
  portfolio.set_updated_at(),
  portfolio.reject_audit_mutation(),
  portfolio.delete_expired_terminal_background_jobs(INTEGER),
  portfolio.claim_local_staging_volume(TEXT),
  portfolio.analytics_date_has_exact_aggregate_coverage(DATE),
  portfolio.prepare_analytics_retention_checkpoint(DATE, TIMESTAMPTZ),
  portfolio.purge_analytics_event_batch(DATE, TIMESTAMPTZ, INTEGER)
TO portfolio_runtime_access;

SELECT format('GRANT portfolio_runtime_access TO %I', :'runtime_user')
\gexec
GRANT USAGE ON SCHEMA portfolio TO :"migrator_user";
GRANT SELECT ON TABLE portfolio.flyway_schema_history TO :"migrator_user";

SELECT 1 / CASE WHEN
  NOT has_schema_privilege(:'runtime_user', 'portfolio', 'CREATE')
  AND has_schema_privilege(:'runtime_user', 'portfolio', 'USAGE')
  AND has_table_privilege(:'runtime_user', 'portfolio.publication', 'SELECT,INSERT,UPDATE')
  AND has_table_privilege(:'runtime_user', 'portfolio.spring_session', 'SELECT,INSERT,UPDATE,DELETE')
  AND NOT has_table_privilege(:'runtime_user', 'portfolio.flyway_schema_history', 'SELECT,INSERT,UPDATE,DELETE')
  AND has_function_privilege(:'runtime_user', 'portfolio.set_updated_at()', 'EXECUTE')
  AND NOT has_function_privilege(:'runtime_user', 'portfolio.reject_published_history_mutation()', 'EXECUTE')
  AND NOT EXISTS (
    SELECT 1
    FROM pg_sequences AS sequence
    WHERE sequence.schemaname = 'portfolio'
      AND NOT has_sequence_privilege(
        :'runtime_user',
        format('%I.%I', sequence.schemaname, sequence.sequencename),
        'USAGE,SELECT'
      )
  )
THEN 1 ELSE 0 END AS isolated_runtime_dml_without_ddl;

BEGIN;
SET LOCAL ROLE :"runtime_user";
SELECT count(*) FROM portfolio.publication;
INSERT INTO portfolio.publication SELECT * FROM portfolio.publication WHERE false;
UPDATE portfolio.publication SET version = version WHERE false;
DELETE FROM portfolio.spring_session WHERE false;
ROLLBACK;
SQL
  then
    die DATABASE_RESTORE_FAILED 'runtime DML/no-DDL privilege proof failed'
  fi

  if PORTFOLIO_RESTORE_PHASE=deny-create compose exec -T restore-postgres sh -eu -c '
      exec psql -X --no-psqlrc -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
        --set="runtime_user=$PORTFOLIO_DB_RUNTIME_USER"
    ' <<'SQL' >/dev/null 2>&1
BEGIN;
SET LOCAL ROLE :"runtime_user";
CREATE TABLE portfolio.restore_drill_forbidden_create (id INTEGER);
ROLLBACK;
SQL
  then
    die DATABASE_RESTORE_FAILED 'runtime login unexpectedly created a schema object'
  fi
  if PORTFOLIO_RESTORE_PHASE=deny-alter compose exec -T restore-postgres sh -eu -c '
      exec psql -X --no-psqlrc -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
        --set="runtime_user=$PORTFOLIO_DB_RUNTIME_USER"
    ' <<'SQL' >/dev/null 2>&1
BEGIN;
SET LOCAL ROLE :"runtime_user";
ALTER TABLE portfolio.publication ADD COLUMN restore_drill_forbidden_alter INTEGER;
ROLLBACK;
SQL
  then
    die DATABASE_RESTORE_FAILED 'runtime login unexpectedly altered a schema object'
  fi
  if PORTFOLIO_RESTORE_PHASE=deny-drop compose exec -T restore-postgres sh -eu -c '
      exec psql -X --no-psqlrc -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
        --set="runtime_user=$PORTFOLIO_DB_RUNTIME_USER"
    ' <<'SQL' >/dev/null 2>&1
BEGIN;
SET LOCAL ROLE :"runtime_user";
DROP TABLE portfolio.publication;
ROLLBACK;
SQL
  then
    die DATABASE_RESTORE_FAILED 'runtime login unexpectedly dropped a schema object'
  fi
}

resolve_media_closure() {
  local joined
  joined="$(IFS=,; printf '%s' "${HISTORICAL_REVISIONS[*]}")"
  if ! PORTFOLIO_RESTORE_PHASE=resolve-closure compose exec -T restore-postgres sh -eu -c '
      exec psql -X --no-psqlrc -A -t -q -F "|" -v ON_ERROR_STOP=1 \
        -U "$POSTGRES_USER" -d "$POSTGRES_DB" "$@"
    ' portfolio-restore-closure --set="historical_revision_ids=$joined" -f - \
      <"$CLOSURE_SQL" >"$WORK_DIRECTORY/media-closure.psv"; then
    die MEDIA_CLOSURE_FAILED 'current and selected historical media closure resolution failed'
  fi
  [[ -s "$WORK_DIRECTORY/media-closure.psv" ]] || die MEDIA_CLOSURE_FAILED 'resolved media closure is empty'
}

validate_and_extract_local_media() {
  local manifest="$WORK_DIRECTORY/media-manifest.json"
  local allowlist="$WORK_DIRECTORY/local-allowlist.json"
  local selection="$WORK_DIRECTORY/local-selection.json"
  local paths="$WORK_DIRECTORY/local-selection.paths"

  if ! jq -e -S '
      {schemaVersion:1,entries:(
        [.rows[] | select(.provider == "LOCAL") |
          {path:.objectKey,size:.byteSize,sha256:.sha256}] | sort_by(.path)
      )}
    ' "$manifest" >"$allowlist"; then
    die LOCAL_MEDIA_FAILED 'complete Local media allowlist could not be built'
  fi
  if ! "$MEDIA_TAR_VALIDATOR" --allowlist "$allowlist" "$WORK_DIRECTORY/local-media.tar"; then
    die LOCAL_MEDIA_FAILED 'Local media archive validation failed before extraction'
  fi

  awk -F '|' '$6 == "LOCAL" {print $9}' "$WORK_DIRECTORY/media-closure.psv" | LC_ALL=C sort -u >"$paths"
  jq -Rn -S '{schemaVersion:1,paths:[inputs]}' <"$paths" >"$selection"
  if ! "$MEDIA_TAR_VALIDATOR" \
      --allowlist "$allowlist" \
      --select "$selection" \
      --destination "$LOCAL_MEDIA_ROOT" \
      "$WORK_DIRECTORY/local-media.tar"; then
    die LOCAL_MEDIA_FAILED 'safe selected Local media extraction failed'
  fi
  chown -R -- 10001:10001 "$LOCAL_MEDIA_ROOT" ||
    die LOCAL_MEDIA_FAILED 'isolated Local media ownership could not be assigned'
  find "$LOCAL_MEDIA_ROOT" -xdev -type d -exec chmod 0750 {} + ||
    die LOCAL_MEDIA_FAILED 'isolated Local media directory modes could not be assigned'
  find "$LOCAL_MEDIA_ROOT" -xdev -type f -exec chmod 0640 {} + ||
    die LOCAL_MEDIA_FAILED 'isolated Local media file modes could not be assigned'
}

verify_media_and_routes() {
  require_credential_deadline 'COS media verification'
  export RESTORE_COS_TRANSFER_COMMAND="$COS_TRANSFER_COMMAND"
  export RESTORE_APPLY_MEDIA_MAPPING_COMMAND="$APPLY_MEDIA_MAPPING_COMMAND"
  export RESTORE_ROUTE_START_COMMAND="$ROUTE_START_COMMAND"
  export RESTORE_HTTP_FETCH_COMMAND="$HTTP_FETCH_COMMAND"
  export RESTORE_NGINX_BASE_URL="https://$RESTORE_TLS_SERVER_NAME:$RESTORE_NGINX_PORT"
  if ! "$MEDIA_VERIFY_SCRIPT" \
      --closure "$WORK_DIRECTORY/media-closure.psv" \
      --manifest "$WORK_DIRECTORY/media-manifest.json" \
      --local-root "$LOCAL_MEDIA_ROOT" \
      --drill-id "$DRILL_ID" \
      --summary "$WORK_DIRECTORY/media-summary.json" \
      --mapping "$WORK_DIRECTORY/media-mapping.psv"; then
    die COS_MEDIA_FAILED 'mixed-provider media mapping or route verification failed'
  fi
}

verify_current_and_historical_content() {
  require_credential_deadline 'isolated API content verification'
  local content_summary="$WORK_DIRECTORY/content-summary.json"
  local -a arguments=(--base-url "$RESTORE_NGINX_BASE_URL")
  local revision
  for revision in "${HISTORICAL_REVISIONS[@]}"; do
    arguments+=(--historical-revision "$revision")
  done
  if ! "$CONTENT_VERIFY_COMMAND" "${arguments[@]}" >"$content_summary"; then
    die HISTORICAL_RESTORE_FAILED 'current read and historical draft-restore proof failed'
  fi
  jq -e '
    (keys | sort) == ["currentProjects","draftRestores","historicalRevisions"] and
    all(.[]; type == "number" and floor == . and . > 0)
  ' "$content_summary" >/dev/null ||
    die HISTORICAL_RESTORE_FAILED 'content proof returned invalid redacted counts'
  jq -e -S -s '
    .[0] as $media | .[1] as $content |
    {
      closureObjects:$media.closureObjects,
      localObjects:$media.localObjects,
      cosObjects:$media.cosObjects,
      currentRoutes:$media.currentRoutes,
      historicalRoutes:$media.historicalRoutes,
      currentProjects:$content.currentProjects,
      historicalRevisions:$content.historicalRevisions,
      draftRestores:$content.draftRestores
    } |
    if all(.[]; type == "number" and floor == . and . >= 0) then . else error("invalid counts") end
  ' "$WORK_DIRECTORY/media-summary.json" "$content_summary" >"$COUNTS_FILE" ||
    die INTERNAL_FAILURE 'redacted drill counts could not be assembled'
}

write_report() {
  local status="$1"
  local category="$2"
  local output="$3"
  valid_error_category "$category" || category=INTERNAL_FAILURE
  FINISHED_AT="$(utc_now)"
  local now_monotonic
  now_monotonic="$(monotonic_seconds)"
  RTO_SECONDS=$((now_monotonic - START_MONOTONIC))
  ((RTO_SECONDS >= 0)) || RTO_SECONDS=0
  local counts='{}'
  if [[ -f "$COUNTS_FILE" ]]; then
    counts="$(jq -cS . "$COUNTS_FILE" 2>/dev/null || printf '{}')"
  fi
  jq -n -S \
    --arg drillId "$DRILL_ID" \
    --arg setId "$SET_ID" \
    --arg releaseId "$RELEASE_ID" \
    --arg status "$status" \
    --arg startedAt "$STARTED_AT" \
    --arg finishedAt "$FINISHED_AT" \
    --arg errorCategory "$category" \
    --arg setManifest "$SET_MANIFEST_SHA" \
    --argjson rpoSeconds "$RPO_SECONDS" \
    --argjson rtoSeconds "$RTO_SECONDS" \
    --argjson counts "$counts" \
    '{
      drillId:$drillId,setId:$setId,releaseId:$releaseId,status:$status,
      startedAt:$startedAt,finishedAt:$finishedAt,
      rpoSeconds:$rpoSeconds,rtoSeconds:$rtoSeconds,
      counts:$counts,checksums:{setManifest:$setManifest},errorCategory:$errorCategory
    }' >"$output"
}

upload_and_verify_report() {
  local report="$1"
  local checksum="$2"
  local report_sha
  report_sha="$(sha256sum -- "$report" | awk '{print $1}')"
  printf '%s  report.json\n' "$report_sha" >"$checksum"
  "$REPORT_UPLOAD_COMMAND" upload \
    --drill-id "$DRILL_ID" \
    --remote-prefix "$BACKUP_PREFIX/drill-reports/$DRILL_ID" \
    --report "$report" \
    --checksum "$checksum" \
    --uploader-config "$BACKUP_UPLOAD_RCLONE_CONFIG" >/dev/null || return 1
  "$REPORT_VERIFY_COMMAND" verify \
    --drill-id "$DRILL_ID" \
    --remote-prefix "$BACKUP_PREFIX/drill-reports/$DRILL_ID" \
    --expected-sha "$report_sha" \
    --verifier-config "$BACKUP_VERIFY_RCLONE_CONFIG" >/dev/null || return 1
  printf '%s\n' "$report_sha"
}

record_report() {
  local status="$1"
  local category="$2"
  local report_sha="$3"
  "$RECORD_COMMAND" \
    --drill-id "$DRILL_ID" \
    --status "$status" \
    --started-at "$STARTED_AT" \
    --finished-at "$FINISHED_AT" \
    --report-sha "$report_sha" \
    --category "$category"
}

publish_failed_evidence_best_effort() {
  [[ "$REMOTE_REPORT_VERIFIED" == false && "$PUBLISHING_FAILURE" == false &&
     -n "$ROOT" && -d "$ROOT" && -f "$ROOT/.portfolio-restore-drill" &&
     -n "$REPORT_UPLOAD_COMMAND" && -n "$REPORT_VERIFY_COMMAND" && -n "$RECORD_COMMAND" ]] || return 0
  PUBLISHING_FAILURE=true
  local failure_report="$ROOT/failed-report.json"
  local failure_checksum="$ROOT/failed-report.sha256"
  write_report FAILED "$ERROR_CATEGORY" "$failure_report"
  local now
  now="$(date -u +%s)"
  ((CREDENTIAL_SUCCESS_DEADLINE_EPOCH > 0 && now < CREDENTIAL_SUCCESS_DEADLINE_EPOCH)) || return 0
  local report_sha
  report_sha="$(upload_and_verify_report "$failure_report" "$failure_checksum" 2>/dev/null)" || return 0
  REMOTE_REPORT_VERIFIED=true
  record_report FAILED "$ERROR_CATEGORY" "$report_sha" >/dev/null 2>&1 || true
}

stop_and_revoke_best_effort() {
  if [[ -n "$ROOT" && -f "$ROOT/.portfolio-restore-drill" &&
        -n "${RESTORE_DRILL_COS_CONFIG:-}" && -f "${RESTORE_DRILL_COS_CONFIG:-}" ]]; then
    "$DRILL_CLEANUP_COMMAND" \
      --drill-id "$DRILL_ID" \
      --prefix "drills/$DRILL_ID/" \
      --config "$RESTORE_DRILL_COS_CONFIG" \
      --root "$ROOT" \
      --remove-root false \
      --purge-remote false >/dev/null 2>&1 || true
  fi
  if [[ "$DRILL_CREDENTIALS_ACTIVE" == true ]]; then
    if [[ -x "$REVOKE_COMMAND" && -n "${RESTORE_DRILL_COS_CONFIG:-}" ]]; then
      "$REVOKE_COMMAND" --drill-id "$DRILL_ID" --config "$RESTORE_DRILL_COS_CONFIG" >/dev/null 2>&1 || true
    fi
    destroy_drill_secrets_best_effort
    DRILL_CREDENTIALS_ACTIVE=false
  fi
}

destroy_drill_secrets_best_effort() {
  local expected="/run/portfolio-restore-secrets/$DRILL_ID"
  [[ -n "$DRILL_ID" && "$SECRETS_DIRECTORY" == "$expected" &&
     -d "$expected" && ! -L "$expected" &&
     -f "$expected/.portfolio-restore-secrets" && ! -L "$expected/.portfolio-restore-secrets" &&
     "$(<"$expected/.portfolio-restore-secrets")" == "$DRILL_ID" ]] || return 0
  local secret
  while IFS= read -r -d '' secret; do
    [[ -f "$secret" && ! -L "$secret" ]] || continue
    chmod 0600 "$secret" 2>/dev/null || true
    : >"$secret" 2>/dev/null || true
    rm -f -- "$secret" 2>/dev/null || true
  done < <(find "$expected" -xdev -mindepth 1 -maxdepth 1 -type f -print0 2>/dev/null)
  rmdir -- "$expected" 2>/dev/null || true
}

on_exit() {
  local status=$?
  trap - EXIT HUP INT TERM
  set +e
  dispose_identity
  if ((status != 0)); then
    publish_failed_evidence_best_effort
    stop_and_revoke_best_effort
  fi
  exit "$status"
}

on_signal() {
  ERROR_CATEGORY=INTERRUPTED
  exit 130
}

finalize_success() {
  require_credential_deadline 'successful cleanup'
  local remove_root=true
  [[ "${RESTORE_PRESERVE_ROOT:-false}" == true ]] && remove_root=false
  "$DRILL_CLEANUP_COMMAND" \
    --drill-id "$DRILL_ID" \
    --prefix "drills/$DRILL_ID/" \
    --config "$RESTORE_DRILL_COS_CONFIG" \
    --root "$ROOT" \
    --remove-root "$remove_root" \
    --purge-remote true || die INTERNAL_FAILURE 'bounded isolated drill cleanup failed'
  if [[ "$DRILL_CREDENTIALS_ACTIVE" == true ]]; then
    "$REVOKE_COMMAND" --drill-id "$DRILL_ID" --config "$RESTORE_DRILL_COS_CONFIG" ||
      die INTERNAL_FAILURE 'short-lived drill credential disposal failed'
    DRILL_CREDENTIALS_ACTIVE=false
  fi
}

main() {
  STARTED_AT="$(utc_now)"
  START_WALL_EPOCH="$(date -u +%s)"
  START_MONOTONIC="$(monotonic_seconds)"
  trap on_exit EXIT
  trap on_signal HUP INT TERM

  sanitize_compose_environment
  sanitize_proxy_environment
  validate_arguments_and_environment "$@"
  validate_runtime_dependencies
  prepare_root
  acquire_and_validate_backup_set
  decrypt_artifacts
  restore_database
  resolve_media_closure
  validate_and_extract_local_media
  verify_media_and_routes
  verify_current_and_historical_content

  local now_monotonic
  now_monotonic="$(monotonic_seconds)"
  RTO_SECONDS=$((now_monotonic - START_MONOTONIC))
  ((RTO_SECONDS <= MAX_RTO_SECONDS)) || die RTO_EXCEEDED 'isolated recovery exceeds the 4-hour RTO'
  write_report SUCCEEDED NONE "$REPORT_FILE"
  require_credential_deadline 'remote report publication'
  local report_sha
  report_sha="$(upload_and_verify_report "$REPORT_FILE" "$REPORT_CHECKSUM_FILE")" ||
    die REMOTE_REPORT_FAILED 'independent remote drill report verification failed'
  REMOTE_REPORT_VERIFIED=true
  if ! record_report SUCCEEDED NONE "$report_sha"; then
    die PRODUCTION_RECORD_FAILED 'verified remote report could not be recorded in production'
  fi
  finalize_success

  printf '%s\n' 'Restore drill completed with independently verified report and production record'
}

main "$@"
