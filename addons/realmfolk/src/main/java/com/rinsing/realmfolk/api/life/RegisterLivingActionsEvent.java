package com.rinsing.realmfolk.api.life;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.eventbus.api.Event;
import java.util.Objects;
import java.util.function.BiConsumer;

/** Posted on MinecraftForge.EVENT_BUS at server start and explicit behavior reload. Do not retain it. */
public final class RegisterLivingActionsEvent extends Event {
    private final MinecraftServer server;
    private final BiConsumer<String,LivingAction> register;
    public RegisterLivingActionsEvent(MinecraftServer server,BiConsumer<String,LivingAction> register) {
        this.server=server;this.register=register;
    }
    public MinecraftServer server() { return server; }
    public void register(ResourceLocation id,LivingAction action) {
        register.accept(Objects.requireNonNull(id).toString(),Objects.requireNonNull(action));
    }
}
