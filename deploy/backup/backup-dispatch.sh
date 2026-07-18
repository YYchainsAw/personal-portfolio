#!/usr/bin/env bash
set -euo pipefail
set +x
umask 077

fail() {
  printf 'portfolio backup dispatch failed: %s\n' "$1" >&2
  exit 1
}

require_root_owned_file() {
  local path="$1"
  local label="$2"
  [[ ! -L "$path" && -f "$path" ]] || fail "$label is missing or linked"
  local owner mode
  owner="$(stat -Lc '%u' -- "$path")"
  mode="$(stat -Lc '%a' -- "$path")"
  [[ "$owner" == 0 ]] || fail "$label is not owned by root"
  (( (8#$mode & 8#022) == 0 )) || fail "$label is group- or world-writable"
}

[[ $# -eq 1 && ("$1" == backup || "$1" == prune) ]] ||
  fail 'usage: backup-dispatch.sh backup|prune'
mode="$1"

portfolio_root="${PORTFOLIO_ROOT:-/opt/portfolio}"
[[ "$portfolio_root" == /* && "$portfolio_root" != / && ! -L "$portfolio_root" &&
   -d "$portfolio_root" ]] || fail 'PORTFOLIO_ROOT is invalid'
portfolio_root="$(realpath -e -- "$portfolio_root")"

marker="$portfolio_root/current-release"
require_root_owned_file "$marker" 'current-release marker'
[[ "$(wc -l <"$marker")" -eq 1 ]] || fail 'current-release marker is malformed'
release_id="$(tr -d '\r\n' <"$marker")"
[[ "$release_id" =~ ^[0-9a-f]{12}-[0-9a-f]{12}$ ]] ||
  fail 'current-release marker contains an invalid release ID'

release_root="$portfolio_root/releases/$release_id"
[[ ! -L "$release_root" && -d "$release_root" ]] || fail 'current release directory is missing or linked'
release_root="$(realpath -e -- "$release_root")"
[[ "$release_root" == "$portfolio_root/releases/$release_id" ]] ||
  fail 'current release directory escaped the reviewed release root'
metadata="$release_root/release.json"
require_root_owned_file "$metadata" 'current release metadata'
jq -e --arg releaseId "$release_id" '.releaseId == $releaseId' "$metadata" >/dev/null ||
  fail 'current release metadata does not match current-release'

export PORTFOLIO_RELEASE_ID="$release_id"
BACKUP_COMPOSE_FILE="$release_root/ops/deploy/docker-compose.prod.yml"
export BACKUP_COMPOSE_FILE
require_root_owned_file "$BACKUP_COMPOSE_FILE" 'current release production Compose file'

case "$mode" in
  backup)
    script="$release_root/ops/deploy/backup/backup-set.sh"
    arguments=(--verify-upload)
    ;;
  prune)
    script="$release_root/ops/deploy/backup/prune-remote.sh"
    arguments=()
    ;;
esac
require_root_owned_file "$script" 'current release backup entry point'
script="$(realpath -e -- "$script")"
[[ "$script" == "$release_root/ops/deploy/backup/"* ]] ||
  fail 'current release backup entry point escaped its release'
exec /usr/bin/bash "$script" "${arguments[@]}"
