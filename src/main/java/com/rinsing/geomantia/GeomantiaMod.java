package com.rinsing.geomantia;

import com.mojang.logging.LogUtils;
import com.rinsing.geomantia.systems.city.infrastructure.world.CityReservationMaskRegistry;
import com.rinsing.geomantia.systems.city.infrastructure.world.CityTemplateTerrainStructureRegistries;
import com.rinsing.geomantia.systems.city.infrastructure.world.CityTemplateContentPackInstaller;
import com.rinsing.geomantia.systems.city.infrastructure.world.landuse.CityLandUseWorldgenRegistry;
import com.rinsing.geomantia.systems.city.infrastructure.landuse.LandUseDefaultConfigBootstrap;
import com.rinsing.geomantia.systems.provider.application.ManagedCityPlanningSources;
import com.rinsing.geomantia.systems.realm_planning.adapter.minecraft.AdventurerMapStarterGrant;
import com.rinsing.geomantia.platform.network.AdventurerMapNetwork;
import com.rinsing.geomantia.platform.network.ProviderNetwork;
import com.rinsing.geomantia.platform.registry.GeomantiaItems;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

@Mod(GeomantiaMod.MOD_ID)
public final class GeomantiaMod {
    public static final String MOD_ID = "geomantia";
    private static final Logger LOGGER = LogUtils.getLogger();

    public GeomantiaMod(FMLJavaModLoadingContext context) {
        IEventBus modEventBus = context.getModEventBus();
        GeomantiaItems.register(modEventBus);
        CityTemplateTerrainStructureRegistries.register(modEventBus);
        modEventBus.addListener(this::onCommonSetup);
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(new AdventurerMapStarterGrant());
        registerClientDevHooks();
    }

    private void onCommonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            AdventurerMapNetwork.register();
            ProviderNetwork.register();
        });
        LOGGER.info("Geomantia initialized.");
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void onServerAboutToStart(ServerAboutToStartEvent event) {
        java.nio.file.Path serverRoot = event.getServer().getWorldPath(LevelResource.ROOT);
        CityReservationMaskRegistry.load(serverRoot);
        CityLandUseWorldgenRegistry.load(serverRoot);
        java.nio.file.Path landUseRoot = FMLPaths.CONFIGDIR.get()
                .resolve("geomantia").resolve("city_land_use");
        try {
            LandUseDefaultConfigBootstrap.ensureInstalled(landUseRoot);
        } catch (java.io.IOException ex) {
            LOGGER.error("Failed to install default City LandUse settings at {}.", landUseRoot, ex);
        }
        try {
            java.nio.file.Path serverDirectory = event.getServer().getServerDirectory().toPath();
            ManagedCityPlanningSources.ResolvedSources sources =
                    new ManagedCityPlanningSources(serverDirectory).resolve();
            CityTemplateContentPackInstaller.InstallReport report =
                    new CityTemplateContentPackInstaller().install(sources.directory(), serverRoot);
            if (report.configured()) {
                LOGGER.info("Installed City template content pack {} into current world: templates={}, copied={}, "
                                + "unchanged={}, repaired={}.", report.packId(), report.templateCount(),
                        report.installedCount(), report.unchangedCount(), report.repairedCount());
            } else {
                LOGGER.warn("Managed City source {} has no {}; D4 template preflight will reject missing world "
                                + "templates.", sources.directory(), CityTemplateContentPackInstaller.MANIFEST_FILE);
            }
        } catch (java.io.IOException ex) {
            LOGGER.error("Failed to install the managed City template content pack into {}.", serverRoot, ex);
        }
    }

    private void registerClientDevHooks() {
        if (FMLEnvironment.dist != Dist.CLIENT || FMLEnvironment.production) {
            return;
        }
        try {
            Class<?> autoLoadClient = Class.forName("com.rinsing.geomantia.client.DevAutoLoadClient");
            autoLoadClient.getMethod("register").invoke(null);
        } catch (ReflectiveOperationException ex) {
            LOGGER.error("Failed to register client development hooks.", ex);
        }
    }

}
