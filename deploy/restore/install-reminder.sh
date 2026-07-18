#!/usr/bin/env bash
set -euo pipefail
set +x
umask 077

SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
readonly SCRIPT_DIRECTORY
readonly SERVICE_NAME='portfolio-restore-reminder.service'
readonly TIMER_NAME='portfolio-restore-reminder.timer'

fail() {
  printf 'Restore reminder installation failed: %s\n' "$1" >&2
  exit 1
}

protected_unit() {
  local path="$1" mode owner
  [[ "$path" == /* && -f "$path" && ! -L "$path" ]] ||
    fail 'reminder unit source is missing, relative, or linked'
  mode="$(stat -Lc '%a' -- "$path")"
  owner="$(stat -Lc '%u' -- "$path")"
  [[ "$owner" == 0 && $((8#$mode & 0022)) -eq 0 ]] ||
    fail 'reminder unit source must be root-owned and not writable by group or other'
}

main() {
  (($# == 0)) || fail 'install-reminder.sh accepts no arguments'
  ((EUID == 0)) || fail 'reminder installation must run as root'

  local source_directory="$SCRIPT_DIRECTORY/../systemd"
  local target_directory='/etc/systemd/system'
  local systemctl_command='systemctl'
  if [[ "${RESTORE_REMINDER_CONTRACT_FIXTURE:-false}" == true && -f /.dockerenv ]]; then
    source_directory="${RESTORE_REMINDER_SOURCE_DIRECTORY:-$source_directory}"
    target_directory="${RESTORE_REMINDER_TARGET_DIRECTORY:-$target_directory}"
    systemctl_command="${RESTORE_SYSTEMCTL_COMMAND:-$systemctl_command}"
  elif [[ -n "${RESTORE_REMINDER_SOURCE_DIRECTORY:-}${RESTORE_REMINDER_TARGET_DIRECTORY:-}${RESTORE_SYSTEMCTL_COMMAND:-}" ]]; then
    fail 'reminder installation overrides are restricted to the isolated contract fixture'
  fi

  source_directory="$(realpath -e -- "$source_directory")" || fail 'reminder unit source directory is unavailable'
  [[ -d "$source_directory" && ! -L "$source_directory" ]] || fail 'reminder unit source directory is invalid'
  mkdir -p -- "$target_directory"
  target_directory="$(realpath -e -- "$target_directory")" || fail 'reminder target directory is unavailable'
  [[ -d "$target_directory" && ! -L "$target_directory" ]] || fail 'reminder target directory is invalid'
  local service_source="$source_directory/$SERVICE_NAME"
  local timer_source="$source_directory/$TIMER_NAME"
  protected_unit "$service_source"
  protected_unit "$timer_source"
  command -v systemd-analyze >/dev/null 2>&1 || fail 'systemd-analyze is unavailable'
  if [[ "$systemctl_command" == */* ]]; then
    [[ "$systemctl_command" == /* && -f "$systemctl_command" && ! -L "$systemctl_command" && -x "$systemctl_command" ]] ||
      fail 'systemctl command override is invalid'
  else
    systemctl_command="$(command -v -- "$systemctl_command" 2>/dev/null || true)"
    [[ -n "$systemctl_command" ]] || fail 'systemctl is unavailable'
  fi

  systemd-analyze verify "$service_source" "$timer_source" >/dev/null ||
    fail 'reminder units failed systemd verification'
  install -o root -g root -m 0644 -- "$service_source" "$target_directory/$SERVICE_NAME"
  install -o root -g root -m 0644 -- "$timer_source" "$target_directory/$TIMER_NAME"
  systemd-analyze verify "$target_directory/$SERVICE_NAME" "$target_directory/$TIMER_NAME" >/dev/null ||
    fail 'installed reminder units failed systemd verification'
  "$systemctl_command" daemon-reload
  "$systemctl_command" enable --now "$TIMER_NAME"
  "$systemctl_command" is-enabled --quiet "$TIMER_NAME"
  "$systemctl_command" is-active --quiet "$TIMER_NAME"
  "$systemctl_command" list-timers --all "$TIMER_NAME" --no-pager
  "$systemctl_command" status "$TIMER_NAME" --no-pager
}

main "$@"
