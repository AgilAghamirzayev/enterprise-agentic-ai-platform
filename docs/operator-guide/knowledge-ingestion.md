# Knowledge ingestion

Use the asynchronous API for updates to a stable document key. A developer posts to `/api/v1/knowledge-bases/{base}/ingestions` with an `Idempotency-Key` header and a JSON body.

```json
{
  "documentKey": "expense-policy",
  "title": "Expense Policy",
  "content": "Attach receipts to travel expense claims.",
  "roles": ["finance"]
}
```

A 202 response includes an `id`, `revision` and `status`. Poll `GET /api/v1/knowledge-bases/{base}/ingestions/{id}` as the submitting user or a tenant auditor. The original publisher and tenant administrators can submit revisions to an existing key. Identical submissions with the same actor and idempotency key reuse the job.

PostgreSQL stores the raw text by tenant and SHA-256 digest. The submission transaction allocates the next document revision and writes a dispatch record. Temporal carries the tenant, job identifier and retry generation. The worker reads the artifact, embeds chunks, then commits the version and the active search pointer in one transaction. The old version stays searchable until the new version commits. A late worker can finish an older revision without moving the pointer backwards. Search applies the active version's role filter before ranking. Historical run citations keep their original document UUID and digest.

A worker heartbeats progress while embedding. A failed attempt can repeat embedding requests. The workflow allows three attempts. Once the job reports `FAILED`, correct the provider or configuration and call `POST /api/v1/knowledge-bases/{base}/ingestions/{id}/retry`. This reuses the revision and dispatches a new generation. A late activity from an earlier generation cannot commit data. Repeated chargeable embedding requests are possible after a provider or worker failure.

The synchronous `POST /api/v1/knowledge-bases/documents` endpoint returns an indexed document immediately. Its returned `documentKey` can be used for subsequent asynchronous revisions. The synchronous route accepts up to 65,536 UTF-16 code units. The asynchronous route accepts up to 262,144.

Use `PLATFORM_EMBEDDING_MODE=learned` with a configured Spring AI embedding provider and the same `PLATFORM_EMBEDDING_REVISION` on the server and worker. Use a new revision identifier for model or dimensionality changes and reindex the relevant documents. The default `lexical` mode runs offline. Search combines pgvector cosine similarity with full-text rank over the active, authorized chunks. Benchmark large corpora with the production embedding model and tenant distribution before choosing approximate vector indexes.

## Classification, retirement and memory

The optional `sourceUri` identifies the original document. Accepted URI schemes are `https`, `s3` and `internal`. Credentials, queries and fragments are rejected. Ingestion reads the submitted content. The URI is citation metadata. Without a URI the API returns `internal://documents/{id}`.

Use `PUBLIC`, `INTERNAL`, `CONFIDENTIAL` or `RESTRICTED` as the optional `classification`. Omitted classifications default to `INTERNAL` for an empty role list and `CONFIDENTIAL` for a restricted list. Confidential and restricted documents require an access role. The tenant and workspace boundary applies at every classification level. The API and generated model citations retain the document UUID, chunk, source, classification, revision and content digest.

The publisher or an administrator can call `POST /api/v1/knowledge-bases/{base}/documents/{key}/retire`. All versions allocated through this request disappear from search, including pending jobs that finish later. Publish a newer version to resume retrieval. Previously produced run outputs remain available to their authorized readers.

Conversation memory takes recent messages from the actor's session and agent. Episodic memory searches earlier messages in that scope using PostgreSQL full-text ranking. Both exclude archived origins, lost role grants, retired documents and superseded revisions. Source dependencies follow subsequent turns. Semantic retrieval uses the configured knowledge provider.

## Deploying another search index

Register one Spring bean implementing `io.agentplatform.spi.KnowledgeProvider` in both server and worker. The default provider uses PostgreSQL text search and pgvector. Give a custom provider a stable index revision. Its `index` method receives immutable chunk ordinals and source metadata. External services upsert by tenant, document UUID and chunk ordinal, report progress and return `IndexResult.managed()`. Their `search` method returns document UUIDs, chunk ordinals and finite scores. The store checks active revision, workspace, base, ACL and index revision again, then reads the canonical text from PostgreSQL.

Implement `retire` as an idempotent watermark on the document key. Preserve newer revisions and honor the watermark even when an older index job finishes later. PostgreSQL commits the visibility change first. Repeat the retirement endpoint if the external index is unavailable. The operator owns transport, destination credentials and deployment of custom indexes such as OpenSearch or Qdrant.
