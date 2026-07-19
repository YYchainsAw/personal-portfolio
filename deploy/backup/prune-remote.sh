#!/usr/bin/env bash
set -euo pipefail
set +x
umask 077
export LC_ALL=C

SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=deploy/backup/lib.sh
source "$SCRIPT_DIRECTORY/lib.sh"

dry_run=false
bootstrap_dry_run=false
case "$#:$*" in
  0:) ;;
  '1:--dry-run') dry_run=true ;;
  *) backup_fail 'usage: prune-remote.sh [--dry-run]' ;;
esac

for forbidden_name in \
  BACKUP_AGE_IDENTITY AGE_SECRET_KEY COS_SECRET_ID COS_SECRET_KEY \
  PORTFOLIO_DB_RUNTIME_PASSWORD POSTGRES_PASSWORD SMTP_PASSWORD
do
  [[ -z "${!forbidden_name:-}" ]] ||
    backup_fail 'database, media-body, decrypt, and notification credentials are forbidden in the pruner service'
done

for command_name in awk dirname flock grep install jq mkdir mktemp mv realpath sed sha256sum sort stat sync tr wc; do
  command -v "$command_name" >/dev/null 2>&1 || backup_fail "$command_name is required"
done
backup_resolve_command BACKUP_RCLONE_COMMAND rclone
backup_resolve_command BACKUP_DATE_COMMAND date
backup_resolve_command BACKUP_OPENSSL_COMMAND openssl
BACKUP_PRUNE_GUARD_COMMAND="${BACKUP_PRUNE_GUARD_COMMAND:-$SCRIPT_DIRECTORY/prune-guard.example.sh}"
backup_resolve_command BACKUP_PRUNE_GUARD_COMMAND prune-guard.example.sh
backup_validate_topology prune

BACKUP_RETENTION_SAFETY_DAYS="${BACKUP_RETENTION_SAFETY_DAYS:-14}"
[[ "$BACKUP_RETENTION_SAFETY_DAYS" =~ ^[1-9][0-9]{0,3}$ ]] ||
  backup_fail 'BACKUP_RETENTION_SAFETY_DAYS is invalid'
backup_acquire_operation_lock
BACKUP_PRUNE_STAGING_ROOT="${BACKUP_PRUNE_STAGING_ROOT:-/var/backups/portfolio/prune}"
[[ "$BACKUP_PRUNE_STAGING_ROOT" == /* && "$BACKUP_PRUNE_STAGING_ROOT" != / &&
   ! -L "$BACKUP_PRUNE_STAGING_ROOT" ]] || backup_fail 'prune staging root is unsafe'
backup_ensure_private_directory "$BACKUP_PRUNE_STAGING_ROOT" 'prune staging root'
BACKUP_PRUNE_TRANSACTION_ROOT="${BACKUP_PRUNE_TRANSACTION_ROOT:-/var/backups/portfolio/prune-transactions}"
[[ "$BACKUP_PRUNE_TRANSACTION_ROOT" == /* && "$BACKUP_PRUNE_TRANSACTION_ROOT" != / &&
   "$BACKUP_PRUNE_TRANSACTION_ROOT" != "$BACKUP_PRUNE_STAGING_ROOT" &&
   ! -L "$BACKUP_PRUNE_TRANSACTION_ROOT" ]] ||
  backup_fail 'persistent prune transaction root is unsafe'
backup_ensure_private_directory "$BACKUP_PRUNE_TRANSACTION_ROOT" \
  'persistent prune transaction root'
work_directory="$(mktemp -d "$BACKUP_PRUNE_STAGING_ROOT/prune.XXXXXX")"
chmod 0700 "$work_directory"
transaction_build_directory=''
cleanup_prune_work() {
  rm -rf -- "$work_directory"
  if [[ -n "$transaction_build_directory" &&
        "$transaction_build_directory" == "$BACKUP_PRUNE_TRANSACTION_ROOT"/.new.* &&
        ! -L "$transaction_build_directory" && -d "$transaction_build_directory" ]]; then
    rm -rf -- "$transaction_build_directory"
  fi
}
trap cleanup_prune_work EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
mkdir -m 0700 "$work_directory/manifests"

transaction_destination_json() {
  jq -S -c -n \
    --arg accountId "$BACKUP_DESTINATION_ACCOUNT_ID" \
    --arg bucket "$BACKUP_DESTINATION_BUCKET" \
    --arg region "$BACKUP_DESTINATION_REGION" \
    --arg prefix "$BACKUP_PREFIX" \
    --arg principalId "$BACKUP_PRUNE_PRINCIPAL_ID" \
    '{accountId:$accountId,bucket:$bucket,region:$region,prefix:$prefix,principalId:$principalId}'
}

transaction_destination_digest() {
  transaction_destination_json | sha256sum | awk '{print $1}'
}

validate_transaction_directory() {
  local directory="$1"
  local directory_name plan plan_size proposal_sha destination_digest candidate_digest expected_name
  backup_require_private_directory "$directory" 'persistent prune transaction directory'
  directory_name="${directory##*/}"
  [[ "$directory_name" =~ ^[0-9a-f]{64}-[0-9a-f]{64}-[0-9a-f]{64}$ ]] ||
    backup_fail 'persistent prune transaction directory name is invalid'
  plan="$directory/transaction-plan.json"
  backup_require_private_file "$plan" 'persistent prune transaction plan'
  plan_size="$(stat -Lc '%s' -- "$plan")" ||
    backup_fail 'persistent prune transaction plan size cannot be read'
  ((plan_size > 0 && plan_size <= 2 * 1024 * 1024)) ||
    backup_fail 'persistent prune transaction plan is invalid'
  jq -e --argjson destination "$(transaction_destination_json)" '
    keys == ["candidateDigest","candidates","destination","destinationDigest","proposalSha256","schemaVersion"] and
    .schemaVersion == 1 and .destination == $destination and
    (.destinationDigest | type == "string" and test("^[0-9a-f]{64}$")) and
    (.proposalSha256 | type == "string" and test("^[0-9a-f]{64}$")) and
    (.candidateDigest | type == "string" and test("^[0-9a-f]{64}$")) and
    (.candidates | type == "array" and length > 0) and
    all(.candidates[];
      keys == ["evidence","kind","relativePath","ticket","ticketSha256"] and
      (.kind == "set" or .kind == "blob" or .kind == "upload") and
      (.relativePath | type == "string") and
      (.evidence | type == "string" and test("^(gc-report|evidence-set-[0-9]{8}T[0-9]{6}Z-[0-9a-f]{12})\\.json$")) and
      (.ticket | type == "string" and test("^ticket-[0-9a-f]{64}\\.json$")) and
      (.ticketSha256 | type == "string" and test("^[0-9a-f]{64}$"))) and
    (.candidates == (.candidates | sort_by(
      (if .kind == "set" then 0 elif .kind == "upload" then 1 else 2 end),
      .relativePath))) and
    ((.candidates | map(.kind + ":" + .relativePath)) as $candidateKeys |
      ($candidateKeys | length) == ($candidateKeys | unique | length)) and
    all(.candidates[];
      if .kind == "set" then (.relativePath | test("^sets/[0-9]{8}T[0-9]{6}Z-[0-9a-f]{12}$"))
      elif .kind == "upload" then (.relativePath | test("^uploading/[0-9]{8}T[0-9]{6}Z-[0-9a-f]{12}$"))
      else (.relativePath | test("^blobs/[0-9a-f]{64}$")) end)
  ' "$plan" >/dev/null || backup_fail 'persistent prune transaction plan is invalid'
  proposal_sha="$(jq -r '.proposalSha256' "$plan")"
  destination_digest="$(transaction_destination_digest)"
  [[ "$(jq -r '.destinationDigest' "$plan")" == "$destination_digest" ]] ||
    backup_fail 'persistent prune transaction destination digest changed'
  candidate_digest="$(jq -S -c '.candidates' "$plan" | sha256sum | awk '{print $1}')"
  [[ "$(jq -r '.candidateDigest' "$plan")" == "$candidate_digest" ]] ||
    backup_fail 'persistent prune transaction candidate digest changed'
  expected_name="$destination_digest-$proposal_sha-$candidate_digest"
  [[ "$directory_name" == "$expected_name" ]] ||
    backup_fail 'persistent prune transaction directory binding changed'
  backup_require_private_file "$directory/gc-report.json" \
    'persistent prune proposal'
  [[ "$(backup_sha256_file "$directory/gc-report.json")" == "$proposal_sha" ]] ||
    backup_fail 'persistent prune proposal checksum changed'

  local -A expected_files=(
    [transaction-plan.json]=1
    [gc-report.json]=1
  )
  local candidate evidence ticket ticket_sha path name
  while IFS= read -r candidate; do
    evidence="$(jq -r '.evidence' <<<"$candidate")"
    ticket="$(jq -r '.ticket' <<<"$candidate")"
    ticket_sha="$(jq -r '.ticketSha256' <<<"$candidate")"
    expected_files["$evidence"]=1
    expected_files["$ticket"]=1
    expected_files["$ticket.transaction.json"]=1
    backup_require_private_file "$directory/$evidence" \
      'persistent prune candidate evidence'
    backup_require_private_file "$directory/$ticket" \
      'persistent prune review ticket'
    [[ "$(backup_sha256_file "$directory/$ticket")" == "$ticket_sha" ]] ||
      backup_fail 'persistent prune review ticket checksum changed'
    if [[ -e "$directory/$ticket.transaction.json" ||
          -L "$directory/$ticket.transaction.json" ]]; then
      backup_require_private_file "$directory/$ticket.transaction.json" \
        'persistent exact-version deletion state'
    fi
  done < <(jq -c '.candidates[]' "$plan")
  shopt -s nullglob dotglob
  for path in "$directory"/*; do
    name="${path##*/}"
    [[ -n "${expected_files[$name]:-}" ]] ||
      backup_fail 'persistent prune transaction contains an unexpected entry'
    [[ ! -L "$path" && -f "$path" ]] ||
      backup_fail 'persistent prune transaction contains a non-regular entry'
  done
  shopt -u nullglob dotglob
}

run_transaction_candidates() {
  local directory="$1"
  local prepare_only="${2:-false}"
  local plan="$directory/transaction-plan.json"
  local candidate kind relative evidence ticket ticket_sha output expected
  while IFS= read -r candidate; do
    kind="$(jq -r '.kind' <<<"$candidate")"
    relative="$(jq -r '.relativePath' <<<"$candidate")"
    evidence="$(jq -r '.evidence' <<<"$candidate")"
    ticket="$(jq -r '.ticket' <<<"$candidate")"
    ticket_sha="$(jq -r '.ticketSha256' <<<"$candidate")"
    output="$work_directory/transaction-$(printf '%s' "$kind:$relative:$prepare_only" | sha256sum | awk '{print $1}').guard"
    local -a prepare_argument=()
    expected=DELETED
    if [[ "$prepare_only" == true ]]; then
      prepare_argument=(--prepare-only)
      expected=PREPARED
    fi
    if ! "$BACKUP_PRUNE_GUARD_COMMAND" delete-reviewed \
        --kind "$kind" \
        --relative-path "$relative" \
        --evidence "$directory/$evidence" \
        --proposal "$directory/gc-report.json" \
        --ticket "$directory/$ticket" \
        --ticket-sha256 "$ticket_sha" \
        "${prepare_argument[@]}" \
        --remote "$BACKUP_REMOTE" \
        --prefix "$BACKUP_PREFIX" \
        --account "$BACKUP_DESTINATION_ACCOUNT_ID" \
        --bucket "$BACKUP_DESTINATION_BUCKET" \
        --region "$BACKUP_DESTINATION_REGION" \
        --principal "$BACKUP_PRUNE_PRINCIPAL_ID" \
        --rclone-config "$BACKUP_PRUNE_RCLONE_CONFIG" >"$output" 2>/dev/null; then
      backup_fail 'persistent reviewed deletion transaction failed closed'
    fi
    [[ "$(tr -d '\r\n' <"$output")" == "$expected" ]] ||
      backup_fail 'persistent reviewed deletion transaction returned an invalid state'
  done < <(jq -c '.candidates[]' "$plan")
}

run_transaction_directory() {
  local directory="$1"
  local prepare_only="${2:-false}"
  validate_transaction_directory "$directory"
  run_transaction_candidates "$directory" "$prepare_only"
}

resume_pending_transactions() {
  local -a entries=()
  local entry
  shopt -s nullglob dotglob
  entries=("$BACKUP_PRUNE_TRANSACTION_ROOT"/*)
  shopt -u nullglob dotglob
  ((${#entries[@]} == 0)) && return 0
  for entry in "${entries[@]}"; do
    [[ ! -L "$entry" && -d "$entry" ]] ||
      backup_fail 'persistent prune transaction root contains an unsafe entry'
    if [[ "${entry##*/}" == .new.* || "${entry##*/}" == .done.* ]]; then
      backup_require_private_directory "$entry" 'incomplete prune transaction build directory'
      rm -rf -- "$entry"
      continue
    fi
    [[ "$dry_run" == false ]] ||
      backup_fail 'a pending exact-version deletion transaction must be resumed before dry-run'
    run_transaction_directory "$entry" false
    validate_transaction_directory "$entry"
    done_directory="$BACKUP_PRUNE_TRANSACTION_ROOT/.done.${entry##*/}"
    [[ ! -e "$done_directory" && ! -L "$done_directory" ]] ||
      backup_fail 'completed prune transaction cleanup name already exists'
    mv -- "$entry" "$done_directory"
    sync -f "$BACKUP_PRUNE_TRANSACTION_ROOT"
    rm -rf -- "$done_directory"
  done
}

readiness_binding_json() {
  local guard_sha requirements_sha wrapper_sha
  guard_sha="$(backup_sha256_file "$SCRIPT_DIRECTORY/cos-prune-guard.py")"
  requirements_sha="$(backup_sha256_file "$SCRIPT_DIRECTORY/requirements-cos-prune.txt")"
  wrapper_sha="$(backup_sha256_file "$SCRIPT_DIRECTORY/prune-guard.example.sh")"
  jq -S -c -n \
    --argjson destination "$(transaction_destination_json)" \
    --arg guardSha256 "$guard_sha" \
    --arg requirementsSha256 "$requirements_sha" \
    --arg wrapperSha256 "$wrapper_sha" \
    --argjson safetyDays "$BACKUP_RETENTION_SAFETY_DAYS" \
    '{destination:$destination,guardSha256:$guardSha256,
      requirementsSha256:$requirementsSha256,wrapperSha256:$wrapperSha256,
      policy:{daily:7,weekly:4,monthly:6,safetyDays:$safetyDays}}'
}

validate_or_select_initial_dry_run() {
  BACKUP_PRUNE_READINESS_FILE="${BACKUP_PRUNE_READINESS_FILE:-/var/backups/portfolio/prune-initial-dry-run.json}"
  [[ "$BACKUP_PRUNE_READINESS_FILE" == /* &&
     "$BACKUP_PRUNE_READINESS_FILE" != / &&
     ! -L "$BACKUP_PRUNE_READINESS_FILE" ]] ||
    backup_fail 'initial prune dry-run readiness path is unsafe'
  local parent
  parent="$(dirname -- "$BACKUP_PRUNE_READINESS_FILE")"
  backup_ensure_private_directory "$parent" 'initial prune dry-run readiness parent'
  if [[ -e "$BACKUP_PRUNE_READINESS_FILE" || -L "$BACKUP_PRUNE_READINESS_FILE" ]]; then
    backup_require_private_file "$BACKUP_PRUNE_READINESS_FILE" \
      'initial prune dry-run readiness evidence'
    jq -e --argjson binding "$(readiness_binding_json)" '
      keys == ["binding","completedAt","schemaVersion"] and
      .schemaVersion == 1 and .binding == $binding and
      (.completedAt | type == "string" and
        test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$"))
    ' "$BACKUP_PRUNE_READINESS_FILE" >/dev/null ||
      backup_fail 'initial prune dry-run readiness evidence is invalid or stale'
  elif [[ "$dry_run" == false ]]; then
    dry_run=true
    bootstrap_dry_run=true
  fi
}

commit_dry_run_readiness() {
  local parent temporary completed_at
  parent="$(dirname -- "$BACKUP_PRUNE_READINESS_FILE")"
  temporary="$(mktemp "$parent/.prune-ready.XXXXXX")"
  completed_at="$("$BACKUP_DATE_COMMAND" -u '+%Y-%m-%dT%H:%M:%SZ')"
  backup_is_rfc3339_utc "$completed_at" ||
    backup_fail 'initial prune dry-run readiness time is invalid'
  jq -S -c -n \
    --argjson binding "$(readiness_binding_json)" \
    --arg completedAt "$completed_at" \
    '{schemaVersion:1,completedAt:$completedAt,binding:$binding}' >"$temporary"
  chmod 0600 "$temporary"
  sync -f "$temporary"
  mv -T -- "$temporary" "$BACKUP_PRUNE_READINESS_FILE"
  sync -f "$parent"
  backup_require_private_file "$BACKUP_PRUNE_READINESS_FILE" \
    'initial prune dry-run readiness evidence'
}

guard_destination_output="$work_directory/destination.guard"
if ! "$BACKUP_PRUNE_GUARD_COMMAND" verify-destination \
    --remote "$BACKUP_REMOTE" \
    --prefix "$BACKUP_PREFIX" \
    --account "$BACKUP_DESTINATION_ACCOUNT_ID" \
    --bucket "$BACKUP_DESTINATION_BUCKET" \
    --region "$BACKUP_DESTINATION_REGION" \
    --principal "$BACKUP_PRUNE_PRINCIPAL_ID" \
    --rclone-config "$BACKUP_PRUNE_RCLONE_CONFIG" >"$guard_destination_output" 2>/dev/null; then
  backup_fail 'destination versioning, retention, prefix, or pruner role verification failed'
fi
[[ "$(tr -d '\r\n' <"$guard_destination_output")" == SAFE ]] ||
  backup_fail 'destination guard did not authorize the reviewed non-root prefix'

# Never issue a fresh proposal while an exact-version transaction is pending.
# Its immutable proposal/tickets remain authoritative until every reviewed
# version is confirmed absent and the transaction directory is removed.
resume_pending_transactions
validate_or_select_initial_dry_run

sets_listing="$work_directory/sets.list"
if ! backup_rclone "$BACKUP_PRUNE_RCLONE_CONFIG" lsf \
    --dirs-only --max-depth 1 -- "$(backup_remote_namespace_root sets)" \
    >"$sets_listing" 2>/dev/null; then
  backup_fail 'pruner could not list verified-set candidates'
fi

set_index="$work_directory/set-index.tsv"
: >"$set_index"
while IFS= read -r directory; do
  [[ -n "$directory" ]] || continue
  set_id="${directory%/}"
  backup_is_set_id "$set_id" || backup_fail 'set namespace contains an unexpected directory'
  [[ "$directory" == "$set_id/" ]] || backup_fail 'set directory listing is not canonical'
  manifest="$work_directory/manifests/$set_id.json"
  marker="$work_directory/manifests/$set_id.VERIFIED"
  if ! backup_rclone "$BACKUP_PRUNE_RCLONE_CONFIG" copyto -- \
      "$(backup_set_remote_path "$set_id" VERIFIED)" "$marker" >/dev/null 2>&1; then
    backup_fail 'pruner could not read a completed-set record'
  fi
  object_namespace=sets
  expected_manifest_sha=''
  if jq -e --arg setId "$set_id" '
      keys == ["manifestByteSize","manifestSha256","schemaVersion","setId","uploadPrefix"] and
      .schemaVersion == 2 and .setId == $setId and
      .uploadPrefix == ("uploading/" + $setId) and
      (.manifestSha256 | type == "string" and test("^[0-9a-f]{64}$")) and
      (.manifestByteSize | type == "number" and floor == . and . > 0)
    ' "$marker" >/dev/null 2>&1; then
    object_namespace=uploading
    expected_manifest_sha="$(jq -r '.manifestSha256' "$marker")"
  else
    [[ "$(wc -l <"$marker")" -eq 1 &&
       "$(tr -d '\r\n' <"$marker")" =~ ^[0-9a-f]{64}$ ]] ||
      backup_fail 'completed-set record is neither schema 2 nor a legacy marker'
    expected_manifest_sha="$(tr -d '\r\n' <"$marker")"
  fi
  manifest_remote="$(BACKUP_SET_OBJECT_NAMESPACE="$object_namespace" \
    backup_set_object_remote_path "$set_id" set-manifest.json)"
  if ! backup_rclone "$BACKUP_PRUNE_RCLONE_CONFIG" copyto -- \
      "$manifest_remote" \
      "$manifest" >/dev/null 2>&1; then
    backup_fail 'pruner could not read the completed set manifest'
  fi
  manifest_sha="$(backup_sha256_file "$manifest")"
  [[ "$expected_manifest_sha" == "$manifest_sha" ]] ||
    backup_fail 'completed-set record does not match its manifest'
  printf '%s\n' "$object_namespace" >"$work_directory/manifests/$set_id.namespace"
  jq -e --arg setId "$set_id" '
    .schemaVersion == 1 and .setId == $setId and
    (.backupFinishedAt | type == "string" and
      test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")) and
    (.retention | keys == ["daily","monthly","weekly"] and
      .daily == true and (.weekly | type == "boolean") and (.monthly | type == "boolean")) and
    (.blobs | type == "array") and
    all(.blobs[];
      (.path | type == "string" and test("^blobs/[0-9a-f]{64}$")) and
      (.sha256 | type == "string" and test("^[0-9a-f]{64}$")) and
      (.path == ("blobs/" + .sha256)) and
      (.byteSize | type == "number" and floor == . and . > 0)) and
    ((.blobs | map(.path)) == (.blobs | map(.path) | sort | unique))
  ' "$manifest" >/dev/null || backup_fail 'retention encountered an invalid self-contained set manifest'
  finished_at="$(jq -r '.backupFinishedAt' "$manifest")"
  daily="$(jq -r '.retention.daily' "$manifest")"
  weekly="$(jq -r '.retention.weekly' "$manifest")"
  monthly="$(jq -r '.retention.monthly' "$manifest")"
  printf '%s\t%s\t%s\t%s\t%s\n' \
    "$finished_at" "$set_id" "$daily" "$weekly" "$monthly" >>"$set_index"
done <"$sets_listing"

sort -r -o "$set_index" "$set_index"
declare -A retained=()
retain_class() {
  local column="$1"
  local limit="$2"
  local count=0 _finished set_id daily weekly monthly selected
  while IFS=$'\t' read -r _finished set_id daily weekly monthly; do
    [[ -n "$set_id" ]] || continue
    case "$column" in
      daily) selected="$daily" ;;
      weekly) selected="$weekly" ;;
      monthly) selected="$monthly" ;;
      *) backup_fail 'retention class is invalid' ;;
    esac
    if [[ "$selected" == true && "$count" -lt "$limit" ]]; then
      retained["$set_id"]=1
      ((count += 1))
    fi
  done <"$set_index"
}
retain_class daily 7
retain_class weekly 4
retain_class monthly 6

if ((${#retained[@]} == 0)); then
  [[ "${BACKUP_DISASTER_RESET_CONFIRMATION:-}" == I_UNDERSTAND_NO_VERIFIED_SET_WILL_REMAIN ]] ||
    backup_fail 'retained-set collection is empty; disaster-reset confirmation is required'
fi

retained_file="$work_directory/retained.txt"
printf '%s\n' "${!retained[@]}" | sed '/^$/d' | sort >"$retained_file"
now_epoch="$("$BACKUP_DATE_COMMAND" -u '+%s')"
[[ "$now_epoch" =~ ^[1-9][0-9]{8,}$ ]] || backup_fail 'pruner clock is invalid'
safety_seconds=$((BACKUP_RETENTION_SAFETY_DAYS * 86400))
delete_sets="$work_directory/delete-sets.txt"
: >"$delete_sets"
while IFS=$'\t' read -r finished_at set_id daily weekly monthly; do
  [[ -n "$set_id" ]] || continue
  [[ -z "${retained[$set_id]:-}" ]] || continue
  finished_epoch="$("$BACKUP_DATE_COMMAND" -u -d "$finished_at" '+%s')" ||
    backup_fail 'set completion time cannot be parsed'
  [[ "$finished_epoch" =~ ^[1-9][0-9]{8,}$ ]] || backup_fail 'set completion epoch is invalid'
  ((now_epoch - finished_epoch >= safety_seconds)) || continue
  printf '%s\n' "$set_id" >>"$delete_sets"
done <"$set_index"
sort -u -o "$delete_sets" "$delete_sets"

protected_sets="$work_directory/protected-sets.txt"
: >"$protected_sets"
while IFS=$'\t' read -r _finished set_id _daily _weekly _monthly; do
  [[ -n "$set_id" ]] || continue
  grep -Fxq -- "$set_id" "$delete_sets" || printf '%s\n' "$set_id" >>"$protected_sets"
done <"$set_index"
sort -u -o "$protected_sets" "$protected_sets"

current_set="$(awk -F '\t' 'NR == 1 {print $2}' "$set_index")"
backup_is_set_id "$current_set" || backup_fail 'current verified set cannot be determined'
grep -Fxq -- "$current_set" "$retained_file" || backup_fail 'current verified set is not retained'
grep -Fxq -- "$current_set" "$protected_sets" || backup_fail 'current verified set is not protected'

reachable_blobs="$work_directory/reachable-blobs.txt"
: >"$reachable_blobs"
while IFS= read -r set_id; do
  [[ -n "$set_id" ]] || continue
  jq -r '.blobs[].path' "$work_directory/manifests/$set_id.json" >>"$reachable_blobs"
done <"$protected_sets"
sort -u -o "$reachable_blobs" "$reachable_blobs"

deletable_blob_references="$work_directory/deletable-blob-references.txt"
: >"$deletable_blob_references"
while IFS= read -r set_id; do
  [[ -n "$set_id" ]] || continue
  jq -r '.blobs[].path' "$work_directory/manifests/$set_id.json" >>"$deletable_blob_references"
done <"$delete_sets"
sort -u -o "$deletable_blob_references" "$deletable_blob_references"

blob_listing="$work_directory/blobs.json"
if ! backup_rclone "$BACKUP_PRUNE_RCLONE_CONFIG" lsjson \
    --files-only --recursive=false -- "$(backup_remote_namespace_root blobs)" \
    >"$blob_listing" 2>/dev/null; then
  backup_fail 'pruner could not list immutable blob metadata'
fi
jq -e '
  type == "array" and all(.[];
    (keys | index("Path") != null) and (keys | index("Size") != null) and
    (keys | index("ModTime") != null) and
    (.Path | type == "string" and test("^[0-9a-f]{64}$")) and
    (.Size | type == "number" and floor == . and . > 0) and
    (.ModTime | type == "string" and
      test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]+)?Z$")))
' "$blob_listing" >/dev/null || backup_fail 'blob namespace contains invalid metadata'
delete_blobs="$work_directory/delete-blobs.txt"
: >"$delete_blobs"
while IFS=$'\t' read -r sha mod_time; do
  [[ -n "$sha" ]] || continue
  grep -Fxq -- "blobs/$sha" "$deletable_blob_references" || continue
  grep -Fxq -- "blobs/$sha" "$reachable_blobs" && continue
  modified_epoch="$("$BACKUP_DATE_COMMAND" -u -d "$mod_time" '+%s')" ||
    backup_fail 'blob modification time cannot be parsed'
  ((now_epoch - modified_epoch >= safety_seconds)) || continue
  printf '%s\n' "$sha" >>"$delete_blobs"
done < <(jq -r '.[] | [.Path,.ModTime] | @tsv' "$blob_listing")

uploading_listing="$work_directory/uploading.list"
if ! backup_rclone "$BACKUP_PRUNE_RCLONE_CONFIG" lsf \
    --dirs-only --max-depth 1 -- "$(backup_remote_namespace_root uploading)" \
    >"$uploading_listing" 2>/dev/null; then
  backup_fail 'pruner could not list isolated upload attempts'
fi
delete_uploads="$work_directory/delete-uploads.txt"
: >"$delete_uploads"
while IFS= read -r directory; do
  [[ -n "$directory" ]] || continue
  upload_id="${directory%/}"
  backup_is_set_id "$upload_id" ||
    backup_fail 'uploading namespace contains an unexpected directory'
  [[ "$directory" == "$upload_id/" ]] ||
    backup_fail 'uploading directory listing is not canonical'
  if [[ -f "$work_directory/manifests/$upload_id.namespace" ]] &&
      [[ "$(tr -d '\r\n' <"$work_directory/manifests/$upload_id.namespace")" == uploading ]]; then
    continue
  fi
  upload_metadata="$work_directory/upload-$upload_id.json"
  if ! backup_rclone "$BACKUP_PRUNE_RCLONE_CONFIG" lsjson \
      --files-only --recursive=false -- \
      "$(backup_remote_namespace_root uploading)/$upload_id" \
      >"$upload_metadata" 2>/dev/null; then
    backup_fail 'pruner could not list an isolated upload attempt'
  fi
  jq -e '
    type == "array" and length > 0 and length <= 5 and
    all(.[];
      (keys | index("Path") != null) and (keys | index("Size") != null) and
      (keys | index("ModTime") != null) and
      (.Path | type == "string" and
        (. == "VERIFIED" or . == "database.dump.age" or
         . == "local-media.tar.age" or . == "media-manifest.json.age" or
         . == "set-manifest.json")) and
      (.Size | type == "number" and floor == . and . > 0) and
      (.ModTime | type == "string" and
        test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]+)?Z$"))) and
    ((map(.Path)) == (map(.Path) | sort | unique))
  ' "$upload_metadata" >/dev/null ||
    backup_fail 'isolated upload attempt contains invalid object metadata'
  upload_is_stale=true
  while IFS= read -r modified_at; do
    modified_epoch="$("$BACKUP_DATE_COMMAND" -u -d "$modified_at" '+%s')" ||
      backup_fail 'upload attempt modification time cannot be parsed'
    if ((now_epoch - modified_epoch < safety_seconds)); then
      upload_is_stale=false
    fi
  done < <(jq -r '.[].ModTime' "$upload_metadata")
  [[ "$upload_is_stale" == true ]] && printf '%s\n' "$upload_id" >>"$delete_uploads"
done <"$uploading_listing"
sort -u -o "$delete_uploads" "$delete_uploads"

observed_sets_ndjson="$work_directory/observed-sets.ndjson"
: >"$observed_sets_ndjson"
while IFS=$'\t' read -r _finished set_id _daily _weekly _monthly; do
  [[ -n "$set_id" ]] || continue
  jq -S -c -n \
    --arg setId "$set_id" \
    --arg manifestSha256 "$(backup_sha256_file "$work_directory/manifests/$set_id.json")" \
    --arg objectNamespace "$(tr -d '\r\n' <"$work_directory/manifests/$set_id.namespace")" \
    '{setId:$setId,manifestSha256:$manifestSha256,objectNamespace:$objectNamespace}' \
    >>"$observed_sets_ndjson"
done <"$set_index"
observed_sets="$work_directory/observed-sets.json"
jq -S -c -s 'sort_by(.setId)' "$observed_sets_ndjson" >"$observed_sets"

generated_at="$("$BACKUP_DATE_COMMAND" -u '+%Y-%m-%dT%H:%M:%SZ')"
backup_is_rfc3339_utc "$generated_at" || backup_fail 'GC report time is invalid'
report="$work_directory/gc-report.json"
jq -S -c -n \
  --arg generatedAt "$generated_at" \
  --arg prefix "$BACKUP_PREFIX" \
  --arg accountId "$BACKUP_DESTINATION_ACCOUNT_ID" \
  --arg bucket "$BACKUP_DESTINATION_BUCKET" \
  --arg region "$BACKUP_DESTINATION_REGION" \
  --arg principalId "$BACKUP_PRUNE_PRINCIPAL_ID" \
  --arg currentSet "$current_set" \
  --argjson safetyDays "$BACKUP_RETENTION_SAFETY_DAYS" \
  --slurpfile observed "$observed_sets" \
  --rawfile retained "$retained_file" \
  --rawfile protected "$protected_sets" \
  --rawfile reachable "$reachable_blobs" \
  --rawfile deleteSets "$delete_sets" \
  --rawfile deleteBlobs "$delete_blobs" \
  --rawfile deleteUploads "$delete_uploads" \
  '{schemaVersion:3,generatedAt:$generatedAt,prefix:$prefix,
    destination:{accountId:$accountId,bucket:$bucket,region:$region,principalId:$principalId},
    policy:{daily:7,weekly:4,monthly:6,safetyDays:$safetyDays},
    currentSet:$currentSet,observedSets:$observed[0],
    retainedSets:($retained|split("\n")|map(select(length>0))|sort),
    protectedSets:($protected|split("\n")|map(select(length>0))|sort),
    reachableBlobs:($reachable|split("\n")|map(select(length>0))|sort),
    deleteSets:($deleteSets|split("\n")|map(select(length>0))|sort),
    deleteBlobs:($deleteBlobs|split("\n")|map(select(length>0)|"blobs/"+.)|sort),
    deleteUploads:($deleteUploads|split("\n")|map(select(length>0))|sort)}' \
  >"$report"

candidate_plan_ndjson="$work_directory/candidates.ndjson"
: >"$candidate_plan_ndjson"
review_candidate() {
  local kind="$1"
  local relative="$2"
  local evidence="$3"
  local output ticket evidence_name ticket_name ticket_sha
  output="$work_directory/review-$(printf '%s' "$kind:$relative" | sha256sum | awk '{print $1}').guard"
  ticket="$work_directory/ticket-$(printf '%s' "$kind:$relative" | sha256sum | awk '{print $1}').json"
  if ! "$BACKUP_PRUNE_GUARD_COMMAND" review-candidate \
      --kind "$kind" \
      --relative-path "$relative" \
      --evidence "$evidence" \
      --proposal "$report" \
      --ticket-output "$ticket" \
      --remote "$BACKUP_REMOTE" \
      --prefix "$BACKUP_PREFIX" \
      --account "$BACKUP_DESTINATION_ACCOUNT_ID" \
      --bucket "$BACKUP_DESTINATION_BUCKET" \
      --region "$BACKUP_DESTINATION_REGION" \
      --principal "$BACKUP_PRUNE_PRINCIPAL_ID" \
      --rclone-config "$BACKUP_PRUNE_RCLONE_CONFIG" >"$output" 2>/dev/null; then
    backup_fail 'a retention candidate failed version and retention review'
  fi
  [[ "$(tr -d '\r\n' <"$output")" == SAFE ]] ||
    backup_fail 'retention guard rejected a deletion candidate'
  case "$kind" in
    set) evidence_name="evidence-set-${relative#sets/}.json" ;;
    blob|upload) evidence_name='gc-report.json' ;;
    *) backup_fail 'reviewed candidate kind is invalid' ;;
  esac
  ticket_name="${ticket##*/}"
  ticket_sha="$(backup_sha256_file "$ticket")"
  jq -S -c -n \
    --arg kind "$kind" \
    --arg relativePath "$relative" \
    --arg evidence "$evidence_name" \
    --arg ticket "$ticket_name" \
    --arg ticketSha256 "$ticket_sha" \
    '{kind:$kind,relativePath:$relativePath,evidence:$evidence,
      ticket:$ticket,ticketSha256:$ticketSha256}' >>"$candidate_plan_ndjson"
}

while IFS= read -r set_id; do
  [[ -n "$set_id" ]] || continue
  review_candidate set "sets/$set_id" "$work_directory/manifests/$set_id.json"
done <"$delete_sets"
while IFS= read -r upload_id; do
  [[ -n "$upload_id" ]] || continue
  review_candidate upload "uploading/$upload_id" "$report"
done <"$delete_uploads"
while IFS= read -r sha; do
  [[ -n "$sha" ]] || continue
  review_candidate blob "blobs/$sha" "$report"
done <"$delete_blobs"
report_id="$("$BACKUP_DATE_COMMAND" -u '+%Y%m%dT%H%M%SZ')-$(backup_random_hex 6).json"
if ! backup_rclone "$BACKUP_PRUNE_RCLONE_CONFIG" copyto --immutable -- \
    "$report" "$(backup_remote_path gc-reports "$report_id")" >/dev/null 2>&1; then
  backup_fail 'immutable GC proposal upload failed'
fi

candidate_plan="$work_directory/candidates.json"
jq -S -c -s 'sort_by(
  (if .kind == "set" then 0 elif .kind == "upload" then 1 else 2 end),
  .relativePath)' "$candidate_plan_ndjson" >"$candidate_plan"

if [[ "$dry_run" == false && "$(jq 'length' "$candidate_plan")" -gt 0 ]]; then
  transaction_build_directory="$(mktemp -d "$BACKUP_PRUNE_TRANSACTION_ROOT/.new.XXXXXX")"
  chmod 0700 "$transaction_build_directory"
  install -m 0600 -- "$report" "$transaction_build_directory/gc-report.json"
  while IFS= read -r candidate; do
    kind="$(jq -r '.kind' <<<"$candidate")"
    relative="$(jq -r '.relativePath' <<<"$candidate")"
    evidence_name="$(jq -r '.evidence' <<<"$candidate")"
    ticket_name="$(jq -r '.ticket' <<<"$candidate")"
    case "$kind" in
      set)
        set_id="${relative#sets/}"
        install -m 0600 -- "$work_directory/manifests/$set_id.json" \
          "$transaction_build_directory/$evidence_name"
        ;;
      blob|upload) ;;
      *) backup_fail 'transaction build encountered an invalid candidate kind' ;;
    esac
    install -m 0600 -- "$work_directory/$ticket_name" \
      "$transaction_build_directory/$ticket_name"
  done < <(jq -c '.[]' "$candidate_plan")
  proposal_sha="$(backup_sha256_file "$transaction_build_directory/gc-report.json")"
  destination_digest="$(transaction_destination_digest)"
  candidate_digest="$(jq -S -c '.' "$candidate_plan" | sha256sum | awk '{print $1}')"
  jq -S -c -n \
    --argjson destination "$(transaction_destination_json)" \
    --arg destinationDigest "$destination_digest" \
    --arg proposalSha256 "$proposal_sha" \
    --arg candidateDigest "$candidate_digest" \
    --slurpfile candidates "$candidate_plan" \
    '{schemaVersion:1,destination:$destination,destinationDigest:$destinationDigest,
      proposalSha256:$proposalSha256,candidateDigest:$candidateDigest,
      candidates:$candidates[0]}' \
    >"$transaction_build_directory/transaction-plan.json"
  chmod 0600 "$transaction_build_directory/transaction-plan.json"

  # Materialize every ACTIVE exact-version state while tickets are fresh.  No
  # provider mutation is issued before the fully prepared directory is atomically
  # renamed out of the .new namespace.
  run_transaction_candidates "$transaction_build_directory" true
  sync -f "$transaction_build_directory"
  transaction_directory="$BACKUP_PRUNE_TRANSACTION_ROOT/$destination_digest-$proposal_sha-$candidate_digest"
  [[ ! -e "$transaction_directory" && ! -L "$transaction_directory" ]] ||
    backup_fail 'an identical persistent prune transaction already exists'
  mv -- "$transaction_build_directory" "$transaction_directory"
  transaction_build_directory=''
  # Persist activation in the parent directory before the first provider delete.
  # A power loss may then leave either .new (never destructive) or active (fully
  # recoverable), but can never lose the only exact-version transaction record.
  sync -f "$BACKUP_PRUNE_TRANSACTION_ROOT"
  validate_transaction_directory "$transaction_directory"
  run_transaction_directory "$transaction_directory" false
  validate_transaction_directory "$transaction_directory"
  done_directory="$BACKUP_PRUNE_TRANSACTION_ROOT/.done.${transaction_directory##*/}"
  [[ ! -e "$done_directory" && ! -L "$done_directory" ]] ||
    backup_fail 'completed prune transaction cleanup name already exists'
  mv -- "$transaction_directory" "$done_directory"
  sync -f "$BACKUP_PRUNE_TRANSACTION_ROOT"
  rm -rf -- "$done_directory"
fi

if [[ "$dry_run" == true ]]; then
  commit_dry_run_readiness
  if [[ "$bootstrap_dry_run" == true ]]; then
    backup_note 'initial prune execution completed as a non-destructive dry-run; deletion is enabled only for a later invocation'
  fi
fi

backup_note '7/4/6 reachability retention proposal completed under the reviewed prefix'
