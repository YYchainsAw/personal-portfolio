#!/usr/bin/env bash

# Shared, fail-closed helpers for the independent backup and pruning services.
# This file is sourced; callers own `set -euo pipefail` and their traps.

set +x
umask 077
export LC_ALL=C

BACKUP_LIBRARY_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
readonly BACKUP_LIBRARY_DIRECTORY

backup_fail() {
  printf 'portfolio backup failed: %s\n' "$1" >&2
  exit 1
}

backup_note() {
  printf 'portfolio backup: %s\n' "$1" >&2
}

backup_require_value() {
  local name="$1"
  [[ -n "${!name:-}" ]] || backup_fail "$name is required"
}

backup_require_boolean() {
  local name="$1"
  backup_require_value "$name"
  [[ "${!name}" == true || "${!name}" == false ]] ||
    backup_fail "$name must be true or false"
}

backup_resolve_command() {
  local name="$1"
  local fallback="$2"
  local value="${!name:-}"
  if [[ -z "$value" ]]; then
    value="$(command -v "$fallback" 2>/dev/null)" ||
      backup_fail "$fallback is required"
  fi
  [[ "$value" == /* && ! -L "$value" && -f "$value" && -x "$value" ]] ||
    backup_fail "$name must name an executable absolute regular file"
  local owner mode
  owner="$(stat -Lc '%u' -- "$value")" || backup_fail "$name owner cannot be read"
  mode="$(stat -Lc '%a' -- "$value")" || backup_fail "$name mode cannot be read"
  [[ "$owner" == 0 ]] || backup_fail "$name must be owned by root"
  (( (8#$mode & 8#022) == 0 )) || backup_fail "$name must not be group- or world-writable"
  printf -v "$name" '%s' "$value"
  export "${name:?}"
}

backup_require_private_file() {
  local path="$1"
  local label="$2"
  [[ "$path" == /* && ! -L "$path" && -f "$path" ]] ||
    backup_fail "$label must be an absolute regular file"
  local mode owner
  mode="$(stat -Lc '%a' -- "$path")" || backup_fail "$label mode cannot be read"
  owner="$(stat -Lc '%u' -- "$path")" || backup_fail "$label owner cannot be read"
  (( (8#$mode & 8#077) == 0 )) || backup_fail "$label must not be accessible by group or other"
  [[ "$owner" == 0 ]] || backup_fail "$label must be owned by root"
}

backup_require_private_directory() {
  local path="$1"
  local label="$2"
  [[ "$path" == /* && ! -L "$path" && -d "$path" ]] ||
    backup_fail "$label must be an absolute regular directory"
  local mode owner
  mode="$(stat -Lc '%a' -- "$path")" || backup_fail "$label mode cannot be read"
  owner="$(stat -Lc '%u' -- "$path")" || backup_fail "$label owner cannot be read"
  (( (8#$mode & 8#077) == 0 )) || backup_fail "$label must not be accessible by group or other"
  [[ "$owner" == 0 ]] || backup_fail "$label must be owned by root"
}

backup_is_sha256() {
  [[ "$1" =~ ^[0-9a-f]{64}$ ]]
}

backup_is_uuid() {
  [[ "$1" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$ ]]
}

backup_is_rfc3339_utc() {
  [[ "$1" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]]
}

backup_is_set_id() {
  [[ "$1" =~ ^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{12}$ ]]
}

backup_safe_relative_path() {
  local value="$1"
  [[ -n "$value" && "$value" != /* && "$value" != *$'\\'* &&
     "$value" != *$'\n'* && "$value" != *$'\r'* && "$value" != *$'\t'* &&
     ! "$value" =~ (^|/)\.\.?(/|$) && ! "$value" =~ // &&
     "$value" =~ ^[A-Za-z0-9._/-]+$ ]]
}

backup_parse_remote() {
  local value="$1"
  local expected_bucket="$2"
  local label="$3"
  [[ "$value" =~ ^([A-Za-z0-9][A-Za-z0-9_.-]{0,63}):([a-z0-9][a-z0-9.-]{1,126}[a-z0-9])$ ]] ||
    backup_fail "$label must resolve to exactly one named remote and bucket root"
  [[ "${BASH_REMATCH[2]}" == "$expected_bucket" ]] ||
    backup_fail "$label bucket does not match its reviewed bucket identity"
}

backup_normalize_prefix() {
  backup_require_value BACKUP_PREFIX
  backup_safe_relative_path "$BACKUP_PREFIX" ||
    backup_fail 'BACKUP_PREFIX must be a canonical non-root relative path'
  [[ "$BACKUP_PREFIX" != . && "$BACKUP_PREFIX" != / ]] ||
    backup_fail 'BACKUP_PREFIX cannot select a bucket root'
  BACKUP_PREFIX="${BACKUP_PREFIX%/}"
  export BACKUP_PREFIX
}

backup_remote_path() {
  local namespace="$1"
  local relative="$2"
  case "$namespace" in
    sets|blobs|drill-reports|gc-reports) ;;
    *) backup_fail 'remote namespace is outside the reviewed allowlist' ;;
  esac
  backup_safe_relative_path "$relative" || backup_fail 'remote relative path is unsafe'
  printf '%s/%s/%s/%s\n' "$BACKUP_REMOTE" "$BACKUP_PREFIX" "$namespace" "$relative"
}

backup_remote_namespace_root() {
  local namespace="$1"
  case "$namespace" in
    sets|blobs|drill-reports|gc-reports) ;;
    *) backup_fail 'remote namespace is outside the reviewed allowlist' ;;
  esac
  printf '%s/%s/%s\n' "$BACKUP_REMOTE" "$BACKUP_PREFIX" "$namespace"
}

backup_set_remote_path() {
  local set_id="$1"
  local filename="$2"
  backup_is_set_id "$set_id" || backup_fail 'set ID is invalid'
  [[ "$filename" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ ]] ||
    backup_fail 'set artifact filename is invalid'
  backup_remote_path sets "$set_id/$filename"
}

backup_source_remote_path() {
  local bucket="$1"
  local region="$2"
  local object_key="$3"
  [[ "$bucket" == "$MEDIA_SOURCE_BUCKET" && "$region" == "$MEDIA_SOURCE_REGION" ]] ||
    backup_fail 'COS media row is outside the reviewed source bucket and region'
  backup_safe_relative_path "$object_key" || backup_fail 'COS media row has an unsafe object key'
  printf '%s/%s\n' "$MEDIA_SOURCE_RCLONE_REMOTE" "$object_key"
}

backup_assert_distinct_values() {
  local label="$1"
  shift
  local -A observed=()
  local value
  for value in "$@"; do
    [[ -n "$value" ]] || backup_fail "$label contains an empty value"
    [[ -z "${observed[$value]:-}" ]] || backup_fail "$label must all be distinct"
    observed["$value"]=1
  done
}

backup_validate_topology() {
  local mode="$1"
  local required=(
    BACKUP_REMOTE BACKUP_PREFIX BACKUP_DESTINATION_ACCOUNT_ID
    BACKUP_DESTINATION_BUCKET BACKUP_UPLOAD_RCLONE_CONFIG
    BACKUP_VERIFY_RCLONE_CONFIG BACKUP_PRUNE_RCLONE_CONFIG
    MEDIA_SOURCE_RCLONE_REMOTE MEDIA_SOURCE_RCLONE_CONFIG
    MEDIA_SOURCE_ACCOUNT_ID MEDIA_SOURCE_BUCKET MEDIA_SOURCE_REGION
    MEDIA_SOURCE_PRINCIPAL_ID BACKUP_UPLOAD_PRINCIPAL_ID
    BACKUP_VERIFY_PRINCIPAL_ID BACKUP_PRUNE_PRINCIPAL_ID
  )
  local name
  for name in "${required[@]}"; do
    backup_require_value "$name"
  done
  backup_normalize_prefix

  [[ "$BACKUP_DESTINATION_ACCOUNT_ID" =~ ^[A-Za-z0-9][A-Za-z0-9_.:@-]{1,127}$ ]] ||
    backup_fail 'backup destination account identity is invalid'
  [[ "$MEDIA_SOURCE_ACCOUNT_ID" =~ ^[A-Za-z0-9][A-Za-z0-9_.:@-]{1,127}$ ]] ||
    backup_fail 'media source account identity is invalid'
  [[ "$BACKUP_DESTINATION_BUCKET" =~ ^[a-z0-9][a-z0-9.-]{1,126}[a-z0-9]$ ]] ||
    backup_fail 'backup destination bucket is invalid'
  [[ "$MEDIA_SOURCE_BUCKET" =~ ^[a-z0-9][a-z0-9.-]{1,126}[a-z0-9]$ ]] ||
    backup_fail 'media source bucket is invalid'
  [[ "$MEDIA_SOURCE_REGION" =~ ^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$ ]] ||
    backup_fail 'media source region is invalid'

  backup_parse_remote "$BACKUP_REMOTE" "$BACKUP_DESTINATION_BUCKET" BACKUP_REMOTE
  backup_parse_remote "$MEDIA_SOURCE_RCLONE_REMOTE" "$MEDIA_SOURCE_BUCKET" MEDIA_SOURCE_RCLONE_REMOTE
  [[ "$BACKUP_DESTINATION_ACCOUNT_ID@$BACKUP_DESTINATION_BUCKET" != "$MEDIA_SOURCE_ACCOUNT_ID@$MEDIA_SOURCE_BUCKET" ]] ||
    backup_fail 'media source and backup destination must be independent accounts or buckets'

  backup_assert_distinct_values 'rclone configuration paths' \
    "$MEDIA_SOURCE_RCLONE_CONFIG" \
    "$BACKUP_UPLOAD_RCLONE_CONFIG" \
    "$BACKUP_VERIFY_RCLONE_CONFIG" \
    "$BACKUP_PRUNE_RCLONE_CONFIG"
  backup_assert_distinct_values 'rclone principal identities' \
    "$MEDIA_SOURCE_PRINCIPAL_ID" \
    "$BACKUP_UPLOAD_PRINCIPAL_ID" \
    "$BACKUP_VERIFY_PRINCIPAL_ID" \
    "$BACKUP_PRUNE_PRINCIPAL_ID"

  case "$mode" in
    nightly)
      backup_require_private_file "$MEDIA_SOURCE_RCLONE_CONFIG" 'media source rclone config'
      backup_require_private_file "$BACKUP_UPLOAD_RCLONE_CONFIG" 'backup uploader rclone config'
      backup_require_private_file "$BACKUP_VERIFY_RCLONE_CONFIG" 'backup verifier rclone config'
      ;;
    prune)
      backup_require_private_file "$BACKUP_PRUNE_RCLONE_CONFIG" 'backup pruner rclone config'
      ;;
    *) backup_fail 'backup topology mode is invalid' ;;
  esac
}

backup_sha256_file() {
  local path="$1"
  [[ ! -L "$path" && -f "$path" ]] || backup_fail 'checksum input is not a regular file'
  sha256sum -- "$path" | awk '{print $1}'
}

backup_file_size() {
  local path="$1"
  stat -Lc '%s' -- "$path"
}

backup_verify_file() {
  local path="$1"
  local expected_size="$2"
  local expected_sha="$3"
  [[ "$expected_size" =~ ^(0|[1-9][0-9]*)$ ]] || return 1
  backup_is_sha256 "$expected_sha" || return 1
  [[ ! -L "$path" && -f "$path" ]] || return 1
  [[ "$(backup_file_size "$path")" == "$expected_size" ]] || return 1
  [[ "$(backup_sha256_file "$path")" == "$expected_sha" ]]
}

backup_constant_time_equal() {
  local left="$1"
  local right="$2"
  local left_hash right_hash difference=0 index
  left_hash="$(printf '%s' "$left" | sha256sum | awk '{print $1}')"
  right_hash="$(printf '%s' "$right" | sha256sum | awk '{print $1}')"
  for ((index = 0; index < ${#left_hash}; index++)); do
    [[ "${left_hash:index:1}" == "${right_hash:index:1}" ]] || difference=1
  done
  ((difference == 0))
}

backup_resolve_local_media_volume() {
  backup_require_value PORTFOLIO_LOCAL_VOLUME_NAME
  backup_require_value PORTFOLIO_LOCAL_HOST_ROOT
  backup_require_value PORTFOLIO_LOCAL_VOLUME_ID
  [[ "$PORTFOLIO_LOCAL_VOLUME_NAME" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$ ]] ||
    backup_fail 'PORTFOLIO_LOCAL_VOLUME_NAME is invalid'
  [[ "$PORTFOLIO_LOCAL_HOST_ROOT" == /* && ! -L "$PORTFOLIO_LOCAL_HOST_ROOT" &&
     -d "$PORTFOLIO_LOCAL_HOST_ROOT" ]] ||
    backup_fail 'PORTFOLIO_LOCAL_HOST_ROOT is not a regular absolute directory'
  PORTFOLIO_LOCAL_HOST_ROOT="$(realpath -e -- "$PORTFOLIO_LOCAL_HOST_ROOT")"

  local identity volume_name volume_mountpoint extra
  if ! identity="$("$BACKUP_DOCKER_COMMAND" volume inspect \
      --format '{{.Name}}|{{.Mountpoint}}' "$PORTFOLIO_LOCAL_VOLUME_NAME" 2>/dev/null)"; then
    backup_fail 'Local Docker volume cannot be inspected for backup'
  fi
  IFS='|' read -r volume_name volume_mountpoint extra <<<"$identity"
  [[ "$volume_name" == "$PORTFOLIO_LOCAL_VOLUME_NAME" &&
     "$volume_mountpoint" == /* && -z "${extra:-}" &&
     ! -L "$volume_mountpoint" && -d "$volume_mountpoint" ]] ||
    backup_fail 'Local Docker volume identity is ambiguous or mismatched'
  volume_mountpoint="$(realpath -e -- "$volume_mountpoint")"
  [[ "$volume_mountpoint" == "$PORTFOLIO_LOCAL_HOST_ROOT" ]] ||
    backup_fail 'Local Docker volume Mountpoint does not match PORTFOLIO_LOCAL_HOST_ROOT'

  local marker="$volume_mountpoint/.portfolio-volume-id"
  [[ ! -L "$marker" && -f "$marker" ]] ||
    backup_fail 'Local volume identity marker is missing, replaced, or linked'
  local marker_mode root_uid marker_uid recorded
  marker_mode="$(stat -Lc '%a' -- "$marker")"
  root_uid="$(stat -Lc '%u' -- "$volume_mountpoint")"
  marker_uid="$(stat -Lc '%u' -- "$marker")"
  [[ "$marker_mode" == 600 && "$marker_uid" == "$root_uid" ]] ||
    backup_fail 'Local volume identity marker is not owner-only'
  [[ "$(wc -l <"$marker")" -eq 1 ]] || backup_fail 'Local volume identity marker is malformed'
  recorded="$(tr -d '\r\n' <"$marker")"
  backup_constant_time_equal "$recorded" "$PORTFOLIO_LOCAL_VOLUME_ID" ||
    backup_fail 'Local volume identity does not match the protected value'

  LOCAL_MEDIA_ROOT="$volume_mountpoint"
  export LOCAL_MEDIA_ROOT PORTFOLIO_LOCAL_HOST_ROOT
}

backup_rclone() {
  local config="$1"
  shift
  "$BACKUP_RCLONE_COMMAND" --config "$config" "$@"
}

backup_upload_immutable() {
  local source="$1"
  local remote="$2"
  backup_rclone "$BACKUP_UPLOAD_RCLONE_CONFIG" copyto --immutable -- "$source" "$remote"
}

backup_verify_download() {
  local remote="$1"
  local destination="$2"
  backup_rclone "$BACKUP_VERIFY_RCLONE_CONFIG" copyto -- "$remote" "$destination"
}

backup_source_download() {
  local remote="$1"
  local destination="$2"
  backup_rclone "$MEDIA_SOURCE_RCLONE_CONFIG" copyto -- "$remote" "$destination"
}

backup_canonicalize_json() {
  local source="$1"
  local destination="$2"
  jq -S -c . "$source" >"$destination"
}

backup_create_uuid() {
  local raw value
  raw="$("$BACKUP_OPENSSL_COMMAND" rand -hex 16 2>/dev/null)" ||
    backup_fail 'cannot create a maintenance run ID'
  [[ "$raw" =~ ^[0-9a-f]{32}$ ]] || backup_fail 'random UUID material is invalid'
  value="${raw:0:8}-${raw:8:4}-4${raw:13:3}-8${raw:17:3}-${raw:20:12}"
  backup_is_uuid "$value" || backup_fail 'generated maintenance run ID is invalid'
  printf '%s\n' "$value"
}

backup_random_hex() {
  local byte_count="$1"
  [[ "$byte_count" =~ ^[1-9][0-9]{0,2}$ ]] || backup_fail 'random byte count is invalid'
  local value
  value="$("$BACKUP_OPENSSL_COMMAND" rand -hex "$byte_count" 2>/dev/null)" ||
    backup_fail 'cannot create random backup identity material'
  [[ "$value" =~ ^[0-9a-f]+$ && "${#value}" -eq $((byte_count * 2)) ]] ||
    backup_fail 'random backup identity material is invalid'
  printf '%s\n' "$value"
}

backup_record_maintenance() {
  local run_id="$1"
  local run_type="$2"
  local status="$3"
  local started_at="$4"
  local finished_at="$5"
  local artifact_sha="$6"
  local category="$7"

  backup_is_uuid "$run_id" || backup_fail 'maintenance run ID is invalid'
  [[ "$run_type" == DATABASE_BACKUP || "$run_type" == MEDIA_BACKUP ]] ||
    backup_fail 'maintenance run type is invalid'
  [[ "$status" == SUCCEEDED || "$status" == FAILED ]] ||
    backup_fail 'maintenance status is invalid'
  backup_is_rfc3339_utc "$started_at" || backup_fail 'maintenance start time is invalid'
  backup_is_rfc3339_utc "$finished_at" || backup_fail 'maintenance finish time is invalid'
  if [[ "$status" == SUCCEEDED ]]; then
    backup_is_sha256 "$artifact_sha" || backup_fail 'successful maintenance checksum is invalid'
    [[ "$category" == NONE ]] || backup_fail 'successful maintenance category is invalid'
  else
    [[ -z "$artifact_sha" ]] || backup_fail 'failed maintenance cannot claim an artifact checksum'
    [[ "$category" =~ ^(CONFIGURATION_FAILED|SNAPSHOT_FAILED|DATABASE_FAILED|MEDIA_FAILED|UPLOAD_FAILED|VERIFY_FAILED|MAINTENANCE_WRITE_FAILED|NOTIFICATION_FAILED|INTERNAL_FAILED)$ ]] ||
      backup_fail 'failure category is not allowlisted'
  fi

  "$BACKUP_POSTGRES_CLIENT_COMMAND" psql -A -t -q --set ON_ERROR_STOP=1 \
    --set "run_id=$run_id" \
    --set "run_type=$run_type" \
    --set "status=$status" \
    --set "started_at=$started_at" \
    --set "finished_at=$finished_at" \
    --set "artifact_sha=$artifact_sha" \
    --set "error_category=$category" \
    <"$BACKUP_LIBRARY_DIRECTORY/record-maintenance.sql" >/dev/null 2>&1
}

backup_initialize_commands() {
  BACKUP_POSTGRES_CLIENT_COMMAND="${BACKUP_POSTGRES_CLIENT_COMMAND:-$BACKUP_LIBRARY_DIRECTORY/postgres-client.sh}"
  BACKUP_MEDIA_TAR_COMMAND="${BACKUP_MEDIA_TAR_COMMAND:-$BACKUP_LIBRARY_DIRECTORY/validate-media-tar.py}"
  BACKUP_NOTIFY_COMMAND="${BACKUP_NOTIFY_COMMAND:-$BACKUP_LIBRARY_DIRECTORY/notify-failure.sh}"
  backup_resolve_command BACKUP_POSTGRES_CLIENT_COMMAND postgres-client.sh
  backup_resolve_command BACKUP_MEDIA_TAR_COMMAND validate-media-tar.py
  backup_resolve_command BACKUP_NOTIFY_COMMAND notify-failure.sh
  backup_resolve_command BACKUP_DOCKER_COMMAND docker
  backup_resolve_command BACKUP_RCLONE_COMMAND rclone
  backup_resolve_command BACKUP_AGE_COMMAND age
  backup_resolve_command BACKUP_DATE_COMMAND date
  backup_resolve_command BACKUP_OPENSSL_COMMAND openssl
}
