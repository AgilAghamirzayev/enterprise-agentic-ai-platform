import { NextRequest } from "next/server";

type Context = { params: Promise<{ path: string[] }> };

async function forward(request: NextRequest, context: Context) {
  const parts = (await context.params).path;
  if (
    parts.some((p) => !/^[a-zA-Z0-9._-]+$/.test(p) || p === ".." || p === ".")
  )
    return new Response(null, { status: 400 });
  if (
    !["GET", "HEAD"].includes(request.method) &&
    request.headers.get("x-platform-client") !== "studio"
  )
    return new Response(null, { status: 403 });
  const headers = new Headers();
  for (const key of [
    "authorization",
    "content-type",
    "idempotency-key",
    "if-match",
    "last-event-id",
    "x-platform-client",
    "x-platform-workspace",
    "accept",
  ]) {
    const value = request.headers.get(key);
    if (value) headers.set(key, value);
  }
  const target = new URL(
    "/api/v1/" + parts.join("/"),
    process.env.PLATFORM_API_URL || "http://127.0.0.1:18080",
  );
  target.search = request.nextUrl.search;
  const upstream = await fetch(target, {
    method: request.method,
    headers,
    body: !["GET", "HEAD"].includes(request.method) ? request.body : undefined,
    duplex: "half",
    cache: "no-store",
    redirect: "error",
    signal: AbortSignal.timeout(35000),
  } as RequestInit);
  return new Response(upstream.body, {
    status: upstream.status,
    headers: {
      "Content-Type":
        upstream.headers.get("content-type") || "application/json",
      "Cache-Control": "no-store",
      "X-Content-Type-Options": "nosniff",
      ...(upstream.headers.has("content-disposition")
        ? {
            "Content-Disposition": upstream.headers.get("content-disposition")!,
          }
        : {}),
    },
  });
}
export const GET = forward;
export const POST = forward;
export const PUT = forward;
export const dynamic = "force-dynamic";
