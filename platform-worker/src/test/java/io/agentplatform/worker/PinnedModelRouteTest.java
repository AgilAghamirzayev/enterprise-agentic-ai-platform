package io.agentplatform.worker;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentplatform.plan.DeploymentModels;
import io.agentplatform.spi.ModelBinding;
import io.agentplatform.spi.ModelRequest;
import io.agentplatform.spi.ModelResult;
import io.temporal.failure.ApplicationFailure;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

class PinnedModelRouteTest {
    private final ModelRequest req = new ModelRequest("system", "request", 128);

    @Test
    void resolvesOrderedVersionedProfilesAndUsesEachAttemptsOwnLimits() throws Exception {
        var json = new ObjectMapper();
        var models =
                new ModelRoutes(
                        DeploymentModels.from("demo", "", "backup,last"),
                        ignored -> {
                            throw new UncheckedIOException(new IOException("transient"));
                        },
                        null,
                        List.of(
                                new ModelBinding(
                                        "backup",
                                        ignored -> {
                                            throw new UncheckedIOException(
                                                    new IOException("transient"));
                                        }),
                                new ModelBinding(
                                        "last", effective -> new ModelResult("ok", 1, 1))));
        var root =
                json.readTree(
                        """
                        {"provider":"demo","model":"echo","parameters":{"maxOutputTokens":32,"temperature":0.2},
                         "timeoutSeconds":5,"fallbackProfiles":["alternate:1.0.0","third:2.0.0"]}
                        """);
        var alternate =
                json.readTree(
                        """
                        {"provider":"demo","model":"backup","parameters":{"maxOutputTokens":16,"temperature":0.5},"timeoutSeconds":2}
                        """);
        var third =
                json.readTree(
                        """
                        {"provider":"demo","model":"last","parameters":{"maxOutputTokens":300}}
                        """);
        var targets =
                PinnedModelRoute.resolve(
                        models,
                        req,
                        root,
                        ref ->
                                switch (ref) {
                                    case "alternate:1.0.0" -> alternate;
                                    case "third:2.0.0" -> third;
                                    default -> throw new AssertionError();
                                });
        var reservations = new ArrayList<String>();
        var result =
                models.complete(
                        targets,
                        (id, request) ->
                                reservations.add(
                                        id
                                                + " "
                                                + request.maxOutputTokens()
                                                + " "
                                                + request.temperature()));
        assertEquals("ok", result.text());
        assertEquals(List.of("echo 32 0.2", "backup 16 0.5", "last 32 0.2"), reservations);
        assertEquals(Duration.ofSeconds(2), targets.get(1).timeout());
        var drifted =
                json.readTree(
                        """
                        {"provider":"demo","model":"echo","policyDigest":"changed"}
                        """);
        assertThrows(
                ApplicationFailure.class,
                () -> PinnedModelRoute.resolve(models, req, drifted, ref -> fail()));
    }

    @Test
    void reachesTotalDeadlineAndInterruptsBlockedProviderWithoutStartingFallback()
            throws Exception {
        var interrupted = new CountDownLatch(1);
        var routes =
                new ModelRoutes(
                        DeploymentModels.from("demo", "", "another"),
                        request -> {
                            try {
                                new CountDownLatch(1).await();
                            } catch (InterruptedException expected) {
                                interrupted.countDown();
                                Thread.currentThread().interrupt();
                            }
                            throw new IllegalStateException("interrupted");
                        },
                        null,
                        List.of(
                                new ModelBinding(
                                        "another", ignored -> fail("fallback must not start"))));
        var targets =
                List.of(
                        new PinnedModelRoute("echo", req, Duration.ofMillis(300)),
                        new PinnedModelRoute("another", req, Duration.ofSeconds(2)));
        var reservations = new ArrayList<String>();
        assertThrows(
                IllegalStateException.class,
                () -> routes.complete(targets, (alias, request) -> reservations.add(alias)));
        assertEquals(List.of("echo"), reservations);
        assertTrue(interrupted.await(2, TimeUnit.SECONDS));
    }
}
