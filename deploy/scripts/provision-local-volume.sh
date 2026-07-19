#!/usr/bin/env bash
set -euo pipefail
set +x
umask 077

export LC_ALL=C

PORTFOLIO_ETC_ROOT="${PORTFOLIO_ETC_ROOT:-/etc/portfolio}"
VOLUME_NAME='portfolio-local-media'
READ_ID_FROM_STDIN=false
APPLICATION_UID=10001
APPLICATION_GID=10001
ENV_STATE=''
ENV_VOLUME_NAME=''
ENV_MOUNTPOINT=''
ENV_VOLUME_ID=''
MARKER_STATE=''
MARKER_VOLUME_ID=''
JOURNAL_FILE=''

fail() {
  printf 'portfolio Local volume provisioning failed: %s\n' "$1" >&2
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

validate_lock_binding() {
  local lock_file="$1" fd="$2" label="$3" default_file="$4"
  local requested_parent parent parent_mode lock_mode
  requested_parent="$(dirname -- "$lock_file")"
  [[ -d "$requested_parent" && ! -L "$requested_parent" ]] ||
    fail "$label lock parent is missing or linked"
  parent="$(realpath -e -- "$requested_parent")"
  [[ "$parent" == "$requested_parent" && "$parent" != / &&
     "$(stat -Lc '%u:%g' -- "$parent")" == 0:0 ]] ||
    fail "$label lock parent is non-canonical or not root:root"
  parent_mode="$(stat -Lc '%a' -- "$parent")"
  if [[ "$lock_file" == "$default_file" ]]; then
    [[ "$parent_mode" == 700 ]] || fail "$label default lock parent mode is not 0700"
  else
    (( (8#$parent_mode & 8#022) == 0 )) ||
      fail "$label lock parent is writable outside root"
  fi
  [[ -f "$lock_file" && ! -L "$lock_file" &&
     "$(stat -Lc '%u:%g:%h' -- "$lock_file")" == 0:0:1 ]] ||
    fail "$label lock is not a root-owned single-link regular file"
  lock_mode="$(stat -Lc '%a' -- "$lock_file")"
  (( (8#$lock_mode & 8#022) == 0 )) || fail "$label lock is writable outside root"
  [[ "$(stat -Lc '%d:%i' -- "$lock_file")" == \
     "$(stat -Lc '%d:%i' -- "/proc/self/fd/$fd")" ]] ||
    fail "$label lock descriptor identity changed"
}

prepare_lock_file() {
  local lock_file="$1" label="$2" default_file="$3" requested_parent parent parent_mode
  [[ "$lock_file" == /* && "$lock_file" != / &&
     "$lock_file" != *$'\n'* && "$lock_file" != *$'\r'* ]] ||
    fail "$label lock path is invalid"
  requested_parent="$(dirname -- "$lock_file")"
  [[ -d "$requested_parent" && ! -L "$requested_parent" ]] ||
    fail "$label lock parent is missing or linked"
  parent="$(realpath -e -- "$requested_parent")"
  [[ "$parent" == "$requested_parent" && "$parent" != / &&
     "$(stat -Lc '%u:%g' -- "$parent")" == 0:0 ]] ||
    fail "$label lock parent is non-canonical or not root:root"
  parent_mode="$(stat -Lc '%a' -- "$parent")"
  if [[ "$lock_file" == "$default_file" ]]; then
    [[ "$parent_mode" == 700 ]] || fail "$label default lock parent mode is not 0700"
  else
    (( (8#$parent_mode & 8#022) == 0 )) ||
      fail "$label lock parent is writable outside root"
  fi
  if [[ ! -e "$lock_file" && ! -L "$lock_file" ]]; then
    (umask 077; set -C; : >"$lock_file") 2>/dev/null || true
  fi
}

acquire_operation_locks() {
  local deploy_lock="${PORTFOLIO_DEPLOY_LOCK_FILE:-/run/lock/portfolio/deploy.lock}"
  local local_lock="${PORTFOLIO_LOCAL_VOLUME_LOCK_FILE:-/run/lock/portfolio/local-volume.lock}"
  [[ "$deploy_lock" != "$local_lock" ]] || fail 'deploy and Local volume locks must be distinct'

  # Fixed global order: every provision attempt takes deploy first, then Local.
  prepare_lock_file "$deploy_lock" deploy /run/lock/portfolio/deploy.lock
  exec 9<>"$deploy_lock"
  validate_lock_binding "$deploy_lock" 9 deploy /run/lock/portfolio/deploy.lock
  flock -x 9
  validate_lock_binding "$deploy_lock" 9 deploy /run/lock/portfolio/deploy.lock

  prepare_lock_file "$local_lock" 'Local volume' /run/lock/portfolio/local-volume.lock
  exec 8<>"$local_lock"
  validate_lock_binding "$local_lock" 8 'Local volume' /run/lock/portfolio/local-volume.lock
  flock -x 8
  validate_lock_binding "$local_lock" 8 'Local volume' /run/lock/portfolio/local-volume.lock
}

parse_arguments() {
  while (($#)); do
    case "$1" in
      --volume-name)
        (($# >= 2)) || fail '--volume-name requires a value'
        VOLUME_NAME="$2"
        shift 2
        ;;
      --volume-id-stdin)
        [[ "$READ_ID_FROM_STDIN" == false ]] || fail 'duplicate --volume-id-stdin'
        READ_ID_FROM_STDIN=true
        shift
        ;;
      *) fail 'usage: provision-local-volume.sh [--volume-name NAME] [--volume-id-stdin]' ;;
    esac
  done
  [[ "$VOLUME_NAME" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$ ]] ||
    fail 'volume name is invalid'
}

read_environment_state() {
  local environment_file="$1" line count_name=0 count_root=0 count_id=0
  [[ "$environment_file" == /* && -f "$environment_file" && ! -L "$environment_file" &&
     "$(stat -Lc '%u:%g:%a:%h' -- "$environment_file")" == 0:0:600:1 ]] ||
    fail 'portfolio.env must be a root:root mode 0600 single-link regular file'
  ENV_VOLUME_NAME=''; ENV_MOUNTPOINT=''; ENV_VOLUME_ID=''
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ "$line" != *$'\r'* ]] || fail 'portfolio.env contains a carriage return'
    case "$line" in
      PORTFOLIO_LOCAL_VOLUME_NAME=*)
        ((count_name += 1)); ENV_VOLUME_NAME="${line#*=}"
        ;;
      PORTFOLIO_LOCAL_HOST_ROOT=*)
        ((count_root += 1)); ENV_MOUNTPOINT="${line#*=}"
        ;;
      PORTFOLIO_LOCAL_VOLUME_ID=*)
        ((count_id += 1)); ENV_VOLUME_ID="${line#*=}"
        ;;
    esac
  done <"$environment_file"
  ((count_name <= 1 && count_root <= 1 && count_id <= 1)) ||
    fail 'portfolio.env contains a duplicate Local volume key'
  if ((count_name == 0 && count_root == 0 && count_id == 0)); then
    ENV_STATE=missing
  elif ((count_name == 1 && count_root == 1 && count_id == 1)); then
    ENV_STATE=complete
  else
    ENV_STATE=partial
  fi
}

read_marker_state() {
  local marker="$1"
  MARKER_VOLUME_ID=''
  if [[ ! -e "$marker" && ! -L "$marker" ]]; then
    MARKER_STATE=missing
    return
  fi
  [[ -f "$marker" && ! -L "$marker" &&
     "$(stat -Lc '%u:%g:%a:%h' -- "$marker")" == \
       "$APPLICATION_UID:$APPLICATION_GID:600:1" ]] ||
    fail 'Local volume identity marker is unsafe'
  [[ "$(wc -l <"$marker" | tr -d '[:space:]')" == 1 ]] ||
    fail 'Local volume identity marker must contain exactly one line'
  IFS= read -r MARKER_VOLUME_ID <"$marker" || fail 'Local volume identity marker is unreadable'
  [[ "$MARKER_VOLUME_ID" =~ ^[A-Za-z0-9_-]{32,128}$ ]] ||
    fail 'Local volume identity marker has an invalid format'
  MARKER_STATE=complete
}

environment_matches() {
  local mountpoint="$1" volume_id="$2"
  [[ "$ENV_STATE" == complete && "$ENV_VOLUME_NAME" == "$VOLUME_NAME" &&
     "$ENV_MOUNTPOINT" == "$mountpoint" && "$ENV_VOLUME_ID" == "$volume_id" ]]
}

write_protected_environment_once() {
  local environment_file="$1" mountpoint="$2" volume_id="$3" temporary line
  [[ "$ENV_STATE" == missing ]] || fail 'refusing to overwrite an existing Local volume environment binding'
  temporary="$(mktemp "$PORTFOLIO_ETC_ROOT/.portfolio.env.local-volume.XXXXXX")"
  while IFS= read -r line || [[ -n "$line" ]]; do
    printf '%s\n' "$line" >>"$temporary"
  done <"$environment_file"
  {
    printf 'PORTFOLIO_LOCAL_VOLUME_NAME=%s\n' "$VOLUME_NAME"
    printf 'PORTFOLIO_LOCAL_HOST_ROOT=%s\n' "$mountpoint"
    printf 'PORTFOLIO_LOCAL_VOLUME_ID=%s\n' "$volume_id"
  } >>"$temporary"
  chown root:root "$temporary"
  chmod 0600 "$temporary"
  sync -f "$temporary"
  mv -fT -- "$temporary" "$environment_file"
  sync -f "$PORTFOLIO_ETC_ROOT"
}

write_marker_once() {
  local mountpoint="$1" marker="$2" volume_id="$3" temporary
  [[ ! -e "$marker" && ! -L "$marker" ]] || fail 'refusing to overwrite a Local volume identity marker'
  temporary="$(mktemp "$mountpoint/.portfolio-volume-id.XXXXXX")"
  printf '%s\n' "$volume_id" >"$temporary"
  chown "$APPLICATION_UID:$APPLICATION_GID" "$temporary"
  chmod 0600 "$temporary"
  sync -f "$temporary"
  if ! ln -- "$temporary" "$marker"; then
    rm -f -- "$temporary"
    fail 'Local volume identity marker appeared during create-only publication'
  fi
  rm -f -- "$temporary"
  sync -f "$mountpoint"
}

create_pending_journal() {
  local mountpoint="$1" volume_id="$2" temporary
  [[ ! -e "$JOURNAL_FILE" && ! -L "$JOURNAL_FILE" ]] ||
    fail 'Local volume pending journal already exists'
  temporary="$(mktemp "$PORTFOLIO_ETC_ROOT/.local-volume.pending.XXXXXX")"
  jq -Scn --arg volumeName "$VOLUME_NAME" --arg mountpoint "$mountpoint" \
    --arg volumeId "$volume_id" \
    '{schemaVersion:1,volumeName:$volumeName,mountpoint:$mountpoint,volumeId:$volumeId}' \
    >"$temporary"
  chown root:root "$temporary"
  chmod 0600 "$temporary"
  sync -f "$temporary"
  if ! ln -- "$temporary" "$JOURNAL_FILE"; then
    rm -f -- "$temporary"
    fail 'Local volume pending journal publication collided'
  fi
  rm -f -- "$temporary"
  sync -f "$PORTFOLIO_ETC_ROOT"
}

read_pending_journal() {
  local mountpoint="$1"
  [[ -f "$JOURNAL_FILE" && ! -L "$JOURNAL_FILE" &&
     "$(stat -Lc '%u:%g:%a:%h' -- "$JOURNAL_FILE")" == 0:0:600:1 ]] ||
    fail 'Local volume pending journal is unsafe'
  jq -e --arg name "$VOLUME_NAME" --arg mountpoint "$mountpoint" '
    (keys | sort) == (["mountpoint","schemaVersion","volumeId","volumeName"] | sort) and
    .schemaVersion == 1 and .volumeName == $name and .mountpoint == $mountpoint and
    (.volumeId | type == "string" and test("^[A-Za-z0-9_-]{32,128}$"))
  ' "$JOURNAL_FILE" >/dev/null || fail 'Local volume pending journal does not match the inspected volume'
  jq -r '.volumeId' "$JOURNAL_FILE"
}

remove_pending_journal() {
  [[ -f "$JOURNAL_FILE" && ! -L "$JOURNAL_FILE" &&
     "$(stat -Lc '%u:%g:%a:%h' -- "$JOURNAL_FILE")" == 0:0:600:1 ]] ||
    fail 'Local volume pending journal changed before completion'
  rm -f -- "$JOURNAL_FILE"
  sync -f "$PORTFOLIO_ETC_ROOT"
}

contract_test_failpoint_enabled() {
  local fixture_root source_path scope mode
  [[ "${PORTFOLIO_CONTRACT_TEST_MODE:-}" == portfolio-state-machine-v1 ]] || return 1
  [[ -n "${PORTFOLIO_CONTRACT_TEST_ROOT:-}" &&
     -d "$PORTFOLIO_CONTRACT_TEST_ROOT" && ! -L "$PORTFOLIO_CONTRACT_TEST_ROOT" ]] || return 1
  fixture_root="$(realpath -e -- "$PORTFOLIO_CONTRACT_TEST_ROOT")" || return 1
  [[ "$fixture_root" == "$PORTFOLIO_CONTRACT_TEST_ROOT" &&
     "$(stat -Lc '%u' -- "$fixture_root")" == 0 ]] || return 1
  mode="$(stat -Lc '%a' -- "$fixture_root")" || return 1
  (( (8#$mode & 8#022) == 0 )) || return 1
  case "$fixture_root/" in
    /tmp/*|/workspace/*) ;;
    *) return 1 ;;
  esac
  source_path="$(realpath -e -- "${BASH_SOURCE[0]}")" || return 1
  case "$source_path" in
    /workspace/*|"$fixture_root"/*) ;;
    *) return 1 ;;
  esac
  scope="$(realpath -e -- "$PORTFOLIO_ETC_ROOT")" || return 1
  [[ "$scope" == "$fixture_root"/* ]]
}

crash_after() {
  [[ "${PORTFOLIO_TEST_LOCAL_VOLUME_CRASH_AFTER:-}" == "$1" ]] || return 0
  contract_test_failpoint_enabled || return 0
  kill -KILL "$$"
}

verify_api_write_probe() {
  local mountpoint="$1" probe="$mountpoint/.portfolio-write-probe.$$"
  # Docker's volume parent directories are intentionally not traversable by the
  # application UID.  Enter the volume while privileged, then test the same
  # write operation using a relative path as the runtime UID.
  # shellcheck disable=SC2016
  (
    cd -- "$mountpoint"
    setpriv --reuid="$APPLICATION_UID" --regid="$APPLICATION_GID" --clear-groups \
      sh -c 'umask 077; : >"$1"; rm -f -- "$1"' sh "./${probe##*/}"
  ) ||
    fail 'API UID/GID cannot initialize and write the Local volume'
  [[ ! -e "$probe" && ! -L "$probe" ]] || fail 'Local volume write probe was not removed'
}

verify_complete_binding() {
  local mountpoint="$1" marker="$2" volume_id="$3" environment_file="$4"
  read_marker_state "$marker"
  read_environment_state "$environment_file"
  [[ "$MARKER_STATE" == complete && "$MARKER_VOLUME_ID" == "$volume_id" ]] ||
    fail 'Local volume marker differs from the audited binding'
  environment_matches "$mountpoint" "$volume_id" ||
    fail 'portfolio.env differs from the audited Local volume binding'
  [[ "$(stat -Lc '%u:%g:%a' -- "$mountpoint")" == \
     "$APPLICATION_UID:$APPLICATION_GID:700" ]] || fail 'Local volume root mode is unsafe'
  verify_api_write_probe "$mountpoint"
}

main() {
  parse_arguments "$@"
  [[ "$(id -u)" == 0 ]] || fail 'must run as root'
  local command_name
  for command_name in chown chmod dirname docker find flock id jq ln mktemp mv openssl \
    realpath setpriv sha256sum sh stat sync tr wc; do
    require_command "$command_name"
  done
  [[ "$PORTFOLIO_ETC_ROOT" == /* && -d "$PORTFOLIO_ETC_ROOT" &&
     ! -L "$PORTFOLIO_ETC_ROOT" ]] || fail 'PORTFOLIO_ETC_ROOT is unsafe'
  PORTFOLIO_ETC_ROOT="$(realpath -e -- "$PORTFOLIO_ETC_ROOT")"
  [[ "$PORTFOLIO_ETC_ROOT" != / ]] || fail 'PORTFOLIO_ETC_ROOT cannot be the filesystem root'
  validate_root_directory "$PORTFOLIO_ETC_ROOT" 'PORTFOLIO_ETC_ROOT'
  local environment_file="$PORTFOLIO_ETC_ROOT/portfolio.env"
  JOURNAL_FILE="$PORTFOLIO_ETC_ROOT/.local-volume-provision.pending.json"

  acquire_operation_locks

  if ! docker volume inspect "$VOLUME_NAME" >/dev/null 2>&1; then
    docker volume create --label portfolio.volume-role=local-media "$VOLUME_NAME" >/dev/null
  fi
  local identity inspected_name driver role mountpoint marker volume_id
  identity="$(docker volume inspect --format \
    '{{.Name}}|{{.Driver}}|{{index .Labels "portfolio.volume-role"}}|{{.Mountpoint}}' \
    "$VOLUME_NAME")" || fail 'Local volume cannot be inspected'
  IFS='|' read -r inspected_name driver role mountpoint <<<"$identity"
  [[ "$inspected_name" == "$VOLUME_NAME" && "$driver" == local &&
     "$role" == local-media && -n "$mountpoint" ]] ||
    fail 'Local volume identity is ambiguous or not provisioner-owned'
  [[ "$mountpoint" == /* && -d "$mountpoint" && ! -L "$mountpoint" ]] ||
    fail 'Local volume Mountpoint is unsafe'
  mountpoint="$(realpath -e -- "$mountpoint")"
  marker="$mountpoint/.portfolio-volume-id"
  read_marker_state "$marker"
  read_environment_state "$environment_file"

  if [[ -e "$JOURNAL_FILE" || -L "$JOURNAL_FILE" ]]; then
    volume_id="$(read_pending_journal "$mountpoint")"
    [[ "$READ_ID_FROM_STDIN" == false ]] ||
      fail '--volume-id-stdin is forbidden while a pending binding is being recovered'
    if [[ "$MARKER_STATE" == complete && "$MARKER_VOLUME_ID" != "$volume_id" ]]; then
      fail 'Local volume marker conflicts with the pending journal'
    fi
    if [[ "$ENV_STATE" == partial ]] ||
       { [[ "$ENV_STATE" == complete ]] && ! environment_matches "$mountpoint" "$volume_id"; }; then
      fail 'portfolio.env conflicts with the pending Local volume journal'
    fi
    chown "$APPLICATION_UID:$APPLICATION_GID" "$mountpoint"
    chmod 0700 "$mountpoint"
    if [[ "$MARKER_STATE" == missing ]]; then
      write_marker_once "$mountpoint" "$marker" "$volume_id"
      crash_after marker
    fi
    if [[ "$ENV_STATE" == missing ]]; then
      write_protected_environment_once "$environment_file" "$mountpoint" "$volume_id"
      crash_after environment
    fi
    verify_complete_binding "$mountpoint" "$marker" "$volume_id" "$environment_file"
    remove_pending_journal
    printf 'PASS: recovered Local media volume binding for API UID/GID 10001\n'
    return
  fi

  if [[ "$MARKER_STATE" == complete ]]; then
    [[ "$READ_ID_FROM_STDIN" == false ]] ||
      fail '--volume-id-stdin cannot rotate an existing Local volume binding'
    [[ "$ENV_STATE" == complete ]] ||
      fail 'existing Local volume marker has no complete portfolio.env binding'
    environment_matches "$mountpoint" "$MARKER_VOLUME_ID" ||
      fail 'existing Local volume marker and portfolio.env binding disagree'
    verify_complete_binding "$mountpoint" "$marker" "$MARKER_VOLUME_ID" "$environment_file"
    printf 'PASS: existing Local media volume binding is unchanged\n'
    return
  fi

  [[ "$ENV_STATE" == missing ]] ||
    fail 'portfolio.env contains a Local volume binding without an audited marker or pending journal'
  [[ -z "$(find "$mountpoint" -mindepth 1 -maxdepth 1 -print -quit)" ]] ||
    fail 'uninitialized Local volume is not empty'
  if [[ "$READ_ID_FROM_STDIN" == true ]]; then
    IFS= read -r volume_id || fail 'could not read the explicit volume ID'
  else
    volume_id="$(openssl rand -hex 32)"
  fi
  [[ "$volume_id" =~ ^[A-Za-z0-9_-]{32,128}$ ]] || fail 'volume ID has an invalid format'

  create_pending_journal "$mountpoint" "$volume_id"
  crash_after journal
  chown "$APPLICATION_UID:$APPLICATION_GID" "$mountpoint"
  chmod 0700 "$mountpoint"
  write_marker_once "$mountpoint" "$marker" "$volume_id"
  crash_after marker
  write_protected_environment_once "$environment_file" "$mountpoint" "$volume_id"
  crash_after environment
  verify_complete_binding "$mountpoint" "$marker" "$volume_id" "$environment_file"
  remove_pending_journal
  printf 'PASS: provisioned Local media volume %s for API UID/GID 10001\n' "$VOLUME_NAME"
}

main "$@"
