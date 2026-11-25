# Workspaces and installed tools

## Tenant and workspace identities

The server derives the organization and subject from the verified identity. Existing data belongs to the `default` workspace. An OIDC `workspace_id` claim pins the session to one workspace. For an unpinned identity, set `X-Platform-Workspace` to select a named workspace. PostgreSQL partitions, the worker, session memory and run artifacts use the same effective scope. The monthly parent-tenant cap also applies to consumption in named workspaces.

A tenant administrator can create a workspace in Studio Settings. Assign the identity provider's exact subject and the permitted roles. Effective roles are the intersection of the identity's roles and the stored grant. Submit an empty role list to revoke access. The API checks membership on every request. A tenant administrator manages all workspaces in the tenant. An IdP-issued `platform_admin` can configure tenant metadata through the control API. Normal invocations use the signed tenant claim.

| Method and resource | Operation |
| --- | --- |
| GET /api/v1/tenant | Read current tenant metadata |
| POST /api/v1/tenants/{tenant} | Set the tenant display name |
| GET /api/v1/workspaces | List visible named workspaces |
| POST /api/v1/workspaces | Create a workspace with name and displayName |
| GET or POST /api/v1/workspaces/{name}/members | List or replace the roles for one subject |
| GET /api/v1/runs/{id}/artifacts | List immutable files for an authorized run |
| PUT or GET /api/v1/runs/{id}/artifacts/{name} | Upload raw bytes or download an attachment |

Each artifact is limited to 512 KiB, with at most 32 files and 8 MiB per run. Repeating the same name, media type and contents is idempotent. A conflicting upload returns HTTP 409. The runtime database role only has INSERT and SELECT on artifact records. Include the database in the backup and retention policy for these files.

## Connector contracts

Install bindings in the API and worker deployment with `PLATFORM_CONNECTORS_FILE`. Use the full scope as `tenant` for a named workspace, for example `retail~invoices`. The public API can publish a versioned tool schema against a deployed binding. Creating a binding or changing a network destination requires an operator deployment.

```json
{
  "name": "create-review",
  "version": "1.0.0",
  "tenant": "retail~invoices",
  "type": "rest",
  "endpoint": "https://reviews.example.org/api/reviews",
  "method": "POST",
  "idempotencySupported": true,
  "arguments": ["subject"],
  "outputFields": ["receipt"]
}
```

The configuration file contains an array of such objects. For a POST binding, the receiving service must implement idempotency using the supplied header. The worker first validates policy and exact approval, atomically claims the dispatch, and records the resulting receipt. If the service might have accepted the request before the connection failed, inspect the service records using the same idempotency key. An authorized administrator can record the verified outcome through the external-effects API.

For MCP, set `type` to `mcp`, configure a fixed HTTPS `endpoint` and `remoteTool`, and set `mcpInputSchema` to the expected discovered schema when publishing a pinned integration. For authenticated MCP connections, set `tokenEnv` to the operator-owned credential variable and set `access` to an object containing `issuer`, `jwksUri` and `audience`. The connector verifies the signature, validity period and audience before sending the bearer credential. Scope the token to that resource and rotate it through the deployment. An output field allowlist can project a structured result before persistence. Unstructured MCP text needs its own content policy.

## Verification environments

The primary Compose suite uses generated local accounts. `scripts/verify-oidc.sh` starts a second API and Studio on the internal Compose network and uses a disposable issuer. The browser completes authorization code with S256 PKCE and runs an agent using the signed token. The issuer has one synthetic identity, no published ports and no persisted signing material. Validate the production issuer's claim mapping, external TLS, backup policy and cluster limits in the intended deployment.
