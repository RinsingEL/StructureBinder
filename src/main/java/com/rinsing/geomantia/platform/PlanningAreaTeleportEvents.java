package com.rinsing.geomantia.platform;

import com.mojang.logging.LogUtils;
import com.rinsing.geomantia.GeomantiaMod;
import com.rinsing.geomantia.systems.realm_planning.application.access.PlanningAreaAccessConfig;
import com.rinsing.geomantia.systems.realm_planning.application.access.PlanningAreaAccessPolicy;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.EntityTeleportEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;

@Mod.EventBusSubscriber(modid = GeomantiaMod.MOD_ID)
public final class PlanningAreaTeleportEvents {
    private static final Logger LOGGER = LogUtils.getLogger();

    private PlanningAreaTeleportEvents() {
    }

    @SubscribeEvent
    public static void onTeleportCommand(EntityTeleportEvent.TeleportCommand event) {
        evaluate(event);
    }

    @SubscribeEvent
    public static void onSpreadPlayersCommand(EntityTeleportEvent.SpreadPlayersCommand event) {
        evaluate(event);
    }

    private static void evaluate(EntityTeleportEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Path configPath = FMLPaths.CONFIGDIR.get().resolve("geomantia").resolve("planning_area_access.json");
        try {
            PlanningAreaAccessConfig config = PlanningAreaAccessConfig.loadOrCreate(configPath);
            Path debugRoot = player.getServer().getServerDirectory().toPath().resolve("realm_debug");
            PlanningAreaAccessPolicy.Decision decision = new PlanningAreaAccessPolicy(debugRoot, config).evaluate(
                    player.serverLevel().dimension().location().toString(), event.getTargetX(), event.getTargetZ());
            if (decision.allowed()) return;
            event.setCanceled(true);
            player.sendSystemMessage(Component.literal(
                    "[Geomantia] 目标区域尚未完成规划，无法传送。请留在初始活动区或前往已开放城市。")
                    .withStyle(ChatFormatting.RED));
        } catch (IOException | RuntimeException ex) {
            event.setCanceled(true);
            player.sendSystemMessage(Component.literal(
                    "[Geomantia] 无法读取规划区域状态，本次传送已安全取消。")
                    .withStyle(ChatFormatting.RED));
            LOGGER.error("Could not evaluate planning-area teleport gate", ex);
        }
    }
}
