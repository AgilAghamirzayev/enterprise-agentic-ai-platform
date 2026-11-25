export type Row = Record<string, unknown>;
export type Account = {
  tenant: string;
  actor: string;
  workspace: string;
  roles: string[];
};
export type Api = {
  <T>(
    path: string,
    body?: unknown,
    headers?: Record<string, string>,
  ): Promise<T>;
  events(path: string, cursor: string, signal: AbortSignal): Promise<Response>;
  transfer(path: string, file?: File): Promise<Response>;
};

export function apiFor(authorization: string, workspace?: string): Api {
  async function request<T>(
    path: string,
    body?: unknown,
    extra?: Record<string, string>,
  ) {
    const response = await fetch("/api/v1/" + path, {
      method: body === undefined ? "GET" : "POST",
      cache: "no-store",
      headers: {
        Authorization: authorization,
        ...(workspace ? { "X-Platform-Workspace": workspace } : {}),
        "Content-Type": "application/json",
        "X-Platform-Client": "studio",
        ...extra,
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const result = await response.json().catch(() => ({}));
    if (!response.ok)
      throw new Error(
        result.detail ||
          result.message ||
          "Request failed (" + response.status + ")",
      );
    return result as T;
  }
  async function transfer(path: string, file?: File) {
    const headers = new Headers({
      Authorization: authorization,
      "X-Platform-Client": "studio",
    });
    if (workspace) headers.set("X-Platform-Workspace", workspace);
    if (file)
      headers.set("Content-Type", file.type || "application/octet-stream");
    const response = await fetch("/api/v1/" + path, {
      method: file ? "PUT" : "GET",
      body: file,
      headers,
      cache: "no-store",
    });
    if (!response.ok)
      throw new Error("Artifact request failed (" + response.status + ")");
    return response;
  }
  async function events(path: string, cursor: string, signal: AbortSignal) {
    const headers = new Headers({
      Authorization: authorization,
      Accept: "text/event-stream",
      "Last-Event-ID": cursor,
    });
    if (workspace) headers.set("X-Platform-Workspace", workspace);
    return fetch("/api/v1/" + path, { headers, signal, cache: "no-store" });
  }
  return Object.assign(request, { transfer, events });
}
export function label(value: unknown) {
  return value == null
    ? ""
    : typeof value === "object"
      ? JSON.stringify(value)
      : String(value);
}
export const example = `start: answer
nodes:
  - id: answer
    type: AGENT
    prompt: Answer the user's question concisely.
    next: finish
  - id: finish
    type: END`;

export function requestId() {
  return Array.from(crypto.getRandomValues(new Uint8Array(16)), (b) =>
    b.toString(16).padStart(2, "0"),
  ).join("");
}
