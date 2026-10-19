import com.google.gson.*;
import com.rinsing.geomantia.systems.city.application.*;
import com.rinsing.geomantia.systems.city.application.landuse.*;
import com.rinsing.geomantia.systems.city.application.outdoor.*;
import java.nio.file.*;

/** Read copied D4/D5/D6 artifacts; no world access, MCP calls or source artifact writes. */
class ReplayFrozenLandUse {
    static JsonObject read(Path root,String name) throws Exception {
        return JsonParser.parseString(Files.readString(root.resolve(name))).getAsJsonObject();
    }
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]);
        var blueprint=new CityBlueprintCodec().read(read(root,"city_blueprint.json"));
        var snapshot=read(root,"city_blueprint_catalog_snapshot.json");
        var templates=new CityTemplateCatalogLoader().load(snapshot.getAsJsonObject("templateCatalog"));
        var catalog=CityBlueprintReferenceCatalog.parse(snapshot.getAsJsonObject("referenceCatalog"),templates);
        var terrain=new LandUseTerrainFieldCodec().fromJson(read(root,"land_use_terrain_field.json"));
        var capacity=read(root,"city_blueprint_geometry_commit.json").getAsJsonObject("result")
                .getAsJsonObject("landscapeCapacityReservationPlan");
        var outdoor=new CityOutdoorBlueprintCompiler().compile(blueprint,
                read(root,"structure_materialization_plan.json"),terrain,catalog,capacity);
        var result=new LandUsePlanningService().plan(blueprint.cityId(),outdoor.resolution(),
                read(root,"reservation_mask_plan.json"),terrain,outdoor.residualConfig(),blueprint.outdoorPlan().publicRealm());
        var surface=result.surfacePrintPlan();
        JsonObject receipt=new JsonObject();
        receipt.addProperty("ok",true);
        receipt.addProperty("scope","Frozen artifacts; sampled terrain only, no live ground sampler or world mutation");
        receipt.addProperty("cityId",blueprint.cityId());
        receipt.addProperty("publicRealmPresent",blueprint.outdoorPlan().publicRealm()!=null);
        receipt.addProperty("surfacePlanHash",surface.planHash());
        receipt.addProperty("featureCells",surface.featureCells().size());
        receipt.addProperty("terraceRails",surface.featureCells().stream()
                .filter(f -> f.kind()==CityLandUseSurfacePrintPlan.FeatureKind.TERRACE_RAIL).count());
        receipt.addProperty("plants",surface.featureCells().stream()
                .filter(f -> f.kind()==CityLandUseSurfacePrintPlan.FeatureKind.GREEN_PLANT).count());
        receipt.add("originalConflictColumn",new Gson().toJsonTree(surface.featureCells().stream()
                .filter(f -> f.x()==-2865 && f.z()==5022).toList()));
        receipt.add("quality",result.quality());
        Files.writeString(Path.of(args[1]),new GsonBuilder().setPrettyPrinting().create().toJson(receipt));
        System.out.println(receipt);
    }
}
