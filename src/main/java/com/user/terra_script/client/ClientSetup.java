package com.user.terra_script.client;

import com.user.terra_script.client.data.ScanResultHolder;
import com.user.terra_script.client.screen.map.StandaloneMapScreen;
import com.user.terra_script.config.StructurePlan;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "terra_script", value = Dist.CLIENT)
public class ClientSetup {

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof CreateWorldScreen screen) {

            // 添加 "Design World" 按钮
            // 放在左上角
            event.addListener(Button.builder(Component.literal("Design / Reset Cache"), b -> {

                // 1. 获取上下文 (虽然我们现在主要靠进服扫描，但保留 context 引用也没坏处)
                WorldCreationContext context = screen.getUiState().getSettings();

                // 2. 清空缓存 (关键！)
                // 玩家点击这个按钮，意图是“我要设计一个新世界”
                // 所以我们把上一局的数据清掉，防止混淆
                ScanResultHolder.get().clearAll();
                StructurePlan.get().clear();

                System.out.println("[TerraScript] Cache cleared via GUI button.");

                // 3. 打开界面
                // 此时界面是空的，但可以让玩家确认状态
                screen.getMinecraft().setScreen(new StandaloneMapScreen(screen, context));

            }).bounds(20, 20, 150, 20).build());
        }
    }
}