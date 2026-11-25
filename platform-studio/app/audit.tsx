import { FormEvent, useEffect, useState } from "react";
import { Api, Row } from "./client";
import { Grid } from "./components";

export function Audit({ api }: { api: Api }) {
  const [text, setText] = useState("");
  const [query, setQuery] = useState("");
  const [pages, update] = useState<number[]>([]);
  const [rows, setRows] = useState<Row[]>([]);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(true);
  const cursor = pages.at(-1);
  useEffect(() => {
    let active = true;
    setBusy(true);
    setError("");
    const params = new URLSearchParams({ q: query, limit: "50" });
    if (cursor) params.set("before", String(cursor));
    api<Row[]>("audit-events?" + params)
      .then((r) => {
        if (active) setRows(r);
      })
      .catch((e) => {
        if (active) setError(e.message);
      })
      .finally(() => {
        if (active) setBusy(false);
      });
    return () => {
      active = false;
    };
  }, [api, query, cursor]);
  function search(e: FormEvent) {
    e.preventDefault();
    update([]);
    setQuery(text.trim());
  }
  return (
    <section className="panel">
      <form onSubmit={search}>
        <label>
          Search audit history
          <input
            value={text}
            maxLength={128}
            onChange={(e) => setText(e.target.value)}
          />
        </label>
        <button>Search</button>
      </form>
      {error && <p role="alert">{error}</p>}
      {busy ? (
        <p>Loading…</p>
      ) : (
        <Grid
          rows={rows}
          columns={[
            ["eventType", "Event"],
            ["actor", "Actor"],
            ["resource", "Resource"],
            ["occurredAt", "Time"],
          ]}
        />
      )}
      <div className="split">
        <button
          disabled={busy || !pages.length}
          onClick={() => update((p) => p.slice(0, -1))}
        >
          Newer events
        </button>
        <button
          disabled={busy || rows.length < 50}
          onClick={() => update((p) => [...p, Number(rows.at(-1)?.id)])}
        >
          Older events
        </button>
      </div>
    </section>
  );
}
