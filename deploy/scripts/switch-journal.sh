#!/usr/bin/env bash

# Durable, root-only transaction journal for the release environment and the
# current-admin/current-ops/current-release pointer set.  This file is sourced
# by the deployment controllers; it deliberately does not change shell options.

switch_journal_error() {
  printf 'portfolio switch journal: %s\n' "$1" >&2
  return 1
}

switch_journal_path() {
  printf '%s/.portfolio-switch-journal.json\n' "${PORTFOLIO_ROOT:?PORTFOLIO_ROOT is required}"
}

switch_journal_backup_path() {
  printf '%s/.portfolio-switch-old-release.env\n' \
    "${PORTFOLIO_ETC_ROOT:?PORTFOLIO_ETC_ROOT is required}"
}

switch_journal_release_env_path() {
  printf '%s\n' "${PORTFOLIO_RELEASE_ENV:-${PORTFOLIO_ETC_ROOT:?}/release.env}"
}

switch_journal_nginx_env_path() {
  printf '%s\n' "${PORTFOLIO_NGINX_ENV:-${PORTFOLIO_ETC_ROOT:?}/nginx.env}"
}

switch_journal_is_release_id() {
  [[ "$1" =~ ^[0-9a-f]{12}-[0-9a-f]{12}$ ]]
}

switch_journal_require_command() {
  command -v "$1" >/dev/null 2>&1 || switch_journal_error "$1 is required"
}

switch_journal_validate_lock_binding() {
  local lock_file="$1" fd="$2" requested_parent parent parent_mode lock_mode
  [[ "$lock_file" == /* && "$lock_file" != / &&
     "$lock_file" != *$'\n'* && "$lock_file" != *$'\r'* ]] ||
    switch_journal_error 'deploy lock path is invalid' || return 1
  requested_parent="$(dirname -- "$lock_file")"
  [[ -d "$requested_parent" && ! -L "$requested_parent" ]] ||
    switch_journal_error 'deploy lock parent is missing or linked' || return 1
  parent="$(realpath -e -- "$requested_parent")" || return 1
  [[ "$parent" == "$requested_parent" && "$parent" != / &&
     "$(stat -Lc '%u:%g' -- "$parent")" == 0:0 ]] ||
    switch_journal_error 'deploy lock parent is non-canonical or not root:root' || return 1
  parent_mode="$(stat -Lc '%a' -- "$parent")" || return 1
  if [[ "$lock_file" == /run/lock/portfolio/deploy.lock ]]; then
    [[ "$parent_mode" == 700 ]] ||
      switch_journal_error 'default deploy lock parent mode is not 0700' || return 1
  else
    (( (8#$parent_mode & 8#022) == 0 )) ||
      switch_journal_error 'deploy lock parent is writable outside root' || return 1
  fi
  [[ -f "$lock_file" && ! -L "$lock_file" &&
     "$(stat -Lc '%u:%g:%h' -- "$lock_file")" == 0:0:1 ]] ||
    switch_journal_error 'deploy lock is not a root-owned single-link regular file' || return 1
  lock_mode="$(stat -Lc '%a' -- "$lock_file")" || return 1
  (( (8#$lock_mode & 8#022) == 0 )) ||
    switch_journal_error 'deploy lock is writable outside root' || return 1
  [[ -e "/proc/self/fd/$fd" &&
     "$(stat -Lc '%d:%i' -- "$lock_file")" == "$(stat -Lc '%d:%i' -- "/proc/self/fd/$fd")" ]] ||
    switch_journal_error 'deploy lock descriptor identity differs' || return 1
  flock -n "$fd" || switch_journal_error 'caller does not hold the deploy lock'
}

switch_journal_assert_lock() {
  local lock_file="${PORTFOLIO_DEPLOY_LOCK_FILE:-/run/lock/portfolio/deploy.lock}"
  local fd="${PORTFOLIO_DEPLOY_LOCK_FD:-}"
  [[ "${PORTFOLIO_DEPLOY_LOCK_HELD:-false}" == true && "$fd" =~ ^[0-9]+$ ]] ||
    switch_journal_error 'the exclusive deploy lock is required' || return 1
  switch_journal_validate_lock_binding "$lock_file" "$fd"
}

switch_journal_acquire_or_verify_lock() {
  local lock_file="${1:-${PORTFOLIO_DEPLOY_LOCK_FILE:-/run/lock/portfolio/deploy.lock}}"
  local requested_parent parent parent_mode lock_mode
  if [[ "${PORTFOLIO_DEPLOY_LOCK_HELD:-false}" == true ]]; then
    PORTFOLIO_DEPLOY_LOCK_FILE="$lock_file"
    export PORTFOLIO_DEPLOY_LOCK_FILE
    switch_journal_assert_lock
    return
  fi
  [[ "$(id -u)" -eq 0 ]] || switch_journal_error 'deploy lock requires root' || return 1
  [[ "$lock_file" == /* && "$lock_file" != / &&
     "$lock_file" != *$'\n'* && "$lock_file" != *$'\r'* ]] ||
    switch_journal_error 'deploy lock path is invalid' || return 1
  requested_parent="$(dirname -- "$lock_file")"
  [[ -d "$requested_parent" && ! -L "$requested_parent" ]] ||
    switch_journal_error 'deploy lock parent is missing or linked' || return 1
  parent="$(realpath -e -- "$requested_parent")" || return 1
  [[ "$parent" == "$requested_parent" && "$parent" != / &&
     "$(stat -Lc '%u:%g' -- "$parent")" == 0:0 ]] ||
    switch_journal_error 'deploy lock parent is non-canonical or not root:root' || return 1
  parent_mode="$(stat -Lc '%a' -- "$parent")" || return 1
  if [[ "$lock_file" == /run/lock/portfolio/deploy.lock ]]; then
    [[ "$parent_mode" == 700 ]] ||
      switch_journal_error 'default deploy lock parent mode is not 0700' || return 1
  else
    (( (8#$parent_mode & 8#022) == 0 )) ||
      switch_journal_error 'deploy lock parent is writable outside root' || return 1
  fi
  if [[ ! -e "$lock_file" && ! -L "$lock_file" ]]; then
    (umask 077; set -C; : >"$lock_file") 2>/dev/null || true
  fi
  [[ -f "$lock_file" && ! -L "$lock_file" &&
     "$(stat -Lc '%u:%g:%h' -- "$lock_file")" == 0:0:1 ]] ||
    switch_journal_error 'deploy lock is not a root-owned single-link regular file' || return 1
  lock_mode="$(stat -Lc '%a' -- "$lock_file")" || return 1
  (( (8#$lock_mode & 8#022) == 0 )) ||
    switch_journal_error 'deploy lock is writable outside root' || return 1
  exec 9<>"$lock_file"
  [[ "$(stat -Lc '%d:%i' -- "$lock_file")" == "$(stat -Lc '%d:%i' -- /proc/self/fd/9)" ]] ||
    switch_journal_error 'deploy lock descriptor identity changed' || return 1
  flock -x 9 || return 1
  PORTFOLIO_DEPLOY_LOCK_FILE="$lock_file"
  PORTFOLIO_DEPLOY_LOCK_HELD=true
  PORTFOLIO_DEPLOY_LOCK_FD=9
  export PORTFOLIO_DEPLOY_LOCK_FILE PORTFOLIO_DEPLOY_LOCK_HELD PORTFOLIO_DEPLOY_LOCK_FD
  switch_journal_validate_lock_binding "$lock_file" 9
}

switch_journal_validate_root_file() {
  local path="$1" expected_mode="$2" label="$3" maximum_bytes="${4:-1048576}"
  local size
  [[ -f "$path" && ! -L "$path" &&
     "$(stat -Lc '%u:%g:%h:%a' -- "$path")" == "0:0:1:$expected_mode" ]] ||
    switch_journal_error "$label is not a root:root single-link mode 0$expected_mode file" || return 1
  size="$(stat -Lc '%s' -- "$path")" || return 1
  [[ "$size" =~ ^[1-9][0-9]*$ && "$size" -le "$maximum_bytes" ]] ||
    switch_journal_error "$label size is invalid" || return 1
}

switch_journal_validate() {
  local journal backup phase expected_sha actual_sha
  journal="$(switch_journal_path)" || return 1
  backup="$(switch_journal_backup_path)" || return 1
  switch_journal_validate_root_file "$journal" 600 'switch journal' 8192 || return 1
  jq -e '
    (keys | sort) == (["envBackupSha256","newRelease","oldPrevious","oldRelease",
      "operation","operationId","phase","releaseEnvGid","schemaVersion"] | sort) and
    .schemaVersion == 1 and
    (.operation == "deploy" or .operation == "rollback") and
    (.operationId | type == "string" and test("^[0-9a-f]{32}$")) and
    (.phase | type == "string" and
      IN("prepared","env","api","admin","ops","markers","verified")) and
    ((.oldRelease == null) or
      (.oldRelease | type == "string" and test("^[0-9a-f]{12}-[0-9a-f]{12}$"))) and
    ((.oldPrevious == null) or
      (.oldPrevious | type == "string" and test("^[0-9a-f]{12}-[0-9a-f]{12}$"))) and
    (.newRelease | type == "string" and test("^[0-9a-f]{12}-[0-9a-f]{12}$")) and
    (.oldRelease != .newRelease) and
    ((.oldRelease != null) or .oldPrevious == null) and
    (.releaseEnvGid | type == "number" and floor == . and . >= 0 and . <= 2147483647) and
    (.envBackupSha256 | type == "string" and test("^[0-9a-f]{64}$"))
  ' "$journal" >/dev/null || switch_journal_error 'switch journal JSON is malformed or non-canonical' || return 1
  phase="$(jq -r '.phase' "$journal")" || return 1
  if [[ -e "$backup" || -L "$backup" ]]; then
    switch_journal_validate_root_file "$backup" 600 'switch environment backup' || return 1
    expected_sha="$(jq -r '.envBackupSha256' "$journal")" || return 1
    actual_sha="$(sha256sum -- "$backup" | awk '{print $1}')" || return 1
    [[ "$actual_sha" == "$expected_sha" ]] ||
      switch_journal_error 'switch environment backup digest differs from the journal' || return 1
  elif [[ "$phase" != verified ]]; then
    switch_journal_error 'switch environment backup is missing before commit' || return 1
  fi
}

switch_journal_publish_create_only() {
  local temporary="$1" destination="$2" parent="$3" identity
  [[ ! -e "$destination" && ! -L "$destination" ]] ||
    switch_journal_error "create-only destination already exists: $destination" || return 1
  identity="$(stat -Lc '%d:%i' -- "$temporary")" || return 1
  mv -nT -- "$temporary" "$destination" || return 1
  [[ ! -e "$temporary" && ! -L "$temporary" &&
     -f "$destination" && ! -L "$destination" &&
     "$(stat -Lc '%d:%i' -- "$destination")" == "$identity" ]] ||
    switch_journal_error "create-only publication did not install the reviewed inode: $destination" || return 1
  sync -f "$destination" || return 1
  sync -f "$parent"
}

switch_journal_contract_test_failpoint_enabled() {
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
  for scope in "${PORTFOLIO_ROOT:?}" "${PORTFOLIO_ETC_ROOT:?}"; do
    scope="$(realpath -e -- "$scope")" || return 1
    [[ "$scope" == "$fixture_root"/* ]] || return 1
  done
}

switch_journal_maybe_crash() {
  local phase="$1"
  [[ "${PORTFOLIO_TEST_SWITCH_CRASH_AFTER:-}" == "$phase" ]] || return 0
  switch_journal_contract_test_failpoint_enabled || return 0
  kill -KILL "$$"
}

switch_journal_begin() {
  local operation="$1" old_release="$2" new_release="$3" old_previous="$4"
  local journal backup release_env backup_temp journal_temp backup_sha env_gid operation_id raw_uuid
  switch_journal_assert_lock || return 1
  [[ "$operation" == deploy || "$operation" == rollback ]] ||
    switch_journal_error 'switch operation is invalid' || return 1
  [[ -z "$old_release" ]] || switch_journal_is_release_id "$old_release" ||
    switch_journal_error 'old release ID is invalid' || return 1
  switch_journal_is_release_id "$new_release" ||
    switch_journal_error 'new release ID is invalid' || return 1
  [[ "$old_release" != "$new_release" ]] ||
    switch_journal_error 'old and new releases must differ' || return 1
  [[ -z "$old_previous" ]] || switch_journal_is_release_id "$old_previous" ||
    switch_journal_error 'old previous release ID is invalid' || return 1
  [[ -n "$old_release" || -z "$old_previous" ]] ||
    switch_journal_error 'an initial deployment cannot have a previous release' || return 1
  journal="$(switch_journal_path)" || return 1
  backup="$(switch_journal_backup_path)" || return 1
  release_env="$(switch_journal_release_env_path)" || return 1
  [[ ! -e "$journal" && ! -L "$journal" && ! -e "$backup" && ! -L "$backup" ]] ||
    switch_journal_error 'a switch journal or its environment backup already exists' || return 1
  [[ -f "$release_env" && ! -L "$release_env" &&
     "$(stat -Lc '%u:%h:%a' -- "$release_env")" == 0:1:640 ]] ||
    switch_journal_error 'release.env is not a root-owned single-link mode 0640 file' || return 1
  env_gid="$(stat -Lc '%g' -- "$release_env")" || return 1
  [[ "$env_gid" =~ ^[0-9]+$ && "$env_gid" -le 2147483647 ]] ||
    switch_journal_error 'release.env group ID is invalid' || return 1
  backup_temp="$(mktemp "${PORTFOLIO_ETC_ROOT}/.portfolio-switch-old-release.env.XXXXXX")" || return 1
  cp -- "$release_env" "$backup_temp" || { rm -f -- "$backup_temp"; return 1; }
  chown 0:0 "$backup_temp" || { rm -f -- "$backup_temp"; return 1; }
  chmod 0600 "$backup_temp" || { rm -f -- "$backup_temp"; return 1; }
  sync -f "$backup_temp" || { rm -f -- "$backup_temp"; return 1; }
  switch_journal_publish_create_only "$backup_temp" "$backup" "$PORTFOLIO_ETC_ROOT" || {
    rm -f -- "$backup_temp"
    return 1
  }
  backup_sha="$(sha256sum -- "$backup" | awk '{print $1}')" || return 1
  [[ -r /proc/sys/kernel/random/uuid ]] ||
    switch_journal_error 'kernel transaction ID source is unavailable' || return 1
  IFS= read -r raw_uuid </proc/sys/kernel/random/uuid || return 1
  operation_id="${raw_uuid//-/}"
  operation_id="${operation_id,,}"
  [[ "$operation_id" =~ ^[0-9a-f]{32}$ ]] ||
    switch_journal_error 'could not generate a transaction ID' || return 1
  journal_temp="$(mktemp "${PORTFOLIO_ROOT}/.portfolio-switch-journal.XXXXXX")" || return 1
  jq -Scn \
    --arg operation "$operation" \
    --arg operationId "$operation_id" \
    --arg oldRelease "$old_release" \
    --arg newRelease "$new_release" \
    --arg oldPrevious "$old_previous" \
    --argjson releaseEnvGid "$env_gid" \
    --arg envBackupSha256 "$backup_sha" \
    '{schemaVersion:1,operation:$operation,operationId:$operationId,phase:"prepared",
      oldRelease:(if $oldRelease == "" then null else $oldRelease end),newRelease:$newRelease,
      oldPrevious:(if $oldPrevious == "" then null else $oldPrevious end),
      releaseEnvGid:$releaseEnvGid,envBackupSha256:$envBackupSha256}' >"$journal_temp" || {
        rm -f -- "$journal_temp"
        return 1
      }
  chown 0:0 "$journal_temp" || { rm -f -- "$journal_temp"; return 1; }
  chmod 0600 "$journal_temp" || { rm -f -- "$journal_temp"; return 1; }
  sync -f "$journal_temp" || { rm -f -- "$journal_temp"; return 1; }
  switch_journal_publish_create_only "$journal_temp" "$journal" "$PORTFOLIO_ROOT" || {
    rm -f -- "$journal_temp"
    return 1
  }
  PORTFOLIO_SWITCH_TRANSACTION_ID="$operation_id"
  export PORTFOLIO_SWITCH_TRANSACTION_ID
  switch_journal_validate || return 1
  switch_journal_maybe_crash prepared
}

switch_journal_phase() {
  local next="$1" journal current operation_id expected temporary
  switch_journal_assert_lock || return 1
  switch_journal_validate || return 1
  journal="$(switch_journal_path)" || return 1
  operation_id="$(jq -r '.operationId' "$journal")" || return 1
  [[ -n "${PORTFOLIO_SWITCH_TRANSACTION_ID:-}" &&
     "$PORTFOLIO_SWITCH_TRANSACTION_ID" == "$operation_id" ]] ||
    switch_journal_error 'phase update is not owned by the active transaction' || return 1
  current="$(jq -r '.phase' "$journal")" || return 1
  case "$current" in
    prepared) expected='env' ;;
    env) expected='api' ;;
    api) expected='admin' ;;
    admin) expected='ops' ;;
    ops) expected='markers' ;;
    markers) expected='verified' ;;
    verified) expected='' ;;
    *) switch_journal_error 'switch journal phase is invalid'; return 1 ;;
  esac
  [[ "$next" == "$expected" ]] ||
    switch_journal_error "invalid switch phase transition: $current -> $next" || return 1
  temporary="$(mktemp "${PORTFOLIO_ROOT}/.portfolio-switch-journal.phase.XXXXXX")" || return 1
  jq -Sc --arg phase "$next" '.phase=$phase' "$journal" >"$temporary" || {
    rm -f -- "$temporary"
    return 1
  }
  chown 0:0 "$temporary" || { rm -f -- "$temporary"; return 1; }
  chmod 0600 "$temporary" || { rm -f -- "$temporary"; return 1; }
  sync -f "$temporary" || { rm -f -- "$temporary"; return 1; }
  mv -fT -- "$temporary" "$journal" || { rm -f -- "$temporary"; return 1; }
  sync -f "$PORTFOLIO_ROOT" || return 1
  switch_journal_validate || return 1
  switch_journal_maybe_crash "$next"
}

switch_journal_read_single_line() {
  local path="$1" label="$2" value=''
  [[ -f "$path" && ! -L "$path" &&
     "$(stat -Lc '%u:%h' -- "$path")" == 0:1 ]] ||
    switch_journal_error "$label is not a root-owned single-link file" || return 1
  IFS= read -r value <"$path" || true
  [[ -n "$value" && "$(wc -l <"$path" | tr -d '[:space:]')" == 1 ]] ||
    switch_journal_error "$label must contain exactly one line" || return 1
  printf '%s\n' "$value"
}

switch_journal_env_value() {
  local path="$1" key="$2"
  awk -F= -v key="$key" '
    $1 == key { count += 1; value = substr($0, index($0, "=") + 1) }
    END { if (count != 1 || value == "") exit 1; print value }
  ' "$path"
}

switch_journal_verify_state() {
  local release="$1" previous="$2" expected_env_sha="${3:-}"
  local release_env current admin_target ops_target actual_sha
  release_env="$(switch_journal_release_env_path)" || return 1
  [[ -f "$release_env" && ! -L "$release_env" &&
     "$(stat -Lc '%u:%h:%a' -- "$release_env")" == 0:1:640 ]] ||
    switch_journal_error 'release.env is unsafe while verifying switch state' || return 1
  if [[ -n "$release" ]]; then
    switch_journal_is_release_id "$release" || return 1
    current="$(switch_journal_read_single_line "$PORTFOLIO_ROOT/current-release" current-release)" ||
      return 1
    [[ "$current" == "$release" ]] ||
      switch_journal_error 'current-release differs from the expected switch state' || return 1
    [[ "$(switch_journal_env_value "$release_env" PORTFOLIO_RELEASE_ID)" == "$release" ]] ||
      switch_journal_error 'release.env differs from the expected switch state' || return 1
    if [[ -n "$expected_env_sha" ]]; then
      actual_sha="$(sha256sum -- "$release_env" | awk '{print $1}')" || return 1
      [[ "$actual_sha" == "$expected_env_sha" ]] ||
        switch_journal_error 'release.env bytes differ from the expected restored state' || return 1
    fi
    [[ -L "$PORTFOLIO_ROOT/current-admin" && -L "$PORTFOLIO_ROOT/current-ops" ]] ||
      switch_journal_error 'one or more active release pointers are missing' || return 1
    admin_target="$(readlink -f -- "$PORTFOLIO_ROOT/current-admin")" || return 1
    ops_target="$(readlink -f -- "$PORTFOLIO_ROOT/current-ops")" || return 1
    [[ "$admin_target" == "$PORTFOLIO_ROOT/releases/$release/admin" &&
       "$ops_target" == "$PORTFOLIO_ROOT/releases/$release/ops" ]] ||
      switch_journal_error 'active release pointers disagree with current-release' || return 1
  else
    [[ ! -e "$PORTFOLIO_ROOT/current-release" && ! -L "$PORTFOLIO_ROOT/current-release" &&
       ! -e "$PORTFOLIO_ROOT/current-admin" && ! -L "$PORTFOLIO_ROOT/current-admin" &&
       ! -e "$PORTFOLIO_ROOT/current-ops" && ! -L "$PORTFOLIO_ROOT/current-ops" ]] ||
      switch_journal_error 'initial release state unexpectedly contains an active pointer' || return 1
    [[ -n "$expected_env_sha" ]] ||
      switch_journal_error 'initial release state requires an environment digest' || return 1
    actual_sha="$(sha256sum -- "$release_env" | awk '{print $1}')" || return 1
    [[ "$actual_sha" == "$expected_env_sha" ]] ||
      switch_journal_error 'initial release environment was not restored byte-for-byte' || return 1
  fi
  if [[ -n "$previous" ]]; then
    switch_journal_is_release_id "$previous" || return 1
    [[ "$(switch_journal_read_single_line "$PORTFOLIO_ROOT/previous-release" previous-release)" == "$previous" ]] ||
      switch_journal_error 'previous-release differs from the expected state' || return 1
  else
    [[ ! -e "$PORTFOLIO_ROOT/previous-release" && ! -L "$PORTFOLIO_ROOT/previous-release" ]] ||
      switch_journal_error 'previous-release should be absent' || return 1
  fi
}

switch_journal_verify_target() {
  local journal old_release new_release
  switch_journal_validate || return 1
  journal="$(switch_journal_path)" || return 1
  old_release="$(jq -r '.oldRelease // ""' "$journal")" || return 1
  new_release="$(jq -r '.newRelease' "$journal")" || return 1
  switch_journal_verify_state "$new_release" "$old_release"
}

switch_journal_restore_environment() {
  local journal backup release_env operation_id gid temporary expected_sha
  journal="$(switch_journal_path)" || return 1
  backup="$(switch_journal_backup_path)" || return 1
  release_env="$(switch_journal_release_env_path)" || return 1
  operation_id="$(jq -r '.operationId' "$journal")" || return 1
  gid="$(jq -r '.releaseEnvGid' "$journal")" || return 1
  expected_sha="$(jq -r '.envBackupSha256' "$journal")" || return 1
  temporary="$PORTFOLIO_ETC_ROOT/.release.env.switch-recover.$operation_id.tmp"
  if [[ -e "$temporary" || -L "$temporary" ]]; then
    [[ -f "$temporary" && ! -L "$temporary" && "$(stat -Lc '%u' -- "$temporary")" == 0 ]] ||
      switch_journal_error 'environment recovery temporary path is unsafe' || return 1
    rm -f -- "$temporary" || return 1
  fi
  cp -- "$backup" "$temporary" || return 1
  chown "0:$gid" "$temporary" || { rm -f -- "$temporary"; return 1; }
  chmod 0640 "$temporary" || { rm -f -- "$temporary"; return 1; }
  [[ "$(sha256sum -- "$temporary" | awk '{print $1}')" == "$expected_sha" ]] || {
    rm -f -- "$temporary"
    switch_journal_error 'environment recovery copy digest mismatch'
    return 1
  }
  sync -f "$temporary" || { rm -f -- "$temporary"; return 1; }
  mv -fT -- "$temporary" "$release_env" || { rm -f -- "$temporary"; return 1; }
  sync -f "$PORTFOLIO_ETC_ROOT"
}

switch_journal_restore_pointer() {
  local name="$1" target="$2" operation_id="$3" temporary
  temporary="$PORTFOLIO_ROOT/.$name.switch-recover.$operation_id.tmp"
  if [[ -e "$temporary" || -L "$temporary" ]]; then
    [[ -L "$temporary" && "$(readlink -- "$temporary")" == "$target" ]] ||
      switch_journal_error "$name recovery temporary path is unsafe" || return 1
    rm -f -- "$temporary" || return 1
  fi
  ln -s -- "$target" "$temporary" || return 1
  mv -fT -- "$temporary" "$PORTFOLIO_ROOT/$name" || {
    rm -f -- "$temporary"
    return 1
  }
  sync -f "$PORTFOLIO_ROOT"
}

switch_journal_remove_state_path() {
  local path="$1"
  if [[ -e "$path" || -L "$path" ]]; then
    [[ ! -d "$path" || -L "$path" ]] ||
      switch_journal_error "refusing to remove a directory at state path: $path" || return 1
    rm -f -- "$path" || return 1
    sync -f "$(dirname -- "$path")" || return 1
  fi
}

switch_journal_write_marker() {
  local name="$1" value="$2" operation_id="$3" gid="$4" temporary
  switch_journal_is_release_id "$value" || return 1
  temporary="$PORTFOLIO_ROOT/.$name.switch-recover.$operation_id.tmp"
  if [[ -e "$temporary" || -L "$temporary" ]]; then
    [[ -f "$temporary" && ! -L "$temporary" && "$(stat -Lc '%u' -- "$temporary")" == 0 ]] ||
      switch_journal_error "$name recovery temporary path is unsafe" || return 1
    rm -f -- "$temporary" || return 1
  fi
  (umask 077; set -C; printf '%s\n' "$value" >"$temporary") 2>/dev/null || return 1
  chown "0:$gid" "$temporary" || { rm -f -- "$temporary"; return 1; }
  chmod 0640 "$temporary" || { rm -f -- "$temporary"; return 1; }
  sync -f "$temporary" || { rm -f -- "$temporary"; return 1; }
  mv -fT -- "$temporary" "$PORTFOLIO_ROOT/$name" || {
    rm -f -- "$temporary"
    return 1
  }
  sync -f "$PORTFOLIO_ROOT"
}

switch_journal_wait_for_api() {
  local directory="$1" release_env="$2"
  local timeout="${PORTFOLIO_SWITCH_RECOVERY_TIMEOUT_SECONDS:-${PORTFOLIO_RECOVERY_READY_TIMEOUT_SECONDS:-${PORTFOLIO_ROLLBACK_RECOVERY_TIMEOUT_SECONDS:-${PORTFOLIO_ROLLBACK_READY_TIMEOUT_SECONDS:-${PORTFOLIO_API_READY_TIMEOUT_SECONDS:-180}}}}}"
  local poll="${PORTFOLIO_SWITCH_RECOVERY_POLL_SECONDS:-${PORTFOLIO_HEALTH_POLL_SECONDS:-2}}"
  local deadline container status
  [[ "$timeout" =~ ^[1-9][0-9]*$ && "$poll" =~ ^[0-9]+$ ]] ||
    switch_journal_error 'switch recovery timeout configuration is invalid' || return 1
  deadline=$((SECONDS + timeout))
  while ((SECONDS < deadline)); do
    container="$(docker compose --env-file "$release_env" \
      -f "$directory/ops/deploy/docker-compose.prod.yml" \
      ps -q portfolio-api 2>/dev/null || true)"
    if [[ -n "$container" ]]; then
      status="$(docker inspect --format \
        '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' \
        "$container" 2>/dev/null || true)"
      [[ "$status" == healthy || "$status" == running ]] && return 0
    fi
    sleep "$poll"
  done
  switch_journal_error 'recovered API did not become healthy before timeout'
}

switch_journal_reload_nginx() {
  local nginx_env nginx_bin nginx_prefix nginx_conf
  nginx_env="$(switch_journal_nginx_env_path)" || return 1
  [[ -f "$nginx_env" && ! -L "$nginx_env" ]] ||
    switch_journal_error 'nginx.env is missing or linked during recovery' || return 1
  nginx_bin="$(switch_journal_env_value "$nginx_env" NGINX_BIN)" || return 1
  nginx_prefix="$(switch_journal_env_value "$nginx_env" NGINX_PREFIX)" || return 1
  nginx_conf="$(switch_journal_env_value "$nginx_env" NGINX_CONF)" || return 1
  nginx_bin="$(realpath -e -- "$nginx_bin")" || return 1
  nginx_prefix="$(realpath -e -- "$nginx_prefix")/" || return 1
  nginx_conf="$(realpath -e -- "$nginx_conf")" || return 1
  [[ -x "$nginx_bin" && "$nginx_bin" == "$nginx_prefix"* &&
     "$nginx_conf" == "$nginx_prefix"* ]] ||
    switch_journal_error 'BaoTa Nginx paths are invalid during recovery' || return 1
  "$nginx_bin" -p "$nginx_prefix" -c "$nginx_conf" -t || return 1
  "$nginx_bin" -p "$nginx_prefix" -c "$nginx_conf" -s reload
}

switch_journal_smoke_restored_release() {
  local directory="$1" manifest candidate source digest='' nginx_env nginx_port smoke mode
  smoke="$directory/ops/deploy/scripts/smoke.sh"
  [[ -f "$smoke" && ! -L "$smoke" && "$(stat -Lc '%u' -- "$smoke")" == 0 ]] ||
    switch_journal_error 'restored release smoke entry point is unavailable' || return 1
  mode="$(stat -Lc '%a' -- "$smoke")" || return 1
  (( (8#$mode & 8#022) == 0 )) ||
    switch_journal_error 'restored release smoke entry point is writable outside root' || return 1
  bash "$smoke" \
    api-local --base-url http://127.0.0.1:18080 || return 1
  manifest="$directory/public-assets/.vite/manifest.json"
  [[ -f "$manifest" && ! -L "$manifest" ]] ||
    switch_journal_error 'restored release asset manifest is unavailable' || return 1
  candidate=''
  while IFS= read -r candidate; do
    [[ "$candidate" =~ ^assets/[A-Za-z0-9._-]+$ ]] || continue
    source="$directory/public-assets/$candidate"
    [[ -f "$source" && ! -L "$source" &&
       "$(realpath -e -- "$source")" == "$directory/public-assets/assets/"* ]] || continue
    digest="$(sha256sum -- "$source" | awk '{print $1}')" || return 1
    [[ "$digest" =~ ^[0-9a-f]{64}$ ]] && break
    candidate=''
  done < <(jq -er '
    [to_entries[] | .value |
      (.file? // empty), (.css[]? // empty), (.assets[]? // empty)] |
    map(select(type == "string")) | unique | sort[]
  ' "$manifest")
  [[ -n "$candidate" && "$digest" =~ ^[0-9a-f]{64}$ ]] ||
    switch_journal_error 'restored release has no smoke-testable immutable asset' || return 1
  nginx_env="$(switch_journal_nginx_env_path)" || return 1
  nginx_port="${NGINX_LOCAL_PORT:-$(switch_journal_env_value "$nginx_env" NGINX_LOCAL_PORT)}" ||
    return 1
  [[ "$nginx_port" =~ ^[1-9][0-9]{0,4}$ ]] ||
    switch_journal_error 'NGINX_LOCAL_PORT is invalid during restored release smoke' || return 1
  ((10#$nginx_port <= 65535)) ||
    switch_journal_error 'NGINX_LOCAL_PORT is invalid during restored release smoke' || return 1
  case "$nginx_port" in
    80|443|18080)
      switch_journal_error 'NGINX_LOCAL_PORT conflicts with a production listener'
      return 1
      ;;
  esac
  PORTFOLIO_SMOKE_ASSET_PATH="$candidate" \
  PORTFOLIO_SMOKE_ASSET_SHA256="$digest" \
    bash "$smoke" nginx-local \
      --resolve "yychainsaw.xyz:$nginx_port:127.0.0.1"
}

switch_journal_restore_old_state() {
  local journal backup release_env old_release old_previous new_release operation_id gid backup_sha
  local old_directory new_directory
  journal="$(switch_journal_path)" || return 1
  backup="$(switch_journal_backup_path)" || return 1
  release_env="$(switch_journal_release_env_path)" || return 1
  old_release="$(jq -r '.oldRelease // ""' "$journal")" || return 1
  old_previous="$(jq -r '.oldPrevious // ""' "$journal")" || return 1
  new_release="$(jq -r '.newRelease' "$journal")" || return 1
  operation_id="$(jq -r '.operationId' "$journal")" || return 1
  gid="$(jq -r '.releaseEnvGid' "$journal")" || return 1
  backup_sha="$(jq -r '.envBackupSha256' "$journal")" || return 1

  if [[ -z "$old_release" ]] && [[ -f "$release_env" && ! -L "$release_env" ]] &&
      [[ "$(switch_journal_env_value "$release_env" PORTFOLIO_RELEASE_ID 2>/dev/null || true)" == "$new_release" ]]; then
    new_directory="$PORTFOLIO_ROOT/releases/$new_release"
    [[ -d "$new_directory" && ! -L "$new_directory" ]] ||
      switch_journal_error 'new release directory is unavailable for initial recovery' || return 1
    docker compose --env-file "$release_env" \
      -f "$new_directory/ops/deploy/docker-compose.prod.yml" stop portfolio-api || return 1
    docker compose --env-file "$release_env" \
      -f "$new_directory/ops/deploy/docker-compose.prod.yml" rm -f portfolio-api || return 1
  fi

  switch_journal_restore_environment || return 1
  if [[ -n "$old_release" ]]; then
    old_directory="$PORTFOLIO_ROOT/releases/$old_release"
    [[ -d "$old_directory" && ! -L "$old_directory" &&
       "$(realpath -e -- "$old_directory")" == "$old_directory" &&
       -d "$old_directory/admin" && -d "$old_directory/ops" ]] ||
      switch_journal_error 'old release directory is unavailable for switch recovery' || return 1
    switch_journal_restore_pointer current-admin "$old_directory/admin" "$operation_id" || return 1
    switch_journal_restore_pointer current-ops "$old_directory/ops" "$operation_id" || return 1
    switch_journal_write_marker current-release "$old_release" "$operation_id" "$gid" || return 1
  else
    switch_journal_remove_state_path "$PORTFOLIO_ROOT/current-admin" || return 1
    switch_journal_remove_state_path "$PORTFOLIO_ROOT/current-ops" || return 1
    switch_journal_remove_state_path "$PORTFOLIO_ROOT/current-release" || return 1
  fi
  if [[ -n "$old_previous" ]]; then
    switch_journal_write_marker previous-release "$old_previous" "$operation_id" "$gid" || return 1
  else
    switch_journal_remove_state_path "$PORTFOLIO_ROOT/previous-release" || return 1
  fi

  if [[ -n "$old_release" ]]; then
    switch_journal_require_command docker || return 1
    docker compose --env-file "$release_env" \
      -f "$old_directory/ops/deploy/docker-compose.prod.yml" \
      up -d postgres portfolio-api || return 1
    switch_journal_wait_for_api "$old_directory" "$release_env" || return 1
    switch_journal_reload_nginx || return 1
    switch_journal_smoke_restored_release "$old_directory" || return 1
  fi
  switch_journal_verify_state "$old_release" "$old_previous" "$backup_sha"
}

switch_journal_pending_is_verified() {
  local journal
  journal="$(switch_journal_path)" || return 1
  [[ -e "$journal" || -L "$journal" ]] || return 1
  switch_journal_validate || return 1
  [[ "$(jq -r '.phase' "$journal")" == verified ]]
}

switch_journal_recover_pending() {
  local journal backup release_env phase operation_id current_sha backup_sha
  switch_journal_assert_lock || return 1
  journal="$(switch_journal_path)" || return 1
  backup="$(switch_journal_backup_path)" || return 1
  release_env="$(switch_journal_release_env_path)" || return 1
  if [[ ! -e "$journal" && ! -L "$journal" ]]; then
    if [[ -e "$backup" || -L "$backup" ]]; then
      switch_journal_validate_root_file "$backup" 600 'orphaned switch environment backup' || return 1
      [[ -f "$release_env" && ! -L "$release_env" ]] ||
        switch_journal_error 'release.env is unavailable beside an orphaned switch backup' || return 1
      current_sha="$(sha256sum -- "$release_env" | awk '{print $1}')" || return 1
      backup_sha="$(sha256sum -- "$backup" | awk '{print $1}')" || return 1
      [[ "$current_sha" == "$backup_sha" ]] ||
        switch_journal_error 'orphaned switch backup does not match release.env' || return 1
      rm -f -- "$backup" || return 1
      sync -f "$PORTFOLIO_ETC_ROOT" || return 1
    fi
    return 0
  fi
  switch_journal_validate || return 1
  operation_id="$(jq -r '.operationId' "$journal")" || return 1
  if [[ -n "${PORTFOLIO_SWITCH_TRANSACTION_ID:-}" &&
        "$PORTFOLIO_SWITCH_TRANSACTION_ID" == "$operation_id" ]]; then
    return 0
  fi
  phase="$(jq -r '.phase' "$journal")" || return 1
  if [[ "$phase" == verified ]]; then
    switch_journal_verify_target || return 1
    if [[ -e "$backup" || -L "$backup" ]]; then
      switch_journal_validate_root_file "$backup" 600 'switch environment backup' || return 1
      rm -f -- "$backup" || return 1
      sync -f "$PORTFOLIO_ETC_ROOT" || return 1
    fi
    rm -f -- "$journal" || return 1
    sync -f "$PORTFOLIO_ROOT" || return 1
    return 0
  fi
  switch_journal_restore_old_state || return 1
  # Restored state is already complete and verified.  Remove the journal first;
  # a crash before backup removal leaves a byte-identical orphan that the next
  # locked process can prove safe and clean up.
  rm -f -- "$journal" || return 1
  sync -f "$PORTFOLIO_ROOT" || return 1
  rm -f -- "$backup" || return 1
  sync -f "$PORTFOLIO_ETC_ROOT"
}

switch_journal_commit() {
  local journal backup operation_id
  switch_journal_assert_lock || return 1
  switch_journal_validate || return 1
  journal="$(switch_journal_path)" || return 1
  backup="$(switch_journal_backup_path)" || return 1
  operation_id="$(jq -r '.operationId' "$journal")" || return 1
  [[ "${PORTFOLIO_SWITCH_TRANSACTION_ID:-}" == "$operation_id" &&
     "$(jq -r '.phase' "$journal")" == verified ]] ||
    switch_journal_error 'only the verified active transaction can commit' || return 1
  switch_journal_verify_target || return 1
  # Once verified is durable the new state is the commit point.  Remove the
  # old environment backup first; recovery explicitly accepts that ordering.
  rm -f -- "$backup" || return 1
  sync -f "$PORTFOLIO_ETC_ROOT" || return 1
  rm -f -- "$journal" || return 1
  sync -f "$PORTFOLIO_ROOT" || return 1
  unset PORTFOLIO_SWITCH_TRANSACTION_ID
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  printf '%s\n' 'switch-journal.sh is a source-only deployment helper' >&2
  exit 64
fi
