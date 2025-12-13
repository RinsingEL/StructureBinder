package com.user.terra_script.client;

import com.user.terra_script.TerraScriptMod;
import com.user.terra_script.config.WorldProjectData;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraft.core.registries.Registries; // 确保引入这个

import java.util.List;

@Mod.EventBusSubscriber(modid = TerraScriptMod.MODID, value = Dist.CLIENT)
public class ClientSetup {

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof CreateWorldScreen screen) {

            event.addListener(Button.builder(Component.literal("Terra Script Settings"), b -> {

                // 【新增】获取当前世界生成上下文中的所有群系
                try {
                    var registryAccess = screen.getUiState().getSettings().worldgenLoadContext();
                    WorldProjectData.availableBiomes = registryAccess.lookupOrThrow(Registries.BIOME)
                            .listElementIds()
                            .map(key -> key.location().toString())
                            .sorted()
                            .toList();
                } catch (Exception e) {
                    e.printStackTrace();
                    // 如果获取失败，可以加一些默认的
                    WorldProjectData.availableBiomes = List.of("minecraft:plains", "minecraft:ocean");
                }

                WorldProjectData.load();
                event.getScreen().getMinecraft().setScreen(new WorldEditorScreen(screen));
            }).bounds(screen.width / 2 + 105, 10, 100, 20).build());
        }
    }
}