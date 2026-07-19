#!/usr/bin/env bash
set -euo pipefail
set +x
umask 077

SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
GUARD="$SCRIPT_DIRECTORY/cos-prune-guard.py"
WRAPPER="$SCRIPT_DIRECTORY/${BASH_SOURCE[0]##*/}"
TEST_SOURCE_ROOT='/tmp/portfolio-cos-prune-test-source'

[[ ! -L "$WRAPPER" && -f "$WRAPPER" && ! -L "$GUARD" && -f "$GUARD" ]] || {
  printf '%s\n' 'portfolio COS prune guard implementation is unavailable' >&2
  exit 78
}
[[ "$(stat -Lc '%u:%h' -- "$WRAPPER")" == 0:1 &&
   "$(stat -Lc '%u:%h' -- "$GUARD")" == 0:1 ]] || {
  printf '%s\n' 'portfolio COS prune guard implementation ownership is unsafe' >&2
  exit 78
}
wrapper_mode="$(stat -Lc '%a' -- "$WRAPPER")"
guard_mode="$(stat -Lc '%a' -- "$GUARD")"
(( (8#$wrapper_mode & 8#022) == 0 && (8#$guard_mode & 8#022) == 0 )) || {
  printf '%s\n' 'portfolio COS prune guard implementation permissions are unsafe' >&2
  exit 78
}

case "${BACKUP_PRUNE_TEST_MODE:-}" in
  '') test_mode=false ;;
  1)
    [[ "$SCRIPT_DIRECTORY" == "$TEST_SOURCE_ROOT/deploy/backup" &&
       -d "$TEST_SOURCE_ROOT" && ! -L "$TEST_SOURCE_ROOT" &&
       "$(stat -Lc '%u:%a' -- "$TEST_SOURCE_ROOT")" == 0:700 ]] || {
      printf '%s\n' 'portfolio COS prune guard test mode is outside its source boundary' >&2
      exit 78
    }
    test_mode=true
    ;;
  *)
    printf '%s\n' 'portfolio COS prune guard test mode value is invalid' >&2
    exit 78
    ;;
esac

if [[ "$test_mode" == true ]]; then
  PYTHON="$(command -v python3 2>/dev/null)" || {
    printf '%s\n' 'python3 is required by the portfolio COS prune guard test fixture' >&2
    exit 78
  }
else
  PYTHON='/opt/portfolio/cos-prune-venv/bin/python3'
  PREFIX='/opt/portfolio/cos-prune-venv'
  [[ -d "$PREFIX" && ! -L "$PREFIX" && -x "$PYTHON" &&
     -f "$PREFIX/pyvenv.cfg" && ! -L "$PREFIX/pyvenv.cfg" ]] || {
    printf '%s\n' 'portfolio COS prune guard isolated runtime is unavailable' >&2
    exit 78
  }
  [[ "$(stat -Lc '%u:%a' -- "$PREFIX")" == 0:700 &&
     "$(stat -Lc '%u' -- "$(realpath -e -- "$PYTHON")")" == 0 ]] || {
    printf '%s\n' 'portfolio COS prune guard isolated runtime ownership is unsafe' >&2
    exit 78
  }
fi

exec "$PYTHON" -B "$GUARD" "$@"
