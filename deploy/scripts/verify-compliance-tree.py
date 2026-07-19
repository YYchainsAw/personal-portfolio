#!/usr/bin/env python3
"""Verify a published compliance closure without Node, Java, or third-party modules."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import stat
import sys
from pathlib import Path, PurePosixPath
from typing import Any


HEX = re.compile(r"^[0-9a-f]{64}$")
CONFIG_DIGEST = re.compile(r"^sha256:[0-9a-f]{64}$")


class VerificationError(Exception):
    pass


def fail(message: str) -> None:
    raise VerificationError(message)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def strict_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            fail(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def reject_json_constant(value: str) -> None:
    fail(f"non-finite JSON number is forbidden: {value}")


def read_json(path: Path, label: str) -> Any:
    try:
        raw = path.read_bytes()
        text = raw.decode("utf-8", errors="strict")
        return json.loads(text, object_pairs_hook=strict_object, parse_constant=reject_json_constant)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        fail(f"{label} is not strict UTF-8 JSON: {error}")


def safe_relative(value: str) -> bool:
    if not value or value.startswith("/") or "\\" in value or any(ord(char) < 32 or ord(char) == 127 for char in value):
        return False
    parts = PurePosixPath(value).parts
    return bool(parts) and all(part not in ("", ".", "..") for part in parts)


def inspect_tree(root: Path) -> list[str]:
    files: list[str] = []
    for directory, directories, names in os.walk(root, followlinks=False):
        directories.sort(key=lambda value: value.encode("utf-8"))
        names.sort(key=lambda value: value.encode("utf-8"))
        for name in directories + names:
            candidate = Path(directory, name)
            relative = candidate.relative_to(root).as_posix()
            if not safe_relative(relative):
                fail(f"unsafe compliance path: {relative!r}")
            info = candidate.lstat()
            if stat.S_ISLNK(info.st_mode):
                fail(f"symbolic link in compliance tree: {relative}")
            if name == ".tmp" or name.endswith(".tmp") or ".tmp." in name:
                fail(f"temporary path in compliance tree: {relative}")
        for name in names:
            candidate = Path(directory, name)
            info = candidate.lstat()
            if not stat.S_ISREG(info.st_mode):
                fail(f"non-regular compliance entry: {candidate.relative_to(root).as_posix()}")
            files.append(candidate.relative_to(root).as_posix())
    return sorted(files, key=lambda value: value.encode("utf-8"))


def validate_checksums(root: Path, files: list[str]) -> dict[str, str]:
    sums = root / "SHA256SUMS"
    if sums.is_symlink() or not sums.is_file():
        fail("SHA256SUMS is missing or linked")
    try:
        text = sums.read_bytes().decode("utf-8", errors="strict")
    except (OSError, UnicodeDecodeError) as error:
        fail(f"SHA256SUMS is not UTF-8: {error}")
    if not text.endswith("\n") or "\r" in text:
        fail("SHA256SUMS must have a final LF and no CR bytes")
    expected: dict[str, str] = {}
    for line in text[:-1].split("\n"):
        match = re.fullmatch(r"([0-9a-f]{64})  (.+)", line)
        if not match:
            fail(f"invalid SHA256SUMS line: {line!r}")
        digest, relative = match.groups()
        if not safe_relative(relative) or relative == "SHA256SUMS":
            fail(f"unsafe SHA256SUMS path: {relative!r}")
        if relative in expected:
            fail(f"duplicate SHA256SUMS path: {relative}")
        expected[relative] = digest
    listed = list(expected)
    if listed != sorted(listed, key=lambda value: value.encode("utf-8")):
        fail("SHA256SUMS paths are not byte-sorted")
    actual = [value for value in files if value != "SHA256SUMS"]
    if listed != actual:
        fail("SHA256SUMS does not exactly cover the compliance payload")
    for relative, digest in expected.items():
        candidate = root.joinpath(*PurePosixPath(relative).parts)
        try:
            candidate.resolve(strict=True).relative_to(root)
        except (OSError, ValueError):
            fail(f"checksum target escapes the compliance tree: {relative}")
        if sha256(candidate) != digest:
            fail(f"compliance checksum mismatch: {relative}")
    return expected


def canonical_tree_sha(root: Path, files: list[str]) -> str:
    digest = hashlib.sha256()
    for relative in files:
        digest.update(relative.encode("utf-8"))
        digest.update(b"\0")
        digest.update(sha256(root.joinpath(*PurePosixPath(relative).parts)).encode("ascii"))
        digest.update(b"\0")
    return digest.hexdigest()


def require_cyclonedx(document: Any, label: str) -> None:
    if not isinstance(document, dict) or document.get("bomFormat") != "CycloneDX" or document.get("specVersion") != "1.6":
        fail(f"{label} is not CycloneDX 1.6")
    if "serialNumber" in document or isinstance(document.get("metadata"), dict) and "timestamp" in document["metadata"]:
        fail(f"{label} contains non-reproducible metadata")


def validate_python(root: Path, expected: dict[str, str]) -> None:
    base = root / "licenses" / "cos-prune-runtime"
    manifest = read_json(base / "manifest.json", "COS Python manifest")
    sbom = read_json(base / "sbom.cdx.json", "COS Python SBOM")
    require_cyclonedx(sbom, "COS Python SBOM")
    wanted = {
        "certifi==2026.6.17",
        "charset-normalizer==3.4.9",
        "cos-python-sdk-v5==1.9.44",
        "crcmod==1.7",
        "idna==3.18",
        "pycryptodome==3.23.0",
        "requests==2.34.2",
        "six==1.17.0",
        "tencentcloud-sdk-python-common==3.1.135",
        "tencentcloud-sdk-python-sts==3.0.1459",
        "urllib3==2.7.0",
        "xmltodict==1.0.4",
    }
    if (
        not isinstance(manifest, dict)
        or manifest.get("schemaVersion") != 1
        or manifest.get("component") != "cos-prune-runtime"
        or manifest.get("target") != {"os": "ubuntu", "osVersion": "22.04", "architecture": "x86_64", "python": "3.10"}
        or not isinstance(manifest.get("packages"), list)
    ):
        fail("COS Python manifest target/schema changed")
    requirements = manifest.get("requirements")
    lock_path = base / "requirements-cos-prune.txt"
    if not isinstance(requirements, dict):
        fail("COS Python requirements binding is invalid")
    lock_digest = requirements.get("sha256")
    if requirements.get("path") != lock_path.name or not isinstance(lock_digest, str) or sha256(lock_path) != lock_digest:
        fail("COS Python requirements binding differs")
    identities: set[str] = set()
    artifacts: dict[str, str] = {}
    for package in manifest["packages"]:
        if not isinstance(package, dict):
            fail("COS Python package record is not an object")
        identity = f"{package.get('name')}=={package.get('version')}"
        if package.get("identity") != identity or identity in identities or not HEX.fullmatch(str(package.get("artifactSha256", ""))):
            fail(f"invalid COS Python package identity: {identity}")
        legal_files = package.get("legalFiles")
        if not isinstance(legal_files, list) or not legal_files:
            fail(f"COS Python package has no legal file: {identity}")
        for legal in legal_files:
            relative = legal.get("output") if isinstance(legal, dict) else None
            digest = legal.get("sha256") if isinstance(legal, dict) else None
            tree_path = f"licenses/cos-prune-runtime/{relative}"
            if not isinstance(relative, str) or not safe_relative(relative) or not isinstance(digest, str) or expected.get(tree_path) != digest:
                fail(f"COS Python legal binding differs: {identity}")
        identities.add(identity)
        artifacts[identity] = package["artifactSha256"]
    if identities != wanted:
        fail("COS Python package closure changed")
    sbom_identities: set[str] = set()
    components = sbom.get("components")
    if not isinstance(components, list):
        fail("COS Python SBOM components are invalid")
    for component in components:
        if not isinstance(component, dict):
            fail("COS Python SBOM component is invalid")
        match = re.fullmatch(r"pkg:pypi/([^@/?#]+)@([^?#]+)", str(component.get("purl", "")))
        if not match:
            fail("COS Python SBOM contains a non-PyPI component")
        identity = f"{match.group(1)}=={match.group(2)}"
        hashes = {item.get("alg"): item.get("content") for item in component.get("hashes", []) if isinstance(item, dict)}
        if identity in sbom_identities or artifacts.get(identity) != hashes.get("SHA-256"):
            fail(f"COS Python SBOM/license binding differs: {identity}")
        sbom_identities.add(identity)
    if sbom_identities != identities:
        fail("COS Python SBOM identity closure changed")


def validate_oci(root: Path, expected: dict[str, str], release: dict[str, Any]) -> None:
    for name, kind, release_key, archive_key in (
        ("api-image", "api", "apiImageId", "apiImageArchiveSha256"),
        ("postgres-image", "postgres", "postgresImageId", "postgresImageArchiveSha256"),
    ):
        base = root / "licenses" / name
        manifest = read_json(base / "manifest.json", f"{name} manifest")
        metadata = read_json(root / "oci" / f"{name}-metadata.json", f"{name} metadata")
        sbom_path = root / "sbom" / f"{name}.cdx.json"
        sbom = read_json(sbom_path, f"{name} SBOM")
        require_cyclonedx(sbom, f"{name} SBOM")
        if not isinstance(manifest, dict) or not isinstance(metadata, dict):
            fail(f"{name} manifest/metadata is not an object")
        image = manifest.get("image")
        if not isinstance(image, dict):
            fail(f"{name} image binding is invalid")
        if (
            manifest.get("schemaVersion") != 1
            or manifest.get("kind") != kind
            or image.get("configDigest") != release.get(release_key)
            or not CONFIG_DIGEST.fullmatch(str(image.get("configDigest", "")))
            or not HEX.fullmatch(str(image.get("archiveSha256", "")))
            or image.get("archiveSha256") != release.get(archive_key)
            or image.get("sbomSha256") != sha256(sbom_path)
            or not isinstance(manifest.get("dpkgPackages"), list)
            or len(manifest["dpkgPackages"]) < 100
        ):
            fail(f"{name} license/image identity differs from release.json")
        if (
            metadata.get("schemaVersion") != 1
            or metadata.get("archiveFormat") != "docker-save"
            or metadata.get("extractionMode") != "license-evidence-only"
            or metadata.get("configDigest") != image.get("configDigest")
            or metadata.get("archiveSha256") != image.get("archiveSha256")
            or metadata.get("platform") != {"architecture": "amd64", "os": "linux"}
            or not isinstance(metadata.get("layers"), list)
            or not metadata["layers"]
        ):
            fail(f"{name} extraction evidence differs")
        for index, layer in enumerate(metadata["layers"]):
            if (
                not isinstance(layer, dict)
                or layer.get("index") != index
                or not CONFIG_DIGEST.fullmatch(str(layer.get("diffId", "")))
                or not isinstance(layer.get("filesApplied"), int)
                or isinstance(layer.get("filesApplied"), bool)
                or layer["filesApplied"] < 0
            ):
                fail(f"{name} layer evidence is invalid")
        identities: set[str] = set()
        for package in manifest["dpkgPackages"]:
            identity = package.get("identity") if isinstance(package, dict) else None
            legal = package.get("legalFile") if isinstance(package, dict) else None
            digest = package.get("legalSha256") if isinstance(package, dict) else None
            tree_path = f"licenses/{name}/{legal}"
            if not isinstance(identity, str) or identity in identities or not isinstance(legal, str) or not safe_relative(legal) or expected.get(tree_path) != digest:
                fail(f"{name} dpkg legal closure differs")
            identities.add(identity)

        jre_legal = manifest.get("jreLegalFiles")
        reviewed = manifest.get("reviewedPackages")
        scripts = manifest.get("scripts")
        if not isinstance(jre_legal, list) or not isinstance(reviewed, list) or not isinstance(scripts, list):
            fail(f"{name} reviewed legal collections are invalid")
        for record in jre_legal:
            image_path = record.get("path") if isinstance(record, dict) else None
            digest = record.get("sha256") if isinstance(record, dict) else None
            prefix = "/opt/java/openjdk/"
            if not isinstance(image_path, str) or not image_path.startswith(prefix):
                fail(f"{name} JRE legal path is invalid")
            relative = image_path[len(prefix) :]
            tree_path = f"licenses/{name}/licenses/jre/{relative}"
            if not safe_relative(relative) or expected.get(tree_path) != digest:
                fail(f"{name} JRE legal binding differs")
        for record in reviewed:
            output = record.get("output") if isinstance(record, dict) else None
            digest = record.get("licenseSha256") if isinstance(record, dict) else None
            if not isinstance(output, str) or not safe_relative(output) or expected.get(f"licenses/{name}/{output}") != digest:
                fail(f"{name} reviewed package legal binding differs")
        for record in scripts:
            output = record.get("output") if isinstance(record, dict) else None
            artifact_digest = record.get("sha256") if isinstance(record, dict) else None
            license_output = record.get("licenseOutput") if isinstance(record, dict) else None
            license_digest = record.get("licenseSha256") if isinstance(record, dict) else None
            if (
                not isinstance(output, str)
                or not safe_relative(output)
                or expected.get(f"licenses/{name}/{output}") != artifact_digest
                or not isinstance(license_output, str)
                or not safe_relative(license_output)
                or expected.get(f"licenses/{name}/{license_output}") != license_digest
            ):
                fail(f"{name} reviewed runtime script binding differs")
        if kind == "api":
            binding = manifest.get("backendBinding")
            fat_jar = binding.get("fatJar") if isinstance(binding, dict) else None
            if (
                not isinstance(binding, dict)
                or not isinstance(fat_jar, dict)
                or fat_jar.get("path") != "/app/portfolio-server.jar"
                or fat_jar.get("sha256") != release.get("jarSha256")
                or not binding.get("runtimePackages")
                or expected.get(f"licenses/{name}/licenses/backend/manifest.json") != binding.get("manifestSha256")
                or expected.get(f"licenses/{name}/licenses/backend/THIRD_PARTY_NOTICES.txt") != binding.get("noticesSha256")
            ):
                fail("API image backend/JAR binding is incomplete")


def validate(tree_value: str, release_value: str) -> int:
    requested = Path(tree_value)
    if requested.is_symlink() or not requested.is_dir():
        fail("tree must be a non-symlink directory")
    root = requested.resolve(strict=True)
    files = inspect_tree(root)
    expected = validate_checksums(root, files)
    required_files = {
        "ASSET_PROVENANCE.md",
        "SHA256SUMS",
        "THIRD_PARTY_NOTICES.txt",
        "asset-provenance.json",
        "license-overrides.json",
        "oci-legal-overrides.json",
        "python-legal-overrides.json",
        "toolchain.env",
    }
    required_directories = {"licenses", "oci", "sbom"}
    entries = {entry.name: entry for entry in root.iterdir()}
    if set(entries) != required_files | required_directories:
        fail("compliance top-level inventory differs")
    if any(not entries[name].is_file() or entries[name].is_symlink() for name in required_files) or any(
        not entries[name].is_dir() or entries[name].is_symlink() for name in required_directories
    ):
        fail("compliance top-level entry types differ")
    license_entries = {entry.name: entry for entry in (root / "licenses").iterdir()}
    license_roots = {"frontend", "admin-web", "backend", "cos-prune-runtime", "api-image", "postgres-image"}
    if set(license_entries) != license_roots or any(
        not entry.is_dir() or entry.is_symlink() for entry in license_entries.values()
    ):
        fail("six-class compliance license closure is incomplete")
    release_path = Path(release_value)
    if release_path.is_symlink() or not release_path.is_file():
        fail("release.json must be a regular non-symlink file")
    release = read_json(release_path, "release.json")
    if not isinstance(release, dict) or not HEX.fullmatch(str(release.get("complianceTreeSha256", ""))):
        fail("release.json has no compliance tree identity")
    if canonical_tree_sha(root, files) != release["complianceTreeSha256"]:
        fail("compliance canonical tree hash differs from release.json")
    assets = read_json(root / "asset-provenance.json", "asset provenance")
    asset_records = assets.get("assets") if isinstance(assets, dict) else None
    if not isinstance(assets, dict) or assets.get("schemaVersion") != 2 or not isinstance(asset_records, list) or len(asset_records) != 5:
        fail("asset provenance schema/count changed")
    for asset in asset_records:
        if not isinstance(asset, dict):
            fail("asset provenance record is invalid")
        if asset.get("license") in ("LicenseRef-Pexels", "LicenseRef-Unsplash") and (not asset.get("credit") or not asset.get("sourceUrl")):
            fail("third-party asset provenance is incomplete")
    for name in ("frontend", "admin-web", "backend", "api-image", "postgres-image"):
        require_cyclonedx(read_json(root / "sbom" / f"{name}.cdx.json", f"{name} SBOM"), f"{name} SBOM")
    validate_python(root, expected)
    validate_oci(root, expected, release)
    print(f"verify-compliance-tree: PASS ({len(expected)} payload files)", file=sys.stderr)
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tree", required=True)
    parser.add_argument("--release-json", required=True)
    return parser.parse_args()


def main() -> int:
    try:
        options = parse_args()
        return validate(options.tree, options.release_json)
    except VerificationError as error:
        print(f"verify-compliance-tree: FAIL: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
