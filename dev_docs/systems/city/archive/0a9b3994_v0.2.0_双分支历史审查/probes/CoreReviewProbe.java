import com.google.gson.*;
import com.rinsing.geomantia.systems.city.application.*;
import com.rinsing.geomantia.systems.city.application.intercity.*;
import com.rinsing.geomantia.systems.city.application.landuse.*;
import com.rinsing.geomantia.systems.city.domain.blueprint.*;
import com.rinsing.geomantia.systems.city.domain.landuse.*;
import com.rinsing.geomantia.systems.city.domain.model.*;
import java.util.*;
import java.nio.file.*;
import static com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlan.*;

public class CoreReviewProbe {
  static JsonObject json(String s) { return JsonParser.parseString(s).getAsJsonObject(); }
  public static void main(String[] args) throws Exception {
    roads(); village(); noWallExit(); population(); domainMask();
  }
  static void roads() throws Exception {
    var cells=new HashMap<BlockPoint,FeatureCell>(); var platforms=new HashMap<BlockPoint,Integer>();
    for(int z=0;z<=40;z++)for(int x=0;x<=2;x++)cells.put(new BlockPoint(x,z),new FeatureCell("north",x,z,"minecraft:stone_brick_slab",0,FeatureKind.ROAD_SLAB,HorizontalFacing.NONE,66));
    for(int x=0;x<=20;x++)for(int z=20;z<=22;z++)cells.put(new BlockPoint(x,z),new FeatureCell("east",x,z,"minecraft:stone_brick_slab",0,FeatureKind.ROAD_SLAB,HorizontalFacing.NONE,66));
    cells.keySet().forEach(p->platforms.put(p,p.x()<10?68:72));
    var pins=Map.of(new BlockPoint(1,0),68,new BlockPoint(20,21),72);
    var bands=List.of(new LandUseSourceResolver.RoadBand("north","n","CITY_MAIN_ROAD",new BlockPoint(1,0),new BlockPoint(1,40),new BlockBounds(0,0,2,40),3,""),
      new LandUseSourceResolver.RoadBand("east","e","CITY_MAIN_ROAD",new BlockPoint(0,21),new BlockPoint(20,21),new BlockBounds(0,20,20,22),3,""));
    var p=new CityTerraceRoadGradePlanner(); List<FeatureCell> out;
    try {out=(List<FeatureCell>)p.getClass().getMethod("plan",List.class,Map.class,Map.class,java.util.function.IntBinaryOperator.class,List.class).invoke(p,List.copyOf(cells.values()),platforms,pins,(java.util.function.IntBinaryOperator)(x,z)->66,bands);}
    catch(NoSuchMethodException ex){out=p.plan(List.copyOf(cells.values()),platforms,pins,(x,z)->66);}
    var heights=new HashMap<BlockPoint,Integer>();out.forEach(f->heights.put(new BlockPoint(f.x(),f.z()),f.targetSurfaceY()-(f.kind()==FeatureKind.ROAD_STAIR?1:0)));
    int transverse=0,maxStep=0;String example="";
    for(int x=4;x<=20;x++)for(int z=20;z<22;z++)if(!heights.get(new BlockPoint(x,z)).equals(heights.get(new BlockPoint(x,z+1)))){transverse++;example=x+","+z+":"+heights.get(new BlockPoint(x,z))+"/"+heights.get(new BlockPoint(x,z+1));}
    for(var e:heights.entrySet())for(var q:List.of(new BlockPoint(e.getKey().x()+1,e.getKey().z()),new BlockPoint(e.getKey().x(),e.getKey().z()+1)))if(heights.containsKey(q))maxStep=Math.max(maxStep,Math.abs(e.getValue()-heights.get(q)));
    System.out.println("T_JUNCTION transverse_edges="+transverse+" max_neighbor_step="+maxStep+" example="+example);
  }
  static void village() throws Exception {
    var band=new LandUseSourceResolver.RoadBand("alley","group","COMPACT_ALLEY",new BlockPoint(0,0),new BlockPoint(10,0),new BlockBounds(0,-1,10,1),3,"");
    var field=new CityMaterialField(new CitySurfaceMaterials(Map.of("roadSurface","minecraft:stone_brick_slab"),Map.of(),Map.of(),Map.of()),List.of(),List.of(new CityMaterialField.Road("alley","COMPACT_ALLEY",band.bounds())));
    var sources=new LandUseSourceResolver.Resolution(List.of(),List.of(),List.of(),"seed",Map.of(),Map.of(),List.of(band),List.of(),List.of(),field);
    var source=new CityLandUseSurfacePrintPlan(SCHEMA,"city","hash","",List.of(),List.of(),List.of(new FeatureCell("alley",5,0,"minecraft:stone_brick_slab",0,FeatureKind.ROAD_SLAB,HorizontalFacing.NONE)),field);
    var area=new LandUseAreaPlan(LandUseAreaPlan.SCHEMA,"v1","city","hash",new BlockBounds(-20,-20,120,30),List.of(),List.of(),List.of(),List.of(),List.of());
    var settings=CityVillageRoadSettings.load(Path.of("runtime/campus-branch-review/no-config.json"));var planner=new CityVillageRoadPlanner();Object result;CityLandUseSurfacePrintPlan output;
    try {result=planner.getClass().getMethod("plan",CityLandUseSurfacePrintPlan.class,LandUseSourceResolver.Resolution.class,LandUseAreaPlan.class,List.class,CityVillageRoadSettings.class,CityScale.class).invoke(planner,source,sources,area,List.of(),settings,CityScale.VILLAGE);output=(CityLandUseSurfacePrintPlan)result.getClass().getMethod("surface").invoke(result);}
    catch(NoSuchMethodException ex){result=planner.getClass().getMethod("apply",CityLandUseSurfacePrintPlan.class,LandUseSourceResolver.Resolution.class,LandUseAreaPlan.class,List.class,CityVillageRoadSettings.class,String.class).invoke(planner,source,sources,area,List.of(),settings,"village");output=(CityLandUseSurfacePrintPlan)result.getClass().getMethod("plan").invoke(result);}
    System.out.println("EXPLICIT_DEFAULT_ROAD expected=minecraft:stone_brick_slab actual="+output.featureCells().stream().filter(f->f.sourceId().equals("alley")).findFirst().orElseThrow().blockId());
  }
  static void noWallExit(){
    var wall=json("{schema:'city_wall_reservation.v0.1',wallPolicy:'none',wallLine:[],wallCorridorMask:[],gateSlots:[],wallNodeSlots:[],boundaryPolicy:{form:'NONE'},cityBoundaryOutline:[{sideHint:'north',from:{x:0,z:0},to:{x:128,z:0},blockBounds:{minX:0,minZ:-8,maxX:128,maxZ:8}},{sideHint:'east',from:{x:128,z:0},to:{x:128,z:128},blockBounds:{minX:120,minZ:0,maxX:136,maxZ:128}},{sideHint:'south',from:{x:128,z:128},to:{x:0,z:128},blockBounds:{minX:0,minZ:120,maxX:128,maxZ:136}},{sideHint:'west',from:{x:0,z:128},to:{x:0,z:0},blockBounds:{minX:-8,minZ:0,maxX:8,maxZ:128}}]}");
    var anchors=json("{anchors:[],streetBands:[{start:{x:32,z:64},end:{x:96,z:64}}]}");
    var network=List.of(new InterCityNetwork.Link("link",new InterCityNetwork.City("city","realm",new BlockPoint(64,64)),new InterCityNetwork.City("other","realm",new BlockPoint(300,64))));
    System.out.println("NO_WALL_EXIT "+new CityExitRoadPlanner().plan("city",anchors,wall,new BlockBounds(-64,-64,192,192),network));
  }
  static void population() throws Exception {
    var validate=CityD4Workflow.class.getDeclaredMethod("validateBody",JsonObject.class);validate.setAccessible(true);
    JsonObject input;try{Class.forName("com.rinsing.geomantia.systems.city.domain.blueprint.CityPopulationBudget");input=json("{population:{purpose:'housing',residents:8}}");}catch(ClassNotFoundException ex){input=json("{population:{roles:[{role:'resident',count:8}]}}");}
    try{validate.invoke(null,input);System.out.println("D4_POPULATION accepted");}
    catch(java.lang.reflect.InvocationTargetException ex){System.out.println("D4_POPULATION "+ex.getCause());}
    try{var cls=Class.forName("com.rinsing.geomantia.systems.city.domain.blueprint.CityPopulationBudget");var budget=cls.getConstructor(JsonObject.class).newInstance(json("{purpose:'staff',jobs:[{profession:'teacher',functions:['teaching']}]}"));System.out.println("MISSING_POPULATION_COUNTS accepted="+cls.getMethod("toJson").invoke(budget));}
    catch(ClassNotFoundException ex){}
  }
  static void domainMask() throws Exception {
    var cls=Class.forName("com.rinsing.geomantia.platform.http.CityPlanningEndpointHandler");
    var m=cls.getDeclaredMethod("maskPlanWithLockedEnvelopes",JsonObject.class,JsonObject.class);m.setAccessible(true);
    var before=json("{noVegetationMask:[{maskId:'city_domain_no_vegetation',maskType:'city_domain_no_vegetation',blockBounds:{minX:0,minZ:0,maxX:128,maxZ:128}}]}");
    var after=(JsonObject)m.invoke(null,before,json("{plannedWorldgenStructures:[]}"));
    System.out.println("CITY_DOMAIN_MASK before="+before.getAsJsonArray("noVegetationMask").size()+" after_activation="+after.getAsJsonArray("noVegetationMask").size());
  }
}
