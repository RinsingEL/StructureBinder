package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.google.gson.JsonObject;
import com.mojang.datafixers.util.Either;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

final class BoundedJigsawTemplateInspectorTest {
    @Test
    void locksVanillaPoolElementFieldNamesWithoutInitializingMinecraftRegistries() throws Exception {
        Class<?> single = Class.forName(
                "net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement", false,
                Thread.currentThread().getContextClassLoader());
        Class<?> list = Class.forName(
                "net.minecraft.world.level.levelgen.structure.pools.ListPoolElement", false,
                Thread.currentThread().getContextClassLoader());

        assertNotNull(findField(single, "template"));
        assertNotNull(findField(list, "elements"));
    }

    @Test
    void extractsTemplateIdsFromSingleAndNestedListLikeObjects() {
        FakeSingle first = new FakeSingle("minecraft:village/plains/houses/plains_small_house_1");
        FakeSingle second = new FakeSingle("minecraft:village/plains/houses/plains_mason_1");
        FakeList list = new FakeList(List.of(first, second));

        List<ResourceLocation> singleIds = BoundedJigsawTemplateInspector.templateIds(first);
        assertEquals(1, singleIds.size());
        assertEquals("minecraft:village/plains/houses/plains_small_house_1", singleIds.get(0).toString());

        List<ResourceLocation> listIds = BoundedJigsawTemplateInspector.templateIds(list);
        assertEquals(2, listIds.size());
        assertEquals("minecraft:village/plains/houses/plains_small_house_1", listIds.get(0).toString());
        assertEquals("minecraft:village/plains/houses/plains_mason_1", listIds.get(1).toString());
    }

    @Test
    void convertsJigsawNbtIntoConnectorRefsWithoutRegistryBootstrap() {
        CompoundTag nbt = new CompoundTag();
        nbt.putString("name", "minecraft:street");
        nbt.putString("target", "minecraft:street");
        nbt.putString("pool", "minecraft:village/plains/streets");
        nbt.putString("final_state", "minecraft:air");
        nbt.putString("joint", "rollable");

        JsonObject connector = BoundedJigsawTemplateInspector.connectorFromNbt(
                Direction.NORTH, new BlockPos(13, 65, -4), new BlockPos(10, 64, -8), nbt, 0);

        assertEquals("jigsaw_north_3_1_4", connector.get("connectorId").getAsString());
        assertEquals("north", connector.get("front").getAsString());
        assertEquals("minecraft:street", connector.get("name").getAsString());
        assertEquals("minecraft:village/plains/streets", connector.get("pool").getAsString());
        assertEquals(3, connector.getAsJsonObject("localBlock").get("x").getAsInt());
        assertEquals(1, connector.getAsJsonObject("localBlock").get("y").getAsInt());
        assertEquals(4, connector.getAsJsonObject("localBlock").get("z").getAsInt());
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static final class FakeSingle {
        @SuppressWarnings("unused")
        private final Either<ResourceLocation, Object> template;

        private FakeSingle(String id) {
            this.template = Either.left(Objects.requireNonNull(ResourceLocation.tryParse(id)));
        }
    }

    private static final class FakeList {
        @SuppressWarnings("unused")
        private final List<Object> elements;

        private FakeList(List<Object> elements) {
            this.elements = elements;
        }
    }
}
