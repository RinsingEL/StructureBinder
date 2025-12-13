package com.user.terra_script.mixin;

import com.user.terra_script.world.gen.density.ContinentControlFunction;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.user.terra_script.world.gen.density.IslandDensityFunction;
import com.user.terra_script.world.gen.density.IslandFluidFunction;

@Mixin(NoiseBasedChunkGenerator.class)
public class NoiseChunkGeneratorMixin {

    @Shadow(aliases = {"settings", "f_188806_"}, remap = false)
    @Final
    @Mutable
    private Holder<NoiseGeneratorSettings> settings;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void injectIslandModification(BiomeSource p_255826_, Holder<NoiseGeneratorSettings> p_256199_, CallbackInfo ci) {

        if (this.settings == null || !this.settings.isBound()) return;

        NoiseGeneratorSettings oldSettings = this.settings.value();
        NoiseRouter originalRouter = oldSettings.noiseRouter();

        // 1. 地形形状修改 (控制石头)
        IslandDensityFunction newFinalDensity = new IslandDensityFunction(originalRouter.finalDensity());

        // 2. 含水层修改 (控制水) -> 解决 "空气峡谷" 的关键！
        IslandFluidFunction newFloodedness = new IslandFluidFunction(originalRouter.fluidLevelFloodednessNoise());

        // 3. 【核心修改】控制大陆性 (Continents)
        // 我们把原版的 "continents" 噪声替换为我们的 "ContinentControlFunction"
        // 这样 FinalDensity 会根据这个值自动决定是生成山脉还是海洋，并且会自动处理天空高度限制 (消除飞天BUG)
        ContinentControlFunction newContinents = new ContinentControlFunction(originalRouter.continents());

        // 3. 构建新的 Router
        NoiseRouter modifiedRouter = new NoiseRouter(
                originalRouter.barrierNoise(),
                originalRouter.fluidLevelFloodednessNoise(),//newFloodedness, // <--- 这里替换了原来的 fluidLevelFloodednessNoise
                originalRouter.fluidLevelSpreadNoise(),
                originalRouter.lavaNoise(),
                originalRouter.temperature(),
                originalRouter.vegetation(),
                newContinents,
                originalRouter.erosion(),
                originalRouter.depth(),
                originalRouter.ridges(),
                originalRouter.initialDensityWithoutJaggedness(),
                originalRouter.finalDensity(), // newFinalDensity, // <--- 这里替换了 finalDensity
                originalRouter.veinToggle(),
                originalRouter.veinRidged(),
                originalRouter.veinGap()
        );

        NoiseGeneratorSettings newSettings = new NoiseGeneratorSettings(
                oldSettings.noiseSettings(),
                oldSettings.defaultBlock(),
                oldSettings.defaultFluid(),
                modifiedRouter, // 注入
                oldSettings.surfaceRule(),
                oldSettings.spawnTarget(),
                oldSettings.seaLevel(),
                oldSettings.disableMobGeneration(),
                oldSettings.aquifersEnabled(),
                oldSettings.oreVeinsEnabled(),
                oldSettings.useLegacyRandomSource()
        );

        this.settings = Holder.direct(newSettings);
        System.out.println("[TerrainTest] 地形与含水层逻辑已注入！");
    }
}