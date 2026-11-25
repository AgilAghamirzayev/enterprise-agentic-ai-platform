package io.agentplatform.server;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

record Identity(
        @JsonProperty("tenant") String organization,
        String actor,
        List<String> roles,
        String workspace) {
    Identity(String organization, String actor, List<String> roles) {
        this(organization, actor, roles, "default");
    }

    String tenant() {
        return workspace.equals("default") ? organization : organization + "~" + workspace;
    }

    static Identity from(Authentication principal) {
        if (RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            if (request.getAttribute(Identity.class.getName()) instanceof Identity identity)
                return identity;
        }
        return authenticated(principal);
    }

    static Identity authenticated(Authentication principal) {
        var roles =
                principal.getAuthorities().stream()
                        .map(Object::toString)
                        .filter(r -> r.startsWith("ROLE_"))
                        .map(r -> r.substring(5))
                        .map(r -> r.equals("tenant_admin") ? "admin" : r)
                        .distinct()
                        .toList();
        if (principal instanceof JwtAuthenticationToken jwt) {
            String tenant = jwt.getToken().getClaimAsString("tenant_id");
            String actor = jwt.getToken().getSubject();
            if (tenant == null
                    || !tenant.matches("[A-Za-z0-9_-]{1,80}")
                    || actor == null
                    || actor.isBlank()
                    || actor.length() > 160)
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "Missing tenant or subject");
            return new Identity(tenant, actor, roles);
        }
        return new Identity("local", principal.getName(), roles);
    }

    boolean has(String... allowed) {
        return roles.contains("admin")
                || java.util.Arrays.stream(allowed).anyMatch(roles::contains);
    }

    void require(String... allowed) {
        if (!has(allowed))
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Role does not permit this action");
    }
}
