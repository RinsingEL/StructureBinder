package com.rinsing.geomantia.systems.provider.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderPlanningToolGatewayTest {
    @TempDir
    Path serverDirectory;
    private HttpServer server;
    private final AtomicReference<JsonObject> received = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/realm/city/plan_d3", exchange -> {
            received.set(read(exchange));
            reply(exchange, "{\"ok\":true,\"artifacts\":{\"preview\":\"run_a/preview.png\"}}");
        });
        server.createContext("/realm/city/prepare_d4_blueprint_context", exchange -> {
            received.set(read(exchange));
            reply(exchange, "{\"ok\":true,\"contextId\":\"sha256:test\"}");
        });
        server.createContext("/realm/patch_explorer/open", exchange -> {
            received.set(read(exchange));
            reply(exchange, "{\"ok\":true,\"sessionId\":\"pex_test\"}");
        });
        server.createContext("/realm/t4/patch_planning/select_capital", exchange -> {
            received.set(read(exchange));
            reply(exchange, "{\"ok\":true,\"status\":\"open\"}");
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void injectsActiveScopeAndAttachesReturnedPreview() throws Exception {
        Path preview = serverDirectory.resolve("realm_debug/run_a/preview.png");
        Files.createDirectories(preview.getParent());
        Files.write(preview, new byte[]{1, 2, 3, 4});
        ProviderPlanningToolGateway gateway = gateway();

        JsonArray output = gateway.execute("city_plan_d3", new JsonObject()).getAsJsonArray();

        assertEquals("run_a", received.get().get("runId").getAsString());
        assertEquals("city_a", received.get().get("citySeedId").getAsString());
        assertEquals("input_text", output.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("input_image", output.get(1).getAsJsonObject().get("type").getAsString());
    }

    @Test
    void rejectsAFunctionCallThatTriesToSwitchRuns() {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("runId", "other_run");

        assertThrows(IllegalArgumentException.class,
                () -> gateway().execute("city_plan_d3", arguments));
    }

    @Test
    void delegatesCatalogOwnershipToTheSharedHostEndpoint() throws Exception {
        gateway().execute("city_prepare_d4_blueprint_context", new JsonObject());
        JsonObject actual = received.get();
        assertEquals("run_a", actual.get("runId").getAsString());
        assertEquals("city_a", actual.get("citySeedId").getAsString());
        assertEquals(2, actual.size());
    }

    @Test
    void inlineImagesDoNotConsumeTheDecisionTextBudget() throws Exception {
        server.removeContext("/realm/city/plan_d3");
        String imageData = "A".repeat(7 * 1024 * 1024);
        server.createContext("/realm/city/plan_d3", exchange -> {
            read(exchange);
            assertEquals("true", exchange.getRequestHeaders().getFirst("X-Geomantia-Agent-View"));
            reply(exchange, "{\"ok\":true,\"presentation\":\"planning_decision_view.v0.1\",\"imageEvidence\":[{\"type\":\"image\",\"mimeType\":\"image/png\",\"data\":\"" + imageData + "\"}]}");
        });
        JsonArray output = gateway().execute("city_plan_d3", new JsonObject()).getAsJsonArray();
        assertEquals(2, output.size());
        assertEquals("data:image/png;base64," + imageData, output.get(1).getAsJsonObject().get("image_url").getAsString());
    }

    @Test
    void locksRealmPatchExplorerToTheDiscoveredT2Scope() throws Exception {
        JsonObject state = new JsonObject();
        var step = new ProviderPlanningDiscovery.PlanningStep(ProviderPlanningDiscovery.Stage.T2,
                "run_a", "realm_a", "", "patch_explorer_open", state,
                serverDirectory.resolve("realm_debug/run_a"), java.util.List.of(), "identity");
        ProviderPlanningToolGateway gateway = ProviderPlanningToolGateway.forStep(
                server.getAddress().getPort(), serverDirectory, step);

        gateway.execute("patch_explorer_open", new JsonObject());

        assertEquals("run_a", received.get().get("runId").getAsString());
        assertEquals("realm_t2", received.get().get("scopeType").getAsString());
        assertEquals("realm_a", received.get().get("scopeId").getAsString());
        assertEquals("realm_a", received.get().get("realmId").getAsString());
    }

    @Test
    void rejectsRealmPatchExplorerScopeSwitching() {
        JsonObject state = new JsonObject();
        var step = new ProviderPlanningDiscovery.PlanningStep(ProviderPlanningDiscovery.Stage.T4,
                "run_a", "realm_a", "", "patch_explorer_open", state,
                serverDirectory.resolve("realm_debug/run_a"), java.util.List.of(), "identity");
        ProviderPlanningToolGateway gateway = ProviderPlanningToolGateway.forStep(
                server.getAddress().getPort(), serverDirectory, step);
        JsonObject arguments = new JsonObject();
        arguments.addProperty("scopeId", "realm_b");

        assertThrows(IllegalArgumentException.class,
                () -> gateway.execute("patch_explorer_open", arguments));
    }

    @Test
    void resolvesArtifactBackedT4SessionAndForwardsFrozenSelection() throws Exception {
        Path sessionPath = serverDirectory.resolve(
                "realm_debug/run_a/realm_t4_patch_planning_t4_session/planning_session.json");
        Files.createDirectories(sessionPath.getParent());
        Files.writeString(sessionPath, """
                {"runId":"run_a","realmId":"realm_a","planningSessionId":"t4_session"}
                """);
        var step = new ProviderPlanningDiscovery.PlanningStep(ProviderPlanningDiscovery.Stage.T4,
                "run_a", "realm_a", "", "realm_t4_patch_planning_select_capital", new JsonObject(),
                serverDirectory.resolve("realm_debug/run_a"), java.util.List.of(), "identity");
        ProviderPlanningToolGateway gateway = ProviderPlanningToolGateway.forStep(
                server.getAddress().getPort(), serverDirectory, step);
        JsonObject arguments = new JsonObject();
        arguments.addProperty("planningSessionId", "t4_session");
        arguments.addProperty("patchSelectionRef", "psel_1234");

        gateway.execute("realm_t4_patch_planning_select_capital", arguments);

        assertEquals("t4_session", received.get().get("planningSessionId").getAsString());
        assertEquals("psel_1234", received.get().get("patchSelectionRef").getAsString());
    }

    @Test
    void rejectsDisplayedCandidateIdBeforeT4SelectionEndpoint() throws Exception {
        Path sessionPath = serverDirectory.resolve(
                "realm_debug/run_a/realm_t4_patch_planning_t4_session/planning_session.json");
        Files.createDirectories(sessionPath.getParent());
        Files.writeString(sessionPath, "{\"runId\":\"run_a\",\"realmId\":\"realm_a\"}");
        var step = new ProviderPlanningDiscovery.PlanningStep(ProviderPlanningDiscovery.Stage.T4,
                "run_a", "realm_a", "", "realm_t4_patch_planning_select_capital", new JsonObject(),
                serverDirectory.resolve("realm_debug/run_a"), java.util.List.of(), "identity");
        ProviderPlanningToolGateway gateway = ProviderPlanningToolGateway.forStep(
                server.getAddress().getPort(), serverDirectory, step);
        JsonObject arguments = new JsonObject();
        arguments.addProperty("planningSessionId", "t4_session");
        arguments.addProperty("patchSelectionRef", "VALLEY-01");

        JsonObject result = gateway.execute(
                "realm_t4_patch_planning_select_capital", arguments).getAsJsonObject();

        assertEquals(false, result.get("ok").getAsBoolean());
        assertEquals("PATCH_SELECTION_REF_REQUIRED", result.get("errorCode").getAsString());
    }

    private ProviderPlanningToolGateway gateway() {
        return new ProviderPlanningToolGateway(server.getAddress().getPort(),
                serverDirectory, "run_a", "city_a");
    }

    private static JsonObject read(HttpExchange exchange) throws IOException {
        return JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static void reply(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
