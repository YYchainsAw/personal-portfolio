#!/usr/bin/env python3
"""Offline stateful provider for cos-prune-guard-contract.sh.

This is deliberately not a Tencent emulator.  It implements only the guard's
documented JSON boundary and exposes deterministic fault switches so pagination,
version, retention, identity, and delete/read-back failures can be exercised
without a network or cloud credential.
"""

from __future__ import annotations

import base64
import copy
import email.utils
import hashlib
import json
import os
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any


LEGAL_HOLD_CAPABILITY = "NOT_SUPPORTED_BY_TENCENT_COS_API_2026-06-03"
OLD_SET = "20200101T000000Z-000000000001"
CURRENT_SET = "20200102T000000Z-000000000002"
UNREVIEWED_SET = "20200103T000000Z-000000000003"
ORPHAN_UPLOAD = "20191231T000000Z-000000000004"
PREFIX = "production"
BUCKET = "backup-bucket"
REGION = "ap-guangzhou"
ACCOUNT = "backup-account"
PRINCIPAL = "backup-pruner"


def canonical(value: Any) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":")).encode("ascii")


def sha256(body: bytes) -> str:
    return hashlib.sha256(body).hexdigest()


def rfc3339(value: datetime) -> str:
    return value.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def save(path: Path, value: dict[str, Any]) -> None:
    temporary = path.with_suffix(path.suffix + ".new")
    temporary.write_bytes(canonical(value) + b"\n")
    os.chmod(temporary, 0o600)
    os.replace(temporary, path)


def load(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError("state")
    return value


def manifest(set_id: str, finished: str, blob_sha: str, blob_size: int) -> dict[str, Any]:
    artifacts = {
        "databaseDump": b"encrypted-database-" + set_id.encode("ascii"),
        "localMediaTar": b"encrypted-local-media-" + set_id.encode("ascii"),
        "mediaManifest": b"encrypted-media-manifest-" + set_id.encode("ascii"),
    }
    return {
        "artifacts": {
            "databaseDump": {
                "byteSize": len(artifacts["databaseDump"]),
                "ciphertextSha256": sha256(artifacts["databaseDump"]),
                "file": "database.dump.age",
            },
            "localMediaTar": {
                "byteSize": len(artifacts["localMediaTar"]),
                "ciphertextSha256": sha256(artifacts["localMediaTar"]),
                "file": "local-media.tar.age",
            },
            "mediaManifest": {
                "byteSize": len(artifacts["mediaManifest"]),
                "ciphertextSha256": sha256(artifacts["mediaManifest"]),
                "file": "media-manifest.json.age",
            },
        },
        "backupFinishedAt": finished,
        "backupStartedAt": finished,
        "blobs": [
            {"byteSize": blob_size, "path": f"blobs/{blob_sha}", "sha256": blob_sha}
        ],
        "counts": {
            "cosObjects": 1,
            "cosVerificationSamples": 1,
            "distinctCosBlobs": 1,
            "localObjects": 0,
            "totalObjects": 1,
        },
        "releaseId": "aaaaaaaaaaaa-bbbbbbbbbbbb",
        "retention": {"daily": True, "monthly": False, "weekly": False},
        "schemaVersion": 1,
        "setId": set_id,
        "snapshotId": "fixture-snapshot-" + set_id[-12:],
    }


def artifact_bodies(set_id: str) -> dict[str, bytes]:
    return {
        "database.dump.age": b"encrypted-database-" + set_id.encode("ascii"),
        "local-media.tar.age": b"encrypted-local-media-" + set_id.encode("ascii"),
        "media-manifest.json.age": b"encrypted-media-manifest-" + set_id.encode("ascii"),
    }


def version(version_id: str, body: bytes, *, latest: bool = True) -> dict[str, Any]:
    return {
        "bodyBase64": base64.b64encode(body).decode("ascii"),
        "entryType": "OBJECT",
        "etag": '"' + sha256(body)[:32] + '"',
        "isLatest": latest,
        "lastModified": "2020-01-01T00:00:00Z",
        "retainUntilDate": "2020-01-15T00:00:00Z",
        "versionId": version_id,
    }


def initialize(state_path: Path, fixture_root: Path) -> None:
    fixture_root.mkdir(parents=True, exist_ok=True)
    os.chmod(fixture_root, 0o700)
    delete_blob = b"delete-only-blob"
    protected_blob = b"protected-current-blob"
    delete_sha = sha256(delete_blob)
    protected_sha = sha256(protected_blob)
    manifests = {
        OLD_SET: manifest(OLD_SET, "2020-01-01T00:00:00Z", delete_sha, len(delete_blob)),
        CURRENT_SET: manifest(
            CURRENT_SET,
            "2020-01-02T00:00:00Z",
            protected_sha,
            len(protected_blob),
        ),
    }
    objects: dict[str, dict[str, Any]] = {}
    manifest_shas: dict[str, str] = {}
    counter = 0
    for set_id in (OLD_SET, CURRENT_SET):
        manifest_body = canonical(manifests[set_id]) + b"\n"
        manifest_sha = sha256(manifest_body)
        manifest_shas[set_id] = manifest_sha
        evidence = fixture_root / f"{set_id}.manifest.json"
        evidence.write_bytes(manifest_body)
        os.chmod(evidence, 0o600)
        bodies = artifact_bodies(set_id)
        bodies["set-manifest.json"] = manifest_body
        bodies["VERIFIED"] = (manifest_sha + "\n").encode("ascii")
        for filename in sorted(bodies):
            counter += 1
            key = f"{PREFIX}/uploading/{set_id}/{filename}"
            objects[key] = {"versions": [version(f"v-{counter:04d}", bodies[filename])]}
        completion_body = canonical(
            {
                "manifestByteSize": len(manifest_body),
                "manifestSha256": manifest_sha,
                "schemaVersion": 2,
                "setId": set_id,
                "uploadPrefix": f"uploading/{set_id}",
            }
        ) + b"\n"
        counter += 1
        objects[f"{PREFIX}/sets/{set_id}/VERIFIED"] = {
            "versions": [version(f"v-{counter:04d}", completion_body)]
        }

    for filename, body in sorted(
        {
            "database.dump.age": b"abandoned-encrypted-database",
            "local-media.tar.age": b"abandoned-encrypted-media",
        }.items()
    ):
        counter += 1
        objects[f"{PREFIX}/uploading/{ORPHAN_UPLOAD}/{filename}"] = {
            "versions": [version(f"v-{counter:04d}", body)]
        }

    counter += 1
    objects[f"{PREFIX}/blobs/{delete_sha}"] = {
        "versions": [version(f"v-{counter:04d}", delete_blob)]
    }
    counter += 1
    objects[f"{PREFIX}/blobs/{protected_sha}"] = {
        "versions": [version(f"v-{counter:04d}", protected_blob)]
    }
    proposal = {
        "currentSet": CURRENT_SET,
        "deleteBlobs": [f"blobs/{delete_sha}"],
        "deleteSets": [OLD_SET],
        "deleteUploads": [ORPHAN_UPLOAD],
        "destination": {
            "accountId": ACCOUNT,
            "bucket": BUCKET,
            "principalId": PRINCIPAL,
            "region": REGION,
        },
        "generatedAt": rfc3339(datetime.now(timezone.utc)),
        "observedSets": [
            {
                "manifestSha256": manifest_shas[OLD_SET],
                "objectNamespace": "uploading",
                "setId": OLD_SET,
            },
            {
                "manifestSha256": manifest_shas[CURRENT_SET],
                "objectNamespace": "uploading",
                "setId": CURRENT_SET,
            },
        ],
        "policy": {"daily": 7, "monthly": 6, "safetyDays": 14, "weekly": 4},
        "prefix": PREFIX,
        "protectedSets": [CURRENT_SET],
        "reachableBlobs": [f"blobs/{protected_sha}"],
        "retainedSets": [CURRENT_SET],
        "schemaVersion": 3,
    }
    proposal_path = fixture_root / "proposal.json"
    proposal_path.write_bytes(canonical(proposal) + b"\n")
    os.chmod(proposal_path, 0o600)
    metadata = {
        "currentSet": CURRENT_SET,
        "deleteBlob": f"blobs/{delete_sha}",
        "deleteSet": OLD_SET,
        "deleteSetEvidence": str(fixture_root / f"{OLD_SET}.manifest.json"),
        "deleteUpload": ORPHAN_UPLOAD,
        "proposal": str(proposal_path),
        "protectedBlob": f"blobs/{protected_sha}",
    }
    metadata_path = fixture_root / "metadata.json"
    metadata_path.write_bytes(canonical(metadata) + b"\n")
    os.chmod(metadata_path, 0o600)
    state = {
        "counters": {},
        "deleteLog": [],
        "destination": {
            "accountId": ACCOUNT,
            "bucket": BUCKET,
            "legalHoldCapability": LEGAL_HOLD_CAPABILITY,
            "objectLock": {
                "defaultRetentionDays": 30,
                "enabled": True,
                "mode": "COMPLIANCE",
            },
            "prefix": PREFIX,
            "principalArn": "qcs::cam::uin/backup-account:uin/backup-pruner",
            "principalId": PRINCIPAL,
            "principalType": "CAMRole",
            "region": REGION,
            "schemaVersion": 1,
            "versioning": "Enabled",
        },
        "fault": "none",
        "objects": objects,
        "pageSize": 1,
    }
    save(state_path, state)


def set_fault(state_path: Path, fault: str) -> None:
    state = load(state_path)
    state["fault"] = fault
    state["counters"] = {}
    save(state_path, state)


def mutate(state_path: Path, action: str) -> None:
    state = load(state_path)
    metadata = json.loads((state_path.parent / "metadata.json").read_text(encoding="utf-8"))
    if action == "add-candidate-version":
        key = f"{PREFIX}/{metadata['deleteBlob']}"
        versions = state["objects"][key]["versions"]
        for item in versions:
            item["isLatest"] = False
        extra = copy.deepcopy(versions[0])
        extra["versionId"] = "v-concurrent-candidate"
        extra["isLatest"] = True
        versions.append(extra)
    elif action == "change-protected-version":
        key = f"{PREFIX}/sets/{CURRENT_SET}/VERIFIED"
        state["objects"][key]["versions"][0]["etag"] = '"concurrent-protected-etag"'
    elif action == "add-unreviewed-set":
        key = f"{PREFIX}/sets/{UNREVIEWED_SET}/VERIFIED"
        state["objects"][key] = {
            "versions": [version("v-unreviewed", b"unreviewed\n")]
        }
    else:
        raise ValueError("unknown mutation")
    save(state_path, state)


def recreate_old_set(state: dict[str, Any]) -> None:
    delete_blob = b"delete-only-blob"
    delete_sha = sha256(delete_blob)
    document = manifest(
        OLD_SET, "2020-01-01T00:00:00Z", delete_sha, len(delete_blob)
    )
    manifest_body = canonical(document) + b"\n"
    manifest_sha = sha256(manifest_body)
    completion_body = canonical(
        {
            "manifestByteSize": len(manifest_body),
            "manifestSha256": manifest_sha,
            "schemaVersion": 2,
            "setId": OLD_SET,
            "uploadPrefix": f"uploading/{OLD_SET}",
        }
    ) + b"\n"
    key = f"{PREFIX}/sets/{OLD_SET}/VERIFIED"
    state["objects"][key] = {
        "versions": [version("v-recreated-completion", completion_body)]
    }


def provider_input() -> dict[str, Any]:
    raw = sys.stdin.buffer.read(8 * 1024 * 1024 + 1)
    if not raw or len(raw) > 8 * 1024 * 1024:
        raise ValueError("input")
    value = json.loads(raw.decode("utf-8"))
    if not isinstance(value, dict):
        raise ValueError("input")
    return value


def public_version(key: str, raw: dict[str, Any]) -> dict[str, Any]:
    body = base64.b64decode(raw.get("bodyBase64", ""))
    return {
        "entryType": raw["entryType"],
        "etag": raw.get("etag"),
        "isLatest": raw["isLatest"],
        "key": key,
        "lastModified": raw["lastModified"],
        "size": 0 if raw["entryType"] == "DELETE_MARKER" else len(body),
        "versionId": raw.get("versionId"),
    }


def locate(state: dict[str, Any], key: str, version_id: str) -> dict[str, Any]:
    for raw in state.get("objects", {}).get(key, {}).get("versions", []):
        if raw.get("versionId") == version_id:
            return raw
    raise ValueError("version absent")


def inspect(state: dict[str, Any], _request: dict[str, Any]) -> dict[str, Any]:
    response = copy.deepcopy(state["destination"])
    fault = state["fault"]
    changes = {
        "wrong-account": ("accountId", "other-account"),
        "wrong-bucket": ("bucket", "other-bucket"),
        "wrong-region": ("region", "ap-beijing"),
        "wrong-principal": ("principalId", "other-pruner"),
        "root-principal": ("principalType", "Root"),
        "suspended-versioning": ("versioning", "Suspended"),
        "legal-capability-changed": ("legalHoldCapability", "SUPPORTED_UNKNOWN"),
    }
    if fault in changes:
        field, value = changes[fault]
        response[field] = value
    elif fault == "no-object-lock":
        response["objectLock"]["enabled"] = False
    elif fault == "governance-object-lock":
        response["objectLock"]["mode"] = "GOVERNANCE"
    elif fault == "short-object-lock":
        response["objectLock"]["defaultRetentionDays"] = 1
    return response


def list_versions(state: dict[str, Any], request: dict[str, Any]) -> dict[str, Any]:
    prefix = request["prefix"]
    key_marker = request["keyMarker"]
    version_marker = request["versionIdMarker"]
    if key_marker == "" and version_marker == "":
        offset = 0
    else:
        offset = -1

    counters = state.setdefault("counters", {})
    if prefix == f"{PREFIX}/sets/" and offset == 0:
        starts = counters.get("setSnapshotStarts", 0) + 1
        counters["setSnapshotStarts"] = starts
        if state["fault"] == "race-second-snapshot" and starts == 2:
            key = f"{PREFIX}/sets/{OLD_SET}/VERIFIED"
            existing = state["objects"][key]["versions"]
            for raw in existing:
                raw["isLatest"] = False
            marker = version("v-race-marker", b"")
            marker.update(
                {
                    "bodyBase64": "",
                    "entryType": "DELETE_MARKER",
                    "etag": None,
                    "isLatest": True,
                }
            )
            existing.append(marker)

    entries: list[dict[str, Any]] = []
    for key, record in state["objects"].items():
        if key.startswith(prefix):
            for raw in record["versions"]:
                entries.append(public_version(key, raw))
    fault = state["fault"]
    if fault == "historical-version":
        target = f"{PREFIX}/sets/{OLD_SET}/VERIFIED"
        matching = next((item for item in entries if item["key"] == target), None)
        if matching is not None:
            matching["isLatest"] = True
            historical = copy.deepcopy(matching)
            historical["versionId"] = "v-historical"
            historical["isLatest"] = False
            historical["lastModified"] = "2019-01-01T00:00:00Z"
            entries.append(historical)
    elif fault == "delete-marker":
        target = f"{PREFIX}/sets/{OLD_SET}/VERIFIED"
        matching = next((item for item in entries if item["key"] == target), None)
        if matching is not None:
            matching["isLatest"] = False
            marker = copy.deepcopy(matching)
            marker.update(
                {
                    "entryType": "DELETE_MARKER",
                    "etag": None,
                    "isLatest": True,
                    "size": 0,
                    "versionId": "v-delete-marker",
                }
            )
            entries.append(marker)
    elif fault == "null-version-id" and entries:
        entries[0]["versionId"] = None
    elif fault == "nonlatest-version" and entries:
        entries[0]["isLatest"] = False
    elif fault == "prefix-collision" and prefix.startswith(f"{PREFIX}/blobs/") and entries:
        collision = copy.deepcopy(entries[0])
        collision["key"] = prefix + "-collision"
        collision["versionId"] = "v-prefix-collision"
        entries.append(collision)
    elif fault == "protected-blob-missing" and prefix == f"{PREFIX}/blobs/{sha256(b'protected-current-blob')}":
        entries = []
    elif fault == "protected-blob-size" and prefix == f"{PREFIX}/blobs/{sha256(b'protected-current-blob')}" and entries:
        entries[0]["size"] += 1
    entries.sort(key=lambda item: (item["key"], str(item["versionId"]), item["entryType"]))
    if offset == -1:
        matching_offsets = [
            index
            for index, entry in enumerate(entries)
            if entry["key"] == key_marker
            and (version_marker == "" or entry["versionId"] == version_marker)
        ]
        if not matching_offsets:
            raise ValueError("marker")
        offset = matching_offsets[-1] + 1

    page_size = int(state["pageSize"])
    page = entries[offset : offset + page_size]
    end = offset + len(page)
    truncated = end < len(entries)
    next_key: str | None = page[-1]["key"] if truncated and page else ""
    # Tencent documents that NextVersionIdMarker may legitimately be empty when
    # the next page advances to another key. It is required here when a page
    # boundary splits multiple versions of the same key.
    next_version: str | None = (
        str(page[-1]["versionId"])
        if truncated and page and entries[end]["key"] == page[-1]["key"]
        else ""
    )
    if fault == "truncated-no-markers" and offset == 0:
        truncated = True
        next_key = ""
        next_version = ""
    elif fault == "duplicate-page" and offset > 0 and entries:
        page = [entries[0], *page]
    elif fault == "omit-page" and offset > 0:
        page = []
        truncated = False
        next_key = ""
        next_version = ""
    elif fault == "terminal-continuation-marker" and not truncated:
        next_key = "unexpected"
        next_version = "unexpected"
    return {
        "bucket": request["bucket"],
        "entries": page,
        "isTruncated": truncated,
        "keyMarker": key_marker,
        "nextKeyMarker": next_key,
        "nextVersionIdMarker": next_version,
        "prefix": prefix,
        "region": request["region"],
        "schemaVersion": 1,
        "versionIdMarker": version_marker,
    }


def read_version(state: dict[str, Any], request: dict[str, Any]) -> dict[str, Any]:
    raw = locate(state, request["key"], request["versionId"])
    body = base64.b64decode(raw["bodyBase64"])
    response_body = body
    if state["fault"] == "readback-mismatch" and request["key"].endswith("set-manifest.json"):
        response_body = body[:-1] + (b" " if body else b"x")
    response = {
        "bodyBase64": base64.b64encode(response_body).decode("ascii"),
        "byteSize": len(body),
        "key": request["key"],
        "schemaVersion": 1,
        "sha256": sha256(body),
        "versionId": request["versionId"],
    }
    if state["fault"] == "readback-wrong-version":
        response["versionId"] = "v-wrong"
    return response


def retention(state: dict[str, Any], request: dict[str, Any]) -> dict[str, Any]:
    raw = locate(state, request["key"], request["versionId"])
    now = datetime.now(timezone.utc)
    response = {
        "isLatest": raw["isLatest"],
        "key": request["key"],
        "legalHoldStatus": LEGAL_HOLD_CAPABILITY,
        "mode": "COMPLIANCE",
        "retainUntilDate": raw["retainUntilDate"],
        "schemaVersion": 1,
        "serverDate": email.utils.format_datetime(now, usegmt=True),
        "versionId": request["versionId"],
    }
    if state["fault"] == "active-retention":
        response["retainUntilDate"] = rfc3339(now + timedelta(days=1))
    elif state["fault"] == "governance-retention":
        response["mode"] = "GOVERNANCE"
    elif state["fault"] == "legal-hold-unknown":
        response["legalHoldStatus"] = "UNKNOWN"
    elif state["fault"] == "server-clock-skew":
        response["serverDate"] = email.utils.format_datetime(now + timedelta(hours=1), usegmt=True)
    return response


def hash_version(state: dict[str, Any], request: dict[str, Any]) -> dict[str, Any]:
    raw = locate(state, request["key"], request["versionId"])
    body = base64.b64decode(raw["bodyBase64"])
    observed_sha = sha256(body)
    if state["fault"] == "protected-blob-hash" and request["key"].endswith(
        sha256(b"protected-current-blob")
    ):
        observed_sha = "0" * 64
    return {
        "byteSize": len(body),
        "key": request["key"],
        "schemaVersion": 1,
        "sha256": observed_sha,
        "versionId": request["versionId"],
    }


def delete_version(state: dict[str, Any], request: dict[str, Any]) -> dict[str, Any]:
    key = request["key"]
    version_id = request["versionId"]
    locate(state, key, version_id)
    fault = state["fault"]
    delete_calls = state.setdefault("counters", {}).get("deleteCalls", 0) + 1
    state["counters"]["deleteCalls"] = delete_calls
    if fault == "fail-second-delete" and delete_calls == 2:
        raise ValueError("injected second exact-version delete failure")
    if fault not in {"delete-noop", "delete-response-mismatch"}:
        versions = state["objects"][key]["versions"]
        state["objects"][key]["versions"] = [
            raw for raw in versions if raw.get("versionId") != version_id
        ]
        if not state["objects"][key]["versions"]:
            del state["objects"][key]
        state["deleteLog"].append({"key": key, "versionId": version_id})
    response = {
        "deleteMarker": False,
        "deleted": True,
        "key": key,
        "schemaVersion": 1,
        "versionId": version_id,
    }
    if fault == "delete-response-mismatch":
        response["versionId"] = "v-wrong"
    elif fault == "delete-marker-response":
        response["deleteMarker"] = True
    elif fault == "post-delete-marker":
        marker = version("v-post-delete-marker", b"")
        marker.update(
            {
                "bodyBase64": "",
                "entryType": "DELETE_MARKER",
                "etag": None,
                "isLatest": True,
            }
        )
        state["objects"].setdefault(key, {"versions": []})["versions"].append(marker)
    elif fault == "recreate-set-after-blob-delete" and key.startswith(
        f"{PREFIX}/blobs/"
    ):
        recreate_old_set(state)
    return response


def provider(operation: str) -> int:
    state_path_value = os.environ.get("FIXTURE_COS_STATE", "")
    if not state_path_value:
        return 90
    state_path = Path(state_path_value)
    try:
        state = load(state_path)
        request = provider_input()
        if state["fault"] == "secret-diagnostic":
            print("fixture-secret-MUST-NOT-LEAK", file=sys.stderr)
            return 91
        handlers = {
            "inspect-destination": inspect,
            "list-versions": list_versions,
            "read-version": read_version,
            "get-retention": retention,
            "hash-version": hash_version,
            "delete-version": delete_version,
        }
        handler = handlers.get(operation)
        if handler is None:
            return 64
        response = handler(state, request)
        save(state_path, state)
        sys.stdout.buffer.write(canonical(response) + b"\n")
        return 0
    except Exception:
        return 92


def main(argv: list[str]) -> int:
    if len(argv) == 3 and argv[0] == "--init":
        initialize(Path(argv[1]), Path(argv[2]))
        return 0
    if len(argv) == 3 and argv[0] == "--fault":
        set_fault(Path(argv[1]), argv[2])
        return 0
    if len(argv) == 3 and argv[0] == "--mutate":
        mutate(Path(argv[1]), argv[2])
        return 0
    if len(argv) == 1:
        return provider(argv[0])
    return 64


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
