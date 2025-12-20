package com.user.terra_script.client;

import com.user.terra_script.client.screen.map.StandaloneMapScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "terra_script", value = Dist.CLIENT) // 记得改成你的 modid
public class ClientSetup {

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof CreateWorldScreen screen) {
            // 在左上角加一个显眼的按钮
            event.addListener(Button.builder(Component.literal("[ Satellite Map ]"), b -> {
                try {
                    // 1.20.1 获取生成器上下文的关键代码
                    var context = screen.getUiState().getSettings();

                    // 打开独立界面
                    screen.getMinecraft().setScreen(new StandaloneMapScreen(screen, context));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }).bounds(10, 10, 100, 20).build());
        }
    }
}