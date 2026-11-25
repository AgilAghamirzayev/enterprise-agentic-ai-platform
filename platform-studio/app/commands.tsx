import { useState } from "react";
import { Account, Api, Row, example, label } from "./client";
import { ActionForm, Field, str } from "./components";
import { EvaluationRegistry, ModelForms, QuotaForm } from "./registry-forms";
import { KnowledgeUpload } from "./knowledge-upload";
import { requestId } from "./client";
import type { Section } from "./workspace";

type Props = {
  api: Api;
  page: Section;
  who: Account;
  rows: Row[];
  inspect: (r: Row) => void;
  refresh: () => void;
};
export function Commands({ api, page, who, rows, inspect, refresh }: Props) {
  const editor = who.roles.some((r) => ["admin", "developer"].includes(r));
  const [notice, setNotice] = useState("");
  const [published, setPublished] = useState<{
    name: string;
    version: string;
    revision: string;
  } | null>(null);
  const [choices, setChoices] = useState<Row[] | null>(null);
  const [kind, setKind] = useState("agent");
  const [busy, setBusy] = useState(false);
  async function publish() {
    if (!published) return;
    setBusy(true);
    try {
      const path =
        page.toLowerCase() +
        "/" +
        published.name +
        "/versions/" +
        published.version;
      const headers = { "If-Match": JSON.stringify(published.revision) };
      await api(path + "/validate", {}, headers);
      await api(path + "/publish", {}, headers);
      setPublished(null);
      setNotice("Version published.");
      refresh();
    } catch (e) {
      setNotice((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  const status = notice && <p role="status">{notice}</p>;
  if (editor && (page === "Agents" || page === "Workflows"))
    return (
      <>
        <ActionForm
          title="Create a version"
          button="Save draft"
          submit={async (f) => {
            const name = str(f, "name"),
              version = str(f, "version");
            const draft = await api<Row>(page.toLowerCase(), {
              name,
              version,
              spec: str(f, "spec"),
            });
            setPublished({ name, version, revision: label(draft.revision) });
            setNotice("Draft saved.");
          }}
        >
          <div className="pair">
            <Field name="name" title="Name" initial="hello-agent" />
            <Field name="version" title="Version" initial="1.0.0" />
          </div>
          <Field
            name="spec"
            title="Workflow definition (YAML or JSON)"
            initial={example}
            area
          />
        </ActionForm>
        <details>
          <summary>Resume a saved draft</summary>
          <ActionForm
            title="Review saved definition"
            button="Read draft"
            submit={async (f) => {
              const name = str(f, "name"),
                version = str(f, "version");
              const draft = await api<Row>(
                page.toLowerCase() +
                  "/" +
                  encodeURIComponent(name) +
                  "/versions/" +
                  encodeURIComponent(version) +
                  "/draft",
              );
              inspect(draft);
              setPublished({ name, version, revision: label(draft.revision) });
            }}
          >
            <Field title="Draft name" name="name" />
            <Field title="Draft version" name="version" />
          </ActionForm>
        </details>
        {status}
        {published && (
          <button className="primary" disabled={busy} onClick={publish}>
            Validate and publish {published.name}
          </button>
        )}
      </>
    );
  if (page === "Playground")
    return (
      <>
        <ActionForm
          title="Run a published version"
          button="Start execution"
          submit={async (f) => {
            const ref = str(f, "ref").split(":");
            if (ref.length !== 2)
              throw new Error("Choose a published version.");
            const result = await api<Row>(
              "runs",
              {
                [kind]: { name: ref[0], version: ref[1] },
                input: JSON.parse(str(f, "input")),
                sessionId: str(f, "session") || null,
              },
              {
                "Idempotency-Key": requestId(),
              },
            );
            inspect(result);
          }}
        >
          <label>
            Resource
            <select
              value={kind}
              onChange={async (e) => {
                const v = e.target.value;
                setKind(v);
                setChoices([]);
                try {
                  setChoices(await api<Row[]>(v + "s"));
                } catch (err) {
                  setNotice((err as Error).message);
                }
              }}
            >
              <option value="agent">Agent</option>
              <option value="workflow">Workflow</option>
            </select>
          </label>
          <label>
            Published version
            <select name="ref" required>
              {(choices ?? rows).map((r) => (
                <option key={label(r.name) + ":" + label(r.version)}>
                  {label(r.name)}:{label(r.version)}
                </option>
              ))}
            </select>
          </label>
          <Field
            title="Input (JSON)"
            name="input"
            area
            initial={'{"text":"Hello from Studio"}'}
          />
          <Field name="session" title="Conversation ID (optional)" optional />
        </ActionForm>
        {status}
      </>
    );
  if (page === "Knowledge")
    return (
      <>
        {editor && <KnowledgeUpload api={api} refresh={refresh} />}
        <ActionForm
          title="Search knowledge"
          button="Search"
          submit={async (f) =>
            inspect({
              matches: await api(
                "knowledge-bases/" +
                  encodeURIComponent(str(f, "base")) +
                  "/search?q=" +
                  encodeURIComponent(str(f, "q")),
              ),
            })
          }
        >
          <Field name="base" title="Knowledge base" initial="handbook" />
          <Field name="q" title="Search terms" />
        </ActionForm>
        {status}
      </>
    );
  if (
    page === "Approvals" &&
    who.roles.some((r) => ["admin", "approver"].includes(r))
  )
    return (
      <>
        <ActionForm
          title="Review an action"
          button="Submit decision"
          submit={async (f) => {
            const r = rows.find((r) => r.id === str(f, "id"));
            if (!r) throw new Error("Choose a pending request.");
            await api("approvals/" + r.id + "/decision", {
              approved: str(f, "decision") === "approve",
              actionHash: r.actionHash,
            });
            refresh();
            setNotice("Decision recorded.");
          }}
        >
          <label>
            Pending request
            <select name="id" required>
              {rows
                .filter((r) => r.status === "PENDING")
                .map((r) => (
                  <option value={label(r.id)} key={label(r.id)}>
                    {label(r.tool)} · {label(r.args)} · {label(r.runId)}
                  </option>
                ))}
            </select>
          </label>
          <label>
            Decision
            <select name="decision">
              <option value="reject">Reject</option>
              <option value="approve">Approve</option>
            </select>
          </label>
        </ActionForm>
        {status}
      </>
    );
  if (page === "Tools" && who.roles.includes("admin"))
    return (
      <ActionForm
        title="Publish a tool contract"
        button="Publish tool version"
        submit={async (f) => {
          const name = str(f, "name");
          inspect(
            await api<Row>("tools/" + encodeURIComponent(name) + "/versions", {
              name,
              version: str(f, "version"),
              spec: JSON.parse(str(f, "spec")),
            }),
          );
          refresh();
        }}
      >
        <Field name="name" title="Installed tool name" />
        <Field name="version" title="Tool version" initial="1.0.0" />
        <Field
          name="spec"
          title="Tool contract (JSON)"
          area
          initial={
            '{"inputSchema":{"type":"string"},"outputSchema":{"type":"string"}}'
          }
        />
      </ActionForm>
    );
  if (page === "Models")
    return (
      <ModelForms api={api} who={who} refresh={refresh} inspect={inspect} />
    );
  if (page === "Settings" && who.roles.includes("admin"))
    return <QuotaForm api={api} inspect={inspect} />;
  if (page === "Policies" && who.roles.includes("admin"))
    return (
      <>
        <ActionForm
          title="Publish a policy version"
          button="Publish policy"
          submit={async (f) => {
            inspect(
              await api<Row>("policy-versions", {
                name: str(f, "name"),
                version: str(f, "version"),
                spec: JSON.parse(str(f, "spec")),
              }),
            );
            refresh();
          }}
        >
          <Field title="Policy name" name="name" />
          <Field title="Policy version" name="version" initial="1.0.0" />
          <Field
            title="Policy rules (JSON)"
            name="spec"
            area
            initial={
              '{"defaultEffect":"DENY","rules":[{"resource":"word-count","effect":"ALLOW"}]}'
            }
          />
        </ActionForm>
        <ActionForm
          title="Set tool policy"
          button="Save policy"
          submit={async (f) => {
            await api("policies", {
              tool: str(f, "tool"),
              effect: str(f, "effect"),
              requiredRole: str(f, "role"),
            });
            refresh();
            setNotice("Policy saved.");
          }}
        >
          <Field name="tool" title="Tool name" />
          <label>
            Effect
            <select name="effect">
              <option>DENY</option>
              <option>INHERIT</option>
              <option>REQUIRE_APPROVAL</option>
            </select>
          </label>
          <Field name="role" title="Approval role" initial="approver" />
        </ActionForm>
        {status}
      </>
    );
  if (page === "Evaluations" && editor)
    return (
      <>
        <ActionForm
          title="Evaluate a completed run"
          button="Compare result"
          submit={async (f) => {
            const result = await api<Row>("evaluations", {
              runId: str(f, "id"),
              expected: JSON.parse(str(f, "expected")),
            });
            inspect(result);
            refresh();
          }}
        >
          <Field name="id" title="Execution ID" />
          <Field name="expected" title="Expected result (JSON)" area />
        </ActionForm>
        <EvaluationRegistry api={api} inspect={inspect} />
        {status}
      </>
    );
  return null;
}
