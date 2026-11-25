# Security reporting and deployment

Use GitHub’s [private vulnerability report form](https://github.com/AgilAghamirzayev/enterprise-agentic-ai-platform/security/advisories/new) to reach the maintainer. Include the affected version, component and observed impact. Redact tokens, prompts, document contents and tenant identifiers in shared reports.

Run the API under its runtime database role. Supply migration credentials only to the process applying Liquibase. Keep operator-installed Java handlers and connectors under the same review as application code. Restrict outbound network access to the configured provider and tool endpoints. Treat retrieved text, tool results and model output as untrusted data.

Configure TLS, secret rotation and encrypted backups for the target deployment. Verify restore procedures and tenant isolation with representative data before onboarding a tenant. The local Compose profile uses generated development credentials. See the [operator guide](docs/operator-guide/kubernetes.md).
