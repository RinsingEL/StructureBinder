package com.rinsing.geomantia.harness.client;
import com.rinsing.geomantia.systems.provider.application.*;

import com.rinsing.geomantia.harness.platform.network.ProviderNetwork;
import com.rinsing.geomantia.harness.systems.provider.application.ProviderSettingsSnapshot;
import com.rinsing.geomantia.harness.systems.provider.application.AgentActivityEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;

public final class ProviderSettingsClient {
    private ProviderSettingsClient() {
    }

    public static void open(Screen parent) {
        Minecraft.getInstance().setScreen(new ProviderSettingsScreen(parent));
    }

    public static void request(PlanningRole role) {
        if (Minecraft.getInstance().getConnection() != null) ProviderNetwork.requestSettings(role);
        else receive(role,com.rinsing.geomantia.harness.systems.provider.application.PlayerProviderService.instance(role).snapshot(true));
    }
    public static void test(PlanningRole role) {
        if(Minecraft.getInstance().getConnection()!=null) ProviderNetwork.testConnection(role);
        else com.rinsing.geomantia.harness.systems.provider.application.PlayerProviderService.instance(role).test(true)
                .thenAccept(result->Minecraft.getInstance().execute(()->receive(role,result)));
    }
    public static void save(PlanningRole role,String providerKind,boolean enabled,String baseUrl,String model,String protocol,int timeout,String runtime,String key,boolean clearKey) {
        if(Minecraft.getInstance().getConnection()!=null) ProviderNetwork.saveSettings(role,providerKind,enabled,baseUrl,model,protocol,timeout,runtime,key,clearKey);
        else com.rinsing.geomantia.harness.systems.provider.application.PlayerProviderService.instance(role)
                .save(new com.rinsing.geomantia.harness.systems.provider.application.PlayerProviderConfig(providerKind,enabled,baseUrl,model,protocol,timeout,runtime),key,clearKey,true)
                .thenAccept(result->Minecraft.getInstance().execute(()->receive(role,result)));
    }

    public static void openActivity(Screen parent) {
        Minecraft.getInstance().setScreen(new AgentActivityScreen(parent));
        if (Minecraft.getInstance().getConnection() != null) ProviderNetwork.requestActivity();
    }

    public static void requestActivity() {
        if (Minecraft.getInstance().getConnection() != null) ProviderNetwork.requestActivity();
    }

    public static void receive(PlanningRole role,ProviderSettingsSnapshot snapshot) {
        if (Minecraft.getInstance().screen instanceof ProviderSettingsScreen screen && screen.role==role) {
            screen.receive(snapshot);
        }
    }

    public static void receiveActivity(List<AgentActivityEvent> events) {
        if (Minecraft.getInstance().screen instanceof AgentActivityScreen screen) {
            screen.receive(events);
        }
    }
}
