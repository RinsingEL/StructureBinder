import com.google.gson.*;
import com.rinsing.realmfolk.design.*;
import com.rinsing.realmfolk.economy.EconomyConfig;
import java.util.*;
import java.nio.file.*;

public class RealmReviewProbe {
  public static void main(String[] args) throws Exception {
    var design=DesignCodec.defaults();var city=new Design.CityPlan();
    var activity=new Design.PublicActivity();activity.maxDistance=64;activity.placeTerms=List.of("广场");
    city.overrides.activities=List.of(activity);design.cities.put("town",city);
    System.out.println("EXPLICIT_RADIUS configured=64 resolved="+design.resolve("realm","town").settings().activities.get(0).maxDistance);
    try {
      var cls=Class.forName("com.rinsing.realmfolk.design.FacilityCapacity");
      var method=Class.forName("com.rinsing.realmfolk.server.CityPlanningExtension").getDeclaredMethod("facilityCapacity",Path.class);method.setAccessible(true);
      var game=Files.createTempDirectory("campus-capacity-review-");
      var catalog=game.resolve("config/structureTemplate/terrasense/pack/realmfolk_facilities.json");Files.createDirectories(catalog.getParent());
      Files.writeString(catalog,"{\"schema\":\"realmfolk_facility_catalog.v1\",\"templates\":[{\"templateRef\":\"studio:dorm\",\"facilities\":[{\"kind\":\"bed\"}]}]}");
      var run=game.resolve("saves/world/realm_debug/run");Files.createDirectories(run);
      Object direct=cls.getMethod("load",Path.class).invoke(null,catalog);Object actual=method.invoke(null,run);
      System.out.println("CAPACITY_PATH actual_catalog_known="+cls.getMethod("known").invoke(direct)+" workflow_known="+cls.getMethod("known").invoke(actual));
      Files.delete(catalog);
    } catch(ClassNotFoundException ex) {}
    try {
      var budgetClass=Class.forName("com.rinsing.geomantia.systems.city.domain.blueprint.CityPopulationBudget");
      // Distinguish the Codex add-on by its Evidence record signature.
      var ctor=CityWorksheet.Evidence.class.getConstructor(List.class,List.class,List.class,JsonArray.class);
      for(String raw:List.of("{purpose:'staff',jobs:[{profession:'teacher',count:2,functions:['教学']}]}","{purpose:'staff',residents:8,jobs:[{profession:'teacher',functions:['教学']}]}")) {
        var validated=budgetClass.getConstructor(JsonObject.class).newInstance(JsonParser.parseString(raw).getAsJsonObject());
        var row=(JsonObject)budgetClass.getMethod("toJson").invoke(validated);row.addProperty("districtId","teaching");var rows=new JsonArray();rows.add(row);
        var facts=(CityWorksheet.Evidence)ctor.newInstance(List.of(new CityWorksheet.Building("home","","home",Set.of("住宅")),new CityWorksheet.Building("school","","school",Set.of("教学"))),List.of(new CityWorksheet.Fact("住宅",1,Set.of(),Set.of()),new CityWorksheet.Fact("教学",1,Set.of(),Set.of())),List.of(),rows);
        var input=new QuickCityPlan.Input();input.housingTerms=List.of("住宅");
        try {QuickCityPlan.expand(input,DesignCodec.defaults(),"realm","town",facts,EconomyConfig.defaults());System.out.println("OPTIONAL_COUNT_EXPAND success");}
        catch(Exception ex){System.out.println("OPTIONAL_COUNT_EXPAND accepted_by_D4_then="+ex);}
      }
    } catch(NoSuchMethodException ex) {}
  }
}
