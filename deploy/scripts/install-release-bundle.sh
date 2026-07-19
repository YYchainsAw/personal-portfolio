#!/usr/bin/env bash
set -euo pipefail
set +x
umask 077

export LC_ALL=C

PORTFOLIO_ROOT="${PORTFOLIO_ROOT:-/opt/portfolio}"
BACKUP_DISPATCH_PATH="${PORTFOLIO_BACKUP_DISPATCH_PATH:-/usr/local/libexec/portfolio/backup-dispatch.sh}"
STAGING_DIRECTORY=''
DISPATCH_SOURCE_SHA=''
DISPATCH_PREEXISTED=false
DISPATCH_CREATED=false
DISPATCH_CREATED_IDENTITY=''
RELEASE_COMMITTED=false
RELEASE_TARGET_CREATED=false
API_TARGET_CREATED=false
POSTGRES_TARGET_CREATED=false
API_ARCHIVE_TAG=''
POSTGRES_ARCHIVE_TAG=''
API_TARGET_TAG=''
POSTGRES_TARGET_TAG=''
RELEASES_ROOT=''

fail() {
  printf 'portfolio bundle install failed: %s\n' "$1" >&2
  exit 1
}

note() {
  printf 'portfolio bundle install: %s\n' "$1" >&2
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "$1 is required"
}

validate_root_directory() {
  local path="$1" label="$2" mode parent
  [[ "$path" == /* && "$path" != / && -d "$path" && ! -L "$path" ]] ||
    fail "$label is missing, linked, or not absolute"
  [[ "$(realpath -e -- "$path")" == "$path" && "$(stat -Lc '%u' -- "$path")" == 0 ]] ||
    fail "$label is non-canonical or not owned by root"
  mode="$(stat -Lc '%a' -- "$path")"
  (( (8#$mode & 8#022) == 0 )) || fail "$label is writable outside root"
  parent="$(dirname -- "$path")"
  [[ -d "$parent" && ! -L "$parent" && "$(realpath -e -- "$parent")" == "$parent" &&
     "$(stat -Lc '%u' -- "$parent")" == 0 ]] || fail "$label parent is unsafe"
  mode="$(stat -Lc '%a' -- "$parent")"
  (( (8#$mode & 8#022) == 0 )) || fail "$label parent is writable outside root"
}

validate_root_input_file() {
  local path="$1" label="$2" mode parent
  [[ "$path" == /* && -f "$path" && ! -L "$path" &&
     "$(stat -Lc '%u:%h' -- "$path")" == 0:1 ]] ||
    fail "$label is not a root-owned single-link regular file"
  mode="$(stat -Lc '%a' -- "$path")"
  (( (8#$mode & 8#022) == 0 )) || fail "$label is writable outside root"
  parent="$(dirname -- "$path")"
  [[ -d "$parent" && ! -L "$parent" && "$(realpath -e -- "$parent")" == "$parent" &&
     "$(stat -Lc '%u' -- "$parent")" == 0 ]] || fail "$label parent is unsafe"
  mode="$(stat -Lc '%a' -- "$parent")"
  (( (8#$mode & 8#022) == 0 )) || fail "$label parent is writable outside root"
}

revalidate_deploy_lock_binding() {
  local lock_file="$1" fd="$2" requested_parent parent parent_mode lock_mode
  requested_parent="$(dirname -- "$lock_file")"
  [[ -d "$requested_parent" && ! -L "$requested_parent" ]] ||
    fail 'deploy lock changed while waiting: parent is missing or linked'
  parent="$(realpath -e -- "$requested_parent")"
  [[ "$parent" == "$requested_parent" && "$parent" != / &&
     "$(stat -Lc '%u:%g' -- "$parent")" == 0:0 ]] ||
    fail 'deploy lock changed while waiting: parent is unsafe'
  parent_mode="$(stat -Lc '%a' -- "$parent")"
  if [[ "$lock_file" == /run/lock/portfolio/deploy.lock ]]; then
    [[ "$parent_mode" == 700 ]] ||
      fail 'deploy lock changed while waiting: default parent mode is not 0700'
  else
    (( (8#$parent_mode & 8#022) == 0 )) ||
      fail 'deploy lock changed while waiting: parent is writable outside root'
  fi
  [[ -f "$lock_file" && ! -L "$lock_file" &&
     "$(stat -Lc '%u:%g:%h' -- "$lock_file")" == 0:0:1 ]] ||
    fail 'deploy lock changed while waiting: path is not a root-owned single-link file'
  lock_mode="$(stat -Lc '%a' -- "$lock_file")"
  (( (8#$lock_mode & 8#022) == 0 )) ||
    fail 'deploy lock changed while waiting: path is writable outside root'
  [[ "$(stat -Lc '%d:%i' -- "$lock_file")" == \
     "$(stat -Lc '%d:%i' -- "/proc/self/fd/$fd")" ]] ||
    fail 'deploy lock changed while waiting: descriptor identity differs'
}

acquire_deploy_lock() {
  local lock_file="${PORTFOLIO_DEPLOY_LOCK_FILE:-/run/lock/portfolio/deploy.lock}"
  local parent requested_parent parent_uid parent_gid parent_mode
  [[ "$lock_file" == /* && "$lock_file" != / &&
     "$lock_file" != *$'\n'* && "$lock_file" != *$'\r'* ]] ||
    fail 'deploy lock path is invalid'
  requested_parent="$(dirname -- "$lock_file")"
  [[ -d "$requested_parent" && ! -L "$requested_parent" ]] ||
    fail 'deploy lock parent is missing or linked'
  parent="$(realpath -e -- "$requested_parent")"
  [[ "$parent" == "$requested_parent" && "$parent" != / ]] ||
    fail 'deploy lock parent is not canonical'
  parent_uid="$(stat -Lc '%u' -- "$parent")"
  parent_gid="$(stat -Lc '%g' -- "$parent")"
  parent_mode="$(stat -Lc '%a' -- "$parent")"
  [[ "$parent_uid" == 0 && "$parent_gid" == 0 ]] ||
    fail 'deploy lock parent is not owned by root:root'
  if [[ "$lock_file" == /run/lock/portfolio/deploy.lock ]]; then
    [[ "$parent_mode" == 700 ]] || fail 'default deploy lock parent mode is not 0700'
  else
    (( (8#$parent_mode & 8#022) == 0 )) ||
      fail 'deploy lock parent is writable outside root'
  fi

  if [[ ! -e "$lock_file" && ! -L "$lock_file" ]]; then
    (umask 077; set -C; : >"$lock_file") 2>/dev/null || true
  fi
  [[ -f "$lock_file" && ! -L "$lock_file" &&
     "$(stat -Lc '%u:%g:%h' -- "$lock_file")" == 0:0:1 ]] ||
    fail 'deploy lock is not a root-owned single-link regular file'
  local lock_mode
  lock_mode="$(stat -Lc '%a' -- "$lock_file")"
  (( (8#$lock_mode & 8#022) == 0 )) || fail 'deploy lock is group- or world-writable'
  exec 9<>"$lock_file"
  [[ "$(stat -Lc '%d:%i' -- "$lock_file")" == "$(stat -Lc '%d:%i' -- /proc/self/fd/9)" ]] ||
    fail 'deploy lock descriptor identity changed'
  flock -x 9
  revalidate_deploy_lock_binding "$lock_file" 9
  export PORTFOLIO_DEPLOY_LOCK_HELD=true PORTFOLIO_DEPLOY_LOCK_FD=9
}

is_release_id() {
  [[ "$1" =~ ^[0-9a-f]{12}-[0-9a-f]{12}$ ]]
}

config_digest_from_manifest() {
  local manifest_json="$1"
  local expected_tag="${2:-}"
  local config_path
  config_path="$(printf '%s' "$manifest_json" | jq -er --arg tag "$expected_tag" '
    if type == "array" and length == 1 and
       (.[0] | type == "object") and
       (.[0].Config | type == "string") and
       ($tag == "" or .[0].RepoTags == [$tag]) and
       (.[0].Layers | type == "array" and length > 0 and
        all(.[]; type == "string"))
    then .[0].Config
    else error("docker save manifest must describe exactly one tagged image")
    end
  ')" || fail 'invalid docker save manifest'
  if [[ "$config_path" =~ ^([0-9a-f]{64})\.json$ ]]; then
    printf '%s\n' "${BASH_REMATCH[1]}"
  elif [[ "$config_path" =~ ^blobs/sha256/([0-9a-f]{64})$ ]]; then
    printf '%s\n' "${BASH_REMATCH[1]}"
  else
    fail 'docker save manifest contains an invalid Config path'
  fi
}

portable_image_id() {
  local reference="$1"
  local manifest config_sha
  manifest="$(docker save "$reference" | tar -xOf - manifest.json)" ||
    fail "could not read portable image identity for $reference"
  config_sha="$(config_digest_from_manifest "$manifest")"
  printf 'sha256:%s\n' "$config_sha"
}

archive_image_id() {
  local archive="$1"
  local expected_tag="$2"
  local manifest config_path config_sha actual_sha
  zstd -q --test "$archive" || fail "invalid compressed image archive: $archive"
  manifest="$(zstd -q -dc "$archive" | tar -xOf - manifest.json)" ||
    fail "image archive has no readable manifest: $archive"
  config_path="$(printf '%s' "$manifest" | jq -er --arg tag "$expected_tag" '
    if type == "array" and length == 1 and
       (.[0] | type == "object") and
       (.[0].Config | type == "string") and
       .[0].RepoTags == [$tag] and
       (.[0].Layers | type == "array" and length > 0 and
        all(.[]; type == "string"))
    then .[0].Config
    else error("archive must contain exactly its disposable tag")
    end
  ')" || fail "image archive tag contract failed: $archive"
  config_sha="$(config_digest_from_manifest "$manifest" "$expected_tag")"
  actual_sha="$(zstd -q -dc "$archive" | tar -xOf - "$config_path" |
    sha256sum | awk '{print $1}')" || fail "image Config blob is unreadable: $archive"
  [[ "$actual_sha" == "$config_sha" ]] ||
    fail "image Config blob digest mismatch: $archive"
  printf 'sha256:%s\n' "$config_sha"
}

image_exists() {
  docker image inspect "$1" >/dev/null 2>&1
}

remove_image_quietly() {
  if [[ -n "$1" ]]; then
    docker image rm "$1" >/dev/null 2>&1 || true
  fi
}

cleanup() {
  local status="$?"
  trap - EXIT HUP INT TERM
  set +e
  remove_image_quietly "$API_ARCHIVE_TAG"
  remove_image_quietly "$POSTGRES_ARCHIVE_TAG"
  if [[ "$RELEASE_COMMITTED" != true ]]; then
    [[ "$API_TARGET_CREATED" == true ]] && remove_image_quietly "$API_TARGET_TAG"
    [[ "$POSTGRES_TARGET_CREATED" == true ]] && remove_image_quietly "$POSTGRES_TARGET_TAG"
    if [[ "$RELEASE_TARGET_CREATED" == true && -n "$RELEASES_ROOT" ]]; then
      local installed_release="$RELEASES_ROOT/${API_TARGET_TAG#portfolio-api:}"
      [[ -d "$installed_release" && ! -L "$installed_release" ]] && rm -rf -- "$installed_release"
    fi
    if [[ "$DISPATCH_CREATED" == true && -n "$DISPATCH_CREATED_IDENTITY" &&
          -f "$BACKUP_DISPATCH_PATH" && ! -L "$BACKUP_DISPATCH_PATH" &&
          "$(stat -Lc '%d:%i' -- "$BACKUP_DISPATCH_PATH" 2>/dev/null)" == \
            "$DISPATCH_CREATED_IDENTITY" ]]; then
      rm -f -- "$BACKUP_DISPATCH_PATH"
    fi
  fi
  if [[ -n "$STAGING_DIRECTORY" && -d "$STAGING_DIRECTORY" &&
        ! -L "$STAGING_DIRECTORY" ]]; then
    case "$STAGING_DIRECTORY" in
      "$PORTFOLIO_ROOT"/.install-*) rm -rf -- "$STAGING_DIRECTORY" ;;
      *) printf 'portfolio bundle install: refusing unsafe staging cleanup\n' >&2 ;;
    esac
  fi
  exit "$status"
}

validate_bundle_manifest() {
  local release_directory="$1"
  python3 - "$release_directory" <<'PY'
import hashlib
import os
import re
import sys
from pathlib import PurePosixPath

root = os.path.realpath(sys.argv[1])
manifest_path = os.path.join(root, "bundle-manifest.json")
line_pattern = re.compile(r"^([0-9a-f]{64})  (.+)$")
listed = {}
with open(manifest_path, "r", encoding="utf-8", newline="") as stream:
    for raw in stream:
        line = raw.removesuffix("\n")
        match = line_pattern.fullmatch(line)
        if match is None:
            raise SystemExit("bundle manifest contains a malformed line")
        digest, encoded = match.groups()
        path = PurePosixPath(encoded)
        if (str(path) != encoded or path.is_absolute() or "\\" in encoded or
                not path.parts or path.parts[0] not in
                {"admin", "public-assets", "ops", "images", "compliance"} or
                any(part in {"", ".", ".."} for part in path.parts)):
            raise SystemExit("bundle manifest contains an unsafe path")
        folded = encoded.casefold()
        if encoded in listed or any(key.casefold() == folded for key in listed):
            raise SystemExit("bundle manifest contains a duplicate path")
        listed[encoded] = digest

actual = set()
for top in ("admin", "public-assets", "ops", "images", "compliance"):
    for directory, directories, files in os.walk(os.path.join(root, top), followlinks=False):
        for name in directories + files:
            candidate = os.path.join(directory, name)
            if os.path.islink(candidate):
                raise SystemExit("bundle tree contains a symbolic link")
        for name in files:
            candidate = os.path.join(directory, name)
            if not os.path.isfile(candidate):
                raise SystemExit("bundle tree contains a non-regular entry")
            relative = os.path.relpath(candidate, root).replace(os.sep, "/")
            actual.add(relative)
if actual != set(listed):
    raise SystemExit("bundle manifest does not exactly cover immutable files")
for relative, expected in listed.items():
    digest = hashlib.sha256()
    with open(os.path.join(root, *PurePosixPath(relative).parts), "rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    if digest.hexdigest() != expected:
        raise SystemExit("bundle manifest checksum mismatch")
PY
}

validate_backup_runtime_executables() {
  local release_directory="$1" relative
  local -a required=(
    backup-dispatch.sh
    backup-set.sh
    backup-database.sh
    backup-media.sh
    lib.sh
    verify-artifact.sh
    postgres-client.sh
    validate-media-tar.py
    notify-failure.sh
    prune-remote.sh
    prune-guard.example.sh
  )
  for relative in "${required[@]}"; do
    relative="ops/deploy/backup/$relative"
    [[ -f "$release_directory/$relative" && ! -L "$release_directory/$relative" &&
       -x "$release_directory/$relative" &&
       "$(stat -Lc '%a' -- "$release_directory/$relative")" == 700 ]] ||
      fail "release-local backup runtime helper is not exactly mode 0700: $relative"
  done
  local -a private=(
    cos-prune-guard.py
    export-media.sql
    record-maintenance.sql
  )
  for relative in "${private[@]}"; do
    relative="ops/deploy/backup/$relative"
    [[ -f "$release_directory/$relative" && ! -L "$release_directory/$relative" &&
       "$(stat -Lc '%a' -- "$release_directory/$relative")" == 600 ]] ||
      fail "release-local backup runtime data is not exactly mode 0600: $relative"
  done
}

normalize_web_permissions() {
  local release_directory="$1"
  chmod 0755 "$RELEASES_ROOT" "$release_directory"
  find -P "$release_directory/admin" -type d -exec chmod 0755 {} +
  find -P "$release_directory/admin" -type f -exec chmod 0644 {} +
  if find -P "$release_directory/ops" "$release_directory/images" "$release_directory/compliance" \
      -perm /0077 -print -quit | grep -q .; then
    fail 'root-only operations or image content was unexpectedly exposed'
  fi
}

preflight_stable_dispatcher() {
  local source="$1"
  local destination_parent existing_parent existing_mode
  [[ -f "$source" && ! -L "$source" ]] || fail 'bundled backup dispatcher is missing or linked'
  [[ "$(stat -Lc '%a' -- "$source")" == 700 ]] ||
    fail 'bundled backup dispatcher mode is not 0700'
  [[ "$BACKUP_DISPATCH_PATH" == /* && "$BACKUP_DISPATCH_PATH" != / ]] ||
    fail 'backup dispatcher destination is invalid'
  [[ "$(realpath -m -- "$BACKUP_DISPATCH_PATH")" == "$BACKUP_DISPATCH_PATH" ]] ||
    fail 'backup dispatcher destination is not canonical'
  destination_parent="$(dirname -- "$BACKUP_DISPATCH_PATH")"
  existing_parent="$destination_parent"
  while [[ ! -e "$existing_parent" && ! -L "$existing_parent" ]]; do
    existing_parent="$(dirname -- "$existing_parent")"
  done
  [[ -d "$existing_parent" && ! -L "$existing_parent" &&
     "$(realpath -e -- "$existing_parent")" == "$existing_parent" &&
     "$(stat -Lc '%u' -- "$existing_parent")" == 0 ]] ||
    fail 'backup dispatcher existing parent is unsafe'
  existing_mode="$(stat -Lc '%a' -- "$existing_parent")"
  (( (8#$existing_mode & 8#022) == 0 )) ||
    fail 'backup dispatcher existing parent is writable outside root'
  DISPATCH_SOURCE_SHA="$(sha256sum -- "$source" | awk '{print $1}')"
  [[ ! -L "$BACKUP_DISPATCH_PATH" ]] || fail 'backup dispatcher destination is linked'
  if [[ -e "$BACKUP_DISPATCH_PATH" ]]; then
    [[ -d "$destination_parent" && ! -L "$destination_parent" &&
       "$(realpath -e -- "$destination_parent")" == "$destination_parent" ]] ||
      fail 'backup dispatcher destination parent is unsafe'
    [[ -f "$BACKUP_DISPATCH_PATH" &&
       "$(stat -Lc '%u:%g:%a:%h' -- "$BACKUP_DISPATCH_PATH")" == 0:0:755:1 ]] ||
      fail 'existing stable backup dispatcher is not root:root mode 0755 single-link'
    cmp -s -- "$source" "$BACKUP_DISPATCH_PATH" ||
      fail 'incoming release backup dispatcher differs from the create-only stable dispatcher'
    DISPATCH_PREEXISTED=true
  fi
}

install_stable_dispatcher_create_only() {
  local source="$1" destination_parent candidate installed_sha
  [[ -n "$DISPATCH_SOURCE_SHA" ]] || fail 'stable backup dispatcher was not preflighted'
  if [[ "$DISPATCH_PREEXISTED" == true ]]; then
    [[ -f "$BACKUP_DISPATCH_PATH" && ! -L "$BACKUP_DISPATCH_PATH" &&
       "$(stat -Lc '%u:%g:%a:%h' -- "$BACKUP_DISPATCH_PATH")" == 0:0:755:1 &&
       "$(sha256sum -- "$BACKUP_DISPATCH_PATH" | awk '{print $1}')" == "$DISPATCH_SOURCE_SHA" ]] ||
      fail 'create-only stable backup dispatcher changed after preflight'
    cmp -s -- "$source" "$BACKUP_DISPATCH_PATH" ||
      fail 'create-only stable backup dispatcher bytes changed after preflight'
    return 0
  fi

  destination_parent="$(dirname -- "$BACKUP_DISPATCH_PATH")"
  install -d -o root -g root -m 0755 -- "$destination_parent"
  destination_parent="$(realpath -e -- "$destination_parent")"
  [[ "$(stat -Lc '%u:%g' -- "$destination_parent")" == 0:0 ]] ||
    fail 'backup dispatcher destination parent is not root:root'
  local destination_mode
  destination_mode="$(stat -Lc '%a' -- "$destination_parent")"
  (( (8#$destination_mode & 8#022) == 0 )) ||
    fail 'backup dispatcher destination parent is writable outside root'
  BACKUP_DISPATCH_PATH="$destination_parent/$(basename -- "$BACKUP_DISPATCH_PATH")"
  [[ ! -e "$BACKUP_DISPATCH_PATH" && ! -L "$BACKUP_DISPATCH_PATH" ]] ||
    fail 'create-only stable backup dispatcher destination appeared after preflight'
  candidate="$(mktemp "$destination_parent/.backup-dispatch.install.XXXXXX")"
  install -o root -g root -m 0755 -- "$source" "$candidate"
  installed_sha="$(sha256sum -- "$candidate" | awk '{print $1}')"
  [[ "$installed_sha" == "$DISPATCH_SOURCE_SHA" ]] || fail 'backup dispatcher candidate hash mismatch'
  sync -f "$candidate"
  if ! ln -- "$candidate" "$BACKUP_DISPATCH_PATH"; then
    rm -f -- "$candidate"
    fail 'create-only stable backup dispatcher publication collided'
  fi
  DISPATCH_CREATED=true
  DISPATCH_CREATED_IDENTITY="$(stat -Lc '%d:%i' -- "$BACKUP_DISPATCH_PATH")"
  rm -f -- "$candidate"
  [[ "$(stat -Lc '%u:%g:%a:%h' -- "$BACKUP_DISPATCH_PATH")" == '0:0:755:1' ]] ||
    fail 'backup dispatcher owner or mode is invalid'
  [[ "$(sha256sum -- "$BACKUP_DISPATCH_PATH" | awk '{print $1}')" == "$DISPATCH_SOURCE_SHA" ]] ||
    fail 'installed backup dispatcher hash mismatch'
  sync -f "$destination_parent"
}

main() {
  [[ $# -eq 2 ]] || fail 'usage: install-release-bundle.sh BUNDLE ENVELOPE'
  local archive="$1" envelope="$2"
  local command_name
  for command_name in awk basename chmod cmp dirname docker find flock grep id install jq ln mktemp mv \
    python3 realpath sha256sum stat sync tar zstd; do
    require_command "$command_name"
  done
  [[ "$(id -u)" -eq 0 ]] || fail 'bundle installation must run as root'
  [[ -f "$archive" && ! -L "$archive" && -f "$envelope" && ! -L "$envelope" ]] ||
    fail 'bundle and envelope must be regular non-linked files'
  archive="$(realpath -e -- "$archive")"
  envelope="$(realpath -e -- "$envelope")"
  [[ "$PORTFOLIO_ROOT" == /* && "$PORTFOLIO_ROOT" != / && -d "$PORTFOLIO_ROOT" &&
     ! -L "$PORTFOLIO_ROOT" ]] || fail 'PORTFOLIO_ROOT is invalid'
  PORTFOLIO_ROOT="$(realpath -e -- "$PORTFOLIO_ROOT")"
  validate_root_directory "$PORTFOLIO_ROOT" 'PORTFOLIO_ROOT'
  validate_root_input_file "$archive" 'bundle'
  validate_root_input_file "$envelope" 'bundle envelope'
  acquire_deploy_lock
  RELEASES_ROOT="$PORTFOLIO_ROOT/releases"
  if [[ -e "$RELEASES_ROOT" || -L "$RELEASES_ROOT" ]]; then
    [[ -d "$RELEASES_ROOT" && ! -L "$RELEASES_ROOT" ]] ||
      fail 'releases root is not a regular directory'
    RELEASES_ROOT="$(realpath -e -- "$RELEASES_ROOT")"
    [[ "$RELEASES_ROOT" == "$PORTFOLIO_ROOT/releases" ]] ||
      fail 'releases root escaped PORTFOLIO_ROOT'
    validate_root_directory "$RELEASES_ROOT" 'releases root'
  fi

  local release_id archive_sha archive_bytes expected_release_sha
  jq -e '
    (keys | sort) == (["archiveBytes","archiveSha256","releaseId","releaseJsonSha256"] | sort) and
    (.releaseId | type == "string" and test("^[0-9a-f]{12}-[0-9a-f]{12}$")) and
    (.archiveSha256 | type == "string" and test("^[0-9a-f]{64}$")) and
    (.releaseJsonSha256 | type == "string" and test("^[0-9a-f]{64}$")) and
    (.archiveBytes | type == "number" and . > 0 and . == floor)
  ' "$envelope" >/dev/null || fail 'detached envelope violates its exact contract'
  release_id="$(jq -r '.releaseId' "$envelope")"
  is_release_id "$release_id" || fail 'detached envelope release ID is invalid'
  archive_sha="$(sha256sum -- "$archive" | awk '{print $1}')"
  archive_bytes="$(stat -c '%s' -- "$archive")"
  [[ "$archive_sha" == "$(jq -r '.archiveSha256' "$envelope")" ]] ||
    fail 'bundle SHA-256 differs from detached envelope'
  [[ "$archive_bytes" == "$(jq -r '.archiveBytes' "$envelope")" ]] ||
    fail 'bundle byte count differs from detached envelope'
  expected_release_sha="$(jq -r '.releaseJsonSha256' "$envelope")"

  local validator compliance_validator script_directory
  script_directory="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
  validator="$script_directory/validate-bundle-tar.py"
  [[ -f "$validator" && ! -L "$validator" ]] || fail 'streaming tar validator is missing or linked'
  compliance_validator="${PORTFOLIO_COMPLIANCE_VALIDATOR:-$script_directory/verify-compliance-tree.py}"
  [[ "$compliance_validator" == /* && -f "$compliance_validator" && ! -L "$compliance_validator" ]] ||
    fail 'independent compliance verifier is missing or linked'
  note 'validating compressed archive stream before extraction'
  zstd -q -dc "$archive" | python3 "$validator" --release-id "$release_id" >/dev/null

  STAGING_DIRECTORY="$(mktemp -d "$PORTFOLIO_ROOT/.install-$release_id.XXXXXX")"
  STAGING_DIRECTORY="$(realpath -e -- "$STAGING_DIRECTORY")"
  case "$STAGING_DIRECTORY" in
    "$PORTFOLIO_ROOT"/.install-*) ;;
    *) fail 'staging directory escaped the portfolio filesystem' ;;
  esac
  [[ -z "$(find "$STAGING_DIRECTORY" -mindepth 1 -print -quit)" ]] ||
    fail 'staging directory was not empty'
  trap cleanup EXIT
  trap 'exit 129' HUP
  trap 'exit 130' INT
  trap 'exit 143' TERM
  zstd -q -dc "$archive" | tar -xf - --directory "$STAGING_DIRECTORY" \
    --no-same-owner --no-same-permissions

  local extracted="$STAGING_DIRECTORY/portfolio-$release_id"
  [[ -d "$extracted" && ! -L "$extracted" ]] || fail 'expected release root was not extracted'
  [[ "$(sha256sum -- "$extracted/release.json" | awk '{print $1}')" == "$expected_release_sha" ]] ||
    fail 'embedded release.json SHA-256 differs from detached envelope'
  validate_bundle_manifest "$extracted" || fail 'bundle manifest validation failed'
  python3 "$compliance_validator" \
    --tree "$extracted/compliance" \
    --release-json "$extracted/release.json" || fail 'release compliance closure failed verification'
  validate_backup_runtime_executables "$extracted"
  preflight_stable_dispatcher "$extracted/ops/deploy/backup/backup-dispatch.sh"

  # Only after the create-only stable dispatcher compatibility gate succeeds
  # may the installer make durable portfolio-tree changes.
  chmod 0711 "$PORTFOLIO_ROOT"
  install -d -m 0755 -- "$RELEASES_ROOT"
  RELEASES_ROOT="$(realpath -e -- "$RELEASES_ROOT")"
  [[ "$RELEASES_ROOT" == "$PORTFOLIO_ROOT/releases" ]] ||
    fail 'releases root escaped PORTFOLIO_ROOT'
  validate_root_directory "$RELEASES_ROOT" 'releases root'

  local expected_api_id expected_postgres_id
  local api_archive="$extracted/images/portfolio-api.oci.tar.zst"
  local postgres_archive="$extracted/images/postgres-17.oci.tar.zst"
  API_TARGET_TAG="portfolio-api:$release_id"
  POSTGRES_TARGET_TAG="portfolio-postgres-17:$release_id"
  API_ARCHIVE_TAG="portfolio-api-archive:$release_id"
  POSTGRES_ARCHIVE_TAG="portfolio-postgres-17-archive:$release_id"
  jq -e --arg id "$release_id" '
    .releaseId == $id and .apiImageTag == ("portfolio-api:"+$id) and
    .postgresImageTag == ("portfolio-postgres-17:"+$id) and
    (.apiImageId | test("^sha256:[0-9a-f]{64}$")) and
    (.postgresImageId | test("^sha256:[0-9a-f]{64}$"))
  ' "$extracted/release.json" >/dev/null || fail 'release image metadata is invalid'
  expected_api_id="$(jq -r '.apiImageId' "$extracted/release.json")"
  expected_postgres_id="$(jq -r '.postgresImageId' "$extracted/release.json")"
  [[ "$(archive_image_id "$api_archive" "$API_ARCHIVE_TAG")" == "$expected_api_id" ]] ||
    fail 'API archive portable identity differs from release.json'
  [[ "$(archive_image_id "$postgres_archive" "$POSTGRES_ARCHIVE_TAG")" == "$expected_postgres_id" ]] ||
    fail 'PostgreSQL archive portable identity differs from release.json'

  local target existing
  for target in "$API_ARCHIVE_TAG" "$POSTGRES_ARCHIVE_TAG"; do
    image_exists "$target" && fail "reserved disposable image tag already exists: $target"
  done
  if image_exists "$API_TARGET_TAG"; then
    existing="$(portable_image_id "$API_TARGET_TAG")"
    [[ "$existing" == "$expected_api_id" ]] || fail 'conflicting API target tag; no image was loaded'
  fi
  if image_exists "$POSTGRES_TARGET_TAG"; then
    existing="$(portable_image_id "$POSTGRES_TARGET_TAG")"
    [[ "$existing" == "$expected_postgres_id" ]] ||
      fail 'conflicting PostgreSQL target tag; no image was loaded'
  fi

  note 'loading validated disposable image archives'
  zstd -q -dc "$api_archive" | docker load >/dev/null
  [[ "$(portable_image_id "$API_ARCHIVE_TAG")" == "$expected_api_id" ]] ||
    fail 'loaded API archive identity mismatch'
  zstd -q -dc "$postgres_archive" | docker load >/dev/null
  [[ "$(portable_image_id "$POSTGRES_ARCHIVE_TAG")" == "$expected_postgres_id" ]] ||
    fail 'loaded PostgreSQL archive identity mismatch'
  if ! image_exists "$API_TARGET_TAG"; then
    docker tag "$API_ARCHIVE_TAG" "$API_TARGET_TAG"
    API_TARGET_CREATED=true
  fi
  if ! image_exists "$POSTGRES_TARGET_TAG"; then
    docker tag "$POSTGRES_ARCHIVE_TAG" "$POSTGRES_TARGET_TAG"
    POSTGRES_TARGET_CREATED=true
  fi
  [[ "$(portable_image_id "$API_TARGET_TAG")" == "$expected_api_id" ]] ||
    fail 'installed API target tag identity mismatch'
  [[ "$(portable_image_id "$POSTGRES_TARGET_TAG")" == "$expected_postgres_id" ]] ||
    fail 'installed PostgreSQL target tag identity mismatch'

  local contract="${PORTFOLIO_RELEASE_CONTRACT:-$extracted/ops/deploy/tests/release-artifact-contract.sh}"
  [[ "$contract" == /* && -f "$contract" && ! -L "$contract" ]] ||
    fail 'release artifact contract is missing, linked, or not absolute'
  bash "$contract" "$extracted" "$release_id" >/dev/null || fail 'release artifact contract failed'
  normalize_web_permissions "$extracted"

  local target_release="$RELEASES_ROOT/$release_id"
  if [[ -e "$target_release" || -L "$target_release" ]]; then
    [[ -d "$target_release" && ! -L "$target_release" ]] ||
      fail 'existing release target is not a regular directory'
    [[ "$(sha256sum -- "$target_release/release.json" | awk '{print $1}')" == "$expected_release_sha" ]] ||
      fail 'existing release target conflicts with bundle metadata'
    bash "$contract" "$target_release" "$release_id" >/dev/null ||
      fail 'existing release target failed the artifact contract'
    normalize_web_permissions "$target_release"
  fi

  if [[ ! -e "$target_release" ]]; then
    mv -T -- "$extracted" "$target_release"
    RELEASE_TARGET_CREATED=true
    sync -f "$RELEASES_ROOT"
  fi
  install_stable_dispatcher_create_only \
    "$target_release/ops/deploy/backup/backup-dispatch.sh"
  remove_image_quietly "$API_ARCHIVE_TAG"
  remove_image_quietly "$POSTGRES_ARCHIVE_TAG"
  API_ARCHIVE_TAG=''
  POSTGRES_ARCHIVE_TAG=''
  [[ "$(sha256sum -- "$BACKUP_DISPATCH_PATH" | awk '{print $1}')" == \
     "$(sha256sum -- "$target_release/ops/deploy/backup/backup-dispatch.sh" | awk '{print $1}')" ]] ||
    fail 'stable backup dispatcher differs from installed release'
  RELEASE_COMMITTED=true
  note "installed immutable release $release_id"
  printf '%s\n' "$release_id"
}

main "$@"
