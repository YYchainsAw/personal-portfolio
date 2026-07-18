#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C
export TZ=UTC
umask 077

die() {
  printf 'finalize-compliance-tree: %s\n' "$*" >&2
  exit 1
}

script_dir="$(CDPATH='' cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
tree=''
while (($# > 0)); do
  case "$1" in
    --tree) (($# >= 2)) || die 'missing --tree value'; tree=$2; shift 2 ;;
    *) die "unknown argument: $1" ;;
  esac
done
[[ -n "$tree" ]] || die 'usage: finalize-compliance-tree.sh --tree DIR'

for command_name in chmod dirname find mktemp mv node realpath rm sha256sum sort; do
  command -v "$command_name" >/dev/null 2>&1 || die "missing command: $command_name"
done
[[ -d "$tree" && ! -L "$tree" ]] || die 'tree must be a regular non-symlink directory'
tree="$(realpath -e -- "$tree")"
[[ ! -e "$tree/SHA256SUMS" && ! -L "$tree/SHA256SUMS" ]] || die 'SHA256SUMS already exists'
[[ -n "$(find "$tree" -type f -print -quit)" ]] || die 'tree has no payload files'

tree_parent="$(realpath -e -- "$(dirname -- "$tree")")"
sums_tmp="$(mktemp -- "$tree_parent/.compliance-sums.XXXXXXXXXX")"
complete=0
cleanup() {
  rm -f -- "$sums_tmp"
  if [[ "$complete" != 1 ]]; then
    rm -f -- "$tree/SHA256SUMS"
  fi
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

find "$tree" -type d -exec chmod 0700 {} +
find "$tree" -type f -exec chmod 0600 {} +
(
CDPATH='' cd -- "$tree"
  while IFS= read -r -d '' path; do
    sha256sum -- "${path#./}"
  done < <(find . -type f -print0 | sort -z)
) >"$sums_tmp"
mv -T -- "$sums_tmp" "$tree/SHA256SUMS"
chmod 0600 -- "$tree/SHA256SUMS"
node "$script_dir/verify-compliance-tree.mjs" --tree "$tree"

complete=1
trap - EXIT HUP INT TERM
printf 'finalize-compliance-tree: finalized %s\n' "$tree" >&2
