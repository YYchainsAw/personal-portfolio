#!/usr/bin/env bash
set -euo pipefail

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
readonly REPOSITORY_ROOT
readonly COMPOSE_SOURCE="$REPOSITORY_ROOT/deploy/docker-compose.prod.yml"
readonly ENV_EXAMPLE="$REPOSITORY_ROOT/deploy/.env.example"
readonly APPLICATION_PROD="$REPOSITORY_ROOT/backend-parent/portfolio-server/src/main/resources/application-prod.yml"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

run_compose() {
  env \
    -u COMPOSE_DISABLE_ENV_FILE \
    -u COMPOSE_ENV_FILES \
    -u COMPOSE_FILE \
    -u COMPOSE_PROFILES \
    -u COMPOSE_PROJECT_NAME \
    -u PORTFOLIO_IMAGE \
    -u PORTFOLIO_RELEASE_ID \
    -u POSTGRES_IMAGE \
    docker compose "$@"
}

for command_name in docker jq sed; do
  command -v "$command_name" >/dev/null 2>&1 ||
    fail "$command_name is required"
done
docker compose version >/dev/null 2>&1 || fail 'Docker Compose v2 is required'
[[ -f "$COMPOSE_SOURCE" ]] || fail 'production Compose file is missing'
[[ -f "$ENV_EXAMPLE" ]] || fail 'deployment environment example is missing'
[[ -f "$APPLICATION_PROD" ]] || fail 'Spring production profile is missing'

WORK_DIRECTORY="$(mktemp -d)"
readonly WORK_DIRECTORY
trap 'rm -rf -- "$WORK_DIRECTORY"' EXIT
readonly COMPOSE_FILE="$WORK_DIRECTORY/docker-compose.prod.yml"
readonly INTERPOLATION_ENV="$WORK_DIRECTORY/release.env"
readonly NO_POSTGRES_ENV="$WORK_DIRECTORY/no-postgres-release.env"
readonly NO_API_ENV="$WORK_DIRECTORY/no-api-release.env"
readonly NO_RELEASE_ID_ENV="$WORK_DIRECTORY/no-release-id.env"
readonly POSTGRES_ENV="$WORK_DIRECTORY/postgres.env"
readonly PORTFOLIO_ENV="$WORK_DIRECTORY/portfolio.env"

[[ "$(grep -Fxc '    env_file: /etc/portfolio/postgres.env' "$COMPOSE_SOURCE")" == '1' ]] ||
  fail 'PostgreSQL must use exactly /etc/portfolio/postgres.env'
[[ "$(grep -Fxc '    env_file: /etc/portfolio/portfolio.env' "$COMPOSE_SOURCE")" == '1' ]] ||
  fail 'the API must use exactly /etc/portfolio/portfolio.env'

sed \
  -e "s|/etc/portfolio/postgres.env|$POSTGRES_ENV|" \
  -e "s|/etc/portfolio/portfolio.env|$PORTFOLIO_ENV|" \
  "$COMPOSE_SOURCE" >"$COMPOSE_FILE"

printf '%s\n' \
  'POSTGRES_IMAGE=postgres:17-bookworm@sha256:1111111111111111111111111111111111111111111111111111111111111111' \
  'PORTFOLIO_IMAGE=portfolio-api:contract-release' \
  'PORTFOLIO_RELEASE_ID=contract-release' >"$INTERPOLATION_ENV"

printf '%s\n' \
  'PORTFOLIO_IMAGE=portfolio-api:contract-release' \
  'PORTFOLIO_RELEASE_ID=contract-release' >"$NO_POSTGRES_ENV"

printf '%s\n' \
  'POSTGRES_IMAGE=postgres:17-bookworm@sha256:1111111111111111111111111111111111111111111111111111111111111111' \
  'PORTFOLIO_RELEASE_ID=contract-release' >"$NO_API_ENV"

printf '%s\n' \
  'POSTGRES_IMAGE=postgres:17-bookworm@sha256:1111111111111111111111111111111111111111111111111111111111111111' \
  'PORTFOLIO_IMAGE=portfolio-api:contract-release' >"$NO_RELEASE_ID_ENV"

printf '%s\n' \
  'POSTGRES_DB=portfolio' \
  'POSTGRES_USER=portfolio_owner' >"$POSTGRES_ENV"

printf '%s\n' \
  'PORTFOLIO_LOCAL_VOLUME_ID=contract-volume-id-not-secret' \
  'PORTFOLIO_STORAGE_DEFAULT_PROVIDER=TENCENT_COS' \
  'PORTFOLIO_COS_ENABLED=false' >"$PORTFOLIO_ENV"

config="$(run_compose \
  --project-directory "$REPOSITORY_ROOT" \
  --env-file "$INTERPOLATION_ENV" \
  -f "$COMPOSE_FILE" \
  config --format json)"

[[ "$(jq -c '.services | keys | sort' <<<"$config")" == \
  '["portfolio-api","postgres"]' ]] ||
  fail 'production Compose must contain exactly portfolio-api and postgres'

jq -e '
  .name == "portfolio" and
  (.services.postgres.image | test("^postgres:17(?:[.-][A-Za-z0-9_.-]+)?@sha256:[0-9a-f]{64}$")) and
  (.services.postgres.ports == null) and
  (.services.postgres.restart == "unless-stopped") and
  (.services.postgres.volumes == [{
    type: "volume",
    source: "postgres-data",
    target: "/var/lib/postgresql/data",
    volume: {}
  }]) and
  (.services.postgres.healthcheck.test == [
    "CMD-SHELL",
    "pg_isready -U $$POSTGRES_USER -d $$POSTGRES_DB"
  ]) and
  (.services["portfolio-api"].image == "portfolio-api:contract-release") and
  (.services["portfolio-api"].restart == "unless-stopped") and
  (.services["portfolio-api"].user == "10001:10001") and
  (.services["portfolio-api"].privileged != true) and
  (.services["portfolio-api"].depends_on.postgres.condition == "service_healthy") and
  (.services["portfolio-api"].ports | length == 1) and
  (.services["portfolio-api"].ports[0] |
    .host_ip == "127.0.0.1" and
    .published == "18080" and
    .target == 8080 and
    .protocol == "tcp") and
  (.services["portfolio-api"].read_only == true) and
  (.services["portfolio-api"].cap_drop == ["ALL"]) and
  (.services["portfolio-api"].security_opt == ["no-new-privileges:true"]) and
  (.services["portfolio-api"].tmpfs == ["/tmp:size=128m,mode=1777,noexec,nosuid,nodev"]) and
  (.services["portfolio-api"].volumes == [{
    type: "volume",
    source: "local-media",
    target: "/var/lib/portfolio/media",
    volume: {}
  }]) and
  (.services["portfolio-api"].environment.SPRING_PROFILES_ACTIVE == "prod") and
  (.services["portfolio-api"].environment.PORTFOLIO_RELEASE_ID == "contract-release") and
  (.services["portfolio-api"].environment.PORTFOLIO_BIND_ADDRESS == "0.0.0.0") and
  (.services["portfolio-api"].environment.PORTFOLIO_PORT == "8080") and
  (.services["portfolio-api"].environment.PORTFOLIO_LOCAL_STORAGE == "/var/lib/portfolio/media") and
  (.services["portfolio-api"].environment.PORTFOLIO_COS_STAGING_ROOT == "/tmp/portfolio-cos-staging") and
  (.services["portfolio-api"].environment.JAVA_TOOL_OPTIONS |
    contains("-Djava.io.tmpdir=/tmp")) and
  (.services["portfolio-api"].environment.PORTFOLIO_LOCAL_VOLUME_ID ==
    "contract-volume-id-not-secret") and
  (.services["portfolio-api"].environment.PORTFOLIO_STORAGE_DEFAULT_PROVIDER ==
    "TENCENT_COS") and
  (.services["portfolio-api"].environment.PORTFOLIO_COS_ENABLED == "false") and
  (.services["portfolio-api"].healthcheck.test == [
    "CMD",
    "curl",
    "--fail",
    "--silent",
    "--show-error",
    "http://127.0.0.1:8080/actuator/health/readiness"
  ]) and
  (.volumes | keys | sort == ["local-media", "postgres-data"]) and
  (.volumes["local-media"].external == true) and
  (.volumes["local-media"].name == "portfolio-local-media") and
  ([.services[] | .volumes[]? | select(.type == "bind")] | length == 0) and
  ([.services["portfolio-api"].volumes[].target |
    select(startswith("/tmp") or . == "/")] | length == 0)
' <<<"$config" >/dev/null || fail 'rendered production Compose violates its security contract'

if run_compose \
  --project-directory "$REPOSITORY_ROOT" \
  --env-file "$NO_POSTGRES_ENV" \
  -f "$COMPOSE_FILE" \
  config --quiet >/dev/null 2>&1; then
  fail 'Compose accepted a missing pinned PostgreSQL image'
fi

if run_compose \
  --project-directory "$REPOSITORY_ROOT" \
  --env-file "$NO_API_ENV" \
  -f "$COMPOSE_FILE" \
  config --quiet >/dev/null 2>&1; then
  fail 'Compose accepted a missing API image'
fi

if run_compose \
  --project-directory "$REPOSITORY_ROOT" \
  --env-file "$NO_RELEASE_ID_ENV" \
  -f "$COMPOSE_FILE" \
  config --quiet >/dev/null 2>&1; then
  fail 'Compose accepted a missing release ID'
fi

required_environment_names=(
  POSTGRES_IMAGE
  PORTFOLIO_IMAGE
  PORTFOLIO_RELEASE_ID
  POSTGRES_DB
  POSTGRES_DEV_PORT
  POSTGRES_OWNER
  POSTGRES_OWNER_PASSWORD
  POSTGRES_USER
  POSTGRES_PASSWORD
  PORTFOLIO_DB_MIGRATOR_USER
  PORTFOLIO_DB_MIGRATOR_PASSWORD
  PORTFOLIO_DB_RUNTIME_USER
  PORTFOLIO_DB_RUNTIME_PASSWORD
  PORTFOLIO_DB_URL
  PORTFOLIO_DB_MIGRATOR_URL
  PORTFOLIO_BIND_ADDRESS
  PORTFOLIO_PORT
  PORTFOLIO_SESSION_COOKIE_SECURE
  PORTFOLIO_ALLOW_DEVELOPMENT_CORS
  PORTFOLIO_ADMIN_DEV_ORIGIN
  PORTFOLIO_TOTP_ACTIVE_KEY_VERSION
  PORTFOLIO_TOTP_KEY_RING
  PORTFOLIO_PREVIEW_HMAC_KEY
  PORTFOLIO_PUBLIC_BASE_URL
  PORTFOLIO_VITE_MANIFEST
  COS_REGION
  COS_BUCKET
  COS_SECRET_ID
  COS_SECRET_KEY
  COS_SESSION_TOKEN
  PORTFOLIO_STORAGE_DEFAULT_PROVIDER
  PORTFOLIO_COS_ENABLED
  PORTFOLIO_LOCAL_STORAGE
  PORTFOLIO_COS_STAGING_ROOT
  PORTFOLIO_LOCAL_VOLUME_ID
  PORTFOLIO_JOBS_WORKER_ENABLED
  PORTFOLIO_STAGING_CLEANUP_ENABLED
  PORTFOLIO_MEDIA_CLEANUP_ENABLED
  PORTFOLIO_JOBS_RETENTION_ENABLED
  PORTFOLIO_JOBS_RETENTION_BATCH_SIZE
  PORTFOLIO_JOBS_RETENTION_MAX_BATCHES
  PORTFOLIO_MEDIA_INGEST_TRANSACTION_TIMEOUT
  PORTFOLIO_LOCAL_PUBLICATION_MAX_CONCURRENCY
  PORTFOLIO_LOCAL_PUBLICATION_CONNECTION_HEADROOM
  PORTFOLIO_LOCAL_PUBLICATION_ACQUIRE_TIMEOUT
  PORTFOLIO_LOCAL_PUBLICATION_OPERATION_TIMEOUT
  PORTFOLIO_EMAIL_ENABLED
  PORTFOLIO_EMAIL_FROM
  PORTFOLIO_OWNER_EMAIL
  PORTFOLIO_MAIL_ID_DOMAIN
  SMTP_HOST
  SMTP_PORT
  SMTP_USERNAME
  SMTP_PASSWORD
  PORTFOLIO_CONTACT_DEDUPE_SECRET
  PORTFOLIO_ANALYTICS_HMAC_SECRET
  PORTFOLIO_ANALYTICS_MAINTENANCE_SCHEDULING_ENABLED
  PORTFOLIO_RECOVERY_DIRECTORY
  PORTFOLIO_PG_DUMP_BIN
  PORTFOLIO_PG_DUMP_TIMEOUT
  PORTFOLIO_RECOVERY_DB_HOST
  PORTFOLIO_RECOVERY_DB_PORT
)

duplicate_names="$(sed -n 's/^\([A-Z][A-Z0-9_]*\)=.*/\1/p' "$ENV_EXAMPLE" |
  LC_ALL=C sort | uniq -d)"
[[ -z "$duplicate_names" ]] || fail 'deployment environment example has duplicate names'

for variable_name in "${required_environment_names[@]}"; do
  grep -Eq "^${variable_name}=" "$ENV_EXAMPLE" ||
    fail "deployment environment example is missing $variable_name"
done

blank_protected_names=(
  POSTGRES_IMAGE
  PORTFOLIO_IMAGE
  PORTFOLIO_RELEASE_ID
  POSTGRES_OWNER_PASSWORD
  POSTGRES_PASSWORD
  PORTFOLIO_DB_MIGRATOR_PASSWORD
  PORTFOLIO_DB_RUNTIME_PASSWORD
  PORTFOLIO_TOTP_KEY_RING
  PORTFOLIO_PREVIEW_HMAC_KEY
  COS_REGION
  COS_BUCKET
  COS_SECRET_ID
  COS_SECRET_KEY
  COS_SESSION_TOKEN
  PORTFOLIO_LOCAL_VOLUME_ID
  PORTFOLIO_EMAIL_FROM
  PORTFOLIO_OWNER_EMAIL
  SMTP_HOST
  SMTP_USERNAME
  SMTP_PASSWORD
  PORTFOLIO_CONTACT_DEDUPE_SECRET
  PORTFOLIO_ANALYTICS_HMAC_SECRET
)

for variable_name in "${blank_protected_names[@]}"; do
  grep -Fqx "${variable_name}=" "$ENV_EXAMPLE" ||
    fail "$variable_name must stay blank in the deployment example"
done

required_spring_environment_names=(
  PORTFOLIO_STORAGE_DEFAULT_PROVIDER
  PORTFOLIO_TOTP_ACTIVE_KEY_VERSION
  PORTFOLIO_TOTP_KEY_RING
  PORTFOLIO_PREVIEW_HMAC_KEY
  PORTFOLIO_CONTACT_DEDUPE_SECRET
  PORTFOLIO_ANALYTICS_HMAC_SECRET
  COS_REGION
  COS_BUCKET
  COS_SECRET_ID
  COS_SECRET_KEY
)

for variable_name in "${required_spring_environment_names[@]}"; do
  required_placeholder="\${${variable_name}}"
  fallback_prefix="\${${variable_name}:"
  grep -Fq "$required_placeholder" "$APPLICATION_PROD" ||
    fail "Spring production profile does not require $variable_name"
  if grep -Fq "$fallback_prefix" "$APPLICATION_PROD"; then
    fail "Spring production profile gives $variable_name a fallback"
  fi
done

grep -Fq "\${PORTFOLIO_COS_ENABLED:false}" "$APPLICATION_PROD" ||
  fail 'Spring production profile does not map the optional COS adapter gate'

printf '%s\n' 'PASS: private two-service production Compose contract'
