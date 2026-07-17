package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgram;
import com.rinsing.geomantia.systems.city.infrastructure.dressing.CityDecorationChunkCompiler;
import com.rinsing.geomantia.systems.city.infrastructure.dressing.CityDecorationContentCatalog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class CityDecorationNbtPlacer {
    public PlacementResult place(CityDecorationChunkCompiler.Fragment fragment, PlacementWorld world) {
        PreflightResult preflight = preflight(fragment, world);
        if (!preflight.ready()) {
            return PlacementResult.failed(preflight.reasonCode(), preflight.baseY(), preflight.targetCount());
        }
        return placePrepared(fragment, preflight, world);
    }

    public PreflightResult preflight(CityDecorationChunkCompiler.Fragment fragment, PlacementWorld world) {
        return preflight(fragment, fragment.layers().get(0), world);
    }

    public PreflightResult preflight(CityDecorationChunkCompiler.Fragment fragment,
                                     CityDecorationChunkCompiler.FragmentLayer layer,
                                     PlacementWorld world) {
        Objects.requireNonNull(fragment, "fragment");
        Objects.requireNonNull(layer, "layer");
        Objects.requireNonNull(world, "world");
        if (fragment.status() != CityDecorationChunkCompiler.Status.READY || fragment.datumY() == null) {
            return PreflightResult.failed("CITY_DECORATION_FRAGMENT_NOT_READY", 0, 0, BlockPos.ZERO, Rotation.NONE);
        }
        CityDecorationContentCatalog.Content content = layer.content();
        int surfaceOffset = "above_surface".equals(content.placementMode()) ? 1 : 0;
        int baseY = fragment.datumY() + surfaceOffset
                - content.groundPlaneLocalY() - content.embedDepthBlocks();
        Rotation rotation = rotation(fragment.rotationDegrees());
        BlockPos origin = new BlockPos(fragment.worldAnchor().x(), baseY, fragment.worldAnchor().z());
        if (content.plant()) {
            if (!world.ensureCanWrite(origin)) {
                return PreflightResult.failed("CITY_DECORATION_TARGET_NOT_WRITABLE", baseY, 1,
                        origin, rotation);
            }
            PlantTarget plant = world.inspectPlant(content.plantBlockState(), origin, rotation);
            if (plant == null) {
                return PreflightResult.failed("CITY_DECORATION_TARGET_STATE_UNAVAILABLE", baseY, 1,
                        origin, rotation);
            }
            if (!plant.alreadySatisfied() && (!plant.replaceable() || !plant.supportSatisfied())) {
                return PreflightResult.failed(plant.supportSatisfied()
                                ? "CITY_DECORATION_REPLACE_POLICY_REJECTED"
                                : "CITY_DECORATION_PLANT_SUPPORT_MISSING",
                        baseY, 1, origin, rotation);
            }
            return PreflightResult.ready(baseY, 1, origin, rotation);
        }
        boolean clearTemplateAir = "clear_template_air".equals(content.clearanceMode());
        List<PlacementTarget> targets = targets(content.template(), origin, rotation, clearTemplateAir);
        for (PlacementTarget target : targets) {
            if (!world.ensureCanWrite(target.worldPos())) {
                return PreflightResult.failed("CITY_DECORATION_TARGET_NOT_WRITABLE", baseY, targets.size(),
                        origin, rotation);
            }
            ExistingTarget existing = world.inspect(target.worldPos());
            if (existing == null) {
                return PreflightResult.failed("CITY_DECORATION_TARGET_STATE_UNAVAILABLE", baseY, targets.size(),
                        origin, rotation);
            }
            boolean allowed = switch (content.replacePolicy()) {
                case "replaceable_only" -> existing.replaceable();
                case "surface_replaceable" -> target.templateAir()
                        ? existing.replaceable() || existing.surfaceReplaceable()
                        : target.localY() <= content.groundPlaneLocalY()
                        ? existing.surfaceReplaceable() : existing.replaceable();
                default -> false;
            };
            if (!allowed) {
                return PreflightResult.failed("CITY_DECORATION_REPLACE_POLICY_REJECTED", baseY, targets.size(),
                        origin, rotation);
            }
        }
        return PreflightResult.ready(baseY, targets.size(), origin, rotation);
    }

    PlacementResult placePrepared(CityDecorationChunkCompiler.Fragment fragment,
                                  PreflightResult preflight,
                                  PlacementWorld world) {
        return placePrepared(fragment, fragment.layers().get(0), preflight, world);
    }

    PlacementResult placePrepared(CityDecorationChunkCompiler.Fragment fragment,
                                  CityDecorationChunkCompiler.FragmentLayer layer,
                                  PreflightResult preflight,
                                  PlacementWorld world) {
        if (!preflight.ready()) {
            return PlacementResult.failed(preflight.reasonCode(), preflight.baseY(), preflight.targetCount());
        }
        CityDecorationContentCatalog.Content content = layer.content();
        boolean placed = content.plant()
                ? world.placePlant(content.plantBlockState(), preflight.origin(), preflight.rotation())
                : world.placeTemplate(content.template(), preflight.origin(), preflight.rotation(),
                stableSeed(templateSeedKey(fragment, layer)),
                "preserve".equals(content.clearanceMode()));
        return placed
                ? PlacementResult.applied(preflight.baseY(), preflight.targetCount())
                : PlacementResult.failed("CITY_DECORATION_TEMPLATE_PLACE_FAILED", preflight.baseY(),
                preflight.targetCount());
    }

    static List<PlacementTarget> targets(CompoundTag templateNbt, BlockPos origin, Rotation rotation,
                                         boolean includeTemplateAir) {
        ListTag blocks = templateNbt.getList("blocks", 10);
        ListTag palette = templateNbt.getList("palette", 10);
        List<PlacementTarget> targets = new ArrayList<>(blocks.size());
        for (int index = 0; index < blocks.size(); index++) {
            CompoundTag block = blocks.getCompound(index);
            int stateIndex = block.getInt("state");
            boolean templateAir = stateIndex >= 0 && stateIndex < palette.size()
                    && "minecraft:air".equals(palette.getCompound(stateIndex).getString("Name"));
            if (templateAir && !includeTemplateAir) {
                continue;
            }
            ListTag pos = block.getList("pos", 3);
            BlockPos local = new BlockPos(pos.getInt(0), pos.getInt(1), pos.getInt(2));
            BlockPos transformed = StructureTemplate.transform(local, Mirror.NONE, rotation, BlockPos.ZERO);
            targets.add(new PlacementTarget(local.getY(), transformed.offset(origin), templateAir));
        }
        return List.copyOf(targets);
    }

    static Rotation rotation(int degrees) {
        return switch (Math.floorMod(degrees, 360)) {
            case 0 -> Rotation.NONE;
            case 90 -> Rotation.CLOCKWISE_90;
            case 180 -> Rotation.CLOCKWISE_180;
            case 270 -> Rotation.COUNTERCLOCKWISE_90;
            default -> throw new IllegalArgumentException("CITY_DECORATION_ROTATION_UNSUPPORTED: " + degrees);
        };
    }

    private static long stableSeed(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            long seed = 0L;
            for (int index = 0; index < Long.BYTES; index++) {
                seed = (seed << 8) | (hash[index] & 0xffL);
            }
            return seed;
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", ex);
        }
    }

    private static String templateSeedKey(CityDecorationChunkCompiler.Fragment fragment,
                                          CityDecorationChunkCompiler.FragmentLayer layer) {
        return fragment.fragmentId() + "/" + layer.layerId();
    }

    public interface PlacementWorld {
        boolean ensureCanWrite(BlockPos pos);

        ExistingTarget inspect(BlockPos pos);

        boolean placeTemplate(CompoundTag templateNbt, BlockPos origin, Rotation rotation, long seed,
                              boolean ignoreTemplateAir);

        default PlantTarget inspectPlant(CompoundTag blockStateNbt, BlockPos pos, Rotation rotation) {
            return null;
        }

        default boolean placePlant(CompoundTag blockStateNbt, BlockPos pos, Rotation rotation) {
            return false;
        }
    }

    public record ExistingTarget(boolean replaceable, boolean surfaceReplaceable) {
    }

    public record PlantTarget(boolean replaceable, boolean alreadySatisfied, boolean supportSatisfied) {
    }

    public record PlacementResult(boolean applied, String reasonCode, int baseY, int targetCount) {
        static PlacementResult applied(int baseY, int targetCount) {
            return new PlacementResult(true, "CITY_DECORATION_TEMPLATE_APPLIED", baseY, targetCount);
        }

        static PlacementResult failed(String reasonCode, int baseY, int targetCount) {
            return new PlacementResult(false, reasonCode, baseY, targetCount);
        }
    }

    public record PreflightResult(boolean ready,
                                  String reasonCode,
                                  int baseY,
                                  int targetCount,
                                  BlockPos origin,
                                  Rotation rotation) {
        static PreflightResult ready(int baseY, int targetCount, BlockPos origin, Rotation rotation) {
            return new PreflightResult(true, "CITY_DECORATION_TARGET_READY", baseY, targetCount, origin, rotation);
        }

        static PreflightResult failed(String reasonCode, int baseY, int targetCount,
                                      BlockPos origin, Rotation rotation) {
            return new PreflightResult(false, reasonCode, baseY, targetCount, origin, rotation);
        }
    }

    record PlacementTarget(int localY, BlockPos worldPos, boolean templateAir) {
    }

    public static final class WorldGenPlacementWorld implements PlacementWorld {
        private final WorldGenLevel level;

        public WorldGenPlacementWorld(WorldGenLevel level) {
            this.level = Objects.requireNonNull(level, "level");
        }

        @Override
        public boolean ensureCanWrite(BlockPos pos) {
            return level.ensureCanWrite(pos);
        }

        @Override
        public ExistingTarget inspect(BlockPos pos) {
            BlockState state = level.getBlockState(pos);
            boolean replaceable = state.isAir() || state.canBeReplaced();
            boolean surfaceReplaceable = state.is(BlockTags.DIRT)
                    || state.is(BlockTags.SAND)
                    || state.is(BlockTags.BASE_STONE_OVERWORLD)
                    || state.is(BlockTags.BASE_STONE_NETHER)
                    || state.is(Blocks.GRAVEL)
                    || state.is(Blocks.CLAY)
                    || state.is(Blocks.SNOW_BLOCK);
            return new ExistingTarget(replaceable, surfaceReplaceable);
        }

        @Override
        public boolean placeTemplate(CompoundTag templateNbt, BlockPos origin, Rotation rotation, long seed,
                                     boolean ignoreTemplateAir) {
            HolderGetter<Block> blocks = level.registryAccess().lookupOrThrow(Registries.BLOCK);
            StructureTemplate template = new StructureTemplate();
            template.load(blocks, templateNbt.copy());
            StructurePlaceSettings settings = new StructurePlaceSettings()
                    .setRotation(rotation)
                    .setMirror(Mirror.NONE)
                    .setRotationPivot(BlockPos.ZERO)
                    .setIgnoreEntities(true)
                    .setKeepLiquids(false);
            if (ignoreTemplateAir) {
                settings.addProcessor(BlockIgnoreProcessor.AIR);
            }
            return template.placeInWorld(level, origin, origin, settings, RandomSource.create(seed), 2);
        }

        @Override
        public PlantTarget inspectPlant(CompoundTag blockStateNbt, BlockPos pos, Rotation rotation) {
            BlockState target = plantState(blockStateNbt, rotation);
            if (!(target.getBlock() instanceof CropBlock crop)) {
                throw new IllegalArgumentException("CITY_DECORATION_PLANT_BLOCK_STATE_NOT_CROP");
            }
            BlockState existing = level.getBlockState(pos);
            BlockPos below = pos.below();
            BlockState support = level.getBlockState(below);
            boolean supportSatisfied = support.canSustainPlant(level, below, Direction.UP, crop);
            return new PlantTarget(existing.isAir() || existing.canBeReplaced(), existing.equals(target),
                    supportSatisfied);
        }

        @Override
        public boolean placePlant(CompoundTag blockStateNbt, BlockPos pos, Rotation rotation) {
            BlockState target = plantState(blockStateNbt, rotation);
            if (level.getBlockState(pos).equals(target)) {
                return true;
            }
            return level.setBlock(pos, target, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }

        private BlockState plantState(CompoundTag blockStateNbt, Rotation rotation) {
            HolderGetter<Block> blocks = level.registryAccess().lookupOrThrow(Registries.BLOCK);
            BlockState state = NbtUtils.readBlockState(blocks, blockStateNbt);
            if (!(state.getBlock() instanceof CropBlock)) {
                throw new IllegalArgumentException("CITY_DECORATION_PLANT_BLOCK_STATE_NOT_CROP");
            }
            return state.mirror(Mirror.NONE).rotate(rotation);
        }
    }
}
