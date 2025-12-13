package com.user.terra_script.world.gen.density;

import com.mojang.serialization.MapCodec;
import com.user.terra_script.util.ContinentTool;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

public record IslandFluidFunction(DensityFunction wrapped) implements DensityFunction {

    public static final KeyDispatchDataCodec<IslandFluidFunction> CODEC = KeyDispatchDataCodec.of(
            MapCodec.unit(new IslandFluidFunction(new DensityFunction() {
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

        // 使用工具类判断海陆
        double landFactor = ContinentTool.getLandFactor(context.blockX(), context.blockZ());

        // [海洋区域] (Factor < 0.5)
        // 强制把 "淹没倾向" 设为最大，告诉含水层这里必须全是水
        if (landFactor < 0.5) {
            return 1.0;
        }

        // [陆地区域]
        // 保持原版逻辑，这样陆地地下会有正常的干洞和地下河
        return original;
    }

    @Override public void fillArray(double[] densities, ContextProvider contextProvider) { contextProvider.fillAllDirectly(densities, this); }
    @Override public DensityFunction mapAll(Visitor visitor) { return new IslandFluidFunction(wrapped.mapAll(visitor)); }
    @Override public double minValue() { return -1.0; }
    @Override public double maxValue() { return 1.0; }
    @Override public KeyDispatchDataCodec<? extends DensityFunction> codec() { return CODEC; }
}