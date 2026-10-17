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

    @Test void townsCitiesAndUnknownScaleNeverAcquireVillageAppearance() {
        for(CityScale scale:new CityScale[]{CityScale.TOWN,CityScale.CITY,CityScale.LARGE_CITY,null}) {
            var input=surface();var result=new CityVillageRoadPlanner().plan(input,sources(),area(),List.of(),settings(),scale);
            assertSame(input,result.surface());
            assertEquals(0,result.report().get("appliedRoadCount").getAsInt());
            assertTrue(result.report().toString().contains("not_village_scale"));
        }
        assertTrue(new CityVillageRoadPlanner().plan(surface(),sources(),area(),List.of(),settings(),CityScale.HAMLET)
                .report().get("appliedRoadCount").getAsInt()>0);
    }

    @Test void explicitCityGroupAndRoadMaterialsPreserveTheWholeRoadIncludingCurbs() {
        for(CitySurfaceMaterials selection:List.of(
                new CitySurfaceMaterials(Map.of("roadSurface","minecraft:stone_brick_slab"),Map.of(),Map.of(),Map.of()),
                new CitySurfaceMaterials(Map.of(),Map.of("house",Map.of("roadStair","minecraft:stone_brick_stairs")),Map.of(),Map.of()),
                new CitySurfaceMaterials(Map.of(),Map.of(),Map.of("COMPACT_ALLEY",Map.of("roadBase","minecraft:stone_bricks")),Map.of()))) {
            var base=sources();var field=new CityMaterialField(selection,base.materialField().owners(),base.materialField().roads());
            var resolution=new LandUseSourceResolver.Resolution(List.of(),List.of(),List.of(),"seed",Map.of(),Map.of(),base.roadBands(),List.of(),List.of(),field);
            var input=surface().withMaterials(field);
            var result=new CityVillageRoadPlanner().plan(input,resolution,area(),List.of(),settings(),CityScale.VILLAGE);
            assertSame(input,result.surface());
            assertTrue(result.report().toString().contains("explicit_materials"));
        }
    }

    @Test void frozenVillageFallbackIsPerRoadAndRemainsBelowExplicitSelections() {
        var road=new CityMaterialField.Road("village","COMPACT_ALLEY",new BlockBounds(0,-1,100,1),
                Map.of("roadStair","minecraft:oak_stairs","roadBase","minecraft:dirt"));
        var selection=new CitySurfaceMaterials(Map.of(),Map.of("stone",Map.of("roadStair","minecraft:stone_brick_stairs")),Map.of(),Map.of());
        var field=new CityMaterialField(selection,List.of(new CityMaterialField.Owner("stone",new BlockBounds(0,0,20,5)),
                new CityMaterialField.Owner("rural",new BlockBounds(80,0,100,5))),List.of(road));
        var restored=CityMaterialField.read(field.toJson());
        assertEquals(field,restored);
        assertEquals("minecraft:stone_brick_stairs",restored.at("roadStair",0,0,"village","fallback"));
        assertEquals("minecraft:oak_stairs",restored.at("roadStair",100,0,"village","fallback"));
        assertEquals("fallback",restored.at("roadStair",100,0,"other","fallback"));
        var legacy=CityMaterialField.read(JsonParser.parseString("""
                {selections:{},owners:[],roads:[{sourceId:'old',kind:'COMPACT_ALLEY',bounds:{minX:0,minZ:0,maxX:3,maxZ:3}}]}
                """).getAsJsonObject());
        assertFalse(legacy.toJson().getAsJsonArray("roads").get(0).getAsJsonObject().has("fallbackMaterials"));
    }

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

    private static CityLandUseSurfacePrintPlan apply(CityLandUseSurfacePrintPlan input,LandUseSourceResolver.Resolution sources,
            CityVillageRoadSettings settings,List<LandUseAreaPlan.CorridorExclusion> reservations) {
        return new CityVillageRoadPlanner().plan(input,sources,area(),reservations,settings,CityScale.VILLAGE).surface();
    }
    private static LandUseAreaPlan area() {
        return new LandUseAreaPlan(LandUseAreaPlan.SCHEMA,"v1","city","hash",new BlockBounds(-20,-20,120,30),
                List.of(),List.of(),List.of(),List.of(),List.of());
    }
    private static LandUseSourceResolver.Resolution sources() {
        var band=new LandUseSourceResolver.RoadBand("village","group::network","COMPACT_ALLEY",
                new BlockPoint(0,0),new BlockPoint(100,0),new BlockBounds(0,-1,100,1),3,"STAIR_SLAB_STAIR");
        var field=new CityMaterialField(CitySurfaceMaterials.empty(),
                List.of(new CityMaterialField.Owner("house",new BlockBounds(0,3,20,5))),
                List.of(new CityMaterialField.Road("village","COMPACT_ALLEY",band.bounds())));
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
