package com.user.terra_script.world;

import com.user.terra_script.config.StructurePlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Optional;
import java.util.Random;

@SuppressWarnings("removal")
@Mod.EventBusSubscriber(modid = "terra_script")
public class StructureInjector {

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
        Rotation rotation = Rotation.values()[level.random.nextInt(Rotation.values().length)];

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

        // 6. 配置放置参数
        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setRotation(rotation)
                .setMirror(Mirror.NONE)
                .setIgnoreEntities(false); // 是否忽略结构里自带的实体(如村民)

        System.out.println("[TerraScript] Placing DIRECTLY: " + structureId + " at " + placePos + " (" + rotation + ")");

        try {
            // 7. 强行放置 (这是最关键的一步)
            // 参数2: 坐标, 参数3: 坐标(用于完整性检查), 参数4: 设置, 参数5: 随机源, 参数6: 更新标志(2=通知客户端)
            template.placeInWorld(level, placePos, placePos, settings, level.random, 2);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}