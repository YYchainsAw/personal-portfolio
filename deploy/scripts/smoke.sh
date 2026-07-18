#!/usr/bin/env bash
set -euo pipefail
set +x
umask 077

export LC_ALL=C

MODE=''
BASE_URL=''
RESOLVE_VALUE=''
WORK_DIRECTORY=''
declare -a CURL_ORIGIN_OPTIONS=()

fail() {
  printf 'portfolio smoke failed: %s\n' "$1" >&2
  exit 1
}

note() {
  printf 'portfolio smoke: %s\n' "$1" >&2
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

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "$1 is required"
}

parse_arguments() {
  (($# >= 1)) || fail 'usage: smoke.sh MODE (--base-url URL | --resolve HOST:PORT:ADDRESS)'
  MODE="$1"
  shift
  case "$MODE" in
    api-local|public)
      if (($# != 2)) || [[ "$1" != '--base-url' ]]; then
        fail "$MODE requires exactly --base-url URL"
      fi
      BASE_URL="${2%/}"
      ;;
    nginx-local)
      if (($# != 2)) || [[ "$1" != '--resolve' ]]; then
        fail 'nginx-local requires exactly --resolve HOST:PORT:ADDRESS'
      fi
      RESOLVE_VALUE="$2"
      [[ "$RESOLVE_VALUE" =~ ^yychainsaw\.xyz:([1-9][0-9]{0,4}):127\.0\.0\.1$ ]] ||
        fail 'nginx-local resolve must be yychainsaw.xyz:PORT:127.0.0.1'
      ((10#${BASH_REMATCH[1]} <= 65535)) || fail 'nginx-local port is invalid'
      BASE_URL="https://yychainsaw.xyz:${BASH_REMATCH[1]}"
      CURL_ORIGIN_OPTIONS+=(--resolve "$RESOLVE_VALUE")
      ;;
    *) fail 'mode must be api-local, nginx-local, or public' ;;
  esac

  case "$MODE" in
    api-local)
      [[ "$BASE_URL" == 'http://127.0.0.1:18080' ]] ||
        fail 'api-local base URL must be http://127.0.0.1:18080'
      ;;
    public)
      [[ "$BASE_URL" == 'https://yychainsaw.xyz' ]] ||
        fail 'public base URL must be https://yychainsaw.xyz'
      [[ "${ICP_APPROVED:-}" == 'true' && -n "${ICP_NUMBER:-}" &&
         "${PUBLIC_DOMAIN_ENABLED:-}" == 'true' ]] ||
        fail 'public mode requires the approved ICP/public-domain gate'
      ;;
  esac

  if [[ -n "${PORTFOLIO_SMOKE_CA_CERT:-}" ]]; then
    [[ "$PORTFOLIO_SMOKE_CA_CERT" == /* && -f "$PORTFOLIO_SMOKE_CA_CERT" &&
       ! -L "$PORTFOLIO_SMOKE_CA_CERT" ]] ||
      fail 'PORTFOLIO_SMOKE_CA_CERT must be an absolute regular file'
    CURL_ORIGIN_OPTIONS+=(--cacert "$PORTFOLIO_SMOKE_CA_CERT")
  fi
}

header_value() {
  local headers="$1"
  local wanted="${2,,}"
  awk -v wanted="$wanted" '
    BEGIN { IGNORECASE = 1 }
    {
      line=$0
      sub(/\r$/, "", line)
      separator=index(line, ":")
      if (separator > 0) {
        name=tolower(substr(line, 1, separator - 1))
        if (name == wanted) {
          value=substr(line, separator + 1)
          sub(/^[[:space:]]+/, "", value)
          latest=value
        }
      }
    }
    END { if (latest != "") print latest }
  ' "$headers"
}

request() {
  local label="$1"
  local path="$2"
  local expected_status="$3"
  shift 3
  local headers="$WORK_DIRECTORY/$label.headers"
  local body="$WORK_DIRECTORY/$label.body"
  local status
  status="$(env \
    -u HTTP_PROXY -u HTTPS_PROXY -u ALL_PROXY \
    -u http_proxy -u https_proxy -u all_proxy \
    -u NO_PROXY -u no_proxy \
    curl --disable --noproxy '*' \
    --silent --show-error \
    --connect-timeout 5 --max-time 20 \
    --proto '=http,https' \
    --output "$body" \
    --dump-header "$headers" \
    --write-out '%{http_code}' \
    "${CURL_ORIGIN_OPTIONS[@]}" \
    "$@" \
    "$BASE_URL$path")" || fail "$label request failed"
  [[ "$status" == "$expected_status" ]] ||
    fail "$label returned HTTP $status instead of $expected_status"
  printf '%s\n' "$body"
}

request_public_media() {
  local path="$1"
  local headers="$WORK_DIRECTORY/public-media.headers"
  local body="$WORK_DIRECTORY/public-media.body"
  local result status effective
  result="$(env \
    -u HTTP_PROXY -u HTTPS_PROXY -u ALL_PROXY \
    -u http_proxy -u https_proxy -u all_proxy \
    -u NO_PROXY -u no_proxy \
    curl --disable --noproxy '*' \
    --silent --show-error \
    --connect-timeout 5 --max-time 60 \
    --proto '=http,https' --proto-redir '=https' \
    --location --max-redirs 3 \
    --output "$body" \
    --dump-header "$headers" \
    --write-out $'%{http_code}\t%{url_effective}' \
    "${CURL_ORIGIN_OPTIONS[@]}" \
    "$BASE_URL$path")" || fail 'public media request failed'
  IFS=$'\t' read -r status effective <<<"$result"
  [[ "$status" == '200' && -n "$effective" ]] ||
    fail 'public media did not finish with HTTP 200'
  case "$effective" in
    "$BASE_URL"/*|"$PORTFOLIO_SMOKE_MEDIA_ORIGIN"/*) ;;
    *) fail 'public media redirected outside the reviewed media origin' ;;
  esac
  printf '%s\n' "$body"
}

assert_header_contains() {
  local label="$1"
  local name="$2"
  local expected="$3"
  local actual
  actual="$(header_value "$WORK_DIRECTORY/$label.headers" "$name")"
  [[ "${actual,,}" == *"${expected,,}"* ]] ||
    fail "$label header $name did not contain the required value"
}

assert_header_exact_normalized() {
  local label="$1" name="$2" expected="$3" actual
  actual="$(header_value "$WORK_DIRECTORY/$label.headers" "$name")"
  actual="$(printf '%s' "$actual" | awk '
    { gsub(/^[[:space:]]+|[[:space:]]+$/, ""); print tolower($0) }
  ')"
  expected="$(printf '%s' "$expected" | awk '
    { gsub(/^[[:space:]]+|[[:space:]]+$/, ""); print tolower($0) }
  ')"
  [[ -n "$actual" && "$actual" == "$expected" ]] ||
    fail "$label header $name did not exactly match the required normalized value"
}

assert_header_absent() {
  local label="$1"
  local name="$2"
  [[ -z "$(header_value "$WORK_DIRECTORY/$label.headers" "$name")" ]] ||
    fail "$label unexpectedly returned header $name"
}

check_json_api() {
  local site projects etag not_modified projects_etag projects_not_modified admin_problem
  site="$(request public-site '/api/public/site?locale=zh-CN' 200)"
  assert_header_contains public-site content-type 'application/json'
  assert_header_contains public-site cache-control 'public, no-cache'
  etag="$(header_value "$WORK_DIRECTORY/public-site.headers" etag)"
  [[ "$etag" =~ ^\"[^\"]+\"$ ]] || fail 'public site did not return a strong quoted ETag'
  jq -e '
    type == "object" and
    (.checksum | type == "string" and test("^[0-9a-f]{64}$")) and
    has("data")
  ' "$site" >/dev/null || fail 'public site response shape is invalid'

  not_modified="$(request public-site-not-modified '/api/public/site?locale=zh-CN' 304 \
    --header "If-None-Match: $etag")"
  [[ ! -s "$not_modified" ]] || fail 'ETag 304 response unexpectedly had a body'

  projects="$(request public-projects '/api/public/projects?locale=en' 200)"
  assert_header_contains public-projects content-type 'application/json'
  assert_header_contains public-projects cache-control 'public, no-cache'
  projects_etag="$(header_value "$WORK_DIRECTORY/public-projects.headers" etag)"
  [[ "$projects_etag" =~ ^\"[^\"]+\"$ ]] ||
    fail 'public projects did not return a strong quoted ETag'
  jq -e '
    type == "object" and
    (.checksum | type == "string" and test("^[0-9a-f]{64}$")) and
    (.data | type == "array")
  ' "$projects" >/dev/null || fail 'public project catalog response shape is invalid'

  projects_not_modified="$(request public-projects-not-modified \
    '/api/public/projects?locale=en' 304 --header "If-None-Match: $projects_etag")"
  [[ ! -s "$projects_not_modified" ]] ||
    fail 'projects ETag 304 response unexpectedly had a body'

  local missing
  missing="$(request api-not-found '/api/not-a-route' 404)"
  assert_header_contains api-not-found content-type 'application/json'
  if grep -Eiq '<!doctype[[:space:]]+html|<html' "$missing"; then
    fail 'unknown API route returned HTML'
  fi
  jq -e 'type == "object" and (.code | type == "string")' "$missing" >/dev/null ||
    fail 'unknown API route did not return the stable JSON error shape'

  admin_problem="$(request admin-anonymous '/api/admin/auth/me' 401)"
  assert_header_contains admin-anonymous content-type 'application/json'
  assert_header_contains admin-anonymous cache-control 'no-store'
  jq -e '.code == "UNAUTHORIZED"' "$admin_problem" >/dev/null ||
    fail 'anonymous admin API did not return its stable unauthorized shape'
}

check_public_pages() {
  local root zh en privacy sitemap robots location
  root="$(request root '/' 302)"
  [[ ! -s "$root" ]] || fail 'root redirect unexpectedly returned a body'
  location="$(header_value "$WORK_DIRECTORY/root.headers" location)"
  [[ "$location" == '/zh-CN' || "$location" == "$BASE_URL/zh-CN" ]] ||
    fail 'root redirect target is not /zh-CN'

  zh="$(request zh-home '/zh-CN' 200)"
  assert_header_contains zh-home content-type 'text/html'
  assert_header_contains zh-home cache-control 'public, no-cache'
  grep -Eiq '<html[^>]+lang="zh-CN"' "$zh" || fail 'Chinese page has no zh-CN html marker'
  if grep -Eiq '<meta[^>]+name="robots"[^>]+content="[^"]*(noindex|nofollow)' "$zh"; then
    fail 'Chinese page contains a search-indexing exclusion'
  fi

  en="$(request en-home '/en' 200)"
  assert_header_contains en-home content-type 'text/html'
  assert_header_contains en-home cache-control 'public, no-cache'
  grep -Eiq '<html[^>]+lang="en"' "$en" || fail 'English page has no en html marker'

  privacy="$(request zh-privacy '/zh-CN/privacy' 200)"
  assert_header_contains zh-privacy content-type 'text/html'
  assert_header_contains zh-privacy cache-control 'public, no-cache'
  grep -Eiq '<link[^>]+rel="canonical"' "$privacy" || fail 'privacy page lacks canonical URL'
  grep -Eiq '<link[^>]+hreflang="(zh-CN|en)"' "$privacy" ||
    fail 'privacy page lacks hreflang alternates'

  sitemap="$(request sitemap '/sitemap.xml' 200)"
  assert_header_contains sitemap content-type 'application/xml'
  assert_header_contains sitemap cache-control 'public, no-cache'
  grep -F '<urlset' "$sitemap" >/dev/null || fail 'sitemap does not contain a URL set'
  grep -F '/zh-CN' "$sitemap" >/dev/null || fail 'sitemap lacks the Chinese home URL'
  if [[ -n "${PORTFOLIO_SMOKE_PROJECT_SLUG:-}" ]]; then
    [[ "$PORTFOLIO_SMOKE_PROJECT_SLUG" =~ ^[a-z0-9]+(-[a-z0-9]+)*$ ]] ||
      fail 'PORTFOLIO_SMOKE_PROJECT_SLUG is invalid'
    grep -F "/projects/$PORTFOLIO_SMOKE_PROJECT_SLUG" "$sitemap" >/dev/null ||
      fail 'sitemap lacks the configured current project'
  fi

  robots="$(request robots '/robots.txt' 200)"
  assert_header_contains robots content-type 'text/plain'
  assert_header_contains robots cache-control 'public, no-cache'
  grep -Eiq '^User-agent:[[:space:]]*\*' "$robots" || fail 'robots.txt lacks a default agent'
}

check_api_health() {
  local readiness
  readiness="$(request readiness '/actuator/health/readiness' 200)"
  assert_header_contains readiness content-type 'application/json'
  jq -e '.status == "UP"' "$readiness" >/dev/null || fail 'API readiness status is not UP'
}

check_edge_contract() {
  local admin hashed actual_hash expected_hash edge_actuator
  assert_header_exact_normalized zh-home x-content-type-options nosniff
  assert_header_exact_normalized zh-home strict-transport-security \
    'max-age=31536000; includeSubDomains'
  assert_header_exact_normalized zh-home referrer-policy strict-origin-when-cross-origin
  assert_header_exact_normalized zh-home x-frame-options DENY
  assert_header_exact_normalized zh-home permissions-policy \
    'camera=(), microphone=(), geolocation=(), payment=(), usb=()'
  assert_header_contains zh-home content-security-policy "default-src 'self'"
  assert_header_contains zh-home content-security-policy "object-src 'none'"
  assert_header_contains zh-home content-security-policy "frame-ancestors 'none'"
  assert_header_contains zh-home content-security-policy "upgrade-insecure-requests"
  edge_actuator="$(request edge-actuator '/actuator/health/readiness' 404)"
  : "$edge_actuator"
  assert_header_exact_normalized edge-actuator x-content-type-options nosniff

  admin="$(request admin '/admin/' 200)"
  assert_header_contains admin content-type 'text/html'
  assert_header_contains admin cache-control 'no-store'
  grep -Eiq '<!doctype[[:space:]]+html|<html' "$admin" || fail 'admin route is not HTML'

  [[ -n "${PORTFOLIO_SMOKE_ASSET_PATH:-}" &&
     "$PORTFOLIO_SMOKE_ASSET_PATH" =~ ^assets/[A-Za-z0-9._-]+$ ]] ||
    fail 'edge smoke requires a safe PORTFOLIO_SMOKE_ASSET_PATH'
  [[ "${PORTFOLIO_SMOKE_ASSET_SHA256:-}" =~ ^[0-9a-f]{64}$ ]] ||
    fail 'edge smoke requires PORTFOLIO_SMOKE_ASSET_SHA256'
  hashed="$(request hashed-asset "/$PORTFOLIO_SMOKE_ASSET_PATH" 200)"
  assert_header_contains hashed-asset cache-control 'public, max-age=31536000, immutable'
  actual_hash="$(sha256sum -- "$hashed" | awk '{print $1}')"
  expected_hash="$PORTFOLIO_SMOKE_ASSET_SHA256"
  [[ "$actual_hash" == "$expected_hash" ]] || fail 'public hashed asset checksum mismatch'

  if [[ -n "${PORTFOLIO_SMOKE_MEDIA_PATH:-}" ||
        -n "${PORTFOLIO_SMOKE_MEDIA_SHA256:-}" ||
        -n "${PORTFOLIO_SMOKE_MEDIA_TYPE:-}" ]]; then
    local media_path_pattern='^/api/public/media/[A-Za-z0-9/_?&=.%:-]+$'
    [[ "${PORTFOLIO_SMOKE_MEDIA_PATH:-}" =~ $media_path_pattern ]] ||
      fail 'PORTFOLIO_SMOKE_MEDIA_PATH is invalid'
    [[ "${PORTFOLIO_SMOKE_MEDIA_SHA256:-}" =~ ^[0-9a-f]{64}$ ]] ||
      fail 'PORTFOLIO_SMOKE_MEDIA_SHA256 is invalid'
    [[ "${PORTFOLIO_SMOKE_MEDIA_TYPE:-}" =~ ^[a-z0-9.+-]+/[a-z0-9.+-]+$ ]] ||
      fail 'PORTFOLIO_SMOKE_MEDIA_TYPE is invalid'
    [[ "${PORTFOLIO_SMOKE_MEDIA_ORIGIN:-}" =~ ^https://[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?(:[1-9][0-9]{0,4})?$ &&
       "$PORTFOLIO_SMOKE_MEDIA_ORIGIN" == *.* &&
       "$PORTFOLIO_SMOKE_MEDIA_ORIGIN" != *[[:space:]\;\"\']* ]] ||
      fail 'PORTFOLIO_SMOKE_MEDIA_ORIGIN is invalid'
    local media media_hash
    media="$(request_public_media "$PORTFOLIO_SMOKE_MEDIA_PATH")"
    assert_header_exact_normalized public-media content-type "$PORTFOLIO_SMOKE_MEDIA_TYPE"
    media_hash="$(sha256sum -- "$media" | awk '{print $1}')"
    [[ "$media_hash" == "$PORTFOLIO_SMOKE_MEDIA_SHA256" ]] ||
      fail 'public media checksum mismatch'
  fi
}

check_optional_content() {
  if [[ -n "${PORTFOLIO_SMOKE_PROJECT_SLUG:-}" ]]; then
    request project-zh "/zh-CN/projects/$PORTFOLIO_SMOKE_PROJECT_SLUG" 200 >/dev/null
    request project-en "/en/projects/$PORTFOLIO_SMOKE_PROJECT_SLUG" 200 >/dev/null
  fi
  if [[ -n "${PORTFOLIO_SMOKE_OLD_PROJECT_SLUG:-}" ]]; then
    [[ "$PORTFOLIO_SMOKE_OLD_PROJECT_SLUG" =~ ^[a-z0-9]+(-[a-z0-9]+)*$ &&
       -n "${PORTFOLIO_SMOKE_PROJECT_SLUG:-}" ]] ||
      fail 'old project redirect variables are invalid'
    request old-project "/zh-CN/projects/$PORTFOLIO_SMOKE_OLD_PROJECT_SLUG" 301 >/dev/null
    assert_header_contains old-project location "/zh-CN/projects/$PORTFOLIO_SMOKE_PROJECT_SLUG"
  fi
}

main() {
  parse_arguments "$@"
  require_command env
  require_command curl
  require_command jq
  require_command sha256sum
  require_command grep
  WORK_DIRECTORY="$(mktemp -d)"

  note "running $MODE checks"
  if [[ "$MODE" == 'api-local' ]]; then
    check_api_health
  fi
  check_json_api
  check_public_pages
  check_optional_content
  if [[ "$MODE" != 'api-local' ]]; then
    check_edge_contract
  fi
  printf 'PASS: %s smoke matrix\n' "$MODE"
}

main "$@"
