package com.rinsing.geomantia.platform.registry;

import com.rinsing.geomantia.GeomantiaMod;
import com.rinsing.geomantia.systems.realm_planning.adapter.minecraft.AdventurerMapItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class GeomantiaItems {
    private static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, GeomantiaMod.MOD_ID);

    public static final RegistryObject<Item> ADVENTURER_MAP = ITEMS.register(
            "adventurer_map", () -> new AdventurerMapItem(new Item.Properties().stacksTo(1)));

    private GeomantiaItems() {
    }

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
        modEventBus.addListener(GeomantiaItems::addCreativeTabContents);
    }

    private static void addCreativeTabContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(ADVENTURER_MAP);
        }
    }
}
