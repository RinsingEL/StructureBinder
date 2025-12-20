package com.user.terra_script.init;

import com.mojang.blaze3d.platform.InputConstants;
import com.user.terra_script.client.screen.map.StandaloneMapScreen;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = "terra_script", value = Dist.CLIENT)
public class KeyInit {

    public static final KeyMapping OPEN_MAP_KEY = new KeyMapping(
            "key.terra_script.open_map",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F7, // 默认 F7
            "key.categories.terra_script"
    );

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN_MAP_KEY);
    }

    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        if (OPEN_MAP_KEY.consumeClick()) {
            // 打开界面，context 传 null 表示在游戏内
            net.minecraft.client.Minecraft.getInstance().setScreen(new StandaloneMapScreen(null, null));
        }
    }
}