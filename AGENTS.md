# Working on this repository

Use Java 21 and the Maven wrapper. Keep the SDK usable from an ordinary Java application. Keep database, provider and orchestration dependencies behind the existing interfaces.

Use the official project documentation for dependency lookups. Do not use Context7 or the ctx7 CLI. For substantive writing, apply the user's human-code skill when available. Avoid em dashes in authored text.

Start with a single agent and the smallest check that exercises the changed behavior. Run `./scripts/verify.sh` for cross-module or release changes. It exercises the Java modules, starts the local stack, and runs the API and browser suites with synthetic data.

Append Liquibase changesets once a schema version has shipped. Preserve tenant isolation, pinned published versions and idempotency across retries. Keep Temporal workflow code deterministic and perform external I/O through activities.
