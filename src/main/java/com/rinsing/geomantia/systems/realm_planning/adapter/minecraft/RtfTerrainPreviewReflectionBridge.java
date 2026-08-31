package com.rinsing.geomantia.systems.realm_planning.adapter.minecraft;

import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Objects;

final class RtfTerrainPreviewReflectionBridge {
    static final String RTF_RANDOM_STATE = "raccoonman.reterraforged.world.worldgen.RTFRandomState";
    static final String RTF_CELL = "raccoonman.reterraforged.world.worldgen.cell.Cell";

    private RtfTerrainPreviewReflectionBridge() {
    }

    static Probe probe(Object randomState) {
        return probe(randomState, null);
    }

    static Probe probe(Object randomState, Object registryAccess) {
        Objects.requireNonNull(randomState, "randomState");
        ClassLoader loader = randomState.getClass().getClassLoader();
        try {
            Class<?> randomStateContract = Class.forName(RTF_RANDOM_STATE, false, loader);
            if (!randomStateContract.isInstance(randomState)) {
                return Probe.unavailable("random_state_not_rtf");
            }
            Class<?> cellType = Class.forName(RTF_CELL, false, loader);
            return Probe.available(bind(randomState, randomStateContract, cellType, registryAccess));
        } catch (ClassNotFoundException ex) {
            return Probe.unavailable("rtf_api_not_present");
        } catch (GeneratorContextUnavailableException ex) {
            return Probe.unavailable("generator_context_unavailable");
        } catch (ReflectiveOperationException | LinkageError ex) {
            return Probe.unavailable("rtf_reflection_contract_incompatible:" + ex.getClass().getSimpleName());
        } catch (RuntimeException ex) {
            return Probe.unavailable("rtf_initialization_failed:" + ex.getClass().getSimpleName());
        }
    }

    static Binding bind(Object randomState, Class<?> randomStateContract, Class<?> cellType)
            throws ReflectiveOperationException {
        return bind(randomState, randomStateContract, cellType, null);
    }

    static Binding bind(Object randomState, Class<?> randomStateContract, Class<?> cellType,
            Object registryAccess) throws ReflectiveOperationException {
        Objects.requireNonNull(randomState, "randomState");
        Objects.requireNonNull(randomStateContract, "randomStateContract");
        Objects.requireNonNull(cellType, "cellType");

        Method generatorContextMethod = randomStateContract.getMethod("generatorContext");
        Object context = invoke(generatorContextMethod, randomState);
        if (context == null && registryAccess != null) {
            synchronized (randomState) {
                context = invoke(generatorContextMethod, randomState);
                if (context == null) {
                    Method initialize = findInitializeMethod(randomStateContract, registryAccess);
                    invoke(initialize, randomState, registryAccess);
                    context = invoke(generatorContextMethod, randomState);
                }
            }
        }
        if (context == null) {
            throw new GeneratorContextUnavailableException();
        }

        Field generatorField = context.getClass().getField("generator");
        Field levelsField = context.getClass().getField("levels");
        Object generator = requireMember(generatorField.get(context), "generator");
        Object levels = requireMember(levelsField.get(context), "levels");
        Object heightmap = requireMember(generator.getClass().getMethod("getHeightmap").invoke(generator), "heightmap");

        Method apply;
        boolean explicitClimate;
        try {
            apply = heightmap.getClass().getMethod("apply", cellType, float.class, float.class, boolean.class);
            explicitClimate = true;
        } catch (NoSuchMethodException ignored) {
            apply = heightmap.getClass().getMethod("apply", cellType, float.class, float.class);
            explicitClimate = false;
        }

        Field presetField = context.getClass().getField("preset");
        Object preset = presetField.get(context);
        return new Binding(
                cellType.getConstructor(),
                cellType.getMethod("reset"),
                heightmap,
                apply,
                explicitClimate,
                levels,
                levels.getClass().getMethod("scale", float.class),
                levels.getClass().getField("water"),
                cellType.getField("height"),
                cellType.getField("terrain"),
                cellType.getField("biome"),
                cellType.getField("terrain").getType().getMethod("getName"),
                explicitClimate ? "explicit_climate" : "inferred_climate",
                presetFingerprintMaterial(preset)
        );
    }

    private static Method findInitializeMethod(Class<?> contract, Object registryAccess)
            throws NoSuchMethodException {
        for (Method method : contract.getMethods()) {
            if (method.getName().equals("initialize") && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].isAssignableFrom(registryAccess.getClass())) {
                return method;
            }
        }
        throw new NoSuchMethodException("RTFRandomState.initialize(RegistryAccess)");
    }

    @SuppressWarnings("unchecked")
    private static String presetFingerprintMaterial(Object preset) throws ReflectiveOperationException {
        if (preset == null) {
            return "null";
        }
        try {
            Object codecValue = preset.getClass().getField("DIRECT_CODEC").get(null);
            if (!(codecValue instanceof Codec<?>)) {
                throw new ReflectiveOperationException("RTF Preset.DIRECT_CODEC is not a Codec.");
            }
            Codec<Object> codec = (Codec<Object>) codecValue;
            JsonElement encoded = codec.encodeStart(JsonOps.INSTANCE, preset).result()
                    .orElseThrow(() -> new ReflectiveOperationException("RTF preset codec returned no result."));
            return encoded.toString();
        } catch (NoSuchFieldException ex) {
            // Test doubles and future compatible contracts may expose an already stable value.
            return preset.getClass().getName() + ":" + preset;
        }
    }

    private static Object requireMember(Object value, String name) throws ReflectiveOperationException {
        if (value == null) {
            throw new ReflectiveOperationException("RTF generator context member is null: " + name);
        }
        return value;
    }

    private static Object invoke(Method method, Object target, Object... args) throws ReflectiveOperationException {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof ReflectiveOperationException reflective) {
                throw reflective;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new ReflectiveOperationException(cause);
        }
    }

    record Probe(Binding binding, String unavailableReason) {
        Probe {
            unavailableReason = unavailableReason == null ? "" : unavailableReason.trim();
            if ((binding == null) == unavailableReason.isEmpty()) {
                throw new IllegalArgumentException("Exactly one of binding or unavailableReason is required.");
            }
        }

        static Probe available(Binding binding) {
            return new Probe(Objects.requireNonNull(binding, "binding"), "");
        }

        static Probe unavailable(String reason) {
            return new Probe(null, reason);
        }

        boolean available() {
            return binding != null;
        }
    }

    static final class Binding {
        private final Method reset;
        private final Object heightmap;
        private final Method apply;
        private final boolean explicitClimate;
        private final Object levels;
        private final Method scale;
        private final Field water;
        private final Field height;
        private final Field terrain;
        private final Field biome;
        private final Method terrainName;
        private final String apiVariant;
        private final String presetFingerprintMaterial;
        private final ThreadLocal<Object> cells;

        private Binding(Constructor<?> cellConstructor, Method reset, Object heightmap, Method apply,
                boolean explicitClimate, Object levels, Method scale, Field water, Field height,
                Field terrain, Field biome, Method terrainName, String apiVariant,
                String presetFingerprintMaterial) throws ReflectiveOperationException {
            this.reset = reset;
            this.heightmap = heightmap;
            this.apply = apply;
            this.explicitClimate = explicitClimate;
            this.levels = levels;
            this.scale = scale;
            this.water = water;
            this.height = height;
            this.terrain = terrain;
            this.biome = biome;
            this.terrainName = terrainName;
            this.apiVariant = apiVariant;
            this.presetFingerprintMaterial = presetFingerprintMaterial;
            cellConstructor.newInstance();
            this.cells = ThreadLocal.withInitial(() -> newCell(cellConstructor));
        }

        RawSample sample(int blockX, int blockZ) {
            try {
                Object cell = cells.get();
                invoke(reset, cell);
                if (explicitClimate) {
                    invoke(apply, heightmap, cell, (float) blockX, (float) blockZ, true);
                } else {
                    invoke(apply, heightmap, cell, (float) blockX, (float) blockZ);
                }
                float rawHeight = height.getFloat(cell);
                int elevation = ((Number) invoke(scale, levels, rawHeight)).intValue();
                boolean sampledWater = rawHeight <= water.getFloat(levels);
                Object sampledTerrain = Objects.requireNonNull(terrain.get(cell), "RTF cell terrain is null.");
                Object sampledBiome = Objects.requireNonNull(biome.get(cell), "RTF cell biome is null.");
                String terrainId = "rtf:" + normalize(String.valueOf(invoke(terrainName, sampledTerrain)));
                String sourceBiomeId = "rtf:" + normalize(enumName(sampledBiome));
                return new RawSample(elevation, sampledWater, terrainId, sourceBiomeId);
            } catch (ReflectiveOperationException | RuntimeException ex) {
                throw new IllegalStateException("RTF heightmap preview sample failed at "
                        + blockX + "," + blockZ + ": " + message(ex), ex);
            }
        }

        String apiVariant() {
            return apiVariant;
        }

        String presetFingerprintMaterial() {
            return presetFingerprintMaterial;
        }

        private static Object newCell(Constructor<?> constructor) {
            try {
                return constructor.newInstance();
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("Failed to create thread-local RTF Cell.", ex);
            }
        }

        private static String enumName(Object value) {
            return value instanceof Enum<?> enumValue ? enumValue.name() : String.valueOf(value);
        }

        private static String normalize(String value) {
            String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            return normalized.isEmpty() ? "unknown" : normalized;
        }

        private static String message(Exception ex) {
            String message = ex.getMessage();
            return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message.trim();
        }
    }

    record RawSample(int elevation, boolean water, String terrainId, String sourceBiomeId) {
    }

    private static final class GeneratorContextUnavailableException extends ReflectiveOperationException {
    }
}
