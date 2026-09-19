package com.rinsing.geomantia.systems.provider.application;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

class HarnessAgentClientTest {
    @TempDir Path root;

    @AfterEach void releaseTemporaryExecutable() throws Exception {
        Path executable = root.resolve("config/geomantia/runtime")
                .resolve(HarnessPortableRuntime.DIRECTORY_NAME).resolve("node.exe");
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        // Windows can retain the image mapping briefly after the process has exited.
        // Keep TempDir cleanup enabled and fail if the executable remains locked.
        while (true) {
            try { Files.deleteIfExists(executable); return; }
            catch (FileSystemException locked) {
                if (System.nanoTime() >= deadline) throw locked;
                Thread.sleep(25);
            }
        }
    }

    @Test @Timeout(60)
    void bundledRuntimeExecutesHostToolAndSendsNativeImagesWithoutSystemDependencies() throws Exception {
        List<JsonObject> requests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.add(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            String delta = requests.size() == 1
                    ? "{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"city_d4_preview\",\"arguments\":\"{}\"}}]}"
                    : "{\"content\":\"预览可见\"}";
            String response = "data: {\"id\":\"test\",\"choices\":[{\"index\":0,\"delta\":" + delta + ",\"finish_reason\":null}]}\n\n"
                    + "data: {\"id\":\"test\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\""
                    + (requests.size() == 1 ? "tool_calls" : "stop") + "\"}]}\n\ndata: [DONE]\n\n";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        HarnessAgentClient client = new HarnessAgentClient();
        try {
            Path image = root.resolve("preview.png");
            javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(24, 24, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", image.toFile());
            client.start(root, root.resolve("debug"), 0);
            var config = new PlayerProviderConfig("custom", true, "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "deepseek-v4.1-flash", "chat_completions", 20, "harness");
            List<AgentActivityEvent> activity = new ArrayList<>();
            var result = client.run(config, new ProviderConfigStore.Credentials("test-only", "test"), "isolated-harness-test",
                    new JsonObject(), List.of(image), List.of("city_d4_preview"), (name, args) -> {
                        assertEquals("city_d4_preview", name);
                        return HarnessAgentClient.promptContent(new JsonObject(), List.of(image));
                    }, activity::add);
            assertTrue(result.success(), () -> result + " " + activity + " " + runtimeLog());
            assertEquals(1, result.toolCalls());
            assertEquals(2, requests.size());
            assertEquals(32768, requests.get(0).get("max_tokens").getAsInt());
            assertEquals(1, imageCount(requests.get(0)));
            assertEquals(1, imageCount(requests.get(1)));
            assertTrue(activity.stream().anyMatch(e -> e.kind().equals("tool")));
            String evidence = Files.readString(root.resolve("debug/.harness/request-evidence.jsonl"));
            assertTrue(evidence.contains("sha256"));
            assertFalse(evidence.contains("test-only"));
            assertFalse(evidence.contains("base64"));
            Path installed = new HarnessPortableRuntime().ensureInstalled(root, ignored -> fail("Must reuse installed runtime"));
            assertTrue(Files.isRegularFile(installed.resolve("node.exe")));
            assertFalse(Files.exists(installed.resolve("python")));
        } finally { client.close(); server.stop(0); }
    }

    private static int imageCount(JsonObject body) {
        int count = 0;
        for (JsonElement message : body.getAsJsonArray("messages")) {
            JsonElement content = message.getAsJsonObject().get("content");
            if (content != null && content.isJsonArray()) for (JsonElement part : content.getAsJsonArray())
                if ("image_url".equals(part.getAsJsonObject().get("type").getAsString())) count++;
        }
        return count;
    }

    private String runtimeLog() {
        try { return Files.readString(root.resolve("debug/.harness/runtime.log")); }
        catch (Exception ignored) { return ""; }
    }
}
