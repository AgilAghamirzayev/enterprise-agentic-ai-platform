package io.agentplatform.store;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import liquibase.integration.spring.SpringLiquibase;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import javax.sql.DataSource;

class PlatformStoreTest {
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:pg17")
                            .asCompatibleSubstituteFor("postgres"));
    private static final ObjectMapper JSON = new ObjectMapper();
    private static DataSource source;
    private static PlatformStore store;

    @BeforeAll
    static void startDatabase() throws Exception {
        POSTGRES.start();
        var admin = connection(POSTGRES.getUsername());
        var jdbc = new JdbcTemplate(admin);
        jdbc.execute("CREATE ROLE agent_runtime LOGIN NOSUPERUSER NOBYPASSRLS");
        // The disposable database already uses the generated container password.
        jdbc.execute(
                "ALTER ROLE agent_runtime PASSWORD '"
                        + POSTGRES.getPassword().replace("'", "''")
                        + "'");
        var migrations = new SpringLiquibase();
        migrations.setDataSource(admin);
        migrations.setChangeLog("classpath:db/changelog/db.changelog-master.yaml");
        migrations.afterPropertiesSet();
        source = connection("agent_runtime");
        store = new PlatformStore(source, JSON);
    }

    static PGSimpleDataSource connection(String user) {
        var result = new PGSimpleDataSource();
        result.setUrl(POSTGRES.getJdbcUrl());
        result.setUser(user);
        result.setPassword(POSTGRES.getPassword());
        return result;
    }

    @AfterAll
    static void stopDatabase() {
        POSTGRES.stop();
    }

    private String tenant() {
        return UUID.randomUUID().toString();
    }

    private JsonNode input(String text) {
        return JSON.createObjectNode().put("text", text);
    }

    private String start(String tenant) {
        store.publish(tenant, "author", "agent", "demo", "1", input("plan"));
        return store.startRun(
                        tenant,
                        "runner",
                        List.of("operator"),
                        "key",
                        "agent",
                        "demo",
                        "1",
                        input("hello"),
                        "session")
                .path("runId")
                .asText();
    }

    @Test
    void persistsTraceParentFromFirstAcceptedInvocationOnly() {
        String tenant = tenant();
        var origin = "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01";
        String run;
        try (var scope = RunTraceContext.restore(origin)) {
            run = start(tenant);
        }
        var row = store.run(tenant, run);
        assertEquals(origin, row.path("traceparent").asText());
        try (var ignored = RunTraceContext.restore("untrusted header")) {
            assertNull(RunTraceContext.current());
        }
        try (var scope = RunTraceContext.restore("00-fedcba9876543210fedcba9876543210-fedcba9876543210-00")) {
            var retry = store.startRun(tenant, "runner", List.of("operator"), "key", "agent", "demo", "1", input("hello"), "session");
            assertEquals(origin, retry.path("traceparent").asText());
        }
    }

    @Test
    void retryAndPublicationAreAtomicUnderConcurrency() throws Exception {
        String tenant = tenant();
        store.upsertDraft(tenant, "author", "agent", "demo", "1", input("draft"));
        assertThrows(
                StoreException.class,
                () -> store.publish(tenant, "author", "agent", "demo", "1", input("changed")));
        store.publish(tenant, "author", "agent", "demo", "1", input("draft"));
        assertEquals(
                404,
                assertThrows(StoreException.class, () -> store.draft(tenant, "agent", "demo", "1"))
                        .status());
        assertEquals(
                409,
                assertThrows(
                                StoreException.class,
                                () ->
                                        store.publish(
                                                tenant,
                                                "author",
                                                "agent",
                                                "demo",
                                                "1",
                                                input("changed")))
                        .status());
        List<Callable<String>> calls = new ArrayList<>();
        for (int i = 0; i < 12; i++)
            calls.add(
                    () ->
                            store.startRun(
                                            tenant,
                                            "runner",
                                            "key",
                                            "agent",
                                            "demo",
                                            "1",
                                            input("hello"))
                                    .get("id")
                                    .asText());
        try (var executor = Executors.newFixedThreadPool(6)) {
            var futures = executor.invokeAll(calls);
            String id = futures.getFirst().get();
            for (var future : futures) assertEquals(id, future.get());
            assertEquals(
                    1,
                    store.pendingStarts(100).stream()
                            .filter(c -> c.tenant().equals(tenant))
                            .count());
            store.acknowledgeStart(tenant, id);
            assertTrue(store.pendingStarts(100).stream().noneMatch(c -> c.tenant().equals(tenant)));
        }
        assertThrows(
                StoreException.class,
                () ->
                        store.startRun(
                                tenant, "runner", "key", "agent", "demo", "1", input("different")));
    }

    @Test
    void tenantAndDocumentAclAreAppliedBeforeRanking() {
        String tenant = tenant(), other = tenant();
        store.ingest(tenant, "author", "docs", "Public", "Travel requires a receipt.", List.of());
        store.ingest(
                tenant,
                "author",
                "docs",
                "Restricted",
                "Travel budget is confidential.",
                List.of("finance"));
        store.ingest(other, "author", "docs", "Other tenant", "Travel claim rules.", List.of());
        var results = store.search(tenant, "docs", "travel", List.of("operator"), 10);
        assertEquals(
                List.of("Public"), results.stream().map(x -> x.path("title").asText()).toList());
        assertEquals(2, store.search(tenant, "docs", "travel", List.of("finance"), 10).size());
        assertEquals(1, store.documents(other, List.of("finance")).size());
    }

    @Test
    void pooledConnectionForgetsTenantAndCannotRewritePublishedOrAuditRecords() throws Exception {
        String tenant = tenant(), other = tenant();
        try (var shared = new SingleConnectionDataSource(source.getConnection(), true)) {
            var sameConnection = new PlatformStore(shared, JSON);
            sameConnection.publish(tenant, "actor", "agent", "private", "1", input("secret"));
            sameConnection.publish(other, "actor", "agent", "other", "1", input("other"));
            assertEquals(
                    "other",
                    sameConnection.definitions(other, "agent").getFirst().get("name").asText());
            var db = new JdbcTemplate(shared);
            assertEquals(0, db.queryForObject("SELECT count(*) FROM definitions", Integer.class));
            assertThrows(
                    DataAccessException.class,
                    () -> db.update("UPDATE definitions SET spec='{}'::jsonb"));
            assertThrows(
                    DataAccessException.class,
                    () -> db.update("UPDATE audit_events SET actor='modified'"));
        }
    }

    @Test
    void ticketRequiresExactCurrentApprovalAndConcurrentRetriesReturnOneEffect() throws Exception {
        String tenant = tenant(), run = start(tenant);
        store.begin(tenant, run);
        var args = input("invoice discrepancy");
        var approval =
                store.requestApproval(tenant, run, "review", "create-ticket", args, "reviewer", 60);
        String id = approval.get("id").asText(), hash = approval.get("actionHash").asText();
        assertThrows(
                StoreException.class,
                () -> store.applyTicket(tenant, run, "review", args, "reviewer", 60));
        assertEquals(
                403,
                assertThrows(
                                StoreException.class,
                                () ->
                                        store.decide(
                                                tenant,
                                                "user",
                                                List.of("operator"),
                                                id,
                                                hash,
                                                true))
                        .status());
        assertThrows(
                StoreException.class,
                () -> store.decide(tenant, "user", List.of("reviewer"), id, "wrong", true));
        store.decide(tenant, "user", List.of("reviewer"), id, hash, true);
        assertThrows(
                StoreException.class,
                () -> store.applyTicket(tenant, run, "review", input("changed"), "reviewer", 60));
        assertThrows(IllegalArgumentException.class,()->store.applyTicket(tenant,run,"review",args,"reviewer",60,output->{throw new IllegalArgumentException("Schema failed");}));
        assertTrue(store.step(tenant,run,"review").isEmpty());
        try (var executor = Executors.newFixedThreadPool(3)) {
            Callable<JsonNode> effect =
                    () -> store.applyTicket(tenant, run, "review", args, "reviewer", 60);
            var futures = executor.invokeAll(List.of(effect, effect, effect));
            assertEquals(futures.getFirst().get(), futures.getLast().get());
        }
        assertEquals(1, store.steps(tenant, run).size());
        assertEquals(
                1,
                store.audit(tenant).stream()
                        .filter(x -> "ticket.created".equals(x.get("eventType").asText()))
                        .count());
    }

    @Test
    void cancellationBudgetAndConversationScopeAreEnforced() {
        String tenant = tenant(), run = start(tenant);
        store.begin(tenant, run);
        store.reserveModel(tenant, run, "infer", 7, 10, 2);
        assertEquals(
                429,
                assertThrows(
                                StoreException.class,
                                () -> store.reserveModel(tenant, run, "retry", 7, 10, 2))
                        .status());
        store.reserveTool(tenant, run, "lookup", 1);
        store.reserveTool(tenant, run, "lookup", 1);
        assertThrows(StoreException.class, () -> store.reserveTool(tenant, run, "other", 1));
        assertTrue(store.messagesForRun(tenant, run, 10).isEmpty());
        store.cancel(tenant, "runner", run);
        assertThrows(
                StoreException.class, () -> store.saveStep(tenant, run, "late", input("no"), 0, 0));
        store.finish(tenant, run, "COMPLETED", input("late"));
        assertEquals("CANCELLED", store.run(tenant, run).get("status").asText());
    }

    @Test
    void changedOrExpiredApprovalCannotAuthorizeAWrite() {
        String tenant = tenant(), run = start(tenant);
        store.begin(tenant, run);
        JsonNode args = input("change");
        var original =
                store.requestApproval(tenant, run, "write", "create-ticket", args, "approver", 60);
        var updated =
                store.setToolPolicy(
                        tenant, "admin", "create-ticket", "REQUIRE_APPROVAL", "manager");
        assertEquals(1, updated.get("revision").asLong());
        assertThrows(
                StoreException.class,
                () ->
                        store.decide(
                                tenant,
                                "reviewer",
                                List.of("manager", "approver"),
                                original.get("id").asText(),
                                original.get("actionHash").asText(),
                                true));
        var approval =
                store.requestApproval(
                        tenant, run, "write-2", "create-ticket", args, "approver", 60);
        assertEquals("manager", approval.get("requiredRole").asText());
        store.decide(
                tenant,
                "reviewer",
                List.of("manager"),
                approval.get("id").asText(),
                approval.get("actionHash").asText(),
                true);
        var admin = new JdbcTemplate(connection(POSTGRES.getUsername()));
        admin.update(
                "UPDATE approvals SET expires_at=clock_timestamp()-interval '1 second' WHERE"
                        + " tenant=? AND run_id=?",
                tenant,
                UUID.fromString(run));
        assertEquals(
                "EXPIRED",
                store.requestApproval(tenant, run, "write-2", "create-ticket", args, "approver", 60)
                        .get("status")
                        .asText());
        assertThrows(
                StoreException.class,
                () -> store.applyTicket(tenant, run, "write-2", args, "approver", 60));
        store.setToolPolicy(tenant, "admin", "create-ticket", "DENY", "manager");
        assertEquals(
                403,
                assertThrows(
                                StoreException.class,
                                () ->
                                        store.requestApproval(
                                                tenant,
                                                run,
                                                "write-3",
                                                "create-ticket",
                                                args,
                                                "manager",
                                                60))
                        .status());
        assertTrue(store.steps(tenant, run).isEmpty());
    }

    @Test
    void semanticRetrievalHonorsAclAndEmbeddingRevision() {
        String tenant = tenant();
        var semantic =
                new PlatformStore(
                        source,
                        JSON,
                        new Vectorizer() {
                            public String model() {
                                return "test-vectors-v2";
                            }

                            public float[] embed(String text) {
                                return text.contains("feline") || text.contains("cat")
                                        ? new float[] {1, 0}
                                        : new float[] {0, 1};
                            }
                        });
        store.ingest(tenant, "author", "docs", "Old revision", "cat", List.of());
        semantic.ingest(tenant, "author", "docs", "Animal", "cat", List.of("research"));
        semantic.ingest(tenant, "author", "docs", "Vehicle", "bus", List.of());
        assertTrue(semantic.search(tenant, "docs", "feline", List.of(), 3).isEmpty());
        var visible = semantic.search(tenant, "docs", "feline", List.of("research"), 3);
        assertEquals(
                List.of("Animal"), visible.stream().map(row -> row.get("title").asText()).toList());
    }

    @Test
    void successfulRunRecordsHistoryAndResultOnce() {
        String tenant = tenant(), run = start(tenant);
        store.begin(tenant, run);
        store.finish(tenant, run, "SUCCEEDED", input("answer"));
        store.finish(tenant, run, "FAILED", input("late"));
        var next = nextRun(tenant, "runner", List.of("operator"));
        var messages = store.messagesForRun(tenant, next, 10);
        assertEquals(
                List.of("user", "assistant"),
                messages.stream().map(row -> row.get("role").asText()).toList());
        assertTrue(
                store.recordEvaluation(tenant, "grader", run, input("answer"))
                        .get("passed")
                        .asBoolean());
        assertEquals(1, store.evaluations(tenant).size());
    }

    @Test
    void monthlyQuotasSerializeReservationsAcrossRunsAndUseProviderUsageForEstimates()
            throws Exception {
        String tenant = tenant(), first = start(tenant);
        var priced =
                new PlatformStore(
                        source,
                        JSON,
                        new TokenHashVectorizer(),
                        new ModelPricing(BigDecimal.ONE, BigDecimal.TEN));
        String second =
                priced.startRun(tenant, "runner", "another", "agent", "demo", "1", input("request"))
                        .get("id")
                        .asText();
        priced.begin(tenant, first);
        priced.begin(tenant, second);
        priced.setQuota(tenant, "admin", "*", 100L, new BigDecimal("0.000105"));
        priced.setQuota(tenant, "admin", "agent:demo", 10L, null);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Callable<Boolean>> actions =
                    List.of(first, second).stream()
                            .map(
                                    id ->
                                            (Callable<Boolean>)
                                                    () -> {
                                                        try {
                                                            priced.reserveModel(
                                                                    tenant, id, "model", 5, 5, 100,
                                                                    10, null);
                                                            priced.saveStep(
                                                                    tenant,
                                                                    id,
                                                                    "model",
                                                                    input("output"),
                                                                    3,
                                                                    2);
                                                            return true;
                                                        } catch (StoreException e) {
                                                            assertEquals(429, e.status());
                                                            return false;
                                                        }
                                                    })
                            .toList();
            var results = executor.invokeAll(actions);
            assertNotEquals(results.getFirst().get(), results.getLast().get());
        }
        assertEquals(1, priced.usage(tenant).get("modelCalls").asInt());
        assertEquals(
                0,
                priced.usage(tenant)
                        .get("estimatedCostUsd")
                        .decimalValue()
                        .compareTo(new BigDecimal("0.000023")));
        assertEquals(
                0,
                priced.usage(tenant)
                        .get("reservedCostUsd")
                        .decimalValue()
                        .compareTo(new BigDecimal("0.000055")));
        priced.setQuota(tenant, "admin", "agent:demo", 100L, null);
        assertThrows(
                StoreException.class,
                () -> priced.reserveModel(tenant, first, "another", 5, 5, 100, 10, null));
        assertThrows(
                StoreException.class,
                () ->
                        priced.reserveModel(
                                tenant, first, "run-cap", 0, 1, 100, 10, BigDecimal.ZERO));
        assertThrows(
                StoreException.class,
                () -> store.reserveModel(tenant, first, "unpriced", 0, 1, 100, 10, null));
        assertEquals(1, priced.usage(tenant).get("modelCalls").asInt());
    }

    @Test
    void suiteScoresObservedToolsAndCitationsAndComparesToARecordedBaseline() throws Exception {
        String tenant = tenant(), actor = "developer";
        store.publish(tenant, actor, "agent", "research", "1.0.0", input("plan"));
        var dataset =
                JSON.readTree(
                        """
                        {"name":"research-cases","version":"1.0.0","cases":[{"id":"travel",
                         "input":"travel","schema":{"type":"object","required":["answer","citations"],
                         "properties":{"answer":{"type":"string"},"citations":{"type":"array"}}},
                         "tool":{"name":"uppercase","arguments":{"text":"travel"}},"citations":[]}]}
                        """);
        store.publishEvaluation(tenant, actor, "evaluation-dataset", dataset);
        var suite =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        JSON.readTree(
                                """
                                {"name":"smoke","version":"1.0.0","target":{"kind":"agent","name":"research","version":"1.0.0"},
                                 "dataset":"research-cases:1.0.0","minPassRate":1}
                                """);
        store.publishEvaluation(tenant, actor, "evaluation-suite", suite);
        store.ingest(tenant, actor, "manual", "Travel", "Travel requires approval.", List.of());
        JsonNode question = JSON.getNodeFactory().textNode("travel");
        String first =
                store.startRun(tenant, actor, "one", "agent", "research", "1.0.0", question)
                        .get("id")
                        .asText();
        store.begin(tenant, first);
        var retrieved =
                store.searchForRun(tenant, first, "answer", "manual", "travel", List.of(), 1)
                        .getFirst();
        JsonNode args = input("travel");
        store.saveToolStep(
                tenant,
                first,
                "lookup",
                "uppercase",
                args,
                JSON.getNodeFactory().textNode("TRAVEL"));
        var output = JSON.createObjectNode().put("answer", "Approval is required.");
        output.putArray("citations").add(retrieved);
        store.finish(tenant, first, "COMPLETED", output);
        JsonNode refs = JSON.createObjectNode().put("travel", first);
        var report = store.scoreSuite(tenant, actor, false, "smoke:1.0.0", refs, null);
        assertTrue(report.at("/report/passed").asBoolean());
        assertTrue(report.at("/report/cases/0/checks/tool").asBoolean());
        assertTrue(report.at("/report/cases/0/checks/citations").asBoolean());
        String baseline = report.get("id").asText();
        assertEquals(
                404,
                assertThrows(
                                StoreException.class,
                                () -> store.report(tenant, "other", false, baseline))
                        .status());
        assertEquals(
                404,
                assertThrows(
                                StoreException.class,
                                () ->
                                        store.scoreSuite(
                                                tenant, "other", false, "smoke:1.0.0", refs, null))
                        .status());
        store.publishEvaluation(
                tenant,
                actor,
                "evaluation-suite",
                suite.deepCopy().put("version", "2.0.0").put("maxRegression", 0));
        String second =
                store.startRun(tenant, actor, "two", "agent", "research", "1.0.0", question)
                        .get("id")
                        .asText();
        store.begin(tenant, second);
        // Identical returned citations must fail if the second run never retrieved the evidence.
        store.finish(tenant, second, "COMPLETED", output);
        var regressed =
                store.scoreSuite(
                        tenant,
                        actor,
                        false,
                        "smoke:2.0.0",
                        JSON.createObjectNode().put("travel", second),
                        baseline);
        assertFalse(regressed.at("/report/passed").asBoolean());
        assertFalse(regressed.at("/report/cases/0/checks/citations").asBoolean());
        assertFalse(regressed.at("/report/cases/0/checks/tool").asBoolean());
        assertEquals(-1, regressed.at("/report/change").asInt());
    }

    @Test
    void evaluationSchemasRejectExternalReferencesAndUnknownAssertions() throws Exception {
        var bad =
                JSON.readTree(
                        """
                        {"name":"invalid","version":"1.0.0","cases":[{"id":"case","input":{},"schema":{"type":"object","$ref":"https://example.invalid/shape"}}]}
                        """);
        assertEquals(
                400,
                assertThrows(
                                StoreException.class,
                                () ->
                                        store.publishEvaluation(
                                                tenant(), "developer", "evaluation-dataset", bad))
                        .status());
    }

    @Test
    void outOfOrderIngestionAndStaleRetriesCannotReplaceTheActiveRevision() {
        String tenant = tenant(), actor = "developer", base = "guides";
        var old = store.ingest(tenant, actor, base, "First", "First edition", List.of());
        String key = old.get("documentKey").asText();
        var second =
                store.submitIngestion(
                        tenant,
                        actor,
                        "upload-2",
                        base,
                        key,
                        "Second",
                        "Second edition",
                        List.of());
        var third =
                store.submitIngestion(
                        tenant,
                        actor,
                        "upload-3",
                        base,
                        key,
                        "Third",
                        "Third edition",
                        List.of("reviewer"));
        assertEquals(3, third.get("revision").asInt());
        assertEquals(
                third.get("id"),
                store.submitIngestion(
                                tenant,
                                actor,
                                "upload-3",
                                base,
                                key,
                                "Third",
                                "Third edition",
                                List.of("reviewer"))
                        .get("id"));
        assertEquals(
                409,
                assertThrows(
                                StoreException.class,
                                () ->
                                        store.submitIngestion(
                                                tenant,
                                                actor,
                                                "upload-3",
                                                base,
                                                key,
                                                "Changed",
                                                "Other",
                                                List.of()))
                        .status());
        var ref3 = new PlatformStore.IngestionRef(tenant, third.get("id").asText(), 1);
        assertTrue(store.pendingIngestions(20).contains(ref3));
        store.acknowledgeIngestion(ref3);
        store.failIngestion(ref3);
        assertEquals("First", store.documents(tenant, List.of()).getFirst().get("title").asText());
        var retry = store.retryIngestion(tenant, actor, false, base, third.get("id").asText());
        assertEquals(2, retry.get("generation").asInt());
        store.indexIngestion(ref3, n -> fail("A stale generation must not perform embedding"));
        var accepted = new PlatformStore.IngestionRef(tenant, third.get("id").asText(), 2);
        store.indexIngestion(accepted, n -> {});
        store.indexIngestion(
                accepted, n -> fail("An acknowledged duplicate must reuse the document version"));
        store.indexIngestion(
                new PlatformStore.IngestionRef(tenant, second.get("id").asText(), 1), n -> {});
        assertTrue(store.documents(tenant, List.of()).isEmpty());
        assertEquals(
                List.of("Third"),
                store.search(tenant, base, "edition", List.of("reviewer"), 10).stream()
                        .map(d -> d.get("title").asText())
                        .toList());
        assertEquals(
                "SUCCEEDED",
                store.ingestion(tenant, actor, false, base, ref3.jobId()).get("status").asText());
        assertEquals(
                404,
                assertThrows(
                                StoreException.class,
                                () -> store.ingestion(tenant, "other", false, base, ref3.jobId()))
                        .status());
    }

    @Test
    void reconciliationLeasesOnlyAcknowledgedNonterminalExecutions() {
        String tenant = tenant(), run = start(tenant);
        var marker = new PlatformStore.StartCommand(tenant, run);
        assertFalse(store.dueReconciliations(100, 60).contains(marker));
        store.acknowledgeStart(tenant, run);
        assertFalse(store.dueReconciliations(100, 60).contains(marker));
        var admin = new JdbcTemplate(connection(POSTGRES.getUsername()));
        admin.update(
                "UPDATE start_commands SET next_check=clock_timestamp() WHERE tenant=? AND"
                        + " run_id=?",
                tenant,
                UUID.fromString(run));
        assertTrue(store.dueReconciliations(100, 60).contains(marker));
        assertFalse(store.dueReconciliations(100, 60).contains(marker));
        store.finish(tenant, run, "FAILED", input("orphaned"));
        admin.update(
                "UPDATE start_commands SET next_check=clock_timestamp() WHERE tenant=? AND"
                        + " run_id=?",
                tenant,
                UUID.fromString(run));
        assertFalse(store.dueReconciliations(100, 60).contains(marker));
    }

    private String nextRun(String tenant, String actor, List<String> roles) {
        String id =
                store.startRun(
                                tenant,
                                actor,
                                roles,
                                UUID.randomUUID().toString(),
                                "agent",
                                "demo",
                                "1",
                                input("next"),
                                "session")
                        .get("id")
                        .asText();
        store.begin(tenant, id);
        return id;
    }

    @Test
    void historyCannotRecoverRevokedRolesOrSupersededSourcesThroughLaterTurns() {
        String tenant = tenant(), initial = start(tenant);
        var doc =
                store.ingest(
                        tenant,
                        "admin",
                        "docs",
                        "Reference",
                        "Original policy",
                        List.of("operator"));
        store.begin(tenant, initial);
        store.searchForRun(tenant, initial, "evidence", "docs", "policy", List.of("operator"), 1);
        store.finish(tenant, initial, "COMPLETED", input("Original policy"));
        var later = nextRun(tenant, "runner", List.of("operator"));
        assertEquals(2, store.messagesForRun(tenant, later, 10).size());
        store.finish(tenant, later, "COMPLETED", input("Summary of original policy"));
        assertTrue(
                store.messagesForRun(tenant, nextRun(tenant, "runner", List.of()), 10).isEmpty());
        assertTrue(
                store.messagesForRun(tenant, nextRun(tenant, "other", List.of("operator")), 10)
                        .isEmpty());
        var revision =
                store.submitIngestion(
                        tenant,
                        "admin",
                        "revise",
                        "docs",
                        doc.get("documentKey").asText(),
                        "Reference",
                        "Replacement terms",
                        List.of("manager"));
        store.indexIngestion(
                new PlatformStore.IngestionRef(tenant, revision.get("id").asText(), 1),
                ignored -> {});
        assertTrue(
                store.messagesForRun(tenant, nextRun(tenant, "runner", List.of("operator")), 10)
                        .isEmpty());
    }

    @Test
    void documentRevisionRequiresOriginalPublisherOrTenantAdministrator() {
        String tenant = tenant();
        var original =
                store.submitIngestion(
                        tenant,
                        "publisher",
                        "first",
                        "manuals",
                        "payroll",
                        "Payroll",
                        "Restricted",
                        List.of("finance"));
        assertEquals(
                403,
                assertThrows(
                                StoreException.class,
                                () ->
                                        store.submitIngestion(
                                                tenant,
                                                "different",
                                                "overwrite",
                                                "manuals",
                                                "payroll",
                                                "Public",
                                                "Changed",
                                                List.of()))
                        .status());
        var revised =
                store.submitIngestion(
                        tenant,
                        "admin",
                        "version-two",
                        "manuals",
                        "payroll",
                        "Updated",
                        "Updated",
                        List.of("finance"),
                        true);
        assertEquals(original.get("revision").asInt() + 1, revised.get("revision").asInt());
    }

    @Test
    void governanceRulesBindArgumentsAndApprovalToThePublishedPolicy() throws Exception {
        String t=tenant(),run=start(t);
        store.begin(t,run);
        var policy=JSON.readTree("""
            {"rules":[{"resource":"send","effect":"ALLOW","when":{"pointer":"/amount","operator":"lt","value":10}},
             {"resource":"send","effect":"REQUIRE_APPROVAL","when":{"pointer":"/amount","operator":"gte","value":10}},
             {"resource":"send","effect":"DENY","when":{"pointer":"/suspended","operator":"eq","value":true}}]}
            """);
        store.publishPolicy(t,"author","orders","1.0.0",policy);
        var refs=List.of("orders:1.0.0");
        var low=JSON.readTree("{\"amount\":3}"); var high=JSON.readTree("{\"amount\":10}");
        assertEquals("ALLOW",store.authorizeTool(t,run,"low","send",low,refs).path("effect").asText());
        assertEquals("DENY",store.authorizeTool(t,run,"string","send",JSON.readTree("{\"amount\":\"3\"}"),refs).path("effect").asText());
        assertEquals("DENY",store.authorizeTool(t,run,"blocked","send",JSON.readTree("{\"amount\":3,\"suspended\":true}"),refs).path("effect").asText());
        assertEquals("REQUIRE_APPROVAL",store.authorizeTool(t,run,"high","send",high,refs).path("effect").asText());
        assertThrows(StoreException.class,()->store.authorizeTool(t,run,"high","send",low,refs));
        assertThrows(StoreException.class,()->store.authorizeTool(t,run,"high","send",high,List.of()));
        var approval=store.requestApproval(t,run,"high","send",high,"approver",300);
        store.decide(t,"reviewer",List.of("approver"),approval.get("id").asText(),approval.get("actionHash").asText(),true);
        store.setToolPolicy(t,"admin","send","DENY","approver");
        assertThrows(StoreException.class,()->store.claimExternalWrite(t,run,"high","send",high,"approver"));
        var failure=new PlatformStore(source,JSON,new TokenHashVectorizer(),new ModelPricing(null,null),
             List.of(request->{throw new IllegalStateException("Offline decision provider");}));
        assertEquals("DENY",failure.authorizeTool(t,run,"plugin","lookup",low,List.of()).path("effect").asText());
    }

    @Test
    void externalDispatchIsClaimedOnceAndConfirmedAfterCancellation() throws Exception {
        String t=tenant(),run=start(t),key="dispatch";
        store.begin(t,run);
        var args=input("item");var a=store.requestApproval(t,run,key,"send",args,"approver",90);
        var hash=a.get("actionHash").asText();
        store.decide(t,"reviewer",List.of("approver"),a.get("id").asText(),hash,true);
        try(var exec=Executors.newFixedThreadPool(2)){
            Callable<Boolean> send=()->store.claimExternalWrite(t,run,key,"send",args,"approver");
            var done=exec.invokeAll(List.of(send,send));
            assertNotEquals(done.getFirst().get(),done.getLast().get());
        }
        assertEquals("DISPATCHED",store.externalEffects(t).getFirst().get("outcome").asText());
        store.cancel(t,"runner",run);
        assertThrows(StoreException.class,()->store.completeExternalWrite(t,run,key,"send",input("different"),input("receipt")));
        assertEquals(input("receipt"),store.completeExternalWrite(t,run,key,"send",args,input("receipt")));
        assertEquals(input("receipt"),store.completeExternalWrite(t,run,key,"send",args,input("ignored")));
        assertThrows(StoreException.class,()->store.reconcileEffect(t,"admin",run,key,hash,"NO_EFFECT",input("receipt")));
    }

    @Test
    void tenantCeilingSerializesReservationsAcrossIsolatedWorkspaces() throws Exception {
        String parent=tenant(),workspace=parent+"~support",one=start(parent),two=start(workspace);
        store.begin(parent,one);store.begin(workspace,two);
        store.setQuota(parent,"admin","*",10L,null);
        try(var exec=Executors.newFixedThreadPool(2)){
            var calls=List.of(new String[]{parent,one},new String[]{workspace,two}).stream()
                .map(pair->(Callable<Boolean>)()->{
                    try {store.reserveModel(pair[0],pair[1],"only",5,5,100,2,null);return true;}
                    catch(StoreException ex){assertEquals(429,ex.status());return false;}
                }).toList();
            var done=exec.invokeAll(calls);
            assertNotEquals(done.getFirst().get(),done.getLast().get());
        }
        assertThrows(StoreException.class,()->store.run(parent,two));
        assertThrows(StoreException.class,()->store.reserveModel(workspace,two,"over",1,1,100,3,null));
        var backend = new JdbcTemplate(source);
        assertThrows(DataAccessException.class,()->backend.queryForObject("SELECT reserve_parent_usage(?,1,null)",Boolean.class,workspace));
    }

    @Test
    void chainCanBeAnchoredAndReportsPrivilegedHistoryChanges() {
        String t=tenant();start(t);
        var first=store.auditIntegrity(t,0,1);
        assertTrue(first.path("valid").asBoolean());assertEquals(1,first.path("next").asInt());
        var page=store.auditIntegrity(t,1,100);
        assertTrue(page.path("valid").asBoolean());assertEquals(first.path("endHash").asText(),page.at("/events/0/previousHash").asText());
        assertEquals(page.at("/events/0/eventHash").asText(),Database.digest(page.at("/events/0/canonical").asText()));
        var matching=store.audit(t,"run.queued",null,1);
        assertEquals(1,matching.size());
        assertTrue(store.audit(t,"run.queued",matching.getFirst().get("id").asLong(),10).isEmpty());
        // This connection and these records exist only in the disposable database.
        new JdbcTemplate(connection(POSTGRES.getUsername())).update("UPDATE audit_events SET resource='changed' WHERE tenant=? AND chain_position=1",t);
        assertFalse(store.auditIntegrity(t,0,100).path("valid").asBoolean());
    }

    @Test
    void retentionArchivesOnlyDuePayloadsAndKeepsImmutableReadsAndIdempotency() {
        String t=tenant(),run=start(t);store.begin(t,run);
        store.saveStep(t,run,"answer",input("detail"),0,0);
        store.finish(t,run,"COMPLETED",input("answer"));
        var admin=new JdbcTemplate(connection(POSTGRES.getUsername()));
        admin.update("UPDATE runs SET created_at=now()-interval '5 days',updated_at=now()-interval '4 days' WHERE tenant=? AND id=?",t,UUID.fromString(run));
        assertEquals(0,store.archiveDue(t,"admin",1).path("archived").asInt());
        store.configureRetention(t,"admin",2,1);
        assertEquals(1,store.archiveDue(t,"admin",1).path("archived").asInt());
        assertEquals(0,store.archiveDue(t,"admin",1).path("archived").asInt());
        assertEquals("null",admin.queryForObject("SELECT input::text FROM runs WHERE tenant=? AND id=?",String.class,t,UUID.fromString(run)));
        assertEquals(input("hello"),store.run(t,run).get("input"));assertEquals(input("answer"),store.run(t,run).get("result"));
        assertEquals(input("detail"),store.step(t,run,"answer").orElseThrow());
        assertEquals(input("detail"),store.steps(t,run).getFirst().get("output"));
        assertEquals(run,store.startRun(t,"runner",List.of("operator"),"key","agent","demo","1",input("hello"),"session").get("id").asText());
        assertTrue(store.recordEvaluation(t,"author",run,input("answer")).path("passed").asBoolean());
        assertTrue(store.messagesForRun(t,nextRun(t,"runner",List.of("operator")),10).isEmpty());
        assertThrows(DataAccessException.class,()->new JdbcTemplate(source).update("UPDATE archived_steps SET output='null'::jsonb"));
    }

    @Test
    void retirementFiltersKnowledgeEpisodesAndPendingRevisions() {
        String t=tenant(),run=start(t);
        assertThrows(StoreException.class,()->store.ingest(t,"owner","docs","Title","Content",List.of(),null,"RESTRICTED"));
        var source=store.ingest(t,"owner","docs","Rules","handover policy",List.of("operator"),"https://knowledge.example/rules","RESTRICTED");
        var key=source.path("documentKey").asText();
        store.begin(t,run);
        var hit=store.searchForRun(t,run,"source","docs","handover",List.of("operator"),1).getFirst();
        assertEquals("https://knowledge.example/rules",hit.path("sourceUri").asText());assertEquals("RESTRICTED",hit.path("classification").asText());
        store.finish(t,run,"COMPLETED",input("handover policy"));
        String next=nextRun(t,"runner",List.of("operator"));
        assertFalse(store.episodicForRun(t,next,"handover",4).isEmpty());
        assertTrue(store.episodicForRun(t,next,"unrelated",4).isEmpty());
        var pending=store.submitIngestion(t,"owner","queued","docs",key,"Updated","handover revision",List.of("operator"));
        assertThrows(StoreException.class,()->store.retireDocument(t,"outsider",false,"docs",key));
        store.retireDocument(t,"owner",false,"docs",key);
        store.indexIngestion(new PlatformStore.IngestionRef(t,pending.path("id").asText(),1),index->{});
        assertTrue(store.search(t,"docs","handover",List.of("operator"),4).isEmpty());
        assertTrue(store.episodicForRun(t,next,"handover",4).isEmpty());
        var active=store.submitIngestion(t,"owner","new","docs",key,"Republished","handover replacement",List.of("operator"),false,"s3://archive/rules","INTERNAL");
        store.indexIngestion(new PlatformStore.IngestionRef(t,active.path("id").asText(),1),index->{});
        assertEquals("s3://archive/rules",store.search(t,"docs","handover",List.of("operator"),1).getFirst().get("sourceUri").asText());
    }
    @Test
    void scoreRequiresRealModelEvidenceAndAnImmutableJudgeBoundToTheAnswer() throws Exception {
        String t=tenant(),actor="scientist";
        store.publish(t,actor,"agent","helper","1.0.0",input("plan"));
        store.publish(t,actor,"agent","grader","1.0.0",input("judge plan"));
        var cases=JSON.readTree("""
            {"name":"quality","version":"1.0.0","cases":[
            {"id":"answer","input":"travel","relevanceTerms":["receipt"],"forbiddenTools":["send"],"maxDurationMillis":600000,
            "maxTokens":0,"maxCostUsd":0,"judge":{"agent":"grader:1.0.0","rubric":"Requires receipts","minScore":0.8}},
            {"id":"guard","input":"request","expectedStatus":"DENIED","forbiddenTools":["send"]}]}
            """);
        store.publishEvaluation(t,actor,"evaluation-dataset",cases);
        store.publishEvaluation(t,actor,"evaluation-suite",JSON.readTree("""
            {"name":"checks","version":"1.0.0","target":{"kind":"agent","name":"helper","version":"1.0.0"},
             "dataset":"quality:1.0.0","thresholds":{"safety":1,"judge":1}}
            """));
        JsonNode question=cases.at("/cases/0/input");
        String run=store.startRun(t,actor,"run","agent","helper","1.0.0",question).path("id").asText();
        String guard=store.startRun(t,actor,"guard","agent","helper","1.0.0",cases.at("/cases/1/input")).path("id").asText();
        var answer=JSON.readTree("{\"answer\":\"Use the receipt\"}");
        store.begin(t,run);store.finish(t,run,"COMPLETED",answer);
        store.begin(t,guard);store.finish(t,guard,"DENIED",input("policy"));
        var claims=JSON.createObjectNode().put("answer",run).put("guard",guard);
        var payload=JSON.createObjectNode().put("candidateRunId",run).put("rubric","Requires receipts");
        payload.set("answer",answer);payload.set("question",question);
        String noModel=store.startRun(t,actor,"unverified","agent","grader","1.0.0",payload).path("id").asText();
        store.begin(t,noModel);store.finish(t,noModel,"COMPLETED",JSON.readTree("{\"score\":1}"));
        var judges=JSON.createObjectNode().put("answer",noModel);
        assertThrows(StoreException.class,()->store.scoreSuite(t,actor,false,"checks:1.0.0",claims,null,judges));
        String model=store.startRun(t,actor,"verified","agent","grader","1.0.0",payload).path("id").asText();
        store.begin(t,model);store.reserveModel(t,model,"grade",10,10,100,3,null);
        store.saveStep(t,model,"grade",input("raw output"),7,2);
        store.finish(t,model,"COMPLETED",JSON.getNodeFactory().textNode("{\"score\":0.9}"));
        judges.put("answer",model);
        var report=store.scoreSuite(t,actor,false,"checks:1.0.0",claims,null,judges).get("report");
        assertTrue(report.path("passed").asBoolean(),report.toString());
        for(String check:List.of("latency","tokens","cost","safety","relevance","judge","status"))
            assertEquals(1,report.at("/checkRates/"+check).intValue(),check);
        assertEquals(model,report.at("/cases/0/measurements/judgeRunId").asText());
        assertThrows(StoreException.class,()->store.scoreSuite(t,"stranger",false,"checks:1.0.0",claims,null,judges));
    }

    @Test
    void metricsObserveOnlyCommittedBusinessTransitions() {
        String t=tenant(),run=start(t);
        var registry=new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var measured=new PlatformStore(source,JSON,new TokenHashVectorizer(),new ModelPricing(BigDecimal.ONE,BigDecimal.ONE),List.of(),registry);
        measured.begin(t,run);
        var args=input("receipt");
        var approval=measured.requestApproval(t,run,"send","send",args,"approver",300);
        measured.requestApproval(t,run,"send","send",args,"approver",300);
        measured.decide(t,"reviewer",List.of("approver"),approval.path("id").asText(),approval.path("actionHash").asText(),true);
        measured.reserveModel(t,run,"one",5,5,100,1,null);
        measured.saveStep(t,run,"one",input("answer"),3,2);
        measured.saveStep(t,run,"one",input("retry"),3,2);
        measured.finish(t,run,"COMPLETED",input("answer"));measured.finish(t,run,"COMPLETED",input("retry"));
        assertEquals(1,registry.get("agent.runs").counter().count());assertEquals(1,registry.get("agent.run.duration").timer().count());
        assertEquals(1,registry.get("approval.requests").counter().count());assertEquals(1,registry.get("approval.wait").timer().count());
        assertEquals(0.000005,registry.get("model.estimated.cost.usd").counter().count(),1e-12);
    }

    @Test
    void customKnowledgeIndexUsesCanonicalAuthorizedContentAndRetirementWatermark() {
        String t=tenant(),foreign=tenant();
        class ExternalIndex implements io.agentplatform.spi.KnowledgeProvider {
            final List<IndexRequest> documents=new ArrayList<>();
            Retirement retired;
            public String revision(){return "custom-v2";}
            public IndexResult index(IndexRequest request,java.util.function.IntConsumer progress){
                documents.add(request);progress.accept(request.chunks().size());return IndexResult.managed();
            }
            public List<Hit> search(Query query){
                return documents.stream().map(d->new Hit(d.documentId(),0,1.0)).toList();
            }
            public void retire(Retirement document){retired=document;}
        }
        var index=new ExternalIndex();
        var other=new PlatformStore(source,JSON,new TokenHashVectorizer(),new ModelPricing(null,null),List.of(),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),index);
        var doc=other.ingest(t,"owner","legal","Public","published canonical text",List.of(),"internal://legal/draft","INTERNAL");
        other.ingest(t,"owner","legal","Restricted","sensitive",List.of("finance"));
        other.ingest(t,"owner","other","Elsewhere","separate base",List.of());
        other.ingest(foreign,"owner","legal","Foreign","other tenant",List.of());
        var hits=other.search(t,"legal","query",List.of(),4);
        assertEquals(1,hits.size());assertEquals("published canonical text",hits.getFirst().get("text").asText());
        assertEquals(2,other.search(t,"legal","query",List.of("finance"),4).size());
        var first=index.documents.getFirst();
        assertEquals("INTERNAL",first.classification());assertEquals("internal://legal/draft",first.sourceUri());
        var key=doc.get("documentKey").asText();
        other.retireDocument(t,"owner",false,"legal",key);
        assertEquals(key,index.retired.documentKey());assertEquals(1,index.retired.throughVersion());
        assertTrue(other.search(t,"legal","query",List.of(),4).isEmpty());
        assertTrue(store.search(t,"legal","query",List.of("finance"),4).isEmpty());
    }
}
