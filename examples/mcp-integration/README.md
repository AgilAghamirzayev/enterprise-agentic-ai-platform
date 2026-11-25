# Install a catalog connector

This example binds one read-only MCP operation to the local tenant. Supply a Streamable HTTP server that exposes `search_catalog` with a string `query` argument. Replace the example endpoint in `bindings.json` with its HTTPS URL and provide its scoped bearer credential in the worker environment. Use a credential that can only perform the configured read. Mount this file read-only into the worker and set `PLATFORM_CONNECTORS_FILE` to its container path. Mount the same binding file on the API to display its catalog entry in Studio. The API only reads binding metadata.

Publish `definition.json` in Studio and execute it as an operator with input `{"query":"shipping policy"}`. The worker selects the fixed remote tool, validates argument names and checks tenant membership before connecting. The client performs MCP initialization and sends the tool call over TLS. Redirects are disabled. Restrict worker egress to the approved upstream host in your deployment.

For a REST read, change the type to `rest`, configure a fixed HTTPS endpoint and omit `remoteTool`. The adapter translates allowlisted scalar arguments into URL-encoded query parameters on a GET. The connector contract tests exercise TLS, REST and MCP against an isolated test server.
