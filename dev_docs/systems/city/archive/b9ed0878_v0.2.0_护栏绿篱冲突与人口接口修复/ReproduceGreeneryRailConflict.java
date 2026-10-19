import java.util.*;
import com.rinsing.geomantia.systems.city.algorithm.landuse.CityDistrictPlanner;
import com.rinsing.geomantia.systems.city.application.landuse.*;
import com.rinsing.geomantia.systems.city.domain.landuse.*;
import com.rinsing.geomantia.systems.city.domain.model.*;
import static com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlan.*;

/** Minimal reproduction against unchanged production classes; never opens a world. */
public class ReproduceGreeneryRailConflict {
    static boolean fails(String railSource, double density) {
        Set<BlockPoint> ground=new HashSet<>();
        for(int z=5021;z<=5027;z++)for(int x=-2866;x<=-2860;x++)ground.add(new BlockPoint(x,z));
        var district=new CityDistrictPlanner.Result(ground,ground,ground,Set.of(),Map.of(),0,0);
        var rail=new FeatureCell(railSource,-2865,5022,"minecraft:oak_fence",1,FeatureKind.TERRACE_RAIL,HorizontalFacing.NONE,66);
        var source=new CityLandUseSurfacePrintPlan(SCHEMA,"city_jingchao_capital","fixture","",List.of(),List.of(),List.of(rail));
        var land=new LandUseAreaPlan(LandUseAreaPlan.SCHEMA,"fixture","city_jingchao_capital","",new BlockBounds(-2866,5021,-2860,5027),List.of(),List.of(),List.of(),List.of(),List.of());
        try {
            new CityPublicGreeneryPlanner().append(source,district,land,
                new CityPublicGreeneryPlanner.Preset("minecraft:grass_block",List.of("minecraft:poppy"),density,1),List.of(),false);
            return false;
        } catch(IllegalArgumentException ex) {
            if(!ex.getMessage().startsWith("CITY_LAND_USE_SURFACE_FEATURE_CELL_DUPLICATE:-2865:5022:1"))throw ex;
            System.out.println(ex.getMessage());return true;
        }
    }
    public static void main(String[] args) {
        if(!fails("shared_terrace::city_jingchao_capital",.16))throw new AssertionError("Expected observed conflict");
        if(fails("other_rail",.16))throw new AssertionError("Non-shared rail should remain reserved");
        if(fails("shared_terrace::city_jingchao_capital",0))throw new AssertionError("Ground-only grading should remain possible");
        System.out.println("CONFIRMED: shared-terrace rail is omitted from planting clearance; ground-only and ordinary-rail controls pass.");
    }
}
