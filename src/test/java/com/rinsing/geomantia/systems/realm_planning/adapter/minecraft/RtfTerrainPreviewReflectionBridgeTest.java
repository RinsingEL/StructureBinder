package com.rinsing.geomantia.systems.realm_planning.adapter.minecraft;

import com.rinsing.geomantia.systems.realm_planning.application.terrain.TerrainPreviewProvider;
import com.rinsing.geomantia.systems.realm_planning.application.terrain.TerrainPreviewProviderAvailability;
import com.rinsing.geomantia.systems.realm_planning.application.terrain.TerrainPreviewProviderDescriptor;
import com.rinsing.geomantia.systems.realm_planning.application.terrain.TerrainPreviewSample;
import com.rinsing.geomantia.systems.realm_planning.application.terrain.TerrainPreviewSourceKind;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RtfTerrainPreviewReflectionBridgeTest {
    @Test
    void samplesRtf005HeightmapContractWithoutRuntimeTileLookup() throws Exception {
        FakeHeightmap005 heightmap = new FakeHeightmap005();
        var binding = RtfTerrainPreviewReflectionBridge.bind(
                new FakeRandomState(new FakeContext(new FakeGenerator(heightmap))),
                FakeRandomStateContract.class,
                FakeCell.class
        );

        var sample = binding.sample(120, -45);

        assertEquals(75, sample.elevation());
        assertFalse(sample.water());
        assertEquals("rtf:plateau", sample.terrainId());
        assertEquals("rtf:temperate_forest", sample.sourceBiomeId());
        assertEquals("v0_0_5", binding.apiVariant());
        assertEquals(1, heightmap.calls);
    }

    @Test
    void samplesRtf006HeightmapContractWithClimateEnabled() throws Exception {
        FakeHeightmap006 heightmap = new FakeHeightmap006();
        var binding = RtfTerrainPreviewReflectionBridge.bind(
                new FakeRandomState(new FakeContext(new FakeGenerator(heightmap))),
                FakeRandomStateContract.class,
                FakeCell.class
        );

        var sample = binding.sample(-8, 9);

        assertEquals(50, sample.elevation());
        assertTrue(sample.water());
        assertTrue(heightmap.applyClimate);
        assertEquals("v0_0_6", binding.apiVariant());
    }

    @Test
    void usesIndependentCellsAcrossSamplingThreads() throws Exception {
        FakeHeightmap005 heightmap = new FakeHeightmap005();
        var binding = RtfTerrainPreviewReflectionBridge.bind(
                new FakeRandomState(new FakeContext(new FakeGenerator(heightmap))),
                FakeRandomStateContract.class,
                FakeCell.class
        );
        CountDownLatch start = new CountDownLatch(1);
        Thread first = new Thread(() -> sampleAfter(start, binding, 1));
        Thread second = new Thread(() -> sampleAfter(start, binding, 2));
        first.start();
        second.start();
        start.countDown();
        first.join();
        second.join();

        assertEquals(2, heightmap.cellIdentities.size());
    }

    @Test
    void samplingFailureIsExplicitAndDoesNotReturnPartialData() throws Exception {
        var binding = RtfTerrainPreviewReflectionBridge.bind(
                new FakeRandomState(new FakeContext(new FakeGenerator(new FailingHeightmap()))),
                FakeRandomStateContract.class,
                FakeCell.class
        );

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> binding.sample(77, -91));

        assertTrue(error.getMessage().contains("77,-91"));
        assertTrue(error.getMessage().contains("fixture_failure"));
    }

    @Test
    void factoryKeepsUnavailableNativeProviderReasonWhenSelectingFallback() {
        TerrainPreviewProvider nativeProvider = provider("rtf", true,
                TerrainPreviewProviderAvailability.unavailable("generator_context_unavailable"));
        TerrainPreviewProvider fallback = provider("fallback", false,
                TerrainPreviewProviderAvailability.ready());

        var selection = MinecraftTerrainPreviewProviderFactory.assemble(nativeProvider, fallback).select();

        assertEquals("fallback", selection.providerId());
        assertEquals("rtf=generator_context_unavailable", selection.fallbackReason());
        assertNotEquals(nativeProvider, selection.provider());
    }

    private static void sampleAfter(CountDownLatch start,
            RtfTerrainPreviewReflectionBridge.Binding binding, int coordinate) {
        try {
            start.await();
            binding.sample(coordinate, coordinate);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }

    private static TerrainPreviewProvider provider(String id, boolean fast,
            TerrainPreviewProviderAvailability availability) {
        return new TerrainPreviewProvider() {
            @Override
            public TerrainPreviewProviderDescriptor descriptor() {
                return new TerrainPreviewProviderDescriptor(id, fast
                        ? TerrainPreviewSourceKind.GENERATOR_NATIVE
                        : TerrainPreviewSourceKind.GIS_ATLAS_SAMPLER, fast, id + ":fingerprint");
            }

            @Override
            public TerrainPreviewProviderAvailability availability() {
                return availability;
            }

            @Override
            public TerrainPreviewSample sample(int blockX, int blockZ) {
                return new TerrainPreviewSample(blockX, blockZ, 64, false, "minecraft:plains");
            }
        };
    }

    public interface FakeRandomStateContract {
        FakeContext generatorContext();
    }

    public static final class FakeRandomState implements FakeRandomStateContract {
        private final FakeContext context;

        private FakeRandomState(FakeContext context) {
            this.context = context;
        }

        @Override
        public FakeContext generatorContext() {
            return context;
        }
    }

    public static final class FakeContext {
        public final FakeGenerator generator;
        public final FakeLevels levels = new FakeLevels();
        public final String preset = "fixture_preset";

        private FakeContext(FakeGenerator generator) {
            this.generator = generator;
        }
    }

    public static final class FakeGenerator {
        private final Object heightmap;

        private FakeGenerator(Object heightmap) {
            this.heightmap = heightmap;
        }

        public Object getHeightmap() {
            return heightmap;
        }
    }

    public static final class FakeLevels {
        public float water = 0.6F;

        public int scale(float value) {
            return (int) (value * 100);
        }
    }

    public static final class FakeCell {
        public float height;
        public FakeTerrain terrain = new FakeTerrain("none");
        public FakeBiome biome = FakeBiome.GRASSLAND;

        public FakeCell reset() {
            height = 0;
            terrain = new FakeTerrain("none");
            biome = FakeBiome.GRASSLAND;
            return this;
        }
    }

    public static final class FakeTerrain {
        private final String name;

        private FakeTerrain(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }

    public enum FakeBiome {
        GRASSLAND,
        TEMPERATE_FOREST
    }

    public static final class FakeHeightmap005 {
        private int calls;
        private final Set<Integer> cellIdentities = ConcurrentHashMap.newKeySet();

        public void apply(FakeCell cell, float x, float z) {
            calls++;
            cellIdentities.add(System.identityHashCode(cell));
            cell.height = 0.75F;
            cell.terrain = new FakeTerrain("Plateau");
            cell.biome = FakeBiome.TEMPERATE_FOREST;
        }
    }

    public static final class FakeHeightmap006 {
        private boolean applyClimate;

        public void apply(FakeCell cell, float x, float z, boolean applyClimate) {
            this.applyClimate = applyClimate;
            cell.height = 0.5F;
            cell.terrain = new FakeTerrain("Lake");
            cell.biome = FakeBiome.GRASSLAND;
        }
    }

    public static final class FailingHeightmap {
        public void apply(FakeCell cell, float x, float z) {
            throw new IllegalStateException("fixture_failure");
        }
    }
}
