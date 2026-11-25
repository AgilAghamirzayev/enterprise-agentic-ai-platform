# Contributing

For a bug, [open an issue](https://github.com/AgilAghamirzayev/enterprise-agentic-ai-platform/issues/new/choose) with a minimal reproduction. For a change, fork the repository, create a branch from `main` and open a pull request against `main`. Discuss changes to the public API, workflow history or database schema in an issue before implementing them.

Use Java 21 and the checked-in Maven wrapper. Run `./mvnw verify` with Docker available. For Studio changes, run `npm ci` and `npm run build` in `platform-studio`, then exercise the browser suite. Include a failing-case test when changing tenant filters, approval binding, provider budgets or retry behavior.

Add each PostgreSQL schema change as a new Liquibase change set. Keep previously published definitions readable. When changing Temporal workflow decisions, replay the checked-in history fixture and exercise recovery with a replacement worker. Update the public plan reference or SDK guide when changing contracts.

Include the affected behavior, compatibility impact and test results in a proposed change. Use synthetic documents, tenant names and credentials in fixtures.

With Python 3 and Docker Compose available, run `./scripts/verify.sh` for cross-module changes. For a change to trace propagation, run `./scripts/verify-langfuse.sh` as well. Follow the [security policy](SECURITY.md) for private reports.
