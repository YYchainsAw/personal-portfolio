#!/usr/bin/env bash
set -euo pipefail
set +x
umask 077

SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
readonly SCRIPT_DIRECTORY
readonly RULE_FILE="$SCRIPT_DIRECTORY/../cos/staging-lifecycle-rule.json"
readonly TARGET_RULE_ID='portfolio-staging-expire-1d'

WORK_DIRECTORY=''

fail() {
  printf 'COS staging lifecycle installation failed: %s\n' "$1" >&2
  exit 1
}

cleanup() {
  if [[ -n "$WORK_DIRECTORY" && -d "$WORK_DIRECTORY" ]]; then
    rm -rf -- "$WORK_DIRECTORY"
  fi
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

require_nonempty_environment() {
  local name="$1"
  [[ -n "${!name:-}" ]] || fail "${name} is required"
}

require_live_prerequisites() {
  require_nonempty_environment COS_REGION
  require_nonempty_environment COS_BUCKET
  require_nonempty_environment COS_SECRET_ID
  require_nonempty_environment COS_SECRET_KEY
  require_nonempty_environment COS_SECURITY_TOKEN

  command -v node >/dev/null 2>&1 ||
    fail 'Node.js is required for fail-closed lifecycle validation'

  if [[ -n "${PORTFOLIO_COS_LIFECYCLE_COMMAND:-}" ]]; then
    command -v "$PORTFOLIO_COS_LIFECYCLE_COMMAND" >/dev/null 2>&1 ||
      [[ -x "$PORTFOLIO_COS_LIFECYCLE_COMMAND" ]] ||
      fail 'PORTFOLIO_COS_LIFECYCLE_COMMAND is not executable'
    return
  fi

  command -v python3 >/dev/null 2>&1 ||
    fail 'python3 is required for the Tencent COS lifecycle operation'
  python3 -c 'import qcloud_cos' >/dev/null 2>&1 ||
    fail 'the official Tencent qcloud_cos Python SDK is required'
}

run_official_sdk() {
  local operation="$1"
  local input_file="${2:-}"

  python3 - "$operation" "$COS_REGION" "$COS_BUCKET" "$input_file" <<'PYTHON'
import json
import os
import sys

try:
    from qcloud_cos import CosConfig, CosS3Client
    from qcloud_cos.cos_exception import CosServiceError
except Exception:
    sys.exit(90)

operation, region, bucket, input_path = sys.argv[1:5]

try:
    credentials = {
        'Secret' + 'Id': os.environ['COS_SECRET_ID'],
        'Secret' + 'Key': os.environ['COS_SECRET_KEY'],
    }
    config = CosConfig(
        Region=region,
        Token=os.environ.get('COS_SECURITY_TOKEN') or None,
        Scheme='https',
        **credentials,
    )
    client = CosS3Client(config)

    if operation == 'get':
        try:
            response = client.get_bucket_lifecycle(Bucket=bucket)
        except CosServiceError as error:
            code = error.get_error_code()
            if code == 'NoSuchLifecycleConfiguration':
                response = {}
            else:
                raise
        rules = response.get('Rule', response.get('Rules', []))
        print(json.dumps({'Rules': rules}, separators=(',', ':')))
    elif operation == 'put':
        with open(input_path, encoding='utf-8') as stream:
            document = json.load(stream)
        client.put_bucket_lifecycle(
            Bucket=bucket,
            LifecycleConfiguration={'Rule': document['Rules']},
        )
    else:
        sys.exit(91)
except Exception:
    sys.exit(92)
PYTHON
}

run_lifecycle_command() {
  local operation="$1"
  local input_file="${2:-}"

  if [[ -n "${PORTFOLIO_COS_LIFECYCLE_COMMAND:-}" ]]; then
    if [[ "$operation" == 'get' ]]; then
      "$PORTFOLIO_COS_LIFECYCLE_COMMAND" get \
        --bucket "$COS_BUCKET" \
        --region "$COS_REGION"
    else
      "$PORTFOLIO_COS_LIFECYCLE_COMMAND" put \
        --bucket "$COS_BUCKET" \
        --region "$COS_REGION" \
        --input "$input_file"
    fi
    return
  fi

  run_official_sdk "$operation" "$input_file"
}

contains_sensitive_material() {
  local file="$1"

  node - "$file" <<'JAVASCRIPT'
const fs = require('fs');

const [filePath] = process.argv.slice(2);
let content;
try {
  content = fs.readFileSync(filePath, 'utf8');
} catch (_) {
  process.exit(0);
}

const secrets = [
  process.env.COS_SECRET_ID,
  process.env.COS_SECRET_KEY,
  process.env.COS_SECURITY_TOKEN,
].filter(value => typeof value === 'string' && value.length > 0);
const genericSecretPattern =
  /secret[\s_-]*(id|key)|security[\s_-]*token|authorization|credential|q-signature|x-cos-security-token/i;

process.exit(secrets.some(secret => content.includes(secret)) ||
  genericSecretPattern.test(content) ? 0 : 1);
JAVASCRIPT
}

read_remote_configuration() {
  local output_file="$1"
  local diagnostic_file="$2"

  if ! run_lifecycle_command get >"$output_file" 2>"$diagnostic_file"; then
    fail 'remote lifecycle read failed'
  fi
  if contains_sensitive_material "$output_file" ||
     contains_sensitive_material "$diagnostic_file"; then
    fail 'remote lifecycle command returned sensitive material'
  fi
  [[ -s "$output_file" ]] || fail 'remote lifecycle read returned no configuration'
}

apply_remote_configuration() {
  local input_file="$1"
  local output_file="$2"
  local diagnostic_file="$3"

  if ! run_lifecycle_command put "$input_file" >"$output_file" 2>"$diagnostic_file"; then
    fail 'remote lifecycle apply failed'
  fi
  if contains_sensitive_material "$output_file" ||
     contains_sensitive_material "$diagnostic_file"; then
    fail 'remote lifecycle command returned sensitive material'
  fi
}

prepare_configuration() {
  local current_file="$1"
  local merged_file="$2"
  local decision_file="$3"
  local diagnostic_file="$4"

  if ! node - "$current_file" "$RULE_FILE" "$merged_file" "$decision_file" \
      >"$WORK_DIRECTORY/validation.out" 2>"$diagnostic_file" <<'JAVASCRIPT'
const fs = require('fs');

const [currentPath, rulePath, mergedPath, decisionPath] = process.argv.slice(2);
const fail = () => process.exit(1);
const isObject = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const hasOnlyKeys = (value, keys) => {
  if (!isObject(value)) return false;
  const actual = Object.keys(value).sort();
  const expected = [...keys].sort();
  return actual.length === expected.length && actual.every((key, index) => key === expected[index]);
};
const isOneDay = value => value === 1 || value === '1';
const isExactTarget = rule =>
  hasOnlyKeys(rule, ['ID', 'Filter', 'Status', 'Expiration']) &&
  rule.ID === 'portfolio-staging-expire-1d' &&
  hasOnlyKeys(rule.Filter, ['Prefix']) &&
  rule.Filter.Prefix === 'staging/' &&
  rule.Status === 'Enabled' &&
  hasOnlyKeys(rule.Expiration, ['Days']) &&
  isOneDay(rule.Expiration.Days);
const filterPrefix = rule => {
  if (!Object.prototype.hasOwnProperty.call(rule, 'Filter')) fail();
  if (rule.Filter === '') return '';
  if (!isObject(rule.Filter)) fail();

  const selector = hasOnlyKeys(rule.Filter, ['And']) ? rule.Filter.And : rule.Filter;
  if (!isObject(selector)) fail();
  const allowedKeys = new Set([
    'Prefix',
    'PrefixNotEquals',
    'Tag',
    'ObjectSizeGreaterThan',
    'ObjectSizeLessThan',
  ]);
  const keys = Object.keys(selector);
  if (keys.some(key => !allowedKeys.has(key))) fail();
  if (!Object.prototype.hasOwnProperty.call(selector, 'Prefix')) fail();
  if (typeof selector.Prefix !== 'string') fail();
  if (keys.length > 1 && selector.Prefix.length === 0) fail();

  if (Object.prototype.hasOwnProperty.call(selector, 'PrefixNotEquals') &&
      (typeof selector.PrefixNotEquals !== 'string' || selector.PrefixNotEquals.length === 0)) fail();

  if (Object.prototype.hasOwnProperty.call(selector, 'Tag')) {
    const tags = Array.isArray(selector.Tag) ? selector.Tag : [selector.Tag];
    if (tags.length === 0) fail();
    for (const tag of tags) {
      if (!hasOnlyKeys(tag, ['Key', 'Value']) ||
          typeof tag.Key !== 'string' || typeof tag.Value !== 'string') fail();
    }
  }

  for (const key of ['ObjectSizeGreaterThan', 'ObjectSizeLessThan']) {
    if (!Object.prototype.hasOwnProperty.call(selector, key)) continue;
    const value = selector[key];
    const validNumber = Number.isSafeInteger(value) && value >= 0;
    const validString = typeof value === 'string' && /^(0|[1-9][0-9]*)$/.test(value);
    if (!validNumber && !validString) fail();
  }
  return selector.Prefix;
};
const overlaps = (left, right) => left.startsWith(right) || right.startsWith(left);
const protectedPrefixes = ['originals/', 'variants/', 'smoke/', 'backup/', 'backups/'];
const matchesProtectedContent = rulePrefix =>
  protectedPrefixes.some(protectedPrefix => overlaps(rulePrefix, protectedPrefix));

let current;
let target;
try {
  current = JSON.parse(fs.readFileSync(currentPath, 'utf8'));
  target = JSON.parse(fs.readFileSync(rulePath, 'utf8'));
} catch (_) {
  fail();
}
if (!isExactTarget(target) || !hasOnlyKeys(current, ['Rules']) || !Array.isArray(current.Rules)) fail();

let targetCount = 0;
for (const rule of current.Rules) {
  if (!isObject(rule) || !['Enabled', 'Disabled'].includes(rule.Status)) fail();
  const prefix = filterPrefix(rule);
  if (matchesProtectedContent(prefix)) fail();
  if (rule.ID === target.ID) {
    if (!isExactTarget(rule)) fail();
    targetCount += 1;
    continue;
  }
  if (overlaps(prefix, target.Filter.Prefix)) fail();
}
if (targetCount > 1) fail();
if (targetCount === 0 && current.Rules.length >= 1000) fail();

const rules = targetCount === 0 ? [...current.Rules, target] : current.Rules;
fs.writeFileSync(mergedPath, `${JSON.stringify({Rules: rules}, null, 2)}\n`, {encoding: 'utf8', mode: 0o600});
fs.writeFileSync(decisionPath, targetCount === 0 ? 'apply\n' : 'retain\n', {encoding: 'utf8', mode: 0o600});
JAVASCRIPT
  then
    fail 'remote lifecycle configuration is malformed or unsafe'
  fi
}

verify_snapshot_unchanged() {
  local initial_file="$1"
  local current_file="$2"
  local diagnostic_file="$3"

  if ! node - "$initial_file" "$current_file" \
      >"$WORK_DIRECTORY/pre-apply-validation.out" 2>"$diagnostic_file" <<'JAVASCRIPT'
const fs = require('fs');

const [initialPath, currentPath] = process.argv.slice(2);
const fail = () => process.exit(1);
const isObject = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const hasOnlyRules = value =>
  isObject(value) && Object.keys(value).length === 1 && Array.isArray(value.Rules);
const numericFields = new Set(['Days', 'NoncurrentDays', 'DaysAfterInitiation']);
const canonicalize = (value, fieldName = '') => {
  if (Array.isArray(value)) return value.map(item => canonicalize(item));
  if (isObject(value)) {
    const result = {};
    for (const key of Object.keys(value).sort()) {
      result[key] = canonicalize(value[key], key);
    }
    return result;
  }
  if (numericFields.has(fieldName) && typeof value === 'string' && /^(0|[1-9][0-9]*)$/.test(value)) {
    const number = Number(value);
    if (Number.isSafeInteger(number)) return number;
  }
  return value;
};
const canonicalRuleSet = document =>
  document.Rules
    .map(rule => JSON.stringify(canonicalize(rule)))
    .sort();

let initial;
let current;
try {
  initial = JSON.parse(fs.readFileSync(initialPath, 'utf8'));
  current = JSON.parse(fs.readFileSync(currentPath, 'utf8'));
} catch (_) {
  fail();
}
if (!hasOnlyRules(initial) || !hasOnlyRules(current)) fail();
if (JSON.stringify(canonicalRuleSet(initial)) !== JSON.stringify(canonicalRuleSet(current))) fail();
JAVASCRIPT
  then
    fail 'remote lifecycle configuration changed before apply'
  fi
}

verify_readback() {
  local configuration_file="$1"
  local expected_file="$2"
  local diagnostic_file="$3"

  if ! node - "$configuration_file" "$expected_file" >"$WORK_DIRECTORY/readback-validation.out" 2>"$diagnostic_file" <<'JAVASCRIPT'
const fs = require('fs');

const [configurationPath, expectedPath] = process.argv.slice(2);
const fail = () => process.exit(1);
const isObject = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const hasOnlyKeys = (value, keys) => {
  if (!isObject(value)) return false;
  const actual = Object.keys(value).sort();
  const expected = [...keys].sort();
  return actual.length === expected.length && actual.every((key, index) => key === expected[index]);
};
const isOneDay = value => value === 1 || value === '1';
const isExactTarget = rule =>
  hasOnlyKeys(rule, ['ID', 'Filter', 'Status', 'Expiration']) &&
  rule.ID === 'portfolio-staging-expire-1d' &&
  hasOnlyKeys(rule.Filter, ['Prefix']) &&
  rule.Filter.Prefix === 'staging/' &&
  rule.Status === 'Enabled' &&
  hasOnlyKeys(rule.Expiration, ['Days']) &&
  isOneDay(rule.Expiration.Days);
const filterPrefix = rule => {
  if (!Object.prototype.hasOwnProperty.call(rule, 'Filter')) fail();
  if (rule.Filter === '') return '';
  if (!isObject(rule.Filter)) fail();

  const selector = hasOnlyKeys(rule.Filter, ['And']) ? rule.Filter.And : rule.Filter;
  if (!isObject(selector)) fail();
  const allowedKeys = new Set([
    'Prefix',
    'PrefixNotEquals',
    'Tag',
    'ObjectSizeGreaterThan',
    'ObjectSizeLessThan',
  ]);
  const keys = Object.keys(selector);
  if (keys.some(key => !allowedKeys.has(key))) fail();
  if (!Object.prototype.hasOwnProperty.call(selector, 'Prefix')) fail();
  if (typeof selector.Prefix !== 'string') fail();
  if (keys.length > 1 && selector.Prefix.length === 0) fail();

  if (Object.prototype.hasOwnProperty.call(selector, 'PrefixNotEquals') &&
      (typeof selector.PrefixNotEquals !== 'string' || selector.PrefixNotEquals.length === 0)) fail();

  if (Object.prototype.hasOwnProperty.call(selector, 'Tag')) {
    const tags = Array.isArray(selector.Tag) ? selector.Tag : [selector.Tag];
    if (tags.length === 0) fail();
    for (const tag of tags) {
      if (!hasOnlyKeys(tag, ['Key', 'Value']) ||
          typeof tag.Key !== 'string' || typeof tag.Value !== 'string') fail();
    }
  }

  for (const key of ['ObjectSizeGreaterThan', 'ObjectSizeLessThan']) {
    if (!Object.prototype.hasOwnProperty.call(selector, key)) continue;
    const value = selector[key];
    const validNumber = Number.isSafeInteger(value) && value >= 0;
    const validString = typeof value === 'string' && /^(0|[1-9][0-9]*)$/.test(value);
    if (!validNumber && !validString) fail();
  }
  return selector.Prefix;
};
const overlaps = (left, right) => left.startsWith(right) || right.startsWith(left);
const protectedPrefixes = ['originals/', 'variants/', 'smoke/', 'backup/', 'backups/'];
const matchesProtectedContent = rulePrefix =>
  protectedPrefixes.some(protectedPrefix => overlaps(rulePrefix, protectedPrefix));
const numericFields = new Set(['Days', 'NoncurrentDays', 'DaysAfterInitiation']);
const canonicalize = (value, fieldName = '') => {
  if (Array.isArray(value)) return value.map(item => canonicalize(item));
  if (isObject(value)) {
    const result = {};
    for (const key of Object.keys(value).sort()) {
      result[key] = canonicalize(value[key], key);
    }
    return result;
  }
  if (numericFields.has(fieldName) && typeof value === 'string' && /^(0|[1-9][0-9]*)$/.test(value)) {
    const number = Number(value);
    if (Number.isSafeInteger(number)) return number;
  }
  return value;
};
const canonicalRuleSet = document =>
  document.Rules
    .map(rule => JSON.stringify(canonicalize(rule)))
    .sort();

let document;
let expected;
try {
  document = JSON.parse(fs.readFileSync(configurationPath, 'utf8'));
  expected = JSON.parse(fs.readFileSync(expectedPath, 'utf8'));
} catch (_) {
  fail();
}
if (!hasOnlyKeys(document, ['Rules']) || !Array.isArray(document.Rules)) fail();
if (!hasOnlyKeys(expected, ['Rules']) || !Array.isArray(expected.Rules)) fail();

let targetCount = 0;
for (const rule of document.Rules) {
  if (!isObject(rule) || !['Enabled', 'Disabled'].includes(rule.Status)) fail();
  const prefix = filterPrefix(rule);
  if (matchesProtectedContent(prefix)) fail();
  if (rule.ID === 'portfolio-staging-expire-1d') {
    if (!isExactTarget(rule)) fail();
    targetCount += 1;
    continue;
  }
  if (overlaps(prefix, 'staging/')) fail();
}
if (targetCount !== 1) fail();
if (JSON.stringify(canonicalRuleSet(document)) !== JSON.stringify(canonicalRuleSet(expected))) fail();
JAVASCRIPT
  then
    fail 'read-back did not prove the exact safe lifecycle configuration'
  fi
}

main() {
  if (($# != 1)) || [[ "$1" != '--apply' ]]; then
    fail 'explicit --apply is required; no action was taken'
  fi

  require_live_prerequisites
  [[ -f "$RULE_FILE" ]] || fail 'repository lifecycle rule is missing'

  WORK_DIRECTORY="$(mktemp -d)"
  local current_file="$WORK_DIRECTORY/current.json"
  local merged_file="$WORK_DIRECTORY/merged.json"
  local decision_file="$WORK_DIRECTORY/decision"
  local preapply_file="$WORK_DIRECTORY/preapply.json"
  local readback_file="$WORK_DIRECTORY/readback.json"

  read_remote_configuration \
    "$current_file" \
    "$WORK_DIRECTORY/read-current.err"
  prepare_configuration \
    "$current_file" \
    "$merged_file" \
    "$decision_file" \
    "$WORK_DIRECTORY/prepare.err"

  case "$(<"$decision_file")" in
    apply)
      read_remote_configuration \
        "$preapply_file" \
        "$WORK_DIRECTORY/read-preapply.err"
      verify_snapshot_unchanged \
        "$current_file" \
        "$preapply_file" \
        "$WORK_DIRECTORY/verify-preapply.err"
      apply_remote_configuration \
        "$merged_file" \
        "$WORK_DIRECTORY/apply.out" \
        "$WORK_DIRECTORY/apply.err"
      ;;
    retain)
      ;;
    *)
      fail 'lifecycle merge did not produce a safe decision'
      ;;
  esac

  read_remote_configuration \
    "$readback_file" \
    "$WORK_DIRECTORY/readback.err"
  verify_readback \
    "$readback_file" \
    "$merged_file" \
    "$WORK_DIRECTORY/verify-readback.err"

  printf '%s\n' "COS staging lifecycle rule ${TARGET_RULE_ID} is installed and verified"
}

main "$@"
