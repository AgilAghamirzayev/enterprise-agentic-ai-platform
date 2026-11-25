package io.agentplatform.domain;

import java.util.regex.Pattern;

/** A reference to one immutable registration. Both fields are required for lookup. */
public record AgentDescriptor(String name, String version) {
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,62}");
    private static final Pattern VERSION = Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?");

    public AgentDescriptor {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid agent name");
        }
        if (version == null || version.length() > 64 || !VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("Invalid agent version");
        }
    }
}
