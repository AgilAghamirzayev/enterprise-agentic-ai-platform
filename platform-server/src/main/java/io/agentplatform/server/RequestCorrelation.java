package io.agentplatform.server;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class RequestCorrelation extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String trace = MDC.get("traceId");
        if (trace != null) {
            response.setHeader("X-Request-Id", trace);
            chain.doFilter(request, response);
            return;
        }
        String requestId = UUID.randomUUID().toString().replace("-", "");
        try (var ignored = MDC.putCloseable("traceId", requestId)) {
            response.setHeader("X-Request-Id", requestId);
            chain.doFilter(request, response);
        }
    }
}
