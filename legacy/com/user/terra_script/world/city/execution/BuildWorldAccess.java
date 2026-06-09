package com.user.terra_script.world.city.execution;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

public interface BuildWorldAccess {
    ServerLevel level();

    MinecraftServer server();

    boolean isServerRunning();

    boolean hasChunk(int chunkX, int chunkZ);

    boolean isChunkFull(int chunkX, int chunkZ);

    BuildBlockSnapshot blockSnapshot(BlockPos pos);

    boolean clearBlock(BlockPos pos);

    BlockState getBlockState(BlockPos pos);

    boolean setBlock(BlockPos pos, BlockState state, int flags);
}
