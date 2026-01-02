package com.user.terra_script.world;

import com.google.common.collect.ImmutableList;
import com.mojang.datafixers.util.Pair;
import com.user.terra_script.config.StructurePlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.Pools;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import net.minecraft.world.level.levelgen.structure.pools.*;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

@SuppressWarnings("removal")
@Mod.EventBusSubscriber(modid = "terra_script")
public class StructureInjector {

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        ChunkPos pos = event.getChunk().getPos();
        String structId = StructurePlan.get().getStructureAt(pos.x, pos.z);

        if (structId != null) {
            spawnStructure(level, pos, structId);
            StructurePlan.get().removeStructure(pos.x, pos.z);
        }
    }

    public static void spawnStructure(ServerLevel level, ChunkPos chunkPos, String structureId) {
        int x = chunkPos.getMinBlockX() + 8;
        int z = chunkPos.getMinBlockZ() + 8;
        int y = level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z);
        BlockPos centerPos = new BlockPos(x, y, z);

        level.getServer().execute(() -> {
            try {
                System.out.println("[TerraScript] Starting generation async for " + structureId);
                placeJigsawStructure(level, centerPos, new ResourceLocation(structureId));
                System.out.println("[TerraScript] Finished generation for " + structureId);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private static void placeJigsawStructure(ServerLevel level, BlockPos startPos, ResourceLocation nbtLocation) {
        Registry<StructureTemplatePool> poolRegistry = level.registryAccess().registryOrThrow(Registries.TEMPLATE_POOL);
        StructureTemplateManager templateManager = level.getStructureManager();
        ChunkGenerator chunkGenerator = level.getChunkSource().getGenerator();
        RandomState randomState = level.getChunkSource().randomState();

        // 1. 获取 Empty Pool 作为 Fallback
        Holder<StructureTemplatePool> emptyPool = poolRegistry.getHolderOrThrow(Pools.EMPTY);

        // 2. 【修复】构建起始元素工厂 (Function)
        // 注意：这里不要调用 .apply()，直接使用 SinglePoolElement.single(...) 返回的 Function
        Function<StructureTemplatePool.Projection, ? extends StructurePoolElement> startElementFactory =
                SinglePoolElement.single(nbtLocation.toString());

        // 3. 【修复】构建起始池
        // 泛型会自动匹配 Function<Projection, Element>
        Holder<StructureTemplatePool> startPool = Holder.direct(new StructureTemplatePool(
                emptyPool,
                ImmutableList.of(Pair.of(startElementFactory, 1)),
                StructureTemplatePool.Projection.RIGID
        ));

        // 4. 计算拼图布局
        int maxDepth = 0;

        Optional<Structure.GenerationStub> stubOptional = JigsawPlacement.addPieces(
                new Structure.GenerationContext(
                        level.registryAccess(),
                        chunkGenerator,
                        chunkGenerator.getBiomeSource(),
                        randomState,
                        templateManager,
                        level.getSeed(),
                        new ChunkPos(startPos),
                        level,
                        registryEntry -> true
                ),
                startPool,
                Optional.empty(),
                maxDepth,
                startPos,
                false,
                Optional.empty(),
                128
        );

        if (stubOptional.isEmpty()) {
            System.err.println("[TerraScript] Jigsaw calculation failed for " + nbtLocation);
            return;
        }

        Structure.GenerationStub stub = stubOptional.get();

        // 5. 【修复】提取组件 (处理 Either 类型)
        StructurePiecesBuilder piecesBuilder = new StructurePiecesBuilder();

        // stub.generator() 返回 Either<Consumer<Builder>, Builder>
        stub.generator().ifLeft(consumer -> {
            // 情况 A: 这是一个消费者，我们把 builder 传给它去填充
            consumer.accept(piecesBuilder);
        }).ifRight(existingBuilder -> {
            // 情况 B: 这已经是一个填充好的 builder，我们把里面的东西拿出来加到我们的 builder 里
            // build() 生成 StructurePieces, pieces() 获取 List<StructurePiece>
            existingBuilder.build().pieces().forEach(piecesBuilder::addPiece);
        });

        List<StructurePiece> pieces = piecesBuilder.build().pieces();

        System.out.println("[TerraScript] Placing " + pieces.size() + " pieces...");

        BoundingBox worldBox = new BoundingBox(
                startPos.getX() - 500, -64, startPos.getZ() - 500,
                startPos.getX() + 500, 320, startPos.getZ() + 500
        );

        for (StructurePiece piece : pieces) {
            if (piece instanceof PoolElementStructurePiece poolPiece) {
                poolPiece.postProcess(
                        level,
                        level.structureManager(),
                        chunkGenerator,
                        level.getRandom(),
                        worldBox,
                        new ChunkPos(startPos),
                        startPos
                );
            }
        }
    }
}