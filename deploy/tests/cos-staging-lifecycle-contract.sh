#!/usr/bin/env bash
set -euo pipefail

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
readonly REPOSITORY_ROOT
readonly INSTALLER="$REPOSITORY_ROOT/deploy/scripts/install-cos-staging-lifecycle.sh"
readonly VERIFIER="$REPOSITORY_ROOT/deploy/scripts/verify-cos-staging-lifecycle.sh"
readonly CONTRACT_SECRET_ID='contract-secret-id-do-not-print'
readonly CONTRACT_SECRET_KEY='contract-secret-key-do-not-print'
readonly CONTRACT_SECURITY_TOKEN='contract-security-token-do-not-print'

WORK_DIRECTORY="$(mktemp -d)"
readonly WORK_DIRECTORY
trap 'rm -rf -- "$WORK_DIRECTORY"' EXIT

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

assert_no_credentials() {
  local output_file="$1"
  local credential
  for credential in "$CONTRACT_SECRET_ID" "$CONTRACT_SECRET_KEY" "$CONTRACT_SECURITY_TOKEN"; do
    if grep -Fq -- "$credential" "$output_file"; then
      fail "command output exposed a credential"
    fi
  done
}

assert_count() {
  local counts_file="$1"
  local operation="$2"
  local expected="$3"
  local actual='0'
  if [[ -f "$counts_file" ]]; then
    actual="$(grep -c "^${operation}$" "$counts_file" || true)"
  fi
  [[ "$actual" == "$expected" ]] ||
    fail "expected ${expected} ${operation} call(s), observed ${actual}"
}

new_case() {
  local name="$1"
  local lifecycle_json="$2"
  local case_directory="$WORK_DIRECTORY/$name"
  mkdir -p "$case_directory"
  printf '%s\n' "$lifecycle_json" >"$case_directory/state.json"
  printf '%s\n' "$case_directory"
}

readonly FIXTURE_COMMAND="$WORK_DIRECTORY/cos-lifecycle-fixture.sh"

cat >"$FIXTURE_COMMAND" <<'FIXTURE'
#!/usr/bin/env bash
set -euo pipefail

: "${FIXTURE_STATE:?FIXTURE_STATE is required}"
: "${FIXTURE_COUNTS:?FIXTURE_COUNTS is required}"

operation="${1:-}"
shift || true

bucket=''
region=''
input=''
while (($# > 0)); do
  case "$1" in
    --bucket)
      bucket="${2:-}"
      shift 2
      ;;
    --region)
      region="${2:-}"
      shift 2
      ;;
    --input)
      input="${2:-}"
      shift 2
      ;;
    *)
      exit 64
      ;;
  esac
done

[[ "$bucket" == 'portfolio-contract-1250000000' ]] || exit 65
[[ "$region" == 'ap-contract' ]] || exit 66
printf '%s\n' "$operation" >>"$FIXTURE_COUNTS"

case "$operation" in
  get)
    get_count="$(grep -c '^get$' "$FIXTURE_COUNTS")"
    if [[ "${FIXTURE_GET_FAIL:-0}" == '1' ]]; then
      printf 'fixture failure: %s\n' "${COS_SECRET_KEY:-redacted}" >&2
      exit 70
    fi
    if [[ "${FIXTURE_DRIFT_ON_SECOND_GET:-0}" == '1' && "$get_count" == '2' ]] &&
       ! grep -q '^put$' "$FIXTURE_COUNTS"; then
      node - "$FIXTURE_STATE" <<'NODE'
const fs = require('fs');
const statePath = process.argv[2];
const document = JSON.parse(fs.readFileSync(statePath, 'utf8'));
document.Rules.push({
  ID: 'concurrent-lifecycle-change',
  Filter: {Prefix: 'audit/'},
  Status: 'Enabled',
  Expiration: {Days: 365},
});
fs.writeFileSync(statePath, `${JSON.stringify(document)}\n`, 'utf8');
NODE
    fi
    if [[ "${FIXTURE_READBACK_FAIL:-0}" == '1' ]] &&
       grep -q '^put$' "$FIXTURE_COUNTS"; then
      printf 'fixture read-back failure: %s\n' "${COS_SECURITY_TOKEN:-redacted}" >&2
      exit 71
    fi
    if [[ "${FIXTURE_INJECT_PROTECTED_ON_READBACK:-0}" == '1' ]] &&
       grep -q '^put$' "$FIXTURE_COUNTS"; then
      node - "$FIXTURE_STATE" <<'NODE'
const fs = require('fs');
const statePath = process.argv[2];
const document = JSON.parse(fs.readFileSync(statePath, 'utf8'));
document.Rules.push({
  ID: 'provider-injected-protected-rule',
  Filter: {Prefix: 'originals/readback/'},
  Status: 'Enabled',
  Expiration: {Days: 1},
});
fs.writeFileSync(statePath, `${JSON.stringify(document)}\n`, 'utf8');
NODE
    fi
    if [[ "${FIXTURE_SECRET_OUTPUT:-0}" == '1' ]]; then
      printf '{"Rules":[],"SecretKey":"%s"}\n' "${COS_SECRET_KEY:-missing}"
      exit 0
    fi
    cat -- "$FIXTURE_STATE"
    ;;
  put)
    [[ -n "$input" && -f "$input" ]] || exit 72
    if [[ "${FIXTURE_PUT_FAIL:-0}" == '1' ]]; then
      printf 'fixture apply failure: %s\n' "${COS_SECRET_ID:-redacted}" >&2
      exit 73
    fi
    if [[ "${FIXTURE_DROP_UNRELATED_ON_PUT:-0}" == '1' ]]; then
      printf '%s\n' '{"Rules":[{"ID":"portfolio-staging-expire-1d","Filter":{"Prefix":"staging/"},"Status":"Enabled","Expiration":{"Days":1}}]}' >"$FIXTURE_STATE"
    elif [[ "${FIXTURE_IGNORE_PUT:-0}" != '1' ]]; then
      cp -- "$input" "$FIXTURE_STATE"
    fi
    ;;
  *)
    exit 74
    ;;
esac
FIXTURE
chmod +x "$FIXTURE_COMMAND"

SYSTEM_GREP="$(command -v grep)"
readonly SYSTEM_GREP
readonly GREP_PROBE_DIRECTORY="$WORK_DIRECTORY/grep-probe"
readonly GREP_PROBE_COMMAND="$GREP_PROBE_DIRECTORY/grep"
mkdir -p "$GREP_PROBE_DIRECTORY"
cat >"$GREP_PROBE_COMMAND" <<'GREP_PROBE'
#!/usr/bin/env bash
set -euo pipefail

: "${FIXTURE_REAL_GREP:?FIXTURE_REAL_GREP is required}"
: "${FIXTURE_GREP_LEAK_MARKER:?FIXTURE_GREP_LEAK_MARKER is required}"

for argument in "$@"; do
  for secret in \
    "${COS_SECRET_ID:-}" \
    "${COS_SECRET_KEY:-}" \
    "${COS_SECURITY_TOKEN:-}"
  do
    if [[ -n "$secret" && "$argument" == "$secret" ]]; then
      : >"$FIXTURE_GREP_LEAK_MARKER"
    fi
  done
done

exec "$FIXTURE_REAL_GREP" "$@"
GREP_PROBE
chmod +x "$GREP_PROBE_COMMAND"

run_lifecycle_script() {
  local script="$1"
  local mode="$2"
  local case_directory="$3"
  local output_file="$4"
  shift 4
  local -a bash_options=()
  if [[ "${RUN_LIFECYCLE_XTRACE:-0}" == '1' ]]; then
    bash_options=(-x)
  fi

  env \
    COS_REGION='ap-contract' \
    COS_BUCKET='portfolio-contract-1250000000' \
    COS_SECRET_ID="$CONTRACT_SECRET_ID" \
    COS_SECRET_KEY="$CONTRACT_SECRET_KEY" \
    COS_SECURITY_TOKEN="$CONTRACT_SECURITY_TOKEN" \
    PORTFOLIO_COS_LIFECYCLE_COMMAND="$FIXTURE_COMMAND" \
    FIXTURE_STATE="$case_directory/state.json" \
    FIXTURE_COUNTS="$case_directory/counts.log" \
    "$@" \
    bash "${bash_options[@]}" "$script" "$mode" >"$output_file" 2>&1
}

expect_success() {
  local label="$1"
  local script="$2"
  local mode="$3"
  local case_directory="$4"
  shift 4
  local output_file="$case_directory/output.log"

  if ! run_lifecycle_script "$script" "$mode" "$case_directory" "$output_file" "$@"; then
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
  shift 4
  local output_file="$case_directory/output.log"

  if run_lifecycle_script "$script" "$mode" "$case_directory" "$output_file" "$@"; then
    assert_no_credentials "$output_file"
    fail "$label unexpectedly succeeded"
  fi
  assert_no_credentials "$output_file"
}

configuration_with_protected_rule() {
  local prefix="$1"
  printf '%s' \
    '{"Rules":[{"ID":"portfolio-staging-expire-1d","Filter":{"Prefix":"staging/"},"Status":"Enabled","Expiration":{"Days":1}},' \
    "{\"ID\":\"dangerous-protected-rule\",\"Filter\":{\"Prefix\":\"${prefix}\"},\"Status\":\"Enabled\",\"Expiration\":{\"Days\":1}}]}"
}

configuration_with_only_protected_rule() {
  local prefix="$1"
  printf '%s' \
    "{\"Rules\":[{\"ID\":\"dangerous-protected-rule\",\"Filter\":{\"Prefix\":\"${prefix}\"},\"Status\":\"Enabled\",\"Expiration\":{\"Days\":1}}]}"
}

configuration_with_protected_and_rule() {
  local prefix="$1"
  printf '%s' \
    '{"Rules":[{"ID":"portfolio-staging-expire-1d","Filter":{"Prefix":"staging/"},"Status":"Enabled","Expiration":{"Days":1}},' \
    "{\"ID\":\"dangerous-protected-and-rule\",\"Filter\":{\"And\":{\"Prefix\":\"${prefix}\",\"Tag\":[{\"Key\":\"scope\",\"Value\":\"temporary\"}]}},\"Status\":\"Enabled\",\"Expiration\":{\"Days\":1}}]}"
}

readonly EXACT_RULE='{"Rules":[{"ID":"portfolio-staging-expire-1d","Filter":{"Prefix":"staging/"},"Status":"Enabled","Expiration":{"Days":1}}]}'
readonly OFFICIAL_SDK_EXACT_RULE='{"Rules":[{"ID":"portfolio-staging-expire-1d","Filter":{"Prefix":"staging/"},"Status":"Enabled","Expiration":{"Days":"1"}}]}'
readonly DISABLED_RULE='{"Rules":[{"ID":"portfolio-staging-expire-1d","Filter":{"Prefix":"staging/"},"Status":"Disabled","Expiration":{"Days":1}}]}'
readonly WRONG_DAY_RULE='{"Rules":[{"ID":"portfolio-staging-expire-1d","Filter":{"Prefix":"staging/"},"Status":"Enabled","Expiration":{"Days":2}}]}'
readonly MISSING_RULE='{"Rules":[{"ID":"logs-expire","Filter":{"Prefix":"logs/"},"Status":"Enabled","Expiration":{"Days":90},"Preserve":{"Marker":"unchanged"}}]}'
readonly EXACT_WITH_SAFE_RULE='{"Rules":[{"ID":"logs-expire","Filter":{"Prefix":"logs/"},"Status":"Enabled","Expiration":{"Days":90},"Preserve":{"Marker":"unchanged"}},{"ID":"portfolio-staging-expire-1d","Filter":{"Prefix":"staging/"},"Status":"Enabled","Expiration":{"Days":1}}]}'
readonly SAFE_AND_RULE='{"Rules":[{"ID":"tagged-logs-expire","Filter":{"And":{"Prefix":"logs/","Tag":[{"Key":"scope","Value":"temporary"}]}},"Status":"Enabled","Expiration":{"Days":90},"Preserve":{"Marker":"and-unchanged"}}]}'
readonly EXACT_WITH_SAFE_AND_RULE='{"Rules":[{"ID":"tagged-logs-expire","Filter":{"And":{"Prefix":"logs/","Tag":[{"Key":"scope","Value":"temporary"}]}},"Status":"Enabled","Expiration":{"Days":90},"Preserve":{"Marker":"and-unchanged"}},{"ID":"portfolio-staging-expire-1d","Filter":{"Prefix":"staging/"},"Status":"Enabled","Expiration":{"Days":1}}]}'
readonly BROAD_RULE='{"Rules":[{"ID":"dangerous-broad","Filter":{"Prefix":""},"Status":"Enabled","Expiration":{"Days":1}}]}'
readonly BROAD_AND_RULE='{"Rules":[{"ID":"dangerous-tag-only","Filter":{"And":{"Tag":[{"Key":"scope","Value":"temporary"}]}},"Status":"Enabled","Expiration":{"Days":1}}]}'
readonly DESCENDANT_OVERLAP_RULE='{"Rules":[{"ID":"dangerous-staging-child","Filter":{"Prefix":"staging/tmp/"},"Status":"Enabled","Expiration":{"Days":1}}]}'
readonly ANCESTOR_OVERLAP_RULE='{"Rules":[{"ID":"dangerous-staging-ancestor","Filter":{"Prefix":"staging"},"Status":"Enabled","Expiration":{"Days":1}}]}'
readonly WRONG_ID_RULE='{"Rules":[{"ID":"not-the-repository-rule","Filter":{"Prefix":"staging/"},"Status":"Enabled","Expiration":{"Days":1}}]}'
readonly DUPLICATE_TARGET_RULE='{"Rules":[{"ID":"portfolio-staging-expire-1d","Filter":{"Prefix":"staging/"},"Status":"Enabled","Expiration":{"Days":1}},{"ID":"portfolio-staging-expire-1d","Filter":{"Prefix":"staging/"},"Status":"Enabled","Expiration":{"Days":1}}]}'
readonly UNKNOWN_FILTER_RULE='{"Rules":[{"ID":"unknown-filter-shape","Filter":{"Prefix":"logs/","UnknownSelector":"unsafe"},"Status":"Enabled","Expiration":{"Days":90}}]}'

case_directory="$(new_case installer_exact "$EXACT_RULE")"
expect_success 'installer exact-rule idempotency' "$INSTALLER" '--apply' "$case_directory"
assert_count "$case_directory/counts.log" get 2
assert_count "$case_directory/counts.log" put 0

case_directory="$(new_case installer_official_sdk_exact "$OFFICIAL_SDK_EXACT_RULE")"
expect_success 'installer official-SDK string-day idempotency' "$INSTALLER" '--apply' "$case_directory"
assert_count "$case_directory/counts.log" get 2
assert_count "$case_directory/counts.log" put 0

case_directory="$(new_case installer_concurrent_drift "$MISSING_RULE")"
expect_failure \
  'installer pre-apply concurrent lifecycle drift rejection' \
  "$INSTALLER" \
  '--apply' \
  "$case_directory" \
  FIXTURE_DRIFT_ON_SECOND_GET=1
assert_count "$case_directory/counts.log" get 2
assert_count "$case_directory/counts.log" put 0

case_directory="$(new_case installer_merge "$MISSING_RULE")"
expect_success 'installer safe merge' "$INSTALLER" '--apply' "$case_directory"
assert_count "$case_directory/counts.log" get 3
assert_count "$case_directory/counts.log" put 1
node - "$case_directory/state.json" <<'NODE' || fail 'installer did not preserve the unrelated rule while appending the exact rule'
const fs = require('fs');
const value = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
if (!Array.isArray(value.Rules) || value.Rules.length !== 2) process.exit(1);
const unrelated = value.Rules.find(rule => rule.ID === 'logs-expire');
const target = value.Rules.find(rule => rule.ID === 'portfolio-staging-expire-1d');
if (JSON.stringify(unrelated) !== JSON.stringify({
  ID: 'logs-expire',
  Filter: {Prefix: 'logs/'},
  Status: 'Enabled',
  Expiration: {Days: 90},
  Preserve: {Marker: 'unchanged'}
})) process.exit(1);
if (JSON.stringify(target) !== JSON.stringify({
  ID: 'portfolio-staging-expire-1d',
  Filter: {Prefix: 'staging/'},
  Status: 'Enabled',
  Expiration: {Days: 1}
})) process.exit(1);
NODE

case_directory="$(new_case installer_safe_and_merge "$SAFE_AND_RULE")"
expect_success 'installer safe Filter.And prefix merge' "$INSTALLER" '--apply' "$case_directory"
assert_count "$case_directory/counts.log" get 3
assert_count "$case_directory/counts.log" put 1
node - "$case_directory/state.json" <<'NODE' || fail 'installer did not preserve the safe Filter.And rule'
const fs = require('fs');
const value = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
const safe = value.Rules.find(rule => rule.ID === 'tagged-logs-expire');
if (JSON.stringify(safe) !== JSON.stringify({
  ID: 'tagged-logs-expire',
  Filter: {And: {Prefix: 'logs/', Tag: [{Key: 'scope', Value: 'temporary'}]}},
  Status: 'Enabled',
  Expiration: {Days: 90},
  Preserve: {Marker: 'and-unchanged'}
})) process.exit(1);
if (!value.Rules.some(rule => rule.ID === 'portfolio-staging-expire-1d')) process.exit(1);
NODE

case_directory="$(new_case installer_broad "$BROAD_RULE")"
expect_failure 'installer broad-prefix rejection' "$INSTALLER" '--apply' "$case_directory"
assert_count "$case_directory/counts.log" put 0

case_directory="$(new_case installer_broad_and "$BROAD_AND_RULE")"
expect_failure 'installer broad Filter.And rejection' "$INSTALLER" '--apply' "$case_directory"
assert_count "$case_directory/counts.log" put 0

case_directory="$(new_case installer_descendant_overlap "$DESCENDANT_OVERLAP_RULE")"
expect_failure 'installer descendant-overlap rejection' "$INSTALLER" '--apply' "$case_directory"
assert_count "$case_directory/counts.log" put 0

case_directory="$(new_case installer_ancestor_overlap "$ANCESTOR_OVERLAP_RULE")"
expect_failure 'installer ancestor-overlap rejection' "$INSTALLER" '--apply' "$case_directory"
assert_count "$case_directory/counts.log" put 0

case_directory="$(new_case installer_wrong_id "$WRONG_ID_RULE")"
expect_failure 'installer wrong-ID exact-shape rejection' "$INSTALLER" '--apply' "$case_directory"
assert_count "$case_directory/counts.log" put 0

case_directory="$(new_case installer_duplicate_target "$DUPLICATE_TARGET_RULE")"
expect_failure 'installer duplicate-target rejection' "$INSTALLER" '--apply' "$case_directory"
assert_count "$case_directory/counts.log" put 0

case_directory="$(new_case installer_unknown_filter "$UNKNOWN_FILTER_RULE")"
expect_failure 'installer unknown-filter fail-closed rejection' "$INSTALLER" '--apply' "$case_directory"
assert_count "$case_directory/counts.log" put 0

for protected_case in \
  'originals/ originals' \
  'variants/ variants' \
  'smoke/ smoke' \
  'backup/ backup' \
  'backups/ backups' \
  'originals/archive/ protected-descendant' \
  'orig protected-ancestor'
do
  read -r protected_prefix protected_label <<<"$protected_case"
  case_directory="$(new_case \
    "installer_protected_${protected_label}" \
    "$(configuration_with_protected_rule "$protected_prefix")")"
  expect_failure \
    "installer ${protected_label} protected-prefix rejection" \
    "$INSTALLER" \
    '--apply' \
    "$case_directory"
  assert_count "$case_directory/counts.log" put 0
done

case_directory="$(new_case \
  installer_protected_without_target \
  "$(configuration_with_only_protected_rule 'originals/')")"
expect_failure \
  'installer protected-prefix rejection before target merge' \
  "$INSTALLER" \
  '--apply' \
  "$case_directory"
assert_count "$case_directory/counts.log" put 0

case_directory="$(new_case \
  installer_protected_and \
  "$(configuration_with_protected_and_rule 'originals/')")"
expect_failure \
  'installer protected Filter.And prefix rejection' \
  "$INSTALLER" \
  '--apply' \
  "$case_directory"
assert_count "$case_directory/counts.log" put 0

case_directory="$(new_case installer_malformed '{not-json')"
expect_failure 'installer malformed-read rejection' "$INSTALLER" '--apply' "$case_directory"
assert_count "$case_directory/counts.log" put 0

case_directory="$(new_case installer_get_failure "$MISSING_RULE")"
expect_failure 'installer read-command failure' "$INSTALLER" '--apply' "$case_directory" FIXTURE_GET_FAIL=1
assert_count "$case_directory/counts.log" put 0

case_directory="$(new_case installer_put_failure "$MISSING_RULE")"
expect_failure 'installer apply-command failure' "$INSTALLER" '--apply' "$case_directory" FIXTURE_PUT_FAIL=1
assert_count "$case_directory/counts.log" put 1

case_directory="$(new_case installer_readback_failure "$MISSING_RULE")"
expect_failure 'installer read-back command failure' "$INSTALLER" '--apply' "$case_directory" FIXTURE_READBACK_FAIL=1
assert_count "$case_directory/counts.log" put 1

case_directory="$(new_case installer_readback_mismatch "$MISSING_RULE")"
expect_failure 'installer read-back mismatch rejection' "$INSTALLER" '--apply' "$case_directory" FIXTURE_IGNORE_PUT=1
assert_count "$case_directory/counts.log" put 1

case_directory="$(new_case installer_readback_dropped_rule "$MISSING_RULE")"
expect_failure 'installer read-back unrelated-rule loss rejection' "$INSTALLER" '--apply' "$case_directory" FIXTURE_DROP_UNRELATED_ON_PUT=1
assert_count "$case_directory/counts.log" put 1

case_directory="$(new_case installer_readback_protected_rule "$MISSING_RULE")"
expect_failure \
  'installer read-back protected-prefix injection rejection' \
  "$INSTALLER" \
  '--apply' \
  "$case_directory" \
  FIXTURE_INJECT_PROTECTED_ON_READBACK=1
assert_count "$case_directory/counts.log" put 1

case_directory="$(new_case installer_secret_output "$MISSING_RULE")"
expect_failure 'installer secret-bearing output rejection' "$INSTALLER" '--apply' "$case_directory" FIXTURE_SECRET_OUTPUT=1
assert_count "$case_directory/counts.log" put 0

case_directory="$(new_case verifier_exact "$EXACT_RULE")"
expect_success 'verifier exact-rule acceptance' "$VERIFIER" '--check-live' "$case_directory"
assert_count "$case_directory/counts.log" get 1
assert_count "$case_directory/counts.log" put 0

case_directory="$(new_case verifier_official_sdk_exact "$OFFICIAL_SDK_EXACT_RULE")"
expect_success 'verifier official-SDK string-day acceptance' "$VERIFIER" '--check-live' "$case_directory"

case_directory="$(new_case verifier_exact_with_safe "$EXACT_WITH_SAFE_RULE")"
expect_success 'verifier exact rule with unrelated safe rule acceptance' "$VERIFIER" '--check-live' "$case_directory"

case_directory="$(new_case verifier_exact_with_safe_and "$EXACT_WITH_SAFE_AND_RULE")"
expect_success 'verifier exact rule with safe Filter.And rule acceptance' "$VERIFIER" '--check-live' "$case_directory"

case_directory="$(new_case verifier_disabled "$DISABLED_RULE")"
expect_failure 'verifier disabled-rule rejection' "$VERIFIER" '--check-live' "$case_directory"

case_directory="$(new_case verifier_missing "$MISSING_RULE")"
expect_failure 'verifier missing-rule rejection' "$VERIFIER" '--check-live' "$case_directory"

case_directory="$(new_case verifier_wrong_day "$WRONG_DAY_RULE")"
expect_failure 'verifier wrong-day rejection' "$VERIFIER" '--check-live' "$case_directory"

case_directory="$(new_case verifier_broad "$BROAD_RULE")"
expect_failure 'verifier broad-prefix rejection' "$VERIFIER" '--check-live' "$case_directory"

case_directory="$(new_case verifier_broad_and "$BROAD_AND_RULE")"
expect_failure 'verifier broad Filter.And rejection' "$VERIFIER" '--check-live' "$case_directory"

case_directory="$(new_case verifier_descendant_overlap "$DESCENDANT_OVERLAP_RULE")"
expect_failure 'verifier descendant-overlap rejection' "$VERIFIER" '--check-live' "$case_directory"

case_directory="$(new_case verifier_ancestor_overlap "$ANCESTOR_OVERLAP_RULE")"
expect_failure 'verifier ancestor-overlap rejection' "$VERIFIER" '--check-live' "$case_directory"

case_directory="$(new_case verifier_wrong_id "$WRONG_ID_RULE")"
expect_failure 'verifier wrong-ID exact-shape rejection' "$VERIFIER" '--check-live' "$case_directory"

case_directory="$(new_case verifier_duplicate_target "$DUPLICATE_TARGET_RULE")"
expect_failure 'verifier duplicate-target rejection' "$VERIFIER" '--check-live' "$case_directory"

case_directory="$(new_case verifier_unknown_filter "$UNKNOWN_FILTER_RULE")"
expect_failure 'verifier unknown-filter fail-closed rejection' "$VERIFIER" '--check-live' "$case_directory"

for protected_case in \
  'originals/ originals' \
  'variants/ variants' \
  'smoke/ smoke' \
  'backup/ backup' \
  'backups/ backups' \
  'originals/archive/ protected-descendant' \
  'orig protected-ancestor'
do
  read -r protected_prefix protected_label <<<"$protected_case"
  case_directory="$(new_case \
    "verifier_protected_${protected_label}" \
    "$(configuration_with_protected_rule "$protected_prefix")")"
  expect_failure \
    "verifier ${protected_label} protected-prefix rejection" \
    "$VERIFIER" \
    '--check-live' \
    "$case_directory"
done

case_directory="$(new_case \
  verifier_protected_and \
  "$(configuration_with_protected_and_rule 'originals/')")"
expect_failure \
  'verifier protected Filter.And prefix rejection' \
  "$VERIFIER" \
  '--check-live' \
  "$case_directory"

case_directory="$(new_case verifier_malformed '{not-json')"
expect_failure 'verifier malformed-read rejection' "$VERIFIER" '--check-live' "$case_directory"

case_directory="$(new_case verifier_command_failure "$EXACT_RULE")"
expect_failure 'verifier command-failure rejection' "$VERIFIER" '--check-live' "$case_directory" FIXTURE_GET_FAIL=1

case_directory="$(new_case verifier_secret_output "$EXACT_RULE")"
expect_failure 'verifier secret-bearing output rejection' "$VERIFIER" '--check-live' "$case_directory" FIXTURE_SECRET_OUTPUT=1

case_directory="$(new_case installer_no_mode "$EXACT_RULE")"
expect_failure 'installer explicit-mode requirement' "$INSTALLER" '' "$case_directory"
assert_count "$case_directory/counts.log" get 0
assert_count "$case_directory/counts.log" put 0

case_directory="$(new_case verifier_no_mode "$EXACT_RULE")"
expect_failure 'verifier explicit-mode requirement' "$VERIFIER" '' "$case_directory"
assert_count "$case_directory/counts.log" get 0
assert_count "$case_directory/counts.log" put 0

case_directory="$(new_case installer_missing_credentials "$EXACT_RULE")"
output_file="$case_directory/output.log"
if env \
  COS_REGION='ap-contract' \
  COS_BUCKET='portfolio-contract-1250000000' \
  COS_SECRET_ID='' \
  COS_SECRET_KEY='' \
  PORTFOLIO_COS_LIFECYCLE_COMMAND="$FIXTURE_COMMAND" \
  FIXTURE_STATE="$case_directory/state.json" \
  FIXTURE_COUNTS="$case_directory/counts.log" \
  bash "$INSTALLER" --apply >"$output_file" 2>&1; then
  fail 'installer accepted missing credentials'
fi
assert_no_credentials "$output_file"
assert_count "$case_directory/counts.log" get 0
assert_count "$case_directory/counts.log" put 0

case_directory="$(new_case verifier_missing_credentials "$EXACT_RULE")"
output_file="$case_directory/output.log"
if env \
  COS_REGION='ap-contract' \
  COS_BUCKET='portfolio-contract-1250000000' \
  COS_SECRET_ID='' \
  COS_SECRET_KEY='' \
  PORTFOLIO_COS_LIFECYCLE_COMMAND="$FIXTURE_COMMAND" \
  FIXTURE_STATE="$case_directory/state.json" \
  FIXTURE_COUNTS="$case_directory/counts.log" \
  bash "$VERIFIER" --check-live >"$output_file" 2>&1; then
  fail 'verifier accepted missing credentials'
fi
assert_no_credentials "$output_file"
assert_count "$case_directory/counts.log" get 0
assert_count "$case_directory/counts.log" put 0

case_directory="$(new_case installer_missing_security_token "$EXACT_RULE")"
output_file="$case_directory/output.log"
if env \
  COS_REGION='ap-contract' \
  COS_BUCKET='portfolio-contract-1250000000' \
  COS_SECRET_ID="$CONTRACT_SECRET_ID" \
  COS_SECRET_KEY="$CONTRACT_SECRET_KEY" \
  COS_SECURITY_TOKEN='' \
  PORTFOLIO_COS_LIFECYCLE_COMMAND="$FIXTURE_COMMAND" \
  FIXTURE_STATE="$case_directory/state.json" \
  FIXTURE_COUNTS="$case_directory/counts.log" \
  bash "$INSTALLER" --apply >"$output_file" 2>&1; then
  fail 'installer accepted a non-temporary operations credential without COS_SECURITY_TOKEN'
fi
assert_no_credentials "$output_file"
assert_count "$case_directory/counts.log" get 0
assert_count "$case_directory/counts.log" put 0

case_directory="$(new_case verifier_without_security_token "$EXACT_RULE")"
expect_success 'read-only verifier accepts a credential without a security token' "$VERIFIER" '--check-live' "$case_directory" COS_SECURITY_TOKEN=

case_directory="$(new_case installer_xtrace "$EXACT_RULE")"
RUN_LIFECYCLE_XTRACE=1 expect_success \
  'installer disables inherited xtrace before credential expansion' \
  "$INSTALLER" \
  '--apply' \
  "$case_directory"

case_directory="$(new_case verifier_xtrace "$EXACT_RULE")"
RUN_LIFECYCLE_XTRACE=1 expect_success \
  'verifier disables inherited xtrace before credential expansion' \
  "$VERIFIER" \
  '--check-live' \
  "$case_directory"

case_directory="$(new_case installer_secret_argv "$EXACT_RULE")"
expect_success \
  'installer keeps credentials out of helper process arguments' \
  "$INSTALLER" \
  '--apply' \
  "$case_directory" \
  PATH="$GREP_PROBE_DIRECTORY:$PATH" \
  FIXTURE_REAL_GREP="$SYSTEM_GREP" \
  FIXTURE_GREP_LEAK_MARKER="$case_directory/grep-secret-argv"
[[ ! -e "$case_directory/grep-secret-argv" ]] ||
  fail 'installer exposed a credential through grep process arguments'

case_directory="$(new_case verifier_secret_argv "$EXACT_RULE")"
expect_success \
  'verifier keeps credentials out of helper process arguments' \
  "$VERIFIER" \
  '--check-live' \
  "$case_directory" \
  PATH="$GREP_PROBE_DIRECTORY:$PATH" \
  FIXTURE_REAL_GREP="$SYSTEM_GREP" \
  FIXTURE_GREP_LEAK_MARKER="$case_directory/grep-secret-argv"
[[ ! -e "$case_directory/grep-secret-argv" ]] ||
  fail 'verifier exposed a credential through grep process arguments'

printf '%s\n' 'PASS: COS staging lifecycle installer and verifier contracts'
