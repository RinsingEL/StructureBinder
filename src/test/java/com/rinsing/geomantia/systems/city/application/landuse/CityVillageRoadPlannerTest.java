package com.rinsing.geomantia.systems.city.application.landuse;

import com.google.gson.*;
import com.rinsing.geomantia.systems.city.domain.blueprint.CitySurfaceMaterials;
import com.rinsing.geomantia.systems.city.domain.landuse.*;
import com.rinsing.geomantia.systems.city.domain.model.*;
import com.rinsing.geomantia.systems.city.infrastructure.landuse.LandUseDefaultConfigBootstrap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlan.*;

class CityVillageRoadPlannerTest {
    @TempDir Path root;
    private CityVillageRoadSettings settings() { return CityVillageRoadSettings.load(root.resolve("missing.json")); }

    @Test void mixedVillageRoadRemovesOnlySideCurbsAndKeepsSlopeAndMainRoad() {
        var input=surface();var sources=sources();
        var actual=apply(input,sources,settings(),List.of());
        var road=actual.featureCells().stream().filter(f->f.sourceId().equals("village")).toList();
        assertFalse(road.stream().anyMatch(f->f.z()==-2 || f.z()==2));
        assertTrue(road.stream().map(FeatureCell::blockId).distinct().count()>=3);
        assertTrue(road.stream().anyMatch(f->f.x()==10 && f.z()==0 && f.kind()==FeatureKind.ROAD_STAIR
                && f.blockId().equals(settings().stairBlockId()) && f.targetSurfaceY()==66));
        assertEquals(input.featureCells().stream().filter(f->f.sourceId().equals("city_main_road_1")).toList(),
                actual.featureCells().stream().filter(f->f.sourceId().equals("city_main_road_1")).toList());
        assertEquals("minecraft:oak_stairs",actual.materialField().at("roadStair",0,0,"village","missing"));
        assertEquals(actual,new CityLandUseSurfacePrintPlanCodec().fromJson(new CityLandUseSurfacePrintPlanCodec().toJson(actual)));
    }

    @Test void decorationsAreSparseOutsideRoadsAndAvoidBuildingAndWallReservations() {
        var s=settings();
        var actual=apply(surface(),sources(),s,List.of());
        var ornaments=actual.featureCells().stream().filter(f->f.sourceId().startsWith(CityVillageRoadPlanner.DECORATION_SOURCE)).toList();
        assertFalse(ornaments.isEmpty());
        assertTrue(ornaments.size()<40);
        assertTrue(ornaments.stream().allMatch(f->Math.abs(f.z())>1 && f.surfaceOffset()>0));
        assertTrue(ornaments.stream().noneMatch(f->f.x()>=0 && f.x()<=20 && f.z()==3));
        var reserved=apply(surface(),sources(),s,List.of(new LandUseAreaPlan.CorridorExclusion("wall",
                new BlockBounds(-10,-10,110,10),"wall")));
        assertTrue(reserved.featureCells().stream().noneMatch(f->f.sourceId().startsWith(CityVillageRoadPlanner.DECORATION_SOURCE)));
        assertEquals(actual,apply(surface(),sources(),s,List.of()));
    }

    @Test void configurationControlsPaletteDecorationAndDisableWithoutChangingOldPlans() throws Exception {
        var original=apply(surface(),sources(),settings(),List.of());
        LandUseDefaultConfigBootstrap.ensureInstalled(root);
        Path config=root.resolve("village_roads.json");
        var json=JsonParser.parseString(Files.readString(config)).getAsJsonObject();
        json.add("surfacePalette",JsonParser.parseString("[{blockId:'minecraft:gravel',weight:1}]"));
        json.addProperty("decorationsEnabled",false);
        Files.writeString(config,json.toString());
        LandUseDefaultConfigBootstrap.ensureInstalled(root);
        var changed=apply(surface(),sources(),CityVillageRoadSettings.load(config),List.of());
        assertTrue(changed.featureCells().stream().filter(f->f.sourceId().equals("village") && f.kind()==FeatureKind.ROAD_SLAB)
                .allMatch(f->f.blockId().equals("minecraft:gravel")));
        assertFalse(changed.featureCells().stream().anyMatch(f->f.sourceId().startsWith(CityVillageRoadPlanner.DECORATION_SOURCE)));
        assertNotEquals(original.planHash(),changed.planHash());
        assertTrue(original.featureCells().stream().anyMatch(f->f.blockId().equals("minecraft:dirt_path")));
        json.addProperty("enabled",false);Files.writeString(config,json.toString());
        assertEquals(surface(),apply(surface(),sources(),CityVillageRoadSettings.load(config),List.of()));
        json.addProperty("spacingBlocks",0);Files.writeString(config,json.toString());
        assertThrows(IllegalArgumentException.class,()->CityVillageRoadSettings.load(config));
    }

    @Test void townScaleKeepsBlueprintMaterialsAndRecordsSkip() {
        var input=surface();
        var result=new CityVillageRoadPlanner().apply(input,sources(),area(),List.of(),settings(),"town");
        assertEquals(input,result.plan());
        assertEquals(List.of(CityVillageRoadPlanner.SKIPPED_SCALE+"town"),result.notes());
    }

    @Test void villageScaleAppliesButExplicitRoadMaterialWins() {
        var applied=new CityVillageRoadPlanner().apply(surface(),sources(),area(),List.of(),settings(),"village");
        assertTrue(applied.notes().isEmpty());
        assertTrue(applied.plan().featureCells().stream().anyMatch(f->f.blockId().equals("minecraft:dirt_path")));
        var kept=new CityVillageRoadPlanner().apply(surface(),sources("COMPACT_ALLEY"),area(),List.of(),settings(),"village");
        assertEquals(surface(),kept.plan());
        assertEquals(1,kept.notes().size());
        assertTrue(kept.notes().get(0).startsWith(CityVillageRoadPlanner.SKIPPED_EXPLICIT));
    }

    private static CityLandUseSurfacePrintPlan apply(CityLandUseSurfacePrintPlan input,LandUseSourceResolver.Resolution sources,
            CityVillageRoadSettings settings,List<LandUseAreaPlan.CorridorExclusion> reservations) {
        return new CityVillageRoadPlanner().apply(input,sources,area(),reservations,settings);
    }
    private static LandUseAreaPlan area() {
        return new LandUseAreaPlan(LandUseAreaPlan.SCHEMA,"v1","city","hash",new BlockBounds(-20,-20,120,30),
                List.of(),List.of(),List.of(),List.of(),List.of());
    }
    private static LandUseSourceResolver.Resolution sources() { return sources(""); }
    private static LandUseSourceResolver.Resolution sources(String explicitRoadKind) {
        var band=new LandUseSourceResolver.RoadBand("village","group::network","COMPACT_ALLEY",
                new BlockPoint(0,0),new BlockPoint(100,0),new BlockBounds(0,-1,100,1),3,"STAIR_SLAB_STAIR");
        Map<String,Map<String,String>> roads=explicitRoadKind.isEmpty()?Map.of():
                Map.of(explicitRoadKind,Map.of("roadSurface","minecraft:stone_brick_slab"));
        var field=new CityMaterialField(new CitySurfaceMaterials(Map.of("roadSurface","minecraft:stone_brick_slab"),Map.of(),roads,Map.of()),
                List.of(new CityMaterialField.Owner("house",new BlockBounds(0,3,20,5))),List.of());
        return new LandUseSourceResolver.Resolution(List.of(),List.of(),List.of(),"seed",Map.of(),Map.of(),List.of(band),List.of(),List.of(),field);
    }
    private static CityLandUseSurfacePrintPlan surface() {
        List<FeatureCell> features=new ArrayList<>();
        for(int x=0;x<=100;x++)for(int z=-2;z<=2;z++) {
            boolean stair=Math.abs(z)==2 || x==10&&z==0;
            features.add(new FeatureCell("village",x,z,stair?"minecraft:mud_brick_stairs":"minecraft:mud_brick_slab",0,
                    stair?FeatureKind.ROAD_STAIR:FeatureKind.ROAD_SLAB,stair?HorizontalFacing.NORTH:HorizontalFacing.NONE,
                    x==10&&z==0?66:null));
        }
        features.add(new FeatureCell("city_main_road_1",110,0,"minecraft:deepslate_tile_slab",0,FeatureKind.ROAD_SLAB,HorizontalFacing.NONE));
        return new CityLandUseSurfacePrintPlan(SCHEMA,"city","hash","",List.of(),List.of(),features);
    }
}
