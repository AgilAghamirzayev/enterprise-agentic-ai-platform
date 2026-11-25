package io.agentplatform.boot;

import org.springframework.stereotype.Component;

import java.lang.annotation.*;

@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Component
public @interface PlatformTool {
    String name();

    String version();

    String[] tenants();

    boolean readOnly() default true;
}
