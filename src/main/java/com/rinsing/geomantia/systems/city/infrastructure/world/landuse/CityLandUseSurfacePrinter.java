package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Resolves globally phased surface-print recipes without sampling terrain or splitting by chunk. */
public final class CityLandUseSurfacePrinter {

    public List<PrintOperation> operationsAt(Recipe recipe, int worldX, int worldZ) {
        Objects.requireNonNull(recipe, "recipe");
        List<Layer> layers = recipe.pattern().layersAt(worldX, worldZ);
        List<PrintOperation> result = new ArrayList<>(layers.size());
        for (int index = 0; index < layers.size(); index++) {
            Layer layer = layers.get(index);
            result.add(new PrintOperation(index, layer.blockId(), layer.surfaceOffset(),
                    layer.requireReplaceableTarget()));
        }
        return List.copyOf(result);
    }

    public static Recipe uniform(String recipeId, String blockId) {
        return new Recipe(recipeId, new UniformPattern(List.of(Layer.surface(blockId))));
    }

    public enum Axis {
        X,
        Z
    }

    public interface Pattern {
        List<Layer> layersAt(int worldX, int worldZ);
    }

    public record Recipe(String recipeId, Pattern pattern) {
        public Recipe {
            if (recipeId == null || recipeId.isBlank()) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_RECIPE_ID_REQUIRED");
            }
            Objects.requireNonNull(pattern, "pattern");
        }
    }

    public record UniformPattern(List<Layer> layers) implements Pattern {
        public UniformPattern {
            layers = validatedLayers(layers, "CITY_LAND_USE_SURFACE_PRINT_UNIFORM_LAYERS_REQUIRED");
        }

        @Override
        public List<Layer> layersAt(int worldX, int worldZ) {
            return layers;
        }
    }

    /**
     * Repeats bands against absolute world coordinates. Chunk ownership therefore cannot restart
     * the pattern phase at x/z 0 of each owner.
     */
    public record CrossSectionRepeatPattern(Axis axis,
                                            int originCoordinate,
                                            int offsetBlocks,
                                            List<Band> bands) implements Pattern {
        public CrossSectionRepeatPattern {
            Objects.requireNonNull(axis, "axis");
            bands = List.copyOf(Objects.requireNonNull(bands, "bands"));
            if (bands.isEmpty()) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_BANDS_REQUIRED");
            }
            long period = bands.stream().mapToLong(Band::widthBlocks).sum();
            if (period > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_PERIOD_TOO_LARGE");
            }
        }

        @Override
        public List<Layer> layersAt(int worldX, int worldZ) {
            int coordinate = axis == Axis.X ? worldX : worldZ;
            int period = bands.stream().mapToInt(Band::widthBlocks).sum();
            long relative = (long) coordinate - originCoordinate - offsetBlocks;
            int phase = (int) Math.floorMod(relative, (long) period);
            int cursor = 0;
            for (Band band : bands) {
                cursor += band.widthBlocks();
                if (phase < cursor) {
                    return band.layers();
                }
            }
            throw new IllegalStateException("CITY_LAND_USE_SURFACE_PRINT_PHASE_UNRESOLVED");
        }
    }

    public record Band(int widthBlocks, List<Layer> layers) {
        public Band {
            if (widthBlocks <= 0) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_BAND_WIDTH_INVALID");
            }
            layers = validatedLayers(layers, "CITY_LAND_USE_SURFACE_PRINT_BAND_LAYERS_REQUIRED");
        }
    }

    /** surfaceOffset=0 replaces the natural top block; positive offsets place ordered overlays. */
    public record Layer(String blockId, int surfaceOffset, boolean requireReplaceableTarget) {
        public Layer {
            if (blockId == null || blockId.isBlank() || surfaceOffset < 0
                    || (surfaceOffset > 0 && !requireReplaceableTarget)) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_LAYER_INVALID");
            }
        }

        public static Layer surface(String blockId) {
            return new Layer(blockId, 0, false);
        }

        public static Layer overlay(String blockId, int surfaceOffset) {
            return new Layer(blockId, surfaceOffset, true);
        }
    }

    public record PrintOperation(int layerOrder,
                                 String blockId,
                                 int surfaceOffset,
                                 boolean requireReplaceableTarget) {
        public PrintOperation {
            if (layerOrder < 0 || blockId == null || blockId.isBlank() || surfaceOffset < 0
                    || (surfaceOffset > 0 && !requireReplaceableTarget)) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_OPERATION_INVALID");
            }
        }
    }

    private static List<Layer> validatedLayers(List<Layer> values, String emptyReason) {
        List<Layer> layers = List.copyOf(Objects.requireNonNull(values, "layers"));
        if (layers.isEmpty()) {
            throw new IllegalArgumentException(emptyReason);
        }
        Set<Integer> offsets = new HashSet<>();
        int previousOffset = -1;
        for (Layer layer : layers) {
            Objects.requireNonNull(layer, "layer");
            if (!offsets.add(layer.surfaceOffset())) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_LAYER_OFFSET_DUPLICATE");
            }
            if (layer.surfaceOffset() < previousOffset) {
                throw new IllegalArgumentException("CITY_LAND_USE_SURFACE_PRINT_LAYER_ORDER_INVALID");
            }
            previousOffset = layer.surfaceOffset();
        }
        return layers;
    }
}
