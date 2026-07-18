#!/usr/bin/env bash
set -euo pipefail
set +x
umask 077

SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=deploy/restore/adapters/adapter-lib.sh
source "$SCRIPT_DIRECTORY/adapter-lib.sh"

curl_local() {
  "$RESTORE_CURL_COMMAND" --disable --noproxy '*' --fail --silent --show-error \
    --proto '=https' --tlsv1.2 \
    --cacert "$RESTORE_TLS_CA_CERT" \
    --resolve "$RESTORE_TLS_SERVER_NAME:$RESTORE_NGINX_PORT:127.0.0.1" "$@"
}

csrf_values() {
  local output="$1"
  local status
  status="$(curl_local --cookie "$RESTORE_ADMIN_COOKIE_JAR" --cookie-jar "$RESTORE_ADMIN_COOKIE_JAR" \
    --output "$output" --write-out '%{http_code}' \
    "https://$RESTORE_TLS_SERVER_NAME:$RESTORE_NGINX_PORT/api/admin/auth/csrf")" ||
    adapter_fail 'administrator CSRF bootstrap failed'
  [[ "$status" == 200 ]] || adapter_fail 'administrator CSRF bootstrap returned a non-200 status'
  jq -er '[.headerName,.token] | @tsv' "$output" || adapter_fail 'administrator CSRF response is invalid'
}

authenticate_drill_admin() {
  adapter_require_private_file "${RESTORE_ADMIN_AUTH_FILE:-}" 'drill administrator authentication secret'
  [[ "$RESTORE_ADMIN_AUTH_FILE" == "$RESTORE_SECRETS_DIRECTORY/admin-auth.json" &&
     "$RESTORE_ADMIN_COOKIE_JAR" == "$RESTORE_ROOT/work/admin-cookie.jar" ]] ||
    adapter_fail 'administrator authentication files escaped the drill secret/work directories'
  jq -e '
    (keys | sort) == (["code","method","password","username"] | sort) and
    (.username | type == "string" and test("^[A-Za-z0-9._-]{3,64}$")) and
    (.password | type == "string" and length > 0 and length <= 256) and
    (.method == "RECOVERY_CODE") and
    (.code | type == "string" and length > 0 and length <= 64)
  ' "$RESTORE_ADMIN_AUTH_FILE" >/dev/null ||
    adapter_fail 'drill administrator authentication secret has an invalid shape'
  : >"$RESTORE_ADMIN_COOKIE_JAR"
  chmod 0600 "$RESTORE_ADMIN_COOKIE_JAR"
  local csrf_file="$RESTORE_ROOT/work/admin-csrf.json"
  local header token
  IFS=$'\t' read -r header token < <(csrf_values "$csrf_file")
  [[ "$header" =~ ^[A-Za-z0-9-]{1,128}$ && -n "$token" ]] || adapter_fail 'administrator CSRF values are invalid'
  local password_request="$RESTORE_ROOT/work/admin-password.json"
  jq -c '{username,password}' "$RESTORE_ADMIN_AUTH_FILE" >"$password_request"
  local response="$RESTORE_ROOT/work/admin-password-response.json" status
  status="$(curl_local --cookie "$RESTORE_ADMIN_COOKIE_JAR" --cookie-jar "$RESTORE_ADMIN_COOKIE_JAR" \
    --header 'Content-Type: application/json' --header "$header: $token" \
    --data-binary "@$password_request" --output "$response" --write-out '%{http_code}' \
    "https://$RESTORE_TLS_SERVER_NAME:$RESTORE_NGINX_PORT/api/admin/auth/password")" ||
    adapter_fail 'administrator password stage failed'
  [[ "$status" == 200 ]] || adapter_fail 'administrator password stage returned a non-200 status'
  jq -e '.next == "SECOND_FACTOR"' "$response" >/dev/null || adapter_fail 'administrator password stage response is invalid'
  IFS=$'\t' read -r header token < <(csrf_values "$csrf_file")
  local factor_request="$RESTORE_ROOT/work/admin-factor.json"
  jq -c '{method,code}' "$RESTORE_ADMIN_AUTH_FILE" >"$factor_request"
  status="$(curl_local --cookie "$RESTORE_ADMIN_COOKIE_JAR" --cookie-jar "$RESTORE_ADMIN_COOKIE_JAR" \
    --header 'Content-Type: application/json' --header "$header: $token" \
    --data-binary "@$factor_request" --output "$response" --write-out '%{http_code}' \
    "https://$RESTORE_TLS_SERVER_NAME:$RESTORE_NGINX_PORT/api/admin/auth/second-factor")" ||
    adapter_fail 'administrator second-factor stage failed'
  [[ "$status" == 200 ]] || adapter_fail 'administrator second-factor stage returned a non-200 status'
  jq -e '.id | type == "string"' "$response" >/dev/null || adapter_fail 'administrator second-factor response is invalid'
  status="$(curl_local --cookie "$RESTORE_ADMIN_COOKIE_JAR" \
    --output "$response" --write-out '%{http_code}' \
    "https://$RESTORE_TLS_SERVER_NAME:$RESTORE_NGINX_PORT/api/admin/auth/me")" ||
    adapter_fail 'administrator authenticated-session verification failed'
  [[ "$status" == 200 ]] || adapter_fail 'administrator session is not authenticated in the isolated API'
  rm -f -- "$password_request" "$factor_request" "$csrf_file" "$response"
}

verify_asset_route() {
  local root="$1"
  local prefix="$2"
  local label="$3"
  local source relative output
  source="$(find "$root/assets" -type f -print | LC_ALL=C sort | head -1)"
  [[ -n "$source" && -f "$source" && ! -L "$source" ]] ||
    adapter_fail "$label release asset is missing"
  relative="${source#"$root/assets/"}"
  adapter_safe_relative_path "$relative" || adapter_fail "$label release asset path is unsafe"
  output="$RESTORE_ROOT/work/route-$label.asset"
  curl_local --output "$output" \
    "https://$RESTORE_TLS_SERVER_NAME:$RESTORE_NGINX_PORT/$prefix/$relative" ||
    adapter_fail "$label asset route failed through the isolated Nginx"
  cmp -s -- "$source" "$output" || adapter_fail "$label asset route bytes differ from the release"
  rm -f -- "$output"
}

main() {
  (($# == 0)) || adapter_fail 'start-routes.sh accepts no arguments'
  adapter_require_drill_context
  adapter_require_disposed_identity
  adapter_resolve_command RESTORE_DOCKER_COMMAND docker
  adapter_resolve_command RESTORE_CURL_COMMAND curl
  adapter_require_value RESTORE_COMPOSE_FILE
  adapter_require_value RESTORE_NGINX_CONFIG_DIR
  adapter_require_value RESTORE_ADMIN_ASSETS_ROOT
  adapter_require_value RESTORE_PUBLIC_ASSETS_ROOT
  adapter_require_value RESTORE_TLS_SERVER_NAME
  adapter_require_value RESTORE_NGINX_PORT
  [[ "$RESTORE_TLS_SERVER_NAME" =~ ^[a-z0-9]([a-z0-9.-]{0,61}[a-z0-9])?$ &&
     "$RESTORE_TLS_SERVER_NAME" == *.* && "$RESTORE_TLS_SERVER_NAME" != *..* ]] ||
    adapter_fail 'drill TLS server name is invalid'
  [[ "$RESTORE_NGINX_PORT" =~ ^[1-9][0-9]{0,4}$ ]] || adapter_fail 'drill Nginx port is invalid'
  local release_root="$RESTORE_ROOT/release"
  [[ "$RESTORE_NGINX_CONFIG_DIR" == "$release_root/runtime/nginx" &&
     "$RESTORE_ADMIN_ASSETS_ROOT" == "$release_root/admin" &&
     "$RESTORE_PUBLIC_ASSETS_ROOT" == "$release_root/public-assets" ]] ||
    adapter_fail 'Nginx configuration or assets escaped the verified drill release root'
  [[ -f "$release_root/release.json" && ! -L "$release_root/release.json" &&
     -d "$RESTORE_ADMIN_ASSETS_ROOT/assets" && -d "$RESTORE_PUBLIC_ASSETS_ROOT/assets" ]] ||
    adapter_fail 'verified release assets are incomplete'
  adapter_require_private_file "${RESTORE_TLS_CERTIFICATE:-}" 'drill TLS certificate'
  adapter_require_private_file "${RESTORE_TLS_PRIVATE_KEY:-}" 'drill TLS private key'
  adapter_require_private_file "${RESTORE_TLS_CA_CERT:-}" 'drill TLS CA certificate'
  [[ "$RESTORE_TLS_CERTIFICATE" == "$RESTORE_SECRETS_DIRECTORY/"* &&
     "$RESTORE_TLS_PRIVATE_KEY" == "$RESTORE_SECRETS_DIRECTORY/"* &&
     "$RESTORE_TLS_CA_CERT" == "$RESTORE_SECRETS_DIRECTORY/"* ]] ||
    adapter_fail 'TLS files must be supplied by the drill-specific tmpfs secret directory'
  [[ ! -e "$RESTORE_NGINX_CONFIG_DIR" && ! -L "$RESTORE_NGINX_CONFIG_DIR" ]] ||
    adapter_fail 'Nginx runtime configuration already exists'
  mkdir -p -- "$RESTORE_NGINX_CONFIG_DIR"
  chmod 0700 "$RESTORE_NGINX_CONFIG_DIR"
  cat >"$RESTORE_NGINX_CONFIG_DIR/nginx.conf" <<NGINX
pid /tmp/nginx.pid;
error_log /dev/stderr notice;
events { worker_connections 256; }
http {
  access_log /dev/stdout;
  server_tokens off;
  default_type application/octet-stream;
  types {
    text/html html htm;
    text/css css;
    application/javascript js mjs;
    application/json json;
    image/svg+xml svg;
    image/png png;
    image/jpeg jpg jpeg;
    image/webp webp;
    font/woff2 woff2;
  }
  client_max_body_size 32m;
  proxy_connect_timeout 5s;
  proxy_send_timeout 30s;
  proxy_read_timeout 60s;
  server {
    listen 443 ssl;
    server_name $RESTORE_TLS_SERVER_NAME;
    ssl_certificate /run/portfolio-restore-secrets/tls.crt;
    ssl_certificate_key /run/portfolio-restore-secrets/tls.key;
    ssl_protocols TLSv1.2 TLSv1.3;
    add_header X-Content-Type-Options nosniff always;
    add_header Cache-Control no-store always;

    location ^~ /assets/ {
      alias /srv/portfolio-release/public-assets/assets/;
      autoindex off;
      limit_except GET { deny all; }
    }
    location = /admin { return 308 /admin/; }
    location ^~ /admin/assets/ {
      alias /srv/portfolio-release/admin/assets/;
      autoindex off;
      limit_except GET { deny all; }
    }
    location /admin/ {
      alias /srv/portfolio-release/admin/;
      try_files \$uri \$uri/ /admin/index.html;
    }
    location = /actuator { return 404; }
    location ^~ /actuator/ { return 404; }
    location / {
      proxy_pass http://api:8080;
      proxy_http_version 1.1;
      proxy_set_header Host \$host;
      proxy_set_header X-Real-IP \$remote_addr;
      proxy_set_header X-Forwarded-For \$remote_addr;
      proxy_set_header X-Forwarded-Proto https;
      proxy_set_header Range \$http_range;
      proxy_set_header If-Range \$http_if_range;
    }
  }
}
NGINX
  chmod 0644 "$RESTORE_NGINX_CONFIG_DIR/nginx.conf"
  PORTFOLIO_RESTORE_PHASE=route-start adapter_compose up -d --wait --wait-timeout 240 \
    restore-api restore-nginx >/dev/null || adapter_fail 'isolated API and Nginx did not become healthy'
  verify_asset_route "$RESTORE_PUBLIC_ASSETS_ROOT" assets public
  verify_asset_route "$RESTORE_ADMIN_ASSETS_ROOT" admin/assets admin
  authenticate_drill_admin
}

main "$@"
