package com.user.terra_script.client;

import com.user.terra_script.client.data.ScanResultHolder;
import com.user.terra_script.client.screen.map.StandaloneMapScreen;
import com.user.terra_script.config.StructurePlan;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class ClientSetup {
    private static final int DEV_AUTO_LOAD_SCREEN_STABLE_TICKS = 20;
    private static boolean registered = false;
    private static boolean attemptedDevAutoLoad = false;
    private static String lastObservedScreenName = "";
    private static int observedScreenTicks = 0;

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        MinecraftForge.EVENT_BUS.register(ClientSetup.class);
        System.out.println("[TerraScript] ClientSetup registered on Forge event bus.");
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || attemptedDevAutoLoad) {
            return;
        }
        String targetWorld = decodeDevAutoLoadWorld();
        if (targetWorld.isBlank()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null || minecraft.getSingleplayerServer() != null) {
            attemptedDevAutoLoad = true;
            return;
        }
        if (minecraft.screen == null) {
            lastObservedScreenName = "";
            observedScreenTicks = 0;
            return;
        }
        String screenName = minecraft.screen.getClass().getName();
        if (!screenName.equals(lastObservedScreenName)) {
            lastObservedScreenName = screenName;
            observedScreenTicks = 0;
            System.out.println("[TerraScript] Dev auto load observing screen: " + screenName);
        }
        observedScreenTicks++;
        if (observedScreenTicks < DEV_AUTO_LOAD_SCREEN_STABLE_TICKS) {
            return;
        }
        attemptedDevAutoLoad = true;
        System.out.println("[TerraScript] Dev auto load world from screen " + screenName + ": " + targetWorld);
        minecraft.execute(() -> minecraft.createWorldOpenFlows().loadLevel(minecraft.screen, targetWorld));
    }

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

    private static String decodeDevAutoLoadWorld() {
        String encoded = System.getProperty("terra_script.devAutoLoadWorldBase64", "").trim();
        if (!encoded.isBlank()) {
            try {
                return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8).trim();
            } catch (IllegalArgumentException ignored) {
            }
        }
        return System.getProperty("terra_script.devAutoLoadWorld", "").trim();
    }
}
