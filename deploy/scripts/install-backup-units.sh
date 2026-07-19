#!/usr/bin/env bash
set -euo pipefail
set +x
umask 077

export LC_ALL=C

PORTFOLIO_ROOT="${PORTFOLIO_ROOT:-/opt/portfolio}"
SYSTEMD_ROOT="${PORTFOLIO_SYSTEMD_ROOT:-/etc/systemd/system}"
SYSTEMCTL="${PORTFOLIO_SYSTEMCTL_COMMAND:-/usr/bin/systemctl}"
RUN_INITIAL_BACKUP=false
WORK_DIRECTORY=''
PRUNE_TIMER_ENABLE_ATTEMPTED=false
INSTALL_SUCCEEDED=false

cleanup() {
  local status=$?
  if [[ "$PRUNE_TIMER_ENABLE_ATTEMPTED" == true && "$INSTALL_SUCCEEDED" != true ]]; then
    "$SYSTEMCTL" disable --now portfolio-backup-prune.timer >/dev/null 2>&1 || true
  fi
  [[ -z "$WORK_DIRECTORY" || ! -d "$WORK_DIRECTORY" ]] || rm -rf -- "$WORK_DIRECTORY"
  return "$status"
}
on_signal() {
  local status="$1"
  trap - HUP INT TERM
  exit "$status"
}
trap cleanup EXIT
trap 'on_signal 129' HUP
trap 'on_signal 130' INT
trap 'on_signal 143' TERM

fail() {
  printf 'portfolio backup unit install failed: %s\n' "$1" >&2
  exit 1
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

validate_root_file() {
  local path="$1" mode="$2" label="$3"
  [[ "$path" == /* && -f "$path" && ! -L "$path" &&
     "$(stat -Lc '%u:%g:%h:%a' -- "$path")" == "0:0:1:$mode" ]] ||
    fail "$label is not a root:root single-link mode $mode regular file"
}

acquire_lock() {
  local lock_file="${PORTFOLIO_DEPLOY_LOCK_FILE:-/run/lock/portfolio/deploy.lock}"
  local parent requested_parent parent_mode lock_mode
  [[ "$lock_file" == /* && "$lock_file" != / ]] || fail 'deploy lock path is invalid'
  requested_parent="$(dirname -- "$lock_file")"
  [[ -d "$requested_parent" && ! -L "$requested_parent" ]] ||
    fail 'deploy lock parent is missing or linked'
  parent="$(realpath -e -- "$requested_parent")"
  [[ "$parent" == "$requested_parent" && "$parent" != / &&
     "$(stat -Lc '%u:%g' -- "$parent")" == 0:0 ]] ||
    fail 'deploy lock parent is non-canonical or not root:root'
  parent_mode="$(stat -Lc '%a' -- "$parent")"
  (( (8#$parent_mode & 8#022) == 0 )) || fail 'deploy lock parent is writable outside root'
  if [[ ! -e "$lock_file" && ! -L "$lock_file" ]]; then
    (umask 077; set -C; : >"$lock_file") 2>/dev/null || true
  fi
  [[ -f "$lock_file" && ! -L "$lock_file" && "$(stat -Lc '%u' -- "$lock_file")" == 0 ]] ||
    fail 'deploy lock is not a root-owned regular file'
  lock_mode="$(stat -Lc '%a' -- "$lock_file")"
  (( (8#$lock_mode & 8#022) == 0 )) || fail 'deploy lock is writable outside root'
  exec 9<>"$lock_file"
  [[ "$(stat -Lc '%d:%i' -- "$lock_file")" == "$(stat -Lc '%d:%i' -- /proc/self/fd/9)" ]] ||
    fail 'deploy lock descriptor identity changed'
  flock -x 9
}

quiesce_prune_timer() {
  local unit='portfolio-backup-prune.timer' output expected
  local load='' active='' sub='' enabled=''
  if ! output="$("$SYSTEMCTL" show "$unit" --property=LoadState \
      --property=ActiveState --property=SubState --property=UnitFileState)"; then
    "$SYSTEMCTL" disable --now "$unit" >/dev/null 2>&1 || true
    fail 'could not inspect the existing backup prune timer safely'
  fi
  [[ "$(printf '%s\n' "$output" | wc -l | tr -d '[:space:]')" == 4 ]] ||
    fail 'backup prune timer state has an unexpected property count'
  while IFS='=' read -r key value; do
    case "$key" in
      LoadState) load="$value" ;;
      ActiveState) active="$value" ;;
      SubState) sub="$value" ;;
      UnitFileState) enabled="$value" ;;
      *) fail 'backup prune timer state contains an unexpected property' ;;
    esac
  done <<<"$output"
  if [[ "$load" == not-found ]]; then
    [[ "$active" == inactive && "$sub" == dead &&
       ( -z "$enabled" || "$enabled" == disabled ) ]] ||
      fail 'missing backup prune timer reported an unsafe state'
    return
  fi
  [[ "$load" == loaded ]] || fail 'existing backup prune timer has an unsafe load state'
  "$SYSTEMCTL" disable --now "$unit"
  output="$("$SYSTEMCTL" show "$unit" --property=LoadState \
    --property=ActiveState --property=SubState --property=UnitFileState)" ||
    fail 'disabled backup prune timer state cannot be inspected'
  [[ "$(printf '%s\n' "$output" | wc -l | tr -d '[:space:]')" == 4 ]] ||
    fail 'disabled backup prune timer state has an unexpected property count'
  for expected in LoadState=loaded ActiveState=inactive SubState=dead UnitFileState=disabled; do
    grep -Fx "$expected" <<<"$output" >/dev/null ||
      fail 'backup prune timer did not become inactive and disabled'
  done
}

install_or_verify_unit() {
  local source="$1" destination="$2" candidate source_sha
  validate_root_file "$source" 600 'release-local systemd unit'
  source_sha="$(sha256sum -- "$source" | awk '{print $1}')"
  if [[ -e "$destination" || -L "$destination" ]]; then
    validate_root_file "$destination" 644 'installed systemd unit'
    [[ "$(sha256sum -- "$destination" | awk '{print $1}')" == "$source_sha" ]] ||
      fail "installed unit differs from current release: ${destination##*/}"
    return
  fi
  candidate="$(mktemp "$SYSTEMD_ROOT/.${destination##*/}.install.XXXXXX")"
  install -o root -g root -m 0644 -- "$source" "$candidate"
  [[ "$(sha256sum -- "$candidate" | awk '{print $1}')" == "$source_sha" ]] ||
    fail 'systemd unit candidate digest mismatch'
  sync -f "$candidate"
  # SYSTEMD_ROOT is root-only for writes, so the existence check plus rename is
  # create-only with respect to every non-root principal.
  [[ ! -e "$destination" && ! -L "$destination" ]] || fail 'systemd unit appeared during install'
  mv -T -- "$candidate" "$destination"
  sync -f "$SYSTEMD_ROOT"
}

verify_effective_unit() {
  local unit="$1" expected_fragment="$2" kind="$3" output
  local load='' fragment='' dropins='' reload='' active='' sub='' enabled=''
  output="$("$SYSTEMCTL" show "$unit" --property=LoadState --property=FragmentPath \
    --property=DropInPaths --property=NeedDaemonReload --property=ActiveState \
    --property=SubState --property=UnitFileState)" || return 1
  [[ "$(printf '%s\n' "$output" | wc -l | tr -d '[:space:]')" == 7 ]] || return 1
  while IFS='=' read -r key value; do
    case "$key" in
      LoadState) load="$value" ;;
      FragmentPath) fragment="$value" ;;
      DropInPaths) dropins="$value" ;;
      NeedDaemonReload) reload="$value" ;;
      ActiveState) active="$value" ;;
      SubState) sub="$value" ;;
      UnitFileState) enabled="$value" ;;
      *) return 1 ;;
    esac
  done <<<"$output"
  [[ "$load" == loaded && "$fragment" == "$expected_fragment" && -z "$dropins" &&
     "$reload" == no ]] || return 1
  if [[ "$kind" == timer ]]; then
    [[ "$enabled" == enabled && "$active" == active && "$sub" == waiting ]]
  else
    [[ "$active" == inactive && "$sub" == dead ]]
  fi
}

verify_backup_result() {
  local unit="$1" output
  output="$("$SYSTEMCTL" show "$unit" --property=ActiveState --property=SubState \
    --property=Result --property=ExecMainStatus)" || return 1
  [[ "$output" == *$'ActiveState=inactive'* && "$output" == *$'SubState=dead'* &&
     "$output" == *$'Result=success'* && "$output" == *$'ExecMainStatus=0'* ]]
}

resolve_prune_readiness_file() {
  local backup_env="$1" line value='' count=0
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ "$line" != *$'\r'* ]] || fail 'backup environment contains CR bytes'
    case "$line" in
      BACKUP_PRUNE_READINESS_FILE=*)
        count=$((count + 1))
        value="${line#BACKUP_PRUNE_READINESS_FILE=}"
        ;;
    esac
  done <"$backup_env"
  ((count <= 1)) || fail 'backup environment repeats BACKUP_PRUNE_READINESS_FILE'
  if ((count == 0)); then
    value='/var/backups/portfolio/prune-initial-dry-run.json'
  fi
  [[ "$value" =~ ^/[A-Za-z0-9._/-]+$ && "$value" != / && "$value" != *//* ]] ||
    fail 'BACKUP_PRUNE_READINESS_FILE is not a canonical absolute path'
  printf '%s\n' "$value"
}

verify_prune_runtime() {
  local release_root="$1" python guard stdout stderr resolved mode
  python='/opt/portfolio/cos-prune-venv/bin/python3'
  guard="$release_root/ops/deploy/backup/cos-prune-guard.py"
  validate_root_file "$guard" 600 'release-local COS prune guard'
  [[ -f "$python" && -x "$python" ]] || fail 'fixed COS prune Python runtime is unavailable'
  resolved="$(realpath -e -- "$python")" || fail 'fixed COS prune Python runtime cannot be resolved'
  [[ -f "$resolved" && "$(stat -Lc '%u' -- "$resolved")" == 0 ]] ||
    fail 'fixed COS prune Python runtime is not root-owned'
  mode="$(stat -Lc '%a' -- "$resolved")"
  (( (8#$mode & 8#022) == 0 )) || fail 'fixed COS prune Python runtime is writable outside root'
  WORK_DIRECTORY="$(mktemp -d)"
  chmod 0700 "$WORK_DIRECTORY"
  stdout="$WORK_DIRECTORY/runtime.stdout"
  stderr="$WORK_DIRECTORY/runtime.stderr"
  if ! "$python" -B "$guard" runtime-check >"$stdout" 2>"$stderr"; then
    fail 'COS prune runtime-check failed'
  fi
  [[ ! -s "$stderr" ]] || fail 'COS prune runtime-check wrote unexpected stderr'
  [[ "$(tr -d '\r\n' <"$stdout")" == SAFE ]] ||
    fail 'COS prune runtime-check did not return exact SAFE output'
}

clear_prune_readiness() {
  local readiness="$1" parent
  [[ -e "$readiness" || -L "$readiness" ]] || return 0
  parent="$(dirname -- "$readiness")"
  validate_root_directory "$parent" 'existing prune readiness parent'
  [[ "$(realpath -e -- "$readiness")" == "$readiness" ]] ||
    fail 'existing prune readiness path is non-canonical'
  validate_root_file "$readiness" 600 'existing prune readiness evidence'
  rm -f -- "$readiness"
  sync -f "$parent"
  [[ ! -e "$readiness" && ! -L "$readiness" ]] ||
    fail 'existing prune readiness evidence was not removed'
}

verify_prune_readiness() {
  local release_root="$1" readiness="$2" parent guard requirements wrapper
  local guard_sha requirements_sha wrapper_sha
  parent="$(dirname -- "$readiness")"
  validate_root_directory "$parent" 'initial prune readiness parent'
  [[ -e "$readiness" || -L "$readiness" ]] ||
    fail 'initial prune readiness evidence was not freshly generated'
  [[ "$(realpath -e -- "$readiness")" == "$readiness" ]] ||
    fail 'initial prune readiness path is non-canonical'
  validate_root_file "$readiness" 600 'initial prune readiness evidence'
  guard="$release_root/ops/deploy/backup/cos-prune-guard.py"
  requirements="$release_root/ops/deploy/backup/requirements-cos-prune.txt"
  wrapper="$release_root/ops/deploy/backup/prune-guard.example.sh"
  validate_root_file "$guard" 600 'release-local COS prune guard'
  validate_root_file "$requirements" 600 'release-local COS prune requirements lock'
  validate_root_file "$wrapper" 700 'release-local COS prune wrapper'
  guard_sha="$(sha256sum -- "$guard" | awk '{print $1}')"
  requirements_sha="$(sha256sum -- "$requirements" | awk '{print $1}')"
  wrapper_sha="$(sha256sum -- "$wrapper" | awk '{print $1}')"
  jq -e \
    --arg guardSha256 "$guard_sha" \
    --arg requirementsSha256 "$requirements_sha" \
    --arg wrapperSha256 "$wrapper_sha" '
      keys == ["binding","completedAt","schemaVersion"] and
      .schemaVersion == 1 and
      (.completedAt | type == "string" and
        test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")) and
      (.binding | keys == ["destination","guardSha256","policy",
        "requirementsSha256","wrapperSha256"]) and
      (.binding.destination | type == "object" and
        keys == ["accountId","bucket","prefix","principalId","region"] and
        all(.[]; type == "string" and length > 0)) and
      .binding.guardSha256 == $guardSha256 and
      .binding.requirementsSha256 == $requirementsSha256 and
      .binding.wrapperSha256 == $wrapperSha256 and
      (.binding.policy | keys == ["daily","monthly","safetyDays","weekly"] and
        .daily == 7 and .weekly == 4 and .monthly == 6 and
        (.safetyDays | type == "number" and floor == . and . >= 1 and . <= 9999))
    ' "$readiness" >/dev/null || fail 'initial prune readiness evidence is invalid or stale'
}

main() {
  [[ $# -le 1 ]] || fail 'usage: install-backup-units.sh [--run-initial-backup]'
  if (($# == 1)); then
    [[ "$1" == --run-initial-backup ]] ||
      fail 'usage: install-backup-units.sh [--run-initial-backup]'
    RUN_INITIAL_BACKUP=true
  fi
  local command
  for command in awk chmod dirname flock grep id install jq mktemp mv realpath rm sha256sum stat sync tr wc; do
    require_command "$command"
  done
  [[ "$(id -u)" -eq 0 ]] || fail 'backup unit installation must run as root'
  [[ -x "$SYSTEMCTL" && -f "$SYSTEMCTL" && ! -L "$SYSTEMCTL" &&
     "$(stat -Lc '%u' -- "$SYSTEMCTL")" == 0 ]] || fail 'systemctl command is unsafe'
  local systemctl_mode
  systemctl_mode="$(stat -Lc '%a' -- "$SYSTEMCTL")"
  (( (8#$systemctl_mode & 8#022) == 0 )) || fail 'systemctl command is writable outside root'
  PORTFOLIO_ROOT="$(realpath -e -- "$PORTFOLIO_ROOT")"
  SYSTEMD_ROOT="$(realpath -e -- "$SYSTEMD_ROOT")"
  validate_root_directory "$PORTFOLIO_ROOT" 'PORTFOLIO_ROOT'
  validate_root_directory "$SYSTEMD_ROOT" 'systemd unit root'
  # Serialize before observing any mutable release marker or stable dispatcher.
  # Deploy and rollback use this same lock, so every source below belongs to the
  # current release at the instant this installation transaction starts.
  acquire_lock
  # Quiesce an earlier release before reading any mutable release state.  Every
  # later failure therefore leaves remote deletion scheduling disabled.
  quiesce_prune_timer
  validate_root_file "$PORTFOLIO_ROOT/current-release" 640 'current-release marker'
  local release_id release_root source_root stable_dispatcher backup_env
  local release_dispatcher stable_dispatcher_sha release_dispatcher_sha
  release_id="$(tr -d '\r\n' <"$PORTFOLIO_ROOT/current-release")"
  [[ "$release_id" =~ ^[0-9a-f]{12}-[0-9a-f]{12}$ &&
     "$(wc -l <"$PORTFOLIO_ROOT/current-release" | tr -d '[:space:]')" == 1 ]] ||
    fail 'current-release marker is malformed'
  release_root="$(realpath -e -- "$PORTFOLIO_ROOT/releases/$release_id")"
  [[ "$release_root" == "$PORTFOLIO_ROOT/releases/$release_id" && ! -L "$release_root" ]] ||
    fail 'current release escaped its reviewed root'
  validate_root_directory "$release_root" 'current release root'
  source_root="$release_root/ops/deploy/systemd"
  validate_root_directory "$source_root" 'release-local systemd source root'
  stable_dispatcher="${PORTFOLIO_BACKUP_DISPATCH_PATH:-/usr/local/libexec/portfolio/backup-dispatch.sh}"
  validate_root_file "$stable_dispatcher" 755 'stable backup dispatcher'
  release_dispatcher="$release_root/ops/deploy/backup/backup-dispatch.sh"
  validate_root_file "$release_dispatcher" 755 'release-local backup dispatcher'
  stable_dispatcher_sha="$(sha256sum "$stable_dispatcher" | awk '{print $1}')"
  release_dispatcher_sha="$(sha256sum "$release_dispatcher" | awk '{print $1}')"
  [[ "$stable_dispatcher_sha" == "$release_dispatcher_sha" ]] ||
    fail 'stable backup dispatcher does not match current release'
  backup_env="${PORTFOLIO_BACKUP_ENV_FILE:-/etc/portfolio/backup.env}"
  validate_root_file "$backup_env" 600 'backup environment'

  local unit
  for unit in portfolio-backup.service portfolio-backup.timer \
    portfolio-backup-prune.service portfolio-backup-prune-readiness.service \
    portfolio-backup-prune.timer; do
    install_or_verify_unit "$source_root/$unit" "$SYSTEMD_ROOT/$unit"
  done
  "$SYSTEMCTL" daemon-reload
  verify_effective_unit portfolio-backup.service "$SYSTEMD_ROOT/portfolio-backup.service" service ||
    fail 'effective backup service is overridden, stale, or invalid'
  verify_effective_unit portfolio-backup-prune.service \
    "$SYSTEMD_ROOT/portfolio-backup-prune.service" service ||
    fail 'effective backup prune service is overridden, stale, or invalid'
  verify_effective_unit portfolio-backup-prune-readiness.service \
    "$SYSTEMD_ROOT/portfolio-backup-prune-readiness.service" service ||
    fail 'effective backup prune readiness service is overridden, stale, or invalid'
  local readiness_file
  readiness_file="$(resolve_prune_readiness_file "$backup_env")"
  clear_prune_readiness "$readiness_file"
  verify_prune_runtime "$release_root"
  "$SYSTEMCTL" start portfolio-backup-prune-readiness.service
  verify_backup_result portfolio-backup-prune-readiness.service ||
    fail 'initial backup prune dry-run service failed'
  verify_prune_readiness "$release_root" "$readiness_file"
  "$SYSTEMCTL" enable --now portfolio-backup.timer
  PRUNE_TIMER_ENABLE_ATTEMPTED=true
  "$SYSTEMCTL" enable --now portfolio-backup-prune.timer
  verify_effective_unit portfolio-backup.timer "$SYSTEMD_ROOT/portfolio-backup.timer" timer ||
    fail 'effective backup timer is not enabled and waiting'
  verify_effective_unit portfolio-backup-prune.timer \
    "$SYSTEMD_ROOT/portfolio-backup-prune.timer" timer ||
    fail 'effective backup prune timer is not enabled and waiting'
  if [[ "$RUN_INITIAL_BACKUP" == true ]]; then
    "$SYSTEMCTL" start portfolio-backup.service
    verify_backup_result portfolio-backup.service || fail 'initial verified backup failed'
  fi
  INSTALL_SUCCEEDED=true
  printf 'PASS: installed and enabled hardened backup units for release %s\n' "$release_id"
}

main "$@"
