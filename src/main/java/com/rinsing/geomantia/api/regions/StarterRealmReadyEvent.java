package com.rinsing.geomantia.api.regions;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;

/** Posted after a player's first successful starter-continent arrival; optional maps may open now. */
public final class StarterRealmReadyEvent extends PlayerEvent {
    public StarterRealmReadyEvent(ServerPlayer player) { super(player); }
}
