#!/usr/bin/env bash
set -euo pipefail
set +x
umask 077

SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=deploy/backup/lib.sh
source "$SCRIPT_DIRECTORY/lib.sh"

payload="$(mktemp)"
trap 'rm -f -- "$payload"' EXIT HUP INT TERM
cat >"$payload"

jq -e '
  keys == ["category","finishedAt","schemaVersion","setId","startedAt"] and
  .schemaVersion == 1 and
  (.setId | type == "string" and test("^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{12}$")) and
  (.startedAt | type == "string" and
    test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")) and
  (.finishedAt | type == "string" and
    test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")) and
  (.category | IN(
    "CONFIGURATION_FAILED","SNAPSHOT_FAILED","DATABASE_FAILED","MEDIA_FAILED",
    "UPLOAD_FAILED","VERIFY_FAILED","MAINTENANCE_WRITE_FAILED",
    "NOTIFICATION_FAILED","INTERNAL_FAILED"))
' "$payload" >/dev/null || backup_fail 'failure notification payload is invalid'

backup_require_value BACKUP_MSMTP_CONFIG
backup_require_value BACKUP_EMAIL_TO
backup_require_value BACKUP_EMAIL_FROM
backup_require_private_file "$BACKUP_MSMTP_CONFIG" 'msmtp config'
[[ "$BACKUP_EMAIL_TO" =~ ^[^[:space:]@]+@[^[:space:]@]+$ ]] ||
  backup_fail 'backup notification recipient is invalid'
[[ "$BACKUP_EMAIL_FROM" =~ ^[^[:space:]@]+@[^[:space:]@]+$ ]] ||
  backup_fail 'backup notification sender is invalid'
backup_resolve_command BACKUP_MSMTP_COMMAND msmtp

set_id="$(jq -r '.setId' "$payload")"
started_at="$(jq -r '.startedAt' "$payload")"
finished_at="$(jq -r '.finishedAt' "$payload")"
category="$(jq -r '.category' "$payload")"

if ! {
  printf 'From: %s\r\n' "$BACKUP_EMAIL_FROM"
  printf 'To: %s\r\n' "$BACKUP_EMAIL_TO"
  printf 'Subject: Portfolio backup failed (%s)\r\n' "$category"
  printf 'Content-Type: text/plain; charset=UTF-8\r\n'
  printf '\r\n'
  printf 'The independent portfolio backup failed.\r\n'
  printf 'Set: %s\r\n' "$set_id"
  printf 'Category: %s\r\n' "$category"
  printf 'Started: %s\r\n' "$started_at"
  printf 'Finished: %s\r\n' "$finished_at"
  printf 'No credential, object identity, path, exception, or PII is included.\r\n'
} | "$BACKUP_MSMTP_COMMAND" --file "$BACKUP_MSMTP_CONFIG" --read-recipients \
    >/dev/null 2>&1; then
  backup_fail 'independent backup failure notification could not be delivered'
fi
