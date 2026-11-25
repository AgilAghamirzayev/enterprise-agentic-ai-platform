"""Exercise the local deployment through the same HTTP interface as Studio."""
import base64
import json
import os
import time
import uuid
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

ORIGIN = os.getenv("PLATFORM_TEST_URL", "http://localhost:18080")
EXAMPLES = Path(__file__).resolve().parent.parent / "examples"
VERSION = "1.0." + str(time.time_ns())
ACTIVE = {"QUEUED", "RUNNING", "WAITING_APPROVAL"}


def call(path, body=None, *, reviewer=False, key=None, expected=None, workspace=None):
    user, password = ("reviewer", os.environ["PLATFORM_REVIEWER_PASSWORD"]) if reviewer else ("admin", os.environ["PLATFORM_LOCAL_PASSWORD"])
    auth = base64.b64encode(f"{user}:{password}".encode()).decode()
    headers = {"Authorization": "Basic " + auth, "Content-Type": "application/json", "X-Platform-Client": "studio"}
    if workspace:
        headers["X-Platform-Workspace"] = workspace
    if key:
        headers["Idempotency-Key"] = key
    request = Request(ORIGIN + "/api/v1/" + path, None if body is None else json.dumps(body).encode(), headers)
    try:
        with urlopen(request, timeout=15) as response:
            if expected and expected != response.status:
                raise AssertionError(f"{path} returned {response.status}, expected {expected}")
            return json.load(response)
    except HTTPError as error:
        if error.code == expected:
            return None
        raise RuntimeError(f"{path} returned {error.code}: {error.read(800).decode()}") from error


def publish(name, kind="agent", spec=None):
    if spec is None:
        spec = json.loads((EXAMPLES / name / "definition.json").read_text())
    draft = {"name": name, "version": VERSION, "spec": spec}
    call(kind + "s", draft)
    path = f"{kind}s/{name}/versions/{VERSION}/"
    call(path + "validate", {})
    call(path + "publish", {})
    return {"name": name, "version": VERSION}


def start(ref, value, kind="agent", session=None):
    body = {kind: ref, "input": value, "sessionId": session}
    key = str(uuid.uuid4())
    first = call("runs", body, key=key, expected=202)["runId"]
    assert call("runs", body, key=key, expected=202)["runId"] == first
    call("runs", {**body, "input": {"changed": True}}, key=key, expected=409)
    return first


def until(read, accepted, timeout=90):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = read()
        if accepted(value):
            return value
        time.sleep(0.45)
    raise TimeoutError("Execution did not reach its expected state")


def finish(run, status="COMPLETED"):
    result = until(lambda: call("runs/" + run), lambda row: row["status"] not in ACTIVE)
    assert result["status"] == status, f"{run}: {result['status']} (expected {status})"
    return result["result"]


def main():
    assert call("me")["tenant"] == "local"
    call("audit-events", reviewer=True, expected=403)
    hello = publish("hello-model")
    run = start(hello, "Hello from the platform")
    answer = finish(run)
    assert answer == "Hello from the platform"
    assert call("runs/" + run + "/steps")
    assert call("evaluations", {"runId": run, "expected": answer})["passed"]
    assert not call("evaluations", {"runId": run, "expected": "different"})["passed"]
    print("PASS immutable publication, idempotency, model execution, steps and evaluations", flush=True)

    words = publish("custom-tool-agent")
    assert finish(start(words, {"tool": "uppercase", "arguments": {"text": "verified"}})) == "VERIFIED"
    finish(start(words, {"tool": "create-ticket", "arguments": {"text": "ungranted"}}), "DENIED")
    print("PASS model-selected tool and denied unbound action", flush=True)

    base = "handbook-" + VERSION.rsplit(".", 1)[1]
    docs = call("knowledge-bases/documents", {"base":base,"title":"Support policy","content":"Cobalt requests require supervisor review.","roles":["admin"]})
    rag_plan = json.loads((EXAMPLES / "rag-agent/definition.json").read_text())
    rag_plan["nodes"][0]["knowledgeBase"] = base
    rag = publish("rag-agent", spec=rag_plan)
    retrieved = finish(start(rag, "Cobalt requests"))
    assert retrieved["citations"] and any(item["documentId"] == docs["id"] for item in retrieved["citations"])
    print("PASS authorized retrieval and citation metadata", flush=True)

    multi = publish("multi-agent-supervisor", "workflow")
    branches = finish(start(multi, {"route":"specialists","text":"two words"}, "workflow"))
    assert branches == {"words": 2, "caps":"TWO WORDS"}, branches
    print("PASS supervisor routing, parallel workers and join", flush=True)

    approval = publish("approval-workflow", "workflow")
    ticket_run = start(approval, {"subject":"Smoke test"}, "workflow")
    def pending():
        return next((a for a in call("approvals", reviewer=True) if a["runId"] == ticket_run), None)
    request = until(pending, bool)
    call("approvals/" + request["id"] + "/decision", {"approved":True,"actionHash":"incorrect"}, reviewer=True, expected=409)
    call("approvals/" + request["id"] + "/decision", {"approved":True,"actionHash":request["actionHash"]}, reviewer=True)
    assert finish(ticket_run)["status"] == "OPEN"
    print("PASS human approval bound to action and idempotent ticket creation", flush=True)

    slow = publish("cancellation-example", "workflow", {"start":"wait","nodes":[{"id":"wait","type":"WAIT","seconds":30,"next":"done"},{"id":"done","type":"END"}]})
    cancelled = start(slow, {"test":"cancel"}, "workflow")
    call("runs/" + cancelled + "/cancel", {})
    finish(cancelled, "CANCELLED")
    usage = call("usage")
    assert usage["runs"] >= 6 and "estimatedCostUsd" in usage
    hourly = usage["hourly"]
    assert len(hourly) == 24 and len({bucket["startedAt"] for bucket in hourly}) == 24
    assert sum(bucket["total"] for bucket in hourly) >= 6
    assert all(0 <= bucket["failed"] <= bucket["total"] for bucket in hourly)
    print("PASS cancellation, tenant usage, and reviewer permissions", flush=True)
    regression(hello, run, answer)
    versioned_ingestion(base)
    trigger_delivery(multi)
    pinned_model()
    stream_cursor(run)
    pinned_during_run()
    pinned_policy()
    accounting_example()
    workspace_boundaries(run)
    artifact_roundtrip(run)
    assert until(lambda: call("status"), lambda s:s["worker"]=="POLLING")["temporal"]=="UP"
    print("PASS live API, Temporal and worker health",flush=True)
    contract=call("openapi")
    paths=contract["paths"]
    for collection in ["agents","workflows","evaluation-suites","evaluation-datasets"]:
        assert {"get","post"} <= paths["/api/v1/"+collection].keys()
    for collection in ("agents","workflows"):
        for action in ("validate","publish"):
            assert "post" in paths["/api/v1/"+collection+"/{name}/versions/{version}/"+action]
    assert not any("{collection}" in path for path in paths)
    assert contract.get("security") and contract["components"]["securitySchemes"]
    print("PASS concrete OpenAPI operations and deployment authentication scheme",flush=True)


def regression(target, run, answer):
    call("evaluation-datasets", {"name":"smoke-cases","version":VERSION,"cases":[
        {"id":"greeting","input":"Hello from the platform","expected":answer,"schema":{"type":"string"}}]})
    call("evaluation-suites", {"name":"smoke-regression","version":VERSION,"dataset":"smoke-cases:"+VERSION,
          "target":{"kind":"agent",**target},"minPassRate":1})
    report = call("evaluation-reports", {"suite":"smoke-regression:"+VERSION,"runs":{"greeting":run}})
    assert report["report"]["passed"]
    print("PASS version-pinned dataset, execution evidence and regression report", flush=True)


def versioned_ingestion(base):
    route = f"knowledge-bases/{base}/ingestions"
    for index, text in enumerate(["Quartz orders require review.", "Quartz orders require double review."], 1):
        item = {"documentKey":"quartz","title":"Quartz policy","content":text,"roles":["admin"]}
        key = str(uuid.uuid4())
        job = call(route, item, key=key, expected=202)
        assert call(route, item, key=key, expected=202)["id"] == job["id"]
        result = until(lambda: call(route+"/"+job["id"]), lambda r:r["status"] in {"SUCCEEDED","FAILED"})
        assert result["status"] == "SUCCEEDED" and result["revision"] == index, result
    matches = call(f"knowledge-bases/{base}/search?q=Quartz")
    assert any(row["text"] == "Quartz orders require double review." for row in matches)
    assert all(row["text"] != "Quartz orders require review." for row in matches)
    print("PASS durable document revisions and active-version retrieval", flush=True)


def trigger_delivery(workflow):
    call("triggers", {"name":"smoke-event","version":VERSION,"source":"urn:smoke","subject":"admin","type":"test.run","workflow":workflow})
    route = f"triggers/smoke-event/versions/{VERSION}/events"
    event = {"specversion":"1.0","id":str(uuid.uuid4()),"source":"urn:smoke","type":"test.run","data":{"route":"direct"}}
    run = call(route,event,expected=202)["runId"]
    assert call(route,event,expected=202)["runId"] == run
    finish(run)
    print("PASS authenticated CloudEvent delivery and duplicate suppression", flush=True)


def pinned_model():
    primary = next(row["model"] for row in call("model-profiles") if row["name"] == "deployment-default")
    call("model-profiles", {"name":"smoke-model","version":VERSION,"model":primary})
    call("prompts", {"name":"smoke-prompt","version":VERSION,"text":"Echo the supplied input."})
    spec = {"start":"respond","nodes":[{"id":"respond","type":"AGENT","promptRef":"smoke-prompt:"+VERSION,
             "modelProfileRef":"smoke-model:"+VERSION,"next":"end"},{"id":"end","type":"END"}]}
    ref = publish("smoke-profiled",spec=spec)
    assert finish(start(ref,"profile bound")) == "profile bound"
    print("PASS pinned prompt and model profile", flush=True)


def stream_cursor(run):
    auth = base64.b64encode(("admin:"+os.environ["PLATFORM_LOCAL_PASSWORD"]).encode()).decode()
    url = ORIGIN+"/api/v1/runs/"+run+"/events"
    def first_id(after):
        headers = {"Authorization":"Basic "+auth, "Accept":"text/event-stream", "Last-Event-ID":str(after)}
        with urlopen(Request(url,headers=headers),timeout=10) as response:
            for line in response:
                if line.startswith(b"id:"):
                    return int(line[3:].strip())
        raise AssertionError("No retained event after requested cursor")
    first = first_id(0)
    assert first_id(first) > first
    print("PASS authenticated SSE replay with Last-Event-ID", flush=True)


def pinned_during_run():
    def child(tool):
        return {"start":"convert","allowedTools":[tool],"nodes":[{"id":"convert","type":"TOOL","tool":tool,"next":"done"},{"id":"done","type":"END"}]}
    name="pinned-child"
    pinned=publish(name,spec=child("uppercase"))
    graph={"apiVersion":"platform.ai/v1alpha1","kind":"Workflow","metadata":{"name":"release-in-progress","version":VERSION},"spec":{
        "start":"pause","allowedTools":["uppercase"],"nodes":[{"id":"pause","type":"WAIT","seconds":4,"next":"delegate"},
        {"id":"delegate","type":"AGENT","agentRef":name+":"+pinned["version"],"next":"done"},{"id":"done","type":"END"}]}}
    parent=publish("release-in-progress","workflow",graph)
    run=start(parent,{"text":"invoice pending"},"workflow")
    newer=VERSION+"1"
    call("agents",{"name":name,"version":newer,"spec":child("word-count")})
    call(f"agents/{name}/versions/{newer}/publish",{})
    assert finish(run)=="INVOICE PENDING"
    assert finish(start({"name":name,"version":newer},{"text":"invoice pending"}))==2
    print("PASS declarative workflow envelope and published child version pinned while parent runs",flush=True)


def pinned_policy():
    policy="bounded-word-count"
    spec={"defaultEffect":"DENY","rules":[{"effect":"ALLOW","resource":"word-count","when":{
        "pointer":"/text","operator":"eq","value":"permitted words"}}]}
    call("policy-versions",{"name":policy,"version":VERSION,"spec":spec})
    graph={"start":"count","allowedTools":["word-count"],"policyRefs":[policy+":"+VERSION],"nodes":[
        {"id":"count","type":"TOOL","tool":"word-count","next":"done"},{"id":"done","type":"END"}]}
    target=publish("policy-word-counter",spec=graph)
    assert finish(start(target,{"text":"permitted words"}))==2
    denied=start(target,{"text":"other words"})
    finish(denied,"DENIED")
    assert not any("count"==step.get("stepKey") for step in call("runs/"+denied+"/steps"))
    print("PASS pinned argument policy allows matching input and denies unmatched tool execution",flush=True)


def accounting_example():
    ref=publish("enterprise-accounting","workflow")
    invoice={"invoiceId":"sample-42","subject":"Review variance","variancePercent":5}
    assert finish(start(ref,invoice,"workflow"))==invoice
    review=start(ref,{**invoice,"variancePercent":7},"workflow")
    pending=until(lambda:next((a for a in call("approvals",reviewer=True) if a["runId"]==review),None),bool)
    call("approvals/"+pending["id"]+"/decision",{"actionHash":pending["actionHash"],"approved":False},reviewer=True)
    finish(review,"DENIED")
    print("PASS accounting threshold and rejected write",flush=True)


def workspace_boundaries(default_run):
    name = "smoke-" + str(time.time_ns())
    call("workspaces", {"name": name, "displayName": "Smoke directory"}, expected=201)
    call("me", reviewer=True, workspace=name, expected=403)
    call("workspaces/" + name + "/members", {"subject": "reviewer", "roles": ["approver", "developer"]})
    scoped = call("me", reviewer=True, workspace=name)
    assert scoped["tenant"] == "local" and scoped["workspace"] == name and scoped["roles"] == ["approver"]
    assert call("runs", workspace=name) == []
    call("runs/" + default_run, workspace=name, expected=404)
    spec = {"start": "done", "nodes": [{"id":"done","type":"END"}]}
    draft = {"name":"directory-test", "version":VERSION, "spec":spec}
    call("agents", draft, workspace=name)
    call("agents/directory-test/versions/" + VERSION + "/publish", {}, workspace=name)
    ref = {"agent":{"name":"directory-test","version":VERSION},"input":"scoped"}
    run = call("runs", ref, key=str(uuid.uuid4()), workspace=name)["runId"]
    until(lambda:call("runs/"+run,workspace=name),lambda r:r["status"]=="COMPLETED")
    call("runs/"+run, expected=404)
    call("workspaces/"+name+"/members", {"subject":"reviewer","roles":[]})
    call("me",reviewer=True,workspace=name,expected=403)
    assert all(row["name"] != name for row in call("workspaces",reviewer=True))
    chain=call("audit-integrity?limit=100",workspace=name)
    assert chain["valid"] and 0<chain["next"]==chain["head"] and chain["legacyEvents"]==0
    print("PASS workspace audit chain and independent management partition",flush=True)
    print("PASS workspace membership, role intersection, revocation and end-to-end execution isolation",flush=True)


def artifact_roundtrip(run):
    data=b"synthetic attachment"
    import hashlib
    key=base64.b64encode(("admin:"+os.environ["PLATFORM_LOCAL_PASSWORD"]).encode()).decode()
    url=ORIGIN+"/api/v1/runs/"+run+"/artifacts/evidence.txt"
    headers={"Authorization":"Basic "+key,"X-Platform-Client":"studio","Content-Type":"text/plain"}
    for _ in range(2):
        with urlopen(Request(url,data,headers,method="PUT"),timeout=10) as res:
            assert json.load(res)["digest"]==hashlib.sha256(data).hexdigest()
    with urlopen(Request(url,headers=headers),timeout=10) as res:
        assert res.read()==data and "attachment" in res.headers["Content-Disposition"]
    assert len(call("runs/"+run+"/artifacts"))==1
    call("runs/"+run+"/artifacts",reviewer=True,expected=403)
    try:
        urlopen(Request(url,b"changed",headers,method="PUT"),timeout=10)
        raise AssertionError("Overwrote immutable artifact")
    except HTTPError as error:
        assert error.code==409
        problem=json.load(error)
        assert problem["code"]=="CONFLICT" and problem["traceId"]
    print("PASS binary artifact upload, exact retry, authorized download and conflict response",flush=True)


if __name__ == "__main__":
    main()
