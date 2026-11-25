import { test, expect } from "@playwright/test";

test("publish a YAML agent, run it in Playground, and inspect the result", async ({
  page,
}) => {
  const password = process.env.PLATFORM_LOCAL_PASSWORD;
  if (!password)
    throw new Error(
      "Supply the local test account via the Compose browser-test service",
    );
  const name = "ui-greeting";
  const version = "1.0." + Date.now();
  await page.goto("/");
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByRole("button", { name: "Connect", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Overview" })).toBeVisible();
  await expect(
    page.getByRole("img", { name: /^Executions by hour/ }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Agents", exact: true }).click();
  await page.getByLabel("Name", { exact: true }).fill(name);
  await page.getByLabel("Version", { exact: true }).fill(version);
  await page.getByRole("button", { name: "Save draft" }).click();
  await page
    .getByRole("button", { name: "Validate and publish " + name })
    .click();
  await expect(page.getByText("Version published.")).toBeVisible();
  await page.getByRole("button", { name: "Playground", exact: true }).click();
  await page.getByLabel("Published version").selectOption(name + ":" + version);
  await page.getByLabel("Input (JSON)").fill('"Studio end-to-end"');
  await page.getByRole("button", { name: "Start execution" }).click();
  await expect(
    page.getByRole("heading", { name: "Execution detail" }),
  ).toBeVisible();
  await expect(page.getByText(/· COMPLETED/)).toBeVisible({ timeout: 30000 });
  await expect(page.locator("pre:visible").first()).toHaveText(
    '"Studio end-to-end"',
  );
  await expect(
    page.getByRole("cell", { name: "run.completed", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(
    page.getByRole("heading", { name: "Connect to your workspace" }),
  ).toBeVisible();
});

test("reviewer sees the permitted workspace controls on mobile", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");
  await page.getByLabel("Username").fill("reviewer");
  await page
    .getByLabel("Password", { exact: true })
    .fill(process.env.PLATFORM_REVIEWER_PASSWORD!);
  await page.getByRole("button", { name: "Connect", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Approvals", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("navigation").getByRole("button", { name: "Agents" }),
  ).toHaveCount(0);
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
});

test("administrator creates a workspace, grants a bounded role and finds the audit event", async ({
  page,
}) => {
  const name = "studio-" + Date.now();
  await page.goto("/");
  await page
    .getByLabel("Password", { exact: true })
    .fill(process.env.PLATFORM_LOCAL_PASSWORD!);
  await page.getByRole("button", { name: "Connect", exact: true }).click();
  await page
    .getByRole("navigation")
    .getByRole("button", { name: "Settings" })
    .click();
  await page.getByLabel("Workspace ID", { exact: true }).fill(name);
  await page.getByLabel("Workspace name", { exact: true }).fill(name);
  await page
    .getByRole("button", { name: "Create workspace", exact: true })
    .click();
  await expect(page.getByText("Workspace created.")).toBeVisible();
  await page
    .getByRole("combobox", { name: "Membership workspace" })
    .selectOption(name);
  await page.getByLabel("Identity provider subject").fill("reviewer");
  await page.getByLabel("Permitted roles, comma separated").fill("approver");
  await page.getByRole("button", { name: "Save membership" }).click();
  await expect(page.getByText("Membership saved.")).toBeVisible();
  await expect(page.getByRole("cell", { name: "reviewer" })).toBeVisible();
  await page
    .getByRole("combobox", { name: "Workspace to open", exact: true })
    .selectOption(name);
  await page.getByRole("button", { name: "Open workspace" }).click();
  await expect(
    page.getByText("Workspace / local / " + name, { exact: true }),
  ).toBeVisible();
  await page
    .getByRole("navigation")
    .getByRole("button", { name: "Settings" })
    .click();
  await page
    .getByRole("combobox", { name: "Workspace to open", exact: true })
    .selectOption("default");
  await page.getByRole("button", { name: "Open workspace" }).click();
  await expect(
    page.getByText("Workspace / local / default", { exact: true }),
  ).toBeVisible();
  await page
    .getByRole("navigation")
    .getByRole("button", { name: "Audit" })
    .click();
  await page.getByLabel("Search audit history").fill(name);
  await page.getByRole("button", { name: "Search", exact: true }).click();
  await expect(
    page.getByRole("cell", { name: "workspace.membership.assigned" }).first(),
  ).toBeVisible();
});
