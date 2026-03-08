package com.user.terra_script.world.city;

import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "terra_script")
public class RoadInjector {

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        // Legacy polygon-boundary road injection disabled.
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        // Legacy polygon-boundary road injection disabled.
    }

    public static void resetProcessing() {
        // No-op: legacy road injector disabled.
    }
}
