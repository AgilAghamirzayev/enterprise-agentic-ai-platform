package io.agentplatform.boot;

import java.lang.annotation.*;
import org.springframework.stereotype.Component;

/** Registers a typed Agent bean in the local catalog under the given immutable version. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface AiAgent {
    String name();
    String version();
}
