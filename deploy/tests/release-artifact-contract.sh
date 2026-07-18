#!/usr/bin/env bash
set -euo pipefail

export LC_ALL=C

die() {
  printf 'release-artifact-contract: %s\n' "$*" >&2
  exit 1
}

release_dir="${1:?release directory required}"
release_id="${2:?release id required}"

[[ "$release_id" =~ ^[0-9a-f]{12}-[0-9a-f]{12}$ ]] ||
  die "invalid release id: $release_id"
[[ -d "$release_dir" ]] || die "release directory does not exist: $release_dir"
release_dir="$(realpath -e -- "$release_dir")"

for command_name in awk cmp date docker find grep jq mktemp realpath sha256sum sort tar unzip zstd; do
  command -v "$command_name" >/dev/null 2>&1 || die "required command not found: $command_name"
done

canonical_tree_sha() {
  local root="${1:?tree root required}"
  (
    cd "$root"
    find . -type f -print0 | sort -z |
      while IFS= read -r -d '' path; do
        printf '%s\0%s\0' "${path#./}" "$(sha256sum -- "$path" | awk '{print $1}')"
      done
  ) | sha256sum | awk '{print $1}'
}

config_digest_from_manifest() {
  local manifest_json="${1:?docker save manifest required}"
  local expected_tag="${2:-}"
  local config_path config_sha
  config_path="$(printf '%s' "$manifest_json" | jq -er --arg tag "$expected_tag" '
    if type == "array" and length == 1 and
       (.[0] | type == "object") and
       (.[0].Config | type == "string") and
       ($tag == "" or (.[0].RepoTags == [$tag])) and
       (.[0].Layers | type == "array" and length > 0 and all(.[]; type == "string"))
    then .[0].Config
    else error("docker save manifest must describe exactly one tagged image")
    end
  ')" || die "invalid docker save manifest"
  if [[ "$config_path" =~ ^([0-9a-f]{64})\.json$ ]]; then
    config_sha="${BASH_REMATCH[1]}"
  elif [[ "$config_path" =~ ^blobs/sha256/([0-9a-f]{64})$ ]]; then
    config_sha="${BASH_REMATCH[1]}"
  else
    die "docker save manifest contains an invalid Config path"
  fi
  printf '%s\n' "$config_sha"
}

portable_image_id() {
  local image_ref="${1:?image reference required}"
  local manifest_json config_sha
  manifest_json="$(docker save "$image_ref" | tar -xOf - manifest.json)" ||
    die "could not read docker save manifest for $image_ref"
  config_sha="$(config_digest_from_manifest "$manifest_json")"
  printf 'sha256:%s\n' "$config_sha"
}

archive_image_id() {
  local archive="${1:?image archive required}"
  local expected_tag="${2:?archive tag required}"
  local manifest_json config_path config_sha actual_sha
  zstd -q --test "$archive" || die "invalid compressed image archive: $archive"
  manifest_json="$(zstd -q -dc "$archive" | tar -xOf - manifest.json)" ||
    die "image archive has no readable docker manifest: $archive"
  config_path="$(printf '%s' "$manifest_json" | jq -er --arg tag "$expected_tag" '
    if type == "array" and length == 1 and
       (.[0] | type == "object") and
       (.[0].Config | type == "string") and
       (.[0].RepoTags == [$tag]) and
       (.[0].Layers | type == "array" and length > 0 and all(.[]; type == "string"))
    then .[0].Config
    else error("archive must contain exactly the disposable release tag")
    end
  ')" || die "image archive manifest/tag contract failed: $archive"
  config_sha="$(config_digest_from_manifest "$manifest_json" "$expected_tag")"
  actual_sha="$(zstd -q -dc "$archive" | tar -xOf - "$config_path" | sha256sum | awk '{print $1}')" ||
    die "image archive Config blob is unreadable: $archive"
  [[ "$actual_sha" == "$config_sha" ]] || die "image archive Config blob digest mismatch: $archive"
  printf 'sha256:%s\n' "$config_sha"
}

bundle_manifest() {
  local root="${1:?release root required}"
  (
    cd "$root"
    find admin public-assets ops images -type f -print0 | sort -z |
      while IFS= read -r -d '' path; do
        printf '%s  %s\n' "$(sha256sum -- "$path" | awk '{print $1}')" "$path"
      done
  )
}

for required_path in \
  admin/index.html \
  public-assets/.vite/manifest.json \
  release.json \
  bundle-manifest.json \
  images/portfolio-api.oci.tar.zst \
  images/postgres-17.oci.tar.zst; do
  [[ -f "$release_dir/$required_path" ]] || die "required file missing: $required_path"
done

for required_path in admin/assets public-assets/assets ops/deploy images; do
  [[ -d "$release_dir/$required_path" ]] || die "required directory missing: $required_path"
done

actual_roots="$(find "$release_dir" -mindepth 1 -maxdepth 1 -printf '%f\n' | sort)"
expected_roots="$(printf '%s\n' admin bundle-manifest.json images ops public-assets release.json | sort)"
[[ "$actual_roots" == "$expected_roots" ]] || die "release root contains missing or unexpected entries"

if find "$release_dir" -type l -print -quit | grep -q .; then
  die "release tree must not contain symbolic links"
fi
if find "$release_dir" \! -type d \! -type f -print -quit | grep -q .; then
  die "release tree contains a non-regular entry"
fi

jq -e --arg id "$release_id" '
  (keys | sort) == ([
    "adminTreeSha256", "apiImageArchiveSha256", "apiImageId", "apiImageRepoDigest",
    "apiImageTag", "buildInputs", "buildTimeUtc", "bundlePayloadSha256", "gitCommit",
    "jarSha256", "manifestSha256", "opsTreeSha256", "postgresImageArchiveSha256",
    "postgresImageId", "postgresImageRef", "postgresImageTag", "publicTreeSha256", "releaseId",
    "sourceContinuityRef"
  ] | sort) and
  .releaseId == $id and
  (.gitCommit | type == "string" and test("^[0-9a-f]{40}$")) and
  (.manifestSha256 | type == "string" and test("^[0-9a-f]{64}$")) and
  .releaseId == ((.gitCommit[0:12]) + "-" + (.manifestSha256[0:12])) and
  .sourceContinuityRef == ("refs/tags/portfolio-release/" + $id) and
  .apiImageTag == ("portfolio-api:" + $id) and
  (.apiImageId | type == "string" and test("^sha256:[0-9a-f]{64}$")) and
  ((.apiImageRepoDigest == null) or
    (.apiImageRepoDigest | type == "string" and test("^[^[:space:]@]+@sha256:[0-9a-f]{64}$"))) and
  (.postgresImageRef | type == "string" and test("^postgres:17-bookworm@sha256:[0-9a-f]{64}$")) and
  .postgresImageTag == ("portfolio-postgres-17:" + $id) and
  (.postgresImageId | type == "string" and test("^sha256:[0-9a-f]{64}$")) and
  (.buildTimeUtc | type == "string" and test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")) and
  (.buildInputs | type == "object") and
  (.buildInputs | keys | sort) == ([
    "javaBuildImageRef", "javaRuntimeImageRef", "nodeImageRef", "sourceDateEpoch",
    "targetPlatform", "ubuntuAptSnapshot"
  ] | sort) and
  (.buildInputs.nodeImageRef | test("^node:22\\.18\\.0-bookworm-slim@sha256:[0-9a-f]{64}$")) and
  (.buildInputs.javaBuildImageRef | test("^eclipse-temurin:17-jdk-jammy@sha256:[0-9a-f]{64}$")) and
  (.buildInputs.javaRuntimeImageRef | test("^eclipse-temurin:17-jre-jammy@sha256:[0-9a-f]{64}$")) and
  (.buildInputs.ubuntuAptSnapshot | test("^20[0-9]{6}T[0-9]{6}Z$")) and
  .buildInputs.targetPlatform == "linux/amd64" and
  (.buildInputs.sourceDateEpoch | type == "number" and . > 0 and floor == .) and
  ([.jarSha256, .adminTreeSha256, .publicTreeSha256, .opsTreeSha256,
    .apiImageArchiveSha256, .postgresImageArchiveSha256, .bundlePayloadSha256]
   | all(type == "string" and test("^[0-9a-f]{64}$")))
' "$release_dir/release.json" >/dev/null || die "release.json violates the release identity contract"

expected_build_time="$(date -u --date="@$(jq -r '.buildInputs.sourceDateEpoch' "$release_dir/release.json")" '+%Y-%m-%dT%H:%M:%SZ')"
[[ "$(jq -r '.buildTimeUtc' "$release_dir/release.json")" == "$expected_build_time" ]] ||
  die "buildTimeUtc is not derived from sourceDateEpoch"

expected_api_image_id="$(jq -r '.apiImageId' "$release_dir/release.json")"
api_image_tag="$(jq -r '.apiImageTag' "$release_dir/release.json")"
actual_api_image_id="$(portable_image_id "$api_image_tag")"
[[ "$actual_api_image_id" == "$expected_api_image_id" ]] || die "API image ID mismatch"

postgres_image_tag="$(jq -r '.postgresImageTag' "$release_dir/release.json")"
expected_postgres_image_id="$(jq -r '.postgresImageId' "$release_dir/release.json")"
actual_postgres_image_id="$(portable_image_id "$postgres_image_tag")"
[[ "$actual_postgres_image_id" == "$expected_postgres_image_id" ]] || die "PostgreSQL image ID mismatch"

container_id=""
jar_path="$(mktemp)"
manifest_tmp=""
cleanup() {
  if [[ -n "$container_id" ]]; then
    docker rm -f "$container_id" >/dev/null 2>&1 || true
  fi
  rm -f -- "$jar_path"
  if [[ -n "$manifest_tmp" ]]; then
    rm -f -- "$manifest_tmp"
  fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

container_id="$(docker create "$api_image_tag")"
docker cp "$container_id:/app/portfolio-server.jar" "$jar_path"
actual_manifest="$(unzip -p "$jar_path" BOOT-INF/classes/public-assets/.vite/manifest.json | sha256sum | awk '{print $1}')"
expected_manifest="$(sha256sum -- "$release_dir/public-assets/.vite/manifest.json" | awk '{print $1}')"
[[ "$actual_manifest" == "$expected_manifest" ]] || die "JAR public manifest does not match release assets"

[[ "$(sha256sum -- "$jar_path" | awk '{print $1}')" == "$(jq -r '.jarSha256' "$release_dir/release.json")" ]] ||
  die "API JAR hash mismatch"
[[ "$(canonical_tree_sha "$release_dir/admin")" == "$(jq -r '.adminTreeSha256' "$release_dir/release.json")" ]] ||
  die "admin tree hash mismatch"
[[ "$(canonical_tree_sha "$release_dir/public-assets")" == "$(jq -r '.publicTreeSha256' "$release_dir/release.json")" ]] ||
  die "public tree hash mismatch"
[[ "$(canonical_tree_sha "$release_dir/ops")" == "$(jq -r '.opsTreeSha256' "$release_dir/release.json")" ]] ||
  die "operations tree hash mismatch"
[[ "$(sha256sum -- "$release_dir/images/portfolio-api.oci.tar.zst" | awk '{print $1}')" == "$(jq -r '.apiImageArchiveSha256' "$release_dir/release.json")" ]] ||
  die "API image archive hash mismatch"
[[ "$(sha256sum -- "$release_dir/images/postgres-17.oci.tar.zst" | awk '{print $1}')" == "$(jq -r '.postgresImageArchiveSha256' "$release_dir/release.json")" ]] ||
  die "PostgreSQL image archive hash mismatch"
[[ "$(archive_image_id "$release_dir/images/portfolio-api.oci.tar.zst" "portfolio-api-archive:$release_id")" == "$expected_api_image_id" ]] ||
  die "API archive portable Config digest mismatch"
[[ "$(archive_image_id "$release_dir/images/postgres-17.oci.tar.zst" "portfolio-postgres-17-archive:$release_id")" == "$expected_postgres_image_id" ]] ||
  die "PostgreSQL archive portable Config digest mismatch"

payload_sha="$(jq -Sc '{releaseId,gitCommit,manifestSha256,sourceContinuityRef,apiImageTag,
  apiImageId,apiImageRepoDigest,postgresImageRef,postgresImageTag,postgresImageId,
  jarSha256,adminTreeSha256,
  publicTreeSha256,opsTreeSha256,apiImageArchiveSha256,postgresImageArchiveSha256,
  buildInputs}' \
  "$release_dir/release.json" | sha256sum | awk '{print $1}')"
[[ "$payload_sha" == "$(jq -r '.bundlePayloadSha256' "$release_dir/release.json")" ]] ||
  die "bundle payload identity mismatch"

manifest_tmp="$(mktemp)"
bundle_manifest "$release_dir" >"$manifest_tmp"
cmp -s -- "$manifest_tmp" "$release_dir/bundle-manifest.json" || {
  rm -f -- "$manifest_tmp"
  die "bundle-manifest.json does not exactly cover the immutable bundle files"
}
rm -f -- "$manifest_tmp"
manifest_tmp=""
(cd "$release_dir" && sha256sum --check --strict bundle-manifest.json >/dev/null) ||
  die "bundle manifest verification failed"

printf 'PASS\n'
