#!/usr/bin/env python3
"""Fail-closed Tencent COS version-aware backup retention guard.

The public commands are invoked by prune-remote.sh.  Cloud access is isolated
behind a small JSON protocol so the state machine can be tested without a
network or credentials.  In production the built-in provider uses Tencent's
official COS and STS Python SDKs.
"""

from __future__ import annotations

import argparse
import base64
import email.utils
import hashlib
import importlib.metadata
import json
import os
import re
import secrets
import stat
import subprocess
import sys
import urllib.request
import urllib.parse
import xml.etree.ElementTree as ET
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable


MAX_API_OUTPUT_BYTES = 8 * 1024 * 1024
MAX_MANIFEST_BYTES = 2 * 1024 * 1024
MAX_MARKER_BYTES = 1024
MAX_VERSIONS = 100_000
MAX_PAGES = 10_000
LEGAL_HOLD_CAPABILITY = "NOT_SUPPORTED_BY_TENCENT_COS_API_2026-06-03"
SET_FILENAMES = {
    "VERIFIED",
    "database.dump.age",
    "local-media.tar.age",
    "media-manifest.json.age",
    "set-manifest.json",
}
SET_ID_RE = re.compile(r"^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{12}$")
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
RELEASE_ID_RE = re.compile(r"^[0-9a-f]{12}-[0-9a-f]{12}$")
SNAPSHOT_ID_RE = re.compile(r"^[A-Za-z0-9-]{8,128}$")
SAFE_RELATIVE_RE = re.compile(r"^[A-Za-z0-9._/-]+$")
REGION_RE = re.compile(r"^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$")
BUCKET_RE = re.compile(r"^[a-z0-9][a-z0-9.-]{1,126}[a-z0-9]$")
IDENTITY_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.:@/-]{1,255}$")
VERSION_ID_RE = re.compile(r"^[^\x00-\x20\x7f]{1,512}$")
PRODUCTION_PYTHON = "/opt/portfolio/cos-prune-venv/bin/python3"
PROVIDER_DEPENDENCY_VERSIONS = {
    "certifi": "2026.6.17",
    "charset-normalizer": "3.4.9",
    "cos-python-sdk-v5": "1.9.44",
    "crcmod": "1.7",
    "idna": "3.18",
    "pycryptodome": "3.23.0",
    "requests": "2.34.2",
    "six": "1.17.0",
    "tencentcloud-sdk-python-common": "3.1.135",
    "tencentcloud-sdk-python-sts": "3.0.1459",
    "urllib3": "2.7.0",
    "xmltodict": "1.0.4",
}
PROVIDER_LOCK_SHA256 = "96d0cf7823a4f32f2fbb97e94919882827fd8aa9cd153f8395ef26459a19ff4a"
TEST_SOURCE_ROOT = Path("/tmp/portfolio-cos-prune-test-source")
TEST_PROVIDER_ROOT = Path("/tmp/portfolio-cos-prune-tests")


class GuardError(Exception):
    """An intentionally sanitized fail-closed error."""


def fail(message: str) -> None:
    raise GuardError(message)


def exact_keys(value: Any, expected: Iterable[str], label: str) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != set(expected):
        fail(f"{label} has an unsupported schema")
    return value


def canonical_bytes(value: Any) -> bytes:
    return json.dumps(
        value, ensure_ascii=True, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while True:
            block = stream.read(1024 * 1024)
            if not block:
                break
            digest.update(block)
    return digest.hexdigest()


def parse_rfc3339(value: Any, label: str) -> datetime:
    if not isinstance(value, str) or len(value) > 40:
        fail(f"{label} is not a bounded UTC timestamp")
    candidate = value
    if candidate.endswith("Z"):
        candidate = candidate[:-1] + "+00:00"
    try:
        parsed = datetime.fromisoformat(candidate)
    except ValueError:
        fail(f"{label} is not a valid UTC timestamp")
    if parsed.tzinfo is None or parsed.utcoffset() != timezone.utc.utcoffset(parsed):
        fail(f"{label} is not UTC")
    return parsed.astimezone(timezone.utc)


def parse_http_date(value: Any, label: str) -> datetime:
    if not isinstance(value, str) or len(value) > 80:
        fail(f"{label} is not a bounded HTTP date")
    try:
        parsed = email.utils.parsedate_to_datetime(value)
    except (TypeError, ValueError):
        fail(f"{label} is not a valid HTTP date")
    if parsed is None or parsed.tzinfo is None:
        fail(f"{label} lacks a timezone")
    return parsed.astimezone(timezone.utc)


def is_safe_relative(value: str) -> bool:
    return bool(
        value
        and not value.startswith("/")
        and "\\" not in value
        and "//" not in value
        and SAFE_RELATIVE_RE.fullmatch(value)
        and all(part not in {"", ".", ".."} for part in value.split("/"))
    )


def test_mode() -> bool:
    value = os.environ.get("BACKUP_PRUNE_TEST_MODE", "")
    if value not in {"", "1"}:
        fail("BACKUP_PRUNE_TEST_MODE is invalid")
    if value != "1":
        return False
    source = Path(__file__)
    try:
        root_info = TEST_SOURCE_ROOT.lstat()
        source_info = source.lstat()
        root = TEST_SOURCE_ROOT.resolve(strict=True)
        resolved = source.resolve(strict=True)
        relative = resolved.relative_to(root)
    except (OSError, ValueError):
        fail("BACKUP_PRUNE_TEST_MODE is forbidden outside the test source boundary")
    if (
        TEST_SOURCE_ROOT.is_symlink()
        or not stat.S_ISDIR(root_info.st_mode)
        or root_info.st_uid != os.geteuid()
        or root_info.st_mode & 0o077
        or source.is_symlink()
        or not stat.S_ISREG(source_info.st_mode)
        or source_info.st_nlink != 1
        or source_info.st_uid != os.geteuid()
        or source_info.st_mode & 0o022
        or relative.as_posix() != "deploy/backup/cos-prune-guard.py"
    ):
        fail("BACKUP_PRUNE_TEST_MODE is forbidden outside the test source boundary")
    return True


def secure_test_provider_command(path: Path) -> Path:
    """Constrain the injectable offline provider to a private test-only root."""
    try:
        root_info = TEST_PROVIDER_ROOT.lstat()
        root = TEST_PROVIDER_ROOT.resolve(strict=True)
        resolved = path.resolve(strict=True)
        resolved.relative_to(root)
    except (OSError, ValueError):
        fail("COS prune API command is outside the test fixture boundary")
    if (
        TEST_PROVIDER_ROOT.is_symlink()
        or not stat.S_ISDIR(root_info.st_mode)
        or root_info.st_uid != os.geteuid()
        or root_info.st_mode & 0o077
    ):
        fail("COS prune API test fixture root is unsafe")
    return path


def ticket_clock() -> datetime:
    override = os.environ.get("BACKUP_PRUNE_TEST_NOW", "")
    if not override:
        return datetime.now(timezone.utc)
    if not test_mode():
        fail("BACKUP_PRUNE_TEST_NOW is forbidden outside test mode")
    return parse_rfc3339(override, "test ticket clock")


def provider_dependency_version_gate() -> None:
    observed: dict[str, str] = {}
    for distribution in importlib.metadata.distributions():
        raw_name = distribution.metadata.get("Name")
        if not isinstance(raw_name, str) or not raw_name:
            fail("isolated runtime contains an unnamed distribution")
        name = re.sub(r"[-_.]+", "-", raw_name).lower()
        if name in observed:
            fail("isolated runtime contains duplicate provider distributions")
        observed[name] = distribution.version
    if observed.keys() != PROVIDER_DEPENDENCY_VERSIONS.keys():
        fail("isolated runtime distribution closure differs from the reviewed lock")
    if observed != PROVIDER_DEPENDENCY_VERSIONS:
        fail("provider dependency version differs from the reviewed lock")


def runtime_gate() -> None:
    if test_mode():
        return
    executable = Path(sys.executable)
    if (
        str(executable) != PRODUCTION_PYTHON
        or tuple(sys.version_info[:2]) != (3, 10)
        or Path(sys.prefix) != Path(PRODUCTION_PYTHON).parent.parent
        or sys.prefix == sys.base_prefix
    ):
        fail("prune guard is not running in its exact isolated Python 3.10 runtime")
    try:
        executable_target = executable.resolve(strict=True)
        executable_info = executable_target.stat()
        prefix_info = Path(sys.prefix).stat()
    except OSError:
        fail("prune guard isolated interpreter metadata is unavailable")
    if (
        executable_info.st_uid != 0
        or executable_info.st_mode & 0o022
        or prefix_info.st_uid != 0
        or prefix_info.st_mode & 0o077
    ):
        fail("prune guard isolated interpreter ownership is unsafe")
    marker_path = Path(sys.prefix) / ".portfolio-runtime.json"
    marker = exact_keys(
        load_json_file(
            secure_regular_file(
                str(marker_path), "prune guard runtime marker", private=True
            ),
            "prune guard runtime marker",
            4096,
        ),
        ["dependencyVersions", "lockSha256", "pythonAbi", "schemaVersion"],
        "prune guard runtime marker",
    )
    if marker != {
        "schemaVersion": 2,
        "lockSha256": PROVIDER_LOCK_SHA256,
        "pythonAbi": "3.10",
        "dependencyVersions": PROVIDER_DEPENDENCY_VERSIONS,
    }:
        fail("prune guard runtime marker differs from the reviewed lock")
    provider_dependency_version_gate()


def secure_regular_file(path_value: str, label: str, *, private: bool) -> Path:
    path = Path(path_value)
    if not path.is_absolute():
        fail(f"{label} must be absolute")
    try:
        info = path.lstat()
    except OSError:
        fail(f"{label} is unavailable")
    if not stat.S_ISREG(info.st_mode) or stat.S_ISLNK(info.st_mode):
        fail(f"{label} must be a regular file")
    if info.st_nlink != 1:
        fail(f"{label} must not have hard links")
    forbidden = 0o077 if private else 0o022
    if info.st_mode & forbidden:
        fail(f"{label} has unsafe permissions")
    expected_uid = os.geteuid()
    if info.st_uid != expected_uid:
        fail(f"{label} has an unexpected owner")
    if not test_mode() and expected_uid != 0:
        fail("the production prune guard must run as root")
    return path


def load_json_file(path: Path, label: str, maximum: int) -> Any:
    try:
        size = path.stat().st_size
        if size <= 0 or size > maximum:
            fail(f"{label} has an invalid byte size")
        with path.open("r", encoding="utf-8") as stream:
            return json.load(stream)
    except GuardError:
        raise
    except (OSError, UnicodeError, json.JSONDecodeError):
        fail(f"{label} is not valid JSON")


def require_environment(name: str) -> str:
    value = os.environ.get(name, "")
    if not value or any(character in value for character in "\r\n\x00"):
        fail(f"{name} is required and must be single-line")
    return value


@dataclass(frozen=True, order=True)
class ObjectVersion:
    key: str
    versionId: str
    entryType: str
    isLatest: bool
    lastModified: str
    size: int
    etag: str | None


def version_from_json(value: Any, expected_prefix: str) -> ObjectVersion:
    document = exact_keys(
        value,
        [
            "entryType",
            "etag",
            "isLatest",
            "key",
            "lastModified",
            "size",
            "versionId",
        ],
        "COS version entry",
    )
    key = document["key"]
    version_id = document["versionId"]
    entry_type = document["entryType"]
    etag = document["etag"]
    if (
        not isinstance(key, str)
        or not is_safe_relative(key)
        or not key.startswith(expected_prefix)
    ):
        fail("COS version listing escaped the exact reviewed prefix")
    if not isinstance(version_id, str) or not VERSION_ID_RE.fullmatch(version_id):
        fail("COS returned an unusable or null object version ID")
    if entry_type not in {"OBJECT", "DELETE_MARKER"}:
        fail("COS returned an unknown version entry type")
    if not isinstance(document["isLatest"], bool):
        fail("COS version entry has an invalid latest flag")
    parse_rfc3339(document["lastModified"], "COS version modification time")
    size = document["size"]
    if not isinstance(size, int) or isinstance(size, bool) or size < 0:
        fail("COS version entry has an invalid byte size")
    if entry_type == "OBJECT":
        if not isinstance(etag, str) or not etag or len(etag) > 256:
            fail("COS object version has an invalid ETag")
    elif etag is not None:
        fail("COS delete marker unexpectedly carried an ETag")
    return ObjectVersion(
        key=key,
        versionId=version_id,
        entryType=entry_type,
        isLatest=document["isLatest"],
        lastModified=document["lastModified"],
        size=size,
        etag=etag,
    )


@dataclass(frozen=True)
class Invocation:
    remote: str
    prefix: str
    account: str
    bucket: str
    region: str
    principal: str
    rclone_config: Path

    @property
    def sets_prefix(self) -> str:
        return f"{self.prefix}/sets/"

    @property
    def blobs_prefix(self) -> str:
        return f"{self.prefix}/blobs/"


def invocation_from_args(args: argparse.Namespace) -> Invocation:
    expected = {
        "remote": require_environment("BACKUP_REMOTE"),
        "prefix": require_environment("BACKUP_PREFIX").rstrip("/"),
        "account": require_environment("BACKUP_DESTINATION_ACCOUNT_ID"),
        "bucket": require_environment("BACKUP_DESTINATION_BUCKET"),
        "region": require_environment("BACKUP_DESTINATION_REGION"),
        "principal": require_environment("BACKUP_PRUNE_PRINCIPAL_ID"),
        "rclone_config": require_environment("BACKUP_PRUNE_RCLONE_CONFIG"),
    }
    for name, expected_value in expected.items():
        observed = str(getattr(args, name))
        if observed != expected_value:
            fail(f"{name} does not match the protected destination allowlist")
    if not BUCKET_RE.fullmatch(args.bucket):
        fail("destination bucket is invalid")
    if not REGION_RE.fullmatch(args.region):
        fail("destination region is invalid")
    if not IDENTITY_RE.fullmatch(args.account) or not IDENTITY_RE.fullmatch(args.principal):
        fail("destination account or principal identity is invalid")
    if not is_safe_relative(args.prefix) or args.prefix in {".", "/"}:
        fail("destination prefix is not a canonical non-root prefix")
    remote_match = re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,63}:([^/]+)", args.remote)
    if not remote_match or remote_match.group(1) != args.bucket:
        fail("rclone remote is not exactly bound to the destination bucket root")
    rclone_config = secure_regular_file(args.rclone_config, "pruner rclone config", private=True)
    if require_environment("BACKUP_OPERATION_LOCK_HELD") != "true":
        fail("the shared backup/prune operation lock is not held")
    return Invocation(
        remote=args.remote,
        prefix=args.prefix,
        account=args.account,
        bucket=args.bucket,
        region=args.region,
        principal=args.principal,
        rclone_config=rclone_config,
    )


class CosApi:
    def __init__(self) -> None:
        configured = os.environ.get("BACKUP_COS_PRUNE_API_COMMAND", "")
        if configured:
            if not test_mode():
                fail("custom COS prune API commands are forbidden in production")
            command = secure_regular_file(configured, "COS prune API command", private=False)
            secure_test_provider_command(command)
            if not os.access(command, os.X_OK):
                fail("COS prune API command is not executable")
            self.command = [str(command)]
        else:
            self.command = [sys.executable, str(Path(__file__).resolve()), "__provider"]

    def call(self, operation: str, payload: dict[str, Any]) -> Any:
        environment = dict(os.environ)
        for name in (
            "ALL_PROXY",
            "HTTPS_PROXY",
            "HTTP_PROXY",
            "NO_PROXY",
            "all_proxy",
            "https_proxy",
            "http_proxy",
            "no_proxy",
        ):
            environment.pop(name, None)
        try:
            result = subprocess.run(
                [*self.command, operation],
                input=canonical_bytes(payload),
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                check=False,
                timeout=120,
                env=environment,
            )
        except (OSError, subprocess.SubprocessError):
            fail("Tencent COS provider command could not be executed")
        if result.returncode != 0:
            fail("Tencent COS provider operation failed closed")
        if not result.stdout or len(result.stdout) > MAX_API_OUTPUT_BYTES:
            fail("Tencent COS provider returned an invalid response size")
        if result.stderr:
            fail("Tencent COS provider returned unexpected diagnostics")
        try:
            return json.loads(result.stdout.decode("utf-8"))
        except (UnicodeError, json.JSONDecodeError):
            fail("Tencent COS provider returned malformed JSON")


def inspect_destination(api: CosApi, invocation: Invocation) -> dict[str, Any]:
    response = exact_keys(
        api.call(
            "inspect-destination",
            {
                "schemaVersion": 1,
                "accountId": invocation.account,
                "bucket": invocation.bucket,
                "prefix": invocation.prefix,
                "principalId": invocation.principal,
                "region": invocation.region,
            },
        ),
        [
            "accountId",
            "bucket",
            "legalHoldCapability",
            "objectLock",
            "prefix",
            "principalArn",
            "principalId",
            "principalType",
            "region",
            "schemaVersion",
            "versioning",
        ],
        "COS destination inspection",
    )
    if response["schemaVersion"] != 1:
        fail("COS destination inspection schema version is unsupported")
    for field, expected in (
        ("accountId", invocation.account),
        ("bucket", invocation.bucket),
        ("prefix", invocation.prefix),
        ("principalId", invocation.principal),
        ("region", invocation.region),
    ):
        if response[field] != expected:
            fail("COS destination identity does not match its exact allowlist")
    if response["principalType"] not in {
        "CAMUser",
        "CAMRole",
        "FederatedUser",
    }:
        fail("COS pruner principal type is not allowlisted")
    if (
        not isinstance(response["principalArn"], str)
        or len(response["principalArn"]) > 512
        or not response["principalArn"].startswith("qcs::")
    ):
        fail("COS pruner principal ARN is invalid")
    if response["versioning"] != "Enabled":
        fail("COS destination versioning is not enabled")
    if response["legalHoldCapability"] != LEGAL_HOLD_CAPABILITY:
        fail("COS legal-hold capability changed and requires independent review")
    lock = exact_keys(response["objectLock"], ["defaultRetentionDays", "enabled", "mode"], "COS Object Lock")
    minimum_days_text = os.environ.get(
        "BACKUP_COS_OBJECT_LOCK_MINIMUM_DAYS",
        os.environ.get("BACKUP_RETENTION_SAFETY_DAYS", "14"),
    )
    if not re.fullmatch(r"[1-9][0-9]{0,4}", minimum_days_text):
        fail("minimum COS Object Lock retention is invalid")
    minimum_days = int(minimum_days_text)
    if (
        lock["enabled"] is not True
        or lock["mode"] != "COMPLIANCE"
        or not isinstance(lock["defaultRetentionDays"], int)
        or isinstance(lock["defaultRetentionDays"], bool)
        or lock["defaultRetentionDays"] < minimum_days
        or lock["defaultRetentionDays"] > 36500
    ):
        fail("COS destination lacks the required COMPLIANCE Object Lock default")
    return response


def list_versions(api: CosApi, invocation: Invocation, prefix: str) -> tuple[ObjectVersion, ...]:
    if not prefix.startswith(f"{invocation.prefix}/") or not is_safe_relative(prefix.rstrip("/")):
        fail("COS version query prefix escaped the reviewed namespace")
    key_marker = ""
    version_marker = ""
    seen_markers: set[tuple[str, str]] = set()
    observed: dict[tuple[str, str, str], ObjectVersion] = {}
    for _page_number in range(MAX_PAGES):
        marker_pair = (key_marker, version_marker)
        if marker_pair in seen_markers:
            fail("COS version pagination repeated a marker")
        seen_markers.add(marker_pair)
        response = exact_keys(
            api.call(
                "list-versions",
                {
                    "schemaVersion": 1,
                    "bucket": invocation.bucket,
                    "region": invocation.region,
                    "prefix": prefix,
                    "keyMarker": key_marker,
                    "versionIdMarker": version_marker,
                    "maxKeys": 1000,
                },
            ),
            [
                "bucket",
                "entries",
                "isTruncated",
                "keyMarker",
                "nextKeyMarker",
                "nextVersionIdMarker",
                "prefix",
                "region",
                "schemaVersion",
                "versionIdMarker",
            ],
            "COS version page",
        )
        if (
            response["schemaVersion"] != 1
            or response["bucket"] != invocation.bucket
            or response["region"] != invocation.region
            or response["prefix"] != prefix
            or response["keyMarker"] != key_marker
            or response["versionIdMarker"] != version_marker
            or not isinstance(response["entries"], list)
            or not isinstance(response["isTruncated"], bool)
        ):
            fail("COS version page identity or shape changed")
        for raw_entry in response["entries"]:
            entry = version_from_json(raw_entry, prefix)
            identity = (entry.key, entry.versionId, entry.entryType)
            if identity in observed:
                fail("COS version pagination returned a duplicate entry")
            observed[identity] = entry
            if len(observed) > MAX_VERSIONS:
                fail("COS version snapshot exceeds the reviewed safety bound")
        next_key = response["nextKeyMarker"]
        next_version = response["nextVersionIdMarker"]
        if response["isTruncated"]:
            if (
                not isinstance(next_key, str)
                or not next_key
                or len(next_key) > MAX_MARKER_BYTES
                or not is_safe_relative(next_key)
                or not next_key.startswith(prefix)
                or not isinstance(next_version, str)
                or len(next_version) > MAX_MARKER_BYTES
                or (next_version != "" and not VERSION_ID_RE.fullmatch(next_version))
                or (next_key, next_version) == marker_pair
            ):
                fail("COS truncated version page omitted a progressive marker pair")
            key_marker, version_marker = next_key, next_version
            continue
        if next_key not in {None, ""} or next_version not in {None, ""}:
            fail("COS terminal version page carried unexpected continuation markers")
        return tuple(sorted(observed.values()))
    fail("COS version pagination exceeded the reviewed page bound")


def stable_versions(api: CosApi, invocation: Invocation, prefix: str) -> tuple[ObjectVersion, ...]:
    first = list_versions(api, invocation, prefix)
    second = list_versions(api, invocation, prefix)
    if first != second:
        fail("COS version state changed during review")
    return first


def read_version(
    api: CosApi,
    invocation: Invocation,
    version: ObjectVersion,
    maximum: int,
) -> bytes:
    response = exact_keys(
        api.call(
            "read-version",
            {
                "schemaVersion": 1,
                "bucket": invocation.bucket,
                "region": invocation.region,
                "key": version.key,
                "versionId": version.versionId,
                "maxBytes": maximum,
            },
        ),
        ["bodyBase64", "byteSize", "key", "schemaVersion", "sha256", "versionId"],
        "COS version read-back",
    )
    if (
        response["schemaVersion"] != 1
        or response["key"] != version.key
        or response["versionId"] != version.versionId
        or not isinstance(response["byteSize"], int)
        or isinstance(response["byteSize"], bool)
        or response["byteSize"] != version.size
        or response["byteSize"] > maximum
        or not isinstance(response["sha256"], str)
        or not SHA256_RE.fullmatch(response["sha256"])
        or not isinstance(response["bodyBase64"], str)
    ):
        fail("COS version read-back identity or metadata changed")
    try:
        body = base64.b64decode(response["bodyBase64"], validate=True)
    except (ValueError, TypeError):
        fail("COS version read-back body is not canonical base64")
    if (
        len(body) != response["byteSize"]
        or sha256_bytes(body) != response["sha256"]
    ):
        fail("COS version read-back checksum or size did not verify")
    return body


def validate_manifest(value: Any, expected_set_id: str) -> dict[str, Any]:
    document = exact_keys(
        value,
        [
            "artifacts",
            "backupFinishedAt",
            "backupStartedAt",
            "blobs",
            "counts",
            "releaseId",
            "retention",
            "schemaVersion",
            "setId",
            "snapshotId",
        ],
        "backup set manifest",
    )
    if document["schemaVersion"] != 1 or document["setId"] != expected_set_id:
        fail("backup set manifest identity is invalid")
    if not isinstance(document["releaseId"], str) or not RELEASE_ID_RE.fullmatch(document["releaseId"]):
        fail("backup set manifest release ID is invalid")
    if not isinstance(document["snapshotId"], str) or not SNAPSHOT_ID_RE.fullmatch(document["snapshotId"]):
        fail("backup set manifest snapshot ID is invalid")
    started = parse_rfc3339(document["backupStartedAt"], "backup start time")
    finished = parse_rfc3339(document["backupFinishedAt"], "backup finish time")
    if finished < started:
        fail("backup set finish time precedes its start time")

    retention = exact_keys(document["retention"], ["daily", "monthly", "weekly"], "backup retention flags")
    if retention["daily"] is not True or not isinstance(retention["weekly"], bool) or not isinstance(retention["monthly"], bool):
        fail("backup set retention flags are invalid")

    counts = exact_keys(
        document["counts"],
        [
            "cosObjects",
            "cosVerificationSamples",
            "distinctCosBlobs",
            "localObjects",
            "totalObjects",
        ],
        "backup set counts",
    )
    for key, number in counts.items():
        if not isinstance(number, int) or isinstance(number, bool) or number < 0:
            fail(f"backup set count {key} is invalid")
    if counts["totalObjects"] != counts["localObjects"] + counts["cosObjects"]:
        fail("backup set provider counts do not close")
    if counts["distinctCosBlobs"] > counts["cosObjects"]:
        fail("backup set distinct COS blob count is impossible")
    if counts["cosVerificationSamples"] > counts["distinctCosBlobs"]:
        fail("backup set COS verification sample count is impossible")

    artifacts = exact_keys(
        document["artifacts"],
        ["databaseDump", "localMediaTar", "mediaManifest"],
        "backup set artifacts",
    )
    artifact_names = {
        "databaseDump": "database.dump.age",
        "localMediaTar": "local-media.tar.age",
        "mediaManifest": "media-manifest.json.age",
    }
    for name, filename in artifact_names.items():
        artifact = exact_keys(
            artifacts[name],
            ["byteSize", "ciphertextSha256", "file"],
            f"backup artifact {name}",
        )
        if artifact["file"] != filename:
            fail("backup artifact filename is not canonical")
        if not isinstance(artifact["ciphertextSha256"], str) or not SHA256_RE.fullmatch(artifact["ciphertextSha256"]):
            fail("backup artifact checksum is invalid")
        if not isinstance(artifact["byteSize"], int) or isinstance(artifact["byteSize"], bool) or artifact["byteSize"] <= 0:
            fail("backup artifact byte size is invalid")

    blobs = document["blobs"]
    if not isinstance(blobs, list):
        fail("backup set blob closure is not an array")
    canonical_paths: list[str] = []
    for raw_blob in blobs:
        blob = exact_keys(raw_blob, ["byteSize", "path", "sha256"], "backup set blob")
        if not isinstance(blob["sha256"], str) or not SHA256_RE.fullmatch(blob["sha256"]):
            fail("backup set blob checksum is invalid")
        if blob["path"] != f"blobs/{blob['sha256']}":
            fail("backup set blob path is not content-addressed")
        if not isinstance(blob["byteSize"], int) or isinstance(blob["byteSize"], bool) or blob["byteSize"] <= 0:
            fail("backup set blob byte size is invalid")
        canonical_paths.append(blob["path"])
    if canonical_paths != sorted(set(canonical_paths)):
        fail("backup set blob closure is not sorted and unique")
    if len(blobs) != counts["distinctCosBlobs"]:
        fail("backup set blob closure count does not match its declared count")
    return document


@dataclass(frozen=True)
class SetRecord:
    set_id: str
    manifest_sha256: str
    manifest: dict[str, Any]
    object_namespace: str
    versions: tuple[ObjectVersion, ...]
    object_versions: tuple[ObjectVersion, ...]


@dataclass(frozen=True)
class BlobRecord:
    path: str
    sha256: str
    byte_size: int
    version: ObjectVersion


def one_current_object(entries: list[ObjectVersion], label: str) -> ObjectVersion:
    if len(entries) != 1:
        fail(f"{label} does not have exactly one immutable version")
    entry = entries[0]
    if entry.entryType != "OBJECT" or entry.isLatest is not True:
        fail(f"{label} has a delete marker, historical version, or non-current version")
    return entry


def load_set_record(
    api: CosApi,
    invocation: Invocation,
    set_id: str,
    entries: tuple[ObjectVersion, ...],
    expected_manifest_sha: str,
    object_namespace: str,
) -> SetRecord:
    if not SET_ID_RE.fullmatch(set_id):
        fail("remote set namespace contains an invalid set ID")
    completed_root = f"{invocation.sets_prefix}{set_id}/"
    candidate_entries = entries
    completion_versions: tuple[ObjectVersion, ...]
    if object_namespace == "uploading":
        if len(entries) != 1 or entries[0].key != completed_root + "VERIFIED":
            fail("atomic completed set contains more than its completion record")
        completion = one_current_object(list(entries), "completed-set record")
        completion_body = read_version(
            api, invocation, completion, MAX_MANIFEST_BYTES
        )
        try:
            completion_document = exact_keys(
                json.loads(completion_body.decode("utf-8")),
                [
                    "manifestByteSize",
                    "manifestSha256",
                    "schemaVersion",
                    "setId",
                    "uploadPrefix",
                ],
                "completed-set record",
            )
        except (UnicodeError, json.JSONDecodeError):
            fail("completed-set record is not valid UTF-8 JSON")
        if (
            completion_document["schemaVersion"] != 2
            or completion_document["setId"] != set_id
            or completion_document["uploadPrefix"] != f"uploading/{set_id}"
            or completion_document["manifestSha256"] != expected_manifest_sha
            or not isinstance(completion_document["manifestByteSize"], int)
            or isinstance(completion_document["manifestByteSize"], bool)
            or completion_document["manifestByteSize"] <= 0
            or completion_document["manifestByteSize"] > MAX_MANIFEST_BYTES
        ):
            fail("completed-set record does not bind the exact upload closure")
        expected_root = f"{invocation.prefix}/uploading/{set_id}/"
        candidate_entries = stable_versions(api, invocation, expected_root)
        completion_versions = (completion,)
    elif object_namespace == "sets":
        expected_root = completed_root
        completion_versions = entries
        completion_document = None
    else:
        fail("GC proposal set object namespace is unsupported")

    by_filename: dict[str, list[ObjectVersion]] = {}
    for entry in candidate_entries:
        if not entry.key.startswith(expected_root):
            fail("remote set version escaped its set directory")
        filename = entry.key[len(expected_root) :]
        if filename not in SET_FILENAMES or "/" in filename:
            fail("remote set contains an unexpected object key")
        by_filename.setdefault(filename, []).append(entry)
    if set(by_filename) != SET_FILENAMES:
        fail("remote set is incomplete")
    selected = {
        filename: one_current_object(values, f"set object {filename}")
        for filename, values in by_filename.items()
    }
    manifest_body = read_version(
        api, invocation, selected["set-manifest.json"], MAX_MANIFEST_BYTES
    )
    if sha256_bytes(manifest_body) != expected_manifest_sha:
        fail("remote set manifest does not match the policy snapshot")
    if (
        object_namespace == "uploading"
        and len(manifest_body) != completion_document["manifestByteSize"]
    ):
        fail("completed-set record manifest size changed")
    try:
        manifest_value = json.loads(manifest_body.decode("utf-8"))
    except (UnicodeError, json.JSONDecodeError):
        fail("remote set manifest is not valid UTF-8 JSON")
    manifest = validate_manifest(manifest_value, set_id)
    marker_body = read_version(api, invocation, selected["VERIFIED"], MAX_MARKER_BYTES)
    if marker_body != (expected_manifest_sha + "\n").encode("ascii"):
        fail("remote VERIFIED marker does not bind the exact manifest version")
    for artifact_name, filename in (
        ("databaseDump", "database.dump.age"),
        ("localMediaTar", "local-media.tar.age"),
        ("mediaManifest", "media-manifest.json.age"),
    ):
        if selected[filename].size != manifest["artifacts"][artifact_name]["byteSize"]:
            fail("remote set artifact size differs from its verified manifest")
    return SetRecord(
        set_id=set_id,
        manifest_sha256=expected_manifest_sha,
        manifest=manifest,
        object_namespace=object_namespace,
        versions=tuple(sorted(completion_versions)),
        object_versions=tuple(sorted(selected.values())),
    )


@dataclass(frozen=True)
class Proposal:
    document: dict[str, Any]
    sha256: str
    observed: dict[str, str]
    object_namespaces: dict[str, str]
    retained: frozenset[str]
    protected: frozenset[str]
    delete_sets: frozenset[str]
    reachable_blobs: frozenset[str]
    delete_blobs: frozenset[str]
    delete_uploads: frozenset[str]
    current_set: str


def validate_sorted_unique_strings(value: Any, pattern: re.Pattern[str], label: str) -> list[str]:
    if not isinstance(value, list) or any(not isinstance(item, str) or not pattern.fullmatch(item) for item in value):
        fail(f"{label} is invalid")
    if value != sorted(set(value)):
        fail(f"{label} is not sorted and unique")
    return value


def load_proposal(path_value: str, invocation: Invocation) -> Proposal:
    path = secure_regular_file(path_value, "GC policy snapshot", private=True)
    document = exact_keys(
        load_json_file(path, "GC policy snapshot", MAX_MANIFEST_BYTES),
        [
            "currentSet",
            "deleteBlobs",
            "deleteSets",
            "deleteUploads",
            "destination",
            "generatedAt",
            "observedSets",
            "policy",
            "prefix",
            "protectedSets",
            "reachableBlobs",
            "retainedSets",
            "schemaVersion",
        ],
        "GC policy snapshot",
    )
    if document["schemaVersion"] != 3 or document["prefix"] != invocation.prefix:
        fail("GC policy snapshot version or prefix is invalid")
    parse_rfc3339(document["generatedAt"], "GC policy generation time")
    destination = exact_keys(
        document["destination"],
        ["accountId", "bucket", "principalId", "region"],
        "GC destination",
    )
    if destination != {
        "accountId": invocation.account,
        "bucket": invocation.bucket,
        "principalId": invocation.principal,
        "region": invocation.region,
    }:
        fail("GC policy snapshot is bound to a different destination")
    policy = exact_keys(
        document["policy"],
        ["daily", "monthly", "safetyDays", "weekly"],
        "GC retention policy",
    )
    safety_days_text = require_environment("BACKUP_RETENTION_SAFETY_DAYS")
    if not re.fullmatch(r"[1-9][0-9]{0,3}", safety_days_text):
        fail("retention safety days are invalid")
    if policy != {"daily": 7, "weekly": 4, "monthly": 6, "safetyDays": int(safety_days_text)}:
        fail("GC policy snapshot is not the reviewed 7/4/6 policy")

    observed_raw = document["observedSets"]
    if not isinstance(observed_raw, list):
        fail("GC observed set collection is invalid")
    observed: dict[str, str] = {}
    rendered_observed: list[tuple[str, str, str]] = []
    for raw_record in observed_raw:
        record = exact_keys(
            raw_record,
            ["manifestSha256", "objectNamespace", "setId"],
            "GC observed set",
        )
        set_id = record["setId"]
        manifest_sha = record["manifestSha256"]
        object_namespace = record["objectNamespace"]
        if not isinstance(set_id, str) or not SET_ID_RE.fullmatch(set_id):
            fail("GC observed set ID is invalid")
        if not isinstance(manifest_sha, str) or not SHA256_RE.fullmatch(manifest_sha):
            fail("GC observed manifest checksum is invalid")
        if object_namespace not in {"sets", "uploading"}:
            fail("GC observed set object namespace is invalid")
        if set_id in observed:
            fail("GC observed set collection contains a duplicate")
        observed[set_id] = manifest_sha
        rendered_observed.append((set_id, manifest_sha, object_namespace))
    if rendered_observed != sorted(rendered_observed):
        fail("GC observed set collection is not canonical")

    retained = frozenset(validate_sorted_unique_strings(document["retainedSets"], SET_ID_RE, "retained set collection"))
    protected = frozenset(validate_sorted_unique_strings(document["protectedSets"], SET_ID_RE, "protected set collection"))
    delete_sets = frozenset(validate_sorted_unique_strings(document["deleteSets"], SET_ID_RE, "delete set collection"))
    blob_path_re = re.compile(r"^blobs/[0-9a-f]{64}$")
    reachable = frozenset(validate_sorted_unique_strings(document["reachableBlobs"], blob_path_re, "reachable blob collection"))
    delete_blobs = frozenset(validate_sorted_unique_strings(document["deleteBlobs"], blob_path_re, "delete blob collection"))
    delete_uploads = frozenset(
        validate_sorted_unique_strings(
            document["deleteUploads"], SET_ID_RE, "delete upload collection"
        )
    )
    current_set = document["currentSet"]
    if not isinstance(current_set, str) or not SET_ID_RE.fullmatch(current_set):
        fail("current set identity is invalid")
    observed_ids = frozenset(observed)
    if protected | delete_sets != observed_ids or protected & delete_sets:
        fail("protected and deletable sets do not exactly partition the observed sets")
    if not retained or not retained <= protected:
        fail("7/4/6 retained sets are not a non-empty subset of protected sets")
    if current_set not in retained or current_set not in protected:
        fail("current backup set is not protected")
    if reachable & delete_blobs:
        fail("GC proposal selects a protected reachable blob")
    return Proposal(
        document=document,
        sha256=sha256_file(path),
        observed=observed,
        object_namespaces={
            raw["setId"]: raw["objectNamespace"] for raw in observed_raw
        },
        retained=retained,
        protected=protected,
        delete_sets=delete_sets,
        reachable_blobs=reachable,
        delete_blobs=delete_blobs,
        delete_uploads=delete_uploads,
        current_set=current_set,
    )


def group_set_versions(
    invocation: Invocation, versions: tuple[ObjectVersion, ...]
) -> dict[str, tuple[ObjectVersion, ...]]:
    grouped: dict[str, list[ObjectVersion]] = {}
    for version in versions:
        relative = version.key[len(invocation.sets_prefix) :]
        parts = relative.split("/")
        if len(parts) != 2 or not SET_ID_RE.fullmatch(parts[0]) or parts[1] not in SET_FILENAMES:
            fail("COS sets namespace contains an unexpected key")
        grouped.setdefault(parts[0], []).append(version)
    return {set_id: tuple(sorted(entries)) for set_id, entries in grouped.items()}


def load_remote_universe(
    api: CosApi,
    invocation: Invocation,
    proposal: Proposal,
    *,
    allow_missing_delete_sets: bool,
    partial_delete_set: str | None = None,
) -> dict[str, SetRecord]:
    versions = stable_versions(api, invocation, invocation.sets_prefix)
    grouped = group_set_versions(invocation, versions)
    remote_ids = frozenset(grouped)
    expected_ids = frozenset(proposal.observed)
    if remote_ids - expected_ids:
        fail("an unreviewed backup set appeared during pruning")
    if not proposal.protected <= remote_ids:
        fail("a protected backup set disappeared during pruning")
    if not allow_missing_delete_sets and remote_ids != expected_ids:
        fail("the observed backup set universe changed during review")
    records: dict[str, SetRecord] = {}
    for set_id, entries in grouped.items():
        if partial_delete_set == set_id:
            if set_id not in proposal.delete_sets or set_id in proposal.protected:
                fail("partial transaction target is not a deletable set")
            continue
        records[set_id] = load_set_record(
            api,
            invocation,
            set_id,
            entries,
            proposal.observed[set_id],
            proposal.object_namespaces[set_id],
        )
    return records


def protected_fingerprint(
    records: dict[str, SetRecord],
    proposal: Proposal,
    blobs: dict[str, BlobRecord],
) -> str:
    material: list[dict[str, Any]] = []
    for set_id in sorted(proposal.protected):
        record = records.get(set_id)
        if record is None:
            fail("protected set is absent from the remote universe")
        material.append(
            {
                "setId": set_id,
                "manifestSha256": record.manifest_sha256,
                "objectNamespace": record.object_namespace,
                "versions": [asdict(version) for version in record.versions],
                "objectVersions": [
                    asdict(version) for version in record.object_versions
                ],
            }
        )
    blob_material = [
        {
            "path": path,
            "sha256": blobs[path].sha256,
            "byteSize": blobs[path].byte_size,
            "version": asdict(blobs[path].version),
        }
        for path in sorted(blobs)
    ]
    if set(blobs) != set(proposal.reachable_blobs):
        fail("protected blob fingerprint does not cover the exact reachable closure")
    return sha256_bytes(
        canonical_bytes({"sets": material, "reachableBlobs": blob_material})
    )


def closure_maps(
    records: dict[str, SetRecord],
    proposal: Proposal,
    *,
    require_delete_references: bool,
) -> tuple[dict[str, set[str]], dict[str, int]]:
    references: dict[str, set[str]] = {}
    sizes: dict[str, int] = {}
    for set_id, record in records.items():
        for blob in record.manifest["blobs"]:
            path = blob["path"]
            references.setdefault(path, set()).add(set_id)
            existing = sizes.setdefault(path, blob["byteSize"])
            if existing != blob["byteSize"]:
                fail("backup manifests disagree on a content-addressed blob size")
    reachable = {
        path
        for path, set_ids in references.items()
        if set_ids & set(proposal.protected)
    }
    if reachable != set(proposal.reachable_blobs):
        fail("GC proposal reachable blob closure differs from protected manifests")
    if require_delete_references:
        for path in proposal.delete_blobs:
            referring_sets = references.get(path, set())
            if not referring_sets or not referring_sets <= set(proposal.delete_sets):
                fail("GC proposal blob is not exclusively owned by complete deletable sets")
    return references, sizes


def hash_version(
    api: CosApi,
    invocation: Invocation,
    version: ObjectVersion,
    expected_size: int,
    expected_sha256: str,
) -> tuple[int, str]:
    response = exact_keys(
        api.call(
            "hash-version",
            {
                "schemaVersion": 1,
                "bucket": invocation.bucket,
                "region": invocation.region,
                "key": version.key,
                "versionId": version.versionId,
                "expectedSize": expected_size,
                "expectedSha256": expected_sha256,
            },
        ),
        ["byteSize", "key", "schemaVersion", "sha256", "versionId"],
        "COS streamed version digest",
    )
    if (
        response["schemaVersion"] != 1
        or response["key"] != version.key
        or response["versionId"] != version.versionId
        or not isinstance(response["byteSize"], int)
        or isinstance(response["byteSize"], bool)
        or response["byteSize"] != expected_size
        or response["byteSize"] != version.size
        or response["sha256"] != expected_sha256
    ):
        fail("COS streamed version digest does not match its exact manifest binding")
    return response["byteSize"], response["sha256"]


def load_reachable_blob_records(
    api: CosApi,
    invocation: Invocation,
    proposal: Proposal,
    blob_sizes: dict[str, int],
) -> dict[str, BlobRecord]:
    result: dict[str, BlobRecord] = {}
    for path in sorted(proposal.reachable_blobs):
        expected_size = blob_sizes.get(path)
        if expected_size is None:
            fail("protected blob lacks a verified manifest size")
        expected_sha = path.removeprefix("blobs/")
        if not SHA256_RE.fullmatch(expected_sha):
            fail("protected blob path is not content-addressed")
        full_key = f"{invocation.prefix}/{path}"
        versions = stable_versions(api, invocation, full_key)
        if any(version.key != full_key for version in versions):
            fail("protected blob prefix query returned a prefix-collision key")
        selected = one_current_object(list(versions), "protected reachable blob")
        hash_version(
            api,
            invocation,
            selected,
            expected_size,
            expected_sha,
        )
        result[path] = BlobRecord(
            path=path,
            sha256=expected_sha,
            byte_size=expected_size,
            version=selected,
        )
    return result


def candidate_versions(
    api: CosApi,
    invocation: Invocation,
    proposal: Proposal,
    records: dict[str, SetRecord],
    kind: str,
    relative_path: str,
) -> tuple[ObjectVersion, ...]:
    if kind == "set":
        match = re.fullmatch(r"sets/([0-9]{8}T[0-9]{6}Z-[0-9a-f]{12})", relative_path)
        if not match:
            fail("set candidate path is not canonical")
        set_id = match.group(1)
        if set_id not in proposal.delete_sets or set_id in proposal.protected:
            fail("set candidate is current, retained, protected, or unreviewed")
        record = records.get(set_id)
        if record is None:
            fail("set candidate is absent")
        return record.versions
    if kind == "blob":
        if not re.fullmatch(r"blobs/[0-9a-f]{64}", relative_path):
            fail("blob candidate path is not canonical")
        if relative_path not in proposal.delete_blobs or relative_path in proposal.reachable_blobs:
            fail("blob candidate is protected, reachable, or unreviewed")
        full_key = f"{invocation.prefix}/{relative_path}"
        versions = stable_versions(api, invocation, full_key)
        if any(version.key != full_key for version in versions):
            fail("blob prefix query returned a prefix-collision key")
        selected = one_current_object(list(versions), "content-addressed blob")
        return (selected,)
    if kind == "upload":
        match = re.fullmatch(
            r"uploading/([0-9]{8}T[0-9]{6}Z-[0-9a-f]{12})", relative_path
        )
        if not match or match.group(1) not in proposal.delete_uploads:
            fail("upload candidate is not a reviewed stale upload attempt")
        set_id = match.group(1)
        if any(
            record.set_id == set_id and record.object_namespace == "uploading"
            for record in records.values()
        ):
            fail("upload candidate is referenced by a completed backup set")
        full_prefix = f"{invocation.prefix}/{relative_path}/"
        versions = stable_versions(api, invocation, full_prefix)
        if not versions:
            fail("upload candidate is absent")
        by_filename: dict[str, list[ObjectVersion]] = {}
        for version in versions:
            if not version.key.startswith(full_prefix):
                fail("upload candidate escaped its exact directory")
            filename = version.key[len(full_prefix) :]
            if filename not in SET_FILENAMES or "/" in filename:
                fail("upload candidate contains an unexpected object key")
            by_filename.setdefault(filename, []).append(version)
        for filename, entries in by_filename.items():
            one_current_object(entries, f"stale upload object {filename}")
        return tuple(sorted(versions))
    fail("candidate kind is not allowlisted")


def validate_candidate_sizes(
    candidate: tuple[ObjectVersion, ...],
    kind: str,
    relative_path: str,
    blob_sizes: dict[str, int],
) -> None:
    if kind != "blob":
        return
    expected = blob_sizes.get(relative_path)
    if expected is None or len(candidate) != 1 or candidate[0].size != expected:
        fail("content-addressed blob size differs from all verified referring manifests")


def validate_retention(
    api: CosApi,
    invocation: Invocation,
    versions: tuple[ObjectVersion, ...],
    *,
    minimum_age_seconds: int | None = None,
) -> None:
    now = datetime.now(timezone.utc)
    for version in versions:
        response = exact_keys(
            api.call(
                "get-retention",
                {
                    "schemaVersion": 1,
                    "bucket": invocation.bucket,
                    "region": invocation.region,
                    "key": version.key,
                    "versionId": version.versionId,
                },
            ),
            [
                "isLatest",
                "key",
                "legalHoldStatus",
                "mode",
                "retainUntilDate",
                "schemaVersion",
                "serverDate",
                "versionId",
            ],
            "COS object retention",
        )
        if (
            response["schemaVersion"] != 1
            or response["key"] != version.key
            or response["versionId"] != version.versionId
            or response["isLatest"] is not True
            or response["mode"] != "COMPLIANCE"
            or response["legalHoldStatus"] != LEGAL_HOLD_CAPABILITY
        ):
            fail("COS object retention is not bound to the exact current version")
        server_date = parse_http_date(response["serverDate"], "COS server date")
        if abs((server_date - now).total_seconds()) > 300:
            fail("COS server date differs too far from the pruner clock")
        retain_until = parse_rfc3339(response["retainUntilDate"], "COS retain-until date")
        if retain_until > server_date:
            fail("COS COMPLIANCE retention has not expired")
        if minimum_age_seconds is not None:
            last_modified = parse_rfc3339(
                version.lastModified, "candidate version modification time"
            )
            age = (server_date - last_modified).total_seconds()
            if age < minimum_age_seconds:
                fail("stale upload candidate is still inside the safety window")


def ticket_document(
    proposal: Proposal,
    invocation: Invocation,
    kind: str,
    relative_path: str,
    versions: tuple[ObjectVersion, ...],
    protected_digest: str,
    referring_set_ids: list[str],
    blob_byte_size: int | None,
) -> dict[str, Any]:
    return {
        "schemaVersion": 1,
        "candidate": {
            "kind": kind,
            "relativePath": relative_path,
            "versions": [asdict(version) for version in versions],
            "referringSetIds": referring_set_ids,
            "blobByteSize": blob_byte_size,
        },
        "destination": {
            "accountId": invocation.account,
            "bucket": invocation.bucket,
            "prefix": invocation.prefix,
            "principalId": invocation.principal,
            "region": invocation.region,
        },
        "issuedAt": ticket_clock().strftime("%Y-%m-%dT%H:%M:%SZ"),
        "nonce": secrets.token_hex(16),
        "proposalSha256": proposal.sha256,
        "protectedFingerprint": protected_digest,
    }


def write_exclusive_json(path_value: str, value: dict[str, Any]) -> Path:
    path = Path(path_value)
    if not path.is_absolute() or path.exists() or path.is_symlink():
        fail("review ticket output must be a new absolute path")
    parent = path.parent
    try:
        parent_info = parent.lstat()
    except OSError:
        fail("review ticket directory is unavailable")
    if not stat.S_ISDIR(parent_info.st_mode) or stat.S_ISLNK(parent_info.st_mode):
        fail("review ticket directory is unsafe")
    if parent_info.st_uid != os.geteuid() or parent_info.st_mode & 0o077:
        fail("review ticket directory is not private to the invoking operator")
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    try:
        descriptor = os.open(path, flags, 0o600)
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(canonical_bytes(value) + b"\n")
            stream.flush()
            os.fsync(stream.fileno())
    except OSError:
        fail("review ticket could not be created exclusively")
    return path


def replace_private_json(path: Path, value: dict[str, Any], label: str) -> None:
    secure_regular_file(str(path), label, private=True)
    temporary = path.with_name(path.name + ".new-" + secrets.token_hex(8))
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    try:
        descriptor = os.open(temporary, flags, 0o600)
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(canonical_bytes(value) + b"\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        directory_descriptor = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(directory_descriptor)
        finally:
            os.close(directory_descriptor)
    except OSError:
        try:
            temporary.unlink(missing_ok=True)
        except OSError:
            pass
        fail(f"{label} could not be updated atomically")


def transaction_path_for_ticket(ticket_value: str) -> Path:
    ticket = Path(ticket_value)
    if not ticket.is_absolute() or ticket.name in {"", ".", ".."}:
        fail("review ticket path cannot anchor a deletion transaction")
    return ticket.with_name(ticket.name + ".transaction.json")


def load_transaction(
    path: Path,
    expected_ticket_sha: str,
    proposal: Proposal,
    invocation: Invocation,
    kind: str,
    relative_path: str,
    reviewed_versions: tuple[ObjectVersion, ...],
    protected_fingerprint_value: str,
) -> tuple[dict[str, Any], set[tuple[str, str]]]:
    document = exact_keys(
        load_json_file(
            secure_regular_file(str(path), "deletion transaction", private=True),
            "deletion transaction",
            MAX_MANIFEST_BYTES,
        ),
        [
            "candidate",
            "completedVersions",
            "destination",
            "proposalSha256",
            "protectedFingerprint",
            "reviewedVersions",
            "reviewTicketSha256",
            "schemaVersion",
            "status",
        ],
        "deletion transaction",
    )
    if document["schemaVersion"] != 1 or document["status"] not in {
        "ACTIVE",
        "COMPLETED",
    }:
        fail("deletion transaction state is invalid")
    if (
        document["proposalSha256"] != proposal.sha256
        or document["protectedFingerprint"] != protected_fingerprint_value
        or document["reviewTicketSha256"] != expected_ticket_sha
        or document["candidate"]
        != {"kind": kind, "relativePath": relative_path}
        or document["destination"]
        != {
            "accountId": invocation.account,
            "bucket": invocation.bucket,
            "prefix": invocation.prefix,
            "principalId": invocation.principal,
            "region": invocation.region,
        }
        or document["reviewedVersions"]
        != [asdict(version) for version in reviewed_versions]
    ):
        fail("deletion transaction is not bound to this exact review")
    completed_raw = document["completedVersions"]
    if not isinstance(completed_raw, list):
        fail("deletion transaction completed-version collection is invalid")
    completed: list[tuple[str, str]] = []
    for raw in completed_raw:
        item = exact_keys(raw, ["key", "versionId"], "completed transaction version")
        if (
            not isinstance(item["key"], str)
            or not isinstance(item["versionId"], str)
        ):
            fail("deletion transaction completed-version identity is invalid")
        completed.append((item["key"], item["versionId"]))
    reviewed_ids = {(item.key, item.versionId) for item in reviewed_versions}
    if completed != sorted(set(completed)) or not set(completed) <= reviewed_ids:
        fail("deletion transaction completed-version closure is invalid")
    if document["status"] == "COMPLETED" and set(completed) != reviewed_ids:
        fail("completed deletion transaction does not cover its reviewed versions")
    return document, set(completed)


def create_transaction(
    path: Path,
    ticket_sha: str,
    proposal: Proposal,
    invocation: Invocation,
    kind: str,
    relative_path: str,
    reviewed_versions: tuple[ObjectVersion, ...],
    protected_fingerprint_value: str,
) -> dict[str, Any]:
    document = {
        "schemaVersion": 1,
        "candidate": {"kind": kind, "relativePath": relative_path},
        "completedVersions": [],
        "destination": {
            "accountId": invocation.account,
            "bucket": invocation.bucket,
            "prefix": invocation.prefix,
            "principalId": invocation.principal,
            "region": invocation.region,
        },
        "proposalSha256": proposal.sha256,
        "protectedFingerprint": protected_fingerprint_value,
        "reviewedVersions": [asdict(version) for version in reviewed_versions],
        "reviewTicketSha256": ticket_sha,
        "status": "ACTIVE",
    }
    write_exclusive_json(str(path), document)
    return document


def record_transaction_progress(
    path: Path,
    document: dict[str, Any],
    completed: set[tuple[str, str]],
    reviewed_versions: tuple[ObjectVersion, ...],
) -> None:
    document["completedVersions"] = [
        {"key": key, "versionId": version_id}
        for key, version_id in sorted(completed)
    ]
    reviewed_ids = {(item.key, item.versionId) for item in reviewed_versions}
    document["status"] = "COMPLETED" if completed == reviewed_ids else "ACTIVE"
    replace_private_json(path, document, "deletion transaction")


def parse_ticket(
    path_value: str,
    expected_sha: str,
    proposal: Proposal,
    invocation: Invocation,
    kind: str,
    relative_path: str,
    *,
    allow_expired: bool = False,
) -> tuple[dict[str, Any], tuple[ObjectVersion, ...], list[str], int | None]:
    if not SHA256_RE.fullmatch(expected_sha):
        fail("review ticket checksum is invalid")
    path = secure_regular_file(path_value, "review ticket", private=True)
    if sha256_file(path) != expected_sha:
        fail("review ticket checksum changed")
    ticket = exact_keys(
        load_json_file(path, "review ticket", MAX_MANIFEST_BYTES),
        [
            "candidate",
            "destination",
            "issuedAt",
            "nonce",
            "proposalSha256",
            "protectedFingerprint",
            "schemaVersion",
        ],
        "review ticket",
    )
    if ticket["schemaVersion"] != 1 or ticket["proposalSha256"] != proposal.sha256:
        fail("review ticket is not bound to the exact GC proposal")
    issued = parse_rfc3339(ticket["issuedAt"], "review ticket issue time")
    age = (ticket_clock() - issued).total_seconds()
    if age < -300 or (age > 3600 and not allow_expired):
        fail("review ticket is outside its one-hour validity window")
    if not isinstance(ticket["nonce"], str) or not re.fullmatch(r"[0-9a-f]{32}", ticket["nonce"]):
        fail("review ticket nonce is invalid")
    if not isinstance(ticket["protectedFingerprint"], str) or not SHA256_RE.fullmatch(ticket["protectedFingerprint"]):
        fail("review ticket protected-set fingerprint is invalid")
    destination = exact_keys(
        ticket["destination"],
        ["accountId", "bucket", "prefix", "principalId", "region"],
        "review ticket destination",
    )
    if destination != {
        "accountId": invocation.account,
        "bucket": invocation.bucket,
        "prefix": invocation.prefix,
        "principalId": invocation.principal,
        "region": invocation.region,
    }:
        fail("review ticket destination does not match the current invocation")
    candidate = exact_keys(
        ticket["candidate"],
        ["blobByteSize", "kind", "referringSetIds", "relativePath", "versions"],
        "review ticket candidate",
    )
    if candidate["kind"] != kind or candidate["relativePath"] != relative_path or not isinstance(candidate["versions"], list):
        fail("review ticket candidate changed")
    expected_prefix = f"{invocation.prefix}/{relative_path}"
    if kind in {"set", "upload"}:
        expected_prefix += "/"
    versions = tuple(sorted(version_from_json(raw, expected_prefix) for raw in candidate["versions"]))
    if not versions:
        fail("review ticket contains no exact versions")
    referring = candidate["referringSetIds"]
    blob_size = candidate["blobByteSize"]
    if (
        not isinstance(referring, list)
        or any(not isinstance(item, str) or not SET_ID_RE.fullmatch(item) for item in referring)
        or referring != sorted(set(referring))
    ):
        fail("review ticket referring-set closure is invalid")
    if kind == "blob":
        if (
            not referring
            or not set(referring) <= set(proposal.delete_sets)
            or not isinstance(blob_size, int)
            or isinstance(blob_size, bool)
            or blob_size <= 0
        ):
            fail("review ticket does not prove the deletable blob closure")
    elif referring or blob_size is not None:
        fail("non-blob review ticket carried unexpected blob closure metadata")
    return ticket, versions, referring, blob_size


def delete_one_version(
    api: CosApi,
    invocation: Invocation,
    version: ObjectVersion,
) -> None:
    response = exact_keys(
        api.call(
            "delete-version",
            {
                "schemaVersion": 1,
                "bucket": invocation.bucket,
                "region": invocation.region,
                "key": version.key,
                "versionId": version.versionId,
            },
        ),
        ["deleted", "deleteMarker", "key", "schemaVersion", "versionId"],
        "COS exact-version delete",
    )
    if (
        response["schemaVersion"] != 1
        or response["deleted"] is not True
        or response["deleteMarker"] is not False
        or response["key"] != version.key
        or response["versionId"] != version.versionId
    ):
        fail("COS did not confirm deletion of the exact reviewed object version")


def validate_local_evidence(
    evidence_value: str,
    proposal_path_value: str,
    proposal: Proposal,
    kind: str,
    relative_path: str,
) -> None:
    evidence = secure_regular_file(evidence_value, "candidate evidence", private=True)
    if kind == "set":
        match = re.fullmatch(r"sets/([0-9]{8}T[0-9]{6}Z-[0-9a-f]{12})", relative_path)
        if not match:
            fail("set evidence path is not canonical")
        set_id = match.group(1)
        if sha256_file(evidence) != proposal.observed.get(set_id):
            fail("local set evidence does not match the reviewed manifest checksum")
        manifest = validate_manifest(
            load_json_file(evidence, "candidate set evidence", MAX_MANIFEST_BYTES),
            set_id,
        )
        if manifest["setId"] != set_id:
            fail("local set evidence identity changed")
        return
    if kind not in {"blob", "upload"}:
        fail("candidate evidence kind is not allowlisted")
    proposal_path = secure_regular_file(
        proposal_path_value, "GC policy snapshot", private=True
    )
    try:
        same_file = os.path.samefile(evidence, proposal_path)
    except OSError:
        same_file = False
    if not same_file or sha256_file(evidence) != proposal.sha256:
        fail("blob evidence must be the exact reviewed GC policy snapshot")


def live_candidate_versions(
    api: CosApi,
    invocation: Invocation,
    kind: str,
    relative_path: str,
) -> tuple[ObjectVersion, ...]:
    full_prefix = f"{invocation.prefix}/{relative_path}"
    if kind in {"set", "upload"}:
        full_prefix += "/"
    versions = stable_versions(api, invocation, full_prefix)
    if kind == "blob" and any(item.key != full_prefix for item in versions):
        fail("blob prefix query returned a prefix-collision key")
    return versions


def review_candidate(args: argparse.Namespace) -> None:
    invocation = invocation_from_args(args)
    api = CosApi()
    inspect_destination(api, invocation)
    proposal = load_proposal(args.proposal, invocation)
    validate_local_evidence(
        args.evidence, args.proposal, proposal, args.kind, args.relative_path
    )
    records = load_remote_universe(
        api, invocation, proposal, allow_missing_delete_sets=False
    )
    references, blob_sizes = closure_maps(
        records, proposal, require_delete_references=True
    )
    protected_blobs = load_reachable_blob_records(
        api, invocation, proposal, blob_sizes
    )
    versions = candidate_versions(
        api, invocation, proposal, records, args.kind, args.relative_path
    )
    validate_candidate_sizes(versions, args.kind, args.relative_path, blob_sizes)
    if args.kind == "blob":
        hash_version(
            api,
            invocation,
            versions[0],
            blob_sizes[args.relative_path],
            args.relative_path.removeprefix("blobs/"),
        )
    minimum_age = None
    if args.kind == "upload":
        minimum_age = int(require_environment("BACKUP_RETENTION_SAFETY_DAYS")) * 86400
    validate_retention(
        api, invocation, versions, minimum_age_seconds=minimum_age
    )
    # A final second candidate snapshot closes review-time list/read races.
    observed_again = candidate_versions(
        api, invocation, proposal, records, args.kind, args.relative_path
    )
    if observed_again != versions:
        fail("candidate versions changed after retention review")
    ticket = ticket_document(
        proposal,
        invocation,
        args.kind,
        args.relative_path,
        versions,
        protected_fingerprint(records, proposal, protected_blobs),
        sorted(references.get(args.relative_path, set())) if args.kind == "blob" else [],
        blob_sizes.get(args.relative_path) if args.kind == "blob" else None,
    )
    write_exclusive_json(args.ticket_output, ticket)
    print("SAFE")


def delete_reviewed(args: argparse.Namespace) -> None:
    invocation = invocation_from_args(args)
    api = CosApi()
    inspect_destination(api, invocation)
    proposal = load_proposal(args.proposal, invocation)
    validate_local_evidence(
        args.evidence, args.proposal, proposal, args.kind, args.relative_path
    )
    transaction_path = transaction_path_for_ticket(args.ticket)
    transaction_exists = transaction_path.exists() or transaction_path.is_symlink()
    ticket, reviewed_versions, referring_set_ids, reviewed_blob_size = parse_ticket(
        args.ticket,
        args.ticket_sha256,
        proposal,
        invocation,
        args.kind,
        args.relative_path,
        allow_expired=transaction_exists,
    )
    partial_set_id: str | None = None
    if transaction_exists and args.kind == "set":
        match = re.fullmatch(
            r"sets/([0-9]{8}T[0-9]{6}Z-[0-9a-f]{12})", args.relative_path
        )
        if not match:
            fail("set transaction path is not canonical")
        partial_set_id = match.group(1)
    records = load_remote_universe(
        api,
        invocation,
        proposal,
        allow_missing_delete_sets=True,
        partial_delete_set=partial_set_id,
    )
    references, blob_sizes = closure_maps(
        records, proposal, require_delete_references=False
    )
    protected_blobs = load_reachable_blob_records(
        api, invocation, proposal, blob_sizes
    )
    protected_digest = protected_fingerprint(records, proposal, protected_blobs)
    if protected_digest != ticket["protectedFingerprint"]:
        fail("protected backup set or reachable blob state changed since review")
    if transaction_exists:
        transaction_new = False
        transaction, completed = load_transaction(
            transaction_path,
            args.ticket_sha256,
            proposal,
            invocation,
            args.kind,
            args.relative_path,
            reviewed_versions,
            protected_digest,
        )
        current = live_candidate_versions(
            api, invocation, args.kind, args.relative_path
        )
        reviewed_set = set(reviewed_versions)
        current_set = set(current)
        if not current_set <= reviewed_set:
            fail("candidate contains a version outside the persistent exact review")
        if any((item.key, item.versionId) in completed for item in current):
            fail("an already completed exact-version deletion reappeared")
        observed_missing = {
            (item.key, item.versionId) for item in reviewed_versions
        } - {(item.key, item.versionId) for item in current}
        if not completed <= observed_missing:
            fail("deletion transaction progress contradicts COS read-back")
        if observed_missing != completed:
            completed = observed_missing
            record_transaction_progress(
                transaction_path, transaction, completed, reviewed_versions
            )
    else:
        transaction_new = True
        current = candidate_versions(
            api, invocation, proposal, records, args.kind, args.relative_path
        )
        if current != reviewed_versions:
            fail("candidate versions changed between review and delete")
        transaction = None
        completed: set[tuple[str, str]] = set()
    if args.kind == "blob":
        current_referring = references.get(args.relative_path, set())
        if (
            not set(referring_set_ids) <= set(proposal.delete_sets)
            or (
                args.prepare_only
                and current_referring != set(referring_set_ids)
            )
            or (not args.prepare_only and current_referring)
        ):
            fail("reviewed blob closure changed before deletion")
        if current:
            if len(current) != 1 or current[0].size != reviewed_blob_size:
                fail("reviewed blob size changed before deletion")
            hash_version(
                api,
                invocation,
                current[0],
                reviewed_blob_size,
                args.relative_path.removeprefix("blobs/"),
            )
    else:
        validate_candidate_sizes(current, args.kind, args.relative_path, blob_sizes)
    minimum_age = None
    if args.kind == "upload":
        minimum_age = int(require_environment("BACKUP_RETENTION_SAFETY_DAYS")) * 86400
    validate_retention(
        api, invocation, current, minimum_age_seconds=minimum_age
    )
    if transaction_new:
        transaction = create_transaction(
            transaction_path,
            args.ticket_sha256,
            proposal,
            invocation,
            args.kind,
            args.relative_path,
            reviewed_versions,
            protected_digest,
        )
    if args.prepare_only:
        print("PREPARED")
        return

    # Remove VERIFIED first so a partial provider failure cannot leave an
    # apparently restorable set.  Every request carries the reviewed version ID.
    ordered = list(current)
    if args.kind == "set":
        ordered.sort(key=lambda item: (0 if item.key.endswith("/VERIFIED") else 1, item.key))
    while ordered:
        version = ordered[0]
        remaining = live_candidate_versions(
            api, invocation, args.kind, args.relative_path
        )
        if set(remaining) != set(ordered) or len(remaining) != len(ordered):
            fail("candidate version set changed before an exact-version delete")
        if args.kind == "upload":
            upload_set_id = args.relative_path.removeprefix("uploading/")
            if any(
                record.set_id == upload_set_id
                and record.object_namespace == "uploading"
                for record in load_remote_universe(
                    api,
                    invocation,
                    proposal,
                    allow_missing_delete_sets=True,
                ).values()
            ):
                fail("a completed set began referencing the stale upload transaction")
        validate_retention(
            api,
            invocation,
            (version,),
            minimum_age_seconds=minimum_age,
        )
        delete_one_version(api, invocation, version)
        after_delete = live_candidate_versions(
            api, invocation, args.kind, args.relative_path
        )
        expected_after = set(ordered[1:])
        if set(after_delete) != expected_after or len(after_delete) != len(expected_after):
            fail("exact-version deletion was not confirmed by an immediate read-back")
        completed.add((version.key, version.versionId))
        record_transaction_progress(
            transaction_path, transaction, completed, reviewed_versions
        )
        ordered.pop(0)

    final_prefix = f"{invocation.prefix}/{args.relative_path}" + (
        "/" if args.kind in {"set", "upload"} else ""
    )
    if stable_versions(api, invocation, final_prefix):
        fail("COS deletion read-back found a version or delete marker")
    final_records = load_remote_universe(
        api, invocation, proposal, allow_missing_delete_sets=True
    )
    final_references, final_blob_sizes = closure_maps(
        final_records, proposal, require_delete_references=False
    )
    if args.kind == "blob" and final_references.get(args.relative_path):
        fail("a remote manifest recreated the deleted blob reference")
    if args.kind == "upload":
        upload_set_id = args.relative_path.removeprefix("uploading/")
        if any(
            record.set_id == upload_set_id
            and record.object_namespace == "uploading"
            for record in final_records.values()
        ):
            fail("a completed set recreated the deleted upload reference")
    final_protected_blobs = load_reachable_blob_records(
        api, invocation, proposal, final_blob_sizes
    )
    if (
        protected_fingerprint(final_records, proposal, final_protected_blobs)
        != ticket["protectedFingerprint"]
    ):
        fail("protected backup set or reachable blob state changed during deletion")
    reviewed_ids = {(item.key, item.versionId) for item in reviewed_versions}
    if completed != reviewed_ids or transaction["status"] != "COMPLETED":
        fail("persistent deletion transaction did not close every reviewed version")
    print("DELETED")


def provider_input(expected_keys: Iterable[str]) -> dict[str, Any]:
    try:
        raw = sys.stdin.buffer.read(MAX_API_OUTPUT_BYTES + 1)
    except OSError:
        raise RuntimeError("provider input unavailable")
    if not raw or len(raw) > MAX_API_OUTPUT_BYTES:
        raise RuntimeError("provider input size invalid")
    try:
        value = json.loads(raw.decode("utf-8"))
    except (UnicodeError, json.JSONDecodeError) as error:
        raise RuntimeError("provider input malformed") from error
    if not isinstance(value, dict) or set(value) != set(expected_keys):
        raise RuntimeError("provider input schema invalid")
    if value.get("schemaVersion") != 1:
        raise RuntimeError("provider schema unsupported")
    return value


def provider_credentials() -> tuple[str, str, str | None]:
    path_value = os.environ.get("BACKUP_COS_PRUNE_CREDENTIAL_FILE", "")
    if not path_value:
        raise RuntimeError("credential file missing")
    path = secure_regular_file(path_value, "COS pruner API credential file", private=True)
    document = load_json_file(path, "COS pruner API credential file", 64 * 1024)
    if not isinstance(document, dict) or set(document) != {
        "schemaVersion",
        "secretId",
        "secretKey",
        "securityToken",
    }:
        raise RuntimeError("credential file schema invalid")
    secret_id = document["secretId"]
    secret_key = document["secretKey"]
    token = document["securityToken"]
    if (
        document["schemaVersion"] != 1
        or not isinstance(secret_id, str)
        or len(secret_id) < 8
        or len(secret_id) > 256
        or not isinstance(secret_key, str)
        or len(secret_key) < 8
        or len(secret_key) > 512
        or (token is not None and (not isinstance(token, str) or not token or len(token) > 4096))
    ):
        raise RuntimeError("credential values invalid")
    return secret_id, secret_key, token


def provider_clients(region: str) -> tuple[Any, Any, str | None]:
    import logging

    logging.disable(logging.CRITICAL)
    secret_id, secret_key, token = provider_credentials()
    try:
        from qcloud_cos import CosConfig, CosS3Client
        from tencentcloud.common import credential
        from tencentcloud.sts.v20180813 import sts_client
    except Exception as error:
        raise RuntimeError("official Tencent SDK dependency missing") from error
    cos_config = CosConfig(
        Region=region,
        SecretId=secret_id,
        SecretKey=secret_key,
        Token=token,
        Scheme="https",
    )
    cos_client = CosS3Client(cos_config)
    sts_credential = credential.Credential(secret_id, secret_key, token)
    identity_client = sts_client.StsClient(sts_credential, region)
    return cos_client, identity_client, token


def provider_validate_location(value: dict[str, Any]) -> tuple[str, str]:
    bucket = value.get("bucket")
    region = value.get("region")
    if not isinstance(bucket, str) or not BUCKET_RE.fullmatch(bucket):
        raise RuntimeError("bucket invalid")
    if not isinstance(region, str) or not REGION_RE.fullmatch(region):
        raise RuntimeError("region invalid")
    return bucket, region


def sdk_single_or_list(value: Any) -> list[dict[str, Any]]:
    if value is None or value == "":
        return []
    if isinstance(value, dict):
        return [value]
    if isinstance(value, list) and all(isinstance(item, dict) for item in value):
        return value
    raise RuntimeError("SDK list response malformed")


def sdk_bool(value: Any) -> bool:
    if value in {True, "true", "True", "1", 1}:
        return True
    if value in {False, "false", "False", "0", 0}:
        return False
    raise RuntimeError("SDK boolean response malformed")


def provider_inspect() -> dict[str, Any]:
    value = provider_input(
        ["accountId", "bucket", "prefix", "principalId", "region", "schemaVersion"]
    )
    bucket, region = provider_validate_location(value)
    prefix = value["prefix"]
    if not isinstance(prefix, str) or not is_safe_relative(prefix):
        raise RuntimeError("prefix invalid")
    cos_client, identity_client, _security_token = provider_clients(region)
    try:
        from tencentcloud.sts.v20180813 import models

        request = models.GetCallerIdentityRequest()
        request.from_json_string("{}")
        identity = identity_client.GetCallerIdentity(request)
        head = cos_client.head_bucket(Bucket=bucket)
        versioning = cos_client.get_bucket_versioning(Bucket=bucket)
        object_lock = cos_client.get_bucket_object_lock(Bucket=bucket)
        listing = cos_client.list_objects_versions(
            Bucket=bucket, Prefix=f"{prefix}/", MaxKeys=1
        )
    except Exception as error:
        raise RuntimeError("destination inspection failed") from error
    identity_shape = {
        "AccountId": getattr(identity, "AccountId", None),
        "Arn": getattr(identity, "Arn", None),
        "PrincipalId": getattr(identity, "PrincipalId", None),
        "Type": getattr(identity, "Type", None),
    }
    if (
        not isinstance(identity_shape["AccountId"], str)
        or not IDENTITY_RE.fullmatch(identity_shape["AccountId"])
        or not isinstance(identity_shape["PrincipalId"], str)
        or not IDENTITY_RE.fullmatch(identity_shape["PrincipalId"])
        or not isinstance(identity_shape["Arn"], str)
        or len(identity_shape["Arn"]) > 512
        or not identity_shape["Arn"].startswith("qcs::")
        or identity_shape["Type"] not in {"CAMUser", "CAMRole", "FederatedUser"}
    ):
        raise RuntimeError("STS caller identity response malformed")
    actual_region = head.get("x-cos-bucket-region")
    listed_bucket = listing.get("Name")
    listed_prefix = listing.get("Prefix", "")
    if actual_region != region or listed_bucket != bucket or listed_prefix != f"{prefix}/":
        raise RuntimeError("destination identity mismatch")
    status = versioning.get("Status")
    if status is None and isinstance(versioning.get("VersioningConfiguration"), dict):
        status = versioning["VersioningConfiguration"].get("Status")
    lock_enabled = object_lock.get("ObjectLockEnabled")
    rule = object_lock.get("Rule") or {}
    if not isinstance(rule, dict):
        raise RuntimeError("object lock rule malformed")
    default = rule.get("DefaultRetention") or {}
    if not isinstance(default, dict):
        raise RuntimeError("default retention malformed")
    try:
        days = int(default.get("Days"))
    except (TypeError, ValueError):
        days = None
    return {
        "schemaVersion": 1,
        "accountId": identity_shape["AccountId"],
        "bucket": listed_bucket,
        "legalHoldCapability": LEGAL_HOLD_CAPABILITY,
        "objectLock": {
            "enabled": lock_enabled == "Enabled",
            "mode": default.get("Mode"),
            "defaultRetentionDays": days,
        },
        "prefix": prefix,
        "principalArn": identity_shape["Arn"],
        "principalId": identity_shape["PrincipalId"],
        "principalType": identity_shape["Type"],
        "region": actual_region,
        "versioning": status,
    }


def provider_list_versions() -> dict[str, Any]:
    value = provider_input(
        [
            "bucket",
            "keyMarker",
            "maxKeys",
            "prefix",
            "region",
            "schemaVersion",
            "versionIdMarker",
        ]
    )
    bucket, region = provider_validate_location(value)
    prefix = value["prefix"]
    key_marker = value["keyMarker"]
    version_marker = value["versionIdMarker"]
    max_keys = value["maxKeys"]
    if (
        not isinstance(prefix, str)
        or not is_safe_relative(prefix.rstrip("/"))
        or not isinstance(key_marker, str)
        or not isinstance(version_marker, str)
        or max_keys != 1000
    ):
        raise RuntimeError("version query invalid")
    cos_client, _identity_client, _security_token = provider_clients(region)
    try:
        response = cos_client.list_objects_versions(
            Bucket=bucket,
            Prefix=prefix,
            KeyMarker=key_marker,
            VersionIdMarker=version_marker,
            MaxKeys=max_keys,
        )
    except Exception as error:
        raise RuntimeError("version listing failed") from error
    entries: list[dict[str, Any]] = []
    for raw in sdk_single_or_list(response.get("Version")):
        try:
            size = int(raw["Size"])
        except (KeyError, TypeError, ValueError) as error:
            raise RuntimeError("version size malformed") from error
        entries.append(
            {
                "entryType": "OBJECT",
                "etag": raw.get("ETag"),
                "isLatest": sdk_bool(raw.get("IsLatest")),
                "key": raw.get("Key"),
                "lastModified": raw.get("LastModified"),
                "size": size,
                "versionId": raw.get("VersionId"),
            }
        )
    for raw in sdk_single_or_list(response.get("DeleteMarker")):
        entries.append(
            {
                "entryType": "DELETE_MARKER",
                "etag": None,
                "isLatest": sdk_bool(raw.get("IsLatest")),
                "key": raw.get("Key"),
                "lastModified": raw.get("LastModified"),
                "size": 0,
                "versionId": raw.get("VersionId"),
            }
        )
    return {
        "schemaVersion": 1,
        "bucket": response.get("Name"),
        "region": region,
        "prefix": response.get("Prefix", ""),
        "keyMarker": response.get("KeyMarker", ""),
        "versionIdMarker": response.get("VersionIdMarker", ""),
        "isTruncated": sdk_bool(response.get("IsTruncated", False)),
        "nextKeyMarker": response.get("NextKeyMarker"),
        "nextVersionIdMarker": response.get("NextVersionIdMarker"),
        "entries": entries,
    }


def provider_object_request() -> tuple[dict[str, Any], str, str, str, str, Any, str | None]:
    value = provider_input(
        ["bucket", "key", "region", "schemaVersion", "versionId"]
    )
    bucket, region = provider_validate_location(value)
    key = value["key"]
    version_id = value["versionId"]
    if not isinstance(key, str) or not is_safe_relative(key):
        raise RuntimeError("object key invalid")
    if not isinstance(version_id, str) or not VERSION_ID_RE.fullmatch(version_id):
        raise RuntimeError("object version ID invalid")
    cos_client, _identity_client, security_token = provider_clients(region)
    return value, bucket, region, key, version_id, cos_client, security_token


def provider_read_version() -> dict[str, Any]:
    value = provider_input(
        ["bucket", "key", "maxBytes", "region", "schemaVersion", "versionId"]
    )
    bucket, region = provider_validate_location(value)
    key = value["key"]
    version_id = value["versionId"]
    maximum = value["maxBytes"]
    if (
        not isinstance(key, str)
        or not is_safe_relative(key)
        or not isinstance(version_id, str)
        or not VERSION_ID_RE.fullmatch(version_id)
        or not isinstance(maximum, int)
        or isinstance(maximum, bool)
        or maximum <= 0
        or maximum > MAX_MANIFEST_BYTES
    ):
        raise RuntimeError("version read request invalid")
    cos_client, _identity_client, _security_token = provider_clients(region)
    try:
        response = cos_client.get_object(
            Bucket=bucket, Key=key, versionId=version_id
        )
        returned_version = response.get("x-cos-version-id")
        stream = response["Body"].get_raw_stream()
        body = stream.read(maximum + 1)
        stream.close()
    except Exception as error:
        raise RuntimeError("version read failed") from error
    if returned_version != version_id or len(body) > maximum:
        raise RuntimeError("version read identity or bound changed")
    return {
        "schemaVersion": 1,
        "key": key,
        "versionId": version_id,
        "byteSize": len(body),
        "sha256": sha256_bytes(body),
        "bodyBase64": base64.b64encode(body).decode("ascii"),
    }


def provider_hash_version() -> dict[str, Any]:
    value = provider_input(
        [
            "bucket",
            "expectedSha256",
            "expectedSize",
            "key",
            "region",
            "schemaVersion",
            "versionId",
        ]
    )
    bucket, region = provider_validate_location(value)
    key = value["key"]
    version_id = value["versionId"]
    expected_size = value["expectedSize"]
    expected_sha = value["expectedSha256"]
    if (
        not isinstance(key, str)
        or not is_safe_relative(key)
        or not isinstance(version_id, str)
        or not VERSION_ID_RE.fullmatch(version_id)
        or not isinstance(expected_size, int)
        or isinstance(expected_size, bool)
        or expected_size <= 0
        or expected_size > 5 * 1024 * 1024 * 1024 * 1024
        or not isinstance(expected_sha, str)
        or not SHA256_RE.fullmatch(expected_sha)
    ):
        raise RuntimeError("streamed version digest request invalid")
    cos_client, _identity_client, _security_token = provider_clients(region)
    stream = None
    try:
        response = cos_client.get_object(
            Bucket=bucket, Key=key, versionId=version_id
        )
        if response.get("x-cos-version-id") != version_id:
            raise RuntimeError("streamed version identity changed")
        stream = response["Body"].get_raw_stream()
        digest = hashlib.sha256()
        observed_size = 0
        while True:
            chunk = stream.read(1024 * 1024)
            if not chunk:
                break
            if not isinstance(chunk, bytes):
                raise RuntimeError("streamed version returned non-bytes")
            observed_size += len(chunk)
            if observed_size > expected_size:
                raise RuntimeError("streamed version exceeded its manifest size")
            digest.update(chunk)
    except Exception as error:
        raise RuntimeError("streamed exact-version digest failed") from error
    finally:
        if stream is not None:
            try:
                stream.close()
            except Exception:
                pass
    observed_sha = digest.hexdigest()
    if observed_size != expected_size or observed_sha != expected_sha:
        raise RuntimeError("streamed version differs from its content address")
    return {
        "schemaVersion": 1,
        "key": key,
        "versionId": version_id,
        "byteSize": observed_size,
        "sha256": observed_sha,
    }


def provider_get_retention() -> dict[str, Any]:
    _value, bucket, region, key, version_id, cos_client, security_token = provider_object_request()
    try:
        exact_head = cos_client.head_object(
            Bucket=bucket, Key=key, versionId=version_id
        )
        current_head = cos_client.head_object(Bucket=bucket, Key=key)
        if (
            exact_head.get("x-cos-version-id") != version_id
            or current_head.get("x-cos-version-id") != version_id
        ):
            raise RuntimeError("retention target is not the exact current version")
        request_parameters = {"retention": "", "versionId": version_id}
        request_headers = {}
        if security_token is not None:
            request_headers["x-cos-security-token"] = security_token
        url = cos_client.get_presigned_url(
            Bucket=bucket,
            Key=key,
            Method="GET",
            Expired=60,
            Params=request_parameters,
            Headers=request_headers,
            SignHost=True,
        )
        parsed_url = urllib.parse.urlsplit(url)
        signed_query = urllib.parse.parse_qs(parsed_url.query, keep_blank_values=True)
        signed_parameters = signed_query.get("q-url-param-list", [])
        signed_headers = signed_query.get("q-header-list", [])
        if (
            signed_query.get("versionId") != [version_id]
            or signed_query.get("retention") != [""]
            or len(signed_parameters) != 1
            or not {"retention", "versionid"} <= set(signed_parameters[0].split(";"))
            or (security_token is not None and (
                len(signed_headers) != 1
                or "x-cos-security-token" not in set(signed_headers[0].split(";"))
            ))
        ):
            raise RuntimeError("retention signature is not version/token bound")
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        request = urllib.request.Request(url, headers=request_headers, method="GET")
        with opener.open(request, timeout=30) as response:
            body = response.read(16 * 1024 + 1)
            server_date = response.headers.get("Date")
            if response.status != 200 or len(body) > 16 * 1024:
                raise RuntimeError("retention response invalid")
    except Exception as error:
        raise RuntimeError("object retention query failed") from error
    if b"<!DOCTYPE" in body.upper() or b"<!ENTITY" in body.upper():
        raise RuntimeError("retention XML contains a declaration")
    try:
        root = ET.fromstring(body)
    except ET.ParseError as error:
        raise RuntimeError("retention XML malformed") from error

    def local_name(tag: str) -> str:
        return tag.rsplit("}", 1)[-1]

    if local_name(root.tag) != "Retention" or len(root) != 2:
        raise RuntimeError("retention XML shape changed")
    children = {local_name(child.tag): child.text for child in root}
    if set(children) != {"Mode", "RetainUntilDate"}:
        raise RuntimeError("retention XML fields changed")
    return {
        "schemaVersion": 1,
        "key": key,
        "versionId": version_id,
        "isLatest": True,
        "mode": children["Mode"],
        "retainUntilDate": children["RetainUntilDate"],
        "legalHoldStatus": LEGAL_HOLD_CAPABILITY,
        "serverDate": server_date,
    }


def provider_delete_version() -> dict[str, Any]:
    _value, bucket, _region, key, version_id, cos_client, _security_token = provider_object_request()
    try:
        response = cos_client.delete_object(
            Bucket=bucket, Key=key, versionId=version_id
        )
    except Exception as error:
        raise RuntimeError("exact version delete failed") from error
    returned_version = response.get("x-cos-version-id")
    delete_marker = str(response.get("x-cos-delete-marker", "false")).lower() == "true"
    if returned_version != version_id:
        raise RuntimeError("delete response version mismatch")
    return {
        "schemaVersion": 1,
        "key": key,
        "versionId": version_id,
        "deleted": True,
        "deleteMarker": delete_marker,
    }


def provider_main(operation: str) -> int:
    try:
        handlers = {
            "inspect-destination": provider_inspect,
            "list-versions": provider_list_versions,
            "read-version": provider_read_version,
            "hash-version": provider_hash_version,
            "get-retention": provider_get_retention,
            "delete-version": provider_delete_version,
        }
        handler = handlers.get(operation)
        if handler is None:
            return 64
        response = handler()
        sys.stdout.buffer.write(canonical_bytes(response) + b"\n")
        return 0
    except Exception:
        # Provider exceptions can include request URLs, signatures, or secret
        # material.  Deliberately emit no diagnostic across this boundary.
        return 92


def add_invocation_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--remote", required=True)
    parser.add_argument("--prefix", required=True)
    parser.add_argument("--account", required=True)
    parser.add_argument("--bucket", required=True)
    parser.add_argument("--region", required=True)
    parser.add_argument("--principal", required=True)
    parser.add_argument("--rclone-config", required=True, dest="rclone_config")


def command_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(add_help=False)
    subparsers = parser.add_subparsers(dest="command", required=True)
    subparsers.add_parser("runtime-check", add_help=False)
    verify = subparsers.add_parser("verify-destination", add_help=False)
    add_invocation_arguments(verify)
    for name in ("review-candidate", "delete-reviewed"):
        candidate = subparsers.add_parser(name, add_help=False)
        add_invocation_arguments(candidate)
        candidate.add_argument(
            "--kind", choices=("set", "blob", "upload"), required=True
        )
        candidate.add_argument("--relative-path", required=True, dest="relative_path")
        candidate.add_argument("--evidence", required=True)
        candidate.add_argument("--proposal", required=True)
        if name == "review-candidate":
            candidate.add_argument("--ticket-output", required=True, dest="ticket_output")
        else:
            candidate.add_argument("--ticket", required=True)
            candidate.add_argument("--ticket-sha256", required=True, dest="ticket_sha256")
            candidate.add_argument(
                "--prepare-only", action="store_true", dest="prepare_only"
            )
    return parser


def main(argv: list[str]) -> int:
    try:
        runtime_gate()
    except GuardError as error:
        print(f"portfolio COS prune guard failed: {error}", file=sys.stderr)
        return 1
    if len(argv) >= 2 and argv[0] == "__provider":
        return provider_main(argv[1])
    try:
        args = command_parser().parse_args(argv)
        if args.command == "runtime-check":
            print("SAFE")
        elif args.command == "verify-destination":
            invocation = invocation_from_args(args)
            inspect_destination(CosApi(), invocation)
            print("SAFE")
        elif args.command == "review-candidate":
            review_candidate(args)
        elif args.command == "delete-reviewed":
            delete_reviewed(args)
        else:
            fail("unsupported prune guard command")
        return 0
    except GuardError as error:
        print(f"portfolio COS prune guard failed: {error}", file=sys.stderr)
        return 1
    except SystemExit:
        return 64
    except Exception:
        print("portfolio COS prune guard failed: internal validation failed closed", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
