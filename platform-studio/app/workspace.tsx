"use client";
import { useCallback, useEffect, useState } from "react";
import { Account, Api, Row, label } from "./client";
import { Timeline } from "./timeline";
import { Artifacts } from "./artifacts";
import { Audit } from "./audit";
import { Directory } from "./directory";
import { Activity } from "./activity";
import { Grid } from "./components";
import { Commands } from "./commands";

const sections = {
  Overview: { path: "usage", columns: [] },
  Agents: {
    path: "agents",
    columns: [
      ["name", "Agent"],
      ["version", "Version"],
      ["createdAt", "Published"],
    ],
  },
  Workflows: {
    path: "workflows",
    columns: [
      ["name", "Workflow"],
      ["version", "Version"],
      ["createdAt", "Published"],
    ],
  },
  Playground: { path: "agents", columns: [] },
  Executions: {
    path: "runs",
    columns: [
      ["name", "Definition"],
      ["version", "Version"],
      ["status", "Status"],
      ["createdAt", "Started"],
    ],
  },
  Tools: {
    path: "tools",
    columns: [
      ["name", "Tool"],
      ["effect", "Operation"],
      ["source", "Integration"],
    ],
  },
  Knowledge: {
    path: "knowledge-bases/documents",
    columns: [
      ["title", "Document"],
      ["base", "Knowledge base"],
      ["createdAt", "Indexed"],
    ],
  },
  Models: {
    path: "model-profiles",
    columns: [
      ["name", "Profile"],
      ["model", "Model"],
      ["provider", "Provider"],
    ],
  },
  Approvals: {
    path: "approvals",
    columns: [
      ["tool", "Action"],
      ["requiredRole", "Role"],
      ["status", "Status"],
      ["expiresAt", "Expires"],
    ],
  },
  Policies: {
    path: "policies",
    columns: [
      ["tool", "Tool"],
      ["effect", "Effect"],
      ["requiredRole", "Role"],
      ["revision", "Revision"],
    ],
  },
  Evaluations: {
    path: "evaluations",
    columns: [
      ["runId", "Execution"],
      ["passed", "Passed"],
      ["createdAt", "Evaluated"],
    ],
  },
  Audit: {
    path: "audit-events",
    columns: [
      ["eventType", "Event"],
      ["actor", "Actor"],
      ["resource", "Resource"],
      ["occurredAt", "Time"],
    ],
  },
  Settings: { path: "me", columns: [] },
} as const;
export type Section = keyof typeof sections;
const roleSections: Record<string, Section[]> = {
  developer: [
    "Overview",
    "Agents",
    "Workflows",
    "Playground",
    "Executions",
    "Tools",
    "Knowledge",
    "Models",
    "Evaluations",
    "Settings",
  ],
  user: ["Agents", "Workflows", "Playground", "Knowledge", "Settings"],
  operator: [
    "Overview",
    "Agents",
    "Workflows",
    "Playground",
    "Executions",
    "Tools",
    "Knowledge",
    "Models",
    "Settings",
  ],
  approver: ["Approvals", "Settings"],
  auditor: [
    "Overview",
    "Agents",
    "Workflows",
    "Executions",
    "Approvals",
    "Evaluations",
    "Policies",
    "Audit",
    "Settings",
  ],
};
type Props = {
  langfuseProjectUrl?: string;
  who: Account;
  api: Api;
  signOut: () => void;
  switchWorkspace: (name: string) => Promise<void>;
};
export function Workspace({ who, api, signOut, switchWorkspace, langfuseProjectUrl }: Props) {
  const all = Object.keys(sections) as Section[];
  const menu = all.filter(
    (page) =>
      who.roles.includes("admin") ||
      who.roles.some((r) => roleSections[r]?.includes(page)) ||
      page === "Settings",
  );
  const [page, setPage] = useState<Section>(menu[0]);
  return (
    <div className="shell">
      <aside>
        <div className="brand">
          <span className="mark">A</span>Agent Studio
        </div>
        <div className="workspace-id">
          <span className="dot" />
          {who.tenant}
        </div>
        <nav aria-label="Workspace">
          {menu.map((name) => (
            <button
              aria-current={name === page ? "page" : undefined}
              key={name}
              onClick={() => setPage(name)}
            >
              {name}
            </button>
          ))}
        </nav>
        <div className="account">
          <strong>{who.actor}</strong>
          <button onClick={signOut}>Sign out</button>
        </div>
      </aside>
      <main className="content">
        <header>
          <span>
            Workspace / {who.tenant} / {who.workspace}
          </span>
          <span className="version">v0.1</span>
        </header>
        <section key={page}>
          {page === "Audit" ? (
            <>
              <h1>Audit</h1>
              <Audit api={api} />
            </>
          ) : (
            <Screen langfuseProjectUrl={langfuseProjectUrl} page={page} api={api} who={who} />
          )}
          {page === "Settings" && (
            <Directory who={who} api={api} switchWorkspace={switchWorkspace} />
          )}
        </section>
      </main>
    </div>
  );
}

function Screen({ page, api, who, langfuseProjectUrl }: { page: Section; api: Api; who: Account; langfuseProjectUrl?: string }) {
  const [rows, setRows] = useState<Row[]>([]);
  const [health, setHealth] = useState<Row>({});
  const [summary, setSummary] = useState<Row>({});
  const [selected, setSelected] = useState<Row | null>(null);
  const [revision, setRevision] = useState(0);
  const [filter, setFilter] = useState("");
  const [err, setErr] = useState("");
  const [loading, setLoading] = useState(true);
  useEffect(() => {
    let active = true;
    async function read() {
      setLoading(true);
      setErr("");
      try {
        const data = await api<Row | Row[]>(sections[page].path);
        const [recent, status] =
          page === "Overview"
            ? await Promise.all([api<Row[]>("runs"), api<Row>("status")])
            : [[], {}];
        if (active) {
          setHealth(status);
          setRows(Array.isArray(data) ? data : recent);
          setSummary(Array.isArray(data) ? {} : data);
        }
      } catch (e) {
        if (active) setErr((e as Error).message);
      } finally {
        if (active) setLoading(false);
      }
    }
    void read();
    return () => {
      active = false;
    };
  }, [api, page, revision]);
  const refresh = useCallback(() => setRevision((n) => n + 1), []);
  const cols =
    page === "Overview" ? sections.Executions.columns : sections[page].columns;
  const shown = rows.filter((r) =>
    Object.values(r).some((v) =>
      label(v).toLowerCase().includes(filter.toLowerCase()),
    ),
  );
  return (
    <>
      <div className="split title">
        <h1>{page}</h1>
        <button onClick={refresh}>Refresh</button>
      </div>
      {err && (
        <p className="error" role="alert">
          {err}
        </p>
      )}
      {page === "Overview" && (
        <div className="metrics">
          {[
            ["runs", "Total executions"],
            ["running", "Running"],
            ["succeeded", "Succeeded"],
            ["modelCalls", "Model calls"],
            ["successPercent24h", "Success, last 24h (%)"],
            ["latencyP95Ms24h", "P95 run time, last 24h (ms)"],
            ["inputTokens", "Input tokens"],
            ["outputTokens", "Output tokens"],
          ].map(([id, title]) => (
            <div key={id}>
              <span>{title}</span>
              <strong>
                {label(summary[id]) ||
                  (id.endsWith("24h") ? "No completed runs" : "0")}
              </strong>
            </div>
          ))}
        </div>
      )}
      {page === "Overview" && (
        <section className="panel">
          <h2>Service health</h2>
          <dl>
            {[
              ["api", "API"],
              ["temporal", "Temporal"],
              ["worker", "Worker"],
            ].map(([key, title]) => (
              <div key={key}>
                <dt>{title}</dt>
                <dd>{label(health[key]) || "Checking"}</dd>
              </div>
            ))}
          </dl>
        </section>
      )}
      {page === "Overview" && <Activity usage={summary} />}
      {page === "Settings" && (
        <div className="panel">
          <h2>Authenticated identity</h2>
          <dl>
            <dt>Tenant</dt>
            <dd>{who.tenant}</dd>
            <dt>Subject</dt>
            <dd>{who.actor}</dd>
            <dt>Roles</dt>
            <dd>{who.roles.join(", ")}</dd>
          </dl>
        </div>
      )}
      {cols.length > 0 && (
        <section className="panel">
          <div className="split">
            <h2>
              {page === "Overview"
                ? "Recent executions"
                : page === "Agents" || page === "Workflows"
                  ? "Published versions"
                  : "Records"}
            </h2>
            <input
              className="search"
              aria-label="Filter records"
              placeholder="Filter records"
              value={filter}
              onChange={(e) => setFilter(e.target.value)}
            />
          </div>
          {loading ? (
            <p>Loading…</p>
          ) : (
            <Grid
              rows={shown}
              columns={cols.map(([id, t]) => [id, t])}
              open={setSelected}
            />
          )}
        </section>
      )}
      {selected && (
        <Inspection
          langfuseProjectUrl={langfuseProjectUrl}
          who={who}
          row={selected}
          page={page}
          api={api}
          close={() => setSelected(null)}
        />
      )}
      <Commands
        api={api}
        page={page}
        who={who}
        rows={rows}
        inspect={setSelected}
        refresh={refresh}
      />
    </>
  );
}

function Inspection({
  langfuseProjectUrl,
  who,
  row,
  page,
  api,
  close,
}: {
  row: Row;
  langfuseProjectUrl?: string;
  who: Account;
  page: Section;
  api: Api;
  close: () => void;
}) {
  const id = label(row.runId || row.id);
  const run = ["Overview", "Executions", "Playground"].includes(page) && !!id;
  const [current, setCurrent] = useState(row);
  // The low bit of the W3C trace flags is the sampled flag.
  const traceId = /^00-([a-f0-9]{32})-[a-f0-9]{16}-[a-f0-9][13579bdf]$/.exec(label(current.traceparent))?.[1];
  const [steps, setSteps] = useState<Row[]>([]);
  const [err, setErr] = useState("");
  useEffect(() => {
    setCurrent(row);
    setSteps([]);
    setErr("");
    if (!run) return;
    let active = true;
    let timer: ReturnType<typeof setTimeout>;
    async function poll() {
      try {
        const [detail, history] = await Promise.all([
          api<Row>("runs/" + id),
          api<Row[]>("runs/" + id + "/steps"),
        ]);
        if (!active) return;
        setCurrent(detail);
        setSteps(history);
        if (
          ["QUEUED", "RUNNING", "WAITING_APPROVAL"].includes(
            label(detail.status),
          )
        )
          timer = setTimeout(poll, 1500);
      } catch (e) {
        if (active) setErr((e as Error).message);
      }
    }
    void poll();
    return () => {
      active = false;
      clearTimeout(timer);
    };
  }, [api, id, row, run]);
  const graph = (current.spec as { nodes?: Row[] } | undefined)?.nodes;
  return (
    <section className="panel">
      <div className="split">
        <h2>{run ? "Execution detail" : "Record detail"}</h2>
        <button onClick={close}>Close</button>
      </div>
      {err && <p className="error">{err}</p>}
      {graph && (
        <div className="graph">
          {graph.map((n) => (
            <div key={label(n.id)}>
              <small>{label(n.type)}</small>
              <b>{label(n.id)}</b>
              <span>
                {[
                  n.next,
                  n.onTrue,
                  n.onFalse,
                  n.onApproved,
                  n.onRejected,
                  ...(Array.isArray(n.branches) ? n.branches : []),
                ]
                  .filter(Boolean)
                  .join(", ")}
              </span>
            </div>
          ))}
        </div>
      )}
      {run && (
        <>
          {traceId && langfuseProjectUrl && (
            <a href={langfuseProjectUrl.replace(/\/$/, "") + "/traces/" + traceId}
              target="_blank" rel="noopener noreferrer">View trace in Langfuse</a>
          )}
          <p className="mono">
            {id} · {label(current.status)}
          </p>
          <Grid
            rows={steps}
            columns={[
              ["stepKey", "Step"],
              ["output", "Output"],
            ]}
          />
          {current.result != null && (
            <pre>{JSON.stringify(current.result, null, 2)}</pre>
          )}
          {["QUEUED", "RUNNING", "WAITING_APPROVAL"].includes(
            label(current.status),
          ) && (
            <button
              onClick={async () => {
                try {
                  setCurrent(await api<Row>("runs/" + id + "/cancel", {}));
                } catch (e) {
                  setErr((e as Error).message);
                }
              }}
            >
              Request cancellation
            </button>
          )}
        </>
      )}
      {run && <Timeline api={api} run={id} />}
      {run && (
        <Artifacts
          api={api}
          run={id}
          canUpload={
            who.roles.includes("admin") ||
            (who.actor === current.actor &&
              who.roles.some((role) =>
                ["user", "operator", "developer"].includes(role),
              ))
          }
        />
      )}
      <details open={!run && !graph}>
        <summary>JSON record</summary>
        <pre>{JSON.stringify(current, null, 2)}</pre>
      </details>
    </section>
  );
}
