#!/usr/bin/env bash
set -euo pipefail

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
readonly REPOSITORY_ROOT
readonly INSTALLER="$REPOSITORY_ROOT/deploy/scripts/install-cos-staging-lifecycle.sh"
readonly VERIFIER="$REPOSITORY_ROOT/deploy/scripts/verify-cos-staging-lifecycle.sh"
readonly CONTRACT_REGION='ap-contract'
readonly CONTRACT_BUCKET='portfolio-sdk-contract-1250000000'
readonly CONTRACT_SECRET_ID='sdk-contract-secret-id-do-not-print'
readonly CONTRACT_SECRET_KEY='sdk-contract-secret-key-do-not-print'
readonly CONTRACT_SECURITY_TOKEN='sdk-contract-security-token-do-not-print'

WORK_DIRECTORY="$(mktemp -d)"
readonly WORK_DIRECTORY
readonly FAKE_SDK_ROOT="$WORK_DIRECTORY/fake-sdk"
FAKE_SDK_PYTHONPATH=''
trap 'rm -rf -- "$WORK_DIRECTORY"' EXIT

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

python_path_for_host() {
  local path="$1"
  if [[ "$(python3 -c 'import os; print(os.name)')" == 'nt' ]]; then
    command -v cygpath >/dev/null 2>&1 ||
      fail 'cygpath is required when the contract uses Windows Python'
    cygpath -w "$path"
    return
  fi
  printf '%s\n' "$path"
}

ensure_python3_command() {
  if command -v python3 >/dev/null 2>&1; then
    return
  fi

  local shim_directory="$WORK_DIRECTORY/python-bin"
  mkdir -p "$shim_directory"
  if command -v py >/dev/null 2>&1 &&
     py -3 -c 'import sys; raise SystemExit(0 if sys.version_info.major == 3 else 1)' \
       >/dev/null 2>&1; then
    cat >"$shim_directory/python3" <<'PYTHON_SHIM'
#!/usr/bin/env bash
exec py -3 "$@"
PYTHON_SHIM
  elif command -v python >/dev/null 2>&1 &&
       python -c 'import sys; raise SystemExit(0 if sys.version_info.major == 3 else 1)' \
         >/dev/null 2>&1; then
    cat >"$shim_directory/python3" <<'PYTHON_SHIM'
#!/usr/bin/env bash
exec python "$@"
PYTHON_SHIM
  else
    fail 'Python 3 is required for the SDK adapter contract'
  fi
  chmod +x "$shim_directory/python3"
  PATH="$shim_directory:$PATH"
  export PATH
}

assert_no_credentials() {
  local output_file="$1"
  local credential
  for credential in \
    "$CONTRACT_SECRET_ID" \
    "$CONTRACT_SECRET_KEY" \
    "$CONTRACT_SECURITY_TOKEN"
  do
    if grep -Fq -- "$credential" "$output_file"; then
      fail 'official SDK adapter output exposed a credential'
    fi
  done
}

assert_calls() {
  local calls_file="$1"
  shift
  local -a actual=()
  mapfile -t actual <"$calls_file"
  if [[ "${#actual[@]}" != "$#" ]]; then
    local rendered_calls=''
    printf -v rendered_calls '%q ' "${actual[@]}"
    fail "expected $# SDK call(s), observed ${#actual[@]}: ${rendered_calls}"
  fi

  local index=0
  local expected
  for expected in "$@"; do
    local observed="${actual[$index]%$'\r'}"
    [[ "$observed" == "$expected" ]] ||
      fail "expected SDK call $((index + 1)) to be ${expected}, observed ${observed}"
    index=$((index + 1))
  done
}

new_case() {
  local name="$1"
  local state="$2"
  local case_directory="$WORK_DIRECTORY/$name"
  mkdir -p "$case_directory"
  printf '%s\n' "$state" >"$case_directory/state.json"
  : >"$case_directory/calls.log"
  printf '%s\n' "$case_directory"
}

run_adapter_script() {
  local script="$1"
  local mode="$2"
  local case_directory="$3"
  local output_file="$4"
  local case_directory_for_python
  local security_token="${ADAPTER_SECURITY_TOKEN-$CONTRACT_SECURITY_TOKEN}"
  case_directory_for_python="$(python_path_for_host "$case_directory")"

  env -u PORTFOLIO_COS_LIFECYCLE_COMMAND \
    PYTHONPATH="$FAKE_SDK_PYTHONPATH" \
    FAKE_COS_STATE="$case_directory_for_python/state.json" \
    FAKE_COS_CALLS="$case_directory_for_python/calls.log" \
    FAKE_COS_PUT_CAPTURE="$case_directory_for_python/put.json" \
    FAKE_EXPECT_REGION="$CONTRACT_REGION" \
    FAKE_EXPECT_BUCKET="$CONTRACT_BUCKET" \
    FAKE_EXPECT_SECRET_ID="$CONTRACT_SECRET_ID" \
    FAKE_EXPECT_SECRET_KEY="$CONTRACT_SECRET_KEY" \
    FAKE_EXPECT_TOKEN="$security_token" \
    COS_REGION="$CONTRACT_REGION" \
    COS_BUCKET="$CONTRACT_BUCKET" \
    COS_SECRET_ID="$CONTRACT_SECRET_ID" \
    COS_SECRET_KEY="$CONTRACT_SECRET_KEY" \
    COS_SECURITY_TOKEN="$security_token" \
    bash "$script" "$mode" >"$output_file" 2>&1
}

expect_success() {
  local label="$1"
  local script="$2"
  local mode="$3"
  local case_directory="$4"
  local output_file="$case_directory/output.log"

  if ! run_adapter_script "$script" "$mode" "$case_directory" "$output_file"; then
    assert_no_credentials "$output_file"
    sed -n '1,80p' "$output_file" >&2
    fail "$label unexpectedly failed"
  fi
  assert_no_credentials "$output_file"
}

expect_failure() {
  local label="$1"
  local script="$2"
  local mode="$3"
  local case_directory="$4"
  local output_file="$case_directory/output.log"

  if run_adapter_script "$script" "$mode" "$case_directory" "$output_file"; then
    assert_no_credentials "$output_file"
    fail "$label unexpectedly succeeded"
  fi
  assert_no_credentials "$output_file"
}

mkdir -p "$FAKE_SDK_ROOT/qcloud_cos"

cat >"$FAKE_SDK_ROOT/qcloud_cos/cos_exception.py" <<'PYTHON'
class CosServiceError(Exception):
    def __init__(self, code):
        super().__init__('sanitized fake COS service failure')
        self._code = code

    def get_error_code(self):
        return self._code
PYTHON

cat >"$FAKE_SDK_ROOT/qcloud_cos/__init__.py" <<'PYTHON'
import json
import os

from .cos_exception import CosServiceError


def required(name):
    value = os.environ.get(name)
    if not value:
        raise RuntimeError('fake SDK contract environment is incomplete')
    return value


def record(operation):
    with open(required('FAKE_COS_CALLS'), 'a', encoding='utf-8') as stream:
        stream.write(operation + '\n')


def require_bucket(bucket):
    if bucket != required('FAKE_EXPECT_BUCKET'):
        raise RuntimeError('fake SDK received the wrong bucket')


class CosConfig:
    def __init__(self, Region, SecretId, SecretKey, Token=None, Scheme=None):
        if Region != required('FAKE_EXPECT_REGION'):
            raise RuntimeError('fake SDK received the wrong region')
        if SecretId != required('FAKE_EXPECT_SECRET_ID'):
            raise RuntimeError('fake SDK received the wrong secret ID')
        if SecretKey != required('FAKE_EXPECT_SECRET_KEY'):
            raise RuntimeError('fake SDK received the wrong secret key')
        expected_token = os.environ.get('FAKE_EXPECT_TOKEN') or None
        if Token != expected_token:
            raise RuntimeError('fake SDK received the wrong security token')
        if Scheme != 'https':
            raise RuntimeError('fake SDK requires HTTPS')


class CosS3Client:
    def __init__(self, config):
        if not isinstance(config, CosConfig):
            raise RuntimeError('fake SDK received the wrong configuration')

    def get_bucket_lifecycle(self, Bucket):
        require_bucket(Bucket)
        record('get')
        with open(required('FAKE_COS_STATE'), encoding='utf-8') as stream:
            state = json.load(stream)
        if state.get('RaiseWithSecret') is True:
            raise RuntimeError(
                'fake provider failure: ' + os.environ['COS_SECRET_KEY'])
        if state.get('NoSuchLifecycleConfiguration') is True:
            raise CosServiceError('NoSuchLifecycleConfiguration')
        if set(state) != {'Rule'} or not isinstance(state['Rule'], list):
            raise RuntimeError('fake SDK lifecycle state is malformed')
        return state

    def put_bucket_lifecycle(self, Bucket, LifecycleConfiguration):
        require_bucket(Bucket)
        record('put')
        if (not isinstance(LifecycleConfiguration, dict) or
                set(LifecycleConfiguration) != {'Rule'} or
                not isinstance(LifecycleConfiguration['Rule'], list)):
            raise RuntimeError('fake SDK received a malformed lifecycle write')
        document = {'Rule': LifecycleConfiguration['Rule']}
        with open(required('FAKE_COS_PUT_CAPTURE'), 'w', encoding='utf-8') as stream:
            json.dump(document, stream, separators=(',', ':'))
        with open(required('FAKE_COS_STATE'), 'w', encoding='utf-8') as stream:
            json.dump(document, stream, separators=(',', ':'))
PYTHON

ensure_python3_command
command -v python3 >/dev/null 2>&1 || fail 'python3 command preparation failed'
FAKE_SDK_PYTHONPATH="$(python_path_for_host "$FAKE_SDK_ROOT")"
readonly FAKE_SDK_PYTHONPATH

readonly EXACT_RULE='{"ID":"portfolio-staging-expire-1d","Filter":{"Prefix":"staging/"},"Status":"Enabled","Expiration":{"Days":"1"}}'
readonly SAFE_RULE='{"ID":"logs-expire","Filter":{"Prefix":"logs/"},"Status":"Enabled","Expiration":{"Days":"90"}}'

case_directory="$(new_case \
  verifier_rule_list \
  "{\"Rule\":[${SAFE_RULE},${EXACT_RULE}]}")"
expect_success \
  'official SDK verifier Rule-list mapping' \
  "$VERIFIER" \
  '--check-live' \
  "$case_directory"
assert_calls "$case_directory/calls.log" get

case_directory="$(new_case \
  verifier_without_security_token \
  "{\"Rule\":[${EXACT_RULE}]}")"
ADAPTER_SECURITY_TOKEN='' expect_success \
  'official SDK verifier supports a non-temporary credential without Token' \
  "$VERIFIER" \
  '--check-live' \
  "$case_directory"
assert_calls "$case_directory/calls.log" get

case_directory="$(new_case \
  installer_no_such \
  '{"NoSuchLifecycleConfiguration":true}')"
expect_success \
  'official SDK installer NoSuchLifecycleConfiguration merge and read-back' \
  "$INSTALLER" \
  '--apply' \
  "$case_directory"
assert_calls "$case_directory/calls.log" get get put get

if ! node - "$case_directory/put.json" <<'NODE'
const fs = require('fs');
const document = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
if (JSON.stringify(Object.keys(document)) !== JSON.stringify(['Rule'])) process.exit(1);
if (!Array.isArray(document.Rule) || document.Rule.length !== 1) process.exit(1);
if (JSON.stringify(document.Rule[0]) !== JSON.stringify({
  ID: 'portfolio-staging-expire-1d',
  Filter: {Prefix: 'staging/'},
  Status: 'Enabled',
  Expiration: {Days: 1},
})) process.exit(1);
NODE
then
  fail 'official SDK adapter did not receive the exact Rule-list PUT document'
fi

case_directory="$(new_case \
  installer_preserves_existing_rule \
  "{\"Rule\":[${SAFE_RULE}]}")"
expect_success \
  'official SDK installer preserves the complete existing Rule list' \
  "$INSTALLER" \
  '--apply' \
  "$case_directory"
assert_calls "$case_directory/calls.log" get get put get

if ! node - "$case_directory/put.json" <<'NODE'
const fs = require('fs');
const document = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
if (JSON.stringify(Object.keys(document)) !== JSON.stringify(['Rule'])) process.exit(1);
if (!Array.isArray(document.Rule) || document.Rule.length !== 2) process.exit(1);
const safe = document.Rule.find(rule => rule.ID === 'logs-expire');
const target = document.Rule.find(rule => rule.ID === 'portfolio-staging-expire-1d');
if (JSON.stringify(safe) !== JSON.stringify({
  ID: 'logs-expire',
  Filter: {Prefix: 'logs/'},
  Status: 'Enabled',
  Expiration: {Days: '90'},
})) process.exit(1);
if (JSON.stringify(target) !== JSON.stringify({
  ID: 'portfolio-staging-expire-1d',
  Filter: {Prefix: 'staging/'},
  Status: 'Enabled',
  Expiration: {Days: 1},
})) process.exit(1);
NODE
then
  fail 'official SDK adapter did not preserve the complete existing Rule list'
fi

case_directory="$(new_case \
  verifier_no_such \
  '{"NoSuchLifecycleConfiguration":true}')"
expect_failure \
  'official SDK verifier missing-lifecycle rejection' \
  "$VERIFIER" \
  '--check-live' \
  "$case_directory"
assert_calls "$case_directory/calls.log" get

case_directory="$(new_case \
  verifier_secret_exception \
  '{"RaiseWithSecret":true}')"
expect_failure \
  'official SDK exception credential redaction' \
  "$VERIFIER" \
  '--check-live' \
  "$case_directory"
assert_calls "$case_directory/calls.log" get

printf '%s\n' 'PASS: offline Tencent qcloud_cos adapter contract'
