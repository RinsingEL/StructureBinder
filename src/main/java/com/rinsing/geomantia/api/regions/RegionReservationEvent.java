package com.rinsing.geomantia.api.regions;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.eventbus.api.Event;
import java.util.function.Consumer;

/**
 * Subscribe on MinecraftForge.EVENT_BUS. Synchronous, server-thread-only, once per new planning run.
 * Register all masks during this event. A failed listener/validation aborts T1 without publishing a plan.
 * Existing plans are restored without replaying the event; registration is not a gameplay operation.
 */
public final class RegionReservationEvent extends Event {
    private final MinecraftServer server;
    private final RegionPlanningContext context;
    private final Consumer<ReservedRegion> registrar;

    public RegionReservationEvent(MinecraftServer server, RegionPlanningContext context,
                                  Consumer<ReservedRegion> registrar) {
        this.server = server;
        this.context = context;
        this.registrar = registrar;
    }

    public MinecraftServer server() { return server; }
    public RegionPlanningContext context() { return context; }
    public void reserve(ReservedRegion region) { registrar.accept(region); }
}
