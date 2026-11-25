package io.agentplatform.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

final class EvaluationSuites {
    static final String DATASET = "evaluation-dataset";
    static final String SUITE = "evaluation-suite";
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,62}");
    private static final Pattern VERSION = Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+");
    private static final Set<String> FINISHED =
            Set.of(
                    "COMPLETED",
                    "SUCCEEDED",
                    "FAILED",
                    "DENIED",
                    "CANCELLED",
                    "TIMED_OUT",
                    "EXPIRED",
                    "BUDGET_EXCEEDED");
    private final Database db;
    private final Definitions definitions;

    EvaluationSuites(Database db, Definitions definitions) {
        this.db = db;
        this.definitions = definitions;
    }

    JsonNode publish(String tenant, String actor, String kind, JsonNode spec) {
        if (!spec.isObject() || spec.toString().length() > 131072)
            throw new StoreException(400, "Invalid evaluation specification");
        String name = spec.path("name").asText(), version = spec.path("version").asText();
        reference(name + ":" + version);
        if (DATASET.equals(kind)) validateDataset(spec);
        else if (SUITE.equals(kind)) {
            checkKeys(
                    spec,
                    Set.of("name", "version", "target", "dataset", "minPassRate", "maxRegression", "thresholds"));
            var target = spec.path("target");
            checkKeys(target, Set.of("kind", "name", "version"));
            if (!Set.of("agent", "workflow").contains(target.path("kind").asText()))
                throw new StoreException(400, "Unknown suite target kind");
            definitions.get(
                    tenant,
                    target.path("kind").asText(),
                    target.path("name").asText(),
                    target.path("version").asText(),
                    false);
            var data = reference(spec.path("dataset").asText());
            definitions.get(tenant, DATASET, data[0], data[1], false);
            rate(spec, "minPassRate", BigDecimal.ONE);
            rate(spec, "maxRegression", null);
            if(spec.has("thresholds")){
                checkKeys(spec.get("thresholds"),RunEvaluation.KEYS);
                spec.get("thresholds").fieldNames().forEachRemaining(key->rate(spec.get("thresholds"),key,null));
            }
        } else throw new IllegalArgumentException("Unknown evaluation definition kind");
        return definitions.save(tenant, actor, kind, name, version, spec, true);
    }

    private static void validateDataset(JsonNode spec) {
        checkKeys(spec, Set.of("name", "version", "cases"));
        JsonNode cases = spec.path("cases");
        if (!cases.isArray() || cases.isEmpty() || cases.size() > 32)
            throw new StoreException(400, "Dataset requires 1..32 cases");
        var ids = new HashSet<String>();
        for (JsonNode item : cases) {
            checkKeys(item, Set.of("id", "input", "expected", "schema", "tool", "citations","judge","relevanceTerms","maxDurationMillis","maxCostUsd","maxTokens","forbiddenTools","expectedStatus"));
            require(
                    NAME.matcher(item.path("id").asText()).matches()
                            && ids.add(item.path("id").asText()),
                    "Invalid or repeated case ID");
            require(
                    item.has("input") && item.size() > 2,
                    "Case needs input and at least one assertion");
            RunEvaluation.validate(item,FINISHED);
            if (item.has("schema")) EvaluationSchema.compile(item.get("schema"));
            if (item.has("tool")) {
                checkKeys(item.get("tool"), Set.of("name", "arguments"));
                Database.requireText(item.get("tool").path("name").textValue(), "tool name", 128);
            }
            if (item.has("citations")) {
                JsonNode refs = item.get("citations");
                require(refs.isArray() && refs.size() <= 32, "Expected citations must be an array");
                refs.forEach(ref -> Database.id(ref.textValue()));
            }
        }
    }

    JsonNode report(
            String tenant,
            String actor,
            boolean auditor,
            String suiteRef,
            JsonNode runIds,
            String baselineId,JsonNode judgeRuns) {
        String[] ref = reference(suiteRef);
        JsonNode suite = definitions.get(tenant, SUITE, ref[0], ref[1], false).required("spec");
        String datasetRef = suite.required("dataset").asText();
        String[] data = reference(datasetRef);
        JsonNode cases =
                definitions.get(tenant, DATASET, data[0], data[1], false).at("/spec/cases");
        require(
                runIds.isObject() && runIds.size() == cases.size(),
                "Supply one run ID for every case");
        var judges=judgeRuns==null?db.mapper.createObjectNode():judgeRuns;
        require(judges.isObject(),"Invalid judge case mapping");
        judges.fieldNames().forEachRemaining(k->require(runIds.has(k),"Unknown judge case ID"));
        var evidence=new RunEvaluation(db);
        return db.scoped(
                tenant,
                () -> {
                    ObjectNode report =
                            db.mapper
                                    .createObjectNode()
                                    .put("dataset", datasetRef)
                                    .put("suite", suiteRef);
                    report.set("target", suite.required("target"));
                    var scores = report.putArray("cases");
                    Set<UUID> seen = new HashSet<>();
                    int passed = 0;
                    for (var item : cases) {
                        String caseId = item.required("id").asText();
                        UUID runId = Database.id(runIds.path(caseId).textValue());
                        require(seen.add(runId), "A run may score only one case per report");
                        var run =
                                db.one(
                                        """
                                        SELECT id,kind,name,version,status,result,actor,ceil(extract(epoch FROM updated_at-created_at)*1000)::bigint AS duration_millis,input=?::jsonb AS input_matches
                                        FROM run_payloads WHERE tenant=? AND id=? AND (actor=? OR ?)
                                        """,
                                        Database.json(item.required("input")),
                                        tenant,
                                        runId,
                                        actor,
                                        auditor);
                        JsonNode target = suite.required("target");
                        for (String key : List.of("kind", "name", "version"))
                            require(
                                    run.get(key).equals(target.get(key)),
                                    "Run target differs from suite");
                        require(
                                run.get("inputMatches").asBoolean(),
                                "Run input differs from dataset");
                        if (!FINISHED.contains(run.get("status").asText()))
                            throw new StoreException(409, "Suite run is still active");
                        var score =
                                scores.addObject().put("id", caseId).put("runId", runId.toString());
                        var checks = score.putObject("checks");
                        boolean success =
                                Set.of("SUCCEEDED", "COMPLETED")
                                        .contains(run.get("status").asText());
                        if(!item.has("expectedStatus")) checks.put("completed",success);
                        if(success) evaluate(tenant,runId,item,run.required("result"),checks);
                        else {
                            for(var key : java.util.List.of("expected","schema","tool","citations"))
                                if(item.has(key)) checks.put(key.equals("expected")?"exact":key,false);
                        }
                        evidence.assess(tenant,actor,auditor,item,run,runId,judges.path(caseId),score,checks);
                        boolean ok =
                                java.util.stream.StreamSupport.stream(checks.spliterator(), false)
                                        .allMatch(JsonNode::asBoolean);
                        score.put("passed", ok);
                        if (ok) passed++;
                    }
                    BigDecimal ratio =
                            BigDecimal.valueOf(passed)
                                    .divide(
                                            BigDecimal.valueOf(cases.size()),
                                            12,
                                            RoundingMode.HALF_UP)
                                    .stripTrailingZeros();
                    BigDecimal minimum = rate(suite, "minPassRate", BigDecimal.ONE),
                            maxRegression = rate(suite, "maxRegression", null);
                    boolean metricPass=RunEvaluation.thresholds(suite,report);
                    boolean accepted = metricPass && ratio.compareTo(minimum) >= 0;
                    if (baselineId != null) {
                        var baseline =
                                read(tenant, actor, auditor, Database.id(baselineId))
                                        .required("report");
                        require(
                                datasetRef.equals(baseline.required("dataset").asText()),
                                "Baseline dataset differs");
                        for (String key : List.of("kind", "name"))
                            require(
                                    suite.path("target")
                                            .get(key)
                                            .equals(baseline.path("target").get(key)),
                                    "Baseline target differs");
                        var delta = ratio.subtract(baseline.required("passRate").decimalValue());
                        report.put("baselineId", baselineId).put("change", delta);
                        if (maxRegression != null && delta.negate().compareTo(maxRegression) > 0)
                            accepted = false;
                    } else if (maxRegression != null)
                        throw new StoreException(400, "This suite requires a baseline report");
                    report.put("passRate", ratio).put("passed", accepted);
                    UUID id = UUID.randomUUID();
                    var saved =
                            db.one(
                                    """
                                    INSERT INTO evaluation_reports(tenant,id,actor,suite,version,baseline_id,report) VALUES (?,?,?,?,?,?,?::jsonb)
                                    RETURNING id,actor,created_at,report
                                    """,
                                    tenant,
                                    id,
                                    actor,
                                    ref[0],
                                    ref[1],
                                    baselineId == null ? null : Database.id(baselineId),
                                    report.toString());
                    db.audit(tenant, actor, null, "evaluation.suite_completed", id.toString());
                    return saved;
                });
    }

    private void evaluate(
            String tenant, UUID runId, JsonNode item, JsonNode output, ObjectNode checks) {
        if (item.has("expected"))
            checks.put(
                    "exact",
                    db.jdbc.queryForObject(
                            "SELECT ?::jsonb=?::jsonb",
                            Boolean.class,
                            Database.json(item.get("expected")),
                            Database.json(output)));
        if (item.has("schema"))
            checks.put(
                    "schema",
                    EvaluationSchema.compile(item.get("schema")).validate(output).isEmpty());
        if (item.has("tool")) {
            JsonNode tool = item.required("tool");
            checks.put(
                    "tool",
                    db.first(
                                    """
                                    SELECT 1 FROM tool_evidence WHERE tenant=? AND run_id=? AND tool=? AND
                                    (? OR argument_hash=encode(sha256(convert_to((?::jsonb)::text,'UTF8')),'hex')) LIMIT 1
                                    """,
                                    tenant,
                                    runId,
                                    tool.required("name").asText(),
                                    !tool.has("arguments"),
                                    tool.path("arguments").isMissingNode()
                                            ? "null"
                                            : Database.json(tool.get("arguments")))
                            .isPresent());
        }
        if (item.has("citations"))
            checks.put(
                    "citations",
                    grounded(tenant, runId, item.required("citations"), output.path("citations")));
    }

    private boolean grounded(String tenant, UUID runId, JsonNode expected, JsonNode cited) {
        if (!cited.isArray() || cited.isEmpty() || cited.size() > 50) return false;
        Set<String> found = new HashSet<>();
        for (var citation : cited) {
            String doc = citation.path("documentId").asText(),
                    digest = citation.path("digest").asText();
            JsonNode chunk = citation.path("chunkId");
            if (!chunk.isIntegralNumber()
                    || !chunk.canConvertToInt()
                    || !digest.matches("[a-f0-9]{64}")) return false;
            UUID id;
            try {
                id = UUID.fromString(doc);
            } catch (IllegalArgumentException e) {
                return false;
            }
            if (!id.toString().equalsIgnoreCase(doc)) return false;
            found.add(doc);
        }
        for (var id : expected) if (!found.contains(id.asText())) return false;
        return Boolean.TRUE.equals(
                db.jdbc.queryForObject(
                        """
                        SELECT NOT EXISTS (
                          SELECT 1 FROM jsonb_array_elements(?::jsonb) AS c
                          WHERE NOT EXISTS (SELECT 1 FROM retrieval_evidence e WHERE e.tenant=? AND e.run_id=?
                            AND e.document_id=(c->>'documentId')::uuid AND e.chunk_id=(c->>'chunkId')::integer AND e.digest=c->>'digest'))
                        """,
                        Boolean.class,
                        Database.json(cited),
                        tenant,
                        runId));
    }

    JsonNode read(String tenant, String actor, boolean auditor, UUID id) {
        return db.one(
                "SELECT id,actor,report,created_at FROM evaluation_reports WHERE tenant=? AND id=?"
                    + " AND (actor=? OR ?)",
                tenant,
                id,
                actor,
                auditor);
    }

    List<JsonNode> list(String tenant, String actor, boolean auditor) {
        return db.scoped(
                tenant,
                () ->
                        db.rows(
                                "SELECT id,actor,report,created_at FROM evaluation_reports WHERE"
                                    + " tenant=? AND (actor=? OR ?) ORDER BY created_at DESC LIMIT"
                                    + " 100",
                                tenant,
                                actor,
                                auditor));
    }

    private static String[] reference(String value) {
        String[] parts = value.split(":", -1);
        require(
                parts.length == 2
                        && NAME.matcher(parts[0]).matches()
                        && VERSION.matcher(parts[1]).matches(),
                "Expected name:major.minor.patch");
        return parts;
    }

    private static BigDecimal rate(JsonNode spec, String key, BigDecimal fallback) {
        if (!spec.has(key)) return fallback;
        var value = spec.required(key);
        require(
                value.isNumber()
                        && value.decimalValue().signum() >= 0
                        && value.decimalValue().compareTo(BigDecimal.ONE) <= 0,
                "Threshold must be between zero and one");
        return value.decimalValue();
    }

    private static void checkKeys(JsonNode node, Set<String> keys) {
        require(node.isObject(), "Expected an object");
        node.fieldNames()
                .forEachRemaining(key -> require(keys.contains(key), "Unknown evaluation field"));
    }

    private static void require(boolean ok, String problem) {
        if (!ok) throw new StoreException(400, problem);
    }
}
