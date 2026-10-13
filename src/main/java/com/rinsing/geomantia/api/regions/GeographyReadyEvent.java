package com.rinsing.geomantia.api.regions;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.eventbus.api.Event;

/** Server-thread barrier after sealed geography and starter identity, before starter activation continues. */
public final class GeographyReadyEvent extends Event {
    public final MinecraftServer server;
    public GeographyReadyEvent(MinecraftServer server) {this.server=server;}
}
