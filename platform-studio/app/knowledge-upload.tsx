import { useEffect, useState } from "react";
import { Api, Row, label, requestId } from "./client";
import { ActionForm, Field, str } from "./components";

export function KnowledgeUpload({
  api,
  refresh,
}: {
  api: Api;
  refresh: () => void;
}) {
  const [job, setJob] = useState<{ path: string; value: Row } | null>(null);
  const [error, setError] = useState("");
  const path = job?.path;
  const state = label(job?.value.status);
  useEffect(() => {
    if (!path || ["SUCCEEDED", "FAILED"].includes(state)) return;
    let active = true;
    let timer: ReturnType<typeof setTimeout>;
    async function poll() {
      try {
        const result = await api<Row>(path!);
        if (!active) return;
        setJob({ path: path!, value: result });
        if (result.status === "SUCCEEDED") refresh();
        else if (result.status !== "FAILED") timer = setTimeout(poll, 1500);
      } catch (e) {
        if (active) setError((e as Error).message);
      }
    }
    timer = setTimeout(poll, 650);
    return () => {
      active = false;
      clearTimeout(timer);
    };
  }, [api, path, state, refresh]);
  return (
    <>
      <ActionForm
        title="Index a document"
        button="Queue indexing"
        submit={async (f) => {
          setError("");
          const base = str(f, "base");
          const file = f.get("file");
          if (file instanceof File && file.size > 262144)
            throw new Error("Choose a text file up to 256 KiB.");
          const content =
            file instanceof File && file.size > 0
              ? await file.text()
              : str(f, "content");
          if (!content.trim())
            throw new Error("Enter document text or choose a text file.");
          const collection =
            "knowledge-bases/" + encodeURIComponent(base) + "/ingestions";
          const result = await api<Row>(
            collection,
            {
              documentKey: str(f, "documentKey"),
              ...(str(f, "sourceUri")
                ? { sourceUri: str(f, "sourceUri") }
                : {}),
              ...(str(f, "classification")
                ? { classification: str(f, "classification") }
                : {}),
              title: str(f, "title"),
              content,
              roles: str(f, "roles")
                .split(",")
                .map((s) => s.trim())
                .filter(Boolean),
            },
            { "Idempotency-Key": requestId() },
          );
          setJob({ path: collection + "/" + result.id, value: result });
        }}
      >
        <div className="pair">
          <Field name="base" title="Knowledge base" initial="handbook" />
          <Field name="documentKey" title="Document key" />
        </div>
        <div className="pair">
          <Field name="title" title="Title" />
          <Field
            name="roles"
            title="Allowed roles (comma-separated)"
            optional
          />
        </div>
        <Field name="sourceUri" title="Source URI (optional)" optional />
        <Field
          name="classification"
          title="Document classification (optional)"
          optional
        />
        <label>
          Text or Markdown file
          <input
            type="file"
            name="file"
            accept=".txt,.md,text/plain,text/markdown"
          />
        </label>
        <Field name="content" title="Document text" area optional />
      </ActionForm>
      {error && <p role="alert">{error}</p>}
      {job && (
        <p role="status">
          Revision {label(job.value.revision)} · {state}
        </p>
      )}
    </>
  );
}
