#!/usr/bin/env bash
set -euo pipefail
set +x
umask 077

SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=deploy/restore/adapters/adapter-lib.sh
source "$SCRIPT_DIRECTORY/adapter-lib.sh"

main() {
  local url='' body='' headers='' allowed_origin='' expected_redirect=''
  while (($#)); do
    case "$1" in
      --url) url="$2"; shift 2 ;;
      --body) body="$2"; shift 2 ;;
      --headers) headers="$2"; shift 2 ;;
      --allowed-redirect-origin) allowed_origin="$2"; shift 2 ;;
      --expected-redirect-url) expected_redirect="$2"; shift 2 ;;
      *) adapter_fail 'HTTP fetch received an unsupported argument' ;;
    esac
  done
  adapter_require_drill_context
  adapter_require_disposed_identity
  adapter_resolve_command RESTORE_CURL_COMMAND curl
  adapter_require_value RESTORE_NGINX_BASE_URL
  adapter_require_value RESTORE_TLS_SERVER_NAME
  adapter_require_value RESTORE_NGINX_PORT
  [[ "$RESTORE_NGINX_BASE_URL" == "https://$RESTORE_TLS_SERVER_NAME:$RESTORE_NGINX_PORT" &&
     "$url" == "$RESTORE_NGINX_BASE_URL/api/"* ]] ||
    adapter_fail 'HTTP fetch URL is outside the local drill Nginx'
  [[ "$allowed_origin" == "${RESTORE_DRILL_COS_ORIGIN:-}" &&
     "$allowed_origin" =~ ^https://[a-z0-9][a-z0-9.-]+\.myqcloud\.com$ ]] ||
    adapter_fail 'redirect origin is not the reviewed drill COS origin'
  if [[ -n "$expected_redirect" ]]; then
    [[ "$expected_redirect" == "$allowed_origin/drills/$RESTORE_DRILL_ID/blobs/"* &&
       "$expected_redirect" =~ /blobs/[0-9a-f]{64}$ ]] ||
      adapter_fail 'expected redirect does not match the verified drill COS mapping'
  fi
  [[ "$body" == "$RESTORE_ROOT/"* && "$headers" == "$RESTORE_ROOT/"* &&
     ! -L "$body" && ! -L "$headers" ]] ||
    adapter_fail 'HTTP fetch output escaped the restore root'
  adapter_require_private_file "${RESTORE_TLS_CA_CERT:-}" 'drill TLS CA certificate'
  local -a cookie_arguments=()
  if [[ "$url" == "$RESTORE_NGINX_BASE_URL/api/admin/"* ]]; then
    adapter_require_private_file "${RESTORE_ADMIN_COOKIE_JAR:-}" 'drill administrator cookie jar'
    [[ "$RESTORE_ADMIN_COOKIE_JAR" == "$RESTORE_ROOT/work/admin-cookie.jar" ]] ||
      adapter_fail 'administrator cookie jar escaped the drill work directory'
    cookie_arguments=(--cookie "$RESTORE_ADMIN_COOKIE_JAR")
  fi
  local metadata="$RESTORE_ROOT/work/http-fetch-$$.metadata"
  local local_headers="$RESTORE_ROOT/work/http-fetch-$$.headers"
  local local_body="$RESTORE_ROOT/work/http-fetch-$$.body"
  "$RESTORE_CURL_COMMAND" --disable --noproxy '*' --silent --show-error --max-redirs 0 \
    --proto '=https' --tlsv1.2 \
    --cacert "$RESTORE_TLS_CA_CERT" \
    --resolve "$RESTORE_TLS_SERVER_NAME:$RESTORE_NGINX_PORT:127.0.0.1" \
    "${cookie_arguments[@]}" \
    --dump-header "$local_headers" --output "$local_body" \
    --write-out '%{http_code}|%{content_type}|%{url_effective}|%{num_redirects}\n' \
    "$url" >"$metadata" || adapter_fail 'HTTP fetch failed'
  local status content_type effective redirects extra
  IFS='|' read -r status content_type effective redirects extra <"$metadata"
  [[ -z "${extra:-}" && "$redirects" == 0 && "$effective" == "$url" ]] ||
    adapter_fail 'local HTTP fetch changed origin or followed a redirect'
  if [[ "$status" == 200 ]]; then
    [[ -z "$expected_redirect" ]] || adapter_fail 'expected COS redirect was not returned'
    mv -- "$local_body" "$body"
  elif [[ "$status" =~ ^30[1278]$ ]]; then
    [[ -n "$expected_redirect" ]] || adapter_fail 'unexpected redirect was returned for a Local object'
    local location
    location="$(awk '
      {line=$0; sub(/\r$/, "", line)}
      tolower(line) ~ /^location:[[:space:]]*/ {
        sub(/^[^:]*:[[:space:]]*/, "", line); value=line; count += 1
      }
      END {if (count != 1) exit 1; print value}
    ' "$local_headers")" || adapter_fail 'COS redirect Location is missing or ambiguous'
    [[ "$location" == "$expected_redirect?"* && "${location%%\?*}" == "$expected_redirect" ]] ||
      adapter_fail 'COS redirect Location does not match the verified bucket, region, and object key'
    rm -f -- "$local_body"
    "$RESTORE_CURL_COMMAND" --disable --noproxy '*' --fail --silent --show-error --max-redirs 0 \
      --proto '=https' --tlsv1.2 --output "$body" \
      --write-out '%{http_code}|%{content_type}|%{url_effective}|%{num_redirects}\n' \
      "$location" >"$metadata" || adapter_fail 'verified COS redirect fetch failed'
    IFS='|' read -r status content_type effective redirects extra <"$metadata"
    [[ -z "${extra:-}" && "$status" == 200 && "$redirects" == 0 && "$effective" == "$location" ]] ||
      adapter_fail 'verified COS fetch did not finish with one successful response'
  else
    adapter_fail 'local HTTP fetch returned neither 200 nor a bounded COS redirect'
  fi
  rm -f -- "$metadata" "$local_headers" "$local_body"
  [[ "$content_type" =~ ^[A-Za-z0-9!#$\&^_.+-]+/[A-Za-z0-9!#$\&^_.+-]+([[:space:]]*\;.*)?$ ]] ||
    adapter_fail 'HTTP fetch returned an invalid Content-Type'
  printf 'Content-Type: %s\r\n' "$content_type" >"$headers"
}

main "$@"
