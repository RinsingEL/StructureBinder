package com.rinsing.geomantia.platform;

import com.rinsing.geomantia.GeomantiaMod;
import com.rinsing.geomantia.systems.gis.adapter.minecraft.MinecraftChunkGenerationBenchmarkService;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = GeomantiaMod.MOD_ID)
public final class ChunkGenerationBenchmarkEvents {
    private ChunkGenerationBenchmarkEvents() {
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) {
            MinecraftChunkGenerationBenchmarkService.forServer(level.getServer())
                    .onChunkLoad(level, event.getChunk().getPos(), event.isNewChunk());
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            MinecraftChunkGenerationBenchmarkService.forServer(event.getServer()).tick();
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        MinecraftChunkGenerationBenchmarkService.closeServer(event.getServer());
    }
}
