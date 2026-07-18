#!/usr/bin/env bash
# shellcheck disable=SC2016
set -euo pipefail
set +x
umask 077

SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=deploy/restore/adapters/adapter-lib.sh
source "$SCRIPT_DIRECTORY/adapter-lib.sh"

emit_mapping_copy() {
  local mapping="$1"
  cat <<'SQL'
CREATE TEMP TABLE restore_media_mapping (
  asset_id UUID NOT NULL,
  object_kind TEXT NOT NULL CHECK (object_kind IN ('ORIGINAL', 'VARIANT')),
  variant_name TEXT NOT NULL,
  provider TEXT NOT NULL CHECK (provider IN ('LOCAL', 'TENCENT_COS')),
  bucket TEXT,
  region TEXT,
  object_key TEXT NOT NULL,
  PRIMARY KEY (asset_id, object_kind, variant_name)
) ON COMMIT DROP;
COPY restore_media_mapping
  (asset_id, object_kind, variant_name, provider, bucket, region, object_key)
  FROM STDIN WITH (FORMAT text, DELIMITER '|', NULL '');
SQL
  cat -- "$mapping"
  printf '\\.\n'
}

emit_common_validation() {
  cat <<'SQL'
SELECT 1 / CASE WHEN
  (SELECT count(*) FROM restore_media_mapping) = :'expected_count'::BIGINT
  AND NOT EXISTS (
    SELECT 1 FROM restore_media_mapping
    WHERE object_key !~ '^[A-Za-z0-9._/-]+$'
       OR object_key ~ '(^|/)\.\.?(/|$)'
       OR object_key LIKE '/%'
       OR object_key LIKE '%//%'
       OR (provider = 'LOCAL' AND (bucket IS NOT NULL OR region IS NOT NULL))
       OR (provider = 'TENCENT_COS' AND (
            bucket <> :'drill_bucket' OR region <> :'drill_region'
            OR length(object_key) <= length(:'drill_prefix')
            OR left(object_key, length(:'drill_prefix')) <> :'drill_prefix'))
  )
  AND NOT EXISTS (
    SELECT 1
    FROM restore_media_mapping
    GROUP BY asset_id
    HAVING count(DISTINCT provider) <> 1
       OR count(DISTINCT coalesce(bucket, '')) <> 1
       OR count(DISTINCT coalesce(region, '')) <> 1
  )
  AND NOT EXISTS (
    SELECT 1 FROM restore_media_mapping AS mapping
    LEFT JOIN portfolio.media_asset AS asset
      ON asset.id = mapping.asset_id
    LEFT JOIN portfolio.media_variant AS variant
      ON mapping.object_kind = 'VARIANT'
     AND variant.asset_id = mapping.asset_id
     AND variant.variant_name = mapping.variant_name
    WHERE asset.id IS NULL
       OR (mapping.object_kind = 'ORIGINAL' AND mapping.variant_name <> 'ORIGINAL')
       OR (mapping.object_kind = 'VARIANT' AND (variant.id IS NULL OR variant.status <> 'READY'))
  )
THEN 1 ELSE 0 END AS mapping_input_is_closed_and_isolated;
SQL
}

emit_database_verification() {
  cat <<'SQL'
SELECT 1 / CASE WHEN
  NOT EXISTS (
    SELECT 1
    FROM restore_media_mapping AS mapping
    JOIN portfolio.media_asset AS asset ON asset.id = mapping.asset_id
    LEFT JOIN portfolio.media_variant AS variant
      ON mapping.object_kind = 'VARIANT'
     AND variant.asset_id = mapping.asset_id
     AND variant.variant_name = mapping.variant_name
    WHERE asset.provider IS DISTINCT FROM mapping.provider
       OR asset.bucket IS DISTINCT FROM mapping.bucket
       OR asset.region IS DISTINCT FROM mapping.region
       OR (mapping.object_kind = 'ORIGINAL'
           AND asset.object_key IS DISTINCT FROM mapping.object_key)
       OR (mapping.object_kind = 'VARIANT'
           AND variant.object_key IS DISTINCT FROM mapping.object_key)
  )
  AND NOT EXISTS (
    SELECT 1
    FROM portfolio.media_asset AS asset
    WHERE asset.provider NOT IN ('LOCAL', 'TENCENT_COS')
       OR (asset.provider = 'LOCAL'
           AND (asset.bucket IS NOT NULL OR asset.region IS NOT NULL))
       OR (asset.provider = 'TENCENT_COS' AND (
            asset.bucket IS DISTINCT FROM :'drill_bucket'
            OR asset.region IS DISTINCT FROM :'drill_region'
            OR length(asset.object_key) <= length(:'drill_prefix')
            OR left(asset.object_key, length(:'drill_prefix')) <> :'drill_prefix'
            OR asset.object_key !~ '^[A-Za-z0-9._/-]+$'
            OR asset.object_key ~ '(^|/)\.\.?(/|$)'
            OR asset.object_key LIKE '/%'
            OR asset.object_key LIKE '%//%'
            OR (:'forbidden_checks_enabled'::BOOLEAN AND (
                 (asset.bucket = :'forbidden_production_bucket'
                  AND asset.region = :'forbidden_production_region')
                 OR (asset.bucket = :'forbidden_backup_bucket'
                     AND asset.region = :'forbidden_backup_region')
            ))
       ))
  )
  AND NOT EXISTS (
    SELECT 1
    FROM portfolio.media_variant AS variant
    JOIN portfolio.media_asset AS asset ON asset.id = variant.asset_id
    WHERE asset.provider = 'TENCENT_COS'
      AND (
        length(variant.object_key) <= length(:'drill_prefix')
        OR left(variant.object_key, length(:'drill_prefix')) <> :'drill_prefix'
        OR variant.object_key !~ '^[A-Za-z0-9._/-]+$'
        OR variant.object_key ~ '(^|/)\.\.?(/|$)'
        OR variant.object_key LIKE '/%'
        OR variant.object_key LIKE '%//%'
      )
  )
THEN 1 ELSE 0 END AS restored_mapping_matches_database;
SQL
}

parse_forbidden_location() {
  local location="$1" label="$2"
  [[ "$location" =~ ^(.+)@([a-z0-9][a-z0-9.-]{1,126}[a-z0-9])@([a-z0-9][a-z0-9-]{1,62}[a-z0-9])$ ]] ||
    adapter_fail "$label COS location is malformed"
  local account="${BASH_REMATCH[1]}"
  local bucket="${BASH_REMATCH[2]}"
  local region="${BASH_REMATCH[3]}"
  [[ "$account" =~ ^[A-Za-z0-9][A-Za-z0-9_.:@-]{1,127}$ ]] ||
    adapter_fail "$label COS account is malformed"
  printf '%s|%s\n' "$bucket" "$region"
}

run_sql() {
  local operation="$1"
  local mapping="$2"
  local expected_count="$3"
  local forbidden_production_bucket="$4"
  local forbidden_production_region="$5"
  local forbidden_backup_bucket="$6"
  local forbidden_backup_region="$7"
  local forbidden_checks_enabled=false
  [[ "$operation" != verify ]] || forbidden_checks_enabled=true
  {
    printf '%s\n' 'BEGIN TRANSACTION ISOLATION LEVEL SERIALIZABLE;'
    emit_mapping_copy "$mapping"
    emit_common_validation
    if [[ "$operation" == apply ]]; then
      cat <<'SQL'
UPDATE portfolio.media_asset AS asset
SET bucket = :'drill_bucket',
    region = :'drill_region',
    object_key = :'drill_prefix' || 'quarantine/assets/' || asset.id::TEXT || '/original',
    version = asset.version + 1,
    updated_at = clock_timestamp()
WHERE asset.provider = 'TENCENT_COS';

UPDATE portfolio.media_asset AS asset
SET provider = mapping.provider,
    bucket = mapping.bucket,
    region = mapping.region,
    object_key = mapping.object_key,
    version = asset.version + 1,
    updated_at = clock_timestamp()
FROM restore_media_mapping AS mapping
WHERE mapping.object_kind = 'ORIGINAL'
  AND mapping.asset_id = asset.id;

UPDATE portfolio.media_variant AS variant
SET object_key = :'drill_prefix' || 'quarantine/assets/' || variant.asset_id::TEXT
                 || '/variants/' || variant.id::TEXT
FROM portfolio.media_asset AS asset
WHERE asset.id = variant.asset_id
  AND asset.provider = 'TENCENT_COS';

UPDATE portfolio.media_variant AS variant
SET object_key = mapping.object_key
FROM restore_media_mapping AS mapping
WHERE mapping.object_kind = 'VARIANT'
  AND mapping.asset_id = variant.asset_id
  AND mapping.variant_name = variant.variant_name;
SQL
    fi
    emit_database_verification
    printf '%s\n' 'COMMIT;'
  } | PORTFOLIO_RESTORE_PHASE="mapping-$operation" adapter_compose exec -T restore-postgres \
      sh -eu -c 'exec psql -X --no-psqlrc -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" "$@"' \
      restore-media-mapping \
      --set="expected_count=$expected_count" \
      --set="drill_bucket=$RESTORE_DRILL_COS_BUCKET" \
      --set="drill_region=$RESTORE_DRILL_COS_REGION" \
      --set="drill_prefix=drills/$RESTORE_DRILL_ID/" \
      --set="forbidden_checks_enabled=$forbidden_checks_enabled" \
      --set="forbidden_production_bucket=$forbidden_production_bucket" \
      --set="forbidden_production_region=$forbidden_production_region" \
      --set="forbidden_backup_bucket=$forbidden_backup_bucket" \
      --set="forbidden_backup_region=$forbidden_backup_region" \
      -f -
}

main() {
  (($# >= 1)) || adapter_fail 'media mapping operation is required'
  local operation="$1"; shift
  local mapping='' expected_count='' production_locations='' backup_location=''
  local local_root='' drill_prefix=''
  while (($#)); do
    case "$1" in
      --mapping) mapping="$2"; shift 2 ;;
      --expected-count) expected_count="$2"; shift 2 ;;
      --forbid-production-locations) production_locations="$2"; shift 2 ;;
      --forbid-backup-location) backup_location="$2"; shift 2 ;;
      --local-root) local_root="$2"; shift 2 ;;
      --drill-prefix) drill_prefix="$2"; shift 2 ;;
      *) adapter_fail 'media mapping received an unsupported argument' ;;
    esac
  done
  [[ "$operation" == apply || "$operation" == verify ]] ||
    adapter_fail 'media mapping operation must be apply or verify'
  adapter_require_drill_context
  adapter_require_disposed_identity
  adapter_resolve_command RESTORE_DOCKER_COMMAND docker
  adapter_require_value RESTORE_COMPOSE_FILE
  [[ "$RESTORE_COMPOSE_FILE" == /* && -f "$RESTORE_COMPOSE_FILE" && ! -L "$RESTORE_COMPOSE_FILE" ]] ||
    adapter_fail 'tracked restore Compose file is unavailable'
  [[ "$mapping" == "$RESTORE_ROOT/work/"* && -f "$mapping" && ! -L "$mapping" ]] ||
    adapter_fail 'media mapping file escaped the drill work directory'
  [[ "$expected_count" =~ ^[1-9][0-9]*$ && "$(wc -l <"$mapping")" == "$expected_count" ]] ||
    adapter_fail 'media mapping count is invalid'
  awk -F '|' '
    NF != 7 {exit 1}
    $1 !~ /^[0-9a-f-]{36}$/ {exit 1}
    $2 != "ORIGINAL" && $2 != "VARIANT" {exit 1}
    $3 !~ /^[A-Za-z0-9_-]{1,32}$/ {exit 1}
    $4 != "LOCAL" && $4 != "TENCENT_COS" {exit 1}
    $7 !~ /^[A-Za-z0-9._\/-]+$/ {exit 1}
    $7 ~ /(^|\/)\.\.?($|\/)/ || $7 ~ /^\// || $7 ~ /\/\// {exit 1}
  ' "$mapping" || adapter_fail 'media mapping contains an unsafe row'
  [[ "$(cut -d '|' -f 1-3 "$mapping" | sort -u | wc -l)" == "$expected_count" ]] ||
    adapter_fail 'media mapping contains duplicate object identities'
  adapter_require_value RESTORE_DRILL_COS_BUCKET
  adapter_require_value RESTORE_DRILL_COS_REGION
  local forbidden_production_bucket='' forbidden_production_region=''
  local forbidden_backup_bucket='' forbidden_backup_region=''
  if [[ "$operation" == verify ]]; then
    [[ "$production_locations" == "${PRODUCTION_COS_LOCATIONS:-}" &&
       "$backup_location" == "${BACKUP_DESTINATION_LOCATION:-}" ]] ||
      adapter_fail 'forbidden COS topology differs from the reviewed restore context'
    [[ "$local_root" == /var/lib/portfolio/media &&
       "$drill_prefix" == "drills/$RESTORE_DRILL_ID/" ]] ||
      adapter_fail 'mapping verification targets a non-isolated runtime location'
    local parsed_production parsed_backup extra
    parsed_production="$(parse_forbidden_location "$production_locations" production)"
    parsed_backup="$(parse_forbidden_location "$backup_location" backup)"
    IFS='|' read -r forbidden_production_bucket forbidden_production_region extra <<<"$parsed_production"
    [[ -z "${extra:-}" ]] || adapter_fail 'production COS location is malformed'
    IFS='|' read -r forbidden_backup_bucket forbidden_backup_region extra <<<"$parsed_backup"
    [[ -z "${extra:-}" ]] || adapter_fail 'backup COS location is malformed'
    local drill_location="$RESTORE_DRILL_COS_BUCKET@$RESTORE_DRILL_COS_REGION"
    [[ "$forbidden_production_bucket@$forbidden_production_region" != "$drill_location" &&
       "$forbidden_backup_bucket@$forbidden_backup_region" != "$drill_location" ]] ||
      adapter_fail 'forbidden COS topology overlaps the drill location'
  elif [[ -n "$production_locations$backup_location$local_root$drill_prefix" ]]; then
    adapter_fail 'mapping apply does not accept verification-only topology arguments'
  fi
  run_sql "$operation" "$mapping" "$expected_count" \
    "$forbidden_production_bucket" "$forbidden_production_region" \
    "$forbidden_backup_bucket" "$forbidden_backup_region" >/dev/null ||
    adapter_fail "drill media mapping $operation transaction failed"
}

main "$@"
