package com.rinsing.geomantia.client;

import com.rinsing.geomantia.platform.network.ProviderNetwork;
import com.rinsing.geomantia.systems.provider.application.ProviderSettingsSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

public final class ProviderSettingsClient {
    private ProviderSettingsClient() {
    }

    public static void open(Screen parent) {
        Minecraft.getInstance().setScreen(new ProviderSettingsScreen(parent));
    }

    public static void request() {
        ProviderNetwork.requestSettings();
    }

    public static void receive(ProviderSettingsSnapshot snapshot) {
        if (Minecraft.getInstance().screen instanceof ProviderSettingsScreen screen) {
            screen.receive(snapshot);
        }
    }
}
