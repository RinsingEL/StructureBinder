package com.rinsing.geomantia.systems.provider.application;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PlanningSessionServiceTest {
    @TempDir Path root;
    @Test void leaseCannotBeStolenDuringLongOperationAndExpiredTokenCannotResume() throws Exception {
        class MutableClock extends Clock {
            long millis;
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return Instant.ofEpochMilli(millis); }
            public long millis() { return millis; }
        }
        var clock = new MutableClock(); var lease = new PlanningLease(clock);
        String first = lease.acquire("external");
        try (var pin = lease.enter(first)) {
            clock.millis = 600_000;
            assertThrows(IllegalStateException.class, () -> lease.acquire("embedded"));
            assertThrows(IllegalStateException.class, () -> lease.enter("wrong-token"));
            assertThrows(IllegalStateException.class, () -> lease.release(first));
        }
        lease.release(first);
        String second = lease.acquire("embedded");
        assertNotEquals(first, second);
        assertThrows(IllegalStateException.class, () -> lease.touch(first));
        clock.millis += 120_001;
        assertNotEquals(second, lease.acquire("external"));
        try (var legacyRequest = new PlanningLease().enter("")) { assertNotNull(legacyRequest); }
    }
    @Test void externalPipelineExecutesWOnceReturnsActualImageAndRestoresFromArtifacts() throws Exception {
        AtomicInteger scans = new AtomicInteger(), decisions = new AtomicInteger();
        Path run = root.resolve("provider_2a_r8192");
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/realm/w/refresh", exchange -> {
            scans.incrementAndGet();
            try { sealed(run); } catch (Exception ex) { throw new RuntimeException(ex); }
            byte[] bytes = "{\"ok\":true}".getBytes(); exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        }); http.start();
        byte[] image = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j2ioAAAAASUVORK5CYII=");
        try (var service = new PlanningSessionService(root, root, http.getAddress().getPort(), 42,
                (step, gateway) -> {
                    Path preview = run.resolve("preview.png"); Files.write(preview, image);
                    return new PreparedPlanningTurn(step.state(), List.of(preview), List.of("realm_t1_prepare"),
                        new PlanningTurnControl((name, args) -> {
                            decisions.incrementAndGet();
                            Files.writeString(run.resolve("realm_profiles.json"), "[{\"realmId\":\"a\"}]");
                            JsonObject result = new JsonObject(); result.addProperty("ok", true); return result;
                        }, step.state()));
                })) {
            assertEquals("W", service.snapshot().get("stage").getAsString());
            assertEquals(0, scans.get()); // Lobby must not start W.
            JsonObject start = service.resume("external-test", "", false);
            String token = start.get("leaseToken").getAsString();
            JsonObject task = awaitTask(service, token);
            assertEquals(1, scans.get());
            assertArrayEquals(image, Base64.getDecoder().decode(task.getAsJsonArray("imageEvidence").get(0).getAsJsonObject().get("data").getAsString()));
            assertThrows(IllegalStateException.class, service::acquireEmbedded);
            assertThrows(IllegalStateException.class, () -> service.resume("external-test", "wrong", false));
            assertThrows(IllegalStateException.class, () -> service.action(token, "stale", "one", "realm_t1_prepare", new JsonObject()));
            String taskId = task.get("taskId").getAsString();
            JsonObject result = service.action(token, taskId, "one", "realm_t1_prepare", new JsonObject());
            assertTrue(result.get("taskFinished").getAsBoolean());
            assertEquals(result, service.action(token, taskId, "one", "realm_t1_prepare", new JsonObject()));
            assertEquals(1, decisions.get());
            assertThrows(IllegalArgumentException.class, () -> service.action(token, taskId, "one", "different", new JsonObject()));
            service.release(token);
        } finally { http.stop(0); }
        try (var restored = new PlanningSessionService(root, root, 1, 42)) {
            assertEquals("T2", restored.snapshot().get("stage").getAsString());
            assertEquals("", restored.snapshot().get("owner").getAsString());
        }
    }
    @Test void artifactsReadActualBytesAndRejectTraversalAndSymlink() throws Exception {
        Path run = Files.createDirectory(root.resolve("run"));
        Files.writeString(root.resolve("outside.txt"), "private");
        Files.writeString(run.resolve("report.json"), "x".repeat(24001));
        var page = PlanningArtifacts.read(run, "text", "report.json", "", 24000);
        assertEquals("x", page.get("text").getAsString());
        assertFalse(page.get("hasMore").getAsBoolean());
        assertThrows(IllegalArgumentException.class, () -> PlanningArtifacts.read(run, "text", "../outside.txt", "", 0));
        assertThrows(IllegalArgumentException.class, () -> PlanningArtifacts.read(run, "text", root.resolve("outside.txt").toString(), "", 0));
        try { Files.createSymbolicLink(run.resolve("escape.txt"), root.resolve("outside.txt")); }
        catch (FileSystemException unsupported) { return; }
        assertThrows(IllegalArgumentException.class, () -> PlanningArtifacts.read(run, "text", "escape.txt", "", 0));
    }
    @Test void waitingReportsProgramBlockAndCompletionWithoutResendingImages() throws Exception {
        Path run = root.resolve("provider_2a_r8192"); sealed(run);
        Files.writeString(run.resolve("realm_profiles.json"), "[{\"realmId\":\"a\"}]");
        Files.writeString(run.resolve("realm_coordinate_selections.json"), "[{\"realmId\":\"a\"}]");
        Files.writeString(run.resolve("t3_report.json"), "{}");
        Files.writeString(run.resolve("realm_territory_map.json"), "{}");
        Files.writeString(run.resolve("city_seed_registry.json"), "{\"citySeeds\":[{\"realmId\":\"a\",\"citySeedId\":\"city_a\",\"role\":\"capital\"}]}");
        Files.createDirectories(run.resolve("automation"));
        Path queue = run.resolve("automation/city_design_queue.json");
        Files.writeString(queue, queue("running"));
        try (var service = new PlanningSessionService(root, root, 1, 42)) {
            JsonObject first = service.resume("waiting-test", "", false);
            String token = first.get("leaseToken").getAsString();
            for (int i = 0; i < 100 && "running".equals(service.snapshot().get("status").getAsString()); i++) Thread.sleep(10);
            JsonObject waiting = service.await(token, first.get("cursor").getAsString(), 0);
            assertFalse(waiting.has("imageEvidence"));
            Files.writeString(queue, queue("needs_agent"));
            JsonObject blocked = service.await(token, waiting.get("cursor").getAsString(), 1);
            assertEquals("blocked", blocked.get("status").getAsString());
            assertTrue(blocked.get("error").getAsString().contains("PLANNING_HOST_BLOCKED"));
            Files.writeString(queue, queue("waiting_for_generation"));
            assertEquals("complete", service.await(token, blocked.get("cursor").getAsString(), 1).get("status").getAsString());
        }
    }
    private static String queue(String status) {
        return "{\"status\":\"" + status + "\",\"currentCitySeedId\":\"city_a\",\"nextAction\":\"city_post_d4_auto_compile_retry\",\"items\":[{\"citySeedId\":\"city_a\",\"realmId\":\"a\",\"status\":\"" + status + "\"}]}";
    }
    private JsonObject awaitTask(PlanningSessionService service, String token) throws Exception {
        for (int i = 0; i < 100; i++) {
            JsonObject view = service.view(token);
            if (view.has("taskId")) return view;
            if ("blocked".equals(view.get("status").getAsString())) fail(view.toString());
            Thread.sleep(20);
        }
        throw new AssertionError("Task did not prepare");
    }
    private static void sealed(Path run) throws Exception {
        Files.createDirectories(run);
        Files.writeString(run.resolve("world_survey_context.json"), "{\"worldSeed\":\"42\",\"sealed\":true,\"scanBounds\":{\"centerBlockX\":0,\"centerBlockZ\":0,\"planningRadiusBlocks\":8192}}");
        Files.writeString(run.resolve("world_survey_manifest.json"), "{\"createdAt\":\"2026-09-18T00:00:00Z\",\"status\":\"sealed\"}");
        Files.writeString(run.resolve("world_patch_map.json"), "{}");
        Files.writeString(run.resolve("world_continent_map.json"), "{}");
    }
}
