"""Submit one scheduled occurrence using a short-lived OIDC access token."""

import json
import os
from urllib.parse import urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


class RejectRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


def endpoint(name):
    value = os.environ[name]
    url = urlsplit(value)
    if url.scheme != "https" or not url.hostname or url.username or url.password:
        raise ValueError(f"{name} must be a fixed HTTPS endpoint")
    return value


def submit(opener):
    form = urlencode({
        "grant_type": "client_credentials",
        "client_id": os.environ["OIDC_CLIENT_ID"],
        "client_secret": os.environ["OIDC_CLIENT_SECRET"],
    }).encode()
    with opener.open(Request(endpoint("OIDC_TOKEN_URL"), data=form), timeout=15) as response:
        token = json.loads(response.read(32768))["access_token"]

    event = {
        "specversion": "1.0",
        "source": os.environ["EVENT_SOURCE"],
        "type": os.environ["EVENT_TYPE"],
        "id": os.environ["JOB_NAME"],
        "data": json.loads(os.environ["EVENT_DATA"]),
    }
    request = Request(endpoint("PLATFORM_TRIGGER_URL"), data=json.dumps(event).encode(), headers={
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/cloudevents+json",
    })
    with opener.open(request, timeout=20) as response:
        if response.status != 202:
            raise RuntimeError("Trigger did not accept the occurrence")
        return json.loads(response.read(8192))["runId"]


if __name__ == "__main__":
    print("Accepted run", submit(build_opener(RejectRedirect())))
