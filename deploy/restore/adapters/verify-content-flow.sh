#!/usr/bin/env bash
# shellcheck disable=SC2016
set -euo pipefail
set +x
umask 077

SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=deploy/restore/adapters/adapter-lib.sh
source "$SCRIPT_DIRECTORY/adapter-lib.sh"

curl_local() {
  "$RESTORE_CURL_COMMAND" --disable --noproxy '*' --silent --show-error \
    --proto '=https' --tlsv1.2 \
    --cacert "$RESTORE_TLS_CA_CERT" \
    --resolve "$RESTORE_TLS_SERVER_NAME:$RESTORE_NGINX_PORT:127.0.0.1" \
    --cookie "$RESTORE_ADMIN_COOKIE_JAR" --cookie-jar "$RESTORE_ADMIN_COOKIE_JAR" "$@"
}

fetch_json() {
  local url="$1"
  local output="$2"
  local status
  status="$(curl_local --output "$output" --write-out '%{http_code}' "$url")" ||
    adapter_fail 'drill API JSON fetch failed'
  [[ "$status" == 200 ]] || adapter_fail 'drill API JSON fetch returned a non-200 status'
  jq -e . "$output" >/dev/null || adapter_fail 'drill API returned invalid JSON'
}

revision_plan() {
  local joined="$1"
  PORTFOLIO_RESTORE_PHASE=content-plan adapter_compose exec -T restore-postgres sh -eu -c '
    exec psql -X --no-psqlrc -A -t -q -F "|" -v ON_ERROR_STOP=1 \
      -U "$POSTGRES_USER" -d "$POSTGRES_DB" "$@"
  ' portfolio-content-plan --set="revision_ids=$joined" -f - <<'SQL'
WITH selected AS (
  SELECT DISTINCT value::UUID AS revision_id
  FROM unnest(string_to_array(:'revision_ids', ',')) AS value
), plan AS (
  SELECT
    revision.id,
    revision.aggregate_type,
    revision.aggregate_id,
    CASE revision.aggregate_type
      WHEN 'SITE' THEN site.version
      WHEN 'PROJECT' THEN project.version
      ELSE NULL
    END AS workspace_version,
    publication.current_revision_id
  FROM selected
  JOIN portfolio.content_revision AS revision ON revision.id = selected.revision_id
  LEFT JOIN portfolio.publication AS publication
    ON publication.aggregate_type = revision.aggregate_type
   AND publication.aggregate_id = revision.aggregate_id
  LEFT JOIN portfolio.site_profile AS site
    ON revision.aggregate_type = 'SITE' AND site.singleton_key = TRUE
  LEFT JOIN portfolio.project AS project
    ON revision.aggregate_type = 'PROJECT' AND project.id = revision.aggregate_id
)
SELECT id, aggregate_type, aggregate_id, workspace_version, current_revision_id
FROM plan
WHERE aggregate_type IN ('SITE', 'PROJECT')
  AND workspace_version IS NOT NULL
  AND current_revision_id IS NOT NULL
  AND current_revision_id <> id
ORDER BY id;
SQL
}

revision_references() {
  local revision_id="$1"
  PORTFOLIO_RESTORE_PHASE=content-revision-references adapter_compose exec -T restore-postgres sh -eu -c '
    exec psql -X --no-psqlrc -A -t -q -F "|" -v ON_ERROR_STOP=1 \
      -U "$POSTGRES_USER" -d "$POSTGRES_DB" "$@"
  ' portfolio-content-references --set="revision_id=$revision_id" -f - <<'SQL'
SELECT reference.asset_id, reference.variant_name, reference.usage, asset.provider
FROM portfolio.revision_media_reference AS reference
JOIN portfolio.media_asset AS asset ON asset.id = reference.asset_id
WHERE reference.revision_id = :'revision_id'::UUID
GROUP BY reference.asset_id, reference.variant_name, reference.usage, asset.provider
ORDER BY reference.asset_id, reference.variant_name, reference.usage, asset.provider;
SQL
}

revision_snapshot() {
  local revision_id="$1"
  PORTFOLIO_RESTORE_PHASE=content-revision-snapshot adapter_compose exec -T restore-postgres sh -eu -c '
    exec psql -X --no-psqlrc -A -t -q -v ON_ERROR_STOP=1 \
      -U "$POSTGRES_USER" -d "$POSTGRES_DB" "$@"
  ' portfolio-content-revision-snapshot --set="revision_id=$revision_id" -f - <<'SQL'
SELECT snapshot::TEXT
FROM portfolio.content_revision
WHERE id = :'revision_id'::UUID;
SQL
}

preview_snapshot() {
  local base_url="$1" aggregate_type="$2" aggregate_id="$3" version="$4" output="$5"
  local csrf_file="$6" header token request response status preview_token
  fetch_json "$base_url/api/admin/auth/csrf" "$csrf_file"
  header="$(jq -er '.headerName | select(test("^[A-Za-z0-9-]{1,128}$"))' "$csrf_file")" ||
    adapter_fail 'preview CSRF header name is invalid'
  token="$(jq -er '.token | select(type == "string" and length > 0 and length <= 4096)' "$csrf_file")" ||
    adapter_fail 'preview CSRF token is invalid'
  request="${output}.request"
  response="${output}.token"
  jq -n -c --arg aggregateType "$aggregate_type" --arg aggregateId "$aggregate_id" \
    --argjson workspaceVersion "$version" \
    '{aggregateType:$aggregateType,aggregateId:$aggregateId,workspaceVersion:$workspaceVersion}' >"$request"
  status="$(curl_local --output "$response" --write-out '%{http_code}' \
    --request POST --header 'Content-Type: application/json' --header "$header: $token" \
    --data-binary "@$request" "$base_url/api/admin/publishing/preview-tokens")" ||
    adapter_fail 'restored workspace preview token request failed'
  [[ "$status" == 200 ]] || adapter_fail 'restored workspace preview token request returned a non-200 status'
  preview_token="$(jq -er '.token | select(type == "string" and test("^[A-Za-z0-9_-]{16,4096}$"))' "$response")" ||
    adapter_fail 'restored workspace preview token response is invalid'
  fetch_json "$base_url/api/admin/publishing/previews/$preview_token" "$output"
  rm -f -- "$request" "$response"
}

publication_pointer() {
  local aggregate_type="$1" aggregate_id="$2"
  PORTFOLIO_RESTORE_PHASE=content-publication-pointer adapter_compose exec -T restore-postgres sh -eu -c '
    exec psql -X --no-psqlrc -A -t -q -v ON_ERROR_STOP=1 \
      -U "$POSTGRES_USER" -d "$POSTGRES_DB" "$@"
  ' portfolio-publication-pointer --set="aggregate_type=$aggregate_type" \
    --set="aggregate_id=$aggregate_id" -f - <<'SQL'
SELECT current_revision_id
FROM portfolio.publication
WHERE aggregate_type = :'aggregate_type'
  AND aggregate_id = :'aggregate_id'::UUID;
SQL
}

audit_total() {
  local revision_id="$1"
  PORTFOLIO_RESTORE_PHASE=content-audit-total adapter_compose exec -T restore-postgres sh -eu -c '
    exec psql -X --no-psqlrc -A -t -q -v ON_ERROR_STOP=1 \
      -U "$POSTGRES_USER" -d "$POSTGRES_DB" "$@"
  ' portfolio-content-audit-total --set="revision_id=$revision_id" -f - <<'SQL'
SELECT count(*)
FROM portfolio.audit_log
WHERE action = 'REVISION_RESTORED_TO_DRAFT'
  AND metadata->>'revisionId' = :'revision_id';
SQL
}

audit_after() {
  local revision_id="$1" started_at="$2"
  PORTFOLIO_RESTORE_PHASE=content-audit-after adapter_compose exec -T restore-postgres sh -eu -c '
    exec psql -X --no-psqlrc -A -t -q -F "|" -v ON_ERROR_STOP=1 \
      -U "$POSTGRES_USER" -d "$POSTGRES_DB" "$@"
  ' portfolio-content-audit-after --set="revision_id=$revision_id" \
    --set="started_at=$started_at" -f - <<'SQL'
SELECT count(*), count(*) FILTER (WHERE created_at >= :'started_at'::TIMESTAMPTZ)
FROM portfolio.audit_log
WHERE action = 'REVISION_RESTORED_TO_DRAFT'
  AND metadata->>'revisionId' = :'revision_id';
SQL
}

main() {
  local base_url=''
  local -a revisions=()
  while (($#)); do
    case "$1" in
      --base-url) base_url="$2"; shift 2 ;;
      --historical-revision) revisions+=("$2"); shift 2 ;;
      *) adapter_fail 'content verification received an unsupported argument' ;;
    esac
  done
  adapter_require_drill_context
  adapter_require_disposed_identity
  adapter_resolve_command RESTORE_CURL_COMMAND curl
  adapter_resolve_command RESTORE_DOCKER_COMMAND docker
  [[ "$base_url" == "$RESTORE_NGINX_BASE_URL" &&
     "$base_url" == "https://$RESTORE_TLS_SERVER_NAME:$RESTORE_NGINX_PORT" ]] ||
    adapter_fail 'content verification base URL is not the local drill Nginx'
  ((${#revisions[@]} > 0)) || adapter_fail 'at least one historical revision is required'
  local revision
  for revision in "${revisions[@]}"; do
    adapter_is_uuid "$revision" || adapter_fail 'historical revision ID is invalid'
  done
  adapter_require_private_file "${RESTORE_TLS_CA_CERT:-}" 'drill TLS CA certificate'
  adapter_require_private_file "${RESTORE_ADMIN_COOKIE_JAR:-}" 'drill administrator cookie jar'
  [[ "$RESTORE_ADMIN_COOKIE_JAR" == "$RESTORE_ROOT/work/admin-cookie.jar" ]] ||
    adapter_fail 'administrator cookie jar escaped the drill work directory'

  local work="$RESTORE_ROOT/work/content-flow"
  [[ ! -e "$work" ]] || adapter_fail 'content flow work directory already exists'
  mkdir -m 0700 -- "$work"
  fetch_json "$base_url/api/public/site?locale=zh-CN" "$work/site.json"
  fetch_json "$base_url/api/public/projects?locale=zh-CN" "$work/projects.json"
  local public_site_sha public_projects_sha
  public_site_sha="$(sha256sum "$work/site.json" | awk '{print $1}')"
  public_projects_sha="$(sha256sum "$work/projects.json" | awk '{print $1}')"
  local current_projects
  current_projects="$(jq -er '.data | if type == "array" then length else error("catalog data is not an array") end' "$work/projects.json")" ||
    adapter_fail 'public project catalog response is invalid'
  ((current_projects > 0)) || adapter_fail 'public project catalog is empty'
  fetch_json "$base_url/api/admin/auth/csrf" "$work/csrf.json"
  fetch_json "$base_url/api/admin/auth/me" "$work/me.json"
  local csrf_header csrf_token
  csrf_header="$(jq -er '.headerName | select(test("^[A-Za-z0-9-]{1,128}$"))' "$work/csrf.json")" ||
    adapter_fail 'CSRF header name is invalid'
  csrf_token="$(jq -er '.token | select(type == "string" and length > 0 and length <= 4096)' "$work/csrf.json")" ||
    adapter_fail 'CSRF token is invalid'

  local joined plan="$work/revision-plan.psv"
  joined="$(IFS=,; printf '%s' "${revisions[*]}")"
  revision_plan "$joined" >"$plan" || adapter_fail 'historical revision draft plan query failed'
  [[ "$(wc -l <"$plan")" == "${#revisions[@]}" ]] ||
    adapter_fail 'not every selected historical revision is restorable through the API'
  local restored=0 id aggregate_type aggregate_id version pointer extra status workspace_url
  local provider_evidence="$work/reference-providers"
  : >"$provider_evidence"
  while IFS='|' read -r id aggregate_type aggregate_id version pointer extra; do
    [[ -z "${extra:-}" && "$version" =~ ^[0-9]+$ && "$pointer" =~ ^[0-9a-f-]{36}$ && "$pointer" != "$id" ]] ||
      adapter_fail 'historical revision plan row is invalid'
    local references_before="$work/references-$id.before"
    revision_references "$id" >"$references_before" || adapter_fail 'historical revision media reference query failed'
    [[ -s "$references_before" ]] || adapter_fail 'historical revision has no immutable media references'
    cut -d '|' -f 4 "$references_before" >>"$provider_evidence"
    local immutable_snapshot="$work/snapshot-$id.immutable.json"
    local immutable_snapshot_raw="$work/snapshot-$id.immutable.raw"
    revision_snapshot "$id" >"$immutable_snapshot_raw" ||
      adapter_fail 'immutable historical revision snapshot query failed'
    [[ "$(wc -l <"$immutable_snapshot_raw")" == 1 ]] ||
      adapter_fail 'immutable historical revision snapshot is missing or ambiguous'
    jq -cS . "$immutable_snapshot_raw" >"$immutable_snapshot" ||
      adapter_fail 'immutable historical revision snapshot is invalid JSON'
    local audit_before_count restore_started_at
    audit_before_count="$(audit_total "$id")" || adapter_fail 'pre-restore audit baseline query failed'
    [[ "$audit_before_count" =~ ^[0-9]+$ ]] || adapter_fail 'pre-restore audit baseline is invalid'
    restore_started_at="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
    fetch_json "$base_url/api/admin/auth/csrf" "$work/csrf.json"
    csrf_header="$(jq -er '.headerName | select(test("^[A-Za-z0-9-]{1,128}$"))' "$work/csrf.json")" ||
      adapter_fail 'CSRF header name is invalid'
    csrf_token="$(jq -er '.token | select(type == "string" and length > 0 and length <= 4096)' "$work/csrf.json")" ||
      adapter_fail 'CSRF token is invalid'
    status="$(curl_local --output "$work/restore-$id.body" --write-out '%{http_code}' \
      --request POST \
      --header 'Content-Type: application/json' \
      --header "$csrf_header: $csrf_token" \
      --data "{\"expectedWorkspaceVersion\":$version}" \
      "$base_url/api/admin/publishing/revisions/$id/restore")" ||
      adapter_fail 'historical revision restore API call failed'
    [[ "$status" == 204 && ! -s "$work/restore-$id.body" ]] ||
      adapter_fail 'historical revision restore did not return an empty 204 response'
    case "$aggregate_type" in
      SITE) workspace_url="$base_url/api/admin/site/workspace" ;;
      PROJECT) workspace_url="$base_url/api/admin/projects/$aggregate_id/workspace" ;;
      *) adapter_fail 'historical revision plan contains a non-restorable aggregate' ;;
    esac
    fetch_json "$workspace_url" "$work/workspace-$id.json"
    jq -e --argjson expected "$((version + 1))" --arg type "$aggregate_type" '
      .version == $expected and (if $type == "PROJECT" then .publicationDirty == true else true end)
    ' "$work/workspace-$id.json" >/dev/null ||
      adapter_fail 'draft workspace did not reflect the historical revision restore'
    [[ "$(publication_pointer "$aggregate_type" "$aggregate_id")" == "$pointer" ]] ||
      adapter_fail 'draft restore changed the published revision pointer'
    local restored_snapshot_raw="$work/snapshot-$id.restored.raw"
    local restored_snapshot="$work/snapshot-$id.restored.json"
    preview_snapshot "$base_url" "$aggregate_type" "$aggregate_id" "$((version + 1))" \
      "$restored_snapshot_raw" "$work/csrf.json"
    jq -cS . "$restored_snapshot_raw" >"$restored_snapshot" ||
      adapter_fail 'restored workspace preview snapshot is invalid JSON'
    cmp -s "$immutable_snapshot" "$restored_snapshot" ||
      adapter_fail 'restored workspace semantic snapshot differs from immutable history'
    fetch_json "$base_url/api/public/site?locale=zh-CN" "$work/site-after-$id.json"
    fetch_json "$base_url/api/public/projects?locale=zh-CN" "$work/projects-after-$id.json"
    [[ "$(sha256sum "$work/site-after-$id.json" | awk '{print $1}')" == "$public_site_sha" &&
       "$(sha256sum "$work/projects-after-$id.json" | awk '{print $1}')" == "$public_projects_sha" ]] ||
      adapter_fail 'draft restore changed publicly served content'
    local audit_result audit_total_after audit_recent_after audit_extra
    audit_result="$(audit_after "$id" "$restore_started_at")" || adapter_fail 'post-restore audit query failed'
    IFS='|' read -r audit_total_after audit_recent_after audit_extra <<<"$audit_result"
    [[ -z "${audit_extra:-}" && "$audit_total_after" == "$((audit_before_count + 1))" &&
       "$audit_recent_after" == 1 ]] ||
      adapter_fail 'draft restore audit trail is not unique to this restore attempt'
    ((restored += 1))
  done <"$plan"
  sort -u "$provider_evidence" -o "$provider_evidence"
  if ! grep -Fx LOCAL "$provider_evidence" >/dev/null ||
     ! grep -Fx TENCENT_COS "$provider_evidence" >/dev/null; then
    adapter_fail 'selected historical revisions do not prove mixed Local and COS media restoration'
  fi
  jq -n -S --argjson currentProjects "$current_projects" \
    --argjson historicalRevisions "${#revisions[@]}" --argjson draftRestores "$restored" \
    '{currentProjects:$currentProjects,historicalRevisions:$historicalRevisions,draftRestores:$draftRestores}'
}

main "$@"
