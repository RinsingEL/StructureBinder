package com.user.terra_script.world.city.execution;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;

public final class ServerLevelBuildWorldAccess implements BuildWorldAccess {
    private final ServerLevel level;

    public ServerLevelBuildWorldAccess(ServerLevel level) {
        this.level = level;
    }

    @Override
    public ServerLevel level() {
        return level;
    }

    @Override
    public MinecraftServer server() {
        return level != null ? level.getServer() : null;
    }

    @Override
    public boolean isServerRunning() {
        MinecraftServer server = server();
        return server != null && server.isRunning();
    }

    @Override
    public boolean hasChunk(int chunkX, int chunkZ) {
        return level != null && level.hasChunk(chunkX, chunkZ);
    }

    @Override
    public boolean isChunkFull(int chunkX, int chunkZ) {
        return level != null && level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) != null;
    }

    @Override
    public BuildBlockSnapshot blockSnapshot(BlockPos pos) {
        BlockState state = getBlockState(pos);
        if (state == null || state.isAir()) return BuildBlockSnapshot.airBlock();
        return BuildBlockSnapshot.of(
                blockId(state),
                state.isAir(),
                isProtected(state),
                softClearReason(state)
        );
    }

    @Override
    public boolean clearBlock(BlockPos pos) {
        return level != null && pos != null && level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return level != null ? level.getBlockState(pos) : null;
    }

    @Override
    public boolean setBlock(BlockPos pos, BlockState state, int flags) {
        return level != null && pos != null && state != null && level.setBlock(pos, state, flags);
    }

    private static String softClearReason(BlockState state) {
        if (state == null || isProtected(state)) return null;
        if (isLogLike(state)) return "trunk";
        if (state.getBlock() instanceof LeavesBlock || state.getBlock() instanceof VineBlock
                || state.getBlock() instanceof BushBlock || state.getBlock() instanceof DoublePlantBlock
                || state.getBlock() instanceof BambooStalkBlock || state.getBlock() instanceof SugarCaneBlock) {
            return "foliage";
        }
        return state.canBeReplaced() ? "replaceable" : null;
    }

    private static boolean isProtected(BlockState state) {
        return state != null && (state.is(Blocks.BEDROCK)
                || state.is(Blocks.BARRIER)
                || state.is(Blocks.COMMAND_BLOCK)
                || state.is(Blocks.CHAIN_COMMAND_BLOCK)
                || state.is(Blocks.REPEATING_COMMAND_BLOCK)
                || state.is(Blocks.STRUCTURE_BLOCK)
                || state.is(Blocks.END_PORTAL_FRAME));
    }

    private static boolean isLogLike(BlockState state) {
        if (state == null) return false;
        if (state.is(BlockTags.LOGS)) return true;
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (key == null) return false;
        String path = key.getPath();
        return path.endsWith("_log")
                || path.endsWith("_wood")
                || path.endsWith("_stem")
                || path.endsWith("_hyphae");
    }

    private static String blockId(BlockState state) {
        if (state == null) return "minecraft:air";
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return key != null ? key.toString() : "minecraft:unknown";
    }
}
