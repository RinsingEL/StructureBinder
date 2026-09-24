package com.rinsing.geomantia.harness.addons;
import com.rinsing.geomantia.systems.provider.application.*;
import com.rinsing.geomantia.harness.systems.provider.application.*;

import com.rinsing.geomantia.harness.client.ProviderSettingsScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid="geomantia_harness", value=Dist.CLIENT, bus=Mod.EventBusSubscriber.Bus.MOD)
public final class HarnessClient {
    @SubscribeEvent public static void setup(FMLClientSetupEvent event) {
        ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory((minecraft,parent) -> new ProviderSettingsScreen(parent)));
    }
}
