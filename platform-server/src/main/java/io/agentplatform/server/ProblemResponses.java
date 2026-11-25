package io.agentplatform.server;

import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.http.*;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.UUID;

final class ProblemResponses {
    private ProblemResponses() {}

    static ProblemDetail problem(int status, String detail) {
        var code = HttpStatus.valueOf(status).name();
        var problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail);
        problem.setType(
                URI.create(
                        "urn:agentplatform:error:"
                                + code.toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setProperty("code", code);
        String trace = MDC.get("traceId");
        problem.setProperty("traceId", trace == null ? UUID.randomUUID().toString() : trace);
        return problem;
    }

    static void write(HttpServletResponse response, int status, String detail, String challenge)
            throws IOException {
        var p = problem(status, detail);
        response.setStatus(status);
        response.setContentType("application/problem+json");
        if (challenge != null) response.setHeader("WWW-Authenticate", challenge);
        try (var json =
                new com.fasterxml.jackson.core.JsonFactory()
                        .createGenerator(response.getOutputStream())) {
            json.writeStartObject();
            json.writeStringField("type", p.getType().toString());
            json.writeStringField("title", p.getTitle());
            json.writeNumberField("status", status);
            json.writeStringField("detail", detail);
            json.writeStringField("code", String.valueOf(p.getProperties().get("code")));
            json.writeStringField("traceId", String.valueOf(p.getProperties().get("traceId")));
            json.writeEndObject();
        }
    }
}
