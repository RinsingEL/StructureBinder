package com.user.terra_script.event;

import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.concurrent.atomic.AtomicLong;

@Mod.EventBusSubscriber(modid = "terra_script")
public class ServerTickTracker {
    private static final AtomicLong TICK_COUNTER = new AtomicLong(0L);

    private ServerTickTracker() {}

    public static long currentTick() {
        return TICK_COUNTER.get();
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            TICK_COUNTER.incrementAndGet();
        }
    }
}
