#!/usr/bin/env bash
# shellcheck disable=SC2016
set -euo pipefail
set +x
umask 077

export LC_ALL=C

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
VALIDATOR="$REPOSITORY_ROOT/deploy/scripts/validate-bundle-tar.py"
COMPLIANCE_VALIDATOR="$REPOSITORY_ROOT/deploy/scripts/verify-compliance-tree.py"
PRUNER="$REPOSITORY_ROOT/deploy/scripts/prune-releases.sh"
ROLLBACK="$REPOSITORY_ROOT/deploy/scripts/rollback-release.sh"
INSTALLER="$REPOSITORY_ROOT/deploy/scripts/install-release-bundle.sh"
DEPLOYER="$REPOSITORY_ROOT/deploy/scripts/deploy-release.sh"
SMOKE="$REPOSITORY_ROOT/deploy/scripts/smoke.sh"
LOCAL_VOLUME_PROVISIONER="$REPOSITORY_ROOT/deploy/scripts/provision-local-volume.sh"
UPLOAD_PROMOTER="$REPOSITORY_ROOT/deploy/scripts/promote-release-upload.sh"
BOOTSTRAP_PACKAGER="$REPOSITORY_ROOT/deploy/scripts/package-bootstrap-kit.sh"
BOOTSTRAP_INSTALLER="$REPOSITORY_ROOT/deploy/scripts/install-bootstrap-kit.py"
BACKUP_UNIT_INSTALLER="$REPOSITORY_ROOT/deploy/scripts/install-backup-units.sh"
BUILD_RELEASE="$REPOSITORY_ROOT/deploy/scripts/build-release.sh"
IMAGE_LOCK_LIB="$REPOSITORY_ROOT/deploy/scripts/image-lock-lib.sh"
RELEASE_ARTIFACT_CONTRACT="$REPOSITORY_ROOT/deploy/tests/release-artifact-contract.sh"
APT_SNAPSHOT_VERIFIER="$REPOSITORY_ROOT/docker/verify-ubuntu-apt-snapshot.sh"
TMPFILES_CONFIG="$REPOSITORY_ROOT/deploy/tmpfiles.d/portfolio.conf"
BACKUP_SERVICE_UNIT="$REPOSITORY_ROOT/deploy/systemd/portfolio-backup.service"
BACKUP_PRUNE_SERVICE_UNIT="$REPOSITORY_ROOT/deploy/systemd/portfolio-backup-prune.service"
PRODUCTION_RUNBOOK="$REPOSITORY_ROOT/docs/operations/production-runbook.md"
RELEASE_EVIDENCE_TEMPLATE="$REPOSITORY_ROOT/docs/operations/release-evidence-template.md"
WORK_DIRECTORY=''
RELEASE_ID='0123456789ab-fedcba987654'

fail() {
  printf 'deploy state-machine contract failed: %s\n' "$1" >&2
  exit 1
}

cleanup() {
  if [[ -n "$WORK_DIRECTORY" && -d "$WORK_DIRECTORY" ]]; then
    rm -rf -- "$WORK_DIRECTORY"
  fi
}
trap cleanup EXIT HUP INT TERM

expect_reject() {
  local label="$1"
  local archive="$2"
  local expected="$3"
  local output="$WORK_DIRECTORY/$label.log"
  if python3 "$VALIDATOR" --release-id "$RELEASE_ID" "$archive" >"$output" 2>&1; then
    fail "$label archive unexpectedly passed"
  fi
  grep -F "$expected" "$output" >/dev/null ||
    {
      sed -n '1,80p' "$output" >&2
      fail "$label archive failed for an unexpected reason"
    }
}

make_tar() {
  local kind="$1"
  local output="$2"
  python3 - "$kind" "$output" "$RELEASE_ID" <<'PY'
import io
import sys
import tarfile

kind, output, release_id = sys.argv[1:]
root = f"portfolio-{release_id}"


def add_dir(archive: tarfile.TarFile, name: str) -> None:
    info = tarfile.TarInfo(name)
    info.type = tarfile.DIRTYPE
    info.mode = 0o750
    info.mtime = 0
    archive.addfile(info)


def add_file(archive: tarfile.TarFile, name: str, body: bytes = b"ok") -> None:
    info = tarfile.TarInfo(name)
    info.size = len(body)
    info.mode = 0o640
    info.mtime = 0
    archive.addfile(info, io.BytesIO(body))


if kind in {"pax-entry", "pax-global"}:
    format_value = tarfile.PAX_FORMAT
elif kind in {"gnu-longname", "gnu-longlink"}:
    format_value = tarfile.GNU_FORMAT
else:
    format_value = tarfile.USTAR_FORMAT
kwargs = {"format": format_value}
if kind == "pax-global":
    kwargs["pax_headers"] = {"comment": "forbidden"}

with tarfile.open(output, "w", **kwargs) as archive:
    add_dir(archive, root)
    add_dir(archive, f"{root}/admin")
    add_file(archive, f"{root}/admin/index.html")
    add_dir(archive, f"{root}/public-assets")
    add_dir(archive, f"{root}/ops")
    add_dir(archive, f"{root}/compliance")
    if kind != "missing-top":
        add_dir(archive, f"{root}/images")
    add_file(archive, f"{root}/release.json", b"{}")
    add_file(archive, f"{root}/bundle-manifest.json", b"")

    if kind == "valid":
        add_file(archive, f"{root}/public-assets/manifest.json", b"{}")
    elif kind == "absolute":
        add_file(archive, "/absolute")
    elif kind == "parent":
        add_file(archive, f"{root}/../escape")
    elif kind == "backslash":
        add_file(archive, f"{root}\\escape")
    elif kind == "outside":
        add_file(archive, "another-root/file")
    elif kind == "unknown-top":
        add_file(archive, f"{root}/evil/payload")
    elif kind == "missing-top":
        pass
    elif kind == "gnu-longname":
        add_file(archive, f"{root}/admin/{'n' * 140}")
    elif kind == "gnu-longlink":
        info = tarfile.TarInfo(f"{root}/admin/gnu-link")
        info.type = tarfile.SYMTYPE
        info.linkname = "l" * 180
        archive.addfile(info)
    elif kind in {"symlink", "hardlink", "fifo", "character", "block", "socket", "sparse"}:
        info = tarfile.TarInfo(f"{root}/forbidden")
        info.mtime = 0
        info.type = {
            "symlink": tarfile.SYMTYPE,
            "hardlink": tarfile.LNKTYPE,
            "fifo": tarfile.FIFOTYPE,
            "character": tarfile.CHRTYPE,
            "block": tarfile.BLKTYPE,
            "socket": b"s",
            "sparse": tarfile.GNUTYPE_SPARSE,
        }[kind]
        if kind in {"symlink", "hardlink"}:
            info.linkname = f"{root}/admin/index.html"
        archive.addfile(info)
    elif kind == "pax-entry":
        info = tarfile.TarInfo(f"{root}/pax-file")
        info.size = 1
        info.pax_headers = {"comment": "forbidden"}
        archive.addfile(info, io.BytesIO(b"x"))
    elif kind == "pax-global":
        add_file(archive, f"{root}/global-pax-file", b"x")
    elif kind == "duplicate":
        add_file(archive, f"{root}/admin/duplicate")
        add_file(archive, f"{root}/admin/duplicate")
    elif kind == "casefold":
        add_file(archive, f"{root}/admin/Case")
        add_file(archive, f"{root}/admin/case")
    elif kind == "file-parent":
        add_file(archive, f"{root}/admin/parent")
        add_file(archive, f"{root}/admin/parent/child")
    elif kind == "file-replaces-dir":
        add_file(archive, f"{root}/admin/path/child")
        add_file(archive, f"{root}/admin/path")
    else:
        raise SystemExit(f"unknown fixture kind: {kind}")
PY
}

test_tar_validator() {
  [[ -f "$VALIDATOR" ]] || fail 'bundle tar validator is missing'
  local kind
  for kind in \
    valid absolute parent backslash outside unknown-top missing-top gnu-longname gnu-longlink \
    symlink hardlink fifo character block socket sparse pax-entry pax-global duplicate casefold \
    file-parent file-replaces-dir
  do
    make_tar "$kind" "$WORK_DIRECTORY/$kind.tar"
  done

  python3 "$VALIDATOR" --release-id "$RELEASE_ID" "$WORK_DIRECTORY/valid.tar" \
    >"$WORK_DIRECTORY/valid.log" 2>&1 || fail 'valid archive was rejected'
  grep -F 'bundle validation passed' "$WORK_DIRECTORY/valid.log" >/dev/null ||
    fail 'valid archive did not report success'

  expect_reject absolute "$WORK_DIRECTORY/absolute.tar" 'absolute path'
  expect_reject parent "$WORK_DIRECTORY/parent.tar" 'parent component'
  expect_reject backslash "$WORK_DIRECTORY/backslash.tar" 'backslash'
  expect_reject outside "$WORK_DIRECTORY/outside.tar" 'outside'
  expect_reject unknown-top "$WORK_DIRECTORY/unknown-top.tar" 'unknown release top-level entry'
  expect_reject missing-top "$WORK_DIRECTORY/missing-top.tar" 'missing a required release top-level entry'
  expect_reject gnu-longname "$WORK_DIRECTORY/gnu-longname.tar" 'strict POSIX ustar'
  expect_reject gnu-longlink "$WORK_DIRECTORY/gnu-longlink.tar" 'strict POSIX ustar'
  for kind in symlink hardlink fifo character block socket; do
    expect_reject "$kind" "$WORK_DIRECTORY/$kind.tar" 'non-regular entry'
  done
  expect_reject sparse "$WORK_DIRECTORY/sparse.tar" 'sparse archive'
  expect_reject pax-entry "$WORK_DIRECTORY/pax-entry.tar" 'PAX headers'
  expect_reject pax-global "$WORK_DIRECTORY/pax-global.tar" 'PAX headers'
  expect_reject duplicate "$WORK_DIRECTORY/duplicate.tar" 'duplicate normalized path'
  expect_reject casefold "$WORK_DIRECTORY/casefold.tar" 'duplicate case-folded path'
  expect_reject file-parent "$WORK_DIRECTORY/file-parent.tar" 'descends from a regular file'
  expect_reject file-replaces-dir "$WORK_DIRECTORY/file-replaces-dir.tar" 'replaces a directory path'
}

test_private_mode_round_trip_without_docker() {
  local fixture="$WORK_DIRECTORY/private-mode-round-trip"
  local source="$fixture/source/portfolio-$RELEASE_ID"
  local extracted="$fixture/extracted/portfolio-$RELEASE_ID"
  mkdir -p \
    "$source/admin" "$source/public-assets" \
    "$source/ops/deploy/scripts" "$source/ops/docs" "$source/images" \
    "$fixture/extracted"
  printf '<!doctype html>\n' >"$source/admin/index.html"
  printf '{}\n' >"$source/release.json"
  printf '\n' >"$source/bundle-manifest.json"
  printf '#!/usr/bin/env bash\nexit 0\n' >"$source/ops/deploy/scripts/run.sh"
  printf 'private operations data\n' >"$source/ops/docs/runbook.md"
  printf 'archive fixture; Docker is intentionally not invoked\n' >"$source/images/image.oci.tar.zst"
  find "$source/ops" "$source/images" -type d -exec chmod 0700 {} +
  chmod 0700 "$source/ops/deploy/scripts/run.sh"
  chmod 0600 "$source/ops/docs/runbook.md" "$source/images/image.oci.tar.zst"

  tar -C "$fixture/source" \
    --sort=name --format=ustar --mtime='@0' \
    --owner=0 --group=0 --numeric-owner --mode='u+rwX,go-rwx' \
    -cf "$fixture/release.tar" "portfolio-$RELEASE_ID"
  (
    umask 077
    tar -xf "$fixture/release.tar" --directory "$fixture/extracted" \
      --no-same-owner --no-same-permissions
  )

  [[ "$(stat -Lc '%a' "$extracted/ops")" == 700 &&
     "$(stat -Lc '%a' "$extracted/ops/deploy/scripts")" == 700 &&
     "$(stat -Lc '%a' "$extracted/images")" == 700 &&
     "$(stat -Lc '%a' "$extracted/ops/deploy/scripts/run.sh")" == 700 &&
     "$(stat -Lc '%a' "$extracted/ops/docs/runbook.md")" == 600 &&
     "$(stat -Lc '%a' "$extracted/images/image.oci.tar.zst")" == 600 ]] ||
    fail 'release private modes changed across package/extraction round trip'
  grep -F '100644) output_mode=0600' "$BUILD_RELEASE" >/dev/null ||
    fail 'release builder does not emit private operations data as mode 0600'
  grep -F '100755) output_mode=0700' "$BUILD_RELEASE" >/dev/null ||
    fail 'release builder does not emit private operations executables as mode 0700'
  grep -F 'release executable mode is not 0700' "$RELEASE_ARTIFACT_CONTRACT" >/dev/null ||
    fail 'release artifact contract does not enforce mode 0700 executables'
  grep -F 'release private operations file mode is not 0600' \
    "$RELEASE_ARTIFACT_CONTRACT" >/dev/null ||
    fail 'release artifact contract does not enforce mode 0600 private data'
}

test_bootstrap_host_prerequisite_contract() {
  local package_name command_name
  for package_name in \
    ca-certificates curl python3 zstd jq coreutils util-linux tar openssl \
    rclone age uuid-runtime msmtp-mta
  do
    grep -E "(^|[[:space:]])${package_name}([[:space:]]|$)" \
      "$PRODUCTION_RUNBOOK" >/dev/null ||
      fail "bootstrap host prerequisite package is undocumented: $package_name"
  done
  for command_name in \
    update-ca-certificates curl python3 zstd jq stat sha256sum install mv mktemp \
    flock setpriv tar openssl rclone age uuidgen msmtp
  do
    grep -E "(^|[[:space:]])${command_name}([[:space:]]|$)" \
      "$PRODUCTION_RUNBOOK" >/dev/null ||
      fail "bootstrap host command gate is undocumented: $command_name"
  done
  # These are literal runbook source assertions, not shell expansion sites.
  # shellcheck disable=SC2016
  grep -F 'command_path="$(command -v -- "$command_name")"' \
    "$PRODUCTION_RUNBOOK" >/dev/null ||
    fail 'bootstrap host prerequisite gate does not resolve each command explicitly'
  # shellcheck disable=SC2016
  grep -F '[[ "$(stat -Lc '\''%u'\'' -- "$resolved_path")" == 0 ]]' \
    "$PRODUCTION_RUNBOOK" >/dev/null ||
    fail 'bootstrap host prerequisite gate does not require root-owned binaries'
  # shellcheck disable=SC2016
  grep -F '8#$binary_mode & 8#022' "$PRODUCTION_RUNBOOK" >/dev/null ||
    fail 'bootstrap host prerequisite gate permits group/world-writable binaries'
  grep -F "不得使用 \`curl | sh\`" "$PRODUCTION_RUNBOOK" >/dev/null ||
    fail 'bootstrap runbook does not prohibit curl-piped remote shells'
  grep -F "\`pg_dump\`/\`pg_restore\` 由受发布镜像约束的生产 PostgreSQL 容器" \
    "$PRODUCTION_RUNBOOK" >/dev/null ||
    fail 'bootstrap runbook incorrectly depends on a host PostgreSQL client'
  grep -F 'APT 签名源 host/suite/component/architecture' \
    "$RELEASE_EVIDENCE_TEMPLATE" >/dev/null ||
    fail 'release evidence template omits APT source evidence'
  grep -F 'Bootstrap 宿主包精确版本/架构' "$RELEASE_EVIDENCE_TEMPLATE" >/dev/null ||
    fail 'release evidence template omits prerequisite package evidence'
  grep -F 'Bootstrap 系统二进制 root-owned / 非 group-world writable' \
    "$RELEASE_EVIDENCE_TEMPLATE" >/dev/null ||
    fail 'release evidence template omits binary trust-gate evidence'
}

create_release_fixture() {
  local root="$1"
  local release_id="$2"
  local day="$3"
  local asset="$4"
  local release="$root/releases/$release_id"
  mkdir -p "$release/public-assets/.vite" "$release/public-assets/assets" "$release/admin"
  printf '{"releaseId":"%s","buildTimeUtc":"2026-07-%02dT00:00:00Z"}\n' \
    "$release_id" "$day" >"$release/release.json"
  printf '{"entry":{"file":"assets/%s"}}\n' "$asset" \
    >"$release/public-assets/.vite/manifest.json"
  printf 'release-%s\n' "$release_id" >"$release/public-assets/assets/$asset"
  printf '<!doctype html>\n' >"$release/admin/index.html"
  printf 'shared-%s\n' "$release_id" >"$root/assets/$asset"
}

test_safe_pruning() {
  [[ -f "$PRUNER" ]] || fail 'release pruner is missing'
  local root="$WORK_DIRECTORY/prune-root"
  mkdir -p "$root/releases" "$root/assets"
  local first='111111111111-aaaaaaaaaaaa'
  local second='222222222222-bbbbbbbbbbbb'
  local previous='333333333333-cccccccccccc'
  local current='444444444444-dddddddddddd'
  create_release_fixture "$root" "$first" 1 first.js
  create_release_fixture "$root" "$second" 2 second.js
  create_release_fixture "$root" "$previous" 3 previous.js
  create_release_fixture "$root" "$current" 4 current.js
  printf '%s\n' "$current" >"$root/current-release"
  printf '%s\n' "$previous" >"$root/previous-release"
  printf 'unreferenced\n' >"$root/assets/unreferenced.js"
  touch -d '10 days ago' "$root/assets/unreferenced.js" "$root/assets/first.js"

  PORTFOLIO_ROOT="$root" PORTFOLIO_DEPLOY_LOCK_FILE="$root/deploy.lock" \
    bash "$PRUNER" --dry-run >"$WORK_DIRECTORY/prune-dry.log"
  [[ -d "$root/releases/$first" && -f "$root/assets/unreferenced.js" ]] ||
    fail 'dry-run changed the filesystem'

  PORTFOLIO_ROOT="$root" PORTFOLIO_DEPLOY_LOCK_FILE="$root/deploy.lock" \
    bash "$PRUNER" >"$WORK_DIRECTORY/prune.log"
  [[ ! -e "$root/releases/$first" ]] || fail 'old fourth release was not removed'
  [[ -d "$root/releases/$second" && -d "$root/releases/$previous" &&
     -d "$root/releases/$current" ]] || fail 'one of the retained three releases was removed'
  [[ ! -e "$root/assets/unreferenced.js" ]] || fail 'old unreferenced asset was not removed'
  [[ ! -e "$root/assets/first.js" ]] || fail 'unreferenced fourth-release asset remained'
  [[ -e "$root/assets/second.js" && -e "$root/assets/previous.js" &&
     -e "$root/assets/current.js" ]] || fail 'an asset referenced by the retained three was removed'

  ln -s "$WORK_DIRECTORY" "$root/releases/555555555555-eeeeeeeeeeee"
  if PORTFOLIO_ROOT="$root" PORTFOLIO_DEPLOY_LOCK_FILE="$root/deploy.lock" \
      bash "$PRUNER" >"$WORK_DIRECTORY/prune-link.log" 2>&1; then
    fail 'release symlink unexpectedly passed pruning'
  fi
  grep -E 'unexpected entry in releases root|retained release is missing|old release is not a regular directory' \
    "$WORK_DIRECTORY/prune-link.log" >/dev/null ||
    fail 'release symlink failed for an unexpected reason'
}

test_local_volume_provisioning() {
  [[ -f "$LOCAL_VOLUME_PROVISIONER" ]] || fail 'Local volume provisioner is missing'
  local fixture="$WORK_DIRECTORY/local-volume-provision" bin volume_root etc_root log
  bin="$fixture/bin"
  volume_root="$fixture/docker-volume"
  etc_root="$fixture/etc"
  log="$fixture/docker.log"
  export PORTFOLIO_DEPLOY_LOCK_FILE="$fixture/deploy.lock"
  mkdir -p "$bin" "$volume_root" "$etc_root"
  chmod 0711 "$WORK_DIRECTORY" "$fixture"
  chmod 0755 "$volume_root"
  printf 'PORTFOLIO_JOBS_WORKER_ENABLED=true\n' \
    >"$etc_root/portfolio.env"
  chmod 0600 "$etc_root/portfolio.env"
  cat >"$bin/docker" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"$PORTFOLIO_TEST_LOCAL_VOLUME_LOG"
case "${1:-}:${2:-}" in
  volume:inspect)
    [[ -f "$PORTFOLIO_TEST_LOCAL_VOLUME_STATE" ]] || exit 1
    if [[ "$*" == *'--format'* ]]; then
      printf 'portfolio-local-media|local|%s|%s\n' \
        "${PORTFOLIO_TEST_LOCAL_VOLUME_ROLE:-local-media}" \
        "${PORTFOLIO_TEST_LOCAL_VOLUME_MOUNTPOINT}"
    else
      printf '{}\n'
    fi
    ;;
  volume:create)
    [[ "$*" == *'--label portfolio.volume-role=local-media portfolio-local-media'* ]] || exit 96
    : >"$PORTFOLIO_TEST_LOCAL_VOLUME_STATE"
    printf 'portfolio-local-media\n'
    ;;
  *) exit 97 ;;
esac
STUB
  chmod 0755 "$bin/docker"

  : >"$log"
  chown 12345:12345 "$etc_root"
  if env PATH="$bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
      PORTFOLIO_ETC_ROOT="$etc_root" \
      PORTFOLIO_LOCAL_VOLUME_LOCK_FILE="$fixture/provision.lock" \
      PORTFOLIO_TEST_LOCAL_VOLUME_LOG="$log" \
      bash "$LOCAL_VOLUME_PROVISIONER" >"$fixture/etc-owner.out" 2>"$fixture/etc-owner.err"; then
    fail 'Local volume provisioner accepted an attacker-owned protected config root'
  fi
  grep -F 'PORTFOLIO_ETC_ROOT is non-canonical or not owned by root' \
    "$fixture/etc-owner.err" >/dev/null ||
    fail 'attacker-owned Local volume config root failed for an unexpected reason'
  [[ ! -s "$log" ]] || fail 'untrusted Local volume config root reached Docker mutation'
  chown 0:0 "$etc_root"

  chmod 0770 "$etc_root"
  if env PATH="$bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
      PORTFOLIO_ETC_ROOT="$etc_root" \
      PORTFOLIO_LOCAL_VOLUME_LOCK_FILE="$fixture/provision.lock" \
      PORTFOLIO_TEST_LOCAL_VOLUME_LOG="$log" \
      bash "$LOCAL_VOLUME_PROVISIONER" >"$fixture/etc-mode.out" 2>"$fixture/etc-mode.err"; then
    fail 'Local volume provisioner accepted a group-writable protected config root'
  fi
  grep -F 'PORTFOLIO_ETC_ROOT is writable outside root' "$fixture/etc-mode.err" >/dev/null ||
    fail 'writable Local volume config root failed for an unexpected reason'
  [[ ! -s "$log" ]] || fail 'writable Local volume config root reached Docker mutation'
  chmod 0700 "$etc_root"

  printf 'do-not-truncate\n' >"$fixture/lock-target"
  chmod 0600 "$fixture/lock-target"
  ln -s -- "$fixture/lock-target" "$fixture/malicious.lock"
  if env PATH="$bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
      PORTFOLIO_ETC_ROOT="$etc_root" \
      PORTFOLIO_LOCAL_VOLUME_LOCK_FILE="$fixture/malicious.lock" \
      PORTFOLIO_TEST_LOCAL_VOLUME_LOG="$log" \
      bash "$LOCAL_VOLUME_PROVISIONER" >"$fixture/lock-link.out" 2>"$fixture/lock-link.err"; then
    fail 'Local volume provisioner followed a malicious lock symlink'
  fi
  grep -F 'Local volume lock is not a root-owned single-link regular file' \
    "$fixture/lock-link.err" >/dev/null ||
    fail 'malicious Local volume lock symlink failed for an unexpected reason'
  [[ "$(tr -d '\r\n' <"$fixture/lock-target")" == do-not-truncate && ! -s "$log" ]] ||
    fail 'malicious Local volume lock symlink was followed or reached Docker'

  : >"$fixture/foreign.lock"
  chown 12345:12345 "$fixture/foreign.lock"
  chmod 0600 "$fixture/foreign.lock"
  if env PATH="$bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
      PORTFOLIO_ETC_ROOT="$etc_root" \
      PORTFOLIO_LOCAL_VOLUME_LOCK_FILE="$fixture/foreign.lock" \
      PORTFOLIO_TEST_LOCAL_VOLUME_LOG="$log" \
      bash "$LOCAL_VOLUME_PROVISIONER" >"$fixture/lock-owner.out" 2>"$fixture/lock-owner.err"; then
    fail 'Local volume provisioner accepted a foreign-owned lock file'
  fi
  grep -F 'Local volume lock is not a root-owned single-link regular file' \
    "$fixture/lock-owner.err" >/dev/null ||
    fail 'foreign-owned Local volume lock failed for an unexpected reason'
  [[ ! -s "$log" ]] || fail 'foreign-owned Local volume lock reached Docker mutation'

  local explicit_id='contract-volume-id-0123456789abcdef'
  printf '%s\n' "$explicit_id" | env \
    PATH="$bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
    PORTFOLIO_ETC_ROOT="$etc_root" \
    PORTFOLIO_LOCAL_VOLUME_LOCK_FILE="$fixture/provision.lock" \
    PORTFOLIO_TEST_LOCAL_VOLUME_LOG="$log" \
    PORTFOLIO_TEST_LOCAL_VOLUME_STATE="$fixture/volume.state" \
    PORTFOLIO_TEST_LOCAL_VOLUME_MOUNTPOINT="$volume_root" \
    bash "$LOCAL_VOLUME_PROVISIONER" --volume-id-stdin \
      >"$fixture/success.out" 2>"$fixture/success.err" || {
        sed -n '1,120p' "$fixture/success.err" >&2
        fail 'disposable Local volume provisioning failed'
      }
  [[ "$(stat -Lc '%u:%g:%a' "$volume_root")" == '10001:10001:700' &&
     "$(stat -Lc '%u:%g:%a' "$volume_root/.portfolio-volume-id")" == \
       '10001:10001:600' ]] ||
    fail 'Local volume provisioner did not establish the API ownership boundary'
  [[ "$(tr -d '\r\n' <"$volume_root/.portfolio-volume-id")" == "$explicit_id" ]] ||
    fail 'Local volume provisioner wrote the wrong opaque marker'
  grep -Fx "PORTFOLIO_LOCAL_VOLUME_NAME=portfolio-local-media" \
    "$etc_root/portfolio.env" >/dev/null || fail 'protected env lacks the exact Local volume name'
  grep -Fx "PORTFOLIO_LOCAL_HOST_ROOT=$volume_root" "$etc_root/portfolio.env" >/dev/null ||
    fail 'protected env lacks the exact Docker Mountpoint'
  grep -Fx "PORTFOLIO_LOCAL_VOLUME_ID=$explicit_id" "$etc_root/portfolio.env" >/dev/null ||
    fail 'protected env does not match the Local volume marker'
  [[ "$(stat -Lc '%u:%a' "$etc_root/portfolio.env")" == '0:600' ]] ||
    fail 'Local volume provisioner weakened portfolio.env'
  setpriv --reuid=10001 --regid=10001 --clear-groups \
    sh -c ": >\"\$1\"; rm -f -- \"\$1\"" sh "$volume_root/api-write-proof" ||
    fail 'API UID cannot write the provisioned Local volume'
  if grep -F "$explicit_id" "$fixture/success.out" "$fixture/success.err" "$log" >/dev/null; then
    fail 'Local volume provisioner leaked the opaque volume ID'
  fi

  local marker_sha environment_sha before_lines holder_pid provision_pid
  marker_sha="$(sha256sum "$volume_root/.portfolio-volume-id" | awk '{print $1}')"
  environment_sha="$(sha256sum "$etc_root/portfolio.env" | awk '{print $1}')"
  cat >"$bin/openssl" <<'STUB'
#!/usr/bin/env bash
printf 'unexpected-openssl:%s\n' "$*" >>"$PORTFOLIO_TEST_LOCAL_VOLUME_LOG"
exit 98
STUB
  chmod 0755 "$bin/openssl"
  env PATH="$bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
    PORTFOLIO_ETC_ROOT="$etc_root" \
    PORTFOLIO_LOCAL_VOLUME_LOCK_FILE="$fixture/provision.lock" \
    PORTFOLIO_TEST_LOCAL_VOLUME_LOG="$log" \
    PORTFOLIO_TEST_LOCAL_VOLUME_STATE="$fixture/volume.state" \
    PORTFOLIO_TEST_LOCAL_VOLUME_MOUNTPOINT="$volume_root" \
    bash "$LOCAL_VOLUME_PROVISIONER" >"$fixture/idempotent.out" 2>"$fixture/idempotent.err" || {
      sed -n '1,120p' "$fixture/idempotent.err" >&2
      fail 'idempotent Local volume provisioning rerun failed'
    }
  [[ "$(sha256sum "$volume_root/.portfolio-volume-id" | awk '{print $1}')" == "$marker_sha" &&
     "$(sha256sum "$etc_root/portfolio.env" | awk '{print $1}')" == "$environment_sha" &&
     ! -e "$etc_root/.local-volume-provision.pending.json" ]] ||
    fail 'idempotent Local volume rerun changed marker/env bytes or left a journal'
  if grep -F 'unexpected-openssl:' "$log" >/dev/null; then
    fail 'idempotent Local volume rerun generated a replacement ID'
  fi

  printf '%s\n' 'conflicting-volume-id-0123456789abcdef' \
    >"$volume_root/.portfolio-volume-id"
  chown 10001:10001 "$volume_root/.portfolio-volume-id"
  chmod 0600 "$volume_root/.portfolio-volume-id"
  if env PATH="$bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
      PORTFOLIO_ETC_ROOT="$etc_root" \
      PORTFOLIO_LOCAL_VOLUME_LOCK_FILE="$fixture/provision.lock" \
      PORTFOLIO_TEST_LOCAL_VOLUME_LOG="$log" \
      PORTFOLIO_TEST_LOCAL_VOLUME_STATE="$fixture/volume.state" \
      PORTFOLIO_TEST_LOCAL_VOLUME_MOUNTPOINT="$volume_root" \
      bash "$LOCAL_VOLUME_PROVISIONER" >"$fixture/marker-mismatch.out" 2>"$fixture/marker-mismatch.err"; then
    fail 'Local volume provisioner accepted a marker/env identity mismatch'
  fi
  grep -F 'existing Local volume marker and portfolio.env binding disagree' \
    "$fixture/marker-mismatch.err" >/dev/null ||
    fail 'Local volume marker mismatch failed for an unexpected reason'
  printf '%s\n' "$explicit_id" >"$volume_root/.portfolio-volume-id"
  chown 10001:10001 "$volume_root/.portfolio-volume-id"
  chmod 0600 "$volume_root/.portfolio-volume-id"

  sed -i "s/^PORTFOLIO_LOCAL_VOLUME_ID=.*/PORTFOLIO_LOCAL_VOLUME_ID=conflicting-env-id-0123456789abcdef/" \
    "$etc_root/portfolio.env"
  if env PATH="$bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
      PORTFOLIO_ETC_ROOT="$etc_root" \
      PORTFOLIO_LOCAL_VOLUME_LOCK_FILE="$fixture/provision.lock" \
      PORTFOLIO_TEST_LOCAL_VOLUME_LOG="$log" \
      PORTFOLIO_TEST_LOCAL_VOLUME_STATE="$fixture/volume.state" \
      PORTFOLIO_TEST_LOCAL_VOLUME_MOUNTPOINT="$volume_root" \
      bash "$LOCAL_VOLUME_PROVISIONER" >"$fixture/env-mismatch.out" 2>"$fixture/env-mismatch.err"; then
    fail 'Local volume provisioner accepted an env/marker identity mismatch'
  fi
  grep -F 'existing Local volume marker and portfolio.env binding disagree' \
    "$fixture/env-mismatch.err" >/dev/null ||
    fail 'Local volume environment mismatch failed for an unexpected reason'
  sed -i "s/^PORTFOLIO_LOCAL_VOLUME_ID=.*/PORTFOLIO_LOCAL_VOLUME_ID=$explicit_id/" \
    "$etc_root/portfolio.env"

  # A concurrent deploy holds the shared lock. Provisioning must not inspect or
  # mutate Docker state until deploy releases it.
  exec 7<>"$fixture/deploy.lock"
  flock -x 7
  before_lines="$(wc -l <"$log" | tr -d '[:space:]')"
  env PATH="$bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
    PORTFOLIO_ETC_ROOT="$etc_root" \
    PORTFOLIO_LOCAL_VOLUME_LOCK_FILE="$fixture/provision.lock" \
    PORTFOLIO_TEST_LOCAL_VOLUME_LOG="$log" \
    PORTFOLIO_TEST_LOCAL_VOLUME_STATE="$fixture/volume.state" \
    PORTFOLIO_TEST_LOCAL_VOLUME_MOUNTPOINT="$volume_root" \
    bash "$LOCAL_VOLUME_PROVISIONER" >"$fixture/concurrent.out" 2>"$fixture/concurrent.err" &
  provision_pid=$!
  sleep 0.25
  kill -0 "$provision_pid" 2>/dev/null || fail 'Local provisioner did not wait for the deploy lock'
  [[ "$(wc -l <"$log" | tr -d '[:space:]')" == "$before_lines" ]] ||
    fail 'Local provisioner reached Docker while a deploy held the shared lock'
  flock -u 7
  exec 7>&-
  wait "$provision_pid" || {
    sed -n '1,120p' "$fixture/concurrent.err" >&2
    fail 'Local provisioner failed after the concurrent deploy lock was released'
  }

  local phase crash_root crash_volume crash_etc crash_id
  for phase in journal marker environment; do
    crash_root="$fixture/crash-$phase"
    crash_volume="$crash_root/volume"
    crash_etc="$crash_root/etc"
    crash_id="contract-crash-$phase-0123456789abcdef"
    mkdir -p "$crash_volume" "$crash_etc"
    chmod 0711 "$crash_root"
    chmod 0755 "$crash_volume"
    chmod 0700 "$crash_etc"
    printf 'PORTFOLIO_JOBS_WORKER_ENABLED=true\n' >"$crash_etc/portfolio.env"
    chmod 0600 "$crash_etc/portfolio.env"
    : >"$crash_root/log"
    if [[ "$phase" == journal ]]; then
      if ! printf '%s\n' "$crash_id" | env \
          PATH="$bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
          PORTFOLIO_ETC_ROOT="$crash_etc" \
          PORTFOLIO_DEPLOY_LOCK_FILE="$crash_root/deploy.lock" \
          PORTFOLIO_LOCAL_VOLUME_LOCK_FILE="$crash_root/local.lock" \
          PORTFOLIO_TEST_LOCAL_VOLUME_LOG="$crash_root/log" \
          PORTFOLIO_TEST_LOCAL_VOLUME_STATE="$crash_root/volume.state" \
          PORTFOLIO_TEST_LOCAL_VOLUME_MOUNTPOINT="$crash_volume" \
          PORTFOLIO_CONTRACT_TEST_MODE= PORTFOLIO_CONTRACT_TEST_ROOT= \
          PORTFOLIO_TEST_LOCAL_VOLUME_CRASH_AFTER=journal \
          bash "$LOCAL_VOLUME_PROVISIONER" --volume-id-stdin \
            >"$crash_root/inherited-env.out" 2>"$crash_root/inherited-env.err"; then
        sed -n '1,120p' "$crash_root/inherited-env.err" >&2
        fail 'production Local volume path honored a test crash variable without contract context'
      fi
      [[ ! -e "$crash_etc/.local-volume-provision.pending.json" ]] ||
        fail 'production Local volume path left a pending test crash journal'
      rm -f -- "$crash_volume/.portfolio-volume-id"
      chown 0:0 "$crash_volume"
      chmod 0755 "$crash_volume"
      printf 'PORTFOLIO_JOBS_WORKER_ENABLED=true\n' >"$crash_etc/portfolio.env"
      chmod 0600 "$crash_etc/portfolio.env"
      : >"$crash_root/log"
    fi
    if printf '%s\n' "$crash_id" | env \
        PATH="$bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
        PORTFOLIO_ETC_ROOT="$crash_etc" \
        PORTFOLIO_DEPLOY_LOCK_FILE="$crash_root/deploy.lock" \
        PORTFOLIO_LOCAL_VOLUME_LOCK_FILE="$crash_root/local.lock" \
        PORTFOLIO_TEST_LOCAL_VOLUME_LOG="$crash_root/log" \
        PORTFOLIO_TEST_LOCAL_VOLUME_STATE="$crash_root/volume.state" \
        PORTFOLIO_TEST_LOCAL_VOLUME_MOUNTPOINT="$crash_volume" \
        PORTFOLIO_TEST_LOCAL_VOLUME_CRASH_AFTER="$phase" \
        bash "$LOCAL_VOLUME_PROVISIONER" --volume-id-stdin \
          >"$crash_root/crash.out" 2>"$crash_root/crash.err"; then
      fail "Local volume $phase SIGKILL fixture unexpectedly completed"
    fi
    [[ -f "$crash_etc/.local-volume-provision.pending.json" &&
       "$(stat -Lc '%u:%g:%a:%h' "$crash_etc/.local-volume-provision.pending.json")" == 0:0:600:1 ]] ||
      fail "Local volume $phase SIGKILL did not retain a safe pending journal"
    env PATH="$bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
      PORTFOLIO_ETC_ROOT="$crash_etc" \
      PORTFOLIO_DEPLOY_LOCK_FILE="$crash_root/deploy.lock" \
      PORTFOLIO_LOCAL_VOLUME_LOCK_FILE="$crash_root/local.lock" \
      PORTFOLIO_TEST_LOCAL_VOLUME_LOG="$crash_root/log" \
      PORTFOLIO_TEST_LOCAL_VOLUME_STATE="$crash_root/volume.state" \
      PORTFOLIO_TEST_LOCAL_VOLUME_MOUNTPOINT="$crash_volume" \
      bash "$LOCAL_VOLUME_PROVISIONER" >"$crash_root/recover.out" 2>"$crash_root/recover.err" || {
        sed -n '1,120p' "$crash_root/recover.err" >&2
        fail "Local volume $phase SIGKILL recovery failed"
      }
    [[ ! -e "$crash_etc/.local-volume-provision.pending.json" &&
       "$(tr -d '\r\n' <"$crash_volume/.portfolio-volume-id")" == "$crash_id" ]] ||
      fail "Local volume $phase SIGKILL recovery did not complete the exact binding"
    grep -Fx "PORTFOLIO_LOCAL_VOLUME_ID=$crash_id" "$crash_etc/portfolio.env" >/dev/null ||
      fail "Local volume $phase SIGKILL recovery wrote the wrong environment identity"
  done

  grep -F 'Fixed global order: every provision attempt takes deploy first, then Local.' \
    "$LOCAL_VOLUME_PROVISIONER" >/dev/null ||
    fail 'Local volume provisioner does not document its global lock order'

  rm -f -- "$fixture/volume.state"
  chown 0:0 "$volume_root"
  chmod 0755 "$volume_root"
  printf '%s\n' "$explicit_id" | env \
    PATH="$bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
    PORTFOLIO_ETC_ROOT="$etc_root" \
    PORTFOLIO_LOCAL_VOLUME_LOCK_FILE="$fixture/provision.lock" \
    PORTFOLIO_TEST_LOCAL_VOLUME_LOG="$log" \
    PORTFOLIO_TEST_LOCAL_VOLUME_STATE="$fixture/volume.state" \
    PORTFOLIO_TEST_LOCAL_VOLUME_MOUNTPOINT="$volume_root" \
    PORTFOLIO_TEST_LOCAL_VOLUME_ROLE=foreign \
    bash "$LOCAL_VOLUME_PROVISIONER" --volume-id-stdin \
      >"$fixture/foreign.out" 2>"$fixture/foreign.err" &&
    fail 'foreign-labeled Local volume unexpectedly passed provisioning'
  grep -F 'identity is ambiguous or not provisioner-owned' "$fixture/foreign.err" >/dev/null ||
    fail 'foreign Local volume failed for an unexpected reason'
  unset PORTFOLIO_DEPLOY_LOCK_FILE
}

write_rollback_release() {
  local root="$1"
  local release_id="$2"
  local image_id="$3"
  local release="$root/releases/$release_id"
  mkdir -p "$release/admin" "$release/ops/deploy/scripts" \
    "$release/public-assets/.vite" "$release/public-assets/assets"
  printf '<!doctype html>%s\n' "$release_id" >"$release/admin/index.html"
  printf '{"entry":{"file":"assets/rollback.js"}}\n' \
    >"$release/public-assets/.vite/manifest.json"
  printf 'rollback-asset-%s\n' "$release_id" \
    >"$release/public-assets/assets/rollback.js"
  printf 'services: {}\n' >"$release/ops/deploy/docker-compose.prod.yml"
  jq -Scn \
    --arg release "$release_id" \
    --arg image "$image_id" \
    '{releaseId:$release,apiImageTag:("portfolio-api:"+$release),apiImageId:$image,
      postgresImageRef:("postgres:17-bookworm@sha256:"+("3"*64)),
      postgresImageTag:("portfolio-postgres-17:"+$release),
      postgresImageId:("sha256:"+("4"*64))}' >"$release/release.json"
cat >"$release/ops/deploy/scripts/smoke.sh" <<'STUB'
#!/usr/bin/env bash
printf 'smoke:%s\n' "$*" >>"$PORTFOLIO_TEST_LOG"
release_root="$(cd "$(dirname "$0")/../../.." && pwd -P)"
release_id="$(jq -r .releaseId "$release_root/release.json")"
if [[ "${1:-}" == nginx-local ]]; then
  expected_sha="$(sha256sum "$release_root/public-assets/assets/rollback.js" | awk '{print $1}')"
  [[ "${PORTFOLIO_SMOKE_ASSET_PATH:-}" == assets/rollback.js &&
     "${PORTFOLIO_SMOKE_ASSET_SHA256:-}" == "$expected_sha" ]] || exit 93
fi
if [[ "${1:-}" == nginx-local &&
      "$(tr -d '\r\n' <"$PORTFOLIO_ROOT/current-release")" != \
      'aaaaaaaaaaaa-111111111111' ]]; then
  exit 92
fi
if [[ "${PORTFOLIO_TEST_SMOKE_FAIL:-false}" == true &&
      "$release_id" == 'bbbbbbbbbbbb-222222222222' ]]; then
  exit 94
fi
if [[ "${PORTFOLIO_TEST_ROLLBACK_SMOKE_FAIL:-false}" == true &&
      "$release_id" == 'aaaaaaaaaaaa-111111111111' ]]; then
  exit 95
fi
STUB
}

reset_rollback_state() {
  local root="$1"
  local current="$2"
  local previous="$3"
  rm -f -- "$root/current-admin" "$root/current-ops"
  ln -s -- "$root/releases/$current/admin" "$root/current-admin"
  ln -s -- "$root/releases/$current/ops" "$root/current-ops"
  printf '%s\n' "$current" >"$root/current-release"
  printf '%s\n' "$previous" >"$root/previous-release"
  printf 'POSTGRES_IMAGE=portfolio-postgres-17:%s\n' "$current" \
    >"$WORK_DIRECTORY/rollback-etc/release.env"
  printf 'PORTFOLIO_IMAGE=portfolio-api:%s\n' "$current" \
    >>"$WORK_DIRECTORY/rollback-etc/release.env"
  printf 'PORTFOLIO_RELEASE_ID=%s\n' "$current" \
    >>"$WORK_DIRECTORY/rollback-etc/release.env"
  chmod 0640 "$WORK_DIRECTORY/rollback-etc/release.env"
}

test_rollback_state_machine() {
  [[ -f "$ROLLBACK" ]] || fail 'rollback controller is missing'
  local root="$WORK_DIRECTORY/rollback-root"
  local etc_root="$WORK_DIRECTORY/rollback-etc"
  local bin="$WORK_DIRECTORY/rollback-bin"
  local nginx="$WORK_DIRECTORY/rollback-nginx"
  local current='aaaaaaaaaaaa-111111111111'
  local previous='bbbbbbbbbbbb-222222222222'
  local current_image previous_image
  current_image="sha256:$(printf '1%.0s' {1..64})"
  previous_image="sha256:$(printf '2%.0s' {1..64})"
  mkdir -p "$root/releases" "$etc_root" "$bin" "$nginx/sbin" "$nginx/conf"
  write_rollback_release "$root" "$current" "$current_image"
  write_rollback_release "$root" "$previous" "$previous_image"

  cat >"$bin/docker" <<'STUB'
#!/usr/bin/env bash
printf 'docker:%s\n' "$*" >>"$PORTFOLIO_TEST_LOG"
case "${1:-}:${2:-}" in
  save:*)
    reference="${2:?image reference required}"
    case "$reference" in
      portfolio-api:aaaaaaaaaaaa-111111111111)
        sha="$(printf '%64s' '' | tr ' ' "${PORTFOLIO_TEST_CURRENT_API_DIGIT:-1}")"
        ;;
      portfolio-api:bbbbbbbbbbbb-222222222222) sha="$(printf '2%.0s' {1..64})" ;;
      portfolio-api:dddddddddddd-444444444444) sha="$(printf '3%.0s' {1..64})" ;;
      portfolio-postgres-17:*) sha="$(printf '4%.0s' {1..64})" ;;
      *) exit 1 ;;
    esac
    work="$(mktemp -d)"
    trap 'rm -rf -- "$work"' EXIT
    printf 'layer\n' >"$work/layer.tar"
    jq -cn --arg config "$sha.json" --arg reference "$reference" \
      '[{Config:$config,RepoTags:[$reference],Layers:["layer.tar"]}]' \
      >"$work/manifest.json"
    tar -C "$work" -cf - manifest.json layer.tar
    ;;
  image:inspect)
    reference="${*: -1}"
    case "$reference" in
      portfolio-api:aaaaaaaaaaaa-111111111111)
        printf 'sha256:%064d\n' 0 | tr '0' '1'
        ;;
      portfolio-api:bbbbbbbbbbbb-222222222222)
        printf 'sha256:%064d\n' 0 | tr '0' '2'
        ;;
      postgres:17-bookworm@sha256:*)
        printf 'sha256:%064d\n' 0 | tr '0' '4'
        ;;
      portfolio-postgres-17:*)
        printf 'sha256:%064d\n' 0 | tr '0' '4'
        ;;
      *) exit 1 ;;
    esac
    ;;
  compose:*)
    if [[ "$*" == *' ps -q portfolio-api' ]]; then
      printf '%s\n' 'rollback-api-container'
    fi
    ;;
  inspect:*) printf '%s\n' 'healthy' ;;
  *) exit 1 ;;
esac
STUB
  chmod 0755 "$bin/docker"

  cat >"$nginx/sbin/nginx" <<'STUB'
#!/usr/bin/env bash
printf 'nginx:%s\n' "$*" >>"$PORTFOLIO_TEST_LOG"
exit "${PORTFOLIO_TEST_NGINX_EXIT:-0}"
STUB
  chmod 0755 "$nginx/sbin/nginx"
  printf 'events {}\n' >"$nginx/conf/nginx.conf"
  printf 'NGINX_BIN=%s\nNGINX_PREFIX=%s/\nNGINX_CONF=%s\nNGINX_LOCAL_PORT=18443\n' \
    "$nginx/sbin/nginx" "$nginx" "$nginx/conf/nginx.conf" >"$etc_root/nginx.env"
  chmod 0640 "$etc_root/nginx.env"

  reset_rollback_state "$root" "$current" "$previous"
  local -a protected_rollback_environment=(
    "PATH=$bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    "PORTFOLIO_TEST_LOG=$WORK_DIRECTORY/rollback-protected.log"
    "PORTFOLIO_ROOT=$root"
    "PORTFOLIO_ETC_ROOT=$etc_root"
    'PORTFOLIO_DEPLOY_GROUP=root'
    "PORTFOLIO_DEPLOY_LOCK_FILE=$WORK_DIRECTORY/rollback.lock"
    'PORTFOLIO_ROLLBACK_READY_TIMEOUT_SECONDS=2'
  )
  : >"$WORK_DIRECTORY/rollback-protected.log"
  chmod 0660 "$etc_root/nginx.env"
  if env "${protected_rollback_environment[@]}" bash "$ROLLBACK" \
      >"$WORK_DIRECTORY/rollback-nginx-mode.out" 2>&1; then
    fail 'rollback accepted a group-writable nginx.env'
  fi
  grep -F 'nginx.env owner, group, or mode is unsafe' \
    "$WORK_DIRECTORY/rollback-nginx-mode.out" >/dev/null || {
      sed -n '1,120p' "$WORK_DIRECTORY/rollback-nginx-mode.out" >&2
      fail 'unsafe nginx.env mode failed for an unexpected reason'
    }
  chmod 0640 "$etc_root/nginx.env"

  chown 10001:0 "$etc_root/release.env"
  if env "${protected_rollback_environment[@]}" bash "$ROLLBACK" \
      >"$WORK_DIRECTORY/rollback-release-owner.out" 2>&1; then
    fail 'rollback accepted a non-root-owned release.env'
  fi
  grep -F 'release.env owner, group, or mode is unsafe' \
    "$WORK_DIRECTORY/rollback-release-owner.out" >/dev/null ||
    fail 'unsafe release.env owner failed for an unexpected reason'
  chown 0:0 "$etc_root/release.env"

  mv "$etc_root/nginx.env" "$etc_root/nginx.env.regular"
  ln -s "$etc_root/nginx.env.regular" "$etc_root/nginx.env"
  if env "${protected_rollback_environment[@]}" bash "$ROLLBACK" \
      >"$WORK_DIRECTORY/rollback-nginx-link.out" 2>&1; then
    fail 'rollback accepted a linked nginx.env path'
  fi
  grep -F 'nginx.env is missing, linked, or not absolute' \
    "$WORK_DIRECTORY/rollback-nginx-link.out" >/dev/null ||
    fail 'linked nginx.env failed for an unexpected reason'
  rm -f "$etc_root/nginx.env"
  mv "$etc_root/nginx.env.regular" "$etc_root/nginx.env"

  chown 12345:12345 "$root"
  if env "${protected_rollback_environment[@]}" bash "$ROLLBACK" \
      >"$WORK_DIRECTORY/rollback-root-owner.out" 2>&1; then
    fail 'rollback accepted an attacker-owned PORTFOLIO_ROOT'
  fi
  grep -F 'PORTFOLIO_ROOT is non-canonical or not owned by root' \
    "$WORK_DIRECTORY/rollback-root-owner.out" >/dev/null ||
    fail 'attacker-owned rollback root failed for an unexpected reason'
  [[ "$(stat -Lc '%a' "$root")" == 700 ]] ||
    fail 'rollback normalized an attacker-owned root before rejection'
  chown 0:0 "$root"

  chmod 0770 "$root"
  if env "${protected_rollback_environment[@]}" bash "$ROLLBACK" \
      >"$WORK_DIRECTORY/rollback-root-writable.out" 2>&1; then
    fail 'rollback accepted a group-writable PORTFOLIO_ROOT'
  fi
  grep -F 'PORTFOLIO_ROOT is writable outside root' \
    "$WORK_DIRECTORY/rollback-root-writable.out" >/dev/null ||
    fail 'group-writable rollback root failed for an unexpected reason'
  chmod 0700 "$root"

  : >"$WORK_DIRECTORY/rollback.log"
  env \
    PATH="$bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
    PORTFOLIO_TEST_LOG="$WORK_DIRECTORY/rollback.log" \
    PORTFOLIO_ROOT="$root" \
    PORTFOLIO_ETC_ROOT="$etc_root" \
    PORTFOLIO_DEPLOY_GROUP=root \
    PORTFOLIO_DEPLOY_LOCK_FILE="$WORK_DIRECTORY/rollback.lock" \
    PORTFOLIO_ROLLBACK_READY_TIMEOUT_SECONDS=2 \
    bash "$ROLLBACK" >"$WORK_DIRECTORY/rollback-success.log"

  grep -Fq "PORTFOLIO_RELEASE_ID=$previous" "$etc_root/release.env" ||
    fail 'successful rollback did not switch release.env'
  [[ "$(readlink -f "$root/current-admin")" == "$root/releases/$previous/admin" ]] ||
    fail 'successful rollback did not switch the administrator pointer'
  [[ "$(readlink -f "$root/current-ops")" == "$root/releases/$previous/ops" ]] ||
    fail 'successful rollback did not switch the stable operations pointer'
  [[ "$(<"$root/current-release")" == "$previous" &&
     "$(<"$root/previous-release")" == "$current" ]] ||
    fail 'successful rollback wrote incorrect release markers'
  grep -F 'nginx:' "$WORK_DIRECTORY/rollback.log" >/dev/null ||
    fail 'successful rollback did not test and reload BaoTa Nginx'
  grep -F 'smoke:nginx-local --resolve yychainsaw.xyz:18443:127.0.0.1' \
    "$WORK_DIRECTORY/rollback.log" >/dev/null ||
    fail 'successful rollback did not smoke Nginx before switching markers'
  if grep -E 'docker:.*(^|[[:space:]])down([[:space:]]|$)' \
      "$WORK_DIRECTORY/rollback.log" >/dev/null; then
    fail 'rollback invoked a database-down command'
  fi

  reset_rollback_state "$root" "$current" "$previous"
  : >"$WORK_DIRECTORY/rollback-failure.log"
  if env \
      PATH="$bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
      PORTFOLIO_TEST_LOG="$WORK_DIRECTORY/rollback-failure.log" \
      PORTFOLIO_TEST_SMOKE_FAIL=true \
      PORTFOLIO_ROOT="$root" \
      PORTFOLIO_ETC_ROOT="$etc_root" \
      PORTFOLIO_DEPLOY_GROUP=root \
      PORTFOLIO_DEPLOY_LOCK_FILE="$WORK_DIRECTORY/rollback.lock" \
      PORTFOLIO_ROLLBACK_READY_TIMEOUT_SECONDS=2 \
      bash "$ROLLBACK" >"$WORK_DIRECTORY/rollback-failed.out" 2>&1; then
    fail 'rollback with a failing API smoke unexpectedly succeeded'
  fi
  grep -Fq "PORTFOLIO_RELEASE_ID=$current" "$etc_root/release.env" ||
    fail 'failed rollback did not restore the original release.env'
  [[ "$(readlink -f "$root/current-admin")" == "$root/releases/$current/admin" ]] ||
    fail 'failed rollback did not preserve the original administrator pointer'
  [[ "$(readlink -f "$root/current-ops")" == "$root/releases/$current/ops" ]] ||
    fail 'failed rollback did not preserve the original operations pointer'
  [[ "$(<"$root/current-release")" == "$current" &&
     "$(<"$root/previous-release")" == "$previous" ]] ||
    fail 'failed rollback changed release markers'
  if grep -E 'docker:.*(^|[[:space:]])down([[:space:]]|$)' \
      "$WORK_DIRECTORY/rollback-failure.log" >/dev/null; then
    fail 'failed rollback invoked a database-down command'
  fi
  jq -e '.status == "FAILED"' "$root/rollback-results/$previous.json" >/dev/null ||
    fail 'successfully recovered rollback failure did not record FAILED'
  grep -Fx 'recovery=SUCCEEDED' "$root/rollback-results/$previous.rollback.log" >/dev/null ||
    fail 'rollback recovery did not preserve a successful redacted diagnostic'

  reset_rollback_state "$root" "$current" "$previous"
  : >"$WORK_DIRECTORY/rollback-recovery-failure.log"
  if env \
      PATH="$bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
      PORTFOLIO_TEST_LOG="$WORK_DIRECTORY/rollback-recovery-failure.log" \
      PORTFOLIO_TEST_SMOKE_FAIL=true \
      PORTFOLIO_TEST_ROLLBACK_SMOKE_FAIL=true \
      PORTFOLIO_ROOT="$root" \
      PORTFOLIO_ETC_ROOT="$etc_root" \
      PORTFOLIO_DEPLOY_GROUP=root \
      PORTFOLIO_DEPLOY_LOCK_FILE="$WORK_DIRECTORY/rollback.lock" \
      PORTFOLIO_ROLLBACK_READY_TIMEOUT_SECONDS=2 \
      bash "$ROLLBACK" >"$WORK_DIRECTORY/rollback-recovery-failed.out" 2>&1; then
    fail 'rollback with a failing automatic recovery unexpectedly succeeded'
  fi
  grep -Fq "PORTFOLIO_RELEASE_ID=$current" "$etc_root/release.env" ||
    fail 'rollback recovery failure did not restore release.env bytes'
  jq -e '.status == "ROLLBACK_FAILED"' "$root/rollback-results/$previous.json" >/dev/null ||
    fail 'failed rollback recovery did not record ROLLBACK_FAILED'
  grep -Fx 'nginx-local-smoke=FAILED' \
    "$root/rollback-results/$previous.rollback.log" >/dev/null ||
    fail 'failed rollback recovery diagnostic omitted the failing smoke step'

  # The deliberately failed recovery above must remain retryable by a fresh
  # lock-holding process.  Clear it through the real startup recovery path.
  if [[ -e "$root/.portfolio-switch-journal.json" ||
        -L "$root/.portfolio-switch-journal.json" ]]; then
    if env \
        PATH="$bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
        PORTFOLIO_TEST_LOG="$WORK_DIRECTORY/rollback-retry.log" \
        PORTFOLIO_ROOT="$root" PORTFOLIO_ETC_ROOT="$etc_root" \
        PORTFOLIO_DEPLOY_GROUP=root \
        PORTFOLIO_DEPLOY_LOCK_FILE="$WORK_DIRECTORY/rollback.lock" \
        PORTFOLIO_ROLLBACK_READY_TIMEOUT_SECONDS=2 \
        bash "$ROLLBACK" "$current" \
        >"$WORK_DIRECTORY/rollback-retry.out" 2>"$WORK_DIRECTORY/rollback-retry.err"; then
      fail 'fresh rollback process did not stop after recovering the already-current release'
    fi
    grep -F 'target release is already current' "$WORK_DIRECTORY/rollback-retry.err" >/dev/null ||
      fail 'fresh rollback recovery retry failed for an unexpected reason'
  fi

  local dispatcher="$WORK_DIRECTORY/rollback-dispatch.sh"
  cp -- "$REPOSITORY_ROOT/deploy/backup/backup-dispatch.sh" "$dispatcher"
  chmod 0755 "$dispatcher"
  local phase journal backup recovery_release
  local -a phases=(prepared env api admin ops markers verified)
  for phase in "${phases[@]}"; do
    reset_rollback_state "$root" "$current" "$previous"
    rm -f -- "$root/.portfolio-switch-journal.json" \
      "$etc_root/.portfolio-switch-old-release.env"
    : >"$WORK_DIRECTORY/rollback-journal-$phase.log"
    if env \
        PATH="$bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
        PORTFOLIO_TEST_LOG="$WORK_DIRECTORY/rollback-journal-$phase.log" \
        PORTFOLIO_ROOT="$root" PORTFOLIO_ETC_ROOT="$etc_root" \
        PORTFOLIO_DEPLOY_GROUP=root \
        PORTFOLIO_DEPLOY_LOCK_FILE="$WORK_DIRECTORY/rollback.lock" \
        PORTFOLIO_ROLLBACK_READY_TIMEOUT_SECONDS=2 \
        PORTFOLIO_TEST_SWITCH_CRASH_AFTER="$phase" \
        bash "$ROLLBACK" \
        >"$WORK_DIRECTORY/rollback-journal-$phase.out" \
        2>"$WORK_DIRECTORY/rollback-journal-$phase.err"; then
      fail "SIGKILL after durable rollback phase $phase unexpectedly succeeded"
    fi
    journal="$root/.portfolio-switch-journal.json"
    backup="$etc_root/.portfolio-switch-old-release.env"
    [[ -f "$journal" && ! -L "$journal" &&
       "$(stat -Lc '%u:%g:%h:%a' -- "$journal")" == 0:0:1:600 &&
       -f "$backup" && ! -L "$backup" &&
       "$(stat -Lc '%u:%g:%h:%a' -- "$backup")" == 0:0:1:600 ]] ||
      fail "rollback phase $phase did not retain protected transaction state"
    jq -e --arg phase "$phase" --arg old "$current" --arg new "$previous" '
      .schemaVersion == 1 and .operation == "rollback" and .phase == $phase and
      .oldRelease == $old and .newRelease == $new and .oldPrevious == $new and
      (.operationId | test("^[0-9a-f]{32}$")) and
      (.envBackupSha256 | test("^[0-9a-f]{64}$"))
    ' "$journal" >/dev/null || fail "rollback phase $phase retained malformed journal state"
    if env PORTFOLIO_ROOT="$root" bash "$dispatcher" backup \
        >"$WORK_DIRECTORY/rollback-backup-$phase.out" \
        2>"$WORK_DIRECTORY/rollback-backup-$phase.err"; then
      fail "backup dispatcher ran during pending rollback phase $phase"
    fi
    grep -F 'pending release switch journal forbids backup dispatch' \
      "$WORK_DIRECTORY/rollback-backup-$phase.err" >/dev/null ||
      fail "backup dispatcher did not fail closed during rollback phase $phase"

    if [[ "$phase" == verified ]]; then
      recovery_release="$previous"
    else
      recovery_release="$current"
    fi
    if env \
        PATH="$bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
        PORTFOLIO_TEST_LOG="$WORK_DIRECTORY/rollback-recover-$phase.log" \
        PORTFOLIO_ROOT="$root" PORTFOLIO_ETC_ROOT="$etc_root" \
        PORTFOLIO_DEPLOY_GROUP=root \
        PORTFOLIO_DEPLOY_LOCK_FILE="$WORK_DIRECTORY/rollback.lock" \
        PORTFOLIO_ROLLBACK_READY_TIMEOUT_SECONDS=2 \
        bash "$ROLLBACK" "$recovery_release" \
        >"$WORK_DIRECTORY/rollback-recover-$phase.out" \
        2>"$WORK_DIRECTORY/rollback-recover-$phase.err"; then
      fail "fresh rollback process after phase $phase did not stop at already-current"
    fi
    grep -F 'target release is already current' "$WORK_DIRECTORY/rollback-recover-$phase.err" \
      >/dev/null || fail "fresh rollback process failed to recover phase $phase"
    [[ ! -e "$journal" && ! -L "$journal" && ! -e "$backup" && ! -L "$backup" ]] ||
      fail "fresh rollback process did not clear recovered phase $phase transaction state"
    [[ "$(tr -d '\r\n' <"$root/current-release")" == "$recovery_release" &&
       "$(readlink -f "$root/current-admin")" == "$root/releases/$recovery_release/admin" &&
       "$(readlink -f "$root/current-ops")" == "$root/releases/$recovery_release/ops" ]] ||
      fail "fresh rollback process recovered phase $phase to the wrong release"
    grep -Fq "PORTFOLIO_RELEASE_ID=$recovery_release" "$etc_root/release.env" ||
      fail "fresh rollback process recovered phase $phase to the wrong release.env"
    if [[ "$phase" == verified ]]; then
      [[ "$(tr -d '\r\n' <"$root/previous-release")" == "$current" ]] ||
        fail 'verified rollback recovery did not retain its committed previous marker'
    else
      [[ "$(tr -d '\r\n' <"$root/previous-release")" == "$previous" ]] ||
        fail "rollback phase $phase recovery did not restore the old previous marker"
    fi
  done
}

INSTALL_CASE_ROOT=''
INSTALL_CASE_STATE=''
INSTALL_CASE_LOG=''
INSTALL_CASE_BIN=''
INSTALL_CASE_CONTRACT=''
INSTALL_CASE_COMPLIANCE_VALIDATOR=''
INSTALL_CASE_DISPATCHER=''
INSTALL_CASE_LOCK=''
INSTALL_FAIL_TAG=''

stub_reference_file() {
  local state="$1"
  local reference="$2"
  local key
  key="$(printf '%s' "$reference" | sha256sum | awk '{print $1}')"
  printf '%s/refs/%s\n' "$state" "$key"
}

stub_image_sha() {
  local state="$1"
  local reference="$2"
  local path
  path="$(stub_reference_file "$state" "$reference")"
  [[ -f "$path" ]] || return 1
  tr -d '\r\n' <"$path"
}

seed_stub_image() {
  local state="$1"
  local reference="$2"
  local body="$3"
  local sha path
  sha="$(printf '%s' "$body" | sha256sum | awk '{print $1}')"
  printf '%s' "$body" >"$state/configs/$sha"
  path="$(stub_reference_file "$state" "$reference")"
  printf '%s\n' "$sha" >"$path"
  printf '%s\n' "$sha"
}

prepare_install_case() {
  local label="$1"
  local base="$WORK_DIRECTORY/install-case-$label"
  rm -rf -- "$base"
  INSTALL_CASE_ROOT="$base/root"
  INSTALL_CASE_STATE="$base/docker-state"
  INSTALL_CASE_LOG="$base/commands.log"
  INSTALL_CASE_BIN="$base/bin"
  INSTALL_CASE_CONTRACT="$base/release-contract.sh"
  INSTALL_CASE_COMPLIANCE_VALIDATOR="$base/compliance-contract.py"
  INSTALL_CASE_DISPATCHER="$base/libexec/portfolio/backup-dispatch.sh"
  INSTALL_CASE_LOCK="$base/lock/deploy.lock"
  INSTALL_FAIL_TAG=''
  mkdir -p \
    "$INSTALL_CASE_ROOT" "$INSTALL_CASE_STATE/refs" "$INSTALL_CASE_STATE/configs" \
    "$INSTALL_CASE_STATE/tmp" "$INSTALL_CASE_BIN" "$(dirname "$INSTALL_CASE_LOCK")"
  chmod 0750 "$INSTALL_CASE_ROOT"
  : >"$INSTALL_CASE_LOG"

  cat >"$INSTALL_CASE_BIN/docker" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail

state="${PORTFOLIO_TEST_DOCKER_STATE:?}"
log="${PORTFOLIO_TEST_COMMAND_LOG:?}"

reference_file() {
  local key
  key="$(printf '%s' "$1" | sha256sum | awk '{print $1}')"
  printf '%s/refs/%s\n' "$state" "$key"
}

read_sha() {
  local path
  path="$(reference_file "$1")"
  [[ -f "$path" ]] || return 1
  tr -d '\r\n' <"$path"
}

write_ref() {
  printf '%s\n' "$2" >"$(reference_file "$1")"
}

case "${1:-}:${2:-}" in
  image:inspect)
    reference="${3:?image reference required}"
    if [[ -n "${PORTFOLIO_TEST_DOCKER_BLOCK_ROOT:-}" ]] &&
       mkdir -- "$PORTFOLIO_TEST_DOCKER_BLOCK_ROOT.once" 2>/dev/null; then
      : >"$PORTFOLIO_TEST_DOCKER_BLOCK_ROOT.entered"
      while [[ ! -e "$PORTFOLIO_TEST_DOCKER_BLOCK_ROOT.release" ]]; do
        sleep 0.05
      done
    fi
    printf 'inspect:%s\n' "$reference" >>"$log"
    read_sha "$reference" >/dev/null || exit 1
    printf '{}\n'
    ;;
  image:rm)
    shift 2
    for reference in "$@"; do
      printf 'rm:%s\n' "$reference" >>"$log"
      rm -f -- "$(reference_file "$reference")"
    done
    ;;
  save:*)
    reference="${2:?image reference required}"
    printf 'save:%s\n' "$reference" >>"$log"
    sha="$(read_sha "$reference")" || exit 1
    work="$(mktemp -d "$state/tmp/save.XXXXXX")"
    trap 'rm -rf -- "$work"' EXIT
    cp -- "$state/configs/$sha" "$work/$sha.json"
    printf 'fixture-layer\n' >"$work/layer.tar"
    jq -cn --arg config "$sha.json" --arg reference "$reference" \
      '[{Config:$config,RepoTags:[$reference],Layers:["layer.tar"]}]' \
      >"$work/manifest.json"
    tar -C "$work" --format=ustar -cf - manifest.json "$sha.json" layer.tar
    ;;
  load:*)
    work="$(mktemp -d "$state/tmp/load.XXXXXX")"
    trap 'rm -rf -- "$work"' EXIT
    tar_path="$work/image.tar"
    cat >"$tar_path"
    manifest="$(tar -xOf "$tar_path" manifest.json)"
    reference="$(jq -er '.[0].RepoTags[0]' <<<"$manifest")"
    config="$(jq -er '.[0].Config' <<<"$manifest")"
    [[ "$config" =~ ^([0-9a-f]{64})\.json$ ]] || exit 1
    sha="${BASH_REMATCH[1]}"
    tar -xOf "$tar_path" "$config" >"$work/config"
    [[ "$(sha256sum "$work/config" | awk '{print $1}')" == "$sha" ]] || exit 1
    cp -- "$work/config" "$state/configs/$sha"
    write_ref "$reference" "$sha"
    printf 'load:%s\n' "$reference" >>"$log"
    printf 'Loaded image: %s\n' "$reference"
    ;;
  tag:*)
    source="${2:?source tag required}"
    target="${3:?target tag required}"
    printf 'tag:%s:%s\n' "$source" "$target" >>"$log"
    [[ "$target" != "${PORTFOLIO_TEST_DOCKER_FAIL_TAG:-}" ]] || exit 73
    sha="$(read_sha "$source")" || exit 1
    write_ref "$target" "$sha"
    ;;
  *)
    printf 'unexpected:%s\n' "$*" >>"$log"
    exit 64
    ;;
esac
STUB
  chmod 0755 "$INSTALL_CASE_BIN/docker"

  cat >"$INSTALL_CASE_BIN/mv" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
printf 'mv:' >>"${PORTFOLIO_TEST_COMMAND_LOG:?}"
printf ' %s' "$@" >>"$PORTFOLIO_TEST_COMMAND_LOG"
printf '\n' >>"$PORTFOLIO_TEST_COMMAND_LOG"
exec /usr/bin/mv "$@"
STUB
  chmod 0755 "$INSTALL_CASE_BIN/mv"

  cat >"$INSTALL_CASE_CONTRACT" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
[[ $# -eq 2 && -d "$1" && ! -L "$1" ]]
[[ "$2" == "${PORTFOLIO_TEST_RELEASE_ID:?}" ]]
[[ -f "$1/release.json" && -f "$1/bundle-manifest.json" ]]
STUB
  chmod 0755 "$INSTALL_CASE_CONTRACT"
  cat >"$INSTALL_CASE_COMPLIANCE_VALIDATOR" <<'STUB'
#!/usr/bin/env python3
import argparse
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("--tree", required=True)
parser.add_argument("--release-json", required=True)
options = parser.parse_args()
if not Path(options.tree).is_dir() or not Path(options.release_json).is_file():
    raise SystemExit(1)
STUB
  chmod 0644 "$INSTALL_CASE_COMPLIANCE_VALIDATOR"
}

create_stub_image_archive() {
  local output="$1"
  local reference="$2"
  local body="$3"
  local corrupt="$4"
  local work sha
  work="$(mktemp -d "$WORK_DIRECTORY/image-archive.XXXXXX")"
  printf '%s' "$body" >"$work/config.body"
  sha="$(sha256sum "$work/config.body" | awk '{print $1}')"
  mv -- "$work/config.body" "$work/$sha.json"
  if [[ "$corrupt" == true ]]; then
    printf '%s' '-corrupt' >>"$work/$sha.json"
  fi
  printf 'fixture-layer\n' >"$work/layer.tar"
  jq -cn --arg config "$sha.json" --arg reference "$reference" \
    '[{Config:$config,RepoTags:[$reference],Layers:["layer.tar"]}]' \
    >"$work/manifest.json"
  tar -C "$work" --sort=name --format=ustar --mtime='@0' \
    --owner=0 --group=0 --numeric-owner \
    -cf "$work/image.tar" manifest.json "$sha.json" layer.tar
  zstd -q -f "$work/image.tar" -o "$output"
  rm -rf -- "$work"
  printf '%s\n' "$sha"
}

create_install_bundle() {
  local fixture="$1"
  local corrupt_api="$2"
  local corrupt_postgres="$3"
  local tree="$fixture/tree"
  local release="$tree/portfolio-$RELEASE_ID"
  local api_id postgres_id
  rm -rf -- "$fixture"
  mkdir -p \
    "$release/admin/assets" "$release/public-assets/.vite" \
    "$release/public-assets/assets" "$release/ops/deploy/backup" \
    "$release/images" "$release/compliance"
  printf '<!doctype html>fixture\n' >"$release/admin/index.html"
  printf '{}\n' >"$release/public-assets/.vite/manifest.json"
  printf 'asset\n' >"$release/public-assets/assets/app.js"
  local helper
  for helper in \
    backup-dispatch.sh backup-set.sh backup-database.sh backup-media.sh lib.sh \
    verify-artifact.sh postgres-client.sh validate-media-tar.py notify-failure.sh \
    prune-remote.sh prune-guard.example.sh; do
    cp -- "$REPOSITORY_ROOT/deploy/backup/$helper" \
      "$release/ops/deploy/backup/$helper"
  done
  local private_data
  for private_data in cos-prune-guard.py export-media.sql record-maintenance.sql; do
    cp -- "$REPOSITORY_ROOT/deploy/backup/$private_data" \
      "$release/ops/deploy/backup/$private_data"
  done
  chmod 0700 \
    "$release/ops/deploy/backup/backup-dispatch.sh" \
    "$release/ops/deploy/backup/backup-set.sh" \
    "$release/ops/deploy/backup/backup-database.sh" \
    "$release/ops/deploy/backup/backup-media.sh" \
    "$release/ops/deploy/backup/lib.sh" \
    "$release/ops/deploy/backup/verify-artifact.sh" \
    "$release/ops/deploy/backup/postgres-client.sh" \
    "$release/ops/deploy/backup/validate-media-tar.py" \
    "$release/ops/deploy/backup/notify-failure.sh" \
    "$release/ops/deploy/backup/prune-remote.sh" \
    "$release/ops/deploy/backup/prune-guard.example.sh"
  chmod 0600 \
    "$release/ops/deploy/backup/cos-prune-guard.py" \
    "$release/ops/deploy/backup/export-media.sql" \
    "$release/ops/deploy/backup/record-maintenance.sql"

  api_id="$(create_stub_image_archive \
    "$release/images/portfolio-api.oci.tar.zst" \
    "portfolio-api-archive:$RELEASE_ID" api-config "$corrupt_api")"
  postgres_id="$(create_stub_image_archive \
    "$release/images/postgres-17.oci.tar.zst" \
    "portfolio-postgres-17-archive:$RELEASE_ID" postgres-config "$corrupt_postgres")"
  jq -Scn \
    --arg releaseId "$RELEASE_ID" \
    --arg apiImageId "sha256:$api_id" \
    --arg postgresImageId "sha256:$postgres_id" \
    '{releaseId:$releaseId,apiImageTag:("portfolio-api:"+$releaseId),
      apiImageId:$apiImageId,postgresImageTag:("portfolio-postgres-17:"+$releaseId),
      postgresImageId:$postgresImageId}' >"$release/release.json"

  (
    cd "$release"
    find admin public-assets ops images compliance -type f -print0 | sort -z |
      while IFS= read -r -d '' path; do
        printf '%s  %s\n' "$(sha256sum -- "$path" | awk '{print $1}')" "$path"
      done
  ) >"$release/bundle-manifest.json"

  tar -C "$tree" --sort=name --format=ustar --mtime='@0' \
    --owner=0 --group=0 --numeric-owner --mode='u+rwX,go-rwx' \
    -cf "$fixture/bundle.tar" "portfolio-$RELEASE_ID"
  zstd -q -f "$fixture/bundle.tar" -o "$fixture/bundle.tar.zst"
  jq -Scn \
    --arg releaseId "$RELEASE_ID" \
    --arg archiveSha256 "$(sha256sum "$fixture/bundle.tar.zst" | awk '{print $1}')" \
    --argjson archiveBytes "$(stat -Lc '%s' "$fixture/bundle.tar.zst")" \
    --arg releaseJsonSha256 "$(sha256sum "$release/release.json" | awk '{print $1}')" \
    '{releaseId:$releaseId,archiveSha256:$archiveSha256,archiveBytes:$archiveBytes,
      releaseJsonSha256:$releaseJsonSha256}' >"$fixture/envelope.json"
  printf '%s\n' "$api_id" >"$fixture/api.id"
  printf '%s\n' "$postgres_id" >"$fixture/postgres.id"
}

run_install_case() {
  local bundle="$1"
  local envelope="$2"
  local stdout="$3"
  local stderr="$4"
  shift 4
  env \
    PATH="$INSTALL_CASE_BIN:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
    PORTFOLIO_ROOT="$INSTALL_CASE_ROOT" \
    PORTFOLIO_DEPLOY_LOCK_FILE="$INSTALL_CASE_LOCK" \
    PORTFOLIO_BACKUP_DISPATCH_PATH="$INSTALL_CASE_DISPATCHER" \
    PORTFOLIO_RELEASE_CONTRACT="$INSTALL_CASE_CONTRACT" \
    PORTFOLIO_COMPLIANCE_VALIDATOR="$INSTALL_CASE_COMPLIANCE_VALIDATOR" \
    PORTFOLIO_TEST_DOCKER_STATE="$INSTALL_CASE_STATE" \
    PORTFOLIO_TEST_COMMAND_LOG="$INSTALL_CASE_LOG" \
    PORTFOLIO_TEST_RELEASE_ID="$RELEASE_ID" \
    PORTFOLIO_TEST_DOCKER_FAIL_TAG="$INSTALL_FAIL_TAG" \
    "$@" bash "$INSTALLER" "$bundle" "$envelope" >"$stdout" 2>"$stderr"
}

expect_install_reject() {
  local label="$1"
  local bundle="$2"
  local envelope="$3"
  local expected="$4"
  prepare_install_case "$label"
  if run_install_case "$bundle" "$envelope" \
      "$WORK_DIRECTORY/$label.out" "$WORK_DIRECTORY/$label.err"; then
    fail "$label bundle unexpectedly installed"
  fi
  grep -F "$expected" "$WORK_DIRECTORY/$label.err" >/dev/null ||
    fail "$label bundle failed for an unexpected reason"
  [[ ! -e "$INSTALL_CASE_ROOT/releases/$RELEASE_ID" ]] ||
    fail "$label failure committed a release directory"
  [[ ! -e "$INSTALL_CASE_DISPATCHER" ]] ||
    fail "$label failure installed the stable dispatcher"
}

test_bundle_envelope_rejection() {
  local fixture="$WORK_DIRECTORY/install-envelope-fixture"
  create_install_bundle "$fixture" false false
  jq '.archiveSha256 = ("0" * 64)' "$fixture/envelope.json" \
    >"$fixture/bad-sha.json"
  jq '.archiveBytes += 1' "$fixture/envelope.json" >"$fixture/bad-bytes.json"
  jq '.releaseJsonSha256 = ("f" * 64)' "$fixture/envelope.json" \
    >"$fixture/bad-release-sha.json"

  expect_install_reject envelope-sha "$fixture/bundle.tar.zst" \
    "$fixture/bad-sha.json" 'bundle SHA-256 differs from detached envelope'
  [[ ! -s "$INSTALL_CASE_LOG" ]] || fail 'checksum rejection reached Docker'
  expect_install_reject envelope-bytes "$fixture/bundle.tar.zst" \
    "$fixture/bad-bytes.json" 'bundle byte count differs from detached envelope'
  [[ ! -s "$INSTALL_CASE_LOG" ]] || fail 'byte-count rejection reached Docker'
  expect_install_reject envelope-release-sha "$fixture/bundle.tar.zst" \
    "$fixture/bad-release-sha.json" \
    'embedded release.json SHA-256 differs from detached envelope'
  if grep -E '^(load|tag):' "$INSTALL_CASE_LOG" >/dev/null; then
    fail 'release.json checksum rejection loaded or tagged an image'
  fi
}

test_archive_config_digest_rejection() {
  local api_fixture="$WORK_DIRECTORY/install-api-corrupt"
  local postgres_fixture="$WORK_DIRECTORY/install-postgres-corrupt"
  create_install_bundle "$api_fixture" true false
  expect_install_reject api-config-digest "$api_fixture/bundle.tar.zst" \
    "$api_fixture/envelope.json" 'image Config blob digest mismatch'
  grep -F 'portfolio-api.oci.tar.zst' "$WORK_DIRECTORY/api-config-digest.err" >/dev/null ||
    fail 'API Config mismatch did not identify the API archive'
  if grep -E '^(load|tag):' "$INSTALL_CASE_LOG" >/dev/null; then
    fail 'API Config mismatch loaded or tagged an image'
  fi

  create_install_bundle "$postgres_fixture" false true
  expect_install_reject postgres-config-digest "$postgres_fixture/bundle.tar.zst" \
    "$postgres_fixture/envelope.json" 'image Config blob digest mismatch'
  grep -F 'postgres-17.oci.tar.zst' "$WORK_DIRECTORY/postgres-config-digest.err" >/dev/null ||
    fail 'PostgreSQL Config mismatch did not identify the PostgreSQL archive'
  if grep -E '^(load|tag):' "$INSTALL_CASE_LOG" >/dev/null; then
    fail 'PostgreSQL Config mismatch loaded or tagged an image'
  fi
}

test_target_conflict_before_load() {
  local fixture="$WORK_DIRECTORY/install-conflict-fixture"
  local target="portfolio-api:$RELEASE_ID"
  local conflict_sha
  create_install_bundle "$fixture" false false
  prepare_install_case target-conflict
  conflict_sha="$(seed_stub_image "$INSTALL_CASE_STATE" "$target" conflict-config)"
  if run_install_case "$fixture/bundle.tar.zst" "$fixture/envelope.json" \
      "$WORK_DIRECTORY/target-conflict.out" "$WORK_DIRECTORY/target-conflict.err"; then
    fail 'conflicting pre-existing API target tag unexpectedly installed'
  fi
  grep -F 'conflicting API target tag; no image was loaded' \
    "$WORK_DIRECTORY/target-conflict.err" >/dev/null ||
    fail 'pre-existing target conflict failed for an unexpected reason'
  if grep -E '^(load|tag):' "$INSTALL_CASE_LOG" >/dev/null; then
    fail 'target conflict reached docker load or tag'
  fi
  [[ "$(stub_image_sha "$INSTALL_CASE_STATE" "$target")" == "$conflict_sha" ]] ||
    fail 'target conflict changed the pre-existing tag'
  [[ ! -e "$INSTALL_CASE_ROOT/releases/$RELEASE_ID" &&
     ! -e "$INSTALL_CASE_DISPATCHER" ]] ||
    fail 'target conflict committed filesystem state'
}

test_disposable_tag_failure_cleanup() {
  local fixture="$WORK_DIRECTORY/install-cleanup-fixture"
  local reference
  create_install_bundle "$fixture" false false
  prepare_install_case disposable-cleanup
  INSTALL_FAIL_TAG="portfolio-postgres-17:$RELEASE_ID"
  if run_install_case "$fixture/bundle.tar.zst" "$fixture/envelope.json" \
      "$WORK_DIRECTORY/disposable-cleanup.out" "$WORK_DIRECTORY/disposable-cleanup.err"; then
    fail 'injected PostgreSQL target-tag failure unexpectedly installed'
  fi
  for reference in \
    "portfolio-api-archive:$RELEASE_ID" \
    "portfolio-postgres-17-archive:$RELEASE_ID" \
    "portfolio-api:$RELEASE_ID" \
    "portfolio-postgres-17:$RELEASE_ID"; do
    if stub_image_sha "$INSTALL_CASE_STATE" "$reference" >/dev/null; then
      fail "failed install retained image tag $reference"
    fi
  done
  [[ "$(grep -c '^load:' "$INSTALL_CASE_LOG")" -eq 2 ]] ||
    fail 'cleanup fixture did not load both disposable archives'
  grep -F "tag:portfolio-postgres-17-archive:$RELEASE_ID:portfolio-postgres-17:$RELEASE_ID" \
    "$INSTALL_CASE_LOG" >/dev/null || fail 'cleanup fixture did not reach injected tag failure'
  [[ ! -e "$INSTALL_CASE_ROOT/releases/$RELEASE_ID" &&
     ! -e "$INSTALL_CASE_DISPATCHER" ]] ||
    fail 'failed install retained release or dispatcher state'
}

test_valid_bundle_install() {
  local fixture="$WORK_DIRECTORY/install-valid-fixture"
  local installed dispatcher_source reference private_data
  create_install_bundle "$fixture" false false
  prepare_install_case valid-install
  mkdir -p "$INSTALL_CASE_ROOT/active-ops"
  ln -s -- "$INSTALL_CASE_ROOT/active-ops" "$INSTALL_CASE_ROOT/current-ops"

  run_install_case "$fixture/bundle.tar.zst" "$fixture/envelope.json" \
    "$WORK_DIRECTORY/valid-install.out" "$WORK_DIRECTORY/valid-install.err" || {
      sed -n '1,200p' "$WORK_DIRECTORY/valid-install.err" >&2
      fail 'valid detached bundle did not install'
    }
  [[ "$(tr -d '\r\n' <"$WORK_DIRECTORY/valid-install.out")" == "$RELEASE_ID" ]] ||
    fail 'valid installer did not return the release ID'
  installed="$INSTALL_CASE_ROOT/releases/$RELEASE_ID"
  [[ -d "$installed" && ! -L "$installed" ]] ||
    fail 'valid installer did not commit the immutable release directory'
  [[ "$(readlink -f "$INSTALL_CASE_ROOT/current-ops")" == \
     "$INSTALL_CASE_ROOT/active-ops" ]] ||
    fail 'installing a future release prematurely switched the active operations entry'
  [[ "$(stat -Lc '%a' "$INSTALL_CASE_ROOT")" == 711 &&
     "$(stat -Lc '%a' "$INSTALL_CASE_ROOT/releases")" == 755 &&
     "$(stat -Lc '%a' "$installed")" == 755 &&
     "$(stat -Lc '%a' "$installed/admin")" == 755 &&
     "$(stat -Lc '%a' "$installed/admin/index.html")" == 644 ]] ||
    fail 'installed administrator tree is not normalized for read-only Nginx access'
  if find -P "$installed/ops" "$installed/images" -perm /0077 -print -quit | grep -q .; then
    fail 'installed operations or image content is readable by a non-root account'
  fi
  if find -P "$installed/ops" "$installed/images" -type d \
      ! -perm 0700 -print -quit | grep -q .; then
    fail 'installed operations or image directory mode is not exactly 0700'
  fi
  [[ "$(stat -Lc '%a' "$installed/images/portfolio-api.oci.tar.zst")" == 600 &&
     "$(stat -Lc '%a' "$installed/images/postgres-17.oci.tar.zst")" == 600 ]] ||
    fail 'installed image archives are not exactly mode 0600'
  chmod 0711 "$WORK_DIRECTORY" "$(dirname "$INSTALL_CASE_ROOT")"
  setpriv --reuid=65534 --regid=65534 --clear-groups \
    grep -F '<!doctype html>fixture' "$installed/admin/index.html" >/dev/null ||
    fail 'a non-root Nginx fixture cannot traverse and read the installed administrator tree'
  dispatcher_source="$installed/ops/deploy/backup/backup-dispatch.sh"
  cmp -s -- "$dispatcher_source" "$INSTALL_CASE_DISPATCHER" ||
    fail 'stable dispatcher bytes differ from the installed release'
  [[ "$(stat -Lc '%u:%g:%a' "$INSTALL_CASE_DISPATCHER")" == '0:0:755' ]] ||
    fail 'stable dispatcher is not root:root mode 0755'
  local helper
  for helper in \
    backup-dispatch.sh backup-set.sh backup-database.sh backup-media.sh lib.sh \
    verify-artifact.sh postgres-client.sh validate-media-tar.py notify-failure.sh \
    prune-remote.sh prune-guard.example.sh; do
    [[ -x "$installed/ops/deploy/backup/$helper" &&
       "$(stat -Lc '%a' "$installed/ops/deploy/backup/$helper")" == 700 ]] ||
      fail "installed release-local backup helper is not exactly mode 0700: $helper"
  done
  for private_data in cos-prune-guard.py export-media.sql record-maintenance.sql; do
    [[ "$(stat -Lc '%a' "$installed/ops/deploy/backup/$private_data")" == 600 ]] ||
      fail "installed release-local backup data is not exactly mode 0600: $private_data"
  done
  # shellcheck disable=SC2016
  grep -F 'ln -- "$candidate" "$BACKUP_DISPATCH_PATH"' "$INSTALLER" >/dev/null ||
    fail 'stable dispatcher is not published by an atomic create-only link'
  if grep -E "^mv: .* $INSTALL_CASE_DISPATCHER$" "$INSTALL_CASE_LOG" >/dev/null; then
    fail 'stable dispatcher was installed by an overwrite-capable rename'
  fi
  if find "$(dirname "$INSTALL_CASE_DISPATCHER")" -maxdepth 1 \
      -name '.backup-dispatch.*' -print -quit | grep -q .; then
    fail 'stable dispatcher install left a candidate or rollback file'
  fi

  [[ "$(stub_image_sha "$INSTALL_CASE_STATE" "portfolio-api:$RELEASE_ID")" == \
     "$(<"$fixture/api.id")" ]] || fail 'API target tag has the wrong portable identity'
  [[ "$(stub_image_sha "$INSTALL_CASE_STATE" "portfolio-postgres-17:$RELEASE_ID")" == \
     "$(<"$fixture/postgres.id")" ]] || fail 'PostgreSQL target tag has the wrong portable identity'
  for reference in \
    "portfolio-api-archive:$RELEASE_ID" \
    "portfolio-postgres-17-archive:$RELEASE_ID"; do
    if stub_image_sha "$INSTALL_CASE_STATE" "$reference" >/dev/null; then
      fail "successful install retained disposable tag $reference"
    fi
    grep -F "rm:$reference" "$INSTALL_CASE_LOG" >/dev/null ||
      fail "successful install did not remove disposable tag $reference"
  done
}

test_future_install_dispatcher_invariant() {
  local fixture="$WORK_DIRECTORY/install-future-dispatcher-fixture"
  local current_id='111111111111-222222222222' current_dispatcher stable_sha
  create_install_bundle "$fixture" false false
  prepare_install_case future-dispatcher
  current_dispatcher="$INSTALL_CASE_ROOT/releases/$current_id/ops/deploy/backup/backup-dispatch.sh"
  mkdir -p "$(dirname "$current_dispatcher")" "$(dirname "$INSTALL_CASE_DISPATCHER")"
  printf '#!/usr/bin/env bash\nprintf current-dispatcher\\n\n' >"$current_dispatcher"
  chmod 0700 "$current_dispatcher"
  install -o root -g root -m 0755 -- "$current_dispatcher" "$INSTALL_CASE_DISPATCHER"
  printf '%s\n' "$current_id" >"$INSTALL_CASE_ROOT/current-release"
  ln -s -- "$INSTALL_CASE_ROOT/releases/$current_id/ops" "$INSTALL_CASE_ROOT/current-ops"
  stable_sha="$(sha256sum "$INSTALL_CASE_DISPATCHER" | awk '{print $1}')"

  if run_install_case "$fixture/bundle.tar.zst" "$fixture/envelope.json" \
      "$WORK_DIRECTORY/future-dispatcher.out" "$WORK_DIRECTORY/future-dispatcher.err"; then
    fail 'future release with a different dispatcher unexpectedly installed'
  fi
  grep -F 'incoming release backup dispatcher differs from the create-only stable dispatcher' \
    "$WORK_DIRECTORY/future-dispatcher.err" >/dev/null ||
    fail 'future dispatcher mismatch failed for an unexpected reason'
  [[ "$(sha256sum "$INSTALL_CASE_DISPATCHER" | awk '{print $1}')" == "$stable_sha" &&
     "$(stat -Lc '%u:%g:%a:%h' "$INSTALL_CASE_DISPATCHER")" == 0:0:755:1 ]] ||
    fail 'rejected future install changed the stable dispatcher'
  cmp -s -- "$current_dispatcher" "$INSTALL_CASE_DISPATCHER" ||
    fail 'stable dispatcher no longer matches current-release after future install rejection'
  [[ "$(tr -d '\r\n' <"$INSTALL_CASE_ROOT/current-release")" == "$current_id" &&
     "$(readlink -f "$INSTALL_CASE_ROOT/current-ops")" == \
       "$INSTALL_CASE_ROOT/releases/$current_id/ops" &&
     ! -e "$INSTALL_CASE_ROOT/releases/$RELEASE_ID" ]] ||
    fail 'rejected future install changed a current pointer or installed the target release'
  [[ ! -s "$INSTALL_CASE_LOG" ]] ||
    fail 'future dispatcher mismatch reached Docker before failing closed'
}

test_installer_exclusive_lock() {
  local fixture="$WORK_DIRECTORY/install-lock-fixture"
  local block="$WORK_DIRECTORY/install-lock-block" first_pid second_pid before_lines
  create_install_bundle "$fixture" false false
  prepare_install_case lock-serialization

  run_install_case "$fixture/bundle.tar.zst" "$fixture/envelope.json" \
    "$WORK_DIRECTORY/install-lock-first.out" "$WORK_DIRECTORY/install-lock-first.err" \
    PORTFOLIO_TEST_DOCKER_BLOCK_ROOT="$block" &
  first_pid=$!
  for _ in {1..200}; do
    [[ -e "$block.entered" ]] && break
    kill -0 "$first_pid" 2>/dev/null || break
    sleep 0.05
  done
  [[ -e "$block.entered" ]] || {
    wait "$first_pid" || true
    fail 'first installer did not reach the blocking Docker fixture'
  }
  [[ "$(find "$INSTALL_CASE_ROOT" -maxdepth 1 -type d \
      -name ".install-${RELEASE_ID}.*" | wc -l | tr -d '[:space:]')" == 1 ]] ||
    fail 'first installer did not create exactly one staging directory under lock'
  [[ ! -e "$INSTALL_CASE_DISPATCHER" ]] ||
    fail 'first installer created the stable dispatcher before validation completed'
  before_lines="$(wc -l <"$INSTALL_CASE_LOG" | tr -d '[:space:]')"

  run_install_case "$fixture/bundle.tar.zst" "$fixture/envelope.json" \
    "$WORK_DIRECTORY/install-lock-second.out" "$WORK_DIRECTORY/install-lock-second.err" \
    PORTFOLIO_TEST_DOCKER_BLOCK_ROOT="$block" &
  second_pid=$!
  sleep 0.25
  kill -0 "$second_pid" 2>/dev/null || fail 'second installer did not wait for the exclusive lock'
  [[ "$(wc -l <"$INSTALL_CASE_LOG" | tr -d '[:space:]')" == "$before_lines" ]] ||
    fail 'second installer reached Docker while the first installer held the lock'
  [[ "$(find "$INSTALL_CASE_ROOT" -maxdepth 1 -type d \
      -name ".install-${RELEASE_ID}.*" | wc -l | tr -d '[:space:]')" == 1 ]] ||
    fail 'concurrent installer created a second staging directory'

  : >"$block.release"
  wait "$first_pid" || {
    sed -n '1,160p' "$WORK_DIRECTORY/install-lock-first.err" >&2
    fail 'first serialized installer failed'
  }
  wait "$second_pid" || {
    sed -n '1,160p' "$WORK_DIRECTORY/install-lock-second.err" >&2
    fail 'second serialized installer failed'
  }
  [[ -d "$INSTALL_CASE_ROOT/releases/$RELEASE_ID" ]] ||
    fail 'serialized installers did not commit the release'
  cmp -s "$INSTALL_CASE_ROOT/releases/$RELEASE_ID/ops/deploy/backup/backup-dispatch.sh" \
    "$INSTALL_CASE_DISPATCHER" || fail 'serialized install left the wrong stable dispatcher'
  if find "$INSTALL_CASE_ROOT" -maxdepth 1 -type d \
      -name ".install-${RELEASE_ID}.*" -print -quit | grep -q .; then
    fail 'serialized installers left a staging directory'
  fi
}

test_installer_lock_path_rejection() {
  local fixture="$WORK_DIRECTORY/install-lock-reject-fixture"
  create_install_bundle "$fixture" false false
  prepare_install_case lock-parent-mode
  chmod 0770 "$(dirname "$INSTALL_CASE_LOCK")"
  if run_install_case "$fixture/bundle.tar.zst" "$fixture/envelope.json" \
      "$WORK_DIRECTORY/install-lock-reject.out" "$WORK_DIRECTORY/install-lock-reject.err"; then
    fail 'installer accepted a group-writable lock parent'
  fi
  grep -F 'deploy lock parent is writable outside root' \
    "$WORK_DIRECTORY/install-lock-reject.err" >/dev/null ||
    fail 'unsafe installer lock parent failed for an unexpected reason'
  [[ ! -s "$INSTALL_CASE_LOG" && ! -e "$INSTALL_CASE_ROOT/releases" &&
     ! -e "$INSTALL_CASE_DISPATCHER" ]] ||
    fail 'unsafe installer lock path reached staging, Docker, or dispatcher mutation'
}

test_installer_lock_wait_replacement() {
  local fixture="$WORK_DIRECTORY/install-lock-wait-replacement-fixture"
  local replacement holder_pid installer_pid observed candidate round child
  local original_identity target
  create_install_bundle "$fixture" false false
  for replacement in inode symlink fifo directory; do
    prepare_install_case "lock-wait-$replacement"
    : >"$INSTALL_CASE_LOCK"
    chmod 0600 "$INSTALL_CASE_LOCK"
    original_identity="$(stat -Lc '%d:%i' "$INSTALL_CASE_LOCK")"
    bash -c '
      set -euo pipefail
      exec 8<>"$1"
      flock -x 8
      : >"$2"
      while [[ ! -e "$3" ]]; do sleep 0.01; done
    ' bash "$INSTALL_CASE_LOCK" "${INSTALL_CASE_LOCK}.held" \
      "${INSTALL_CASE_LOCK}.release" &
    holder_pid=$!
    while [[ ! -e "${INSTALL_CASE_LOCK}.held" ]]; do sleep 0.01; done

    run_install_case "$fixture/bundle.tar.zst" "$fixture/envelope.json" \
      "$WORK_DIRECTORY/install-lock-$replacement.out" \
      "$WORK_DIRECTORY/install-lock-$replacement.err" &
    installer_pid=$!
    observed=false
    local -a candidates=("$installer_pid")
    for ((round = 0; round < 500; round++)); do
      for candidate in "${candidates[@]}"; do
        if [[ -e "/proc/$candidate/fd/9" &&
              "$(stat -Lc '%d:%i' "/proc/$candidate/fd/9" 2>/dev/null || true)" == \
                "$original_identity" ]]; then
          observed=true
          break 2
        fi
        if [[ -r "/proc/$candidate/task/$candidate/children" ]]; then
          for child in $(<"/proc/$candidate/task/$candidate/children"); do
            [[ " ${candidates[*]} " == *" $child "* ]] || candidates+=("$child")
          done
        fi
      done
      kill -0 "$installer_pid" 2>/dev/null || break
      sleep 0.01
    done
    if [[ "$observed" != true ]]; then
      : >"${INSTALL_CASE_LOCK}.release"
      wait "$holder_pid" || true
      wait "$installer_pid" || true
      sed -n '1,120p' "$WORK_DIRECTORY/install-lock-$replacement.err" >&2
      fail "installer did not block on the original lock for $replacement replacement"
    fi

    rm -f -- "$INSTALL_CASE_LOCK"
    case "$replacement" in
      inode)
        : >"$INSTALL_CASE_LOCK"
        chmod 0600 "$INSTALL_CASE_LOCK"
        ;;
      symlink)
        target="${INSTALL_CASE_LOCK}.target"
        : >"$target"
        chmod 0600 "$target"
        ln -s -- "$target" "$INSTALL_CASE_LOCK"
        ;;
      fifo) mkfifo -- "$INSTALL_CASE_LOCK" ;;
      directory) mkdir -- "$INSTALL_CASE_LOCK" ;;
    esac
    : >"${INSTALL_CASE_LOCK}.release"
    wait "$holder_pid" || fail "lock holder failed for $replacement replacement"
    if wait "$installer_pid"; then
      fail "installer accepted a wait-time lock $replacement replacement"
    fi
    grep -F 'deploy lock changed while waiting' \
      "$WORK_DIRECTORY/install-lock-$replacement.err" >/dev/null || {
        sed -n '1,120p' "$WORK_DIRECTORY/install-lock-$replacement.err" >&2
        fail "wait-time lock $replacement replacement failed unexpectedly"
      }
    [[ ! -s "$INSTALL_CASE_LOG" && ! -e "$INSTALL_CASE_ROOT/releases" &&
       ! -e "$INSTALL_CASE_DISPATCHER" ]] ||
      fail "wait-time lock $replacement replacement reached mutation"
  done

  local hardened_script
  for hardened_script in "$DEPLOYER" "$INSTALLER" "$ROLLBACK" "$PRUNER"; do
    grep -F '8#022' "$hardened_script" >/dev/null ||
      fail "shared lock parent is not group/world-write safe: $hardened_script"
    grep -F 'lock changed while waiting' "$hardened_script" >/dev/null ||
      fail "shared lock is not rebound after flock: $hardened_script"
  done
  grep -F '0o022' "$BOOTSTRAP_INSTALLER" >/dev/null ||
    fail 'bootstrap custom lock parent is not group/world-write safe'
  grep -F 'deploy lock changed while waiting' "$BOOTSTRAP_INSTALLER" >/dev/null ||
    fail 'bootstrap lock is not rebound after flock'
}

test_installer_root_boundary_rejection() {
  local fixture="$WORK_DIRECTORY/install-root-boundary-fixture"
  create_install_bundle "$fixture" false false
  prepare_install_case root-owner
  chown 12345:12345 "$INSTALL_CASE_ROOT"
  if run_install_case "$fixture/bundle.tar.zst" "$fixture/envelope.json" \
      "$WORK_DIRECTORY/install-root-owner.out" "$WORK_DIRECTORY/install-root-owner.err"; then
    fail 'installer accepted an attacker-owned PORTFOLIO_ROOT'
  fi
  grep -F 'PORTFOLIO_ROOT is non-canonical or not owned by root' \
    "$WORK_DIRECTORY/install-root-owner.err" >/dev/null ||
    fail 'attacker-owned PORTFOLIO_ROOT failed for an unexpected reason'
  [[ "$(stat -Lc '%a' "$INSTALL_CASE_ROOT")" == 750 && ! -s "$INSTALL_CASE_LOG" &&
     ! -e "$INSTALL_CASE_ROOT/releases" ]] ||
    fail 'installer mutated an attacker-owned root before rejecting it'

  prepare_install_case root-writable
  chmod 0770 "$INSTALL_CASE_ROOT"
  if run_install_case "$fixture/bundle.tar.zst" "$fixture/envelope.json" \
      "$WORK_DIRECTORY/install-root-writable.out" "$WORK_DIRECTORY/install-root-writable.err"; then
    fail 'installer accepted a group-writable PORTFOLIO_ROOT'
  fi
  grep -F 'PORTFOLIO_ROOT is writable outside root' \
    "$WORK_DIRECTORY/install-root-writable.err" >/dev/null ||
    fail 'writable PORTFOLIO_ROOT failed for an unexpected reason'
  [[ "$(stat -Lc '%a' "$INSTALL_CASE_ROOT")" == 770 && ! -s "$INSTALL_CASE_LOG" &&
     ! -e "$INSTALL_CASE_ROOT/releases" ]] ||
    fail 'installer normalized or used an unsafe root before rejecting it'
}

test_offline_bundle_installer() {
  [[ -f "$INSTALLER" ]] || fail 'release bundle installer is missing'
  [[ "$(id -u)" -eq 0 ]] || fail 'bundle installer contract requires a disposable root environment'
  test_bundle_envelope_rejection
  test_archive_config_digest_rejection
  test_target_conflict_before_load
  test_disposable_tag_failure_cleanup
  test_installer_lock_path_rejection
  test_installer_root_boundary_rejection
  test_future_install_dispatcher_invariant
  test_installer_exclusive_lock
  test_valid_bundle_install
}

test_bootstrap_kit_contract() {
  local fixture="$WORK_DIRECTORY/bootstrap-kit" release output archive envelope installer
  local commit='1234567890abcdef1234567890abcdef12345678' expected_sha installer_sha extracted
  release="$fixture/release"
  output="$fixture/output"
  mkdir -p "$release/ops/deploy/scripts" "$output"
  jq -Scn --arg commit "$commit" '{gitCommit:$commit}' >"$release/release.json"
  cp -- "$BOOTSTRAP_INSTALLER" "$release/ops/deploy/scripts/install-bootstrap-kit.py"
  cp -- "$BACKUP_UNIT_INSTALLER" "$release/ops/deploy/scripts/install-backup-units.sh"
  cp -- "$INSTALLER" "$release/ops/deploy/scripts/install-release-bundle.sh"
  cp -- "$UPLOAD_PROMOTER" "$release/ops/deploy/scripts/promote-release-upload.sh"
  cp -- "$VALIDATOR" "$release/ops/deploy/scripts/validate-bundle-tar.py"
  cp -- "$COMPLIANCE_VALIDATOR" "$release/ops/deploy/scripts/verify-compliance-tree.py"
  cp -- "$LOCAL_VOLUME_PROVISIONER" "$release/ops/deploy/scripts/provision-local-volume.sh"

  archive="$(bash "$BOOTSTRAP_PACKAGER" "$release" "$output")" ||
    fail 'valid commit-bound bootstrap kit did not package'
  envelope="$archive.envelope.json"
  installer="$(dirname "$archive")/install-bootstrap-kit-$commit.py"
  [[ "$archive" == "$output/portfolio-bootstrap-$commit/portfolio-bootstrap-$commit.tar.zst" &&
     -f "$archive" && ! -L "$archive" && -f "$envelope" && ! -L "$envelope" &&
     -f "$installer" && ! -L "$installer" ]] ||
    fail 'bootstrap packager did not atomically publish the expected kit and standalone installer'
  expected_sha="$(sha256sum "$archive" | awk '{print $1}')"
  installer_sha="$(sha256sum "$installer" | awk '{print $1}')"
  printf '%s\n' "$expected_sha" >"$fixture/independent-expected-sha256"
  printf '%s\n' "$(sha256sum "$installer" | awk '{print $1}')" \
    >"$fixture/independent-installer-sha256"
  printf '%s\n' "$(stat -Lc '%s' "$installer")" \
    >"$fixture/independent-installer-bytes"
  [[ "$(sha256sum "$installer" | awk '{print $1}')" == \
       "$(<"$fixture/independent-installer-sha256")" &&
     "$(stat -Lc '%s' "$installer")" == \
       "$(<"$fixture/independent-installer-bytes")" ]] ||
    fail 'standalone installer did not pass the required pre-execution independent trust check'
  cp -- "$installer" "$fixture/tampered-standalone.py"
  printf '# tamper\n' >>"$fixture/tampered-standalone.py"
  if [[ "$(sha256sum "$fixture/tampered-standalone.py" | awk '{print $1}')" == \
        "$(<"$fixture/independent-installer-sha256")" ]]; then
    fail 'pre-execution independent trust check accepted a replaced standalone installer'
  fi
  [[ "$(jq -r '.gitCommit' "$envelope")" == "$commit" &&
     "$(jq -r '.archiveSha256' "$envelope")" == "$expected_sha" &&
     "$(stat -Lc '%s' "$archive")" == "$(jq -r '.archiveBytes' "$envelope")" &&
     "$(jq -r '.bootstrapInstallerSha256' "$envelope")" == \
       "$(sha256sum "$installer" | awk '{print $1}')" ]] ||
    fail 'bootstrap envelope does not bind the exact commit, kit, and standalone installer'

  extracted="$fixture/extracted"
  mkdir -p "$extracted"
  zstd -q -dc "$archive" | tar -xf - -C "$extracted"
  jq -e --arg commit "$commit" '
    .schemaVersion == 1 and .gitCommit == $commit and
    .installRoot == "/usr/local/libexec/portfolio" and
    [.files[].path] == [
      "libexec/portfolio/install-backup-units.sh",
      "libexec/portfolio/install-bootstrap-kit.py",
      "libexec/portfolio/install-release-bundle.sh",
      "libexec/portfolio/promote-release-upload.sh",
      "libexec/portfolio/provision-local-volume.sh",
      "libexec/portfolio/validate-bundle-tar.py",
      "libexec/portfolio/verify-compliance-tree.py"
    ]
  ' "$extracted/portfolio-bootstrap-$commit/bootstrap-manifest.json" >/dev/null ||
    fail 'bootstrap kit manifest is not minimal, exact, and commit-bound'
  cmp -s "$INSTALLER" \
    "$extracted/portfolio-bootstrap-$commit/libexec/portfolio/install-release-bundle.sh" ||
    fail 'bootstrap installer bytes differ from the reviewed release'
  mkdir -p "$fixture/stable" "$fixture/installer-lock"
  chmod 0755 "$fixture/stable"
  chmod 0700 "$fixture/installer-lock"
  env PORTFOLIO_BOOTSTRAP_TEST_MODE=contract \
    PORTFOLIO_DEPLOY_LOCK_FILE="$fixture/installer-lock/deploy.lock" \
    python3 "$installer" --kit "$archive" --envelope "$envelope" \
      --expected-git-commit "$commit" --expected-kit-sha256 "$expected_sha" \
      --expected-kit-bytes "$(stat -Lc '%s' "$archive")" \
      --expected-installer-sha256 "$installer_sha" \
      --expected-installer-bytes "$(stat -Lc '%s' "$installer")" \
      --install-root "$fixture/stable/portfolio" \
      >"$fixture/bootstrap-install.out" 2>"$fixture/bootstrap-install.err" || {
        sed -n '1,160p' "$fixture/bootstrap-install.err" >&2
        fail 'independently bound standalone bootstrap installer failed'
      }
  [[ -f "$fixture/stable/portfolio/install-release-bundle.sh" &&
     -f "$fixture/stable/portfolio/install-bootstrap-kit.py" &&
     "$(stat -Lc '%u:%g:%a' "$fixture/stable/portfolio")" == 0:0:755 ]] ||
    fail 'standalone bootstrap installer did not atomically install the stable root-owned toolset'
  cmp -s "$TMPFILES_CONFIG" /etc/tmpfiles.d/portfolio.conf ||
    fail 'standalone bootstrap installer did not install the reboot-persistent lock namespace contract'
  [[ "$(stat -Lc '%u:%g:%a' /etc/tmpfiles.d/portfolio.conf)" == 0:0:644 ]] ||
    fail 'installed tmpfiles lock namespace contract owner or mode is unsafe'
  if env PORTFOLIO_BOOTSTRAP_TEST_MODE=contract \
      PORTFOLIO_DEPLOY_LOCK_FILE="$fixture/installer-lock/deploy.lock" \
      python3 "$installer" --kit "$archive" --envelope "$envelope" \
        --expected-git-commit "$commit" --expected-kit-sha256 "$expected_sha" \
        --expected-kit-bytes "$(stat -Lc '%s' "$archive")" \
        --expected-installer-sha256 "$installer_sha" \
        --expected-installer-bytes "$(stat -Lc '%s' "$installer")" \
        --install-root "$fixture/stable/portfolio" \
        >"$fixture/bootstrap-overwrite.out" 2>"$fixture/bootstrap-overwrite.err"; then
    fail 'standalone bootstrap installer overwrote an existing stable toolset'
  fi
  grep -F 'overwrite is forbidden' "$fixture/bootstrap-overwrite.err" >/dev/null ||
    fail 'standalone bootstrap create-only failure was not explicit'

  env -u PORTFOLIO_DEPLOY_LOCK_FILE PORTFOLIO_BOOTSTRAP_TEST_MODE=contract \
    python3 "$installer" --kit "$archive" --envelope "$envelope" \
      --expected-git-commit "$commit" --expected-kit-sha256 "$expected_sha" \
      --expected-kit-bytes "$(stat -Lc '%s' "$archive")" \
      --expected-installer-sha256 "$installer_sha" \
      --expected-installer-bytes "$(stat -Lc '%s' "$installer")" \
      --install-root "$fixture/default-lock-install" \
      >"$fixture/default-lock.out" 2>"$fixture/default-lock.err" || {
        sed -n '1,160p' "$fixture/default-lock.err" >&2
        fail 'bootstrap installer could not safely initialize the Ubuntu /run/lock namespace'
      }
  [[ "$(stat -Lc '%u:%g:%a' /run/lock/portfolio)" == 0:0:700 &&
     "$(stat -Lc '%u:%g:%a' /run/lock/portfolio/deploy.lock)" == 0:0:600 ]] ||
    fail 'bootstrap installer did not create the dedicated root-only deploy lock namespace'
  rm -f -- /run/lock/portfolio/deploy.lock
  rmdir -- /run/lock/portfolio
  systemd-tmpfiles --create /etc/tmpfiles.d/portfolio.conf
  [[ "$(stat -Lc '%u:%g:%a' /run/lock/portfolio)" == 0:0:700 ]] ||
    fail 'systemd-tmpfiles did not recreate the lock namespace after simulated reboot cleanup'
  [[ "$(bash "$BOOTSTRAP_PACKAGER" "$release" "$output")" == "$archive" ]] ||
    fail 'verified existing bootstrap kit was not reused idempotently'

  printf 'tamper\n' >>"$archive"
  if bash "$BOOTSTRAP_PACKAGER" "$release" "$output" \
      >"$fixture/tamper.out" 2>"$fixture/tamper.err"; then
    fail 'tampered existing bootstrap kit was silently replaced or accepted'
  fi
  grep -F 'bootstrap archive SHA-256 mismatch' "$fixture/tamper.err" >/dev/null ||
    fail 'tampered bootstrap kit failed for an unexpected reason'
}

test_upload_quarantine_promotion() {
  local fixture="$WORK_DIRECTORY/upload-promotion" root drop verify incoming lock_root
  local release_id='abcdefabcdef-123456789012' uploader_uid=12345
  local bundle envelope bundle_sha envelope_sha bundle_bytes envelope_bytes
  root="$fixture/root"
  drop="$root/quarantine/drop"
  verify="$root/quarantine/verify"
  incoming="$root/incoming"
  lock_root="$fixture/lock"
  mkdir -p "$drop" "$verify" "$incoming" "$lock_root"
  chmod 0711 "$WORK_DIRECTORY" "$fixture" "$root" "$root/quarantine"
  chmod 0700 "$drop" "$verify"
  chmod 0750 "$incoming"
  chown "$uploader_uid:$uploader_uid" "$drop"
  setpriv --reuid="$uploader_uid" --regid="$uploader_uid" --clear-groups \
    sh -c ": >\"\$1\"; rm -f -- \"\$1\"" sh "$drop/uploader-write-proof" ||
    fail 'dedicated uploader cannot write its isolated drop directory'
  if setpriv --reuid="$uploader_uid" --regid="$uploader_uid" --clear-groups \
      sh -c ": >\"\$1\"" sh "$incoming/uploader-escape" 2>/dev/null; then
    fail 'dedicated uploader can write the root-owned incoming directory'
  fi
  bundle="$drop/.portfolio-bundle.part.1234567890abcdef"
  envelope="$drop/.portfolio-envelope.part.fedcba0987654321"
  printf 'immutable bundle fixture\n' >"$bundle"
  bundle_sha="$(sha256sum "$bundle" | awk '{print $1}')"
  bundle_bytes="$(stat -Lc '%s' "$bundle")"
  jq -Scn --arg releaseId "$release_id" --arg archiveSha256 "$bundle_sha" \
    --argjson archiveBytes "$bundle_bytes" \
    '{releaseId:$releaseId,archiveSha256:$archiveSha256,archiveBytes:$archiveBytes,
      releaseJsonSha256:("a" * 64)}' >"$envelope"
  envelope_sha="$(sha256sum "$envelope" | awk '{print $1}')"
  envelope_bytes="$(stat -Lc '%s' "$envelope")"
  chmod 0600 "$bundle" "$envelope"
  chown "$uploader_uid:$uploader_uid" "$bundle" "$envelope"

  env PORTFOLIO_ROOT="$root" PORTFOLIO_UPLOAD_DROP="$drop" \
    PORTFOLIO_UPLOAD_VERIFY_ROOT="$verify" PORTFOLIO_INCOMING_ROOT="$incoming" \
    PORTFOLIO_UPLOAD_UID="$uploader_uid" PORTFOLIO_DEPLOY_LOCK_FILE="$lock_root/deploy.lock" \
    bash "$UPLOAD_PROMOTER" "$bundle" "$bundle_sha" "$bundle_bytes" \
      "$envelope" "$envelope_sha" "$envelope_bytes" "$release_id" \
      >"$fixture/promote.out" 2>"$fixture/promote.err" || {
        sed -n '1,160p' "$fixture/promote.err" >&2
        fail 'valid isolated upload pair did not promote'
      }
  local final_bundle="$incoming/portfolio-$release_id.tar.zst"
  local final_envelope="$final_bundle.envelope.json"
  [[ ! -e "$bundle" && ! -e "$envelope" &&
     "$(stat -Lc '%u:%g:%a:%h' "$final_bundle")" == 0:0:600:1 &&
     "$(stat -Lc '%u:%g:%a:%h' "$final_envelope")" == 0:0:600:1 &&
     "$(sha256sum "$final_bundle" | awk '{print $1}')" == "$bundle_sha" ]] ||
    fail 'promoted upload is not an immutable root-owned no-link final pair'

  printf 'second bundle\n' >"$bundle"
  cp -- "$final_envelope" "$envelope"
  chmod 0600 "$bundle" "$envelope"
  chown "$uploader_uid:$uploader_uid" "$bundle" "$envelope"
  if env PORTFOLIO_ROOT="$root" PORTFOLIO_UPLOAD_DROP="$drop" \
      PORTFOLIO_UPLOAD_VERIFY_ROOT="$verify" PORTFOLIO_INCOMING_ROOT="$incoming" \
      PORTFOLIO_UPLOAD_UID="$uploader_uid" PORTFOLIO_DEPLOY_LOCK_FILE="$lock_root/deploy.lock" \
      bash "$UPLOAD_PROMOTER" "$bundle" "$(sha256sum "$bundle" | awk '{print $1}')" \
        "$(stat -Lc '%s' "$bundle")" "$envelope" \
        "$(sha256sum "$envelope" | awk '{print $1}')" "$(stat -Lc '%s' "$envelope")" \
        "$release_id" >"$fixture/overwrite.out" 2>"$fixture/overwrite.err"; then
    fail 'upload promotion overwrote an existing final release pair'
  fi
  grep -F 'overwrite is forbidden' "$fixture/overwrite.err" >/dev/null ||
    fail 'create-only upload promotion failed for an unexpected reason'
  [[ -f "$bundle" && -f "$envelope" &&
     "$(sha256sum "$final_bundle" | awk '{print $1}')" == "$bundle_sha" ]] ||
    fail 'rejected overwrite consumed its source or changed final incoming bytes'

  local replacement race_root race_drop race_verify race_incoming race_lock
  local race_bundle race_envelope race_sha race_bytes race_envelope_sha race_envelope_bytes
  local holder_pid promoter_pid observed_wait attempt expected_error
  for replacement in symlink fifo foreign-inode; do
    race_root="$fixture/wait-$replacement/root"
    race_drop="$race_root/quarantine/drop"
    race_verify="$race_root/quarantine/verify"
    race_incoming="$race_root/incoming"
    race_lock="$fixture/wait-$replacement/lock"
    mkdir -p "$race_drop" "$race_verify" "$race_incoming" "$race_lock"
    chmod 0711 "$fixture/wait-$replacement" "$race_root" "$race_root/quarantine"
    chmod 0700 "$race_drop" "$race_verify" "$race_lock"
    chmod 0750 "$race_incoming"
    chown "$uploader_uid:$uploader_uid" "$race_drop"
    race_bundle="$race_drop/.bundle.part.0123456789abcdef"
    race_envelope="$race_drop/.envelope.part.fedcba9876543210"
    printf 'lock-pinned-upload\n' >"$race_bundle"
    race_sha="$(sha256sum "$race_bundle" | awk '{print $1}')"
    race_bytes="$(stat -Lc '%s' "$race_bundle")"
    jq -Scn --arg releaseId "$release_id" --arg archiveSha256 "$race_sha" \
      --argjson archiveBytes "$race_bytes" \
      '{releaseId:$releaseId,archiveSha256:$archiveSha256,archiveBytes:$archiveBytes,
        releaseJsonSha256:("b" * 64)}' >"$race_envelope"
    race_envelope_sha="$(sha256sum "$race_envelope" | awk '{print $1}')"
    race_envelope_bytes="$(stat -Lc '%s' "$race_envelope")"
    chmod 0600 "$race_bundle" "$race_envelope"
    chown "$uploader_uid:$uploader_uid" "$race_bundle" "$race_envelope"
    : >"$race_lock/deploy.lock"
    chmod 0600 "$race_lock/deploy.lock"
    bash -c '
      set -euo pipefail
      exec 8<>"$1"
      flock -x 8
      : >"$2"
      while [[ ! -e "$3" ]]; do sleep 0.01; done
    ' bash "$race_lock/deploy.lock" "$race_lock/held" "$race_lock/release" &
    holder_pid=$!
    while [[ ! -e "$race_lock/held" ]]; do sleep 0.01; done
    env PORTFOLIO_ROOT="$race_root" PORTFOLIO_UPLOAD_DROP="$race_drop" \
      PORTFOLIO_UPLOAD_VERIFY_ROOT="$race_verify" PORTFOLIO_INCOMING_ROOT="$race_incoming" \
      PORTFOLIO_UPLOAD_UID="$uploader_uid" \
      PORTFOLIO_DEPLOY_LOCK_FILE="$race_lock/deploy.lock" \
      bash "$UPLOAD_PROMOTER" "$race_bundle" "$race_sha" "$race_bytes" \
        "$race_envelope" "$race_envelope_sha" "$race_envelope_bytes" "$release_id" \
        >"$race_lock/promote.out" 2>"$race_lock/promote.err" &
    promoter_pid=$!
    observed_wait=false
    for attempt in {1..500}; do
      if [[ -e "/proc/$promoter_pid/fd/9" ]]; then
        observed_wait=true
        break
      fi
      kill -0 "$promoter_pid" 2>/dev/null || break
      sleep 0.01
    done
    if [[ "$observed_wait" != true ]]; then
      : >"$race_lock/release"
      wait "$holder_pid" || true
      wait "$promoter_pid" || true
      sed -n '1,120p' "$race_lock/promote.err" >&2
      fail "upload promoter did not block for $replacement replacement test"
    fi
    case "$replacement" in
      symlink)
        # The child shell receives the path as its positional argument.
        # shellcheck disable=SC2016
        setpriv --reuid="$uploader_uid" --regid="$uploader_uid" --clear-groups \
          bash -c 'rm -f -- "$1"; ln -s /etc/passwd "$1"' bash "$race_bundle"
        expected_error='bundle upload is missing, linked, or not absolute'
        ;;
      fifo)
        # The child shell receives the path as its positional argument.
        # shellcheck disable=SC2016
        setpriv --reuid="$uploader_uid" --regid="$uploader_uid" --clear-groups \
          bash -c 'rm -f -- "$1"; mkfifo "$1"; chmod 0600 "$1"' bash "$race_bundle"
        expected_error='bundle upload is missing, linked, or not absolute'
        ;;
      foreign-inode)
        # The child shell receives the path as its positional argument.
        # shellcheck disable=SC2016
        setpriv --reuid="$uploader_uid" --regid="$uploader_uid" --clear-groups \
          bash -c 'printf "replacement inode\n" >"$1.swap"; chmod 0600 "$1.swap"; mv -T -- "$1.swap" "$1"' \
          bash "$race_bundle"
        expected_error='bundle candidate differs from the independent byte/hash record'
        ;;
    esac
    : >"$race_lock/release"
    wait "$holder_pid" || fail "deployment lock holder failed for $replacement test"
    if wait "$promoter_pid"; then
      fail "upload promoter accepted a wait-time $replacement replacement"
    fi
    grep -F "$expected_error" "$race_lock/promote.err" >/dev/null || {
      sed -n '1,120p' "$race_lock/promote.err" >&2
      fail "wait-time $replacement replacement failed for an unexpected reason"
    }
    [[ -z "$(find "$race_incoming" -mindepth 1 -maxdepth 1 -print -quit)" ]] ||
      fail "rejected wait-time $replacement replacement published an incoming file"
  done
}

test_backup_unit_installation() {
  local fixture="$WORK_DIRECTORY/backup-unit-install" root release_id release systemd_root
  local next_release_id next_release
  local stable_dispatcher backup_env systemctl_stub log lock_root readiness runtime_python
  release_id='abcdefabcdef-123456789012'
  root="$fixture/root"
  release="$root/releases/$release_id"
  systemd_root="$fixture/systemd"
  stable_dispatcher="$fixture/libexec/backup-dispatch.sh"
  backup_env="$fixture/etc/backup.env"
  systemctl_stub="$fixture/bin/systemctl"
  log="$fixture/systemctl.log"
  lock_root="$fixture/lock"
  readiness="$fixture/readiness/prune-initial-dry-run.json"
  runtime_python='/opt/portfolio/cos-prune-venv/bin/python3'
  mkdir -p "$release/ops/deploy/systemd" "$release/ops/deploy/backup" \
    "$systemd_root" "$(dirname "$stable_dispatcher")" "$(dirname "$backup_env")" \
    "$(dirname "$systemctl_stub")" "$lock_root"
  printf '%s\n' "$release_id" >"$root/current-release"
  chmod 0640 "$root/current-release"
  cp -- "$REPOSITORY_ROOT/deploy/backup/backup-dispatch.sh" \
    "$release/ops/deploy/backup/backup-dispatch.sh"
  cp -- "$release/ops/deploy/backup/backup-dispatch.sh" "$stable_dispatcher"
  chmod 0755 "$release/ops/deploy/backup/backup-dispatch.sh" "$stable_dispatcher"
  printf 'guard fixture\n' >"$release/ops/deploy/backup/cos-prune-guard.py"
  printf 'requirements fixture\n' >"$release/ops/deploy/backup/requirements-cos-prune.txt"
  printf '#!/usr/bin/env bash\nexit 99\n' \
    >"$release/ops/deploy/backup/prune-guard.example.sh"
  cat >"$release/ops/deploy/backup/prune-remote.sh" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
[[ "$*" == --dry-run ]]
printf 'prune-readiness:dry-run\n' >>"${PORTFOLIO_TEST_SYSTEMCTL_LOG:?}"
[[ "${BACKUP_PRUNE_SENTINEL:-}" == configured ]]
if [[ "${PORTFOLIO_TEST_READINESS_NO_WRITE:-false}" == true ]]; then
  exit 0
fi
readiness="${BACKUP_PRUNE_READINESS_FILE:?}"
parent="$(dirname -- "$readiness")"
install -d -m 0700 -- "$parent"
guard_sha="$(sha256sum -- "${BASH_SOURCE[0]%/*}/cos-prune-guard.py" | awk '{print $1}')"
requirements_sha="$(sha256sum -- "${BASH_SOURCE[0]%/*}/requirements-cos-prune.txt" | awk '{print $1}')"
wrapper_sha="$(sha256sum -- "${BASH_SOURCE[0]%/*}/prune-guard.example.sh" | awk '{print $1}')"
if [[ "${PORTFOLIO_TEST_READINESS_MALFORMED:-false}" == true ]]; then
  printf '{}\n' >"$readiness"
else
  jq -Scn --arg guard "$guard_sha" --arg requirements "$requirements_sha" \
    --arg wrapper "$wrapper_sha" '
      {schemaVersion:1,completedAt:"2026-07-19T12:34:56Z",
       binding:{destination:{accountId:"123456",bucket:"backup-1234567890",
         prefix:"portfolio-prod",principalId:"pruner-role",region:"ap-guangzhou"},
         guardSha256:$guard,requirementsSha256:$requirements,wrapperSha256:$wrapper,
         policy:{daily:7,weekly:4,monthly:6,safetyDays:14}}}
    ' >"$readiness"
fi
chmod 0600 "$readiness"
STUB
  chmod 0600 "$release/ops/deploy/backup/cos-prune-guard.py" \
    "$release/ops/deploy/backup/requirements-cos-prune.txt"
  chmod 0700 "$release/ops/deploy/backup/prune-guard.example.sh" \
    "$release/ops/deploy/backup/prune-remote.sh"
  printf 'services: {}\n' >"$release/ops/deploy/docker-compose.prod.yml"
  jq -Scn --arg releaseId "$release_id" '{releaseId:$releaseId}' >"$release/release.json"
  chmod 0600 "$release/ops/deploy/docker-compose.prod.yml" "$release/release.json"
  local unit
  for unit in portfolio-backup.service portfolio-backup.timer \
    portfolio-backup-prune.service portfolio-backup-prune-readiness.service \
    portfolio-backup-prune.timer; do
    cp -- "$REPOSITORY_ROOT/deploy/systemd/$unit" "$release/ops/deploy/systemd/$unit"
    chmod 0600 "$release/ops/deploy/systemd/$unit"
  done
  next_release_id='bbbbbbbbbbbb-222222222222'
  next_release="$root/releases/$next_release_id"
  cp -a -- "$release" "$next_release"
  jq -Scn --arg releaseId "$next_release_id" '{releaseId:$releaseId}' \
    >"$next_release/release.json"
  chmod 0600 "$next_release/release.json"
  printf '\n# lock-current-release-contract\n' \
    >>"$next_release/ops/deploy/backup/backup-dispatch.sh"
  chmod 0755 "$next_release/ops/deploy/backup/backup-dispatch.sh"
  for unit in portfolio-backup.service portfolio-backup.timer \
    portfolio-backup-prune.service portfolio-backup-prune-readiness.service \
    portfolio-backup-prune.timer; do
    printf '\n# lock-current-release-contract\n' \
      >>"$next_release/ops/deploy/systemd/$unit"
    chmod 0600 "$next_release/ops/deploy/systemd/$unit"
  done
  printf 'BACKUP_AGE_RECIPIENT=age1fixture\nBACKUP_PRUNE_SENTINEL=configured\nBACKUP_PRUNE_READINESS_FILE=%s\n' \
    "$readiness" >"$backup_env"
  chmod 0600 "$backup_env"
  chmod 0755 "$systemd_root"
  : >"$log"

  [[ ! -e /opt/portfolio/cos-prune-venv ]] ||
    fail 'backup unit contract requires an isolated fixed prune runtime path'
  mkdir -p /opt/portfolio/cos-prune-venv/bin
  chmod 0700 /opt/portfolio/cos-prune-venv /opt/portfolio/cos-prune-venv/bin
  cat >"$runtime_python" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
[[ "$#" -eq 3 && "$1" == -B && "$2" == */ops/deploy/backup/cos-prune-guard.py &&
   "$3" == runtime-check ]]
printf 'prune-runtime:check\n' >>"${PORTFOLIO_TEST_SYSTEMCTL_LOG:?}"
[[ ! -e "${PORTFOLIO_TEST_SYSTEMCTL_PRUNE_ENABLED:?}" ]]
if [[ "${PORTFOLIO_TEST_RUNTIME_FAIL:-false}" == true ]]; then
  exit 72
fi
if [[ "${PORTFOLIO_TEST_RUNTIME_STDERR:-false}" == true ]]; then
  printf 'unexpected runtime stderr\n' >&2
fi
: >"${PORTFOLIO_TEST_RUNTIME_SAFE:?}"
printf '%s\n' "${PORTFOLIO_TEST_RUNTIME_OUTPUT:-SAFE}"
STUB
  chmod 0755 "$runtime_python"

  cat >"$systemctl_stub" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"${PORTFOLIO_TEST_SYSTEMCTL_LOG:?}"
case "${1:-}" in
  daemon-reload) : >"${PORTFOLIO_TEST_SYSTEMCTL_RELOADED:?}" ;;
  disable)
    [[ "$*" == 'disable --now portfolio-backup-prune.timer' ]]
    rm -f -- "${PORTFOLIO_TEST_SYSTEMCTL_PRUNE_ENABLED:?}"
    ;;
  enable)
    [[ -f "${PORTFOLIO_TEST_SYSTEMCTL_RELOADED:?}" ]]
    case "$*" in
      'enable --now portfolio-backup.timer')
        : >"${PORTFOLIO_TEST_SYSTEMCTL_BACKUP_ENABLED:?}"
        ;;
      'enable --now portfolio-backup-prune.timer')
        [[ -f "${PORTFOLIO_TEST_RUNTIME_SAFE:?}" &&
           -f "${PORTFOLIO_TEST_PRUNE_READINESS_FILE:?}" ]]
        : >"${PORTFOLIO_TEST_SYSTEMCTL_PRUNE_ENABLED:?}"
        [[ "${PORTFOLIO_TEST_PRUNE_ENABLE_FAIL_AFTER_STATE:-false}" != true ]] || exit 74
        ;;
      *) exit 66 ;;
    esac
    ;;
  start)
    case "${2:-}" in
      portfolio-backup.service)
        : >"${PORTFOLIO_TEST_SYSTEMCTL_BACKUP_RAN:?}"
        ;;
      portfolio-backup-prune-readiness.service)
        [[ -f "${PORTFOLIO_TEST_RUNTIME_SAFE:?}" ]]
        [[ "${PORTFOLIO_TEST_READINESS_FAIL:-false}" != true ]] || exit 73
        (
          set -a
          # shellcheck disable=SC1090
          source "${PORTFOLIO_TEST_BACKUP_ENV:?}"
          set +a
          /usr/bin/bash "${PORTFOLIO_BACKUP_DISPATCH_PATH:?}" prune-dry-run
        )
        : >"${PORTFOLIO_TEST_SYSTEMCTL_READINESS_RAN:?}"
        ;;
      *) exit 67 ;;
    esac
    ;;
  show)
    unit="${2:?}"
    if [[ "$*" == *'--property=Result'* ]]; then
      case "$unit" in
        portfolio-backup.service)
          [[ -f "${PORTFOLIO_TEST_SYSTEMCTL_BACKUP_RAN:?}" ]]
          ;;
        portfolio-backup-prune-readiness.service)
          [[ -f "${PORTFOLIO_TEST_SYSTEMCTL_READINESS_RAN:?}" ]]
          ;;
        *) exit 68 ;;
      esac
      printf 'ActiveState=inactive\nSubState=dead\nResult=success\nExecMainStatus=0\n'
      exit 0
    fi
    if [[ "$unit" == portfolio-backup-prune.timer &&
          "$*" != *'--property=FragmentPath'* ]]; then
      if [[ ! -f "${PORTFOLIO_TEST_SYSTEMD_ROOT:?}/$unit" ]]; then
        printf 'LoadState=not-found\nActiveState=inactive\nSubState=dead\nUnitFileState=\n'
      elif [[ -f "${PORTFOLIO_TEST_SYSTEMCTL_PRUNE_ENABLED:?}" ]]; then
        printf 'LoadState=loaded\nActiveState=active\nSubState=waiting\nUnitFileState=enabled\n'
      else
        printf 'LoadState=loaded\nActiveState=inactive\nSubState=dead\nUnitFileState=disabled\n'
      fi
      exit 0
    fi
    case "$unit" in
      portfolio-backup.timer)
        [[ -f "${PORTFOLIO_TEST_SYSTEMCTL_BACKUP_ENABLED:?}" ]]
        active=active; sub=waiting; enabled=enabled ;;
      portfolio-backup-prune.timer)
        [[ -f "${PORTFOLIO_TEST_SYSTEMCTL_PRUNE_ENABLED:?}" ]]
        active=active; sub=waiting; enabled=enabled ;;
      portfolio-backup.service|portfolio-backup-prune.service|portfolio-backup-prune-readiness.service)
        active=inactive; sub=dead; enabled=static ;;
      *) exit 64 ;;
    esac
    printf 'LoadState=loaded\nFragmentPath=%s/%s\nDropInPaths=%s\nNeedDaemonReload=%s\n' \
      "${PORTFOLIO_TEST_SYSTEMD_ROOT:?}" "$unit" \
      "${PORTFOLIO_TEST_SYSTEMD_DROP_INS:-}" "${PORTFOLIO_TEST_SYSTEMD_NEED_RELOAD:-no}"
    printf 'ActiveState=%s\nSubState=%s\nUnitFileState=%s\n' "$active" "$sub" "$enabled"
    ;;
  *) exit 65 ;;
esac
STUB
  chmod 0755 "$systemctl_stub"

  local -a unit_environment=(
    "PORTFOLIO_ROOT=$root"
    "PORTFOLIO_SYSTEMD_ROOT=$systemd_root"
    "PORTFOLIO_SYSTEMCTL_COMMAND=$systemctl_stub"
    "PORTFOLIO_BACKUP_DISPATCH_PATH=$stable_dispatcher"
    "PORTFOLIO_BACKUP_ENV_FILE=$backup_env"
    "PORTFOLIO_DEPLOY_LOCK_FILE=$lock_root/deploy.lock"
    "PORTFOLIO_TEST_SYSTEMCTL_LOG=$log"
    "PORTFOLIO_TEST_SYSTEMCTL_RELOADED=$fixture/reloaded"
    "PORTFOLIO_TEST_SYSTEMCTL_BACKUP_ENABLED=$fixture/backup-enabled"
    "PORTFOLIO_TEST_SYSTEMCTL_PRUNE_ENABLED=$fixture/prune-enabled"
    "PORTFOLIO_TEST_SYSTEMCTL_BACKUP_RAN=$fixture/backup-ran"
    "PORTFOLIO_TEST_SYSTEMCTL_READINESS_RAN=$fixture/readiness-ran"
    "PORTFOLIO_TEST_RUNTIME_SAFE=$fixture/runtime-safe"
    "PORTFOLIO_TEST_PRUNE_READINESS_FILE=$readiness"
    "PORTFOLIO_TEST_BACKUP_ENV=$backup_env"
    "PORTFOLIO_TEST_SYSTEMD_ROOT=$systemd_root"
  )
  : >"$lock_root/deploy.lock"
  chmod 0600 "$lock_root/deploy.lock"
  bash -c '
    set -euo pipefail
    exec 8<>"$1"
    flock -x 8
    : >"$2"
    while [[ ! -e "$3" ]]; do sleep 0.01; done
  ' bash "$lock_root/deploy.lock" "$fixture/lock-held" "$fixture/release-lock" &
  local lock_holder_pid=$!
  while [[ ! -e "$fixture/lock-held" ]]; do sleep 0.01; done

  env "${unit_environment[@]}" bash "$BACKUP_UNIT_INSTALLER" --run-initial-backup \
    >"$fixture/install.out" 2>"$fixture/install.err" &
  local installer_pid=$!
  local observed_wait=false attempt
  for ((attempt = 0; attempt < 500; attempt++)); do
    if [[ -e "/proc/$installer_pid/fd/9" ]]; then
      observed_wait=true
      break
    fi
    kill -0 "$installer_pid" 2>/dev/null || break
    sleep 0.01
  done
  [[ "$observed_wait" == true ]] || {
    : >"$fixture/release-lock"
    wait "$lock_holder_pid" || true
    wait "$installer_pid" || true
    sed -n '1,160p' "$fixture/install.err" >&2
    fail 'backup unit installer did not block on the shared deployment lock'
  }
  printf '%s\n' "$next_release_id" >"$root/current-release.next"
  chmod 0640 "$root/current-release.next"
  mv -T -- "$root/current-release.next" "$root/current-release"
  cp -- "$next_release/ops/deploy/backup/backup-dispatch.sh" "$stable_dispatcher.next"
  chmod 0755 "$stable_dispatcher.next"
  mv -T -- "$stable_dispatcher.next" "$stable_dispatcher"
  : >"$fixture/release-lock"
  wait "$lock_holder_pid" || fail 'deployment lock holder failed during current-release switch'
  if ! wait "$installer_pid"; then
    sed -n '1,160p' "$fixture/install.err" >&2
    fail 'trusted first-release backup unit installation failed after lock wait'
  fi
  release_id="$next_release_id"
  release="$next_release"
  for unit in portfolio-backup.service portfolio-backup.timer \
    portfolio-backup-prune.service portfolio-backup-prune-readiness.service \
    portfolio-backup-prune.timer; do
    cmp -s "$release/ops/deploy/systemd/$unit" "$systemd_root/$unit" ||
      fail "installed systemd unit differs from current release: $unit"
    [[ "$(stat -Lc '%u:%g:%a' "$systemd_root/$unit")" == 0:0:644 ]] ||
      fail "installed systemd unit owner or mode is unsafe: $unit"
  done
  grep -Fx 'enable --now portfolio-backup.timer' "$log" >/dev/null ||
    fail 'backup timer was not enabled after first release'
  grep -Fx 'enable --now portfolio-backup-prune.timer' "$log" >/dev/null ||
    fail 'backup prune timer was not enabled after its readiness gate'
  grep -Fx 'start portfolio-backup-prune-readiness.service' "$log" >/dev/null ||
    fail 'first release did not execute the dedicated prune readiness service'
  grep -Fx 'prune-runtime:check' "$log" >/dev/null ||
    fail 'first release did not verify the fixed COS prune runtime'
  grep -Fx 'prune-readiness:dry-run' "$log" >/dev/null ||
    fail 'first release did not execute a real prune dry-run'
  [[ -f "$readiness" && "$(stat -Lc '%u:%g:%a:%h' "$readiness")" == 0:0:600:1 ]] ||
    fail 'first release did not retain safe prune readiness evidence'
  grep -Fx 'start portfolio-backup.service' "$log" >/dev/null ||
    fail 'first release did not produce an immediate verified backup'
  cp -- "$readiness" "$fixture/valid-readiness.json"

  local disable_line runtime_line readiness_line enable_line
  disable_line="$(grep -n -m1 -F 'show portfolio-backup-prune.timer --property=LoadState' "$log" | cut -d: -f1)"
  runtime_line="$(grep -n -m1 -F 'prune-runtime:check' "$log" | cut -d: -f1)"
  readiness_line="$(grep -n -m1 -F 'start portfolio-backup-prune-readiness.service' "$log" | cut -d: -f1)"
  enable_line="$(grep -n -m1 -F 'enable --now portfolio-backup-prune.timer' "$log" | cut -d: -f1)"
  [[ "$disable_line" =~ ^[0-9]+$ && "$runtime_line" =~ ^[0-9]+$ &&
     "$readiness_line" =~ ^[0-9]+$ && "$enable_line" =~ ^[0-9]+$ &&
     "$disable_line" -lt "$runtime_line" && "$runtime_line" -lt "$readiness_line" &&
     "$readiness_line" -lt "$enable_line" ]] ||
    fail 'prune timer readiness operations ran out of fail-closed order'

  : >"$log"
  printf 'malformed-current-release\n' >"$root/current-release"
  chmod 0640 "$root/current-release"
  if env "${unit_environment[@]}" bash "$BACKUP_UNIT_INSTALLER" \
      >"$fixture/invalid-current.out" 2>"$fixture/invalid-current.err"; then
    fail 'backup unit installer accepted an invalid current-release marker'
  fi
  grep -F 'current-release marker is malformed' "$fixture/invalid-current.err" >/dev/null ||
    fail 'invalid current-release marker failed for an unexpected reason'
  [[ ! -e "$fixture/prune-enabled" ]] ||
    fail 'invalid current-release left the old prune timer enabled'
  grep -Fx 'disable --now portfolio-backup-prune.timer' "$log" >/dev/null ||
    fail 'current-release validation ran before the old prune timer was stopped'
  if grep -Fx 'enable --now portfolio-backup-prune.timer' "$log" >/dev/null; then
    fail 'invalid current-release re-enabled the prune timer'
  fi
  printf '%s\n' "$release_id" >"$root/current-release"
  chmod 0640 "$root/current-release"

  env "${unit_environment[@]}" bash "$BACKUP_UNIT_INSTALLER" \
    >"$fixture/rearm-after-current.out" 2>"$fixture/rearm-after-current.err" || {
      sed -n '1,160p' "$fixture/rearm-after-current.err" >&2
      fail 'backup unit installer could not re-arm after current-release repair'
    }
  cp -- "$readiness" "$fixture/valid-readiness.json"

  : >"$log"
  if env "${unit_environment[@]}" PORTFOLIO_TEST_RUNTIME_FAIL=true \
      bash "$BACKUP_UNIT_INSTALLER" >"$fixture/runtime-fail.out" \
      2>"$fixture/runtime-fail.err"; then
    fail 'backup unit installer enabled pruning after a failed runtime-check'
  fi
  grep -F 'COS prune runtime-check failed' "$fixture/runtime-fail.err" >/dev/null ||
    fail 'failed COS prune runtime-check stopped for an unexpected reason'
  [[ ! -e "$fixture/prune-enabled" && ! -e "$readiness" ]] ||
    fail 'runtime-check failure retained prune enablement or stale readiness'
  if grep -Fx 'enable --now portfolio-backup-prune.timer' "$log" >/dev/null; then
    fail 'runtime-check failure reached prune timer enablement'
  fi

  : >"$log"
  if env "${unit_environment[@]}" PORTFOLIO_TEST_READINESS_FAIL=true \
      bash "$BACKUP_UNIT_INSTALLER" >"$fixture/readiness-fail.out" \
      2>"$fixture/readiness-fail.err"; then
    fail 'backup unit installer enabled pruning after readiness service failure'
  fi
  [[ ! -e "$fixture/prune-enabled" && ! -e "$readiness" ]] ||
    fail 'readiness service failure retained prune enablement or evidence'
  if grep -Fx 'enable --now portfolio-backup-prune.timer' "$log" >/dev/null; then
    fail 'readiness service failure reached prune timer enablement'
  fi

  cp -- "$fixture/valid-readiness.json" "$readiness"
  chmod 0600 "$readiness"
  : >"$log"
  if env "${unit_environment[@]}" PORTFOLIO_TEST_READINESS_NO_WRITE=true \
      bash "$BACKUP_UNIT_INSTALLER" >"$fixture/stale-readiness.out" \
      2>"$fixture/stale-readiness.err"; then
    fail 'backup unit installer accepted stale readiness without a fresh marker write'
  fi
  grep -F 'initial prune readiness evidence was not freshly generated' \
    "$fixture/stale-readiness.err" >/dev/null ||
    fail 'stale readiness no-write service failed for an unexpected reason'
  [[ ! -e "$readiness" && ! -e "$fixture/prune-enabled" ]] ||
    fail 'stale readiness was reused or the prune timer was enabled'
  grep -Fx 'start portfolio-backup-prune-readiness.service' "$log" >/dev/null ||
    fail 'stale readiness negative did not execute its successful no-write service'

  : >"$log"
  if env "${unit_environment[@]}" PORTFOLIO_TEST_READINESS_MALFORMED=true \
      bash "$BACKUP_UNIT_INSTALLER" >"$fixture/malformed-readiness.out" \
      2>"$fixture/malformed-readiness.err"; then
    fail 'backup unit installer accepted malformed readiness evidence'
  fi
  grep -F 'initial prune readiness evidence is invalid or stale' \
    "$fixture/malformed-readiness.err" >/dev/null ||
    fail 'malformed readiness evidence failed for an unexpected reason'
  [[ ! -e "$fixture/prune-enabled" ]] ||
    fail 'malformed readiness evidence enabled the prune timer'

  : >"$log"
  if env "${unit_environment[@]}" PORTFOLIO_TEST_PRUNE_ENABLE_FAIL_AFTER_STATE=true \
      bash "$BACKUP_UNIT_INSTALLER" >"$fixture/partial-enable.out" \
      2>"$fixture/partial-enable.err"; then
    fail 'backup unit installer accepted a partial prune timer enable failure'
  fi
  [[ ! -e "$fixture/prune-enabled" ]] ||
    fail 'EXIT cleanup left a partially enabled prune timer active'
  enable_line="$(grep -n -m1 -F 'enable --now portfolio-backup-prune.timer' "$log" | cut -d: -f1)"
  disable_line="$(grep -n -F 'disable --now portfolio-backup-prune.timer' "$log" | tail -n1 | cut -d: -f1)"
  [[ "$enable_line" =~ ^[0-9]+$ && "$disable_line" =~ ^[0-9]+$ &&
     "$enable_line" -lt "$disable_line" ]] ||
    fail 'partial prune enable failure did not trigger a later fail-closed disable'

  env "${unit_environment[@]}" bash "$BACKUP_UNIT_INSTALLER" \
    >"$fixture/rearm-before-dropin.out" 2>"$fixture/rearm-before-dropin.err" || {
      sed -n '1,160p' "$fixture/rearm-before-dropin.err" >&2
      fail 'backup unit installer could not re-arm after readiness gate negatives'
    }

  if env "${unit_environment[@]}" \
      PORTFOLIO_TEST_SYSTEMD_DROP_INS=/run/systemd/system/portfolio-backup.service.d/override.conf \
      bash "$BACKUP_UNIT_INSTALLER" >"$fixture/dropin.out" 2>"$fixture/dropin.err"; then
    fail 'backup unit installer accepted an effective drop-in override'
  fi
  grep -F 'effective backup service is overridden, stale, or invalid' \
    "$fixture/dropin.err" >/dev/null ||
    fail 'backup unit drop-in override failed for an unexpected reason'
  [[ ! -e "$fixture/prune-enabled" ]] ||
    fail 'effective unit validation failure left the prune timer enabled'

  env "${unit_environment[@]}" bash "$BACKUP_UNIT_INSTALLER" \
    >"$fixture/rearm-before-drift.out" 2>"$fixture/rearm-before-drift.err" || {
      sed -n '1,160p' "$fixture/rearm-before-drift.err" >&2
      fail 'backup unit installer could not re-arm before drift rejection'
    }

  : >"$log"
  printf '# drift\n' >>"$systemd_root/portfolio-backup.service"
  if env "${unit_environment[@]}" bash "$BACKUP_UNIT_INSTALLER" \
      >"$fixture/drift.out" 2>"$fixture/drift.err"; then
    fail 'backup unit installer accepted a drifted installed fragment'
  fi
  grep -F 'installed unit differs from current release: portfolio-backup.service' \
    "$fixture/drift.err" >/dev/null ||
    fail 'drifted backup unit failed for an unexpected reason'
  [[ ! -e "$fixture/prune-enabled" ]] ||
    fail 'installed unit drift left the old prune timer enabled'
  grep -Fx 'disable --now portfolio-backup-prune.timer' "$log" >/dev/null ||
    fail 'installed unit drift was checked before the old prune timer was stopped'
  rm -rf -- /opt/portfolio/cos-prune-venv
}

DEPLOY_CASE_ROOT=''
DEPLOY_CASE_ETC=''
DEPLOY_CASE_BIN=''
DEPLOY_CASE_LOG=''
DEPLOY_CASE_PREFLIGHT=''
DEPLOY_CASE_QUERY=''
DEPLOY_CASE_BACKUP=''
DEPLOY_CASE_BACKUP_ENV=''
DEPLOY_CASE_BACKUP_UNIT=''
DEPLOY_CASE_SYSTEMCTL=''
DEPLOY_CASE_INITIAL_MODE=false
DEPLOY_CASE_PUBLIC_MODE=false
DEPLOY_CURRENT='aaaaaaaaaaaa-111111111111'
DEPLOY_TARGET='bbbbbbbbbbbb-222222222222'

write_deploy_release() {
  local root="$1" release_id="$2" api_digit="$3" day="$4"
  local release="$root/releases/$release_id"
  mkdir -p \
    "$release/admin/assets" "$release/public-assets/.vite" \
    "$release/public-assets/assets" "$release/ops/deploy/scripts" \
    "$release/ops/deploy/backup"
  printf '<!doctype html>%s\n' "$release_id" >"$release/admin/index.html"
  printf 'target-asset-%s\n' "$release_id" >"$release/public-assets/assets/app.target.js"
  printf '{"entry":{"file":"assets/app.target.js"}}\n' \
    >"$release/public-assets/.vite/manifest.json"
  printf 'services: {}\n' >"$release/ops/deploy/docker-compose.prod.yml"
  jq -Scn \
    --arg release "$release_id" \
    --arg api "sha256:$(printf '%64s' '' | tr ' ' "$api_digit")" \
    --arg build "2026-07-${day}T00:00:00Z" \
    '{releaseId:$release,buildTimeUtc:$build,
      apiImageTag:("portfolio-api:"+$release),apiImageId:$api,
      postgresImageRef:("postgres:17-bookworm@sha256:"+("4"*64)),
      postgresImageTag:("portfolio-postgres-17:"+$release),
      postgresImageId:("sha256:"+("4"*64))}' >"$release/release.json"

  cat >"$release/ops/deploy/scripts/preflight.sh" <<'STUB'
#!/usr/bin/env bash
exit 88
STUB
  cat >"$release/ops/deploy/scripts/smoke.sh" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
mode="${1:-}"
release_root="$(cd "$(dirname "$0")/../../.." && pwd -P)"
release_id="$(jq -r .releaseId "$release_root/release.json")"
printf 'smoke:%s\n' "$*" >>"$PORTFOLIO_TEST_COMMAND_LOG"
printf 'smoke-release:%s:%s\n' "$release_id" "$mode" >>"$PORTFOLIO_TEST_COMMAND_LOG"
if [[ "$mode" == nginx-local || "$mode" == public ]]; then
  expected_sha="$(sha256sum "$release_root/public-assets/assets/app.target.js" | awk '{print $1}')"
  [[ "${PORTFOLIO_SMOKE_ASSET_PATH:-}" == assets/app.target.js &&
     "${PORTFOLIO_SMOKE_ASSET_SHA256:-}" == "$expected_sha" ]] || exit 93
fi
if [[ "$mode" == nginx-local && -f "$PORTFOLIO_ROOT/current-release" &&
      "$(tr -d '\r\n' <"$PORTFOLIO_ROOT/current-release")" == \
      "$PORTFOLIO_TEST_TARGET_RELEASE" ]]; then
  printf 'nginx smoke ran after marker switch\n' >&2
  exit 91
fi
if [[ "$release_id" == "$PORTFOLIO_TEST_TARGET_RELEASE" &&
      "${PORTFOLIO_TEST_SMOKE_FAIL_MODE:-}" == "$mode" ]]; then
  printf 'injected target smoke failure: %s\n' "$mode" >&2
  exit 94
fi
if [[ "$release_id" != "$PORTFOLIO_TEST_TARGET_RELEASE" &&
      "${PORTFOLIO_TEST_ROLLBACK_SMOKE_FAIL_MODE:-}" == "$mode" ]]; then
  exit 95
fi
STUB
  cat >"$release/ops/deploy/scripts/prune-releases.sh" <<'STUB'
#!/usr/bin/env bash
printf 'prune:%s\n' "$*" >>"$PORTFOLIO_TEST_COMMAND_LOG"
if [[ "${PORTFOLIO_TEST_PRUNE_EXIT:-0}" != 0 ]]; then
  printf 'injected prune failure\n' >&2
fi
exit "${PORTFOLIO_TEST_PRUNE_EXIT:-0}"
STUB
  cp -- "$REPOSITORY_ROOT/deploy/backup/backup-dispatch.sh" \
    "$release/ops/deploy/backup/backup-dispatch.sh"
  cat >"$release/ops/deploy/backup/backup-set.sh" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
[[ "$*" == --verify-upload ]]
[[ "${BACKUP_SENTINEL:-}" == configured ]]
for forbidden in \
  BACKUP_AGE_IDENTITY AGE_SECRET_KEY AGE_SECRET_KEY_FILE \
  COS_SECRET_ID COS_SECRET_KEY SMTP_PASSWORD; do
  [[ ! -v "$forbidden" ]] || {
    printf 'forbidden deployment secret reached backup service: %s\n' "$forbidden" >&2
    exit 97
  }
done
printf 'backup:clean:%s\n' "$*" >>"$PORTFOLIO_TEST_COMMAND_LOG"
STUB
  chmod 0755 \
    "$release/ops/deploy/scripts/preflight.sh" \
    "$release/ops/deploy/scripts/smoke.sh" \
    "$release/ops/deploy/scripts/prune-releases.sh" \
    "$release/ops/deploy/backup/backup-dispatch.sh" \
    "$release/ops/deploy/backup/backup-set.sh"
}

prepare_deploy_case() {
  local label="$1" base
  base="$WORK_DIRECTORY/deploy-case-$label"
  rm -rf -- "$base"
  DEPLOY_CASE_ROOT="$base/root"
  DEPLOY_CASE_ETC="$base/etc"
  DEPLOY_CASE_BIN="$base/bin"
  DEPLOY_CASE_LOG="$base/commands.log"
  DEPLOY_CASE_PREFLIGHT="$base/preflight-stub.sh"
  DEPLOY_CASE_QUERY="$base/cleanup-query-stub.sh"
  DEPLOY_CASE_BACKUP="$base/libexec/portfolio/backup-dispatch.sh"
  DEPLOY_CASE_BACKUP_ENV="$base/etc/backup.env"
  DEPLOY_CASE_BACKUP_UNIT="$base/systemd/portfolio-backup.service"
  DEPLOY_CASE_SYSTEMCTL="$base/bin/systemctl"
  DEPLOY_CASE_INITIAL_MODE=false
  DEPLOY_CASE_PUBLIC_MODE=false
  mkdir -p \
    "$DEPLOY_CASE_ROOT/releases" "$DEPLOY_CASE_ROOT/assets" \
    "$DEPLOY_CASE_ETC" "$DEPLOY_CASE_BIN" "$base/nginx/sbin" "$base/nginx/conf" \
    "$(dirname "$DEPLOY_CASE_BACKUP")" "$(dirname "$DEPLOY_CASE_BACKUP_UNIT")" \
    "$base/postgres-volume"
  chmod 0750 "$DEPLOY_CASE_ROOT"
  : >"$DEPLOY_CASE_LOG"
  write_deploy_release "$DEPLOY_CASE_ROOT" "$DEPLOY_CURRENT" 1 16
  write_deploy_release "$DEPLOY_CASE_ROOT" "$DEPLOY_TARGET" 2 17
  printf '%s\n' "$DEPLOY_CURRENT" >"$DEPLOY_CASE_ROOT/current-release"
  printf 'cccccccccccc-333333333333\n' >"$DEPLOY_CASE_ROOT/previous-release"
  ln -s -- "$DEPLOY_CASE_ROOT/releases/$DEPLOY_CURRENT/admin" \
    "$DEPLOY_CASE_ROOT/current-admin"
  ln -s -- "$DEPLOY_CASE_ROOT/releases/$DEPLOY_CURRENT/ops" \
    "$DEPLOY_CASE_ROOT/current-ops"
  printf 'POSTGRES_IMAGE=portfolio-postgres-17:%s\nPORTFOLIO_IMAGE=portfolio-api:%s\nPORTFOLIO_RELEASE_ID=%s\n' \
    "$DEPLOY_CURRENT" "$DEPLOY_CURRENT" "$DEPLOY_CURRENT" \
    >"$DEPLOY_CASE_ETC/release.env"
  printf 'COS_SECRET_ID=runtime-id\nCOS_SECRET_KEY=runtime-key\nSMTP_PASSWORD=runtime-password\n' \
    >"$DEPLOY_CASE_ETC/portfolio.env"
  printf 'POSTGRES_DB=portfolio\nPOSTGRES_USER=portfolio_owner\n' \
    >"$DEPLOY_CASE_ETC/postgres.env"
  printf 'events {}\n' >"$base/nginx/conf/nginx.conf"
  cat >"$base/nginx/sbin/nginx" <<'STUB'
#!/usr/bin/env bash
printf 'nginx:%s\n' "$*" >>"$PORTFOLIO_TEST_COMMAND_LOG"
count=0
[[ ! -f "$PORTFOLIO_TEST_NGINX_COUNT" ]] || count="$(<"$PORTFOLIO_TEST_NGINX_COUNT")"
count=$((count + 1))
printf '%s\n' "$count" >"$PORTFOLIO_TEST_NGINX_COUNT"
if [[ -n "${PORTFOLIO_TEST_NGINX_FAIL_CALL:-}" &&
      "$count" -eq "$PORTFOLIO_TEST_NGINX_FAIL_CALL" ]]; then
  printf 'injected nginx failure call %s\n' "$count" >&2
  exit 97
fi
if [[ "${PORTFOLIO_TEST_NGINX_EXIT:-0}" != 0 ]]; then
  printf 'injected persistent nginx failure\n' >&2
fi
exit "${PORTFOLIO_TEST_NGINX_EXIT:-0}"
STUB
  chmod 0755 "$base/nginx/sbin/nginx"
  printf 'NGINX_BIN=%s\nNGINX_PREFIX=%s/\nNGINX_CONF=%s\nNGINX_LOCAL_PORT=18443\n' \
    "$base/nginx/sbin/nginx" "$base/nginx" "$base/nginx/conf/nginx.conf" \
    >"$DEPLOY_CASE_ETC/nginx.env"
  chmod 0640 "$DEPLOY_CASE_ETC/release.env" "$DEPLOY_CASE_ETC/nginx.env"
  chmod 0600 "$DEPLOY_CASE_ETC/portfolio.env" "$DEPLOY_CASE_ETC/postgres.env"

  cp -- "$REPOSITORY_ROOT/deploy/backup/backup-dispatch.sh" "$DEPLOY_CASE_BACKUP"
  chmod 0755 "$DEPLOY_CASE_BACKUP"
  printf 'BACKUP_SENTINEL=configured\nPORTFOLIO_ROOT=%s\nPORTFOLIO_TEST_COMMAND_LOG=%s\n' \
    "$DEPLOY_CASE_ROOT" "$DEPLOY_CASE_LOG" >"$DEPLOY_CASE_BACKUP_ENV"
  chmod 0600 "$DEPLOY_CASE_BACKUP_ENV"
  cat >"$DEPLOY_CASE_BACKUP_UNIT" <<EOF
[Service]
EnvironmentFile=$DEPLOY_CASE_BACKUP_ENV
EnvironmentFile=$DEPLOY_CASE_ETC/release.env
UnsetEnvironment=BACKUP_AGE_IDENTITY AGE_SECRET_KEY AGE_SECRET_KEY_FILE COS_SECRET_ID COS_SECRET_KEY SMTP_PASSWORD
ExecStart=/usr/bin/bash $DEPLOY_CASE_BACKUP backup
EOF
  chmod 0644 "$DEPLOY_CASE_BACKUP_UNIT"

  cat >"$DEPLOY_CASE_SYSTEMCTL" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
printf 'systemctl:%s\n' "$*" >>"${PORTFOLIO_TEST_COMMAND_LOG:?}"
state="${PORTFOLIO_TEST_SYSTEMCTL_STATE:?}"
case "${1:-}" in
  daemon-reload)
    [[ $# -eq 1 ]]
    : >"${PORTFOLIO_TEST_SYSTEMCTL_RELOADED:?}"
    ;;
  start)
    [[ "${2:-}" == portfolio-backup.service ]]
    [[ -f "${PORTFOLIO_TEST_SYSTEMCTL_RELOADED:?}" ]]
    if [[ "${PORTFOLIO_TEST_BACKUP_FAIL:-false}" == true ]]; then
      printf 'failed\n' >"$state"
      exit 1
    fi
    set -a
    # These are the two root-owned EnvironmentFile inputs modeled by the fixture.
    # shellcheck disable=SC1090
    source "${PORTFOLIO_TEST_BACKUP_ENV:?}"
    # shellcheck disable=SC1090
    source "${PORTFOLIO_TEST_RELEASE_ENV:?}"
    set +a
    if env -i \
        PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
        BACKUP_SENTINEL="$BACKUP_SENTINEL" PORTFOLIO_ROOT="$PORTFOLIO_ROOT" \
        PORTFOLIO_TEST_COMMAND_LOG="$PORTFOLIO_TEST_COMMAND_LOG" \
        POSTGRES_IMAGE="$POSTGRES_IMAGE" PORTFOLIO_IMAGE="$PORTFOLIO_IMAGE" \
        PORTFOLIO_RELEASE_ID="$PORTFOLIO_RELEASE_ID" \
        /usr/bin/bash "${PORTFOLIO_TEST_STABLE_DISPATCHER:?}" backup; then
      printf 'success\n' >"$state"
    else
      printf 'failed\n' >"$state"
      exit 1
    fi
    ;;
  show)
    [[ "${2:-}" == portfolio-backup.service ]]
    [[ -f "${PORTFOLIO_TEST_SYSTEMCTL_RELOADED:?}" ]]
    printf 'LoadState=loaded\nFragmentPath=%s\nDropInPaths=%s\nNeedDaemonReload=%s\n' \
      "${PORTFOLIO_TEST_SYSTEMD_FRAGMENT_PATH:-${PORTFOLIO_TEST_BACKUP_UNIT:?}}" \
      "${PORTFOLIO_TEST_SYSTEMD_DROP_INS:-}" \
      "${PORTFOLIO_TEST_SYSTEMD_NEED_RELOAD:-no}"
    if [[ "$*" == *'--property=Result'* ]]; then
      if [[ -f "$state" && "$(tr -d '\r\n' <"$state")" == success ]]; then
        printf 'ActiveState=inactive\nSubState=dead\nResult=success\nExecMainStatus=0\n'
      else
        printf 'ActiveState=failed\nSubState=failed\nResult=exit-code\nExecMainStatus=1\n'
      fi
    else
      printf 'ActiveState=inactive\nSubState=dead\n'
    fi
    ;;
  *) exit 64 ;;
esac
STUB
  chmod 0755 "$DEPLOY_CASE_SYSTEMCTL"

  cat >"$DEPLOY_CASE_PREFLIGHT" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
printf 'preflight:%s\n' "$*" >>"$PORTFOLIO_TEST_COMMAND_LOG"
[[ "${PORTFOLIO_TEST_PREFLIGHT_FAIL:-false}" != true ]]
providers="$(printf '%s' "${PORTFOLIO_TEST_PROVIDERS:-LOCAL}" | tr ',' '\n' |
  jq -Rsc 'split("\n") | map(select(length > 0)) | sort | unique')"
if [[ "${PORTFOLIO_TEST_COS_EVIDENCE:-present}" == present &&
      "$providers" == *TENCENT_COS* ]]; then
  cos='[{"bucket":"portfolio-contract-1250000000","region":"ap-guangzhou","verified":true}]'
else
  cos='[]'
fi
jq -Scn \
  --arg targetReleaseId "$PORTFOLIO_PREFLIGHT_TARGET_RELEASE_ID" \
  --argjson requiredProviders "$providers" \
  --argjson cosLocations "$cos" \
  '{schemaVersion:1,targetReleaseId:$targetReleaseId,
    requiredProviders:$requiredProviders,cosLocations:$cosLocations}' \
  >"$PORTFOLIO_PREFLIGHT_EVIDENCE_OUTPUT"
STUB
  chmod 0755 "$DEPLOY_CASE_PREFLIGHT"

  cat >"$DEPLOY_CASE_QUERY" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
printf 'query:%s|%s|%s\n' "$1" "$2" "$3" >>"$PORTFOLIO_TEST_COMMAND_LOG"
case "${PORTFOLIO_TEST_CLEANUP_RESULT:-success}" in
  success) printf '%s|%s|%s|SUCCEEDED\n' "$1" "$2" "$3" ;;
  wrong-key) printf '%s|media-staging-cleanup:old-release:2026-07-17|%s|SUCCEEDED\n' "$1" "$3" ;;
  wrong-payload) printf '%s|%s|{"cutoffEpochSecond":0}|SUCCEEDED\n' "$1" "$2" ;;
  failed) printf '%s|%s|%s|FAILED\n' "$1" "$2" "$3" ;;
  timeout) ;;
  forbidden) exit 94 ;;
  *) exit 95 ;;
esac
STUB
  chmod 0755 "$DEPLOY_CASE_QUERY"

  cat >"$DEPLOY_CASE_BIN/docker" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
printf 'docker:%s\n' "$*" >>"$PORTFOLIO_TEST_COMMAND_LOG"
case "${1:-}:${2:-}" in
  save:*)
    reference="${2:?}"
    case "$reference" in
      portfolio-api:aaaaaaaaaaaa-111111111111)
        sha="$(printf '%64s' '' | tr ' ' "${PORTFOLIO_TEST_CURRENT_API_DIGIT:-1}")"
        ;;
      portfolio-api:bbbbbbbbbbbb-222222222222) sha="$(printf '2%.0s' {1..64})" ;;
      portfolio-api:dddddddddddd-444444444444) sha="$(printf '3%.0s' {1..64})" ;;
      portfolio-postgres-17:*) sha="$(printf '4%.0s' {1..64})" ;;
      *) exit 1 ;;
    esac
    work="$(mktemp -d)"
    trap 'rm -rf -- "$work"' EXIT
    printf 'layer\n' >"$work/layer.tar"
    jq -cn --arg config "$sha.json" --arg reference "$reference" \
      '[{Config:$config,RepoTags:[$reference],Layers:["layer.tar"]}]' \
      >"$work/manifest.json"
    tar -C "$work" -cf - manifest.json layer.tar
    ;;
  compose:*)
    env_file=''
    previous=''
    for argument in "$@"; do
      if [[ "$previous" == --env-file ]]; then
        env_file="$argument"
        break
      fi
      previous="$argument"
    done
    if [[ "$*" == *' config --format json'* ]]; then
      printf '{"volumes":{"postgres-data":{"name":"portfolio_postgres-data"}}}\n'
    elif [[ "$*" == *' up '*portfolio-api* ]]; then
      awk -F= '$1 == "PORTFOLIO_RELEASE_ID" {print $2}' "$env_file" \
        >"$PORTFOLIO_TEST_ACTIVE_RELEASE_STATE"
    elif [[ "$*" == *' ps -q postgres'* ]]; then
      printf 'postgres-container\n'
    elif [[ "$*" == *' ps -q portfolio-api'* ]]; then
      printf 'portfolio-api-container\n'
    elif [[ "$*" == *' rm -f portfolio-api'* ]]; then
      rm -f -- "$PORTFOLIO_TEST_ACTIVE_RELEASE_STATE"
    fi
    ;;
  inspect:*)
    container="${*: -1}"
    if [[ "$container" == portfolio-api-container ]]; then
      active=''
      [[ ! -f "$PORTFOLIO_TEST_ACTIVE_RELEASE_STATE" ]] ||
        active="$(tr -d '\r\n' <"$PORTFOLIO_TEST_ACTIVE_RELEASE_STATE")"
      if [[ "$active" == "$PORTFOLIO_TEST_TARGET_RELEASE" &&
            "${PORTFOLIO_TEST_API_HEALTH_FAIL:-false}" == true ]]; then
        printf 'unhealthy\n'
      elif [[ -n "$active" && "$active" != "$PORTFOLIO_TEST_TARGET_RELEASE" &&
              "${PORTFOLIO_TEST_ROLLBACK_API_HEALTH_FAIL:-false}" == true ]]; then
        printf 'unhealthy\n'
      else
        printf 'healthy\n'
      fi
    else
      printf 'healthy\n'
    fi
    ;;
  volume:inspect)
    if [[ "${PORTFOLIO_TEST_VOLUME_EXISTS:-false}" == true ||
          -f "${PORTFOLIO_TEST_VOLUME_STATE:?}" ]]; then
      if [[ "$*" == *'--format'* ]]; then
        if [[ -f "$PORTFOLIO_TEST_VOLUME_STATE" ]]; then
          initial_release="$(tr -d '\r\n' <"$PORTFOLIO_TEST_VOLUME_STATE")"
          volume_role=postgres-initial
        else
          initial_release="${PORTFOLIO_TEST_EXISTING_VOLUME_RELEASE:-ffffffffffff-eeeeeeeeeeee}"
          volume_role="${PORTFOLIO_TEST_EXISTING_VOLUME_ROLE:-foreign}"
        fi
        printf 'portfolio_postgres-data|%s|%s|%s\n' \
          "$initial_release" "$volume_role" "$PORTFOLIO_TEST_VOLUME_MOUNTPOINT"
      else
        printf '{}\n'
      fi
    else
      exit 1
    fi
    ;;
  volume:create)
    initial_release=''
    previous=''
    for argument in "$@"; do
      if [[ "$previous" == --label && "$argument" == portfolio.initial-release=* ]]; then
        initial_release="${argument#portfolio.initial-release=}"
      fi
      previous="$argument"
    done
    [[ -n "$initial_release" ]] || exit 98
    printf '%s\n' "$initial_release" >"${PORTFOLIO_TEST_VOLUME_STATE:?}"
    printf 'portfolio_postgres-data\n'
    ;;
  *) exit 96 ;;
esac
STUB
  chmod 0755 "$DEPLOY_CASE_BIN/docker"

  cat >"$DEPLOY_CASE_BIN/mv" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
/usr/bin/mv "$@"
destination="${*: -1}"
if [[ -n "${PORTFOLIO_TEST_SIGNAL_AFTER_MV:-}" &&
      "$destination" == "$PORTFOLIO_TEST_SIGNAL_AFTER_MV" &&
      ! -e "$PORTFOLIO_TEST_SIGNAL_STATE" ]]; then
  : >"$PORTFOLIO_TEST_SIGNAL_STATE"
  kill -TERM "$PPID"
fi
STUB
  chmod 0755 "$DEPLOY_CASE_BIN/mv"
}

run_deploy_case() {
  local stdout="$1" stderr="$2"
  shift 2
  local -a deploy_arguments=("$DEPLOY_TARGET")
  [[ "$DEPLOY_CASE_INITIAL_MODE" == true ]] && deploy_arguments+=(--initial-empty-database)
  [[ "$DEPLOY_CASE_PUBLIC_MODE" == true ]] && deploy_arguments+=(--public-cutover)
  env \
    PATH="$DEPLOY_CASE_BIN:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
    PORTFOLIO_ROOT="$DEPLOY_CASE_ROOT" \
    PORTFOLIO_ETC_ROOT="$DEPLOY_CASE_ETC" \
    PORTFOLIO_DEPLOY_GROUP=root \
    PORTFOLIO_DEPLOY_LOCK_FILE="$DEPLOY_CASE_ROOT/deploy.lock" \
    PORTFOLIO_BACKUP_DISPATCH_PATH="$DEPLOY_CASE_BACKUP" \
    PORTFOLIO_BACKUP_ENV_FILE="$DEPLOY_CASE_BACKUP_ENV" \
    PORTFOLIO_BACKUP_UNIT_FILE="$DEPLOY_CASE_BACKUP_UNIT" \
    PORTFOLIO_SYSTEMCTL_COMMAND="$DEPLOY_CASE_SYSTEMCTL" \
    PORTFOLIO_BACKUP_SERVICE=portfolio-backup.service \
    PORTFOLIO_PREFLIGHT_COMMAND="$DEPLOY_CASE_PREFLIGHT" \
    PORTFOLIO_CLEANUP_QUERY_COMMAND="$DEPLOY_CASE_QUERY" \
    PORTFOLIO_TEST_COMMAND_LOG="$DEPLOY_CASE_LOG" \
    PORTFOLIO_TEST_BACKUP_ENV="$DEPLOY_CASE_BACKUP_ENV" \
    PORTFOLIO_TEST_RELEASE_ENV="$DEPLOY_CASE_ETC/release.env" \
    PORTFOLIO_TEST_STABLE_DISPATCHER="$DEPLOY_CASE_BACKUP" \
    PORTFOLIO_TEST_SYSTEMCTL_STATE="$(dirname "$DEPLOY_CASE_ROOT")/systemctl-state" \
    PORTFOLIO_TEST_SYSTEMCTL_RELOADED="$(dirname "$DEPLOY_CASE_ROOT")/systemctl-reloaded" \
    PORTFOLIO_TEST_BACKUP_UNIT="$DEPLOY_CASE_BACKUP_UNIT" \
    PORTFOLIO_TEST_TARGET_RELEASE="$DEPLOY_TARGET" \
    PORTFOLIO_TEST_VOLUME_STATE="$(dirname "$DEPLOY_CASE_ROOT")/volume-created" \
    PORTFOLIO_TEST_VOLUME_MOUNTPOINT="$(dirname "$DEPLOY_CASE_ROOT")/postgres-volume" \
    PORTFOLIO_TEST_ACTIVE_RELEASE_STATE="$(dirname "$DEPLOY_CASE_ROOT")/active-release" \
    PORTFOLIO_TEST_NGINX_COUNT="$(dirname "$DEPLOY_CASE_ROOT")/nginx-count" \
    PORTFOLIO_TEST_SIGNAL_STATE="$(dirname "$DEPLOY_CASE_ROOT")/signal-fired" \
    PORTFOLIO_DEPLOY_NOW_UTC=2026-07-17T19:00:00Z \
    PORTFOLIO_POSTGRES_READY_TIMEOUT_SECONDS=2 \
    PORTFOLIO_API_READY_TIMEOUT_SECONDS=1 \
    PORTFOLIO_HEALTH_POLL_SECONDS=0 \
    PORTFOLIO_CLEANUP_GATE_TIMEOUT_SECONDS=1 \
    PORTFOLIO_CLEANUP_POLL_INTERVAL_SECONDS=0 \
    "$@" bash "$DEPLOYER" "${deploy_arguments[@]}" >"$stdout" 2>"$stderr"
}

assert_deploy_preserved_current() {
  [[ "$(tr -d '\r\n' <"$DEPLOY_CASE_ROOT/current-release")" == "$DEPLOY_CURRENT" ]] ||
    fail 'failed deployment changed current-release'
  [[ "$(readlink -f "$DEPLOY_CASE_ROOT/current-admin")" == \
     "$DEPLOY_CASE_ROOT/releases/$DEPLOY_CURRENT/admin" ]] ||
    fail 'failed deployment changed the administrator pointer'
  [[ "$(readlink -f "$DEPLOY_CASE_ROOT/current-ops")" == \
     "$DEPLOY_CASE_ROOT/releases/$DEPLOY_CURRENT/ops" ]] ||
    fail 'failed deployment changed the stable operations pointer'
  grep -Fq "PORTFOLIO_RELEASE_ID=$DEPLOY_CURRENT" "$DEPLOY_CASE_ETC/release.env" ||
    fail 'failed deployment did not retain or restore release.env'
  cmp -s -- \
    "$DEPLOY_CASE_ROOT/releases/$DEPLOY_CURRENT/ops/deploy/backup/backup-dispatch.sh" \
    "$DEPLOY_CASE_BACKUP" ||
    fail 'failed deployment left the stable dispatcher different from current-release'
}

expect_deploy_failure() {
  local label="$1" expected="$2"
  shift 2
  prepare_deploy_case "$label"
  if run_deploy_case "$WORK_DIRECTORY/$label.out" "$WORK_DIRECTORY/$label.err" "$@"; then
    fail "$label deployment unexpectedly succeeded"
  fi
  grep -F "$expected" "$WORK_DIRECTORY/$label.err" >/dev/null || {
    sed -n '1,160p' "$WORK_DIRECTORY/$label.err" >&2
    fail "$label deployment failed for an unexpected reason"
  }
  assert_deploy_preserved_current
  if grep -E 'docker:.*(^|[[:space:]])down([[:space:]]|$)' "$DEPLOY_CASE_LOG" >/dev/null; then
    fail "$label deployment ran a database-down command"
  fi
}

assert_deploy_result_status() {
  local expected="$1"
  local result="$DEPLOY_CASE_ROOT/deployment-results/$DEPLOY_TARGET.json"
  [[ -f "$result" && ! -L "$result" ]] || fail "missing $expected deployment result"
  jq -e --arg release "$DEPLOY_TARGET" --arg status "$expected" \
    '.releaseId == $release and .status == $status' "$result" >/dev/null ||
    fail "deployment result did not record $expected"
}

assert_successful_automatic_recovery() {
  assert_deploy_preserved_current
  assert_deploy_result_status FAILED
  local diagnostic="$DEPLOY_CASE_ROOT/deployment-results/$DEPLOY_TARGET.rollback.log"
  [[ -f "$diagnostic" && ! -L "$diagnostic" ]] ||
    fail 'successful automatic recovery did not preserve a redacted diagnostic'
  grep -Fx 'recovery=SUCCEEDED' "$diagnostic" >/dev/null ||
    fail 'automatic recovery diagnostic did not record success'
  if grep -F '=FAILED' "$diagnostic" >/dev/null; then
    fail 'successful automatic recovery diagnostic contains a failed step'
  fi
  grep -F "smoke-release:$DEPLOY_CURRENT:api-local" "$DEPLOY_CASE_LOG" >/dev/null ||
    fail 'automatic recovery did not smoke the restored API locally'
  grep -F "smoke-release:$DEPLOY_CURRENT:nginx-local" "$DEPLOY_CASE_LOG" >/dev/null ||
    fail 'automatic recovery did not smoke the restored Nginx origin'
}

test_deploy_failure_gates() {
  prepare_deploy_case root-owner
  chown 12345:12345 "$DEPLOY_CASE_ROOT"
  if run_deploy_case "$WORK_DIRECTORY/root-owner.out" "$WORK_DIRECTORY/root-owner.err"; then
    fail 'deployment accepted an attacker-owned PORTFOLIO_ROOT'
  fi
  grep -F 'PORTFOLIO_ROOT is non-canonical or not owned by root' \
    "$WORK_DIRECTORY/root-owner.err" >/dev/null ||
    fail 'attacker-owned deployment root failed for an unexpected reason'
  [[ "$(stat -Lc '%a' "$DEPLOY_CASE_ROOT")" == 750 && ! -s "$DEPLOY_CASE_LOG" ]] ||
    fail 'deployment mutated or used an attacker-owned root before rejection'
  assert_deploy_preserved_current

  prepare_deploy_case root-writable
  chmod 0770 "$DEPLOY_CASE_ROOT"
  if run_deploy_case "$WORK_DIRECTORY/root-writable.out" "$WORK_DIRECTORY/root-writable.err"; then
    fail 'deployment accepted a group-writable PORTFOLIO_ROOT'
  fi
  grep -F 'PORTFOLIO_ROOT is writable outside root' \
    "$WORK_DIRECTORY/root-writable.err" >/dev/null ||
    fail 'group-writable deployment root failed for an unexpected reason'
  [[ "$(stat -Lc '%a' "$DEPLOY_CASE_ROOT")" == 770 && ! -s "$DEPLOY_CASE_LOG" ]] ||
    fail 'deployment normalized or used a writable root before rejection'
  assert_deploy_preserved_current

  expect_deploy_failure current-image-identity \
    'local API image portable digest differs from release.json' \
    PORTFOLIO_TEST_CURRENT_API_DIGIT=9
  if grep -F 'backup:' "$DEPLOY_CASE_LOG" >/dev/null; then
    fail 'current image identity mismatch reached the backup gate'
  fi

  expect_deploy_failure backup-failure 'verified pre-deployment backup failed' \
    PORTFOLIO_TEST_BACKUP_FAIL=true
  if grep -F ' up -d ' "$DEPLOY_CASE_LOG" >/dev/null; then
    fail 'backup failure started a target service'
  fi

  expect_deploy_failure backup-run-override 'verified pre-deployment backup failed' \
    PORTFOLIO_TEST_SYSTEMD_FRAGMENT_PATH=/run/systemd/system/portfolio-backup.service
  expect_deploy_failure backup-drop-in 'verified pre-deployment backup failed' \
    PORTFOLIO_TEST_SYSTEMD_DROP_INS=/run/systemd/system/portfolio-backup.service.d/override.conf
  expect_deploy_failure backup-stale-cache 'verified pre-deployment backup failed' \
    PORTFOLIO_TEST_SYSTEMD_NEED_RELOAD=yes
  if grep -F 'systemctl:start portfolio-backup.service' "$DEPLOY_CASE_LOG" >/dev/null; then
    fail 'stale effective backup unit reached service start'
  fi

  prepare_deploy_case backup-config-missing
  rm -f -- "$DEPLOY_CASE_BACKUP_ENV"
  if run_deploy_case "$WORK_DIRECTORY/backup-config-missing.out" \
      "$WORK_DIRECTORY/backup-config-missing.err"; then
    fail 'deployment accepted a missing independent backup environment'
  fi
  grep -F 'backup environment is missing, linked, or not absolute' \
    "$WORK_DIRECTORY/backup-config-missing.err" >/dev/null ||
    fail 'missing backup environment failed for an unexpected reason'
  if grep -F ' up -d ' "$DEPLOY_CASE_LOG" >/dev/null; then
    fail 'missing backup environment started a target service'
  fi
  assert_deploy_preserved_current

  prepare_deploy_case asset-conflict
  printf 'conflicting bytes\n' >"$DEPLOY_CASE_ROOT/assets/app.target.js"
  if run_deploy_case "$WORK_DIRECTORY/asset-conflict.out" \
      "$WORK_DIRECTORY/asset-conflict.err"; then
    fail 'conflicting create-only public asset unexpectedly deployed'
  fi
  grep -F 'public asset checksum conflict: app.target.js' \
    "$WORK_DIRECTORY/asset-conflict.err" >/dev/null ||
    fail 'public asset conflict failed for an unexpected reason'
  assert_deploy_preserved_current

  prepare_deploy_case unsafe-protected-env
  chmod 0660 "$DEPLOY_CASE_ETC/portfolio.env"
  if run_deploy_case "$WORK_DIRECTORY/unsafe-protected-env.out" \
      "$WORK_DIRECTORY/unsafe-protected-env.err"; then
    fail 'group-writable protected environment was sourced'
  fi
  grep -F 'protected environment source owner or mode is unsafe' \
    "$WORK_DIRECTORY/unsafe-protected-env.err" >/dev/null ||
    fail 'unsafe protected environment failed for an unexpected reason'
  if grep -E '^(preflight|backup):' "$DEPLOY_CASE_LOG" >/dev/null; then
    fail 'unsafe protected environment reached preflight or backup'
  fi
  assert_deploy_preserved_current

  expect_deploy_failure api-health 'target API did not become healthy before timeout' \
    PORTFOLIO_TEST_API_HEALTH_FAIL=true
  expect_deploy_failure cleanup-stale 'wrong job type or idempotency key' \
    PORTFOLIO_TEST_CLEANUP_RESULT=wrong-key
  expect_deploy_failure cleanup-payload 'payload cutoff is not canonical' \
    PORTFOLIO_TEST_CLEANUP_RESULT=wrong-payload
  expect_deploy_failure cleanup-failed 'current-release staging cleanup failed' \
    PORTFOLIO_TEST_CLEANUP_RESULT=failed
  expect_deploy_failure cleanup-timeout 'current-release staging cleanup timed out' \
    PORTFOLIO_TEST_CLEANUP_RESULT=timeout
  expect_deploy_failure cos-evidence 'preflight deployment evidence is incomplete' \
    PORTFOLIO_TEST_PROVIDERS=TENCENT_COS PORTFOLIO_TEST_COS_EVIDENCE=missing
}

test_post_switch_recovery() {
  prepare_deploy_case nginx-test-recovery
  if run_deploy_case "$WORK_DIRECTORY/nginx-test-recovery.out" \
      "$WORK_DIRECTORY/nginx-test-recovery.err" \
      PORTFOLIO_TEST_NGINX_FAIL_CALL=1; then
    fail 'injected target Nginx test failure unexpectedly deployed'
  fi
  grep -F 'injected nginx failure call 1' \
    "$WORK_DIRECTORY/nginx-test-recovery.err" >/dev/null ||
    fail 'target Nginx test failure stopped for an unexpected reason'
  assert_successful_automatic_recovery

  prepare_deploy_case nginx-smoke-recovery
  if run_deploy_case "$WORK_DIRECTORY/nginx-smoke-recovery.out" \
      "$WORK_DIRECTORY/nginx-smoke-recovery.err" \
      PORTFOLIO_TEST_SMOKE_FAIL_MODE=nginx-local; then
    fail 'injected target nginx-local smoke failure unexpectedly deployed'
  fi
  grep -F 'injected target smoke failure: nginx-local' \
    "$WORK_DIRECTORY/nginx-smoke-recovery.err" >/dev/null ||
    fail 'target nginx-local failure stopped for an unexpected reason'
  assert_successful_automatic_recovery

  prepare_deploy_case public-smoke-recovery
  DEPLOY_CASE_PUBLIC_MODE=true
  if run_deploy_case "$WORK_DIRECTORY/public-smoke-recovery.out" \
      "$WORK_DIRECTORY/public-smoke-recovery.err" \
      PORTFOLIO_TEST_SMOKE_FAIL_MODE=public; then
    fail 'injected target public smoke failure unexpectedly deployed'
  fi
  grep -F 'injected target smoke failure: public' \
    "$WORK_DIRECTORY/public-smoke-recovery.err" >/dev/null ||
    fail 'target public failure stopped for an unexpected reason'
  assert_successful_automatic_recovery

  prepare_deploy_case prune-recovery
  if ! run_deploy_case "$WORK_DIRECTORY/prune-recovery.out" \
      "$WORK_DIRECTORY/prune-recovery.err" PORTFOLIO_TEST_PRUNE_EXIT=99; then
    sed -n '1,160p' "$WORK_DIRECTORY/prune-recovery.err" >&2
    fail 'post-commit release prune failure changed deployment success'
  fi
  grep -F 'injected prune failure' "$WORK_DIRECTORY/prune-recovery.err" >/dev/null ||
    fail 'release prune failure was not surfaced'
  grep -F 'post-commit release pruning failed; the deployed release remains active' \
    "$WORK_DIRECTORY/prune-recovery.err" >/dev/null ||
    fail 'post-commit prune failure did not preserve an explicit diagnostic'
  [[ "$(tr -d '\r\n' <"$DEPLOY_CASE_ROOT/current-release")" == "$DEPLOY_TARGET" &&
     "$(tr -d '\r\n' <"$DEPLOY_CASE_ROOT/previous-release")" == "$DEPLOY_CURRENT" &&
     "$(readlink -f "$DEPLOY_CASE_ROOT/current-admin")" == "$DEPLOY_CASE_ROOT/releases/$DEPLOY_TARGET/admin" &&
     "$(readlink -f "$DEPLOY_CASE_ROOT/current-ops")" == "$DEPLOY_CASE_ROOT/releases/$DEPLOY_TARGET/ops" ]] ||
    fail 'post-commit prune failure did not retain the verified new state'
  grep -Fq "PORTFOLIO_RELEASE_ID=$DEPLOY_TARGET" "$DEPLOY_CASE_ETC/release.env" ||
    fail 'post-commit prune failure reverted release.env'
  [[ ! -e "$DEPLOY_CASE_ROOT/.portfolio-switch-journal.json" &&
     ! -L "$DEPLOY_CASE_ROOT/.portfolio-switch-journal.json" &&
     ! -e "$DEPLOY_CASE_ETC/.portfolio-switch-old-release.env" &&
     ! -L "$DEPLOY_CASE_ETC/.portfolio-switch-old-release.env" ]] ||
    fail 'post-commit prune failure left switch transaction state behind'
  [[ "$(tr -d '\r\n' <"$DEPLOY_CASE_ROOT/previous-release")" == \
     "$DEPLOY_CURRENT" ]] ||
    fail 'post-commit prune failure wrote an incorrect previous-release marker'

  prepare_deploy_case recovery-health-failure
  if run_deploy_case "$WORK_DIRECTORY/recovery-health-failure.out" \
      "$WORK_DIRECTORY/recovery-health-failure.err" \
      PORTFOLIO_TEST_SMOKE_FAIL_MODE=nginx-local \
      PORTFOLIO_TEST_ROLLBACK_API_HEALTH_FAIL=true; then
    fail 'deployment with an unhealthy recovery API unexpectedly succeeded'
  fi
  assert_deploy_preserved_current
  assert_deploy_result_status ROLLBACK_FAILED
  grep -Fx 'current-api-health=FAILED' \
    "$DEPLOY_CASE_ROOT/deployment-results/$DEPLOY_TARGET.rollback.log" >/dev/null ||
    fail 'recovery health failure was not preserved in the diagnostic'

  prepare_deploy_case recovery-nginx-failure
  if run_deploy_case "$WORK_DIRECTORY/recovery-nginx-failure.out" \
      "$WORK_DIRECTORY/recovery-nginx-failure.err" \
      PORTFOLIO_TEST_NGINX_EXIT=98; then
    fail 'deployment with persistent Nginx failure unexpectedly succeeded'
  fi
  assert_deploy_preserved_current
  assert_deploy_result_status ROLLBACK_FAILED
  grep -Fx 'nginx-reload=FAILED' \
    "$DEPLOY_CASE_ROOT/deployment-results/$DEPLOY_TARGET.rollback.log" >/dev/null ||
    fail 'recovery Nginx failure was not preserved in the diagnostic'
}

test_signal_recovery_windows() {
  local label destination
  for label in env admin ops marker; do
    prepare_deploy_case "signal-$label"
    case "$label" in
      env) destination="$DEPLOY_CASE_ETC/release.env" ;;
      admin) destination="$DEPLOY_CASE_ROOT/current-admin" ;;
      ops) destination="$DEPLOY_CASE_ROOT/current-ops" ;;
      marker) destination="$DEPLOY_CASE_ROOT/current-release" ;;
    esac
    if run_deploy_case "$WORK_DIRECTORY/signal-$label.out" \
        "$WORK_DIRECTORY/signal-$label.err" \
        PORTFOLIO_TEST_SIGNAL_AFTER_MV="$destination"; then
      fail "TERM after $label mutation unexpectedly deployed"
    fi
    [[ -f "$(dirname "$DEPLOY_CASE_ROOT")/signal-fired" ]] ||
      fail "TERM fixture did not reach the $label rename window"
    assert_successful_automatic_recovery
  done
}

test_durable_deploy_switch_journal() {
  local phase journal backup before_backup_count after_backup_count recovery_target
  local -a phases=(prepared env api admin ops markers verified)
  prepare_deploy_case journal-inherited-production-env
  if ! run_deploy_case "$WORK_DIRECTORY/journal-inherited-production-env.out" \
      "$WORK_DIRECTORY/journal-inherited-production-env.err" \
      PORTFOLIO_CONTRACT_TEST_MODE= PORTFOLIO_CONTRACT_TEST_ROOT= \
      PORTFOLIO_TEST_SWITCH_CRASH_AFTER=prepared; then
    sed -n '1,160p' "$WORK_DIRECTORY/journal-inherited-production-env.err" >&2
    fail 'production deploy path honored a switch crash variable without contract context'
  fi
  [[ "$(tr -d '\r\n' <"$DEPLOY_CASE_ROOT/current-release")" == "$DEPLOY_TARGET" &&
     ! -e "$DEPLOY_CASE_ROOT/.portfolio-switch-journal.json" &&
     ! -L "$DEPLOY_CASE_ROOT/.portfolio-switch-journal.json" &&
     ! -e "$DEPLOY_CASE_ETC/.portfolio-switch-old-release.env" &&
     ! -L "$DEPLOY_CASE_ETC/.portfolio-switch-old-release.env" ]] ||
    fail 'production deploy path with an inherited crash variable did not commit cleanly'

  for phase in "${phases[@]}"; do
    prepare_deploy_case "journal-deploy-$phase"
    if run_deploy_case "$WORK_DIRECTORY/journal-deploy-$phase.out" \
        "$WORK_DIRECTORY/journal-deploy-$phase.err" \
        PORTFOLIO_TEST_SWITCH_CRASH_AFTER="$phase"; then
      fail "SIGKILL after durable deploy phase $phase unexpectedly succeeded"
    fi
    journal="$DEPLOY_CASE_ROOT/.portfolio-switch-journal.json"
    backup="$DEPLOY_CASE_ETC/.portfolio-switch-old-release.env"
    [[ -f "$journal" && ! -L "$journal" &&
       "$(stat -Lc '%u:%g:%h:%a' -- "$journal")" == 0:0:1:600 ]] ||
      fail "deploy phase $phase did not retain a protected switch journal"
    [[ -f "$backup" && ! -L "$backup" &&
       "$(stat -Lc '%u:%g:%h:%a' -- "$backup")" == 0:0:1:600 ]] ||
      fail "deploy phase $phase did not retain a protected environment backup"
    jq -e --arg phase "$phase" --arg old "$DEPLOY_CURRENT" --arg new "$DEPLOY_TARGET" '
      (keys | sort) == (["envBackupSha256","newRelease","oldPrevious","oldRelease",
        "operation","operationId","phase","releaseEnvGid","schemaVersion"] | sort) and
      .schemaVersion == 1 and .operation == "deploy" and .phase == $phase and
      .oldRelease == $old and .newRelease == $new and
      .oldPrevious == "cccccccccccc-333333333333" and
      (.operationId | test("^[0-9a-f]{32}$")) and
      (.envBackupSha256 | test("^[0-9a-f]{64}$"))
    ' "$journal" >/dev/null || fail "deploy phase $phase retained malformed journal state"

    before_backup_count="$(grep -c '^backup:clean:' "$DEPLOY_CASE_LOG" || true)"
    if env \
        PATH="$DEPLOY_CASE_BIN:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
        PORTFOLIO_ROOT="$DEPLOY_CASE_ROOT" \
        PORTFOLIO_TEST_COMMAND_LOG="$DEPLOY_CASE_LOG" \
        bash "$DEPLOY_CASE_BACKUP" backup \
        >"$WORK_DIRECTORY/journal-backup-$phase.out" \
        2>"$WORK_DIRECTORY/journal-backup-$phase.err"; then
      fail "backup dispatcher ran during pending deploy phase $phase"
    fi
    grep -F 'pending release switch journal forbids backup dispatch' \
      "$WORK_DIRECTORY/journal-backup-$phase.err" >/dev/null ||
      fail "backup dispatcher did not fail closed during deploy phase $phase"
    after_backup_count="$(grep -c '^backup:clean:' "$DEPLOY_CASE_LOG" || true)"
    [[ "$after_backup_count" == "$before_backup_count" ]] ||
      fail "backup workload ran during pending deploy phase $phase"

    if [[ "$phase" == verified ]]; then
      recovery_target="$DEPLOY_TARGET"
    else
      recovery_target="$DEPLOY_CURRENT"
    fi
    if run_deploy_case "$WORK_DIRECTORY/journal-recover-$phase.out" \
        "$WORK_DIRECTORY/journal-recover-$phase.err" \
        PORTFOLIO_TEST_PREFLIGHT_FAIL=true; then
      fail "new deploy process after phase $phase did not stop at the injected gate"
    fi
    [[ ! -e "$journal" && ! -L "$journal" && ! -e "$backup" && ! -L "$backup" ]] ||
      fail "new deploy process did not clear recovered phase $phase transaction state"
    [[ "$(tr -d '\r\n' <"$DEPLOY_CASE_ROOT/current-release")" == "$recovery_target" &&
       "$(readlink -f "$DEPLOY_CASE_ROOT/current-admin")" == "$DEPLOY_CASE_ROOT/releases/$recovery_target/admin" &&
       "$(readlink -f "$DEPLOY_CASE_ROOT/current-ops")" == "$DEPLOY_CASE_ROOT/releases/$recovery_target/ops" ]] ||
      fail "new deploy process recovered phase $phase to the wrong release"
    grep -Fq "PORTFOLIO_RELEASE_ID=$recovery_target" "$DEPLOY_CASE_ETC/release.env" ||
      fail "new deploy process recovered phase $phase to the wrong release.env"
    if [[ "$phase" == verified ]]; then
      [[ "$(tr -d '\r\n' <"$DEPLOY_CASE_ROOT/previous-release")" == "$DEPLOY_CURRENT" ]] ||
        fail 'verified deploy recovery did not retain the committed previous marker'
    else
      [[ "$(tr -d '\r\n' <"$DEPLOY_CASE_ROOT/previous-release")" == \
         'cccccccccccc-333333333333' ]] ||
        fail "deploy phase $phase recovery did not restore the old previous marker"
    fi
  done

  prepare_deploy_case journal-preflight-recovery
  if run_deploy_case "$WORK_DIRECTORY/journal-preflight-crash.out" \
      "$WORK_DIRECTORY/journal-preflight-crash.err" \
      PORTFOLIO_TEST_SWITCH_CRASH_AFTER=env; then
    fail 'preflight recovery fixture did not crash with a pending journal'
  fi
  if env \
      PATH="$DEPLOY_CASE_BIN:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
      PORTFOLIO_ROOT="$DEPLOY_CASE_ROOT" PORTFOLIO_ETC_ROOT="$DEPLOY_CASE_ETC" \
      PORTFOLIO_RELEASE_ENV="$DEPLOY_CASE_ETC/release.env" \
      PORTFOLIO_NGINX_ENV="$DEPLOY_CASE_ETC/nginx.env" \
      PORTFOLIO_DEPLOY_GROUP=root \
      PORTFOLIO_DEPLOY_LOCK_FILE="$DEPLOY_CASE_ROOT/deploy.lock" \
      PORTFOLIO_TEST_COMMAND_LOG="$DEPLOY_CASE_LOG" \
      PORTFOLIO_TEST_TARGET_RELEASE="$DEPLOY_TARGET" \
      PORTFOLIO_TEST_ACTIVE_RELEASE_STATE="$(dirname "$DEPLOY_CASE_ROOT")/active-release" \
      PORTFOLIO_TEST_NGINX_COUNT="$(dirname "$DEPLOY_CASE_ROOT")/nginx-count" \
      PORTFOLIO_API_READY_TIMEOUT_SECONDS=1 PORTFOLIO_HEALTH_POLL_SECONDS=0 \
      bash "$REPOSITORY_ROOT/deploy/scripts/preflight.sh" \
      >"$WORK_DIRECTORY/journal-preflight-recover.out" \
      2>"$WORK_DIRECTORY/journal-preflight-recover.err"; then
    fail 'preflight recovery fixture unexpectedly passed the later jurisdiction gate'
  fi
  grep -F 'SERVER_JURISDICTION is required' \
    "$WORK_DIRECTORY/journal-preflight-recover.err" >/dev/null ||
    fail 'preflight did not reach its post-recovery jurisdiction gate'
  assert_deploy_preserved_current
  [[ ! -e "$DEPLOY_CASE_ROOT/.portfolio-switch-journal.json" &&
     ! -e "$DEPLOY_CASE_ETC/.portfolio-switch-old-release.env" ]] ||
    fail 'preflight did not clear the recovered switch transaction'

  prepare_deploy_case journal-prune-recovery
  if run_deploy_case "$WORK_DIRECTORY/journal-prune-crash.out" \
      "$WORK_DIRECTORY/journal-prune-crash.err" \
      PORTFOLIO_TEST_SWITCH_CRASH_AFTER=ops; then
    fail 'prune recovery fixture did not crash with a pending journal'
  fi
  if env \
      PATH="$DEPLOY_CASE_BIN:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
      PORTFOLIO_ROOT="$DEPLOY_CASE_ROOT" PORTFOLIO_ETC_ROOT="$DEPLOY_CASE_ETC" \
      PORTFOLIO_DEPLOY_GROUP=root \
      PORTFOLIO_DEPLOY_LOCK_FILE="$DEPLOY_CASE_ROOT/deploy.lock" \
      PORTFOLIO_TEST_COMMAND_LOG="$DEPLOY_CASE_LOG" \
      PORTFOLIO_TEST_TARGET_RELEASE="$DEPLOY_TARGET" \
      PORTFOLIO_TEST_ACTIVE_RELEASE_STATE="$(dirname "$DEPLOY_CASE_ROOT")/active-release" \
      PORTFOLIO_TEST_NGINX_COUNT="$(dirname "$DEPLOY_CASE_ROOT")/nginx-count" \
      PORTFOLIO_API_READY_TIMEOUT_SECONDS=1 PORTFOLIO_HEALTH_POLL_SECONDS=0 \
      bash "$REPOSITORY_ROOT/deploy/scripts/prune-releases.sh" --dry-run \
      >"$WORK_DIRECTORY/journal-prune-recover.out" \
      2>"$WORK_DIRECTORY/journal-prune-recover.err"; then
    fail 'prune recovery fixture unexpectedly accepted the missing historical release'
  fi
  grep -F 'retained release is missing: cccccccccccc-333333333333' \
    "$WORK_DIRECTORY/journal-prune-recover.err" >/dev/null ||
    fail 'prune did not reach release selection after switch recovery'
  assert_deploy_preserved_current
  [[ ! -e "$DEPLOY_CASE_ROOT/.portfolio-switch-journal.json" &&
     ! -e "$DEPLOY_CASE_ETC/.portfolio-switch-old-release.env" ]] ||
    fail 'prune did not clear the recovered switch transaction'

  local kind malicious
  for kind in truncated symlink directory fifo; do
    prepare_deploy_case "journal-malicious-$kind"
    malicious="$DEPLOY_CASE_ROOT/.portfolio-switch-journal.json"
    case "$kind" in
      truncated) printf '{"schemaVersion":1' >"$malicious"; chmod 0600 "$malicious" ;;
      symlink) ln -s -- "$DEPLOY_CASE_ETC/release.env" "$malicious" ;;
      directory) mkdir -- "$malicious" ;;
      fifo) mkfifo -- "$malicious" ;;
    esac
    if run_deploy_case "$WORK_DIRECTORY/journal-malicious-$kind.out" \
        "$WORK_DIRECTORY/journal-malicious-$kind.err"; then
      fail "deployment accepted a $kind switch journal"
    fi
    grep -E 'switch journal|pending release switch recovery failed' \
      "$WORK_DIRECTORY/journal-malicious-$kind.err" >/dev/null ||
      fail "$kind switch journal failed for an unexpected reason"
    assert_deploy_preserved_current
  done

  prepare_deploy_case result-write-after-commit
  printf 'blocks result directory creation\n' >"$DEPLOY_CASE_ROOT/deployment-results"
  if ! run_deploy_case "$WORK_DIRECTORY/result-write-after-commit.out" \
      "$WORK_DIRECTORY/result-write-after-commit.err"; then
    sed -n '1,160p' "$WORK_DIRECTORY/result-write-after-commit.err" >&2
    fail 'post-commit deployment-result failure changed deployment success'
  fi
  grep -F 'could not persist the successful deployment result' \
    "$WORK_DIRECTORY/result-write-after-commit.err" >/dev/null ||
    fail 'post-commit deployment-result failure was not surfaced'
  [[ "$(tr -d '\r\n' <"$DEPLOY_CASE_ROOT/current-release")" == "$DEPLOY_TARGET" &&
     "$(readlink -f "$DEPLOY_CASE_ROOT/current-admin")" == "$DEPLOY_CASE_ROOT/releases/$DEPLOY_TARGET/admin" &&
     "$(readlink -f "$DEPLOY_CASE_ROOT/current-ops")" == "$DEPLOY_CASE_ROOT/releases/$DEPLOY_TARGET/ops" &&
     ! -e "$DEPLOY_CASE_ROOT/.portfolio-switch-journal.json" &&
     ! -e "$DEPLOY_CASE_ETC/.portfolio-switch-old-release.env" ]] ||
    fail 'post-commit deployment-result failure rolled back or left transaction state'
}

test_first_deployment_backup_gate() {
  prepare_deploy_case initial-flag
  rm -f -- \
    "$DEPLOY_CASE_ROOT/current-release" "$DEPLOY_CASE_ROOT/previous-release" \
    "$DEPLOY_CASE_ROOT/current-admin" "$DEPLOY_CASE_ROOT/current-ops"
  if run_deploy_case "$WORK_DIRECTORY/initial-flag.out" "$WORK_DIRECTORY/initial-flag.err"; then
    fail 'first deployment without explicit empty-database flag unexpectedly succeeded'
  fi
  grep -F 'first deployment requires --initial-empty-database' \
    "$WORK_DIRECTORY/initial-flag.err" >/dev/null ||
    fail 'missing first-deployment flag failed for an unexpected reason'

  prepare_deploy_case initial-volume
  rm -f -- \
    "$DEPLOY_CASE_ROOT/current-release" "$DEPLOY_CASE_ROOT/previous-release" \
    "$DEPLOY_CASE_ROOT/current-admin" "$DEPLOY_CASE_ROOT/current-ops"
  DEPLOY_CASE_INITIAL_MODE=true
  if run_deploy_case "$WORK_DIRECTORY/initial-volume.out" \
      "$WORK_DIRECTORY/initial-volume.err" PORTFOLIO_TEST_VOLUME_EXISTS=true; then
    fail 'first deployment accepted a pre-existing PostgreSQL volume'
  fi
  grep -F 'requires a provably new PostgreSQL volume' \
    "$WORK_DIRECTORY/initial-volume.err" >/dev/null ||
    fail 'unsafe first-deployment volume failed for an unexpected reason'

  prepare_deploy_case initial-api-failure
  rm -f -- \
    "$DEPLOY_CASE_ROOT/current-release" "$DEPLOY_CASE_ROOT/previous-release" \
    "$DEPLOY_CASE_ROOT/current-admin" "$DEPLOY_CASE_ROOT/current-ops"
  DEPLOY_CASE_INITIAL_MODE=true
  if run_deploy_case "$WORK_DIRECTORY/initial-api-failure.out" \
      "$WORK_DIRECTORY/initial-api-failure.err" \
      PORTFOLIO_TEST_API_HEALTH_FAIL=true; then
    fail 'first deployment with an unhealthy target API unexpectedly succeeded'
  fi
  grep -F 'target API did not become healthy before timeout' \
    "$WORK_DIRECTORY/initial-api-failure.err" >/dev/null ||
    fail 'initial API failure stopped for an unexpected reason'
  grep -F ' stop portfolio-api' "$DEPLOY_CASE_LOG" >/dev/null ||
    fail 'failed first deployment did not stop the uncommitted target API'
  grep -F ' rm -f portfolio-api' "$DEPLOY_CASE_LOG" >/dev/null ||
    fail 'failed first deployment did not remove the uncommitted target API'
  [[ ! -e "$DEPLOY_CASE_ROOT/current-release" &&
     ! -e "$DEPLOY_CASE_ROOT/current-admin" && ! -e "$DEPLOY_CASE_ROOT/current-ops" ]] ||
    fail 'failed first deployment published a release marker or active pointer'
  if grep -E 'docker:.*(^|[[:space:]])down([[:space:]]|$)' "$DEPLOY_CASE_LOG" >/dev/null; then
    fail 'failed first deployment ran a database-down command'
  fi

  local original_target="$DEPLOY_TARGET"
  DEPLOY_TARGET='dddddddddddd-444444444444'
  write_deploy_release "$DEPLOY_CASE_ROOT" "$DEPLOY_TARGET" 3 18
  if run_deploy_case "$WORK_DIRECTORY/initial-different-release.out" \
      "$WORK_DIRECTORY/initial-different-release.err"; then
    fail 'different release reused an unpublished initial PostgreSQL volume'
  fi
  grep -F 'requires a provably new PostgreSQL volume or an unpublished same-release retry' \
    "$WORK_DIRECTORY/initial-different-release.err" >/dev/null ||
    fail 'different-release initial retry failed for an unexpected reason'
  [[ ! -e "$DEPLOY_CASE_ROOT/current-release" &&
     ! -e "$DEPLOY_CASE_ROOT/current-admin" && ! -e "$DEPLOY_CASE_ROOT/current-ops" ]] ||
    fail 'rejected different-release retry published state'
  DEPLOY_TARGET="$original_target"

  if ! run_deploy_case "$WORK_DIRECTORY/initial-retry.out" \
      "$WORK_DIRECTORY/initial-retry.err"; then
    sed -n '1,160p' "$WORK_DIRECTORY/initial-retry.err" >&2
    fail 'same-release retry after a failed first deployment did not succeed'
  fi
  [[ "$(tr -d '\r\n' <"$DEPLOY_CASE_ROOT/current-release")" == "$DEPLOY_TARGET" &&
     "$(readlink -f "$DEPLOY_CASE_ROOT/current-admin")" == \
       "$DEPLOY_CASE_ROOT/releases/$DEPLOY_TARGET/admin" &&
     "$(readlink -f "$DEPLOY_CASE_ROOT/current-ops")" == \
       "$DEPLOY_CASE_ROOT/releases/$DEPLOY_TARGET/ops" ]] ||
    fail 'same-release initial retry did not publish the target release'
  [[ "$(grep -c 'docker:volume create' "$DEPLOY_CASE_LOG")" -eq 1 ]] ||
    fail 'same-release initial retry created or replaced the retained PostgreSQL volume'
  grep -F 'reusing the unpublished PostgreSQL volume from an earlier attempt' \
    "$WORK_DIRECTORY/initial-retry.err" >/dev/null ||
    fail 'same-release initial retry did not identify the retained volume explicitly'
  if grep -F 'systemctl:start portfolio-backup.service' "$DEPLOY_CASE_LOG" >/dev/null; then
    fail 'initial empty-database deployment invoked the backup service'
  fi
}

test_successful_deploy_case() {
  local label="$1" providers="$2" cleanup_result="$3"
  prepare_deploy_case "$label"
  run_deploy_case "$WORK_DIRECTORY/$label.out" "$WORK_DIRECTORY/$label.err" \
    PORTFOLIO_TEST_PROVIDERS="$providers" \
    PORTFOLIO_TEST_CLEANUP_RESULT="$cleanup_result" || {
      sed -n '1,200p' "$WORK_DIRECTORY/$label.err" >&2
      fail "$label valid deployment failed"
    }
  [[ "$(tr -d '\r\n' <"$DEPLOY_CASE_ROOT/current-release")" == "$DEPLOY_TARGET" &&
     "$(tr -d '\r\n' <"$DEPLOY_CASE_ROOT/previous-release")" == "$DEPLOY_CURRENT" ]] ||
    fail "$label deployment wrote incorrect release markers"
  [[ "$(readlink -f "$DEPLOY_CASE_ROOT/current-admin")" == \
     "$DEPLOY_CASE_ROOT/releases/$DEPLOY_TARGET/admin" ]] ||
    fail "$label deployment did not switch the administrator pointer"
  [[ "$(readlink -f "$DEPLOY_CASE_ROOT/current-ops")" == \
     "$DEPLOY_CASE_ROOT/releases/$DEPLOY_TARGET/ops" ]] ||
    fail "$label deployment did not switch the stable operations pointer"
  grep -Fq "POSTGRES_IMAGE=portfolio-postgres-17:$DEPLOY_TARGET" \
    "$DEPLOY_CASE_ETC/release.env" || fail "$label release.env lacks the local PostgreSQL tag"
  cmp -s \
    "$DEPLOY_CASE_ROOT/releases/$DEPLOY_TARGET/public-assets/assets/app.target.js" \
    "$DEPLOY_CASE_ROOT/assets/app.target.js" ||
    fail "$label did not install the Nginx-visible hashed asset at /assets/app.target.js"
  [[ ! -e "$DEPLOY_CASE_ROOT/assets/assets" ]] ||
    fail "$label incorrectly nested the Vite assets directory under the shared asset root"
  [[ "$(stat -Lc '%a' "$DEPLOY_CASE_ROOT")" == 711 ]] ||
    fail "$label deployment did not make the portfolio root traversable without listing"
  chmod 0711 "$WORK_DIRECTORY" "$(dirname "$DEPLOY_CASE_ROOT")"
  setpriv --reuid=65534 --regid=65534 --clear-groups \
    grep -F "target-asset-$DEPLOY_TARGET" \
    "$DEPLOY_CASE_ROOT/assets/app.target.js" >/dev/null ||
    fail "$label shared hashed asset is not readable by a non-root Nginx fixture"
  grep -F 'smoke:nginx-local --resolve yychainsaw.xyz:18443:127.0.0.1' \
    "$DEPLOY_CASE_LOG" >/dev/null || fail "$label did not run loopback Nginx smoke"
  grep -F 'prune:' "$DEPLOY_CASE_LOG" >/dev/null || fail "$label did not run release pruning"
  grep -F 'systemctl:start portfolio-backup.service' "$DEPLOY_CASE_LOG" >/dev/null ||
    fail "$label did not invoke the hardened backup service"
  grep -Fx 'systemctl:daemon-reload' "$DEPLOY_CASE_LOG" >/dev/null ||
    fail "$label did not reload and re-resolve the effective backup unit"
  grep -F 'systemctl:show portfolio-backup.service' "$DEPLOY_CASE_LOG" >/dev/null ||
    fail "$label did not verify the backup service result"
  grep -F 'backup:clean:--verify-upload' "$DEPLOY_CASE_LOG" >/dev/null ||
    fail "$label did not run the real dispatcher with a secret-clean environment"
}

test_deploy_provider_matrix_and_boundary() {
  test_successful_deploy_case local-success LOCAL success
  local boundary_epoch cutoff
  boundary_epoch="$(TZ=Asia/Hong_Kong date --date='2026-07-17 04:00:00' '+%s')"
  cutoff=$((boundary_epoch - 86400))
  grep -F "query:CLEAN_MEDIA_STAGING|media-staging-cleanup:$DEPLOY_TARGET:2026-07-17|{\"cutoffEpochSecond\":$cutoff}" \
    "$DEPLOY_CASE_LOG" >/dev/null ||
    fail 'before-04:00 deployment did not derive the exact prior Hong Kong boundary and cutoff'

  test_successful_deploy_case cos-success TENCENT_COS forbidden
  if grep -F 'query:' "$DEPLOY_CASE_LOG" >/dev/null; then
    fail 'COS-only deployment unexpectedly waited for a Local cleanup job'
  fi
  test_successful_deploy_case mixed-success LOCAL,TENCENT_COS success
  grep -F 'query:' "$DEPLOY_CASE_LOG" >/dev/null ||
    fail 'mixed-provider deployment skipped the Local cleanup job gate'
}

test_deploy_release_state_machine() {
  [[ -f "$DEPLOYER" ]] || fail 'deployment controller is missing'
  test_deploy_failure_gates
  test_post_switch_recovery
  test_signal_recovery_windows
  test_durable_deploy_switch_journal
  test_first_deployment_backup_gate
  test_deploy_provider_matrix_and_boundary
}

test_edge_smoke_source_contract() {
  [[ -f "$SMOKE" ]] || fail 'smoke controller is missing'
  grep -F "request edge-actuator '/actuator/health/readiness' 404" "$SMOKE" >/dev/null ||
    fail 'edge smoke does not deny the actuator route on a real response'
  for header in \
    x-content-type-options referrer-policy x-frame-options permissions-policy \
    content-security-policy; do
    grep -F "$header" "$SMOKE" >/dev/null ||
      fail "edge smoke does not verify the $header response header"
  done
  grep -F 'assert_header_exact_normalized public-media content-type' "$SMOKE" >/dev/null ||
    fail 'public-media smoke does not exactly compare normalized Content-Type'
}

test_release_source_hygiene_contract() {
  local fixture="$WORK_DIRECTORY/release-source-hygiene" git_fixture cache_fixture
  git_fixture="$fixture/git"
  cache_fixture="$fixture/cache"
  mkdir -p "$git_fixture/deploy/scripts" "$git_fixture/bin" \
    "$cache_fixture/ops/deploy/scripts/__pycache__"
  cp -- "$BUILD_RELEASE" "$git_fixture/deploy/scripts/build-release.sh"
  cat >"$git_fixture/bin/git" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
case "$*" in
  *'rev-parse --show-toplevel'*) printf '%s\n' "$PORTFOLIO_TEST_GIT_ROOT" ;;
  *' diff '*) exit 0 ;;
  *'ls-files --others --exclude-standard -z'*) printf 'release-input.txt\0' ;;
  *) exit 64 ;;
esac
STUB
  chmod 0755 "$git_fixture/bin/git"
  printf 'must be committed\n' >"$git_fixture/release-input.txt"
  if env PATH="$git_fixture/bin:/usr/bin:/bin" PORTFOLIO_TEST_GIT_ROOT="$git_fixture" \
      bash "$git_fixture/deploy/scripts/build-release.sh" \
      >"$fixture/untracked.out" 2>"$fixture/untracked.err"; then
    fail 'release builder accepted a non-ignored untracked source file'
  fi
  grep -F 'release source must be a complete clean commit' "$fixture/untracked.err" >/dev/null ||
    fail 'untracked source rejection failed for an unexpected reason'

  grep -F '*/__pycache__/*)' "$BUILD_RELEASE" >/dev/null ||
    fail 'release builder does not explicitly reject tracked __pycache__ paths'
  grep -F '*.py[cod])' "$BUILD_RELEASE" >/dev/null ||
    fail 'release builder does not explicitly reject tracked Python bytecode'
  printf 'forced cache\n' >"$cache_fixture/ops/deploy/scripts/__pycache__/forced.py"
  if bash "$RELEASE_ARTIFACT_CONTRACT" "$cache_fixture" "$RELEASE_ID" \
      >"$fixture/cache-dir.out" 2>"$fixture/cache-dir.err"; then
    fail 'release artifact contract accepted an __pycache__ directory'
  fi
  grep -F 'forbidden Python cache material' "$fixture/cache-dir.err" >/dev/null ||
    fail '__pycache__ artifact rejection failed for an unexpected reason'
  rm -rf -- "$cache_fixture/ops/deploy/scripts/__pycache__"
  printf 'forced bytecode\n' >"$cache_fixture/ops/deploy/scripts/forced.PYC"
  if bash "$RELEASE_ARTIFACT_CONTRACT" "$cache_fixture" "$RELEASE_ID" \
      >"$fixture/bytecode.out" 2>"$fixture/bytecode.err"; then
    fail 'release artifact contract accepted forced-added Python bytecode'
  fi
  grep -F 'forbidden Python cache material' "$fixture/bytecode.err" >/dev/null ||
    fail 'Python bytecode artifact rejection failed for an unexpected reason'
}

test_maven_verify_runner_contract() {
  local dockerfile="$REPOSITORY_ROOT/Dockerfile" compliance_block java_base_block runtime_block playwright_block
  local fixture="$WORK_DIRECTORY/apt-snapshot-verifier" snapshot='20260718T000000Z'
  local apt_root="$fixture/apt" lists_root="$fixture/lists" suite lock_line key integration_source
  local -A parsed_lock=()
  java_base_block="$(sed -n '/^FROM .* AS java-build-base$/,/^FROM java-build-base AS server-test$/p' \
    "$dockerfile")"
  runtime_block="$(sed -n '/^FROM .* AS runtime$/,/^COPY --from=server-build/p' "$dockerfile")"
  playwright_block="$(sed -n '/^FROM .* AS public-e2e$/,/^FROM public-deps AS public-build$/p' "$dockerfile")"
  mkdir -p -- "$fixture"
  grep -Fx '# syntax=docker/dockerfile:1.7@sha256:a57df69d0ea827fb7266491f2813635de6f17269be881f696fbfdf2d83dda33e' \
    "$dockerfile" >/dev/null || fail 'Dockerfile frontend is not digest-pinned'
  [[ -f "$IMAGE_LOCK_LIB" && ! -L "$IMAGE_LOCK_LIB" ]] || fail 'image-lock parser is missing'
  # shellcheck source=deploy/scripts/image-lock-lib.sh
  source "$IMAGE_LOCK_LIB"
  portfolio_load_image_lock "$REPOSITORY_ROOT/deploy/image-lock.env" parsed_lock ||
    fail 'valid source image lock was rejected'
  [[ "${#parsed_lock[@]}" -eq 7 ]] || fail 'source image lock has an unexpected key set'
  while IFS= read -r lock_line; do
    [[ -z "$lock_line" || "$lock_line" == \#* ]] && continue
    key="${lock_line%%=*}"
    case "$key" in
      NODE_IMAGE|PLAYWRIGHT_IMAGE|JAVA_BUILD_IMAGE|JAVA_RUNTIME_IMAGE)
        grep -Fx "ARG $lock_line" "$dockerfile" >/dev/null ||
          fail "Dockerfile default does not use the source-pinned $key digest"
        ;;
    esac
  done <"$REPOSITORY_ROOT/deploy/image-lock.env"
  cp -- "$REPOSITORY_ROOT/deploy/image-lock.env" "$fixture/tag-only.env"
  sed -i 's/@sha256:[0-9a-f]\{64\}//' "$fixture/tag-only.env"
  if portfolio_load_image_lock "$fixture/tag-only.env" parsed_lock 2>"$fixture/tag-only.err"; then
    fail 'image-lock parser accepted mutable tag-only references'
  fi
  grep -F 'tag@sha256' "$fixture/tag-only.err" >/dev/null ||
    fail 'tag-only image rejection failed for an unexpected reason'
  cp -- "$REPOSITORY_ROOT/deploy/image-lock.env" "$fixture/wrong-version.env"
  sed -i 's/node:22\.18\.0-bookworm-slim/node:22-bookworm-slim/' "$fixture/wrong-version.env"
  if portfolio_load_image_lock "$fixture/wrong-version.env" parsed_lock 2>"$fixture/wrong-version.err"; then
    fail 'image-lock parser accepted an unreviewed Node tag with a digest'
  fi
  portfolio_load_image_lock "$REPOSITORY_ROOT/deploy/image-lock.env" parsed_lock ||
    fail 'valid source image lock failed after negative cases'
  if portfolio_require_locked_image \
      "${parsed_lock[NODE_IMAGE]%?}0" "${parsed_lock[NODE_IMAGE]}" \
      2>"$fixture/drift.err"; then
    fail 'rebuild input accepted a digest that drifted from the source lock'
  fi
  grep -F 'differs from the source-pinned digest' "$fixture/drift.err" >/dev/null ||
    fail 'digest drift rejection failed for an unexpected reason'
  jq -n \
    --arg node "${parsed_lock[NODE_IMAGE]}" \
    --arg playwright "${parsed_lock[PLAYWRIGHT_IMAGE]}" \
    --arg java_build "${parsed_lock[JAVA_BUILD_IMAGE]}" \
    --arg java_runtime "${parsed_lock[JAVA_RUNTIME_IMAGE]}" \
    --arg postgres "${parsed_lock[POSTGRES_IMAGE]}" \
    --arg snapshot "${parsed_lock[UBUNTU_APT_SNAPSHOT]}" \
    --arg platform "${parsed_lock[TARGET_PLATFORM]}" \
    '{postgresImageRef:$postgres,buildInputs:{nodeImageRef:$node,
      playwrightImageRef:$playwright,javaBuildImageRef:$java_build,
      javaRuntimeImageRef:$java_runtime,ubuntuAptSnapshot:$snapshot,
      targetPlatform:$platform}}' >"$fixture/release-lock.json"
  portfolio_require_release_image_lock "$fixture/release-lock.json" parsed_lock ||
    fail 'release image-lock binding rejected the valid exact references'
  jq '.buildInputs.nodeImageRef = (.buildInputs.nodeImageRef[0:-1] + "0")' \
    "$fixture/release-lock.json" >"$fixture/release-lock-drift.json"
  if portfolio_require_release_image_lock "$fixture/release-lock-drift.json" parsed_lock \
      2>"$fixture/release-lock-drift.err"; then
    fail 'release/restore image-lock binding accepted a drifted digest'
  fi
  grep -F 'release build inputs differ from the source-pinned image lock' \
    "$fixture/release-lock-drift.err" >/dev/null ||
    fail 'release/restore image-lock drift failed for an unexpected reason'
  grep -F 'source "$TRUSTED_IMAGE_LOCK_LIB"' "$RELEASE_ARTIFACT_CONTRACT" >/dev/null ||
    fail 'release artifact verifier does not use its trusted image-lock parser'
  if grep -F 'source "$release_image_lock_lib"' "$RELEASE_ARTIFACT_CONTRACT" >/dev/null; then
    fail 'release artifact verifier executes an untrusted release-local parser'
  fi
  grep -F 'FROM ${PLAYWRIGHT_IMAGE} AS public-e2e' <<<"$playwright_block" >/dev/null ||
    fail 'public E2E target does not consume the reviewed Playwright image argument'
  grep -F 'COPY --from=node-toolchain /usr/local/ /usr/local/' <<<"$playwright_block" >/dev/null ||
    fail 'public E2E target does not use the release Node 22 toolchain'
  grep -F 'PLAYWRIGHT_BROWSERS_PATH=/ms-playwright' <<<"$playwright_block" >/dev/null ||
    fail 'public E2E target does not use the browser payload shipped in the reviewed image'
  grep -F 'PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1' <<<"$playwright_block" >/dev/null ||
    fail 'public E2E target can download an unreviewed browser payload'
  grep -F "test \"\$(node --version)\" = 'v22.18.0'" <<<"$playwright_block" >/dev/null ||
    fail 'public E2E target does not verify the release Node version'
  grep -F "test \"\$(npm --version)\" = '10.9.3'" <<<"$playwright_block" >/dev/null ||
    fail 'public E2E target does not verify the release npm version'
  grep -F "test \"\$(npx --no-install playwright --version)\" = 'Version 1.58.2'" \
    <<<"$playwright_block" >/dev/null ||
    fail 'public E2E target does not verify the reviewed Playwright version'
  grep -F 'RUN --network=none npm run test:e2e' <<<"$playwright_block" >/dev/null ||
    fail 'public E2E target is not an offline release gate'
  if grep -Eiq 'playwright[[:space:]]+install|--with-deps' <<<"$playwright_block"; then
    fail 'public E2E target performs an unreviewed browser installation'
  fi
  grep -F -- '--target public-e2e "${docker_build_args[@]}" "$source_dir"' \
    "$BUILD_RELEASE" >/dev/null ||
    fail 'release builder does not execute the digest-pinned public E2E target'
  grep -F -- '--arg playwrightImageRef "$playwright_image_ref"' "$BUILD_RELEASE" >/dev/null ||
    fail 'release builder does not record the resolved Playwright image reference'
  grep -F 'buildInputs:{nodeImageRef:$nodeImageRef,playwrightImageRef:$playwrightImageRef' \
    "$BUILD_RELEASE" >/dev/null ||
    fail 'release identity does not bind the resolved Playwright image reference'
  sed -n '/^bundle_payload_sha256=/,/sha256sum/p' "$BUILD_RELEASE" |
    grep -F 'buildInputs}' >/dev/null ||
    fail 'release payload hash does not bind the complete build inputs'
  grep -F 'ARG UBUNTU_APT_SNAPSHOT' <<<"$java_base_block" >/dev/null ||
    fail 'java-build-base does not consume the reviewed Ubuntu snapshot argument'
  grep -F 'apt-get install -y --no-install-recommends unzip' <<<"$java_base_block" >/dev/null ||
    fail 'java-build-base does not install unzip for a cold Maven wrapper cache'
  for container_block in "$java_base_block" "$runtime_block"; do
    grep -F 'source=docker/verify-ubuntu-apt-snapshot.sh' <<<"$container_block" >/dev/null ||
      fail 'Ubuntu package installation does not mount the reviewed snapshot verifier'
    grep -F "prepare \"\$UBUNTU_APT_SNAPSHOT\" /etc/apt" <<<"$container_block" >/dev/null ||
      fail 'Ubuntu package sources are not rewritten to the reviewed snapshot before update'
    grep -F "verify \"\$UBUNTU_APT_SNAPSHOT\"" <<<"$container_block" >/dev/null ||
      fail 'Ubuntu package indexes are not verified after apt update'
    grep -F 'apt-get indextargets' <<<"$container_block" >/dev/null ||
      fail 'Ubuntu package installation does not capture materialized apt index targets'
    grep -F 'rm -rf /var/lib/apt/lists/*;' <<<"$container_block" >/dev/null ||
      fail 'Ubuntu package installation can reuse unreviewed inherited apt indexes'
  done

  grep -F "MINIMUM_JAMMY_APT_VERSION='2.4.11'" "$APT_SNAPSHOT_VERIFIER" >/dev/null ||
    fail 'Jammy snapshot verifier does not enforce the Canonical apt version floor'
  grep -F "SNAPSHOT_ORIGIN='https://snapshot.ubuntu.com/ubuntu'" \
    "$APT_SNAPSHOT_VERIFIER" >/dev/null ||
    fail 'Jammy snapshot verifier does not pin the Canonical snapshot service'
  grep -F "fail 'apt update contacted a live Ubuntu archive outside the reviewed snapshot'" \
    "$APT_SNAPSHOT_VERIFIER" >/dev/null ||
    fail 'Jammy snapshot verifier does not reject live Ubuntu archive fallback'

  mkdir -p "$fixture/bin" "$apt_root/sources.list.d" "$lists_root"
  cat >"$fixture/bin/apt-get" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
[[ "${1:-}" == --version ]] || exit 64
printf 'apt %s (amd64)\n' "${PORTFOLIO_TEST_APT_VERSION:-2.4.14}"
STUB
  chmod 0755 "$fixture/bin/apt-get"
  cat >"$apt_root/sources.list" <<'SOURCES'
deb http://archive.ubuntu.com/ubuntu/ jammy main
deb http://archive.ubuntu.com/ubuntu/ jammy-updates main
deb http://archive.ubuntu.com/ubuntu/ jammy-backports main
deb http://security.ubuntu.com/ubuntu/ jammy-security main
SOURCES
  env PATH="$fixture/bin:/usr/bin:/bin" \
    bash "$APT_SNAPSHOT_VERIFIER" prepare "$snapshot" "$apt_root"
  if grep -Eq 'https?://(archive|security)\.ubuntu\.com/ubuntu' "$apt_root/sources.list"; then
    fail 'snapshot prepare mode retained a live Ubuntu archive source'
  fi
  for suite in jammy jammy-updates jammy-backports jammy-security; do
    grep -F "deb https://snapshot.ubuntu.com/ubuntu/$snapshot $suite " \
      "$apt_root/sources.list" >/dev/null ||
      fail "snapshot prepare mode did not rewrite $suite to the exact snapshot"
    printf 'Get:1 https://snapshot.ubuntu.com/ubuntu/%s %s InRelease [1 B]\n' \
      "$snapshot" "$suite" >>"$fixture/update.log"
    printf 'https://snapshot.ubuntu.com/ubuntu/%s|%s|Packages|%s/%s\n' \
      "$snapshot" "$suite" "$lists_root" "$suite" >>"$fixture/targets"
    printf 'signed fixture\n' \
      >"$lists_root/snapshot.ubuntu.com_ubuntu_${snapshot}_dists_${suite}_InRelease"
  done
  env PATH="$fixture/bin:/usr/bin:/bin" \
    bash "$APT_SNAPSHOT_VERIFIER" verify "$snapshot" \
      "$fixture/update.log" "$fixture/targets" "$apt_root" "$lists_root"

  if env PATH="$fixture/bin:/usr/bin:/bin" PORTFOLIO_TEST_APT_VERSION=2.4.10 \
      bash "$APT_SNAPSHOT_VERIFIER" verify "$snapshot" \
        "$fixture/update.log" "$fixture/targets" "$apt_root" "$lists_root" \
        >"$fixture/old-apt.out" 2>"$fixture/old-apt.err"; then
    fail 'snapshot verifier accepted Jammy apt 2.4.10, which ignores snapshot options'
  fi
  grep -F 'requires apt >= 2.4.11' "$fixture/old-apt.err" >/dev/null ||
    fail 'old apt rejection failed for an unexpected reason'

  grep -v ' jammy-security InRelease' "$fixture/update.log" >"$fixture/missed-update.log"
  if env PATH="$fixture/bin:/usr/bin:/bin" \
      bash "$APT_SNAPSHOT_VERIFIER" verify "$snapshot" \
        "$fixture/missed-update.log" "$fixture/targets" "$apt_root" "$lists_root" \
        >"$fixture/missed.out" 2>"$fixture/missed.err"; then
    fail 'snapshot verifier accepted an apt update that never hit the security snapshot pocket'
  fi
  grep -F "did not fetch 'jammy-security' from snapshot $snapshot" \
    "$fixture/missed.err" >/dev/null ||
    fail 'missing snapshot pocket rejection failed for an unexpected reason'

  grep -F 'maven_verify_mode="${PORTFOLIO_MAVEN_VERIFY_MODE:-host}"' "$BUILD_RELEASE" >/dev/null ||
    fail 'full Maven verification does not default to the host Testcontainers runner'
  grep -F 'approved host Maven verification requires Java 17' "$BUILD_RELEASE" >/dev/null ||
    fail 'host Maven verification does not enforce Java 17'
  grep -F './mvnw -B -Dproject.build.outputTimestamp="$source_date_epoch" verify' \
    "$BUILD_RELEASE" >/dev/null || fail 'host Maven verification no longer runs the full verify lifecycle'
  if grep -F '/var/run/docker.sock' "$BUILD_RELEASE" >/dev/null; then
    fail 'release builder exposes the Docker socket to a build container'
  fi
  if grep -F 'PORTFOLIO_MAVEN_VERIFY_MODE:-container' "$BUILD_RELEASE" >/dev/null; then
    fail 'release builder still defaults to the Docker-socket Maven runner'
  fi
  integration_source="$REPOSITORY_ROOT/backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/support/PostgresTestImage.java"
  grep -F 'postgres:17-bookworm@sha256:4f736ae292687621d4dbe0d499ffd024a36bd2ee7d8ca6f2ccd4c800f047b394' \
    "$integration_source" >/dev/null || fail 'Testcontainers PostgreSQL image is not digest-pinned'
  grep -F '.asCompatibleSubstituteFor("postgres")' "$integration_source" >/dev/null ||
    fail 'digest-pinned Testcontainers PostgreSQL image lacks explicit compatibility'
  if grep -R --include='*.java' -F 'new PostgreSQLContainer<>("postgres:' \
      "$REPOSITORY_ROOT/backend-parent/portfolio-server/src/test/java" >/dev/null; then
    fail 'a Testcontainers source bypasses the shared digest-pinned PostgreSQL image'
  fi
  compliance_block="$(sed -n "/^generate_syft_raw_sbom() {$/,/^cleanup_tags=()$/p" "$BUILD_RELEASE")"
  for hardening in '--pull never' '--network none' '--read-only' '--cap-drop ALL' \
      '--security-opt no-new-privileges:true'; do
    grep -F -- "$hardening" <<<"$compliance_block" >/dev/null ||
      fail "host-side Syft gate lost hardening: $hardening"
  done
  grep -F "\$syft_image_ref" <<<"$compliance_block" >/dev/null ||
    fail 'host-side Syft gate does not execute the digest-pinned image'
  grep -F 'dst=/run/api-syft.raw.cdx.json,readonly' <<<"$compliance_block" >/dev/null ||
    fail 'compliance runner does not receive API Syft evidence read-only'
  grep -F -- '--api-syft-raw "$API_SYFT_RAW"' <<<"$compliance_block" >/dev/null ||
    fail 'compliance generator does not consume precomputed API Syft evidence'
  grep -F '&& ! command -v docker' <<<"$(sed -n '/^FROM java-build-base AS compliance-runner$/,/^FROM java-build-base AS server-test$/p' "$dockerfile")" >/dev/null ||
    fail 'compliance runner still includes a Docker client'
}

test_crash_failpoint_scope_contract() {
  local script function_block
  local -a scripts=(
    "$LOCAL_VOLUME_PROVISIONER"
    "$REPOSITORY_ROOT/deploy/scripts/switch-journal.sh"
  )
  for script in "${scripts[@]}"; do
    # shellcheck disable=SC2016
    grep -F '[[ "${PORTFOLIO_CONTRACT_TEST_MODE:-}" == portfolio-state-machine-v1 ]]' \
      "$script" >/dev/null ||
      fail "$(basename "$script") crash failpoint lacks an explicit contract-test mode"
    # shellcheck disable=SC2016
    grep -F 'source_path="$(realpath -e -- "${BASH_SOURCE[0]}")"' "$script" >/dev/null ||
      fail "$(basename "$script") crash failpoint does not canonicalize its source path"
    # shellcheck disable=SC2016
    grep -F '/workspace/*|"$fixture_root"/*)' "$script" >/dev/null ||
      fail "$(basename "$script") crash failpoint is not restricted to a reviewed test source tree"
    # shellcheck disable=SC2016
    grep -F 'scope="$(realpath -e -- ' "$script" >/dev/null ||
      fail "$(basename "$script") crash failpoint does not bind its fixture scope canonically"
  done
  function_block="$(sed -n '/^crash_after() {$/,/^}$/p' "$LOCAL_VOLUME_PROVISIONER")"
  grep -F 'contract_test_failpoint_enabled || return 0' <<<"$function_block" >/dev/null ||
    fail 'Local volume SIGKILL can run without its contract-test guard'
  function_block="$(sed -n '/^switch_journal_maybe_crash() {$/,/^}$/p' \
    "$REPOSITORY_ROOT/deploy/scripts/switch-journal.sh")"
  grep -F 'switch_journal_contract_test_failpoint_enabled || return 0' \
    <<<"$function_block" >/dev/null ||
    fail 'switch-journal SIGKILL can run without its contract-test guard'
}

test_production_signal_cleanup_contract() {
  local script fixture="$WORK_DIRECTORY/production-signal-cleanup"
  local -a scripts=(
    "$REPOSITORY_ROOT/deploy/backup/notify-failure.sh"
    "$REPOSITORY_ROOT/deploy/restore/adapters/acquire-backup-set.sh"
    "$REPOSITORY_ROOT/deploy/restore/adapters/verify-release-bundle.sh"
    "$REPOSITORY_ROOT/deploy/restore/adapters/report-transfer.sh"
    "$REPOSITORY_ROOT/deploy/restore/verify-restored-media.sh"
    "$REPOSITORY_ROOT/deploy/scripts/preflight.sh"
    "$REPOSITORY_ROOT/deploy/scripts/render-nginx.sh"
    "$REPOSITORY_ROOT/deploy/scripts/install-cos-staging-lifecycle.sh"
    "$REPOSITORY_ROOT/deploy/scripts/verify-cos-staging-lifecycle.sh"
    "$SMOKE"
  )
  for script in "${scripts[@]}"; do
    grep -Fx 'trap cleanup EXIT' "$script" >/dev/null ||
      fail "$(basename "$script") does not reserve cleanup for EXIT"
    grep -Fx "trap 'on_signal 129' HUP" "$script" >/dev/null ||
      fail "$(basename "$script") does not exit 129 on HUP"
    grep -Fx "trap 'on_signal 130' INT" "$script" >/dev/null ||
      fail "$(basename "$script") does not exit 130 on INT"
    grep -Fx "trap 'on_signal 143' TERM" "$script" >/dev/null ||
      fail "$(basename "$script") does not exit 143 on TERM"
    grep -F "local status=\"\$1\"" "$script" >/dev/null ||
      fail "$(basename "$script") signal handler does not receive the exact exit status"
    grep -F 'trap - HUP INT TERM' "$script" >/dev/null ||
      fail "$(basename "$script") signal handler can recursively re-enter during EXIT cleanup"
    if grep -E '^trap cleanup .*(HUP|INT|TERM)' "$script" >/dev/null; then
      fail "$(basename "$script") can swallow a termination signal after cleanup"
    fi
  done

  for script in \
    "$BUILD_RELEASE" \
    "$REPOSITORY_ROOT/deploy/scripts/package-release.sh" \
    "$REPOSITORY_ROOT/deploy/scripts/deploy-release.sh" \
    "$REPOSITORY_ROOT/deploy/scripts/install-release-bundle.sh" \
    "$REPOSITORY_ROOT/deploy/scripts/package-bootstrap-kit.sh" \
    "$REPOSITORY_ROOT/deploy/scripts/promote-release-upload.sh" \
    "$REPOSITORY_ROOT/deploy/scripts/rollback-release.sh" \
    "$RELEASE_ARTIFACT_CONTRACT"; do
    if ! grep -Eq "^[[:space:]]*trap (cleanup|on_exit) EXIT$" "$script" &&
        ! grep -F "trap 'on_exit \$?' EXIT" "$script" >/dev/null; then
      fail "$(basename "$script") does not reserve recovery or cleanup for EXIT"
    fi
    grep -Eq "^[[:space:]]*trap 'exit 129' HUP$" "$script" ||
      fail "$(basename "$script") can strand build state after SSH HUP"
    grep -Eq "^[[:space:]]*trap 'exit 130' INT$" "$script" ||
      fail "$(basename "$script") does not preserve the INT exit status"
    grep -Eq "^[[:space:]]*trap 'exit 143' TERM$" "$script" ||
      fail "$(basename "$script") does not preserve the TERM exit status"
    if grep -F "trap 'exit 130' HUP INT TERM" "$script" >/dev/null; then
      fail "$(basename "$script") still collapses production signals to status 130"
    fi
  done

  script="$REPOSITORY_ROOT/deploy/backup/backup-set.sh"
  grep -Fx 'trap on_exit EXIT' "$script" >/dev/null ||
    fail 'backup-set.sh does not reserve failure evidence and cleanup for EXIT'
  grep -F "local status=\"\$1\"" "$script" >/dev/null ||
    fail 'backup-set.sh signal handler does not receive the exact exit status'
  grep -F 'trap - HUP INT TERM' "$script" >/dev/null ||
    fail 'backup-set.sh signal handler can recursively re-enter'
  grep -F 'FAILURE_CATEGORY=INTERNAL_FAILED' "$script" >/dev/null ||
    fail 'backup-set.sh signal handler no longer preserves its failure category'
  grep -Fx "trap 'on_signal 129' HUP" "$script" >/dev/null ||
    fail 'backup-set.sh does not exit 129 on HUP'
  grep -Fx "trap 'on_signal 130' INT" "$script" >/dev/null ||
    fail 'backup-set.sh does not exit 130 on INT'
  grep -Fx "trap 'on_signal 143' TERM" "$script" >/dev/null ||
    fail 'backup-set.sh does not exit 143 on TERM'

  mkdir -p "$fixture/bin"
  cat >"$fixture/bin/curl" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
: >"${PORTFOLIO_TEST_SIGNAL_REACHED:?}"
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
while :; do sleep 1; done
STUB
  chmod 0755 "$fixture/bin/curl"
  python3 - "$SMOKE" "$fixture/bin" "$fixture" <<'PY'
import os
from pathlib import Path
import signal
import subprocess
import sys
import time

smoke, fixture_bin, fixture_root = sys.argv[1:]
for signal_name, signal_value, expected in (
    ("HUP", signal.SIGHUP, 129),
    ("INT", signal.SIGINT, 130),
    ("TERM", signal.SIGTERM, 143),
):
    case_root = Path(fixture_root, signal_name.lower())
    tmp = case_root / "tmp"
    tmp.mkdir(parents=True)
    reached = case_root / "reached"
    environment = os.environ.copy()
    environment.update(
        PATH=f"{fixture_bin}:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        TMPDIR=str(tmp),
        PORTFOLIO_TEST_SIGNAL_REACHED=str(reached),
    )
    with (case_root / "smoke.out").open("wb") as stdout, \
         (case_root / "smoke.err").open("wb") as stderr:
        process = subprocess.Popen(
            ["bash", smoke, "api-local", "--base-url", "http://127.0.0.1:18080"],
            env=environment,
            stdout=stdout,
            stderr=stderr,
            start_new_session=True,
        )
        deadline = time.monotonic() + 5
        while not reached.exists() and process.poll() is None and time.monotonic() < deadline:
            time.sleep(0.05)
        if not reached.exists():
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()
            raise SystemExit(f"smoke {signal_name} fixture did not reach its blocking request")
        os.killpg(process.pid, signal_value)
        try:
            status = process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()
            raise SystemExit(f"smoke {signal_name} did not terminate")
    if status != expected:
        raise SystemExit(
            f"smoke {signal_name} exited with status {status} instead of {expected}"
        )
    if any(tmp.iterdir()):
        raise SystemExit(
            f"smoke {signal_name} did not remove its private work directory through EXIT cleanup"
        )
PY
}

test_release_runtime_closure_contract() {
  local fixture="$WORK_DIRECTORY/release-runtime-closure" base case_root key index=0
  base="$fixture/base"
  mkdir -p \
    "$fixture/bin" \
    "$base/admin/assets" "$base/public-assets/.vite" "$base/public-assets/assets" \
    "$base/images" "$base/ops/docs" \
    "$base/compliance/licenses/frontend" "$base/compliance/licenses/admin-web" \
    "$base/compliance/licenses/backend" "$base/compliance/licenses/cos-prune-runtime" \
    "$base/compliance/licenses/api-image" "$base/compliance/licenses/postgres-image" \
    "$base/compliance/sbom" "$base/compliance/oci"
  printf '#!/usr/bin/env bash\nexit 99\n' >"$fixture/bin/docker"
  chmod 0700 "$fixture/bin/docker"
  printf '<!doctype html>\n' >"$base/admin/index.html"
  printf '{}\n' >"$base/public-assets/.vite/manifest.json"
  printf '<svg xmlns="http://www.w3.org/2000/svg"/>\n' >"$base/public-assets/favicon.svg"
  for path in \
    SHA256SUMS THIRD_PARTY_NOTICES.txt ASSET_PROVENANCE.md asset-provenance.json \
    licenses/frontend/manifest.json licenses/admin-web/manifest.json licenses/backend/manifest.json \
    licenses/cos-prune-runtime/manifest.json licenses/api-image/manifest.json \
    licenses/postgres-image/manifest.json sbom/frontend.cdx.json sbom/admin-web.cdx.json \
    sbom/backend.cdx.json sbom/api-image.cdx.json sbom/postgres-image.cdx.json \
    oci/api-image-metadata.json oci/postgres-image-metadata.json; do
    printf '{}\n' >"$base/compliance/$path"
  done
  printf '{}\n' >"$base/release.json"
  printf '\n' >"$base/bundle-manifest.json"
  printf 'image fixture\n' >"$base/images/portfolio-api.oci.tar.zst"
  printf 'image fixture\n' >"$base/images/postgres-17.oci.tar.zst"
  cp -a -- "$REPOSITORY_ROOT/deploy" "$base/ops/deploy"
  cp -a -- "$REPOSITORY_ROOT/docs/operations" "$base/ops/docs/operations"
  find "$base/ops" -type d -name __pycache__ -prune -exec rm -rf -- {} +
  find "$base/ops" "$base/images" "$base/compliance" -type d -exec chmod 0700 {} +
  find "$base/ops" "$base/images" "$base/compliance" -type f -exec chmod 0600 {} +
  find "$base/ops" -type f -name '*.sh' -exec chmod 0700 {} +
  chmod 0600 \
    "$base/ops/deploy/image-lock.env" \
    "$base/ops/deploy/scripts/image-lock-lib.sh" \
    "$base/ops/deploy/scripts/switch-journal.sh"
  chmod 0700 \
    "$base/ops/deploy/backup/validate-media-tar.py" \
    "$base/ops/deploy/scripts/install-bootstrap-kit.py" \
    "$base/ops/deploy/scripts/validate-bundle-tar.py"

  local -a key_files=(
    ops/deploy/image-lock.env
    ops/deploy/scripts/image-lock-lib.sh
    ops/deploy/scripts/preflight.sh
    ops/deploy/scripts/prune-releases.sh
    ops/deploy/scripts/switch-journal.sh
    ops/deploy/restore/record-drill-result.sql
    ops/deploy/restore/resolve-media-closure.sql
    ops/deploy/backup/backup-database.sh
    ops/deploy/backup/backup-dispatch.sh
    ops/deploy/backup/backup-media.sh
    ops/deploy/backup/backup-set.sh
    ops/deploy/backup/cos-prune-guard.py
    ops/deploy/backup/export-media.sql
    ops/deploy/backup/install-cos-prune-runtime.sh
    ops/deploy/backup/lib.sh
    ops/deploy/backup/notify-failure.sh
    ops/deploy/backup/postgres-client.sh
    ops/deploy/backup/prune-guard.example.sh
    ops/deploy/backup/prune-remote.sh
    ops/deploy/backup/record-maintenance.sql
    ops/deploy/backup/requirements-cos-prune.in
    ops/deploy/backup/requirements-cos-prune.txt
    ops/deploy/backup/validate-media-tar.py
    ops/deploy/backup/verify-artifact.sh
    ops/deploy/systemd/portfolio-backup-prune-readiness.service
  )
  for key in "${key_files[@]}"; do
    index=$((index + 1))
    case_root="$fixture/missing-$index"
    cp -a -- "$base" "$case_root"
    rm -f -- "$case_root/$key"
    if env PATH="$fixture/bin:/usr/bin:/bin" \
        bash "$RELEASE_ARTIFACT_CONTRACT" "$case_root" "$RELEASE_ID" \
        >"$fixture/missing-$index.out" 2>"$fixture/missing-$index.err"; then
      fail "release artifact contract accepted missing runtime closure file: $key"
    fi
    grep -F "required file missing: $key" "$fixture/missing-$index.err" >/dev/null || {
      sed -n '1,80p' "$fixture/missing-$index.err" >&2
      fail "missing runtime closure file failed for an unexpected reason: $key"
    }
  done

  case_root="$fixture/executable-mode"
  cp -a -- "$base" "$case_root"
  chmod 0600 "$case_root/ops/deploy/scripts/preflight.sh"
  if env PATH="$fixture/bin:/usr/bin:/bin" \
      bash "$RELEASE_ARTIFACT_CONTRACT" "$case_root" "$RELEASE_ID" \
      >"$fixture/executable-mode.out" 2>"$fixture/executable-mode.err"; then
    fail 'release artifact contract accepted a non-executable preflight helper'
  fi
  grep -F 'release executable mode is not 0700: ops/deploy/scripts/preflight.sh' \
    "$fixture/executable-mode.err" >/dev/null ||
    fail 'preflight mode rejection failed for an unexpected reason'

  case_root="$fixture/private-mode"
  cp -a -- "$base" "$case_root"
  chmod 0700 "$case_root/ops/deploy/backup/cos-prune-guard.py"
  if env PATH="$fixture/bin:/usr/bin:/bin" \
      bash "$RELEASE_ARTIFACT_CONTRACT" "$case_root" "$RELEASE_ID" \
      >"$fixture/private-mode.out" 2>"$fixture/private-mode.err"; then
    fail 'release artifact contract accepted executable COS guard implementation data'
  fi
  grep -F 'release private operations file mode is not 0600: ops/deploy/backup/cos-prune-guard.py' \
    "$fixture/private-mode.err" >/dev/null ||
    fail 'COS guard private mode rejection failed for an unexpected reason'

  case_root="$fixture/switch-journal-private-mode"
  cp -a -- "$base" "$case_root"
  chmod 0700 "$case_root/ops/deploy/scripts/switch-journal.sh"
  if env PATH="$fixture/bin:/usr/bin:/bin" \
      bash "$RELEASE_ARTIFACT_CONTRACT" "$case_root" "$RELEASE_ID" \
      >"$fixture/switch-journal-mode.out" 2>"$fixture/switch-journal-mode.err"; then
    fail 'release artifact contract accepted an executable source-only switch journal helper'
  fi
  grep -F 'release private operations file mode is not 0600: ops/deploy/scripts/switch-journal.sh' \
    "$fixture/switch-journal-mode.err" >/dev/null ||
    fail 'source-only switch journal mode rejection failed for an unexpected reason'
}

test_backup_local_volume_read_capability() {
  grep -Fx 'CapabilityBoundingSet=CAP_DAC_READ_SEARCH' "$BACKUP_SERVICE_UNIT" >/dev/null ||
    fail 'backup service capability bounding set is not read/search-only'
  grep -Fx 'AmbientCapabilities=CAP_DAC_READ_SEARCH' "$BACKUP_SERVICE_UNIT" >/dev/null ||
    fail 'backup service does not make CAP_DAC_READ_SEARCH effective for ExecStart'
  [[ "$(grep -Ec '^(CapabilityBoundingSet|AmbientCapabilities)=' "$BACKUP_SERVICE_UNIT")" == 2 &&
     "$(grep -Ec 'CAP_[A-Z_]+' "$BACKUP_SERVICE_UNIT")" == 2 ]] ||
    fail 'backup service contains an unexpected additional capability directive'
  grep -Fx 'CapabilityBoundingSet=' "$BACKUP_PRUNE_SERVICE_UNIT" >/dev/null ||
    fail 'backup prune service capability bounding set is not empty'
  grep -Fx 'AmbientCapabilities=' "$BACKUP_PRUNE_SERVICE_UNIT" >/dev/null ||
    fail 'backup prune service ambient capability set is not empty'

  # Docker does not grant DAC_READ_SEARCH by default. When the contract runner
  # supplies --cap-add DAC_READ_SEARCH, prove the exact effective capability
  # can read the 10001-owned Local volume but cannot mutate it.
  if ! capsh --has-p=cap_dac_read_search >/dev/null 2>&1; then
    printf '%s\n' 'SKIP: runtime Local backup capability proof requires --cap-add DAC_READ_SEARCH' >&2
    return
  fi
  local fixture="$WORK_DIRECTORY/backup-local-capability" local_root protected extra
  local_root="$fixture/local-media"
  protected="$local_root/original.bin"
  extra="$local_root/new.bin"
  mkdir -p "$local_root"
  printf 'read-only-capability-proof\n' >"$protected"
  chown -R 10001:10001 "$local_root"
  chmod 0700 "$local_root"
  chmod 0600 "$protected"
  # Positional paths are intentionally expanded only by the child shell.
  # shellcheck disable=SC2016
  setpriv --reuid=0 --regid=0 --clear-groups \
    --bounding-set=-all,+dac_read_search \
    --inh-caps=-all,+dac_read_search \
    --ambient-caps=-all,+dac_read_search \
    bash -c '
      set -euo pipefail
      [[ "$(awk "/^CapEff:/ {print \$2}" /proc/self/status)" == 0000000000000004 ]]
      [[ "$(cat -- "$1")" == read-only-capability-proof ]]
      ! printf "mutated\n" >"$1" 2>/dev/null
      ! rm -f -- "$1" 2>/dev/null
      ! printf "created\n" >"$2" 2>/dev/null
    ' bash "$protected" "$extra" ||
    fail 'CAP_DAC_READ_SEARCH did not provide exact read-only Local volume access'
  [[ "$(cat -- "$protected")" == read-only-capability-proof && ! -e "$extra" ]] ||
    fail 'read-only backup capability proof mutated Local media bytes'
}

test_real_smoke_with_proxy_pollution() {
  local bin="$WORK_DIRECTORY/real-smoke-bin" log="$WORK_DIRECTORY/real-smoke-curl.log"
  mkdir -p "$bin"
  cat >"$bin/curl" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
for variable in HTTP_PROXY HTTPS_PROXY ALL_PROXY http_proxy https_proxy all_proxy NO_PROXY no_proxy; do
  [[ -z "${!variable+x}" ]] || {
    printf 'proxy variable leaked to curl: %s\n' "$variable" >&2
    exit 90
  }
done
[[ "${1:-}" == --disable ]] || exit 91
printf '%s\n' "$*" >>"$PORTFOLIO_TEST_REAL_CURL_LOG"
arguments="$*"
output='' headers='' write_out='' url="${*: -1}" no_proxy=false
while (($#)); do
  case "$1" in
    --output) output="$2"; shift 2 ;;
    --dump-header) headers="$2"; shift 2 ;;
    --write-out) write_out="$2"; shift 2 ;;
    --noproxy)
      [[ "$2" == '*' ]] || exit 92
      no_proxy=true
      shift 2
      ;;
    *) shift ;;
  esac
done
[[ "$no_proxy" == true && -n "$output" && -n "$headers" ]] || exit 93
path="${url#https://yychainsaw.xyz:18443}"
status=200 content_type='text/plain' cache='public, no-cache' body=''
location='' etag=''
case "$path" in
  '/api/public/site?locale=zh-CN')
    if [[ "$arguments" == *'If-None-Match:'* ]]; then
      status=304
    else
      content_type='application/json'
      etag='"contract"'
      body="{\"checksum\":\"$(printf 'a%.0s' {1..64})\",\"data\":{}}"
    fi
    ;;
  '/api/public/projects?locale=en')
    mode="${PORTFOLIO_TEST_PROJECT_ETAG_MODE:-strong}"
    if [[ "$arguments" == *'If-None-Match:'* ]]; then
      status=304
      [[ "$mode" != non304 ]] || status=200
      [[ "$mode" != body304 ]] || body='forbidden-304-body'
    else
      content_type='application/json'
      body="{\"checksum\":\"$(printf 'b%.0s' {1..64})\",\"data\":[]}"
      case "$mode" in
        missing) etag='' ;;
        weak) etag='W/"projects"' ;;
        *) etag='"projects"' ;;
      esac
    fi
    ;;
  '/api/not-a-route') status=404; content_type='application/json'; body='{"code":"NOT_FOUND"}' ;;
  '/api/admin/auth/me') status=401; content_type='application/json'; cache='no-store'; body='{"code":"UNAUTHORIZED"}' ;;
  '/') status=302; location='/zh-CN' ;;
  '/zh-CN') content_type='text/html'; body='<html lang="zh-CN"><head></head></html>' ;;
  '/en') content_type='text/html'; body='<html lang="en"><head></head></html>' ;;
  '/zh-CN/privacy')
    content_type='text/html'
    body='<html><head><link rel="canonical"><link hreflang="zh-CN"></head></html>'
    ;;
  '/sitemap.xml') content_type='application/xml'; body='<urlset><url>/zh-CN</url></urlset>' ;;
  '/robots.txt') content_type='text/plain'; body=$'User-agent: *\nAllow: /\n' ;;
  '/actuator/health/readiness') status=404; content_type='text/plain'; body='not found' ;;
  '/admin/') content_type='text/html'; cache='no-store'; body='<!doctype html><html></html>' ;;
  '/assets/app.js')
    content_type='application/javascript'
    cache='public, max-age=31536000, immutable'
    body=$'real-asset\n'
    ;;
  *) printf 'unexpected real-smoke URL: %s\n' "$url" >&2; exit 94 ;;
esac
{
  printf 'HTTP/1.1 %s Contract\r\n' "$status"
  printf 'Content-Type: %s\r\nCache-Control: %s\r\n' "$content_type" "$cache"
  [[ -z "$location" ]] || printf 'Location: %s\r\n' "$location"
  [[ -z "$etag" ]] || printf 'ETag: %s\r\n' "$etag"
  printf 'X-Content-Type-Options: nosniff\r\n'
  printf 'Referrer-Policy: strict-origin-when-cross-origin\r\n'
  printf 'X-Frame-Options: DENY\r\n'
  printf 'Permissions-Policy: camera=(), microphone=(), geolocation=(), payment=(), usb=()\r\n'
  printf 'Strict-Transport-Security: max-age=31536000; includeSubDomains\r\n'
  printf "Content-Security-Policy: default-src 'self'; object-src 'none'; frame-ancestors 'none'; upgrade-insecure-requests\r\n\r\n"
} >"$headers"
printf '%s' "$body" >"$output"
case "$write_out" in
  '%{http_code}') printf '%s' "$status" ;;
  *) printf '%s' "$status" ;;
esac
STUB
  chmod 0755 "$bin/curl"
  local asset_sha
  asset_sha="$(printf 'real-asset\n' | sha256sum | awk '{print $1}')"
  local -a real_smoke_environment=(
    PATH="$bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
    PORTFOLIO_TEST_REAL_CURL_LOG="$log" \
    PORTFOLIO_SMOKE_ASSET_PATH=assets/app.js \
    PORTFOLIO_SMOKE_ASSET_SHA256="$asset_sha" \
    HTTP_PROXY=http://127.0.0.1:9 HTTPS_PROXY=http://127.0.0.1:9 \
    ALL_PROXY=http://127.0.0.1:9 http_proxy=http://127.0.0.1:9 \
    https_proxy=http://127.0.0.1:9 all_proxy=http://127.0.0.1:9 \
    NO_PROXY=example.invalid no_proxy=example.invalid
  )
  env "${real_smoke_environment[@]}" \
    bash "$SMOKE" nginx-local \
      --resolve yychainsaw.xyz:18443:127.0.0.1 \
      >"$WORK_DIRECTORY/real-smoke.out" 2>"$WORK_DIRECTORY/real-smoke.err" || {
        sed -n '1,160p' "$WORK_DIRECTORY/real-smoke.err" >&2
        fail 'real smoke matrix failed under proxy-polluted environment'
      }
  grep -F 'PASS: nginx-local smoke matrix' "$WORK_DIRECTORY/real-smoke.out" >/dev/null ||
    fail 'real smoke matrix did not report success'
  [[ "$(wc -l <"$log" | tr -d '[:space:]')" -ge 14 ]] ||
    fail 'real smoke curl fixture did not exercise the full edge matrix'

  local mode expected
  for mode in missing weak non304 body304; do
    case "$mode" in
      missing|weak) expected='public projects did not return a strong quoted ETag' ;;
      non304) expected='public-projects-not-modified returned HTTP 200 instead of 304' ;;
      body304) expected='projects ETag 304 response unexpectedly had a body' ;;
    esac
    if env "${real_smoke_environment[@]}" PORTFOLIO_TEST_PROJECT_ETAG_MODE="$mode" \
        bash "$SMOKE" nginx-local --resolve yychainsaw.xyz:18443:127.0.0.1 \
        >"$WORK_DIRECTORY/projects-$mode.out" 2>"$WORK_DIRECTORY/projects-$mode.err"; then
      fail "projects $mode ETag fixture unexpectedly passed smoke"
    fi
    grep -F "$expected" "$WORK_DIRECTORY/projects-$mode.err" >/dev/null || {
      sed -n '1,120p' "$WORK_DIRECTORY/projects-$mode.err" >&2
      fail "projects $mode ETag fixture failed for an unexpected reason"
    }
  done
}

main() {
  local suite="${1:-all}"
  (($# <= 1)) || fail 'usage: deploy-state-machine.sh [--deploy|--rollback|--journal|--backup-units]'
  case "$suite" in
    all|--deploy|--rollback|--journal|--backup-units) ;;
    *) fail 'usage: deploy-state-machine.sh [--deploy|--rollback|--journal|--backup-units]' ;;
  esac
  for command_name in \
    awk bash capsh chown cmp cp cut find flock grep id jq python3 realpath sed setpriv setsid sha256sum shellcheck sleep sort stat systemd-tmpfiles tar zstd
  do
    command -v "$command_name" >/dev/null 2>&1 || fail "$command_name is required"
  done
  WORK_DIRECTORY="$(mktemp -d)"
  PORTFOLIO_CONTRACT_TEST_MODE=portfolio-state-machine-v1
  PORTFOLIO_CONTRACT_TEST_ROOT="$WORK_DIRECTORY"
  export PORTFOLIO_CONTRACT_TEST_MODE PORTFOLIO_CONTRACT_TEST_ROOT
  if [[ "$suite" == --deploy ]]; then
    test_deploy_release_state_machine
    test_maven_verify_runner_contract
    test_crash_failpoint_scope_contract
    test_production_signal_cleanup_contract
    printf '%s\n' 'PASS: deploy state-machine and durable switch-journal contracts'
    return
  fi
  if [[ "$suite" == --rollback ]]; then
    test_rollback_state_machine
    printf '%s\n' 'PASS: rollback state-machine and durable switch-journal contracts'
    return
  fi
  if [[ "$suite" == --journal ]]; then
    test_rollback_state_machine
    test_deploy_release_state_machine
    printf '%s\n' 'PASS: deploy and rollback durable switch-journal contracts'
    return
  fi
  if [[ "$suite" == --backup-units ]]; then
    test_backup_unit_installation
    printf '%s\n' 'PASS: fail-closed backup unit installation and prune readiness gate'
    return
  fi
  test_tar_validator
  test_private_mode_round_trip_without_docker
  test_bootstrap_host_prerequisite_contract
  test_safe_pruning
  test_local_volume_provisioning
  test_rollback_state_machine
  test_offline_bundle_installer
  test_installer_lock_wait_replacement
  test_bootstrap_kit_contract
  test_upload_quarantine_promotion
  test_backup_unit_installation
  test_deploy_release_state_machine
  test_release_source_hygiene_contract
  test_maven_verify_runner_contract
  test_crash_failpoint_scope_contract
  test_production_signal_cleanup_contract
  test_release_runtime_closure_contract
  test_backup_local_volume_read_capability
  test_edge_smoke_source_contract
  test_real_smoke_with_proxy_pollution
  printf '%s\n' \
    'PASS: tar, install, deploy, provider-gate, rollback, and pruning state-machine contracts'
}

main "$@"
