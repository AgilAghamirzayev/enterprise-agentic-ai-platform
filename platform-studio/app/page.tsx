"use client";
import { FormEvent, useEffect, useState } from "react";
import { Account, Api, apiFor } from "./client";
import { Workspace } from "./workspace";
import { LoginConfig, manager } from "./sso";

async function authenticate(header: string, workspace?: string) {
  const api = apiFor(header, workspace);
  return { api, header, who: await api<Account>("me") };
}

export default function Page() {
  const [session, setSession] = useState<Awaited<
    ReturnType<typeof authenticate>
  > | null>(null);
  const [config, setConfig] = useState<LoginConfig | null>(null);
  const [mode, setMode] = useState("token");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    let active = true;
    async function init() {
      try {
        const response = await fetch("/api/login-config", {
          cache: "no-store",
        });
        if (!response.ok)
          throw new Error("Unable to load sign-in configuration");
        const cfg: LoginConfig = await response.json();
        if (!active) return;
        setConfig(cfg);
        setMode(cfg.local ? "local" : "token");
        if (cfg.oidc && new URL(location.href).searchParams.has("state")) {
          setBusy(true);
          const client = await manager(cfg.oidc);
          try {
            const user = await client.signinRedirectCallback();
            const account = await authenticate("Bearer " + user.access_token);
            if (active) setSession(account);
          } finally {
            history.replaceState({}, "", location.pathname);
            if (active) setBusy(false);
          }
        }
      } catch (e) {
        if (active) setError((e as Error).message);
      }
    }
    void init();
    return () => {
      active = false;
    };
  }, []);
  async function connect(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setError("");
    const data = new FormData(event.currentTarget);
    try {
      const header =
        mode === "local"
          ? "Basic " + btoa(data.get("user") + ":" + data.get("secret"))
          : "Bearer " + data.get("secret");
      setSession(await authenticate(header));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  if (session)
    return (
      <Workspace
        key={session.who.tenant + "/" + session.who.workspace}
        switchWorkspace={async (name) =>
          setSession(await authenticate(session.header, name))
        }
        langfuseProjectUrl={config?.langfuseProjectUrl}
        who={session.who}
        api={session.api}
        signOut={() => setSession(null)}
      />
    );
  return (
    <main className="signin">
      <div className="login">
        <div className="brand">
          <span className="mark">A</span> Agent Studio
        </div>
        <h1>Connect to your workspace</h1>
        {config?.oidc && (
          <button
            className="primary"
            disabled={busy}
            onClick={async () => {
              try {
                await (await manager(config.oidc!)).signinRedirect();
              } catch (e) {
                setError((e as Error).message);
              }
            }}
          >
            Sign in with SSO
          </button>
        )}
        {config && (
          <form onSubmit={connect} key={mode}>
            <label>
              Authentication
              <select value={mode} onChange={(e) => setMode(e.target.value)}>
                {config.local && <option value="local">Local account</option>}
                <option value="token">OIDC access token</option>
              </select>
            </label>
            {mode === "local" && (
              <label>
                Username
                <input
                  name="user"
                  defaultValue="admin"
                  autoComplete="username"
                  required
                />
              </label>
            )}
            <label>
              {mode === "local" ? "Password" : "Access token"}
              <input
                name="secret"
                type="password"
                autoComplete={mode === "local" ? "current-password" : "off"}
                required
              />
            </label>
            <button className="primary" disabled={busy}>
              {busy ? "Connecting…" : "Connect"}
            </button>
          </form>
        )}
        {error && (
          <p className="error" role="alert">
            {error}
          </p>
        )}
      </div>
    </main>
  );
}
