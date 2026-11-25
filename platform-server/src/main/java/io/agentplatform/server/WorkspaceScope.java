package io.agentplatform.server;

import io.agentplatform.store.PlatformStore;

import jakarta.servlet.http.*;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;

import java.util.*;

@Component
class WorkspaceScope implements WebMvcConfigurer, HandlerInterceptor {
    static final String HEADER = "X-Platform-Workspace";
    private final PlatformStore store;

    WorkspaceScope(PlatformStore store) {
        this.store = store;
    }

    @Override
    public void addInterceptors(InterceptorRegistry interceptors) {
        interceptors.addInterceptor(this).addPathPatterns("/api/v1/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        if (!(req.getUserPrincipal() instanceof Authentication auth)) return true;
        var identity = Identity.authenticated(auth);
        String pinned =
                auth instanceof JwtAuthenticationToken jwt
                        ? jwt.getToken().getClaimAsString("workspace_id")
                        : null;
        String requested = req.getHeader(HEADER);
        if (pinned != null && requested != null && !pinned.equals(requested))
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Token is restricted to a different workspace");
        String workspace =
                requested == null ? Objects.requireNonNullElse(pinned, "default") : requested;
        if (!workspace.equals("default")) {
            if (!workspace.matches("[a-z][a-z0-9-]{0,62}"))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid workspace name");
            boolean admin = identity.roles().contains("admin");
            var grants =
                    store.workspaces()
                            .grants(identity.organization(), workspace, identity.actor(), admin);
            var effective =
                    admin
                            ? identity.roles()
                            : identity.roles().stream().filter(grants::contains).toList();
            if (effective.isEmpty())
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "Workspace membership required");
            identity =
                    new Identity(identity.organization(), identity.actor(), effective, workspace);
        }
        req.setAttribute(Identity.class.getName(), identity);
        return true;
    }
}
