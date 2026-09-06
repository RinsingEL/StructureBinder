package com.rinsing.geomantia.systems.provider.application;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ProviderRevisionLoopTest {
    @TempDir Path root;

    @Test void asyncFailureWakesAnotherRevisionWithoutPollingDuplicates() throws Exception { loop(false); }
    @Test void failureBeforeSuccessfulTurnReturnsAlsoWakesAnotherRevision() throws Exception { loop(true); }

    private void loop(boolean immediateFailure) throws Exception {
        Path run = fixture();
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger attempt = new AtomicInteger(3);
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/realm/city/prepare_d4_blueprint_context", exchange -> {
            byte[] response = ("{\"ok\":true,\"cityBlueprintContext\":{\"contextId\":\"ctx\","
                    + "\"d3ReviewPackage\":{\"preview\":\"map.png\"}}}").getBytes(StandardCharsets.UTF_8);
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        http.start();
        ProviderConfigStore store = new ProviderConfigStore(root.resolve("config"));
        store.save(new PlayerProviderConfig("custom", true, "http://127.0.0.1", "test-model",
                "chat_completions", 10, "hermes"), "test-key-not-a-real-secret", false);
        ProviderAgentClient client = (config, credentials, session, state, images, tools, executor, listener) -> {
            calls.incrementAndGet();
            try {
                int next = attempt.incrementAndGet();
                job(run, next, immediateFailure ? next - 1 : next - 2, "ctx");
                queue(run, immediateFailure ? "needs_agent" : "compiling", "submission");
            } catch (Exception error) { throw new RuntimeException(error); }
            return new DeepSeekToolLoopClient.LoopResult(true, "completed", "", 1, "");
        };
        try (PlayerProviderAgentRunner runner = new PlayerProviderAgentRunner(store, client, client,
                ignored -> {}, ignored -> {})) {
            // Drive the real tick/turn-completion code synchronously, without scheduler timing sleeps.
            set(runner, "serverDirectory", root);
            set(runner, "debugRoot", root);
            set(runner, "apiPort", http.getAddress().getPort());
            set(runner, "discovery", new ProviderPlanningDiscovery(root, 42));
            var tick = PlayerProviderAgentRunner.class.getDeclaredMethod("tick");
            tick.setAccessible(true);
            for (int round = 1; round <= 3; round++) {
                tick.invoke(runner);
                assertEquals(round, calls.get(), runner.status().toString());
                if (!immediateFailure) {
                    for (int poll = 0; poll < 3; poll++) tick.invoke(runner);
                    assertEquals(round, calls.get(), "WAITING must not call the model");
                    job(run, attempt.get(), attempt.get() - 1, "ctx");
                    queue(run, "needs_agent", "failed");
                }
            }
        } finally { http.stop(0); }
    }

    @Test void revisionIdentityIgnoresTimestampRefreshButChangesForNewAttemptOrBudgetContext() throws Exception {
        Path run = fixture();
        var discovery = new ProviderPlanningDiscovery(root, 42);
        var before = discovery.nextStep();
        queue(run, "needs_agent", "later");
        job(run, 3, 2, "ctx");
        assertEquals(before.semanticIdentity(), discovery.nextStep().semanticIdentity());
        job(run, 4, 2, "ctx");
        var next = discovery.nextStep();
        assertNotEquals(before.semanticIdentity(), next.semanticIdentity());
        job(run, 4, 3, "ctx");
        var failed = discovery.nextStep();
        assertNotEquals(next.semanticIdentity(), failed.semanticIdentity());
        job(run, 4, 3, "new-context");
        assertNotEquals(failed.semanticIdentity(), discovery.nextStep().semanticIdentity());
    }

    private Path fixture() throws Exception {
        Path run = root.resolve("run");
        write(run, "world_survey_context.json", "{\"worldSeed\":\"42\",\"sealed\":true,\"scanBounds\":{\"centerBlockX\":0,\"centerBlockZ\":0,\"planningRadiusBlocks\":8192}}");
        write(run, "world_patch_map.json", "{}");
        write(run, "world_survey_manifest.json", "{\"createdAt\":\"2026-09-06T00:00:00Z\"}");
        write(run, "realm_profiles.json", "[{\"realmId\":\"realm\"}]");
        write(run, "realm_coordinate_selections.json", "[{\"realmId\":\"realm\"}]");
        write(run, "t3_report.json", "{}");
        write(run, "realm_territory_map.json", "{}");
        write(run, "city_seed_registry.json", "{\"citySeeds\":[{\"citySeedId\":\"city\",\"realmId\":\"realm\",\"role\":\"capital\"}]}");
        Files.write(root.resolve("map.png"), new byte[]{1});
        queue(run, "needs_agent", "first");
        job(run, 3, 2, "ctx");
        return run;
    }
    private void queue(Path run, String status, String stamp) throws Exception {
        write(run, "automation/city_design_queue.json", "{\"status\":\"" + status + "\",\"updatedAt\":\"" + stamp
                + "\",\"currentCitySeedId\":\"city\",\"nextAction\":\""
                + (status.equals("needs_agent") ? "city_submit_d4_blueprint" : "city_post_d4_auto_compile_status")
                + "\",\"items\":[{\"citySeedId\":\"city\",\"realmId\":\"realm\",\"status\":\"" + status + "\"}]}");
    }
    private void job(Path run, int attempt, int failures, String context) throws Exception {
        write(run, "automation/post_d4/city.json", "{\"attempt\":" + attempt + ",\"updatedAt\":\"" + System.nanoTime() + "\"}");
        write(run, "city_test_runs/city/steps/blueprint/city_blueprint_failure_budget.json",
                "{\"contextId\":\"" + context + "\",\"failureCount\":" + failures + "}");
    }
    private static void set(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    private static void write(Path root, String relative, String json) throws Exception {
        Path path = root.resolve(relative); Files.createDirectories(path.getParent()); Files.writeString(path, json);
    }
}
