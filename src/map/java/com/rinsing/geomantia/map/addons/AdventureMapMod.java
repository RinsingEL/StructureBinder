package com.rinsing.geomantia.map.addons;
import com.rinsing.geomantia.systems.provider.application.*;

import com.rinsing.geomantia.map.platform.network.AdventurerMapNetwork;
import com.rinsing.geomantia.map.platform.registry.GeomantiaItems;
import com.rinsing.geomantia.map.systems.realm_planning.adapter.minecraft.AdventurerMapStarterGrant;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

@Mod("geomantia_map")
public final class AdventureMapMod {
    public AdventureMapMod(FMLJavaModLoadingContext context) {
        GeomantiaItems.register(context.getModEventBus());
        context.getModEventBus().addListener(this::setup);
        MinecraftForge.EVENT_BUS.register(new AdventurerMapStarterGrant());
    }
    private void setup(FMLCommonSetupEvent event) { event.enqueueWork(AdventurerMapNetwork::register); }
}
