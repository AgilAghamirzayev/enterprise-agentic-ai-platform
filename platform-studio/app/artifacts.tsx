import { useEffect, useState } from "react";
import { Api, Row, label } from "./client";
import { ActionForm } from "./components";
export function Artifacts({
  api,
  run,
  canUpload,
}: {
  api: Api;
  run: string;
  canUpload: boolean;
}) {
  const [files, setFiles] = useState<Row[]>([]);
  const [error, setError] = useState("");
  const [revision, setRevision] = useState(0);
  const base = "runs/" + run + "/artifacts";
  useEffect(() => {
    let active = true;
    api<Row[]>(base)
      .then((r) => {
        if (active) setFiles(r);
      })
      .catch((e) => {
        if (active) setError(e.message);
      });
    return () => {
      active = false;
    };
  }, [api, base, revision]);
  async function download(name: string) {
    try {
      const response = await api.transfer(
        base + "/" + encodeURIComponent(name),
      );
      const url = URL.createObjectURL(await response.blob());
      const a = document.createElement("a");
      a.href = url;
      a.download = name;
      a.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (e) {
      setError((e as Error).message);
    }
  }
  return (
    <>
      <h3>Execution files</h3>
      {files.map((row) => (
        <p key={label(row.name)}>
          <button onClick={() => download(label(row.name))}>
            {label(row.name)}
          </button>{" "}
          {label(row.sizeBytes)} bytes
        </p>
      ))}
      {error && <p role="alert">{error}</p>}
      {canUpload && (
        <ActionForm
          title="Attach a file"
          button="Upload"
          submit={async (data) => {
            const file = data.get("file");
            if (
              !(file instanceof File) ||
              file.size === 0 ||
              file.size > 524288
            )
              throw new Error("Choose a file between 1 byte and 512 KiB.");
            if (!/^[a-zA-Z0-9][a-zA-Z0-9._-]{0,95}$/.test(file.name))
              throw new Error(
                "Use a filename with letters, numbers, dots, underscores or hyphens.",
              );
            await api.transfer(base + "/" + file.name, file);
            setRevision((v) => v + 1);
          }}
        >
          <label>
            Execution file
            <input type="file" name="file" required />
          </label>
        </ActionForm>
      )}
    </>
  );
}
