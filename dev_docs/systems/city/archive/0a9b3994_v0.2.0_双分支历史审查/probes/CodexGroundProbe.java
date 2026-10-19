import com.rinsing.geomantia.systems.city.application.landuse.*;
import com.rinsing.geomantia.systems.city.application.outdoor.*;
import com.rinsing.geomantia.systems.city.algorithm.landuse.*;
import com.rinsing.geomantia.systems.city.domain.landuse.*;
import com.rinsing.geomantia.systems.city.domain.model.*;
import com.rinsing.geomantia.systems.city.infrastructure.world.landuse.*;
import java.util.*;
import static com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlan.*;

public class CodexGroundProbe {
  public static void main(String[] args) {
    entrance();
    var original=new FeatureCell(CityPublicGreeneryPlanner.SOURCE+"city",2,2,"minecraft:grass_block",0,FeatureKind.GREEN_GROUND,HorizontalFacing.NONE);
    var source=new CityLandUseSurfacePrintPlan(SCHEMA,"city","hash","",List.of(),List.of(),List.of(original));
    var district=new CityDistrictPlanner.Result(Set.of(),Set.of(),Set.of(),Set.of(),Map.of(),2,0);
    var terrain=new LandUseTerrainField(LandUseTerrainField.SCHEMA,"city",new BlockBounds(0,0,15,15),16,List.of());
    var planned=new CityPublicGroundPlanner().plan(source,district,terrain,(x,z)->new CityPublicRealmPlanner.Ground(64,true));
    for(var plan:List.of(source,planned.surface())){
      var fs=plan.featureCells().stream().map(f->new CityLandUseChunkCompiler.FeatureOperation(f.sourceId(),f.x(),f.z(),f.blockId(),f.surfaceOffset(),f.kind(),f.facing(),f.targetSurfaceY())).toList();
      var fragment=new CityLandUseChunkCompiler.ChunkFragment(CityLandUseChunkCompiler.RESULT_SCHEMA,"city","hash","palette",0,0,1,0,0,0,"minecraft:dirt",List.of(),List.of(),List.of(),fs,fs);
      var world=new World();var result=new CityLandUseChunkExecutor().execute(fragment,world,CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES);
      System.out.println("EXISTING_TREE source="+fs.get(0).sourceId()+" status="+result.status()+" log_at_65="+world.blocks.get("2,65,2")+" writes="+world.writes);
    }
  }
  static void entrance(){
    var area=new AreaPrint("foundation","foundation",List.of("city::foundation"),LandUseSurfaceSettings.defaults(SurfacePolicy.PAVE),List.of(new LandUseAreaPlan.ScanlineSpan(0,0,0)),List.of(),LandUseSurfaceSettings.SurfaceAlgorithm.UNIFORM,null,new UniformRecipe("minecraft:stone_bricks","",List.of(new PlatformSpan(0,0,0,72))));
    var fs=new ArrayList<FeatureCell>();
    for(int x=1;x<=8;x++)for(int z=-1;z<=1;z++){boolean stair=x<5;int base=Math.min(72,67+x);fs.add(new FeatureCell("road",x,z,stair?"minecraft:stone_brick_stairs":"minecraft:stone_brick_slab",0,stair?FeatureKind.ROAD_STAIR:FeatureKind.ROAD_SLAB,stair?HorizontalFacing.EAST:HorizontalFacing.NONE,base+(stair?1:0)));}
    var plan=new CityLandUseSurfacePrintPlan(SCHEMA,"city","hash","",List.of(area),List.of(),fs);
    var r=new CityEntranceAccessPlanner().plan(plan,List.of(new LandUseAreaPlan.GateSlot("door",new BlockPoint(0,0),CardinalDirection.EAST,"building")),Set.of(new BlockPoint(0,0)));
    System.out.println("ENTRANCE_EXISTING_SLOPE "+r.report());
    for(var f:r.surface().featureCells())if(f.z()==0)System.out.println("approach x="+f.x()+" kind="+f.kind()+" target="+f.targetSurfaceY()+" facing="+f.facing());
  }
  static class World implements CityLandUseChunkExecutor.ExecutionWorld {
    Map<String,String> blocks=new HashMap<>();List<String> writes=new ArrayList<>();
    World(){blocks.put("2,65,2","minecraft:oak_log");blocks.put("2,66,2","minecraft:oak_leaves");}
    public CityLandUseChunkExecutor.ColumnSample sampleColumn(int x,int z){return new CityLandUseChunkExecutor.ColumnSample(64,"minecraft:dirt",true);}
    public boolean isKnownBlock(String s){return true;}
    public boolean ensureCanWrite(int x,int y,int z){return true;}
    public CityLandUseChunkExecutor.TargetState inspect(int x,int y,int z){String old=blocks.getOrDefault(x+","+y+","+z,"minecraft:air");return new CityLandUseChunkExecutor.TargetState(old,!old.equals("minecraft:oak_log"));}
    public boolean isPublicVegetation(int x,int y,int z){String s=blocks.getOrDefault(x+","+y+","+z,"");return s.contains("oak_log")||s.contains("oak_leaves");}
    public boolean setBlock(int x,int y,int z,String s){blocks.put(x+","+y+","+z,s);writes.add(x+","+y+","+z+"="+s);return true;}
    public boolean restoreBlock(int x,int y,int z,Object s){blocks.put(x+","+y+","+z,s.toString());return true;}
  }
}
