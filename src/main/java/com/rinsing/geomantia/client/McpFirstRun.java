package com.rinsing.geomantia.client;

import com.rinsing.geomantia.platform.mcp.McpServerService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

/** Ordinary Harness users do not need to configure an external Agent connection. */
@Mod.EventBusSubscriber(modid="geomantia", value=Dist.CLIENT)
public final class McpFirstRun {
    private static boolean shown;
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if(event.phase!=TickEvent.Phase.END || shown || ModList.get().isLoaded("geomantia_harness")) return;
        var minecraft=Minecraft.getInstance();
        if(minecraft.screen instanceof TitleScreen && McpServerService.instance().needsSetup()) {
            shown=true; minecraft.setScreen(new McpSettingsScreen(minecraft.screen));
        }
    }
}
