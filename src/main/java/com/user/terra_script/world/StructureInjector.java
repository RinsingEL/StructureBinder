package com.user.terra_script.world;

import com.user.terra_script.config.StructurePlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@SuppressWarnings("removal")
@Mod.EventBusSubscriber(modid = "terra_script")
public class StructureInjector {
    public static final class PlacementBounds {
        public final int minX;
        public final int minY;
        public final int minZ;
        public final int maxXExclusive;
        public final int maxYExclusive;
        public final int maxZExclusive;

        private PlacementBounds(int minX, int minY, int minZ, int maxXExclusive, int maxYExclusive, int maxZExclusive) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxXExclusive = maxXExclusive;
            this.maxYExclusive = maxYExclusive;
            this.maxZExclusive = maxZExclusive;
        }

        public static PlacementBounds of(int minX, int minY, int minZ, int maxXExclusive, int maxYExclusive, int maxZExclusive) {
            return new PlacementBounds(minX, minY, minZ, maxXExclusive, maxYExclusive, maxZExclusive);
        }

        public boolean intersects(PlacementBounds other) {
            if (other == null) return false;
            boolean separated = this.maxXExclusive <= other.minX
                    || this.minX >= other.maxXExclusive
                    || this.maxYExclusive <= other.minY
                    || this.minY >= other.maxYExclusive
                    || this.maxZExclusive <= other.minZ
                    || this.minZ >= other.maxZExclusive;
            return !separated;
        }
    }

    public static final class TemplateSnapshot {
        public final String structureId;
        public final BlockPos origin;
        public final Rotation rotation;
        public final List<BlockEntry> entries;

        private TemplateSnapshot(String structureId, BlockPos origin, Rotation rotation, List<BlockEntry> entries) {
            this.structureId = structureId;
            this.origin = origin;
            this.rotation = rotation;
            this.entries = entries;
        }
    }

    public static final class PlacementOutcome {
        public final boolean placed;
        public final PlacementBounds bounds;
        public final int clearedJigsawBlocks;
        public final List<BlockPos> clearedJigsawSamples;

        private PlacementOutcome(boolean placed, PlacementBounds bounds, int clearedJigsawBlocks, List<BlockPos> clearedJigsawSamples) {
            this.placed = placed;
            this.bounds = bounds;
            this.clearedJigsawBlocks = clearedJigsawBlocks;
            this.clearedJigsawSamples = clearedJigsawSamples;
        }

        public static PlacementOutcome of(boolean placed, PlacementBounds bounds, int clearedJigsawBlocks, List<BlockPos> clearedJigsawSamples) {
            return new PlacementOutcome(placed, bounds, clearedJigsawBlocks, clearedJigsawSamples != null ? clearedJigsawSamples : Collections.emptyList());
        }

        public static PlacementOutcome failed(PlacementBounds bounds) {
            return new PlacementOutcome(false, bounds, 0, Collections.emptyList());
        }
    }

    private static final class BlockClearSummary {
        public final int count;
        public final List<BlockPos> samples;

        private BlockClearSummary(int count, List<BlockPos> samples) {
            this.count = count;
            this.samples = samples;
        }
    }

    public static final class BlockEntry {
        public final BlockPos pos;
        public final BlockState state;

        private BlockEntry(BlockPos pos, BlockState state) {
            this.pos = pos;
            this.state = state;
        }
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        // 安全检查：如果服务器正在关闭，不要生成，防止死锁
        if (!level.getServer().isRunning()) return;

        ChunkPos pos = event.getChunk().getPos();
        String structId = StructurePlan.get().getStructureAt(pos.x, pos.z);

        if (structId != null) {
            // 提交给主线程执行
            level.getServer().execute(() -> {
                if (level.getServer().isRunning()) {
                    spawnStructure(level, pos, structId);
                    StructurePlan.get().removeStructure(pos.x, pos.z);
                }
            });
        }
    }

    public static void spawnStructure(ServerLevel level, ChunkPos chunkPos, String structureId) {
        spawnStructure(level, chunkPos, structureId, null);
    }

    public static void spawnStructure(ServerLevel level, ChunkPos chunkPos, String structureId, Rotation forcedRotation) {
        StructureTemplateManager manager = level.getStructureManager();
        ResourceLocation loc = new ResourceLocation(structureId);
        Optional<StructureTemplate> templateOp = manager.get(loc);

        if (templateOp.isEmpty()) {
            System.err.println("[TerraScript] Structure not found: " + structureId);
            return;
        }

        StructureTemplate template = templateOp.get();
        Vec3i size = template.getSize();

        // 1. 确定放置中心点 (区块中心)
        int centerX = chunkPos.getMinBlockX() + 8;
        int centerZ = chunkPos.getMinBlockZ() + 8;

        // 2. 获取地面高度
        // OCEAN_FLOOR_WG: 获取固体方块高度 (忽略树木、水)
        // WORLD_SURFACE: 获取最高点 (包含树叶)
        int surfaceY = level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, centerX, centerZ);

        // 3. 设置随机旋转 (让村庄更自然)
        Rotation rotation = forcedRotation != null ? forcedRotation : Rotation.values()[level.random.nextInt(Rotation.values().length)];

        // 4. 计算偏移量以实现“中心对齐”
        // 旋转后的尺寸变化
        int rotatedWidth = (rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90) ? size.getZ() : size.getX();
        int rotatedDepth = (rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90) ? size.getX() : size.getZ();

        // 起始点 = 中心点 - (旋转后尺寸 / 2)
        int originX = centerX - (rotatedWidth / 2);
        int originZ = centerZ - (rotatedDepth / 2);

        // 5. Y轴微调
        // 大多数原版结构是以地基为 0 层的，但也有些是有地下室的
        // 这里做一个简单的处理：如果是普通房屋，向下嵌入 1 格，防止浮空
        int originY = surfaceY - 1;

        BlockPos placePos = new BlockPos(originX, originY, originZ);
        Bounds bounds = boundsFor(template, placePos, rotation);

        // 6. 配置放置参数
        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setRotation(rotation)
                .setMirror(Mirror.NONE)
                .setIgnoreEntities(false); // 是否忽略结构里自带的实体(如村民)

        System.out.println("[TerraScript] Placing DIRECTLY: " + structureId + " at " + placePos + " (" + rotation + ")"
                + " bounds=(" + bounds.minX + "," + bounds.minY + "," + bounds.minZ + ")->("
                + (bounds.maxXExclusive - 1) + "," + (bounds.maxYExclusive - 1) + "," + (bounds.maxZExclusive - 1) + ")");

        try {
            // 7. 强行放置 (这是最关键的一步)
            // 参数2: 坐标, 参数3: 坐标(用于完整性检查), 参数4: 设置, 参数5: 随机源, 参数6: 更新标志(2=通知客户端)
            template.placeInWorld(level, placePos, placePos, settings, level.random, 2);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static boolean spawnStructureAtBlock(ServerLevel level, String structureId, BlockPos origin, Rotation rotation, boolean clearJigsawBlocks) {
        return placeStructureDetailed(level, structureId, origin, rotation, clearJigsawBlocks).placed;
    }

    public static PlacementOutcome placeStructureDetailed(ServerLevel level, String structureId, BlockPos origin, Rotation rotation, boolean clearJigsawBlocks) {
        if (level == null || structureId == null || structureId.isBlank() || origin == null) return PlacementOutcome.failed(null);
        StructureTemplateManager manager = level.getStructureManager();
        ResourceLocation loc = new ResourceLocation(structureId);
        Optional<StructureTemplate> templateOp = manager.get(loc);
        if (templateOp.isEmpty()) {
            System.err.println("[TerraScript] Structure not found: " + structureId + " origin=" + origin + " rotation=" + rotation);
            return PlacementOutcome.failed(null);
        }
        StructureTemplate template = templateOp.get();
        Vec3i size = template.getSize();
        Bounds bounds = boundsFor(template, origin, rotation != null ? rotation : Rotation.NONE);
        PlacementBounds placementBounds = new PlacementBounds(
                bounds.minX,
                bounds.minY,
                bounds.minZ,
                bounds.maxXExclusive,
                bounds.maxYExclusive,
                bounds.maxZExclusive
        );
        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setRotation(rotation != null ? rotation : Rotation.NONE)
                .setMirror(Mirror.NONE)
                .setIgnoreEntities(false);
        try {
            System.out.println("[TerraScript] spawnStructureAtBlock template=" + structureId
                    + " origin=" + origin
                    + " rotation=" + (rotation != null ? rotation : Rotation.NONE)
                    + " size=(" + size.getX() + "," + size.getY() + "," + size.getZ() + ")"
                    + " bounds=(" + bounds.minX + "," + bounds.minY + "," + bounds.minZ + ")->("
                    + (bounds.maxXExclusive - 1) + "," + (bounds.maxYExclusive - 1) + "," + (bounds.maxZExclusive - 1) + ")"
                    + " clear_jigsaw=" + clearJigsawBlocks);
            boolean placed = template.placeInWorld(level, origin, origin, settings, level.random, 2);
            BlockClearSummary clearSummary = placed && clearJigsawBlocks
                    ? clearPlacedJigsawBlocks(level, template, origin, rotation != null ? rotation : Rotation.NONE)
                    : new BlockClearSummary(0, Collections.emptyList());
            System.out.println("[TerraScript] spawnStructureAtBlock result template=" + structureId
                    + " origin=" + origin
                    + " bounds=(" + bounds.minX + "," + bounds.minY + "," + bounds.minZ + ")->("
                    + (bounds.maxXExclusive - 1) + "," + (bounds.maxYExclusive - 1) + "," + (bounds.maxZExclusive - 1) + ")"
                    + " placed=" + placed);
            return new PlacementOutcome(placed, placementBounds, clearSummary.count, clearSummary.samples);
        } catch (Exception e) {
            System.err.println("[TerraScript] spawnStructureAtBlock exception template=" + structureId
                    + " origin=" + origin
                    + " rotation=" + (rotation != null ? rotation : Rotation.NONE));
            e.printStackTrace();
            return PlacementOutcome.failed(placementBounds);
        }
    }

    public static TemplateSnapshot captureTemplateSnapshot(ServerLevel level, String structureId, BlockPos origin, Rotation rotation) {
        StructureTemplate template = loadTemplate(level, structureId);
        if (level == null || template == null || origin == null) return null;
        Bounds bounds = boundsFor(template, origin, rotation != null ? rotation : Rotation.NONE);
        List<BlockEntry> entries = new ArrayList<>(Math.max(1, bounds.width * bounds.depth * bounds.height));
        for (int x = bounds.minX; x < bounds.maxXExclusive; x++) {
            for (int y = bounds.minY; y < bounds.maxYExclusive; y++) {
                for (int z = bounds.minZ; z < bounds.maxZExclusive; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    entries.add(new BlockEntry(pos, level.getBlockState(pos)));
                }
            }
        }
        return new TemplateSnapshot(structureId, origin, rotation != null ? rotation : Rotation.NONE, entries);
    }

    public static boolean restoreTemplateSnapshot(ServerLevel level, TemplateSnapshot snapshot) {
        if (level == null || snapshot == null || snapshot.entries == null) return false;
        for (BlockEntry entry : snapshot.entries) {
            if (entry == null || entry.pos == null || entry.state == null) continue;
            level.setBlock(entry.pos, entry.state, Block.UPDATE_ALL);
        }
        return true;
    }

    public static Vec3i templateSize(ServerLevel level, String structureId) {
        StructureTemplate template = loadTemplate(level, structureId);
        return template != null ? template.getSize() : null;
    }

    public static PlacementBounds placementBounds(ServerLevel level, String structureId, BlockPos origin, Rotation rotation) {
        StructureTemplate template = loadTemplate(level, structureId);
        if (template == null || origin == null) return null;
        Bounds bounds = boundsFor(template, origin, rotation != null ? rotation : Rotation.NONE);
        return new PlacementBounds(
                bounds.minX,
                bounds.minY,
                bounds.minZ,
                bounds.maxXExclusive,
                bounds.maxYExclusive,
                bounds.maxZExclusive
        );
    }

    private static BlockClearSummary clearPlacedJigsawBlocks(ServerLevel level, StructureTemplate template, BlockPos origin, Rotation rotation) {
        if (level == null || template == null || origin == null) return new BlockClearSummary(0, Collections.emptyList());
        Bounds bounds = boundsFor(template, origin, rotation);
        int count = 0;
        List<BlockPos> samples = new ArrayList<>();
        for (int x = bounds.minX; x < bounds.maxXExclusive; x++) {
            for (int y = bounds.minY; y < bounds.maxYExclusive; y++) {
                for (int z = bounds.minZ; z < bounds.maxZExclusive; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.JIGSAW)) {
                        level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
                        count++;
                        if (samples.size() < 8) {
                            samples.add(pos.immutable());
                        }
                    }
                }
            }
        }
        return new BlockClearSummary(count, samples);
    }

    private static StructureTemplate loadTemplate(ServerLevel level, String structureId) {
        if (level == null || structureId == null || structureId.isBlank()) return null;
        StructureTemplateManager manager = level.getStructureManager();
        return manager.get(new ResourceLocation(structureId)).orElse(null);
    }

    private static Bounds boundsFor(StructureTemplate template, BlockPos origin, Rotation rotation) {
        Vec3i size = template.getSize();
        int width = (rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90) ? size.getZ() : size.getX();
        int depth = (rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90) ? size.getX() : size.getZ();
        int height = Math.max(1, size.getY());
        return new Bounds(
                origin.getX(),
                origin.getY(),
                origin.getZ(),
                origin.getX() + Math.max(1, width),
                origin.getY() + height,
                origin.getZ() + Math.max(1, depth),
                Math.max(1, width),
                height,
                Math.max(1, depth)
        );
    }

    private record Bounds(
            int minX,
            int minY,
            int minZ,
            int maxXExclusive,
            int maxYExclusive,
            int maxZExclusive,
            int width,
            int height,
            int depth
    ) {}
}
