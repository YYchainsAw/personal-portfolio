#!/usr/bin/env bash
set -euo pipefail
set +x
umask 077

WORK_DIRECTORY=''

fail() {
  printf 'Restored media verification failed: %s\n' "$1" >&2
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

require_command_path() {
  local value="$1"
  local label="$2"
  [[ "$value" == /* && -x "$value" ]] || fail "$label must be an executable absolute path"
}

is_beneath() {
  local child="$1"
  local parent="${2%/}"
  [[ "$child" == "$parent" || "$child" == "$parent/"* ]]
}

safe_relative_key() {
  local key="$1"
  [[ -n "$key" && "$key" =~ ^[A-Za-z0-9._/-]+$ &&
     "$key" != /* && "$key" != */ && "$key" != *//* && "$key" != *\\* && "$key" != *'|'* &&
     ! "$key" =~ (^|/)\.\.?(/|$) && "$key" != *$'\n'* && "$key" != *$'\r'* ]]
}

normalize_mime() {
  local value="$1"
  local mime_pattern='^[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+$'
  value="${value%%;*}"
  value="$(printf '%s' "$value" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' | tr '[:upper:]' '[:lower:]')"
  [[ "$value" =~ $mime_pattern ]] || return 1
  printf '%s\n' "$value"
}

verify_file() {
  local path="$1"
  local expected_size="$2"
  local expected_sha="$3"
  [[ ! -L "$path" && -f "$path" ]] || return 1
  [[ "$(stat -Lc '%s' -- "$path")" == "$expected_size" ]] || return 1
  [[ "$(sha256sum "$path" | awk '{print $1}')" == "$expected_sha" ]]
}

verify_route() {
  local url="$1"
  local expected_size="$2"
  local expected_sha="$3"
  local expected_mime="$4"
  local label="$5"
  local sequence="$6"
  local expected_redirect="$7"
  local body="$WORK_DIRECTORY/route-$sequence.body"
  local headers="$WORK_DIRECTORY/route-$sequence.headers"

  if ! "$RESTORE_HTTP_FETCH_COMMAND" \
      --url "$url" \
      --body "$body" \
      --headers "$headers" \
      --allowed-redirect-origin "$RESTORE_DRILL_COS_ORIGIN" \
      --expected-redirect-url "$expected_redirect"; then
    fail "$label route fetch failed"
  fi
  verify_file "$body" "$expected_size" "$expected_sha" ||
    fail "$label route response bytes do not match restored media"
  [[ -f "$headers" ]] || fail "$label route response headers are missing"
  local content_type
  content_type="$(awk '
    BEGIN {count = 0}
    {
      line = $0
      sub(/\r$/, "", line)
      if (tolower(line) ~ /^content-type:[[:space:]]*/) {
        sub(/^[^:]*:[[:space:]]*/, "", line)
        value = line
        count += 1
      }
    }
    END {if (count != 1) exit 1; print value}
  ' "$headers")" || fail "$label route returned an ambiguous Content-Type"
  content_type="$(normalize_mime "$content_type")" ||
    fail "$label route returned an invalid Content-Type"
  [[ "$content_type" == "$expected_mime" ]] ||
    fail "$label route Content-Type does not match restored media"
}

main() {
  local closure='' manifest='' local_root='' drill_id='' summary='' mapping=''
  while (($#)); do
    case "$1" in
      --closure) closure="$2"; shift 2 ;;
      --manifest) manifest="$2"; shift 2 ;;
      --local-root) local_root="$2"; shift 2 ;;
      --drill-id) drill_id="$2"; shift 2 ;;
      --summary) summary="$2"; shift 2 ;;
      --mapping) mapping="$2"; shift 2 ;;
      *) fail 'usage: verify-restored-media.sh --closure FILE --manifest FILE --local-root DIR --drill-id UUID --summary FILE --mapping FILE' ;;
    esac
  done

  [[ "$drill_id" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$ ]] ||
    fail 'drill ID is invalid'
  [[ "${RESTORE_ROOT:-}" == /srv/portfolio-restore/* && -d "$RESTORE_ROOT" && ! -L "$RESTORE_ROOT" &&
     "$(realpath -e -- "$RESTORE_ROOT")" == "$RESTORE_ROOT" &&
     -f "$RESTORE_ROOT/.portfolio-restore-drill" &&
     "$(<"$RESTORE_ROOT/.portfolio-restore-drill")" == "$drill_id" ]] ||
    fail 'restore root is not exactly bound to the drill ID'
  [[ "$closure" == /* && ! -L "$closure" && -f "$closure" ]] || fail 'closure file is invalid'
  [[ "$manifest" == /* && ! -L "$manifest" && -f "$manifest" ]] || fail 'media manifest is invalid'
  [[ "$local_root" == /* && ! -L "$local_root" && -d "$local_root" ]] || fail 'isolated Local root is invalid'
  [[ "$summary" == /* && "$mapping" == /* ]] || fail 'output paths must be absolute'
  [[ "$closure" == "$RESTORE_ROOT/work/media-closure.psv" &&
     "$manifest" == "$RESTORE_ROOT/work/media-manifest.json" &&
     "$local_root" == "$RESTORE_ROOT/local-media" &&
     "$summary" == "$RESTORE_ROOT/work/media-summary.json" &&
     "$mapping" == "$RESTORE_ROOT/work/media-mapping.psv" ]] ||
    fail 'media verification inputs or outputs escaped the isolated restore root'
  local_root="$(realpath -e -- "$local_root")"

  require_command_path "${RESTORE_COS_TRANSFER_COMMAND:-}" RESTORE_COS_TRANSFER_COMMAND
  require_command_path "${RESTORE_APPLY_MEDIA_MAPPING_COMMAND:-}" RESTORE_APPLY_MEDIA_MAPPING_COMMAND
  require_command_path "${RESTORE_ROUTE_START_COMMAND:-}" RESTORE_ROUTE_START_COMMAND
  require_command_path "${RESTORE_HTTP_FETCH_COMMAND:-}" RESTORE_HTTP_FETCH_COMMAND
  [[ "${RESTORE_IDENTITY_DISPOSED:-false}" == true ]] || fail 'offline age identity reached media verification'
  [[ "${RESTORE_TLS_SERVER_NAME:-}" =~ ^[a-z0-9]([a-z0-9.-]{0,61}[a-z0-9])?$ &&
     "${RESTORE_NGINX_BASE_URL:-}" == "https://$RESTORE_TLS_SERVER_NAME:${RESTORE_NGINX_PORT:-}" ]] ||
    fail 'RESTORE_NGINX_BASE_URL must resolve only to the local drill Nginx'
  [[ "${RESTORE_DRILL_COS_ACCOUNT_ID:-}" =~ ^[A-Za-z0-9][A-Za-z0-9_.:@-]{1,127}$ ]] ||
    fail 'drill COS account is invalid'
  [[ "${RESTORE_DRILL_COS_BUCKET:-}" =~ ^[a-z0-9][a-z0-9-]{2,62}$ ]] ||
    fail 'drill COS bucket is invalid'
  [[ "${RESTORE_DRILL_COS_REGION:-}" =~ ^[a-z0-9][a-z0-9-]{2,31}$ ]] ||
    fail 'drill COS region is invalid'
  [[ -n "${PRODUCTION_COS_LOCATIONS:-}" && -n "${BACKUP_DESTINATION_LOCATION:-}" ]] ||
    fail 'production and backup COS locations are required'
  local drill_location="$RESTORE_DRILL_COS_ACCOUNT_ID@$RESTORE_DRILL_COS_BUCKET@$RESTORE_DRILL_COS_REGION"
  local production_location
  IFS=',' read -r -a production_locations <<<"${PRODUCTION_COS_LOCATIONS:-}"
  for production_location in "${production_locations[@]}"; do
    [[ -z "$production_location" || "$drill_location" != "$production_location" ]] ||
      fail 'drill COS destination equals a production media location'
  done
  [[ "$drill_location" != "${BACKUP_DESTINATION_LOCATION:-}" ]] ||
    fail 'drill COS destination equals the backup destination'
  RESTORE_DRILL_COS_ORIGIN="https://$RESTORE_DRILL_COS_BUCKET.cos.$RESTORE_DRILL_COS_REGION.myqcloud.com"
  export RESTORE_DRILL_COS_ORIGIN

  WORK_DIRECTORY="$(mktemp -d -- "$RESTORE_ROOT/work/media-verify.XXXXXX")"
  local manifest_rows="$WORK_DIRECTORY/manifest.rows"
  if ! jq -e '
      .schemaVersion == 1 and (.rows | type == "array") and
      all(.rows[];
        (.assetId | type == "string") and
        (.objectKind == "ORIGINAL" or .objectKind == "VARIANT") and
        (.variantName | type == "string") and
        (.variantName | test("^[A-Za-z0-9_-]{1,32}$")) and
        (if .objectKind == "ORIGINAL" then .variantName == "ORIGINAL" else true end) and
        (.provider == "LOCAL" or .provider == "TENCENT_COS") and
        (.objectKey | type == "string") and
        (.objectKey | contains("|") | not) and
        (.objectKey | explode | all(. >= 32 and . != 127)) and
        (.mimeType | type == "string") and
        (.mimeType | contains("|") | not) and
        (.byteSize | type == "number") and
        ((.byteSize | floor) == .byteSize) and (.byteSize > 0) and
        (.sha256 | type == "string") and
        (.sha256 | test("^[0-9a-f]{64}$")) and
        (if .provider == "LOCAL" then .bucket == null and .region == null
         else (.bucket | type == "string") and (.region | type == "string") and
               (.bucket | test("^[a-z0-9][a-z0-9.-]{1,126}[a-z0-9]$")) and
               (.region | test("^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$")) end)
      )
    ' "$manifest" >/dev/null; then
    fail 'authoritative media manifest has an invalid shape'
  fi
  jq -r '.rows[] | [
      .assetId,.objectKind,.variantName,.provider,(.bucket // ""),(.region // ""),
      .objectKey,.mimeType,(.byteSize|tostring),.sha256
    ] | join("|")' "$manifest" | LC_ALL=C sort >"$manifest_rows"
  [[ "$(uniq -d "$manifest_rows" | wc -l)" -eq 0 ]] ||
    fail 'authoritative media manifest contains duplicate rows'
  [[ "$(cut -d '|' -f 1-3 "$manifest_rows" | uniq -d | wc -l)" -eq 0 ]] ||
    fail 'authoritative media manifest contains duplicate object identities'

  : >"$mapping"
  local routes="$WORK_DIRECTORY/routes"
  : >"$routes"
  local total=0 local_count=0 cos_count=0 current_routes=0 historical_routes=0
  local asset kind variant current historical provider bucket region object_key mime size sha extra
  declare -A closure_keys=()
  while IFS='|' read -r \
      asset kind variant current historical provider bucket region object_key mime size sha extra; do
    [[ -n "$asset" && -z "${extra:-}" ]] || fail 'closure row is malformed'
    [[ "$asset" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$ ]] ||
      fail 'closure asset ID is invalid'
    [[ "$kind" == ORIGINAL || "$kind" == VARIANT ]] || fail 'closure object kind is invalid'
    [[ "$variant" =~ ^[A-Za-z0-9_-]{1,32}$ ]] || fail 'closure variant is invalid'
    [[ "$current" == true || "$current" == false ]] || fail 'closure current flag is invalid'
    [[ "$historical" == true || "$historical" == false ]] || fail 'closure historical flag is invalid'
    [[ "$current" == true || "$historical" == true ]] || fail 'closure row has no required revision'
    [[ "$size" =~ ^[1-9][0-9]*$ && "$sha" =~ ^[0-9a-f]{64}$ ]] ||
      fail 'closure size or checksum is invalid'
    safe_relative_key "$object_key" || fail 'closure object key is unsafe'
    mime="$(normalize_mime "$mime")" || fail 'closure MIME type is invalid'
    local closure_key="$asset|$kind|$variant"
    [[ -z "${closure_keys[$closure_key]:-}" ]] || fail 'closure row is duplicated'
    closure_keys["$closure_key"]=1
    grep -Fx -- "$asset|$kind|$variant|$provider|$bucket|$region|$object_key|$mime|$size|$sha" \
      "$manifest_rows" >/dev/null || fail 'closure row does not exactly match the authoritative manifest'

    local mapped_key="$object_key"
    case "$provider" in
      LOCAL)
        [[ -z "$bucket" && -z "$region" ]] || fail 'Local closure row contains remote location metadata'
        local resolved="$local_root/$object_key"
        resolved="$(realpath -e -- "$resolved" 2>/dev/null)" || fail 'required Local object is missing'
        is_beneath "$resolved" "$local_root" || fail 'required Local object escaped the isolated volume'
        verify_file "$resolved" "$size" "$sha" || fail 'required Local object bytes do not match'
        ((local_count += 1))
        ;;
      TENCENT_COS)
        [[ -n "$bucket" && -n "$region" ]] || fail 'COS closure row lacks its source location'
        local backup_copy="$WORK_DIRECTORY/backup-$sha"
        local drill_copy="$WORK_DIRECTORY/drill-$sha"
        "$RESTORE_COS_TRANSFER_COMMAND" fetch-backup \
          --blob "blobs/$sha" --sha256 "$sha" --output "$backup_copy" ||
          fail 'immutable backup COS blob download failed'
        verify_file "$backup_copy" "$size" "$sha" || fail 'backup COS blob bytes do not match'
        mapped_key="drills/$drill_id/blobs/$sha"
        "$RESTORE_COS_TRANSFER_COMMAND" upload-drill \
          --account "$RESTORE_DRILL_COS_ACCOUNT_ID" \
          --bucket "$RESTORE_DRILL_COS_BUCKET" \
          --region "$RESTORE_DRILL_COS_REGION" \
          --key "$mapped_key" --sha256 "$sha" --input "$backup_copy" ||
          fail 'drill COS upload failed'
        "$RESTORE_COS_TRANSFER_COMMAND" download-drill \
          --account "$RESTORE_DRILL_COS_ACCOUNT_ID" \
          --bucket "$RESTORE_DRILL_COS_BUCKET" \
          --region "$RESTORE_DRILL_COS_REGION" \
          --key "$mapped_key" --sha256 "$sha" --output "$drill_copy" ||
          fail 'drill COS read-back failed'
        verify_file "$drill_copy" "$size" "$sha" || fail 'drill COS read-back bytes do not match'
        bucket="$RESTORE_DRILL_COS_BUCKET"
        region="$RESTORE_DRILL_COS_REGION"
        ((cos_count += 1))
        ;;
      *) fail 'closure contains an unsupported storage provider' ;;
    esac
    printf '%s|%s|%s|%s|%s|%s|%s\n' \
      "$asset" "$kind" "$variant" "$provider" "$bucket" "$region" "$mapped_key" \
      >>"$mapping"

    if [[ "$kind" == VARIANT && "$current" == true ]]; then
      ((current_routes += 1))
      local expected_redirect=''
      [[ "$provider" != TENCENT_COS ]] || expected_redirect="$RESTORE_DRILL_COS_ORIGIN/$mapped_key"
      printf 'current|%s|%s|%s|%s|%s|%s\n' \
        "$RESTORE_NGINX_BASE_URL/api/public/media/$asset/$variant" \
        "$size" "$sha" "$mime" "$total" "$expected_redirect" >>"$routes"
    fi
    if [[ "$kind" == VARIANT && "$historical" == true && "$current" == false ]]; then
      ((historical_routes += 1))
      local expected_redirect=''
      [[ "$provider" != TENCENT_COS ]] || expected_redirect="$RESTORE_DRILL_COS_ORIGIN/$mapped_key"
      printf 'historical|%s|%s|%s|%s|%s|%s\n' \
        "$RESTORE_NGINX_BASE_URL/api/admin/media/$asset/preview/$variant" \
        "$size" "$sha" "$mime" "$total" "$expected_redirect" >>"$routes"
    fi
    ((total += 1))
  done <"$closure"
  ((total > 0 && current_routes > 0 && historical_routes > 0)) ||
    fail 'closure does not prove both current and historical route bytes'
  [[ "$(wc -l <"$mapping")" -eq "$total" ]] || fail 'media mapping does not cover closure exactly once'

  "$RESTORE_APPLY_MEDIA_MAPPING_COMMAND" apply \
    --mapping "$mapping" --expected-count "$total" || fail 'drill media mapping apply failed'
  "$RESTORE_APPLY_MEDIA_MAPPING_COMMAND" verify \
    --mapping "$mapping" \
    --expected-count "$total" \
    --forbid-production-locations "${PRODUCTION_COS_LOCATIONS:-}" \
    --forbid-backup-location "${BACKUP_DESTINATION_LOCATION:-}" \
    --local-root /var/lib/portfolio/media \
    --drill-prefix "drills/$drill_id/" || fail 'drill media mapping coverage or isolation failed'

  "$RESTORE_ROUTE_START_COMMAND" || fail 'isolated API and Nginx route startup failed'

  local route_label route_url route_size route_sha route_mime route_sequence route_redirect route_extra
  while IFS='|' read -r \
      route_label route_url route_size route_sha route_mime route_sequence route_redirect route_extra; do
    [[ -n "$route_label" && -z "${route_extra:-}" ]] || fail 'route verification row is malformed'
    verify_route \
      "$route_url" "$route_size" "$route_sha" "$route_mime" \
      "$route_label" "$route_sequence" "$route_redirect"
  done <"$routes"

  jq -n -S \
    --argjson closureObjects "$total" \
    --argjson localObjects "$local_count" \
    --argjson cosObjects "$cos_count" \
    --argjson currentRoutes "$current_routes" \
    --argjson historicalRoutes "$historical_routes" \
    '{closureObjects:$closureObjects,localObjects:$localObjects,cosObjects:$cosObjects,currentRoutes:$currentRoutes,historicalRoutes:$historicalRoutes}' \
    >"$summary"
  printf '%s\n' 'Restored Local/COS media and current/historical routes verified'
}

main "$@"
