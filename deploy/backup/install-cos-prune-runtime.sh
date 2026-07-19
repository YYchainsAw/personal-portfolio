#!/usr/bin/env bash
set -euo pipefail
set +x
umask 077
export LC_ALL=C

SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
LOCK_FILE="$SCRIPT_DIRECTORY/requirements-cos-prune.txt"
GUARD="$SCRIPT_DIRECTORY/cos-prune-guard.py"
TEST_SOURCE_ROOT='/tmp/portfolio-cos-prune-test-source'
TEST_SOURCE_DEPLOY_DIRECTORY="$TEST_SOURCE_ROOT/deploy"
TEST_SOURCE_DIRECTORY="$TEST_SOURCE_DEPLOY_DIRECTORY/backup"
TEST_INSTALLER="$TEST_SOURCE_DIRECTORY/install-cos-prune-runtime.sh"
RUNTIME_ROOT='/opt/portfolio/cos-prune-venv'
INSTALL_LOCK_DIRECTORY='/run/lock/portfolio'
INSTALL_LOCK='/run/lock/portfolio/cos-prune-runtime.lock'
OPERATION_LOCK="${BACKUP_OPERATION_LOCK:-/var/backups/portfolio/operation.lock}"
STAGING=''
PREVIOUS=''
TEST_MODE=false
TEST_SKIP_APT=0
TEST_FAILPOINT=''

fail() {
  printf 'portfolio COS prune runtime install failed: %s\n' "$1" >&2
  exit 1
}

cleanup() {
  if [[ -n "$STAGING" && "$STAGING" == /opt/portfolio/.cos-prune-venv.new.* &&
        ! -L "$STAGING" && -d "$STAGING" ]]; then
    rm -rf -- "$STAGING"
  fi
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

test_failpoint() {
  local name="$1"
  [[ "$TEST_MODE" == true ]] || return 0
  case "$TEST_FAILPOINT" in
    "$name-kill") kill -KILL "$$" ;;
    "$name-term") kill -TERM "$$" ;;
    *) ;;
  esac
}

require_test_source_directory() {
  local path="$1" expected_mode="$2" expected_entries="$3" links entry_count
  [[ -d "$path" && ! -L "$path" && "$(realpath -e -- "$path")" == "$path" ]] ||
    fail 'runtime installer test source directory is non-canonical or symlinked'
  [[ "$(stat -Lc '%u:%g:%a' -- "$path")" == "0:0:$expected_mode" ]] ||
    fail 'runtime installer test source directory metadata is unsafe'
  links="$(stat -Lc '%h' -- "$path")" ||
    fail 'runtime installer test source directory link count cannot be read'
  [[ "$links" =~ ^[1-9][0-9]*$ ]] ||
    fail 'runtime installer test source directory link count is unsafe'
  entry_count="$(find "$path" -mindepth 1 -maxdepth 1 -printf '.\n' | wc -l)" ||
    fail 'runtime installer test source directory entries cannot be counted'
  [[ "$entry_count" -eq "$expected_entries" ]] ||
    fail 'runtime installer test source directory contains unexpected entries'
}

require_test_source_file() {
  local path="$1" expected_mode="$2"
  [[ -f "$path" && ! -L "$path" && "$(realpath -e -- "$path")" == "$path" &&
     "$(stat -Lc '%u:%g:%h:%a' -- "$path")" == "0:0:1:$expected_mode" ]] ||
    fail 'runtime installer test source file metadata is unsafe'
}

validate_test_mode_source() {
  local tmp_links
  [[ "$SCRIPT_DIRECTORY" == "$TEST_SOURCE_DIRECTORY" &&
     ! -L "${BASH_SOURCE[0]}" &&
     "$(realpath -e -- "${BASH_SOURCE[0]}")" == "$TEST_INSTALLER" ]] ||
    fail 'runtime installer test mode is restricted to the fixed private test source'
  [[ -d /tmp && ! -L /tmp && "$(realpath -e -- /tmp)" == /tmp &&
     "$(stat -Lc '%u:%g:%a' -- /tmp)" == 0:0:1777 ]] ||
    fail 'runtime installer test source /tmp ancestor is unsafe'
  tmp_links="$(stat -Lc '%h' -- /tmp)" ||
    fail 'runtime installer test source /tmp link count cannot be read'
  [[ "$tmp_links" =~ ^[1-9][0-9]*$ ]] ||
    fail 'runtime installer test source /tmp link count is unsafe'
  require_test_source_directory "$TEST_SOURCE_ROOT" 700 1
  require_test_source_directory "$TEST_SOURCE_DEPLOY_DIRECTORY" 700 1
  require_test_source_directory "$TEST_SOURCE_DIRECTORY" 700 3
  require_test_source_file "$TEST_INSTALLER" 700
  require_test_source_file "$LOCK_FILE" 600
  require_test_source_file "$GUARD" 600
}

validate_source_file() {
  local path="$1" label="$2" mode
  [[ -f "$path" && ! -L "$path" &&
     "$(stat -Lc '%u:%g:%h' -- "$path")" == 0:0:1 ]] ||
    fail "$label is unsafe"
  mode="$(stat -Lc '%a' -- "$path")" || fail "$label mode cannot be read"
  (( (8#$mode & 8#022) == 0 )) || fail "$label is group/world writable"
}

[[ $# -eq 0 ]] || fail 'this installer accepts no arguments'
[[ "$(id -u)" -eq 0 ]] || fail 'installer must run as root'
[[ "$(uname -m)" == x86_64 ]] || fail 'the reviewed runtime lock is for x86_64 only'
source /etc/os-release
[[ "${ID:-}" == ubuntu && "${VERSION_ID:-}" == 22.04 ]] ||
  fail 'the reviewed runtime installer requires Ubuntu 22.04'
for command in apt-get awk chmod chown dirname find flock id install mkdir mktemp mv \
  realpath rm sha256sum stat sync uname wc; do
  command -v "$command" >/dev/null 2>&1 || fail "$command is required"
done
if [[ -n "${PORTFOLIO_COS_RUNTIME_TEST_MODE+x}" ]]; then
  [[ "$PORTFOLIO_COS_RUNTIME_TEST_MODE" == 1 ]] ||
    fail 'runtime installer test mode flag is invalid'
  TEST_MODE=true
  TEST_SKIP_APT="${PORTFOLIO_COS_RUNTIME_SKIP_APT:-0}"
  TEST_FAILPOINT="${PORTFOLIO_COS_RUNTIME_TEST_FAILPOINT:-}"
  [[ "$TEST_SKIP_APT" == 0 || "$TEST_SKIP_APT" == 1 ]] ||
    fail 'runtime installer test apt flag is invalid'
  case "$TEST_FAILPOINT" in
    ''|after-current-to-previous-kill|after-current-to-previous-term|after-staging-to-current-kill|after-staging-to-current-term) ;;
    *) fail 'runtime installer test failpoint is invalid' ;;
  esac
  validate_test_mode_source
elif [[ -n "${PORTFOLIO_COS_RUNTIME_SKIP_APT+x}" ||
        -n "${PORTFOLIO_COS_RUNTIME_TEST_FAILPOINT+x}" ]]; then
  fail 'runtime installer test-only controls require validated test mode'
fi
readonly TEST_MODE TEST_SKIP_APT TEST_FAILPOINT
validate_source_file "$LOCK_FILE" 'hash-locked runtime requirements'
validate_source_file "$GUARD" 'COS prune guard implementation'

[[ -d /opt/portfolio && ! -L /opt/portfolio &&
   "$(realpath -e -- /opt/portfolio)" == /opt/portfolio &&
   "$(stat -Lc '%u:%a' -- /opt/portfolio)" == 0:711 ]] ||
  fail '/opt/portfolio must already be canonical root-owned mode 0711'
[[ -d /run/lock && ! -L /run/lock &&
   "$(realpath -e -- /run/lock)" == /run/lock &&
   "$(stat -Lc '%u:%g' -- /run/lock)" == 0:0 ]] ||
  fail '/run/lock is unsafe'
run_lock_mode="$(stat -Lc '%a' -- /run/lock)" || fail '/run/lock mode cannot be read'
run_lock_links="$(stat -Lc '%h' -- /run/lock)" || fail '/run/lock link count cannot be read'
(( (8#$run_lock_mode & 8#0002) == 0 || (8#$run_lock_mode & 8#1000) != 0 )) ||
  fail '/run/lock is world-writable without the sticky bit'
[[ "$run_lock_links" =~ ^[1-9][0-9]*$ ]] || fail '/run/lock link count is unsafe'
if [[ ! -e "$INSTALL_LOCK_DIRECTORY" && ! -L "$INSTALL_LOCK_DIRECTORY" ]]; then
  mkdir -m 0700 -- "$INSTALL_LOCK_DIRECTORY"
fi
[[ -d "$INSTALL_LOCK_DIRECTORY" && ! -L "$INSTALL_LOCK_DIRECTORY" &&
   "$(realpath -e -- "$INSTALL_LOCK_DIRECTORY")" == "$INSTALL_LOCK_DIRECTORY" &&
   "$(stat -Lc '%u:%g:%a' -- "$INSTALL_LOCK_DIRECTORY")" == 0:0:700 ]] ||
  fail 'runtime installer lock directory is unsafe'
if [[ ! -e "$INSTALL_LOCK" && ! -L "$INSTALL_LOCK" ]]; then
  install -o root -g root -m 0600 -- /dev/null "$INSTALL_LOCK"
fi
[[ -f "$INSTALL_LOCK" && ! -L "$INSTALL_LOCK" &&
   "$(stat -Lc '%u:%g:%h:%a' -- "$INSTALL_LOCK")" == 0:0:1:600 ]] ||
  fail 'runtime installer lock is unsafe'
exec 9<>"$INSTALL_LOCK"
[[ "$(stat -Lc '%d:%i' -- "$INSTALL_LOCK")" == "$(stat -Lc '%d:%i' -- /proc/self/fd/9)" ]] ||
  fail 'runtime installer lock descriptor identity changed'
flock -x 9

# Lock order is always runtime-installer lock, then the shared backup/prune
# operation lock.  The latter blocks replacement/recovery while a prune is
# active and makes newly starting backup/prune jobs fail closed until the
# fixed runtime is again complete.
[[ "$OPERATION_LOCK" == /* && "$OPERATION_LOCK" != / && ! -L "$OPERATION_LOCK" ]] ||
  fail 'shared backup/prune operation lock path is unsafe'
operation_parent="$(dirname -- "$OPERATION_LOCK")"
if [[ "$operation_parent" == /var/backups/portfolio &&
      ! -e "$operation_parent" && ! -L "$operation_parent" ]]; then
  mkdir -m 0700 -- "$operation_parent"
fi
[[ -d "$operation_parent" && ! -L "$operation_parent" &&
   "$(realpath -e -- "$operation_parent")" == "$operation_parent" &&
   "$(stat -Lc '%u:%g:%a' -- "$operation_parent")" == 0:0:700 ]] ||
  fail 'shared backup/prune operation lock directory is unsafe'
if [[ ! -e "$OPERATION_LOCK" && ! -L "$OPERATION_LOCK" ]]; then
  install -o root -g root -m 0600 -- /dev/null "$OPERATION_LOCK"
fi
[[ -f "$OPERATION_LOCK" && ! -L "$OPERATION_LOCK" &&
   "$(stat -Lc '%u:%g:%h:%a' -- "$OPERATION_LOCK")" == 0:0:1:600 ]] ||
  fail 'shared backup/prune operation lock is unsafe'
exec 8<>"$OPERATION_LOCK"
[[ "$(stat -Lc '%d:%i' -- "$OPERATION_LOCK")" == "$(stat -Lc '%d:%i' -- /proc/self/fd/8)" ]] ||
  fail 'shared backup/prune operation lock descriptor identity changed'
flock -x 8

shopt -s nullglob
for stale_staging in /opt/portfolio/.cos-prune-venv.new.*; do
  [[ -d "$stale_staging" && ! -L "$stale_staging" &&
     "$(stat -Lc '%u:%a' -- "$stale_staging")" == 0:700 ]] ||
    fail 'stale runtime staging directory is unsafe'
  rm -rf -- "$stale_staging"
done
shopt -u nullglob

# Ubuntu-signed packages provide the fixed CPython 3.10 venv and the compiler
# needed for crcmod's reviewed source artifact. Python packages themselves are
# installed only from the hash-locked file below.
export DEBIAN_FRONTEND=noninteractive
if [[ "$TEST_MODE" == true && "$TEST_SKIP_APT" == 1 ]]; then
  :
else
  apt-get update -qq
  apt-get install -y -qq --no-install-recommends \
    python3 python3-venv python3-dev gcc libc6-dev jq >/dev/null
fi
command -v jq >/dev/null 2>&1 || fail 'jq is required'
[[ "$(/usr/bin/python3 -c 'import sys; print("%d.%d" % sys.version_info[:2])')" == 3.10 ]] ||
  fail 'Ubuntu system interpreter is not CPython 3.10'

if [[ -e "$RUNTIME_ROOT.previous" || -L "$RUNTIME_ROOT.previous" ]]; then
  [[ -d "$RUNTIME_ROOT.previous" && ! -L "$RUNTIME_ROOT.previous" &&
     "$(stat -Lc '%u:%a' -- "$RUNTIME_ROOT.previous")" == 0:700 ]] ||
    fail 'previous runtime recovery directory is unsafe'
  if [[ ! -e "$RUNTIME_ROOT" && ! -L "$RUNTIME_ROOT" ]]; then
    mv -- "$RUNTIME_ROOT.previous" "$RUNTIME_ROOT"
    sync -f /opt/portfolio
    recovered_check="$($RUNTIME_ROOT/bin/python3 -B "$GUARD" runtime-check)" ||
      fail 'runtime interrupted before replacement and the previous runtime is invalid'
    [[ "$recovered_check" == SAFE ]] ||
      fail 'restored previous runtime returned an invalid self-check state'
  else
    [[ -d "$RUNTIME_ROOT" && ! -L "$RUNTIME_ROOT" &&
       "$(stat -Lc '%u:%a' -- "$RUNTIME_ROOT")" == 0:700 ]] ||
      fail 'replacement runtime is unsafe while previous runtime exists'
    recovered_check="$($RUNTIME_ROOT/bin/python3 -B "$GUARD" runtime-check)" ||
      fail 'replacement runtime is incomplete while previous runtime exists'
    [[ "$recovered_check" == SAFE ]] ||
      fail 'replacement runtime marker/version gate is invalid'
    rm -rf -- "$RUNTIME_ROOT.previous"
    sync -f /opt/portfolio
  fi
fi

STAGING="$(mktemp -d /opt/portfolio/.cos-prune-venv.new.XXXXXX)"
chmod 0700 "$STAGING"
/usr/bin/python3 -m venv "$STAGING"
PIP_DISABLE_PIP_VERSION_CHECK=1 PIP_NO_INPUT=1 \
  "$STAGING/bin/python3" -m pip install \
    --require-hashes --no-deps --no-build-isolation --only-binary=:all: \
    --no-binary=crcmod -r "$LOCK_FILE"
PIP_DISABLE_PIP_VERSION_CHECK=1 "$STAGING/bin/python3" -m pip check

"$STAGING/bin/python3" - <<'PY'
import importlib.metadata
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
if sys.version_info[:2] != (3, 10) or sys.prefix == sys.base_prefix:
    raise SystemExit("isolated CPython 3.10 check failed")
for name, wanted in expected.items():
    if importlib.metadata.version(name) != wanted:
        raise SystemExit("hash-locked dependency version check failed")
import qcloud_cos  # noqa: F401
from tencentcloud.sts.v20180813 import sts_client  # noqa: F401
PY

# Build tooling is intentionally absent from the executable provider runtime.
# Every remaining distribution is part of the 12-package reviewed closure.
PIP_DISABLE_PIP_VERSION_CHECK=1 "$STAGING/bin/python3" -m pip uninstall \
  --yes pip setuptools >/dev/null

lock_sha="$(sha256sum "$LOCK_FILE" | awk '{print $1}')"
jq -S -c -n \
  --arg lockSha256 "$lock_sha" \
  --arg pythonAbi '3.10' \
  '{schemaVersion:2,lockSha256:$lockSha256,pythonAbi:$pythonAbi,
    dependencyVersions:{
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
    }}' \
  >"$STAGING/.portfolio-runtime.json"
chown -hR root:root "$STAGING"
chmod -R u+rwX,go-rwx "$STAGING"
sync -f "$STAGING"

if [[ -e "$RUNTIME_ROOT" || -L "$RUNTIME_ROOT" ]]; then
  [[ -d "$RUNTIME_ROOT" && ! -L "$RUNTIME_ROOT" &&
     "$(stat -Lc '%u:%a' -- "$RUNTIME_ROOT")" == 0:700 ]] ||
    fail 'existing prune runtime is unsafe'
  mv -- "$RUNTIME_ROOT" "$RUNTIME_ROOT.previous"
  PREVIOUS="$RUNTIME_ROOT.previous"
  sync -f /opt/portfolio
  test_failpoint after-current-to-previous
fi
mv -- "$STAGING" "$RUNTIME_ROOT"
STAGING=''
sync -f /opt/portfolio
test_failpoint after-staging-to-current
[[ "$(stat -Lc '%u:%a' -- "$RUNTIME_ROOT")" == 0:700 &&
   "$($RUNTIME_ROOT/bin/python3 -c 'import sys; print(sys.prefix)')" == "$RUNTIME_ROOT" ]] ||
  fail 'installed prune runtime failed its final path gate'
runtime_check="$($RUNTIME_ROOT/bin/python3 -B "$GUARD" runtime-check)" ||
  fail 'installed prune runtime failed the guard version/marker gate'
[[ "$runtime_check" == SAFE ]] ||
  fail 'installed prune runtime returned an invalid guard self-check state'
if [[ -n "$PREVIOUS" ]]; then
  rm -rf -- "$PREVIOUS"
  PREVIOUS=''
  sync -f /opt/portfolio
fi

printf 'PASS: installed hash-locked COS prune runtime %s\n' "$lock_sha"
