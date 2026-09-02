package com.rinsing.geomantia.platform;

import com.rinsing.geomantia.GeomantiaMod;
import com.rinsing.geomantia.systems.realm_planning.application.access.PlanningAreaAccessPolicy;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityTeleportEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = GeomantiaMod.MOD_ID)
public final class PlanningAreaAccessEvents {
    private PlanningAreaAccessEvents() {
    }

    /** The base event receives command, spreadplayers, ender pearl and chorus fruit child events. */
    @SubscribeEvent
    public static void onTeleport(EntityTeleportEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        PlanningAreaAccessPolicy.Decision decision = PlanningAreaAccessRuntime.evaluate(
                player, event.getTargetX(), event.getTargetZ());
        if (decision.allowed()) return;
        event.setCanceled(true);
        player.sendSystemMessage(Component.literal(
                "[Geomantia] 目标区域尚未开放，无法传送。请沿已开放路线前往已联通城市。")
                .withStyle(ChatFormatting.RED));
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        PlanningAreaAccessRuntime.handleMovement(player);
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) PlanningAreaAccessRuntime.handleMovement(player);
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) PlanningAreaAccessRuntime.forget(player);
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        PlanningAreaAccessRuntime.clear(event.getServer());
    }
}
