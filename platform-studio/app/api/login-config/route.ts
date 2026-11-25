export const dynamic = "force-dynamic";
export function GET() {
  const issuer = process.env.PLATFORM_OIDC_ISSUER;
  const clientId = process.env.PLATFORM_OIDC_CLIENT_ID;
  return Response.json(
    {
      oidc:
        issuer && clientId
          ? {
              issuer,
              clientId,
              scope: process.env.PLATFORM_OIDC_SCOPE || "openid profile",
            }
          : null,
      langfuseProjectUrl: process.env.PLATFORM_LANGFUSE_PROJECT_URL,
      local: process.env.PLATFORM_LOCAL_LOGIN === "true",
    },
    { headers: { "Cache-Control": "no-store" } },
  );
}
