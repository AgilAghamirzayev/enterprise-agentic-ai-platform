package io.agentplatform.server;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.*;
import java.nio.charset.StandardCharsets;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
class JsonBodyLimit extends OncePerRequestFilter {
    private static final int LIMIT = 512 * 1024;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !java.util.Set.of("POST", "PUT", "PATCH").contains(request.getMethod())
                || !request.getRequestURI().startsWith("/api/v1/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request.getContentLengthLong() > LIMIT) {
            ProblemResponses.write(response, 413, "Request body exceeds the allowed size", null);
            return;
        }
        byte[] bytes = request.getInputStream().readNBytes(LIMIT + 1);
        if (bytes.length > LIMIT) {
            ProblemResponses.write(response, 413, "Request body exceeds the allowed size", null);
            return;
        }
        var buffer = new ByteArrayInputStream(bytes);
        chain.doFilter(
                new HttpServletRequestWrapper(request) {
                    @Override
                    public ServletInputStream getInputStream() {
                        return new ServletInputStream() {
                            @Override
                            public boolean isFinished() {
                                return buffer.available() == 0;
                            }

                            @Override
                            public boolean isReady() {
                                return true;
                            }

                            @Override
                            public void setReadListener(ReadListener listener) {
                                throw new UnsupportedOperationException();
                            }

                            @Override
                            public int read() {
                                return buffer.read();
                            }

                            @Override
                            public int read(byte[] b, int off, int len) {
                                return buffer.read(b, off, len);
                            }
                        };
                    }

                    @Override
                    public BufferedReader getReader() {
                        return new BufferedReader(
                                new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
                    }
                },
                response);
    }
}
