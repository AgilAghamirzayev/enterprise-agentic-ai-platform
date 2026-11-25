import { test, expect } from "@playwright/test";

test("SSO exchanges a PKCE code and runs a published agent under a signed OIDC identity", async ({
  page,
}) => {
  await page.goto("/");
  await page.getByRole("button", { name: "Sign in with SSO" }).click();
  await expect(page.getByRole("heading", { name: "Overview" })).toBeVisible({
    timeout: 30000,
  });
  await page
    .getByRole("navigation")
    .getByRole("button", { name: "Settings" })
    .click();
  await expect(
    page.getByText("oidc-verification", { exact: true }).last(),
  ).toBeVisible();
  await expect(
    page.getByText("browser-operator", { exact: true }).last(),
  ).toBeVisible();
  await page
    .getByRole("navigation")
    .getByRole("button", { name: "Agents" })
    .click();
  const version = "1.0." + Date.now(),
    name = "sso-example";
  await page.getByLabel("Name", { exact: true }).fill(name);
  await page.getByLabel("Version", { exact: true }).fill(version);
  await page.getByRole("button", { name: "Save draft" }).click();
  await page
    .getByRole("button", { name: "Validate and publish " + name })
    .click();
  await expect(page.getByText("Version published.")).toBeVisible();
  await page
    .getByRole("navigation")
    .getByRole("button", { name: "Playground" })
    .click();
  await page.getByLabel("Published version").selectOption(name + ":" + version);
  await page.getByLabel("Input (JSON)").fill('"Verified through OIDC"');
  await page.getByRole("button", { name: "Start execution" }).click();
  await expect(page.getByText(/· COMPLETED/)).toBeVisible({ timeout: 30000 });
  await expect(page.locator("pre:visible").first()).toHaveText(
    '"Verified through OIDC"',
  );
});
