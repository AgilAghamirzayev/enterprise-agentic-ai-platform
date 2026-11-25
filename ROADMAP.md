# Roadmap

The current development version is 0.1.0. The project targets teams running trusted agents with a durable execution history, tenant boundaries and explicit approval for external effects.

| Milestone | Acceptance criteria |
| --- | --- |
| First public release | Review the dependency inventories, publish signed SDK coordinates and immutable image digests, and reproduce an installation from the published chart. |
| Production qualification | Measure API and provider latency, queue backlogs and recovery time under representative load. Restore PostgreSQL and Temporal backups into an isolated environment and verify the reconstructed run history. |
| Provider and connector coverage | Add each integration with a bounded request contract, a credential boundary, deterministic test fixtures and an opt-in qualification against its actual service. |
| SDK compatibility | Exercise supported Java and Spring versions against releases. Keep historical workflow replay and external-consumer compilation in release checks. |

A milestone is complete after its acceptance checks pass. Propose a change through the process in [CONTRIBUTING.md](CONTRIBUTING.md).
