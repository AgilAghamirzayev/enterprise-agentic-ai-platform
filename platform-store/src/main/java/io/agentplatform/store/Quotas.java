package io.agentplatform.store;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;

final class Quotas {
    private static final Pattern SCOPE =
            Pattern.compile("(?:agent|workflow):[A-Za-z0-9_.-]{1,128}");
    private final Database db;
    private final ModelPricing pricing;

    Quotas(Database db, ModelPricing pricing) {
        this.db = db;
        this.pricing = pricing;
    }

    private void lockTenant(String tenant) {
        db.jdbc.queryForList(
                "SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "usage/" + tenant);
    }

    JsonNode set(String tenant, String actor, String scope, Long tokens, BigDecimal usd) {
        if (!"*".equals(scope) && (scope == null || !SCOPE.matcher(scope).matches()))
            throw new StoreException(400, "Invalid usage quota scope");
        if (tokens != null && tokens < 0
                || usd != null
                        && (usd.signum() < 0
                                || usd.scale() > 12
                                || usd.precision() - usd.scale() > 18))
            throw new StoreException(400, "Invalid monthly quota");
        return db.scoped(
                tenant,
                () -> {
                    lockTenant(tenant);
                    JsonNode quota =
                            db.one(
                                    """
                                    INSERT INTO quotas(tenant,scope,monthly_tokens,monthly_usd,updated_by) VALUES (?,?,?,?,?)
                                    ON CONFLICT(tenant,scope) DO UPDATE SET monthly_tokens=excluded.monthly_tokens,monthly_usd=excluded.monthly_usd,
                                        updated_by=excluded.updated_by,updated_at=now()
                                    RETURNING scope,monthly_tokens,monthly_usd,updated_by,updated_at
                                    """,
                                    tenant,
                                    scope,
                                    tokens,
                                    usd,
                                    actor);
                    db.audit(tenant, actor, null, "quota.updated", scope);
                    return quota;
                });
    }

    List<JsonNode> list(String tenant) {
        return db.scoped(
                tenant,
                () ->
                        db.rows(
                                "SELECT scope,monthly_tokens,monthly_usd,updated_by,updated_at FROM"
                                        + " quotas WHERE tenant=? ORDER BY scope",
                                tenant));
    }

    void reserve(
            String tenant,
            String runId,
            String scope,
            String key,
            int inTokens,
            int outTokens,
            BigDecimal maxUsd,
            String modelId) {
        long tokens = (long) inTokens + outTokens;
        BigDecimal cost = pricing.estimate(inTokens, outTokens);
        if (maxUsd != null) {
            JsonNode previous =
                    db.one(
                            """
                            SELECT coalesce(sum(reserved_usd),0) AS cost, count(*) FILTER (WHERE reserved_usd IS NULL) AS unpriced
                            FROM model_attempts WHERE tenant=? AND run_id=?
                            """,
                            tenant,
                            Database.id(runId));
            if (cost == null
                    || previous.get("unpriced").asLong() != 0
                    || previous.get("cost").decimalValue().add(cost).compareTo(maxUsd) > 0)
                throw new StoreException(
                        429, "Run cost budget exhausted or model price unavailable");
        }
        lockTenant(tenant);
        if (tenant.indexOf('~')>=0 && !Boolean.TRUE.equals(db.jdbc.queryForObject(
                "SELECT reserve_parent_usage(?,?::bigint,?::numeric)",Boolean.class,tenant,tokens,cost))) {
            throw new StoreException(429,"Parent tenant monthly quota exhausted");
        }
        reservePeriod(tenant, "*", tokens, cost);
        reservePeriod(tenant, scope, tokens, cost);
        db.jdbc.update(
                """
                INSERT INTO model_attempts(tenant,run_id,step_key,reserved_tokens,input_price,output_price,reserved_usd,model_id)
                VALUES (?,?,?,?,?,?,?,?)
                """,
                tenant,
                Database.id(runId),
                key,
                tokens,
                pricing.inputPerMillion(),
                pricing.outputPerMillion(),
                cost,
                modelId);
    }

    private void reservePeriod(String tenant, String scope, long tokens, BigDecimal cost) {
        var usage =
                db.one(
                        """
                        INSERT INTO usage_periods(tenant,scope,period,tokens,reserved_usd,unpriced)
                        VALUES (?,?,date_trunc('month',current_timestamp AT TIME ZONE 'UTC')::date,?,?,?)
                        ON CONFLICT (tenant,scope,period) DO UPDATE SET tokens=usage_periods.tokens+excluded.tokens,
                           reserved_usd=usage_periods.reserved_usd+excluded.reserved_usd,unpriced=usage_periods.unpriced+excluded.unpriced
                        RETURNING tokens,reserved_usd,unpriced
                        """,
                        tenant,
                        scope,
                        tokens,
                        cost == null ? BigDecimal.ZERO : cost,
                        cost == null ? 1 : 0);
        var quota =
                db.first(
                        "SELECT monthly_tokens,monthly_usd FROM quotas WHERE tenant=? AND scope=?",
                        tenant,
                        scope);
        if (quota.isEmpty()) return;
        JsonNode maxTokens = quota.get().get("monthlyTokens"),
                maxCost = quota.get().get("monthlyUsd");
        if (maxTokens.isNumber() && usage.get("tokens").asLong() > maxTokens.asLong())
            throw new StoreException(429, "Monthly token quota exhausted");
        if (maxCost.isNumber()
                && (usage.get("unpriced").asLong() != 0
                        || usage.get("reservedUsd").decimalValue().compareTo(maxCost.decimalValue())
                                > 0))
            throw new StoreException(
                    429, "Monthly cost quota exhausted or model price unavailable");
    }

    void account(String tenant, String runId, String key, int inTokens, int outTokens) {
        var recorded=db.first(
                """
                UPDATE model_attempts SET input_tokens=?,output_tokens=?,estimated_usd=(input_price*?+output_price*?)/1000000
                WHERE tenant=? AND id=(SELECT id FROM model_attempts WHERE tenant=? AND run_id=? AND step_key=?
                                        AND input_tokens IS NULL ORDER BY id DESC LIMIT 1) RETURNING estimated_usd
                """,
                inTokens,
                outTokens,
                inTokens,
                outTokens,
                tenant,
                tenant,
                Database.id(runId),
                key);
        recorded.map(row->row.path("estimatedUsd")).filter(JsonNode::isNumber).ifPresent(usd->
            db.afterCommit(()->db.metrics.counter("model.estimated.cost.usd").increment(usd.doubleValue())));
    }

    JsonNode costs(String tenant) {
        return db.one(
                """
                SELECT CASE WHEN count(*) FILTER(WHERE reserved_usd IS NULL)=0 THEN coalesce(sum(reserved_usd),0) END AS reserved_cost_usd,
                  CASE WHEN count(*) FILTER(WHERE input_tokens IS NOT NULL AND estimated_usd IS NULL)=0
                       THEN coalesce(sum(estimated_usd),0) END AS estimated_cost_usd,
                  count(*) FILTER(WHERE input_tokens IS NULL) AS open_model_attempts
                FROM model_attempts WHERE tenant=?
                """,
                tenant);
    }
}
