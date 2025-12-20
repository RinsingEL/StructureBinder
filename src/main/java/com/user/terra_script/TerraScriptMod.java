package com.user.terra_script;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(TerraScriptMod.MODID)
public class TerraScriptMod {
    public static final String MODID = "terra_script";
    public static final Logger LOGGER = LogUtils.getLogger();

    public TerraScriptMod() {
        @SuppressWarnings("removal") IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        // 2. 注册生命周期事件
        modEventBus.addListener(this::commonSetup);
        // 3. 注册服务器/游戏内事件
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("TerraScript WorldGen Initiated based on Fixed Rules.");
    }
}
