#!/usr/bin/env bash
set -euo pipefail
umask 077

die() {
  printf 'run-syft-sbom: %s\n' "$*" >&2
  exit 1
}

script_dir="$(CDPATH='' cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
toolchain_file="$script_dir/../compliance/toolchain.env"

archive=""
output=""
while (($# > 0)); do
  case "$1" in
    --archive) (($# >= 2)) || die 'missing --archive value'; archive=$2; shift 2 ;;
    --output) (($# >= 2)) || die 'missing --output value'; output=$2; shift 2 ;;
    *) die "unknown argument: $1" ;;
  esac
done
[[ -n "$archive" && -n "$output" ]] || die 'usage: run-syft-sbom.sh --archive IMAGE.docker.tar --output SBOM.json'

for command_name in basename chmod dirname docker mktemp mv node realpath rm stat; do
  command -v "$command_name" >/dev/null 2>&1 || die "missing command: $command_name"
done
[[ -f "$toolchain_file" && ! -L "$toolchain_file" ]] || die 'reviewed toolchain file is missing'

SYFT_IMAGE=''
SYFT_PLATFORM=''
declare -A seen=()
while IFS='=' read -r key value || [[ -n "$key$value" ]]; do
  [[ -z "$key" || "$key" == \#* ]] && continue
  [[ -z "${seen[$key]+x}" ]] || die "duplicate toolchain key: $key"
  seen[$key]=1
  case "$key" in
    SYFT_IMAGE) SYFT_IMAGE=$value ;;
    SYFT_PLATFORM) SYFT_PLATFORM=$value ;;
    CYCLONEDX_SPEC_VERSION|CYCLONEDX_NPM_VERSION|CYCLONEDX_NPM_INTEGRITY|CYCLONEDX_VALIDATOR_AJV_VERSION|CYCLONEDX_VALIDATOR_FORMATS_VERSION|CYCLONEDX_VALIDATOR_DRAFT2019_VERSION|CYCLONEDX_MAVEN_PLUGIN_VERSION|MAVEN_DEPENDENCY_PLUGIN_VERSION) ;;
    *) die "unexpected toolchain key: $key" ;;
  esac
done <"$toolchain_file"
[[ "$SYFT_PLATFORM" == 'linux/amd64' ]] || die 'Syft platform is not the reviewed linux/amd64 target'
[[ "$SYFT_IMAGE" =~ ^docker\.io/anchore/syft:v[0-9]+\.[0-9]+\.[0-9]+@sha256:[0-9a-f]{64}$ ]] ||
  die 'Syft image must be an exact version and linux/amd64 manifest digest'
[[ "$SYFT_IMAGE" != *latest* ]] || die 'floating Syft tags are forbidden'

[[ -f "$archive" && ! -L "$archive" ]] || die 'docker-save archive must be a regular non-symlink file'
archive="$(realpath -e -- "$archive")"
[[ "$archive" == *.tar ]] || die 'Syft interface accepts an uncompressed docker-save tar archive only'
output_parent="$(realpath -e -- "$(dirname -- "$output")")"
output="$output_parent/$(basename -- "$output")"
[[ ! -e "$output" && ! -L "$output" ]] || die 'output already exists'

image_json="$(docker image inspect --format '{{json .}}' "$SYFT_IMAGE" 2>/dev/null)" ||
  die 'exact Syft image is not preloaded; pull it in a separately reviewed networked step'
[[ -n "$image_json" ]] || die 'Docker returned no Syft image metadata'

raw="$(mktemp -- "$output_parent/.syft-raw.XXXXXXXXXX")"
normalized="$(mktemp -- "$output_parent/.syft-normalized.XXXXXXXXXX")"
cleanup() {
  rm -f -- "$raw" "$normalized"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

docker run --rm \
  --pull never \
  --network none \
  --platform "$SYFT_PLATFORM" \
  --read-only \
  --tmpfs /tmp:rw,noexec,nosuid,nodev,size=1g,mode=1777 \
  --cap-drop ALL \
  --security-opt no-new-privileges:true \
  --user 0:0 \
  -e HOME=/tmp \
  -e TMPDIR=/tmp \
  -e SYFT_CACHE_DIR=/tmp/syft-cache \
  -e SYFT_CHECK_FOR_APP_UPDATE=false \
  -v "$archive:/input/image.docker.tar:ro" \
  "$SYFT_IMAGE" \
  'docker-archive:/input/image.docker.tar' \
  --output cyclonedx-json >"$raw"

[[ "$(stat -c '%s' -- "$raw")" -gt 0 ]] || die 'Syft produced an empty SBOM'
node "$script_dir/normalize-cyclonedx.mjs" --input "$raw" --output "$normalized"
chmod 0600 -- "$normalized"
mv -T -- "$normalized" "$output"
trap - EXIT HUP INT TERM
cleanup
printf 'run-syft-sbom: wrote %s\n' "$output" >&2
