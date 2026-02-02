package com.user.terra_script.event;

import com.user.terra_script.client.data.ScanResultHolder;
import com.user.terra_script.client.screen.map.StandaloneMapScreen;
import com.user.terra_script.config.StructurePlan;
import com.user.terra_script.scan.SatelliteScanner;
import com.user.terra_script.util.ScanDataIO;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "terra_script", value = Dist.CLIENT)
public class WorldEntryHandler {

    private static boolean pendingOpenMap = false;
    private static boolean hasDesigned = false;

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        // 1. 获取内置服务端 (仅单人游戏有效)
        var server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return;

        // 2. 获取服务端主世界 (用来拿种子)
        ServerLevel level = server.overworld();
        long currentSeed = level.getSeed();
        ScanDataIO.setWorldRoot(server.getWorldPath(LevelResource.ROOT));
        ScanDataIO.loadInto(ScanResultHolder.get());

        // 3. 检查种子是否变化 (如果是新存档，清空旧缓存)
        if (ScanResultHolder.get().seedUsed != 0 && ScanResultHolder.get().seedUsed != currentSeed) {
            System.out.println("[TerraScript] New world detected (Seed changed). Clearing cache.");
            ScanResultHolder.get().clearAll();
            // 同时清空结构计划
            StructurePlan.get().clear();
            hasDesigned = false; // 重置设计标记
        }

        // 4. 判断是否需要进入上帝模式
        // 条件：世界时间 < 20 (刚创建) 且 还没设计过
        if (level.getGameTime() < 20 && !hasDesigned) {

            // 获取 ServerPlayer 以便修改模式 (绕过作弊锁)
            ServerPlayer serverPlayer = server.getPlayerList().getPlayer(event.getEntity().getUUID());

            if (serverPlayer != null) {
                // 强制设为旁观者
                serverPlayer.setGameMode(GameType.SPECTATOR);
                // 传送到高空 (320层)
                serverPlayer.teleportTo(0, 320, 0);

                event.getEntity().displayClientMessage(Component.literal("§e[TerraScript] Initializing Architect Mode..."), false);

                // 标记下一帧打开 GUI
                pendingOpenMap = true;
            }
        }

        com.user.terra_script.world.city.CityManager.get().reload();
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        // 延迟一帧打开 GUI，防止在登陆瞬间打开导致被关闭
        if (event.phase == TickEvent.Phase.END && pendingOpenMap) {
            if (Minecraft.getInstance().player != null) {
                pendingOpenMap = false;
                hasDesigned = true; // 标记本次游戏已触发过

                // 打开设计界面 (context 传 null，强制走游戏内扫描逻辑)
                Minecraft.getInstance().setScreen(new StandaloneMapScreen(null, null));
            }
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        // 当服务端世界卸载时，立刻停止扫描
        if (event.getLevel() instanceof ServerLevel) {
            SatelliteScanner.stopScanning();
        }
    }
}
