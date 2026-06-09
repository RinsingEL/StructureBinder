package com.rinsing.geomantia;

import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

@Mod(GeomantiaMod.MOD_ID)
public final class GeomantiaMod {
    public static final String MOD_ID = "geomantia";
    private static final Logger LOGGER = LogUtils.getLogger();

    public GeomantiaMod(FMLJavaModLoadingContext context) {
        IEventBus modEventBus = context.getModEventBus();
        modEventBus.addListener(this::onCommonSetup);
        MinecraftForge.EVENT_BUS.register(this);
        registerTemporaryClientDevHooks();
    }

    private void onCommonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("Geomantia initialized.");
    }

    private void registerTemporaryClientDevHooks() {
        if (FMLEnvironment.dist != Dist.CLIENT || FMLEnvironment.production) {
            return;
        }
        try {
            Class<?> clientSetupClass = Class.forName("com.rinsing.geomantia.client.DevAutoLoadClient");
            clientSetupClass.getMethod("register").invoke(null);
        } catch (ReflectiveOperationException ex) {
            LOGGER.error("Failed to register temporary client dev hooks.", ex);
        }
    }
}
