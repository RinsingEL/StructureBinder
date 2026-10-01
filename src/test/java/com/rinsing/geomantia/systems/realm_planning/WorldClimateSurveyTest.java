package com.rinsing.geomantia.systems.realm_planning;

import com.google.gson.*;
import com.rinsing.geomantia.systems.realm_planning.application.terrain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class WorldClimateSurveyTest {
    @TempDir Path root;
    private final TerrainSamplingProvenance provenance = new TerrainSamplingProvenance(true,"rtf","generator_native",true,"","fixture","estimated_coarse_heightmap");
    private void fixture() throws Exception {
        Files.writeString(root.resolve("world_survey_manifest.json"),"""
                {"status":"sealed","runId":"fixture","configHash":"hash",
                 "config":{"worldSeed":"42","dimensionId":"minecraft:overworld","cellStepBlocks":128,"terrainProvider":{"sourceFingerprint":"fixture"}},
                 "grid":{"originBlockX":-128,"originBlockZ":-128,"width":2,"height":2,"cellCount":4},
                 "scanBounds":{"minBlockX":-128,"minBlockZ":-128,"maxBlockX":127,"maxBlockZ":127}}
                """);
        Files.writeString(root.resolve("world_patch_map.json"),"preserve terrain");
        Files.writeString(root.resolve("realm_profiles.json"),"preserve planning");
    }
    private TerrainClimateSampler sampler(AtomicInteger count) {
        return new TerrainClimateSampler() {
            public boolean climateAvailable(){return true;}
            public TerrainClimateSample sampleClimate(int x,int z) {
                count.incrementAndGet();
                return new TerrainClimateSample(x<0?.2:.8,z<0?.3:.9,.45,.55,x<0);
            }
        };
    }
    @Test void exportsNativeRegionAndFinalFieldsAtCentersAndReusesVerifiedSidecar() throws Exception {
        fixture();AtomicInteger count=new AtomicInteger();
        byte[] before=Files.readAllBytes(root.resolve("world_survey_manifest.json"));
        var result=WorldClimateSurvey.ensure(root,"42","minecraft:overworld",provenance,sampler(count),()->false);
        assertEquals(4,count.get());assertEquals(4,result.get("sampleCount").getAsInt());
        var data=JsonParser.parseString(Files.readString(root.resolve(WorldClimateSurvey.GRID))).getAsJsonObject();
        var first=data.getAsJsonArray("cells").get(0).getAsJsonArray();
        assertEquals(-1,first.get(0).getAsInt());assertEquals(-64,first.get(2).getAsInt());
        assertEquals(.2,first.get(4).getAsDouble());assertEquals(.3,first.get(5).getAsDouble());
        assertEquals(.45,first.get(6).getAsDouble());assertTrue(first.get(8).getAsBoolean());
        assertEquals("regionTemperature",data.get("previewTemperatureField").getAsString());
        WorldClimateSurvey.ensure(root,"42","minecraft:overworld",provenance,sampler(count),()->false);
        assertEquals(4,count.get());
        assertArrayEquals(before,Files.readAllBytes(root.resolve("world_survey_manifest.json")));
        assertEquals("preserve terrain",Files.readString(root.resolve("world_patch_map.json")));
        assertEquals("preserve planning",Files.readString(root.resolve("realm_profiles.json")));
        Files.writeString(root.resolve(WorldClimateSurvey.GRID),"broken");
        WorldClimateSurvey.ensure(root,"42","minecraft:overworld",provenance,sampler(count),()->false);
        assertEquals(8,count.get());
    }
    @Test void rejectsChangedWorldOrPresetBeforeSampling() throws Exception {
        fixture();AtomicInteger count=new AtomicInteger();
        assertThrows(java.io.IOException.class,()->WorldClimateSurvey.ensure(root,"43","minecraft:overworld",provenance,sampler(count),()->false));
        var other=new TerrainSamplingProvenance(true,"rtf","generator_native",true,"","changed","estimated_coarse_heightmap");
        assertThrows(java.io.IOException.class,()->WorldClimateSurvey.ensure(root,"42","minecraft:overworld",other,sampler(count),()->false));
        assertEquals(0,count.get());assertFalse(Files.exists(root.resolve(WorldClimateSurvey.MANIFEST)));
    }
    @Test void optionalSupplementRecordsMismatchWithoutBlockingSealedSurvey() throws Exception {
        fixture();AtomicInteger count=new AtomicInteger();
        byte[] before=Files.readAllBytes(root.resolve("world_survey_manifest.json"));
        var other=new TerrainSamplingProvenance(true,"rtf","generator_native",true,"","changed","estimated_coarse_heightmap");
        assertNull(WorldClimateSurvey.supplement(root,"42","minecraft:overworld",other,sampler(count),()->false));
        assertEquals(0,count.get());
        assertArrayEquals(before,Files.readAllBytes(root.resolve("world_survey_manifest.json")));
        var diagnostic=JsonParser.parseString(Files.readString(root.resolve("world_climate_failure.json"))).getAsJsonObject();
        assertEquals("changed",diagnostic.getAsJsonObject("currentProvider").get("sourceFingerprint").getAsString());
        assertEquals("fixture",diagnostic.getAsJsonObject("sealedConfig").getAsJsonObject("terrainProvider").get("sourceFingerprint").getAsString());
        assertFalse(Files.exists(root.resolve(WorldClimateSurvey.MANIFEST)));
        assertNotNull(WorldClimateSurvey.supplement(root,"42","minecraft:overworld",provenance,sampler(count),()->false));
        assertFalse(Files.exists(root.resolve("world_climate_failure.json")));
    }
    @Test void optionalSupplementStillHonorsCancellation() throws Exception {
        fixture();
        assertThrows(java.util.concurrent.CancellationException.class,()->WorldClimateSurvey.supplement(
                root,"42","minecraft:overworld",provenance,sampler(new AtomicInteger()),()->true));
        assertFalse(Files.exists(root.resolve("world_climate_failure.json")));
    }
    @Test void cancellationNeverPublishesPartialClimate() throws Exception {
        fixture();AtomicInteger count=new AtomicInteger();
        assertThrows(java.util.concurrent.CancellationException.class,()->WorldClimateSurvey.ensure(root,"42","minecraft:overworld",provenance,sampler(count),()->count.get()>=2));
        assertFalse(Files.exists(root.resolve(WorldClimateSurvey.MANIFEST)));
        assertFalse(Files.exists(root.resolve(WorldClimateSurvey.GRID)));
    }
    @Test void unsupportedSamplerDoesNotInventClimate() throws Exception {
        fixture();
        TerrainClimateSampler unavailable=new TerrainClimateSampler() {
            public boolean climateAvailable(){return false;}
            public TerrainClimateSample sampleClimate(int x,int z){throw new AssertionError();}
        };
        assertNull(WorldClimateSurvey.ensure(root,"42","minecraft:overworld",provenance,unavailable,()->false));
        assertFalse(Files.exists(root.resolve(WorldClimateSurvey.MANIFEST)));
        assertThrows(IllegalArgumentException.class,()->new TerrainClimateSample(Double.NaN,.3,.4,.5,false));
    }
}
