import { FormEvent, ReactNode, useState } from "react";
import { Row, label } from "./client";

export function ActionForm({
  title,
  children,
  submit,
  button,
}: {
  title: string;
  children: ReactNode;
  submit: (form: FormData) => Promise<void>;
  button: string;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  async function run(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    setBusy(true);
    setError("");
    try {
      await submit(data);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <form className="panel form" onSubmit={run}>
      <h2>{title}</h2>
      {children}
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
      <div>
        <button className="primary" disabled={busy}>
          {busy ? "Working…" : button}
        </button>
      </div>
    </form>
  );
}
export function Field({
  name,
  title,
  initial = "",
  area = false,
  optional = false,
}: {
  name: string;
  title: string;
  initial?: string;
  area?: boolean;
  optional?: boolean;
}) {
  return (
    <label>
      {title}
      {area ? (
        <textarea
          name={name}
          defaultValue={initial}
          required={!optional}
          rows={7}
        />
      ) : (
        <input name={name} defaultValue={initial} required={!optional} />
      )}
    </label>
  );
}
export function Grid({
  rows,
  columns,
  open,
}: {
  rows: Row[];
  columns: [string, string][];
  open?: (r: Row) => void;
}) {
  if (!rows.length) return <div className="empty">No records yet.</div>;
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            {columns.map(([id, title]) => (
              <th key={id}>{title}</th>
            ))}
            {open && <th />}
          </tr>
        </thead>
        <tbody>
          {rows.map((r, i) => (
            <tr key={label(r.id || r.name || i) + "-" + i}>
              {columns.map(([id]) => (
                <td key={id}>
                  {id === "status" ? (
                    <span className={"status " + label(r[id]).toLowerCase()}>
                      {label(r[id])}
                    </span>
                  ) : id.endsWith("At") && r[id] ? (
                    new Date(label(r[id])).toLocaleString()
                  ) : (
                    label(r[id])
                  )}
                </td>
              ))}
              {open && (
                <td>
                  <button onClick={() => open(r)}>Inspect</button>
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
export const str = (form: FormData, name: string) =>
  String(form.get(name) || "").trim();
