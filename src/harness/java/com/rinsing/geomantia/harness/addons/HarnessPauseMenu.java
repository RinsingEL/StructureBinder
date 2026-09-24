package com.rinsing.geomantia.harness.addons;
import com.rinsing.geomantia.systems.provider.application.*;
import com.rinsing.geomantia.harness.systems.provider.application.*;

import com.rinsing.geomantia.harness.client.ProviderSettingsClient;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid="geomantia_harness",value=Dist.CLIENT)
public final class HarnessPauseMenu {
    @SubscribeEvent public static void init(ScreenEvent.Init.Post event) {
        if(event.getScreen() instanceof PauseScreen screen)
            event.addListener(Button.builder(Component.literal("城市规划助手"), button -> ProviderSettingsClient.open(screen))
                    .bounds(8,8,120,20).build());
    }
}
