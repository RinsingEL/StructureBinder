package com.bind.structure_bind.world.gen;

import com.bind.structure_bind.structures.Door2Structure;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.event.level.LevelEvent; // 修正导入
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "structure_bind", bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RoadGenerator {

    private static void generateRoad(ServerLevel level, BlockPos start, BlockPos end) {
        int y = Math.min(start.getY(), end.getY());
        for (int x = Math.min(start.getX(), end.getX()); x <= Math.max(start.getX(), end.getX()); x++) {
            for (int z = Math.min(start.getZ(), end.getZ()); z <= Math.max(start.getZ(), end.getZ()); z++) {
                level.setBlock(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState(), 3);
            }
        }
    }
}