package com.rinsing.geomantia.adventure;

import com.rinsing.geomantia.api.regions.WorldRegionSnapshot;
import com.rinsing.geomantia.api.regions.WorldRegionSnapshot.Centre;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AdventureDistributionTest {
    @TempDir Path dir;
    private Map<String,String> defaults() throws Exception {
        Map<String,String> result=new HashMap<>();
        for(String name:List.of("distribution.json","mob_pools.json","structure_pools.json","settlement_rules.json","generation_control.json"))
            try(var in=getClass().getResourceAsStream("/adventure_defaults/"+name)){assertNotNull(in);result.put(name,new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
        return result;
    }
    @Test void collinearContinentsAdvanceAndCapWithoutDistanceThreshold() {
        var points=Map.of("a",new Centre(0,0),"b",new Centre(10000,0),"c",new Centre(10100,0),"d",new Centre(50000,0));
        assertEquals(Map.of("a",1,"b",2,"c",3,"d",3),ContinentTiers.compute(points,"a",3));
    }
    @Test void pointContactIsNotANeighborAndInputOrderIsIrrelevant() {
        var points=new LinkedHashMap<String,Centre>();points.put("d",new Centre(100,100));points.put("b",new Centre(100,0));points.put("a",new Centre(0,0));points.put("c",new Centre(0,100));
        assertEquals(Map.of("a",1,"b",2,"c",2,"d",3),ContinentTiers.compute(points,"a",9));
        assertEquals(ContinentTiers.compute(points,"a",9),ContinentTiers.compute(new TreeMap<>(points),"a",9));
    }
    @Test void translatedCoordinatesDoNotChangeTopology() {
        var points=Map.of("a",new Centre(29000000,-29000000),"b",new Centre(29000100,-29000000),"c",new Centre(29000000,-28999900),"d",new Centre(29000100,-28999900));
        assertEquals(3,ContinentTiers.compute(points,"a",8).get("d"));
    }
    @Test void invalidOrMissingPoolReferencesFailTheWholeBundle() throws Exception {
        var text=defaults();text.put("distribution.json",text.get("distribution.json").replace("land_common","missing"));
        var missing=text;assertThrows(IllegalArgumentException.class,()->AdventureConfig.parse(missing));
        text=defaults();text.put("generation_control.json","{\"version\":1,\"mobs\":{},\"structures\":{}}");var bad=text;
        assertThrows(IllegalArgumentException.class,()->AdventureConfig.parse(bad));
    }
    @Test void defaultsAreInstallableAndEmptySelectionsCanDisableARegion() throws Exception {
        var config=AdventureConfig.load(dir.resolve("config"));assertEquals(3,config.distribution().tiers().size());
        var text=defaults();text.put("distribution.json","{\"version\":1,\"tiers\":[{\"land\":{\"mobs\":[],\"structures\":[]},\"nearSea\":{\"mobs\":[],\"structures\":[]}}],\"ocean\":{\"mobs\":[],\"structures\":[]}}");
        assertTrue(AdventureConfig.parse(text).select("land",999).mobs().isEmpty());
    }
    @Test void landAndNearSeaShareTierAndRestartPreservesIt() throws Exception {
        var config=AdventureConfig.parse(defaults());
        var snapshot=new WorldRegionSnapshot("fixture","minecraft:overworld",16,"a",Map.of(
            WorldRegionSnapshot.key(-1,0),new WorldRegionSnapshot.Cell("a",true,false),
            WorldRegionSnapshot.key(-2,0),new WorldRegionSnapshot.Cell("a",false,false),
            WorldRegionSnapshot.key(-3,0),new WorldRegionSnapshot.Cell("ocean_1",false,true)),Map.of("a",new Centre(-8,8)));
        var world=new AdventureWorld(snapshot,config,dir.resolve("tiers.json"));
        assertEquals(1,world.at("minecraft:overworld",-1,0).tier());assertEquals(1,world.at("minecraft:overworld",-17,0).tier());
        assertEquals("nearSea",world.at("minecraft:overworld",-17,0).terrain());assertEquals(0,world.at("minecraft:overworld",-33,0).tier());
        assertNull(world.at("minecraft:the_nether",-1,0));assertNull(world.at("minecraft:overworld",1000,0));
        assertEquals(world.tiers,new AdventureWorld(snapshot,config,dir.resolve("tiers.json")).tiers);
        var changed=new WorldRegionSnapshot("changed","minecraft:overworld",16,"a",snapshot.cells(),snapshot.continents());
        assertThrows(java.io.IOException.class,()->new AdventureWorld(changed,config,dir.resolve("tiers.json")));
    }
    @Test void candidatesRespectNegativeCellsAndSeparation() {
        var pool=new AdventureConfig.StructurePool("test",32,12,1,List.of());
        List<AdventureWorld.Candidate> candidates=new ArrayList<>();
        for(int z=-4;z<4;z++)for(int x=-4;x<4;x++){
            var c=AdventureWorld.candidate(123,pool,x,z);assertEquals(x,Math.floorDiv(c.chunkX(),32));assertEquals(z,Math.floorDiv(c.chunkZ(),32));
            assertEquals(c,AdventureWorld.candidate(123,pool,x,z));candidates.add(c);
        }
        for(var a:candidates)for(var b:candidates)if(a!=b)assertTrue(Math.hypot(a.chunkX()-b.chunkX(),a.chunkZ()-b.chunkZ())>=12);
    }
    @Test void missingNestedFieldsAndFractionalCountsAreRejected() throws Exception {
        var text=defaults();text.put("mob_pools.json",text.get("mob_pools.json").replace("\"minCount\": 1", "\"minCount\": 1.5"));
        assertThrows(IllegalArgumentException.class,()->AdventureConfig.parse(text));
        var other=defaults();other.put("mob_pools.json",other.get("mob_pools.json").replace("\"chance\"", "\"chnace\""));
        assertThrows(IllegalArgumentException.class,()->AdventureConfig.parse(other));
    }
    @Test void twoHundredContinentsProduceAConnectedStablePartition() {
        var random=new Random(42);Map<String,Centre> points=new TreeMap<>();
        for(int i=0;i<200;i++)points.put("c"+i,new Centre(random.nextInt(100000),random.nextInt(100000)));
        long started=System.nanoTime();var tiers=ContinentTiers.compute(points,"c0",5);
        assertEquals(200,tiers.size());assertEquals(1,tiers.get("c0"));assertTrue(tiers.values().stream().allMatch(t->t>=1 && t<=5));
        System.out.println("200-continent tier computation ms="+(System.nanoTime()-started)/1_000_000.0);
    }
}
