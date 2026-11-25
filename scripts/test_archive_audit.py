import base64
from datetime import datetime, timedelta, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import unittest
from types import SimpleNamespace
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("archive", Path(__file__).with_name("archive-audit.py"))
archive = importlib.util.module_from_spec(spec)
spec.loader.exec_module(archive)


class AuditExportTest(unittest.TestCase):
    def page(self):
        record = json.dumps([1, "t", 1, archive.GENESIS, 123, "uuid", "actor", None,
                              "sample", "kind", "key", None, None, None, 1234])
        digest = hashlib.sha256(record.encode()).hexdigest()
        return {"events": [{"canonical": record, "eventHash": digest}], "valid": True,
                "after": 0, "next": 1, "head": 1, "headHash": digest, "endHash": digest}

    def test_hash_scope_and_preceding_anchor(self):
        page = self.page()
        records, end, digest = archive.verify(page, "t", 0, archive.GENESIS)
        self.assertEqual((end, digest), (1, page["headHash"]))
        with self.assertRaises(ValueError):
            archive.verify(page, "other", 0, archive.GENESIS)
        with self.assertRaises(ValueError):
            archive.verify(page, "t", 0, "1" * 64)
        page["events"][0]["canonical"] = records[0].replace("actor", "other")
        with self.assertRaises(ValueError):
            archive.verify(page, "t", 0, archive.GENESIS)

    def test_locked_version_and_checksums_are_verified(self):
        page = self.page()
        records, end, digest = archive.verify(page, "t", 0, archive.GENESIS)
        observed = {}

        def mock_s3(operation, *args):
            if operation == "put-object":
                self.assertIn("COMPLIANCE", args)
                observed["sha"] = base64.b64encode(hashlib.sha256(
                    Path(args[args.index("--body") + 1]).read_bytes()).digest()).decode()
                self.assertEqual(observed["sha"], args[args.index("--checksum-sha256") + 1])
                return {"VersionId": "1"}
            self.assertIn("--version-id", args)
            return {"ChecksumSHA256": observed["sha"], "ObjectLockMode": "COMPLIANCE",
                    "ObjectLockRetainUntilDate": (datetime.now(timezone.utc) + timedelta(days=7)).isoformat()}

        options = SimpleNamespace(bucket="test", bucket_owner="owner", days=7)
        with patch.object(archive, "s3", side_effect=mock_s3):
            receipt = archive.upload(records, "t", 0, end, digest, options)
        self.assertEqual(digest, receipt["endHash"])
        with patch.object(archive, "s3", side_effect=[{"VersionId": "1"}, {"ChecksumSHA256": "wrong"}]):
            with self.assertRaises(ValueError):
                archive.upload(records, "t", 0, end, digest, options)


if __name__ == "__main__":
    unittest.main()
