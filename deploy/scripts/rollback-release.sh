#!/usr/bin/env bash
set -euo pipefail
set +x
umask 077

export LC_ALL=C

SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=deploy/scripts/switch-journal.sh
source "$SCRIPT_DIRECTORY/switch-journal.sh"

PORTFOLIO_ROOT="${PORTFOLIO_ROOT:-/opt/portfolio}"
PORTFOLIO_ETC_ROOT="${PORTFOLIO_ETC_ROOT:-/etc/portfolio}"
RELEASE_ENV=''
NGINX_ENV=''
CURRENT_RELEASE=''
CURRENT_DIRECTORY=''
ORIGINAL_PREVIOUS_RELEASE=''
TARGET_RELEASE=''
TARGET_DIRECTORY=''
OLD_ENV_COPY=''
OLD_ADMIN_TARGET=''
OLD_OPS_TARGET=''
ENV_SWITCHED=false
ADMIN_SWITCHED=false
OPS_SWITCHED=false
MARKERS_SWITCHED=false
ROLLBACK_IN_PROGRESS=false
ROLLBACK_SUCCEEDED=false
RECOVERY_DIAGNOSTIC=''

fail() {
  printf 'portfolio rollback failed: %s\n' "$1" >&2
  exit 1
}

note() {
  printf 'portfolio rollback: %s\n' "$1" >&2
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "$1 is required"
}

validate_root_directory() {
  local path="$1" label="$2" mode parent
  [[ "$path" == /* && "$path" != / && -d "$path" && ! -L "$path" ]] ||
    fail "$label is missing, linked, or not absolute"
  [[ "$(realpath -e -- "$path")" == "$path" && "$(stat -Lc '%u' -- "$path")" == 0 ]] ||
    fail "$label is non-canonical or not owned by root"
  mode="$(stat -Lc '%a' -- "$path")"
  (( (8#$mode & 8#022) == 0 )) || fail "$label is writable outside root"
  parent="$(dirname -- "$path")"
  [[ -d "$parent" && ! -L "$parent" && "$(realpath -e -- "$parent")" == "$parent" &&
     "$(stat -Lc '%u' -- "$parent")" == 0 ]] || fail "$label parent is unsafe"
  mode="$(stat -Lc '%a' -- "$parent")"
  (( (8#$mode & 8#022) == 0 )) || fail "$label parent is writable outside root"
}

validate_root_state_file() {
  local path="$1" label="$2" mode
  [[ -f "$path" && ! -L "$path" && "$(stat -Lc '%u:%h' -- "$path")" == 0:1 ]] ||
    fail "$label is not a root-owned single-link regular file"
  mode="$(stat -Lc '%a' -- "$path")"
  (( (8#$mode & 8#022) == 0 )) || fail "$label is writable outside root"
}

revalidate_deploy_lock_binding() {
  local lock_file="$1" fd="$2" requested_parent parent parent_mode lock_mode
  requested_parent="$(dirname -- "$lock_file")"
  [[ -d "$requested_parent" && ! -L "$requested_parent" ]] ||
    fail 'deploy lock changed while waiting: parent is missing or linked'
  parent="$(realpath -e -- "$requested_parent")"
  [[ "$parent" == "$requested_parent" && "$parent" != / &&
     "$(stat -Lc '%u:%g' -- "$parent")" == 0:0 ]] ||
    fail 'deploy lock changed while waiting: parent is unsafe'
  parent_mode="$(stat -Lc '%a' -- "$parent")"
  if [[ "$lock_file" == /run/lock/portfolio/deploy.lock ]]; then
    [[ "$parent_mode" == 700 ]] ||
      fail 'deploy lock changed while waiting: default parent mode is not 0700'
  else
    (( (8#$parent_mode & 8#022) == 0 )) ||
      fail 'deploy lock changed while waiting: parent is writable outside root'
  fi
  [[ -f "$lock_file" && ! -L "$lock_file" &&
     "$(stat -Lc '%u:%g:%h' -- "$lock_file")" == 0:0:1 ]] ||
    fail 'deploy lock changed while waiting: path is not a root-owned single-link file'
  lock_mode="$(stat -Lc '%a' -- "$lock_file")"
  (( (8#$lock_mode & 8#022) == 0 )) ||
    fail 'deploy lock changed while waiting: path is writable outside root'
  [[ "$(stat -Lc '%d:%i' -- "$lock_file")" == \
     "$(stat -Lc '%d:%i' -- "/proc/self/fd/$fd")" ]] ||
    fail 'deploy lock changed while waiting: descriptor identity differs'
}

acquire_deploy_lock() {
  local lock_file="$1" parent requested_parent parent_mode lock_mode
  [[ "$lock_file" == /* && "$lock_file" != / &&
     "$lock_file" != *$'\n'* && "$lock_file" != *$'\r'* ]] ||
    fail 'deploy lock path is invalid'
  requested_parent="$(dirname -- "$lock_file")"
  [[ -d "$requested_parent" && ! -L "$requested_parent" ]] ||
    fail 'deploy lock parent is missing or linked'
  parent="$(realpath -e -- "$requested_parent")"
  [[ "$parent" == "$requested_parent" && "$parent" != / &&
     "$(stat -Lc '%u:%g' -- "$parent")" == 0:0 ]] ||
    fail 'deploy lock parent is non-canonical or not root:root'
  parent_mode="$(stat -Lc '%a' -- "$parent")"
  if [[ "$lock_file" == /run/lock/portfolio/deploy.lock ]]; then
    [[ "$parent_mode" == 700 ]] || fail 'default deploy lock parent mode is not 0700'
  else
    (( (8#$parent_mode & 8#022) == 0 )) ||
      fail 'deploy lock parent is writable outside root'
  fi
  if [[ ! -e "$lock_file" && ! -L "$lock_file" ]]; then
    (umask 077; set -C; : >"$lock_file") 2>/dev/null || true
  fi
  [[ -f "$lock_file" && ! -L "$lock_file" &&
     "$(stat -Lc '%u:%g:%h' -- "$lock_file")" == 0:0:1 ]] ||
    fail 'deploy lock is not a root-owned single-link regular file'
  lock_mode="$(stat -Lc '%a' -- "$lock_file")"
  (( (8#$lock_mode & 8#022) == 0 )) || fail 'deploy lock is writable outside root'
  exec 9<>"$lock_file"
  [[ "$(stat -Lc '%d:%i' -- "$lock_file")" == "$(stat -Lc '%d:%i' -- /proc/self/fd/9)" ]] ||
    fail 'deploy lock descriptor identity changed'
  flock -x 9
  revalidate_deploy_lock_binding "$lock_file" 9
  export PORTFOLIO_DEPLOY_LOCK_HELD=true PORTFOLIO_DEPLOY_LOCK_FD=9
}

validate_protected_file() {
  local path="$1" expected_mode="$2" label="$3"
  local owner group mode expected_group="${PORTFOLIO_DEPLOY_GROUP:-portfolio-deploy}"
  [[ "$path" == /* && -f "$path" && ! -L "$path" ]] ||
    fail "$label is missing, linked, or not absolute"
  owner="$(stat -Lc '%U' -- "$path")"
  group="$(stat -Lc '%G' -- "$path")"
  mode="$(stat -Lc '%a' -- "$path")"
  [[ "$owner" == root && "$group" == "$expected_group" && "$mode" == "$expected_mode" ]] ||
    fail "$label owner, group, or mode is unsafe"
}

is_release_id() {
  [[ "$1" =~ ^[0-9a-f]{12}-[0-9a-f]{12}$ ]]
}

is_beneath() {
  local child="$1"
  local parent="${2%/}"
  [[ "$child" == "$parent/"* ]]
}

read_single_line() {
  local file="$1"
  local label="$2"
  [[ -f "$file" && ! -L "$file" ]] || fail "$label is missing or linked"
  local value=''
  IFS= read -r value <"$file" || true
  [[ -n "$value" && "$(wc -l <"$file" | tr -d '[:space:]')" == '1' ]] ||
    fail "$label must contain exactly one non-empty line"
  printf '%s\n' "$value"
}

env_value() {
  local file="$1"
  local key="$2"
  local value
  value="$(awk -F= -v key="$key" '
    $1 == key { count += 1; value = substr($0, index($0, "=") + 1) }
    END { if (count != 1 || value == "") exit 1; print value }
  ' "$file")" || fail "protected environment has a missing or duplicate $key"
  [[ "$value" != *$'\n'* && "$value" != *$'\r'* ]] ||
    fail "protected environment value $key is malformed"
  printf '%s\n' "$value"
}

validate_release() {
  local release_id="$1"
  local directory="$PORTFOLIO_ROOT/releases/$release_id"
  [[ -d "$directory" && ! -L "$directory" ]] || fail "release is missing: $release_id"
  directory="$(realpath -e -- "$directory")"
  [[ "$directory" == "$PORTFOLIO_ROOT/releases/$release_id" ]] ||
    fail 'release directory escaped the reviewed root'
  [[ -f "$directory/release.json" && ! -L "$directory/release.json" ]] ||
    fail 'release metadata is missing or linked'
  jq -e --arg id "$release_id" '
    .releaseId == $id and
    .apiImageTag == ("portfolio-api:" + $id) and
    (.apiImageId | type == "string" and test("^sha256:[0-9a-f]{64}$")) and
    .postgresImageTag == ("portfolio-postgres-17:" + $id) and
    (.postgresImageRef | type == "string" and
      test("^postgres:17-bookworm@sha256:[0-9a-f]{64}$")) and
    (.postgresImageId | type == "string" and test("^sha256:[0-9a-f]{64}$"))
  ' "$directory/release.json" >/dev/null || fail 'release metadata identity is invalid'
  [[ -f "$directory/admin/index.html" && ! -L "$directory/admin/index.html" ]] ||
    fail 'release administrator tree is missing'
  [[ -f "$directory/ops/deploy/docker-compose.prod.yml" &&
     ! -L "$directory/ops/deploy/docker-compose.prod.yml" ]] ||
    fail 'release production Compose file is missing'
  [[ -f "$directory/ops/deploy/scripts/smoke.sh" &&
     ! -L "$directory/ops/deploy/scripts/smoke.sh" ]] ||
    fail 'release smoke script is missing'
  [[ -f "$directory/public-assets/.vite/manifest.json" &&
     ! -L "$directory/public-assets/.vite/manifest.json" &&
     -d "$directory/public-assets/assets" &&
     ! -L "$directory/public-assets/assets" ]] ||
    fail 'release Vite asset contract is missing or linked'
  if find -P "$directory" -type l -print -quit | grep -q .; then
    fail 'release tree contains a symbolic link'
  fi
  printf '%s\n' "$directory"
}

config_digest_from_manifest() {
  local manifest_json="$1" config_path
  config_path="$(printf '%s' "$manifest_json" | jq -er '
    if type == "array" and length == 1 and
       (.[0].Config | type == "string") and
       (.[0].Layers | type == "array" and length > 0)
    then .[0].Config else error("invalid docker save manifest") end
  ')" || fail 'invalid docker save manifest'
  if [[ "$config_path" =~ ^([0-9a-f]{64})\.json$ ]]; then
    printf '%s\n' "${BASH_REMATCH[1]}"
  elif [[ "$config_path" =~ ^blobs/sha256/([0-9a-f]{64})$ ]]; then
    printf '%s\n' "${BASH_REMATCH[1]}"
  else
    fail 'docker save manifest Config path is invalid'
  fi
}

portable_image_id() {
  local reference="$1"
  local manifest config_sha
  manifest="$(docker save "$reference" | tar -xOf - manifest.json)" ||
    fail "image is unavailable: $reference"
  config_sha="$(config_digest_from_manifest "$manifest")"
  printf 'sha256:%s\n' "$config_sha"
}

require_image_identity() {
  local reference="$1" expected_id="$2" label="$3" actual_id
  actual_id="$(portable_image_id "$reference")"
  [[ "$actual_id" == "$expected_id" ]] || fail "$label image identity differs from release.json"
}

write_release_env() {
  local release_id="$1"
  local directory="$2"
  local api_image postgres_image temporary deploy_group
  api_image="$(jq -r '.apiImageTag' "$directory/release.json")"
  postgres_image="$(jq -r '.postgresImageTag' "$directory/release.json")"
  [[ "$api_image" == "portfolio-api:$release_id" ]] || fail 'API image tag is invalid'
  [[ "$postgres_image" == "portfolio-postgres-17:$release_id" ]] ||
    fail 'PostgreSQL release-local image tag is invalid'
  temporary="$(mktemp "$PORTFOLIO_ETC_ROOT/.release.env.XXXXXX")"
  printf 'POSTGRES_IMAGE=%s\nPORTFOLIO_IMAGE=%s\nPORTFOLIO_RELEASE_ID=%s\n' \
    "$postgres_image" "$api_image" "$release_id" >"$temporary"
  chmod 0640 "$temporary"
  deploy_group="${PORTFOLIO_DEPLOY_GROUP:-portfolio-deploy}"
  chown "root:$deploy_group" "$temporary"
  sync -f "$temporary"
  mv -fT -- "$temporary" "$RELEASE_ENV"
  sync -f "$PORTFOLIO_ETC_ROOT"
}

compose_up_api() {
  local directory="$1"
  docker compose \
    --env-file "$RELEASE_ENV" \
    -f "$directory/ops/deploy/docker-compose.prod.yml" \
    up -d --no-deps portfolio-api
}

wait_for_api() {
  local directory="$1" timeout="${2:-${PORTFOLIO_ROLLBACK_READY_TIMEOUT_SECONDS:-180}}"
  local deadline=$((SECONDS + timeout))
  local container
  while ((SECONDS < deadline)); do
    container="$(docker compose \
      --env-file "$RELEASE_ENV" \
      -f "$directory/ops/deploy/docker-compose.prod.yml" \
      ps -q portfolio-api 2>/dev/null || true)"
    if [[ -n "$container" &&
          "$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' \
            "$container" 2>/dev/null || true)" == healthy ]]; then
      return 0
    fi
    sleep 2
  done
  return 1
}

verify_release_images() {
  local directory="$1" release_id="$2"
  local api_id postgres_id api_tag postgres_tag
  api_id="$(jq -r '.apiImageId' "$directory/release.json")"
  postgres_id="$(jq -r '.postgresImageId' "$directory/release.json")"
  api_tag="$(jq -r '.apiImageTag' "$directory/release.json")"
  postgres_tag="$(jq -r '.postgresImageTag' "$directory/release.json")"
  [[ "$api_tag" == "portfolio-api:$release_id" &&
     "$postgres_tag" == "portfolio-postgres-17:$release_id" ]] ||
    fail 'release-local image tags are invalid'
  require_image_identity "$api_tag" "$api_id" API
  require_image_identity "$postgres_tag" "$postgres_id" PostgreSQL
}

release_smoke_asset_contract() {
  local directory="$1" manifest="$directory/public-assets/.vite/manifest.json"
  local candidate source canonical digest
  [[ -f "$manifest" && ! -L "$manifest" ]] || return 1
  while IFS= read -r candidate; do
    [[ "$candidate" =~ ^assets/[A-Za-z0-9._-]+$ ]] || continue
    source="$directory/public-assets/$candidate"
    [[ -f "$source" && ! -L "$source" ]] || continue
    canonical="$(realpath -e -- "$source")" || continue
    [[ "$canonical" == "$directory/public-assets/assets/"* ]] || continue
    digest="$(sha256sum -- "$canonical" | awk '{print $1}')"
    [[ "$digest" =~ ^[0-9a-f]{64}$ ]] || continue
    printf '%s\t%s\n' "$candidate" "$digest"
    return 0
  done < <(jq -er '
    [to_entries[] | .value |
      (.file? // empty), (.css[]? // empty), (.assets[]? // empty)] |
    map(select(type == "string")) | unique | sort[]
  ' "$manifest")
  return 1
}

run_edge_smoke() {
  local directory="$1" mode="$2"
  shift 2
  local contract asset_path asset_sha
  contract="$(release_smoke_asset_contract "$directory")" || return 1
  IFS=$'\t' read -r asset_path asset_sha <<<"$contract"
  [[ -n "$asset_path" && "$asset_sha" =~ ^[0-9a-f]{64}$ ]] || return 1
  PORTFOLIO_SMOKE_ASSET_PATH="$asset_path" \
    PORTFOLIO_SMOKE_ASSET_SHA256="$asset_sha" \
    bash "$directory/ops/deploy/scripts/smoke.sh" "$mode" "$@"
}

switch_admin() {
  local directory="$1"
  local temporary="$PORTFOLIO_ROOT/.current-admin.$$.tmp"
  [[ ! -e "$temporary" && ! -L "$temporary" ]] || fail 'temporary admin link already exists'
  ln -s -- "$directory/admin" "$temporary"
  mv -fT -- "$temporary" "$PORTFOLIO_ROOT/current-admin"
  sync -f "$PORTFOLIO_ROOT"
}

switch_ops() {
  local directory="$1"
  local temporary="$PORTFOLIO_ROOT/.current-ops.$$.tmp"
  [[ ! -e "$temporary" && ! -L "$temporary" ]] || fail 'temporary operations link already exists'
  ln -s -- "$directory/ops" "$temporary"
  mv -fT -- "$temporary" "$PORTFOLIO_ROOT/current-ops"
  sync -f "$PORTFOLIO_ROOT"
}

nginx_test_and_reload() {
  local nginx_bin nginx_prefix nginx_conf
  nginx_bin="$(env_value "$NGINX_ENV" NGINX_BIN)"
  nginx_prefix="$(env_value "$NGINX_ENV" NGINX_PREFIX)"
  nginx_conf="$(env_value "$NGINX_ENV" NGINX_CONF)"
  nginx_bin="$(realpath -e -- "$nginx_bin")"
  nginx_prefix="$(realpath -e -- "$nginx_prefix")/"
  nginx_conf="$(realpath -e -- "$nginx_conf")"
  [[ -x "$nginx_bin" && "$nginx_bin" == "$nginx_prefix"* &&
     "$nginx_conf" == "$nginx_prefix"* ]] || fail 'BaoTa Nginx paths are invalid'
  "$nginx_bin" -p "$nginx_prefix" -c "$nginx_conf" -t
  "$nginx_bin" -p "$nginx_prefix" -c "$nginx_conf" -s reload
}

write_marker() {
  local name="$1"
  local value="$2"
  local temporary
  is_release_id "$value" || fail "cannot write invalid $name marker"
  temporary="$(mktemp "$PORTFOLIO_ROOT/.$name.XXXXXX")"
  printf '%s\n' "$value" >"$temporary"
  chmod 0640 "$temporary"
  sync -f "$temporary"
  mv -fT -- "$temporary" "$PORTFOLIO_ROOT/$name"
  sync -f "$PORTFOLIO_ROOT"
}

restore_release_environment() {
  local restore_env=''
  [[ -f "$OLD_ENV_COPY" && ! -L "$OLD_ENV_COPY" ]] || return 1
  restore_env="$(mktemp "$PORTFOLIO_ETC_ROOT/.release.env.restore.XXXXXX")" || return 1
  cp -- "$OLD_ENV_COPY" "$restore_env" || { rm -f -- "$restore_env"; return 1; }
  chmod 0640 "$restore_env" || { rm -f -- "$restore_env"; return 1; }
  chown "root:${PORTFOLIO_DEPLOY_GROUP:-portfolio-deploy}" "$restore_env" || {
    rm -f -- "$restore_env"
    return 1
  }
  sync -f "$restore_env" || { rm -f -- "$restore_env"; return 1; }
  mv -fT -- "$restore_env" "$RELEASE_ENV" || { rm -f -- "$restore_env"; return 1; }
  sync -f "$PORTFOLIO_ETC_ROOT"
}

restore_admin_pointer() {
  local recovery_link="$PORTFOLIO_ROOT/.current-admin.recover.$$.tmp"
  [[ -n "$OLD_ADMIN_TARGET" ]] || return 1
  rm -f -- "$recovery_link" || return 1
  ln -s -- "$OLD_ADMIN_TARGET" "$recovery_link" || return 1
  mv -fT -- "$recovery_link" "$PORTFOLIO_ROOT/current-admin" || {
    rm -f -- "$recovery_link"
    return 1
  }
  sync -f "$PORTFOLIO_ROOT"
}

restore_ops_pointer() {
  local recovery_link="$PORTFOLIO_ROOT/.current-ops.recover.$$.tmp"
  [[ -n "$OLD_OPS_TARGET" ]] || return 1
  rm -f -- "$recovery_link" || return 1
  ln -s -- "$OLD_OPS_TARGET" "$recovery_link" || return 1
  mv -fT -- "$recovery_link" "$PORTFOLIO_ROOT/current-ops" || {
    rm -f -- "$recovery_link"
    return 1
  }
  sync -f "$PORTFOLIO_ROOT"
}

run_original_api_smoke() {
  bash "$CURRENT_DIRECTORY/ops/deploy/scripts/smoke.sh" \
    api-local --base-url http://127.0.0.1:18080
}

run_original_nginx_smoke() {
  local nginx_local_port
  nginx_local_port="${NGINX_LOCAL_PORT:-$(env_value "$NGINX_ENV" NGINX_LOCAL_PORT)}"
  [[ "$nginx_local_port" =~ ^[1-9][0-9]{0,4}$ ]] || return 1
  ((10#$nginx_local_port <= 65535)) || return 1
  case "$nginx_local_port" in
    80|443|18080) return 1 ;;
  esac
  run_edge_smoke "$CURRENT_DIRECTORY" nginx-local \
    --resolve "yychainsaw.xyz:$nginx_local_port:127.0.0.1"
}

prepare_recovery_diagnostic() {
  local result_root="$PORTFOLIO_ROOT/rollback-results"
  install -d -m 0750 -- "$result_root" || return 1
  RECOVERY_DIAGNOSTIC="$result_root/$TARGET_RELEASE.rollback.log"
  printf 'schemaVersion=1\ntargetReleaseId=%s\n' "$TARGET_RELEASE" >"$RECOVERY_DIAGNOSTIC" ||
    return 1
  chmod 0640 "$RECOVERY_DIAGNOSTIC"
}

recovery_record() {
  [[ -n "$RECOVERY_DIAGNOSTIC" ]] || return 0
  printf '%s=%s\n' "$1" "$2" >>"$RECOVERY_DIAGNOSTIC"
}

recovery_step() {
  local label="$1"
  shift
  if ("$@") >/dev/null 2>&1; then
    recovery_record "$label" SUCCEEDED || return 1
    return 0
  fi
  recovery_record "$label" FAILED || true
  return 1
}

write_rollback_result() {
  local status="$1" result_root="$PORTFOLIO_ROOT/rollback-results" temporary
  case "$status" in
    FAILED|ROLLBACK_FAILED) ;;
    *) return 1 ;;
  esac
  install -d -m 0750 -- "$result_root" || return 1
  temporary="$(mktemp "$result_root/.result.XXXXXX")" || return 1
  jq -Scn --arg targetReleaseId "$TARGET_RELEASE" --arg status "$status" \
    --arg finishedAtUtc "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" \
    '{schemaVersion:1,targetReleaseId:$targetReleaseId,status:$status,
      finishedAtUtc:$finishedAtUtc}' >"$temporary" || { rm -f -- "$temporary"; return 1; }
  chmod 0640 "$temporary" || { rm -f -- "$temporary"; return 1; }
  mv -fT -- "$temporary" "$result_root/$TARGET_RELEASE.json"
}

restore_original_state() {
  [[ "$ROLLBACK_IN_PROGRESS" == false && "$ROLLBACK_SUCCEEDED" == false ]] || return 0
  ROLLBACK_IN_PROGRESS=true
  if [[ "$ENV_SWITCHED" == false && "$ADMIN_SWITCHED" == false && "$OPS_SWITCHED" == false &&
        "$MARKERS_SWITCHED" == false ]]; then
    return 0
  fi
  local recovery_failed=false env_restored=false images_verified=false
  local api_started=false api_healthy=false admin_restored=true
  note 'restoring the release that was active before the failed rollback'
  if ! prepare_recovery_diagnostic; then
    RECOVERY_DIAGNOSTIC=''
    recovery_failed=true
  fi
  if [[ "$ENV_SWITCHED" == true ]]; then
    if recovery_step release-env restore_release_environment; then
      env_restored=true
    else
      recovery_failed=true
    fi
    if [[ "$env_restored" == true ]] && recovery_step current-images \
        verify_release_images "$CURRENT_DIRECTORY" "$CURRENT_RELEASE"; then
      images_verified=true
    else
      recovery_record current-images SKIPPED || true
      recovery_failed=true
    fi
    if [[ "$images_verified" == true ]] && recovery_step current-api-start \
        compose_up_api "$CURRENT_DIRECTORY"; then
      api_started=true
    else
      recovery_record current-api-start SKIPPED || true
      recovery_failed=true
    fi
    if [[ "$api_started" == true ]] && recovery_step current-api-health \
        wait_for_api "$CURRENT_DIRECTORY" \
        "${PORTFOLIO_ROLLBACK_RECOVERY_TIMEOUT_SECONDS:-${PORTFOLIO_ROLLBACK_READY_TIMEOUT_SECONDS:-180}}"; then
      api_healthy=true
    else
      recovery_record current-api-health SKIPPED || true
      recovery_failed=true
    fi
    if [[ "$api_healthy" == true ]]; then
      recovery_step current-api-smoke run_original_api_smoke || recovery_failed=true
    else
      recovery_record current-api-smoke SKIPPED || true
      recovery_failed=true
    fi
  fi
  if [[ "$ADMIN_SWITCHED" == true ]]; then
    if ! recovery_step administrator-pointer restore_admin_pointer; then
      admin_restored=false
      recovery_failed=true
    fi
  fi
  if [[ "$OPS_SWITCHED" == true ]]; then
    recovery_step operations-pointer restore_ops_pointer || recovery_failed=true
  fi
  if [[ "$MARKERS_SWITCHED" == true ]]; then
    recovery_step current-release-marker write_marker current-release "$CURRENT_RELEASE" ||
      recovery_failed=true
    if [[ -n "$ORIGINAL_PREVIOUS_RELEASE" ]]; then
      recovery_step previous-release-marker write_marker previous-release \
        "$ORIGINAL_PREVIOUS_RELEASE" || recovery_failed=true
    else
      recovery_step previous-release-marker rm -f -- "$PORTFOLIO_ROOT/previous-release" ||
        recovery_failed=true
    fi
  fi
  if [[ "$ADMIN_SWITCHED" == true ]]; then
    if [[ "$admin_restored" == true ]]; then
      recovery_step nginx-reload nginx_test_and_reload || recovery_failed=true
    else
      recovery_record nginx-reload SKIPPED || true
      recovery_failed=true
    fi
  fi
  if [[ "$api_healthy" == true && "$admin_restored" == true ]]; then
    recovery_step nginx-local-smoke run_original_nginx_smoke || recovery_failed=true
  else
    recovery_record nginx-local-smoke SKIPPED || true
    recovery_failed=true
  fi
  recovery_record recovery "$([[ "$recovery_failed" == false ]] && printf SUCCEEDED || printf FAILED)" ||
    recovery_failed=true
  [[ "$recovery_failed" == false ]]
}

on_exit() {
  local status="$1"
  trap - EXIT ERR HUP INT TERM
  if ((status != 0)); then
    local result_status=FAILED journal_verified=false recovery_failed=false
    if switch_journal_pending_is_verified; then
      journal_verified=true
    fi
    if [[ "$journal_verified" != true ]] && ! restore_original_state; then
      recovery_failed=true
    fi
    unset PORTFOLIO_SWITCH_TRANSACTION_ID
    if [[ -e "$PORTFOLIO_ROOT/.portfolio-switch-journal.json" ||
          -L "$PORTFOLIO_ROOT/.portfolio-switch-journal.json" ||
          -e "$PORTFOLIO_ETC_ROOT/.portfolio-switch-old-release.env" ||
          -L "$PORTFOLIO_ETC_ROOT/.portfolio-switch-old-release.env" ]] &&
        ! switch_journal_recover_pending; then
      recovery_failed=true
    fi
    if [[ "$recovery_failed" == true ]]; then
      result_status=ROLLBACK_FAILED
      note 'automatic recovery failed; see the redacted rollback diagnostic'
    fi
    if ! write_rollback_result "$result_status" >/dev/null 2>&1; then
      note "could not persist rollback result status $result_status"
    fi
  fi
  if [[ -n "$OLD_ENV_COPY" && -f "$OLD_ENV_COPY" ]]; then
    rm -f -- "$OLD_ENV_COPY"
  fi
  exit "$status"
}

main() {
  (($# <= 1)) || fail 'usage: rollback-release.sh [releaseId]'
  for command_name in awk bash chown chmod cp date dirname docker find flock grep id install jq ln \
    mktemp mv readlink realpath sha256sum sleep stat sync tar tr wc; do
    require_command "$command_name"
  done
  [[ "$(id -u)" -eq 0 ]] || fail 'rollback must run as root'
  [[ "$PORTFOLIO_ROOT" == /* && "$PORTFOLIO_ETC_ROOT" == /* &&
     -d "$PORTFOLIO_ROOT" && ! -L "$PORTFOLIO_ROOT" &&
     -d "$PORTFOLIO_ETC_ROOT" && ! -L "$PORTFOLIO_ETC_ROOT" ]] ||
    fail 'portfolio roots must be absolute'
  PORTFOLIO_ROOT="$(realpath -e -- "$PORTFOLIO_ROOT")"
  PORTFOLIO_ETC_ROOT="$(realpath -e -- "$PORTFOLIO_ETC_ROOT")"
  [[ "$PORTFOLIO_ROOT" != '/' && "$PORTFOLIO_ETC_ROOT" != '/' ]] ||
    fail 'portfolio roots cannot be the filesystem root'
  validate_root_directory "$PORTFOLIO_ROOT" 'PORTFOLIO_ROOT'
  validate_root_directory "$PORTFOLIO_ETC_ROOT" 'PORTFOLIO_ETC_ROOT'
  local releases_root
  releases_root="$(realpath -e -- "$PORTFOLIO_ROOT/releases")"
  is_beneath "$releases_root" "$PORTFOLIO_ROOT" || fail 'releases root escaped PORTFOLIO_ROOT'
  validate_root_directory "$releases_root" 'releases root'
  RELEASE_ENV="$PORTFOLIO_ETC_ROOT/release.env"
  NGINX_ENV="$PORTFOLIO_ETC_ROOT/nginx.env"
  PORTFOLIO_RELEASE_ENV="$RELEASE_ENV"
  PORTFOLIO_NGINX_ENV="$NGINX_ENV"
  export PORTFOLIO_ROOT PORTFOLIO_ETC_ROOT PORTFOLIO_RELEASE_ENV PORTFOLIO_NGINX_ENV
  validate_protected_file "$RELEASE_ENV" 640 release.env
  validate_protected_file "$NGINX_ENV" 640 nginx.env

  local lock_file="${PORTFOLIO_DEPLOY_LOCK_FILE:-/run/lock/portfolio/deploy.lock}"
  acquire_deploy_lock "$lock_file"
  switch_journal_recover_pending || fail 'pending release switch recovery failed'

  validate_root_state_file "$PORTFOLIO_ROOT/current-release" 'current-release marker'
  CURRENT_RELEASE="$(read_single_line "$PORTFOLIO_ROOT/current-release" current-release)"
  is_release_id "$CURRENT_RELEASE" || fail 'current release marker is invalid'
  if [[ -e "$PORTFOLIO_ROOT/previous-release" ]]; then
    validate_root_state_file "$PORTFOLIO_ROOT/previous-release" 'previous-release marker'
    ORIGINAL_PREVIOUS_RELEASE="$(read_single_line \
      "$PORTFOLIO_ROOT/previous-release" previous-release)"
    is_release_id "$ORIGINAL_PREVIOUS_RELEASE" || fail 'previous release marker is invalid'
  fi
  if (($# == 1)); then
    TARGET_RELEASE="$1"
  else
    [[ -n "$ORIGINAL_PREVIOUS_RELEASE" ]] || fail 'previous release marker is missing'
    TARGET_RELEASE="$ORIGINAL_PREVIOUS_RELEASE"
  fi
  is_release_id "$TARGET_RELEASE" || fail 'target release ID is invalid'
  [[ "$TARGET_RELEASE" != "$CURRENT_RELEASE" ]] || fail 'target release is already current'
  TARGET_DIRECTORY="$(validate_release "$TARGET_RELEASE")"
  CURRENT_DIRECTORY="$(validate_release "$CURRENT_RELEASE")"
  [[ "$(env_value "$RELEASE_ENV" PORTFOLIO_RELEASE_ID)" == "$CURRENT_RELEASE" ]] ||
    fail 'current release marker and release.env disagree'

  verify_release_images "$CURRENT_DIRECTORY" "$CURRENT_RELEASE"
  verify_release_images "$TARGET_DIRECTORY" "$TARGET_RELEASE"

  OLD_ENV_COPY="$(mktemp "$PORTFOLIO_ETC_ROOT/.release.env.rollback.XXXXXX")"
  cp -- "$RELEASE_ENV" "$OLD_ENV_COPY"
  chmod 0600 "$OLD_ENV_COPY"
  trap 'on_exit $?' EXIT
  trap 'exit 129' HUP
  trap 'exit 130' INT
  trap 'exit 143' TERM
  if [[ -L "$PORTFOLIO_ROOT/current-admin" ]]; then
    OLD_ADMIN_TARGET="$(readlink -f -- "$PORTFOLIO_ROOT/current-admin")"
    [[ -d "$OLD_ADMIN_TARGET" ]] || fail 'current administrator link target is invalid'
  else
    fail 'current administrator pointer is missing or not a symbolic link'
  fi
  [[ "$OLD_ADMIN_TARGET" == "$PORTFOLIO_ROOT/releases/$CURRENT_RELEASE/admin" ]] ||
    fail 'current administrator pointer does not match current-release'
  if [[ -L "$PORTFOLIO_ROOT/current-ops" ]]; then
    OLD_OPS_TARGET="$(readlink -f -- "$PORTFOLIO_ROOT/current-ops")"
    [[ -d "$OLD_OPS_TARGET" ]] || fail 'current operations link target is invalid'
  else
    fail 'current operations pointer is missing or not a symbolic link'
  fi
  [[ "$OLD_OPS_TARGET" == "$PORTFOLIO_ROOT/releases/$CURRENT_RELEASE/ops" ]] ||
    fail 'current operations pointer does not match current-release'

  switch_journal_begin rollback "$CURRENT_RELEASE" "$TARGET_RELEASE" \
    "$ORIGINAL_PREVIOUS_RELEASE" || fail 'could not durably prepare the rollback switch'
  ENV_SWITCHED=true
  write_release_env "$TARGET_RELEASE" "$TARGET_DIRECTORY"
  switch_journal_phase env || fail 'could not persist the environment switch phase'
  compose_up_api "$TARGET_DIRECTORY"
  wait_for_api "$TARGET_DIRECTORY" || fail 'target API did not become healthy before timeout'
  bash "$TARGET_DIRECTORY/ops/deploy/scripts/smoke.sh" \
    api-local --base-url http://127.0.0.1:18080
  switch_journal_phase api || fail 'could not persist the API switch phase'

  ADMIN_SWITCHED=true
  switch_admin "$TARGET_DIRECTORY"
  nginx_test_and_reload
  local nginx_local_port
  nginx_local_port="${NGINX_LOCAL_PORT:-$(env_value "$NGINX_ENV" NGINX_LOCAL_PORT)}"
  [[ "$nginx_local_port" =~ ^[1-9][0-9]{0,4}$ ]] || fail 'NGINX_LOCAL_PORT is invalid'
  ((10#$nginx_local_port <= 65535)) || fail 'NGINX_LOCAL_PORT is invalid'
  case "$nginx_local_port" in
    80|443|18080) fail 'NGINX_LOCAL_PORT conflicts with a production listener' ;;
  esac
  run_edge_smoke "$TARGET_DIRECTORY" nginx-local \
    --resolve "yychainsaw.xyz:$nginx_local_port:127.0.0.1"
  switch_journal_phase admin || fail 'could not persist the administrator switch phase'
  OPS_SWITCHED=true
  switch_ops "$TARGET_DIRECTORY"
  switch_journal_phase ops || fail 'could not persist the operations switch phase'
  MARKERS_SWITCHED=true
  write_marker current-release "$TARGET_RELEASE"
  write_marker previous-release "$CURRENT_RELEASE"
  switch_journal_phase markers || fail 'could not persist the release marker switch phase'
  switch_journal_verify_target || fail 'the rolled-back release pointers are inconsistent'
  switch_journal_phase verified || fail 'could not persist the verified switch commit point'
  # `verified` is the irreversible commit point.  Cleanup failure after this
  # line must leave the verified rollback target active.
  ROLLBACK_SUCCEEDED=true
  switch_journal_commit || fail 'could not durably finish the rollback switch'
  rm -f -- "$OLD_ENV_COPY"
  OLD_ENV_COPY=''
  trap - EXIT HUP INT TERM
  printf 'PASS: rolled back to release %s without reversing database migrations\n' \
    "$TARGET_RELEASE"
}

main "$@"
