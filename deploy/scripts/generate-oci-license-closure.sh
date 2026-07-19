#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C
export TZ=UTC
umask 077

die() {
  printf 'generate-oci-license-closure: %s\n' "$*" >&2
  exit 1
}

script_dir="$(CDPATH='' cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
compliance_dir="$(realpath -e -- "$script_dir/../compliance")"
archive=''
config_digest=''
kind=''
output=''
backend_licenses=''
syft_raw_sbom=''

while (($# > 0)); do
  case "$1" in
    --archive) (($# >= 2)) || die 'missing --archive value'; archive=$2; shift 2 ;;
    --config-digest) (($# >= 2)) || die 'missing --config-digest value'; config_digest=$2; shift 2 ;;
    --kind) (($# >= 2)) || die 'missing --kind value'; kind=$2; shift 2 ;;
    --output) (($# >= 2)) || die 'missing --output value'; output=$2; shift 2 ;;
    --backend-licenses) (($# >= 2)) || die 'missing --backend-licenses value'; backend_licenses=$2; shift 2 ;;
    --syft-raw-sbom) (($# >= 2)) || die 'missing --syft-raw-sbom value'; syft_raw_sbom=$2; shift 2 ;;
    *) die "unknown argument: $1" ;;
  esac
done

[[ -n "$archive" && -n "$config_digest" && -n "$kind" && -n "$output" ]] ||
  die 'usage: --archive IMAGE.tar --config-digest sha256:... --kind api|postgres --output DIR [--backend-licenses DIR] [--syft-raw-sbom FILE]'
[[ "$kind" == api || "$kind" == postgres ]] || die 'kind must be api or postgres'
[[ "$config_digest" =~ ^sha256:[0-9a-f]{64}$ ]] || die 'config digest must be exact lowercase SHA-256'
if [[ "$kind" == api ]]; then
  [[ -n "$backend_licenses" ]] || die 'api kind requires --backend-licenses'
else
  [[ -z "$backend_licenses" ]] || die 'postgres kind does not accept --backend-licenses'
fi
for command_name in bash basename cp cut dirname find mkdir mktemp mv node python3 realpath rm sha256sum; do
  command -v "$command_name" >/dev/null 2>&1 || die "missing command: $command_name"
done
for required in \
  "$script_dir/extract-docker-image-rootfs.py" \
  "$script_dir/run-syft-sbom.sh" \
  "$script_dir/normalize-cyclonedx.mjs" \
  "$script_dir/collect-oci-image-licenses.mjs" \
  "$script_dir/node-production-closure.mjs" \
  "$script_dir/finalize-compliance-tree.sh" \
  "$compliance_dir/oci-legal-overrides.json"; do
  [[ -f "$required" && ! -L "$required" ]] || die "missing reviewed input: $required"
done
[[ -f "$archive" && ! -L "$archive" ]] || die 'archive must be a regular non-symlink file'
archive="$(realpath -e -- "$archive")"
if [[ -n "$syft_raw_sbom" ]]; then
  [[ -f "$syft_raw_sbom" && ! -L "$syft_raw_sbom" ]] ||
    die 'precomputed Syft SBOM must be a regular non-symlink file'
  syft_raw_sbom="$(realpath -e -- "$syft_raw_sbom")"
else
  command -v docker >/dev/null 2>&1 ||
    die 'Docker is required when a precomputed Syft SBOM is not supplied'
fi
if [[ -n "$backend_licenses" ]]; then
  [[ -d "$backend_licenses" && ! -L "$backend_licenses" ]] || die 'backend licenses must be a non-symlink directory'
  backend_licenses="$(realpath -e -- "$backend_licenses")"
  [[ -f "$backend_licenses/manifest.json" && -f "$backend_licenses/THIRD_PARTY_NOTICES.txt" ]] ||
    die 'backend license closure is incomplete'
fi

output_parent="$(realpath -e -- "$(dirname -- "$output")")"
output_name="$(basename -- "$output")"
[[ "$output_name" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ && "$output_name" != '.' && "$output_name" != '..' ]] ||
  die 'output basename is unsafe'
output="$output_parent/$output_name"
[[ ! -e "$output" && ! -L "$output" ]] || die 'output already exists'
staging="$(mktemp -d -- "$output_parent/.oci-legal.XXXXXXXXXX")"
cleanup() {
  [[ -n "${staging:-}" && -d "$staging" ]] && rm -rf --one-file-system -- "$staging"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

mkdir -p -- "$staging/rootfs" "$staging/result/legal"
archive_before="$(sha256sum -- "$archive" | cut -d ' ' -f 1)"
syft_before=''
if [[ -n "$syft_raw_sbom" ]]; then
  syft_before="$(sha256sum -- "$syft_raw_sbom" | cut -d ' ' -f 1)"
fi
python3 "$script_dir/extract-docker-image-rootfs.py" \
  --archive "$archive" \
  --expected-config-digest "$config_digest" \
  --rootfs "$staging/rootfs" \
  --metadata-output "$staging/result/image-metadata.json"
if [[ -n "$syft_raw_sbom" ]]; then
  node "$script_dir/normalize-cyclonedx.mjs" \
    --input "$syft_raw_sbom" \
    --output "$staging/result/sbom.cdx.json"
else
  bash "$script_dir/run-syft-sbom.sh" \
    --archive "$archive" \
    --output "$staging/result/sbom.cdx.json"
fi
collector=(
  node "$script_dir/collect-oci-image-licenses.mjs"
  --archive "$archive"
  --rootfs "$staging/rootfs"
  --sbom "$staging/result/sbom.cdx.json"
  --image-metadata "$staging/result/image-metadata.json"
  --kind "$kind"
  --output "$staging/result/legal"
  --overrides "$compliance_dir/oci-legal-overrides.json"
)
if [[ -n "$backend_licenses" ]]; then
  collector+=(--backend-licenses "$backend_licenses")
fi
"${collector[@]}"
archive_after="$(sha256sum -- "$archive" | cut -d ' ' -f 1)"
[[ "$archive_before" == "$archive_after" ]] || die 'archive changed during offline analysis'
if [[ -n "$syft_raw_sbom" ]]; then
  [[ "$syft_before" == "$(sha256sum -- "$syft_raw_sbom" | cut -d ' ' -f 1)" ]] ||
    die 'precomputed Syft SBOM changed during offline analysis'
fi
rm -rf --one-file-system -- "$staging/rootfs"
bash "$script_dir/finalize-compliance-tree.sh" --tree "$staging/result"
mv -T -- "$staging/result" "$output"
trap - EXIT HUP INT TERM
cleanup
printf 'generate-oci-license-closure: published %s\n' "$output" >&2
