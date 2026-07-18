#!/usr/bin/env bash
# shellcheck disable=SC2016
set -euo pipefail
set +x
umask 077

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
readonly REPOSITORY_ROOT
readonly RENDER="$REPOSITORY_ROOT/deploy/scripts/render-nginx.sh"
readonly PREFLIGHT="$REPOSITORY_ROOT/deploy/scripts/preflight.sh"

WORK_DIRECTORY=''

fail() {
  printf 'Nginx contract failed: %s\n' "$1" >&2
  exit 1
}

cleanup() {
  if [[ -n "$WORK_DIRECTORY" && -d "$WORK_DIRECTORY" ]]; then
    rm -rf -- "$WORK_DIRECTORY"
  fi
}
trap cleanup EXIT HUP INT TERM

assert_contains() {
  local file="$1"
  local text="$2"
  grep -F -- "$text" "$file" >/dev/null ||
    fail "$(basename "$file") does not contain: $text"
}

assert_not_contains() {
  local file="$1"
  local text="$2"
  if grep -F -- "$text" "$file" >/dev/null; then
    fail "$(basename "$file") unexpectedly contains: $text"
  fi
}

expect_failure() {
  local label="$1"
  local expected="$2"
  shift 2
  local output="$WORK_DIRECTORY/failure-${label}.log"
  if "$@" >"$output" 2>&1; then
    fail "$label unexpectedly succeeded"
  fi
  assert_contains "$output" "$expected"
}

WORK_DIRECTORY="$(mktemp -d)"
readonly WORK_DIRECTORY

render_environment=(
  'PUBLIC_HOSTS=yychainsaw.xyz,www.yychainsaw.xyz'
  'API_LOOPBACK=127.0.0.1:18080'
  'MEDIA_ORIGIN=https://media.yychainsaw.xyz'
  'VIDEO_FRAME_ORIGINS=https://player.bilibili.com,https://www.youtube-nocookie.com'
  'TLS_CERTIFICATE=/www/server/panel/vhost/cert/yychainsaw.xyz/fullchain.pem'
  'TLS_CERTIFICATE_KEY=/www/server/panel/vhost/cert/yychainsaw.xyz/privkey.pem'
  'NGINX_CONF=/www/server/nginx/conf/nginx.conf'
  'ICP_NUMBER=赣ICP备2026000000号-1'
)

rendered="$WORK_DIRECTORY/rendered"
env "${render_environment[@]}" bash "$RENDER" "$rendered"

site="$rendered/portfolio-site.conf"
proxy="$rendered/portfolio-proxy.conf"
headers="$rendered/portfolio-security-headers.conf"
if [[ ! -f "$site" || ! -f "$proxy" || ! -f "$headers" ]]; then
  fail 'render did not create all three generated includes'
fi

assert_contains "$REPOSITORY_ROOT/deploy/nginx/portfolio-http.conf" \
  'limit_req_zone $binary_remote_addr zone=portfolio_login:10m rate=5r/m;'
assert_contains "$REPOSITORY_ROOT/deploy/nginx/portfolio-http.conf" \
  'limit_req_zone $binary_remote_addr zone=portfolio_contact:10m rate=5r/m;'
assert_contains "$REPOSITORY_ROOT/deploy/nginx/portfolio-http.conf" \
  'limit_req_zone $binary_remote_addr zone=portfolio_events:10m rate=60r/m;'
assert_contains "$REPOSITORY_ROOT/deploy/nginx/baota.env.example" \
  'PORTFOLIO_LOCAL_HOST_ROOT=/var/lib/docker/volumes/portfolio-local-media/_data'
assert_not_contains "$REPOSITORY_ROOT/deploy/nginx/baota.env.example" \
  '/var/lib/docker/volumes/portfolio_local-media/_data'

assert_contains "$site" 'alias /opt/portfolio/assets/;'
assert_contains "$site" 'Cache-Control "public, max-age=31536000, immutable" always;'
assert_contains "$site" 'location = /admin { return 308 /admin/; }'
assert_contains "$site" 'alias /opt/portfolio/current-admin/assets/;'
assert_contains "$site" 'alias /opt/portfolio/current-admin/;'
assert_contains "$site" 'try_files $uri $uri/ /admin/index.html;'
assert_contains "$site" 'location = /actuator { return 404; }'
assert_contains "$site" 'location ^~ /actuator/ { return 404; }'
assert_contains "$site" 'location = /api/admin/auth/login {'
assert_contains "$site" 'limit_req zone=portfolio_login burst=3 nodelay;'
assert_contains "$site" 'limit_req zone=portfolio_contact burst=5 nodelay;'
assert_contains "$site" 'limit_req zone=portfolio_events burst=20 nodelay;'
assert_contains "$site" 'location ^~ /api/ {'
assert_contains "$site" 'location / {'
assert_contains "$site" 'proxy_pass http://127.0.0.1:18080;'
assert_contains "$site" 'include /www/server/nginx/conf/portfolio-proxy.conf;'
assert_contains "$site" 'include /www/server/nginx/conf/portfolio-security-headers.conf;'

assert_contains "$proxy" 'proxy_set_header Host $host;'
assert_contains "$proxy" 'proxy_set_header X-Real-IP $remote_addr;'
assert_contains "$proxy" 'proxy_set_header X-Forwarded-For $remote_addr;'
assert_contains "$proxy" 'proxy_set_header X-Forwarded-Proto $scheme;'
assert_contains "$proxy" 'proxy_set_header Range $http_range;'
assert_contains "$proxy" 'proxy_set_header If-Range $http_if_range;'
assert_not_contains "$proxy" '$proxy_add_x_forwarded_for'

assert_contains "$headers" 'X-Content-Type-Options "nosniff" always;'
assert_contains "$headers" 'Referrer-Policy "strict-origin-when-cross-origin" always;'
assert_contains "$headers" 'X-Frame-Options "DENY" always;'
assert_contains "$headers" 'https://media.yychainsaw.xyz'
assert_contains "$headers" 'https://player.bilibili.com'
assert_contains "$headers" 'https://www.youtube-nocookie.com'
assert_contains "$headers" 'Strict-Transport-Security "max-age=31536000; includeSubDomains" always;'
assert_not_contains "$headers" 'http://'
assert_not_contains "$headers" '*.'

http_server="$WORK_DIRECTORY/http-server.conf"
https_server="$WORK_DIRECTORY/https-server.conf"
awk '/listen 80;/{capture=1} capture{print} capture && /^}/{exit}' "$site" >"$http_server"
awk '/listen 443 ssl http2;/{capture=1} capture{print}' "$site" >"$https_server"
assert_not_contains "$http_server" 'Strict-Transport-Security'
assert_not_contains "$http_server" 'portfolio-security-headers.conf'
assert_contains "$https_server" 'portfolio-security-headers.conf'

second="$WORK_DIRECTORY/rendered-second"
env "${render_environment[@]}" bash "$RENDER" "$second"
first_hash="$(cd "$rendered" && find . -type f -printf '%P\0' | sort -z | xargs -0 sha256sum | sha256sum)"
second_hash="$(cd "$second" && find . -type f -printf '%P\0' | sort -z | xargs -0 sha256sum | sha256sum)"
[[ "$first_hash" == "$second_hash" ]] || fail 'render is not deterministic'

for invalid_origin in \
  'http://media.example.com' \
  'https://*.example.com' \
  'https://media.example.com/path' \
  'https://media.example.com;include' \
  'https://media.example.com bad'
do
  invalid_output="$WORK_DIRECTORY/invalid-$(printf '%s' "$invalid_origin" | sha256sum | cut -c1-12)"
  expect_failure \
    "origin-$(basename "$invalid_output")" \
    'invalid HTTPS origin' \
    env "${render_environment[@]}" MEDIA_ORIGIN="$invalid_origin" \
      bash "$RENDER" "$invalid_output"
  [[ ! -e "$invalid_output/portfolio-site.conf" ]] ||
    fail 'invalid origin left a rendered public configuration'
done

fixture="$WORK_DIRECTORY/fixture"
bin="$fixture/bin"
baota="$fixture/www/server/nginx"
portfolio="$fixture/opt/portfolio"
etc_portfolio="$fixture/etc/portfolio"
proc_root="$fixture/proc"
mkdir -p \
  "$bin" "$baota/sbin" "$baota/conf" "$baota/logs" \
  "$portfolio/releases/aaaaaaaaaaaa-bbbbbbbbbbbb" \
  "$portfolio/assets" "$portfolio/current-admin" \
  "$etc_portfolio" "$proc_root/4242" \
  "$fixture/tls" "$fixture/local-media/staging" "$fixture/tmp"

command_log="$fixture/commands.log"
: >"$command_log"

cat >"$baota/sbin/nginx" <<'STUB'
#!/usr/bin/env bash
printf 'nginx:%s\n' "$*" >>"$PORTFOLIO_TEST_COMMAND_LOG"
exit "${PORTFOLIO_TEST_NGINX_EXIT:-0}"
STUB
chmod 0755 "$baota/sbin/nginx"
printf 'events {}\nhttp {}\n' >"$baota/conf/nginx.conf"
printf '4242\n' >"$baota/logs/nginx.pid"
ln -s "$baota/sbin/nginx" "$proc_root/4242/exe"

cat >"$bin/dig" <<'STUB'
#!/usr/bin/env bash
printf 'dig:%s\n' "$*" >>"$PORTFOLIO_TEST_COMMAND_LOG"
record_type="${*: -1}"
if [[ "$record_type" == 'A' ]]; then
  printf '%s\n' "${PORTFOLIO_TEST_DNS_A:-203.0.113.10}"
elif [[ -n "${PORTFOLIO_TEST_DNS_AAAA:-}" ]]; then
  printf '%s\n' "$PORTFOLIO_TEST_DNS_AAAA"
fi
STUB

cat >"$bin/ss" <<'STUB'
#!/usr/bin/env bash
if [[ "${PORTFOLIO_TEST_BAD_PORT_OWNER:-false}" == 'true' ]]; then
  printf 'LISTEN 0 511 0.0.0.0:80 0.0.0.0:* users:(("nginx",pid=9999,fd=6))\n'
  printf 'LISTEN 0 511 0.0.0.0:443 0.0.0.0:* users:(("nginx",pid=9999,fd=7))\n'
else
  printf 'LISTEN 0 511 0.0.0.0:80 0.0.0.0:* users:(("nginx",pid=4242,fd=6))\n'
  printf 'LISTEN 0 511 0.0.0.0:443 0.0.0.0:* users:(("nginx",pid=4242,fd=7))\n'
fi
STUB

cat >"$bin/timedatectl" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "${PORTFOLIO_TEST_CLOCK_SYNC:-yes}"
STUB

cat >"$bin/findmnt" <<'STUB'
#!/usr/bin/env bash
if [[ "$*" == *' -S '* ]]; then
  printf '%s\n' "$PORTFOLIO_TEST_LOCAL_ROOT"
else
  printf '%s %s ext4 rw,relatime\n' \
    "$PORTFOLIO_TEST_LOCAL_ROOT" "$PORTFOLIO_TEST_LOCAL_SOURCE"
fi
STUB

cat >"$bin/docker" <<'STUB'
#!/usr/bin/env bash
printf 'docker:%s\n' "$*" >>"$PORTFOLIO_TEST_COMMAND_LOG"
case "${1:-}:${2:-}" in
  version:*) printf '%s\n' "${PORTFOLIO_TEST_DOCKER_VERSION:-26.1.4}" ;;
  compose:version) printf '%s\n' 'Docker Compose version v2.27.0' ;;
  compose:*)
    if [[ "$*" == *' exec '* ]]; then
      cat "$PORTFOLIO_TEST_MEDIA_LOCATIONS"
    elif [[ "$*" == *' ps -q portfolio-api'* ]]; then
      printf '%s\n' 'portfolio-api-resolved'
    fi
    ;;
  inspect:*)
    if [[ "$*" == *'HostConfig.Tmpfs'* ]]; then
      printf '%s\n' "${PORTFOLIO_TEST_TMPFS_OPTIONS:-rw,noexec,nosuid,nodev,size=134217728,mode=1777}"
    else
      printf '%s\n' 'JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/tmp'
      printf '%s\n' 'PORTFOLIO_COS_STAGING_ROOT=/tmp/portfolio-cos-staging'
      printf '%s\n' 'PORTFOLIO_LOCAL_STORAGE=/var/lib/portfolio/media'
    fi
    ;;
  exec:*) printf '%s\n' "${@: -1}" ;;
  *) exit 1 ;;
esac
STUB

cat >"$bin/jq" <<'STUB'
#!/usr/bin/env bash
file="${*: -1}"
sed -n 's/.*"releaseId"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$file"
STUB

cat >"$bin/curl" <<'STUB'
#!/usr/bin/env bash
printf 'curl:%s\n' "$*" >>"$PORTFOLIO_TEST_COMMAND_LOG"
exit "${PORTFOLIO_TEST_CURL_EXIT:-0}"
STUB

cat >"$bin/lifecycle-check" <<'STUB'
#!/usr/bin/env bash
printf 'lifecycle:%s@%s:%s\n' "$COS_BUCKET" "$COS_REGION" "$*" >>"$PORTFOLIO_TEST_COMMAND_LOG"
exit "${PORTFOLIO_TEST_LIFECYCLE_EXIT:-0}"
STUB

for executable in dig ss timedatectl findmnt docker jq curl lifecycle-check; do
  chmod 0755 "$bin/$executable"
done
for executable in unzip zstd age rclone; do
  cat >"$bin/$executable" <<'STUB'
#!/usr/bin/env bash
exit 0
STUB
  chmod 0755 "$bin/$executable"
done

printf '{"releaseId":"aaaaaaaaaaaa-bbbbbbbbbbbb"}\n' \
  >"$portfolio/releases/aaaaaaaaaaaa-bbbbbbbbbbbb/release.json"
openssl req -x509 -newkey rsa:2048 -nodes -days 2 \
  -subj '/CN=yychainsaw.xyz' \
  -keyout "$fixture/tls/privkey.pem" \
  -out "$fixture/tls/fullchain.pem" >/dev/null 2>&1
printf 'volume-contract-secret\n' >"$fixture/local-media/.portfolio-volume-id"
printf 'LOCAL||\nTENCENT_COS|portfolio-contract-1250000000|ap-guangzhou\n' \
  >"$fixture/media-locations.tsv"

for protected in postgres.env portfolio.env; do
  printf 'PROTECTED=true\n' >"$etc_portfolio/$protected"
  chmod 0600 "$etc_portfolio/$protected"
done
for protected in release.env nginx.env; do
  printf 'PROTECTED=true\n' >"$etc_portfolio/$protected"
  chmod 0640 "$etc_portfolio/$protected"
done
chmod 0600 "$fixture/tls/privkey.pem" "$fixture/local-media/.portfolio-volume-id"

cat >"$fixture/docker-compose.prod.yml" <<'YAML'
services:
  portfolio-api:
    tmpfs:
      - /tmp:size=128m,mode=1777,noexec,nosuid,nodev
    environment:
      JAVA_TOOL_OPTIONS: -Djava.io.tmpdir=/tmp
      PORTFOLIO_COS_STAGING_ROOT: /tmp/portfolio-cos-staging
    volumes:
      - local-media:/var/lib/portfolio/media
YAML

base_preflight_environment=(
  PATH="$bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
  PORTFOLIO_TEST_COMMAND_LOG="$command_log"
  PORTFOLIO_TEST_LOCAL_ROOT="$fixture/local-media"
  PORTFOLIO_TEST_LOCAL_SOURCE=/dev/contract-volume
  PORTFOLIO_TEST_MEDIA_LOCATIONS="$fixture/media-locations.tsv"
  SERVER_JURISDICTION=MAINLAND_CN
  TENCENT_REGION=ap-guangzhou
  ICP_APPROVED=true
  ICP_NUMBER='赣ICP备2026000000号-1'
  PUBLIC_DOMAIN_ENABLED=true
  'PUBLIC_HOSTS=yychainsaw.xyz,www.yychainsaw.xyz'
  EXPECTED_PUBLIC_IPV4=203.0.113.10
  EXPECTED_PUBLIC_IPV6=NONE
  NGINX_BIN="$baota/sbin/nginx"
  NGINX_PREFIX="$baota/"
  NGINX_CONF="$baota/conf/nginx.conf"
  NGINX_PID="$baota/logs/nginx.pid"
  PORTFOLIO_PROC_ROOT="$proc_root"
  TLS_CERTIFICATE="$fixture/tls/fullchain.pem"
  TLS_CERTIFICATE_KEY="$fixture/tls/privkey.pem"
  PORTFOLIO_POSTGRES_ENV="$etc_portfolio/postgres.env"
  PORTFOLIO_APP_ENV="$etc_portfolio/portfolio.env"
  PORTFOLIO_RELEASE_ENV="$etc_portfolio/release.env"
  PORTFOLIO_NGINX_ENV="$etc_portfolio/nginx.env"
  PORTFOLIO_DEPLOY_GROUP=root
  PORTFOLIO_ROOT="$portfolio"
  PORTFOLIO_RELEASE_ID=aaaaaaaaaaaa-bbbbbbbbbbbb
  PORTFOLIO_LOCAL_HOST_ROOT="$fixture/local-media"
  PORTFOLIO_LOCAL_VOLUME_ID=volume-contract-secret
  PORTFOLIO_JOBS_WORKER_ENABLED=true
  PORTFOLIO_STAGING_CLEANUP_ENABLED=true
  PORTFOLIO_MEDIA_CLEANUP_ENABLED=true
  PORTFOLIO_LOCAL_STAGING_SCAN_LIMIT=100000
  PORTFOLIO_JOB_LEASE_MILLIS=120000
  PORTFOLIO_CLEANUP_SCAN_MAX_LEASE_PERCENT=50
  'PORTFOLIO_STORAGE_ADAPTERS=LOCAL,TENCENT_COS'
  PORTFOLIO_STORAGE_DEFAULT_PROVIDER=TENCENT_COS
  PORTFOLIO_COS_LOCATIONS=portfolio-contract-1250000000@ap-guangzhou
  COS_BUCKET=portfolio-contract-1250000000
  COS_REGION=ap-guangzhou
  COS_SECRET_ID=runtime-id
  COS_SECRET_KEY=runtime-key
  COS_RUNTIME_LIFECYCLE_MANAGEMENT_DISABLED=true
  COS_LIFECYCLE_READ_SECRET_ID=read-id
  COS_LIFECYCLE_READ_SECRET_KEY=read-key
  COS_LIFECYCLE_READ_SECURITY_TOKEN=read-token
  PORTFOLIO_COS_LIFECYCLE_VERIFY_COMMAND="$bin/lifecycle-check"
  PORTFOLIO_COMPOSE_FILE="$fixture/docker-compose.prod.yml"
  PORTFOLIO_CONTAINER_NAME=portfolio-api
  PORTFOLIO_MIN_FREE_KIB=1
)

: >"$command_log"
env "${base_preflight_environment[@]}" bash "$PREFLIGHT" --public-cutover \
  >"$WORK_DIRECTORY/preflight-success.log"
assert_contains "$WORK_DIRECTORY/preflight-success.log" 'Portfolio preflight passed for public cutover'
assert_contains "$command_log" "nginx:-p $baota/ -c $baota/conf/nginx.conf -t"
assert_contains "$command_log" 'lifecycle:portfolio-contract-1250000000@ap-guangzhou:--check-live'
[[ "$(grep -c '^dig:' "$command_log")" -eq 4 ]] ||
  fail 'public cutover did not verify A and AAAA for both hosts'

: >"$command_log"
expect_failure \
  icp-gate \
  'ICP approval required for mainland public cutover' \
  env "${base_preflight_environment[@]}" ICP_APPROVED=false ICP_NUMBER= \
    PUBLIC_DOMAIN_ENABLED=false bash "$PREFLIGHT" --public-cutover
[[ ! -s "$command_log" ]] || fail 'ICP gate ran commands before rejecting public cutover'

: >"$command_log"
expect_failure \
  stale-dns \
  'public DNS A answers do not exactly match the recorded expected set' \
  env "${base_preflight_environment[@]}" \
    PORTFOLIO_TEST_DNS_A=$'203.0.113.10\n203.0.113.11' \
    bash "$PREFLIGHT" --public-cutover

expect_failure \
  wrong-port-owner \
  'BaoTa Nginx PID does not own both public listener ports' \
  env "${base_preflight_environment[@]}" PORTFOLIO_TEST_BAD_PORT_OWNER=true \
    bash "$PREFLIGHT"

printf 'TENCENT_COS|wrong-bucket-1250000000|ap-guangzhou\n' \
  >"$fixture/media-locations-mismatch.tsv"
: >"$command_log"
expect_failure \
  historical-provider \
  'historical COS location is not configured' \
  env "${base_preflight_environment[@]}" \
    PORTFOLIO_TEST_MEDIA_LOCATIONS="$fixture/media-locations-mismatch.tsv" \
    bash "$PREFLIGHT"
assert_not_contains "$command_log" 'lifecycle:'

expect_failure \
  tmpfs-options \
  'running /tmp tmpfs is missing a required size or security option' \
  env "${base_preflight_environment[@]}" \
    PORTFOLIO_TEST_TMPFS_OPTIONS='rw,nosuid,nodev,size=134217728' \
    bash "$PREFLIGHT"

expect_failure \
  lifecycle-live \
  'COS staging lifecycle verification failed' \
  env "${base_preflight_environment[@]}" PORTFOLIO_TEST_LIFECYCLE_EXIT=1 \
    bash "$PREFLIGHT"

expect_failure \
  volume-marker \
  'Local volume identity does not match the protected value' \
  env "${base_preflight_environment[@]}" \
    PORTFOLIO_LOCAL_VOLUME_ID=do-not-print-this-value \
    bash "$PREFLIGHT"
assert_not_contains "$WORK_DIRECTORY/failure-volume-marker.log" 'do-not-print-this-value'
assert_not_contains "$WORK_DIRECTORY/failure-volume-marker.log" 'volume-contract-secret'

printf 'bounded-entry\n' >"$fixture/local-media/staging/entry"
expect_failure \
  staging-ceiling \
  'Local staging entry count reached the configured hard scan ceiling' \
  env "${base_preflight_environment[@]}" PORTFOLIO_LOCAL_STAGING_SCAN_LIMIT=1 \
    bash "$PREFLIGHT"
rm -f -- "$fixture/local-media/staging/entry"

expect_failure \
  scan-lease-fraction \
  'latest cleanup scan duration is not below the reviewed lease fraction' \
  env "${base_preflight_environment[@]}" PORTFOLIO_JOB_LEASE_MILLIS=1 \
    PORTFOLIO_CLEANUP_SCAN_MAX_LEASE_PERCENT=1 bash "$PREFLIGHT"

expect_failure \
  unconfigured-historical-provider \
  'historical media provider is not configured' \
  env "${base_preflight_environment[@]}" PORTFOLIO_STORAGE_ADAPTERS=LOCAL \
    PORTFOLIO_STORAGE_DEFAULT_PROVIDER=LOCAL bash "$PREFLIGHT"

: >"$command_log"
env "${base_preflight_environment[@]}" \
  SERVER_JURISDICTION=HONG_KONG ICP_APPROVED=false ICP_NUMBER= \
  PUBLIC_DOMAIN_ENABLED=false PORTFOLIO_CONTAINER_NAME= bash "$PREFLIGHT" \
  >"$WORK_DIRECTORY/preflight-overseas.log"
assert_contains "$WORK_DIRECTORY/preflight-overseas.log" \
  'Mainland ICP gate does not apply to jurisdiction HONG_KONG'
assert_not_contains "$command_log" 'dig:'
assert_contains "$command_log" 'docker:compose --env-file'
assert_contains "$command_log" 'ps -q portfolio-api'

grep -F '"$NGINX_BIN" -p "$NGINX_PREFIX" -c "$NGINX_CONF" -t' "$PREFLIGHT" >/dev/null ||
  fail 'preflight does not use the exact BaoTa syntax command'
grep -F '"$NGINX_BIN" -p "$NGINX_PREFIX" -c "$NGINX_CONF" -s reload' "$PREFLIGHT" >/dev/null ||
  fail 'preflight does not use the exact BaoTa reload command'
if grep -E '(^|[[:space:]])(nginx -t|systemctl reload nginx)([[:space:]]|$)' "$PREFLIGHT" >/dev/null; then
  fail 'generic Nginx syntax or reload command is forbidden'
fi

printf '%s\n' 'PASS: Nginx render, BaoTa, DNS, ICP, storage, and lifecycle contracts'
