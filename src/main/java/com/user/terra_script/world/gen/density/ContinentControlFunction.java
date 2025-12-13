package com.user.terra_script.world.gen.density;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.user.terra_script.util.ContinentTool;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;

public record ContinentControlFunction(DensityFunction wrapped) implements DensityFunction {

    public static final KeyDispatchDataCodec<ContinentControlFunction> CODEC = KeyDispatchDataCodec.of(
            MapCodec.unit(new ContinentControlFunction(new DensityFunction() {
                @Override public double compute(FunctionContext context) { return 0; }
                @Override public void fillArray(double[] a, ContextProvider c) {}
                @Override public DensityFunction mapAll(Visitor v) { return this; }
                @Override public double minValue() { return 0; }
                @Override public double maxValue() { return 0; }
                @Override public KeyDispatchDataCodec<? extends DensityFunction> codec() { return KeyDispatchDataCodec.of(MapCodec.unit(this)); }
            }))
    );

    @Override
    public double compute(FunctionContext context) {
        // 1. 获取原版计算出的随机大陆性 (Original Continentalness)
        // 我们需要它来保留一点点随机性，或者完全覆盖它
        double originalC = wrapped.compute(context);

        // 2. 获取你的画板平滑因子 (0.0 = 海, 1.0 = 陆)
        double landFactor = ContinentTool.getLandFactor(context.blockX(), context.blockZ());

        // ==============================================================
        // 3. 映射逻辑
        // ==============================================================

        // --- A. 海洋区域 (Factor < 0.2) ---
        // 强制返回深海数值 (-1.2 ~ -0.8)
        if (landFactor < 0.2) {
            // 返回一个极低的数值，确保生成深海
            return -1.1;
        }

        // --- B. 陆地区域 (Factor > 0.8) ---
        // 我们希望这里是 "大陆"，但具体是 "海岸" 还是 "远内陆"？
        // 我们可以根据 originalC 稍微波动一下，让它不那么死板，但底线是必须 > 0.1
        else if (landFactor > 0.8) {
            // Math.max(originalC, 0.3) 的意思是：
            // 如果原版随到了高山(0.8)，那就保留高山(0.8)。
            // 如果原版随到了海洋(-0.5)，强制把它抬升到丘陵(0.3)。
            // 这样保证了 "一定是陆地"，同时保留了原版的高山潜力。
            return Math.max(originalC, 0.3);
        }

        // --- C. 过渡区域 (0.2 ~ 0.8) ---
        // 进行平滑插值，从 -1.1 过渡到 0.3
        // 这里的 landFactor 已经被 ContinentTool 平滑过了，所以结果也是平滑的
        else {
            // 归一化 delta (0.0 ~ 1.0)
            double delta = (landFactor - 0.2) / (0.8 - 0.2);
            return Mth.lerp(delta, -1.1, 0.3);
        }
    }

    // ... 标准方法 ...
    @Override public void fillArray(double[] densities, ContextProvider contextProvider) { contextProvider.fillAllDirectly(densities, this); }
    @Override public DensityFunction mapAll(Visitor visitor) { return new ContinentControlFunction(wrapped.mapAll(visitor)); }
    @Override public double minValue() { return -2.0; }
    @Override public double maxValue() { return 2.0; }
    @Override public KeyDispatchDataCodec<? extends DensityFunction> codec() { return CODEC; }
}