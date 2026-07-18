#!/usr/bin/env bash
# shellcheck disable=SC2016
set -euo pipefail
set +x
umask 077
export LC_ALL=C

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
readonly REPOSITORY_ROOT
readonly RESTORE_DIRECTORY="$REPOSITORY_ROOT/deploy/restore"
readonly DRILL="$RESTORE_DIRECTORY/restore-drill.sh"
readonly RESTORE_COMPOSE_YAML="$RESTORE_DIRECTORY/docker-compose.restore.yml"
readonly CLOSURE_SQL="$RESTORE_DIRECTORY/resolve-media-closure.sql"
readonly MEDIA_VERIFY="$RESTORE_DIRECTORY/verify-restored-media.sh"
readonly REMINDER_SERVICE="$REPOSITORY_ROOT/deploy/systemd/portfolio-restore-reminder.service"
readonly REMINDER_TIMER="$REPOSITORY_ROOT/deploy/systemd/portfolio-restore-reminder.timer"
readonly REMINDER_INSTALLER="$RESTORE_DIRECTORY/install-reminder.sh"
readonly IMAGE_LOCK_FILE="$REPOSITORY_ROOT/deploy/image-lock.env"
readonly IMAGE_LOCK_LIB="$REPOSITORY_ROOT/deploy/scripts/image-lock-lib.sh"
readonly DRILL_ID='44444444-4444-4444-8444-444444444444'
readonly FAILURE_DRILL_ID='66666666-6666-4666-8666-666666666666'
readonly REPORT_FAILURE_DRILL_ID='88888888-8888-4888-8888-888888888888'
readonly OVERRIDE_DRILL_ID='77777777-7777-4777-8777-777777777777'
readonly DD_FAILURE_DRILL_ID='99999999-9999-4999-8999-999999999999'
readonly CHMOD_FAILURE_DRILL_ID='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
readonly EARLY_FAILURE_DRILL_ID='bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
readonly SEMANTIC_FAILURE_DRILL_ID='cccccccc-cccc-4ccc-8ccc-cccccccccccc'
readonly REDIRECT_FAILURE_DRILL_ID='dddddddd-dddd-4ddd-8ddd-dddddddddddd'
readonly MISSING_BLOB_DRILL_ID='eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee'
readonly EXTRA_BLOB_DRILL_ID='ffffffff-ffff-4fff-8fff-ffffffffffff'
readonly RPO_FAILURE_DRILL_ID='12121212-1212-4121-8121-121212121212'
readonly HISTORICAL_REVISION='33333333-3333-4333-8333-333333333333'
readonly PROJECT_ID='55555555-5555-4555-8555-555555555555'
readonly LOCAL_ASSET='11111111-1111-4111-8111-111111111111'
readonly COS_ASSET='22222222-2222-4222-8222-222222222222'
readonly UNSELECTED_COS_ASSET='abababab-abab-4bab-8bab-abababababab'
readonly UNSELECTED_COS_VARIANT='bcbcbcbc-bcbc-4cbc-8cbc-bcbcbcbcbcbc'
readonly SET_ID='20260718T000000Z-abcdef123456'
REAL_DOCKER="$(command -v docker 2>/dev/null || true)"
readonly REAL_DOCKER

WORK_DIRECTORY=''
PG_CONTAINER=''
NGINX_CONTAINER=''

fail() {
  printf 'Restore safety contract failed: %s\n' "$1" >&2
  exit 1
}

cleanup() {
  set +e
  [[ -z "$PG_CONTAINER" || -z "$REAL_DOCKER" ]] || "$REAL_DOCKER" rm -f "$PG_CONTAINER" >/dev/null 2>&1
  [[ -z "$NGINX_CONTAINER" || -z "$REAL_DOCKER" ]] || "$REAL_DOCKER" rm -f "$NGINX_CONTAINER" >/dev/null 2>&1
  [[ -z "$WORK_DIRECTORY" || ! -d "$WORK_DIRECTORY" ]] || rm -rf -- "$WORK_DIRECTORY"
  rm -rf -- \
    "/srv/portfolio-restore/contract-$DRILL_ID" \
    "/srv/portfolio-restore/contract-$FAILURE_DRILL_ID" \
    "/srv/portfolio-restore/contract-$REPORT_FAILURE_DRILL_ID" \
    "/srv/portfolio-restore/contract-$OVERRIDE_DRILL_ID" \
    "/srv/portfolio-restore/contract-$DD_FAILURE_DRILL_ID" \
    "/srv/portfolio-restore/contract-$CHMOD_FAILURE_DRILL_ID" \
    "/srv/portfolio-restore/contract-$EARLY_FAILURE_DRILL_ID" \
    "/srv/portfolio-restore/contract-$SEMANTIC_FAILURE_DRILL_ID" \
    "/srv/portfolio-restore/contract-$REDIRECT_FAILURE_DRILL_ID" \
    "/srv/portfolio-restore/contract-$MISSING_BLOB_DRILL_ID" \
    "/srv/portfolio-restore/contract-$EXTRA_BLOB_DRILL_ID" \
    "/srv/portfolio-restore/contract-$RPO_FAILURE_DRILL_ID" \
    "/run/portfolio-restore-secrets/$DRILL_ID" \
    "/run/portfolio-restore-secrets/$FAILURE_DRILL_ID" 2>/dev/null
  rm -rf -- "/run/portfolio-restore-secrets/$REPORT_FAILURE_DRILL_ID" 2>/dev/null
  rm -rf -- "/run/portfolio-restore-secrets/$OVERRIDE_DRILL_ID" 2>/dev/null
  rm -rf -- "/run/portfolio-restore-secrets/$DD_FAILURE_DRILL_ID" 2>/dev/null
  rm -rf -- "/run/portfolio-restore-secrets/$CHMOD_FAILURE_DRILL_ID" 2>/dev/null
  rm -rf -- "/run/portfolio-restore-secrets/$EARLY_FAILURE_DRILL_ID" 2>/dev/null
  rm -rf -- "/run/portfolio-restore-secrets/$SEMANTIC_FAILURE_DRILL_ID" 2>/dev/null
  rm -rf -- "/run/portfolio-restore-secrets/$REDIRECT_FAILURE_DRILL_ID" 2>/dev/null
  rm -rf -- "/run/portfolio-restore-secrets/$MISSING_BLOB_DRILL_ID" 2>/dev/null
  rm -rf -- "/run/portfolio-restore-secrets/$EXTRA_BLOB_DRILL_ID" 2>/dev/null
  rm -rf -- "/run/portfolio-restore-secrets/$RPO_FAILURE_DRILL_ID" 2>/dev/null
}
trap cleanup EXIT HUP INT TERM

assert_contains() {
  grep -F -- "$2" "$1" >/dev/null || fail "$(basename "$1") does not contain: $2"
}

assert_not_contains() {
  if grep -F -- "$2" "$1" >/dev/null; then
    fail "$(basename "$1") unexpectedly contains: $2"
  fi
}

expect_failure() {
  local label="$1" expected="$2"; shift 2
  local output="$WORK_DIRECTORY/failure-$label.log"
  if "$@" >"$output" 2>&1; then
    fail "$label unexpectedly succeeded"
  fi
  if ! grep -F -- "$expected" "$output" >/dev/null; then
    sed -n '1,160p' "$output" >&2
    fail "failure-$label.log does not contain: $expected"
  fi
}

for command_name in awk base64 cmp date docker find findmnt jq nginx openssl python3 \
  realpath sed sha256sum shellcheck stat systemd-analyze tar zstd; do
  command -v "$command_name" >/dev/null 2>&1 || fail "$command_name is required"
done
for required in "$DRILL" "$RESTORE_COMPOSE_YAML" "$CLOSURE_SQL" "$MEDIA_VERIFY" \
  "$REMINDER_SERVICE" "$REMINDER_TIMER" "$RESTORE_DIRECTORY/README.md" \
  "$REMINDER_INSTALLER" "$IMAGE_LOCK_FILE" "$IMAGE_LOCK_LIB" \
  "$RESTORE_DIRECTORY"/adapters/*.sh; do
  [[ -f "$required" ]] || fail "required restore artifact is missing: $required"
done
# shellcheck source=deploy/scripts/image-lock-lib.sh
source "$IMAGE_LOCK_LIB"
# Accessed through the image-lock helper's nameref.
# shellcheck disable=SC2034
declare -A restore_test_image_lock=()
portfolio_load_image_lock "$IMAGE_LOCK_FILE" restore_test_image_lock ||
  fail 'source-pinned image lock is invalid'
readonly POSTGRES_TEST_IMAGE="${restore_test_image_lock[POSTGRES_IMAGE]}"

# Static invariants make accidental adapter replacement and unsafe topology
# changes fail even before the executable fixture starts.
assert_contains "$DRILL" 'DEFAULT_BACKUP_VERIFY_COMMAND="$ADAPTER_DIRECTORY/acquire-backup-set.sh"'
assert_contains "$DRILL" 'DEFAULT_RELEASE_VERIFY_COMMAND="$ADAPTER_DIRECTORY/verify-release-bundle.sh"'
assert_contains "$DRILL" 'DEFAULT_COS_TRANSFER_COMMAND="$ADAPTER_DIRECTORY/cos-transfer.sh"'
assert_contains "$DRILL" 'DEFAULT_APPLY_MEDIA_MAPPING_COMMAND="$ADAPTER_DIRECTORY/apply-media-mapping.sh"'
assert_contains "$DRILL" 'DEFAULT_ROUTE_START_COMMAND="$ADAPTER_DIRECTORY/start-routes.sh"'
assert_contains "$DRILL" 'DEFAULT_CONTENT_VERIFY_COMMAND="$ADAPTER_DIRECTORY/verify-content-flow.sh"'
assert_contains "$DRILL" 'DEFAULT_REPORT_COMMAND="$ADAPTER_DIRECTORY/report-transfer.sh"'
assert_contains "$DRILL" 'DEFAULT_REVOKE_COMMAND="$ADAPTER_DIRECTORY/revoke-drill-credentials.sh"'
assert_contains "$DRILL" 'DEFAULT_DRILL_CLEANUP_COMMAND="$ADAPTER_DIRECTORY/cleanup-drill.sh"'
assert_contains "$DRILL" 'cannot replace the tracked production adapter'
assert_contains "$RESTORE_DIRECTORY/adapters/verify-release-bundle.sh" 'source "$IMAGE_LOCK_LIB"'
assert_contains "$RESTORE_DIRECTORY/adapters/verify-release-bundle.sh" 'portfolio_load_image_lock "$release/ops/deploy/image-lock.env" release_locked_images'
assert_contains "$RESTORE_DIRECTORY/adapters/verify-release-bundle.sh" 'portfolio_require_release_image_lock "$release/release.json" release_locked_images'
assert_not_contains "$RESTORE_DIRECTORY/adapters/verify-release-bundle.sh" 'source "$release/ops/deploy/scripts/image-lock-lib.sh"'
assert_contains "$DRILL" 'SECRETS_DIRECTORY="/run/portfolio-restore-secrets/$DRILL_ID"'
assert_contains "$DRILL" 'jdbc:postgresql://postgres:5432/$database'
assert_contains "$DRILL" 'production, backup, and drill COS locations must all be distinct'
assert_contains "$DRILL" 'dispose_identity'
assert_contains "$DRILL" 'if ! "$AGE_COMMAND" --decrypt'
assert_contains "$DRILL" 'trap on_exit EXIT'
assert_contains "$DRILL" 'local status="$1"'
assert_contains "$DRILL" 'trap - HUP INT TERM'
assert_contains "$DRILL" 'ERROR_CATEGORY=INTERRUPTED'
assert_contains "$DRILL" "trap 'on_signal 129' HUP"
assert_contains "$DRILL" "trap 'on_signal 130' INT"
assert_contains "$DRILL" "trap 'on_signal 143' TERM"
assert_contains "$DRILL" 'compose --project-name "$RESTORE_COMPOSE_PROJECT_NAME"'
assert_contains "$DRILL" 'up -d --wait --wait-timeout 180 restore-postgres'
assert_contains "$DRILL" 'caller-supplied RCLONE_* environment is forbidden'
assert_contains "$RESTORE_DIRECTORY/record-drill-result.sh" 'current-release'
assert_contains "$RESTORE_DIRECTORY/record-drill-result.sh" 'release_root/ops/deploy/docker-compose.prod.yml'
assert_contains "$RESTORE_DIRECTORY/record-drill-result.sh" '--project-name portfolio'
assert_contains "$RESTORE_DIRECTORY/adapters/cleanup-drill.sh" 'quiet_flag=-aq'
assert_contains "$RESTORE_DIRECTORY/adapters/start-routes.sh" 'application/javascript js mjs;'
assert_contains "$RESTORE_DIRECTORY/adapters/start-routes.sh" '(.method == "RECOVERY_CODE")'
assert_contains "$RESTORE_DIRECTORY/adapters/apply-media-mapping.sh" "quarantine/assets/"
assert_contains "$RESTORE_DIRECTORY/adapters/apply-media-mapping.sh" "forbidden_production_bucket"
assert_contains "$RESTORE_DIRECTORY/adapters/apply-media-mapping.sh" "FROM portfolio.media_variant AS variant"
assert_contains "$RESTORE_COMPOSE_YAML" 'pull_policy: never'
[[ "$(grep -Fc 'pull_policy: never' "$RESTORE_COMPOSE_YAML")" -eq 3 ]] ||
  fail 'all three restore images must be offline-only'
[[ "$(grep -Fc 'com.yychainsaw.portfolio.restore.drill:' "$RESTORE_COMPOSE_YAML")" -eq 7 ]] ||
  fail 'services, volumes, and networks are not all drill-labelled'
assert_not_contains "$RESTORE_COMPOSE_YAML" '/opt/portfolio'
assert_not_contains "$RESTORE_COMPOSE_YAML" '/etc/portfolio'
assert_not_contains "$RESTORE_COMPOSE_YAML" '/www/'
assert_contains "$CLOSURE_SQL" "THEN 'true' ELSE 'false'"
assert_contains "$REMINDER_TIMER" 'OnCalendar=Mon *-01,04,07,10-01..07 01:00:00 UTC'
assert_contains "$REMINDER_TIMER" 'Persistent=true'
assert_contains "$REMINDER_SERVICE" 'Documentation=file:/opt/portfolio/current-ops/docs/operations/production-runbook.md'
assert_contains "$RESTORE_DIRECTORY/README.md" 'sudo /opt/portfolio/current-ops/deploy/restore/install-reminder.sh'
assert_contains "$RESTORE_DIRECTORY/README.md" 'docs/operations/backup-recovery.md'
assert_contains "$RESTORE_DIRECTORY/README.md" 'real Tencent backup/readback credentials'

bash -n "$RESTORE_DIRECTORY"/*.sh "$RESTORE_DIRECTORY"/adapters/*.sh
shellcheck -x --source-path="$REPOSITORY_ROOT" \
  "$RESTORE_DIRECTORY"/*.sh "$RESTORE_DIRECTORY"/adapters/*.sh

[[ "$(findmnt -T /run -n -o FSTYPE)" == tmpfs ]] ||
  fail 'run this contract in an isolated container with --tmpfs /run'
[[ -f /.dockerenv ]] || fail 'this destructive-path fixture must run inside an isolated container'
mkdir -p /srv/portfolio-restore /run/portfolio-restore-secrets
WORK_DIRECTORY="$(mktemp -d)"
readonly WORK_DIRECTORY
BIN="$WORK_DIRECTORY/bin"
REMOTE="$WORK_DIRECTORY/remote"
STATE="$WORK_DIRECTORY/state"
FIXTURE="$WORK_DIRECTORY/fixture"
mkdir -p "$BIN" "$REMOTE" "$STATE/docker-images" "$FIXTURE/local-source/originals" \
  "$FIXTURE/local-source/variants"
COMPLIANCE_STUB="$WORK_DIRECTORY/verify-compliance-fixture.py"
cat >"$COMPLIANCE_STUB" <<'PY'
#!/usr/bin/env python3
import argparse
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("--tree", required=True)
parser.add_argument("--release-json", required=True)
options = parser.parse_args()
if not Path(options.tree).is_dir() or not Path(options.release_json).is_file():
    raise SystemExit(1)
PY
chmod 0600 "$COMPLIANCE_STUB"
COMMAND_LOG="$WORK_DIRECTORY/commands.log"
AGE_COUNT="$STATE/age-count"
MALICIOUS_HOME="$WORK_DIRECTORY/malicious-home"
mkdir -p "$MALICIOUS_HOME"
cat >"$MALICIOUS_HOME/.curlrc" <<'CURLRC'
insecure
location
connect-to = "restore.test.invalid::prod-media-1250000000.cos.ap-guangzhou.myqcloud.com:"
CURLRC
: >"$COMMAND_LOG"
printf '0\n' >"$AGE_COUNT"

cat >"$BIN/rclone" <<'RCLONE'
#!/usr/bin/env bash
set -euo pipefail
assert_disposed() {
  if [[ "${RESTORE_IDENTITY_DISPOSED:-false}" == true ]]; then
    [[ ! -e "${RESTORE_SECRETS_DIRECTORY:-/nonexistent}/age-identity" ]]
    [[ ! -e "/proc/self/fd/${RESTORE_TEST_IDENTITY_FD:-9}" ]]
  fi
}
map_path() {
  local value="$1"
  if [[ "$value" =~ ^([A-Za-z0-9_.-]+):([^/]+)(/(.*))?$ ]]; then
    printf '%s/%s/%s/%s\n' "$RESTORE_TEST_REMOTE" \
      "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}" "${BASH_REMATCH[4]:-}"
  else
    printf '%s\n' "$value"
  fi
}
[[ "$1" == --config && -f "$2" ]]; config="$2"; shift 2
operation="$1"; shift
printf 'rclone:%s:%s:%s\n' "$(basename "$config")" "$operation" "$*" >>"$RESTORE_TEST_COMMAND_LOG"
assert_disposed
if [[ -n "${RESTORE_TEST_RCLONE_FAIL_MATCH:-}" && "$(basename "$config")" == backup-verifier.rclone.conf &&
      "$*" == *"$RESTORE_TEST_RCLONE_FAIL_MATCH"* ]]; then
  exit 1
fi
case "$operation" in
  copyto)
    immutable=false
    positional=()
    while (($#)); do
      case "$1" in
        --immutable) immutable=true; shift ;;
        --) shift ;;
        --retries|--low-level-retries|--contimeout|--timeout) shift 2 ;;
        --*) shift ;;
        *) positional+=("$1"); shift ;;
      esac
    done
    ((${#positional[@]} == 2))
    source_path="$(map_path "${positional[0]}")"
    destination_path="$(map_path "${positional[1]}")"
    [[ -f "$source_path" ]]
    if [[ "$immutable" == true && -e "$destination_path" ]]; then exit 1; fi
    mkdir -p -- "$(dirname -- "$destination_path")"
    cp -- "$source_path" "$destination_path"
    ;;
  purge)
    remote=''
    while (($#)); do
      case "$1" in --retries|--low-level-retries|--contimeout|--timeout) shift 2 ;; --*) shift ;; *) remote="$1"; shift ;; esac
    done
    target="$(map_path "$remote")"
    rm -rf -- "$target"
    ;;
  lsf)
    remote="$1"
    target="$(map_path "$remote")"
    [[ ! -d "$target" ]] || find "$target" -mindepth 1 -maxdepth 1 -printf '%f\n'
    ;;
  *) exit 64 ;;
esac
RCLONE

cat >"$BIN/age" <<'AGE'
#!/usr/bin/env bash
set -euo pipefail
output='' identity='' input=''
while (($#)); do
  case "$1" in
    --decrypt) shift ;;
    --identity|-i) identity="$2"; shift 2 ;;
    --output|-o) output="$2"; shift 2 ;;
    *) input="$1"; shift ;;
  esac
done
[[ "$identity" == "$RESTORE_SECRETS_DIRECTORY/age-identity" && -f "$identity" ]]
[[ ! -e "/proc/self/fd/${RESTORE_TEST_IDENTITY_FD:-9}" ]]
count="$(( $(<"$RESTORE_TEST_AGE_COUNT") + 1 ))"
printf '%s\n' "$count" >"$RESTORE_TEST_AGE_COUNT"
printf 'age:%s\n' "$count" >>"$RESTORE_TEST_COMMAND_LOG"
if [[ "${RESTORE_TEST_AGE_FAIL_CALL:-0}" == "$count" ]]; then exit 1; fi
cp -- "$input" "$output"
AGE

cat >"$BIN/docker" <<'DOCKER'
#!/usr/bin/env bash
set -euo pipefail
assert_disposed() {
  if [[ "${RESTORE_IDENTITY_DISPOSED:-false}" == true ]]; then
    [[ ! -e "${RESTORE_SECRETS_DIRECTORY:-/nonexistent}/age-identity" ]]
    [[ ! -e "/proc/self/fd/${RESTORE_TEST_IDENTITY_FD:-9}" ]]
  fi
}
image_key() { printf '%s' "$1" | sha256sum | awk '{print $1}'; }
printf 'docker:%s:%s\n' "${PORTFOLIO_RESTORE_PHASE:-none}" "$*" >>"$RESTORE_TEST_COMMAND_LOG"
assert_disposed
case "$1" in
  image)
    [[ "$2" == inspect ]]
    image="$3"
    [[ "$image" == sha256:* ]] || [[ -f "$RESTORE_TEST_DOCKER_STATE/$(image_key "$image").tar" ]]
    ;;
  load)
    archive="$(mktemp)"; trap 'rm -f "$archive"' EXIT
    cat >"$archive"
    tag="$(tar -xOf "$archive" manifest.json | jq -er '.[0].RepoTags[0]')"
    cp -- "$archive" "$RESTORE_TEST_DOCKER_STATE/$(image_key "$tag").tar"
    ;;
  save)
    cat "$RESTORE_TEST_DOCKER_STATE/$(image_key "$2").tar"
    ;;
  compose)
    joined=" $* "
    if [[ "$joined" == *' exec -T postgres '* ]]; then
      [[ "$joined" == *' --project-name portfolio '* ]]
    else
      [[ "$joined" == *" --project-name portfolio-restore-$RESTORE_DRILL_ID "* ]]
    fi
    if [[ "$joined" == *' up -d '* && "$joined" == *' restore-postgres '* ]]; then
      [[ "$joined" == *' --wait '* && "$joined" == *' --wait-timeout 180 '* ]]
      printf 'postgres-ready-wait\n' >>"$RESTORE_TEST_COMMAND_LOG"
    fi
    if [[ "$joined" != *' exec '* ]]; then exit 0; fi
    phase="${PORTFOLIO_RESTORE_PHASE:-none}"
    case "$phase" in
      deny-create|deny-alter|deny-drop) cat >/dev/null; exit 1 ;;
      resolve-closure) cat >/dev/null; cat "$RESTORE_TEST_CLOSURE" ;;
      mapping-apply)
        sql="$(cat)"
        grep -F 'CREATE TEMP TABLE restore_media_mapping' <<<"$sql" >/dev/null
        grep -F 'UPDATE portfolio.media_asset' <<<"$sql" >/dev/null
        grep -F "quarantine/assets/" <<<"$sql" >/dev/null
        grep -F "drills/$RESTORE_DRILL_ID/blobs/" <<<"$sql" >/dev/null
        printf 'mapping-sql:apply\n' >>"$RESTORE_TEST_COMMAND_LOG"
        ;;
      mapping-verify)
        sql="$(cat)"
        grep -F 'restored_mapping_matches_database' <<<"$sql" >/dev/null
        grep -F 'forbidden_production_bucket' <<<"$sql" >/dev/null
        grep -F 'FROM portfolio.media_variant AS variant' <<<"$sql" >/dev/null
        printf 'mapping-sql:verify\n' >>"$RESTORE_TEST_COMMAND_LOG"
        ;;
      content-plan)
        cat >/dev/null
        printf '%s|PROJECT|%s|7|77777777-7777-4777-8777-777777777777\n' \
          "$RESTORE_TEST_HISTORICAL_REVISION" "$RESTORE_TEST_PROJECT_ID"
        ;;
      content-revision-references)
        cat >/dev/null
        printf '%s|public|GALLERY|LOCAL\n%s|history|GALLERY|TENCENT_COS\n' \
          "$RESTORE_TEST_LOCAL_ASSET" "$RESTORE_TEST_COS_ASSET"
        ;;
      content-revision-snapshot)
        cat >/dev/null
        jq -n -cS --arg project "$RESTORE_TEST_PROJECT_ID" \
          --arg local "$RESTORE_TEST_LOCAL_ASSET" --arg cos "$RESTORE_TEST_COS_ASSET" \
          '{schemaVersion:1,projectId:$project,externalKey:"contract",slug:"contract",number:"01",sortOrder:0,
            featured:true,translations:{},tags:[],skills:[],
            projectMedia:[{assetId:$local,usage:"DETAIL",sortOrder:0,layout:"wide",
              objectPosition:"50% 50%",credit:"contract",sourceUrl:"https://example.invalid/local"}],
            blocks:[{type:"GALLERY",sortOrder:0,payload:{mediaAssetIds:[$cos]}}],
            media:[{assetId:$local,mimeType:"image/png",byteSize:1,sha256:("1"*64),copy:{},
              variants:[{variantName:"public",width:1,height:1,byteSize:1,sha256:("2"*64)}]},
              {assetId:$cos,mimeType:"image/png",byteSize:1,sha256:("3"*64),copy:{},
              variants:[{variantName:"history",width:1,height:1,byteSize:1,sha256:("4"*64)}]}]}'
        ;;
      content-publication-pointer) cat >/dev/null; printf '77777777-7777-4777-8777-777777777777\n' ;;
      content-audit-total) cat >/dev/null; printf '0\n' ;;
      content-audit-after) cat >/dev/null; printf '1|1\n' ;;
      none)
        cat >/dev/null
        if grep -F ' exec -T postgres ' <<<"$joined" >/dev/null; then
          printf 'production-record\n' >>"$RESTORE_TEST_COMMAND_LOG"
        fi
        ;;
      *) cat >/dev/null ;;
    esac
    ;;
  container|volume|network)
    [[ "$2" == ls ]]
    if [[ "$1" == container ]]; then
      [[ "$3" == -aq ]]
      [[ "${RESTORE_TEST_STOPPED_ORPHAN:-false}" != true ]] || printf 'stopped-orphan\n'
    else
      [[ "$3" == -q ]]
    fi
    ;;
  *) exit 64 ;;
esac
DOCKER

cat >"$BIN/curl" <<'CURL'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1" == --disable ]]
shift
if [[ "${RESTORE_IDENTITY_DISPOSED:-false}" == true ]]; then
  [[ ! -e "${RESTORE_SECRETS_DIRECTORY:-/nonexistent}/age-identity" ]]
  [[ ! -e "/proc/self/fd/${RESTORE_TEST_IDENTITY_FD:-9}" ]]
fi
output='' writeout='' url='' cookie_jar='' method=GET dump_header='' cacert='' data_binary=''
while (($#)); do
  case "$1" in
    --output) output="$2"; shift 2 ;;
    --write-out) writeout="$2"; shift 2 ;;
    --cookie-jar) cookie_jar="$2"; shift 2 ;;
    --request) method="$2"; shift 2 ;;
    --dump-header) dump_header="$2"; shift 2 ;;
    --cacert) cacert="$2"; shift 2 ;;
    --data-binary) data_binary="$2"; shift 2 ;;
    --resolve|--cookie|--header|--data|--proto|--proto-redir|--max-redirs|--noproxy) shift 2 ;;
    --fail|--silent|--show-error|--tlsv1.2) shift ;;
    https://*) url="$1"; shift ;;
    *) shift ;;
  esac
done
[[ -n "$url" && -n "$output" ]]
if [[ "$url" == https://restore.test.invalid:* ]]; then
  [[ "$cacert" == "$RESTORE_TLS_CA_CERT" ]]
else
  [[ -z "$cacert" ]]
fi
[[ -z "$cookie_jar" ]] || : >"$cookie_jar"
status=200; content_type=application/json
case "$url" in
  */admin/assets/*)
    asset_name="${url%%\?*}"; asset_name="${asset_name##*/}"
    cp -- "$(dirname "$RESTORE_TEST_ADMIN_ASSET")/$asset_name" "$output"
    [[ "$asset_name" != *.css ]] || content_type=text/css
    [[ "$asset_name" == *.css ]] || content_type=application/javascript
    ;;
  */assets/*)
    asset_name="${url%%\?*}"; asset_name="${asset_name##*/}"
    cp -- "$(dirname "$RESTORE_TEST_PUBLIC_ASSET")/$asset_name" "$output"
    [[ "$asset_name" != *.css ]] || content_type=text/css
    [[ "$asset_name" == *.css ]] || content_type=application/javascript
    ;;
  */api/public/media/*/public) cp -- "$RESTORE_TEST_LOCAL_VARIANT" "$output"; content_type=image/png ;;
  */api/admin/media/*/preview/history)
    : >"$output"; status=302; content_type=text/plain
    location="https://$RESTORE_DRILL_COS_BUCKET.cos.$RESTORE_DRILL_COS_REGION.myqcloud.com/drills/$RESTORE_DRILL_ID/blobs/$(sha256sum "$RESTORE_TEST_COS_VARIANT" | awk '{print $1}')?contract-signature=1"
    [[ "${RESTORE_TEST_REDIRECT_ESCAPE:-false}" != true ]] || location='https://prod-media-1250000000.cos.ap-guangzhou.myqcloud.com/production/escape?signature=bad'
    ;;
  */api/admin/auth/csrf) printf '%s' '{"headerName":"X-CSRF-TOKEN","token":"contract-csrf"}' >"$output" ;;
  */api/admin/auth/password) printf '%s' '{"next":"SECOND_FACTOR"}' >"$output" ;;
  */api/admin/auth/second-factor)
    [[ "$data_binary" == @* && -f "${data_binary#@}" ]]
    jq -e '.method == "RECOVERY_CODE" and (.code | length > 0)' "${data_binary#@}" >/dev/null
    printf '%s' '{"id":"contract-admin"}' >"$output"
    ;;
  */api/admin/auth/me) printf '%s' '{"id":"contract-admin"}' >"$output" ;;
  */api/admin/publishing/preview-tokens)
    [[ "$method" == POST ]]
    printf '%s' '{"token":"contract-preview-token"}' >"$output"
    ;;
  */api/admin/publishing/previews/contract-preview-token)
    jq -n -cS --arg project "$RESTORE_TEST_PROJECT_ID" \
      --arg local "$RESTORE_TEST_LOCAL_ASSET" --arg cos "$RESTORE_TEST_COS_ASSET" \
      --arg mismatch "${RESTORE_TEST_PREVIEW_MISMATCH:-}" \
      '{schemaVersion:1,projectId:$project,externalKey:"contract",slug:"contract",number:"01",sortOrder:0,
        featured:true,translations:{},tags:[],skills:[],
        projectMedia:[{assetId:$local,usage:"DETAIL",sortOrder:0,layout:"wide",
          objectPosition:(if $mismatch == "position" then "0% 0%" else "50% 50%" end),
          credit:"contract",sourceUrl:"https://example.invalid/local"}],
        blocks:[{type:"GALLERY",sortOrder:0,payload:{mediaAssetIds:[$cos]}}],
        media:[{assetId:$local,mimeType:"image/png",byteSize:1,sha256:("1"*64),copy:{},
          variants:[{variantName:(if $mismatch == "variant" then "wrong" else "public" end),
            width:1,height:1,byteSize:1,sha256:("2"*64)}]},
          {assetId:$cos,mimeType:"image/png",byteSize:1,sha256:("3"*64),copy:{},
          variants:[{variantName:"history",width:1,height:1,byteSize:1,sha256:("4"*64)}]}]}' >"$output"
    ;;
  */api/public/site\?*) printf '%s' '{"data":{"name":"contract"}}' >"$output" ;;
  */api/public/projects\?*) printf '%s' '{"data":[{"id":"contract-project"}]}' >"$output" ;;
  */api/admin/publishing/revisions/*/restore)
    [[ "$method" == POST ]]; : >"$output"; status=204 ;;
  */api/admin/projects/*/workspace) printf '%s' '{"version":8,"publicationDirty":true}' >"$output" ;;
  https://drill-media-1250000000.cos.ap-shanghai.myqcloud.com/drills/*/blobs/*\?*)
    cp -- "$RESTORE_TEST_COS_VARIANT" "$output"; content_type=image/png
    ;;
  *) exit 22 ;;
esac
if [[ -n "$dump_header" ]]; then
  {
    printf 'HTTP/1.1 %s Contract\r\n' "$status"
    printf 'Content-Type: %s\r\n' "$content_type"
    [[ -z "${location:-}" ]] || printf 'Location: %s\r\n' "$location"
    printf '\r\n'
  } >"$dump_header"
fi
printf 'curl:%s:%s\n' "$status" "$url" >>"$RESTORE_TEST_COMMAND_LOG"
if [[ "$writeout" == *'http_code}|'* ]]; then
  printf '%s|%s|%s|0\n' "$status" "$content_type" "$url"
else
  printf '%s' "$status"
fi
CURL

chmod 0755 "$BIN/rclone" "$BIN/age" "$BIN/docker" "$BIN/curl"

DD_FAIL_BIN="$WORK_DIRECTORY/dd-fail-bin"
CHMOD_FAIL_BIN="$WORK_DIRECTORY/chmod-fail-bin"
mkdir -p "$DD_FAIL_BIN" "$CHMOD_FAIL_BIN"
cat >"$DD_FAIL_BIN/dd" <<'DDFAIL'
#!/usr/bin/env bash
set -euo pipefail
output=''
for argument in "$@"; do
  [[ "$argument" != of=* ]] || output="${argument#of=}"
done
[[ -n "$output" ]]
printf 'partial-identity' >"$output"
exit 1
DDFAIL
cat >"$CHMOD_FAIL_BIN/chmod" <<'CHMODFAIL'
#!/usr/bin/env bash
set -euo pipefail
for argument in "$@"; do
  [[ "$argument" != */age-identity ]] || exit 1
done
exec /usr/bin/chmod "$@"
CHMODFAIL
chmod 0755 "$DD_FAIL_BIN/dd" "$CHMOD_FAIL_BIN/chmod"

canonical_tree_sha() {
  local root="$1"
  (cd "$root"; find . -type f -print0 | sort -z | while IFS= read -r -d '' path; do
    printf '%s\0%s\0' "${path#./}" "$(sha256sum "$path" | awk '{print $1}')"
  done) | sha256sum | awk '{print $1}'
}

make_image_archive() {
  local tag="$1" output="$2" directory
  directory="$WORK_DIRECTORY/image-$(printf '%s' "$tag" | sha256sum | cut -c1-12)"
  mkdir -p "$directory"
  printf '%s' '{"architecture":"amd64","os":"linux","rootfs":{"type":"layers","diff_ids":[]}}' >"$directory/config"
  local config_sha
  config_sha="$(sha256sum "$directory/config" | awk '{print $1}')"
  mv "$directory/config" "$directory/$config_sha.json"
  printf 'contract-layer\n' >"$directory/layer.tar"
  jq -n -c --arg config "$config_sha.json" --arg tag "$tag" \
    '[{Config:$config,RepoTags:[$tag],Layers:["layer.tar"]}]' >"$directory/manifest.json"
  tar --format=ustar --sort=name --mtime='@0' --owner=0 --group=0 --numeric-owner \
    -cf "$directory/image.tar" -C "$directory" "$config_sha.json" layer.tar manifest.json
  zstd -q -19 -f "$directory/image.tar" -o "$output"
  printf 'sha256:%s\n' "$config_sha"
}

local_original='local-original-contract'
local_variant='local-current-contract'
cos_original='cos-original-contract'
cos_variant='cos-history-contract'
printf '%s' "$local_original" >"$FIXTURE/local-source/originals/local.bin"
printf '%s' "$local_variant" >"$FIXTURE/local-source/variants/local.png"
printf '%s' "$cos_original" >"$FIXTURE/cos-original.bin"
printf '%s' "$cos_variant" >"$FIXTURE/cos-history.png"
local_original_sha="$(sha256sum "$FIXTURE/local-source/originals/local.bin" | awk '{print $1}')"
local_variant_sha="$(sha256sum "$FIXTURE/local-source/variants/local.png" | awk '{print $1}')"
cos_original_sha="$(sha256sum "$FIXTURE/cos-original.bin" | awk '{print $1}')"
cos_variant_sha="$(sha256sum "$FIXTURE/cos-history.png" | awk '{print $1}')"

mkdir -p "$FIXTURE/release/admin/assets" "$FIXTURE/release/public-assets/assets" \
  "$FIXTURE/release/public-assets/.vite" "$FIXTURE/release/ops/deploy" \
  "$FIXTURE/release/ops/docs/operations" "$FIXTURE/release/images" \
  "$FIXTURE/release/compliance/licenses/frontend" \
  "$FIXTURE/release/compliance/licenses/admin-web" \
  "$FIXTURE/release/compliance/licenses/backend" \
  "$FIXTURE/release/compliance/licenses/cos-prune-runtime" \
  "$FIXTURE/release/compliance/licenses/api-image" \
  "$FIXTURE/release/compliance/licenses/postgres-image" \
  "$FIXTURE/release/compliance/sbom"
printf 'admin-release-asset' >"$FIXTURE/release/admin/assets/admin.js"
printf 'admin-style-contract' >"$FIXTURE/release/admin/assets/admin.css"
printf '<!doctype html><link rel="stylesheet" href="/admin/assets/admin.css"><script src="/admin/assets/admin.js"></script>' >"$FIXTURE/release/admin/index.html"
printf 'public-release-asset' >"$FIXTURE/release/public-assets/assets/public.js"
printf 'public-style-contract' >"$FIXTURE/release/public-assets/assets/public.css"
printf '%s' '{"src/main.ts":{"file":"assets/public.js","css":["assets/public.css"],"isEntry":true}}' \
  >"$FIXTURE/release/public-assets/.vite/manifest.json"
printf '<svg xmlns="http://www.w3.org/2000/svg"/>\n' >"$FIXTURE/release/public-assets/favicon.svg"
printf 'fixture notices\n' >"$FIXTURE/release/compliance/THIRD_PARTY_NOTICES.txt"
printf 'fixture assets\n' >"$FIXTURE/release/compliance/ASSET_PROVENANCE.md"
printf 'fixture sums\n' >"$FIXTURE/release/compliance/SHA256SUMS"
for fixture_json in \
  licenses/frontend/manifest.json licenses/admin-web/manifest.json \
  licenses/backend/manifest.json licenses/cos-prune-runtime/manifest.json \
  licenses/api-image/manifest.json licenses/postgres-image/manifest.json \
  sbom/api-image.cdx.json sbom/postgres-image.cdx.json; do
  printf '{}\n' >"$FIXTURE/release/compliance/$fixture_json"
done
cp -a "$REPOSITORY_ROOT/deploy/restore" "$FIXTURE/release/ops/deploy/restore"
cp -a "$REPOSITORY_ROOT/deploy/systemd" "$FIXTURE/release/ops/deploy/systemd"
cp -a "$REPOSITORY_ROOT/deploy/tmpfiles.d" "$FIXTURE/release/ops/deploy/tmpfiles.d"
mkdir -p "$FIXTURE/release/ops/deploy/scripts" "$FIXTURE/release/ops/deploy/backup"
cp "$REPOSITORY_ROOT/deploy/docker-compose.prod.yml" \
  "$FIXTURE/release/ops/deploy/docker-compose.prod.yml"
cp "$REPOSITORY_ROOT/deploy/image-lock.env" \
  "$FIXTURE/release/ops/deploy/image-lock.env"
for backup_tool in \
  backup-database.sh backup-dispatch.sh backup-media.sh backup-set.sh \
  cos-prune-guard.py export-media.sql install-cos-prune-runtime.sh lib.sh \
  notify-failure.sh postgres-client.sh \
  prune-guard.example.sh prune-remote.sh record-maintenance.sql \
  requirements-cos-prune.in requirements-cos-prune.txt \
  validate-media-tar.py verify-artifact.sh
do
  cp "$REPOSITORY_ROOT/deploy/backup/$backup_tool" \
    "$FIXTURE/release/ops/deploy/backup/$backup_tool"
done
for release_tool in \
  deploy-release.sh image-lock-lib.sh install-backup-units.sh preflight.sh prune-releases.sh \
  rollback-release.sh smoke.sh switch-journal.sh verify-cos-staging-lifecycle.sh \
  verify-compliance-tree.py
do
  cp "$REPOSITORY_ROOT/deploy/scripts/$release_tool" \
    "$FIXTURE/release/ops/deploy/scripts/$release_tool"
done
for operations_document in production-runbook.md backup-recovery.md release-evidence-template.md; do
  cp "$REPOSITORY_ROOT/docs/operations/$operations_document" \
    "$FIXTURE/release/ops/docs/operations/$operations_document"
done
find "$FIXTURE/release/ops" -type d -exec chmod 0700 {} +
find "$FIXTURE/release/ops" -type f -exec chmod 0600 {} +
find "$FIXTURE/release/ops" -type f -name '*.sh' -exec chmod 0700 {} +
find "$FIXTURE/release/compliance" -type d -exec chmod 0700 {} +
find "$FIXTURE/release/compliance" -type f -exec chmod 0600 {} +
chmod 0600 \
  "$FIXTURE/release/ops/deploy/image-lock.env" \
  "$FIXTURE/release/ops/deploy/scripts/image-lock-lib.sh" \
  "$FIXTURE/release/ops/deploy/scripts/switch-journal.sh"
chmod 0700 \
  "$FIXTURE/release/ops/deploy/backup/validate-media-tar.py"
manifest_sha="$(sha256sum "$FIXTURE/release/public-assets/.vite/manifest.json" | awk '{print $1}')"
git_commit='aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
RELEASE_ID="${git_commit:0:12}-${manifest_sha:0:12}"
readonly RELEASE_ID
api_tag="portfolio-api-archive:$RELEASE_ID"
postgres_tag="portfolio-postgres-17-archive:$RELEASE_ID"
api_id="$(make_image_archive "$api_tag" "$FIXTURE/release/images/portfolio-api.oci.tar.zst")"
postgres_id="$(make_image_archive "$postgres_tag" "$FIXTURE/release/images/postgres-17.oci.tar.zst")"
source_epoch=1700000000
build_time="$(date -u --date="@$source_epoch" '+%Y-%m-%dT%H:%M:%SZ')"
admin_tree="$(canonical_tree_sha "$FIXTURE/release/admin")"
public_tree="$(canonical_tree_sha "$FIXTURE/release/public-assets")"
ops_tree="$(canonical_tree_sha "$FIXTURE/release/ops")"
compliance_tree="$(canonical_tree_sha "$FIXTURE/release/compliance")"
api_archive_sha="$(sha256sum "$FIXTURE/release/images/portfolio-api.oci.tar.zst" | awk '{print $1}')"
postgres_archive_sha="$(sha256sum "$FIXTURE/release/images/postgres-17.oci.tar.zst" | awk '{print $1}')"
jq -n -S \
  --arg releaseId "$RELEASE_ID" --arg gitCommit "$git_commit" --arg manifestSha256 "$manifest_sha" \
  --arg sourceContinuityRef "refs/tags/portfolio-release/$RELEASE_ID" \
  --arg apiImageTag "portfolio-api:$RELEASE_ID" --arg apiImageId "$api_id" \
  --arg postgresImageRef "$(awk -F= '$1 == "POSTGRES_IMAGE" {print $2}' "$REPOSITORY_ROOT/deploy/image-lock.env")" \
  --arg postgresImageTag "portfolio-postgres-17:$RELEASE_ID" --arg postgresImageId "$postgres_id" \
  --arg buildTimeUtc "$build_time" --arg jarSha256 "$(printf '1%.0s' {1..64})" \
  --arg adminTreeSha256 "$admin_tree" --arg publicTreeSha256 "$public_tree" --arg opsTreeSha256 "$ops_tree" \
  --arg complianceTreeSha256 "$compliance_tree" \
  --arg apiImageArchiveSha256 "$api_archive_sha" --arg postgresImageArchiveSha256 "$postgres_archive_sha" \
  --arg nodeRef "$(awk -F= '$1 == "NODE_IMAGE" {print $2}' "$REPOSITORY_ROOT/deploy/image-lock.env")" \
  --arg playwrightRef "$(awk -F= '$1 == "PLAYWRIGHT_IMAGE" {print $2}' "$REPOSITORY_ROOT/deploy/image-lock.env")" \
  --arg javaBuild "$(awk -F= '$1 == "JAVA_BUILD_IMAGE" {print $2}' "$REPOSITORY_ROOT/deploy/image-lock.env")" \
  --arg javaRuntime "$(awk -F= '$1 == "JAVA_RUNTIME_IMAGE" {print $2}' "$REPOSITORY_ROOT/deploy/image-lock.env")" \
  --arg ubuntuSnapshot "$(awk -F= '$1 == "UBUNTU_APT_SNAPSHOT" {print $2}' "$REPOSITORY_ROOT/deploy/image-lock.env")" \
  --argjson sourceEpoch "$source_epoch" \
  '{releaseId:$releaseId,gitCommit:$gitCommit,manifestSha256:$manifestSha256,
    sourceContinuityRef:$sourceContinuityRef,apiImageTag:$apiImageTag,apiImageId:$apiImageId,
    apiImageRepoDigest:null,postgresImageRef:$postgresImageRef,postgresImageTag:$postgresImageTag,
    postgresImageId:$postgresImageId,buildTimeUtc:$buildTimeUtc,jarSha256:$jarSha256,
    adminTreeSha256:$adminTreeSha256,publicTreeSha256:$publicTreeSha256,opsTreeSha256:$opsTreeSha256,
    complianceTreeSha256:$complianceTreeSha256,
    apiImageArchiveSha256:$apiImageArchiveSha256,postgresImageArchiveSha256:$postgresImageArchiveSha256,
    bundlePayloadSha256:"",buildInputs:{nodeImageRef:$nodeRef,playwrightImageRef:$playwrightRef,javaBuildImageRef:$javaBuild,
      javaRuntimeImageRef:$javaRuntime,ubuntuAptSnapshot:$ubuntuSnapshot,
      targetPlatform:"linux/amd64",sourceDateEpoch:$sourceEpoch}}' >"$FIXTURE/release/release.tmp.json"
bundle_payload="$(jq -Sc '{releaseId,gitCommit,manifestSha256,sourceContinuityRef,apiImageTag,
  apiImageId,apiImageRepoDigest,postgresImageRef,postgresImageTag,postgresImageId,
  jarSha256,adminTreeSha256,publicTreeSha256,opsTreeSha256,complianceTreeSha256,apiImageArchiveSha256,
  postgresImageArchiveSha256,buildInputs}' "$FIXTURE/release/release.tmp.json" | sha256sum | awk '{print $1}')"
jq -S --arg payload "$bundle_payload" '.bundlePayloadSha256=$payload' \
  "$FIXTURE/release/release.tmp.json" >"$FIXTURE/release/release.json"
rm "$FIXTURE/release/release.tmp.json"
(cd "$FIXTURE/release"; find admin public-assets ops images compliance -type f -print0 | sort -z | \
  while IFS= read -r -d '' path; do printf '%s  %s\n' "$(sha256sum "$path" | awk '{print $1}')" "$path"; done \
  >bundle-manifest.json)

release_parent="$FIXTURE/portfolio-$RELEASE_ID"
mv "$FIXTURE/release" "$release_parent"
release_tar="$FIXTURE/portfolio-$RELEASE_ID.tar"
tar --format=ustar --sort=name --mtime='@0' --owner=0 --group=0 --numeric-owner \
  -cf "$release_tar" -C "$FIXTURE" "portfolio-$RELEASE_ID"
zstd -q -19 -f "$release_tar" -o "$FIXTURE/portfolio-$RELEASE_ID.tar.zst"
release_remote="$REMOTE/release/release-bundles-1250000000"
mkdir -p "$release_remote"
cp "$FIXTURE/portfolio-$RELEASE_ID.tar.zst" "$release_remote/"
jq -n -S --arg releaseId "$RELEASE_ID" \
  --arg archiveSha256 "$(sha256sum "$FIXTURE/portfolio-$RELEASE_ID.tar.zst" | awk '{print $1}')" \
  --argjson archiveBytes "$(stat -Lc '%s' "$FIXTURE/portfolio-$RELEASE_ID.tar.zst")" \
  --arg releaseJsonSha256 "$(sha256sum "$release_parent/release.json" | awk '{print $1}')" \
  '{releaseId:$releaseId,archiveSha256:$archiveSha256,archiveBytes:$archiveBytes,
    releaseJsonSha256:$releaseJsonSha256}' \
  >"$release_remote/portfolio-$RELEASE_ID.tar.zst.envelope.json"

database_plain="$FIXTURE/database.dump.age"
printf 'contract-custom-format-dump\n' >"$database_plain"
database_plain_sha="$(sha256sum "$database_plain" | awk '{print $1}')"
media_manifest="$FIXTURE/media-manifest.json.age"
jq -n -S \
  --arg setId "$SET_ID" --arg snapshotId contract-snapshot --arg databaseSha "$database_plain_sha" \
  --arg localAsset "$LOCAL_ASSET" --arg cosAsset "$COS_ASSET" \
  --arg localOriginalSha "$local_original_sha" --arg localVariantSha "$local_variant_sha" \
  --arg cosOriginalSha "$cos_original_sha" --arg cosVariantSha "$cos_variant_sha" \
  --argjson localOriginalSize "${#local_original}" --argjson localVariantSize "${#local_variant}" \
  --argjson cosOriginalSize "${#cos_original}" --argjson cosVariantSize "${#cos_variant}" \
  '{schemaVersion:1,setId:$setId,snapshotId:$snapshotId,databaseDumpPlaintextSha256:$databaseSha,
    rows:[
      {assetId:$localAsset,objectKind:"ORIGINAL",variantName:"ORIGINAL",provider:"LOCAL",bucket:null,region:null,objectKey:"originals/local.bin",mimeType:"application/octet-stream",byteSize:$localOriginalSize,sha256:$localOriginalSha},
      {assetId:$localAsset,objectKind:"VARIANT",variantName:"public",provider:"LOCAL",bucket:null,region:null,objectKey:"variants/local.png",mimeType:"image/png",byteSize:$localVariantSize,sha256:$localVariantSha},
      {assetId:$cosAsset,objectKind:"ORIGINAL",variantName:"ORIGINAL",provider:"TENCENT_COS",bucket:"prod-media-1250000000",region:"ap-guangzhou",objectKey:"originals/cos.bin",mimeType:"application/octet-stream",byteSize:$cosOriginalSize,sha256:$cosOriginalSha},
      {assetId:$cosAsset,objectKind:"VARIANT",variantName:"history",provider:"TENCENT_COS",bucket:"prod-media-1250000000",region:"ap-guangzhou",objectKey:"variants/cos.png",mimeType:"image/png",byteSize:$cosVariantSize,sha256:$cosVariantSha}
    ]}' >"$media_manifest"
jq -n -S --arg localOriginalSha "$local_original_sha" --arg localVariantSha "$local_variant_sha" \
  --argjson localOriginalSize "${#local_original}" --argjson localVariantSize "${#local_variant}" \
  '{schemaVersion:1,entries:[
    {path:"originals/local.bin",size:$localOriginalSize,sha256:$localOriginalSha},
    {path:"variants/local.png",size:$localVariantSize,sha256:$localVariantSha}]}' \
  >"$FIXTURE/local-allowlist.json"
python3 "$REPOSITORY_ROOT/deploy/backup/validate-media-tar.py" \
  --allowlist "$FIXTURE/local-allowlist.json" --build-root "$FIXTURE/local-source" \
  --build-output "$FIXTURE/local-media.tar.age"

backup_set_remote="$REMOTE/backup/backup-store-1250000000/production/sets/$SET_ID"
backup_blob_remote="$REMOTE/backup/backup-store-1250000000/production/blobs"
mkdir -p "$backup_set_remote" "$backup_blob_remote"
cp "$database_plain" "$FIXTURE/local-media.tar.age" "$media_manifest" "$backup_set_remote/"
cp "$FIXTURE/cos-original.bin" "$backup_blob_remote/$cos_original_sha"
cp "$FIXTURE/cos-history.png" "$backup_blob_remote/$cos_variant_sha"
started="$(date -u -d '2 minutes ago' '+%Y-%m-%dT%H:%M:%SZ')"
finished="$(date -u -d '1 minute ago' '+%Y-%m-%dT%H:%M:%SZ')"
jq -n -S -c --arg setId "$SET_ID" --arg releaseId "$RELEASE_ID" --arg snapshotId contract-snapshot \
  --arg started "$started" --arg finished "$finished" \
  --arg dbSha "$(sha256sum "$database_plain" | awk '{print $1}')" \
  --arg localSha "$(sha256sum "$FIXTURE/local-media.tar.age" | awk '{print $1}')" \
  --arg manifestSha "$(sha256sum "$media_manifest" | awk '{print $1}')" \
  --arg cosOriginalSha "$cos_original_sha" --arg cosVariantSha "$cos_variant_sha" \
  --argjson dbSize "$(stat -Lc '%s' "$database_plain")" \
  --argjson localSize "$(stat -Lc '%s' "$FIXTURE/local-media.tar.age")" \
  --argjson manifestSize "$(stat -Lc '%s' "$media_manifest")" \
  --argjson cosOriginalSize "${#cos_original}" --argjson cosVariantSize "${#cos_variant}" \
  '{artifacts:{databaseDump:{byteSize:$dbSize,ciphertextSha256:$dbSha,file:"database.dump.age"},
      localMediaTar:{byteSize:$localSize,ciphertextSha256:$localSha,file:"local-media.tar.age"},
      mediaManifest:{byteSize:$manifestSize,ciphertextSha256:$manifestSha,file:"media-manifest.json.age"}},
    backupFinishedAt:$finished,backupStartedAt:$started,
    blobs:[{byteSize:$cosOriginalSize,path:("blobs/"+$cosOriginalSha),sha256:$cosOriginalSha},
      {byteSize:$cosVariantSize,path:("blobs/"+$cosVariantSha),sha256:$cosVariantSha}] | sort_by(.path),
    counts:{cosObjects:2,cosVerificationSamples:2,distinctCosBlobs:2,localObjects:2,totalObjects:4},
    releaseId:$releaseId,retention:{daily:true,monthly:false,weekly:false},schemaVersion:1,
    setId:$setId,snapshotId:$snapshotId}' >"$backup_set_remote/set-manifest.json"
set_manifest_sha="$(sha256sum "$backup_set_remote/set-manifest.json" | awk '{print $1}')"
printf '%s\n' "$set_manifest_sha" >"$backup_set_remote/VERIFIED"

cat >"$FIXTURE/closure.psv" <<EOF
$LOCAL_ASSET|ORIGINAL|ORIGINAL|true|true|LOCAL|||originals/local.bin|application/octet-stream|${#local_original}|$local_original_sha
$LOCAL_ASSET|VARIANT|public|true|true|LOCAL|||variants/local.png|image/png|${#local_variant}|$local_variant_sha
$COS_ASSET|ORIGINAL|ORIGINAL|false|true|TENCENT_COS|prod-media-1250000000|ap-guangzhou|originals/cos.bin|application/octet-stream|${#cos_original}|$cos_original_sha
$COS_ASSET|VARIANT|history|false|true|TENCENT_COS|prod-media-1250000000|ap-guangzhou|variants/cos.png|image/png|${#cos_variant}|$cos_variant_sha
EOF

printf 'offline-contract-identity\n' >"$FIXTURE/offline-age-identity"
chmod 0600 "$FIXTURE/offline-age-identity"

make_secrets() {
  local drill_id="$1" directory="/run/portfolio-restore-secrets/$1"
  mkdir -m 0700 "$directory"
  printf '%s\n' "$drill_id" >"$directory/.portfolio-restore-secrets"
  cat >"$directory/postgres.env" <<'EOF'
POSTGRES_DB=portfolio
POSTGRES_USER=postgres
POSTGRES_PASSWORD=contract-postgres
PORTFOLIO_DB_RUNTIME_USER=portfolio_runtime_drill
PORTFOLIO_DB_RUNTIME_PASSWORD=contract-runtime
PORTFOLIO_DB_MIGRATOR_USER=portfolio_migrator_drill
PORTFOLIO_DB_MIGRATOR_PASSWORD=contract-migrator
EOF
  cat >"$directory/app.env" <<'EOF'
PORTFOLIO_DB_URL=jdbc:postgresql://postgres:5432/portfolio
PORTFOLIO_DB_MIGRATOR_URL=jdbc:postgresql://postgres:5432/portfolio
PORTFOLIO_DB_RUNTIME_USER=portfolio_runtime_drill
PORTFOLIO_DB_RUNTIME_PASSWORD=contract-runtime
PORTFOLIO_DB_MIGRATOR_USER=portfolio_migrator_drill
PORTFOLIO_DB_MIGRATOR_PASSWORD=contract-migrator
PORTFOLIO_STORAGE_DEFAULT_PROVIDER=LOCAL
PORTFOLIO_COS_ENABLED=true
COS_BUCKET=drill-media-1250000000
COS_REGION=ap-shanghai
COS_SECRET_ID=contract-temporary-id
COS_SECRET_KEY=contract-temporary-key
COS_SESSION_TOKEN=contract-temporary-token
PORTFOLIO_EMAIL_ENABLED=false
PORTFOLIO_JOBS_WORKER_ENABLED=false
PORTFOLIO_STAGING_CLEANUP_ENABLED=false
PORTFOLIO_MEDIA_CLEANUP_ENABLED=false
PORTFOLIO_ANALYTICS_MAINTENANCE_SCHEDULING_ENABLED=false
PORTFOLIO_JOBS_RETENTION_ENABLED=false
EOF
  {
    printf 'PORTFOLIO_RELEASE_ID=%s\n' "$RELEASE_ID"
    printf 'PORTFOLIO_PREVIEW_HMAC_KEY=%s\n' "$(printf 'p%.0s' {1..32} | base64 -w0)"
    printf 'PORTFOLIO_CONTACT_DEDUPE_SECRET=%s\n' "$(printf 'c%.0s' {1..32} | base64 -w0)"
    printf 'PORTFOLIO_ANALYTICS_HMAC_SECRET=%s\n' "$(printf 'a%.0s' {1..32} | base64 -w0)"
    printf 'PORTFOLIO_TOTP_ACTIVE_KEY_VERSION=1\nPORTFOLIO_TOTP_KEY_RING=1=%s\n' \
      "$(printf 't%.0s' {1..32} | base64 -w0)"
  } >>"$directory/app.env"
  for file in backup-verifier.rclone.conf report-uploader.rclone.conf drill-cos.rclone.conf; do
    printf 'fixture=true\n' >"$directory/$file"
  done
  openssl req -x509 -newkey rsa:2048 -nodes -days 1 \
    -subj '/CN=restore.test.invalid' -addext 'subjectAltName=DNS:restore.test.invalid' \
    -keyout "$directory/tls.key" -out "$directory/tls.crt" >/dev/null 2>&1
  cp "$directory/tls.crt" "$directory/ca.crt"
  printf '%s' '{"username":"contract-admin","password":"contract-password","method":"RECOVERY_CODE","code":"contract-unused-recovery"}' \
    >"$directory/admin-auth.json"
  printf 'PORTFOLIO_RELEASE_ID=contract\n' >"$directory/production-release.env"
  cp "$REPOSITORY_ROOT/deploy/docker-compose.prod.yml" "$directory/production-compose.yml"
  chmod 0600 "$directory"/* "$directory/.portfolio-restore-secrets"
}

run_drill() {
  local drill_id="$1" root="$2" fail_call="$3" rclone_fail_match="${4:-}" app_extra="${5:-}"
  local preview_mismatch="${6:-}" secret_mode="${7:-0700}" path_prefix="${8:-}"
  local redirect_escape="${9:-false}"
  local credential_window="${10:-45 minutes}"
  make_secrets "$drill_id"
  chmod "$secret_mode" "/run/portfolio-restore-secrets/$drill_id"
  if [[ -n "$app_extra" ]]; then
    printf '%s\n' "$app_extra" >>"/run/portfolio-restore-secrets/$drill_id/app.env"
  fi
  printf '0\n' >"$AGE_COUNT"
  : >"$COMMAND_LOG"
  HOME="$MALICIOUS_HOME" PATH="${path_prefix:+$path_prefix:}$PATH" \
  COMPOSE_PROJECT_NAME='portfolio/evil' COMPOSE_FILE='/production/evil.yml' \
  COMPOSE_PROFILES=production RESTORE_ENV=isolated \
  RESTORE_DOCKER_COMMAND="$BIN/docker" RESTORE_RCLONE_COMMAND="$BIN/rclone" \
  RESTORE_CURL_COMMAND="$BIN/curl" RESTORE_AGE_COMMAND="$BIN/age" \
  RESTORE_COMPLIANCE_VALIDATOR="$COMPLIANCE_STUB" \
  RESTORE_API_IMAGE="$api_tag" RESTORE_POSTGRES_IMAGE="$postgres_tag" \
  RESTORE_NGINX_IMAGE="sha256:$(printf 'c%.0s' {1..64})" \
  RESTORE_APP_ENV="/run/portfolio-restore-secrets/$drill_id/app.env" \
  RESTORE_POSTGRES_ENV="/run/portfolio-restore-secrets/$drill_id/postgres.env" \
  BACKUP_VERIFY_RCLONE_CONFIG="/run/portfolio-restore-secrets/$drill_id/backup-verifier.rclone.conf" \
  BACKUP_UPLOAD_RCLONE_CONFIG="/run/portfolio-restore-secrets/$drill_id/report-uploader.rclone.conf" \
  RESTORE_DRILL_COS_CONFIG="/run/portfolio-restore-secrets/$drill_id/drill-cos.rclone.conf" \
  RESTORE_TLS_CERTIFICATE="/run/portfolio-restore-secrets/$drill_id/tls.crt" \
  RESTORE_TLS_PRIVATE_KEY="/run/portfolio-restore-secrets/$drill_id/tls.key" \
  RESTORE_TLS_CA_CERT="/run/portfolio-restore-secrets/$drill_id/ca.crt" \
  RESTORE_ADMIN_AUTH_FILE="/run/portfolio-restore-secrets/$drill_id/admin-auth.json" \
  RESTORE_TLS_SERVER_NAME=restore.test.invalid \
  PRODUCTION_COS_ACCOUNT_ID=production-account PRODUCTION_COS_BUCKET=prod-media-1250000000 \
  PRODUCTION_COS_REGION=ap-guangzhou PRODUCTION_COS_PRINCIPAL_ID=production-principal \
  BACKUP_DESTINATION_ACCOUNT_ID=backup-account BACKUP_DESTINATION_BUCKET=backup-store-1250000000 \
  BACKUP_DESTINATION_REGION=ap-beijing BACKUP_VERIFY_PRINCIPAL_ID=backup-verifier-principal \
  BACKUP_UPLOAD_PRINCIPAL_ID=report-uploader-principal \
  RESTORE_DRILL_COS_ACCOUNT_ID=drill-account RESTORE_DRILL_COS_BUCKET=drill-media-1250000000 \
  RESTORE_DRILL_COS_REGION=ap-shanghai RESTORE_DRILL_COS_PRINCIPAL_ID=drill-principal \
  PRODUCTION_COS_LOCATIONS=production-account@prod-media-1250000000@ap-guangzhou \
  BACKUP_DESTINATION_LOCATION=backup-account@backup-store-1250000000@ap-beijing \
  BACKUP_REMOTE=backup:backup-store-1250000000 \
  RESTORE_RELEASE_REMOTE=release:release-bundles-1250000000 \
  RESTORE_DRILL_COS_REMOTE=drill:drill-media-1250000000 BACKUP_PREFIX=production \
  RESTORE_DRILL_CREDENTIAL_EXPIRES_AT="$(date -u -d "$credential_window" '+%Y-%m-%dT%H:%M:%SZ')" \
  RESTORE_PRODUCTION_COMPOSE_FILE="/run/portfolio-restore-secrets/$drill_id/production-compose.yml" \
  RESTORE_PRODUCTION_RELEASE_ENV="/run/portfolio-restore-secrets/$drill_id/production-release.env" \
  RESTORE_RECORD_CONTRACT_FIXTURE=true \
  RESTORE_PRESERVE_ROOT=true RESTORE_TEST_REMOTE="$REMOTE" \
  RESTORE_TEST_DOCKER_STATE="$STATE/docker-images" RESTORE_TEST_COMMAND_LOG="$COMMAND_LOG" \
  RESTORE_TEST_AGE_COUNT="$AGE_COUNT" RESTORE_TEST_AGE_FAIL_CALL="$fail_call" \
  RESTORE_TEST_RCLONE_FAIL_MATCH="$rclone_fail_match" \
  RESTORE_TEST_PREVIEW_MISMATCH="$preview_mismatch" \
  RESTORE_TEST_REDIRECT_ESCAPE="$redirect_escape" \
  RESTORE_TEST_IDENTITY_FD=9 RESTORE_TEST_CLOSURE="$FIXTURE/closure.psv" \
  RESTORE_TEST_HISTORICAL_REVISION="$HISTORICAL_REVISION" RESTORE_TEST_PROJECT_ID="$PROJECT_ID" \
  RESTORE_TEST_LOCAL_ASSET="$LOCAL_ASSET" RESTORE_TEST_COS_ASSET="$COS_ASSET" \
  RESTORE_TEST_PUBLIC_ASSET="$release_parent/public-assets/assets/public.js" \
  RESTORE_TEST_ADMIN_ASSET="$release_parent/admin/assets/admin.js" \
  RESTORE_TEST_LOCAL_VARIANT="$FIXTURE/local-source/variants/local.png" \
  RESTORE_TEST_COS_VARIANT="$FIXTURE/cos-history.png" \
  bash -c 'exec 9<"$1"; shift; exec "$@" --age-identity-fd 9' _ \
    "$FIXTURE/offline-age-identity" "$DRILL" \
    --root "$root" --drill-id "$drill_id" --set-id "$SET_ID" \
    --historical-revision "$HISTORICAL_REVISION"
}

success_root="/srv/portfolio-restore/contract-$DRILL_ID"
override_root="/srv/portfolio-restore/contract-$OVERRIDE_DRILL_ID"
for override in \
  'SPRING_APPLICATION_JSON={"portfolio.storage.cos.bucket":"prod-media-1250000000"}' \
  'JAVA_TOOL_OPTIONS=-Dportfolio.storage.cos.bucket=prod-media-1250000000' \
  'PORTFOLIO_STORAGE_COS_BUCKET=prod-media-1250000000'; do
  expect_failure env-override 'restore application env must contain exactly the reviewed key allowlist' \
    run_drill "$OVERRIDE_DRILL_ID" "$override_root" 0 '' "$override"
  [[ ! -e "/run/portfolio-restore-secrets/$OVERRIDE_DRILL_ID" ]] ||
    fail 'rejected application override left drill credentials behind'
done

run_rclone_polluted() {
  export RCLONE_CONFIG_DRILL_TYPE=s3
  export RCLONE_CONFIG_DRILL_ENDPOINT=https://prod-media-1250000000.cos.ap-guangzhou.myqcloud.com
  local status=0
  run_drill "$OVERRIDE_DRILL_ID" "$override_root" 0 || status=$?
  unset RCLONE_CONFIG_DRILL_TYPE RCLONE_CONFIG_DRILL_ENDPOINT
  return "$status"
}
expect_failure rclone-env 'caller-supplied RCLONE_* environment is forbidden' run_rclone_polluted
[[ ! -s "$COMMAND_LOG" ]] || fail 'RCLONE_* override reached a Docker, age, curl, or rclone boundary'
[[ ! -e "/run/portfolio-restore-secrets/$OVERRIDE_DRILL_ID" ]] ||
  fail 'rejected RCLONE_* override left drill credentials behind'

expect_failure secret-directory-mode 'drill secret directory must be owner-only' \
  run_drill "$OVERRIDE_DRILL_ID" "$override_root" 0 '' '' '' 0755
[[ ! -e "/run/portfolio-restore-secrets/$OVERRIDE_DRILL_ID" ]] ||
  fail 'world-readable secret directory was not destroyed'

expect_failure credential-window 'drill cloud credential must have 15-60 minutes of remaining lifetime' \
  run_drill "$OVERRIDE_DRILL_ID" "$override_root" 0 '' '' '' 0700 '' false '10 minutes'
[[ ! -e "/run/portfolio-restore-secrets/$OVERRIDE_DRILL_ID" ]] ||
  fail 'rejected short credential window left drill credentials behind'

expect_failure identity-dd 'offline age identity could not be copied into drill tmpfs' \
  run_drill "$DD_FAILURE_DRILL_ID" "/srv/portfolio-restore/contract-$DD_FAILURE_DRILL_ID" \
    0 '' '' '' 0700 "$DD_FAIL_BIN"
[[ ! -e "/run/portfolio-restore-secrets/$DD_FAILURE_DRILL_ID" ]] ||
  fail 'partial age identity target survived dd failure'
expect_failure identity-chmod 'offline age identity could not be protected in drill tmpfs' \
  run_drill "$CHMOD_FAILURE_DRILL_ID" "/srv/portfolio-restore/contract-$CHMOD_FAILURE_DRILL_ID" \
    0 '' '' '' 0700 "$CHMOD_FAIL_BIN"
[[ ! -e "/run/portfolio-restore-secrets/$CHMOD_FAILURE_DRILL_ID" ]] ||
  fail 'copied age identity target survived chmod failure'

mkdir -m 0700 "$override_root"
chown 65534:65534 "$override_root"
expect_failure foreign-precreated-root 'restore root must not exist before atomic creation' \
  run_drill "$OVERRIDE_DRILL_ID" "$override_root" 0
[[ ! -e "/run/portfolio-restore-secrets/$OVERRIDE_DRILL_ID" ]] ||
  fail 'foreign pre-created restore root failure left credentials behind'
rm -rf --one-file-system -- "$override_root"

early_failure_root="/srv/portfolio-restore/contract-$EARLY_FAILURE_DRILL_ID"
expect_failure early-backup 'verified backup set acquisition failed' \
  run_drill "$EARLY_FAILURE_DRILL_ID" "$early_failure_root" 0 \
    "sets/$SET_ID/set-manifest.json"
[[ ! -e "/run/portfolio-restore-secrets/$EARLY_FAILURE_DRILL_ID" ]] ||
  fail 'early backup failure left drill credentials behind'
early_report="$REMOTE/backup/backup-store-1250000000/production/drill-reports/$EARLY_FAILURE_DRILL_ID/report.json"
jq -e '.status == "FAILED" and .errorCategory == "BACKUP_VERIFICATION_FAILED"' "$early_report" >/dev/null ||
  fail 'early backup failure did not persist a redacted remote report'
upload_line="$(grep -n -m1 'rclone:report-uploader.rclone.conf:copyto' "$COMMAND_LOG" | cut -d: -f1)"
readback_line="$(grep -n -m1 'rclone:backup-verifier.rclone.conf:copyto.*drill-reports' "$COMMAND_LOG" | cut -d: -f1)"
record_line="$(grep -n -m1 'production-record' "$COMMAND_LOG" | cut -d: -f1)"
[[ "$upload_line" =~ ^[0-9]+$ && "$readback_line" =~ ^[0-9]+$ && "$record_line" =~ ^[0-9]+$ &&
   "$upload_line" -lt "$readback_line" && "$readback_line" -lt "$record_line" ]] ||
  fail 'early failure evidence was not uploaded, read back, then recorded in order'

original_set_manifest="$WORK_DIRECTORY/original-set-manifest.json"
cp "$backup_set_remote/set-manifest.json" "$original_set_manifest"
jq -Sc '.blobs = .blobs[0:1] | .counts.distinctCosBlobs = 1 | .counts.cosVerificationSamples = 1' \
  "$original_set_manifest" >"$backup_set_remote/set-manifest.json"
printf '%s\n' "$(sha256sum "$backup_set_remote/set-manifest.json" | awk '{print $1}')" \
  >"$backup_set_remote/VERIFIED"
expect_failure missing-set-blob 'authoritative media manifest and immutable set manifest do not match exactly' \
  run_drill "$MISSING_BLOB_DRILL_ID" "/srv/portfolio-restore/contract-$MISSING_BLOB_DRILL_ID" 0
[[ ! -e "/run/portfolio-restore-secrets/$MISSING_BLOB_DRILL_ID" ]] ||
  fail 'missing set blob mismatch left drill credentials behind'

extra_blob_file="$WORK_DIRECTORY/extra-blob"
printf 'extra-contract-blob' >"$extra_blob_file"
extra_blob_sha="$(sha256sum "$extra_blob_file" | awk '{print $1}')"
cp "$extra_blob_file" "$backup_blob_remote/$extra_blob_sha"
jq -Sc --arg sha "$extra_blob_sha" --argjson size "$(stat -Lc '%s' "$extra_blob_file")" '
  .blobs += [{byteSize:$size,path:("blobs/"+$sha),sha256:$sha}] |
  .blobs |= sort_by(.path) |
  .counts.distinctCosBlobs = 3 |
  .counts.cosVerificationSamples = 3
' "$original_set_manifest" >"$backup_set_remote/set-manifest.json"
printf '%s\n' "$(sha256sum "$backup_set_remote/set-manifest.json" | awk '{print $1}')" \
  >"$backup_set_remote/VERIFIED"
expect_failure extra-set-blob 'authoritative media manifest and immutable set manifest do not match exactly' \
  run_drill "$EXTRA_BLOB_DRILL_ID" "/srv/portfolio-restore/contract-$EXTRA_BLOB_DRILL_ID" 0
[[ ! -e "/run/portfolio-restore-secrets/$EXTRA_BLOB_DRILL_ID" ]] ||
  fail 'extra set blob mismatch left drill credentials behind'
cp "$original_set_manifest" "$backup_set_remote/set-manifest.json"
printf '%s\n' "$(sha256sum "$backup_set_remote/set-manifest.json" | awk '{print $1}')" \
  >"$backup_set_remote/VERIFIED"

old_started="$(date -u -d '3 days ago' '+%Y-%m-%dT%H:%M:%SZ')"
old_finished="$(date -u -d '3 days ago + 1 minute' '+%Y-%m-%dT%H:%M:%SZ')"
jq -Sc --arg started "$old_started" --arg finished "$old_finished" \
  '.backupStartedAt=$started | .backupFinishedAt=$finished' \
  "$original_set_manifest" >"$backup_set_remote/set-manifest.json"
printf '%s\n' "$(sha256sum "$backup_set_remote/set-manifest.json" | awk '{print $1}')" \
  >"$backup_set_remote/VERIFIED"
expect_failure rpo-exceeded 'selected backup set exceeds the 24-hour RPO' \
  run_drill "$RPO_FAILURE_DRILL_ID" "/srv/portfolio-restore/contract-$RPO_FAILURE_DRILL_ID" 0
[[ ! -e "/run/portfolio-restore-secrets/$RPO_FAILURE_DRILL_ID" ]] ||
  fail 'RPO failure left drill credentials behind'
rpo_report="$REMOTE/backup/backup-store-1250000000/production/drill-reports/$RPO_FAILURE_DRILL_ID/report.json"
jq -e '.status == "FAILED" and .errorCategory == "RPO_EXCEEDED"' "$rpo_report" >/dev/null ||
  fail 'RPO failure did not persist a redacted remote report'
cp "$original_set_manifest" "$backup_set_remote/set-manifest.json"
printf '%s\n' "$(sha256sum "$backup_set_remote/set-manifest.json" | awk '{print $1}')" \
  >"$backup_set_remote/VERIFIED"

semantic_failure_root="/srv/portfolio-restore/contract-$SEMANTIC_FAILURE_DRILL_ID"
expect_failure semantic-position 'restored workspace semantic snapshot differs from immutable history' \
  run_drill "$SEMANTIC_FAILURE_DRILL_ID" "$semantic_failure_root" 0 '' '' position
[[ ! -e "/run/portfolio-restore-secrets/$SEMANTIC_FAILURE_DRILL_ID" ]] ||
  fail 'semantic historical mismatch left drill credentials behind'

redirect_failure_root="/srv/portfolio-restore/contract-$REDIRECT_FAILURE_DRILL_ID"
expect_failure redirect-origin 'COS redirect Location does not match the verified bucket, region, and object key' \
  run_drill "$REDIRECT_FAILURE_DRILL_ID" "$redirect_failure_root" 0 '' '' '' 0700 '' true
[[ ! -e "/run/portfolio-restore-secrets/$REDIRECT_FAILURE_DRILL_ID" ]] ||
  fail 'escaped COS redirect left drill credentials behind'

run_drill "$DRILL_ID" "$success_root" 0 '' >"$WORK_DIRECTORY/success.log"
assert_contains "$WORK_DIRECTORY/success.log" 'Restore drill completed with independently verified report and production record'
[[ "$(<"$AGE_COUNT")" == 3 ]] || fail 'the success drill did not perform exactly three decryptions'
[[ ! -e "/run/portfolio-restore-secrets/$DRILL_ID" ]] || fail 'success drill secrets were not destroyed'
for event in 'mapping-sql:apply' 'mapping-sql:verify' 'postgres-ready-wait' 'docker:route-start:' \
  'docker:content-plan:' 'docker:content-revision-snapshot:' 'docker:content-audit-after:' 'production-record' \
  'rclone:report-uploader.rclone.conf:copyto' 'rclone:backup-verifier.rclone.conf:copyto'; do
  assert_contains "$COMMAND_LOG" "$event"
done
report_remote="$REMOTE/backup/backup-store-1250000000/production/drill-reports/$DRILL_ID/report.json"
[[ -f "$report_remote" ]] || fail 'tracked report adapter did not upload the report'
jq -e --arg id "$DRILL_ID" '.drillId == $id and .status == "SUCCEEDED" and .errorCategory == "NONE"' \
  "$report_remote" >/dev/null || fail 'uploaded success report is invalid'

orphan_secret="/run/portfolio-restore-secrets/$DRILL_ID"
mkdir -m 0700 "$orphan_secret"
printf '%s\n' "$DRILL_ID" >"$orphan_secret/.portfolio-restore-secrets"
printf 'fixture=true\n' >"$orphan_secret/drill-cos.rclone.conf"
chmod 0600 "$orphan_secret"/* "$orphan_secret/.portfolio-restore-secrets"
expect_failure stopped-orphan 'Docker container resources remain after bounded cleanup' \
  env RESTORE_ROOT="$success_root" RESTORE_DRILL_ID="$DRILL_ID" \
    RESTORE_COMPOSE_PROJECT_NAME="portfolio-restore-$DRILL_ID" RESTORE_IDENTITY_DISPOSED=true \
    RESTORE_DOCKER_COMMAND="$BIN/docker" RESTORE_RCLONE_COMMAND="$BIN/rclone" \
    RESTORE_COMPOSE_FILE="$RESTORE_COMPOSE_YAML" RESTORE_DRILL_COS_REMOTE=drill:drill-media-1250000000 \
    RESTORE_TEST_COMMAND_LOG="$COMMAND_LOG" RESTORE_TEST_STOPPED_ORPHAN=true \
    "$RESTORE_DIRECTORY/adapters/cleanup-drill.sh" \
      --drill-id "$DRILL_ID" --prefix "drills/$DRILL_ID/" \
      --config "$orphan_secret/drill-cos.rclone.conf" --root "$success_root" \
      --remove-root false --purge-remote false
rm -rf -- "$orphan_secret"

failure_root="/srv/portfolio-restore/contract-$FAILURE_DRILL_ID"
expect_failure decrypt-failure 'Local media tar decryption failed' \
  run_drill "$FAILURE_DRILL_ID" "$failure_root" 2
[[ "$(<"$AGE_COUNT")" == 2 ]] || fail 'failure fixture did not stop at the second decrypt attempt'
[[ ! -e "/run/portfolio-restore-secrets/$FAILURE_DRILL_ID" ]] ||
  fail 'failed decryption left the copied identity or credentials behind'
assert_not_contains "$COMMAND_LOG" 'mapping-sql:apply'
assert_contains "$COMMAND_LOG" 'rclone:report-uploader.rclone.conf:copyto'
assert_not_contains "$COMMAND_LOG" 'rclone:drill-cos.rclone.conf:purge'

report_failure_root="/srv/portfolio-restore/contract-$REPORT_FAILURE_DRILL_ID"
expect_failure report-readback 'independent remote drill report verification failed' \
  run_drill "$REPORT_FAILURE_DRILL_ID" "$report_failure_root" 0 \
    "drill-reports/$REPORT_FAILURE_DRILL_ID/report.json"
[[ ! -e "/run/portfolio-restore-secrets/$REPORT_FAILURE_DRILL_ID" ]] ||
  fail 'report failure left short-lived credentials behind'
assert_not_contains "$COMMAND_LOG" 'rclone:drill-cos.rclone.conf:purge'
preserved_drill_prefix="$REMOTE/drill/drill-media-1250000000/drills/$REPORT_FAILURE_DRILL_ID"
[[ -d "$preserved_drill_prefix/blobs" && -n "$(find "$preserved_drill_prefix/blobs" -type f -print -quit)" ]] ||
  fail 'report read-back failure did not preserve the drill COS evidence prefix'

# Real PostgreSQL 17 proof: all migrations, the tracked closure query, the
# exact mapping SQL emitted by the adapter, runtime DML, and rejected DDL.
PG_CONTAINER="portfolio-restore-pg17-$RANDOM-$$"
"$REAL_DOCKER" run -d --name "$PG_CONTAINER" -e POSTGRES_PASSWORD=contract \
  -e POSTGRES_DB=portfolio "$POSTGRES_TEST_IMAGE" >/dev/null
deadline=$((SECONDS + 60))
until "$REAL_DOCKER" exec "$PG_CONTAINER" pg_isready -U postgres -d portfolio >/dev/null 2>&1; do
  ((SECONDS < deadline)) || fail 'PostgreSQL 17 fixture did not become ready'
  sleep 1
done
"$REAL_DOCKER" exec "$PG_CONTAINER" psql -X -v ON_ERROR_STOP=1 -U postgres -d portfolio \
  -c 'CREATE ROLE portfolio_runtime_access NOLOGIN' >/dev/null
for version in {1..11}; do
  migration="$(find "$REPOSITORY_ROOT/backend-parent/portfolio-server/src/main/resources/db/migration" \
    -maxdepth 1 -type f -name "V${version}__*.sql" -print -quit)"
  [[ -n "$migration" ]] || fail "migration V$version is missing"
  "$REAL_DOCKER" exec -e PGOPTIONS='-c search_path=portfolio,public' -i "$PG_CONTAINER" \
    psql -X -v ON_ERROR_STOP=1 -U postgres -d portfolio <"$migration" >/dev/null
done
"$REAL_DOCKER" exec -i "$PG_CONTAINER" psql -X -v ON_ERROR_STOP=1 -U postgres -d portfolio >/dev/null <<SQL
CREATE TABLE portfolio.flyway_schema_history(installed_rank integer PRIMARY KEY);
INSERT INTO portfolio.admin_user(id,singleton_key,username,password_hash,status,totp_key_version,totp_nonce,totp_ciphertext)
VALUES ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',true,'proof-admin','proof-hash','ACTIVE',1,decode('000000000000000000000000','hex'),decode('00','hex'));
INSERT INTO portfolio.media_asset(id,provider,bucket,region,object_key,original_filename,mime_type,byte_size,sha256,status)
VALUES
('$LOCAL_ASSET','LOCAL',NULL,NULL,'originals/local.bin','local.bin','application/octet-stream',${#local_original},'$local_original_sha','READY'),
('$COS_ASSET','TENCENT_COS','prod-media-1250000000','ap-guangzhou','originals/cos.bin','cos.bin','application/octet-stream',${#cos_original},'$cos_original_sha','READY'),
('$UNSELECTED_COS_ASSET','TENCENT_COS','prod-media-1250000000','ap-guangzhou','legacy/unselected.bin','unselected.bin','application/octet-stream',17,repeat('7',64),'READY');
INSERT INTO portfolio.media_variant(id,asset_id,variant_name,format,object_key,mime_type,byte_size,sha256,status)
VALUES
('11111111-1111-4111-9111-111111111111','$LOCAL_ASSET','public','PNG','variants/local.png','image/png',${#local_variant},'$local_variant_sha','READY'),
('22222222-2222-4222-9222-222222222222','$COS_ASSET','history','PNG','variants/cos.png','image/png',${#cos_variant},'$cos_variant_sha','READY'),
('$UNSELECTED_COS_VARIANT','$UNSELECTED_COS_ASSET','legacy','PNG','legacy/unselected.png','image/png',19,repeat('8',64),'READY');
INSERT INTO portfolio.content_revision(id,aggregate_type,aggregate_id,version,snapshot_schema_version,snapshot,checksum,published_by,published_at)
VALUES
('$HISTORICAL_REVISION','PROJECT','$PROJECT_ID',1,1,'{}',repeat('5',64),'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',clock_timestamp()-interval '2 days'),
('77777777-7777-4777-8777-777777777777','PROJECT','$PROJECT_ID',2,1,'{}',repeat('6',64),'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',clock_timestamp()-interval '1 day');
INSERT INTO portfolio.publication(aggregate_type,aggregate_id,status,current_revision_id,current_slug,version,published_at)
VALUES('PROJECT','$PROJECT_ID','PUBLISHED','77777777-7777-4777-8777-777777777777','proof',2,clock_timestamp());
INSERT INTO portfolio.revision_media_reference(revision_id,asset_id,variant_name,usage)
VALUES
('$HISTORICAL_REVISION','$LOCAL_ASSET','public','GALLERY'),
('$HISTORICAL_REVISION','$COS_ASSET','history','GALLERY'),
('77777777-7777-4777-8777-777777777777','$LOCAL_ASSET','public','GALLERY');
CREATE ROLE portfolio_runtime_drill LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE ROLE portfolio_migrator_drill LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE;
SQL
closure_output="$WORK_DIRECTORY/pg17-closure.psv"
"$REAL_DOCKER" exec -i "$PG_CONTAINER" psql -X -A -t -q -F '|' -v ON_ERROR_STOP=1 \
  -U postgres -d portfolio --set="historical_revision_ids=$HISTORICAL_REVISION" -f - \
  <"$CLOSURE_SQL" >"$closure_output"
cmp -s "$closure_output" "$FIXTURE/closure.psv" || fail 'PostgreSQL 17 closure output differs from the verifier contract'

cat >"$BIN/docker-pg17" <<'PGDOCKER'
#!/usr/bin/env bash
set -euo pipefail
joined=" $* "
[[ "$joined" == *" --project-name portfolio-restore-$RESTORE_DRILL_ID "* ]]
expected=''; bucket=''; region=''; prefix=''; forbidden_enabled='false'
production_bucket=''; production_region=''; backup_bucket=''; backup_region=''
for argument in "$@"; do
  case "$argument" in
    --set=expected_count=*) expected="${argument##*=}" ;;
    --set=drill_bucket=*) bucket="${argument##*=}" ;;
    --set=drill_region=*) region="${argument##*=}" ;;
    --set=drill_prefix=*) prefix="${argument##*=}" ;;
    --set=forbidden_checks_enabled=*) forbidden_enabled="${argument##*=}" ;;
    --set=forbidden_production_bucket=*) production_bucket="${argument##*=}" ;;
    --set=forbidden_production_region=*) production_region="${argument##*=}" ;;
    --set=forbidden_backup_bucket=*) backup_bucket="${argument##*=}" ;;
    --set=forbidden_backup_region=*) backup_region="${argument##*=}" ;;
  esac
done
exec "$RESTORE_TEST_REAL_DOCKER" exec -i "$RESTORE_TEST_PG_CONTAINER" psql -X -v ON_ERROR_STOP=1 \
  -U postgres -d portfolio --set="expected_count=$expected" --set="drill_bucket=$bucket" \
  --set="drill_region=$region" --set="drill_prefix=$prefix" \
  --set="forbidden_checks_enabled=$forbidden_enabled" \
  --set="forbidden_production_bucket=$production_bucket" \
  --set="forbidden_production_region=$production_region" \
  --set="forbidden_backup_bucket=$backup_bucket" \
  --set="forbidden_backup_region=$backup_region" -f -
PGDOCKER
chmod 0755 "$BIN/docker-pg17"
run_pg17_mapping() {
  env \
    RESTORE_ROOT="$success_root" RESTORE_DRILL_ID="$DRILL_ID" \
    RESTORE_COMPOSE_PROJECT_NAME="portfolio-restore-$DRILL_ID" RESTORE_IDENTITY_DISPOSED=true \
    RESTORE_DOCKER_COMMAND="$BIN/docker-pg17" RESTORE_TEST_PG_CONTAINER="$PG_CONTAINER" \
    RESTORE_TEST_REAL_DOCKER="$REAL_DOCKER" \
    RESTORE_COMPOSE_FILE="$RESTORE_COMPOSE_YAML" RESTORE_DRILL_COS_BUCKET=drill-media-1250000000 \
    RESTORE_DRILL_COS_REGION=ap-shanghai \
    PRODUCTION_COS_LOCATIONS=production-account@prod-media-1250000000@ap-guangzhou \
    BACKUP_DESTINATION_LOCATION=backup-account@backup-store-1250000000@ap-beijing \
    "$RESTORE_DIRECTORY/adapters/apply-media-mapping.sh" "$@"
}
mapping_arguments=(--mapping "$success_root/work/media-mapping.psv" --expected-count 4)
verification_arguments=(
  "${mapping_arguments[@]}"
  --forbid-production-locations production-account@prod-media-1250000000@ap-guangzhou
  --forbid-backup-location backup-account@backup-store-1250000000@ap-beijing
  --local-root /var/lib/portfolio/media
  --drill-prefix "drills/$DRILL_ID/"
)

grep -F "$UNSELECTED_COS_ASSET" "$success_root/work/media-mapping.psv" >/dev/null &&
  fail 'unselected legacy COS asset unexpectedly entered the selected closure mapping'
unselected_before="$("$REAL_DOCKER" exec "$PG_CONTAINER" psql -X -A -t -U postgres -d portfolio -c \
  "SELECT asset.bucket||'|'||asset.region||'|'||asset.object_key||'|'||variant.object_key
   FROM portfolio.media_asset AS asset
   JOIN portfolio.media_variant AS variant ON variant.asset_id=asset.id
   WHERE asset.id='$UNSELECTED_COS_ASSET'")"
[[ "$unselected_before" == 'prod-media-1250000000|ap-guangzhou|legacy/unselected.bin|legacy/unselected.png' ]] ||
  fail 'legacy COS isolation regression fixture did not begin at the production location'

run_pg17_mapping apply "${mapping_arguments[@]}" >/dev/null
mapped_location="$("$REAL_DOCKER" exec "$PG_CONTAINER" psql -X -A -t -U postgres -d portfolio \
  -c "SELECT bucket||'|'||region||'|'||object_key FROM portfolio.media_asset WHERE id='$COS_ASSET'")"
[[ "$mapped_location" == "drill-media-1250000000|ap-shanghai|drills/$DRILL_ID/blobs/$cos_original_sha" ]] ||
  fail 'tracked mapping SQL did not isolate the real PostgreSQL 17 row'
unselected_after="$("$REAL_DOCKER" exec "$PG_CONTAINER" psql -X -A -t -U postgres -d portfolio -c \
  "SELECT asset.bucket||'|'||asset.region||'|'||asset.object_key||'|'||variant.object_key
   FROM portfolio.media_asset AS asset
   JOIN portfolio.media_variant AS variant ON variant.asset_id=asset.id
   WHERE asset.id='$UNSELECTED_COS_ASSET'")"
[[ "$unselected_after" == "drill-media-1250000000|ap-shanghai|drills/$DRILL_ID/quarantine/assets/$UNSELECTED_COS_ASSET/original|drills/$DRILL_ID/quarantine/assets/$UNSELECTED_COS_ASSET/variants/$UNSELECTED_COS_VARIANT" ]] ||
  fail 'closure-external legacy COS asset and variant were not deterministically quarantined'
run_pg17_mapping verify "${verification_arguments[@]}" >/dev/null

"$REAL_DOCKER" exec "$PG_CONTAINER" psql -X -v ON_ERROR_STOP=1 -U postgres -d portfolio -c \
  "UPDATE portfolio.media_asset SET bucket='prod-media-1250000000',region='ap-guangzhou',object_key='production/legacy.bin' WHERE id='$UNSELECTED_COS_ASSET'" >/dev/null
expect_failure pg17-production-location 'drill media mapping verify transaction failed' \
  run_pg17_mapping verify "${verification_arguments[@]}"
run_pg17_mapping apply "${mapping_arguments[@]}" >/dev/null

"$REAL_DOCKER" exec "$PG_CONTAINER" psql -X -v ON_ERROR_STOP=1 -U postgres -d portfolio -c \
  "UPDATE portfolio.media_asset SET bucket='backup-store-1250000000',region='ap-beijing',object_key='production/sets/legacy.bin' WHERE id='$UNSELECTED_COS_ASSET'" >/dev/null
expect_failure pg17-backup-location 'drill media mapping verify transaction failed' \
  run_pg17_mapping verify "${verification_arguments[@]}"
run_pg17_mapping apply "${mapping_arguments[@]}" >/dev/null

"$REAL_DOCKER" exec "$PG_CONTAINER" psql -X -v ON_ERROR_STOP=1 -U postgres -d portfolio -c \
  "UPDATE portfolio.media_variant SET object_key='production/legacy-variant.png' WHERE id='$UNSELECTED_COS_VARIANT'" >/dev/null
expect_failure pg17-production-variant 'drill media mapping verify transaction failed' \
  run_pg17_mapping verify "${verification_arguments[@]}"
run_pg17_mapping apply "${mapping_arguments[@]}" >/dev/null
run_pg17_mapping verify "${verification_arguments[@]}" >/dev/null

grant_sql="$WORK_DIRECTORY/runtime-grants.sql"
awk '/^REVOKE ALL ON SCHEMA portfolio FROM PUBLIC;/{copy=1} copy{if ($0 == "SQL") exit; print}' \
  "$DRILL" >"$grant_sql"
"$REAL_DOCKER" exec -i "$PG_CONTAINER" psql -X -v ON_ERROR_STOP=1 -U postgres -d portfolio \
  --set=runtime_user=portfolio_runtime_drill --set=migrator_user=portfolio_migrator_drill \
  -f - <"$grant_sql" >/dev/null
privilege_proof="$("$REAL_DOCKER" exec "$PG_CONTAINER" psql -X -A -t -U postgres -d portfolio -c \
  "SELECT has_table_privilege('portfolio_runtime_drill','portfolio.publication','SELECT,INSERT,UPDATE')::text||'|'||has_schema_privilege('portfolio_runtime_drill','portfolio','CREATE')::text")"
[[ "$privilege_proof" == 'true|false' ]] || fail 'runtime role is not DML-yes/DDL-no on PostgreSQL 17'
acl_count="$("$REAL_DOCKER" exec -i "$PG_CONTAINER" psql -X -A -t -U postgres -d portfolio <<'SQL'
WITH target AS (
  SELECT oid FROM pg_roles WHERE rolname = 'portfolio_runtime_access'
), grants AS (
  SELECT acl.grantee
  FROM pg_class AS relation
  JOIN pg_namespace AS namespace ON namespace.oid = relation.relnamespace
  CROSS JOIN LATERAL aclexplode(relation.relacl) AS acl
  WHERE namespace.nspname = 'portfolio' AND relation.relacl IS NOT NULL
  UNION ALL
  SELECT acl.grantee
  FROM pg_attribute AS attribute
  JOIN pg_class AS relation ON relation.oid = attribute.attrelid
  JOIN pg_namespace AS namespace ON namespace.oid = relation.relnamespace
  CROSS JOIN LATERAL aclexplode(attribute.attacl) AS acl
  WHERE namespace.nspname = 'portfolio' AND attribute.attnum > 0 AND attribute.attacl IS NOT NULL
  UNION ALL
  SELECT acl.grantee
  FROM pg_proc AS function
  JOIN pg_namespace AS namespace ON namespace.oid = function.pronamespace
  CROSS JOIN LATERAL aclexplode(function.proacl) AS acl
  WHERE namespace.nspname = 'portfolio' AND function.proacl IS NOT NULL
  UNION ALL
  SELECT acl.grantee
  FROM pg_namespace AS namespace
  CROSS JOIN LATERAL aclexplode(namespace.nspacl) AS acl
  WHERE namespace.nspname = 'portfolio' AND namespace.nspacl IS NOT NULL
)
SELECT count(*) FROM grants, target WHERE grants.grantee = target.oid;
SQL
)"
[[ "$acl_count" == 275 ]] || fail 'PostgreSQL 17 normalized runtime ACL count differs from the reviewed 275 grants'
for ddl in \
  'CREATE TABLE portfolio.contract_forbidden(id integer)' \
  'ALTER TABLE portfolio.publication ADD COLUMN contract_forbidden integer' \
  'DROP TABLE portfolio.publication'; do
  if printf 'SET ROLE portfolio_runtime_drill; %s;\n' "$ddl" | \
      "$REAL_DOCKER" exec -i "$PG_CONTAINER" psql -X -v ON_ERROR_STOP=1 -U postgres -d portfolio \
      >/dev/null 2>&1; then
    fail "runtime role unexpectedly executed DDL: $ddl"
  fi
done

# Parse and validate the Compose topology with Docker Compose itself.
compose_render="$WORK_DIRECTORY/restore-compose.rendered.yml"
: >"$WORK_DIRECTORY/postgres.env"
: >"$WORK_DIRECTORY/app.env"
mkdir -p "$WORK_DIRECTORY/local-media"
printf 'contract-certificate\n' >"$WORK_DIRECTORY/tls.crt"
printf 'contract-key\n' >"$WORK_DIRECTORY/tls.key"
RESTORE_COMPOSE_PROJECT_NAME="portfolio-restore-$DRILL_ID" RESTORE_DRILL_ID="$DRILL_ID" \
RESTORE_POSTGRES_IMAGE="$postgres_tag" RESTORE_API_IMAGE="$api_tag" \
RESTORE_NGINX_IMAGE="sha256:$(printf 'c%.0s' {1..64})" \
RESTORE_POSTGRES_ENV="$WORK_DIRECTORY/postgres.env" RESTORE_APP_ENV="$WORK_DIRECTORY/app.env" \
RESTORE_LOCAL_MEDIA_ROOT="$WORK_DIRECTORY/local-media" \
RESTORE_NGINX_CONFIG_DIR="$success_root/release/runtime/nginx" \
RESTORE_ADMIN_ASSETS_ROOT="$success_root/release/admin" \
RESTORE_PUBLIC_ASSETS_ROOT="$success_root/release/public-assets" \
RESTORE_TLS_CERTIFICATE="$WORK_DIRECTORY/tls.crt" RESTORE_TLS_PRIVATE_KEY="$WORK_DIRECTORY/tls.key" \
  "$REAL_DOCKER" compose -f "$RESTORE_COMPOSE_YAML" config >"$compose_render"
[[ "$(grep -Fc 'pull_policy: never' "$compose_render")" -eq 3 ]] ||
  fail 'rendered Compose topology can pull a restore image'

# Exercise the production default resolver (no Compose path override): marker
# -> release.json -> immutable release-local ops/Compose.
production_release="/opt/portfolio/releases/$RELEASE_ID"
mkdir -p "$production_release/ops/deploy" /etc/portfolio
printf '%s\n' "$RELEASE_ID" >/opt/portfolio/current-release
jq -n --arg id "$RELEASE_ID" '{releaseId:$id}' >"$production_release/release.json"
cp "$REPOSITORY_ROOT/deploy/docker-compose.prod.yml" \
  "$production_release/ops/deploy/docker-compose.prod.yml"
printf 'PORTFOLIO_RELEASE_ID=%s\n' "$RELEASE_ID" >/etc/portfolio/release.env
chmod 0755 /opt/portfolio /opt/portfolio/releases "$production_release" \
  "$production_release/ops" "$production_release/ops/deploy" /etc/portfolio
chmod 0600 /opt/portfolio/current-release "$production_release/release.json" \
  "$production_release/ops/deploy/docker-compose.prod.yml" /etc/portfolio/release.env
env -u RESTORE_PRODUCTION_COMPOSE_FILE \
  RESTORE_PRODUCTION_RELEASE_ENV=/etc/portfolio/release.env \
  RESTORE_DOCKER_COMMAND="$BIN/docker" RESTORE_TEST_COMMAND_LOG="$COMMAND_LOG" \
  "$RESTORE_DIRECTORY/record-drill-result.sh" \
    --drill-id "$DRILL_ID" --status SUCCEEDED \
    --started-at 2026-07-18T00:00:00Z --finished-at 2026-07-18T00:01:00Z \
    --report-sha "$set_manifest_sha" --category NONE >/dev/null
assert_contains "$COMMAND_LOG" 'production-record'

# Validate and serve the adapter-generated Nginx config in a separate local
# container, then compare actual TLS route bytes. No external network is used.
NGINX_CONTAINER="portfolio-restore-nginx-$RANDOM-$$"
"$REAL_DOCKER" run -d --name "$NGINX_CONTAINER" --add-host api:127.0.0.1 \
  portfolio-ops-tools:ubuntu22 sleep infinity >/dev/null
"$REAL_DOCKER" exec "$NGINX_CONTAINER" mkdir -p /srv/portfolio-release /run/portfolio-restore-secrets
"$REAL_DOCKER" cp "$success_root/release/runtime/nginx/." "$NGINX_CONTAINER:/etc/nginx/"
"$REAL_DOCKER" cp "$success_root/release/admin" "$NGINX_CONTAINER:/srv/portfolio-release/admin"
"$REAL_DOCKER" cp "$success_root/release/public-assets" "$NGINX_CONTAINER:/srv/portfolio-release/public-assets"
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj '/CN=restore.test.invalid' \
  -addext 'subjectAltName=DNS:restore.test.invalid' -keyout "$WORK_DIRECTORY/tls.key" \
  -out "$WORK_DIRECTORY/tls.crt" >/dev/null 2>&1
"$REAL_DOCKER" cp "$WORK_DIRECTORY/tls.crt" "$NGINX_CONTAINER:/run/portfolio-restore-secrets/tls.crt"
"$REAL_DOCKER" cp "$WORK_DIRECTORY/tls.key" "$NGINX_CONTAINER:/run/portfolio-restore-secrets/tls.key"
"$REAL_DOCKER" exec "$NGINX_CONTAINER" nginx -t >/dev/null
"$REAL_DOCKER" exec "$NGINX_CONTAINER" nginx
"$REAL_DOCKER" exec "$NGINX_CONTAINER" sh -c \
  "ps -eo user,comm | awk '\$2 == \"nginx\" && \$1 != \"root\" {found=1} END {exit !found}'"
nginx_fetch() {
  local path="$1" body="$2" headers="$3"
  "$REAL_DOCKER" exec "$NGINX_CONTAINER" curl --fail --silent --show-error \
    --cacert /run/portfolio-restore-secrets/tls.crt \
    --resolve restore.test.invalid:443:127.0.0.1 \
    --dump-header - --output - "https://restore.test.invalid$path" \
    >"$WORK_DIRECTORY/nginx-response"
  sed -n '/^\r$/,$p' "$WORK_DIRECTORY/nginx-response" | tail -n +2 >"$body"
  sed -n '1,/^\r$/p' "$WORK_DIRECTORY/nginx-response" >"$headers"
}
assert_mime() {
  local headers="$1" expected="$2"
  tr -d '\r' <"$headers" | grep -i -E "^content-type: ${expected}([;]|$)" >/dev/null ||
    fail "actual Nginx response did not use $expected"
}
nginx_fetch /assets/public.js "$WORK_DIRECTORY/public.js" "$WORK_DIRECTORY/public.js.headers"
nginx_fetch /assets/public.css "$WORK_DIRECTORY/public.css" "$WORK_DIRECTORY/public.css.headers"
nginx_fetch /admin/assets/admin.js "$WORK_DIRECTORY/admin.js" "$WORK_DIRECTORY/admin.js.headers"
nginx_fetch /admin/assets/admin.css "$WORK_DIRECTORY/admin.css" "$WORK_DIRECTORY/admin.css.headers"
nginx_fetch /admin/ "$WORK_DIRECTORY/admin.index" "$WORK_DIRECTORY/admin.index.headers"
cmp -s "$WORK_DIRECTORY/public.js" "$success_root/release/public-assets/assets/public.js" ||
  fail 'actual public JS route bytes differ from the verified release'
cmp -s "$WORK_DIRECTORY/public.css" "$success_root/release/public-assets/assets/public.css" ||
  fail 'actual public CSS route bytes differ from the verified release'
cmp -s "$WORK_DIRECTORY/admin.js" "$success_root/release/admin/assets/admin.js" ||
  fail 'actual administrator JS route bytes differ from the verified release'
cmp -s "$WORK_DIRECTORY/admin.css" "$success_root/release/admin/assets/admin.css" ||
  fail 'actual administrator CSS route bytes differ from the verified release'
cmp -s "$WORK_DIRECTORY/admin.index" "$success_root/release/admin/index.html" ||
  fail 'actual /admin/ route bytes differ from the verified release'
assert_mime "$WORK_DIRECTORY/public.js.headers" 'application/javascript'
assert_mime "$WORK_DIRECTORY/admin.js.headers" 'application/javascript'
assert_mime "$WORK_DIRECTORY/public.css.headers" 'text/css'
assert_mime "$WORK_DIRECTORY/admin.css.headers" 'text/css'
assert_mime "$WORK_DIRECTORY/admin.index.headers" 'text/html'

SYSTEMCTL_LOG="$WORK_DIRECTORY/systemctl.log"
cat >"$BIN/systemctl" <<'SYSTEMCTL'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"$RESTORE_TEST_SYSTEMCTL_LOG"
case "$1" in
  daemon-reload|enable|is-enabled|is-active|list-timers|status) exit 0 ;;
  *) exit 64 ;;
esac
SYSTEMCTL
chmod 0755 "$BIN/systemctl"
reminder_target="$WORK_DIRECTORY/systemd-target"
reminder_source="$WORK_DIRECTORY/reminder-source"
mkdir -m 0700 "$reminder_source"
install -o root -g root -m 0644 -- "$REMINDER_SERVICE" "$reminder_source/portfolio-restore-reminder.service"
install -o root -g root -m 0644 -- "$REMINDER_TIMER" "$reminder_source/portfolio-restore-reminder.timer"
RESTORE_REMINDER_CONTRACT_FIXTURE=true \
RESTORE_REMINDER_SOURCE_DIRECTORY="$reminder_source" \
RESTORE_REMINDER_TARGET_DIRECTORY="$reminder_target" \
RESTORE_SYSTEMCTL_COMMAND="$BIN/systemctl" RESTORE_TEST_SYSTEMCTL_LOG="$SYSTEMCTL_LOG" \
  "$REMINDER_INSTALLER" >"$WORK_DIRECTORY/reminder-install.log"
[[ "$(stat -Lc '%u:%g:%a' "$reminder_target/portfolio-restore-reminder.service")" == 0:0:644 &&
   "$(stat -Lc '%u:%g:%a' "$reminder_target/portfolio-restore-reminder.timer")" == 0:0:644 ]] ||
  fail 'reminder installer did not install root-owned mode 0644 units'
assert_contains "$SYSTEMCTL_LOG" 'daemon-reload'
assert_contains "$SYSTEMCTL_LOG" 'enable --now portfolio-restore-reminder.timer'
assert_contains "$SYSTEMCTL_LOG" 'is-enabled --quiet portfolio-restore-reminder.timer'
assert_contains "$SYSTEMCTL_LOG" 'is-active --quiet portfolio-restore-reminder.timer'

systemd-analyze verify \
  "$reminder_source/portfolio-restore-reminder.service" \
  "$reminder_source/portfolio-restore-reminder.timer" >/dev/null
calendar_output="$(systemd-analyze calendar --iterations=4 'Mon *-01,04,07,10-01..07 01:00:00 UTC')"
grep -F '01:00:00 UTC' <<<"$calendar_output" >/dev/null ||
  fail 'quarterly reminder is not unambiguously UTC on Ubuntu 22.04'

printf '%s\n' 'PASS: tracked restore adapters, isolation, PG17 closure/full-database COS quarantine/mapping/ACL, Nginx bytes, report readback, credential destruction, cleanup, and UTC reminder'
