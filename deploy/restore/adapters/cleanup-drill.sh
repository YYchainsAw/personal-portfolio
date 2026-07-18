#!/usr/bin/env bash
set -euo pipefail
set +x
umask 077

SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=deploy/restore/adapters/adapter-lib.sh
source "$SCRIPT_DIRECTORY/adapter-lib.sh"

assert_no_labeled_resources() {
  local kind="$1"
  local label output
  # Check each binding independently.  Requiring both labels in one Docker
  # filter would miss a partially relabelled resource and could make a leaked
  # container/volume/network invisible to the cleanup proof.
  for label in \
    "com.docker.compose.project=$RESTORE_COMPOSE_PROJECT_NAME" \
    "com.yychainsaw.portfolio.restore.drill=$RESTORE_DRILL_ID"
  do
    local quiet_flag=-q
    [[ "$kind" != container ]] || quiet_flag=-aq
    output="$("$RESTORE_DOCKER_COMMAND" "$kind" ls "$quiet_flag" --filter "label=$label")" ||
      adapter_fail "Docker $kind label verification failed"
    [[ -z "$output" ]] || adapter_fail "Docker $kind resources remain after bounded cleanup"
  done
}

main() {
  local drill_id='' prefix='' config='' root='' remove_root=false purge_remote=''
  while (($#)); do
    case "$1" in
      --drill-id) drill_id="$2"; shift 2 ;;
      --prefix) prefix="$2"; shift 2 ;;
      --config) config="$2"; shift 2 ;;
      --root) root="$2"; shift 2 ;;
      --remove-root) remove_root="$2"; shift 2 ;;
      --purge-remote) purge_remote="$2"; shift 2 ;;
      *) adapter_fail 'cleanup received an unsupported argument' ;;
    esac
  done
  adapter_require_drill_context
  adapter_require_disposed_identity
  [[ "$drill_id" == "$RESTORE_DRILL_ID" && "$root" == "$RESTORE_ROOT" ]] ||
    adapter_fail 'cleanup context does not match the active drill'
  [[ "$prefix" == "drills/$RESTORE_DRILL_ID/" ]] ||
    adapter_fail 'cleanup prefix is not exactly bound to the drill UUID'
  [[ "$remove_root" == true || "$remove_root" == false ]] ||
    adapter_fail 'cleanup remove-root flag must be true or false'
  [[ "$purge_remote" == true || "$purge_remote" == false ]] ||
    adapter_fail 'cleanup purge-remote flag must be explicitly true or false'
  adapter_resolve_command RESTORE_DOCKER_COMMAND docker
  adapter_resolve_command RESTORE_RCLONE_COMMAND rclone
  adapter_require_private_file "$config" 'drill COS rclone config'
  adapter_require_value RESTORE_DRILL_COS_REMOTE
  adapter_parse_remote "$RESTORE_DRILL_COS_REMOTE" RESTORE_DRILL_COS_REMOTE
  adapter_require_value RESTORE_COMPOSE_FILE
  [[ "$RESTORE_COMPOSE_FILE" == /* && -f "$RESTORE_COMPOSE_FILE" ]] ||
    adapter_fail 'tracked restore Compose file is unavailable'

  PORTFOLIO_RESTORE_PHASE=cleanup-compose adapter_compose down \
    --volumes --remove-orphans --timeout 30 >/dev/null ||
    adapter_fail 'isolated Compose teardown failed'
  assert_no_labeled_resources container
  assert_no_labeled_resources volume
  assert_no_labeled_resources network

  if [[ "$purge_remote" == true ]]; then
    local remote="${RESTORE_DRILL_COS_REMOTE%/}/drills/$RESTORE_DRILL_ID"
    "$RESTORE_RCLONE_COMMAND" --config "$config" purge "$remote" \
      --retries 3 --low-level-retries 10 --contimeout 10s --timeout 5m ||
      adapter_fail 'bounded drill COS prefix cleanup failed'
    if "$RESTORE_RCLONE_COMMAND" --config "$config" lsf "$remote" --max-depth 1 2>/dev/null | grep -q .; then
      adapter_fail 'drill COS prefix is not empty after cleanup'
    fi
  fi

  if [[ "$remove_root" == true ]]; then
    [[ -f "$RESTORE_ROOT/.portfolio-restore-drill" &&
       "$(<"$RESTORE_ROOT/.portfolio-restore-drill")" == "$RESTORE_DRILL_ID" ]] ||
      adapter_fail 'restore root sentinel changed before cleanup'
    rm -rf --one-file-system -- "$RESTORE_ROOT" || adapter_fail 'bounded restore root cleanup failed'
    [[ ! -e "$RESTORE_ROOT" && ! -L "$RESTORE_ROOT" ]] ||
      adapter_fail 'restore root remains after bounded cleanup'
  fi
}

main "$@"
