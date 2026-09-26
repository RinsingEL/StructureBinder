package com.rinsing.geomantia.harness.addons;
import com.rinsing.geomantia.systems.provider.application.*;
import com.rinsing.geomantia.harness.systems.provider.application.*;

import com.rinsing.geomantia.harness.platform.network.ProviderNetwork;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

@Mod("geomantia_harness")
public final class HarnessMod {
    public HarnessMod(FMLJavaModLoadingContext context) {
        context.getModEventBus().addListener(this::setup);
        MinecraftForge.EVENT_BUS.addListener(this::started);
        MinecraftForge.EVENT_BUS.addListener(this::stopping);
    }
    private void setup(FMLCommonSetupEvent event) { event.enqueueWork(ProviderNetwork::register); }
    private void started(PlanningHost.Started event) {
        for(var role:PlanningRole.values()) PlayerProviderService.instance(role).startAutomation(event.serverDirectory, event.debugRoot, event.port, event.seed);
    }
    private void stopping(PlanningHost.Stopping event) { for(var role:PlanningRole.values()) PlayerProviderService.instance(role).stopAutomation(); }
}
