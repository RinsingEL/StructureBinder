import java.nio.file.Path;
import java.util.Set;
import com.rinsing.geomantia.systems.realm_planning.application.access.*;
import com.rinsing.geomantia.systems.realm_planning.application.map.*;

/** Read-only evaluation of saved state; no Minecraft command, activation or save writes. */
class V4StateProbe {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        var config = new PlanningAreaAccessConfig(true, 2048, 3072, Set.of("minecraft:overworld"));
        var policy = new PlanningAreaAccessPolicy(root, config);
        System.out.println("firstCityAccess=" + policy.evaluate("minecraft:overworld", 2032, 4016));
        var map = AdventurerMapStatusReader.read(root, "provider_22d55c3faaf1fd10_r8192", config,
                new AdventurerMapStatusReader.MapViewport(-128, 128, 4096));
        System.out.println("completed=" + map.completedCityCount() + " remaining=" + map.remainingCityCount());
        for (var city : map.cityNodes()) System.out.println("node=" + city.citySeedId()
                + " status=" + city.status() + " revealed=" + map.coarseMap().revealedAt(city.blockX(), city.blockZ()));
    }
}
