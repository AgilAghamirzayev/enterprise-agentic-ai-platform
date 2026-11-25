package io.agentplatform.store;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;

import java.util.regex.Pattern;

/** The durable execution boundary carries the W3C parent and sampling flag only. */
public final class RunTraceContext {
    private static final Pattern TRACEPARENT =
            Pattern.compile("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");

    private RunTraceContext() {}

    public static String current() {
        var span = Span.current().getSpanContext();
        return span.isValid()
                ? "00-"
                        + span.getTraceId()
                        + "-"
                        + span.getSpanId()
                        + "-"
                        + span.getTraceFlags().asHex()
                : null;
    }

    public static Scope restore(String traceparent) {
        if (traceparent == null || !TRACEPARENT.matcher(traceparent).matches()) return Scope.noop();
        var context =
                SpanContext.createFromRemoteParent(
                        traceparent.substring(3, 35),
                        traceparent.substring(36, 52),
                        TraceFlags.fromHex(traceparent, 53),
                        TraceState.getDefault());
        return context.isValid() ? Span.wrap(context).makeCurrent() : Scope.noop();
    }
}
