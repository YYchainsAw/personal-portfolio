#!/usr/bin/env bash
set -euo pipefail
set +x
umask 027

export LC_ALL=C

SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=deploy/scripts/switch-journal.sh
source "$SCRIPT_DIRECTORY/switch-journal.sh"

DRY_RUN=false
PORTFOLIO_ROOT="${PORTFOLIO_ROOT:-}"
RELEASES_ROOT=''
ASSETS_ROOT=''
declare -A RETAINED_RELEASES=()
declare -A RETAINED_ASSETS=()

fail() {
  printf 'portfolio prune failed: %s\n' "$1" >&2
  exit 1
}

note() {
  printf 'portfolio prune: %s\n' "$1" >&2
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "$1 is required"
}

is_release_id() {
  [[ "$1" =~ ^[0-9a-f]{12}-[0-9a-f]{12}$ ]]
}

is_beneath() {
  local child="$1"
  local parent="${2%/}"
  [[ "$child" == "$parent/"* ]]
}

read_marker() {
  local path="$1"
  local required="$2"
  local value=''
  if [[ ! -e "$path" ]]; then
    [[ "$required" == 'false' ]] || fail "required marker is missing: $(basename "$path")"
    return 0
  fi
  [[ -f "$path" && ! -L "$path" ]] || fail 'release marker is not a regular file'
  IFS= read -r value <"$path" || true
  [[ -n "$value" ]] || fail 'release marker is empty'
  is_release_id "$value" || fail 'release marker contains an invalid release ID'
  [[ "$(wc -l <"$path" | tr -d '[:space:]')" == '1' ]] ||
    fail 'release marker must contain exactly one line'
  printf '%s\n' "$value"
}

resolve_release() {
  local release_id="$1"
  local path="$RELEASES_ROOT/$release_id"
  [[ -d "$path" && ! -L "$path" ]] || fail "retained release is missing: $release_id"
  path="$(realpath -e -- "$path")"
  [[ "$path" == "$RELEASES_ROOT/$release_id" ]] || fail 'release path escaped its root'
  printf '%s\n' "$path"
}

validate_release_metadata() {
  local release_id="$1"
  local directory="$2"
  local metadata="$directory/release.json"
  [[ -f "$metadata" && ! -L "$metadata" ]] || fail "release metadata is missing: $release_id"
  jq -e --arg release "$release_id" '
    .releaseId == $release and
    (.buildTimeUtc | type == "string" and
      test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$"))
  ' "$metadata" >/dev/null || fail "release metadata is invalid: $release_id"
}

retain_release() {
  local release_id="$1"
  [[ -n "$release_id" ]] || return 0
  local directory
  directory="$(resolve_release "$release_id")"
  validate_release_metadata "$release_id" "$directory"
  RETAINED_RELEASES["$release_id"]=1
}

select_retained_releases() {
  local current previous
  current="$(read_marker "$PORTFOLIO_ROOT/current-release" true)"
  previous="$(read_marker "$PORTFOLIO_ROOT/previous-release" false)"
  retain_release "$current"
  retain_release "$previous"

  local release_id directory build_time newest=''
  local newest_time=''
  while IFS= read -r release_id; do
    [[ -n "$release_id" ]] || continue
    is_release_id "$release_id" || fail "unexpected entry in releases root: $release_id"
    [[ -z "${RETAINED_RELEASES[$release_id]:-}" ]] || continue
    directory="$(resolve_release "$release_id")"
    validate_release_metadata "$release_id" "$directory"
    build_time="$(jq -r '.buildTimeUtc' "$directory/release.json")"
    if [[ -z "$newest" || "$build_time" > "$newest_time" ||
          ("$build_time" == "$newest_time" && "$release_id" > "$newest") ]]; then
      newest="$release_id"
      newest_time="$build_time"
    fi
  done < <(find -P "$RELEASES_ROOT" -mindepth 1 -maxdepth 1 -printf '%f\n' | sort)
  retain_release "$newest"
  note "retaining ${#RETAINED_RELEASES[@]} release(s)"
}

collect_retained_assets() {
  local release_id directory manifest asset relative
  for release_id in "${!RETAINED_RELEASES[@]}"; do
    directory="$(resolve_release "$release_id")"
    manifest="$directory/public-assets/.vite/manifest.json"
    [[ -f "$manifest" && ! -L "$manifest" ]] ||
      fail "retained release lacks its Vite manifest: $release_id"

    while IFS= read -r asset; do
      [[ "$asset" =~ ^assets/[A-Za-z0-9._/-]+$ &&
         "$asset" != *'/../'* && "$asset" != *//* ]] ||
        fail "retained Vite manifest contains an unsafe asset path: $release_id"
      RETAINED_ASSETS["$asset"]=1
    done < <(jq -r '.. | strings | select(startswith("assets/"))' "$manifest" | sort -u)

    # Keep every hashed payload shipped beside a retained manifest.  This also
    # protects CSS url() dependencies that a Vite version may not list as a
    # top-level manifest value.
    if [[ -d "$directory/public-assets/assets" ]]; then
      while IFS= read -r -d '' asset; do
        [[ ! -L "$asset" && -f "$asset" ]] ||
          fail "retained public assets contain a linked or non-regular file: $release_id"
        relative="assets/${asset#"$directory/public-assets/assets/"}"
        case "$relative" in
          *\\*|*'/../'*) fail 'retained asset path is unsafe' ;;
        esac
        RETAINED_ASSETS["$relative"]=1
      done < <(find -P "$directory/public-assets/assets" -type f -print0)
    fi

    [[ -f "$directory/admin/index.html" && ! -L "$directory/admin/index.html" ]] ||
      fail "retained release lacks administrator files: $release_id"
    if find -P "$directory/admin" -type l -print -quit | grep -q .; then
      fail "retained administrator tree contains a symbolic link: $release_id"
    fi
  done
}

delete_old_releases() {
  local entry release_id resolved
  while IFS= read -r -d '' entry; do
    release_id="$(basename -- "$entry")"
    is_release_id "$release_id" || fail "unexpected entry in releases root: $release_id"
    [[ -z "${RETAINED_RELEASES[$release_id]:-}" ]] || continue
    [[ -d "$entry" && ! -L "$entry" ]] || fail 'old release is not a regular directory'
    resolved="$(realpath -e -- "$entry")"
    [[ "$resolved" == "$RELEASES_ROOT/$release_id" ]] || fail 'old release escaped its root'
    if [[ "$DRY_RUN" == 'true' ]]; then
      note "would remove release $release_id"
    else
      rm -rf --one-file-system -- "$resolved"
      [[ ! -e "$resolved" ]] || fail "old release could not be fully removed: $release_id"
      note "removed release $release_id"
    fi
  done < <(find -P "$RELEASES_ROOT" -mindepth 1 -maxdepth 1 -print0)
}

delete_unreferenced_assets() {
  [[ -d "$ASSETS_ROOT" && ! -L "$ASSETS_ROOT" ]] || fail 'shared assets root is invalid'
  local file resolved relative
  while IFS= read -r -d '' file; do
    [[ ! -L "$file" && -f "$file" ]] || fail 'shared asset is not a regular file'
    resolved="$(realpath -e -- "$file")"
    is_beneath "$resolved" "$ASSETS_ROOT" || fail 'shared asset escaped its root'
    relative="assets/${resolved#"$ASSETS_ROOT/"}"
    [[ -z "${RETAINED_ASSETS[$relative]:-}" ]] || continue
    # The seven-day floor is a second line of defense for clients holding old
    # HTML outside the three retained releases.
    if find -P "$resolved" -maxdepth 0 -type f -mmin +10080 -print -quit | grep -q .; then
      if [[ "$DRY_RUN" == 'true' ]]; then
        note "would remove unreferenced asset $relative"
      else
        rm -f -- "$resolved"
        note "removed unreferenced asset $relative"
      fi
    fi
  done < <(find -P "$ASSETS_ROOT" -type f -print0)

  if [[ "$DRY_RUN" == 'false' ]]; then
    find -P "$ASSETS_ROOT" -depth -mindepth 1 -type d -empty -delete
  fi
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

acquire_lock() {
  local lock_file="${PORTFOLIO_DEPLOY_LOCK_FILE:-/run/lock/portfolio/deploy.lock}"
  local requested_parent parent parent_mode lock_mode
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
  if [[ "${PORTFOLIO_DEPLOY_LOCK_HELD:-false}" == 'true' ]]; then
    [[ "${PORTFOLIO_DEPLOY_LOCK_FD:-}" =~ ^[0-9]+$ ]] ||
      fail 'a caller claiming the deploy lock must provide its file descriptor'
    [[ "$(stat -Lc '%d:%i' -- "$lock_file")" == \
       "$(stat -Lc '%d:%i' -- "/proc/self/fd/$PORTFOLIO_DEPLOY_LOCK_FD")" ]] ||
      fail 'caller deploy lock descriptor does not match the reviewed lock file'
    flock -n "$PORTFOLIO_DEPLOY_LOCK_FD" || fail 'caller does not hold the deploy lock'
    revalidate_deploy_lock_binding "$lock_file" "$PORTFOLIO_DEPLOY_LOCK_FD"
    return
  fi
  exec 9<>"$lock_file"
  [[ "$(stat -Lc '%d:%i' -- "$lock_file")" == "$(stat -Lc '%d:%i' -- /proc/self/fd/9)" ]] ||
    fail 'deploy lock descriptor identity changed'
  flock -x 9
  revalidate_deploy_lock_binding "$lock_file" 9
  PORTFOLIO_DEPLOY_LOCK_FILE="$lock_file"
  PORTFOLIO_DEPLOY_LOCK_HELD=true
  PORTFOLIO_DEPLOY_LOCK_FD=9
  export PORTFOLIO_DEPLOY_LOCK_FILE PORTFOLIO_DEPLOY_LOCK_HELD PORTFOLIO_DEPLOY_LOCK_FD
}

main() {
  if (($# == 1)) && [[ "$1" == '--dry-run' ]]; then
    DRY_RUN=true
  elif (($# != 0)); then
    fail 'usage: prune-releases.sh [--dry-run]'
  fi
  for command_name in dirname find flock grep jq realpath sha256sum sort stat; do
    require_command "$command_name"
  done
  PORTFOLIO_ROOT="${PORTFOLIO_ROOT:-/opt/portfolio}"
  [[ "$PORTFOLIO_ROOT" == /* ]] || fail 'PORTFOLIO_ROOT must be absolute'
  PORTFOLIO_ROOT="$(realpath -e -- "$PORTFOLIO_ROOT")"
  [[ "$PORTFOLIO_ROOT" != '/' ]] || fail 'PORTFOLIO_ROOT cannot be the filesystem root'
  local root_mode
  root_mode="$(stat -Lc '%a' -- "$PORTFOLIO_ROOT")"
  [[ "$(stat -Lc '%u' -- "$PORTFOLIO_ROOT")" == 0 ]] ||
    fail 'PORTFOLIO_ROOT is not owned by root'
  (( (8#$root_mode & 8#022) == 0 )) || fail 'PORTFOLIO_ROOT is writable outside root'
  PORTFOLIO_ETC_ROOT="${PORTFOLIO_ETC_ROOT:-/etc/portfolio}"
  export PORTFOLIO_ROOT PORTFOLIO_ETC_ROOT
  RELEASES_ROOT="$(realpath -e -- "$PORTFOLIO_ROOT/releases")"
  ASSETS_ROOT="$(realpath -e -- "$PORTFOLIO_ROOT/assets")"
  is_beneath "$RELEASES_ROOT" "$PORTFOLIO_ROOT" || fail 'releases root escaped PORTFOLIO_ROOT'
  is_beneath "$ASSETS_ROOT" "$PORTFOLIO_ROOT" || fail 'assets root escaped PORTFOLIO_ROOT'
  acquire_lock
  if [[ -e "$PORTFOLIO_ROOT/.portfolio-switch-journal.json" ||
        -L "$PORTFOLIO_ROOT/.portfolio-switch-journal.json" ||
        -e "$PORTFOLIO_ETC_ROOT/.portfolio-switch-old-release.env" ||
        -L "$PORTFOLIO_ETC_ROOT/.portfolio-switch-old-release.env" ]]; then
    [[ "$PORTFOLIO_ETC_ROOT" == /* && "$PORTFOLIO_ETC_ROOT" != / &&
       -d "$PORTFOLIO_ETC_ROOT" && ! -L "$PORTFOLIO_ETC_ROOT" ]] ||
      fail 'PORTFOLIO_ETC_ROOT is invalid during switch recovery'
    PORTFOLIO_ETC_ROOT="$(realpath -e -- "$PORTFOLIO_ETC_ROOT")"
    root_mode="$(stat -Lc '%a' -- "$PORTFOLIO_ETC_ROOT")"
    [[ "$(stat -Lc '%u' -- "$PORTFOLIO_ETC_ROOT")" == 0 ]] ||
      fail 'PORTFOLIO_ETC_ROOT is not owned by root during switch recovery'
    (( (8#$root_mode & 8#022) == 0 )) ||
      fail 'PORTFOLIO_ETC_ROOT is writable outside root during switch recovery'
  fi
  switch_journal_recover_pending || fail 'pending release switch recovery failed'
  select_retained_releases
  collect_retained_assets
  delete_old_releases
  delete_unreferenced_assets
  printf 'PASS: retained releases=%s assets=%s dry-run=%s\n' \
    "${#RETAINED_RELEASES[@]}" "${#RETAINED_ASSETS[@]}" "$DRY_RUN"
}

main "$@"
