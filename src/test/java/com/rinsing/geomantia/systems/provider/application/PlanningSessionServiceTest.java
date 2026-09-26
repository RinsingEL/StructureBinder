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
    @Test void rolesShareOneLeaseAndEscalationInvalidatesFlashCredential() throws Exception {
        sealed(root.resolve("provider_2a_r8192"));
        try(var service=new PlanningSessionService(root,root,1,42,(step,gateway)->new PreparedPlanningTurn(step.state(),List.of(),List.of("realm_t1_prepare"),
                new PlanningTurnControl((name,args)->JsonParser.parseString("{ok:true}"))))) {
            assertEquals("waiting_for_role",service.resume("advanced-agent","",false,PlanningRole.ADVANCED).get("status").getAsString());
            assertEquals("",service.snapshot().get("owner").getAsString());
            String token=service.resume("flash-agent-one","",false,PlanningRole.FLASH).get("leaseToken").getAsString();
            var task=awaitTask(service,token);
            assertThrows(IllegalStateException.class,()->service.resume("flash-agent-two","",false,PlanningRole.FLASH));
            String id=task.get("taskId").getAsString();
            service.escalate(token,id,"No available terrain satisfies both constraints");
            assertEquals("ADVANCED",service.snapshot().get("requiredRole").getAsString());
            assertThrows(IllegalStateException.class,()->service.action(token,id,"old","realm_t1_prepare",new JsonObject()));
            assertThrows(IllegalStateException.class,()->service.enter(token));
            assertEquals("waiting_for_role",service.resume("flash-agent-one",token,false,PlanningRole.FLASH).get("status").getAsString());
            String next=service.resume("advanced-agent","",false,PlanningRole.ADVANCED).get("leaseToken").getAsString();
            assertNotEquals(token,next);awaitTask(service,next);service.release(next);
        }
        try(var restored=new PlanningSessionService(root,root,1,42)) {
            assertEquals("ADVANCED",restored.snapshot().get("requiredRole").getAsString());
        }
    }
    @Test void staleUnfinishedTaskIsDiscardedWhenArtifactsAdvanceStage() throws Exception {
        Path run = root.resolve("provider_2a_r8192"); sealed(run);
        AtomicInteger preparedCount = new AtomicInteger(), executed = new AtomicInteger();
        try (var service = new PlanningSessionService(root,root,1,42,(step,gateway) -> {
            preparedCount.incrementAndGet();
            return new PreparedPlanningTurn(step.state(),List.of(),List.of("realm_t1_prepare"),
                    new PlanningTurnControl((name,args) -> {
                        executed.incrementAndGet(); return JsonParser.parseString("{ok:true}");
                    }));
        })) {
            var first = service.resume("stale-task-test","",false);
            String token = first.get("leaseToken").getAsString();
            var old = awaitTask(service,token);
            assertFalse(old.get("taskFinished").getAsBoolean());
            // Simulate a committed stage whose old tool failed to mark the in-memory turn finished.
            Files.writeString(run.resolve("realm_profiles.json"),"[{\"realmId\":\"a\"}]");
            assertEquals("T2",service.snapshot().get("stage").getAsString());
            var error = assertThrows(IllegalStateException.class,() -> service.action(token,
                    old.get("taskId").getAsString(),"stale-action","realm_t1_prepare",new JsonObject()));
            assertTrue(error.getMessage().contains("PLANNING_TASK_STALE"));
            assertEquals(0,executed.get());
            assertFalse(service.view(token).has("tools"));
            service.resume("stale-task-test",token,false);
            var next = awaitTask(service,token);
            assertEquals("T2",next.get("stage").getAsString());
            assertNotEquals(old.get("taskId"),next.get("taskId"));
            assertEquals(2,preparedCount.get());
        }
    }
    @Test void explicitResumeEnqueuesSavedBlueprintThroughHttpWithoutPreparingD4() throws Exception {
        Path run = root.resolve("provider_2a_r8192"); sealed(run);
        Files.writeString(run.resolve("realm_profiles.json"), "[{\"realmId\":\"a\"}]");
        Files.writeString(run.resolve("realm_coordinate_selections.json"), "[{\"realmId\":\"a\"}]");
        Files.writeString(run.resolve("t3_report.json"), "{}");
        Files.writeString(run.resolve("realm_territory_map.json"), "{}");
        Files.writeString(run.resolve("city_seed_registry.json"), "{\"citySeeds\":[{\"realmId\":\"a\",\"citySeedId\":\"city_a\",\"role\":\"capital\"}]}");
        Path bp = Files.createDirectories(run.resolve("city_test_runs/city_a/steps/blueprint"));
        Files.writeString(bp.resolve("city_d4_workflow.json"), "{\"stage\":\"COMPLETE\",\"contextId\":\"ctx\"}");
        Files.writeString(bp.resolve("city_blueprint.json"), "{\"cityId\":\"city_a\"}");
        Files.writeString(bp.resolve("city_blueprint_submission_trace.json"), "{\"status\":\"accepted\",\"contextId\":\"ctx\"}");
        var cityQueue = new com.rinsing.geomantia.systems.city.application.queue.CityDesignQueue(root, root.resolve("queue_config.json"));
        Files.createDirectories(run.resolve("automation"));
        AtomicInteger enqueues = new AtomicInteger(), unexpected = new AtomicInteger();
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", exchange -> {
            JsonObject result = new JsonObject();
            try {
                assertEquals("/realm/city/post_d4_auto_compile_retry", exchange.getRequestURI().getPath());
                JsonObject args = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                assertEquals("provider_2a_r8192", args.get("runId").getAsString());
                assertEquals("city_a", args.get("citySeedId").getAsString());
                assertNotNull(exchange.getRequestHeaders().getFirst("X-Geomantia-Planning-Token"));
                cityQueue.requireProgramRetryIfManaged("provider_2a_r8192", "city_a");
                JsonObject post = new JsonObject(); post.addProperty("runId", "provider_2a_r8192");
                post.addProperty("citySeedId", "city_a"); post.addProperty("status", "running");
                cityQueue.onPostD4State(post); enqueues.incrementAndGet(); result.addProperty("ok", true);
            } catch (Throwable ex) { unexpected.incrementAndGet(); result.addProperty("ok", false); result.addProperty("error", ex.toString()); }
            byte[] bytes = result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); http.start();
        try {
            for (String savedStatus : List.of("waiting_for_agent", "design_saved", "blocked_by_program")) {
                int before = enqueues.get();
                JsonObject stale = JsonParser.parseString(queue(savedStatus)).getAsJsonObject();
                stale.addProperty("runId", "provider_2a_r8192");
                if (!"blocked_by_program".equals(savedStatus)) stale.addProperty("nextAction", "city_d4_overview");
                Files.writeString(run.resolve("automation/city_design_queue.json"), stale.toString());
                try (var service = new PlanningSessionService(root, root, http.getAddress().getPort(), 42,
                        (step, gateway) -> { throw new AssertionError("Saved city must never prepare design"); })) {
                    if ("blocked_by_program".equals(savedStatus)) {
                        assertEquals("blocked", service.snapshot().get("status").getAsString());
                    } else {
                        assertEquals("waiting", service.snapshot().get("status").getAsString());
                        assertTrue(service.snapshot().get("instruction").getAsString().contains("retry=true"));
                    }
                    String token = service.resume("saved-resume-test", "", false).get("leaseToken").getAsString();
                    waitForIdle(service);
                    assertEquals(before, enqueues.get()); // Reopening a session does not authorize construction.
                    service.resume("saved-resume-test", token, true);
                    waitForIdle(service);
                    assertEquals(before + 1, enqueues.get());
                    assertEquals("waiting", service.snapshot().get("status").getAsString());
                    service.resume("saved-resume-test", token, true);
                    waitForIdle(service);
                    assertEquals(before + 1, enqueues.get()); // No duplicate while compiling.
                    service.release(token);
                }
            }
            assertEquals(0, unexpected.get());
        } finally { http.stop(0); }
    }
    private static void waitForIdle(PlanningSessionService service) throws Exception {
        for (int i=0; i<200; i++) {
            if (!"running".equals(service.snapshot().get("status").getAsString())) return;
            Thread.sleep(20);
        }
        fail("Planning session did not settle");
    }
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
