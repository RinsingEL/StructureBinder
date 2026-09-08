import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.provider.application.ProviderPlanningDiscovery;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;

/** Read-only reproduction of a second design revision being treated as the completed first turn. */
class RevisionIdentityProbe {
    public static void main(String[] args) throws Exception {
        Path run = Path.of(args[0]);
        JsonObject before = JsonParser.parseString(Files.readString(run.resolve("automation/city_design_queue.json"))).getAsJsonObject();
        JsonObject after = before.deepCopy();
        before.addProperty("updatedAt", "2026-09-06T10:51:07Z");
        after.addProperty("updatedAt", "2026-09-06T10:52:02Z");
        before.addProperty("failureCount", 2);
        after.addProperty("failureCount", 3);
        var method = ProviderPlanningDiscovery.class.getDeclaredMethod("step",
                ProviderPlanningDiscovery.Stage.class, String.class, String.class, String.class,
                String.class, JsonObject.class, Path.class, List.class);
        method.setAccessible(true);
        var first = (ProviderPlanningDiscovery.PlanningStep) method.invoke(null,
                ProviderPlanningDiscovery.Stage.CITY, run.getFileName().toString(), "realm_grainmark",
                "city_realm_grainmark_ferry_cross", "city_submit_d4_blueprint", before, run, List.of());
        var second = (ProviderPlanningDiscovery.PlanningStep) method.invoke(null,
                ProviderPlanningDiscovery.Stage.CITY, run.getFileName().toString(), "realm_grainmark",
                "city_realm_grainmark_ferry_cross", "city_submit_d4_blueprint", after, run, List.of());
        System.out.println("newFailureHasSameCompletedIdentity=" + first.semanticIdentity().equals(second.semanticIdentity()));
    }
}
