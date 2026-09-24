package com.rinsing.geomantia.api.planning;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.eventbus.api.Event;
import java.util.function.Consumer;

/** Forge EVENT_BUS, once per server planning-service startup. Registration closes when dispatch returns. */
public final class RegisterPlanningExtensionsEvent extends Event {
    private final MinecraftServer server;
    private final Consumer<PlanningExtension> registrar;
    public RegisterPlanningExtensionsEvent(MinecraftServer server, Consumer<PlanningExtension> registrar) {
        this.server = server; this.registrar = registrar;
    }
    public MinecraftServer server() { return server; }
    public void register(PlanningExtension extension) { registrar.accept(extension); }
}
