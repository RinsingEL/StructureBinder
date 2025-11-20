package com.user.terra_script.init;

import com.mojang.serialization.Codec;
import com.user.terra_script.world.biomesource.FixedBiomeSource;
import com.user.terra_script.TerraScriptMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public class ModBiomeSources {
    // 创建延迟注册器，对应原版的 BIOME_SOURCE 注册表
    public static final DeferredRegister<Codec<? extends BiomeSource>> BIOME_SOURCES =
            DeferredRegister.create(Registries.BIOME_SOURCE, TerraScriptMod.MODID);

    // 注册我们的 FixedBiomeSource
    // 这样在 JSON 配置中就可以使用 "terra_script:fixed_biome_source" 来引用它
    public static final RegistryObject<Codec<FixedBiomeSource>> FIXED_BIOME_SOURCE =
            BIOME_SOURCES.register("fixed_biome_source", () -> FixedBiomeSource.CODEC);

    public static void register(IEventBus eventBus) {
        BIOME_SOURCES.register(eventBus);
    }
}