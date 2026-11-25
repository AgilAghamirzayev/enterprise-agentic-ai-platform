package io.agentplatform.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

final class RunEvaluation {
    static final Set<String> KEYS=Set.of("exact","schema","tool","citations","completed","status","safety","latency","tokens","cost","relevance","judge");
    private final Database db;
    RunEvaluation(Database db){this.db=db;}

    static void validate(JsonNode item,Set<String> terminal) {
        if(item.has("expectedStatus")&&!terminal.contains(item.path("expectedStatus").asText())) invalid("Invalid expected status");
        if(item.has("forbiddenTools")) labels(item.get("forbiddenTools"),"forbidden tools");
        if(item.has("relevanceTerms")) labels(item.get("relevanceTerms"),"relevance labels");
        for(String key:Set.of("maxDurationMillis","maxTokens","maxCostUsd")) if(item.has(key)) {
            JsonNode n=item.get(key);
            if(!n.isNumber() || n.decimalValue().signum()<0 || !key.equals("maxCostUsd") && (!n.isIntegralNumber()||!n.canConvertToLong()))
                invalid("Invalid evaluation ceiling");
        }
        if(item.has("judge")) {
            JsonNode judge=item.get("judge");
            if(!judge.isObject()||judge.size()!=3||!judge.path("agent").asText().matches("[a-z][a-z0-9-]{0,62}:[0-9]+\\.[0-9]+\\.[0-9]+"))
                invalid("Judge requires an immutable agent version, rubric and minimum score");
            Database.requireText(judge.path("rubric").textValue(),"judge rubric",2048);
            JsonNode minimum=judge.path("minScore");
            if(!minimum.isNumber()||minimum.decimalValue().signum()<0||minimum.decimalValue().compareTo(BigDecimal.ONE)>0) invalid("Judge score must be in [0,1]");
        }
    }
    private static void labels(JsonNode data,String name){
        if(!data.isArray()||data.isEmpty()||data.size()>32) invalid("Invalid "+name);
        Set<String> ids=new HashSet<>();
        data.forEach(value->{if(!ids.add(Database.requireText(value.textValue(),name,128))) invalid("Duplicate "+name);});
    }
    private static void invalid(String msg){throw new StoreException(400,msg);}

    void assess(String tenant,String actor,boolean auditor,JsonNode item,JsonNode run,UUID runId,
            JsonNode judgeRef,ObjectNode score,ObjectNode checks) {
        ObjectNode measurements=score.putObject("measurements");
        if(item.has("expectedStatus")) checks.put("status",run.path("status").equals(item.get("expectedStatus")));
        if(item.has("forbiddenTools")) {
            boolean safe=db.first("""
                SELECT 1 FROM (SELECT tool FROM tool_evidence WHERE tenant=? AND run_id=?
                  UNION ALL SELECT tool FROM external_effects WHERE tenant=? AND run_id=? AND outcome<>'NO_EFFECT') invocations
                  WHERE tool IN (SELECT jsonb_array_elements_text(?::jsonb)) LIMIT 1
                """,tenant,runId,tenant,runId,item.get("forbiddenTools").toString()).isEmpty();
            checks.put("safety",safe);
        }
        if(item.has("maxDurationMillis")) {
            long ms=run.get("durationMillis").asLong();
            measurements.put("durationMillis",ms);
            checks.put("latency",ms<=item.get("maxDurationMillis").longValue());
        }
        if(item.has("maxTokens")||item.has("maxCostUsd")){
            var usage=db.one("""
                SELECT coalesce(sum(input_tokens::bigint+output_tokens),0) AS tokens,
                 coalesce(sum(estimated_usd),0) AS cost,
                 count(*) FILTER(WHERE input_tokens IS NULL) AS open,
                 count(*) FILTER(WHERE estimated_usd IS NULL) AS unpriced
                FROM model_attempts WHERE tenant=? AND run_id=?
                """,tenant,runId);
            if(item.has("maxTokens")){
                measurements.set("reportedTokens",usage.get("tokens"));
                checks.put("tokens",usage.get("open").asLong()==0 && usage.get("tokens").decimalValue().compareTo(item.get("maxTokens").decimalValue())<=0);
            }
            if(item.has("maxCostUsd")){
                boolean known=usage.get("unpriced").asLong()==0;
                if(known) measurements.set("estimatedCostUsd",usage.get("cost")); else measurements.putNull("estimatedCostUsd");
                checks.put("cost",known&&usage.get("cost").decimalValue().compareTo(item.get("maxCostUsd").decimalValue())<=0);
            }
        }
        if(item.has("relevanceTerms")){
            JsonNode output=run.path("result");
            String text=(output.isTextual()?output.asText():output.path("answer").asText()).toLowerCase(Locale.ROOT);
            boolean relevant=true;
            for(var term:item.get("relevanceTerms")) if(!text.contains(term.asText().toLowerCase(Locale.ROOT))) relevant=false;
            checks.put("relevance",relevant);
        }
        if(item.has("judge")){
            UUID judgeId=Database.id(judgeRef.textValue());
            if(judgeId.equals(runId)) invalid("Judge and candidate must be distinct runs");
            JsonNode rule=item.get("judge");
            var requiredInput=db.mapper.createObjectNode().put("candidateRunId",runId.toString()).put("rubric",rule.required("rubric").asText());
            requiredInput.set("question",item.get("input"));
            requiredInput.set("answer",run.get("result"));
            String[] agent=rule.get("agent").asText().split(":");
            var witness=db.first("""
                SELECT result FROM run_payloads r WHERE tenant=? AND id=? AND (actor=? OR ?) AND status IN ('SUCCEEDED','COMPLETED')
                AND kind='agent' AND name=? AND version=? AND input=?::jsonb
                AND EXISTS (SELECT 1 FROM model_attempts m WHERE m.tenant=r.tenant AND m.run_id=r.id AND m.input_tokens IS NOT NULL)
                """,tenant,judgeId,actor,auditor,agent[0],agent[1],requiredInput.toString());
            if(witness.isEmpty()) throw new StoreException(409,"A completed judge run on the exact candidate and rubric is required");
            JsonNode output=witness.get().path("result");
            if(output.isTextual()){
                try {output=db.mapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(output.textValue());}
                catch(java.io.IOException invalidJson){output=db.mapper.nullNode();}
            }
            JsonNode value=output.path("score");
            boolean valid=value.isNumber()&&value.decimalValue().signum()>=0&&value.decimalValue().compareTo(BigDecimal.ONE)<=0;
            measurements.put("judgeRunId",judgeId.toString());
            if(valid) measurements.set("judgeScore",value);
            checks.put("judge",valid&&value.decimalValue().compareTo(rule.get("minScore").decimalValue())>=0);
        } else if(!judgeRef.isMissingNode()) invalid("Unexpected judge run");
    }

    static boolean thresholds(JsonNode suite,ObjectNode report){
        var totals=new HashMap<String,Integer>();
        var passed=new HashMap<String,Integer>();
        for(var item:report.path("cases")){
            item.path("checks").fields().forEachRemaining(entry->{
                totals.merge(entry.getKey(),1,Integer::sum);
                if(entry.getValue().asBoolean()) passed.merge(entry.getKey(),1,Integer::sum);
            });
        }
        ObjectNode rates=report.putObject("checkRates");
        totals.forEach((k,n)->rates.put(k,BigDecimal.valueOf(passed.getOrDefault(k,0)).divide(BigDecimal.valueOf(n),12,java.math.RoundingMode.HALF_UP)));
        var criteria=suite.path("thresholds").fields();
        while(criteria.hasNext()){
            var check=criteria.next();
            if(!rates.has(check.getKey())||rates.get(check.getKey()).decimalValue().compareTo(check.getValue().decimalValue())<0) return false;
        }
        return true;
    }
}
