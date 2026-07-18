#!/usr/bin/env bash
set -euo pipefail
set +x
umask 077

fail() {
  printf 'portfolio PostgreSQL client failed: %s\n' "$1" >&2
  exit 1
}

[[ $# -ge 1 ]] || fail 'usage: postgres-client.sh psql|pg_dump|pg_restore [arguments]'
mode="$1"
shift

docker_command="${BACKUP_DOCKER_COMMAND:-$(command -v docker 2>/dev/null || true)}"
[[ "$docker_command" == /* && ! -L "$docker_command" && -x "$docker_command" ]] ||
  fail 'BACKUP_DOCKER_COMMAND must name an executable absolute file'

compose_file="${BACKUP_COMPOSE_FILE:-}"
[[ "$compose_file" == /* && ! -L "$compose_file" && -f "$compose_file" ]] ||
  fail 'BACKUP_COMPOSE_FILE must name the current marker-resolved production Compose file'

case "$mode" in
  psql)
    # shellcheck disable=SC2016
    exec "$docker_command" compose -f "$compose_file" exec -T postgres \
      sh -eu -c \
      'export PGPASSWORD="$POSTGRES_PASSWORD"; exec psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" "$@"' \
      portfolio-postgres-client "$@"
    ;;
  pg_dump)
    # shellcheck disable=SC2016
    exec "$docker_command" compose -f "$compose_file" exec -T postgres \
      sh -eu -c \
      'export PGPASSWORD="$POSTGRES_PASSWORD"; exec pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" "$@"' \
      portfolio-postgres-client "$@"
    ;;
  pg_restore)
    exec "$docker_command" compose -f "$compose_file" exec -T postgres \
      pg_restore "$@"
    ;;
  *) fail 'client mode must be psql, pg_dump, or pg_restore' ;;
esac
