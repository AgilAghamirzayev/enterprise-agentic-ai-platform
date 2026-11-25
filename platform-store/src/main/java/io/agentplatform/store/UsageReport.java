package io.agentplatform.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class UsageReport {
    private UsageReport() {}

    static JsonNode load(Database db, Quotas quotas, String tenant) {
        return db.scoped(
                tenant,
                () -> {
                    var usage =
                            (ObjectNode)
                                    db.one(
                                            """
                                            SELECT count(*) AS runs, count(*) FILTER (WHERE status='RUNNING') AS running,
                                                   count(*) FILTER (WHERE status IN ('COMPLETED','SUCCEEDED')) AS succeeded,
                                                   coalesce(sum(model_calls),0) AS model_calls, coalesce(sum(reserved_tokens),0) AS reserved_tokens,
                                                   (SELECT coalesce(sum(input_tokens),0) FROM run_steps WHERE tenant=?) AS input_tokens,
                                                   (SELECT coalesce(sum(output_tokens),0) FROM run_steps WHERE tenant=?) AS output_tokens
                                            FROM runs WHERE tenant=?
                                            """,
                                            tenant,
                                            tenant,
                                            tenant);
                    usage.setAll(
                            (ObjectNode)
                                    db.one(
                                            """
                                            SELECT round(100.0*count(*) FILTER (WHERE status IN ('COMPLETED','SUCCEEDED')) / nullif(count(*),0),1) AS success_percent_24h,
                                                   round((percentile_cont(0.95) WITHIN GROUP (ORDER BY extract(epoch FROM (updated_at-created_at))*1000))::numeric) AS latency_p95_ms_24h
                                            FROM runs WHERE tenant=? AND updated_at>=now()-interval '24 hours'
                                            AND status NOT IN ('QUEUED','RUNNING','WAITING_APPROVAL')
                                            """,
                                            tenant));
                    var buckets =
                            db.rows(
                                    """
                                    WITH hours AS (
                                        SELECT generate_series(date_trunc('hour',now()) - interval '23 hours',
                                                               date_trunc('hour',now()), interval '1 hour') AS bucket
                                    ), activity AS (
                                        SELECT date_trunc('hour',created_at) AS bucket, count(*) AS total,
                                               count(*) FILTER (WHERE status IN ('FAILED','TIMED_OUT','BUDGET_EXCEEDED','DENIED','EXPIRED')) AS failed
                                        FROM runs WHERE tenant=? AND created_at >= date_trunc('hour',now()) - interval '23 hours'
                                        GROUP BY 1
                                    )
                                    SELECT h.bucket AS started_at, coalesce(a.total,0) AS total, coalesce(a.failed,0) AS failed
                                    FROM hours h LEFT JOIN activity a USING(bucket) ORDER BY h.bucket
                                    """,
                                    tenant);
                    usage.set("hourly", db.mapper.valueToTree(buckets));
                    return usage.setAll((ObjectNode) quotas.costs(tenant));
                });
    }
}
