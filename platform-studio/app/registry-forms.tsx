import { useState } from "react";
import type { Account, Api, Row } from "./client";
import { ActionForm, Field, str } from "./components";

const dataset = {
  name: "support-cases",
  version: "1.0.0",
  cases: [{ id: "greeting", input: "Hello", expected: "Hello" }],
};
const suite = {
  name: "support-regression",
  version: "1.0.0",
  dataset: "support-cases:1.0.0",
  target: { kind: "agent", name: "hello-agent", version: "1.0.0" },
  minPassRate: 1,
};

export function EvaluationRegistry({
  api,
  inspect,
}: {
  api: Api;
  inspect: (r: Row) => void;
}) {
  const [resource, setResource] = useState("evaluation-datasets");
  const initial =
    resource === "evaluation-datasets"
      ? dataset
      : resource === "evaluation-suites"
        ? suite
        : {
            suite: "support-regression:1.0.0",
            runs: { greeting: "execution-uuid" },
          };
  return (
    <section>
      <h2 className="subheading">Regression suites</h2>
      <label className="selector">
        Registry
        <select value={resource} onChange={(e) => setResource(e.target.value)}>
          <option value="evaluation-datasets">Datasets</option>
          <option value="evaluation-suites">Suites</option>
          <option value="evaluation-reports">Reports</option>
        </select>
      </label>
      <ActionForm
        key={resource}
        title={
          resource === "evaluation-reports"
            ? "Score completed executions"
            : "Publish an immutable version"
        }
        button="Submit"
        submit={async (f) =>
          inspect(await api<Row>(resource, JSON.parse(str(f, "definition"))))
        }
      >
        <Field
          name="definition"
          title="Definition (JSON)"
          initial={JSON.stringify(initial, null, 2)}
          area
        />
      </ActionForm>
      <ActionForm
        title="Browse registry"
        button="Show records"
        submit={async () => inspect({ records: await api(resource) })}
      >
        {null}
      </ActionForm>
    </section>
  );
}

export function ModelForms({
  api,
  who,
  refresh,
  inspect,
}: {
  api: Api;
  who: Account;
  refresh: () => void;
  inspect: (r: Row) => void;
}) {
  const admin = who.roles.includes("admin");
  return (
    <>
      {admin && (
        <ActionForm
          title="Publish a model profile"
          button="Publish profile"
          submit={async (f) => {
            const fallback = str(f, "fallback");
            inspect(
              await api<Row>("model-profiles", {
                name: str(f, "name"),
                version: str(f, "version"),
                model: str(f, "model"),
                ...(fallback ? { fallbackModel: fallback } : {}),
                ...(str(f, "profileOptions")
                  ? JSON.parse(str(f, "profileOptions"))
                  : {}),
              }),
            );
            refresh();
          }}
        >
          <div className="pair">
            <Field name="name" title="Profile name" />
            <Field name="version" title="Version" initial="1.0.0" />
          </div>
          <Field name="model" title="Installed model" initial="echo" />
          <Field name="fallback" title="Fallback model (optional)" optional />
          <details>
            <summary>Model limits and routing</summary>
            <Field
              name="profileOptions"
              title="Parameters, capabilities and fallback profiles (JSON)"
              area
              optional
            />
          </details>
        </ActionForm>
      )}
      {(admin || who.roles.includes("developer")) && (
        <ActionForm
          title="Publish a prompt"
          button="Publish prompt"
          submit={async (f) => {
            inspect(
              await api<Row>("prompts", {
                name: str(f, "name"),
                version: str(f, "version"),
                text: str(f, "prompt"),
              }),
            );
          }}
        >
          <div className="pair">
            <Field name="name" title="Prompt name" />
            <Field name="version" title="Version" initial="1.0.0" />
          </div>
          <Field name="prompt" title="System prompt" area />
        </ActionForm>
      )}
      <ActionForm
        title="Published prompts"
        button="View prompts"
        submit={async () => inspect({ prompts: await api("prompts") })}
      >
        {null}
      </ActionForm>
    </>
  );
}

export function QuotaForm({
  api,
  inspect,
}: {
  api: Api;
  inspect: (r: Row) => void;
}) {
  return (
    <>
      <ActionForm
        title="Monthly execution quota"
        button="Apply quota"
        submit={async (f) => {
          const tokens = str(f, "tokens"),
            usd = str(f, "usd");
          inspect(
            await api<Row>("quotas", {
              scope: str(f, "scope"),
              monthlyTokens: tokens ? Number(tokens) : null,
              monthlyUsd: usd ? Number(usd) : null,
            }),
          );
        }}
      >
        <Field
          name="scope"
          title="Scope (*, agent:name or workflow:name)"
          initial="*"
        />
        <div className="pair">
          <Field name="tokens" title="Token limit (optional)" optional />
          <Field name="usd" title="USD limit (optional)" optional />
        </div>
      </ActionForm>
      <ActionForm
        title="Current quotas"
        button="View quotas"
        submit={async () => inspect({ quotas: await api("quotas") })}
      >
        {null}
      </ActionForm>
    </>
  );
}
