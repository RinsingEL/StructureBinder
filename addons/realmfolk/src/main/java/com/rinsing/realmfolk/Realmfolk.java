package com.rinsing.realmfolk;

import com.mojang.logging.LogUtils;
import com.rinsing.realmfolk.entity.CitizenEntity;
import com.rinsing.realmfolk.net.Messages;
import com.rinsing.realmfolk.server.SocietyRuntime;
import net.minecraft.world.entity.*;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.*;
import org.slf4j.Logger;

@Mod(Realmfolk.ID)
public final class Realmfolk {
    public static final String ID="realmfolk";
    public static final Logger LOG=LogUtils.getLogger();
    private static final DeferredRegister<EntityType<?>> ENTITIES=DeferredRegister.create(ForgeRegistries.ENTITY_TYPES,ID);
    public static final RegistryObject<EntityType<CitizenEntity>> CITIZEN=ENTITIES.register("citizen",()->EntityType.Builder
        .of(CitizenEntity::new,MobCategory.CREATURE).sized(.6f,1.95f).clientTrackingRange(10).build("realmfolk:citizen"));
    public Realmfolk() {
        IEventBus bus=FMLJavaModLoadingContext.get().getModEventBus();
        ENTITIES.register(bus);
        bus.addListener(this::attributes);
        Messages.register();
        MinecraftForge.EVENT_BUS.register(SocietyRuntime.class);
    }
    private void attributes(EntityAttributeCreationEvent event) { event.put(CITIZEN.get(),CitizenEntity.attributes().build()); }
}
