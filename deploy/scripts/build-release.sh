#!/usr/bin/env bash
set -euo pipefail

export LC_ALL=C
umask 027

die() {
  printf 'build-release: %s\n' "$*" >&2
  exit 1
}

note() {
  printf 'build-release: %s\n' "$*" >&2
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"
}

for command_name in awk git realpath; do
  require_command "$command_name"
done

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
repo_root="$(realpath -e -- "$script_dir/../..")"
git_root="$(git -C "$repo_root" rev-parse --show-toplevel 2>/dev/null)" || die "repository root is not a Git worktree"
[[ "$(realpath -e -- "$git_root")" == "$repo_root" ]] || die "script must run from its owning repository"

git -C "$repo_root" diff --quiet --ignore-submodules -- || die "tracked worktree has unstaged changes"
git -C "$repo_root" diff --cached --quiet --ignore-submodules -- || die "tracked worktree has staged changes"

assert_no_untracked_files() {
  local untracked
  if IFS= read -r -d '' untracked < <(
    git -C "$repo_root" ls-files --others --exclude-standard -z
  ); then
    printf 'build-release: untracked non-ignored file is forbidden: %q\n' "$untracked" >&2
    die 'release source must be a complete clean commit'
  fi
}
assert_no_untracked_files

git_commit="$(git -C "$repo_root" rev-parse --verify 'HEAD^{commit}')"
[[ "$git_commit" =~ ^[0-9a-f]{40}$ ]] || die "HEAD did not resolve to a full commit"
git -C "$repo_root" merge-base --is-ancestor "$git_commit" HEAD || die "release commit is not reachable from HEAD"
if git -C "$repo_root" ls-files --stage | awk '$1 == "160000" { found=1 } END { exit !found }'; then
  die "Git submodules are not permitted in a release source tree"
fi

for command_name in cmp date docker find grep id install jq mktemp mv python3 rm sha256sum sort stat tar tr unzip zstd; do
  require_command "$command_name"
done

source_date_epoch="$(git -C "$repo_root" show -s --format='%ct' "$git_commit")"
[[ "$source_date_epoch" =~ ^[1-9][0-9]*$ ]] || die "release commit has an invalid epoch"
build_time_utc="$(date -u --date="@$source_date_epoch" '+%Y-%m-%dT%H:%M:%SZ')"
[[ "$build_time_utc" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] ||
  die "could not derive deterministic UTC build time from the release commit"

release_root_input="${PORTFOLIO_RELEASE_ROOT:-$repo_root/artifacts/releases}"
[[ "$release_root_input" != *$'\n'* && "$release_root_input" != *$'\r'* && "$release_root_input" != *,* ]] ||
  die "release root contains an unsupported character"
install -d -m 0750 -- "$release_root_input"
release_root="$(realpath -e -- "$release_root_input")"
[[ "$release_root" != "/" ]] || die "release root must not be the filesystem root"
if [[ "$release_root" == "/opt/portfolio/releases" || "$release_root" == /opt/portfolio/releases/* ]]; then
  [[ "${PORTFOLIO_APPROVE_ON_HOST_BUILD:-}" == "yes" ]] ||
    die "on-host /opt build requires PORTFOLIO_APPROVE_ON_HOST_BUILD=yes"
fi

release_parent="$(dirname -- "$release_root")"
release_basename="$(basename -- "$release_root")"
work_dir="$(mktemp -d -- "$release_parent/.${release_basename}.build-${git_commit:0:12}.XXXXXX")"
work_dir="$(realpath -e -- "$work_dir")"
case "$work_dir" in
  "$release_parent"/."$release_basename".build-"${git_commit:0:12}".*) ;;
  *) die "temporary build path escaped its reviewed parent" ;;
esac

source_dir="$work_dir/source"
export_dir="$work_dir/export"
staging_dir="$work_dir/release"
jar_copy="$work_dir/portfolio-server.jar"
install -d -m 0750 -- "$source_dir" "$export_dir" "$staging_dir"

declare -a cleanup_containers=()
declare -a cleanup_tags=()
cleanup() {
  local item
  for item in "${cleanup_containers[@]}"; do
    if [[ -n "$item" ]]; then
      docker rm -f "$item" >/dev/null 2>&1 || true
    fi
  done
  for item in "${cleanup_tags[@]}"; do
    if [[ -n "$item" ]]; then
      docker image rm "$item" >/dev/null 2>&1 || true
    fi
  done
  if [[ -n "${work_dir:-}" && -d "$work_dir" ]]; then
    case "$(realpath -e -- "$work_dir" 2>/dev/null || true)" in
      "$release_parent"/."$release_basename".build-"${git_commit:0:12}".*)
        rm -rf -- "$work_dir"
        ;;
      *)
        printf 'build-release: refusing to remove unexpected temporary path\n' >&2
        ;;
    esac
  fi
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

note "materializing tracked source at $git_commit"
git -C "$repo_root" archive --format=tar "$git_commit" | tar -xf - -C "$source_dir"
[[ -f "$source_dir/Dockerfile" && -f "$source_dir/deploy/image-lock.env" ]] ||
  die "release commit does not contain the release build files"

image_lock_lib="$source_dir/deploy/scripts/image-lock-lib.sh"
[[ -f "$image_lock_lib" && ! -L "$image_lock_lib" ]] || die 'tracked image-lock parser is missing'
# shellcheck source=deploy/scripts/image-lock-lib.sh
source "$image_lock_lib"
declare -A locked_images=()
portfolio_load_image_lock "$source_dir/deploy/image-lock.env" locked_images ||
  die 'reviewed image lock is invalid'

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

ubuntu_apt_snapshot="${locked_images[UBUNTU_APT_SNAPSHOT]}"
target_platform="${locked_images[TARGET_PLATFORM]}"
rebuild_release_json=""
if [[ -n "${PORTFOLIO_REBUILD_FROM_RELEASE_JSON:-}" ]]; then
  [[ -f "$PORTFOLIO_REBUILD_FROM_RELEASE_JSON" && ! -L "$PORTFOLIO_REBUILD_FROM_RELEASE_JSON" ]] ||
    die "rebuild release.json must be a regular non-symlink file"
  rebuild_release_json="$(realpath -e -- "$PORTFOLIO_REBUILD_FROM_RELEASE_JSON")"
  jq -e --arg commit "$git_commit" --arg snapshot "$ubuntu_apt_snapshot" \
    --arg platform "$target_platform" --argjson epoch "$source_date_epoch" '
    .gitCommit == $commit and
    .buildInputs.ubuntuAptSnapshot == $snapshot and
    .buildInputs.targetPlatform == $platform and
    .buildInputs.sourceDateEpoch == $epoch
  ' "$rebuild_release_json" >/dev/null ||
    die "recorded rebuild inputs do not match this commit and protected lock"
  node_image_ref="$(jq -r '.buildInputs.nodeImageRef' "$rebuild_release_json")"
  playwright_image_ref="$(jq -r '.buildInputs.playwrightImageRef' "$rebuild_release_json")"
  java_build_image_ref="$(jq -r '.buildInputs.javaBuildImageRef' "$rebuild_release_json")"
  java_runtime_image_ref="$(jq -r '.buildInputs.javaRuntimeImageRef' "$rebuild_release_json")"
  postgres_image_ref="$(jq -r '.postgresImageRef' "$rebuild_release_json")"
  portfolio_require_locked_image "$node_image_ref" "${locked_images[NODE_IMAGE]}" ||
    die 'recorded Node rebuild input differs from the source-pinned digest'
  portfolio_require_locked_image "$playwright_image_ref" "${locked_images[PLAYWRIGHT_IMAGE]}" ||
    die 'recorded Playwright rebuild input differs from the source-pinned digest'
  portfolio_require_locked_image "$java_build_image_ref" "${locked_images[JAVA_BUILD_IMAGE]}" ||
    die 'recorded Java build input differs from the source-pinned digest'
  portfolio_require_locked_image "$java_runtime_image_ref" "${locked_images[JAVA_RUNTIME_IMAGE]}" ||
    die 'recorded Java runtime input differs from the source-pinned digest'
  portfolio_require_locked_image "$postgres_image_ref" "${locked_images[POSTGRES_IMAGE]}" ||
    die 'recorded PostgreSQL rebuild input differs from the source-pinned digest'
else
  node_image_ref="${locked_images[NODE_IMAGE]}"
  playwright_image_ref="${locked_images[PLAYWRIGHT_IMAGE]}"
  java_build_image_ref="${locked_images[JAVA_BUILD_IMAGE]}"
  java_runtime_image_ref="${locked_images[JAVA_RUNTIME_IMAGE]}"
  postgres_image_ref="${locked_images[POSTGRES_IMAGE]}"
fi
[[ "$node_image_ref" =~ ^node:22\.18\.0-bookworm-slim@sha256:[0-9a-f]{64}$ ]] ||
  die "reviewed Node image is not the exact Node 22.18.0 bookworm-slim reference"
[[ "$postgres_image_ref" =~ ^postgres:17-bookworm@sha256:[0-9a-f]{64}$ ]] ||
  die "reviewed PostgreSQL image is not the exact PostgreSQL 17 bookworm reference"
[[ "$playwright_image_ref" =~ ^mcr\.microsoft\.com/playwright:v1\.58\.2-noble@sha256:[0-9a-f]{64}$ ]] ||
  die "reviewed Playwright image is not the exact v1.58.2 noble reference"
[[ "$java_build_image_ref" =~ ^eclipse-temurin:17-jdk-jammy@sha256:[0-9a-f]{64}$ ]] ||
  die "reviewed Java build image is not the exact Temurin 17 JDK Jammy reference"
[[ "$java_runtime_image_ref" =~ ^eclipse-temurin:17-jre-jammy@sha256:[0-9a-f]{64}$ ]] ||
  die "reviewed Java runtime image is not the exact Temurin 17 JRE Jammy reference"
for immutable_ref in "$node_image_ref" "$playwright_image_ref" "$java_build_image_ref" "$java_runtime_image_ref" "$postgres_image_ref"; do
  note "pulling source-pinned immutable build input $immutable_ref"
  docker pull --platform "$target_platform" "$immutable_ref" >&2
done

docker_build_args=(
  --build-arg "NODE_IMAGE=$node_image_ref"
  --build-arg "PLAYWRIGHT_IMAGE=$playwright_image_ref"
  --build-arg "JAVA_BUILD_IMAGE=$java_build_image_ref"
  --build-arg "JAVA_RUNTIME_IMAGE=$java_runtime_image_ref"
  --build-arg "SOURCE_DATE_EPOCH=$source_date_epoch"
  --build-arg "UBUNTU_APT_SNAPSHOT=$ubuntu_apt_snapshot"
)

note "running public frontend release tests"
docker build --progress=plain --provenance=false --platform "$target_platform" \
  --target public-test "${docker_build_args[@]}" "$source_dir" >&2
note "running public browser release tests in the digest-pinned Playwright image"
docker build --progress=plain --provenance=false --platform "$target_platform" \
  --target public-e2e "${docker_build_args[@]}" "$source_dir" >&2
note "running administrator frontend release tests"
docker build --progress=plain --provenance=false --platform "$target_platform" \
  --target admin-test "${docker_build_args[@]}" "$source_dir" >&2

maven_verify_mode="${PORTFOLIO_MAVEN_VERIFY_MODE:-host}"
case "$maven_verify_mode" in
  host)
    require_command java
    [[ "$(java -XshowSettings:properties -version 2>&1 | awk -F= '/^[[:space:]]*java\.specification\.version[[:space:]]*=/{gsub(/[[:space:]]/, "", $2); print $2; exit}')" == 17 ]] ||
      die "approved host Maven verification requires Java 17"
    note "running Maven verification on the approved Java 17 host runner without exposing the Docker socket to a build container"
    (cd "$source_dir/backend-parent" && SOURCE_DATE_EPOCH="$source_date_epoch" \
      ./mvnw -B -Dproject.build.outputTimestamp="$source_date_epoch" verify) >&2
    ;;
  buildkit)
    note "running Maven verification through the server-test Docker target"
    docker build --progress=plain --provenance=false --platform "$target_platform" \
      --target server-test "${docker_build_args[@]}" "$source_dir" >&2
    ;;
  *)
    die "PORTFOLIO_MAVEN_VERIFY_MODE must be host or buildkit; Docker-socket build containers are forbidden"
    ;;
esac

note "exporting public and administrator release files"
docker build --progress=plain --provenance=false --platform "$target_platform" --target release-files \
  --output "type=local,dest=$export_dir" \
  "${docker_build_args[@]}" "$source_dir" >&2
[[ -f "$export_dir/admin/index.html" && -d "$export_dir/admin/assets" ]] ||
  die "administrator build did not produce the required files"
[[ -f "$export_dir/public-assets/.vite/manifest.json" && -d "$export_dir/public-assets/assets" ]] ||
  die "public build did not produce the required Vite manifest and assets"
mv -- "$export_dir/admin" "$staging_dir/admin"
mv -- "$export_dir/public-assets" "$staging_dir/public-assets"
install -d -m 0700 -- "$staging_dir/ops" "$staging_dir/images"

reject_operations_path() {
  local path="${1:?path required}"
  local base lower lower_base
  [[ "$path" != /* && "$path" != *\\* && "$path" != *$'\n'* && "$path" != *$'\r'* ]] ||
    die "unsafe tracked operations path"
  [[ "/$path/" != *'/../'* ]] || die "parent traversal in tracked operations path"
  base="${path##*/}"
  lower="$(printf '%s' "$path" | tr '[:upper:]' '[:lower:]')"
  lower_base="${base,,}"
  if [[ "$lower_base" == ".env" || "$lower_base" == .env.* ]]; then
    case "$lower_base" in
      *.example|*.sample|*.template) ;;
      *) die "tracked environment secret file is forbidden: $path" ;;
    esac
  fi
  case "$lower" in
    __pycache__|__pycache__/*|*/__pycache__|*/__pycache__/*)
      die "tracked Python cache directory is forbidden: $path"
      ;;
    *.py[cod])
      die "tracked Python bytecode cache is forbidden: $path"
      ;;
    */credentials/*|*/credential/*|*/secrets/*|*/secret/*|*/private/*)
      die "tracked credential path is forbidden: $path"
      ;;
    *.key|*.pem|*.crt|*.cer|*.p12|*.pfx|*.jks|*.keystore|*.kdbx|*.age|*.dump|*.backup|*.bak|*.db|*.sqlite|*.sqlite3|*.tar|*.tar.gz|*.tgz|*.zip|*.7z|*.zst|*.jpg|*.jpeg|*.png|*.gif|*.webp|*.avif|*.ico|*.pdf|*.docx|*.mp3|*.wav|*.mp4|*.webm|*.mov|*.avi)
      die "tracked secret, media, database, or backup material is forbidden: $path"
      ;;
  esac
}

note "assembling tracked no-secret operations continuity files"
while IFS= read -r -d '' path; do
  reject_operations_path "$path"
  [[ -f "$source_dir/$path" && ! -L "$source_dir/$path" ]] ||
    die "operations continuity input is not a regular file: $path"
  if grep -aE -q -- '-----BEGIN ([A-Z0-9 ]+ )?PRIVATE KEY-----|AKID[A-Za-z0-9]{13,}|gh[pousr]_[A-Za-z0-9_]{20,}' "$source_dir/$path"; then
    die "tracked operations file contains credential material: $path"
  fi
  mode="$(git -C "$repo_root" ls-tree "$git_commit" -- "$path" | awk '{print $1}')"
  case "$mode" in
    100644) output_mode=0600 ;;
    100755) output_mode=0700 ;;
    *) die "unsupported Git mode $mode for operations file: $path" ;;
  esac
  install -D -m "$output_mode" -- "$source_dir/$path" "$staging_dir/ops/$path"
done < <(git -C "$repo_root" ls-tree -r -z --name-only "$git_commit" -- deploy docs/operations README.md)
# `install -D` may create intermediate directories with implementation-defined
# defaults.  The release contract is intentionally exact: the complete
# operations tree stays root-only throughout build, package, and extraction.
find "$staging_dir/ops" -type d -exec chmod 0700 {} +
[[ -d "$staging_dir/ops/deploy" ]] || die "tracked deploy continuity tree is missing"
if ! git -C "$repo_root" cat-file -e "$git_commit:README.md" 2>/dev/null; then
  note "repository has no tracked root README.md; continuing with the complete present operations set"
fi

if find "$staging_dir" -type l -print -quit | grep -q .; then
  die "release staging tree contains a symbolic link"
fi
if find "$staging_dir" \! -type d \! -type f -print -quit | grep -q .; then
  die "release staging tree contains a non-regular entry"
fi
while IFS= read -r -d '' staged_path; do
  relative_path="${staged_path#"$staging_dir"/}"
  [[ "$relative_path" != *\\* && "$relative_path" != *$'\n'* && "$relative_path" != *$'\r'* ]] ||
    die "release output contains an unsupported path"
done < <(find "$staging_dir" -mindepth 1 -print0)

manifest_sha256="$(sha256sum -- "$staging_dir/public-assets/.vite/manifest.json" | awk '{print $1}')"
[[ "$manifest_sha256" =~ ^[0-9a-f]{64}$ ]] || die "could not hash public Vite manifest"
release_id="${git_commit:0:12}-${manifest_sha256:0:12}"
api_image_tag="portfolio-api:$release_id"
if [[ -n "$rebuild_release_json" ]]; then
  jq -e --arg id "$release_id" --arg manifest "$manifest_sha256" '
    .releaseId == $id and .manifestSha256 == $manifest
  ' "$rebuild_release_json" >/dev/null ||
    die "recorded rebuild release ID or public manifest does not match this source build"
fi

source_remote="${SOURCE_CONTINUITY_REMOTE:-}"
[[ "$source_remote" =~ ^[A-Za-z0-9._-]+$ ]] ||
  die "SOURCE_CONTINUITY_REMOTE must name a configured private Git remote"
git -C "$repo_root" remote get-url "$source_remote" >/dev/null 2>&1 ||
  die "SOURCE_CONTINUITY_REMOTE is not a configured Git remote"
source_continuity_ref="refs/tags/portfolio-release/$release_id"
if ! remote_refs="$(git -C "$repo_root" ls-remote --tags "$source_remote" \
    "$source_continuity_ref" "$source_continuity_ref^{}" 2>/dev/null)"; then
  die "cannot read protected source tag; create/protect $source_continuity_ref at $git_commit on remote '$source_remote', then rerun"
fi
remote_commit="$(printf '%s\n' "$remote_refs" | awk -v direct="$source_continuity_ref" \
  '$2 == direct "^{}" { peeled=$1 } $2 == direct { tagged=$1 } END { if (peeled) print peeled; else print tagged }')"
[[ "$remote_commit" == "$git_commit" ]] ||
  die "protected source tag must equal $git_commit; create/protect $source_continuity_ref on remote '$source_remote', then rerun"

note "building API image $api_image_tag"
docker build --progress=plain --provenance=false --platform "$target_platform" \
  --target runtime --tag "$api_image_tag" \
  "${docker_build_args[@]}" "$source_dir" >&2
docker image inspect "$api_image_tag" >/dev/null
api_image_id="$(portable_image_id "$api_image_tag")"
[[ "$api_image_id" =~ ^sha256:[0-9a-f]{64}$ ]] || die "Docker did not report a portable API config digest"

api_image_repo_digest=""
registry_repository="${PORTFOLIO_API_REGISTRY_REPOSITORY:-}"
if [[ -n "$rebuild_release_json" ]]; then
  api_image_repo_digest="$(jq -r '.apiImageRepoDigest // empty' "$rebuild_release_json")"
  if [[ -n "$api_image_repo_digest" ]]; then
    [[ "$api_image_repo_digest" =~ ^[^[:space:]@]+@sha256:[0-9a-f]{64}$ ]] ||
      die "recorded API repository digest is invalid"
    note "pulling recorded immutable API repository digest"
    docker pull --platform "$target_platform" "$api_image_repo_digest" >&2
    [[ "$(portable_image_id "$api_image_repo_digest")" == "$api_image_id" ]] ||
      die "recorded API repository digest does not reproduce the built API image"
  fi
elif [[ -n "$registry_repository" ]]; then
  [[ "$registry_repository" =~ ^[A-Za-z0-9][A-Za-z0-9._:/-]*[A-Za-z0-9]$ && "$registry_repository" != *@* ]] ||
    die "invalid PORTFOLIO_API_REGISTRY_REPOSITORY"
  [[ "${registry_repository##*/}" != *:* ]] || die "registry repository must not contain an image tag"
  registry_tag="$registry_repository:$release_id"
  if docker image inspect "$registry_tag" >/dev/null 2>&1; then
    existing_registry_id="$(portable_image_id "$registry_tag")"
    [[ "$existing_registry_id" == "$api_image_id" ]] ||
      die "local registry release tag already points to a different image"
  else
    docker tag "$api_image_tag" "$registry_tag"
  fi
  note "publishing explicitly configured immutable registry tag $registry_tag"
  docker push "$registry_tag" >&2
  api_image_repo_digest="$(docker image inspect "$registry_tag" |
    jq -r --arg repository "$registry_repository" \
      '.[0].RepoDigests[]? | select(startswith($repository + "@sha256:"))' | sort -u)"
  [[ "$api_image_repo_digest" =~ ^[^[:space:]@]+@sha256:[0-9a-f]{64}$ ]] ||
    die "registry push did not produce one immutable repository digest"
  [[ "$(portable_image_id "$api_image_repo_digest")" == "$api_image_id" ]] ||
    die "registry repository digest does not resolve to the built API image"
fi

postgres_image_id="$(portable_image_id "$postgres_image_ref")"
[[ "$postgres_image_id" =~ ^sha256:[0-9a-f]{64}$ ]] ||
  die "Docker did not report a portable PostgreSQL config digest"
postgres_image_tag="portfolio-postgres-17:$release_id"
if docker image inspect "$postgres_image_tag" >/dev/null 2>&1; then
  [[ "$(portable_image_id "$postgres_image_tag")" == "$postgres_image_id" ]] ||
    die "PostgreSQL release-local target tag already points to different content"
else
  docker tag "$postgres_image_ref" "$postgres_image_tag"
fi

create_image_archive() {
  local image_ref="${1:?image reference required}"
  local expected_id="${2:?image id required}"
  local archive_tag="${3:?archive tag required}"
  local output_path="${4:?archive output required}"
  local source_id archive_id loaded_id
  if docker image inspect "$archive_tag" >/dev/null 2>&1; then
    die "reserved disposable archive tag already exists: $archive_tag"
  fi
  cleanup_tags+=("$archive_tag")
  source_id="$(portable_image_id "$image_ref")"
  [[ "$source_id" == "$expected_id" ]] || die "source image config digest changed before archive export"
  docker tag "$image_ref" "$archive_tag"
  docker save "$archive_tag" | zstd -q -T1 -19 -o "$output_path"
  archive_id="$(archive_image_id "$output_path" "$archive_tag")"
  [[ "$archive_id" == "$expected_id" ]] || die "image archive Config digest mismatch for $archive_tag"
  docker image rm "$archive_tag" >/dev/null
  zstd -q -dc "$output_path" | docker load >&2
  loaded_id="$(portable_image_id "$archive_tag")"
  [[ "$loaded_id" == "$expected_id" ]] || die "image archive round trip changed content ID for $archive_tag"
  [[ "$(portable_image_id "$image_ref")" == "$expected_id" ]] ||
    die "image archive round trip changed the source image reference"
  docker image rm "$archive_tag" >/dev/null
}

note "exporting and round-trip verifying API and PostgreSQL image archives"
create_image_archive "$api_image_tag" "$api_image_id" \
  "portfolio-api-archive:$release_id" "$staging_dir/images/portfolio-api.oci.tar.zst"
create_image_archive "$postgres_image_ref" "$postgres_image_id" \
  "portfolio-postgres-17-archive:$release_id" "$staging_dir/images/postgres-17.oci.tar.zst"
chmod 0600 \
  "$staging_dir/images/portfolio-api.oci.tar.zst" \
  "$staging_dir/images/postgres-17.oci.tar.zst"

container_id="$(docker create "$api_image_tag")"
cleanup_containers+=("$container_id")
docker cp "$container_id:/app/portfolio-server.jar" "$jar_copy" >/dev/null
docker rm "$container_id" >/dev/null
cleanup_containers=()
jar_sha256="$(sha256sum -- "$jar_copy" | awk '{print $1}')"

note 'building complete containerized compliance closure'
mapfile -t syft_image_lines < <(awk -F= '$1 == "SYFT_IMAGE" { print substr($0, index($0, "=") + 1) }' \
  "$source_dir/deploy/compliance/toolchain.env")
[[ ${#syft_image_lines[@]} -eq 1 ]] || die 'compliance toolchain must contain exactly one Syft image'
syft_image_ref="${syft_image_lines[0]}"
[[ "$syft_image_ref" =~ ^docker\.io/anchore/syft:v[0-9]+\.[0-9]+\.[0-9]+@sha256:[0-9a-f]{64}$ ]] ||
  die 'compliance Syft image is not an immutable reviewed reference'
docker pull --platform "$target_platform" "$syft_image_ref" >&2

generate_syft_raw_sbom() {
  local archive="${1:?docker archive required}" output="${2:?SBOM output required}"
  [[ -f "$archive" && ! -L "$archive" && ! -e "$output" && ! -L "$output" ]] ||
    die 'Syft input/output boundary is unsafe'
  docker run --rm \
    --pull never \
    --network none \
    --platform "$target_platform" \
    --read-only \
    --tmpfs /tmp:rw,noexec,nosuid,nodev,size=1073741824,mode=1777 \
    --cap-drop ALL \
    --security-opt no-new-privileges:true \
    --user 0:0 \
    --env HOME=/tmp \
    --env TMPDIR=/tmp \
    --env SYFT_CACHE_DIR=/tmp/syft-cache \
    --env SYFT_CHECK_FOR_APP_UPDATE=false \
    --mount "type=bind,src=$archive,dst=/input/image.docker.tar,readonly" \
    "$syft_image_ref" \
    'docker-archive:/input/image.docker.tar' \
    --output cyclonedx-json >"$output"
  [[ -s "$output" && ! -L "$output" ]] || die 'digest-pinned Syft produced no SBOM evidence'
  jq -e '.bomFormat == "CycloneDX" and .specVersion == "1.6"' "$output" >/dev/null ||
    die 'digest-pinned Syft produced an unsupported SBOM'
}

api_syft_archive="$work_dir/api-syft.docker.tar"
postgres_syft_archive="$work_dir/postgres-syft.docker.tar"
api_syft_raw="$work_dir/api-syft.raw.cdx.json"
postgres_syft_raw="$work_dir/postgres-syft.raw.cdx.json"
zstd -q -dc -- "$staging_dir/images/portfolio-api.oci.tar.zst" >"$api_syft_archive"
zstd -q -dc -- "$staging_dir/images/postgres-17.oci.tar.zst" >"$postgres_syft_archive"
note 'running digest-pinned Syft in isolated no-network, no-socket containers'
generate_syft_raw_sbom "$api_syft_archive" "$api_syft_raw"
generate_syft_raw_sbom "$postgres_syft_archive" "$postgres_syft_raw"

compliance_runner_tag="portfolio-compliance-runner:${git_commit}-${BASHPID}"
docker image inspect "$compliance_runner_tag" >/dev/null 2>&1 &&
  die "refusing to replace existing compliance runner tag: $compliance_runner_tag"
cleanup_tags+=("$compliance_runner_tag")
docker build --progress=plain --provenance=false --platform "$target_platform" \
  --target compliance-runner --tag "$compliance_runner_tag" \
  "${docker_build_args[@]}" "$source_dir" >&2
compliance_runner_image_id="$(docker image inspect --format '{{.Id}}' "$compliance_runner_tag")"
[[ "$compliance_runner_image_id" =~ ^sha256:[0-9a-f]{64}$ ]] ||
  die 'compliance runner did not resolve to an immutable image ID'

compliance_container="portfolio-compliance-${git_commit:0:12}-${BASHPID}"
cleanup_containers+=("$compliance_container")
host_uid="$(id -u)"
host_gid="$(id -g)"
docker run --rm --name "$compliance_container" \
  --platform "$target_platform" \
  --network bridge \
  --read-only \
  --cap-drop ALL \
  --security-opt no-new-privileges:true \
  --user "$host_uid:$host_gid" \
  --mount "type=bind,src=$source_dir,dst=$source_dir" \
  --mount "type=bind,src=$staging_dir,dst=$staging_dir" \
  --mount "type=bind,src=$api_syft_raw,dst=/run/api-syft.raw.cdx.json,readonly" \
  --mount "type=bind,src=$postgres_syft_raw,dst=/run/postgres-syft.raw.cdx.json,readonly" \
  --tmpfs /tmp:rw,nosuid,nodev,size=4294967296,mode=1777 \
  --env "HOME=$source_dir/.compliance-home" \
  --env "COMPLIANCE_SOURCE=$source_dir" \
  --env "COMPLIANCE_OUTPUT=$staging_dir/compliance" \
  --env "API_OCI=$staging_dir/images/portfolio-api.oci.tar.zst" \
  --env "POSTGRES_OCI=$staging_dir/images/postgres-17.oci.tar.zst" \
  --env "API_CONFIG_DIGEST=$api_image_id" \
  --env "POSTGRES_CONFIG_DIGEST=$postgres_image_id" \
  --env API_SYFT_RAW=/run/api-syft.raw.cdx.json \
  --env POSTGRES_SYFT_RAW=/run/postgres-syft.raw.cdx.json \
  --env "SOURCE_DATE_EPOCH=$source_date_epoch" \
  "$compliance_runner_image_id" /bin/bash -euo pipefail -c '
    exec bash "$COMPLIANCE_SOURCE/deploy/scripts/generate-compliance-artifacts.sh" \
      --source "$COMPLIANCE_SOURCE" \
      --output "$COMPLIANCE_OUTPUT" \
      --source-date-epoch "$SOURCE_DATE_EPOCH" \
      --api-oci "$API_OCI" \
      --api-config-digest "$API_CONFIG_DIGEST" \
      --postgres-oci "$POSTGRES_OCI" \
      --postgres-config-digest "$POSTGRES_CONFIG_DIGEST" \
      --api-syft-raw "$API_SYFT_RAW" \
      --postgres-syft-raw "$POSTGRES_SYFT_RAW"
  ' >&2
cleanup_containers=()
docker image rm "$compliance_runner_tag" >/dev/null
cleanup_tags=()
[[ -f "$staging_dir/compliance/SHA256SUMS" &&
   -f "$staging_dir/compliance/THIRD_PARTY_NOTICES.txt" &&
   -f "$staging_dir/compliance/ASSET_PROVENANCE.md" ]] ||
  die 'containerized compliance closure is incomplete'
if find "$staging_dir/compliance" -type l -print -quit | grep -q .; then
  die 'compliance closure contains a symbolic link'
fi
if find "$staging_dir/compliance" \! -type d \! -type f -print -quit | grep -q .; then
  die 'compliance closure contains a non-regular entry'
fi

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

admin_tree_sha256="$(canonical_tree_sha "$staging_dir/admin")"
public_tree_sha256="$(canonical_tree_sha "$staging_dir/public-assets")"
ops_tree_sha256="$(canonical_tree_sha "$staging_dir/ops")"
compliance_tree_sha256="$(canonical_tree_sha "$staging_dir/compliance")"
api_archive_sha256="$(sha256sum -- "$staging_dir/images/portfolio-api.oci.tar.zst" | awk '{print $1}')"
postgres_archive_sha256="$(sha256sum -- "$staging_dir/images/postgres-17.oci.tar.zst" | awk '{print $1}')"

if [[ -n "$api_image_repo_digest" ]]; then
  repo_digest_json="$(jq -cn --arg value "$api_image_repo_digest" '$value')"
else
  repo_digest_json=null
fi

jq -n \
  --arg releaseId "$release_id" \
  --arg gitCommit "$git_commit" \
  --arg manifestSha256 "$manifest_sha256" \
  --arg sourceContinuityRef "$source_continuity_ref" \
  --arg apiImageTag "$api_image_tag" \
  --arg apiImageId "$api_image_id" \
  --argjson apiImageRepoDigest "$repo_digest_json" \
  --arg postgresImageRef "$postgres_image_ref" \
  --arg postgresImageTag "$postgres_image_tag" \
  --arg postgresImageId "$postgres_image_id" \
  --arg jarSha256 "$jar_sha256" \
  --arg adminTreeSha256 "$admin_tree_sha256" \
  --arg publicTreeSha256 "$public_tree_sha256" \
  --arg opsTreeSha256 "$ops_tree_sha256" \
  --arg complianceTreeSha256 "$compliance_tree_sha256" \
  --arg apiImageArchiveSha256 "$api_archive_sha256" \
  --arg postgresImageArchiveSha256 "$postgres_archive_sha256" \
  --arg buildTimeUtc "$build_time_utc" \
  --arg nodeImageRef "$node_image_ref" \
  --arg playwrightImageRef "$playwright_image_ref" \
  --arg javaBuildImageRef "$java_build_image_ref" \
  --arg javaRuntimeImageRef "$java_runtime_image_ref" \
  --arg ubuntuAptSnapshot "$ubuntu_apt_snapshot" \
  --arg targetPlatform "$target_platform" \
  --argjson sourceDateEpoch "$source_date_epoch" \
  '{releaseId:$releaseId,gitCommit:$gitCommit,manifestSha256:$manifestSha256,
    sourceContinuityRef:$sourceContinuityRef,apiImageTag:$apiImageTag,apiImageId:$apiImageId,
    apiImageRepoDigest:$apiImageRepoDigest,postgresImageRef:$postgresImageRef,
    postgresImageTag:$postgresImageTag,postgresImageId:$postgresImageId,
    jarSha256:$jarSha256,adminTreeSha256:$adminTreeSha256,
    publicTreeSha256:$publicTreeSha256,opsTreeSha256:$opsTreeSha256,
    complianceTreeSha256:$complianceTreeSha256,
    apiImageArchiveSha256:$apiImageArchiveSha256,
    postgresImageArchiveSha256:$postgresImageArchiveSha256,bundlePayloadSha256:null,
    buildTimeUtc:$buildTimeUtc,
    buildInputs:{nodeImageRef:$nodeImageRef,playwrightImageRef:$playwrightImageRef,javaBuildImageRef:$javaBuildImageRef,
      javaRuntimeImageRef:$javaRuntimeImageRef,ubuntuAptSnapshot:$ubuntuAptSnapshot,
      sourceDateEpoch:$sourceDateEpoch,targetPlatform:$targetPlatform}}' >"$staging_dir/release.json.tmp"

bundle_payload_sha256="$(jq -Sc '{releaseId,gitCommit,manifestSha256,sourceContinuityRef,apiImageTag,
  apiImageId,apiImageRepoDigest,postgresImageRef,postgresImageTag,postgresImageId,
  jarSha256,adminTreeSha256,
  publicTreeSha256,opsTreeSha256,complianceTreeSha256,
  apiImageArchiveSha256,postgresImageArchiveSha256,
  buildInputs}' \
  "$staging_dir/release.json.tmp" | sha256sum | awk '{print $1}')"
jq --arg value "$bundle_payload_sha256" '.bundlePayloadSha256 = $value' \
  "$staging_dir/release.json.tmp" >"$staging_dir/release.json"
rm -f -- "$staging_dir/release.json.tmp"

if [[ -n "$rebuild_release_json" ]]; then
  [[ "$(jq -Sc . "$staging_dir/release.json")" == "$(jq -Sc . "$rebuild_release_json")" ]] ||
    die "exact-tag rebuild differs from the complete recorded release identity"
fi

(
  cd "$staging_dir"
  find admin public-assets ops images compliance -type f -print0 | sort -z |
    while IFS= read -r -d '' path; do
      printf '%s  %s\n' "$(sha256sum -- "$path" | awk '{print $1}')" "$path"
    done
) >"$staging_dir/bundle-manifest.json"

note "running complete release artifact contract"
bash "$source_dir/deploy/tests/release-artifact-contract.sh" "$staging_dir" "$release_id" >&2

final_dir="$release_root/$release_id"
[[ "$(git -C "$repo_root" rev-parse --verify 'HEAD^{commit}')" == "$git_commit" ]] ||
  die "HEAD changed during release construction"
git -C "$repo_root" diff --quiet --ignore-submodules -- || die "tracked worktree changed during release construction"
git -C "$repo_root" diff --cached --quiet --ignore-submodules -- || die "index changed during release construction"
assert_no_untracked_files
if [[ -e "$final_dir" ]]; then
  [[ -d "$final_dir" && ! -L "$final_dir" ]] || die "existing release path is not a regular directory"
  bash "$source_dir/deploy/tests/release-artifact-contract.sh" "$final_dir" "$release_id" >&2
  existing_identity="$(jq -Sc . "$final_dir/release.json")"
  staged_identity="$(jq -Sc . "$staging_dir/release.json")"
  [[ "$existing_identity" == "$staged_identity" ]] ||
    die "release $release_id already exists with a different recorded identity"
  note "identical verified release already exists; keeping the installed copy"
else
  mv -- "$staging_dir" "$final_dir"
fi

printf '%s\n' "$release_id"
