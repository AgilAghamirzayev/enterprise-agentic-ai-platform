# Evaluating agent versions

Publish a versioned dataset and suite, then run the target agent once per case through the ordinary runs API. The evaluator checks those persisted runs and records a report. Scoring consumes no model tokens. Dataset authors and tenant auditors can read the shared test fixtures, so use synthetic inputs.

## Dataset

Send this body to `POST /api/v1/evaluation-datasets`. Names use lowercase letters, digits and hyphens. Versions use major.minor.patch. A dataset contains 1 to 32 distinct case IDs.

```json
{
  "name": "support-regression",
  "version": "1.0.0",
  "cases": [{
    "id": "uppercase",
    "input": {"text": "receipt"},
    "expected": "RECEIPT",
    "schema": {"type": "string"},
    "tool": {"name": "uppercase", "arguments": {"text":"receipt"}}
  }]
}
```

Each case requires at least one assertion.

| Assertion | Evidence |
| --- | --- |
| `expected` | PostgreSQL JSON equality against the persisted result |
| `schema` | JSON Schema 2020-12 structural validation of the result |
| `tool` | A successful invocation with the specified name. Optional arguments must match its stored digest. |
| `citations` | A nonempty returned citation list whose document IDs, chunk IDs and digests all occur in the run's retrieval evidence. Every expected document UUID must appear. An empty expected array accepts any verified source. |

Supported schema keywords are `type`, `properties`, `required`, `additionalProperties` (boolean), `items`, `enum` and `const`. Nesting is limited to 12 levels. Verification of a citation establishes document provenance. Assessing whether an answer accurately interprets the cited source requires labeled examples or human review.

## Suite

Publish the target agent or workflow before sending this body to `POST /api/v1/evaluation-suites`.

```json
{
  "name": "support",
  "version": "1.0.0",
  "dataset": "support-regression:1.0.0",
  "target": {"kind": "agent", "name": "support", "version": "1.0.0"},
  "minPassRate": 1
}
```

## Report

Submit each case's input to the suite's exact target version using `POST /api/v1/runs`. Wait for terminal states, then send the mapping to `POST /api/v1/evaluation-reports`.

```json
{"suite":"support:1.0.0","runs":{"uppercase":"<run UUID>"}}
```

By default a case must complete successfully and pass every assertion. A case with an explicit expected status is assessed against that status. The API checks that each case has exactly one distinct run, matching input, target and version. Developers may score and view their own runs and reports. An additional auditor or admin role permits tenant-wide inspection.

For a regression comparison, publish another suite version referencing the same dataset, target name and kind. Its target version can change. Set `maxRegression` to the allowed absolute decrease in pass rate, between zero and one. Include the previous report's UUID as `baselineReportId` when scoring. Reports preserve the dataset, suite, target, case inputs by immutable reference, run IDs, checks and pass rate. Read a report with `GET /api/v1/evaluation-reports/{id}`.

## Grading safety, performance and cost

A case may set `expectedStatus` to a terminal status. A policy-denial test can require `DENIED` and use `forbiddenTools` to check successful invocations and unresolved external dispatches. `relevanceTerms` checks case-insensitive labels in a text response or its `answer` field. `maxDurationMillis` measures elapsed time from enqueue to terminal persistence. `maxTokens` uses reported input and output tokens. `maxCostUsd` checks costs derived from reported usage and configured rates. Unknown costs and outstanding token reservations fail their respective ceilings. Use load tests to measure concurrent throughput.

Set suite `thresholds` to per-check minimum pass rates. For example, `{"safety":1,"judge":0.9,"latency":0.95}` requires each named check to appear and meet its threshold. The report records per-case measurements and aggregate `checkRates`. A suite can apply both these gates and the overall pass-rate regression gate.

## Running a model judge

Publish a scorer agent using a model node and a version-pinned prompt which asks for a JSON object containing a numeric `score` from zero through one. Give the judge its own model, time and spend limits. For example, a dataset case can include this rule.

```json
{"judge":{"agent":"grader:1.0.0","rubric":"The answer gives the applicable receipt limit.","minScore":0.8}}
```

After the candidate finishes, start the exact scorer agent through the ordinary run API. Its input must contain precisely `candidateRunId`, `rubric`, `question` and `answer`. Copy the question from the case and the answer from the persisted candidate result. Wait for the scorer's terminal result. Submit `judgeRuns` alongside `runs` in the evaluation report request. Both maps use the case ID as their key. The report checks model-call evidence, scorer identity, immutable version, input equality and caller access. It stores the judge run UUID and score. Judge quality depends on the rubric, labeled examples and provider.
