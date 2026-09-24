package com.rinsing.geomantia.harness.systems.provider.application;
import com.rinsing.geomantia.systems.provider.application.*;

import com.rinsing.geomantia.harness.systems.provider.application.ProviderConfigStore.Credentials;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderConnectionTesterTest {
    private HttpServer server;
    private int visionStatus;
    private String visionErrorBody;
    private int modelStatus;
    private volatile String visionRequestBody;

    @BeforeEach
    void startServer() throws Exception {
        visionStatus = 200;
        modelStatus = 200;
        visionErrorBody = "{\"error\":{\"message\":\"max_tokens must be at least 16\",\"code\":\"invalid_parameter\"}}";
        visionRequestBody = "";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/models", exchange -> reply(exchange, modelStatus,
                "{\"data\":[{\"id\":\"vision-model\"},{\"id\":\"glm-5.3-flash\"}]}"));
        server.createContext("/responses", exchange -> {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (!"Bearer test-key".equals(authorization)) {
                reply(exchange, 401, "{}");
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            visionRequestBody = body;
            if (!body.contains("input_image")) {
                reply(exchange, 400, "{}");
                return;
            }
            reply(exchange, visionStatus, visionStatus == 200 ? "{\"output_text\":\"OK\"}" : visionErrorBody);
        });
        server.createContext("/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            visionRequestBody = body;
            if (!body.contains("image_url")) {
                reply(exchange, 400, "{}");
                return;
            }
            reply(exchange, visionStatus,
                    "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"OK\"}}]}");
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void verifiesModelAvailabilityAndRealImageInput() {
        PlayerProviderConfig config = customConfig();

        var result = new ProviderConnectionTester().test(config, new Credentials("test-key", "stored"));

        assertEquals("connected_multimodal", result.state());
        assertTrue(result.connected());
        assertTrue(result.multimodal());
        assertTrue(visionRequestBody.contains("\"max_output_tokens\":16"));
    }

    @Test
    void rejectedVisionProbeDoesNotClaimTextOnlyCapability() {
        visionStatus = 400;

        var result = new ProviderConnectionTester().test(customConfig(), new Credentials("test-key", "stored"));

        assertEquals("error", result.state());
        assertTrue(result.message().contains("PROVIDER_VISION_HTTP_400"));
        assertTrue(result.message().contains("max_tokens must be at least 16"));
        assertTrue(result.connected());
        assertFalse(result.multimodal());
    }

    @Test
    void preservesHttpFailuresAndPlainTextButRedactsCredentialsAndBoundsDetails() {
        for (int status : new int[]{401, 403, 429, 500}) {
            visionStatus = status;
            visionErrorBody = "gateway failure test-key " + "x".repeat(2000);
            var result = new ProviderConnectionTester().test(customConfig(), new Credentials("test-key", "stored"));
            assertEquals("error", result.state());
            assertTrue(result.message().contains("HTTP_" + status));
            assertTrue(result.message().contains("gateway failure [redacted]"));
            assertFalse(result.message().contains("test-key"));
            assertTrue(result.message().length() < 1100);
        }
        modelStatus = 401;
        var result = new ProviderConnectionTester().test(customConfig(), new Credentials("test-key", "stored"));
        assertEquals("error", result.state());
        assertFalse(result.connected());
        assertTrue(result.message().startsWith("PROVIDER_HTTP_401:"));
    }

    @Test
    void refusesToConnectWithoutKey() {
        var result = new ProviderConnectionTester().test(customConfig(), new Credentials("", "none"));

        assertEquals("missing_key", result.state());
        assertFalse(result.connected());
    }

    @Test
    void callableClientSubmitsImageAndReturnsResponseText() {
        MultimodalProviderClient client = new MultimodalProviderClient();

        var result = client.analyze(customConfig(), new Credentials("test-key", "stored"),
                new byte[]{1, 2, 3}, "image/png", "Inspect this planning preview.");

        assertTrue(result.success());
        assertEquals("OK", result.outputText());
    }

    @Test
    void supportsChatCompletionsVisionProbeAndInvocation() {
        PlayerProviderConfig config = new PlayerProviderConfig(PlayerProviderConfig.CUSTOM, true,
                "http://127.0.0.1:" + server.getAddress().getPort(), "glm-5.3-flash",
                PlayerProviderConfig.CHAT_COMPLETIONS, 10);

        var test = new ProviderConnectionTester().test(config, new Credentials("test-key", "stored"));
        assertEquals("connected_multimodal", test.state());
        assertTrue(visionRequestBody.contains("\"messages\""));
        assertTrue(visionRequestBody.contains("\"max_tokens\":16"));
        assertFalse(visionRequestBody.contains("max_output_tokens"));

        var invocation = new MultimodalProviderClient().analyze(config,
                new Credentials("test-key", "stored"), new byte[]{1, 2, 3}, "image/png", "Inspect.");
        assertTrue(invocation.success());
        assertEquals("OK", invocation.outputText());
    }

    private PlayerProviderConfig customConfig() {
        return new PlayerProviderConfig(PlayerProviderConfig.CUSTOM, true,
                "http://127.0.0.1:" + server.getAddress().getPort(), "vision-model", 10);
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        assertEquals("Geomantia/0.1.0", exchange.getRequestHeaders().getFirst("User-Agent"));
        assertEquals(null, exchange.getRequestHeaders().getFirst("x-opencode-session"));
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
