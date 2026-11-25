package io.agentplatform.server;

import io.agentplatform.store.StoreException;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(StoreException.class)
    ProblemDetail store(StoreException ex) {
        return ProblemResponses.problem(ex.status(), ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalid(IllegalArgumentException ex) {
        return ProblemResponses.problem(400, ex.getMessage());
    }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    ProblemDetail responseStatus(org.springframework.web.server.ResponseStatusException ex) {
        return ProblemResponses.problem(
                ex.getStatusCode().value(),
                ex.getReason() == null ? "Resource is unavailable" : ex.getReason());
    }
}
