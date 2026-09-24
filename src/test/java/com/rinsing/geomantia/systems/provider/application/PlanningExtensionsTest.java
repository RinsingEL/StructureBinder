package com.rinsing.geomantia.systems.provider.application;

import com.google.gson.*;
import com.rinsing.geomantia.api.planning.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PlanningExtensionsTest {
    @TempDir Path root;
    static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    static final class Addon implements PlanningExtension {
        final String id, version;
        List<String> after = List.of();
        boolean applicable = true, publish = true, fail;
        final AtomicInteger calls = new AtomicInteger();
        String thread;
        Addon(String id) { this(id, "1"); }
        Addon(String id, String version) { this.id=id; this.version=version; }
        public String id() { return id; }
        public String version() { return version; }
        public String title() { return "Configure " + id; }
        public List<String> after() { return after; }
        String tool() { return id.replace(':','_') + "_publish"; }
        public List<PlanningTool> tools() { return List.of(new PlanningTool(tool(), "Publish city rules",
                json("{\"type\":\"object\",\"properties\":{\"choice\":{\"type\":\"string\",\"enum\":[\"agriculture\",\"industry\"]}},\"required\":[\"choice\"],\"additionalProperties\":false}"))); }
        Path result(PlanningExtensionContext c) { return c.runDirectory().resolve(c.citySeedId()+"_"+tool()+".result"); }
        public boolean applies(PlanningExtensionContext c) { return applicable; }
        public boolean isComplete(PlanningExtensionContext c) throws Exception {
            return Files.isRegularFile(result(c)) && Files.readString(result(c)).equals(c.taskRevision());
        }
        public JsonObject prepare(PlanningExtensionContext c) {
            var data = c.blueprint(); data.addProperty("mutated", true);
            assertFalse(c.blueprint().has("mutated"));
            return json("{\"instruction\":\"Choose content suitable for this city\"}");
        }
        public JsonObject execute(PlanningExtensionContext c, String name, JsonObject args) throws Exception {
            thread = Thread.currentThread().getName(); calls.incrementAndGet();
            if (fail) throw new IllegalStateException("addon offline");
            if (publish) Files.writeString(result(c), c.taskRevision());
            // An addon tool payload must not independently terminate the host task.
            return json("{\"ok\":true,\"hostDecisionCommitted\":true,\"designInProgress\":false}");
        }
    }
    @Test void registrationSortsDependenciesAndRejectsConflicts() {
        var a = new Addon("test:a"); var b = new Addon("test:b"); b.after=List.of(a.id());
        var registry = registry(b,a);
        assertEquals(List.of(a.id(),b.id()), registry.entries().stream().map(PlanningExtensionRegistry.Entry::id).toList());
        assertThrows(IllegalArgumentException.class, () -> registry(a,a));
        assertThrows(IllegalArgumentException.class, () -> registry(b));
        a.after=List.of(b.id()); assertThrows(IllegalArgumentException.class, () -> registry(a,b));
        assertThrows(IllegalArgumentException.class, () -> registry(new Addon("../invalid")));
    }
    @Test void externalSessionExposesCustomToolsCompletesOnceAndRestoresWithoutReexecution() throws Exception {
        Path run=world(false); var addon=new Addon("test:residents");
        try(var service=service(registry(addon))) {
            assertEquals("EXTENSION", service.snapshot().get("stage").getAsString());
            assertFalse(Files.exists(run.resolve("automation/planning_extensions")));
            var first=service.resume("extension-test","",false); String token=first.get("leaseToken").getAsString();
            var task=awaitTask(service,token);
            assertEquals(addon.tool(), task.getAsJsonArray("tools").get(0).getAsJsonObject().get("name").getAsString());
            assertTrue(task.getAsJsonObject("state").has("extensionTask"));
            assertThrows(IllegalStateException.class, service::acquireEmbedded);
            String id=task.get("taskId").getAsString();
            var denied=service.action(token,id,"denied","city_submit_d4_blueprint",new JsonObject());
            assertFalse(denied.get("ok").getAsBoolean()); assertEquals(0,addon.calls.get());
            var bad=service.action(token,id,"invalid",addon.tool(),json("{\"choice\":\"invented\"}"));
            assertFalse(bad.get("ok").getAsBoolean()); assertEquals(0,addon.calls.get());
            var done=service.action(token,id,"publish",addon.tool(),choice());
            assertTrue(done.get("taskFinished").getAsBoolean());
            assertEquals(done,service.action(token,id,"publish",addon.tool(),choice()));
            assertEquals(1,addon.calls.get());
            assertEquals("COMPLETE",service.snapshot().get("stage").getAsString());
            service.release(token);
        }
        var restored=new Addon("test:residents");
        try(var service=service(registry(restored))) {
            assertEquals("COMPLETE",service.snapshot().get("stage").getAsString());
            assertEquals(0,restored.calls.get());
        }
    }
    @Test void dependenciesRunBeforeNextCityAndSkipWithoutModelCalls() throws Exception {
        world(true); var first=new Addon("test:first"); first.applicable=false;
        var second=new Addon("test:second"); second.after=List.of(first.id());
        try(var service=service(registry(second,first))) {
            var initial=service.discovery().nextStep();
            assertEquals(first.id(),initial.nextAction());
            assertNull(service.prepare(initial,service.gateway(initial,"")));
            var next=service.discovery().nextStep();
            assertEquals(second.id(),next.nextAction());
            var prepared=service.prepare(next,service.gateway(next,""));
            prepared.control().execute(second.tool(),choice());
            var city=service.discovery().nextStep();
            assertEquals(ProviderPlanningDiscovery.Stage.CITY,city.stage());
            assertEquals("city_b",city.citySeedId());
            assertEquals(0,first.calls.get());
        }
    }
    @Test void toolClaimsCannotFinishWithoutDurableAddonCompletion() throws Exception {
        world(false); var addon=new Addon("test:claims"); addon.publish=false;
        try(var service=service(registry(addon))) {
            var step=service.discovery().nextStep();
            var prepared=service.prepare(step,service.gateway(step,""));
            prepared.control().execute(addon.tool(),choice());
            assertFalse(prepared.control().finished());
            assertEquals(ProviderPlanningDiscovery.Stage.EXTENSION, service.discovery().nextStep().stage());
            addon.publish=true;
            prepared.control().execute(addon.tool(),choice());
            assertTrue(prepared.control().finished());
        }
    }
    @Test void restartRecoversPublishedResultWithoutRepeatingMutation() throws Exception {
        Path run=world(false); var addon=new Addon("test:recover");
        try(var service=service(registry(addon))) {
            var step=service.discovery().nextStep();
            service.prepare(step,service.gateway(step,"")).control().execute(addon.tool(),choice());
        }
        // Model crash after addon publish, before host receipt: simulate the missing receipt.
        Files.delete(run.resolve("automation/planning_extensions/city_a.json"));
        try(var service=service(registry(addon))) {
            var step=service.discovery().nextStep();
            assertNull(service.prepare(step,service.gateway(step,"")));
            assertEquals(1,addon.calls.get());
            assertEquals(ProviderPlanningDiscovery.Stage.COMPLETE,service.discovery().nextStep().stage());
        }
    }
    @Test void pendingAddonCannotDisappearAcrossRestart() throws Exception {
        world(false); var addon=new Addon("test:missing");
        try(var service=service(registry(addon))) {
            var step=service.discovery().nextStep(); service.prepare(step,service.gateway(step,""));
        }
        try(var service=service(PlanningExtensionRegistry.empty())) {
            var error=assertThrows(java.io.IOException.class,service::snapshot);
            assertTrue(error.getMessage().contains("PLANNING_EXTENSION_MISSING"));
        }
    }
    @Test void changedBlueprintInvalidatesPreparedTaskAndPreviousCompletion() throws Exception {
        Path run=world(false); var addon=new Addon("test:stale");
        try(var service=service(registry(addon))) {
            var step=service.discovery().nextStep();
            var prepared=service.prepare(step,service.gateway(step,""));
            write(run,"city_test_runs/city_a/steps/blueprint/city_blueprint.json","{\"theme\":\"new\"}");
            var result=prepared.control().execute(addon.tool(),choice()).getAsJsonObject();
            assertFalse(result.get("ok").getAsBoolean());
            assertTrue(prepared.control().finished());
            assertEquals(0,addon.calls.get());
            var fresh=service.discovery().nextStep();
            assertNotEquals(step.semanticIdentity(),fresh.semanticIdentity());
            service.prepare(fresh,service.gateway(fresh,"")).control().execute(addon.tool(),choice());
            write(run,"city_test_runs/city_a/steps/blueprint/city_blueprint.json","{\"theme\":\"third\"}");
            assertEquals(ProviderPlanningDiscovery.Stage.EXTENSION,service.discovery().nextStep().stage());
        }
    }
    @Test void versionAndDependencyVersionsInvalidateTaskReceipts() throws Exception {
        world(false); var a=new Addon("test:a"); var b=new Addon("test:b"); b.after=List.of(a.id());
        try(var service=service(registry(a,b))) {
            for(int i=0;i<2;i++) { var step=service.discovery().nextStep(); service.prepare(step,service.gateway(step,"")).control().execute(i==0?a.tool():b.tool(),choice()); }
        }
        var changed=new Addon("test:a","2");
        try(var service=service(registry(changed,b))) {
            var step=service.discovery().nextStep(); assertEquals(a.id(),step.nextAction());
            service.prepare(step,service.gateway(step,"")).control().execute(changed.tool(),choice());
            assertEquals(b.id(),service.discovery().nextStep().nextAction());
        }
    }
    @Test void callbackRunsOnDispatcherAndFailureCanBeRetried() throws Exception {
        world(false); var addon=new Addon("test:failure"); addon.fail=true;
        ExecutorService dispatcher=Executors.newSingleThreadExecutor(r->new Thread(r,"addon-server-thread"));
        try(var service=service(new PlanningExtensionRegistry(List.of(addon),dispatcher))) {
            var step=service.discovery().nextStep();
            var result=service.prepare(step,service.gateway(step,"")).control().execute(addon.tool(),choice()).getAsJsonObject();
            assertFalse(result.get("ok").getAsBoolean()); assertEquals("addon-server-thread",addon.thread);
            assertEquals(ProviderPlanningDiscovery.Stage.EXTENSION,service.discovery().nextStep().stage());
            addon.fail=false;
            assertTrue(service.prepare(step,service.gateway(step,"")).control().execute(addon.tool(),choice()).getAsJsonObject().get("ok").getAsBoolean());
        } finally { dispatcher.shutdownNow(); }
    }
    @Test void noAddonLeavesExistingWorldFlowUnchanged() throws Exception {
        world(false);
        try(var service=service(PlanningExtensionRegistry.empty())) {
            assertEquals(ProviderPlanningDiscovery.Stage.COMPLETE,service.discovery().nextStep().stage());
        }
    }
    private PlanningSessionService service(PlanningExtensionRegistry registry) { return new PlanningSessionService(root,root,1,42,registry); }
    private static PlanningExtensionRegistry registry(Addon... addons) { return new PlanningExtensionRegistry(List.of(addons),Runnable::run); }
    private static JsonObject choice() { return json("{\"choice\":\"industry\"}"); }
    private static JsonObject awaitTask(PlanningSessionService service,String token) throws Exception {
        for(int i=0;i<150;i++) {
            var result=service.view(token); if(result.has("taskId")) return result;
            if("blocked".equals(result.get("status").getAsString())) fail(result.toString());
            Thread.sleep(10);
        }
        throw new AssertionError("No extension task");
    }
    private Path world(boolean nextCity) throws Exception {
        Path run=root.resolve("test_run");
        write(run,"world_survey_context.json","{\"worldSeed\":\"42\",\"sealed\":true,\"scanBounds\":{\"centerBlockX\":0,\"centerBlockZ\":0,\"planningRadiusBlocks\":8192}}");
        write(run,"world_survey_manifest.json","{\"createdAt\":\"2026-09-24T00:00:00Z\"}");
        write(run,"world_patch_map.json","{}");
        write(run,"realm_profiles.json","[{\"realmId\":\"a\"}]");
        write(run,"realm_coordinate_selections.json","[{\"realmId\":\"a\"}]");
        write(run,"t3_report.json","{}");write(run,"realm_territory_map.json","{}");
        String second=nextCity?",{\"citySeedId\":\"city_b\",\"realmId\":\"a\",\"role\":\"town\"}":"";
        write(run,"city_seed_registry.json","{\"citySeeds\":[{\"citySeedId\":\"city_a\",\"realmId\":\"a\",\"role\":\"capital\"}"+second+"]}");
        String waiting=nextCity?",{\"citySeedId\":\"city_b\",\"realmId\":\"a\",\"status\":\"waiting_for_agent\"}":"";
        write(run,"automation/city_design_queue.json","{\"status\":\"waiting_for_agent\",\"currentCitySeedId\":\"city_b\",\"nextAction\":\"city_prepare_d4_blueprint_context\",\"items\":[{\"citySeedId\":\"city_a\",\"realmId\":\"a\",\"status\":\"waiting_for_generation\"}"+waiting+"]}");
        write(run,"city_test_runs/city_a/steps/blueprint/city_blueprint.json","{\"theme\":\"industry\"}");
        return run;
    }
    private static void write(Path root,String path,String text) throws Exception {
        Path file=root.resolve(path);Files.createDirectories(file.getParent());Files.writeString(file,text);
    }
}
