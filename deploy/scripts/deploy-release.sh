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
PUBLIC_CUTOVER=false
INITIAL_EMPTY_DATABASE=false
TARGET_RELEASE=''
TARGET_DIRECTORY=''
CURRENT_RELEASE=''
CURRENT_DIRECTORY=''
ORIGINAL_PREVIOUS_RELEASE=''
RELEASE_ENV=''
NGINX_ENV=''
WORK_DIRECTORY=''
OLD_ENV_COPY=''
OLD_ADMIN_TARGET=''
OLD_OPS_TARGET=''
ENV_SWITCHED=false
ADMIN_SWITCHED=false
OPS_SWITCHED=false
MARKERS_SWITCHED=false
SWITCH_ARMED=false
DEPLOYMENT_SUCCEEDED=false
RECOVERY_DIAGNOSTIC=''

fail() {
  printf 'portfolio deployment failed: %s\n' "$1" >&2
  exit 1
}

note() {
  printf 'portfolio deployment: %s\n' "$1" >&2
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

revalidate_deploy_lock_binding() {
  local lock_file="$1" fd="$2" requested_parent parent parent_mode lock_mode
  requested_parent="$(dirname -- "$lock_file")"
  [[ -d "$requested_parent" && ! -L "$requested_parent" ]] ||
    fail 'deployment lock changed while waiting: parent is missing or linked'
  parent="$(realpath -e -- "$requested_parent")"
  [[ "$parent" == "$requested_parent" && "$parent" != / &&
     "$(stat -Lc '%u:%g' -- "$parent")" == 0:0 ]] ||
    fail 'deployment lock changed while waiting: parent is unsafe'
  parent_mode="$(stat -Lc '%a' -- "$parent")"
  if [[ "$lock_file" == /run/lock/portfolio/deploy.lock ]]; then
    [[ "$parent_mode" == 700 ]] ||
      fail 'deployment lock changed while waiting: default parent mode is not 0700'
  else
    (( (8#$parent_mode & 8#022) == 0 )) ||
      fail 'deployment lock changed while waiting: parent is writable outside root'
  fi
  [[ -f "$lock_file" && ! -L "$lock_file" &&
     "$(stat -Lc '%u:%g:%h' -- "$lock_file")" == 0:0:1 ]] ||
    fail 'deployment lock changed while waiting: path is not a root-owned single-link file'
  lock_mode="$(stat -Lc '%a' -- "$lock_file")"
  (( (8#$lock_mode & 8#022) == 0 )) ||
    fail 'deployment lock changed while waiting: path is writable outside root'
  [[ "$(stat -Lc '%d:%i' -- "$lock_file")" == \
     "$(stat -Lc '%d:%i' -- "/proc/self/fd/$fd")" ]] ||
    fail 'deployment lock changed while waiting: descriptor identity differs'
}

acquire_deploy_lock() {
  local lock_file="$1" parent requested_parent parent_mode
  [[ "$lock_file" == /* && "$lock_file" != / &&
     "$lock_file" != *$'\n'* && "$lock_file" != *$'\r'* ]] ||
    fail 'deployment lock path is invalid'
  requested_parent="$(dirname -- "$lock_file")"
  [[ -d "$requested_parent" && ! -L "$requested_parent" ]] ||
    fail 'deployment lock parent is missing or linked'
  parent="$(realpath -e -- "$requested_parent")"
  [[ "$parent" == "$requested_parent" && "$parent" != / &&
     "$(stat -Lc '%u:%g' -- "$parent")" == 0:0 ]] ||
    fail 'deployment lock parent is non-canonical or not root:root'
  parent_mode="$(stat -Lc '%a' -- "$parent")"
  if [[ "$lock_file" == /run/lock/portfolio/deploy.lock ]]; then
    [[ "$parent_mode" == 700 ]] || fail 'default deployment lock parent mode is not 0700'
  else
    (( (8#$parent_mode & 8#022) == 0 )) ||
      fail 'deployment lock parent is writable outside root'
  fi
  if [[ ! -e "$lock_file" && ! -L "$lock_file" ]]; then
    (umask 077; set -C; : >"$lock_file") 2>/dev/null || true
  fi
  [[ -f "$lock_file" && ! -L "$lock_file" &&
     "$(stat -Lc '%u:%g:%h' -- "$lock_file")" == 0:0:1 ]] ||
    fail 'deployment lock is not a root-owned single-link regular file'
  local lock_mode
  lock_mode="$(stat -Lc '%a' -- "$lock_file")"
  (( (8#$lock_mode & 8#022) == 0 )) || fail 'deployment lock is writable outside root'
  exec 9<>"$lock_file"
  [[ "$(stat -Lc '%d:%i' -- "$lock_file")" == "$(stat -Lc '%d:%i' -- /proc/self/fd/9)" ]] ||
    fail 'deployment lock descriptor identity changed'
  flock -x 9
  revalidate_deploy_lock_binding "$lock_file" 9
  export PORTFOLIO_DEPLOY_LOCK_HELD=true PORTFOLIO_DEPLOY_LOCK_FD=9
}

is_release_id() {
  [[ "$1" =~ ^[0-9a-f]{12}-[0-9a-f]{12}$ ]]
}

read_single_line() {
  local path="$1" label="$2" value=''
  [[ -f "$path" && ! -L "$path" ]] || fail "$label is missing or linked"
  IFS= read -r value <"$path" || true
  [[ -n "$value" && "$(wc -l <"$path" | tr -d '[:space:]')" == 1 ]] ||
    fail "$label must contain exactly one non-empty line"
  printf '%s\n' "$value"
}

env_value() {
  local path="$1" key="$2" value
  value="$(awk -F= -v key="$key" '
    $1 == key { count += 1; value = substr($0, index($0, "=") + 1) }
    END { if (count != 1 || value == "") exit 1; print value }
  ' "$path")" || fail "$path has a missing or duplicate $key"
  [[ "$value" != *$'\n'* && "$value" != *$'\r'* ]] || fail "$key contains a control character"
  printf '%s\n' "$value"
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
  local reference="$1" manifest config_sha
  manifest="$(docker save "$reference" | tar -xOf - manifest.json)" ||
    fail "image is unavailable: $reference"
  config_sha="$(config_digest_from_manifest "$manifest")"
  printf 'sha256:%s\n' "$config_sha"
}

validate_release() {
  local release_id="$1" directory
  directory="$PORTFOLIO_ROOT/releases/$release_id"
  [[ -d "$directory" && ! -L "$directory" ]] || fail "installed release is missing: $release_id"
  directory="$(realpath -e -- "$directory")"
  [[ "$directory" == "$PORTFOLIO_ROOT/releases/$release_id" ]] ||
    fail 'release directory escaped the reviewed root'
  [[ -f "$directory/release.json" && ! -L "$directory/release.json" ]] ||
    fail 'release.json is missing or linked'
  jq -e --arg id "$release_id" '
    .releaseId == $id and
    .apiImageTag == ("portfolio-api:"+$id) and
    .postgresImageTag == ("portfolio-postgres-17:"+$id) and
    (.apiImageId | type == "string" and test("^sha256:[0-9a-f]{64}$")) and
    (.postgresImageId | type == "string" and test("^sha256:[0-9a-f]{64}$"))
  ' "$directory/release.json" >/dev/null || fail 'release image identity contract is invalid'
  for required in admin/index.html public-assets/.vite/manifest.json \
    ops/deploy/docker-compose.prod.yml ops/deploy/scripts/preflight.sh \
    ops/deploy/scripts/smoke.sh ops/deploy/scripts/prune-releases.sh; do
    [[ -f "$directory/$required" && ! -L "$directory/$required" ]] ||
      fail "release file is missing or linked: $required"
  done
  if find -P "$directory" -type l -print -quit | grep -q .; then
    fail 'release tree contains a symbolic link'
  fi
  printf '%s\n' "$directory"
}

verify_release_images() {
  local directory="$1" release_id="$2"
  local api_tag postgres_tag expected actual
  api_tag="$(jq -r '.apiImageTag' "$directory/release.json")"
  postgres_tag="$(jq -r '.postgresImageTag' "$directory/release.json")"
  expected="$(jq -r '.apiImageId' "$directory/release.json")"
  actual="$(portable_image_id "$api_tag")"
  [[ "$actual" == "$expected" ]] || fail 'local API image portable digest differs from release.json'
  expected="$(jq -r '.postgresImageId' "$directory/release.json")"
  actual="$(portable_image_id "$postgres_tag")"
  [[ "$actual" == "$expected" ]] ||
    fail 'local PostgreSQL image portable digest differs from release.json'
  [[ "$api_tag" == "portfolio-api:$release_id" &&
     "$postgres_tag" == "portfolio-postgres-17:$release_id" ]] ||
    fail 'release-local image tags are invalid'
}

write_release_env() {
  local release_id="$1" directory="$2" temporary deploy_group
  local api_tag postgres_tag
  api_tag="$(jq -r '.apiImageTag' "$directory/release.json")"
  postgres_tag="$(jq -r '.postgresImageTag' "$directory/release.json")"
  [[ "$api_tag" == "portfolio-api:$release_id" &&
     "$postgres_tag" == "portfolio-postgres-17:$release_id" ]] ||
    fail 'cannot write release.env with non-local image tags'
  temporary="$(mktemp "$PORTFOLIO_ETC_ROOT/.release.env.XXXXXX")"
  printf 'POSTGRES_IMAGE=%s\nPORTFOLIO_IMAGE=%s\nPORTFOLIO_RELEASE_ID=%s\n' \
    "$postgres_tag" "$api_tag" "$release_id" >"$temporary"
  chmod 0640 "$temporary"
  deploy_group="${PORTFOLIO_DEPLOY_GROUP:-portfolio-deploy}"
  chown "root:$deploy_group" "$temporary"
  sync -f "$temporary"
  mv -fT -- "$temporary" "$RELEASE_ENV"
  sync -f "$PORTFOLIO_ETC_ROOT"
}

compose() {
  docker compose --env-file "$RELEASE_ENV" \
    -f "$TARGET_DIRECTORY/ops/deploy/docker-compose.prod.yml" "$@"
}

wait_for_release_service_health() {
  local directory="$1" service="$2" timeout="$3" deadline container status
  deadline=$((SECONDS + timeout))
  while ((SECONDS < deadline)); do
    container="$(docker compose --env-file "$RELEASE_ENV" \
      -f "$directory/ops/deploy/docker-compose.prod.yml" \
      ps -q "$service" 2>/dev/null || true)"
    if [[ -n "$container" && "$container" != *[[:space:]]* ]]; then
      status="$(docker inspect --format \
        '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' \
        "$container" 2>/dev/null || true)"
      [[ "$status" == healthy ]] && return 0
      [[ "$status" == exited || "$status" == dead ]] && return 1
    fi
    sleep "${PORTFOLIO_HEALTH_POLL_SECONDS:-2}"
  done
  return 1
}

wait_for_service_health() {
  wait_for_release_service_health "$TARGET_DIRECTORY" "$1" "$2"
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
  contract="$(release_smoke_asset_contract "$directory")" || {
    note 'release has no deterministic Vite hashed asset for edge smoke'
    return 1
  }
  IFS=$'\t' read -r asset_path asset_sha <<<"$contract"
  [[ -n "$asset_path" && "$asset_sha" =~ ^[0-9a-f]{64}$ ]] || return 1
  PORTFOLIO_SMOKE_ASSET_PATH="$asset_path" \
    PORTFOLIO_SMOKE_ASSET_SHA256="$asset_sha" \
    bash "$directory/ops/deploy/scripts/smoke.sh" "$mode" "$@"
}

verify_root_owned_executable() {
  local path="$1" label="$2" owner mode
  [[ -f "$path" && ! -L "$path" && -x "$path" ]] || fail "$label is missing, linked, or not executable"
  owner="$(stat -Lc '%u' -- "$path")"
  mode="$(stat -Lc '%a' -- "$path")"
  [[ "$owner" == 0 ]] || fail "$label is not owned by root"
  (( (8#$mode & 8#022) == 0 )) || fail "$label is group- or world-writable"
}

verify_root_owned_regular() {
  local path="$1" label="$2" expected_mode="${3:-}" owner mode
  [[ "$path" == /* && -f "$path" && ! -L "$path" && "$(stat -Lc '%h' -- "$path")" == 1 ]] ||
    fail "$label is missing, linked, or not absolute"
  owner="$(stat -Lc '%u' -- "$path")"
  mode="$(stat -Lc '%a' -- "$path")"
  [[ "$owner" == 0 ]] || fail "$label is not owned by root"
  if [[ -n "$expected_mode" ]]; then
    [[ "$mode" == "$expected_mode" ]] || fail "$label mode is not $expected_mode"
  else
    (( (8#$mode & 8#022) == 0 )) || fail "$label is group- or world-writable"
  fi
}

run_backup_gate() {
  local stable="${PORTFOLIO_BACKUP_DISPATCH_PATH:-/usr/local/libexec/portfolio/backup-dispatch.sh}"
  local backup_env="${PORTFOLIO_BACKUP_ENV_FILE:-/etc/portfolio/backup.env}"
  local unit_file="${PORTFOLIO_BACKUP_UNIT_FILE:-/etc/systemd/system/portfolio-backup.service}"
  local systemctl_command="${PORTFOLIO_SYSTEMCTL_COMMAND:-/usr/bin/systemctl}"
  local service="${PORTFOLIO_BACKUP_SERVICE:-portfolio-backup.service}"
  local output load_state='' fragment_path='' drop_in_paths='' need_reload=''
  local active_state='' sub_state='' result='' exec_status=''

  [[ "$service" == portfolio-backup.service ]] ||
    fail 'backup service name must be portfolio-backup.service'
  verify_root_owned_executable "$stable" 'stable backup dispatcher'
  verify_root_owned_executable "$systemctl_command" 'systemctl command'
  verify_root_owned_regular "$backup_env" 'backup environment' 600
  verify_root_owned_regular "$unit_file" 'backup service unit'
  grep -Fx "EnvironmentFile=$backup_env" "$unit_file" >/dev/null ||
    fail 'backup service unit does not load the reviewed backup environment'
  grep -Fx "EnvironmentFile=$RELEASE_ENV" "$unit_file" >/dev/null ||
    fail 'backup service unit does not load the reviewed release environment'
  grep -Fx "ExecStart=/usr/bin/bash $stable backup" "$unit_file" >/dev/null ||
    fail 'backup service unit does not call the stable dispatcher'
  grep -Fx 'UnsetEnvironment=BACKUP_AGE_IDENTITY AGE_SECRET_KEY AGE_SECRET_KEY_FILE COS_SECRET_ID COS_SECRET_KEY SMTP_PASSWORD' \
    "$unit_file" >/dev/null ||
    fail 'backup service unit does not scrub deployment runtime secrets'
  if grep -F "EnvironmentFile=$PORTFOLIO_ETC_ROOT/portfolio.env" "$unit_file" >/dev/null; then
    fail 'backup service unit must not load the application environment'
  fi

  "$systemctl_command" daemon-reload || return 1
  output="$("$systemctl_command" show "$service" \
    --property=LoadState --property=FragmentPath --property=DropInPaths \
    --property=NeedDaemonReload --property=ActiveState --property=SubState)" || return 1
  [[ "$(printf '%s\n' "$output" | wc -l | tr -d '[:space:]')" == 6 ]] || return 1
  while IFS='=' read -r key value; do
    case "$key" in
      LoadState) [[ -z "$load_state" ]] || return 1; load_state="$value" ;;
      FragmentPath) [[ -z "$fragment_path" ]] || return 1; fragment_path="$value" ;;
      DropInPaths) [[ -z "$drop_in_paths" ]] || return 1; drop_in_paths="$value" ;;
      NeedDaemonReload) [[ -z "$need_reload" ]] || return 1; need_reload="$value" ;;
      ActiveState) [[ -z "$active_state" ]] || return 1; active_state="$value" ;;
      SubState) [[ -z "$sub_state" ]] || return 1; sub_state="$value" ;;
      *) return 1 ;;
    esac
  done <<<"$output"
  [[ "$load_state" == loaded && "$fragment_path" == "$unit_file" &&
     -z "$drop_in_paths" && "$need_reload" == no &&
     "$active_state" == inactive && "$sub_state" == dead ]] || return 1

  "$systemctl_command" start "$service" || return 1
  load_state=''; fragment_path=''; drop_in_paths=''; need_reload=''
  active_state=''; sub_state=''; result=''; exec_status=''
  output="$("$systemctl_command" show "$service" \
    --property=LoadState --property=FragmentPath --property=DropInPaths \
    --property=NeedDaemonReload --property=ActiveState --property=SubState --property=Result \
    --property=ExecMainStatus)" || return 1
  [[ "$(printf '%s\n' "$output" | wc -l | tr -d '[:space:]')" == 8 ]] || return 1
  while IFS='=' read -r key value; do
    case "$key" in
      LoadState) [[ -z "$load_state" ]] || return 1; load_state="$value" ;;
      FragmentPath) [[ -z "$fragment_path" ]] || return 1; fragment_path="$value" ;;
      DropInPaths) [[ -z "$drop_in_paths" ]] || return 1; drop_in_paths="$value" ;;
      NeedDaemonReload) [[ -z "$need_reload" ]] || return 1; need_reload="$value" ;;
      ActiveState) [[ -z "$active_state" ]] || return 1; active_state="$value" ;;
      SubState) [[ -z "$sub_state" ]] || return 1; sub_state="$value" ;;
      Result) [[ -z "$result" ]] || return 1; result="$value" ;;
      ExecMainStatus) [[ -z "$exec_status" ]] || return 1; exec_status="$value" ;;
      *) return 1 ;;
    esac
  done <<<"$output"
  [[ "$load_state" == loaded && "$fragment_path" == "$unit_file" &&
     -z "$drop_in_paths" && "$need_reload" == no &&
     "$active_state" == inactive && "$sub_state" == dead &&
     "$result" == success && "$exec_status" == 0 ]]
}

safe_asset_parent() {
  local relative="$1" current="$PORTFOLIO_ROOT/assets" component
  local parent="${relative%/*}"
  [[ "$parent" != "$relative" ]] || return 0
  IFS='/' read -r -a components <<<"$parent"
  for component in "${components[@]}"; do
    [[ -n "$component" && "$component" != . && "$component" != .. ]] ||
      fail 'public asset path is not canonical'
    current="$current/$component"
    if [[ -e "$current" || -L "$current" ]]; then
      [[ -d "$current" && ! -L "$current" ]] || fail 'public asset parent conflicts with a non-directory'
    else
      mkdir -- "$current"
      chmod 0755 "$current"
    fi
  done
}

copy_public_assets_create_only() {
  local source_root="$TARGET_DIRECTORY/public-assets"
  local hashed_root="$source_root/assets"
  local assets_root="$PORTFOLIO_ROOT/assets"
  [[ -d "$hashed_root" && ! -L "$hashed_root" ]] ||
    fail 'release hashed public assets directory is missing or linked'
  [[ -d "$assets_root" && ! -L "$assets_root" ]] || fail 'shared public assets root is missing or linked'
  chmod 0755 "$assets_root"
  local source relative destination temporary source_sha destination_sha
  while IFS= read -r -d '' source; do
    relative="${source#"$hashed_root"/}"
    [[ "$relative" != "$source" && "$relative" != *\\* &&
       ! "$relative" =~ (^|/)\.\.?(/|$) ]] || fail 'release contains an unsafe public asset path'
    safe_asset_parent "$relative"
    destination="$assets_root/$relative"
    source_sha="$(sha256sum -- "$source" | awk '{print $1}')"
    if [[ -e "$destination" || -L "$destination" ]]; then
      [[ -f "$destination" && ! -L "$destination" ]] || fail "public asset conflicts with a non-file: $relative"
      destination_sha="$(sha256sum -- "$destination" | awk '{print $1}')"
      [[ "$destination_sha" == "$source_sha" ]] || fail "public asset checksum conflict: $relative"
      continue
    fi
    temporary="$(mktemp "$(dirname -- "$destination")/.asset.XXXXXX")"
    install -m 0644 -- "$source" "$temporary"
    sync -f "$temporary"
    if ! ln -- "$temporary" "$destination" 2>/dev/null; then
      [[ -f "$destination" && ! -L "$destination" ]] || fail "public asset creation raced unsafely: $relative"
    fi
    rm -f -- "$temporary"
    destination_sha="$(sha256sum -- "$destination" | awk '{print $1}')"
    [[ "$destination_sha" == "$source_sha" ]] || fail "installed public asset checksum mismatch: $relative"
  done < <(find "$hashed_root" -type f -print0 | sort -z)
  sync -f "$assets_root"
}

prove_and_create_initial_database_volume() {
  local volume_name="${PORTFOLIO_POSTGRES_VOLUME_NAME:-portfolio_postgres-data}"
  [[ "$volume_name" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$ ]] ||
    fail 'initial PostgreSQL volume name is invalid'
  local rendered_name initial_env="$WORK_DIRECTORY/initial-release.env"
  printf 'POSTGRES_IMAGE=portfolio-postgres-17:%s\nPORTFOLIO_IMAGE=portfolio-api:%s\nPORTFOLIO_RELEASE_ID=%s\n' \
    "$TARGET_RELEASE" "$TARGET_RELEASE" "$TARGET_RELEASE" >"$initial_env"
  chmod 0600 "$initial_env"
  rendered_name="$(docker compose --env-file "$initial_env" \
    -f "$TARGET_DIRECTORY/ops/deploy/docker-compose.prod.yml" config --format json |
    jq -er '.volumes["postgres-data"].name')" || fail 'could not resolve Compose PostgreSQL volume name'
  [[ "$rendered_name" == "$volume_name" ]] || fail 'configured PostgreSQL volume name differs from Compose'
  local identity mountpoint initial_release volume_role
  if docker volume inspect "$volume_name" >/dev/null 2>&1; then
    identity="$(docker volume inspect --format \
      '{{.Name}}|{{index .Labels "portfolio.initial-release"}}|{{index .Labels "portfolio.volume-role"}}|{{.Mountpoint}}' \
      "$volume_name")" || fail 'existing PostgreSQL volume cannot be inspected'
    IFS='|' read -r rendered_name initial_release volume_role mountpoint <<<"$identity"
    [[ "$rendered_name" == "$volume_name" &&
       "$initial_release" == "$TARGET_RELEASE" &&
       "$volume_role" == postgres-initial && -n "$mountpoint" ]] ||
      fail 'initial deployment requires a provably new PostgreSQL volume or an unpublished same-release retry'
    mountpoint="$(realpath -e -- "$mountpoint")"
    [[ -d "$mountpoint" && ! -L "$mountpoint" ]] ||
      fail 'retry PostgreSQL volume mountpoint is invalid'
    note 'reusing the unpublished PostgreSQL volume from an earlier attempt of this release'
    return 0
  fi
  docker volume create \
    --label "portfolio.initial-release=$TARGET_RELEASE" \
    --label 'portfolio.volume-role=postgres-initial' \
    "$volume_name" >/dev/null
  identity="$(docker volume inspect --format \
    '{{.Name}}|{{index .Labels "portfolio.initial-release"}}|{{index .Labels "portfolio.volume-role"}}|{{.Mountpoint}}' \
    "$volume_name")" ||
    fail 'new PostgreSQL volume cannot be inspected'
  IFS='|' read -r rendered_name initial_release volume_role mountpoint <<<"$identity"
  [[ "$rendered_name" == "$volume_name" &&
     "$initial_release" == "$TARGET_RELEASE" &&
     "$volume_role" == postgres-initial && -n "$mountpoint" ]] ||
    fail 'new PostgreSQL volume identity is ambiguous'
  mountpoint="$(realpath -e -- "$mountpoint")"
  [[ -d "$mountpoint" && ! -L "$mountpoint" ]] || fail 'new PostgreSQL volume mountpoint is invalid'
  [[ -z "$(find "$mountpoint" -mindepth 1 -print -quit)" ]] ||
    fail 'new PostgreSQL volume is not empty'
}

load_protected_environment() {
  local path owner expected_mode
  for path in "$NGINX_ENV:640" "$PORTFOLIO_ETC_ROOT/portfolio.env:600"; do
    expected_mode="${path##*:}"
    path="${path%:*}"
    [[ "$path" == /* && -f "$path" && ! -L "$path" ]] ||
      fail 'protected environment source is missing, linked, or not absolute'
    owner="$(stat -Lc '%u' -- "$path")"
    [[ "$owner" == 0 && "$(stat -Lc '%a' -- "$path")" == "$expected_mode" ]] ||
      fail 'protected environment source owner or mode is unsafe'
  done
  # The files are root-owned deployment inputs checked by preflight. They are
  # loaded without xtrace so credentials never enter logs or process argv.
  set -a
  # shellcheck disable=SC1090,SC1091
  source "$NGINX_ENV"
  # shellcheck disable=SC1090,SC1091
  source "$PORTFOLIO_ETC_ROOT/portfolio.env"
  set +a
}

run_preflight() {
  local evidence="$1" mode="$2" release_id="$3" directory="$4"
  local preflight="${PORTFOLIO_PREFLIGHT_COMMAND:-$directory/ops/deploy/scripts/preflight.sh}"
  [[ "$preflight" == /* && -f "$preflight" && ! -L "$preflight" ]] ||
    fail 'preflight command is missing, linked, or not absolute'
  local -a arguments=()
  [[ "$mode" == public ]] && arguments+=(--public-cutover)
  [[ "$mode" == initial ]] && arguments+=(--initial-empty-database)
  env \
    PORTFOLIO_PREFLIGHT_EVIDENCE_OUTPUT="$evidence" \
    PORTFOLIO_PREFLIGHT_TARGET_RELEASE_ID="$TARGET_RELEASE" \
    PORTFOLIO_ROOT="$PORTFOLIO_ROOT" \
    PORTFOLIO_POSTGRES_ENV="$PORTFOLIO_ETC_ROOT/postgres.env" \
    PORTFOLIO_APP_ENV="$PORTFOLIO_ETC_ROOT/portfolio.env" \
    PORTFOLIO_RELEASE_ENV="$RELEASE_ENV" \
    PORTFOLIO_NGINX_ENV="$NGINX_ENV" \
    PORTFOLIO_COMPOSE_FILE="$directory/ops/deploy/docker-compose.prod.yml" \
    PORTFOLIO_RELEASE_ID="$release_id" \
    bash "$preflight" "${arguments[@]}" >/dev/null
  [[ -f "$evidence" && ! -L "$evidence" ]] || fail 'preflight did not retain deployment evidence'
}

validate_preflight_evidence() {
  local evidence="$1"
  jq -e --arg target "$TARGET_RELEASE" '
    (keys | sort) == (["cosLocations","requiredProviders","schemaVersion","targetReleaseId"] | sort) and
    .schemaVersion == 1 and .targetReleaseId == $target and
    (.requiredProviders | type == "array" and length > 0 and
      all(.[]; . == "LOCAL" or . == "TENCENT_COS") and
      length == (unique | length)) and
    (.cosLocations | type == "array" and
      all(.[]; (keys | sort) == ["bucket","region","verified"] and
        (.bucket | type == "string" and test("^[a-z0-9][a-z0-9-]{2,62}$")) and
        (.region | type == "string" and test("^[a-z0-9][a-z0-9-]{2,31}$")) and
        .verified == true) and
      ([.[] | (.bucket+"@"+.region)] | length == (unique | length))) and
    ((.requiredProviders | index("TENCENT_COS")) == null or (.cosLocations | length > 0))
  ' "$evidence" >/dev/null || fail 'preflight deployment evidence is incomplete or non-canonical'
}

derive_cleanup_contract() {
  local now="${PORTFOLIO_DEPLOY_NOW_UTC:-$(date -u '+%Y-%m-%dT%H:%M:%SZ')}"
  [[ "$now" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] ||
    fail 'deployment clock value is not canonical UTC'
  date -u --date="$now" '+%Y-%m-%dT%H:%M:%SZ' >/dev/null 2>&1 ||
    fail 'deployment clock value is invalid'
  local local_date local_hour boundary_epoch
  local_date="$(TZ=Asia/Hong_Kong date --date="$now" '+%Y-%m-%d')"
  local_hour="$(TZ=Asia/Hong_Kong date --date="$now" '+%H%M%S')"
  if ((10#$local_hour < 40000)); then
    local_date="$(TZ=Asia/Hong_Kong date --date="$local_date - 1 day" '+%Y-%m-%d')"
  fi
  boundary_epoch="$(TZ=Asia/Hong_Kong date --date="$local_date 04:00:00" '+%s')"
  CLEANUP_BOUNDARY_DATE="$local_date"
  CLEANUP_CUTOFF_EPOCH=$((boundary_epoch - 86400))
  CLEANUP_JOB_TYPE='CLEAN_MEDIA_STAGING'
  CLEANUP_JOB_KEY="media-staging-cleanup:$TARGET_RELEASE:$CLEANUP_BOUNDARY_DATE"
  CLEANUP_PAYLOAD="$(jq -Scn --argjson cutoff "$CLEANUP_CUTOFF_EPOCH" \
    '{cutoffEpochSecond:$cutoff}')"
}

query_cleanup_job() {
  local query_command="${PORTFOLIO_CLEANUP_QUERY_COMMAND:-}"
  if [[ -n "$query_command" ]]; then
    [[ "$query_command" == /* && -x "$query_command" && ! -L "$query_command" ]] ||
      fail 'cleanup query command is invalid'
    "$query_command" "$CLEANUP_JOB_TYPE" "$CLEANUP_JOB_KEY" "$CLEANUP_PAYLOAD"
    return
  fi
  local sql
  sql="select job_type, idempotency_key, payload::text, status from portfolio.background_job where idempotency_key = '$CLEANUP_JOB_KEY' order by created_at"
  # The positional parameters expand inside the container shell, not here.
  # shellcheck disable=SC2016
  compose exec -T postgres sh -eu -c \
    'export PGOPTIONS="-c default_transaction_read_only=on"; exec psql -X --no-psqlrc -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" --tuples-only --no-align --field-separator="|" -c "$1"' \
    portfolio-deployment "$sql"
}

wait_for_cleanup_gate() {
  local evidence="$1"
  validate_preflight_evidence "$evidence"
  local has_local has_cos
  has_local="$(jq -r '.requiredProviders | index("LOCAL") != null' "$evidence")"
  has_cos="$(jq -r '.requiredProviders | index("TENCENT_COS") != null' "$evidence")"
  if [[ "$has_cos" == true ]]; then
    jq -e '.cosLocations | length > 0 and all(.[]; .verified == true)' "$evidence" >/dev/null ||
      fail 'matching successful COS lifecycle evidence is absent'
  fi
  [[ "$has_local" == true ]] || return 0
  derive_cleanup_contract
  local timeout="${PORTFOLIO_CLEANUP_GATE_TIMEOUT_SECONDS:-300}"
  local interval="${PORTFOLIO_CLEANUP_POLL_INTERVAL_SECONDS:-2}"
  [[ "$timeout" =~ ^[1-9][0-9]*$ && "$interval" =~ ^[0-9]+$ ]] ||
    fail 'cleanup gate timeout configuration is invalid'
  local deadline=$((SECONDS + timeout)) output line_count
  local job_type key payload status extra canonical_payload
  while ((SECONDS < deadline)); do
    output="$(query_cleanup_job 2>/dev/null || true)"
    line_count="$(printf '%s\n' "$output" | sed '/^[[:space:]]*$/d' | wc -l | tr -d '[:space:]')"
    if [[ "$line_count" == 1 ]]; then
      IFS='|' read -r job_type key payload status extra <<<"$output"
      [[ -z "${extra:-}" && "$job_type" == "$CLEANUP_JOB_TYPE" && "$key" == "$CLEANUP_JOB_KEY" ]] ||
        fail 'cleanup gate returned the wrong job type or idempotency key'
      canonical_payload="$(printf '%s' "$payload" | jq -Sc . 2>/dev/null)" ||
        fail 'cleanup gate payload is not JSON'
      [[ "$canonical_payload" == "$CLEANUP_PAYLOAD" ]] ||
        fail 'cleanup gate payload cutoff is not canonical'
      case "$status" in
        SUCCEEDED) return 0 ;;
        FAILED|DEAD) fail 'current-release staging cleanup failed' ;;
        PENDING|RUNNING) ;;
        *) fail 'cleanup gate returned an invalid status' ;;
      esac
    elif [[ "$line_count" != 0 ]]; then
      fail 'cleanup gate returned an ambiguous job set'
    fi
    sleep "$interval"
  done
  fail 'current-release staging cleanup timed out'
}

switch_admin() {
  local temporary="$PORTFOLIO_ROOT/.current-admin.$$.tmp"
  [[ ! -e "$temporary" && ! -L "$temporary" ]] || fail 'temporary administrator pointer already exists'
  ln -s -- "$TARGET_DIRECTORY/admin" "$temporary"
  mv -fT -- "$temporary" "$PORTFOLIO_ROOT/current-admin"
  sync -f "$PORTFOLIO_ROOT"
}

switch_ops() {
  local temporary="$PORTFOLIO_ROOT/.current-ops.$$.tmp"
  [[ ! -e "$temporary" && ! -L "$temporary" ]] || fail 'temporary operations pointer already exists'
  ln -s -- "$TARGET_DIRECTORY/ops" "$temporary"
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
  local name="$1" value="$2" temporary
  is_release_id "$value" || fail "cannot write invalid $name marker"
  temporary="$(mktemp "$PORTFOLIO_ROOT/.$name.XXXXXX")"
  printf '%s\n' "$value" >"$temporary"
  chmod 0640 "$temporary"
  chown "root:${PORTFOLIO_DEPLOY_GROUP:-portfolio-deploy}" "$temporary"
  sync -f "$temporary"
  mv -fT -- "$temporary" "$PORTFOLIO_ROOT/$name"
  sync -f "$PORTFOLIO_ROOT"
}

write_deployment_result() {
  local status="$1" result_root="$PORTFOLIO_ROOT/deployment-results"
  case "$status" in
    SUCCEEDED|FAILED|ROLLBACK_FAILED) ;;
    *) return 1 ;;
  esac
  [[ -d "$PORTFOLIO_ROOT" && ! -L "$PORTFOLIO_ROOT" ]] || return 0
  install -d -m 0750 -- "$result_root"
  local temporary
  temporary="$(mktemp "$result_root/.result.XXXXXX")"
  jq -Scn --arg releaseId "$TARGET_RELEASE" --arg status "$status" \
    --arg finishedAtUtc "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" \
    --argjson publicCutover "$PUBLIC_CUTOVER" \
    '{schemaVersion:1,releaseId:$releaseId,status:$status,finishedAtUtc:$finishedAtUtc,
      publicCutover:$publicCutover}' >"$temporary"
  chmod 0640 "$temporary"
  mv -fT -- "$temporary" "$result_root/$TARGET_RELEASE.json"
}

restore_release_environment() {
  local restored=''
  [[ -f "$OLD_ENV_COPY" && ! -L "$OLD_ENV_COPY" ]] || return 1
  restored="$(mktemp "$PORTFOLIO_ETC_ROOT/.release.env.restore.XXXXXX")" || return 1
  cp -- "$OLD_ENV_COPY" "$restored" || { rm -f -- "$restored"; return 1; }
  chmod 0640 "$restored" || { rm -f -- "$restored"; return 1; }
  chown "root:${PORTFOLIO_DEPLOY_GROUP:-portfolio-deploy}" "$restored" || {
    rm -f -- "$restored"
    return 1
  }
  sync -f "$restored" || { rm -f -- "$restored"; return 1; }
  mv -fT -- "$restored" "$RELEASE_ENV" || { rm -f -- "$restored"; return 1; }
  sync -f "$PORTFOLIO_ETC_ROOT"
}

restore_admin_pointer() {
  local recovery="$PORTFOLIO_ROOT/.current-admin.recover.$$.tmp"
  rm -f -- "$recovery" || return 1
  if [[ -n "$OLD_ADMIN_TARGET" ]]; then
    ln -s -- "$OLD_ADMIN_TARGET" "$recovery" || return 1
    mv -fT -- "$recovery" "$PORTFOLIO_ROOT/current-admin" || {
      rm -f -- "$recovery"
      return 1
    }
  else
    rm -f -- "$PORTFOLIO_ROOT/current-admin" || return 1
  fi
  sync -f "$PORTFOLIO_ROOT"
}

restore_ops_pointer() {
  local recovery="$PORTFOLIO_ROOT/.current-ops.recover.$$.tmp"
  rm -f -- "$recovery" || return 1
  if [[ -n "$OLD_OPS_TARGET" ]]; then
    ln -s -- "$OLD_OPS_TARGET" "$recovery" || return 1
    mv -fT -- "$recovery" "$PORTFOLIO_ROOT/current-ops" || {
      rm -f -- "$recovery"
      return 1
    }
  else
    rm -f -- "$PORTFOLIO_ROOT/current-ops" || return 1
  fi
  sync -f "$PORTFOLIO_ROOT"
}

run_current_api_smoke() {
  bash "$CURRENT_DIRECTORY/ops/deploy/scripts/smoke.sh" \
    api-local --base-url http://127.0.0.1:18080
}

run_current_nginx_smoke() {
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
  local result_root="$PORTFOLIO_ROOT/deployment-results"
  install -d -m 0750 -- "$result_root" || return 1
  RECOVERY_DIAGNOSTIC="$result_root/$TARGET_RELEASE.rollback.log"
  printf 'schemaVersion=1\nreleaseId=%s\n' "$TARGET_RELEASE" >"$RECOVERY_DIAGNOSTIC" || return 1
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

restore_previous_state() {
  [[ "$SWITCH_ARMED" == true && "$DEPLOYMENT_SUCCEEDED" != true ]] || return 0
  local recovery_failed=false env_restored=false images_verified=false
  local api_started=false api_healthy=false admin_restored=true
  note 'restoring the previously active API, environment, administrator, and operations pointers'
  if ! prepare_recovery_diagnostic; then
    RECOVERY_DIAGNOSTIC=''
    recovery_failed=true
  fi
  if [[ "$ENV_SWITCHED" == true ]]; then
    if [[ -z "$CURRENT_DIRECTORY" ]]; then
      recovery_step initial-api-stop docker compose --env-file "$RELEASE_ENV" \
        -f "$TARGET_DIRECTORY/ops/deploy/docker-compose.prod.yml" \
        stop portfolio-api || recovery_failed=true
      recovery_step initial-api-remove docker compose --env-file "$RELEASE_ENV" \
        -f "$TARGET_DIRECTORY/ops/deploy/docker-compose.prod.yml" \
        rm -f portfolio-api || recovery_failed=true
    fi
    if recovery_step release-env restore_release_environment; then
      env_restored=true
    else
      recovery_failed=true
    fi
    if [[ -n "$CURRENT_DIRECTORY" ]]; then
      if [[ "$env_restored" == true ]] && recovery_step current-images \
          verify_release_images "$CURRENT_DIRECTORY" "$CURRENT_RELEASE"; then
        images_verified=true
      else
        recovery_record current-images SKIPPED || true
        recovery_failed=true
      fi
      if [[ "$images_verified" == true ]] && recovery_step current-api-start \
          docker compose --env-file "$RELEASE_ENV" \
          -f "$CURRENT_DIRECTORY/ops/deploy/docker-compose.prod.yml" \
          up -d --no-deps portfolio-api; then
        api_started=true
      else
        recovery_record current-api-start SKIPPED || true
        recovery_failed=true
      fi
      if [[ "$api_started" == true ]] && recovery_step current-api-health \
          wait_for_release_service_health "$CURRENT_DIRECTORY" portfolio-api \
          "${PORTFOLIO_RECOVERY_READY_TIMEOUT_SECONDS:-${PORTFOLIO_API_READY_TIMEOUT_SECONDS:-180}}"; then
        api_healthy=true
      else
        recovery_record current-api-health SKIPPED || true
        recovery_failed=true
      fi
      if [[ "$api_healthy" == true ]]; then
        recovery_step current-api-smoke run_current_api_smoke || recovery_failed=true
      else
        recovery_record current-api-smoke SKIPPED || true
        recovery_failed=true
      fi
    fi
  fi
  if [[ "$ADMIN_SWITCHED" == true ]]; then
    if ! recovery_step administrator-pointer restore_admin_pointer; then
      admin_restored=false
      recovery_failed=true
    fi
  fi
  if [[ "$OPS_SWITCHED" == true ]]; then
    if ! recovery_step operations-pointer restore_ops_pointer; then
      recovery_failed=true
    fi
  fi
  if [[ "$MARKERS_SWITCHED" == true ]]; then
    if [[ -n "$CURRENT_RELEASE" ]]; then
      recovery_step current-release-marker write_marker current-release "$CURRENT_RELEASE" ||
        recovery_failed=true
    else
      recovery_step current-release-marker rm -f -- "$PORTFOLIO_ROOT/current-release" ||
        recovery_failed=true
    fi
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
  if [[ -n "$CURRENT_DIRECTORY" ]]; then
    if [[ "$api_healthy" == true && "$admin_restored" == true ]]; then
      recovery_step nginx-local-smoke run_current_nginx_smoke || recovery_failed=true
    else
      recovery_record nginx-local-smoke SKIPPED || true
      recovery_failed=true
    fi
  fi
  recovery_record recovery "$([[ "$recovery_failed" == false ]] && printf SUCCEEDED || printf FAILED)" ||
    recovery_failed=true
  [[ "$recovery_failed" == false ]]
}

on_exit() {
  local status="$?"
  trap - EXIT HUP INT TERM
  if ((status != 0)); then
    local result_status=FAILED journal_verified=false recovery_failed=false
    if switch_journal_pending_is_verified; then
      journal_verified=true
    fi
    if [[ "$journal_verified" != true ]] && ! restore_previous_state; then
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
    if ! write_deployment_result "$result_status" >/dev/null 2>&1; then
      note "could not persist deployment result status $result_status"
    fi
  fi
  [[ -n "$OLD_ENV_COPY" && -f "$OLD_ENV_COPY" ]] && rm -f -- "$OLD_ENV_COPY"
  [[ -n "$WORK_DIRECTORY" && -d "$WORK_DIRECTORY" ]] && rm -rf -- "$WORK_DIRECTORY"
  exit "$status"
}

parse_arguments() {
  local argument
  for argument in "$@"; do
    case "$argument" in
      --public-cutover)
        [[ "$PUBLIC_CUTOVER" == false ]] || fail 'duplicate --public-cutover'
        PUBLIC_CUTOVER=true
        ;;
      --initial-empty-database)
        [[ "$INITIAL_EMPTY_DATABASE" == false ]] || fail 'duplicate --initial-empty-database'
        INITIAL_EMPTY_DATABASE=true
        ;;
      --*) fail 'usage: deploy-release.sh RELEASE_ID [--public-cutover] [--initial-empty-database]' ;;
      *)
        [[ -z "$TARGET_RELEASE" ]] || fail 'exactly one release ID is required'
        TARGET_RELEASE="$argument"
        ;;
    esac
  done
  is_release_id "$TARGET_RELEASE" || fail 'target release ID is invalid'
}

main() {
  parse_arguments "$@"
  local command_name
  for command_name in awk bash chmod chown cp date dirname docker find flock grep id \
    install jq ln mktemp mv readlink realpath sed sha256sum sort stat sync tar tr wc; do
    require_command "$command_name"
  done
  [[ "$(id -u)" -eq 0 ]] || fail 'deployment must run as root'
  [[ "$PORTFOLIO_ROOT" == /* && "$PORTFOLIO_ETC_ROOT" == /* &&
     "$PORTFOLIO_ROOT" != / && "$PORTFOLIO_ETC_ROOT" != / &&
     -d "$PORTFOLIO_ROOT" && ! -L "$PORTFOLIO_ROOT" &&
     -d "$PORTFOLIO_ETC_ROOT" && ! -L "$PORTFOLIO_ETC_ROOT" ]] ||
    fail 'portfolio roots must be absolute non-root paths'
  PORTFOLIO_ROOT="$(realpath -e -- "$PORTFOLIO_ROOT")"
  PORTFOLIO_ETC_ROOT="$(realpath -e -- "$PORTFOLIO_ETC_ROOT")"
  validate_root_directory "$PORTFOLIO_ROOT" 'PORTFOLIO_ROOT'
  validate_root_directory "$PORTFOLIO_ETC_ROOT" 'PORTFOLIO_ETC_ROOT'
  # The external BaoTa Nginx worker is deliberately not a member of the
  # deployment group.  Execute-only access permits traversal without exposing
  # a directory listing; child permissions still enforce the public boundary.
  chmod 0711 "$PORTFOLIO_ROOT"
  [[ -d "$PORTFOLIO_ROOT/releases" && ! -L "$PORTFOLIO_ROOT/releases" ]] ||
    fail 'releases root is missing or linked'
  [[ -d "$PORTFOLIO_ROOT/assets" && ! -L "$PORTFOLIO_ROOT/assets" ]] ||
    fail 'shared public assets root is missing or linked'
  validate_root_directory "$PORTFOLIO_ROOT/releases" 'releases root'
  validate_root_directory "$PORTFOLIO_ROOT/assets" 'shared assets root'
  RELEASE_ENV="$PORTFOLIO_ETC_ROOT/release.env"
  NGINX_ENV="$PORTFOLIO_ETC_ROOT/nginx.env"
  PORTFOLIO_RELEASE_ENV="$RELEASE_ENV"
  PORTFOLIO_NGINX_ENV="$NGINX_ENV"
  export PORTFOLIO_ROOT PORTFOLIO_ETC_ROOT PORTFOLIO_RELEASE_ENV PORTFOLIO_NGINX_ENV
  [[ -f "$RELEASE_ENV" && ! -L "$RELEASE_ENV" && -f "$NGINX_ENV" && ! -L "$NGINX_ENV" ]] ||
    fail 'protected release.env or nginx.env is missing or linked'
  TARGET_DIRECTORY="$(validate_release "$TARGET_RELEASE")"

  local lock_file="${PORTFOLIO_DEPLOY_LOCK_FILE:-/run/lock/portfolio/deploy.lock}"
  acquire_deploy_lock "$lock_file"
  switch_journal_recover_pending || fail 'pending release switch recovery failed'
  WORK_DIRECTORY="$(mktemp -d)"
  OLD_ENV_COPY="$(mktemp "$PORTFOLIO_ETC_ROOT/.release.env.deploy.XXXXXX")"
  cp -- "$RELEASE_ENV" "$OLD_ENV_COPY"
  chmod 0600 "$OLD_ENV_COPY"
  trap on_exit EXIT
  trap 'exit 129' HUP
  trap 'exit 130' INT
  trap 'exit 143' TERM

  if [[ -e "$PORTFOLIO_ROOT/current-release" || -L "$PORTFOLIO_ROOT/current-release" ]]; then
    verify_root_owned_regular "$PORTFOLIO_ROOT/current-release" 'current-release marker'
    CURRENT_RELEASE="$(read_single_line "$PORTFOLIO_ROOT/current-release" current-release)"
    is_release_id "$CURRENT_RELEASE" || fail 'current release marker is invalid'
    [[ "$CURRENT_RELEASE" != "$TARGET_RELEASE" ]] || fail 'target release is already current'
    CURRENT_DIRECTORY="$(validate_release "$CURRENT_RELEASE")"
    [[ "$(env_value "$RELEASE_ENV" PORTFOLIO_RELEASE_ID)" == "$CURRENT_RELEASE" ]] ||
      fail 'current release marker and release.env disagree'
    [[ "$INITIAL_EMPTY_DATABASE" == false ]] ||
      fail '--initial-empty-database is forbidden when a current release exists'
    if [[ -e "$PORTFOLIO_ROOT/previous-release" || -L "$PORTFOLIO_ROOT/previous-release" ]]; then
      verify_root_owned_regular "$PORTFOLIO_ROOT/previous-release" 'previous-release marker'
      ORIGINAL_PREVIOUS_RELEASE="$(read_single_line "$PORTFOLIO_ROOT/previous-release" previous-release)"
      is_release_id "$ORIGINAL_PREVIOUS_RELEASE" || fail 'previous release marker is invalid'
    fi
    [[ -L "$PORTFOLIO_ROOT/current-admin" ]] || fail 'current administrator pointer is missing'
    OLD_ADMIN_TARGET="$(readlink -f -- "$PORTFOLIO_ROOT/current-admin")"
    [[ "$OLD_ADMIN_TARGET" == "$CURRENT_DIRECTORY/admin" ]] ||
      fail 'current administrator pointer disagrees with current-release'
    [[ -L "$PORTFOLIO_ROOT/current-ops" ]] || fail 'current operations pointer is missing'
    OLD_OPS_TARGET="$(readlink -f -- "$PORTFOLIO_ROOT/current-ops")"
    [[ "$OLD_OPS_TARGET" == "$CURRENT_DIRECTORY/ops" ]] ||
      fail 'current operations pointer disagrees with current-release'
  else
    [[ "$INITIAL_EMPTY_DATABASE" == true ]] ||
      fail 'first deployment requires --initial-empty-database'
    [[ ! -e "$PORTFOLIO_ROOT/current-admin" && ! -L "$PORTFOLIO_ROOT/current-admin" ]] ||
      fail 'first deployment found an unexpected current administrator pointer'
    [[ ! -e "$PORTFOLIO_ROOT/current-ops" && ! -L "$PORTFOLIO_ROOT/current-ops" ]] ||
      fail 'first deployment found an unexpected current operations pointer'
  fi

  if [[ -n "$CURRENT_DIRECTORY" ]]; then
    verify_release_images "$CURRENT_DIRECTORY" "$CURRENT_RELEASE"
  fi
  verify_release_images "$TARGET_DIRECTORY" "$TARGET_RELEASE"
  load_protected_environment
  local evidence="$WORK_DIRECTORY/preflight-evidence.json"
  if [[ -n "$CURRENT_RELEASE" ]]; then
    run_preflight "$evidence" private "$CURRENT_RELEASE" "$CURRENT_DIRECTORY"
    note 'running verified on-demand backup before migration'
    run_backup_gate || fail 'verified pre-deployment backup failed'
  else
    prove_and_create_initial_database_volume
    run_preflight "$evidence" initial "$TARGET_RELEASE" "$TARGET_DIRECTORY"
  fi
  validate_preflight_evidence "$evidence"

  copy_public_assets_create_only
  switch_journal_begin deploy "$CURRENT_RELEASE" "$TARGET_RELEASE" \
    "$ORIGINAL_PREVIOUS_RELEASE" || fail 'could not durably prepare the release switch'
  SWITCH_ARMED=true
  ENV_SWITCHED=true
  write_release_env "$TARGET_RELEASE" "$TARGET_DIRECTORY"
  switch_journal_phase env || fail 'could not persist the environment switch phase'
  verify_release_images "$TARGET_DIRECTORY" "$TARGET_RELEASE"

  compose up -d postgres
  wait_for_service_health postgres "${PORTFOLIO_POSTGRES_READY_TIMEOUT_SECONDS:-180}" ||
    fail 'PostgreSQL did not become healthy before timeout'
  compose up -d --no-deps portfolio-api
  wait_for_service_health portfolio-api "${PORTFOLIO_API_READY_TIMEOUT_SECONDS:-180}" ||
    fail 'target API did not become healthy before timeout'
  bash "$TARGET_DIRECTORY/ops/deploy/scripts/smoke.sh" \
    api-local --base-url http://127.0.0.1:18080
  switch_journal_phase api || fail 'could not persist the API switch phase'

  local live_evidence="$WORK_DIRECTORY/live-preflight-evidence.json"
  run_preflight "$live_evidence" private "$TARGET_RELEASE" "$TARGET_DIRECTORY"
  validate_preflight_evidence "$live_evidence"
  wait_for_cleanup_gate "$live_evidence"
  ADMIN_SWITCHED=true
  switch_admin
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

  if [[ "$PUBLIC_CUTOVER" == true ]]; then
    local public_evidence="$WORK_DIRECTORY/public-preflight-evidence.json"
    run_preflight "$public_evidence" public "$TARGET_RELEASE" "$TARGET_DIRECTORY"
    validate_preflight_evidence "$public_evidence"
    run_edge_smoke "$TARGET_DIRECTORY" public \
      --base-url https://yychainsaw.xyz
  fi

  OPS_SWITCHED=true
  switch_ops
  switch_journal_phase ops || fail 'could not persist the operations switch phase'
  MARKERS_SWITCHED=true
  write_marker current-release "$TARGET_RELEASE"
  if [[ -n "$CURRENT_RELEASE" ]]; then
    write_marker previous-release "$CURRENT_RELEASE"
  else
    rm -f -- "$PORTFOLIO_ROOT/previous-release"
    sync -f "$PORTFOLIO_ROOT"
  fi
  switch_journal_phase markers || fail 'could not persist the release marker switch phase'
  switch_journal_verify_target || fail 'the switched release pointers are inconsistent'
  switch_journal_phase verified || fail 'could not persist the verified switch commit point'
  # `verified` is the irreversible commit point.  From here onward a failure
  # must retain/recover the verified new state, never roll it back in memory.
  DEPLOYMENT_SUCCEEDED=true
  SWITCH_ARMED=false
  switch_journal_commit || fail 'could not durably finish the release switch'
  if ! write_deployment_result SUCCEEDED; then
    note 'could not persist the successful deployment result'
  fi
  # Pruning is deliberately outside the reversible transaction: until the
  # commit is durable every old/previous release remains available to recovery.
  if ! PORTFOLIO_ROOT="$PORTFOLIO_ROOT" PORTFOLIO_DEPLOY_LOCK_FILE="$lock_file" \
      PORTFOLIO_DEPLOY_LOCK_HELD=true PORTFOLIO_DEPLOY_LOCK_FD=9 \
      bash "$TARGET_DIRECTORY/ops/deploy/scripts/prune-releases.sh"; then
    note 'post-commit release pruning failed; the deployed release remains active'
  fi
  rm -f -- "$OLD_ENV_COPY"
  OLD_ENV_COPY=''
  trap - EXIT HUP INT TERM
  rm -rf -- "$WORK_DIRECTORY"
  WORK_DIRECTORY=''
  printf 'PASS: deployed release %s with forward-only database migrations\n' "$TARGET_RELEASE"
}

main "$@"
