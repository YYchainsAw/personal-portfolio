#!/usr/bin/env python3
"""Create a closed legal inventory for an installed, hash-locked Python runtime."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
import re
import shutil
import stat
import sys
from email import policy
from email.parser import BytesParser
from pathlib import Path, PurePosixPath


class CollectionError(RuntimeError):
    pass


def fail(message: str) -> None:
    raise CollectionError(message)


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def canonical_name(value: str) -> str:
    return re.sub(r"[-_.]+", "-", value).lower()


def safe_segment(value: str) -> str:
    result = re.sub(r"[^A-Za-z0-9._+-]", "_", value)
    if not result or result in {".", ".."}:
        fail(f"unsafe output segment: {value!r}")
    return result


def strict_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            fail(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def exact_keys(value: object, keys: set[str], label: str) -> dict[str, object]:
    if not isinstance(value, dict) or set(value) != keys:
        fail(f"{label} fields must be exactly: {', '.join(sorted(keys))}")
    return value


def read_utf8(path: Path, label: str, maximum: int = 4 * 1024 * 1024) -> tuple[bytes, str]:
    if path.is_symlink() or not path.is_file():
        fail(f"{label} is not a regular non-symlink file")
    data = path.read_bytes()
    if not data or len(data) > maximum:
        fail(f"{label} has unsafe size")
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError as error:
        fail(f"{label} is not UTF-8: {error}")
    return data, text


def parse_lock(path: Path) -> dict[str, dict[str, str]]:
    _, text = read_utf8(path, "Python requirements lock", 1024 * 1024)
    logical: list[str] = []
    pending = ""
    for raw_line in text.replace("\r\n", "\n").split("\n"):
        stripped = raw_line.strip()
        if not stripped or stripped.startswith("#"):
            if pending:
                fail("requirements lock has a comment or blank line inside a continuation")
            continue
        continuation = stripped.endswith("\\")
        part = stripped[:-1].rstrip() if continuation else stripped
        pending = f"{pending} {part}".strip()
        if not continuation:
            logical.append(pending)
            pending = ""
    if pending:
        fail("requirements lock ends inside a continuation")
    packages: dict[str, dict[str, str]] = {}
    pattern = re.compile(
        r"^([A-Za-z0-9][A-Za-z0-9._-]*)==([^\s;\\]+)\s+--hash=sha256:([0-9a-f]{64})$"
    )
    for requirement in logical:
        match = pattern.fullmatch(requirement)
        if not match:
            fail(f"requirements entry is not one exact version plus one SHA-256 hash: {requirement}")
        source_name, version, artifact_sha256 = match.groups()
        name = canonical_name(source_name)
        if name in packages:
            fail(f"duplicate requirements distribution: {name}")
        packages[name] = {
            "name": name,
            "sourceName": source_name,
            "version": version,
            "artifactSha256": artifact_sha256,
        }
    if not packages:
        fail("requirements lock is empty")
    return packages


def secure_relative(value: str, label: str) -> PurePosixPath:
    if not value or "\\" in value or value.startswith("/") or any(ord(char) < 32 for char in value):
        fail(f"unsafe {label}: {value!r}")
    path = PurePosixPath(value)
    if any(part in {"", ".", ".."} for part in path.parts):
        fail(f"unsafe {label}: {value!r}")
    return path


def path_inside(root: Path, candidate: Path, label: str) -> Path:
    try:
        relative = candidate.relative_to(root)
        current = root
        for part in relative.parts:
            current = current / part
            if current.is_symlink():
                fail(f"{label} contains a symlink: {relative.as_posix()}")
        actual = candidate.resolve(strict=True)
        actual.relative_to(root)
    except (OSError, ValueError) as error:
        fail(f"{label} escapes site-packages: {error}")
    if candidate.is_symlink() or not actual.is_file():
        fail(f"{label} is not a regular non-symlink file")
    return actual


def read_record(dist_info: Path, site_packages: Path) -> set[str]:
    record_path = dist_info / "RECORD"
    data, text = read_utf8(record_path, f"{dist_info.name}/RECORD", 16 * 1024 * 1024)
    del data
    paths: set[str] = set()
    try:
        rows = csv.reader(text.splitlines())
        for row in rows:
            if len(row) != 3 or not row[0]:
                fail(f"{dist_info.name}/RECORD has an invalid row")
            raw_path = row[0]
            if "\\" in raw_path or raw_path.startswith("/") or any(ord(char) < 32 for char in raw_path):
                fail(f"{dist_info.name}/RECORD has an unsafe path: {raw_path!r}")
            relative = PurePosixPath(raw_path)
            if any(part in {"", "."} for part in relative.parts):
                fail(f"{dist_info.name}/RECORD has an unsafe path: {raw_path!r}")
            if ".." in relative.parts:
                parent_count = 0
                while parent_count < len(relative.parts) and relative.parts[parent_count] == "..":
                    parent_count += 1
                if parent_count > 0 and ".." not in relative.parts[parent_count:]:
                    continue
                fail(f"{dist_info.name}/RECORD has a non-canonical path: {raw_path!r}")
            normalized = relative.as_posix()
            if normalized in paths:
                fail(f"{dist_info.name}/RECORD has a duplicate path: {normalized}")
            paths.add(normalized)
    except csv.Error as error:
        fail(f"{dist_info.name}/RECORD is invalid CSV: {error}")
    own_record = dist_info.relative_to(site_packages).as_posix() + "/RECORD"
    if own_record not in paths:
        fail(f"{dist_info.name}/RECORD does not list itself")
    return paths


def read_installed_files(egg_info: Path, site_packages: Path) -> set[str]:
    _, text = read_utf8(egg_info / "installed-files.txt", f"{egg_info.name}/installed-files.txt", 16 * 1024 * 1024)
    paths: set[str] = set()
    for raw in text.replace("\r\n", "\n").split("\n"):
        if not raw:
            continue
        if "\\" in raw or raw.startswith("/") or any(ord(char) < 32 for char in raw):
            fail(f"{egg_info.name}/installed-files.txt has an unsafe path: {raw!r}")
        candidate = egg_info.joinpath(*PurePosixPath(raw).parts)
        actual = path_inside(site_packages, candidate, f"{egg_info.name} installed file")
        normalized = actual.relative_to(site_packages).as_posix()
        if normalized in paths:
            fail(f"{egg_info.name}/installed-files.txt has a duplicate path: {normalized}")
        paths.add(normalized)
    own_metadata = egg_info.relative_to(site_packages).as_posix() + "/PKG-INFO"
    if own_metadata not in paths:
        fail(f"{egg_info.name}/installed-files.txt does not list PKG-INFO")
    return paths


def parse_overrides(path: Path) -> dict[str, dict[str, object]]:
    data, text = read_utf8(path, "Python legal override manifest", 1024 * 1024)
    del data
    try:
        manifest = json.loads(text, object_pairs_hook=strict_object)
    except (json.JSONDecodeError, CollectionError) as error:
        fail(f"invalid Python legal override manifest: {error}")
    manifest = exact_keys(manifest, {"packages", "schemaVersion"}, "Python legal override manifest")
    if manifest["schemaVersion"] != 1 or not isinstance(manifest["packages"], dict):
        fail("Python legal override manifest schema changed")
    result: dict[str, dict[str, object]] = {}
    for identity, raw_override in manifest["packages"].items():
        if not isinstance(identity, str) or not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*==[^\s=]+", identity):
            fail(f"invalid Python legal override identity: {identity!r}")
        override = exact_keys(raw_override, {"artifactSha256", "files"}, f"{identity} override")
        if not isinstance(override["artifactSha256"], str) or not re.fullmatch(r"[0-9a-f]{64}", override["artifactSha256"]):
            fail(f"{identity} override has an invalid artifact digest")
        if not isinstance(override["files"], list) or not override["files"]:
            fail(f"{identity} override has no legal files")
        seen_files: set[str] = set()
        for raw_file in override["files"]:
            file = exact_keys(raw_file, {"file", "sha256", "source"}, f"{identity} override legal file")
            if not isinstance(file["file"], str):
                fail(f"{identity} override legal file path is invalid")
            secure_relative(file["file"], f"{identity} override legal file")
            if file["file"] in seen_files:
                fail(f"{identity} override repeats a legal file: {file['file']}")
            seen_files.add(file["file"])
            if not isinstance(file["sha256"], str) or not re.fullmatch(r"[0-9a-f]{64}", file["sha256"]):
                fail(f"{identity} override legal file digest is invalid")
            if not isinstance(file["source"], str) or not file["source"].startswith("https://"):
                fail(f"{identity} override legal file source is invalid")
        result[identity] = override
    return result


def metadata_license_values(message: object) -> list[str]:
    values: set[str] = set()
    for key in ("License-Expression", "License"):
        for raw in message.get_all(key, []):  # type: ignore[attr-defined]
            value = str(raw).strip()
            if value and not re.fullmatch(r"(?i:unknown|unlicensed|none|n/a)", value):
                values.add(value)
    for raw in message.get_all("Classifier", []):  # type: ignore[attr-defined]
        value = str(raw).strip()
        if value.startswith("License :: "):
            values.add(value)
    if not values:
        fail("installed distribution has no usable license metadata")
    return sorted(values)


def legal_candidates(
    message: object, dist_info: Path, site_packages: Path, record_paths: set[str]
) -> list[tuple[str, Path]]:
    declared = [str(value).strip() for value in message.get_all("License-File", [])]  # type: ignore[attr-defined]
    candidates: dict[str, Path] = {}
    for value in declared:
        relative = secure_relative(value, f"{dist_info.name} License-File")
        options = [dist_info / "licenses" / Path(*relative.parts), dist_info / Path(*relative.parts)]
        found = [candidate for candidate in options if candidate.exists() or candidate.is_symlink()]
        if len(found) == 0:
            return []
        if len(found) != 1:
            fail(f"{dist_info.name} declared License-File does not resolve exactly once: {value}")
        candidate = found[0]
        record_path = candidate.relative_to(site_packages).as_posix()
        if record_path not in record_paths:
            fail(f"{dist_info.name} license file is absent from RECORD: {record_path}")
        candidates[record_path] = path_inside(site_packages, candidate, f"{dist_info.name} license file")
    if candidates:
        return sorted(candidates.items())

    prefix = dist_info.relative_to(site_packages).as_posix() + "/"
    for record_path in sorted(record_paths):
        if not record_path.startswith(prefix):
            continue
        name = PurePosixPath(record_path).name
        if not re.match(r"(?i)^(license|licence|copying|notice)(?:[._-]|$)", name):
            continue
        candidate = site_packages.joinpath(*PurePosixPath(record_path).parts)
        candidates[record_path] = path_inside(site_packages, candidate, f"{dist_info.name} discovered legal file")
    if not candidates:
        return []
    return sorted(candidates.items())


def collect(options: argparse.Namespace) -> None:
    requirements_input = Path(os.path.abspath(options.requirements))
    overrides_input = Path(os.path.abspath(options.overrides))
    site_input = Path(os.path.abspath(options.site_packages))
    output = Path(os.path.abspath(options.output))
    if requirements_input.is_symlink() or not requirements_input.is_file():
        fail("requirements lock must be a regular non-symlink file")
    requirements = requirements_input.resolve(strict=True)
    locked = parse_lock(requirements)
    if len(locked) != options.expected_count:
        fail(f"requirements closure count differs: expected {options.expected_count}, got {len(locked)}")
    if site_input.is_symlink() or not site_input.is_dir():
        fail("site-packages must be a non-symlink directory")
    site_packages = site_input.resolve(strict=True)
    if overrides_input.is_symlink() or not overrides_input.is_file():
        fail("Python legal overrides must be a regular non-symlink file")
    overrides_path = overrides_input.resolve(strict=True)
    overrides = parse_overrides(overrides_path)
    if output.exists() or output.is_symlink():
        fail("output already exists")
    output.parent.mkdir(parents=True, exist_ok=True)
    staging = output.parent / f".{output.name}.tmp.{os.getpid()}"
    if staging.exists() or staging.is_symlink():
        fail("staging output already exists")
    staging.mkdir(mode=0o700)
    try:
        dist_infos: dict[str, tuple[Path, object, bytes, str]] = {}
        for child in sorted(site_packages.iterdir(), key=lambda path: path.name):
            if child.name.endswith(".egg-link"):
                fail(f"legacy installed distribution link is not allowed: {child.name}")
            if not child.name.endswith((".dist-info", ".egg-info")):
                continue
            if child.is_symlink() or not child.is_dir():
                fail(f"distribution metadata is not a regular directory: {child.name}")
            metadata_kind = "dist-info" if child.name.endswith(".dist-info") else "egg-info"
            metadata_path = child / ("METADATA" if metadata_kind == "dist-info" else "PKG-INFO")
            metadata_bytes, _ = read_utf8(metadata_path, f"{child.name}/METADATA", 16 * 1024 * 1024)
            try:
                message = BytesParser(policy=policy.default).parsebytes(metadata_bytes)
            except Exception as error:  # email parser exposes several implementation-specific exceptions.
                fail(f"cannot parse {child.name}/METADATA: {error}")
            names = message.get_all("Name", [])
            versions = message.get_all("Version", [])
            if len(names) != 1 or len(versions) != 1:
                fail(f"{child.name}/METADATA must have one Name and one Version")
            name = canonical_name(str(names[0]).strip())
            version = str(versions[0]).strip()
            if not name or not version:
                fail(f"{child.name}/METADATA has an incomplete identity")
            if name in dist_infos:
                fail(f"duplicate installed distribution identity: {name}")
            dist_infos[name] = (child, message, metadata_bytes, metadata_kind)

        if set(dist_infos) != set(locked):
            missing = sorted(set(locked) - set(dist_infos))
            extra = sorted(set(dist_infos) - set(locked))
            fail(f"installed/locked Python closure differs (missing={','.join(missing) or 'none'} extra={','.join(extra) or 'none'})")

        license_root = staging / "licenses"
        license_root.mkdir(mode=0o700)
        package_records: list[dict[str, object]] = []
        used_overrides: set[str] = set()
        for name in sorted(locked):
            lock = locked[name]
            dist_info, message, metadata_bytes, metadata_kind = dist_infos[name]
            installed_version = str(message["Version"]).strip()
            if installed_version != lock["version"]:
                fail(f"installed/locked Python version differs for {name}: {installed_version} != {lock['version']}")
            record_paths = (
                read_record(dist_info, site_packages)
                if metadata_kind == "dist-info"
                else read_installed_files(dist_info, site_packages)
            )
            license_values = metadata_license_values(message)
            package_directory = license_root / f"{safe_segment(name)}-{safe_segment(installed_version)}"
            package_directory.mkdir(mode=0o700)
            legal_records: list[dict[str, object]] = []
            installed_legal = legal_candidates(message, dist_info, site_packages, record_paths)
            identity = f"{name}=={installed_version}"
            if installed_legal:
                for index, (record_path, legal_path) in enumerate(installed_legal, start=1):
                    legal_bytes, _ = read_utf8(legal_path, f"{identity} legal text")
                    output_name = f"{index:02d}-{safe_segment(PurePosixPath(record_path).name)}"
                    destination = package_directory / output_name
                    destination.write_bytes(legal_bytes)
                    os.chmod(destination, 0o600)
                    legal_records.append(
                        {
                            "provenance": "installed-metadata",
                            "sourcePath": record_path,
                            "output": destination.relative_to(staging).as_posix(),
                            "bytes": len(legal_bytes),
                            "sha256": sha256_bytes(legal_bytes),
                        }
                    )
            else:
                override = overrides.get(identity)
                if override is None:
                    fail(f"{identity} has no installed legal text and no reviewed override")
                if override["artifactSha256"] != lock["artifactSha256"]:
                    fail(f"{identity} override artifact digest differs from the requirements lock")
                used_overrides.add(identity)
                override_root = overrides_path.parent
                for index, file in enumerate(override["files"], start=1):
                    file_relative = secure_relative(str(file["file"]), f"{identity} override legal file")
                    legal_path = override_root.joinpath(*file_relative.parts)
                    actual = path_inside(override_root, legal_path, f"{identity} override legal file")
                    legal_bytes, _ = read_utf8(actual, f"{identity} reviewed legal text")
                    if sha256_bytes(legal_bytes) != file["sha256"]:
                        fail(f"{identity} reviewed legal text digest mismatch: {file['file']}")
                    output_name = f"{index:02d}-{safe_segment(PurePosixPath(str(file['file'])).name)}"
                    destination = package_directory / output_name
                    destination.write_bytes(legal_bytes)
                    os.chmod(destination, 0o600)
                    legal_records.append(
                        {
                            "provenance": "reviewed-override",
                            "sourcePath": f"reviewed:{file['file']}",
                            "source": file["source"],
                            "output": destination.relative_to(staging).as_posix(),
                            "bytes": len(legal_bytes),
                            "sha256": sha256_bytes(legal_bytes),
                        }
                    )
            package_records.append(
                {
                    "name": name,
                    "version": installed_version,
                    "identity": identity,
                    "artifactSha256": lock["artifactSha256"],
                    "metadataDirectory": dist_info.relative_to(site_packages).as_posix(),
                    "metadataKind": metadata_kind,
                    "metadataSha256": sha256_bytes(metadata_bytes),
                    "licenseMetadata": license_values,
                    "legalFiles": legal_records,
                }
            )
        unused_overrides = sorted(set(overrides) - used_overrides)
        if unused_overrides:
            fail(f"unused or unknown Python legal overrides: {', '.join(unused_overrides)}")

        manifest = {
            "schemaVersion": 1,
            "component": "cos-prune-runtime",
            "target": {"os": "ubuntu", "osVersion": "22.04", "architecture": "x86_64", "python": "3.10"},
            "requirements": {
                "path": "requirements-cos-prune.txt",
                "sha256": sha256_file(requirements),
            },
            "packages": package_records,
        }
        lock_output = staging / "requirements-cos-prune.txt"
        shutil.copyfile(requirements, lock_output)
        os.chmod(lock_output, 0o600)
        manifest_path = staging / "manifest.json"
        manifest_path.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        os.chmod(manifest_path, 0o600)
        root_ref = "urn:portfolio:cos-prune-runtime"
        components = []
        for package in package_records:
            purl = f"pkg:pypi/{package['name']}@{package['version']}"
            components.append(
                {
                    "type": "library",
                    "bom-ref": purl,
                    "name": package["name"],
                    "version": package["version"],
                    "purl": purl,
                    "hashes": [{"alg": "SHA-256", "content": package["artifactSha256"]}],
                    "licenses": [{"license": {"name": value}} for value in package["licenseMetadata"]],
                }
            )
        sbom = {
            "bomFormat": "CycloneDX",
            "specVersion": "1.6",
            "version": 1,
            "metadata": {
                "component": {
                    "type": "application",
                    "bom-ref": root_ref,
                    "name": "cos-prune-runtime",
                    "version": "1",
                }
            },
            "components": components,
            "dependencies": [
                {"ref": root_ref, "dependsOn": [component["bom-ref"] for component in components]},
                *[{"ref": component["bom-ref"], "dependsOn": []} for component in components],
            ],
        }
        sbom_path = staging / "sbom.cdx.json"
        sbom_path.write_text(json.dumps(sbom, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        os.chmod(sbom_path, 0o600)
        notice = [
            "COS PRUNE PYTHON THIRD-PARTY NOTICES",
            "",
            "Target: CPython 3.10 on Ubuntu 22.04 x86_64",
            f"Requirements SHA-256: {manifest['requirements']['sha256']}",
            "",
        ]
        for package in package_records:
            notice.append(f"- {package['identity']}: {', '.join(package['licenseMetadata'])}")
            for legal in package["legalFiles"]:  # type: ignore[union-attr]
                notice.append(f"  {legal['output']} (sha256:{legal['sha256']})")
        notice_path = staging / "THIRD_PARTY_NOTICES.txt"
        notice_path.write_text("\n".join(notice) + "\n", encoding="utf-8")
        os.chmod(notice_path, 0o600)
        staging.replace(output)
    except Exception:
        shutil.rmtree(staging, ignore_errors=True)
        raise


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--requirements", required=True)
    parser.add_argument("--overrides", required=True)
    parser.add_argument("--site-packages", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--expected-count", type=int, default=12)
    options = parser.parse_args()
    if options.expected_count <= 0 or options.expected_count > 1000:
        parser.error("--expected-count is outside the reviewed range")
    return options


def main() -> int:
    os.umask(0o077)
    try:
        collect(parse_args())
    except CollectionError as error:
        print(f"collect-python-licenses: {error}", file=sys.stderr)
        return 1
    print("collect-python-licenses: complete", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
