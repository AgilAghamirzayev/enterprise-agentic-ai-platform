"""Publish a local demo agent and run it through the HTTP API (Python 3)."""

import base64
import json
import os
import sys
import time
import uuid
from getpass import getpass
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


def main():
    port = int(os.environ.get("PLATFORM_API_PORT", "18080"))
    base = f"http://127.0.0.1:{port}/api/v1"
    password = os.environ.get("PLATFORM_LOCAL_PASSWORD") or getpass("Local admin password: ")
    credentials = base64.b64encode(f"admin:{password}".encode()).decode()
    headers = {"Authorization": f"Basic {credentials}", "X-Platform-Client": "studio"}

    def call(path, body=None, extra_headers=None):
        request = Request(
            base + path,
            data=json.dumps(body).encode() if body is not None else None,
            headers={**headers, "Content-Type": "application/json", **(extra_headers or {})},
        )
        with urlopen(request, timeout=10) as response:
            return json.load(response)

    spec = Path(__file__).resolve().parents[1] / "hello-model" / "definition.json"
    ref = {"name": "http-quickstart", "version": f"1.0.{time.time_ns()}"}
    draft = call("/agents", {**ref, "spec": json.loads(spec.read_text())})
    version_path = f"/agents/{ref['name']}/versions/{ref['version']}"
    revision = {"If-Match": json.dumps(draft["revision"])}
    call(version_path + "/validate", {}, revision)
    call(version_path + "/publish", {}, revision)
    print(f"Published {ref['name']}:{ref['version']}")

    body = {"agent": ref, "input": "Hello from the HTTP API"}
    key = {"Idempotency-Key": str(uuid.uuid4())}
    receipt = call("/runs", body, key)
    run_id = receipt["runId"]
    print(f"Run {run_id}")
    replay = call("/runs", body, key)
    if replay["runId"] != run_id:
        raise RuntimeError("Retry returned a different run ID")

    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        status = call(f"/runs/{run_id}")["status"]
        if status in ("COMPLETED", "SUCCEEDED"):
            print("Result:", json.dumps(call(f"/runs/{run_id}/result")))
            return
        if status not in ("QUEUED", "RUNNING", "WAITING_APPROVAL"):
            raise RuntimeError(f"Run {run_id} ended with {status}")
        time.sleep(0.5)
    raise RuntimeError(f"Timed out waiting. Inspect run {run_id} in Studio.")


if __name__ == "__main__":
    try:
        main()
    except HTTPError as error:
        sys.exit(f"HTTP {error.code}: {error.read(1024).decode(errors='replace')}")
    except (URLError, OSError, RuntimeError, ValueError) as error:
        sys.exit(str(error))
