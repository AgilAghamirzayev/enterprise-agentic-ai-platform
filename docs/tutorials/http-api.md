# Publish and call an agent over HTTP

Use this path when a backend service, script or job needs to run a versioned agent. The API accepts the run immediately. A worker executes it asynchronously. Keep the run ID to retrieve the result or follow progress after reconnecting.

## Try the local client

Start the [local platform](../../README.md#run-studio-and-the-worker). From the repository root, run the following command with Python 3.8 or later. The client uses the standard library.

```sh
python3 examples/http-client/run_agent.py
```

At the prompt, enter the local admin password from the project’s generated `.env` file. The client publishes a new version of the [hello-model definition](../../examples/hello-model/definition.json) on each invocation. It submits the same request twice with one idempotency key and verifies that both receipts identify the same execution. With the default echo provider, the client prints the following result.

```text
Result: "Hello from the HTTP API"
```

Use the printed run ID in Studio under **Executions**. If you changed the local API port, set `PLATFORM_API_PORT` for the client as well. [Runnable client source](../../examples/http-client/run_agent.py)

## Requests made by the client

All paths below start at `/api/v1`. Local clients use HTTP Basic authentication. Include `Content-Type: application/json` for JSON and `X-Platform-Client: studio` on local writes. In an OIDC deployment, send an access token for the configured audience in `Authorization: Bearer <token>`. For a named workspace, follow the [workspace selection rules](../operator-guide/workspaces-and-tools.md).

| Request | What to send or retain |
| --- | --- |
| `POST /agents` | Send `name`, a three-part numeric `version` and `spec`. The response is HTTP 201. Keep its `revision`. |
| `POST /agents/{name}/versions/{version}/validate` | Send `{}` and `If-Match: "<revision>"`, including the double quotes. This validates the stored draft. |
| `POST /agents/{name}/versions/{version}/publish` | Send the same reviewed revision. A concurrent change to the draft returns HTTP 412. Read and review the draft again. |
| `POST /runs` | Send an agent reference and JSON input with an `Idempotency-Key`. HTTP 202 returns the `runId` and links for status, events and result. |
| `GET /runs/{id}` | Check `status`. Continue waiting while it is `QUEUED`, `RUNNING` or `WAITING_APPROVAL`. Apply a client-side deadline. |
| `GET /runs/{id}/result` | Read the JSON result after `COMPLETED` or `SUCCEEDED`. HTTP 409 indicates the run has no successful result yet or ended unsuccessfully. |

A service invoking an already published version only needs the last three operations. A typical submission body looks like this. Substitute the version you published.

```json
{
  "agent": {"name": "http-quickstart", "version": "1.0.0"},
  "input": "Hello from the HTTP API"
}
```

For a workflow, send a `workflow` reference instead of `agent`. Generate one idempotency key per intended execution and retain it with the body before sending. If the response is lost, reuse that key and body under the same identity and workspace. Changing the input while reusing the key returns HTTP 409.

## Inspect an execution

In the local environment, these commands prompt for the admin password. Enter the run ID printed by the client.

```sh
read -r RUN_ID
API="http://localhost:${PLATFORM_API_PORT:-18080}/api/v1"
curl --fail --user admin "$API/runs/$RUN_ID/steps"
curl --no-buffer --fail --user admin -H 'Accept: text/event-stream' "$API/runs/$RUN_ID/events"
```

The event stream has a bounded lifetime. Save the last delivered event ID and send it in `Last-Event-ID` when reconnecting. Use `GET /runs/{id}` for the current state. For cancellation, submit `POST /runs/{id}/cancel` and check the resulting state. A previously completed external action may already have taken effect.

Fetch the exact contract served by your installation with `GET /api/v1/openapi`. [Execution lifecycle and troubleshooting](../architecture/execution.md) · [Documentation home](../README.md)
