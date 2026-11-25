# Run an agent and approve a tool call in Studio

You will publish two definitions. The first returns a greeting through the demo model. The second pauses until a reviewer approves creating an internal ticket. Both use the local installation and synthetic input.

## Start and sign in

Follow [Run Studio and the worker](../../README.md#run-studio-and-the-worker) from the repository root. Check that the API is ready.

```sh
curl --fail http://localhost:18080/actuator/health/readiness
```

Look for `"status":"UP"`. Open [Agent Studio](http://localhost:13000), choose **Local account**, enter `admin` and select **Connect**. The password is the value after `PLATFORM_LOCAL_PASSWORD=` in the generated project-root `.env` file. The reviewer account has its own password under `PLATFORM_REVIEWER_PASSWORD`.

## Publish and run a greeting

1. Open **Agents**. Enter `studio-greeting` as **Name** and `1.0.0` as **Version**. Paste the contents of the [hello-model definition](../../examples/hello-model/definition.json) into **Workflow definition (YAML or JSON)**.
2. Select **Save draft**, then **Validate and publish studio-greeting**. Wait for **Version published.** A published version is immutable. If you already published this example, reuse it or publish changes under a new version.
3. Open **Playground**. Choose **Agent** under **Resource** and select `studio-greeting:1.0.0` under **Published version**. Enter `"Hello from Studio"` in **Input (JSON)**, including the quotation marks, and select **Start execution**.
4. Under **Execution detail**, copy the run ID. With the default echo provider the status becomes `COMPLETED` and the result is `"Hello from Studio"`. The timeline includes `run.completed`. To find the run again, open **Executions** and select it.

## Review a proposed action

1. Open **Workflows** as `admin`. Save and publish the [approval workflow definition](../../examples/approval-workflow/definition.json) as `ticket-review:1.0.0`.
2. In **Playground**, change **Resource** to **Workflow** and select that version. Start it with `{"subject":"Review the sample invoice"}`. Copy the run ID and look for `approval.requested` in the timeline. The run can continue to show `RUNNING` while the request is pending. This definition grants 180 seconds for the decision.
3. Open Studio in a second browser tab and sign in as `reviewer`. In **Approvals**, choose the pending request with the same run ID. Check its tool name (`create-ticket`) and arguments. Select **Approve** under **Decision**, then **Submit decision**.
4. Return to the administrator tab. The run completes and its result contains a ticket with `"status":"OPEN"`. To try rejection, start a fresh run and select **Reject**. That execution ends with `DENIED`.

The approval covers the particular tool and arguments shown to the reviewer. This example creates a platform ticket using the bundled tool. To connect your own service, follow the [connector guide](../operator-guide/workspaces-and-tools.md).

[Documentation home](../README.md) · [Call the same API from code](http-api.md) · [Troubleshoot a run](../architecture/execution.md)
