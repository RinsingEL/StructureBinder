import java.nio.file.Path;
import com.rinsing.geomantia.systems.city.application.CityBlueprintCompilerService;
import com.rinsing.geomantia.systems.city.application.CityStructureAnchorPlanner;
import com.rinsing.geomantia.systems.city.domain.model.CityLandformReviewPackage;
import com.google.gson.JsonParser;
import java.nio.file.Files;

/** Offline compiler replay: reads frozen inputs, never calls persist or world activation. */
class CompileProbe {
    public static void main(String[] args) throws Exception {
        long started = System.nanoTime();
        var result = new CityBlueprintCompilerService().compile(Path.of(args[0]), args[1], args[2]);
        System.out.println("elapsedMs=" + (System.nanoTime() - started) / 1_000_000);
        System.out.println("ok=" + result.ok() + " reason=" + result.reasonCode() + " message=" + result.message());
        System.out.println("failureSummary=" + result.compileTrace().get("failureSummary"));
        System.out.println("arrayCompositionSlots=" + result.compileTrace().get("arrayCompositionSlots"));
        if (!result.ok()) {
            var rejectionCounts = new java.util.TreeMap<String, Integer>();
            String lastFailed = "";
            for (var element : result.compileTrace().getAsJsonArray("selections")) {
                var selection = element.getAsJsonObject();
                if ("committed".equals(selection.get("status").getAsString())) continue;
                lastFailed = selection.get("groupId") + "/" + selection.get("structureRef") + ":" + selection.get("status");
                if (selection.has("attempts")) {
                    for (var attempt : selection.getAsJsonArray("attempts")) {
                        var counts = attempt.getAsJsonObject().getAsJsonObject("filterReasonCounts");
                        if (counts != null) for (var entry : counts.entrySet()) {
                            rejectionCounts.merge(selection.get("groupId").getAsString() + ":" + entry.getKey(),
                                    entry.getValue().getAsInt(), Integer::sum);
                        }
                    }
                }
            }
            System.out.println("lastFailed=" + lastFailed);
            System.out.println("rejectionCounts=" + rejectionCounts);
        }
        if (result.ok()) {
            Path run = Path.of(args[0]).resolve(args[1]);
            Path d3 = run.resolve("city_test_runs").resolve(args[2]).resolve("steps/d3/city_landform_review_package.json");
            var review = CityLandformReviewPackage.fromJson(JsonParser.parseString(Files.readString(d3)).getAsJsonObject());
            var finalized = new CityStructureAnchorPlanner().plan(run, review,
                    result.terraSenseProfileSource(), result.structureAnchorPlan());
            System.out.println("finalQuality=" + finalized.qualityReport().get("passed"));
            System.out.println("hardBlocks=" + finalized.qualityReport().get("hardBlocks"));
            System.out.println("anchors=" + finalized.structureAnchorMap().getAsJsonArray("anchors").size());
            System.out.println("totalMs=" + (System.nanoTime() - started) / 1_000_000);
        }
    }
}
