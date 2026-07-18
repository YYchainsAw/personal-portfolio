#!/usr/bin/env bash
set -euo pipefail
set +x
umask 077

# Fail-closed provider adapter skeleton. It intentionally authorizes nothing.
# Replace it with an independently reviewed Tencent COS implementation that
# satisfies README.md; never change this example to call generic rclone delete.

case "${1:-}" in
  verify-destination|review-candidate|delete-reviewed)
    printf '%s\n' \
      'portfolio prune guard is not installed; version/Object-Lock/version-ID review is mandatory' >&2
    exit 78
    ;;
  *)
    printf '%s\n' \
      'usage: prune-guard.example.sh verify-destination|review-candidate|delete-reviewed ...' >&2
    exit 64
    ;;
esac
