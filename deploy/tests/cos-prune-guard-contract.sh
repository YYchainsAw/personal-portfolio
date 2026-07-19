#!/usr/bin/env bash
set -euo pipefail
set +x
umask 077

export LC_ALL=C

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
GUARD_SOURCE="$REPOSITORY_ROOT/deploy/backup/prune-guard.example.sh"
PYTHON_GUARD_SOURCE="$REPOSITORY_ROOT/deploy/backup/cos-prune-guard.py"
PROVIDER_SOURCE_REPOSITORY="$REPOSITORY_ROOT/deploy/tests/cos-prune-api-fixture.py"
TEST_SOURCE_ROOT='/tmp/portfolio-cos-prune-test-source'
TEST_FIXTURE_ROOT='/tmp/portfolio-cos-prune-tests'
GUARD="$TEST_SOURCE_ROOT/deploy/backup/prune-guard.example.sh"
PYTHON_GUARD="$TEST_SOURCE_ROOT/deploy/backup/cos-prune-guard.py"
PROVIDER_SOURCE="$TEST_SOURCE_ROOT/deploy/tests/cos-prune-api-fixture.py"
WORK_DIRECTORY=''
CASE_ROOT=''
STATE=''
PROPOSAL=''
DELETE_SET=''
CURRENT_SET=''
DELETE_BLOB=''
DELETE_UPLOAD=''
PROTECTED_BLOB=''
DELETE_SET_EVIDENCE=''
PROVIDER_COMMAND=''
RCLONE_CONFIG=''
declare -a GUARD_ENV=()
declare -a INVOCATION=()

fail() {
  printf 'COS prune guard contract failed: %s\n' "$1" >&2
  exit 1
}

cleanup() {
  if [[ -n "$WORK_DIRECTORY" && -d "$WORK_DIRECTORY" ]]; then
    rm -rf -- "$WORK_DIRECTORY"
  fi
  if [[ ! -L "$TEST_SOURCE_ROOT" && -d "$TEST_SOURCE_ROOT" &&
        "$(stat -Lc '%u' -- "$TEST_SOURCE_ROOT")" == "$(id -u)" ]]; then
    rm -rf -- "$TEST_SOURCE_ROOT"
  fi
}
trap cleanup EXIT HUP INT TERM

prepare_case() {
  local name="$1"
  CASE_ROOT="$WORK_DIRECTORY/$name"
  mkdir -m 0700 -- "$CASE_ROOT"
  STATE="$CASE_ROOT/state.json"
  python3 "$PROVIDER_SOURCE" --init "$STATE" "$CASE_ROOT"

  PROVIDER_COMMAND="$CASE_ROOT/provider-command"
  # The generated wrapper expands these variables when the guard invokes it.
  # shellcheck disable=SC2016
  printf '%s\n' '#!/usr/bin/env bash' \
    'exec python3 "$FIXTURE_COS_PROVIDER_SOURCE" "$@"' >"$PROVIDER_COMMAND"
  chmod 0700 "$PROVIDER_COMMAND"
  RCLONE_CONFIG="$CASE_ROOT/rclone-pruner.conf"
  printf '%s\n' 'offline-pruner-config-MUST-NOT-LEAK' >"$RCLONE_CONFIG"
  chmod 0600 "$RCLONE_CONFIG"

  local metadata="$CASE_ROOT/metadata.json"
  PROPOSAL="$(jq -r '.proposal' "$metadata")"
  DELETE_SET="$(jq -r '.deleteSet' "$metadata")"
  CURRENT_SET="$(jq -r '.currentSet' "$metadata")"
  DELETE_BLOB="$(jq -r '.deleteBlob' "$metadata")"
  DELETE_UPLOAD="$(jq -r '.deleteUpload' "$metadata")"
  PROTECTED_BLOB="$(jq -r '.protectedBlob' "$metadata")"
  DELETE_SET_EVIDENCE="$(jq -r '.deleteSetEvidence' "$metadata")"

  GUARD_ENV=(
    'BACKUP_REMOTE=portfolio-backup:backup-bucket'
    'BACKUP_PREFIX=production'
    'BACKUP_DESTINATION_ACCOUNT_ID=backup-account'
    'BACKUP_DESTINATION_BUCKET=backup-bucket'
    'BACKUP_DESTINATION_REGION=ap-guangzhou'
    'BACKUP_PRUNE_PRINCIPAL_ID=backup-pruner'
    "BACKUP_PRUNE_RCLONE_CONFIG=$RCLONE_CONFIG"
    'BACKUP_OPERATION_LOCK_HELD=true'
    'BACKUP_RETENTION_SAFETY_DAYS=14'
    'BACKUP_COS_OBJECT_LOCK_MINIMUM_DAYS=14'
    'BACKUP_PRUNE_TEST_MODE=1'
    "BACKUP_COS_PRUNE_API_COMMAND=$PROVIDER_COMMAND"
    "FIXTURE_COS_PROVIDER_SOURCE=$PROVIDER_SOURCE"
    "FIXTURE_COS_STATE=$STATE"
  )
  INVOCATION=(
    --remote portfolio-backup:backup-bucket
    --prefix production
    --account backup-account
    --bucket backup-bucket
    --region ap-guangzhou
    --principal backup-pruner
    --rclone-config "$RCLONE_CONFIG"
  )
}

set_fault() {
  python3 "$PROVIDER_SOURCE" --fault "$STATE" "$1"
}

mutate_state() {
  python3 "$PROVIDER_SOURCE" --mutate "$STATE" "$1"
}

run_success() {
  local expected="$1"
  local label="$2"
  shift 2
  local stdout="$CASE_ROOT/$label.stdout"
  local stderr="$CASE_ROOT/$label.stderr"
  if ! env "${GUARD_ENV[@]}" bash "$GUARD" "$@" >"$stdout" 2>"$stderr"; then
    sed -n '1,120p' "$stderr" >&2
    fail "$label unexpectedly failed"
  fi
  [[ "$(tr -d '\r\n' <"$stdout")" == "$expected" ]] ||
    fail "$label did not return exactly $expected"
  [[ ! -s "$stderr" ]] || fail "$label emitted unexpected diagnostics"
}

expect_failure() {
  local label="$1"
  shift
  local log="$CASE_ROOT/$label.failure.log"
  if env "${GUARD_ENV[@]}" bash "$GUARD" "$@" >"$log" 2>&1; then
    fail "$label unexpectedly passed"
  fi
  if grep -Ex '(SAFE|DELETED)' "$log" >/dev/null; then
    fail "$label emitted an authorization token while failing"
  fi
  if grep -F 'fixture-secret-MUST-NOT-LEAK' "$log" >/dev/null; then
    fail "$label exposed provider diagnostics"
  fi
}

review_set() {
  local ticket="$1"
  run_success SAFE review-set review-candidate \
    --kind set --relative-path "sets/$DELETE_SET" \
    --evidence "$DELETE_SET_EVIDENCE" --proposal "$PROPOSAL" \
    --ticket-output "$ticket" "${INVOCATION[@]}"
}

review_blob() {
  local ticket="$1"
  run_success SAFE review-blob review-candidate \
    --kind blob --relative-path "$DELETE_BLOB" \
    --evidence "$PROPOSAL" --proposal "$PROPOSAL" \
    --ticket-output "$ticket" "${INVOCATION[@]}"
}

review_upload() {
  local ticket="$1"
  run_success SAFE review-upload review-candidate \
    --kind upload --relative-path "uploading/$DELETE_UPLOAD" \
    --evidence "$PROPOSAL" --proposal "$PROPOSAL" \
    --ticket-output "$ticket" "${INVOCATION[@]}"
}

delete_with_ticket() {
  local expected="$1"
  local label="$2"
  local kind="$3"
  local relative="$4"
  local evidence="$5"
  local ticket="$6"
  local ticket_sha
  ticket_sha="$(sha256sum "$ticket" | awk '{print $1}')"
  run_success "$expected" "$label" delete-reviewed \
    --kind "$kind" --relative-path "$relative" --evidence "$evidence" \
    --proposal "$PROPOSAL" --ticket "$ticket" --ticket-sha256 "$ticket_sha" \
    "${INVOCATION[@]}"
}

expect_verify_fault() {
  local fault="$1"
  prepare_case "verify-$fault"
  set_fault "$fault"
  expect_failure "verify-$fault" verify-destination "${INVOCATION[@]}"
}

expect_review_fault() {
  local fault="$1"
  local kind="${2:-set}"
  prepare_case "review-$fault-$kind"
  set_fault "$fault"
  if [[ "$kind" == set ]]; then
    expect_failure "review-$fault" review-candidate \
      --kind set --relative-path "sets/$DELETE_SET" \
      --evidence "$DELETE_SET_EVIDENCE" --proposal "$PROPOSAL" \
      --ticket-output "$CASE_ROOT/review.ticket" "${INVOCATION[@]}"
  else
    expect_failure "review-$fault" review-candidate \
      --kind blob --relative-path "$DELETE_BLOB" \
      --evidence "$PROPOSAL" --proposal "$PROPOSAL" \
      --ticket-output "$CASE_ROOT/review.ticket" "${INVOCATION[@]}"
  fi
  [[ ! -e "$CASE_ROOT/review.ticket" ]] ||
    fail "$fault created a review ticket despite failing closed"
}

test_destination_allowlist_and_lock_contract() {
  local fault
  for fault in \
    wrong-account wrong-bucket wrong-region wrong-principal root-principal \
    suspended-versioning no-object-lock governance-object-lock short-object-lock \
    legal-capability-changed secret-diagnostic
  do
    expect_verify_fault "$fault"
  done

  prepare_case verify-exact-cli-allowlist
  local -a wrong_invocation=("${INVOCATION[@]}")
  wrong_invocation[5]=other-account
  expect_failure verify-exact-cli-allowlist verify-destination "${wrong_invocation[@]}"

  prepare_case verify-shared-lock
  if env "${GUARD_ENV[@]}" BACKUP_OPERATION_LOCK_HELD=false \
      bash "$GUARD" verify-destination "${INVOCATION[@]}" \
      >"$CASE_ROOT/no-lock.log" 2>&1; then
    fail 'guard accepted an invocation without the shared operation lock'
  fi
  grep -F 'shared backup/prune operation lock is not held' "$CASE_ROOT/no-lock.log" >/dev/null ||
    fail 'missing shared operation lock failed for an unexpected reason'
}

test_injection_boundaries() {
  prepare_case injection-boundaries
  chmod 0770 "$TEST_SOURCE_ROOT"
  if env BACKUP_PRUNE_TEST_MODE=1 python3 -B "$PYTHON_GUARD" runtime-check \
      >"$CASE_ROOT/writable-source-root.log" 2>&1; then
    chmod 0700 "$TEST_SOURCE_ROOT"
    fail 'group-writable test source root enabled the test seam'
  fi
  chmod 0700 "$TEST_SOURCE_ROOT"
  grep -F 'test source boundary' "$CASE_ROOT/writable-source-root.log" >/dev/null ||
    fail 'writable test source root failed for an unexpected reason'

  chmod 0720 "$PYTHON_GUARD"
  if env BACKUP_PRUNE_TEST_MODE=1 python3 -B "$PYTHON_GUARD" runtime-check \
      >"$CASE_ROOT/writable-guard.log" 2>&1; then
    chmod 0700 "$PYTHON_GUARD"
    fail 'group-writable guard source enabled the test seam'
  fi
  chmod 0700 "$PYTHON_GUARD"
  grep -F 'test source boundary' "$CASE_ROOT/writable-guard.log" >/dev/null ||
    fail 'writable guard source failed for an unexpected reason'

  chown 65534:65534 "$PYTHON_GUARD"
  if env BACKUP_PRUNE_TEST_MODE=1 python3 -B "$PYTHON_GUARD" runtime-check \
      >"$CASE_ROOT/wrong-owner-guard.log" 2>&1; then
    chown 0:0 "$PYTHON_GUARD"
    fail 'non-euid-owned guard source enabled the test seam'
  fi
  chown 0:0 "$PYTHON_GUARD"
  grep -F 'test source boundary' "$CASE_ROOT/wrong-owner-guard.log" >/dev/null ||
    fail 'wrong-owner guard source failed for an unexpected reason'

  local outside_provider="/tmp/cos-prune-provider-outside.$$"
  local log="$CASE_ROOT/outside-provider.log"
  printf '%s\n' '#!/usr/bin/env bash' 'exit 99' >"$outside_provider"
  chmod 0700 "$outside_provider"
  if env "${GUARD_ENV[@]}" BACKUP_COS_PRUNE_API_COMMAND="$outside_provider" \
      bash "$GUARD" verify-destination "${INVOCATION[@]}" >"$log" 2>&1; then
    rm -f -- "$outside_provider"
    fail 'custom provider outside the fixed test fixture root was accepted'
  fi
  rm -f -- "$outside_provider"
  grep -F 'test fixture boundary' "$log" >/dev/null ||
    fail 'outside custom provider failed for an unexpected reason'

  local copied_guard="$CASE_ROOT/cos-prune-guard.py"
  cp -- "$REPOSITORY_ROOT/deploy/backup/cos-prune-guard.py" "$copied_guard"
  chmod 0700 "$copied_guard"
  if env BACKUP_PRUNE_TEST_MODE=1 python3 -B "$copied_guard" runtime-check \
      >"$CASE_ROOT/copied-guard.log" 2>&1; then
    fail 'copied production-path guard accepted the test-mode environment seam'
  fi
  grep -F 'test source boundary' "$CASE_ROOT/copied-guard.log" >/dev/null ||
    fail 'copied guard test-mode rejection returned an unexpected diagnostic'

  local copied_wrapper="$CASE_ROOT/prune-guard.example.sh"
  cp -- "$GUARD" "$copied_wrapper"
  chmod 0700 "$copied_wrapper"
  if env BACKUP_PRUNE_TEST_MODE=1 bash "$copied_wrapper" runtime-check \
      >"$CASE_ROOT/copied-wrapper.log" 2>&1; then
    fail 'production-path wrapper accepted the test-mode environment seam'
  fi
  grep -F 'outside its source boundary' "$CASE_ROOT/copied-wrapper.log" >/dev/null ||
    fail 'copied wrapper test-mode rejection returned an unexpected diagnostic'
}

test_pagination_and_version_failures() {
  local fault
  for fault in \
    truncated-no-markers duplicate-page omit-page terminal-continuation-marker \
    race-second-snapshot historical-version delete-marker null-version-id \
    nonlatest-version readback-mismatch readback-wrong-version \
    protected-blob-missing protected-blob-size protected-blob-hash
  do
    expect_review_fault "$fault" set
  done
  expect_review_fault prefix-collision blob
}

test_retention_and_legal_hold_failures() {
  local fault
  for fault in active-retention governance-retention legal-hold-unknown server-clock-skew; do
    expect_review_fault "$fault" set
  done
}

test_current_reachable_and_path_rejection() {
  prepare_case protected-candidates
  expect_failure current-set review-candidate \
    --kind set --relative-path "sets/$CURRENT_SET" \
    --evidence "$CASE_ROOT/$CURRENT_SET.manifest.json" --proposal "$PROPOSAL" \
    --ticket-output "$CASE_ROOT/current.ticket" "${INVOCATION[@]}"
  expect_failure reachable-blob review-candidate \
    --kind blob --relative-path "$PROTECTED_BLOB" \
    --evidence "$PROPOSAL" --proposal "$PROPOSAL" \
    --ticket-output "$CASE_ROOT/reachable.ticket" "${INVOCATION[@]}"
  expect_failure traversal review-candidate \
    --kind set --relative-path 'sets/../production' \
    --evidence "$DELETE_SET_EVIDENCE" --proposal "$PROPOSAL" \
    --ticket-output "$CASE_ROOT/traversal.ticket" "${INVOCATION[@]}"
  expect_failure wildcard review-candidate \
    --kind blob --relative-path 'blobs/*' \
    --evidence "$PROPOSAL" --proposal "$PROPOSAL" \
    --ticket-output "$CASE_ROOT/wildcard.ticket" "${INVOCATION[@]}"
  [[ ! -e "$CASE_ROOT/current.ticket" && ! -e "$CASE_ROOT/reachable.ticket" &&
     ! -e "$CASE_ROOT/traversal.ticket" && ! -e "$CASE_ROOT/wildcard.ticket" ]] ||
    fail 'a protected or non-canonical candidate produced a ticket'
}

test_ticket_and_concurrent_change_rejection() {
  prepare_case ticket-tamper
  local ticket="$CASE_ROOT/set.ticket"
  review_set "$ticket"
  local ticket_sha
  ticket_sha="$(sha256sum "$ticket" | awk '{print $1}')"
  printf ' ' >>"$ticket"
  expect_failure ticket-tamper delete-reviewed \
    --kind set --relative-path "sets/$DELETE_SET" \
    --evidence "$DELETE_SET_EVIDENCE" --proposal "$PROPOSAL" \
    --ticket "$ticket" --ticket-sha256 "$ticket_sha" "${INVOCATION[@]}"

  prepare_case ticket-sha
  ticket="$CASE_ROOT/set.ticket"
  review_set "$ticket"
  expect_failure ticket-wrong-sha delete-reviewed \
    --kind set --relative-path "sets/$DELETE_SET" \
    --evidence "$DELETE_SET_EVIDENCE" --proposal "$PROPOSAL" \
    --ticket "$ticket" --ticket-sha256 "$(printf '0%.0s' {1..64})" \
    "${INVOCATION[@]}"

  prepare_case proposal-tamper
  ticket="$CASE_ROOT/set.ticket"
  review_set "$ticket"
  ticket_sha="$(sha256sum "$ticket" | awk '{print $1}')"
  printf ' ' >>"$PROPOSAL"
  expect_failure proposal-tamper delete-reviewed \
    --kind set --relative-path "sets/$DELETE_SET" \
    --evidence "$DELETE_SET_EVIDENCE" --proposal "$PROPOSAL" \
    --ticket "$ticket" --ticket-sha256 "$ticket_sha" "${INVOCATION[@]}"

  prepare_case candidate-race
  ticket="$CASE_ROOT/blob.ticket"
  review_blob "$ticket"
  ticket_sha="$(sha256sum "$ticket" | awk '{print $1}')"
  mutate_state add-candidate-version
  expect_failure candidate-race delete-reviewed \
    --kind blob --relative-path "$DELETE_BLOB" \
    --evidence "$PROPOSAL" --proposal "$PROPOSAL" \
    --ticket "$ticket" --ticket-sha256 "$ticket_sha" "${INVOCATION[@]}"

  prepare_case protected-race
  ticket="$CASE_ROOT/set.ticket"
  review_set "$ticket"
  ticket_sha="$(sha256sum "$ticket" | awk '{print $1}')"
  mutate_state change-protected-version
  expect_failure protected-race delete-reviewed \
    --kind set --relative-path "sets/$DELETE_SET" \
    --evidence "$DELETE_SET_EVIDENCE" --proposal "$PROPOSAL" \
    --ticket "$ticket" --ticket-sha256 "$ticket_sha" "${INVOCATION[@]}"

  prepare_case unreviewed-set-race
  ticket="$CASE_ROOT/set.ticket"
  review_set "$ticket"
  ticket_sha="$(sha256sum "$ticket" | awk '{print $1}')"
  mutate_state add-unreviewed-set
  expect_failure unreviewed-set-race delete-reviewed \
    --kind set --relative-path "sets/$DELETE_SET" \
    --evidence "$DELETE_SET_EVIDENCE" --proposal "$PROPOSAL" \
    --ticket "$ticket" --ticket-sha256 "$ticket_sha" "${INVOCATION[@]}"
}

test_delete_confirmation_and_readback_failures() {
  local fault ticket ticket_sha
  for fault in delete-noop delete-response-mismatch delete-marker-response post-delete-marker; do
    prepare_case "delete-$fault"
    ticket="$CASE_ROOT/set.ticket"
    review_set "$ticket"
    ticket_sha="$(sha256sum "$ticket" | awk '{print $1}')"
    set_fault "$fault"
    expect_failure "delete-$fault" delete-reviewed \
      --kind set --relative-path "sets/$DELETE_SET" \
      --evidence "$DELETE_SET_EVIDENCE" --proposal "$PROPOSAL" \
      --ticket "$ticket" --ticket-sha256 "$ticket_sha" "${INVOCATION[@]}"
  done
}

test_blob_reference_and_recreation_gates() {
  prepare_case blob-before-set
  local set_ticket="$CASE_ROOT/set.ticket"
  local blob_ticket="$CASE_ROOT/blob.ticket"
  review_set "$set_ticket"
  review_blob "$blob_ticket"
  local blob_ticket_sha
  blob_ticket_sha="$(sha256sum "$blob_ticket" | awk '{print $1}')"
  expect_failure blob-before-set delete-reviewed \
    --kind blob --relative-path "$DELETE_BLOB" \
    --evidence "$PROPOSAL" --proposal "$PROPOSAL" \
    --ticket "$blob_ticket" --ticket-sha256 "$blob_ticket_sha" "${INVOCATION[@]}"
  jq -e --arg key "production/$DELETE_BLOB" '
    (.objects | has($key)) and (.deleteLog | length == 0)
  ' "$STATE" >/dev/null || fail 'blob-before-set gate mutated the still-referenced blob'

  delete_with_ticket DELETED delete-set set "sets/$DELETE_SET" \
    "$DELETE_SET_EVIDENCE" "$set_ticket"
  set_fault recreate-set-after-blob-delete
  expect_failure blob-set-recreation delete-reviewed \
    --kind blob --relative-path "$DELETE_BLOB" \
    --evidence "$PROPOSAL" --proposal "$PROPOSAL" \
    --ticket "$blob_ticket" --ticket-sha256 "$blob_ticket_sha" "${INVOCATION[@]}"
  jq -e --arg prefix "production/sets/$DELETE_SET/" --arg blob "production/$DELETE_BLOB" '
    ([.objects | keys[] | select(startswith($prefix))] | length == 1) and
    (.objects | has($blob) | not)
  ' "$STATE" >/dev/null || fail 'post-delete set recreation fault was not exercised'
}

test_complete_exact_version_deletion() {
  prepare_case complete-delete
  run_success SAFE verify-destination verify-destination "${INVOCATION[@]}"
  local set_ticket="$CASE_ROOT/set.ticket"
  local blob_ticket="$CASE_ROOT/blob.ticket"
  review_set "$set_ticket"
  review_blob "$blob_ticket"
  delete_with_ticket DELETED delete-set set "sets/$DELETE_SET" \
    "$DELETE_SET_EVIDENCE" "$set_ticket"
  delete_with_ticket DELETED delete-blob blob "$DELETE_BLOB" "$PROPOSAL" "$blob_ticket"

  python3 - "$STATE" "$DELETE_SET" "$CURRENT_SET" "$DELETE_BLOB" "$PROTECTED_BLOB" <<'PY'
import json
import sys

state_path, delete_set, current_set, delete_blob, protected_blob = sys.argv[1:]
state = json.load(open(state_path, encoding="utf-8"))
objects = state["objects"]
old_prefix = f"production/sets/{delete_set}/"
current_prefix = f"production/sets/{current_set}/"
assert not any(key.startswith(old_prefix) for key in objects)
assert f"production/{delete_blob}" not in objects
assert len([key for key in objects if key.startswith(current_prefix)]) == 1
assert f"production/{protected_blob}" in objects
assert len([key for key in objects if key.startswith(f"production/uploading/{delete_set}/")]) == 5
assert len([key for key in objects if key.startswith(f"production/uploading/{current_set}/")]) == 5
assert len(state["deleteLog"]) == 2
assert state["deleteLog"][0]["key"] == old_prefix + "VERIFIED"
assert len({entry["versionId"] for entry in state["deleteLog"]}) == 2
assert state["counters"].get("setSnapshotStarts", 0) >= 2
for record in objects.values():
    for version in record["versions"]:
        assert version["entryType"] == "OBJECT"
        assert version["isLatest"] is True
PY
}

test_cross_process_upload_transaction_recovery() {
  prepare_case cross-process-upload-recovery
  local ticket="$CASE_ROOT/upload.ticket"
  local transaction="$ticket.transaction.json"
  review_upload "$ticket"
  local ticket_sha
  ticket_sha="$(sha256sum "$ticket" | awk '{print $1}')"
  set_fault fail-second-delete
  expect_failure upload-partial-first-process delete-reviewed \
    --kind upload --relative-path "uploading/$DELETE_UPLOAD" \
    --evidence "$PROPOSAL" --proposal "$PROPOSAL" \
    --ticket "$ticket" --ticket-sha256 "$ticket_sha" "${INVOCATION[@]}"
  jq -e '
    .status == "ACTIVE" and
    (.reviewedVersions | length == 2) and
    (.completedVersions | length == 1)
  ' "$transaction" >/dev/null ||
    fail 'first process did not persist its exact partial deletion progress'
  set_fault none
  GUARD_ENV+=("BACKUP_PRUNE_TEST_NOW=2099-01-01T00:00:00Z")
  delete_with_ticket DELETED upload-resumed-second-process upload \
    "uploading/$DELETE_UPLOAD" "$PROPOSAL" "$ticket"
  unset "GUARD_ENV[$((${#GUARD_ENV[@]} - 1))]"
  jq -e '
    .status == "COMPLETED" and
    (.reviewedVersions | length == 2) and
    (.completedVersions | length == 2)
  ' "$transaction" >/dev/null ||
    fail 'second process did not close the persistent exact-version transaction'
  jq -e --arg prefix "production/uploading/$DELETE_UPLOAD/" '
    [.objects | keys[] | select(startswith($prefix))] | length == 0
  ' "$STATE" >/dev/null || fail 'resumed upload transaction left remote objects behind'
}

test_transaction_state_file_rejection() {
  local ticket ticket_sha transaction target

  prepare_case transaction-symlink
  ticket="$CASE_ROOT/set.ticket"
  review_set "$ticket"
  transaction="$ticket.transaction.json"
  target="$CASE_ROOT/attacker-controlled.json"
  printf '%s\n' '{}' >"$target"
  chmod 0600 "$target"
  ln -s "$target" "$transaction"
  ticket_sha="$(sha256sum "$ticket" | awk '{print $1}')"
  expect_failure transaction-symlink delete-reviewed \
    --kind set --relative-path "sets/$DELETE_SET" \
    --evidence "$DELETE_SET_EVIDENCE" --proposal "$PROPOSAL" \
    --ticket "$ticket" --ticket-sha256 "$ticket_sha" "${INVOCATION[@]}"

  prepare_case transaction-hardlink
  ticket="$CASE_ROOT/set.ticket"
  transaction="$ticket.transaction.json"
  review_set "$ticket"
  ticket_sha="$(sha256sum "$ticket" | awk '{print $1}')"
  set_fault delete-noop
  expect_failure transaction-hardlink-create delete-reviewed \
    --kind set --relative-path "sets/$DELETE_SET" \
    --evidence "$DELETE_SET_EVIDENCE" --proposal "$PROPOSAL" \
    --ticket "$ticket" --ticket-sha256 "$ticket_sha" "${INVOCATION[@]}"
  ln "$transaction" "$CASE_ROOT/transaction-hardlink-copy.json"
  set_fault none
  expect_failure transaction-hardlink-resume delete-reviewed \
    --kind set --relative-path "sets/$DELETE_SET" \
    --evidence "$DELETE_SET_EVIDENCE" --proposal "$PROPOSAL" \
    --ticket "$ticket" --ticket-sha256 "$ticket_sha" "${INVOCATION[@]}"

  prepare_case transaction-truncated
  ticket="$CASE_ROOT/set.ticket"
  transaction="$ticket.transaction.json"
  review_set "$ticket"
  ticket_sha="$(sha256sum "$ticket" | awk '{print $1}')"
  set_fault delete-noop
  expect_failure transaction-truncated-create delete-reviewed \
    --kind set --relative-path "sets/$DELETE_SET" \
    --evidence "$DELETE_SET_EVIDENCE" --proposal "$PROPOSAL" \
    --ticket "$ticket" --ticket-sha256 "$ticket_sha" "${INVOCATION[@]}"
  : >"$transaction"
  set_fault none
  expect_failure transaction-truncated-resume delete-reviewed \
    --kind set --relative-path "sets/$DELETE_SET" \
    --evidence "$DELETE_SET_EVIDENCE" --proposal "$PROPOSAL" \
    --ticket "$ticket" --ticket-sha256 "$ticket_sha" "${INVOCATION[@]}"

  prepare_case expired-ticket-without-active-transaction
  ticket="$CASE_ROOT/set.ticket"
  review_set "$ticket"
  ticket_sha="$(sha256sum "$ticket" | awk '{print $1}')"
  GUARD_ENV+=("BACKUP_PRUNE_TEST_NOW=2099-01-01T00:00:00Z")
  expect_failure expired-ticket-without-active-transaction delete-reviewed \
    --kind set --relative-path "sets/$DELETE_SET" \
    --evidence "$DELETE_SET_EVIDENCE" --proposal "$PROPOSAL" \
    --ticket "$ticket" --ticket-sha256 "$ticket_sha" "${INVOCATION[@]}"
  unset "GUARD_ENV[$((${#GUARD_ENV[@]} - 1))]"
  [[ ! -e "$ticket.transaction.json" ]] ||
    fail 'an expired ticket created a deletion transaction'
}

main() {
  local command_name
  for command_name in awk bash chmod chown cp grep id install jq ln mktemp python3 rm sha256sum stat tr; do
    command -v "$command_name" >/dev/null 2>&1 || fail "$command_name is required"
  done
  [[ "$(id -u)" -eq 0 ]] || fail 'COS prune guard contract must run as root'
  [[ -f "$GUARD_SOURCE" && -f "$PYTHON_GUARD_SOURCE" &&
     -f "$PROVIDER_SOURCE_REPOSITORY" ]] ||
    fail 'COS prune guard implementation or offline provider fixture is missing'
  if [[ -e "$TEST_SOURCE_ROOT" || -L "$TEST_SOURCE_ROOT" ]]; then
    [[ ! -L "$TEST_SOURCE_ROOT" && -d "$TEST_SOURCE_ROOT" &&
       "$(stat -Lc '%u:%a' -- "$TEST_SOURCE_ROOT")" == "$(id -u):700" ]] ||
      fail 'stale COS prune test source root is unsafe'
    rm -rf -- "$TEST_SOURCE_ROOT"
  fi
  install -d -m 0700 -- "$TEST_SOURCE_ROOT"
  install -d -m 0700 -- "$TEST_SOURCE_ROOT/deploy" \
    "$TEST_SOURCE_ROOT/deploy/backup" "$TEST_SOURCE_ROOT/deploy/tests"
  install -m 0700 -- "$GUARD_SOURCE" "$GUARD"
  install -m 0700 -- "$PYTHON_GUARD_SOURCE" "$PYTHON_GUARD"
  install -m 0700 -- "$PROVIDER_SOURCE_REPOSITORY" "$PROVIDER_SOURCE"
  [[ "$(stat -Lc '%u:%a' -- "$TEST_SOURCE_ROOT")" == "$(id -u):700" &&
     "$(stat -Lc '%u:%h:%a' -- "$GUARD")" == "$(id -u):1:700" &&
     "$(stat -Lc '%u:%h:%a' -- "$PYTHON_GUARD")" == "$(id -u):1:700" ]] ||
    fail 'staged COS prune test source boundary is unsafe'
  install -d -m 0700 -- "$TEST_FIXTURE_ROOT"
  [[ ! -L "$TEST_FIXTURE_ROOT" &&
     "$(stat -Lc '%u:%a' -- "$TEST_FIXTURE_ROOT")" == "$(id -u):700" ]] ||
    fail 'fixed COS prune test fixture root is unsafe'
  WORK_DIRECTORY="$(mktemp -d "$TEST_FIXTURE_ROOT/run.XXXXXX")"
  chmod 0700 "$WORK_DIRECTORY"
  test_injection_boundaries
  test_destination_allowlist_and_lock_contract
  test_pagination_and_version_failures
  test_retention_and_legal_hold_failures
  test_current_reachable_and_path_rejection
  test_ticket_and_concurrent_change_rejection
  test_delete_confirmation_and_readback_failures
  test_blob_reference_and_recreation_gates
  test_complete_exact_version_deletion
  test_cross_process_upload_transaction_recovery
  test_transaction_state_file_rejection
  printf '%s\n' 'COS prune guard contract passed'
}

main "$@"
