#!/usr/bin/env python3
"""Safely materialize one linux/amd64 image from an uncompressed docker-save archive."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import os
import posixpath
import shutil
import stat
import tarfile
from pathlib import Path, PurePosixPath
from typing import BinaryIO, Iterable


class ExtractionError(RuntimeError):
    pass


def strict_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ExtractionError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def parse_json(data: bytes, label: str) -> object:
    try:
        return json.loads(data.decode("utf-8"), object_pairs_hook=strict_object)
    except (UnicodeDecodeError, json.JSONDecodeError, ExtractionError) as error:
        raise ExtractionError(f"invalid {label}: {error}") from error


def safe_archive_name(name: str, label: str) -> str:
    if not name or name.startswith("/") or "\\" in name or any(ord(char) < 32 or ord(char) == 127 for char in name):
        raise ExtractionError(f"unsafe {label} path: {name!r}")
    parts: list[str] = []
    for part in PurePosixPath(name).parts:
        if part in ("", "."):
            continue
        if part == "..":
            raise ExtractionError(f"escaping {label} path: {name!r}")
        parts.append(part)
    if not parts:
        raise ExtractionError(f"empty {label} path: {name!r}")
    return "/".join(parts)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def remove_path(path: Path) -> None:
    try:
        mode = path.lstat().st_mode
    except FileNotFoundError:
        return
    if stat.S_ISDIR(mode) and not stat.S_ISLNK(mode):
        shutil.rmtree(path)
    else:
        path.unlink()


def ensure_parents(root: Path, relative: str) -> None:
    current = root
    for part in PurePosixPath(relative).parts[:-1]:
        current = current / part
        try:
            mode = current.lstat().st_mode
        except FileNotFoundError:
            current.mkdir(mode=0o700)
            continue
        if stat.S_ISLNK(mode) or not stat.S_ISDIR(mode):
            raise ExtractionError(f"layer path has a non-directory ancestor: {relative}")


def secure_target(root: Path, relative: str) -> Path:
    normalized = safe_archive_name(relative, "layer")
    target = root.joinpath(*PurePosixPath(normalized).parts)
    try:
        target.relative_to(root)
    except ValueError as error:
        raise ExtractionError(f"layer path escapes rootfs: {relative}") from error
    return target


def symlink_value(root: Path, relative: str, link_name: str) -> str:
    if not link_name or "\\" in link_name or any(ord(char) < 32 or ord(char) == 127 for char in link_name):
        raise ExtractionError(f"unsafe symlink target for {relative}: {link_name!r}")
    if link_name.startswith("/"):
        image_target = safe_archive_name(link_name.lstrip("/"), "absolute symlink")
    else:
        image_target = posixpath.normpath(posixpath.join(posixpath.dirname(relative), link_name))
        if image_target == ".." or image_target.startswith("../"):
            raise ExtractionError(f"symlink escapes image root: {relative} -> {link_name}")
        image_target = safe_archive_name(image_target, "symlink")
    host_target = root.joinpath(*PurePosixPath(image_target).parts)
    host_parent = root.joinpath(*PurePosixPath(relative).parts).parent
    return os.path.relpath(host_target, host_parent)


def apply_whiteout(root: Path, relative: str) -> bool:
    name = posixpath.basename(relative)
    if not name.startswith(".wh."):
        return False
    parent_relative = posixpath.dirname(relative)
    parent = root if not parent_relative else secure_target(root, parent_relative)
    if name == ".wh..wh..opq":
        if parent.exists():
            if parent.is_symlink() or not parent.is_dir():
                raise ExtractionError(f"opaque whiteout parent is not a directory: {relative}")
            for child in parent.iterdir():
                remove_path(child)
        return True
    target_name = name[len(".wh.") :]
    if not target_name or target_name in (".", ".."):
        raise ExtractionError(f"invalid whiteout: {relative}")
    target_relative = posixpath.join(parent_relative, target_name) if parent_relative else target_name
    remove_path(secure_target(root, target_relative))
    return True


def is_evidence_entry(relative: str, member: tarfile.TarInfo) -> bool:
    if relative == "app/portfolio-server.jar":
        return True
    if relative == "var/lib/dpkg/status":
        return True
    if relative.startswith("var/lib/dpkg/info/") and relative.endswith(".list"):
        return True
    for prefix in ("opt/java/openjdk/legal", "usr/local/share/doc", "usr/local/share/licenses", "usr/share/licenses"):
        if relative == prefix or relative.startswith(f"{prefix}/"):
            return True
    if relative.startswith("opt/java/openjdk/"):
        name = posixpath.basename(relative).lower()
        if name == "license" or name.startswith("notice"):
            return True
    if relative == "usr/share/doc" or relative.startswith("usr/share/doc/"):
        if member.isdir() or member.issym() or member.islnk():
            return True
        return bool(posixpath.basename(relative).lower().startswith(("copyright", "license", "copying", "notice")))
    return relative in {
        "usr/local/bin/docker-entrypoint.sh",
        "usr/local/bin/docker-ensure-initdb.sh",
        "usr/local/bin/docker-enforce-initdb.sh",
        "usr/local/bin/gosu",
    }


class HashingReader:
    def __init__(self, source: BinaryIO) -> None:
        self.source = source
        self.digest = hashlib.sha256()

    def read(self, size: int = -1) -> bytes:
        data = self.source.read(size)
        self.digest.update(data)
        return data

    def readable(self) -> bool:
        return True


class PrefixReader:
    def __init__(self, prefix: bytes, source: BinaryIO) -> None:
        self.prefix = prefix
        self.source = source

    def read(self, size: int = -1) -> bytes:
        if size == 0:
            return b""
        if size < 0:
            data = self.prefix + self.source.read()
            self.prefix = b""
            return data
        data = self.prefix[:size]
        self.prefix = self.prefix[len(data) :]
        if len(data) < size:
            data += self.source.read(size - len(data))
        return data


def apply_layer(root: Path, stream: BinaryIO, expected_diff_id: str) -> tuple[str, int]:
    prefix = stream.read(4)
    encoded = PrefixReader(prefix, stream)
    if prefix.startswith(b"\x1f\x8b"):
        uncompressed: BinaryIO = gzip.GzipFile(fileobj=encoded, mode="rb")
    elif prefix.startswith(b"\x28\xb5\x2f\xfd"):
        raise ExtractionError("zstd-compressed docker layers are not supported by the reviewed Python runtime")
    else:
        uncompressed = encoded  # type: ignore[assignment]
    reader = HashingReader(uncompressed)
    pending_hardlinks: list[tuple[Path, Path, str]] = []
    file_count = 0
    try:
        archive = tarfile.open(fileobj=reader, mode="r|")
        for member in archive:
            if member.name.rstrip("/") in {"", "."}:
                if not member.isdir():
                    raise ExtractionError("layer root entry is not a directory")
                continue
            relative = safe_archive_name(member.name, "layer")
            if apply_whiteout(root, relative):
                continue
            if not is_evidence_entry(relative, member):
                continue
            target = secure_target(root, relative)
            ensure_parents(root, relative)
            if member.isdir():
                if target.is_symlink() or (target.exists() and not target.is_dir()):
                    remove_path(target)
                target.mkdir(mode=0o700, exist_ok=True)
                continue
            if member.isreg():
                if member.size < 0 or member.size > 2 * 1024 * 1024 * 1024:
                    raise ExtractionError(f"unsafe layer file size: {relative}")
                remove_path(target)
                source = archive.extractfile(member)
                if source is None:
                    raise ExtractionError(f"cannot read layer file: {relative}")
                with target.open("xb") as destination:
                    shutil.copyfileobj(source, destination, length=1024 * 1024)
                os.chmod(target, 0o600)
                file_count += 1
                continue
            if member.issym():
                remove_path(target)
                target.symlink_to(symlink_value(root, relative, member.linkname))
                continue
            if member.islnk():
                link_relative = safe_archive_name(member.linkname, "hardlink")
                link_target = secure_target(root, link_relative)
                remove_path(target)
                pending_hardlinks.append((target, link_target, relative))
                continue
            raise ExtractionError(f"unsupported layer entry type: {relative}")
        archive.close()
        for chunk in iter(lambda: reader.read(1024 * 1024), b""):
            pass
    except (tarfile.TarError, OSError) as error:
        raise ExtractionError(f"cannot apply image layer: {error}") from error

    unresolved = pending_hardlinks
    while unresolved:
        next_round: list[tuple[Path, Path, str]] = []
        progress = False
        for target, link_target, relative in unresolved:
            try:
                mode = link_target.lstat().st_mode
            except FileNotFoundError:
                next_round.append((target, link_target, relative))
                continue
            if not stat.S_ISREG(mode):
                raise ExtractionError(f"hardlink does not reference a regular file: {relative}")
            os.link(link_target, target)
            progress = True
        if next_round and not progress:
            raise ExtractionError(f"unresolved layer hardlink: {next_round[0][2]}")
        unresolved = next_round

    actual = f"sha256:{reader.digest.hexdigest()}"
    if actual != expected_diff_id:
        raise ExtractionError(f"layer diff ID mismatch: expected {expected_diff_id}, got {actual}")
    return actual, file_count


def regular_member(members: dict[str, tarfile.TarInfo], name: str, label: str) -> tarfile.TarInfo:
    member = members.get(name)
    if member is None or not member.isreg() or member.issym() or member.islnk():
        raise ExtractionError(f"{label} is not a regular archive member: {name}")
    return member


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--archive", required=True, type=Path)
    parser.add_argument("--expected-config-digest", required=True)
    parser.add_argument("--rootfs", required=True, type=Path)
    parser.add_argument("--metadata-output", required=True, type=Path)
    return parser.parse_args()


def main() -> None:
    os.umask(0o077)
    options = parse_args()
    if not options.expected_config_digest.startswith("sha256:") or len(options.expected_config_digest) != 71:
        raise ExtractionError("expected config digest must be sha256:<64 lowercase hex>")
    if any(char not in "0123456789abcdef" for char in options.expected_config_digest[7:]):
        raise ExtractionError("expected config digest must use lowercase hexadecimal")
    archive_input = Path(os.path.abspath(options.archive))
    if archive_input.is_symlink() or not archive_input.is_file():
        raise ExtractionError("archive must be a regular non-symlink file")
    archive_path = archive_input.resolve(strict=True)
    rootfs = Path(os.path.abspath(options.rootfs))
    if rootfs.exists():
        if rootfs.is_symlink() or not rootfs.is_dir() or any(rootfs.iterdir()):
            raise ExtractionError("rootfs output must be an empty non-symlink directory")
    else:
        rootfs.mkdir(mode=0o700)
    metadata_output = Path(os.path.abspath(options.metadata_output))
    if metadata_output.exists() or not metadata_output.parent.is_dir():
        raise ExtractionError("metadata output must not exist and its parent must exist")

    archive_digest = sha256_file(archive_path)
    with tarfile.open(archive_path, mode="r:") as outer:
        members: dict[str, tarfile.TarInfo] = {}
        for member in outer.getmembers():
            name = safe_archive_name(member.name, "docker archive")
            if name in members and member.isreg():
                raise ExtractionError(f"duplicate docker archive member: {name}")
            if member.issym() or member.islnk() or member.ischr() or member.isblk() or member.isfifo():
                raise ExtractionError(f"unsupported docker archive member type: {name}")
            members[name] = member
        manifest_member = regular_member(members, "manifest.json", "manifest")
        manifest_stream = outer.extractfile(manifest_member)
        if manifest_stream is None or manifest_member.size > 1024 * 1024:
            raise ExtractionError("docker archive manifest is unreadable or too large")
        manifest = parse_json(manifest_stream.read(), "docker archive manifest")
        if not isinstance(manifest, list) or len(manifest) != 1 or not isinstance(manifest[0], dict):
            raise ExtractionError("docker archive must contain exactly one image manifest")
        image = manifest[0]
        if set(image) != {"Config", "RepoTags", "Layers"}:
            raise ExtractionError("docker archive image manifest fields are unexpected")
        config_name = safe_archive_name(str(image["Config"]), "config")
        config_member = regular_member(members, config_name, "config")
        config_stream = outer.extractfile(config_member)
        if config_stream is None or config_member.size > 16 * 1024 * 1024:
            raise ExtractionError("image config is unreadable or too large")
        config_bytes = config_stream.read()
        config_digest = f"sha256:{hashlib.sha256(config_bytes).hexdigest()}"
        if config_digest != options.expected_config_digest:
            raise ExtractionError(f"image config digest mismatch: {config_digest}")
        config_basename = posixpath.basename(config_name)
        if config_basename not in {config_digest[7:], f"{config_digest[7:]}.json"}:
            raise ExtractionError("image config filename does not match its digest")
        config = parse_json(config_bytes, "image config")
        if not isinstance(config, dict) or config.get("os") != "linux" or config.get("architecture") != "amd64":
            raise ExtractionError("image config is not linux/amd64")
        rootfs_config = config.get("rootfs")
        if not isinstance(rootfs_config, dict) or rootfs_config.get("type") != "layers":
            raise ExtractionError("image config rootfs is invalid")
        diff_ids = rootfs_config.get("diff_ids")
        layers = image["Layers"]
        if not isinstance(diff_ids, list) or not isinstance(layers, list) or len(diff_ids) != len(layers) or not layers:
            raise ExtractionError("image layers and diff IDs are incomplete")
        if len(set(layers)) != len(layers):
            raise ExtractionError("docker archive repeats a layer")
        applied_layers: list[dict[str, object]] = []
        for index, (layer_name_value, expected_diff_id) in enumerate(zip(layers, diff_ids, strict=True)):
            layer_name = safe_archive_name(str(layer_name_value), "layer member")
            if not isinstance(expected_diff_id, str) or not expected_diff_id.startswith("sha256:"):
                raise ExtractionError("image config contains an invalid diff ID")
            layer_member = regular_member(members, layer_name, "layer")
            layer_stream = outer.extractfile(layer_member)
            if layer_stream is None:
                raise ExtractionError(f"cannot read image layer: {layer_name}")
            actual_diff_id, file_count = apply_layer(rootfs, layer_stream, expected_diff_id)
            applied_layers.append({"index": index, "diffId": actual_diff_id, "filesApplied": file_count})

    repo_tags = image["RepoTags"]
    if not isinstance(repo_tags, list) or not repo_tags or not all(isinstance(tag, str) and tag for tag in repo_tags):
        raise ExtractionError("docker archive has no valid repository tags")
    metadata = {
        "schemaVersion": 1,
        "archiveFormat": "docker-save",
        "extractionMode": "license-evidence-only",
        "archiveSha256": archive_digest,
        "configDigest": config_digest,
        "platform": {"os": "linux", "architecture": "amd64"},
        "repoTags": sorted(set(repo_tags)),
        "layers": applied_layers,
    }
    metadata_output.write_text(json.dumps(metadata, indent=2, sort_keys=True) + "\n", encoding="utf-8", newline="\n")
    os.chmod(metadata_output, 0o600)
    print(f"extract-docker-image-rootfs: {len(applied_layers)} layers, config {config_digest}", file=os.sys.stderr)


if __name__ == "__main__":
    try:
        main()
    except (ExtractionError, OSError, tarfile.TarError) as error:
        print(f"extract-docker-image-rootfs: {error}", file=os.sys.stderr)
        raise SystemExit(1)
