#!/usr/bin/env python3
"""Validate a portfolio release tar stream without extracting it.

The caller is expected to decompress the bundle first, for example:

    zstd -dc bundle.tar.zst | validate-bundle-tar.py --release-id <id>

Only a deterministic tree of regular files and directories rooted at the
expected release directory is accepted.  The script intentionally does not
extract or return archive contents.
"""

from __future__ import annotations

import argparse
import os
import re
import sys
import tarfile
from pathlib import PurePosixPath
from typing import BinaryIO


RELEASE_ID_PATTERN = re.compile(r"[0-9a-f]{12}-[0-9a-f]{12}\Z")
DEFAULT_MAX_ENTRIES = 200_000
DEFAULT_MAX_FILE_SIZE = 16 * 1024 * 1024 * 1024
DEFAULT_MAX_TOTAL_SIZE = 32 * 1024 * 1024 * 1024
REQUIRED_DIRECTORIES = {"admin", "public-assets", "ops", "images", "compliance"}
REQUIRED_FILES = {"release.json", "bundle-manifest.json"}
ALLOWED_TOP_LEVEL = REQUIRED_DIRECTORIES | REQUIRED_FILES


class ValidationError(RuntimeError):
    """Raised for an archive that violates the release-bundle contract."""


class StrictUstarStream:
    """Pass through bytes while rejecting non-POSIX-ustar raw headers."""

    def __init__(self, stream: BinaryIO) -> None:
        self._stream = stream
        self._buffer = bytearray()
        self._payload_bytes = 0

    def read(self, size: int = -1) -> bytes:
        data = self._stream.read(size)
        if data:
            self._scan(data)
        return data

    def _scan(self, data: bytes) -> None:
        self._buffer.extend(data)
        while self._buffer:
            if self._payload_bytes:
                consumed = min(self._payload_bytes, len(self._buffer))
                del self._buffer[:consumed]
                self._payload_bytes -= consumed
                if self._payload_bytes:
                    return
                continue
            if len(self._buffer) < tarfile.BLOCKSIZE:
                return
            block = bytes(self._buffer[: tarfile.BLOCKSIZE])
            del self._buffer[: tarfile.BLOCKSIZE]
            if block == b"\0" * tarfile.BLOCKSIZE:
                continue
            type_flag = block[156:157]
            if type_flag in {tarfile.XHDTYPE, tarfile.XGLTYPE}:
                raise ValidationError("PAX headers are forbidden")
            if type_flag in {tarfile.GNUTYPE_LONGNAME, tarfile.GNUTYPE_LONGLINK}:
                raise ValidationError("GNU longname/longlink metadata is forbidden")
            if type_flag == tarfile.GNUTYPE_SPARSE:
                raise ValidationError("sparse archive entries are forbidden")
            if block[257:263] != b"ustar\0" or block[263:265] != b"00":
                raise ValidationError("archive is not strict POSIX ustar")
            if type_flag not in {b"", b"\0", tarfile.REGTYPE, tarfile.AREGTYPE, tarfile.DIRTYPE}:
                raise ValidationError("archive contains a non-regular entry")
            raw_size = block[124:136]
            if raw_size[0] & 0x80:
                raise ValidationError("base-256 tar sizes are forbidden")
            encoded_size = raw_size.rstrip(b"\0 ").lstrip(b" ") or b"0"
            if any(byte not in b"01234567" for byte in encoded_size):
                raise ValidationError("tar header contains an invalid size")
            file_size = int(encoded_size, 8)
            self._payload_bytes = (
                (file_size + tarfile.BLOCKSIZE - 1) // tarfile.BLOCKSIZE
            ) * tarfile.BLOCKSIZE


def positive_limit(name: str, default: int) -> int:
    raw = os.environ.get(name)
    if raw is None:
        return default
    try:
        value = int(raw)
    except ValueError as exc:
        raise ValidationError(f"{name} must be an integer") from exc
    if value <= 0:
        raise ValidationError(f"{name} must be greater than zero")
    return value


def raw_path_parts(name: str) -> list[str]:
    if not name or name in {".", "./"}:
        raise ValidationError("archive entry has an empty or dot path")
    if "\\" in name:
        raise ValidationError("archive entry contains a backslash")
    if "\x00" in name or any(ord(character) < 32 for character in name):
        raise ValidationError("archive entry contains a control character")
    if name.startswith("/"):
        raise ValidationError("archive entry has an absolute path")

    trimmed = name[:-1] if name.endswith("/") else name
    parts = trimmed.split("/")
    if any(part in {"", ".", ".."} for part in parts):
        raise ValidationError("archive entry has an empty, dot, or parent component")
    return parts


def normalized_name(member: tarfile.TarInfo, expected_root: str) -> str:
    parts = raw_path_parts(member.name)
    path = PurePosixPath(*parts)
    if path.is_absolute() or path.parts[0] != expected_root:
        raise ValidationError(f"archive entry is outside {expected_root}/")
    if len(path.parts) == 1 and not member.isdir():
        raise ValidationError("release root entry must be a directory")
    return path.as_posix()


def reject_unsupported_metadata(member: tarfile.TarInfo) -> None:
    if member.type in {tarfile.XHDTYPE, tarfile.XGLTYPE} or member.pax_headers:
        raise ValidationError("PAX headers are forbidden")
    if member.type == tarfile.GNUTYPE_SPARSE or getattr(member, "sparse", None):
        raise ValidationError("sparse archive entries are forbidden")
    if not (member.isfile() or member.isdir()):
        raise ValidationError("archive contains a non-regular entry")
    if member.size < 0:
        raise ValidationError("archive entry has a negative size")
    if member.isdir() and member.size != 0:
        raise ValidationError("archive directory has a non-zero size")


def validate_archive(stream: BinaryIO, release_id: str) -> tuple[int, int]:
    if not RELEASE_ID_PATTERN.fullmatch(release_id):
        raise ValidationError("release ID must match <12 lowercase hex>-<12 lowercase hex>")

    max_entries = positive_limit("PORTFOLIO_BUNDLE_MAX_ENTRIES", DEFAULT_MAX_ENTRIES)
    max_file_size = positive_limit(
        "PORTFOLIO_BUNDLE_MAX_FILE_SIZE", DEFAULT_MAX_FILE_SIZE
    )
    max_total_size = positive_limit(
        "PORTFOLIO_BUNDLE_MAX_TOTAL_SIZE", DEFAULT_MAX_TOTAL_SIZE
    )
    expected_root = f"portfolio-{release_id}"
    normalized_names: set[str] = set()
    casefolded_names: set[str] = set()
    regular_files: set[str] = set()
    entry_count = 0
    total_size = 0

    try:
        archive = tarfile.open(fileobj=StrictUstarStream(stream), mode="r|")
    except (tarfile.TarError, OSError) as exc:
        raise ValidationError("input is not a readable tar stream") from exc

    try:
        for member in archive:
            entry_count += 1
            if entry_count > max_entries:
                raise ValidationError("archive contains too many entries")

            reject_unsupported_metadata(member)
            name = normalized_name(member, expected_root)
            path = PurePosixPath(name)
            if len(path.parts) > 1:
                top_level = path.parts[1]
                if top_level not in ALLOWED_TOP_LEVEL:
                    raise ValidationError(
                        "archive contains an unknown release top-level entry"
                    )
                if len(path.parts) == 2:
                    if top_level in REQUIRED_DIRECTORIES and not member.isdir():
                        raise ValidationError(
                            "required release top-level directory has the wrong type"
                        )
                    if top_level in REQUIRED_FILES and not member.isfile():
                        raise ValidationError(
                            "required release top-level file has the wrong type"
                        )
            folded = name.casefold()
            if name in normalized_names:
                raise ValidationError("archive contains a duplicate normalized path")
            if folded in casefolded_names:
                raise ValidationError("archive contains a duplicate case-folded path")

            # A regular-file ancestor makes a child path ambiguous to extractors.
            for parent in path.parents:
                parent_name = parent.as_posix()
                if parent_name == ".":
                    break
                if parent_name in regular_files:
                    raise ValidationError("archive path descends from a regular file")
            if member.isfile():
                prefix = f"{name}/"
                if any(existing.startswith(prefix) for existing in normalized_names):
                    raise ValidationError("archive regular file replaces a directory path")
                if member.size > max_file_size:
                    raise ValidationError("archive file exceeds the configured size limit")
                total_size += member.size
                if total_size > max_total_size:
                    raise ValidationError("archive exceeds the configured total size limit")
                regular_files.add(name)

            normalized_names.add(name)
            casefolded_names.add(folded)
    except (tarfile.TarError, OSError, EOFError) as exc:
        raise ValidationError("tar stream is truncated or malformed") from exc
    finally:
        archive.close()

    if entry_count == 0:
        raise ValidationError("archive is empty")
    if expected_root not in normalized_names:
        raise ValidationError("archive does not contain its release root directory")
    required = {
        *(f"{expected_root}/{name}" for name in REQUIRED_DIRECTORIES),
        *(f"{expected_root}/{name}" for name in REQUIRED_FILES),
    }
    missing = required - normalized_names
    if missing:
        raise ValidationError("archive is missing a required release top-level entry")
    return entry_count, total_size


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release-id", required=True)
    parser.add_argument(
        "archive",
        nargs="?",
        help="uncompressed tar path; omit it to read the tar stream from stdin",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        if args.archive:
            with open(args.archive, "rb") as stream:
                entry_count, total_size = validate_archive(stream, args.release_id)
        else:
            entry_count, total_size = validate_archive(sys.stdin.buffer, args.release_id)
    except (ValidationError, OSError) as exc:
        print(f"bundle validation failed: {exc}", file=sys.stderr)
        return 1

    print(
        f"bundle validation passed: entries={entry_count} regular-bytes={total_size}",
        file=sys.stderr,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
