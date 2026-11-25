import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";

const port = process.env.LANGFUSE_PORT || "13001";
const origin = "http://localhost:" + port;
const project = origin + "/project/agent-platform";

// Resolve the same public link a host browser opens from inside the test container.
test.use({
  actionTimeout: 15000,
  launchOptions: { args: ["--host-resolver-rules=MAP localhost:" + port + " lf-web:3000"] },
});

type Observation = {
  id: string;
  traceId: string;
  projectId: string;
  parentObservationId: string | null;
  name: string;
  type: string;
  model?: string;
  usageDetails?: { input: number; output: number };
  traceName?: string;
  input?: string | null;
  output?: string | null;
};

test("Studio agent execution appears in Langfuse with a correlated generation", async ({ page, context, request }) => {
  test.setTimeout(180_000);
  const name = "langfuse-check";
  const version = "1.0." + Date.now();
  const privateInput = "private-input-" + randomUUID();

  await page.goto("/");
  await page.getByLabel("Password", { exact: true }).fill(process.env.PLATFORM_LOCAL_PASSWORD!);
  await page.getByRole("button", { name: "Connect", exact: true }).click();
  await page.getByRole("button", { name: "Agents", exact: true }).click();
  await page.getByLabel("Name", { exact: true }).fill(name);
  await page.getByLabel("Version", { exact: true }).fill(version);
  await page.getByRole("button", { name: "Save draft" }).click();
  await page.getByRole("button", { name: "Validate and publish " + name }).click();
  await expect(page.getByText("Version published.")).toBeVisible();
  await page.getByRole("button", { name: "Playground", exact: true }).click();
  await page.getByLabel("Published version").selectOption(name + ":" + version);
  await page.getByLabel("Input (JSON)").fill(JSON.stringify(privateInput));
  const accepted = page.waitForResponse(r => r.url().endsWith("/api/v1/runs") && r.request().method() === "POST", { timeout: 15000 });
  await page.getByRole("button", { name: "Start execution" }).click();
  const receipt = await accepted;
  expect(receipt.status()).toBe(202);
  const { runId } = await receipt.json();
  await expect(page.getByText(runId + " · COMPLETED", { exact: true })).toBeVisible({ timeout: 40000 });

  const link = page.getByRole("link", { name: "View trace in Langfuse" });
  await expect(link).toBeVisible();
  const href = (await link.getAttribute("href"))!;
  const traceId = href.split("/").pop()!;
  expect(traceId).toMatch(/^[a-f0-9]{32}$/);
  expect(href).toBe(project + "/traces/" + traceId);

  const auth = Buffer.from(process.env.LF_PUBLIC_KEY + ":" + process.env.LF_SECRET_KEY).toString("base64");
  await expect(async () => {
    const response = await request.get("http://lf-web:3000/api/public/v2/observations", {
      params: { traceId, fields: "core,basic,model,usage,io,metadata,trace_context" },
      headers: { Authorization: "Basic " + auth },
    });
    expect(response.status()).toBe(200);
    const { data }: { data: Observation[] } = await response.json();
    const submit = data.find(span => span.name === "agent.run.submit");
    const step = data.find(span => span.type === "AGENT");
    const inference = data.find(span => span.name === "agent.model.inference");
    const generation = data.find(span => span.type === "GENERATION");
    expect(submit).toBeDefined();
    expect(step).toBeDefined();
    expect(inference).toBeDefined();
    expect(generation).toBeDefined();
    expect(submit!.parentObservationId).toBeNull();
    expect(step!.parentObservationId).toBe(submit!.id);
    expect(inference!.parentObservationId).toBe(step!.id);
    expect(generation!.parentObservationId).toBe(inference!.id);
    expect(data.every(span => span.traceName === "agent/" + name)).toBe(true);
    expect(generation!.model).toBeTruthy();
    expect(generation!.usageDetails!.input).toBeGreaterThan(0);
    expect(generation!.usageDetails!.output).toBeGreaterThan(0);
    expect(data.every(span => span.traceId === traceId && span.projectId === "agent-platform")).toBe(true);
    expect(data.every(span => span.input == null && span.output == null)).toBe(true);
    expect(JSON.stringify(data)).toContain(runId);
    expect(JSON.stringify(data)).not.toContain(privateInput);
  }).toPass({ timeout: 90000, intervals: [1000, 2000, 4000] });

  const login = await context.newPage();
  await login.goto(origin + "/auth/sign-in");
  await login.locator('input[name="email"]').fill("admin@platform.local");
  await login.locator('input[type="password"]').fill(process.env.LF_ADMIN_PASSWORD!);
  await login.locator('button[type="submit"]').click();
  await expect(login).not.toHaveURL(/\/auth\/sign-in/, { timeout: 30000 });
  const opened = context.waitForEvent("page");
  await link.click();
  const tracePage = await opened;
  await expect(tracePage).toHaveURL(href);
  await expect(tracePage.getByText("model.request.duration", { exact: true }).first()).toBeVisible({ timeout: 30000 });
  console.log("Verified Studio run " + runId + " in Langfuse: " + href);
});
