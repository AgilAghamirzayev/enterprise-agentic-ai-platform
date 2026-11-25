#!/usr/bin/env python3
"""Verify audit chain pages against a trusted cursor and store them in an Object Lock bucket."""
import argparse
import base64
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
from urllib.parse import urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


GENESIS = "0" * 64


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def verify(page, scope, cursor, previous):
    if page.get("valid") is not True or page.get("after") != cursor:
        raise ValueError("Server reported an invalid audit prefix")
    verified = []
    for entry in page["events"]:
        canonical = entry["canonical"]
        fields = json.loads(canonical)
        if len(fields) != 15 or fields[:4] != [1, scope, cursor + 1, previous]:
            raise ValueError("Audit scope, position or anchor mismatch")
        digest = hashlib.sha256(canonical.encode("utf-8")).hexdigest()
        if digest != entry.get("eventHash"):
            raise ValueError("Audit event hash mismatch")
        verified.append(canonical)
        previous, cursor = digest, cursor + 1
    if page.get("next") != cursor or page.get("endHash") != previous:
        raise ValueError("Invalid final audit cursor")
    if page["head"] < cursor or page["head"] == cursor and page["headHash"] != previous:
        raise ValueError("Invalid audit head")
    return verified, cursor, previous


def s3(command, *args):
    response = subprocess.run(["aws", "s3api", command, *args, "--output", "json", "--no-cli-pager"],
                              capture_output=True, text=True, check=True, timeout=90)
    return json.loads(response.stdout)


def upload(records, scope, start, end, digest, args):
    key = f"agent-platform/{scope}/{start + 1:020d}-{end:020d}-{digest}.json"
    data = json.dumps({"version": 1, "canonical": records}, ensure_ascii=False,
                      separators=(",", ":")).encode()
    checksum = base64.b64encode(hashlib.sha256(data).digest()).decode("ascii")
    expires = (datetime.now(timezone.utc) + timedelta(days=args.days)).replace(microsecond=0)
    location = ["--bucket", args.bucket, "--key", key, "--expected-bucket-owner", args.bucket_owner]
    with tempfile.TemporaryDirectory() as folder:
        path = Path(folder, "batch.json")
        path.write_bytes(data)
        try:
            result = s3("put-object", *location, "--body", str(path), "--content-type", "application/json",
                        "--checksum-sha256", checksum, "--if-none-match", "*", "--object-lock-mode",
                        "COMPLIANCE", "--object-lock-retain-until-date", expires.isoformat())
            version = result["VersionId"]
        except subprocess.CalledProcessError as error:
            if "PreconditionFailed" not in error.stderr:
                raise
            # A write can succeed before the caller receives its response. Certify the existing bytes.
            version = s3("head-object", *location)["VersionId"]
        head = s3("head-object", *location, "--version-id", version, "--checksum-mode", "ENABLED")
        if head.get("ChecksumSHA256") != checksum or head.get("ObjectLockMode") != "COMPLIANCE":
            raise ValueError("Object checksum or retention mode mismatch")
        retained = datetime.fromisoformat(head["ObjectLockRetainUntilDate"].replace("Z", "+00:00"))
        if retained <= datetime.now(timezone.utc):
            raise ValueError("Object retention already expired")
    return {"key": key, "version": version, "after": end, "endHash": digest, "retainedUntil": retained.isoformat()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ["api", "tenant", "bucket", "bucket-owner"]:
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--workspace", default="default")
    parser.add_argument("--after", type=int, default=0)
    parser.add_argument("--anchor", default=GENESIS)
    parser.add_argument("--days", type=int, required=True)
    parser.add_argument("--pages", type=int, default=10)
    args = parser.parse_args()
    if not 1 <= args.days <= 36500 or not 1 <= args.pages <= 1000 or args.after < 0:
        parser.error("Invalid retention period or cursor")
    if not re.fullmatch("[a-f0-9]{64}", args.anchor) or (args.after == 0) != (args.anchor == GENESIS):
        parser.error("Specify the end hash from the preceding independent archive receipt")
    if not all(re.fullmatch("[A-Za-z0-9_-]{1,80}", n) for n in (args.tenant, args.workspace)):
        parser.error("Invalid partition")
    parsed = urlsplit(args.api)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.query or parsed.fragment:
        parser.error("API must use HTTPS")
    scope = args.tenant if args.workspace == "default" else f"{args.tenant}~{args.workspace}"
    api = build_opener(NoRedirect())
    cursor, anchor = args.after, args.anchor
    for _ in range(args.pages):
        params = urlencode({"after": cursor, "limit": 500})
        req = Request(f"{args.api.rstrip('/')}/api/v1/audit-integrity?{params}",
                      headers={"Authorization": "Bearer " + os.environ["AUDIT_BEARER_TOKEN"],
                               "X-Platform-Workspace": args.workspace})
        with api.open(req, timeout=30) as response:
            page = json.load(response)
        records, end, digest = verify(page, scope, cursor, anchor)
        if not records:
            break
        receipt = upload(records, scope, cursor, end, digest, args)
        print(json.dumps(receipt), flush=True)
        cursor, anchor = end, digest
        if cursor == page["head"]:
            break


if __name__ == "__main__":
    main()
