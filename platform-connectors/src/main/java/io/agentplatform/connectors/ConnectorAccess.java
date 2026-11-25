package io.agentplatform.connectors;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;

import java.net.URI;
import java.util.List;

/** Validates an installed bearer credential against its issuer and intended resource. */
public record ConnectorAccess(String issuer, String jwksUri, String audience) {
    public ConnectorAccess {
        if (!https(issuer) || !https(jwksUri) || audience == null || audience.isBlank())
            throw new IllegalArgumentException("Expected HTTPS issuer, key set and token audience");
    }

    private static boolean https(String value) {
        if (value == null) return false;
        URI uri = URI.create(value);
        return "https".equals(uri.getScheme())
                && uri.getHost() != null
                && uri.getUserInfo() == null
                && uri.getQuery() == null
                && uri.getFragment() == null;
    }

    JwtDecoder verifier() {
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        JwtValidators.createDefaultWithIssuer(issuer),
                        new JwtClaimValidator<List<String>>(
                                "aud", values -> values != null && values.contains(audience)),
                        new JwtClaimValidator<>("exp", value -> value != null)));
        return decoder;
    }
}
