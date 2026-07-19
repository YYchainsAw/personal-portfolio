#!/usr/bin/env bash
set -euo pipefail
set +x
umask 077
export LC_ALL=C

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
SOURCE_INSTALLER="$REPOSITORY_ROOT/deploy/backup/install-cos-prune-runtime.sh"
SOURCE_LOCK="$REPOSITORY_ROOT/deploy/backup/requirements-cos-prune.txt"
SOURCE_GUARD="$REPOSITORY_ROOT/deploy/backup/cos-prune-guard.py"
TEST_SOURCE_ROOT='/tmp/portfolio-cos-prune-test-source'
TEST_SOURCE_DEPLOY_DIRECTORY="$TEST_SOURCE_ROOT/deploy"
TEST_SOURCE_DIRECTORY="$TEST_SOURCE_DEPLOY_DIRECTORY/backup"
INSTALLER="$TEST_SOURCE_DIRECTORY/install-cos-prune-runtime.sh"
LOCK="$TEST_SOURCE_DIRECTORY/requirements-cos-prune.txt"
GUARD="$TEST_SOURCE_DIRECTORY/cos-prune-guard.py"
RUNTIME='/opt/portfolio/cos-prune-venv'
OPERATION_LOCK='/var/backups/portfolio/operation.lock'
PORTFOLIO_ROOT_METADATA=''
RUN_LOCK_METADATA=''

fail() {
  printf 'COS prune runtime contract failed: %s\n' "$1" >&2
  exit 1
}

run_installer() {
  env PORTFOLIO_COS_RUNTIME_TEST_MODE=1 \
    PORTFOLIO_COS_RUNTIME_SKIP_APT="${1:-0}" \
    PORTFOLIO_COS_RUNTIME_TEST_FAILPOINT="${2:-}" \
    bash "$INSTALLER"
}

stage_private_test_source() {
  [[ ! -e "$TEST_SOURCE_ROOT" && ! -L "$TEST_SOURCE_ROOT" ]] ||
    fail 'fixed private runtime test source already exists'
  [[ -d /tmp && ! -L /tmp && "$(realpath -e -- /tmp)" == /tmp &&
     "$(stat -Lc '%u:%g:%a' -- /tmp)" == 0:0:1777 ]] ||
    fail 'runtime contract requires the standard root-owned /tmp mode 1777'
  mkdir -m 0700 -- "$TEST_SOURCE_ROOT"
  mkdir -m 0700 -- "$TEST_SOURCE_DEPLOY_DIRECTORY"
  mkdir -m 0700 -- "$TEST_SOURCE_DIRECTORY"
  install -o root -g root -m 0700 -- "$SOURCE_INSTALLER" "$INSTALLER"
  install -o root -g root -m 0600 -- "$SOURCE_LOCK" "$LOCK"
  install -o root -g root -m 0600 -- "$SOURCE_GUARD" "$GUARD"
}

assert_private_test_source() {
  [[ "$(stat -Lc '%u:%g:%a' -- "$TEST_SOURCE_ROOT")" == 0:0:700 &&
     "$(stat -Lc '%u:%g:%a' -- "$TEST_SOURCE_DEPLOY_DIRECTORY")" == 0:0:700 &&
     "$(stat -Lc '%u:%g:%a' -- "$TEST_SOURCE_DIRECTORY")" == 0:0:700 &&
     "$(stat -Lc '%u:%g:%h:%a' -- "$INSTALLER")" == 0:0:1:700 &&
     "$(stat -Lc '%u:%g:%h:%a' -- "$LOCK")" == 0:0:1:600 &&
     "$(stat -Lc '%u:%g:%h:%a' -- "$GUARD")" == 0:0:1:600 ]] ||
    fail 'fixed runtime test source metadata changed'
  [[ "$(find "$TEST_SOURCE_ROOT" -mindepth 1 -maxdepth 1 -printf '.\n' | wc -l)" -eq 1 &&
     "$(find "$TEST_SOURCE_DEPLOY_DIRECTORY" -mindepth 1 -maxdepth 1 -printf '.\n' | wc -l)" -eq 1 &&
     "$(find "$TEST_SOURCE_DIRECTORY" -mindepth 1 -maxdepth 1 -printf '.\n' | wc -l)" -eq 3 ]] ||
    fail 'fixed runtime test source contains unexpected entries'
  for path in "$TEST_SOURCE_ROOT" "$TEST_SOURCE_DEPLOY_DIRECTORY" "$TEST_SOURCE_DIRECTORY"; do
    [[ -d "$path" && ! -L "$path" && "$(realpath -e -- "$path")" == "$path" ]] ||
      fail 'fixed runtime test source directory is non-canonical or symlinked'
    [[ "$(stat -Lc '%h' -- "$path")" =~ ^[1-9][0-9]*$ ]] ||
      fail 'fixed runtime test source directory link count is unsafe'
  done
  for path in "$INSTALLER" "$LOCK" "$GUARD"; do
    [[ -f "$path" && ! -L "$path" && "$(realpath -e -- "$path")" == "$path" ]] ||
      fail 'fixed runtime test source file is non-canonical or symlinked'
  done
}

assert_host_roots_preserved() {
  [[ "$(stat -Lc '%u:%g:%a' -- /opt/portfolio)" == "$PORTFOLIO_ROOT_METADATA" ]] ||
    fail 'runtime installer changed /opt/portfolio owner, group, or mode'
  [[ "$(stat -Lc '%u:%g:%a' -- /run/lock)" == "$RUN_LOCK_METADATA" ]] ||
    fail 'runtime installer changed the standard /run/lock owner, group, or mode'
}

assert_complete_runtime() {
  local lock_sha output
  lock_sha="$(sha256sum "$LOCK" | awk '{print $1}')"
  [[ -d "$RUNTIME" && ! -L "$RUNTIME" &&
     "$(stat -Lc '%u:%a' -- "$RUNTIME")" == 0:700 ]] ||
    fail 'fixed isolated runtime directory is unsafe'
  jq -e --arg lockSha "$lock_sha" '
    keys == ["dependencyVersions","lockSha256","pythonAbi","schemaVersion"] and
    .schemaVersion == 2 and .lockSha256 == $lockSha and .pythonAbi == "3.10" and
    .dependencyVersions == {
      "certifi":"2026.6.17",
      "charset-normalizer":"3.4.9",
      "cos-python-sdk-v5":"1.9.44",
      "crcmod":"1.7",
      "idna":"3.18",
      "pycryptodome":"3.23.0",
      "requests":"2.34.2",
      "six":"1.17.0",
      "tencentcloud-sdk-python-common":"3.1.135",
      "tencentcloud-sdk-python-sts":"3.0.1459",
      "urllib3":"2.7.0",
      "xmltodict":"1.0.4"
    }
  ' "$RUNTIME/.portfolio-runtime.json" >/dev/null ||
    fail 'installed runtime marker is not bound to the exact lock and ABI'
  output="$($RUNTIME/bin/python3 -B "$GUARD" runtime-check)" ||
    fail 'real fixed-path Python runtime failed the guard gate'
  [[ "$output" == SAFE ]] || fail 'guard runtime gate returned an invalid state'
  "$RUNTIME/bin/python3" - <<'PY'
import importlib.metadata
import re
import sys

expected = {
    "certifi": "2026.6.17",
    "charset-normalizer": "3.4.9",
    "cos-python-sdk-v5": "1.9.44",
    "crcmod": "1.7",
    "idna": "3.18",
    "pycryptodome": "3.23.0",
    "requests": "2.34.2",
    "six": "1.17.0",
    "tencentcloud-sdk-python-common": "3.1.135",
    "tencentcloud-sdk-python-sts": "3.0.1459",
    "urllib3": "2.7.0",
    "xmltodict": "1.0.4",
}
observed = {
    re.sub(r"[-_.]+", "-", distribution.metadata["Name"]).lower(): distribution.version
    for distribution in importlib.metadata.distributions()
}
assert sys.version_info[:2] == (3, 10)
assert sys.prefix == "/opt/portfolio/cos-prune-venv"
assert observed == expected
PY
  assert_host_roots_preserved
  assert_private_test_source
}

expect_runtime_gate_failure() {
  local label="$1" log="/tmp/cos-prune-runtime-$1.log"
  if "$RUNTIME/bin/python3" -B "$GUARD" runtime-check >"$log" 2>&1; then
    fail "$label tampering bypassed the runtime gate"
  fi
  grep -F 'portfolio COS prune guard failed:' "$log" >/dev/null ||
    fail "$label tampering returned an unexpected diagnostic"
}

test_runtime_tamper_gates() {
  local metadata marker_backup metadata_backup
  metadata="$($RUNTIME/bin/python3 - <<'PY'
import importlib.metadata
print(importlib.metadata.distribution("urllib3")._path / "METADATA")
PY
)"
  metadata_backup="/tmp/cos-prune-urllib3-metadata.backup"
  marker_backup="/tmp/cos-prune-runtime-marker.backup"
  cp -- "$metadata" "$metadata_backup"
  sed -i 's/^Version: 2\.7\.0$/Version: 2.7.0-tampered/' "$metadata"
  grep -Fx 'Version: 2.7.0-tampered' "$metadata" >/dev/null ||
    fail 'transitive dependency tamper fixture was not applied'
  expect_runtime_gate_failure transitive-dependency
  cp -- "$metadata_backup" "$metadata"
  rm -f -- "$metadata_backup"
  assert_complete_runtime

  cp -- "$RUNTIME/.portfolio-runtime.json" "$marker_backup"
  sed -i 's/"schemaVersion":2/"schemaVersion":3/' "$RUNTIME/.portfolio-runtime.json"
  expect_runtime_gate_failure marker
  cp -- "$marker_backup" "$RUNTIME/.portfolio-runtime.json"
  rm -f -- "$marker_backup"
  assert_complete_runtime
}

expect_installer_rejection() {
  local label="$1"
  shift
  local log="/tmp/cos-prune-runtime-reject-$label.log" before_identity status
  before_identity="$(stat -Lc '%d:%i' -- "$RUNTIME")"
  set +e
  "$@" >"$log" 2>&1
  status=$?
  set -e
  [[ "$status" -ne 0 ]] || fail "$label unexpectedly enabled a runtime test control"
  grep -F 'portfolio COS prune runtime install failed:' "$log" >/dev/null ||
    fail "$label returned an unexpected runtime installer diagnostic"
  [[ "$(stat -Lc '%d:%i' -- "$RUNTIME")" == "$before_identity" &&
     ! -e "$RUNTIME.previous" && ! -L "$RUNTIME.previous" &&
     "$(find /opt/portfolio -maxdepth 1 -name '.cos-prune-venv.new.*' -print -quit)" == '' ]] ||
    fail "$label mutated the installed runtime before failing"
}

test_private_test_source_boundary() {
  local copy_root='/tmp/portfolio-cos-prune-test-source-copy'
  local copy_backup="$copy_root/deploy/backup"
  local saved_metadata

  expect_installer_rejection outside-fixed-source \
    env PORTFOLIO_COS_RUNTIME_TEST_MODE=1 PORTFOLIO_COS_RUNTIME_SKIP_APT=1 \
      bash "$SOURCE_INSTALLER"
  expect_installer_rejection invalid-test-flag \
    env PORTFOLIO_COS_RUNTIME_TEST_MODE=0 bash "$INSTALLER"
  expect_installer_rejection skip-apt-without-test-mode \
    env -u PORTFOLIO_COS_RUNTIME_TEST_MODE \
      PORTFOLIO_COS_RUNTIME_SKIP_APT=1 bash "$INSTALLER"
  expect_installer_rejection failpoint-without-test-mode \
    env -u PORTFOLIO_COS_RUNTIME_TEST_MODE \
      PORTFOLIO_COS_RUNTIME_TEST_FAILPOINT=after-current-to-previous-kill bash "$INSTALLER"

  mkdir -m 0700 -- "$copy_root"
  mkdir -m 0700 -- "$copy_root/deploy"
  mkdir -m 0700 -- "$copy_backup"
  install -o root -g root -m 0700 -- "$INSTALLER" "$copy_backup/install-cos-prune-runtime.sh"
  install -o root -g root -m 0600 -- "$LOCK" "$copy_backup/requirements-cos-prune.txt"
  install -o root -g root -m 0600 -- "$GUARD" "$copy_backup/cos-prune-guard.py"
  expect_installer_rejection copied-private-source \
    env PORTFOLIO_COS_RUNTIME_TEST_MODE=1 PORTFOLIO_COS_RUNTIME_SKIP_APT=1 \
      bash "$copy_backup/install-cos-prune-runtime.sh"
  rm -rf -- "$copy_root"

  saved_metadata="$(stat -Lc '%u:%g:%a' -- /tmp)"
  chmod 0777 /tmp
  expect_installer_rejection tmp-without-sticky \
    env PORTFOLIO_COS_RUNTIME_TEST_MODE=1 PORTFOLIO_COS_RUNTIME_SKIP_APT=1 bash "$INSTALLER"
  [[ "$(stat -Lc '%u:%g:%a' -- /tmp)" == 0:0:777 ]] ||
    fail 'installer changed unsafe /tmp metadata'
  chmod "${saved_metadata##*:}" /tmp

  chmod 0750 "$TEST_SOURCE_ROOT"
  expect_installer_rejection test-root-mode \
    env PORTFOLIO_COS_RUNTIME_TEST_MODE=1 PORTFOLIO_COS_RUNTIME_SKIP_APT=1 bash "$INSTALLER"
  [[ "$(stat -Lc '%u:%g:%a' -- "$TEST_SOURCE_ROOT")" == 0:0:750 ]] ||
    fail 'installer repaired unsafe test root metadata'
  chmod 0700 "$TEST_SOURCE_ROOT"
  chown 0:1234 "$TEST_SOURCE_ROOT"
  expect_installer_rejection test-root-group \
    env PORTFOLIO_COS_RUNTIME_TEST_MODE=1 PORTFOLIO_COS_RUNTIME_SKIP_APT=1 bash "$INSTALLER"
  [[ "$(stat -Lc '%u:%g:%a' -- "$TEST_SOURCE_ROOT")" == 0:1234:700 ]] ||
    fail 'installer repaired unsafe test root group'
  chown 0:0 "$TEST_SOURCE_ROOT"

  chmod 0755 "$TEST_SOURCE_DEPLOY_DIRECTORY"
  expect_installer_rejection deploy-ancestor-mode \
    env PORTFOLIO_COS_RUNTIME_TEST_MODE=1 PORTFOLIO_COS_RUNTIME_SKIP_APT=1 bash "$INSTALLER"
  chmod 0700 "$TEST_SOURCE_DEPLOY_DIRECTORY"
  chmod 0770 "$TEST_SOURCE_DIRECTORY"
  expect_installer_rejection backup-directory-mode \
    env PORTFOLIO_COS_RUNTIME_TEST_MODE=1 PORTFOLIO_COS_RUNTIME_SKIP_APT=1 bash "$INSTALLER"
  chmod 0700 "$TEST_SOURCE_DIRECTORY"

  mkdir -m 0700 -- "$TEST_SOURCE_ROOT/unexpected-directory"
  expect_installer_rejection test-root-link-count \
    env PORTFOLIO_COS_RUNTIME_TEST_MODE=1 PORTFOLIO_COS_RUNTIME_SKIP_APT=1 bash "$INSTALLER"
  rmdir -- "$TEST_SOURCE_ROOT/unexpected-directory"
  mkdir -m 0700 -- "$TEST_SOURCE_DEPLOY_DIRECTORY/unexpected-directory"
  expect_installer_rejection deploy-ancestor-link-count \
    env PORTFOLIO_COS_RUNTIME_TEST_MODE=1 PORTFOLIO_COS_RUNTIME_SKIP_APT=1 bash "$INSTALLER"
  rmdir -- "$TEST_SOURCE_DEPLOY_DIRECTORY/unexpected-directory"
  mkdir -m 0700 -- "$TEST_SOURCE_DIRECTORY/unexpected-directory"
  expect_installer_rejection backup-directory-link-count \
    env PORTFOLIO_COS_RUNTIME_TEST_MODE=1 PORTFOLIO_COS_RUNTIME_SKIP_APT=1 bash "$INSTALLER"
  rmdir -- "$TEST_SOURCE_DIRECTORY/unexpected-directory"

  mv -- "$TEST_SOURCE_ROOT" "$TEST_SOURCE_ROOT.real"
  ln -s -- "$TEST_SOURCE_ROOT.real" "$TEST_SOURCE_ROOT"
  expect_installer_rejection symlinked-test-root \
    env PORTFOLIO_COS_RUNTIME_TEST_MODE=1 PORTFOLIO_COS_RUNTIME_SKIP_APT=1 bash "$INSTALLER"
  rm -- "$TEST_SOURCE_ROOT"
  mv -- "$TEST_SOURCE_ROOT.real" "$TEST_SOURCE_ROOT"

  mv -- "$INSTALLER" "$INSTALLER.real"
  ln -s -- "$INSTALLER.real" "$INSTALLER"
  expect_installer_rejection symlinked-installer \
    env PORTFOLIO_COS_RUNTIME_TEST_MODE=1 PORTFOLIO_COS_RUNTIME_SKIP_APT=1 bash "$INSTALLER"
  rm -- "$INSTALLER"
  mv -- "$INSTALLER.real" "$INSTALLER"
  ln -- "$INSTALLER" /tmp/cos-prune-runtime-installer-hardlink
  expect_installer_rejection hardlinked-installer \
    env PORTFOLIO_COS_RUNTIME_TEST_MODE=1 PORTFOLIO_COS_RUNTIME_SKIP_APT=1 bash "$INSTALLER"
  rm -- /tmp/cos-prune-runtime-installer-hardlink

  mv -- "$LOCK" "$LOCK.real"
  ln -s -- "$LOCK.real" "$LOCK"
  expect_installer_rejection symlinked-lock \
    env PORTFOLIO_COS_RUNTIME_TEST_MODE=1 PORTFOLIO_COS_RUNTIME_SKIP_APT=1 bash "$INSTALLER"
  rm -- "$LOCK"
  mv -- "$LOCK.real" "$LOCK"
  ln -- "$LOCK" /tmp/cos-prune-runtime-lock-hardlink
  expect_installer_rejection hardlinked-lock \
    env PORTFOLIO_COS_RUNTIME_TEST_MODE=1 PORTFOLIO_COS_RUNTIME_SKIP_APT=1 bash "$INSTALLER"
  rm -- /tmp/cos-prune-runtime-lock-hardlink

  mv -- "$GUARD" "$GUARD.real"
  ln -s -- "$GUARD.real" "$GUARD"
  expect_installer_rejection symlinked-guard \
    env PORTFOLIO_COS_RUNTIME_TEST_MODE=1 PORTFOLIO_COS_RUNTIME_SKIP_APT=1 bash "$INSTALLER"
  rm -- "$GUARD"
  mv -- "$GUARD.real" "$GUARD"
  ln -- "$GUARD" /tmp/cos-prune-runtime-guard-hardlink
  expect_installer_rejection hardlinked-guard \
    env PORTFOLIO_COS_RUNTIME_TEST_MODE=1 PORTFOLIO_COS_RUNTIME_SKIP_APT=1 bash "$INSTALLER"
  rm -- /tmp/cos-prune-runtime-guard-hardlink

  chmod 0720 "$INSTALLER"
  expect_installer_rejection writable-installer \
    env PORTFOLIO_COS_RUNTIME_TEST_MODE=1 PORTFOLIO_COS_RUNTIME_SKIP_APT=1 bash "$INSTALLER"
  chmod 0700 "$INSTALLER"
  chmod 0620 "$LOCK"
  expect_installer_rejection writable-lock \
    env PORTFOLIO_COS_RUNTIME_TEST_MODE=1 PORTFOLIO_COS_RUNTIME_SKIP_APT=1 bash "$INSTALLER"
  chmod 0600 "$LOCK"
  chmod 0620 "$GUARD"
  expect_installer_rejection writable-guard \
    env PORTFOLIO_COS_RUNTIME_TEST_MODE=1 PORTFOLIO_COS_RUNTIME_SKIP_APT=1 bash "$INSTALLER"
  chmod 0600 "$GUARD"

  chown 1234:0 "$INSTALLER"
  expect_installer_rejection wrong-owner-installer \
    env PORTFOLIO_COS_RUNTIME_TEST_MODE=1 PORTFOLIO_COS_RUNTIME_SKIP_APT=1 bash "$INSTALLER"
  chown 0:0 "$INSTALLER"
  chown 1234:0 "$LOCK"
  expect_installer_rejection wrong-owner-lock \
    env PORTFOLIO_COS_RUNTIME_TEST_MODE=1 PORTFOLIO_COS_RUNTIME_SKIP_APT=1 bash "$INSTALLER"
  chown 0:0 "$LOCK"
  chown 1234:0 "$GUARD"
  expect_installer_rejection wrong-owner-guard \
    env PORTFOLIO_COS_RUNTIME_TEST_MODE=1 PORTFOLIO_COS_RUNTIME_SKIP_APT=1 bash "$INSTALLER"
  chown 0:0 "$GUARD"

  assert_complete_runtime
}

test_run_lock_parent_modes() {
  local saved_metadata="$RUN_LOCK_METADATA" mode
  for mode in 1777 0755 0775; do
    chmod "$mode" /run/lock
    RUN_LOCK_METADATA="$(stat -Lc '%u:%g:%a' -- /run/lock)"
    run_installer 1
    assert_complete_runtime
  done
  chmod 0777 /run/lock
  expect_installer_rejection run-lock-world-writable-without-sticky \
    env PORTFOLIO_COS_RUNTIME_TEST_MODE=1 PORTFOLIO_COS_RUNTIME_SKIP_APT=1 bash "$INSTALLER"
  [[ "$(stat -Lc '%u:%g:%a' -- /run/lock)" == 0:0:777 ]] ||
    fail 'installer changed unsafe /run/lock metadata'
  chmod "${saved_metadata##*:}" /run/lock
  RUN_LOCK_METADATA="$saved_metadata"
  assert_complete_runtime
}

test_shared_operation_lock_serialization() {
  local release_fifo='/tmp/cos-prune-runtime-release.fifo'
  local acquired='/tmp/cos-prune-runtime-holder.acquired'
  local install_log='/tmp/cos-prune-runtime-concurrent-install.log'
  local before_identity holder_pid installer_pid status found_staging=false
  rm -f -- "$release_fifo" "$acquired" "$install_log"
  mkfifo -m 0600 -- "$release_fifo"
  (
    exec 7<>"$OPERATION_LOCK"
    flock -x 7
    : >"$acquired"
    IFS= read -r _ <"$release_fifo"
  ) &
  holder_pid=$!
  for _ in $(seq 1 100); do
    [[ -e "$acquired" ]] && break
    sleep 0.05
  done
  [[ -e "$acquired" ]] || fail 'operation-lock holder did not start'
  before_identity="$(stat -Lc '%d:%i' -- "$RUNTIME")"
  run_installer 1 >"$install_log" 2>&1 &
  installer_pid=$!
  sleep 1
  kill -0 "$installer_pid" 2>/dev/null || {
    sed -n '1,120p' "$install_log" >&2
    fail 'runtime installer did not wait for the active backup/prune operation'
  }
  [[ "$(stat -Lc '%d:%i' -- "$RUNTIME")" == "$before_identity" &&
     "$(find /opt/portfolio -maxdepth 1 -name '.cos-prune-venv.new.*' -print -quit)" == '' ]] ||
    fail 'runtime installer mutated the venv before acquiring the shared operation lock'

  printf 'release\n' >"$release_fifo"
  wait "$holder_pid"
  for _ in $(seq 1 200); do
    if [[ -n "$(find /opt/portfolio -maxdepth 1 -name '.cos-prune-venv.new.*' -print -quit)" ]]; then
      found_staging=true
      break
    fi
    kill -0 "$installer_pid" 2>/dev/null || break
    sleep 0.05
  done
  [[ "$found_staging" == true ]] || {
    sed -n '1,120p' "$install_log" >&2
    fail 'concurrent installer did not enter its lock-held replacement phase'
  }
  if flock -n "$OPERATION_LOCK" -c true; then
    fail 'a new backup/prune operation acquired the lock during runtime replacement'
  fi
  set +e
  wait "$installer_pid"
  status=$?
  set -e
  [[ "$status" -eq 0 ]] || {
    sed -n '1,160p' "$install_log" >&2
    fail 'serialized runtime replacement failed'
  }
  rm -f -- "$release_fifo" "$acquired"
  assert_complete_runtime
}

expect_interruption() {
  local failpoint="$1" expected_status="$2" status
  set +e
  run_installer 1 "$failpoint" >/tmp/cos-prune-runtime-interrupt.log 2>&1
  status=$?
  set -e
  [[ "$status" -eq "$expected_status" ]] || {
    sed -n '1,120p' /tmp/cos-prune-runtime-interrupt.log >&2
    fail "$failpoint returned $status instead of $expected_status"
  }
}

main() {
  [[ "$(id -u)" -eq 0 ]] || fail 'runtime contract must run as root in Ubuntu 22.04'
  for source_file in "$SOURCE_INSTALLER" "$SOURCE_LOCK" "$SOURCE_GUARD"; do
    [[ -f "$source_file" && ! -L "$source_file" ]] ||
      fail 'runtime contract source file is missing or symlinked'
  done
  stage_private_test_source
  assert_private_test_source
  if [[ -e /opt/portfolio || -L /opt/portfolio ]]; then
    rm -rf -- /opt/portfolio
  fi
  mkdir -m 0711 -- /opt/portfolio
  chown 0:1234 /opt/portfolio
  PORTFOLIO_ROOT_METADATA="$(stat -Lc '%u:%g:%a' -- /opt/portfolio)"
  RUN_LOCK_METADATA="$(stat -Lc '%u:%g:%a' -- /run/lock)"
  rm -rf -- "$RUNTIME" "$RUNTIME.previous" /opt/portfolio/.cos-prune-venv.new.*
  run_installer 0
  assert_complete_runtime
  test_runtime_tamper_gates
  test_private_test_source_boundary
  test_run_lock_parent_modes
  test_shared_operation_lock_serialization

  expect_interruption after-current-to-previous-kill 137
  [[ ! -e "$RUNTIME" && -d "$RUNTIME.previous" ]] ||
    fail 'current-to-previous SIGKILL window was not reproduced'
  [[ "$(find /opt/portfolio -maxdepth 1 -type d -name '.cos-prune-venv.new.*' | wc -l)" -eq 1 ]] ||
    fail 'SIGKILL did not preserve the interrupted staging evidence'
  run_installer 1
  assert_complete_runtime
  [[ ! -e "$RUNTIME.previous" &&
     "$(find /opt/portfolio -maxdepth 1 -name '.cos-prune-venv.new.*' -print -quit)" == '' ]] ||
    fail 'current-to-previous recovery left stale replacement state'

  expect_interruption after-staging-to-current-kill 137
  [[ -d "$RUNTIME" && -d "$RUNTIME.previous" ]] ||
    fail 'staging-to-current SIGKILL window was not reproduced'
  assert_complete_runtime
  run_installer 1
  assert_complete_runtime
  [[ ! -e "$RUNTIME.previous" ]] ||
    fail 'valid replacement plus previous runtime was not committed safely'

  expect_interruption after-current-to-previous-term 143
  [[ ! -e "$RUNTIME" && -d "$RUNTIME.previous" ]] ||
    fail 'TERM replacement window did not preserve the recoverable previous runtime'
  [[ "$(find /opt/portfolio -maxdepth 1 -name '.cos-prune-venv.new.*' -print -quit)" == '' ]] ||
    fail 'TERM handler returned or failed to clean the staging directory'
  run_installer 1
  assert_complete_runtime
  printf '%s\n' 'COS prune runtime contract passed'
}

main "$@"
