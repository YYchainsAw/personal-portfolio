#!/usr/bin/env bash
set -euo pipefail
set +x
umask 077

SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
readonly SCRIPT_DIRECTORY

WORK_DIRECTORY=''
PUBLIC_CUTOVER=false
RELOAD_NGINX=false
PORTFOLIO_API_CONTAINER_ID=''

fail() {
  printf 'Portfolio preflight failed: %s\n' "$1" >&2
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
on_error() {
  local status="$?"
  local line="$1"
  printf 'Portfolio preflight failed: internal command failed at line %s\n' "$line" >&2
  exit "$status"
}
trap cleanup EXIT
trap on_signal HUP INT TERM
trap 'on_error "$LINENO"' ERR

require_value() {
  local name="$1"
  [[ -n "${!name:-}" ]] || fail "$name is required"
  [[ "${!name}" != *$'\n'* && "${!name}" != *$'\r'* ]] ||
    fail "$name contains a control character"
}

require_true() {
  local name="$1"
  [[ "${!name:-}" == 'true' ]] || fail "$name must be true"
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "$1 is required"
}

canonical_existing() {
  local value="$1"
  local label="$2"
  [[ "$value" == /* ]] || fail "$label must be absolute"
  realpath -e -- "$value" 2>/dev/null || fail "$label does not resolve"
}

is_beneath() {
  local child="$1"
  local parent="${2%/}"
  [[ "$child" == "$parent" || "$child" == "$parent/"* ]]
}

constant_time_equal() {
  local left="$1"
  local right="$2"
  local left_hash right_hash difference=0 index
  left_hash="$(printf '%s' "$left" | sha256sum | awk '{print $1}')"
  right_hash="$(printf '%s' "$right" | sha256sum | awk '{print $1}')"
  for ((index = 0; index < ${#left_hash}; index++)); do
    [[ "${left_hash:index:1}" == "${right_hash:index:1}" ]] || difference=1
  done
  ((difference == 0))
}

check_jurisdiction_gate() {
  require_value SERVER_JURISDICTION
  require_value TENCENT_REGION
  case "$SERVER_JURISDICTION" in
    MAINLAND_CN)
      if [[ "$PUBLIC_CUTOVER" == 'true' ]] &&
         { [[ "${ICP_APPROVED:-}" != 'true' ]] ||
           [[ -z "${ICP_NUMBER:-}" ]] ||
           [[ "${PUBLIC_DOMAIN_ENABLED:-}" != 'true' ]]; }; then
        fail 'ICP approval required for mainland public cutover'
      fi
      ;;
    HONG_KONG|OVERSEAS)
      printf 'Mainland ICP gate does not apply to jurisdiction %s\n' \
        "$SERVER_JURISDICTION"
      ;;
    *)
      fail 'SERVER_JURISDICTION must be MAINLAND_CN, HONG_KONG, or OVERSEAS'
      ;;
  esac

  if [[ "$PUBLIC_CUTOVER" == 'true' ]]; then
    require_true PUBLIC_DOMAIN_ENABLED
    require_value PUBLIC_HOSTS
    require_value EXPECTED_PUBLIC_IPV4
    require_value EXPECTED_PUBLIC_IPV6
  fi
}

check_prerequisites() {
  local command
  for command in \
    age awk curl date df dig docker find findmnt grep jq openssl python3 readlink \
    realpath rclone sed sha256sum sort ss stat timedatectl unzip zstd
  do
    require_command "$command"
  done

  local docker_version major
  docker_version="$(docker version --format '{{.Server.Version}}')" ||
    fail 'Docker server version is unavailable'
  major="${docker_version%%.*}"
  [[ "$major" =~ ^[0-9]+$ ]] || fail 'Docker server version is invalid'
  ((10#$major >= 26)) || fail 'Docker Engine 26 or newer is required'
  docker compose version >/dev/null 2>&1 || fail 'Docker Compose is required'

  [[ "$(timedatectl show -p NTPSynchronized --value 2>/dev/null)" == 'yes' ]] ||
    fail 'system clock is not synchronized'
}

check_protected_file() {
  local path="$1"
  local expected_owner_group="$2"
  local expected_mode="$3"
  local label="$4"
  [[ ! -L "$path" && -f "$path" ]] || fail "$label is missing or is a symbolic link"
  local actual
  actual="$(stat -Lc '%U:%G:%a' -- "$path")" || fail "$label metadata is unavailable"
  [[ "$actual" == "$expected_owner_group:$expected_mode" ]] ||
    fail "$label ownership or mode is not protected"
}

check_protected_configuration() {
  require_value PORTFOLIO_POSTGRES_ENV
  require_value PORTFOLIO_APP_ENV
  require_value PORTFOLIO_RELEASE_ENV
  require_value PORTFOLIO_NGINX_ENV
  local deploy_group="${PORTFOLIO_DEPLOY_GROUP:-portfolio-deploy}"
  check_protected_file "$PORTFOLIO_POSTGRES_ENV" root:root 600 postgres.env
  check_protected_file "$PORTFOLIO_APP_ENV" root:root 600 portfolio.env
  check_protected_file \
    "$PORTFOLIO_RELEASE_ENV" "root:$deploy_group" 640 release.env
  check_protected_file \
    "$PORTFOLIO_NGINX_ENV" "root:$deploy_group" 640 nginx.env
}

nginx_syntax() {
  "$NGINX_BIN" -p "$NGINX_PREFIX" -c "$NGINX_CONF" -t
}

nginx_reload() {
  "$NGINX_BIN" -p "$NGINX_PREFIX" -c "$NGINX_CONF" -s reload
}

check_baota_nginx() {
  require_value NGINX_BIN
  require_value NGINX_PREFIX
  require_value NGINX_CONF
  require_value NGINX_PID
  require_value TLS_CERTIFICATE
  require_value TLS_CERTIFICATE_KEY

  NGINX_PREFIX="$(canonical_existing "$NGINX_PREFIX" NGINX_PREFIX)/"
  NGINX_BIN="$(canonical_existing "$NGINX_BIN" NGINX_BIN)"
  NGINX_CONF="$(canonical_existing "$NGINX_CONF" NGINX_CONF)"
  NGINX_PID="$(canonical_existing "$NGINX_PID" NGINX_PID)"
  [[ -x "$NGINX_BIN" ]] || fail 'NGINX_BIN is not executable'
  [[ -f "$NGINX_CONF" && -f "$NGINX_PID" ]] || fail 'BaoTa Nginx files are invalid'
  is_beneath "$NGINX_BIN" "$NGINX_PREFIX" || fail 'NGINX_BIN is outside NGINX_PREFIX'
  is_beneath "$NGINX_CONF" "$NGINX_PREFIX" || fail 'NGINX_CONF is outside NGINX_PREFIX'
  is_beneath "$NGINX_PID" "$NGINX_PREFIX" || fail 'NGINX_PID is outside NGINX_PREFIX'

  local pid
  pid="$(tr -d '[:space:]' <"$NGINX_PID")"
  [[ "$pid" =~ ^[1-9][0-9]*$ ]] || fail 'NGINX_PID does not contain one process ID'
  local proc_root="${PORTFOLIO_PROC_ROOT:-/proc}"
  proc_root="$(canonical_existing "$proc_root" PORTFOLIO_PROC_ROOT)"
  [[ -e "$proc_root/$pid/exe" ]] || fail 'BaoTa Nginx PID is not running'
  local process_executable
  process_executable="$(readlink -f -- "$proc_root/$pid/exe")" ||
    fail 'BaoTa Nginx executable cannot be resolved'
  [[ "$process_executable" == "$NGINX_BIN" ]] ||
    fail 'BaoTa Nginx PID executable does not equal NGINX_BIN'

  local listeners
  listeners="$(ss -H -ltnp 2>/dev/null)" || fail 'public listeners cannot be inspected'
  if ! grep -E ":80[[:space:]].*pid=${pid}," <<<"$listeners" >/dev/null ||
     ! grep -E ":443[[:space:]].*pid=${pid}," <<<"$listeners" >/dev/null; then
    fail 'BaoTa Nginx PID does not own both public listener ports'
  fi

  TLS_CERTIFICATE="$(canonical_existing "$TLS_CERTIFICATE" TLS_CERTIFICATE)"
  TLS_CERTIFICATE_KEY="$(canonical_existing "$TLS_CERTIFICATE_KEY" TLS_CERTIFICATE_KEY)"
  [[ -f "$TLS_CERTIFICATE" && -r "$TLS_CERTIFICATE" ]] ||
    fail 'TLS certificate is not readable'
  [[ ! -L "$TLS_CERTIFICATE_KEY" && -f "$TLS_CERTIFICATE_KEY" ]] ||
    fail 'TLS private key is missing or is a symbolic link'
  local key_mode
  key_mode="$(stat -Lc '%a' -- "$TLS_CERTIFICATE_KEY")"
  [[ "$key_mode" == 600 || "$key_mode" == 640 ]] ||
    fail 'TLS private key mode must be 0600 or 0640'
  [[ "$(stat -Lc '%U' -- "$TLS_CERTIFICATE_KEY")" == root ]] ||
    fail 'TLS private key must be owned by root'
  openssl x509 -checkend 86400 -noout -in "$TLS_CERTIFICATE" >/dev/null 2>&1 ||
    fail 'TLS certificate is invalid or expires within 24 hours'
  openssl x509 -pubkey -noout -in "$TLS_CERTIFICATE" \
    >"$WORK_DIRECTORY/tls-certificate.pub" 2>/dev/null ||
    fail 'TLS certificate public key cannot be read'
  openssl pkey -pubout -in "$TLS_CERTIFICATE_KEY" \
    >"$WORK_DIRECTORY/tls-private-key.pub" 2>/dev/null ||
    fail 'TLS private key cannot be read'
  cmp -s "$WORK_DIRECTORY/tls-certificate.pub" "$WORK_DIRECTORY/tls-private-key.pub" ||
    fail 'TLS certificate and private key do not match'

  nginx_syntax || fail 'BaoTa Nginx syntax check failed'
}

check_release_and_host() {
  require_value PORTFOLIO_ROOT
  require_value PORTFOLIO_RELEASE_ID
  [[ "$PORTFOLIO_RELEASE_ID" =~ ^[0-9a-f]{12}-[0-9a-f]{12}$ ]] ||
    fail 'PORTFOLIO_RELEASE_ID has an invalid format'
  PORTFOLIO_ROOT="$(canonical_existing "$PORTFOLIO_ROOT" PORTFOLIO_ROOT)"
  local release_directory="$PORTFOLIO_ROOT/releases/$PORTFOLIO_RELEASE_ID"
  release_directory="$(canonical_existing "$release_directory" release-directory)"
  is_beneath "$release_directory" "$PORTFOLIO_ROOT/releases" ||
    fail 'release directory escaped PORTFOLIO_ROOT'
  local metadata="$release_directory/release.json"
  [[ ! -L "$metadata" && -f "$metadata" ]] || fail 'release.json is missing'
  local recorded_release
  recorded_release="$(jq -er '.releaseId | select(type == "string")' "$metadata")" ||
    fail 'release.json does not contain a release ID'
  [[ "$recorded_release" == "$PORTFOLIO_RELEASE_ID" ]] ||
    fail 'release bundle does not match PORTFOLIO_RELEASE_ID'

  local available_kib minimum_kib="${PORTFOLIO_MIN_FREE_KIB:-2097152}"
  [[ "$minimum_kib" =~ ^[1-9][0-9]*$ ]] || fail 'PORTFOLIO_MIN_FREE_KIB is invalid'
  available_kib="$(df -Pk -- "$PORTFOLIO_ROOT" | awk 'NR == 2 {print $4}')"
  [[ "$available_kib" =~ ^[0-9]+$ ]] || fail 'disk headroom cannot be measured'
  ((available_kib >= minimum_kib)) || fail 'insufficient deployment disk headroom'

  curl --fail --silent --show-error --max-time 5 \
    http://127.0.0.1:18080/actuator/health/readiness >/dev/null ||
    fail 'API loopback readiness failed'
}

resolve_portfolio_api_container() {
  if [[ -n "$PORTFOLIO_API_CONTAINER_ID" ]]; then
    printf '%s\n' "$PORTFOLIO_API_CONTAINER_ID"
    return
  fi
  local container="${PORTFOLIO_CONTAINER_NAME:-}"
  if [[ -z "$container" ]]; then
    container="$(docker compose \
      --env-file "$PORTFOLIO_RELEASE_ENV" \
      -f "$PORTFOLIO_COMPOSE_FILE" \
      ps -q portfolio-api)" || fail 'running portfolio-api container cannot be resolved'
  fi
  [[ "$container" != *[[:space:]]* && -n "$container" ]] ||
    fail 'running portfolio-api container identity is missing or ambiguous'
  PORTFOLIO_API_CONTAINER_ID="$container"
  printf '%s\n' "$PORTFOLIO_API_CONTAINER_ID"
}

check_local_storage() {
  require_value PORTFOLIO_LOCAL_VOLUME_NAME
  require_value PORTFOLIO_LOCAL_HOST_ROOT
  require_value PORTFOLIO_LOCAL_VOLUME_ID
  require_true PORTFOLIO_JOBS_WORKER_ENABLED
  require_true PORTFOLIO_STAGING_CLEANUP_ENABLED
  require_true PORTFOLIO_MEDIA_CLEANUP_ENABLED

  PORTFOLIO_LOCAL_HOST_ROOT="$(canonical_existing \
    "$PORTFOLIO_LOCAL_HOST_ROOT" PORTFOLIO_LOCAL_HOST_ROOT)"
  [[ "$PORTFOLIO_LOCAL_VOLUME_NAME" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$ ]] ||
    fail 'PORTFOLIO_LOCAL_VOLUME_NAME is invalid'
  [[ -d "$PORTFOLIO_LOCAL_HOST_ROOT" && -w "$PORTFOLIO_LOCAL_HOST_ROOT" ]] ||
    fail 'Local storage root is not writable'

  local volume_identity volume_name volume_mountpoint volume_extra
  volume_identity="$(docker volume inspect \
    --format '{{.Name}}|{{.Mountpoint}}' \
    "$PORTFOLIO_LOCAL_VOLUME_NAME")" ||
    fail 'Local Docker volume cannot be inspected'
  IFS='|' read -r volume_name volume_mountpoint volume_extra <<<"$volume_identity"
  [[ "$volume_name" == "$PORTFOLIO_LOCAL_VOLUME_NAME" &&
     -n "$volume_mountpoint" && -z "${volume_extra:-}" ]] ||
    fail 'Local Docker volume identity is ambiguous or mismatched'
  volume_mountpoint="$(canonical_existing "$volume_mountpoint" Local-volume-mountpoint)"
  [[ "$volume_mountpoint" == "$PORTFOLIO_LOCAL_HOST_ROOT" ]] ||
    fail 'Local Docker volume Mountpoint does not match PORTFOLIO_LOCAL_HOST_ROOT'

  local container mount_rows mount_type mount_name mount_source mount_destination mount_rw
  local mount_extra matching_mounts=0 named_volume_mounts=0
  container="$(resolve_portfolio_api_container)"
  mount_rows="$(docker inspect --format \
    '{{range .Mounts}}{{printf "%s|%s|%s|%s|%t\n" .Type .Name .Source .Destination .RW}}{{end}}' \
    "$container")" || fail 'running portfolio-api mounts cannot be inspected'
  while IFS='|' read -r \
      mount_type mount_name mount_source mount_destination mount_rw mount_extra; do
    [[ -n "$mount_type" ]] || continue
    [[ -z "${mount_extra:-}" ]] || fail 'running portfolio-api mount metadata is malformed'
    if [[ "$mount_name" == "$PORTFOLIO_LOCAL_VOLUME_NAME" ]]; then
      ((named_volume_mounts += 1))
    fi
    if [[ "$mount_destination" == '/var/lib/portfolio/media' ]]; then
      ((matching_mounts += 1))
      [[ "$mount_type" == volume &&
         "$mount_name" == "$PORTFOLIO_LOCAL_VOLUME_NAME" &&
         "$mount_rw" == true ]] ||
        fail 'running durable media mount is not the reviewed read-write named volume'
      mount_source="$(canonical_existing "$mount_source" running-Local-volume-source)"
      [[ "$mount_source" == "$PORTFOLIO_LOCAL_HOST_ROOT" ]] ||
        fail 'running durable media source does not match the reviewed volume Mountpoint'
    fi
  done <<<"$mount_rows"
  [[ "$matching_mounts" -eq 1 && "$named_volume_mounts" -eq 1 ]] ||
    fail 'reviewed Local named volume is missing, duplicated, or mounted at an extra target'

  local marker="$PORTFOLIO_LOCAL_HOST_ROOT/.portfolio-volume-id"
  [[ ! -L "$marker" && -f "$marker" ]] ||
    fail 'Local volume identity marker is missing, replaced, or linked'
  local marker_mode root_uid marker_uid
  marker_mode="$(stat -Lc '%a' -- "$marker")"
  root_uid="$(stat -Lc '%u' -- "$PORTFOLIO_LOCAL_HOST_ROOT")"
  marker_uid="$(stat -Lc '%u' -- "$marker")"
  [[ "$marker_mode" == 600 && "$marker_uid" == "$root_uid" ]] ||
    fail 'Local volume identity marker is not owner-only'
  local recorded_marker
  recorded_marker="$(<"$marker")"
  constant_time_equal "$recorded_marker" "$PORTFOLIO_LOCAL_VOLUME_ID" ||
    fail 'Local volume identity does not match the protected value'

  local mount_line mount_target mount_source mount_filesystem mount_options extra
  mount_line="$(findmnt -rn -T "$PORTFOLIO_LOCAL_HOST_ROOT" \
    -o TARGET,SOURCE,FSTYPE,OPTIONS)" || fail 'Local storage mount cannot be inspected'
  read -r mount_target mount_source mount_filesystem mount_options extra <<<"$mount_line"
  [[ -n "$mount_target" && -n "$mount_source" && -n "$mount_filesystem" &&
     -n "$mount_options" && -z "${extra:-}" ]] ||
    fail 'Local storage has an ambiguous mount'
  [[ "$mount_filesystem" != tmpfs && "$mount_filesystem" != ramfs ]] ||
    fail 'Local storage is backed by an ephemeral filesystem'
  has_csv_option "$mount_options" rw ||
    fail 'Local storage backing filesystem is not read-write'

  local free_kib minimum_local_kib="${PORTFOLIO_LOCAL_MIN_FREE_KIB:-1048576}"
  [[ "$minimum_local_kib" =~ ^[1-9][0-9]*$ ]] ||
    fail 'PORTFOLIO_LOCAL_MIN_FREE_KIB is invalid'
  free_kib="$(df -Pk -- "$PORTFOLIO_LOCAL_HOST_ROOT" | awk 'NR == 2 {print $4}')"
  [[ "$free_kib" =~ ^[0-9]+$ && "$free_kib" -ge "$minimum_local_kib" ]] ||
    fail 'Local storage has insufficient headroom'

  local scan_limit="${PORTFOLIO_LOCAL_STAGING_SCAN_LIMIT:-100000}"
  [[ "$scan_limit" =~ ^[1-9][0-9]*$ && "$scan_limit" -le 1000000 ]] ||
    fail 'PORTFOLIO_LOCAL_STAGING_SCAN_LIMIT is invalid'
  local staging="$PORTFOLIO_LOCAL_HOST_ROOT/staging" count=0 entry
  local scan_started_ns scan_finished_ns duration_ms
  scan_started_ns="$(date +%s%N)"
  [[ "$scan_started_ns" =~ ^[0-9]+$ ]] || fail 'cleanup scan timer is unavailable'
  if [[ -d "$staging" ]]; then
    while IFS= read -r -d '' entry; do
      : "$entry"
      ((count += 1))
      ((count < scan_limit)) ||
        fail 'Local staging entry count reached the configured hard scan ceiling'
    done < <(find "$staging" -xdev -mindepth 1 -print0)
  fi
  scan_finished_ns="$(date +%s%N)"
  [[ "$scan_finished_ns" =~ ^[0-9]+$ &&
     "$scan_finished_ns" -ge "$scan_started_ns" ]] ||
    fail 'cleanup scan timer moved backwards'
  duration_ms=$(((scan_finished_ns - scan_started_ns + 999999) / 1000000))
  local lease_ms="${PORTFOLIO_JOB_LEASE_MILLIS:-120000}"
  local fraction="${PORTFOLIO_CLEANUP_SCAN_MAX_LEASE_PERCENT:-50}"
  [[ "$lease_ms" =~ ^[1-9][0-9]*$ && "$fraction" =~ ^[1-9][0-9]*$ &&
     "$fraction" -lt 100 ]] || fail 'cleanup scan lease fraction is invalid'
  ((duration_ms * 100 < lease_ms * fraction)) ||
    fail 'latest cleanup scan duration is not below the reviewed lease fraction'
}

add_required_provider() {
  local provider="$1"
  case "$provider" in
    LOCAL|TENCENT_COS) REQUIRED_PROVIDERS["$provider"]=1 ;;
    *) fail 'an unconfigured or unknown media provider is required' ;;
  esac
}

check_storage_providers() {
  require_value PORTFOLIO_STORAGE_ADAPTERS
  require_value PORTFOLIO_STORAGE_DEFAULT_PROVIDER
  require_value PORTFOLIO_COMPOSE_FILE
  [[ -f "$PORTFOLIO_COMPOSE_FILE" ]] || fail 'production Compose file is missing'

  declare -gA REQUIRED_PROVIDERS=()
  declare -A configured_providers=()
  declare -A configured_cos=()
  local -a adapters=() locations=()
  local provider location bucket region extra
  IFS=',' read -r -a adapters <<<"$PORTFOLIO_STORAGE_ADAPTERS"
  for provider in "${adapters[@]}"; do
    add_required_provider "$provider"
    configured_providers["$provider"]=1
  done
  [[ -n "${configured_providers[$PORTFOLIO_STORAGE_DEFAULT_PROVIDER]:-}" ]] ||
    fail 'default media writer is not configured'
  add_required_provider "$PORTFOLIO_STORAGE_DEFAULT_PROVIDER"

  if [[ -n "${PORTFOLIO_COS_LOCATIONS:-}" ]]; then
    IFS=',' read -r -a locations <<<"$PORTFOLIO_COS_LOCATIONS"
    for location in "${locations[@]}"; do
      [[ "$location" =~ ^([a-z0-9][a-z0-9-]{2,62})@([a-z0-9][a-z0-9-]{2,31})$ ]] ||
        fail 'PORTFOLIO_COS_LOCATIONS contains an invalid location'
      configured_cos["${BASH_REMATCH[1]}@${BASH_REMATCH[2]}"]=1
    done
  fi

  local inventory="$WORK_DIRECTORY/media-locations.tsv"
  local inventory_sql
  inventory_sql="select distinct provider, coalesce(bucket, ''), coalesce(region, '') from portfolio.media_asset order by 1,2,3"
  if ! docker compose \
      --env-file "$PORTFOLIO_RELEASE_ENV" \
      -f "$PORTFOLIO_COMPOSE_FILE" \
      exec -T postgres sh -eu -c \
      'export PGOPTIONS="-c default_transaction_read_only=on"; exec psql -X --no-psqlrc -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" --tuples-only --no-align --field-separator="|" -c "$1"' \
      portfolio-preflight "$inventory_sql" \
      >"$inventory" 2>"$WORK_DIRECTORY/media-locations.err"; then
    fail 'media provider inventory query failed'
  fi

  while IFS='|' read -r provider bucket region extra; do
    [[ -n "$provider" && -z "${extra:-}" ]] ||
      fail 'media provider inventory is malformed'
    add_required_provider "$provider"
    [[ -n "${configured_providers[$provider]:-}" ]] ||
      fail 'historical media provider is not configured'
    case "$provider" in
      LOCAL)
        [[ -z "$bucket" && -z "$region" ]] ||
          fail 'historical Local media location is malformed'
        ;;
      TENCENT_COS)
        [[ -n "$bucket" && -n "$region" ]] ||
          fail 'historical COS media location is incomplete'
        [[ -n "${configured_cos[$bucket@$region]:-}" ]] ||
          fail 'historical COS location is not configured'
        ;;
    esac
  done <"$inventory"

  if [[ -n "${REQUIRED_PROVIDERS[LOCAL]:-}" ]]; then
    check_local_storage
  fi

  if [[ -n "${REQUIRED_PROVIDERS[TENCENT_COS]:-}" ]]; then
    ((${#configured_cos[@]} > 0)) || fail 'required COS provider has no configured location'
    require_true COS_RUNTIME_LIFECYCLE_MANAGEMENT_DISABLED
    require_value COS_SECRET_ID
    require_value COS_SECRET_KEY
    require_value COS_BUCKET
    require_value COS_REGION
    [[ -n "${configured_cos[$COS_BUCKET@$COS_REGION]:-}" ]] ||
      fail 'runtime COS location is not configured'
    require_value COS_LIFECYCLE_READ_SECRET_ID
    require_value COS_LIFECYCLE_READ_SECRET_KEY
    require_value COS_LIFECYCLE_READ_SECURITY_TOKEN
    [[ "$COS_SECRET_ID" != "$COS_LIFECYCLE_READ_SECRET_ID" &&
       "$COS_SECRET_KEY" != "$COS_LIFECYCLE_READ_SECRET_KEY" ]] ||
      fail 'runtime and lifecycle-read COS credentials must be separate'
    local verifier="${PORTFOLIO_COS_LIFECYCLE_VERIFY_COMMAND:-$SCRIPT_DIRECTORY/verify-cos-staging-lifecycle.sh}"
    [[ "$verifier" == /* && -x "$verifier" ]] ||
      fail 'COS staging lifecycle verifier is not executable'
    for location in $(printf '%s\n' "${!configured_cos[@]}" | LC_ALL=C sort); do
      bucket="${location%@*}"
      region="${location#*@}"
      if ! env \
          COS_BUCKET="$bucket" \
          COS_REGION="$region" \
          COS_SECRET_ID="$COS_LIFECYCLE_READ_SECRET_ID" \
          COS_SECRET_KEY="$COS_LIFECYCLE_READ_SECRET_KEY" \
          COS_SECURITY_TOKEN="$COS_LIFECYCLE_READ_SECURITY_TOKEN" \
          "$verifier" --check-live \
          >"$WORK_DIRECTORY/lifecycle.out" \
          2>"$WORK_DIRECTORY/lifecycle.err"; then
        fail 'COS staging lifecycle verification failed'
      fi
    done
  fi
}

has_csv_option() {
  local options=",$1,"
  local wanted="$2"
  [[ "$options" == *",$wanted,"* ]]
}

check_tmpfs() {
  local compose="$PORTFOLIO_COMPOSE_FILE"
  grep -E '/tmp:.*size=[^,[:space:]]+.*noexec.*nosuid.*nodev' "$compose" >/dev/null ||
    fail 'rendered Compose /tmp is not a bounded noexec,nosuid,nodev tmpfs'
  grep -E 'java\.io\.tmpdir=/tmp([/[:space:]]|$)' "$compose" >/dev/null ||
    fail 'rendered JVM scratch is not beneath /tmp'
  grep -E 'PORTFOLIO_COS_STAGING_ROOT: /tmp/' "$compose" >/dev/null ||
    fail 'rendered COS scratch is not beneath /tmp'

  local container
  container="$(resolve_portfolio_api_container)"
  local options
  options="$(docker inspect --format '{{index .HostConfig.Tmpfs "/tmp"}}' "$container")" ||
    fail 'running /tmp tmpfs cannot be inspected'
  if [[ ! "$options" =~ (^|,)size=[1-9][0-9]*([kKmMgG])?(,|$) ]] ||
     ! has_csv_option "$options" noexec ||
     ! has_csv_option "$options" nosuid ||
     ! has_csv_option "$options" nodev; then
    fail 'running /tmp tmpfs is missing a required size or security option'
  fi

  local runtime_env java_tmp='' cos_tmp='' container_local='' line
  runtime_env="$(docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$container")" ||
    fail 'running scratch environment cannot be inspected'
  while IFS= read -r line; do
    case "$line" in
      JAVA_TOOL_OPTIONS=*)
        local java_options="${line#*=}"
        if [[ "$java_options" =~ (^|[[:space:]])-Djava\.io\.tmpdir=([^[:space:]]+) ]]; then
          java_tmp="${BASH_REMATCH[2]}"
        fi
        ;;
      PORTFOLIO_COS_STAGING_ROOT=*) cos_tmp="${line#*=}" ;;
      PORTFOLIO_LOCAL_STORAGE=*) container_local="${line#*=}" ;;
    esac
  done <<<"$runtime_env"
  [[ "$java_tmp" == /tmp || "$java_tmp" == /tmp/* ]] ||
    fail 'running JVM scratch is not beneath /tmp'
  [[ "$cos_tmp" == /tmp || "$cos_tmp" == /tmp/* ]] ||
    fail 'running COS scratch is not beneath /tmp'
  [[ "$container_local" == /* && "$container_local" != /tmp &&
     "$container_local" != /tmp/* ]] ||
    fail 'running durable media root is missing or beneath /tmp'
  [[ ! "$java_tmp" =~ (^|/)\.\.(/|$) && ! "$cos_tmp" =~ (^|/)\.\.(/|$) ]] ||
    fail 'running scratch path contains parent traversal'
  local resolved
  for resolved in "$java_tmp" "$cos_tmp"; do
    resolved="$(docker exec "$container" realpath -m "$resolved")" ||
      fail 'running scratch path cannot be resolved'
    [[ "$resolved" == /tmp || "$resolved" == /tmp/* ]] ||
      fail 'running scratch resolved outside /tmp'
    if is_beneath "$resolved" "$container_local"; then
      fail 'ephemeral scratch resolved beneath durable media'
    fi
  done
}

normalize_ip_file() {
  local input="$1"
  local output="$2"
  local version="$3"
  python3 - "$input" "$output" "$version" <<'PYTHON'
import ipaddress
import pathlib
import sys

source, destination, expected_version = sys.argv[1:4]
try:
    raw_lines = pathlib.Path(source).read_text(encoding="ascii").splitlines()
    normalized = set()
    for raw in raw_lines:
        value = raw.strip()
        if not value:
            continue
        address = ipaddress.ip_address(value)
        if address.version != int(expected_version):
            raise ValueError("wrong address family")
        normalized.add(address.compressed.lower())
    pathlib.Path(destination).write_text(
        "".join(f"{value}\n" for value in sorted(normalized)),
        encoding="ascii",
    )
except Exception:
    sys.exit(1)
PYTHON
}

check_dns() {
  local expected4_raw="$WORK_DIRECTORY/expected4.raw"
  local expected6_raw="$WORK_DIRECTORY/expected6.raw"
  printf '%s' "$EXPECTED_PUBLIC_IPV4" | tr ',' '\n' >"$expected4_raw"
  normalize_ip_file "$expected4_raw" "$WORK_DIRECTORY/expected4" 4 ||
    fail 'EXPECTED_PUBLIC_IPV4 is invalid'
  [[ -s "$WORK_DIRECTORY/expected4" ]] || fail 'EXPECTED_PUBLIC_IPV4 is empty'
  if [[ "$EXPECTED_PUBLIC_IPV6" == 'NONE' ]]; then
    : >"$WORK_DIRECTORY/expected6"
  else
    printf '%s' "$EXPECTED_PUBLIC_IPV6" | tr ',' '\n' >"$expected6_raw"
    normalize_ip_file "$expected6_raw" "$WORK_DIRECTORY/expected6" 6 ||
      fail 'EXPECTED_PUBLIC_IPV6 is invalid'
    [[ -s "$WORK_DIRECTORY/expected6" ]] || fail 'EXPECTED_PUBLIC_IPV6 is empty'
  fi

  local -a hosts=()
  local host
  IFS=',' read -r -a hosts <<<"$PUBLIC_HOSTS"
  ((${#hosts[@]} == 2)) || fail 'PUBLIC_HOSTS must contain both reviewed public hosts'
  local normalized_hosts
  normalized_hosts="$(printf '%s\n' "${hosts[@],,}" | LC_ALL=C sort -u | paste -sd, -)"
  [[ "$normalized_hosts" == 'www.yychainsaw.xyz,yychainsaw.xyz' ]] ||
    fail 'PUBLIC_HOSTS must contain yychainsaw.xyz and www.yychainsaw.xyz exactly'
  for host in "${hosts[@]}"; do
    [[ "$host" =~ ^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$ &&
       "$host" == *.* && "$host" != *..* ]] ||
      fail 'PUBLIC_HOSTS contains an invalid hostname'
    host="${host,,}"
    dig +short "$host" A >"$WORK_DIRECTORY/actual4.raw" ||
      fail 'public DNS A lookup failed'
    normalize_ip_file "$WORK_DIRECTORY/actual4.raw" "$WORK_DIRECTORY/actual4" 4 ||
      fail 'public DNS A lookup returned a non-address answer'
    cmp -s "$WORK_DIRECTORY/expected4" "$WORK_DIRECTORY/actual4" ||
      fail 'public DNS A answers do not exactly match the recorded expected set'

    dig +short "$host" AAAA >"$WORK_DIRECTORY/actual6.raw" ||
      fail 'public DNS AAAA lookup failed'
    normalize_ip_file "$WORK_DIRECTORY/actual6.raw" "$WORK_DIRECTORY/actual6" 6 ||
      fail 'public DNS AAAA lookup returned a non-address answer'
    cmp -s "$WORK_DIRECTORY/expected6" "$WORK_DIRECTORY/actual6" ||
      fail 'public DNS AAAA answers do not exactly match the recorded expected set'
  done
}

parse_arguments() {
  local argument
  for argument in "$@"; do
    case "$argument" in
      --public-cutover) PUBLIC_CUTOVER=true ;;
      --reload-nginx) RELOAD_NGINX=true ;;
      *) fail 'usage: preflight.sh [--public-cutover] [--reload-nginx]' ;;
    esac
  done
}

main() {
  parse_arguments "$@"
  check_jurisdiction_gate
  check_prerequisites
  WORK_DIRECTORY="$(mktemp -d)"
  check_protected_configuration
  check_baota_nginx
  check_release_and_host
  check_storage_providers
  check_tmpfs
  if [[ "$PUBLIC_CUTOVER" == 'true' ]]; then
    check_dns
  fi
  if [[ "$RELOAD_NGINX" == 'true' ]]; then
    nginx_reload || fail 'BaoTa Nginx reload failed'
  fi
  if [[ "$PUBLIC_CUTOVER" == 'true' ]]; then
    printf '%s\n' 'Portfolio preflight passed for public cutover'
  else
    printf '%s\n' 'Portfolio preflight passed for private deployment'
  fi
}

main "$@"
