package com.rinsing.geomantia.platform.http;

import com.rinsing.geomantia.systems.provider.application.PlanningSessionService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class PlanningOwnershipHttpTest {
    @TempDir Path root;
    @Test void legacyMutationIsBlockedButOwnerAndReadOnlyStatusStillWork() throws Exception {
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var service = new PlanningSessionService(root, root, http.getAddress().getPort(), 42)) {
            var controller = new PlanningSessionHttpController(() -> service, () -> "Test World");
            for (String path : new String[]{"/realm/city/submit_d4_blueprint", "/realm/city/design_queue/status"}) {
                http.createContext(path, exchange -> {
                    byte[] body = "{\"ok\":true}".getBytes(); exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body); exchange.close();
                }).getFilters().add(controller.ownershipFilter());
            }
            http.createContext("/planning/", controller::handle);
            http.start();
            URI base = URI.create("http://127.0.0.1:" + http.getAddress().getPort());
            HttpClient client = HttpClient.newHttpClient();
            assertEquals(200, post(client, base, "/realm/city/submit_d4_blueprint", "").statusCode());
            String token = service.acquireEmbedded();
            assertEquals(409, post(client, base, "/realm/city/submit_d4_blueprint", "").statusCode());
            assertEquals(409, post(client, base, "/realm/city/submit_d4_blueprint", "stale").statusCode());
            assertEquals(200, post(client, base, "/realm/city/submit_d4_blueprint", token).statusCode());
            assertEquals(200, post(client, base, "/realm/city/design_queue/status", "").statusCode());
            assertTrue(post(client, base, "/planning/lobby", "").body().contains("Test World"));
            service.release(token);
            assertEquals(200, post(client, base, "/realm/city/submit_d4_blueprint", "").statusCode());
        } finally { http.stop(0); }
    }
    private static HttpResponse<String> post(HttpClient client, URI base, String path, String token) throws Exception {
        return client.send(HttpRequest.newBuilder(base.resolve(path))
                .header("X-Geomantia-Planning-Token", token)
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
    }
}
