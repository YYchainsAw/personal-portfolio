#!/usr/bin/env bash
set -euo pipefail
set +x

export LC_ALL=C

readonly MINIMUM_JAMMY_APT_VERSION='2.4.11'
readonly SNAPSHOT_ORIGIN='https://snapshot.ubuntu.com/ubuntu'
readonly -a JAMMY_SUITES=(jammy jammy-updates jammy-backports jammy-security)
declare -a SOURCE_FILES=()

fail() {
  printf 'Ubuntu apt snapshot verification failed: %s\n' "$1" >&2
  exit 1
}

usage() {
  printf '%s\n' \
    'usage:' \
    '  verify-ubuntu-apt-snapshot.sh prepare SNAPSHOT_ID APT_ROOT' \
    '  verify-ubuntu-apt-snapshot.sh verify SNAPSHOT_ID UPDATE_LOG INDEX_TARGETS APT_ROOT LISTS_ROOT' >&2
  exit 64
}

require_snapshot_id() {
  local snapshot_id="$1"
  [[ "$snapshot_id" =~ ^[0-9]{8}T[0-9]{6}Z$ ]] ||
    fail 'snapshot ID must use YYYYMMDDTHHMMSSZ'
}

verify_apt_version() {
  local first_line apt_version
  command -v apt-get >/dev/null 2>&1 || fail 'apt-get is unavailable'
  command -v dpkg >/dev/null 2>&1 || fail 'dpkg is unavailable'
  first_line="$(apt-get --version | sed -n '1p')" || fail 'could not read the apt version'
  [[ "$first_line" =~ ^apt[[:space:]]+([^[:space:]]+) ]] ||
    fail 'apt-get returned an unrecognized version string'
  apt_version="${BASH_REMATCH[1]}"
  dpkg --compare-versions "$apt_version" ge "$MINIMUM_JAMMY_APT_VERSION" ||
    fail "Ubuntu 22.04 snapshot support requires apt >= $MINIMUM_JAMMY_APT_VERSION (found $apt_version)"
}

collect_list_sources() {
  local apt_root="$1" file
  SOURCE_FILES=()
  [[ -d "$apt_root" && ! -L "$apt_root" ]] || fail 'apt root must be a non-linked directory'
  if [[ -e "$apt_root/sources.list" || -L "$apt_root/sources.list" ]]; then
    [[ -f "$apt_root/sources.list" && ! -L "$apt_root/sources.list" ]] ||
      fail 'sources.list must be a regular non-linked file'
    SOURCE_FILES+=("$apt_root/sources.list")
  fi
  if [[ -d "$apt_root/sources.list.d" ]]; then
    [[ ! -L "$apt_root/sources.list.d" ]] || fail 'sources.list.d must not be a symbolic link'
    while IFS= read -r -d '' file; do
      [[ -f "$file" && ! -L "$file" ]] || fail 'apt source fragments must be regular non-linked files'
      SOURCE_FILES+=("$file")
    done < <(find "$apt_root/sources.list.d" -maxdepth 1 -type f -name '*.list' -print0 | sort -z)
    while IFS= read -r -d '' file; do
      if grep -Eq '^[[:space:]]*[^#[:space:]]' "$file"; then
        fail 'active deb822 apt sources are not supported by the reviewed Jammy snapshot gate'
      fi
    done < <(find "$apt_root/sources.list.d" -maxdepth 1 -type f -name '*.sources' -print0 | sort -z)
  fi
  ((${#SOURCE_FILES[@]} > 0)) || fail 'no one-line apt source files were found'
}

validate_snapshot_sources() {
  local snapshot_id="$1" apt_root="$2" expected line token uri suite file
  local -a fields
  local -A seen=()
  expected="$SNAPSHOT_ORIGIN/$snapshot_id"
  collect_list_sources "$apt_root"
  for file in "${SOURCE_FILES[@]}"; do
    while IFS= read -r line || [[ -n "$line" ]]; do
      line="${line#"${line%%[![:space:]]*}"}"
      [[ -z "$line" || "${line:0:1}" == '#' ]] && continue
      read -r -a fields <<<"$line"
      ((${#fields[@]} >= 4)) || fail "malformed active apt source in $file"
      token="${fields[0]}"
      [[ "$token" == deb ]] || fail "unsupported active apt source type '$token' in $file"
      uri="${fields[1]}"
      suite="${fields[2]}"
      [[ "$uri" == "$expected" ]] ||
        fail "active apt source is not bound directly to $expected"
      case "$suite" in
        jammy|jammy-updates|jammy-backports|jammy-security) seen["$suite"]=true ;;
        *) fail "unexpected Ubuntu suite '$suite' in the Jammy build image" ;;
      esac
    done <"$file"
  done
  for suite in "${JAMMY_SUITES[@]}"; do
    [[ "${seen[$suite]:-}" == true ]] || fail "required Ubuntu suite '$suite' is not enabled"
  done
}

prepare_sources() {
  local snapshot_id="$1" apt_root="$2" file expected
  expected="$SNAPSHOT_ORIGIN/$snapshot_id"
  collect_list_sources "$apt_root"
  for file in "${SOURCE_FILES[@]}"; do
    sed -E -i \
      "s#^([[:space:]]*deb[[:space:]]+)https?://(archive|security)\\.ubuntu\\.com/ubuntu/?([[:space:]]+)#\\1${expected}\\3#" \
      "$file"
  done
  validate_snapshot_sources "$snapshot_id" "$apt_root"
}

verify_update_evidence() {
  local snapshot_id="$1" update_log="$2" targets_file="$3" apt_root="$4" lists_root="$5"
  local expected suite line site identifier filename extra expected_list
  local -A target_suites=()
  expected="$SNAPSHOT_ORIGIN/$snapshot_id"
  validate_snapshot_sources "$snapshot_id" "$apt_root"
  [[ -f "$update_log" && ! -L "$update_log" && -s "$update_log" ]] ||
    fail 'apt update evidence must be a non-empty regular file'
  [[ -f "$targets_file" && ! -L "$targets_file" && -s "$targets_file" ]] ||
    fail 'apt index-target evidence must be a non-empty regular file'
  [[ -d "$lists_root" && ! -L "$lists_root" ]] ||
    fail 'apt lists root must be a non-linked directory'

  if grep -Eq 'https?://(archive|security)\.ubuntu\.com/ubuntu' "$update_log"; then
    fail 'apt update contacted a live Ubuntu archive outside the reviewed snapshot'
  fi
  for suite in "${JAMMY_SUITES[@]}"; do
    grep -F -- "$expected $suite InRelease" "$update_log" >/dev/null ||
      fail "apt update did not fetch '$suite' from snapshot $snapshot_id"
  done

  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ -n "$line" ]] || continue
    IFS='|' read -r site suite identifier filename extra <<<"$line"
    [[ -n "$site" && -n "$suite" && -n "$identifier" && -n "$filename" && -z "${extra:-}" ]] ||
      fail 'apt index-target evidence contains a malformed row'
    [[ "$site" == "$expected" ]] ||
      fail "apt index target came from '$site' instead of '$expected'"
    case "$suite" in
      jammy|jammy-updates|jammy-backports|jammy-security) target_suites["$suite"]=true ;;
      *) fail "apt index target used unexpected suite '$suite'" ;;
    esac
  done <"$targets_file"

  for suite in "${JAMMY_SUITES[@]}"; do
    [[ "${target_suites[$suite]:-}" == true ]] ||
      fail "apt index targets did not materialize '$suite' from snapshot $snapshot_id"
    expected_list="$lists_root/snapshot.ubuntu.com_ubuntu_${snapshot_id}_dists_${suite}_InRelease"
    [[ -f "$expected_list" && ! -L "$expected_list" && -s "$expected_list" ]] ||
      fail "apt lists do not contain the exact snapshot InRelease for '$suite'"
  done
  if find "$lists_root" -maxdepth 1 -type f \
      \( -name 'archive.ubuntu.com_*' -o -name 'security.ubuntu.com_*' \) \
      -print -quit | grep -q .; then
    fail 'apt lists contain live Ubuntu archive indexes outside the reviewed snapshot'
  fi
}

main() {
  (($# >= 1)) || usage
  local mode="$1"
  shift
  case "$mode" in
    prepare)
      (($# == 2)) || usage
      require_snapshot_id "$1"
      verify_apt_version
      prepare_sources "$1" "$2"
      ;;
    verify)
      (($# == 5)) || usage
      require_snapshot_id "$1"
      verify_apt_version
      verify_update_evidence "$1" "$2" "$3" "$4" "$5"
      ;;
    *) usage ;;
  esac
}

main "$@"
