#!/usr/bin/env python3
"""Validate and optionally extract an allowlisted Local-media POSIX ustar.

The allowlist is canonical JSON with this shape:

    {"schemaVersion":1,"entries":[
      {"path":"published/asset/original.webp","size":123,"sha256":"..."}
    ]}

Validation always reads and hashes every regular-file body.  Extraction, when
requested, happens only after complete validation and writes selected members
one by one beneath an initially empty destination without following links.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import stat
import sys
import tarfile
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import BinaryIO, Iterable


SHA256 = re.compile(r"[0-9a-f]{64}\Z")
SAFE_PATH = re.compile(r"[A-Za-z0-9._/-]+\Z")
MAX_ENTRIES = 1_000_000
COPY_BUFFER_SIZE = 1024 * 1024


class MediaTarError(RuntimeError):
    """Raised when the archive or its allowlist violates the safety contract."""


@dataclass(frozen=True)
class ExpectedEntry:
    path: str
    size: int
    sha256: str


class HashingReader:
    """Hash the exact bytes handed to tarfile while retaining a bounded API."""

    def __init__(self, source: BinaryIO) -> None:
        self.source = source
        self.digest = hashlib.sha256()
        self.count = 0

    def read(self, size: int = -1) -> bytes:
        chunk = self.source.read(size)
        self.digest.update(chunk)
        self.count += len(chunk)
        return chunk


def canonical_path(raw: object, label: str) -> str:
    if not isinstance(raw, str) or not raw or raw in {".", "./"}:
        raise MediaTarError(f"{label} path is empty or dot")
    if raw.startswith("/") or "\\" in raw:
        raise MediaTarError(f"{label} path is absolute or contains a backslash")
    if not SAFE_PATH.fullmatch(raw):
        raise MediaTarError(f"{label} path contains a non-allowlisted character")
    if "\x00" in raw or any(ord(character) < 32 or ord(character) == 127 for character in raw):
        raise MediaTarError(f"{label} path contains a control character")
    trimmed = raw[:-1] if raw.endswith("/") else raw
    components = trimmed.split("/")
    if any(component in {"", ".", ".."} for component in components):
        raise MediaTarError(f"{label} path has an empty, dot, or parent component")
    normalized = PurePosixPath(*components).as_posix()
    if normalized != trimmed:
        raise MediaTarError(f"{label} path is not canonical")
    return normalized


def load_json(path: Path, label: str) -> object:
    try:
        with path.open("r", encoding="utf-8") as source:
            return json.load(source)
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise MediaTarError(f"{label} is not valid UTF-8 JSON") from exc


def load_allowlist(path: Path) -> dict[str, ExpectedEntry]:
    document = load_json(path, "allowlist")
    if not isinstance(document, dict) or set(document) != {"schemaVersion", "entries"}:
        raise MediaTarError("allowlist must contain exactly schemaVersion and entries")
    if document["schemaVersion"] != 1 or not isinstance(document["entries"], list):
        raise MediaTarError("allowlist schema is unsupported")

    expected: dict[str, ExpectedEntry] = {}
    folded: set[str] = set()
    previous = ""
    for raw in document["entries"]:
        if not isinstance(raw, dict) or set(raw) != {"path", "size", "sha256"}:
            raise MediaTarError("allowlist entry has unknown or missing fields")
        path_value = canonical_path(raw["path"], "allowlist")
        size_value = raw["size"]
        digest_value = raw["sha256"]
        if isinstance(size_value, bool) or not isinstance(size_value, int) or size_value < 0:
            raise MediaTarError("allowlist entry size is invalid")
        if not isinstance(digest_value, str) or not SHA256.fullmatch(digest_value):
            raise MediaTarError("allowlist entry SHA-256 is invalid")
        if previous and path_value <= previous:
            raise MediaTarError("allowlist entries must be strictly bytewise sorted")
        previous = path_value
        casefolded = path_value.casefold()
        if path_value in expected or casefolded in folded:
            raise MediaTarError("allowlist contains a duplicate path")
        expected[path_value] = ExpectedEntry(path_value, size_value, digest_value)
        folded.add(casefolded)
    if len(expected) > MAX_ENTRIES:
        raise MediaTarError("allowlist contains too many entries")
    return expected


def expected_directories(entries: Iterable[str]) -> set[str]:
    directories: set[str] = set()
    for entry in entries:
        parent = PurePosixPath(entry).parent
        while parent.as_posix() != ".":
            directories.add(parent.as_posix())
            parent = parent.parent
    return directories


def open_source_member(root_descriptor: int, relative: PurePosixPath) -> int:
    descriptor = os.dup(root_descriptor)
    directory_flags = os.O_RDONLY | getattr(os, "O_DIRECTORY", 0)
    file_flags = os.O_RDONLY
    if hasattr(os, "O_NOFOLLOW"):
        directory_flags |= os.O_NOFOLLOW
        file_flags |= os.O_NOFOLLOW
    try:
        for component in relative.parts[:-1]:
            next_descriptor = os.open(component, directory_flags, dir_fd=descriptor)
            os.close(descriptor)
            descriptor = next_descriptor
        file_descriptor = os.open(relative.name, file_flags, dir_fd=descriptor)
        os.close(descriptor)
        return file_descriptor
    except Exception:
        os.close(descriptor)
        raise


def prepare_build_root(path: Path) -> Path:
    if not path.is_absolute():
        raise MediaTarError("build root must be absolute")
    try:
        metadata = path.lstat()
    except OSError as exc:
        raise MediaTarError("build root does not exist") from exc
    if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISDIR(metadata.st_mode):
        raise MediaTarError("build root is not a regular directory")
    return path.resolve(strict=True)


def prepare_build_output(path: Path) -> None:
    if not path.is_absolute() or path.exists() or path.is_symlink():
        raise MediaTarError("build output must be an absolute new path")
    try:
        parent_metadata = path.parent.lstat()
        resolved_parent = path.parent.resolve(strict=True)
    except OSError as exc:
        raise MediaTarError("build output parent does not exist") from exc
    if stat.S_ISLNK(parent_metadata.st_mode) or not stat.S_ISDIR(parent_metadata.st_mode):
        raise MediaTarError("build output parent is not a regular directory")
    if resolved_parent != path.parent:
        raise MediaTarError("build output parent contains a symbolic link")


def build_archive(
    root: Path,
    output: Path,
    expected: dict[str, ExpectedEntry],
) -> None:
    prepare_build_output(output)
    root_flags = os.O_RDONLY | getattr(os, "O_DIRECTORY", 0)
    if hasattr(os, "O_NOFOLLOW"):
        root_flags |= os.O_NOFOLLOW
    root_descriptor = os.open(root, root_flags)
    output_flags = os.O_RDWR | os.O_CREAT | os.O_EXCL
    if hasattr(os, "O_NOFOLLOW"):
        output_flags |= os.O_NOFOLLOW
    output_descriptor = -1
    try:
        output_descriptor = os.open(output, output_flags, 0o600)
        with os.fdopen(output_descriptor, "w+b", closefd=True) as target:
            output_descriptor = -1
            with tarfile.open(fileobj=target, mode="w", format=tarfile.USTAR_FORMAT) as archive:
                for path_value, wanted in expected.items():
                    relative = PurePosixPath(path_value)
                    try:
                        source_descriptor = open_source_member(root_descriptor, relative)
                    except OSError as exc:
                        raise MediaTarError("allowlisted Local source cannot be opened safely") from exc
                    with os.fdopen(source_descriptor, "rb", closefd=True) as source:
                        metadata = os.fstat(source.fileno())
                        if not stat.S_ISREG(metadata.st_mode) or metadata.st_size != wanted.size:
                            raise MediaTarError("Local source size does not match the allowlist")
                        member = tarfile.TarInfo(path_value)
                        member.size = wanted.size
                        member.mode = 0o600
                        member.uid = 0
                        member.gid = 0
                        member.uname = ""
                        member.gname = ""
                        member.mtime = 0
                        hashing_source = HashingReader(source)
                        archive.addfile(member, hashing_source)
                        if (
                            hashing_source.count != wanted.size
                            or hashing_source.digest.hexdigest() != wanted.sha256
                        ):
                            raise MediaTarError("Local source SHA-256 does not match the allowlist")
            target.flush()
            os.fsync(target.fileno())
            target.seek(0)
            validate(target, expected)
    except Exception:
        if output_descriptor >= 0:
            os.close(output_descriptor)
        try:
            output.unlink()
        except FileNotFoundError:
            pass
        raise
    finally:
        os.close(root_descriptor)


def reject_member_metadata(member: tarfile.TarInfo) -> None:
    if member.type in {tarfile.XHDTYPE, tarfile.XGLTYPE} or member.pax_headers:
        raise MediaTarError("PAX headers are forbidden")
    if member.type == tarfile.GNUTYPE_SPARSE or getattr(member, "sparse", None):
        raise MediaTarError("sparse archive entries are forbidden")
    if not (member.isfile() or member.isdir()):
        raise MediaTarError("archive contains a link or special entry")
    if member.size < 0 or (member.isdir() and member.size != 0):
        raise MediaTarError("archive member size is invalid")


def require_ustar_header(source: BinaryIO, member: tarfile.TarInfo) -> None:
    current = source.tell()
    try:
        source.seek(member.offset)
        header = source.read(tarfile.BLOCKSIZE)
    finally:
        source.seek(current)
    if len(header) != tarfile.BLOCKSIZE or header[257:263] not in {b"ustar\0", b"ustar "}:
        raise MediaTarError("archive member is not POSIX ustar")


def hash_member(archive: tarfile.TarFile, member: tarfile.TarInfo) -> tuple[int, str]:
    stream = archive.extractfile(member)
    if stream is None:
        raise MediaTarError("regular archive member has no readable body")
    digest = hashlib.sha256()
    total = 0
    with stream:
        while True:
            chunk = stream.read(COPY_BUFFER_SIZE)
            if not chunk:
                break
            total += len(chunk)
            digest.update(chunk)
    return total, digest.hexdigest()


def validate(source: BinaryIO, expected: dict[str, ExpectedEntry]) -> dict[str, tarfile.TarInfo]:
    source.seek(0)
    try:
        archive = tarfile.open(fileobj=source, mode="r:")
    except (tarfile.TarError, OSError) as exc:
        raise MediaTarError("archive is not a readable tar") from exc

    expected_dirs = expected_directories(expected)
    seen: set[str] = set()
    seen_casefolded: set[str] = set()
    regular_members: dict[str, tarfile.TarInfo] = {}
    regular_paths: set[str] = set()
    count = 0
    try:
        if archive.pax_headers:
            raise MediaTarError("global PAX headers are forbidden")
        for member in archive:
            count += 1
            if count > MAX_ENTRIES:
                raise MediaTarError("archive contains too many members")
            require_ustar_header(source, member)
            reject_member_metadata(member)
            path_value = canonical_path(member.name, "archive")
            folded = path_value.casefold()
            if path_value in seen:
                raise MediaTarError("archive contains a duplicate normalized path")
            if folded in seen_casefolded:
                raise MediaTarError("archive contains a duplicate case-folded path")
            path = PurePosixPath(path_value)
            for parent in path.parents:
                parent_value = parent.as_posix()
                if parent_value == ".":
                    break
                if parent_value in regular_paths:
                    raise MediaTarError("archive member descends from a regular file")
            if member.isfile():
                prefix = f"{path_value}/"
                if any(item.startswith(prefix) for item in seen):
                    raise MediaTarError("archive regular file replaces a directory path")
                wanted = expected.get(path_value)
                if wanted is None:
                    raise MediaTarError("archive contains a file outside the allowlist")
                actual_size, actual_digest = hash_member(archive, member)
                if actual_size != member.size or actual_size != wanted.size:
                    raise MediaTarError("archive file size does not match the allowlist")
                if actual_digest != wanted.sha256:
                    raise MediaTarError("archive file SHA-256 does not match the allowlist")
                regular_members[path_value] = member
                regular_paths.add(path_value)
            elif path_value not in expected_dirs:
                raise MediaTarError("archive contains a directory outside the allowlist")
            seen.add(path_value)
            seen_casefolded.add(folded)
    except (tarfile.TarError, OSError, EOFError) as exc:
        raise MediaTarError("archive is truncated or malformed") from exc
    finally:
        archive.close()

    missing = set(expected).difference(regular_members)
    if missing:
        raise MediaTarError("archive is missing an allowlisted file")
    return regular_members


def load_selection(path: Path, expected: dict[str, ExpectedEntry]) -> list[str]:
    document = load_json(path, "selection")
    if not isinstance(document, dict) or set(document) != {"schemaVersion", "paths"}:
        raise MediaTarError("selection must contain exactly schemaVersion and paths")
    paths = document["paths"]
    if document["schemaVersion"] != 1 or not isinstance(paths, list):
        raise MediaTarError("selection schema is unsupported")
    selected: list[str] = []
    seen: set[str] = set()
    for raw in paths:
        path_value = canonical_path(raw, "selection")
        if path_value not in expected:
            raise MediaTarError("selection contains a path outside the allowlist")
        if path_value in seen:
            raise MediaTarError("selection contains a duplicate path")
        seen.add(path_value)
        selected.append(path_value)
    if selected != sorted(selected):
        raise MediaTarError("selection paths must be bytewise sorted")
    return selected


def prepare_destination(path: Path) -> Path:
    if not path.is_absolute():
        raise MediaTarError("extraction destination must be absolute")
    try:
        metadata = path.lstat()
    except OSError as exc:
        raise MediaTarError("extraction destination does not exist") from exc
    if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISDIR(metadata.st_mode):
        raise MediaTarError("extraction destination is not a regular directory")
    resolved = path.resolve(strict=True)
    if resolved == Path("/"):
        raise MediaTarError("extraction destination cannot be the filesystem root")
    if any(resolved.iterdir()):
        raise MediaTarError("extraction destination must be empty")
    return resolved


def open_parent_directory(root_descriptor: int, relative: PurePosixPath) -> int:
    descriptor = os.dup(root_descriptor)
    directory_flags = os.O_RDONLY | getattr(os, "O_DIRECTORY", 0)
    if hasattr(os, "O_NOFOLLOW"):
        directory_flags |= os.O_NOFOLLOW
    try:
        for component in relative.parts[:-1]:
            try:
                os.mkdir(component, mode=0o700, dir_fd=descriptor)
            except FileExistsError:
                pass
            next_descriptor = os.open(component, directory_flags, dir_fd=descriptor)
            os.close(descriptor)
            descriptor = next_descriptor
        return descriptor
    except Exception:
        os.close(descriptor)
        raise


def extract_selected(
    source: BinaryIO,
    expected: dict[str, ExpectedEntry],
    selected: list[str],
    destination: Path,
) -> None:
    source.seek(0)
    try:
        archive = tarfile.open(fileobj=source, mode="r:")
    except (tarfile.TarError, OSError) as exc:
        raise MediaTarError("archive cannot be reopened for extraction") from exc
    selected_set = set(selected)
    extracted: set[str] = set()
    root_flags = os.O_RDONLY | getattr(os, "O_DIRECTORY", 0)
    if hasattr(os, "O_NOFOLLOW"):
        root_flags |= os.O_NOFOLLOW
    root_descriptor = os.open(destination, root_flags)
    try:
        for member in archive:
            path_value = canonical_path(member.name, "archive")
            if not member.isfile() or path_value not in selected_set:
                continue
            relative = PurePosixPath(path_value)
            stream = archive.extractfile(member)
            if stream is None:
                raise MediaTarError("selected member has no readable body")
            parent_descriptor = open_parent_directory(root_descriptor, relative)
            flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
            if hasattr(os, "O_NOFOLLOW"):
                flags |= os.O_NOFOLLOW
            descriptor = -1
            digest = hashlib.sha256()
            total = 0
            try:
                descriptor = os.open(
                    relative.name,
                    flags,
                    0o600,
                    dir_fd=parent_descriptor,
                )
                with stream, os.fdopen(descriptor, "wb", closefd=True) as output:
                    descriptor = -1
                    while True:
                        chunk = stream.read(COPY_BUFFER_SIZE)
                        if not chunk:
                            break
                        output.write(chunk)
                        digest.update(chunk)
                        total += len(chunk)
                    output.flush()
                    os.fsync(output.fileno())
            except Exception:
                if descriptor >= 0:
                    os.close(descriptor)
                try:
                    os.unlink(relative.name, dir_fd=parent_descriptor)
                except FileNotFoundError:
                    pass
                os.close(parent_descriptor)
                raise
            wanted = expected[path_value]
            if total != wanted.size or digest.hexdigest() != wanted.sha256:
                try:
                    os.unlink(relative.name, dir_fd=parent_descriptor)
                except FileNotFoundError:
                    pass
                os.close(parent_descriptor)
                raise MediaTarError("selected member changed between validation and extraction")
            os.close(parent_descriptor)
            extracted.add(path_value)
    finally:
        os.close(root_descriptor)
        archive.close()
    if extracted != selected_set:
        raise MediaTarError("not every selected member was extracted")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--allowlist", required=True, type=Path)
    parser.add_argument("--select", type=Path)
    parser.add_argument("--destination", type=Path)
    parser.add_argument("--build-root", type=Path)
    parser.add_argument("--build-output", type=Path)
    parser.add_argument("archive", nargs="?", type=Path)
    args = parser.parse_args()
    if (args.select is None) != (args.destination is None):
        parser.error("--select and --destination must be supplied together")
    building = args.build_root is not None or args.build_output is not None
    if building:
        if args.build_root is None or args.build_output is None:
            parser.error("--build-root and --build-output must be supplied together")
        if args.archive is not None or args.select is not None:
            parser.error("build mode cannot be combined with validation or extraction arguments")
    elif args.archive is None:
        parser.error("archive is required outside build mode")
    return args


def main() -> int:
    args = parse_args()
    try:
        expected = load_allowlist(args.allowlist)
        if args.build_root is not None:
            root = prepare_build_root(args.build_root)
            build_archive(root, args.build_output, expected)
            print(
                f"media tar build passed: files={len(expected)}",
                file=sys.stderr,
            )
            return 0
        assert args.archive is not None
        if not args.archive.is_absolute():
            raise MediaTarError("archive path must be absolute")
        archive_metadata = args.archive.lstat()
        if stat.S_ISLNK(archive_metadata.st_mode) or not stat.S_ISREG(archive_metadata.st_mode):
            raise MediaTarError("archive is not a regular file")
        with args.archive.open("rb") as source:
            validate(source, expected)
            extracted_count = 0
            if args.select is not None:
                selection = load_selection(args.select, expected)
                destination = prepare_destination(args.destination)
                extract_selected(source, expected, selection, destination)
                extracted_count = len(selection)
    except (MediaTarError, OSError, tarfile.TarError, shutil.Error) as exc:
        print(f"media tar validation failed: {exc}", file=sys.stderr)
        return 1
    print(
        f"media tar validation passed: files={len(expected)} extracted={extracted_count}",
        file=sys.stderr,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
