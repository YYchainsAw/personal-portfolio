#!/usr/bin/env bash
set -euo pipefail
set +x
umask 077

readonly TARGET_RULE_ID='portfolio-staging-expire-1d'

WORK_DIRECTORY=''

fail() {
  printf 'COS staging lifecycle verification failed: %s\n' "$1" >&2
  exit 1
}

cleanup() {
  if [[ -n "$WORK_DIRECTORY" && -d "$WORK_DIRECTORY" ]]; then
    rm -rf -- "$WORK_DIRECTORY"
  fi
}
on_signal() {
  exit 130
}
trap cleanup EXIT
trap on_signal HUP INT TERM

require_nonempty_environment() {
  local name="$1"
  [[ -n "${!name:-}" ]] || fail "${name} is required"
}

require_live_prerequisites() {
  require_nonempty_environment COS_REGION
  require_nonempty_environment COS_BUCKET
  require_nonempty_environment COS_SECRET_ID
  require_nonempty_environment COS_SECRET_KEY

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
  python3 - "$COS_REGION" "$COS_BUCKET" <<'PYTHON'
import json
import os
import sys

try:
    from qcloud_cos import CosConfig, CosS3Client
    from qcloud_cos.cos_exception import CosServiceError
except Exception:
    sys.exit(90)

region, bucket = sys.argv[1:3]

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
except Exception:
    sys.exit(92)
PYTHON
}

run_lifecycle_command() {
  if [[ -n "${PORTFOLIO_COS_LIFECYCLE_COMMAND:-}" ]]; then
    "$PORTFOLIO_COS_LIFECYCLE_COMMAND" get \
      --bucket "$COS_BUCKET" \
      --region "$COS_REGION"
    return
  fi

  run_official_sdk
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

  if ! run_lifecycle_command >"$output_file" 2>"$diagnostic_file"; then
    fail 'remote lifecycle read failed'
  fi
  if contains_sensitive_material "$output_file" ||
     contains_sensitive_material "$diagnostic_file"; then
    fail 'remote lifecycle command returned sensitive material'
  fi
  [[ -s "$output_file" ]] || fail 'remote lifecycle read returned no configuration'
}

verify_configuration() {
  local configuration_file="$1"
  local diagnostic_file="$2"

  if ! node - "$configuration_file" >"$WORK_DIRECTORY/validation.out" 2>"$diagnostic_file" <<'JAVASCRIPT'
const fs = require('fs');

const [configurationPath] = process.argv.slice(2);
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

let document;
try {
  document = JSON.parse(fs.readFileSync(configurationPath, 'utf8'));
} catch (_) {
  fail();
}
if (!hasOnlyKeys(document, ['Rules']) || !Array.isArray(document.Rules)) fail();

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
JAVASCRIPT
  then
    fail 'exact enabled staging-only one-day rule was not proven'
  fi
}

main() {
  if (($# != 1)) || [[ "$1" != '--check-live' ]]; then
    fail 'explicit --check-live is required; no live check was made'
  fi

  require_live_prerequisites
  WORK_DIRECTORY="$(mktemp -d)"

  local configuration_file="$WORK_DIRECTORY/current.json"
  read_remote_configuration \
    "$configuration_file" \
    "$WORK_DIRECTORY/read.err"
  verify_configuration \
    "$configuration_file" \
    "$WORK_DIRECTORY/verify.err"

  printf '%s\n' "COS staging lifecycle rule ${TARGET_RULE_ID} is live and safe"
}

main "$@"
