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
    void injectsManagedCatalogsInsteadOfAcceptingModelSelectedPaths() throws Exception {
        Path source = serverDirectory.resolve("config/structureTemplate/terrasense/release_bundle");
        Files.createDirectories(source);
        Files.writeString(source.resolve("TerraSenseStructureProfileSource.official.json"),
                "{\"schema\":\"terrasense_structure_profile_source\",\"sourceType\":\"structure_profile_jsonl\"}");
        Files.writeString(source.resolve("StructureProfile.jsonl"), "{}\n");
        Files.writeString(source.resolve("StructureVocabulary.snapshot.json"), "{}");
        Files.writeString(source.resolve("template_catalog.json"), "{\"schema\":\"city_template_catalog\"}");
        Files.writeString(source.resolve("blueprint_reference_catalog.json"),
                "{\"schema\":\"city_blueprint_reference_catalog\"}");
        JsonObject arguments = new JsonObject();
        JsonObject hostile = new JsonObject();
        hostile.addProperty("path", "C:/not/model/controlled.json");
        arguments.add("templateCatalogSource", hostile);

        gateway().execute("city_prepare_d4_blueprint_context", arguments);

        JsonObject actual = received.get();
        assertTrue(actual.has("terrasenseProfileSource"));
        assertTrue(actual.has("blueprintReferenceCatalog"));
        assertTrue(actual.getAsJsonObject("templateCatalogSource").get("catalogPath")
                .getAsString().endsWith("template_catalog.json"));
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
