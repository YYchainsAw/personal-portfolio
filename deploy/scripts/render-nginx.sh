#!/usr/bin/env bash
set -euo pipefail
set +x
umask 027

SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
readonly SCRIPT_DIRECTORY
readonly TEMPLATE="$SCRIPT_DIRECTORY/../nginx/portfolio-site.conf.example"

WORK_DIRECTORY=''

fail() {
  printf 'Nginx render failed: %s\n' "$1" >&2
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

require_value() {
  local name="$1"
  [[ -n "${!name:-}" ]] || fail "$name is required"
  [[ "${!name}" != *$'\n'* && "${!name}" != *$'\r'* ]] ||
    fail "$name contains a control character"
}

validate_origin() {
  local origin="$1"
  local label="$2"

  [[ "$origin" =~ ^https://([A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?)(:([1-9][0-9]{0,4}))?$ ]] ||
    fail "invalid HTTPS origin in $label"
  [[ "$origin" != *[[:space:]\;\"\']* && "$origin" != *'*'* ]] ||
    fail "invalid HTTPS origin in $label"
  local authority="${origin#https://}"
  local host="${authority%%:*}"
  [[ "$host" == *.* && "$host" != .* && "$host" != *. && "$host" != *..* ]] ||
    fail "invalid HTTPS origin in $label"
  if [[ "$authority" == *:* ]]; then
    local port="${authority##*:}"
    ((10#$port <= 65535)) || fail "invalid HTTPS origin in $label"
  fi
}

validate_path_value() {
  local value="$1"
  local label="$2"
  [[ "$value" == /* && "$value" =~ ^/[A-Za-z0-9._/-]+$ && "$value" != *'/../'* ]] ||
    fail "$label must be a safe absolute path"
}

normalize_hosts() {
  local raw="$1"
  local host
  local -a values=()
  IFS=',' read -r -a values <<<"$raw"
  ((${#values[@]} > 0)) || fail 'PUBLIC_HOSTS is empty'
  for host in "${values[@]}"; do
    [[ "$host" =~ ^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$ &&
       "$host" == *.* && "$host" != *..* ]] ||
      fail 'PUBLIC_HOSTS contains an invalid hostname'
    printf '%s\n' "${host,,}"
  done | LC_ALL=C sort -u | paste -sd' ' -
}

replace_template() {
  local input="$1"
  local output="$2"
  local hosts="$3"
  local certificate="$4"
  local certificate_key="$5"
  local api="$6"
  local include_directory="$7"
  local icp="$8"

  awk \
    -v hosts="$hosts" \
    -v certificate="$certificate" \
    -v certificate_key="$certificate_key" \
    -v api="$api" \
    -v include_directory="$include_directory" \
    -v icp="$icp" \
    '{
      gsub(/@@PUBLIC_HOSTS@@/, hosts)
      gsub(/@@TLS_CERTIFICATE@@/, certificate)
      gsub(/@@TLS_CERTIFICATE_KEY@@/, certificate_key)
      gsub(/@@API_LOOPBACK@@/, api)
      gsub(/@@NGINX_INCLUDE_DIRECTORY@@/, include_directory)
      gsub(/@@ICP_NUMBER@@/, icp)
      print
    }' "$input" >"$output"
}

main() {
  (($# == 1)) || fail 'usage: render-nginx.sh OUTPUT_DIRECTORY'
  local output_directory="$1"
  [[ "$output_directory" == /* ]] || fail 'OUTPUT_DIRECTORY must be absolute'
  [[ ! -L "$output_directory" ]] || fail 'OUTPUT_DIRECTORY may not be a symbolic link'

  require_value PUBLIC_HOSTS
  require_value API_LOOPBACK
  require_value MEDIA_ORIGIN
  require_value TLS_CERTIFICATE
  require_value TLS_CERTIFICATE_KEY
  require_value NGINX_CONF
  [[ -f "$TEMPLATE" ]] || fail 'site template is missing'

  [[ "$API_LOOPBACK" =~ ^127\.0\.0\.1:([1-9][0-9]{0,4})$ ]] ||
    fail 'API_LOOPBACK must use IPv4 loopback and an explicit port'
  ((10#${BASH_REMATCH[1]} <= 65535)) || fail 'API_LOOPBACK port is invalid'
  validate_origin "$MEDIA_ORIGIN" MEDIA_ORIGIN
  validate_path_value "$TLS_CERTIFICATE" TLS_CERTIFICATE
  validate_path_value "$TLS_CERTIFICATE_KEY" TLS_CERTIFICATE_KEY
  validate_path_value "$NGINX_CONF" NGINX_CONF

  local hosts
  hosts="$(normalize_hosts "$PUBLIC_HOSTS")"
  local include_directory
  include_directory="$(dirname "$NGINX_CONF")"
  validate_path_value "$include_directory" NGINX_INCLUDE_DIRECTORY

  local -a frame_origins=()
  local origin
  if [[ -n "${VIDEO_FRAME_ORIGINS:-}" ]]; then
    IFS=',' read -r -a frame_origins <<<"$VIDEO_FRAME_ORIGINS"
  fi
  local frame_file
  frame_file="$(mktemp)"
  for origin in "${frame_origins[@]}"; do
    validate_origin "$origin" VIDEO_FRAME_ORIGINS
    printf '%s\n' "${origin,,}" >>"$frame_file"
  done
  local frames=''
  if [[ -s "$frame_file" ]]; then
    frames="$(LC_ALL=C sort -u "$frame_file" | paste -sd' ' -)"
  fi
  rm -f -- "$frame_file"

  local parent
  parent="$(dirname "$output_directory")"
  mkdir -p -- "$parent"
  parent="$(realpath -e -- "$parent")"
  output_directory="$parent/$(basename "$output_directory")"
  [[ ! -e "$output_directory" || -d "$output_directory" ]] ||
    fail 'OUTPUT_DIRECTORY exists and is not a directory'
  mkdir -p -- "$output_directory"
  output_directory="$(realpath -e -- "$output_directory")"
  WORK_DIRECTORY="$(mktemp -d "$output_directory/.render.XXXXXX")"

  local icp="${ICP_NUMBER:-not-applicable-before-public-cutover}"
  [[ "$icp" != *[[:cntrl:]\;\"\']* && "$icp" != *'&'* &&
     "${icp//\\/}" == "$icp" ]] || fail 'ICP_NUMBER contains an unsafe character'

  replace_template \
    "$TEMPLATE" \
    "$WORK_DIRECTORY/portfolio-site.conf" \
    "$hosts" \
    "$TLS_CERTIFICATE" \
    "$TLS_CERTIFICATE_KEY" \
    "$API_LOOPBACK" \
    "$include_directory" \
    "$icp"

  cat >"$WORK_DIRECTORY/portfolio-proxy.conf" <<'NGINX'
proxy_http_version 1.1;
proxy_set_header Host $host;
proxy_set_header X-Real-IP $remote_addr;
proxy_set_header X-Forwarded-For $remote_addr;
proxy_set_header X-Forwarded-Proto $scheme;
proxy_set_header Range $http_range;
proxy_set_header If-Range $http_if_range;
proxy_connect_timeout 5s;
proxy_send_timeout 30s;
proxy_read_timeout 60s;
client_max_body_size 32m;
proxy_request_buffering on;
proxy_buffering on;
NGINX

  local frame_source="'self'"
  [[ -z "$frames" ]] || frame_source="$frame_source $frames"
  cat >"$WORK_DIRECTORY/portfolio-security-headers.conf" <<NGINX
add_header Content-Security-Policy "default-src 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'none'; form-action 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; font-src 'self' data:; img-src 'self' data: blob: $MEDIA_ORIGIN; media-src 'self' blob: $MEDIA_ORIGIN; connect-src 'self' $MEDIA_ORIGIN; frame-src $frame_source; upgrade-insecure-requests" always;
add_header X-Content-Type-Options "nosniff" always;
add_header Referrer-Policy "strict-origin-when-cross-origin" always;
add_header Permissions-Policy "camera=(), microphone=(), geolocation=(), payment=(), usb=()" always;
add_header X-Frame-Options "DENY" always;
add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
NGINX

  local generated
  for generated in \
    portfolio-site.conf \
    portfolio-proxy.conf \
    portfolio-security-headers.conf
  do
    [[ ! -L "$output_directory/$generated" ]] ||
      fail "refusing to replace symbolic link $generated"
    chmod 0640 "$WORK_DIRECTORY/$generated"
  done
  for generated in \
    portfolio-site.conf \
    portfolio-proxy.conf \
    portfolio-security-headers.conf
  do
    mv -f -- "$WORK_DIRECTORY/$generated" "$output_directory/$generated"
  done
  rmdir -- "$WORK_DIRECTORY"
  WORK_DIRECTORY=''
  printf '%s\n' "$output_directory"
}

main "$@"
