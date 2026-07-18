#!/usr/bin/env bash
set -euo pipefail
set +x
umask 027

export LC_ALL=C

die() {
  printf 'package-bootstrap-kit: %s\n' "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"
}

[[ $# -eq 2 ]] || die 'usage: package-bootstrap-kit.sh RELEASE_DIRECTORY OUTPUT_DIRECTORY'
for command_name in awk chmod find install jq mktemp mv python3 realpath rm sha256sum sort stat sync tar zstd; do
  require_command "$command_name"
done

release_input="$1"
output_input="$2"
[[ "$release_input" == /* && "$release_input" != / && -d "$release_input" &&
   ! -L "$release_input" ]] || die 'release directory must be an absolute non-linked directory'
release_dir="$(realpath -e -- "$release_input")"
[[ -f "$release_dir/release.json" && ! -L "$release_dir/release.json" ]] ||
  die 'release.json is missing or linked'
git_commit="$(jq -er '.gitCommit | select(type == "string" and test("^[0-9a-f]{40}$"))' \
  "$release_dir/release.json")" || die 'release.json has no canonical Git commit'

declare -a source_paths=(
  'ops/deploy/scripts/install-bootstrap-kit.py'
  'ops/deploy/scripts/install-backup-units.sh'
  'ops/deploy/scripts/install-release-bundle.sh'
  'ops/deploy/scripts/promote-release-upload.sh'
  'ops/deploy/scripts/provision-local-volume.sh'
  'ops/deploy/scripts/validate-bundle-tar.py'
  'ops/deploy/scripts/verify-compliance-tree.py'
)
declare -a target_paths=(
  'libexec/portfolio/install-bootstrap-kit.py'
  'libexec/portfolio/install-backup-units.sh'
  'libexec/portfolio/install-release-bundle.sh'
  'libexec/portfolio/promote-release-upload.sh'
  'libexec/portfolio/provision-local-volume.sh'
  'libexec/portfolio/validate-bundle-tar.py'
  'libexec/portfolio/verify-compliance-tree.py'
)
declare -a target_modes=('0644' '0755' '0755' '0755' '0755' '0644' '0644')

for path in "${source_paths[@]}"; do
  [[ -f "$release_dir/$path" && ! -L "$release_dir/$path" ]] ||
    die "trusted bootstrap input is missing or linked: $path"
done

install -d -m 0750 -- "$output_input"
output_root="$(realpath -e -- "$output_input")"
[[ "$output_root" != / ]] || die 'output directory must not be the filesystem root'
case "$output_root/" in
  "$release_dir"/*) die 'output directory must not be inside the immutable release' ;;
esac

kit_name="portfolio-bootstrap-$git_commit.tar.zst"
envelope_name="$kit_name.envelope.json"
bootstrap_installer_name="install-bootstrap-kit-$git_commit.py"
final_directory="$output_root/portfolio-bootstrap-$git_commit"
work_directory="$(mktemp -d -- "$output_root/.bootstrap-$git_commit.XXXXXX")"
work_directory="$(realpath -e -- "$work_directory")"
case "$work_directory" in
  "$output_root"/.bootstrap-"$git_commit".*) ;;
  *) die 'bootstrap work directory escaped its reviewed parent' ;;
esac

cleanup() {
  if [[ -n "${work_directory:-}" && -d "$work_directory" ]]; then
    case "$(realpath -e -- "$work_directory" 2>/dev/null || true)" in
      "$output_root"/.bootstrap-"$git_commit".*) rm -rf -- "$work_directory" ;;
      *) printf 'package-bootstrap-kit: refusing to remove unexpected work directory\n' >&2 ;;
    esac
  fi
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

validate_kit() {
  local archive="$1" envelope="$2" installer="$3" validation_tar="$work_directory/validate.tar"
  [[ -f "$archive" && ! -L "$archive" && -f "$envelope" && ! -L "$envelope" &&
     -f "$installer" && ! -L "$installer" ]] ||
    die 'bootstrap archive, envelope, and standalone installer must be regular non-linked files'
  jq -e --arg commit "$git_commit" --arg archive_name "$kit_name" \
    --arg installer_name "$bootstrap_installer_name" '
    (keys | sort) == (["archiveBytes","archiveName","archiveSha256",
      "bootstrapInstallerBytes","bootstrapInstallerName","bootstrapInstallerSha256","gitCommit",
      "manifestSha256","schemaVersion"] | sort) and
    .schemaVersion == 1 and .gitCommit == $commit and .archiveName == $archive_name and
    .bootstrapInstallerName == $installer_name and
    (.archiveSha256 | type == "string" and test("^[0-9a-f]{64}$")) and
    (.bootstrapInstallerSha256 | type == "string" and test("^[0-9a-f]{64}$")) and
    (.manifestSha256 | type == "string" and test("^[0-9a-f]{64}$")) and
    (.archiveBytes | type == "number" and . > 0 and . == floor)
    and (.bootstrapInstallerBytes | type == "number" and . > 0 and . == floor)
  ' "$envelope" >/dev/null || die 'bootstrap envelope violates its exact contract'
  [[ "$(sha256sum -- "$archive" | awk '{print $1}')" == \
     "$(jq -r '.archiveSha256' "$envelope")" ]] || die 'bootstrap archive SHA-256 mismatch'
  [[ "$(stat -Lc '%s' -- "$archive")" == "$(jq -r '.archiveBytes' "$envelope")" ]] ||
    die 'bootstrap archive byte count mismatch'
  [[ "$(sha256sum -- "$installer" | awk '{print $1}')" == \
     "$(jq -r '.bootstrapInstallerSha256' "$envelope")" &&
     "$(stat -Lc '%s' -- "$installer")" == \
     "$(jq -r '.bootstrapInstallerBytes' "$envelope")" ]] ||
    die 'standalone bootstrap installer identity mismatch'
  zstd -q --test "$archive" || die 'bootstrap archive is not a valid zstd stream'
  zstd -q -dc "$archive" >"$validation_tar"
  python3 - "$validation_tar" "$git_commit" "$(jq -r '.manifestSha256' "$envelope")" <<'PY'
import hashlib
import json
import sys
import tarfile

tar_path, commit, expected_manifest_sha = sys.argv[1:]
root = f"portfolio-bootstrap-{commit}"
expected_specs = {
    "libexec/portfolio/install-bootstrap-kit.py": "0644",
    "libexec/portfolio/install-backup-units.sh": "0755",
    "libexec/portfolio/install-release-bundle.sh": "0755",
    "libexec/portfolio/promote-release-upload.sh": "0755",
    "libexec/portfolio/provision-local-volume.sh": "0755",
    "libexec/portfolio/validate-bundle-tar.py": "0644",
    "libexec/portfolio/verify-compliance-tree.py": "0644",
}
expected_directories = {root, f"{root}/libexec", f"{root}/libexec/portfolio"}
expected_regular = {f"{root}/bootstrap-manifest.json"} | {
    f"{root}/{path}" for path in expected_specs
}

with tarfile.open(tar_path, mode="r:") as archive:
    if archive.pax_headers:
        raise SystemExit("package-bootstrap-kit: global PAX headers are forbidden")
    members = archive.getmembers()
    names = [member.name.rstrip("/") for member in members]
    if len(names) != len(set(names)):
        raise SystemExit("package-bootstrap-kit: duplicate tar entry")
    if set(names) != expected_directories | expected_regular:
        raise SystemExit("package-bootstrap-kit: bootstrap tar entry set is not exact")
    by_name = {member.name.rstrip("/"): member for member in members}
    for name in expected_directories:
        member = by_name[name]
        if not member.isdir() or member.mode != 0o755:
            raise SystemExit(f"package-bootstrap-kit: invalid directory entry: {name}")
    for name in expected_regular:
        member = by_name[name]
        if not member.isreg():
            raise SystemExit(f"package-bootstrap-kit: non-regular file entry: {name}")
    for member in members:
        if member.pax_headers or member.uid != 0 or member.gid != 0 or member.mtime != 0:
            raise SystemExit(f"package-bootstrap-kit: non-canonical tar metadata: {member.name}")

    manifest_member = by_name[f"{root}/bootstrap-manifest.json"]
    if manifest_member.mode != 0o644:
        raise SystemExit("package-bootstrap-kit: manifest mode is not 0644")
    manifest_body = archive.extractfile(manifest_member).read()
    if hashlib.sha256(manifest_body).hexdigest() != expected_manifest_sha:
        raise SystemExit("package-bootstrap-kit: manifest SHA-256 mismatch")
    try:
        manifest = json.loads(manifest_body)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise SystemExit("package-bootstrap-kit: manifest is not valid UTF-8 JSON") from error
    if set(manifest) != {"files", "gitCommit", "installRoot", "schemaVersion"}:
        raise SystemExit("package-bootstrap-kit: manifest keys are not exact")
    if manifest["schemaVersion"] != 1 or manifest["gitCommit"] != commit:
        raise SystemExit("package-bootstrap-kit: manifest commit contract failed")
    if manifest["installRoot"] != "/usr/local/libexec/portfolio":
        raise SystemExit("package-bootstrap-kit: manifest install root is invalid")
    files = manifest["files"]
    if not isinstance(files, list) or [item.get("path") for item in files] != sorted(expected_specs):
        raise SystemExit("package-bootstrap-kit: manifest file list is not exact and sorted")
    for item in files:
        if set(item) != {"mode", "path", "sha256"}:
            raise SystemExit("package-bootstrap-kit: manifest file keys are not exact")
        path = item["path"]
        if item["mode"] != expected_specs[path]:
            raise SystemExit(f"package-bootstrap-kit: manifest mode mismatch: {path}")
        if not isinstance(item["sha256"], str) or len(item["sha256"]) != 64:
            raise SystemExit(f"package-bootstrap-kit: invalid manifest digest: {path}")
        member = by_name[f"{root}/{path}"]
        if member.mode != int(item["mode"], 8):
            raise SystemExit(f"package-bootstrap-kit: tar mode mismatch: {path}")
        body = archive.extractfile(member).read()
        if hashlib.sha256(body).hexdigest() != item["sha256"]:
            raise SystemExit(f"package-bootstrap-kit: file digest mismatch: {path}")
PY
  rm -f -- "$validation_tar"
}

if [[ -e "$final_directory" || -L "$final_directory" ]]; then
  [[ -d "$final_directory" && ! -L "$final_directory" ]] ||
    die 'existing bootstrap output is not a regular directory'
  actual_entries="$(find "$final_directory" -mindepth 1 -maxdepth 1 -printf '%f\n' | sort)"
  expected_entries="$(printf '%s\n' "$kit_name" "$envelope_name" "$bootstrap_installer_name" | sort)"
  [[ "$actual_entries" == "$expected_entries" ]] ||
    die 'partial or unexpected existing bootstrap output requires investigation'
  validate_kit "$final_directory/$kit_name" "$final_directory/$envelope_name" \
    "$final_directory/$bootstrap_installer_name"
  printf '%s\n' "$final_directory/$kit_name"
  exit 0
fi

tree="$work_directory/portfolio-bootstrap-$git_commit"
candidate_directory="$work_directory/candidate"
install -d -m 0755 -- "$tree/libexec/portfolio" "$candidate_directory"
for index in "${!source_paths[@]}"; do
  install -m "${target_modes[$index]}" -- "$release_dir/${source_paths[$index]}" \
    "$tree/${target_paths[$index]}"
done

files_json='[]'
for index in "${!target_paths[@]}"; do
  path="${target_paths[$index]}"
  files_json="$(jq -Sc \
    --arg path "$path" --arg mode "${target_modes[$index]}" \
    --arg sha256 "$(sha256sum -- "$tree/$path" | awk '{print $1}')" \
    '. + [{path:$path,mode:$mode,sha256:$sha256}] | sort_by(.path)' <<<"$files_json")"
done
jq -Scn --arg commit "$git_commit" --argjson files "$files_json" \
  '{schemaVersion:1,gitCommit:$commit,installRoot:"/usr/local/libexec/portfolio",files:$files}' \
  >"$tree/bootstrap-manifest.json"
chmod 0644 "$tree/bootstrap-manifest.json"

tar_path="$work_directory/bootstrap.tar"
candidate_archive="$candidate_directory/$kit_name"
candidate_envelope="$candidate_directory/$envelope_name"
candidate_installer="$candidate_directory/$bootstrap_installer_name"
tar -C "$work_directory" --sort=name --format=ustar --mtime='@0' \
  --owner=0 --group=0 --numeric-owner -cf "$tar_path" "portfolio-bootstrap-$git_commit"
zstd -q -T1 -19 "$tar_path" -o "$candidate_archive"
install -m 0640 -- "$release_dir/ops/deploy/scripts/install-bootstrap-kit.py" \
  "$candidate_installer"
jq -Scn \
  --arg gitCommit "$git_commit" \
  --arg archiveName "$kit_name" \
  --arg archiveSha256 "$(sha256sum -- "$candidate_archive" | awk '{print $1}')" \
  --argjson archiveBytes "$(stat -Lc '%s' -- "$candidate_archive")" \
  --arg bootstrapInstallerName "$bootstrap_installer_name" \
  --arg bootstrapInstallerSha256 "$(sha256sum -- "$candidate_installer" | awk '{print $1}')" \
  --argjson bootstrapInstallerBytes "$(stat -Lc '%s' -- "$candidate_installer")" \
  --arg manifestSha256 "$(sha256sum -- "$tree/bootstrap-manifest.json" | awk '{print $1}')" \
  '{schemaVersion:1,gitCommit:$gitCommit,archiveName:$archiveName,
    archiveSha256:$archiveSha256,archiveBytes:$archiveBytes,
    bootstrapInstallerName:$bootstrapInstallerName,
    bootstrapInstallerSha256:$bootstrapInstallerSha256,
    bootstrapInstallerBytes:$bootstrapInstallerBytes,
    manifestSha256:$manifestSha256}' \
  >"$candidate_envelope"
chmod 0640 "$candidate_archive" "$candidate_envelope" "$candidate_installer"
validate_kit "$candidate_archive" "$candidate_envelope" "$candidate_installer"

mv -T -- "$candidate_directory" "$final_directory"
sync -f "$output_root"
validate_kit "$final_directory/$kit_name" "$final_directory/$envelope_name" \
  "$final_directory/$bootstrap_installer_name"
printf '%s\n' "$final_directory/$kit_name"
