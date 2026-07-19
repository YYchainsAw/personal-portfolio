#!/usr/bin/env bash
set -euo pipefail
set +x
umask 077

export LC_ALL=C

fail() {
  printf 'portfolio upload promotion failed: %s\n' "$1" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "$1 is required"
}

[[ $# -eq 7 ]] || fail \
  'usage: promote-release-upload.sh BUNDLE_PART BUNDLE_SHA256 BUNDLE_BYTES ENVELOPE_PART ENVELOPE_SHA256 ENVELOPE_BYTES RELEASE_ID'
[[ "$(id -u)" -eq 0 ]] || fail 'upload promotion must run as root'
for command_name in awk cp dirname flock id install jq ln mktemp mv realpath rm sha256sum stat sync; do
  require_command "$command_name"
done

bundle_source="$1"
expected_bundle_sha="$2"
expected_bundle_bytes="$3"
envelope_source="$4"
expected_envelope_sha="$5"
expected_envelope_bytes="$6"
release_id="$7"
[[ "$release_id" =~ ^[0-9a-f]{12}-[0-9a-f]{12}$ ]] || fail 'release ID is invalid'
[[ "$expected_bundle_sha" =~ ^[0-9a-f]{64}$ &&
   "$expected_envelope_sha" =~ ^[0-9a-f]{64}$ ]] || fail 'expected SHA-256 is invalid'
[[ "$expected_bundle_bytes" =~ ^[1-9][0-9]*$ &&
   "$expected_envelope_bytes" =~ ^[1-9][0-9]*$ ]] || fail 'expected byte count is invalid'

portfolio_root="${PORTFOLIO_ROOT:-/opt/portfolio}"
drop_input="${PORTFOLIO_UPLOAD_DROP:-$portfolio_root/quarantine/drop}"
verify_input="${PORTFOLIO_UPLOAD_VERIFY_ROOT:-$portfolio_root/quarantine/verify}"
incoming_input="${PORTFOLIO_INCOMING_ROOT:-$portfolio_root/incoming}"
uploader_uid="${PORTFOLIO_UPLOAD_UID:-$(id -u portfolio-upload 2>/dev/null || true)}"
[[ "$uploader_uid" =~ ^[1-9][0-9]*$ ]] || fail 'dedicated non-root upload UID is unavailable'

validate_directory() {
  local input="$1" expected_uid="$2" expected_mode="$3" label="$4" resolved
  [[ "$input" == /* && "$input" != / && -d "$input" && ! -L "$input" ]] ||
    fail "$label is missing, linked, or not absolute"
  resolved="$(realpath -e -- "$input")"
  [[ "$resolved" == "$input" ]] || fail "$label is not canonical"
  [[ "$(stat -Lc '%u' -- "$resolved")" == "$expected_uid" &&
     "$(stat -Lc '%a' -- "$resolved")" == "$expected_mode" ]] ||
    fail "$label owner or mode is unsafe"
  printf '%s\n' "$resolved"
}

drop="$(validate_directory "$drop_input" "$uploader_uid" 700 'upload drop')"
verify_root="$(validate_directory "$verify_input" 0 700 'root verification directory')"
incoming="$(validate_directory "$incoming_input" 0 750 'root incoming directory')"
quarantine_root="$(dirname -- "$drop")"
[[ "$(dirname -- "$verify_root")" == "$quarantine_root" &&
   -d "$quarantine_root" && ! -L "$quarantine_root" &&
   "$(realpath -e -- "$quarantine_root")" == "$quarantine_root" &&
   "$(stat -Lc '%u:%g:%a' -- "$quarantine_root")" == 0:0:711 ]] ||
  fail 'quarantine root must be canonical root:root mode 0711 with sibling drop and verify directories'
[[ "$(stat -Lc '%d' -- "$drop")" == "$(stat -Lc '%d' -- "$verify_root")" ]] ||
  fail 'upload drop and root verification directory must share a filesystem'

validate_source() {
  local source="$1" label="$2" requested_parent canonical_parent base
  [[ "$source" == /* && -f "$source" && ! -L "$source" ]] ||
    fail "$label is missing, linked, or not absolute"
  requested_parent="$(dirname -- "$source")"
  canonical_parent="$(realpath -e -- "$requested_parent")"
  [[ "$requested_parent" == "$drop" && "$canonical_parent" == "$drop" ]] ||
    fail "$label is outside or non-canonical within the isolated upload drop"
  base="${source##*/}"
  [[ "$base" =~ ^\.[A-Za-z0-9._-]+\.part\.[A-Za-z0-9]{16,64}$ ]] ||
    fail "$label does not use a temporary upload name"
  [[ "$(stat -Lc '%u:%a:%h' -- "$source")" == "$uploader_uid:600:1" ]] ||
    fail "$label owner, mode, or link count is unsafe"
}

validate_source "$bundle_source" 'bundle upload'
validate_source "$envelope_source" 'envelope upload'
[[ "$bundle_source" != "$envelope_source" ]] || fail 'bundle and envelope source must differ'

lock_file="${PORTFOLIO_DEPLOY_LOCK_FILE:-/run/lock/portfolio/deploy.lock}"
[[ "$lock_file" == /* && "$lock_file" != / ]] || fail 'deployment lock path is invalid'
lock_parent="$(dirname -- "$lock_file")"
[[ -d "$lock_parent" && ! -L "$lock_parent" &&
   "$(realpath -e -- "$lock_parent")" == "$lock_parent" &&
   "$(stat -Lc '%u:%g' -- "$lock_parent")" == 0:0 ]] ||
  fail 'deployment lock parent is unsafe'
lock_parent_mode="$(stat -Lc '%a' -- "$lock_parent")"
(( (8#$lock_parent_mode & 8#022) == 0 )) || fail 'deployment lock parent is writable outside root'
if [[ ! -e "$lock_file" && ! -L "$lock_file" ]]; then
  (umask 077; set -C; : >"$lock_file") 2>/dev/null || true
fi
[[ -f "$lock_file" && ! -L "$lock_file" && "$(stat -Lc '%u' -- "$lock_file")" == 0 ]] ||
  fail 'deployment lock is unsafe'
lock_mode="$(stat -Lc '%a' -- "$lock_file")"
(( (8#$lock_mode & 8#022) == 0 )) || fail 'deployment lock is writable outside root'
exec 9<>"$lock_file"
[[ "$(stat -Lc '%d:%i' -- "$lock_file")" == "$(stat -Lc '%d:%i' -- /proc/self/fd/9)" ]] ||
  fail 'deployment lock descriptor identity changed'
flock -x 9

# The uploader owns the drop directory and can replace names while this
# process waits for the deployment lock. Revalidate every mutable boundary
# inside the lock; the post-move lstat checks below pin the objects only after
# they enter the root-only verification directory.
[[ "$(validate_directory "$drop_input" "$uploader_uid" 700 'upload drop')" == "$drop" &&
   "$(validate_directory "$verify_input" 0 700 'root verification directory')" == "$verify_root" &&
   "$(validate_directory "$incoming_input" 0 750 'root incoming directory')" == "$incoming" ]] ||
  fail 'upload directories changed while waiting for the deployment lock'
[[ "$(dirname -- "$drop")" == "$quarantine_root" &&
   "$(dirname -- "$verify_root")" == "$quarantine_root" &&
   -d "$quarantine_root" && ! -L "$quarantine_root" &&
   "$(realpath -e -- "$quarantine_root")" == "$quarantine_root" &&
   "$(stat -c '%u:%g:%a' -- "$quarantine_root")" == 0:0:711 &&
   "$(stat -c '%d' -- "$drop")" == "$(stat -c '%d' -- "$verify_root")" ]] ||
  fail 'upload quarantine topology changed while waiting for the deployment lock'
validate_source "$bundle_source" 'bundle upload'
validate_source "$envelope_source" 'envelope upload'

isolation_directory="$(mktemp -d -- "$verify_root/.promote-$release_id.XXXXXX")"
isolation_directory="$(realpath -e -- "$isolation_directory")"
case "$isolation_directory" in
  "$verify_root"/.promote-"$release_id".*) ;;
  *) fail 'verification work directory escaped its root' ;;
esac
bundle_isolated="$isolation_directory/bundle.upload"
envelope_isolated="$isolation_directory/envelope.upload"
bundle_candidate="$incoming/.portfolio-$release_id.bundle.install.$$"
envelope_candidate="$incoming/.portfolio-$release_id.envelope.install.$$"
bundle_final="$incoming/portfolio-$release_id.tar.zst"
envelope_final="$bundle_final.envelope.json"
bundle_published=false
envelope_published=false

cleanup() {
  local status="$?"
  trap - EXIT HUP INT TERM
  rm -f -- "$bundle_candidate" "$envelope_candidate"
  if [[ "$status" -ne 0 && "$bundle_published" == true ]]; then
    rm -f -- "$bundle_final"
  fi
  if [[ "$status" -ne 0 && "$envelope_published" == true ]]; then
    rm -f -- "$envelope_final"
  fi
  if [[ -n "${isolation_directory:-}" && -d "$isolation_directory" ]]; then
    case "$(realpath -e -- "$isolation_directory" 2>/dev/null || true)" in
      "$verify_root"/.promote-"$release_id".*) rm -rf -- "$isolation_directory" ;;
      *) printf 'portfolio upload promotion failed: refusing unexpected cleanup path\n' >&2 ;;
    esac
  fi
  exit "$status"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

[[ ! -e "$bundle_final" && ! -L "$bundle_final" &&
   ! -e "$envelope_final" && ! -L "$envelope_final" ]] ||
  fail 'final incoming release pair already exists; overwrite is forbidden'
mv -T -- "$bundle_source" "$bundle_isolated"
mv -T -- "$envelope_source" "$envelope_isolated"
[[ -f "$bundle_isolated" && ! -L "$bundle_isolated" &&
   -f "$envelope_isolated" && ! -L "$envelope_isolated" &&
   "$(stat -c '%F:%u:%a:%h' -- "$bundle_isolated")" == \
     "regular file:$uploader_uid:600:1" &&
   "$(stat -c '%F:%u:%a:%h' -- "$envelope_isolated")" == \
     "regular file:$uploader_uid:600:1" ]] ||
  fail 'isolated upload identity changed during transfer'

install -m 0600 -o root -g root -- "$bundle_isolated" "$bundle_candidate"
install -m 0600 -o root -g root -- "$envelope_isolated" "$envelope_candidate"
[[ "$(stat -Lc '%u:%g:%a:%h' -- "$bundle_candidate")" == 0:0:600:1 &&
   "$(stat -Lc '%u:%g:%a:%h' -- "$envelope_candidate")" == 0:0:600:1 ]] ||
  fail 'root-owned incoming candidates have unsafe metadata'
[[ "$(stat -Lc '%s' -- "$bundle_candidate")" == "$expected_bundle_bytes" &&
   "$(sha256sum -- "$bundle_candidate" | awk '{print $1}')" == "$expected_bundle_sha" ]] ||
  fail 'bundle candidate differs from the independent byte/hash record'
[[ "$(stat -Lc '%s' -- "$envelope_candidate")" == "$expected_envelope_bytes" &&
   "$(sha256sum -- "$envelope_candidate" | awk '{print $1}')" == "$expected_envelope_sha" ]] ||
  fail 'envelope candidate differs from the independent byte/hash record'
jq -e --arg release "$release_id" --arg sha "$expected_bundle_sha" \
  --argjson bytes "$expected_bundle_bytes" '
    (keys | sort) == (["archiveBytes","archiveSha256","releaseId","releaseJsonSha256"] | sort) and
    .releaseId == $release and .archiveSha256 == $sha and .archiveBytes == $bytes and
    (.releaseJsonSha256 | type == "string" and test("^[0-9a-f]{64}$"))
  ' "$envelope_candidate" >/dev/null || fail 'envelope does not bind the independently verified bundle'

sync -f "$bundle_candidate" "$envelope_candidate"
ln -- "$bundle_candidate" "$bundle_final"
bundle_published=true
ln -- "$envelope_candidate" "$envelope_final"
envelope_published=true
sync -f "$incoming"
rm -f -- "$bundle_candidate" "$envelope_candidate"
bundle_published=false
envelope_published=false
printf '%s\n%s\n' "$bundle_final" "$envelope_final"
