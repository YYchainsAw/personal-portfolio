#!/usr/bin/env python3
"""Offline contract for the built-in Tencent provider boundary."""

from __future__ import annotations

import importlib.util
import sys
import types
import unittest
import urllib.parse
from pathlib import Path
from unittest import mock


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
GUARD_PATH = REPOSITORY_ROOT / "deploy" / "backup" / "cos-prune-guard.py"
SPEC = importlib.util.spec_from_file_location("portfolio_cos_prune_guard", GUARD_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("guard module cannot be loaded")
guard = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = guard
SPEC.loader.exec_module(guard)


class FakeCallerRequest:
    def from_json_string(self, value: str) -> None:
        if value != "{}":
            raise AssertionError("unexpected STS request")


def install_fake_sts_models() -> None:
    packages = {
        "tencentcloud": types.ModuleType("tencentcloud"),
        "tencentcloud.sts": types.ModuleType("tencentcloud.sts"),
        "tencentcloud.sts.v20180813": types.ModuleType(
            "tencentcloud.sts.v20180813"
        ),
        "tencentcloud.sts.v20180813.models": types.ModuleType(
            "tencentcloud.sts.v20180813.models"
        ),
    }
    for name, module in packages.items():
        if name != "tencentcloud.sts.v20180813.models":
            module.__path__ = []  # type: ignore[attr-defined]
        sys.modules[name] = module
    packages["tencentcloud.sts.v20180813.models"].GetCallerIdentityRequest = (
        FakeCallerRequest
    )
    packages["tencentcloud.sts.v20180813"].models = packages[
        "tencentcloud.sts.v20180813.models"
    ]


class FakeCosInspection:
    def head_bucket(self, **kwargs):
        self._bucket(kwargs)
        return {"x-cos-bucket-region": "ap-guangzhou"}

    def get_bucket_versioning(self, **kwargs):
        self._bucket(kwargs)
        return {"Status": "Enabled"}

    def get_bucket_object_lock(self, **kwargs):
        self._bucket(kwargs)
        return {
            "ObjectLockEnabled": "Enabled",
            "Rule": {"DefaultRetention": {"Mode": "COMPLIANCE", "Days": "30"}},
        }

    def list_objects_versions(self, **kwargs):
        self._bucket(kwargs)
        if kwargs.get("Prefix") != "production/" or kwargs.get("MaxKeys") != 1:
            raise AssertionError("destination prefix probe changed")
        return {"Name": "backup-bucket", "Prefix": "production/"}

    @staticmethod
    def _bucket(kwargs):
        if kwargs.get("Bucket") != "backup-bucket":
            raise AssertionError("bucket changed")


class FakeIdentityClient:
    def __init__(self, identity):
        self.identity = identity

    def GetCallerIdentity(self, request):
        if not isinstance(request, FakeCallerRequest):
            raise AssertionError("wrong STS request type")
        return self.identity


class ProviderInspectionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        install_fake_sts_models()

    def payload(self):
        return {
            "accountId": "backup-account",
            "bucket": "backup-bucket",
            "prefix": "production",
            "principalId": "backup-pruner",
            "region": "ap-guangzhou",
            "schemaVersion": 1,
        }

    @staticmethod
    def valid_identity():
        return types.SimpleNamespace(
            AccountId="backup-account",
            Arn="qcs::cam::uin/backup-account:roleName/backup-pruner",
            PrincipalId="backup-pruner",
            Type="CAMRole",
        )

    def inspect(self, identity):
        with mock.patch.object(guard, "provider_input", return_value=self.payload()), mock.patch.object(
            guard,
            "provider_clients",
            return_value=(FakeCosInspection(), FakeIdentityClient(identity), "sts-token"),
        ):
            return guard.provider_inspect()

    def test_valid_sts_shape_is_preserved(self) -> None:
        response = self.inspect(self.valid_identity())
        self.assertEqual(response["accountId"], "backup-account")
        self.assertEqual(response["principalId"], "backup-pruner")
        self.assertEqual(response["principalType"], "CAMRole")

    def test_missing_sts_field_fails(self) -> None:
        identity = self.valid_identity()
        del identity.Arn
        with self.assertRaisesRegex(RuntimeError, "STS caller identity response malformed"):
            self.inspect(identity)

    def test_root_sts_identity_fails(self) -> None:
        identity = self.valid_identity()
        identity.Type = "Root"
        with self.assertRaisesRegex(RuntimeError, "STS caller identity response malformed"):
            self.inspect(identity)

    def test_wrong_sts_field_types_fail(self) -> None:
        for field, value in (
            ("AccountId", 123),
            ("PrincipalId", ["backup-pruner"]),
            ("Arn", "not-a-qcs-arn"),
            ("Type", "UnknownIdentity"),
        ):
            with self.subTest(field=field):
                identity = self.valid_identity()
                setattr(identity, field, value)
                with self.assertRaisesRegex(
                    RuntimeError, "STS caller identity response malformed"
                ):
                    self.inspect(identity)


class FakeRetentionResponse:
    status = 200
    headers = {"Date": "Wed, 01 Jan 2025 00:00:00 GMT"}

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    @staticmethod
    def read(_maximum):
        return (
            b"<Retention><Mode>COMPLIANCE</Mode>"
            b"<RetainUntilDate>2024-01-01T00:00:00Z</RetainUntilDate></Retention>"
        )


class FakeOpener:
    def __init__(self):
        self.request = None

    def open(self, request, timeout):
        if timeout != 30:
            raise AssertionError("retention timeout changed")
        self.request = request
        return FakeRetentionResponse()


class FakeRetentionCos:
    def __init__(self, valid_url: bool = True):
        self.valid_url = valid_url
        self.presign = None

    @staticmethod
    def head_object(**kwargs):
        if kwargs.get("Bucket") != "backup-bucket" or kwargs.get("Key") != "production/blobs/abc":
            raise AssertionError("HEAD identity changed")
        return {"x-cos-version-id": "v-exact"}

    def get_presigned_url(self, **kwargs):
        self.presign = kwargs
        query = {
            "q-sign-algorithm": "sha1",
            "q-url-param-list": "retention;versionid",
            "q-header-list": "host;x-cos-security-token",
            "retention": "",
            "versionId": "v-exact",
        }
        if not self.valid_url:
            query.pop("versionId")
            query["q-url-param-list"] = "retention"
            query["q-header-list"] = "host"
        return "https://backup-bucket.cos.ap-guangzhou.myqcloud.com/object?" + urllib.parse.urlencode(query)


class ProviderRetentionSignatureTests(unittest.TestCase):
    def call_retention(self, cos):
        opener = FakeOpener()
        with mock.patch.object(
            guard,
            "provider_object_request",
            return_value=(
                {},
                "backup-bucket",
                "ap-guangzhou",
                "production/blobs/abc",
                "v-exact",
                cos,
                "temporary-sts-token",
            ),
        ), mock.patch.object(guard.urllib.request, "build_opener", return_value=opener):
            response = guard.provider_get_retention()
        return response, opener

    def test_signature_and_request_bind_token_and_exact_version(self) -> None:
        cos = FakeRetentionCos()
        response, opener = self.call_retention(cos)
        self.assertEqual(
            cos.presign["Params"], {"retention": "", "versionId": "v-exact"}
        )
        self.assertEqual(
            cos.presign["Headers"],
            {"x-cos-security-token": "temporary-sts-token"},
        )
        self.assertEqual(
            opener.request.get_header("X-cos-security-token"),
            "temporary-sts-token",
        )
        self.assertEqual(response["versionId"], "v-exact")

    def test_unsigned_version_or_token_fails_before_network(self) -> None:
        cos = FakeRetentionCos(valid_url=False)
        with self.assertRaisesRegex(
            RuntimeError, "object retention query failed"
        ):
            self.call_retention(cos)


class ProviderRuntimeVersionGateTests(unittest.TestCase):
    @staticmethod
    def distributions(versions: dict[str, str] | None = None) -> list[object]:
        values = versions or guard.PROVIDER_DEPENDENCY_VERSIONS
        return [
            types.SimpleNamespace(metadata={"Name": name}, version=version)
            for name, version in values.items()
        ]

    def test_exact_reviewed_dependency_closure_passes(self) -> None:
        self.assertEqual(len(guard.PROVIDER_DEPENDENCY_VERSIONS), 12)
        with mock.patch.object(
            guard.importlib.metadata,
            "distributions",
            return_value=self.distributions(),
        ):
            guard.provider_dependency_version_gate()

    def test_transitive_dependency_version_drift_fails_closed(self) -> None:
        versions = dict(guard.PROVIDER_DEPENDENCY_VERSIONS)
        versions["urllib3"] = "0.0.0-unreviewed"
        with mock.patch.object(
            guard.importlib.metadata,
            "distributions",
            return_value=self.distributions(versions),
        ), self.assertRaisesRegex(guard.GuardError, "reviewed lock"):
            guard.provider_dependency_version_gate()

    def test_missing_distribution_fails_closed(self) -> None:
        versions = dict(guard.PROVIDER_DEPENDENCY_VERSIONS)
        versions.pop("certifi")
        with mock.patch.object(
            guard.importlib.metadata,
            "distributions",
            return_value=self.distributions(versions),
        ), self.assertRaisesRegex(guard.GuardError, "closure"):
            guard.provider_dependency_version_gate()

    def test_unreviewed_extra_distribution_fails_closed(self) -> None:
        versions = dict(guard.PROVIDER_DEPENDENCY_VERSIONS)
        versions["setuptools"] = "unreviewed"
        with mock.patch.object(
            guard.importlib.metadata,
            "distributions",
            return_value=self.distributions(versions),
        ), self.assertRaisesRegex(guard.GuardError, "closure"):
            guard.provider_dependency_version_gate()


if __name__ == "__main__":
    unittest.main(verbosity=2)
