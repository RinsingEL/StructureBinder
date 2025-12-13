package com.user.terra_script.world.gen.density;

import com.mojang.serialization.MapCodec;
import com.user.terra_script.util.ContinentTool;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;

public record IslandDensityFunction(DensityFunction wrapped) implements DensityFunction {

    public static final KeyDispatchDataCodec<IslandDensityFunction> CODEC = KeyDispatchDataCodec.of(
            MapCodec.unit(new IslandDensityFunction(new DensityFunction() {
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
        double original = wrapped.compute(context);
        int y = context.blockY();

        // 保护基岩层
        if (y < -54) return original;

        // 禁止浮空
        if (y >= 200) {
            return -1.0;
        }

        // 获取平滑后的大陆因子 (0.0=海, 1.0=陆)
        double landFactor = ContinentTool.getLandFactor(context.blockX(), context.blockZ());

        // --- 核心逻辑 ---
        if (landFactor < 0.1) {
            // [完全海洋]
            // 降低地形。-1.2 是一个很强的值，足以把高山压成深海
            // 防止压穿海底：如果 Y 已经很低了(比如 -20)，就少压一点
            if (y < -20) return original;
            return original - 1.2;
        }
        
        return original;
    }

    // 标准方法保持不变
    @Override public void fillArray(double[] densities, ContextProvider contextProvider) { contextProvider.fillAllDirectly(densities, this); }
    @Override public DensityFunction mapAll(Visitor visitor) { return new IslandDensityFunction(wrapped.mapAll(visitor)); }
    @Override public double minValue() { return -100.0; }
    @Override public double maxValue() { return 100.0; }
    @Override public KeyDispatchDataCodec<? extends DensityFunction> codec() { return CODEC; }
}
