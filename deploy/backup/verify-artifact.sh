#!/usr/bin/env bash
set -euo pipefail
set +x
umask 077

SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=deploy/backup/lib.sh
source "$SCRIPT_DIRECTORY/lib.sh"

validate_set_manifest() {
  local path="$1"
  local expected_set_id="$2"
  jq -e --arg setId "$expected_set_id" '
    (keys == [
      "artifacts","backupFinishedAt","backupStartedAt","blobs","counts",
      "releaseId","retention","schemaVersion","setId","snapshotId"
    ]) and
    .schemaVersion == 1 and .setId == $setId and
    (.releaseId | type == "string" and test("^[0-9a-f]{12}-[0-9a-f]{12}$")) and
    (.snapshotId | type == "string" and test("^[A-Za-z0-9-]{8,128}$")) and
    (.backupStartedAt | type == "string" and
      test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")) and
    (.backupFinishedAt | type == "string" and
      test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")) and
    (.backupStartedAt <= .backupFinishedAt) and
    (.retention | keys == ["daily","monthly","weekly"] and
      .daily == true and (.weekly | type == "boolean") and (.monthly | type == "boolean")) and
    (.counts | keys == [
      "cosObjects","cosVerificationSamples","distinctCosBlobs","localObjects","totalObjects"
    ] and all(.[]; type == "number" and floor == . and . >= 0)) and
    (.counts.totalObjects == (.counts.localObjects + .counts.cosObjects)) and
    (.counts.distinctCosBlobs == (.blobs | length)) and
    (.artifacts | keys == ["databaseDump","localMediaTar","mediaManifest"]) and
    (.artifacts.databaseDump.file == "database.dump.age") and
    (.artifacts.localMediaTar.file == "local-media.tar.age") and
    (.artifacts.mediaManifest.file == "media-manifest.json.age") and
    all(.artifacts[];
      (keys == ["byteSize","ciphertextSha256","file"]) and
      (.file | type == "string" and test("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")) and
      (.byteSize | type == "number" and floor == . and . > 0) and
      (.ciphertextSha256 | type == "string" and test("^[0-9a-f]{64}$"))) and
    (.blobs | type == "array") and
    all(.blobs[];
      (keys == ["byteSize","path","sha256"]) and
      (.sha256 | type == "string" and test("^[0-9a-f]{64}$")) and
      (.path == ("blobs/" + .sha256)) and
      (.byteSize | type == "number" and floor == . and . > 0)) and
    ((.blobs | map(.path)) == (.blobs | map(.path) | sort | unique))
  ' "$path" >/dev/null || backup_fail 'set manifest has an invalid or non-canonical shape'
}

expected_sample_count() {
  local total="$1"
  if ((total == 0)); then
    printf '0\n'
  elif ((total < 20)); then
    printf '%s\n' "$total"
  else
    local sample=$(((total + 99) / 100))
    ((sample < 20)) && sample=20
    ((sample > 200)) && sample=200
    ((sample > total)) && sample="$total"
    printf '%s\n' "$sample"
  fi
}

verify_set() {
  local set_id='' expected_manifest_sha='' local_manifest='' work_directory=''
  while (($#)); do
    case "$1" in
      --set-id) set_id="$2"; shift 2 ;;
      --expected-manifest-sha) expected_manifest_sha="$2"; shift 2 ;;
      --local-manifest) local_manifest="$2"; shift 2 ;;
      --work-directory) work_directory="$2"; shift 2 ;;
      *) backup_fail 'usage: verify-artifact.sh set --set-id ID --expected-manifest-sha SHA --local-manifest FILE --work-directory DIR' ;;
    esac
  done
  backup_is_set_id "$set_id" || backup_fail 'set ID is invalid'
  backup_is_sha256 "$expected_manifest_sha" || backup_fail 'set manifest SHA-256 is invalid'
  [[ "$local_manifest" == /* && ! -L "$local_manifest" && -f "$local_manifest" ]] ||
    backup_fail 'local set manifest is invalid'
  backup_require_private_directory "$work_directory" 'set verification work directory'

  backup_resolve_command BACKUP_RCLONE_COMMAND rclone
  backup_validate_topology nightly

  local remote_manifest="$work_directory/remote-set-manifest.json"
  local manifest_remote
  manifest_remote="$(backup_set_remote_path "$set_id" set-manifest.json)"
  if ! backup_verify_download "$manifest_remote" "$remote_manifest" >/dev/null 2>&1; then
    backup_fail 'verifier could not download the set manifest'
  fi
  [[ "$(backup_sha256_file "$remote_manifest")" == "$expected_manifest_sha" ]] ||
    backup_fail 'remote set manifest checksum does not match'
  cmp -s -- "$local_manifest" "$remote_manifest" ||
    backup_fail 'remote set manifest bytes differ from the published canonical manifest'
  validate_set_manifest "$remote_manifest" "$set_id"

  local artifact_name file expected_sha expected_size remote local_copy
  for artifact_name in databaseDump localMediaTar mediaManifest; do
    file="$(jq -r --arg name "$artifact_name" '.artifacts[$name].file' "$remote_manifest")"
    expected_sha="$(jq -r --arg name "$artifact_name" '.artifacts[$name].ciphertextSha256' "$remote_manifest")"
    expected_size="$(jq -r --arg name "$artifact_name" '.artifacts[$name].byteSize' "$remote_manifest")"
    remote="$(backup_set_remote_path "$set_id" "$file")"
    local_copy="$work_directory/verify-$artifact_name"
    if ! backup_verify_download "$remote" "$local_copy" >/dev/null 2>&1; then
      backup_fail 'verifier could not download a set artifact'
    fi
    backup_verify_file "$local_copy" "$expected_size" "$expected_sha" ||
      backup_fail 'remote set artifact ciphertext validation failed'
    rm -f -- "$local_copy"
  done

  local total sample declared_sample ranking selected
  total="$(jq '.blobs | length' "$remote_manifest")"
  sample="$(expected_sample_count "$total")"
  declared_sample="$(jq '.counts.cosVerificationSamples' "$remote_manifest")"
  [[ "$declared_sample" == "$sample" ]] ||
    backup_fail 'set manifest declares an invalid COS verification sample count'
  ranking="$work_directory/blob-ranking.tsv"
  : >"$ranking"
  local blob_path blob_sha blob_size seed_rank
  while IFS=$'\t' read -r blob_path blob_sha blob_size; do
    [[ -n "$blob_path" ]] || continue
    seed_rank="$(printf '%s:%s' "$set_id" "$blob_sha" | sha256sum | awk '{print $1}')"
    printf '%s\t%s\t%s\t%s\n' "$seed_rank" "$blob_path" "$blob_sha" "$blob_size" >>"$ranking"
  done < <(jq -r '.blobs[] | [.path,.sha256,(.byteSize|tostring)] | @tsv' "$remote_manifest")
  LC_ALL=C sort -o "$ranking" "$ranking"
  selected="$work_directory/blob-selected.tsv"
  awk -v count="$sample" 'NR <= count {print $0}' "$ranking" >"$selected"
  [[ "$(wc -l <"$selected")" -eq "$sample" ]] ||
    backup_fail 'deterministic COS verification sample is incomplete'
  local sequence=0 blob_copy
  while IFS=$'\t' read -r seed_rank blob_path blob_sha blob_size; do
    [[ -n "$blob_path" ]] || continue
    blob_copy="$work_directory/verify-blob-$sequence"
    remote="$(backup_remote_path blobs "${blob_path#blobs/}")"
    if ! backup_verify_download "$remote" "$blob_copy" >/dev/null 2>&1; then
      backup_fail 'verifier could not download a sampled immutable blob'
    fi
    backup_verify_file "$blob_copy" "$blob_size" "$blob_sha" ||
      backup_fail 'sampled immutable blob validation failed'
    rm -f -- "$blob_copy"
    ((sequence += 1))
  done <"$selected"

  printf '%s\n' "$expected_manifest_sha"
}

verify_marker() {
  local set_id='' expected_manifest_sha='' work_directory=''
  while (($#)); do
    case "$1" in
      --set-id) set_id="$2"; shift 2 ;;
      --expected-manifest-sha) expected_manifest_sha="$2"; shift 2 ;;
      --work-directory) work_directory="$2"; shift 2 ;;
      *) backup_fail 'usage: verify-artifact.sh marker --set-id ID --expected-manifest-sha SHA --work-directory DIR' ;;
    esac
  done
  backup_is_set_id "$set_id" || backup_fail 'set ID is invalid'
  backup_is_sha256 "$expected_manifest_sha" || backup_fail 'set manifest SHA-256 is invalid'
  backup_require_private_directory "$work_directory" 'marker verification work directory'
  backup_resolve_command BACKUP_RCLONE_COMMAND rclone
  backup_validate_topology nightly
  local marker="$work_directory/remote-VERIFIED"
  if ! backup_verify_download "$(backup_set_remote_path "$set_id" VERIFIED)" "$marker" >/dev/null 2>&1; then
    backup_fail 'verifier could not read the immutable verification marker'
  fi
  [[ "$(wc -l <"$marker")" -eq 1 && "$(tr -d '\r\n' <"$marker")" == "$expected_manifest_sha" ]] ||
    backup_fail 'verification marker does not match the set manifest checksum'
  printf '%s\n' "$expected_manifest_sha"
}

[[ $# -ge 1 ]] || backup_fail 'usage: verify-artifact.sh set|marker ...'
mode="$1"
shift
case "$mode" in
  set) verify_set "$@" ;;
  marker) verify_marker "$@" ;;
  sample-count)
    [[ $# -eq 1 && "$1" =~ ^(0|[1-9][0-9]*)$ ]] ||
      backup_fail 'usage: verify-artifact.sh sample-count TOTAL'
    expected_sample_count "$1"
    ;;
  *) backup_fail 'artifact verification mode must be set or marker' ;;
esac
