import type { UserManager } from "oidc-client-ts";
export interface LoginConfig {
  oidc: { issuer: string; clientId: string; scope: string } | null;
  local: boolean;
  langfuseProjectUrl?: string;
}
export async function manager(
  config: NonNullable<LoginConfig["oidc"]>,
): Promise<UserManager> {
  const { UserManager, WebStorageStateStore, InMemoryWebStorage } =
    await import("oidc-client-ts");
  return new UserManager({
    authority: config.issuer,
    client_id: config.clientId,
    scope: config.scope,
    redirect_uri: window.location.origin + "/",
    response_type: "code",
    automaticSilentRenew: false,
    stateStore: new WebStorageStateStore({ store: sessionStorage }),
    userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }),
  });
}
