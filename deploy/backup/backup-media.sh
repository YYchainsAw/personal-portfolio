#!/usr/bin/env bash
set -euo pipefail
set +x
umask 077

SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=deploy/backup/lib.sh
source "$SCRIPT_DIRECTORY/lib.sh"

validate_export_shape() {
  local path="$1"
  jq -e '
    (keys == ["rowCounts","rows","schemaVersion","snapshotId"]) and
    .schemaVersion == 1 and
    (.snapshotId | type == "string" and test("^[A-Za-z0-9-]{8,128}$")) and
    (.rowCounts | type == "object" and
      keys == [
        "adminUser","contactMessage","contentRevision","flywaySchemaHistory",
        "mediaAsset","mediaVariant","publication","revisionMediaReference"
      ] and all(.[]; type == "number" and floor == . and . >= 0)) and
    (.rows | type == "array") and
    all(.rows[];
      (keys == [
        "assetId","bucket","byteSize","mimeType","objectKey","objectKind",
        "provider","region","sha256","variantName"
      ]) and
      (.assetId | type == "string" and
        test("^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")) and
      (.objectKind == "ORIGINAL" or .objectKind == "VARIANT") and
      (.variantName | type == "string" and test("^[A-Za-z0-9_-]{1,32}$")) and
      (.provider == "LOCAL" or .provider == "TENCENT_COS") and
      (.objectKey | type == "string" and
        test("^[A-Za-z0-9._/-]+$") and
        (startswith("/") | not) and
        (contains("\\\\") | not) and
        (test("(^|/)\\.\\.?(/|$)") | not) and
        (contains("//") | not)) and
      (.mimeType | type == "string" and
        test("^[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+$")) and
      (.byteSize | type == "number" and floor == . and . > 0) and
      (.sha256 | type == "string" and test("^[0-9a-f]{64}$")) and
      (if .provider == "LOCAL" then .bucket == null and .region == null
       else
         (.bucket | type == "string" and test("^[a-z0-9][a-z0-9.-]{1,126}[a-z0-9]$")) and
         (.region | type == "string" and test("^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$"))
       end)
    ) and
    ((.rows | map([.assetId,.objectKind,.variantName] | join("|")) | length) ==
      (.rows | map([.assetId,.objectKind,.variantName] | join("|")) | unique | length)) and
    ((.rows | map(select(.objectKind == "ORIGINAL")) | length) == .rowCounts.mediaAsset) and
    ((.rows | map(select(.objectKind == "VARIANT")) | length) == .rowCounts.mediaVariant)
  ' "$path" >/dev/null || backup_fail 'exported media snapshot has an invalid shape'
}

export_snapshot() {
  local snapshot_fd='' output=''
  while (($#)); do
    case "$1" in
      --snapshot-fd) snapshot_fd="$2"; shift 2 ;;
      --output) output="$2"; shift 2 ;;
      *) backup_fail 'usage: backup-media.sh export --snapshot-fd FD --output FILE' ;;
    esac
  done
  [[ "$snapshot_fd" =~ ^[0-9]+$ && "$snapshot_fd" -ge 3 ]] ||
    backup_fail 'snapshot file descriptor is invalid'
  [[ "$output" == /* && ! -e "$output" && ! -L "$output" ]] ||
    backup_fail 'media export output must be an absolute new path'

  BACKUP_POSTGRES_CLIENT_COMMAND="${BACKUP_POSTGRES_CLIENT_COMMAND:-$SCRIPT_DIRECTORY/postgres-client.sh}"
  backup_resolve_command BACKUP_POSTGRES_CLIENT_COMMAND postgres-client.sh

  local snapshot_id raw
  snapshot_id=''
  IFS= read -r snapshot_id <&"$snapshot_fd" || backup_fail 'snapshot file descriptor is unreadable'
  [[ "$snapshot_id" =~ ^[A-Za-z0-9-]{8,128}$ ]] || backup_fail 'snapshot identifier is invalid'
  raw="${output}.raw"
  [[ ! -e "$raw" && ! -L "$raw" ]] || backup_fail 'media export scratch path already exists'
  if ! {
    printf "\\set backup_snapshot '%s'\n" "$snapshot_id"
    cat "$SCRIPT_DIRECTORY/export-media.sql"
  } | "$BACKUP_POSTGRES_CLIENT_COMMAND" psql -A -t -q --set ON_ERROR_STOP=1 \
      >"$raw" 2>/dev/null; then
    backup_fail 'media snapshot reader failed'
  fi
  jq -S -c . "$raw" >"$output" || backup_fail 'media snapshot reader did not return JSON'
  rm -f -- "$raw"
  validate_export_shape "$output"
  [[ "$(jq -r '.snapshotId' "$output")" == "$snapshot_id" ]] ||
    backup_fail 'media snapshot reader returned a different snapshot identifier'
}

require_new_output() {
  local path="$1"
  [[ "$path" == /* && ! -e "$path" && ! -L "$path" ]] ||
    backup_fail 'media package output must be an absolute new path'
}

package_snapshot() {
  local exported='' database_sha='' set_id='' local_tar='' local_allowlist=''
  local manifest_output='' blobs_output='' work_directory=''
  while (($#)); do
    case "$1" in
      --export) exported="$2"; shift 2 ;;
      --database-sha) database_sha="$2"; shift 2 ;;
      --set-id) set_id="$2"; shift 2 ;;
      --local-tar) local_tar="$2"; shift 2 ;;
      --local-allowlist) local_allowlist="$2"; shift 2 ;;
      --manifest-output) manifest_output="$2"; shift 2 ;;
      --blobs-output) blobs_output="$2"; shift 2 ;;
      --work-directory) work_directory="$2"; shift 2 ;;
      *) backup_fail 'usage: backup-media.sh package --export FILE --database-sha SHA --set-id ID --local-tar FILE --local-allowlist FILE --manifest-output FILE --blobs-output FILE --work-directory DIR' ;;
    esac
  done

  [[ "$exported" == /* && ! -L "$exported" && -f "$exported" ]] ||
    backup_fail 'media export input is invalid'
  backup_is_sha256 "$database_sha" || backup_fail 'database dump SHA-256 is invalid'
  backup_is_set_id "$set_id" || backup_fail 'set ID is invalid'
  backup_require_private_directory "$work_directory" 'media packaging work directory'
  for path in "$local_tar" "$local_allowlist" "$manifest_output" "$blobs_output"; do
    require_new_output "$path"
  done
  backup_require_value LOCAL_MEDIA_ROOT
  [[ "$LOCAL_MEDIA_ROOT" == /* && ! -L "$LOCAL_MEDIA_ROOT" && -d "$LOCAL_MEDIA_ROOT" ]] ||
    backup_fail 'LOCAL_MEDIA_ROOT must be an absolute regular directory'
  LOCAL_MEDIA_ROOT="$(realpath -e -- "$LOCAL_MEDIA_ROOT")"

  BACKUP_MEDIA_TAR_COMMAND="${BACKUP_MEDIA_TAR_COMMAND:-$SCRIPT_DIRECTORY/validate-media-tar.py}"
  backup_resolve_command BACKUP_MEDIA_TAR_COMMAND validate-media-tar.py
  backup_resolve_command BACKUP_RCLONE_COMMAND rclone
  backup_validate_topology nightly
  validate_export_shape "$exported"

  jq -S -c \
    --arg setId "$set_id" \
    --arg databaseDumpPlaintextSha256 "$database_sha" \
    '{schemaVersion:1,setId:$setId,snapshotId:.snapshotId,
      databaseDumpPlaintextSha256:$databaseDumpPlaintextSha256,
      rowCounts:.rowCounts,rows:.rows}' \
    "$exported" >"$manifest_output"

  jq -S -c '
    [.rows[] | select(.provider == "LOCAL") |
      {path:.objectKey,size:.byteSize,sha256:.sha256}]
    | sort_by(.path)
    | group_by(.path)
    | map(
        if (map([.size,.sha256] | join("|")) | unique | length) == 1
        then .[0]
        else error("Local path has conflicting immutable metadata")
        end
      )
    | {schemaVersion:1,entries:.}
  ' "$exported" >"$local_allowlist" || backup_fail 'Local media allowlist is inconsistent'

  "$BACKUP_MEDIA_TAR_COMMAND" \
    --allowlist "$local_allowlist" \
    --build-root "$LOCAL_MEDIA_ROOT" \
    --build-output "$local_tar" >/dev/null
  "$BACKUP_MEDIA_TAR_COMMAND" --allowlist "$local_allowlist" "$local_tar" >/dev/null

  local blob_records="$work_directory/blob-records.jsonl"
  : >"$blob_records"
  local total local_count cos_count index
  total="$(jq '.rows | length' "$exported")"
  local_count="$(jq '[.rows[] | select(.provider == "LOCAL")] | length' "$exported")"
  cos_count="$(jq '[.rows[] | select(.provider == "TENCENT_COS")] | length' "$exported")"
  index=0
  declare -A blob_sizes=()
  local encoded row provider bucket region object_key expected_size expected_sha
  local source_remote source_copy blob_remote blob_readback existing_size
  while IFS= read -r encoded; do
    [[ -n "$encoded" ]] || continue
    row="$(printf '%s' "$encoded" | base64 -d)" || backup_fail 'media row decoding failed'
    provider="$(jq -r '.provider' <<<"$row")"
    [[ "$provider" == TENCENT_COS ]] || continue
    bucket="$(jq -r '.bucket' <<<"$row")"
    region="$(jq -r '.region' <<<"$row")"
    object_key="$(jq -r '.objectKey' <<<"$row")"
    expected_size="$(jq -r '.byteSize' <<<"$row")"
    expected_sha="$(jq -r '.sha256' <<<"$row")"
    source_remote="$(backup_source_remote_path "$bucket" "$region" "$object_key")"
    source_copy="$work_directory/cos-source-$index"
    if ! backup_source_download "$source_remote" "$source_copy" >/dev/null 2>&1; then
      backup_fail 'a COS media source could not be read with the source-reader credential'
    fi
    backup_verify_file "$source_copy" "$expected_size" "$expected_sha" ||
      backup_fail 'a COS media source failed byte-count or SHA-256 verification'

    existing_size="${blob_sizes[$expected_sha]:-}"
    if [[ -n "$existing_size" ]]; then
      [[ "$existing_size" == "$expected_size" ]] ||
        backup_fail 'one content hash has conflicting byte sizes'
    else
      blob_remote="$(backup_remote_path blobs "$expected_sha")"
      blob_readback="$work_directory/blob-readback-$expected_sha"
      if backup_verify_download "$blob_remote" "$blob_readback" >/dev/null 2>&1; then
        backup_verify_file "$blob_readback" "$expected_size" "$expected_sha" ||
          backup_fail 'a pre-existing immutable blob failed verifier read-back'
      else
        rm -f -- "$blob_readback"
        if ! backup_upload_immutable "$source_copy" "$blob_remote" >/dev/null 2>&1; then
          backup_fail 'an immutable COS blob upload failed'
        fi
        if ! backup_verify_download "$blob_remote" "$blob_readback" >/dev/null 2>&1; then
          backup_fail 'an uploaded COS blob could not be read by the verifier credential'
        fi
        backup_verify_file "$blob_readback" "$expected_size" "$expected_sha" ||
          backup_fail 'an uploaded COS blob failed verifier byte validation'
      fi
      jq -S -c -n \
        --arg path "blobs/$expected_sha" \
        --arg sha256 "$expected_sha" \
        --argjson byteSize "$expected_size" \
        '{path:$path,sha256:$sha256,byteSize:$byteSize}' >>"$blob_records"
      blob_sizes["$expected_sha"]="$expected_size"
    fi
    rm -f -- "$source_copy" "$blob_readback"
    ((index += 1))
  done < <(jq -r '.rows[] | @base64' "$exported")

  jq -S -c -s \
    --argjson total "$total" \
    --argjson local "$local_count" \
    --argjson cos "$cos_count" \
    '{schemaVersion:1,
      counts:{totalObjects:$total,localObjects:$local,cosObjects:$cos,distinctCosBlobs:length},
      blobs:(sort_by(.path))}' \
    "$blob_records" >"$blobs_output"
}

[[ $# -ge 1 ]] || backup_fail 'usage: backup-media.sh export|package ...'
mode="$1"
shift
case "$mode" in
  export) export_snapshot "$@" ;;
  package) package_snapshot "$@" ;;
  *) backup_fail 'media backup mode must be export or package' ;;
esac
