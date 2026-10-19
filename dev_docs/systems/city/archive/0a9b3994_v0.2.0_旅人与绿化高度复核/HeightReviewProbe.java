import com.rinsing.geomantia.systems.city.application.landuse.*;
import com.rinsing.geomantia.systems.city.application.outdoor.*;
import com.rinsing.geomantia.systems.city.algorithm.landuse.*;
import com.rinsing.geomantia.systems.city.domain.landuse.*;
import com.rinsing.geomantia.systems.city.domain.model.*;
import com.rinsing.geomantia.systems.city.infrastructure.world.landuse.*;
import java.util.*;
import static com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlan.*;

public class HeightReviewProbe {
  public static void main(String[] args){quantization();courtEdge();deepGreen();}
  static void quantization(){
    for(int natural:List.of(65,66)){
      var cells=new ArrayList<LandUseTerrainField.Cell>();
      for(int z=-1;z<=1;z++)for(int x=-1;x<=1;x++)cells.add(new LandUseTerrainField.Cell(x,z,x*16,z*16,16,natural,0,0,0,false,0,100,"minecraft:plains","plain","p",true));
      var terrain=new LandUseTerrainField(LandUseTerrainField.SCHEMA,"city",new BlockBounds(-16,-16,31,31),16,cells);
      var result=CityFoundationElevationPlanner.planForCore(List.of(new LandUseAreaPlan.ScanlineSpan(0,0,0)),terrain,new BlockBounds(-1,-1,1,1));
      System.out.println("CORE_DATUM natural="+natural+" planned="+result.get(0).targetY());
    }
  }
  static void courtEdge(){
    var areaSpans=new ArrayList<LandUseAreaPlan.ScanlineSpan>();var platformSpans=new ArrayList<PlatformSpan>();var features=new ArrayList<FeatureCell>();
    for(int z=0;z<16;z++){
      areaSpans.add(new LandUseAreaPlan.ScanlineSpan(z,0,15));platformSpans.add(new PlatformSpan(z,0,15,68));
      for(int x=0;x<16;x++)features.add(new FeatureCell(CityPublicGreeneryPlanner.SOURCE+"city",x,z,"minecraft:grass_block",0,FeatureKind.GREEN_GROUND,HorizontalFacing.NONE));
    }
    var area=new AreaPrint("foundation","foundation",List.of("city::foundation"),LandUseSurfaceSettings.defaults(SurfacePolicy.PAVE),areaSpans,List.of(),LandUseSurfaceSettings.SurfaceAlgorithm.UNIFORM,null,new UniformRecipe("minecraft:stone_bricks","",platformSpans));
    var source=new CityLandUseSurfacePrintPlan(SCHEMA,"city","hash","",List.of(area),List.of(),features);
    var district=new CityDistrictPlanner.Result(Set.of(),Set.of(),Set.of(),Set.of(),Map.of(),2,0).withPublicSpaces(List.of(new CityDistrictPlanner.Result.PublicSpace("court",new BlockBounds(4,4,11,11),"courtyard")));
    var terrain=new LandUseTerrainField(LandUseTerrainField.SCHEMA,"city",new BlockBounds(0,0,15,15),16,List.of());
    var result=new CityPublicGroundPlanner().plan(source,district,terrain,(x,z)->new CityPublicRealmPlanner.Ground(65,true));
    var inside=result.surface().featureCells().stream().filter(f->f.x()==4&&f.z()==5).findFirst().orElseThrow();
    var outside=result.surface().featureCells().stream().filter(f->f.x()==3&&f.z()==5).findFirst().orElseThrow();
    System.out.println("FLAT_COURT_EDGE natural=65/65 planned="+outside.targetSurfaceY()+"/"+inside.targetSurfaceY()+" stairs="+result.surface().featureCells().stream().filter(f->f.kind()==FeatureKind.ROAD_STAIR).count()+" report="+result.report());
  }
  static void deepGreen(){
    var f=new CityLandUseChunkCompiler.FeatureOperation("public_ground::city",3,3,"minecraft:grass_block",0,FeatureKind.GREEN_GROUND,HorizontalFacing.NONE,64);
    var fragment=new CityLandUseChunkCompiler.ChunkFragment(CityLandUseChunkCompiler.RESULT_SCHEMA,"city","hash","palette",0,0,1,0,0,0,"minecraft:dirt",List.of(),List.of(),List.of(),List.of(f),List.of(f));
    var world=new World();var result=new CityLandUseChunkExecutor().execute(fragment,world,CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES);
    System.out.println("DEEP_GREEN natural=34 planned=64 status="+result.status()+" writes="+world.writes);
  }
  static class World implements CityLandUseChunkExecutor.ExecutionWorld {
    List<String> writes=new ArrayList<>();
    public CityLandUseChunkExecutor.ColumnSample sampleColumn(int x,int z){return new CityLandUseChunkExecutor.ColumnSample(34,"minecraft:stone",true);}
    public boolean isKnownBlock(String s){return true;}
    public boolean ensureCanWrite(int x,int y,int z){return true;}
    public CityLandUseChunkExecutor.TargetState inspect(int x,int y,int z){return new CityLandUseChunkExecutor.TargetState(y<=34?"minecraft:stone":"minecraft:air",y>34);}
    public boolean setBlock(int x,int y,int z,String s){writes.add(x+","+y+","+z+"="+s);return true;}
    public boolean restoreBlock(int x,int y,int z,Object s){return true;}
  }
}
