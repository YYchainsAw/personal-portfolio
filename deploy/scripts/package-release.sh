#!/usr/bin/env bash
set -euo pipefail

export LC_ALL=C
umask 027

die() {
  printf 'package-release: %s\n' "$*" >&2
  exit 1
}

note() {
  printf 'package-release: %s\n' "$*" >&2
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"
}

release_id="${1:?release id required}"
[[ "$release_id" =~ ^[0-9a-f]{12}-[0-9a-f]{12}$ ]] || die "invalid release id: $release_id"

for command_name in awk docker install jq mktemp mv python3 realpath rm sha256sum stat tar zstd; do
  require_command "$command_name"
done

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

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
repo_root="$(realpath -e -- "$script_dir/../..")"
release_root_input="${PORTFOLIO_RELEASE_ROOT:-$repo_root/artifacts/releases}"
[[ "$release_root_input" != *$'\n'* && "$release_root_input" != *$'\r'* ]] ||
  die "release root contains an unsupported character"
release_root="$(realpath -e -- "$release_root_input")" || die "release root does not exist"
release_dir="$(realpath -e -- "$release_root/$release_id")" || die "release does not exist: $release_id"
[[ "$release_dir" == "$release_root/$release_id" && -d "$release_dir" && ! -L "$release_dir" ]] ||
  die "release path escaped its reviewed root"

contract="$repo_root/deploy/tests/release-artifact-contract.sh"
[[ -f "$contract" ]] || die "release artifact contract is missing"
note "validating installed release before packaging"
bash "$contract" "$release_dir" "$release_id" >&2

bundle_root_input="${PORTFOLIO_BUNDLE_ROOT:-$(dirname -- "$release_root")/bundles}"
[[ "$bundle_root_input" != *$'\n'* && "$bundle_root_input" != *$'\r'* ]] ||
  die "bundle root contains an unsupported character"
install -d -m 0750 -- "$bundle_root_input"
bundle_root="$(realpath -e -- "$bundle_root_input")"
[[ "$bundle_root" != "/" ]] || die "bundle root must not be the filesystem root"
case "$bundle_root/" in
  "$release_dir"/*) die "bundle root must not be inside the immutable release directory" ;;
esac

archive_name="portfolio-$release_id.tar.zst"
archive_path="$bundle_root/$archive_name"
envelope_path="$archive_path.envelope.json"
release_json_sha256="$(sha256sum -- "$release_dir/release.json" | awk '{print $1}')"

work_dir="$(mktemp -d -- "$bundle_root/.package-$release_id.XXXXXX")"
work_dir="$(realpath -e -- "$work_dir")"
case "$work_dir" in
  "$bundle_root"/.package-"$release_id".*) ;;
  *) die "temporary package path escaped its reviewed parent" ;;
esac
extract_root="$work_dir/extract"
tar_path="$work_dir/bundle.tar"
candidate_archive="$work_dir/$archive_name"
candidate_envelope="$work_dir/$archive_name.envelope.json"
install -d -m 0750 -- "$extract_root"

declare -a cleanup_tags=()
cleanup() {
  local tag
  for tag in "${cleanup_tags[@]}"; do
    if [[ -n "$tag" ]]; then
      docker image rm "$tag" >/dev/null 2>&1 || true
    fi
  done
  if [[ -n "${work_dir:-}" && -d "$work_dir" ]]; then
    case "$(realpath -e -- "$work_dir" 2>/dev/null || true)" in
      "$bundle_root"/.package-"$release_id".*) rm -rf -- "$work_dir" ;;
      *) printf 'package-release: refusing to remove unexpected temporary path\n' >&2 ;;
    esac
  fi
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

validate_envelope() {
  local archive="${1:?archive required}"
  local envelope="${2:?envelope required}"
  local expected_archive_sha expected_archive_bytes
  [[ -f "$archive" && ! -L "$archive" && -f "$envelope" && ! -L "$envelope" ]] ||
    die "bundle archive and detached envelope must both be regular files"
  jq -e --arg id "$release_id" --arg release_sha "$release_json_sha256" '
    (keys | sort) == (["archiveBytes", "archiveSha256", "releaseId", "releaseJsonSha256"] | sort) and
    .releaseId == $id and
    .releaseJsonSha256 == $release_sha and
    (.archiveSha256 | type == "string" and test("^[0-9a-f]{64}$")) and
    (.archiveBytes | type == "number") and
    (.archiveBytes > 0) and
    (.archiveBytes == (.archiveBytes | floor))
  ' "$envelope" >/dev/null || die "detached bundle envelope violates its contract"
  expected_archive_sha="$(jq -r '.archiveSha256' "$envelope")"
  expected_archive_bytes="$(jq -r '.archiveBytes' "$envelope")"
  [[ "$(sha256sum -- "$archive" | awk '{print $1}')" == "$expected_archive_sha" ]] ||
    die "outer bundle SHA-256 does not match detached envelope"
  [[ "$(stat -c '%s' -- "$archive")" == "$expected_archive_bytes" ]] ||
    die "outer bundle byte size does not match detached envelope"
}

if [[ -e "$archive_path" || -e "$envelope_path" ]]; then
  [[ -f "$archive_path" && -f "$envelope_path" ]] ||
    die "partial existing bundle found; preserve it for investigation and remove it explicitly before retrying"
  note "using existing bundle after detached-envelope verification"
  validate_envelope "$archive_path" "$envelope_path"
  selected_archive="$archive_path"
  selected_envelope="$envelope_path"
  install_candidate=false
else
  note "creating reproducible POSIX ustar bundle"
  tar -C "$release_root" \
    --sort=name \
    --format=ustar \
    --mtime='@0' \
    --owner=0 \
    --group=0 \
    --numeric-owner \
    --mode='u+rwX,go-rwx' \
    --transform="s,^$release_id,portfolio-$release_id," \
    -cf - "$release_id" | zstd -q -T1 -19 -o "$candidate_archive"
  archive_sha256="$(sha256sum -- "$candidate_archive" | awk '{print $1}')"
  archive_bytes="$(stat -c '%s' -- "$candidate_archive")"
  jq -n \
    --arg releaseId "$release_id" \
    --arg archiveSha256 "$archive_sha256" \
    --argjson archiveBytes "$archive_bytes" \
    --arg releaseJsonSha256 "$release_json_sha256" \
    '{releaseId:$releaseId,archiveSha256:$archiveSha256,archiveBytes:$archiveBytes,
      releaseJsonSha256:$releaseJsonSha256}' >"$candidate_envelope"
  validate_envelope "$candidate_archive" "$candidate_envelope"
  selected_archive="$candidate_archive"
  selected_envelope="$candidate_envelope"
  install_candidate=true
fi

note "verifying detached envelope before extraction"
validate_envelope "$selected_archive" "$selected_envelope"
zstd -q --test "$selected_archive"
zstd -q -dc "$selected_archive" >"$tar_path"

python3 - "$tar_path" "$release_id" <<'PY'
import sys
import tarfile
from pathlib import PurePosixPath

archive_path, release_id = sys.argv[1:]
prefix = f"portfolio-{release_id}"
seen = set()
seen_casefold = set()

with tarfile.open(archive_path, mode="r:") as archive:
    if archive.pax_headers:
        raise SystemExit("package-release: global PAX headers are forbidden")
    for member in archive:
        if member.type not in (tarfile.REGTYPE, tarfile.AREGTYPE, tarfile.DIRTYPE):
            raise SystemExit(f"package-release: forbidden tar entry type: {member.name}")
        if member.pax_headers:
            raise SystemExit(f"package-release: per-entry PAX headers are forbidden: {member.name}")
        name = member.name
        if not name or "\\" in name or name.startswith("/"):
            raise SystemExit("package-release: unsafe tar path")
        raw_name = name[:-1] if name.endswith("/") else name
        raw_parts = raw_name.split("/")
        if any(part in ("", ".", "..") for part in raw_parts):
            raise SystemExit(f"package-release: non-canonical tar path: {name}")
        path = PurePosixPath(raw_name)
        if path.parts[0] != prefix:
            raise SystemExit(f"package-release: tar path outside release prefix: {name}")
        normalized = str(path)
        folded = normalized.casefold()
        if normalized in seen or folded in seen_casefold:
            raise SystemExit(f"package-release: duplicate tar path: {name}")
        seen.add(normalized)
        seen_casefold.add(folded)

if prefix not in seen:
    raise SystemExit("package-release: release root missing from tar")
PY

tar -xf "$tar_path" \
  --directory "$extract_root" \
  --no-same-owner \
  --no-same-permissions
extracted_release="$extract_root/portfolio-$release_id"
[[ -d "$extracted_release" && ! -L "$extracted_release" ]] ||
  die "bundle did not extract the expected release root"
[[ "$(sha256sum -- "$extracted_release/release.json" | awk '{print $1}')" == "$release_json_sha256" ]] ||
  die "extracted release.json does not match the detached envelope"

expected_api_id="$(jq -r '.apiImageId' "$extracted_release/release.json")"
expected_postgres_id="$(jq -r '.postgresImageId' "$extracted_release/release.json")"
api_image_tag="portfolio-api:$release_id"
postgres_image_tag="$(jq -r '.postgresImageTag' "$extracted_release/release.json")"
api_archive_tag="portfolio-api-archive:$release_id"
postgres_archive_tag="portfolio-postgres-17-archive:$release_id"

original_api_id="$(portable_image_id "$api_image_tag")"
original_postgres_id="$(portable_image_id "$postgres_image_tag")"
[[ "$original_api_id" == "$expected_api_id" && "$original_postgres_id" == "$expected_postgres_id" ]] ||
  die "loaded source images do not match release.json before bundle round trip"
if docker image inspect "$postgres_image_tag" >/dev/null 2>&1; then
  [[ "$(portable_image_id "$postgres_image_tag")" == "$expected_postgres_id" ]] ||
    die "PostgreSQL release-local target tag already points to different content"
fi
for tag in "$api_archive_tag" "$postgres_archive_tag"; do
  docker image inspect "$tag" >/dev/null 2>&1 && die "reserved disposable archive tag already exists: $tag"
  cleanup_tags+=("$tag")
done

note "loading bundled images into disposable tags"
zstd -q --test "$extracted_release/images/portfolio-api.oci.tar.zst"
[[ "$(archive_image_id "$extracted_release/images/portfolio-api.oci.tar.zst" "$api_archive_tag")" == "$expected_api_id" ]] ||
  die "bundled API archive Config digest mismatch"
zstd -q -dc "$extracted_release/images/portfolio-api.oci.tar.zst" | docker load >&2
[[ "$(portable_image_id "$api_archive_tag")" == "$expected_api_id" ]] ||
  die "bundled API image ID mismatch"
zstd -q --test "$extracted_release/images/postgres-17.oci.tar.zst"
[[ "$(archive_image_id "$extracted_release/images/postgres-17.oci.tar.zst" "$postgres_archive_tag")" == "$expected_postgres_id" ]] ||
  die "bundled PostgreSQL archive Config digest mismatch"
zstd -q -dc "$extracted_release/images/postgres-17.oci.tar.zst" | docker load >&2
[[ "$(portable_image_id "$postgres_archive_tag")" == "$expected_postgres_id" ]] ||
  die "bundled PostgreSQL image ID mismatch"
[[ "$(portable_image_id "$api_image_tag")" == "$original_api_id" ]] ||
  die "bundle round trip changed the API production tag"
[[ "$(portable_image_id "$postgres_image_tag")" == "$original_postgres_id" ]] ||
  die "bundle round trip changed the PostgreSQL release-local target tag"

note "rerunning complete artifact contract against disposable extraction"
bash "$contract" "$extracted_release" "$release_id" >&2

docker image rm "$api_archive_tag" "$postgres_archive_tag" >/dev/null
cleanup_tags=()

if [[ "$install_candidate" == true ]]; then
  mv -- "$candidate_archive" "$archive_path"
  mv -- "$candidate_envelope" "$envelope_path"
  validate_envelope "$archive_path" "$envelope_path"
fi

bootstrap_packager="$release_dir/ops/deploy/scripts/package-bootstrap-kit.sh"
[[ -f "$bootstrap_packager" && ! -L "$bootstrap_packager" ]] ||
  die "release-local bootstrap packager is missing or linked"
note "creating or verifying commit-bound bootstrap kit"
bootstrap_archive="$(bash "$bootstrap_packager" "$release_dir" "$bundle_root")" ||
  die "bootstrap kit packaging failed"
[[ -f "$bootstrap_archive" && ! -L "$bootstrap_archive" ]] ||
  die "bootstrap packager did not return a regular archive"
note "bootstrap kit: $bootstrap_archive"

printf '%s\n' "$archive_path"
