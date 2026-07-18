#!/usr/bin/env bash

# Shared fail-closed helpers for tracked restore adapters.  Callers own strict
# mode and traps.  This file deliberately contains no cloud-provider secrets.

set +x
umask 077
export LC_ALL=C

# Explicit --config files and local --resolve targets are the only reviewed
# network routing inputs.  Host proxy variables must never redirect drill COS
# or local verification traffic.
for adapter_proxy_name in \
  HTTP_PROXY HTTPS_PROXY ALL_PROXY NO_PROXY http_proxy https_proxy all_proxy no_proxy
do
  unset "$adapter_proxy_name"
done
unset adapter_proxy_name

adapter_fail() {
  printf 'Restore adapter failed: %s\n' "$1" >&2
  exit 1
}

while IFS='=' read -r adapter_environment_name _; do
  [[ "$adapter_environment_name" == RCLONE_* ]] || continue
  adapter_fail 'caller-supplied RCLONE_* environment is forbidden'
done < <(env)
unset adapter_environment_name

adapter_require_value() {
  local name="$1"
  [[ -n "${!name:-}" ]] || adapter_fail "$name is required"
  [[ "${!name}" != *$'\n'* && "${!name}" != *$'\r'* ]] ||
    adapter_fail "$name contains a control character"
}

adapter_is_uuid() {
  [[ "$1" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$ ]]
}

adapter_is_set_id() {
  [[ "$1" =~ ^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{12}$ ]]
}

adapter_is_release_id() {
  [[ "$1" =~ ^[0-9a-f]{12}-[0-9a-f]{12}$ ]]
}

adapter_is_sha256() {
  [[ "$1" =~ ^[0-9a-f]{64}$ ]]
}

adapter_safe_relative_path() {
  local value="$1"
  [[ -n "$value" && "$value" != /* && "$value" != *$'\\'* &&
     "$value" != *$'\n'* && "$value" != *$'\r'* && "$value" != *$'\t'* &&
     "$value" != *'|'* && ! "$value" =~ (^|/)\.\.?(/|$) &&
     ! "$value" =~ // && "$value" =~ ^[A-Za-z0-9._/-]+$ ]]
}

adapter_resolve_command() {
  local variable="$1"
  local fallback="$2"
  local value="${!variable:-}"
  if [[ -z "$value" ]]; then
    value="$(command -v -- "$fallback" 2>/dev/null || true)"
  elif [[ "$value" != */* ]]; then
    value="$(command -v -- "$value" 2>/dev/null || true)"
  fi
  [[ "$value" == /* && -f "$value" && ! -L "$value" && -x "$value" ]] ||
    adapter_fail "$variable must resolve to an executable regular file"
  printf -v "$variable" '%s' "$(realpath -e -- "$value")"
  export "${variable?}"
}

adapter_require_private_file() {
  local path="$1"
  local label="$2"
  [[ "$path" == /* && -f "$path" && ! -L "$path" ]] ||
    adapter_fail "$label must be an absolute regular file"
  local mode owner
  mode="$(stat -Lc '%a' -- "$path")" || adapter_fail "$label mode is unavailable"
  owner="$(stat -Lc '%u' -- "$path")" || adapter_fail "$label owner is unavailable"
  (((8#$mode & 0077) == 0)) || adapter_fail "$label must not be accessible by group or other"
  ((owner == EUID)) || adapter_fail "$label must be owned by the invoking operator"
}

adapter_require_private_directory() {
  local path="$1"
  local label="$2"
  [[ "$path" == /* && -d "$path" && ! -L "$path" ]] ||
    adapter_fail "$label must be an absolute regular directory"
  local mode owner
  mode="$(stat -Lc '%a' -- "$path")" || adapter_fail "$label mode is unavailable"
  owner="$(stat -Lc '%u' -- "$path")" || adapter_fail "$label owner is unavailable"
  (((8#$mode & 0077) == 0)) || adapter_fail "$label must not be accessible by group or other"
  ((owner == EUID)) || adapter_fail "$label must be owned by the invoking operator"
}

adapter_is_beneath() {
  local child="${1%/}"
  local parent="${2%/}"
  [[ "$child" == "$parent" || "$child" == "$parent/"* ]]
}

adapter_require_drill_context() {
  adapter_require_value RESTORE_ROOT
  adapter_require_value RESTORE_DRILL_ID
  adapter_is_uuid "$RESTORE_DRILL_ID" || adapter_fail 'RESTORE_DRILL_ID is invalid'
  local canonical
  canonical="$(realpath -e -- "$RESTORE_ROOT" 2>/dev/null || true)"
  [[ "$canonical" == /srv/portfolio-restore/* && "$canonical" != /srv/portfolio-restore ]] ||
    adapter_fail 'RESTORE_ROOT is outside the isolated restore base'
  RESTORE_ROOT="$canonical"
  export RESTORE_ROOT
  [[ -f "$RESTORE_ROOT/.portfolio-restore-drill" && ! -L "$RESTORE_ROOT/.portfolio-restore-drill" &&
     "$(<"$RESTORE_ROOT/.portfolio-restore-drill")" == "$RESTORE_DRILL_ID" ]] ||
    adapter_fail 'restore root sentinel does not match the drill ID'
  [[ "${RESTORE_COMPOSE_PROJECT_NAME:-}" == "portfolio-restore-$RESTORE_DRILL_ID" ]] ||
    adapter_fail 'Compose project is not exactly bound to the drill UUID'
}

adapter_require_disposed_identity() {
  [[ "${RESTORE_IDENTITY_DISPOSED:-false}" == true ]] ||
    adapter_fail 'offline age identity has not been disposed'
  local name
  for name in RESTORE_AGE_IDENTITY AGE_IDENTITY AGE_IDENTITIES AGE_SECRET_KEY AGE_KEY; do
    [[ -z "${!name:-}" ]] || adapter_fail 'an age identity value reached a post-decryption adapter'
  done
}

adapter_require_output_beneath_root() {
  local path="$1"
  local parent
  [[ "$path" == /* && ! -L "$path" ]] || adapter_fail 'adapter output path is invalid'
  parent="$(realpath -e -- "$(dirname -- "$path")" 2>/dev/null || true)"
  adapter_is_beneath "$parent" "$RESTORE_ROOT" || adapter_fail 'adapter output escaped the restore root'
}

adapter_parse_remote() {
  local value="$1"
  local label="$2"
  [[ "$value" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}:[a-z0-9][a-z0-9.-]{1,126}[a-z0-9](/[A-Za-z0-9._/-]+)?$ &&
     "$value" != */ && "$value" != *'..'* && "$value" != *//* ]] ||
    adapter_fail "$label is not a bounded rclone remote path"
}

adapter_rclone_copyto() {
  local config="$1"
  local source="$2"
  local destination="$3"
  "$RESTORE_RCLONE_COMMAND" --config "$config" copyto "$source" "$destination" \
    --retries 3 --low-level-retries 10 --contimeout 10s --timeout 5m
}

adapter_compose() {
  "$RESTORE_DOCKER_COMMAND" compose \
    --project-name "$RESTORE_COMPOSE_PROJECT_NAME" \
    -f "$RESTORE_COMPOSE_FILE" "$@"
}
