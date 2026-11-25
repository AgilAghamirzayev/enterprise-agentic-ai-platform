import { useEffect, useState } from "react";
import { Account, Api, Row, label } from "./client";
import { ActionForm, Field, Grid } from "./components";

export function Directory({
  api,
  who,
  switchWorkspace,
}: {
  api: Api;
  who: Account;
  switchWorkspace: (name: string) => Promise<void>;
}) {
  const [destination, setDestination] = useState(who.workspace);
  const [spaces, setSpaces] = useState<Row[]>([]);
  const [members, setMembers] = useState<Row[]>([]);
  const [selected, select] = useState("");
  const [revision, reload] = useState(0);
  const [message, setMessage] = useState("");
  const admin = who.workspace === "default" && who.roles.includes("admin");
  useEffect(() => {
    let active = true;
    api<Row[]>("workspaces")
      .then((v) => {
        if (active) setSpaces(v);
      })
      .catch((e) => {
        if (active) setMessage(e.message);
      });
    return () => {
      active = false;
    };
  }, [api, revision]);
  useEffect(() => {
    let active = true;
    setMembers([]);
    if (admin && selected)
      api<Row[]>("workspaces/" + selected + "/members")
        .then((v) => {
          if (active) setMembers(v);
        })
        .catch((e) => {
          if (active) setMessage(e.message);
        });
    return () => {
      active = false;
    };
  }, [api, admin, selected, revision]);
  return (
    <>
      <ActionForm
        title="Switch workspace"
        button="Open workspace"
        submit={async (d) => switchWorkspace(String(d.get("space")))}
      >
        <label>
          Workspace
          <select
            aria-label="Workspace to open"
            name="space"
            value={destination}
            onChange={(e) => setDestination(e.target.value)}
          >
            <option value="default">Default</option>
            {spaces.map((w) => (
              <option key={label(w.name)} value={label(w.name)}>
                {label(w.displayName)}
              </option>
            ))}
          </select>
        </label>
      </ActionForm>
      {admin && (
        <>
          <ActionForm
            title="Create workspace"
            button="Create workspace"
            submit={async (d) => {
              const name = String(d.get("name"));
              const created = await api<Row>("workspaces", {
                name,
                displayName: String(d.get("displayName")),
              });
              setSpaces((current) => [
                ...current.filter((row) => row.name !== created.name),
                created,
              ]);
              select(name);
              setMessage("Workspace created.");
              reload((x) => x + 1);
            }}
          >
            <Field name="name" title="Workspace ID" />
            <Field name="displayName" title="Workspace name" />
          </ActionForm>
          {spaces.length > 0 && (
            <ActionForm
              title="Workspace membership"
              button="Save membership"
              submit={async (d) => {
                const saved = await api<Row>(
                  "workspaces/" + selected + "/members",
                  {
                    subject: String(d.get("subject")),
                    roles: String(d.get("roles"))
                      .split(",")
                      .map((s) => s.trim())
                      .filter(Boolean),
                  },
                );
                setMembers((current) => [
                  ...current.filter((row) => row.subject !== saved.subject),
                  saved,
                ]);
                setMessage("Membership saved.");
                reload((x) => x + 1);
              }}
            >
              <label>
                Manage members in
                <select
                  required
                  aria-label="Membership workspace"
                  value={selected}
                  onChange={(e) => select(e.target.value)}
                >
                  <option value="">Select a workspace</option>
                  {spaces.map((w) => (
                    <option key={label(w.name)} value={label(w.name)}>
                      {label(w.displayName)}
                    </option>
                  ))}
                </select>
              </label>
              <Field name="subject" title="Identity provider subject" />
              <Field
                name="roles"
                title="Permitted roles, comma separated"
                optional
              />
              <small>Leave roles empty to revoke workspace access.</small>
              {members.length > 0 && (
                <Grid
                  rows={members}
                  columns={[
                    ["subject", "Subject"],
                    ["roles", "Roles"],
                  ]}
                />
              )}
            </ActionForm>
          )}
        </>
      )}
      {message && <p role="status">{message}</p>}
    </>
  );
}
