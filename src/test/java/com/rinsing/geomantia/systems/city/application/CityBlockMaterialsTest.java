package com.rinsing.geomantia.systems.city.application;
import com.google.gson.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
class CityBlockMaterialsTest {
    private final CityBlockMaterials.Registry registry=new CityBlockMaterials.Registry(){
        private final java.util.Map<String,CityBlockMaterials.BlockInfo> entries=entries();
        private java.util.Map<String,CityBlockMaterials.BlockInfo> entries(){
            var result=new java.util.HashMap<String,CityBlockMaterials.BlockInfo>();
            for(int i=0;i<36;i++)result.put("pack:stone_"+i+"_slab",new CityBlockMaterials.BlockInfo("pack:stone_"+i+"_slab","Stone slab "+i,"forge:slabs",CityBlockMaterials.Shape.SLAB,true));
            result.put("minecraft:stone_brick_slab",new CityBlockMaterials.BlockInfo("minecraft:stone_brick_slab","Stone brick slab","",CityBlockMaterials.Shape.SLAB,true));
            result.put("minecraft:stone",new CityBlockMaterials.BlockInfo("minecraft:stone","Stone","",CityBlockMaterials.Shape.BLOCK,true));
            result.put("minecraft:sunflower",new CityBlockMaterials.BlockInfo("minecraft:sunflower","Sunflower","",CityBlockMaterials.Shape.MULTIBLOCK,false));return result;
        }
        public java.util.Set<String> ids(){return entries.keySet();}
        public CityBlockMaterials.BlockInfo get(String id){return entries.get(id);}
    };
    @Test void registryValidationDistinguishesSurfaceStairAndMissingBlock(){
        assertDoesNotThrow(()->CityBlockMaterials.validateBlock("minecraft:stone_brick_slab","roadSurface","road",registry));
        var wrong=assertThrows(IllegalArgumentException.class,()->CityBlockMaterials.validateBlock("minecraft:stone","roadStair","roads.main.roadStair",registry));
        assertTrue(wrong.getMessage().contains("requires a stair"));
        assertTrue(wrong.getMessage().contains("roads.main.roadStair"));
        assertThrows(IllegalArgumentException.class,()->CityBlockMaterials.validateBlock("absent:road","ground","ground",registry));
        assertThrows(IllegalArgumentException.class,()->CityBlockMaterials.validateBlock("minecraft:sunflower","cropBlockId","flowers",registry));
    }
    @Test void searchReturnsPagesAndDoesNotSendImagesOrWholeRegistry(){
        var query=JsonParser.parseString("{\"slot\":\"roadSurface\",\"query\":\"slab\"}").getAsJsonObject();
        var first=CityBlockMaterials.query(query,null,registry);
        assertEquals(12,first.getAsJsonArray("candidates").size());assertTrue(first.get("hasMore").getAsBoolean());
        assertFalse(first.has("imageEvidence"));query.addProperty("page",1);var second=CityBlockMaterials.query(query,null,registry);
        assertNotEquals(first.getAsJsonArray("candidates").get(0),second.getAsJsonArray("candidates").get(0));
        assertEquals("basic_checks_passed_not_placement_verified",first.getAsJsonArray("candidates").get(0).getAsJsonObject().get("compatibility").getAsString());
    }
}
