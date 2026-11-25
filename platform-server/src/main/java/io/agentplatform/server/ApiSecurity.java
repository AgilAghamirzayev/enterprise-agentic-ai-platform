package io.agentplatform.server;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Configuration
class ApiSecurity {
    private HttpSecurity api(HttpSecurity http, String challenge, String metricsRole)
            throws Exception {
        return http.exceptionHandling(
                        errors ->
                                errors.accessDeniedHandler(
                                                (r, s, e) ->
                                                        ProblemResponses.write(
                                                                s, 403, "Access denied", null))
                                        .authenticationEntryPoint(
                                                (r, s, e) ->
                                                        ProblemResponses.write(
                                                                s,
                                                                401,
                                                                "Authentication required",
                                                                challenge)))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers("/actuator/health", "/actuator/health/**")
                                        .permitAll()
                                        .requestMatchers("/actuator/**")
                                        .hasRole(metricsRole)
                                        .anyRequest()
                                        .authenticated());
    }

    @Bean
    @Profile("!local")
    SecurityFilterChain oidc(
            HttpSecurity http,
            @Value("${platform.oidc.issuer}") String issuer,
            @Value("${platform.oidc.audience}") String audience)
            throws Exception {
        NimbusJwtDecoder decoder = JwtDecoders.fromIssuerLocation(issuer);
        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        JwtValidators.createDefaultWithIssuer(issuer),
                        new JwtClaimValidator<List<String>>(
                                "aud", values -> values != null && values.contains(audience))));
        var roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("roles");
        roles.setAuthorityPrefix("ROLE_");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(roles);
        return api(http, "Bearer", "platform_admin")
                .oauth2ResourceServer(
                        config ->
                                config.authenticationEntryPoint(
                                                (r, s, e) ->
                                                        ProblemResponses.write(
                                                                s,
                                                                401,
                                                                "Invalid or missing bearer token",
                                                                "Bearer"))
                                        .jwt(
                                                jwt ->
                                                        jwt.decoder(decoder)
                                                                .jwtAuthenticationConverter(
                                                                        converter)))
                .build();
    }

    @Bean
    @Profile("local")
    SecurityFilterChain local(HttpSecurity http) throws Exception {
        return api(http, "Basic realm=\"agent-platform\"", "admin")
                .addFilterBefore(new LocalWriteHeader(), BasicAuthenticationFilter.class)
                .httpBasic(
                        basic ->
                                basic.authenticationEntryPoint(
                                        (r, s, e) ->
                                                ProblemResponses.write(
                                                        s,
                                                        401,
                                                        "Invalid or missing local account",
                                                        "Basic realm=\"agent-platform\"")))
                .build();
    }

    @Bean
    @Profile("local")
    InMemoryUserDetailsManager localAccounts(
            @Value("${platform.local.admin-password}") String admin,
            @Value("${platform.local.reviewer-password}") String reviewer) {
        if (admin.length() < 16 || reviewer.length() < 16 || admin.equals(reviewer))
            throw new IllegalArgumentException(
                    "Local accounts need distinct passwords of at least 16 characters");
        var encoder = new BCryptPasswordEncoder();
        return new InMemoryUserDetailsManager(
                User.withUsername("admin")
                        .password("{bcrypt}" + encoder.encode(admin))
                        .roles("admin")
                        .build(),
                User.withUsername("reviewer")
                        .password("{bcrypt}" + encoder.encode(reviewer))
                        .roles("approver")
                        .build());
    }

    private static final class LocalWriteHeader extends OncePerRequestFilter {
        protected void doFilterInternal(
                HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws IOException, ServletException {
            if (!List.of("GET", "HEAD", "OPTIONS").contains(req.getMethod())
                    && !"studio".equals(req.getHeader("X-Platform-Client"))) {
                ProblemResponses.write(
                        res, 403, "Local writes require X-Platform-Client: studio", null);
                return;
            }
            chain.doFilter(req, res);
        }
    }
}
