package io.agentplatform.connectors;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.jcajce.*;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.*;

import java.math.BigInteger;
import java.net.*;
import java.net.http.HttpClient;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.net.ssl.*;

class ConnectorTransportTest {
    final ObjectMapper json = new ObjectMapper();
    final List<String> methods = new CopyOnWriteArrayList<>();
    HttpsServer server;
    HttpClient client;

    @BeforeEach
    void listen() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        var subject = new X500Name("CN=localhost");
        var now = Instant.now();
        var certificate =
                new JcaX509v3CertificateBuilder(
                                subject,
                                BigInteger.ONE,
                                Date.from(now.minusSeconds(30)),
                                Date.from(now.plusSeconds(3600)),
                                subject,
                                pair.getPublic())
                        .addExtension(
                                Extension.subjectAlternativeName,
                                false,
                                new GeneralNames(
                                        new GeneralName(GeneralName.iPAddress, "127.0.0.1")))
                        .build(
                                new JcaContentSignerBuilder("SHA256withRSA")
                                        .build(pair.getPrivate()));
        var cert = new JcaX509CertificateConverter().getCertificate(certificate);
        var keys = KeyStore.getInstance("PKCS12");
        keys.load(null, null);
        keys.setKeyEntry(
                "fixture",
                pair.getPrivate(),
                new char[0],
                new java.security.cert.Certificate[] {cert});
        var km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        km.init(keys, new char[0]);
        var tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tm.init(keys);
        var tls = SSLContext.getInstance("TLS");
        tls.init(km.getKeyManagers(), tm.getTrustManagers(), new SecureRandom());
        client = HttpClient.newBuilder().sslContext(tls).build();
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls));
        server.createContext(
                "/rest", e -> respond(e, 200, Map.of("query", e.getRequestURI().getRawQuery())));
        server.createContext("/mcp", this::rpc);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        client.close();
    }

    @Test
    void restSendsOnlyEncodedQueryParameters() {
        assertEquals(
                "id=a%26b",
                tool("rest")
                        .invoke(json.createObjectNode().put("id", "a&b"))
                        .required("query")
                        .asText());
    }

    @Test
    void negotiatesMcpAndCallsOnlyBoundTool() {
        var result = tool("mcp").invoke(json.createObjectNode().put("id", "C-42"));
        assertEquals("catalog.find:C-42", result.get(0).required("text").asText());
        assertTrue(
                methods.containsAll(
                        List.of("initialize", "notifications/initialized", "tools/call")));
    }

    @Test
    void pinsDiscoveredMcpSchemaBeforeInvocation() {
        var uri = URI.create("https://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
        var expected = json.createObjectNode().put("type", "object");
        var args = json.createObjectNode().put("id", "sample");
        var binding =
                new ConfiguredTools.Binding(
                        "catalog",
                        "tenant",
                        "mcp",
                        uri,
                        "catalog.find",
                        null,
                        Set.of("id"),
                        "2.0.0",
                        null,
                        false,
                        expected,
                        null,
                        null);
        var remote = new RemoteTool(binding, json, null, client);
        assertEquals("2.0.0", remote.version());
        assertDoesNotThrow(() -> remote.invoke(args));
        methods.clear();
        var mismatched =
                new ConfiguredTools.Binding(
                        "catalog",
                        "tenant",
                        "mcp",
                        uri,
                        "catalog.find",
                        null,
                        Set.of("id"),
                        "2.1.0",
                        null,
                        false,
                        json.createObjectNode().put("type", "string"),
                        null,
                        null);
        assertThrows(
                IllegalStateException.class,
                () -> new RemoteTool(mismatched, json, null, client).invoke(args));
        assertTrue(methods.contains("tools/list"));
        assertFalse(methods.contains("tools/call"));
    }

    @Test
    void fixedRestWriteRequiresReceiptKeyAndProjectsPublicResult() {
        var receipts = new java.util.concurrent.ConcurrentHashMap<String, String>();
        server.createContext(
                "/tickets",
                exchange -> {
                    assertEquals("POST", exchange.getRequestMethod());
                    String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
                    assertEquals(
                            "T-1", json.readTree(exchange.getRequestBody()).path("id").asText());
                    String ticket =
                            receipts.computeIfAbsent(key, k -> UUID.randomUUID().toString());
                    respond(exchange, 201, Map.of("ticket", ticket, "internal", "omit"));
                });
        var binding =
                new ConfiguredTools.Binding(
                        "tickets",
                        "tenant",
                        "rest",
                        URI.create(
                                "https://127.0.0.1:" + server.getAddress().getPort() + "/tickets"),
                        null,
                        null,
                        Set.of("id"),
                        "1.0.0",
                        "POST",
                        true,
                        null,
                        Set.of("ticket"),
                        null);
        var remote = new RemoteTool(binding, json, null, client);
        var args = json.createObjectNode().put("id", "T-1");
        assertThrows(IllegalStateException.class, () -> remote.invoke(args));
        var result = remote.invokeApproved(args, "run-1:step-2");
        assertEquals(result, remote.invokeApproved(args, "run-1:step-2"));
        assertEquals(1, receipts.size());
        assertFalse(result.has("internal"));
    }

    private RemoteTool tool(String type) {
        var url = URI.create("https://127.0.0.1:" + server.getAddress().getPort() + "/" + type);
        var binding =
                new ConfiguredTools.Binding(
                        "catalog", "tenant", type, url, "catalog.find", null, Set.of("id"));
        return new RemoteTool(binding, json, null, client);
    }

    private void rpc(HttpExchange e) throws java.io.IOException {
        if (!e.getRequestMethod().equals("POST")) {
            e.sendResponseHeaders(405, -1);
            e.close();
            return;
        }
        var req = json.readTree(e.getRequestBody());
        var method = req.path("method").asText();
        methods.add(method);
        if (!req.has("id")) {
            e.sendResponseHeaders(202, -1);
            e.close();
            return;
        }
        Object result =
                switch (method) {
                    case "initialize" ->
                            Map.of(
                                    "protocolVersion",
                                    req.at("/params/protocolVersion").asText(),
                                    "capabilities",
                                    Map.of("tools", Map.of()),
                                    "serverInfo",
                                    Map.of("name", "fixture", "version", "1"));
                    case "tools/list" ->
                            Map.of(
                                    "tools",
                                    List.of(
                                            Map.of(
                                                    "name",
                                                    "catalog.find",
                                                    "inputSchema",
                                                    Map.of("type", "object"))));
                    case "tools/call" ->
                            Map.of(
                                    "content",
                                    List.of(
                                            Map.of(
                                                    "type",
                                                    "text",
                                                    "text",
                                                    req.at("/params/name").asText()
                                                            + ":"
                                                            + req.at("/params/arguments/id")
                                                                    .asText())));
                    default -> Map.of();
                };
        respond(e, 200, Map.of("jsonrpc", "2.0", "id", req.get("id"), "result", result));
    }

    private void respond(HttpExchange e, int status, Object result) throws java.io.IOException {
        byte[] body = json.writeValueAsBytes(result);
        e.getResponseHeaders().set("Content-Type", "application/json");
        e.sendResponseHeaders(status, body.length);
        try (var out = e.getResponseBody()) {
            out.write(body);
        }
    }
}
