#!/usr/bin/env python3
"""Validate and atomically install the independently trusted bootstrap kit."""

from __future__ import annotations

import argparse
import ctypes
import errno
import fcntl
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile


FIXED_INSTALL_ROOT = Path("/usr/local/libexec/portfolio")
DEFAULT_LOCK_DIRECTORY = Path("/run/lock/portfolio")
TMPFILES_CONFIGURATION = Path("/etc/tmpfiles.d/portfolio.conf")
TMPFILES_BODY = b"# Runtime lock namespace. /run is ephemeral and this directory must be recreated on every boot.\nd /run/lock/portfolio 0700 root root -\n"
FILE_MODES = {
    "libexec/portfolio/install-bootstrap-kit.py": 0o644,
    "libexec/portfolio/install-backup-units.sh": 0o755,
    "libexec/portfolio/install-release-bundle.sh": 0o755,
    "libexec/portfolio/promote-release-upload.sh": 0o755,
    "libexec/portfolio/provision-local-volume.sh": 0o755,
    "libexec/portfolio/validate-bundle-tar.py": 0o644,
    "libexec/portfolio/verify-compliance-tree.py": 0o644,
}
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
COMMIT_RE = re.compile(r"^[0-9a-f]{40}$")
RENAME_NOREPLACE = 1
AT_FDCWD = -100


class BootstrapError(RuntimeError):
    pass


def fail(message: str) -> None:
    raise BootstrapError(message)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def require_root_regular(path: Path, label: str) -> None:
    if not path.is_absolute():
        fail(f"{label} path is not absolute")
    try:
        metadata = path.lstat()
    except FileNotFoundError:
        fail(f"{label} is missing")
    if not stat.S_ISREG(metadata.st_mode) or metadata.st_uid != 0:
        fail(f"{label} is not a root-owned regular file")
    if metadata.st_nlink != 1 or stat.S_IMODE(metadata.st_mode) not in (0o600, 0o640):
        fail(f"{label} mode or link count is unsafe")
    parent = path.parent
    parent_metadata = parent.lstat()
    if not stat.S_ISDIR(parent_metadata.st_mode) or parent_metadata.st_uid != 0:
        fail(f"{label} parent is not a root-owned directory")
    if stat.S_IMODE(parent_metadata.st_mode) & 0o022:
        fail(f"{label} parent is writable outside root")
    if parent.resolve(strict=True) != parent:
        fail(f"{label} parent is linked or non-canonical")


def acquire_lock() -> int:
    lock_path = Path(os.environ.get("PORTFOLIO_DEPLOY_LOCK_FILE", "/run/lock/portfolio/deploy.lock"))
    if not lock_path.is_absolute() or lock_path == Path("/"):
        fail("deploy lock path is invalid")
    parent = lock_path.parent
    parent_metadata = parent.lstat()
    expected_parent_mode = 0o700 if lock_path == DEFAULT_LOCK_DIRECTORY / "deploy.lock" else None
    if (
        not stat.S_ISDIR(parent_metadata.st_mode)
        or parent_metadata.st_uid != 0
        or parent_metadata.st_gid != 0
        or parent.resolve(strict=True) != parent
        or (
            stat.S_IMODE(parent_metadata.st_mode) != expected_parent_mode
            if expected_parent_mode is not None
            else bool(stat.S_IMODE(parent_metadata.st_mode) & 0o022)
        )
    ):
        fail("deploy lock parent is unsafe")
    flags = os.O_RDWR | os.O_CREAT | getattr(os, "O_NOFOLLOW", 0)
    descriptor = os.open(lock_path, flags, 0o600)
    descriptor_metadata = os.fstat(descriptor)
    path_metadata = lock_path.lstat()
    if (
        not stat.S_ISREG(descriptor_metadata.st_mode)
        or descriptor_metadata.st_uid != 0
        or descriptor_metadata.st_gid != 0
        or descriptor_metadata.st_nlink != 1
        or not stat.S_ISREG(path_metadata.st_mode)
        or path_metadata.st_uid != 0
        or path_metadata.st_gid != 0
        or path_metadata.st_nlink != 1
        or stat.S_IMODE(descriptor_metadata.st_mode) & 0o022
        or (descriptor_metadata.st_dev, descriptor_metadata.st_ino)
        != (path_metadata.st_dev, path_metadata.st_ino)
    ):
        os.close(descriptor)
        fail("deploy lock is unsafe or changed identity")
    fcntl.flock(descriptor, fcntl.LOCK_EX)
    try:
        parent_metadata = parent.lstat()
        resolved_parent = parent.resolve(strict=True)
        path_metadata = lock_path.lstat()
        descriptor_metadata = os.fstat(descriptor)
    except (FileNotFoundError, OSError):
        os.close(descriptor)
        fail("deploy lock changed while waiting")
    if (
        not stat.S_ISDIR(parent_metadata.st_mode)
        or parent_metadata.st_uid != 0
        or parent_metadata.st_gid != 0
        or resolved_parent != parent
        or (
            stat.S_IMODE(parent_metadata.st_mode) != expected_parent_mode
            if expected_parent_mode is not None
            else bool(stat.S_IMODE(parent_metadata.st_mode) & 0o022)
        )
        or not stat.S_ISREG(path_metadata.st_mode)
        or path_metadata.st_uid != 0
        or path_metadata.st_gid != 0
        or path_metadata.st_nlink != 1
        or stat.S_IMODE(path_metadata.st_mode) & 0o022
        or not stat.S_ISREG(descriptor_metadata.st_mode)
        or descriptor_metadata.st_uid != 0
        or descriptor_metadata.st_gid != 0
        or descriptor_metadata.st_nlink != 1
        or (descriptor_metadata.st_dev, descriptor_metadata.st_ino)
        != (path_metadata.st_dev, path_metadata.st_ino)
    ):
        os.close(descriptor)
        fail("deploy lock changed while waiting")
    return descriptor


def ensure_default_lock_directory() -> None:
    try:
        os.mkdir(DEFAULT_LOCK_DIRECTORY, 0o700)
    except FileExistsError:
        pass
    metadata = DEFAULT_LOCK_DIRECTORY.lstat()
    if (
        not stat.S_ISDIR(metadata.st_mode)
        or metadata.st_uid != 0
        or metadata.st_gid != 0
        or stat.S_IMODE(metadata.st_mode) != 0o700
        or DEFAULT_LOCK_DIRECTORY.resolve(strict=True) != DEFAULT_LOCK_DIRECTORY
    ):
        fail("dedicated runtime lock directory is unsafe")


def install_tmpfiles_configuration() -> None:
    parent = TMPFILES_CONFIGURATION.parent
    if not parent.exists():
        os.mkdir(parent, 0o755)
    parent_metadata = parent.lstat()
    if (
        not stat.S_ISDIR(parent_metadata.st_mode)
        or parent_metadata.st_uid != 0
        or parent.resolve(strict=True) != parent
        or stat.S_IMODE(parent_metadata.st_mode) & 0o022
    ):
        fail("tmpfiles configuration parent is unsafe")
    if TMPFILES_CONFIGURATION.exists() or TMPFILES_CONFIGURATION.is_symlink():
        metadata = TMPFILES_CONFIGURATION.lstat()
        if (
            not stat.S_ISREG(metadata.st_mode)
            or metadata.st_uid != 0
            or metadata.st_gid != 0
            or metadata.st_nlink != 1
            or stat.S_IMODE(metadata.st_mode) != 0o644
            or TMPFILES_CONFIGURATION.read_bytes() != TMPFILES_BODY
        ):
            fail("existing tmpfiles configuration differs from the trusted contract")
        return
    descriptor, name = tempfile.mkstemp(prefix=".portfolio.conf.install.", dir=parent)
    candidate = Path(name)
    try:
        os.fchmod(descriptor, 0o644)
        with os.fdopen(descriptor, "wb", closefd=True) as output:
            output.write(TMPFILES_BODY)
            output.flush()
            os.fsync(output.fileno())
        rename_noreplace(candidate, TMPFILES_CONFIGURATION)
        parent_descriptor = os.open(parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(parent_descriptor)
        finally:
            os.close(parent_descriptor)
    finally:
        candidate.unlink(missing_ok=True)


def load_envelope(
    path: Path,
    commit: str,
    archive: Path,
    archive_sha256: str,
    archive_bytes: int,
    installer: Path,
) -> dict[str, object]:
    try:
        document = json.loads(path.read_bytes())
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        fail(f"bootstrap envelope is not valid UTF-8 JSON: {error}")
    exact_keys = {
        "archiveBytes",
        "archiveName",
        "archiveSha256",
        "bootstrapInstallerBytes",
        "bootstrapInstallerName",
        "bootstrapInstallerSha256",
        "gitCommit",
        "manifestSha256",
        "schemaVersion",
    }
    if not isinstance(document, dict) or set(document) != exact_keys:
        fail("bootstrap envelope keys are not exact")
    if (
        document["schemaVersion"] != 1
        or document["gitCommit"] != commit
        or document["archiveName"] != archive.name
        or document["archiveSha256"] != archive_sha256
        or document["archiveBytes"] != archive_bytes
        or document["bootstrapInstallerName"] != installer.name
        or document["bootstrapInstallerSha256"] != sha256_file(installer)
        or document["bootstrapInstallerBytes"] != installer.stat().st_size
        or not isinstance(document["manifestSha256"], str)
        or SHA256_RE.fullmatch(document["manifestSha256"]) is None
    ):
        fail("bootstrap envelope identity contract failed")
    return document


def materialize_tar(archive: Path) -> Path:
    zstd = Path(shutil.which("zstd") or "")
    if not zstd.is_absolute() or not zstd.exists():
        fail("zstd is unavailable")
    zstd_metadata = zstd.lstat()
    if (
        not stat.S_ISREG(zstd_metadata.st_mode)
        or zstd_metadata.st_uid != 0
        or stat.S_IMODE(zstd_metadata.st_mode) & 0o022
    ):
        fail("zstd executable is not trusted")
    descriptor, name = tempfile.mkstemp(prefix=".bootstrap-tar.", dir=archive.parent)
    os.fchmod(descriptor, 0o600)
    tar_path = Path(name)
    try:
        with os.fdopen(descriptor, "wb", closefd=True) as output:
            result = subprocess.run(
                [str(zstd), "-q", "-dc", "--", str(archive)],
                check=False,
                stdin=subprocess.DEVNULL,
                stdout=output,
                stderr=subprocess.DEVNULL,
            )
            output.flush()
            os.fsync(output.fileno())
        if result.returncode != 0:
            fail("bootstrap archive is not a valid zstd stream")
        return tar_path
    except BaseException:
        tar_path.unlink(missing_ok=True)
        raise


def validate_tar(tar_path: Path, commit: str, expected_manifest_sha: str) -> tuple[dict[str, object], dict[str, bytes]]:
    root = f"portfolio-bootstrap-{commit}"
    expected_directories = {root, f"{root}/libexec", f"{root}/libexec/portfolio"}
    expected_regular = {f"{root}/bootstrap-manifest.json"} | {
        f"{root}/{path}" for path in FILE_MODES
    }
    payloads: dict[str, bytes] = {}
    try:
        archive = tarfile.open(tar_path, mode="r:")
    except tarfile.TarError as error:
        fail(f"bootstrap tar is invalid: {error}")
    with archive:
        if archive.pax_headers:
            fail("global PAX headers are forbidden")
        members = archive.getmembers()
        names = [member.name.rstrip("/") for member in members]
        if len(names) != len(set(names)) or set(names) != expected_directories | expected_regular:
            fail("bootstrap tar entry set is not exact")
        by_name = {member.name.rstrip("/"): member for member in members}
        for member in members:
            normalized = member.name.rstrip("/")
            pure_path = PurePosixPath(normalized)
            if (
                not normalized
                or "\\" in normalized
                or pure_path.is_absolute()
                or any(part in ("", ".", "..") for part in pure_path.parts)
                or member.pax_headers
                or member.uid != 0
                or member.gid != 0
                or member.mtime != 0
            ):
                fail(f"unsafe or non-canonical tar metadata: {member.name}")
        for name in expected_directories:
            member = by_name[name]
            if not member.isdir() or member.mode != 0o755:
                fail(f"invalid bootstrap directory: {name}")
        for name in expected_regular:
            member = by_name[name]
            if not member.isreg():
                fail(f"non-regular bootstrap entry: {name}")
            extracted = archive.extractfile(member)
            if extracted is None:
                fail(f"unreadable bootstrap entry: {name}")
            payloads[name] = extracted.read()

    manifest_name = f"{root}/bootstrap-manifest.json"
    manifest_body = payloads[manifest_name]
    if hashlib.sha256(manifest_body).hexdigest() != expected_manifest_sha:
        fail("bootstrap manifest SHA-256 mismatch")
    try:
        manifest = json.loads(manifest_body)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        fail(f"bootstrap manifest is invalid: {error}")
    if not isinstance(manifest, dict) or set(manifest) != {
        "files", "gitCommit", "installRoot", "schemaVersion"
    }:
        fail("bootstrap manifest keys are not exact")
    if (
        manifest["schemaVersion"] != 1
        or manifest["gitCommit"] != commit
        or manifest["installRoot"] != str(FIXED_INSTALL_ROOT)
    ):
        fail("bootstrap manifest identity contract failed")
    files = manifest["files"]
    if not isinstance(files, list) or [item.get("path") for item in files if isinstance(item, dict)] != sorted(FILE_MODES):
        fail("bootstrap manifest file list is not exact and sorted")
    for item in files:
        if not isinstance(item, dict) or set(item) != {"mode", "path", "sha256"}:
            fail("bootstrap manifest file record is invalid")
        relative = item["path"]
        expected_mode = FILE_MODES.get(relative)
        expected_mode_text = f"{expected_mode:04o}" if expected_mode is not None else ""
        digest = item["sha256"]
        if item["mode"] != expected_mode_text or not isinstance(digest, str) or SHA256_RE.fullmatch(digest) is None:
            fail(f"bootstrap manifest metadata mismatch: {relative}")
        member = by_name[f"{root}/{relative}"]
        body = payloads[f"{root}/{relative}"]
        if member.mode != expected_mode or hashlib.sha256(body).hexdigest() != digest:
            fail(f"bootstrap payload mismatch: {relative}")
    return manifest, payloads


def rename_noreplace(source: Path, target: Path) -> None:
    libc = ctypes.CDLL(None, use_errno=True)
    renameat2 = getattr(libc, "renameat2", None)
    if renameat2 is None:
        fail("kernel/libc renameat2 support is required for create-only bootstrap install")
    result = renameat2(
        ctypes.c_int(AT_FDCWD), os.fsencode(source),
        ctypes.c_int(AT_FDCWD), os.fsencode(target), ctypes.c_uint(RENAME_NOREPLACE)
    )
    if result != 0:
        error_number = ctypes.get_errno()
        if error_number == errno.EEXIST:
            fail("stable bootstrap install root already exists; overwrite is forbidden")
        fail(f"atomic bootstrap install rename failed: {os.strerror(error_number)}")


def install_payloads(commit: str, payloads: dict[str, bytes], install_root: Path) -> None:
    parent = install_root.parent
    parent_metadata = parent.lstat()
    if (
        not install_root.is_absolute()
        or install_root == Path("/")
        or not stat.S_ISDIR(parent_metadata.st_mode)
        or parent_metadata.st_uid != 0
        or parent.resolve(strict=True) != parent
        or stat.S_IMODE(parent_metadata.st_mode) & 0o022
    ):
        fail("stable bootstrap install parent is unsafe")
    if install_root.exists() or install_root.is_symlink():
        fail("stable bootstrap install root already exists; overwrite is forbidden")
    candidate = Path(tempfile.mkdtemp(prefix=f".{install_root.name}.bootstrap-{commit}.", dir=parent))
    os.chmod(candidate, 0o755)
    try:
        archive_root = f"portfolio-bootstrap-{commit}"
        for relative, mode in FILE_MODES.items():
            destination = candidate / Path(relative).relative_to("libexec/portfolio")
            destination.parent.mkdir(mode=0o755, parents=True, exist_ok=True)
            descriptor = os.open(
                destination,
                os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0),
                mode,
            )
            with os.fdopen(descriptor, "wb", closefd=True) as output:
                output.write(payloads[f"{archive_root}/{relative}"])
                output.flush()
                os.fsync(output.fileno())
            os.chmod(destination, mode)
            if sha256_file(destination) != hashlib.sha256(payloads[f"{archive_root}/{relative}"]).hexdigest():
                fail(f"installed candidate digest mismatch: {relative}")
        for directory, _, _ in os.walk(candidate, topdown=False):
            descriptor = os.open(directory, os.O_RDONLY | os.O_DIRECTORY)
            try:
                os.fsync(descriptor)
            finally:
                os.close(descriptor)
        rename_noreplace(candidate, install_root)
        parent_descriptor = os.open(parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(parent_descriptor)
        finally:
            os.close(parent_descriptor)
    finally:
        if candidate.exists():
            shutil.rmtree(candidate)


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(allow_abbrev=False)
    parser.add_argument("--kit", required=True, type=Path)
    parser.add_argument("--envelope", required=True, type=Path)
    parser.add_argument("--expected-git-commit", required=True)
    parser.add_argument("--expected-kit-sha256", required=True)
    parser.add_argument("--expected-kit-bytes", required=True, type=int)
    parser.add_argument("--expected-installer-sha256", required=True)
    parser.add_argument("--expected-installer-bytes", required=True, type=int)
    parser.add_argument("--install-root", type=Path, default=FIXED_INSTALL_ROOT, help=argparse.SUPPRESS)
    return parser.parse_args()


def main() -> int:
    arguments = parse_arguments()
    if os.geteuid() != 0:
        fail("bootstrap installer must run as root")
    if COMMIT_RE.fullmatch(arguments.expected_git_commit) is None:
        fail("expected Git commit is invalid")
    if (
        SHA256_RE.fullmatch(arguments.expected_kit_sha256) is None
        or arguments.expected_kit_bytes <= 0
        or SHA256_RE.fullmatch(arguments.expected_installer_sha256) is None
        or arguments.expected_installer_bytes <= 0
    ):
        fail("expected kit/installer hash or byte count is invalid")
    install_root = arguments.install_root
    if install_root != FIXED_INSTALL_ROOT:
        if os.environ.get("PORTFOLIO_BOOTSTRAP_TEST_MODE") != "contract" or not str(install_root).startswith("/tmp/"):
            fail("production bootstrap install root is fixed")
    require_root_regular(arguments.kit, "bootstrap kit")
    require_root_regular(arguments.envelope, "bootstrap envelope")
    self_path = Path(__file__).resolve(strict=True)
    require_root_regular(self_path, "standalone bootstrap installer")
    if (
        self_path.stat().st_size != arguments.expected_installer_bytes
        or sha256_file(self_path) != arguments.expected_installer_sha256
    ):
        fail("standalone bootstrap installer differs from the independent byte/hash record")
    if arguments.kit.stat().st_size != arguments.expected_kit_bytes:
        fail("bootstrap kit differs from the independent byte record")
    if sha256_file(arguments.kit) != arguments.expected_kit_sha256:
        fail("bootstrap kit differs from the independent SHA-256 record")

    if os.environ.get("PORTFOLIO_DEPLOY_LOCK_FILE") is None:
        ensure_default_lock_directory()
    lock_descriptor = acquire_lock()
    tar_path: Path | None = None
    try:
        envelope = load_envelope(
            arguments.envelope,
            arguments.expected_git_commit,
            arguments.kit,
            arguments.expected_kit_sha256,
            arguments.expected_kit_bytes,
            self_path,
        )
        tar_path = materialize_tar(arguments.kit)
        _, payloads = validate_tar(
            tar_path, arguments.expected_git_commit, str(envelope["manifestSha256"])
        )
        if install_root.exists() or install_root.is_symlink():
            fail("stable bootstrap install root already exists; overwrite is forbidden")
        install_tmpfiles_configuration()
        install_payloads(arguments.expected_git_commit, payloads, install_root)
    finally:
        if tar_path is not None:
            tar_path.unlink(missing_ok=True)
        os.close(lock_descriptor)
    print(f"PASS: installed trusted bootstrap tools at {install_root}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except BootstrapError as error:
        print(f"portfolio bootstrap install failed: {error}", file=sys.stderr)
        raise SystemExit(1)
