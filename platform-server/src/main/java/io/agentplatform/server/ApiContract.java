package io.agentplatform.server;

import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.*;

import org.springframework.context.annotation.*;
import org.springframework.core.env.*;

@Configuration(proxyBeanMethods = false)
class ApiContract {
    @Bean
    OpenAPI publicApi(Environment environment) {
        boolean local = environment.acceptsProfiles(Profiles.of("local"));
        String name = local ? "localAccount" : "oidcBearer";
        var scheme =
                new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme(local ? "basic" : "bearer");
        if (!local) scheme.bearerFormat("JWT");
        return new OpenAPI()
                .info(new Info().title("Enterprise Agentic AI Platform").version("v1"))
                .components(new Components().addSecuritySchemes(name, scheme))
                .addSecurityItem(new SecurityRequirement().addList(name));
    }
}
