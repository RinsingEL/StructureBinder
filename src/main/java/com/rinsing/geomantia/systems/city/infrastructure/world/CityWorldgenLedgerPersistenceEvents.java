package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.rinsing.geomantia.GeomantiaMod;
import com.rinsing.geomantia.systems.city.infrastructure.world.landuse.CityLandUseWorldgenRegistry;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Keeps growing City worldgen ledgers out of chunk-generation worker hot paths. */
@Mod.EventBusSubscriber(modid = GeomantiaMod.MOD_ID)
public final class CityWorldgenLedgerPersistenceEvents {
    private CityWorldgenLedgerPersistenceEvents() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        CityLandUseWorldgenRegistry.tickD7Backfills(event.getServer());
        CityReservationMaskRegistry.flushPendingWorldgenLedgerIfDue();
        CityLandUseWorldgenRegistry.flushPendingLedgerIfDue();
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        CityLandUseWorldgenRegistry.clearD7Backfills(event.getServer());
        CityReservationMaskRegistry.flushPendingWorldgenLedgerNow();
        CityLandUseWorldgenRegistry.flushPendingLedgerNow();
    }
}
