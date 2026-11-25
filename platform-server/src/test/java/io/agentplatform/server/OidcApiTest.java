package io.agentplatform.server;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;

import io.agentplatform.store.PlatformStore;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@org.springframework.boot.autoconfigure.ImportAutoConfiguration(
        org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration
                .class)
@WebMvcTest(controllers = {WorkspaceController.class, RunController.class})
@ContextConfiguration(
        classes = {
            RunSubmission.class,
            WorkspaceController.class,
            RunController.class,
            ApiSecurity.class,
            JsonBodyLimit.class,
            WorkspaceScope.class,
            ApiErrors.class
        })
class OidcApiTest {
    @MockitoBean RunWake wake;
    static final RSAKey SIGNER;
    static final HttpServer IDP;
    static final String ISSUER;

    static {
        try {
            SIGNER = new RSAKeyGenerator(2048).keyID("test-signing").generate();
            IDP = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ISSUER = "http://127.0.0.1:" + IDP.getAddress().getPort();
            IDP.createContext(
                    "/",
                    exchange -> {
                        String payload =
                                exchange.getRequestURI().getPath().equals("/jwks")
                                        ? new JWKSet(SIGNER.toPublicJWK()).toString()
                                        : "{\"issuer\":\""
                                                + ISSUER
                                                + "\",\"jwks_uri\":\""
                                                + ISSUER
                                                + "/jwks\"}";
                        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, bytes.length);
                        try (var body = exchange.getResponseBody()) {
                            body.write(bytes);
                        }
                    });
            IDP.start();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) {
        registry.add("platform.oidc.issuer", () -> ISSUER);
        registry.add("platform.oidc.audience", () -> "agent-platform");
    }

    @AfterAll
    static void closeIssuer() {
        IDP.stop(0);
    }

    @Autowired MockMvc api;
    @MockitoBean PlatformStore store;

    static String bearer(String audience, String tenant, String role) throws Exception {
        return bearer(audience, tenant, role, null);
    }

    static String bearer(String audience, String tenant, String role, String workspace)
            throws Exception {
        var claims =
                new JWTClaimsSet.Builder()
                        .issuer(ISSUER)
                        .subject("alice")
                        .audience(audience)
                        .expirationTime(Date.from(Instant.now().plusSeconds(120)))
                        .claim("tenant_id", tenant)
                        .claim("workspace_id", workspace)
                        .claim("roles", List.of(role))
                        .build();
        var jwt =
                new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(SIGNER.getKeyID()).build(),
                        claims);
        jwt.sign(new RSASSASigner(SIGNER));
        return "Bearer " + jwt.serialize();
    }

    @Test
    void validatesSignatureAudienceAndTenant() throws Exception {
        api.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
        api.perform(get("/api/v1/me").header("Authorization", bearer("another-api", "a", "user")))
                .andExpect(status().isUnauthorized());
        api.perform(
                        get("/api/v1/me")
                                .header("Authorization", bearer("agent-platform", null, "user")))
                .andExpect(status().isForbidden());
        api.perform(
                        get("/api/v1/me")
                                .header("Authorization", bearer("agent-platform", "alpha", "user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant").value("alpha"))
                .andExpect(jsonPath("$.roles[0]").value("user"));
    }

    @Test
    void workspaceClaimPinsSignedIdentityAndIntersectsMembership() throws Exception {
        var directory = org.mockito.Mockito.mock(io.agentplatform.store.WorkspaceDirectory.class);
        when(store.workspaces()).thenReturn(directory);
        when(directory.grants("alpha", "payroll", "alice", false))
                .thenReturn(List.of("operator", "developer"));
        String token = bearer("agent-platform", "alpha", "operator", "payroll");
        api.perform(get("/api/v1/me").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant").value("alpha"))
                .andExpect(jsonPath("$.workspace").value("payroll"))
                .andExpect(jsonPath("$.roles.length()").value(1));
        api.perform(
                        get("/api/v1/me")
                                .header("Authorization", token)
                                .header("X-Platform-Workspace", "default"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        when(directory.grants("alpha", "payroll", "alice", false)).thenReturn(List.of());
        api.perform(get("/api/v1/me").header("Authorization", token))
                .andExpect(status().isForbidden());
    }

    @Test
    void onlyOwnerOrAuditorCanReadRunPayload() throws Exception {
        var row =
                new ObjectMapper()
                        .createObjectNode()
                        .put("actor", "bob")
                        .put("status", "COMPLETED");
        when(store.run("alpha", "r")).thenReturn(row);
        api.perform(
                        get("/api/v1/runs/r")
                                .header(
                                        "Authorization",
                                        bearer("agent-platform", "alpha", "operator")))
                .andExpect(status().isNotFound());
        api.perform(
                        get("/api/v1/runs/r")
                                .header(
                                        "Authorization",
                                        bearer("agent-platform", "alpha", "auditor")))
                .andExpect(status().isOk());
    }

    @Test
    void untrustedTenantFieldCannotChangeStartScope() throws Exception {
        api.perform(
                        post("/api/v1/runs")
                                .header("Authorization", bearer("agent-platform", "a", "user"))
                                .header("Idempotency-Key", "request")
                                .contentType("application/json")
                                .content(
                                        "{\"tenant\":\"b\",\"input\":{},\"agent\":{\"name\":\"hello\",\"version\":\"1.0.0\"}}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(store);
    }

    @Test
    void deniesOversizedInputsBeforeParsing() throws Exception {
        api.perform(
                        post("/api/v1/runs")
                                .contentType("application/json")
                                .content("a".repeat(524289)))
                .andExpect(status().isPayloadTooLarge());
    }
}
