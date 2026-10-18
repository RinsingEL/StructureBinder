package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.rinsing.geomantia.GeomantiaMod;
import com.rinsing.geomantia.systems.city.infrastructure.world.landuse.CityLandUseWorldgenRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.event.entity.living.MobSpawnEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Natural vanilla population is suppressed; authored, commanded and spawner populations retain their rules. */
@Mod.EventBusSubscriber(modid = GeomantiaMod.MOD_ID)
public final class CityNaturalSpawnPolicy {
    public static final ForgeConfigSpec SPEC;
    private static final ForgeConfigSpec.BooleanValue ENABLED;
    private static final ForgeConfigSpec.IntValue MONSTER_BUFFER;
    static {
        var builder = new ForgeConfigSpec.Builder();
        ENABLED = builder.comment("Suppress natural vanilla mobs inside managed city land.").define("enabled", true);
        MONSTER_BUFFER = builder.comment("Additional horizontal radius for natural monsters around managed city land.")
                .defineInRange("monsterBufferBlocks", 128, 0, 512);
        SPEC = builder.build();
    }
    public static boolean natural(MobSpawnType reason) {
        return reason == MobSpawnType.NATURAL || reason == MobSpawnType.CHUNK_GENERATION
                || reason == MobSpawnType.PATROL || reason == MobSpawnType.REINFORCEMENT;
    }
    @SubscribeEvent
    public static void check(MobSpawnEvent.PositionCheck event) {
        if (!ENABLED.get() || !natural(event.getSpawnType())) return;
        var mob = event.getEntity();
        if (!BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).getNamespace().equals("minecraft")) return;
        var category = mob.getType().getCategory();
        if (category == MobCategory.MISC) return;
        String dimension = event.getLevel().getLevel().dimension().location().toString();
        int radius = category == MobCategory.MONSTER ? MONSTER_BUFFER.get() : 0;
        var at = mob.blockPosition();
        if (CityLandUseWorldgenRegistry.generationMask().nearColumn(dimension, at.getX(), at.getZ(), radius)
                || CityReservationMaskRegistry.spawnMask().nearColumn(dimension, at.getX(), at.getZ(), radius)
                || CityReservationMaskRegistry.generationMask().nearColumn(dimension, at.getX(), at.getZ(), radius))
            event.setResult(Event.Result.DENY);
    }
}
