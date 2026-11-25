import { useEffect, useState } from "react";
import { Api, Row, label } from "./client";
import { Grid } from "./components";

const terminal = new Set([
  "run.completed",
  "run.succeeded",
  "run.denied",
  "run.failed",
  "run.timed_out",
  "run.expired",
  "run.budget_exceeded",
  "run.cancelled",
]);
export function Timeline({ api, run }: { api: Api; run: string }) {
  const [items, setItems] = useState<Row[]>([]);
  const [error, setError] = useState("");
  useEffect(() => {
    const stop = new AbortController();
    let cursor = "0";
    const retained: Row[] = [];
    setItems([]);
    setError("");
    async function collect() {
      while (!stop.signal.aborted) {
        let reader: ReadableStreamDefaultReader<string> | undefined;
        try {
          const response = await api.events(
            "runs/" + run + "/events",
            cursor,
            stop.signal,
          );
          if (
            response.status === 401 ||
            response.status === 403 ||
            response.status === 404
          ) {
            setError("Timeline is unavailable for this identity.");
            return;
          }
          if (!response.ok || !response.body)
            throw new Error("Timeline connection unavailable");
          reader = response.body
            .pipeThrough(new TextDecoderStream())
            .getReader();
          let buffer = "";
          setError("");
          while (!stop.signal.aborted) {
            const part = await reader.read();
            if (part.done) break;
            buffer = (buffer + part.value).replace(/\r\n/g, "\n");
            let boundary;
            while ((boundary = buffer.indexOf("\n\n")) >= 0) {
              const block = buffer.slice(0, boundary);
              buffer = buffer.slice(boundary + 2);
              const data = block
                .split("\n")
                .filter((l) => l.startsWith("data:"))
                .map((l) => l.slice(5).trimStart())
                .join("\n");
              if (!data) continue;
              const event = JSON.parse(data) as Row;
              const id = label(event.id);
              if (!/^\d+$/.test(id) || BigInt(id) <= BigInt(cursor)) continue;
              cursor = id;
              retained.push(event);
              if (retained.length > 100) retained.shift();
              if (!stop.signal.aborted) setItems([...retained]);
              if (terminal.has(label(event.eventType))) return;
            }
          }
        } catch {
          if (!stop.signal.aborted)
            setError("Reconnecting to the execution timeline…");
        } finally {
          await reader?.cancel().catch(() => {});
        }
        if (!stop.signal.aborted)
          await new Promise((resolve) => setTimeout(resolve, 1000));
      }
    }
    void collect();
    return () => stop.abort();
  }, [api, run]);
  return (
    <section>
      <h3>Execution timeline</h3>
      {error && <p role="status">{error}</p>}
      <Grid
        rows={items}
        columns={[
          ["occurredAt", "Time"],
          ["eventType", "Event"],
          ["resource", "Resource"],
        ]}
      />
    </section>
  );
}
