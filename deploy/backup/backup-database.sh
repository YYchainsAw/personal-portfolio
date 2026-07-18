#!/usr/bin/env bash
set -euo pipefail
set +x
umask 077

SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=deploy/backup/lib.sh
source "$SCRIPT_DIRECTORY/lib.sh"

snapshot_fd=''
output=''
list_output=''
metadata_output=''

while (($#)); do
  case "$1" in
    --snapshot-fd) snapshot_fd="$2"; shift 2 ;;
    --output) output="$2"; shift 2 ;;
    --list-output) list_output="$2"; shift 2 ;;
    --metadata-output) metadata_output="$2"; shift 2 ;;
    *) backup_fail 'usage: backup-database.sh --snapshot-fd FD --output FILE --list-output FILE --metadata-output FILE' ;;
  esac
done

[[ "$snapshot_fd" =~ ^[0-9]+$ && "$snapshot_fd" -ge 3 ]] ||
  backup_fail 'snapshot file descriptor is invalid'
for path in "$output" "$list_output" "$metadata_output"; do
  [[ "$path" == /* && ! -e "$path" && ! -L "$path" ]] ||
    backup_fail 'database output path must be absolute and new'
done

BACKUP_POSTGRES_CLIENT_COMMAND="${BACKUP_POSTGRES_CLIENT_COMMAND:-$SCRIPT_DIRECTORY/postgres-client.sh}"
backup_resolve_command BACKUP_POSTGRES_CLIENT_COMMAND postgres-client.sh

snapshot_id=''
IFS= read -r snapshot_id <&"$snapshot_fd" || backup_fail 'snapshot file descriptor is unreadable'
[[ "$snapshot_id" =~ ^[A-Za-z0-9-]{8,128}$ ]] || backup_fail 'snapshot identifier is invalid'

if ! "$BACKUP_POSTGRES_CLIENT_COMMAND" pg_dump \
    --format=custom \
    --no-owner \
    --no-privileges \
    --snapshot="$snapshot_id" >"$output" 2>/dev/null; then
  backup_fail 'database custom dump failed'
fi
[[ -s "$output" ]] || backup_fail 'database custom dump is empty'

if ! "$BACKUP_POSTGRES_CLIENT_COMMAND" pg_restore --list \
    <"$output" >"$list_output" 2>/dev/null; then
  backup_fail 'database custom dump list failed'
fi
[[ -s "$list_output" ]] || backup_fail 'database custom dump list is empty'

required_relations=(
  flyway_schema_history
  publication
  content_revision
  revision_media_reference
  media_asset
  media_variant
  contact_message
  admin_user
)
for relation in "${required_relations[@]}"; do
  grep -Eq "(^|[[:space:]])${relation}([[:space:]]|$)" "$list_output" ||
    backup_fail 'database custom dump omits a required relation'
done

dump_sha="$(backup_sha256_file "$output")"
dump_size="$(backup_file_size "$output")"
jq -S -c -n \
  --arg plaintextSha256 "$dump_sha" \
  --argjson byteSize "$dump_size" \
  '{schemaVersion:1,plaintextSha256:$plaintextSha256,byteSize:$byteSize}' \
  >"$metadata_output"
