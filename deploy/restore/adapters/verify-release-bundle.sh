#!/usr/bin/env bash
set -euo pipefail
set +x
umask 077

SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=deploy/restore/adapters/adapter-lib.sh
source "$SCRIPT_DIRECTORY/adapter-lib.sh"
readonly TAR_VALIDATOR="$SCRIPT_DIRECTORY/../../scripts/validate-bundle-tar.py"
readonly COMPLIANCE_VALIDATOR="${RESTORE_COMPLIANCE_VALIDATOR:-$SCRIPT_DIRECTORY/../../scripts/verify-compliance-tree.py}"
readonly IMAGE_LOCK_LIB="$SCRIPT_DIRECTORY/../../scripts/image-lock-lib.sh"

WORK_DIRECTORY=''
cleanup() {
  [[ -z "$WORK_DIRECTORY" || ! -d "$WORK_DIRECTORY" ]] || rm -rf -- "$WORK_DIRECTORY"
}
on_signal() {
  local status="$1"
  trap - HUP INT TERM
  exit "$status"
}
trap cleanup EXIT
trap 'on_signal 129' HUP
trap 'on_signal 130' INT
trap 'on_signal 143' TERM

canonical_tree_sha() {
  local root="$1"
  (
    cd "$root"
    find . -type f -print0 | sort -z |
      while IFS= read -r -d '' path; do
        printf '%s\0%s\0' "${path#./}" "$(sha256sum -- "$path" | awk '{print $1}')"
      done
  ) | sha256sum | awk '{print $1}'
}

bundle_manifest() {
  local root="$1"
  (
    cd "$root"
    find admin public-assets ops images compliance -type f -print0 | sort -z |
      while IFS= read -r -d '' path; do
        printf '%s  %s\n' "$(sha256sum -- "$path" | awk '{print $1}')" "$path"
      done
  )
}

archive_image_id() {
  local archive="$1"
  local expected_tag="$2"
  local manifest config_path config_sha actual_sha
  manifest="$(zstd -q -dc "$archive" | tar -xOf - manifest.json)" ||
    adapter_fail 'image archive has no readable docker-save manifest'
  config_path="$(printf '%s' "$manifest" | jq -er --arg tag "$expected_tag" '
    if type == "array" and length == 1 and
       (.[0] | type == "object") and
       (.[0].RepoTags == [$tag]) and
       (.[0].Config | type == "string") and
       (.[0].Layers | type == "array" and length > 0 and all(.[]; type == "string"))
    then .[0].Config else error("invalid docker-save manifest") end
  ')" || adapter_fail 'image archive must contain exactly its release-local archive tag'
  if [[ "$config_path" =~ ^([0-9a-f]{64})\.json$ ]]; then
    config_sha="${BASH_REMATCH[1]}"
  elif [[ "$config_path" =~ ^blobs/sha256/([0-9a-f]{64})$ ]]; then
    config_sha="${BASH_REMATCH[1]}"
  else
    adapter_fail 'image archive Config path is invalid'
  fi
  actual_sha="$(zstd -q -dc "$archive" | tar -xOf - "$config_path" | sha256sum | awk '{print $1}')" ||
    adapter_fail 'image archive Config blob is unreadable'
  [[ "$actual_sha" == "$config_sha" ]] || adapter_fail 'image archive Config digest does not match its bytes'
  printf 'sha256:%s\n' "$config_sha"
}

portable_loaded_image_id() {
  local image="$1"
  local manifest config_path config_sha
  manifest="$("$RESTORE_DOCKER_COMMAND" save "$image" | tar -xOf - manifest.json)" ||
    adapter_fail 'loaded image cannot be exported for portable ID verification'
  config_path="$(printf '%s' "$manifest" | jq -er '
    if type == "array" and length == 1 and
       (.[0].Config | type == "string") and
       (.[0].Layers | type == "array" and length > 0)
    then .[0].Config else error("invalid loaded image manifest") end
  ')" || adapter_fail 'loaded image docker-save manifest is invalid'
  if [[ "$config_path" =~ ^([0-9a-f]{64})\.json$ ]]; then
    config_sha="${BASH_REMATCH[1]}"
  elif [[ "$config_path" =~ ^blobs/sha256/([0-9a-f]{64})$ ]]; then
    config_sha="${BASH_REMATCH[1]}"
  else
    adapter_fail 'loaded image Config path is invalid'
  fi
  printf 'sha256:%s\n' "$config_sha"
}

validate_release_json() {
  local release="$1"
  local release_id="$2"
  jq -e --arg id "$release_id" '
    (keys | sort) == ([
      "adminTreeSha256", "apiImageArchiveSha256", "apiImageId", "apiImageRepoDigest",
      "apiImageTag", "buildInputs", "buildTimeUtc", "bundlePayloadSha256", "gitCommit",
      "complianceTreeSha256", "jarSha256", "manifestSha256", "opsTreeSha256", "postgresImageArchiveSha256",
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
    (.buildInputs | keys | sort) == ([
      "javaBuildImageRef", "javaRuntimeImageRef", "nodeImageRef", "playwrightImageRef", "sourceDateEpoch",
      "targetPlatform", "ubuntuAptSnapshot"
    ] | sort) and
    (.buildInputs.nodeImageRef | test("^node:22\\.18\\.0-bookworm-slim@sha256:[0-9a-f]{64}$")) and
    (.buildInputs.playwrightImageRef | test("^mcr\\.microsoft\\.com/playwright:v1\\.58\\.2-noble@sha256:[0-9a-f]{64}$")) and
    (.buildInputs.javaBuildImageRef | test("^eclipse-temurin:17-jdk-jammy@sha256:[0-9a-f]{64}$")) and
    (.buildInputs.javaRuntimeImageRef | test("^eclipse-temurin:17-jre-jammy@sha256:[0-9a-f]{64}$")) and
    (.buildInputs.ubuntuAptSnapshot | test("^20[0-9]{6}T[0-9]{6}Z$")) and
    .buildInputs.targetPlatform == "linux/amd64" and
    (.buildInputs.sourceDateEpoch | type == "number" and . > 0 and floor == .) and
    ([.jarSha256,.adminTreeSha256,.publicTreeSha256,.opsTreeSha256,.complianceTreeSha256,
      .apiImageArchiveSha256,.postgresImageArchiveSha256,.bundlePayloadSha256] |
      all(type == "string" and test("^[0-9a-f]{64}$")))
  ' "$release" >/dev/null || adapter_fail 'release.json violates the complete release identity contract'
}

load_exact_archive_image() {
  local archive="$1"
  local tag="$2"
  local expected_id="$3"
  local actual_id
  if "$RESTORE_DOCKER_COMMAND" image inspect "$tag" >/dev/null 2>&1; then
    actual_id="$(portable_loaded_image_id "$tag")"
    [[ "$actual_id" == "$expected_id" ]] ||
      adapter_fail 'existing release-local archive tag points to different content'
  else
    zstd -q -dc "$archive" | "$RESTORE_DOCKER_COMMAND" load >/dev/null ||
      adapter_fail 'offline image load failed'
  fi
  actual_id="$(portable_loaded_image_id "$tag")"
  [[ "$actual_id" == "$expected_id" ]] || adapter_fail 'loaded archive image ID differs from release.json'
}

main() {
  local release_id='' set_manifest='' verifier_config='' output=''
  while (($#)); do
    case "$1" in
      --release-id) release_id="$2"; shift 2 ;;
      --set-manifest) set_manifest="$2"; shift 2 ;;
      --verifier-config) verifier_config="$2"; shift 2 ;;
      --output) output="$2"; shift 2 ;;
      *) adapter_fail 'usage: verify-release-bundle.sh --release-id ID --set-manifest FILE --verifier-config FILE --output DIR' ;;
    esac
  done
  adapter_require_drill_context
  adapter_is_release_id "$release_id" || adapter_fail 'release ID is invalid'
  [[ "$set_manifest" == "$RESTORE_ROOT/set/set-manifest.json" && -f "$set_manifest" && ! -L "$set_manifest" ]] ||
    adapter_fail 'set manifest is outside the acquired drill set'
  jq -e --arg id "$release_id" '.releaseId == $id' "$set_manifest" >/dev/null ||
    adapter_fail 'set manifest does not select this release'
  [[ "$output" == "$RESTORE_ROOT/release" && ! -e "$output" && ! -L "$output" ]] ||
    adapter_fail 'release output must be a new drill-root release directory'
  adapter_require_private_file "$verifier_config" 'release verifier rclone config'
  adapter_resolve_command RESTORE_RCLONE_COMMAND rclone
  adapter_resolve_command RESTORE_DOCKER_COMMAND docker
  for command_name in jq python3 sha256sum stat tar zstd; do
    command -v "$command_name" >/dev/null 2>&1 || adapter_fail "$command_name is required"
  done
  [[ -x "$TAR_VALIDATOR" ]] || adapter_fail 'tracked release tar validator is unavailable'
  [[ "$COMPLIANCE_VALIDATOR" == /* && -f "$COMPLIANCE_VALIDATOR" && ! -L "$COMPLIANCE_VALIDATOR" ]] ||
    adapter_fail 'tracked release compliance verifier is unavailable'
  adapter_require_value RESTORE_RELEASE_REMOTE
  adapter_parse_remote "$RESTORE_RELEASE_REMOTE" RESTORE_RELEASE_REMOTE

  WORK_DIRECTORY="$(mktemp -d -- "$RESTORE_ROOT/work/release-verify.XXXXXX")"
  chmod 0700 "$WORK_DIRECTORY"
  local archive="$WORK_DIRECTORY/portfolio-$release_id.tar.zst"
  local envelope="$archive.envelope.json"
  adapter_rclone_copyto "$verifier_config" \
    "${RESTORE_RELEASE_REMOTE%/}/portfolio-$release_id.tar.zst" "$archive" ||
    adapter_fail 'release verifier could not acquire the detached bundle'
  adapter_rclone_copyto "$verifier_config" \
    "${RESTORE_RELEASE_REMOTE%/}/portfolio-$release_id.tar.zst.envelope.json" "$envelope" ||
    adapter_fail 'release verifier could not acquire the detached envelope'
  jq -e --arg id "$release_id" '
    (keys | sort) == (["archiveBytes","archiveSha256","releaseId","releaseJsonSha256"] | sort) and
    .releaseId == $id and
    (.archiveBytes | type == "number" and . > 0 and floor == .) and
    (.archiveSha256 | type == "string" and test("^[0-9a-f]{64}$")) and
    (.releaseJsonSha256 | type == "string" and test("^[0-9a-f]{64}$"))
  ' "$envelope" >/dev/null || adapter_fail 'release detached envelope is invalid'
  [[ "$(stat -Lc '%s' -- "$archive")" == "$(jq -r '.archiveBytes' "$envelope")" &&
     "$(sha256sum -- "$archive" | awk '{print $1}')" == "$(jq -r '.archiveSha256' "$envelope")" ]] ||
    adapter_fail 'release bundle bytes do not match the detached envelope'
  zstd -q --test "$archive" || adapter_fail 'release bundle compression is corrupt'
  local tar_path="$WORK_DIRECTORY/release.tar"
  zstd -q -dc "$archive" >"$tar_path" || adapter_fail 'release bundle decompression failed'
  "$TAR_VALIDATOR" --release-id "$release_id" "$tar_path" >/dev/null ||
    adapter_fail 'release bundle tar contract failed'
  local extraction="$WORK_DIRECTORY/extract"
  mkdir -m 0700 -- "$extraction"
  tar -xf "$tar_path" --directory "$extraction" --no-same-owner --no-same-permissions ||
    adapter_fail 'validated release bundle extraction failed'
  local release="$extraction/portfolio-$release_id"
  [[ -d "$release" && ! -L "$release" ]] || adapter_fail 'release root is missing after extraction'
  [[ "$(sha256sum -- "$release/release.json" | awk '{print $1}')" == \
     "$(jq -r '.releaseJsonSha256' "$envelope")" ]] ||
    adapter_fail 'release.json does not match the independently transferred envelope'
  validate_release_json "$release/release.json" "$release_id"
  local required_path
  for required_path in \
    admin/index.html \
    public-assets/.vite/manifest.json \
    public-assets/favicon.svg \
    compliance/SHA256SUMS \
    compliance/THIRD_PARTY_NOTICES.txt \
    compliance/ASSET_PROVENANCE.md \
    compliance/licenses/frontend/manifest.json \
    compliance/licenses/admin-web/manifest.json \
    compliance/licenses/backend/manifest.json \
    compliance/licenses/cos-prune-runtime/manifest.json \
    compliance/licenses/api-image/manifest.json \
    compliance/licenses/postgres-image/manifest.json \
    compliance/sbom/api-image.cdx.json \
    compliance/sbom/postgres-image.cdx.json \
    bundle-manifest.json \
    images/portfolio-api.oci.tar.zst \
    images/postgres-17.oci.tar.zst \
    ops/deploy/image-lock.env \
    ops/deploy/docker-compose.prod.yml \
    ops/deploy/backup/backup-database.sh \
    ops/deploy/backup/backup-dispatch.sh \
    ops/deploy/backup/backup-media.sh \
    ops/deploy/backup/backup-set.sh \
    ops/deploy/backup/cos-prune-guard.py \
    ops/deploy/backup/export-media.sql \
    ops/deploy/backup/install-cos-prune-runtime.sh \
    ops/deploy/backup/lib.sh \
    ops/deploy/backup/notify-failure.sh \
    ops/deploy/backup/postgres-client.sh \
    ops/deploy/backup/prune-guard.example.sh \
    ops/deploy/backup/prune-remote.sh \
    ops/deploy/backup/record-maintenance.sql \
    ops/deploy/backup/requirements-cos-prune.in \
    ops/deploy/backup/requirements-cos-prune.txt \
    ops/deploy/backup/validate-media-tar.py \
    ops/deploy/backup/verify-artifact.sh \
    ops/deploy/scripts/deploy-release.sh \
    ops/deploy/scripts/install-backup-units.sh \
    ops/deploy/scripts/image-lock-lib.sh \
    ops/deploy/scripts/preflight.sh \
    ops/deploy/scripts/prune-releases.sh \
    ops/deploy/scripts/rollback-release.sh \
    ops/deploy/scripts/smoke.sh \
    ops/deploy/scripts/verify-cos-staging-lifecycle.sh \
    ops/deploy/scripts/verify-compliance-tree.py \
    ops/deploy/restore/restore-drill.sh \
    ops/deploy/restore/install-reminder.sh \
    ops/deploy/restore/record-drill-result.sh \
    ops/deploy/restore/record-drill-result.sql \
    ops/deploy/restore/resolve-media-closure.sql \
    ops/deploy/restore/verify-restored-media.sh \
    ops/deploy/restore/adapters/acquire-backup-set.sh \
    ops/deploy/restore/adapters/adapter-lib.sh \
    ops/deploy/restore/adapters/apply-media-mapping.sh \
    ops/deploy/restore/adapters/cleanup-drill.sh \
    ops/deploy/restore/adapters/cos-transfer.sh \
    ops/deploy/restore/adapters/http-fetch.sh \
    ops/deploy/restore/adapters/report-transfer.sh \
    ops/deploy/restore/adapters/revoke-drill-credentials.sh \
    ops/deploy/restore/adapters/start-routes.sh \
    ops/deploy/restore/adapters/verify-content-flow.sh \
    ops/deploy/restore/adapters/verify-release-bundle.sh \
    ops/deploy/systemd/portfolio-backup.service \
    ops/deploy/systemd/portfolio-backup.timer \
    ops/deploy/systemd/portfolio-backup-prune.service \
    ops/deploy/systemd/portfolio-backup-prune-readiness.service \
    ops/deploy/systemd/portfolio-backup-prune.timer \
    ops/deploy/systemd/portfolio-restore-reminder.service \
    ops/deploy/systemd/portfolio-restore-reminder.timer \
    ops/deploy/tmpfiles.d/portfolio.conf \
    ops/deploy/scripts/switch-journal.sh \
    ops/docs/operations/production-runbook.md \
    ops/docs/operations/backup-recovery.md \
    ops/docs/operations/release-evidence-template.md
  do
    [[ -f "$release/$required_path" && ! -L "$release/$required_path" ]] ||
      adapter_fail "release is missing $required_path"
  done
  [[ -f "$IMAGE_LOCK_LIB" && ! -L "$IMAGE_LOCK_LIB" ]] ||
    adapter_fail 'trusted image-lock parser is missing or linked'
  # Never execute the release-local parser before the release tree is trusted.
  # shellcheck source=deploy/scripts/image-lock-lib.sh
  source "$IMAGE_LOCK_LIB"
  # Accessed through nameref helpers from image-lock-lib.sh.
  # shellcheck disable=SC2034
  local -A release_locked_images=()
  portfolio_load_image_lock "$release/ops/deploy/image-lock.env" release_locked_images ||
    adapter_fail 'release image lock is invalid'
  portfolio_require_release_image_lock "$release/release.json" release_locked_images ||
    adapter_fail 'release build inputs differ from the source-pinned image lock'
  local executable_path
  for executable_path in \
    ops/deploy/backup/backup-database.sh \
    ops/deploy/backup/backup-dispatch.sh \
    ops/deploy/backup/backup-media.sh \
    ops/deploy/backup/backup-set.sh \
    ops/deploy/backup/install-cos-prune-runtime.sh \
    ops/deploy/backup/lib.sh \
    ops/deploy/backup/notify-failure.sh \
    ops/deploy/backup/postgres-client.sh \
    ops/deploy/backup/prune-guard.example.sh \
    ops/deploy/backup/prune-remote.sh \
    ops/deploy/backup/validate-media-tar.py \
    ops/deploy/backup/verify-artifact.sh \
    ops/deploy/scripts/deploy-release.sh \
    ops/deploy/scripts/install-backup-units.sh \
    ops/deploy/scripts/preflight.sh \
    ops/deploy/scripts/prune-releases.sh \
    ops/deploy/scripts/rollback-release.sh \
    ops/deploy/scripts/smoke.sh \
    ops/deploy/scripts/verify-cos-staging-lifecycle.sh \
    ops/deploy/restore/restore-drill.sh \
    ops/deploy/restore/install-reminder.sh \
    ops/deploy/restore/record-drill-result.sh \
    ops/deploy/restore/verify-restored-media.sh \
    ops/deploy/restore/adapters/acquire-backup-set.sh \
    ops/deploy/restore/adapters/adapter-lib.sh \
    ops/deploy/restore/adapters/apply-media-mapping.sh \
    ops/deploy/restore/adapters/cleanup-drill.sh \
    ops/deploy/restore/adapters/cos-transfer.sh \
    ops/deploy/restore/adapters/http-fetch.sh \
    ops/deploy/restore/adapters/report-transfer.sh \
    ops/deploy/restore/adapters/revoke-drill-credentials.sh \
    ops/deploy/restore/adapters/start-routes.sh \
    ops/deploy/restore/adapters/verify-content-flow.sh \
    ops/deploy/restore/adapters/verify-release-bundle.sh
  do
    [[ "$(stat -Lc '%a' -- "$release/$executable_path")" == 700 ]] ||
      adapter_fail "release executable mode is not 0700 after extraction: $executable_path"
  done
  local private_path
  for private_path in \
    ops/deploy/image-lock.env \
    ops/deploy/docker-compose.prod.yml \
    ops/deploy/scripts/image-lock-lib.sh \
    ops/deploy/scripts/switch-journal.sh \
    ops/deploy/backup/cos-prune-guard.py \
    ops/deploy/backup/export-media.sql \
    ops/deploy/backup/record-maintenance.sql \
    ops/deploy/backup/requirements-cos-prune.in \
    ops/deploy/backup/requirements-cos-prune.txt \
    ops/deploy/restore/docker-compose.restore.yml \
    ops/deploy/restore/record-drill-result.sql \
    ops/deploy/restore/resolve-media-closure.sql \
    ops/deploy/systemd/portfolio-backup.service \
    ops/deploy/systemd/portfolio-backup.timer \
    ops/deploy/systemd/portfolio-backup-prune.service \
    ops/deploy/systemd/portfolio-backup-prune-readiness.service \
    ops/deploy/systemd/portfolio-backup-prune.timer \
    ops/deploy/systemd/portfolio-restore-reminder.service \
    ops/deploy/systemd/portfolio-restore-reminder.timer \
    ops/deploy/tmpfiles.d/portfolio.conf \
    ops/docs/operations/production-runbook.md \
    ops/docs/operations/backup-recovery.md \
    ops/docs/operations/release-evidence-template.md
  do
    [[ "$(stat -Lc '%a' -- "$release/$private_path")" == 600 ]] ||
      adapter_fail "release private data mode is not 0600 after extraction: $private_path"
  done
  [[ "$(sha256sum -- "$release/public-assets/.vite/manifest.json" | awk '{print $1}')" == \
     "$(jq -r '.manifestSha256' "$release/release.json")" ]] ||
    adapter_fail 'public Vite manifest checksum differs from release.json'
  local source_epoch expected_build_time payload_sha
  source_epoch="$(jq -r '.buildInputs.sourceDateEpoch' "$release/release.json")"
  expected_build_time="$(date -u --date="@$source_epoch" '+%Y-%m-%dT%H:%M:%SZ')"
  [[ "$(jq -r '.buildTimeUtc' "$release/release.json")" == "$expected_build_time" ]] ||
    adapter_fail 'release buildTimeUtc is not derived from sourceDateEpoch'
  payload_sha="$(jq -Sc '{releaseId,gitCommit,manifestSha256,sourceContinuityRef,apiImageTag,
    apiImageId,apiImageRepoDigest,postgresImageRef,postgresImageTag,postgresImageId,
    jarSha256,adminTreeSha256,publicTreeSha256,opsTreeSha256,complianceTreeSha256,apiImageArchiveSha256,
    postgresImageArchiveSha256,buildInputs}' "$release/release.json" | sha256sum | awk '{print $1}')"
  [[ "$payload_sha" == "$(jq -r '.bundlePayloadSha256' "$release/release.json")" ]] ||
    adapter_fail 'release bundle payload identity is invalid'
  local actual_roots expected_roots
  actual_roots="$(find "$release" -mindepth 1 -maxdepth 1 -printf '%f\n' | sort)"
  expected_roots="$(printf '%s\n' admin bundle-manifest.json compliance images ops public-assets release.json | sort)"
  [[ "$actual_roots" == "$expected_roots" ]] || adapter_fail 'release root contains missing or unexpected entries'
  [[ -z "$(find "$release" -type l -print -quit)" &&
     -z "$(find "$release" ! -type d ! -type f -print -quit)" ]] ||
    adapter_fail 'release tree contains a link or special entry'
  if find "$release/ops" "$release/images" "$release/compliance" -type d \
      ! -perm 0700 -print -quit | grep -q .; then
    adapter_fail 'release private directory mode is not exactly 0700 after extraction'
  fi
  local private_file private_relative private_mode
  while IFS= read -r -d '' private_file; do
    private_relative="${private_file#"$release"/}"
    private_mode="$(stat -Lc '%a' -- "$private_file")"
    case "$private_relative:$private_mode" in
      ops/*:600|ops/*:700|images/*:600|compliance/*:600) ;;
      *) adapter_fail "release private file mode is unsafe after extraction: $private_relative" ;;
    esac
  done < <(find "$release/ops" "$release/images" "$release/compliance" -type f -print0)
  local recomputed="$WORK_DIRECTORY/bundle-manifest.json"
  bundle_manifest "$release" >"$recomputed"
  cmp -s -- "$recomputed" "$release/bundle-manifest.json" ||
    adapter_fail 'bundle manifest does not exactly cover the immutable release files'
  (cd "$release" && sha256sum --check --strict bundle-manifest.json >/dev/null) ||
    adapter_fail 'bundle manifest checksum verification failed'
  [[ "$(canonical_tree_sha "$release/admin")" == "$(jq -r '.adminTreeSha256' "$release/release.json")" &&
     "$(canonical_tree_sha "$release/public-assets")" == "$(jq -r '.publicTreeSha256' "$release/release.json")" &&
     "$(canonical_tree_sha "$release/ops")" == "$(jq -r '.opsTreeSha256' "$release/release.json")" &&
     "$(canonical_tree_sha "$release/compliance")" == "$(jq -r '.complianceTreeSha256' "$release/release.json")" ]] ||
    adapter_fail 'release tree checksum differs from release.json'
  python3 "$COMPLIANCE_VALIDATOR" \
    --tree "$release/compliance" \
    --release-json "$release/release.json" ||
    adapter_fail 'release compliance closure failed independent verification'

  local api_archive="$release/images/portfolio-api.oci.tar.zst"
  local postgres_archive="$release/images/postgres-17.oci.tar.zst"
  [[ "$(sha256sum -- "$api_archive" | awk '{print $1}')" == "$(jq -r '.apiImageArchiveSha256' "$release/release.json")" &&
     "$(sha256sum -- "$postgres_archive" | awk '{print $1}')" == "$(jq -r '.postgresImageArchiveSha256' "$release/release.json")" ]] ||
    adapter_fail 'release image archive checksum differs from release.json'
  local api_tag="portfolio-api-archive:$release_id"
  local postgres_tag="portfolio-postgres-17-archive:$release_id"
  local api_id postgres_id
  api_id="$(archive_image_id "$api_archive" "$api_tag")"
  postgres_id="$(archive_image_id "$postgres_archive" "$postgres_tag")"
  [[ "$api_id" == "$(jq -r '.apiImageId' "$release/release.json")" &&
     "$postgres_id" == "$(jq -r '.postgresImageId' "$release/release.json")" ]] ||
    adapter_fail 'docker-save Config digest differs from release.json image ID'
  [[ "${RESTORE_API_IMAGE:-}" == "$api_tag" || "${RESTORE_API_IMAGE:-}" == "$api_id" ]] ||
    adapter_fail 'RESTORE_API_IMAGE must be the exact archive tag or exact verified Config ID'
  [[ "${RESTORE_POSTGRES_IMAGE:-}" == "$postgres_tag" || "${RESTORE_POSTGRES_IMAGE:-}" == "$postgres_id" ]] ||
    adapter_fail 'RESTORE_POSTGRES_IMAGE must be the exact archive tag or exact verified Config ID'
  load_exact_archive_image "$api_archive" "$api_tag" "$api_id"
  load_exact_archive_image "$postgres_archive" "$postgres_tag" "$postgres_id"

  # The bundle is intentionally transported owner-only.  Only after every
  # byte/tree/image identity check succeeds, expose the two immutable web
  # trees to the unprivileged Nginx worker.  Ops, image archives, and release
  # metadata retain their private extraction modes.
  chmod 0755 "$release" "$release/admin" "$release/public-assets"
  find "$release/admin" "$release/public-assets" -type d -exec chmod 0755 {} +
  find "$release/admin" "$release/public-assets" -type f -exec chmod 0644 {} +

  mv -- "$release" "$output"
  [[ -f "$output/release.json" && ! -L "$output/release.json" ]] ||
    adapter_fail 'complete release.json was not installed at the drill release root'
  printf '%s\n' "$output/release.json"
}

main "$@"
