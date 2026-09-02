package com.rinsing.geomantia.systems.provider.application;

import com.rinsing.geomantia.systems.provider.application.ProviderConfigStore.Credentials;
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

    @BeforeEach
    void startServer() throws Exception {
        visionStatus = 200;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/models", exchange -> reply(exchange, 200,
                "{\"data\":[{\"id\":\"vision-model\"}]}"));
        server.createContext("/responses", exchange -> {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (!"Bearer test-key".equals(authorization)) {
                reply(exchange, 401, "{}");
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (!body.contains("input_image")) {
                reply(exchange, 400, "{}");
                return;
            }
            reply(exchange, visionStatus, "{\"output_text\":\"OK\"}");
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
    }

    @Test
    void distinguishesReachableTextOnlyEndpoint() {
        visionStatus = 400;

        var result = new ProviderConnectionTester().test(customConfig(), new Credentials("test-key", "stored"));

        assertEquals("connected_text_only", result.state());
        assertTrue(result.connected());
        assertFalse(result.multimodal());
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

    private PlayerProviderConfig customConfig() {
        return new PlayerProviderConfig(PlayerProviderConfig.CUSTOM, true,
                "http://127.0.0.1:" + server.getAddress().getPort(), "vision-model", 10);
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
